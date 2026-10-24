package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode"
)

const (
	defaultMaxUpload   = 25 << 20 // per file
	maxInlineImage     = 5 << 20  // the model API rejects larger inline images
	maxAttachments     = 10       // per message
	maxSessionUploads  = 1 << 30  // total bytes kept per session
	uploadsDirName     = "uploads"
	attachmentKindFile = "file"
	attachmentKindImg  = "image"
)

var uploadIDRe = regexp.MustCompile(`^[0-9a-f]{16}$`)

var errUploadQuota = errors.New("session upload quota exceeded")

// Attachment describes one uploaded file. It is persisted next to the file and
// echoed in the user_message event so every client can render it.
type Attachment struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Kind string `json:"kind"` // "image" (sent inline to the model) or "file"
	Mime string `json:"mime"`
	Size int64  `json:"size"`
}

func (a Attachment) path(dir string) string { return filepath.Join(dir, a.ID, a.Name) }

// uploadsDir is where this session's attachments live. The agent is granted
// access to it with --add-dir so it can read the files by path.
func (s *Session) uploadsDir() string {
	return filepath.Join(s.m.store.sessionDir(s.meta.User, s.meta.ID), uploadsDirName)
}

// sanitizeFileName keeps letters (any script), digits and a few safe symbols.
func sanitizeFileName(name string) string {
	name = filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	var b strings.Builder
	for _, r := range name {
		switch {
		case unicode.IsLetter(r), unicode.IsDigit(r), r == '.', r == '-', r == '_', r == ' ', r == '(', r == ')':
			b.WriteRune(r)
		default:
			b.WriteByte('_')
		}
	}
	out := strings.Trim(b.String(), ". ")
	if r := []rune(out); len(r) > 100 {
		// keep the extension when truncating
		ext := filepath.Ext(out)
		if len([]rune(ext)) > 12 {
			ext = ""
		}
		out = string(r[:100-len([]rune(ext))]) + ext
	}
	if out == "" {
		return "file"
	}
	return out
}

func isInlineImageMime(m string) bool {
	switch m {
	case "image/png", "image/jpeg", "image/gif", "image/webp":
		return true
	}
	return false
}

func dirSize(dir string) int64 {
	var n int64
	filepath.WalkDir(dir, func(_ string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if info, e := d.Info(); e == nil {
				n += info.Size()
			}
		}
		return nil
	})
	return n
}

// SaveUpload stores the body as a new attachment of the session.
func (s *Session) SaveUpload(name string, body io.Reader, limit int64) (Attachment, error) {
	dir := s.uploadsDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return Attachment{}, err
	}
	if dirSize(dir) >= maxSessionUploads {
		return Attachment{}, errUploadQuota
	}
	var idb [8]byte
	if _, err := rand.Read(idb[:]); err != nil {
		return Attachment{}, err
	}
	att := Attachment{ID: hex.EncodeToString(idb[:]), Name: sanitizeFileName(name)}
	fdir := filepath.Join(dir, att.ID)
	if err := os.Mkdir(fdir, 0o700); err != nil {
		return Attachment{}, err
	}
	fail := func(err error) (Attachment, error) {
		os.RemoveAll(fdir)
		return Attachment{}, err
	}
	f, err := os.OpenFile(att.path(dir), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return fail(err)
	}
	head := make([]byte, 512)
	n, err := io.ReadFull(body, head)
	if err != nil && err != io.EOF && err != io.ErrUnexpectedEOF {
		f.Close()
		return fail(err)
	}
	head = head[:n]
	if n == 0 {
		f.Close()
		return fail(errors.New("empty file"))
	}
	// The sniffed type decides how the file is treated, never the client's claim.
	att.Mime = http.DetectContentType(head)
	if i := strings.Index(att.Mime, ";"); i >= 0 {
		att.Mime = att.Mime[:i]
	}
	if _, err := f.Write(head); err != nil {
		f.Close()
		return fail(err)
	}
	rest, err := io.Copy(f, io.LimitReader(body, limit-int64(n)+1))
	f.Close()
	if err != nil {
		return fail(err)
	}
	att.Size = int64(n) + rest
	if att.Size > limit {
		return fail(fmt.Errorf("file exceeds the %d MB limit", limit>>20))
	}
	att.Kind = attachmentKindFile
	if isInlineImageMime(att.Mime) && att.Size <= maxInlineImage {
		att.Kind = attachmentKindImg
	}
	meta, _ := json.Marshal(att)
	if err := os.WriteFile(filepath.Join(dir, att.ID+".json"), meta, 0o600); err != nil {
		return fail(err)
	}
	return att, nil
}

// LoadUpload returns the stored description of attachment id.
func (s *Session) LoadUpload(id string) (Attachment, error) {
	if !uploadIDRe.MatchString(id) {
		return Attachment{}, errNotFound
	}
	b, err := os.ReadFile(filepath.Join(s.uploadsDir(), id+".json"))
	if err != nil {
		return Attachment{}, errNotFound
	}
	var a Attachment
	if json.Unmarshal(b, &a) != nil || a.ID != id || a.Name != sanitizeFileName(a.Name) {
		return Attachment{}, errNotFound
	}
	return a, nil
}

// attachmentBlocks renders attachments for the model: the text note naming
// each file by path, plus one inline content block per small image.
func (s *Session) attachmentBlocks(atts []Attachment) (note string, blocks []map[string]any, err error) {
	dir := s.uploadsDir()
	var sb strings.Builder
	for _, a := range atts {
		fmt.Fprintf(&sb, "- %s (%s, %d bytes): %s\n", a.Name, a.Mime, a.Size, a.path(dir))
		if a.Kind != attachmentKindImg {
			continue
		}
		data, rerr := os.ReadFile(a.path(dir))
		if rerr != nil {
			return "", nil, fmt.Errorf("attachment %s: %w", a.Name, rerr)
		}
		blocks = append(blocks, map[string]any{
			"type": "image",
			"source": map[string]any{
				"type": "base64", "media_type": a.Mime, "data": base64.StdEncoding.EncodeToString(data),
			},
		})
	}
	return "[The user attached these files; read them by path if needed]\n" + sb.String(), blocks, nil
}

// ---- HTTP -------------------------------------------------------------------

func (a *API) maxUpload() int64 {
	if a.cfg.MaxUpload > 0 {
		return a.cfg.MaxUpload
	}
	return defaultMaxUpload
}

// upload takes the raw request body as the file: POST .../uploads?name=<file name>.
func (a *API) upload(w http.ResponseWriter, r *http.Request, s *Session) {
	limit := a.maxUpload()
	if r.ContentLength > limit {
		writeErr(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("file exceeds the %d MB limit", limit>>20))
		return
	}
	body := http.MaxBytesReader(w, r.Body, limit+1)
	att, err := s.SaveUpload(r.URL.Query().Get("name"), body, limit)
	if err != nil {
		code := http.StatusBadRequest
		var mbe *http.MaxBytesError
		switch {
		case errors.Is(err, errUploadQuota):
			code = http.StatusInsufficientStorage
		case errors.As(err, &mbe), strings.Contains(err.Error(), "limit"):
			code = http.StatusRequestEntityTooLarge
		}
		writeErr(w, code, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, att)
}

// download serves one attachment back. Only images are shown inline; anything
// else is a download, and a sandbox CSP stops active content from running.
func (a *API) download(w http.ResponseWriter, r *http.Request, s *Session) {
	att, err := s.LoadUpload(r.PathValue("uid"))
	if err != nil {
		writeErr(w, http.StatusNotFound, "attachment not found")
		return
	}
	f, err := os.Open(att.path(s.uploadsDir()))
	if err != nil {
		writeErr(w, http.StatusNotFound, "attachment not found")
		return
	}
	defer f.Close()
	h := w.Header()
	h.Set("Content-Security-Policy", "sandbox")
	h.Set("Cache-Control", "private, max-age=3600")
	if att.Kind == attachmentKindImg {
		h.Set("Content-Type", att.Mime)
		h.Set("Content-Disposition", "inline")
	} else {
		h.Set("Content-Type", "application/octet-stream")
		h.Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlEscapeName(att.Name))
	}
	w.Header().Set("Content-Length", fmt.Sprint(att.Size))
	io.Copy(w, f)
}

func urlEscapeName(s string) string {
	var b strings.Builder
	for _, c := range []byte(s) {
		if c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_' {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}
