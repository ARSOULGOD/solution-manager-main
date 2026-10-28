package logfile

import (
	"testing"
	"time"
)

func TestEntryTimeParsesTheDefaultPattern(t *testing.T) {
	want := time.Date(2026, 10, 20, 4, 46, 6, 745e6, time.UTC)

	got, ok := entryTime([]byte("2026-10-20T04:46:06.745Z  INFO 1 --- [main] a.B : x"))
	if !ok || !got.Equal(want) || got.Location() != time.UTC {
		t.Fatalf("Z form: %v %v", got, ok)
	}
	got, ok = entryTime([]byte("2026-10-20T10:16:06.745+05:30  INFO 1 --- [main] a.B : x"))
	if !ok || !got.Equal(want) || got.Location() != time.UTC {
		t.Fatalf("+05:30 form: %v %v", got, ok)
	}
	for _, line := range []string{
		"\tat com.x.Y(Y.java:1)",
		"2026-10-20 04:46:06.745 INFO",
		"",
		" 2026-10-20T04:46:06.745Z",
		"Caused by: java.lang.IllegalStateException",
	} {
		if got, ok := entryTime([]byte(line)); ok {
			t.Errorf("%q parsed as an entry start: %v", line, got)
		}
	}
}

func TestEntryTimeIgnoresALongTokenWithoutParsingIt(t *testing.T) {
	long := make([]byte, 1<<20)
	for i := range long {
		long[i] = 'x'
	}
	if _, ok := entryTime(long); ok {
		t.Fatal("a megabyte of x parsed as an entry start")
	}
}

func TestSplitReturnsLinesEndsAndTheUnterminatedTail(t *testing.T) {
	lines, ends, tail := split([]byte("a\r\nbb\n\ntail"), 100)
	if len(lines) != 3 || string(lines[0]) != "a" || string(lines[1]) != "bb" || string(lines[2]) != "" {
		t.Fatalf("lines %q", lines)
	}
	if len(ends) != 3 || ends[0] != 103 || ends[1] != 106 || ends[2] != 107 {
		t.Fatalf("ends %v", ends)
	}
	if string(tail) != "tail" {
		t.Fatalf("tail %q", tail)
	}
	// A line must not be able to grow into its neighbour.
	if cap(lines[0]) != len(lines[0]) {
		t.Fatalf("cap %d for len %d", cap(lines[0]), len(lines[0]))
	}
}

func TestStampGivesContinuationLinesTheirEntryTime(t *testing.T) {
	t1 := time.Date(2026, 10, 20, 4, 46, 6, 0, time.UTC)
	t2 := t1.Add(time.Second)
	now := t1.Add(time.Hour)
	prev := t1.Add(-time.Minute)
	in := [][]byte{
		[]byte("\tat lead"),
		[]byte("2026-10-20T04:46:06Z  INFO a"),
		[]byte("\tat one"),
		[]byte("2026-10-20T04:46:07Z  WARN b"),
		[]byte("\tat two"),
	}
	cases := []struct {
		name string
		prev time.Time
		want []time.Time
	}{
		{"prev set", prev, []time.Time{prev, t1, t1, t2, t2}},
		{"prev zero: the next entry", time.Time{}, []time.Time{t1, t1, t1, t2, t2}},
	}
	for _, c := range cases {
		got := stamp(in, c.prev, now)
		for i, l := range got {
			if !l.TS.Equal(c.want[i]) || l.Stream != Stream {
				t.Errorf("%s: line %d: %v %q", c.name, i, l.TS, l.Stream)
			}
		}
	}
	got := stamp(in[:1], time.Time{}, now)
	if !got[0].TS.Equal(now) {
		t.Errorf("no entry at all: %v, want the fetch time", got[0].TS)
	}
}
