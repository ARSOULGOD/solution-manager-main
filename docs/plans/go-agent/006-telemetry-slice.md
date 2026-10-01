# go-agent Telemetry Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the `host-local` agent a telemetry role: collect declared `http_json` metrics from a binding's container, buffer them on disk under `AG-D9`, and stream them to the gateway under credits, labelled only with DP-minted ids. Demonstrable end to end against `fake-gateway`.

**Architecture:** A separate pipeline beside the slice-1 reactor (`AG-D21`). One collector goroutine per declared binding writes batches into a file-per-batch disk buffer. A sender — a pure reducer plus a runner — spends gateway credits on the oldest buffered batch across streams, and trims the buffer on `TelemetryAck`. The reactor publishes an immutable `view.Snapshot` (session, lease bindings, id registry) after relevant events, and receives no telemetry events.

**Tech Stack:** Go 1.26 (`go.mod`), `google.golang.org/grpc` + `google.golang.org/protobuf` (buf codegen), `github.com/docker/docker` v27 Engine API, `github.com/docker/go-connections/nat`, `github.com/prometheus/client_golang`, `gopkg.in/yaml.v3`, stdlib `net/http`, `encoding/json`, `log/slog`.

**Spec:** `docs/superpowers/specs/2026-10-01-go-agent-telemetry-slice-design.md` (approved 2026-10-01). Read it first; this plan argues from it. Above the spec: `docs/design/backend/agent-gateway/design.md` §4.1, §4.3, §4.10, §4.13, §5.5, §6.1–§6.3, which overrules anything below it.

## Global Constraints

- Module `github.com/solace/solution-manager/go-agent`, `go 1.26.4`. Never lower the floor.
- **`internal/wire` and `internal/wire/codec` are the only packages that import `internal/wire/pb`.** The telemetry packages speak `internal/msg`.
- **`internal/telemetry/...` never imports `internal/agent`.** `agent` imports `telemetry`, `telemetry/view` and `telemetry/declare`.
- **Sample labels are exactly `binding_id`, `replica_id`, `incarnation_id`**, all three DP-minted (gateway §6.1). No other key, ever.
- **Never emit a zero for an unavailable metric** — no sample at all (`architecture.md` §10, `UI-D8`).
- Telemetry buffer files go through `internal/store` (atomic write, parent-directory fsync, dirs `0700`, files `0600`).
- **A telemetry write failure is counted and non-fatal (`AG-D25`). A control-path `Persist` failure stays fatal.** Do not change slice 1's behaviour.
- **Response bodies from scraped endpoints are never logged or stored (`I6`).** Only numbers at declared pointers leave the collector.
- Reducers are pure: no clock reads, I/O, goroutines or randomness. Impurities arrive through the `Env` argument.
- Codegen tools live in `~/go/bin`: run `PATH="$PATH:$HOME/go/bin" make proto`.
- Before every commit run `go vet ./... && go test ./...` from `go-agent/`. Integration tests are `-tags integration`; Docker tests are `-tags docker`. Run `go test -race ./internal/telemetry/... ./internal/fakegw/` whenever those change.
- **Branching.** Work on a branch, `feat/go-agent-telemetry`, not `main`. The interactive-console work, this spec and this plan are uncommitted on `main` when the plan is written. Ask the user before committing them; carry them onto the branch.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Decided while planning — deviations from the spec, each with its reason

1. **An agent-side capability check.** The spec (§2.3) says a `--roles telemetry` agent's commands are refused "by the existing offer gate". That gate (`authority.State.RefusalDetail`) checks only the **lease**, and `fake-gateway` grants `apply-config`+`lifecycle` regardless. Task 13 wraps it so an operation whose capability the agent did not advertise is refused `CAPABILITY_MISSING` first.
2. **Two more gap reasons.**
   - `UNREGISTERED`: a batch whose ids are still unknown after two report intervals of an open stream — Review Focus 1.
   - `CORRUPT`: an unreadable batch file found on load. Telemetry must not stop the agent starting.

   Both join the `AG-16` proposal.
3. **The view is one value.** `View.Current() Snapshot` plus `Changed()`, with `InLease` and `Lookup` methods on the snapshot. This is spec §5.1's four methods expressed as one immutable value, so a reader never sees a session from one publish and ids from another.
4. **`fake-gateway` marks a session live before sending `SessionAccept`**, and offers wait on an `accepted` channel. The old order, live after accept, let an agent that opens `Telemetry` immediately be refused — a race Task 2's tests would hit.
5. **`StreamBroken` while the session is up restores one credit per in-flight batch**, because each held one credit its ack would have returned. Recorded under `AG-17` as an assumption.
6. **A rejected batch's credit is restored**, except for `OUT_OF_CREDITS`: the gateway "refuses before accepting" a credit (gateway §5.5).
7. **The oversize check compares the stored batch's JSON size against `max_batch_bytes`.** The telemetry packages cannot import `pb` to measure the protobuf size; JSON is larger than the protobuf encoding of the same batch, so the check is conservative.
8. **`fake-gateway`'s `ResumeTelemetry(n)` takes the credit count.** The console passes `max(initial_credits, 1)`, so a scenario with zero initial credits can still be resumed.

## Review Focus

The input classes the spec implies but no task's own tests would otherwise exercise. Each has a test in the task named.

1. **A container restarted before its new incarnation was ever reported.** Its batches' ids never resolve, so the stream must not stall until the 24 h age cap. Expected: dropped as gap `UNREGISTERED` after 2 × `report_interval` of an open stream (Task 11, `TestUnresolvedIdsAreDroppedAfterTwoReportIntervals`).
2. **A metrics endpoint returning a huge body** (a 2 MiB array). Expected: the read stops at 1 MiB, the scrape counts as `decode`, nothing is appended, and memory stays flat (Task 10, `TestOversizedBodyIsRefused`).
3. **The agent's clock stepping backwards**, so `produced_at` lies in the future. Expected: such batches do not expire early, but the byte cap still bounds the buffer (Task 7, `TestFutureTimestampsStillObeyTheByteCap`).
4. **More bindings than credits** (six streams, four credits). Expected: oldest-first across streams; no stream starves while others keep sending (Task 11, `TestMoreStreamsThanCreditsNeverStarves`).
5. **An endpoint that answers slowly at the edge of the interval.** Expected: scrapes for one binding never overlap — at most one request in flight (Task 10, `TestSlowEndpointNeverOverlaps`).

---

## File Structure

| Path | Responsibility |
|---|---|
| `proto/agentgateway/v1draft/agent_gateway.proto` | + `MetricType`, `Sample`, `GapMarker`, `TelemetryBatch/Ack/Credit/Reject`; `TelemetryUp/Down` gain `oneof`s |
| `internal/wire/pb/*.pb.go` | regenerated, committed |
| `internal/msg/msg.go` | + telemetry message structs, `MetricType`, `TelemetryInbound` |
| `internal/wire/codec/codec.go` | + `TelemetryUpToPB/FromPB`, `TelemetryDownToPB/FromPB` |
| `internal/wire/client.go` | + `Conn.OpenTelemetry`, `TelemetryStream` |
| `internal/fakegw/telemetry.go` | new: the `Telemetry` RPC, watermark, credits, pause/resume, output lines |
| `internal/fakegw/{server,session,scenario,console}.go` | + `Config.Out`, live-before-accept, `Disconnect`, telemetry limits/policy, console verbs |
| `internal/telemetry/jsonptr/jsonptr.go` | RFC 6901 `Valid`, `Resolve`, `Number` |
| `internal/telemetry/declare/declare.go` | parse and validate the declarations file |
| `internal/telemetry/tmetrics/tmetrics.go` | the pipeline's Prometheus metrics; nil-safe helpers |
| `internal/telemetry/buffer/buffer.go` | the `AG-D9` disk buffer |
| `internal/telemetry/view/view.go` | `Snapshot`, `SessionInfo`, `Stream`, `IDLookup`, `Hub` |
| `internal/telemetry/collect/collect.go` | the `http_json` collector |
| `internal/telemetry/send/reducer.go` | pure sender reducer |
| `internal/telemetry/send/runner.go` | executes the reducer's effects |
| `internal/telemetry/pipeline.go` | wires collectors, buffer, sender and sweep |
| `internal/reports/reports.go` | + `IDTable` |
| `internal/runtime/runtime.go`, `internal/runtime/docker/docker.go` | + `Adapter.ScrapeAddr` |
| `internal/reactor/reactor.go` | `Connected` gains `TelemetryResume` |
| `internal/agent/{state,session,loop,wiring,admin,roles}.go` | capability gate, resume, view publishing, roles, pipeline lifecycle, metric registration |
| `cmd/sm-agent/{main,bytes}.go` | `--roles`, `--telemetry-config`, buffer caps |
| `internal/agent/telemetry_integration_test.go` | end-to-end scenarios |
| `internal/runtime/docker/telemetry_e2e_test.go` | `ScrapeAddr` against a real container |
| `go-agent/RUNBOOK.md`, `docs/design/go-agent/{decisions,design}.md`, `go-agent/CLAUDE.md`, `go-agent/README.md` | docs |

---

### Task 1: DRAFT `.proto`, `msg` types and codec for the Telemetry stream

**Files:**
- Modify: `go-agent/proto/agentgateway/v1draft/agent_gateway.proto` (the `Telemetry` rpc comment; replace the `TelemetryUp`/`TelemetryDown` stubs at the end)
- Regenerate: `go-agent/internal/wire/pb/agent_gateway.pb.go`, `agent_gateway_grpc.pb.go`
- Modify: `go-agent/internal/msg/msg.go` (append)
- Modify: `go-agent/internal/wire/codec/codec.go` (append)
- Test: `go-agent/internal/wire/codec/codec_test.go` (append)

**Interfaces:**
- Produces:
  - `msg.MetricType` (`MetricCounter`, `MetricGauge`, `MetricHistogram`), `msg.Sample`, `msg.GapMarker`, `msg.TelemetryBatch`
  - `msg.TelemetryInbound` interface, implemented by `msg.TelemetryAck`, `msg.TelemetryCredit`, `msg.TelemetryReject`
  - `codec.TelemetryUpToPB(msg.TelemetryBatch) *pb.TelemetryUp`
  - `codec.TelemetryUpFromPB(*pb.TelemetryUp) (msg.TelemetryBatch, error)`
  - `codec.TelemetryDownToPB(msg.TelemetryInbound) (*pb.TelemetryDown, error)`
  - `codec.TelemetryDownFromPB(*pb.TelemetryDown) (msg.TelemetryInbound, error)`

- [ ] **Step 1: Write the failing test.** Append to `internal/wire/codec/codec_test.go`; `now` is the file's existing package-level time:

```go
func TestTelemetryMessagesRoundTrip(t *testing.T) {
	env := msg.Envelope{MessageID: "m", SentAt: now}
	ra := 3 * time.Second
	up := msg.TelemetryBatch{Envelope: env, StreamID: "binding-1", BatchSeq: 7, ProducedAt: now,
		Samples: []msg.Sample{{SeriesKey: "cache.hit_rate", Type: msg.MetricGauge, TS: now, Value: 0.93,
			Labels: map[string]string{"binding_id": "binding-1", "replica_id": "r", "incarnation_id": "i"}}},
		GapMarkers: []msg.GapMarker{{FromSeq: 3, ToSeq: 6, Reason: "DROPPED_OLDEST"}}}
	got, err := TelemetryUpFromPB(TelemetryUpToPB(up))
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(up, got) {
		t.Errorf("batch round trip:\n in=%+v\nout=%+v", up, got)
	}
	for _, in := range []msg.TelemetryInbound{
		msg.TelemetryAck{Envelope: env, StreamID: "binding-1", HighWatermarkSeq: 7, CreditsGranted: 1},
		msg.TelemetryCredit{Envelope: env, StreamID: "binding-1", CreditsGranted: 4},
		msg.TelemetryReject{Envelope: env, StreamID: "binding-1", Code: msg.ErrUnknownReplica, RetryAfter: &ra},
	} {
		p, err := TelemetryDownToPB(in)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		out, err := TelemetryDownFromPB(p)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		if !reflect.DeepEqual(in, out) {
			t.Errorf("%T round trip:\n in=%+v\nout=%+v", in, in, out)
		}
	}
}

func TestMetricTypeCrossesTheWireAsItsNamedValue(t *testing.T) {
	for mt, want := range map[msg.MetricType]pb.MetricType{
		msg.MetricCounter:   pb.MetricType_METRIC_TYPE_COUNTER,
		msg.MetricGauge:     pb.MetricType_METRIC_TYPE_GAUGE,
		msg.MetricHistogram: pb.MetricType_METRIC_TYPE_HISTOGRAM,
	} {
		p := TelemetryUpToPB(msg.TelemetryBatch{Samples: []msg.Sample{{Type: mt}}})
		if got := p.GetTelemetryBatch().Samples[0].Type; got != want {
			t.Errorf("%s encodes as %v, want %v", mt, got, want)
		}
	}
}

func TestEmptyTelemetryEnvelopesAreErrors(t *testing.T) {
	if _, err := TelemetryUpFromPB(&pb.TelemetryUp{}); err == nil {
		t.Error("an up message with no batch must be an error")
	}
	if _, err := TelemetryDownFromPB(&pb.TelemetryDown{}); err == nil {
		t.Error("a down message with nothing in it must be an error")
	}
}
```

- [ ] **Step 2: Run it and watch it fail to compile.**

Run: `cd go-agent && go test ./internal/wire/codec/ -run Telemetry`
Expected: build failure: `undefined: msg.TelemetryBatch` (and siblings).

- [ ] **Step 3: Transcribe the messages into the DRAFT `.proto`.**

Change the rpc comment above `rpc Telemetry`:

```proto
  // Opened only after SessionAccept (gateway §4.1); credits are the only
  // backpressure (§4.10).
  rpc Telemetry(stream TelemetryUp) returns (stream TelemetryDown);
```

Replace the existing `// ---- Telemetry stream (declared, unimplemented in slice 1) ----` section, down to the end of the file, with:

```proto
// ---- Telemetry stream (gateway §4.3, §4.10) ----
//
// Transcription notes:
//  * §4.3 types samples[] as "the normalized model of architecture §10"; the
//    fields are taken from gateway §6.1's NormalizedSample.
//  * gap_markers[].reason is a string because §4.3 defines no vocabulary
//    (go-agent AG-16 proposes DROPPED_OLDEST, EXPIRED, OVERSIZE, UNREGISTERED,
//    CORRUPT, plus §4.11 reject codes).

enum MetricType {
  METRIC_TYPE_UNSPECIFIED = 0;
  METRIC_TYPE_COUNTER = 1;
  METRIC_TYPE_GAUGE = 2;
  METRIC_TYPE_HISTOGRAM = 3;
}

message Sample {
  string series_key = 1;
  MetricType type = 2;
  google.protobuf.Timestamp ts = 3;
  double value = 4;
  map<string, string> labels = 5;
}

message GapMarker {
  uint64 from_seq = 1;
  uint64 to_seq = 2;
  string reason = 3;
}

message TelemetryBatch {
  Envelope envelope = 1;
  string stream_id = 2;
  uint64 batch_seq = 3;
  repeated Sample samples = 4;
  repeated GapMarker gap_markers = 5;
  google.protobuf.Timestamp produced_at = 6;
}

message TelemetryAck {
  Envelope envelope = 1;
  string stream_id = 2;
  uint64 high_watermark_seq = 3;
  uint32 credits_granted = 4;
}

message TelemetryCredit {
  Envelope envelope = 1;
  string stream_id = 2;
  uint32 credits_granted = 3;
}

message TelemetryReject {
  Envelope envelope = 1;
  string stream_id = 2;
  ErrorCode code = 3;
  optional google.protobuf.Duration retry_after = 4;
}

// envelope = 1 predates the oneof and is unused; it is kept, never
// renumbered (§4.12 evolution rules).
message TelemetryUp {
  Envelope envelope = 1;
  oneof msg {
    TelemetryBatch telemetry_batch = 2;
  }
}

message TelemetryDown {
  Envelope envelope = 1;
  oneof msg {
    TelemetryAck telemetry_ack = 2;
    TelemetryCredit telemetry_credit = 3;
    TelemetryReject telemetry_reject = 4;
  }
}
```

- [ ] **Step 4: Regenerate.**

Run: `cd go-agent && PATH="$PATH:$HOME/go/bin" make proto && git diff --stat internal/wire/pb`
Expected: both `*.pb.go` files change; `go build ./...` still passes.

- [ ] **Step 5: Add the `msg` types.** Append to `internal/msg/msg.go`:

```go
// ---- Telemetry stream (gateway §4.3, §4.10) ----

type MetricType string

const (
	MetricCounter   MetricType = "counter"
	MetricGauge     MetricType = "gauge"
	MetricHistogram MetricType = "histogram"
)

// Sample is gateway §6.1's NormalizedSample. On an agent batch, Labels holds
// exactly binding_id, replica_id and incarnation_id, all DP-minted.
type Sample struct {
	SeriesKey string
	Type      MetricType
	TS        time.Time
	Value     float64
	Labels    map[string]string
}

type GapMarker struct {
	FromSeq, ToSeq uint64
	Reason         string
}

// TelemetryBatch is the only agent → gateway message on the Telemetry stream.
type TelemetryBatch struct {
	Envelope
	StreamID   string
	BatchSeq   uint64
	Samples    []Sample
	GapMarkers []GapMarker
	ProducedAt time.Time
}

// TelemetryInbound is a gateway → agent message on the Telemetry stream.
type TelemetryInbound interface{ telemetryInbound() }

type TelemetryAck struct {
	Envelope
	StreamID         string
	HighWatermarkSeq uint64
	CreditsGranted   uint32
}

type TelemetryCredit struct {
	Envelope
	StreamID       string
	CreditsGranted uint32
}

type TelemetryReject struct {
	Envelope
	StreamID   string
	Code       ErrorCode
	RetryAfter *time.Duration
}

func (TelemetryAck) telemetryInbound()    {}
func (TelemetryCredit) telemetryInbound() {}
func (TelemetryReject) telemetryInbound() {}
```

- [ ] **Step 6: Add the codec.** Append to `internal/wire/codec/codec.go`:

```go
// ---- Telemetry stream (gateway §4.3, §4.10) ----

func metricTypeToPB(t msg.MetricType) pb.MetricType {
	return enumTo[pb.MetricType](pb.MetricType_value, "METRIC_TYPE_", string(t))
}

func metricTypeFromPB(p pb.MetricType) msg.MetricType {
	if p == pb.MetricType_METRIC_TYPE_UNSPECIFIED {
		return ""
	}
	return msg.MetricType(strings.ToLower(enumFrom(pb.MetricType_name, "METRIC_TYPE_", p)))
}

func TelemetryUpToPB(b msg.TelemetryBatch) *pb.TelemetryUp {
	p := &pb.TelemetryBatch{Envelope: envToPB(b.Envelope), StreamId: b.StreamID, BatchSeq: b.BatchSeq, ProducedAt: ts(b.ProducedAt)}
	for _, s := range b.Samples {
		p.Samples = append(p.Samples, &pb.Sample{SeriesKey: s.SeriesKey, Type: metricTypeToPB(s.Type), Ts: ts(s.TS), Value: s.Value, Labels: s.Labels})
	}
	for _, g := range b.GapMarkers {
		p.GapMarkers = append(p.GapMarkers, &pb.GapMarker{FromSeq: g.FromSeq, ToSeq: g.ToSeq, Reason: g.Reason})
	}
	return &pb.TelemetryUp{Msg: &pb.TelemetryUp_TelemetryBatch{TelemetryBatch: p}}
}

func TelemetryUpFromPB(p *pb.TelemetryUp) (msg.TelemetryBatch, error) {
	b := p.GetTelemetryBatch()
	if b == nil {
		return msg.TelemetryBatch{}, fmt.Errorf("codec: TelemetryUp carries no batch")
	}
	out := msg.TelemetryBatch{Envelope: envFromPB(b.Envelope), StreamID: b.StreamId, BatchSeq: b.BatchSeq, ProducedAt: tsFrom(b.ProducedAt)}
	for _, s := range b.Samples {
		out.Samples = append(out.Samples, msg.Sample{SeriesKey: s.SeriesKey, Type: metricTypeFromPB(s.Type), TS: tsFrom(s.Ts), Value: s.Value, Labels: s.Labels})
	}
	for _, g := range b.GapMarkers {
		out.GapMarkers = append(out.GapMarkers, msg.GapMarker{FromSeq: g.FromSeq, ToSeq: g.ToSeq, Reason: g.Reason})
	}
	return out, nil
}

func TelemetryDownToPB(m msg.TelemetryInbound) (*pb.TelemetryDown, error) {
	switch v := m.(type) {
	case msg.TelemetryAck:
		return &pb.TelemetryDown{Msg: &pb.TelemetryDown_TelemetryAck{TelemetryAck: &pb.TelemetryAck{
			Envelope: envToPB(v.Envelope), StreamId: v.StreamID, HighWatermarkSeq: v.HighWatermarkSeq, CreditsGranted: v.CreditsGranted}}}, nil
	case msg.TelemetryCredit:
		return &pb.TelemetryDown{Msg: &pb.TelemetryDown_TelemetryCredit{TelemetryCredit: &pb.TelemetryCredit{
			Envelope: envToPB(v.Envelope), StreamId: v.StreamID, CreditsGranted: v.CreditsGranted}}}, nil
	case msg.TelemetryReject:
		return &pb.TelemetryDown{Msg: &pb.TelemetryDown_TelemetryReject{TelemetryReject: &pb.TelemetryReject{
			Envelope: envToPB(v.Envelope), StreamId: v.StreamID, Code: errToPB(v.Code), RetryAfter: durPtr(v.RetryAfter)}}}, nil
	}
	return nil, fmt.Errorf("codec: unknown telemetry message %T", m)
}

func TelemetryDownFromPB(p *pb.TelemetryDown) (msg.TelemetryInbound, error) {
	switch v := p.Msg.(type) {
	case *pb.TelemetryDown_TelemetryAck:
		a := v.TelemetryAck
		return msg.TelemetryAck{Envelope: envFromPB(a.Envelope), StreamID: a.StreamId, HighWatermarkSeq: a.HighWatermarkSeq, CreditsGranted: a.CreditsGranted}, nil
	case *pb.TelemetryDown_TelemetryCredit:
		c := v.TelemetryCredit
		return msg.TelemetryCredit{Envelope: envFromPB(c.Envelope), StreamID: c.StreamId, CreditsGranted: c.CreditsGranted}, nil
	case *pb.TelemetryDown_TelemetryReject:
		r := v.TelemetryReject
		return msg.TelemetryReject{Envelope: envFromPB(r.Envelope), StreamID: r.StreamId, Code: errFromPB(r.Code), RetryAfter: durPtrFrom(r.RetryAfter)}, nil
	}
	return nil, fmt.Errorf("codec: TelemetryDown carries nothing")
}
```

- [ ] **Step 7: Run the tests and watch them pass.**

Run: `cd go-agent && go vet ./... && go test ./internal/wire/... ./internal/msg/`
Expected: `ok` for each package.

- [ ] **Step 8: Commit.**

```bash
cd go-agent && git add proto internal/wire internal/msg
git commit -m "go-agent: transcribe the Telemetry stream messages into the DRAFT wire

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `wire.Conn.OpenTelemetry` and the fake gateway's `Telemetry` RPC

**Files:**
- Modify: `go-agent/internal/wire/client.go` (append)
- Create: `go-agent/internal/fakegw/telemetry.go`
- Modify: `go-agent/internal/fakegw/server.go`
  - `Config` gains `Out io.Writer`;
  - `Server` gains `tele`, `tstream`, `paused`, `rejectNext`, `outMu`;
  - `Start` initialises them;
  - add `env()`.
- Modify: `go-agent/internal/fakegw/session.go`
  - `session` gains `accepted chan struct{}`;
  - `Connect` initialises it;
  - `run()` sets live **before** `SessionAccept` and closes `accepted` after it;
  - `offer()` waits on `accepted`;
  - `limits()` carries the new fields.
- Modify: `go-agent/internal/fakegw/scenario.go`: `LimitsSpec` gains `MaxBatchBytes`, `InitialCredits`; `Policy` gains `Telemetry TelemetryPolicy`; defaults.
- Modify: `go-agent/internal/fakegw/server_test.go`: the hand-built session in `TestSessionCommandsAndPolicyConcurrentAccess` gets `accepted: closed()`.
- Test: `go-agent/internal/fakegw/telemetry_test.go`

**Interfaces:**
- Consumes: Task 1's `msg` telemetry types and codec functions.
- Produces:
  - `(*wire.Conn).OpenTelemetry(ctx) (*wire.TelemetryStream, error)`
  - `(*wire.TelemetryStream).Send(msg.TelemetryBatch) error`
  - `(*wire.TelemetryStream).Recv() (msg.TelemetryInbound, error)`
  - `(*wire.TelemetryStream).Close() error`
  - `fakegw.Config.Out io.Writer`
  - `(*fakegw.Server).PauseTelemetry()`, `(*fakegw.Server).ResumeTelemetry(n uint32)`
  - scenario `limits.max_batch_bytes`, `limits.initial_credits`, `policy.telemetry{credits_per_ack, reject_next}`
  - console output lines:
    - `telemetry <stream> seq=<n> <name>=<value>… -> acked`
    - `telemetry <stream> seq=<n> duplicate`
    - `telemetry <stream> seq=<n> -> rejected <CODE>`
    - `gap <stream> seq=<a>..<b> <reason>`

- [ ] **Step 1: Write the failing tests.** Create `internal/fakegw/telemetry_test.go`:

```go
package fakegw

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/wire"
)

type lockedBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

func telemetryServer(t *testing.T, mutate func(*Scenario)) (*Server, *lockedBuffer) {
	t.Helper()
	sc := DefaultScenario()
	if mutate != nil {
		mutate(sc)
	}
	out := &lockedBuffer{}
	s, err := Start(Config{Listen: "127.0.0.1:0", Tokens: []string{"tok"}, Scenario: sc, Out: out})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Stop)
	return s, out
}

// agentConn enrolls a fresh identity and dials; with open, it also completes
// SessionOpen → SessionAccept so the gateway holds a live session.
func agentConn(t *testing.T, s *Server, open bool) *wire.Conn {
	t.Helper()
	ctx := context.Background()
	id, err := identity.Load(store.MustOpen(t.TempDir()))
	if err != nil {
		t.Fatal(err)
	}
	csr, err := id.CSR()
	if err != nil {
		t.Fatal(err)
	}
	c := wire.New(id, s.CAPEM())
	resp, err := c.Enroll(ctx, s.Endpoint(), msg.EnrollRequest{Token: "tok", CSRDER: csr, AgentVersion: "t"})
	if err != nil {
		t.Fatal(err)
	}
	if err := id.StoreEnrollment(*resp); err != nil {
		t.Fatal(err)
	}
	conn, err := c.Dial(ctx, s.Endpoint())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if open {
		if err := conn.Send(msg.SessionOpen{Envelope: msg.Envelope{MessageID: "open"}, ProtocolVersion: 1}); err != nil {
			t.Fatal(err)
		}
		m, err := conn.Recv()
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := m.(msg.SessionAccept); !ok {
			t.Fatalf("want SessionAccept, got %T", m)
		}
	}
	return conn
}

func batch(seq uint64) msg.TelemetryBatch {
	return msg.TelemetryBatch{Envelope: msg.Envelope{MessageID: fmt.Sprintf("b%d", seq)}, StreamID: "binding-1", BatchSeq: seq,
		Samples: []msg.Sample{{SeriesKey: "cache.hit_rate", Type: msg.MetricGauge, Value: 0.93}}}
}

func openTelemetry(t *testing.T, conn *wire.Conn) *wire.TelemetryStream {
	t.Helper()
	ts, err := conn.OpenTelemetry(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ts.Close() })
	return ts
}

func recvAck(t *testing.T, ts *wire.TelemetryStream) msg.TelemetryAck {
	t.Helper()
	m, err := ts.Recv()
	if err != nil {
		t.Fatal(err)
	}
	a, ok := m.(msg.TelemetryAck)
	if !ok {
		t.Fatalf("want TelemetryAck, got %T %+v", m, m)
	}
	return a
}

func sendBatch(t *testing.T, ts *wire.TelemetryStream, seq uint64) {
	t.Helper()
	if err := ts.Send(batch(seq)); err != nil {
		t.Fatal(err)
	}
}

func TestTelemetryAcksCommitsAndDedupesReplays(t *testing.T) {
	s, out := telemetryServer(t, nil)
	ts := openTelemetry(t, agentConn(t, s, true))

	sendBatch(t, ts, 1)
	if a := recvAck(t, ts); a.StreamID != "binding-1" || a.HighWatermarkSeq != 1 || a.CreditsGranted != 1 {
		t.Fatalf("first ack %+v", a)
	}
	sendBatch(t, ts, 1) // a replay: acked at the watermark, not recorded again
	if a := recvAck(t, ts); a.HighWatermarkSeq != 1 {
		t.Fatalf("replay ack %+v", a)
	}
	sendBatch(t, ts, 2)
	if a := recvAck(t, ts); a.HighWatermarkSeq != 2 {
		t.Fatalf("second ack %+v", a)
	}
	for _, want := range []string{
		"telemetry binding-1 seq=1 cache.hit_rate=0.93 -> acked",
		"telemetry binding-1 seq=1 duplicate",
		"telemetry binding-1 seq=2 cache.hit_rate=0.93 -> acked",
	} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("want %q in output:\n%s", want, out.String())
		}
	}
}

func TestTelemetryReopenAcksTheWatermarkFirst(t *testing.T) {
	s, _ := telemetryServer(t, nil)
	conn := agentConn(t, s, true)
	ts := openTelemetry(t, conn)
	sendBatch(t, ts, 1)
	recvAck(t, ts)
	sendBatch(t, ts, 2)
	recvAck(t, ts)
	_ = ts.Close()

	ts2 := openTelemetry(t, conn) // gateway §4.13 (b): the durable watermark comes first
	if a := recvAck(t, ts2); a.StreamID != "binding-1" || a.HighWatermarkSeq != 2 || a.CreditsGranted != 0 {
		t.Fatalf("watermark ack on reopen %+v", a)
	}
}

func TestTelemetryRefusedWithoutAnAcceptedSession(t *testing.T) {
	s, _ := telemetryServer(t, nil)
	ts := openTelemetry(t, agentConn(t, s, false))
	_, err := ts.Recv()
	var pe *msg.ProtocolError
	if !errors.As(err, &pe) || pe.Code != msg.ErrUnauthenticatedIdentity {
		t.Fatalf("want UNAUTHENTICATED_IDENTITY, got %v", err)
	}
}

func TestTelemetryPauseWithholdsCreditsAndResumeGrants(t *testing.T) {
	s, _ := telemetryServer(t, nil)
	ts := openTelemetry(t, agentConn(t, s, true))
	s.PauseTelemetry()
	sendBatch(t, ts, 1)
	if a := recvAck(t, ts); a.CreditsGranted != 0 {
		t.Fatalf("paused ack granted %d credits", a.CreditsGranted)
	}
	s.ResumeTelemetry(4)
	m, err := ts.Recv()
	if c, ok := m.(msg.TelemetryCredit); err != nil || !ok || c.CreditsGranted != 4 {
		t.Fatalf("want TelemetryCredit{4}, got %T %+v %v", m, m, err)
	}
	sendBatch(t, ts, 2)
	if a := recvAck(t, ts); a.CreditsGranted != 1 {
		t.Fatalf("resumed ack granted %d credits", a.CreditsGranted)
	}
}

func TestTelemetryRejectNextRejectsOnceWithoutCommitting(t *testing.T) {
	s, out := telemetryServer(t, func(sc *Scenario) { sc.Policy.Telemetry.RejectNext = "UNKNOWN_REPLICA" })
	ts := openTelemetry(t, agentConn(t, s, true))
	sendBatch(t, ts, 1)
	m, err := ts.Recv()
	if r, ok := m.(msg.TelemetryReject); err != nil || !ok || r.Code != msg.ErrUnknownReplica || r.StreamID != "binding-1" {
		t.Fatalf("want TelemetryReject{UNKNOWN_REPLICA}, got %T %+v %v", m, m, err)
	}
	sendBatch(t, ts, 1) // not committed by the reject, so this is new, not a duplicate
	if a := recvAck(t, ts); a.HighWatermarkSeq != 1 {
		t.Fatalf("ack after reject %+v", a)
	}
	if strings.Contains(out.String(), "duplicate") {
		t.Fatalf("a rejected batch must not advance the watermark:\n%s", out.String())
	}
}
```

- [ ] **Step 2: Run them and watch them fail to compile.**

Run: `cd go-agent && go test ./internal/fakegw/ -run Telemetry`
Expected: build failure — `conn.OpenTelemetry undefined`, `unknown field Out`, `s.PauseTelemetry undefined`, `sc.Policy.Telemetry undefined`.

- [ ] **Step 3: Add the client stream.** Append to `internal/wire/client.go`:

```go
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
```

- [ ] **Step 4: Add the scenario fields.** In `internal/fakegw/scenario.go`:
  - add to `LimitsSpec`:

    ```go
	MaxBatchBytes     uint64        `yaml:"max_batch_bytes"`
	InitialCredits    uint32        `yaml:"initial_credits"`
    ```

  - add a type:

    ```go
type TelemetryPolicy struct {
	CreditsPerAck uint32 `yaml:"credits_per_ack"`
	RejectNext    string `yaml:"reject_next"` // an ErrorCode; the next batch is rejected with it, once
}
    ```

  - add to `Policy`:

    ```go
	Telemetry TelemetryPolicy `yaml:"telemetry"`
    ```

  - in `DefaultScenario`, the `Limits` literal gains `MaxBatchBytes: 65536, InitialCredits: 4,` (test values, not measured ones), and the `Policy` literal gains `Telemetry: TelemetryPolicy{CreditsPerAck: 1},`.

- [ ] **Step 5: Carry the limits, and mark the session live before accepting it.** In `internal/fakegw/session.go`:
  - `limits()` returns the two new fields:

    ```go
	return msg.Limits{MaxMessageBytes: l.MaxMessageBytes, MaxBatchBytes: l.MaxBatchBytes, InitialCredits: l.InitialCredits,
		HeartbeatInterval: l.HeartbeatInterval, KeepaliveInterval: l.KeepaliveInterval, SkewTolerance: l.SkewTolerance, ReportInterval: l.ReportInterval}
    ```

  - add to the `session` struct, after `done`:

    ```go
	// accepted is closed once SessionAccept is on the stream. The session is
	// live (Offer, Telemetry) a moment before that, so an agent that opens
	// Telemetry straight after SessionAccept is never refused; an offer
	// waits here so it can never overtake SessionAccept.
	accepted chan struct{}
    ```

  - in `Connect`, the `session` literal gains `accepted: make(chan struct{}),`.
  - in `run()`, replace the four lines that send `SessionAccept` and then call `setLive`/`clearLive` with:

    ```go
	ss.s.setLive(ss)
	defer ss.s.clearLive(ss)
	ss.send(msg.SessionAccept{Envelope: ss.env(), SessionID: "sess-" + ss.s.newID()[:8], AssignedTargetID: ss.sc.Lease.TargetID,
		RequiredCapabilities: nil, Lease: *lease, Limits: ss.limits(), ServerClock: now})
	close(ss.accepted)
    ```

  - in `offer()`, make the first line:

    ```go
	<-ss.accepted
    ```

- [ ] **Step 6: Fix the hand-built session in `server_test.go`.** In `TestSessionCommandsAndPolicyConcurrentAccess`, the `session` literal gains `accepted: closed(),`. Add to the same file:

```go
func closed() chan struct{} {
	c := make(chan struct{})
	close(c)
	return c
}
```

- [ ] **Step 7: Server fields.** In `internal/fakegw/server.go`:
  - `Config` gains:

    ```go
	Out      io.Writer // telemetry lines; nil discards
    ```

  - `Server` gains, after `gens`:

    ```go
	tele       map[string]map[string]uint64 // agent_id → stream → watermark: the DP's telemetry_ingest_watermark (§6.2)
	tstream    *teleStream                  // the live Telemetry stream, for unsolicited credits
	paused     bool
	rejectNext string
	outMu      sync.Mutex
    ```

  - the `Server` literal in `Start` gains `tele: map[string]map[string]uint64{}, rejectNext: cfg.Scenario.Policy.Telemetry.RejectNext,`.
  - add next to `newID`:

    ```go
func (s *Server) env() msg.Envelope { return msg.Envelope{MessageID: s.newID(), SentAt: time.Now()} }
    ```

  - add `"io"` to the imports.

- [ ] **Step 8: Serve the stream.** Create `internal/fakegw/telemetry.go`:

```go
package fakegw

import (
	"fmt"
	"maps"
	"slices"
	"strconv"
	"strings"
	"sync"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

type teleStream struct {
	mu     sync.Mutex
	stream pb.AgentGateway_TelemetryServer
	s      *Server
}

func (t *teleStream) send(m msg.TelemetryInbound) {
	p, err := codec.TelemetryDownToPB(m)
	if err != nil {
		t.s.log.Error("fakegw: encode telemetry", "err", err)
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.s.tr.add("out", m)
	_ = t.stream.Send(p)
}

// Telemetry serves gateway §4.10: one credit per batch, an ack after
// "commit", and a per-stream watermark that makes a replay idempotent the way
// the DP's telemetry_ingest_watermark does (§6.2).
func (s *Server) Telemetry(stream pb.AgentGateway_TelemetryServer) error {
	ag, err := s.agentFor(stream.Context())
	if err != nil {
		return codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: err.Error()})
	}
	s.mu.Lock()
	if s.live == nil || s.live.ag != ag {
		s.mu.Unlock()
		return codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: "no accepted session"})
	}
	ts := &teleStream{stream: stream, s: s}
	s.tstream = ts
	marks := maps.Clone(s.tele[ag.agentID])
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.tstream == ts {
			s.tstream = nil
		}
		s.mu.Unlock()
	}()

	// §4.13 (b): the durable watermark first, so the agent trims before resending.
	for _, id := range slices.Sorted(maps.Keys(marks)) {
		ts.send(msg.TelemetryAck{Envelope: s.env(), StreamID: id, HighWatermarkSeq: marks[id]})
	}
	for {
		p, err := stream.Recv()
		if err != nil {
			return nil
		}
		b, err := codec.TelemetryUpFromPB(p)
		if err != nil {
			s.log.Warn("fakegw: undecodable telemetry", "err", err)
			continue
		}
		s.tr.add("in", b)
		s.batch(ag, ts, b)
	}
}

func (s *Server) batch(ag *enrolledAgent, ts *teleStream, b msg.TelemetryBatch) {
	s.mu.Lock()
	if code := s.rejectNext; code != "" {
		s.rejectNext = ""
		s.mu.Unlock()
		s.printf("telemetry %s seq=%d -> rejected %s", b.StreamID, b.BatchSeq, code)
		ts.send(msg.TelemetryReject{Envelope: s.env(), StreamID: b.StreamID, Code: msg.ErrorCode(code)})
		return
	}
	if s.tele[ag.agentID] == nil {
		s.tele[ag.agentID] = map[string]uint64{}
	}
	wm := s.tele[ag.agentID][b.StreamID]
	dup := b.BatchSeq <= wm
	if !dup {
		s.tele[ag.agentID][b.StreamID] = b.BatchSeq
		wm = b.BatchSeq
	}
	credits := s.cfg.Scenario.Policy.Telemetry.CreditsPerAck
	if s.paused {
		credits = 0
	}
	s.mu.Unlock()
	if dup {
		s.printf("telemetry %s seq=%d duplicate", b.StreamID, b.BatchSeq)
	} else {
		for _, g := range b.GapMarkers {
			s.printf("gap %s seq=%d..%d %s", b.StreamID, g.FromSeq, g.ToSeq, g.Reason)
		}
		s.printf("telemetry %s seq=%d%s -> acked", b.StreamID, b.BatchSeq, values(b.Samples))
	}
	ts.send(msg.TelemetryAck{Envelope: s.env(), StreamID: b.StreamID, HighWatermarkSeq: wm, CreditsGranted: credits})
}

func values(samples []msg.Sample) string {
	var sb strings.Builder
	for _, x := range samples {
		fmt.Fprintf(&sb, " %s=%s", x.SeriesKey, strconv.FormatFloat(x.Value, 'g', -1, 64))
	}
	return sb.String()
}

func (s *Server) printf(format string, a ...any) {
	if s.cfg.Out == nil {
		return
	}
	s.outMu.Lock()
	defer s.outMu.Unlock()
	fmt.Fprintf(s.cfg.Out, format+"\n", a...)
}

// PauseTelemetry makes every ack grant no credit, so the agent spends what it
// holds and then buffers on disk (AG-D9).
func (s *Server) PauseTelemetry() {
	s.mu.Lock()
	s.paused = true
	s.mu.Unlock()
}

// ResumeTelemetry grants n credits unsolicited — the §4.10 recovery path from
// zero — and returns acks to the scenario's credits_per_ack.
func (s *Server) ResumeTelemetry(n uint32) {
	s.mu.Lock()
	s.paused = false
	ts := s.tstream
	s.mu.Unlock()
	if ts != nil {
		ts.send(msg.TelemetryCredit{Envelope: s.env(), CreditsGranted: n})
	}
}
```

- [ ] **Step 9: Run the tests and watch them pass, race-clean.**

Run: `cd go-agent && go vet ./... && go test -race ./internal/fakegw/ ./internal/wire/...`
Expected: `ok`. Then run `go test -tags integration ./internal/agent/` to confirm the live-before-accept change broke no slice-1 or console scenario.

- [ ] **Step 10: Commit.**

```bash
cd go-agent && git add internal/wire internal/fakegw
git commit -m "go-agent: serve the Telemetry stream in fake-gateway; add the client stream

The fake gateway acks with credits, keeps a per-stream watermark so replays
are idempotent, can pause, resume and reject once, and prints one line per
batch. A session is now live before SessionAccept goes out, with offers
waiting on it, so an agent that opens Telemetry at once is never refused.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Console verbs `pause`, `resume`, `disconnect`

**Files:**
- Modify: `go-agent/internal/fakegw/console.go` (usage; the verb switch)
- Modify: `go-agent/internal/fakegw/session.go`: `session` gains `kick chan struct{}`, `kickOnce sync.Once`; `Connect` initialises `kick`; both `select` loops in `run()` gain `case <-ss.kick: return nil`.
- Modify: `go-agent/internal/fakegw/server.go`: add `Disconnect()`.
- Test: `go-agent/internal/fakegw/console_test.go` (append), `go-agent/internal/agent/console_integration_test.go` (append)

**Interfaces:**
- Consumes: Task 2's `PauseTelemetry`, `ResumeTelemetry(n)`; slice-1 `ErrNoAgent`.
- Produces: `(*fakegw.Server).Disconnect() error`, and the console verbs:

  | Verb | Prints |
  |---|---|
  | `pause` | `telemetry paused: acks grant no credits` |
  | `resume` | `telemetry resumed: <n> credits granted` |
  | `disconnect` | `session closed; the agent will reconnect` |

- [ ] **Step 1: Write the failing tests.** Append to `internal/fakegw/console_test.go`:

```go
func TestConsoleTelemetryVerbsWithoutAnAgent(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	for line, want := range map[string]string{
		"pause":      "telemetry paused: acks grant no credits",
		"resume":     "telemetry resumed: 4 credits granted",
		"disconnect": "no agent connected",
	} {
		var out bytes.Buffer
		s.Console(strings.NewReader(line+"\n"), &out)
		if !strings.Contains(out.String(), want) {
			t.Errorf("%q: want %q, got %q", line, want, out.String())
		}
	}
}
```

Append to `internal/agent/console_integration_test.go`:

```go
// disconnect drops the session while the gateway keeps its CA and enrolment,
// so the agent reconnects with the identity it has — unlike a gateway restart.
func TestIntegrationConsoleDisconnectKeepsEnrolment(t *testing.T) {
	gw := startGateway(t, nil)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	out := &syncBuffer{}
	gw.Console(strings.NewReader("disconnect\n"), out)
	waitOutput(t, out, "session closed; the agent will reconnect", 5*time.Second)
	deadline := time.Now().Add(30 * time.Second)
	for gw.Sessions() < 2 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if gw.Sessions() < 2 {
		t.Fatalf("agent did not reconnect: sessions=%d", gw.Sessions())
	}
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if n := gw.Count("EnrollRequest"); n != 1 {
		t.Fatalf("a disconnect must not force re-enrolment: EnrollRequest=%d", n)
	}
}
```

- [ ] **Step 2: Run them and watch them fail.**

Run: `cd go-agent && go test ./internal/fakegw/ -run ConsoleTelemetryVerbs && go test -tags integration ./internal/agent/ -run ConsoleDisconnect`
Expected: FAIL — the verbs print `unknown command "pause"`; the integration test times out on `session closed`.

- [ ] **Step 3: Add the kick.** In `internal/fakegw/session.go`:
  - `session` gains:

    ```go
	kick     chan struct{} // closed by Server.Disconnect: end this stream, keep the enrolment
	kickOnce sync.Once
    ```

  - the `Connect` literal gains `kick: make(chan struct{}),`.
  - in `run()`, add `case <-ss.kick: return nil` to **both** `select` statements in the steady-state section: the outer one with `case <-stepDone`, and the inner one inside `case <-stepDone`.

  In `internal/fakegw/server.go`, add:

```go
// Disconnect ends the live agent's Connect stream. The CA and the enrolment
// survive, so the agent reconnects with its identity — a gateway restart
// cannot do that, because the CA lives in memory.
func (s *Server) Disconnect() error {
	s.mu.Lock()
	ss := s.live
	s.mu.Unlock()
	if ss == nil {
		return ErrNoAgent
	}
	ss.kickOnce.Do(func() { close(ss.kick) })
	return nil
}
```

- [ ] **Step 4: Add the verbs.** In `internal/fakegw/console.go`:

```go
const consoleUsage = "type: preflight | apply <file> | restart | stop | pause | resume | disconnect"
```

In `line()`, directly after the `if len(f) == 0 { return }` block:

```go
	switch f[0] {
	case "pause":
		c.s.PauseTelemetry()
		c.println("telemetry paused: acks grant no credits")
		return
	case "resume":
		n := max(c.s.cfg.Scenario.Limits.InitialCredits, 1)
		c.s.ResumeTelemetry(n)
		c.println("telemetry resumed: %d credits granted", n)
		return
	case "disconnect":
		if err := c.s.Disconnect(); err != nil {
			c.println("%v", err)
			return
		}
		c.println("session closed; the agent will reconnect")
		return
	}
```

- [ ] **Step 5: Run them and watch them pass.**

Run: `cd go-agent && go test -race ./internal/fakegw/ && go test -tags integration ./internal/agent/ -run Console`
Expected: `ok`, `ok`.

- [ ] **Step 6: Commit.**

```bash
cd go-agent && git add internal/fakegw internal/agent/console_integration_test.go
git commit -m "go-agent: fake-gateway console verbs pause, resume and disconnect

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `jsonptr` — RFC 6901 pointers and numbers

**Files:**
- Create: `go-agent/internal/telemetry/jsonptr/jsonptr.go`
- Test: `go-agent/internal/telemetry/jsonptr/jsonptr_test.go`

**Interfaces:**
- Produces:
  - `jsonptr.Valid(p string) bool`
  - `jsonptr.Resolve(doc any, p string) (any, error)`
  - `jsonptr.Number(v any) (float64, bool)`
  - `jsonptr.ErrNotFound`

- [ ] **Step 1: Write the failing test.** The document is RFC 6901 §5's example:

```go
package jsonptr

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

const rfcDoc = `{"foo": ["bar", "baz"], "": 0, "a/b": 1, "c%d": 2, "e^f": 3, "g|h": 4, "i\\j": 5, "k\"l": 6, " ": 7, "m~n": 8}`

func decode(t *testing.T, s string) any {
	t.Helper()
	dec := json.NewDecoder(strings.NewReader(s))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		t.Fatal(err)
	}
	return v
}

func TestResolvesTheRFC6901Examples(t *testing.T) {
	doc := decode(t, rfcDoc)
	for ptr, want := range map[string]float64{"/": 0, "/a~1b": 1, "/c%d": 2, "/e^f": 3, "/g|h": 4, "/i\\j": 5, "/k\"l": 6, "/ ": 7, "/m~0n": 8} {
		v, err := Resolve(doc, ptr)
		got, ok := Number(v)
		if err != nil || !ok || got != want {
			t.Errorf("%q: got %v %v %v, want %v", ptr, v, ok, err, want)
		}
	}
	if v, err := Resolve(doc, "/foo/0"); err != nil || v != "bar" {
		t.Errorf("/foo/0: %v %v", v, err)
	}
	if v, err := Resolve(doc, ""); err != nil || v == nil {
		t.Errorf(`"" must be the whole document: %v %v`, v, err)
	}
}

func TestMissingMembersAndBadIndexesAreNotFound(t *testing.T) {
	doc := decode(t, rfcDoc)
	for _, ptr := range []string{"/x", "/foo/2", "/foo/01", "/foo/-", "/foo/0/x"} {
		if _, err := Resolve(doc, ptr); !errors.Is(err, ErrNotFound) {
			t.Errorf("%q: want ErrNotFound, got %v", ptr, err)
		}
	}
}

func TestValid(t *testing.T) {
	for p, want := range map[string]bool{"": true, "/": true, "/a~0b~1c": true, "a": false, "/~2": false, "/~": false} {
		if Valid(p) != want {
			t.Errorf("Valid(%q) = %v, want %v", p, !want, want)
		}
	}
}

func TestNumberAcceptsOnlyJSONNumbers(t *testing.T) {
	for _, v := range []any{"1.5", true, nil, map[string]any{}} {
		if _, ok := Number(v); ok {
			t.Errorf("Number(%#v) must not be a number", v)
		}
	}
	if f, ok := Number(json.Number("1.5")); !ok || f != 1.5 {
		t.Errorf("json.Number(1.5) → %v %v", f, ok)
	}
}
```

- [ ] **Step 2: Run it and watch it fail.**

Run: `cd go-agent && go test ./internal/telemetry/jsonptr/`
Expected: build failure, `undefined: Resolve`.

- [ ] **Step 3: Implement.**

```go
// Package jsonptr resolves RFC 6901 JSON Pointers over a document decoded by
// encoding/json with UseNumber — the package schema's source.pointer (UI-D14).
package jsonptr

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
)

var ErrNotFound = errors.New("jsonptr: no such member")

// Valid reports whether p is a JSON Pointer: "" (the whole document), or
// "/"-prefixed tokens in which every "~" is followed by "0" or "1".
func Valid(p string) bool {
	if p == "" {
		return true
	}
	if p[0] != '/' {
		return false
	}
	for i := 0; i < len(p); i++ {
		if p[i] == '~' && (i+1 >= len(p) || (p[i+1] != '0' && p[i+1] != '1')) {
			return false
		}
	}
	return true
}

func Resolve(doc any, p string) (any, error) {
	if !Valid(p) {
		return nil, fmt.Errorf("jsonptr: invalid pointer %q", p)
	}
	if p == "" {
		return doc, nil
	}
	cur := doc
	for _, raw := range strings.Split(p[1:], "/") {
		tok := strings.ReplaceAll(strings.ReplaceAll(raw, "~1", "/"), "~0", "~") // RFC 6901 §4 order
		switch v := cur.(type) {
		case map[string]any:
			next, ok := v[tok]
			if !ok {
				return nil, fmt.Errorf("%w: %q", ErrNotFound, tok)
			}
			cur = next
		case []any:
			i, ok := index(tok, len(v))
			if !ok {
				return nil, fmt.Errorf("%w: index %q", ErrNotFound, tok)
			}
			cur = v[i]
		default:
			return nil, fmt.Errorf("%w: %q is below a scalar", ErrNotFound, tok)
		}
	}
	return cur, nil
}

// index accepts RFC 6901 array indexes: "0", or digits without a leading
// zero. "-" names the element after the last, which a read cannot resolve.
func index(tok string, n int) (int, bool) {
	if tok == "" || (len(tok) > 1 && tok[0] == '0') {
		return 0, false
	}
	for _, c := range tok {
		if c < '0' || c > '9' {
			return 0, false
		}
	}
	i, err := strconv.Atoi(tok)
	return i, err == nil && i < n
}

// Number reports a JSON number as a float64. A string, bool, null, object or
// array is not one — and the collector emits no sample for it, never a zero.
func Number(v any) (float64, bool) {
	n, ok := v.(json.Number)
	if !ok {
		return 0, false
	}
	f, err := n.Float64()
	return f, err == nil
}
```

- [ ] **Step 4: Run it and watch it pass.**

Run: `cd go-agent && go test ./internal/telemetry/jsonptr/`
Expected: `ok`.

- [ ] **Step 5: Commit.**

```bash
cd go-agent && git add internal/telemetry/jsonptr
git commit -m "go-agent: RFC 6901 JSON Pointer resolution for the http_json collector

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `declare` — the interim declarations file (`AG-D22`)

**Files:**
- Create: `go-agent/internal/telemetry/declare/declare.go`
- Test: `go-agent/internal/telemetry/declare/declare_test.go`

**Interfaces:**
- Consumes: `jsonptr.Valid`.
- Produces:
  - `declare.File{SchemaVersion int; Bindings map[string]Binding}`
  - `declare.Binding{Interval, Timeout time.Duration; Metrics []Metric}`
  - `declare.Metric{Name, MetricType, Unit string; When *string; Source Source}`
  - `declare.Source{Kind string; Port int; Path string; Pointer *string; OnUnavailable string}`
  - `declare.Parse(io.Reader) (*File, error)`, `declare.Load(path string) (*File, error)`

- [ ] **Step 1: Write the failing test.**

```go
package declare

import (
	"strings"
	"testing"
	"time"
)

const valid = `schema_version: 1
bindings:
  binding-1:
    interval: 15s
    metrics:
      - name: cache.hit_rate
        metricType: gauge
        unit: ratio
        source: {kind: http_json, port: 8081, path: /api/cache/stats, pointer: /hitRate, onUnavailable: degrade}
`

func TestParseAcceptsTheSpecExampleAndDefaultsTheTimeout(t *testing.T) {
	f, err := Parse(strings.NewReader(valid))
	if err != nil {
		t.Fatal(err)
	}
	b := f.Bindings["binding-1"]
	if b.Interval != 15*time.Second || b.Timeout != 5*time.Second {
		t.Fatalf("interval %v timeout %v", b.Interval, b.Timeout)
	}
	m := b.Metrics[0]
	if m.Name != "cache.hit_rate" || m.MetricType != "gauge" || m.Unit != "ratio" || m.Source.Port != 8081 ||
		m.Source.Path != "/api/cache/stats" || *m.Source.Pointer != "/hitRate" || m.Source.OnUnavailable != "degrade" {
		t.Fatalf("%+v", m)
	}
}

func TestParseRefuses(t *testing.T) {
	for _, tc := range []struct{ name, from, to, want string }{
		{"schema version", "schema_version: 1", "schema_version: 2", "schema_version 2"},
		{"unsafe binding id", "binding-1:", "binding/1:", "must match"},
		{"interval below 1s", "interval: 15s", "interval: 500ms", "at least 1s"},
		{"timeout above interval", "interval: 15s", "interval: 15s\n    timeout: 20s", "timeout must be between 0 and interval"},
		{"histogram", "metricType: gauge", "metricType: histogram", "histogram is not in this slice"},
		{"unknown metric type", "metricType: gauge", "metricType: summary", "counter or gauge"},
		{"missing unit", "        unit: ratio\n", "", "unit is required"},
		{"when", "unit: ratio", "unit: ratio\n        when: \"config.provider == 'caffeine'\"", "when is not in this slice"},
		{"another schema kind", "kind: http_json", "kind: prometheus", "kind prometheus is not in this slice"},
		{"not a schema kind", "kind: http_json", "kind: graphite", "not a schema source kind"},
		{"port", "port: 8081", "port: 70000", "out of range"},
		{"path", "path: /api/cache/stats", "path: api/cache/stats", "must start with /"},
		{"pointer missing", "pointer: /hitRate, ", "", "pointer is required"},
		{"pointer invalid", "pointer: /hitRate", "pointer: hitRate", "not a JSON Pointer"},
		{"onUnavailable", "onUnavailable: degrade", "onUnavailable: zero", "degrade or error"},
		{"unknown key", "unit: ratio", "unit: ratio\n        units: ratio", "field units not found"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if !strings.Contains(valid, tc.from) {
				t.Fatalf("fixture lacks %q", tc.from)
			}
			_, err := Parse(strings.NewReader(strings.Replace(valid, tc.from, tc.to, 1)))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("want error containing %q, got %v", tc.want, err)
			}
		})
	}
}

func TestParseRefusesADuplicateNameInOneBinding(t *testing.T) {
	dup := valid + "      - name: cache.hit_rate\n        metricType: gauge\n        unit: ratio\n" +
		"        source: {kind: http_json, port: 8081, path: /x, pointer: /y, onUnavailable: degrade}\n"
	if _, err := Parse(strings.NewReader(dup)); err == nil || !strings.Contains(err.Error(), `duplicate name "cache.hit_rate"`) {
		t.Fatalf("got %v", err)
	}
}

func TestErrorsNameTheBindingAndIndex(t *testing.T) {
	_, err := Parse(strings.NewReader(strings.Replace(valid, "port: 8081", "port: 0", 1)))
	if err == nil || !strings.Contains(err.Error(), `binding "binding-1" metrics[0]`) {
		t.Fatalf("got %v", err)
	}
}
```

- [ ] **Step 2: Run it and watch it fail.**

Run: `cd go-agent && go test ./internal/telemetry/declare/`
Expected: build failure, `undefined: Parse`.

- [ ] **Step 3: Implement.**

```go
// Package declare reads the agent's interim metric declarations (AG-D22):
// per binding, entries shaped like the Solution Package schema's metric
// object (contracts/solution-package.schema.json), restricted to what this
// slice collects. The plane does not deliver declarations yet (AG-14).
package declare

import (
	"errors"
	"fmt"
	"io"
	"maps"
	"os"
	"regexp"
	"slices"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"github.com/solace/solution-manager/go-agent/internal/telemetry/jsonptr"
)

type File struct {
	SchemaVersion int                `yaml:"schema_version"`
	Bindings      map[string]Binding `yaml:"bindings"`
}

type Binding struct {
	Interval time.Duration `yaml:"interval"`
	Timeout  time.Duration `yaml:"timeout"` // default min(interval, 5s)
	Metrics  []Metric      `yaml:"metrics"`
}

// Metric mirrors the schema's metric object: name, metricType, unit and
// source are required there, so they are required here. unit is not sent —
// the DP normalizes units from the declared contract — but an entry without
// it would not survive the move to a plane-delivered source.
type Metric struct {
	Name       string  `yaml:"name"`
	MetricType string  `yaml:"metricType"`
	Unit       string  `yaml:"unit"`
	When       *string `yaml:"when"` // refused in this slice
	Source     Source  `yaml:"source"`
}

type Source struct {
	Kind          string  `yaml:"kind"`
	Port          int     `yaml:"port"`
	Path          string  `yaml:"path"`
	Pointer       *string `yaml:"pointer"` // required; "" is the whole document
	OnUnavailable string  `yaml:"onUnavailable"`
}

// safeID: a binding id names a buffer directory.
var safeID = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

var laterKinds = map[string]bool{"prometheus": true, "solace_event": true, "exec": true, "jdbc_query": true}

func Load(path string) (*File, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("declarations: %w", err)
	}
	defer f.Close()
	return Parse(f)
}

// Parse decodes strictly — an unknown key is an error, as the schema's
// additionalProperties: false makes it — then validates everything.
func Parse(r io.Reader) (*File, error) {
	dec := yaml.NewDecoder(r)
	dec.KnownFields(true)
	var f File
	if err := dec.Decode(&f); err != nil {
		return nil, fmt.Errorf("declarations: %w", err)
	}
	if err := f.validate(); err != nil {
		return nil, err
	}
	for id, b := range f.Bindings {
		if b.Timeout == 0 {
			b.Timeout = min(b.Interval, 5*time.Second)
			f.Bindings[id] = b
		}
	}
	return &f, nil
}

func (f *File) validate() error {
	if f.SchemaVersion != 1 {
		return fmt.Errorf("declarations: schema_version %d, want 1", f.SchemaVersion)
	}
	if len(f.Bindings) == 0 {
		return errors.New("declarations: no bindings")
	}
	for _, id := range slices.Sorted(maps.Keys(f.Bindings)) {
		b := f.Bindings[id]
		where := fmt.Sprintf("declarations: binding %q", id)
		switch {
		case !safeID.MatchString(id):
			return fmt.Errorf("%s: id must match %s", where, safeID)
		case b.Interval < time.Second:
			return fmt.Errorf("%s: interval must be at least 1s", where)
		case b.Timeout < 0 || b.Timeout > b.Interval:
			return fmt.Errorf("%s: timeout must be between 0 and interval", where)
		case len(b.Metrics) == 0:
			return fmt.Errorf("%s: no metrics", where)
		}
		seen := map[string]bool{}
		for i, m := range b.Metrics {
			at := fmt.Sprintf("%s metrics[%d]", where, i)
			switch {
			case m.Name == "":
				return fmt.Errorf("%s: name is required", at)
			case seen[m.Name]:
				return fmt.Errorf("%s: duplicate name %q", at, m.Name)
			case m.MetricType == "histogram":
				return fmt.Errorf("%s: metricType histogram is not in this slice", at)
			case m.MetricType != "counter" && m.MetricType != "gauge":
				return fmt.Errorf("%s: metricType must be counter or gauge", at)
			case m.Unit == "":
				return fmt.Errorf("%s: unit is required", at)
			case m.When != nil:
				return fmt.Errorf("%s: when is not in this slice", at)
			}
			seen[m.Name] = true
			if err := m.Source.validate(); err != nil {
				return fmt.Errorf("%s: source: %w", at, err)
			}
		}
	}
	return nil
}

func (s Source) validate() error {
	switch {
	case laterKinds[s.Kind]:
		return fmt.Errorf("kind %s is not in this slice", s.Kind)
	case s.Kind != "http_json":
		return fmt.Errorf("kind %q is not a schema source kind", s.Kind)
	case s.Port < 1 || s.Port > 65535:
		return fmt.Errorf("port %d out of range", s.Port)
	case !strings.HasPrefix(s.Path, "/"):
		return fmt.Errorf("path %q must start with /", s.Path)
	case s.Pointer == nil:
		return errors.New("pointer is required")
	case !jsonptr.Valid(*s.Pointer):
		return fmt.Errorf("pointer %q is not a JSON Pointer (RFC 6901)", *s.Pointer)
	case s.OnUnavailable != "degrade" && s.OnUnavailable != "error":
		return errors.New("onUnavailable must be degrade or error")
	}
	return nil
}
```

- [ ] **Step 4: Run it and watch it pass.**

Run: `cd go-agent && go test ./internal/telemetry/declare/`
Expected: `ok`. If the `unknown key` case's message differs in this `yaml.v3` version, read the actual error and change only that case's `want` to its stable fragment — the test must still fail without `KnownFields(true)`.

- [ ] **Step 5: Commit.**

```bash
cd go-agent && git add internal/telemetry/declare
git commit -m "go-agent: parse and validate the interim metric declarations file (AG-D22)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `tmetrics` — the pipeline's metrics

**Files:**
- Create: `go-agent/internal/telemetry/tmetrics/tmetrics.go`
- Test: `go-agent/internal/telemetry/tmetrics/tmetrics_test.go`

**Interfaces:**
- Produces:
  - `tmetrics.New() *Metrics` and `(*Metrics).Collectors() []prometheus.Collector`
  - `(*Metrics).BufferGauges(stats func() (int64, int, time.Time), limitBytes int64, limitAge time.Duration, now func() time.Time)`
  - nil-safe helpers:

    | Helper | Signature |
    |---|---|
    | `Drop` | `(reason string, samples int)` |
    | `Unavail` | `(binding, metric string)` |
    | `ScrapeError` | `(binding, reason string)` |
    | `Panic` | `(binding string)` |
    | `WriteError` | `()` |
    | `Sent` | `()` |
    | `AckedBatches` | `(n int)` |
    | `Reject` | `(code string)` |
    | `SetCredits` | `(n uint32)` |

  - exported vectors, for tests: `DroppedBatches`, `DroppedSamples`, `Unavailable`, `ScrapeErrors`, `CollectorPanics`, `WriteErrors`, `BatchesSent`, `BatchesAcked`, `Rejects`, `Credits`.

- [ ] **Step 1: Write the failing test.**

```go
package tmetrics

import (
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestHelpersCountWhatTheyName(t *testing.T) {
	m := New()
	m.BufferGauges(func() (int64, int, time.Time) { return 1234, 3, time.Unix(100, 0) }, 64<<20, 24*time.Hour, func() time.Time { return time.Unix(160, 0) })
	reg := prometheus.NewRegistry()
	reg.MustRegister(m.Collectors()...) // no duplicate names

	m.Drop("EXPIRED", 3)
	m.Unavail("binding-1", "cache.hit_rate")
	m.ScrapeError("binding-1", "status")
	m.Panic("binding-1")
	m.WriteError()
	m.Sent()
	m.AckedBatches(2)
	m.Reject("UNKNOWN_REPLICA")
	m.SetCredits(4)

	for name, c := range map[string]prometheus.Collector{
		"dropped batches": m.DroppedBatches.WithLabelValues("EXPIRED"),
		"unavailable":     m.Unavailable.WithLabelValues("binding-1", "cache.hit_rate"),
		"scrape errors":   m.ScrapeErrors.WithLabelValues("binding-1", "status"),
		"panics":          m.CollectorPanics.WithLabelValues("binding-1"),
		"write errors":    m.WriteErrors,
		"sent":            m.BatchesSent,
		"rejects":         m.Rejects.WithLabelValues("UNKNOWN_REPLICA"),
	} {
		if v := testutil.ToFloat64(c); v != 1 {
			t.Errorf("%s = %v, want 1", name, v)
		}
	}
	if v := testutil.ToFloat64(m.DroppedSamples.WithLabelValues("EXPIRED")); v != 3 {
		t.Errorf("dropped samples = %v, want 3", v)
	}
	if v := testutil.ToFloat64(m.BatchesAcked); v != 2 {
		t.Errorf("acked = %v, want 2", v)
	}
	if v := testutil.ToFloat64(m.Credits); v != 4 {
		t.Errorf("credits = %v, want 4", v)
	}
	if n, err := testutil.GatherAndCount(reg, "sm_agent_telemetry_buffer_oldest_age_seconds"); err != nil || n != 1 {
		t.Errorf("age gauge: %d %v", n, err)
	}
}

func TestANilMetricsIsANoOp(t *testing.T) {
	var m *Metrics
	m.Drop("EXPIRED", 1)
	m.Unavail("b", "m")
	m.ScrapeError("b", "r")
	m.Panic("b")
	m.WriteError()
	m.Sent()
	m.AckedBatches(1)
	m.Reject("X")
	m.SetCredits(1)
}
```

- [ ] **Step 2: Run it and watch it fail.**

Run: `cd go-agent && go test ./internal/telemetry/tmetrics/`
Expected: build failure, `undefined: New`.

- [ ] **Step 3: Implement.**

```go
// Package tmetrics holds the telemetry pipeline's Prometheus metrics, shared
// by the buffer's callbacks, the collectors and the sender. Every helper is a
// no-op on a nil *Metrics, so unit tests may pass none.
package tmetrics

import (
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

type Metrics struct {
	DroppedBatches  *prometheus.CounterVec
	DroppedSamples  *prometheus.CounterVec
	Unavailable     *prometheus.CounterVec
	ScrapeErrors    *prometheus.CounterVec
	CollectorPanics *prometheus.CounterVec
	WriteErrors     prometheus.Counter
	BatchesSent     prometheus.Counter
	BatchesAcked    prometheus.Counter
	Rejects         *prometheus.CounterVec
	Credits         prometheus.Gauge
	all             []prometheus.Collector
}

func counterVec(name, help string, labels ...string) *prometheus.CounterVec {
	return prometheus.NewCounterVec(prometheus.CounterOpts{Name: name, Help: help}, labels)
}

func New() *Metrics {
	m := &Metrics{
		DroppedBatches:  counterVec("sm_agent_telemetry_dropped_batches_total", "Batches dropped from the buffer, by reason (AG-D9).", "reason"),
		DroppedSamples:  counterVec("sm_agent_telemetry_dropped_samples_total", "Samples in dropped batches, by reason (AG-D9).", "reason"),
		Unavailable:     counterVec("sm_agent_telemetry_unavailable_total", "Declared metrics that yielded no number; no sample was emitted.", "binding", "metric"),
		ScrapeErrors:    counterVec("sm_agent_telemetry_scrape_errors_total", "Failed endpoint fetches, by reason.", "binding", "reason"),
		CollectorPanics: counterVec("sm_agent_telemetry_collector_panics_total", "Collector ticks that panicked and were recovered.", "binding"),
		WriteErrors:     prometheus.NewCounter(prometheus.CounterOpts{Name: "sm_agent_telemetry_buffer_write_errors_total", Help: "Buffer writes that failed; the batch was dropped (AG-D25)."}),
		BatchesSent:     prometheus.NewCounter(prometheus.CounterOpts{Name: "sm_agent_telemetry_batches_sent_total", Help: "TelemetryBatch messages sent."}),
		BatchesAcked:    prometheus.NewCounter(prometheus.CounterOpts{Name: "sm_agent_telemetry_batches_acked_total", Help: "In-flight batches released by a TelemetryAck."}),
		Rejects:         counterVec("sm_agent_telemetry_rejects_total", "TelemetryReject messages received, by code.", "code"),
		Credits:         prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_telemetry_credits", Help: "Telemetry credits held."}),
	}
	m.all = []prometheus.Collector{m.DroppedBatches, m.DroppedSamples, m.Unavailable, m.ScrapeErrors, m.CollectorPanics,
		m.WriteErrors, m.BatchesSent, m.BatchesAcked, m.Rejects, m.Credits}
	return m
}

func (m *Metrics) Collectors() []prometheus.Collector { return m.all }

// BufferGauges exports the buffer's state and both AG-D9 caps — the decision
// requires both caps to be reported, not just enforced.
func (m *Metrics) BufferGauges(stats func() (bytes int64, batches int, oldest time.Time), limitBytes int64, limitAge time.Duration, now func() time.Time) {
	gauge := func(name, help string, f func() float64) prometheus.Collector {
		return prometheus.NewGaugeFunc(prometheus.GaugeOpts{Name: name, Help: help}, f)
	}
	m.all = append(m.all,
		gauge("sm_agent_telemetry_buffer_bytes", "Bytes held in the telemetry buffer.", func() float64 { b, _, _ := stats(); return float64(b) }),
		gauge("sm_agent_telemetry_buffer_batches", "Batches held in the telemetry buffer.", func() float64 { _, n, _ := stats(); return float64(n) }),
		gauge("sm_agent_telemetry_buffer_oldest_age_seconds", "Age of the oldest buffered batch.", func() float64 {
			_, _, o := stats()
			if o.IsZero() {
				return 0
			}
			return now().Sub(o).Seconds()
		}),
		gauge("sm_agent_telemetry_buffer_limit_bytes", "AG-D9 byte cap.", func() float64 { return float64(limitBytes) }),
		gauge("sm_agent_telemetry_buffer_limit_age_seconds", "AG-D9 time horizon.", func() float64 { return limitAge.Seconds() }),
	)
}

func (m *Metrics) Drop(reason string, samples int) {
	if m == nil {
		return
	}
	m.DroppedBatches.WithLabelValues(reason).Inc()
	m.DroppedSamples.WithLabelValues(reason).Add(float64(samples))
}

func (m *Metrics) Unavail(binding, metric string) {
	if m != nil {
		m.Unavailable.WithLabelValues(binding, metric).Inc()
	}
}

func (m *Metrics) ScrapeError(binding, reason string) {
	if m != nil {
		m.ScrapeErrors.WithLabelValues(binding, reason).Inc()
	}
}

func (m *Metrics) Panic(binding string) {
	if m != nil {
		m.CollectorPanics.WithLabelValues(binding).Inc()
	}
}

func (m *Metrics) WriteError() {
	if m != nil {
		m.WriteErrors.Inc()
	}
}

func (m *Metrics) Sent() {
	if m != nil {
		m.BatchesSent.Inc()
	}
}

func (m *Metrics) AckedBatches(n int) {
	if m != nil {
		m.BatchesAcked.Add(float64(n))
	}
}

func (m *Metrics) Reject(code string) {
	if m != nil {
		m.Rejects.WithLabelValues(code).Inc()
	}
}

func (m *Metrics) SetCredits(n uint32) {
	if m != nil {
		m.Credits.Set(float64(n))
	}
}
```

- [ ] **Step 4: Run it and watch it pass.**

Run: `cd go-agent && go test ./internal/telemetry/tmetrics/`
Expected: `ok`.

- [ ] **Step 5: Commit.**

```bash
cd go-agent && git add internal/telemetry/tmetrics
git commit -m "go-agent: the telemetry pipeline's metrics, nil-safe

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `buffer` — the `AG-D9` disk buffer (`AG-D24`, `AG-D25`)

**Files:**
- Create: `go-agent/internal/telemetry/buffer/buffer.go`
- Test: `go-agent/internal/telemetry/buffer/buffer_test.go`

**Interfaces:**
- Consumes: `store.Store`, `store.Record`, `store.JSON`, `store.Blob`, `store.LoadJSON`, `store.ErrNotFound`.
- Produces:
  - **Types:** `Sample{Name, Type string; TS time.Time; Value float64; ReplicaRef, Incarnation string}`, `Batch{SchemaVersion int; StreamID string; Seq uint64; ProducedAt time.Time; Samples []Sample}`, `Gap{From, To uint64; Reason string}`, `Ref{ReplicaRef, Incarnation string}`, `Head{Stream string; Seq uint64; ProducedAt time.Time; Bytes int64; Refs []Ref}`, `Stats{Bytes int64; Batches int; Oldest time.Time}`, `Options{MaxBytes int64; MaxAge time.Duration; Now func() time.Time; Save func(store.Record) error; OnDrop func(reason string, samples int); OnWriteError func(error)}`.
  - **Gap reasons:** `ReasonDroppedOldest`, `ReasonExpired`, `ReasonOversize`, `ReasonUnregistered`, `ReasonCorrupt`.
  - `Open(*store.Store, Options) (*Buffer, error)`.
  - **Methods on `*Buffer`:**

    | Method | Signature |
    |---|---|
    | `Append` | `(stream string, producedAt time.Time, samples []Sample) (uint64, error)` |
    | `Heads` | `() []Head` |
    | `Read` | `(stream string, seq uint64) (Batch, error)` |
    | `MarkInFlight` | `(stream string, seq uint64) bool` |
    | `ClearInFlight` | `(stream string)` |
    | `Trim` | `(stream string, hwm uint64)` |
    | `DropAsGap` | `(stream string, seq uint64, reason string)` |
    | `GapsBefore` | `(stream string, seq uint64) []Gap` |
    | `Resume` | `() map[string]uint64` |
    | `Sweep` | `()` |
    | `Stats` | `() Stats` |
    | `Changed` | `() <-chan struct{}` |

- [ ] **Step 1: Write the failing tests.**

```go
package buffer

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)

func at(sec int) time.Time { return t0.Add(time.Duration(sec) * time.Second) }

func samples() []Sample {
	return []Sample{{Name: "cache.hit_rate", Type: "gauge", TS: t0, Value: 0.5, ReplicaRef: "java1", Incarnation: "c1"}}
}

func open(t *testing.T, dir string, opt Options) *Buffer {
	t.Helper()
	b, err := Open(store.MustOpen(dir), opt)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func mustAppend(t *testing.T, b *Buffer, stream string, produced time.Time) uint64 {
	t.Helper()
	seq, err := b.Append(stream, produced, samples())
	if err != nil {
		t.Fatal(err)
	}
	return seq
}

// oneBatch measures one batch's stored size, so caps are expressed in batches.
func oneBatch(t *testing.T) int64 {
	b := open(t, t.TempDir(), Options{})
	mustAppend(t, b, "a", at(1))
	return b.Stats().Bytes
}

func TestAppendNumbersEachStreamFromOne(t *testing.T) {
	b := open(t, t.TempDir(), Options{})
	got := []uint64{mustAppend(t, b, "a", at(1)), mustAppend(t, b, "a", at(2)), mustAppend(t, b, "b", at(3))}
	if !reflect.DeepEqual(got, []uint64{1, 2, 1}) {
		t.Fatalf("seqs %v", got)
	}
}

func TestReopenContinuesAboveTheHighestFile(t *testing.T) {
	dir := t.TempDir()
	b := open(t, dir, Options{})
	for i := 1; i <= 3; i++ {
		mustAppend(t, b, "a", at(i))
	}
	b2 := open(t, dir, Options{})
	if h := b2.Heads(); len(h) != 1 || h[0].Seq != 1 {
		t.Fatalf("heads after reopen %+v", h)
	}
	if seq := mustAppend(t, b2, "a", at(4)); seq != 4 {
		t.Fatalf("a reopened buffer reused a seq: %d", seq)
	}
}

func TestTrimDeletesThroughTheWatermarkAndSurvivesReopen(t *testing.T) {
	dir := t.TempDir()
	b := open(t, dir, Options{})
	for i := 1; i <= 3; i++ {
		mustAppend(t, b, "a", at(i))
	}
	b.Trim("a", 2)
	for _, seq := range []uint64{1, 2} {
		if _, err := os.Stat(filepath.Join(dir, "telemetry", "a", seqName(seq))); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("batch %d still on disk: %v", seq, err)
		}
	}
	b2 := open(t, dir, Options{})
	if r := b2.Resume(); r["a"] != 2 {
		t.Fatalf("resume %v", r)
	}
	if h := b2.Heads(); len(h) != 1 || h[0].Seq != 3 {
		t.Fatalf("heads %+v", h)
	}
}

func TestACrashBetweenStateAndDeleteIsRepairedOnLoad(t *testing.T) {
	dir := t.TempDir()
	b := open(t, dir, Options{})
	mustAppend(t, b, "a", at(1))
	mustAppend(t, b, "a", at(2))
	// Trim's first write landed; its deletes did not.
	st := store.MustOpen(dir)
	if err := st.Save(store.JSON{RelPath: "telemetry/a/state.json", Value: streamState{SchemaVersion: 1, AckedSeq: 2}}); err != nil {
		t.Fatal(err)
	}
	b2 := open(t, dir, Options{})
	if h := b2.Heads(); len(h) != 0 {
		t.Fatalf("files at or below acked_seq must be removed on load: %+v", h)
	}
	if seq := mustAppend(t, b2, "a", at(3)); seq != 3 {
		t.Fatalf("seq %d", seq)
	}
}

func TestByteCapDropsTheOldestBatchAcrossStreams(t *testing.T) {
	size := oneBatch(t)
	var dropped []string
	b := open(t, t.TempDir(), Options{MaxBytes: 2*size + size/2, OnDrop: func(r string, n int) { dropped = append(dropped, r) }})
	mustAppend(t, b, "a", at(1))
	mustAppend(t, b, "b", at(2))
	mustAppend(t, b, "a", at(3)) // three batches over a two-batch cap: a/1 is the oldest
	if !reflect.DeepEqual(dropped, []string{ReasonDroppedOldest}) {
		t.Fatalf("drops %v", dropped)
	}
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 1, Reason: ReasonDroppedOldest}}) {
		t.Fatalf("gaps %+v", g)
	}
	if h := b.Heads(); len(h) != 2 || h[0].Stream != "b" || h[1].Stream != "a" || h[1].Seq != 2 {
		t.Fatalf("heads %+v", h)
	}
}

func TestAnInFlightBatchIsNeverDropped(t *testing.T) {
	size := oneBatch(t)
	b := open(t, t.TempDir(), Options{MaxBytes: 2*size + size/2})
	mustAppend(t, b, "a", at(1))
	if !b.MarkInFlight("a", 1) {
		t.Fatal("MarkInFlight refused an existing batch")
	}
	mustAppend(t, b, "a", at(2))
	mustAppend(t, b, "a", at(3)) // over the cap: a/1 is in flight, so a/2 goes
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 2, To: 2, Reason: ReasonDroppedOldest}}) {
		t.Fatalf("gaps %+v", g)
	}
	if _, err := b.Read("a", 1); err != nil {
		t.Fatalf("the in-flight batch was dropped: %v", err)
	}
}

func TestContiguousDropsMergeIntoOneGap(t *testing.T) {
	size := oneBatch(t)
	b := open(t, t.TempDir(), Options{MaxBytes: size + size/2})
	for i := 1; i <= 4; i++ {
		mustAppend(t, b, "a", at(i))
	}
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 3, Reason: ReasonDroppedOldest}}) {
		t.Fatalf("gaps %+v", g)
	}
}

func TestAgeCapExpiresOldBatchesOnSweep(t *testing.T) {
	now := at(0)
	b := open(t, t.TempDir(), Options{MaxAge: time.Hour, Now: func() time.Time { return now }})
	mustAppend(t, b, "a", at(0))
	now = at(2 * 3600)
	b.Sweep()
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 1, Reason: ReasonExpired}}) {
		t.Fatalf("gaps %+v", g)
	}
}

// Review Focus 3: the clock stepped back, so produced_at lies in the future.
// Such a batch is not "old", but it still occupies the byte cap.
func TestFutureTimestampsStillObeyTheByteCap(t *testing.T) {
	size := oneBatch(t)
	b := open(t, t.TempDir(), Options{MaxBytes: 2*size + size/2, MaxAge: time.Hour, Now: func() time.Time { return at(0) }})
	for i := 1; i <= 5; i++ {
		mustAppend(t, b, "a", at(3600+i)) // an hour ahead of the agent's clock
	}
	b.Sweep()
	if s := b.Stats(); s.Bytes > 2*size+size/2 || s.Batches != 2 {
		t.Fatalf("stats %+v over a cap of %d", s, 2*size+size/2)
	}
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 3, Reason: ReasonDroppedOldest}}) {
		t.Fatalf("gaps %+v", g)
	}
}

func TestAWriteFailureIsReportedNotFatal(t *testing.T) {
	dir := t.TempDir()
	st := store.MustOpen(dir)
	fail := true
	var reported []error
	b, err := Open(st, Options{
		Save: func(r store.Record) error {
			if fail {
				return errors.New("disk full")
			}
			return st.Save(r)
		},
		OnWriteError: func(err error) { reported = append(reported, err) },
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := b.Append("a", at(1), samples()); err == nil {
		t.Fatal("Append must return the write error")
	}
	if len(reported) != 1 {
		t.Fatalf("reported %v", reported)
	}
	fail = false
	if seq := mustAppend(t, b, "a", at(2)); seq != 1 {
		t.Fatalf("a failed write must not consume a seq: %d", seq)
	}
}

func TestDropAsGapReleasesTheInFlightMark(t *testing.T) {
	b := open(t, t.TempDir(), Options{})
	mustAppend(t, b, "a", at(1))
	mustAppend(t, b, "a", at(2))
	b.MarkInFlight("a", 1)
	b.DropAsGap("a", 1, "UNKNOWN_REPLICA")
	if h := b.Heads(); len(h) != 1 || h[0].Seq != 2 {
		t.Fatalf("heads %+v", h)
	}
	if g := b.GapsBefore("a", 2); !reflect.DeepEqual(g, []Gap{{From: 1, To: 1, Reason: "UNKNOWN_REPLICA"}}) {
		t.Fatalf("gaps %+v", g)
	}
}

func TestTrimClearsDeliveredGaps(t *testing.T) {
	dir := t.TempDir()
	b := open(t, dir, Options{})
	mustAppend(t, b, "a", at(1))
	mustAppend(t, b, "a", at(2))
	b.DropAsGap("a", 1, ReasonOversize)
	b.Trim("a", 2) // batch 2 carried the gap and was acked
	if g := open(t, dir, Options{}).GapsBefore("a", 99); len(g) != 0 {
		t.Fatalf("delivered gaps must not survive: %+v", g)
	}
}

func TestACorruptBatchFileBecomesAGap(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "telemetry", "a"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "telemetry", "a", seqName(1)), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	b := open(t, dir, Options{})
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 1, Reason: ReasonCorrupt}}) {
		t.Fatalf("gaps %+v", g)
	}
	if seq := mustAppend(t, b, "a", at(2)); seq != 2 {
		t.Fatalf("seq %d", seq)
	}
}

func TestTrimAboveNextMovesNextPastTheGatewayWatermark(t *testing.T) {
	b := open(t, t.TempDir(), Options{})
	b.Trim("a", 10)
	if seq := mustAppend(t, b, "a", at(1)); seq != 11 {
		t.Fatalf("seq %d would be treated as a duplicate", seq)
	}
}

func TestMarkInFlightRefusesAMissingBatch(t *testing.T) {
	if open(t, t.TempDir(), Options{}).MarkInFlight("a", 5) {
		t.Fatal("MarkInFlight accepted a batch that does not exist")
	}
}
```

- [ ] **Step 2: Run them and watch them fail.**

Run: `cd go-agent && go test ./internal/telemetry/buffer/`
Expected: build failure, `undefined: Open`.

- [ ] **Step 3: Implement.**

```go
// Package buffer is the AG-D9 telemetry buffer (AG-D24): one file per batch
// under <data-dir>/telemetry/<stream>/, bounded by bytes and age across all
// streams, dropping the oldest batch first and recording every drop as a gap
// marker the stream's next batch carries. It survives agent restart.
package buffer

import (
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/store"
)

// Gap reasons (proposed for the wire as AG-16).
const (
	ReasonDroppedOldest = "DROPPED_OLDEST"
	ReasonExpired       = "EXPIRED"
	ReasonOversize      = "OVERSIZE"
	ReasonUnregistered  = "UNREGISTERED"
	ReasonCorrupt       = "CORRUPT"
)

const root = "telemetry"

type Sample struct {
	Name        string    `json:"name"`
	Type        string    `json:"type"`
	TS          time.Time `json:"ts"`
	Value       float64   `json:"value"`
	ReplicaRef  string    `json:"replica_ref"`
	Incarnation string    `json:"incarnation"`
}

type Batch struct {
	SchemaVersion int       `json:"schema_version"`
	StreamID      string    `json:"stream_id"`
	Seq           uint64    `json:"seq"`
	ProducedAt    time.Time `json:"produced_at"`
	Samples       []Sample  `json:"samples"`
}

func (b Batch) Version() int { return b.SchemaVersion }

type Gap struct {
	From   uint64 `json:"from_seq"`
	To     uint64 `json:"to_seq"`
	Reason string `json:"reason"`
}

type streamState struct {
	SchemaVersion int    `json:"schema_version"`
	AckedSeq      uint64 `json:"acked_seq"`
	Gaps          []Gap  `json:"gaps"`
}

func (s streamState) Version() int { return s.SchemaVersion }

// Ref is one (replica_ref, incarnation) pair a batch's samples name.
type Ref struct{ ReplicaRef, Incarnation string }

// Head is a stream's oldest unsent batch, as the sender sees it.
type Head struct {
	Stream     string
	Seq        uint64
	ProducedAt time.Time
	Bytes      int64
	Refs       []Ref
}

type Stats struct {
	Bytes   int64
	Batches int
	Oldest  time.Time // zero when empty
}

type Options struct {
	MaxBytes     int64         // 0 = no byte cap
	MaxAge       time.Duration // 0 = no age cap
	Now          func() time.Time
	Save         func(store.Record) error // default: the store's Save; tests inject failures
	OnDrop       func(reason string, samples int)
	OnWriteError func(error)
}

type meta struct {
	seq      uint64
	bytes    int64
	produced time.Time
	samples  int
	refs     []Ref
}

type stream struct {
	id       string
	state    streamState
	files    []meta // ascending seq
	next     uint64
	inflight uint64 // 0 = none
}

type Buffer struct {
	mu      sync.Mutex
	st      *store.Store
	opt     Options
	streams map[string]*stream
	total   int64
	changed chan struct{}
}

func seqName(seq uint64) string           { return fmt.Sprintf("%020d.json", seq) }
func stateRel(id string) string            { return root + "/" + id + "/state.json" }
func batchRel(id string, seq uint64) string { return root + "/" + id + "/" + seqName(seq) }

func seqOf(name string) (uint64, bool) {
	base, ok := strings.CutSuffix(name, ".json")
	if !ok || len(base) != 20 {
		return 0, false
	}
	n, err := strconv.ParseUint(base, 10, 64)
	return n, err == nil
}

func metaOf(b Batch, size int64) meta {
	m := meta{seq: b.Seq, bytes: size, produced: b.ProducedAt, samples: len(b.Samples)}
	seen := map[Ref]bool{}
	for _, s := range b.Samples {
		r := Ref{ReplicaRef: s.ReplicaRef, Incarnation: s.Incarnation}
		if !seen[r] {
			seen[r] = true
			m.refs = append(m.refs, r)
		}
	}
	return m
}

func Open(st *store.Store, opt Options) (*Buffer, error) {
	if opt.Now == nil {
		opt.Now = time.Now
	}
	if opt.Save == nil {
		opt.Save = st.Save
	}
	if opt.OnDrop == nil {
		opt.OnDrop = func(string, int) {}
	}
	if opt.OnWriteError == nil {
		opt.OnWriteError = func(error) {}
	}
	b := &Buffer{st: st, opt: opt, streams: map[string]*stream{}, changed: make(chan struct{})}
	entries, err := os.ReadDir(st.Path(root))
	if errors.Is(err, os.ErrNotExist) {
		return b, nil
	}
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		if e.IsDir() {
			if err := b.load(e.Name()); err != nil {
				return nil, err
			}
		}
	}
	return b, nil
}

func (b *Buffer) load(id string) error {
	s := &stream{id: id, state: streamState{SchemaVersion: 1}}
	if st, err := store.LoadJSON[streamState](b.st, stateRel(id), 1); err == nil {
		s.state = st
	}
	entries, err := os.ReadDir(b.st.Path(root + "/" + id))
	if err != nil {
		return err
	}
	for _, e := range entries {
		seq, ok := seqOf(e.Name())
		if !ok {
			continue
		}
		if seq <= s.state.AckedSeq { // trimmed in state before a crash finished the deletes
			if err := b.st.Delete(batchRel(id, seq)); err != nil {
				return err
			}
			continue
		}
		batch, err := store.LoadJSON[Batch](b.st, batchRel(id, seq), 1)
		if err != nil {
			// Telemetry must not stop the agent starting: the batch is lost, and said so.
			_ = b.st.Delete(batchRel(id, seq))
			s.addGap(seq, ReasonCorrupt)
			b.opt.OnDrop(ReasonCorrupt, 0)
			continue
		}
		info, err := e.Info()
		if err != nil {
			return err
		}
		s.files = append(s.files, metaOf(batch, info.Size()))
	}
	slices.SortFunc(s.files, func(x, y meta) int { return cmp.Compare(x.seq, y.seq) })
	s.next = s.state.AckedSeq + 1
	for _, g := range s.state.Gaps {
		s.next = max(s.next, g.To+1)
	}
	if n := len(s.files); n > 0 {
		s.next = max(s.next, s.files[n-1].seq+1)
	}
	for _, f := range s.files {
		b.total += f.bytes
	}
	b.streams[id] = s
	b.saveState(s)
	return nil
}

func (b *Buffer) stream(id string) *stream {
	s := b.streams[id]
	if s == nil {
		s = &stream{id: id, state: streamState{SchemaVersion: 1}, next: 1}
		b.streams[id] = s
	}
	return s
}

// Append persists one tick's samples as the stream's next batch, then
// enforces the caps. A failed write drops the batch and is reported, never
// fatal (AG-D25); WriteAtomic leaves no partial file, so its seq is reused.
func (b *Buffer) Append(id string, producedAt time.Time, samples []Sample) (uint64, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	s := b.stream(id)
	batch := Batch{SchemaVersion: 1, StreamID: id, Seq: s.next, ProducedAt: producedAt.UTC(), Samples: samples}
	data, err := json.Marshal(batch)
	if err == nil {
		err = b.opt.Save(store.Blob{RelPath: batchRel(id, batch.Seq), Data: data})
	}
	if err != nil {
		b.opt.OnWriteError(err)
		return 0, err
	}
	s.next++
	s.files = append(s.files, metaOf(batch, int64(len(data))))
	b.total += int64(len(data))
	b.enforce()
	b.notify()
	return batch.Seq, nil
}

// enforce applies the age cap, then the byte cap, dropping the oldest batch
// across all streams first. A batch produced in the future (the clock stepped
// back) has a negative age: never expired early, but still counted against
// the byte cap.
func (b *Buffer) enforce() {
	now := b.opt.Now()
	for {
		s, i, ok := b.oldest()
		if !ok {
			return
		}
		switch {
		case b.opt.MaxAge > 0 && now.Sub(s.files[i].produced) > b.opt.MaxAge:
			b.drop(s, i, ReasonExpired)
		case b.opt.MaxBytes > 0 && b.total > b.opt.MaxBytes:
			b.drop(s, i, ReasonDroppedOldest)
		default:
			return
		}
	}
}

// oldest is the batch with the earliest produced_at across streams, never an
// in-flight one: its fate is the gateway's, and a gap must not claim a batch
// the DP may already have committed.
func (b *Buffer) oldest() (*stream, int, bool) {
	var best *stream
	bi := -1
	for _, s := range b.streams {
		for i, f := range s.files {
			if f.seq == s.inflight {
				continue
			}
			if best == nil || f.produced.Before(best.files[bi].produced) ||
				(f.produced.Equal(best.files[bi].produced) && s.id < best.id) {
				best, bi = s, i
			}
			break // within a stream, the first non-in-flight file is its oldest
		}
	}
	return best, bi, best != nil
}

func (b *Buffer) drop(s *stream, i int, reason string) {
	f := s.files[i]
	if err := b.st.Delete(batchRel(s.id, f.seq)); err != nil {
		b.opt.OnWriteError(err)
	}
	s.files = slices.Delete(s.files, i, i+1)
	b.total -= f.bytes
	s.addGap(f.seq, reason)
	b.saveState(s)
	b.opt.OnDrop(reason, f.samples)
}

func (s *stream) addGap(seq uint64, reason string) {
	if n := len(s.state.Gaps); n > 0 {
		g := &s.state.Gaps[n-1]
		if g.Reason == reason && g.To+1 == seq {
			g.To = seq
			return
		}
	}
	s.state.Gaps = append(s.state.Gaps, Gap{From: seq, To: seq, Reason: reason})
}

func (b *Buffer) saveState(s *stream) {
	if err := b.opt.Save(store.JSON{RelPath: stateRel(s.id), Value: s.state}); err != nil {
		b.opt.OnWriteError(err)
	}
}

func (b *Buffer) notify() {
	close(b.changed)
	b.changed = make(chan struct{})
}

// Changed is closed on the next Append, Trim, drop or in-flight change.
func (b *Buffer) Changed() <-chan struct{} {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.changed
}

// Heads returns, for every stream with nothing in flight, its oldest batch
// above the acked watermark, oldest produced_at first.
func (b *Buffer) Heads() []Head {
	b.mu.Lock()
	defer b.mu.Unlock()
	var out []Head
	for _, s := range b.streams {
		if s.inflight != 0 || len(s.files) == 0 {
			continue
		}
		f := s.files[0]
		out = append(out, Head{Stream: s.id, Seq: f.seq, ProducedAt: f.produced, Bytes: f.bytes, Refs: slices.Clone(f.refs)})
	}
	slices.SortFunc(out, func(x, y Head) int {
		if c := x.ProducedAt.Compare(y.ProducedAt); c != 0 {
			return c
		}
		return strings.Compare(x.Stream, y.Stream)
	})
	return out
}

func (b *Buffer) Read(id string, seq uint64) (Batch, error) {
	return store.LoadJSON[Batch](b.st, batchRel(id, seq), 1)
}

// MarkInFlight spares the batch from the caps until it is acked, rejected or
// requeued. It refuses a batch that is no longer buffered.
func (b *Buffer) MarkInFlight(id string, seq uint64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	s := b.streams[id]
	if s == nil || !slices.ContainsFunc(s.files, func(f meta) bool { return f.seq == seq }) {
		return false
	}
	s.inflight = seq
	return true
}

func (b *Buffer) ClearInFlight(id string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if s := b.streams[id]; s != nil && s.inflight != 0 {
		s.inflight = 0
		b.notify()
	}
}

// Trim records the gateway's durable watermark: state first, then the files,
// so a crash between the two leaves only files that load deletes.
func (b *Buffer) Trim(id string, hwm uint64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	s := b.stream(id)
	s.state.AckedSeq = max(s.state.AckedSeq, hwm)
	s.state.Gaps = slices.DeleteFunc(s.state.Gaps, func(g Gap) bool { return g.To < hwm })
	b.saveState(s)
	for len(s.files) > 0 && s.files[0].seq <= hwm {
		f := s.files[0]
		if err := b.st.Delete(batchRel(id, f.seq)); err != nil {
			b.opt.OnWriteError(err)
		}
		s.files = s.files[1:]
		b.total -= f.bytes
	}
	if s.inflight != 0 && s.inflight <= hwm {
		s.inflight = 0
	}
	// A gateway watermark above our next seq means our state was lost: never
	// send a seq the DP would discard as a replay.
	s.next = max(s.next, hwm+1)
	b.notify()
}

// DropAsGap removes one batch the sender gave up on (a terminal reject,
// OVERSIZE, UNREGISTERED) and records it as a gap.
func (b *Buffer) DropAsGap(id string, seq uint64, reason string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	s := b.streams[id]
	if s == nil {
		return
	}
	i := slices.IndexFunc(s.files, func(f meta) bool { return f.seq == seq })
	if i < 0 {
		return
	}
	if s.inflight == seq {
		s.inflight = 0
	}
	b.drop(s, i, reason)
	b.notify()
}

// GapsBefore is the gap markers a batch with this seq must carry.
func (b *Buffer) GapsBefore(id string, seq uint64) []Gap {
	b.mu.Lock()
	defer b.mu.Unlock()
	s := b.streams[id]
	if s == nil {
		return nil
	}
	var out []Gap
	for _, g := range s.state.Gaps {
		if g.To < seq {
			out = append(out, g)
		}
	}
	return out
}

// Resume is SessionOpen.resume.last_telemetry_seq_by_stream (a hint; the
// DP's watermark wins, gateway §4.13).
func (b *Buffer) Resume() map[string]uint64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make(map[string]uint64, len(b.streams))
	for id, s := range b.streams {
		out[id] = s.state.AckedSeq
	}
	return out
}

// Sweep applies the caps without an append — the age cap needs a clock tick.
func (b *Buffer) Sweep() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.enforce()
	b.notify()
}

func (b *Buffer) Stats() Stats {
	b.mu.Lock()
	defer b.mu.Unlock()
	st := Stats{Bytes: b.total}
	for _, s := range b.streams {
		st.Batches += len(s.files)
		for _, f := range s.files {
			if st.Oldest.IsZero() || f.produced.Before(st.Oldest) {
				st.Oldest = f.produced
			}
		}
	}
	return st
}
```

- [ ] **Step 4: Run them and watch them pass, race-clean.**

Run: `cd go-agent && go test -race ./internal/telemetry/buffer/`
Expected: `ok`.

- [ ] **Step 5: Prove the two guards are load-bearing.**
  1. Delete the `if f.seq == s.inflight { continue }` lines in `oldest()` and run `go test ./internal/telemetry/buffer/ -run InFlightBatchIsNever` — it must FAIL.
  2. Restore them. Delete the `if n := len(s.files); n > 0 { … }` block in `load()` and run `-run ReopenContinues` — it must FAIL.
  3. Restore it. Both must pass again.

- [ ] **Step 6: Commit.**

```bash
cd go-agent && git add internal/telemetry/buffer
git commit -m "go-agent: the AG-D9 telemetry disk buffer

One file per batch through the store's atomic write; byte and age caps across
all streams dropping the oldest first, never an in-flight batch; every drop a
gap marker the stream's next batch carries; trim on the gateway's watermark.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `reports.IDTable` and the `view` hub

**Files:**
- Modify: `go-agent/internal/reports/reports.go` (append `IDTable`)
- Test: `go-agent/internal/reports/reports_test.go` (append)
- Create: `go-agent/internal/telemetry/view/view.go`
- Test: `go-agent/internal/telemetry/view/view_test.go`

**Interfaces:**
- Consumes: Task 1's `msg.TelemetryBatch`, `msg.TelemetryInbound`.
- Produces:
  - `(reports.State).IDTable() reports.IDTable`
  - `(reports.IDTable).IDs(binding, replicaRef, incarnation string) (replicaID, incarnationID string, ok bool)`
  - `view.Stream` (`Send`/`Recv`/`Close`), `view.SessionInfo{ID string; Limits msg.Limits; Open func(context.Context) (Stream, error)}`, `view.IDLookup`
  - `view.Snapshot{Session *SessionInfo; Bindings map[string]bool; IDs IDLookup; Halted bool}`, with methods `InLease(binding) bool` and `Lookup(binding, ref, inc) (string, string, bool)`
  - `view.View` interface (`Current() Snapshot`, `Changed() <-chan struct{}`)
  - `view.NewHub() *Hub`, with `Publish(Snapshot)`

- [ ] **Step 1: Write the failing tests.** Append to `internal/reports/reports_test.go`:

```go
func TestIDTableIsAnIsolatedCopy(t *testing.T) {
	s := State{Registry: Registry{SchemaVersion: 1,
		ReplicaIDs:     map[string]string{"binding-1|java1": "rep-1"},
		IncarnationIDs: map[string]string{"binding-1|java1|c1": "inc-1"}}}
	tbl := s.IDTable()
	if r, i, ok := tbl.IDs("binding-1", "java1", "c1"); !ok || r != "rep-1" || i != "inc-1" {
		t.Fatalf("IDs = %q %q %v", r, i, ok)
	}
	if _, _, ok := tbl.IDs("binding-1", "java1", "c2"); ok {
		t.Fatal("an unregistered incarnation must not resolve")
	}
	s.Registry.IncarnationIDs["binding-1|java1|c2"] = "inc-2" // the loop keeps mutating its maps (L23)
	if _, _, ok := tbl.IDs("binding-1", "java1", "c2"); ok {
		t.Fatal("IDTable aliases the registry it was taken from")
	}
}
```

Create `internal/telemetry/view/view_test.go`:

```go
package view

import (
	"sync"
	"testing"
)

type ids map[string][2]string

func (m ids) IDs(b, r, i string) (string, string, bool) {
	v, ok := m[b+"|"+r+"|"+i]
	return v[0], v[1], ok
}

func TestPublishWakesWaitersAndReplacesTheSnapshot(t *testing.T) {
	h := NewHub()
	ch := h.Changed()
	h.Publish(Snapshot{Bindings: map[string]bool{"binding-1": true}})
	select {
	case <-ch:
	default:
		t.Fatal("Publish did not close the previous Changed channel")
	}
	if !h.Current().InLease("binding-1") {
		t.Fatal("Current is not the published snapshot")
	}
}

func TestTheZeroSnapshotIsSafeAndEmpty(t *testing.T) {
	var s Snapshot
	if s.InLease("binding-1") {
		t.Fatal("zero snapshot claims a binding")
	}
	if _, _, ok := s.Lookup("b", "r", "i"); ok {
		t.Fatal("zero snapshot resolves ids")
	}
}

func TestAHaltedSnapshotLeasesNothing(t *testing.T) {
	s := Snapshot{Bindings: map[string]bool{"binding-1": true}, Halted: true}
	if s.InLease("binding-1") {
		t.Fatal("a halted agent must collect nothing")
	}
}

func TestLookupGoesThroughTheTable(t *testing.T) {
	s := Snapshot{IDs: ids{"b|r|i": {"rep", "inc"}}}
	if r, i, ok := s.Lookup("b", "r", "i"); !ok || r != "rep" || i != "inc" {
		t.Fatalf("%q %q %v", r, i, ok)
	}
}

func TestConcurrentReadersAndPublisher(t *testing.T) {
	h := NewHub()
	var wg sync.WaitGroup
	for range 4 {
		wg.Go(func() {
			for range 1000 {
				_ = h.Current().InLease("b")
				_ = h.Changed()
			}
		})
	}
	for range 1000 {
		h.Publish(Snapshot{Bindings: map[string]bool{"b": true}})
	}
	wg.Wait()
}
```

- [ ] **Step 2: Run them and watch them fail.**

Run: `cd go-agent && go test ./internal/reports/ ./internal/telemetry/view/`
Expected: build failures, `s.IDTable undefined` and `undefined: NewHub`.

- [ ] **Step 3: Implement `IDTable`.** Append to `internal/reports/reports.go`, adding `"maps"` to its imports:

```go
// IDTable is an immutable copy of the registry for readers off the loop
// goroutine — the telemetry pipeline. The loop takes it after reducing, on
// its own goroutine; nobody mutates it afterwards.
type IDTable struct {
	replicas     map[string]string
	incarnations map[string]string
}

func (s State) IDTable() IDTable {
	return IDTable{replicas: maps.Clone(s.Registry.ReplicaIDs), incarnations: maps.Clone(s.Registry.IncarnationIDs)}
}

// IDs is the DP-minted pair a sample is labelled with; ok only when both exist.
func (t IDTable) IDs(binding, replicaRef, incarnation string) (replicaID, incarnationID string, ok bool) {
	replicaID = t.replicas[replicaKey(binding, replicaRef)]
	incarnationID = t.incarnations[incarnationKey(binding, replicaRef, incarnation)]
	return replicaID, incarnationID, replicaID != "" && incarnationID != ""
}
```

- [ ] **Step 4: Implement the view.** Create `internal/telemetry/view/view.go`:

```go
// Package view is what the control loop publishes for the telemetry pipeline
// (AG-D21): the session, the lease's bindings and the DP-minted ids, as one
// immutable value replaced whole, so a reader never touches reducer state
// and never sees a session from one publish and ids from another.
package view

import (
	"context"
	"sync"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// Stream is the Telemetry RPC as the sender uses it; *wire.TelemetryStream
// satisfies it.
type Stream interface {
	Send(msg.TelemetryBatch) error
	Recv() (msg.TelemetryInbound, error)
	Close() error
}

// SessionInfo is present only while the session is ACTIVE. Open opens the
// Telemetry stream on that session's own connection.
type SessionInfo struct {
	ID     string
	Limits msg.Limits
	Open   func(ctx context.Context) (Stream, error)
}

// IDLookup resolves a sample's identity to its DP-minted ids; reports.IDTable
// satisfies it.
type IDLookup interface {
	IDs(binding, replicaRef, incarnation string) (replicaID, incarnationID string, ok bool)
}

type Snapshot struct {
	Session  *SessionInfo
	Bindings map[string]bool // the current lease's binding_ids
	IDs      IDLookup
	Halted   bool
}

func (s Snapshot) InLease(binding string) bool { return !s.Halted && s.Bindings[binding] }

func (s Snapshot) Lookup(binding, replicaRef, incarnation string) (string, string, bool) {
	if s.IDs == nil {
		return "", "", false
	}
	return s.IDs.IDs(binding, replicaRef, incarnation)
}

type View interface {
	Current() Snapshot
	Changed() <-chan struct{}
}

type Hub struct {
	mu      sync.Mutex
	cur     Snapshot
	changed chan struct{}
}

func NewHub() *Hub { return &Hub{changed: make(chan struct{})} }

func (h *Hub) Publish(s Snapshot) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.cur = s
	close(h.changed)
	h.changed = make(chan struct{})
}

func (h *Hub) Current() Snapshot {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.cur
}

// Changed is closed by the next Publish. Take it before reading Current, so a
// publish between the two is never missed.
func (h *Hub) Changed() <-chan struct{} {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.changed
}
```

- [ ] **Step 5: Run them and watch them pass, race-clean.**

Run: `cd go-agent && go test -race ./internal/reports/ ./internal/telemetry/view/`
Expected: `ok`, `ok`.

- [ ] **Step 6: Commit.**

```bash
cd go-agent && git add internal/reports internal/telemetry/view
git commit -m "go-agent: the view the control loop publishes for telemetry; IDTable

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: `Adapter.ScrapeAddr`

**Files:**
- Modify: `go-agent/internal/runtime/runtime.go` (the `Adapter` interface)
- Modify: `go-agent/internal/runtime/docker/docker.go` (add `ScrapeAddr`, `ErrPortUnreachable`; imports)
- Test: `go-agent/internal/runtime/docker/docker_test.go` (append)
- Modify (keep compiling):
  - `go-agent/internal/agent/inttest_helpers_test.go`: `fakeAdapter` gains `scrape string` and `ScrapeAddr`;
  - `go-agent/internal/agent/ops_test.go`: `stubAdapter`, `diskAdapter` gain `ScrapeAddr`.

**Interfaces:**
- Produces:
  - `runtime.Adapter.ScrapeAddr(ctx context.Context, p runtime.Placement, port int) (string, error)`, returning `host:port`
  - `docker.ErrPortUnreachable`
  - `fakeAdapter.scrape`, the address returned to collectors in integration tests

- [ ] **Step 1: Write the failing test.** Append to `internal/runtime/docker/docker_test.go`, adding imports `"github.com/docker/docker/api/types/network"` and `"github.com/docker/go-connections/nat"`:

```go
func TestScrapeAddrPrefersThePublishedPortThenTheContainerIP(t *testing.T) {
	withNet := func(ports nat.PortMap, nets map[string]*network.EndpointSettings) types.ContainerJSON {
		return types.ContainerJSON{ContainerJSONBase: &types.ContainerJSONBase{ID: "c1", Name: "/java1"},
			NetworkSettings: &types.NetworkSettings{NetworkSettingsBase: types.NetworkSettingsBase{Ports: ports}, Networks: nets}}
	}
	for _, tc := range []struct {
		name string
		insp types.ContainerJSON
		want string
	}{
		{"published on all interfaces", withNet(nat.PortMap{"8081/tcp": {{HostIP: "0.0.0.0", HostPort: "18081"}}}, nil), "127.0.0.1:18081"},
		{"published on one interface", withNet(nat.PortMap{"8081/tcp": {{HostIP: "192.168.1.5", HostPort: "18081"}}}, nil), "192.168.1.5:18081"},
		{"unpublished: the container IP", withNet(nil, map[string]*network.EndpointSettings{"b": {IPAddress: "172.18.0.9"}, "a": {IPAddress: "172.18.0.4"}}), "172.18.0.4:8081"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := New(&fakeEngine{inspect: map[string]types.ContainerJSON{"c1": tc.insp}})
			got, err := a.ScrapeAddr(context.Background(), runtime.Placement{ContainerID: "c1", Name: "java1"}, 8081)
			if err != nil || got != tc.want {
				t.Fatalf("got %q %v, want %q", got, err, tc.want)
			}
		})
	}
	a := New(&fakeEngine{inspect: map[string]types.ContainerJSON{"c1": withNet(nil, nil)}})
	if _, err := a.ScrapeAddr(context.Background(), runtime.Placement{ContainerID: "c1", Name: "java1"}, 8081); !errors.Is(err, ErrPortUnreachable) {
		t.Fatalf("want ErrPortUnreachable, got %v", err)
	}
}
```

If `docker_test.go` does not yet import `internal/runtime`, add `"github.com/solace/solution-manager/go-agent/internal/runtime"`.

- [ ] **Step 2: Run it and watch it fail.**

Run: `cd go-agent && go test ./internal/runtime/docker/ -run ScrapeAddr`
Expected: build failure, `a.ScrapeAddr undefined`.

- [ ] **Step 3: Extend the interface.** In `internal/runtime/runtime.go`, the `Adapter` interface gains, after `Observe`:

```go
	// ScrapeAddr is the host:port the agent fetches a container port from.
	ScrapeAddr(ctx context.Context, p Placement, port int) (string, error)
```

- [ ] **Step 4: Implement it for Docker.** In `internal/runtime/docker/docker.go`:
  - add the imports `"maps"`, `"net"`, `"slices"`, `"strconv"` and `"github.com/docker/go-connections/nat"`;
  - add `ErrPortUnreachable` to the `var` block:

```go
	ErrPortUnreachable = errors.New("docker: port is neither published nor reachable on a container network")
```

  - append:

```go
// ScrapeAddr is where a container port is fetched from (spec §3.4): its
// published binding first — on Docker Desktop the only way in from the host
// (docs.docker.com, "Networking on Docker Desktop", fetched 2026-10-01) —
// then its IP, for an agent attached to the container's own network, which is
// the control architecture.md §10 prescribes for Cache.
func (a *Adapter) ScrapeAddr(ctx context.Context, p runtime.Placement, port int) (string, error) {
	insp, err := a.cli.ContainerInspect(ctx, p.ContainerID)
	if err != nil {
		return "", err
	}
	if ns := insp.NetworkSettings; ns != nil {
		for _, b := range ns.Ports[nat.Port(fmt.Sprintf("%d/tcp", port))] {
			if b.HostPort == "" {
				continue
			}
			host := b.HostIP
			if host == "" || host == "0.0.0.0" || host == "::" {
				host = "127.0.0.1"
			}
			return net.JoinHostPort(host, b.HostPort), nil
		}
		for _, name := range slices.Sorted(maps.Keys(ns.Networks)) {
			if ep := ns.Networks[name]; ep != nil && ep.IPAddress != "" {
				return net.JoinHostPort(ep.IPAddress, strconv.Itoa(port)), nil
			}
		}
	}
	return "", fmt.Errorf("%w: %d (container %s)", ErrPortUnreachable, port, p.Name)
}
```

  - run `go mod tidy`; `github.com/docker/go-connections` becomes a direct dependency.

- [ ] **Step 5: Keep every other `Adapter` compiling.**
  - **`internal/agent/inttest_helpers_test.go`:**
    - `fakeAdapter` gains the field `scrape string // host:port collectors fetch from (telemetry tests)`;
    - add:

    ```go
func (a *fakeAdapter) ScrapeAddr(context.Context, runtime.Placement, int) (string, error) {
	if a.scrape == "" {
		return "", errors.New("fakeAdapter: no scrape address")
	}
	return a.scrape, nil
}
    ```

    - add `"errors"` to that file's imports.
  - **`internal/agent/ops_test.go`:** `stubAdapter` and `diskAdapter` each gain:

    ```go
func (*stubAdapter) ScrapeAddr(context.Context, runtime.Placement, int) (string, error) { return "", errors.New("unused") }
func (*diskAdapter) ScrapeAddr(context.Context, runtime.Placement, int) (string, error) { return "", errors.New("unused") }
    ```

    Add `"errors"` to its imports if it is missing.

- [ ] **Step 6: Run everything and watch it pass.**

Run: `cd go-agent && go vet ./... && go test ./... && go vet -tags integration ./internal/agent/`
Expected: all `ok`; the integration build vets clean.

- [ ] **Step 7: Commit.**

```bash
cd go-agent && git add go.mod go.sum internal/runtime internal/agent/inttest_helpers_test.go internal/agent/ops_test.go
git commit -m "go-agent: Adapter.ScrapeAddr — published port first, then the container IP

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: `collect` — the `http_json` collector

**Files:**
- Create: `go-agent/internal/telemetry/collect/collect.go`
- Test: `go-agent/internal/telemetry/collect/collect_test.go`

**Interfaces:**
- Consumes: `declare.Binding`, `jsonptr.Resolve`, `jsonptr.Number`, `buffer.Sample`, `runtime.Adapter` (`Resolve`, `ScrapeAddr`), `tmetrics.Metrics`.
- Produces:
  - `collect.Appender` interface: `Append(stream string, producedAt time.Time, samples []buffer.Sample) (uint64, error)`
  - `collect.Collector{Binding string; Decl declare.Binding; Adapter runtime.Adapter; InLease func(string) bool; Out Appender; Client *http.Client; Now func() time.Time; Log *slog.Logger; Metrics *tmetrics.Metrics}`
  - `(*Collector).Run(ctx)` and `(*Collector).Tick(ctx)`
  - `collect.MaxBody = 1 << 20`

- [ ] **Step 1: Write the failing tests.**

```go
package collect

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/tmetrics"
)

type target struct {
	addr    string
	resolve func() (runtime.Placement, error)
}

func (t *target) Resolve(context.Context, string) (runtime.Placement, error) {
	if t.resolve != nil {
		return t.resolve()
	}
	return runtime.Placement{BindingID: "binding-1", ContainerID: "c1", Name: "java1"}, nil
}
func (t *target) ScrapeAddr(context.Context, runtime.Placement, int) (string, error) { return t.addr, nil }
func (t *target) Preflight(context.Context, runtime.Placement) (msg.PreflightResult, error) {
	return msg.PreflightResult{}, errors.New("unused")
}
func (t *target) WriteConfig(context.Context, runtime.Placement, []byte, string, runtime.Progress) (runtime.WriteResult, error) {
	return runtime.WriteResult{}, errors.New("unused")
}
func (t *target) Restart(context.Context, runtime.Placement) error { return errors.New("unused") }
func (t *target) Stop(context.Context, runtime.Placement) error    { return errors.New("unused") }
func (t *target) Observe(context.Context, runtime.Placement) (runtime.ReplicaFacts, error) {
	return runtime.ReplicaFacts{}, errors.New("unused")
}

type sink struct {
	mu      sync.Mutex
	batches [][]buffer.Sample
}

func (s *sink) Append(_ string, _ time.Time, samples []buffer.Sample) (uint64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.batches = append(s.batches, samples)
	return uint64(len(s.batches)), nil
}

func (s *sink) all() [][]buffer.Sample {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.batches
}

func ptr(s string) *string { return &s }

func decl(timeout time.Duration, metrics ...declare.Metric) declare.Binding {
	return declare.Binding{Interval: time.Second, Timeout: timeout, Metrics: metrics}
}

func metric(name, typ, pointer string) declare.Metric {
	return declare.Metric{Name: name, MetricType: typ, Unit: "x",
		Source: declare.Source{Kind: "http_json", Port: 8081, Path: "/api/cache/stats", Pointer: ptr(pointer), OnUnavailable: "degrade"}}
}

var t0 = time.Date(2026, 10, 1, 7, 0, 0, 0, time.UTC)

func collector(addr string, out *sink, m *tmetrics.Metrics, d declare.Binding) *Collector {
	return &Collector{Binding: "binding-1", Decl: d, Adapter: &target{addr: addr}, InLease: func(string) bool { return true },
		Out: out, Now: func() time.Time { return t0 }, Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Metrics: m}
}

func serve(t *testing.T, h http.HandlerFunc) string {
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return strings.TrimPrefix(srv.URL, "http://")
}

func TestATickIsOneBatchFromTheDeclaredPointers(t *testing.T) {
	var gets atomic.Int32
	addr := serve(t, func(w http.ResponseWriter, r *http.Request) {
		gets.Add(1)
		if r.URL.Path != "/api/cache/stats" {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, `{"hitRate": 0.93, "missCount": 12}`)
	})
	out := &sink{}
	collector(addr, out, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"), metric("cache.miss_count", "counter", "/missCount"))).Tick(context.Background())
	want := []buffer.Sample{
		{Name: "cache.hit_rate", Type: "gauge", TS: t0, Value: 0.93, ReplicaRef: "java1", Incarnation: "c1"},
		{Name: "cache.miss_count", Type: "counter", TS: t0, Value: 12, ReplicaRef: "java1", Incarnation: "c1"},
	}
	if got := out.all(); len(got) != 1 || !equal(got[0], want) {
		t.Fatalf("batches %+v", got)
	}
	if gets.Load() != 1 {
		t.Fatalf("two metrics on one path cost %d GETs, want 1", gets.Load())
	}
}

func equal(a, b []buffer.Sample) bool {
	x, _ := json.Marshal(a)
	y, _ := json.Marshal(b)
	return string(x) == string(y)
}

func TestMissingOrNonNumericValuesProduceNoSampleNeverZero(t *testing.T) {
	addr := serve(t, func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, `{"hitRate": "n/a"}`) })
	out, m := &sink{}, tmetrics.New()
	collector(addr, out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"), metric("cache.miss_count", "counter", "/missCount"))).Tick(context.Background())
	if len(out.all()) != 0 {
		t.Fatalf("a tick with no numbers must append nothing: %+v", out.all())
	}
	for _, name := range []string{"cache.hit_rate", "cache.miss_count"} {
		if v := testutil.ToFloat64(m.Unavailable.WithLabelValues("binding-1", name)); v != 1 {
			t.Errorf("unavailable{%s} = %v", name, v)
		}
	}
}

func TestBadResponsesAreScrapeErrors(t *testing.T) {
	for reason, h := range map[string]http.HandlerFunc{
		"status": func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "boom", http.StatusInternalServerError) },
		"decode": func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "<html>") },
	} {
		out, m := &sink{}, tmetrics.New()
		collector(serve(t, h), out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"))).Tick(context.Background())
		if len(out.all()) != 0 || testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", reason)) != 1 {
			t.Errorf("%s: batches %d, errors %v", reason, len(out.all()), testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", reason)))
		}
	}
}

// Review Focus 2.
func TestOversizedBodyIsRefused(t *testing.T) {
	addr := serve(t, func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"hitRate": 0.5, "pad": [`+strings.Repeat(`0,`, MaxBody)+`0]}`)
	})
	out, m := &sink{}, tmetrics.New()
	collector(addr, out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate"))).Tick(context.Background())
	if len(out.all()) != 0 || testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", "decode")) != 1 {
		t.Fatalf("a body over %d bytes must be refused, not decoded", MaxBody)
	}
}

func TestAHungEndpointIsBoundedByTheTimeout(t *testing.T) {
	addr := serve(t, func(_ http.ResponseWriter, r *http.Request) { <-r.Context().Done() })
	out, m := &sink{}, tmetrics.New()
	start := time.Now()
	collector(addr, out, m, decl(100*time.Millisecond, metric("cache.hit_rate", "gauge", "/hitRate"))).Tick(context.Background())
	if time.Since(start) > 2*time.Second || testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", "http")) != 1 {
		t.Fatalf("tick took %v", time.Since(start))
	}
}

func TestAPanicIsRecoveredAndCounted(t *testing.T) {
	out, m := &sink{}, tmetrics.New()
	c := collector("unused", out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Adapter = &target{resolve: func() (runtime.Placement, error) { panic("adapter bug") }}
	c.Tick(context.Background()) // must return
	if v := testutil.ToFloat64(m.CollectorPanics.WithLabelValues("binding-1")); v != 1 {
		t.Fatalf("panics = %v", v)
	}
}

func TestABindingOutsideTheLeaseIsNotScraped(t *testing.T) {
	var gets atomic.Int32
	addr := serve(t, func(w http.ResponseWriter, _ *http.Request) { gets.Add(1); _, _ = io.WriteString(w, `{"hitRate": 1}`) })
	c := collector(addr, &sink{}, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.InLease = func(string) bool { return false }
	c.Tick(context.Background())
	if gets.Load() != 0 {
		t.Fatal("scraped a binding the lease does not name")
	}
}

// countingRT counts this collector's own requests in flight. Counting on the
// server would be flaky: a cancelled handler can still be finishing when the
// next scrape arrives, though the collector has already moved on.
type countingRT struct{ inflight, peak atomic.Int32 }

func (c *countingRT) RoundTrip(r *http.Request) (*http.Response, error) {
	n := c.inflight.Add(1)
	defer c.inflight.Add(-1)
	for p := c.peak.Load(); n > p && !c.peak.CompareAndSwap(p, n); p = c.peak.Load() {
	}
	return http.DefaultTransport.RoundTrip(r)
}

// Review Focus 5: an endpoint slower than the timeout, at the interval's edge.
func TestSlowEndpointNeverOverlaps(t *testing.T) {
	addr := serve(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(60 * time.Millisecond):
		case <-r.Context().Done():
			return
		}
		_, _ = io.WriteString(w, `{"hitRate": 1}`)
	})
	rt := &countingRT{}
	d := declare.Binding{Interval: 50 * time.Millisecond, Timeout: 50 * time.Millisecond, Metrics: []declare.Metric{metric("cache.hit_rate", "gauge", "/hitRate")}}
	c := collector(addr, &sink{}, nil, d)
	c.Client = &http.Client{Transport: rt}
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	c.Run(ctx)
	if p := rt.peak.Load(); p != 1 {
		t.Fatalf("peak in-flight scrapes %d, want 1", p)
	}
}
```

- [ ] **Step 2: Run them and watch them fail.**

Run: `cd go-agent && go test ./internal/telemetry/collect/`
Expected: build failure, `undefined: Collector`.

- [ ] **Step 3: Implement.**

```go
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
```

- [ ] **Step 4: Run them and watch them pass, race-clean.**

Run: `cd go-agent && go test -race ./internal/telemetry/collect/`
Expected: `ok`.

- [ ] **Step 5: Prove two guards bite.**
  1. Remove the bound: read with `io.ReadAll(resp.Body)` instead of the `io.LimitReader` call, and delete the `if len(body) > MaxBody {` block. Run `-run OversizedBody` — it must FAIL, because the whole 2 MiB document decodes and `hitRate` is appended.
  2. Restore both.
  3. Delete the `defer func() { if r := recover()…` block and run `-run PanicIsRecovered` — the test binary must crash.
  4. Restore it.

- [ ] **Step 6: Commit.**

```bash
cd go-agent && git add internal/telemetry/collect
git commit -m "go-agent: the http_json collector — isolated, bounded, never a zero

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: `send` — the pure sender reducer (`AG-D23`)

**Files:**
- Create: `go-agent/internal/telemetry/send/reducer.go`
- Test: `go-agent/internal/telemetry/send/reducer_test.go`

**Interfaces:**
- Consumes: `msg.Limits`, `msg.ErrorCode` constants, the `buffer.Reason*` constants.
- Produces:
  - `send.Head{Stream string; Seq uint64; ProducedAt time.Time; Bytes int64; IDsKnown bool}`
  - `send.Env{Now time.Time; Heads []Head; Jitter func(time.Duration) time.Duration}`
  - `send.State`, with `send.NewState() State`
  - **Events:** `SessionUp{ID; Limits}`, `SessionDown`, `StreamOpened`, `StreamBroken`, `Ack{Stream; HWM; Credits}`, `Credit{N}`, `Reject{Stream; Code; RetryAfter}`, `Unsendable{Stream}`, `Wake`
  - **Effects:** `OpenStream{After}`, `CloseStream`, `Send{Stream; Seq}`, `Trim{Stream; HWM}`, `DropAsGap{Stream; Seq; Reason}`, `Requeue{Stream}`, `ArmRetry{At}`
  - `send.Reduce(State, Event, Env) (State, []Effect)`

- [ ] **Step 1: Write the failing tests.**

```go
package send

import (
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
)

var t0 = time.Date(2026, 10, 1, 7, 0, 0, 0, time.UTC)

func noJitter(d time.Duration) time.Duration { return d }

func env(now time.Time, heads ...Head) Env { return Env{Now: now, Heads: heads, Jitter: noJitter} }

func head(stream string, seq uint64, producedSec int) Head {
	return Head{Stream: stream, Seq: seq, ProducedAt: t0.Add(time.Duration(producedSec) * time.Second), Bytes: 300, IDsKnown: true}
}

var limits = msg.Limits{InitialCredits: 2, MaxBatchBytes: 65536, ReportInterval: time.Minute}

// up returns a state with an open stream and the given credits.
func up(credits uint32) State {
	s, _ := Reduce(NewState(), SessionUp{ID: "s1", Limits: limits}, env(t0))
	s, _ = Reduce(s, StreamOpened{}, env(t0))
	s.Credits = credits
	return s
}

func sends(effs []Effect) []Send {
	var out []Send
	for _, e := range effs {
		if s, ok := e.(Send); ok {
			out = append(out, s)
		}
	}
	return out
}

func TestSessionUpOpensTheStreamAndNothingIsSentUntilItOpens(t *testing.T) {
	s, effs := Reduce(NewState(), SessionUp{ID: "s1", Limits: limits}, env(t0, head("a", 1, 0)))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}}) || s.Credits != 2 || s.Up {
		t.Fatalf("state %+v effects %+v", s, effs)
	}
	_, effs = Reduce(s, StreamOpened{}, env(t0, head("a", 1, 0)))
	if !reflect.DeepEqual(sends(effs), []Send{{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestCreditsAreSpentOnTheOldestHeadsFirst(t *testing.T) {
	s, effs := Reduce(up(2), Wake{}, env(t0, head("a", 1, 1), head("b", 1, 2), head("c", 1, 3)))
	if !reflect.DeepEqual(sends(effs), []Send{{"a", 1}, {"b", 1}}) || s.Credits != 0 {
		t.Fatalf("credits %d effects %+v", s.Credits, effs)
	}
}

func TestOneBatchInFlightPerStream(t *testing.T) {
	s := up(4)
	s.InFlight["a"] = 1
	if _, effs := Reduce(s, Wake{}, env(t0, head("a", 2, 1))); len(sends(effs)) != 0 {
		t.Fatalf("sent a second batch on a busy stream: %+v", effs)
	}
}

func TestAnAckTrimsFreesTheStreamAndSkipsTheStaleHead(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 1
	// heads were read before the trim, so a/1 is still listed
	s, effs := Reduce(s, Ack{Stream: "a", HWM: 1, Credits: 1}, env(t0, head("a", 1, 1), head("b", 1, 2)))
	want := []Effect{Trim{Stream: "a", HWM: 1}, Send{Stream: "b", Seq: 1}}
	if !reflect.DeepEqual(effs, want) || len(s.InFlight) != 1 {
		t.Fatalf("effects %+v inflight %v", effs, s.InFlight)
	}
}

func TestAWatermarkAckBelowTheInFlightBatchKeepsItInFlight(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 5
	s, _ = Reduce(s, Ack{Stream: "a", HWM: 4}, env(t0))
	if s.InFlight["a"] != 5 {
		t.Fatalf("inflight %v", s.InFlight)
	}
}

func TestATerminalRejectDropsTheBatchAsAGapAndRestoresTheCredit(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 3
	s, effs := Reduce(s, Reject{Stream: "a", Code: msg.ErrUnknownReplica}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{DropAsGap{Stream: "a", Seq: 3, Reason: "UNKNOWN_REPLICA"}}) || s.Credits != 1 {
		t.Fatalf("effects %+v credits %d", effs, s.Credits)
	}
}

func TestARetryableRejectWaitsOutRetryAfter(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 3
	s, effs := Reduce(s, Reject{Stream: "a", Code: msg.ErrInternalUnavailable, RetryAfter: 5 * time.Second}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{Requeue{Stream: "a"}}) || s.Credits != 1 {
		t.Fatalf("effects %+v credits %d", effs, s.Credits)
	}
	_, effs = Reduce(s, Wake{}, env(t0.Add(time.Second), head("a", 3, 0)))
	if len(sends(effs)) != 0 || !reflect.DeepEqual(effs, []Effect{ArmRetry{At: t0.Add(5 * time.Second)}}) {
		t.Fatalf("before retry_after: %+v", effs)
	}
	_, effs = Reduce(s, Wake{}, env(t0.Add(6*time.Second), head("a", 3, 0)))
	if !reflect.DeepEqual(sends(effs), []Send{{"a", 3}}) {
		t.Fatalf("after retry_after: %+v", effs)
	}
}

func TestOutOfCreditsZeroesTheCount(t *testing.T) {
	s := up(3)
	s.InFlight["a"] = 1
	s, effs := Reduce(s, Reject{Stream: "a", Code: msg.ErrOutOfCredits}, env(t0, head("b", 1, 0)))
	if s.Credits != 0 || !reflect.DeepEqual(effs, []Effect{Requeue{Stream: "a"}}) {
		t.Fatalf("credits %d effects %+v", s.Credits, effs)
	}
}

func TestAnOversizeHeadIsDroppedWithoutSpendingACredit(t *testing.T) {
	big := head("a", 1, 0)
	big.Bytes = 70000
	s, effs := Reduce(up(1), Wake{}, env(t0, big))
	if !reflect.DeepEqual(effs, []Effect{DropAsGap{Stream: "a", Seq: 1, Reason: buffer.ReasonOversize}}) || s.Credits != 1 {
		t.Fatalf("effects %+v credits %d", effs, s.Credits)
	}
}

// Review Focus 1.
func TestUnresolvedIdsAreDroppedAfterTwoReportIntervals(t *testing.T) {
	h := head("a", 1, 0)
	h.IDsKnown = false
	s := up(1) // stream opened at t0
	if _, effs := Reduce(s, Wake{}, env(t0.Add(90*time.Second), h)); len(effs) != 0 {
		t.Fatalf("dropped too early: %+v", effs)
	}
	if _, effs := Reduce(s, Wake{}, env(t0.Add(121*time.Second), h)); !reflect.DeepEqual(effs, []Effect{DropAsGap{Stream: "a", Seq: 1, Reason: buffer.ReasonUnregistered}}) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestAStreamBreakRequeuesRestoresCreditsAndReopensWithBackoff(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 1
	s.InFlight["b"] = 4
	s, effs := Reduce(s, StreamBroken{}, env(t0))
	want := []Effect{Requeue{Stream: "a"}, Requeue{Stream: "b"}, CloseStream{}, OpenStream{After: time.Second}}
	if !reflect.DeepEqual(effs, want) || s.Credits != 2 || s.Up {
		t.Fatalf("effects %+v state %+v", effs, s)
	}
	_, effs = Reduce(s, StreamBroken{}, env(t0))
	if effs[len(effs)-1] != (OpenStream{After: 2 * time.Second}) {
		t.Fatalf("second break: %+v", effs)
	}
}

func TestSessionDownStopsEverything(t *testing.T) {
	s := up(2)
	s.InFlight["a"] = 1
	s, effs := Reduce(s, SessionDown{}, env(t0, head("b", 1, 0)))
	if !reflect.DeepEqual(effs, []Effect{Requeue{Stream: "a"}, CloseStream{}}) || s.Credits != 0 || s.SessionID != "" {
		t.Fatalf("effects %+v state %+v", effs, s)
	}
	if _, effs := Reduce(s, Wake{}, env(t0, head("b", 1, 0))); len(effs) != 0 {
		t.Fatalf("sent with no session: %+v", effs)
	}
}

// Review Focus 4: six streams, four credits; each ack frees one credit.
func TestMoreStreamsThanCreditsNeverStarves(t *testing.T) {
	s := up(4)
	streams := []string{"a", "b", "c", "d", "e", "f"}
	next := map[string]uint64{"a": 1, "b": 1, "c": 1, "d": 1, "e": 1, "f": 1}
	produced := map[string]int{"a": 1, "b": 2, "c": 3, "d": 4, "e": 5, "f": 6}
	heads := func() []Head {
		var hs []Head
		for _, st := range streams {
			if _, busy := s.InFlight[st]; !busy {
				hs = append(hs, head(st, next[st], produced[st]))
			}
		}
		sortHeads(hs)
		return hs
	}
	sent := map[string]int{}
	var effs []Effect
	s, effs = Reduce(s, Wake{}, env(t0, heads()...))
	for round := 0; round < 12; round++ {
		for _, x := range sends(effs) {
			sent[x.Stream]++
		}
		// ack the oldest in-flight batch; its stream's next batch is newer than every head
		var oldest string
		for st := range s.InFlight {
			if oldest == "" || produced[st] < produced[oldest] {
				oldest = st
			}
		}
		next[oldest]++
		produced[oldest] += 10
		s, effs = Reduce(s, Ack{Stream: oldest, HWM: next[oldest] - 1, Credits: 1}, env(t0, heads()...))
	}
	for _, st := range streams {
		if sent[st] < 2 {
			t.Fatalf("stream %s starved: %v", st, sent)
		}
	}
}

func TestReduceDoesNotMutateItsInput(t *testing.T) {
	s := up(1)
	s.InFlight["a"] = 1
	_, _ = Reduce(s, Ack{Stream: "a", HWM: 1, Credits: 1}, env(t0, head("b", 1, 0)))
	if s.InFlight["a"] != 1 || len(s.Acked) != 0 {
		t.Fatalf("input mutated: %+v", s)
	}
}
```

- [ ] **Step 2: Run them and watch them fail.**

Run: `cd go-agent && go test ./internal/telemetry/send/`
Expected: build failure, `undefined: Reduce`.

- [ ] **Step 3: Implement.**

```go
// Package send is the telemetry sender (AG-D21, AG-D23): a pure reducer over
// credits, in-flight batches and the stream's state, and a runner (runner.go)
// that executes its effects against the buffer and the Telemetry stream.
package send

import (
	"maps"
	"slices"
	"strings"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
)

// Head is a buffered stream's oldest unsent batch, as the runner read it.
type Head struct {
	Stream     string
	Seq        uint64
	ProducedAt time.Time
	Bytes      int64
	IDsKnown   bool
}

// Env carries every impurity into Reduce.
type Env struct {
	Now    time.Time
	Heads  []Head // oldest produced_at first
	Jitter func(time.Duration) time.Duration
}

type State struct {
	SessionID      string
	Up             bool
	UpSince        time.Time
	Credits        uint32
	MaxBatchBytes  uint64
	ReportInterval time.Duration
	Failures       int
	InFlight       map[string]uint64
	Acked          map[string]uint64 // highest watermark seen per stream: filters stale heads
	RetryAt        map[string]time.Time
}

func NewState() State {
	return State{InFlight: map[string]uint64{}, Acked: map[string]uint64{}, RetryAt: map[string]time.Time{}}
}

type Event interface{ event() }

type (
	SessionUp struct {
		ID     string
		Limits msg.Limits
	}
	SessionDown  struct{}
	StreamOpened struct{}
	StreamBroken struct{}
	Ack          struct {
		Stream  string
		HWM     uint64
		Credits uint32
	}
	Credit struct{ N uint32 }
	Reject struct {
		Stream     string
		Code       msg.ErrorCode
		RetryAfter time.Duration
	}
	Unsendable struct{ Stream string } // the runner could not send what was selected
	Wake       struct{}               // the buffer, the view or a timer changed
)

func (SessionUp) event()    {}
func (SessionDown) event()  {}
func (StreamOpened) event() {}
func (StreamBroken) event() {}
func (Ack) event()          {}
func (Credit) event()       {}
func (Reject) event()       {}
func (Unsendable) event()   {}
func (Wake) event()         {}

type Effect interface{ effect() }

type (
	OpenStream  struct{ After time.Duration }
	CloseStream struct{}
	Send        struct {
		Stream string
		Seq    uint64
	}
	Trim struct {
		Stream string
		HWM    uint64
	}
	DropAsGap struct {
		Stream string
		Seq    uint64
		Reason string
	}
	Requeue  struct{ Stream string }
	ArmRetry struct{ At time.Time }
)

func (OpenStream) effect()  {}
func (CloseStream) effect() {}
func (Send) effect()        {}
func (Trim) effect()        {}
func (DropAsGap) effect()   {}
func (Requeue) effect()     {}
func (ArmRetry) effect()    {}

// terminal rejects: the same bytes can never be accepted, so retrying is
// pointless and the batch becomes a gap (spec §5.2).
var terminal = map[msg.ErrorCode]bool{
	msg.ErrPayloadTooLarge: true, msg.ErrUnknownReplica: true, msg.ErrIncarnationRetired: true,
	msg.ErrUndeclaredLabel: true, msg.ErrLabelInvalid: true, msg.ErrBindingNotInLease: true, msg.ErrTenantMismatch: true,
}

const (
	backoffBase  = time.Second
	backoffCap   = 30 * time.Second
	retryDefault = time.Second
)

func backoff(failures int) time.Duration {
	d := backoffBase << max(failures-1, 0)
	if d <= 0 || d > backoffCap {
		return backoffCap
	}
	return d
}

func (s State) clone() State {
	s.InFlight, s.Acked, s.RetryAt = maps.Clone(s.InFlight), maps.Clone(s.Acked), maps.Clone(s.RetryAt)
	if s.InFlight == nil {
		s.InFlight = map[string]uint64{}
	}
	if s.Acked == nil {
		s.Acked = map[string]uint64{}
	}
	if s.RetryAt == nil {
		s.RetryAt = map[string]time.Time{}
	}
	return s
}

// requeueAll returns every in-flight batch to unsent, in stream order.
func (s *State) requeueAll() []Effect {
	var effs []Effect
	for _, st := range slices.Sorted(maps.Keys(s.InFlight)) {
		effs = append(effs, Requeue{Stream: st})
	}
	clear(s.InFlight)
	return effs
}

func Reduce(in State, ev Event, env Env) (State, []Effect) {
	s := in.clone()
	var effs []Effect
	switch e := ev.(type) {
	case SessionUp:
		if e.ID == s.SessionID {
			break
		}
		if s.SessionID != "" {
			effs = append(effs, s.requeueAll()...)
			effs = append(effs, CloseStream{})
		}
		s.SessionID, s.Up, s.Failures = e.ID, false, 0
		s.Credits = e.Limits.InitialCredits // the old session's credits are void
		s.MaxBatchBytes, s.ReportInterval = e.Limits.MaxBatchBytes, e.Limits.ReportInterval
		effs = append(effs, OpenStream{})
	case SessionDown:
		if s.SessionID == "" {
			break
		}
		effs = append(effs, s.requeueAll()...)
		effs = append(effs, CloseStream{})
		s.SessionID, s.Up, s.Credits = "", false, 0
	case StreamOpened:
		if s.SessionID == "" {
			break
		}
		s.Up, s.UpSince, s.Failures = true, env.Now, 0
	case StreamBroken:
		if s.SessionID == "" {
			break
		}
		// Each in-flight batch held one credit its ack would have returned;
		// the session keeps its pool across a stream break (AG-17 assumption).
		s.Credits += uint32(len(s.InFlight))
		effs = append(effs, s.requeueAll()...)
		s.Up = false
		s.Failures++
		effs = append(effs, CloseStream{}, OpenStream{After: env.Jitter(backoff(s.Failures))})
	case Ack:
		s.Acked[e.Stream] = max(s.Acked[e.Stream], e.HWM)
		effs = append(effs, Trim{Stream: e.Stream, HWM: e.HWM})
		if seq, ok := s.InFlight[e.Stream]; ok && seq <= e.HWM {
			delete(s.InFlight, e.Stream)
		}
		s.Credits += e.Credits
	case Credit:
		s.Credits += e.N
	case Reject:
		seq, ok := s.InFlight[e.Stream]
		if !ok {
			break
		}
		delete(s.InFlight, e.Stream)
		switch {
		case e.Code == msg.ErrOutOfCredits:
			s.Credits = 0
			effs = append(effs, Requeue{Stream: e.Stream})
		case terminal[e.Code]:
			s.Credits++ // refused before the credit was accepted (gateway §5.5)
			effs = append(effs, DropAsGap{Stream: e.Stream, Seq: seq, Reason: string(e.Code)})
		default:
			s.Credits++
			ra := e.RetryAfter
			if ra <= 0 {
				ra = retryDefault
			}
			s.RetryAt[e.Stream] = env.Now.Add(ra)
			effs = append(effs, Requeue{Stream: e.Stream})
		}
	case Unsendable:
		if _, ok := s.InFlight[e.Stream]; ok {
			delete(s.InFlight, e.Stream)
			s.Credits++
		}
	case Wake:
	}
	return s, append(effs, s.selectSends(env)...)
}

// selectSends spends credits on the oldest eligible head across streams.
// Drops (oversize, unregistered) cost no credit. Stale heads — at or below a
// watermark this reduction already acked — are skipped, because the buffer
// trims only when the effect runs.
func (s *State) selectSends(env Env) []Effect {
	if !s.Up {
		return nil
	}
	var effs []Effect
	var nextRetry time.Time
	for _, h := range env.Heads {
		_, busy := s.InFlight[h.Stream]
		switch {
		case h.Seq <= s.Acked[h.Stream] || busy:
			continue
		case env.Now.Before(s.RetryAt[h.Stream]):
			if nextRetry.IsZero() || s.RetryAt[h.Stream].Before(nextRetry) {
				nextRetry = s.RetryAt[h.Stream]
			}
			continue
		case s.MaxBatchBytes > 0 && uint64(h.Bytes) > s.MaxBatchBytes:
			effs = append(effs, DropAsGap{Stream: h.Stream, Seq: h.Seq, Reason: buffer.ReasonOversize})
			continue
		case !h.IDsKnown:
			if env.Now.Sub(s.UpSince) > s.unregisteredAfter() {
				effs = append(effs, DropAsGap{Stream: h.Stream, Seq: h.Seq, Reason: buffer.ReasonUnregistered})
			}
			continue
		case s.Credits == 0:
			continue
		}
		s.Credits--
		s.InFlight[h.Stream] = h.Seq
		effs = append(effs, Send{Stream: h.Stream, Seq: h.Seq})
	}
	if !nextRetry.IsZero() {
		effs = append(effs, ArmRetry{At: nextRetry})
	}
	return effs
}

// unregisteredAfter: ids still unknown after two report intervals of an open
// stream will not resolve — the report that would mint them has had two
// chances (Review Focus 1).
func (s State) unregisteredAfter() time.Duration {
	ri := s.ReportInterval
	if ri <= 0 {
		ri = time.Minute
	}
	return 2 * ri
}

// sortHeads orders heads oldest produced_at first, then by stream — the order
// Env.Heads must arrive in.
func sortHeads(hs []Head) {
	slices.SortFunc(hs, func(x, y Head) int {
		if c := x.ProducedAt.Compare(y.ProducedAt); c != 0 {
			return c
		}
		return strings.Compare(x.Stream, y.Stream)
	})
}
```

- [ ] **Step 4: Run them and watch them pass.**

Run: `cd go-agent && go test ./internal/telemetry/send/`
Expected: `ok`.

- [ ] **Step 5: Prove the one-in-flight guard bites.** Change `case h.Seq <= s.Acked[h.Stream] || busy:` to `case h.Seq <= s.Acked[h.Stream]:`, run `-run OneBatchInFlightPerStream` — it must FAIL. Restore it.

- [ ] **Step 6: Commit.**

```bash
cd go-agent && git add internal/telemetry/send
git commit -m "go-agent: the telemetry sender reducer — credits, one in flight per stream

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: `send.Runner` — executing the reducer's effects

**Files:**
- Create: `go-agent/internal/telemetry/send/runner.go`
- Test: `go-agent/internal/telemetry/send/runner_test.go`

**Interfaces:**
- Consumes:
  - `buffer.Buffer`: `Heads`, `Read`, `MarkInFlight`, `ClearInFlight`, `Trim`, `DropAsGap`, `GapsBefore`, `Changed`
  - `view.View`, `view.Snapshot`, `view.SessionInfo`, `view.Stream`
  - `tmetrics.Metrics`
  - Task 11's `Reduce`
- Produces:
  - `send.Runner{Buf *buffer.Buffer; View view.View; NewID func() string; Now func() time.Time; Jitter func(time.Duration) time.Duration; Log *slog.Logger; Metrics *tmetrics.Metrics}`
  - `(*Runner).Run(ctx)`, which returns when ctx ends

- [ ] **Step 1: Write the failing tests.**

```go
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
```

- [ ] **Step 2: Run them and watch them fail.**

Run: `cd go-agent && go test ./internal/telemetry/send/ -run Runner`
Expected: build failure, `undefined: Runner`.

- [ ] **Step 3: Implement.**

```go
package send

import (
	"context"
	"log/slog"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/tmetrics"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
)

// Runner executes Reduce's effects. One goroutine — Run — owns the state and
// the stream; the stream's reader posts back through in.
type Runner struct {
	Buf     *buffer.Buffer
	View    view.View
	NewID   func() string
	Now     func() time.Time
	Jitter  func(time.Duration) time.Duration
	Log     *slog.Logger
	Metrics *tmetrics.Metrics

	state   State
	session *view.SessionInfo
	stream  view.Stream
	gen     int // bumped per OpenStream; stale stream events are dropped
	cancel  context.CancelFunc
	retry   *time.Timer
	in      chan any
	queue   []Event
}

type (
	opened struct {
		gen int
		s   view.Stream
	}
	fromStream struct {
		gen int
		ev  Event
	}
)

// wakeEvery bounds how long a time-based decision (UNREGISTERED) can wait
// when nothing else wakes the runner.
const wakeEvery = 5 * time.Second

func (r *Runner) Run(ctx context.Context) {
	r.state = NewState()
	r.in = make(chan any, 64)
	r.retry = time.NewTimer(time.Hour)
	r.retry.Stop()
	tick := time.NewTicker(wakeEvery)
	defer tick.Stop()
	defer r.closeStream()
	r.onView(ctx)
	for {
		viewCh, bufCh := r.View.Changed(), r.Buf.Changed() // before reading, so no change is missed
		select {
		case <-ctx.Done():
			return
		case <-viewCh:
			r.onView(ctx)
		case <-bufCh:
			r.step(ctx, Wake{})
		case <-tick.C:
			r.step(ctx, Wake{})
		case <-r.retry.C:
			r.step(ctx, Wake{})
		case m := <-r.in:
			switch m := m.(type) {
			case opened:
				if m.gen != r.gen {
					_ = m.s.Close()
					continue
				}
				r.stream = m.s
				go r.read(ctx, m.gen, m.s)
				r.step(ctx, StreamOpened{})
			case fromStream:
				if m.gen == r.gen {
					r.step(ctx, m.ev)
				}
			}
		}
	}
}

func (r *Runner) onView(ctx context.Context) {
	snap := r.View.Current()
	switch {
	case snap.Session != nil && snap.Session.ID != r.state.SessionID:
		r.session = snap.Session
		r.step(ctx, SessionUp{ID: snap.Session.ID, Limits: snap.Session.Limits})
	case snap.Session == nil && r.state.SessionID != "":
		r.session = nil
		r.step(ctx, SessionDown{})
	default:
		r.step(ctx, Wake{}) // ids or bindings may have changed
	}
}

// step reduces ev and executes its effects. An effect that must feed a new
// event back (a failed send) queues it, and step drains the queue in order.
func (r *Runner) step(ctx context.Context, ev Event) {
	r.queue = append(r.queue, ev)
	for len(r.queue) > 0 {
		ev := r.queue[0]
		r.queue = r.queue[1:]
		snap := r.View.Current()
		prev := len(r.state.InFlight)
		next, effs := Reduce(r.state, ev, Env{Now: r.Now(), Heads: r.heads(snap), Jitter: r.Jitter})
		if _, isAck := ev.(Ack); isAck && prev > len(next.InFlight) {
			r.Metrics.AckedBatches(prev - len(next.InFlight))
		}
		r.state = next
		r.Metrics.SetCredits(next.Credits)
		for _, e := range effs {
			r.exec(ctx, e, snap)
		}
	}
}

func (r *Runner) heads(snap view.Snapshot) []Head {
	var out []Head
	for _, h := range r.Buf.Heads() {
		known := len(h.Refs) > 0
		for _, ref := range h.Refs {
			if _, _, ok := snap.Lookup(h.Stream, ref.ReplicaRef, ref.Incarnation); !ok {
				known = false
				break
			}
		}
		out = append(out, Head{Stream: h.Stream, Seq: h.Seq, ProducedAt: h.ProducedAt, Bytes: h.Bytes, IDsKnown: known})
	}
	return out // Buf.Heads is already oldest-first
}

func (r *Runner) exec(ctx context.Context, e Effect, snap view.Snapshot) {
	switch e := e.(type) {
	case OpenStream:
		r.closeStream()
		r.gen++
		gen, info := r.gen, r.session
		sctx, cancel := context.WithCancel(ctx)
		r.cancel = cancel
		go func() {
			if e.After > 0 {
				t := time.NewTimer(e.After)
				select {
				case <-sctx.Done():
					t.Stop()
					return
				case <-t.C:
				}
			}
			if info == nil {
				return
			}
			s, err := info.Open(sctx)
			if err != nil {
				r.Log.Debug("telemetry stream open failed", "err", err)
				r.post(sctx, fromStream{gen: gen, ev: StreamBroken{}})
				return
			}
			r.post(sctx, opened{gen: gen, s: s})
		}()
	case CloseStream:
		r.closeStream()
	case Send:
		if !r.Buf.MarkInFlight(e.Stream, e.Seq) {
			r.queue = append(r.queue, Unsendable{Stream: e.Stream})
			return
		}
		b, err := r.Buf.Read(e.Stream, e.Seq)
		m, ok := r.message(b, snap)
		if err != nil || !ok {
			r.Buf.ClearInFlight(e.Stream)
			r.queue = append(r.queue, Unsendable{Stream: e.Stream})
			return
		}
		if r.stream == nil || r.stream.Send(m) != nil {
			r.queue = append(r.queue, StreamBroken{})
			return
		}
		r.Metrics.Sent()
	case Trim:
		r.Buf.Trim(e.Stream, e.HWM)
	case DropAsGap:
		r.Buf.DropAsGap(e.Stream, e.Seq, e.Reason)
	case Requeue:
		r.Buf.ClearInFlight(e.Stream)
	case ArmRetry:
		r.retry.Reset(time.Until(e.At))
	}
}

// message labels every sample with exactly the three DP-minted ids
// (gateway §6.1) and attaches the gaps this batch must carry.
func (r *Runner) message(b buffer.Batch, snap view.Snapshot) (msg.TelemetryBatch, bool) {
	out := msg.TelemetryBatch{Envelope: msg.Envelope{MessageID: r.NewID(), SentAt: r.Now()}, StreamID: b.StreamID, BatchSeq: b.Seq, ProducedAt: b.ProducedAt}
	for _, s := range b.Samples {
		rid, iid, ok := snap.Lookup(b.StreamID, s.ReplicaRef, s.Incarnation)
		if !ok {
			return out, false
		}
		out.Samples = append(out.Samples, msg.Sample{SeriesKey: s.Name, Type: msg.MetricType(s.Type), TS: s.TS, Value: s.Value,
			Labels: map[string]string{"binding_id": b.StreamID, "replica_id": rid, "incarnation_id": iid}})
	}
	for _, g := range r.Buf.GapsBefore(b.StreamID, b.Seq) {
		out.GapMarkers = append(out.GapMarkers, msg.GapMarker{FromSeq: g.From, ToSeq: g.To, Reason: g.Reason})
	}
	return out, true
}

func (r *Runner) read(ctx context.Context, gen int, s view.Stream) {
	for {
		m, err := s.Recv()
		if err != nil {
			r.post(ctx, fromStream{gen: gen, ev: StreamBroken{}})
			return
		}
		var ev Event
		switch m := m.(type) {
		case msg.TelemetryAck:
			ev = Ack{Stream: m.StreamID, HWM: m.HighWatermarkSeq, Credits: m.CreditsGranted}
		case msg.TelemetryCredit:
			ev = Credit{N: m.CreditsGranted}
		case msg.TelemetryReject:
			r.Metrics.Reject(string(m.Code))
			var ra time.Duration
			if m.RetryAfter != nil {
				ra = *m.RetryAfter
			}
			ev = Reject{Stream: m.StreamID, Code: m.Code, RetryAfter: ra}
		default:
			continue
		}
		r.post(ctx, fromStream{gen: gen, ev: ev})
	}
}

func (r *Runner) post(ctx context.Context, m any) {
	select {
	case r.in <- m:
	case <-ctx.Done():
	}
}

func (r *Runner) closeStream() {
	if r.cancel != nil {
		r.cancel()
		r.cancel = nil
	}
	if r.stream != nil {
		_ = r.stream.Close()
		r.stream = nil
	}
}
```

- [ ] **Step 4: Run them and watch them pass, race-clean.**

Run: `cd go-agent && go test -race ./internal/telemetry/send/`
Expected: `ok`.

- [ ] **Step 5: Commit.**

```bash
cd go-agent && git add internal/telemetry/send
git commit -m "go-agent: the telemetry sender's runner

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Pipeline, roles, capability gate and loop wiring

**Files:**
- Create: `go-agent/internal/telemetry/pipeline.go`
- Create: `go-agent/internal/agent/roles.go`, test `go-agent/internal/agent/roles_test.go`
- Modify: `go-agent/internal/reactor/reactor.go` (`Connected` gains `TelemetryResume`)
- Modify: `go-agent/internal/agent/session.go` (`Connected` puts it in `SessionOpen.Resume`); test `session_test.go` (append)
- Modify: `go-agent/internal/agent/state.go` (`Reduce` passes `gate{…}` to `commands.Reduce`)
- Modify: `go-agent/internal/agent/loop.go`
  - `Deps` gains `View`, `Telemetry`, `TelemetryResume`;
  - `Loop` gains `viewPublished`;
  - `translate` fills `TelemetryResume`;
  - `Run` starts and waits for the pipeline and publishes the view;
  - add `publish`, `telemetryOpener`.
- Modify: `go-agent/internal/agent/wiring.go`
  - `BuildConfig` gains `Roles`, `Telemetry`;
  - `TelemetryConfig`;
  - `wireConn`;
  - `Build` assembles the pipeline.
- Modify: `go-agent/internal/agent/admin.go` (`Metrics.Register`)
- Create: `go-agent/cmd/sm-agent/bytes.go`, test `bytes_test.go`
- Modify: `go-agent/cmd/sm-agent/main.go` (flags)

**Interfaces:**
- Consumes: every earlier task.
- Produces:
  - `telemetry.Config`, `telemetry.New(Config) (*Pipeline, error)`, and on `*Pipeline`: `Run(ctx)`, `Resume() map[string]uint64`, `Metrics() *tmetrics.Metrics`
  - `agent.Roles{Control, Telemetry bool}`, `agent.ParseRoles(string) (Roles, error)`
  - `agent.TelemetryConfig{Declarations *declare.File; BufferBytes int64; BufferAge time.Duration}`
  - `BuildConfig.Roles`, `BuildConfig.Telemetry`
  - `reactor.Connected.TelemetryResume map[string]uint64`

- [ ] **Step 1: Write the failing unit tests.** Create `internal/agent/roles_test.go`:

```go
package agent

import (
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestParseRoles(t *testing.T) {
	for in, want := range map[string]Roles{
		"control":           {Control: true},
		"telemetry":         {Telemetry: true},
		"control,telemetry": {Control: true, Telemetry: true},
		" telemetry ,control": {Control: true, Telemetry: true},
	} {
		got, err := ParseRoles(in)
		if err != nil || got != want {
			t.Errorf("%q → %+v %v, want %+v", in, got, err, want)
		}
	}
	if _, err := ParseRoles("control,billing"); err == nil {
		t.Error("an unknown role must be refused")
	}
}

func TestCapabilitiesFollowTheRoles(t *testing.T) {
	both := []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}
	for r, want := range map[Roles][]msg.Capability{
		{}:                                 both, // a zero Roles is the slice-1 default
		{Control: true}:                    both,
		{Control: true, Telemetry: true}:   both,
		{Telemetry: true}:                  {msg.CapReportOnly},
	} {
		if got := r.capabilities(); !reflect.DeepEqual(got, want) {
			t.Errorf("%+v → %v, want %v", r, got, want)
		}
	}
}

// Deviation 1: the lease grants lifecycle, but this agent did not advertise it.
func TestGateRefusesWhatTheAgentDidNotAdvertise(t *testing.T) {
	g := msg.LeaseGrant{Capabilities: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}, BindingIDs: []string{"b"}, ExpiresAt: time.Now().Add(time.Hour)}
	a := authority.State{Phase: authority.PhaseActive, Grant: &g}
	if got := (gate{State: a, supported: []msg.Capability{msg.CapReportOnly}}).RefusalDetail(msg.OpRestart, "b", time.Now()); got != msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatalf("got %q", got)
	}
	if got := (gate{State: a, supported: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}}).RefusalDetail(msg.OpRestart, "b", time.Now()); got == msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatal("an advertised capability must reach the lease checks")
	}
}
```

Append to `internal/agent/session_test.go`:

```go
func TestSessionOpenCarriesTheTelemetryResumeHint(t *testing.T) {
	e := reactor.Env{Now: time.Unix(0, 0), NewID: func() string { return "id" }}
	_, effs := reduceSession(cfg, SessionState{Phase: PhaseConnecting},
		reactor.Connected{At: time.Unix(0, 0), TelemetryResume: map[string]uint64{"binding-1": 7}}, e, SessionInputs{})
	open, ok := effs[0].(reactor.Send).Msg.(msg.SessionOpen)
	if !ok || open.Resume.LastTelemetrySeqByStream["binding-1"] != 7 {
		t.Fatalf("SessionOpen %+v", effs)
	}
}
```

Create `cmd/sm-agent/bytes_test.go`:

```go
package main

import "testing"

func TestParseBytes(t *testing.T) {
	for in, want := range map[string]int64{"4096": 4096, "64KiB": 64 << 10, "64MiB": 64 << 20, "2GiB": 2 << 30} {
		if got, err := parseBytes(in); err != nil || got != want {
			t.Errorf("%q → %d %v, want %d", in, got, err, want)
		}
	}
	for _, bad := range []string{"", "0", "-1", "64MB", "MiB"} {
		if _, err := parseBytes(bad); err == nil {
			t.Errorf("%q must be refused", bad)
		}
	}
}
```

- [ ] **Step 2: Run them and watch them fail.**

Run: `cd go-agent && go test ./internal/agent/ ./cmd/sm-agent/`
Expected: build failures — `undefined: ParseRoles`, `undefined: gate`, `unknown field TelemetryResume`, `undefined: parseBytes`.

- [ ] **Step 3: Carry the resume hint.**
  - In `internal/reactor/reactor.go`:

    ```go
type Connected struct {
	At              time.Time
	TelemetryResume map[string]uint64 // SessionOpen.resume.last_telemetry_seq_by_stream
}
    ```

  - In `internal/agent/session.go`'s `case reactor.Connected:`, the `SessionOpen` literal's `Resume` becomes:

    ```go
			Resume: msg.Resume{LastAckedGenerationByBinding: in.Resume.LastAckedGenerationByBinding,
				LastTelemetrySeqByStream: e.TelemetryResume},
    ```

- [ ] **Step 4: Roles and the gate.** Create `internal/agent/roles.go`:

```go
package agent

import (
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// Roles are the agent's enabled roles (AG-D1; spec §2.3). The zero value is
// the slice-1 default: control.
type Roles struct{ Control, Telemetry bool }

func ParseRoles(s string) (Roles, error) {
	var r Roles
	for _, p := range strings.Split(s, ",") {
		switch strings.TrimSpace(p) {
		case "control":
			r.Control = true
		case "telemetry":
			r.Telemetry = true
		default:
			return Roles{}, fmt.Errorf("unknown role %q (want control, telemetry)", strings.TrimSpace(p))
		}
	}
	return r, nil
}

// capabilities is what SessionOpen advertises. A telemetry-only agent still
// runs the session and reports — a TRUSTED report is what mints the ids its
// samples carry — but executes nothing (AG-11 stays open on its lease).
func (r Roles) capabilities() []msg.Capability {
	if r.Control || !r.Telemetry {
		return []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}
	}
	return []msg.Capability{msg.CapReportOnly}
}

// gate refuses an operation whose capability this agent did not advertise
// before the lease is consulted: a lease that grants more than the agent
// supports must not widen what it executes.
type gate struct {
	authority.State
	supported []msg.Capability
}

func (g gate) RefusalDetail(op msg.Operation, bindingID string, dpNow time.Time) msg.DetailCode {
	if !slices.Contains(g.supported, op.RequiredCapability()) {
		return msg.DetailOf(msg.ErrCapabilityMissing)
	}
	return g.State.RefusalDetail(op, bindingID, dpNow)
}
```

In `internal/agent/state.go`'s `Reduce`, replace the `commands.Reduce` line with:

```go
	s.Commands, effs = commands.Reduce(s.Commands, ev, env, gate{State: s.Authority, supported: cfg.Capabilities})
```

- [ ] **Step 5: Byte sizes.** Create `cmd/sm-agent/bytes.go`:

```go
package main

import (
	"fmt"
	"strconv"
	"strings"
)

// parseBytes accepts a positive byte count with an optional KiB, MiB or GiB
// suffix.
func parseBytes(s string) (int64, error) {
	mult := int64(1)
	for _, u := range []struct {
		suffix string
		n      int64
	}{{"KiB", 1 << 10}, {"MiB", 1 << 20}, {"GiB", 1 << 30}} {
		if v, ok := strings.CutSuffix(s, u.suffix); ok {
			s, mult = v, u.n
			break
		}
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil || n <= 0 {
		return 0, fmt.Errorf("bad byte size %q (want e.g. 64MiB)", s)
	}
	return n * mult, nil
}
```

- [ ] **Step 6: Run the unit tests and watch them pass.**

Run: `cd go-agent && go test ./internal/agent/ ./cmd/sm-agent/`
Expected: `ok`, `ok`.

- [ ] **Step 7: The pipeline.** Create `internal/telemetry/pipeline.go`:

```go
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
```

- [ ] **Step 8: Publish the view and run the pipeline from the loop.** In `internal/agent/loop.go`:
  - `Deps` gains:

    ```go
	View            *view.Hub                         // nil: no telemetry role
	Telemetry       interface{ Run(context.Context) } // the pipeline; Run returns when ctx ends
	TelemetryResume func() map[string]uint64
    ```

  - `Loop` gains `viewPublished bool`.
  - In `translate`, the success return of `case dialResult:` becomes:

    ```go
		return reactor.Connected{At: e.at, TelemetryResume: l.telemetryResume()}, true
    ```

  - In `Run`, the top `defer` becomes:

    ```go
	var tele sync.WaitGroup
	defer func() {
		cancel()
		l.stopTimers()
		close(l.ops)
		tele.Wait() // Loop.Run releases every goroutine it starts, on every exit path
	}()
	if l.d.Telemetry != nil {
		tele.Go(func() { l.d.Telemetry.Run(ctx) })
	}
    ```

  - In `Run`, directly after `l.mu.Unlock()` that follows the `Reduce` call, add `l.publish(st, next, ev)`.
  - Append:

```go
func (l *Loop) telemetryResume() map[string]uint64 {
	if l.d.TelemetryResume == nil {
		return nil
	}
	return l.d.TelemetryResume()
}

// publish hands the telemetry pipeline a new view when what it reads may have
// changed: the session phase or id, the lease (replaced wholesale on every
// grant), or the id registry (changed only by a ReportAck). It runs on the
// loop goroutine, the only writer of these maps, so taking IDTable is safe.
func (l *Loop) publish(prev, next State, ev reactor.Event) {
	if l.d.View == nil {
		return
	}
	reportAck := false
	if w, ok := ev.(reactor.WireIn); ok {
		_, reportAck = w.Msg.(msg.ReportAck)
	}
	if l.viewPublished && !reportAck && prev.Session.Phase == next.Session.Phase &&
		prev.Session.SessionID == next.Session.SessionID && prev.Authority.Grant == next.Authority.Grant {
		return
	}
	l.viewPublished = true
	snap := view.Snapshot{IDs: next.Reports.IDTable(), Halted: next.Session.Phase == PhaseHalted, Bindings: map[string]bool{}}
	if g := next.Authority.Grant; g != nil {
		for _, b := range g.BindingIDs {
			snap.Bindings[b] = true
		}
	}
	if next.Session.Phase == PhaseActive && l.conn != nil {
		snap.Session = &view.SessionInfo{ID: next.Session.SessionID, Limits: next.Session.Limits, Open: telemetryOpener(l.conn)}
	}
	l.d.View.Publish(snap)
}

type telemetryConn interface {
	OpenTelemetry(ctx context.Context) (view.Stream, error)
}

// telemetryOpener binds the opener to this connection: after a reconnect a
// stale opener fails instead of opening on the new connection behind the
// sender's back.
func telemetryOpener(c Conn) func(context.Context) (view.Stream, error) {
	return func(ctx context.Context) (view.Stream, error) {
		tc, ok := c.(telemetryConn)
		if !ok {
			return nil, errors.New("agent: this connection carries no Telemetry stream")
		}
		return tc.OpenTelemetry(ctx)
	}
}
```

  - Add to `loop.go`'s imports: `"errors"` (if missing), `"sync"` (if missing), and `"github.com/solace/solution-manager/go-agent/internal/telemetry/view"`.

- [ ] **Step 9: Build it.** In `internal/agent/admin.go`, add:

```go
// Register adds collectors (the telemetry pipeline's) to /metrics.
func (m *Metrics) Register(cs ...prometheus.Collector) { m.reg.MustRegister(cs...) }
```

In `internal/agent/wiring.go`:

```go
type TelemetryConfig struct {
	Declarations *declare.File
	BufferBytes  int64
	BufferAge    time.Duration
}
```

`BuildConfig` gains `Roles Roles` and `Telemetry *TelemetryConfig`. In `Build`:
  - set `Capabilities: bc.Roles.capabilities()` in the `cfg` literal;
  - replace the `l := New(Deps{…}, st)` statement with a `deps := Deps{…}` literal carrying the same fields, followed by:

```go
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
```

In the existing `if bc.AdminAddr != "" {` block, after `m := NewMetrics()`:

```go
		if pipe != nil {
			m.Register(pipe.Metrics().Collectors()...)
		}
```

Change `wireAdapter.Dial` to return `wireConn{c}, nil` instead of `c, nil`, and add:

```go
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
```

Add to `wiring.go`'s imports: `"errors"`, `"github.com/solace/solution-manager/go-agent/internal/telemetry"`, `".../internal/telemetry/declare"` and `".../internal/telemetry/view"`.

- [ ] **Step 10: Flags.** In `cmd/sm-agent/main.go`:
  - after the existing flag definitions, add:

    ```go
	roles := flag.String("roles", envOr("SM_AGENT_ROLES", "control"), "comma list: control, telemetry")
	telemetryCfg := flag.String("telemetry-config", "", "metric declarations file; required with the telemetry role (AG-D22)")
	bufBytes := flag.String("telemetry-buffer-bytes", "64MiB", "AG-D9 byte cap across all streams (provisional default)")
	bufAge := flag.Duration("telemetry-buffer-age", 24*time.Hour, "AG-D9 time horizon (provisional default)")
    ```

  - after the `--log-level` check, add:

    ```go
	r, err := agent.ParseRoles(*roles)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	var tc *agent.TelemetryConfig
	if r.Telemetry {
		if *telemetryCfg == "" {
			fmt.Fprintln(os.Stderr, "--telemetry-config is required with the telemetry role")
			os.Exit(2)
		}
		decl, err := declare.Load(*telemetryCfg)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
		n, err := parseBytes(*bufBytes)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
		tc = &agent.TelemetryConfig{Declarations: decl, BufferBytes: n, BufferAge: *bufAge}
	}
    ```

  - pass `Roles: r, Telemetry: tc` in the `agent.BuildConfig` literal.
  - add `"time"` and `"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"` to the imports.

- [ ] **Step 11: Run the whole suite.**

Run: `cd go-agent && go vet ./... && go test ./... && go test -tags integration ./internal/agent/ && go test -race ./internal/telemetry/...`
Expected: all `ok`. Every slice-1 and console integration test still passes: Roles' zero value keeps the control capabilities.

- [ ] **Step 12: Smoke the flags.**

Run:

```bash
cd go-agent && make build
./bin/sm-agent --roles telemetry; echo "exit $?"
./bin/sm-agent --roles bogus; echo "exit $?"
```

Expected:
- the first prints `--telemetry-config is required with the telemetry role`, then `exit 2`;
- the second prints `unknown role "bogus" (want control, telemetry)`, then `exit 2`.

- [ ] **Step 13: Commit.**

```bash
cd go-agent && git add internal cmd
git commit -m "go-agent: wire the telemetry role — roles, capability gate, view, pipeline

--roles control,telemetry starts the pipeline beside the loop; the loop
publishes a view after any event that changes the session, the lease or the
id registry, and fills SessionOpen's telemetry resume hint. A --roles
telemetry agent advertises report-only, and an operation it did not advertise
is refused before the lease is consulted.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: End-to-end integration tests

**Files:**
- Create: `go-agent/internal/agent/telemetry_integration_test.go`

**Interfaces:**
- Consumes:
  - `startGateway`, `startAgent`, `waitPhase`, `newFakeAdapter` (with its `scrape` field), `syncBuffer`, `waitOutput`, `acksByCommand`
  - fakegw: `PauseTelemetry`, `ResumeTelemetry`, `Disconnect`, `Received`, `WaitFor`, `Sessions`, `Console`
  - `BuildConfig.Roles`, `BuildConfig.Telemetry`

- [ ] **Step 1: Write the tests.**

```go
//go:build integration

package agent

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
)

func ptrTo(s string) *string { return &s }

// fastDecl bypasses declare's 1 s interval floor so tests run in tenths of a second.
func fastDecl() *declare.File {
	return &declare.File{SchemaVersion: 1, Bindings: map[string]declare.Binding{"binding-1": {
		Interval: 100 * time.Millisecond, Timeout: 100 * time.Millisecond,
		Metrics: []declare.Metric{{Name: "cache.hit_rate", MetricType: "gauge", Unit: "ratio",
			Source: declare.Source{Kind: "http_json", Port: 8081, Path: "/api/cache/stats", Pointer: ptrTo("/hitRate"), OnUnavailable: "degrade"}}},
	}}}
}

// jsonTarget serves {"hitRate": <value>} and returns its host:port.
func jsonTarget(t *testing.T) (string, *atomic.Value) {
	var v atomic.Value
	v.Store(0.93)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = fmt.Fprintf(w, `{"hitRate": %v}`, v.Load())
	}))
	t.Cleanup(srv.Close)
	return strings.TrimPrefix(srv.URL, "http://"), &v
}

func startTelemetryAgent(t *testing.T, gw *fakegw.Server, dir string, roles Roles, bytes int64) (*agentHandle, *fakeAdapter) {
	t.Helper()
	addr, _ := jsonTarget(t)
	ad := newFakeAdapter(t)
	ad.scrape = addr
	h := startAgent(t, gw, dir, func(bc *BuildConfig) {
		bc.Adapter = ad
		bc.Roles = roles
		bc.Telemetry = &TelemetryConfig{Declarations: fastDecl(), BufferBytes: bytes, BufferAge: time.Hour}
	})
	return h, ad
}

func batches(gw *fakegw.Server) []msg.TelemetryBatch {
	var out []msg.TelemetryBatch
	for _, m := range gw.Received("TelemetryBatch") {
		out = append(out, m.(msg.TelemetryBatch))
	}
	return out
}

var both = Roles{Control: true, Telemetry: true}

func TestIntegrationTelemetryBatchesCarryMintedIDs(t *testing.T) {
	gw := startGateway(t, nil)
	h, _ := startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 1, 10*time.Second) {
		t.Fatal("no telemetry batch")
	}
	reg := h.loop.Snapshot().Reports
	want := map[string]string{
		"binding_id":     "binding-1",
		"replica_id":     reg.ReplicaID("binding-1", "java1"),
		"incarnation_id": reg.IncarnationID("binding-1", "java1", "container-abc"),
	}
	b := batches(gw)[0]
	if want["replica_id"] == "" || len(b.Samples) != 1 || !reflect.DeepEqual(b.Samples[0].Labels, want) || b.Samples[0].Value != 0.93 {
		t.Fatalf("batch %+v, want labels %v", b, want)
	}
}

func TestIntegrationTelemetryPauseBuffersResumeDrainsInOrder(t *testing.T) {
	gw := startGateway(t, nil)
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 1, 10*time.Second) {
		t.Fatal("no telemetry batch")
	}
	gw.PauseTelemetry()
	time.Sleep(1500 * time.Millisecond) // credits run dry, then ~10 ticks buffer on disk
	before := len(batches(gw))
	time.Sleep(500 * time.Millisecond)
	if n := len(batches(gw)); n != before {
		t.Fatalf("sent %d batches while paused and out of credits", n-before)
	}
	gw.ResumeTelemetry(4)
	if !gw.WaitFor("TelemetryBatch", before+8, 10*time.Second) {
		t.Fatalf("buffer did not drain: %d batches", len(batches(gw)))
	}
	last := uint64(0)
	for _, b := range batches(gw) {
		if b.BatchSeq <= last {
			t.Fatalf("seq %d after %d: out of order", b.BatchSeq, last)
		}
		last = b.BatchSeq
	}
}

func TestIntegrationTelemetryTinyBufferEmitsTheGapRange(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Limits.InitialCredits = 0 // nothing leaves until ResumeTelemetry
	gw := startGateway(t, sc)
	startTelemetryAgent(t, gw, "", both, 400) // one ~230-byte batch fits, two do not
	time.Sleep(1500 * time.Millisecond)
	gw.ResumeTelemetry(1)
	if !gw.WaitFor("TelemetryBatch", 1, 10*time.Second) {
		t.Fatal("no telemetry batch after resume")
	}
	b := batches(gw)[0]
	want := []msg.GapMarker{{FromSeq: 1, ToSeq: b.BatchSeq - 1, Reason: "DROPPED_OLDEST"}}
	if b.BatchSeq < 3 || !reflect.DeepEqual(b.GapMarkers, want) {
		t.Fatalf("first batch seq %d gaps %+v, want %+v", b.BatchSeq, b.GapMarkers, want)
	}
}

func TestIntegrationTelemetryAgentRestartSendsUnacked(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Limits.InitialCredits = 0
	gw := startGateway(t, sc)
	h, _ := startTelemetryAgent(t, gw, "", both, 1<<20)
	time.Sleep(800 * time.Millisecond) // several batches buffered, none sent
	h.stop()
	startTelemetryAgent(t, gw, h.dir, both, 1<<20)
	deadline := time.Now().Add(10 * time.Second)
	for !gw.WaitFor("TelemetryBatch", 1, 200*time.Millisecond) && time.Now().Before(deadline) {
		gw.ResumeTelemetry(16) // repeated until the new session's stream is open
	}
	if !gw.WaitFor("TelemetryBatch", 4, 10*time.Second) {
		t.Fatalf("only %d batches", len(batches(gw)))
	}
	if b := batches(gw)[0]; b.BatchSeq != 1 {
		t.Fatalf("the buffer did not survive the restart: first seq %d", b.BatchSeq)
	}
}

func TestIntegrationTelemetryDisconnectResumesFromTheWatermark(t *testing.T) {
	gw := startGateway(t, nil)
	out := &syncBuffer{}
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 3, 10*time.Second) {
		t.Fatal("no telemetry")
	}
	gw.Console(strings.NewReader("disconnect\n"), out)
	waitOutput(t, out, "session closed", 5*time.Second)
	before := len(batches(gw))
	if !gw.WaitFor("TelemetryBatch", before+3, 30*time.Second) {
		t.Fatal("telemetry did not resume after reconnect")
	}
	seen := map[uint64]int{}
	for _, b := range batches(gw) {
		seen[b.BatchSeq]++
	}
	resent := 0
	for _, n := range seen {
		if n > 1 {
			resent++
		}
	}
	if resent > 1 { // at most the one batch in flight at the disconnect
		t.Fatalf("%d batches resent after reconnect: %v", resent, seen)
	}
}

func TestIntegrationTelemetryOnlyRoleRefusesCommands(t *testing.T) {
	gw := startGateway(t, nil)
	h, _ := startTelemetryAgent(t, gw, "", Roles{Telemetry: true}, 1<<20)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	open := gw.Received("SessionOpen")[0].(msg.SessionOpen)
	if !reflect.DeepEqual(open.CapabilitiesSupported, []msg.Capability{msg.CapReportOnly}) {
		t.Fatalf("advertised %v", open.CapabilitiesSupported)
	}
	out := &syncBuffer{}
	go gw.Console(strings.NewReader("restart\n"), out)
	waitOutput(t, out, "restart-1 gen=1 -> REFUSED (CAPABILITY_MISSING)", 10*time.Second)
	if !gw.WaitFor("TelemetryBatch", 1, 10*time.Second) {
		t.Fatal("a telemetry-only agent must still send telemetry")
	}
}

func TestIntegrationTelemetryRejectDropsTheBatchAsAGap(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Policy.Telemetry.RejectNext = "UNKNOWN_REPLICA"
	gw := startGateway(t, sc)
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 2, 10*time.Second) {
		t.Fatal("too few batches")
	}
	b := batches(gw)[1]
	if b.BatchSeq != 2 || !reflect.DeepEqual(b.GapMarkers, []msg.GapMarker{{FromSeq: 1, ToSeq: 1, Reason: "UNKNOWN_REPLICA"}}) {
		t.Fatalf("second batch %+v", b)
	}
}

```

- [ ] **Step 2: Run them.**

Run: `cd go-agent && go test -race -tags integration ./internal/agent/ -run IntegrationTelemetry -v 2>&1 | grep -E '^(---|ok|FAIL)'`
Expected: all seven PASS. They exercise only code from Tasks 1–13. If one fails, the failure is in that code: debug it with superpowers:systematic-debugging; do not loosen the test.

`TinyBufferEmitsTheGapRange` depends on one batch being about 230 bytes. If its `BatchSeq < 3` check fails, print `buf.Stats().Bytes` for one batch in a scratch run, and set the cap to 1.5× that size.

- [ ] **Step 3: Prove a test bites.** In `runner.go`'s `message`, add a fourth label `"extra": "x"` and rerun `-run CarryMintedIDs` — it must FAIL. Remove it.

- [ ] **Step 4: Run the full integration suite.**

Run: `cd go-agent && go test -tags integration ./internal/agent/`
Expected: `ok`. The one pre-existing skip (`AG-5`) remains.

- [ ] **Step 5: Commit.**

```bash
cd go-agent && git add internal/agent/telemetry_integration_test.go
git commit -m "go-agent: end-to-end telemetry scenarios against fake-gateway

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Docker E2E for `ScrapeAddr`, a manual run, and the runbook

**Files:**
- Create: `go-agent/internal/runtime/docker/telemetry_e2e_test.go`
- Modify: `go-agent/RUNBOOK.md` (add a telemetry section)
- Create: `go-agent/examples/telemetry.yaml` (the runbook's declarations file)

**Interfaces:**
- Consumes: `docker.NewFromEnv`, `(*Adapter).Resolve`, `(*Adapter).ScrapeAddr`, the built `sm-agent` and `fake-gateway`.

- [ ] **Step 1: Write the Docker test.**

```go
//go:build docker

package docker

import (
	"context"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// TestE2EScrapeAddrReachesAPublishedPort runs busybox httpd with a published
// port and the binding label, then fetches through ScrapeAddr. The config dir
// is under $HOME because Docker Desktop shares only that (CLAUDE.md).
func TestE2EScrapeAddrReachesAPublishedPort(t *testing.T) {
	conf, err := os.MkdirTemp(os.Getenv("HOME"), "sm-agent-e2e-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(conf) })
	name := "sm-agent-telemetry-e2e"
	_ = exec.Command("docker", "rm", "-f", name).Run()
	cmd := exec.Command("docker", "run", "-d", "--name", name,
		"--label", BindingLabel+"=e2e-t1", "-e", "SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/",
		"-v", conf+":/etc/app", "-p", "127.0.0.1::8080", "busybox:1.36",
		"sh", "-c", `mkdir -p /www && echo '{"hitRate":0.5}' > /www/stats.json && httpd -f -p 8080 -h /www`)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("docker unavailable: %v: %s", err, out)
	}
	t.Cleanup(func() { _ = exec.Command("docker", "rm", "-f", name).Run() })

	a, closeFn, err := NewFromEnv()
	if err != nil {
		t.Skipf("docker client: %v", err)
	}
	defer closeFn()
	ctx := context.Background()
	p, err := a.Resolve(ctx, "e2e-t1")
	if err != nil {
		t.Fatal(err)
	}
	addr, err := a.ScrapeAddr(ctx, p, 8080)
	if err != nil || !strings.HasPrefix(addr, "127.0.0.1:") {
		t.Fatalf("addr %q %v", addr, err)
	}
	get := func() string {
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			if resp, err := http.Get("http://" + addr + "/stats.json"); err == nil {
				b, _ := io.ReadAll(resp.Body)
				resp.Body.Close()
				if resp.StatusCode == 200 {
					return string(b)
				}
			}
			time.Sleep(100 * time.Millisecond)
		}
		return ""
	}
	if body := get(); !strings.Contains(body, `"hitRate":0.5`) {
		t.Fatalf("body %q", body)
	}
	if out, err := exec.Command("docker", "exec", name, "sh", "-c", `echo '{"hitRate":0.75}' > /www/stats.json`).CombinedOutput(); err != nil {
		t.Fatalf("%v: %s", err, out)
	}
	if body := get(); !strings.Contains(body, `"hitRate":0.75`) {
		t.Fatalf("edited value not served: %q", body)
	}
}
```

- [ ] **Step 2: Run it.**

Run: `cd go-agent && export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock && go test -tags docker ./internal/runtime/docker/ -run ScrapeAddrReaches -v`
Expected: PASS. `SKIP` is acceptable only if Docker is down; then start Docker Desktop and rerun.

- [ ] **Step 3: Write the runbook's declarations file.** Create `go-agent/examples/telemetry.yaml`:

```yaml
# Declarations for RUNBOOK.md's telemetry section (AG-D22, interim). Each
# metrics[] entry is the Solution Package schema's metric object.
schema_version: 1
bindings:
  binding-1:
    interval: 5s
    metrics:
      - name: cache.hit_rate
        metricType: gauge
        unit: ratio
        source: {kind: http_json, port: 8080, path: /stats.json, pointer: /hitRate, onUnavailable: degrade}
      - name: cache.miss_count
        metricType: counter
        unit: count
        source: {kind: http_json, port: 8080, path: /stats.json, pointer: /missCount, onUnavailable: degrade}
```

- [ ] **Step 4: Run it by hand, end to end, against a stand-in.**

Use your own throwaway container and binding, not the user's `java1`: use the label `binding-1` only if no other container carries it (`docker ps -a --filter label=com.solace.sm.binding-id=binding-1`). Otherwise give the throwaway container a binding of its own, and use that binding in a scratch copy of `telemetry.yaml` and a one-line `--scenario` with `lease: {binding_ids: [<it>]}`.

```bash
cd go-agent && make build && mkdir -p ~/sm-telemetry-demo
docker run -d --name sm-telemetry-demo --label com.solace.sm.binding-id=binding-1 \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/ -v ~/sm-telemetry-demo:/etc/app \
  -p 127.0.0.1:18080:8080 busybox:1.36 \
  sh -c 'mkdir -p /www && echo "{\"hitRate\":0.93,\"missCount\":12}" > /www/stats.json && httpd -f -p 8080 -h /www'
# terminal 1
./bin/fake-gateway --interactive --listen 127.0.0.1:8443 --enroll-token demo-token --ca-out bin/ca.pem
# terminal 2
export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock
rm -rf bin/agent-data && ./bin/sm-agent --data-dir bin/agent-data --connect-endpoint 127.0.0.1:8443 \
  --enrollment-token demo-token --trust-bundle bin/ca.pem --roles control,telemetry --telemetry-config examples/telemetry.yaml
```

Record exact output for each of:
1. terminal 1's `telemetry binding-1 seq=… cache.hit_rate=0.93 cache.miss_count=12 -> acked`, every 5 s;
2. after `docker exec sm-telemetry-demo sh -c 'echo "{\"hitRate\":0.5,\"missCount\":13}" > /www/stats.json'`, the next line's new values;
3. `pause` → the lines stop; `curl -s localhost:9464/metrics | grep sm_agent_telemetry_buffer_batches` rises; `resume` → the buffered batches drain in order;
4. `disconnect` → `session closed…`, the agent reconnects, and lines resume with no `duplicate` beyond at most one.

Then stop both processes, `docker rm -f sm-telemetry-demo` and `rm -rf ~/sm-telemetry-demo`.

- [ ] **Step 5: Write the runbook section.** Append to `go-agent/RUNBOOK.md` a `## Telemetry` section with:
  - the stand-in container command;
  - `examples/telemetry.yaml`;
  - `--roles control,telemetry --telemetry-config examples/telemetry.yaml` added to step 2's agent command;
  - a table of what terminal 1 prints, using the exact lines recorded in Step 4;
  - the `pause` / `resume` / `disconnect` demonstrations, with what to expect and the `/metrics` gauges to watch (`sm_agent_telemetry_buffer_bytes`, `…_batches`, `…_credits`, `…_dropped_batches_total{reason}`);
  - a status line saying it was executed on the date of Step 4, against the stand-in;
  - troubleshooting rows: `port … is neither published nor reachable` (publish the port), no lines at all (check `scrape_errors_total{reason}`), and `declared binding is not in the lease`.

  Then:
  - add `pause | resume | disconnect` to the existing console section's verb list;
  - extend `If it goes wrong` with `no ack within 5m0s`, plus the telemetry rows if they are not already there.

- [ ] **Step 6: Commit.**

```bash
cd go-agent && git add internal/runtime/docker/telemetry_e2e_test.go RUNBOOK.md examples/telemetry.yaml
git commit -m "go-agent: Docker E2E for ScrapeAddr; runbook telemetry section, executed

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Decisions, open items and status docs

**Files:**
- Modify: `docs/design/go-agent/decisions.md`
  - summary table rows `AG-D21`–`AG-D25`;
  - a body section for each;
  - a changelog row.
- Modify: `docs/design/go-agent/design.md`
  - §13.2 rows `AG-14`–`AG-20`;
  - a sentence on `AG-12`'s row: telemetry stays off the reactor (`AG-D21`), so it does not raise `AG-12`'s urgency.
- Modify: `docs/superpowers/specs/2026-10-01-go-agent-telemetry-slice-design.md`
  - §2.3: the gate is extended, not existing — deviation 1;
  - §4.2 and §11 `AG-16`: add `UNREGISTERED` and `CORRUPT`;
  - §5.1: `Current()`/`Changed()` with methods on `Snapshot` — deviation 3;
  - §5.2: credit restoration on reject and on a stream break — deviations 5 and 6;
  - §5.2 oversize: the stored JSON size — deviation 7.
- Modify: `go-agent/CLAUDE.md`
  - Status: slice 2 telemetry built;
  - the package tree gains `internal/telemetry/*`;
  - Commands: `--roles`.
- Modify: `go-agent/README.md`: one paragraph and the configuration table rows for `--roles`, `--telemetry-config`, `--telemetry-buffer-bytes`, `--telemetry-buffer-age`.

**Interfaces:** none — documentation.

- [ ] **Step 1: Write the decisions.** In `docs/design/go-agent/decisions.md`, add a summary-table row and a body for each, in the existing `## \`AG-Dnn\` — Title` style. Each body states the decision, its rationale and what it rejects, taken from spec §2.1, §3.1, §4, §5.2 and §10 and from this plan's deviations:

  | ID | Title | Status |
  |---|---|---|
  | `AG-D21` | The telemetry role is a separate pipeline with its own loop; the reactor publishes a read-only view | PROPOSED |
  | `AG-D22` | Interim local declarations file, schema-shaped, until the plane delivers declarations | PROPOSED |
  | `AG-D23` | One logical stream per binding; one batch in flight per stream | PROPOSED |
  | `AG-D24` | Buffer: one file per batch; global byte and age caps; drop-oldest; in-flight spared; gap reasons `DROPPED_OLDEST`, `EXPIRED`, `OVERSIZE`, `UNREGISTERED`, `CORRUPT`, or the reject code | PROPOSED |
  | `AG-D25` | A telemetry write failure is counted, not fatal — unlike control-path `Persist` | PROPOSED |

  Add a changelog row dated with the commit date, naming the plan.

- [ ] **Step 2: Write the open items.** In `docs/design/go-agent/design.md` §13.2, add rows `AG-14`–`AG-20` with the wording of spec §11. Extend `AG-16` with `UNREGISTERED`, `CORRUPT`, and extend `AG-17` with deviation 5's assumption.

- [ ] **Step 3: Correct the spec in place.** Make the five edits listed under **Files**. Each edit adds a sentence of the form "Corrected while planning (plan 006, deviation N): …", so the change is visible rather than silent.

- [ ] **Step 4: Status docs.** Update `go-agent/CLAUDE.md` and `go-agent/README.md` as listed. The package tree lines:

```
internal/telemetry/    pipeline wiring (AG-D21); view/ declare/ jsonptr/ collect/ buffer/ send/ tmetrics/
```

- [ ] **Step 5: Verify the docs.** From the repo root:

```bash
for f in docs/design/go-agent/decisions.md docs/design/go-agent/design.md go-agent/CLAUDE.md go-agent/README.md go-agent/RUNBOOK.md docs/superpowers/specs/2026-10-01-go-agent-telemetry-slice-design.md; do
  d=$(dirname "$f"); grep -oE '\]\([^)#]+' "$f" | sed 's/](//' | grep -v '^http' | while read -r l; do [ -e "$d/$l" ] || echo "MISS $f -> $l"; done
done
grep -n "AG-D2[1-5]" docs/design/go-agent/decisions.md | head
```

Expected: no `MISS` lines; each of `AG-D21`–`AG-D25` appears in both the table and a heading.

- [ ] **Step 6: Commit.**

```bash
git add docs go-agent/CLAUDE.md go-agent/README.md
git commit -m "docs: record the telemetry slice's decisions, open items and spec corrections

AG-D21–AG-D25 (PROPOSED), AG-14–AG-20 routed to the AG-D*/AGW owners, and
the spec corrected where planning found it wrong.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Results

*(Filled in at the end of execution: what was built, the test counts, the manual run's date and outcome, and any deviation beyond the eight above.)*
