# go-agent — DP-set telemetry pull and push intervals

**Component:** `go-agent` · **Locus:** `host-local`, Docker runtime · **Date:** 2026-10-09 · **Status:** DESIGN, approved section by section in brainstorming; built on `feat/go-agent-telemetry`; implementation plan [`007-telemetry-cadence.md`](../../plans/go-agent/007-telemetry-cadence.md)
**Authority:** [`../../design/backend/agent-gateway/design.md`](../../design/backend/agent-gateway/design.md) (§4.3 `SessionAccept.limits`, §4.10 credits, §5.8 limits) — which overrules anything below it collides with — then the baseline agent family [`../../design/go-agent/design.md`](../../design/go-agent/design.md) and slice 2's [`2026-10-01-go-agent-telemetry-slice-design.md`](2026-10-01-go-agent-telemetry-slice-design.md).
**Builds on:** slice 2 (the telemetry role), built and reviewed on `feat/go-agent-telemetry`, not merged.
**Requirement:** [`REQ-013`](../../requirements/REQ-013-dp-set-telemetry-cadence.md).

---

## 1. Scope

### 1.1 Builds

The Data Plane decides, **per agent**, two durations that slice 2 fixes locally or does not have at all:

| Duration | Slice 2 today | After this change |
|---|---|---|
| **Pull**: how often each binding is scraped | per binding, `interval` in the local declarations file (`AG-D22`) | the DP's `telemetry_pull_interval` for every binding; the file's `interval` when the DP sends none |
| **Push**: how often stored batches are sent | none: a batch is sent as soon as it is written, credits permitting | the DP's `telemetry_push_interval`: batches wait on disk and are sent at each push tick; immediate when the DP sends none |

Storage is unchanged: a batch lives on disk from its pull until the DP acks it, then it is deleted (slice 2 §4.3); the byte and age caps still apply.

**Demonstrable end state** (pull 5 s, push 1 m, set from `fake-gateway`):

```
> set pull 5s
next session: pull=5s push=0s (disconnect to apply)
> set push 1m
next session: pull=5s push=1m (disconnect to apply)
> disconnect                         # 10:00:00; the agent reconnects with the new limits
[10:01:00] telemetry binding-1 seq=41 cache.hit_rate=0.93 … -> acked
  … seq=42 … seq=52, all within the same second
[10:02:00] telemetry binding-1 seq=53 … -> acked
  … seq=54 … seq=64
```

### 1.2 Does not build

| Out | Why / when |
|---|---|
| Per-binding intervals from the DP | Delivering per-binding telemetry settings is part of delivering declarations: open item `AG-14` |
| A change reaching a connected agent mid-session | Needs a new DP→agent message and its ordering rules; carried as the later option in `AG-23` (§9) |
| Merging stored batches into one larger batch at push time | Rejected in brainstorming: the goal is a predictable send schedule, not fewer messages, and merging would rework slice 2's seq, in-flight and gap guarantees |
| A local flag for the push interval | The DP is the source; absent a DP value the agent keeps slice 2's behaviour |
| Persisting the DP's values across an agent restart | Not needed: they arrive again on the first `SessionAccept` (§2.3) |

### 1.3 Brainstorming choices, recorded

1. **Pull every *pull*, push every *push*, delete on ack.** Storage between pull and ack is slice 2's buffer; only the push schedule is new.
2. **Send the stored batches individually**, back to back, at each push tick, for a predictable send schedule.
3. **The DP sets both durations**, not local flags or the declarations file.
4. **Per agent**, as two fields of `SessionAccept.limits`, the same pattern `report_interval` uses (gateway §4.3, §5.8).
5. **A change takes effect at the agent's next session** (approach A). The DP can force one with a reconnect; the agent needs nothing extra for that.

---

## 2. The two settings

### 2.1 Wire — DRAFT (`AG-D13` discipline)

`Limits` in `proto/agentgateway/v1draft/agent_gateway.proto` gains:

```proto
google.protobuf.Duration telemetry_pull_interval = 8;
google.protobuf.Duration telemetry_push_interval = 9;
```

Unlike the rest of the DRAFT, these two fields are **not transcribed from gateway §4.3**: they are an agent-side ask, and the DRAFT header says so in those words. `msg.Limits` gains `TelemetryPullInterval` and `TelemetryPushInterval`; `wire/codec` maps both directions with the existing `dur`/`durFrom` helpers, so an absent field decodes to `0`. Nothing is placed under `contracts/`. The ask itself is open item `AG-23` (§9).

### 2.2 Resolution — one pure function

`internal/telemetry/cadence.Resolve(limits msg.Limits, bufferAge time.Duration) (Settings, []Ignored)` returns `Settings{Pull, Push}`, where `0` means "the local default", and names each DP value it refused.

| Field | DP sends `0` / absent | DP sends a valid value | DP sends an invalid value |
|---|---|---|---|
| **Pull** | `Pull = 0`: each binding uses its declared `interval` | valid when **≥ 1 s**, the declarations file's own rule (slice 2 §3.2); used for every binding | `< 1 s`, including negative: refused, `Pull = 0` |
| **Push** | `Push = 0`: send immediately | valid when **> 0 and < `--telemetry-buffer-age`** | `≥` the buffer age, or negative: refused, `Push = 0`. A push at or beyond the age cap would expire every batch before it was due |

A refused value is logged once per session at `WARN` (`limit`, `value`, `reason`; durations only, nothing from a payload) and counted in `sm_agent_telemetry_limit_ignored_total{limit="pull"|"push"}`.

The scrape timeout follows from the pull interval in use: `timeout = min(declared timeout, pull interval)`, which keeps slice 2's `timeout ≤ interval` rule true when the DP shortens the interval.

### 2.3 Lifetime

- The values in use are recomputed on every **new session id**, and only then.
- A session drop changes nothing: collection continues at the last DP-set pull rate, and the push schedule stays. The agent does not fall back to the file's rates in the middle of an outage.
- Nothing is persisted. After an agent restart the file's intervals and immediate push apply until the first `SessionAccept`.

### 2.4 Visibility

| Gauge | Value |
|---|---|
| `sm_agent_telemetry_pull_interval_seconds{binding}` | the pull interval each binding is collected at: the DP's, or the declared one |
| `sm_agent_telemetry_push_interval_seconds` | the push interval in use; `0` = immediate |

Both are set at pipeline start from the declared intervals and `0`, and again whenever the values in use change (§4.1).

Because the DP sets the push interval, it knows a sample can reach it up to about one push interval (plus the time to drain) after it was taken, and can allow for that when it judges staleness.

---

## 3. Push — the sender

### 3.1 Reducer changes (`send/reducer.go`)

| Item | Change |
|---|---|
| **State** | + `PushInterval time.Duration`, + `DueBy time.Time`, + `BufferAge time.Duration` (configuration, set once by the runner) |
| **Events** | + `PushDue{}`: the runner's push ticker fired |
| **Effects** | + `ArmPush{Every time.Duration}`: reset the push ticker, or stop it when `Every` is `0` |

| On | Then |
|---|---|
| `SessionUp{limits}` | as slice 2, plus: `PushInterval = cadence.Resolve(limits, BufferAge).Push`; if it changed, `ArmPush{PushInterval}` |
| `PushDue` | `DueBy = env.Now` |
| `SessionDown` | as slice 2; `PushInterval` and `DueBy` are kept |

The push interval is resolved **inside `SessionUp`**, from the limits that event already carries, rather than delivered by a separate event. A separate event from the pipeline's watcher (§4.1) would race the runner's own `SessionUp`, so the first batches of a session could go out immediately before the schedule applied. Both consumers call the same pure `Resolve`, so they cannot disagree.

### 3.2 Selection

One case is added to `selectSends`, **after** the oversize and `UNREGISTERED` checks and **before** the credit check:

```go
case s.PushInterval > 0 && h.ProducedAt.After(s.DueBy):
    continue // stored, not yet due
```

`Env.Heads` holds each stream's oldest unsent batch, so a head that is not due means nothing behind it on that stream is due: order within a stream never changes. Across streams, the oldest-first rule of slice 2 §5.2 is unchanged.

**Pull 5 s, push 1 m:**

```
00:05 … 00:55   12 batches written, nothing sent
01:00           PushDue: DueBy = 01:00 → seq 1..12 sent back to back
                (one in flight per stream, AG-D23; each ack frees the next)
01:00.3         seq 13, produced 01:00.2, waits for 02:00
```

### 3.3 Unchanged

- Oversize and `UNREGISTERED` drops happen on any wake, as now, because their checks precede the new one.
- Gap markers ride on the next batch sent, now at the next push.
- Credits, rejects, stream breaks and backoff are slice 2's.
- With `PushInterval = 0` the new case never matches: **every slice 2 reducer test must pass unchanged**.

### 3.4 Edge cases

| Case | Behaviour |
|---|---|
| A push does not finish before the next tick (credit-starved, or a backlog) | `DueBy` advances; nothing is skipped or reordered |
| Session drops, then reconnects | `DueBy` is kept, so overdue batches go as soon as the stream opens; new ones wait for the next tick. The ticker is not reset unless the interval changed |
| Agent restart | The runner sets `DueBy` to its start time, so everything stored before the restart is already due on the first session |
| A fleet of agents | Each ticker counts from its own agent's first `ArmPush`, not from wall-clock `:00`, so agents do not all push in the same second |
| Push changes from `1m` to `0` at a new session | `ArmPush{0}` stops the ticker; the new case stops matching; everything stored is eligible at once |
| Agent wall clock steps backwards | Batches can wait up to the size of the step. The age cap and `UNREGISTERED` already compare `produced_at` the same way; recorded as a limit (§10), not given a second clock scheme |

### 3.5 Runner changes (`send/runner.go`)

- A push ticker, created stopped (as `retry` is today) and driven only by `ArmPush`: `Every > 0` → `Reset(Every)`; `Every == 0` → `Stop()`. `time.Ticker.Reset` **panics on a non-positive duration** (verified: `go doc time.Ticker.Reset`, Go 1.26.4, the module's toolchain), so `0` must never reach it.
- Its firing steps `PushDue{}`.
- `Run` sets `state.DueBy = Now()` and `state.BufferAge` before the first step.

---

## 4. Pull — the collectors

### 4.1 One holder, one watcher

`internal/telemetry/cadence` also provides `Holder`, the `view.Hub` idiom: `Current() Settings`, `Changed() <-chan struct{}` (closed and replaced on every `Set`), `Set(Settings)`.

The pipeline runs one more goroutine. It watches the view, and on a **new session id** it calls `Resolve`, logs and counts what was refused (§2.2), updates the gauges (§2.4) and calls `Holder.Set` if the settings changed. On a session drop it does nothing (§2.3).

### 4.2 `Collector.Run`

```
interval = holder.Current().Pull, or Decl.Interval when that is 0
timeout  = min(Decl.Timeout, interval)                   // per tick
on holder.Changed(): if interval changed → ticker.Reset(interval)
```

`interval` is always ≥ 1 s here (the file's rule, or `Resolve`'s), so `Reset` never sees `0`. After a change the next scrape comes one new interval later, never immediately, so a reconnect causes no burst of scrapes. Everything inside a tick is slice 2's: the lease check, `recover()`, no zero for an unavailable value, one `GET` per `(port, path)`.

### 4.3 Unchanged, and one consequence

- The file's `interval` stays **required and validated** (≥ 1 s): it is the fallback.
- Bindings outside the lease are not collected.
- A slow tick drops beats rather than queuing them.
- **The DP can now make the agent pull faster.** Pulling at 1 s instead of 15 s writes 15× the batches. `--telemetry-buffer-bytes` still bounds the disk (drop-oldest as gaps), so a bad DP value costs data, never the host's disk.

---

## 5. `fake-gateway`

- **Scenario:** `limits.telemetry_pull_interval`, `limits.telemetry_push_interval`. The built-in default is `0` for both, so every existing scenario and test is unchanged.
- **Console verbs:**

  | Verb | Effect |
  |---|---|
  | `set pull <duration>` | stores the value for the **next** `SessionAccept`; `0` clears it |
  | `set push <duration>` | likewise |

  Each prints `next session: pull=<p> push=<q> (disconnect to apply)`. The existing `disconnect` applies them. The stored values are guarded by a mutex: the console and the session goroutines both touch them.
- **Output:** `telemetry …`, `gap …` and `duplicate` lines gain a `[15:04:05]` local-time prefix, so a burst is visible. The console tests matching those lines change with it. Integration tests time arrivals from the transcript's existing `Entry.At`, not from console text.

---

## 6. Error handling

| Failure | Behaviour |
|---|---|
| DP sends a pull interval `< 1 s` | refused, declared intervals used, `WARN` + `limit_ignored_total{limit="pull"}` |
| DP sends a push interval `≥ --telemetry-buffer-age`, or negative | refused, immediate push, `WARN` + `limit_ignored_total{limit="push"}` |
| The DP is not told its value was refused | a limit (§10); routed in `AG-23` |
| Session down | nothing sent, buffering continues, the values in use are kept |
| Everything else | slice 2 §8 |

---

## 7. Testing

**Unit**, table-driven with literal expectations:

- `cadence.Resolve`: valid pull and push; `0`; pull `< 1 s`; negative; push equal to and greater than the buffer age.
- `cadence.Holder`: `Changed` closes on `Set`.
- `send` reducer:
  - a head that is not due waits;
  - `PushDue` releases every head `≤ DueBy`, oldest first;
  - oversize and `UNREGISTERED` drops ignore the schedule;
  - overdue heads go at once after a reconnect;
  - `DueBy` starts at the runner's start;
  - the cutoff advances over an unfinished push;
  - `SessionUp` with a changed push interval emits `ArmPush`, with an unchanged one emits nothing, with `0` emits `ArmPush{0}`.
  - All existing reducer tests pass unchanged.
- `collect`: falls back to `Decl.Interval`; resets the ticker on a change; `timeout = min(Decl.Timeout, interval)`.
- `codec`: both `Limits` fields round-trip in both directions; an absent field decodes to `0`.
- `fakegw` console: `set pull` / `set push` parse, print, and land in the next `SessionAccept`.

**Integration** (`-tags integration`):

- `TestIntegrationPushIntervalSendsInBursts`: pull 1 s, push 3 s. Arrivals cluster, and no batch arrives before the first push tick after its `produced_at`.
- `TestIntegrationDPPullIntervalOverridesYAML`: declared 5 s, DP 1 s; consecutive `produced_at` about 1 s apart.
- `TestIntegrationLimitsApplyAtNextSession`: push `0` → batches arrive about every pull; set push 3 s → unchanged until `disconnect`; after it, bursts.
- Slice 2's telemetry integration tests run unchanged, at the defaults.

**Race:** `go test -race` on `internal/telemetry/...` and `internal/fakegw`.

**Mutation checks:** removing the due check, the collector's ticker reset, or the timeout clamp must each fail a named test.

**Manual:** a RUNBOOK run of `set pull 5s`, `set push 1m`, `disconnect`, watching the bursts; marked executed only after a real run.

No Docker test is added: `ScrapeAddr` and the adapter are untouched.

---

## 8. Decision this proposes

`PROPOSED` (`I4`); the plan adds it to [`../../design/go-agent/decisions.md`](../../design/go-agent/decisions.md).

| ID | Decision |
|---|---|
| `AG-D26` | **The DP sets the telemetry pull and push intervals per agent**, as `telemetry_pull_interval` and `telemetry_push_interval` on `SessionAccept.limits`. `0` means the local default (the declared `interval`; immediate push). Values take effect at the next session. A push interval holds stored batches until the next push tick, then sends them in order as individual batches. Amends `AG-D22`: the declared `interval` becomes the fallback rather than the only source. The plan records the amendment **in both entries** (`I3`), never by editing `AG-D22`'s meaning in place |

---

## 9. Open item — routed, not decided here

New row for [`../../design/go-agent/design.md`](../../design/go-agent/design.md) §13.2. Owners: the `AG-D*` and `AGW` owners; the wire is the gateway design's (pinned precedence).

| # | Item |
|---|---|
| `AG-23` | **Two `SessionAccept.limits` fields not in gateway §4.3: `telemetry_pull_interval` and `telemetry_push_interval`** (`AG-D26`). The DRAFT carries them; only `fake-gateway` sends them, and a gateway that sends neither leaves slice 2's behaviour. Also routed with it: (a) the DP is not told when the agent refuses a value (§2.2), (b) whether the gateway should validate the values before sending, and (c) the later option of a mid-session update message so a change reaches a connected agent without a reconnect (brainstorming approach B). Proposed |

The `AG-14` row gains a note: `AG-D26` covers the pull interval **per agent** only; a per-binding interval from the DP is still part of delivering declarations.

---

## 10. Capability limits — stated, not implied

1. **A change waits for the next session.** A connected agent keeps its old values until it reconnects; the DP can force that, the agent cannot be told otherwise (`AG-23` c).
2. **Per agent, not per binding.** Every binding on an agent is pulled at the same DP-set rate.
3. **A refused value is invisible to the DP.** Only the agent's log, counter and gauges show it (`AG-23` a).
4. **Up to one push interval of extra latency** at the DP, plus drain time, by design.
5. **A backwards wall-clock step delays sends** by up to the step (§3.4), as it already shifts the age cap.
6. **A faster DP-set pull spends the byte cap sooner**; data is lost as gaps, the disk is not (§4.3).
7. **The wire fields are a DRAFT ask.** Against a real gateway that does not send them, nothing in this design is exercised.

`I1`: nothing here touches an MI; both durations are agent and DP behaviour. `I6`: only durations are logged; no payload or sample value is added to any log.

---

## 11. Documentation

- The DRAFT `.proto` header: the two fields are an agent-side ask, `AG-23`.
- `go-agent/README.md`: the telemetry paragraph and configuration notes say the DP may set both intervals.
- `go-agent/RUNBOOK.md`: the telemetry section gains the `set pull` / `set push` / `disconnect` walk-through, marked executed only after a real run.
- `go-agent/CLAUDE.md`: the status line names this change, its spec and its plan.
- `docs/requirements/REQ-013-dp-set-telemetry-cadence.md` and `traceability.md`: "Satisfied by" points at `AG-D26` once it is in `decisions.md`. The status stays `PROPOSED` until the project owner accepts the requirement into scope; only then can it become `DESIGNED`.
