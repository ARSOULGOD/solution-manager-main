package agent

import (
	"context"
	"errors"
	"log/slog"
	"math/rand/v2"
	"time"

	"github.com/google/uuid"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/telemetry"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
	"github.com/solace/solution-manager/go-agent/internal/wire"
)

type TelemetryConfig struct {
	Declarations *declare.File
	BufferBytes  int64
	BufferAge    time.Duration
}

type BuildConfig struct {
	DataDir        string
	Endpoint       string
	Token          string
	TrustBundlePEM []byte
	AgentVersion   string
	AdminAddr      string
	Adapter        runtime.Adapter
	Log            *slog.Logger
	Observe        func(reactor.Event, []reactor.Effect)
	Roles          Roles
	Telemetry      *TelemetryConfig
}

func newUUIDv7() string {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.NewString()
	}
	return id.String()
}

// fullJitter returns a uniform duration in [0, d] — "full jitter" backoff.
func fullJitter(d time.Duration) time.Duration {
	if d <= 0 {
		return 0
	}
	return time.Duration(rand.Int64N(int64(d) + 1))
}

func Build(bc BuildConfig) (*Loop, *store.Store, error) {
	s, err := store.Open(bc.DataDir)
	if err != nil {
		return nil, nil, err
	}
	id, err := identity.Load(s)
	if err != nil {
		return nil, nil, err
	}
	cfg := Config{
		BootstrapEndpoint: bc.Endpoint,
		EnrollmentToken:   bc.Token,
		AgentVersion:      bc.AgentVersion,
		Capabilities:      bc.Roles.capabilities(),
	}
	st, err := LoadState(cfg, s, id)
	if err != nil {
		return nil, nil, err
	}
	deps := Deps{
		Config:   cfg,
		Store:    s,
		Identity: id,
		Wire:     wireAdapter{wire.New(id, bc.TrustBundlePEM)},
		Adapter:  bc.Adapter,
		NewID:    newUUIDv7,
		Jitter:   fullJitter,
		Now:      time.Now,
		Log:      bc.Log,
		Observe:  bc.Observe,
	}
	var pipe *telemetry.Pipeline
	if bc.Roles.Telemetry {
		if bc.Telemetry == nil || bc.Telemetry.Declarations == nil {
			return nil, nil, errors.New("agent: the telemetry role needs declarations")
		}
		hub := view.NewHub()
		pipe, err = telemetry.New(telemetry.Config{Store: s, Declarations: bc.Telemetry.Declarations,
			BufferBytes: bc.Telemetry.BufferBytes, BufferAge: bc.Telemetry.BufferAge,
			Adapter: bc.Adapter, View: hub, NewID: newUUIDv7, Jitter: fullJitter, Log: bc.Log})
		if err != nil {
			return nil, nil, err
		}
		deps.View, deps.Telemetry, deps.TelemetryResume = hub, pipe, pipe.Resume
	}
	l := New(deps, st)
	if bc.Adapter != nil {
		l.d.Ops = &opRunner{adapter: bc.Adapter, store: s, push: l.Push, now: l.d.Now, offset: l.d.Offset}
	}
	if bc.AdminAddr != "" {
		m := NewMetrics()
		if pipe != nil {
			m.Register(pipe.Metrics().Collectors()...)
		}
		l.d.Observe = m.Observe(l)
		go func() {
			if err := ServeAdmin(context.Background(), bc.AdminAddr, l, m, bc.Log); err != nil {
				bc.Log.Error("admin server", "err", err)
			}
		}()
	}
	return l, s, nil
}

type wireAdapter struct{ c *wire.Client }

func (w wireAdapter) Enroll(ctx context.Context, endpoint string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	return w.c.Enroll(ctx, endpoint, req)
}

func (w wireAdapter) Dial(ctx context.Context, endpoint string) (Conn, error) {
	c, err := w.c.Dial(ctx, endpoint)
	if err != nil {
		return nil, err
	}
	return wireConn{c}, nil
}

// wireConn adds the telemetry opener to *wire.Conn in view's types, so
// internal/wire never imports the telemetry packages.
type wireConn struct{ *wire.Conn }

func (c wireConn) OpenTelemetry(ctx context.Context) (view.Stream, error) {
	s, err := c.Conn.OpenTelemetry(ctx)
	if err != nil {
		return nil, err // never a typed-nil *TelemetryStream inside the interface
	}
	return s, nil
}
