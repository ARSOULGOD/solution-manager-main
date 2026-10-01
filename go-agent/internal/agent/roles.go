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
// the slice-1 default: control.
type Roles struct{ Control, Telemetry bool }

func ParseRoles(s string) (Roles, error) {
	var r Roles
	for _, p := range strings.Split(s, ",") {
		switch strings.TrimSpace(p) {
		case "control":
			r.Control = true
		case "telemetry":
			r.Telemetry = true
		default:
			return Roles{}, fmt.Errorf("unknown role %q (want control, telemetry)", strings.TrimSpace(p))
		}
	}
	return r, nil
}

// capabilities is what SessionOpen advertises. A telemetry-only agent still
// runs the session and reports — a TRUSTED report is what mints the ids its
// samples carry — but executes nothing (AG-11 stays open on its lease).
func (r Roles) capabilities() []msg.Capability {
	if r.Control || !r.Telemetry {
		return []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}
	}
	return []msg.Capability{msg.CapReportOnly}
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
	return g.State.RefusalDetail(op, bindingID, dpNow)
}
