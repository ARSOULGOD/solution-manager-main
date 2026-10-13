package agent

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"maps"
	"path/filepath"
	goruntime "runtime"
	"slices"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/clock"
	"github.com/solace/solution-manager/go-agent/internal/discovery"
	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/reports"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
)

var ErrFatalPersist = errors.New("agent: persist failed; refusing to continue without durability")

type Conn interface {
	Send(msg.Outbound) error
	Recv() (msg.Inbound, error)
	Close() error
}

type Wire interface {
	Enroll(ctx context.Context, endpoint string, req msg.EnrollRequest) (*msg.EnrollResponse, error)
	Dial(ctx context.Context, endpoint string) (Conn, error)
}

// OpRunner executes one runtime operation; the loop guarantees at most one
// in flight. Task 19 supplies the Docker-backed implementation.
type OpRunner interface {
	Run(ctx context.Context, op reactor.RunOp) runtime.OpResult
}

type Deps struct {
	Config   Config
	Store    *store.Store
	Identity *identity.Identity
	Wire     Wire
	Ops      OpRunner
	Adapter  runtime.Adapter
	Clock    *clock.Clock
	Offset   func() time.Duration
	NewID    func() string
	Jitter   func(time.Duration) time.Duration
	Now      func() time.Time
	Log      *slog.Logger
	Observe  func(reactor.Event, []reactor.Effect)

	View            *view.Hub                         // nil: no telemetry role
	Telemetry       interface{ Run(context.Context) } // the pipeline; Run returns when ctx ends
	TelemetryResume func() map[string]uint64

	// Declarations is the live set the DP pushes (AG-D28): telemetry metrics and
	// the declared health probe, per binding. Names is the registered container
	// name of each binding (AG-D26). OnTruncated and OnDropped feed the counters.
	Declarations *declare.Set
	Names        *discovery.Names
	OnTruncated  func(int)
	OnDropped    func(int)
}

// loop-private events
type dialResult struct {
	at   time.Time
	conn Conn
	err  error
}
type connClosed struct {
	at  time.Time
	gen int
	err error
}

func (e dialResult) EventAt() time.Time { return e.at }
func (e connClosed) EventAt() time.Time { return e.at }

// writer commands
type flushAndClose struct{}

type Loop struct {
	d      Deps
	inbox  chan reactor.Event
	timers map[reactor.TimerKind]*time.Timer
	ops    chan reactor.RunOp

	mu     sync.Mutex
	state  State
	halted msg.ErrorCode

	conn       Conn
	connGen    int
	sendCh     chan any // msg.Outbound | flushAndClose
	writerDone chan struct{}
	// intentionalCloseGen is the connection generation whose connClosed echo
	// should be dropped, not delivered as Disconnected — set only via
	// closeConn(willRedialImmediately: true), i.e. only when we closed this
	// connection in the same batch as arming an immediate (zero-delay)
	// reconnect, so a fresh Dial is already racing to replace it.
	intentionalCloseGen int

	viewPublished bool
}

func New(d Deps, initial State) *Loop {
	if d.Now == nil {
		d.Now = time.Now
	}
	if d.Clock == nil {
		d.Clock = &clock.Clock{}
	}
	if d.Offset == nil {
		d.Offset = d.Clock.Offset
	}
	if d.Log == nil {
		d.Log = slog.Default()
	}
	return &Loop{
		d:      d,
		inbox:  make(chan reactor.Event, 1024),
		timers: map[reactor.TimerKind]*time.Timer{},
		ops:    make(chan reactor.RunOp, 16),
		state:  initial,
	}
}

func (l *Loop) Push(ev reactor.Event) { l.inbox <- ev }

// Snapshot returns an isolated copy of the current state, safe to read after
// this call returns with no further synchronization — see cloneState.
func (l *Loop) Snapshot() State {
	l.mu.Lock()
	defer l.mu.Unlock()
	return cloneState(l.state)
}

// cloneState deep-copies st's mutable-map fields rather than aliasing them,
// so the result is safe to use outside l.mu (Snapshot's whole point) and to
// hand to another goroutine.
//
// Session's fields are plain values. Authority's Grant/Pending are
// pointer-swapped wholesale on every change (authority.acceptGrant,
// sendRenew) rather than ever mutated through an existing pointer, so an
// older State's pointer stays valid and frozen even after a newer grant
// replaces it — safe to share as-is. Commands.Journal (Journal.Put) and
// Reports' Cursor/Registry (observe/ack) are the opposite: those maps are
// created once at Load and only ever added to in place, never reallocated,
// so every State value this Loop ever produces — including one captured by
// Snapshot or handed to a spawned goroutine — aliases the very same maps for
// the process's whole life. Without this clone, reading them from anywhere
// but the loop goroutine races the loop goroutine's next Reduce (caught by
// -race: reports.State.ack's write vs. a Snapshot-polling test's read, both
// on Registry.ReplicaIDs).
func cloneState(st State) State {
	st.Commands.Journal.Records = maps.Clone(st.Commands.Journal.Records)
	st.Commands.Journal.Watermark = maps.Clone(st.Commands.Journal.Watermark)
	st.Commands.Pulling = maps.Clone(st.Commands.Pulling)
	st.Commands.Spool = maps.Clone(st.Commands.Spool)
	st.Discovery.Bindings.ByName = maps.Clone(st.Discovery.Bindings.ByName)
	st.Commands.Queue = slices.Clone(st.Commands.Queue)
	st.Reports.Cursor.Sequences = maps.Clone(st.Reports.Cursor.Sequences)
	st.Reports.Registry.ReplicaIDs = maps.Clone(st.Reports.Registry.ReplicaIDs)
	st.Reports.Registry.IncarnationIDs = maps.Clone(st.Reports.Registry.IncarnationIDs)
	st.Reports.Pending.Items = slices.Clone(st.Reports.Pending.Items)
	return st
}

func (l *Loop) Halted() msg.ErrorCode {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.halted
}

func (l *Loop) Clock() *clock.Clock { return l.d.Clock }

func (l *Loop) Run(ctx context.Context) error {
	// Every exit — shutdown or a fatal persist — must release what Run
	// starts: the ctx watcher (waiting on a context the caller may never
	// cancel), the worker (parked on range l.ops) and any live timer (which
	// would otherwise post into an inbox nobody reads). One defer, so no exit
	// path can skip it.
	ctx, cancel := context.WithCancel(ctx)
	var tele sync.WaitGroup
	defer func() {
		cancel()
		l.stopTimers()
		close(l.ops)
		tele.Wait() // Loop.Run releases every goroutine it starts, on every exit path
	}()
	if l.d.Telemetry != nil {
		tele.Go(func() { l.d.Telemetry.Run(ctx) })
	}
	go l.worker(ctx)
	go func() {
		<-ctx.Done()
		l.Push(reactor.Shutdown{At: l.d.Now(), Cause: ctx.Err()})
	}()
	l.Push(reactor.Resumed{At: l.d.Now()})

	for raw := range l.inbox {
		ev, ok := l.translate(raw)
		if !ok {
			continue
		}
		if w, isWire := ev.(reactor.WireIn); isWire {
			switch m := w.Msg.(type) {
			case msg.HeartbeatAck:
				l.d.Clock.Update(m.ClockOffsetMs, m.ClockState)
			case msg.SessionAccept:
				if !m.ServerClock.IsZero() {
					l.d.Clock.Set(m.ServerClock, w.At)
				}
			case msg.LeaseGrant:
				if !m.ServerClock.IsZero() {
					l.d.Clock.Set(m.ServerClock, w.At)
				}
			case msg.TelemetryDeclarations:
				l.declare(m)
			}
		}
		// l.mu covers the Reduce call itself, not just the state swap: the
		// commands and reports reducers mutate their journal/cursor/registry
		// maps in place (see cloneState), so Snapshot's clone must be unable
		// to run concurrently with this — only holding the lock around the
		// swap leaves that mutation unprotected.
		l.mu.Lock()
		st := l.state
		env := reactor.Env{Now: ev.EventAt(), Offset: l.d.Offset(), NewID: l.d.NewID, Jitter: l.d.Jitter, Limits: st.Session.Limits}
		next, effs := Reduce(l.d.Config, st, ev, env)
		l.state = next
		l.mu.Unlock()
		if l.d.Names != nil {
			l.d.Names.Set(next.Discovery.Bindings.ByName)
		}
		l.publish(st, next, ev)
		effs = reactor.Sort(effs)
		if l.d.Observe != nil {
			l.d.Observe(ev, effs)
		}
		if in, ok := ev.(reactor.WireIn); ok {
			// Which protocol message (type only, never its contents) — makes a stalled exchange diagnosable.
			l.d.Log.Debug("event", "type", fmt.Sprintf("%T", ev), "msg", fmt.Sprintf("%T", in.Msg), "effects", len(effs))
		} else {
			l.d.Log.Debug("event", "type", fmt.Sprintf("%T", ev), "effects", len(effs))
		}
		if err := l.execute(ctx, effs); err != nil {
			l.closeConn(false)
			return err
		}
		if obs, ok := l.observationFor(ev, next); ok {
			l.Push(reactor.Observed{At: l.d.Now(), Obs: obs})
		}
		if tf, ok := ev.(reactor.TimerFired); ok && tf.Kind == reactor.ReportDue {
			go l.observeAll(ctx, next, persistedGenerations(next))
			l.scanLater(ctx)
		}
		if d, ok := ev.(reactor.Discovered); ok && d.Truncated > 0 && l.d.OnTruncated != nil {
			l.d.OnTruncated(d.Truncated)
		}
		if w, ok := ev.(reactor.WireIn); ok {
			if _, accepted := w.Msg.(msg.SessionAccept); accepted {
				l.scanLater(ctx) // a full snapshot after every SessionAccept (AG-D26 item 2)
			}
		}
		if _, isShutdown := ev.(reactor.Shutdown); isShutdown {
			return nil
		}
	}
	return nil
}

// observationFor turns a finished operation into an actual-state observation.
// It runs after the event was reduced, so the journal record is terminal and
// PersistedGeneration is the durable one.
func (l *Loop) observationFor(ev reactor.Event, st State) (reports.Observation, bool) {
	fin, ok := ev.(reactor.OpFinished)
	if !ok || fin.Result.Facts == nil || fin.Result.Placement == nil {
		return reports.Observation{}, false
	}
	f, p := fin.Result.Facts, fin.Result.Placement
	obs := reports.Observation{
		BindingID: p.BindingID, ReplicaRef: f.ReplicaRef, Incarnation: f.Incarnation,
		PersistedGeneration: st.Commands.PersistedGeneration(p.BindingID),
		EffectiveHash:       fin.Result.EffectiveHash,
		VersionObservation:  versionOf(f),
	}
	if record, ok := st.Commands.Journal.ByCommand(fin.CommandID); ok {
		obs.Correlation = record.Correlation
	}
	if f.Running && !f.StartedAt.IsZero() {
		at := f.StartedAt.Add(l.d.Clock.Offset()) // host time → DP time
		obs.RestartObservedAt = &at
	}
	if !f.StartedAt.IsZero() {
		started := f.StartedAt // the host's clock, uncorrected (OR-112 same-clock rule)
		obs.ContainerStartedAt = &started
	}
	obs.Health = fin.Result.Health
	return obs, true
}

// versionOf reports what the runtime shows and nothing more (AG-D12).
func versionOf(f *runtime.ReplicaFacts) msg.VersionObservation {
	if f.ImageDigest == "" {
		return msg.VersionObservation{Mechanism: "none", Confidence: "none"}
	}
	return msg.VersionObservation{Mechanism: "image-digest", Value: f.ImageDigest, Confidence: "low"}
}

// translate turns loop-private events into public ones, dropping stale
// connection events.
func (l *Loop) translate(raw reactor.Event) (reactor.Event, bool) {
	switch e := raw.(type) {
	case dialResult:
		if e.err != nil {
			return reactor.Disconnected{At: e.at, Cause: e.err}, true
		}
		l.attach(e.conn)
		return reactor.Connected{At: e.at, TelemetryResume: l.telemetryResume()}, true
	case connClosed:
		// The second condition catches only the immediate-redial echo (see
		// closeConn/intentionalCloseGen) — any other intentional close is
		// already safe via the phase guard in session.go's Disconnected case,
		// so it is delivered normally rather than suppressed here.
		if e.gen != l.connGen || e.gen == l.intentionalCloseGen {
			return nil, false
		}
		l.closeConn(false) // the peer dropped us: stop the writer too, never just dereference
		return reactor.Disconnected{At: e.at, Cause: e.err}, true
	default:
		return raw, true
	}
}

func (l *Loop) attach(c Conn) {
	l.closeConn(false)
	l.connGen++
	gen := l.connGen
	l.conn = c
	l.sendCh = make(chan any, 256)
	l.writerDone = make(chan struct{})
	go l.writer(c, l.sendCh, l.writerDone)
	go func() {
		for {
			m, err := c.Recv()
			if err != nil {
				l.Push(connClosed{at: l.d.Now(), gen: gen, err: err})
				return
			}
			l.Push(reactor.WireIn{At: l.d.Now(), Msg: m})
		}
	}()
}

func (l *Loop) writer(c Conn, ch chan any, done chan struct{}) {
	defer close(done)
	for item := range ch {
		switch v := item.(type) {
		case flushAndClose:
			_ = c.Close()
			return
		case msg.Outbound:
			if err := c.Send(v); err != nil {
				l.d.Log.Warn("send failed", "err", err)
			}
		}
	}
}

// closeConn tears down the active connection. willRedialImmediately marks
// this generation's eventual connClosed echo for suppression (see
// intentionalCloseGen) — set only when the effects batch that produced this
// Close also armed a zero-delay ReconnectDue timer, meaning a new Dial is
// about to race the echo. A Close not paired with an immediate reconnect
// (Drain's real-delay timer, Shutdown, Revoke, a halt) must NOT suppress:
// Drain relies on the echo to leave PhaseDraining, and the others are
// already safe because the reducer transitioned to an ignore-listed phase
// (Closed/Halted) before the echo can arrive.
func (l *Loop) closeConn(willRedialImmediately bool) {
	if l.conn == nil {
		return
	}
	if willRedialImmediately {
		l.intentionalCloseGen = l.connGen
	}
	l.sendCh <- flushAndClose{}
	close(l.sendCh)
	<-l.writerDone
	l.conn = nil
}

func (l *Loop) execute(ctx context.Context, effs []reactor.Effect) error {
	// A same-batch, zero-delay ReconnectDue timer means the reducer is about
	// to redial immediately after this Close — see closeConn.
	immediateRedial := false
	for _, eff := range effs {
		if a, ok := eff.(reactor.ArmTimer); ok && a.Kind == reactor.ReconnectDue && a.After == 0 {
			immediateRedial = true
			break
		}
	}
	for _, eff := range effs {
		switch e := eff.(type) {
		case reactor.Persist:
			if err := l.d.Store.Save(e.Rec); err != nil {
				l.d.Log.Error("persist failed", "rel", e.Rec.Rel(), "err", err)
				return fmt.Errorf("%w: %s: %v", ErrFatalPersist, e.Rec.Rel(), err)
			}
		case reactor.Delete:
			del := l.d.Store.Delete
			if filepath.Ext(e.Rel) == "" {
				del = l.d.Store.DeleteAll
			}
			if err := del(e.Rel); err != nil {
				return fmt.Errorf("%w: delete %s: %v", ErrFatalPersist, e.Rel, err)
			}
		case reactor.Send:
			if l.conn == nil {
				l.d.Log.Warn("send with no connection", "type", fmt.Sprintf("%T", e.Msg))
				continue
			}
			l.sendCh <- e.Msg
		case reactor.RunOp:
			l.ops <- e
		case reactor.ArmTimer:
			l.arm(e.Kind, e.After)
		case reactor.CancelTimer:
			if t := l.timers[e.Kind]; t != nil {
				t.Stop()
				delete(l.timers, e.Kind)
			}
		case reactor.Enroll:
			go l.enroll(ctx, e.Endpoint)
		case reactor.Dial:
			go func() {
				c, err := l.d.Wire.Dial(ctx, e.Endpoint)
				l.Push(dialResult{at: l.d.Now(), conn: c, err: err})
			}()
		case reactor.Close:
			l.closeConn(immediateRedial)
		case reactor.Halt:
			l.mu.Lock()
			l.halted = e.Code
			l.mu.Unlock()
			l.d.Log.Error("halted", "code", e.Code)
		}
	}
	return nil
}

func (l *Loop) arm(kind reactor.TimerKind, after time.Duration) {
	if t := l.timers[kind]; t != nil {
		t.Stop()
	}
	l.timers[kind] = time.AfterFunc(after, func() {
		l.Push(reactor.TimerFired{At: l.d.Now(), Kind: kind})
	})
}

func (l *Loop) stopTimers() {
	for k, t := range l.timers {
		t.Stop()
		delete(l.timers, k)
	}
}

func (l *Loop) enroll(ctx context.Context, endpoint string) {
	csr, err := l.d.Identity.CSR()
	if err != nil {
		l.Push(reactor.EnrollResult{At: l.d.Now(), Err: err})
		return
	}
	req := msg.EnrollRequest{
		Token:                     l.d.Config.EnrollmentToken,
		CSRDER:                    csr,
		AgentVersion:              l.d.Config.AgentVersion,
		ProtocolVersionsSupported: []uint32{protocolVersion},
		HostFacts:                 msg.HostFacts{OS: goruntime.GOOS, Arch: goruntime.GOARCH, RuntimeKind: "docker"},
	}
	resp, err := l.d.Wire.Enroll(ctx, endpoint, req)
	if err == nil {
		err = l.d.Identity.StoreEnrollment(*resp)
	}
	if err != nil {
		// Operators otherwise see only backoff timers. The error never carries the token: it is
		// the transport/coded status, and the token travels only as request metadata.
		l.d.Log.Warn("enroll failed; will retry", "endpoint", endpoint, "err", err)
	}
	l.Push(reactor.EnrollResult{At: l.d.Now(), Resp: resp, Err: err})
}

func (l *Loop) worker(ctx context.Context) {
	for op := range l.ops {
		if l.d.Ops == nil {
			l.d.Log.Error("RunOp with no OpRunner", "command", op.CommandID)
			continue
		}
		res := l.d.Ops.Run(ctx, op)
		l.Push(reactor.OpFinished{At: l.d.Now(), CommandID: op.CommandID, Result: res})
	}
}

// persistedGenerations snapshots Commands.PersistedGeneration for every bound
// binding while still on the loop goroutine. Unlike Authority.Grant (replaced
// wholesale on each new grant — see acceptGrant — so an older State's Grant
// pointer is never mutated after another State snapshot moves on), the
// commands journal's underlying map is mutated in place by Journal.Put and is
// never reallocated, so every State copy for the process's life aliases the
// same map. Reading it through st.Commands from observeAll's own goroutine
// would race the loop goroutine's next journal write; computing the values
// here, before the goroutine starts, keeps that read on the loop goroutine
// where it is safe and hands the new goroutine only a private copy.
func persistedGenerations(st State) map[string]uint64 {
	if st.Authority.Grant == nil {
		return nil
	}
	out := make(map[string]uint64, len(st.Authority.Grant.BindingIDs))
	for _, binding := range st.Authority.Grant.BindingIDs {
		out[binding] = st.Commands.PersistedGeneration(binding)
	}
	return out
}

// observeAll asks the adapter for current facts for every binding the lease
// names, so a healthy unchanged workload still reports (AG-D18 / AGW-19).
// persistedGen is precomputed on the loop goroutine (see persistedGenerations)
// — this function and everything it calls must only read st.Authority.Grant
// (safe: pointer-swapped, never mutated in place) and persistedGen (a private
// copy), call the adapter, and l.Push; it must never touch st.Commands or any
// Loop field directly.
func (l *Loop) observeAll(ctx context.Context, st State, persistedGen map[string]uint64) {
	if l.d.Adapter == nil || st.Authority.Grant == nil {
		return
	}
	for _, binding := range st.Authority.Grant.BindingIDs {
		p, err := l.d.Adapter.Resolve(ctx, binding)
		if err != nil {
			l.d.Log.Warn("periodic report: resolve", "binding", binding, "err", err)
			continue
		}
		f, err := l.d.Adapter.Observe(ctx, p)
		if err != nil {
			l.d.Log.Warn("periodic report: observe", "binding", binding, "err", err)
			continue
		}
		obs := reports.Observation{BindingID: binding, ReplicaRef: f.ReplicaRef, Incarnation: f.Incarnation,
			PersistedGeneration: persistedGen[binding], VersionObservation: versionOf(&f)}
		if f.Running && !f.StartedAt.IsZero() {
			at := f.StartedAt.Add(l.d.Clock.Offset()) // host time → DP time
			obs.RestartObservedAt = &at
		}
		if !f.StartedAt.IsZero() {
			started := f.StartedAt // host clock, no offset
			obs.ContainerStartedAt = &started
		}
		if ho, ok := l.d.Adapter.(runtime.HealthObserver); ok {
			var probe *msg.HealthProbe
			if l.d.Declarations != nil {
				probe = l.d.Declarations.Probe(binding) // nil: nothing is probed (OR-101)
			}
			if h := ho.ObserveHealth(ctx, p, probe); h.State != msg.HealthUnspecified {
				obs.Health = &h
			}
		}
		l.Push(reactor.Observed{At: l.d.Now(), Obs: obs})
	}
}

// declare applies a TelemetryDeclarations: the binding's complete set replaces the
// previous one atomically (AG-D28). A message that fails validation changes
// nothing; the log carries the binding id and the reason, never the metrics.
func (l *Loop) declare(m msg.TelemetryDeclarations) {
	if l.d.Declarations == nil {
		return
	}
	dropped, err := l.d.Declarations.Replace(m)
	if err != nil {
		l.d.Log.Warn("telemetry declarations refused; the previous set stays", "binding", m.BindingID, "err", err)
		return
	}
	if dropped > 0 && l.d.OnDropped != nil {
		l.d.OnDropped(dropped)
	}
}

// scanLater lists the opted-in containers off the loop goroutine and feeds the
// result back as a Discovered event; the discovery reducer sends a
// DiscoveryReport only when the set changed.
func (l *Loop) scanLater(ctx context.Context) {
	d, ok := l.d.Adapter.(runtime.Discoverer)
	if !ok || !slices.Contains(l.d.Config.Capabilities, msg.CapDiscovery) {
		return
	}
	go func() {
		items, truncated, err := d.Discover(ctx)
		if errors.Is(err, discovery.ErrUnsupported) {
			return
		}
		if err != nil {
			l.d.Log.Warn("discovery scan failed", "err", err)
			return
		}
		l.Push(reactor.Discovered{At: l.d.Now(), Items: items, Truncated: truncated})
	}()
}

func (l *Loop) telemetryResume() map[string]uint64 {
	if l.d.TelemetryResume == nil {
		return nil
	}
	return l.d.TelemetryResume()
}

// publish hands the telemetry pipeline a new view when what it reads may have
// changed: the session phase or id, the lease (replaced wholesale on every
// grant), or the id registry (changed only by a ReportAck). It runs on the
// loop goroutine, the only writer of these maps, so taking IDTable is safe.
func (l *Loop) publish(prev, next State, ev reactor.Event) {
	if l.d.View == nil {
		return
	}
	reportAck := false
	if w, ok := ev.(reactor.WireIn); ok {
		_, reportAck = w.Msg.(msg.ReportAck)
	}
	if l.viewPublished && !reportAck && prev.Session.Phase == next.Session.Phase &&
		prev.Session.SessionID == next.Session.SessionID && prev.Authority.Grant == next.Authority.Grant {
		return
	}
	l.viewPublished = true
	snap := view.Snapshot{IDs: next.Reports.IDTable(), Halted: next.Session.Phase == PhaseHalted, Bindings: map[string]bool{}}
	if g := next.Authority.Grant; g != nil {
		for _, b := range g.BindingIDs {
			snap.Bindings[b] = true
		}
	}
	if next.Session.Phase == PhaseActive && l.conn != nil {
		snap.Session = &view.SessionInfo{ID: next.Session.SessionID, Limits: next.Session.Limits, Open: telemetryOpener(l.conn)}
	}
	l.d.View.Publish(snap)
}

type telemetryConn interface {
	OpenTelemetry(ctx context.Context) (view.Stream, error)
}

// telemetryOpener binds the opener to this connection: after a reconnect a
// stale opener fails instead of opening on the new connection behind the
// sender's back.
func telemetryOpener(c Conn) func(context.Context) (view.Stream, error) {
	return func(ctx context.Context) (view.Stream, error) {
		tc, ok := c.(telemetryConn)
		if !ok {
			return nil, errors.New("agent: this connection carries no Telemetry stream")
		}
		return tc.OpenTelemetry(ctx)
	}
}
