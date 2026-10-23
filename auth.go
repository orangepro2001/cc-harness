package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	cookieName      = "cch_session"
	maxIPFails      = 5
	ipLockDuration  = 5 * time.Minute
	globalFailLimit = 30
	globalFailWin   = 10 * time.Minute
)

type failState struct {
	n     int
	until time.Time
	last  time.Time
}

// Auth verifies passwords, issues signed tokens and throttles guessing.
type Auth struct {
	cfg Config
	key []byte

	mu     sync.Mutex
	fails  map[string]*failState
	global []time.Time
	now    func() time.Time
}

func NewAuth(cfg Config) (*Auth, error) {
	secretPath := filepath.Join(cfg.DataDir, "secret")
	secret, err := os.ReadFile(secretPath)
	if err != nil || len(secret) != 32 {
		secret = make([]byte, 32)
		if _, err := rand.Read(secret); err != nil {
			return nil, err
		}
		if err := os.WriteFile(secretPath, secret, 0o600); err != nil {
			return nil, err
		}
	}
	// The signing key is bound to the configured passwords, so changing a
	// password invalidates every token issued before.
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(cfg.Password))
	names := make([]string, 0, len(cfg.UserPasswords))
	for n := range cfg.UserPasswords {
		names = append(names, n)
	}
	sort.Strings(names)
	for _, n := range names {
		mac.Write([]byte{0})
		mac.Write([]byte(n + "=" + cfg.UserPasswords[n]))
	}
	return &Auth{cfg: cfg, key: mac.Sum(nil), fails: map[string]*failState{}, now: time.Now}, nil
}

func (a *Auth) sign(payload string) string {
	m := hmac.New(sha256.New, a.key)
	m.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// CheckPassword compares in constant time. A user with a dedicated password
// can no longer log in with the shared one.
func (a *Auth) CheckPassword(user, pw string) bool {
	if !validUser(user) {
		return false
	}
	want := a.cfg.Password
	if p, ok := a.cfg.UserPasswords[user]; ok {
		want = p
	}
	h1 := sha256.Sum256([]byte(pw))
	h2 := sha256.Sum256([]byte(want))
	return subtle.ConstantTimeCompare(h1[:], h2[:]) == 1
}

func (a *Auth) Issue(user string) string {
	exp := a.now().Add(a.cfg.TokenTTL).Unix()
	payload := base64.RawURLEncoding.EncodeToString([]byte(user + "|" + strconv.FormatInt(exp, 10)))
	return payload + "." + a.sign(payload)
}

func (a *Auth) Verify(tok string) (string, bool) {
	payload, sig, ok := strings.Cut(tok, ".")
	if !ok || !hmac.Equal([]byte(sig), []byte(a.sign(payload))) {
		return "", false
	}
	raw, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil {
		return "", false
	}
	user, expS, ok := strings.Cut(string(raw), "|")
	exp, err := strconv.ParseInt(expS, 10, 64)
	if !ok || err != nil || a.now().Unix() > exp || !validUser(user) {
		return "", false
	}
	return user, true
}

// Blocked reports whether this client may not attempt a login right now.
func (a *Auth) Blocked(ip string) (time.Duration, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.now()
	if f := a.fails[ip]; f != nil && now.Before(f.until) {
		return f.until.Sub(now), true
	}
	a.pruneGlobal(now)
	if len(a.global) >= globalFailLimit {
		return a.global[0].Add(globalFailWin).Sub(now), true
	}
	return 0, false
}

func (a *Auth) pruneGlobal(now time.Time) {
	i := 0
	for i < len(a.global) && now.Sub(a.global[i]) > globalFailWin {
		i++
	}
	a.global = a.global[i:]
}

func (a *Auth) Fail(ip string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.now()
	f := a.fails[ip]
	if f == nil {
		f = &failState{}
		a.fails[ip] = f
	}
	if now.Sub(f.last) > ipLockDuration {
		f.n = 0
	}
	f.n++
	f.last = now
	if f.n >= maxIPFails {
		f.until = now.Add(ipLockDuration)
		f.n = 0
	}
	a.pruneGlobal(now)
	a.global = append(a.global, now)
	if len(a.fails) > 4096 { // bound memory under scanning
		for k, v := range a.fails {
			if now.Sub(v.last) > ipLockDuration {
				delete(a.fails, k)
			}
		}
	}
}

func (a *Auth) Succeed(ip string) {
	a.mu.Lock()
	delete(a.fails, ip)
	a.mu.Unlock()
}

func clientIP(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

var (
	errNoCreds  = errors.New("authentication required")
	errBadCreds = errors.New("invalid credentials")
	errLocked   = errors.New("too many failed attempts")
)

// Authenticate resolves the request's user from a bearer token, session
// cookie or HTTP basic credentials, in that order.
func (a *Auth) Authenticate(r *http.Request) (string, error) {
	ip := clientIP(r)
	if _, blocked := a.Blocked(ip); blocked {
		return "", errLocked
	}
	if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
		if u, ok := a.Verify(strings.TrimSpace(h[7:])); ok {
			return u, nil
		}
		return "", errBadCreds
	}
	if c, err := r.Cookie(cookieName); err == nil {
		if u, ok := a.Verify(c.Value); ok {
			return u, nil
		}
	}
	if user, pw, ok := r.BasicAuth(); ok {
		user = strings.ToLower(user)
		if a.CheckPassword(user, pw) {
			a.Succeed(ip)
			return user, nil
		}
		a.Fail(ip)
		return "", errBadCreds
	}
	return "", errNoCreds
}

// sameOrigin rejects cross-origin state-changing requests.
func sameOrigin(r *http.Request) bool {
	switch r.Method {
	case http.MethodGet, http.MethodHead, http.MethodOptions:
		return true
	}
	o := r.Header.Get("Origin")
	if o == "" {
		return true
	}
	u, err := url.Parse(o)
	return err == nil && u.Host == r.Host
}
