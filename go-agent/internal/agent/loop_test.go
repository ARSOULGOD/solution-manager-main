package agent

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/testca"
	"github.com/solace/solution-manager/go-agent/internal/wire"
)

// fakeConn is a channel-backed Conn: the test reads what the agent sends and
// injects what the gateway would say.
type fakeConn struct {
	sent   chan msg.Outbound
	inject chan msg.Inbound
	closed chan struct{}
	once   sync.Once
}

func newFakeConn() *fakeConn {
	return &fakeConn{sent: make(chan msg.Outbound, 64), inject: make(chan msg.Inbound, 64), closed: make(chan struct{})}
}
func (c *fakeConn) Send(m msg.Outbound) error { c.sent <- m; return nil }
func (c *fakeConn) Recv() (msg.Inbound, error) {
	select {
	case m := <-c.inject:
		return m, nil
	case <-c.closed:
		return nil, io.EOF
	}
}
func (c *fakeConn) Close() error { c.once.Do(func() { close(c.closed) }); return nil }

type fakeWire struct {
	ca       *testca.CA
	enrolls  int
	conns    []*fakeConn
	mu       sync.Mutex
	failNext error
}

func (w *fakeWire) Enroll(_ context.Context, _ string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.enrolls++
	if w.failNext != nil {
		err := w.failNext
		w.failNext = nil
		return nil, err
	}
	leaf, err := w.ca.Sign(req.CSRDER)
	if err != nil {
		return nil, err
	}
	return &msg.EnrollResponse{AgentID: "agent-1", CertificateChain: [][]byte{leaf},
		TrustBundle: msg.TrustBundle{CertsPEM: [][]byte{w.ca.PEM()}}, ConnectEndpoint: "dp:443"}, nil
}

func (w *fakeWire) Dial(context.Context, string) (Conn, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	c := newFakeConn()
	w.conns = append(w.conns, c)
	return c, nil
}

func (w *fakeWire) conn(i int) *fakeConn {
	for {
		w.mu.Lock()
		if len(w.conns) > i {
			c := w.conns[i]
			w.mu.Unlock()
			return c
		}
		w.mu.Unlock()
		time.Sleep(5 * time.Millisecond)
	}
}

func expect[T msg.Outbound](t *testing.T, c *fakeConn) T {
	t.Helper()
	select {
	case m := <-c.sent:
		v, ok := m.(T)
		if !ok {
			t.Fatalf("expected %T, got %T", *new(T), m)
		}
		return v
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for %T", *new(T))
	}
	panic("unreachable")
}

func newTestLoop(t *testing.T, w *fakeWire) (*Loop, *store.Store) {
	t.Helper()
	s, err := store.Open(filepath.Join(t.TempDir(), "d"))
	if err != nil {
		t.Fatal(err)
	}
	id, err := identity.Load(s)
	if err != nil {
		t.Fatal(err)
	}
	st, err := LoadState(cfg, s, id)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	var mu sync.Mutex
	l := New(Deps{
		Config: cfg, Store: s, Identity: id, Wire: w,
		Offset: func() time.Duration { return 0 },
		NewID:  func() string { mu.Lock(); defer mu.Unlock(); n++; return "id-" + time.Duration(n).String() },
		Jitter: func(d time.Duration) time.Duration { return d },
		Now:    time.Now,
		Log:    slog.New(slog.NewTextHandler(io.Discard, nil)),
	}, st)
	return l, s
}

func TestLoopEnrollsOpensAndHeartbeats(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, s := newTestLoop(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- l.Run(ctx) }()

	c := w.conn(0)
	so := expect[msg.SessionOpen](t, c)
	if so.ProtocolVersion != 1 {
		t.Fatalf("%+v", so)
	}
	if !s.Exists("identity/cert.pem") || !s.Exists("session/endpoint") {
		t.Fatal("enrollment and endpoint must be persisted before dialing")
	}
	c.inject <- msg.SessionAccept{SessionID: "s1", Limits: msg.Limits{HeartbeatInterval: 20 * time.Millisecond}}
	hb := expect[msg.Heartbeat](t, c)
	if hb.MessageID == "" {
		t.Fatal("heartbeat has no message id")
	}
	expect[msg.Heartbeat](t, c)
	if l.Snapshot().Session.Phase != PhaseActive {
		t.Fatal(l.Snapshot().Session.Phase)
	}

	cancel()
	expect[msg.Teardown](t, c)
	select {
	case <-c.closed:
	case <-time.After(time.Second):
		t.Fatal("conn not closed on shutdown")
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestLoopReconnectsAfterDisconnect(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, _ := newTestLoop(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()

	c0 := w.conn(0)
	expect[msg.SessionOpen](t, c0)
	c0.inject <- msg.SessionAccept{SessionID: "s1", Limits: msg.Limits{HeartbeatInterval: time.Hour}}
	c0.Close() // gateway drops us → backoff 1s → dial again
	c1 := w.conn(1)
	expect[msg.SessionOpen](t, c1)
}

func TestLoopStaleDisconnectIsIgnored(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, _ := newTestLoop(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()
	c0 := w.conn(0)
	expect[msg.SessionOpen](t, c0)
	c0.inject <- msg.Error{Code: msg.ErrSessionSuperseded} // Close + reconnect now
	c1 := w.conn(1)
	expect[msg.SessionOpen](t, c1)
	c0.Close() // late EOF from the old conn must not disturb the new one
	c1.inject <- msg.SessionAccept{SessionID: "s2", Limits: msg.Limits{HeartbeatInterval: time.Hour}}
	time.Sleep(50 * time.Millisecond)
	if p := l.Snapshot().Session.Phase; p != PhaseActive {
		t.Fatalf("stale disconnect disturbed the session: %s", p)
	}
}

// TestLoopReconnectsAfterDrain is the regression test for the deadlock the
// broadened intentionalCloseGen suppression introduced and the narrowed
// willRedialImmediately condition fixes: Drain arms ReconnectDue with a real
// (nonzero) delay, not 0, so its Close must NOT suppress the resulting
// connClosed echo — session.go's Disconnected case relies on that echo to
// leave PhaseDraining. If the echo were wrongly suppressed, the timer would
// still fire, but PhaseDraining never matches TimerFired{ReconnectDue}'s
// phase checks, so the agent would drain and never reconnect.
func TestLoopReconnectsAfterDrain(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, _ := newTestLoop(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()

	c0 := w.conn(0)
	expect[msg.SessionOpen](t, c0)
	c0.inject <- msg.SessionAccept{SessionID: "s1", Limits: msg.Limits{HeartbeatInterval: time.Hour}}
	c0.inject <- msg.Drain{Reason: "maintenance", ReconnectAfter: 50 * time.Millisecond}

	select {
	case <-c0.closed:
	case <-time.After(time.Second):
		t.Fatal("conn not closed after drain")
	}

	c1 := w.conn(1)
	expect[msg.SessionOpen](t, c1)
}

func TestLoopHaltsOnTokenConsumed(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca, failNext: &msg.ProtocolError{Code: msg.ErrTokenConsumed}}
	l, _ := newTestLoop(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if l.Halted() == msg.ErrTokenConsumed {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("loop did not halt")
}

func TestLoopPersistFailureIsFatal(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, s := newTestLoop(t, w)
	// Make session/ unwritable so the endpoint persist fails.
	if err := os.Chmod(s.Path("session"), 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(s.Path("session"), 0o700) })
	err := l.Run(context.Background())
	if err == nil || !errors.Is(err, ErrFatalPersist) {
		t.Fatalf("want ErrFatalPersist, got %v", err)
	}
}

// A fatal persist returns from Run while the worker is parked on `range l.ops`
// and the ctx watcher on `<-ctx.Done()` — which, for context.Background(),
// never fires. Every exit from Run must release both.
func TestRunReleasesItsGoroutinesOnTheFatalPath(t *testing.T) {
	ca, _ := testca.New()
	l, s := newTestLoop(t, &fakeWire{ca: ca})
	if err := os.Chmod(s.Path("session"), 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(s.Path("session"), 0o700) })
	if err := l.Run(context.Background()); !errors.Is(err, ErrFatalPersist) {
		t.Fatalf("want ErrFatalPersist, got %v", err)
	}

	// the worker: its channel must be closed so its range ends
	select {
	case _, ok := <-l.ops:
		if ok {
			t.Fatal("an op was queued on the fatal path")
		}
	default:
		t.Fatal("l.ops left open: the worker goroutine is stranded on range forever")
	}

	// the ctx watcher: Run's own context must be cancelled, which releases it;
	// it posts its Shutdown and returns
	deadline := time.After(2 * time.Second)
	for {
		select {
		case ev := <-l.inbox:
			if _, ok := ev.(reactor.Shutdown); ok {
				return
			}
		case <-deadline:
			t.Fatal("the ctx watcher never fired: it is blocked on a context nothing will cancel")
		}
	}
}

// ---- logs role (plan 016) ----

// fakeLogsRole records what the loop hands the logs role.
type fakeLogsRole struct {
	mu       sync.Mutex
	sessions []*LogsSession
	requests []msg.LogRequest
	retains  []msg.LogRetention
}

func (f *fakeLogsRole) Run(ctx context.Context) { <-ctx.Done() }
func (f *fakeLogsRole) Session(s *LogsSession) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sessions = append(f.sessions, s)
}
func (f *fakeLogsRole) Retention(m msg.LogRetention) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.retains = append(f.retains, m)
}
func (f *fakeLogsRole) Request(m msg.LogRequest) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.requests = append(f.requests, m)
}
func (f *fakeLogsRole) counts() (int, int, int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.sessions), len(f.requests), len(f.retains)
}

func runLogsLoop(t *testing.T, caps []msg.Capability) (*fakeLogsRole, *fakeConn, *Loop) {
	t.Helper()
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, _ := newTestLoop(t, w)
	l.d.Config.Capabilities = caps
	role := &fakeLogsRole{}
	l.d.Logs = role
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- l.Run(ctx) }()
	t.Cleanup(func() { cancel(); <-done })
	c := w.conn(0)
	expect[msg.SessionOpen](t, c)
	return role, c, l
}

func TestLogsSessionStartsOnlyAfterSessionAcceptWithCapLogs(t *testing.T) {
	role, c, l := runLogsLoop(t, []msg.Capability{msg.CapReportOnly, msg.CapLogs})
	if n, _, _ := role.counts(); n != 0 {
		t.Fatal("no logs session before SessionAccept")
	}
	c.inject <- msg.SessionAccept{SessionID: "s1", Limits: msg.Limits{HeartbeatInterval: time.Hour, MaxMessageBytes: 4096},
		Lease: msg.LeaseGrant{BindingIDs: []string{"b1"}, ExpiresAt: time.Now().Add(time.Hour)}}
	c.inject <- msg.LogRetention{PlacementRef: "b1", MaxBytes: 1 << 20}
	c.inject <- msg.LogRequest{RequestID: "r1", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 1}}
	waitFor(t, func() bool { _, r, k := role.counts(); return r == 1 && k == 1 })
	role.mu.Lock()
	s := role.sessions[0]
	role.mu.Unlock()
	if s == nil || s.ID != "s1" || s.Limits.MaxMessageBytes != 4096 || s.Open == nil {
		t.Fatalf("%+v", s)
	}
	if l.Snapshot().Session.Phase != PhaseActive {
		t.Fatal("not active")
	}
}

func TestAgentWithoutCapLogsNeverHandsTheRoleLogMessages(t *testing.T) {
	role, c, _ := runLogsLoop(t, []msg.Capability{msg.CapReportOnly})
	c.inject <- msg.SessionAccept{SessionID: "s1", Limits: msg.Limits{HeartbeatInterval: time.Hour}}
	c.inject <- msg.LogRetention{PlacementRef: "b1", MaxBytes: 1 << 20}
	c.inject <- msg.LogRequest{RequestID: "r1", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 1}}
	c.inject <- msg.HeartbeatAck{}
	time.Sleep(100 * time.Millisecond)
	if s, r, k := role.counts(); s != 0 || r != 0 || k != 0 {
		t.Fatalf("sessions=%d requests=%d retentions=%d; a session without CapLogs must carry no logs", s, r, k)
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestLogCollectorBackoffHasAFloorOnEveryAttempt(t *testing.T) {
	for a := range 12 {
		for range 50 {
			if d := collectorBackoff(a); d < time.Second || d > 31*time.Second {
				t.Fatalf("attempt %d: %v", a, d)
			}
		}
	}
}

// fakeLogsStream is the Logs RPC: the test reads what the agent sends and
// injects credits and cancels.
type fakeLogsStream struct {
	sent   chan msg.LogsOutbound
	inject chan msg.LogsInbound
	errs   chan error // a Recv error: undecodable (wraps wire.ErrUndecodable) or transport
	closed chan struct{}
	once   sync.Once
}

func newFakeLogsStream() *fakeLogsStream {
	return &fakeLogsStream{sent: make(chan msg.LogsOutbound, 64), inject: make(chan msg.LogsInbound, 64),
		errs: make(chan error, 4), closed: make(chan struct{})}
}
func (s *fakeLogsStream) Send(m msg.LogsOutbound) error {
	select {
	case <-s.closed:
		return io.EOF
	default:
	}
	s.sent <- m
	return nil
}
func (s *fakeLogsStream) Recv() (msg.LogsInbound, error) {
	select {
	case m := <-s.inject:
		return m, nil
	case err := <-s.errs:
		return nil, err
	case <-s.closed:
		return nil, io.EOF
	}
}
func (s *fakeLogsStream) Close() error { s.once.Do(func() { close(s.closed) }); return nil }

func (s *fakeLogsStream) page(t *testing.T) msg.LogPage {
	t.Helper()
	select {
	case m := <-s.sent:
		p, ok := m.(msg.LogPage)
		if !ok {
			t.Fatalf("want LogPage, got %T", m)
		}
		return p
	case <-time.After(2 * time.Second):
		t.Fatal("no LogPage")
	}
	panic("unreachable")
}

func TestLogsRoleServesOverTheSessionStreamAndKeepsTheDPCap(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "d"))
	if err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	role := newLogsRole(s, LogsConfig{DefaultMaxBytes: 4096}, nil, func() string { return "m" }, log)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); role.Run(ctx) }()

	st := newFakeLogsStream()
	opened := make(chan struct{}, 4)
	role.Session(&LogsSession{ID: "s1", Limits: msg.Limits{MaxMessageBytes: 1 << 20, MaxBatchBytes: 1 << 20},
		Bindings: map[string]bool{"b1": true, "b2": true},
		Open:     func(context.Context) (LogsStream, error) { opened <- struct{}{}; return st, nil }})
	role.Retention(msg.LogRetention{PlacementRef: "b1", MaxBytes: 1 << 20})
	role.Retention(msg.LogRetention{PlacementRef: "b2", MaxBytes: 0})
	waitFor(t, func() bool { rg, ok := role.ring("b1"); return ok && rg.Cap() == 1<<20 })
	rg, _ := role.ring("b1")
	if err := rg.Append(msg.LogLine{TS: time.Now(), Stream: "stdout", Text: []byte("hello")}); err != nil {
		t.Fatal(err)
	}

	role.Request(msg.LogRequest{RequestID: "r1", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 10}})
	if p := st.page(t); p.RequestID != "r1" || len(p.Lines) != 1 || string(p.Lines[0].Text) != "hello" {
		t.Fatalf("%+v", p)
	}
	role.Request(msg.LogRequest{RequestID: "r2", PlacementRef: "elsewhere", Page: &msg.LogPageQuery{Limit: 10}})
	if p := st.page(t); p.Unavailable != msg.UnavailablePlacementNotLease {
		t.Fatalf("%+v", p)
	}
	role.Request(msg.LogRequest{RequestID: "r3", PlacementRef: "b2", Page: &msg.LogPageQuery{Limit: 10}})
	if p := st.page(t); p.Unavailable != msg.UnavailableNotCollecting {
		t.Fatalf("a DP cap of 0 turns collection off: %+v", p)
	}

	// A new lease on the same session refreshes it without reopening the stream.
	role.Session(&LogsSession{ID: "s1", Bindings: map[string]bool{"b1": true}, Open: func(context.Context) (LogsStream, error) { opened <- struct{}{}; return st, nil }})
	role.Session(nil)
	waitFor(t, func() bool {
		select {
		case <-st.closed:
			return true
		default:
			return false
		}
	})
	if len(opened) != 1 {
		t.Fatalf("stream opened %d times", len(opened))
	}
	cancel()
	<-done

	// After a restart the configured default never overrides the DP's caps.
	role2 := newLogsRole(s, LogsConfig{DefaultMaxBytes: 4096}, nil, func() string { return "m" }, log)
	for p, want := range map[string]uint64{"b1": 1 << 20, "b2": 0, "b3": 4096} {
		role2.ensure(context.Background(), p)
		rg, ok := role2.ring(p)
		if !ok || rg.Cap() != want {
			t.Fatalf("%s: cap %d, want %d", p, rg.Cap(), want)
		}
		_ = rg.Close()
	}
}

func (s *fakeLogsStream) chunk(t *testing.T) msg.LogChunk {
	t.Helper()
	select {
	case m := <-s.sent:
		c, ok := m.(msg.LogChunk)
		if !ok {
			t.Fatalf("want LogChunk, got %T %+v", m, m)
		}
		return c
	case <-time.After(2 * time.Second):
		t.Fatal("no LogChunk")
	}
	panic("unreachable")
}

func isClosed(c chan struct{}) bool {
	select {
	case <-c:
		return true
	default:
		return false
	}
}

// startRole runs a logsRole over a fresh store with fast reopen backoff.
func startRole(t *testing.T, src *fakeLogSource) (*logsRole, *store.Store, *strings.Builder) {
	t.Helper()
	s, err := store.Open(filepath.Join(t.TempDir(), "d"))
	if err != nil {
		t.Fatal(err)
	}
	var buf strings.Builder
	var mu sync.Mutex
	log := slog.New(slog.NewTextHandler(lockedWriter{&mu, &buf}, nil))
	var cs collectSource
	if src != nil {
		cs = src
	}
	role := newLogsRole(s, LogsConfig{DefaultMaxBytes: 1 << 20}, cs, func() string { return "m" }, log)
	role.backoff = func(int) time.Duration { return 10 * time.Millisecond }
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); role.Run(ctx) }()
	t.Cleanup(func() { cancel(); <-done })
	return role, s, &buf
}

type lockedWriter struct {
	mu *sync.Mutex
	b  *strings.Builder
}

func (w lockedWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.b.Write(p)
}

// streams hands out a new fake stream per Open.
type streams struct {
	mu  sync.Mutex
	all []*fakeLogsStream
}

func (ss *streams) open(context.Context) (LogsStream, error) {
	ss.mu.Lock()
	defer ss.mu.Unlock()
	st := newFakeLogsStream()
	ss.all = append(ss.all, st)
	return st, nil
}

func (ss *streams) get(t *testing.T, i int) *fakeLogsStream {
	t.Helper()
	var st *fakeLogsStream
	waitFor(t, func() bool {
		ss.mu.Lock()
		defer ss.mu.Unlock()
		if len(ss.all) > i {
			st = ss.all[i]
			return true
		}
		return false
	})
	return st
}

func session(ss *streams, bindings ...string) *LogsSession {
	b := map[string]bool{}
	for _, x := range bindings {
		b[x] = true
	}
	return &LogsSession{ID: "s1", Limits: msg.Limits{MaxMessageBytes: 1 << 20, MaxBatchBytes: 1 << 20}, Bindings: b, Open: ss.open}
}

func TestLogsStreamSkipsAnUndecodableMessageAndKeepsServing(t *testing.T) {
	role, _, _ := startRole(t, nil)
	ss := &streams{}
	role.Session(session(ss, "b1"))
	role.Retention(msg.LogRetention{PlacementRef: "b1", MaxBytes: 1 << 20})
	st := ss.get(t, 0)
	waitFor(t, func() bool { _, ok := role.ring("b1"); return ok })
	rg, _ := role.ring("b1")
	_ = rg.Append(msg.LogLine{TS: time.Now(), Stream: "stdout", Text: []byte("x")})

	st.errs <- fmt.Errorf("decode: %w", wire.ErrUndecodable)
	for i := range serveBound + 1 { // more exports than the bound: none may be BUSY
		id := fmt.Sprintf("e%d", i)
		role.Request(msg.LogRequest{RequestID: id, PlacementRef: "b1", Export: &msg.LogExportQuery{}})
		waitFor(t, func() bool { return role.srv.Inflight() == 1 })
		st.inject <- msg.LogCredit{RequestID: id, CreditsGranted: 1}
		if c := st.chunk(t); c.RequestID != id || !c.Final {
			t.Fatalf("%+v", c)
		}
		waitFor(t, func() bool { return role.srv.Inflight() == 0 })
	}
	if role.undecodable.Load() != 1 {
		t.Fatalf("undecodable=%d", role.undecodable.Load())
	}
	if len(ss.all) != 1 {
		t.Fatal("an undecodable message must not reopen the stream")
	}
}

const serveBound = 4

func TestLogsStreamTransportErrorEndsPendingExportsAndReopens(t *testing.T) {
	role, _, _ := startRole(t, nil)
	ss := &streams{}
	role.Session(session(ss, "b1"))
	role.Retention(msg.LogRetention{PlacementRef: "b1", MaxBytes: 1 << 20})
	st := ss.get(t, 0)
	waitFor(t, func() bool { _, ok := role.ring("b1"); return ok })
	rg, _ := role.ring("b1")
	_ = rg.Append(msg.LogLine{TS: time.Now(), Stream: "stdout", Text: []byte("x")})
	role.Request(msg.LogRequest{RequestID: "e1", PlacementRef: "b1", Export: &msg.LogExportQuery{}})
	waitFor(t, func() bool { return role.srv.Inflight() == 1 })

	st.errs <- io.ErrUnexpectedEOF // transport: the stream is gone
	waitFor(t, func() bool { return role.srv.Inflight() == 0 && isClosed(st.closed) })

	st2 := ss.get(t, 1) // reopened within the same session
	role.Request(msg.LogRequest{RequestID: "r2", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 5}})
	if p := st2.page(t); p.RequestID != "r2" || len(p.Lines) != 1 {
		t.Fatalf("%+v", p)
	}
}

// fakeLogSource follows nothing; it counts live follows per binding.
type fakeLogSource struct {
	mu   sync.Mutex
	live map[string]int
}

type collectSource interface {
	FollowLogs(ctx context.Context, p runtime.Placement, since time.Time) (<-chan msg.LogLine, <-chan error)
	ResolveByName(ctx context.Context, containerName, bindingID string) (runtime.Placement, error)
}

func (f *fakeLogSource) ResolveByName(_ context.Context, _ string, b string) (runtime.Placement, error) {
	return runtime.Placement{BindingID: b, ContainerID: "c-" + b}, nil
}

func (f *fakeLogSource) FollowLogs(ctx context.Context, p runtime.Placement, _ time.Time) (<-chan msg.LogLine, <-chan error) {
	f.mu.Lock()
	f.live[p.BindingID]++
	f.mu.Unlock()
	lines, errs := make(chan msg.LogLine), make(chan error, 1)
	go func() {
		<-ctx.Done()
		f.mu.Lock()
		f.live[p.BindingID]--
		f.mu.Unlock()
		close(lines)
		errs <- nil
	}()
	return lines, errs
}

func (f *fakeLogSource) following(b string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.live[b]
}

func TestLeaseShrinkStopsCollectorClosesRingAndCancelsExport(t *testing.T) {
	src := &fakeLogSource{live: map[string]int{}}
	role, _, _ := startRole(t, src)
	ss := &streams{}
	role.Session(session(ss, "b1", "b2"))
	st := ss.get(t, 0)
	waitFor(t, func() bool { return src.following("b1") == 1 && src.following("b2") == 1 })
	rg, _ := role.ring("b1")
	_ = rg.Append(msg.LogLine{TS: time.Now(), Stream: "stdout", Text: []byte("kept")})
	role.Request(msg.LogRequest{RequestID: "e1", PlacementRef: "b1", Export: &msg.LogExportQuery{}})
	waitFor(t, func() bool { return role.srv.Inflight() == 1 })

	role.Session(session(ss, "b2")) // b1 leaves the lease, same session
	waitFor(t, func() bool {
		_, open := role.ring("b1")
		return src.following("b1") == 0 && !open && role.srv.Inflight() == 0
	})
	if src.following("b2") != 1 {
		t.Fatal("a placement still in the lease keeps its collector")
	}
	st.inject <- msg.LogCredit{RequestID: "e1", CreditsGranted: 5}
	select {
	case m := <-st.sent:
		t.Fatalf("a revoked export must send nothing more: %T", m)
	case <-time.After(100 * time.Millisecond):
	}

	role.Session(session(ss, "b1", "b2")) // re-enters
	waitFor(t, func() bool { return src.following("b1") == 1 })
	role.Request(msg.LogRequest{RequestID: "r2", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 5}})
	if p := st.page(t); len(p.Lines) != 1 || string(p.Lines[0].Text) != "kept" {
		t.Fatalf("stored logs must survive leaving the lease: %+v", p)
	}
}

func TestEnsureRefusesAHostileBindingIDBeforeTouchingTheStore(t *testing.T) {
	role, s, buf := startRole(t, nil)
	if err := s.Save(store.Blob{RelPath: "x"}); err != nil { // what "logs-dp-cap/../x" would resolve to
		t.Fatal(err)
	}
	role.ensure(context.Background(), "../x")
	if _, ok := role.ring("../x"); ok {
		t.Fatal("a hostile id must get no ring")
	}
	if !strings.Contains(buf.String(), "invalid placement ref") {
		t.Fatalf("want the id refused up front; log: %s", buf.String())
	}
}
