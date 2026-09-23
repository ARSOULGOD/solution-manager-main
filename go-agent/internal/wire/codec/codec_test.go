package codec

import (
	"errors"
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

var now = time.Date(2026, 9, 22, 12, 0, 0, 0, time.UTC)

func u64(v uint64) *uint64 { return &v }

func TestAgentMessagesRoundTrip(t *testing.T) {
	ra := 5 * time.Second
	_ = ra
	env := msg.Envelope{MessageID: "m", SentAt: now}
	cases := []msg.Outbound{
		msg.SessionOpen{Envelope: env, ProtocolVersion: 1, AgentVersion: "v", CapabilitiesSupported: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle},
			Resume: msg.Resume{LastAckedGenerationByBinding: map[string]uint64{"b": 3}, LastTelemetrySeqByStream: map[string]uint64{}}, AgentClock: now},
		msg.LeaseRenew{Envelope: env, NonceConsumed: "n", AgentClock: now},
		msg.CommandPull{Envelope: env, CommandID: "c"},
		msg.CommandReceived{Envelope: env, CommandID: "c", Generation: 2},
		msg.CommandAck{Envelope: env, CommandID: "c", Generation: 2, Outcome: msg.OutcomeRefused,
			Detail: msg.AckDetail{Code: msg.DetailAdmissionBlocked, Message: "x"}, AgentClock: now},
		msg.CommandAck{Envelope: env, CommandID: "c", Generation: 2, Outcome: msg.OutcomePreflightOK,
			Detail: msg.AckDetail{PreflightResult: &msg.PreflightResult{ContainerID: "id", HostPath: "/h", Writable: true, CompetingSources: []string{"a"}}}, AgentClock: now},
		msg.CommandAck{Envelope: env, CommandID: "c", Generation: 2, Outcome: msg.OutcomeRefused,
			Detail: msg.AckDetail{Code: msg.DetailOf(msg.ErrKeyHashMismatch)}, AgentClock: now},
		msg.ApplyProgress{Envelope: env, CommandID: "c", Step: msg.StepPersisted, ReplicaRef: "r", At: now},
		msg.ActualStateReportBatch{Envelope: env, Items: []msg.ActualStateReport{{
			ItemID: "i", Sequence: 9, BindingID: "b", ReplicaRef: "r", Incarnation: "abc", PersistedGeneration: 2,
			RestartObservedAt: &now, EffectiveHash: "h", VersionObservation: msg.VersionObservation{Mechanism: "image-digest", Value: "sha", Confidence: "low"}}}},
		msg.Heartbeat{Envelope: env, AgentClock: now, JournalDepth: 1, LeaseRemainingAtSend: time.Minute},
		msg.Teardown{Envelope: env, Reason: "bye"},
	}
	for _, in := range cases {
		p, err := AgentToPB(in)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		out, err := AgentFromPB(p)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		if !reflect.DeepEqual(in, out) {
			t.Errorf("%T round trip:\n in=%+v\nout=%+v", in, in, out)
		}
	}
}

func TestGatewayMessagesRoundTrip(t *testing.T) {
	ra := 5 * time.Second
	env := msg.Envelope{MessageID: "m", SentAt: now}
	grant := msg.LeaseGrant{Envelope: env, AgentID: "a", CnfThumbprint: "tp", TargetID: "t", BindingIDs: []string{"b"},
		AgentRevocationEpoch: 1, FleetRevocationEpoch: 2, IssuedAt: now, ExpiresAt: now.Add(time.Hour), Audience: "dp", Nonce: "n",
		Capabilities: []msg.Capability{msg.CapApplyConfig}, VerbCeilings: map[msg.Operation]time.Duration{msg.OpApply: 2 * time.Minute},
		AdmissionMargin: 30 * time.Second, ServerClock: now}
	cases := []msg.Inbound{
		msg.SessionAccept{Envelope: env, SessionID: "s", AssignedTargetID: "t", RequiredCapabilities: []msg.Capability{msg.CapLifecycle}, Lease: grant,
			Limits: msg.Limits{MaxMessageBytes: 1, MaxBatchBytes: 2, InitialCredits: 3, HeartbeatInterval: time.Second, KeepaliveInterval: 2 * time.Second, SkewTolerance: 3 * time.Second, ReportInterval: 4 * time.Second}, ServerClock: now},
		msg.SessionReject{Envelope: env, Code: msg.ErrReEnrollRequired, RetryAfter: nil, MinAgentVersion: "2"},
		msg.SessionReject{Envelope: env, Code: msg.ErrAdmissionLimited, RetryAfter: &ra},
		grant,
		msg.Revoke{Envelope: env, AgentRevocationEpoch: u64(5), Reason: "r"},
		msg.GenerationAvailable{Envelope: env, CommandID: "c", BindingID: "b", Generation: 3, Operation: msg.OpRestart, ConfigSHA256: "h", Deadline: now},
		msg.CommandOffer{Envelope: env, CommandID: "c", Generation: 3, IdempotencyKey: "k", BindingID: "b", Operation: msg.OpApply, Payload: []byte("x"), ConfigSHA256: "h", SupersedesGeneration: u64(2), Deadline: now},
		msg.ReportAck{Envelope: env, InReplyToMessageID: "m0", Results: []msg.ReportResult{{InReplyToItemID: "i", Sequence: 9, ReplicaID: "rid", IncarnationID: "iid", Trust: msg.TrustTrusted, Disposition: msg.DispApplied},
			{InReplyToItemID: "j", Sequence: 10, Trust: msg.TrustUntrusted, Disposition: msg.DispRefused, RefusedReason: msg.ErrReplicaCapExceeded}}},
		msg.HeartbeatAck{Envelope: env, ServerClock: now, ClockOffsetMs: -12, ClockState: msg.ClockSuspect},
		msg.TrustBundleUpdate{Envelope: env, TrustBundle: msg.TrustBundle{CertsPEM: [][]byte{[]byte("pem")}, ValidFrom: now, ValidTo: now.Add(time.Hour)}},
		msg.Drain{Envelope: env, Reason: "restart", ReconnectAfter: 7 * time.Second},
		msg.Teardown{Envelope: env, Reason: "bye"},
		msg.Error{Envelope: env, Code: msg.ErrDraining, RetryAfter: &ra, CorrelationID: "x", Message: "m"},
	}
	for _, in := range cases {
		p, err := GatewayToPB(in)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		out, err := GatewayFromPB(p)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		if !reflect.DeepEqual(in, out) {
			t.Errorf("%T round trip:\n in=%+v\nout=%+v", in, in, out)
		}
	}
}

func TestUnknownErrorCodeMapsToUnknown(t *testing.T) {
	p, _ := GatewayToPB(msg.Error{Code: msg.ErrorCode("FUTURE_CODE")})
	out, _ := GatewayFromPB(p)
	if out.(msg.Error).Code != msg.ErrUnknown {
		t.Fatalf("got %s", out.(msg.Error).Code)
	}
}

func TestStatusRoundTrip(t *testing.T) {
	ra := time.Second
	err := StatusFromError(msg.Error{Code: msg.ErrTokenConsumed, RetryAfter: &ra, Message: "used"})
	got := ErrorFromStatus(err)
	var pe *msg.ProtocolError
	if !errors.As(got, &pe) || pe.Code != msg.ErrTokenConsumed || pe.RetryAfter == nil || *pe.RetryAfter != ra {
		t.Fatalf("%v", got)
	}
	plain := errors.New("dial: refused")
	if ErrorFromStatus(plain) != plain {
		t.Fatal("non-status errors must pass through")
	}
}
