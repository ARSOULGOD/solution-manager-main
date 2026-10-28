// Package logfile reads a Spring Boot MI's /actuator/logfile over ranged HTTP (agent-mi-logs-from-actuator design
// §4): the MI's own file is the only copy of its logs, so each read asks for the bytes it needs, turns them into
// lines, and keeps nothing. Reading never holds more than one budget of text (plus one fixed scratch buffer), however
// large the file or the line. The MI's log text is never placed in an error string or written to slog (I6).
package logfile

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const (
	// Path is where Spring Boot serves the log file; a moved base path or context path is not supported.
	Path = "/actuator/logfile"
	// Stream is the LogLine.Stream of every line read here.
	Stream = "file"
	// DefaultStep is how far a page reads at a time.
	DefaultStep = 64 << 10
)

// ErrUnavailable means the file cannot be read now: nothing answered, the answer was a status or a body this package
// does not accept (401, 403, 404, 5xx, a redirect, a whole-file 200), or it did not make sense. A returned error wraps
// it with a status code or a fixed phrase, and with the transport's own error (a context error stays visible to
// errors.Is), never with response bytes.
var ErrUnavailable = errors.New("logfile: unavailable")

// File is one MI's log file, addressed by the MI's host:port. It holds no state between reads and is safe for
// concurrent use.
type File struct {
	url    string
	client *http.Client
	step   int
	now    func() time.Time
}

type Option func(*File)

// WithClient replaces the HTTP client (tests, or a deployment needing different transport rules).
func WithClient(c *http.Client) Option {
	return func(f *File) {
		if c != nil {
			f.client = c
		}
	}
}

// WithStep sets the read step of a page; n < 1 is ignored.
func WithStep(n int) Option {
	return func(f *File) {
		if n > 0 {
			f.step = n
		}
	}
}

// WithNow sets the clock that stamps lines with no time of their own.
func WithNow(now func() time.Time) Option {
	return func(f *File) {
		if now != nil {
			f.now = now
		}
	}
}

// defaultClient is telemetry/collect's pair of rules: never a proxy (a container-IP read must not leave the host
// through HTTP_PROXY), and never a redirect, so a 3xx reaches the status rules as a response. It has no timeout of
// its own: the context of every call carries the deadline.
var defaultClient = &http.Client{
	Transport:     &http.Transport{Proxy: nil},
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
}

// New addresses the log file of the MI at addr, a host:port. No request is made.
func New(addr string, opts ...Option) *File {
	f := &File{url: "http://" + addr + Path, client: defaultClient, step: DefaultStep, now: time.Now}
	for _, o := range opts {
		o(f)
	}
	return f
}

func unavailable(format string, args ...any) error {
	return fmt.Errorf("%w: "+format, append([]any{ErrUnavailable}, args...)...)
}

// reply is the accepted answer to one ranged GET. body, when not nil, yields exactly n bytes, the file's bytes from
// offset off; it is nil for a range past the end and for an empty file, which carry only the size.
type reply struct {
	off, size uint64
	n         int
	body      io.ReadCloser
}

// limited is a body bounded to what was asked for: a server that sends more cannot make us read it.
type limited struct {
	io.Reader
	io.Closer
}

func (r *reply) close() {
	if r.body != nil {
		r.body.Close()
	}
}

// bytes reads the whole body and closes it.
func (r *reply) bytes() ([]byte, error) {
	if r.body == nil {
		return nil, nil
	}
	defer r.close()
	b := make([]byte, r.n)
	if _, err := io.ReadFull(r.body, b); err != nil {
		return nil, fmt.Errorf("%w: reading the range: %w", ErrUnavailable, err)
	}
	return b, nil
}

// skipLine reads the body, keeping none of it, up to and including the first '\n'. It reports how many bytes it
// consumed, the first of them, and whether it found the '\n'. The body is left open.
func (r *reply) skipLine() (n int, first byte, found bool, err error) {
	var scratch [4 << 10]byte
	for {
		m, rerr := r.body.Read(scratch[:])
		if m > 0 {
			if n == 0 {
				first = scratch[0]
			}
			if i := bytes.IndexByte(scratch[:m], '\n'); i >= 0 {
				return n + i + 1, first, true, nil
			}
			n += m
		}
		switch {
		case rerr == io.EOF && n == r.n:
			return n, first, false, nil
		case rerr == io.EOF:
			return n, first, false, unavailable("range ended short")
		case rerr != nil:
			return n, first, false, fmt.Errorf("%w: reading the range: %w", ErrUnavailable, rerr)
		}
	}
}

// get asks for bytes first..last (inclusive) and applies the status rules:
//
//	206  accepted when its Content-Range is exactly the range asked for, clamped to the file's end;
//	416  the range starts past the end; Content-Range `bytes */size` gives the size (an empty file is 416 `*/0`);
//	200  accepted only as an empty file (Content-Length 0, as http.ServeContent answers one); any other 200 means the
//	     server ignored Range, and its body is not read;
//	anything else, and any transport error, is ErrUnavailable.
//
// A returned reply with a body must be closed by the caller.
func (f *File) get(ctx context.Context, first, last uint64) (*reply, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, f.url, nil)
	if err != nil {
		return nil, unavailable("bad request")
	}
	req.Header.Set("Range", "bytes="+strconv.FormatUint(first, 10)+"-"+strconv.FormatUint(last, 10))
	resp, err := f.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrUnavailable, err)
	}
	switch resp.StatusCode {
	case http.StatusPartialContent:
		a, b, total, data, ok := contentRange(resp.Header.Get("Content-Range"))
		// The server must give what was asked: clamped to the end of the file, never narrower, wider or elsewhere.
		if !ok || !data || a != first || b > last || b >= total || (b != last && b != total-1) {
			resp.Body.Close()
			return nil, unavailable("unusable Content-Range")
		}
		n := int(b - a + 1)
		return &reply{off: a, size: total, n: n, body: limited{io.LimitReader(resp.Body, int64(n)), resp.Body}}, nil
	case http.StatusRequestedRangeNotSatisfiable:
		resp.Body.Close()
		_, _, total, data, ok := contentRange(resp.Header.Get("Content-Range"))
		if !ok || data {
			return nil, unavailable("status 416 without a size")
		}
		return &reply{size: total}, nil
	case http.StatusOK:
		resp.Body.Close()
		if resp.ContentLength == 0 {
			return &reply{}, nil
		}
		return nil, unavailable("status 200 to a ranged request")
	default:
		resp.Body.Close()
		return nil, unavailable("status %d", resp.StatusCode)
	}
}

// contentRange parses `bytes a-b/total` (data true) or `bytes */total` (data false). The total must be a number.
func contentRange(h string) (a, b, total uint64, data, ok bool) {
	rest, found := strings.CutPrefix(h, "bytes ")
	if !found {
		return 0, 0, 0, false, false
	}
	span, tot, found := strings.Cut(rest, "/")
	if !found {
		return 0, 0, 0, false, false
	}
	total, err := strconv.ParseUint(tot, 10, 64)
	if err != nil {
		return 0, 0, 0, false, false
	}
	if span == "*" {
		return 0, 0, total, false, true
	}
	as, bs, found := strings.Cut(span, "-")
	if !found {
		return 0, 0, 0, false, false
	}
	a, errA := strconv.ParseUint(as, 10, 64)
	b, errB := strconv.ParseUint(bs, 10, 64)
	if errA != nil || errB != nil || a > b {
		return 0, 0, 0, false, false
	}
	return a, b, total, true, true
}

// lastByte is the last byte of a range of n bytes from first, kept below until (0 = no bound).
func lastByte(first, n, until uint64) uint64 {
	last := first + n - 1
	if last < first { // overflow: a start no file reaches
		last = ^uint64(0)
	}
	if until != 0 {
		last = min(last, until-1)
	}
	return last
}
