package logfile

import (
	"bytes"
	"context"
	"slices"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// Page returns the newest lines strictly inside (q.After, q.Before), oldest first, at most q.Limit of them
// (0 = no line limit), with at most textBudget bytes of text in total.
//
// It walks back from the end of the file one step at a time: a suffix range for the last step, then the step before
// each read, joining a line that two reads split. A continuation line takes the time of its entry, the entry start
// above it, so it is held until that start is read, and an entry is on a page whole or not at all. The walk ends at
// the first entry not newer than After, passes over entries not older than Before, and stops as soon as the page is
// full, held lines counted. Then the lines held for an entry that cannot be whole are dropped (their entry began
// before what was read; agent-mi-logs-from-actuator design §4.2). And unless the newest entry left off is known to be
// older than the page's oldest time, the entries at the page's oldest end at that time are dropped too, unless that
// would empty the page: a full page ends where the time changes. Known older means its start is in the bytes read
// and parses to an earlier time (no read is made to find it), so a page that fills beside held lines, which overflow
// it, or just below a continuation line always trims. A page asked with Before = the oldest time of the page before
// it therefore loses no line of an entry that fits a page, except:
//
//   - entries sharing one time that do not fit a page together: the page is the newest of them;
//   - an entry whose start lies further above the page its newest lines fill than the reach below: its lines take
//     the fetch time, so a page asked with Before is empty there and paging back goes no further;
//   - an entry whose time is older than one written above it in the file (threads can write a few milliseconds out of
//     order);
//   - lines with the fetch time (below), since no Before asked from a page is above it.
//
// When held lines alone fill the page, the walk goes on back towards their entry start, holding no more, for a reach of
// the bytes those lines span in the file, and at least one step, past where the page filled. Found, they take its time
// and are decided as any entry is. Not found, they take the fetch time and the walk stops: no parsable time (design
// §4.2), as with a non-default pattern. Lines above the file's first entry start belong to no entry and take the fetch
// time too.
//
// An empty line costs one byte of the budget, so the line count is bounded. An unterminated last line is the MI
// mid-write and is left for the next poll. A line longer than textBudget is cut on a rune boundary with Truncated set,
// and fits only a page with nothing else on it.
//
// A read reporting a smaller file than the first one (rotated or truncated) ends the walk with the lines found so far,
// held lines dropped: what the file holds now is not what was read. Memory is the page's text budget, one step of read
// bytes, and the first bytes of a line read back over several steps, kept only up to the room the page has left (and
// at least a timestamp's worth), which are briefly held twice when they grow, or are copied to an exact size to keep.
// An error is ErrUnavailable (see there), with a context error visible to errors.Is; an empty file is not an error.
func (f *File) Page(ctx context.Context, q msg.LogPageQuery, textBudget int) ([]msg.LogLine, error) {
	budget := max(textBudget, 1)
	step := uint64(f.step)
	w := &walk{q: q, budget: budget, step: step, now: f.now().UTC(), tail: true}
	rp, err := f.suffix(ctx, step)
	if err != nil {
		return nil, err
	}
	size := rp.size
	for {
		buf, err := rp.bytes()
		if err != nil {
			return nil, err
		}
		if w.read(buf, rp.off) {
			break
		}
		end := rp.off
		if w.long && end <= w.floor {
			w.lead() // no entry start within reach of the full page
			break
		}
		if rp, err = f.get(ctx, end-min(step, end), end-1); err != nil {
			return nil, err
		}
		if rp.size < size {
			rp.close()
			break
		}
	}
	if w.short {
		w.boundary()
	}
	slices.Reverse(w.kept)
	return w.kept, nil
}

// walk is a page being read back from the end of the file: its lines arrive newest first.
type walk struct {
	q      msg.LogPageQuery
	budget int       // for the page's text, a line costing max(len, 1)
	step   uint64    // the read step
	now    time.Time // the fetch time

	tail  bool   // inside the file's unterminated last line, which is not sent
	head  []byte // the first bytes read so far of the pending line, the one ending where the walk has reached
	n     uint64 // the pending line's length read so far, its '\n' excluded
	zero  bool   // the read in hand starts the file
	first bool   // the line being taken is the file's first

	kept, held     []msg.LogLine // the page, and the continuation lines waiting for their entry's time; newest first
	cost, heldCost int
	heldFrom       uint64 // where the held lines start in the file
	heldTo         uint64 // and end, past the newest one's '\n'
	long           bool   // held lines alone fill the page: going back for their entry start, to no line below floor
	floor          uint64 // the lowest offset a line may start at, while long
	short          bool   // the page stopped full, with older lines left off it that may share its oldest time
}

// read walks buf, the bytes at offset off just before those already walked, from its end. It reports whether the page
// is done, as it always is at the start of the file.
func (w *walk) read(buf []byte, off uint64) bool {
	w.zero = off == 0
	j := len(buf)
	for {
		i := bytes.LastIndexByte(buf[:j], '\n')
		if i < 0 {
			break
		}
		if w.tail {
			w.tail = false // what follows the file's last '\n' is still being written
		} else if w.line(buf[i+1:j], buf[:i], off+uint64(i)+1) {
			return true
		}
		j = i
	}
	switch {
	case w.tail:
		return off == 0
	case off > 0:
		w.head, w.n = join(buf[:j], w.head, w.keep()), w.n+uint64(j)
		return false
	}
	w.first = true
	if !w.line(buf[:j], nil, 0) {
		w.lead()
	}
	return true
}

// keep is how many first bytes of the pending line are kept: one more than the room the page has left, and at least
// a timestamp's worth so that an entry start is still seen. No line is taken while one is pending, so it holds still
// for the whole line.
func (w *walk) keep() int { return max(w.budget-w.cost-w.heldCost, maxStamp) + 1 }

// line ends the pending line, seg being its first bytes and at its offset, and takes it; above is the read's bytes
// above it. It reports whether the page is done.
func (w *walk) line(seg, above []byte, at uint64) bool {
	next := at + uint64(len(seg)) + w.n + 1 // just past its '\n'
	raw, whole, own := seg, true, false
	if w.n > 0 { // the line went on into the read after this one, and is joined into an array of its own
		keep := w.keep()
		raw, whole, own = join(seg, w.head, keep), uint64(len(seg))+w.n <= uint64(keep), true
	}
	w.head, w.n = nil, 0
	if w.long && at < w.floor {
		w.lead() // no entry start within reach of the full page
		return true
	}
	t, entry := entryTime(raw)
	text, cut := finish(raw, whole, w.budget)
	size := len(text)
	if cut {
		// Longer than the budget, or than the room left, which is all the carry kept of it: it fits only an empty page,
		// whose room is the whole budget.
		size = w.budget
	}
	if entry {
		return w.entry(t, text, cut, size, own, above)
	}
	switch {
	case w.long:
		return false // an older line of the entry whose newest lines are held
	case !w.fits(size):
		return w.overflow(at)
	}
	if len(w.held) == 0 {
		w.heldTo = next
	}
	w.held = append(w.held, msg.LogLine{Stream: Stream, Text: owned(text, own), Truncated: cut})
	w.heldCost += max(len(text), 1)
	w.heldFrom = at
	if w.full() && !w.first { // the file's first line ends the walk: the lead keeps what fits
		return w.overflow(at)
	}
	return false
}

// entry decides an entry, its start line at time t and the lines held below it, as one; above is the read's bytes above
// it. It reports whether the page is done.
func (w *walk) entry(t time.Time, text []byte, cut bool, size int, own bool, above []byte) bool {
	whole := !w.long && w.fits(size)
	held := w.held
	w.held, w.heldCost, w.long = nil, 0, false
	switch {
	case w.q.After != nil && !t.After(*w.q.After):
		return true // and nothing above it is newer
	case w.q.Before != nil && !t.Before(*w.q.Before):
		return false
	case whole:
		w.add(held, t)
		w.add([]msg.LogLine{{Stream: Stream, Text: owned(text, own), Truncated: cut}}, t)
		if w.full() {
			// The file's first line leaves nothing off; otherwise the line above, if the read in hand has it.
			w.short = !w.first && !w.before(above, t)
			return true
		}
		return false
	case len(w.kept) == 0:
		w.add(held, t) // the newest part of an entry larger than the page
		return true
	}
	w.short = !t.Before(w.kept[len(w.kept)-1].TS) // this entry is left off
	return true
}

// before reports whether the line above, the last line of above, is known to start an entry older than t: the read
// in hand holds its start (no read is made to look), and it parses as one.
func (w *walk) before(above []byte, t time.Time) bool {
	k := bytes.LastIndexByte(above, '\n')
	if k < 0 && !w.zero {
		return false // it began in the read before this one
	}
	u, ok := entryTime(above[k+1:])
	return ok && u.Before(t)
}

// overflow ends a page that is full with lines held, the line at offset at being the one that filled it. Their entry
// cannot be whole on it, so they are dropped, its time unknown, unless the page would be empty: then the walk goes on
// back towards their entry start, for the bytes the held lines span and at least one step.
func (w *walk) overflow(at uint64) bool {
	if len(w.kept) > 0 {
		w.short = true
		return true
	}
	reach := max(w.heldTo-w.heldFrom, w.step)
	w.long, w.floor = true, at-min(at, reach)
	return false
}

// lead decides the held lines that no entry start was found above, at the fetch time.
func (w *walk) lead() {
	if (w.q.After == nil || w.now.After(*w.q.After)) && (w.q.Before == nil || w.now.Before(*w.q.Before)) {
		w.add(w.held, w.now)
	}
}

// boundary ends a full page where its time changes: the lines at its oldest end that share the oldest line's time
// are dropped, unless that would empty it, so that the page asked with Before = its oldest time has every one of them.
func (w *walk) boundary() {
	i, oldest := len(w.kept), w.kept[len(w.kept)-1].TS
	for i > 0 && w.kept[i-1].TS.Equal(oldest) {
		i--
	}
	if i > 0 {
		w.kept = w.kept[:i]
	}
}

func (w *walk) add(lines []msg.LogLine, t time.Time) {
	for _, l := range lines {
		l.TS = t
		w.kept = append(w.kept, l)
		w.cost += max(len(l.Text), 1)
	}
}

// fits reports whether one more line of n bytes fits the page beside what is kept and held.
func (w *walk) fits(n int) bool {
	lines := len(w.kept) + len(w.held)
	return (w.q.Limit == 0 || lines < int(w.q.Limit)) && w.cost+w.heldCost+max(n, 1) <= w.budget
}

// full reports whether no line at all fits the page any more, held lines counted.
func (w *walk) full() bool { return !w.fits(0) }

// owned is a line's text to keep, in an array of exactly its size: a joined line's own array when it is that size,
// else a copy (of the read's bytes, or of a joined array grown with room to spare).
func owned(text []byte, own bool) []byte {
	if own && cap(text) == len(text) {
		return text
	}
	return append(make([]byte, 0, len(text)), text...)
}

// finish is a line's text from raw, its first bytes, and whether it was cut: when whole says raw is all of it, raw
// less a final '\r'; otherwise, or when that is still over budget, raw cut to at most budget bytes on a rune boundary.
func finish(raw []byte, whole bool, budget int) (text []byte, cut bool) {
	if whole {
		raw = bytes.TrimSuffix(raw, []byte{'\r'})
		if len(raw) <= budget {
			return raw, false
		}
	}
	n := min(budget, len(raw)-1) // a line not whole has len(raw) == keep, which can be below budget+1
	return cutRunes(raw[:n], raw[n]), true
}

// join is seg followed by head, cut to its first keep bytes, in head's array when it has room, else in one at least
// twice as large, capped at keep: a line read back over many steps is copied a few times, not once a step.
func join(seg, head []byte, keep int) []byte {
	seg = seg[:min(len(seg), keep)]
	m := min(len(head), keep-len(seg)) // the bytes of head that stay
	out := head[:0]
	if cap(head) < len(seg)+m {
		out = make([]byte, 0, min(max(len(seg)+m, 2*cap(head)), keep))
	}
	out = out[:len(seg)+m]
	copy(out[len(seg):], head[:m])
	copy(out, seg)
	return out
}
