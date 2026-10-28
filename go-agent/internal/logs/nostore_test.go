package logs

// The agent keeps no copy of an MI's logs (agent-mi-logs-from-actuator design
// §4.6): a page or an export is read from the MI's /actuator/logfile for one
// request and nothing is stored. This guard makes that a property of the
// imports rather than of whoever last read the code — logs code that cannot
// import os, io/ioutil or the agent's store cannot write a file.

import (
	"go/parser"
	"go/token"
	"io/fs"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"
)

// forbiddenImports returns the imports in src that would let logs code write
// to disk: os, io/ioutil, or the agent's store package.
func forbiddenImports(filename string, src any) ([]string, error) {
	f, err := parser.ParseFile(token.NewFileSet(), filename, src, parser.ImportsOnly)
	if err != nil {
		return nil, err
	}
	var bad []string
	for _, im := range f.Imports {
		p, err := strconv.Unquote(im.Path.Value)
		if err != nil {
			return nil, err
		}
		if p == "os" || p == "io/ioutil" || strings.HasSuffix(p, "/internal/store") {
			bad = append(bad, p)
		}
	}
	return bad, nil
}

func TestLogsCodeCannotWriteToDisk(t *testing.T) {
	var files []string
	err := filepath.WalkDir(".", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() && strings.HasSuffix(path, ".go") && !strings.HasSuffix(path, "_test.go") {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	files = append(files, filepath.Join("..", "agent", "logs_role.go"))
	// A walk that found nothing would pass for the wrong reason.
	if !slices.Contains(files, filepath.Join("serve", "serve.go")) || !slices.Contains(files, filepath.Join("logfile", "logfile.go")) {
		t.Fatalf("the walk did not reach the logs packages: %v", files)
	}
	for _, path := range files {
		bad, err := forbiddenImports(path, nil)
		if err != nil {
			t.Fatalf("%s: %v", path, err)
		}
		if len(bad) > 0 {
			t.Errorf("%s imports %v: logs code must not be able to write to disk", path, bad)
		}
	}
}

// The checker is the guard (L7): it must report each way in, and only those.
func TestNoStoreCheckerReportsPlantedViolations(t *testing.T) {
	for name, tc := range map[string]struct {
		src  string
		want []string
	}{
		"os":         {"package x\nimport \"os\"\n", []string{"os"}},
		"ioutil":     {"package x\nimport \"io/ioutil\"\n", []string{"io/ioutil"}},
		"store":      {"package x\nimport s \"github.com/solace/solution-manager/go-agent/internal/store\"\n", []string{"github.com/solace/solution-manager/go-agent/internal/store"}},
		"grouped":    {"package x\nimport (\n\t\"io\"\n\t\"os\"\n)\n", []string{"os"}},
		"clean":      {"package x\nimport (\n\t\"io\"\n\t\"net/http\"\n\t\"os/signal\"\n)\n", nil},
		"look-alike": {"package x\nimport \"github.com/solace/solution-manager/go-agent/internal/storefront\"\n", nil},
	} {
		t.Run(name, func(t *testing.T) {
			got, err := forbiddenImports("planted.go", tc.src)
			if err != nil {
				t.Fatal(err)
			}
			if !slices.Equal(got, tc.want) {
				t.Fatalf("got %v, want %v", got, tc.want)
			}
		})
	}
}
