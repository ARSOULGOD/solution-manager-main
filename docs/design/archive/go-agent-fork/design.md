# Go-agent — component design

**Component:** `go-agent` · **Locus:** `host-local` only · **Status:** DRAFT · **Date:** 2026-08-26  
**Stack:** Go (`D8` LOCKED) · **Code:** none yet  
**Decisions:** [`decisions.md`](decisions.md) (`AG-D*`) · **Cross-cutting:** [`../../DECISIONS.md`](../../DECISIONS.md), [`../../architecture.md`](../../architecture.md)

> ⚠️ **UNBASELINED.** `SM-D*` decisions are PROPOSED. The agent↔plane **wire protocol does not exist** (no `.proto`, no
> transport state machine). This document records what is already decided for the agent and what remains open. It does
> **not** invent the protocol. Do not implement against it as if ratified — see
> [`../../../tasks/codex-review-triage.md`](../../../tasks/codex-review-triage.md) and open item 6 in `DECISIONS.md`.

---

## 1. Purpose and scope

Solution Manager’s `host-local` actor: a Go agent that enrolls outbound-only to the **Data Plane**, holds a
CP-issued lease the DP renews, applies config + lifecycle for its Target, and (as a separate role) collects
infra telemetry locally and pushes normalized samples to the DP.

| In scope | Out of scope |
|---|---|
| Enrollment (agent → DP → CP), mTLS identity, lease-bounded authority | `dp-remote` apply (DP holds host credentials; separate open item) |
| Control role: generations, apply, lifecycle, report-actual | Artifact deploy / image pull (customer CI/CD owns artifacts — SM-D2) |
| Telemetry role: `local-collector` scrape + push; v1 Cache = HTTP/actuator path | `solace_event` Go collector (phase 2 / File Mesh; gated on Solace Go API maturity) |
| Runtime version **introspection** (observe, never require MI build-info) | Auto cert rotation; HTTP/1.1 long-poll fallback (deferred — h2 is a v1 install prerequisite) |
| K8s control-role leader election; DaemonSet telemetry | Large-scale fleet self-upgrade/canary (deferred; identity is **not** deferred — SM-D7) |
| Crash-safe, idempotent apply on the host | `scheduled` workloads (cut from v1 — incompatible with SM-D28’s one-hour lease) |

**Platform sequencing (architecture §15):** walking skeleton is Docker + **`dp-remote` first** (no agent). This
component starts at step 2 — **`host-local` agent**. Both loci are **v1**; this binary implements only `host-local`.

---

## 2. Relationship to cross-cutting decisions

| Topic | Binding IDs | Agent implication |
|---|---|---|
| Language | `D8` LOCKED | Go |
| Two roles, one binary | SM-D7; topology under §8 | control (1/Target/cluster) + telemetry (per node); both in one process on a single host |
| Session homes at DP | SM-D24 (revises B/D16 home) | generations, report-actual, telemetry terminate at DP; CP issues CA/enrollment/lease |
| Authority | SM-D28 | 1h CP-issued lease, DP renews; full partition state machine |
| Protocol shape | B/D16 LOCKED (re-homed) | agent-initiated gRPC/HTTP2 bidi over mTLS; phases Enroll → Session → Steady → Teardown |
| Authz scope | B/D8 | desired state only for the Target mapped to this mTLS identity; no catalog enumerate |
| Compatibility | B/D10 | advertise capabilities; mismatch → “agent upgrade required” before apply |
| Collector semantics | B/D2, SM-D4 | bounded local buffer, backpressure/drop, dedupe, freshness, replay/backfill; gap semantics path-specific |
| Config apply | SM-D3, SM-D21 | `additional-location` preferred / `overwrite-in-place`; write file + restart; path discovered by pre-flight |
| Restart / divergence | SM-D15 | stage-everywhere-then-restart default; `CONFIG_DIVERGED` is explicit; agent reports per-replica facts |
| Version observe | SM-D14 | `OK` / `VERSION_DRIFT` / `VERSION_UNKNOWN` / `MISSING…`; never render against anything but observed version’s schema (**DP** renders; agent **reports** observed version) |
| Time / ordering | B/D15 | UTC; generations monotonic; agent reports clock offset |
| No MI development | `I1` | fair game: launch/mount/config file contents SM writes; not fair: MI code, build-info, new endpoints |
| Secrets | `I6` | DP never persists secret **values**; on-target file written atomically mode `0600`; hash over **refs**, not resolved values |

Where this design needs a change that contradicts a LOCKED decision, that is a new decision in `DECISIONS.md` — not a
local override (`I3`/`I4`).

---

## 3. Topology and roles

```
                    ┌──────────── Control Plane ────────────┐
                    │  CA · enrollment approval · lease mint │
                    │  revocation_epoch                       │
                    └──────────────────▲─────────────────────┘
                                       │ (DP ↔ CP; agent never needs a CP route)
┌──────── host / node ────────┐        │
│  go-agent (one binary)      │   ┌────┴──── Data Plane ────┐
│  ├─ control role (0|1)      │◄──┤  session · lease renew  │
│  │    leader-elected on K8s │   │  generations · ingest   │
│  └─ telemetry role (0|1)    │──►│  report-actual store    │
│       DaemonSet: 1 / node   │   └─────────────────────────┘
│  MI process(es) ← apply/    │
│                 scrape      │
└─────────────────────────────┘
```

- **Control role:** receives desired generations for its Target, applies config/lifecycle, reports actual.
  Cardinality: **one active control per Target/cluster**, HA via leader election on Kubernetes — **not** per node.
- **Telemetry role:** scrapes local MI endpoints (and later broker events under host-local), pushes normalized
  samples. Cardinality: **DaemonSet, one collector per node — not per pod**.
- On a single host (Docker/systemd), both roles typically run in **one process** (`AG-D1`).

---

## 4. Enrollment and identity

Per B/D16 + SM-D24 + SM-D28:

1. Operator obtains a **single-use, target-bound enrollment token**.
2. Agent generates a key + CSR and enrolls **through the DP to the CP** (agent → DP → CP). The agent never requires a
   CP network route.
3. CP signs the cert; DP relays and caches the trust bundle.
4. Agent opens an **agent-initiated** mTLS session to the DP (gRPC/HTTP2 on 443).
5. CP issues the first **lease**; DP holds/renews it. Steady-state begins with **report-actual** (not “empty desired
   state”).

**v1 cert rotation is manual** (B/D16). Authority is bounded by the **lease**, not cert TTL. Auto rotation is deferred.

**Launch-spec pre-flight (architecture §6.1):** before enrollment completes (and again **per binding before every
apply**), the control role inspects the real launch command / unit / pod spec and mounts for competing Spring property
sources and for the artifact’s declared config path. **Refuse to enroll / refuse to apply** until reconciled. SM does
not invent the path — it discovers and honours the artifact’s own (SM-D21; Cache already uses a command-line
`--spring.config.additional-location=…`).

---

## 5. Authority — lease state machine (SM-D28)

Do not invent a thinner machine. The agent **implements** this; details of wire encoding remain open.

| State | Agent may |
|---|---|
| `ACTIVE` | apply new generations · lifecycle · report |
| `RENEW_DEGRADED` | reconcile **cached** desired only; **accept no new generation**; report |
| `LEASE_EXPIRED` | stop reconciling; report-only via **separate report credential** |
| `REVOKED` | stop immediately; discard cached desired |
| `ADMISSION_BLOCKED` | next step’s `verb_ceiling` does not fit remaining lease; surface reason |
| `CLOCK_SUSPECT` | treat lease as expired (fail closed); reference clock = **DP-provided** timestamps |
| `RE_ENROLL_REQUIRED` | no apply until re-enrollment + fresh lease |

**Hard rules already decided:**

- Lease duration **1 hour**; renew at **50%** elapsed; hard stop at absolute `expires_at` (UTC).
- Lease bound to **mTLS public key / session**, not a textual `agent_id`.
- Claims include `target_id`, `binding_ids[]`, `revocation_epoch`, `audience` (DP instance), single-use renewal
  `nonce`, `capabilities[]`.
- Step admission: `expires_at − now ≥ verb_ceiling + margin`; every step has absolute deadline
  `min(step_start + max_step_duration, expires_at)`.
- After expiry: only local atomic cleanup + reporting. No restart, no remote command, no config write.
- Reports from `LEASE_EXPIRED` / `REVOKED` / `CLOCK_SUSPECT` are **labelled untrusted** in the store and **must not**
  satisfy convergence gates (including `restart-observed`).

Renewal backoff/jitter and on-disk lease persistence layout are **open** (`AG-D2`, open item below).

---

## 6. Protocol and apply path

**Channel (B/D16, re-homed by SM-D24):** agent-initiated, outbound-only, long-lived stream to the DP. Message set is
transport-agnostic. HTTP/1.1 long-poll fallback is **deferred** (h2 egress is a v1 install prerequisite).

**Safety properties the agent must implement** (exist for `host-local` only until a `dp-remote` equivalent is built):

- Monotonic **generation** numbers (never wall-clock ordering)
- **Idempotency keys**
- Persisted in-flight / generation state on the host
- Deterministic **crash-safe resume**
- Explicit cleanup of superseded partials
- Active revocation **and** lease expiry

**Config write path (control role):**

1. Receive generation for assigned bindings only (B/D8).
2. Pre-flight launch/mount/competing sources for that binding.
3. Resolve `secretRef` values only at render/write time into the on-target file — **never** persist secret values in
   agent logs or into anything that leaves the host as config history. DP hashes over references (`I6`). On-target
   file: **atomic write**, mode **0600**, owned by the service account. Canonical-path + **refuse symlink escape** of
   the declared config directory (`AG-D4`).
4. Persist → restart (strategy from binding / SM-D15; v1 default interaction is operator-driven `manual`).
5. Report-actual: rendered hash (file SM wrote), restart-observed, version introspection result, apply outcome.
   Convergence gates use **`restart-observed`**, not “effective config proven” — Cache is permanently
   `effective-unverified` (SM-D22).

**`APPLY_INDETERMINATE`:** when the agent cannot know whether a write or restart completed (crash mid-rename, process
kill mid-restart), report that state and **block further applies** for that binding until an operator confirms actual.
(Same failure class as the `dp-remote` open item; host-local must still name it.)

**Multi-replica:** platform default is stage-everywhere-then-restart (SM-D15). Control role stages on all replicas it
owns before restart waves; reports per-replica rendered version + restart-observed so the DP can surface
`CONFIG_DIVERGED`.

**Kubernetes config objects:** generation-named immutable ConfigMaps + workload reference update (SM-D25), only where
SM is the declared writer for that binding/resource. GitOps exclusion remains a customer prerequisite (open item).

---

## 7. Telemetry role

Under `infra_telemetry_source: local-collector` (SM-D23 / architecture §10):

- Discover scrape targets from **declared desired state + local runtime authority** (K8s API, Docker socket, inventory)
  — never from a cached IP.
- Scrape / poll; normalize to the transport-agnostic envelope driven by the solution’s telemetry contract.
- Bounded node-local buffer; explicit backpressure/drop; dedupe keys; freshness/staleness markers; replay/backfill on
  reconnect (B/D2). Pull/actuator path is **best-effort with marked gaps**; broker path durability is a declared
  per-signal property (SM-D27) — relevant when `solace_event` lands.
- Edge auth: collector-only network path where the MI has no process-level auth (Cache capability limit).

**v1 (Cache):** HTTP/actuator KPIs only — Cache emits **no** broker business telemetry. The Go `solace_event`
collector is **phase 2** and gated on Solace Go API maturity (`AG-D5`, open item 14).

**HA (B/D2):** Detect (self-heartbeat, investigated across cause-specific dimensions) + Recover (auto-restart +
persisted buffer). A bad collector fails alone, not the whole process (`AG-D1` isolation expectation).

---

## 8. Runtime version introspection

SM-D14: observe the running version; states `OK` / `VERSION_DRIFT` / `VERSION_UNKNOWN` / `MISSING` (with
`RESTART_EXPECTED` / `UNREACHABLE` / `NOT_EXPOSED` / `MISSING` splits).

Under `host-local` the agent can read **identity** from the runtime (image digest, JAR path, `ExecStart`, pod spec).
It must **not** require MI `build-info` or other build-plugin changes (`I1`). Where the version string is not
observable from the artifact, report `VERSION_UNKNOWN` / degrade honestly — do not invent a value.

Reported as part of **report-actual**, never folded into “desired state.”

---

## 9. Kubernetes leader election

Control role only (`AG-D3`): use the cluster’s leader-election primitive (client-go Lease) so exactly one control
instance per Target/cluster is active. On leadership loss: step down gracefully (stop apply/lifecycle); telemetry role
on the same node is unaffected. Standby must not apply generations.

---

## 10. Module shape (implementation sketch — not locked libraries)

Logical packages (names illustrative):

| Area | Responsibility |
|---|---|
| `enrollment` | token, CSR, trust-bundle accept, cert store |
| `session` | mTLS dial, stream, lease renew, state machine |
| `apply` | pre-flight, atomic write, restart verbs, resume, indeterminate |
| `report` | report-actual, untrusted labelling when lease bad |
| `collect` | scrape, normalize, buffer, push |
| `introspect` | version / process / mount observation |
| `leaderelect` | K8s control-role HA |
| `persist` | host-local durable state (`AG-D2`) |

**Library choices are not ratified.** Reasonable defaults at implementation time: `google.golang.org/grpc`,
`crypto/tls`, `k8s.io/client-go`. Logging and config libraries are deferred to the implementation plan — do not treat
Viper/zap/zerolog as decisions.

The agent holds **no SQL** and does not enforce tenant isolation itself — that is server-side (`I5`). Agent scope is
the Target/bindings in its lease and mTLS identity mapping (B/D8).

---

## 11. Testing and verification

| Layer | What |
|---|---|
| Unit | lease transitions (incl. admission, clock skew, revoke); apply resume; symlink refusal; buffer drop policy |
| Integration | enroll → session → apply → report-actual against a DP test double; collector push + reconnect backfill |
| K8s | leader failover; only one control applies; DaemonSet cardinality |
| Contract | once `.proto` exists: `buf breaking` / compatibility window (contracts README) |

**Success criteria (host-local path):** enroll + mTLS; lease renew/expire correctly; crash mid-apply resumes or
surfaces `APPLY_INDETERMINATE`; config file at declared path mode 0600; restart-observed reported; Cache KPIs scraped
and pushed with gap markers; control HA on K8s; no secret values in logs.

---

## 12. Open items (agent-blocking)

| # | Item | Blocks |
|---|---|---|
| A | Agent↔DP **`.proto` + transport state machine** (open item 6). Working **ASSUMED** sketch: [`p2-wire-assumptions.md`](p2-wire-assumptions.md) (non-binding until `contracts/`) | all agent implementation |
| B | Exact lease renewal **backoff/jitter** and **persisted-state file layout** (`AG-D2`) — **drafted** in [`p3-mechanism-gaps.md`](p3-mechanism-gaps.md) (ASSUMED) | crash-safe apply / renew |
| C | Solace **Go API maturity** for `solace_event` (open item 14) | host-local broker collector only |
| D | Ratification of `SM-D7` / `SM-D24` / `SM-D28` (and the set) | treating this design as baseline |
| E | K8s GitOps writer model with customers (SM-D25 / open item 4) | K8s `additional-location` usability |
| F | Per-MI introspection recipes (Cache first) without violating `I1` — **drafted** in [`p3-mechanism-gaps.md`](p3-mechanism-gaps.md) §7 | version accuracy |

Non-goals until later: HTTP/1.1 fallback, auto cert rotation, fleet self-upgrade, dual-collector redundancy,
`scheduled` workload support.

---

## 13. What this document deliberately does not specify

- Authoritative wire message schemas and RPC names — **UNSPECIFIED** until the contract is written under `contracts/`. A non-binding ASSUMED sketch for go-agent planning lives in [`p2-wire-assumptions.md`](p2-wire-assumptions.md); the real `.proto` supersedes it.
- DP-side lease store — backend sub-spec.
- `dp-remote` apply protocol — separate open item; not this component.
- Solution Package / telemetry contract field lists — common contracts + backend/dynamic-ui.

---

*Next step after review: independent adversarial pass (`docs/checklists/spec-review.md`), then an approved plan under
`docs/plans/go-agent/` via `superpowers:writing-plans` — only after the `.proto`/authority wire work is scheduled
with backend.*
