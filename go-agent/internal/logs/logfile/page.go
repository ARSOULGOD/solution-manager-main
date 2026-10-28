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
// above it, so it is held until that start is read and is in or out of the page with its entry: the walk ends at the
// first entry not newer than After, and passes over entries not older than Before. Lines above the file's first entry
// start belong to no entry and take the fetch time. The newest lines are kept, so the oldest line of a page can be a
// continuation line whose entry start did not fit. An empty line costs one byte of the budget, so the line count is
// bounded too. An unterminated last line is the MI mid-write and is left for the next poll. A line longer than
// textBudget is cut on a rune boundary with Truncated set.
//
// A read reporting a smaller file than the first one (rotated or truncated) ends the walk with the lines found so far:
// what the file holds now is not what was read. Memory is the page, one step of read bytes and the first textBudget+1
// bytes of one line. An error is ErrUnavailable (see there), with a context error visible to errors.Is; an empty file
// is not an error.
func (f *File) Page(ctx context.Context, q msg.LogPageQuery, textBudget int) ([]msg.LogLine, error) {
	budget := max(textBudget, 1)
	w := &walk{q: q, budget: budget, keep: max(budget, maxStamp) + 1, now: f.now().UTC(), tail: true}
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
	keep   int       // how many first bytes of a line are kept: the budget (and at least a timestamp), plus one
	now    time.Time // the fetch time

	tail bool   // inside the file's unterminated last line, which is not sent
	head []byte // the first bytes read so far of the pending line, the one ending where the walk has reached
	n    uint64 // the pending line's length read so far, its '\n' excluded

	kept, held     []msg.LogLine // the page, and the continuation lines waiting for their entry's time; newest first
	cost, heldCost int
	full           bool // a held line did not fit: the page ends with the entry being held, if it is in the window
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
		w.head, w.n = join(buf[:j], w.head, w.keep), w.n+uint64(j)
		return false
	}
	if !w.line(buf[:j]) { // the file's first line
		w.lead()
	}
	return true
}

// line ends the pending line, seg being its first bytes, and takes it. It reports whether the page is done.
func (w *walk) line(seg []byte) bool {
	raw, whole := seg, true
	if w.n > 0 { // the line went on into the read after this one
		w.head = join(seg, w.head, w.keep)
		raw, whole = w.head, uint64(len(seg))+w.n <= uint64(w.keep)
	}
	w.head, w.n = w.head[:0], 0
	t, entry := entryTime(raw)
	text, cut := finish(raw, whole, w.budget)
	if entry {
		return w.entry(t, text, cut)
	}
	if w.full || !w.fits(len(text)) {
		w.full = true // older lines of this entry are past the page
		return false
	}
	w.held = append(w.held, msg.LogLine{Stream: Stream, Text: bytes.Clone(text), Truncated: cut})
	w.heldCost += max(len(text), 1)
	return false
}

// entry decides an entry, its start line at time t and the lines held below it, as one. It reports whether the page
// is done.
func (w *walk) entry(t time.Time, text []byte, cut bool) bool {
	held, full := w.held, w.full
	w.held, w.heldCost, w.full = nil, 0, false
	switch {
	case w.q.After != nil && !t.After(*w.q.After):
		return true // and nothing above it is newer
	case w.q.Before != nil && !t.Before(*w.q.Before):
		return false
	}
	w.add(held, t)
	if full || !w.fits(len(text)) {
		return true
	}
	w.add([]msg.LogLine{{Stream: Stream, Text: bytes.Clone(text), Truncated: cut}}, t)
	return w.q.Limit > 0 && len(w.kept) >= int(w.q.Limit)
}

// lead decides the lines held at the start of the file, above its first entry start, at the fetch time.
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

// finish is a line's text from raw, its first bytes: when whole says raw is all of it, raw less a final '\r';
// otherwise, or when that is still over budget, raw cut to budget bytes on a rune boundary, with cut set.
func finish(raw []byte, whole bool, budget int) (text []byte, cut bool) {
	if whole {
		raw = bytes.TrimSuffix(raw, []byte{'\r'})
		if len(raw) <= budget {
			return raw, false
		}
	}
	return cutRunes(raw[:budget], raw[budget]), true
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
