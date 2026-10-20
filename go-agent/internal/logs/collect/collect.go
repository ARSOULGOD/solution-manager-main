// Package collect follows one container's logs into its ring (agent-mi-logs
// design §3): it resumes from the newest stored Docker timestamp, never stores
// the boundary line twice, records every window it could not read as a gap, and
// backs off on Docker errors. MI output is never written to slog, nor placed in
// an error string.
package collect

import (
	"bytes"
	"context"
	"log/slog"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/logs/ring"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

type Source interface {
	FollowLogs(ctx context.Context, p runtime.Placement, since time.Time) (<-chan msg.LogLine, <-chan error)
	ResolveByName(ctx context.Context, containerName, bindingID string) (runtime.Placement, error)
}

type Collector struct {
	src     Source
	ring    *ring.Ring
	log     *slog.Logger
	backoff func(attempt int) time.Duration
}

// New: ring is already open (the caller chose its cap). backoff(attempt) is the
// wait before the next try; attempt counts consecutive unproductive sessions
// (1 for the first), and is 0 after a session that stored lines.
func New(src Source, r *ring.Ring, log *slog.Logger, backoff func(attempt int) time.Duration) *Collector {
	return &Collector{src: src, ring: r, log: log, backoff: backoff}
}

// pending is a window not yet readable; it becomes a gap when the first new line
// arrives, so its end is Docker's timestamp, not the agent clock.
type pending struct {
	set    bool
	from   time.Time
	reason string
}

// Run follows until ctx is cancelled (returns ctx.Err()) or the ring's cap is
// zero (collection off; returns nil — the caller restarts Run if it is turned
// back on). A ring write failure is returned.
func (c *Collector) Run(ctx context.Context, containerName, bindingID string) error {
	var (
		prevID   string
		gap      pending
		failures int
	)
	defer func() { // a window with no later line is closed at its start
		if gap.set && c.ring.Cap() != 0 {
			_ = c.ring.MarkGap(msg.LogGap{From: gap.from, To: gap.from, Reason: gap.reason})
		}
	}()
	pause := func(attempt int) bool {
		t := time.NewTimer(c.backoff(attempt))
		defer t.Stop()
		select {
		case <-t.C:
			return true
		case <-ctx.Done():
			return false
		}
	}
	markUnreadable := func() {
		if !gap.set {
			last, _ := c.ring.LastTimestamp()
			gap = pending{set: true, from: last, reason: msg.GapContainerUnreadable}
		}
	}
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		if c.ring.Cap() == 0 {
			return nil
		}
		p, err := c.src.ResolveByName(ctx, containerName, bindingID)
		if err != nil {
			markUnreadable()
			failures++
			c.log.Warn("log collector: container not resolvable", "binding_id", bindingID, "attempt", failures, "err", err)
			if !pause(failures) {
				return ctx.Err()
			}
			continue
		}
		if prevID != "" && p.ContainerID != prevID {
			markUnreadable() // re-created under the same name: its old output is gone
		}
		prevID = p.ContainerID

		last, has := c.ring.LastTimestamp()
		dd := newDeduper(c.ring, last, has)
		sctx, cancel := context.WithCancel(ctx)
		lines, errs := c.src.FollowLogs(sctx, p, last)
		got := false
	stream:
		for {
			select {
			case <-ctx.Done():
				cancel()
				return ctx.Err()
			case l, ok := <-lines:
				if !ok {
					break stream
				}
				if c.ring.Cap() == 0 {
					cancel()
					return nil
				}
				if dd.skip(l) {
					continue
				}
				if gap.set {
					to := l.TS
					if !gap.from.IsZero() && to.Before(gap.from) {
						to = gap.from
					}
					from := gap.from
					if from.IsZero() {
						from = to
					}
					if err := c.ring.MarkGap(msg.LogGap{From: from, To: to, Reason: gap.reason}); err != nil {
						cancel()
						return err
					}
					gap = pending{}
				}
				if err := c.ring.Append(l); err != nil {
					cancel()
					return err
				}
				got = true
			}
		}
		cancel()
		var serr error
		select {
		case serr = <-errs:
		case <-ctx.Done():
			return ctx.Err()
		}
		if serr != nil {
			markUnreadable()
			c.log.Warn("log collector: stream failed", "binding_id", bindingID, "err", serr)
		}
		if got {
			failures = 0
		} else {
			failures++
		}
		if !pause(failures) {
			return ctx.Err()
		}
	}
}

// deduper drops what resume would store twice. Docker's since is a timestamp,
// not a position, so lines already stored AT the boundary timestamp are
// consumed one-for-one: a distinct line sharing that timestamp is kept, and so
// is a genuine repeat beyond what is stored.
type deduper struct {
	has    bool
	last   time.Time
	stored []msg.LogLine
	used   []bool
}

func newDeduper(r *ring.Ring, last time.Time, has bool) *deduper {
	d := &deduper{has: has, last: last}
	if has {
		after, before := last.Add(-time.Nanosecond), last.Add(time.Nanosecond)
		d.stored, _, _ = r.Page(&after, &before, 0, 0)
		d.used = make([]bool, len(d.stored))
	}
	return d
}

func (d *deduper) skip(l msg.LogLine) bool {
	if !d.has {
		return false
	}
	if l.TS.Before(d.last) {
		return true // already covered by what is stored
	}
	if !l.TS.Equal(d.last) {
		return false
	}
	for i, s := range d.stored {
		if d.used[i] || s.Stream != l.Stream {
			continue
		}
		// the ring truncates over-long lines, so a stored Truncated line is a prefix
		if bytes.Equal(s.Text, l.Text) || (s.Truncated && bytes.HasPrefix(l.Text, s.Text)) {
			d.used[i] = true
			return true
		}
	}
	return false
}
