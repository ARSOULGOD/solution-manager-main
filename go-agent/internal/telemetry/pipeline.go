// Package telemetry wires the telemetry role (AG-D21): collectors → buffer →
// sender, beside the control loop and fed only by its published view.
package telemetry

import (
	"context"
	"log/slog"
	"maps"
	"slices"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/collect"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/send"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/tmetrics"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
)

type Config struct {
	Store        *store.Store
	Declarations *declare.File
	BufferBytes  int64
	BufferAge    time.Duration
	Adapter      runtime.Adapter
	View         view.View
	NewID        func() string
	Jitter       func(time.Duration) time.Duration
	Log          *slog.Logger
}

type Pipeline struct {
	cfg     Config
	buf     *buffer.Buffer
	metrics *tmetrics.Metrics
}

func New(cfg Config) (*Pipeline, error) {
	m := tmetrics.New()
	buf, err := buffer.Open(cfg.Store, buffer.Options{
		MaxBytes: cfg.BufferBytes, MaxAge: cfg.BufferAge,
		OnDrop:       m.Drop,
		OnWriteError: func(error) { m.WriteError() },
	})
	if err != nil {
		return nil, err
	}
	m.BufferGauges(func() (int64, int, time.Time) {
		s := buf.Stats()
		return s.Bytes, s.Batches, s.Oldest
	}, cfg.BufferBytes, cfg.BufferAge, time.Now)
	return &Pipeline{cfg: cfg, buf: buf, metrics: m}, nil
}

func (p *Pipeline) Metrics() *tmetrics.Metrics { return p.metrics }

// Resume is SessionOpen.resume.last_telemetry_seq_by_stream (spec §5.1).
func (p *Pipeline) Resume() map[string]uint64 { return p.buf.Resume() }

// Run starts one collector per declared binding, the sender and the age
// sweep, and returns only when ctx has ended and all of them have stopped.
func (p *Pipeline) Run(ctx context.Context) {
	var wg sync.WaitGroup
	for _, id := range slices.Sorted(maps.Keys(p.cfg.Declarations.Bindings)) {
		c := &collect.Collector{Binding: id, Decl: p.cfg.Declarations.Bindings[id], Adapter: p.cfg.Adapter,
			InLease: func(b string) bool { return p.cfg.View.Current().InLease(b) },
			Out:     p.buf, Now: time.Now, Log: p.cfg.Log, Metrics: p.metrics}
		wg.Go(func() { c.Run(ctx) })
	}
	r := &send.Runner{Buf: p.buf, View: p.cfg.View, NewID: p.cfg.NewID, Now: time.Now, Jitter: p.cfg.Jitter, Log: p.cfg.Log, Metrics: p.metrics}
	wg.Go(func() { r.Run(ctx) })
	wg.Go(func() {
		t := time.NewTicker(time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				p.buf.Sweep()
			}
		}
	})
	wg.Wait()
}
