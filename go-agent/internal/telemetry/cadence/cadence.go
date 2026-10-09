// Package cadence resolves the telemetry pull and push intervals the Data
// Plane sets on SessionAccept.limits (AG-D26), and holds the values in use
// for the collectors and the pipeline's gauges. The sender resolves the push
// interval itself, with the same Resolve, inside its SessionUp.
package cadence

import (
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// Settings are the intervals in use. Zero means the local default: each
// binding's declared interval, and sending each batch as soon as it is written.
type Settings struct {
	Pull time.Duration
	Push time.Duration
}

// Ignored names a DP value the agent refused; the local default applies.
type Ignored struct {
	Limit  string // "pull" | "push"
	Value  time.Duration
	Reason string
}

// MinPull is the declarations file's own floor for interval (AG-D22).
const MinPull = time.Second

// Resolve applies spec §2.2. bufferAge is --telemetry-buffer-age; 0 means no
// age cap, as in buffer.Options, so push then has no upper bound.
func Resolve(l msg.Limits, bufferAge time.Duration) (Settings, []Ignored) {
	var s Settings
	var ig []Ignored
	switch p := l.TelemetryPullInterval; {
	case p == 0:
	case p < MinPull:
		ig = append(ig, Ignored{Limit: "pull", Value: p, Reason: "below 1s"})
	default:
		s.Pull = p
	}
	switch p := l.TelemetryPushInterval; {
	case p == 0:
	case p < 0:
		ig = append(ig, Ignored{Limit: "push", Value: p, Reason: "negative"})
	case bufferAge > 0 && p >= bufferAge:
		// every batch would expire before it was due
		ig = append(ig, Ignored{Limit: "push", Value: p, Reason: "not below the buffer age cap"})
	default:
		s.Push = p
	}
	return s, ig
}

// Holder is the values in use, in view.Hub's idiom: take Changed before
// Current, so a Set between the two is never missed.
type Holder struct {
	mu      sync.Mutex
	cur     Settings
	changed chan struct{}
}

func NewHolder() *Holder { return &Holder{changed: make(chan struct{})} }

// Set stores s and reports whether it differed. Equal values signal nothing,
// so a reconnect that repeats them resets no ticker.
func (h *Holder) Set(s Settings) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if s == h.cur {
		return false
	}
	h.cur = s
	close(h.changed)
	h.changed = make(chan struct{})
	return true
}

func (h *Holder) Current() Settings {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.cur
}

// Changed is closed by the next Set that changes the values.
func (h *Holder) Changed() <-chan struct{} {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.changed
}
