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

func TestConsoleSetPushStoresItForTheNextSession(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	for _, tc := range []struct{ line, want string }{
		{"set push 1m", "next session: push=1m0s (disconnect to apply)"},
		{"set push 0", "next session: push=0s (disconnect to apply)"},
		{"set push unset", "next session: push=unset (disconnect to apply)"},
		// a malformed line prints why and changes nothing
		{"set push", "usage: set push <duration>|unset"},
		{"set push abc", `set: time: invalid duration "abc"`},
		{"set pull 5s", "usage: set push <duration>|unset"}, // pull is the DP's declarations (AG-D28)
		{"set foo abc", "usage: set push <duration>|unset"}, // the verb is checked before the value
	} {
		var out bytes.Buffer
		s.Console(strings.NewReader(tc.line+"\n"), &out)
		if !strings.Contains(out.String(), tc.want) {
			t.Errorf("%q: want %q, got %q", tc.line, tc.want, out.String())
		}
	}
	if got := s.PushInterval(); got != (PushSpec{Unset: true}) {
		t.Fatalf("stored %+v, want unset", got)
	}
}

// With two bindings a line must say which one it is for: no silent default
// to the first, a binding the lease does not grant is refused, and "@" may
// sit anywhere on the line, including after apply's file.
func TestConsoleTargetsWithTwoBindings(t *testing.T) {
	sc := DefaultScenario()
	sc.Lease.BindingIDs = []string{"cont1", "cont2"}
	s, err := Start(Config{Listen: "127.0.0.1:0", Scenario: sc})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	file := filepath.Join(t.TempDir(), "application.yml")
	if err := os.WriteFile(file, []byte("a: 1\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	for _, tc := range []struct{ line, want string }{
		{"preflight", "which binding? add @cont1 | @cont2 | @all"},
		{"apply " + file, "which binding? add @cont1 | @cont2 | @all"},
		{"restart @nope", `binding "nope" is not in the lease (cont1, cont2)`},
		{"stop @cont1 @cont2", "one @target per command"},
		{"apply @cont1", "apply needs a file"},
		// these get as far as an offer, so they print "no agent connected"
		{"preflight @cont1", "no agent connected"},
		{"apply " + file + " @cont2", "no agent connected"},
		{"restart @all", "no agent connected"},
	} {
		var out bytes.Buffer
		s.Console(strings.NewReader(tc.line+"\n"), &out)
		if !strings.Contains(out.String(), tc.want) {
			t.Errorf("%q: want %q in output, got %q", tc.line, tc.want, out.String())
		}
	}
}

func TestConsoleOffersToTheTargetedBindings(t *testing.T) {
	s, _ := telemetryServer(t, func(sc *Scenario) { sc.Lease.BindingIDs = []string{"cont1", "cont2"} })
	agentConn(t, s, true)
	out := &lockedBuffer{}
	s.Console(strings.NewReader("preflight @cont2\nrestart @all\n"), out)
	for _, want := range []string{
		"preflight-cont2-1 gen=1: offered",
		"restart-cont1-1 gen=1: offered", // @all: one command per binding, in lease order
		"restart-cont2-2 gen=2: offered", // generations count per binding
	} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("want %q in output, got %q", want, out.String())
		}
	}
	if strings.Contains(out.String(), "preflight-cont1") {
		t.Errorf("@cont2 must not offer to cont1: %q", out.String())
	}
}
