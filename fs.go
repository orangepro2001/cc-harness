package main

import (
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type dirItem struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

const maxDirEntries = 2000

// listDirs serves GET /api/fs?path=<abs>&hidden=1. Without a path it lists the
// user's roots; otherwise the sub-directories of path (which must be inside a
// root). "parent" is empty at the top of a root, meaning "back to the roots".
func (a *API) listDirs(w http.ResponseWriter, r *http.Request, user string) {
	roots, err := a.mgr.Roots(user)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	q := r.URL.Query().Get("path")
	if q == "" {
		items := make([]dirItem, 0, len(roots))
		for i, root := range roots {
			name := root
			if i == 0 {
				name = "个人工作区"
			}
			items = append(items, dirItem{Name: name, Path: root})
		}
		writeJSON(w, 200, map[string]any{"path": "", "parent": "", "entries": items})
		return
	}
	if !filepath.IsAbs(q) {
		writeErr(w, http.StatusBadRequest, "path must be absolute")
		return
	}
	dir, err := a.mgr.ResolveExisting(user, filepath.Clean(q))
	if err != nil {
		writeErr(w, http.StatusForbidden, err.Error())
		return
	}
	showHidden := r.URL.Query().Get("hidden") == "1"
	ents, err := os.ReadDir(dir)
	if err != nil {
		writeErr(w, http.StatusForbidden, "cannot read directory")
		return
	}
	items := []dirItem{}
	for _, e := range ents {
		name := e.Name()
		if !showHidden && strings.HasPrefix(name, ".") {
			continue
		}
		full := filepath.Join(dir, name)
		if !e.IsDir() {
			if e.Type()&os.ModeSymlink == 0 {
				continue
			}
			// a symlink is listed only if it resolves to a directory inside a root
			if _, err := a.mgr.ResolveExisting(user, full); err != nil {
				continue
			}
		}
		items = append(items, dirItem{Name: name, Path: full})
		if len(items) >= maxDirEntries {
			break
		}
	}
	sort.Slice(items, func(i, j int) bool { return strings.ToLower(items[i].Name) < strings.ToLower(items[j].Name) })
	parent := filepath.Dir(dir)
	for _, root := range roots {
		if dir == root {
			parent = ""
		}
	}
	writeJSON(w, 200, map[string]any{"path": dir, "parent": parent, "entries": items})
}

// makeDir serves POST /api/fs/mkdir {parent, name}.
func (a *API) makeDir(w http.ResponseWriter, r *http.Request, user string) {
	var req struct {
		Parent string `json:"parent"`
		Name   string `json:"name"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" || name == "." || name == ".." || len(name) > 100 ||
		strings.ContainsAny(name, "/\\\x00") {
		writeErr(w, http.StatusBadRequest, "invalid folder name")
		return
	}
	if !filepath.IsAbs(req.Parent) {
		writeErr(w, http.StatusBadRequest, "parent must be absolute")
		return
	}
	parent, err := a.mgr.ResolveExisting(user, filepath.Clean(req.Parent))
	if err != nil {
		writeErr(w, http.StatusForbidden, err.Error())
		return
	}
	full := filepath.Join(parent, name)
	if err := os.Mkdir(full, 0o755); err != nil {
		if errors.Is(err, os.ErrExist) {
			writeErr(w, http.StatusConflict, "already exists")
		} else {
			writeErr(w, http.StatusForbidden, "cannot create folder")
		}
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"path": full})
}
