package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func singleUserServer(t *testing.T, user string) (*httptest.Server, Config) {
	t.Helper()
	dir := t.TempDir()
	cfg := Config{
		Password: "pw-one", User: user, DataDir: filepath.Join(dir, "data"), ClaudeBin: "/bin/false", Port: "0",
		IdleTimeout: time.Hour, MaxProcs: 4, TokenTTL: time.Hour,
	}
	os.MkdirAll(cfg.DataDir, 0o700)
	auth, err := NewAuth(cfg)
	if err != nil {
		t.Fatal(err)
	}
	mgr := NewManager(cfg, NewStore(cfg.DataDir))
	srv := httptest.NewServer(NewAPI(cfg, auth, mgr).Handler())
	t.Cleanup(func() { srv.Close(); mgr.StopAll() })
	return srv, cfg
}

func login(t *testing.T, srv *httptest.Server, user, pw string) (int, map[string]any) {
	t.Helper()
	b, _ := json.Marshal(map[string]string{"user": user, "password": pw})
	resp, err := http.Post(srv.URL+"/api/login", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func TestSingleUserModeIgnoresTheTypedName(t *testing.T) {
	srv, _ := singleUserServer(t, "owner")
	for _, name := range []string{"whatever", "", "BOB", "claude", "tony"} {
		code, out := login(t, srv, name, "pw-one")
		if code != 200 || out["user"] != "owner" {
			t.Fatalf("login as %q: %d %v", name, code, out)
		}
	}
	if code, _ := login(t, srv, "owner", "wrong"); code != 401 {
		t.Fatalf("wrong password accepted: %d", code)
	}

	// every name sees the same sessions
	if code, s := tcallPW(t, srv, "a", "pw-one", "POST", "/api/sessions", map[string]any{"title": "shared"}); code != 201 {
		t.Fatalf("create: %d %v", code, s)
	}
	_, l := tcallPW(t, srv, "someone-else", "pw-one", "GET", "/api/sessions", nil)
	if n := len(l["sessions"].([]any)); n != 1 {
		t.Fatalf("second name sees %d sessions", n)
	}
	if code, _ := tcallPW(t, srv, "a", "nope", "GET", "/api/sessions", nil); code != 401 {
		t.Fatalf("bad password via basic auth: %d", code)
	}
}

func TestSingleUserModeAcceptsTokensIssuedUnderOtherNames(t *testing.T) {
	// tokens carry the name typed at login time; after switching to single-user mode they must still work
	multi := Config{Password: "pw-one", DataDir: t.TempDir(), TokenTTL: time.Hour}
	oldAuth, err := NewAuth(multi)
	if err != nil {
		t.Fatal(err)
	}
	old := oldAuth.Issue("claude")

	srv, cfg := singleUserServer(t, "owner")
	// same secret and password => same signing key
	secret, _ := os.ReadFile(filepath.Join(multi.DataDir, "secret"))
	os.WriteFile(filepath.Join(cfg.DataDir, "secret"), secret, 0o600)
	srv.Close()
	auth, _ := NewAuth(cfg)
	mgr := NewManager(cfg, NewStore(cfg.DataDir))
	srv2 := httptest.NewServer(NewAPI(cfg, auth, mgr).Handler())
	defer srv2.Close()
	defer mgr.StopAll()

	req, _ := http.NewRequest("GET", srv2.URL+"/api/me", nil)
	req.Header.Set("Authorization", "Bearer "+old)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var me map[string]any
	json.NewDecoder(resp.Body).Decode(&me)
	if resp.StatusCode != 200 || me["user"] != "owner" {
		t.Fatalf("old token: %d %v", resp.StatusCode, me)
	}
}

func TestSingleUserConfig(t *testing.T) {
	t.Setenv("HARNESS_PASSWORD", "x")
	t.Setenv("HARNESS_DATA", t.TempDir())
	t.Setenv("HARNESS_USER", " Tony ")
	cfg, err := LoadConfig()
	if err != nil || cfg.User != "tony" {
		t.Fatalf("user: %q %v", cfg.User, err)
	}
	t.Setenv("HARNESS_TERMINAL", "1")
	cfg, _ = LoadConfig()
	if len(cfg.TerminalUsers) != 1 || cfg.TerminalUsers[0] != "*" {
		t.Fatalf("HARNESS_TERMINAL=1 => %v", cfg.TerminalUsers)
	}
	t.Setenv("HARNESS_USER_PASSWORDS", "bob=pw")
	if _, err := LoadConfig(); err == nil {
		t.Fatal("HARNESS_USER with HARNESS_USER_PASSWORDS must be rejected")
	}
	t.Setenv("HARNESS_USER_PASSWORDS", "")
	t.Setenv("HARNESS_USER", "bad name!")
	if _, err := LoadConfig(); err == nil {
		t.Fatal("invalid HARNESS_USER accepted")
	}
}

func tcallPW(t *testing.T, srv *httptest.Server, user, pw, method, path string, body any) (int, map[string]any) {
	t.Helper()
	var rd *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	} else {
		rd = bytes.NewReader(nil)
	}
	req, _ := http.NewRequest(method, srv.URL+path, rd)
	req.SetBasicAuth(user, pw)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}
