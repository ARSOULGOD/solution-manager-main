// Package telemetry wires the telemetry role (AG-D21): collectors → buffer →
// sender, beside the control loop and fed only by its published view.
package telemetry

import (
	"context"
	"log/slog"
	"maps"
	"reflect"
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
	Store *store.Store
	// Declarations is the live set: pushed by the DP (AG-D28), seeded by the
	// development file. Collectors follow it as it changes.
	Declarations *declare.Set
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
	// The supervisor keeps one collector per declared binding and replaces a
	// binding's collector when its declared set changes (the replacement is atomic
	// at the Set; a collector only ever runs one complete set).
	wg.Go(func() { p.supervise(ctx) })
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

type running struct {
	cancel context.CancelFunc
	decl   declare.Binding
	done   chan struct{}
}

func (p *Pipeline) supervise(ctx context.Context) {
	active := map[string]running{}
	stop := func(id string) {
		r := active[id]
		r.cancel()
		<-r.done
		delete(active, id)
	}
	defer func() {
		for id := range active {
			stop(id)
		}
	}()
	for {
		cur := p.cfg.Declarations.Bindings()
		for id := range active {
			if d, ok := cur[id]; !ok || len(d.Metrics) == 0 || !reflect.DeepEqual(d, active[id].decl) {
				stop(id)
			}
		}
		for _, id := range slices.Sorted(maps.Keys(cur)) {
			d := cur[id]
			if _, ok := active[id]; ok || len(d.Metrics) == 0 {
				continue
			}
			cctx, cancel := context.WithCancel(ctx)
			done := make(chan struct{})
			c := &collect.Collector{Binding: id, Decl: d, Adapter: p.cfg.Adapter,
				InLease: func(b string) bool { return p.cfg.View.Current().InLease(b) },
				Out:     p.buf, Now: time.Now, Log: p.cfg.Log, Metrics: p.metrics}
			go func() { defer close(done); c.Run(cctx) }()
			active[id] = running{cancel: cancel, decl: d, done: done}
		}
		select {
		case <-ctx.Done():
			return
		case <-p.cfg.Declarations.Changed():
		}
	}
}
