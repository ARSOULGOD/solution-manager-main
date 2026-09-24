package clock

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestOffsetAndState(t *testing.T) {
	var c Clock
	if c.Offset() != 0 || c.State() != msg.ClockOK {
		t.Fatal("zero value must be OK/0")
	}
	c.Update(-1500, msg.ClockSuspect)
	if c.Offset() != -1500*time.Millisecond || c.State() != msg.ClockSuspect {
		t.Fatalf("%v %s", c.Offset(), c.State())
	}
	local := time.Unix(1000, 0)
	c.Set(local.Add(3*time.Second), local)
	if c.Offset() != 3*time.Second || c.State() != msg.ClockOK {
		t.Fatalf("Set must derive offset and reset state: %v %s", c.Offset(), c.State())
	}
}
