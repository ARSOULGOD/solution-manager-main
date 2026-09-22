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

**Slice 1 (the control path) is specified, planned and not yet built.**

| Artifact | Where |
|---|---|
| Design | [`../docs/superpowers/specs/2026-09-21-go-agent-control-path-design.md`](../docs/superpowers/specs/2026-09-21-go-agent-control-path-design.md) |
| Implementation plan | [`../docs/plans/go-agent/005-control-path.md`](../docs/plans/go-agent/005-control-path.md) — 24 TDD tasks, phases P1–P5 |
| Agent decisions | [`../docs/design/go-agent/decisions.md`](../docs/design/go-agent/decisions.md) — `AG-D1`–`AG-D12` baseline, `AG-D13`–`AG-D20` from the plan |

**Do not improvise an implementation.** If you are here to build slice 1, execute the plan
task-by-task (`superpowers:executing-plans` or `superpowers:subagent-driven-development`); the plan
carries the code, the tests and the commit boundaries.

**Two gates, and their current readings:**

- **The `.proto`.** There is still no *authoritative* contract — open item 6 / `AG-1` / `AGW-2` routes
  it to the architect group **with the `AG-D*` owners**, and the gateway design's §4 is explicitly
  "the input" to it. `AG-D13` therefore drafts one **in-repo** at
  `proto/agentgateway/v1draft/agent_gateway.proto`, transcribed 1:1 from §4.3/§4.11, labelled DRAFT,
  and **never** placed under `../contracts/` (single-writer, normative). Only `internal/wire` imports
  the generated code, so the swap when the real contract lands is one directory.
- **The walking skeleton.** The `dp-remote` apply engine (backend plans 004–005) is merged on this
  lineage, and the project owner has confirmed agent product code may now be written. The old
  `workplan.md` rule "no product code until the skeleton is accepted" is **discharged**; it is
  recorded in the plan's §1.3 as an assumption so the reading stays visible.

`SM-D*`, `AG-D*` and `AGW-D*` are all **PROPOSED**, not ratified (`I4`).

## Current state of the code

⚠️ **What is in `internal/` today is the pre-rewrite scaffold, not the product.** It was written
against the ASSUMED surrogate wire and is structurally incompatible with the gateway design. Plan
task 1 **archives it to `oldGo-Agent/`** — `git mv`, with history, plus a nested `go.mod` so the
parent module's `go build ./...` skips it. **It is never deleted**; it stays readable while the
replacement is written.

The three incompatibilities the gateway design's fork inventory named, and where each is fixed:

| Scaffold | Problem | Replaced by |
|---|---|---|
| `internal/persist` (`Cursor.InFlight.Phase`) | phases `PREFLIGHT\|STAGING\|RENAMED\|RESTARTING\|INDETERMINATE` against the wire's and baseline `AG-D4`'s `RECEIVED→STAGED→PERSISTED→REPORTED` | `internal/commands` (journal) |
| `internal/persist` (`WriteFileAtomic`) | no parent-directory fsync after rename, which design §5.1 requires | `internal/store` + `internal/runtime/configwriter` |
| `internal/config` (`DPEnrollURL`/`DPConnectAddr`/`DPTelemetryAddr`) | encodes three endpoints where the design has **one** mTLS connection carrying `Enroll`, `Connect` and `Telemetry` as three streams (`AGW-D5`, §4.1) | one `--connect-endpoint`, replaced by `EnrollResponse.connect_endpoint` |

Worth keeping from the scaffold, and why it is archived rather than dropped: `internal/docker` is the
clearest record of how the Engine API was driven (it becomes the command-driven
`internal/runtime/docker`), and `internal/telemetry`'s scrape → validate → store shape is what slice
2's telemetry role rebuilds. What was correct is **re-derived, not copied** — the atomic write (now
with the parent fsync) and the symlink-escape refusal the gateway design credits as a genuine addition.

### Commands that work today

These drive the two demo CLIs; plan task 1 retires both (the lifecycle verbs become DP commands, the
collect demo becomes slice 2's telemetry role).

```bash
go build -o sm-agent ./cmd/sm-agent
go vet ./... && go test ./...

./sm-agent start|stop|restart|status <container-name>          # Docker lifecycle demo
./sm-agent collect --targets <targets.yaml> --data-dir <dir>   # local telemetry demo
```

### Commands after plan task 1

```bash
make proto              # regenerate internal/wire/pb (needs buf + protoc-gen-go[-grpc])
make build              # bin/sm-agent, bin/fake-gateway
make test               # go vet + unit tests
make test-integration   # scenarios against an in-process fake gateway
make test-docker        # Docker adapter against a real container (needs a socket)
```

## Target architecture (what the plan builds)

An **event-driven reactor** (`AG-D14`). Everything that can happen — an inbound message, a timer, a
finished operation, a connection event, a signal — becomes an `Event` on one inbox. One loop drains
it and passes it to **pure reducers** that return `(state, []Effect)`; the loop executes effects in
the fixed order **`Persist → Delete → Send → RunOp → timers → connection`**. That order *is* the
agent-side write-before-act rule, in one place instead of at every call site. Reducers never read a
clock, do I/O or spawn goroutines — impurities arrive only through `reactor.Env`. **A failed
`Persist` is fatal**: the agent stops rather than act without durability.

```
cmd/sm-agent/          flags+env (AG-D7), wiring, run
cmd/fake-gateway/      scenario-driven test double (AG-D16)
proto/…/v1draft/       DRAFT wire, transcribed from gateway §4.3/§4.11 (AG-D13)
internal/msg/          the message catalogue as Go structs — what every package except wire speaks
internal/reactor/      Event, Effect, Env, TimerKind
internal/agent/        the loop, the session reducer, effect executor, op worker, admin surface
internal/authority/    lease reducer — SM-D28's phases, DP-time timers, admission
internal/commands/     command journal (AG-D4) — offer gate, idempotency, resume, outcomes
internal/reports/      actual-state reports — item_id/sequence (AG-D17), cadence (AG-D18)
internal/wire/         gRPC/mTLS binding; wire + wire/codec are the ONLY importers of wire/pb
internal/identity/     key, CSR (persisted before first Enroll), cert chain, trust bundle
internal/runtime/      Adapter seam; docker/ implementation; configwriter (atomic, 0600, symlink refusal)
internal/store/        durable records; one write primitive with parent-dir fsync
internal/clock/        DP clock offset → CLOCK_SUSPECT input
internal/fakegw/       fake gateway server, shared by the binary and integration tests
oldGo-Agent/           the archived scaffold — reference only, not built, not maintained
```

Reducer dispatch order is fixed: **`session → authority → commands → reports`**, each later reducer
reading the earlier ones' *new* state. That is how `commands` refuses an offer because `authority`
just left `ACTIVE`.

**Slice 1 does not build:** the `Telemetry` stream, credits and the disk buffer (`AG-D9`, slice 2);
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
| [`../docs/plans/go-agent/005-control-path.md`](../docs/plans/go-agent/005-control-path.md) | The executable plan. |

Superseded, kept for reference, **never cite as authority**:

| Where | What |
|---|---|
| [`docs/`](docs/) — moves to `../docs/design/archive/go-agent-fork/` at plan task 24 | The old local design, its `AG-D1`–`AG-D6` family (IDs collide with the baseline), the workplan, and the ASSUMED surrogate wire (`p2-*`, `p3-mechanism-gaps.md`). [`docs/p2-wire-assumptions-audit.md`](docs/p2-wire-assumptions-audit.md) is still worth reading beside §4.3 — it shows what got answered. |
| `oldGo-Agent/` — created by plan task 1 | The archived scaffold. Not built, not tested, not maintained. |
