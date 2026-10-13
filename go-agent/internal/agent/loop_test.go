package agent

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/testca"
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
