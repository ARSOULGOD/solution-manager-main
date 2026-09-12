# Go-agent decisions — `AG-D*`

Decisions arising from [`design.md`](design.md). **All are `PROPOSED`.** None is ratified.

**ID discipline (`I3`):** IDs are immutable and never renumbered. `AG-D*` is the go-agent component family
alongside `D*`, `B/D*`, `SM-D*`, and other per-component families (`BE-D*` / `FE-D*` / `RT-D*` / `UI-D*`).

Cross-cutting authority (lease machine, session home, enrollment routing) lives in `DECISIONS.md`
(`SM-D24`, `SM-D28`, `B/D16`, …). **Do not re-decide those here.** This file records only agent-local choices.

⚠️ The agent↔DP **authoritative** wire under `contracts/` is still unspecified. Go-agent uses the **surrogate** in
[`p2-wire-assumptions.md`](p2-wire-assumptions.md) ([`p2-baseline.md`](p2-baseline.md)). Persist layout is specified in
[`p3-mechanism-gaps.md`](p3-mechanism-gaps.md). No `AG-D*` invents message schemas owned by backend.

---

| ID | Decision | Status |
|---|---|---|
| `AG-D1` | One binary; control and telemetry roles independently enabled at startup | PROPOSED |
| `AG-D2` | Host-local durable state is file-backed; **layout + renew backoff in `p3-mechanism-gaps.md`** (ASSUMED vs surrogate wire) | PROPOSED |
| `AG-D3` | Kubernetes control-role HA uses client-go leader election (Lease); one active control per Target/cluster | PROPOSED |
| `AG-D4` | Config writes are write-temp-then-rename with canonical-path + symlink-escape refusal | PROPOSED |
| `AG-D5` | v1 Cache collector surface is HTTP/`local-collector` only; Go `solace_event` is phase 2 | PROPOSED |
| `AG-D6` | Library baseline is deferred to the implementation plan — not locked in design | PROPOSED |

---

## `AG-D1` — One binary; roles independently enabled

A single Go binary implements both **control** and **telemetry** roles. Which roles run is selected at process
startup (flags / config). On a single host both typically run in one process; on Kubernetes, telemetry scales as a
DaemonSet while control is leader-elected at Target/cluster cardinality.

**Rationale.** Matches the locked topology note under `DECISIONS.md` §8 and SM-D7 (two-role agent in scope for v1).
One binary reduces install footprint and avoids dual-upgrade skew on the same node.

**Isolation expectation:** a fault in a collector path must not take down the control role’s lease/apply loop (and
the reverse). Same process is allowed; shared-fate bugs are not.

---

## `AG-D2` — File-backed durable state; layout specified (ASSUMED)

The agent persists on the host at least: lease/authority material, generation / idempotency / in-flight apply cursor
(B/D16 crash-safe apply), and the telemetry buffer (B/D2 Detect+Recover). Storage is **local files** under
`$SM_AGENT_DATA_DIR` (default `/var/lib/sm-agent`), mode `0700` / files `0600`.

**Layout, fsync, resume rules, and renew backoff/jitter** are recorded in [`p3-mechanism-gaps.md`](p3-mechanism-gaps.md)
against the surrogate wire. They remain **ASSUMED** until an authoritative `contracts/` proto forces a rename.

**Rationale.** Surrogate P2 unblocked closing the “TBD layout” gap without inventing a fake contracts proto.

---

## `AG-D3` — K8s control HA via client-go Lease

On Kubernetes, the control role participates in leader election using the standard client-go Lease-based election
for its Target/cluster identity. Only the leader applies generations or runs lifecycle verbs. On loss of leadership
the instance stops apply/lifecycle immediately; telemetry on that node continues if enabled.

**Rationale.** Architecture and §8 topology require one control actor per Target/cluster with HA — not one control
per node. Lease election is the ecosystem-native primitive; inventing a custom election would add failure modes
without benefit.

---

## `AG-D4` — Atomic config write + symlink refusal

Every config file write uses **write to a temp path in the same directory → fsync → atomic rename**. Before write,
resolve the destination with canonical-path checks and **refuse** if the path escapes the declared config directory
via symlink (or otherwise). On-target files carrying secrets: mode `0600`, owned by the MI/service account as
declared (`I6` / architecture §16.1).

**Rationale.** Same class of defect the hardening backlog forbids copying from File Mesh remote helpers; applies
equally when the writer is local. Truncated mid-write configs are worse than no write; a planted symlink turns a
config apply into arbitrary file overwrite.

---

## `AG-D5` — v1 collector = HTTP local scrape; `solace_event` later

For v1 (Cache), the telemetry role implements **HTTP scrape / actuator poll** under `local-collector` only. The Go
**`solace_event`** broker collector is **out of v1 agent scope** — it arrives with File Mesh (phase 2) and is gated
on Solace Go API maturity (`DECISIONS.md` open item 14). Cache emits no broker business metrics.

**Rationale.** Architecture §15 walking skeleton and v1 slice state this explicitly. Building a second Solace
consumer in Go before the API maturity gate and before File Mesh lands wastes scope.

---

## `AG-D6` — Libraries not locked in design

Do not treat logging, config-parsing, or metrics-exposition library picks as design decisions. The implementation
plan may choose concrete modules (`google.golang.org/grpc`, `crypto/tls`, `k8s.io/client-go` are the obvious
protocol/HA baselines). Revisit only if a pick forces a cross-component contract change.

**Rationale.** Earlier draft text named Viper/zap/zerolog without evidence or an `AG-D*` ID. Premature library locks
are churn once the `.proto` and persist layout exist.

---

## Changelog

| Date | Change |
|---|---|
| 2026-08-26 | Initial `AG-D1`…`AG-D6` from rewrite of the 2026-08-13 draft, kept under `go-agent/docs/`. Corrected false “dp-remote out of v1”, incomplete lease machine, and premature library locks. |
| 2026-09-02 | `AG-D2` layout + renew backoff closed in [`p3-mechanism-gaps.md`](p3-mechanism-gaps.md) against surrogate P2 wire. |
