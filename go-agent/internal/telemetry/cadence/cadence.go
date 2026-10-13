// Package cadence resolves the telemetry push interval a session's limits set
// (AG-D30). The sender resolves it inside its SessionUp; the pipeline resolves
// the same value to log, count and export it.
package cadence

import (
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// DefaultPush is OR-73's push interval (AGW-D27 item 4), used when the DP
// sends none or sends one the agent refuses.
const DefaultPush = time.Minute

// MinPush is the floor for a DP-set push interval: a faster ticker only spends
// CPU (plan 007's final review measured about one core at 1 ns).
const MinPush = time.Second

// Ignored is what Resolve wants logged. Refused means a DP value was refused
// (and is counted); otherwise only the default could not apply.
type Ignored struct {
	Value   time.Duration // the DP's value; 0 when it sent none
	Reason  string
	Refused bool
}

// Resolve returns the push interval for a session (spec §3): 0 sends each
// batch as soon as it is written. bufferAge ≤ 0 means no age cap, as in
// buffer.Options.
func Resolve(l msg.Limits, bufferAge time.Duration) (time.Duration, *Ignored) {
	capped := func(v time.Duration) bool { return bufferAge > 0 && v >= bufferAge }
	fallback := func(ig *Ignored) (time.Duration, *Ignored) {
		if capped(DefaultPush) { // the default would expire every batch before it was due
			if ig == nil {
				ig = &Ignored{Reason: "default not below the buffer age cap"}
			}
			return 0, ig
		}
		return DefaultPush, ig
	}
	p := l.TelemetryPushInterval
	switch {
	case p == nil:
		return fallback(nil)
	case *p == 0:
		return 0, nil
	case *p < 0:
		return fallback(&Ignored{Value: *p, Reason: "negative", Refused: true})
	case *p < MinPush:
		return fallback(&Ignored{Value: *p, Reason: "below 1s", Refused: true})
	case capped(*p):
		return fallback(&Ignored{Value: *p, Reason: "not below the buffer age cap", Refused: true})
	}
	return *p, nil
}
