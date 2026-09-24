// Package clock tracks the DP-reported clock offset. Reducers never read a
// clock; the loop reads Offset() into Env for every event.
package clock

import (
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

type Clock struct {
	mu     sync.Mutex
	offset time.Duration
	state  msg.ClockState
}

func (c *Clock) Offset() time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.offset
}

func (c *Clock) State() msg.ClockState {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state == "" {
		return msg.ClockOK
	}
	return c.state
}

// Update stores the latest HeartbeatAck values verbatim — the DP computed
// them; no smoothing.
func (c *Clock) Update(offsetMs int64, state msg.ClockState) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.offset = time.Duration(offsetMs) * time.Millisecond
	c.state = state
}

// Set derives the offset from a server clock observed at localAt.
func (c *Clock) Set(serverClock, localAt time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.offset = serverClock.Sub(localAt)
	c.state = msg.ClockOK
}
