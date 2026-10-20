// Package ring is the per-placement on-disk log ring (agent-mi-logs design
// §3): time-indexed segment files under <data-dir>/logs/<placement>/, bounded
// by bytes, dropping the oldest segment first and recording every drop as a
// gap marker. It survives agent restart. A record never spans two segments, so
// a half line is never readable; a torn tail left by a crash is cut on open.
//
// Log text is the MI's own output and is never written to slog here.
package ring

import (
	"bytes"
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const (
	root       = "logs"
	segExt     = ".seg"
	stateFile  = "state.json"
	gapsFile   = "gaps.json"
	minSegSize = 512
	maxSegSize = 1 << 20
	maxGaps    = 256

	reasonDroppedOldest = "DROPPED_OLDEST"
	reasonCapReduced    = "CAP_REDUCED"
)

var safeID = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

// validID: a placement id is a path segment under logs/.
func validID(id string) bool { return id != "." && id != ".." && safeID.MatchString(id) }

type rec struct {
	T time.Time `json:"t"`
	S string    `json:"s"`
	X []byte    `json:"x"`
	R bool      `json:"r,omitempty"`
}

type state struct {
	SchemaVersion int    `json:"schema_version"`
	Cap           uint64 `json:"cap"`
	Next          uint64 `json:"next"` // next seq, kept for when no segment remains
}

func (s state) Version() int { return s.SchemaVersion }

type gapRec struct {
	Pos uint64     `json:"pos"` // seq of the line that followed the gap when it was recorded
	End uint64     `json:"end"` // exclusive end seq; End == Pos for a marker covering no stored seq
	G   msg.LogGap `json:"gap"`
}

type gapFile struct {
	SchemaVersion int      `json:"schema_version"`
	Gaps          []gapRec `json:"gaps"`
}

func (g gapFile) Version() int { return g.SchemaVersion }

type segment struct {
	first    uint64
	count    int
	bytes    int64
	min, max time.Time
}

func (s *segment) note(ts time.Time) {
	if s.count == 0 || ts.Before(s.min) {
		s.min = ts
	}
	if s.count == 0 || ts.After(s.max) {
		s.max = ts
	}
}

type Ring struct {
	mu      sync.Mutex
	st      *store.Store
	id      string
	dir     string // relative to the store root
	cap     uint64
	next    uint64
	segs    []*segment // ascending first
	gaps    []gapRec
	last    time.Time
	hasLast bool
	active  *os.File // handle on the newest segment, nil until first append
}

func segName(first uint64) string { return fmt.Sprintf("%020d%s", first, segExt) }

func (r *Ring) segRel(first uint64) string { return r.dir + "/" + segName(first) }

// Open loads (or lazily creates) the ring for a placement. A zero maxBytes
// keeps the cap persisted beside the segments; a non-zero one replaces it.
func Open(s *store.Store, placement string, maxBytes uint64) (*Ring, error) {
	if !validID(placement) {
		return nil, fmt.Errorf("ring: invalid placement id %q", placement)
	}
	r := &Ring{st: s, id: placement, dir: root + "/" + placement, next: 1}
	stt, err := store.LoadJSON[state](s, r.dir+"/"+stateFile, 1)
	switch {
	case err == nil:
		r.cap, r.next = stt.Cap, max(stt.Next, 1)
	case !errors.Is(err, store.ErrNotFound):
		return nil, err
	}
	if g, err := store.LoadJSON[gapFile](s, r.dir+"/"+gapsFile, 1); err == nil {
		r.gaps = g.Gaps
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	if err := r.load(); err != nil {
		return nil, err
	}
	if maxBytes != 0 && maxBytes != r.cap {
		if err := r.setCap(maxBytes); err != nil {
			return nil, err
		}
	}
	return r, nil
}

func (r *Ring) load() error {
	entries, err := os.ReadDir(r.st.Path(r.dir))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	for _, e := range entries {
		base, ok := strings.CutSuffix(e.Name(), segExt)
		if !ok || len(base) != 20 {
			continue
		}
		first, err := strconv.ParseUint(base, 10, 64)
		if err != nil {
			continue
		}
		seg := &segment{first: first}
		path := r.st.Path(r.dir + "/" + e.Name())
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		valid := 0
		for _, rc := range parseRecords(data, &valid) {
			seg.note(rc.T)
			seg.count++
		}
		if valid < len(data) { // torn tail: cut it so appends stay aligned
			if err := os.Truncate(path, int64(valid)); err != nil {
				return err
			}
		}
		seg.bytes = int64(valid)
		if seg.count == 0 {
			_ = os.Remove(path)
			continue
		}
		r.segs = append(r.segs, seg)
	}
	slices.SortFunc(r.segs, func(a, b *segment) int { return cmp.Compare(a.first, b.first) })
	if err := r.repairOverlap(); err != nil {
		return err
	}
	if n := len(r.segs); n > 0 {
		l := r.segs[n-1]
		r.next = max(r.next, l.first+uint64(l.count))
		if recs, err := r.readSeg(l); err == nil && len(recs) > 0 {
			r.last, r.hasLast = recs[len(recs)-1].T, true
		}
	}
	return nil
}

// repairOverlap handles a crash inside resegment: later pieces were written
// but the original was not yet replaced, so it still holds records the next
// segment repeats. Cut it back to the records before that segment's first seq.
func (r *Ring) repairOverlap() error {
	for i := 0; i+1 < len(r.segs); i++ {
		s, nx := r.segs[i], r.segs[i+1]
		if s.first+uint64(s.count) <= nx.first {
			continue
		}
		keep := int(nx.first - s.first)
		data, err := os.ReadFile(r.st.Path(r.segRel(s.first)))
		if err != nil {
			return err
		}
		off, recs := 0, 0
		for recs < keep {
			nl := bytes.IndexByte(data[off:], '\n')
			if nl < 0 {
				break
			}
			off += nl + 1
			recs++
		}
		if err := os.Truncate(r.st.Path(r.segRel(s.first)), int64(off)); err != nil {
			return err
		}
		s.count, s.bytes = recs, int64(off)
		s.min, s.max = time.Time{}, time.Time{}
		parsed, _ := r.readSeg(s)
		s.count = 0
		for _, rc := range parsed {
			s.note(rc.T)
			s.count++
		}
	}
	return nil
}

// parseRecords returns the leading run of complete, well-formed records and
// sets *valid to the byte length they occupy.
func parseRecords(data []byte, valid *int) []rec {
	var out []rec
	for pos := 0; pos < len(data); {
		nl := bytes.IndexByte(data[pos:], '\n')
		if nl < 0 {
			break
		}
		var rc rec
		if json.Unmarshal(data[pos:pos+nl], &rc) != nil {
			break
		}
		out = append(out, rc)
		pos += nl + 1
		*valid = pos
	}
	return out
}

func (r *Ring) readSeg(s *segment) ([]rec, error) {
	data, err := os.ReadFile(r.st.Path(r.segRel(s.first)))
	if err != nil {
		return nil, err
	}
	var valid int
	return parseRecords(data, &valid), nil
}

func (r *Ring) saveState() error {
	return r.st.Save(store.JSON{RelPath: r.dir + "/" + stateFile, Value: state{SchemaVersion: 1, Cap: r.cap, Next: r.next}})
}

func (r *Ring) saveGaps() error {
	return r.st.Save(store.JSON{RelPath: r.dir + "/" + gapsFile, Value: gapFile{SchemaVersion: 1, Gaps: r.gaps}})
}

func (r *Ring) segSize() int {
	return int(min(max(r.cap/4, minSegSize), maxSegSize, max(r.cap, 1)))
}

func (r *Ring) total() int64 {
	var n int64
	for _, s := range r.segs {
		n += s.bytes
	}
	return n
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

func encode(rc rec) ([]byte, error) {
	b, err := json.Marshal(rc)
	if err != nil {
		return nil, err
	}
	return append(b, '\n'), nil
}

// Append stores one line. With a zero cap the placement is not collecting and
// the line is not stored. A line larger than a segment is stored truncated on a
// rune boundary with Truncated set; it is never dropped.
func (r *Ring) Append(l msg.LogLine) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cap == 0 {
		return nil
	}
	rc := rec{T: l.TS, S: l.Stream, X: l.Text, R: l.Truncated}
	segSize := r.segSize()
	b, err := encode(rc)
	if err != nil {
		return err
	}
	if len(b) > segSize {
		over, _ := encode(rec{T: l.TS, S: l.Stream, R: true})
		n := max((segSize-len(over))/4*3, 0)
		for {
			rc.X, rc.R = slices.Clone(truncRunes(l.Text, n)), true
			if b, err = encode(rc); err != nil {
				return err
			}
			if len(b) <= segSize || n == 0 {
				break
			}
			n = max(n-8, 0)
		}
	}
	var seg *segment
	if n := len(r.segs); n > 0 {
		seg = r.segs[n-1]
		if seg.bytes+int64(len(b)) > int64(segSize) {
			seg = nil
		}
	}
	if seg == nil {
		if err := r.rotate(); err != nil {
			return err
		}
		seg = r.segs[len(r.segs)-1]
	}
	if r.active == nil {
		if r.active, err = os.OpenFile(r.st.Path(r.segRel(seg.first)), os.O_WRONLY|os.O_APPEND, 0o600); err != nil {
			return err
		}
	}
	if _, err := r.active.Write(b); err != nil {
		r.truncateTail(seg)
		return err
	}
	if err := r.active.Sync(); err != nil {
		r.truncateTail(seg)
		return err
	}
	seg.note(rc.T)
	seg.count++
	seg.bytes += int64(len(b))
	r.next++
	r.last, r.hasLast = rc.T, true
	return r.enforce(reasonDroppedOldest)
}

// truncateTail removes a possibly partial record after a failed write so the
// segment stays aligned with its index.
func (r *Ring) truncateTail(seg *segment) {
	_ = r.active.Truncate(seg.bytes)
	_ = r.active.Close()
	r.active = nil
}

func (r *Ring) rotate() error {
	if r.active != nil {
		_ = r.active.Close()
		r.active = nil
	}
	if err := os.MkdirAll(r.st.Path(r.dir), 0o700); err != nil {
		return err
	}
	path := r.st.Path(r.segRel(r.next))
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	r.active = f
	d, err := os.Open(filepath.Dir(path))
	if err == nil {
		_ = d.Sync()
		_ = d.Close()
	}
	r.segs = append(r.segs, &segment{first: r.next})
	// Item 2: persist Next whenever a segment is created or dropped (not on
	// every Append), so an emptied or torn ring never reuses seq numbers.
	return r.saveState()
}

// enforce drops the oldest segment while the ring is over its cap, recording
// each drop as a gap marker.
func (r *Ring) enforce(reason string) error {
	changed := false
	for r.total() > int64(r.cap) && len(r.segs) > 0 {
		if err := r.dropOldest(reason); err != nil {
			return err
		}
		changed = true
	}
	if changed {
		if err := r.saveState(); err != nil {
			return err
		}
		return r.saveGaps()
	}
	return nil
}

func (r *Ring) dropOldest(reason string) error {
	s := r.segs[0]
	recs, _ := r.readSeg(s)
	if len(r.segs) == 1 && r.active != nil {
		_ = r.active.Close()
		r.active = nil
	}
	if err := r.st.Delete(r.segRel(s.first)); err != nil {
		return err
	}
	r.segs = r.segs[1:]
	if len(recs) > 0 {
		r.addGap(s.first, s.first+uint64(s.count), msg.LogGap{From: s.min, To: s.max, Reason: reason})
	}
	return nil
}

func (r *Ring) addGap(pos, end uint64, g msg.LogGap) {
	if n := len(r.gaps); n > 0 {
		p := &r.gaps[n-1]
		if p.G.Reason == g.Reason && (g.Reason == reasonDroppedOldest || g.Reason == reasonCapReduced) {
			if g.To.After(p.G.To) {
				p.G.To = g.To
			}
			p.End = max(p.End, end)
			return
		}
	}
	r.gaps = append(r.gaps, gapRec{Pos: pos, End: end, G: g})
	if len(r.gaps) > maxGaps {
		r.gaps = r.gaps[len(r.gaps)-maxGaps:]
	}
}

// MarkGap records a window the collector could not read.
func (r *Ring) MarkGap(g msg.LogGap) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.gaps = append(r.gaps, gapRec{Pos: r.next, End: r.next, G: g})
	if len(r.gaps) > maxGaps {
		r.gaps = r.gaps[len(r.gaps)-maxGaps:]
	}
	return r.saveGaps()
}

// SetCap persists a new cap and trims the oldest segments to it now. Zero
// turns collection off and deletes every stored line and gap marker.
func (r *Ring) SetCap(maxBytes uint64) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.setCap(maxBytes)
}

func (r *Ring) setCap(maxBytes uint64) error {
	r.cap = maxBytes
	if err := r.saveState(); err != nil {
		return err
	}
	if maxBytes == 0 {
		if r.active != nil {
			_ = r.active.Close()
			r.active = nil
		}
		for _, s := range r.segs {
			if err := r.st.Delete(r.segRel(s.first)); err != nil {
				return err
			}
		}
		r.segs, r.gaps, r.hasLast, r.last = nil, nil, false, time.Time{}
		return r.st.Delete(r.dir + "/" + gapsFile)
	}
	if err := r.resegment(); err != nil {
		// Files may have changed halfway: rebuild the index from disk.
		r.segs = nil
		if lerr := r.load(); lerr != nil {
			return errors.Join(err, lerr)
		}
		return err
	}
	return r.enforce(reasonCapReduced)
}

// resegment splits segments larger than the current segment size so a cap
// reduction trims by whole small segments instead of discarding a big one.
//
// Crash safety: for each oversize segment the later pieces are written first
// under their final names, then the first piece replaces the original (same
// name) last. A crash before that last step leaves the original whole plus
// pieces that repeat its tail; load's repairOverlap cuts the original back, so
// nothing is lost or duplicated. r.segs is only replaced on success.
func (r *Ring) resegment() error {
	size := int64(r.segSize())
	var out []*segment
	for _, s := range r.segs {
		if s.bytes <= size {
			out = append(out, s)
			continue
		}
		recs, err := r.readSeg(s)
		if err != nil {
			return err
		}
		if r.active != nil {
			_ = r.active.Close()
			r.active = nil
		}
		var pieces []*segment
		var bufs [][]byte
		for j, rc := range recs {
			b, err := encode(rc)
			if err != nil {
				return err
			}
			n := len(pieces) - 1
			if n < 0 || int64(len(bufs[n])+len(b)) > size {
				pieces = append(pieces, &segment{first: s.first + uint64(j)})
				bufs = append(bufs, nil)
				n++
			}
			pieces[n].note(rc.T)
			pieces[n].count++
			bufs[n] = append(bufs[n], b...)
		}
		for i := len(pieces) - 1; i >= 0; i-- {
			pieces[i].bytes = int64(len(bufs[i]))
			if err := r.st.Save(store.Blob{RelPath: r.segRel(pieces[i].first), Data: bufs[i]}); err != nil {
				return err
			}
		}
		out = append(out, pieces...)
	}
	r.segs = out
	return nil
}

// Close releases the open segment handle.
func (r *Ring) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.active == nil {
		return nil
	}
	err := r.active.Close()
	r.active = nil
	return err
}

// LastTimestamp is the timestamp of the newest stored line.
func (r *Ring) LastTimestamp() (time.Time, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.last, r.hasLast
}

func toLine(rc rec) msg.LogLine {
	return msg.LogLine{TS: rc.T, Stream: rc.S, Text: slices.Clone(rc.X), Truncated: rc.R}
}

// fit applies the per-message budget: used is what earlier lines consumed. It
// reports the (possibly truncated) line and whether it fits at all. The first
// line is always returned, truncated if it alone exceeds the budget.
func fit(l msg.LogLine, budget, used int, first bool) (msg.LogLine, bool) {
	if budget <= 0 {
		return l, true
	}
	if first && len(l.Text) > budget {
		l.Text, l.Truncated = truncRunes(l.Text, budget), true
		return l, true
	}
	return l, used+len(l.Text) <= budget
}

// Page returns the newest limit lines strictly inside (after, before), oldest
// first, within maxBytes of text (<=0 means unbounded), plus the gap markers
// overlapping the returned span. A single line over maxBytes is returned
// truncated with Truncated set.
func (r *Ring) Page(after, before *time.Time, limit int, maxBytes int) ([]msg.LogLine, []msg.LogGap, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	inWin := func(ts time.Time) bool {
		return (after == nil || ts.After(*after)) && (before == nil || ts.Before(*before))
	}
	var rev []msg.LogLine
	used, cut := 0, false
scan:
	for i := len(r.segs) - 1; i >= 0; i-- {
		s := r.segs[i]
		if (after != nil && !s.max.After(*after)) || (before != nil && !s.min.Before(*before)) {
			continue
		}
		recs, err := r.readSeg(s)
		if err != nil {
			return nil, nil, err
		}
		for j := len(recs) - 1; j >= 0; j-- {
			if !inWin(recs[j].T) {
				continue
			}
			if limit > 0 && len(rev) >= limit {
				cut = true
				break scan
			}
			l, ok := fit(toLine(recs[j]), maxBytes, used, len(rev) == 0)
			if !ok {
				cut = true
				break scan
			}
			used += len(l.Text)
			rev = append(rev, l)
		}
	}
	slices.Reverse(rev)
	lo := after
	if cut && len(rev) > 0 {
		lo = &rev[0].TS
	}
	var gaps []msg.LogGap
	for _, g := range r.gaps {
		if (lo == nil || !g.G.To.Before(*lo)) && (before == nil || g.G.From.Before(*before)) {
			gaps = append(gaps, g.G)
		}
	}
	return rev, gaps, nil
}

// ExportFrom returns lines with seq >= seq, oldest first, within maxBytes
// (<=0 unbounded; the first line is truncated rather than dropped if it alone
// exceeds it). next is the seq to pass to resume; done is true once the ring is
// exhausted. A seq older than the ring starts at the oldest retained line.
func (r *Ring) ExportFrom(seq uint64, maxBytes int) ([]msg.LogLine, []msg.LogGap, uint64, bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	next := seq
	var lines []msg.LogLine
	used := 0
scan:
	for _, s := range r.segs {
		if s.first+uint64(s.count) <= seq {
			continue
		}
		recs, err := r.readSeg(s)
		if err != nil {
			return nil, nil, seq, false, err
		}
		for j, rc := range recs {
			q := s.first + uint64(j)
			if q < seq {
				continue
			}
			l, ok := fit(toLine(rc), maxBytes, used, len(lines) == 0)
			if !ok {
				break scan
			}
			used += len(l.Text)
			lines = append(lines, l)
			next = q + 1
		}
	}
	done := next >= r.next || len(r.segs) == 0
	if done {
		next = max(next, r.next)
		if len(r.segs) == 0 {
			next = max(seq, r.next)
		}
	}
	var gaps []msg.LogGap
	for _, g := range r.gaps {
		if (g.Pos >= seq || g.End > seq) && (done || g.Pos < next) {
			gaps = append(gaps, g.G)
		}
	}
	return lines, gaps, next, done, nil
}
