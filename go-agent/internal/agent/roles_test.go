package agent

import (
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestParseRoles(t *testing.T) {
	for in, want := range map[string]Roles{
		"control":             {Control: true},
		"telemetry":           {Telemetry: true},
		"control,telemetry":   {Control: true, Telemetry: true},
		" telemetry ,control": {Control: true, Telemetry: true},
	} {
		got, err := ParseRoles(in)
		if err != nil || got != want {
			t.Errorf("%q → %+v %v, want %+v", in, got, err, want)
		}
	}
	if _, err := ParseRoles("control,billing"); err == nil {
		t.Error("an unknown role must be refused")
	}
}

func TestCapabilitiesFollowTheRoles(t *testing.T) {
	// plan 015 T2.3 appends the feature capabilities: rematerialize, discovery and
	// declarations (never lease capabilities).
	both := []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle, msg.CapRematerialize, msg.CapDiscovery, msg.CapDeclarations}
	for r, want := range map[Roles][]msg.Capability{
		{}:                               both, // a zero Roles is the slice-1 default
		{Control: true}:                  both,
		{Control: true, Telemetry: true}: both,
		{Telemetry: true}:                {msg.CapReportOnly, msg.CapDeclarations},
	} {
		if got := r.capabilities(); !reflect.DeepEqual(got, want) {
			t.Errorf("%+v → %v, want %v", r, got, want)
		}
	}
}

// Deviation 1: the lease grants lifecycle, but this agent did not advertise it.
func TestGateRefusesWhatTheAgentDidNotAdvertise(t *testing.T) {
	g := msg.LeaseGrant{Capabilities: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}, BindingIDs: []string{"b"}, ExpiresAt: time.Now().Add(time.Hour)}
	a := authority.State{Phase: authority.PhaseActive, Grant: &g}
	if got := (gate{State: a, supported: []msg.Capability{msg.CapReportOnly}}).RefusalDetail(msg.OpRestart, "b", time.Now()); got != msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatalf("got %q", got)
	}
	if got := (gate{State: a, supported: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}}).RefusalDetail(msg.OpRestart, "b", time.Now()); got == msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatal("an advertised capability must reach the lease checks")
	}
}
