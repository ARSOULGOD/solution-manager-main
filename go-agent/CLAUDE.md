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
**Where anything under `go-agent/docs/` collides with it, the gateway design overrules — do not
re-litigate.** In particular, the gateway design was written against the baseline `AG-D*` family in
`../docs/design/go-agent/` (`AG-D1`–`AG-D12`), whose IDs 2–6 mean different things from this
directory's older `docs/decisions.md` (`AG-D1`–`AG-D6`); its "Fork inventory" section lists the
collisions. Cite `AGW-D*` and the gateway `design.md` section, not the older local IDs.

⚠️ **Still no `.proto`.** The gateway design specifies the message and state model but explicitly
stops short of the encoding (open item 6 / `AG-1` / `AGW-2`, architect group); its §14 Group A forbids
codegen before a reviewed `.proto` lands under `../contracts/`. What exists in `internal/` today is
scaffold plus two standalone demo CLIs, not the product. The `SM-D*` and `AGW-D*` decisions this
component depends on are PROPOSED, not ratified.

## Commands

```bash
# From go-agent/
go build -o sm-agent ./cmd/sm-agent
go vet ./...
go test ./...
go test ./internal/telemetry -run TestRun_AllTargetsFail_ReturnsError -v   # single test

# Docker container lifecycle CLI demo (controls pre-created containers only)
./sm-agent start|stop|restart|status <container-name>

# Local telemetry collect demo (HTTP-scrapes targets, writes JSON under --data-dir)
./sm-agent collect --targets <targets.yaml> --data-dir <dir>

# No subcommand: long-running skeleton mode (loads Config, blocks on SIGINT/SIGTERM)
./sm-agent -dp-enroll-url ... -dp-connect-addr ... -dp-telemetry-addr ...
```

Demo fixtures for the lifecycle/collect CLIs live in `../testdata/java-demo/` (see its README).

Container image build/run contract (distroless, non-root, expects a mounted Docker socket +
`$SM_AGENT_DATA_DIR` volume) is documented in [`README.md`](README.md); build context is this
directory.

## Architecture

`cmd/sm-agent/main.go` is a dispatcher, not the agent itself — it routes on `os.Args[1]` to one of
three unrelated code paths:

- `start|stop|restart|status` → `internal/docker.RunCLI` — a Docker lifecycle demo.
- `collect` → `internal/telemetry.Run` — a local telemetry-scrape demo.
- anything else → `internal/config.Load` then block on a signal channel. This is a placeholder for
  the real enroll → session → lease → apply loop, which is not implemented (no wire protocol yet).

These two demos exist independently of each other and of the eventual DP protocol; neither requires
the other to run.

**`internal/docker`** — `Controller` is the interface (`Start`/`Stop`/`Restart`/`Status`); `Client`
implements it against the real Docker Engine API via `github.com/docker/docker/client`, restricted to
containers that already exist (it never creates one). The engine dependency is narrowed to an
`engineAPI` interface so tests can fake it (`NewForTest`). Raw client errors are normalized in
`client.go`'s `mapError` into three sentinels (`ErrNotFound`, `ErrDaemonUnavailable`, `ErrPermission`)
that `cli.go`'s `RunCLI` and callers match against instead of inspecting Docker's error strings
directly.

**`internal/persist`** — file-backed durable state under `$SM_AGENT_DATA_DIR`. `WriteFileAtomic`
(`atomic.go`) is the single write primitive everything else builds on: temp file in the same directory
→ `fsync` → `chmod` → rename. `EnsureLayout` creates the `identity/`, `session/`, `apply/staging/`,
`telemetry/buffer/` subtree at mode `0700`. `Authority` and `Cursor` are typed JSON documents
(lease/session state, and apply generation/idempotency/in-flight state respectively) read and written
through that same atomic primitive. **Known gaps against the gateway design** (its "Fork inventory"):
`Cursor.InFlight.Phase` uses `PREFLIGHT|STAGING|RENAMED|RESTARTING|INDETERMINATE` but the wire's
`ApplyProgress.step` and the baseline `AG-D4` journal are `RECEIVED→STAGED→PERSISTED→REPORTED`;
`WriteFileAtomic` does not fsync the parent directory after rename; `Authority` lacks the
`cnf_thumbprint` that `LeaseGrant` carries.

**`internal/telemetry`** — `Run(ctx, dataDir, targets, client, maxBody)` is the pipeline:
`Scrape` (HTTP GET, capped body read) → `Validate` (status/body/size checks, sets `Sample.OK`/`Error`)
→ `Store.SaveSuccess`/`SaveFailure`. On success, `Store` writes `telemetry/<id>/latest.json` plus a
timestamped copy under `telemetry/<id>/history/`; on failure it writes
`telemetry/_failures/<id>-<timestamp>.json` and leaves any existing `latest.json` untouched, so one
bad target never clobbers another target's last-known-good sample. `Run` returns an error only when
**every** target fails — a single failing target among healthy ones is a partial success (exit 0),
matching the recovery behavior the failure-isolation design implies. `LoadTargets` parses the YAML
target list (`targets.go`) and is the only entry point that validates target `id`/`url` are non-empty.

**`internal/config`** — flag+env parsing (`SM_AGENT_DATA_DIR`, `SM_AGENT_ENROLL_TOKEN` override CLI
flags) for the long-running mode's `Config`. `ProtocolVersion` is hardcoded to `1`. The separate
`DPEnrollURL` / `DPConnectAddr` / `DPTelemetryAddr` fields encode a topology the gateway design
rejects — it is **one** mTLS connection to the `connect_endpoint` returned by `EnrollResponse`, with
`Enroll`, `Connect` and `Telemetry` as three streams on it (`AGW-D5`, design §4.1).

## Design docs (read before extending anything under `internal/`)

Authoritative, in reading order:

| File | What |
|---|---|
| `../docs/design/backend/agent-gateway/design.md` | **The wire.** §4 message catalogue and session machine; §3.5 how commands enter the apply engine; §11 the asks on the agent owners; §13 open items (`AGW-19` periodic report, `AGW-20` `item_id`/`sequence`, `AGW-8` ceilings, `AGW-3` cgo). |
| `../docs/design/backend/agent-gateway/decisions.md` | `AGW-D1`–`AGW-D16`; the `AGW-D ↔ AG-D` dependency table; the "Fork inventory" naming this directory's collisions. |
| `../docs/design/go-agent/` | The baseline `AG-D*` family (`AG-D1`–`AG-D12`) the gateway design cites — journal (`AG-D4`), outcomes (`AG-D5`), disk buffer (`AG-D9`), version observation (`AG-D12`). |

Local, **superseded where they collide** with the above:

| File | Status |
|---|---|
| [`docs/design.md`](docs/design.md) / [`docs/decisions.md`](docs/decisions.md) | Older component draft and `AG-D1`–`AG-D6`; IDs 2–6 collide with the baseline family. Keep for the additions the gateway design credits (`AG-D4` symlink-escape refusal); do not cite as authority. |
| [`docs/p2-wire-assumptions.md`](docs/p2-wire-assumptions.md), [`p2-baseline.md`](docs/p2-baseline.md), [`p2-wire-assumptions-audit.md`](docs/p2-wire-assumptions-audit.md) | The ASSUMED surrogate wire. Replaced by gateway `design.md` §4; its open questions are answered there (`AGW-D5`) or routed (§13). |
| [`docs/p3-mechanism-gaps.md`](docs/p3-mechanism-gaps.md) | Persist layout + renew backoff drafted against the surrogate; layout must follow the baseline `AG-D4` journal. |
| [`docs/workplan.md`](docs/workplan.md) | Phase gates P0–P5; the P2 "surrogate in force" line is void. The rule that still stands: **no product code (enroll/session/apply) until the `.proto` exists and the platform walking skeleton is accepted**. |
