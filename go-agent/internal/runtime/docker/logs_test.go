package docker

import (
	"bytes"
	"context"
	"io"
	"strings"
	"testing"
	"testing/iotest"
	"time"
	"unicode/utf8"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/pkg/stdcopy"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

func logEngine(tty bool, body []byte, seen *container.LogsOptions) *fakeEngine {
	return &fakeEngine{
		inspect: map[string]types.ContainerJSON{"c1": {
			ContainerJSONBase: &types.ContainerJSONBase{ID: "c1", Name: "/java1"},
			Config:            &container.Config{Tty: tty},
		}},
		logs: func(id string, opts container.LogsOptions) (io.ReadCloser, error) {
			if seen != nil {
				*seen = opts
			}
			return io.NopCloser(bytes.NewReader(body)), nil
		},
	}
}

func drain(t *testing.T, lines <-chan msg.LogLine, errs <-chan error) ([]msg.LogLine, error) {
	t.Helper()
	var out []msg.LogLine
	timeout := time.After(5 * time.Second)
	for {
		select {
		case l, ok := <-lines:
			if !ok {
				return out, <-errs
			}
			out = append(out, l)
		case <-timeout:
			t.Fatal("FollowLogs did not finish")
		}
	}
}

func TestFollowLogsDemultiplexesAndKeepsDockerTimestamps(t *testing.T) {
	var buf bytes.Buffer
	so, se := stdcopy.NewStdWriter(&buf, stdcopy.Stdout), stdcopy.NewStdWriter(&buf, stdcopy.Stderr)
	// a stdout line split across two frames, an interleaved stderr line, and a
	// trailing stdout fragment with no newline (held until the stream ends)
	so.Write([]byte("2026-10-01T10:00:00.000000001Z hel"))
	se.Write([]byte("2026-10-01T10:00:00.000000002Z oops\n"))
	so.Write([]byte("lo\n2026-10-01T10:00:01.5Z tail"))
	var seen container.LogsOptions
	a := New(logEngine(false, buf.Bytes(), &seen))
	since := time.Date(2026, 10, 1, 9, 59, 59, 123, time.UTC)
	lines, errs := a.FollowLogs(context.Background(), runtime.Placement{ContainerID: "c1"}, since)
	got, err := drain(t, lines, errs)
	if err != nil {
		t.Fatal(err)
	}
	if !seen.Follow || !seen.Timestamps || !seen.ShowStdout || !seen.ShowStderr {
		t.Fatalf("options: %+v", seen)
	}
	if seen.Since != "1790848799.000000123" {
		t.Fatalf("since: %q", seen.Since)
	}
	want := []struct{ stream, text, ts string }{
		{"stderr", "oops", "2026-10-01T10:00:00.000000002Z"},
		{"stdout", "hello", "2026-10-01T10:00:00.000000001Z"},
		{"stdout", "tail", "2026-10-01T10:00:01.5Z"},
	}
	if len(got) != len(want) {
		t.Fatalf("got %d lines: %+v", len(got), got)
	}
	for i, w := range want {
		wt, _ := time.Parse(time.RFC3339Nano, w.ts)
		if got[i].Stream != w.stream || string(got[i].Text) != w.text || !got[i].TS.Equal(wt) {
			t.Fatalf("line %d: %+v want %+v", i, got[i], w)
		}
	}
}

func TestFollowLogsTTYContainerIsARawStream(t *testing.T) {
	body := []byte("2026-10-01T10:00:00Z one\r\n2026-10-01T10:00:01Z two\r\n")
	a := New(logEngine(true, body, nil))
	lines, errs := a.FollowLogs(context.Background(), runtime.Placement{ContainerID: "c1"}, time.Time{})
	got, err := drain(t, lines, errs)
	if err != nil || len(got) != 2 {
		t.Fatalf("%v %+v", err, got)
	}
	if got[0].Stream != "stdout" || string(got[0].Text) != "one" || string(got[1].Text) != "two" {
		t.Fatalf("%+v", got)
	}
}

func TestFollowLogsReportsEngineErrorAndStopsOnCancel(t *testing.T) {
	e := logEngine(false, nil, nil)
	e.logs = func(string, container.LogsOptions) (io.ReadCloser, error) { return nil, io.ErrUnexpectedEOF }
	lines, errs := New(e).FollowLogs(context.Background(), runtime.Placement{ContainerID: "c1"}, time.Time{})
	if _, err := drain(t, lines, errs); err == nil {
		t.Fatal("expected the engine error")
	}

	pr, pw := io.Pipe()
	e.logs = func(string, container.LogsOptions) (io.ReadCloser, error) { return pr, nil }
	ctx, cancel := context.WithCancel(context.Background())
	lines, errs = New(e).FollowLogs(ctx, runtime.Placement{ContainerID: "c1"}, time.Time{})
	cancel()
	if _, err := drain(t, lines, errs); err != nil {
		t.Fatalf("cancel is not an error: %v", err)
	}
	pw.Close()
}

func TestFollowLogsHoldsAnOversizedPartialLineBounded(t *testing.T) {
	var buf bytes.Buffer
	so := stdcopy.NewStdWriter(&buf, stdcopy.Stdout)
	so.Write([]byte("2026-10-01T10:00:00Z "))
	big := bytes.Repeat([]byte("x"), 3<<20)
	so.Write(big)
	so.Write([]byte("\n2026-10-01T10:00:01Z next\n"))
	lines, errs := New(logEngine(false, buf.Bytes(), nil)).FollowLogs(context.Background(), runtime.Placement{ContainerID: "c1"}, time.Time{})
	got, err := drain(t, lines, errs)
	if err != nil || len(got) != 2 {
		t.Fatalf("%v %d", err, len(got))
	}
	if !got[0].Truncated || len(got[0].Text) > maxHeldLine || string(got[1].Text) != "next" {
		t.Fatalf("truncated=%v len=%d next=%q", got[0].Truncated, len(got[0].Text), got[1].Text)
	}
}

// Verified in moby (daemon/logger/copier.go:147-155, api/server/httputils/
// write_log_stream.go): a line over 16 KiB is split into partial messages that
// share the first one's timestamp, each written with its OWN prefix and no
// newline between them. So continuation prefixes appear mid-line.
func TestFollowLogsStripsContinuationChunkPrefixesOfALongLine(t *testing.T) {
	const ts = "2026-10-01T10:00:00.123456789Z"
	c1, c2, c3 := strings.Repeat("a", 16384), strings.Repeat("b", 16384), "tail"
	var buf bytes.Buffer
	so := stdcopy.NewStdWriter(&buf, stdcopy.Stdout)
	so.Write([]byte(ts + " " + c1))
	so.Write([]byte(ts + " " + c2))
	so.Write([]byte(ts + " " + c3 + "\n"))
	so.Write([]byte("2026-10-01T10:00:01.000000000Z next\n"))
	lines, errs := New(logEngine(false, buf.Bytes(), nil)).FollowLogs(context.Background(), runtime.Placement{ContainerID: "c1"}, time.Time{})
	got, err := drain(t, lines, errs)
	if err != nil || len(got) != 2 {
		t.Fatalf("%v %d", err, len(got))
	}
	if string(got[0].Text) != c1+c2+c3 || string(got[1].Text) != "next" {
		t.Fatalf("text not byte-exact: len=%d", len(got[0].Text))
	}
}

// A TTY stream is unframed, so a prefix can be split across reads.
func TestFollowLogsTTYStripsContinuationPrefixSplitAcrossReads(t *testing.T) {
	const ts = "2026-10-01T10:00:00.123456789Z"
	body := []byte(ts + " abc" + ts + " def\r\n")
	e := logEngine(true, nil, nil)
	e.logs = func(string, container.LogsOptions) (io.ReadCloser, error) {
		return io.NopCloser(iotest.OneByteReader(bytes.NewReader(body))), nil
	}
	lines, errs := New(e).FollowLogs(context.Background(), runtime.Placement{ContainerID: "c1"}, time.Time{})
	got, err := drain(t, lines, errs)
	if err != nil || len(got) != 1 || string(got[0].Text) != "abcdef" {
		t.Fatalf("%v %+v", err, got)
	}
}

func TestFollowLogsOversizedLineIsCutOnARuneBoundary(t *testing.T) {
	var buf bytes.Buffer
	so := stdcopy.NewStdWriter(&buf, stdcopy.Stdout)
	so.Write([]byte("2026-10-01T10:00:00Z "))
	so.Write(bytes.Repeat([]byte("x"), maxHeldLine-len("2026-10-01T10:00:00Z ")-1)) // leaves one byte of room
	so.Write([]byte("\u00e9\u00e9\n"))                                              // 2-byte runes straddle the limit
	lines, errs := New(logEngine(false, buf.Bytes(), nil)).FollowLogs(context.Background(), runtime.Placement{ContainerID: "c1"}, time.Time{})
	got, err := drain(t, lines, errs)
	if err != nil || len(got) != 1 || !got[0].Truncated || !utf8.Valid(got[0].Text) {
		t.Fatalf("%v valid=%v", err, len(got) == 1 && utf8.Valid(got[0].Text))
	}
}

func TestFollowLogsUnprefixedFirstLineGetsANonZeroTimestamp(t *testing.T) {
	since := time.Date(2026, 10, 1, 9, 0, 0, 0, time.UTC)
	lines, errs := New(logEngine(false, mux("no prefix here\n"), nil)).FollowLogs(context.Background(), runtime.Placement{ContainerID: "c1"}, since)
	got, err := drain(t, lines, errs)
	if err != nil || len(got) != 1 || !got[0].TS.Equal(since) {
		t.Fatalf("%v %+v", err, got)
	}
	lines, errs = New(logEngine(false, mux("no prefix here\n"), nil)).FollowLogs(context.Background(), runtime.Placement{ContainerID: "c1"}, time.Time{})
	got, err = drain(t, lines, errs)
	if err != nil || len(got) != 1 || got[0].TS.IsZero() {
		t.Fatalf("%v %+v", err, got)
	}
}

func mux(s string) []byte {
	var b bytes.Buffer
	stdcopy.NewStdWriter(&b, stdcopy.Stdout).Write([]byte(s))
	return b.Bytes()
}
