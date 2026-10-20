package codec

import (
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

func TestLogRequestPageRoundTrip(t *testing.T) {
	after, before := now.Add(-time.Hour), now
	in := msg.LogRequest{Envelope: msg.Envelope{MessageID: "m", SentAt: now}, RequestID: "r1", PlacementRef: "p1",
		Page: &msg.LogPageQuery{Limit: 200, After: &after, Before: &before}}
	p, err := GatewayToPB(in)
	if err != nil {
		t.Fatal(err)
	}
	out, err := GatewayFromPB(p)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(out, in) {
		t.Fatalf("got %+v want %+v", out, in)
	}
}

func TestLogRequestExportRoundTrip(t *testing.T) {
	in := msg.LogRequest{Envelope: msg.Envelope{MessageID: "m", SentAt: now}, RequestID: "r2", PlacementRef: "p1",
		Export: &msg.LogExportQuery{FromSeq: 42}}
	p, err := GatewayToPB(in)
	if err != nil {
		t.Fatal(err)
	}
	out, err := GatewayFromPB(p)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(out, in) {
		t.Fatalf("got %+v want %+v", out, in)
	}
}

func TestLogRetentionDecode(t *testing.T) {
	in := msg.LogRetention{Envelope: msg.Envelope{MessageID: "m", SentAt: now}, PlacementRef: "p1", MaxBytes: 1 << 20}
	p, err := GatewayToPB(in)
	if err != nil {
		t.Fatal(err)
	}
	if p.GetLogRetention().GetMaxBytes() != 1<<20 {
		t.Fatalf("wire: %+v", p)
	}
	out, err := GatewayFromPB(p)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(out, in) {
		t.Fatalf("got %+v want %+v", out, in)
	}
}

func TestCapLogsMapsToWire(t *testing.T) {
	if capToPB(msg.CapLogs) != pb.Capability_CAPABILITY_LOGS || capFromPB(pb.Capability_CAPABILITY_LOGS) != msg.CapLogs {
		t.Fatal("logs capability does not round-trip")
	}
}

func logFixtures() ([]msg.LogLine, []msg.LogGap) {
	return []msg.LogLine{
		{TS: now, Stream: "stdout", Text: []byte("hello\n")},
		{TS: now.Add(time.Second), Stream: "stderr", Text: []byte{0xff, 0x00, 'x'}, Truncated: true},
	}, []msg.LogGap{
		{From: now.Add(-time.Minute), To: now, Reason: "DROPPED_OLDEST"},
	}
}

func TestLogPageEncodeKeepsGapsAndUnavailable(t *testing.T) {
	lines, gaps := logFixtures()
	in := msg.LogPage{Envelope: msg.Envelope{MessageID: "m", SentAt: now}, RequestID: "r1", Lines: lines, Gaps: gaps, Unavailable: msg.UnavailableBusy}
	p, err := EncodeLogUp(in)
	if err != nil {
		t.Fatal(err)
	}
	if p.GetLogPage().GetUnavailable() != pb.UnavailableReason_UNAVAILABLE_REASON_BUSY {
		t.Fatalf("unavailable not on the wire: %+v", p)
	}
	if got := len(p.GetLogPage().GetGaps()); got != 1 {
		t.Fatalf("gaps = %d", got)
	}
	out, err := logUpFromPB(p)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(out, in) {
		t.Fatalf("got %+v want %+v", out, in)
	}
	none := msg.LogPage{RequestID: "r"}
	p, _ = EncodeLogUp(none)
	if p.GetLogPage().GetUnavailable() != pb.UnavailableReason_UNAVAILABLE_REASON_UNSPECIFIED {
		t.Fatal("empty reason must be NONE")
	}
}

func TestLogChunkFinalFlag(t *testing.T) {
	lines, gaps := logFixtures()
	for _, final := range []bool{false, true} {
		in := msg.LogChunk{Envelope: msg.Envelope{MessageID: "m", SentAt: now}, RequestID: "r2", Seq: 7, Lines: lines, Gaps: gaps, Final: final}
		p, err := EncodeLogUp(in)
		if err != nil {
			t.Fatal(err)
		}
		if p.GetLogChunk().GetFinal() != final {
			t.Fatalf("final = %v", p.GetLogChunk().GetFinal())
		}
		out, err := logUpFromPB(p)
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(out, in) {
			t.Fatalf("got %+v want %+v", out, in)
		}
	}
}

func TestLogDownDecode(t *testing.T) {
	c, err := DecodeLogDown(&pb.LogDown{Msg: &pb.LogDown_LogCredit{LogCredit: &pb.LogCredit{RequestId: "r", CreditsGranted: 3}}})
	if err != nil || !reflect.DeepEqual(c, msg.LogCredit{RequestID: "r", CreditsGranted: 3}) {
		t.Fatalf("credit: %v %v", c, err)
	}
	c, err = DecodeLogDown(&pb.LogDown{Msg: &pb.LogDown_LogCancel{LogCancel: &pb.LogCancel{RequestId: "r"}}})
	if err != nil || !reflect.DeepEqual(c, msg.LogCancel{RequestID: "r"}) {
		t.Fatalf("cancel: %v %v", c, err)
	}
}

func TestUnknownLogsDownMessageIsAnError(t *testing.T) {
	if _, err := DecodeLogDown(&pb.LogDown{}); err == nil {
		t.Fatal("empty LogDown must be an error")
	}
	if _, err := DecodeLogDown(nil); err == nil {
		t.Fatal("nil LogDown must be an error")
	}
	if _, err := EncodeLogUp(nil); err == nil {
		t.Fatal("nil outbound must be an error")
	}
}

func TestUnavailableReasonRoundTripAll(t *testing.T) {
	for _, r := range []msg.UnavailableReason{msg.UnavailableNone, msg.UnavailableNotCollecting,
		msg.UnavailableContainerMissing, msg.UnavailableBusy, msg.UnavailablePlacementNotLease} {
		in := msg.LogPage{Envelope: msg.Envelope{MessageID: "m", SentAt: now}, RequestID: "r", Unavailable: r}
		p, err := EncodeLogUp(in)
		if err != nil {
			t.Fatalf("%q: %v", r, err)
		}
		out, err := DecodeLogUp(p)
		if err != nil {
			t.Fatalf("%q: %v", r, err)
		}
		if !reflect.DeepEqual(out, in) {
			t.Fatalf("%q: got %+v", r, out)
		}
	}
}

func TestUnknownUnavailableReasonIsAnError(t *testing.T) {
	if _, err := EncodeLogUp(msg.LogPage{RequestID: "r", Unavailable: "BOGUS"}); err == nil {
		t.Fatal("encode of unknown reason must fail")
	}
	p := &pb.LogUp{Msg: &pb.LogUp_LogPage{LogPage: &pb.LogPage{RequestId: "r", Unavailable: pb.UnavailableReason(99)}}}
	if _, err := DecodeLogUp(p); err == nil {
		t.Fatal("decode of unknown pb reason must fail")
	}
}

func TestLogUpChunkRoundTrip(t *testing.T) {
	in := msg.LogChunk{Envelope: msg.Envelope{MessageID: "m", SentAt: now}, RequestID: "r", Seq: 7,
		Lines: []msg.LogLine{{TS: now, Stream: "stderr", Text: []byte("x"), Truncated: true}},
		Gaps:  []msg.LogGap{{From: now, To: now.Add(time.Second), Reason: "CAP_REDUCED"}}, Final: true}
	p, err := EncodeLogUp(in)
	if err != nil {
		t.Fatal(err)
	}
	out, err := DecodeLogUp(p)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(out, in) {
		t.Fatalf("got %+v want %+v", out, in)
	}
	if _, err := DecodeLogUp(&pb.LogUp{}); err == nil {
		t.Fatal("empty LogUp must fail")
	}
}

func TestLogDownRoundTrip(t *testing.T) {
	for _, in := range []msg.LogsInbound{msg.LogCredit{RequestID: "r", CreditsGranted: 5}, msg.LogCancel{RequestID: "r"}} {
		p, err := EncodeLogDown(in)
		if err != nil {
			t.Fatal(err)
		}
		out, err := DecodeLogDown(p)
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(out, in) {
			t.Fatalf("got %+v want %+v", out, in)
		}
	}
	if _, err := DecodeLogDown(&pb.LogDown{}); err == nil {
		t.Fatal("empty LogDown must fail")
	}
}
