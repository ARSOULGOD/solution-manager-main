// Package serve answers the DP's log requests from the per-placement rings
// (agent-mi-logs design §3 serving, §4 wire, §7 errors): a PAGE request gets
// exactly one LogPage within Limits.max_message_bytes; an EXPORT request gets
// LogChunks within Limits.max_batch_bytes, one per credit, ending with a chunk
// whose Final is set.
//
// Handle never blocks its caller on a read or a send: every reply is produced
// on a goroutine of its own. Refusals carry an enum reason only (I6) — never a
// host, path, container name or error text — and no MI log text is ever
// written to slog or placed in an error string by this package.
package serve

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/solace/solution-manager/go-agent/internal/logs/ring"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// LogsSender is the agent → gateway half of the Logs stream. It must be safe
// for concurrent use: several requests reply at once.
type LogsSender interface {
	Send(msg.LogsOutbound) error
}

// DefaultMaxConcurrent bounds requests in flight; one more is answered BUSY
// (design §7: no queue).
const DefaultMaxConcurrent = 4

// defaultBudget stands in for a Limits value the session did not carry.
const defaultBudget = 1 << 20

// Conservative upper bounds of the protobuf framing around the payload bytes
// of a LogUp (outer oneof, envelope, request id, seq/final or unavailable; per
// line its timestamp, stream, text and truncated tags; per gap its two
// timestamps and reason). They only ever overestimate, so a reply sized by
// them fits the limit it was sized for.
const (
	headerOverhead = 64
	lineOverhead   = 40
	gapOverhead    = 48
	streamGuess    = 8 // "stdout"/"stderr"; only steers the first read
)

type Option func(*Server)

// WithMaxConcurrent sets the in-flight bound (minimum 1).
func WithMaxConcurrent(n int) Option { return func(s *Server) { s.maxConc = max(n, 1) } }

// WithInitialCredits sets the chunks an export may send before its first
// LogCredit (the session's Limits.initial_credits, mirroring Telemetry). The
// default is zero: nothing is sent until the gateway grants credit.
func WithInitialCredits(f func() uint32) Option { return func(s *Server) { s.initial = f } }

// WithNewID sets the envelope message-id source.
func WithNewID(f func() string) Option { return func(s *Server) { s.newID = f } }

// WithNow sets the envelope clock.
func WithNow(f func() time.Time) Option { return func(s *Server) { s.now = f } }

// Server serves page and export requests. rings returns a placement's open
// ring; inLease says whether the session's lease names the placement; limits
// returns the session's max_message_bytes and max_batch_bytes.
type Server struct {
	rings   func(placementRef string) (*ring.Ring, bool)
	inLease func(placementRef string) bool
	limits  func() (maxMsg, maxBatch uint64)
	maxConc int
	initial func() uint32
	newID   func() string
	now     func() time.Time

	mu      sync.Mutex
	active  map[string]*request
	ignored atomic.Uint64
	wg      sync.WaitGroup
}

type request struct {
	placement string
	cancel    context.CancelFunc
	export    bool
	credits   uint32
	wake      chan struct{} // capacity 1: a credit arrived
}

func New(rings func(placementRef string) (*ring.Ring, bool), inLease func(placementRef string) bool, limits func() (maxMsg, maxBatch uint64), opts ...Option) *Server {
	s := &Server{
		rings: rings, inLease: inLease, limits: limits,
		maxConc: DefaultMaxConcurrent,
		initial: func() uint32 { return 0 },
		newID:   uuid.NewString,
		now:     time.Now,
		active:  map[string]*request{},
	}
	for _, o := range opts {
		o(s)
	}
	return s
}

// Handle admits one request and returns at once. Order of the checks: a
// malformed request or a request id already in flight is ignored and counted;
// a placement outside the lease is refused PLACEMENT_NOT_IN_LEASE before
// anything is looked up; one with no ring or a zero cap is NOT_COLLECTING; over
// the in-flight bound is BUSY. A refusal is always a LogPage, for an export
// too, since LogChunk carries no reason. ctx ends the request (the session).
func (s *Server) Handle(ctx context.Context, req msg.LogRequest, out LogsSender) {
	id := req.RequestID
	if id == "" || (req.Page == nil) == (req.Export == nil) || s.inFlight(id) {
		s.ignored.Add(1)
		return
	}
	if !s.inLease(req.PlacementRef) {
		s.refuse(out, id, msg.UnavailablePlacementNotLease)
		return
	}
	r, ok := s.rings(req.PlacementRef)
	if !ok || r == nil || r.Cap() == 0 {
		s.refuse(out, id, msg.UnavailableNotCollecting)
		return
	}
	s.mu.Lock()
	if _, dup := s.active[id]; dup {
		s.mu.Unlock()
		s.ignored.Add(1)
		return
	}
	if len(s.active) >= s.maxConc {
		s.mu.Unlock()
		s.refuse(out, id, msg.UnavailableBusy)
		return
	}
	rctx, cancel := context.WithCancel(ctx)
	rq := &request{placement: req.PlacementRef, cancel: cancel, export: req.Export != nil, wake: make(chan struct{}, 1)}
	if rq.export {
		rq.credits = s.initial()
	}
	s.active[id] = rq
	s.wg.Add(1)
	s.mu.Unlock()
	go func() {
		defer s.wg.Done()
		defer s.finish(id, rq)
		if req.Page != nil {
			s.page(rctx, r, id, *req.Page, out)
		} else {
			s.export(rctx, rq, r, id, req.Export.FromSeq, out)
		}
	}()
}

// Credit grants an export n more chunks. A credit for an id not in flight (or
// for a page) is ignored and counted.
func (s *Server) Credit(requestID string, n uint32) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rq := s.active[requestID]
	if rq == nil || !rq.export {
		s.ignored.Add(1)
		return
	}
	rq.credits = uint64ToU32(uint64(rq.credits) + uint64(n))
	select {
	case rq.wake <- struct{}{}:
	default:
	}
}

func uint64ToU32(v uint64) uint32 {
	if v > uint64(^uint32(0)) {
		return ^uint32(0)
	}
	return uint32(v)
}

// Cancel stops a request and frees its state at once. An unknown id is
// ignored and counted.
func (s *Server) Cancel(requestID string) {
	s.mu.Lock()
	rq := s.active[requestID]
	delete(s.active, requestID)
	s.mu.Unlock()
	if rq == nil {
		s.ignored.Add(1)
		return
	}
	rq.cancel()
}

// CancelAll ends every request (the session ended).
func (s *Server) CancelAll() {
	s.mu.Lock()
	all := s.active
	s.active = map[string]*request{}
	s.mu.Unlock()
	for _, rq := range all {
		rq.cancel()
	}
}

// Revoke ends every request for a placement that left the lease: an export
// admitted before the revocation sends nothing more. Not counted as ignored.
func (s *Server) Revoke(placementRef string) {
	s.mu.Lock()
	var gone []*request
	for id, rq := range s.active {
		if rq.placement == placementRef {
			gone = append(gone, rq)
			delete(s.active, id)
		}
	}
	s.mu.Unlock()
	for _, rq := range gone {
		rq.cancel()
	}
}

// Wait blocks until every reply goroutine has returned.
func (s *Server) Wait() { s.wg.Wait() }

// Ignored counts duplicate, malformed and unknown request ids.
func (s *Server) Ignored() uint64 { return s.ignored.Load() }

// Inflight is the number of admitted requests not yet finished.
func (s *Server) Inflight() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.active)
}

func (s *Server) inFlight(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.active[id]
	return ok
}

func (s *Server) finish(id string, rq *request) {
	s.mu.Lock()
	if s.active[id] == rq {
		delete(s.active, id)
	}
	s.mu.Unlock()
	rq.cancel()
}

func (s *Server) env() msg.Envelope { return msg.Envelope{MessageID: s.newID(), SentAt: s.now()} }

func (s *Server) refuse(out LogsSender, id string, why msg.UnavailableReason) {
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		_ = out.Send(msg.LogPage{Envelope: s.env(), RequestID: id, Unavailable: why})
	}()
}

func (s *Server) budgets() (int, int) {
	m, b := s.limits()
	return budget(m), budget(b)
}

func budget(v uint64) int {
	if v == 0 || v > 1<<30 {
		return defaultBudget
	}
	return int(v)
}

func (s *Server) page(ctx context.Context, r *ring.Ring, id string, q msg.LogPageQuery, out LogsSender) {
	limit, _ := s.budgets()
	e := s.env()
	fixed := headerOverhead + len(id) + len(e.MessageID)
	lines, gaps, err := fitRead(limit, fixed, func(text int) ([]msg.LogLine, []msg.LogGap, error) {
		return r.Page(q.After, q.Before, int(q.Limit), text)
	})
	if ctx.Err() != nil {
		return // cancelled: the gateway no longer waits for this page
	}
	if err != nil {
		_ = out.Send(msg.LogPage{Envelope: e, RequestID: id, Unavailable: msg.UnavailableNotCollecting})
		return
	}
	lines, gaps, _ = trim(lines, gaps, limit-fixed, true)
	_ = out.Send(msg.LogPage{Envelope: e, RequestID: id, Lines: lines, Gaps: gaps})
}

// export sends chunks from seq, one per credit. A chunk's Seq is its resume
// point: the from_seq that continues right after it. A cap reduced under the
// export shows as the ring's CAP_REDUCED gap marker; collection turned off
// (cap 0) ends the export with one.
func (s *Server) export(ctx context.Context, rq *request, r *ring.Ring, id string, seq uint64, out LogsSender) {
	_, limit := s.budgets()
	var lastTS time.Time
	for {
		if !s.take(ctx, rq) {
			return
		}
		e := s.env()
		window := func(reason string) []msg.LogGap {
			from := lastTS
			if from.IsZero() || from.After(e.SentAt) {
				from = e.SentAt
			}
			return []msg.LogGap{{From: from, To: e.SentAt, Reason: reason}}
		}
		if r.Cap() == 0 {
			_ = out.Send(msg.LogChunk{Envelope: e, RequestID: id, Seq: seq, Final: true, Gaps: window(msg.GapCapReduced)})
			return
		}
		fixed := headerOverhead + len(id) + len(e.MessageID)
		var next uint64
		var done bool
		lines, gaps, err := fitRead(limit, fixed, func(text int) ([]msg.LogLine, []msg.LogGap, error) {
			ls, gs, n, d, err := r.ExportFrom(seq, text)
			next, done = n, d
			return ls, gs, err
		})
		if err != nil {
			// The rest could not be read: end cleanly, marking the unread window.
			_ = out.Send(msg.LogChunk{Envelope: e, RequestID: id, Seq: seq, Final: true, Gaps: window(msg.GapCollectionPaused)})
			return
		}
		var dropped int
		lines, gaps, dropped = trim(lines, gaps, limit-fixed, false)
		if dropped > 0 {
			next, done = next-uint64(dropped), false
		}
		if len(lines) == 0 && !done && next <= seq {
			done = true // no progress possible: never spin
		}
		if ctx.Err() != nil {
			return
		}
		if err := out.Send(msg.LogChunk{Envelope: e, RequestID: id, Seq: next, Lines: lines, Gaps: gaps, Final: done}); err != nil || done {
			return
		}
		if n := len(lines); n > 0 {
			lastTS = lines[n-1].TS
		}
		seq = next
	}
}

// take spends one credit, waiting for one; false when ctx ends first.
func (s *Server) take(ctx context.Context, rq *request) bool {
	for {
		s.mu.Lock()
		if rq.credits > 0 {
			rq.credits--
			s.mu.Unlock()
			return ctx.Err() == nil
		}
		s.mu.Unlock()
		select {
		case <-ctx.Done():
			return false
		case <-rq.wake:
		}
	}
}

func size(lines []msg.LogLine, gaps []msg.LogGap) int {
	n := 0
	for _, l := range lines {
		n += lineOverhead + len(l.Stream) + len(l.Text)
	}
	for _, g := range gaps {
		n += gapOverhead + len(g.Reason)
	}
	return n
}

func textLen(lines []msg.LogLine) int {
	n := 0
	for _, l := range lines {
		n += len(l.Text)
	}
	return n
}

// fitRead calls read with a shrinking text budget until the estimated message
// fits limit. The ring counts only text against its budget; the framing is
// added here. Each retry shrinks the budget strictly, so it ends; trim is the
// final guarantee for what the retries could not fix.
func fitRead(limit, fixed int, read func(textBudget int) ([]msg.LogLine, []msg.LogGap, error)) ([]msg.LogLine, []msg.LogGap, error) {
	tb := limit - fixed - lineOverhead - streamGuess
	var lines []msg.LogLine
	var gaps []msg.LogGap
	for range 8 {
		var err error
		lines, gaps, err = read(max(tb, 1))
		if err != nil {
			return nil, nil, err
		}
		over := fixed + size(lines, gaps) - limit
		if over <= 0 || tb <= 1 {
			break
		}
		tb = min(textLen(lines)-over, tb-1)
	}
	return lines, gaps, nil
}

// trim enforces room (the bytes left after the fixed framing): it drops
// lines — the oldest for a page (fromFront), the newest for an export, which
// resumes there — down to one, then cuts that one line's text on a rune
// boundary, and drops gap markers (oldest first) only once the line is empty:
// a gap says what is missing, so it outranks the text of one line. dropped
// counts lines dropped from the end.
func trim(lines []msg.LogLine, gaps []msg.LogGap, room int, fromFront bool) ([]msg.LogLine, []msg.LogGap, int) {
	dropped := 0
	for len(lines) > 1 && size(lines, gaps) > room {
		if fromFront {
			lines = lines[1:]
		} else {
			lines = lines[:len(lines)-1]
			dropped++
		}
	}
	if over := size(lines, gaps) - room; over > 0 && len(lines) == 1 {
		l := lines[0]
		l.Text, l.Truncated = truncRunes(l.Text, len(l.Text)-over), true
		lines = []msg.LogLine{l}
	}
	for len(gaps) > 0 && size(lines, gaps) > room {
		gaps = gaps[1:]
	}
	return lines, gaps, dropped
}

// truncRunes cuts b to at most n bytes on a rune boundary.
func truncRunes(b []byte, n int) []byte {
	if n >= len(b) {
		return b
	}
	n = max(n, 0)
	for n > 0 && !utf8.RuneStart(b[n]) {
		n--
	}
	return b[:n]
}
