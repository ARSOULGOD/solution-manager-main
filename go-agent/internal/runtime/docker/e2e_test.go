//go:build docker

package docker

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
)

// TestE2EAgainstRealContainer needs a Docker socket. It creates a container
// labelled for binding "e2e-b1" with a bind-mounted config dir, then exercises
// Resolve → Preflight → WriteConfig → Restart → Observe.
func TestE2EAgainstRealContainer(t *testing.T) {
	hostDir := t.TempDir()
	if err := os.Chmod(hostDir, 0o777); err != nil {
		t.Fatal(err)
	}
	name := "sm-agent-e2e"
	_ = exec.Command("docker", "rm", "-f", name).Run()
	cmd := exec.Command("docker", "run", "-d", "--name", name,
		"--label", BindingLabel+"=e2e-b1",
		"-v", hostDir+":/etc/app",
		"alpine:3", "sh", "-c", "--spring.config.additional-location=file:/etc/app/ ; while true; do sleep 1; done")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("docker unavailable: %v: %s", err, out)
	}
	t.Cleanup(func() { _ = exec.Command("docker", "rm", "-f", name).Run() })

	a, closeFn, err := NewFromEnv()
	if err != nil {
		t.Skipf("docker client: %v", err)
	}
	defer closeFn()
	ctx := context.Background()
	p, err := a.Resolve(ctx, "e2e-b1")
	if err != nil {
		t.Fatal(err)
	}
	if p.HostPath != filepath.Join(hostDir, configFileName) {
		t.Fatalf("%+v", p)
	}
	if _, err := a.Preflight(ctx, p); err != nil {
		t.Fatal(err)
	}
	payload := []byte("server.port: 8081\n")
	if _, err := a.WriteConfig(ctx, p, payload, shaOf(payload), func(msg.ProgressStep) error { return nil }); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(p.HostPath)
	if err != nil || st.Mode().Perm() != 0o600 {
		t.Fatalf("%v %v", err, st)
	}
	before, _ := a.Observe(ctx, p)
	if err := a.Restart(ctx, p); err != nil {
		t.Fatal(err)
	}
	time.Sleep(time.Second)
	after, _ := a.Observe(ctx, p)
	if !after.Running || !after.StartedAt.After(before.StartedAt) {
		t.Fatalf("restart not observed: %v → %v", before.StartedAt, after.StartedAt)
	}
}

func shaOf(b []byte) string { return configwriter.SHA256(b) }
