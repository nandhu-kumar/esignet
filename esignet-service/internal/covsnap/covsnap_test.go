/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

//go:build coverage

package covsnap

import "testing"

// The label becomes a directory name under GOCOVERDIR, so anything that could
// walk out of it — a separator, "..", a drive letter — must be rejected rather
// than sanitised. Rejecting loudly means a typo fails the run instead of
// quietly writing one surface's counters somewhere the collector will not look.
func TestSafeLabelRejectsPathEscapes(t *testing.T) {
	for _, label := range []string{
		"", "..", ".", "../etc", "a/b", `a\b`, "a:b", "mock__e2e/../..", "a b", "a\x00b",
	} {
		if _, err := safeLabel(label); err == nil {
			t.Errorf("safeLabel(%q) = nil error, want rejection", label)
		}
	}
}

func TestSafeLabelAcceptsSurfaceLabels(t *testing.T) {
	for _, label := range []string{"mock__conformance", "sunbird__api", "mosip__e2e", "a.b-c_1"} {
		got, err := safeLabel(label)
		if err != nil {
			t.Errorf("safeLabel(%q): %v", label, err)
			continue
		}
		if got != label {
			t.Errorf("safeLabel(%q) = %q, want it returned unchanged", label, got)
		}
	}
}

func TestSafeLabelRejectsOverlongLabel(t *testing.T) {
	long := make([]byte, 129)
	for i := range long {
		long[i] = 'a'
	}
	if _, err := safeLabel(string(long)); err == nil {
		t.Error("want a 129-character label to be rejected")
	}
}
