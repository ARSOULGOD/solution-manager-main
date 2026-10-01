// Package wire is the gRPC/mTLS binding of the DRAFT agent-gateway protocol.
// It and codec are the only packages that import pb.
package wire

import (
	"context"
	"fmt"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/metadata"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

const MetadataTokenKey = "x-sm-enrollment-token"

const maxRecvBytes = 16 << 20 // conservative; Limits.max_message_bytes is enforced in reducers

type Client struct {
	id        *identity.Identity
	bootstrap []byte
}

func New(id *identity.Identity, bootstrapBundlePEM []byte) *Client {
	return &Client{id: id, bootstrap: bootstrapBundlePEM}
}

// Enroll is the one cert-less RPC: a short-lived connection verified by the
// install-time bundle, the token as metadata, closed after the reply.
func (c *Client) Enroll(ctx context.Context, endpoint string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	tlsCfg, err := c.id.EnrollTLS(c.bootstrap)
	if err != nil {
		return nil, err
	}
	cc, err := grpc.NewClient(endpoint, grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg)))
	if err != nil {
		return nil, err
	}
	defer cc.Close()
	ctx = metadata.AppendToOutgoingContext(ctx, MetadataTokenKey, req.Token)
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	resp, err := pb.NewAgentGatewayClient(cc).Enroll(ctx, codec.EnrollRequestToPB(req))
	if err != nil {
		return nil, codec.ErrorFromStatus(err)
	}
	r := codec.EnrollResponseFromPB(resp)
	return &r, nil
}

// Conn is one Connect stream over one mTLS connection. It satisfies
// agent.Conn without importing agent.
type Conn struct {
	cc     *grpc.ClientConn
	stream pb.AgentGateway_ConnectClient
	cancel context.CancelFunc
}

// *Conn must satisfy agent.Conn (Send/Recv/Close). wire does not import
// agent — this proves the method set lines up without that import.
var _ interface {
	Send(msg.Outbound) error
	Recv() (msg.Inbound, error)
	Close() error
} = (*Conn)(nil)

func (c *Client) Dial(ctx context.Context, endpoint string) (*Conn, error) {
	tlsCfg, err := c.id.ConnectTLS()
	if err != nil {
		return nil, err
	}
	cc, err := grpc.NewClient(endpoint,
		grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg)),
		grpc.WithKeepaliveParams(keepalive.ClientParameters{Time: 30 * time.Second, Timeout: 10 * time.Second, PermitWithoutStream: true}),
		grpc.WithDefaultCallOptions(grpc.MaxCallRecvMsgSize(maxRecvBytes)),
	)
	if err != nil {
		return nil, err
	}
	sctx, cancel := context.WithCancel(ctx)
	stream, err := pb.NewAgentGatewayClient(cc).Connect(sctx)
	if err != nil {
		cancel()
		_ = cc.Close()
		return nil, fmt.Errorf("wire: connect: %w", codec.ErrorFromStatus(err))
	}
	return &Conn{cc: cc, stream: stream, cancel: cancel}, nil
}

func (c *Conn) Send(m msg.Outbound) error {
	p, err := codec.AgentToPB(m)
	if err != nil {
		return err
	}
	return c.stream.Send(p)
}

func (c *Conn) Recv() (msg.Inbound, error) {
	p, err := c.stream.Recv()
	if err != nil {
		return nil, codec.ErrorFromStatus(err)
	}
	return codec.GatewayFromPB(p)
}

func (c *Conn) Close() error {
	_ = c.stream.CloseSend()
	c.cancel()
	return c.cc.Close()
}

// TelemetryStream is the Telemetry RPC on the Conn's own connection: gateway
// §4.1 puts all three RPCs on one TLS connection.
type TelemetryStream struct {
	stream pb.AgentGateway_TelemetryClient
	cancel context.CancelFunc
}

func (c *Conn) OpenTelemetry(ctx context.Context) (*TelemetryStream, error) {
	sctx, cancel := context.WithCancel(ctx)
	st, err := pb.NewAgentGatewayClient(c.cc).Telemetry(sctx)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("wire: telemetry: %w", codec.ErrorFromStatus(err))
	}
	return &TelemetryStream{stream: st, cancel: cancel}, nil
}

func (t *TelemetryStream) Send(b msg.TelemetryBatch) error {
	return t.stream.Send(codec.TelemetryUpToPB(b))
}

func (t *TelemetryStream) Recv() (msg.TelemetryInbound, error) {
	p, err := t.stream.Recv()
	if err != nil {
		return nil, codec.ErrorFromStatus(err)
	}
	return codec.TelemetryDownFromPB(p)
}

func (t *TelemetryStream) Close() error {
	err := t.stream.CloseSend()
	t.cancel()
	return err
}
