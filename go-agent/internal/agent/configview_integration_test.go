//go:build integration

package agent

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

var cvAllow = []string{"server.port", "management.*"}

const cvDoc = "server:\n  port: 8080\nmanagement:\n  server:\n    port: 9090\ndatasource:\n  password: hunter2-secret\n"

// cvAgent starts an agent on the default scenario (lease: binding-1) whose
// config file is the adapter's target, holding doc (nil: no file).
func cvAgent(t *testing.T, doc []byte, mutate func(*BuildConfig, *fakeAdapter)) (*fakegw.Server, *fakeAdapter) {
	t.Helper()
	gw := startGateway(t, fakegw.DefaultScenario())
	ad := newFakeAdapter(t)
	if doc != nil {
		if err := os.WriteFile(ad.target(), doc, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	h := startAgent(t, gw, "", func(bc *BuildConfig) {
		bc.Adapter = ad
		if mutate != nil {
			mutate(bc, ad)
		}
	})
	waitPhase(t, h.loop, PhaseActive, 15*time.Second)
	return gw, ad
}

func cvAsk(t *testing.T, gw *fakegw.Server, req msg.ConfigViewRequest) msg.ConfigViewReply {
	t.Helper()
	before := len(gw.Received("ConfigViewReply"))
	if err := gw.RequestConfigView(req); err != nil {
		t.Fatal(err)
	}
	if !gw.WaitFor("ConfigViewReply", before+1, 10*time.Second) {
		t.Fatal("no ConfigViewReply")
	}
	rs := gw.Received("ConfigViewReply")
	if len(rs) != before+1 {
		t.Fatalf("%d replies for one request", len(rs)-before)
	}
	r := rs[len(rs)-1].(msg.ConfigViewReply)
	if r.RequestID != req.RequestID {
		t.Fatalf("reply id %q, want %q", r.RequestID, req.RequestID)
	}
	if r.Status == msg.ConfigViewRefused && (len(r.Entries) != 0 || r.Reason == "") {
		t.Fatalf("malformed refusal %+v", r)
	}
	if r.Status == msg.ConfigViewOK && r.Reason != "" {
		t.Fatalf("OK reply with a reason %+v", r)
	}
	return r
}

func cvReq(id string) msg.ConfigViewRequest {
	return msg.ConfigViewRequest{RequestID: id, PlacementID: "binding-1", Allow: cvAllow, MaxBytes: 64 << 10}
}

func wantRefused(t *testing.T, r msg.ConfigViewReply, reason msg.ConfigViewReason) {
	t.Helper()
	if r.Status != msg.ConfigViewRefused || r.Reason != reason {
		t.Fatalf("reply %+v, want REFUSED %s", r, reason)
	}
}

func tViewReturnsAllowedKeysOnly(t *testing.T) {
	gw, _ := cvAgent(t, []byte(cvDoc), nil)
	r := cvAsk(t, gw, cvReq("v1"))
	if r.Status != msg.ConfigViewOK {
		t.Fatalf("reply %+v", r)
	}
	got := map[string]string{}
	for _, e := range r.Entries {
		got[e.Path] = e.Value
	}
	if len(got) != 2 || got["server.port"] != "8080" || got["management.server.port"] != "9090" {
		t.Fatalf("entries %+v", r.Entries)
	}
	if r.HiddenCount != 1 {
		t.Fatalf("hidden %d, want 1", r.HiddenCount)
	}
}

func tPlacementNotInLeaseRefused(t *testing.T) {
	gw, _ := cvAgent(t, []byte(cvDoc), nil)
	req := cvReq("v2")
	req.PlacementID = "binding-other"
	wantRefused(t, cvAsk(t, gw, req), msg.ConfigViewPlacementNotInLease)
}

func tMissingFile(t *testing.T) {
	gw, _ := cvAgent(t, nil, nil)
	wantRefused(t, cvAsk(t, gw, cvReq("v3")), msg.ConfigViewFileMissing)
}

func tSymlinkRefusedAsNotPermitted(t *testing.T) {
	gw, ad := cvAgent(t, nil, nil)
	outside := filepath.Join(t.TempDir(), "other.yml")
	if err := os.WriteFile(outside, []byte(cvDoc), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, ad.target()); err != nil {
		t.Fatal(err)
	}
	wantRefused(t, cvAsk(t, gw, cvReq("v4")), msg.ConfigViewNotPermitted)
}

func tOversizedFileRefused(t *testing.T) {
	big := []byte("a: " + strings.Repeat("x", configViewHardCap+10) + "\n")
	gw, _ := cvAgent(t, big, nil)
	req := cvReq("v5")
	req.MaxBytes = 1 << 30
	wantRefused(t, cvAsk(t, gw, req), msg.ConfigViewFileTooLarge)
}

func tInvalidYamlRefused(t *testing.T) {
	gw, _ := cvAgent(t, []byte("a: [unclosed\n"), nil)
	wantRefused(t, cvAsk(t, gw, cvReq("v6")), msg.ConfigViewInvalidYAML)
}

// A request max_bytes above the hard cap is clamped to it: a file between the
// cap and the request is refused, one under the cap is served.
func tRequestLimitsAreClamped(t *testing.T) {
	over := []byte("server:\n  port: 1\npad: " + strings.Repeat("x", configViewHardCap+1) + "\n")
	gw, ad := cvAgent(t, over, nil)
	req := cvReq("v7")
	req.MaxBytes = 1 << 31
	wantRefused(t, cvAsk(t, gw, req), msg.ConfigViewFileTooLarge)

	// Under the hard cap but above the file's own emitted-bytes cap (the clamped
	// request): the filter refuses it rather than the huge request widening it.
	under := []byte("server:\n  port: 1\npad: " + strings.Repeat("x", configViewHardCap-4096) + "\n")
	if err := os.WriteFile(ad.target(), under, 0o600); err != nil {
		t.Fatal(err)
	}
	req.RequestID = "v7b"
	if r := cvAsk(t, gw, req); r.Status != msg.ConfigViewOK || len(r.Entries) != 1 {
		t.Fatalf("under-cap file with a huge request: %+v", r)
	}
	req.RequestID, req.MaxBytes = "v7c", 100
	wantRefused(t, cvAsk(t, gw, req), msg.ConfigViewFileTooLarge)
}

type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuf) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuf) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

type failingResolve struct {
	*fakeAdapter
	fail *bool
}

func (a failingResolve) Resolve(ctx context.Context, b string) (runtime.Placement, error) {
	if *a.fail {
		return runtime.Placement{}, errors.New("boom /secret/host/path/application.yml hunter2-secret")
	}
	return a.fakeAdapter.Resolve(ctx, b)
}

func tNoValueOrPathInLogs(t *testing.T) {
	var buf syncBuf
	fail := false
	gw, ad := cvAgent(t, []byte(cvDoc), func(bc *BuildConfig, a *fakeAdapter) {
		bc.Log = slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
		bc.Adapter = failingResolve{fakeAdapter: a, fail: &fail}
	})
	// success
	cvAsk(t, gw, cvReq("l1"))
	// refusal: not in lease
	missing := cvReq("l2")
	missing.PlacementID = "binding-other"
	wantRefused(t, cvAsk(t, gw, missing), msg.ConfigViewPlacementNotInLease)
	// refusal: symlink
	outside := filepath.Join(t.TempDir(), "other.yml")
	_ = os.WriteFile(outside, []byte(cvDoc), 0o600)
	_ = os.Remove(ad.target())
	if err := os.Symlink(outside, ad.target()); err != nil {
		t.Fatal(err)
	}
	wantRefused(t, cvAsk(t, gw, cvReq("l3")), msg.ConfigViewNotPermitted)
	// refusal: invalid YAML carrying a secret
	_ = os.Remove(ad.target())
	_ = os.WriteFile(ad.target(), []byte("datasource:\n  password: hunter2-secret\n  x: [\n"), 0o600)
	wantRefused(t, cvAsk(t, gw, cvReq("l4")), msg.ConfigViewInvalidYAML)
	// error path: the adapter fails with text naming a path and a secret
	fail = true
	wantRefused(t, cvAsk(t, gw, cvReq("l5")), msg.ConfigViewFileUnreadable)

	out := buf.String()
	if !strings.Contains(out, "config view refused") {
		t.Fatalf("expected the refusals to be logged by reason; log:\n%s", out)
	}
	for _, bad := range []string{"hunter2-secret", "datasource", "password", "application.yml", ad.dir, "/secret/host", "other.yml", "management", "server.port", "8080", "9090"} {
		if strings.Contains(out, bad) {
			t.Fatalf("log contains %q:\n%s", bad, out)
		}
	}
}

type blockingResolve struct {
	*fakeAdapter
	gate chan struct{}
}

func (a blockingResolve) Resolve(ctx context.Context, b string) (runtime.Placement, error) {
	select {
	case <-a.gate:
	case <-ctx.Done():
	}
	return a.fakeAdapter.Resolve(ctx, b)
}

// A FIFO at the path is refused without hanging.
func tFifoRefusedWithoutHanging(t *testing.T) {
	gw, ad := cvAgent(t, nil, nil)
	if err := syscall.Mkfifo(ad.target(), 0o600); err != nil {
		t.Fatal(err)
	}
	done := make(chan msg.ConfigViewReply, 1)
	go func() { done <- cvAsk(t, gw, cvReq("f1")) }()
	select {
	case r := <-done:
		wantRefused(t, r, msg.ConfigViewNotPermitted)
	case <-time.After(5 * time.Second):
		t.Fatal("a FIFO at the config path hung the handler")
	}
}

// With no role (no adapter) a request still gets exactly one refusal.
func tNoRoleStillReplies(t *testing.T) {
	gw := startGateway(t, fakegw.DefaultScenario())
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 15*time.Second)
	wantRefused(t, cvAsk(t, gw, cvReq("n1")), msg.ConfigViewNotPermitted)
}

// Over the concurrency bound, further requests are refused at once rather than queued.
func tBusyRefusedImmediately(t *testing.T) {
	gate := make(chan struct{})
	gw, _ := cvAgent(t, []byte(cvDoc), func(bc *BuildConfig, a *fakeAdapter) {
		bc.Adapter = blockingResolve{fakeAdapter: a, gate: gate}
	})
	for i := 0; i < configViewConcurrency; i++ {
		req := cvReq("b" + string(rune('0'+i)))
		if err := gw.RequestConfigView(req); err != nil {
			t.Fatal(err)
		}
	}
	extra := cvReq("b-extra")
	if err := gw.RequestConfigView(extra); err != nil {
		t.Fatal(err)
	}
	if !gw.WaitFor("ConfigViewReply", 1, 5*time.Second) {
		t.Fatal("the over-bound request was not refused at once")
	}
	r := gw.Received("ConfigViewReply")[0].(msg.ConfigViewReply)
	if r.RequestID != "b-extra" {
		t.Fatalf("first reply is %q, want the refused extra", r.RequestID)
	}
	wantRefused(t, r, msg.ConfigViewFileUnreadable)
	close(gate)
	if !gw.WaitFor("ConfigViewReply", configViewConcurrency+1, 10*time.Second) {
		t.Fatal("held requests were not answered after release")
	}
}

func tAllowListBounded(t *testing.T) {
	gw, _ := cvAgent(t, []byte(cvDoc), nil)
	many := cvReq("a1")
	many.Allow = make([]string, configViewMaxAllow+1)
	for i := range many.Allow {
		many.Allow[i] = "a"
	}
	wantRefused(t, cvAsk(t, gw, many), msg.ConfigViewNotPermitted)
	long := cvReq("a2")
	long.Allow = []string{strings.Repeat("k", configViewMaxAllowBytes+1)}
	wantRefused(t, cvAsk(t, gw, long), msg.ConfigViewNotPermitted)
}

// The plan's test names are kept as subtest names; the one top-level name lets
// the Makefile's -run pattern (and -run ConfigView) select them all.
func TestConfigView(t *testing.T) {
	for _, c := range []struct {
		name string
		f    func(*testing.T)
	}{
		{"TestViewReturnsAllowedKeysOnly", tViewReturnsAllowedKeysOnly},
		{"TestPlacementNotInLeaseRefused", tPlacementNotInLeaseRefused},
		{"TestMissingFile", tMissingFile},
		{"TestSymlinkRefusedAsNotPermitted", tSymlinkRefusedAsNotPermitted},
		{"TestOversizedFileRefused", tOversizedFileRefused},
		{"TestInvalidYamlRefused", tInvalidYamlRefused},
		{"TestRequestLimitsAreClamped", tRequestLimitsAreClamped},
		{"TestNoValueOrPathInLogs", tNoValueOrPathInLogs},
		{"TestFifoRefusedWithoutHanging", tFifoRefusedWithoutHanging},
		{"TestNoRoleStillReplies", tNoRoleStillReplies},
		{"TestBusyRefusedImmediately", tBusyRefusedImmediately},
		{"TestAllowListBounded", tAllowListBounded},
	} {
		t.Run(c.name, c.f)
	}
}
