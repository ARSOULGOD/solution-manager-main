// Package wire is the gRPC/mTLS binding of the DRAFT agent-gateway protocol.
// It and codec are the only packages that import pb.
package wire

import (
	"context"
	"errors"
	"fmt"
	"sync"
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

// ErrUndecodable wraps a stream message that arrived intact but could not be
// decoded (an empty or unknown variant): the stream itself is still usable,
// unlike a transport error.
var ErrUndecodable = errors.New("wire: undecodable message")

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
	// ended is closed by Recv the first time the stream ends (the gateway
	// closed it, or it broke). Close waits on it after half-closing.
	ended     chan struct{}
	endedOnce sync.Once
}

// closeGrace bounds how long Close waits, after half-closing, for the gateway
// to end the stream; a gateway that never answers must not hold shutdown.
const closeGrace = 2 * time.Second

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
	return &Conn{cc: cc, stream: stream, cancel: cancel, ended: make(chan struct{})}, nil
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
		c.endedOnce.Do(func() { close(c.ended) })
		return nil, codec.ErrorFromStatus(err)
	}
	return codec.GatewayFromPB(p)
}

// Close ends the stream by half-closing, then waits (bounded by closeGrace) for
// the gateway to end it in answer, and only then cancels. Cancelling straight
// after the last Send resets the stream, and a cancelled server call discards
// the inbound messages it has not yet read: a DrainComplete sent just before
// Close was lost that way (plan 015 Final, W5 review finding 8). The agent
// loop's own reader is the one goroutine calling Recv, so Close waits on the
// signal Recv raises rather than reading the stream itself. Cost: when the
// reader goroutine is already gone (the context was cancelled first) nothing
// raises that signal, so Close always pays the full closeGrace (2 s) before it
// cancels; accepted, because Close runs once, at the end of a session
// (final review NIT).
func (c *Conn) Close() error {
	_ = c.stream.CloseSend()
	select {
	case <-c.ended:
	case <-time.After(closeGrace):
	}
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

// LogsStream is the Logs RPC (plan 016) on the Conn's own connection, opened
// after SessionAccept as Telemetry is. Send is not safe for concurrent use;
// the caller serializes it.
type LogsStream struct {
	stream pb.AgentGateway_LogsClient
	cancel context.CancelFunc
}

func (c *Conn) OpenLogs(ctx context.Context) (*LogsStream, error) {
	sctx, cancel := context.WithCancel(ctx)
	st, err := pb.NewAgentGatewayClient(c.cc).Logs(sctx)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("wire: logs: %w", codec.ErrorFromStatus(err))
	}
	return &LogsStream{stream: st, cancel: cancel}, nil
}

func (s *LogsStream) Send(m msg.LogsOutbound) error {
	p, err := codec.EncodeLogUp(m)
	if err != nil {
		return err
	}
	return s.stream.Send(p)
}

func (s *LogsStream) Recv() (msg.LogsInbound, error) {
	p, err := s.stream.Recv()
	if err != nil {
		return nil, codec.ErrorFromStatus(err)
	}
	m, err := codec.DecodeLogDown(p)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUndecodable, err)
	}
	return m, nil
}

func (s *LogsStream) Close() error {
	err := s.stream.CloseSend()
	s.cancel()
	return err
}
