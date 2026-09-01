/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

//go:build coverage

// Package covsnap exposes runtime coverage-counter control so an out-of-process
// black-box test harness (api-test) can attribute coverage to the surface that
// produced it.
//
// A binary built with `go build -cover` only flushes its counters when the
// process exits normally, which would mean one server restart per test surface
// — expensive here, since a restart also means re-establishing postgres/redis
// and re-reading the flow/design config. runtime/coverage lets the running
// process snapshot and reset its own counters instead, so the harness can do
// reset -> run conformance -> snapshot -> reset -> run api -> snapshot -> ...
// against a single long-lived server, and still get counters attributable to
// one surface each.
//
// This file is behind the `coverage` build tag: a production build compiles
// covsnap_off.go instead and registers nothing, so the endpoints below cannot
// exist in a shipped binary. Build the instrumented server with
// `./make.sh build-cover`.
package covsnap

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime/coverage"
	"strings"

	applog "github.com/mosip/esignet/internal/log"
)

// BasePathEnv names the environment variable holding the directory snapshots are
// written under. It is the same variable `go build -cover` binaries already read
// for their exit-time dump, so an instrumented run needs no extra configuration.
const BasePathEnv = "GOCOVERDIR"

// Register mounts the coverage control endpoints on mux. Both are POST-only so a
// stray crawler or a browser preview cannot silently reset a run's counters
// mid-surface.
func Register(mux *http.ServeMux, logger *applog.Logger) {
	base := os.Getenv(BasePathEnv)
	if base == "" {
		logger.Warn(context.Background(), "coverage build without "+BasePathEnv+"; snapshot endpoints will return 503")
	} else {
		logger.Info(context.Background(), "coverage snapshot endpoints enabled", applog.String("dir", base))
	}

	mux.HandleFunc("POST /internal/coverage/reset", func(w http.ResponseWriter, r *http.Request) {
		if err := coverage.ClearCounters(); err != nil {
			// ClearCounters only works under -covermode=atomic; a binary built
			// with the default mode would otherwise silently accumulate one
			// surface's counters into the next and report every surface as if it
			// had covered everything the surfaces before it did.
			writeErr(r.Context(), w, logger, http.StatusPreconditionFailed,
				fmt.Errorf("clear counters (build with -covermode=atomic): %w", err))
			return
		}
		writeJSON(r.Context(), w, logger, http.StatusOK, map[string]any{"reset": true})
	})

	mux.HandleFunc("POST /internal/coverage/snapshot", func(w http.ResponseWriter, r *http.Request) {
		if base == "" {
			writeErr(r.Context(), w, logger, http.StatusServiceUnavailable,
				fmt.Errorf("%s is not set; start the server with %s=<dir>", BasePathEnv, BasePathEnv))
			return
		}
		label, err := safeLabel(r.URL.Query().Get("label"))
		if err != nil {
			writeErr(r.Context(), w, logger, http.StatusBadRequest, err)
			return
		}

		dir := filepath.Join(base, label)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			writeErr(r.Context(), w, logger, http.StatusInternalServerError, fmt.Errorf("mkdir %s: %w", dir, err))
			return
		}
		// Meta-data is per-binary and identical for every snapshot, but `go tool
		// covdata` needs it alongside the counters in each input directory, so
		// write it into all of them. It is content-addressed (covmeta.<hash>), so
		// re-writing it into a directory that already has it is a no-op.
		if err := coverage.WriteMetaDir(dir); err != nil {
			writeErr(r.Context(), w, logger, http.StatusInternalServerError, fmt.Errorf("write meta %s: %w", dir, err))
			return
		}
		if err := coverage.WriteCountersDir(dir); err != nil {
			writeErr(r.Context(), w, logger, http.StatusInternalServerError, fmt.Errorf("write counters %s: %w", dir, err))
			return
		}
		logger.Info(r.Context(), "coverage snapshot written", applog.String("label", label), applog.String("dir", dir))
		writeJSON(r.Context(), w, logger, http.StatusOK, map[string]any{"label": label, "dir": dir})
	})
}

// safeLabel validates the caller-supplied snapshot name before it becomes a path
// element. The harness is trusted, but the label lands in a filesystem path, so
// anything outside [A-Za-z0-9._-] — separators and "." / ".." in particular — is
// rejected rather than cleaned, so a typo fails loudly instead of quietly
// writing a surface's counters somewhere unexpected.
func safeLabel(label string) (string, error) {
	if label == "" {
		return "", fmt.Errorf("label query parameter is required")
	}
	if len(label) > 128 {
		return "", fmt.Errorf("label is too long (max 128)")
	}
	if label == "." || label == ".." {
		return "", fmt.Errorf("label %q is not a usable directory name", label)
	}
	const allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
	for _, c := range label {
		if !strings.ContainsRune(allowed, c) {
			return "", fmt.Errorf("label %q contains %q; allowed characters are [A-Za-z0-9._-]", label, c)
		}
	}
	return label, nil
}

func writeJSON(ctx context.Context, w http.ResponseWriter, logger *applog.Logger, code int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		logger.Warn(ctx, "write coverage response", applog.Error(err))
	}
}

func writeErr(ctx context.Context, w http.ResponseWriter, logger *applog.Logger, code int, err error) {
	logger.Warn(ctx, "coverage endpoint", applog.Error(err))
	writeJSON(ctx, w, logger, code, map[string]any{"error": err.Error()})
}
