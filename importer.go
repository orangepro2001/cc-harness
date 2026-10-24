package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Local sessions are the transcripts the claude CLI keeps under
// <claude home>/projects/<encoded cwd>/<session id>.jsonl. Importing one makes
// it a harness session with the same id, so `claude --resume <id>` continues
// the very same conversation (and keeps appending to the same file).

const (
	importHeadLines = 400       // lines scanned for cwd and the first prompt
	importTailBytes = 512 << 10 // tail scanned for the latest AI title
	importMaxEvents = 4000      // newest converted events kept for display
	importMaxLine   = 32 << 20
	activeWindow    = 45 * time.Second // a transcript touched this recently may still be in use
	localListLimit  = 300
	titleMaxRunes   = 40
)

// LocalSession describes one importable transcript.
type LocalSession struct {
	ID        string    `json:"id"`
	Title     string    `json:"title"`
	Cwd       string    `json:"cwd"`
	UpdatedAt time.Time `json:"updatedAt"`
	Size      int64     `json:"size"`
	Active    bool      `json:"active"` // modified moments ago: probably open in another Claude Code
	path      string
}

// ClaudeHome is where the CLI keeps its data (HARNESS_CLAUDE_HOME, default ~/.claude).
func (c Config) claudeHome() string {
	if c.ClaudeHome != "" {
		return c.ClaudeHome
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".claude")
}

type transcriptLine struct {
	Type        string `json:"type"`
	Cwd         string `json:"cwd"`
	IsSidechain bool   `json:"isSidechain"`
	IsMeta      bool   `json:"isMeta"`
	AITitle     string `json:"aiTitle"`
	CustomTitle string `json:"customTitle"`
	Timestamp   string `json:"timestamp"`
	Message     struct {
		Role    string          `json:"role"`
		Content json.RawMessage `json:"content"`
	} `json:"message"`
}

var (
	commandNameRe = regexp.MustCompile(`(?s)<command-name>(.*?)</command-name>`)
	commandArgsRe = regexp.MustCompile(`(?s)<command-args>(.*?)</command-args>`)
)

// promptText extracts what the user typed from a user line ("" when it is
// not a human prompt: tool results, hook output, command echoes, caveats).
func promptText(l transcriptLine) string {
	if l.Type != "user" || l.IsSidechain || l.IsMeta {
		return ""
	}
	var s string
	if json.Unmarshal(l.Message.Content, &s) == nil {
		return cleanPrompt(s)
	}
	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if json.Unmarshal(l.Message.Content, &blocks) != nil {
		return ""
	}
	var parts []string
	for _, b := range blocks {
		if b.Type == "text" {
			if t := cleanPrompt(b.Text); t != "" {
				parts = append(parts, t)
			}
		}
	}
	return strings.Join(parts, "\n")
}

func cleanPrompt(s string) string {
	s = strings.TrimSpace(s)
	switch {
	case s == "", strings.HasPrefix(s, "<system-reminder>"), strings.HasPrefix(s, "<local-command-"),
		strings.HasPrefix(s, "[Request interrupted"):
		return ""
	}
	if m := commandNameRe.FindStringSubmatch(s); m != nil {
		cmd := strings.TrimSpace(m[1])
		if a := commandArgsRe.FindStringSubmatch(s); a != nil && strings.TrimSpace(a[1]) != "" {
			cmd += " " + strings.TrimSpace(a[1])
		}
		return cmd
	}
	return s
}

func truncateTitle(s string) string {
	t := []rune(strings.Join(strings.Fields(s), " "))
	if len(t) > titleMaxRunes {
		t = append(t[:titleMaxRunes], '…')
	}
	return string(t)
}

func newLineScanner(r *bufio.Reader) *bufio.Scanner {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64<<10), importMaxLine)
	return sc
}

// scanTranscript reads just enough of a transcript to describe it.
func scanTranscript(path string) (LocalSession, bool) {
	f, err := os.Open(path)
	if err != nil {
		return LocalSession{}, false
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return LocalSession{}, false
	}
	ls := LocalSession{
		ID: strings.TrimSuffix(filepath.Base(path), ".jsonl"), UpdatedAt: st.ModTime(), Size: st.Size(), path: path,
	}
	var first, custom, ai string
	sc := newLineScanner(bufio.NewReader(f))
	for i := 0; i < importHeadLines && sc.Scan(); i++ {
		var l transcriptLine
		if json.Unmarshal(sc.Bytes(), &l) != nil {
			continue
		}
		if ls.Cwd == "" && l.Cwd != "" {
			ls.Cwd = l.Cwd
		}
		if first == "" {
			first = promptText(l)
		}
		if ls.Cwd != "" && first != "" {
			break
		}
	}
	// The newest AI/custom title is near the end of the file.
	off := st.Size() - importTailBytes
	if off < 0 {
		off = 0
	}
	if _, err := f.Seek(off, 0); err == nil {
		tsc := newLineScanner(bufio.NewReader(f))
		if off > 0 {
			tsc.Scan() // drop the partial first line
		}
		for tsc.Scan() {
			b := tsc.Bytes()
			if !bytes.Contains(b, []byte("itle")) {
				continue
			}
			var l transcriptLine
			if json.Unmarshal(b, &l) != nil {
				continue
			}
			if l.CustomTitle != "" {
				custom = l.CustomTitle
			}
			if l.AITitle != "" {
				ai = l.AITitle
			}
		}
	}
	ls.Title = custom
	if ls.Title == "" {
		ls.Title = ai
	}
	if ls.Title == "" {
		ls.Title = first
	}
	ls.Title = truncateTitle(ls.Title)
	ls.Active = time.Since(st.ModTime()) < activeWindow
	return ls, ls.Cwd != "" && first != ""
}

// LocalSessions lists importable transcripts whose working directory the user
// may use and that are not already managed by this server, newest first.
func (m *Manager) LocalSessions(user string) ([]LocalSession, error) {
	files, _ := filepath.Glob(filepath.Join(m.cfg.claudeHome(), "projects", "*", "*.jsonl"))
	m.mu.RLock()
	known := make(map[string]bool, len(m.sessions))
	for id := range m.sessions {
		known[id] = true
	}
	m.mu.RUnlock()

	var out []LocalSession
	for _, p := range files {
		id := strings.TrimSuffix(filepath.Base(p), ".jsonl")
		if !uuidRe.MatchString(id) || known[id] {
			continue
		}
		ls, ok := scanTranscript(p)
		if !ok {
			continue
		}
		real, err := m.ResolveExisting(user, ls.Cwd)
		if err != nil {
			continue
		}
		ls.Cwd = real
		out = append(out, ls)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.After(out[j].UpdatedAt) })
	if len(out) > localListLimit {
		out = out[:localListLimit]
	}
	return out, nil
}

var (
	errAlreadyManaged = errors.New("session is already managed by this server")
	errBusyElsewhere  = errors.New("session was modified moments ago and may be open in another Claude Code; close it there or pass force")
)

// convertTranscript turns the part of a transcript from byte offset `from`
// into the event stream the clients render. end is the offset just past the
// last complete line read, so a line another process is still writing is left
// for the next call.
func convertTranscript(path string, from int64) (events [][]byte, prompts int, end int64, err error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, 0, from, err
	}
	defer f.Close()
	if _, err := f.Seek(from, 0); err != nil {
		return nil, 0, from, err
	}
	end = from
	rd := bufio.NewReaderSize(f, 1<<20)
	for {
		line, rerr := rd.ReadBytes('\n')
		if rerr != nil && rerr != io.EOF {
			return nil, 0, from, rerr
		}
		if len(line) == 0 || line[len(line)-1] != '\n' {
			break // EOF, possibly in the middle of a line that is still being written
		}
		end += int64(len(line))
		if len(line) > importMaxLine {
			continue
		}
		var l transcriptLine
		if json.Unmarshal(line, &l) != nil || l.IsSidechain {
			continue
		}
		switch l.Type {
		case "user":
			if l.IsMeta {
				continue
			}
			if t := promptText(l); t != "" {
				prompts++
				events = append(events, harnessEvent("user_message", map[string]any{"text": t}))
			}
			if blocks := toolResultBlocks(l.Message.Content); len(blocks) > 0 {
				b, _ := json.Marshal(map[string]any{"type": "user", "message": map[string]any{"role": "user", "content": blocks}})
				events = append(events, b)
			}
		case "assistant":
			blocks := assistantBlocks(l.Message.Content)
			if len(blocks) == 0 {
				continue
			}
			b, _ := json.Marshal(map[string]any{"type": "assistant", "message": map[string]any{"role": "assistant", "content": blocks}})
			events = append(events, b)
		}
	}
	return events, prompts, end, nil
}

func toolResultBlocks(raw json.RawMessage) []json.RawMessage {
	var blocks []json.RawMessage
	if json.Unmarshal(raw, &blocks) != nil {
		return nil
	}
	var out []json.RawMessage
	for _, b := range blocks {
		var h struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(b, &h) == nil && h.Type == "tool_result" {
			out = append(out, b)
		}
	}
	return out
}

// assistantBlocks keeps text and tool_use blocks; thinking is large and private.
func assistantBlocks(raw json.RawMessage) []json.RawMessage {
	var blocks []json.RawMessage
	if json.Unmarshal(raw, &blocks) != nil {
		return nil
	}
	var out []json.RawMessage
	for _, b := range blocks {
		var h struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(b, &h) == nil && (h.Type == "text" || h.Type == "tool_use") {
			out = append(out, b)
		}
	}
	return out
}

// ImportLocal adopts transcript id as a harness session of user.
func (m *Manager) ImportLocal(user, id string, force bool) (*Session, error) {
	if !uuidRe.MatchString(id) {
		return nil, errNotFound
	}
	m.mu.RLock()
	_, exists := m.sessions[id]
	m.mu.RUnlock()
	if exists {
		return nil, errAlreadyManaged
	}
	files, _ := filepath.Glob(filepath.Join(m.cfg.claudeHome(), "projects", "*", id+".jsonl"))
	if len(files) == 0 {
		return nil, errNotFound
	}
	ls, ok := scanTranscript(files[0])
	if !ok {
		return nil, errors.New("transcript has no conversation to import")
	}
	cwd, err := m.ResolveExisting(user, ls.Cwd)
	if err != nil {
		return nil, err
	}
	if ls.Active && !force {
		return nil, errBusyElsewhere
	}
	events, prompts, end, err := convertTranscript(files[0], 0)
	if err != nil {
		return nil, err
	}
	if len(events) > importMaxEvents {
		events = events[len(events)-importMaxEvents:]
	}

	now := time.Now()
	meta := SessionMeta{
		ID: id, User: user, Title: ls.Title, Cwd: cwd, PermissionMode: "manual",
		Started: true, Turns: prompts, CreatedAt: now, UpdatedAt: ls.UpdatedAt, SyncedSize: end,
	}
	if err := m.store.SaveMeta(meta); err != nil {
		return nil, err
	}
	log, err := OpenEventLog(filepath.Join(m.store.sessionDir(user, id), "events.jsonl"))
	if err != nil {
		m.store.Remove(user, id)
		return nil, err
	}
	log.Append(harnessEvent("imported", map[string]any{"messages": len(events), "source": "local"}))
	for _, e := range events {
		if _, _, err := log.Append(e); err != nil {
			log.Close()
			m.store.Remove(user, id)
			return nil, err
		}
	}
	log.Close()

	s := newSession(m, meta)
	m.mu.Lock()
	if _, dup := m.sessions[id]; dup {
		m.mu.Unlock()
		return nil, errAlreadyManaged
	}
	m.sessions[id] = s
	m.mu.Unlock()
	return s, nil
}

// ---- HTTP ---------------------------------------------------------------------

func (a *API) listLocalSessions(w http.ResponseWriter, r *http.Request, user string) {
	list, err := a.mgr.LocalSessions(user)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if list == nil {
		list = []LocalSession{}
	}
	writeJSON(w, 200, map[string]any{"sessions": list})
}

func (a *API) importLocalSession(w http.ResponseWriter, r *http.Request, user string) {
	var req struct {
		Force bool `json:"force"`
	}
	if r.ContentLength != 0 {
		readJSON(w, r, &req) // an empty or missing body is fine
	}
	s, err := a.mgr.ImportLocal(user, r.PathValue("id"), req.Force)
	switch {
	case err == nil:
		writeJSON(w, http.StatusCreated, s.View())
	case errors.Is(err, errNotFound):
		writeErr(w, http.StatusNotFound, "local session not found")
	case errors.Is(err, errAlreadyManaged), errors.Is(err, errBusyElsewhere):
		writeErr(w, http.StatusConflict, err.Error())
	default:
		writeErr(w, http.StatusBadRequest, fmt.Sprint(err))
	}
}
