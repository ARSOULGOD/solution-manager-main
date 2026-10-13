# go-agent plan 008 — the telemetry push interval (DP-set, OR-73's 1 min by default) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The `host-local` agent holds stored telemetry batches and sends them at each push tick — `OR-73`'s 1 min unless the DP sets `Limits.telemetry_push_interval` (`0` = send at once) — which builds `AGW-D27` item 4 on the agent side.

**Architecture:** One field is appended to the versioned agent contract. A pure `cadence.Resolve` turns the session's limits into the push interval. The sender's reducer resolves it inside `SessionUp` and holds heads produced after the last tick; its runner drives a push ticker. The pipeline reports the value (log, counter, gauge) on each new session. `fake-gateway` sends an explicit `0` by default so every existing test is unchanged, and can send a duration or nothing.

**Tech Stack:** Go 1.26.4, protobuf (buf 1.73.0, `protoc-gen-go` v1.36.12, `protoc-gen-go-grpc` v1.5.1), Prometheus client_golang, `gopkg.in/yaml.v3`; Python 3 for the contract gate.

**Spec:** [`../../design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md`](../../design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md). Read it before Task 1. It ports the push half of solution-manager-main's plan 007; this plan's code descends from that plan's reviewed code, adjusted where the spec differs (no pull, a 1 s floor, unset ≠ 0).

**Plan number:** 008, not 006: solution-manager-main's `006-telemetry-slice.md` and `007-telemetry-cadence.md` hold 006 and 007 (spec §12).

## Global Constraints

- **Repository:** `/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT` (note the spaces: quote every path), branch `go-agent/push-interval`. The base is the snapshot commit on branch `UAT`. Never touch `~/Documents/projects/solaceSolutionManager/solution-manager-main`.
- **Go commands run in `go-agent/`**: `cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT/go-agent"`.
- **Generators:** buf 1.73.0 and `protoc-gen-go` v1.36.12 are in `~/go/bin`. `~/go/bin/protoc-gen-go-grpc` is **1.6.2 and must not be used**; Task 1 installs the pinned v1.5.1 into `go-agent/bin/tools/` (gitignored).
- **Wire:** `google.protobuf.Duration telemetry_push_interval = 8;` appended to `Limits` in `contracts/agent/v1/agent_gateway.proto`. Append-only (`AGW-D11`); nothing else in the contract changes.
- **Semantics (spec §3):** unset → `DefaultPush` = 1 min; explicit `0` → send at once; valid = `0`, or ≥ `MinPush` (1 s) and < `--telemetry-buffer-age`; anything else is refused and the default applies. When `DefaultPush ≥ bufferAge` the default becomes `0`. `bufferAge ≤ 0` means no age cap (as in `buffer.Options`).
- **Lifetime:** resolved on each new session id only; a session drop keeps it; nothing is persisted.
- **Metric names:** `sm_agent_telemetry_push_interval_seconds` (gauge), `sm_agent_telemetry_limit_ignored_total{limit}` (counter, `limit="push"`).
- **Logs (`I6`):** durations and fixed reason strings only — never a payload or sample value.
- **fake-gateway default:** an explicit `0`.
- **Out of bounds:** nothing under `backend/` or `docs/design/backend/` changes (the backend team's); no edit to `AGW-D27` or any `AGW-D*`/`BE-D*` text.
- **Ids:** `AG-D30`, `REQ-015`, central open item **48**. Never reuse `AG-14`–`AG-23`, never give `AG-D26` or `REQ-013` the old meaning (spec §8).
- **Comments in code** cite `AG-D30` (not plan 007's `AG-D26`).
- **Every commit message ends with** `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A gateway that mis-encodes 60 s as 60 ns.** Expected: refused (below 1 s), the 1 min default applies, and no ticker spins a core (plan 007's final review measured ~1 CPU-second per second at 1 ns). Pinned in Task 2's `TestResolve` (`"nanoseconds"`).
2. **An operator who sets `--telemetry-buffer-age 30s`.** Expected: the 1 min default would expire every batch, so the agent sends at once and logs why, without counting a refusal. Pinned in Task 2 (`"unset, age cap 30 s"`) and Task 4 (`TestAnAgeCapAtOrBelowTheDefaultSendsAtOnceWithoutCounting`).
3. **A session that flaps faster than the push interval, repeating the same value.** Expected: the ticker is not re-armed, or every reconnect would postpone every push. Pinned in Task 3 (`TestSessionUpArmsThePushTickerOnlyWhenTheIntervalChanges`).
4. **An agent restarted with a backlog stored under a 1 min push.** Expected: everything stored before the restart goes on the first session, not a minute later. Pinned in Task 3 (`TestRunnerSendsWhatWasStoredBeforeItStartedAtOnce`).
5. **A scenario file with `telemetry_push_interval: 60` (no unit).** Expected: `LoadScenario` fails, naming the field, rather than silently sending 60 ns. Pinned in Task 5 (`TestScenarioPushIntervalParses`).

---

## File map

| File | Responsibility | Task |
|---|---|---|
| `contracts/agent/v1/agent_gateway.proto`, `baseline.json` | the appended field and its frozen record | 1 |
| `go-agent/internal/wire/pb/agent_gateway.pb.go` | generated | 1 |
| `go-agent/internal/msg/msg.go` | `Limits.TelemetryPushInterval *time.Duration` | 1 |
| `go-agent/internal/wire/codec/codec.go`, `codec_test.go` | the three wire states, both directions | 1 |
| `go-agent/internal/telemetry/cadence/cadence.go`, `cadence_test.go` (new) | `Resolve`, `DefaultPush`, `MinPush`, `Ignored` | 2 |
| `go-agent/internal/telemetry/send/reducer.go`, `reducer_test.go` | `PushInterval`, `DueBy`, `PushDue`, `ArmPush`, the due check | 3 |
| `go-agent/internal/telemetry/send/runner.go`, `runner_test.go` | the push ticker, `BufferAge`, `DueBy` at start | 3 |
| `go-agent/internal/telemetry/tmetrics/tmetrics.go` | the gauge and the counter | 4 |
| `go-agent/internal/telemetry/pipeline.go`, `pipeline_test.go` (new) | the session watcher that reports; `BufferAge` to the runner | 4 |
| `go-agent/internal/fakegw/scenario.go`, `server.go`, `session.go`, `telemetry.go`, `console.go`, `telemetry_test.go`, `console_test.go` | `PushSpec`, the scenario field, `set push`, the time prefix | 5 |
| `go-agent/internal/agent/telemetry_integration_test.go` | bursts, next-session, unset default | 6 |
| `docs/design/go-agent/decisions.md`, `docs/design/DECISIONS.md`, `docs/requirements/REQ-015-…md`, `README.md`, `traceability.md`, `go-agent/README.md`, `CLAUDE.md`, `RUNBOOK.md`, the spec's status line | records and docs | 7 |

---

### Task 1: The wire — contract field, regenerated code, `msg.Limits`, codec

**Files:**
- Modify: `contracts/agent/v1/agent_gateway.proto` (`message Limits`, about line 188)
- Modify: `contracts/agent/v1/baseline.json` (by the gate's `--write-baseline` only)
- Regenerate: `go-agent/internal/wire/pb/agent_gateway.pb.go`
- Modify: `go-agent/internal/msg/msg.go` (`type Limits`, about line 228)
- Modify: `go-agent/internal/wire/codec/codec.go` (`GatewayToPB` SessionAccept ≈ line 394; `GatewayFromPB` SessionAccept ≈ line 443)
- Test: `go-agent/internal/wire/codec/codec_test.go`

**Interfaces:**
- Produces: `msg.Limits.TelemetryPushInterval *time.Duration` (nil = unset); `pb.Limits.TelemetryPushInterval *durationpb.Duration` and `GetTelemetryPushInterval()`.

- [ ] **Step 0: Record the baseline is green**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT/go-agent"
git status --short          # expect: nothing
go vet ./... && go test ./... 2>&1 | tail -30
go test -tags integration ./internal/agent/ 2>&1 | tail -5
```

Expected: every package `ok`. If anything fails before a single edit, stop and report it: it is not this plan's to fix. Record the result in Results.

- [ ] **Step 1: Write the failing codec tests**

Append to `go-agent/internal/wire/codec/codec_test.go`:

```go
// AG-D30: telemetry_push_interval has three states on the wire — unset, 0 and a
// value — and each must survive both directions, presence included.
func TestTelemetryPushIntervalKeepsItsThreeStates(t *testing.T) {
	zero, minute := time.Duration(0), time.Minute
	for name, in := range map[string]*time.Duration{"unset": nil, "zero": &zero, "one minute": &minute} {
		p, err := GatewayToPB(msg.SessionAccept{Limits: msg.Limits{ReportInterval: time.Minute, TelemetryPushInterval: in}})
		if err != nil {
			t.Fatal(err)
		}
		if onWire := p.GetSessionAccept().GetLimits().GetTelemetryPushInterval() != nil; onWire != (in != nil) {
			t.Fatalf("%s: field present on the wire = %v", name, onWire)
		}
		out, err := GatewayFromPB(p)
		if err != nil {
			t.Fatal(err)
		}
		got := out.(msg.SessionAccept).Limits.TelemetryPushInterval
		if (got == nil) != (in == nil) || (got != nil && *got != *in) {
			t.Fatalf("%s: round trip gave %v", name, got)
		}
	}
}

// Today's Java gateway sends no field: the agent must read unset (nil), never 0,
// because 0 means "send at once" and unset means OR-73's 1 min.
func TestAnAbsentPushIntervalDecodesToUnset(t *testing.T) {
	out, err := GatewayFromPB(&pb.GatewayToAgent{Msg: &pb.GatewayToAgent_SessionAccept{SessionAccept: &pb.SessionAccept{
		Limits: &pb.Limits{ReportInterval: dur(time.Minute)}}}})
	if err != nil {
		t.Fatal(err)
	}
	if l := out.(msg.SessionAccept).Limits; l.TelemetryPushInterval != nil || l.ReportInterval != time.Minute {
		t.Fatalf("limits %+v", l)
	}
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `go test ./internal/wire/codec/ -run 'PushInterval' 2>&1 | head`
Expected: build failure — `unknown field TelemetryPushInterval in struct literal of type msg.Limits`.

- [ ] **Step 3: Append the field to the contract**

In `contracts/agent/v1/agent_gateway.proto`, `message Limits` becomes:

```proto
message Limits {
  uint64 max_message_bytes = 1;
  uint64 max_batch_bytes = 2;
  uint32 initial_credits = 3;
  google.protobuf.Duration heartbeat_interval = 4;
  google.protobuf.Duration keepalive_interval = 5;
  google.protobuf.Duration skew_tolerance = 6;
  google.protobuf.Duration report_interval = 7;
  // AG-D30 (go-agent plan 008). The telemetry push interval for this session: stored batches are sent at each tick.
  // Unset: the agent's default, OR-73's 1 min (AGW-D27 item 4). Set to 0: each batch is sent as soon as it is written.
  // Valid: 0, or at least 1 s and below the agent's --telemetry-buffer-age; any other value is refused and the default applies.
  google.protobuf.Duration telemetry_push_interval = 8;
}
```

- [ ] **Step 4: Install the pinned gRPC generator and regenerate**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT/go-agent"
GOBIN="$PWD/bin/tools" go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1
export PATH="$PWD/bin/tools:$HOME/go/bin:$PATH"
protoc-gen-go-grpc --version     # expect: protoc-gen-go-grpc 1.5.1
protoc-gen-go --version          # expect: protoc-gen-go v1.36.12
buf --version                    # expect: 1.73.0
make proto
git status --short ..            # expect: the .proto and internal/wire/pb/agent_gateway.pb.go only
git diff --stat -- internal/wire/pb/agent_gateway_grpc.pb.go   # expect: no output
```

If `agent_gateway_grpc.pb.go` changed, the wrong generator ran: `git checkout -- internal/wire/pb/agent_gateway_grpc.pb.go`, fix `PATH`, and run `make proto` again.

- [ ] **Step 5: Add the field to `msg.Limits`**

In `go-agent/internal/msg/msg.go`, `type Limits` becomes:

```go
type Limits struct {
	MaxMessageBytes   uint64
	MaxBatchBytes     uint64
	InitialCredits    uint32
	HeartbeatInterval time.Duration
	KeepaliveInterval time.Duration
	SkewTolerance     time.Duration
	ReportInterval    time.Duration
	// TelemetryPushInterval is Limits.telemetry_push_interval (AG-D30): nil when
	// the DP sent none (the agent's default, OR-73's 1 min); 0 sends each batch
	// as soon as it is written.
	TelemetryPushInterval *time.Duration
}
```

- [ ] **Step 6: Map it in the codec**

In `go-agent/internal/wire/codec/codec.go`, `GatewayToPB`'s `msg.SessionAccept` case, the `Limits:` literal gains `TelemetryPushInterval: durPtr(v.Limits.TelemetryPushInterval)`:

```go
			Limits: &pb.Limits{MaxMessageBytes: v.Limits.MaxMessageBytes, MaxBatchBytes: v.Limits.MaxBatchBytes, InitialCredits: v.Limits.InitialCredits,
				HeartbeatInterval: dur(v.Limits.HeartbeatInterval), KeepaliveInterval: dur(v.Limits.KeepaliveInterval), SkewTolerance: dur(v.Limits.SkewTolerance), ReportInterval: dur(v.Limits.ReportInterval),
				TelemetryPushInterval: durPtr(v.Limits.TelemetryPushInterval)},
```

and `GatewayFromPB`'s `*pb.GatewayToAgent_SessionAccept` case gains `TelemetryPushInterval: durPtrFrom(l.GetTelemetryPushInterval())`:

```go
			Limits: msg.Limits{MaxMessageBytes: l.GetMaxMessageBytes(), MaxBatchBytes: l.GetMaxBatchBytes(), InitialCredits: l.GetInitialCredits(),
				HeartbeatInterval: durFrom(l.GetHeartbeatInterval()), KeepaliveInterval: durFrom(l.GetKeepaliveInterval()), SkewTolerance: durFrom(l.GetSkewTolerance()), ReportInterval: durFrom(l.GetReportInterval()),
				TelemetryPushInterval: durPtrFrom(l.GetTelemetryPushInterval())},
```

`durPtr` and `durPtrFrom` already exist (codec.go ≈ lines 158 and 164; `SessionReject.RetryAfter` uses them) and keep presence: nil ↔ no field.

- [ ] **Step 7: Run the codec tests and the whole module**

Run: `go test ./internal/wire/... && go vet ./... && go test ./...`
Expected: all `ok`.

- [ ] **Step 8: Grow the contract baseline and run the gate**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT/contracts"
python3 agent/v1/check_proto_append_only.py                 # expect: ok — an addition passes even before the baseline grows
python3 agent/v1/check_proto_append_only.py --write-baseline
git diff --stat -- agent/v1/baseline.json                   # expect: a few lines added, none removed
python3 agent/v1/check_proto_append_only.py
python3 agent/v1/check_proto_append_only.py --self-test
BASELINE_BASE_REF=UAT python3 agent/v1/check_proto_append_only.py --superset
```

Expected: every command passes. `git diff -- agent/v1/baseline.json` shows only `telemetry_push_interval` under `Limits` added. `BASELINE_BASE_REF=UAT` is needed because this repository has no `origin/UAT` (spec §2).

- [ ] **Step 9: Commit**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT"
git add contracts/agent/v1/agent_gateway.proto contracts/agent/v1/baseline.json go-agent/internal/wire/pb/agent_gateway.pb.go \
  go-agent/internal/msg/msg.go go-agent/internal/wire/codec/codec.go go-agent/internal/wire/codec/codec_test.go
git commit -m "contracts, go-agent: Limits.telemetry_push_interval (field 8, AG-D30)

Appended to contracts/agent/v1; baseline grown with --write-baseline.
msg.Limits carries it as *time.Duration so unset (OR-73's 1 min) and 0
(send at once) stay distinct through the codec.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `cadence.Resolve`

**Files:**
- Create: `go-agent/internal/telemetry/cadence/cadence.go`
- Test: `go-agent/internal/telemetry/cadence/cadence_test.go`

**Interfaces:**
- Consumes: `msg.Limits.TelemetryPushInterval` (Task 1).
- Produces:
  - `const DefaultPush = time.Minute`, `const MinPush = time.Second`
  - `type Ignored struct { Value time.Duration; Reason string; Refused bool }`
  - `func Resolve(l msg.Limits, bufferAge time.Duration) (push time.Duration, ignored *Ignored)` — `push == 0` means send at once; `ignored` is nil when there is nothing to log.

- [ ] **Step 1: Write the failing test**

Create `go-agent/internal/telemetry/cadence/cadence_test.go`:

```go
package cadence

import (
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func d(v time.Duration) *time.Duration { return &v }

func TestResolve(t *testing.T) {
	const age = time.Hour
	refused := func(v time.Duration, reason string) *Ignored { return &Ignored{Value: v, Reason: reason, Refused: true} }
	noRoom := &Ignored{Reason: "default not below the buffer age cap"}
	for _, tc := range []struct {
		name string
		push *time.Duration
		age  time.Duration
		want time.Duration
		ig   *Ignored
	}{
		{"unset: OR-73's default", nil, age, time.Minute, nil},
		{"zero: at once", d(0), age, 0, nil},
		{"valid", d(30 * time.Second), age, 30 * time.Second, nil},
		{"at the floor", d(time.Second), age, time.Second, nil},
		{"just below the age cap", d(age - time.Nanosecond), age, age - time.Nanosecond, nil},
		// Review Focus 1: a gateway that mis-encodes 60 s as 60 ns
		{"nanoseconds", d(60 * time.Nanosecond), age, time.Minute, refused(60*time.Nanosecond, "below 1s")},
		{"just below the floor", d(999 * time.Millisecond), age, time.Minute, refused(999*time.Millisecond, "below 1s")},
		{"negative", d(-time.Second), age, time.Minute, refused(-time.Second, "negative")},
		{"equal to the age cap", d(age), age, time.Minute, refused(age, "not below the buffer age cap")},
		{"beyond the age cap", d(2 * age), age, time.Minute, refused(2*age, "not below the buffer age cap")},
		// Review Focus 2: an age cap of 1 min or less leaves no room for the default
		{"unset, age cap 1 min", nil, time.Minute, 0, noRoom},
		{"unset, age cap 30 s", nil, 30 * time.Second, 0, noRoom},
		{"valid, age cap 30 s", d(10 * time.Second), 30 * time.Second, 10 * time.Second, nil},
		{"refused, age cap 30 s", d(time.Hour), 30 * time.Second, 0, refused(time.Hour, "not below the buffer age cap")},
		// No age cap (as buffer.Options reads 0): no upper bound
		{"unset, no age cap", nil, 0, time.Minute, nil},
		{"two days, no age cap", d(48 * time.Hour), 0, 48 * time.Hour, nil},
	} {
		got, ig := Resolve(msg.Limits{ReportInterval: time.Minute, TelemetryPushInterval: tc.push}, tc.age)
		if got != tc.want || !reflect.DeepEqual(ig, tc.ig) {
			t.Errorf("%s: got %v %+v, want %v %+v", tc.name, got, ig, tc.want, tc.ig)
		}
	}
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `go test ./internal/telemetry/cadence/ 2>&1 | head -5`
Expected: build failure — `undefined: Resolve`, `undefined: Ignored`.

- [ ] **Step 3: Write the implementation**

Create `go-agent/internal/telemetry/cadence/cadence.go`:

```go
// Package cadence resolves the telemetry push interval a session's limits set
// (AG-D30). The sender resolves it inside its SessionUp; the pipeline resolves
// the same value to log, count and export it.
package cadence

import (
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// DefaultPush is OR-73's push interval (AGW-D27 item 4), used when the DP
// sends none or sends one the agent refuses.
const DefaultPush = time.Minute

// MinPush is the floor for a DP-set push interval: a faster ticker only spends
// CPU (plan 007's final review measured about one core at 1 ns).
const MinPush = time.Second

// Ignored is what Resolve wants logged. Refused means a DP value was refused
// (and is counted); otherwise only the default could not apply.
type Ignored struct {
	Value   time.Duration // the DP's value; 0 when it sent none
	Reason  string
	Refused bool
}

// Resolve returns the push interval for a session (spec §3): 0 sends each
// batch as soon as it is written. bufferAge ≤ 0 means no age cap, as in
// buffer.Options.
func Resolve(l msg.Limits, bufferAge time.Duration) (time.Duration, *Ignored) {
	capped := func(v time.Duration) bool { return bufferAge > 0 && v >= bufferAge }
	fallback := func(ig *Ignored) (time.Duration, *Ignored) {
		if capped(DefaultPush) { // the default would expire every batch before it was due
			if ig == nil {
				ig = &Ignored{Reason: "default not below the buffer age cap"}
			}
			return 0, ig
		}
		return DefaultPush, ig
	}
	p := l.TelemetryPushInterval
	switch {
	case p == nil:
		return fallback(nil)
	case *p == 0:
		return 0, nil
	case *p < 0:
		return fallback(&Ignored{Value: *p, Reason: "negative", Refused: true})
	case *p < MinPush:
		return fallback(&Ignored{Value: *p, Reason: "below 1s", Refused: true})
	case capped(*p):
		return fallback(&Ignored{Value: *p, Reason: "not below the buffer age cap", Refused: true})
	}
	return *p, nil
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `go test ./internal/telemetry/cadence/ -v 2>&1 | tail -3`
Expected: `ok`.

- [ ] **Step 5: Commit**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT"
git add go-agent/internal/telemetry/cadence/
git commit -m "go-agent: cadence.Resolve — the session's push interval, OR-73's 1 min by default (AG-D30)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The sender — hold batches until the push tick

**Files:**
- Modify: `go-agent/internal/telemetry/send/reducer.go` (State ≈ line 36; events ≈ line 60–80; effects ≈ 95–120; `SessionUp` case ≈ 166; the `Wake` case ≈ 240; `selectSends` ≈ 265–285)
- Modify: `go-agent/internal/telemetry/send/runner.go` (Runner struct ≈ 16; `Run` ≈ 51; the select loop; `exec`'s `ArmRetry` case ≈ 218)
- Test: `go-agent/internal/telemetry/send/reducer_test.go`, `runner_test.go`

**Interfaces:**
- Consumes: `cadence.Resolve`, `cadence.DefaultPush` (Task 2); `msg.Limits.TelemetryPushInterval` (Task 1).
- Produces: `State.PushInterval`, `State.DueBy`, `State.BufferAge`; event `PushDue{}`; effect `ArmPush{Every time.Duration}`; `Runner.BufferAge time.Duration` (Task 4 sets it).

- [ ] **Step 1: Give the existing fixtures an explicit push 0**

The existing reducer and runner tests describe immediate sending; under `AG-D30` a session without the field pushes every minute. Their fixtures now say what `fake-gateway` sends by default, an explicit `0`, so the tests keep their meaning.

In `reducer_test.go`, replace line 22:

```go
var limits = msg.Limits{InitialCredits: 2, MaxBatchBytes: 65536, ReportInterval: time.Minute}
```

with:

```go
// durp is a *time.Duration literal.
func durp(d time.Duration) *time.Duration { return &d }

// limits pushes at once (an explicit 0, fake-gateway's default), as every test
// written before AG-D30 assumed.
var limits = msg.Limits{InitialCredits: 2, MaxBatchBytes: 65536, ReportInterval: time.Minute, TelemetryPushInterval: durp(0)}
```

In `runner_test.go`, `session` (≈ line 56) becomes:

```go
func session(open func(context.Context) (view.Stream, error)) *view.SessionInfo {
	return &view.SessionInfo{ID: "s1", Limits: msg.Limits{InitialCredits: 2, MaxBatchBytes: 65536, ReportInterval: time.Minute, TelemetryPushInterval: durp(0)}, Open: open}
}
```

Run: `go test ./internal/telemetry/send/` — expected `ok` (the reducer does not read the field yet).

- [ ] **Step 2: Write the failing reducer tests**

Append to `reducer_test.go`:

```go
func withPush(push time.Duration) msg.Limits {
	l := limits
	l.TelemetryPushInterval = durp(push)
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
	// Review Focus 3: the same value again re-arms nothing
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

// AG-D30: today's Java gateway sends no field; the agent pushes every minute.
func TestAnUnsetPushIntervalIsOneMinute(t *testing.T) {
	s := NewState()
	s.BufferAge = time.Hour
	l := limits
	l.TelemetryPushInterval = nil
	s, effs := Reduce(s, SessionUp{ID: "s1", Limits: l}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}, ArmPush{Every: time.Minute}}) || s.PushInterval != time.Minute {
		t.Fatalf("effects %+v push %v", effs, s.PushInterval)
	}
}

func TestARefusedPushIntervalFallsBackToTheDefault(t *testing.T) {
	s := NewState()
	s.BufferAge = time.Hour
	s, effs := Reduce(s, SessionUp{ID: "s1", Limits: withPush(time.Hour)}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}, ArmPush{Every: time.Minute}}) || s.PushInterval != time.Minute {
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

// Batches stored while push was immediate were already due.
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

func TestTurningThePushIntervalOffReleasesEverythingStored(t *testing.T) {
	s := upWithPush(0, time.Minute)
	s, _ = Reduce(s, SessionDown{}, env(t0.Add(30*time.Second)))
	s, _ = Reduce(s, SessionUp{ID: "s2", Limits: limits}, env(t0.Add(40*time.Second)))
	_, effs := Reduce(s, StreamOpened{}, env(t0.Add(41*time.Second), head("a", 1, 20), head("b", 1, 35)))
	if !reflect.DeepEqual(sends(effs), []Send{{"a", 1}, {"b", 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

// The wall clock stepped back, so a stored batch claims a produced_at after the
// latest tick. It waits, bounded by the step, and goes at the first tick after
// the clock passes it (spec §4.3).
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

Check `reducer_test.go`'s imports include `buffer` (`TestDropsDoNotWaitForThePushTick` uses `buffer.ReasonOversize`); add `"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"` if `go vet` says it is missing.

- [ ] **Step 3: Write the failing runner tests**

Append to `runner_test.go`:

```go
func appendAt(t *testing.T, buf *buffer.Buffer, at time.Time) {
	t.Helper()
	if _, err := buf.Append("binding-1", at, []buffer.Sample{{Name: "cache.hit_rate", Type: "gauge", TS: time.Unix(0, 0).UTC(), Value: 0.93, ReplicaRef: "java1", Incarnation: "c1"}}); err != nil {
		t.Fatal(err)
	}
}

func pushSession(push time.Duration, open func(context.Context) (view.Stream, error)) *view.SessionInfo {
	s := session(open)
	s.Limits.TelemetryPushInterval = durp(push)
	return s
}

func TestRunnerHoldsANewBatchUntilThePushTick(t *testing.T) {
	hub := view.NewHub()
	_, buf, _ := runnerWith(t, hub, t.TempDir(), func(r *Runner) { r.BufferAge = time.Hour })
	s := newFakeStream()
	opened := make(chan struct{})
	var once sync.Once
	hub.Publish(view.Snapshot{Session: pushSession(time.Second, func(context.Context) (view.Stream, error) {
		once.Do(func() { close(opened) })
		return s, nil
	}), Bindings: map[string]bool{"binding-1": true}, IDs: registered})
	<-opened // the runner has armed the push ticker: appending now is after its start
	appendOne(t, buf)
	select {
	case b := <-s.sent:
		t.Fatalf("seq %d sent before the push tick", b.BatchSeq)
	case <-time.After(500 * time.Millisecond):
	}
	receive(t, s) // at the tick, about 1 s after the session
}

// Review Focus 4: a backlog stored before a restart goes on the first session,
// though the first push tick is an hour away.
func TestRunnerSendsWhatWasStoredBeforeItStartedAtOnce(t *testing.T) {
	hub := view.NewHub()
	runnerWith(t, hub, t.TempDir(), func(r *Runner) {
		r.BufferAge = 2 * time.Hour
		appendAt(t, r.Buf, time.Now().Add(-time.Second))
	})
	s := newFakeStream()
	hub.Publish(view.Snapshot{Session: pushSession(time.Hour, func(context.Context) (view.Stream, error) { return s, nil }),
		Bindings: map[string]bool{"binding-1": true}, IDs: registered})
	receive(t, s)
}
```

Add `"sync"` to `runner_test.go`'s imports if it is not there.

- [ ] **Step 4: Run them to see them fail**

Run: `go test ./internal/telemetry/send/ 2>&1 | head -5`
Expected: build failure — `undefined: PushDue`, `undefined: ArmPush`, `r.BufferAge undefined`.

- [ ] **Step 5: Implement the reducer**

In `reducer.go`:

1. Imports gain `"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"`.
2. `State` gains three fields after `RetryAt`:

```go
	RetryAt        map[string]time.Time
	PushInterval   time.Duration // AG-D30: 0 sends each batch at once; otherwise batches wait for a push tick
	DueBy          time.Time     // a batch produced at or before it is due
	BufferAge      time.Duration // the age cap a push interval must stay below; set once by the runner
}
```

3. In the events block, after `Wake       struct{}                // the buffer, the view or a timer changed`:

```go
	PushDue    struct{}                // the push ticker fired (AG-D30)
```

   and after `func (Wake) event()         {}`:

```go
func (PushDue) event()      {}
```

4. In the effects block, after `ArmRetry struct{ At time.Time }`:

```go
	ArmPush  struct{ Every time.Duration } // reset the push ticker; 0 stops it
```

   and after `func (ArmRetry) effect()    {}`:

```go
func (ArmPush) effect()     {}
```

5. In `Reduce`'s `SessionUp` case, after `effs = append(effs, OpenStream{})`:

```go
		// Resolved here, from the limits this event carries, not delivered by a
		// separate event that would race it (spec §4.1).
		if push, _ := cadence.Resolve(e.Limits, s.BufferAge); push != s.PushInterval {
			if s.PushInterval == 0 {
				s.DueBy = env.Now // stored under immediate push: all of it is already due
			}
			s.PushInterval = push
			effs = append(effs, ArmPush{Every: push})
		}
```

6. Before `case Wake:` in `Reduce`:

```go
	case PushDue:
		s.DueBy = env.Now
```

7. In `selectSends`, between the `case !h.IDsKnown:` branch (which ends `continue`) and `case s.Credits == 0:`:

```go
		case s.PushInterval > 0 && h.ProducedAt.After(s.DueBy):
			continue // stored, not yet due (AG-D30)
```

- [ ] **Step 6: Implement the runner**

In `runner.go`:

1. `Runner` gains, after `Metrics *tmetrics.Metrics` (gofmt realigns the block):

```go
	BufferAge time.Duration // --telemetry-buffer-age, the push interval's upper bound (AG-D30)
```

   and, after `retry   *time.Timer`:

```go
	push    *time.Ticker // fires PushDue; stopped while the push interval is 0
```

2. `Run` begins:

```go
func (r *Runner) Run(ctx context.Context) {
	r.state = NewState()
	// Everything stored before the agent started is already due (spec §4.3).
	r.state.DueBy, r.state.BufferAge = r.Now(), r.BufferAge
	r.in = make(chan any, 64)
	r.retry = time.NewTimer(time.Hour)
	r.retry.Stop()
	r.push = time.NewTicker(time.Hour)
	r.push.Stop()
	defer r.push.Stop()
```

3. The `select` gains, after `case <-r.retry.C: r.step(ctx, Wake{})`:

```go
		case <-r.push.C:
			r.step(ctx, PushDue{})
```

4. `exec`, after the `ArmRetry` case:

```go
	case ArmPush:
		if e.Every > 0 {
			r.push.Reset(e.Every) // Reset panics on a non-positive duration
		} else {
			r.push.Stop()
		}
```

- [ ] **Step 7: Run the sender's tests**

Run: `go test ./internal/telemetry/send/ -v 2>&1 | grep -E '^(--- FAIL|ok|FAIL)' ; go test -race ./internal/telemetry/send/`
Expected: `ok` twice, no `--- FAIL`. Every test that existed before Step 1 passes unchanged.

- [ ] **Step 8: Commit**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT"
git add go-agent/internal/telemetry/send/
git commit -m "go-agent: the sender holds stored batches until the push tick (AG-D30)

Ported from solution-manager-main's plan 007 sender. The interval is
resolved inside SessionUp; an unset field is OR-73's 1 min. The old
fixtures now carry fake-gateway's explicit 0, so their meaning is kept.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Report the push interval — gauge, counter, log

**Files:**
- Modify: `go-agent/internal/telemetry/tmetrics/tmetrics.go` (struct ≈ line 14–24; `New` ≈ 30–46; after `SetCredits` ≈ 121)
- Modify: `go-agent/internal/telemetry/pipeline.go` (imports; `New`; `Run`; new `watchPush`)
- Create: `go-agent/internal/telemetry/pipeline_test.go`

**Interfaces:**
- Consumes: `cadence.Resolve`, `cadence.Ignored`, `cadence.DefaultPush` (Task 2); `send.Runner.BufferAge` (Task 3).
- Produces: `tmetrics.Metrics.PushInterval prometheus.Gauge`, `.LimitsIgnored *prometheus.CounterVec`, `(*Metrics).SetPushInterval(time.Duration)`, `(*Metrics).LimitIgnored(limit string)`; `(*Pipeline).watchPush(ctx)`.

- [ ] **Step 1: Write the failing test**

Create `go-agent/internal/telemetry/pipeline_test.go`:

```go
package telemetry

import (
	"bytes"
	"context"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/view"
)

type logBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *logBuf) Write(p []byte) (int, error) { l.mu.Lock(); defer l.mu.Unlock(); return l.b.Write(p) }
func (l *logBuf) String() string              { l.mu.Lock(); defer l.mu.Unlock(); return l.b.String() }

// watching builds a pipeline with the given age cap and runs only its push watcher.
func watching(t *testing.T, age time.Duration) (*Pipeline, *view.Hub, *logBuf) {
	t.Helper()
	hub, logs := view.NewHub(), &logBuf{}
	p, err := New(Config{Store: store.MustOpen(t.TempDir()), Declarations: declare.NewSet(), BufferAge: age, View: hub,
		Log: slog.New(slog.NewTextHandler(logs, nil))})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { p.watchPush(ctx); close(done) }()
	t.Cleanup(func() { cancel(); <-done })
	return p, hub, logs
}

func sessionWith(id string, push *time.Duration) view.Snapshot {
	return view.Snapshot{Session: &view.SessionInfo{ID: id, Limits: msg.Limits{TelemetryPushInterval: push}}}
}

func durp(d time.Duration) *time.Duration { return &d }

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

func pushGauge(p *Pipeline) float64 { return testutil.ToFloat64(p.metrics.PushInterval) }
func refusals(p *Pipeline) float64  { return testutil.ToFloat64(p.metrics.LimitsIgnored.WithLabelValues("push")) }

func TestANewSessionReportsItsPushIntervalAndADropKeepsIt(t *testing.T) {
	p, hub, _ := watching(t, time.Hour)
	if got := pushGauge(p); got != 60 {
		t.Fatalf("before any session the gauge is %v, want the default 60", got)
	}
	hub.Publish(sessionWith("s1", durp(30*time.Second)))
	eventually(t, "the DP's 30 s", func() bool { return pushGauge(p) == 30 })
	hub.Publish(view.Snapshot{}) // the session drops
	time.Sleep(50 * time.Millisecond)
	if got := pushGauge(p); got != 30 {
		t.Fatalf("a session drop changed the gauge to %v", got)
	}
	hub.Publish(sessionWith("s2", nil)) // today's gateway: no field
	eventually(t, "the default back", func() bool { return pushGauge(p) == 60 })
	hub.Publish(sessionWith("s3", durp(0)))
	eventually(t, "at once", func() bool { return pushGauge(p) == 0 })
}

func TestARefusedPushIntervalIsCountedOncePerSessionAndLogged(t *testing.T) {
	p, hub, logs := watching(t, time.Hour)
	hub.Publish(sessionWith("s1", durp(2*time.Hour)))
	eventually(t, "the refusal counted", func() bool { return refusals(p) == 1 })
	hub.Publish(sessionWith("s1", durp(2*time.Hour))) // the same session again
	time.Sleep(50 * time.Millisecond)
	if got := refusals(p); got != 1 {
		t.Fatalf("counted %v times for one session", got)
	}
	if got := pushGauge(p); got != 60 {
		t.Fatalf("gauge %v, want the default 60", got)
	}
	for _, want := range []string{`msg="telemetry: DP push interval refused; using the default"`, `value=2h0m0s`, `reason="not below the buffer age cap"`} {
		if !strings.Contains(logs.String(), want) {
			t.Errorf("log lacks %s:\n%s", want, logs.String())
		}
	}
}

// Review Focus 2.
func TestAnAgeCapAtOrBelowTheDefaultSendsAtOnceWithoutCounting(t *testing.T) {
	p, hub, logs := watching(t, 30*time.Second)
	if got := pushGauge(p); got != 0 {
		t.Fatalf("before any session the gauge is %v, want 0", got)
	}
	hub.Publish(sessionWith("s1", nil))
	eventually(t, "the warning", func() bool {
		return strings.Contains(logs.String(), `msg="telemetry: the default push interval is not below the buffer age cap; sending each batch at once"`)
	})
	if got := refusals(p); got != 0 {
		t.Fatalf("counted %v refusals; no DP value was refused", got)
	}
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `go test ./internal/telemetry/ 2>&1 | head -5`
Expected: build failure — `p.watchPush undefined`, `p.metrics.PushInterval undefined`.

- [ ] **Step 3: Add the metrics**

In `tmetrics.go`:

1. The `Metrics` struct gains, after `Credits         prometheus.Gauge`:

```go
	PushInterval    prometheus.Gauge
	LimitsIgnored   *prometheus.CounterVec
```

2. In `New`, after the `Credits:` entry:

```go
		PushInterval:    prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_telemetry_push_interval_seconds", Help: "The push interval in use (AG-D30); 0 sends each batch as soon as it is written."}),
		LimitsIgnored:   counterVec("sm_agent_telemetry_limit_ignored_total", "DP-set telemetry limits the agent refused, by limit (AG-D30).", "limit"),
```

   and the `m.all` list gains `m.PushInterval, m.LimitsIgnored` after `m.Credits`.

3. After `SetCredits`:

```go
// SetPushInterval exports the push interval in use (AG-D30).
func (m *Metrics) SetPushInterval(push time.Duration) {
	if m != nil {
		m.PushInterval.Set(push.Seconds())
	}
}

func (m *Metrics) LimitIgnored(limit string) {
	if m != nil {
		m.LimitsIgnored.WithLabelValues(limit).Inc()
	}
}
```

- [ ] **Step 4: Add the watcher to the pipeline**

In `pipeline.go`:

1. Imports gain `"github.com/solace/solution-manager/go-agent/internal/msg"` and `"github.com/solace/solution-manager/go-agent/internal/telemetry/cadence"`.
2. `New`'s last line becomes:

```go
	push, _ := cadence.Resolve(msg.Limits{}, cfg.BufferAge) // what applies until the first session says otherwise
	m.SetPushInterval(push)
	return &Pipeline{cfg: cfg, buf: buf, metrics: m}, nil
```

3. In `Run`, the runner gains `BufferAge`, and the watcher starts beside it:

```go
	r := &send.Runner{Buf: p.buf, View: p.cfg.View, NewID: p.cfg.NewID, Now: time.Now, Jitter: p.cfg.Jitter, Log: p.cfg.Log, Metrics: p.metrics, BufferAge: p.cfg.BufferAge}
	wg.Go(func() { r.Run(ctx) })
	wg.Go(func() { p.watchPush(ctx) })
```

4. At the end of the file:

```go
// watchPush reports the push interval each new session resolves to (AG-D30):
// a log line and a count for a refused value, and the gauge. The sender
// resolves the same value inside its own SessionUp; this only reports it. A
// session drop changes nothing (spec §3.1).
func (p *Pipeline) watchPush(ctx context.Context) {
	var seen string
	for {
		ch := p.cfg.View.Changed()
		if s := p.cfg.View.Current().Session; s != nil && s.ID != seen {
			seen = s.ID
			push, ig := cadence.Resolve(s.Limits, p.cfg.BufferAge)
			switch {
			case ig != nil && ig.Refused:
				p.cfg.Log.Warn("telemetry: DP push interval refused; using the default",
					"limit", "push", "value", ig.Value.String(), "reason", ig.Reason, "push", push.String())
				p.metrics.LimitIgnored("push")
			case ig != nil:
				p.cfg.Log.Warn("telemetry: the default push interval is not below the buffer age cap; sending each batch at once",
					"default", cadence.DefaultPush.String(), "buffer_age", p.cfg.BufferAge.String())
			}
			p.metrics.SetPushInterval(push)
		}
		select {
		case <-ctx.Done():
			return
		case <-ch:
		}
	}
}
```

- [ ] **Step 5: Run the pipeline tests and the module**

Run: `go test -race ./internal/telemetry/... && go vet ./... && go test ./...`
Expected: all `ok`.

- [ ] **Step 6: Commit**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT"
git add go-agent/internal/telemetry/tmetrics/tmetrics.go go-agent/internal/telemetry/pipeline.go go-agent/internal/telemetry/pipeline_test.go
git commit -m "go-agent: report the push interval — gauge, refusal counter, one log line per session (AG-D30)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `fake-gateway` — `PushSpec`, the scenario field, `set push`, the time prefix

**Files:**
- Modify: `go-agent/internal/fakegw/scenario.go` (`LimitsSpec` ≈ line 23; new `PushSpec`)
- Modify: `go-agent/internal/fakegw/server.go` (`Server` struct ≈ 73; `Start` ≈ 101)
- Modify: `go-agent/internal/fakegw/telemetry.go` (`printf` ≈ 120; new `SetPushInterval`, `PushInterval`)
- Modify: `go-agent/internal/fakegw/session.go` (`limits()` ≈ 141)
- Modify: `go-agent/internal/fakegw/console.go` (`consoleUsage`; `line`)
- Test: `go-agent/internal/fakegw/telemetry_test.go`, `console_test.go`

**Interfaces:**
- Consumes: `msg.Limits.TelemetryPushInterval` (Task 1).
- Produces (Task 6 uses them):
  - `type PushSpec struct { Unset bool; Every time.Duration }` — zero value = an explicit 0
  - `func ParsePushSpec(s string) (PushSpec, error)` — `"unset"` or a Go duration
  - `func (p PushSpec) Wire() *time.Duration`, `func (p PushSpec) String() string`
  - `LimitsSpec.TelemetryPushInterval PushSpec` (YAML `telemetry_push_interval`)
  - `func (s *Server) SetPushInterval(p PushSpec)`, `func (s *Server) PushInterval() PushSpec`
  - console line `set push <duration>|unset` → `next session: push=<value> (disconnect to apply)`

- [ ] **Step 1: Write the failing tests**

Append to `telemetry_test.go` (add `"os"`, `"path/filepath"`, `"regexp"` and `"time"` to its imports if missing):

```go
func TestScenarioPushIntervalParses(t *testing.T) {
	for _, tc := range []struct {
		yaml string
		want PushSpec
		err  string
	}{
		{"{}\n", PushSpec{}, ""}, // omitted: fake-gateway's default, an explicit 0
		{"limits: {telemetry_push_interval: 1m}\n", PushSpec{Every: time.Minute}, ""},
		{"limits: {telemetry_push_interval: 0}\n", PushSpec{}, ""},
		{"limits: {telemetry_push_interval: unset}\n", PushSpec{Unset: true}, ""},
		// Review Focus 5: a bare number has no unit
		{"limits: {telemetry_push_interval: 60}\n", PushSpec{}, `telemetry_push_interval: time: missing unit in duration "60"`},
	} {
		path := filepath.Join(t.TempDir(), "sc.yaml")
		if err := os.WriteFile(path, []byte(tc.yaml), 0o600); err != nil {
			t.Fatal(err)
		}
		sc, err := LoadScenario(path)
		if tc.err != "" {
			if err == nil || !strings.Contains(err.Error(), tc.err) {
				t.Errorf("%q: error %v, want one containing %q", tc.yaml, err, tc.err)
			}
			continue
		}
		if err != nil {
			t.Fatalf("%q: %v", tc.yaml, err)
		}
		if sc.Limits.TelemetryPushInterval != tc.want || sc.Limits.ReportInterval != time.Minute {
			t.Errorf("%q: limits %+v", tc.yaml, sc.Limits)
		}
	}
}

func TestTheNextSessionAcceptCarriesThePushInterval(t *testing.T) {
	zero, minute := time.Duration(0), time.Minute
	for _, tc := range []struct {
		name          string
		scenario, set *PushSpec
		want          *time.Duration
	}{
		{"the default: an explicit 0", nil, nil, &zero},
		{"unset in the scenario: no field", &PushSpec{Unset: true}, nil, nil},
		{"set from the console", nil, &PushSpec{Every: time.Minute}, &minute},
	} {
		s, _ := telemetryServer(t, func(sc *Scenario) {
			if tc.scenario != nil {
				sc.Limits.TelemetryPushInterval = *tc.scenario
			}
		})
		if tc.set != nil {
			s.SetPushInterval(*tc.set)
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
		if !ok {
			t.Fatalf("%s: got %T", tc.name, m)
		}
		if got := a.Limits.TelemetryPushInterval; (got == nil) != (tc.want == nil) || (got != nil && *got != *tc.want) {
			t.Fatalf("%s: push %v", tc.name, got)
		}
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

Append to `console_test.go` (add `"time"` to its imports):

```go
func TestConsoleSetPushStoresItForTheNextSession(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	for _, tc := range []struct{ line, want string }{
		{"set push 1m", "next session: push=1m0s (disconnect to apply)"},
		{"set push 0", "next session: push=0s (disconnect to apply)"},
		{"set push unset", "next session: push=unset (disconnect to apply)"},
		// a malformed line prints why and changes nothing
		{"set push", "usage: set push <duration>|unset"},
		{"set push abc", `set: time: invalid duration "abc"`},
		{"set pull 5s", "usage: set push <duration>|unset"}, // pull is the DP's declarations (AG-D28)
		{"set foo abc", "usage: set push <duration>|unset"}, // the verb is checked before the value
	} {
		var out bytes.Buffer
		s.Console(strings.NewReader(tc.line+"\n"), &out)
		if !strings.Contains(out.String(), tc.want) {
			t.Errorf("%q: want %q, got %q", tc.line, tc.want, out.String())
		}
	}
	if got := s.PushInterval(); got != (PushSpec{Unset: true}) {
		t.Fatalf("stored %+v, want unset", got)
	}
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `go test ./internal/fakegw/ 2>&1 | head -5`
Expected: build failure — `undefined: PushSpec`, `s.SetPushInterval undefined`.

- [ ] **Step 3: `PushSpec` and the scenario field**

In `scenario.go`, `LimitsSpec` gains a last field:

```go
	ReportInterval    time.Duration `yaml:"report_interval"`
	// TelemetryPushInterval is what SessionAccept carries in
	// telemetry_push_interval (AG-D30). The zero value sends an explicit 0, so
	// every scenario written before AG-D30 keeps sending at once.
	TelemetryPushInterval PushSpec `yaml:"telemetry_push_interval"`
}
```

and, after `LimitsSpec`:

```go
// PushSpec is the telemetry_push_interval fake-gateway sends: a duration (0
// included, the zero value) or Unset — no field, which is what today's Java
// gateway sends and the agent reads as OR-73's 1 min (AG-D30).
type PushSpec struct {
	Unset bool
	Every time.Duration
}

// ParsePushSpec reads "unset" or a Go duration ("1m", "0").
func ParsePushSpec(s string) (PushSpec, error) {
	if s == "unset" {
		return PushSpec{Unset: true}, nil
	}
	d, err := time.ParseDuration(s)
	if err != nil {
		return PushSpec{}, err
	}
	return PushSpec{Every: d}, nil
}

func (p *PushSpec) UnmarshalYAML(n *yaml.Node) error {
	v, err := ParsePushSpec(n.Value)
	if err != nil {
		return fmt.Errorf("telemetry_push_interval: %w", err)
	}
	*p = v
	return nil
}

// Wire is the field as msg.Limits carries it: nil when Unset.
func (p PushSpec) Wire() *time.Duration {
	if p.Unset {
		return nil
	}
	d := p.Every
	return &d
}

func (p PushSpec) String() string {
	if p.Unset {
		return "unset"
	}
	return p.Every.String()
}
```

- [ ] **Step 4: The server holds the next session's value**

In `server.go`, the `Server` struct gains, after `rejectNext string`:

```go
	push       PushSpec // what the next SessionAccept carries in telemetry_push_interval (AG-D30)
```

and `Start`'s `&Server{...}` literal gains, after `rejectNext: cfg.Scenario.Policy.Telemetry.RejectNext,`:

```go
		push: cfg.Scenario.Limits.TelemetryPushInterval,
```

In `telemetry.go`, `printf` prefixes the local time (add `"time"` to the imports):

```go
	// local time first, so a push burst is visible (AG-D30)
	fmt.Fprintf(s.cfg.Out, "[%s] %s\n", time.Now().Format("15:04:05"), fmt.Sprintf(format, a...))
```

replacing `fmt.Fprintf(s.cfg.Out, format+"\n", a...)`, and the file ends with:

```go
// SetPushInterval stores the telemetry_push_interval the next SessionAccept
// carries (AG-D30). The live session keeps the limits it was accepted with;
// Disconnect makes the agent take the new value. Any value is stored,
// including one the agent will refuse: this is a test double.
func (s *Server) SetPushInterval(p PushSpec) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.push = p
}

// PushInterval returns what the next SessionAccept carries.
func (s *Server) PushInterval() PushSpec {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.push
}
```

In `session.go`, `limits()` becomes:

```go
func (ss *session) limits() msg.Limits {
	l := ss.sc.Limits
	return msg.Limits{MaxMessageBytes: l.MaxMessageBytes, MaxBatchBytes: l.MaxBatchBytes, InitialCredits: l.InitialCredits,
		HeartbeatInterval: l.HeartbeatInterval, KeepaliveInterval: l.KeepaliveInterval, SkewTolerance: l.SkewTolerance, ReportInterval: l.ReportInterval,
		TelemetryPushInterval: ss.s.PushInterval().Wire()}
}
```

`limits()` is called while sending `SessionAccept` (≈ line 199), after `setLive` has returned, so `s.mu` is not held there.

- [ ] **Step 5: The console verb**

In `console.go`:

```go
const consoleUsage = "type: preflight | apply <file> | restart | stop | pause | resume | disconnect | set push <duration>|unset"
```

and in `line`, after the `disconnect` case and before the switch's closing brace:

```go
	case "set":
		if len(f) != 3 || f[1] != "push" {
			c.println("usage: set push <duration>|unset")
			return
		}
		p, err := ParsePushSpec(f[2])
		if err != nil {
			c.println("set: %v", err)
			return
		}
		c.s.SetPushInterval(p)
		c.println("next session: push=%s (disconnect to apply)", p)
		return
```

- [ ] **Step 6: Run the package and its race test**

Run: `go test -race ./internal/fakegw/... && go vet ./... && go test ./...`
Expected: all `ok`. `scenarios_test.go` still loads every file under `internal/fakegw/scenarios/` (none names the new field, so each sends an explicit 0).

- [ ] **Step 7: Commit**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT"
git add go-agent/internal/fakegw/
git commit -m "fake-gateway: telemetry_push_interval from the scenario or 'set push'; time-stamped telemetry lines (AG-D30)

The default is an explicit 0, so every existing scenario and test still
sees send-at-once; 'unset' sends no field, as today's Java gateway does.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Integration tests, mutation proofs and the full gate

**Files:**
- Test: `go-agent/internal/agent/telemetry_integration_test.go`

**Interfaces:**
- Consumes: `fakegw.PushSpec`, `(*fakegw.Server).Console`, `gw.Transcript()`, `gw.WaitFor`, `startGateway`, `startAgent`, `startTelemetryAgent`, `telemetryScenario`, `fastDecl`, `jsonTarget`, `newFakeAdapter`, `batches`, `syncBuffer`, `waitOutput`, `both` (all existing in `internal/agent` tests).

- [ ] **Step 1: Write the integration tests**

Append to `telemetry_integration_test.go` (add `"io"`, `"net"`, `"regexp"` and `"strconv"` to its imports):

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
	sc.Limits.TelemetryPushInterval = fakegw.PushSpec{Every: time.Second} // pull stays fastDecl's 100 ms
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

func TestIntegrationTelemetryPushIntervalAppliesAtNextSession(t *testing.T) {
	gw := startGateway(t, telemetryScenario()) // fake-gateway's default: an explicit 0
	startTelemetryAgent(t, gw, "", both, 1<<20)
	if !gw.WaitFor("TelemetryBatch", 5, 10*time.Second) {
		t.Fatal("no telemetry")
	}
	out := &syncBuffer{}
	gw.Console(strings.NewReader("set push 1s\n"), out)
	waitOutput(t, out, "next session: push=1s (disconnect to apply)", 5*time.Second)
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

func freeAddr(t *testing.T) string {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := lis.Addr().String()
	lis.Close()
	return addr
}

func metricValue(t *testing.T, body, name string) float64 {
	t.Helper()
	m := regexp.MustCompile(`(?m)^` + name + ` (\S+)$`).FindStringSubmatch(body)
	if m == nil {
		t.Fatalf("no %s in /metrics", name)
	}
	v, err := strconv.ParseFloat(m[1], 64)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

// AG-D30 against what today's Java gateway sends: no field. The agent pushes
// every minute, so a batch is stored, not sent, for the first seconds — read
// from the gauge and the buffer, not by waiting a minute.
func TestIntegrationTelemetryPushIntervalUnsetUsesTheDefault(t *testing.T) {
	sc := telemetryScenario()
	sc.Limits.TelemetryPushInterval = fakegw.PushSpec{Unset: true}
	gw := startGateway(t, sc)
	addr, _ := jsonTarget(t)
	ad := newFakeAdapter(t)
	ad.scrape = addr
	admin := freeAddr(t)
	startAgent(t, gw, "", func(bc *BuildConfig) {
		bc.Adapter, bc.Roles, bc.AdminAddr = ad, both, admin
		bc.Telemetry = &TelemetryConfig{Declarations: fastDecl(), BufferBytes: 1 << 20, BufferAge: time.Hour}
	})
	if !gw.WaitFor("ActualStateReport", 1, 10*time.Second) {
		t.Fatal("no actual-state report: the ids batches need were never minted")
	}
	time.Sleep(3 * time.Second) // about 30 pulls at 100 ms
	// At most one: a batch pulled in the instant before the sender reduced the
	// session's SessionUp was stored under send-at-once and is due at once
	// (spec §4.3). Every later one waits for the first tick, a minute away.
	if n := len(batches(gw)); n > 1 {
		t.Fatalf("%d batches sent within 3 s; an unset push interval is 1 min", n)
	}
	resp, err := http.Get("http://" + admin + "/metrics")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if v := metricValue(t, string(body), "sm_agent_telemetry_push_interval_seconds"); v != 60 {
		t.Fatalf("push interval gauge %v, want 60", v)
	}
	if v := metricValue(t, string(body), "sm_agent_telemetry_buffer_batches"); v < 10 {
		t.Fatalf("buffer holds %v batches after about 30 pulls; they were not stored", v)
	}
}
```

- [ ] **Step 2: Run them, then every integration test**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT/go-agent"
go test -tags integration ./internal/agent/ -run 'TelemetryPushInterval' -v 2>&1 | grep -E '^(=== RUN|--- |ok|FAIL)'
go test -tags integration ./internal/agent/ 2>&1 | tail -3
```

Expected: the three new tests `PASS`; the whole package `ok` — every existing UAT integration test (telemetry, declarations, discovery, rematerialize, drain) unchanged.

- [ ] **Step 3: Race**

```bash
go test -race ./internal/telemetry/... ./internal/fakegw/... ./internal/wire/...
go test -race -tags integration ./internal/agent/ -run 'Telemetry' 2>&1 | tail -3
```

Expected: `ok` everywhere.

- [ ] **Step 4: Mutation proofs (each must FAIL, then restore)**

Run each, record the failing test's name in Results, and restore with `git checkout -- <file>`:

| # | Mutation | Command | Must fail |
|---|---|---|---|
| M1 | delete the two lines `case s.PushInterval > 0 && h.ProducedAt.After(s.DueBy):` / `continue // stored, not yet due (AG-D30)` in `send/reducer.go` | `go test ./internal/telemetry/send/` | `TestANotYetDueHeadWaitsForThePushTick` (and others) |
| M2 | `const DefaultPush = 0` in `cadence/cadence.go` | `go test ./internal/telemetry/cadence/` | `TestResolve` (`unset: OR-73's default`) |
| M3 | delete the `case *p < MinPush:` branch (two lines) in `cadence/cadence.go` | `go test ./internal/telemetry/cadence/` | `TestResolve` (`nanoseconds`) |
| M4 | in `fakegw/scenario.go`, make `Wire()` always return `&d` (drop the `Unset` check) | `go test ./internal/fakegw/` | `TestTheNextSessionAcceptCarriesThePushInterval` |

After the four: `git status --short` shows nothing.

- [ ] **Step 5: The full gate**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT/go-agent"
make test                           # go vet + go test ./...
make build                          # bin/sm-agent and bin/fake-gateway still build
cd ../contracts
python3 agent/v1/check_proto_append_only.py && python3 agent/v1/check_proto_append_only.py --self-test \
  && BASELINE_BASE_REF=UAT python3 agent/v1/check_proto_append_only.py --superset
```

Expected: all pass. Then, **only if** `../backend/mvnw` runs and its dependencies are in `~/.m2`:

```bash
cd ../backend && ./mvnw -o -q -pl agent-gateway -am test 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` — evidence the appended field changes nothing for the gateway. If offline resolution fails, record "Java not run: <first error line>" in Results; do not go online without asking.

`make test-docker` is not run: `ScrapeAddr` and the Docker adapter are untouched.

- [ ] **Step 6: Commit**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT"
git add go-agent/internal/agent/telemetry_integration_test.go
git commit -m "go-agent: integration — push bursts, the next-session rule, and the unset 1 min default (AG-D30)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Records and documentation

**Files:**
- Modify: `docs/design/go-agent/decisions.md` (index after the `AG-D29` row ≈ line 66; body after `AG-D29`'s, before `---` and `## Changelog`; changelog row first in its table)
- Modify: `docs/design/DECISIONS.md` (the `AG-D*` row of the ratification matrix ≈ line 1251; open items table after row 47 ≈ line 1343)
- Create: `docs/requirements/REQ-015-dp-set-telemetry-push-interval.md`
- Modify: `docs/requirements/README.md` (after the `REQ-014` row ≈ line 85), `docs/requirements/traceability.md` (after the `014` row ≈ line 27)
- Modify: `go-agent/README.md` (Telemetry paragraph ≈ line 24), `go-agent/CLAUDE.md` (Status section ≈ line 40), `go-agent/RUNBOOK.md` (line 90; Telemetry §4 and §5)
- Modify: `docs/design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md` (status line)

- [ ] **Step 1: `AG-D30` in the go-agent register**

Index row, after the `AG-D29` row:

```markdown
| `AG-D30` | The DP may set the telemetry push interval per session on `Limits.telemetry_push_interval`; unset is `OR-73`'s 1 min, `0` sends each batch when written (go-agent plan 008; applies `AGW-D27` item 4) | PROPOSED |
```

Body, after `AG-D29`'s **Enforced by** paragraph and before the `---` that precedes `## Changelog`:

```markdown
## `AG-D30` — The DP may set the telemetry push interval per session; unset is `OR-73`'s 1 min

**Status: PROPOSED.** Applies `AGW-D27` item 4 ("5 s collection / 1 min push", `OR-73`, `OR-128` (8)) on the agent side, where
only the 5 s collection was built (`AG-D28`). Amends nothing; `AG-D23`'s credits and `AG-D24`'s buffer are unchanged. Spec:
[`specs/2026-10-13-go-agent-telemetry-push-interval-design.md`](specs/2026-10-13-go-agent-telemetry-push-interval-design.md);
plan: [`../../plans/go-agent/008-telemetry-push-interval.md`](../../plans/go-agent/008-telemetry-push-interval.md).

1. `SessionAccept.limits.telemetry_push_interval` (field 8, appended to `contracts/agent/v1`) sets how often the sender releases
   stored batches. **Unset** (what today's gateway sends) means `OR-73`'s **1 min**; **`0`** means each batch is sent as soon as it
   is written (what G-A1 and G-A2 measured); a value is valid when at least 1 s and below `--telemetry-buffer-age`. Anything else
   is refused — logged once per session and counted in `sm_agent_telemetry_limit_ignored_total{limit="push"}` — and the default
   applies. When the buffer age is 1 min or less the default cannot apply, and the agent sends at once and logs why.
2. The value is resolved at each new session and only then; a session drop keeps the schedule; nothing is persisted, so everything
   stored before an agent restart is due on the first session.
3. At each tick the stored batches go individually, in order, one in flight per stream (`AG-D23`); oversize and `UNREGISTERED`
   drops do not wait for a tick.
4. Pull is untouched: `TelemetryDeclarations.interval`, per binding (`AG-D28`).

**Rejected.** *A DP-set pull on `Limits`* (solution-manager-main's plan 007) — a second source for the number
`TelemetryDeclarations.interval` already carries. *Absent means send at once* — it leaves `AGW-D27` item 4 unbuilt against
today's gateway. *Merging stored batches into one at push time* — it reworks `AG-D23`/`AG-D24`'s seq, in-flight and gap
guarantees. *A local flag* — the DP is the source.

⚠️ **Flagged (`I4`), not resolved:** a DP-chosen value departs from `OR-128` (8)'s "fixed values"; the default keeps the fixed
value. Central open item 48 routes it, with the Java gateway sending the field.

**Enforced by:** `cadence_test.go` (`TestResolve`); `reducer_test.go` (the push cases); `runner_test.go`
(`TestRunnerHoldsANewBatchUntilThePushTick`, `TestRunnerSendsWhatWasStoredBeforeItStartedAtOnce`); `pipeline_test.go`;
`codec_test.go` (`TestTelemetryPushIntervalKeepsItsThreeStates`); the integration tests `TestIntegrationTelemetryPushInterval*`.
Mutation proofs M1–M4 in plan 008's Results.
```

Changelog row, as the **first** row of the changelog table:

```markdown
| 2026-10-13 | `AG-D30` added, `PROPOSED`, by go-agent plan 008: the DP may set the telemetry push interval per session; unset is `OR-73`'s 1 min. Ported from solution-manager-main's plan 007, where the idea was that tree's `AG-D26` on a DRAFT wire and also carried a pull interval; neither that id nor the pull half is carried (this register's `AG-D26` is discovery). No earlier row edited (`I3`). |
```

- [ ] **Step 2: The central register — matrix note and open item 48**

In `docs/design/DECISIONS.md`, the `AG-D*` row of the ratification matrix ends
`(the on-disk spool, a named `I6` exception for the `host-local` agent only). |`. Replace that ending with:

```markdown
(the on-disk spool, a named `I6` exception for the `host-local` agent only). ⚠️ **Added 2026-10-13 (go-agent plan 008), `PROPOSED`, nothing above edited (`I3`):** `AG-D30` (the telemetry push interval, DP-settable per session, `OR-73`'s 1 min when unset; applies `AGW-D27` item 4; flagged against `OR-128` (8), open item 48). |
```

Check the `old_string` is unique before editing (`grep -c 'exception for the `host-local` agent only). |' docs/design/DECISIONS.md` → 1).

Open items table, a new row after row 47:

```markdown
| 48 | **The telemetry push interval on the agent path (`AG-D30`, go-agent plan 008).** Raised 2026-10-13. `contracts/agent/v1` `Limits` gains `telemetry_push_interval` (field 8); the agent holds stored batches until each push tick, `OR-73`'s 1 min when the field is unset. **(a)** The Java gateway (`AgentGatewayService`'s `limits(...)`) does not send the field yet, and where the DP's value is configured is undecided (one gateway property, or per agent registration). Until it is sent every agent pushes at 1 min, so G-A1 owes a re-run under that default (`backend/local/ga1-agent.sh`). **(b)** The agent's refusal of a value is invisible to the DP (its log, counter and gauge only); whether the gateway validates before sending. **(c)** A mid-session update, so a change reaches a connected agent without a reconnect. **(d)** ⚠️ **Owner ruling owed:** a DP-chosen push departs from `OR-128` (8) ("agent telemetry cadence is `OR-73`'s fixed values"); the default keeps the fixed value, and the departure is real only when the DP sends another. | owner ruling (d); `AGW-D*` owners (a)–(c) |
```

- [ ] **Step 3: `REQ-015`**

Create `docs/requirements/REQ-015-dp-set-telemetry-push-interval.md`:

```markdown
# REQ-015 — The Data Plane sets how often an agent pushes its stored telemetry

| | |
|---|---|
| **Status** | PROPOSED |
| **Source** | go-agent developer (rinawarnav@gmail.com), brainstorming sessions for the telemetry cadence change |
| **Raised** | 2026-10-08, as `REQ-013` in solution-manager-main; renumbered here and narrowed to push, 2026-10-13 |
| **Priority** | should (provisional: the source did not state one) |
| **Phase** | v1 (`host-local` telemetry role) |

## Requirement

A user at the Data Plane chooses how often each `host-local` agent pushes the telemetry it has stored to the Data Plane.
Between pull and push the data is stored on the agent's host, and it is deleted once the Data Plane acknowledges it. When the
Data Plane chooses nothing, the agent pushes every minute (`OR-73`, `AGW-D27` item 4).

## Why

A predictable send schedule, set at the Data Plane rather than on the host. The project owner's cadence for the agent path is
5 s collection and a 1 min push (`OR-73`); the 5 s collection was built (`AG-D28`), the push was not.

## Acceptance

With pull 5 s (the declarations) and no push value from the Data Plane, an agent writes a batch every 5 s, sends the stored
batches together about once a minute, and deletes each one when it is acknowledged. With a push value set, it sends at that
interval from its next session. With push `0`, it sends each batch as soon as it is written.

## Satisfied by

`AG-D30` (PROPOSED) in [`../design/go-agent/decisions.md`](../design/go-agent/decisions.md) · design
[`../design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md`](../design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md)
· built by [`../plans/go-agent/008-telemetry-push-interval.md`](../plans/go-agent/008-telemetry-push-interval.md). The status
stays `PROPOSED` until the project owner accepts this requirement into scope.

## Conflicts

⚠️ **A Data-Plane-chosen value departs from `OR-128` (8)'s fixed values**; the default keeps them (central open item 48 (d)).
⚠️ **Until the Java gateway sends the field** (item 48 (a)), only the 1 min default and `fake-gateway` exercise it.
**Renumbered:** raised as `REQ-013` in solution-manager-main, where it also asked for a Data-Plane-set pull interval; this tree's
`REQ-013` is fleet apply (`OR-47`), and pull is already set per binding by the declarations (`AG-D28`).
```

`docs/requirements/README.md`, after the `REQ-014` row:

```markdown
| `REQ-015` | The Data Plane sets the `host-local` agent's telemetry push interval; 1 min when it sets none | go-agent developer, 2026-10-08 (raised as `REQ-013` in solution-manager-main) — `PROPOSED`; see [`REQ-015`](REQ-015-dp-set-telemetry-push-interval.md) |
```

`docs/requirements/traceability.md`, after the `014` row:

```markdown
| [015](REQ-015-dp-set-telemetry-push-interval.md) | DP-set telemetry push interval, 1 min by default | PROPOSED | `AG-D30` · applies `AGW-D27` item 4 (`OR-73`) | [`../design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md`](../design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md) | ⚠️ the Java gateway does not send the field yet, and a DP-chosen value needs an owner ruling against `OR-128` (8): central open item 48 |
```

- [ ] **Step 4: go-agent `README.md` and `CLAUDE.md`**

`go-agent/README.md`, Telemetry paragraph: replace

```
under a byte and an age cap, and sends them on the Telemetry stream within the
gateway's credits.
```

with

```
under a byte and an age cap, and sends what it has stored at each push tick —
every minute (`OR-73`) unless the Data Plane sets `telemetry_push_interval` on the
session's limits, `0` meaning as soon as each batch is written (`AG-D30`) — on the
Telemetry stream within the gateway's credits.
```

`go-agent/CLAUDE.md`, Status section: after the slice-2 table (its last row is `| Open items | ...`), insert:

```markdown

**The telemetry push interval (`AG-D30`) is built on branch `go-agent/push-interval`** (go-agent plan 008): stored batches
go at each push tick, `OR-73`'s 1 min unless the DP sets `Limits.telemetry_push_interval` (`0` = at once).

| Artifact | Where |
|---|---|
| Design | [`../docs/design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md`](../docs/design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md) |
| Implementation plan | [`../docs/plans/go-agent/008-telemetry-push-interval.md`](../docs/plans/go-agent/008-telemetry-push-interval.md) |
| Open item | central item 48 in [`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md): the Java gateway does not send the field yet |
```

- [ ] **Step 5: `RUNBOOK.md`**

1. Line 90's quoted usage becomes
   `` `type: preflight | apply <file> | restart | stop | pause | resume | disconnect | set push <duration>|unset` ``.
2. Under `### 4. What terminal 1 prints`, before its table, add:
   `Each line now starts with the local time, `[hh:mm:ss]` (plan 008); the recorded lines below predate the prefix.`
3. Rename `### 5. Clean up` to `### 6. Clean up` and insert before it:

````markdown
### 5. The push interval set by the gateway

The gateway can set how often the agent pushes what it has stored (`AG-D30`). It travels on `SessionAccept`, so a change applies
when the agent next connects: until you `disconnect`, the live session keeps the value it was accepted with. `fake-gateway` sends
an explicit `0` unless told otherwise, which is why step 4's lines arrive every 5 s; the real gateway sends **no** value today,
and the agent then pushes every minute. `set` stores any value, including one the agent will refuse: the gateway is a test double.

**Status: not yet executed.**

In terminal 1, with step 4's lines arriving every 5 s:

```
set push 1m
disconnect
```

For a quicker demo use `set push 15s`: three lines per burst instead of twelve.

| When | Terminal 1 |
|---|---|
| `set push 1m` | `next session: push=1m0s (disconnect to apply)` |
| `disconnect` | `session closed; the agent will reconnect`; whatever was stored while it reconnected is sent at once |
| Then, once a minute | about twelve `[hh:mm:ss] telemetry binding-1 seq=…` lines with the same time, then a minute of silence |

In terminal 3:

```bash
curl -s localhost:9464/metrics | grep -E 'sm_agent_telemetry_(push_interval_seconds|limit_ignored_total)'
```

Expect `sm_agent_telemetry_push_interval_seconds 60`. `sm_agent_telemetry_limit_ignored_total` has no series until a value is
refused.

**What the real gateway sends.** `set push unset` and `disconnect`: the field is left out, and the gauge stays at `60`.

**A refused value.** `set push 500ms` and `disconnect`. A push below 1 s is refused, and so is one not below
`--telemetry-buffer-age` (24 h by default). Terminal 2 logs `telemetry: DP push interval refused; using the default`, naming the
`value` and the `reason` (`below 1s`); terminal 3 shows `sm_agent_telemetry_limit_ignored_total{limit="push"} 1` and the gauge
at the default `60`.

`set push 0` and `disconnect` return to sending each batch as soon as it is written.
````

- [ ] **Step 6: The spec's status line**

In the spec, replace `**Status:** DESIGN, approved section by section in brainstorming; implementation plan` with
`**Status:** DESIGN, approved section by section in brainstorming; built on branch `go-agent/push-interval` (see plan 008's Results); implementation plan`.

- [ ] **Step 7: Check every link in the changed documents**

```bash
cd "/home/arnav-rinawa/Documents/Solution Manager/solution-manager-UAT"
for f in docs/design/go-agent/decisions.md docs/requirements/REQ-015-dp-set-telemetry-push-interval.md docs/requirements/README.md \
         docs/requirements/traceability.md go-agent/CLAUDE.md go-agent/README.md go-agent/RUNBOOK.md \
         docs/design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md docs/plans/go-agent/008-telemetry-push-interval.md; do
  dir=$(dirname "$f")
  grep -oE '\]\([^)#[:space:]]+' "$f" | sed 's/^](//' | grep -v '^https\?:' | sort -u | while read -r p; do
    [ -e "$dir/$p" ] || echo "MISSING in $f: $p"
  done
done
```

Expected: no `MISSING` line that this plan introduced. (A link that was already dangling before this plan, in a file it only appended to, is recorded in Results, not fixed.)

- [ ] **Step 8: Commit**

```bash
git add docs/design/go-agent/decisions.md docs/design/DECISIONS.md docs/requirements/ go-agent/README.md go-agent/CLAUDE.md \
  go-agent/RUNBOOK.md docs/design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md
git commit -m "docs: AG-D30, REQ-015 and open item 48; go-agent README, CLAUDE and RUNBOOK for the push interval

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 9: Results**

Fill the Results section below (baseline, per-task test counts, mutation proofs with the failing test names, race, contract gate, Java run or why not, any deviation from this plan with its reason), then:

```bash
git add docs/plans/go-agent/008-telemetry-push-interval.md
git commit -m "docs: plan 008 results

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git log --oneline UAT..HEAD
```

---

## Results

*(Filled during execution.)*
