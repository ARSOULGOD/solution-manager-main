package msg

import (
	"bytes"
	"fmt"
	"log/slog"
	"strconv"
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
// decimalBytes renders b the way Go's default formatting renders a []byte
// struct field — "[112 97 115 …]". That, not the ASCII text, is the shape a
// real payload leak takes, so it is what the negative assertions below look
// for.
func decimalBytes(b []byte) string {
	parts := make([]string, len(b))
	for i, c := range b {
		parts[i] = strconv.Itoa(int(c))
	}
	return "[" + strings.Join(parts, " ") + "]"
}

func TestCommandOfferRedactsPayloadFromLogsAndFmt(t *testing.T) {
	const secret = "super-secret-password-xyz"
	payload := []byte("password: " + secret)
	offer := CommandOffer{CommandID: "c1", BindingID: "b1", Operation: OpApply,
		Payload: payload, ConfigSHA256: "deadbeef"}

	// Asserting only "the ASCII secret is absent" would pass with the
	// redaction removed, because Go renders a []byte field as decimal codes,
	// never as text. So assert positively that the redacted marker IS present
	// — that can only hold when LogValue/String actually ran — and negatively
	// against the decimal rendering, which is the real leak shape.
	wantMarker := fmt.Sprintf("payload_len=%d", len(payload))
	rawBytes := decimalBytes(payload)

	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, nil))
	log.Info("offer", "offer", offer)
	got := buf.String()
	if !strings.Contains(got, wantMarker) {
		t.Fatalf("slog output lacks %q, so LogValue did not run: %s", wantMarker, got)
	}
	if strings.Contains(got, secret) || strings.Contains(got, rawBytes) {
		t.Fatalf("payload leaked via slog: %s", got)
	}

	for _, got := range []string{fmt.Sprintf("%v", offer), fmt.Sprintf("%+v", offer), fmt.Sprint(offer)} {
		if !strings.Contains(got, wantMarker) {
			t.Fatalf("fmt output lacks %q, so String did not run: %s", wantMarker, got)
		}
		if strings.Contains(got, secret) || strings.Contains(got, rawBytes) {
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

	// Token is a plain string, so the negative assertion alone does have teeth
	// here — but assert the marker too, so this test fails the same way as its
	// CommandOffer sibling if redaction is ever removed. The two paths emit
	// different markers: LogValue a token_present bool, String a literal.
	const slogMarker = "token_present=true"
	const fmtMarker = "token=[REDACTED]"
	csrBytes := decimalBytes(req.CSRDER)

	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, nil))
	log.Info("enroll", "req", req)
	got := buf.String()
	if !strings.Contains(got, slogMarker) {
		t.Fatalf("slog output lacks %q, so LogValue did not run: %s", slogMarker, got)
	}
	if strings.Contains(got, token) || strings.Contains(got, csrBytes) {
		t.Fatalf("token or CSR leaked via slog: %s", got)
	}

	for _, got := range []string{fmt.Sprintf("%v", req), fmt.Sprintf("%+v", req), fmt.Sprint(req)} {
		if !strings.Contains(got, fmtMarker) {
			t.Fatalf("fmt output lacks %q, so String did not run: %s", fmtMarker, got)
		}
		if strings.Contains(got, token) || strings.Contains(got, csrBytes) {
			t.Fatalf("token or CSR leaked via fmt: %s", got)
		}
	}
}

// Plan 015 S2, Global constraint 2 and 9 (AG-D29 item 4): neither the payload's
// digest nor the REMATERIALIZE precondition digest ever reaches a log line or a
// fmt verb. A plain digest of a payload that can hold secrets lets a log reader
// test guessed secrets.
func TestCommandOfferNeverLogsADigest(t *testing.T) {
	const digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
	const expected = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752"
	offer := CommandOffer{CommandID: "c1", BindingID: "b1", Operation: OpRematerialize, Payload: []byte("x"),
		ConfigSHA256: digest, ExpectedCurrentSHA256: func() *string { s := expected; return &s }()}
	var buf bytes.Buffer
	slog.New(slog.NewTextHandler(&buf, nil)).Info("offer", "offer", offer)
	slog.New(slog.NewJSONHandler(&buf, nil)).Info("offer", "offer", offer)
	for _, out := range []string{buf.String(), fmt.Sprintf("%v", offer), fmt.Sprintf("%+v", offer), offer.String()} {
		for _, d := range []string{digest, expected, "config_sha256", "expected_current"} {
			if strings.Contains(out, d) {
				t.Fatalf("%q leaked into %s", d, out)
			}
		}
	}
	if !strings.Contains(buf.String(), "payload_len=1") {
		t.Fatalf("LogValue did not run: %s", buf.String())
	}
}

func TestRematerializeIsAnApplyConfigOperation(t *testing.T) {
	if OpRematerialize.RequiredCapability() != CapApplyConfig {
		t.Fatal("OPERATION_REMATERIALIZE is an apply-config operation (lease authority is unchanged)")
	}
}
