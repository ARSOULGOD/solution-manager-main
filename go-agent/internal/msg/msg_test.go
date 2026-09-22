package msg

import "testing"

func TestCatalogueDirections(t *testing.T) {
	var out []Outbound = []Outbound{
		SessionOpen{}, LeaseRenew{}, CommandPull{}, CommandReceived{}, CommandAck{},
		ApplyProgress{}, ActualStateReportBatch{}, Heartbeat{}, Teardown{},
	}
	var in []Inbound = []Inbound{
		SessionAccept{}, SessionReject{}, LeaseGrant{}, Revoke{}, GenerationAvailable{},
		CommandOffer{}, ReportAck{}, HeartbeatAck{}, TrustBundleUpdate{}, Drain{},
		Teardown{}, Error{},
	}
	if len(out) != 9 || len(in) != 12 {
		t.Fatalf("catalogue changed: out=%d in=%d", len(out), len(in))
	}
}

func TestOperationCapability(t *testing.T) {
	cases := map[Operation]Capability{
		OpPreflight: CapApplyConfig, OpApply: CapApplyConfig,
		OpRestart: CapLifecycle, OpStop: CapLifecycle,
	}
	for op, want := range cases {
		if got := op.RequiredCapability(); got != want {
			t.Errorf("%s: got %s want %s", op, got, want)
		}
	}
}
