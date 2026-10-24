// Package codec maps the generated DRAFT protobuf to internal/msg and back.
// Both directions exist because the fake gateway speaks the same wire.
package codec

import (
	"fmt"
	"strings"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/durationpb"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

// ---- enum helpers: proto values carry the enum-name prefix ----

func enumTo[E ~int32](values map[string]int32, prefix, name string) E {
	return E(values[prefix+strings.ToUpper(strings.ReplaceAll(name, "-", "_"))])
}

func enumFrom[E ~int32](names map[int32]string, prefix string, v E) string {
	return strings.TrimPrefix(names[int32(v)], prefix)
}

func opToPB(o msg.Operation) pb.Operation {
	return enumTo[pb.Operation](pb.Operation_value, "OPERATION_", string(o))
}
func opFromPB(p pb.Operation) msg.Operation {
	return msg.Operation(strings.ToLower(enumFrom(pb.Operation_name, "OPERATION_", p)))
}

func capToPB(c msg.Capability) pb.Capability {
	return enumTo[pb.Capability](pb.Capability_value, "CAPABILITY_", string(c))
}
func capFromPB(p pb.Capability) msg.Capability {
	return msg.Capability(strings.ToLower(strings.ReplaceAll(enumFrom(pb.Capability_name, "CAPABILITY_", p), "_", "-")))
}
func capsToPB(cs []msg.Capability) []pb.Capability {
	out := make([]pb.Capability, 0, len(cs))
	for _, c := range cs {
		out = append(out, capToPB(c))
	}
	return out
}
func capsFromPB(ps []pb.Capability) []msg.Capability {
	if len(ps) == 0 {
		return nil
	}
	out := make([]msg.Capability, 0, len(ps))
	for _, p := range ps {
		out = append(out, capFromPB(p))
	}
	return out
}

func outcomeToPB(o msg.Outcome) pb.Outcome {
	return enumTo[pb.Outcome](pb.Outcome_value, "OUTCOME_", string(o))
}
func outcomeFromPB(p pb.Outcome) msg.Outcome {
	return msg.Outcome(enumFrom(pb.Outcome_name, "OUTCOME_", p))
}
func stepToPB(s msg.ProgressStep) pb.ProgressStep {
	return enumTo[pb.ProgressStep](pb.ProgressStep_value, "PROGRESS_STEP_", string(s))
}
func stepFromPB(p pb.ProgressStep) msg.ProgressStep {
	return msg.ProgressStep(enumFrom(pb.ProgressStep_name, "PROGRESS_STEP_", p))
}
func trustToPB(t msg.Trust) pb.Trust   { return enumTo[pb.Trust](pb.Trust_value, "TRUST_", string(t)) }
func trustFromPB(p pb.Trust) msg.Trust { return msg.Trust(enumFrom(pb.Trust_name, "TRUST_", p)) }
func dispToPB(d msg.Disposition) pb.Disposition {
	return enumTo[pb.Disposition](pb.Disposition_value, "DISPOSITION_", string(d))
}
func dispFromPB(p pb.Disposition) msg.Disposition {
	return msg.Disposition(enumFrom(pb.Disposition_name, "DISPOSITION_", p))
}

func correlationToPB(c *msg.OperationCorrelation) *pb.OperationCorrelation {
	if c == nil {
		return nil
	}
	return &pb.OperationCorrelation{ApplyId: c.ApplyID, IntentId: c.IntentID, AttemptId: c.AttemptID, Step: c.Step}
}

func correlationFromPB(c *pb.OperationCorrelation) *msg.OperationCorrelation {
	if c == nil {
		return nil
	}
	return &msg.OperationCorrelation{ApplyID: c.GetApplyId(), IntentID: c.GetIntentId(), AttemptID: c.GetAttemptId(), Step: c.GetStep()}
}
func clockToPB(c msg.ClockState) pb.ClockState {
	return enumTo[pb.ClockState](pb.ClockState_value, "CLOCK_STATE_", string(c))
}
func clockFromPB(p pb.ClockState) msg.ClockState {
	return msg.ClockState(enumFrom(pb.ClockState_name, "CLOCK_STATE_", p))
}

func errToPB(c msg.ErrorCode) pb.ErrorCode {
	return enumTo[pb.ErrorCode](pb.ErrorCode_value, "ERROR_CODE_", string(c))
}
func errFromPB(p pb.ErrorCode) msg.ErrorCode {
	if p == pb.ErrorCode_ERROR_CODE_UNSPECIFIED {
		return msg.ErrUnknown
	}
	s := enumFrom(pb.ErrorCode_name, "ERROR_CODE_", p)
	if s == "" {
		return msg.ErrUnknown
	}
	return msg.ErrorCode(s)
}

// ---- scalar helpers ----

func ts(t time.Time) *timestamppb.Timestamp {
	if t.IsZero() {
		return nil
	}
	return timestamppb.New(t)
}
func tsFrom(p *timestamppb.Timestamp) time.Time {
	if p == nil {
		return time.Time{}
	}
	return p.AsTime()
}
func tsPtr(t *time.Time) *timestamppb.Timestamp {
	if t == nil {
		return nil
	}
	return timestamppb.New(*t)
}
func tsPtrFrom(p *timestamppb.Timestamp) *time.Time {
	if p == nil {
		return nil
	}
	t := p.AsTime()
	return &t
}
func dur(d time.Duration) *durationpb.Duration { return durationpb.New(d) }

// drainDeadlineToPB keeps the field absent (legacy behaviour) when no deadline is set.
func drainDeadlineToPB(d time.Duration) *durationpb.Duration {
	if d <= 0 {
		return nil
	}
	return durationpb.New(d)
}
func durFrom(p *durationpb.Duration) time.Duration {
	if p == nil {
		return 0
	}
	return p.AsDuration()
}
func durPtr(d *time.Duration) *durationpb.Duration {
	if d == nil {
		return nil
	}
	return durationpb.New(*d)
}
func durPtrFrom(p *durationpb.Duration) *time.Duration {
	if p == nil {
		return nil
	}
	d := p.AsDuration()
	return &d
}
func strOrEmpty(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}
func strPtr(s string) *string {
	if s == "" {
		return nil
	}
	return proto.String(s)
}

func envToPB(e msg.Envelope) *pb.Envelope {
	return &pb.Envelope{MessageId: e.MessageID, SentAt: ts(e.SentAt)}
}
func envFromPB(p *pb.Envelope) msg.Envelope {
	if p == nil {
		return msg.Envelope{}
	}
	return msg.Envelope{MessageID: p.GetMessageId(), SentAt: tsFrom(p.GetSentAt())}
}

func bundleToPB(b msg.TrustBundle) *pb.TrustBundle {
	return &pb.TrustBundle{CertsPem: b.CertsPEM, ValidFrom: ts(b.ValidFrom), ValidTo: ts(b.ValidTo)}
}
func bundleFromPB(p *pb.TrustBundle) msg.TrustBundle {
	if p == nil {
		return msg.TrustBundle{}
	}
	return msg.TrustBundle{CertsPEM: p.GetCertsPem(), ValidFrom: tsFrom(p.GetValidFrom()), ValidTo: tsFrom(p.GetValidTo())}
}

// ---- Enroll ----

func EnrollRequestToPB(r msg.EnrollRequest) *pb.EnrollRequest {
	return &pb.EnrollRequest{
		CsrDer: r.CSRDER, AgentVersion: r.AgentVersion, ProtocolVersionsSupported: r.ProtocolVersionsSupported,
		HostFacts: &pb.HostFacts{Os: r.HostFacts.OS, Arch: r.HostFacts.Arch, RuntimeKind: r.HostFacts.RuntimeKind},
	}
}

func EnrollRequestFromPB(p *pb.EnrollRequest) msg.EnrollRequest {
	return msg.EnrollRequest{
		CSRDER: p.GetCsrDer(), AgentVersion: p.GetAgentVersion(), ProtocolVersionsSupported: p.GetProtocolVersionsSupported(),
		HostFacts: msg.HostFacts{OS: p.GetHostFacts().GetOs(), Arch: p.GetHostFacts().GetArch(), RuntimeKind: p.GetHostFacts().GetRuntimeKind()},
	}
}

func EnrollResponseToPB(r msg.EnrollResponse) *pb.EnrollResponse {
	return &pb.EnrollResponse{AgentId: r.AgentID, CertificateChain: r.CertificateChain, TrustBundle: bundleToPB(r.TrustBundle), ConnectEndpoint: r.ConnectEndpoint}
}

func EnrollResponseFromPB(p *pb.EnrollResponse) msg.EnrollResponse {
	return msg.EnrollResponse{AgentID: p.GetAgentId(), CertificateChain: p.GetCertificateChain(), TrustBundle: bundleFromPB(p.GetTrustBundle()), ConnectEndpoint: p.GetConnectEndpoint()}
}

// ---- Agent → Gateway ----

func AgentToPB(m msg.Outbound) (*pb.AgentToGateway, error) {
	switch v := m.(type) {
	case msg.SessionOpen:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_SessionOpen{SessionOpen: &pb.SessionOpen{
			Envelope: envToPB(v.Envelope), ProtocolVersion: v.ProtocolVersion, AgentVersion: v.AgentVersion,
			CapabilitiesSupported: capsToPB(v.CapabilitiesSupported),
			Resume:                &pb.Resume{LastAckedGenerationByBinding: v.Resume.LastAckedGenerationByBinding, LastTelemetrySeqByStream: v.Resume.LastTelemetrySeqByStream},
			AgentClock:            ts(v.AgentClock),
		}}}, nil
	case msg.LeaseRenew:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_LeaseRenew{LeaseRenew: &pb.LeaseRenew{Envelope: envToPB(v.Envelope), NonceConsumed: v.NonceConsumed, AgentClock: ts(v.AgentClock)}}}, nil
	case msg.CommandPull:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_CommandPull{CommandPull: &pb.CommandPull{Envelope: envToPB(v.Envelope), CommandId: v.CommandID}}}, nil
	case msg.CommandReceived:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_CommandReceived{CommandReceived: &pb.CommandReceived{Envelope: envToPB(v.Envelope), CommandId: v.CommandID, Generation: v.Generation}}}, nil
	case msg.CommandAck:
		d := &pb.AckDetail{Message: v.Detail.Message}
		switch v.Detail.Code {
		case msg.DetailNone:
		case msg.DetailAdmissionBlocked:
			d.Code = pb.AckDetailCode_ACK_DETAIL_CODE_ADMISSION_BLOCKED
		case msg.DetailLeaseNotActive:
			d.Code = pb.AckDetailCode_ACK_DETAIL_CODE_LEASE_NOT_ACTIVE
		default:
			d.Code = pb.AckDetailCode_ACK_DETAIL_CODE_ERROR
			d.ErrorCode = errToPB(msg.ErrorCode(v.Detail.Code))
		}
		if pr := v.Detail.PreflightResult; pr != nil {
			d.PreflightResult = &pb.PreflightResult{ContainerId: pr.ContainerID, ImageDigest: pr.ImageDigest, ConfigPathInContainer: pr.ConfigPathInContainer,
				HostPath: pr.HostPath, Mechanism: pr.Mechanism, CompetingSources: pr.CompetingSources, Writable: pr.Writable}
		}
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_CommandAck{CommandAck: &pb.CommandAck{
			Envelope: envToPB(v.Envelope), CommandId: v.CommandID, Generation: v.Generation, Outcome: outcomeToPB(v.Outcome), Detail: d, AgentClock: ts(v.AgentClock), ContainerStartedAt: tsPtr(v.ContainerStartedAt)}}}, nil
	case msg.ApplyProgress:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_ApplyProgress{ApplyProgress: &pb.ApplyProgress{
			Envelope: envToPB(v.Envelope), CommandId: v.CommandID, Step: stepToPB(v.Step), ReplicaRef: v.ReplicaRef, At: ts(v.At)}}}, nil
	case msg.ActualStateReportBatch:
		items := make([]*pb.ActualStateReport, 0, len(v.Items))
		for _, it := range v.Items {
			items = append(items, &pb.ActualStateReport{
				ItemId: it.ItemID, Sequence: it.Sequence, BindingId: it.BindingID, ReplicaRef: it.ReplicaRef, Incarnation: it.Incarnation,
				PersistedGeneration: it.PersistedGeneration, RestartObservedAt: tsPtr(it.RestartObservedAt), EffectiveHash: strPtr(it.EffectiveHash),
				VersionObservation: &pb.VersionObservation{Mechanism: it.VersionObservation.Mechanism, Value: it.VersionObservation.Value, Confidence: it.VersionObservation.Confidence},
				Correlation:        correlationToPB(it.Correlation),
				Health:             healthToPB(it.Health), ContainerStartedAt: tsPtr(it.ContainerStartedAt),
			})
		}
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_ActualStateReportBatch{ActualStateReportBatch: &pb.ActualStateReportBatch{Envelope: envToPB(v.Envelope), Items: items}}}, nil
	case msg.Heartbeat:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_Heartbeat{Heartbeat: &pb.Heartbeat{
			Envelope: envToPB(v.Envelope), AgentClock: ts(v.AgentClock), BufferDepthBytes: v.BufferDepthBytes, DropCount: v.DropCount,
			JournalDepth: v.JournalDepth, LeaseRemainingAtSend: dur(v.LeaseRemainingAtSend)}}}, nil
	case msg.Teardown:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_Teardown{Teardown: &pb.Teardown{Envelope: envToPB(v.Envelope), Reason: v.Reason}}}, nil
	case msg.DrainComplete:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_DrainComplete{DrainComplete: &pb.DrainComplete{
			Envelope: envToPB(v.Envelope), InReplyToMessageId: v.InReplyTo, AbandonedCommands: v.AbandonedCommands}}}, nil
	case msg.DiscoveryReport:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_DiscoveryReport{DiscoveryReport: discoveryReportToPB(v)}}, nil
	case msg.ConfigViewReply:
		r, err := configViewReplyToPB(v)
		if err != nil {
			return nil, err
		}
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_ConfigViewReply{ConfigViewReply: r}}, nil
	}
	return nil, fmt.Errorf("codec: unsupported outbound %T", m)
}

func AgentFromPB(p *pb.AgentToGateway) (msg.Outbound, error) {
	switch v := p.GetMsg().(type) {
	case *pb.AgentToGateway_SessionOpen:
		m := v.SessionOpen
		return msg.SessionOpen{Envelope: envFromPB(m.GetEnvelope()), ProtocolVersion: m.GetProtocolVersion(), AgentVersion: m.GetAgentVersion(),
			CapabilitiesSupported: capsFromPB(m.GetCapabilitiesSupported()),
			Resume:                msg.Resume{LastAckedGenerationByBinding: m.GetResume().GetLastAckedGenerationByBinding(), LastTelemetrySeqByStream: m.GetResume().GetLastTelemetrySeqByStream()},
			AgentClock:            tsFrom(m.GetAgentClock())}, nil
	case *pb.AgentToGateway_LeaseRenew:
		m := v.LeaseRenew
		return msg.LeaseRenew{Envelope: envFromPB(m.GetEnvelope()), NonceConsumed: m.GetNonceConsumed(), AgentClock: tsFrom(m.GetAgentClock())}, nil
	case *pb.AgentToGateway_CommandPull:
		return msg.CommandPull{Envelope: envFromPB(v.CommandPull.GetEnvelope()), CommandID: v.CommandPull.GetCommandId()}, nil
	case *pb.AgentToGateway_CommandReceived:
		m := v.CommandReceived
		return msg.CommandReceived{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), Generation: m.GetGeneration()}, nil
	case *pb.AgentToGateway_CommandAck:
		m := v.CommandAck
		d := msg.AckDetail{Message: m.GetDetail().GetMessage()}
		switch m.GetDetail().GetCode() {
		case pb.AckDetailCode_ACK_DETAIL_CODE_ADMISSION_BLOCKED:
			d.Code = msg.DetailAdmissionBlocked
		case pb.AckDetailCode_ACK_DETAIL_CODE_LEASE_NOT_ACTIVE:
			d.Code = msg.DetailLeaseNotActive
		case pb.AckDetailCode_ACK_DETAIL_CODE_ERROR:
			d.Code = msg.DetailOf(errFromPB(m.GetDetail().GetErrorCode()))
		}
		if pr := m.GetDetail().GetPreflightResult(); pr != nil {
			d.PreflightResult = &msg.PreflightResult{ContainerID: pr.GetContainerId(), ImageDigest: pr.GetImageDigest(), ConfigPathInContainer: pr.GetConfigPathInContainer(),
				HostPath: pr.GetHostPath(), Mechanism: pr.GetMechanism(), CompetingSources: pr.GetCompetingSources(), Writable: pr.GetWritable()}
		}
		return msg.CommandAck{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), Generation: m.GetGeneration(),
			Outcome: outcomeFromPB(m.GetOutcome()), Detail: d, AgentClock: tsFrom(m.GetAgentClock()), ContainerStartedAt: tsPtrFrom(m.GetContainerStartedAt())}, nil
	case *pb.AgentToGateway_ApplyProgress:
		m := v.ApplyProgress
		return msg.ApplyProgress{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), Step: stepFromPB(m.GetStep()), ReplicaRef: m.GetReplicaRef(), At: tsFrom(m.GetAt())}, nil
	case *pb.AgentToGateway_ActualStateReportBatch:
		m := v.ActualStateReportBatch
		items := make([]msg.ActualStateReport, 0, len(m.GetItems()))
		for _, it := range m.GetItems() {
			items = append(items, msg.ActualStateReport{
				ItemID: it.GetItemId(), Sequence: it.GetSequence(), BindingID: it.GetBindingId(), ReplicaRef: it.GetReplicaRef(), Incarnation: it.GetIncarnation(),
				PersistedGeneration: it.GetPersistedGeneration(), RestartObservedAt: tsPtrFrom(it.GetRestartObservedAt()), EffectiveHash: strOrEmpty(it.EffectiveHash),
				VersionObservation: msg.VersionObservation{Mechanism: it.GetVersionObservation().GetMechanism(), Value: it.GetVersionObservation().GetValue(), Confidence: it.GetVersionObservation().GetConfidence()},
				Correlation:        correlationFromPB(it.GetCorrelation()),
				Health:             healthFromPB(it.GetHealth()), ContainerStartedAt: tsPtrFrom(it.GetContainerStartedAt()),
			})
		}
		return msg.ActualStateReportBatch{Envelope: envFromPB(m.GetEnvelope()), Items: items}, nil
	case *pb.AgentToGateway_Heartbeat:
		m := v.Heartbeat
		return msg.Heartbeat{Envelope: envFromPB(m.GetEnvelope()), AgentClock: tsFrom(m.GetAgentClock()), BufferDepthBytes: m.GetBufferDepthBytes(),
			DropCount: m.GetDropCount(), JournalDepth: m.GetJournalDepth(), LeaseRemainingAtSend: durFrom(m.GetLeaseRemainingAtSend())}, nil
	case *pb.AgentToGateway_Teardown:
		return msg.Teardown{Envelope: envFromPB(v.Teardown.GetEnvelope()), Reason: v.Teardown.GetReason()}, nil
	case *pb.AgentToGateway_DrainComplete:
		return msg.DrainComplete{Envelope: envFromPB(v.DrainComplete.GetEnvelope()), InReplyTo: v.DrainComplete.GetInReplyToMessageId(),
			AbandonedCommands: v.DrainComplete.GetAbandonedCommands()}, nil
	case *pb.AgentToGateway_DiscoveryReport:
		return discoveryReportFromPB(v.DiscoveryReport), nil
	case *pb.AgentToGateway_ConfigViewReply:
		return configViewReplyFromPB(v.ConfigViewReply)
	}
	return nil, fmt.Errorf("codec: unsupported agent message %T", p.GetMsg())
}

// ---- Gateway → Agent ----

func grantToPB(g msg.LeaseGrant) *pb.LeaseGrant {
	vc := map[string]*durationpb.Duration{}
	for op, d := range g.VerbCeilings {
		vc[string(op)] = dur(d)
	}
	return &pb.LeaseGrant{
		Envelope: envToPB(g.Envelope), AgentId: g.AgentID, CnfThumbprint: g.CnfThumbprint, TargetId: g.TargetID, BindingIds: g.BindingIDs,
		AgentRevocationEpoch: g.AgentRevocationEpoch, FleetRevocationEpoch: g.FleetRevocationEpoch, IssuedAt: ts(g.IssuedAt), ExpiresAt: ts(g.ExpiresAt),
		Audience: g.Audience, Nonce: g.Nonce, Capabilities: capsToPB(g.Capabilities), VerbCeilings: vc, AdmissionMargin: dur(g.AdmissionMargin), ServerClock: ts(g.ServerClock),
	}
}

func grantFromPB(p *pb.LeaseGrant) msg.LeaseGrant {
	if p == nil {
		return msg.LeaseGrant{}
	}
	var vc map[msg.Operation]time.Duration
	if len(p.GetVerbCeilings()) > 0 {
		vc = map[msg.Operation]time.Duration{}
		for k, d := range p.GetVerbCeilings() {
			vc[msg.Operation(k)] = durFrom(d)
		}
	}
	return msg.LeaseGrant{
		Envelope: envFromPB(p.GetEnvelope()), AgentID: p.GetAgentId(), CnfThumbprint: p.GetCnfThumbprint(), TargetID: p.GetTargetId(), BindingIDs: p.GetBindingIds(),
		AgentRevocationEpoch: p.GetAgentRevocationEpoch(), FleetRevocationEpoch: p.GetFleetRevocationEpoch(), IssuedAt: tsFrom(p.GetIssuedAt()), ExpiresAt: tsFrom(p.GetExpiresAt()),
		Audience: p.GetAudience(), Nonce: p.GetNonce(), Capabilities: capsFromPB(p.GetCapabilities()), VerbCeilings: vc, AdmissionMargin: durFrom(p.GetAdmissionMargin()), ServerClock: tsFrom(p.GetServerClock()),
	}
}

func GatewayToPB(m msg.Inbound) (*pb.GatewayToAgent, error) {
	switch v := m.(type) {
	case msg.SessionAccept:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_SessionAccept{SessionAccept: &pb.SessionAccept{
			Envelope: envToPB(v.Envelope), SessionId: v.SessionID, AssignedTargetId: v.AssignedTargetID, RequiredCapabilities: capsToPB(v.RequiredCapabilities), Lease: grantToPB(v.Lease),
			Limits: &pb.Limits{MaxMessageBytes: v.Limits.MaxMessageBytes, MaxBatchBytes: v.Limits.MaxBatchBytes, InitialCredits: v.Limits.InitialCredits,
				HeartbeatInterval: dur(v.Limits.HeartbeatInterval), KeepaliveInterval: dur(v.Limits.KeepaliveInterval), SkewTolerance: dur(v.Limits.SkewTolerance), ReportInterval: dur(v.Limits.ReportInterval)},
			ServerClock: ts(v.ServerClock)}}}, nil
	case msg.SessionReject:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_SessionReject{SessionReject: &pb.SessionReject{Envelope: envToPB(v.Envelope), Code: errToPB(v.Code), RetryAfter: durPtr(v.RetryAfter), MinAgentVersion: strPtr(v.MinAgentVersion)}}}, nil
	case msg.LeaseGrant:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_LeaseGrant{LeaseGrant: grantToPB(v)}}, nil
	case msg.Revoke:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_Revoke{Revoke: &pb.Revoke{Envelope: envToPB(v.Envelope), AgentRevocationEpoch: v.AgentRevocationEpoch, FleetRevocationEpoch: v.FleetRevocationEpoch, Reason: v.Reason}}}, nil
	case msg.GenerationAvailable:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_GenerationAvailable{GenerationAvailable: &pb.GenerationAvailable{
			Envelope: envToPB(v.Envelope), CommandId: v.CommandID, BindingId: v.BindingID, Generation: v.Generation, Operation: opToPB(v.Operation), ConfigSha256: strPtr(v.ConfigSHA256), Deadline: ts(v.Deadline), Correlation: correlationToPB(v.Correlation)}}}, nil
	case msg.CommandOffer:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_CommandOffer{CommandOffer: &pb.CommandOffer{
			Envelope: envToPB(v.Envelope), CommandId: v.CommandID, Generation: v.Generation, IdempotencyKey: v.IdempotencyKey, BindingId: v.BindingID, Operation: opToPB(v.Operation),
			PayloadBytes: v.Payload, ConfigSha256: v.ConfigSHA256, SupersedesGeneration: v.SupersedesGeneration, Deadline: ts(v.Deadline), Correlation: correlationToPB(v.Correlation),
			ExpectedCurrentSha256: v.ExpectedCurrentSHA256}}}, nil
	case msg.ReportAck:
		rs := make([]*pb.ReportResult, 0, len(v.Results))
		for _, r := range v.Results {
			rs = append(rs, &pb.ReportResult{InReplyToItemId: r.InReplyToItemID, Sequence: r.Sequence, ReplicaId: strPtr(r.ReplicaID), IncarnationId: strPtr(r.IncarnationID),
				Trust: trustToPB(r.Trust), Disposition: dispToPB(r.Disposition), RefusedReason: errToPB(r.RefusedReason)})
		}
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_ReportAck{ReportAck: &pb.ReportAck{Envelope: envToPB(v.Envelope), InReplyToMessageId: v.InReplyToMessageID, Results: rs}}}, nil
	case msg.HeartbeatAck:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_HeartbeatAck{HeartbeatAck: &pb.HeartbeatAck{Envelope: envToPB(v.Envelope), ServerClock: ts(v.ServerClock), ClockOffsetMs: v.ClockOffsetMs, ClockState: clockToPB(v.ClockState)}}}, nil
	case msg.TrustBundleUpdate:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_TrustBundleUpdate{TrustBundleUpdate: &pb.TrustBundleUpdate{Envelope: envToPB(v.Envelope), TrustBundle: bundleToPB(v.TrustBundle)}}}, nil
	case msg.Drain:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_Drain{Drain: &pb.Drain{Envelope: envToPB(v.Envelope), Reason: v.Reason, ReconnectAfter: dur(v.ReconnectAfter), Deadline: drainDeadlineToPB(v.Deadline)}}}, nil
	case msg.Teardown:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_Teardown{Teardown: &pb.Teardown{Envelope: envToPB(v.Envelope), Reason: v.Reason}}}, nil
	case msg.Error:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_Error{Error: &pb.Error{Envelope: envToPB(v.Envelope), Code: errToPB(v.Code), RetryAfter: durPtr(v.RetryAfter), CorrelationId: v.CorrelationID, Message: v.Message}}}, nil
	case msg.DiscoveryAck:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_DiscoveryAck{DiscoveryAck: discoveryAckToPB(v)}}, nil
	case msg.TelemetryDeclarations:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_TelemetryDeclarations{TelemetryDeclarations: declarationsToPB(v)}}, nil
	case msg.LogRetention:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_LogRetention{LogRetention: &pb.LogRetention{Envelope: envToPB(v.Envelope), PlacementRef: v.PlacementRef, MaxBytes: v.MaxBytes}}}, nil
	case msg.LogRequest:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_LogRequest{LogRequest: logRequestToPB(v)}}, nil
	case msg.ConfigViewRequest:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_ConfigViewRequest{ConfigViewRequest: configViewRequestToPB(v)}}, nil
	}
	return nil, fmt.Errorf("codec: unsupported inbound %T", m)
}

func GatewayFromPB(p *pb.GatewayToAgent) (msg.Inbound, error) {
	switch v := p.GetMsg().(type) {
	case *pb.GatewayToAgent_SessionAccept:
		m := v.SessionAccept
		l := m.GetLimits()
		return msg.SessionAccept{Envelope: envFromPB(m.GetEnvelope()), SessionID: m.GetSessionId(), AssignedTargetID: m.GetAssignedTargetId(),
			RequiredCapabilities: capsFromPB(m.GetRequiredCapabilities()), Lease: grantFromPB(m.GetLease()),
			Limits: msg.Limits{MaxMessageBytes: l.GetMaxMessageBytes(), MaxBatchBytes: l.GetMaxBatchBytes(), InitialCredits: l.GetInitialCredits(),
				HeartbeatInterval: durFrom(l.GetHeartbeatInterval()), KeepaliveInterval: durFrom(l.GetKeepaliveInterval()), SkewTolerance: durFrom(l.GetSkewTolerance()), ReportInterval: durFrom(l.GetReportInterval())},
			ServerClock: tsFrom(m.GetServerClock())}, nil
	case *pb.GatewayToAgent_SessionReject:
		m := v.SessionReject
		return msg.SessionReject{Envelope: envFromPB(m.GetEnvelope()), Code: errFromPB(m.GetCode()), RetryAfter: durPtrFrom(m.GetRetryAfter()), MinAgentVersion: strOrEmpty(m.MinAgentVersion)}, nil
	case *pb.GatewayToAgent_LeaseGrant:
		return grantFromPB(v.LeaseGrant), nil
	case *pb.GatewayToAgent_Revoke:
		m := v.Revoke
		return msg.Revoke{Envelope: envFromPB(m.GetEnvelope()), AgentRevocationEpoch: m.AgentRevocationEpoch, FleetRevocationEpoch: m.FleetRevocationEpoch, Reason: m.GetReason()}, nil
	case *pb.GatewayToAgent_GenerationAvailable:
		m := v.GenerationAvailable
		return msg.GenerationAvailable{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), BindingID: m.GetBindingId(), Generation: m.GetGeneration(),
			Operation: opFromPB(m.GetOperation()), ConfigSHA256: strOrEmpty(m.ConfigSha256), Deadline: tsFrom(m.GetDeadline()), Correlation: correlationFromPB(m.GetCorrelation())}, nil
	case *pb.GatewayToAgent_CommandOffer:
		m := v.CommandOffer
		return msg.CommandOffer{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), Generation: m.GetGeneration(), IdempotencyKey: m.GetIdempotencyKey(), BindingID: m.GetBindingId(),
			Operation: opFromPB(m.GetOperation()), Payload: m.GetPayloadBytes(), ConfigSHA256: m.GetConfigSha256(), SupersedesGeneration: m.SupersedesGeneration, Deadline: tsFrom(m.GetDeadline()), Correlation: correlationFromPB(m.GetCorrelation()),
			ExpectedCurrentSHA256: m.ExpectedCurrentSha256}, nil
	case *pb.GatewayToAgent_ReportAck:
		m := v.ReportAck
		rs := make([]msg.ReportResult, 0, len(m.GetResults()))
		for _, r := range m.GetResults() {
			rr := msg.ReportResult{InReplyToItemID: r.GetInReplyToItemId(), Sequence: r.GetSequence(), ReplicaID: strOrEmpty(r.ReplicaId), IncarnationID: strOrEmpty(r.IncarnationId),
				Trust: trustFromPB(r.GetTrust()), Disposition: dispFromPB(r.GetDisposition())}
			if r.GetRefusedReason() != pb.ErrorCode_ERROR_CODE_UNSPECIFIED {
				rr.RefusedReason = errFromPB(r.GetRefusedReason())
			}
			rs = append(rs, rr)
		}
		return msg.ReportAck{Envelope: envFromPB(m.GetEnvelope()), InReplyToMessageID: m.GetInReplyToMessageId(), Results: rs}, nil
	case *pb.GatewayToAgent_HeartbeatAck:
		m := v.HeartbeatAck
		return msg.HeartbeatAck{Envelope: envFromPB(m.GetEnvelope()), ServerClock: tsFrom(m.GetServerClock()), ClockOffsetMs: m.GetClockOffsetMs(), ClockState: clockFromPB(m.GetClockState())}, nil
	case *pb.GatewayToAgent_TrustBundleUpdate:
		return msg.TrustBundleUpdate{Envelope: envFromPB(v.TrustBundleUpdate.GetEnvelope()), TrustBundle: bundleFromPB(v.TrustBundleUpdate.GetTrustBundle())}, nil
	case *pb.GatewayToAgent_Drain:
		return msg.Drain{Envelope: envFromPB(v.Drain.GetEnvelope()), Reason: v.Drain.GetReason(), ReconnectAfter: durFrom(v.Drain.GetReconnectAfter()), Deadline: durFrom(v.Drain.GetDeadline())}, nil
	case *pb.GatewayToAgent_Teardown:
		return msg.Teardown{Envelope: envFromPB(v.Teardown.GetEnvelope()), Reason: v.Teardown.GetReason()}, nil
	case *pb.GatewayToAgent_Error:
		m := v.Error
		return msg.Error{Envelope: envFromPB(m.GetEnvelope()), Code: errFromPB(m.GetCode()), RetryAfter: durPtrFrom(m.GetRetryAfter()), CorrelationID: m.GetCorrelationId(), Message: m.GetMessage()}, nil
	case *pb.GatewayToAgent_DiscoveryAck:
		return discoveryAckFromPB(v.DiscoveryAck), nil
	case *pb.GatewayToAgent_TelemetryDeclarations:
		return declarationsFromPB(v.TelemetryDeclarations), nil
	case *pb.GatewayToAgent_LogRetention:
		return logRetentionFromPB(v.LogRetention), nil
	case *pb.GatewayToAgent_LogRequest:
		return logRequestFromPB(v.LogRequest), nil
	case *pb.GatewayToAgent_ConfigViewRequest:
		return configViewRequestFromPB(v.ConfigViewRequest), nil
	}
	return nil, fmt.Errorf("codec: unsupported gateway message %T", p.GetMsg())
}

// ---- gRPC status carrying an Error (used by Enroll and stream close) ----

func StatusFromError(e msg.Error) error {
	code := codes.PermissionDenied
	if msg.Classify(e.Code) == msg.ClassRetry {
		code = codes.Unavailable
	}
	st := status.New(code, string(e.Code))
	withDetails, err := st.WithDetails(&pb.Error{Envelope: envToPB(e.Envelope), Code: errToPB(e.Code), RetryAfter: durPtr(e.RetryAfter), CorrelationId: e.CorrelationID, Message: e.Message})
	if err != nil {
		return st.Err()
	}
	return withDetails.Err()
}

// ErrorFromStatus returns a *msg.ProtocolError when err carries a pb.Error
// detail, otherwise err unchanged.
func ErrorFromStatus(err error) error {
	st, ok := status.FromError(err)
	if !ok || st == nil {
		return err
	}
	for _, d := range st.Details() {
		if e, ok := d.(*pb.Error); ok {
			return &msg.ProtocolError{Code: errFromPB(e.GetCode()), RetryAfter: durPtrFrom(e.GetRetryAfter()), Message: e.GetMessage()}
		}
	}
	return err
}

// ---- Telemetry stream (gateway §4.3, §4.10) ----

func metricTypeToPB(t msg.MetricType) pb.MetricType {
	return enumTo[pb.MetricType](pb.MetricType_value, "METRIC_TYPE_", string(t))
}

func metricTypeFromPB(p pb.MetricType) msg.MetricType {
	if p == pb.MetricType_METRIC_TYPE_UNSPECIFIED {
		return ""
	}
	return msg.MetricType(strings.ToLower(enumFrom(pb.MetricType_name, "METRIC_TYPE_", p)))
}

func TelemetryUpToPB(b msg.TelemetryBatch) *pb.TelemetryUp {
	p := &pb.TelemetryBatch{Envelope: envToPB(b.Envelope), StreamId: b.StreamID, BatchSeq: b.BatchSeq, ProducedAt: ts(b.ProducedAt)}
	for _, s := range b.Samples {
		p.Samples = append(p.Samples, &pb.Sample{SeriesKey: s.SeriesKey, Type: metricTypeToPB(s.Type), Ts: ts(s.TS), Value: s.Value, Labels: s.Labels})
	}
	for _, g := range b.GapMarkers {
		p.GapMarkers = append(p.GapMarkers, &pb.GapMarker{FromSeq: g.FromSeq, ToSeq: g.ToSeq, Reason: g.Reason})
	}
	return &pb.TelemetryUp{Msg: &pb.TelemetryUp_TelemetryBatch{TelemetryBatch: p}}
}

func TelemetryUpFromPB(p *pb.TelemetryUp) (msg.TelemetryBatch, error) {
	b := p.GetTelemetryBatch()
	if b == nil {
		return msg.TelemetryBatch{}, fmt.Errorf("codec: TelemetryUp carries no batch")
	}
	out := msg.TelemetryBatch{Envelope: envFromPB(b.Envelope), StreamID: b.StreamId, BatchSeq: b.BatchSeq, ProducedAt: tsFrom(b.ProducedAt)}
	for _, s := range b.Samples {
		out.Samples = append(out.Samples, msg.Sample{SeriesKey: s.SeriesKey, Type: metricTypeFromPB(s.Type), TS: tsFrom(s.Ts), Value: s.Value, Labels: s.Labels})
	}
	for _, g := range b.GapMarkers {
		out.GapMarkers = append(out.GapMarkers, msg.GapMarker{FromSeq: g.FromSeq, ToSeq: g.ToSeq, Reason: g.Reason})
	}
	return out, nil
}

func TelemetryDownToPB(m msg.TelemetryInbound) (*pb.TelemetryDown, error) {
	switch v := m.(type) {
	case msg.TelemetryAck:
		return &pb.TelemetryDown{Msg: &pb.TelemetryDown_TelemetryAck{TelemetryAck: &pb.TelemetryAck{
			Envelope: envToPB(v.Envelope), StreamId: v.StreamID, HighWatermarkSeq: v.HighWatermarkSeq, CreditsGranted: v.CreditsGranted}}}, nil
	case msg.TelemetryCredit:
		return &pb.TelemetryDown{Msg: &pb.TelemetryDown_TelemetryCredit{TelemetryCredit: &pb.TelemetryCredit{
			Envelope: envToPB(v.Envelope), StreamId: v.StreamID, CreditsGranted: v.CreditsGranted}}}, nil
	case msg.TelemetryReject:
		return &pb.TelemetryDown{Msg: &pb.TelemetryDown_TelemetryReject{TelemetryReject: &pb.TelemetryReject{
			Envelope: envToPB(v.Envelope), StreamId: v.StreamID, Code: errToPB(v.Code), RetryAfter: durPtr(v.RetryAfter)}}}, nil
	}
	return nil, fmt.Errorf("codec: unknown telemetry message %T", m)
}

func TelemetryDownFromPB(p *pb.TelemetryDown) (msg.TelemetryInbound, error) {
	switch v := p.Msg.(type) {
	case *pb.TelemetryDown_TelemetryAck:
		a := v.TelemetryAck
		return msg.TelemetryAck{Envelope: envFromPB(a.Envelope), StreamID: a.StreamId, HighWatermarkSeq: a.HighWatermarkSeq, CreditsGranted: a.CreditsGranted}, nil
	case *pb.TelemetryDown_TelemetryCredit:
		c := v.TelemetryCredit
		return msg.TelemetryCredit{Envelope: envFromPB(c.Envelope), StreamID: c.StreamId, CreditsGranted: c.CreditsGranted}, nil
	case *pb.TelemetryDown_TelemetryReject:
		r := v.TelemetryReject
		return msg.TelemetryReject{Envelope: envFromPB(r.Envelope), StreamID: r.StreamId, Code: errFromPB(r.Code), RetryAfter: durPtrFrom(r.RetryAfter)}, nil
	}
	return nil, fmt.Errorf("codec: TelemetryDown carries nothing")
}
