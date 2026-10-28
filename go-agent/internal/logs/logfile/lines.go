package logfile

import (
	"bytes"
	"time"
	"unicode/utf8"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// maxStamp bounds the token entryTime tries to parse: the longest RFC 3339 form is 35 bytes, so a longer token is
// text, and a megabyte line with no space is not copied to find that out.
const maxStamp = 40

// entryTime reports whether line starts a log entry: its first space-delimited token parses as RFC 3339, which is
// Spring Boot's default `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`. The time is in UTC. A stack-trace line, or a line in any other
// pattern, is not an entry start.
func entryTime(line []byte) (time.Time, bool) {
	head := line[:min(len(line), maxStamp+1)]
	i := bytes.IndexByte(head, ' ')
	if i < 0 {
		i = len(head)
	}
	if i == 0 || i > maxStamp {
		return time.Time{}, false
	}
	t, err := time.Parse(time.RFC3339, string(head[:i]))
	if err != nil {
		return time.Time{}, false
	}
	return t.UTC(), true
}

// split cuts b, whose first byte is at file offset base, into its '\n'-terminated lines (a trailing '\r' dropped) and
// the offset just past each '\n'. Each line is charged its length plus LineCost, and split stops before a line that
// would take the charge over budget, the first line excepted; rest is the bytes after the last line taken (the
// unterminated tail, when every line was taken). The lines alias b; each has its capacity cut to its length so that
// appending to one cannot overwrite the next.
func split(b []byte, base uint64, budget int) (lines [][]byte, ends []uint64, rest []byte) {
	off, charge := base, 0
	for {
		i := bytes.IndexByte(b, '\n')
		if i < 0 {
			return lines, ends, b
		}
		n := i
		if n > 0 && b[n-1] == '\r' {
			n--
		}
		if charge += n + LineCost; charge > budget && len(lines) > 0 {
			return lines, ends, b
		}
		off += uint64(i) + 1
		lines = append(lines, b[:n:n])
		ends = append(ends, off)
		b = b[i+1:]
	}
}

// stamp turns lines into LogLines. An entry start carries its own time and every continuation line the time of the
// entry above it. Continuation lines before the first entry start in the read take prev (the caller's previous
// chunk), else the first entry's time further down: in lines, else in rest (the read's bytes after them), else now.
func stamp(lines [][]byte, rest []byte, prev, now time.Time) []msg.LogLine {
	out := make([]msg.LogLine, len(lines))
	cur, first := prev, -1
	for i, l := range lines {
		if t, ok := entryTime(l); ok {
			cur = t
			if first < 0 {
				first = i
			}
		}
		out[i] = msg.LogLine{TS: cur, Stream: Stream, Text: l}
	}
	if prev.IsZero() { // the lines above the first entry have no time yet
		lead, n := now.UTC(), len(out)
		if first >= 0 {
			lead, n = out[first].TS, first
		} else if t, ok := firstEntry(rest); ok {
			lead = t
		}
		for i := range n {
			out[i].TS = lead
		}
	}
	return out
}

// firstEntry is the time of the first entry start among b's '\n'-terminated lines.
func firstEntry(b []byte) (time.Time, bool) {
	for {
		i := bytes.IndexByte(b, '\n')
		if i < 0 {
			return time.Time{}, false
		}
		if t, ok := entryTime(bytes.TrimSuffix(b[:i], []byte{'\r'})); ok {
			return t, true
		}
		b = b[i+1:]
	}
}

// cutRunes is the text of a line that was cut after len(kept) bytes, next being the first byte dropped: kept itself
// when the cut fell between runes, else shortened to the last rune boundary.
func cutRunes(kept []byte, next byte) []byte {
	n := len(kept)
	if !utf8.RuneStart(next) {
		for n--; n > 0 && !utf8.RuneStart(kept[n]); n-- {
		}
	}
	return kept[:max(n, 0)]
}
