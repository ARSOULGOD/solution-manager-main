# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Scope

This file covers only `go-agent/`, the Go module for Solution Manager's `host-local` agent. The
repo-root `../CLAUDE.md` is authoritative for cross-cutting rules (decision-ID discipline, project
invariants `I1`–`I7`, repo layout, workflow); read it first.

## Precedence — the agent-gateway design wins

**`../docs/design/backend/agent-gateway/` is authoritative for everything on the agent↔Data-Plane
wire**: `design.md` §4 (message catalogue, session machine, lease handling, command delivery, reports,
telemetry credits, error enum), `decisions.md` (`AGW-D1`–`AGW-D16`) and `lease-and-registry-sketch.md`.
**Where anything else in this directory collides with it, the gateway design overrules — do not
re-litigate.** The gateway design was written against the baseline `AG-D*` family in
`../docs/design/go-agent/`, whose IDs 2–6 mean different things from the older local family that used
to live in `go-agent/docs/`; the gateway `decisions.md` "Fork inventory" lists the collisions. Cite
`AGW-D*` or the baseline `AG-D*`, never the retired local IDs.

## Status — read this before writing code

**Slice 1 (the control path) is specified, planned, built, reviewed and merged to `main`**, including the fixes
from its final whole-branch review.

| Artifact | Where |
|---|---|
| Design | [`../docs/superpowers/specs/2026-09-21-go-agent-control-path-design.md`](../docs/superpowers/specs/2026-09-21-go-agent-control-path-design.md) |
| Implementation plan | [`../docs/plans/go-agent/005-control-path.md`](../docs/plans/go-agent/005-control-path.md) — 24 TDD tasks, phases P1–P5 |
| Agent decisions | [`../docs/design/go-agent/decisions.md`](../docs/design/go-agent/decisions.md) — `AG-D1`–`AG-D12` baseline, `AG-D13`–`AG-D20` from the plan |

**Slice 2 (the telemetry role) is specified, planned, built and reviewed on branch `feat/go-agent-telemetry`, not merged.**

| Artifact | Where |
|---|---|
| Design | [`../docs/superpowers/specs/2026-10-01-go-agent-telemetry-slice-design.md`](../docs/superpowers/specs/2026-10-01-go-agent-telemetry-slice-design.md) |
| Implementation plan | [`../docs/plans/go-agent/006-telemetry-slice.md`](../docs/plans/go-agent/006-telemetry-slice.md) — its Results section records what was built and every deviation |
| Agent decisions | `AG-D21`–`AG-D25` in [`../docs/design/go-agent/decisions.md`](../docs/design/go-agent/decisions.md), all PROPOSED; open items `AG-14`–`AG-21` in design §13.2 |

The plan is now the historical record of what was built, not forward guidance — read it to
understand why the code is shaped the way it is, not as a task list to execute.

**One gate remains open:**

- **The `.proto`.** There is still no *authoritative* contract — open item 6 / `AG-1` / `AGW-2` routes
  it to the architect group **with the `AG-D*` owners**, and the gateway design's §4 is explicitly
  "the input" to it. `AG-D13` therefore drafts one **in-repo** at
  `proto/agentgateway/v1draft/agent_gateway.proto`, transcribed 1:1 from §4.3/§4.11, labelled DRAFT,
  and **never** placed under `../contracts/` (single-writer, normative). Only `internal/wire` imports
  the generated code, so the swap when the real contract lands is one directory.

The walking-skeleton gate — product code blocked until the `dp-remote` apply engine merged — is
**discharged** (recorded in the plan's §1.3) and no longer applies.

**Open items slice 1 leaves.** None stops the agent running. The `AG-*` rows are in
[`../docs/design/go-agent/design.md`](../docs/design/go-agent/design.md) §13:

- **`AG-5`**: renewal backoff and jitter are unspecified (§13.1).
  `TestIntegrationRenewAtHalfLifeAndSameIDOnResend` is skipped on it, not deleted.
- **`AG-12`**: nothing prunes terminated journal records (§13.2).
- **`AG-13`**: pending actual-state reports are unbounded during a DP outage (§13.2).
- **`make test-docker` skips on Docker Desktop.** The E2E test bind-mounts `t.TempDir()`, which is under `/tmp`,
  and Docker Desktop mounts only the paths it shares. The fix, a temp dir under `$HOME`, is not done yet.

`SM-D*`, `AG-D*` and `AGW-D*` are all **PROPOSED**, not ratified (`I4`).

## Current state of the code

`internal/` holds the rewritten control path — see Architecture below. The pre-rewrite scaffold is
archived at [`oldGo-Agent/`](oldGo-Agent/), not deleted: its own README names the three structural
incompatibilities the gateway design's fork inventory found in it (journal phases, a config shape
encoding a separate telemetry endpoint, `WriteFileAtomic` with no parent-directory fsync). What those
gaps described is closed here: the journal lives in `internal/commands`, the atomic-write-with-
parent-fsync guarantee in `internal/store` and `internal/runtime/configwriter`, and the single mTLS
connection in `internal/wire`.

## Commands

```bash
make proto              # regenerate internal/wire/pb (needs buf + protoc-gen-go[-grpc])
make build              # bin/sm-agent, bin/fake-gateway
make test               # go vet + unit tests
make test-integration   # scenarios against an in-process fake gateway
make test-docker        # Docker adapter against a real container (needs a socket)

bin/sm-agent --roles control,telemetry --telemetry-config telemetry.yaml ...   # roles: control (default) | control,telemetry | telemetry
```

**On Docker Desktop:** export `DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock`. Bind-mount only directories
under `$HOME`, never `/tmp`, because Docker Desktop refuses a mount of a path it does not share.

## Architecture

An **event-driven reactor** (`AG-D14`). Everything that can happen — an inbound message, a timer, a
finished operation, a connection event, a signal — becomes an `Event` on one inbox. One loop drains
it and passes it to **pure reducers** that return `(state, []Effect)`; the loop executes effects in
the fixed order **`Persist → Delete → Send → RunOp → timers → connection`**. That order *is* the
agent-side write-before-act rule, in one place instead of at every call site. Reducers never read a
clock, do I/O or spawn goroutines — impurities arrive only through `reactor.Env`. **A failed
`Persist` is fatal**: the agent stops rather than act without durability.

```
cmd/sm-agent/          flags+env (AG-D7), wiring, run
cmd/fake-gateway/      scenario-driven test double (AG-D16); --interactive adds a stdin console (RUNBOOK.md)
proto/…/v1draft/       DRAFT wire, transcribed from gateway §4.3/§4.11 (AG-D13)
internal/msg/          the message catalogue as Go structs — what every package except wire speaks
internal/reactor/      Event, Effect, Env, TimerKind
internal/agent/        the loop, the session reducer, effect executor, op worker, admin surface
internal/authority/    lease reducer — SM-D28's phases, DP-time timers, admission
internal/commands/     command journal (AG-D4) — offer gate, idempotency, one-at-a-time dispatch queue, resume, outcomes
internal/reports/      actual-state reports — item_id/sequence (AG-D17), cadence (AG-D18)
internal/wire/         gRPC/mTLS binding; wire + wire/codec are the only production importers of wire/pb (fakegw, the test double, must implement its server)
internal/identity/     key, CSR (persisted before first Enroll), cert chain, trust bundle
internal/runtime/      Adapter seam; docker/ implementation; configwriter (atomic, 0600, symlink refusal, in-memory rollback)
internal/store/        durable records; one write primitive with parent-dir fsync
internal/clock/        DP clock offset → CLOCK_SUSPECT input
internal/telemetry/    pipeline wiring (AG-D21); view/ declare/ jsonptr/ collect/ buffer/ send/ tmetrics/
internal/fakegw/       fake gateway server, shared by the binary and integration tests
oldGo-Agent/           the archived scaffold — reference only, not built, not maintained
```

Reducer dispatch order is fixed: **`session → authority → commands → reports`**, each later reducer
reading the earlier ones' *new* state. That is how `commands` refuses an offer because `authority`
just left `ACTIVE`.

### Invariants the final review paid for — do not undo

Each one was a real defect, and each has a test that fails without its fix.

- **One runtime op at a time.** `commands.State.InFlight` is set when a `RunOp` is dispatched and cleared **only**
  by that command's `OpFinished`. Closing its record, for example on a step deadline or `SUPERSEDED`, does not clear
  it, because the worker is still busy. Later offers wait in `Queue`.
  - Admission and the step deadline are evaluated **at dispatch**, not at offer.
  - Why: without the queue, a stop that interleaved with an outstanding apply was acked `FAILED_NO_EFFECT` after
    it had succeeded.
- **The replaced config never touches disk (`I6`).** `configwriter` keeps the previous content in memory, and
  only for `FAILED_ROLLED_BACK`. The journal may lag the filesystem but never lead it. That is safe because the
  write is idempotent.
- **`Snapshot` must not alias mutable state.** `cloneState` deep-copies every map and slice that a reducer mutates
  (`L23` in [`../docs/design/common/lessons.md`](../docs/design/common/lessons.md)). A new field of that kind in any
  reducer's `State` needs a matching line there.
- **`Loop.Run` releases every goroutine it starts, on every exit path.**
- **Secrets stay out of logs.**
  - `CommandOffer` logs `payload_len`, never the payload.
  - `EnrollRequest` logs `token_present` and prints `token=[REDACTED]`.
  - A test that claims to prove a redaction must fail when the redaction is removed (`L24`).

**Slice 1 did not build (slice 2 now does, on `feat/go-agent-telemetry`):** the `Telemetry` stream, credits and the disk buffer (`AG-D9`, slice 2);
Kubernetes leader election (`AG-D6`) and the ConfigMap writer (`SM-D25`, slice 3); the `solace_event`
collector (open item 35 / `AGW-3` — the vendor Go API is cgo and `AG-D1` requires a cgo-free binary);
the report-only credential (`AGW-12`, undesigned plane-side).

## Two asks this component owes other owners

Named here because they are easy to mistake for settled:

- **`AG-D19`** — `AG-D5`'s outcome set has no member for "the lifecycle verb changed state and still
  failed". Slice 1 decides outcomes by **observation** (`restart` is `APPLIED` only when the
  post-state shows a newer `StartedAt`) and lets the next `ActualStateReport` carry the truth. Adding
  an outcome is a wire change: **routed to the `AG-D*`/`AGW` owners**, not decided.
- **`AG-D20`** — the wire carries **no placement facts**, so the agent must find the container itself.
  On Docker: the label `com.solace.sm.binding-id`, the config path from the container's own launch
  args/env, the host path through a required bind mount. All four are *deployment configuration*, so
  `I1` is satisfied — but if the protocol later carries placement, this is superseded, not extended.

## Design docs

Authoritative, in reading order:

| File | What |
|---|---|
| [`../docs/design/backend/agent-gateway/design.md`](../docs/design/backend/agent-gateway/design.md) | **The wire.** §4 catalogue and session machine; §3.5 how commands enter the apply engine; §11 the asks on the agent owners; §13 open items (`AGW-19`, `AGW-20`, `AGW-8`, `AGW-3`). |
| [`../docs/design/backend/agent-gateway/decisions.md`](../docs/design/backend/agent-gateway/decisions.md) | `AGW-D1`–`AGW-D16`; the `AGW-D ↔ AG-D` dependency table; the "Fork inventory". |
| [`../docs/design/go-agent/design.md`](../docs/design/go-agent/design.md) · [`decisions.md`](../docs/design/go-agent/decisions.md) | The baseline `AG-D*` family — journal (`AG-D4`), outcomes (`AG-D5`), buffer (`AG-D9`), version observation (`AG-D12`) — plus `AG-D13`–`AG-D20` from slice 1. |
| [`../docs/superpowers/specs/2026-09-21-go-agent-control-path-design.md`](../docs/superpowers/specs/2026-09-21-go-agent-control-path-design.md) | Slice 1's design: reactor, reducers, store layout, error taxonomy, testing. |
| [`../docs/plans/go-agent/005-control-path.md`](../docs/plans/go-agent/005-control-path.md) | The executed plan. |

Superseded, kept for reference, **never cite as authority**:

| Where | What |
|---|---|
| [`../docs/design/archive/go-agent-fork/`](../docs/design/archive/go-agent-fork/) | The old local design, its `AG-D1`–`AG-D6` family (IDs collide with the baseline), the workplan, and the ASSUMED surrogate wire (`p2-*`, `p3-mechanism-gaps.md`). Its own README says what supersedes each part. |
| [`oldGo-Agent/`](oldGo-Agent/) | The archived scaffold. Not built, not tested, not maintained. |
