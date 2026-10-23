package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var modelRe = regexp.MustCompile(`^[A-Za-z0-9._\[\]-]{1,64}$`)

type CreateReq struct {
	Title          string `json:"title"`
	Cwd            string `json:"cwd"`
	Model          string `json:"model"`
	PermissionMode string `json:"permissionMode"`
	AppendPrompt   string `json:"appendSystemPrompt"`
}

var errNotFound = errors.New("not found")

// Manager owns every session and enforces per-user isolation: all lookups are
// keyed by (user, id), and sessions never leak across users.
type Manager struct {
	cfg   Config
	store *Store
	live  atomic.Int32

	mu       sync.RWMutex
	sessions map[string]*Session
}

func NewManager(cfg Config, store *Store) *Manager {
	m := &Manager{cfg: cfg, store: store, sessions: map[string]*Session{}}
	for _, meta := range store.LoadAll() {
		m.sessions[meta.ID] = newSession(m, meta)
	}
	return m
}

func (m *Manager) Get(user, id string) (*Session, error) {
	if !uuidRe.MatchString(id) {
		return nil, errNotFound
	}
	m.mu.RLock()
	s := m.sessions[id]
	m.mu.RUnlock()
	if s == nil || s.meta.User != user {
		return nil, errNotFound
	}
	return s, nil
}

func (m *Manager) List(user string) []SessionView {
	m.mu.RLock()
	var ss []*Session
	for _, s := range m.sessions {
		if s.meta.User == user {
			ss = append(ss, s)
		}
	}
	m.mu.RUnlock()
	out := make([]SessionView, 0, len(ss))
	for _, s := range ss {
		out = append(out, s.View())
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.After(out[j].UpdatedAt) })
	return out
}

func within(root, p string) bool {
	rel, err := filepath.Rel(root, p)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

// Roots returns the real paths a user may use as working directories: their
// private workspace first, then the configured extra roots that exist.
func (m *Manager) Roots(user string) ([]string, error) {
	ws, err := m.store.Workspace(user)
	if err != nil {
		return nil, err
	}
	wsReal, err := filepath.EvalSymlinks(ws)
	if err != nil {
		return nil, err
	}
	roots := []string{wsReal}
	for _, r := range m.cfg.ExtraRoots {
		if rr, err := filepath.EvalSymlinks(r); err == nil {
			roots = append(roots, rr)
		}
	}
	return roots, nil
}

// ResolveExisting returns the real path of an existing directory that lies
// inside one of the user's roots. Symlinks cannot be used to escape them.
func (m *Manager) ResolveExisting(user, p string) (string, error) {
	real, err := filepath.EvalSymlinks(p)
	if err != nil {
		return "", errors.New("directory does not exist")
	}
	if st, err := os.Stat(real); err != nil || !st.IsDir() {
		return "", errors.New("not a directory")
	}
	roots, err := m.Roots(user)
	if err != nil {
		return "", err
	}
	for _, r := range roots {
		if within(r, real) {
			return real, nil
		}
	}
	return "", errors.New("directory is outside the allowed roots")
}

// ResolveCwd maps a requested directory to a real path inside the user's
// workspace or one of the configured extra roots. Relative paths are taken
// relative to the workspace, where missing directories are created.
func (m *Manager) ResolveCwd(user, cwd string) (string, error) {
	roots, err := m.Roots(user)
	if err != nil {
		return "", err
	}
	wsReal := roots[0]
	if cwd == "" {
		return wsReal, nil
	}
	if !filepath.IsAbs(cwd) {
		cwd = filepath.Join(wsReal, cwd)
	}
	cwd = filepath.Clean(cwd)
	if within(wsReal, cwd) { // create missing directories only inside the workspace
		if err := os.MkdirAll(cwd, 0o700); err != nil {
			return "", err
		}
	}
	return m.ResolveExisting(user, cwd)
}

func (m *Manager) Create(user string, req CreateReq) (*Session, error) {
	if len([]rune(req.Title)) > 100 {
		return nil, errors.New("title too long")
	}
	if len(req.AppendPrompt) > 8192 {
		return nil, errors.New("appendSystemPrompt too long")
	}
	if req.Model != "" && !modelRe.MatchString(req.Model) {
		return nil, errors.New("invalid model")
	}
	mode := req.PermissionMode
	if mode == "" {
		mode = "manual"
	}
	if !permissionModes[mode] {
		return nil, errors.New("invalid permission mode")
	}
	if mode == "bypassPermissions" && !m.cfg.AllowBypass {
		return nil, errors.New("bypassPermissions is disabled on this server")
	}
	cwd, err := m.ResolveCwd(user, req.Cwd)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	meta := SessionMeta{
		ID: newUUID(), User: user, Title: strings.TrimSpace(req.Title), Cwd: cwd,
		Model: req.Model, PermissionMode: mode, AppendPrompt: req.AppendPrompt,
		CreatedAt: now, UpdatedAt: now,
	}
	if err := m.store.SaveMeta(meta); err != nil {
		return nil, err
	}
	s := newSession(m, meta)
	m.mu.Lock()
	m.sessions[meta.ID] = s
	m.mu.Unlock()
	return s, nil
}

func (m *Manager) Delete(user, id string) error {
	s, err := m.Get(user, id)
	if err != nil {
		return err
	}
	s.Stop("deleted")
	// wait briefly for the process to go away before removing its files
	for i := 0; i < 60; i++ {
		s.mu.Lock()
		gone := s.proc == nil
		s.mu.Unlock()
		if gone {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	m.mu.Lock()
	delete(m.sessions, id)
	m.mu.Unlock()
	s.logMu.Lock()
	if s.log != nil {
		s.log.Close()
	}
	s.logMu.Unlock()
	// also drop claude's own transcript for this (uuid-named) conversation
	if home, err := os.UserHomeDir(); err == nil {
		if ms, _ := filepath.Glob(filepath.Join(home, ".claude", "projects", "*", id+".jsonl")); len(ms) > 0 {
			for _, p := range ms {
				os.Remove(p)
			}
		}
	}
	return m.store.Remove(user, id)
}

// ensureCapacity frees a process slot if the limit is reached by stopping the
// longest-idle session that is not mid-turn. No locks may be held by the caller.
func (m *Manager) ensureCapacity(self *Session) {
	if int(m.live.Load()) < m.cfg.MaxProcs {
		return
	}
	self.mu.Lock()
	running := self.proc != nil
	self.mu.Unlock()
	if running {
		return
	}
	m.mu.RLock()
	all := make([]*Session, 0, len(m.sessions))
	for _, s := range m.sessions {
		all = append(all, s)
	}
	m.mu.RUnlock()
	var victim *Session
	var oldest time.Time
	for _, s := range all {
		if s == self || !s.mu.TryLock() {
			continue
		}
		if s.proc != nil && s.inflight == 0 && len(s.pending) == 0 && (victim == nil || s.lastActive.Before(oldest)) {
			victim, oldest = s, s.lastActive
		}
		s.mu.Unlock()
	}
	if victim != nil {
		victim.Stop("evicted (process limit)")
		for i := 0; i < 50 && int(m.live.Load()) >= m.cfg.MaxProcs; i++ {
			time.Sleep(100 * time.Millisecond)
		}
	}
}

// ReapLoop stops processes that have been idle longer than the configured timeout.
func (m *Manager) ReapLoop(ctx context.Context) {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			m.mu.RLock()
			all := make([]*Session, 0, len(m.sessions))
			for _, s := range m.sessions {
				all = append(all, s)
			}
			m.mu.RUnlock()
			for _, s := range all {
				s.mu.Lock()
				idle := s.proc != nil && s.inflight == 0 && len(s.pending) == 0 &&
					time.Since(s.lastActive) > m.cfg.IdleTimeout
				s.mu.Unlock()
				if idle {
					s.Stop("idle timeout")
				}
			}
		}
	}
}

// StopAll terminates every child process (used on shutdown).
func (m *Manager) StopAll() {
	m.mu.RLock()
	all := make([]*Session, 0, len(m.sessions))
	for _, s := range m.sessions {
		all = append(all, s)
	}
	m.mu.RUnlock()
	for _, s := range all {
		s.Stop("server shutdown")
	}
	for i := 0; i < 80 && m.live.Load() > 0; i++ {
		time.Sleep(100 * time.Millisecond)
	}
}
