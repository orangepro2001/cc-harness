package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

var permissionModes = map[string]bool{
	"manual": true, "acceptEdits": true, "auto": true,
	"plan": true, "dontAsk": true, "bypassPermissions": true,
}

var (
	errNotRunning = errors.New("session process is not running")
	errNoPending  = errors.New("no such pending permission request")
	errTooBusy    = errors.New("too many running sessions; stop one first")
)

// PendingPerm is a tool-permission question the CLI is blocked on.
type PendingPerm struct {
	RequestID   string          `json:"requestId"`
	ToolName    string          `json:"toolName"`
	ToolUseID   string          `json:"toolUseId,omitempty"`
	Input       json.RawMessage `json:"input"`
	Suggestions json.RawMessage `json:"suggestions,omitempty"`
}

type SessionView struct {
	SessionMeta
	Status  string         `json:"status"` // stopped | idle | busy | awaiting_permission
	Pending []*PendingPerm `json:"pending"`
	LastSeq int64          `json:"lastSeq"`
}

type item struct {
	seq  int64
	line []byte
}

type sub struct{ ch chan item }

// proc is one running `claude` child process.
type proc struct {
	cmd    *exec.Cmd
	out    chan []byte
	done   chan struct{}
	stderr *tailBuf
}

func (p *proc) enqueue(b []byte) bool {
	select {
	case p.out <- b:
		return true
	default:
		return false
	}
}

// kill asks the whole process group to exit and escalates after 5s.
func (p *proc) kill() {
	pid := p.cmd.Process.Pid
	_ = syscall.Kill(-pid, syscall.SIGTERM)
	go func() {
		select {
		case <-p.done:
		case <-time.After(5 * time.Second):
			_ = syscall.Kill(-pid, syscall.SIGKILL)
		}
	}()
}

type Session struct {
	m *Manager

	mu         sync.Mutex
	meta       SessionMeta
	proc       *proc
	pending    map[string]*PendingPerm
	waiters    map[string]chan error
	inflight   int
	lastStatus string
	lastActive time.Time
	stopReason string
	subs       map[*sub]struct{}

	logMu sync.Mutex
	log   *EventLog

	commands []string // slash commands reported by the CLI's init message (guarded by mu)
}

// Commands lists the slash commands available in this session, falling back to
// the last list any session reported (the CLI only reveals it once started).
func (s *Session) Commands() []string {
	s.mu.Lock()
	c := s.commands
	s.mu.Unlock()
	if len(c) == 0 {
		c = s.m.knownCommands()
	}
	if c == nil {
		c = []string{}
	}
	return c
}

func newSession(m *Manager, meta SessionMeta) *Session {
	return &Session{
		m: m, meta: meta,
		pending: map[string]*PendingPerm{},
		waiters: map[string]chan error{},
		subs:    map[*sub]struct{}{},
		// A freshly loaded session is stopped; treat it as just used so the
		// reaper has nothing to do until it runs.
		lastActive: time.Now(),
	}
}

func (s *Session) eventLog() (*EventLog, error) {
	s.logMu.Lock()
	defer s.logMu.Unlock()
	if s.log == nil {
		l, err := OpenEventLog(filepath.Join(s.m.store.sessionDir(s.meta.User, s.meta.ID), "events.jsonl"))
		if err != nil {
			return nil, err
		}
		s.log = l
	}
	return s.log, nil
}

func harnessEvent(kind string, fields map[string]any) []byte {
	if fields == nil {
		fields = map[string]any{}
	}
	fields["type"] = "harness"
	fields["kind"] = kind
	b, _ := json.Marshal(fields)
	return b
}

// emitLocked records (optionally) and fans out one event. Caller holds s.mu.
func (s *Session) emitLocked(raw []byte, persist bool) {
	var seq int64
	var line []byte
	if persist {
		l, err := s.eventLog()
		if err == nil {
			seq, line, err = l.Append(raw)
		}
		if err != nil {
			log.Printf("session %s: event log: %v", s.meta.ID, err)
			return
		}
	} else {
		line = wrapEvent(0, raw)
	}
	for sb := range s.subs {
		select {
		case sb.ch <- item{seq, line}:
		default: // slow consumer: drop it, it will resume via Last-Event-ID
			close(sb.ch)
			delete(s.subs, sb)
		}
	}
}

func (s *Session) statusLocked() string {
	switch {
	case s.proc == nil:
		return "stopped"
	case len(s.pending) > 0:
		return "awaiting_permission"
	case s.inflight > 0:
		return "busy"
	}
	return "idle"
}

func (s *Session) statusEventLocked() []byte {
	return harnessEvent("status", map[string]any{"status": s.statusLocked()})
}

func (s *Session) statusChangedLocked() {
	if st := s.statusLocked(); st != s.lastStatus {
		s.lastStatus = st
		s.emitLocked(s.statusEventLocked(), false)
	}
}

func (s *Session) saveMetaLocked() {
	s.meta.UpdatedAt = time.Now()
	if err := s.m.store.SaveMeta(s.meta); err != nil {
		log.Printf("session %s: save meta: %v", s.meta.ID, err)
	}
}

func (s *Session) View() SessionView {
	s.mu.Lock()
	v := SessionView{SessionMeta: s.meta, Status: s.statusLocked(), Pending: []*PendingPerm{}}
	for _, p := range s.pending {
		v.Pending = append(v.Pending, p)
	}
	s.mu.Unlock()
	if l, err := s.eventLog(); err == nil {
		v.LastSeq = l.Seq()
	}
	return v
}

// ---- subscription / replay ------------------------------------------------

func (s *Session) Subscribe() (*sub, func()) {
	sb := &sub{ch: make(chan item, 1024)}
	s.mu.Lock()
	s.subs[sb] = struct{}{}
	s.mu.Unlock()
	return sb, func() {
		s.mu.Lock()
		if _, ok := s.subs[sb]; ok {
			delete(s.subs, sb)
			close(sb.ch)
		}
		s.mu.Unlock()
	}
}

func (s *Session) LastSeq() (int64, error) {
	l, err := s.eventLog()
	if err != nil {
		return 0, err
	}
	return l.Seq(), nil
}

func (s *Session) Replay(after, upTo int64, fn func(seq int64, line []byte) error) error {
	l, err := s.eventLog()
	if err != nil {
		return err
	}
	return l.Read(after, upTo, fn)
}

func (s *Session) StatusLine() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return wrapEvent(0, s.statusEventLocked())
}

// ---- process lifecycle ----------------------------------------------------

func cleanEnv() []string {
	var env []string
	for _, kv := range os.Environ() {
		// Never leak the harness's own secrets into the agent's shell.
		if strings.HasPrefix(kv, "HARNESS_") {
			continue
		}
		env = append(env, kv)
	}
	return env
}

func (s *Session) buildArgs() []string {
	args := []string{
		"-p", "--input-format", "stream-json", "--output-format", "stream-json",
		"--verbose", "--include-partial-messages",
		"--permission-prompt-tool", "stdio",
		"--permission-mode", s.meta.PermissionMode,
		"--add-dir", s.uploadsDir(), // lets the agent read attachments by path
	}
	if s.meta.Model != "" {
		args = append(args, "--model", s.meta.Model)
	}
	if s.meta.Effort != "" {
		args = append(args, "--effort", s.meta.Effort)
	}
	if s.meta.AppendPrompt != "" {
		args = append(args, "--append-system-prompt", s.meta.AppendPrompt)
	}
	if s.meta.Started {
		args = append(args, "--resume", s.meta.ID)
	} else {
		args = append(args, "--session-id", s.meta.ID)
	}
	return args
}

// transcriptPath is where the claude CLI keeps this session's conversation
// ("" if it has none yet). The CLI, the VS Code extension and this server all
// read and append to the same file, which is what makes a session portable.
func (s *Session) transcriptPath() string {
	files, _ := filepath.Glob(filepath.Join(s.m.cfg.claudeHome(), "projects", "*", s.meta.ID+".jsonl"))
	if len(files) == 0 {
		return ""
	}
	return files[0]
}

// syncLocked pulls conversation that was appended to the transcript behind
// our back (the session was continued in a terminal or the VS Code extension)
// into the event log, and returns how many events it added. Nothing is done
// while our own claude process is running: it is the writer then.
func (s *Session) syncLocked() int {
	if s.proc != nil || !s.meta.Started {
		return 0
	}
	path := s.transcriptPath()
	if path == "" {
		return 0
	}
	st, err := os.Stat(path)
	if err != nil {
		return 0
	}
	if s.meta.SyncedSize == 0 || st.Size() < s.meta.SyncedSize {
		// Sessions from before sync existed (or a rewritten transcript): trust the log as it is.
		s.meta.SyncedSize = st.Size()
		s.saveMetaLocked()
		return 0
	}
	if st.Size() == s.meta.SyncedSize {
		return 0
	}
	events, prompts, end, err := convertTranscript(path, s.meta.SyncedSize)
	if err != nil {
		log.Printf("session %s: sync transcript: %v", s.meta.ID, err)
		return 0
	}
	if len(events) > 0 {
		s.emitLocked(harnessEvent("synced", map[string]any{"messages": len(events), "source": "local"}), true)
		for _, e := range events {
			s.emitLocked(e, true)
		}
		s.meta.Turns += prompts
	}
	s.meta.SyncedSize = end
	s.saveMetaLocked()
	return len(events)
}

// Sync is syncLocked for callers that do not hold the lock.
func (s *Session) Sync() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := s.syncLocked()
	if n > 0 {
		s.statusChangedLocked()
	}
	return n
}

func (s *Session) startLocked() error {
	if s.proc != nil {
		return nil
	}
	s.syncLocked()
	resume := s.meta.Started
	if err := os.MkdirAll(s.uploadsDir(), 0o700); err != nil {
		return err
	}
	cmd := exec.Command(s.m.cfg.ClaudeBin, s.buildArgs()...)
	cmd.Dir = s.meta.Cwd
	cmd.Env = cleanEnv()
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.WaitDelay = 5 * time.Second

	p := &proc{cmd: cmd, out: make(chan []byte, 64), done: make(chan struct{}), stderr: &tailBuf{max: 4096}}
	cmd.Stdout = &lineWriter{max: 64 << 20, fn: func(line []byte) { s.handleLine(p, line) }}
	cmd.Stderr = p.stderr
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start claude: %w", err)
	}
	s.proc = p
	s.m.live.Add(1)
	go s.writeLoop(p, stdin)
	go s.waitLoop(p)
	s.emitLocked(harnessEvent("process_started", map[string]any{"pid": cmd.Process.Pid, "resume": resume}), true)
	return nil
}

func (s *Session) writeLoop(p *proc, w interface {
	Write([]byte) (int, error)
}) {
	for {
		select {
		case b := <-p.out:
			if _, err := w.Write(b); err != nil {
				p.kill()
				return
			}
		case <-p.done:
			return
		}
	}
}

func (s *Session) waitLoop(p *proc) {
	_ = p.cmd.Wait()
	close(p.done)
	// Reap stragglers (background jobs) left in the process group.
	_ = syscall.Kill(-p.cmd.Process.Pid, syscall.SIGKILL)
	s.m.live.Add(-1)

	code := -1
	if p.cmd.ProcessState != nil {
		code = p.cmd.ProcessState.ExitCode()
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if s.proc == p {
		s.proc = nil
	}
	reason := s.stopReason
	s.stopReason = ""
	if reason == "" {
		reason = "exited"
	}
	for rid := range s.pending {
		s.emitLocked(harnessEvent("permission_resolved", map[string]any{"requestId": rid, "cancelled": true}), true)
		delete(s.pending, rid)
	}
	for id, ch := range s.waiters {
		ch <- errNotRunning
		delete(s.waiters, id)
	}
	if s.inflight > 0 {
		msg := fmt.Sprintf("claude exited unexpectedly (code %d)", code)
		if t := strings.TrimSpace(p.stderr.String()); t != "" {
			msg += ": " + t
		}
		s.emitLocked(harnessEvent("error", map[string]any{"message": msg}), true)
		s.inflight = 0
	}
	s.emitLocked(harnessEvent("process_exit", map[string]any{"code": code, "reason": reason}), true)
	// Everything now in the transcript was written by this process and is
	// already in the event log; only later growth is foreign.
	if path := s.transcriptPath(); path != "" {
		if st, err := os.Stat(path); err == nil {
			s.meta.SyncedSize = st.Size()
		}
	}
	s.saveMetaLocked()
	s.statusChangedLocked()
}

// handleLine processes one stdout line from the CLI.
func (s *Session) handleLine(p *proc, line []byte) {
	line = bytes.TrimSpace(line)
	if len(line) == 0 {
		return
	}
	var h struct {
		Type      string   `json:"type"`
		Subtype   string   `json:"subtype"`
		RequestID string   `json:"request_id"`
		Parent    *string  `json:"parent_tool_use_id"`
		Commands  []string `json:"slash_commands"`
		Request   struct {
			Subtype     string          `json:"subtype"`
			ToolName    string          `json:"tool_name"`
			ToolUseID   string          `json:"tool_use_id"`
			Input       json.RawMessage `json:"input"`
			Suggestions json.RawMessage `json:"permission_suggestions"`
		} `json:"request"`
		Response struct {
			Subtype   string `json:"subtype"`
			RequestID string `json:"request_id"`
			Error     string `json:"error"`
		} `json:"response"`
	}
	if err := json.Unmarshal(line, &h); err != nil {
		log.Printf("session %s: non-JSON stdout line ignored (%d bytes)", s.meta.ID, len(line))
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.lastActive = time.Now()

	switch h.Type {
	case "stream_event":
		if h.Parent == nil || *h.Parent == "" { // main-agent deltas only
			s.emitLocked(line, false)
		}
		return
	case "rate_limit_event", "keep_alive":
		return
	case "system":
		switch h.Subtype {
		case "hook_started", "hook_response", "hook_progress", "status":
			return
		case "init":
			if len(h.Commands) > 0 {
				s.commands = h.Commands
				s.m.rememberCommands(h.Commands)
			}
		}
	case "control_request":
		s.onControlRequestLocked(p, line, h.RequestID, h.Request.Subtype, &PendingPerm{
			RequestID: h.RequestID, ToolName: h.Request.ToolName, ToolUseID: h.Request.ToolUseID,
			Input: h.Request.Input, Suggestions: h.Request.Suggestions,
		})
		return
	case "control_cancel_request":
		if _, ok := s.pending[h.RequestID]; ok {
			delete(s.pending, h.RequestID)
			s.emitLocked(harnessEvent("permission_resolved", map[string]any{"requestId": h.RequestID, "cancelled": true}), true)
			s.statusChangedLocked()
		}
		return
	case "control_response":
		if ch, ok := s.waiters[h.Response.RequestID]; ok {
			delete(s.waiters, h.Response.RequestID)
			if h.Response.Subtype == "error" {
				ch <- errors.New(h.Response.Error)
			} else {
				ch <- nil
			}
		}
		return
	case "assistant":
		if !s.meta.Started {
			s.meta.Started = true
			s.saveMetaLocked()
		}
	case "result":
		if s.inflight > 0 {
			s.inflight--
		}
		s.meta.Started = true
		s.meta.Turns++
		s.saveMetaLocked()
	}
	s.emitLocked(line, true)
	if h.Type == "result" || h.Type == "assistant" {
		s.statusChangedLocked()
	}
}

func (s *Session) onControlRequestLocked(p *proc, line []byte, rid, sub string, pp *PendingPerm) {
	if sub == "can_use_tool" {
		if len(pp.Input) == 0 {
			pp.Input = json.RawMessage(`{}`)
		}
		s.pending[rid] = pp
		s.emitLocked(line, true)
		s.statusChangedLocked()
		return
	}
	b, _ := json.Marshal(map[string]any{"type": "control_response", "response": map[string]any{
		"subtype": "error", "request_id": rid, "error": "unsupported control request: " + sub,
	}})
	p.enqueue(append(b, '\n'))
}

// ---- operations -----------------------------------------------------------

func (s *Session) Send(text string, atts []Attachment) error {
	var content any = text
	if len(atts) > 0 {
		note, blocks, err := s.attachmentBlocks(atts)
		if err != nil {
			return err
		}
		parts := []map[string]any{{"type": "text", "text": strings.TrimSpace(text + "\n\n" + note)}}
		content = append(parts, blocks...)
	}
	s.m.ensureCapacity(s)
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.proc == nil && int(s.m.live.Load()) >= s.m.cfg.MaxProcs {
		return errTooBusy
	}
	if err := s.startLocked(); err != nil {
		return err
	}
	b, _ := json.Marshal(map[string]any{
		"type": "user", "message": map[string]any{"role": "user", "content": content},
	})
	if !s.proc.enqueue(append(b, '\n')) {
		return errors.New("process input queue is full")
	}
	s.inflight++
	s.lastActive = time.Now()
	if s.meta.Title == "" {
		title := text
		if strings.TrimSpace(title) == "" && len(atts) > 0 {
			title = atts[0].Name
		}
		t := []rune(strings.Join(strings.Fields(title), " "))
		if len(t) > 40 {
			t = append(t[:40], '…')
		}
		s.meta.Title = string(t)
	}
	ev := map[string]any{"text": text}
	if len(atts) > 0 {
		ev["attachments"] = atts
	}
	s.emitLocked(harnessEvent("user_message", ev), true)
	s.saveMetaLocked()
	s.statusChangedLocked()
	return nil
}

// control sends a control_request and waits for the CLI's answer.
func (s *Session) control(req map[string]any) error {
	id := newUUID()
	ch := make(chan error, 1)
	s.mu.Lock()
	if s.proc == nil {
		s.mu.Unlock()
		return errNotRunning
	}
	s.waiters[id] = ch
	b, _ := json.Marshal(map[string]any{"type": "control_request", "request_id": id, "request": req})
	ok := s.proc.enqueue(append(b, '\n'))
	if !ok {
		delete(s.waiters, id)
	}
	s.mu.Unlock()
	if !ok {
		return errors.New("process input queue is full")
	}
	select {
	case err := <-ch:
		return err
	case <-time.After(10 * time.Second):
		s.mu.Lock()
		delete(s.waiters, id)
		s.mu.Unlock()
		return errors.New("timed out waiting for claude")
	}
}

func (s *Session) Interrupt() error {
	return s.control(map[string]any{"subtype": "interrupt"})
}

func (s *Session) Stop(reason string) {
	s.mu.Lock()
	p := s.proc
	if p != nil {
		s.stopReason = reason
	}
	s.mu.Unlock()
	if p != nil {
		p.kill()
	}
}

func (s *Session) SetTitle(t string) {
	s.mu.Lock()
	s.meta.Title = t
	s.saveMetaLocked()
	s.mu.Unlock()
}

func (s *Session) SetPermissionMode(mode string) error {
	if !permissionModes[mode] {
		return errors.New("invalid permission mode")
	}
	if mode == "bypassPermissions" && !s.m.cfg.AllowBypass {
		return errors.New("bypassPermissions is disabled on this server")
	}
	s.mu.Lock()
	running := s.proc != nil
	s.mu.Unlock()
	if running {
		if err := s.control(map[string]any{"subtype": "set_permission_mode", "mode": mode}); err != nil && !errors.Is(err, errNotRunning) {
			return err
		}
	}
	s.mu.Lock()
	s.meta.PermissionMode = mode
	s.saveMetaLocked()
	s.emitLocked(harnessEvent("mode_changed", map[string]any{"permissionMode": mode}), true)
	s.mu.Unlock()
	return nil
}

func (s *Session) SetModel(model string) error {
	if model != "" && !modelRe.MatchString(model) {
		return errors.New("invalid model")
	}
	s.mu.Lock()
	running := s.proc != nil
	s.mu.Unlock()
	if running && model != "" {
		if err := s.control(map[string]any{"subtype": "set_model", "model": model}); err != nil && !errors.Is(err, errNotRunning) {
			return err
		}
	}
	s.mu.Lock()
	s.meta.Model = model
	s.saveMetaLocked()
	s.emitLocked(harnessEvent("model_changed", map[string]any{"model": model}), true)
	s.mu.Unlock()
	return nil
}

// SetEffort changes the effort level. The CLI only takes it at startup, so an
// idle process is stopped and the next message resumes the session with the
// new level; a busy one keeps its current level until it next restarts.
func (s *Session) SetEffort(level string) error {
	if level != "" && !effortLevels[level] {
		return errors.New("invalid effort")
	}
	s.mu.Lock()
	s.meta.Effort = level
	s.saveMetaLocked()
	s.emitLocked(harnessEvent("effort_changed", map[string]any{"effort": level}), true)
	idle := s.proc != nil && s.inflight == 0 && len(s.pending) == 0
	s.mu.Unlock()
	if idle {
		s.Stop("effort changed")
	}
	return nil
}

// Resolve answers a pending tool-permission request.
func (s *Session) Resolve(rid string, allow bool, message string, remember bool, updated json.RawMessage) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	pp := s.pending[rid]
	if pp == nil {
		return errNoPending
	}
	if s.proc == nil {
		return errNotRunning
	}
	var resp map[string]any
	if allow {
		in := pp.Input
		if len(updated) > 0 && string(updated) != "null" {
			in = updated
		}
		resp = map[string]any{"behavior": "allow", "updatedInput": in}
		if remember && len(pp.Suggestions) > 0 {
			resp["updatedPermissions"] = pp.Suggestions
		}
	} else {
		if message == "" {
			message = "The user denied this action."
		}
		resp = map[string]any{"behavior": "deny", "message": message}
	}
	b, err := json.Marshal(map[string]any{"type": "control_response", "response": map[string]any{
		"subtype": "success", "request_id": rid, "response": resp,
	}})
	if err != nil {
		return err
	}
	if !s.proc.enqueue(append(b, '\n')) {
		return errors.New("process input queue is full")
	}
	delete(s.pending, rid)
	s.emitLocked(harnessEvent("permission_resolved", map[string]any{
		"requestId": rid, "allow": allow, "remember": remember && allow,
	}), true)
	s.statusChangedLocked()
	return nil
}

// ---- small helpers --------------------------------------------------------

// lineWriter splits a byte stream into newline-delimited lines.
type lineWriter struct {
	buf     []byte
	max     int
	discard bool
	fn      func([]byte)
}

func (w *lineWriter) Write(p []byte) (int, error) {
	n := len(p)
	for len(p) > 0 {
		i := bytes.IndexByte(p, '\n')
		if i < 0 {
			if !w.discard {
				if len(w.buf)+len(p) > w.max {
					w.discard, w.buf = true, nil
					log.Printf("dropping oversized output line")
				} else {
					w.buf = append(w.buf, p...)
				}
			}
			break
		}
		if !w.discard {
			if len(w.buf)+i <= w.max {
				w.fn(append(w.buf, p[:i]...))
			}
		}
		w.buf, w.discard = nil, false
		p = p[i+1:]
	}
	return n, nil
}

// tailBuf keeps the last max bytes written to it.
type tailBuf struct {
	mu  sync.Mutex
	max int
	b   []byte
}

func (t *tailBuf) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.b = append(t.b, p...)
	if len(t.b) > t.max {
		t.b = t.b[len(t.b)-t.max:]
	}
	return len(p), nil
}

func (t *tailBuf) String() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return string(t.b)
}
