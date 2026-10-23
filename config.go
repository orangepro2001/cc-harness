package main

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Config is loaded entirely from HARNESS_* environment variables.
type Config struct {
	Password      string            // shared password (HARNESS_PASSWORD)
	UserPasswords map[string]string // optional per-user passwords (HARNESS_USER_PASSWORDS=alice=pw,bob=pw)
	Binds         []string          // "auto" or host[:port] entries (HARNESS_BIND)
	Port          string            // default port (HARNESS_PORT)
	DataDir       string            // HARNESS_DATA
	ClaudeBin     string            // HARNESS_CLAUDE_BIN
	ExtraRoots    []string          // extra directories sessions may use as cwd (HARNESS_ROOTS, ':' separated)
	AllowBypass   bool              // HARNESS_ALLOW_BYPASS=1 permits bypassPermissions sessions
	IdleTimeout   time.Duration     // idle claude processes are reaped after this long
	MaxProcs      int               // max simultaneously running claude processes
	TokenTTL      time.Duration     // login token lifetime
}

var userRe = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,31}$`)

func validUser(u string) bool { return userRe.MatchString(u) }

func splitList(s string, sep string) []string {
	var out []string
	for _, p := range strings.Split(s, sep) {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func envDuration(key string, def time.Duration) (time.Duration, error) {
	v := os.Getenv(key)
	if v == "" {
		return def, nil
	}
	d, err := time.ParseDuration(v)
	if err != nil || d <= 0 {
		return 0, fmt.Errorf("%s: invalid duration %q", key, v)
	}
	return d, nil
}

func LoadConfig() (Config, error) {
	var c Config
	c.Password = os.Getenv("HARNESS_PASSWORD")
	if c.Password == "" {
		return c, errors.New("HARNESS_PASSWORD is required")
	}

	c.UserPasswords = map[string]string{}
	for _, kv := range splitList(os.Getenv("HARNESS_USER_PASSWORDS"), ",") {
		name, pw, ok := strings.Cut(kv, "=")
		name = strings.ToLower(strings.TrimSpace(name))
		if !ok || !validUser(name) || pw == "" {
			return c, fmt.Errorf("HARNESS_USER_PASSWORDS: bad entry for user %q", name)
		}
		c.UserPasswords[name] = pw
	}

	c.Port = os.Getenv("HARNESS_PORT")
	if c.Port == "" {
		c.Port = "8765"
	}
	if n, err := strconv.Atoi(c.Port); err != nil || n < 1 || n > 65535 {
		return c, fmt.Errorf("HARNESS_PORT: invalid port %q", c.Port)
	}

	bind := os.Getenv("HARNESS_BIND")
	if bind == "" {
		bind = "auto"
	}
	c.Binds = splitList(bind, ",")

	home, _ := os.UserHomeDir()
	c.DataDir = os.Getenv("HARNESS_DATA")
	if c.DataDir == "" {
		c.DataDir = filepath.Join(home, ".local", "share", "cc-harness")
	}
	if err := os.MkdirAll(c.DataDir, 0o700); err != nil {
		return c, err
	}
	if real, err := filepath.EvalSymlinks(c.DataDir); err == nil {
		c.DataDir = real
	}

	c.ClaudeBin = os.Getenv("HARNESS_CLAUDE_BIN")
	if c.ClaudeBin == "" {
		if p, err := exec.LookPath("claude"); err == nil {
			c.ClaudeBin = p
		} else {
			c.ClaudeBin = filepath.Join(home, ".local", "bin", "claude")
		}
	}

	for _, r := range splitList(os.Getenv("HARNESS_ROOTS"), ":") {
		if !filepath.IsAbs(r) {
			return c, fmt.Errorf("HARNESS_ROOTS: %q is not absolute", r)
		}
		c.ExtraRoots = append(c.ExtraRoots, filepath.Clean(r))
	}

	c.AllowBypass = os.Getenv("HARNESS_ALLOW_BYPASS") == "1"

	var err error
	if c.IdleTimeout, err = envDuration("HARNESS_IDLE_TIMEOUT", 30*time.Minute); err != nil {
		return c, err
	}
	if c.TokenTTL, err = envDuration("HARNESS_TOKEN_TTL", 30*24*time.Hour); err != nil {
		return c, err
	}
	c.MaxProcs = 8
	if v := os.Getenv("HARNESS_MAX_PROCS"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 64 {
			return c, fmt.Errorf("HARNESS_MAX_PROCS: invalid value %q", v)
		}
		c.MaxProcs = n
	}
	return c, nil
}

// loadEnvFile reads KEY=VALUE lines into the environment without overriding
// variables that are already set.
func loadEnvFile(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		k = strings.TrimSpace(k)
		v = strings.TrimSpace(v)
		if len(v) >= 2 && (v[0] == '"' || v[0] == '\'') && v[len(v)-1] == v[0] {
			v = v[1 : len(v)-1]
		}
		if _, set := os.LookupEnv(k); !set {
			os.Setenv(k, v)
		}
	}
	return sc.Err()
}
