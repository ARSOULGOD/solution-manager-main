package fakegw

import (
	"fmt"
	"slices"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

// The MI-logs half of the fake (plan 016; design §4, §4.1). LogRetention and
// LogRequest ride Connect; all log data rides the Logs RPC, served like
// Telemetry: only after a session is accepted, and only for a session that
// advertised CAPABILITY_LOGS. Like the rest of the fake it is a scripted
// double, not a statement about the real gateway's correctness (AG-D16).

type logStream struct {
	mu     sync.Mutex
	stream pb.AgentGateway_LogsServer
	s      *Server
}

func (l *logStream) send(m msg.LogsInbound) {
	p, err := codec.EncodeLogDown(m)
	if err != nil {
		l.s.log.Error("fakegw: encode logs", "err", err)
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.s.tr.add("out", m)
	_ = l.stream.Send(p)
}

// Logs serves the Logs RPC: LogPage and LogChunk are recorded in the
// transcript, and every non-final chunk is answered with the scenario's
// credits_per_chunk. A session that did not advertise CAPABILITY_LOGS is
// refused, as is a stream with no accepted session.
func (s *Server) Logs(stream pb.AgentGateway_LogsServer) error {
	ag, err := s.agentFor(stream.Context())
	if err != nil {
		return codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: err.Error()})
	}
	s.mu.Lock()
	if s.live == nil || s.live.ag != ag || !s.live.logs {
		s.mu.Unlock()
		return codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: "no accepted session that advertised logs"})
	}
	ls := &logStream{stream: stream, s: s}
	s.lstream = ls
	s.logStreams++
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.lstream == ls {
			s.lstream = nil
		}
		s.mu.Unlock()
	}()
	for {
		p, err := stream.Recv()
		if err != nil {
			return nil
		}
		m, err := codec.DecodeLogUp(p)
		if err != nil {
			s.log.Warn("fakegw: undecodable logs message", "err", err)
			continue
		}
		s.tr.add("in", m)
		switch v := m.(type) {
		case msg.LogPage:
			s.printf("logs page %s lines=%d gaps=%d unavailable=%q", v.RequestID, len(v.Lines), len(v.Gaps), v.Unavailable)
		case msg.LogChunk:
			s.printf("logs chunk %s seq=%d lines=%d gaps=%d final=%t", v.RequestID, v.Seq, len(v.Lines), len(v.Gaps), v.Final)
		}
		if c, ok := m.(msg.LogChunk); ok && !c.Final {
			if n := s.cfg.Scenario.Policy.Logs.CreditsPerChunk; n > 0 {
				ls.send(msg.LogCredit{RequestID: c.RequestID, CreditsGranted: n})
			}
		}
	}
}

// LogsStreams counts Logs RPCs accepted so far.
func (s *Server) LogsStreams() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.logStreams
}

// GrantLogCredit sends n credits for requestID on the live Logs stream. Grant
// it only after the request was sent: a credit that reaches the agent first is
// dropped as unknown (design §4.1).
func (s *Server) GrantLogCredit(requestID string, n uint32) error {
	s.mu.Lock()
	ls := s.lstream
	s.mu.Unlock()
	if ls == nil {
		return fmt.Errorf("fakegw: no Logs stream")
	}
	ls.send(msg.LogCredit{RequestID: requestID, CreditsGranted: n})
	return nil
}

// CancelLog sends LogCancel for requestID on the live Logs stream.
func (s *Server) CancelLog(requestID string) error {
	s.mu.Lock()
	ls := s.lstream
	s.mu.Unlock()
	if ls == nil {
		return fmt.Errorf("fakegw: no Logs stream")
	}
	ls.send(msg.LogCancel{RequestID: requestID})
	return nil
}

// RequestLogs sends a LogRequest to the live agent, if it advertised logs.
func (s *Server) RequestLogs(r msg.LogRequest) error {
	ss, err := s.liveLogs()
	if err != nil {
		return err
	}
	ss.sendLogRequest(r)
	return nil
}

// SetLogRetention sends LogRetention to the live agent, if it advertised logs.
func (s *Server) SetLogRetention(placement string, maxBytes uint64) error {
	ss, err := s.liveLogs()
	if err != nil {
		return err
	}
	ss.sendLogRetention(placement, maxBytes)
	return nil
}

func (s *Server) liveLogs() (*session, error) {
	s.mu.Lock()
	ss := s.live
	s.mu.Unlock()
	if ss == nil {
		return nil, ErrNoAgent
	}
	if !ss.logs {
		return nil, fmt.Errorf("fakegw: the session did not advertise %s", msg.CapLogs)
	}
	return ss, nil
}

// ---- session side ----

// advertisesLogs reports whether the agent's SessionOpen named CAPABILITY_LOGS.
func advertisesLogs(o msg.SessionOpen) bool {
	return slices.Contains(o.CapabilitiesSupported, msg.CapLogs)
}

func (ss *session) sendLogRetention(placement string, maxBytes uint64) {
	<-ss.accepted
	ss.send(msg.LogRetention{Envelope: ss.env(), PlacementRef: placement, MaxBytes: maxBytes})
}

func (ss *session) sendLogRequest(r msg.LogRequest) {
	<-ss.accepted
	r.Envelope = ss.env()
	ss.send(r)
}

// logRequestOf builds the wire request of a step, minting the id if absent.
func (ss *session) logRequestOf(st LogRequestStep) msg.LogRequest {
	id := st.RequestID
	if id == "" {
		ss.logSeq++
		id = fmt.Sprintf("req-%d", ss.logSeq)
	}
	ss.lastLogReq = id
	r := msg.LogRequest{RequestID: id, PlacementRef: st.Placement}
	switch {
	case st.Page != nil:
		r.Page = &msg.LogPageQuery{Limit: st.Page.Limit, After: st.Page.After, Before: st.Page.Before}
	case st.Export != nil:
		r.Export = &msg.LogExportQuery{FromSeq: st.Export.FromSeq}
	}
	return r
}

func (ss *session) requestID(id string) string {
	if id == "" {
		return ss.lastLogReq
	}
	return id
}

// expectLogPage / expectLogChunks scan the transcript from the step cursor.
func (ss *session) expectLogPage(e ExpectLogPageStep) bool {
	id := ss.requestID(e.RequestID)
	end, ok := ss.s.tr.waitScan(ss.cursor, func(es []Entry) (int, bool) {
		for i, en := range es {
			if p, isPage := en.Msg.(msg.LogPage); en.Dir == "in" && isPage && p.RequestID == id &&
				len(p.Lines) >= e.MinLines && (e.Unavailable == "" || string(p.Unavailable) == e.Unavailable) {
				return i + 1, true
			}
		}
		return 0, false
	}, timeoutOr(e.Timeout))
	if !ok {
		ss.s.log.Error("fakegw: expect_log_page timed out", "request_id", id)
		return false
	}
	ss.cursor = end
	return true
}

func (ss *session) expectLogChunks(e ExpectLogChunksStep) bool {
	id := ss.requestID(e.RequestID)
	end, ok := ss.s.tr.waitScan(ss.cursor, func(es []Entry) (int, bool) {
		lines := 0
		for i, en := range es {
			c, isChunk := en.Msg.(msg.LogChunk)
			if en.Dir != "in" || !isChunk || c.RequestID != id {
				continue
			}
			lines += len(c.Lines)
			if lines >= e.MinLines && (!e.Final || c.Final) {
				return i + 1, true
			}
		}
		return 0, false
	}, timeoutOr(e.Timeout))
	if !ok {
		ss.s.log.Error("fakegw: expect_log_chunks timed out", "request_id", id)
		return false
	}
	ss.cursor = end
	return true
}

func timeoutOr(d time.Duration) time.Duration {
	if d == 0 {
		return 10 * time.Second
	}
	return d
}

// ---- MI config view (plan 017) ----
//
// ConfigViewRequest rides Connect and needs no capability, so it is sent to
// any live session; the agent's ConfigViewReply lands in the transcript.

// ConfigViewRequestStep sends a ConfigViewRequest. An empty RequestID becomes
// "cv-<n>" for the session.
type ConfigViewRequestStep struct {
	Placement string   `yaml:"placement"`
	RequestID string   `yaml:"request_id"`
	Allow     []string `yaml:"allow"`
	MaxBytes  uint32   `yaml:"max_bytes"`
}

func (ss *session) sendConfigView(st ConfigViewRequestStep) {
	id := st.RequestID
	if id == "" {
		ss.logSeq++
		id = fmt.Sprintf("cv-%d", ss.logSeq)
	}
	<-ss.accepted
	ss.send(msg.ConfigViewRequest{RequestID: id, PlacementID: st.Placement, Allow: st.Allow, MaxBytes: st.MaxBytes})
}

// RequestConfigView sends a ConfigViewRequest to the live agent.
func (s *Server) RequestConfigView(r msg.ConfigViewRequest) error {
	s.mu.Lock()
	ss := s.live
	s.mu.Unlock()
	if ss == nil {
		return ErrNoAgent
	}
	<-ss.accepted
	ss.send(r)
	return nil
}
