package agent

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"runtime"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
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
	Run(ctx context.Context, op reactor.RunOp) any
}

type Deps struct {
	Config   Config
	Store    *store.Store
	Identity *identity.Identity
	Wire     Wire
	Ops      OpRunner
	Offset   func() time.Duration
	NewID    func() string
	Jitter   func(time.Duration) time.Duration
	Now      func() time.Time
	Log      *slog.Logger
	Observe  func(reactor.Event, []reactor.Effect)
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

	conn    Conn
	connGen int
	sendCh  chan any // msg.Outbound | flushAndClose
	writerDone chan struct{}
	intentionalCloseGen int
}

func New(d Deps, initial State) *Loop {
	if d.Now == nil {
		d.Now = time.Now
	}
	if d.Offset == nil {
		d.Offset = func() time.Duration { return 0 }
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

func (l *Loop) Snapshot() State {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.state
}

func (l *Loop) Halted() msg.ErrorCode {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.halted
}

func (l *Loop) Run(ctx context.Context) error {
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
		l.mu.Lock()
		st := l.state
		l.mu.Unlock()
		env := reactor.Env{Now: ev.EventAt(), Offset: l.d.Offset(), NewID: l.d.NewID, Jitter: l.d.Jitter, Limits: st.Session.Limits}
		next, effs := Reduce(l.d.Config, st, ev, env)
		effs = reactor.Sort(effs)
		l.mu.Lock()
		l.state = next
		l.mu.Unlock()
		if l.d.Observe != nil {
			l.d.Observe(ev, effs)
		}
		l.d.Log.Debug("event", "type", fmt.Sprintf("%T", ev), "effects", len(effs))
		if err := l.execute(ctx, effs); err != nil {
			l.closeConn()
			return err
		}
		if _, isShutdown := ev.(reactor.Shutdown); isShutdown {
			l.stopTimers()
			return nil
		}
	}
	return nil
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
		return reactor.Connected{At: e.at}, true
	case connClosed:
		if e.gen != l.connGen || e.gen == l.intentionalCloseGen {
			return nil, false
		}
		l.closeConn() // the peer dropped us: stop the writer too, never just dereference
		return reactor.Disconnected{At: e.at, Cause: e.err}, true
	default:
		return raw, true
	}
}

func (l *Loop) attach(c Conn) {
	l.closeConn()
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

func (l *Loop) closeConn() {
	if l.conn == nil {
		return
	}
	l.intentionalCloseGen = l.connGen
	l.sendCh <- flushAndClose{}
	close(l.sendCh)
	<-l.writerDone
	l.conn = nil
}

func (l *Loop) execute(ctx context.Context, effs []reactor.Effect) error {
	for _, eff := range effs {
		switch e := eff.(type) {
		case reactor.Persist:
			if err := l.d.Store.Save(e.Rec); err != nil {
				l.d.Log.Error("persist failed", "rel", e.Rec.Rel(), "err", err)
				return fmt.Errorf("%w: %s: %v", ErrFatalPersist, e.Rec.Rel(), err)
			}
		case reactor.Delete:
			if err := l.d.Store.Delete(e.Rel); err != nil {
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
			l.closeConn()
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
		CSRDER:                    csr,
		AgentVersion:              l.d.Config.AgentVersion,
		ProtocolVersionsSupported: []uint32{protocolVersion},
		HostFacts:                 msg.HostFacts{OS: runtime.GOOS, Arch: runtime.GOARCH, RuntimeKind: "docker"},
	}
	resp, err := l.d.Wire.Enroll(ctx, endpoint, req)
	if err == nil {
		err = l.d.Identity.StoreEnrollment(*resp)
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
