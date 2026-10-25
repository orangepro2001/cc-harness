package main

import (
	"bytes"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"strconv"
	"strings"
	"time"
)

//go:embed web
var webFS embed.FS

type API struct {
	cfg  Config
	auth *Auth
	mgr  *Manager

	usage  *usageCache
	models *modelsCache
	term   *Terminals
}

func NewAPI(cfg Config, auth *Auth, mgr *Manager) *API {
	return &API{cfg: cfg, auth: auth, mgr: mgr, term: NewTerminals(cfg), usage: &usageCache{http: &http.Client{Timeout: 10 * time.Second}}, models: &modelsCache{}}
}

type authed func(w http.ResponseWriter, r *http.Request, user string)

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid JSON body")
		return false
	}
	return true
}

func (a *API) require(h authed) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !sameOrigin(r) {
			writeErr(w, http.StatusForbidden, "cross-origin request rejected")
			return
		}
		user, err := a.auth.Authenticate(r)
		switch {
		case err == nil:
			h(w, r, user)
		case errors.Is(err, errLocked):
			a.tooMany(w, r)
		default:
			w.Header().Set("WWW-Authenticate", `Basic realm="cc-harness", charset="UTF-8"`)
			if r.Header.Get("X-Requested-With") != "" || strings.HasPrefix(r.URL.Path, "/api/") {
				// avoid the browser's native basic-auth popup for the SPA
				w.Header().Del("WWW-Authenticate")
			}
			writeErr(w, http.StatusUnauthorized, err.Error())
		}
	}
}

func (a *API) tooMany(w http.ResponseWriter, r *http.Request) {
	d, _ := a.auth.Blocked(clientIP(r))
	w.Header().Set("Retry-After", strconv.Itoa(int(d.Seconds())+1))
	writeErr(w, http.StatusTooManyRequests, "too many failed attempts, retry later")
}

func (a *API) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("POST /api/login", a.login)
	mux.HandleFunc("POST /api/logout", func(w http.ResponseWriter, r *http.Request) {
		http.SetCookie(w, &http.Cookie{Name: cookieName, Value: "", Path: a.cookiePath(), MaxAge: -1, HttpOnly: true, SameSite: http.SameSiteStrictMode})
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("GET /api/me", a.require(a.me))

	mux.HandleFunc("GET /api/usage", a.require(a.getUsage))
	mux.HandleFunc("GET /api/models", a.require(a.getModels))

	mux.HandleFunc("GET /api/client/android/latest", a.require(a.latestRelease))
	mux.HandleFunc("GET /api/client/android/download", a.require(a.downloadRelease))

	mux.HandleFunc("GET /api/local-sessions", a.require(a.listLocalSessions))
	mux.HandleFunc("POST /api/local-sessions/{id}/import", a.require(a.importLocalSession))

	mux.HandleFunc("GET /api/fs", a.require(a.listDirs))
	mux.HandleFunc("POST /api/fs/mkdir", a.require(a.makeDir))

	mux.HandleFunc("GET /api/sessions", a.require(a.listSessions))
	mux.HandleFunc("POST /api/sessions", a.require(a.createSession))
	mux.HandleFunc("GET /api/sessions/{id}", a.require(a.withSession(a.getSession)))
	mux.HandleFunc("PATCH /api/sessions/{id}", a.require(a.withSession(a.patchSession)))
	mux.HandleFunc("DELETE /api/sessions/{id}", a.require(a.deleteSession))
	mux.HandleFunc("POST /api/sessions/{id}/messages", a.require(a.withSession(a.sendMessage)))
	mux.HandleFunc("GET /api/sessions/{id}/commands", a.require(a.withSession(a.commands)))
	mux.HandleFunc("POST /api/sessions/{id}/uploads", a.require(a.withSession(a.upload)))
	mux.HandleFunc("GET /api/sessions/{id}/uploads/{uid}", a.require(a.withSession(a.download)))
	mux.HandleFunc("POST /api/sessions/{id}/sync", a.require(a.withSession(a.syncSession)))
	mux.HandleFunc("POST /api/sessions/{id}/interrupt", a.require(a.withSession(a.interrupt)))
	mux.HandleFunc("POST /api/sessions/{id}/stop", a.require(a.withSession(a.stop)))
	mux.HandleFunc("POST /api/sessions/{id}/permissions/{rid}", a.require(a.withSession(a.permission)))
	mux.HandleFunc("GET /api/sessions/{id}/history", a.require(a.withSession(a.history)))
	mux.HandleFunc("GET /api/sessions/{id}/events", a.require(a.withSession(a.events)))

	mux.HandleFunc("GET /api/terminals", a.terminalAPI(a.listTerminals))
	mux.HandleFunc("POST /api/terminals", a.terminalAPI(a.createTerminal))
	mux.HandleFunc("DELETE /api/terminals/{id}", a.terminalAPI(a.deleteTerminal))
	mux.HandleFunc("GET /api/terminals/{id}/stream", a.terminalAPI(a.streamTerminal))
	mux.HandleFunc("POST /api/term-attachments/{aid}/input", a.terminalAPI(a.termInput))
	mux.HandleFunc("POST /api/term-attachments/{aid}/resize", a.terminalAPI(a.termResize))

	sub, _ := fs.Sub(webFS, "web")
	static := http.FileServerFS(sub)
	index, _ := fs.ReadFile(sub, "index.html")
	// The page loads its assets and calls the API relative to <base>, so the same
	// build works at the root and behind a path-routing proxy (HARNESS_BASE_PATH).
	index = bytes.Replace(index, []byte(`<base href="/">`), []byte(`<base href="`+a.cfg.BasePath+`/">`), 1)
	mux.Handle("GET /", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache")
		if r.URL.Path == "/" || r.URL.Path == "/index.html" {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Write(index)
			return
		}
		static.ServeHTTP(w, r)
	}))

	return stripBase(a.cfg.BasePath, secure(mux))
}

// stripBase accepts requests both with and without the configured prefix, so
// it does not matter whether the proxy in front strips it.
func stripBase(base string, h http.Handler) http.Handler {
	if base == "" {
		return h
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if p := r.URL.Path; p == base || strings.HasPrefix(p, base+"/") {
			r2 := new(http.Request)
			*r2 = *r
			u := *r.URL
			u.Path = strings.TrimPrefix(p, base)
			if u.Path == "" {
				u.Path = "/"
			}
			u.RawPath = ""
			r2.URL = &u
			r = r2
		}
		h.ServeHTTP(w, r)
	})
}

func (a *API) cookiePath() string {
	if a.cfg.BasePath != "" {
		return a.cfg.BasePath
	}
	return "/"
}

func secure(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hd := w.Header()
		hd.Set("X-Content-Type-Options", "nosniff")
		hd.Set("X-Frame-Options", "DENY")
		hd.Set("Referrer-Policy", "no-referrer")
		hd.Set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'")
		if strings.HasPrefix(r.URL.Path, "/api/") {
			hd.Set("Cache-Control", "no-store")
		}
		h.ServeHTTP(w, r)
	})
}

// ---- auth endpoints ---------------------------------------------------------

func (a *API) login(w http.ResponseWriter, r *http.Request) {
	if !sameOrigin(r) {
		writeErr(w, http.StatusForbidden, "cross-origin request rejected")
		return
	}
	ip := clientIP(r)
	if _, blocked := a.auth.Blocked(ip); blocked {
		a.tooMany(w, r)
		return
	}
	var req struct {
		User     string `json:"user"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	user := strings.ToLower(strings.TrimSpace(req.User))
	if !a.auth.CheckPassword(user, req.Password) {
		a.auth.Fail(ip)
		writeErr(w, http.StatusUnauthorized, "invalid user or password")
		return
	}
	a.auth.Succeed(ip)
	user = a.auth.Identity(user)
	tok := a.auth.Issue(user)
	http.SetCookie(w, &http.Cookie{
		Name: cookieName, Value: tok, Path: a.cookiePath(), HttpOnly: true,
		SameSite: http.SameSiteStrictMode, MaxAge: int(a.cfg.TokenTTL.Seconds()),
	})
	writeJSON(w, 200, map[string]any{"user": user, "token": tok})
}

func (a *API) me(w http.ResponseWriter, r *http.Request, user string) {
	ws, _ := a.mgr.store.Workspace(user)
	writeJSON(w, 200, map[string]any{
		"user": user, "workspace": ws, "roots": append([]string{}, a.cfg.ExtraRoots...),
		"allowBypass": a.cfg.AllowBypass,
		"terminal":    a.term.Allowed(user, r) == nil,
	})
}

// ---- session endpoints ------------------------------------------------------

func (a *API) listSessions(w http.ResponseWriter, r *http.Request, user string) {
	writeJSON(w, 200, map[string]any{"sessions": a.mgr.List(user)})
}

func (a *API) createSession(w http.ResponseWriter, r *http.Request, user string) {
	var req CreateReq
	if r.ContentLength != 0 && !readJSON(w, r, &req) {
		return
	}
	s, err := a.mgr.Create(user, req)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, s.View())
}

type sessionHandler func(w http.ResponseWriter, r *http.Request, s *Session)

func (a *API) withSession(h sessionHandler) authed {
	return func(w http.ResponseWriter, r *http.Request, user string) {
		s, err := a.mgr.Get(user, r.PathValue("id"))
		if err != nil {
			writeErr(w, http.StatusNotFound, "session not found")
			return
		}
		h(w, r, s)
	}
}

func (a *API) getSession(w http.ResponseWriter, r *http.Request, s *Session) {
	writeJSON(w, 200, s.View())
}

func (a *API) patchSession(w http.ResponseWriter, r *http.Request, s *Session) {
	var req struct {
		Title          *string `json:"title"`
		PermissionMode *string `json:"permissionMode"`
		Model          *string `json:"model"`
		Effort         *string `json:"effort"`
		Ultracode      *bool   `json:"ultracode"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Title != nil {
		if len([]rune(*req.Title)) > 100 {
			writeErr(w, http.StatusBadRequest, "title too long")
			return
		}
		s.SetTitle(strings.TrimSpace(*req.Title))
	}
	if req.PermissionMode != nil {
		if err := s.SetPermissionMode(*req.PermissionMode); err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
	}
	if req.Model != nil {
		if err := s.SetModel(*req.Model); err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
	}
	if req.Effort != nil {
		if err := s.SetEffort(*req.Effort); err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
	}
	if req.Ultracode != nil {
		if err := s.SetUltracode(*req.Ultracode); err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
	}
	writeJSON(w, 200, s.View())
}

func (a *API) commands(w http.ResponseWriter, r *http.Request, s *Session) {
	writeJSON(w, 200, map[string]any{"commands": s.Commands()})
}

func (a *API) deleteSession(w http.ResponseWriter, r *http.Request, user string) {
	if err := a.mgr.Delete(user, r.PathValue("id")); err != nil {
		if errors.Is(err, errNotFound) {
			writeErr(w, http.StatusNotFound, "session not found")
		} else {
			writeErr(w, http.StatusInternalServerError, err.Error())
		}
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (a *API) sendMessage(w http.ResponseWriter, r *http.Request, s *Session) {
	var req struct {
		Text        string   `json:"text"`
		Attachments []string `json:"attachments"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, 512<<10)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	if strings.TrimSpace(req.Text) == "" && len(req.Attachments) == 0 {
		writeErr(w, http.StatusBadRequest, "text or attachments required")
		return
	}
	if len(req.Attachments) > maxAttachments {
		writeErr(w, http.StatusBadRequest, fmt.Sprintf("at most %d attachments per message", maxAttachments))
		return
	}
	var atts []Attachment
	seen := map[string]bool{}
	for _, id := range req.Attachments {
		if seen[id] {
			continue
		}
		seen[id] = true
		att, err := s.LoadUpload(id)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "unknown attachment "+id)
			return
		}
		atts = append(atts, att)
	}
	if err := s.Send(req.Text, atts); err != nil {
		code := http.StatusInternalServerError
		if errors.Is(err, errTooBusy) {
			code = http.StatusTooManyRequests
		}
		writeErr(w, code, err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"ok": true})
}

func (a *API) interrupt(w http.ResponseWriter, r *http.Request, s *Session) {
	if err := s.Interrupt(); err != nil {
		writeErr(w, http.StatusConflict, err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (a *API) stop(w http.ResponseWriter, r *http.Request, s *Session) {
	s.Stop("stopped by user")
	writeJSON(w, http.StatusAccepted, map[string]any{"ok": true})
}

// syncSession folds conversation added outside the harness (the same session
// continued in a terminal or VS Code) into the history.
func (a *API) syncSession(w http.ResponseWriter, r *http.Request, s *Session) {
	n := s.Sync()
	v := s.View()
	writeJSON(w, 200, map[string]any{"synced": n, "session": v})
}

func (a *API) permission(w http.ResponseWriter, r *http.Request, s *Session) {
	var req struct {
		Allow        bool            `json:"allow"`
		Message      string          `json:"message"`
		Remember     bool            `json:"remember"`
		UpdatedInput json.RawMessage `json:"updatedInput"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	err := s.Resolve(r.PathValue("rid"), req.Allow, req.Message, req.Remember, req.UpdatedInput)
	switch {
	case err == nil:
		writeJSON(w, 200, map[string]any{"ok": true})
	case errors.Is(err, errNoPending):
		writeErr(w, http.StatusNotFound, err.Error())
	default:
		writeErr(w, http.StatusConflict, err.Error())
	}
}

func parseAfter(r *http.Request) int64 {
	v := r.Header.Get("Last-Event-ID")
	if q := r.URL.Query().Get("after"); q != "" {
		v = q
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n < 0 {
		return 0
	}
	return n
}

// history returns stored events as JSON (non-streaming alternative to events).
func (a *API) history(w http.ResponseWriter, r *http.Request, s *Session) {
	after := parseAfter(r)
	limit := 1000
	if n, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && n > 0 && n <= 5000 {
		limit = n
	}
	last, err := s.LastSeq()
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	var events []json.RawMessage
	stopped := errors.New("limit")
	err = s.Replay(after, last, func(seq int64, line []byte) error {
		events = append(events, json.RawMessage(append([]byte(nil), line...)))
		if len(events) >= limit {
			return stopped
		}
		return nil
	})
	if err != nil && err != stopped {
		writeErr(w, 500, err.Error())
		return
	}
	if events == nil {
		events = []json.RawMessage{}
	}
	writeJSON(w, 200, map[string]any{"events": events, "lastSeq": last, "more": len(events) >= limit})
}

// events streams the session as Server-Sent Events. Persisted events carry an
// id so EventSource resumes with Last-Event-ID; live-only deltas have none.
func (a *API) events(w http.ResponseWriter, r *http.Request, s *Session) {
	rc := http.NewResponseController(w)
	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache, no-transform")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(200)

	write := func(seq int64, line []byte) error {
		if seq > 0 {
			if _, err := fmt.Fprintf(w, "id: %d\n", seq); err != nil {
				return err
			}
		}
		if _, err := fmt.Fprintf(w, "data: %s\n\n", line); err != nil {
			return err
		}
		return nil
	}
	if _, err := fmt.Fprint(w, "retry: 2000\n\n"); err != nil {
		return
	}

	sb, cancel := s.Subscribe()
	defer cancel()

	sent := parseAfter(r)
	upTo, err := s.LastSeq()
	if err != nil {
		return
	}
	err = s.Replay(sent, upTo, func(seq int64, line []byte) error {
		sent = seq
		return write(seq, line)
	})
	if err != nil {
		return
	}
	if write(0, s.StatusLine()) != nil || rc.Flush() != nil {
		return
	}

	ping := time.NewTicker(15 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case it, ok := <-sb.ch:
			if !ok {
				return // dropped as slow consumer; client reconnects
			}
			if it.seq != 0 {
				if it.seq <= sent {
					continue
				}
				sent = it.seq
			}
			if write(it.seq, it.line) != nil || rc.Flush() != nil {
				return
			}
		case <-ping.C:
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil || rc.Flush() != nil {
				return
			}
		}
	}
}
