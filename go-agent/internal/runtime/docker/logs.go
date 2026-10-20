package docker

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"strconv"
	"time"
	"unicode/utf8"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/pkg/stdcopy"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

// maxHeldLine bounds the bytes held for one line that has not seen its newline
// yet. Past it the rest of that line is discarded and the line is emitted
// Truncated at the next newline; the ring applies its own, smaller, limit.
const maxHeldLine = 1 << 20

// FollowLogs streams the container's stdout and stderr as lines carrying
// DOCKER's own timestamps (Timestamps: true, never the agent clock), starting
// at since (zero: from the beginning). Verified against docker v27.1.2:
// ContainerLogs returns a stdcopy-multiplexed stream for a non-TTY container
// and a raw single stream for a TTY one (so Config.Tty is inspected), and Since
// is passed as "sec.nsec".
//
// Contract: lines is closed when the stream ends, after the error (if any) has
// been delivered on errs, which is buffered and then closed; a ctx
// cancellation is not an error. A partial trailing line is emitted at stream
// end, never mid-line. Line text is MI output and is never logged here.
func (a *Adapter) FollowLogs(ctx context.Context, p runtime.Placement, since time.Time) (<-chan msg.LogLine, <-chan error) {
	lines, errs := make(chan msg.LogLine), make(chan error, 1)
	go func() {
		err := a.follow(ctx, p, since, lines)
		if err != nil && ctx.Err() == nil {
			errs <- err
		}
		close(errs)
		close(lines)
	}()
	return lines, errs
}

func (a *Adapter) follow(ctx context.Context, p runtime.Placement, since time.Time, out chan<- msg.LogLine) error {
	insp, err := a.cli.ContainerInspect(ctx, p.ContainerID)
	if err != nil {
		return fmt.Errorf("docker: inspect for logs: %w", err)
	}
	tty := insp.Config != nil && insp.Config.Tty
	opts := container.LogsOptions{ShowStdout: true, ShowStderr: true, Timestamps: true, Follow: true, Tail: "all"}
	if !since.IsZero() {
		s := since.UTC()
		opts.Since = strconv.FormatInt(s.Unix(), 10) + "." + fmt.Sprintf("%09d", s.Nanosecond())
	}
	rc, err := a.cli.ContainerLogs(ctx, p.ContainerID, opts)
	if err != nil {
		return fmt.Errorf("docker: open logs: %w", err)
	}
	defer rc.Close()
	stop := context.AfterFunc(ctx, func() { rc.Close() }) // unblocks a read parked on an idle stream
	defer stop()

	emit := func(l msg.LogLine) error {
		select {
		case out <- l:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	so := &lineSplitter{stream: "stdout", tty: tty, emit: emit, since: since}
	se := &lineSplitter{stream: "stderr", tty: tty, emit: emit, since: since}
	if tty {
		_, err = io.Copy(so, rc)
	} else {
		_, err = stdcopy.StdCopy(so, se, rc)
	}
	if ctx.Err() != nil {
		return nil
	}
	if err != nil {
		return fmt.Errorf("docker: read logs: %w", err)
	}
	if err := so.flush(); err != nil {
		return err
	}
	return se.flush()
}

// lineSplitter turns a byte stream into lines, holding a partial trailing line.
type lineSplitter struct {
	stream string
	tty    bool
	emit   func(msg.LogLine) error
	buf    []byte
	over   bool // the held line already exceeded maxHeldLine; the rest is discarded
	lastTS time.Time
	since  time.Time // last-resort timestamp for a line with no usable prefix
}

func (s *lineSplitter) Write(p []byte) (int, error) {
	n := len(p)
	for len(p) > 0 {
		i := bytes.IndexByte(p, '\n')
		if i < 0 {
			s.hold(p)
			return n, nil
		}
		s.hold(p[:i])
		if err := s.line(); err != nil {
			return 0, err
		}
		p = p[i+1:]
	}
	return n, nil
}

func (s *lineSplitter) hold(b []byte) {
	room := maxHeldLine - len(s.buf)
	if len(b) > room {
		b, s.over = b[:max(room, 0)], true
	}
	s.buf = append(s.buf, b...)
	if s.over { // never end the kept bytes inside a multibyte rune
		i := len(s.buf) - 1
		for i > 0 && !utf8.RuneStart(s.buf[i]) {
			i--
		}
		if i >= 0 && !utf8.FullRune(s.buf[i:]) {
			s.buf = s.buf[:i]
		}
	}
}

func (s *lineSplitter) flush() error {
	if len(s.buf) == 0 && !s.over {
		return nil
	}
	return s.line()
}

// line emits the held bytes as one line: "<RFC3339Nano timestamp> <text>".
func (s *lineSplitter) line() error {
	b := s.buf
	if s.tty {
		b = bytes.TrimSuffix(b, []byte("\r"))
	}
	ts, text := s.lastTS, b
	if ts.IsZero() {
		ts = s.since
	}
	if ts.IsZero() {
		ts = time.Unix(0, 0).UTC() // no prefix and no bound: still a non-zero time
	}
	if i := bytes.IndexByte(b, ' '); i > 0 {
		if t, err := time.Parse(time.RFC3339Nano, string(b[:i])); err == nil {
			ts, text = t.UTC(), b[i+1:]
			// A line over 16 KiB reaches us as partial messages that share the
			// first one's timestamp, each written with its own prefix and no
			// newline between them (daemon/logger/copier.go:147-155,
			// api/server/httputils/write_log_stream.go). Remove exactly those
			// repeats of this line's own prefix.
			if tok := b[:i+1]; bytes.Contains(text, tok) {
				text = bytes.ReplaceAll(text, tok, nil)
			}
		}
	}
	l := msg.LogLine{TS: ts, Stream: s.stream, Text: append([]byte(nil), text...), Truncated: s.over}
	s.lastTS = ts
	s.buf, s.over = s.buf[:0], false
	return s.emit(l)
}
