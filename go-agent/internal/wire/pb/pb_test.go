package pb

import (
	"testing"

	"google.golang.org/protobuf/proto"
)

func TestOfferRoundTrip(t *testing.T) {
	// protoc-gen-go does not upper-case acronyms: command_id → CommandId.
	in := &GatewayToAgent{Msg: &GatewayToAgent_CommandOffer{CommandOffer: &CommandOffer{
		Envelope:     &Envelope{MessageId: "m1"},
		CommandId:    "c1",
		Generation:   7,
		Operation:    Operation_OPERATION_APPLY,
		PayloadBytes: []byte("server.port: 8080\n"),
		ConfigSha256: "abc",
	}}}
	b, err := proto.Marshal(in)
	if err != nil {
		t.Fatal(err)
	}
	var out GatewayToAgent
	if err := proto.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	got := out.GetCommandOffer()
	if got.GetCommandId() != "c1" || got.GetGeneration() != 7 || got.GetOperation() != Operation_OPERATION_APPLY {
		t.Fatalf("round trip lost fields: %+v", got)
	}
}
