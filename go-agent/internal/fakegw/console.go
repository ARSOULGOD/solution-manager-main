package fakegw

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

const consoleUsage = "type: preflight | apply <file> | restart | stop  [@<binding>|@all] | pause | resume | disconnect | set push <duration>|unset"

// Console turns lines typed at the gateway's terminal into commands for the
// connected agent, one operation per line, and prints each outcome when the
// agent acks it. A word "@<binding>" or "@all", anywhere on a command's line,
// picks the binding; it may be left out only when the lease has one. It
// returns when r is exhausted.
func (s *Server) Console(r io.Reader, w io.Writer) {
	c := &console{s: s, w: w}
	c.println(consoleUsage)
	sc := bufio.NewScanner(r)
	for sc.Scan() {
		c.line(sc.Text())
	}
}

type console struct {
	s  *Server
	mu sync.Mutex // acks print from their own goroutines
	w  io.Writer
}

func (c *console) println(format string, a ...any) {
	c.mu.Lock()
	defer c.mu.Unlock()
	fmt.Fprintf(c.w, format+"\n", a...)
}

func (c *console) line(text string) {
	f := strings.Fields(text)
	if len(f) == 0 {
		return
	}
	switch f[0] {
	case "pause":
		c.s.PauseTelemetry()
		c.println("telemetry paused: acks grant no credits")
		return
	case "resume":
		n := max(c.s.cfg.Scenario.Limits.InitialCredits, 1)
		c.s.ResumeTelemetry(n)
		c.println("telemetry resumed: %d credits granted", n)
		return
	case "disconnect":
		if err := c.s.Disconnect(); err != nil {
			c.println("%v", err)
			return
		}
		c.println("session closed; the agent will reconnect")
		return
	case "set":
		if len(f) != 3 || f[1] != "push" {
			c.println("usage: set push <duration>|unset")
			return
		}
		p, err := ParsePushSpec(f[2])
		if err != nil {
			c.println("set: %v", err)
			return
		}
		c.s.SetPushInterval(p)
		c.println("next session: push=%s (disconnect to apply)", p)
		return
	}
	var args, at []string
	for _, w := range f[1:] {
		if t, ok := strings.CutPrefix(w, "@"); ok {
			at = append(at, t)
		} else {
			args = append(args, w)
		}
	}
	op := msg.Operation(f[0])
	switch op {
	case msg.OpPreflight, msg.OpApply, msg.OpRestart, msg.OpStop:
	default:
		c.println("unknown command %q — %s", f[0], consoleUsage)
		return
	}
	bindings, err := targets(c.s.cfg.Scenario.Lease.BindingIDs, at)
	if err != nil {
		c.println("%v", err)
		return
	}
	var payload []byte
	if op == msg.OpApply {
		if len(args) == 0 {
			c.println("apply needs a file: apply <file>")
			return
		}
		b, err := os.ReadFile(args[0])
		if err != nil {
			c.println("apply: %v", err)
			return
		}
		payload = b
	}
	for _, binding := range bindings {
		o, err := c.s.Offer(binding, op, payload)
		if err != nil {
			c.println("%v", err)
			return
		}
		c.println("%s gen=%d: offered", o.CommandID, o.Generation)
		go func() {
			select {
			case a := <-o.Ack:
				c.println("%s gen=%d -> %s", o.CommandID, o.Generation, describe(a))
			case <-time.After(offerDeadline):
				c.println("%s gen=%d: no ack within %s", o.CommandID, o.Generation, offerDeadline)
			}
		}()
	}
}

// targets resolves a line's @ words against the lease's bindings: "@all" is
// every binding in lease order, and no @ word is allowed only when the lease
// has a single binding, so a command never lands on a container by default.
func targets(lease, at []string) ([]string, error) {
	switch {
	case len(at) > 1:
		return nil, errors.New("one @target per command")
	case len(lease) == 0:
		return nil, errors.New("the lease names no binding")
	case len(at) == 0 && len(lease) == 1:
		return lease, nil
	case len(at) == 0:
		return nil, fmt.Errorf("which binding? add @%s | @all", strings.Join(lease, " | @"))
	case at[0] == "all":
		return lease, nil
	case slices.Contains(lease, at[0]):
		return at, nil
	}
	return nil, fmt.Errorf("binding %q is not in the lease (%s)", at[0], strings.Join(lease, ", "))
}

// describe renders an ack as OUTCOME, then its detail code and message if any.
func describe(a msg.CommandAck) string {
	out := string(a.Outcome)
	if a.Detail.Code != "" {
		out += " (" + string(a.Detail.Code) + ")"
	}
	if a.Detail.Message != "" {
		out += ": " + a.Detail.Message
	}
	return out
}
