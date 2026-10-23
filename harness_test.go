package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestMain lets this test binary impersonate the `claude` CLI: when
// MOCK_CLAUDE=1 it speaks just enough of the stream-json protocol.
func TestMain(m *testing.M) {
	if os.Getenv("MOCK_CLAUDE") == "1" {
		mockClaude()
		return
	}
	os.Exit(m.Run())
}

func mockClaude() {
	if f := os.Getenv("MOCK_ARGS_FILE"); f != "" {
		fh, _ := os.OpenFile(f, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
		fmt.Fprintln(fh, strings.Join(os.Args[1:], " "))
		fh.Close()
	}
	if os.Getenv("HARNESS_PASSWORD") != "" {
		fmt.Println(`{"type":"leak","env":"HARNESS_PASSWORD visible"}`)
	}
	out := bufio.NewWriter(os.Stdout)
	emit := func(s string) { out.WriteString(s + "\n"); out.Flush() }
	in := bufio.NewReaderSize(os.Stdin, 1<<20)
	first := true
	respCh := make(chan map[string]any, 4)
	lines := make(chan []byte, 16)
	go func() {
		for {
			l, err := in.ReadBytes('\n')
			if len(l) > 0 {
				lines <- l
			}
			if err != nil {
				close(lines)
				return
			}
		}
	}()
	turn := func(text string) {
		if first {
			first = false
			emit(`{"type":"system","subtype":"hook_started","hook_id":"x"}`)
			emit(`{"type":"system","subtype":"init","session_id":"s","model":"mock","permissionMode":"default"}`)
		}
		emit(`{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"par"}}}`)
		if strings.Contains(text, "SLEEP") {
			time.Sleep(30 * time.Second)
		}
		if strings.Contains(text, "TOOL") {
			emit(`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu1","name":"Write","input":{"file_path":"/x","content":"y"}}]}}`)
			emit(`{"type":"control_request","request_id":"rq1","request":{"subtype":"can_use_tool","tool_name":"Write","tool_use_id":"tu1","input":{"file_path":"/x","content":"y"},"permission_suggestions":[{"type":"setMode","mode":"acceptEdits","destination":"session"}]}}`)
			r := <-respCh
			b, _ := json.Marshal(r)
			emit(fmt.Sprintf(`{"type":"assistant","message":{"content":[{"type":"text","text":"decision %s"}]}}`, strings.ReplaceAll(string(b), `"`, `'`)))
		} else {
			b, _ := json.Marshal("echo: " + text)
			emit(fmt.Sprintf(`{"type":"assistant","message":{"content":[{"type":"text","text":%s}]}}`, b))
		}
		emit(`{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.001,"duration_ms":5}`)
	}
	pendingTurns := make(chan string, 8)
	go func() {
		for t := range pendingTurns {
			turn(t)
		}
	}()
	for l := range lines {
		var m map[string]any
		if json.Unmarshal(l, &m) != nil {
			continue
		}
		switch m["type"] {
		case "user":
			msg, _ := m["message"].(map[string]any)
			t, _ := msg["content"].(string)
			pendingTurns <- t
		case "control_response":
			resp, _ := m["response"].(map[string]any)
			inner, _ := resp["response"].(map[string]any)
			respCh <- inner
		case "control_request":
			req, _ := m["request"].(map[string]any)
			id, _ := m["request_id"].(string)
			emit(fmt.Sprintf(`{"type":"control_response","response":{"subtype":"success","request_id":%q}}`, id))
			if req["subtype"] == "interrupt" {
				emit(`{"type":"result","subtype":"error_during_execution","is_error":true}`)
			}
		}
	}
}

type env struct {
	srv  *httptest.Server
	cfg  Config
	mgr  *Manager
	args string
}

func newEnv(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	exe, _ := os.Executable()
	args := filepath.Join(dir, "args.txt")
	t.Setenv("MOCK_CLAUDE", "1")
	t.Setenv("MOCK_ARGS_FILE", args)
	t.Setenv("HARNESS_PASSWORD", "512557")
	cfg := Config{
		Password: "512557", UserPasswords: map[string]string{"carol": "own-pass"},
		DataDir: filepath.Join(dir, "data"), ClaudeBin: exe, Port: "0",
		IdleTimeout: time.Hour, MaxProcs: 4, TokenTTL: time.Hour,
		ExtraRoots: []string{filepath.Join(dir, "shared")},
	}
	os.MkdirAll(cfg.DataDir, 0o700)
	os.MkdirAll(filepath.Join(dir, "shared"), 0o700)
	os.MkdirAll(filepath.Join(dir, "elsewhere"), 0o700)
	auth, err := NewAuth(cfg)
	if err != nil {
		t.Fatal(err)
	}
	mgr := NewManager(cfg, NewStore(cfg.DataDir))
	srv := httptest.NewServer(NewAPI(cfg, auth, mgr).Handler())
	t.Cleanup(func() { srv.Close(); mgr.StopAll() })
	return &env{srv: srv, cfg: cfg, mgr: mgr, args: args}
}

func (e *env) do(t *testing.T, user, pass, method, path string, body any) (int, map[string]any) {
	t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, rd)
	req.SetBasicAuth(user, pass)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func (e *env) history(t *testing.T, user, id string) []map[string]any {
	_, h := e.do(t, user, "512557", "GET", "/api/sessions/"+id+"/history", nil)
	var evs []map[string]any
	for _, x := range h["events"].([]any) {
		evs = append(evs, x.(map[string]any)["data"].(map[string]any))
	}
	return evs
}

func waitFor(t *testing.T, what string, f func() bool) {
	t.Helper()
	for i := 0; i < 100; i++ {
		if f() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("timeout waiting for %s", what)
}

func (e *env) status(t *testing.T, user, id string) string {
	_, v := e.do(t, user, "512557", "GET", "/api/sessions/"+id, nil)
	s, _ := v["status"].(string)
	return s
}

func countType(evs []map[string]any, typ string) int {
	n := 0
	for _, e := range evs {
		if e["type"] == typ {
			n++
		}
	}
	return n
}

func TestSessionLifecycleResumeAndPermissions(t *testing.T) {
	e := newEnv(t)
	code, v := e.do(t, "alice", "512557", "POST", "/api/sessions", map[string]any{"title": "t1"})
	if code != 201 {
		t.Fatalf("create: %d %v", code, v)
	}
	id := v["id"].(string)

	// plain turn
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "hello"}); code != 202 {
		t.Fatalf("send: %d", code)
	}
	waitFor(t, "result", func() bool { return countType(e.history(t, "alice", id), "result") == 1 })
	evs := e.history(t, "alice", id)
	var sawEcho bool
	for _, ev := range evs {
		if ev["type"] == "assistant" && strings.Contains(fmt.Sprint(ev), "echo: hello") {
			sawEcho = true
		}
		if ev["type"] == "leak" {
			t.Fatal("HARNESS_PASSWORD leaked to child environment")
		}
		if ev["type"] == "stream_event" || (ev["type"] == "system" && ev["subtype"] == "hook_started") {
			t.Fatalf("ephemeral/noisy event persisted: %v", ev)
		}
	}
	if !sawEcho {
		t.Fatalf("no echo in %v", evs)
	}
	waitFor(t, "idle", func() bool { return e.status(t, "alice", id) == "idle" })

	// permission round-trip with "remember"
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "TOOL please"})
	waitFor(t, "awaiting", func() bool { return e.status(t, "alice", id) == "awaiting_permission" })
	_, sv := e.do(t, "alice", "512557", "GET", "/api/sessions/"+id, nil)
	if p := sv["pending"].([]any); len(p) != 1 {
		t.Fatalf("pending = %v", p)
	}
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/permissions/nope", map[string]any{"allow": true}); code != 404 {
		t.Fatalf("unknown rid: %d", code)
	}
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/permissions/rq1", map[string]any{"allow": true, "remember": true}); code != 200 {
		t.Fatalf("permission: %d", code)
	}
	waitFor(t, "2 results", func() bool { return countType(e.history(t, "alice", id), "result") == 2 })
	var decision string
	for _, ev := range e.history(t, "alice", id) {
		s := fmt.Sprint(ev)
		if strings.Contains(s, "decision") {
			decision = s
		}
	}
	if !strings.Contains(decision, "allow") || !strings.Contains(decision, "updatedPermissions") || !strings.Contains(decision, "updatedInput") {
		t.Fatalf("CLI did not get allow+updatedPermissions: %s", decision)
	}

	// deny path
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "TOOL again"})
	waitFor(t, "awaiting2", func() bool { return e.status(t, "alice", id) == "awaiting_permission" })
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/permissions/rq1", map[string]any{"allow": false, "message": "nope"})
	waitFor(t, "3 results", func() bool { return countType(e.history(t, "alice", id), "result") == 3 })

	// stop the process, then a new message must --resume
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/stop", map[string]any{})
	waitFor(t, "stopped", func() bool { return e.status(t, "alice", id) == "stopped" })
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "again"})
	waitFor(t, "4 results", func() bool { return countType(e.history(t, "alice", id), "result") == 4 })
	argv, _ := os.ReadFile(e.args)
	ls := strings.Split(strings.TrimSpace(string(argv)), "\n")
	if len(ls) != 2 || !strings.Contains(ls[0], "--session-id "+id) || !strings.Contains(ls[1], "--resume "+id) {
		t.Fatalf("unexpected spawn args: %q", ls)
	}
	if !strings.Contains(ls[0], "--permission-prompt-tool stdio") || !strings.Contains(ls[0], "--permission-mode manual") {
		t.Fatalf("missing permission flags: %q", ls[0])
	}
}

func TestInterruptAndStopMidTurn(t *testing.T) {
	e := newEnv(t)
	_, v := e.do(t, "alice", "512557", "POST", "/api/sessions", nil)
	id := v["id"].(string)
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "SLEEP"})
	waitFor(t, "busy", func() bool { return e.status(t, "alice", id) == "busy" })
	if code, r := e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/interrupt", map[string]any{}); code != 200 {
		t.Fatalf("interrupt: %d %v", code, r)
	}
	// the CLI ends the interrupted turn with an error result -> back to idle
	waitFor(t, "idle after interrupt", func() bool { return e.status(t, "alice", id) == "idle" })

	// killing a process that is mid-turn must surface an error and reset the state
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "queued"})
	waitFor(t, "busy again", func() bool { return e.status(t, "alice", id) == "busy" })
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/stop", map[string]any{})
	waitFor(t, "stopped", func() bool { return e.status(t, "alice", id) == "stopped" })
	var sawErr bool
	for _, ev := range e.history(t, "alice", id) {
		if ev["type"] == "harness" && ev["kind"] == "error" {
			sawErr = true
		}
	}
	if !sawErr {
		t.Fatal("no error event for process killed mid-turn")
	}
}

func TestUserIsolationAndCwdRules(t *testing.T) {
	e := newEnv(t)
	_, v := e.do(t, "alice", "512557", "POST", "/api/sessions", map[string]any{"title": "private"})
	id := v["id"].(string)

	if code, _ := e.do(t, "bob", "512557", "GET", "/api/sessions/"+id, nil); code != 404 {
		t.Fatalf("bob read alice session: %d", code)
	}
	if code, _ := e.do(t, "bob", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "x"}); code != 404 {
		t.Fatalf("bob sent to alice session: %d", code)
	}
	if code, _ := e.do(t, "bob", "512557", "DELETE", "/api/sessions/"+id, nil); code != 404 {
		t.Fatalf("bob deleted alice session: %d", code)
	}
	_, l := e.do(t, "bob", "512557", "GET", "/api/sessions", nil)
	if n := len(l["sessions"].([]any)); n != 0 {
		t.Fatalf("bob sees %d sessions", n)
	}
	if code, _ := e.do(t, "alice", "512557", "GET", "/api/sessions/../../etc", nil); code == 200 {
		t.Fatal("path traversal accepted")
	}
	if code, _ := e.do(t, "alice", "512557", "GET", "/api/sessions/not-a-uuid", nil); code != 404 {
		t.Fatalf("bad id: %d", code)
	}

	// cwd rules
	for _, bad := range []string{"/etc", "../../..", "/", filepath.Join(filepath.Dir(e.cfg.DataDir), "elsewhere")} {
		if code, r := e.do(t, "alice", "512557", "POST", "/api/sessions", map[string]any{"cwd": bad}); code != 400 {
			t.Fatalf("cwd %q accepted: %d %v", bad, code, r)
		}
	}
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/sessions", map[string]any{"cwd": "proj/sub"}); code != 201 {
		t.Fatalf("relative cwd rejected: %d", code)
	}
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/sessions", map[string]any{"cwd": e.cfg.ExtraRoots[0]}); code != 201 {
		t.Fatalf("extra root rejected: %d", code)
	}
	// symlink escape from the workspace
	ws, _ := e.mgr.store.Workspace("alice")
	os.Symlink("/etc", filepath.Join(ws, "link"))
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/sessions", map[string]any{"cwd": "link"}); code != 400 {
		t.Fatalf("symlink escape accepted: %d", code)
	}
	// bypass disabled by default
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/sessions", map[string]any{"permissionMode": "bypassPermissions"}); code != 400 {
		t.Fatalf("bypass accepted: %d", code)
	}

	// per-user password
	if code, _ := e.do(t, "carol", "512557", "GET", "/api/me", nil); code != 401 {
		t.Fatalf("carol logged in with shared password: %d", code)
	}
	if code, _ := e.do(t, "carol", "own-pass", "GET", "/api/me", nil); code != 200 {
		t.Fatalf("carol own password: %d", code)
	}

	// delete really removes files
	if code, _ := e.do(t, "alice", "512557", "DELETE", "/api/sessions/"+id, nil); code != 200 {
		t.Fatalf("delete: %d", code)
	}
	if _, err := os.Stat(e.mgr.store.sessionDir("alice", id)); !os.IsNotExist(err) {
		t.Fatal("session dir still exists")
	}
}

func TestLoginTokenCookieAndLockout(t *testing.T) {
	e := newEnv(t)
	post := func(user, pw string) *http.Response {
		b, _ := json.Marshal(map[string]string{"user": user, "password": pw})
		r, err := http.Post(e.srv.URL+"/api/login", "application/json", bytes.NewReader(b))
		if err != nil {
			t.Fatal(err)
		}
		return r
	}
	r := post("alice", "512557")
	if r.StatusCode != 200 {
		t.Fatalf("login: %d", r.StatusCode)
	}
	var tok struct{ Token string }
	json.NewDecoder(r.Body).Decode(&tok)
	var cookie *http.Cookie
	for _, c := range r.Cookies() {
		if c.Name == cookieName {
			cookie = c
		}
	}
	if cookie == nil || !cookie.HttpOnly || cookie.SameSite != http.SameSiteStrictMode {
		t.Fatalf("bad cookie: %+v", cookie)
	}
	req, _ := http.NewRequest("GET", e.srv.URL+"/api/me", nil)
	req.Header.Set("Authorization", "Bearer "+tok.Token)
	if resp, _ := http.DefaultClient.Do(req); resp.StatusCode != 200 {
		t.Fatalf("bearer: %d", resp.StatusCode)
	}
	req, _ = http.NewRequest("GET", e.srv.URL+"/api/me", nil)
	req.AddCookie(cookie)
	if resp, _ := http.DefaultClient.Do(req); resp.StatusCode != 200 {
		t.Fatalf("cookie: %d", resp.StatusCode)
	}
	if resp, _ := http.Get(e.srv.URL + "/api/me"); resp.StatusCode != 401 {
		t.Fatalf("anonymous: %d", resp.StatusCode)
	}
	// cross-origin POST rejected
	req, _ = http.NewRequest("POST", e.srv.URL+"/api/sessions", strings.NewReader("{}"))
	req.AddCookie(cookie)
	req.Header.Set("Origin", "http://evil.example")
	if resp, _ := http.DefaultClient.Do(req); resp.StatusCode != 403 {
		t.Fatalf("cross-origin: %d", resp.StatusCode)
	}

	// brute force: 5 failures lock the client, even for the right password
	for i := 0; i < maxIPFails; i++ {
		if c := post("alice", fmt.Sprintf("00000%d", i)).StatusCode; c != 401 {
			t.Fatalf("attempt %d: %d", i, c)
		}
	}
	if resp := post("alice", "512557"); resp.StatusCode != 429 || resp.Header.Get("Retry-After") == "" {
		t.Fatalf("not locked: %d", resp.StatusCode)
	}
	if resp, _ := http.Get(e.srv.URL + "/api/health"); resp.StatusCode != 200 {
		t.Fatal("health should stay open")
	}
}

func TestTokenExpiryAndPasswordRotation(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{Password: "a", DataDir: dir, TokenTTL: time.Hour}
	a, _ := NewAuth(cfg)
	tok := a.Issue("alice")
	if u, ok := a.Verify(tok); !ok || u != "alice" {
		t.Fatal("fresh token rejected")
	}
	if _, ok := a.Verify(tok + "x"); ok {
		t.Fatal("tampered token accepted")
	}
	a.now = func() time.Time { return time.Now().Add(2 * time.Hour) }
	if _, ok := a.Verify(tok); ok {
		t.Fatal("expired token accepted")
	}
	cfg.Password = "b"
	a2, _ := NewAuth(cfg)
	if _, ok := a2.Verify(tok); ok {
		t.Fatal("token survived password change")
	}
}

func TestSSEResumeWithLastEventID(t *testing.T) {
	e := newEnv(t)
	_, v := e.do(t, "alice", "512557", "POST", "/api/sessions", nil)
	id := v["id"].(string)
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "one"})
	waitFor(t, "result", func() bool { return countType(e.history(t, "alice", id), "result") == 1 })

	read := func(after string, want func(ids []string, datas []string) bool) []string {
		req, _ := http.NewRequest("GET", e.srv.URL+"/api/sessions/"+id+"/events", nil)
		req.SetBasicAuth("alice", "512557")
		if after != "" {
			req.Header.Set("Last-Event-ID", after)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream" {
			t.Fatalf("content-type %q", ct)
		}
		var ids, datas []string
		sc := bufio.NewScanner(resp.Body)
		sc.Buffer(make([]byte, 1<<20), 8<<20)
		done := make(chan struct{})
		go func() {
			defer close(done)
			for sc.Scan() {
				l := sc.Text()
				if strings.HasPrefix(l, "id: ") {
					ids = append(ids, l[4:])
				} else if strings.HasPrefix(l, "data: ") {
					datas = append(datas, l[6:])
				}
				if want(ids, datas) {
					return
				}
			}
		}()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatalf("SSE timeout, got ids=%v", ids)
		}
		return ids
	}

	all := read("", func(ids, datas []string) bool {
		return len(datas) > 0 && strings.Contains(datas[len(datas)-1], `"kind":"status"`)
	})
	if len(all) < 4 {
		t.Fatalf("replay too short: %v", all)
	}
	last := all[len(all)-1]
	// reconnect after the last id: nothing replayed, then a live event arrives
	go func() {
		time.Sleep(300 * time.Millisecond)
		e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "two"})
	}()
	var sawTwo bool
	read(last, func(ids, datas []string) bool {
		for _, d := range datas {
			if strings.Contains(d, `echo: one`) {
				t.Errorf("replayed old event after Last-Event-ID: %s", d)
			}
			if strings.Contains(d, `echo: two`) {
				sawTwo = true
			}
		}
		return sawTwo
	})
	if !sawTwo {
		t.Fatal("live event not delivered")
	}
}

func TestPersistenceAcrossRestart(t *testing.T) {
	e := newEnv(t)
	_, v := e.do(t, "alice", "512557", "POST", "/api/sessions", map[string]any{"title": "keep"})
	id := v["id"].(string)
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "hello"})
	waitFor(t, "result", func() bool { return countType(e.history(t, "alice", id), "result") == 1 })
	before := len(e.history(t, "alice", id))
	e.srv.Close()
	e.mgr.StopAll()

	// a brand new manager over the same data dir sees the session and its history
	mgr2 := NewManager(e.cfg, NewStore(e.cfg.DataDir))
	auth, _ := NewAuth(e.cfg)
	srv2 := httptest.NewServer(NewAPI(e.cfg, auth, mgr2).Handler())
	defer func() { srv2.Close(); mgr2.StopAll() }()
	e.srv = srv2
	e.mgr = mgr2
	_, sv := e.do(t, "alice", "512557", "GET", "/api/sessions/"+id, nil)
	if sv["title"] != "keep" || sv["status"] != "stopped" || sv["started"] != true {
		t.Fatalf("restored view: %v", sv)
	}
	if got := len(e.history(t, "alice", id)); got < before {
		t.Fatalf("history shrank: %d < %d", got, before)
	}
	e.do(t, "alice", "512557", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "after restart"})
	waitFor(t, "second result", func() bool { return countType(e.history(t, "alice", id), "result") == 2 })
	argv, _ := os.ReadFile(e.args)
	if !strings.Contains(string(argv), "--resume "+id) {
		t.Fatalf("restart did not resume: %s", argv)
	}
	// seq continues instead of restarting at 1
	evs := e.history(t, "alice", id)
	if len(evs) <= before {
		t.Fatal("no new events after restart")
	}
}

func TestEventLogTornTail(t *testing.T) {
	p := filepath.Join(t.TempDir(), "events.jsonl")
	l, err := OpenEventLog(p)
	if err != nil {
		t.Fatal(err)
	}
	l.Append([]byte(`{"a":1}`))
	l.Append([]byte(`{"a":2}`))
	l.Close()
	f, _ := os.OpenFile(p, os.O_APPEND|os.O_WRONLY, 0)
	f.WriteString(`{"seq":3,"ts":1,"data":{"a"`) // crash mid-write
	f.Close()

	l2, err := OpenEventLog(p)
	if err != nil {
		t.Fatal(err)
	}
	defer l2.Close()
	seq, _, _ := l2.Append([]byte(`{"a":"multi\nline"}`))
	if seq != 3 {
		t.Fatalf("unexpected seq %d", seq)
	}
	var got []int64
	l2.Read(0, 100, func(s int64, line []byte) error {
		if !json.Valid(line) {
			t.Fatalf("invalid line served: %s", line)
		}
		got = append(got, s)
		return nil
	})
	if len(got) != 3 || got[0] != 1 || got[1] != 2 {
		t.Fatalf("read back %v", got)
	}
}

func TestBindAddressSelection(t *testing.T) {
	cases := []struct {
		iface, ip string
		want      bool
	}{
		{"tailscale0", "100.101.146.117", true},
		{"tailscale0", "10.0.0.5", false},
		{"enp0s31f6", "192.168.1.164", true},
		{"enp0s31f6", "10.1.2.3", true},
		{"wlan0", "172.20.0.9", true},
		{"docker0", "172.17.0.1", false},
		{"br-4641d72fa99d", "172.18.0.1", false},
		{"wlxb0386ce14e81", "110.15.137.36", false},
		{"eth0", "8.8.8.8", false},
		{"eth0", "fe80::1", false},
	}
	for _, c := range cases {
		if got := classifyIP(c.iface, net.ParseIP(c.ip)); got != c.want {
			t.Errorf("classifyIP(%s,%s)=%v want %v", c.iface, c.ip, got, c.want)
		}
	}
	cfg := Config{Port: "9000", Binds: []string{"127.0.0.1", "10.0.0.1:81", "[::1]"}}
	got := strings.Join(desiredAddrs(cfg), ",")
	if got != "10.0.0.1:81,127.0.0.1:9000,[::1]:9000" {
		t.Fatalf("desiredAddrs = %s", got)
	}
}

func TestLineWriterSplitsAndCaps(t *testing.T) {
	var got []string
	w := &lineWriter{max: 16, fn: func(b []byte) { got = append(got, string(b)) }}
	w.Write([]byte("ab"))
	w.Write([]byte("c\nde"))
	w.Write([]byte("f\nTOOLONGTOOLONGTOOLONG\nok\n"))
	if strings.Join(got, "|") != "abc|def|ok" {
		t.Fatalf("got %q", got)
	}
}

func TestDirectoryBrowserAndMkdir(t *testing.T) {
	e := newEnv(t)
	names := func(v map[string]any) []string {
		var out []string
		for _, x := range v["entries"].([]any) {
			out = append(out, x.(map[string]any)["name"].(string))
		}
		return out
	}
	shared := e.cfg.ExtraRoots[0]
	os.MkdirAll(filepath.Join(shared, "projB"), 0o755)
	os.MkdirAll(filepath.Join(shared, "projA", "src"), 0o755)
	os.MkdirAll(filepath.Join(shared, ".hidden"), 0o755)
	os.WriteFile(filepath.Join(shared, "file.txt"), []byte("x"), 0o644)
	os.Symlink("/etc", filepath.Join(shared, "escape"))
	os.Symlink(filepath.Join(shared, "projA"), filepath.Join(shared, "alias"))

	// roots
	code, v := e.do(t, "alice", "512557", "GET", "/api/fs", nil)
	if code != 200 || len(v["entries"].([]any)) != 2 || v["path"] != "" {
		t.Fatalf("roots: %d %v", code, v)
	}
	// listing: dirs only, sorted, no hidden, no escaping symlink, valid symlink kept
	code, v = e.do(t, "alice", "512557", "GET", "/api/fs?path="+shared, nil)
	if code != 200 || strings.Join(names(v), ",") != "alias,projA,projB" {
		t.Fatalf("list: %d %v", code, names(v))
	}
	if v["parent"] != "" {
		t.Fatalf("root parent should be empty, got %v", v["parent"])
	}
	if _, v = e.do(t, "alice", "512557", "GET", "/api/fs?hidden=1&path="+shared, nil); !strings.Contains(strings.Join(names(v), ","), ".hidden") {
		t.Fatalf("hidden not shown: %v", names(v))
	}
	code, v = e.do(t, "alice", "512557", "GET", "/api/fs?path="+filepath.Join(shared, "projA"), nil)
	if code != 200 || v["parent"] != shared || strings.Join(names(v), ",") != "src" {
		t.Fatalf("sub list: %d %v", code, v)
	}
	// forbidden targets
	other := filepath.Join(e.cfg.DataDir, "users", "bob", "workspace")
	os.MkdirAll(other, 0o700)
	for _, bad := range []string{"/etc", "/", filepath.Join(shared, "escape"), other, filepath.Join(shared, "..")} {
		if code, _ := e.do(t, "alice", "512557", "GET", "/api/fs?path="+bad, nil); code != 403 {
			t.Fatalf("browse %q allowed: %d", bad, code)
		}
	}
	if code, _ := e.do(t, "alice", "512557", "GET", "/api/fs?path=relative", nil); code != 400 {
		t.Fatalf("relative path: %d", code)
	}

	// mkdir
	if code, r := e.do(t, "alice", "512557", "POST", "/api/fs/mkdir", map[string]any{"parent": shared, "name": "newdir"}); code != 201 {
		t.Fatalf("mkdir: %d %v", code, r)
	}
	if st, err := os.Stat(filepath.Join(shared, "newdir")); err != nil || !st.IsDir() {
		t.Fatal("directory not created")
	}
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/fs/mkdir", map[string]any{"parent": shared, "name": "newdir"}); code != 409 {
		t.Fatalf("duplicate mkdir: %d", code)
	}
	for _, name := range []string{"", ".", "..", "a/b", "../x", "a\\b"} {
		if code, _ := e.do(t, "alice", "512557", "POST", "/api/fs/mkdir", map[string]any{"parent": shared, "name": name}); code != 400 {
			t.Fatalf("mkdir name %q: %d", name, code)
		}
	}
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/fs/mkdir", map[string]any{"parent": "/etc", "name": "x"}); code != 403 {
		t.Fatalf("mkdir outside roots: %d", code)
	}
	// a picked directory is usable as a session cwd
	if code, _ := e.do(t, "alice", "512557", "POST", "/api/sessions", map[string]any{"cwd": filepath.Join(shared, "projA", "src")}); code != 201 {
		t.Fatalf("session in picked dir: %d", code)
	}
}
