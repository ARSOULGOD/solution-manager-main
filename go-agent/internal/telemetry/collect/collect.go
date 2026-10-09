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
	"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"
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
	Cadence *cadence.Holder // the DP's pull interval (AG-D26); nil: always Decl.Interval

	warnedNotLeased bool
}

type endpoint struct {
	port int
	path string
}

// Run ticks every interval until ctx ends. The interval is the DP's pull
// interval when it set one (AG-D26), else the declared one. A change resets
// the ticker, so the next scrape comes one new interval later, never at once.
// A tick is synchronous, so one binding never has two scrapes in flight; a
// slow tick makes the ticker drop beats rather than queue them.
func (c *Collector) Run(ctx context.Context) {
	changed, every := c.cadence()
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		c.Tick(ctx)
	wait:
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				break wait
			case <-changed:
				var d time.Duration
				if changed, d = c.cadence(); d != every {
					every = d
					t.Reset(every) // every > 0: a DP pull is ≥ 1 s, a declared one validated ≥ 1 s
				}
			}
		}
	}
}

// cadence returns the interval in use and the channel its next change closes.
// With no holder nothing changes: a nil channel never fires in a select.
func (c *Collector) cadence() (<-chan struct{}, time.Duration) {
	if c.Cadence == nil {
		return nil, c.Decl.Interval
	}
	ch := c.Cadence.Changed() // before Current, so a Set between them is not missed
	return ch, c.interval(c.Cadence.Current())
}

func (c *Collector) interval(s cadence.Settings) time.Duration {
	if s.Pull > 0 {
		return s.Pull
	}
	return c.Decl.Interval
}

// timeout keeps timeout ≤ interval when the DP shortens the interval (spec §2.2).
func (c *Collector) timeout() time.Duration {
	if c.Cadence == nil {
		return c.Decl.Timeout
	}
	return min(c.Decl.Timeout, c.interval(c.Cadence.Current()))
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
	ctx, cancel := context.WithTimeout(ctx, c.timeout())
	defer cancel()
	p, err := c.Adapter.Resolve(ctx, c.Binding)
	if err != nil {
		c.fail("resolve", err)
		return
	}
	now := c.Now().UTC()
	docs := map[endpoint]fetched{} // a failed endpoint is cached too: one attempt per tick
	var samples []buffer.Sample
	for _, m := range c.Decl.Metrics {
		ep := endpoint{port: m.Source.Port, path: m.Source.Path}
		r, seen := docs[ep]
		if !seen {
			r.doc, r.ok = c.fetch(ctx, p, ep)
			docs[ep] = r
		}
		if !r.ok {
			continue
		}
		doc := r.doc
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

type fetched struct {
	doc any
	ok  bool // the fetch succeeded; doc may still be nil (a JSON null body)
}

// defaultClient never uses a proxy (a container-IP scrape must not leave the
// host through HTTP_PROXY) and never follows a redirect: a 3xx is a status error.
var defaultClient = &http.Client{
	Transport:     &http.Transport{Proxy: nil},
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
}

// fetch returns the decoded document and whether the fetch succeeded; a failure
// is counted here. The body is never logged (I6).
func (c *Collector) fetch(ctx context.Context, p runtime.Placement, ep endpoint) (any, bool) {
	addr, err := c.Adapter.ScrapeAddr(ctx, p, ep.port)
	if err != nil {
		c.fail("addr", err)
		return nil, false
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+addr+ep.path, nil)
	if err != nil {
		c.fail("http", err)
		return nil, false
	}
	client := c.Client
	if client == nil {
		client = defaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		c.fail("http", err)
		return nil, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		c.fail("status", fmt.Errorf("GET %s: %s", ep.path, resp.Status))
		return nil, false
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, MaxBody+1))
	if err != nil {
		c.fail("http", err)
		return nil, false
	}
	if len(body) > MaxBody {
		c.fail("decode", fmt.Errorf("GET %s: body over %d bytes", ep.path, MaxBody))
		return nil, false
	}
	var doc any
	if err := decodeNumbers(body, &doc); err != nil {
		c.fail("decode", fmt.Errorf("GET %s: %w", ep.path, err))
		return nil, false
	}
	return doc, true
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
