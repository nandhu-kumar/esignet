// Command coverage turns the per-surface counter snapshots written by a
// coverage-instrumented esignet-service into out/coverage.json, which
// consolidate renders as the report's coverage panel.
//
// run-all.sh brackets each surface with a counter reset and a snapshot, leaving
// one directory per surface under -covdir named <plugin>__<surface>. This reads
// them back, converts each with `go tool covdata textfmt`, and reports both the
// per-surface percentages and their union.
//
// Usage:
//
//	coverage -covdir out/covdata -plugin mock -out out
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"github.com/mosip/esignet/api-test/internal/covreport"
)

// surfaceOrder fixes the column order of the report, matching the order
// run-all.sh executes them in. A surface not present in this list sorts last,
// alphabetically, so an added surface still renders rather than disappearing.
var surfaceOrder = []string{"conformance", "api", "e2e"}

// defaultExclude drops the coverage snapshot endpoints from the measurement.
// They exist only because coverage is being measured — they are compiled in by
// the `coverage` build tag and are absent from the binary this is meant to say
// something about, so counting them would pad the denominator with statements
// no test surface is supposed to reach.
const defaultExclude = "github.com/mosip/esignet/internal/covsnap"

func main() {
	covDir := flag.String("covdir", "out/covdata", "directory holding the per-surface snapshot directories")
	plugin := flag.String("plugin", "mock", "plugin whose snapshots to read (the <plugin>__<surface> prefix)")
	outDir := flag.String("out", "out", "directory to write coverage.json into")
	module := flag.String("module", "github.com/mosip/esignet", "module prefix trimmed from package names for display")
	exclude := flag.String("exclude", defaultExclude, "comma-separated packages to leave out of both numerator and denominator")
	flag.Parse()

	logger := log.New(os.Stderr, "", 0)

	dirs, err := snapshotDirs(*covDir, *plugin)
	if err != nil {
		logger.Printf("coverage: %v", err)
		os.Exit(1)
	}

	tmp, err := os.MkdirTemp("", "esignet-coverage-")
	if err != nil {
		logger.Printf("coverage: temp dir: %v", err)
		os.Exit(1)
	}
	defer os.RemoveAll(tmp)

	var sps []covreport.SurfaceProfile
	for _, d := range dirs {
		txt := filepath.Join(tmp, d.surface+".txt")
		if err := covdataTextfmt(d.path, txt); err != nil {
			logger.Printf("coverage: %v", err)
			os.Exit(1)
		}
		p, err := covreport.ParseProfile(txt)
		if err != nil {
			logger.Printf("coverage: surface %s: %v", d.surface, err)
			os.Exit(1)
		}
		sps = append(sps, covreport.SurfaceProfile{Surface: d.surface, Profile: p})
	}

	rep := covreport.Build(covreport.Options{
		Plugin:          *plugin,
		Module:          *module,
		ExcludePackages: splitList(*exclude),
	}, sps)
	rep.Generated = time.Now().Format("2006-01-02 15:04:05 MST")

	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		logger.Printf("coverage: mkdir %s: %v", *outDir, err)
		os.Exit(1)
	}
	path := filepath.Join(*outDir, "coverage.json")
	data, err := json.MarshalIndent(rep, "", "  ")
	if err != nil {
		logger.Printf("coverage: marshal: %v", err)
		os.Exit(1)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		logger.Printf("coverage: write %s: %v", path, err)
		os.Exit(1)
	}

	// Console summary, so a coverage run is readable without opening the report.
	fmt.Printf("\n== Coverage of esignet-service — %s ==\n", rep.Plugin)
	width := len("overall")
	for _, s := range rep.Surfaces {
		width = max(width, len(s))
	}
	for i, s := range rep.Surfaces {
		fmt.Printf("  %-*s %6.1f%%  (%d/%d statements)\n", width, s, rep.Totals[i].Percent, rep.Totals[i].Covered, rep.Totals[i].Total)
	}
	fmt.Printf("  %-*s %6.1f%%  (%d/%d statements)  — union of the above\n",
		width, "overall", rep.Overall.Percent, rep.Overall.Covered, rep.Overall.Total)
	fmt.Printf("coverage: %s\n", path)
}

// splitList parses a comma-separated flag, dropping blanks so a trailing comma
// or an empty -exclude does not become a package named "".
func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// snapshot is one surface's snapshot directory.
type snapshot struct {
	surface string
	path    string
}

// snapshotDirs finds this plugin's snapshot directories and orders them the way
// the surfaces run. Finding none is an error: the caller enabled coverage, so an
// empty result means the reset/snapshot calls never reached the server, and
// reporting that as "nothing covered" would be a lie about a measurement that
// did not happen.
func snapshotDirs(covDir, plugin string) ([]snapshot, error) {
	entries, err := os.ReadDir(covDir)
	if err != nil {
		return nil, fmt.Errorf("read %s: %w (was the server started with GOCOVERDIR=%s?)", covDir, err, covDir)
	}
	prefix := plugin + "__"
	var out []snapshot
	for _, e := range entries {
		if !e.IsDir() || !strings.HasPrefix(e.Name(), prefix) {
			continue
		}
		out = append(out, snapshot{
			surface: strings.TrimPrefix(e.Name(), prefix),
			path:    filepath.Join(covDir, e.Name()),
		})
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no %s* snapshot directories in %s — no surface snapshotted its counters", prefix, covDir)
	}
	slices.SortFunc(out, func(a, b snapshot) int {
		ia, ib := slices.Index(surfaceOrder, a.surface), slices.Index(surfaceOrder, b.surface)
		switch {
		case ia >= 0 && ib >= 0:
			return ia - ib
		case ia >= 0:
			return -1 // known surfaces before unknown
		case ib >= 0:
			return 1
		default:
			return strings.Compare(a.surface, b.surface)
		}
	})
	return out, nil
}

// covdataTextfmt converts a snapshot directory to a text coverage profile.
//
// This shells out to the Go toolchain because the binary counter format is
// internal and has no public reader. It is not a new dependency in practice: a
// coverage run already needed `go build -cover` to produce the server.
func covdataTextfmt(in, out string) error {
	cmd := exec.Command("go", "tool", "covdata", "textfmt", "-i="+in, "-o="+out)
	stderr, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("go tool covdata textfmt -i=%s: %w\n%s", in, err, stderr)
	}
	return nil
}
