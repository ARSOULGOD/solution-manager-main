package fakegw

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestConsoleRejectsLinesItCannotOffer covers every line the console must
// answer without offering anything — here no agent is connected, so a line
// that got as far as an offer prints "no agent connected" instead. The case
// that matters most is "apply" with no file: offering it would write an empty
// application.yml over the managed config.
func TestConsoleRejectsLinesItCannotOffer(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	file := filepath.Join(t.TempDir(), "application.yml")
	if err := os.WriteFile(file, []byte("a: 1\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	for _, tc := range []struct{ line, want string }{
		{"bogus", `unknown command "bogus"`},
		{"apply", "apply needs a file"},
		{"apply /nonexistent/application.yml", "no such file"},
		{"restart", "no agent connected"},
		{"  stop  ", "no agent connected"},
		{"apply " + file, "no agent connected"},
	} {
		var out bytes.Buffer
		s.Console(strings.NewReader(tc.line+"\n"), &out)
		if !strings.Contains(out.String(), tc.want) {
			t.Errorf("%q: want %q in output, got %q", tc.line, tc.want, out.String())
		}
	}
}

func TestConsoleIgnoresBlankLines(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	var banner, out bytes.Buffer
	s.Console(strings.NewReader(""), &banner)
	s.Console(strings.NewReader("\n   \n\t\n"), &out)
	if out.String() != banner.String() {
		t.Fatalf("blank lines must print nothing beyond the banner: %q", out.String())
	}
}
