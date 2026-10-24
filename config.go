package main

import (
	"bufio"
	"errors"
	"fmt"
	"net"
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
	User          string            // HARNESS_USER: single-user mode, every login is this user whatever name is typed
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
	ClaudeHome    string            // HARNESS_CLAUDE_HOME: where claude keeps its data (default ~/.claude)
	BasePath      string            // HARNESS_BASE_PATH: URL prefix when behind a path-routing proxy, e.g. /claude
	ReleasesDir   string            // HARNESS_RELEASES: published client builds (default <data>/releases)
	UsageURL      string            // HARNESS_USAGE_URL: plan-usage endpoint (default Anthropic's OAuth usage API)
	MaxUpload     int64             // max bytes per uploaded file (HARNESS_MAX_UPLOAD_MB, default 25)

	// Terminal (a real shell on the server, shown by the Android app). Off unless HARNESS_TERMINAL names users.
	TerminalUsers []string     // HARNESS_TERMINAL: comma separated users, or "*" for every user; empty = disabled
	TerminalNets  []*net.IPNet // HARNESS_TERMINAL_NETS: client addresses allowed to use it (default loopback + Tailscale); nil = any
	TerminalMax   int          // HARNESS_TERMINAL_MAX: terminals per user (default 8)
	TerminalShell string       // HARNESS_TERMINAL_SHELL: login shell to run (default $SHELL or /bin/bash)
	TmuxBin       string       // HARNESS_TMUX_BIN (default: tmux from PATH)
	TmuxSocket    string       // HARNESS_TMUX_SOCKET: tmux server name (default cch)
}

var basePathRe = regexp.MustCompile(`^[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*$`)

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

	if u := strings.ToLower(strings.TrimSpace(os.Getenv("HARNESS_USER"))); u != "" {
		if !validUser(u) {
			return c, fmt.Errorf("HARNESS_USER: invalid name %q", u)
		}
		if os.Getenv("HARNESS_USER_PASSWORDS") != "" {
			return c, errors.New("HARNESS_USER and HARNESS_USER_PASSWORDS are mutually exclusive")
		}
		c.User = u
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
	if bp := strings.Trim(os.Getenv("HARNESS_BASE_PATH"), "/ "); bp != "" {
		if !basePathRe.MatchString(bp) {
			return c, fmt.Errorf("HARNESS_BASE_PATH: invalid value %q", bp)
		}
		c.BasePath = "/" + bp
	}
	c.ClaudeHome = os.Getenv("HARNESS_CLAUDE_HOME")
	c.ReleasesDir = os.Getenv("HARNESS_RELEASES")
	c.UsageURL = os.Getenv("HARNESS_USAGE_URL")
	if v := os.Getenv("HARNESS_MAX_UPLOAD_MB"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 1024 {
			return c, fmt.Errorf("HARNESS_MAX_UPLOAD_MB: invalid value %q", v)
		}
		c.MaxUpload = int64(n) << 20
	}
	if err := loadTerminalConfig(&c); err != nil {
		return c, err
	}
	return c, nil
}

// defaultTerminalNets: this machine and the tailnet. LAN addresses are excluded on purpose, the
// harness speaks plain HTTP there and a terminal would send keystrokes in the clear.
var defaultTerminalNets = "127.0.0.0/8,::1/128,100.64.0.0/10,fd7a:115c:a1e0::/48"

func loadTerminalConfig(c *Config) error {
	c.TerminalMax = 8
	if v := os.Getenv("HARNESS_TERMINAL_MAX"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 64 {
			return fmt.Errorf("HARNESS_TERMINAL_MAX: invalid value %q", v)
		}
		c.TerminalMax = n
	}
	for _, u := range splitList(os.Getenv("HARNESS_TERMINAL"), ",") {
		u = strings.ToLower(u)
		if u == "1" || u == "true" || u == "yes" || u == "on" {
			u = "*" // plain "on": fine in single-user mode, where there is only one user
		}
		if u != "*" && !validUser(u) {
			return fmt.Errorf("HARNESS_TERMINAL: bad user %q", u)
		}
		c.TerminalUsers = append(c.TerminalUsers, u)
	}
	nets := os.Getenv("HARNESS_TERMINAL_NETS")
	if nets == "" {
		nets = defaultTerminalNets
	}
	if nets != "any" {
		for _, n := range splitList(nets, ",") {
			if !strings.Contains(n, "/") {
				if ip := net.ParseIP(n); ip != nil && ip.To4() != nil {
					n += "/32"
				} else {
					n += "/128"
				}
			}
			_, ipn, err := net.ParseCIDR(n)
			if err != nil {
				return fmt.Errorf("HARNESS_TERMINAL_NETS: %v", err)
			}
			c.TerminalNets = append(c.TerminalNets, ipn)
		}
	}
	c.TerminalShell = os.Getenv("HARNESS_TERMINAL_SHELL")
	if c.TerminalShell == "" {
		c.TerminalShell = os.Getenv("SHELL")
	}
	if c.TerminalShell == "" {
		c.TerminalShell = "/bin/bash"
	}
	c.TmuxBin = os.Getenv("HARNESS_TMUX_BIN")
	c.TmuxSocket = os.Getenv("HARNESS_TMUX_SOCKET")
	return nil
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
