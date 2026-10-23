"use strict";
// Minimal dependency-free client for cc-harness. Everything is rendered with
// DOM APIs / escaped strings, nothing is fetched from third parties.

const $ = (id) => document.getElementById(id);
const MODES = {
  manual: "逐项询问",
  acceptEdits: "自动接受编辑",
  plan: "计划模式",
  auto: "自动分类",
  dontAsk: "不询问(拒绝未授权)",
  bypassPermissions: "全部放行",
};
const STATUS = { stopped: "进程未运行", idle: "空闲", busy: "运行中", awaiting_permission: "等待授权" };

let me = null;
let cur = null; // current session view
let es = null;
let sessions = [];
let tools = {}; // tool_use_id -> result element
let perms = {}; // request id -> card element
let live = null; // {el, text}
let renderQueued = false;

// ---------- helpers ----------
async function api(method, path, body) {
  const r = await fetch(path, {
    method,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
  });
  let data = null;
  try { data = await r.json(); } catch (_) {}
  if (r.status === 401 && path !== "/api/login") showLogin();
  if (!r.ok) throw Object.assign(new Error((data && data.error) || r.statusText), { status: r.status });
  return data;
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function esc(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function inline(s) {
  s = esc(s);
  const codes = [];
  s = s.replace(/`([^`\n]+)`/g, (_, c) => { codes.push(c); return "\u0000" + (codes.length - 1) + "\u0000"; });
  s = s.replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>");
  s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>");
  s = s.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
  return s.replace(/\u0000(\d+)\u0000/g, (_, i) => "<code>" + codes[+i] + "</code>");
}

// Small markdown subset: fences, headings, lists, paragraphs, inline code/bold/em/links.
function md(src) {
  const out = [];
  const lines = src.replace(/\r/g, "").split("\n");
  let i = 0;
  while (i < lines.length) {
    const ln = lines[i];
    const fence = ln.match(/^```(.*)$/);
    if (fence) {
      const buf = [];
      i++;
      while (i < lines.length && !/^```\s*$/.test(lines[i])) buf.push(lines[i++]);
      i++;
      out.push("<pre><code>" + esc(buf.join("\n")) + "</code></pre>");
      continue;
    }
    const h = ln.match(/^(#{1,3})\s+(.*)$/);
    if (h) { out.push("<h" + h[1].length + ">" + inline(h[2]) + "</h" + h[1].length + ">"); i++; continue; }
    if (/^\s*[-*]\s+/.test(ln) || /^\s*\d+\.\s+/.test(ln)) {
      const ordered = /^\s*\d+\./.test(ln);
      const items = [];
      while (i < lines.length && (/^\s*[-*]\s+/.test(lines[i]) || /^\s*\d+\.\s+/.test(lines[i]))) {
        items.push("<li>" + inline(lines[i].replace(/^\s*([-*]|\d+\.)\s+/, "")) + "</li>");
        i++;
      }
      out.push((ordered ? "<ol>" : "<ul>") + items.join("") + (ordered ? "</ol>" : "</ul>"));
      continue;
    }
    if (ln.trim() === "") { i++; continue; }
    const para = [];
    while (i < lines.length && lines[i].trim() !== "" && !/^```/.test(lines[i]) && !/^#{1,3}\s/.test(lines[i]) &&
      !/^\s*[-*]\s+/.test(lines[i]) && !/^\s*\d+\.\s+/.test(lines[i])) para.push(lines[i++]);
    out.push("<p>" + inline(para.join("\n")).replace(/\n/g, "<br>") + "</p>");
  }
  return out.join("");
}

function nearBottom() {
  const l = $("log");
  return l.scrollHeight - l.scrollTop - l.clientHeight < 80;
}
function add(node) {
  const stick = nearBottom();
  $("log").appendChild(node);
  if (stick) $("log").scrollTop = $("log").scrollHeight;
  return node;
}

function summarize(name, input) {
  if (!input) return "";
  const v = input.command || input.file_path || input.path || input.pattern || input.url || input.query || input.description || input.prompt;
  if (typeof v === "string") return v.replace(/\s+/g, " ").slice(0, 160);
  return JSON.stringify(input).slice(0, 160);
}

// ---------- session list ----------
async function loadSessions() {
  try {
    sessions = (await api("GET", "/api/sessions")).sessions;
  } catch (_) { return; }
  const ul = $("list");
  ul.textContent = "";
  for (const s of sessions) {
    const li = el("li", s.id === (cur && cur.id) ? "cur" : "");
    li.appendChild(el("div", "t", s.title || "(未命名)"));
    li.appendChild(el("div", "s", (STATUS[s.status] || s.status) + " · " + new Date(s.updatedAt).toLocaleString()));
    li.onclick = () => { openSession(s.id); document.body.classList.remove("menu"); };
    ul.appendChild(li);
  }
  if (cur) {
    const v = sessions.find((s) => s.id === cur.id);
    if (v) { cur = v; renderBar(); }
  }
}

function setStatus(st) {
  const c = $("status");
  c.textContent = STATUS[st] || st;
  c.className = "chip " + st;
  $("btn-int").disabled = !(st === "busy" || st === "awaiting_permission");
  $("btn-stop").disabled = st === "stopped";
}

function renderBar() {
  $("title").textContent = cur.title || "(未命名)";
  $("meta").textContent = cur.cwd + (cur.model ? " · " + cur.model : "") + " · " + cur.id;
  $("mode").value = cur.permissionMode;
  setStatus(cur.status);
}

// ---------- session view ----------
function closeStream() {
  if (es) { es.close(); es = null; }
}

function openSession(id) {
  closeStream();
  const s = sessions.find((x) => x.id === id);
  if (!s) return;
  cur = s;
  tools = {}; perms = {}; live = null;
  $("log").textContent = "";
  $("empty").hidden = true;
  renderBar();
  loadSessions();
  location.hash = id;

  es = new EventSource("/api/sessions/" + id + "/events");
  es.onmessage = (m) => {
    let ev;
    try { ev = JSON.parse(m.data); } catch (_) { return; }
    handle(ev.data);
  };
  es.onerror = async () => {
    // EventSource retries by itself; only check whether our login expired.
    try { await api("GET", "/api/me"); } catch (_) {}
  };
}

function handle(d) {
  if (!d) return;
  switch (d.type) {
    case "harness": return onHarness(d);
    case "system":
      if (d.subtype === "init") add(el("div", "note", "已连接 · " + (d.model || "") + " · " + (d.permissionMode || "")));
      return;
    case "assistant": return onAssistant(d);
    case "user": return onToolResults(d);
    case "control_request": return onPermission(d);
    case "result": return onResult(d);
    case "stream_event": return onDelta(d.event);
  }
}

function onHarness(d) {
  switch (d.kind) {
    case "user_message": {
      const m = add(el("div", "msg user", d.text));
      m.dataset.kind = "user";
      break;
    }
    case "status":
      if (cur) cur.status = d.status;
      setStatus(d.status);
      loadSessions();
      break;
    case "process_started": add(el("div", "note", d.resume ? "后台进程已启动 (恢复上下文)" : "后台进程已启动")); break;
    case "process_exit": add(el("div", "note", "后台进程已退出: " + d.reason)); break;
    case "error": add(el("div", "note bad", d.message)); break;
    case "mode_changed": if (cur) { cur.permissionMode = d.permissionMode; $("mode").value = d.permissionMode; } break;
    case "permission_resolved": {
      const c = perms[d.requestId];
      if (c) {
        c.classList.add("done");
        c.querySelectorAll("button,input").forEach((b) => (b.disabled = true));
        c.appendChild(el("div", "note", d.cancelled ? "已取消" : d.allow ? "已允许" + (d.remember ? " (本会话)" : "") : "已拒绝"));
      }
      break;
    }
  }
}

function dropLive() {
  if (live) { live.el.remove(); live = null; }
}

function onDelta(e) {
  if (!e) return;
  if (e.type === "message_start") { dropLive(); return; }
  if (e.type === "content_block_delta" && e.delta && e.delta.type === "text_delta") {
    if (!live) live = { el: add(el("div", "msg asst live")), text: "" };
    live.text += e.delta.text;
    if (!renderQueued) {
      renderQueued = true;
      requestAnimationFrame(() => {
        renderQueued = false;
        if (live) { const stick = nearBottom(); live.el.innerHTML = md(live.text); if (stick) $("log").scrollTop = $("log").scrollHeight; }
      });
    }
  }
}

function onAssistant(d) {
  dropLive();
  const blocks = (d.message && d.message.content) || [];
  for (const b of blocks) {
    if (b.type === "text" && b.text && b.text.trim()) {
      add(el("div", "msg asst")).innerHTML = md(b.text);
    } else if (b.type === "thinking" && b.thinking) {
      const det = el("details", "tool");
      det.appendChild(el("summary", "", "思考"));
      det.appendChild(el("pre", "", b.thinking));
      add(det);
    } else if (b.type === "tool_use") {
      const det = el("details", "tool");
      const sm = el("summary");
      sm.appendChild(el("b", "", b.name));
      sm.appendChild(el("span", "", summarize(b.name, b.input)));
      det.appendChild(sm);
      const body = el("div", "body");
      body.appendChild(el("pre", "", JSON.stringify(b.input, null, 2)));
      const res = el("div", "res");
      body.appendChild(res);
      det.appendChild(body);
      tools[b.id] = res;
      add(det);
    }
  }
}

function onToolResults(d) {
  const c = d.message && d.message.content;
  if (!Array.isArray(c)) return;
  for (const b of c) {
    if (b.type !== "tool_result") continue;
    const res = tools[b.tool_use_id];
    if (!res) continue;
    let t = b.content;
    if (Array.isArray(t)) t = t.map((x) => (x.type === "text" ? x.text : "[" + x.type + "]")).join("\n");
    t = String(t == null ? "" : t);
    if (t.length > 8000) t = t.slice(0, 8000) + "\n… (已截断 " + (t.length - 8000) + " 字符)";
    res.textContent = "";
    const pre = el("pre", b.is_error ? "bad" : "", t || "(无输出)");
    res.appendChild(pre);
  }
}

function onResult(d) {
  dropLive();
  if (d.is_error || (d.subtype && d.subtype !== "success")) {
    add(el("div", "note bad", "回合结束: " + (d.subtype || "error") + (d.result ? " · " + String(d.result).slice(0, 300) : "")));
  } else {
    const parts = [];
    if (d.duration_ms) parts.push((d.duration_ms / 1000).toFixed(1) + "s");
    if (typeof d.total_cost_usd === "number") parts.push("$" + d.total_cost_usd.toFixed(4));
    add(el("div", "note", parts.join(" · ")));
  }
}

function onPermission(d) {
  const r = d.request;
  if (!r || r.subtype !== "can_use_tool") return;
  const card = el("div", "perm");
  perms[d.request_id] = card;
  const ask = r.tool_name === "AskUserQuestion" && r.input && Array.isArray(r.input.questions);
  card.appendChild(el("h4", "", ask ? "Claude 需要你的回答" : "请求使用工具: " + r.tool_name));
  const post = (body) =>
    api("POST", "/api/sessions/" + cur.id + "/permissions/" + encodeURIComponent(d.request_id), body).catch((e) => {
      if (e.status === 404) { card.classList.add("done"); card.querySelectorAll("button,input").forEach((b) => (b.disabled = true)); }
      else add(el("div", "note bad", e.message));
    });

  if (ask) {
    const picks = [];
    r.input.questions.forEach((q, qi) => {
      const box = el("div", "q");
      box.appendChild(el("div", "", (q.header ? "[" + q.header + "] " : "") + q.question));
      (q.options || []).forEach((o) => {
        const lab = el("label");
        const inp = document.createElement("input");
        inp.type = q.multiSelect ? "checkbox" : "radio";
        inp.name = d.request_id + "-" + qi;
        inp.value = o.label;
        lab.appendChild(inp);
        lab.appendChild(document.createTextNode(" " + o.label + (o.description ? " — " + o.description : "")));
        box.appendChild(lab);
      });
      picks.push({ q, box });
      card.appendChild(box);
    });
    const acts = el("div", "acts");
    const ok = el("button", "primary", "提交");
    ok.onclick = () => {
      const answers = {};
      for (const { q, box } of picks) {
        answers[q.question] = [...box.querySelectorAll("input:checked")].map((i) => i.value).join(", ");
      }
      post({ allow: true, updatedInput: Object.assign({}, r.input, { answers }) });
    };
    const no = el("button", "", "拒绝");
    no.onclick = () => post({ allow: false });
    acts.append(ok, no);
    card.appendChild(acts);
  } else {
    card.appendChild(el("pre", "", JSON.stringify(r.input, null, 2)));
    const acts = el("div", "acts");
    const ok = el("button", "primary", "允许");
    ok.onclick = () => post({ allow: true });
    acts.appendChild(ok);
    if (r.permission_suggestions && r.permission_suggestions.length) {
      const always = el("button", "", "本会话总是允许");
      always.onclick = () => post({ allow: true, remember: true });
      acts.appendChild(always);
    }
    const no = el("button", "", "拒绝");
    no.onclick = () => post({ allow: false });
    acts.appendChild(no);
    card.appendChild(acts);
  }
  add(card);
}

// ---------- actions ----------
async function send() {
  const t = $("input").value;
  if (!t.trim() || !cur) return;
  $("input").value = "";
  try {
    await api("POST", "/api/sessions/" + cur.id + "/messages", { text: t });
  } catch (e) {
    $("input").value = t;
    add(el("div", "note bad", "发送失败: " + e.message));
  }
}

function showLogin() {
  closeStream();
  me = null;
  $("app").hidden = true;
  $("login").hidden = false;
}

async function enterApp() {
  $("login").hidden = true;
  $("app").hidden = false;
  $("who").textContent = me.user;
  const sel = $("mode"), nsel = $("n-mode");
  sel.textContent = ""; nsel.textContent = "";
  for (const [k, v] of Object.entries(MODES)) {
    if (k === "bypassPermissions" && !me.allowBypass) continue;
    sel.appendChild(new Option(v, k));
    nsel.appendChild(new Option(v, k));
  }
  await loadSessions();
  const id = location.hash.slice(1);
  if (id && sessions.some((s) => s.id === id)) openSession(id);
}

async function boot() {
  try {
    me = await api("GET", "/api/me");
    await enterApp();
  } catch (_) { showLogin(); }
}

$("login-form").onsubmit = async (e) => {
  e.preventDefault();
  $("login-err").textContent = "";
  try {
    await api("POST", "/api/login", { user: $("login-user").value, password: $("login-pass").value });
    $("login-pass").value = "";
    me = await api("GET", "/api/me");
    await enterApp();
  } catch (err) { $("login-err").textContent = err.message; }
};
$("btn-logout").onclick = async () => { await api("POST", "/api/logout", {}).catch(() => {}); showLogin(); };
$("btn-menu").onclick = () => document.body.classList.toggle("menu");
$("btn-new").onclick = () => { $("new-err").textContent = ""; $("dlg-new").showModal(); };
$("n-cancel").onclick = () => $("dlg-new").close();
$("new-form").onsubmit = async (e) => {
  e.preventDefault();
  try {
    const s = await api("POST", "/api/sessions", {
      title: $("n-title").value, cwd: $("n-cwd").value.trim(), model: $("n-model").value, permissionMode: $("n-mode").value,
    });
    $("dlg-new").close();
    $("n-title").value = "";
    await loadSessions();
    openSession(s.id);
  } catch (err) { $("new-err").textContent = err.message; }
};
// ---------- directory picker ----------
let dirPath = "";
async function browse(path) {
  $("d-err").textContent = "";
  try {
    const q = "/api/fs?path=" + encodeURIComponent(path) + ($("d-hidden").checked ? "&hidden=1" : "");
    const d = await api("GET", path ? q : "/api/fs");
    dirPath = d.path;
    const crumbs = $("d-path");
    crumbs.textContent = "";
    const top = el("a", "", "根目录");
    top.onclick = () => browse("");
    crumbs.appendChild(top);
    crumbs.appendChild(document.createTextNode(" " + (d.path || "")));
    const ul = $("d-list");
    ul.textContent = "";
    if (d.path) {
      const up = el("li", "", "⬆ 上级目录");
      up.onclick = () => browse(d.parent);
      ul.appendChild(up);
    }
    for (const e of d.entries) {
      const li = el("li", "", (d.path ? "📁 " : "🏠 ") + e.name);
      li.title = e.path;
      li.onclick = () => browse(e.path);
      ul.appendChild(li);
    }
    if (!d.entries.length) ul.appendChild(el("li", "empty", "(没有子目录)"));
    $("d-ok").disabled = !d.path;
    $("d-mk").disabled = !d.path;
  } catch (e) { $("d-err").textContent = e.message; }
}
$("n-pick").onclick = () => {
  $("dlg-dir").showModal();
  const c = $("n-cwd").value.trim();
  browse(c.startsWith("/") ? c : "");
};
$("d-hidden").onchange = () => browse(dirPath);
$("d-cancel").onclick = () => $("dlg-dir").close();
$("d-ok").onclick = () => { $("n-cwd").value = dirPath; $("dlg-dir").close(); };
$("d-mk").onclick = async () => {
  const name = prompt("新文件夹名称");
  if (!name) return;
  try {
    const r = await api("POST", "/api/fs/mkdir", { parent: dirPath, name });
    browse(r.path);
  } catch (e) { $("d-err").textContent = e.message; }
};

$("composer").onsubmit = (e) => { e.preventDefault(); send(); };
$("input").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.keyCode !== 229) { e.preventDefault(); send(); }
});
$("mode").onchange = () =>
  api("PATCH", "/api/sessions/" + cur.id, { permissionMode: $("mode").value }).catch((e) => { add(el("div", "note bad", e.message)); $("mode").value = cur.permissionMode; });
$("btn-int").onclick = () => api("POST", "/api/sessions/" + cur.id + "/interrupt", {}).catch((e) => add(el("div", "note bad", e.message)));
$("btn-stop").onclick = () => api("POST", "/api/sessions/" + cur.id + "/stop", {}).catch(() => {});
$("btn-del").onclick = async () => {
  if (!cur || !confirm("删除该会话及其全部记录？")) return;
  closeStream();
  await api("DELETE", "/api/sessions/" + cur.id).catch((e) => alert(e.message));
  cur = null;
  $("log").textContent = "";
  $("empty").hidden = false;
  location.hash = "";
  loadSessions();
};
$("title").onclick = async () => {
  if (!cur) return;
  const t = prompt("会话标题", cur.title || "");
  if (t === null) return;
  await api("PATCH", "/api/sessions/" + cur.id, { title: t }).catch((e) => alert(e.message));
  loadSessions();
};

setInterval(() => { if (me && !document.hidden) loadSessions(); }, 5000);
boot();
