# P3 — Mechanism gaps (`AG-D2` + Cache introspection)

**Date:** 2026-09-02  
**Status:** DRAFT · **ASSUMED** against surrogate wire ([`p2-baseline.md`](p2-baseline.md) → [`p2-wire-assumptions.md`](p2-wire-assumptions.md))  
**Workplan:** P3 — close persist layout / renew backoff; sketch Cache version introspection (`I1`-safe)  
**Decisions:** refines [`decisions.md`](decisions.md) `AG-D2`; open item F from [`design.md`](design.md)

> Backend-published `contracts/*.proto` **supersedes** any ASSUMED persist key that mirrors wire field names.
> This document is paper mechanism only — not Go product code (workplan parallelism B).

---

## 1. Purpose

| Deliverable | Outcome |
|---|---|
| Host-local durable state layout | `AG-D2` no longer “layout TBD” |
| Lease renew backoff/jitter | Concrete ASSUMED schedule for planning/tests |
| Cache introspection recipe | Honest observe path without requiring MI `build-info` (`I1`) |

---

## 2. Data directory (ASSUMED)

| Item | ASSUMED |
|---|---|
| Root | `$SM_AGENT_DATA_DIR` (default `/var/lib/sm-agent`) |
| Permissions | `0700` dir; files `0600`; owner = agent service account |
| Secrets in clear | Lease material, report bearer, certs — **never** world-readable; never logged |
| Atomic replace | Write `*.tmp` → `fsync` → `rename` over target (same as `AG-D4` for config) |

```text
$SM_AGENT_DATA_DIR/
  identity/
    client.crt                 # mTLS client cert (PEM)
    client.key                 # private key (PEM) — 0600
    trust-bundle.pem           # overlapping CAs
    report.bearer              # report-only token (opaque)
  session/
    authority.json             # lease + epochs + DP time + ceilings (see §3)
    protocol_version           # last negotiated uint
  apply/
    cursor.json                # last applied + in-flight generation (see §4)
    staging/                   # partial writes pending rename into MI config path
  telemetry/
    buffer/                    # durable sample segments (Detect+Recover)
    buffer.meta.json           # head/tail offsets, drop counts
  agent.logseq                 # optional crash marker / boot generation (ASSUMED)
```

---

## 3. `authority.json` (lease persist)

**When to write:** after every successful `LeaseUpdate` / enroll that issues a lease; before acknowledging renew on wire if local write fails → treat renew as failed.

| Field | Source | Notes |
|---|---|---|
| `target_id`, `binding_ids[]` | lease claims | PROPOSED (SM-D28) |
| `agent_revocation_epoch`, `fleet_revocation_epoch` | lease | dual epochs |
| `issued_at`, `expires_at` | lease | UTC RFC3339 |
| `audience` | lease | DP instance id |
| `nonce` | lease | for next `LeaseRenew` |
| `capabilities[]` | lease | closed enum from assumptions |
| `dp_wall_time` | last `LeaseUpdate` | skew reference |
| `clock_offset_ms` | last computed | ASSUMED |
| `verb_ceilings` | `LeaseUpdate` admission block | map verb → duration |
| `admission_margin` | `LeaseUpdate` | ASSUMED |
| `authority_state` | local derived | `ACTIVE` / … — not trusted from disk alone; recompute on boot |
| `report_bearer_path` | pointer | `identity/report.bearer` |

**On boot:** load file → if `expires_at` past or epochs stale unknown → enter `LEASE_EXPIRED` or `RE_ENROLL_REQUIRED` per SM-D28; do **not** apply from cache until `DesiredStatePull`/`Snapshot` after renew succeeds.

---

## 4. `apply/cursor.json` (crash-safe apply)

| Field | Notes |
|---|---|
| `last_applied_generation` | Monotonic; cursor for `DesiredStatePull` |
| `last_applied_idempotency_key` | |
| `last_applied_config_sha256` | Hash of file agent wrote |
| `in_flight` | null \| object below |
| `superseded_cleaned` | generations whose staging was removed |

**`in_flight` object (when non-null):**

| Field | Notes |
|---|---|
| `generation`, `idempotency_key` | |
| `binding_id` | |
| `phase` | `PREFLIGHT` · `STAGING` · `RENAMED` · `RESTARTING` · `INDETERMINATE` |
| `step_deadline` | absolute UTC |
| `staging_path` | under `apply/staging/` |
| `target_config_path` | discovered launch path |

**Resume rules (ASSUMED):**

| Crash phase | On boot |
|---|---|
| `PREFLIGHT` / `STAGING` | Discard staging; report `ApplyProgress` resume or re-offer via snapshot |
| `RENAMED` before restart observed | `APPLY_INDETERMINATE` — do not invent success; block further apply until operator/DP confirms |
| `RESTARTING` | Re-check process identity; may close `restart_observed` or stay indeterminate |
| null in_flight | Idle; may `DesiredStatePull` if authority recovered |

---

## 5. Telemetry buffer (ASSUMED)

- Segmented files under `telemetry/buffer/` (e.g. `000001.seg`), max segment size **ASSUMED 8 MiB**.
- `buffer.meta.json`: `head`, `tail`, `dropped_samples`, `last_success_push`.
- Backpressure: when total buffer **ASSUMED ≥ 256 MiB** or age **ASSUMED ≥ 1h**, drop oldest segment; count drops; mark gaps on next `TelemetryBatch`.
- Fsync policy: meta on every segment seal; samples may batch fsync every **ASSUMED 1s** or N samples (trade durability vs disk).

---

## 6. Lease renew backoff / jitter (ASSUMED)

| Rule | Value |
|---|---|
| Nominal renew | At **50%** of lease duration (PROPOSED SM-D28) — e.g. 30 min on 1h lease |
| On renew failure | Enter `RENEW_DEGRADED` while `expires_at` not passed |
| Retry schedule | Exponential: **1s, 2s, 4s, … cap 60s** between attempts |
| Jitter | Full jitter: `delay * U(0,1)` (AWS-style) — ASSUMED |
| After `LeaseUpdate` success | Reset to nominal 50% schedule |
| Hard stop | At `expires_at` → `LEASE_EXPIRED`; stop apply; untrusted report only |

---

## 7. Cache introspection recipe (open item F, `I1`-safe)

**Goal:** Populate version observe for report-actual without requiring Cache `build-info` or new MI endpoints.

### 7.1 Observation order (Docker host-local 001)

| Priority | Probe | If success | If fail |
|---|---|---|---|
| 1 | Container/image **digest** from local Docker API / inspect of the running container | Map digest → version via **Solution Package / DP-provided digest→version table** (agent does not invent mapping) | try next |
| 2 | Image reference **only if immutable** (digest-pinned ref); never trust mutable tags alone as version | Same mapping table | try next |
| 3 | Process/JAR path heuristic (e.g. installed artifact path) | Report **evidence string** only; version string only if mapping table says so | try next |
| 4 | Optional HTTP `/actuator/info` **if already exposed** — read `build.version` if present | May corroborate | ignore absence |

### 7.2 State emission

| Condition | Report |
|---|---|
| Digest (or approved evidence) maps to declared version | `OK` |
| Maps to a different published version | `VERSION_DRIFT` |
| Evidence present, **no** mapping in package/DP table | `VERSION_UNKNOWN` (honest — do not guess from tag/`SNAPSHOT`) |
| Process missing after restart window | `MISSING` / `RESTART_EXPECTED` / `UNREACHABLE` per design.md / SM-D14 splits |
| No usable evidence | `VERSION_UNKNOWN` — **never** fabricate |

### 7.3 Hard `I1` rules

- Do **not** require Cache to add `build-info`, new actuators, or code changes.
- Do **not** treat filename `cache-engine-0.0.1-SNAPSHOT.jar` as a semver authority.
- Digest→version mapping is **data** owned by Solution Package / CP catalog / DP — agent only observes and looks up.

### 7.4 ASSUMED agent config

| Knob | Default |
|---|---|
| `introspection.docker_enabled` | true on Docker |
| `introspection.actuator_info_enabled` | true if URL in desired state |
| `introspection.mapping_refresh` | from DP desired/snapshot (not a hard-coded agent table) |

---

## 8. Mapping to wire (surrogate)

| Persist concern | Wire kinds |
|---|---|
| Lease / ceilings / nonce | `LeaseUpdate`, `LeaseRenew` |
| Generation cursor / indeterminate | `ApplyProgress`, `GenerationOffer`, `DesiredStatePull`/`Snapshot` |
| Report bearer | `EnrollResponse` → metadata on report/telemetry channel |
| Telemetry durability | `TelemetryBatch` on separate channel |

---

## 9. Open / still ASSUMED for backend or later

- Exact JSON schemas for `authority.json` / `cursor.json` (may become generated from proto later)
- Whether report bearer rotation rewrites `report.bearer` in place or dual-file overlap
- Digest→version table delivery mechanism (inline in snapshot vs separate RPCs)
- systemd / K8s introspection recipes (post-001)

---

## 10. Done when (P3)

| Criterion | Met by |
|---|---|
| `AG-D2` layout + fsync + resume rules written | §§2–5 |
| Renew backoff/jitter specified | §6 |
| Cache introspection without `I1` breach | §7 |
| Surrogate wire alignment | §8 |
| `decisions.md` `AG-D2` updated to point here | follow-up edit |

Next workplan phase: **P4** — `docs/plans/go-agent/001-host-local-vertical-slice.md`.

---

## Changelog

| Date | Change |
|---|---|
| 2026-09-02 | Initial P3 mechanism gaps against surrogate P2 wire. |
