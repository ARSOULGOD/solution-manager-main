package send

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
)

type fakeStream struct {
	sent   chan msg.TelemetryBatch
	in     chan msg.TelemetryInbound
	closed chan struct{}
	once   sync.Once
}

func newFakeStream() *fakeStream {
	return &fakeStream{sent: make(chan msg.TelemetryBatch, 16), in: make(chan msg.TelemetryInbound, 16), closed: make(chan struct{})}
}

func (f *fakeStream) Send(b msg.TelemetryBatch) error { f.sent <- b; return nil }
func (f *fakeStream) Recv() (msg.TelemetryInbound, error) {
	select {
	case m := <-f.in:
		return m, nil
	case <-f.closed:
		return nil, errors.New("closed")
	}
}
func (f *fakeStream) Close() error { f.once.Do(func() { close(f.closed) }); return nil }

type ids map[string][2]string

func (m ids) IDs(b, r, i string) (string, string, bool) {
	v, ok := m[b+"|"+r+"|"+i]
	return v[0], v[1], ok
}

var registered = ids{"binding-1|java1|c1": {"rep-1", "inc-1"}}

func session(open func(context.Context) (view.Stream, error)) *view.SessionInfo {
	return &view.SessionInfo{ID: "s1", Limits: msg.Limits{InitialCredits: 2, MaxBatchBytes: 65536, ReportInterval: time.Minute}, Open: open}
}

func runner(t *testing.T, hub *view.Hub) (*Runner, *buffer.Buffer) {
	t.Helper()
	buf, err := buffer.Open(store.MustOpen(t.TempDir()), buffer.Options{})
	if err != nil {
		t.Fatal(err)
	}
	r := &Runner{Buf: buf, View: hub, NewID: func() string { return "m" },
		Now: time.Now, Jitter: func(time.Duration) time.Duration { return 0 }, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { r.Run(ctx); close(done) }()
	t.Cleanup(func() { cancel(); <-done })
	return r, buf
}

func appendOne(t *testing.T, buf *buffer.Buffer) {
	t.Helper()
	if _, err := buf.Append("binding-1", time.Now(), []buffer.Sample{{Name: "cache.hit_rate", Type: "gauge", TS: time.Unix(0, 0).UTC(), Value: 0.93, ReplicaRef: "java1", Incarnation: "c1"}}); err != nil {
		t.Fatal(err)
	}
}

func receive(t *testing.T, s *fakeStream) msg.TelemetryBatch {
	t.Helper()
	select {
	case b := <-s.sent:
		return b
	case <-time.After(5 * time.Second):
		t.Fatal("nothing sent")
	}
	return msg.TelemetryBatch{}
}

func TestRunnerSendsWithExactlyTheMintedLabelsAndTrimsOnAck(t *testing.T) {
	hub := view.NewHub()
	_, buf := runner(t, hub)
	s := newFakeStream()
	appendOne(t, buf)
	hub.Publish(view.Snapshot{Session: session(func(context.Context) (view.Stream, error) { return s, nil }),
		Bindings: map[string]bool{"binding-1": true}, IDs: registered})

	b := receive(t, s)
	want := map[string]string{"binding_id": "binding-1", "replica_id": "rep-1", "incarnation_id": "inc-1"}
	if b.StreamID != "binding-1" || b.BatchSeq != 1 || len(b.Samples) != 1 || !reflect.DeepEqual(b.Samples[0].Labels, want) ||
		b.Samples[0].SeriesKey != "cache.hit_rate" || b.Samples[0].Type != msg.MetricGauge || b.Samples[0].Value != 0.93 {
		t.Fatalf("batch %+v", b)
	}
	s.in <- msg.TelemetryAck{StreamID: "binding-1", HighWatermarkSeq: 1, CreditsGranted: 1}
	deadline := time.Now().Add(5 * time.Second)
	for buf.Stats().Batches != 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if n := buf.Stats().Batches; n != 0 {
		t.Fatalf("ack did not trim: %d batches left", n)
	}
}

func TestRunnerWaitsForIdsBeforeSending(t *testing.T) {
	hub := view.NewHub()
	_, buf := runner(t, hub)
	s := newFakeStream()
	appendOne(t, buf)
	sess := session(func(context.Context) (view.Stream, error) { return s, nil })
	hub.Publish(view.Snapshot{Session: sess, Bindings: map[string]bool{"binding-1": true}, IDs: ids{}})
	select {
	case b := <-s.sent:
		t.Fatalf("sent before the ids were minted: %+v", b)
	case <-time.After(300 * time.Millisecond):
	}
	hub.Publish(view.Snapshot{Session: sess, Bindings: map[string]bool{"binding-1": true}, IDs: registered})
	receive(t, s)
}

func TestRunnerCarriesGapsBeforeTheBatch(t *testing.T) {
	hub := view.NewHub()
	_, buf := runner(t, hub)
	appendOne(t, buf)
	appendOne(t, buf)
	buf.DropAsGap("binding-1", 1, buffer.ReasonDroppedOldest)
	s := newFakeStream()
	hub.Publish(view.Snapshot{Session: session(func(context.Context) (view.Stream, error) { return s, nil }),
		Bindings: map[string]bool{"binding-1": true}, IDs: registered})
	b := receive(t, s)
	if b.BatchSeq != 2 || !reflect.DeepEqual(b.GapMarkers, []msg.GapMarker{{FromSeq: 1, ToSeq: 1, Reason: buffer.ReasonDroppedOldest}}) {
		t.Fatalf("batch %+v", b)
	}
}

func TestRunnerReopensAfterTheStreamBreaks(t *testing.T) {
	hub := view.NewHub()
	runner(t, hub)
	var opens atomic.Int32
	hub.Publish(view.Snapshot{Session: session(func(context.Context) (view.Stream, error) {
		opens.Add(1)
		s := newFakeStream()
		_ = s.Close() // Recv fails at once: a broken stream
		return s, nil
	}), Bindings: map[string]bool{"binding-1": true}, IDs: registered})
	deadline := time.Now().Add(5 * time.Second)
	for opens.Load() < 3 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if opens.Load() < 3 {
		t.Fatalf("opened %d times", opens.Load())
	}
}

func TestRunnerStopsSendingWhenTheSessionGoes(t *testing.T) {
	hub := view.NewHub()
	_, buf := runner(t, hub)
	s := newFakeStream()
	hub.Publish(view.Snapshot{Session: session(func(context.Context) (view.Stream, error) { return s, nil }),
		Bindings: map[string]bool{"binding-1": true}, IDs: registered})
	appendOne(t, buf)
	receive(t, s)
	hub.Publish(view.Snapshot{Bindings: map[string]bool{"binding-1": true}, IDs: registered}) // no session
	select {
	case <-s.closed:
	case <-time.After(5 * time.Second):
		t.Fatal("stream not closed when the session went away")
	}
}
