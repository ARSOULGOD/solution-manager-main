package agent

import (
	"context"
	"errors"
	"log/slog"
	"maps"
	"math/rand/v2"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"

	"github.com/solace/solution-manager/go-agent/internal/discovery"
	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/logs/collect"
	"github.com/solace/solution-manager/go-agent/internal/logs/ring"
	"github.com/solace/solution-manager/go-agent/internal/logs/serve"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/telemetry"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
	"github.com/solace/solution-manager/go-agent/internal/wire"
)

type TelemetryConfig struct {
	Declarations *declare.File
	BufferBytes  int64
	BufferAge    time.Duration
}

// LogsConfig configures the logs role (plan 016).
type LogsConfig struct {
	// DefaultMaxBytes is a placement's ring cap until the DP sets one
	// (SM_AGENT_LOGS_DEFAULT_MAX_BYTES); zero leaves collection off until then.
	DefaultMaxBytes uint64
	// MaxConcurrent bounds log requests in flight; zero is serve's default.
	MaxConcurrent int
}

type BuildConfig struct {
	DataDir        string
	Endpoint       string
	Token          string
	TrustBundlePEM []byte
	AgentVersion   string
	AdminAddr      string
	Adapter        runtime.Adapter
	Log            *slog.Logger
	Observe        func(reactor.Event, []reactor.Effect)
	Roles          Roles
	Telemetry      *TelemetryConfig
	Logs           *LogsConfig // used with the logs role; nil = zero LogsConfig
}

func newUUIDv7() string {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.NewString()
	}
	return id.String()
}

// fullJitter returns a uniform duration in [0, d] — "full jitter" backoff.
func fullJitter(d time.Duration) time.Duration {
	if d <= 0 {
		return 0
	}
	return time.Duration(rand.Int64N(int64(d) + 1))
}

func Build(bc BuildConfig) (*Loop, *store.Store, error) {
	s, err := store.Open(bc.DataDir)
	if err != nil {
		return nil, nil, err
	}
	id, err := identity.Load(s)
	if err != nil {
		return nil, nil, err
	}
	cfg := Config{
		BootstrapEndpoint: bc.Endpoint,
		EnrollmentToken:   bc.Token,
		AgentVersion:      bc.AgentVersion,
		Capabilities:      bc.Roles.capabilities(),
	}
	st, err := LoadState(cfg, s, id)
	if err != nil {
		return nil, nil, err
	}
	names := discovery.NewNames()
	names.Set(st.Discovery.Bindings.ByName)
	adapter := discovery.Wrap(bc.Adapter, names)
	decls := declare.NewSet()
	deps := Deps{
		Declarations: decls,
		Names:        names,
		Config:       cfg,
		Store:        s,
		Identity:     id,
		Wire:         wireAdapter{wire.New(id, bc.TrustBundlePEM)},
		Adapter:      adapter,
		NewID:        newUUIDv7,
		Jitter:       fullJitter,
		Now:          time.Now,
		Log:          bc.Log,
		Observe:      bc.Observe,
	}
	var pipe *telemetry.Pipeline
	if bc.Roles.Telemetry {
		if bc.Telemetry == nil {
			return nil, nil, errors.New("agent: the telemetry role needs its buffer settings")
		}
		// Declarations arrive on the wire (AG-D28); the file only seeds them, for development.
		decls.SeedFrom(bc.Telemetry.Declarations)
		hub := view.NewHub()
		pipe, err = telemetry.New(telemetry.Config{Store: s, Declarations: decls,
			BufferBytes: bc.Telemetry.BufferBytes, BufferAge: bc.Telemetry.BufferAge,
			Adapter: adapter, View: hub, NewID: newUUIDv7, Jitter: fullJitter, Log: bc.Log})
		if err != nil {
			return nil, nil, err
		}
		deps.View, deps.Telemetry, deps.TelemetryResume = hub, pipe, pipe.Resume
	}
	if bc.Roles.Logs {
		var lc LogsConfig
		if bc.Logs != nil {
			lc = *bc.Logs
		}
		var src collect.Source
		if f, ok := bc.Adapter.(logFollower); ok && adapter != nil {
			src = logSource{resolver: adapter, logFollower: f}
		} else {
			bc.Log.Warn("logs role: the runtime adapter cannot follow container logs; stored logs are served, nothing new is collected")
		}
		deps.Logs = newLogsRole(s, lc, src, newUUIDv7, bc.Log)
	}
	l := New(deps, st)
	if adapter != nil {
		l.d.Ops = &opRunner{adapter: adapter, store: s, push: l.Push, now: l.d.Now, offset: l.d.Offset, probe: decls.Probe}
	}
	if bc.AdminAddr != "" {
		m := NewMetrics()
		if pipe != nil {
			m.Register(pipe.Metrics().Collectors()...)
		}
		l.d.Observe = m.Observe(l)
		l.d.OnTruncated, l.d.OnDropped = m.DiscoveryTruncated, m.DeclarationsDropped
		go func() {
			if err := ServeAdmin(context.Background(), bc.AdminAddr, l, m, bc.Log); err != nil {
				bc.Log.Error("admin server", "err", err)
			}
		}()
	}
	return l, s, nil
}

type wireAdapter struct{ c *wire.Client }

func (w wireAdapter) Enroll(ctx context.Context, endpoint string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	return w.c.Enroll(ctx, endpoint, req)
}

func (w wireAdapter) Dial(ctx context.Context, endpoint string) (Conn, error) {
	c, err := w.c.Dial(ctx, endpoint)
	if err != nil {
		return nil, err
	}
	return wireConn{c}, nil
}

// wireConn adds the telemetry opener to *wire.Conn in view's types, so
// internal/wire never imports the telemetry packages.
type wireConn struct{ *wire.Conn }

func (c wireConn) OpenLogs(ctx context.Context) (LogsStream, error) {
	s, err := c.Conn.OpenLogs(ctx)
	if err != nil {
		return nil, err // never a typed-nil *LogsStream inside the interface
	}
	return s, nil
}

func (c wireConn) OpenTelemetry(ctx context.Context) (view.Stream, error) {
	s, err := c.Conn.OpenTelemetry(ctx)
	if err != nil {
		return nil, err // never a typed-nil *TelemetryStream inside the interface
	}
	return s, nil
}

// ---- the logs role (plan 016) ----

// collectorBackoff is the wait before a log collector's next try. Every
// attempt, attempt 0 included, waits at least a second: a stopped container
// ends its log stream at once, and a zero wait would spin.
func collectorBackoff(attempt int) time.Duration {
	d := min(time.Second<<min(max(attempt, 0), 5), 30*time.Second)
	return time.Second + fullJitter(d-time.Second)
}

type logFollower interface {
	FollowLogs(ctx context.Context, p runtime.Placement, since time.Time) (<-chan msg.LogLine, <-chan error)
}

// logSource is collect.Source over the runtime. The container is found through
// the discovery-wrapped Resolve, which looks the binding's registered name up
// live on every try (a re-created container keeps its placement by name) and
// falls back to the development label; so the name argument is unused.
type logSource struct {
	resolver runtime.Adapter
	logFollower
}

func (s logSource) ResolveByName(ctx context.Context, _ string, bindingID string) (runtime.Placement, error) {
	return s.resolver.Resolve(ctx, bindingID)
}

// logsDPCapDir holds one empty marker per placement whose cap the DP has set:
// from then on the configured default is never applied to it again (ring.Open
// with a non-zero cap replaces the persisted one).
const logsDPCapDir = "logs-dp-cap"

// logsRole keeps one ring per placement and a collector on each leased
// placement whose cap is above zero, and serves requests over the session's
// Logs stream. The placements are the lease's binding_ids — the same set
// telemetry treats as in-lease — and a placement ref is a binding id.
// Collectors outlive a session (logs keep being collected across reconnects);
// they stop at cap 0 or on shutdown.
//
// Session, Retention and Request only queue work; Run does it in order, so a
// request is never handled before the session it arrived on.
type logsRole struct {
	store *store.Store
	cfg   LogsConfig
	src   collect.Source // nil: nothing is collected
	log   *slog.Logger
	srv   *serve.Server

	qmu   sync.Mutex
	queue []func(context.Context)
	wake  chan struct{}

	mu    sync.Mutex // rings and cur: read by serve's goroutines
	rings map[string]*ring.Ring
	cur   *LogsSession

	backoff     func(int) time.Duration
	undecodable atomic.Uint64

	// owned by Run
	bound      map[string]bool // the last session's lease: the placements collected
	collectors map[string]*logCollector
	sess       *logsSess
}

type logCollector struct {
	cancel context.CancelFunc
	done   chan struct{}
}

type logsSess struct {
	ctx    context.Context
	cancel context.CancelFunc
	out    *logsSender
	done   chan struct{}
}

func newLogsRole(s *store.Store, cfg LogsConfig, src collect.Source, newID func() string, log *slog.Logger) *logsRole {
	r := &logsRole{store: s, cfg: cfg, src: src, log: log, wake: make(chan struct{}, 1),
		rings: map[string]*ring.Ring{}, collectors: map[string]*logCollector{}, backoff: collectorBackoff}
	opts := []serve.Option{serve.WithNewID(newID), serve.WithInitialCredits(r.initialCredits)}
	if cfg.MaxConcurrent > 0 {
		opts = append(opts, serve.WithMaxConcurrent(cfg.MaxConcurrent))
	}
	r.srv = serve.New(r.ring, r.inLease, r.limits, opts...)
	return r
}

func (r *logsRole) Session(ls *LogsSession) {
	r.enqueue(func(ctx context.Context) { r.setSession(ctx, ls) })
}

func (r *logsRole) Retention(m msg.LogRetention) {
	r.enqueue(func(ctx context.Context) { r.retain(ctx, m) })
}

func (r *logsRole) Request(m msg.LogRequest) {
	r.enqueue(func(context.Context) {
		if r.sess != nil {
			r.srv.Handle(r.sess.ctx, m, r.sess.out)
		}
	})
}

func (r *logsRole) enqueue(f func(context.Context)) {
	r.qmu.Lock()
	r.queue = append(r.queue, f)
	r.qmu.Unlock()
	select {
	case r.wake <- struct{}{}:
	default:
	}
}

func (r *logsRole) pop() func(context.Context) {
	r.qmu.Lock()
	defer r.qmu.Unlock()
	if len(r.queue) == 0 {
		return nil
	}
	f := r.queue[0]
	r.queue = r.queue[1:]
	return f
}

func (r *logsRole) Run(ctx context.Context) {
	defer r.shutdown()
	for {
		select {
		case <-ctx.Done():
			return
		case <-r.wake:
		}
		for f := r.pop(); f != nil && ctx.Err() == nil; f = r.pop() {
			f(ctx)
		}
	}
}

func (r *logsRole) shutdown() {
	r.endSession()
	for p := range r.collectors {
		r.stopCollector(p)
	}
	r.srv.Wait()
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, rg := range r.rings {
		_ = rg.Close()
	}
}

func (r *logsRole) ring(p string) (*ring.Ring, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	rg, ok := r.rings[p]
	return rg, ok
}

func (r *logsRole) inLease(p string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.cur != nil && r.cur.Bindings[p]
}

func (r *logsRole) limits() (uint64, uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cur == nil {
		return 0, 0
	}
	return r.cur.Limits.MaxMessageBytes, r.cur.Limits.MaxBatchBytes
}

func (r *logsRole) initialCredits() uint32 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cur == nil {
		return 0
	}
	return r.cur.Limits.InitialCredits
}

func (r *logsRole) setSession(ctx context.Context, ls *LogsSession) {
	if ls == nil {
		// The session ended: its stream and requests go; collectors stay, so
		// logs keep being collected across a reconnect.
		r.endSession()
		r.mu.Lock()
		r.cur = nil
		r.mu.Unlock()
		return
	}
	r.mu.Lock()
	r.cur = ls // first: a placement that left the lease is refused from here on
	r.mu.Unlock()
	for _, p := range slices.Sorted(maps.Keys(r.bound)) {
		if !ls.Bindings[p] {
			r.release(p)
		}
	}
	r.bound = maps.Clone(ls.Bindings)
	if r.sess == nil {
		r.startSession(ctx, ls)
	}
	for _, p := range slices.Sorted(maps.Keys(ls.Bindings)) {
		r.ensure(ctx, p)
	}
}

// release lets go of a placement that left the lease: its requests end, its
// collector stops and its ring is closed. The stored logs stay on disk; only a
// DP cap of 0 deletes them.
func (r *logsRole) release(p string) {
	r.srv.Revoke(p)
	r.stopCollector(p)
	r.mu.Lock()
	rg := r.rings[p]
	delete(r.rings, p)
	r.mu.Unlock()
	if rg != nil {
		_ = rg.Close()
	}
}

// startSession runs the session's Logs stream. A message that cannot be
// decoded is counted and skipped; a transport error closes the stream (Sends
// fail fast, every request ends) and the stream is reopened after a backoff
// with a one-second floor, for as long as the session lasts.
func (r *logsRole) startSession(ctx context.Context, ls *LogsSession) {
	sctx, cancel := context.WithCancel(ctx)
	out := newLogsSender()
	done := make(chan struct{})
	r.sess = &logsSess{ctx: sctx, cancel: cancel, out: out, done: done}
	go func() {
		defer close(done)
		for attempt := 0; ; {
			stctx, stcancel := context.WithCancel(sctx)
			out.opening()
			st, err := ls.Open(stctx)
			got := false
			if err == nil {
				if !out.attach(st) {
					stcancel()
					return
				}
				got, err = r.pump(st)
			}
			stcancel() // first: unblocks a Send waiting on flow control
			out.fail()
			r.srv.CancelAll() // nothing in flight can be sent any more
			if sctx.Err() != nil {
				return
			}
			if got {
				attempt = 0
			}
			attempt++
			r.log.Warn("logs stream broken; reopening", "attempt", attempt, "err", err)
			t := time.NewTimer(r.backoff(attempt))
			select {
			case <-sctx.Done():
				t.Stop()
				return
			case <-t.C:
			}
		}
	}()
}

// pump reads credits and cancels until a transport error; got says whether
// anything arrived.
func (r *logsRole) pump(st LogsStream) (got bool, err error) {
	for {
		m, err := st.Recv()
		if errors.Is(err, wire.ErrUndecodable) {
			r.undecodable.Add(1) // the stream is intact: skip it
			continue
		}
		if err != nil {
			return got, err
		}
		got = true
		switch v := m.(type) {
		case msg.LogCredit:
			r.srv.Credit(v.RequestID, v.CreditsGranted)
		case msg.LogCancel:
			r.srv.Cancel(v.RequestID)
		}
	}
}

func (r *logsRole) endSession() {
	if r.sess == nil {
		return
	}
	r.sess.cancel()
	r.srv.CancelAll()
	r.sess.out.close()
	<-r.sess.done
	r.sess = nil
}

// openRing returns the placement's ring, opening it with capIfNew (0 keeps the
// persisted cap) the first time. Only Run opens rings.
func (r *logsRole) openRing(p string, capIfNew func() uint64) (*ring.Ring, error) {
	if rg, ok := r.ring(p); ok {
		return rg, nil
	}
	rg, err := ring.Open(r.store, p, capIfNew())
	if err != nil {
		return nil, err
	}
	r.mu.Lock()
	r.rings[p] = rg
	r.mu.Unlock()
	return rg, nil
}

// ensure opens a leased placement's ring — with the configured default cap
// only while the DP has never set one — and starts its collector if the cap is
// above zero.
func (r *logsRole) ensure(ctx context.Context, p string) {
	if !ring.ValidPlacement(p) { // before any path is built from a DP-supplied id
		r.log.Warn("logs: invalid placement ref; ignored")
		return
	}
	rg, err := r.openRing(p, func() uint64 {
		if r.store.Exists(logsDPCapDir + "/" + p) {
			return 0
		}
		return r.cfg.DefaultMaxBytes
	})
	if err != nil {
		r.log.Warn("logs ring: open failed", "binding_id", p, "err", err)
		return
	}
	if rg.Cap() > 0 {
		r.startCollector(ctx, p, rg)
	}
}

// retain applies a DP-set cap: persisted (the marker first, so a crash never
// lets the default override a DP value), applied at once by the ring (a
// smaller cap trims and records CAP_REDUCED; zero deletes everything), and
// collection started or stopped to match.
func (r *logsRole) retain(ctx context.Context, m msg.LogRetention) {
	p := m.PlacementRef
	if !ring.ValidPlacement(p) {
		r.log.Warn("logs: invalid placement ref; ignored")
		return
	}
	rg, err := r.openRing(p, func() uint64 { return 0 })
	if err != nil {
		r.log.Warn("log retention refused", "binding_id", p, "err", err)
		return
	}
	if err := r.store.Save(store.Blob{RelPath: logsDPCapDir + "/" + p}); err != nil {
		r.log.Warn("log retention: persist failed", "binding_id", p, "err", err)
		return
	}
	if m.MaxBytes == 0 {
		r.stopCollector(p)
	}
	if err := rg.SetCap(m.MaxBytes); err != nil {
		r.log.Warn("log retention: apply failed", "binding_id", p, "err", err)
		return
	}
	if m.MaxBytes > 0 && r.inLease(p) {
		r.startCollector(ctx, p, rg)
	}
}

func (r *logsRole) startCollector(ctx context.Context, p string, rg *ring.Ring) {
	if c, ok := r.collectors[p]; ok {
		select {
		case <-c.done: // ended at cap 0: restart below
		default:
			return
		}
	}
	if r.src == nil {
		return
	}
	cctx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	r.collectors[p] = &logCollector{cancel: cancel, done: done}
	go func() {
		defer close(done)
		for attempt := 1; ; attempt++ {
			err := collect.New(r.src, rg, r.log, collectorBackoff).Run(cctx, "", p)
			if err == nil || cctx.Err() != nil {
				return // collection off, or stopped
			}
			// A ring write failed: collection pauses and retries (design §7).
			r.log.Warn("log collector stopped; will retry", "binding_id", p, "err", err)
			t := time.NewTimer(collectorBackoff(attempt))
			select {
			case <-cctx.Done():
				t.Stop()
				return
			case <-t.C:
			}
		}
	}()
}

func (r *logsRole) stopCollector(p string) {
	c, ok := r.collectors[p]
	if !ok {
		return
	}
	c.cancel()
	<-c.done
	delete(r.collectors, p)
}

// logsSender is the session's Logs stream as serve sends on it. While a
// stream is being opened Send waits for it (a request can arrive on Connect
// first); between streams, and once the session ended, Send fails fast. Sends
// are serialized.
type logsSender struct {
	mu     sync.Mutex
	st     LogsStream
	ready  chan struct{} // non-nil while a stream is being opened
	closed bool
}

var errNoLogsStream = errors.New("agent: no Logs stream")

func newLogsSender() *logsSender { return &logsSender{} }

func (s *logsSender) opening() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closed && s.ready == nil {
		s.ready = make(chan struct{})
	}
}

func (s *logsSender) release() {
	if s.ready != nil {
		close(s.ready)
		s.ready = nil
	}
}

func (s *logsSender) attach(st LogsStream) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		_ = st.Close()
		return false
	}
	s.st = st
	s.release()
	return true
}

// fail drops the current stream; Sends fail until the next attach.
func (s *logsSender) fail() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.st != nil {
		_ = s.st.Close()
		s.st = nil
	}
	s.release()
}

func (s *logsSender) close() {
	s.mu.Lock()
	s.closed = true
	s.mu.Unlock()
	s.fail()
}

func (s *logsSender) Send(m msg.LogsOutbound) error {
	s.mu.Lock()
	for s.ready != nil {
		r := s.ready
		s.mu.Unlock()
		<-r
		s.mu.Lock()
	}
	defer s.mu.Unlock()
	if s.st == nil {
		return errNoLogsStream
	}
	return s.st.Send(m)
}
