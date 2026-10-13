package codec

import (
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

// The plan 015 T2.3 additions: health, discovery, telemetry declarations.
// Enum values carry the proto prefix; the msg side uses the bare names.

func healthStateToPB(s msg.HealthState) pb.HealthState {
	return enumTo[pb.HealthState](pb.HealthState_value, "HEALTH_STATE_", string(s))
}
func healthStateFromPB(p pb.HealthState) msg.HealthState {
	if p == pb.HealthState_HEALTH_STATE_UNSPECIFIED {
		return msg.HealthUnspecified
	}
	return msg.HealthState(enumFrom(pb.HealthState_name, "HEALTH_STATE_", p))
}

func strengthToPB(s msg.ProbeStrength) pb.ProbeStrength {
	return enumTo[pb.ProbeStrength](pb.ProbeStrength_value, "PROBE_STRENGTH_", string(s))
}
func strengthFromPB(p pb.ProbeStrength) msg.ProbeStrength {
	if p == pb.ProbeStrength_PROBE_STRENGTH_UNSPECIFIED {
		return msg.StrengthUnspecified
	}
	return msg.ProbeStrength(enumFrom(pb.ProbeStrength_name, "PROBE_STRENGTH_", p))
}

func failureToPB(f msg.ProbeFailure) pb.ProbeFailure {
	if f == msg.ProbeFailureNone {
		return pb.ProbeFailure_PROBE_FAILURE_NONE
	}
	return enumTo[pb.ProbeFailure](pb.ProbeFailure_value, "PROBE_FAILURE_", string(f))
}
func failureFromPB(p pb.ProbeFailure) msg.ProbeFailure {
	if p == pb.ProbeFailure_PROBE_FAILURE_NONE {
		return msg.ProbeFailureNone
	}
	return msg.ProbeFailure(enumFrom(pb.ProbeFailure_name, "PROBE_FAILURE_", p))
}

func healthToPB(h *msg.HealthObservation) *pb.HealthObservation {
	if h == nil {
		return nil
	}
	return &pb.HealthObservation{State: healthStateToPB(h.State), ObservedAt: ts(h.ObservedAt), ProbeStrength: strengthToPB(h.Strength),
		HttpStatus: h.HTTPStatus, ActuatorStatus: h.ActuatorStatus, Failure: failureToPB(h.Failure)}
}
func healthFromPB(p *pb.HealthObservation) *msg.HealthObservation {
	if p == nil {
		return nil
	}
	return &msg.HealthObservation{State: healthStateFromPB(p.GetState()), ObservedAt: tsFrom(p.GetObservedAt()), Strength: strengthFromPB(p.GetProbeStrength()),
		HTTPStatus: p.GetHttpStatus(), ActuatorStatus: p.GetActuatorStatus(), Failure: failureFromPB(p.GetFailure())}
}

func discoveryReportToPB(r msg.DiscoveryReport) *pb.DiscoveryReport {
	items := make([]*pb.DiscoveredContainer, 0, len(r.Items))
	for _, c := range r.Items {
		d := &pb.DiscoveredContainer{ContainerId: c.ContainerID, ContainerName: c.ContainerName, ImageRef: c.ImageRef, Labels: c.Labels}
		for _, m := range c.Mounts {
			d.Mounts = append(d.Mounts, &pb.DiscoveredMount{Source: m.Source, Destination: m.Destination})
		}
		for _, p := range c.PublishedPorts {
			d.PublishedPorts = append(d.PublishedPorts, &pb.DiscoveredPort{ContainerPort: p.ContainerPort, HostPort: p.HostPort})
		}
		items = append(items, d)
	}
	return &pb.DiscoveryReport{Envelope: envToPB(r.Envelope), Items: items}
}
func discoveryReportFromPB(p *pb.DiscoveryReport) msg.DiscoveryReport {
	items := make([]msg.DiscoveredContainer, 0, len(p.GetItems()))
	for _, c := range p.GetItems() {
		d := msg.DiscoveredContainer{ContainerID: c.GetContainerId(), ContainerName: c.GetContainerName(), ImageRef: c.GetImageRef(), Labels: c.GetLabels()}
		for _, m := range c.GetMounts() {
			d.Mounts = append(d.Mounts, msg.DiscoveredMount{Source: m.GetSource(), Destination: m.GetDestination()})
		}
		for _, q := range c.GetPublishedPorts() {
			d.PublishedPorts = append(d.PublishedPorts, msg.DiscoveredPort{ContainerPort: q.GetContainerPort(), HostPort: q.GetHostPort()})
		}
		items = append(items, d)
	}
	return msg.DiscoveryReport{Envelope: envFromPB(p.GetEnvelope()), Items: items}
}

func discoveryStateToPB(s msg.DiscoveryState) pb.DiscoveryState {
	return enumTo[pb.DiscoveryState](pb.DiscoveryState_value, "DISCOVERY_STATE_", string(s))
}
func discoveryStateFromPB(p pb.DiscoveryState) msg.DiscoveryState {
	if p == pb.DiscoveryState_DISCOVERY_STATE_UNSPECIFIED {
		return ""
	}
	return msg.DiscoveryState(enumFrom(pb.DiscoveryState_name, "DISCOVERY_STATE_", p))
}

func discoveryAckToPB(a msg.DiscoveryAck) *pb.DiscoveryAck {
	rs := make([]*pb.DiscoveryResult, 0, len(a.Results))
	for _, r := range a.Results {
		rs = append(rs, &pb.DiscoveryResult{ContainerId: r.ContainerID, State: discoveryStateToPB(r.State), Code: r.Code, BindingId: r.BindingID})
	}
	return &pb.DiscoveryAck{Envelope: envToPB(a.Envelope), InReplyToMessageId: a.InReplyToMessageID, Results: rs}
}
func discoveryAckFromPB(p *pb.DiscoveryAck) msg.DiscoveryAck {
	rs := make([]msg.DiscoveryResult, 0, len(p.GetResults()))
	for _, r := range p.GetResults() {
		rs = append(rs, msg.DiscoveryResult{ContainerID: r.GetContainerId(), State: discoveryStateFromPB(r.GetState()), Code: r.GetCode(), BindingID: r.GetBindingId()})
	}
	return msg.DiscoveryAck{Envelope: envFromPB(p.GetEnvelope()), InReplyToMessageID: p.GetInReplyToMessageId(), Results: rs}
}

func onUnavailableToPB(o msg.OnUnavailable) pb.OnUnavailable {
	switch o {
	case msg.OnUnavailableDegrade:
		return pb.OnUnavailable_ON_UNAVAILABLE_DEGRADE
	case msg.OnUnavailableError:
		return pb.OnUnavailable_ON_UNAVAILABLE_ERROR
	}
	return pb.OnUnavailable_ON_UNAVAILABLE_UNSPECIFIED
}
func onUnavailableFromPB(p pb.OnUnavailable) msg.OnUnavailable {
	switch p {
	case pb.OnUnavailable_ON_UNAVAILABLE_DEGRADE:
		return msg.OnUnavailableDegrade
	case pb.OnUnavailable_ON_UNAVAILABLE_ERROR:
		return msg.OnUnavailableError
	}
	return ""
}

func probeKindToPB(k msg.ProbeKind) pb.ProbeKind {
	return enumTo[pb.ProbeKind](pb.ProbeKind_value, "PROBE_KIND_", string(k))
}
func probeKindFromPB(p pb.ProbeKind) msg.ProbeKind {
	if p == pb.ProbeKind_PROBE_KIND_UNSPECIFIED {
		return ""
	}
	return msg.ProbeKind(enumFrom(pb.ProbeKind_name, "PROBE_KIND_", p))
}

func declarationsToPB(d msg.TelemetryDeclarations) *pb.TelemetryDeclarations {
	out := &pb.TelemetryDeclarations{Envelope: envToPB(d.Envelope), BindingId: d.BindingID, Interval: dur(d.Interval)}
	for _, m := range d.Metrics {
		tm := &pb.TelemetryMetric{Name: m.Name, MetricType: metricTypeToPB(m.MetricType), Unit: m.Unit}
		if h := m.HTTPJSON; h != nil {
			tm.Source = &pb.TelemetryMetric_HttpJson{HttpJson: &pb.HttpJsonSource{Port: h.Port, Path: h.Path, Pointer: h.Pointer, OnUnavailable: onUnavailableToPB(h.OnUnavailable)}}
		}
		out.Metrics = append(out.Metrics, tm)
	}
	if p := d.HealthProbe; p != nil {
		out.HealthProbe = &pb.HealthProbe{Kind: probeKindToPB(p.Kind), Path: p.Path, HostPort: p.HostPort, Strength: strengthToPB(p.Strength)}
	}
	return out
}
func declarationsFromPB(p *pb.TelemetryDeclarations) msg.TelemetryDeclarations {
	out := msg.TelemetryDeclarations{Envelope: envFromPB(p.GetEnvelope()), BindingID: p.GetBindingId(), Interval: durFrom(p.GetInterval())}
	for _, m := range p.GetMetrics() {
		tm := msg.TelemetryMetric{Name: m.GetName(), MetricType: metricTypeFromPB(m.GetMetricType()), Unit: m.GetUnit()}
		if h := m.GetHttpJson(); h != nil {
			tm.HTTPJSON = &msg.HTTPJSONSource{Port: h.GetPort(), Path: h.GetPath(), Pointer: h.GetPointer(), OnUnavailable: onUnavailableFromPB(h.GetOnUnavailable())}
		}
		out.Metrics = append(out.Metrics, tm)
	}
	if hp := p.GetHealthProbe(); hp != nil {
		out.HealthProbe = &msg.HealthProbe{Kind: probeKindFromPB(hp.GetKind()), Path: hp.GetPath(), HostPort: hp.GetHostPort(), Strength: strengthFromPB(hp.GetStrength())}
	}
	return out
}
