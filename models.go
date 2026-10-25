package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"time"
)

// The model picker is driven by what the installed CLI reports (names,
// descriptions and, per model, which effort levels it offers), so it follows
// the CLI instead of a list baked into the clients. The answer comes from the
// CLI's `initialize` control request, which costs no model call.

const (
	modelsCacheTTL = 10 * time.Minute
	modelsErrorTTL = 30 * time.Second
)

// ModelInfo is one entry of the picker.
type ModelInfo struct {
	Value        string   `json:"value"` // "default", an alias (opus) or a full model id
	Name         string   `json:"name"`
	Description  string   `json:"description,omitempty"`
	Resolved     string   `json:"resolved,omitempty"`
	EffortLevels []string `json:"effortLevels"` // empty = the model has no effort setting
	ThinkingOff  bool     `json:"thinkingOff,omitempty"`
}

type modelsCache struct {
	mu  sync.Mutex
	val []ModelInfo
	exp time.Time
}

var allEffortLevels = []string{"low", "medium", "high", "xhigh", "max"}

// fallbackModels is served until the CLI has answered (or when it cannot).
func fallbackModels() []ModelInfo {
	return []ModelInfo{
		{Value: "default", Name: "默认", EffortLevels: allEffortLevels},
		{Value: "fable", Name: "Fable", EffortLevels: allEffortLevels},
		{Value: "opus", Name: "Opus", EffortLevels: allEffortLevels},
		{Value: "sonnet", Name: "Sonnet", EffortLevels: allEffortLevels},
		{Value: "haiku", Name: "Haiku", EffortLevels: allEffortLevels},
	}
}

func (c Config) probeModels() ([]ModelInfo, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, c.ClaudeBin, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose")
	cmd.Dir = os.TempDir()
	cmd.Env = cleanEnv()
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	defer func() { cancel(); cmd.Wait() }()
	if _, err := in.Write([]byte(`{"type":"control_request","request_id":"models","request":{"subtype":"initialize"}}` + "\n")); err != nil {
		return nil, err
	}
	sc := bufio.NewScanner(out)
	sc.Buffer(make([]byte, 1<<20), 8<<20)
	for sc.Scan() {
		var m struct {
			Type     string `json:"type"`
			Response struct {
				RequestID string `json:"request_id"`
				Error     string `json:"error"`
				Response  struct {
					Models []struct {
						Value       string   `json:"value"`
						Resolved    string   `json:"resolvedModel"`
						DisplayName string   `json:"displayName"`
						Description string   `json:"description"`
						Supports    bool     `json:"supportsEffort"`
						Levels      []string `json:"supportedEffortLevels"`
						ThinkingOff bool     `json:"supportsThinkingOff"`
					} `json:"models"`
				} `json:"response"`
			} `json:"response"`
		}
		if json.Unmarshal(sc.Bytes(), &m) != nil || m.Type != "control_response" || m.Response.RequestID != "models" {
			continue
		}
		if m.Response.Error != "" {
			return nil, errors.New(m.Response.Error)
		}
		var res []ModelInfo
		for _, x := range m.Response.Response.Models {
			mi := ModelInfo{Value: x.Value, Name: x.DisplayName, Description: x.Description, Resolved: x.Resolved, ThinkingOff: x.ThinkingOff, EffortLevels: []string{}}
			if x.Supports {
				for _, l := range x.Levels {
					if effortLevels[l] {
						mi.EffortLevels = append(mi.EffortLevels, l)
					}
				}
			}
			res = append(res, mi)
		}
		if len(res) == 0 {
			return nil, errors.New("claude reported no models")
		}
		return res, nil
	}
	return nil, errors.New("claude exited without a model list")
}

func (a *API) getModels(w http.ResponseWriter, r *http.Request, user string) {
	c := a.models
	c.mu.Lock()
	if time.Now().After(c.exp) {
		res, err := a.cfg.probeModels()
		if err != nil {
			log.Printf("model list: %v", err)
			c.exp = time.Now().Add(modelsErrorTTL)
		} else {
			c.val, c.exp = res, time.Now().Add(modelsCacheTTL)
		}
	}
	val := c.val
	c.mu.Unlock()
	if val == nil {
		val = fallbackModels()
	}
	writeJSON(w, 200, map[string]any{"models": val})
}
