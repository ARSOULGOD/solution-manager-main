//go:build docker

package docker

import (
	"context"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// TestE2EScrapeAddrReachesAPublishedPort runs busybox httpd with a published
// port and the binding label, then fetches through ScrapeAddr. The config dir
// is under $HOME because Docker Desktop shares only that (CLAUDE.md).
func TestE2EScrapeAddrReachesAPublishedPort(t *testing.T) {
	conf, err := os.MkdirTemp(os.Getenv("HOME"), "sm-agent-e2e-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(conf) })
	name := "sm-agent-telemetry-e2e"
	_ = exec.Command("docker", "rm", "-f", name).Run()
	cmd := exec.Command("docker", "run", "-d", "--name", name,
		"--label", BindingLabel+"=e2e-t1", "-e", "SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/",
		"-v", conf+":/etc/app", "-p", "127.0.0.1::8080", "busybox:1.36",
		"sh", "-c", `mkdir -p /www && echo '{"hitRate":0.5}' > /www/stats.json && httpd -f -p 8080 -h /www`)
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
	p, err := a.Resolve(ctx, "e2e-t1")
	if err != nil {
		t.Fatal(err)
	}
	addr, err := a.ScrapeAddr(ctx, p, 8080)
	if err != nil || !strings.HasPrefix(addr, "127.0.0.1:") {
		t.Fatalf("addr %q %v", addr, err)
	}
	get := func() string {
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			if resp, err := http.Get("http://" + addr + "/stats.json"); err == nil {
				b, _ := io.ReadAll(resp.Body)
				resp.Body.Close()
				if resp.StatusCode == 200 {
					return string(b)
				}
			}
			time.Sleep(100 * time.Millisecond)
		}
		return ""
	}
	if body := get(); !strings.Contains(body, `"hitRate":0.5`) {
		t.Fatalf("body %q", body)
	}
	if out, err := exec.Command("docker", "exec", name, "sh", "-c", `echo '{"hitRate":0.75}' > /www/stats.json`).CombinedOutput(); err != nil {
		t.Fatalf("%v: %s", err, out)
	}
	if body := get(); !strings.Contains(body, `"hitRate":0.75`) {
		t.Fatalf("edited value not served: %q", body)
	}
}
