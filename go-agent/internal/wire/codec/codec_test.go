package codec

import (
	"errors"
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
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
			Limits: msg.Limits{MaxMessageBytes: 1, MaxBatchBytes: 2, InitialCredits: 3, HeartbeatInterval: time.Second, KeepaliveInterval: 2 * time.Second, SkewTolerance: 3 * time.Second, ReportInterval: 4 * time.Second,
				TelemetryPullInterval: 5 * time.Second, TelemetryPushInterval: time.Minute}, ServerClock: now},
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

// A gateway that predates AG-D26 sends neither field; the agent must read 0,
// its local default.
func TestAbsentTelemetryIntervalsDecodeToZero(t *testing.T) {
	out, err := GatewayFromPB(&pb.GatewayToAgent{Msg: &pb.GatewayToAgent_SessionAccept{SessionAccept: &pb.SessionAccept{
		Limits: &pb.Limits{ReportInterval: dur(time.Minute)}}}})
	if err != nil {
		t.Fatal(err)
	}
	l := out.(msg.SessionAccept).Limits
	if l.TelemetryPullInterval != 0 || l.TelemetryPushInterval != 0 || l.ReportInterval != time.Minute {
		t.Fatalf("limits %+v", l)
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

// CommandAck.Operation is agent-internal: the gateway design's CommandAck
// field list carries no operation, and the DP already knows it. This asserts
// the omission is deliberate — the round-trip test above only stays green
// because its fixtures leave the field zero, which would hide a regression
// here.
func TestCommandAckOperationIsNotOnTheWire(t *testing.T) {
	in := msg.CommandAck{Envelope: msg.Envelope{MessageID: "m"}, CommandID: "c1", Generation: 3,
		Outcome: msg.OutcomeRefused, Operation: msg.OpRestart}
	p, err := AgentToPB(in)
	if err != nil {
		t.Fatal(err)
	}
	out, err := AgentFromPB(p)
	if err != nil {
		t.Fatal(err)
	}
	got := out.(msg.CommandAck)
	if got.Operation != "" {
		t.Fatalf("Operation must not survive the wire, got %q", got.Operation)
	}
	if got.CommandID != "c1" || got.Outcome != msg.OutcomeRefused || got.Generation != 3 {
		t.Fatalf("the rest of the ack must still round-trip: %+v", got)
	}
}

func TestTelemetryMessagesRoundTrip(t *testing.T) {
	env := msg.Envelope{MessageID: "m", SentAt: now}
	ra := 3 * time.Second
	up := msg.TelemetryBatch{Envelope: env, StreamID: "binding-1", BatchSeq: 7, ProducedAt: now,
		Samples: []msg.Sample{{SeriesKey: "cache.hit_rate", Type: msg.MetricGauge, TS: now, Value: 0.93,
			Labels: map[string]string{"binding_id": "binding-1", "replica_id": "r", "incarnation_id": "i"}}},
		GapMarkers: []msg.GapMarker{{FromSeq: 3, ToSeq: 6, Reason: "DROPPED_OLDEST"}}}
	got, err := TelemetryUpFromPB(TelemetryUpToPB(up))
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(up, got) {
		t.Errorf("batch round trip:\n in=%+v\nout=%+v", up, got)
	}
	for _, in := range []msg.TelemetryInbound{
		msg.TelemetryAck{Envelope: env, StreamID: "binding-1", HighWatermarkSeq: 7, CreditsGranted: 1},
		msg.TelemetryCredit{Envelope: env, StreamID: "binding-1", CreditsGranted: 4},
		msg.TelemetryReject{Envelope: env, StreamID: "binding-1", Code: msg.ErrUnknownReplica, RetryAfter: &ra},
	} {
		p, err := TelemetryDownToPB(in)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		out, err := TelemetryDownFromPB(p)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		if !reflect.DeepEqual(in, out) {
			t.Errorf("%T round trip:\n in=%+v\nout=%+v", in, in, out)
		}
	}
}

func TestMetricTypeCrossesTheWireAsItsNamedValue(t *testing.T) {
	for mt, want := range map[msg.MetricType]pb.MetricType{
		msg.MetricCounter:   pb.MetricType_METRIC_TYPE_COUNTER,
		msg.MetricGauge:     pb.MetricType_METRIC_TYPE_GAUGE,
		msg.MetricHistogram: pb.MetricType_METRIC_TYPE_HISTOGRAM,
	} {
		p := TelemetryUpToPB(msg.TelemetryBatch{Samples: []msg.Sample{{Type: mt}}})
		if got := p.GetTelemetryBatch().Samples[0].Type; got != want {
			t.Errorf("%s encodes as %v, want %v", mt, got, want)
		}
	}
}

func TestEmptyTelemetryEnvelopesAreErrors(t *testing.T) {
	if _, err := TelemetryUpFromPB(&pb.TelemetryUp{}); err == nil {
		t.Error("an up message with no batch must be an error")
	}
	if _, err := TelemetryDownFromPB(&pb.TelemetryDown{}); err == nil {
		t.Error("a down message with nothing in it must be an error")
	}
}
