package covreport

import (
	"os"
	"path/filepath"
	"testing"
)

// writeProfile writes a textfmt-shaped profile and returns its path.
func writeProfile(t *testing.T, body string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "profile.txt")
	if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
		t.Fatalf("write profile: %v", err)
	}
	return p
}

func mustParse(t *testing.T, body string) *Profile {
	t.Helper()
	p, err := ParseProfile(writeProfile(t, body))
	if err != nil {
		t.Fatalf("ParseProfile: %v", err)
	}
	return p
}

func TestParseProfile(t *testing.T) {
	p := mustParse(t, `mode: atomic
github.com/mosip/esignet/internal/config/config.go:10.1,12.2 3 1
github.com/mosip/esignet/internal/config/config.go:14.1,15.2 2 0
`)
	if got := len(p.numStmt); got != 2 {
		t.Fatalf("blocks = %d, want 2", got)
	}
	if len(p.covered) != 1 {
		t.Fatalf("covered blocks = %d, want 1", len(p.covered))
	}
}

// An empty profile must be an error, not 0%. `covdata textfmt` exits 0 and
// writes an empty file when the snapshot directory holds no counters, so
// treating that as "covered nothing" would report a measurement that never
// happened as a real result.
func TestParseProfileEmptyIsError(t *testing.T) {
	if _, err := ParseProfile(writeProfile(t, "mode: atomic\n")); err == nil {
		t.Fatal("want an error for a profile with no data lines, got nil")
	}
}

func TestParseProfileMalformed(t *testing.T) {
	for name, body := range map[string]string{
		"missing count":  "mode: atomic\nfoo/bar.go:1.1,2.2 3\n",
		"bad numStmt":    "mode: atomic\nfoo/bar.go:1.1,2.2 x 1\n",
		"no span marker": "mode: atomic\nfoobar 3 1\n",
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := ParseProfile(writeProfile(t, body)); err == nil {
				t.Fatalf("want an error for %s, got nil", name)
			}
		})
	}
}

// Build's combined column must be the UNION of the surfaces, not their sum: a
// statement two surfaces both execute is covered once. Getting this wrong is the
// bug that would make the headline number exceed 100%.
func TestBuildCombinedIsUnionNotSum(t *testing.T) {
	// Four blocks of one statement each, all in one package.
	// a: conformance only, b: both, c: e2e only, d: neither.
	conf := mustParse(t, `mode: atomic
m/pkg/f.go:1.1,1.2 1 1
m/pkg/f.go:2.1,2.2 1 1
m/pkg/f.go:3.1,3.2 1 0
m/pkg/f.go:4.1,4.2 1 0
`)
	e2e := mustParse(t, `mode: atomic
m/pkg/f.go:1.1,1.2 1 0
m/pkg/f.go:2.1,2.2 1 1
m/pkg/f.go:3.1,3.2 1 1
m/pkg/f.go:4.1,4.2 1 0
`)

	rep := Build(Options{Plugin: "mock", Module: "m"}, []SurfaceProfile{
		{Surface: "conformance", Profile: conf},
		{Surface: "e2e", Profile: e2e},
	})

	if got, want := rep.Totals[0].Covered, 2; got != want {
		t.Errorf("conformance covered = %d, want %d", got, want)
	}
	if got, want := rep.Totals[1].Covered, 2; got != want {
		t.Errorf("e2e covered = %d, want %d", got, want)
	}
	// Union of {a,b} and {b,c} is {a,b,c} = 3, NOT 2+2=4.
	if got, want := rep.Overall.Covered, 3; got != want {
		t.Errorf("overall covered = %d, want %d (union, not sum)", got, want)
	}
	if got, want := rep.Overall.Total, 4; got != want {
		t.Errorf("overall total = %d, want %d", got, want)
	}
	if got, want := rep.Overall.Percent, 75.0; got != want {
		t.Errorf("overall percent = %v, want %v", got, want)
	}
	// The union can never exceed the total, whatever the surfaces overlap.
	if rep.Overall.Covered > rep.Overall.Total {
		t.Errorf("overall covered %d exceeds total %d", rep.Overall.Covered, rep.Overall.Total)
	}
}

// Every surface's denominator must be the whole service, not just the packages
// that surface happened to touch — otherwise a surface that only ever enters one
// package would report ~100% and read as excellent coverage.
func TestBuildDenominatorIsWholeService(t *testing.T) {
	narrow := mustParse(t, `mode: atomic
m/a/f.go:1.1,1.2 1 1
m/b/f.go:1.1,1.2 1 0
m/b/f.go:2.1,2.2 1 0
m/b/f.go:3.1,3.2 1 0
`)
	rep := Build(Options{Plugin: "mock", Module: "m"}, []SurfaceProfile{{Surface: "api", Profile: narrow}})

	if got, want := rep.Totals[0].Total, 4; got != want {
		t.Errorf("api denominator = %d, want %d (all packages)", got, want)
	}
	if got, want := rep.Totals[0].Percent, 25.0; got != want {
		t.Errorf("api percent = %v, want %v", got, want)
	}

	// Per-package rows still show the package-local view: 100% of a, 0% of b.
	if len(rep.Packages) != 2 {
		t.Fatalf("packages = %d, want 2", len(rep.Packages))
	}
	if got, want := rep.Packages[0].Name, "m/a"; got != want {
		t.Fatalf("packages[0] = %q, want %q (sorted)", got, want)
	}
	if got, want := rep.Packages[0].BySurface[0].Percent, 100.0; got != want {
		t.Errorf("m/a api = %v, want %v", got, want)
	}
	if got, want := rep.Packages[1].BySurface[0].Percent, 0.0; got != want {
		t.Errorf("m/b api = %v, want %v", got, want)
	}
}

func TestBuildSurfaceOrderIsPreserved(t *testing.T) {
	p := mustParse(t, "mode: atomic\nm/a/f.go:1.1,1.2 1 1\n")
	rep := Build(Options{Plugin: "mock", Module: "m"}, []SurfaceProfile{
		{Surface: "conformance", Profile: p},
		{Surface: "api", Profile: p},
		{Surface: "e2e", Profile: p},
	})
	want := []string{"conformance", "api", "e2e"}
	for i, s := range want {
		if rep.Surfaces[i] != s {
			t.Fatalf("Surfaces = %v, want %v", rep.Surfaces, want)
		}
	}
	if len(rep.Totals) != len(rep.Surfaces) {
		t.Fatalf("Totals has %d entries for %d surfaces — they must stay parallel", len(rep.Totals), len(rep.Surfaces))
	}
	for _, pkg := range rep.Packages {
		if len(pkg.BySurface) != len(rep.Surfaces) {
			t.Fatalf("package %s has %d cells for %d surfaces", pkg.Name, len(pkg.BySurface), len(rep.Surfaces))
		}
	}
}

func TestShortName(t *testing.T) {
	r := Report{Module: "github.com/mosip/esignet"}
	for in, want := range map[string]string{
		"github.com/mosip/esignet/internal/clientmgmt": "internal/clientmgmt",
		"github.com/mosip/esignet":                     ".",
		"other.com/pkg":                                "other.com/pkg",
	} {
		if got := r.ShortName(in); got != want {
			t.Errorf("ShortName(%q) = %q, want %q", in, got, want)
		}
	}
}

// The coverage snapshot endpoints only exist because coverage is being
// measured, so they must leave the denominator entirely — counting them would
// charge the test surfaces for code that is not in the binary they are meant to
// say something about.
func TestBuildExcludesPackages(t *testing.T) {
	p := mustParse(t, `mode: atomic
m/a/f.go:1.1,1.2 1 1
m/a/f.go:2.1,2.2 1 0
m/covsnap/f.go:1.1,1.2 1 1
m/covsnap/f.go:2.1,2.2 1 1
`)
	rep := Build(Options{Plugin: "mock", Module: "m", ExcludePackages: []string{"m/covsnap"}},
		[]SurfaceProfile{{Surface: "e2e", Profile: p}})

	if got, want := rep.Overall.Total, 2; got != want {
		t.Errorf("denominator = %d, want %d (excluded package must not count)", got, want)
	}
	if got, want := rep.Overall.Covered, 1; got != want {
		t.Errorf("numerator = %d, want %d", got, want)
	}
	for _, pkg := range rep.Packages {
		if pkg.Name == "m/covsnap" {
			t.Error("excluded package still has a row")
		}
	}
}
