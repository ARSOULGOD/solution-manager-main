package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// The enrolment token is a secret (plan 015 Global constraint 9). flag prints a flag's default value in its usage text,
// so a default taken from the environment leaked the token on any flag-parse error and on -h. Black-box: build the
// real binary and read what it prints.
func TestEnrollTokenNeverAppearsInUsageOutput(t *testing.T) {
	const secret = "tok-SECRET-0123456789"
	bin := filepath.Join(t.TempDir(), "sm-agent")
	if out, err := exec.Command("go", "build", "-o", bin, ".").CombinedOutput(); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}
	for _, args := range [][]string{{"--no-such-flag"}, {"-h"}, {"--log-level"}} {
		cmd := exec.Command(bin, args...)
		cmd.Env = append(os.Environ(), "SM_AGENT_ENROLL_TOKEN="+secret)
		out, _ := cmd.CombinedOutput()
		if strings.Contains(string(out), secret) {
			t.Fatalf("args %v: output contains the enrolment token:\n%s", args, out)
		}
		if !strings.Contains(string(out), "enrollment-token") {
			t.Fatalf("args %v: usage text missing, test is not exercising the flag output:\n%s", args, out)
		}
	}
}

func TestEnrollTokenFromEnvWhenFlagEmpty(t *testing.T) {
	get := func(k string) string { return map[string]string{"SM_AGENT_ENROLL_TOKEN": "from-env"}[k] }
	if got := resolveToken("", get); got != "from-env" {
		t.Fatalf("empty flag must fall back to env, got %q", got)
	}
	if got := resolveToken("from-flag", get); got != "from-flag" {
		t.Fatalf("flag must win, got %q", got)
	}
}
