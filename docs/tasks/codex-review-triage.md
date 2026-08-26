# Codex review — triage

**Review date:** 2026-08-10 · **Verdict given:** REJECT, not a ratifiable baseline
**Triage status:** ✅ all agreed findings fixed (`SM-D20`–`SM-D27`).

> ⚠️ **Historical record — read with the later rounds.** Two positions taken below were **overturned by round 2/3
> review**: the recommendation to adopt `FM_CONFIGURATION_TEMPLATE` as *the reference model for the schema registry*
> (its version columns are unused — `fetchTemplate` has no version predicate and `IConfigurationTemplate` omits both
> fields, so it is a **legacy adapter input** only), and the "contract-tested by launching the real artifact" test
> (Spring ignores unknown properties, so it detects nothing — replaced by a bidirectional key-set contract). Current
> positions live in `../design/DECISIONS.md` §10; this file records how we got there.

## Verification first

I re-verified every factual claim before agreeing to anything. **All of Codex's checkable claims hold**, including
the ones about source I had never read:

| Claim | Verified how | Result |
|---|---|---|
| Cache Dockerfile hard-codes `--spring.config.additional-location=/app/external/spring/config/` | read `solace-cache-engine/Dockerfile:12` | ✅ **true** — it's in `ENTRYPOINT`, i.e. a command-line arg |
| Cache has no `spring-boot-configuration-processor`, no generated metadata | `grep` on `pom.xml`; `find` for `spring-configuration-metadata.json` | ✅ **true** — neither present |
| `CacheEngineProperties` carries credentials; no Spring Security | read the class; `grep` `pom.xml` | ✅ **true** — `vpn`, `username`, `password`; no `spring-boot-starter-security` |
| `FM_CONFIGURATION_TEMPLATE` holds real UI/config metadata | read `file-mesh-schema.sql:3531` | ✅ **true — and richer than Codex said** (see A3) |
| File Mesh permits `DIRECT` Solace delivery | `grep` schema | ⚠️ **true but default is `PERSISTENT`** (see C1) |
| File Mesh runs PostgreSQL 13 | `docker-compose.yml:7` | ✅ **true** — `image: postgres:13` |
| `DETACH PARTITION … CONCURRENTLY` is PG 14+ | WebFetch PG 14.0 release notes | ✅ **true** — listed under Partitioning |
| Argo `ignoreDifferences` doesn't prevent overwrite on sync | WebFetch Argo sync-options | ✅ **true — and worse** (see A7) |
| `metrics_raw` has no `tenant_id` | read `design/architecture.md` | ✅ **true** — only the `series` dimension has it |
| Proposed SM-D2 marks locked B/D6 + D16 `RETIRED` | read `design/DECISIONS.md:87-88` | ✅ **true** |
| `sync-conventions.sh --check` exits 0 with all repos missing | ran it | ✅ **true** |
| A mistyped mode enters the write path | ran `--chek` | ✅ **true** |

---

## A. Agreed — blocking. Must be fixed before any code repo or implementation

### A1 · Effective-config hashing is broken (Codex #1) — CRITICAL
`/actuator/configprops` sanitizes values to `******` by default, so two different effective configurations hash
identically. Unsanitizing is worse: Cache's config carries `password`/`username`/`vpn` and Cache has **no Spring
Security at all**, so an unsanitized actuator would expose broker credentials on an unauthenticated endpoint — which
would also violate our own invariant `I6`.

**Correction to Codex's proposed fix.** It suggests "verify a non-secret allowlist through a protected management
path." Cache **has no protected management path**, and adding one is MI development, which `HC1` forbids. So this is
not fixable as designed. The honest resolution is a **capability limit**:
- Verify what SM controls: the file it wrote (hash), plus a **launch-source pre-flight** that detects competing
  property sources.
- Verify *effective* values only for a declared **non-secret allowlist**, and only where the solution exposes a
  protected actuator. Otherwise report those fields as **`effective-unverified`**.
- Never read unsanitized `configprops`. Ever.

This weakens SM-D3's drift-detection story, which several other decisions lean on. Needs saying out loud rather than
papering over.

### A2 · The `launchVar` model fails on the v1 target solution (Codex #2) — CRITICAL
Cache's `ENTRYPOINT` passes `--spring.config.additional-location=/app/external/spring/config/` on the **command
line**, which outranks the `SPRING_CONFIG_ADDITIONAL_LOCATION` environment variable SM-D19 specifies. SM would write
`/etc/sm/...` and Cache would keep reading `/app/external/...`.

This is the trap **my own §6.1 documented**, and the design specified a mechanism that walks straight into it.

**Silver lining worth capturing:** Cache *already has* an external config location, so SM writes to
`/app/external/spring/config/` and the launch-spec change for Cache drops to **zero**. The fix makes SM-D3 *cheaper*,
not more expensive: replace the generic `launchVar` with a **per-artifact, per-runtime declared config path,
confirmed by pre-flight against the actual launch command** — never assumed.

### A3 · Locked D3 is unimplementable, and there is existing prior art we ignored (Codex #3) — CRITICAL, and the most valuable finding in the review
Cache has no annotation processor and no generated metadata, so "schema generated from the solution's build" (D3)
has **no input** — and adding the processor is `HC1`-forbidden.

**Codex understated the second half.** `FM_CONFIGURATION_TEMPLATE` is not merely "config metadata" — it is a
working, production implementation of the platform's **#1 objective**:

```
COMPONENT_TYPE · CONFIGURATION_FIELD · CONFIGURATION_DESCRIPTION
UI_TYPE · UI_DATA_SOURCE · UI_TYPE_META      <- the renderer contract
SECTION · SECTION_ORDER · SUB_SECTION · SECTION_FIELD_ORDER  <- layout/grouping
DEFAULT_VALUE · CONFIG_FILE_RELATIVE_PATH
MIN_VERSION · MAX_VERSION                    <- version-aware rendering
```

`MIN_VERSION`/`MAX_VERSION` solve the version-aware rendering problem `architecture.md` §7.2 designs from scratch.
`UI_TYPE` + `UI_TYPE_META` are the schema-driven renderer contract §14 designs from scratch. **File Mesh already
shipped this.**

**Recommended fix, stronger than Codex's:** supersede D3 with **source adapters** (curated contract for Cache,
contract-tested by launching the real artifact; `FM_CONFIGURATION_TEMPLATE` + value tables for File Mesh) **and
study `FM_CONFIGURATION_TEMPLATE` as the reference model for the schema registry itself**, not just as one input.

### A4 · The decision register violates its own status rules (Codex #4)
Proposed SM-D2 marks locked B/D6 and D16 `RETIRED`; proposed SM-D4 "supersedes" locked D5. My own status legend says
PROPOSED is non-binding and LOCKED stands.

*Severity note: I'd rate this **MAJOR**, not CRITICAL — it cannot cause a runtime failure. But Codex's scenario is
legitimate and gets worse with four teams: two developers can both claim compliance while building different
systems.*

**Fix:** restore the locked status and re-label as `LOCKED — proposed supersession by SM-Dx`. Nothing becomes
`RETIRED`/`SUPERSEDED` until ratified. Add a mechanical check so this can't recur.

### A5 · Agents terminate at the wrong plane, and the protocol doesn't exist (Codex #5) — CRITICAL
Sharp catch I missed. SM-D1 makes the **DP** the config owner and the agent's home; B/D16 defines enrollment, CA,
lease and desired-state against the **CP**. After the SaaS split the CP holds no config yet is expected to deliver
generations and renew agent authority. And `design/common/contracts/README.md` calls the protocol **"specified"**
while referencing a `.proto` that does not exist — a false-green in my own document.

**Fix:** define **agent ↔ DP** as the operational protocol, with CP-issued authorization the DP validates (this is
the token-audience seam, now load-bearing). Assign ownership of CA, enrollment, lease, revocation and generation
state explicitly. Downgrade the contract row to **"concept locked, protocol unspecified"** until the `.proto` and
state machine exist.

### A6 · Target model is at the wrong cardinality (Codex #6) — CRITICAL
Direct hit on **SM-D12, which I designed and recommended.** `config_mechanism` and `infra_telemetry_source` are
per-Target, but they are properties of a *solution on a target*. One Kubernetes cluster hosting Cache (HTTP poll,
one config path) and File Mesh (broker events, a different config path) **cannot be expressed** without duplicating
the Target and its credentials. Target ownership scope is also missing despite `target.use` in D10.

**Fix:** Target keeps **location, driver, tenant/trust domain, control locus, ownership scope**. Move
**telemetry acquisition, config mechanism, selectors and lifecycle policy to an Instance↔Target binding.**

### A7 · Kubernetes apply is incompatible with the GitOps model (Codex #7) — CRITICAL
Verified against Argo docs, and **worse than Codex stated**: `ignoreDifferences` affects only diff computation —
*"during the sync stage, the desired state is applied as-is"*. `RespectIgnoreDifferences=true` is required, **and
even then it only applies when the resource already exists in the cluster** — so first creation still overwrites.

**Codex's reframing is the key insight:** I listed "immutable ConfigMaps cannot be patched" as a *constraint*. It is
actually the *solution* — use **generation-named immutable ConfigMaps** and update the workload's reference, which
also gives deterministic rollback that mutable ConfigMaps cannot. Plus: either GitOps PR/commit integration is the
sole writer, or explicit field ownership must cover **both** the ConfigMap and the workload reference. Test
Deployment and StatefulSet separately.

---

## B. Agreed — fix in the docs, not blocking repo creation

| # | Finding | Assessment |
|---|---|---|
| **B1** | **Counter rollups wrong after late data** (Codex #8) | Correct. My `delta` includes carry-over `first − prev.last`, so a late sample in bucket N invalidates N+1 and all upper tiers, but correction only reprocesses "touched buckets". Codex's alternative — delta as the sum of within-bucket positive increments — removes the cascade but **loses the increment between buckets**; that trade needs stating, not just adopting. Either way: cascade invalidation upward, and add tests for multiple resets, boundary resets, gaps, out-of-order arrival. |
| **B2** | **Metrics schema not tenant-safe or event-safe** (Codex #9) | Correct, and it's a **self-contradiction**: §3.1 says `tenant_id` on every table in both schemas; `metrics_raw` has none. The archive copies a whole monthly partition while the same document claims tenant-partitioned archives. `(series_id, ts)` also cannot separate two same-timestamp business events (two file-complete events). Fix: carry tenant + placement identity in facts, composite tenant FKs, store events separately keyed on source message/event ID, one archive manifest per tenant+range. |
| **B3** | **No PostgreSQL version pinned; retention self-contradictory** (Codex #10) | Correct on both. `DETACH … CONCURRENTLY` needs **PG 14+**, and the reference deployment runs **postgres:13** — I mandated a mechanism unavailable on the version in use. Separately, six months is described as a minimum, as reducible, *and* as not guaranteed. Pin a major version (**≥14**); make six months either an enforced capacity prerequisite or an explicitly negotiable target — not both. The ~100 bytes/row estimate should carry WAL, bloat, backup and rollup workspace. |
| **B4** | **Governance is false-green** (Codex #12) | Correct, and the `sync-conventions.sh` part is the sharpest: **`--check` exits 0 when all four repos are missing**, so CI passes on a repo that doesn't exist — the exact false-green the script was built to prevent. A mistyped mode (`--chek`) also falls into the write path. Fix: validate the mode, require explicit component paths in CI, and fail on missing repos, unknown modes, duplicate blocks, marker mismatch and empty extraction. |

---

## C. Partially agree — corrections to Codex

### C1 · "Broker path is durable" — right conclusion, overstated evidence (Codex #11)
Codex says the claim is *false* because File Mesh permits `DIRECT`. Verified: `SOLACE_MESSAGING_MODE` does offer
`DIRECT`/`PERSISTENT`/`NON-PERSISTENT` — but **`defaultValue` is `PERSISTENT`**. So the honest position is *"durable
by default, configurable to non-durable"*, not *"not durable"*.

The finding stands regardless — my blanket claim is unconditional and shouldn't be. **Durability must be a declared,
verified property per signal** (persistent delivery, durable endpoint, spool quota, acknowledgement, dedupe, replay),
not an assumption. I'd also accept the related catch: SM-D4's "business metrics **always** broker" is absolute in
`DECISIONS.md` but conditional in `architecture.md` ("where the MI already does so"). Cache has no broker business
metrics at all. The two need reconciling to the conditional form.

### C2 · "Review marked complete while requirements are empty" — mild misreading
`docs/tasks/todo.md` marks the *spec review* complete, which it is. It does not claim requirements are done — they're
listed under "Next up — blocking others". The substance still stands: **a baseline with zero requirement entries
shouldn't present itself as reviewed**, so I agree with the fix (mark the baseline `UNBASELINED`) while noting the
specific inconsistency Codex cites isn't quite there.

---

## D. Disagree

### D1 · "Delete the four component index stubs"
These are the **index the local-design model depends on** — the model you chose so design+code move in one PR.
Deleting them removes the only central pointer to where each component's design lives. They should be *required to
carry a real summary* once a design exists, not deleted. **Recommend: keep, with a rule that an index without a
summary is incomplete.**

### D2 · "Remove model/session-cost guidance from the synced workflow block"
This is **your explicit instruction from the previous turn** — `executing-plans` on a lower-cost model, and the agent
must ask about session mode before executing. Codex had no way to know that. **Recommend: keep. Your call, not
Codex's.**

### D3 · "Delete the four component runbook stubs" — **agree, actually**
Listing this under Disagree only to be clear I'm not defending it: `runbooks/backend/README.md` and friends contain
no runbooks and are pure ceremony. **Agree — collapse to `runbooks/README.md` + `runbooks/common/`, and create a
component folder when its first real runbook exists.**

### D4 · "Generate traceability and the decision index; don't maintain manually" — agree in principle
Good idea, but it's tooling to build, not a doc fix. **Recommend: accept as a task, not a blocker.**

---

## E. Your decisions that Codex dissents from — not mine to overturn

### E1 · Monorepo instead of four repos
Codex recommends a monorepo until contracts and release boundaries stabilise, arguing that **path-scoped CI plus
independent artifacts give independent release cadence without cross-repo PR choreography**. That is technically
sound and is a stronger version of the concern I raised earlier (four repos discard ArchUnit's cross-module
enforcement).

**You chose four repos.** I'm flagging the dissent, not re-litigating it. Note it interacts with A5 and B4: with four
repos, the contract publishing mechanism and a working drift check become prerequisites rather than niceties.

### E2 · Java backend
Codex agrees Java is defensible on merit but says **don't scaffold it while D6/D15 still locked to Node** — which is
really finding A4 (status hygiene), not a stack objection. Resolvable by ratifying, not by re-deciding.

---

## Proposed sequence, if you accept the above

1. **A4 first** — restore locked statuses. Cheap, and everything else is easier to reason about once the baseline
   stops contradicting itself.
2. **A2 + A3 together** — both are "we assumed something about the real MIs that isn't true." Requires reading Cache
   and File Mesh properly, which the design never did. **A3 may change the schema-registry design materially.**
3. **A6** — Target/binding split. Touches SM-D12, SM-D3, SM-D4 and the topology matrix.
4. **A1 + A5 + A7** — each needs a decision, not just an edit.
5. **B1–B4** — mechanical corrections.
6. **D3, D4, C1, C2** — cleanup.
7. Mark the baseline **UNBASELINED** until 1–5 land.

**Not started. Awaiting your review.**
