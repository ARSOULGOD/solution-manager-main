// Package send is the telemetry sender (AG-D21, AG-D23): a pure reducer over
// credits, in-flight batches and the stream's state, and a runner (runner.go)
// that executes its effects against the buffer and the Telemetry stream.
package send

import (
	"maps"
	"slices"
	"strings"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"
)

// Head is a buffered stream's oldest unsent batch, as the runner read it.
type Head struct {
	Stream     string
	Seq        uint64
	ProducedAt time.Time
	Bytes      int64
	IDsKnown   bool
}

// Env carries every impurity into Reduce.
type Env struct {
	Now    time.Time
	Heads  []Head // each is a stream's oldest unsent batch, oldest produced_at first; a stream with a batch in flight in the buffer is not listed
	Jitter func(time.Duration) time.Duration
}

type State struct {
	SessionID      string
	Up             bool
	UpSince        time.Time
	Credits        uint32
	MaxBatchBytes  uint64
	ReportInterval time.Duration
	Failures       int
	InFlight       map[string]uint64
	Acked          map[string]uint64 // highest watermark seen per stream: filters stale heads
	RetryAt        map[string]time.Time
	PushInterval   time.Duration // AG-D30: 0 sends each batch at once; otherwise batches wait for a push tick
	DueBy          time.Time     // a batch produced at or before it is due
	BufferAge      time.Duration // the age cap a push interval must stay below; set once by the runner
}

func NewState() State {
	return State{InFlight: map[string]uint64{}, Acked: map[string]uint64{}, RetryAt: map[string]time.Time{}}
}

type Event interface{ event() }

type (
	SessionUp struct {
		ID     string
		Limits msg.Limits
	}
	SessionDown  struct{}
	StreamOpened struct{}
	StreamBroken struct{}
	Ack          struct {
		Stream  string
		HWM     uint64
		Credits uint32
	}
	Credit struct{ N uint32 }
	Reject struct {
		Stream     string
		Code       msg.ErrorCode
		RetryAfter time.Duration
	}
	Unsendable struct{ Stream string } // the runner could not send what was selected
	Wake       struct{}                // the buffer, the view or a timer changed
	PushDue    struct{}                // the push ticker fired (AG-D30)
)

func (SessionUp) event()    {}
func (SessionDown) event()  {}
func (StreamOpened) event() {}
func (StreamBroken) event() {}
func (Ack) event()          {}
func (Credit) event()       {}
func (Reject) event()       {}
func (Unsendable) event()   {}
func (Wake) event()         {}
func (PushDue) event()      {}

type Effect interface{ effect() }

type (
	OpenStream  struct{ After time.Duration }
	CloseStream struct{}
	Send        struct {
		Stream string
		Seq    uint64
	}
	Trim struct {
		Stream string
		HWM    uint64
	}
	DropAsGap struct {
		Stream string
		Seq    uint64
		Reason string
	}
	Requeue  struct{ Stream string }
	ArmRetry struct{ At time.Time }
	ArmPush  struct{ Every time.Duration } // reset the push ticker; 0 stops it
)

func (OpenStream) effect()  {}
func (CloseStream) effect() {}
func (Send) effect()        {}
func (Trim) effect()        {}
func (DropAsGap) effect()   {}
func (Requeue) effect()     {}
func (ArmRetry) effect()    {}
func (ArmPush) effect()     {}

// terminal rejects: the same bytes can never be accepted, so retrying is
// pointless and the batch becomes a gap (spec §5.2).
var terminal = map[msg.ErrorCode]bool{
	msg.ErrPayloadTooLarge: true, msg.ErrUnknownReplica: true, msg.ErrIncarnationRetired: true,
	msg.ErrUndeclaredLabel: true, msg.ErrLabelInvalid: true, msg.ErrBindingNotInLease: true, msg.ErrTenantMismatch: true,
}

const (
	backoffBase  = time.Second
	backoffCap   = 30 * time.Second
	retryDefault = time.Second
)

func backoff(failures int) time.Duration {
	d := backoffBase << max(failures-1, 0)
	if d <= 0 || d > backoffCap {
		return backoffCap
	}
	return d
}

func (s State) clone() State {
	s.InFlight, s.Acked, s.RetryAt = maps.Clone(s.InFlight), maps.Clone(s.Acked), maps.Clone(s.RetryAt)
	if s.InFlight == nil {
		s.InFlight = map[string]uint64{}
	}
	if s.Acked == nil {
		s.Acked = map[string]uint64{}
	}
	if s.RetryAt == nil {
		s.RetryAt = map[string]time.Time{}
	}
	return s
}

// requeueAll returns every in-flight batch to unsent, in stream order.
func (s *State) requeueAll() []Effect {
	var effs []Effect
	for _, st := range slices.Sorted(maps.Keys(s.InFlight)) {
		effs = append(effs, Requeue{Stream: st})
	}
	clear(s.InFlight)
	return effs
}

// Reduce applies an event to a state and returns the new state and effects.
// Reject relies on Env.Heads omitting every stream with a batch in flight in the buffer,
// which ensures a terminal reject does not re-send the dropped batch.
func Reduce(in State, ev Event, env Env) (State, []Effect) {
	s := in.clone()
	var effs []Effect
	switch e := ev.(type) {
	case SessionUp:
		if e.ID == s.SessionID {
			break
		}
		if s.SessionID != "" {
			effs = append(effs, s.requeueAll()...)
			effs = append(effs, CloseStream{})
		}
		s.SessionID, s.Up, s.Failures = e.ID, false, 0
		s.Credits = e.Limits.InitialCredits // the old session's credits are void
		s.MaxBatchBytes, s.ReportInterval = e.Limits.MaxBatchBytes, e.Limits.ReportInterval
		effs = append(effs, OpenStream{})
		// Resolved here, from the limits this event carries, not delivered by a
		// separate event that would race it (spec §4.1).
		if push, _ := cadence.Resolve(e.Limits, s.BufferAge); push != s.PushInterval {
			if s.PushInterval == 0 {
				s.DueBy = env.Now // stored under immediate push: all of it is already due
			}
			s.PushInterval = push
			effs = append(effs, ArmPush{Every: push})
		}
	case SessionDown:
		if s.SessionID == "" {
			break
		}
		effs = append(effs, s.requeueAll()...)
		effs = append(effs, CloseStream{})
		s.SessionID, s.Up, s.Credits = "", false, 0
	case StreamOpened:
		if s.SessionID == "" {
			break
		}
		s.Up, s.UpSince, s.Failures = true, env.Now, 0
	case StreamBroken:
		if s.SessionID == "" {
			break
		}
		// Each in-flight batch held one credit its ack would have returned;
		// the session keeps its pool across a stream break (AG-17 assumption).
		s.Credits += uint32(len(s.InFlight))
		effs = append(effs, s.requeueAll()...)
		s.Up = false
		s.Failures++
		effs = append(effs, CloseStream{}, OpenStream{After: env.Jitter(backoff(s.Failures))})
	case Ack:
		s.Acked[e.Stream] = max(s.Acked[e.Stream], e.HWM)
		effs = append(effs, Trim{Stream: e.Stream, HWM: e.HWM})
		if seq, ok := s.InFlight[e.Stream]; ok && seq <= e.HWM {
			delete(s.InFlight, e.Stream)
		}
		s.Credits += e.Credits
	case Credit:
		s.Credits += e.N
	case Reject:
		seq, ok := s.InFlight[e.Stream]
		if !ok {
			break
		}
		delete(s.InFlight, e.Stream)
		switch {
		case e.Code == msg.ErrOutOfCredits:
			s.Credits = 0
			effs = append(effs, Requeue{Stream: e.Stream})
		case terminal[e.Code]:
			s.Credits++ // refused before the credit was accepted (gateway §5.5)
			effs = append(effs, DropAsGap{Stream: e.Stream, Seq: seq, Reason: string(e.Code)})
		default:
			s.Credits++
			ra := e.RetryAfter
			if ra <= 0 {
				ra = retryDefault
			}
			s.RetryAt[e.Stream] = env.Now.Add(ra)
			effs = append(effs, Requeue{Stream: e.Stream})
		}
	case Unsendable:
		if _, ok := s.InFlight[e.Stream]; ok {
			delete(s.InFlight, e.Stream)
			s.Credits++
			s.RetryAt[e.Stream] = env.Now.Add(retryDefault)
		}
	case PushDue:
		s.DueBy = env.Now
	case Wake:
	}
	return s, append(effs, s.selectSends(env)...)
}

// selectSends spends credits on the oldest eligible head across streams.
// Drops (oversize, unregistered) cost no credit. Stale heads — at or below a
// watermark this reduction already acked — are skipped, because the buffer
// trims only when the effect runs.
func (s *State) selectSends(env Env) []Effect {
	if !s.Up {
		return nil
	}
	var effs []Effect
	var nextRetry time.Time
	for _, h := range env.Heads {
		_, busy := s.InFlight[h.Stream]
		switch {
		case h.Seq <= s.Acked[h.Stream] || busy:
			continue
		case env.Now.Before(s.RetryAt[h.Stream]):
			if nextRetry.IsZero() || s.RetryAt[h.Stream].Before(nextRetry) {
				nextRetry = s.RetryAt[h.Stream]
			}
			continue
		case s.MaxBatchBytes > 0 && uint64(h.Bytes) > s.MaxBatchBytes:
			effs = append(effs, DropAsGap{Stream: h.Stream, Seq: h.Seq, Reason: buffer.ReasonOversize})
			continue
		case !h.IDsKnown:
			// The wait runs from the later of stream open and the batch's own
			// production time, so a batch collected long after the stream opened
			// still gets its full two report intervals for the next report.
			since := s.UpSince
			if h.ProducedAt.After(since) {
				since = h.ProducedAt
			}
			if env.Now.Sub(since) > s.unregisteredAfter() {
				effs = append(effs, DropAsGap{Stream: h.Stream, Seq: h.Seq, Reason: buffer.ReasonUnregistered})
			}
			continue
		case s.PushInterval > 0 && h.ProducedAt.After(s.DueBy):
			continue // stored, not yet due (AG-D30)
		case s.Credits == 0:
			continue
		}
		s.Credits--
		s.InFlight[h.Stream] = h.Seq
		effs = append(effs, Send{Stream: h.Stream, Seq: h.Seq})
	}
	if !nextRetry.IsZero() {
		effs = append(effs, ArmRetry{At: nextRetry})
	}
	return effs
}

// unregisteredAfter: ids still unknown after two report intervals of an open
// stream will not resolve — the report that would mint them has had two
// chances (Review Focus 1).
func (s State) unregisteredAfter() time.Duration {
	ri := s.ReportInterval
	if ri <= 0 {
		ri = time.Minute
	}
	return 2 * ri
}

// sortHeads orders heads oldest produced_at first, then by stream — the order
// Env.Heads must arrive in.
func sortHeads(hs []Head) {
	slices.SortFunc(hs, func(x, y Head) int {
		if c := x.ProducedAt.Compare(y.ProducedAt); c != 0 {
			return c
		}
		return strings.Compare(x.Stream, y.Stream)
	})
}
