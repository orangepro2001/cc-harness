package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

// Release describes the newest published build of a client app. It lives in
// <releases>/<app>.json next to the package file (see deploy/publish-android.sh).
type Release struct {
	VersionCode int    `json:"versionCode"`
	VersionName string `json:"versionName"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
	File        string `json:"file"`
	Notes       string `json:"notes,omitempty"`
	PublishedAt string `json:"publishedAt,omitempty"`
}

var (
	sha256Re   = regexp.MustCompile(`^[0-9a-f]{64}$`)
	releaseRe  = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,100}$`)
	releaseApp = "android"
)

// ReleasesDir is where published client builds are kept (HARNESS_RELEASES, default <data>/releases).
func (c Config) releasesDir() string {
	if c.ReleasesDir != "" {
		return c.ReleasesDir
	}
	return filepath.Join(c.DataDir, "releases")
}

// loadRelease reads and validates the manifest for app; the file name is
// confined to the releases directory so a bad manifest cannot expose other files.
func (a *API) loadRelease(app string) (Release, string, error) {
	dir := a.cfg.releasesDir()
	b, err := os.ReadFile(filepath.Join(dir, app+".json"))
	if err != nil {
		return Release{}, "", err
	}
	var r Release
	if err := json.Unmarshal(b, &r); err != nil {
		return Release{}, "", err
	}
	if r.VersionCode < 1 || !sha256Re.MatchString(r.SHA256) || !releaseRe.MatchString(r.File) {
		return Release{}, "", fmt.Errorf("invalid release manifest")
	}
	path := filepath.Join(dir, r.File)
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return Release{}, "", fmt.Errorf("release file missing")
	}
	r.Size = info.Size()
	return r, path, nil
}

func (a *API) latestRelease(w http.ResponseWriter, r *http.Request, user string) {
	rel, _, err := a.loadRelease(releaseApp)
	if err != nil {
		writeErr(w, http.StatusNotFound, "no release published")
		return
	}
	writeJSON(w, 200, rel)
}

func (a *API) downloadRelease(w http.ResponseWriter, r *http.Request, user string) {
	rel, path, err := a.loadRelease(releaseApp)
	if err != nil {
		writeErr(w, http.StatusNotFound, "no release published")
		return
	}
	f, err := os.Open(path)
	if err != nil {
		writeErr(w, http.StatusNotFound, "no release published")
		return
	}
	defer f.Close()
	h := w.Header()
	h.Set("Content-Type", "application/vnd.android.package-archive")
	h.Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, rel.File))
	h.Set("Cache-Control", "no-store")
	http.ServeContent(w, r, rel.File, time.Time{}, f) // handles Range, so interrupted downloads can resume
}
