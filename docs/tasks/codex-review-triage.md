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

---

## R5 — backend spec review (2026-08-27, branch `backend-design`)

**Scope:** `docs/design/backend/design.md` + `decisions.md` (`BE-D1`–`BE-D9`), `REQ-012`, index rows.
**Verdict given:** not plan-ready; 10 findings. **Triage: 9½ accepted, all applied in place**
(commits `96b31a1` doc corrections, and the design-amendment commit following it); the spec's draft
amendment policy applies — no code or ratification exists against this family.

| # | Finding | Disposition |
|---|---|---|
| 1 | SaaS `/mesh` is not "a packaging change" — Solace-hosted gateway → customer broker is inbound reach | **Accepted.** Claim withdrawn (`BE-D3`, design §2); SaaS relay/tunnel is design §16 question 7 |
| 2 | JWT-claim subscribe *authorization* unproven (ACLs bind to username/authz-group, limited substitution) | **Accepted — the load-bearing one.** `BE-D5` now names 3 candidate mechanisms and a gating Phase-0 broker spike (design §13) |
| 3 | Event identity drops `SM-D23` binding/placement | **Accepted with nuance.** Health + rollout are placement-scoped; config-state/version stay instance-scoped invalidations **deliberately** (design §3, stated not silent) |
| 4 | `broker-push` renames the canonical `solace_event` kind (`UI-D14`) | **Accepted, plain error.** `BE-D9` now extends `solace_event`, incl. `SM-D27` durability evidence |
| 5 | "mesh-bridge is the only mesh client" contradicts the architecture's per-locus collector assignment (Go agent under `host-local`) | **Accepted as unflagged conflict.** Now flagged; central-collection supersession routed as question 8 with `AG-D*` owners; v1 unaffected |
| 6 | Event catalog diverges from the closed vocabularies | **Half accepted, half refuted.** The quoted health line ("GREEN/RED/YELLOW/GRAY/NOTFOUND") **does not exist in the spec** — line 88 used the engine's `HealthRollup` keys throughout (verified). Valid residue fixed: full five-state ladder, four NOTFOUND sub-states enumerated, key-space named |
| 7 | JWKS signing-key lifecycle absent | **Accepted.** Persistence, overlapping rotation, refresh-window runbook parameter, negative tests (`BE-D5`, design §11/§14) |
| 8 | `REQ-012` solutioned the requirement | **Accepted.** Requirement rewritten to intent (bounded honest staleness); mechanism stays in `BE-D*` |
| 9 | Wrong open-item pointer (23 vs 24); ticket/stream verbs missing from the catalog | **Accepted.** Pointer fixed; `ticket mint` / `stream grant+revoke` verbs routed to the `D10` amendment paper |
| 10 | SDK token renewal reconnects (`updateAccessToken` → "updated/reconnected") | **Accepted, verified in SDK source.** Renewal restated as a planned reconnect reusing the re-backfill path; duplicate/gap test added |

**Pattern note for the next round:** finding 6 partially fabricated its quotation — the reviewer's
conclusions were directionally useful but its citation was invented. Same lesson as R1–R3 in the other
direction: verify the reviewer's citations against source too, both ways (`L2`).

### R5.1 — verification pass (2026-08-27)

All ten R5 fixes **confirmed solved** by the reviewer (F2 and F5 as gates/flags, which is what they are).
Three residual findings, **all accepted and applied**:

| # | Finding | Disposition |
|---|---|---|
| 1 | Stale-discard claimed via `generation`/`ts` while four catalog events carried neither; `B/D15` 🔒 forbids wall-clock ordering | **Accepted.** Common event envelope added (design §3): monotonic per-entity `stateVersion` is the only ordering/stale-discard input; `occurredAtUtc` is display-only; envelope defined once in the AsyncAPI contract |
| 2 | "Ready for plan with the spike first" understated — the walking skeleton's auth/ticket work is also gated on the `D10`/identity decision (open item 24) | **Accepted.** Design §13 restated as **gate-first**: G1 broker spike (mesh-access tasks), G2 `D10` closure (all auth-bearing tasks), G3 envelope-before-AsyncAPI (closed in-spec). Gate-independent work (contracts, scaffolding, envelope) proceeds in parallel |
| 3 | Rationale wording overgeneralized "the MIs' telemetry travels on the mesh" | **Accepted.** `BE-D2` and `REQ-012` narrowed to push-mode MIs (Cache is HTTP-polled) |

**Standing verdict adopted:** the implementation plan is written gate-first; G1 and G2 are its first
tasks, and no task depending on them is scheduled ahead of their outcomes.

### R6 — plan 001 review (2026-08-27)

Verdict given: gate-first shape right, not execution-ready — 7 findings, **all accepted and applied**
(the plan file was revised in place; it is unexecuted, so no supersession concerns):

| # | Finding | Disposition |
|---|---|---|
| 1 | Spike happy-path had the **browser publishing** a notify event — validating a topology where a compromised session forges health notices | **Accepted, the serious one.** Publishing moved to a basic-auth internal identity (`sm-internal`, the only publish-capable ACL); required negative added: a browser OAuth token can never publish to any `sm/v1` topic |
| 2 | Envelope correction unpropagated: design §9 failure table still said `generation`/`ts` discard; `BE-D4` body still described the old payload | **Accepted.** Both lines patched to the §3 envelope / `stateVersion` |
| 3 | Event schema didn't constrain per-event **entity** identity — a health event without `placementId` validated, silently undoing the R5 fix | **Accepted.** Per-event entity constraints added (placement-scoped require it, instance-scoped forbid it, catalog forbids instance identity) + two negatives |
| 4 | `wrong-topic-version` negative was **false-green** — rejected as an unknown property, not as an `sm/v2` address | **Accepted.** Replaced with an `expected-topics.json` manifest: the validator derives each fixture's topic per design §4 and compares; `bad-topics.json` proves the deriver refuses `sm/v2` |
| 5 | Task 10's IT depended on a broker user no task provisioned | **Accepted.** `sm-internal` created in Task 1's committed `provision.sh`; Task 10 references it and forbids ad-hoc broker users |
| 6 | G1 ran on plaintext `ws://` while the design path is `wss://` | **Accepted.** nginx TLS-terminates with the webAppSDK repo's cert tooling from the start; all spike tests run `wss://` |
| 7 | Signing-key **persistence across restarts** required by BE-D5 but untested | **Accepted.** Issuer persists keys to disk; restart-persistence test added; result doc records the verdict |

**Pattern note:** findings 1 and 4 are the same failure class the frontend rounds hit — a test that
passes without proving the property (guard escape / false green). Plan tests get the same adversarial
"prove it fires" treatment as guards.

### R7 — post-execution branch review (2026-08-28)

**Scope:** the whole executed `backend-design` branch — plan 001's Results, `contracts/events/`, the Maven reactor
(`mesh-events`, `dp-core` mesh bridge, `arch-tests`), and the index/register rows that describe them.
**Verdict given:** *"PR reasonable, local merge premature"* — recorded for Parikshit's decision, not acted on here.
Five findings, **all verified by the controller before triage**; **all five accepted**, two of them with the
reviewer's framing narrowed. Applied in-branch rather than handed to plan 002, because two are code defects in
already-reviewed code and the other three are labels that are wrong today.

| # | Finding | Disposition |
|---|---|---|
| F1 | **Java producers can publish schema-invalid events.** Only the envelope was validated in Java; the per-event branch constraints (entity-kind pairing, required/forbidden entity ids, required payload keys, closed payload key sets, closed enums) lived only in the JSON Schema, which no Java producer runs. An `instance.health` with `entity.kind=instance` and no `placementId` would publish | **Accepted — the load-bearing one, and a known-and-recorded landmine.** Tasks 9 and 10's reviews both logged "payload-shape conformance in Java is proven for the golden case only" and left it to plan 002; R7 is right that a recorded landmine on the *contract* side is not the same as an accepted one. New `NotifyEventShapes.validate()` hand-mirrors all six branches (no JSON-Schema engine dependency — the `NotifyTopics` pattern, not a new runtime dep), golden-tested against the same `contracts/events/fixtures/` and `negative/` documents the Python validator uses (`UI-D15`: one fixture suite, two implementations). Wired into `JcsmpEventPublisher.publish()` on the **loud/propagate** side, beside `NotifyTopics.topicFor` and outside the try: a shape defect is an identity defect (the R5 fix undone) or an `I6` leak, and neither may degrade into a dropped notice. 51 new tests |
| F2 | **G3 is described as closed while `contracts/events/README.md` lists four Known gaps** | **Accepted as a labeling fix; the conflation pushed back on.** G3's scope is the *envelope* — common envelope, `stateVersion`-only ordering — and that is closed and executable. The four items are **catalog vocabulary** already routed to the `BE-D4` amendment (quarantine topic absent from `BE-D4`'s list; rollout `step` has no canonical home; §3's rollout row omits `placementId`; `packageVersion` untyped), not envelope defects, and reopening the gate over them would be the wrong instrument. What was genuinely wrong is that "closed" was stated without its scope: the plan's G3 line and `CLAUDE.md`'s index phrase now say **closed for the envelope**, name the four items, and warn that generated client/server types are not settled until the amendment lands |
| F3 | **Swallowed drops have no signal** — the log-and-drop contract had nothing counting the drops | **Accepted, narrowed by F1.** With shape defects moved to the loud side, the swallow class shrinks to genuine transport failures plus Jackson serialization failures. `JcsmpEventPublisher` gains `droppedCount()`, incremented at **every** swallow site — the `publish` catch, `JcsmpTransport.accept`'s `JCSMPException` catch, and the asynchronous error handler (the one site with no synchronous trace at all). A plain `AtomicLong`, not a metrics dependency: plan 002 chooses the metrics stack and **must** expose this. Serialization failures stay on the drop side per the Task-10-review ruling (security-bearing fails loud; transport and serialization degrade) — but the residual silent-drop-with-committed-state case is now exactly one: **shape-valid yet non-serializable**, reachable solely through `catalog.package`'s deliberately untyped `packageVersion` (Known gap 4). The recorded transactional-outbox upgrade behind the `EventPublisher` seam is where that closes |
| F4 | **Stale ratification-matrix row** — `DECISIONS.md`'s `BE-D*` row still said `BE-D5`'s subscribe-authorization mechanism was unproven and gated on a spike, after the spike ran | **Accepted.** Row updated: the G1 spike ran 2026-08-28 and chose candidate (a), the substitution-variable ACL (`spike-authz-result.md`); `BE-D5` ratification is now blocked by **two named decisions with the architect group** — multi-tenant composition, and the notify-tier exception shape. The SaaS browser↔mesh clause is unchanged and still open |
| F5 | **Undeclared Python dependencies** — `run-checks.sh` imports `jsonschema`, `referencing` and `PyYAML` with nothing declaring them; a fresh clone got a bare `ModuleNotFoundError` | **Accepted.** `contracts/requirements.txt` added (floors read from the installed distributions, not recalled — `I2`), plus a **step-0 preflight** in `run-checks.sh` that names `pip3 install -r contracts/requirements.txt` and **fails the run** — deliberately not a skip, because steps 1–6 and 10 cannot run at all without them (`L7`) |

**Pattern note.** F1 is the third appearance of the same class in this branch: a constraint that exists in the
contract, is *recorded* as unenforced on one side, and is then treated as handled because it was written down.
R6's findings 1 and 4 were its test-side twin (a check that cannot fire). The rule this round adds: **a landmine
recorded against code that already exists is a defect, not a plan-002 input** — deferral is legitimate only for
work the branch never claimed to have done.

### R8 — skeptical review of the R7 fix wave (2026-08-28)

**Scope:** the R7 wave's own output — `NotifyEventShapes` and its suite, the publisher drop counter, the
`contracts/` preflight, and the four labels R7 corrected — read against the code rather than against R7's
description of it. **Verdict given:** *"PR yes; local merge only after this wave"* — recorded for Parikshit's
decision, not acted on here. Nine findings, **all accepted**; two are measured live code defects that escaped
R7's own guard, the rest are stale or unfalsifiable claims. Applied in-branch, on the R7 rule: a defect in code
the branch already claims to have reviewed is a defect, not a plan-002 input.

| # | Finding | Verdict | Disposition |
|---|---|---|---|
| F1 | **A null-valued required payload key survives validation and is then deleted by the serializer.** `NotifyEventShapes.payload()` tested `containsKey`, while the class javadoc says *"present means non-null"*; `EventJson`'s mapper is `JsonInclude.Value.construct(NON_NULL, NON_NULL)` — **content** inclusion — so a null map value is stripped at write time. Measured: `payload={"solution":"cache","packageVersion":null}` passed `validate`, emitted `{"payload":{"solution":"cache"}}`, and the Python validator rejected it | CRITICAL | **Accepted.** Presence is now a non-null value, with the message distinguishing *null* from *missing*. Nine new parameterized cases (one per required payload key of every branch, generated not hand-listed) plus a test proving the serializer really does delete a null value, so the rule's stated reason cannot go stale. At the publish site: a **wire-honesty** assertion over all six fixtures — the emitted payload's key set must equal the validated envelope's — plus a `catalog.package` / `packageVersion: null` case that must throw with the transport seeing nothing. `contracts/events/README.md` Known gap 4 records the one **deliberate Java/Python asymmetry** this creates (a literal JSON `null` validates in Python; Java refuses to construct what it cannot faithfully serialize), pending the `BE-D4` amendment |
| F2 | **The envelope's `producer` enum is enforced nowhere in Java.** `envelope.schema.json` declares `enum: [cp-core, dp-core]`; `EventEnvelope`'s compact constructor only null-checked it and `NotifyEventShapes` ignored it, while two documents claimed the constructor mirrored the schema. Measured: `producer: "go-agent"` publishes from Java and Python rejects the same document | MAJOR | **Accepted.** The two-member set is enforced in the compact constructor, beside the other envelope constraints, copied from `envelope.schema.json` and citing it (`I2`). Negative tests on both paths — direct construction and `EventJson.reader()`, the latter asserting `ValueInstantiationException` with a cause naming `producer`, matching the existing Jackson-path pattern. `NotifyEventShapes`' javadoc enumeration of what the constructor mirrors is corrected |
| F3 | **Two of the three drop-counter increments were untested and provably deletable.** Only the `publish` catch had a test; `JcsmpTransport.accept`'s `JCSMPException` catch and `DirectPublishEvents.handleErrorEx` were reachable only from the compose-gated IT, which skips. A mutation run deleted both and the build stayed green — while three texts claimed coverage of *every* swallow site | MAJOR | **Accepted, and this is the R7 wave's own instance of the class R6 named.** Both inner classes are now package-private with seam constructors taking the send behaviour / the shared `AtomicLong`; the production constructor wires the real JCSMP calls into the same shape, so the tests exercise the real catch rather than a stub of it. Two unit tests added; deleting either increment turns the named test red (verified by re-running the mutation). The three coverage claims are now true and left as written; this row is the correction note |
| F4 | **`BE-D5`'s own family-status row and body still said the mechanism was "gated on the spike"** after the spike ran and R7 had corrected the central matrix | MAJOR | **Accepted.** The family row now mirrors the central matrix; the body gains a dated **"G1 result (2026-08-28)"** paragraph — spike ran, candidate (a) chosen, result document cited, ratification blocked instead by the two named gaps. `I3`: the three-candidates paragraph is **not rewritten**; status metadata is added around it |
| F5 | **Plan residual and triage overclaims falsified by F1/F2.** The plan's struck-through *"payload-shape conformance proven for the golden case only"* was written as closed while two wire escapes remained | MAJOR | **Accepted.** The plan's residual now names both mechanisms this wave closed (null-stripping, producer enum) so the record shows they existed and are closed, rather than the claim silently becoming true. This R8 section is the other half. **R7's *"mirrors all six branches"* was true at branch level and false at wire level** — the correction is stated here rather than by editing R7's row |
| F6 | **Java negatives were hand-named while the Python validator globs the directory** — a negative added to `contracts/events/negative/` was silently untested on the Java side, with nothing saying so | MINOR | **Accepted.** `NotifyEventShapesTest` globs `negative/*.json` against a per-file expected-reason map and **fails on an unmapped file**, mirroring `validate-events.py`'s `NEGATIVE_REASON` guard and its *"no declared rejection reason — add one"* failure. `bad-topics.json` is excluded exactly as the Python side excludes it. The pinned non-defect (`missing-state-version` deserializes to 0) is kept as its own mapped entry behind a named sentinel |
| F7 | **The two `BE-D5` blockers had no open-item numbers**, so every citation restated them in prose and they were absent from the register's own list | MINOR | **Accepted.** Open items **32** (multi-tenant composition of the substitution mechanism) and **33** (notify-tier exception shape) added to `DECISIONS.md` — each with what is unresolved, why it blocks (`BE-D5` ratification and the provisioning runbook), decided-by (architect group), pointing at `spike-authz-result.md`. The `BE-D*` matrix row now cites the numbers instead of restating the prose |
| F8 | **The `contracts/` preflight checked importability, not the declared floors** — `requirements.txt` states `>=` versions nothing compared against, so an older `jsonschema` imported cleanly and failed step 1 with a schema traceback naming nothing about versions | MINOR | **Accepted.** Step 0 parses `requirements.txt` and compares each floor against `importlib.metadata.version()` (pure stdlib — reaching for `packaging` would add a fourth dependency to the check that makes the first three checkable), failing with actual-vs-required, and the install hint is script-relative. `>=` floors stay — the header's argument stands — but the floor is now **checked** |
| F9 | **Residual G3 / count staleness** — design §13's G3 row still said "closed in-spec" without the envelope/catalog scope R7 had added everywhere else, and the plan's Task-11 sweep table read as current | MINOR | **Accepted.** The G3 row carries the *"(envelope — the catalog carries Known-gap items pending the `BE-D4` amendment)"* qualifier, consistent with `CLAUDE.md`'s wording; the sweep table is date-stamped as of Task 11 and marked superseded by the R7/R8 notes, with its historical numbers left intact |

**Pattern note.** F1 and F2 are the same failure with two faces: **a mirror asserted to be complete, verified only
at the level it was written at.** R7 proved `NotifyEventShapes` matched the schema *branch by branch* and stopped
there — nothing compared the bytes that actually left the process, so a serializer setting could delete a field
the validator had just required, and a whole envelope-level enum could be absent from the mirror without any test
noticing. F3 is its test-side twin, and the third appearance in this branch of *a guard nothing proves can fire*.
The rule this round adds: **a claim about what reaches the wire must be tested against the emitted bytes**, not
against the component that believes it.
