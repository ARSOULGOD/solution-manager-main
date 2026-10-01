// Package buffer is the AG-D9 telemetry buffer (AG-D24): one file per batch
// under <data-dir>/telemetry/<stream>/, bounded by bytes and age across all
// streams, dropping the oldest batch first and recording every drop as a gap
// marker the stream's next batch carries. It survives agent restart.
package buffer

import (
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/store"
)

// Gap reasons (proposed for the wire as AG-16).
const (
	ReasonDroppedOldest = "DROPPED_OLDEST"
	ReasonExpired       = "EXPIRED"
	ReasonOversize      = "OVERSIZE"
	ReasonUnregistered  = "UNREGISTERED"
	ReasonCorrupt       = "CORRUPT"
)

const root = "telemetry"

var safeID = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

// validID: a stream id is a path segment under telemetry/.
func validID(id string) bool { return id != "." && id != ".." && safeID.MatchString(id) }

type Sample struct {
	Name        string    `json:"name"`
	Type        string    `json:"type"`
	TS          time.Time `json:"ts"`
	Value       float64   `json:"value"`
	ReplicaRef  string    `json:"replica_ref"`
	Incarnation string    `json:"incarnation"`
}

type Batch struct {
	SchemaVersion int       `json:"schema_version"`
	StreamID      string    `json:"stream_id"`
	Seq           uint64    `json:"seq"`
	ProducedAt    time.Time `json:"produced_at"`
	Samples       []Sample  `json:"samples"`
}

func (b Batch) Version() int { return b.SchemaVersion }

type Gap struct {
	From   uint64 `json:"from_seq"`
	To     uint64 `json:"to_seq"`
	Reason string `json:"reason"`
}

type streamState struct {
	SchemaVersion int    `json:"schema_version"`
	AckedSeq      uint64 `json:"acked_seq"`
	Gaps          []Gap  `json:"gaps"`
}

func (s streamState) Version() int { return s.SchemaVersion }

// Ref is one (replica_ref, incarnation) pair a batch's samples name.
type Ref struct{ ReplicaRef, Incarnation string }

// Head is a stream's oldest unsent batch, as the sender sees it.
type Head struct {
	Stream     string
	Seq        uint64
	ProducedAt time.Time
	Bytes      int64
	Refs       []Ref
}

type Stats struct {
	Bytes   int64
	Batches int
	Oldest  time.Time // zero when empty
}

type Options struct {
	MaxBytes     int64         // 0 = no byte cap
	MaxAge       time.Duration // 0 = no age cap
	Now          func() time.Time
	Save         func(store.Record) error // default: the store's Save; tests inject failures
	OnDrop       func(reason string, samples int)
	OnWriteError func(error)
}

type meta struct {
	seq      uint64
	bytes    int64
	produced time.Time
	samples  int
	refs     []Ref
}

type stream struct {
	id       string
	state    streamState
	files    []meta // ascending seq
	next     uint64
	inflight uint64 // 0 = none
}

type Buffer struct {
	mu      sync.Mutex
	st      *store.Store
	opt     Options
	streams map[string]*stream
	total   int64
	changed chan struct{}
}

func seqName(seq uint64) string             { return fmt.Sprintf("%020d.json", seq) }
func stateRel(id string) string             { return root + "/" + id + "/state.json" }
func batchRel(id string, seq uint64) string { return root + "/" + id + "/" + seqName(seq) }

func seqOf(name string) (uint64, bool) {
	base, ok := strings.CutSuffix(name, ".json")
	if !ok || len(base) != 20 {
		return 0, false
	}
	n, err := strconv.ParseUint(base, 10, 64)
	return n, err == nil
}

func metaOf(b Batch, size int64) meta {
	m := meta{seq: b.Seq, bytes: size, produced: b.ProducedAt, samples: len(b.Samples)}
	seen := map[Ref]bool{}
	for _, s := range b.Samples {
		r := Ref{ReplicaRef: s.ReplicaRef, Incarnation: s.Incarnation}
		if !seen[r] {
			seen[r] = true
			m.refs = append(m.refs, r)
		}
	}
	return m
}

func Open(st *store.Store, opt Options) (*Buffer, error) {
	if opt.Now == nil {
		opt.Now = time.Now
	}
	if opt.Save == nil {
		opt.Save = st.Save
	}
	if opt.OnDrop == nil {
		opt.OnDrop = func(string, int) {}
	}
	if opt.OnWriteError == nil {
		opt.OnWriteError = func(error) {}
	}
	b := &Buffer{st: st, opt: opt, streams: map[string]*stream{}, changed: make(chan struct{})}
	entries, err := os.ReadDir(st.Path(root))
	if errors.Is(err, os.ErrNotExist) {
		return b, nil
	}
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		if e.IsDir() && validID(e.Name()) {
			if err := b.load(e.Name()); err != nil {
				return nil, err
			}
		}
	}
	return b, nil
}

func (b *Buffer) load(id string) error {
	s := &stream{id: id, state: streamState{SchemaVersion: 1}}
	if st, err := store.LoadJSON[streamState](b.st, stateRel(id), 1); err == nil {
		s.state = st
	} else if !errors.Is(err, store.ErrNotFound) {
		// Unreadable state: report it and start from the default; telemetry must not stop the agent.
		b.opt.OnWriteError(err)
	}
	entries, err := os.ReadDir(b.st.Path(root + "/" + id))
	if err != nil {
		return err
	}
	for _, e := range entries {
		seq, ok := seqOf(e.Name())
		if !ok {
			continue
		}
		if seq <= s.state.AckedSeq { // trimmed in state before a crash finished the deletes
			if err := b.st.Delete(batchRel(id, seq)); err != nil {
				b.opt.OnWriteError(err)
			}
			continue
		}
		batch, err := store.LoadJSON[Batch](b.st, batchRel(id, seq), 1)
		if err != nil {
			// Telemetry must not stop the agent starting: the batch is lost, and said so.
			if err := b.st.Delete(batchRel(id, seq)); err != nil {
				b.opt.OnWriteError(err)
			}
			s.addGap(seq, ReasonCorrupt)
			b.opt.OnDrop(ReasonCorrupt, 0)
			continue
		}
		info, err := e.Info()
		if err != nil {
			return err
		}
		s.files = append(s.files, metaOf(batch, info.Size()))
	}
	slices.SortFunc(s.files, func(x, y meta) int { return cmp.Compare(x.seq, y.seq) })
	s.next = s.state.AckedSeq + 1
	for _, g := range s.state.Gaps {
		s.next = max(s.next, g.To+1)
	}
	if n := len(s.files); n > 0 {
		s.next = max(s.next, s.files[n-1].seq+1)
	}
	for _, f := range s.files {
		b.total += f.bytes
	}
	b.streams[id] = s
	b.saveState(s)
	return nil
}

func (b *Buffer) stream(id string) *stream {
	s := b.streams[id]
	if s == nil {
		s = &stream{id: id, state: streamState{SchemaVersion: 1}, next: 1}
		b.streams[id] = s
	}
	return s
}

// Append persists one tick's samples as the stream's next batch, then
// enforces the caps. A failed write drops the batch and is reported, never
// fatal (AG-D25); WriteAtomic leaves no partial file, so its seq is reused.
func (b *Buffer) Append(id string, producedAt time.Time, samples []Sample) (uint64, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !validID(id) {
		return 0, fmt.Errorf("buffer: invalid stream id %q", id)
	}
	s := b.stream(id)
	batch := Batch{SchemaVersion: 1, StreamID: id, Seq: s.next, ProducedAt: producedAt.UTC(), Samples: samples}
	data, err := json.Marshal(batch)
	if err == nil {
		err = b.opt.Save(store.Blob{RelPath: batchRel(id, batch.Seq), Data: data})
	}
	if err != nil {
		b.opt.OnWriteError(err)
		return 0, err
	}
	s.next++
	s.files = append(s.files, metaOf(batch, int64(len(data))))
	b.total += int64(len(data))
	b.enforce()
	b.notify()
	return batch.Seq, nil
}

// enforce applies the age cap, then the byte cap, dropping the oldest batch
// across all streams first. A batch produced in the future (the clock stepped
// back) has a negative age: never expired early, but still counted against
// the byte cap.
func (b *Buffer) enforce() {
	now := b.opt.Now()
	for {
		s, i, ok := b.oldest()
		if !ok {
			return
		}
		switch {
		case b.opt.MaxAge > 0 && now.Sub(s.files[i].produced) > b.opt.MaxAge:
			b.drop(s, i, ReasonExpired)
		case b.opt.MaxBytes > 0 && b.total > b.opt.MaxBytes:
			b.drop(s, i, ReasonDroppedOldest)
		default:
			return
		}
	}
}

// oldest is the batch with the earliest produced_at across streams, never an
// in-flight one: its fate is the gateway's, and a gap must not claim a batch
// the DP may already have committed.
func (b *Buffer) oldest() (*stream, int, bool) {
	var best *stream
	bi := -1
	for _, s := range b.streams {
		for i, f := range s.files {
			if f.seq == s.inflight {
				continue
			}
			if best == nil || f.produced.Before(best.files[bi].produced) ||
				(f.produced.Equal(best.files[bi].produced) && s.id < best.id) {
				best, bi = s, i
			}
			break // within a stream, the first non-in-flight file is its oldest
		}
	}
	return best, bi, best != nil
}

func (b *Buffer) drop(s *stream, i int, reason string) {
	f := s.files[i]
	if err := b.st.Delete(batchRel(s.id, f.seq)); err != nil {
		b.opt.OnWriteError(err)
	}
	s.files = slices.Delete(s.files, i, i+1)
	b.total -= f.bytes
	s.addGap(f.seq, reason)
	b.saveState(s)
	b.opt.OnDrop(reason, f.samples)
}

func (s *stream) addGap(seq uint64, reason string) {
	if n := len(s.state.Gaps); n > 0 {
		g := &s.state.Gaps[n-1]
		if g.Reason == reason && g.To+1 == seq {
			g.To = seq
			return
		}
	}
	s.state.Gaps = append(s.state.Gaps, Gap{From: seq, To: seq, Reason: reason})
}

func (b *Buffer) saveState(s *stream) error {
	err := b.opt.Save(store.JSON{RelPath: stateRel(s.id), Value: s.state})
	if err != nil {
		b.opt.OnWriteError(err)
	}
	return err
}

func (b *Buffer) notify() {
	close(b.changed)
	b.changed = make(chan struct{})
}

// Changed is closed on the next Append, Trim, drop or in-flight change.
func (b *Buffer) Changed() <-chan struct{} {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.changed
}

// Heads returns, for every stream with nothing in flight, its oldest batch
// above the acked watermark, oldest produced_at first.
func (b *Buffer) Heads() []Head {
	b.mu.Lock()
	defer b.mu.Unlock()
	var out []Head
	for _, s := range b.streams {
		if s.inflight != 0 || len(s.files) == 0 {
			continue
		}
		f := s.files[0]
		out = append(out, Head{Stream: s.id, Seq: f.seq, ProducedAt: f.produced, Bytes: f.bytes, Refs: slices.Clone(f.refs)})
	}
	slices.SortFunc(out, func(x, y Head) int {
		if c := x.ProducedAt.Compare(y.ProducedAt); c != 0 {
			return c
		}
		return strings.Compare(x.Stream, y.Stream)
	})
	return out
}

func (b *Buffer) Read(id string, seq uint64) (Batch, error) {
	return store.LoadJSON[Batch](b.st, batchRel(id, seq), 1)
}

// MarkInFlight spares the batch from the caps until it is acked, rejected or
// requeued. It refuses a batch that is no longer buffered.
func (b *Buffer) MarkInFlight(id string, seq uint64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	s := b.streams[id]
	if s == nil || !slices.ContainsFunc(s.files, func(f meta) bool { return f.seq == seq }) {
		return false
	}
	s.inflight = seq
	return true
}

func (b *Buffer) ClearInFlight(id string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if s := b.streams[id]; s != nil && s.inflight != 0 {
		s.inflight = 0
		b.notify()
	}
}

// Trim records the gateway's durable watermark: state first, then the files,
// so a crash between the two leaves only files that load deletes.
func (b *Buffer) Trim(id string, hwm uint64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !validID(id) {
		return
	}
	s := b.stream(id)
	s.state.AckedSeq = max(s.state.AckedSeq, hwm)
	s.state.Gaps = slices.DeleteFunc(s.state.Gaps, func(g Gap) bool { return g.To < hwm })
	// If the state write failed, keep the files: after a restart they sit above
	// the stale acked_seq, reload, and keep next above them.
	stateSaved := b.saveState(s) == nil
	for len(s.files) > 0 && s.files[0].seq <= hwm {
		f := s.files[0]
		if stateSaved {
			if err := b.st.Delete(batchRel(id, f.seq)); err != nil {
				b.opt.OnWriteError(err)
			}
		}
		s.files = s.files[1:]
		b.total -= f.bytes
	}
	if s.inflight != 0 && s.inflight <= hwm {
		s.inflight = 0
	}
	// A gateway watermark above our next seq means our state was lost: never
	// send a seq the DP would discard as a replay.
	s.next = max(s.next, hwm+1)
	b.notify()
}

// DropAsGap removes one batch the sender gave up on (a terminal reject,
// OVERSIZE, UNREGISTERED) and records it as a gap.
func (b *Buffer) DropAsGap(id string, seq uint64, reason string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !validID(id) {
		return
	}
	s := b.streams[id]
	if s == nil {
		return
	}
	i := slices.IndexFunc(s.files, func(f meta) bool { return f.seq == seq })
	if i < 0 {
		return
	}
	if s.inflight == seq {
		s.inflight = 0
	}
	b.drop(s, i, reason)
	b.notify()
}

// GapsBefore is the gap markers a batch with this seq must carry.
func (b *Buffer) GapsBefore(id string, seq uint64) []Gap {
	b.mu.Lock()
	defer b.mu.Unlock()
	s := b.streams[id]
	if s == nil {
		return nil
	}
	var out []Gap
	for _, g := range s.state.Gaps {
		if g.To < seq {
			out = append(out, g)
		}
	}
	return out
}

// Resume is SessionOpen.resume.last_telemetry_seq_by_stream (a hint; the
// DP's watermark wins, gateway §4.13).
func (b *Buffer) Resume() map[string]uint64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make(map[string]uint64, len(b.streams))
	for id, s := range b.streams {
		out[id] = s.state.AckedSeq
	}
	return out
}

// Sweep applies the caps without an append — the age cap needs a clock tick.
func (b *Buffer) Sweep() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.enforce()
	b.notify()
}

func (b *Buffer) Stats() Stats {
	b.mu.Lock()
	defer b.mu.Unlock()
	st := Stats{Bytes: b.total}
	for _, s := range b.streams {
		st.Batches += len(s.files)
		for _, f := range s.files {
			if st.Oldest.IsZero() || f.produced.Before(st.Oldest) {
				st.Oldest = f.produced
			}
		}
	}
	return st
}
