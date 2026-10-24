package agent

import (
	"reflect"
	"slices"
	"strings"
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

func TestParseRolesAcceptsLogs(t *testing.T) {
	for in, want := range map[string]Roles{
		"logs":                   {Logs: true},
		"control,logs":           {Control: true, Logs: true},
		"telemetry, logs":        {Telemetry: true, Logs: true},
		"control,telemetry,logs": {Control: true, Telemetry: true, Logs: true},
	} {
		got, err := ParseRoles(in)
		if err != nil || got != want {
			t.Errorf("%q → %+v %v, want %+v", in, got, err, want)
		}
	}
	_, err := ParseRoles("billing")
	if err == nil || !strings.Contains(err.Error(), "want control, telemetry, logs") {
		t.Fatalf("the refusal must name every role: %v", err)
	}
}

func TestLogsRoleAdvertisesCapLogs(t *testing.T) {
	control := []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle, msg.CapRematerialize, msg.CapDiscovery, msg.CapDeclarations}
	for r, want := range map[Roles][]msg.Capability{
		{Control: true, Logs: true}:                  append(slices.Clone(control), msg.CapLogs),
		{Control: true, Telemetry: true, Logs: true}: append(slices.Clone(control), msg.CapLogs),
		// A logs-only agent is report-only, as a telemetry-only one is: it
		// executes nothing on its lease.
		{Logs: true}:                  {msg.CapReportOnly, msg.CapDeclarations, msg.CapLogs},
		{Telemetry: true, Logs: true}: {msg.CapReportOnly, msg.CapDeclarations, msg.CapLogs},
	} {
		if got := r.capabilities(); !reflect.DeepEqual(got, want) {
			t.Errorf("%+v → %v, want %v", r, got, want)
		}
	}
	for _, r := range []Roles{{}, {Control: true}, {Telemetry: true}, {Control: true, Telemetry: true}} {
		if slices.Contains(r.capabilities(), msg.CapLogs) {
			t.Errorf("%+v advertises logs without the role", r)
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

// Plan 017: CapConfigView is advertised exactly when the config-view role is
// present, on top of whatever the roles advertise, and never otherwise.
func TestConfigViewCapabilityFollowsTheRole(t *testing.T) {
	for _, r := range []Roles{{}, {Control: true}, {Telemetry: true}, {Logs: true}, {Control: true, Telemetry: true, Logs: true}} {
		if got, want := sessionCapabilities(r, true), append(slices.Clone(r.capabilities()), msg.CapConfigView); !reflect.DeepEqual(got, want) {
			t.Errorf("%+v with the role → %v, want %v", r, got, want)
		}
		if got := sessionCapabilities(r, false); slices.Contains(got, msg.CapConfigView) || !reflect.DeepEqual(got, r.capabilities()) {
			t.Errorf("%+v without the role → %v, want %v", r, got, r.capabilities())
		}
	}
}
