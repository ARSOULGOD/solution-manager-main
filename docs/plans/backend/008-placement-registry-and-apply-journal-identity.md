# Backend Plan 008 — the placement registry and placement-aware apply-journal identity

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lift `BE-D23`'s single-placement restriction by giving placements a real registry and the apply journal a
placement-aware identity — **both** of `BE-D23`'s clauses, not one — while keeping the `RuntimeAdapter` seam and the
Agent Gateway's typed semantic ports compatible, and without letting the gateway near `sm_data`.

**Architecture:** A `sm_data.placement` table becomes the referential home the nil-UUID sentinel currently has none
of, and `apply_step_intent`'s uniqueness gains `placement_id`. That is two changes and they are **not**
interchangeable: `BE-D23` refuses a multi-placement apply for **two independent reasons** — a placement-scoped step
collides on `replica_ref = '@placement'`, **and** a replica-scoped step collides when two placements produce the
same `replica_ref`. Fixing the first alone leaves the second, and the second is the one that bites on SSH-Docker,
where `SshDockerRuntimeAdapter` derives `replica_ref` from the container name.

✅ **Placement is a first-class level of the canonical hierarchy** (ruling **OR-2**, 2026-09-16):
`Organization → Sub-organization → Environment → Solution → Instance → **Placement**`. This plan builds the
registry for that level, and ⚠️ **OR-6 makes it a topic level too** —
`…/instance/{instanceId}/placement/{placementId}/{aspect}` — so the registry's ids are externally visible and
**immutable**, not internal keys.

**Requirements:** [`REQ-005`](../../requirements/REQ-005-three-runtimes.md) — multi-placement is what a
multi-site, multi-runtime instance needs · [`REQ-001`](../../requirements/REQ-001-unified-config-layer.md) ·
[`REQ-003`](../../requirements/REQ-003-onprem-and-saas.md) (the identity model must survive the plane split)

**Tech Stack:** Java 17 · **Spring Boot 3.5.16** · Flyway at the reactor's managed version (`sm_data` continues at
`V007`) · **PostgreSQL 14** · **Testcontainers 1.21.4** · **ArchUnit 1.5.0** · the existing DDL replay harness

**Spec:** [`docs/design/backend/decisions.md`](../../design/backend/decisions.md) `BE-D23` (both clauses) and
`BE-D13`/`BE-D19`/`BE-D20` · [`docs/design/backend/eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md)
§3.1 (the SQL block), §3.3 (admission), §8 rows 13 and 14 ·
[`docs/design/backend/agent-gateway/design.md`](../../design/backend/agent-gateway/design.md) §2.2, §3.2, §3.5, §13 ·
[`docs/design/common/contracts/console-integration.md`](../../design/common/contracts/console-integration.md) §12

---

## Global Constraints

- **Both `BE-D23` clauses, or neither.** A task that closes the placement-scoped-step collision and declares
  multi-placement supported has not read the decision. The acceptance test is two placements whose adapters return
  the **same** `replica_ref`.
- **Compatibility with both execution paths is a hard requirement**, and it is what makes this plan risky rather
  than routine:
  - **`dp-remote` adapters** — `DockerAdapter` and `SshDockerRuntimeAdapter` keep working unchanged, or the change
    to them is part of this plan and is tested.
  - **Agent Gateway typed semantic ports** — ⚠️ **measured: none of them exists.** `backend/dp-api` holds one file
    (`DpMarker.java`), there is no `agent-gateway` module in the reactor, and there is no contract kit to run. So
    the obligation is **not** "the kits still pass" (an earlier draft said that, and it was unverifiable by
    construction) — it is: **leave the nine ports `AGW-D6` documents implementable**, and in particular leave a
    future `AgentDispatchAdapter` able to sit behind the amended journal **without a second identity model**.
    Task 6's record is the evidence, and a record is all it is.
- ⛔ **The Agent Gateway may never touch `sm_data`.** No DP repository import, no JPA entity, no table access, no
  datasource, no DB role (`AGW-D2`). If this plan's identity change tempts a port toward a table, **the design is
  wrong, not the rule** — record it and stop.
- **Do not weaken an existing guarantee.** Every DDL constraint, the replay harness, the `I6` secret controls and
  the adapter contract tests stay as strong or stronger. A migration that drops a constraint to make room for a
  column must re-add an at-least-as-strong one **in the same migration**, with the refusal test to prove it.
- **`I3`** — ⛔ **No document reserves a decision id.** New decisions **allocate the next free id from the merged `DECISIONS.md`** — the one central allocator (ruling **OR-11**) — and only **after plan 005 merges and the register is re-read**, because plan 005 carries `BE-D25`/`BE-D26`.
- **`I5`** — every new query carries `TenantId`; all SQL uses bind parameters.
- **`I7`** — `sm_data` belongs to `dp-core`. No cross-schema join and no FK to `sm_control`; the placement's
  **scope** is validated by the CP/DP instance-identity protocol, not by a foreign key (see Task 6).
- **Docker/Colima is shared state.** The usual checks and serialization; a Compose project name owned by this
  worktree; clean up only its containers.
- **Every guard carries a planted-violation proof** (`L7`); a mutation that does not fire is a finding about the
  tests (`L22`).
- **Commit attribution.** Every commit ends with the session's attribution trailer.

---

## Prerequisites

1. **Plan 005's completion and plan 007 must both be landed.** This plan changes the identity the engine and the
   API already depend on; doing it first means changing it twice.
2. **Re-measure `BE-D23` before touching anything.** Its two clauses are the plan's premise; confirm both still
   hold against the DDL as it actually is (`UNIQUE (tenant_id, apply_id, step, replica_ref)` plus
   `placement_scoped_steps`), and record the measurement. **Do not accept this plan's own summary as evidence.**
3. ⛔ **Five statements about the Agent Gateway that must survive this plan unchanged.** They are restated here
   because an identity change is exactly the kind of work that erodes them by accident:
   - **It is designed, not implemented.** No `agent-gateway` module exists in the reactor; `backend/dp-api` holds
     one marker class. **Nothing in this plan may describe it as implemented, conformant, or partially built.**
   - **`AGW-2` is open: there is no `.proto` and no wire state machine.** All v1 agent work waits on it.
   - **`AGW-17`–`AGW-21` stay open and stay gated**, each blocking what `design.md` §13's own `Blocks` column says
     it blocks — not a paraphrase (see `console-integration.md` §12.2).
   - **The gateway retains no datasource, no DB role, no repository import and no `sm_data` access** (`AGW-D2`).
     Task 6's rules fence the placement repository inside `dp-core` and keep `dp-api` free of persistence types.
   - **This plan makes the journal and placement identity *compatible* with a future `host-local` adapter. That is
     all it claims.** Compatibility is recorded in Task 6's reconciliation document; it is not demonstrated, because
     there is no code to demonstrate it against.
4. **Read `agent-gateway/design.md` §3.5 and §13 `AGW-20` first.** The agent path's `status(replica)` reads a
   canonical per-replica actual-state projection **that does not exist**. This plan must not accidentally become
   that projection, and must not assume it.

---

## Design-first work that must happen before Task 1

⚠️ **Three questions this plan cannot answer by writing code.** Each is a contradiction between documents, and the
standing rule is: record it and stop that decision-dependent implementation rather than inventing semantics.

| # | Question | Why code cannot settle it |
|---|---|---|
| D1 | **Is `sm_data.placement` the same registry as `AGW-D16`'s `agent_replica`, a parent of it, or unrelated?** ⚠️ **Routed to the `AGW-D*` owners — not decided by a `BE-D` of this plan's.** A `BE-D` declaring the two registries identical would settle `AGW-D16`'s subject from outside its family, which `I3`/`I4` forbid and Task 6 Step 5 also forbids. That decision may state only **what `sm_data.placement` is**; whether `agent_replica` hangs off it is theirs. If the answer has not arrived, Task 1 proceeds on the narrow statement and Task 6 records the question. `AGW-D16` mints a two-level replica registry with a per-label grammar, live caps, one active incarnation and timestamped retirement. This plan mints a **placement** registry. A placement is a *site*; a replica is a *process at a site* — but `AGW-D16`'s registry is also DP-owned and also keyed by tenant, and two overlapping registries is how a dual identity model starts | It decides whether the agent path's replicas hang off a placement row or a parallel tree. Getting it wrong is a migration later, not a refactor |
| D2 | **What does `replica_ref` mean once two placements can produce the same string?** Either it becomes placement-qualified (a value change — `BE-D19`'s grammar and every existing row) or uniqueness moves to `(placement_id, replica_ref)` (a key change). `BE-D19`/`BE-D20` constrain the first; `BE-D23` names the second | The two choices have different migration costs and different effects on `AGW-D16`'s grammar |
| D3 | **§8 row 14 — cross-scope supersession.** An instance-wide apply admitted while a placement-scoped `STAGED` row exists leaves the elder `STAGED` although its staged bytes are wholly or partly overwritten. **Measured** in the plan 004 prototype (`MonotonicityIT`). Two candidate rules are named and neither is chosen | Multi-placement makes this common rather than a corner. Choosing silently would invent admission semantics |

- [ ] **Step 0: Record D1, D2 and D3 as decisions, allocating ids from the merged register, or stop.** No task below may begin on the part of the plan the unanswered question governs. Tasks 1 and 2 depend on D2; Task 4 depends on D3; Task 6 depends on D1.


### Ratification posture — stated, because `docs/plans/README.md` says not to start on a PROPOSED decision

[`docs/plans/README.md`](../README.md) says: *do not start a plan when it depends on a `PROPOSED` decision that
ratification could still change.* This plan does depend on PROPOSED decisions, and the posture is the same one
`BE-D16`–`BE-D18` and plans 004–005 already carry, stated rather than assumed:

- **What is settled** is the part this plan is built on: `BE-D13`'s journal is implemented and measured, and `BE-D23` is a measured restriction rather than a preference. The `AGW-D*` family, by contrast, is PROPOSED **and ratifies after `BE-D*`** — which is exactly why Task 6 produces a reconciliation record instead of editing that family.
- **What ratification would decide** is whether those decisions *stay*, not whether they may be built.
- **What is genuinely blocked** is listed in the non-goals table, and nothing in the task list depends on it.
- **A contradiction found during execution is design-first**: record it and stop the decision-dependent task.
  Do not invent semantics, relax a constraint, or write a migration around it.

---

## File Structure

| Path | Responsibility |
|---|---|
| `backend/dp-core/src/main/resources/db/migration/sm_data/V007__placement.sql` | The registry, its grammar, its caps, retirement. |
| `…/sm_data/V008__intent_identity.sql` | `apply_step_intent`'s placement-aware uniqueness, and the constraint that replaces the old one. |
| `…/sm_data/V009__placement_referential_guard.sql` | The FK the nil-UUID sentinel needs, and the sentinel's own row. |
| `backend/dp-core/src/main/java/com/solace/sm/dp/apply/placement/PlacementRepository.java` | Reads and writes the registry. |
| `…/dp/apply/placement/PlacementRegistry.java` | Enumerate a placement set for an instance; the production replacement for plan 005's `placementsOf`. |
| `…/dp/apply/engine/ApplyExecutor.java` | Multi-placement fan-out, ordering and per-placement terminality. |
| `backend/dp-api/src/main/java/com/solace/sm/dp/api/PlacementView.java` | What the gateway may see. No repository escapes `dp-core`. |
| `backend/arch-tests/.../PlacementBoundaryTest.java` | Three rules fencing the registry inside `dp-core` and keeping `dp-api` free of persistence and engine types — each with a planted violation. **Not** a proof about the gateway, which has no code to scan. |

---

## Task 1 — The placement registry

**Files:** Create `V007__placement.sql`, `…/placement/PlacementRepository.java`, `…/placement/PlacementIT.java`.

**Interfaces:**
- Produces: `sm_data.placement(tenant_id, instance_id, placement_id, kind, label, created_at, retired_at)`;
  `PlacementRepository.of(TenantId, InstanceId) → List<Placement>`, `find(TenantId, PlacementId)`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void aPlacementLabelObeysTheSameGrammarAsReplicaRef() { }            // BE-D19, one grammar
@Test void theNilUuidIsNotAStorablePlacementId() {                        // mirrors real_placements_only
    assertThatThrownBy(() -> insert(NIL_UUID)).hasMessageContaining("23514");
}
@Test void twoPlacementsOfOneInstanceMayNotShareALabel() { }
@Test void retirementIsATimestampAndTheRowSurvives() { }
@Test void aRetiredPlacementIsNotEnumeratedForANewApply() { }
@Test void enumerationIsTenantScopedAndBindParameterised() { }            // I5
@Test void theRuntimeRoleCannotDeleteAPlacementRow() { }                  // 42501
```

- [ ] **Step 2: Run; all fail.** **Step 3: Write the migration and the repository.** **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Drop the grammar CHECK → its test red. Drop the label uniqueness → its test red. Grant `DELETE` → its test red.
- [ ] **Step 6: Re-record the DDL replay baseline** and commit. `feat(dp-core): the sm_data.placement registry`

⚠️ **This replaces plan 005's `placementsOf`**, which reads `SELECT DISTINCT placement_id FROM apply_request`.
That was correct as a stopgap and is wrong as a registry: it enumerates only placements that have *already had an
apply*, so a newly declared placement is invisible until something is applied to it. Delete it in Task 5 — do not
leave two enumerators.

---

## Task 2 — `apply_step_intent`'s placement-aware identity

**Files:** Create `V008__intent_identity.sql`; Modify `…/persistence/IntentRepository.java`; Create `…/persistence/IntentIdentityIT.java`.

- [ ] **Step 1: Write the failing tests. The second one is the whole point of the plan.**

```java
@Test void twoPlacementScopedStepsOfOneApplyCoexist() {
    // clause 1: both rows carry replica_ref = '@placement', different placement_id
}
@Test void twoReplicaScopedStepsWithTheSameReplicaRefInDifferentPlacementsCoexist() {
    // clause 2 — the one an SSH-Docker fleet hits, because replica_ref is the container name
}
@Test void twoStepsWithTheSameReplicaRefInTheSamePlacementStillCollide() {
    // the guarantee must NOT be weakened: uniqueness is narrowed, not removed
}
@Test void placementScopedStepsStillRequireTheAtPlacementSentinel() { }   // placement_scoped_steps intact
@Test void stopRemainsExemptFromPlacementScopedSteps() { }                // unchanged
@Test void hashOnConfigStepsIsUnchanged() { }
@Test void attemptOpenFenceAndTheTripleFkStillHold() { }
@Test void theIdempotencyKeyIsStillGloballyUnique() { }
```

- [ ] **Step 2: Run; clauses 1 and 2 fail, the rest pass** — which is itself the measurement that `BE-D23` was right.
- [ ] **Step 3: Write `V008`.** Replace `UNIQUE (tenant_id, apply_id, step, replica_ref)` with the placement-aware key **in one migration**, and re-add every dependent constraint and FK target the old key served (`UNIQUE (tenant_id, apply_id, intent_id)` and `UNIQUE (intent_id, step)` are `apply_attempt`'s FK targets — do not disturb them).

  ⚠️ **Four migration questions [`plan-review.md`](../../checklists/plan-review.md) requires answered — answered here rather than discovered in production:**
  1. **Backfill.** Existing `apply_step_intent` rows carry no `placement_id`. Derive it from the owning `apply_request.placement_id` in the same migration — including the nil-UUID sentinel for instance-wide requests — and assert zero nulls afterwards.
  2. **Lock class.** Dropping and creating a unique constraint takes `ACCESS EXCLUSIVE` and rebuilds the index. On a small journal that is milliseconds; record the measured row count the decision is made against, and if it is large, build the new index `CONCURRENTLY` **before** the constraint swap (which cannot itself be concurrent).
  3. **Order.** The new key must exist before the old one is dropped, or a concurrent insert slips through the gap.
  4. **Rollback.** Reverting means re-narrowing the key, which **fails once any multi-placement rows exist**. Say so plainly: after the first multi-placement apply this migration is one-way. That is a normal property of an identity change, and it belongs in writing rather than in a surprise.
- [ ] **Step 4: Run green — all eight.**
- [ ] **Step 5: Prove nothing was weakened — with **one** named exception, decided in advance.** Remove the narrowed key → `twoStepsWithTheSameReplicaRefInTheSamePlacement…` red. Then run the **whole** existing `dp-core` suite. ⚠️ **Exactly one class is *expected* to go red:** `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/InstanceWideIdentityLimitIT.java`, which plan 005's Results record as pinning both halves of the identity limit against the shipped DDL and as *"expected to go red when a persistence plan grows `apply_step_intent` a placement column"* — i.e. this plan. **Invert it in the same commit** so it becomes the proof the limit is lifted, rather than deleting it, and say so in the commit message. **Any other previously-green test going red is a defect, not a consequence.** Record before/after counts and this one inversion.
- [ ] **Step 6: Re-record the replay baseline** and commit. `feat(dp-core): placement-aware apply_step_intent identity — both BE-D23 clauses`

---

## Task 3 — The referential guard for the sentinel

**Files:** Create `V009__placement_referential_guard.sql`; Create `…/persistence/PlacementReferentialIT.java`.

`apply_request.placement_id` is `NOT NULL` with the nil UUID as the explicit instance-wide sentinel, and its own
comment says the referential guard arrives *"with the placements table, §8 row 13"*. This is that task.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void anApplyRequestCannotNameAPlacementThatDoesNotExist() { /* 23503 */ }
@Test void theInstanceWideSentinelIsStillAccepted() { }
@Test void theSentinelRowItselfIsNotEnumerableAsARealPlacement() { }
@Test void placementWatermarkStillRefusesTheSentinel() { }        // real_placements_only intact
@Test void aPlacementCannotBeRetiredWhileAnApplyIsInFlightOnIt() { }
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement — and this is a choice *this task* makes.** (An earlier draft deferred it to D1/D2, which are about registry identity and `replica_ref`'s meaning and answer nothing about the sentinel's referential form.) Two shapes; pick one and record it as a new decision (next free id from the merged register): **(a)** seed the nil UUID as a reserved `placement` row of `kind = 'instance-wide'`, excluded from enumeration by a predicate with its own test — simplest, and the FK is then unconditional; or **(b)** leave the sentinel unrepresented and guard the reference with a trigger (`placement_id = NIL OR EXISTS …`), which is not a declarative FK and therefore needs a stronger refusal test. **(a) is preferred**: it keeps the guarantee declarative, and `placement_watermark`'s `real_placements_only` already proves the exclusion predicate is expressible.
- [ ] **Step 4: Run green.** **Step 5: Prove they fire** — drop the FK → the first test red; enumerate the sentinel → its test red.
- [ ] **Step 6: Re-record the baseline** and commit. `feat(dp-core): close §8 row 13 — the placement referential guard and the sentinel's status`

---

## Task 4 — Multi-placement apply in the engine

**Files:** Modify `…/apply/engine/ApplyExecutor.java`; Create `…/apply/engine/MultiPlacementApplyIT.java`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void anInstanceWideApplyFansOutOverEveryLivePlacement() { }
@Test void aPlacementScopedApplyTouchesOnlyItsOwnPlacement() { }
@Test void oneFailingPlacementDoesNotSilentlyMarkTheOthersDone() { }
@Test void perPlacementTerminalityIsRecordedAndTheRequestsOutcomeIsDerivedNotGuessed() { }
@Test void theHealthGateIsEvaluatedPerPlacementNotAcrossPlacements() { }
@Test void resumeAfterACrashMidFanOutContinuesExactlyTheUnfinishedPlacements() { }
@Test void generationMonotonicityHoldsPerPlacementWatermark() { }
@Test void crossScopeSupersessionFollowsTheRecordedDecision() {            // D3
    // asserts the rule chosen in Step 0, whichever it is — never an invented one
}
@Test void stopIsStillScopeGranularAndHasNoApiSurfaceInThisPlan() { }
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement** — the fan-out is ordered, each placement's journal rows are independent, and the request's terminal state is **derived** from the per-placement outcomes by the rule D3 recorded.
- [ ] **Step 4: Run green.** **Step 5: Prove they fire.** Make a failing placement land the request `APPLIED` → its test red. Share one health gate across placements → its test red. Kill the process mid-fan-out → resume test covers it.
- [ ] **Step 6: Commit.** `feat(dp-core): multi-placement apply with per-placement terminality and resume`

---

## Task 5 — Retire the stopgap enumerator, and keep the adapters working

**Files:** Modify `…/persistence/ApplyRequestRepository.java` **and** `…/apply/admission/AdmissionService.java` — ⚠️ **there are two enumerators, not one**: `AdmissionService.placementsOf(ScopeKey)` delegates to the repository method and `ApplyExecutor` calls the **service**, so deleting only the repository method leaves a dangling delegate; Modify `…/apply/runtime/docker/DockerPlacementCatalog.java`, `…/runtime/ssh/SshDockerPlacementCatalog.java`; Modify `AbstractRuntimeAdapterContractTest`.

✅ **Both are on the current integration baseline:** Plan 005 is an ancestor of `integration/dp-remote-demo`, so locate `placementsOf` in this worktree before editing. **Historical provenance only:** the 2026-09-15 starting measurement at `2a0047e` predated the Plan 005 merge and reported no match; `plan005-runtime-completion` / `ba7db2e` was the source then inspected to write this task. It is not a current branch prerequisite or checkout instruction.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void everyAdapterAgreesWithTheRegistryAboutWhatAPlacementIs() { }
@Test void theContractKitStillPassesForBothAdaptersUnchanged() { }
@Test void replicasAndPrepareStillAgreeAndMutateNothing() { }        // the plan 005 contract clause
@Test void aPlacementInTheRegistryWithNoContainersYieldsAnEmptyReplicaList() { }
@Test void thereIsExactlyOnePlacementEnumeratorInProductionCode() {
    // a grep-count assertion is NOT acceptable here (L12): assert by type, not by text
}
```

- [ ] **Step 2: Run; fails.** **Step 3: Delete `placementsOf` and route every caller through `PlacementRegistry`.** **Step 4: Run green** — the full `dp-core` and `arch-tests` suites.
- [ ] **Step 5: Prove it fires.** Reintroduce a second enumerator → its test red.
- [ ] **Step 6: Commit.** `refactor(dp-core): one placement enumerator, both adapters unchanged`

---

## Task 6 — Fence the placement repository, and record the Agent Gateway reconciliation

⚠️ **Deliberately *not* titled "compatibility proved."** There is no gateway bytecode to scan, so no ArchUnit rule
in this repository can prove the gateway respects a boundary; claiming otherwise is exactly the `L19` pattern this
plan's own constraints cite. What this task delivers: a **fence** around the placement repository that holds today,
and a **written reconciliation** the gateway's own plan must consume. `agwUsesDpApiOnly` and `agwHoldsNoDatasource`
stay obligations on **that** plan.

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/PlacementView.java`; Create `backend/arch-tests/src/test/java/.../PlacementBoundaryTest.java` + three planted-violation fixtures; Create `docs/design/backend/agent-gateway/RECONCILIATION-008.md`.

- [ ] **Step 1: Write the three rules and their three planted-violation fixtures.**

```java
// Scoped to the engine's own package, NOT to com.solace.sm.dp.. -- that wider package
// includes dp-api, the shared artifact the gateway imports, so the wider rule would have
// permitted precisely the leak that matters: a DP repository type surfacing in dp-api.
@ArchTest static final ArchRule onlyDpCoreReachesThePlacementRepository = noClasses()
        .that().resideOutsideOfPackage("com.solace.sm.dp.apply..")
        .should().dependOnClassesThat().haveSimpleName("PlacementRepository")
        .because("the registry is dp-core's; dp-api carries views, never repositories");

// The shared artifact must not reach into the engine either, or "typed semantic port"
// degrades into "a window onto dp-core".
@ArchTest static final ArchRule dpApiDependsOnNothingInsideDpCore = noClasses()
        .that().resideInAPackage("com.solace.sm.dp.api..")
        .should().dependOnClassesThat().resideInAnyPackage("com.solace.sm.dp.apply..",
                                                          "com.solace.sm.dp.meshbridge..");

// Five roots, matching agent-gateway/design.md §2.6's own agwHoldsNoDatasource. A lone
// javax.sql.DataSource check lets a JPA entity or a Flyway type straight through.
@ArchTest static final ArchRule dpApiCarriesNoPersistenceType = noClasses()
        .that().resideInAPackage("com.solace.sm.dp.api..")
        .should().dependOnClassesThat().resideInAnyPackage(
                "javax.sql..", "jakarta.persistence..", "org.springframework.jdbc..",
                "org.springframework.data..", "org.flywaydb..");
```

- [ ] **Step 2: Run against the fixtures; each of the three reports exactly its own violation.** **Step 3: Exclude the fixtures from the production scan.** **Step 4: Green excluded, red included — record both.**
- [ ] **Step 5: Write the reconciliation record.** A short document stating, per item, what this plan's identity change means for the Agent Gateway design — and **routing, not resolving**, anything that needs the `AGW-D*` owners:
  - `BE-D22` — §3.5's table still maps **one** `rollbackOrMarkIndeterminate`; the seam has **seven** operations (`assessRollback` + `restore`). Unreconciled.
  - `BE-D23`/this plan — the journal identity §3.5 writes against changes here. State the new shape.
  - `BE-D24` — a config location names a **writer access mode**, relevant to `materializeConfig` on the agent path. Unreconciled.
  - `AGW-D16` vs `sm_data.placement` — whatever D1 decided, written down where an `AGW-D*` owner will find it.
  - `AGW-20` — `status(replica)`'s canonical actual-state projection still does not exist; this plan does **not**
    supply it, and the `ActualStatePort` contract stays **PROVISIONAL**.

  ⚠️ **Do not edit `docs/design/backend/agent-gateway/design.md` or `decisions.md`.** That family is PROPOSED and ratifies
  **after** `BE-D*`; changing its meaning from here violates `I3`/`I4`. A reconciliation record beside it is the
  correct artifact.
- [ ] **Step 6: Commit.** `test(arch-tests): the gateway may not reach the placement registry — and the AGW reconciliation record`

---

## Task 7 — Close the plan

- [ ] **Step 1: Full gate.** `mvn -f backend/pom.xml clean verify` · `./contracts/run-checks.sh --strict` · the DDL replay harness with the baseline re-recorded and the drift reported · `python3 …/test_paths.py` · `git diff --check`.
- [ ] **Step 2: Results section** — the `BE-D23` re-measurement from the Prerequisites, before/after test counts for Task 2 step 5, every mutation with whether it fired, Docker state before and after, and every skip with its reason.
- [ ] **Step 3: Update the register.** `BE-D23` is **superseded, never edited** (`I3`): the new decision says the engine is multi-placement and names what changed. Update `console-integration.md` §12.1 and §15.3's `BE-D` id row.
- [ ] **Step 4: Independent adversarial review.** It must specifically test for: only one of `BE-D23`'s two clauses closed; a weakened constraint; a second placement enumerator; a gateway path to `sm_data`; the Agent Gateway represented as implemented or reconciled when it is neither; and an invented cross-scope supersession rule.
- [ ] **Step 5: Commit.**

---

## Scope and non-goals — stated exactly

| Not here | Why |
|---|---|
| Agent Gateway **implementation** | `AGW-2` (no `.proto`, no wire state machine) and `AGW-17`–`AGW-21` are open. This plan makes the journal *compatible*, it does not build the path |
| The canonical actual-state projection (`AGW-20`) | It is a four-part design question the `AGW-D*` family explicitly does not answer |
| `ActualStatePort` / `AgentAuditPort` durability kits | No contract to conform to until `AGW-20`/`AGW-21` close |
| A `stop` API surface | ⚠️ **The verb exists now — the surface still does not.** Ruling **OR-9** approved `instance.stop` as catalog row 36 (⛔ **an earlier revision of this row said "No verb (approval §3 item 1)"**, and §3 item 1 now says the opposite). Stop stays scope-granular in the engine and **absent from HTTP in this plan**. ✅ **Plan 007 Task 6b owns it**, assigned by ruling **OR-18** — ⚠️ **an earlier revision of this row recorded that *no* plan owned the endpoint, because plan 007's non-goals declined it while this table pointed there. OR-18 closed that gap** |
| Multi-tenancy | Open items 32/33/35 |
| `placement_watermark`'s replacement | It is correct; this plan adds the registry beside it, and the nil-UUID refusal stays |
| Kubernetes placements | `SM-D25`'s writer model is open item 4 |
