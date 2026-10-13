# go-agent: the telemetry push interval (DP-set, OR-73's 1 min by default)

**Component:** `go-agent` · **Locus:** `host-local`, Docker runtime · **Date:** 2026-10-13 · **Status:** DESIGN, approved section by section in brainstorming; implementation plan [`008-telemetry-push-interval.md`](../../../plans/go-agent/008-telemetry-push-interval.md)
**Authority:** [`../../backend/agent-gateway/design.md`](../../backend/agent-gateway/design.md) and [`decisions.md`](../../backend/agent-gateway/decisions.md) (`AGW-D27` item 4: *"Cadence is `OR-73`'s fixed 5 s collection / 1 min push"*), which overrule anything below they collide with; then [`../decisions.md`](../decisions.md) (`AG-D*`) and [`../design.md`](../design.md).
**Contract:** [`../../../../contracts/agent/v1/agent_gateway.proto`](../../../../contracts/agent/v1/agent_gateway.proto), append-only (`AGW-D11`; run-checks step 18).
**Requirement:** [`REQ-015`](../../../requirements/REQ-015-dp-set-telemetry-push-interval.md).
**Ported from:** solution-manager-main's plan 007 (`docs/plans/go-agent/007-telemetry-cadence.md` and `docs/superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md`, there), which put a DP-set **pull and push** interval on a DRAFT wire as `AG-D26` / `REQ-013`. Both ids mean something else in this tree; see §8.

---

## 1. Why, and what changes

`AGW-D27` item 4 rules the agent path's cadence: **5 s collection, 1 min push** (`OR-73`, `OR-128` (8)). Collection is built:
the gateway sends each binding's interval in `TelemetryDeclarations.interval` (5 s) and the agent pulls at it (`AG-D28`). **Push is not
built:** the agent sends each batch as soon as it is written, credits permitting, so a Cache with five metrics is about 12 sends a minute,
not one.

This change builds the push half on the agent, makes it settable by the DP per session, and keeps `OR-73`'s 1 min as the default.

| | Today (UAT) | After |
|---|---|---|
| **Pull** | per binding, `TelemetryDeclarations.interval` (`AG-D28`) | **unchanged** |
| **Push** | none: a batch goes as soon as it is written | stored batches wait on disk and go at each push tick; the tick is the DP's `telemetry_push_interval`, or **1 min** when the DP sends none |

Storage is unchanged: a batch lives on disk from its pull until the DP acks it, then it is deleted; the byte and age caps still apply.

### 1.1 Not built here

| Out | Why |
|---|---|
| A DP-set **pull** interval (plan 007's other half) | Pull is already DP-set, per binding, by `TelemetryDeclarations.interval`; a second field would be a second source of truth for one number |
| The Java gateway sending the field | The backend team's code; central open item 48 (§9). Until it does, every agent uses the 1 min default |
| A change reaching a connected agent mid-session | Needs a new message and its ordering rules; item 48 (c) |
| Merging stored batches into one larger batch at push time | Rejected in plan 007's brainstorming: the goal is a send schedule, not fewer messages, and merging would rework `AG-D23`/`AG-D24`'s seq, in-flight and gap guarantees |
| A local flag for the push interval | The DP is the source |
| Persisting the DP's value across an agent restart | It arrives again on the first `SessionAccept` |

---

## 2. The wire

`Limits` in `contracts/agent/v1/agent_gateway.proto` gains one appended field:

```proto
  // AG-D30. The telemetry push interval for this session: stored batches are sent at each tick.
  // Unset: the agent's default, OR-73's 1 min (AGW-D27 item 4). Set to 0: each batch is sent as soon as it is written.
  // Valid: 0, or at least 1 s and below the agent's --telemetry-buffer-age; any other value is refused and the default applies.
  google.protobuf.Duration telemetry_push_interval = 8;
```

- **Field 8 is the next free number in `Limits`** (1–7 are taken). It is new in this contract's lineage: plan 007's DRAFT used 8 for a
  pull interval, but that DRAFT never entered `contracts/` and no agent built from it was deployed.
- **Unset and `0` are different on purpose.** `Duration` is a message type, so proto3 keeps its presence. Unset is what today's Java
  gateway sends and gives `OR-73`'s 1 min; an explicit `0` restores the behaviour G-A1 and G-A2 proved, so the DP can roll back
  without an agent rebuild, and `fake-gateway` can keep every existing test unchanged (§5).
- `baseline.json` grows by `check_proto_append_only.py --write-baseline`; the append-only check, its self-test and `--superset` must pass.
  This tree has no `origin/UAT`, so `--superset` runs with `BASELINE_BASE_REF=UAT` (the local snapshot branch).
- Go code is regenerated with `make proto` (buf 1.73.0, `protoc-gen-go` v1.36.12, `protoc-gen-go-grpc` v1.5.1, the pinned versions).
  Java regenerates at build time; appending a field needs no Java change.

In Go, `msg.Limits` gains `TelemetryPushInterval *time.Duration` (nil = unset). `wire/codec` maps the three states both ways: no field ↔
nil, `0` ↔ pointer to 0, a value ↔ pointer to it.

---

## 3. Resolution: one pure function

`internal/telemetry/cadence.Resolve(l msg.Limits, bufferAge time.Duration) (push time.Duration, ignored *Ignored)`, with
`DefaultPush = time.Minute`. `push == 0` means "send at once".

| The DP sends | Push in use | Logged / counted |
|---|---|---|
| nothing | `DefaultPush` (1 min) | — |
| `0` | `0`: at once | — |
| ≥ 1 s and < `bufferAge` | that value | — |
| > 0 and < 1 s, or negative | `DefaultPush` | refused: `reason="below 1s"` / `"negative"` |
| ≥ `bufferAge` | `DefaultPush` | refused: `reason="not below the buffer age cap"` (every batch would expire before it was due) |

**One guard.** When `DefaultPush ≥ bufferAge` (an operator set `--telemetry-buffer-age` to 1 min or less), the default cannot apply
either, so wherever the table says `DefaultPush` the result is `0` instead. In the unset case this is logged once per session at `WARN`
with `reason="default not below the buffer age cap"` and **not counted**: no DP value was refused. `--telemetry-buffer-age` must already
be positive (`cmd/sm-agent`), so `bufferAge > 0` always holds here.

A refusal is logged once per session at `WARN` (`limit`, `value`, `reason`: durations only, nothing from a payload) and counted in
`sm_agent_telemetry_limit_ignored_total{limit="push"}`. The label stays for a future limit. `Resolve` returns what to log as
`*Ignored{Value, Reason, Refused bool}`; only `Refused` is counted.

### 3.1 Lifetime

- Resolved on every **new session id**, and only then. A session drop changes nothing: the schedule continues.
- Nothing is persisted. After an agent restart everything stored is due at once (§4.3), and the first `SessionAccept` sets the schedule.

---

## 4. The sender

### 4.1 Reducer (`internal/telemetry/send/reducer.go`)

| Item | Change |
|---|---|
| **State** | + `PushInterval time.Duration`, + `DueBy time.Time`, + `BufferAge time.Duration` (configuration, set once by the runner) |
| **Events** | + `PushDue{}`: the push ticker fired |
| **Effects** | + `ArmPush{Every time.Duration}`: reset the push ticker, or stop it when `Every` is `0` |

| On | Then |
|---|---|
| `SessionUp{ID, Limits}` | as today, plus `PushInterval = cadence.Resolve(Limits, BufferAge)`; if it changed, `ArmPush{PushInterval}` |
| `PushDue` | `DueBy = env.Now` |
| `SessionDown` | as today; `PushInterval` and `DueBy` are kept |

The interval is resolved **inside `SessionUp`**, from the limits that event already carries. A separate event from the pipeline's watcher
(§4.4) would race the runner's own `SessionUp`, and a session's first batches could go out before the schedule applied. Both call the
same pure `Resolve`, so they cannot disagree.

**Selection.** One case in `selectSends`, **after** the oversize and `UNREGISTERED` checks and **before** the credit check:

```go
case s.PushInterval > 0 && h.ProducedAt.After(s.DueBy):
    continue // stored, not yet due
```

`Env.Heads` holds each stream's oldest unsent batch, so a head that is not due means nothing behind it on that stream is due; order within
a stream never changes, and across streams the oldest-first rule is unchanged. With `PushInterval == 0` the case never matches, so
**every existing reducer test passes unchanged**.

Pull 5 s, push 1 min:

```
00:05 … 00:55   12 batches written, nothing sent
01:00           PushDue: DueBy = 01:00 → the 12 are sent back to back (one in flight per stream, AG-D23; each ack frees the next)
01:00.3         a batch produced at 01:00.2 waits for 02:00
```

### 4.2 Runner (`internal/telemetry/send/runner.go`)

- `Runner.BufferAge` (from `--telemetry-buffer-age`) is copied into the state before the first step.
- A push ticker, created stopped (as `retry` is) and driven only by `ArmPush`: `Every > 0` → `Reset(Every)`, `Every == 0` → `Stop()`.
  `time.Ticker.Reset` panics on a non-positive duration, so `0` never reaches it. Its firing steps `PushDue{}`.
- `Run` sets `state.DueBy = Now()` before the first step.

### 4.3 Edge cases

| Case | Behaviour |
|---|---|
| A push does not finish before the next tick (credit-starved, a backlog) | `DueBy` advances; nothing is skipped or reordered |
| Session drops, then reconnects | `DueBy` is kept: overdue batches go as soon as the stream opens, new ones wait for the next tick. The ticker is not reset unless the interval changed |
| Agent restart | `DueBy` = the runner's start, so everything stored before the restart goes on the first session |
| A fleet of agents | Each ticker counts from its own agent's first `ArmPush`, not from wall-clock `:00`, so agents do not all push in the same second |
| Push moves from 1 min to `0` at a new session | `ArmPush{0}` stops the ticker; everything stored is eligible at once |
| Agent wall clock steps backwards | Batches can wait up to the size of the step; the age cap and `UNREGISTERED` already compare `produced_at` the same way (§10) |
| Gap markers | Ride on the next batch sent, now at the next push |
| Oversize and `UNREGISTERED` drops | Happen on any wake, as now: their checks precede the due check |

### 4.4 Pipeline (`internal/telemetry/pipeline.go`)

UAT's pipeline keeps its declarations supervisor (`AG-D28`) as it is. It gains:

- one goroutine that watches the view and, on a **new session id**, calls `Resolve`, logs and counts a refusal (§3), and sets the gauge;
- `BufferAge` on the `send.Runner` it builds;
- at `New`, the gauge set to `Resolve(msg.Limits{}, BufferAge)` (1 min, or `0` under the §3 guard), so `/metrics` is truthful before the
  first session.

### 4.5 Visibility

| Metric | Value |
|---|---|
| `sm_agent_telemetry_push_interval_seconds` | the push interval in use; `0` = at once |
| `sm_agent_telemetry_limit_ignored_total{limit}` | refused DP values (`limit="push"`) |

The DP sees a sample up to about one push interval (plus drain time) after it was taken. `OR-134` ruled that the gateway's ±5 minute
window runs on the agent's **clock offset**, not on a sample's age (`AGW-D27`, clarification 2026-10-11), so a held batch is never refused
as `clock_skew` for having waited.

---

## 5. `fake-gateway`

- **Default: an explicit `0`**, so every existing scenario, unit and integration test, and `RUNBOOK.md`, behave exactly as today.
- **Scenario:** `limits.telemetry_push_interval:` takes a duration (`1m`, `0`) or `unset` (send no field: the production default).
- **Console:** `set push <duration>` and `set push unset` store the value for the **next** `SessionAccept`, printing
  `next session: push=<value> (disconnect to apply)`; `disconnect` applies it. The stored value is guarded by a mutex: the console and the
  session goroutines both touch it.
- **Output:** `telemetry …`, `gap …` and `duplicate` lines gain a `[15:04:05]` local-time prefix, so a burst is visible. Console tests
  matching those lines change with it; integration tests time arrivals from the transcript's `Entry.At`, never from console text.

---

## 6. Error handling

| Failure | Behaviour |
|---|---|
| The DP sends an invalid value | refused, default applies (§3), `WARN` + `limit_ignored_total{limit="push"}` |
| The DP is not told its value was refused | a limit (§10); item 48 (b) |
| Session down | nothing sent, buffering continues, the schedule is kept |
| Everything else | as today (`AG-D23`–`AG-D25`) |

---

## 7. Testing

**Unit**, table-driven with literal expectations:

- `cadence.Resolve`: unset → 1 min; `0` → `0`; 30 s; 999 ms, negative, equal to and above the buffer age → refused, 1 min; buffer age
  ≤ 1 min with unset → `0` and the guard's reason.
- `send` reducer (plan 007's cases, adjusted so "absent" means 1 min): a head that is not due waits; `PushDue` releases every head
  `≤ DueBy`, oldest first; oversize and `UNREGISTERED` drops ignore the schedule; overdue heads go at once after a reconnect; `DueBy`
  starts at the runner's start; the cutoff advances over an unfinished push; `SessionUp` with a changed interval emits `ArmPush`, with an
  unchanged one emits nothing, with `0` emits `ArmPush{0}`. **All existing reducer tests pass unchanged.**
- `codec`: `telemetry_push_interval` round-trips all three states both ways.
- `fakegw`: the scenario parses a duration, `0` and `unset`, and the default is an explicit `0`; `set push` parses, prints and lands in the
  next `SessionAccept`.

**Integration** (`-tags integration`, `internal/agent`):

- `TestIntegrationPushIntervalSendsInBursts`: pull 1 s (declared), push 3 s. Arrivals cluster, and no batch arrives before the first push
  tick after its `produced_at`.
- `TestIntegrationPushIntervalAppliesAtNextSession`: push `0` → batches arrive about every pull; `set push 3s` → unchanged until
  `disconnect`; after it, bursts.
- `TestIntegrationPushIntervalUnsetUsesTheDefault`: the scenario sends no field; the agent's `sm_agent_telemetry_push_interval_seconds`
  reads `60` (the gauge, not a 60 s wait).
- Every existing UAT integration test (telemetry, declarations, discovery, rematerialize, drain) runs unchanged.

**Race:** `go test -race` on `internal/telemetry/...` and `internal/fakegw/...`.

**Mutation checks:** removing the due check, and changing `DefaultPush`, must each fail a named test.

**Contract:** `check_proto_append_only.py`, `--self-test`, and `--superset` with `BASELINE_BASE_REF=UAT`.

**Java, only if Maven and its dependencies are available here:** `mvn -pl agent-gateway -am test`, as evidence that the appended field
changes nothing on the gateway; recorded as not run otherwise.

**Manual:** a `RUNBOOK.md` walk-through against `fake-gateway`: `set push 1m`, `disconnect`, watch the bursts. Marked executed only after
a real run.

**Owed, not run here: a G-A1 re-run** (`backend/local/ga1-agent.sh`). G-A1 and G-A2 passed with send-at-once; this change makes the real
gateway's agents push every minute. The scenario's waits (240 s for metrics, 120 s in the crash step) fit 1 min, but only a run proves it.
The harness needs `solace-cache-engine:local`, `solace-pubsub-enterprise:10.25.0.177`, and the integrator's Docker lock scripts.

---

## 8. Records, all `PROPOSED`

| Record | Content |
|---|---|
| **`AG-D30`** in [`../decisions.md`](../decisions.md) (next free; `AG-D29` is the last) | **The DP may set the telemetry push interval per session**, as `Limits.telemetry_push_interval`. Unset means `OR-73`'s 1 min; `0` means each batch is sent when written; a change applies at the next session; at each push tick the stored batches go individually, in order. Applies `AGW-D27` item 4 on the agent side; amends nothing; `AG-D28`'s per-binding pull is untouched. Index row, body, changelog row, and the `AG-D*` entry in [`../../DECISIONS.md`](../../DECISIONS.md)'s ratification matrix |
| **[`REQ-015`](../../../requirements/REQ-015-dp-set-telemetry-push-interval.md)** | The push half of solution-manager-main's `REQ-013`, renumbered: that tree's `REQ-013` collides with this tree's `REQ-013` (fleet apply, `OR-47`). `REQ-010`/`REQ-011` are reserved, `REQ-014` is the last. README row and `traceability.md` row |
| **Central open item 48** in [`../../DECISIONS.md`](../../DECISIONS.md) | §9 |

**Id collisions avoided, stated so nobody re-collides:** `AG-D26` (here: discovery by label; there: the cadence decision), `REQ-013`
(above), and `AG-14`–`AG-23`: this tree cites `AG-14`–`AG-21` without defining them (`go-agent/CLAUDE.md`), and solution-manager-main
defines `AG-14`–`AG-23` differently. None is reused; the open item goes to the central register, where agent items have lived since plan 015.

## 9. Open item 48: routed, not decided here

Owners: the backend (`AGW-D*`) team and the project owner.

| | Item |
|---|---|
| (a) | **The Java gateway does not send `telemetry_push_interval` yet**, and where the DP's value is configured is undecided (one gateway property, or per agent registration). Until it is sent, every agent pushes at 1 min |
| (b) | The DP is not told when the agent refuses a value; only the agent's log, counter and gauge show it. Whether the gateway should validate before sending |
| (c) | A mid-session update, so a change reaches a connected agent without a reconnect |
| (d) | **Owner ruling:** a DP-chosen push departs from `OR-128` (8) ("agent telemetry cadence is `OR-73`'s fixed values"). The default keeps the fixed value; the departure is real only when the DP sends another. Flagged, not resolved (`I4`) |

---

## 10. Capability limits: stated, not implied

1. **A change waits for the next session** (item 48 (c)).
2. **Per session, not per binding.** Every binding on an agent pushes on one schedule.
3. **A refused value is invisible to the DP** (item 48 (b)).
4. **Up to one push interval of extra latency** at the DP, plus drain time, by design.
5. **A backwards wall-clock step delays sends** by up to the step, as it already shifts the age cap.
6. **Against today's gateway only the default is exercised.** Explicit values are exercised by `fake-gateway` alone until item 48 (a).

`I1`: nothing touches an MI. `I6`: only durations are logged.

---

## 11. Documentation

- `go-agent/README.md`: the telemetry paragraph says batches go at the push interval (DP-set, 1 min by default).
- `go-agent/RUNBOOK.md`: the `set push` walk-through (§7, manual).
- `go-agent/CLAUDE.md`: the status line names this change, this spec and plan 008.

## 12. Follow-ups, out of scope

1. **Restore plan 006's documents into this tree:** the telemetry-slice spec and plan, `AG-14`–`AG-22`'s definitions, and the original
   `AG-D21`–`AG-D25` texts, which would replace this tree's reconstructions and close its dangling citations. A separate doc-only change.
2. Item 48 (a)–(d).
3. The G-A1 re-run (§7).

**Plan number.** Plan 008, not 006: solution-manager-main's `006-telemetry-slice.md` and `007-telemetry-cadence.md` hold 006 and 007,
and follow-up 1 may bring them here.
