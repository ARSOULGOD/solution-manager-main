package agent

import (
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// Roles are the agent's enabled roles (AG-D1; spec §2.3). The zero value is
// the slice-1 default: control. Logs (plan 016) collects the placements' MI
// container logs and serves them on request.
type Roles struct{ Control, Telemetry, Logs bool }

func ParseRoles(s string) (Roles, error) {
	var r Roles
	for _, p := range strings.Split(s, ",") {
		switch strings.TrimSpace(p) {
		case "control":
			r.Control = true
		case "telemetry":
			r.Telemetry = true
		case "logs":
			r.Logs = true
		default:
			return Roles{}, fmt.Errorf("unknown role %q (want control, telemetry, logs)", strings.TrimSpace(p))
		}
	}
	return r, nil
}

// capabilities is what SessionOpen advertises. A telemetry-only agent still
// runs the session and reports — a TRUSTED report is what mints the ids its
// samples carry — but executes nothing (AG-11 stays open on its lease). A
// logs-only agent is treated exactly like a telemetry-only one: report-only,
// executing nothing on its lease. CapLogs is appended whenever the logs role
// is on; the gateway sends LogRetention and LogRequest only to a session that
// names it.
func (r Roles) capabilities() []msg.Capability {
	// The last three of a control agent are plan 015 T2.3's feature
	// capabilities: the gateway sends DiscoveryAck, TelemetryDeclarations and
	// OPERATION_REMATERIALIZE only to a session that names them. A report-only agent
	// still takes declarations (its collectors and health probe).
	var caps []msg.Capability
	if r.Control || (!r.Telemetry && !r.Logs) {
		caps = []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle, msg.CapRematerialize, msg.CapDiscovery, msg.CapDeclarations}
	} else {
		caps = []msg.Capability{msg.CapReportOnly, msg.CapDeclarations}
	}
	if r.Logs {
		caps = append(caps, msg.CapLogs)
	}
	return caps
}

// gate refuses an operation whose capability this agent did not advertise
// before the lease is consulted: a lease that grants more than the agent
// supports must not widen what it executes.
type gate struct {
	authority.State
	supported []msg.Capability
}

func (g gate) RefusalDetail(op msg.Operation, bindingID string, dpNow time.Time) msg.DetailCode {
	if !slices.Contains(g.supported, op.RequiredCapability()) {
		return msg.DetailOf(msg.ErrCapabilityMissing)
	}
	// OPERATION_REMATERIALIZE is an apply-config operation that an agent built
	// before the precondition field must refuse (R-7): it is named by its own
	// feature capability.
	if op == msg.OpRematerialize && !slices.Contains(g.supported, msg.CapRematerialize) {
		return msg.DetailOf(msg.ErrCapabilityMissing)
	}
	return g.State.RefusalDetail(op, bindingID, dpNow)
}
