package fakegw

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"

	"github.com/google/uuid"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/peer"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/testca"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

const tokenHeader = "x-sm-enrollment-token"

type Config struct {
	Listen   string
	Tokens   []string
	Scenario *Scenario
	Log      *slog.Logger
}

type enrolledAgent struct {
	agentID    string
	csrHash    string
	resp       msg.EnrollResponse
	thumbprint string
	lease      *msg.LeaseGrant
	renewIDs   map[string]msg.LeaseGrant // message_id → grant, for idempotent resends
	revoked    bool
	epoch      uint64
	sessions   int
	nextID     uint64
}

type Server struct {
	pb.UnimplementedAgentGatewayServer
	cfg Config
	ca  *testca.CA
	srv *grpc.Server
	lis net.Listener
	tr  *transcript
	log *slog.Logger

	mu       sync.Mutex
	tokens   map[string]*enrolledAgent // token → agent (nil until consumed)
	byThumb  map[string]*enrolledAgent
	replicas map[string]string // binding|replica_ref → replica_id
	incarn   map[string]string // binding|replica_ref|incarnation → incarnation_id
	sessions int
}

func Start(cfg Config) (*Server, error) {
	if cfg.Scenario == nil {
		cfg.Scenario = DefaultScenario()
	}
	if cfg.Log == nil {
		cfg.Log = slog.Default()
	}
	ca, err := testca.New()
	if err != nil {
		return nil, err
	}
	lis, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return nil, err
	}
	host, _, _ := net.SplitHostPort(lis.Addr().String())
	tlsCfg, err := ca.ServerTLS(host)
	if err != nil {
		return nil, err
	}
	s := &Server{
		cfg: cfg, ca: ca, lis: lis, tr: newTranscript(), log: cfg.Log,
		tokens: map[string]*enrolledAgent{}, byThumb: map[string]*enrolledAgent{},
		replicas: map[string]string{}, incarn: map[string]string{},
	}
	for _, t := range cfg.Tokens {
		s.tokens[t] = nil
	}
	s.srv = grpc.NewServer(grpc.Creds(credentials.NewTLS(tlsCfg)))
	pb.RegisterAgentGatewayServer(s.srv, s)
	go func() { _ = s.srv.Serve(lis) }()
	return s, nil
}

func (s *Server) Endpoint() string { return s.lis.Addr().String() }
func (s *Server) CAPEM() []byte    { return s.ca.PEM() }
func (s *Server) Stop()            { s.srv.Stop() }

func (s *Server) Transcript() []Entry                              { return s.tr.all() }
func (s *Server) Count(kind string) int                            { return s.tr.count(kind) }
func (s *Server) Received(kind string) []any                       { return s.tr.received(kind) }
func (s *Server) WaitFor(kind string, n int, d time.Duration) bool { return s.tr.waitFor(kind, n, d) }
func (s *Server) Sessions() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sessions
}

func (s *Server) newID() string {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.NewString()
	}
	return id.String()
}

func csrHash(der []byte) string {
	h := sha256.Sum256(der)
	return hex.EncodeToString(h[:])
}

// Enroll: single-use token + CSR; the same (token, CSR) replays the stored
// reply; a different CSR or a second distinct request is TOKEN_CONSUMED.
func (s *Server) Enroll(ctx context.Context, req *pb.EnrollRequest) (*pb.EnrollResponse, error) {
	md, _ := metadata.FromIncomingContext(ctx)
	var token string
	if v := md.Get(tokenHeader); len(v) > 0 {
		token = v[0]
	}
	r := codec.EnrollRequestFromPB(req)
	s.tr.add("in", r)
	s.mu.Lock()
	defer s.mu.Unlock()
	ag, known := s.tokens[token]
	if !known {
		return nil, codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: "unknown token"})
	}
	h := csrHash(r.CSRDER)
	if ag != nil {
		if ag.csrHash == h {
			s.tr.add("out", ag.resp)
			return codec.EnrollResponseToPB(ag.resp), nil
		}
		return nil, codec.StatusFromError(msg.Error{Code: msg.ErrTokenConsumed, Message: "token already used"})
	}
	leaf, err := s.ca.Sign(r.CSRDER)
	if err != nil {
		return nil, codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: "bad csr"})
	}
	ag = &enrolledAgent{
		agentID: "agent-" + s.newID()[:8], csrHash: h,
		thumbprint: thumbprint(leaf), renewIDs: map[string]msg.LeaseGrant{},
	}
	ag.resp = msg.EnrollResponse{
		AgentID: ag.agentID, CertificateChain: [][]byte{leaf},
		TrustBundle:     msg.TrustBundle{CertsPEM: [][]byte{s.ca.PEM()}, ValidFrom: time.Now().Add(-time.Hour), ValidTo: time.Now().Add(365 * 24 * time.Hour)},
		ConnectEndpoint: s.Endpoint(),
	}
	s.tokens[token] = ag
	s.byThumb[ag.thumbprint] = ag
	s.tr.add("out", ag.resp)
	return codec.EnrollResponseToPB(ag.resp), nil
}

func thumbprint(der []byte) string {
	h := sha256.Sum256(der)
	return hex.EncodeToString(h[:])
}

// agentFor resolves the mTLS peer to an enrolled agent.
func (s *Server) agentFor(ctx context.Context) (*enrolledAgent, error) {
	p, ok := peer.FromContext(ctx)
	if !ok {
		return nil, fmt.Errorf("no peer")
	}
	ti, ok := p.AuthInfo.(credentials.TLSInfo)
	if !ok || len(ti.State.PeerCertificates) == 0 {
		return nil, fmt.Errorf("no client certificate")
	}
	tp := thumbprint(ti.State.PeerCertificates[0].Raw)
	s.mu.Lock()
	defer s.mu.Unlock()
	ag := s.byThumb[tp]
	if ag == nil {
		return nil, fmt.Errorf("unknown certificate")
	}
	return ag, nil
}

var _ = tls.VersionTLS13
var _ = x509.ErrUnsupportedAlgorithm
