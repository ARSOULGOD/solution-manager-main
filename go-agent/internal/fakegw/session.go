package fakegw

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

type cmdState struct {
	offer      msg.CommandOffer
	superseded bool
	state      string              // OFFERED | DELIVERED | ACKED | REFUSED | SUPERSEDED
	ack        chan msg.CommandAck // the first CommandAck, for whoever offered it
}

// offerDeadline is an offer's deadline when its step names none.
const offerDeadline = 5 * time.Minute

type session struct {
	s      *Server
	ag     *enrolledAgent
	stream pb.AgentGateway_ConnectServer
	sc     *Scenario
	policy Policy

	sendMu sync.Mutex

	kick     chan struct{} // closed by Server.Disconnect: end this stream, keep the enrolment
	kickOnce sync.Once

	drainDeadline *time.Timer // AGW-D18: backstop that ends the stream if the agent never answers a Drain

	// mu guards commands and policy. Once the steady-state phase starts,
	// run() has two live goroutines touching session state: the scripted
	// step runner (runStep, driven by the scenario) and the auto-responder
	// (respond, driven by ss.inbox). The only thing that ties a step's
	// effect (e.g. an offer) to the agent's resulting message (e.g. a
	// CommandPull respond() then handles) is a network round trip through
	// the agent, and the Go memory model does not treat socket I/O as a
	// synchronizing operation — so without this lock, commands/policy
	// access from the two goroutines is a genuine data race (confirmed by
	// -race; see server_test.go's TestSessionCommandsAndPolicyConcurrentAccess)
	// even though every scenario happens to write before the corresponding
	// read arrives in wall-clock time. cursor and mustClose are NOT guarded
	// by mu: cursor is only ever touched by the step-runner goroutine, and
	// mustClose's one read (in run(), after <-stepDone) is already ordered
	// after every write to it by the stepDone channel close, which is
	// itself a happens-before edge per the Go memory model.
	mu       sync.Mutex
	commands map[string]*cmdState
	inbox    chan msg.Outbound
	done     chan struct{}
	// accepted is closed once SessionAccept is on the stream. The session is
	// live (Offer, Telemetry) a moment before that, so an agent that opens
	// Telemetry straight after SessionAccept is never refused; an offer
	// waits here so it can never overtake SessionAccept.
	accepted  chan struct{}
	cursor    int  // transcript index the next `expect` scans from
	mustClose bool // set by disconnect/drain/revoke steps: drop the stream when steps finish

	logs       bool   // SessionOpen advertised CAPABILITY_LOGS (plan 016); set before the session is live
	logSeq     int    // request ids minted for log_request steps; step-runner goroutine only
	lastLogReq string // the last log_request's id, for expect_log_* steps that name none
}

func (s *Server) Connect(stream pb.AgentGateway_ConnectServer) error {
	ag, err := s.agentFor(stream.Context())
	if err != nil {
		return codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: err.Error()})
	}
	s.mu.Lock()
	if ag.revoked {
		s.mu.Unlock()
		return codec.StatusFromError(msg.Error{Code: msg.ErrIdentityRevoked, Message: "revoked"})
	}
	ag.sessions++
	ag.epoch++
	s.sessions++
	s.mu.Unlock()

	ss := &session{s: s, ag: ag, stream: stream, sc: s.cfg.Scenario, policy: s.cfg.Scenario.Policy,
		commands: map[string]*cmdState{}, inbox: make(chan msg.Outbound, 256), done: make(chan struct{}), accepted: make(chan struct{}), kick: make(chan struct{}),
		cursor: s.tr.length()}
	go ss.reader()
	return ss.run()
}

func (ss *session) reader() {
	defer close(ss.done)
	for {
		p, err := ss.stream.Recv()
		if err != nil {
			return
		}
		m, err := codec.AgentFromPB(p)
		if err != nil {
			ss.s.log.Warn("fakegw: undecodable", "err", err)
			continue
		}
		ss.s.tr.add("in", m)
		ss.inbox <- m
	}
}

func (ss *session) send(m msg.Inbound) {
	ss.sendMu.Lock()
	defer ss.sendMu.Unlock()
	p, err := codec.GatewayToPB(m)
	if err != nil {
		ss.s.log.Error("fakegw: encode", "err", err)
		return
	}
	ss.s.tr.add("out", m)
	_ = ss.stream.Send(p)
}

func (ss *session) env() msg.Envelope {
	return msg.Envelope{MessageID: ss.s.newID(), SentAt: time.Now()}
}

func (ss *session) newGrant(now time.Time) msg.LeaseGrant {
	l := ss.sc.Lease
	caps := make([]msg.Capability, 0, len(l.Capabilities))
	for _, c := range l.Capabilities {
		caps = append(caps, msg.Capability(c))
	}
	vc := map[msg.Operation]time.Duration{}
	for k, d := range l.VerbCeilings {
		vc[msg.Operation(k)] = d
	}
	return msg.LeaseGrant{
		Envelope: ss.env(), AgentID: ss.ag.agentID, CnfThumbprint: ss.ag.thumbprint, TargetID: l.TargetID, BindingIDs: l.BindingIDs,
		AgentRevocationEpoch: 1, FleetRevocationEpoch: 1, IssuedAt: now, ExpiresAt: now.Add(l.Duration), Audience: "dp-logical",
		Nonce: ss.s.newID(), Capabilities: caps, VerbCeilings: vc, AdmissionMargin: l.AdmissionMargin, ServerClock: now,
	}
}

func (ss *session) limits() msg.Limits {
	l := ss.sc.Limits
	return msg.Limits{MaxMessageBytes: l.MaxMessageBytes, MaxBatchBytes: l.MaxBatchBytes, InitialCredits: l.InitialCredits,
		HeartbeatInterval: l.HeartbeatInterval, KeepaliveInterval: l.KeepaliveInterval, SkewTolerance: l.SkewTolerance, ReportInterval: l.ReportInterval}
}

// next waits for the next inbound message or the stream ending.
func (ss *session) next(timeout time.Duration) (msg.Outbound, bool) {
	select {
	case m := <-ss.inbox:
		return m, true
	case <-ss.done:
		return nil, false
	case <-time.After(timeout):
		return nil, false
	}
}

func (ss *session) run() error {
	// 1. SessionOpen
	m, ok := ss.next(30 * time.Second)
	if !ok {
		return nil
	}
	open, isOpen := m.(msg.SessionOpen)
	if isOpen {
		ss.logs = advertisesLogs(open)
	}
	if !isOpen {
		ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrUnauthenticatedIdentity, Message: "SessionOpen expected first"})
		return nil
	}
	if strings.HasPrefix(ss.policy.OnOpen, "reject:") {
		ss.send(msg.SessionReject{Envelope: ss.env(), Code: msg.ErrorCode(strings.TrimPrefix(ss.policy.OnOpen, "reject:"))})
		return nil
	}
	now := time.Now()
	ss.s.mu.Lock()
	lease := ss.ag.lease
	ss.s.mu.Unlock()
	switch {
	case lease == nil: // never granted → issue
		g := ss.newGrant(now)
		ss.setLease(g)
		lease = &g
	case now.Before(lease.ExpiresAt) && ss.policy.OnOpen != "renew-required": // live → return as is
	default: // expired (or forced) → renew under the nonce
		ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrLeaseRenewRequired})
		r, ok := ss.next(30 * time.Second)
		if !ok {
			return nil
		}
		lr, isRenew := r.(msg.LeaseRenew)
		if !isRenew || lr.NonceConsumed != lease.Nonce {
			ss.send(msg.SessionReject{Envelope: ss.env(), Code: msg.ErrReEnrollRequired})
			return nil
		}
		g := ss.grantForRenew(lr, now)
		lease = &g
	}
	ss.s.setLive(ss)
	defer ss.s.clearLive(ss)
	ss.send(msg.SessionAccept{Envelope: ss.env(), SessionID: "sess-" + ss.s.newID()[:8], AssignedTargetID: ss.sc.Lease.TargetID,
		RequiredCapabilities: nil, Lease: *lease, Limits: ss.limits(), ServerClock: now})
	close(ss.accepted)

	// 2. steady state: auto-responders + scripted steps
	steps := ss.sc.Steps
	stepDone := make(chan struct{})
	go func() {
		defer close(stepDone)
		for _, st := range steps {
			if !ss.runStep(st) {
				return
			}
		}
	}()
	for {
		select {
		case m := <-ss.inbox:
			ss.respond(m)
		case <-ss.done:
			return nil
		case <-ss.kick:
			return nil
		case <-stepDone:
			if ss.mustClose {
				return nil // returning ends the stream: the agent sees EOF
			}
			// keep responding until the agent goes away
			for {
				select {
				case m := <-ss.inbox:
					ss.respond(m)
				case <-ss.done:
					return nil
				case <-ss.kick:
					return nil
				}
			}
		}
	}
}

func (ss *session) setLease(g msg.LeaseGrant) {
	ss.s.mu.Lock()
	ss.ag.lease = &g
	ss.s.mu.Unlock()
}

// grantForRenew is idempotent per message_id: a resend gets the same grant.
func (ss *session) grantForRenew(lr msg.LeaseRenew, now time.Time) msg.LeaseGrant {
	ss.s.mu.Lock()
	if g, seen := ss.ag.renewIDs[lr.MessageID]; seen {
		ss.s.mu.Unlock()
		return g
	}
	ss.s.mu.Unlock()
	g := ss.newGrant(now)
	ss.s.mu.Lock()
	ss.ag.renewIDs[lr.MessageID] = g
	ss.ag.lease = &g
	ss.s.mu.Unlock()
	return g
}

func (ss *session) respond(m msg.Outbound) {
	switch v := m.(type) {
	case msg.Heartbeat:
		ss.mu.Lock()
		hb := ss.policy.Heartbeat
		ss.mu.Unlock()
		st := msg.ClockOK
		if strings.EqualFold(hb.State, "SUSPECT") {
			st = msg.ClockSuspect
		}
		ss.send(msg.HeartbeatAck{Envelope: ss.env(), ServerClock: time.Now(), ClockOffsetMs: hb.OffsetMs, ClockState: st})
	case msg.LeaseRenew:
		ss.s.mu.Lock()
		cur := ss.ag.lease
		_, seen := ss.ag.renewIDs[v.MessageID]
		ss.s.mu.Unlock()
		ss.mu.Lock()
		renew := ss.policy.Renew
		ss.mu.Unlock()
		switch {
		case renew == "silent":
		case strings.HasPrefix(renew, "refuse:"):
			ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrorCode(strings.TrimPrefix(renew, "refuse:")), CorrelationID: v.MessageID})
		case seen: // resend of a request already granted → same grant (AGW-D15 adoption)
			ss.send(ss.grantForRenew(v, time.Now()))
		case cur == nil || v.NonceConsumed != cur.Nonce:
			ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrLeaseRenewRefused, CorrelationID: v.MessageID, Message: "nonce"})
		default:
			ss.send(ss.grantForRenew(v, time.Now()))
		}
	case msg.CommandPull:
		ss.mu.Lock()
		c := ss.commands[v.CommandID]
		var offer msg.CommandOffer
		superseded := false
		if c != nil {
			offer, superseded = c.offer, c.superseded
		}
		ss.mu.Unlock()
		switch {
		case c == nil:
			ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrUnknownCommand, CorrelationID: v.CommandID})
		case superseded:
			ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrSuperseded, CorrelationID: v.CommandID})
		default:
			o := offer
			o.Envelope = ss.env()
			ss.send(o)
		}
	case msg.CommandReceived:
		ss.mu.Lock()
		if c := ss.commands[v.CommandID]; c != nil && c.state == "OFFERED" {
			c.state = "DELIVERED"
		}
		ss.mu.Unlock()
	case msg.CommandAck:
		ss.mu.Lock()
		if c := ss.commands[v.CommandID]; c != nil {
			if v.Outcome == msg.OutcomeRefused {
				c.state = "REFUSED"
			} else {
				c.state = "ACKED"
			}
			select {
			case c.ack <- v:
			default: // a replayed ack: the first one was already delivered
			}
		}
		ss.mu.Unlock()
	case msg.ActualStateReportBatch:
		ss.ackReports(v)
	case msg.DrainComplete:
		ss.closeAfterDrain()
	case msg.DiscoveryReport:
		ss.ackDiscovery(v)
	}
}

// ackDiscovery registers every reported container (plan 015 T2.3) unless the
// scenario says to stay silent.
func (ss *session) ackDiscovery(r msg.DiscoveryReport) {
	ss.mu.Lock()
	pol := ss.policy.Discovery
	ss.mu.Unlock()
	if pol.Mode == "silent" {
		return
	}
	ack := msg.DiscoveryAck{Envelope: ss.env(), InReplyToMessageID: r.MessageID}
	for _, c := range r.Items {
		id := pol.Bindings[c.ContainerName]
		if id == "" {
			id = "bind-" + c.ContainerName
		}
		ack.Results = append(ack.Results, msg.DiscoveryResult{ContainerID: c.ContainerID, State: msg.DiscoveryRegistered, BindingID: id})
	}
	ss.send(ack)
}

func declarationsOf(env msg.Envelope, d DeclarationsStep) msg.TelemetryDeclarations {
	out := msg.TelemetryDeclarations{Envelope: env, BindingID: d.BindingID, Interval: d.Interval}
	for _, m := range d.Metrics {
		on := msg.OnUnavailableDegrade
		if m.OnUnavailable == "error" {
			on = msg.OnUnavailableError
		}
		out.Metrics = append(out.Metrics, msg.TelemetryMetric{Name: m.Name, MetricType: msg.MetricType(m.MetricType), Unit: m.Unit,
			HTTPJSON: &msg.HTTPJSONSource{Port: m.Port, Path: m.Path, Pointer: m.Pointer, OnUnavailable: on}})
	}
	if p := d.HealthProbe; p != nil {
		out.HealthProbe = &msg.HealthProbe{Kind: msg.ProbeKind(p.Kind), Path: p.Path, HostPort: p.HostPort, Strength: msg.ProbeStrength(p.Strength)}
	}
	return out
}

// closeAfterDrain ends the stream once the agent has answered a planned drain
// (or the drain deadline lapsed), exactly as the real gateway does.
func (ss *session) closeAfterDrain() {
	if ss.drainDeadline != nil {
		ss.drainDeadline.Stop()
	}
	ss.kickOnce.Do(func() { close(ss.kick) })
}

func (ss *session) ackReports(b msg.ActualStateReportBatch) {
	ss.mu.Lock()
	mode := ss.policy.Report.Mode
	ss.mu.Unlock()
	if mode == "silent" {
		return
	}
	ack := msg.ReportAck{Envelope: ss.env(), InReplyToMessageID: b.MessageID}
	for _, it := range b.Items {
		r := msg.ReportResult{InReplyToItemID: it.ItemID, Sequence: it.Sequence, Trust: msg.TrustTrusted, Disposition: msg.DispApplied}
		if strings.HasPrefix(mode, "refuse:") {
			r.Disposition = msg.DispRefused
			r.RefusedReason = msg.ErrorCode(strings.TrimPrefix(mode, "refuse:"))
		} else {
			ss.s.mu.Lock()
			rk := it.BindingID + "|" + it.ReplicaRef
			if _, ok := ss.s.replicas[rk]; !ok {
				ss.s.replicas[rk] = "rep-" + ss.s.newID()[:8]
			}
			ik := rk + "|" + it.Incarnation
			if _, ok := ss.s.incarn[ik]; !ok {
				ss.s.incarn[ik] = "inc-" + ss.s.newID()[:8]
			}
			r.ReplicaID, r.IncarnationID = ss.s.replicas[rk], ss.s.incarn[ik]
			ss.s.mu.Unlock()
		}
		ack.Results = append(ack.Results, r)
	}
	ss.send(ack)
}

// offer registers a command and notifies the agent of it. Scripted steps and
// the console both offer through here.
func (ss *session) offer(o OfferStep) *cmdState {
	<-ss.accepted
	sum := sha256.Sum256([]byte(o.Payload))
	h := hex.EncodeToString(sum[:])
	if o.BadHash {
		h = "deadbeef"
	}
	dl := o.Deadline
	if dl == 0 {
		dl = offerDeadline
	}
	key := o.IdempotencyKey
	if key == "" {
		key = "key-" + o.CommandID
	}
	cs := &cmdState{state: "OFFERED", ack: make(chan msg.CommandAck, 1), offer: msg.CommandOffer{
		CommandID: o.CommandID, Generation: o.Generation, IdempotencyKey: key, BindingID: o.BindingID,
		Operation: msg.Operation(o.Operation), Payload: []byte(o.Payload), ConfigSHA256: h,
		SupersedesGeneration: o.SupersedesGeneration, Deadline: time.Now().Add(dl), ExpectedCurrentSHA256: o.ExpectedCurrentSHA256,
	}}
	ss.mu.Lock()
	ss.commands[o.CommandID] = cs
	ss.mu.Unlock()
	ss.s.sawGeneration(o.BindingID, o.Generation)
	ss.send(msg.GenerationAvailable{Envelope: ss.env(), CommandID: o.CommandID, BindingID: o.BindingID, Generation: o.Generation,
		Operation: msg.Operation(o.Operation), ConfigSHA256: h, Deadline: time.Now().Add(dl)})
	return cs
}

// runStep returns false when the session should stop.
func (ss *session) runStep(st Step) bool {
	switch {
	case st.Expect != nil:
		to := st.Expect.Timeout
		if to == 0 {
			to = 10 * time.Second
		}
		next, ok := ss.s.tr.waitForFrom(st.Expect.Kind, ss.cursor, to)
		if !ok {
			ss.s.log.Error("fakegw: expect timed out", "kind", st.Expect.Kind)
			return false
		}
		ss.cursor = next
	case st.Offer != nil:
		ss.offer(*st.Offer)
	case st.Declarations != nil:
		<-ss.accepted
		ss.send(declarationsOf(ss.env(), *st.Declarations))
	case st.Hold != nil:
		st.Hold()
	case st.LogRetention != nil:
		if ss.logs || st.LogRetention.Force {
			ss.sendLogRetention(st.LogRetention.Placement, st.LogRetention.MaxBytes)
		}
	case st.LogRequest != nil:
		r := ss.logRequestOf(*st.LogRequest)
		if ss.logs || st.LogRequest.Force {
			ss.sendLogRequest(r)
		}
	case st.ExpectLogPage != nil:
		return ss.expectLogPage(*st.ExpectLogPage)
	case st.ExpectLogChunks != nil:
		return ss.expectLogChunks(*st.ExpectLogChunks)
	case st.Superseded != nil:
		ss.mu.Lock()
		if c := ss.commands[*st.Superseded]; c != nil {
			c.superseded = true
			c.state = "SUPERSEDED"
		}
		ss.mu.Unlock()
	case st.Error != nil:
		code := msg.ErrorCode(st.Error.Code)
		ss.send(msg.Error{Envelope: ss.env(), Code: code, RetryAfter: st.Error.RetryAfter, Message: "scripted"})
		// A session-level error ends the stream from the gateway side, as the
		// real gateway would; per-command codes and LEASE_RENEW_REQUIRED do not.
		if msg.Classify(code) != msg.ClassPerCommand && code != msg.ErrLeaseRenewRequired && code != msg.ErrLeaseRenewRefused {
			ss.mustClose = true
			return false
		}
	case st.Revoke != nil:
		ss.s.mu.Lock()
		ss.ag.revoked = true
		ss.s.mu.Unlock()
		e := uint64(2)
		ss.send(msg.Revoke{Envelope: ss.env(), AgentRevocationEpoch: &e, Reason: *st.Revoke})
		ss.mustClose = true
		return false
	case st.Drain != nil:
		d := msg.Drain{Envelope: ss.env(), Reason: "planned restart", ReconnectAfter: *st.Drain}
		if st.DrainDeadline != nil {
			// AGW-D18: keep the stream open; respond() closes it on DrainComplete (or the deadline).
			d.Deadline = *st.DrainDeadline
			ss.send(d)
			ss.drainDeadline = time.AfterFunc(*st.DrainDeadline, func() { ss.closeAfterDrain() })
			return true
		}
		ss.send(d)
		ss.mustClose = true
		return false
	case st.Disconnect != nil && *st.Disconnect:
		ss.mustClose = true
		return false
	case st.Sleep != nil:
		time.Sleep(*st.Sleep)
	case st.SendGrant != nil && *st.SendGrant:
		g := ss.newGrant(time.Now())
		ss.setLease(g)
		ss.send(g)
	case st.RenewPolicy != nil:
		ss.mu.Lock()
		ss.policy.Renew = *st.RenewPolicy
		ss.mu.Unlock()
	case st.HeartbeatPolicy != nil:
		ss.mu.Lock()
		ss.policy.Heartbeat = *st.HeartbeatPolicy
		ss.mu.Unlock()
	case st.ReportPolicy != nil:
		ss.mu.Lock()
		ss.policy.Report = *st.ReportPolicy
		ss.mu.Unlock()
	}
	return true
}

var _ = fmt.Sprintf
