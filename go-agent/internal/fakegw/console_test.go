package fakegw

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
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

func TestConsoleTelemetryVerbsWithoutAnAgent(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	for line, want := range map[string]string{
		"pause":      "telemetry paused: acks grant no credits",
		"resume":     "telemetry resumed: 4 credits granted",
		"disconnect": "no agent connected",
	} {
		var out bytes.Buffer
		s.Console(strings.NewReader(line+"\n"), &out)
		if !strings.Contains(out.String(), want) {
			t.Errorf("%q: want %q, got %q", line, want, out.String())
		}
	}
}

func TestConsoleSetStoresIntervalsForTheNextSession(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	for _, tc := range []struct{ line, want string }{
		{"set pull 5s", "next session: pull=5s push=0s (disconnect to apply)"},
		{"set push 1m", "next session: pull=5s push=1m0s (disconnect to apply)"},
		{"set push 0", "next session: pull=5s push=0s (disconnect to apply)"},
		// Review Focus 5: a malformed line prints why and changes nothing
		{"set push", "usage: set pull|push <duration>"},
		{"set push abc", `set: time: invalid duration "abc"`},
		{"set foo 1s", "usage: set pull|push <duration>"},
	} {
		var out bytes.Buffer
		s.Console(strings.NewReader(tc.line+"\n"), &out)
		if !strings.Contains(out.String(), tc.want) {
			t.Errorf("%q: want %q, got %q", tc.line, tc.want, out.String())
		}
	}
	if pull, push := s.TelemetryIntervals(); pull != 5*time.Second || push != 0 {
		t.Fatalf("stored pull %v push %v", pull, push)
	}
}
