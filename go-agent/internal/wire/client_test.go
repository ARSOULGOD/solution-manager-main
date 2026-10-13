package wire

import (
	"context"
	"io"
	"net"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

// slowReader is a gateway that is busy for a moment before it reads its inbound
// stream: the situation in which a client that ends the stream by cancelling
// loses the message it has just sent (plan 015 Final, W5 review finding 8).
type slowReader struct {
	pb.UnimplementedAgentGatewayServer
	got  chan string
	busy time.Duration
}

func (s *slowReader) Connect(stream pb.AgentGateway_ConnectServer) error {
	time.Sleep(s.busy)
	for {
		in, err := stream.Recv()
		if err == io.EOF {
			s.got <- "EOF"
			return nil
		}
		if err != nil {
			s.got <- "ERR:" + err.Error()
			return err
		}
		if in.GetDrainComplete() != nil {
			s.got <- "DrainComplete"
		}
	}
}

func TestCloseHalfClosesSoTheGatewayStillReadsTheLastMessage(t *testing.T) {
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := grpc.NewServer()
	gw := &slowReader{got: make(chan string, 4), busy: 300 * time.Millisecond}
	pb.RegisterAgentGatewayServer(srv, gw)
	go func() { _ = srv.Serve(lis) }()
	defer srv.Stop()

	cc, err := grpc.NewClient(lis.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	sctx, cancel := context.WithCancel(context.Background())
	stream, err := pb.NewAgentGatewayClient(cc).Connect(sctx)
	if err != nil {
		t.Fatal(err)
	}
	c := &Conn{cc: cc, stream: stream, cancel: cancel, ended: make(chan struct{})}
	go func() { // the agent loop's own reader: the one goroutine that calls Recv
		for {
			if _, err := c.Recv(); err != nil {
				return
			}
		}
	}()

	if err := c.Send(msg.DrainComplete{Envelope: msg.Envelope{MessageID: "m1"}, InReplyTo: "d1"}); err != nil {
		t.Fatal(err)
	}
	_ = c.Close()

	var seen []string
	deadline := time.After(5 * time.Second)
	for len(seen) < 2 {
		select {
		case s := <-gw.got:
			seen = append(seen, s)
		case <-deadline:
			t.Fatalf("the gateway did not finish reading; saw %v", seen)
		}
	}
	if seen[0] != "DrainComplete" || seen[1] != "EOF" {
		t.Fatalf("the gateway must read DrainComplete and then the half-close; saw %v", seen)
	}
}

func TestCloseDoesNotWaitForeverOnAGatewayThatNeverEndsTheStream(t *testing.T) {
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := grpc.NewServer()
	hold := make(chan struct{})
	pb.RegisterAgentGatewayServer(srv, &holdOpen{hold: hold})
	go func() { _ = srv.Serve(lis) }()
	defer srv.Stop()
	defer close(hold)

	cc, err := grpc.NewClient(lis.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	sctx, cancel := context.WithCancel(context.Background())
	stream, err := pb.NewAgentGatewayClient(cc).Connect(sctx)
	if err != nil {
		t.Fatal(err)
	}
	c := &Conn{cc: cc, stream: stream, cancel: cancel, ended: make(chan struct{})}
	go func() {
		for {
			if _, err := c.Recv(); err != nil {
				return
			}
		}
	}()
	start := time.Now()
	_ = c.Close()
	if d := time.Since(start); d > closeGrace+2*time.Second {
		t.Fatalf("Close took %v, more than its bound %v", d, closeGrace)
	}
}

type holdOpen struct {
	pb.UnimplementedAgentGatewayServer
	hold chan struct{}
}

func (h *holdOpen) Connect(stream pb.AgentGateway_ConnectServer) error {
	<-h.hold
	return nil
}
