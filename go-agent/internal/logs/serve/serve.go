// Package serve answers the DP's log requests from the MI's own log file, read when asked
// (agent-mi-logs-from-actuator design §4.3–§4.5): a PAGE request gets exactly one LogPage within
// Limits.max_message_bytes; an EXPORT request gets LogChunks within Limits.max_batch_bytes, one per
// credit, ending with a chunk whose Final is set. The file is opened for each request, and nothing read
// is kept once its reply is sent.
//
// Handle never blocks its caller on an open, a read or a send: every reply is produced on a goroutine of
// its own. Refusals carry an enum reason only (I6) — never a host, path, container name or error text —
// and no MI log text is ever written to slog or placed in an error string by this package.
package serve

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/solace/solution-manager/go-agent/internal/logs/logfile"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// LogsSender is the agent → gateway half of the Logs stream. It must be safe
// for concurrent use: several requests reply at once.
type LogsSender interface {
	Send(msg.LogsOutbound) error
}

// Source is one placement's log file, as *logfile.File reads it.
type Source interface {
	// Page returns the newest lines strictly inside (q.After, q.Before), oldest first, with at most
	// textBudget bytes of text.
	Page(ctx context.Context, q msg.LogPageQuery, textBudget int) ([]msg.LogLine, error)
	// Read returns the whole lines from byte offset from, never reading at or past until (0 = no bound),
	// charging each line its text plus logfile.LineCost within textBudget (the first line always taken);
	// prev is the time for continuation lines before the first entry start.
	Read(ctx context.Context, from, until uint64, textBudget int, prev time.Time) (logfile.Chunk, error)
}

// Opener finds a placement's log file for one request; a non-empty reason refuses it.
type Opener func(ctx context.Context, placementRef string) (Source, msg.UnavailableReason)

// DefaultMaxConcurrent bounds requests in flight; one more is answered BUSY
// (design §7: no queue).
const DefaultMaxConcurrent = 4

// DefaultReadTimeout bounds each open, each page and each export read, so that a page is answered inside
// the DP's 5 s wait (sm.dp.logs.agent-request-deadline).
const DefaultReadTimeout = 4 * time.Second

// defaultBudget stands in for a Limits value the session did not carry.
const defaultBudget = 1 << 20

// Conservative upper bounds of the protobuf framing around the payload bytes
// of a LogUp (outer oneof, envelope, request id, seq/final or unavailable; per
// line its timestamp, stream, text and truncated tags; per gap its two
// timestamps and reason). They only ever overestimate, so a reply sized by
// them fits the limit it was sized for. The per-line bound is logfile's, which
// charges each line it reads the same cost, so the two cannot drift.
const (
	headerOverhead = 64
	lineOverhead   = logfile.LineOverhead
	gapOverhead    = 48
)

type Option func(*Server)

// WithMaxConcurrent sets the in-flight bound (minimum 1).
func WithMaxConcurrent(n int) Option { return func(s *Server) { s.maxConc = max(n, 1) } }

// WithReadTimeout sets the bound on each open, page and export read; d <= 0 is ignored.
func WithReadTimeout(d time.Duration) Option {
	return func(s *Server) {
		if d > 0 {
			s.timeout = d
		}
	}
}

// WithInitialCredits sets the chunks an export may send before its first
// LogCredit (the session's Limits.initial_credits, mirroring Telemetry). The
// default is zero: nothing is sent until the gateway grants credit.
func WithInitialCredits(f func() uint32) Option { return func(s *Server) { s.initial = f } }

// WithNewID sets the envelope message-id source.
func WithNewID(f func() string) Option { return func(s *Server) { s.newID = f } }

// WithNow sets the envelope clock.
func WithNow(f func() time.Time) Option { return func(s *Server) { s.now = f } }

// Server serves page and export requests. open finds a placement's log file;
// inLease says whether the session's lease names the placement; limits
// returns the session's max_message_bytes and max_batch_bytes.
type Server struct {
	open    Opener
	inLease func(placementRef string) bool
	limits  func() (maxMsg, maxBatch uint64)
	maxConc int
	timeout time.Duration
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

func New(open Opener, inLease func(placementRef string) bool, limits func() (maxMsg, maxBatch uint64), opts ...Option) *Server {
	s := &Server{
		open: open, inLease: inLease, limits: limits,
		maxConc: DefaultMaxConcurrent,
		timeout: DefaultReadTimeout,
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
// anything is looked up; over the in-flight bound is BUSY. Only then, on the
// request's own goroutine and within the read timeout, is the placement's file
// opened, so an open never blocks Handle and BUSY comes before CONTAINER_MISSING
// or NOT_COLLECTING. A refusal is always a LogPage, for an export too, since
// LogChunk carries no reason. ctx ends the request (the session).
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
		src, why := s.openSource(rctx, req.PlacementRef)
		switch {
		case rctx.Err() != nil:
			return // cancelled, revoked or the session ended: nothing more is sent
		case why != msg.UnavailableNone:
			_ = out.Send(msg.LogPage{Envelope: s.env(), RequestID: id, Unavailable: why})
		case req.Page != nil:
			s.page(rctx, src, id, *req.Page, out)
		default:
			s.export(rctx, rq, src, id, req.Export.FromSeq, out)
		}
	}()
}

// openSource opens the placement's file within the read timeout. An opener that gives no source and no reason
// is NOT_COLLECTING.
func (s *Server) openSource(ctx context.Context, placementRef string) (Source, msg.UnavailableReason) {
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	src, why := s.open(ctx, placementRef)
	if why == msg.UnavailableNone && src == nil {
		why = msg.UnavailableNotCollecting
	}
	return src, why
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

// textBudget is the text to ask a source's Page for when a reply has room bytes for at most maxLines lines: room
// less a line's framing for each line it can hold, a line counted as at least as long as its framing (a
// default-pattern entry is longer), or as maxLines lines when that is fewer. trim stays the guarantee for a reply
// of shorter lines.
func textBudget(room int, maxLines uint32) int {
	room = max(room, 0)
	n := min(room/(2*logfile.LineCost), int(maxLines))
	return max(room-n*logfile.LineCost, 1)
}

// pageLimit is the Limit a page asks its source for: no more lines than room can carry framed, Limit 0 included,
// so that a page of short lines never builds a line it cannot send.
func pageLimit(room int, limit uint32) uint32 {
	most := uint32(max(room/logfile.LineCost, 1))
	if limit == 0 || limit > most {
		return most
	}
	return limit
}

func (s *Server) page(ctx context.Context, src Source, id string, q msg.LogPageQuery, out LogsSender) {
	limit, _ := s.budgets()
	e := s.env()
	room := limit - (headerOverhead + len(id) + len(e.MessageID))
	q.Limit = pageLimit(room, q.Limit)
	rctx, cancel := context.WithTimeout(ctx, s.timeout)
	lines, err := src.Page(rctx, q, textBudget(room, q.Limit))
	cancel()
	if ctx.Err() != nil {
		return // cancelled: the gateway no longer waits for this page
	}
	if err != nil {
		_ = out.Send(msg.LogPage{Envelope: e, RequestID: id, Unavailable: msg.UnavailableNotCollecting})
		return
	}
	lines, _ = trim(lines, nil, room, true)
	_ = out.Send(msg.LogPage{Envelope: e, RequestID: id, Lines: lines})
}

// exportPos is where an export stands in its file.
type exportPos struct {
	seq      uint64 // the next byte to read
	until    uint64 // the snapshot's end, once snapped
	snapped  bool   // the first read has reported the snapshot's size
	replaced bool   // from_seq was beyond the file: the export started again at 0
}

// export sends chunks from seq, one per credit (design §4.4). The download is a snapshot: it ends at the file
// size its first read reported, however the file grows after. A chunk's Seq is the byte offset just past its
// last line, the from_seq that resumes right after it. A from_seq beyond that size means the file was
// replaced: the export starts again at 0, its first chunk opening with a DROPPED_OLDEST gap at that chunk's
// first line's time (the lost range's times are unknown). The file shrinking below the snapshot, or a read
// failing once a chunk is out, ends the export with a final chunk carrying a COLLECTION_PAUSED gap; a read
// failing before that is refused NOT_COLLECTING, so that the DP answers with a status rather than with an
// incomplete download.
func (s *Server) export(ctx context.Context, rq *request, src Source, id string, seq uint64, out LogsSender) {
	_, limit := s.budgets()
	pos := exportPos{seq: seq}
	var lastTS time.Time // of the newest line sent
	sent := false
	for {
		if !s.take(ctx, rq) {
			return
		}
		e := s.env()
		room := limit - (headerOverhead + len(id) + len(e.MessageID))
		// Read charges each line its text plus the framing lineSize counts, so its lines fit room as they are.
		ch, shrunk, err := s.advance(ctx, src, &pos, max(room, 1), lastTS)
		if ctx.Err() != nil {
			return
		}
		lead := pos.replaced && !sent // this chunk says the lines before the new file are gone
		switch {
		case err != nil && !sent:
			_ = out.Send(msg.LogPage{Envelope: e, RequestID: id, Unavailable: msg.UnavailableNotCollecting})
			return
		case err != nil || shrunk:
			// The rest cannot be read, or is no longer the file the export began on: end cleanly, marking
			// the unread window.
			var gaps []msg.LogGap
			if lead {
				gaps = append(gaps, droppedAt(e.SentAt))
			}
			gaps = append(gaps, paused(lastTS, e.SentAt))
			_ = out.Send(msg.LogChunk{Envelope: e, RequestID: id, Seq: pos.seq, Final: true, Gaps: gaps})
			return
		}
		var gaps []msg.LogGap
		if lead {
			at := e.SentAt
			if len(ch.Lines) > 0 {
				at = ch.Lines[0].TS
			}
			gaps = []msg.LogGap{droppedAt(at)}
		}
		var lines []msg.LogLine
		lines, gaps = trim(ch.Lines, gaps, room, false)
		if n := len(lines); n > 0 {
			pos.seq = ch.Ends[n-1] // the newest lines trim dropped are read again by the next chunk
		}
		final := len(lines) == 0 || pos.seq >= pos.until
		if err := out.Send(msg.LogChunk{Envelope: e, RequestID: id, Seq: pos.seq, Lines: lines, Gaps: gaps, Final: final}); err != nil || final {
			return
		}
		lastTS, sent = lines[len(lines)-1].TS, true
	}
}

// advance reads the next chunk's lines for an export at p, within a charge of tb (see Source.Read), each read
// within the read timeout. The first read is unbounded and sets the snapshot; when it finds p.seq beyond the file,
// the file was replaced and reading starts again at 0. A read that only skipped bytes (the tail of a line begun
// before p.seq) is followed by the next on the same credit. It returns no lines once the snapshot is read, or when nothing
// complete is left in it (an unterminated last line); shrunk reports a file now smaller than the snapshot. The
// snapshot of an empty file is never read again, since until 0 means no bound.
func (s *Server) advance(ctx context.Context, src Source, p *exportPos, tb int, prev time.Time) (ch logfile.Chunk, shrunk bool, err error) {
	for {
		if p.snapped && p.seq >= p.until {
			return logfile.Chunk{Next: p.seq, Size: p.until}, false, nil
		}
		rctx, cancel := context.WithTimeout(ctx, s.timeout)
		ch, err = src.Read(rctx, p.seq, p.until, tb, prev)
		cancel()
		switch {
		case err != nil:
			return ch, false, err
		case !p.snapped:
			p.snapped, p.until = true, ch.Size
			if p.seq > ch.Size {
				p.seq, p.replaced = 0, true
				continue
			}
		case ch.Size < p.until:
			return ch, true, nil
		}
		if len(ch.Lines) > 0 || ch.Next <= p.seq {
			return ch, false, nil
		}
		p.seq = ch.Next
	}
}

// droppedAt is the DROPPED_OLDEST gap of an export that started again at 0, at time t.
func droppedAt(t time.Time) msg.LogGap {
	return msg.LogGap{From: t, To: t, Reason: msg.GapDroppedOldest}
}

// paused is the COLLECTION_PAUSED gap from the newest line sent (now, with none) to now.
func paused(last, now time.Time) msg.LogGap {
	from := last
	if from.IsZero() || from.After(now) {
		from = now
	}
	return msg.LogGap{From: from, To: now, Reason: msg.GapCollectionPaused}
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

func lineSize(l msg.LogLine) int { return lineOverhead + len(l.Stream) + len(l.Text) }

func gapSize(g msg.LogGap) int { return gapOverhead + len(g.Reason) }

func size(lines []msg.LogLine, gaps []msg.LogGap) int {
	n := 0
	for _, l := range lines {
		n += lineSize(l)
	}
	for _, g := range gaps {
		n += gapSize(g)
	}
	return n
}

// trim enforces room (the bytes left after the fixed framing). It drops lines down to one: for a page
// (fromFront) the oldest, then the rest of the time group of the last one dropped, unless that would empty
// the page, so that no trace line goes without its entry and the page asked with Before = this page's
// oldest time has what was dropped; for an export the newest, which the next chunk resumes at. Then it cuts
// that one line's text on a rune boundary, and drops gap markers (oldest first) only once the line is
// empty: a gap says what is missing, so it outranks the text of one line. The size is counted once and
// then reduced, so a page of many short lines costs no more than it holds.
func trim(lines []msg.LogLine, gaps []msg.LogGap, room int, fromFront bool) ([]msg.LogLine, []msg.LogGap) {
	total := size(lines, gaps)
	if fromFront {
		i := 0
		for len(lines)-i > 1 && total > room {
			total -= lineSize(lines[i])
			i++
		}
		if i > 0 {
			j, group, ts := i, 0, lines[i-1].TS
			for j < len(lines) && lines[j].TS.Equal(ts) {
				group += lineSize(lines[j])
				j++
			}
			if j < len(lines) {
				i, total = j, total-group
			}
		}
		lines = lines[i:]
	} else {
		n := len(lines)
		for n > 1 && total > room {
			n--
			total -= lineSize(lines[n])
		}
		lines = lines[:n]
	}
	if over := total - room; over > 0 && len(lines) == 1 {
		l := lines[0]
		text := truncRunes(l.Text, len(l.Text)-over)
		total -= len(l.Text) - len(text)
		l.Text, l.Truncated = text, true
		lines = []msg.LogLine{l}
	}
	for len(gaps) > 0 && total > room {
		total -= gapSize(gaps[0])
		gaps = gaps[1:]
	}
	return lines, gaps
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
