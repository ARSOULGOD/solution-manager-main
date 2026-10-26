package fakegw

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

const consoleUsage = "type: preflight | apply <file> | restart | stop  [@<binding>|@all] | view <placement> <allow,comma,list> | logs <placement> [limit] | export <placement> [from-seq] | cap <placement> <bytes> | pause | resume | disconnect | set push <duration>|unset"

// logsPageLimit is how many of the newest lines "logs" asks for when no limit
// is typed; a limit of 0 on the wire would ask for every line one message fits.
const logsPageLimit = 20

// logsReplyDeadline bounds the wait for a page, or for an export's next chunk.
const logsReplyDeadline = 30 * time.Second

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
	s   *Server
	mu  sync.Mutex // acks print from their own goroutines
	w   io.Writer
	seq int // numbers the log request ids this console mints
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
	case "logs":
		c.logs(f)
		return
	case "export":
		c.export(f)
		return
	case "cap":
		c.capLogs(f)
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

// logs asks the agent for a page of a placement's newest stored lines and
// prints them oldest first, with any gaps: logs <placement> [limit].
func (c *console) logs(f []string) {
	if len(f) < 2 {
		c.println("logs needs a placement: logs binding-1 [limit]")
		return
	}
	limit := uint64(logsPageLimit)
	if len(f) > 2 {
		n, err := strconv.ParseUint(f[2], 10, 32)
		if err != nil {
			c.println("logs: the limit must be a whole number of lines, got %q", f[2])
			return
		}
		limit = n
	}
	id := c.nextID("logs")
	from := c.s.tr.length()
	if err := c.s.RequestLogs(msg.LogRequest{RequestID: id, PlacementRef: f[1], Page: &msg.LogPageQuery{Limit: uint32(limit)}}); err != nil {
		c.println("%v", err)
		return
	}
	c.println("%s: requested the newest %d lines of %s", id, limit, f[1])
	go func() {
		m, _, ok := c.await(from, func(m any) bool { p, is := m.(msg.LogPage); return is && p.RequestID == id })
		if !ok {
			c.println("%s: no page within %s", id, logsReplyDeadline)
			return
		}
		p := m.(msg.LogPage)
		if p.Unavailable != msg.UnavailableNone {
			c.println("%s -> unavailable: %s", id, p.Unavailable)
			return
		}
		c.println("%s -> %d lines, %d gaps%s", id, len(p.Lines), len(p.Gaps), formatLog(p.Lines, p.Gaps))
	}()
}

// export asks the agent for every stored line from a seq on and prints each
// chunk as it arrives, until the final one: export <placement> [from-seq]. A
// chunk's seq is its resume point, the from-seq that continues right after it.
// Credits are granted by the scenario's logs.credits_per_chunk.
func (c *console) export(f []string) {
	if len(f) < 2 {
		c.println("export needs a placement: export binding-1 [from-seq]")
		return
	}
	var fromSeq uint64
	if len(f) > 2 {
		n, err := strconv.ParseUint(f[2], 10, 64)
		if err != nil {
			c.println("export: the from-seq must be a whole number, got %q", f[2])
			return
		}
		fromSeq = n
	}
	id := c.nextID("export")
	cursor := c.s.tr.length()
	if err := c.s.RequestLogs(msg.LogRequest{RequestID: id, PlacementRef: f[1], Export: &msg.LogExportQuery{FromSeq: fromSeq}}); err != nil {
		c.println("%v", err)
		return
	}
	c.println("%s: requested %s from seq %d", id, f[1], fromSeq)
	go func() {
		lines, chunks, resume := 0, 0, fromSeq
		for {
			m, end, ok := c.await(cursor, func(m any) bool { ch, is := m.(msg.LogChunk); return is && ch.RequestID == id })
			if !ok {
				c.println("%s: no chunk within %s; resume with: export %s %d", id, logsReplyDeadline, f[1], resume)
				return
			}
			cursor = end
			ch := m.(msg.LogChunk)
			lines, chunks, resume = lines+len(ch.Lines), chunks+1, ch.Seq
			c.println("%s seq=%d: %d lines, %d gaps%s", id, ch.Seq, len(ch.Lines), len(ch.Gaps), formatLog(ch.Lines, ch.Gaps))
			if ch.Final {
				c.println("%s: complete, %d lines in %d chunks", id, lines, chunks)
				return
			}
		}
	}()
}

// capLogs sets a placement's log ring cap on the agent: cap <placement> <bytes>.
// A cap of 0 turns collection off and the agent deletes the stored logs.
func (c *console) capLogs(f []string) {
	if len(f) < 3 {
		c.println("cap needs a placement and a size: cap binding-1 512000 (bytes; 0 stops collection and deletes the stored logs)")
		return
	}
	n, err := strconv.ParseUint(f[2], 10, 64)
	if err != nil {
		c.println("cap: the size must be a whole number of bytes, got %q", f[2])
		return
	}
	if err := c.s.SetLogRetention(f[1], n); err != nil {
		c.println("%v", err)
		return
	}
	c.println("cap %s = %d bytes: sent", f[1], n)
}

func (c *console) nextID(kind string) string {
	c.seq++
	return fmt.Sprintf("%s-%d", kind, c.seq)
}

// await waits for the first inbound transcript entry at or after index from
// that match accepts, returning it and the index after it.
func (c *console) await(from int, match func(any) bool) (any, int, bool) {
	var got any
	end, ok := c.s.tr.waitScan(from, func(es []Entry) (int, bool) {
		for i, e := range es {
			if e.Dir == "in" && match(e.Msg) {
				got = e.Msg
				return i + 1, true
			}
		}
		return 0, false
	}, logsReplyDeadline)
	return got, end, ok
}

// formatLog renders lines oldest first as "time stream text", then the gaps,
// one per indented line, so a page prints as one block.
func formatLog(lines []msg.LogLine, gaps []msg.LogGap) string {
	var b strings.Builder
	for _, l := range lines {
		fmt.Fprintf(&b, "\n    %s %-6s %s", l.TS.Local().Format("15:04:05.000"), l.Stream, strings.TrimRight(string(l.Text), "\r\n"))
		if l.Truncated {
			b.WriteString(" [truncated]")
		}
	}
	for _, g := range gaps {
		fmt.Fprintf(&b, "\n    gap %s from %s to %s", g.Reason, g.From.Local().Format("15:04:05"), g.To.Local().Format("15:04:05"))
	}
	return b.String()
}
