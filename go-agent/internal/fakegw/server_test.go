package fakegw

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

func enroll(t *testing.T, s *Server, token string, csr []byte) (*msg.EnrollResponse, error) {
	t.Helper()
	st, _ := store.Open(t.TempDir())
	id, _ := identity.Load(st)
	tlsCfg, _ := id.EnrollTLS(s.CAPEM())
	cc, err := grpc.NewClient(s.Endpoint(), grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg)))
	if err != nil {
		t.Fatal(err)
	}
	defer cc.Close()
	ctx := metadata.AppendToOutgoingContext(context.Background(), tokenHeader, token)
	resp, err := pb.NewAgentGatewayClient(cc).Enroll(ctx, codec.EnrollRequestToPB(msg.EnrollRequest{CSRDER: csr, AgentVersion: "t"}))
	if err != nil {
		return nil, codec.ErrorFromStatus(err)
	}
	r := codec.EnrollResponseFromPB(resp)
	return &r, nil
}

func TestEnrollReplayAndConsumption(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0", Tokens: []string{"tok"}})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	st, _ := store.Open(t.TempDir())
	id, _ := identity.Load(st)
	csr, _ := id.CSR()

	r1, err := enroll(t, s, "tok", csr)
	if err != nil {
		t.Fatal(err)
	}
	r2, err := enroll(t, s, "tok", csr) // same (token, CSR) → replay
	if err != nil || r2.AgentID != r1.AgentID || string(r2.CertificateChain[0]) != string(r1.CertificateChain[0]) {
		t.Fatalf("replay must return the stored reply: %v", err)
	}
	st2, _ := store.Open(t.TempDir())
	id2, _ := identity.Load(st2)
	csr2, _ := id2.CSR()
	_, err = enroll(t, s, "tok", csr2) // different CSR → consumed
	var pe *msg.ProtocolError
	if !errors.As(err, &pe) || pe.Code != msg.ErrTokenConsumed {
		t.Fatalf("want TOKEN_CONSUMED, got %v", err)
	}
	_, err = enroll(t, s, "nope", csr)
	if !errors.As(err, &pe) || pe.Code != msg.ErrUnauthenticatedIdentity {
		t.Fatalf("want UNAUTHENTICATED_IDENTITY, got %v", err)
	}
	if s.Count("EnrollRequest") != 4 {
		t.Fatalf("transcript count %d", s.Count("EnrollRequest"))
	}
}

func TestScenarioLoads(t *testing.T) {
	sc, err := LoadScenario("scenarios/demo.yaml")
	if err != nil {
		t.Fatal(err)
	}
	if len(sc.Steps) == 0 || sc.Lease.TargetID == "" {
		t.Fatalf("%+v", sc)
	}
}

// nopStream is a minimal pb.AgentGateway_ConnectServer stub: only Send is
// exercised by session.send in the tests below, so only Send is overridden;
// any other method resolves to the embedded nil interface and would panic if
// called, which is intentional (it would mean a test is exercising more of
// the stream than it should).
type nopStream struct {
	pb.AgentGateway_ConnectServer
}

func (nopStream) Send(*pb.GatewayToAgent) error { return nil }

// TestSessionCommandsAndPolicyConcurrentAccess drives the scripted step
// runner (runStep, as run()'s steps goroutine does) and the auto-responder
// (respond, as run()'s main select loop does) concurrently against the same
// session, the same way a live Connect stream does once the handshake
// completes. In a real deployment the only thing tying an offer (runStep) to
// the agent's resulting CommandPull/CommandReceived/CommandAck (respond) is a
// network round trip through the agent, and the Go memory model does not
// treat socket I/O as a happens-before edge — so ss.commands and ss.policy
// need their own synchronization independent of that round trip. This test
// reproduces the two goroutines' access pattern directly (no real stream
// needed) so -race can verify it.
func TestSessionCommandsAndPolicyConcurrentAccess(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()

	ag := &enrolledAgent{agentID: "agent-x", thumbprint: "thumb-x", renewIDs: map[string]msg.LeaseGrant{}}
	ss := &session{
		s: s, ag: ag, stream: nopStream{}, sc: s.cfg.Scenario, policy: s.cfg.Scenario.Policy,
		commands: map[string]*cmdState{},
	}

	const n = 200
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for i := 0; i < n; i++ {
			id := fmt.Sprintf("cmd-%d", i)
			ss.runStep(Step{Offer: &OfferStep{CommandID: id, BindingID: "binding-1", Generation: 1, Operation: "preflight"}})
			ss.runStep(Step{HeartbeatPolicy: &HeartbeatPolicy{OffsetMs: int64(i), State: "OK"}})
			ss.runStep(Step{ReportPolicy: &ReportPolicy{Mode: "auto"}})
			if i > 0 {
				prev := fmt.Sprintf("cmd-%d", i-1)
				ss.runStep(Step{Superseded: &prev})
			}
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < n; i++ {
			id := fmt.Sprintf("cmd-%d", i)
			ss.respond(msg.CommandPull{Envelope: msg.Envelope{MessageID: "p" + id}, CommandID: id})
			ss.respond(msg.CommandReceived{Envelope: msg.Envelope{MessageID: "r" + id}, CommandID: id})
			ss.respond(msg.CommandAck{Envelope: msg.Envelope{MessageID: "a" + id}, CommandID: id, Outcome: msg.OutcomeApplied})
			ss.respond(msg.Heartbeat{Envelope: msg.Envelope{MessageID: "h" + id}})
		}
	}()
	wg.Wait()
}
