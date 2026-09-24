package msg

import (
	"bytes"
	"fmt"
	"log/slog"
	"strings"
	"testing"
)

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

// TestCommandOfferRedactsPayloadFromLogsAndFmt is the Question-C guarantee:
// a payload can carry a secretRef's resolved value or other embedded
// credentials, so neither slog output (via LogValuer) nor a bare
// fmt.Sprintf("%v"/"%+v") of a CommandOffer (via Stringer, which fmt also
// consults for %+v) may ever show its bytes — only its length.
func TestCommandOfferRedactsPayloadFromLogsAndFmt(t *testing.T) {
	const secret = "super-secret-password-xyz"
	offer := CommandOffer{CommandID: "c1", BindingID: "b1", Operation: OpApply,
		Payload: []byte("password: " + secret), ConfigSHA256: "deadbeef"}

	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, nil))
	log.Info("offer", "offer", offer)
	if strings.Contains(buf.String(), secret) {
		t.Fatalf("payload leaked via slog: %s", buf.String())
	}

	for _, got := range []string{fmt.Sprintf("%v", offer), fmt.Sprintf("%+v", offer), fmt.Sprint(offer)} {
		if strings.Contains(got, secret) {
			t.Fatalf("payload leaked via fmt: %s", got)
		}
	}
}

// TestEnrollRequestRedactsTokenFromLogsAndFmt mirrors the above for the
// enrollment token (Token's own comment already says "never logged" — this
// makes that a checked property rather than an audit).
func TestEnrollRequestRedactsTokenFromLogsAndFmt(t *testing.T) {
	const token = "enroll-tok-abc123-distinctive"
	req := EnrollRequest{Token: token, AgentVersion: "1.0", CSRDER: []byte("csr-der-bytes"),
		HostFacts: HostFacts{OS: "linux", Arch: "amd64", RuntimeKind: "docker"}}

	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, nil))
	log.Info("enroll", "req", req)
	if strings.Contains(buf.String(), token) {
		t.Fatalf("token leaked via slog: %s", buf.String())
	}

	for _, got := range []string{fmt.Sprintf("%v", req), fmt.Sprintf("%+v", req), fmt.Sprint(req)} {
		if strings.Contains(got, token) {
			t.Fatalf("token leaked via fmt: %s", got)
		}
	}
}
