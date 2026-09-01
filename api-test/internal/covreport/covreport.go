// Package covreport turns the per-surface counter snapshots taken from a
// coverage-instrumented esignet-service into the percentages the consolidated
// report shows.
//
// The point of keeping the surfaces apart is attribution. A single number for a
// whole run ("80%") says nothing about which surface earned it; conformance,
// api and e2e exercise very different parts of the service, and a per-surface
// column shows that directly — as well as where two surfaces are covering the
// same ground and one of them is not paying for itself.
//
// Scope is deliberately esignet-service's own packages (the instrumented binary
// is built with -coverpkg=./...). The OIDC engine lives in the thunderid module
// and is not this repo's code to be measured against, so it is not in the
// denominator.
package covreport

import (
	"bufio"
	"fmt"
	"os"
	"path"
	"sort"
	"strconv"
	"strings"
)

// Stat is one coverage measurement: statements covered out of statements
// instrumented. Percent is carried rather than recomputed by every consumer so
// the report and the console summary can never disagree.
type Stat struct {
	Covered int     `json:"covered"`
	Total   int     `json:"total"`
	Percent float64 `json:"percent"`
}

// newStat computes the percentage. A package with no statements at all reports
// 0% rather than dividing by zero; it also cannot appear in practice, since a
// package with no statements produces no profile lines to group.
func newStat(covered, total int) Stat {
	s := Stat{Covered: covered, Total: total}
	if total > 0 {
		s.Percent = float64(covered) * 100 / float64(total)
	}
	return s
}

// Package is one Go package's coverage, split by surface. BySurface is parallel
// to Report.Surfaces — a slice rather than a map so the report renders the
// columns in a stable order without the template having to sort anything.
type Package struct {
	Name      string `json:"name"`
	BySurface []Stat `json:"by_surface"`
	Overall   Stat   `json:"overall"`
}

// Report is the whole coverage picture for one plugin's run, written to
// out/coverage.json and read back by consolidate.
type Report struct {
	Plugin    string `json:"plugin"`
	Generated string `json:"generated"`
	// Module is the import prefix trimmed from package names for display.
	Module string `json:"module"`
	// Surfaces are the surfaces that produced a snapshot, in run order. A
	// surface that was not selected for this run simply does not appear.
	Surfaces []string `json:"surfaces"`
	// Totals is parallel to Surfaces: the whole-service number for each.
	Totals []Stat `json:"totals"`
	// Overall is the union across every surface — what the service got out of
	// running all of them together, which is strictly less than their sum
	// wherever two surfaces cover the same statement.
	Overall  Stat      `json:"overall"`
	Packages []Package `json:"packages"`
}

// block identifies one coverable region of source: a statement range within a
// file. The instrumented binary is the same for every snapshot, so the block set
// is identical across surfaces and can be unioned by key.
type block struct {
	file string
	span string // "12.34,56.78" — start/end line.col, opaque here
}

// Profile is one parsed textfmt coverage profile: how many statements each block
// holds, and whether it was executed.
type Profile struct {
	numStmt map[block]int
	covered map[block]bool
}

// ParseProfile reads a `go tool covdata textfmt` profile.
//
// A profile with no data lines is an error, not 0%. `covdata textfmt` exits 0
// and writes an empty file when its input directory holds no counter files, so
// a snapshot that never happened would otherwise be reported as "this surface
// covered nothing" — indistinguishable from a surface that genuinely ran and
// covered nothing.
func ParseProfile(path string) (*Profile, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	p := &Profile{numStmt: map[block]int{}, covered: map[block]bool{}}
	sc := bufio.NewScanner(f)
	// Coverage profiles are one short line per block, but a very long generated
	// file could exceed bufio's 64KiB default; raise the ceiling so a parse
	// failure cannot be mistaken for missing coverage.
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	line := 0
	for sc.Scan() {
		line++
		text := strings.TrimSpace(sc.Text())
		if text == "" || strings.HasPrefix(text, "mode:") {
			continue
		}
		b, numStmt, count, err := parseLine(text)
		if err != nil {
			return nil, fmt.Errorf("%s:%d: %w", path, line, err)
		}
		p.numStmt[b] = numStmt
		if count > 0 {
			p.covered[b] = true
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	if len(p.numStmt) == 0 {
		return nil, fmt.Errorf("%s holds no coverage data (the snapshot directory had no counter files)", path)
	}
	return p, nil
}

// parseLine parses one profile line: "<file>:<span> <numStmt> <count>".
func parseLine(text string) (b block, numStmt, count int, err error) {
	fields := strings.Fields(text)
	if len(fields) != 3 {
		return b, 0, 0, fmt.Errorf("want 3 fields, got %d in %q", len(fields), text)
	}
	// Split on the LAST colon: an import path cannot contain one, but a Windows
	// drive letter could reach here if a profile was produced without -trimpath.
	i := strings.LastIndex(fields[0], ":")
	if i < 0 {
		return b, 0, 0, fmt.Errorf("no file:span separator in %q", fields[0])
	}
	b = block{file: fields[0][:i], span: fields[0][i+1:]}
	if numStmt, err = strconv.Atoi(fields[1]); err != nil {
		return b, 0, 0, fmt.Errorf("numStmt %q: %w", fields[1], err)
	}
	if count, err = strconv.Atoi(fields[2]); err != nil {
		return b, 0, 0, fmt.Errorf("count %q: %w", fields[2], err)
	}
	return b, numStmt, count, nil
}

// SurfaceProfile pairs a surface name with its parsed profile.
type SurfaceProfile struct {
	Surface string
	Profile *Profile
}

// Options configures Build.
type Options struct {
	Plugin string
	// Module is the import prefix trimmed from package names for display.
	Module string
	// ExcludePackages drops packages from both the numerator and the
	// denominator — used for code that only exists because coverage is being
	// measured (the snapshot endpoints), which would otherwise pad the
	// denominator with statements no test surface is meant to reach.
	ExcludePackages []string
}

// Build assembles the report from one profile per surface.
//
// The overall column is the UNION of the surfaces' covered blocks, not the sum:
// a statement both conformance and e2e execute is covered once. That makes
// Overall exactly what a single run of all surfaces together would have
// measured, which is why the surfaces can be snapshotted separately without
// giving up the combined number.
func Build(opts Options, sps []SurfaceProfile) Report {
	rep := Report{Plugin: opts.Plugin, Module: opts.Module}

	pkgOf := func(b block) string { return path.Dir(b.file) }
	excluded := make(map[string]bool, len(opts.ExcludePackages))
	for _, p := range opts.ExcludePackages {
		excluded[p] = true
	}
	skip := func(b block) bool { return excluded[pkgOf(b)] }

	// Statement counts come from the block set, which is identical across
	// snapshots of one binary. Union them anyway so a surface whose profile is
	// somehow missing a package still leaves that package in the denominator.
	total := map[block]int{}
	unionCovered := map[block]bool{}
	for _, sp := range sps {
		rep.Surfaces = append(rep.Surfaces, sp.Surface)
		for b, n := range sp.Profile.numStmt {
			if !skip(b) {
				total[b] = n
			}
		}
		for b := range sp.Profile.covered {
			if !skip(b) {
				unionCovered[b] = true
			}
		}
	}

	// Per-package denominators, and the set of packages to report.
	pkgTotal := map[string]int{}
	for b, n := range total {
		pkgTotal[pkgOf(b)] += n
	}
	names := make([]string, 0, len(pkgTotal))
	for name := range pkgTotal {
		names = append(names, name)
	}
	sort.Strings(names)

	// Per-surface numerators, per package and overall.
	pkgCovered := make([]map[string]int, len(sps))
	for i, sp := range sps {
		pkgCovered[i] = map[string]int{}
		surfaceCovered := 0
		for b := range sp.Profile.covered {
			n := total[b]
			pkgCovered[i][pkgOf(b)] += n
			surfaceCovered += n
		}
		rep.Totals = append(rep.Totals, newStat(surfaceCovered, sumInts(pkgTotal)))
	}

	overallCovered := 0
	pkgOverall := map[string]int{}
	for b := range unionCovered {
		n := total[b]
		pkgOverall[pkgOf(b)] += n
		overallCovered += n
	}
	rep.Overall = newStat(overallCovered, sumInts(pkgTotal))

	for _, name := range names {
		p := Package{Name: name, Overall: newStat(pkgOverall[name], pkgTotal[name])}
		for i := range sps {
			p.BySurface = append(p.BySurface, newStat(pkgCovered[i][name], pkgTotal[name]))
		}
		rep.Packages = append(rep.Packages, p)
	}
	return rep
}

func sumInts(m map[string]int) int {
	n := 0
	for _, v := range m {
		n += v
	}
	return n
}

// ShortName trims the module prefix from a package import path for display, so
// the report reads "internal/clientmgmt" rather than repeating
// "github.com/mosip/esignet/" on every row. The module package itself renders
// as ".".
func (r Report) ShortName(pkg string) string {
	if r.Module == "" {
		return pkg
	}
	if pkg == r.Module {
		return "."
	}
	return strings.TrimPrefix(pkg, r.Module+"/")
}
