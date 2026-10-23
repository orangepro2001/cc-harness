package main

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"sync"
	"time"
)

var uuidRe = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func newUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// SessionMeta is the persisted description of one conversation.
type SessionMeta struct {
	ID             string    `json:"id"`
	User           string    `json:"user"`
	Title          string    `json:"title"`
	Cwd            string    `json:"cwd"`
	Model          string    `json:"model,omitempty"`
	PermissionMode string    `json:"permissionMode"`
	AppendPrompt   string    `json:"appendSystemPrompt,omitempty"`
	Started        bool      `json:"started"` // claude has a transcript to --resume
	Turns          int       `json:"turns"`
	CreatedAt      time.Time `json:"createdAt"`
	UpdatedAt      time.Time `json:"updatedAt"`
}

// Store lays out data as <root>/users/<user>/{workspace,sessions/<id>/}.
type Store struct{ root string }

func NewStore(root string) *Store { return &Store{root: root} }

func (s *Store) userDir(user string) string { return filepath.Join(s.root, "users", user) }

func (s *Store) Workspace(user string) (string, error) {
	p := filepath.Join(s.userDir(user), "workspace")
	return p, os.MkdirAll(p, 0o700)
}

func (s *Store) sessionDir(user, id string) string {
	return filepath.Join(s.userDir(user), "sessions", id)
}

func (s *Store) SaveMeta(m SessionMeta) error {
	dir := s.sessionDir(m.User, m.ID)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	tmp := filepath.Join(dir, "meta.json.tmp")
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(dir, "meta.json"))
}

func (s *Store) LoadAll() []SessionMeta {
	var out []SessionMeta
	metas, _ := filepath.Glob(filepath.Join(s.root, "users", "*", "sessions", "*", "meta.json"))
	for _, p := range metas {
		b, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		var m SessionMeta
		// A record is only trusted when its ids match its location.
		if json.Unmarshal(b, &m) != nil || !validUser(m.User) || !uuidRe.MatchString(m.ID) {
			continue
		}
		if filepath.Dir(p) != s.sessionDir(m.User, m.ID) {
			continue
		}
		out = append(out, m)
	}
	return out
}

func (s *Store) Remove(user, id string) error { return os.RemoveAll(s.sessionDir(user, id)) }

// EventLog is an append-only JSONL file. Every line is
//
//	{"seq":N,"ts":<unix ms>,"data":<event>}
//
// which lets clients resume from any sequence number after a disconnect.
type EventLog struct {
	path string
	mu   sync.Mutex
	f    *os.File
	seq  int64
}

const seqPrefix = `{"seq":`

func parseSeq(line []byte) (int64, bool) {
	if !bytes.HasPrefix(line, []byte(seqPrefix)) {
		return 0, false
	}
	rest := line[len(seqPrefix):]
	end := bytes.IndexByte(rest, ',')
	if end <= 0 {
		return 0, false
	}
	n, err := strconv.ParseInt(string(rest[:end]), 10, 64)
	return n, err == nil
}

// iterLines calls fn for each complete (newline terminated) line.
func iterLines(path string, fn func(line []byte) bool) error {
	f, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	defer f.Close()
	r := bufio.NewReaderSize(f, 1<<20)
	for {
		line, err := r.ReadBytes('\n')
		if len(line) > 0 && line[len(line)-1] == '\n' {
			if !fn(bytes.TrimRight(line, "\r\n")) {
				return nil
			}
		}
		if err != nil {
			if err == io.EOF {
				return nil
			}
			return err
		}
	}
}

func OpenEventLog(path string) (*EventLog, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	l := &EventLog{path: path}
	var good int64 // bytes covered by complete lines
	if err := iterLines(path, func(line []byte) bool {
		good += int64(len(line)) + 1
		if n, ok := parseSeq(line); ok && n > l.seq {
			l.seq = n
		}
		return true
	}); err != nil {
		return nil, err
	}
	// Drop a torn final line left by a crash so the next record starts clean.
	if st, err := os.Stat(path); err == nil && st.Size() > good {
		if err := os.Truncate(path, good); err != nil {
			return nil, err
		}
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return nil, err
	}
	l.f = f
	return l, nil
}

func (l *EventLog) Seq() int64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.seq
}

func wrapEvent(seq int64, raw []byte) []byte {
	if bytes.IndexByte(raw, '\n') >= 0 {
		var buf bytes.Buffer
		if json.Compact(&buf, raw) == nil {
			raw = buf.Bytes()
		}
	}
	line := make([]byte, 0, len(raw)+48)
	line = append(line, seqPrefix...)
	line = strconv.AppendInt(line, seq, 10)
	line = append(line, `,"ts":`...)
	line = strconv.AppendInt(line, time.Now().UnixMilli(), 10)
	line = append(line, `,"data":`...)
	line = append(line, raw...)
	line = append(line, '}')
	return line
}

// Append stores raw and returns its sequence number and wire line (no newline).
func (l *EventLog) Append(raw []byte) (int64, []byte, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	next := l.seq + 1
	line := wrapEvent(next, raw)
	if _, err := l.f.Write(append(line[:len(line):len(line)], '\n')); err != nil {
		return 0, nil, err
	}
	l.seq = next
	return next, line, nil
}

// Read streams stored lines with after < seq <= upTo to fn.
func (l *EventLog) Read(after, upTo int64, fn func(seq int64, line []byte) error) error {
	var ferr error
	err := iterLines(l.path, func(line []byte) bool {
		n, ok := parseSeq(line)
		if !ok || n <= after {
			return true
		}
		if n > upTo {
			return false
		}
		if ferr = fn(n, line); ferr != nil {
			return false
		}
		return true
	})
	if ferr != nil {
		return ferr
	}
	return err
}

func (l *EventLog) Close() {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.f != nil {
		l.f.Close()
	}
}
