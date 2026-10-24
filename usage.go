package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Plan usage (the "Session (5hr) / Weekly (7 day) / Fable limit" bars of the
// Claude Code panel) comes from the same OAuth endpoint the CLI uses. It is
// account-wide: whoever is logged in to claude on this machine, not a
// per-harness-user figure. The CLI refreshes its own token whenever it runs,
// so the harness only reads the credentials and never rotates them.

const (
	defaultUsageURL = "https://api.anthropic.com/api/oauth/usage"
	usageCacheTTL   = 60 * time.Second
	usageErrorTTL   = 15 * time.Second
)

// UsageLimit is one bar of the usage panel.
type UsageLimit struct {
	Kind     string     `json:"kind"`  // session | weekly_all | weekly_scoped
	Label    string     `json:"label"` // ready to display
	Percent  float64    `json:"percent"`
	Severity string     `json:"severity,omitempty"`
	ResetsAt *time.Time `json:"resetsAt,omitempty"`
}

// Usage is the normalized /api/usage answer.
type Usage struct {
	Plan      string       `json:"plan,omitempty"`
	Limits    []UsageLimit `json:"limits"`
	FetchedAt time.Time    `json:"fetchedAt"`
}

type usageCache struct {
	mu   sync.Mutex
	val  *Usage
	err  error
	exp  time.Time
	http *http.Client
}

func (c Config) usageURL() string {
	if c.UsageURL != "" {
		return c.UsageURL
	}
	return defaultUsageURL
}

var (
	errNoCredentials = errors.New("claude is not logged in on the server (no OAuth credentials)")
	errTokenExpired  = errors.New("claude login on the server has expired; run claude there once to refresh it")
)

type oauthCreds struct {
	AccessToken      string `json:"accessToken"`
	ExpiresAt        int64  `json:"expiresAt"` // ms since epoch
	SubscriptionType string `json:"subscriptionType"`
}

func (c Config) readCreds() (oauthCreds, error) {
	b, err := os.ReadFile(filepath.Join(c.claudeHome(), ".credentials.json"))
	if err != nil {
		return oauthCreds{}, errNoCredentials
	}
	var f struct {
		OAuth oauthCreds `json:"claudeAiOauth"`
	}
	if json.Unmarshal(b, &f) != nil || f.OAuth.AccessToken == "" {
		return oauthCreds{}, errNoCredentials
	}
	if f.OAuth.ExpiresAt > 0 && time.UnixMilli(f.OAuth.ExpiresAt).Before(time.Now()) {
		return oauthCreds{}, errTokenExpired
	}
	return f.OAuth, nil
}

type rawLimit struct {
	Kind     string  `json:"kind"`
	Percent  float64 `json:"percent"`
	Severity string  `json:"severity"`
	ResetsAt string  `json:"resets_at"`
	Scope    *struct {
		Model *struct {
			DisplayName string `json:"display_name"`
		} `json:"model"`
	} `json:"scope"`
}

type rawWindow struct {
	Utilization float64 `json:"utilization"`
	ResetsAt    string  `json:"resets_at"`
}

func parseTime(s string) *time.Time {
	if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
		return &t
	}
	return nil
}

// parseUsage normalizes the endpoint's body. The `limits` array is preferred
// (it carries model-scoped limits such as Fable); the legacy five_hour /
// seven_day windows are the fallback.
func parseUsage(body []byte, plan string) (*Usage, error) {
	var raw struct {
		Limits    []rawLimit `json:"limits"`
		FiveHour  *rawWindow `json:"five_hour"`
		SevenDay  *rawWindow `json:"seven_day"`
		SevenOpus *rawWindow `json:"seven_day_opus"`
	}
	if err := json.Unmarshal(body, &raw); err != nil {
		return nil, fmt.Errorf("bad usage response: %w", err)
	}
	u := &Usage{Plan: plan, Limits: []UsageLimit{}, FetchedAt: time.Now()}
	for _, l := range raw.Limits {
		out := UsageLimit{Kind: l.Kind, Percent: l.Percent, Severity: l.Severity, ResetsAt: parseTime(l.ResetsAt)}
		switch l.Kind {
		case "session":
			out.Label = "当前会话（5 小时）"
		case "weekly_all":
			out.Label = "每周（7 天）"
		case "weekly_scoped":
			name := "模型"
			if l.Scope != nil && l.Scope.Model != nil && l.Scope.Model.DisplayName != "" {
				name = l.Scope.Model.DisplayName
			}
			out.Label = name + " 额度"
		default:
			continue // unknown kinds have no stable meaning to show
		}
		u.Limits = append(u.Limits, out)
	}
	if len(u.Limits) == 0 {
		add := func(kind, label string, w *rawWindow) {
			if w != nil {
				u.Limits = append(u.Limits, UsageLimit{Kind: kind, Label: label, Percent: w.Utilization, ResetsAt: parseTime(w.ResetsAt)})
			}
		}
		add("session", "当前会话（5 小时）", raw.FiveHour)
		add("weekly_all", "每周（7 天）", raw.SevenDay)
		add("weekly_scoped", "Opus 额度", raw.SevenOpus)
	}
	return u, nil
}

func (a *API) fetchUsage() (*Usage, error) {
	cred, err := a.cfg.readCreds()
	if err != nil {
		return nil, err
	}
	req, _ := http.NewRequest("GET", a.cfg.usageURL(), nil)
	req.Header.Set("Authorization", "Bearer "+cred.AccessToken)
	req.Header.Set("anthropic-beta", "oauth-2025-04-20")
	req.Header.Set("Accept", "application/json")
	resp, err := a.usage.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("usage request failed: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	switch {
	case resp.StatusCode == http.StatusUnauthorized:
		return nil, errTokenExpired
	case resp.StatusCode != http.StatusOK:
		return nil, fmt.Errorf("usage endpoint answered %d", resp.StatusCode)
	}
	return parseUsage(body, cred.SubscriptionType)
}

// GET /api/usage — cached for a minute so several clients polling stay cheap.
func (a *API) getUsage(w http.ResponseWriter, r *http.Request, user string) {
	c := a.usage
	c.mu.Lock()
	if time.Now().After(c.exp) {
		c.val, c.err = a.fetchUsage()
		c.exp = time.Now().Add(usageCacheTTL)
		if c.err != nil {
			c.exp = time.Now().Add(usageErrorTTL)
		}
	}
	val, err := c.val, c.err
	c.mu.Unlock()
	if err != nil {
		code := http.StatusBadGateway
		if errors.Is(err, errNoCredentials) || errors.Is(err, errTokenExpired) {
			code = http.StatusServiceUnavailable
		}
		writeErr(w, code, err.Error())
		return
	}
	writeJSON(w, 200, val)
}
