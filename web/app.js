"use strict";
// Minimal dependency-free client for cc-harness. Everything is rendered with
// DOM APIs / escaped strings, nothing is fetched from third parties.

const $ = (id) => document.getElementById(id);
// URL prefix the server is mounted at (from <base href>), "" at the root.
const P = new URL(document.baseURI).pathname.replace(/\/$/, "");
const MODES = {
  manual: "逐项询问",
  acceptEdits: "自动接受编辑",
  plan: "计划模式",
  auto: "自动分类",
  dontAsk: "不询问(拒绝未授权)",
  bypassPermissions: "全部放行",
};
const MODELS = ["opus", "sonnet", "haiku", "fable"];
const EFFORTS = { "": "默认", low: "低", medium: "中", high: "高", xhigh: "超高", max: "最高" };
const STATUS = { stopped: "进程未运行", idle: "空闲", busy: "运行中", awaiting_permission: "等待授权" };

let me = null;
let cur = null; // current session view
let es = null;
let sessions = [];
let tools = {}; // tool_use_id -> result element
let perms = {}; // request id -> card element
let live = null; // {el, text}
let renderQueued = false;
let atts = []; // composer attachments: {name, el, id?, promise}
let commands = []; // slash commands of the current session
let slashSel = 0;

// ---------- helpers ----------
async function api(method, path, body) {
  const r = await fetch(P + path, {
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
  // Protect spans whose content must not be touched by later rules, then restore them in order.
  const keep = [];
  const stash = (html) => "\u0000" + (keep.push(html) - 1) + "\u0000";
  s = s.replace(/`([^`\n]+)`/g, (_, c) => stash("<code>" + c + "</code>"));
  s = s.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, (_, t, u) => stash('<a href="' + u + '" target="_blank" rel="noopener noreferrer">' + t + "</a>"));
  s = s.replace(/https?:\/\/[A-Za-z0-9\-._~:\/?#@!$&*+,;=%]+/g, (u) => {
    const m = u.match(/[.,;:!?'"&;，。；：！？]+$/); // sentence punctuation is not part of the URL
    const tail = m ? m[0] : "";
    const url = tail ? u.slice(0, -tail.length) : u;
    return stash('<a href="' + url + '" target="_blank" rel="noopener noreferrer">' + url + "</a>") + tail;
  });
  s = s.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
  s = s.replace(/~~(.+?)~~/g, "<del>$1</del>");
  s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>");
  // stashed entries can nest (a link inside bold), so restore until nothing is left
  while (/\u0000\d+\u0000/.test(s)) s = s.replace(/\u0000(\d+)\u0000/g, (_, i) => keep[+i]);
  return s;
}

const RE_ROW = /\|/;
const RE_SEP = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;
const RE_LI = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;

function splitRow(line) {
  let t = line.trim();
  if (t.startsWith("|")) t = t.slice(1);
  if (t.endsWith("|") && !t.endsWith("\\|")) t = t.slice(0, -1);
  const cells = [];
  let cur = "", code = false;
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    if (c === "\\" && t[i + 1] === "|") { cur += "|"; i++; }
    else if (c === "`") { code = !code; cur += c; }
    else if (c === "|" && !code) { cells.push(cur.trim()); cur = ""; }
    else cur += c;
  }
  cells.push(cur.trim());
  return cells;
}

// Markdown subset (same grammar as the Android client): fences, tables, headings, nested
// lists and task lists, quotes, rules, paragraphs, and inline code/bold/em/strike/links.
function md(src) {
  const out = [];
  const lines = src.replace(/\r/g, "").split("\n");
  const isTable = (k) => k + 1 < lines.length && RE_ROW.test(lines[k]) && lines[k].trim() !== "" && RE_SEP.test(lines[k + 1]) && lines[k + 1].includes("-");
  const structural = (k) => /^\s*```/.test(lines[k]) || /^#{1,6}\s/.test(lines[k]) || RE_LI.test(lines[k]) ||
    /^\s{0,3}>/.test(lines[k]) || /^\s{0,3}([-*_])(\s*\1){2,}\s*$/.test(lines[k]) || isTable(k);
  let i = 0;
  while (i < lines.length) {
    const ln = lines[i];
    if (/^\s*```/.test(ln)) {
      const buf = [];
      i++;
      while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) buf.push(lines[i++]);
      i++;
      out.push('<pre><button type="button" class="cp">复制</button><code>' + esc(buf.join("\n")) + "</code></pre>");
      continue;
    }
    if (/^\s{0,3}([-*_])(\s*\1){2,}\s*$/.test(ln)) { out.push("<hr>"); i++; continue; }
    const h = ln.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/);
    if (h) { out.push("<h" + h[1].length + ">" + inline(h[2]) + "</h" + h[1].length + ">"); i++; continue; }
    if (isTable(i)) {
      const head = splitRow(ln);
      const al = splitRow(lines[i + 1]).map((c) => (c.startsWith(":") && c.endsWith(":") && c.length > 1 ? "center" : c.endsWith(":") ? "right" : ""));
      i += 2;
      const style = (c) => (al[c] ? ' style="text-align:' + al[c] + '"' : "");
      let html = '<div class="tbl"><table><thead><tr>' + head.map((c, k) => "<th" + style(k) + ">" + inline(c) + "</th>").join("") + "</tr></thead><tbody>";
      while (i < lines.length && lines[i].trim() !== "" && RE_ROW.test(lines[i]) && !/^\s*```/.test(lines[i])) {
        const cells = splitRow(lines[i++]);
        html += "<tr>" + head.map((_, k) => "<td" + style(k) + ">" + inline(cells[k] || "") + "</td>").join("") + "</tr>";
      }
      out.push(html + "</tbody></table></div>");
      continue;
    }
    if (/^\s{0,3}>/.test(ln)) {
      const q = [];
      while (i < lines.length && /^\s{0,3}>/.test(lines[i])) q.push(lines[i++].replace(/^\s{0,3}>\s?/, ""));
      out.push("<blockquote>" + inline(q.join("\n").trim()).replace(/\n/g, "<br>") + "</blockquote>");
      continue;
    }
    if (RE_LI.test(ln)) {
      // Items keep their indentation level; consecutive items form one nested list.
      const stack = []; // {tag, level}
      let html = "";
      while (i < lines.length && RE_LI.test(lines[i])) {
        const m = lines[i++].match(RE_LI);
        const level = Math.floor(m[1].replace(/\t/g, "  ").length / 2);
        const tag = /\d/.test(m[2]) ? "ol" : "ul";
        let text = m[3], task = "";
        const t = text.match(/^\[([ xX])\]\s+/);
        if (t && tag === "ul") { task = t[1] === " " ? "☐ " : "☑ "; text = text.slice(t[0].length); }
        while (stack.length && stack[stack.length - 1].level > level) { html += "</li></" + stack.pop().tag + ">"; }
        const top = stack[stack.length - 1];
        if (top && top.level === level) html += "</li>";
        else { html += "<" + tag + (tag === "ol" && /^\d+/.test(m[2]) && parseInt(m[2]) !== 1 ? ' start="' + parseInt(m[2]) + '"' : "") + ">"; stack.push({ tag, level }); }
        html += "<li" + (task ? ' class="task"' : "") + ">" + task + inline(text);
      }
      while (stack.length) html += "</li></" + stack.pop().tag + ">";
      out.push(html);
      continue;
    }
    if (ln.trim() === "") { i++; continue; }
    const para = [];
    while (i < lines.length && lines[i].trim() !== "" && !structural(i)) para.push(lines[i++]);
    if (!para.length) para.push(lines[i++]); // never spin on a line the paragraph rule refuses
    out.push("<p>" + inline(para.join("\n")).replace(/\n/g, "<br>") + "</p>");
  }
  return out.join("");
}

function nearBottom() {
  const l = $("log");
  return l.scrollHeight - l.scrollTop - l.clientHeight < 80;
}
function toBottom() { $("log").scrollTop = $("log").scrollHeight; }
function add(node) {
  const stick = nearBottom();
  $("wrap").appendChild(node);
  if (stick) toBottom();
  else $("jump").hidden = false;
  return node;
}
// Copy buttons on fenced code blocks (event delegation, works for streamed output too).
$("wrap").addEventListener("click", async (e) => {
  const b = e.target.closest(".cp");
  if (!b) return;
  try { await navigator.clipboard.writeText(b.parentElement.querySelector("code").textContent); b.textContent = "已复制"; } catch (_) { b.textContent = "失败"; }
  setTimeout(() => { b.textContent = "复制"; }, 1500);
});
$("log").addEventListener("scroll", () => { if (nearBottom()) $("jump").hidden = true; });
$("jump").onclick = () => { toBottom(); $("jump").hidden = true; };

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
  if (!sessions.length) ul.appendChild(el("li", "none", "还没有会话"));
  for (const s of sessions) {
    const li = el("li", s.id === (cur && cur.id) ? "cur" : "");
    li.appendChild(el("span", "dot " + s.status));
    const m = el("div", "m");
    m.appendChild(el("div", "t", s.title || "(未命名)"));
    m.appendChild(el("div", "s", (STATUS[s.status] || s.status) + " · " + ago(s.updatedAt)));
    li.appendChild(m);
    li.title = s.cwd;
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
  $("meta").textContent = cur.cwd + (cur.model ? " · " + cur.model : "");
  $("meta").title = cur.cwd + " · " + cur.id;
  $("mode").value = cur.permissionMode;
  setModel($("model"), cur.model);
  $("effort").value = cur.effort || "";
  setStatus(cur.status);
}

// Selects a model, adding it when it is a custom value the list lacks.
function setModel(sel, v) {
  v = v || "";
  if (v && ![...sel.options].some((o) => o.value === v)) sel.appendChild(new Option(v, v));
  sel.value = v;
}

function fillModelEffort(modelSel, effortSel) {
  modelSel.textContent = ""; effortSel.textContent = "";
  modelSel.appendChild(new Option("默认", ""));
  for (const m of MODELS) modelSel.appendChild(new Option(m, m));
  for (const [k, v] of Object.entries(EFFORTS)) effortSel.appendChild(new Option(k ? "effort: " + v : "effort 默认", k));
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
  clearAtts();
  commands = [];
  hideSlash();
  loadCommands(id);
  $("wrap").textContent = "";
  $("jump").hidden = true;
  $("empty").hidden = true;
  renderBar();
  loadSessions();
  location.hash = id;

  // Fold in anything added to this session from a terminal / VS Code before
  // replaying its history. A failed sync must not keep the session closed.
  api("POST", "/api/sessions/" + id + "/sync").catch(() => {}).then(() => {
    if (!cur || cur.id !== id) return;
    es = new EventSource(P + "/api/sessions/" + id + "/events");
    es.onmessage = (m) => {
      let ev;
      try { ev = JSON.parse(m.data); } catch (_) { return; }
      handle(ev.data);
    };
    es.onerror = async () => {
      // EventSource retries by itself; only check whether our login expired.
      try { await api("GET", "/api/me"); } catch (_) {}
    };
  });
}

function handle(d) {
  if (!d) return;
  switch (d.type) {
    case "harness": return onHarness(d);
    case "system":
      if (d.subtype === "init") {
        add(el("div", "note", "已连接 · " + (d.model || "") + " · " + (d.permissionMode || "")));
        if (Array.isArray(d.slash_commands) && d.slash_commands.length) commands = d.slash_commands;
      }
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
      if (Array.isArray(d.attachments) && d.attachments.length) {
        const box = el("div", "att");
        for (const a of d.attachments) {
          const url = attUrl(cur.id, a.id);
          if (a.kind === "image") {
            const link = el("a"); link.href = url; link.target = "_blank"; link.rel = "noopener";
            const img = el("img"); img.src = url; img.alt = a.name; img.loading = "lazy";
            link.appendChild(img); box.appendChild(link);
          } else {
            const link = el("a", "", "📄 " + a.name + " (" + fmtSize(a.size) + ")");
            link.href = url; link.download = a.name;
            box.appendChild(link);
          }
        }
        m.appendChild(box);
      }
      break;
    }
    case "status":
      if (cur) cur.status = d.status;
      setStatus(d.status);
      loadSessions();
      break;
    case "imported": add(el("div", "note", "已接管本机会话 · " + d.messages + " 条历史")); break;
    case "synced": add(el("div", "note", "终端/VS Code 中继续过本会话 · 已同步 " + d.messages + " 条新内容")); break;
    case "process_started": add(el("div", "note", d.resume ? "后台进程已启动 (恢复上下文)" : "后台进程已启动")); break;
    case "process_exit": add(el("div", "note", "后台进程已退出: " + d.reason)); break;
    case "error": add(el("div", "note bad", d.message)); break;
    case "model_changed": if (cur) { cur.model = d.model; setModel($("model"), d.model); } break;
    case "effort_changed": if (cur) { cur.effort = d.effort; $("effort").value = d.effort || ""; } break;
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
        if (live) { const stick = nearBottom(); live.el.innerHTML = md(live.text); if (stick) toBottom(); }
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
      const det = el("details", "tool think");
      const sm = el("summary");
      sm.appendChild(el("b", "", "思考"));
      sm.appendChild(el("i", "", "›"));
      det.appendChild(sm);
      const body = el("div", "body");
      body.appendChild(el("pre", "", b.thinking));
      det.appendChild(body);
      add(det);
    } else if (b.type === "tool_use") {
      const det = el("details", "tool run");
      const sm = el("summary");
      sm.appendChild(el("b", "", b.name));
      sm.appendChild(el("span", "", summarize(b.name, b.input)));
      sm.appendChild(el("i", "", "›"));
      det.appendChild(sm);
      const body = el("div", "body");
      body.appendChild(el("pre", "", JSON.stringify(b.input, null, 2)));
      const res = el("div", "res");
      body.appendChild(res);
      det.appendChild(body);
      tools[b.id] = res;
      res.card = det;
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
    res.card.classList.remove("run");
    res.card.classList.add(b.is_error ? "err" : "ok");
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
    if (parts.length) add(el("div", "note res", parts.join(" · ")));
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
  if ((!t.trim() && !atts.length) || !cur) return;
  const sid = cur.id;
  if (atts.some((a) => !a.id && !a.failed && a.promise)) {
    await Promise.allSettled(atts.map((a) => a.promise));
  }
  if (atts.some((a) => a.failed)) {
    add(el("div", "note bad", "有附件上传失败，请移除后再发送"));
    return;
  }
  const ids = atts.map((a) => a.id);
  const keep = atts;
  $("input").value = "";
  autosize();
  atts = [];
  renderAtts();
  hideSlash();
  try {
    await api("POST", "/api/sessions/" + sid + "/messages", ids.length ? { text: t, attachments: ids } : { text: t });
  } catch (e) {
    $("input").value = t;
    autosize();
    atts = keep;
    renderAtts();
    add(el("div", "note bad", "发送失败: " + e.message));
  }
}

// ---------- attachments ----------
const attUrl = (sid, id) => P + "/api/sessions/" + sid + "/uploads/" + id;

function fmtSize(n) {
  if (n < 1024) return n + " B";
  if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
  return (n / 1048576).toFixed(1) + " MB";
}

function renderAtts() {
  const box = $("atts");
  box.textContent = "";
  box.hidden = atts.length === 0;
  for (const a of atts) {
    const chip = el("span", "chip-att" + (a.id ? "" : a.failed ? " bad" : " up"));
    if (a.preview) { const im = el("img"); im.src = a.preview; im.alt = ""; chip.appendChild(im); }
    chip.appendChild(el("span", "nm", a.name + (a.failed ? " — " + a.failed : a.id ? "" : " — 上传中…")));
    const x = el("button", "", "×");
    x.type = "button"; x.title = "移除"; x.setAttribute("aria-label", "移除 " + a.name);
    x.onclick = () => { atts = atts.filter((y) => y !== a); if (a.preview) URL.revokeObjectURL(a.preview); renderAtts(); };
    chip.appendChild(x);
    box.appendChild(chip);
  }
}

function clearAtts() {
  atts.forEach((a) => a.preview && URL.revokeObjectURL(a.preview));
  atts = [];
  renderAtts();
}

function addFiles(files) {
  if (!cur) return;
  const sid = cur.id;
  for (const f of files) {
    if (atts.length >= 10) { add(el("div", "note bad", "每条消息最多 10 个附件")); break; }
    const a = { name: f.name || "pasted-" + Date.now(), preview: f.type.startsWith("image/") ? URL.createObjectURL(f) : null };
    a.promise = fetch(P + "/api/sessions/" + sid + "/uploads?name=" + encodeURIComponent(a.name), {
      method: "POST", body: f, credentials: "same-origin",
      headers: { "Content-Type": f.type || "application/octet-stream" },
    }).then(async (r) => {
      let data = null;
      try { data = await r.json(); } catch (_) {}
      if (!r.ok) throw new Error((data && data.error) || r.statusText);
      a.id = data.id;
    }).catch((e) => { a.failed = e.message; }).finally(renderAtts);
    atts.push(a);
  }
  renderAtts();
}

// ---------- slash commands ----------
async function loadCommands(id) {
  try {
    const r = await api("GET", "/api/sessions/" + id + "/commands");
    if (cur && cur.id === id && r.commands.length) commands = r.commands;
  } catch (_) {}
}

function slashMatches() {
  const v = $("input").value;
  if (!v.startsWith("/") || /\s/.test(v)) return [];
  const q = v.slice(1).toLowerCase();
  const starts = commands.filter((c) => c.toLowerCase().startsWith(q));
  const rest = commands.filter((c) => !c.toLowerCase().startsWith(q) && c.toLowerCase().includes(q));
  return starts.concat(rest).slice(0, 50);
}

function hideSlash() { $("slash").hidden = true; $("slash").textContent = ""; slashSel = 0; }

function showSlash() {
  const m = slashMatches();
  const ul = $("slash");
  if (!m.length) return hideSlash();
  slashSel = Math.min(slashSel, m.length - 1);
  ul.textContent = "";
  m.forEach((c, i) => {
    const li = el("li", i === slashSel ? "sel" : "", "/" + c);
    li.onmousedown = (e) => { e.preventDefault(); pickSlash(c); };
    ul.appendChild(li);
  });
  ul.hidden = false;
  const sel = ul.querySelector(".sel");
  if (sel) sel.scrollIntoView({ block: "nearest" });
}

function pickSlash(c) {
  $("input").value = "/" + c + " ";
  hideSlash();
  $("input").focus();
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
  fillModelEffort($("model"), $("effort"));
  fillModelEffort($("n-model"), $("n-effort"));
  const sel = $("mode"), nsel = $("n-mode");
  sel.textContent = ""; nsel.textContent = "";
  for (const [k, v] of Object.entries(MODES)) {
    if (k === "bypassPermissions" && !me.allowBypass) continue;
    sel.appendChild(new Option(v, k));
    nsel.appendChild(new Option(v, k));
  }
  loadUsage();
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
      title: $("n-title").value, cwd: $("n-cwd").value.trim(), model: $("n-model").value, effort: $("n-effort").value, permissionMode: $("n-mode").value,
    });
    $("dlg-new").close();
    $("n-title").value = "";
    await loadSessions();
    openSession(s.id);
  } catch (err) { $("new-err").textContent = err.message; }
};
// ---------- import local sessions ----------
let localSessions = [];

function ago(iso) {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 90) return "刚刚";
  if (s < 5400) return Math.round(s / 60) + " 分钟前";
  if (s < 129600) return Math.round(s / 3600) + " 小时前";
  return Math.round(s / 86400) + " 天前";
}

function renderLocal() {
  const q = $("i-filter").value.trim().toLowerCase();
  const ul = $("i-list");
  ul.textContent = "";
  const shown = localSessions.filter((x) => !q || (x.title + " " + x.cwd).toLowerCase().includes(q));
  if (!shown.length) ul.appendChild(el("li", "empty", localSessions.length ? "没有匹配的会话" : "没有可导入的本地会话"));
  for (const x of shown) {
    const li = el("li");
    const main = el("div", "i-main");
    main.appendChild(el("div", "i-title", (x.title || "(无标题)") + (x.active ? "  · 可能正在使用" : "")));
    main.appendChild(el("div", "i-sub", x.cwd + " · " + ago(x.updatedAt)));
    li.appendChild(main);
    const b = el("button", "primary", "导入");
    b.type = "button";
    b.onclick = () => importLocal(x, b, false);
    li.appendChild(b);
    ul.appendChild(li);
  }
}

async function importLocal(x, btn, force) {
  $("i-err").textContent = "";
  btn.disabled = true;
  try {
    const s = await api("POST", "/api/local-sessions/" + x.id + "/import", force ? { force: true } : undefined);
    $("dlg-import").close();
    await loadSessions();
    openSession(s.id);
  } catch (e) {
    if (e.status === 409 && !force && /moments ago/.test(e.message)) {
      if (confirm("这个会话刚刚还在被修改，可能仍在别处打开。仍要接管吗？")) return importLocal(x, btn, true);
    } else {
      $("i-err").textContent = e.message;
    }
    btn.disabled = false;
  }
}

$("btn-import").onclick = async () => {
  $("i-err").textContent = "";
  $("i-filter").value = "";
  localSessions = [];
  renderLocal();
  $("dlg-import").showModal();
  try {
    localSessions = (await api("GET", "/api/local-sessions")).sessions;
    renderLocal();
  } catch (e) { $("i-err").textContent = e.message; }
};
$("i-filter").oninput = renderLocal;
$("i-close").onclick = () => $("dlg-import").close();

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
function autosize() {
  const t = $("input");
  t.style.height = "auto";
  t.style.height = Math.min(t.scrollHeight, window.innerHeight * 0.4) + "px";
}
$("input").addEventListener("input", () => { slashSel = 0; showSlash(); autosize(); });
$("btn-new2").onclick = () => $("btn-new").click();
$("scrim").onclick = () => document.body.classList.remove("menu");
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && cur && !$("btn-int").disabled && !document.querySelector("dialog[open]") && $("slash").hidden) {
    api("POST", "/api/sessions/" + cur.id + "/interrupt", {}).catch(() => {});
  }
});
$("input").addEventListener("keydown", (e) => {
  const sl = !$("slash").hidden ? slashMatches() : [];
  if (sl.length) {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      slashSel = (slashSel + (e.key === "ArrowDown" ? 1 : sl.length - 1)) % sl.length;
      showSlash();
      return;
    }
    if ((e.key === "Tab" || e.key === "Enter") && !e.isComposing && e.keyCode !== 229) {
      e.preventDefault();
      pickSlash(sl[slashSel]);
      return;
    }
    if (e.key === "Escape") { e.preventDefault(); hideSlash(); return; }
  }
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.keyCode !== 229) { e.preventDefault(); send(); }
});
$("btn-attach").onclick = () => $("file").click();
$("file").onchange = () => { addFiles([...$("file").files]); $("file").value = ""; };
$("input").addEventListener("paste", (e) => {
  const files = [...(e.clipboardData ? e.clipboardData.files : [])];
  if (files.length) { e.preventDefault(); addFiles(files); }
});
for (const t of ["dragenter", "dragover"]) $("main").addEventListener(t, (e) => { if (cur && e.dataTransfer && [...e.dataTransfer.types].includes("Files")) e.preventDefault(); });
$("main").addEventListener("dragenter", (e) => { if (cur && e.dataTransfer && [...e.dataTransfer.types].includes("Files")) $("main").classList.add("drop"); });
$("main").addEventListener("dragleave", (e) => { if (e.target === $("main") || !$("main").contains(e.relatedTarget)) $("main").classList.remove("drop"); });
$("main").addEventListener("drop", (e) => {
  $("main").classList.remove("drop");
  if (!cur || !e.dataTransfer || !e.dataTransfer.files.length) return;
  e.preventDefault();
  addFiles([...e.dataTransfer.files]);
});
$("model").onchange = () =>
  api("PATCH", "/api/sessions/" + cur.id, { model: $("model").value }).catch((e) => { add(el("div", "note bad", e.message)); setModel($("model"), cur.model); });
$("effort").onchange = () =>
  api("PATCH", "/api/sessions/" + cur.id, { effort: $("effort").value }).catch((e) => { add(el("div", "note bad", e.message)); $("effort").value = cur.effort || ""; });
$("mode").onchange = () =>
  api("PATCH", "/api/sessions/" + cur.id, { permissionMode: $("mode").value }).catch((e) => { add(el("div", "note bad", e.message)); $("mode").value = cur.permissionMode; });
$("btn-int").onclick = () => api("POST", "/api/sessions/" + cur.id + "/interrupt", {}).catch((e) => add(el("div", "note bad", e.message)));
$("btn-stop").onclick = () => api("POST", "/api/sessions/" + cur.id + "/stop", {}).catch(() => {});
$("btn-del").onclick = async () => {
  if (!cur || !confirm("删除该会话及其全部记录？")) return;
  closeStream();
  await api("DELETE", "/api/sessions/" + cur.id).catch((e) => alert(e.message));
  cur = null;
  $("wrap").textContent = "";
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

// ---------- plan usage ----------
function fmtReset(iso) {
  const ms = new Date(iso) - Date.now();
  if (!(ms > 0)) return "";
  const m = Math.round(ms / 60000);
  if (m < 60) return m + " 分钟后重置";
  const h = Math.floor(m / 60);
  return h < 48 ? h + " 小时 " + (m % 60) + " 分后重置" : Math.round(h / 24) + " 天后重置";
}

async function loadUsage() {
  const box = $("usage"), bars = $("usage-bars");
  let u;
  try { u = await api("GET", "/api/usage"); } catch (e) {
    box.hidden = false;
    bars.textContent = "";
    bars.appendChild(el("div", "u-err", "用量不可用：" + e.message));
    $("usage-plan").textContent = "";
    return;
  }
  box.hidden = false;
  $("usage-plan").textContent = u.plan ? "· " + u.plan : "";
  bars.textContent = "";
  for (const l of u.limits) {
    const pct = Math.max(0, Math.min(100, l.percent));
    const d = el("div", "u" + (pct >= 90 ? " bad" : pct >= 70 ? " warn" : ""));
    const row = el("div", "u-row");
    row.appendChild(el("span", "", l.label));
    row.appendChild(el("span", "u-pct", Math.round(l.percent) + "%"));
    d.appendChild(row);
    const bar = el("div", "u-bar");
    const fill = document.createElement("i");
    fill.style.width = pct + "%";
    bar.appendChild(fill);
    d.appendChild(bar);
    const r = l.resetsAt ? fmtReset(l.resetsAt) : "";
    if (r) d.appendChild(el("div", "u-reset", r));
    bars.appendChild(d);
  }
}

setInterval(() => { if (me && !document.hidden) loadSessions(); }, 5000);
setInterval(() => { if (me && !document.hidden) loadUsage(); }, 60000);
boot();
