package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func termEnv(t *testing.T, users string, nets string) (*httptest.Server, Config) {
	t.Helper()
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not installed")
	}
	dir := t.TempDir()
	t.Setenv("HARNESS_PASSWORD", "test-pass-1")
	cfg := Config{
		Password: "test-pass-1", DataDir: filepath.Join(dir, "data"), ClaudeBin: "/bin/false", Port: "0",
		IdleTimeout: time.Hour, MaxProcs: 4, TokenTTL: time.Hour,
		TerminalUsers: splitList(users, ","), TerminalMax: 2, TerminalShell: "/bin/bash",
		TmuxSocket: "cchtest" + filepath.Base(dir),
	}
	for _, n := range splitList(nets, ",") {
		_, ipn, err := net.ParseCIDR(n)
		if err != nil {
			t.Fatal(err)
		}
		cfg.TerminalNets = append(cfg.TerminalNets, ipn)
	}
	os.MkdirAll(cfg.DataDir, 0o700)
	auth, err := NewAuth(cfg)
	if err != nil {
		t.Fatal(err)
	}
	mgr := NewManager(cfg, NewStore(cfg.DataDir))
	api := NewAPI(cfg, auth, mgr)
	srv := httptest.NewServer(api.Handler())
	t.Cleanup(func() {
		srv.Close()
		api.term.KillAttachments()
		mgr.StopAll()
		exec.Command("tmux", "-L", cfg.TmuxSocket, "kill-server").Run()
	})
	return srv, cfg
}

func tcall(t *testing.T, srv *httptest.Server, user, method, path string, body any) (int, map[string]any) {
	t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, srv.URL+path, rd)
	req.SetBasicAuth(user, "test-pass-1")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// screen reads one terminal SSE stream.
type screen struct {
	att  string
	out  chan string
	stop context.CancelFunc
}

func openScreen(t *testing.T, srv *httptest.Server, user, id string) *screen {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	req, _ := http.NewRequestWithContext(ctx, "GET", srv.URL+"/api/terminals/"+id+"/stream?cols=100&rows=30", nil)
	req.SetBasicAuth(user, "test-pass-1")
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 200 {
		cancel()
		t.Fatalf("stream: %v %v", err, resp)
	}
	s := &screen{out: make(chan string, 256), stop: cancel}
	first := make(chan string, 1)
	go func() {
		defer resp.Body.Close()
		sc := bufio.NewScanner(resp.Body)
		sc.Buffer(make([]byte, 1<<20), 1<<20)
		for sc.Scan() {
			line := sc.Text()
			if !strings.HasPrefix(line, "data: ") {
				continue
			}
			var ev struct {
				Attach string `json:"attach"`
				D      string `json:"d"`
				Exit   bool   `json:"exit"`
			}
			if json.Unmarshal([]byte(line[6:]), &ev) != nil {
				continue
			}
			switch {
			case ev.Attach != "":
				first <- ev.Attach
			case ev.D != "":
				b, _ := base64.StdEncoding.DecodeString(ev.D)
				s.out <- string(b)
			case ev.Exit:
				s.out <- "\x00EXIT"
			}
		}
	}()
	select {
	case s.att = <-first:
	case <-time.After(5 * time.Second):
		cancel()
		t.Fatal("no attach event")
	}
	t.Cleanup(cancel)
	return s
}

func (s *screen) waitFor(t *testing.T, srv *httptest.Server, user, want string) string {
	t.Helper()
	var acc strings.Builder
	deadline := time.After(10 * time.Second)
	for {
		select {
		case c := <-s.out:
			acc.WriteString(c)
			if strings.Contains(acc.String(), want) {
				return acc.String()
			}
		case <-deadline:
			t.Fatalf("timeout waiting for %q, got %q", want, acc.String())
		}
	}
}

func (s *screen) type_(t *testing.T, srv *httptest.Server, user, text string) {
	t.Helper()
	req, _ := http.NewRequest("POST", srv.URL+"/api/term-attachments/"+s.att+"/input", strings.NewReader(text))
	req.SetBasicAuth(user, "test-pass-1")
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 204 {
		t.Fatalf("input: %v %v", err, resp)
	}
	resp.Body.Close()
}

func TestTerminalPolicy(t *testing.T) {
	srv, _ := termEnv(t, "alice", "127.0.0.0/8")
	if code, _ := tcall(t, srv, "bob", "GET", "/api/terminals", nil); code != 403 {
		t.Fatalf("user not enabled: %d", code)
	}
	if code, out := tcall(t, srv, "alice", "GET", "/api/terminals", nil); code != 200 {
		t.Fatalf("enabled user: %d %v", code, out)
	}
	if _, me := tcall(t, srv, "alice", "GET", "/api/me", nil); me["terminal"] != true {
		t.Fatalf("me.terminal = %v", me["terminal"])
	}
	if _, me := tcall(t, srv, "bob", "GET", "/api/me", nil); me["terminal"] != false {
		t.Fatalf("me.terminal for bob = %v", me["terminal"])
	}

	// the same user from a network outside the allowlist (the test client is 127.0.0.1)
	srv2, _ := termEnv(t, "alice", "10.0.0.0/8")
	code, out := tcall(t, srv2, "alice", "GET", "/api/terminals", nil)
	if code != 403 || !strings.Contains(out["error"].(string), "tailnet") {
		t.Fatalf("outside net: %d %v", code, out)
	}
	if _, me := tcall(t, srv2, "alice", "GET", "/api/me", nil); me["terminal"] != false {
		t.Fatalf("me.terminal outside net = %v", me["terminal"])
	}
}

func TestTerminalSessionPersistsAcrossConnections(t *testing.T) {
	srv, _ := termEnv(t, "alice,bob", "127.0.0.0/8")
	code, ti := tcall(t, srv, "alice", "POST", "/api/terminals", map[string]any{"title": "work"})
	if code != 201 {
		t.Fatalf("create: %d %v", code, ti)
	}
	id := ti["id"].(string)

	if _, l := tcall(t, srv, "bob", "GET", "/api/terminals", nil); len(l["terminals"].([]any)) != 0 {
		t.Fatalf("bob sees alice's terminal: %v", l)
	}
	if code, _ := tcall(t, srv, "bob", "DELETE", "/api/terminals/"+id, nil); code != 404 {
		t.Fatalf("bob deleted alice's terminal: %d", code)
	}

	t.Setenv("HARNESS_SECRET_PROBE", "leaked")
	s := openScreen(t, srv, "alice", id)
	s.type_(t, srv, "alice", "echo cch-$((40+2)) [$HARNESS_PASSWORD$HARNESS_SECRET_PROBE]\n")
	got := s.waitFor(t, srv, "alice", "cch-42 []")
	if strings.Contains(got, "test-pass-1") || strings.Contains(got, "leaked") {
		t.Fatalf("service secrets reached the shell: %q", got)
	}

	// another user cannot type into alice's screen
	req, _ := http.NewRequest("POST", srv.URL+"/api/term-attachments/"+s.att+"/input", strings.NewReader("id\n"))
	req.SetBasicAuth("bob", "test-pass-1")
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != 404 {
		t.Fatalf("foreign input: %v %v", err, resp)
	}

	if code, _ := tcall(t, srv, "alice", "POST", "/api/term-attachments/"+s.att+"/resize", map[string]int{"cols": 60, "rows": 20}); code != 204 {
		t.Fatalf("resize: %d", code)
	}

	// dropping the connection keeps the shell; reconnecting repaints what was on screen
	s.stop()
	time.Sleep(300 * time.Millisecond)
	if _, l := tcall(t, srv, "alice", "GET", "/api/terminals", nil); len(l["terminals"].([]any)) != 1 {
		t.Fatalf("terminal vanished: %v", l)
	}
	s2 := openScreen(t, srv, "alice", id)
	s2.waitFor(t, srv, "alice", "cch-42")

	// limit per user (TerminalMax = 2)
	if code, _ := tcall(t, srv, "alice", "POST", "/api/terminals", map[string]any{}); code != 201 {
		t.Fatalf("second terminal: %d", code)
	}
	if code, out := tcall(t, srv, "alice", "POST", "/api/terminals", map[string]any{}); code != 400 {
		t.Fatalf("third terminal should hit the limit: %d %v", code, out)
	}

	if code, _ := tcall(t, srv, "alice", "DELETE", "/api/terminals/"+id, nil); code != 204 {
		t.Fatalf("delete: %d", code)
	}
	s2.waitFor(t, srv, "alice", "\x00EXIT")
}

func TestTerminalRejectsBadInput(t *testing.T) {
	srv, _ := termEnv(t, "alice", "127.0.0.0/8")
	if code, _ := tcall(t, srv, "alice", "POST", "/api/terminals", map[string]any{"kind": "bogus"}); code != 400 {
		t.Fatalf("unknown kind: %d", code)
	}
	if code, _ := tcall(t, srv, "alice", "POST", "/api/terminals", map[string]any{"cwd": "/etc"}); code != 400 {
		t.Fatalf("cwd outside the roots: %d", code)
	}
	if code, _ := tcall(t, srv, "alice", "POST", "/api/terminals", map[string]any{"kind": "claude", "session": "nope"}); code != 404 {
		t.Fatalf("claude takeover of a missing session: %d", code)
	}
	if code, _ := tcall(t, srv, "alice", "DELETE", "/api/terminals/..%2fx", nil); code != 404 {
		t.Fatalf("bad id: %d", code)
	}
}

func TestTerminalTitleCannotInjectTmuxCommands(t *testing.T) {
	srv, _ := termEnv(t, "alice", "127.0.0.0/8")
	for _, title := range []string{";", "x;", "a;kill-server", "tab\there\nnewline"} {
		code, ti := tcall(t, srv, "alice", "POST", "/api/terminals", map[string]any{"title": title})
		if code != 201 {
			t.Fatalf("title %q: %d %v", title, code, ti)
		}
		if strings.ContainsAny(ti["title"].(string), ";\t\n") {
			t.Fatalf("unsafe title kept: %q", ti["title"])
		}
		tcall(t, srv, "alice", "DELETE", "/api/terminals/"+ti["id"].(string), nil)
	}
	if got := cleanTitle(strings.Repeat("长", 150)); len([]rune(got)) != 100 {
		t.Fatalf("title not capped: %d", len([]rune(got)))
	}
	// the tmux server must still be alive and listing works after those titles
	if code, _ := tcall(t, srv, "alice", "GET", "/api/terminals", nil); code != 200 {
		t.Fatalf("list after odd titles: %d", code)
	}
}
