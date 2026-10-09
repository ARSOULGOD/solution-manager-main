package send

import (
	"context"
	"log/slog"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/tmetrics"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
)

// Runner executes Reduce's effects. One goroutine — Run — owns the state and
// the stream; the stream's reader posts back through in.
type Runner struct {
	Buf       *buffer.Buffer
	View      view.View
	NewID     func() string
	Now       func() time.Time
	Jitter    func(time.Duration) time.Duration
	Log       *slog.Logger
	Metrics   *tmetrics.Metrics
	BufferAge time.Duration // --telemetry-buffer-age, for the push interval's bound (AG-D26)

	state   State
	session *view.SessionInfo
	stream  view.Stream
	gen     int // bumped per OpenStream; stale stream events are dropped
	cancel  context.CancelFunc
	retry   *time.Timer
	push    *time.Ticker // fires PushDue; stopped while the push interval is 0
	in      chan any
	queue   []Event
	dead    bool // a Send failed on this stream: skip Sends until the next OpenStream
}

type (
	opened struct {
		gen int
		s   view.Stream
	}
	fromStream struct {
		gen int
		ev  Event
	}
)

// wakeEvery bounds how long a time-based decision (UNREGISTERED) can wait
// when nothing else wakes the runner.
const wakeEvery = 5 * time.Second

func (r *Runner) Run(ctx context.Context) {
	r.state = NewState()
	// Everything stored before the agent started is already due (spec §3.4).
	r.state.DueBy, r.state.BufferAge = r.Now(), r.BufferAge
	r.in = make(chan any, 64)
	r.retry = time.NewTimer(time.Hour)
	r.retry.Stop()
	r.push = time.NewTicker(time.Hour)
	r.push.Stop()
	defer r.push.Stop()
	tick := time.NewTicker(wakeEvery)
	defer tick.Stop()
	defer r.closeStream()
	// Each channel is replaced before the work its firing triggers reads the
	// view or the buffer, and kept across unrelated wakeups: re-taking both on
	// every pass would miss a change that lands between two passes.
	viewCh, bufCh := r.View.Changed(), r.Buf.Changed()
	r.onView(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-viewCh:
			viewCh = r.View.Changed()
			r.onView(ctx)
		case <-bufCh:
			bufCh = r.Buf.Changed()
			r.step(ctx, Wake{})
		case <-tick.C:
			r.step(ctx, Wake{})
		case <-r.retry.C:
			r.step(ctx, Wake{})
		case <-r.push.C:
			r.step(ctx, PushDue{})
		case m := <-r.in:
			switch m := m.(type) {
			case opened:
				if m.gen != r.gen {
					_ = m.s.Close()
					continue
				}
				r.stream = m.s
				go r.read(ctx, m.gen, m.s)
				r.step(ctx, StreamOpened{})
			case fromStream:
				if m.gen == r.gen {
					r.step(ctx, m.ev)
				}
			}
		}
	}
}

func (r *Runner) onView(ctx context.Context) {
	snap := r.View.Current()
	switch {
	case snap.Session != nil && snap.Session.ID != r.state.SessionID:
		r.session = snap.Session
		r.step(ctx, SessionUp{ID: snap.Session.ID, Limits: snap.Session.Limits})
	case snap.Session == nil && r.state.SessionID != "":
		r.session = nil
		r.step(ctx, SessionDown{})
	default:
		r.step(ctx, Wake{}) // ids or bindings may have changed
	}
}

// step reduces ev and executes its effects. An effect that must feed a new
// event back (a failed send) queues it, and step drains the queue in order.
func (r *Runner) step(ctx context.Context, ev Event) {
	r.queue = append(r.queue, ev)
	for len(r.queue) > 0 {
		ev := r.queue[0]
		r.queue = r.queue[1:]
		snap := r.View.Current()
		if a, isAck := ev.(Ack); isAck {
			// Mirrors the reducer's Ack case; counted before Reduce because a
			// send selected in the same reduction would cancel it out.
			if seq, ok := r.state.InFlight[a.Stream]; ok && seq <= a.HWM {
				r.Metrics.AckedBatches(1)
			}
		}
		next, effs := Reduce(r.state, ev, Env{Now: r.Now(), Heads: r.heads(snap), Jitter: r.Jitter})
		r.state = next
		r.Metrics.SetCredits(next.Credits)
		for _, e := range effs {
			r.exec(ctx, e, snap)
		}
	}
}

func (r *Runner) heads(snap view.Snapshot) []Head {
	var out []Head
	for _, h := range r.Buf.Heads() {
		known := len(h.Refs) > 0
		for _, ref := range h.Refs {
			if _, _, ok := snap.Lookup(h.Stream, ref.ReplicaRef, ref.Incarnation); !ok {
				known = false
				break
			}
		}
		out = append(out, Head{Stream: h.Stream, Seq: h.Seq, ProducedAt: h.ProducedAt, Bytes: h.Bytes, IDsKnown: known})
	}
	return out // Buf.Heads is already oldest-first
}

func (r *Runner) exec(ctx context.Context, e Effect, snap view.Snapshot) {
	switch e := e.(type) {
	case OpenStream:
		r.dead = false
		r.closeStream()
		r.gen++
		gen, info := r.gen, r.session
		sctx, cancel := context.WithCancel(ctx)
		r.cancel = cancel
		go func() {
			if e.After > 0 {
				t := time.NewTimer(e.After)
				select {
				case <-sctx.Done():
					t.Stop()
					return
				case <-t.C:
				}
			}
			if info == nil {
				return
			}
			s, err := info.Open(sctx)
			if err != nil {
				r.Log.Debug("telemetry stream open failed", "err", err)
				r.post(sctx, fromStream{gen: gen, ev: StreamBroken{}})
				return
			}
			r.post(sctx, opened{gen: gen, s: s})
		}()
	case CloseStream:
		r.closeStream()
	case Send:
		if r.dead {
			return // one break is one failure: the StreamBroken already queued requeues this send
		}
		if !r.Buf.MarkInFlight(e.Stream, e.Seq) {
			r.queue = append(r.queue, Unsendable{Stream: e.Stream})
			return
		}
		b, err := r.Buf.Read(e.Stream, e.Seq)
		if err != nil {
			// The file is unreadable now and will be on every retry: the same
			// class of fault the buffer drops as CORRUPT on load. No contents logged (I6).
			r.Log.Warn("telemetry batch unreadable; dropping as a gap", "stream", e.Stream, "seq", e.Seq, "err", err)
			r.Buf.DropAsGap(e.Stream, e.Seq, buffer.ReasonCorrupt) // also clears the in-flight mark
			r.queue = append(r.queue, Unsendable{Stream: e.Stream})
			return
		}
		m, ok := r.message(b, snap)
		if !ok {
			r.Buf.ClearInFlight(e.Stream)
			r.queue = append(r.queue, Unsendable{Stream: e.Stream})
			return
		}
		if r.stream == nil || r.stream.Send(m) != nil {
			r.dead = true
			r.closeStream()
			r.queue = append(r.queue, StreamBroken{})
			return
		}
		r.Metrics.Sent()
	case Trim:
		r.Buf.Trim(e.Stream, e.HWM)
	case DropAsGap:
		r.Buf.DropAsGap(e.Stream, e.Seq, e.Reason)
	case Requeue:
		r.Buf.ClearInFlight(e.Stream)
	case ArmRetry:
		r.retry.Reset(time.Until(e.At))
	case ArmPush:
		if e.Every > 0 {
			r.push.Reset(e.Every) // Reset panics on a non-positive duration
		} else {
			r.push.Stop()
		}
	}
}

// message labels every sample with exactly the three DP-minted ids
// (gateway §6.1) and attaches the gaps this batch must carry.
func (r *Runner) message(b buffer.Batch, snap view.Snapshot) (msg.TelemetryBatch, bool) {
	out := msg.TelemetryBatch{Envelope: msg.Envelope{MessageID: r.NewID(), SentAt: r.Now()}, StreamID: b.StreamID, BatchSeq: b.Seq, ProducedAt: b.ProducedAt}
	for _, s := range b.Samples {
		rid, iid, ok := snap.Lookup(b.StreamID, s.ReplicaRef, s.Incarnation)
		if !ok {
			return out, false
		}
		out.Samples = append(out.Samples, msg.Sample{SeriesKey: s.Name, Type: msg.MetricType(s.Type), TS: s.TS, Value: s.Value,
			Labels: map[string]string{"binding_id": b.StreamID, "replica_id": rid, "incarnation_id": iid}})
	}
	for _, g := range r.Buf.GapsBefore(b.StreamID, b.Seq) {
		out.GapMarkers = append(out.GapMarkers, msg.GapMarker{FromSeq: g.From, ToSeq: g.To, Reason: g.Reason})
	}
	return out, true
}

func (r *Runner) read(ctx context.Context, gen int, s view.Stream) {
	for {
		m, err := s.Recv()
		if err != nil {
			r.post(ctx, fromStream{gen: gen, ev: StreamBroken{}})
			return
		}
		var ev Event
		switch m := m.(type) {
		case msg.TelemetryAck:
			ev = Ack{Stream: m.StreamID, HWM: m.HighWatermarkSeq, Credits: m.CreditsGranted}
		case msg.TelemetryCredit:
			ev = Credit{N: m.CreditsGranted}
		case msg.TelemetryReject:
			r.Metrics.Reject(string(m.Code))
			var ra time.Duration
			if m.RetryAfter != nil {
				ra = *m.RetryAfter
			}
			ev = Reject{Stream: m.StreamID, Code: m.Code, RetryAfter: ra}
		default:
			continue
		}
		r.post(ctx, fromStream{gen: gen, ev: ev})
	}
}

func (r *Runner) post(ctx context.Context, m any) {
	select {
	case r.in <- m:
	case <-ctx.Done():
	}
}

func (r *Runner) closeStream() {
	if r.cancel != nil {
		r.cancel()
		r.cancel = nil
	}
	if r.stream != nil {
		_ = r.stream.Close()
		r.stream = nil
	}
}
