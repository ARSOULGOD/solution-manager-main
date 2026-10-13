package fakegw

import (
	"fmt"
	"maps"
	"slices"
	"strconv"
	"strings"
	"sync"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

type teleStream struct {
	mu     sync.Mutex
	stream pb.AgentGateway_TelemetryServer
	s      *Server
}

func (t *teleStream) send(m msg.TelemetryInbound) {
	p, err := codec.TelemetryDownToPB(m)
	if err != nil {
		t.s.log.Error("fakegw: encode telemetry", "err", err)
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.s.tr.add("out", m)
	_ = t.stream.Send(p)
}

// Telemetry serves gateway §4.10: one credit per batch, an ack after
// "commit", and a per-stream watermark that makes a replay idempotent the way
// the DP's telemetry_ingest_watermark does (§6.2).
func (s *Server) Telemetry(stream pb.AgentGateway_TelemetryServer) error {
	ag, err := s.agentFor(stream.Context())
	if err != nil {
		return codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: err.Error()})
	}
	s.mu.Lock()
	if s.live == nil || s.live.ag != ag {
		s.mu.Unlock()
		return codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: "no accepted session"})
	}
	ts := &teleStream{stream: stream, s: s}
	s.tstream = ts
	marks := maps.Clone(s.tele[ag.agentID])
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.tstream == ts {
			s.tstream = nil
		}
		s.mu.Unlock()
	}()

	// §4.13 (b): the durable watermark first, so the agent trims before resending.
	for _, id := range slices.Sorted(maps.Keys(marks)) {
		ts.send(msg.TelemetryAck{Envelope: s.env(), StreamID: id, HighWatermarkSeq: marks[id]})
	}
	for {
		p, err := stream.Recv()
		if err != nil {
			return nil
		}
		b, err := codec.TelemetryUpFromPB(p)
		if err != nil {
			s.log.Warn("fakegw: undecodable telemetry", "err", err)
			continue
		}
		s.tr.add("in", b)
		s.batch(ag, ts, b)
	}
}

func (s *Server) batch(ag *enrolledAgent, ts *teleStream, b msg.TelemetryBatch) {
	s.mu.Lock()
	if code := s.rejectNext; code != "" {
		s.rejectNext = ""
		s.mu.Unlock()
		s.printf("telemetry %s seq=%d -> rejected %s", b.StreamID, b.BatchSeq, code)
		ts.send(msg.TelemetryReject{Envelope: s.env(), StreamID: b.StreamID, Code: msg.ErrorCode(code)})
		return
	}
	if s.tele[ag.agentID] == nil {
		s.tele[ag.agentID] = map[string]uint64{}
	}
	wm := s.tele[ag.agentID][b.StreamID]
	dup := b.BatchSeq <= wm
	if !dup {
		s.tele[ag.agentID][b.StreamID] = b.BatchSeq
		wm = b.BatchSeq
	}
	credits := s.cfg.Scenario.Policy.Telemetry.CreditsPerAck
	if s.paused {
		credits = 0
	}
	s.mu.Unlock()
	if dup {
		s.printf("telemetry %s seq=%d duplicate", b.StreamID, b.BatchSeq)
	} else {
		for _, g := range b.GapMarkers {
			s.printf("gap %s seq=%d..%d %s", b.StreamID, g.FromSeq, g.ToSeq, g.Reason)
		}
		s.printf("telemetry %s seq=%d%s -> acked", b.StreamID, b.BatchSeq, values(b.Samples))
	}
	ts.send(msg.TelemetryAck{Envelope: s.env(), StreamID: b.StreamID, HighWatermarkSeq: wm, CreditsGranted: credits})
}

func values(samples []msg.Sample) string {
	var sb strings.Builder
	for _, x := range samples {
		fmt.Fprintf(&sb, " %s=%s", x.SeriesKey, strconv.FormatFloat(x.Value, 'g', -1, 64))
	}
	return sb.String()
}

func (s *Server) printf(format string, a ...any) {
	if s.cfg.Out == nil {
		return
	}
	s.outMu.Lock()
	defer s.outMu.Unlock()
	fmt.Fprintf(s.cfg.Out, format+"\n", a...)
}

// PauseTelemetry makes every ack grant no credit, so the agent spends what it
// holds and then buffers on disk (AG-D9).
func (s *Server) PauseTelemetry() {
	s.mu.Lock()
	s.paused = true
	s.mu.Unlock()
}

// ResumeTelemetry grants n credits unsolicited — the §4.10 recovery path from
// zero — and returns acks to the scenario's credits_per_ack.
func (s *Server) ResumeTelemetry(n uint32) {
	s.mu.Lock()
	s.paused = false
	ts := s.tstream
	s.mu.Unlock()
	if ts != nil {
		ts.send(msg.TelemetryCredit{Envelope: s.env(), CreditsGranted: n})
	}
}
