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
// before what was read; agent-mi-logs-from-actuator design §4.2), and so are the entries at the page's oldest end that
// share its oldest time, unless that would empty the page: a full page ends where the time changes. A page asked with
// Before = the oldest time of the page before it therefore loses no line of an entry that fits a page, except:
//
//   - entries sharing one time that do not fit a page together: the page is the newest of them;
//   - an entry whose start is further above the page it fills than reach (below), about two page budgets long: its
//     lines take the fetch time, so a page asked with Before is empty there and paging back goes no further;
//   - an entry whose time is older than one written above it in the file (threads can write a few milliseconds out of
//     order);
//   - lines with the fetch time (below), since no Before asked from a page is above it.
//
// When held lines alone fill the page, the walk goes on back towards their entry start, holding no more, for at most
// one page budget of bytes (and at least one step) past where the page filled. Found, they take its time and are
// decided as any entry is. Not found, they take the fetch time and the walk stops: no parsable time (design §4.2), as
// with a non-default pattern. Lines above the file's first entry start belong to no entry and take the fetch time too.
//
// An empty line costs one byte of the budget, so the line count is bounded. An unterminated last line is the MI
// mid-write and is left for the next poll. A line longer than textBudget is cut on a rune boundary with Truncated set,
// and fits only a page with nothing else on it.
//
// A read reporting a smaller file than the first one (rotated or truncated) ends the walk with the lines found so far,
// held lines dropped: what the file holds now is not what was read. Memory is the page's text budget, one step of read
// bytes, and the first bytes of a line read back over several steps, kept only up to the room the page has left (and
// at least a timestamp's worth), which are briefly held twice when they grow. An error is ErrUnavailable (see there),
// with a context error visible to errors.Is; an empty file is not an error.
func (f *File) Page(ctx context.Context, q msg.LogPageQuery, textBudget int) ([]msg.LogLine, error) {
	budget := max(textBudget, 1)
	w := &walk{q: q, budget: budget, reach: uint64(max(budget, f.step)), now: f.now().UTC(), tail: true}
	step := uint64(f.step)
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
	reach  uint64    // how far past a page held lines alone fill the walk goes for their entry start
	now    time.Time // the fetch time

	tail  bool   // inside the file's unterminated last line, which is not sent
	head  []byte // the first bytes read so far of the pending line, the one ending where the walk has reached
	n     uint64 // the pending line's length read so far, its '\n' excluded
	first bool   // the line being taken is the file's first

	kept, held     []msg.LogLine // the page, and the continuation lines waiting for their entry's time; newest first
	cost, heldCost int
	long           bool   // held lines alone fill the page: going back for their entry start, to no line below floor
	floor          uint64 // the lowest offset a line may start at, while long
	short          bool   // the page stopped full, with older lines left off it
}

// read walks buf, the bytes at offset off just before those already walked, from its end. It reports whether the page
// is done, as it always is at the start of the file.
func (w *walk) read(buf []byte, off uint64) bool {
	j := len(buf)
	for {
		i := bytes.LastIndexByte(buf[:j], '\n')
		if i < 0 {
			break
		}
		if w.tail {
			w.tail = false // what follows the file's last '\n' is still being written
		} else if w.line(buf[i+1:j], off+uint64(i)+1) {
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
	if !w.line(buf[:j], 0) {
		w.lead()
	}
	return true
}

// keep is how many first bytes of the pending line are kept: one more than the room the page has left, and at least
// a timestamp's worth so that an entry start is still seen. No line is taken while one is pending, so it holds still
// for the whole line.
func (w *walk) keep() int { return max(w.budget-w.cost-w.heldCost, maxStamp) + 1 }

// line ends the pending line, seg being its first bytes and at its offset, and takes it. It reports whether the page is
// done.
func (w *walk) line(seg []byte, at uint64) bool {
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
		return w.entry(t, text, cut, size, own)
	}
	switch {
	case w.long:
		return false // an older line of the entry whose newest lines are held
	case !w.fits(size):
		return w.overflow(at)
	}
	w.held = append(w.held, msg.LogLine{Stream: Stream, Text: owned(text, own), Truncated: cut})
	w.heldCost += max(len(text), 1)
	if w.full() && !w.first { // the file's first line ends the walk: the lead keeps what fits
		return w.overflow(at)
	}
	return false
}

// entry decides an entry, its start line at time t and the lines held below it, as one. It reports whether the page
// is done.
func (w *walk) entry(t time.Time, text []byte, cut bool, size int, own bool) bool {
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
			w.short = !w.first
			return true
		}
		return false
	case len(w.kept) == 0:
		w.add(held, t) // the newest part of an entry larger than the page
		return true
	}
	w.short = true // this entry is left off
	return true
}

// overflow ends a page that is full with lines held. Their entry cannot be whole on it, so they are dropped, unless
// the page would be empty: then the walk goes on back towards their entry start, no further than reach.
func (w *walk) overflow(at uint64) bool {
	if len(w.kept) > 0 {
		w.short = true
		return true
	}
	w.long, w.floor = true, at-min(at, w.reach)
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

// owned is a line's text to keep: a joined line's array is already its own; anything else is the read's, and copied.
func owned(text []byte, own bool) []byte {
	if own {
		return text[:len(text):len(text)]
	}
	return bytes.Clone(text)
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

// join is seg followed by head, cut to its first keep bytes, in an array of exactly that size (head's own when it is
// already that size), so that a line kept from it carries no spare capacity.
func join(seg, head []byte, keep int) []byte {
	seg = seg[:min(len(seg), keep)]
	m := min(len(head), keep-len(seg)) // the bytes of head that stay
	out := head[:0]
	if cap(head) < len(seg)+m {
		out = make([]byte, 0, len(seg)+m)
	}
	out = out[:len(seg)+m]
	copy(out[len(seg):], head[:m])
	copy(out, seg)
	return out
}
