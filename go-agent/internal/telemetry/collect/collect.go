// Package collect is the http_json collector (spec §3.3): one goroutine per
// declared binding, isolated so a panic or a hung endpoint fails alone and
// never reaches the process ("a bad collector fails alone", design §2).
package collect

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/jsonptr"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/tmetrics"
)

// MaxBody bounds one fetched document. A larger one is refused, never held.
const MaxBody = 1 << 20

type Appender interface {
	Append(stream string, producedAt time.Time, samples []buffer.Sample) (uint64, error)
}

type Collector struct {
	Binding string
	Decl    declare.Binding
	Adapter runtime.Adapter
	InLease func(binding string) bool
	Out     Appender
	Client  *http.Client // nil: a default client; the binding's timeout bounds each tick
	Now     func() time.Time
	Log     *slog.Logger
	Metrics *tmetrics.Metrics

	warnedNotLeased bool
}

type endpoint struct {
	port int
	path string
}

// Run ticks every interval until ctx ends. A tick is synchronous, so one
// binding never has two scrapes in flight; a slow tick makes the ticker drop
// beats rather than queue them.
func (c *Collector) Run(ctx context.Context) {
	t := time.NewTicker(c.Decl.Interval)
	defer t.Stop()
	for {
		c.Tick(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Tick runs one collection. It never panics outward and never blocks past
// the binding's timeout.
func (c *Collector) Tick(ctx context.Context) {
	defer func() {
		if r := recover(); r != nil {
			c.Metrics.Panic(c.Binding)
			c.Log.Error("telemetry collector panicked", "binding", c.Binding, "panic", r)
		}
	}()
	if !c.InLease(c.Binding) {
		if !c.warnedNotLeased {
			c.warnedNotLeased = true
			c.Log.Warn("telemetry: declared binding is not in the lease; not collecting", "binding", c.Binding)
		}
		return
	}
	c.warnedNotLeased = false
	ctx, cancel := context.WithTimeout(ctx, c.Decl.Timeout)
	defer cancel()
	p, err := c.Adapter.Resolve(ctx, c.Binding)
	if err != nil {
		c.fail("resolve", err)
		return
	}
	now := c.Now().UTC()
	docs := map[endpoint]any{} // a failed endpoint is cached as nil: one attempt per tick
	var samples []buffer.Sample
	for _, m := range c.Decl.Metrics {
		ep := endpoint{port: m.Source.Port, path: m.Source.Path}
		doc, fetched := docs[ep]
		if !fetched {
			doc = c.fetch(ctx, p, ep)
			docs[ep] = doc
		}
		if doc == nil {
			continue
		}
		v, err := jsonptr.Resolve(doc, *m.Source.Pointer)
		f, isNumber := jsonptr.Number(v)
		if err != nil || !isNumber {
			c.Metrics.Unavail(c.Binding, m.Name)
			if m.Source.OnUnavailable == "error" {
				c.Log.Warn("telemetry metric unavailable", "binding", c.Binding, "metric", m.Name)
			}
			continue // no sample — never a zero
		}
		samples = append(samples, buffer.Sample{Name: m.Name, Type: m.MetricType, TS: now, Value: f, ReplicaRef: p.Name, Incarnation: p.ContainerID})
	}
	if len(samples) == 0 {
		return
	}
	if _, err := c.Out.Append(c.Binding, now, samples); err != nil {
		c.Log.Warn("telemetry buffer write failed; batch dropped", "binding", c.Binding, "err", err)
	}
}

// fetch returns the decoded document, or nil after counting the failure. The
// body is never logged (I6).
func (c *Collector) fetch(ctx context.Context, p runtime.Placement, ep endpoint) any {
	addr, err := c.Adapter.ScrapeAddr(ctx, p, ep.port)
	if err != nil {
		c.fail("addr", err)
		return nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+addr+ep.path, nil)
	if err != nil {
		c.fail("http", err)
		return nil
	}
	client := c.Client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		c.fail("http", err)
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		c.fail("status", fmt.Errorf("GET %s: %s", ep.path, resp.Status))
		return nil
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, MaxBody+1))
	if err != nil {
		c.fail("http", err)
		return nil
	}
	if len(body) > MaxBody {
		c.fail("decode", fmt.Errorf("GET %s: body over %d bytes", ep.path, MaxBody))
		return nil
	}
	var doc any
	if err := decodeNumbers(body, &doc); err != nil {
		c.fail("decode", fmt.Errorf("GET %s: %w", ep.path, err))
		return nil
	}
	return doc
}

func decodeNumbers(body []byte, v *any) error {
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	return dec.Decode(v)
}

func (c *Collector) fail(reason string, err error) {
	c.Metrics.ScrapeError(c.Binding, reason)
	c.Log.Debug("telemetry scrape failed", "binding", c.Binding, "reason", reason, "err", err)
}
