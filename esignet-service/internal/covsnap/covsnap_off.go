/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

//go:build !coverage

// Package covsnap exposes runtime coverage-counter control to the api-test
// harness. This is the production build: Register is a no-op, so a shipped
// binary carries no coverage endpoints at all. See covsnap_on.go for the
// instrumented implementation and the rationale.
package covsnap

import (
	"net/http"

	applog "github.com/mosip/esignet/internal/log"
)

// Register does nothing in a production build.
func Register(_ *http.ServeMux, _ *applog.Logger) {}
