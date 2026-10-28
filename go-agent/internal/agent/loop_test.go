package agent

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/logs/logfile"
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
}

func (f *fakeLogsRole) Run(ctx context.Context) { <-ctx.Done() }
func (f *fakeLogsRole) Session(s *LogsSession) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sessions = append(f.sessions, s)
}
func (f *fakeLogsRole) Request(m msg.LogRequest) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.requests = append(f.requests, m)
}
func (f *fakeLogsRole) counts() (int, int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.sessions), len(f.requests)
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
	if n, _ := role.counts(); n != 0 {
		t.Fatal("no logs session before SessionAccept")
	}
	c.inject <- msg.SessionAccept{SessionID: "s1", Limits: msg.Limits{HeartbeatInterval: time.Hour, MaxMessageBytes: 4096},
		Lease: msg.LeaseGrant{BindingIDs: []string{"b1"}, ExpiresAt: time.Now().Add(time.Hour)}}
	c.inject <- msg.LogRequest{RequestID: "r1", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 1}}
	waitFor(t, func() bool { _, r := role.counts(); return r == 1 })
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
	c.inject <- msg.LogRequest{RequestID: "r1", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 1}}
	c.inject <- msg.HeartbeatAck{}
	time.Sleep(100 * time.Millisecond)
	if s, r := role.counts(); s != 0 || r != 0 {
		t.Fatalf("sessions=%d requests=%d; a session without CapLogs must carry no logs", s, r)
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

func TestLogsStreamBackoffHasAFloorOnEveryAttempt(t *testing.T) {
	for a := range 12 {
		for range 50 {
			if d := logsStreamBackoff(a); d < time.Second || d > 31*time.Second {
				t.Fatalf("attempt %d: %v", a, d)
			}
		}
	}
}

// ---- an MI's log file, as the logs role reads it ----

var miEpoch = time.Date(2026, 10, 28, 10, 0, 0, 0, time.UTC)

// miLine is entry i of an MI log in Spring Boot's default pattern, one second after entry i-1.
func miLine(i int, text string) string {
	return miEpoch.Add(time.Duration(i)*time.Second).Format("2006-01-02T15:04:05.000Z07:00") +
		"  INFO 1 --- [           main] c.s.demo.App                             : " + text
}

// miLines is entries from..to-1, each text naming its number.
func miLines(from, to int) []string {
	var out []string
	for i := from; i < to; i++ {
		out = append(out, miLine(i, fmt.Sprintf("entry %03d %s", i, strings.Repeat("x", 40))))
	}
	return out
}

// logfileServer is an MI's /actuator/logfile: http.ServeContent answers ranged GETs over the current bytes as Spring
// Boot does (206, and 416 with the size). add grows the file, rotate replaces it, and a non-zero status answers every
// request with that code instead (404: the endpoint is not exposed).
type logfileServer struct {
	mu     sync.Mutex
	data   []byte
	status int
	srv    *httptest.Server
}

func newLogfileServer(t *testing.T, lines ...string) *logfileServer {
	t.Helper()
	s := &logfileServer{}
	s.add(lines...)
	s.srv = httptest.NewServer(http.HandlerFunc(s.serve))
	t.Cleanup(s.srv.Close)
	return s
}

func (s *logfileServer) serve(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	data, status := s.data, s.status
	s.mu.Unlock()
	switch {
	case r.URL.Path != logfile.Path:
		http.NotFound(w, r)
	case status != 0:
		w.WriteHeader(status)
	default:
		http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(data))
	}
}

func (s *logfileServer) addr() string { return s.srv.Listener.Addr().String() }

// add appends lines. The bytes go to a new array, so a reply being written never shares one with a write.
func (s *logfileServer) add(lines ...string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	data := slices.Clip(s.data)
	for _, l := range lines {
		data = append(data, l+"\n"...)
	}
	s.data = data
}

func (s *logfileServer) rotate(lines ...string) {
	s.mu.Lock()
	s.data = nil
	s.mu.Unlock()
	s.add(lines...)
}

func (s *logfileServer) setStatus(code int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.status = code
}

func (s *logfileServer) size() uint64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return uint64(len(s.data))
}

// miAdapter resolves every placement and puts its actuator at addr, except that a placement in gone has no container
// and one in noPort no usable actuator port. Every other runtime method panics (the embedded nil Adapter): the logs
// role calls none.
type miAdapter struct {
	runtime.Adapter
	addr   string
	gone   map[string]bool
	noPort map[string]bool
}

func (a miAdapter) Resolve(_ context.Context, b string) (runtime.Placement, error) {
	if a.gone[b] {
		return runtime.Placement{}, fmt.Errorf("fake: %w", runtime.ErrContainerNotFound)
	}
	return runtime.Placement{BindingID: b, ContainerID: "c-" + b}, nil
}

func (a miAdapter) ActuatorAddr(_ context.Context, p runtime.Placement) (string, error) {
	if a.noPort[p.BindingID] {
		return "", fmt.Errorf("fake: %w", runtime.ErrNoActuatorPort)
	}
	return a.addr, nil
}

// resolveOnly is an adapter with no ActuatorLocator; err, when set, is what Resolve fails with.
type resolveOnly struct {
	runtime.Adapter
	err error
}

func (a resolveOnly) Resolve(_ context.Context, b string) (runtime.Placement, error) {
	return runtime.Placement{BindingID: b}, a.err
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

// lockedLog is a log destination the test can read while the role writes to it.
type lockedLog struct {
	mu sync.Mutex
	b  strings.Builder
}

func (l *lockedLog) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedLog) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

// startRole runs a logsRole over adapter with fast reopen backoff; the returned log is what it wrote.
func startRole(t *testing.T, adapter runtime.Adapter) (*logsRole, *lockedLog) {
	t.Helper()
	logged := &lockedLog{}
	role := newLogsRole(LogsConfig{}, adapter, func() string { return "m" }, slog.New(slog.NewTextHandler(logged, nil)))
	role.backoff = func(int) time.Duration { return 10 * time.Millisecond }
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); role.Run(ctx) }()
	t.Cleanup(func() { cancel(); <-done })
	return role, logged
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

func TestLogsRoleServesOverTheSessionStream(t *testing.T) {
	mi := newLogfileServer(t, miLine(0, "hello"))
	role, logged := startRole(t, miAdapter{addr: mi.addr(), noPort: map[string]bool{"b2": true}, gone: map[string]bool{"b3": true}})
	st := newFakeLogsStream()
	opened := make(chan struct{}, 4)
	open := func(context.Context) (LogsStream, error) { opened <- struct{}{}; return st, nil }
	role.Session(&LogsSession{ID: "s1", Limits: msg.Limits{MaxMessageBytes: 1 << 20, MaxBatchBytes: 1 << 20},
		Bindings: map[string]bool{"b1": true, "b2": true, "b3": true}, Open: open})

	role.Request(msg.LogRequest{RequestID: "r1", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 10}})
	p := st.page(t)
	if p.RequestID != "r1" || len(p.Lines) != 1 || string(p.Lines[0].Text) != miLine(0, "hello") {
		t.Fatalf("%+v", p)
	}
	if l := p.Lines[0]; l.Stream != logfile.Stream || !l.TS.Equal(miEpoch) {
		t.Fatalf("stream %q ts %v", l.Stream, l.TS)
	}
	for _, c := range []struct {
		ref  string
		want msg.UnavailableReason
	}{
		{"elsewhere", msg.UnavailablePlacementNotLease},
		{"b2", msg.UnavailableNotCollecting},    // no usable actuator port
		{"b3", msg.UnavailableContainerMissing}, // no container
	} {
		role.Request(msg.LogRequest{RequestID: "r-" + c.ref, PlacementRef: c.ref, Page: &msg.LogPageQuery{Limit: 10}})
		if p := st.page(t); p.RequestID != "r-"+c.ref || p.Unavailable != c.want || len(p.Lines) != 0 {
			t.Fatalf("%s: %+v", c.ref, p)
		}
	}

	// A new lease on the same session refreshes it without reopening the stream.
	role.Session(&LogsSession{ID: "s1", Bindings: map[string]bool{"b1": true}, Open: open})
	role.Session(nil)
	waitFor(t, func() bool { return isClosed(st.closed) })
	if len(opened) != 1 {
		t.Fatalf("stream opened %d times", len(opened))
	}
	if strings.Contains(logged.String(), "hello") {
		t.Fatalf("MI log text reached the agent's log (I6): %s", logged.String())
	}
}

func TestLogsRoleOpenerMapsEachFailureToItsReason(t *testing.T) {
	mi := newLogfileServer(t, miLine(0, "found"))
	ok := miAdapter{addr: mi.addr(), gone: map[string]bool{"gone": true}, noPort: map[string]bool{"noport": true}}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, c := range []struct {
		name    string
		adapter runtime.Adapter
		ref     string
		want    msg.UnavailableReason
	}{
		{"no adapter", nil, "b1", msg.UnavailableNotCollecting},
		{"container gone", ok, "gone", msg.UnavailableContainerMissing},
		{"resolve fails", resolveOnly{err: errors.New("docker: daemon unreachable")}, "b1", msg.UnavailableNotCollecting},
		{"no actuator locator", resolveOnly{}, "b1", msg.UnavailableNotCollecting},
		{"no actuator port", ok, "noport", msg.UnavailableNotCollecting},
		{"found", ok, "b1", msg.UnavailableNone},
	} {
		t.Run(c.name, func(t *testing.T) {
			role := newLogsRole(LogsConfig{}, c.adapter, func() string { return "m" }, log)
			src, why := role.open(context.Background(), c.ref)
			if why != c.want || (src != nil) != (c.want == msg.UnavailableNone) {
				t.Fatalf("reason %q source %v, want %q", why, src, c.want)
			}
			if src == nil {
				return
			}
			lines, err := src.Page(context.Background(), msg.LogPageQuery{}, 1<<10)
			if err != nil || len(lines) != 1 || string(lines[0].Text) != miLine(0, "found") {
				t.Fatalf("the source must read the MI's file at its actuator: %v %d", err, len(lines))
			}
		})
	}
}

func TestLogsStreamSkipsAnUndecodableMessageAndKeepsServing(t *testing.T) {
	mi := newLogfileServer(t, miLine(0, "x"))
	role, _ := startRole(t, miAdapter{addr: mi.addr()})
	ss := &streams{}
	role.Session(session(ss, "b1"))
	st := ss.get(t, 0)

	st.errs <- fmt.Errorf("decode: %w", wire.ErrUndecodable)
	for i := range serveBound + 1 { // more exports than the bound: none may be BUSY
		id := fmt.Sprintf("e%d", i)
		role.Request(msg.LogRequest{RequestID: id, PlacementRef: "b1", Export: &msg.LogExportQuery{}})
		waitFor(t, func() bool { return role.srv.Inflight() == 1 })
		st.inject <- msg.LogCredit{RequestID: id, CreditsGranted: 1}
		if c := st.chunk(t); c.RequestID != id || !c.Final || len(c.Lines) != 1 || c.Seq != mi.size() {
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
	mi := newLogfileServer(t, miLine(0, "x"))
	role, _ := startRole(t, miAdapter{addr: mi.addr()})
	ss := &streams{}
	role.Session(session(ss, "b1"))
	st := ss.get(t, 0)
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

func TestLeaseShrinkCancelsTheExportAndRefusesThePlacement(t *testing.T) {
	mi := newLogfileServer(t, miLine(0, "kept"))
	role, _ := startRole(t, miAdapter{addr: mi.addr()})
	ss := &streams{}
	role.Session(session(ss, "b1", "b2"))
	st := ss.get(t, 0)
	role.Request(msg.LogRequest{RequestID: "e1", PlacementRef: "b1", Export: &msg.LogExportQuery{}})
	waitFor(t, func() bool { return role.srv.Inflight() == 1 })

	role.Session(session(ss, "b2")) // b1 leaves the lease, same session
	waitFor(t, func() bool { return role.srv.Inflight() == 0 })
	st.inject <- msg.LogCredit{RequestID: "e1", CreditsGranted: 5}
	select {
	case m := <-st.sent:
		t.Fatalf("a revoked export must send nothing more: %T", m)
	case <-time.After(100 * time.Millisecond):
	}
	role.Request(msg.LogRequest{RequestID: "r2", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 5}})
	if p := st.page(t); p.Unavailable != msg.UnavailablePlacementNotLease {
		t.Fatalf("%+v", p)
	}

	role.Session(session(ss, "b1", "b2")) // re-enters
	role.Request(msg.LogRequest{RequestID: "r3", PlacementRef: "b1", Page: &msg.LogPageQuery{Limit: 5}})
	if p := st.page(t); len(p.Lines) != 1 || string(p.Lines[0].Text) != miLine(0, "kept") {
		t.Fatalf("a placement back in the lease is served again: %+v", p)
	}
}
