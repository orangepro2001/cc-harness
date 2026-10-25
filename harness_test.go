package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
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
			emit(`{"type":"system","subtype":"init","session_id":"s","model":"mock","permissionMode":"default","slash_commands":["compact","context","review"]}`)
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
			if parts, ok := msg["content"].([]any); ok {
				images := 0
				for _, p := range parts {
					pm, _ := p.(map[string]any)
					switch pm["type"] {
					case "text":
						t += pm["text"].(string)
					case "image":
						images++
					}
				}
				t += fmt.Sprintf(" IMAGES=%d", images)
			}
			pendingTurns <- t
		case "control_response":
			resp, _ := m["response"].(map[string]any)
			inner, _ := resp["response"].(map[string]any)
			respCh <- inner
		case "control_request":
			req, _ := m["request"].(map[string]any)
			id, _ := m["request_id"].(string)
			if f := os.Getenv("MOCK_ARGS_FILE"); f != "" {
				fh, _ := os.OpenFile(f, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
				b, _ := json.Marshal(req)
				fmt.Fprintln(fh, "CONTROL "+string(b))
				fh.Close()
			}
			if req["subtype"] == "initialize" {
				emit(fmt.Sprintf(`{"type":"control_response","response":{"subtype":"success","request_id":%q,"response":{"models":[{"value":"default","displayName":"Default","supportsEffort":true,"supportedEffortLevels":["low","medium","high","max","bogus"]},{"value":"haiku","displayName":"Haiku"}]}}}`, id))
				continue
			}
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
	t.Setenv("HARNESS_PASSWORD", "test-pass-1")
	cfg := Config{
		Password: "test-pass-1", UserPasswords: map[string]string{"carol": "own-pass"},
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
	_, h := e.do(t, user, "test-pass-1", "GET", "/api/sessions/"+id+"/history", nil)
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
	_, v := e.do(t, user, "test-pass-1", "GET", "/api/sessions/"+id, nil)
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
	code, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"title": "t1"})
	if code != 201 {
		t.Fatalf("create: %d %v", code, v)
	}
	id := v["id"].(string)

	// plain turn
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "hello"}); code != 202 {
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
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "TOOL please"})
	waitFor(t, "awaiting", func() bool { return e.status(t, "alice", id) == "awaiting_permission" })
	_, sv := e.do(t, "alice", "test-pass-1", "GET", "/api/sessions/"+id, nil)
	if p := sv["pending"].([]any); len(p) != 1 {
		t.Fatalf("pending = %v", p)
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/permissions/nope", map[string]any{"allow": true}); code != 404 {
		t.Fatalf("unknown rid: %d", code)
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/permissions/rq1", map[string]any{"allow": true, "remember": true}); code != 200 {
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
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "TOOL again"})
	waitFor(t, "awaiting2", func() bool { return e.status(t, "alice", id) == "awaiting_permission" })
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/permissions/rq1", map[string]any{"allow": false, "message": "nope"})
	waitFor(t, "3 results", func() bool { return countType(e.history(t, "alice", id), "result") == 3 })

	// stop the process, then a new message must --resume
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/stop", map[string]any{})
	waitFor(t, "stopped", func() bool { return e.status(t, "alice", id) == "stopped" })
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "again"})
	waitFor(t, "4 results", func() bool { return countType(e.history(t, "alice", id), "result") == 4 })
	argv, _ := os.ReadFile(e.args)
	ls := strings.Split(strings.TrimSpace(string(argv)), "\n")
	if len(ls) != 2 || !strings.Contains(ls[0], "--session-id "+id) || !strings.Contains(ls[1], "--resume "+id) {
		t.Fatalf("unexpected spawn args: %q", ls)
	}
	if !strings.Contains(ls[0], "--permission-prompt-tool stdio") || !strings.Contains(ls[0], "--permission-mode auto") {
		t.Fatalf("missing permission flags: %q", ls[0])
	}
}

func TestInterruptAndStopMidTurn(t *testing.T) {
	e := newEnv(t)
	_, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", nil)
	id := v["id"].(string)
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "SLEEP"})
	waitFor(t, "busy", func() bool { return e.status(t, "alice", id) == "busy" })
	if code, r := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/interrupt", map[string]any{}); code != 200 {
		t.Fatalf("interrupt: %d %v", code, r)
	}
	// the CLI ends the interrupted turn with an error result -> back to idle
	waitFor(t, "idle after interrupt", func() bool { return e.status(t, "alice", id) == "idle" })

	// killing a process that is mid-turn must surface an error and reset the state
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "queued"})
	waitFor(t, "busy again", func() bool { return e.status(t, "alice", id) == "busy" })
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/stop", map[string]any{})
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
	_, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"title": "private"})
	id := v["id"].(string)

	if code, _ := e.do(t, "bob", "test-pass-1", "GET", "/api/sessions/"+id, nil); code != 404 {
		t.Fatalf("bob read alice session: %d", code)
	}
	if code, _ := e.do(t, "bob", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "x"}); code != 404 {
		t.Fatalf("bob sent to alice session: %d", code)
	}
	if code, _ := e.do(t, "bob", "test-pass-1", "DELETE", "/api/sessions/"+id, nil); code != 404 {
		t.Fatalf("bob deleted alice session: %d", code)
	}
	_, l := e.do(t, "bob", "test-pass-1", "GET", "/api/sessions", nil)
	if n := len(l["sessions"].([]any)); n != 0 {
		t.Fatalf("bob sees %d sessions", n)
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "GET", "/api/sessions/../../etc", nil); code == 200 {
		t.Fatal("path traversal accepted")
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "GET", "/api/sessions/not-a-uuid", nil); code != 404 {
		t.Fatalf("bad id: %d", code)
	}

	// cwd rules
	for _, bad := range []string{"/etc", "../../..", "/", filepath.Join(filepath.Dir(e.cfg.DataDir), "elsewhere")} {
		if code, r := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"cwd": bad}); code != 400 {
			t.Fatalf("cwd %q accepted: %d %v", bad, code, r)
		}
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"cwd": "proj/sub"}); code != 201 {
		t.Fatalf("relative cwd rejected: %d", code)
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"cwd": e.cfg.ExtraRoots[0]}); code != 201 {
		t.Fatalf("extra root rejected: %d", code)
	}
	// symlink escape from the workspace
	ws, _ := e.mgr.store.Workspace("alice")
	os.Symlink("/etc", filepath.Join(ws, "link"))
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"cwd": "link"}); code != 400 {
		t.Fatalf("symlink escape accepted: %d", code)
	}
	// bypass disabled by default
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"permissionMode": "bypassPermissions"}); code != 400 {
		t.Fatalf("bypass accepted: %d", code)
	}

	// per-user password
	if code, _ := e.do(t, "carol", "test-pass-1", "GET", "/api/me", nil); code != 401 {
		t.Fatalf("carol logged in with shared password: %d", code)
	}
	if code, _ := e.do(t, "carol", "own-pass", "GET", "/api/me", nil); code != 200 {
		t.Fatalf("carol own password: %d", code)
	}

	// delete really removes files
	if code, _ := e.do(t, "alice", "test-pass-1", "DELETE", "/api/sessions/"+id, nil); code != 200 {
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
	r := post("alice", "test-pass-1")
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
	if resp := post("alice", "test-pass-1"); resp.StatusCode != 429 || resp.Header.Get("Retry-After") == "" {
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
	_, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", nil)
	id := v["id"].(string)
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "one"})
	waitFor(t, "result", func() bool { return countType(e.history(t, "alice", id), "result") == 1 })

	read := func(after string, want func(ids []string, datas []string) bool) []string {
		req, _ := http.NewRequest("GET", e.srv.URL+"/api/sessions/"+id+"/events", nil)
		req.SetBasicAuth("alice", "test-pass-1")
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
		e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "two"})
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
	_, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"title": "keep"})
	id := v["id"].(string)
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "hello"})
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
	_, sv := e.do(t, "alice", "test-pass-1", "GET", "/api/sessions/"+id, nil)
	if sv["title"] != "keep" || sv["status"] != "stopped" || sv["started"] != true {
		t.Fatalf("restored view: %v", sv)
	}
	if got := len(e.history(t, "alice", id)); got < before {
		t.Fatalf("history shrank: %d < %d", got, before)
	}
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "after restart"})
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
	code, v := e.do(t, "alice", "test-pass-1", "GET", "/api/fs", nil)
	if code != 200 || len(v["entries"].([]any)) != 2 || v["path"] != "" {
		t.Fatalf("roots: %d %v", code, v)
	}
	// listing: dirs only, sorted, no hidden, no escaping symlink, valid symlink kept
	code, v = e.do(t, "alice", "test-pass-1", "GET", "/api/fs?path="+shared, nil)
	if code != 200 || strings.Join(names(v), ",") != "alias,projA,projB" {
		t.Fatalf("list: %d %v", code, names(v))
	}
	if v["parent"] != "" {
		t.Fatalf("root parent should be empty, got %v", v["parent"])
	}
	if _, v = e.do(t, "alice", "test-pass-1", "GET", "/api/fs?hidden=1&path="+shared, nil); !strings.Contains(strings.Join(names(v), ","), ".hidden") {
		t.Fatalf("hidden not shown: %v", names(v))
	}
	code, v = e.do(t, "alice", "test-pass-1", "GET", "/api/fs?path="+filepath.Join(shared, "projA"), nil)
	if code != 200 || v["parent"] != shared || strings.Join(names(v), ",") != "src" {
		t.Fatalf("sub list: %d %v", code, v)
	}
	// forbidden targets
	other := filepath.Join(e.cfg.DataDir, "users", "bob", "workspace")
	os.MkdirAll(other, 0o700)
	for _, bad := range []string{"/etc", "/", filepath.Join(shared, "escape"), other, filepath.Join(shared, "..")} {
		if code, _ := e.do(t, "alice", "test-pass-1", "GET", "/api/fs?path="+bad, nil); code != 403 {
			t.Fatalf("browse %q allowed: %d", bad, code)
		}
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "GET", "/api/fs?path=relative", nil); code != 400 {
		t.Fatalf("relative path: %d", code)
	}

	// mkdir
	if code, r := e.do(t, "alice", "test-pass-1", "POST", "/api/fs/mkdir", map[string]any{"parent": shared, "name": "newdir"}); code != 201 {
		t.Fatalf("mkdir: %d %v", code, r)
	}
	if st, err := os.Stat(filepath.Join(shared, "newdir")); err != nil || !st.IsDir() {
		t.Fatal("directory not created")
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/fs/mkdir", map[string]any{"parent": shared, "name": "newdir"}); code != 409 {
		t.Fatalf("duplicate mkdir: %d", code)
	}
	for _, name := range []string{"", ".", "..", "a/b", "../x", "a\\b"} {
		if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/fs/mkdir", map[string]any{"parent": shared, "name": name}); code != 400 {
			t.Fatalf("mkdir name %q: %d", name, code)
		}
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/fs/mkdir", map[string]any{"parent": "/etc", "name": "x"}); code != 403 {
		t.Fatalf("mkdir outside roots: %d", code)
	}
	// a picked directory is usable as a session cwd
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"cwd": filepath.Join(shared, "projA", "src")}); code != 201 {
		t.Fatalf("session in picked dir: %d", code)
	}
}

func (e *env) rawDo(t *testing.T, user, method, path string, body []byte) (int, []byte, http.Header) {
	t.Helper()
	req, _ := http.NewRequest(method, e.srv.URL+path, bytes.NewReader(body))
	req.SetBasicAuth(user, "test-pass-1")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, b, resp.Header
}

// 1x1 transparent PNG.
var tinyPNG = []byte{
	0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
	0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4,
	0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0xf8, 0xff, 0xff, 0x3f,
	0x00, 0x05, 0xfe, 0x02, 0xfe, 0xa7, 0x35, 0x81, 0x84, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e,
	0x44, 0xae, 0x42, 0x60, 0x82,
}

func TestUploadsAndAttachments(t *testing.T) {
	e := newEnv(t)
	e.cfg.MaxUpload = 0
	_, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{})
	id := v["id"].(string)
	base := "/api/sessions/" + id

	upload := func(user, name string, data []byte) (int, map[string]any) {
		code, b, _ := e.rawDo(t, user, "POST", base+"/uploads?name="+url.QueryEscape(name), data)
		var m map[string]any
		json.Unmarshal(b, &m)
		return code, m
	}

	code, img := upload("alice", "../../etc/pic.png", tinyPNG)
	if code != 201 || img["kind"] != "image" || img["mime"] != "image/png" || img["name"] != "pic.png" {
		t.Fatalf("image upload: %d %v", code, img)
	}
	// A disguised executable named .png is classified by content, not by name.
	code, txt := upload("alice", "notes 报告.txt", []byte("hello notes\n"))
	if code != 201 || txt["kind"] != "file" {
		t.Fatalf("file upload: %d %v", code, txt)
	}
	if code, _ := upload("alice", "empty.txt", nil); code != 400 {
		t.Fatalf("empty upload should be 400, got %d", code)
	}
	// Another user cannot see or use the session's uploads.
	if code, _ := upload("bob", "x.txt", []byte("x")); code != 404 {
		t.Fatalf("other user upload: %d", code)
	}
	if code, _, _ := e.rawDo(t, "bob", "GET", base+"/uploads/"+img["id"].(string), nil); code != 404 {
		t.Fatalf("other user download: %d", code)
	}

	code, body, hdr := e.rawDo(t, "alice", "GET", base+"/uploads/"+img["id"].(string), nil)
	if code != 200 || !bytes.Equal(body, tinyPNG) || hdr.Get("Content-Type") != "image/png" || hdr.Get("Content-Disposition") != "inline" {
		t.Fatalf("image download: %d %q %v", code, hdr.Get("Content-Type"), hdr)
	}
	code, body, hdr = e.rawDo(t, "alice", "GET", base+"/uploads/"+txt["id"].(string), nil)
	if code != 200 || string(body) != "hello notes\n" || !strings.HasPrefix(hdr.Get("Content-Disposition"), "attachment;") {
		t.Fatalf("file download: %d %q %v", code, body, hdr)
	}
	if code, _, _ := e.rawDo(t, "alice", "GET", base+"/uploads/..%2f..%2fmeta", nil); code != 404 {
		t.Fatalf("traversal id: %d", code)
	}

	// Unknown attachment ids are rejected.
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", base+"/messages", map[string]any{"text": "x", "attachments": []string{"0000000000000000"}}); code != 400 {
		t.Fatalf("unknown attachment: %d", code)
	}
	// An attachment alone is a valid message.
	code, m := e.do(t, "alice", "test-pass-1", "POST", base+"/messages", map[string]any{
		"text": "look", "attachments": []string{img["id"].(string), txt["id"].(string)},
	})
	if code != 202 {
		t.Fatalf("send with attachments: %d %v", code, m)
	}
	waitFor(t, "idle", func() bool { return e.status(t, "alice", id) == "idle" })
	evs := e.history(t, "alice", id)
	var user, reply map[string]any
	for _, ev := range evs {
		if ev["kind"] == "user_message" {
			user = ev
		}
		if ev["type"] == "assistant" {
			reply = ev
		}
	}
	atts, _ := user["attachments"].([]any)
	if len(atts) != 2 {
		t.Fatalf("user_message attachments: %v", user)
	}
	b, _ := json.Marshal(reply)
	if !strings.Contains(string(b), "IMAGES=1") || !strings.Contains(string(b), "notes ") {
		t.Fatalf("model should get 1 inline image and the file path note: %s", b)
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", base+"/messages", map[string]any{}); code != 400 {
		t.Fatalf("empty message: %d", code)
	}
	argv, _ := os.ReadFile(e.args)
	if !strings.Contains(string(argv), "--add-dir") || !strings.Contains(string(argv), "/uploads") {
		t.Fatalf("claude must be given the uploads dir: %s", argv)
	}
}

func TestUploadLimit(t *testing.T) {
	e := newEnv(t)
	e.cfg.MaxUpload = 1 << 20
	// Rebuild the server so the lowered limit applies.
	auth, _ := NewAuth(e.cfg)
	srv := httptest.NewServer(NewAPI(e.cfg, auth, e.mgr).Handler())
	defer srv.Close()
	e.srv = srv
	_, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{})
	id := v["id"].(string)
	code, _, _ := e.rawDo(t, "alice", "POST", "/api/sessions/"+id+"/uploads?name=big.bin", bytes.Repeat([]byte("a"), 2<<20))
	if code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize upload: %d", code)
	}
	if entries, _ := os.ReadDir(filepath.Join(e.cfg.DataDir, "users", "alice", "sessions", id, "uploads")); len(entries) != 0 {
		t.Fatalf("failed upload left files behind: %v", entries)
	}
}

func TestEffortAndCommands(t *testing.T) {
	e := newEnv(t)
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"effort": "turbo"}); code != 400 {
		t.Fatalf("invalid effort accepted: %d", code)
	}
	_, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{"effort": "high"})
	id := v["id"].(string)
	base := "/api/sessions/" + id
	if v["effort"] != "high" {
		t.Fatalf("effort not stored: %v", v)
	}

	// Before the CLI has started there is nothing to offer yet.
	if _, c := e.do(t, "alice", "test-pass-1", "GET", base+"/commands", nil); len(c["commands"].([]any)) != 0 {
		t.Fatalf("commands before start: %v", c)
	}
	e.do(t, "alice", "test-pass-1", "POST", base+"/messages", map[string]any{"text": "hi"})
	waitFor(t, "idle", func() bool { return e.status(t, "alice", id) == "idle" })
	_, c := e.do(t, "alice", "test-pass-1", "GET", base+"/commands", nil)
	if got := c["commands"].([]any); len(got) != 3 || got[0] != "compact" {
		t.Fatalf("commands after init: %v", c)
	}
	// A brand-new session inherits the last known list.
	_, v2 := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{})
	_, c2 := e.do(t, "alice", "test-pass-1", "GET", "/api/sessions/"+v2["id"].(string)+"/commands", nil)
	if len(c2["commands"].([]any)) != 3 {
		t.Fatalf("new session commands: %v", c2)
	}

	// Changing effort is applied in place: the process keeps running and the
	// next turn uses the new level, so nothing is interrupted or restarted.
	if code, _ := e.do(t, "alice", "test-pass-1", "PATCH", base, map[string]any{"effort": "bogus"}); code != 400 {
		t.Fatalf("bogus effort: %d", code)
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "PATCH", base, map[string]any{"effort": "max"}); code != 200 {
		t.Fatal("patch effort")
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "PATCH", base, map[string]any{"ultracode": true}); code != 200 {
		t.Fatal("patch ultracode")
	}
	if got := e.status(t, "alice", id); got != "idle" {
		t.Fatalf("process should stay up, status %s", got)
	}
	argv, _ := os.ReadFile(e.args)
	if !strings.Contains(string(argv), "--effort high") || strings.Contains(string(argv), "--effort max") {
		t.Fatalf("effort must not restart the process: %s", argv)
	}
	if !strings.Contains(string(argv), `"effortLevel":"max"`) || !strings.Contains(string(argv), `"ultracode":true`) {
		t.Fatalf("apply_flag_settings not sent: %s", argv)
	}
	_, sv := e.do(t, "alice", "test-pass-1", "GET", base, nil)
	if sv["effort"] != "max" || sv["ultracode"] != true {
		t.Fatalf("settings not stored: %v", sv)
	}

	// After a restart the saved values come back as flags.
	e.do(t, "alice", "test-pass-1", "POST", base+"/stop", map[string]any{})
	waitFor(t, "stopped", func() bool { return e.status(t, "alice", id) == "stopped" })
	e.do(t, "alice", "test-pass-1", "POST", base+"/messages", map[string]any{"text": "again"})
	waitFor(t, "idle", func() bool { return e.status(t, "alice", id) == "idle" })
	argv, _ = os.ReadFile(e.args)
	if !strings.Contains(string(argv), "--effort max") || !strings.Contains(string(argv), `--settings {"ultracode":true}`) {
		t.Fatalf("saved settings not passed on restart: %s", argv)
	}
}

func TestModelsAndDefaultMode(t *testing.T) {
	e := newEnv(t)
	_, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions", map[string]any{})
	if v["permissionMode"] != "auto" {
		t.Fatalf("new sessions should default to auto: %v", v["permissionMode"])
	}
	code, m := e.do(t, "alice", "test-pass-1", "GET", "/api/models", nil)
	list, _ := m["models"].([]any)
	if code != 200 || len(list) != 2 {
		t.Fatalf("models: %d %v", code, m)
	}
	if lv := list[0].(map[string]any)["effortLevels"].([]any); len(lv) != 4 {
		t.Fatalf("effort levels should drop unknown ones: %v", lv)
	}
	if lv := list[1].(map[string]any)["effortLevels"].([]any); len(lv) != 0 {
		t.Fatalf("a model without effort support offers none: %v", lv)
	}
}

func TestAndroidRelease(t *testing.T) {
	e := newEnv(t)
	if code, _ := e.do(t, "alice", "test-pass-1", "GET", "/api/client/android/latest", nil); code != 404 {
		t.Fatalf("no release should be 404, got %d", code)
	}
	dir := filepath.Join(e.cfg.DataDir, "releases")
	os.MkdirAll(dir, 0o755)
	apk := []byte("PK-fake-apk-bytes-0123456789")
	os.WriteFile(filepath.Join(dir, "app-7.apk"), apk, 0o644)
	sum := sha256.Sum256(apk)
	man := map[string]any{"versionCode": 7, "versionName": "1.2.0", "sha256": hex.EncodeToString(sum[:]), "file": "app-7.apk", "notes": "n"}
	b, _ := json.Marshal(man)
	os.WriteFile(filepath.Join(dir, "android.json"), b, 0o644)

	code, rel := e.do(t, "alice", "test-pass-1", "GET", "/api/client/android/latest", nil)
	if code != 200 || rel["versionCode"] != float64(7) || rel["size"] != float64(len(apk)) || rel["sha256"] != man["sha256"] {
		t.Fatalf("latest: %d %v", code, rel)
	}
	if code, _, _ := e.rawDo(t, "nobody", "GET", "/api/client/android/latest", nil); code != 200 {
		// any valid user may fetch; a wrong password must not
		t.Fatalf("shared-password user: %d", code)
	}
	req, _ := http.NewRequest("GET", e.srv.URL+"/api/client/android/download", nil)
	req.Header.Set("Range", "bytes=3-")
	req.SetBasicAuth("alice", "test-pass-1")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 206 || !bytes.Equal(got, apk[3:]) || resp.Header.Get("Content-Type") != "application/vnd.android.package-archive" {
		t.Fatalf("range download: %d %q %v", resp.StatusCode, got, resp.Header)
	}
	req2, _ := http.NewRequest("GET", e.srv.URL+"/api/client/android/download", nil)
	if resp2, _ := http.DefaultClient.Do(req2); resp2.StatusCode != 401 {
		t.Fatalf("unauthenticated download: %d", resp2.StatusCode)
	}

	// A manifest pointing outside the releases dir is refused.
	man["file"] = "../android.json"
	b, _ = json.Marshal(man)
	os.WriteFile(filepath.Join(dir, "android.json"), b, 0o644)
	if code, _ := e.do(t, "alice", "test-pass-1", "GET", "/api/client/android/latest", nil); code != 404 {
		t.Fatalf("traversal manifest accepted: %d", code)
	}
}

func TestBasePath(t *testing.T) {
	e := newEnv(t)
	e.cfg.BasePath = "/claude"
	auth, _ := NewAuth(e.cfg)
	srv := httptest.NewServer(NewAPI(e.cfg, auth, e.mgr).Handler())
	defer srv.Close()

	get := func(path string) (int, string, http.Header) {
		resp, err := http.Get(srv.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b), resp.Header
	}
	// The page is the same whether or not the proxy strips the prefix.
	for _, p := range []string{"/", "/claude", "/claude/"} {
		code, body, _ := get(p)
		if code != 200 || !strings.Contains(body, `<base href="/claude/">`) {
			t.Fatalf("GET %s: %d %q", p, code, body)
		}
	}
	for _, p := range []string{"/app.js", "/claude/app.js"} {
		if code, body, _ := get(p); code != 200 || !strings.Contains(body, "document.baseURI") {
			t.Fatalf("GET %s: %d", p, code)
		}
	}
	for _, p := range []string{"/api/health", "/claude/api/health"} {
		if code, body, _ := get(p); code != 200 || !strings.Contains(body, "ok") {
			t.Fatalf("GET %s: %d %s", p, code, body)
		}
	}
	// Login works under the prefix and scopes the cookie to it.
	body, _ := json.Marshal(map[string]string{"user": "alice", "password": "test-pass-1"})
	resp, err := http.Post(srv.URL+"/claude/api/login", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	var cookie *http.Cookie
	for _, c := range resp.Cookies() {
		if c.Name == cookieName {
			cookie = c
		}
	}
	if resp.StatusCode != 200 || cookie == nil || cookie.Path != "/claude" {
		t.Fatalf("login: %d %+v", resp.StatusCode, cookie)
	}
	// A path that merely starts with the prefix text is not stripped.
	if code, _, _ := get("/claudex/api/health"); code != 404 {
		t.Fatalf("/claudex must not match the prefix, got %d", code)
	}
}

func writeTranscript(t *testing.T, home, proj, id, cwd string, mtime time.Time, extra ...string) string {
	t.Helper()
	dir := filepath.Join(home, "projects", proj)
	os.MkdirAll(dir, 0o755)
	lines := []string{
		`{"type":"queue-operation","operation":"enqueue","content":"hi"}`,
		fmt.Sprintf(`{"type":"attachment","isSidechain":false,"cwd":%q,"sessionId":%q}`, cwd, id),
		fmt.Sprintf(`{"type":"user","isSidechain":false,"isMeta":true,"cwd":%q,"message":{"role":"user","content":"<local-command-caveat>x</local-command-caveat>"}}`, cwd),
		fmt.Sprintf(`{"type":"user","isSidechain":false,"cwd":%q,"message":{"role":"user","content":"整理 README"}}`, cwd),
		`{"type":"assistant","isSidechain":false,"message":{"role":"assistant","content":[{"type":"thinking","thinking":"secret"},{"type":"text","text":"好的"},{"type":"tool_use","id":"tu1","name":"Read","input":{"file_path":"/x"}}]}}`,
		`{"type":"user","isSidechain":false,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"data"}]}}`,
		`{"type":"assistant","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"sub-agent chatter"}]}}`,
		`{"type":"user","isSidechain":false,"message":{"role":"user","content":"<command-name>/review</command-name><command-message>review</command-message><command-args>main</command-args>"}}`,
		`{"type":"ai-title","aiTitle":"旧标题","sessionId":"x"}`,
		`{"type":"ai-title","aiTitle":"整理安装步骤","sessionId":"x"}`,
	}
	lines = append(lines, extra...)
	p := filepath.Join(dir, id+".jsonl")
	if err := os.WriteFile(p, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	os.Chtimes(p, mtime, mtime)
	return p
}

func TestImportLocalSessions(t *testing.T) {
	e := newEnv(t)
	home := filepath.Join(t.TempDir(), "claude-home")
	e.mgr.cfg.ClaudeHome = home
	shared := e.cfg.ExtraRoots[0]
	old := time.Now().Add(-time.Hour)

	idOK := "11111111-1111-4111-8111-111111111111"
	idRecent := "22222222-2222-4222-8222-222222222222"
	idOutside := "33333333-3333-4333-8333-333333333333"
	idEmpty := "44444444-4444-4444-8444-444444444444"
	writeTranscript(t, home, "-shared", idOK, shared, old)
	writeTranscript(t, home, "-shared", idRecent, shared, time.Now())
	writeTranscript(t, home, "-elsewhere", idOutside, filepath.Join(filepath.Dir(shared), "elsewhere"), old)
	os.WriteFile(filepath.Join(home, "projects", "-shared", idEmpty+".jsonl"), []byte(`{"type":"mode","mode":"normal"}`+"\n"), 0o644)
	os.WriteFile(filepath.Join(home, "projects", "-shared", "not-a-uuid.jsonl"), []byte("{}\n"), 0o644)

	list := func() map[string]map[string]any {
		_, v := e.do(t, "alice", "test-pass-1", "GET", "/api/local-sessions", nil)
		out := map[string]map[string]any{}
		for _, x := range v["sessions"].([]any) {
			m := x.(map[string]any)
			out[m["id"].(string)] = m
		}
		return out
	}
	got := list()
	if len(got) != 2 || got[idOK] == nil || got[idRecent] == nil {
		t.Fatalf("listing should hold only the two importable transcripts: %v", got)
	}
	if got[idOK]["title"] != "整理安装步骤" || got[idOK]["active"] != false || got[idRecent]["active"] != true {
		t.Fatalf("metadata: %v", got[idOK])
	}
	if cwd, _ := filepath.EvalSymlinks(shared); got[idOK]["cwd"] != cwd {
		t.Fatalf("cwd %v != %v", got[idOK]["cwd"], cwd)
	}

	// A transcript touched moments ago is refused unless forced.
	if code, v := e.do(t, "alice", "test-pass-1", "POST", "/api/local-sessions/"+idRecent+"/import", nil); code != 409 {
		t.Fatalf("recent import: %d %v", code, v)
	}
	// Outside the allowed roots it is invisible and cannot be imported.
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/local-sessions/"+idOutside+"/import", nil); code != 400 {
		t.Fatalf("outside import: %d", code)
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/local-sessions/"+idEmpty+"/import", nil); code != 400 {
		t.Fatalf("empty import: %d", code)
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/local-sessions/55555555-5555-4555-8555-555555555555/import", nil); code != 404 {
		t.Fatalf("missing import: %d", code)
	}

	code, v := e.do(t, "alice", "test-pass-1", "POST", "/api/local-sessions/"+idOK+"/import", nil)
	if code != 201 || v["id"] != idOK || v["title"] != "整理安装步骤" || v["started"] != true || v["status"] != "stopped" {
		t.Fatalf("import: %d %v", code, v)
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/local-sessions/"+idOK+"/import", nil); code != 409 {
		t.Fatalf("second import: %d", code)
	}
	if _, ok := list()[idOK]; ok {
		t.Fatal("imported session is still offered")
	}
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/local-sessions/"+idRecent+"/import", map[string]any{"force": true}); code != 201 {
		t.Fatalf("forced import: %d", code)
	}

	// History: prompts, commands, assistant text and tool calls; no thinking, no sub-agents.
	evs := e.history(t, "alice", idOK)
	var texts []string
	for _, ev := range evs {
		b, _ := json.Marshal(ev)
		s := string(b)
		if strings.Contains(s, "secret") || strings.Contains(s, "sub-agent chatter") || strings.Contains(s, "local-command-caveat") {
			t.Fatalf("leaked content: %s", s)
		}
		if ev["kind"] == "user_message" {
			texts = append(texts, ev["text"].(string))
		}
	}
	if fmt.Sprint(texts) != "[整理 README /review main]" {
		t.Fatalf("user prompts: %v", texts)
	}
	if countType(evs, "assistant") != 1 || countType(evs, "user") != 1 || evs[0]["kind"] != "imported" {
		t.Fatalf("event mix: %v", evs)
	}

	// Continuing resumes the original session id instead of starting a new one.
	os.Remove(e.args)
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+idOK+"/messages", map[string]any{"text": "continue"})
	waitFor(t, "idle", func() bool { return e.status(t, "alice", idOK) == "idle" })
	argv, _ := os.ReadFile(e.args)
	if !strings.Contains(string(argv), "--resume "+idOK) || strings.Contains(string(argv), "--session-id") {
		t.Fatalf("must resume the original id: %s", argv)
	}

	// Deleting the harness session leaves the original transcript alone.
	e.do(t, "alice", "test-pass-1", "DELETE", "/api/sessions/"+idOK, nil)
	if _, err := os.Stat(filepath.Join(home, "projects", "-shared", idOK+".jsonl")); err != nil {
		t.Fatalf("transcript must survive: %v", err)
	}
}

func TestUsageEndpoint(t *testing.T) {
	e := newEnv(t)
	hits := 0
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		if r.Header.Get("Authorization") != "Bearer tok-1" || r.Header.Get("anthropic-beta") == "" {
			w.WriteHeader(401)
			return
		}
		io.WriteString(w, `{"five_hour":{"utilization":1,"resets_at":"2026-10-24T05:30:00+00:00"},"limits":[
{"kind":"session","percent":12,"severity":"normal","resets_at":"2026-10-24T05:30:00.085270+00:00"},
{"kind":"weekly_all","percent":4,"resets_at":"2026-10-30T12:00:00+00:00"},
{"kind":"weekly_scoped","percent":0,"resets_at":"2026-10-30T12:00:00+00:00","scope":{"model":{"display_name":"Fable"}}},
{"kind":"mystery","percent":99}]}`)
	}))
	defer up.Close()

	home := t.TempDir()
	cfg := e.cfg
	cfg.ClaudeHome, cfg.UsageURL = home, up.URL
	auth, _ := NewAuth(cfg)
	srv := httptest.NewServer(NewAPI(cfg, auth, e.mgr).Handler())
	defer srv.Close()
	e.srv = srv

	// No credentials yet.
	if code, _ := e.do(t, "alice", "test-pass-1", "GET", "/api/usage", nil); code != 503 {
		t.Fatalf("no creds: %d", code)
	}
	if code, _ := e.do(t, "", "", "GET", "/api/usage", nil); code != 401 {
		t.Fatalf("unauthenticated: %d", code)
	}

	creds := func(token string, exp time.Time) {
		b, _ := json.Marshal(map[string]any{"claudeAiOauth": map[string]any{
			"accessToken": token, "expiresAt": exp.UnixMilli(), "subscriptionType": "max"}})
		os.WriteFile(filepath.Join(home, ".credentials.json"), b, 0o600)
	}
	// An expired token is reported, not sent. (The failed lookup is cached briefly,
	// so use a fresh API for each scenario.)
	fresh := func() {
		srv.Close()
		a := NewAPI(cfg, auth, e.mgr)
		srv = httptest.NewServer(a.Handler())
		e.srv = srv
	}
	creds("tok-1", time.Now().Add(-time.Hour))
	fresh()
	if code, v := e.do(t, "alice", "test-pass-1", "GET", "/api/usage", nil); code != 503 || hits != 0 {
		t.Fatalf("expired: %d %v hits=%d", code, v, hits)
	}

	creds("tok-1", time.Now().Add(time.Hour))
	fresh()
	code, v := e.do(t, "alice", "test-pass-1", "GET", "/api/usage", nil)
	if code != 200 {
		t.Fatalf("usage: %d %v", code, v)
	}
	limits, _ := v["limits"].([]any)
	if len(limits) != 3 || v["plan"] != "max" {
		t.Fatalf("limits: %v", v)
	}
	l0 := limits[0].(map[string]any)
	l2 := limits[2].(map[string]any)
	if l0["kind"] != "session" || l0["percent"] != 12.0 || l0["resetsAt"] == nil || l2["label"] != "Fable 额度" {
		t.Fatalf("normalized limits: %v", limits)
	}
	e.do(t, "alice", "test-pass-1", "GET", "/api/usage", nil)
	if hits != 1 {
		t.Fatalf("second call should be cached, upstream hits=%d", hits)
	}
}

func TestSyncExternalTranscript(t *testing.T) {
	e := newEnv(t)
	home := filepath.Join(t.TempDir(), "claude-home")
	e.mgr.cfg.ClaudeHome = home
	shared := e.cfg.ExtraRoots[0]
	id := "66666666-6666-4666-8666-666666666666"
	path := writeTranscript(t, home, "-shared", id, shared, time.Now().Add(-time.Hour))
	if code, _ := e.do(t, "alice", "test-pass-1", "POST", "/api/local-sessions/"+id+"/import", nil); code != 201 {
		t.Fatalf("import: %d", code)
	}
	base := len(e.history(t, "alice", id))

	appendRaw := func(s string) {
		f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
		if err != nil {
			t.Fatal(err)
		}
		f.WriteString(s)
		f.Close()
	}
	sync := func() (float64, map[string]any) {
		code, v := e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/sync", nil)
		if code != 200 {
			t.Fatalf("sync: %d %v", code, v)
		}
		return v["synced"].(float64), v["session"].(map[string]any)
	}

	if n, _ := sync(); n != 0 || len(e.history(t, "alice", id)) != base {
		t.Fatalf("nothing changed, nothing to sync (n=%v)", n)
	}

	// The session is continued in a terminal: one prompt, one answer, and a
	// half-written line that must wait for its newline.
	appendRaw(`{"type":"user","isSidechain":false,"message":{"role":"user","content":"在终端里继续"}}` + "\n")
	appendRaw(`{"type":"assistant","isSidechain":false,"message":{"role":"assistant","content":[{"type":"text","text":"终端回答"}]}}` + "\n")
	appendRaw(`{"type":"assistant","isSidechain":false,"message":{"role":"assistant","content":[{"type":"text","text":"unfin`)
	n, view := sync()
	if n != 2 || view["turns"].(float64) < 2 {
		t.Fatalf("synced %v events, view %v", n, view)
	}
	evs := e.history(t, "alice", id)
	if len(evs) != base+3 || evs[base]["kind"] != "synced" || evs[base+1]["text"] != "在终端里继续" {
		t.Fatalf("history after sync: %v", evs[base:])
	}
	if n, _ := sync(); n != 0 {
		t.Fatalf("second sync must be a no-op, got %v", n)
	}

	// The unfinished line completes later and is picked up then, once.
	appendRaw(`ished"}]}}` + "\n")
	if n, _ := sync(); n != 1 {
		t.Fatalf("completed line: %v", n)
	}
	if n, _ := sync(); n != 0 {
		t.Fatalf("again: %v", n)
	}

	// Continuing in the harness first folds in anything added meanwhile.
	appendRaw(`{"type":"user","isSidechain":false,"message":{"role":"user","content":"又一条"}}` + "\n")
	e.do(t, "alice", "test-pass-1", "POST", "/api/sessions/"+id+"/messages", map[string]any{"text": "from harness"})
	waitFor(t, "idle", func() bool { return e.status(t, "alice", id) == "idle" })
	var order []string
	for _, ev := range e.history(t, "alice", id) {
		if ev["kind"] == "user_message" {
			order = append(order, ev["text"].(string))
		}
	}
	if got := fmt.Sprint(order[len(order)-2:]); got != "[又一条 from harness]" {
		t.Fatalf("order: %v", order)
	}
}
