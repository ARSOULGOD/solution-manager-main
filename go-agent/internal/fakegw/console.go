package fakegw

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

const consoleUsage = "type: preflight | apply <file> | restart | stop | view <placement> <allow,comma,list> | pause | resume | disconnect"

// Console turns lines typed at the gateway's terminal into commands for the
// connected agent, one operation per line, and prints each outcome when the
// agent acks it. It returns when r is exhausted.
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
	case "view":
		c.view(f)
		return
	case "disconnect":
		if err := c.s.Disconnect(); err != nil {
			c.println("%v", err)
			return
		}
		c.println("session closed; the agent will reconnect")
		return
	}
	var payload []byte
	switch op := msg.Operation(f[0]); op {
	case msg.OpPreflight, msg.OpRestart, msg.OpStop:
	case msg.OpApply:
		if len(f) < 2 {
			c.println("apply needs a file: apply <file>")
			return
		}
		b, err := os.ReadFile(f[1])
		if err != nil {
			c.println("apply: %v", err)
			return
		}
		payload = b
	default:
		c.println("unknown command %q — %s", f[0], consoleUsage)
		return
	}
	o, err := c.s.Offer(msg.Operation(f[0]), payload)
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

// view asks the agent for a placement's config view and prints the reply:
// view <placement> <allow,comma,list> (patterns as in sm.dp.config-view.allow).
func (c *console) view(f []string) {
	if len(f) < 3 {
		c.println("view needs a placement and an allow list: view binding-1 server.port,info.*")
		return
	}
	before := len(c.s.Received("ConfigViewReply"))
	id := fmt.Sprintf("view-%d", before+1)
	err := c.s.RequestConfigView(msg.ConfigViewRequest{RequestID: id, PlacementID: f[1], Allow: strings.Split(f[2], ","), MaxBytes: 256 << 10})
	if err != nil {
		c.println("%v", err)
		return
	}
	c.println("%s: offered", id)
	go func() {
		if !c.s.WaitFor("ConfigViewReply", before+1, 30*time.Second) {
			c.println("%s: no reply within 30s", id)
			return
		}
		r := c.s.Received("ConfigViewReply")[before].(msg.ConfigViewReply)
		c.println("%s -> %v %v hidden=%d truncated=%v", id, r.Status, r.Reason, r.HiddenCount, r.Truncated)
		for _, e := range r.Entries {
			c.println("    %s = %s", e.Path, e.Value)
		}
	}()
}
