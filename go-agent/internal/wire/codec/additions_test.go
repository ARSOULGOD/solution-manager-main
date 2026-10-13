package codec

import (
	"reflect"
	"testing"
	"time"

	"google.golang.org/protobuf/reflect/protoreflect"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

// Plan 015 T2.3: every addition round-trips through the generated proto.
func TestT23AgentMessagesRoundTrip(t *testing.T) {
	env := msg.Envelope{MessageID: "m", SentAt: now}
	started := now.Add(-time.Hour)
	cases := []msg.Outbound{
		msg.CommandAck{Envelope: env, CommandID: "c", Generation: 2, Outcome: msg.OutcomeApplied, AgentClock: now, ContainerStartedAt: &started},
		msg.CommandAck{Envelope: env, CommandID: "c", Generation: 2, Outcome: msg.OutcomeRefused, Detail: msg.AckDetail{Code: msg.DetailOf(msg.ErrTargetMoved)}, AgentClock: now},
		msg.ActualStateReportBatch{Envelope: env, Items: []msg.ActualStateReport{{
			ItemID: "i", Sequence: 9, BindingID: "b", ReplicaRef: "r", Incarnation: "abc", VersionObservation: msg.VersionObservation{Mechanism: "none", Confidence: "none"},
			ContainerStartedAt: &started,
			Health:             &msg.HealthObservation{State: msg.HealthHealthy, ObservedAt: now, Strength: msg.StrengthReadiness, HTTPStatus: 503, ActuatorStatus: "DOWN"}}}},
		msg.ActualStateReportBatch{Envelope: env, Items: []msg.ActualStateReport{{
			ItemID: "i", Sequence: 10, BindingID: "b", ReplicaRef: "r", Incarnation: "abc", VersionObservation: msg.VersionObservation{Mechanism: "none", Confidence: "none"},
			Health: &msg.HealthObservation{State: msg.HealthUnreachable, ObservedAt: now, Failure: msg.ProbeFailureTimeout}}}},
		msg.DiscoveryReport{Envelope: env, Items: []msg.DiscoveredContainer{{
			ContainerID: "id", ContainerName: "cache-a", ImageRef: "img:1", Labels: map[string]string{"com.solace.sm.instance": "cache-a"},
			Mounts:         []msg.DiscoveredMount{{Source: "/srv", Destination: "/etc/app"}},
			PublishedPorts: []msg.DiscoveredPort{{ContainerPort: 8080, HostPort: 18080}}}}},
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

func TestT23GatewayMessagesRoundTrip(t *testing.T) {
	env := msg.Envelope{MessageID: "m", SentAt: now}
	expected, empty := "ab12", ""
	cases := []msg.Inbound{
		msg.CommandOffer{Envelope: env, CommandID: "c", Generation: 3, IdempotencyKey: "k", BindingID: "b", Operation: msg.OpRematerialize,
			Payload: []byte("x"), ConfigSHA256: "h", Deadline: now, ExpectedCurrentSHA256: &expected},
		msg.CommandOffer{Envelope: env, CommandID: "c", Generation: 3, IdempotencyKey: "k", BindingID: "b", Operation: msg.OpRematerialize,
			Payload: []byte("x"), ConfigSHA256: "h", Deadline: now, ExpectedCurrentSHA256: &empty}, // present and empty = "must be absent"
		msg.CommandOffer{Envelope: env, CommandID: "c", Generation: 3, IdempotencyKey: "k", BindingID: "b", Operation: msg.OpApply,
			Payload: []byte("x"), ConfigSHA256: "h", Deadline: now}, // absent stays absent
		msg.DiscoveryAck{Envelope: env, InReplyToMessageID: "r", Results: []msg.DiscoveryResult{
			{ContainerID: "id", State: msg.DiscoveryRegistered, BindingID: "placement-1"},
			{ContainerID: "id2", State: msg.DiscoveryRefused, Code: "discovery.refused"}}},
		msg.TelemetryDeclarations{Envelope: env, BindingID: "placement-1", Interval: 5 * time.Second,
			Metrics: []msg.TelemetryMetric{{Name: "cache.size", MetricType: msg.MetricGauge, Unit: "entries",
				HTTPJSON: &msg.HTTPJSONSource{Port: 8080, Path: "/api/cache/size", Pointer: "/size", OnUnavailable: msg.OnUnavailableError}}},
			HealthProbe: &msg.HealthProbe{Kind: msg.ProbeActuatorHealth, Path: "/actuator/health", HostPort: 18080, Strength: msg.StrengthReadiness}},
		msg.TelemetryDeclarations{Envelope: env, BindingID: "placement-2", Interval: 5 * time.Second}, // no probe declared: HealthProbe stays nil
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

// The wire numbers are the ones proto-amendments-015.md fixes (AGW-D11: never
// renumbered). A drift here is caught before the append-only gate sees it.
func TestT23WireNumbers(t *testing.T) {
	num := func(m protoreflect.ProtoMessage, field string) int {
		fd := m.ProtoReflect().Descriptor().Fields().ByName(protoreflect.Name(field))
		if fd == nil {
			t.Fatalf("%T has no field %s", m, field)
		}
		return int(fd.Number())
	}
	for _, c := range []struct {
		m     protoreflect.ProtoMessage
		field string
		want  int
	}{
		{&pb.ActualStateReport{}, "health", 11}, {&pb.ActualStateReport{}, "container_started_at", 12},
		{&pb.CommandAck{}, "container_started_at", 7}, {&pb.CommandOffer{}, "expected_current_sha256", 12},
		{&pb.AgentToGateway{}, "discovery_report", 11}, {&pb.GatewayToAgent{}, "discovery_ack", 13},
		{&pb.DiscoveryResult{}, "binding_id", 4}, {&pb.GatewayToAgent{}, "telemetry_declarations", 14},
		{&pb.TelemetryDeclarations{}, "health_probe", 5},
		{&pb.HealthObservation{}, "http_status", 4}, {&pb.HealthObservation{}, "actuator_status", 5}, {&pb.HealthObservation{}, "failure", 6},
	} {
		if got := num(c.m, c.field); got != c.want {
			t.Errorf("%T.%s = %d, want %d", c.m, c.field, got, c.want)
		}
	}
	for name, want := range map[string]int32{
		"ERROR_CODE_TARGET_MOVED": 38, "ERROR_CODE_RENDER_CHANGED": 39,
		"CAPABILITY_DISCOVERY": 4, "CAPABILITY_REMATERIALIZE": 5, "CAPABILITY_DECLARATIONS": 6, "OPERATION_REMATERIALIZE": 5,
	} {
		var got int32
		var ok bool
		for _, m := range []map[string]int32{pb.ErrorCode_value, pb.Capability_value, pb.Operation_value} {
			if v, found := m[name]; found {
				got, ok = v, true
			}
		}
		if !ok || got != want {
			t.Errorf("%s = %d (found %v), want %d", name, got, ok, want)
		}
	}
}
