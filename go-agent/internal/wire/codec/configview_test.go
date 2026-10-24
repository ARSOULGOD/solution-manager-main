package codec

import (
	"reflect"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

func TestConfigViewRoundTrip(t *testing.T) {
	req := msg.ConfigViewRequest{RequestID: "r1", PlacementID: "pl1", Allow: []string{"server.port", "cache.*"}, MaxBytes: 65536}
	gp, err := GatewayToPB(req)
	if err != nil {
		t.Fatal(err)
	}
	gout, err := GatewayFromPB(gp)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(gout, req) {
		t.Fatalf("request: got %+v want %+v", gout, req)
	}

	rep := msg.ConfigViewReply{RequestID: "r1", Status: msg.ConfigViewOK,
		Entries:     []msg.ConfigEntry{{Path: "server.port", Value: "8080"}, {Path: "cache.size", Value: "10"}},
		HiddenCount: 3, Truncated: true}
	ap, err := AgentToPB(rep)
	if err != nil {
		t.Fatal(err)
	}
	aout, err := AgentFromPB(ap)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(aout, rep) {
		t.Fatalf("reply: got %+v want %+v", aout, rep)
	}

	ref := msg.ConfigViewReply{RequestID: "r2", Status: msg.ConfigViewRefused, Reason: msg.ConfigViewFileMissing}
	ap, err = AgentToPB(ref)
	if err != nil {
		t.Fatal(err)
	}
	aout, err = AgentFromPB(ap)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(aout, ref) {
		t.Fatalf("refused: got %+v want %+v", aout, ref)
	}
}

func TestConfigViewReasonsAreClosed(t *testing.T) {
	reasons := []msg.ConfigViewReason{msg.ConfigViewPlacementNotInLease, msg.ConfigViewFileMissing, msg.ConfigViewFileUnreadable,
		msg.ConfigViewFileTooLarge, msg.ConfigViewInvalidYAML, msg.ConfigViewNotPermitted, msg.ConfigViewTooManyKeys}
	seen := map[msg.ConfigViewReason]bool{}
	wire := map[int32]bool{}
	for _, r := range reasons {
		if seen[r] || r == "" {
			t.Fatalf("reason %q duplicated or empty", r)
		}
		seen[r] = true
		p, err := configViewReasonToPB(r)
		if err != nil || wire[int32(p)] || p == 0 {
			t.Fatalf("reason %q: pb=%v err=%v", r, p, err)
		}
		wire[int32(p)] = true
		back, err := configViewReasonFromPB(p)
		if err != nil || back != r {
			t.Fatalf("reason %q round trip: %q %v", r, back, err)
		}
	}
	if _, err := configViewReasonToPB("BOGUS"); err == nil {
		t.Fatal("unknown reason must error")
	}
	if _, err := configViewReasonFromPB(99); err == nil {
		t.Fatal("unknown pb reason must error")
	}
	if _, err := configViewStatusToPB("BOGUS"); err == nil {
		t.Fatal("unknown status must error")
	}
	if _, err := configViewStatusFromPB(99); err == nil {
		t.Fatal("unknown pb status must error")
	}
}

func TestCapConfigViewMapsToWire(t *testing.T) {
	if capToPB(msg.CapConfigView) != pb.Capability_CAPABILITY_CONFIG_VIEW || capFromPB(pb.Capability_CAPABILITY_CONFIG_VIEW) != msg.CapConfigView {
		t.Fatalf("config-view must round-trip to CAPABILITY_CONFIG_VIEW: %v / %q", capToPB(msg.CapConfigView), capFromPB(pb.Capability_CAPABILITY_CONFIG_VIEW))
	}
}
