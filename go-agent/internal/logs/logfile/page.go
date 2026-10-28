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
// above it, so it is held until that start is read, and an entry is on the page whole or not at all: the walk ends at
// the first entry not newer than After, passes over entries not older than Before, and stops as soon as the page is
// full, held lines counted, dropping the lines held for an entry that cannot be whole (their entry began before what
// was read; agent-mi-logs-from-actuator design §4.2). Paging back with Before = a page's oldest time therefore loses
// no line of an entry that fits a page, as long as no two entries share a time (a strict Before passes over every
// entry at that time). Two cases would leave the page empty instead, and are kept:
//
//   - one entry larger than a page, reached after some entry start has been read: the walk goes back to its start,
//     reading no more than the entry, and the page is its newest lines, at its time;
//   - a page filled before any entry start is read (a non-default pattern, or a stack trace longer than a page at the
//     end of the file): the page is those lines, at the fetch time.
//
// Lines above the file's first entry start belong to no entry and take the fetch time too. An empty line costs one
// byte of the budget, so the line count is bounded. An unterminated last line is the MI mid-write and is left for the
// next poll. A line longer than textBudget is cut on a rune boundary with Truncated set.
//
// A read reporting a smaller file than the first one (rotated or truncated) ends the walk with the lines found so far,
// held lines dropped: what the file holds now is not what was read. Memory is one page budget plus one step of read
// bytes: the first bytes of the line being read back are kept only up to the room the page has left (and at least a
// timestamp's worth). An error is ErrUnavailable (see there), with a context error visible to errors.Is; an empty
// file is not an error.
func (f *File) Page(ctx context.Context, q msg.LogPageQuery, textBudget int) ([]msg.LogLine, error) {
	budget := max(textBudget, 1)
	w := &walk{q: q, budget: budget, now: f.now().UTC(), tail: true}
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
		if w.read(buf, rp.off == 0) {
			break
		}
		end := rp.off
		if rp, err = f.get(ctx, end-min(step, end), end-1); err != nil {
			return nil, err
		}
		if rp.size < size {
			rp.close()
			break
		}
	}
	slices.Reverse(w.kept)
	return w.kept, nil
}

// walk is a page being read back from the end of the file: its lines arrive newest first.
type walk struct {
	q      msg.LogPageQuery
	budget int       // for the page's text, a line costing max(len, 1)
	now    time.Time // the fetch time

	tail bool   // inside the file's unterminated last line, which is not sent
	head []byte // the first bytes read so far of the pending line, the one ending where the walk has reached
	n    uint64 // the pending line's length read so far, its '\n' excluded

	kept, held     []msg.LogLine // the page, and the continuation lines waiting for their entry's time; newest first
	cost, heldCost int
	started        bool // an entry start has been read
	long           bool // the page is the held newest part of an entry larger than a page: going back to its start
}

// read walks buf, the bytes just before those already walked, from its end; start says buf begins the file. It
// reports whether the page is done, as it always is at the start of the file.
func (w *walk) read(buf []byte, start bool) bool {
	j := len(buf)
	for {
		i := bytes.LastIndexByte(buf[:j], '\n')
		if i < 0 {
			break
		}
		if w.tail {
			w.tail = false // what follows the file's last '\n' is still being written
		} else if w.line(buf[i+1 : j]) {
			return true
		}
		j = i
	}
	switch {
	case w.tail:
		return start
	case !start:
		w.head, w.n = join(buf[:j], w.head, w.keep()), w.n+uint64(j)
		return false
	}
	if !w.line(buf[:j]) { // the file's first line
		w.lead()
	}
	return true
}

// keep is how many first bytes of the pending line are kept: one more than the room the page has left, and at least
// a timestamp's worth so that an entry start is still seen. No line is taken while one is pending, so it holds still
// for the whole line.
func (w *walk) keep() int { return max(w.budget-w.cost-w.heldCost, maxStamp) + 1 }

// line ends the pending line, seg being its first bytes, and takes it. It reports whether the page is done.
func (w *walk) line(seg []byte) bool {
	raw, whole := seg, true
	if w.n > 0 { // the line went on into the read after this one
		keep := w.keep()
		raw, whole = join(seg, w.head, keep), uint64(len(seg))+w.n <= uint64(keep)
	}
	w.head, w.n = nil, 0
	t, entry := entryTime(raw)
	text, cut := finish(raw, whole, w.budget)
	size := len(text)
	if !whole {
		// Longer than the room left, all that was kept of it: only an empty page, whose room is the whole budget, has
		// room for its first budget bytes.
		size = w.budget
	}
	if entry {
		return w.entry(t, text, cut, size)
	}
	switch {
	case w.long:
		return false // an older line of the entry the page is the newest part of
	case !w.fits(size):
		return w.overflow()
	}
	w.held = append(w.held, msg.LogLine{Stream: Stream, Text: bytes.Clone(text), Truncated: cut})
	w.heldCost += max(len(text), 1)
	if w.full() {
		return w.overflow()
	}
	return false
}

// entry decides an entry, its start line at time t and the lines held below it, as one. It reports whether the page
// is done.
func (w *walk) entry(t time.Time, text []byte, cut bool, size int) bool {
	whole := !w.long && w.fits(size)
	held := w.held
	w.held, w.heldCost, w.started, w.long = nil, 0, true, false
	switch {
	case w.q.After != nil && !t.After(*w.q.After):
		return true // and nothing above it is newer
	case w.q.Before != nil && !t.Before(*w.q.Before):
		return false
	case whole:
		w.add(held, t)
		w.add([]msg.LogLine{{Stream: Stream, Text: bytes.Clone(text), Truncated: cut}}, t)
		return w.full()
	case len(w.kept) == 0:
		w.add(held, t) // the newest part of an entry larger than the page
	}
	return true
}

// overflow ends a page that is full with lines held. Their entry cannot be whole on it, so they are dropped, unless
// the page would be empty: then, past an entry start, the walk goes back to their entry's start for its time; with
// none read, they take the fetch time.
func (w *walk) overflow() bool {
	switch {
	case len(w.kept) > 0:
		return true
	case w.started:
		w.long = true
		return false
	}
	w.lead()
	return true
}

// lead decides the held lines that no entry start is above, at the fetch time.
func (w *walk) lead() {
	if (w.q.After == nil || w.now.After(*w.q.After)) && (w.q.Before == nil || w.now.Before(*w.q.Before)) {
		w.add(w.held, w.now)
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

// join is seg followed by head, cut to its first keep bytes. It works in head's array when that has room, so a long
// line read back over many steps is not copied into a new array each time.
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
