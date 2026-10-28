package logfile

import (
	"bytes"
	"context"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// Chunk is what one Read found.
type Chunk struct {
	Lines []msg.LogLine
	Ends  []uint64 // Ends[i]: the offset just past Lines[i]'s '\n'
	Next  uint64   // where the next read starts
	Size  uint64   // the file size the server reported in this read's last reply (0 is a real size: an empty file)
}

// Read returns the whole lines from byte offset from, never reading at or past until (0 = no bound), with at most
// textBudget bytes of text. prev is the time for continuation lines before the read's first entry start.
//
// Only '\n'-terminated lines are returned: an unterminated tail is the MI mid-write and is left for the next read, so
// Next never moves past it. When from is not a line start (the byte before it is not '\n') the bytes up to the next
// line start are skipped, and they still advance Next even when no line comes back. A line longer than textBudget is
// returned cut on a rune boundary with Truncated set; its remainder is read and thrown away in budget-sized ranges, so
// Ends and Next are past its '\n'. No line is ever returned twice or out of order, and Next always moves forward
// unless the file has nothing more to give.
//
// An error is ErrUnavailable (see there). A file that is shorter than from, or empty, is not an error: Size says so.
// Every call that returns no error makes at least one request, so Size is always a size the server reported. That
// includes from >= until, where there is nothing to read: one byte below until is asked for, to learn the size.
func (f *File) Read(ctx context.Context, from, until uint64, textBudget int, prev time.Time) (Chunk, error) {
	ch := Chunk{Next: from}
	if until != 0 && from >= until {
		// Nothing to read, but the caller (an export that has reached its snapshot) still needs the file's size to
		// tell "done" from "the file shrank". One byte below until is within the bound.
		rp, err := f.get(ctx, until-1, until-1)
		if err != nil {
			return ch, err
		}
		ch.Size = rp.size
		_, err = rp.bytes()
		return ch, err
	}
	textBudget = max(textBudget, 1)
	budget := uint64(textBudget)

	// The byte before from tells whether from is a line start.
	start := from
	if from > 0 {
		start--
	}
	last := lastByte(from, budget, until)
	rp, err := f.get(ctx, start, last)
	if err != nil {
		return ch, err
	}
	ch.Size = rp.size
	if rp.body == nil { // the range starts past the end, or the file is empty
		return ch, nil
	}
	buf, err := rp.bytes()
	if err != nil {
		return ch, err
	}
	end := rp.off + uint64(len(buf)) // just past the last byte received
	exhausted := end >= rp.size || (until != 0 && end >= until)

	text, pos := buf, 0
	if from > 0 {
		text = buf[1:]
		if buf[0] != '\n' { // from is inside a line: skip to the start of the next
			i := bytes.IndexByte(text, '\n')
			if i < 0 {
				// All of it is the tail of a line that began before from. Past the end of what is readable there
				// is nothing to move to; otherwise the skipped bytes are progress.
				if !exhausted {
					ch.Next = from + uint64(len(text))
				}
				return ch, nil
			}
			pos = i + 1
		}
	}
	lineStart := from + uint64(pos)
	lines, ends, tail := split(text[pos:], lineStart)
	switch {
	case len(lines) > 0:
		ch.Lines, ch.Ends = stamp(lines, prev, f.now()), ends
		ch.Next = ends[len(ends)-1]
		return ch, nil
	case len(tail) == 0 || exhausted || pos > 0:
		// Nothing complete here: an unterminated tail, or (pos > 0) a line that may fit a budget of its own and is
		// read whole from its start next time.
		ch.Next = lineStart
		return ch, nil
	}
	// One line fills the whole budget without ending.
	return f.longLine(ctx, ch, tail, until, budget, prev)
}

// longLine finishes a line that began at ch.Next and is at least kept long with no '\n' yet: it keeps the first
// len(kept) bytes, reads the rest of the line in ranges of at most budget bytes without keeping them, and returns it
// cut at a rune boundary with Truncated set, or whole when nothing but its terminator follows. If the file ends (or
// reaches until, or shrinks) before the '\n', the line is an unterminated tail and nothing is returned.
func (f *File) longLine(ctx context.Context, ch Chunk, kept []byte, until, budget uint64, prev time.Time) (Chunk, error) {
	cur := ch.Next + uint64(len(kept)) // the first byte not yet seen
	var next byte                      // the first byte after kept
	var skipped int                    // bytes after kept, before the '\n', including a '\r'
	for firstRange := true; ; firstRange = false {
		rp, err := f.get(ctx, cur, lastByte(cur, budget, until))
		if err != nil {
			return ch, err
		}
		ch.Size = rp.size
		if rp.body == nil { // the file shrank under us
			return ch, nil
		}
		n, first, found, err := rp.skipLine()
		rp.close()
		if err != nil {
			return ch, err
		}
		if firstRange {
			next = first
		}
		if found {
			skipped += n - 1
			cur += uint64(n)
			break
		}
		skipped += n
		cur += uint64(n)
		if cur >= rp.size || (until != 0 && cur >= until) {
			return ch, nil
		}
	}

	text, truncated := kept, true
	switch {
	case skipped == 0: // exactly the budget, then '\n': kept may end with the '\r' of "\r\n"
		text, truncated = kept, false
		if n := len(text); n > 0 && text[n-1] == '\r' {
			text = text[:n-1]
		}
	case skipped == 1 && next == '\r': // exactly the budget, then "\r\n"
		truncated = false
	default:
		text = cutRunes(kept, next)
	}
	ln := stamp([][]byte{text[:len(text):len(text)]}, prev, f.now())
	ln[0].Truncated = truncated
	ch.Lines, ch.Ends, ch.Next = ln, []uint64{cur}, cur
	return ch, nil
}
