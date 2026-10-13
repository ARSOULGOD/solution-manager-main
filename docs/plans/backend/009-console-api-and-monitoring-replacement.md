# Backend Plan 009 — `console-api`, and the monitoring surface that replaces the demo's

> **⚠️ SUPERSEDED IN PART by [plan 011](011-monitoring-backend.md), 2026-10-05** (owner rulings `OR-78` (M-Q11, M-Q23) and `OR-80`
> (M-Q16), registered in `DECISIONS.md`). Plan 011 is a **DRAFT, not approved** (`OR-91`), so this plan's text stands until
> it is; **nothing below is edited** (`I3`). Plan 011 [replaces this plan exactly as listed](011-monitoring-backend.md#relationship-to-plan-009--exactly-what-is-replaced):
> **Tasks 1 and 2** (the `console-api` module and the concurrent composer) are **dropped**, because `console-gateway` hosts the
> `/api/console/v1` routes (`OR-78`); **Tasks 3 and 4** are replaced by plan 011's MW1, MW2, MW3 and MW7 (the 14 operations,
> durable metrics, dashboards), and the Global Constraint *"`metrics.export` and `metrics.forceRaw` are … Administrator-only"*
> is **inverted** (`OR-80`: the Operator holds both); **Tasks 5 and 6** (demo retirement, close) are replaced by plan 011's
> P11-9.1 and P11-10.1. Plan 007's Task 13b still waits on the replacement; its owner is now plan 011.

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the thin **`console-api`** composition module ruling **OR-10** approved, and with it the metrics,
dashboard and Explore surface the demo launcher currently serves — so that plan 007 Task 13b's retained demo
monitoring has somewhere to go and `demo_control` can finally be dropped.

**Architecture:** `console-api` is a **new plane module** in the existing backend deployable — the **third built**
(`cp-core`, `dp-core`, `console-api`) and the fourth *designed*, counting `agent-gateway`, which is designed and
unbuilt. It depends on
`cp-api` and `dp-api` **service interfaces only**, owns **no database**, performs **no cross-schema SQL**, and
serves `/api/console/v1/*` — the prefix open item 21 blocked until OR-10 closed it. It composes CP reads (package,
hierarchy, config, auth) and DP reads (apply, runtime, health, metrics) **concurrently**, and returns
`stateVersion` plus **per-surface availability/degraded** information so a partly-degraded console can say which
surface is stale instead of degrading whole.

**Tech Stack:** Java 17 · **Spring Boot 3.5.16** · **PostgreSQL 14** · **Testcontainers 1.21.4** ·
**ArchUnit 1.5.0** · React + Vite + strict TS + MUI (the frontend's existing pins)

**Requirements:** [`REQ-007`](../../requirements/REQ-007-six-month-metrics.md) — six months of metrics, raw
queryable · [`REQ-012`](../../requirements/REQ-012-event-driven-live-console.md) · `REQ-006`'s *"no endpoint is
reachable without it"*, per endpoint

**Spec:** [`DECISIONS.md`](../../design/DECISIONS.md) ruling **OR-10** ·
[`common/contracts/console-integration.md`](../../design/common/contracts/console-integration.md) §13.3 (the module
boundary) and §5.4 (the 14 console operations, each already mapped to verb and scope) ·
[`dynamic-metrics/design.md`](../../design/dynamic-metrics/design.md) · `SM-D30`/`SM-D31` (the metric catalog and
dashboard model)

---

## Global Constraints

- ⛔ **`console-api` owns no database.** No table, no migration, no datasource, no DB role, and **no cross-schema
  SQL** — not in this module and not anywhere on its behalf. That is what keeps `I7` intact and satisfies
  `UI-D13`'s *"two reads assembled, never one query"*.
- ⛔ **It depends on `cp-api` and `dp-api` only** — never on `cp-core`, `dp-core` or their data-access packages.
  An ArchUnit rule with a planted violation enforces it (Task 1).
- **CP owns** package, hierarchy, config and auth data; **DP owns** apply, runtime, health and metrics data.
  A read that needs both is **composed**, never joined.
- **Broker consumers persist into the owning plane**, and ⛔ **a broker notice only triggers a refetch** — a notice
  is never the write, and no consumer writes the other plane's schema.
- **Reads compose concurrently.** Serial fan-out is the latency `UI-D13` warns about; the point of the module is to
  avoid it.
- **Every response carries `stateVersion`** (`console-integration.md` §6.1) **and per-surface availability** (§10.2).
- **The catalog is 38 verbs** and every console operation names one — `metrics.query`, `metrics.export`,
  `metrics.forceRaw`, `metric.declare`, `dashboard.view`, `dashboard.edit`, `dashboard.reset`. ⛔ No new verb is
  invented here; `metrics.export` and `metrics.forceRaw` are **high-consequence** and **Administrator-only** under
  OR-8, so an Operator gets query and dashboards but not export or forced-raw.
- **Decision ids:** ⛔ **none is reserved.** Allocate the next free id from the merged `DECISIONS.md` (ruling
  **OR-11**), after plan 005 merges.
- **Contracts are single-writer.** Task 3 touches `contracts/openapi/console.yaml`, so
  `./contracts/run-checks.sh --strict` must be green **before and after**, and the change is reviewed rather than a
  convenience edit.
- **Docker/Colima is shared state.** The usual checks before container work; never prune, switch context or use the
  demo's ports.
- **Every guard carries a planted-violation proof** (`L7`); a mutation that does not fire is a finding about the
  tests (`L22`).

---

## Prerequisites

1. ⛔ **Plan 007 must be landed.** `console-api` composes DP reads through `dp-api` service interfaces that plan 007
   creates, and it needs plan 007's session/authorization boundary to authorize anything.
2. ⛔ **Plan 006 must be landed** — every operation calls `authorizationService.require`.
3. **Read `console-integration.md` §5.4 first.** All fourteen console operations are already mapped to a verb and a
   scope; this plan builds them, it does not re-decide them.
4. ⚠️ **`architecture.md` §3.1 names only two plane modules.** `console-api` is the third (fourth counting
   `agent-gateway`, which is designed and unbuilt). That text needs it added — a docs change this plan owns.

---

## File Structure

| Path | Responsibility |
|---|---|
| `backend/console-api/pom.xml` | The module. Depends on `cp-api`, `dp-api` — nothing else from the reactor. |
| `…/console-api/src/main/java/com/solace/sm/console/CapabilitiesController.java` | `GET /capabilities`. |
| `…/console/MetricsController.java` | catalog, label values, entity types, range/instant query, export, type-metric. |
| `…/console/DashboardController.java` | dashboard read, widgets, layout, reset. |
| `…/console/compose/ConcurrentComposer.java` | The one place CP and DP reads are fanned out and joined in memory. |
| `…/console/compose/SurfaceAvailability.java` | Per-surface `{surface, liveness, reason}` (§10.2) + `stateVersion`. |
| `backend/arch-tests/.../ConsoleApiBoundaryTest.java` | The module rules + planted violations. |
| `frontend/src/features/monitoring/**` | Point the monitoring screens at `/api/console/v1/*`. |

---

## Task 1 — The module and its boundary, enforced before anything is built

**Files:** Create `backend/console-api/pom.xml`; Modify `backend/pom.xml`, `backend/arch-tests/pom.xml`; Create `backend/arch-tests/src/test/java/.../ConsoleApiBoundaryTest.java` + its planted-violation fixtures — **one per rule actually written**; see Step 1's note on the second rule.

- [ ] **Step 1: Write the rules and their fixtures first** — the boundary is the reason the module was approved, so it is built before the endpoints.

```java
// The two mechanics a bare @ArchTest field omits, both required: without @AnalyzeClasses the
// JUnit5 engine has no class set and these fields never run; without the import option the
// planted fixtures this same task creates keep the rules permanently red.
@AnalyzeClasses(packages = "com.solace.sm",
                importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class ConsoleApiBoundaryTest {

@ArchTest static final ArchRule consoleApiUsesApisOnly = noClasses()
        .that().resideInAPackage("com.solace.sm.console..")
        .should().dependOnClassesThat(
                resideInAPackage("com.solace.sm.cp..").and(not(resideInAPackage("com.solace.sm.cp.api..")))
                .or(resideInAPackage("com.solace.sm.dp..").and(not(resideInAPackage("com.solace.sm.dp.api..")))))
        .because("OR-10: console-api composes SERVICE INTERFACES, never plane internals");

// ⚠️ ASK BEFORE ADDING A SECOND RULE. jdbcIsConfinedToApplyPersistence is already phrased
// resideOutsideOfPackage("com.solace.sm.dp.apply.persistence.."), so it binds
// com.solace.sm.console.. the day the module exists. PlaneBoundaryTest carries a test that
// exists to ask exactly this -- theExistingJdbcConfinementRuleAlreadyCoversTheApplyEnginePackage,
// whose javadoc says "Answers the question the plan asked rather than assuming it." Write the
// console-api equivalent FIRST; add a second rule only if the answer is no.
@ArchTest static final ArchRule theExistingJdbcConfinementAlreadyCoversConsoleApi = /* … */;
```

- [ ] **Step 2: Run against the fixtures; each rule reports exactly its own violation.** ⚠️ The count depends on Step 1's answer about `jdbcIsConfinedToApplyPersistence`, and the migration-directory check is a **resource assertion**, not a rule a fixture trips.
- [ ] **Step 3: Create the module** and add it to the reactor **and** to `arch-tests` at test scope (otherwise the rules are silently vacuous — plan 007 Task 3a Step 3b learned this the hard way).
- [ ] **Step 4: Green with the fixtures excluded, red with them included — record both.**
- [ ] **Step 5: Prove they fire.** Import `dp-core`'s `ApplyRepository` → the first rule red. Declare a `DataSource` bean → the second red. Add a migration directory → a resource assertion red.
- [ ] **Step 6: Commit.** `feat(console-api): the composition module OR-10 approved, boundary-enforced first`

---

## Task 2 — Concurrent composition, `stateVersion` and per-surface availability

**Files:** Create `…/console/compose/ConcurrentComposer.java`, `SurfaceAvailability.java`; Create `…/console-api/src/test/java/.../ComposerTest.java`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void cpAndDpReadsRunConcurrentlyNotSerially() {
    // The module exists to avoid serial fan-out. Two 200ms reads must finish in ~200ms, not 400.
}
@Test void everyComposedResponseCarriesStateVersion() { }
@Test void aDegradedDpSurfaceDoesNotDegradeTheCpSurfaces() {
    // Per-surface availability: the point is that a console can say WHICH surface is stale.
    assertThat(response.surfaces()).contains(surface("metrics", "degraded"), surface("hierarchy", "live"));
}
@Test void oneSlowPlaneIsBoundedAndReportedRatherThanHangingTheRequest() { }
@Test void noComposedReadIssuesASingleQueryAcrossBothSchemas() { }
@Test void aFailedPlaneReadNeverFabricatesAnEmptyResultThatLooksLikeData() { }
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement** with a bounded deadline per plane and a virtual-thread or executor fan-out. **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Make the composer serial → the concurrency test red. Collapse per-surface availability into one flag → its test red. Return an empty list on a plane failure → the last test red.
- [ ] **Step 6: Commit.** `feat(console-api): concurrent CP/DP composition with per-surface availability`

---

## Task 3 — The fourteen console operations

**Files:** Create the three controllers; Modify `contracts/openapi/console.yaml`; Create the matching `*IT` tests.

- [ ] **Step 1: Write the failing tests — one per operation, each asserting its mapped verb and scope** from `console-integration.md` §5.4, plus:

```java
@Test void metricsExportAndForceRawAreAdministratorOnly() {
    // OR-8: high-consequence per architecture.md §16, outside Operator's 19.
    assertThat(post("/api/console/v1/query/range", forcedRaw(), asOperator())).hasStatus(403);
}
@Test void anOperatorCanQueryMetricsAndEditDashboards() { }
@Test void aViewerCanReadDashboardsAndQueryMetricsAndNothingElse() { }
@Test void everyOperationIsRegisteredWithAShippableVerb() { /* plan 007's check 11 */ }
@Test void theSeriesCapAndAggregationRefusalsUseTheSpecifiedErrorCodes() {
    // SERIES_CAP_EXCEEDED, AGGREGATION_NOT_APPLICABLE, GROUP_BY_REQUIRES_AGGREGATION,
    // STAT_REQUIRES_AGGREGATION, METRIC_UNTYPED -- the five SPECIFIED codes, as 422s.
}
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement.** **Step 4: Run green.** **Step 5: Prove the authorization fires** — drop one verb check and watch exactly that row go red.
- [ ] **Step 6: Commit.** `feat(console-api): the fourteen console operations, each authorized by its mapped verb`

---

## Task 4 — Durable metrics and dashboards, replacing the demo's in-memory stores

**Files:** Create `dp-core` migrations for the metric catalog and series (per `SM-D26`/`SM-D30`) and `cp-core` migrations for dashboards (per `SM-D31`); Create the repositories behind the DP/CP service interfaces; Create the ITs.

⚠️ **The tables live in the owning plane, never in `console-api`.** Metrics and series are **DP**; dashboards are
**CP**. `console-api` reads both through service interfaces and joins in memory.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void aDeclaredMetricSurvivesRestartAndTheDemoInMemoryStoreDoesNot() { }
@Test void anUndeclaredMetricIsQuarantinedAndEscapableOnlyByMetricDeclare() { }  // UI-D5
@Test void theCardinalityCapIsEnforcedAtIngest() { }                              // SM-D30
@Test void aDashboardIsPerInstanceAndSharedWithPackageDefaultsAsASeparateLayer() { }  // SM-D31
@Test void resetRestoresThePackageDefaultLayerWithoutDeletingUserWidgets() { }
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement.** **Step 4: Run green, and re-record the DDL replay baseline.**
- [ ] **Step 5: Prove the guards fire.** Remove the cardinality cap → its test red. Make the default layer mutable → the reset test red.
- [ ] **Step 6: Commit.** `feat: durable metric catalog, series and dashboards in their owning planes`

⛔ **This is the task plan 007 Task 13b waits on.** Until it is green, the demo's monitoring is the only monitoring
and `demo_control` cannot be dropped.

---

## Task 5 — Backend API handoff and demo retirement

**Files:** The backend API, contract and demo retirement are accepted here after backend monitoring parity. The OpenAPI and endpoint inventory are handed to the frontend; no frontend file is changed or accepted by this backend plan. Consumer cutover is a release/deployability gate, not a condition for deleting backend demo code.

- [ ] **Step 1: Prove all-backend parity before one cleanup.** Combine Plan 007's production API parity for auth/instance/config/apply/status/logs with `console-api` parity for monitoring, dashboards and Explore. The checklist must show every demo capability has a backend production replacement under real authorization; no partial demo deletion is permitted.
- [ ] **Step 2: Remove `demo-launcher` from the reactor and delete the entire module in one backend cleanup**, including its `arch-tests` test-scope edge; then drop `demo_control`. Run Task 12's mutating-`RuntimeAdapter` rule unscoped after deletion to establish the full isolation that Plan 007 expressly did not claim. ⚠️ Deleting migration files is not dropping a schema: on any host where the demo ran, `demo_control` and its Flyway history survive. Either a documented operator step or a final self-dropping migration — and state the **rollback**: once `demo_control` is gone, reverting to the demo is a reinstall, not a revert.
- [ ] **Step 3: Hand off `/api/console/v1/*`, the OpenAPI, endpoint inventory and parity evidence to the frontend, and record the release gate.** The frontend owns monitoring-screen cutover and removal of demo-source labelling. This backend-cleanup branch is **not deployable with the current frontend** until it adopts the endpoints; consumer cutover is not a prerequisite for deletion.
- [ ] **Step 4: Backend full gate.** `mvn -f backend/pom.xml clean verify` · `./contracts/run-checks.sh --strict` · DDL replay · `git diff --check`. This gate establishes backend parity and retirement without frontend tests or browser evidence.
- [ ] **Step 5: Record two results.** `console-api` backend-ready and demo-retired with the handoff recorded; separately, release/deployability remains pending until consumer cutover.

---

## Task 6 — Close the plan

- [ ] **Step 1: Backend completion gate** as Task 5 Step 4.
- [ ] **Step 2: Results section** — exact backend test counts, every skip with its reason, Docker state before and after, each mutation and whether it fired; separately record the frontend handoff and the outstanding release/deployability gate.
- [ ] **Step 3: Update `architecture.md` §3.1** to name `console-api`, and `console-integration.md` §14 from planned to measured.
- [ ] **Step 4: Record decisions by allocating the next free id from the merged `DECISIONS.md`** (ruling OR-11).
- [ ] **Step 5: Independent adversarial review** — specifically: a cross-schema query smuggled into a composer; `console-api` holding a repository or a datasource; serial fan-out described as concurrent; per-surface availability collapsed to one flag; a demo capability dropped rather than replaced; `demo_control` dropped without a rollback story.
- [ ] **Step 6: Commit.**

**Backend/API cleanup and consumer cutover are separate.** Plan 009's backend result is complete after its APIs, contract checks, durable replacement, demo retirement and handoff pass. Frontend implementation and browser verification remain a separate consumer delivery and cannot block that backend cleanup. The backend-ready integration branch is **not deployable with the current frontend** until consumer cutover is recorded; that is a release gate, not a reason to retain `demo-launcher` or defer backend retirement.

---

## Scope and non-goals — stated exactly

| Not here | Why |
|---|---|
| The `stop` endpoint | `instance.stop` exists (amendment §1.7) but the endpoint belongs with the apply surface, not the console composition |
| The Admin surface (`/cp/admin/*`) | Still no plan; `group.*` verbs are open item 39 proposals |
| Live chart tails over the mesh | ⛔ Hard-gated on open items 32/33/35 — the §11.3 broker spike is **written and NOT executed** — and must not execute while the demo is active. `console-api` serves REST charts; tails wait |
| Raw-tier retention and rollup tuning | `SM-D8`'s sizing model is open item 5 |
| Agent-sourced metrics | `AGW-D9`'s ingest path; `AGW-2` has no `.proto` |
