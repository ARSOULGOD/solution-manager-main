package agent

import (
	"context"
	"errors"
	"log/slog"
	"maps"
	"sync"
	"sync/atomic"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/logs/logfile"
	"github.com/solace/solution-manager/go-agent/internal/logs/serve"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/wire"
)

// ---- the logs role (plan 016; read from the MI's log file by plan 018) ----

// logsStreamBackoff is the wait before the Logs stream is reopened. Every
// attempt, attempt 0 included, waits at least a second, so a stream that
// breaks at once never spins.
func logsStreamBackoff(attempt int) time.Duration {
	d := min(time.Second<<min(max(attempt, 0), 5), 30*time.Second)
	return time.Second + fullJitter(d-time.Second)
}

// logsRole serves log requests over the session's Logs stream from each
// placement's own log file, which the MI serves at /actuator/logfile
// (agent-mi-logs-from-actuator design §4). The file is found for each request
// and nothing is kept, so there is no collector, no store and no work between
// requests. The placements are the lease's binding_ids — the same set
// telemetry treats as in-lease — and a placement ref is a binding id.
//
// Session, Retention and Request only queue work; Run does it in order, so a
// request is never handled before the session it arrived on.
type logsRole struct {
	adapter runtime.Adapter // the discovery-wrapped adapter; nil: every request is NOT_COLLECTING
	log     *slog.Logger
	srv     *serve.Server

	qmu   sync.Mutex
	queue []func(context.Context)
	wake  chan struct{}

	mu  sync.Mutex // cur: read by serve's goroutines
	cur *LogsSession

	backoff     func(int) time.Duration
	undecodable atomic.Uint64

	// owned by Run
	bound map[string]bool // the last session's lease: a placement that leaves it is revoked
	sess  *logsSess
}

type logsSess struct {
	ctx    context.Context
	cancel context.CancelFunc
	out    *logsSender
	done   chan struct{}
}

func newLogsRole(cfg LogsConfig, adapter runtime.Adapter, newID func() string, log *slog.Logger) *logsRole {
	r := &logsRole{adapter: adapter, log: log, wake: make(chan struct{}, 1), backoff: logsStreamBackoff}
	opts := []serve.Option{serve.WithNewID(newID), serve.WithInitialCredits(r.initialCredits)}
	if cfg.MaxConcurrent > 0 {
		opts = append(opts, serve.WithMaxConcurrent(cfg.MaxConcurrent))
	}
	r.srv = serve.New(r.open, r.inLease, r.limits, opts...)
	return r
}

func (r *logsRole) Session(ls *LogsSession) {
	r.enqueue(func(ctx context.Context) { r.setSession(ctx, ls) })
}

// Retention does nothing: the DP-set cap is gone with the store it capped
// (design §4.6), and the message goes next.
func (r *logsRole) Retention(msg.LogRetention) {}

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
	r.srv.Wait()
}

// open finds a placement's log file for one request (design §4.1): its
// container is resolved as apply resolves it (AG-D20), through the
// discovery-wrapped adapter, which also says where its actuator answers. Each
// failure is an enum reason only; its error goes nowhere (I6).
func (r *logsRole) open(ctx context.Context, placementRef string) (serve.Source, msg.UnavailableReason) {
	if r.adapter == nil {
		return nil, msg.UnavailableNotCollecting
	}
	p, err := r.adapter.Resolve(ctx, placementRef)
	switch {
	case errors.Is(err, runtime.ErrContainerNotFound):
		return nil, msg.UnavailableContainerMissing
	case err != nil:
		return nil, msg.UnavailableNotCollecting
	}
	loc, ok := r.adapter.(runtime.ActuatorLocator)
	if !ok {
		return nil, msg.UnavailableNotCollecting
	}
	addr, err := loc.ActuatorAddr(ctx, p)
	if err != nil {
		return nil, msg.UnavailableNotCollecting
	}
	return logfile.New(addr), msg.UnavailableNone
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
		// The session ended: its stream and requests go.
		r.endSession()
		r.mu.Lock()
		r.cur = nil
		r.mu.Unlock()
		return
	}
	r.mu.Lock()
	r.cur = ls // first: a placement that left the lease is refused from here on
	r.mu.Unlock()
	for p := range r.bound {
		if !ls.Bindings[p] {
			r.srv.Revoke(p) // its requests end; an export sends nothing more
		}
	}
	r.bound = maps.Clone(ls.Bindings)
	if r.sess == nil {
		r.startSession(ctx, ls)
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
