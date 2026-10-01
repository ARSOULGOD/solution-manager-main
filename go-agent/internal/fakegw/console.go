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

const consoleUsage = "type: preflight | apply <file> | restart | stop"

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
