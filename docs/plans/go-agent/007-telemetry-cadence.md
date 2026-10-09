# go-agent DP-Set Telemetry Intervals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Data Plane set, per agent, how often the telemetry role pulls every binding and how often it pushes the batches it has stored, carried as two DRAFT fields of `SessionAccept.limits`. Demonstrable against `fake-gateway`.

**Architecture:** One pure function, `cadence.Resolve(limits, bufferAge)`, turns the two DP values into the settings in use. The sender's pure reducer resolves the **push** interval inside `SessionUp` and holds every batch produced after its `DueBy` cutoff until a push tick moves the cutoff. A pipeline goroutine resolves the **pull** interval on every new session and stores it in a `cadence.Holder`; the collectors watch the holder and reset their tickers. Storage, deletion on ack and the buffer caps are slice 2's, unchanged.

**Tech Stack:** Go 1.26 (`go.mod`), `google.golang.org/protobuf` + buf codegen, `github.com/prometheus/client_golang`, stdlib `time`, `net/http/httptest`.

**Spec:** `docs/superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md` (approved 2026-10-09). Read it first; this plan argues from it. Above the spec: `docs/design/backend/agent-gateway/design.md` §4.3 and §5.8, which overrule anything below them. Slice 2's spec, `docs/superpowers/specs/2026-10-01-go-agent-telemetry-slice-design.md`, describes the code this changes.

## Global Constraints

- Module `github.com/solace/solution-manager/go-agent`, `go 1.26.4`. Never lower the floor.
- **`internal/wire` and `internal/wire/codec` are the only packages that import `internal/wire/pb`.** The telemetry packages speak `internal/msg`.
- **`internal/telemetry/...` never imports `internal/agent`.** `internal/telemetry/cadence` imports only `internal/msg` and the standard library.
- **The two new `Limits` fields are a DRAFT, agent-side ask (`AG-23`).** They go in `proto/agentgateway/v1draft/` only, never under `contracts/`.
- **`0` means the local default.** With both fields `0`, the agent must behave exactly as slice 2 does: **no existing test's expectation changes.**
- Reducers are pure: no clock reads, I/O, goroutines or randomness. Impurities arrive through `Env`.
- **`time.Ticker.Reset` panics on a non-positive duration** (verified: `go doc time.Ticker.Reset`, Go 1.26.4). Never pass it `0`; stop the ticker instead.
- **`I6`:** a refused DP value is logged as durations only. No payload, sample value or response body enters a log.
- Codegen tools live in `~/go/bin`: run `PATH="$PATH:$HOME/go/bin" make proto` from `go-agent/`.
- Before every commit, run `go vet ./... && go test ./...` from `go-agent/`. Run `go test -race ./internal/telemetry/... ./internal/fakegw/` whenever those change. Integration tests are `-tags integration`.
- **Branch:** `feat/go-agent-telemetry`. Slice 2 is unmerged there and the spec is already committed there (`1899cff`). Do not commit to `main`.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- All commands below run from `go-agent/` unless a path says otherwise.

## Decided while planning — deviations from the spec, each with its reason

1. **Switching from immediate push to a push interval moves `DueBy` to now.** Spec §3.4 says overdue batches go at once after a reconnect, but while push is `0` nothing advances `DueBy`. Without this, batches stored under immediate push would wait up to one interval when a new session turns a push interval on. `SessionUp` therefore sets `DueBy = env.Now` when the previous push interval was `0` (Task 3, `TestSwitchingFromImmediateKeepsWhatIsStoredDue`).
2. **`cadence.Resolve` with `bufferAge == 0` puts no upper bound on push.** `buffer.Options.MaxAge` `0` means no age cap. Production refuses a non-positive `--telemetry-buffer-age` at start (`cmd/sm-agent/main.go`), so only tests reach this case.
3. **`Holder.Set` is a no-op on equal values and reports whether anything changed.** Spec §4.1 has the watcher compare before `Set`; folding the comparison into `Set` keeps it in one place, and it is what stops a flapping session resetting tickers (Review Focus 1).
4. **The console prints Go's duration format**, `push=1m0s`, not the spec demo's `push=1m`.
5. **`ArmPush` follows `OpenStream`** in `SessionUp`'s effects. The spec does not order them.

## Review Focus

The input classes the spec implies but its own test list does not name. Each has a test in the task named.

1. **A session that flaps and reconnects with the same values.** Expected: neither the push ticker nor any pull ticker is reset. Otherwise a session reconnecting more often than the push interval would postpone every push (Task 2, `TestSettingTheSameValuesDoesNotSignalAChange`; Task 3, `TestSessionUpArmsThePushTickerOnlyWhenTheIntervalChanges`).
2. **The DP turns the push interval off while batches are stored.** Expected: everything stored goes as soon as the stream opens (Task 3, `TestTurningThePushIntervalOffReleasesEverythingStored`).
3. **The wall clock steps backwards past a stored batch's `produced_at`.** Expected: the batch waits, and goes at the first push tick after the clock passes it; it is never stuck (Task 3, `TestABatchFromTheFutureIsSentOnceTheClockPassesIt`).
4. **Values on the boundaries.** Expected: pull of exactly 1 s is accepted, push equal to the age cap is refused, and a 1 ns push is accepted (Task 2, `TestResolve` rows).
5. **A malformed `set` line at the gateway console.** Expected: a usage or parse message, and the stored values do not change (Task 7, `TestConsoleSetStoresIntervalsForTheNextSession`).

---

## File Structure

| Path | Responsibility |
|---|---|
| `proto/agentgateway/v1draft/agent_gateway.proto` | `Limits` gains fields 8 and 9; the header names them as an ask |
| `internal/wire/pb/agent_gateway.pb.go` | regenerated, committed |
| `internal/msg/msg.go` | `Limits` gains `TelemetryPullInterval`, `TelemetryPushInterval` |
| `internal/wire/codec/codec.go` | maps both fields in both directions |
| `internal/telemetry/cadence/cadence.go` | **new:** `Settings`, `Ignored`, `Resolve`, `Holder` |
| `internal/telemetry/send/reducer.go` | `PushInterval`, `DueBy`, `BufferAge`; `PushDue`; `ArmPush`; the due check |
| `internal/telemetry/send/runner.go` | `BufferAge` field; the push ticker; `DueBy` at start |
| `internal/telemetry/collect/collect.go` | `Cadence` field; the ticker follows it; the timeout clamp |
| `internal/telemetry/tmetrics/tmetrics.go` | the two interval gauges and the refusal counter |
| `internal/telemetry/pipeline.go` | the holder, `watchCadence`, wiring to collectors and runner |
| `internal/fakegw/{scenario,session,telemetry,console}.go` | scenario fields, `SetTelemetryInterval`, the `set` verb, time-stamped lines |
| `internal/agent/telemetry_integration_test.go` | three scenarios |
| `docs/design/go-agent/{decisions,design}.md`, `go-agent/{README,RUNBOOK,CLAUDE}.md`, `docs/requirements/{REQ-013-…,traceability}.md` | `AG-D26`, `AG-23`, the runbook walk-through, status |

---

### Task 1: Wire — two DRAFT `Limits` fields

**Files:**
- Modify: `proto/agentgateway/v1draft/agent_gateway.proto` (header comment; `message Limits`)
- Regenerate: `internal/wire/pb/agent_gateway.pb.go`
- Modify: `internal/msg/msg.go` (`type Limits`, line ~215)
- Modify: `internal/wire/codec/codec.go` (`GatewayToPB` and `GatewayFromPB`, the `SessionAccept` cases)
- Test: `internal/wire/codec/codec_test.go`

**Interfaces:**
- Produces: `msg.Limits.TelemetryPullInterval time.Duration` and `msg.Limits.TelemetryPushInterval time.Duration`. Absent on the wire decodes to `0`.

- [ ] **Step 1: Write the failing tests**

In `internal/wire/codec/codec_test.go`, `TestGatewayMessagesRoundTrip`, extend the `SessionAccept` case's `Limits` literal with the two fields:

```go
			Limits: msg.Limits{MaxMessageBytes: 1, MaxBatchBytes: 2, InitialCredits: 3, HeartbeatInterval: time.Second, KeepaliveInterval: 2 * time.Second, SkewTolerance: 3 * time.Second, ReportInterval: 4 * time.Second,
				TelemetryPullInterval: 5 * time.Second, TelemetryPushInterval: time.Minute}, ServerClock: now},
```

Add this test after `TestGatewayMessagesRoundTrip`:

```go
// A gateway that predates AG-D26 sends neither field; the agent must read 0,
// its local default.
func TestAbsentTelemetryIntervalsDecodeToZero(t *testing.T) {
	out, err := GatewayFromPB(&pb.GatewayToAgent{Msg: &pb.GatewayToAgent_SessionAccept{SessionAccept: &pb.SessionAccept{
		Limits: &pb.Limits{ReportInterval: dur(time.Minute)}}}})
	if err != nil {
		t.Fatal(err)
	}
	l := out.(msg.SessionAccept).Limits
	if l.TelemetryPullInterval != 0 || l.TelemetryPushInterval != 0 || l.ReportInterval != time.Minute {
		t.Fatalf("limits %+v", l)
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `go test ./internal/wire/codec/`
Expected: FAIL to compile with `unknown field TelemetryPullInterval in struct literal of type msg.Limits`.

- [ ] **Step 3: Add the fields to the DRAFT proto and regenerate**

In `proto/agentgateway/v1draft/agent_gateway.proto`, replace `message Limits` with:

```proto
message Limits {
  uint64 max_message_bytes = 1;
  uint64 max_batch_bytes = 2;
  uint32 initial_credits = 3;
  google.protobuf.Duration heartbeat_interval = 4;
  google.protobuf.Duration keepalive_interval = 5;
  google.protobuf.Duration skew_tolerance = 6;
  google.protobuf.Duration report_interval = 7;
  // NOT transcribed from §4.3: an agent-side ask (AG-D26, open item AG-23).
  // Absent or zero means the agent's local default.
  google.protobuf.Duration telemetry_pull_interval = 8;
  google.protobuf.Duration telemetry_push_interval = 9;
}
```

In the same file's header, insert this paragraph immediately before `syntax = "proto3";`, after the two transcription notes:

```proto
// One deliberate departure from 1:1 transcription: Limits fields 8 and 9,
// telemetry_pull_interval and telemetry_push_interval, are NOT in §4.3. They
// are an agent-side ask (AG-D26), routed to the AG-D* and AGW owners as open
// item AG-23. A gateway that sends neither leaves the agent's defaults.
```

Run: `PATH="$PATH:$HOME/go/bin" make proto`
Expected: exit 0; `git diff --stat internal/wire/pb/` shows `agent_gateway.pb.go` changed.

- [ ] **Step 4: Add the fields to `msg.Limits`**

In `internal/msg/msg.go`, replace `type Limits struct { … }` with:

```go
type Limits struct {
	MaxMessageBytes   uint64
	MaxBatchBytes     uint64
	InitialCredits    uint32
	HeartbeatInterval time.Duration
	KeepaliveInterval time.Duration
	SkewTolerance     time.Duration
	ReportInterval    time.Duration
	// AG-D26, a DRAFT ask not in gateway §4.3 (AG-23). 0 = the local default.
	TelemetryPullInterval time.Duration
	TelemetryPushInterval time.Duration
}
```

- [ ] **Step 5: Map them in the codec**

In `internal/wire/codec/codec.go`, `GatewayToPB`, `case msg.SessionAccept:`, replace the `Limits: &pb.Limits{…}` expression with:

```go
			Limits: &pb.Limits{MaxMessageBytes: v.Limits.MaxMessageBytes, MaxBatchBytes: v.Limits.MaxBatchBytes, InitialCredits: v.Limits.InitialCredits,
				HeartbeatInterval: dur(v.Limits.HeartbeatInterval), KeepaliveInterval: dur(v.Limits.KeepaliveInterval), SkewTolerance: dur(v.Limits.SkewTolerance), ReportInterval: dur(v.Limits.ReportInterval),
				TelemetryPullInterval: dur(v.Limits.TelemetryPullInterval), TelemetryPushInterval: dur(v.Limits.TelemetryPushInterval)},
```

In `GatewayFromPB`, `case *pb.GatewayToAgent_SessionAccept:`, replace the `Limits: msg.Limits{…}` expression with:

```go
			Limits: msg.Limits{MaxMessageBytes: l.GetMaxMessageBytes(), MaxBatchBytes: l.GetMaxBatchBytes(), InitialCredits: l.GetInitialCredits(),
				HeartbeatInterval: durFrom(l.GetHeartbeatInterval()), KeepaliveInterval: durFrom(l.GetKeepaliveInterval()), SkewTolerance: durFrom(l.GetSkewTolerance()), ReportInterval: durFrom(l.GetReportInterval()),
				TelemetryPullInterval: durFrom(l.GetTelemetryPullInterval()), TelemetryPushInterval: durFrom(l.GetTelemetryPushInterval())},
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `go test ./internal/wire/codec/ -run 'RoundTrip|AbsentTelemetry' -v`
Expected: PASS for `TestAgentMessagesRoundTrip`, `TestGatewayMessagesRoundTrip`, `TestAbsentTelemetryIntervalsDecodeToZero`.

- [ ] **Step 7: Commit**

```bash
go vet ./... && go test ./...
git add proto/agentgateway/v1draft/agent_gateway.proto internal/wire/pb/ internal/msg/msg.go internal/wire/codec/
git commit -m "go-agent: DRAFT SessionAccept.limits telemetry pull and push intervals (AG-D26)

Two Limits fields that gateway §4.3 does not define, carried as an
agent-side ask (AG-23). Absent decodes to 0, the agent's local default.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `cadence` — resolve the DP's values, hold the ones in use

**Files:**
- Create: `internal/telemetry/cadence/cadence.go`
- Test: `internal/telemetry/cadence/cadence_test.go`

**Interfaces:**
- Consumes: `msg.Limits.TelemetryPullInterval`, `msg.Limits.TelemetryPushInterval` (Task 1).
- Produces:
  - `type Settings struct { Pull, Push time.Duration }` (zero = local default; comparable with `==`)
  - `type Ignored struct { Limit string; Value time.Duration; Reason string }`
  - `const MinPull = time.Second`
  - `func Resolve(l msg.Limits, bufferAge time.Duration) (Settings, []Ignored)`
  - `func NewHolder() *Holder`, `(*Holder) Set(Settings) bool`, `(*Holder) Current() Settings`, `(*Holder) Changed() <-chan struct{}`

- [ ] **Step 1: Write the failing tests**

Create `internal/telemetry/cadence/cadence_test.go`:

```go
package cadence

import (
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestResolve(t *testing.T) {
	const age = time.Hour
	for _, tc := range []struct {
		name       string
		pull, push time.Duration
		want       Settings
		ignored    []Ignored
	}{
		{"absent", 0, 0, Settings{}, nil},
		{"valid", 5 * time.Second, time.Minute, Settings{Pull: 5 * time.Second, Push: time.Minute}, nil},
		// Review Focus 4: the boundaries
		{"pull at the floor", time.Second, 0, Settings{Pull: time.Second}, nil},
		{"pull below the floor", 999 * time.Millisecond, 0, Settings{}, []Ignored{{Limit: "pull", Value: 999 * time.Millisecond, Reason: "below 1s"}}},
		{"negative pull", -time.Second, 0, Settings{}, []Ignored{{Limit: "pull", Value: -time.Second, Reason: "below 1s"}}},
		{"smallest push", 0, time.Nanosecond, Settings{Push: time.Nanosecond}, nil},
		{"push just below the age cap", 0, age - time.Nanosecond, Settings{Push: age - time.Nanosecond}, nil},
		{"push equal to the age cap", 0, age, Settings{}, []Ignored{{Limit: "push", Value: age, Reason: "not below the buffer age cap"}}},
		{"push beyond the age cap", 0, 2 * age, Settings{}, []Ignored{{Limit: "push", Value: 2 * age, Reason: "not below the buffer age cap"}}},
		{"negative push", 0, -time.Second, Settings{}, []Ignored{{Limit: "push", Value: -time.Second, Reason: "negative"}}},
		{"both refused", 10 * time.Millisecond, age, Settings{}, []Ignored{
			{Limit: "pull", Value: 10 * time.Millisecond, Reason: "below 1s"},
			{Limit: "push", Value: age, Reason: "not below the buffer age cap"}}},
	} {
		got, ig := Resolve(msg.Limits{TelemetryPullInterval: tc.pull, TelemetryPushInterval: tc.push}, age)
		if got != tc.want || !reflect.DeepEqual(ig, tc.ignored) {
			t.Errorf("%s: got %+v %+v, want %+v %+v", tc.name, got, ig, tc.want, tc.ignored)
		}
	}
}

// Deviation 2: no age cap, so no upper bound on push.
func TestWithNoAgeCapAnyPositivePushIsValid(t *testing.T) {
	if got, ig := Resolve(msg.Limits{TelemetryPushInterval: 48 * time.Hour}, 0); got.Push != 48*time.Hour || ig != nil {
		t.Fatalf("got %+v %+v", got, ig)
	}
}

func TestHolderSignalsAChange(t *testing.T) {
	h := NewHolder()
	ch := h.Changed()
	if !h.Set(Settings{Push: time.Minute}) {
		t.Fatal("Set reported no change")
	}
	select {
	case <-ch:
	default:
		t.Fatal("Changed was not closed by Set")
	}
	if h.Current() != (Settings{Push: time.Minute}) {
		t.Fatalf("current %+v", h.Current())
	}
}

// Review Focus 1: a reconnect that repeats the values must signal nothing, or
// every ticker resets and a session flapping faster than the push interval
// postpones every push.
func TestSettingTheSameValuesDoesNotSignalAChange(t *testing.T) {
	h := NewHolder()
	h.Set(Settings{Pull: 5 * time.Second})
	ch := h.Changed()
	if h.Set(Settings{Pull: 5 * time.Second}) {
		t.Fatal("Set reported a change for equal values")
	}
	select {
	case <-ch:
		t.Fatal("Changed was closed for equal values")
	default:
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `go test ./internal/telemetry/cadence/`
Expected: FAIL to compile with `undefined: Settings` (and `Resolve`, `NewHolder`).

- [ ] **Step 3: Write the implementation**

Create `internal/telemetry/cadence/cadence.go`:

```go
// Package cadence resolves the telemetry pull and push intervals the Data
// Plane sets on SessionAccept.limits (AG-D26), and holds the values in use
// for the collectors and the pipeline's gauges. The sender resolves the push
// interval itself, with the same Resolve, inside its SessionUp.
package cadence

import (
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// Settings are the intervals in use. Zero means the local default: each
// binding's declared interval, and sending each batch as soon as it is written.
type Settings struct {
	Pull time.Duration
	Push time.Duration
}

// Ignored names a DP value the agent refused; the local default applies.
type Ignored struct {
	Limit  string // "pull" | "push"
	Value  time.Duration
	Reason string
}

// MinPull is the declarations file's own floor for interval (AG-D22).
const MinPull = time.Second

// Resolve applies spec §2.2. bufferAge is --telemetry-buffer-age; 0 means no
// age cap, as in buffer.Options, so push then has no upper bound.
func Resolve(l msg.Limits, bufferAge time.Duration) (Settings, []Ignored) {
	var s Settings
	var ig []Ignored
	switch p := l.TelemetryPullInterval; {
	case p == 0:
	case p < MinPull:
		ig = append(ig, Ignored{Limit: "pull", Value: p, Reason: "below 1s"})
	default:
		s.Pull = p
	}
	switch p := l.TelemetryPushInterval; {
	case p == 0:
	case p < 0:
		ig = append(ig, Ignored{Limit: "push", Value: p, Reason: "negative"})
	case bufferAge > 0 && p >= bufferAge:
		// every batch would expire before it was due
		ig = append(ig, Ignored{Limit: "push", Value: p, Reason: "not below the buffer age cap"})
	default:
		s.Push = p
	}
	return s, ig
}

// Holder is the values in use, in view.Hub's idiom: take Changed before
// Current, so a Set between the two is never missed.
type Holder struct {
	mu      sync.Mutex
	cur     Settings
	changed chan struct{}
}

func NewHolder() *Holder { return &Holder{changed: make(chan struct{})} }

// Set stores s and reports whether it differed. Equal values signal nothing,
// so a reconnect that repeats them resets no ticker.
func (h *Holder) Set(s Settings) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if s == h.cur {
		return false
	}
	h.cur = s
	close(h.changed)
	h.changed = make(chan struct{})
	return true
}

func (h *Holder) Current() Settings {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.cur
}

// Changed is closed by the next Set that changes the values.
func (h *Holder) Changed() <-chan struct{} {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.changed
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `go test -race ./internal/telemetry/cadence/ -v`
Expected: PASS, four tests.

- [ ] **Step 5: Commit**

```bash
go vet ./... && go test ./...
git add internal/telemetry/cadence/
git commit -m "go-agent: cadence — resolve and hold the DP-set telemetry intervals (AG-D26)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Sender reducer — the push schedule

**Files:**
- Modify: `internal/telemetry/send/reducer.go`
- Test: `internal/telemetry/send/reducer_test.go`

**Interfaces:**
- Consumes: `cadence.Resolve` (Task 2).
- Produces:
  - `State.PushInterval time.Duration`, `State.DueBy time.Time`, `State.BufferAge time.Duration`
  - event `PushDue struct{}`
  - effect `ArmPush struct{ Every time.Duration }` (`Every == 0` means stop the ticker)

- [ ] **Step 1: Write the failing tests**

Append to `internal/telemetry/send/reducer_test.go`:

```go
func withPush(push time.Duration) msg.Limits {
	l := limits
	l.TelemetryPushInterval = push
	return l
}

// upWithPush is up() with a push interval from the session's limits, a one-hour
// age cap and the cutoff at t0, as the runner starts it.
func upWithPush(credits uint32, push time.Duration) State {
	s := NewState()
	s.BufferAge, s.DueBy = time.Hour, t0
	s, _ = Reduce(s, SessionUp{ID: "s1", Limits: withPush(push)}, env(t0))
	s, _ = Reduce(s, StreamOpened{}, env(t0))
	s.Credits = credits
	return s
}

func TestANotYetDueHeadWaitsForThePushTick(t *testing.T) {
	s := upWithPush(4, time.Minute)
	s, effs := Reduce(s, Wake{}, env(t0.Add(10*time.Second), head("a", 1, 5)))
	if len(effs) != 0 {
		t.Fatalf("sent before the push tick: %+v", effs)
	}
	_, effs = Reduce(s, PushDue{}, env(t0.Add(60*time.Second), head("a", 1, 5)))
	if !reflect.DeepEqual(effs, []Effect{Send{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestAPushTickReleasesEveryHeadProducedAtOrBeforeIt(t *testing.T) {
	s := upWithPush(4, time.Minute)
	_, effs := Reduce(s, PushDue{}, env(t0.Add(60*time.Second), head("a", 1, 5), head("b", 1, 10), head("c", 1, 60)))
	if !reflect.DeepEqual(effs, []Effect{Send{"a", 1}, Send{"b", 1}, Send{"c", 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestTheCutoffAdvancesOverAnUnfinishedPush(t *testing.T) {
	s := upWithPush(1, time.Minute)
	s, effs := Reduce(s, PushDue{}, env(t0.Add(60*time.Second), head("a", 1, 5), head("b", 1, 10)))
	if !reflect.DeepEqual(effs, []Effect{Send{"a", 1}}) {
		t.Fatalf("first tick: %+v", effs)
	}
	// b was due at the tick and stays due; c, produced after it, is not
	s, effs = Reduce(s, Credit{N: 2}, env(t0.Add(70*time.Second), head("b", 1, 10), head("c", 1, 65)))
	if !reflect.DeepEqual(effs, []Effect{Send{"b", 1}}) {
		t.Fatalf("credit: %+v", effs)
	}
	_, effs = Reduce(s, PushDue{}, env(t0.Add(120*time.Second), head("c", 1, 65)))
	if !reflect.DeepEqual(effs, []Effect{Send{"c", 1}}) {
		t.Fatalf("second tick: %+v", effs)
	}
}

func TestDropsDoNotWaitForThePushTick(t *testing.T) {
	big := head("a", 1, 5)
	big.Bytes = 70000
	lost := head("b", 1, 5)
	lost.IDsKnown = false
	s := upWithPush(4, 30*time.Minute)
	_, effs := Reduce(s, Wake{}, env(t0.Add(130*time.Second), big, lost))
	want := []Effect{DropAsGap{Stream: "a", Seq: 1, Reason: buffer.ReasonOversize}, DropAsGap{Stream: "b", Seq: 1, Reason: buffer.ReasonUnregistered}}
	if !reflect.DeepEqual(effs, want) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestSessionUpArmsThePushTickerOnlyWhenTheIntervalChanges(t *testing.T) {
	s := NewState()
	s.BufferAge = time.Hour
	s, effs := Reduce(s, SessionUp{ID: "s1", Limits: withPush(time.Minute)}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}, ArmPush{Every: time.Minute}}) || s.PushInterval != time.Minute {
		t.Fatalf("first session: %+v", effs)
	}
	s, _ = Reduce(s, SessionDown{}, env(t0))
	if s.PushInterval != time.Minute {
		t.Fatalf("a session drop changed the push interval to %v", s.PushInterval)
	}
	// Review Focus 1: the same value again re-arms nothing
	s, effs = Reduce(s, SessionUp{ID: "s2", Limits: withPush(time.Minute)}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}}) {
		t.Fatalf("the same interval re-armed the ticker: %+v", effs)
	}
	s, _ = Reduce(s, SessionDown{}, env(t0))
	s, effs = Reduce(s, SessionUp{ID: "s3", Limits: limits}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}, ArmPush{Every: 0}}) || s.PushInterval != 0 {
		t.Fatalf("push turned off: %+v", effs)
	}
}

func TestAPushIntervalNotBelowTheBufferAgeIsRefused(t *testing.T) {
	s := NewState()
	s.BufferAge = time.Hour
	s, effs := Reduce(s, SessionUp{ID: "s1", Limits: withPush(time.Hour)}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}}) || s.PushInterval != 0 {
		t.Fatalf("effects %+v push %v", effs, s.PushInterval)
	}
}

func TestOverdueHeadsGoAtOnceAfterAReconnect(t *testing.T) {
	s := upWithPush(0, time.Minute) // no credits: the tick's batch stays stored
	s, _ = Reduce(s, PushDue{}, env(t0.Add(60*time.Second), head("a", 1, 30)))
	s, _ = Reduce(s, SessionDown{}, env(t0.Add(61*time.Second)))
	s, _ = Reduce(s, SessionUp{ID: "s2", Limits: withPush(time.Minute)}, env(t0.Add(70*time.Second)))
	_, effs := Reduce(s, StreamOpened{}, env(t0.Add(71*time.Second), head("a", 1, 30), head("b", 1, 65)))
	if !reflect.DeepEqual(effs, []Effect{Send{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

// Deviation 1: batches stored while push was immediate were already due.
func TestSwitchingFromImmediateKeepsWhatIsStoredDue(t *testing.T) {
	s := NewState()
	s.BufferAge, s.DueBy = time.Hour, t0
	s, _ = Reduce(s, SessionUp{ID: "s1", Limits: limits}, env(t0))
	s, _ = Reduce(s, SessionDown{}, env(t0.Add(10*time.Second)))
	s, _ = Reduce(s, SessionUp{ID: "s2", Limits: withPush(time.Minute)}, env(t0.Add(40*time.Second)))
	_, effs := Reduce(s, StreamOpened{}, env(t0.Add(41*time.Second), head("a", 1, 30), head("b", 1, 41)))
	if !reflect.DeepEqual(effs, []Effect{Send{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

// Review Focus 2.
func TestTurningThePushIntervalOffReleasesEverythingStored(t *testing.T) {
	s := upWithPush(0, time.Minute)
	s, _ = Reduce(s, SessionDown{}, env(t0.Add(30*time.Second)))
	s, _ = Reduce(s, SessionUp{ID: "s2", Limits: limits}, env(t0.Add(40*time.Second)))
	_, effs := Reduce(s, StreamOpened{}, env(t0.Add(41*time.Second), head("a", 1, 20), head("b", 1, 35)))
	if !reflect.DeepEqual(sends(effs), []Send{{"a", 1}, {"b", 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

// Review Focus 3: the wall clock stepped back, so a stored batch claims a
// produced_at after the latest tick. It waits, bounded by the step, and goes
// at the first tick after the clock passes it.
func TestABatchFromTheFutureIsSentOnceTheClockPassesIt(t *testing.T) {
	s := upWithPush(4, time.Minute)
	future := head("a", 1, 100)
	s, effs := Reduce(s, PushDue{}, env(t0.Add(60*time.Second), future))
	if len(effs) != 0 {
		t.Fatalf("sent a batch produced after the tick: %+v", effs)
	}
	_, effs = Reduce(s, PushDue{}, env(t0.Add(120*time.Second), future))
	if !reflect.DeepEqual(effs, []Effect{Send{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `go test ./internal/telemetry/send/`
Expected: FAIL to compile with `s.BufferAge undefined` (and `undefined: PushDue`, `undefined: ArmPush`).

- [ ] **Step 3: Write the implementation**

In `internal/telemetry/send/reducer.go`:

Add the import `"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"`.

Add three fields at the end of `type State struct`:

```go
	PushInterval   time.Duration // AG-D26: 0 sends each batch at once; otherwise batches wait for a push tick
	DueBy          time.Time     // a batch produced at or before it is due
	BufferAge      time.Duration // the age cap a push interval must stay below; set once by the runner
```

Add the event beside `Wake` in the event `type (…)` block, and its marker method:

```go
	PushDue    struct{}                // the push ticker fired (AG-D26)
```

```go
func (PushDue) event()      {}
```

Add the effect beside `ArmRetry`, and its marker method:

```go
	ArmPush  struct{ Every time.Duration } // reset the push ticker; 0 stops it
```

```go
func (ArmPush) effect()     {}
```

In `Reduce`, `case SessionUp:`, replace the final line `effs = append(effs, OpenStream{})` with:

```go
		effs = append(effs, OpenStream{})
		// Resolved here, from the limits this event carries, not delivered by a
		// separate event that would race it (spec §3.1).
		if set, _ := cadence.Resolve(e.Limits, s.BufferAge); set.Push != s.PushInterval {
			if s.PushInterval == 0 {
				s.DueBy = env.Now // stored under immediate push: all of it is already due
			}
			s.PushInterval = set.Push
			effs = append(effs, ArmPush{Every: s.PushInterval})
		}
```

Add a case to `Reduce`'s switch, before `case Wake:`:

```go
	case PushDue:
		s.DueBy = env.Now
```

In `selectSends`, add this case after the `case !h.IDsKnown:` block and before `case s.Credits == 0:`:

```go
		case s.PushInterval > 0 && h.ProducedAt.After(s.DueBy):
			continue // stored, not yet due (AG-D26)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `go test -race ./internal/telemetry/send/ -v`
Expected: PASS — every new test **and every slice 2 test unchanged**.

- [ ] **Step 5: Mutation check — the due check must be load-bearing**

Delete the two lines of the `case s.PushInterval > 0 && h.ProducedAt.After(s.DueBy):` block, then run:

Run: `go test ./internal/telemetry/send/ -run 'NotYetDue|CutoffAdvances|FromTheFuture|SwitchingFromImmediate|OverdueHeads'`
Expected: FAIL in `TestANotYetDueHeadWaitsForThePushTick`, `TestTheCutoffAdvancesOverAnUnfinishedPush`, `TestABatchFromTheFutureIsSentOnceTheClockPassesIt`, `TestSwitchingFromImmediateKeepsWhatIsStoredDue` and `TestOverdueHeadsGoAtOnceAfterAReconnect`.

Restore the block (`git diff internal/telemetry/send/reducer.go` shows only Step 3's changes), then run `go test ./internal/telemetry/send/` — PASS.

- [ ] **Step 6: Commit**

```bash
go vet ./... && go test ./...
git add internal/telemetry/send/reducer.go internal/telemetry/send/reducer_test.go
git commit -m "go-agent: sender reducer holds stored batches until the push tick (AG-D26)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Sender runner — the push ticker

**Files:**
- Modify: `internal/telemetry/send/runner.go`
- Test: `internal/telemetry/send/runner_test.go`

**Interfaces:**
- Consumes: `PushDue`, `ArmPush`, `State.DueBy`, `State.BufferAge` (Task 3).
- Produces: `Runner.BufferAge time.Duration` (exported config field, set by the pipeline in Task 6).

- [ ] **Step 1: Write the failing tests**

`runner_test.go` already imports `sync`, `context`, `buffer` and `view`. Append:

```go
func appendAt(t *testing.T, buf *buffer.Buffer, at time.Time) {
	t.Helper()
	if _, err := buf.Append("binding-1", at, []buffer.Sample{{Name: "cache.hit_rate", Type: "gauge", TS: time.Unix(0, 0).UTC(), Value: 0.93, ReplicaRef: "java1", Incarnation: "c1"}}); err != nil {
		t.Fatal(err)
	}
}

func pushSession(push time.Duration, open func(context.Context) (view.Stream, error)) *view.SessionInfo {
	s := session(open)
	s.Limits.TelemetryPushInterval = push
	return s
}

func TestRunnerHoldsANewBatchUntilThePushTick(t *testing.T) {
	hub := view.NewHub()
	_, buf, _ := runnerWith(t, hub, t.TempDir(), func(r *Runner) { r.BufferAge = time.Hour })
	s := newFakeStream()
	opened := make(chan struct{})
	var once sync.Once
	hub.Publish(view.Snapshot{Session: pushSession(600*time.Millisecond, func(context.Context) (view.Stream, error) {
		once.Do(func() { close(opened) })
		return s, nil
	}), Bindings: map[string]bool{"binding-1": true}, IDs: registered})
	<-opened // the runner has armed the push ticker: appending now is after its start
	appendOne(t, buf)
	select {
	case b := <-s.sent:
		t.Fatalf("seq %d sent before the push tick", b.BatchSeq)
	case <-time.After(300 * time.Millisecond):
	}
	receive(t, s) // at the tick, about 600 ms after the session
}

func TestRunnerSendsWhatWasStoredBeforeItStartedAtOnce(t *testing.T) {
	hub := view.NewHub()
	runnerWith(t, hub, t.TempDir(), func(r *Runner) {
		r.BufferAge = 2 * time.Hour
		appendAt(t, r.Buf, time.Now().Add(-time.Second))
	})
	s := newFakeStream()
	hub.Publish(view.Snapshot{Session: pushSession(time.Hour, func(context.Context) (view.Stream, error) { return s, nil }),
		Bindings: map[string]bool{"binding-1": true}, IDs: registered})
	receive(t, s) // within 5 s, though the first push tick is an hour away
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `go test ./internal/telemetry/send/ -run 'HoldsANewBatch|StoredBeforeItStarted'`
Expected: FAIL to compile with `r.BufferAge undefined (type *Runner has no field or method BufferAge)`.

- [ ] **Step 3: Write the implementation**

In `internal/telemetry/send/runner.go`:

Add the exported field after `Metrics *tmetrics.Metrics` in `type Runner struct`:

```go
	BufferAge time.Duration // --telemetry-buffer-age, for the push interval's bound (AG-D26)
```

Add the private field after `retry   *time.Timer`:

```go
	push    *time.Ticker // fires PushDue; stopped while the push interval is 0
```

In `Run`, replace the four lines from `r.state = NewState()` through `r.retry.Stop()` with:

```go
	r.state = NewState()
	// Everything stored before the agent started is already due (spec §3.4).
	r.state.DueBy, r.state.BufferAge = r.Now(), r.BufferAge
	r.in = make(chan any, 64)
	r.retry = time.NewTimer(time.Hour)
	r.retry.Stop()
	r.push = time.NewTicker(time.Hour)
	r.push.Stop()
	defer r.push.Stop()
```

In `Run`'s `select`, add after `case <-r.retry.C:` and its body:

```go
		case <-r.push.C:
			r.step(ctx, PushDue{})
```

In `exec`, add a case after `case ArmRetry:` and its body:

```go
	case ArmPush:
		if e.Every > 0 {
			r.push.Reset(e.Every) // Reset panics on a non-positive duration
		} else {
			r.push.Stop()
		}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `go test -race ./internal/telemetry/send/ -v`
Expected: PASS, including every slice 2 runner test.

- [ ] **Step 5: Mutation check — `DueBy` at start must be load-bearing**

Change `r.state.DueBy, r.state.BufferAge = r.Now(), r.BufferAge` to `r.state.BufferAge = r.BufferAge`.

Run: `go test ./internal/telemetry/send/ -run StoredBeforeItStarted`
Expected: FAIL with `nothing sent`.

Restore the line; `go test ./internal/telemetry/send/` — PASS.

- [ ] **Step 6: Commit**

```bash
go vet ./... && go test ./... && go test -race ./internal/telemetry/...
git add internal/telemetry/send/runner.go internal/telemetry/send/runner_test.go
git commit -m "go-agent: the sender's push ticker; what was stored before start is due at once

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Collector — follow the DP's pull interval

**Files:**
- Modify: `internal/telemetry/collect/collect.go` (`Collector` struct, `Run`, `Tick`'s timeout line)
- Test: `internal/telemetry/collect/collect_test.go`

**Interfaces:**
- Consumes: `cadence.Holder`, `cadence.Settings` (Task 2).
- Produces: `Collector.Cadence *cadence.Holder` (nil = always `Decl.Interval`; set by the pipeline in Task 6).

- [ ] **Step 1: Write the failing tests**

Add the import `"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"` to `collect_test.go`, then append:

```go
func okTarget(t *testing.T) string {
	return serve(t, func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, `{"hitRate": 1}`) })
}

func runFor(c *Collector, d time.Duration) {
	ctx, cancel := context.WithTimeout(context.Background(), d)
	defer cancel()
	c.Run(ctx)
}

func TestTheDPPullIntervalReplacesTheDeclaredOne(t *testing.T) {
	out := &sink{}
	c := collector(okTarget(t), out, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Cadence = cadence.NewHolder()
	c.Cadence.Set(cadence.Settings{Pull: 50 * time.Millisecond})
	runFor(c, 400*time.Millisecond)
	if n := len(out.all()); n < 5 {
		t.Fatalf("%d ticks in 400ms at a 50ms DP interval; the declared 1s gives 1", n)
	}
}

func TestAZeroDPPullKeepsTheDeclaredInterval(t *testing.T) {
	out := &sink{}
	c := collector(okTarget(t), out, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Cadence = cadence.NewHolder()
	c.Cadence.Set(cadence.Settings{Push: time.Minute})
	runFor(c, 400*time.Millisecond)
	if n := len(out.all()); n != 1 {
		t.Fatalf("%d ticks in 400ms; the declared 1s gives exactly 1", n)
	}
}

func TestACadenceChangeResetsTheTicker(t *testing.T) {
	out := &sink{}
	c := collector(okTarget(t), out, nil, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Cadence = cadence.NewHolder()
	go func() {
		time.Sleep(100 * time.Millisecond)
		c.Cadence.Set(cadence.Settings{Pull: 50 * time.Millisecond})
	}()
	runFor(c, 600*time.Millisecond)
	if n := len(out.all()); n < 5 {
		t.Fatalf("%d ticks: the ticker kept the declared 1s after the change", n)
	}
}

func TestTheTimeoutIsClampedToTheDPPullInterval(t *testing.T) {
	addr := serve(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(300 * time.Millisecond):
		case <-r.Context().Done():
			return
		}
		_, _ = io.WriteString(w, `{"hitRate": 1}`)
	})
	out, m := &sink{}, tmetrics.New()
	c := collector(addr, out, m, decl(time.Second, metric("cache.hit_rate", "gauge", "/hitRate")))
	c.Cadence = cadence.NewHolder()
	c.Cadence.Set(cadence.Settings{Pull: 100 * time.Millisecond})
	c.Tick(context.Background())
	if len(out.all()) != 0 || testutil.ToFloat64(m.ScrapeErrors.WithLabelValues("binding-1", "http")) != 1 {
		t.Fatalf("a 300ms answer was accepted under a 100ms pull interval: %d batches", len(out.all()))
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `go test ./internal/telemetry/collect/`
Expected: FAIL to compile with `c.Cadence undefined (type *Collector has no field or method Cadence)`.

- [ ] **Step 3: Write the implementation**

In `internal/telemetry/collect/collect.go`:

Add the import `"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"`.

Add the field after `Metrics *tmetrics.Metrics` in `type Collector struct`:

```go
	Cadence *cadence.Holder // the DP's pull interval (AG-D26); nil: always Decl.Interval
```

Replace `Run` and its doc comment with:

```go
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
```

In `Tick`, replace `ctx, cancel := context.WithTimeout(ctx, c.Decl.Timeout)` with:

```go
	ctx, cancel := context.WithTimeout(ctx, c.timeout())
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `go test -race ./internal/telemetry/collect/ -v`
Expected: PASS, including every slice 2 collector test.

- [ ] **Step 5: Mutation checks — the reset and the clamp must be load-bearing**

1. Delete the line `t.Reset(every) // every > 0: …`.
   Run: `go test ./internal/telemetry/collect/ -run ACadenceChangeResetsTheTicker`
   Expected: FAIL with `the ticker kept the declared 1s after the change`. Restore the line.
2. In `timeout`, change `return min(c.Decl.Timeout, c.interval(c.Cadence.Current()))` to `return c.Decl.Timeout`.
   Run: `go test ./internal/telemetry/collect/ -run TimeoutIsClamped`
   Expected: FAIL with `a 300ms answer was accepted`. Restore the line.

Run: `go test ./internal/telemetry/collect/` — PASS.

- [ ] **Step 6: Commit**

```bash
go vet ./... && go test ./... && go test -race ./internal/telemetry/...
git add internal/telemetry/collect/
git commit -m "go-agent: collectors follow the DP-set pull interval; timeout clamped to it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Pipeline — watch the session, publish the settings, show them

**Files:**
- Modify: `internal/telemetry/tmetrics/tmetrics.go`
- Modify: `internal/telemetry/pipeline.go`
- Test: `internal/telemetry/pipeline_test.go` (new)

**Interfaces:**
- Consumes: `cadence.Resolve`, `cadence.Holder` (Task 2); `Runner.BufferAge` (Task 4); `Collector.Cadence` (Task 5).
- Produces:
  - `Metrics.PullInterval *prometheus.GaugeVec` (label `binding`), `Metrics.PushInterval prometheus.Gauge`, `Metrics.LimitsIgnored *prometheus.CounterVec` (label `limit`)
  - `(*Metrics) SetCadence(pull map[string]time.Duration, push time.Duration)`, `(*Metrics) LimitIgnored(limit string)`
  - `(*Pipeline) watchCadence(ctx context.Context)` (unexported; run by `Run`)

- [ ] **Step 1: Write the failing tests**

Create `internal/telemetry/pipeline_test.go`:

```go
package telemetry

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
)

// watching builds a pipeline with one binding declared at 5 s and runs only
// its cadence watcher.
func watching(t *testing.T) (*Pipeline, *view.Hub) {
	t.Helper()
	hub := view.NewHub()
	decl := &declare.File{SchemaVersion: 1, Bindings: map[string]declare.Binding{"binding-1": {Interval: 5 * time.Second, Timeout: time.Second}}}
	p, err := New(Config{Store: store.MustOpen(t.TempDir()), Declarations: decl, BufferAge: time.Hour, View: hub,
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { p.watchCadence(ctx); close(done) }()
	t.Cleanup(func() { cancel(); <-done })
	return p, hub
}

func sessionWith(id string, pull, push time.Duration) view.Snapshot {
	return view.Snapshot{Session: &view.SessionInfo{ID: id, Limits: msg.Limits{TelemetryPullInterval: pull, TelemetryPushInterval: push}}}
}

func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("never: %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func gauges(p *Pipeline) (pull, push float64) {
	return testutil.ToFloat64(p.metrics.PullInterval.WithLabelValues("binding-1")), testutil.ToFloat64(p.metrics.PushInterval)
}

func TestANewSessionAppliesTheDPIntervalsAndADropKeepsThem(t *testing.T) {
	p, hub := watching(t)
	if pull, push := gauges(p); pull != 5 || push != 0 {
		t.Fatalf("at start: pull %v push %v, want the declared 5 and 0", pull, push)
	}
	hub.Publish(sessionWith("s1", 2*time.Second, time.Minute))
	want := cadence.Settings{Pull: 2 * time.Second, Push: time.Minute}
	eventually(t, "the DP's intervals applied", func() bool { return p.cadence.Current() == want })
	if pull, push := gauges(p); pull != 2 || push != 60 {
		t.Fatalf("gauges pull %v push %v", pull, push)
	}
	hub.Publish(view.Snapshot{}) // the session drops
	time.Sleep(50 * time.Millisecond)
	if got := p.cadence.Current(); got != want {
		t.Fatalf("a session drop changed the intervals to %+v", got)
	}
	hub.Publish(sessionWith("s2", 0, 0)) // a new session that sets neither
	eventually(t, "the local defaults back", func() bool { return p.cadence.Current() == (cadence.Settings{}) })
	if pull, push := gauges(p); pull != 5 || push != 0 {
		t.Fatalf("gauges after s2: pull %v push %v", pull, push)
	}
}

func TestARefusedDPIntervalIsCountedAndTheDefaultKept(t *testing.T) {
	p, hub := watching(t)
	hub.Publish(sessionWith("s1", 500*time.Millisecond, 2*time.Hour))
	ignored := func(limit string) float64 { return testutil.ToFloat64(p.metrics.LimitsIgnored.WithLabelValues(limit)) }
	eventually(t, "both refusals counted", func() bool { return ignored("pull") == 1 && ignored("push") == 1 })
	if got := p.cadence.Current(); got != (cadence.Settings{}) {
		t.Fatalf("settings %+v, want the defaults", got)
	}
	if pull, push := gauges(p); pull != 5 || push != 0 {
		t.Fatalf("gauges pull %v push %v", pull, push)
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `go test ./internal/telemetry/`
Expected: FAIL to compile with `p.watchCadence undefined` (and `p.cadence`, `p.metrics.PullInterval` undefined).

- [ ] **Step 3: Add the metrics**

In `internal/telemetry/tmetrics/tmetrics.go`:

Add three fields at the end of `type Metrics struct`, before `all`:

```go
	PullInterval    *prometheus.GaugeVec
	PushInterval    prometheus.Gauge
	LimitsIgnored   *prometheus.CounterVec
```

In `New`, add to the struct literal:

```go
		PullInterval:    prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "sm_agent_telemetry_pull_interval_seconds", Help: "The interval each binding is pulled at: the DP's (AG-D26) or the declared one."}, []string{"binding"}),
		PushInterval:    prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_telemetry_push_interval_seconds", Help: "The push interval in use (AG-D26); 0 sends each batch as soon as it is written."}),
		LimitsIgnored:   counterVec("sm_agent_telemetry_limit_ignored_total", "DP-set telemetry intervals the agent refused, by limit (AG-D26).", "limit"),
```

and append `m.PullInterval, m.PushInterval, m.LimitsIgnored` to the `m.all` slice.

Add two helpers at the end of the file:

```go
// SetCadence exports the intervals in use: per binding for pull, one for push.
func (m *Metrics) SetCadence(pull map[string]time.Duration, push time.Duration) {
	if m == nil {
		return
	}
	for b, d := range pull {
		m.PullInterval.WithLabelValues(b).Set(d.Seconds())
	}
	m.PushInterval.Set(push.Seconds())
}

func (m *Metrics) LimitIgnored(limit string) {
	if m != nil {
		m.LimitsIgnored.WithLabelValues(limit).Inc()
	}
}
```

- [ ] **Step 4: Wire the holder and the watcher**

In `internal/telemetry/pipeline.go`:

Add the import `"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"`.

Add the field to `type Pipeline struct`:

```go
	cadence *cadence.Holder // the DP's intervals in use (AG-D26)
```

In `New`, replace the final `return &Pipeline{cfg: cfg, buf: buf, metrics: m}, nil` with:

```go
	p := &Pipeline{cfg: cfg, buf: buf, metrics: m, cadence: cadence.NewHolder()}
	m.SetCadence(p.pulls(cadence.Settings{}), 0)
	return p, nil
```

In `Run`, add `Cadence: p.cadence,` to the `collect.Collector` literal, add `BufferAge: p.cfg.BufferAge,` to the `send.Runner` literal, and add after `wg.Go(func() { r.Run(ctx) })`:

```go
	wg.Go(func() { p.watchCadence(ctx) })
```

Add at the end of the file:

```go
// watchCadence applies the DP's telemetry intervals (AG-D26) when a new
// session's limits arrive. A session drop changes nothing (spec §2.3).
func (p *Pipeline) watchCadence(ctx context.Context) {
	var seen string
	for {
		ch := p.cfg.View.Changed()
		if s := p.cfg.View.Current().Session; s != nil && s.ID != seen {
			seen = s.ID
			set, ignored := cadence.Resolve(s.Limits, p.cfg.BufferAge)
			for _, ig := range ignored {
				p.cfg.Log.Warn("telemetry: DP interval refused; using the local default",
					"limit", ig.Limit, "value", ig.Value.String(), "reason", ig.Reason)
				p.metrics.LimitIgnored(ig.Limit)
			}
			if p.cadence.Set(set) {
				p.metrics.SetCadence(p.pulls(set), set.Push)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-ch:
		}
	}
}

// pulls is the interval each declared binding is collected at.
func (p *Pipeline) pulls(set cadence.Settings) map[string]time.Duration {
	out := map[string]time.Duration{}
	for id, b := range p.cfg.Declarations.Bindings {
		out[id] = b.Interval
		if set.Pull > 0 {
			out[id] = set.Pull
		}
	}
	return out
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `go test -race ./internal/telemetry/... -v -run 'NewSessionApplies|RefusedDPInterval'`
Expected: PASS, two tests. Then `go test -race ./internal/telemetry/...` — PASS.

- [ ] **Step 6: Commit**

```bash
go vet ./... && go test ./... && go test -race ./internal/telemetry/...
git add internal/telemetry/tmetrics/tmetrics.go internal/telemetry/pipeline.go internal/telemetry/pipeline_test.go
git commit -m "go-agent: the pipeline applies the DP's telemetry intervals per session (AG-D26)

New gauges for the intervals in use and a counter for refused values.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `fake-gateway` — set the intervals from the scenario and the console

**Files:**
- Modify: `internal/fakegw/scenario.go` (`LimitsSpec`)
- Modify: `internal/fakegw/server.go` (`Server` fields; `Start`)
- Modify: `internal/fakegw/session.go` (`limits()`)
- Modify: `internal/fakegw/telemetry.go` (imports; `printf`; two new methods)
- Modify: `internal/fakegw/console.go` (`consoleUsage`; the `set` verb)
- Test: `internal/fakegw/console_test.go`, `internal/fakegw/telemetry_test.go`

**Interfaces:**
- Consumes: `msg.Limits.TelemetryPullInterval`, `msg.Limits.TelemetryPushInterval` (Task 1).
- Produces:
  - `LimitsSpec.TelemetryPullInterval`, `LimitsSpec.TelemetryPushInterval` (YAML `telemetry_pull_interval`, `telemetry_push_interval`)
  - `(*Server) SetTelemetryInterval(which string, d time.Duration) error` (`which` ∈ `pull`, `push`)
  - `(*Server) TelemetryIntervals() (pull, push time.Duration)`
  - console verb `set pull|push <duration>`

- [ ] **Step 1: Write the failing tests**

Append to `internal/fakegw/console_test.go` (add `"time"` to its imports):

```go
func TestConsoleSetStoresIntervalsForTheNextSession(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	for _, tc := range []struct{ line, want string }{
		{"set pull 5s", "next session: pull=5s push=0s (disconnect to apply)"},
		{"set push 1m", "next session: pull=5s push=1m0s (disconnect to apply)"},
		{"set push 0", "next session: pull=5s push=0s (disconnect to apply)"},
		// Review Focus 5: a malformed line prints why and changes nothing
		{"set push", "usage: set pull|push <duration>"},
		{"set push abc", `set: time: invalid duration "abc"`},
		{"set foo 1s", "usage: set pull|push <duration>"},
	} {
		var out bytes.Buffer
		s.Console(strings.NewReader(tc.line+"\n"), &out)
		if !strings.Contains(out.String(), tc.want) {
			t.Errorf("%q: want %q, got %q", tc.line, tc.want, out.String())
		}
	}
	if pull, push := s.TelemetryIntervals(); pull != 5*time.Second || push != 0 {
		t.Fatalf("stored pull %v push %v", pull, push)
	}
}
```

Append to `internal/fakegw/telemetry_test.go` (add `"regexp"` and `"time"` to its imports):

```go
func TestTheNextSessionAcceptCarriesTheTelemetryIntervals(t *testing.T) {
	s, _ := telemetryServer(t, func(sc *Scenario) { sc.Limits.TelemetryPullInterval = 2 * time.Second })
	if err := s.SetTelemetryInterval("push", time.Minute); err != nil {
		t.Fatal(err)
	}
	conn := agentConn(t, s, false)
	if err := conn.Send(msg.SessionOpen{Envelope: msg.Envelope{MessageID: "open"}, ProtocolVersion: 1}); err != nil {
		t.Fatal(err)
	}
	m, err := conn.Recv()
	if err != nil {
		t.Fatal(err)
	}
	a, ok := m.(msg.SessionAccept)
	if !ok || a.Limits.TelemetryPullInterval != 2*time.Second || a.Limits.TelemetryPushInterval != time.Minute {
		t.Fatalf("got %T %+v", m, m)
	}
}

func TestTelemetryLinesCarryTheTime(t *testing.T) {
	s, out := telemetryServer(t, nil)
	ts := openTelemetry(t, agentConn(t, s, true))
	sendBatch(t, ts, 1)
	recvAck(t, ts)
	if !regexp.MustCompile(`(?m)^\[\d{2}:\d{2}:\d{2}\] telemetry binding-1 seq=1 `).MatchString(out.String()) {
		t.Fatalf("no time prefix:\n%s", out.String())
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `go test ./internal/fakegw/`
Expected: FAIL to compile with `s.TelemetryIntervals undefined` (and `SetTelemetryInterval`, `sc.Limits.TelemetryPullInterval` undefined).

- [ ] **Step 3: Write the implementation**

`internal/fakegw/scenario.go` — add at the end of `type LimitsSpec struct`:

```go
	// AG-D26, a DRAFT ask (AG-23); 0 leaves the agent's local defaults.
	TelemetryPullInterval time.Duration `yaml:"telemetry_pull_interval"`
	TelemetryPushInterval time.Duration `yaml:"telemetry_push_interval"`
```

`internal/fakegw/server.go` — add to `type Server struct`, after `rejectNext string`:

```go
	pull, push time.Duration // the telemetry intervals the next SessionAccept carries (AG-D26)
```

and in `Start`'s `s := &Server{…}` literal, after `rejectNext: cfg.Scenario.Policy.Telemetry.RejectNext,`:

```go
		pull: cfg.Scenario.Limits.TelemetryPullInterval, push: cfg.Scenario.Limits.TelemetryPushInterval,
```

`internal/fakegw/session.go` — replace `limits()` with:

```go
func (ss *session) limits() msg.Limits {
	l := ss.sc.Limits
	pull, push := ss.s.TelemetryIntervals()
	return msg.Limits{MaxMessageBytes: l.MaxMessageBytes, MaxBatchBytes: l.MaxBatchBytes, InitialCredits: l.InitialCredits,
		HeartbeatInterval: l.HeartbeatInterval, KeepaliveInterval: l.KeepaliveInterval, SkewTolerance: l.SkewTolerance, ReportInterval: l.ReportInterval,
		TelemetryPullInterval: pull, TelemetryPushInterval: push}
}
```

`internal/fakegw/telemetry.go` — add `"errors"` and `"time"` to the imports; replace `printf` with:

```go
func (s *Server) printf(format string, a ...any) {
	if s.cfg.Out == nil {
		return
	}
	s.outMu.Lock()
	defer s.outMu.Unlock()
	// local time first, so a push burst is visible (AG-D26)
	fmt.Fprintf(s.cfg.Out, "[%s] %s\n", time.Now().Format("15:04:05"), fmt.Sprintf(format, a...))
}
```

and add at the end of the file:

```go
// SetTelemetryInterval stores the pull or push interval the next SessionAccept
// carries (AG-D26). The live session keeps the limits it was accepted with;
// Disconnect makes the agent take the new ones. Any value is stored, including
// one the agent will refuse: this is a test double.
func (s *Server) SetTelemetryInterval(which string, d time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	switch which {
	case "pull":
		s.pull = d
	case "push":
		s.push = d
	default:
		return errors.New("usage: set pull|push <duration>")
	}
	return nil
}

// TelemetryIntervals returns what the next SessionAccept carries.
func (s *Server) TelemetryIntervals() (pull, push time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.pull, s.push
}
```

`internal/fakegw/console.go` — replace `consoleUsage` with:

```go
const consoleUsage = "type: preflight | apply <file> | restart | stop | pause | resume | disconnect | set pull|push <duration>"
```

and in `line`, add a case to the first `switch f[0]`, after `case "disconnect":` and its body:

```go
	case "set":
		if len(f) != 3 {
			c.println("usage: set pull|push <duration>")
			return
		}
		d, err := time.ParseDuration(f[2])
		if err != nil {
			c.println("set: %v", err)
			return
		}
		if err := c.s.SetTelemetryInterval(f[1], d); err != nil {
			c.println("%v", err)
			return
		}
		pull, push := c.s.TelemetryIntervals()
		c.println("next session: pull=%s push=%s (disconnect to apply)", pull, push)
		return
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `go test -race ./internal/fakegw/ -v`
Expected: PASS, including every existing console and telemetry test (they match with `strings.Contains`, so the time prefix does not break them).

- [ ] **Step 5: Commit**

```bash
go vet ./... && go test ./... && go test -race ./internal/fakegw/
git add internal/fakegw/
git commit -m "fake-gateway: set the telemetry intervals from the scenario or the console

'set pull|push <duration>' stores them for the next SessionAccept; telemetry
lines carry the time so push bursts are visible.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Integration — the three scenarios end to end

**Files:**
- Test: `internal/agent/telemetry_integration_test.go`

**Interfaces:**
- Consumes: `fakegw.Scenario.Limits.TelemetryPullInterval/PushInterval`, `fakegw.Server.Transcript()`, the console `set` verb (Task 7); the whole agent.

- [ ] **Step 1: Write the tests**

Append to `internal/agent/telemetry_integration_test.go`:

```go
// arrivals is when each TelemetryBatch reached the gateway after a moment, in order.
func arrivals(gw *fakegw.Server, after time.Time) []time.Time {
	var out []time.Time
	for _, e := range gw.Transcript() {
		if e.Dir == "in" && e.Kind == "TelemetryBatch" && e.At.After(after) {
			out = append(out, e.At)
		}
	}
	return out
}

// bursts splits arrivals wherever two are more than quiet apart.
func bursts(ts []time.Time, quiet time.Duration) [][]time.Time {
	var out [][]time.Time
	for i, at := range ts {
		if i == 0 || at.Sub(ts[i-1]) > quiet {
			out = append(out, nil)
		}
		out[len(out)-1] = append(out[len(out)-1], at)
	}
	return out
}

func TestIntegrationTelemetryPushIntervalSendsInBursts(t *testing.T) {
	sc := telemetryScenario()
	sc.Limits.TelemetryPushInterval = time.Second // pull stays fastDecl's 100 ms
	gw := startGateway(t, sc)
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 30, 20*time.Second) {
		t.Fatalf("only %d batches", len(batches(gw)))
	}
	bs := bursts(arrivals(gw, time.Time{}), 300*time.Millisecond)
	if len(bs) < 3 {
		t.Fatalf("%d bursts for 30 batches: batches were not held for the push tick", len(bs))
	}
	for i := 1; i < len(bs); i++ {
		if gap := bs[i][0].Sub(bs[i-1][len(bs[i-1])-1]); gap < 600*time.Millisecond {
			t.Fatalf("burst %d began %v after the previous one ended; push is 1s", i, gap)
		}
	}
}

func TestIntegrationTelemetryDPPullIntervalOverridesYAML(t *testing.T) {
	sc := telemetryScenario()
	sc.Limits.TelemetryPullInterval = time.Second // fastDecl declares 100 ms
	gw := startGateway(t, sc)
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 5, 20*time.Second) {
		t.Fatalf("only %d batches", len(batches(gw)))
	}
	bs := batches(gw)
	// The first may be scraped at 100 ms, before the session's limits reached the collector.
	for i := len(bs) - 3; i < len(bs); i++ {
		if d := bs[i].ProducedAt.Sub(bs[i-1].ProducedAt); d < 900*time.Millisecond || d > 1500*time.Millisecond {
			t.Fatalf("seq %d and %d produced %v apart; the DP set 1s", bs[i-1].BatchSeq, bs[i].BatchSeq, d)
		}
	}
}

func TestIntegrationTelemetryLimitsApplyAtNextSession(t *testing.T) {
	gw := startGateway(t, telemetryScenario())
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 5, 10*time.Second) {
		t.Fatal("no telemetry")
	}
	out := &syncBuffer{}
	gw.Console(strings.NewReader("set push 1s\n"), out)
	waitOutput(t, out, "next session: pull=0s push=1s (disconnect to apply)", 5*time.Second)
	mark := time.Now()
	time.Sleep(time.Second)
	if bs := bursts(arrivals(gw, mark), 300*time.Millisecond); len(bs) != 1 || len(bs[0]) < 5 {
		t.Fatalf("the live session changed schedule before a reconnect: %d bursts", len(bs))
	}
	gw.Console(strings.NewReader("disconnect\n"), out)
	waitOutput(t, out, "session closed", 5*time.Second)
	mark = time.Now()
	deadline := time.Now().Add(30 * time.Second)
	for len(arrivals(gw, mark)) == 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	time.Sleep(3500 * time.Millisecond) // what was stored during the reconnect, then about three push ticks
	if bs := bursts(arrivals(gw, mark), 300*time.Millisecond); len(bs) < 3 {
		t.Fatalf("%d bursts after the reconnect: the new push interval was not applied", len(bs))
	}
}
```

- [ ] **Step 2: Run them**

Run: `go test -tags integration ./internal/agent/ -run 'Integration.*(PushInterval|DPPullInterval|LimitsApplyAtNextSession)' -v`
Expected: PASS, three tests.

These tests pass only because Tasks 1–7 exist. To show each one guards its feature, run one mutation per test, restoring after each:

1. Task 3's due check removed → `TestIntegrationTelemetryPushIntervalSendsInBursts` FAILS (`1 bursts for 30 batches`).
2. Task 5's `t.Reset(every)` removed → `TestIntegrationTelemetryDPPullIntervalOverridesYAML` FAILS (`produced 100ms apart`).
3. In `fakegw/session.go`'s `limits()`, `TelemetryPushInterval: push` replaced by `TelemetryPushInterval: 0` → `TestIntegrationTelemetryLimitsApplyAtNextSession` FAILS (`the new push interval was not applied`).

- [ ] **Step 3: Run the whole integration suite**

Run: `go test -tags integration ./internal/agent/ -run Integration`
Expected: PASS — slice 1's and slice 2's scenarios unchanged.

- [ ] **Step 4: Commit**

```bash
go vet -tags integration ./... && go test ./...
git add internal/agent/telemetry_integration_test.go
git commit -m "go-agent: integration — push bursts, DP pull override, limits at the next session

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Record the decision, the open item and the runbook run

**Files:**
- Modify: `docs/design/go-agent/decisions.md` (index row; `AG-D22` body; new `AG-D26` body; changelog)
- Modify: `docs/design/go-agent/design.md` (§13.2: the `AG-14` row; new `AG-23` row)
- Modify: `docs/requirements/REQ-013-dp-set-telemetry-cadence.md`, `docs/requirements/traceability.md`
- Modify: `docs/superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md` (status line)
- Modify: `go-agent/README.md`, `go-agent/CLAUDE.md`, `go-agent/RUNBOOK.md`

Paths in this task are from the repository root.

- [ ] **Step 1: `decisions.md`**

After the `AG-D25` index row, add:

```markdown
| `AG-D26` | The DP sets the telemetry pull and push intervals per agent on `SessionAccept.limits`; a push interval holds stored batches until the next tick | PROPOSED |
```

At the end of the `AG-D22` body, add a paragraph (this records the amendment without changing `AG-D22`'s meaning, `I3`):

```markdown
**Amended by `AG-D26` (2026-10-09).** The declared `interval` becomes the fallback: when the DP sets `telemetry_pull_interval` on `SessionAccept.limits`, every binding is pulled at that instead, and the scrape timeout is clamped to it. Nothing else in this decision changes.
```

After the `AG-D25` body, before `---`, add:

```markdown
## `AG-D26` — The DP sets the telemetry pull and push intervals per agent

The Data Plane sets two durations per agent, `telemetry_pull_interval` and `telemetry_push_interval`, on `SessionAccept.limits` beside `report_interval`. **Pull** replaces every binding's declared `interval` (`AG-D22`) when it is at least 1 s, and the scrape timeout becomes min(declared timeout, pull interval). **Push** holds stored batches on disk until the next push tick, then sends every batch produced at or before that tick, oldest first, as individual batches under the existing credits and the one-in-flight rule (`AG-D23`). It must be shorter than `--telemetry-buffer-age`. `0` or absent means the local default: the declared interval, and sending each batch as soon as it is written, which is slice 2's behaviour. A value outside those bounds is refused, logged at `WARN` and counted in `sm_agent_telemetry_limit_ignored_total{limit}`, and the default applies. Values are recomputed on every new session and only then: a session drop keeps them, a reconnect that repeats them resets no ticker, and a changed value reaches a connected agent at its next session. Deletion on ack and both buffer caps are unchanged (`AG-D24`).

**Rationale.** A predictable send schedule, set by the person operating the Data Plane (`REQ-013`). `SessionAccept.limits` already carries the DP-set `report_interval`, so two more fields follow an existing pattern instead of adding a message. **Rejected.** *Merging the stored batches into one larger batch at each push* — fewer messages was not the goal, and it would rework `AG-D23`/`AG-D24`'s seq, in-flight and gap guarantees. *Per-binding intervals from the DP* — that is delivering declarations, `AG-14`. *A mid-session update message* — a new message type and its ordering rules, kept as the later option in `AG-23`. *Local flags* — the DP is the source.

⚠️ **Neither field is in gateway §4.3.** The DRAFT `.proto` carries them as an agent-side ask, open item `AG-23`; a gateway that sends neither leaves slice 2's behaviour. Spec: [`../../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md`](../../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md); plan: [`../../plans/go-agent/007-telemetry-cadence.md`](../../plans/go-agent/007-telemetry-cadence.md).
```

As the first changelog row:

```markdown
| 2026-10-09 | `AG-D26`, `PROPOSED`, from plan `docs/plans/go-agent/007-telemetry-cadence.md`: the DP sets the telemetry pull and push intervals per agent on `SessionAccept.limits`. Amends `AG-D22`, recorded in both entries. |
```

- [ ] **Step 2: `design.md` §13.2**

Append to the end of the `AG-14` row's text, before its closing `|`:

```markdown
 **`AG-D26` covers the pull interval per agent only**; a per-binding interval from the DP is still part of this item.
```

After the `AG-22` row, add:

```markdown
| AG-23 | **Two `SessionAccept.limits` fields that gateway §4.3 does not define: `telemetry_pull_interval` and `telemetry_push_interval`** (`AG-D26`). The DRAFT `.proto` carries them; only `fake-gateway` sends them, and a gateway that sends neither leaves slice 2's behaviour. Routed with them: (a) the DP is not told when the agent refuses a value; (b) whether the gateway should validate the values before sending; (c) the later option of a mid-session update message, so a change reaches a connected agent without a reconnect. Proposed; owner: the `AG-D*` and `AGW` owners |
```

- [ ] **Step 3: Requirement and spec status**

In `docs/requirements/REQ-013-dp-set-telemetry-cadence.md`, replace the "Satisfied by" body with:

```markdown
`AG-D26` (PROPOSED) in [`../design/go-agent/decisions.md`](../design/go-agent/decisions.md) · design [`../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md`](../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md) · built by [`../plans/go-agent/007-telemetry-cadence.md`](../plans/go-agent/007-telemetry-cadence.md). The status stays `PROPOSED` until the project owner accepts this requirement into scope.
```

In `docs/requirements/traceability.md`'s `REQ-013` row, replace `` `AG-D26` (proposed by the design; not yet in `decisions.md`) `` with `` `AG-D26` ``.

In the spec's header, replace `**Status:** DESIGN, approved section by section in brainstorming;` with `**Status:** DESIGN, approved section by section in brainstorming; built on `feat/go-agent-telemetry`;`.

- [ ] **Step 4: `go-agent/README.md` and `go-agent/CLAUDE.md`**

In `README.md`'s **Telemetry.** paragraph, after the sentence ending `…within the gateway's credits.`, insert:

```markdown
The Data Plane may set how often every binding is pulled and how often stored batches are pushed, on `SessionAccept.limits` (`AG-D26`, a DRAFT ask); without them the declared intervals apply and each batch is sent as soon as it is written.
```

In `CLAUDE.md`, after the slice 2 status table, add:

```markdown
**The DP-set telemetry intervals (`AG-D26`) are specified, planned and built on the same branch, not merged.** Design: [`../docs/superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md`](../docs/superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md); plan: [`../docs/plans/go-agent/007-telemetry-cadence.md`](../docs/plans/go-agent/007-telemetry-cadence.md). The two `Limits` fields are a DRAFT ask, open item `AG-23`.
```

In its Architecture block, change the `internal/telemetry/` line to:

```
internal/telemetry/    pipeline wiring (AG-D21); view/ declare/ jsonptr/ collect/ buffer/ send/ tmetrics/ cadence/
```

In "Invariants the final review paid for", add after the `UNREGISTERED` bullet:

```markdown
- **A reconnect that repeats the DP's intervals resets no ticker**, or a session flapping faster than the push interval would postpone every push (`TestSettingTheSameValuesDoesNotSignalAChange`, `TestSessionUpArmsThePushTickerOnlyWhenTheIntervalChanges`).
```

- [ ] **Step 5: `go-agent/RUNBOOK.md` — write the walk-through, then run it**

In the Telemetry section, rename `### 5. Clean up` to `### 6. Clean up` and insert before it:

````markdown
### 5. Pull and push intervals set by the gateway

The gateway can set how often the agent pulls every binding and how often it pushes what it has stored (`AG-D26`).
Both travel on `SessionAccept`, so a change applies when the agent next connects.

**Status: not yet executed.**

In terminal 1:

```
set pull 2s
set push 1m
disconnect
```

| When | Terminal 1 |
|---|---|
| `set pull 2s` | `next session: pull=2s push=0s (disconnect to apply)` |
| `set push 1m` | `next session: pull=2s push=1m0s (disconnect to apply)` |
| `disconnect` | `session closed; the agent will reconnect`; whatever was stored while it reconnected is sent at once |
| Then, once a minute | about thirty `[hh:mm:ss] telemetry binding-1 seq=…` lines with the same time, then a minute of silence |

In terminal 3:

```bash
curl -s localhost:9464/metrics | grep -E 'sm_agent_telemetry_(pull|push)_interval_seconds'
```

expect `sm_agent_telemetry_pull_interval_seconds{binding="binding-1"} 2` and `sm_agent_telemetry_push_interval_seconds 60`.
`set push 0` followed by `disconnect` returns to sending each batch as soon as it is written.
````

Add a row to "Telemetry: if it goes wrong":

```markdown
| `sm_agent_telemetry_limit_ignored_total{limit}` rises, and the agent logs `DP interval refused` | The gateway sent a pull below 1 s, or a push not below `--telemetry-buffer-age`. | `set` a value in range and `disconnect`; the log line names the value and the reason. |
```

Now run it for real, following the Telemetry section's steps 1–4 with `fake-gateway --interactive`, then step 5. If it runs: replace `**Status: not yet executed.**` with `**Status: executed on <date>.**` and replace the table's last column with the lines actually printed, the way steps 3–4 record theirs. If Docker or a free port is unavailable, leave the status line as it is and say so in the plan's Results.

- [ ] **Step 6: Check the links and commit**

Run from the repository root, checking every link in the changed files resolves:

```bash
python3 -I - docs/design/go-agent/decisions.md docs/design/go-agent/design.md docs/requirements/REQ-013-dp-set-telemetry-cadence.md docs/superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md go-agent/README.md go-agent/CLAUDE.md go-agent/RUNBOOK.md <<'EOF'
import sys, re, os
bad = 0
for f in sys.argv[1:]:
    d = os.path.dirname(f)
    for m in re.finditer(r'\]\(([^)#\s]+)(#[^)]*)?\)', open(f).read()):
        t = m.group(1)
        if not t.startswith('http') and not os.path.exists(os.path.normpath(os.path.join(d, t))):
            print("BROKEN", f, t); bad += 1
print("broken:", bad)
EOF
```

Expected: `broken: 0`.

```bash
git add docs/design/go-agent/ docs/requirements/ docs/superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md go-agent/README.md go-agent/CLAUDE.md go-agent/RUNBOOK.md
git commit -m "docs: record AG-D26 and open item AG-23; runbook walk-through for DP-set intervals

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Verify the branch and record the results

**Files:**
- Modify: `docs/plans/go-agent/007-telemetry-cadence.md` (this file: the Results section)

- [ ] **Step 1: The full suite**

Run, from `go-agent/`, each of these and keep the output:

```bash
go vet ./... && go vet -tags integration ./...
go test ./...
go test -race ./internal/telemetry/... ./internal/fakegw/
go test -tags integration ./internal/agent/ -run Integration
make build
```

Expected: every command exits 0. If one fails, stop and fix it with `superpowers:systematic-debugging`, then rerun all five.

- [ ] **Step 2: Fill in Results**

Replace the Results section below with: what was built per task (commit hashes from `git log --oneline`), each mutation check's observed failure message, whether the RUNBOOK run was executed (and if not, why), and every deviation found during execution, numbered on from the five above.

- [ ] **Step 3: Commit, then request the whole-branch review**

```bash
git add docs/plans/go-agent/007-telemetry-cadence.md
git commit -m "docs: plan 007 results

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Then use `superpowers:requesting-code-review` for the commits since `1899cff`.

---

## Results

Executed 2026-10-09 on `feat/go-agent-telemetry`, inline (`superpowers:executing-plans`), Go 1.26.4.

### What was built

| Task | Commit | Built |
|---|---|---|
| 1 | `8311e02` | DRAFT `Limits` fields 8 and 9, the `.proto` header note, regenerated `pb`, `msg.Limits` fields, codec mapping both ways |
| 2 | `ea28c37` | `internal/telemetry/cadence`: `Settings`, `Ignored`, `MinPull`, `Resolve`, `Holder` |
| 3 | `fff9619` | Sender reducer: `PushInterval`, `DueBy`, `BufferAge`; `PushDue`; `ArmPush`; the due check in `selectSends` |
| 4 | `f2a5a30` | Sender runner: `BufferAge`, the push ticker, `DueBy` at start |
| 5 | `17520da` | Collector: `Cadence`, the ticker follows it, the timeout clamp |
| 6 | `5877aee` | Pipeline: the holder, `watchCadence`, the two interval gauges and the refusal counter |
| 7 | `aa34eed` | `fake-gateway`: scenario fields, `SetTelemetryInterval`/`TelemetryIntervals`, the `set` verb, `[hh:mm:ss]` on telemetry lines |
| 8 | `cbd224d` | Three integration scenarios |
| 9 | `e887784` | `AG-D26` (and the amendment note in `AG-D22`), `AG-23`, the `AG-14` note, `REQ-013`/traceability, README, CLAUDE.md, the RUNBOOK walk-through |

### Mutation checks — observed

| Mutation | Observed |
|---|---|
| Task 3: due check removed | FAIL in all five named tests: `TestANotYetDueHeadWaitsForThePushTick`, `TestTheCutoffAdvancesOverAnUnfinishedPush`, `TestOverdueHeadsGoAtOnceAfterAReconnect`, `TestSwitchingFromImmediateKeepsWhatIsStoredDue`, `TestABatchFromTheFutureIsSentOnceTheClockPassesIt` |
| Task 4: `DueBy` at start removed | **Survived** — `TestRunnerSendsWhatWasStoredBeforeItStartedAtOnce` stayed green (deviation 6). With deviation 1's `DueBy = env.Now` also removed: `runner_test.go:311: nothing sent`. Deviation 1's line alone removed: `TestSwitchingFromImmediateKeepsWhatIsStoredDue` FAIL |
| Task 5: `t.Reset(every)` removed | `1 ticks: the ticker kept the declared 1s after the change` |
| Task 5: timeout clamp removed | `a 300ms answer was accepted under a 100ms pull interval: 1 batches` |
| Task 8.1: due check removed | `1 bursts for 30 batches: batches were not held for the push tick` |
| Task 8.2: `t.Reset(every)` removed | `seq 2 and 3 produced 99.522239ms apart; the DP set 1s` |
| Task 8.3: `fake-gateway` sends push `0` | `1 bursts after the reconnect: the new push interval was not applied` (deviation 7) |

Each mutation was restored and its package re-run green.

### Final verification (Task 10, Step 1)

All five exit 0: `go vet ./...` and `go vet -tags integration ./...`; `go test ./...`; `go test -race ./internal/telemetry/... ./internal/fakegw/`; `go test -tags integration ./internal/agent/ -run Integration` — 35 pass, 1 skip (`TestIntegrationRenewAtHalfLifeAndSameIDOnResend`, open item `AG-5`, unchanged); `make build`.

### RUNBOOK run — not executed

Docker Desktop was stopped on the execution host (its user service inactive, its socket stale), and the telemetry walk-through needs a container to scrape. Starting it was a host side effect outside the change, so the RUNBOOK's step 5 keeps **Status: not yet executed** (Task 9 Step 5's fallback). Its expected lines are read from the code and the integration tests, not from a run.

### Deviations found during execution

6. **Task 4's mutation check cannot fail as written.** A fresh runner has `PushInterval` 0, so the first `SessionUp` that sets a push interval already moves `DueBy` to now (deviation 1). That makes `DueBy` at start redundant. The line stays, because spec §3.5 names it and it costs nothing. The restart behaviour is still guarded: removing **both** sources fails `TestRunnerSendsWhatWasStoredBeforeItStartedAtOnce`.
7. **Task 8's third mutation does not compile as written.** Replacing `TelemetryPushInterval: push` with `0` leaves `push` unused, which Go rejects. It was applied as `pull, _ := ss.s.TelemetryIntervals()` plus `TelemetryPushInterval: 0`, which has the same effect.
8. **`design.md`'s `AG-14` row ended without a full stop**, so a `.` was inserted before the appended `AG-D26` sentence.
9. **Task 9's link check found six broken links that predate this plan.** Three in `go-agent/decisions.md` (rows 24–26) used a repo-root path as a relative link. Three in `traceability.md` pointed at `../design/agent-gateway/`, which does not exist. The targets were corrected so the check reports `broken: 0`, and `traceability.md` was added to the check because Task 9 edits it.

### Final review

A **self-review** of `1899cff..92f6e69` against `superpowers:requesting-code-review`'s checklist. No fresh reviewer was dispatched, because the repo's `CLAUDE.md` reserves the Agent tool for an explicit request. An author's self-review is weaker than a fresh reviewer's.

**One finding, not fixed, because the fix is a design change.** Push has no lower bound: spec §2.2 says valid when `> 0`, and Review Focus 4 accepts 1 ns. Measured on the execution host, a `time.Ticker` at 1 ns or 1 µs keeps a receiving loop at about one CPU-second per second (234 000 and 197 000 ticks/s); at 1 ms it costs about 37 ms/s. A gateway that mis-encodes a push interval (60 ns for 60 s, say) would therefore keep one core busy on every agent until a session carries a sane value. Pull is protected by its 1 s floor. The recommended amendment to `AG-D26` is a push floor (1 s, like pull), refused and counted like the other bounds. It is routed to the design owner, not decided here.

**Deferred minors.** `RUNBOOK.md`'s Steps §1 still quotes the console usage line without `set pull|push <duration>`. Telemetry step 4's recorded lines predate the `[hh:mm:ss]` prefix. `set` parses the duration before checking `pull|push`, so `set foo abc` reports the parse error, not the usage. A few timing-window tests could flake under heavy load. Spec §7's "no batch before the first push tick after its `produced_at`" is asserted in the reducer and runner tests, not in the integration test.

