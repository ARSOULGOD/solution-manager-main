# Backend Plan 011 — the monitoring backend: collection, storage, rollups, query/export, collector health

> **Status: APPROVED (OR-97, 2026-10-05)**
>
> **Close, 2026-10-07 (P11-10.1): BUILT AND REVIEWED, NOT FULLY PROVEN.** The storage gate ran at one week of S-A (`OR-112` (3)); REQ-007 is **not met** at the measured gate topology; the 105 M-row export was **not measured** (`exportRange` stays "wired, not counted served"); P11-5.2 is **not built** (conditional). The acceptance table, with each item's evidence or "not claimed", is in Results, `P11-10.1`.
>
> *(**Approved 2026-10-05 (`OR-97`), as refreshed at `p011/mw0` `bec686e`.** The paragraphs below that say "DRAFT", "not approved" or "only MW0 may run" are the history of the draft and are superseded by `OR-97`; OQ-3, OQ-5, OQ-20 and OQ-29 stay open and gate MW5 only. Finished tasks take their wave review and the full build before they merge and push.)*
>
> *(Note: two adversarial review rounds were applied on 2026-09-30 and the second round's fixes were not
> re-reviewed. This plan was **refreshed on 2026-10-05 against `bd802fc`**, the tip of `integration/dp-remote-demo`
> when MW0 began. A third, pre-sitting review (2026-10-05) then found 5 MAJOR and 9 MINOR defects in that refresh; its
> dispositions are at the end ([Review round 3](#review-round-3-2026-10-05-before-the-owner-sitting-5-major-and-9-minor)),
> and those fixes, including the per-task gates and OQ-26…OQ-29, have not been re-reviewed. Only MW0, the docs-only wave,
> may run (`OR-91`). Plan 011 is not approved, no later wave is dispatched.)*
>
> *(**Update, the owner sitting (2026-10-05, `OR-92`…`OR-95`):** 25 of the 29 owner questions are ruled (21 as recommended,
> OQ-28 `OR-93`, OQ-26 `OR-94`, OQ-14 `OR-95`); OQ-3, OQ-5, OQ-20 and OQ-29 stay open, deferred until MF26 is measured, and gate
> MW5 only. `OR-93` lets six tasks start after the sitting; everything stays LOCAL until its wave review. **The plan is still a
> DRAFT until the owner approves it**, and the ruled text below has not been re-reviewed.)*

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking. **Read [Dispatch map](#dispatch-map--what-may-run-in-parallel) before starting any task.** It says which
> tasks may run in separate worktrees at the same time, and which **plan 010** task each one waits for.

| | |
|---|---|
| **Component** | backend (`dp-api`, `dp-core`, `cp-api`, `cp-core`, `console-gateway`, `launcher`, `arch-tests`) + `contracts/` + docs. **No frontend change** (owner standing rule). |
| **Status** | **APPROVED (`OR-97`, 2026-10-05)**; the rest of this cell is the history of the draft (it was *DRAFT — not approved, `OR-91`*). Written 2026-09-30 against `integration/dp-remote-demo` @ `0cbaa28`, after the owner's monitoring sitting (M-Q1…M-Q24, all ruled), and revised the same day for two adversarial reviews (41 and 31 findings; [dispositions](#plan-review--dispositions)). Round 2's fixes have not been re-reviewed. **Refreshed 2026-10-05 against `bd802fc`**: the monitoring rulings are registered (`OR-69`, `OR-71`…`OR-81`, with `OR-82`…`OR-91` beside them), plan 010 has landed through `T5.2` (plus `T2.7`, `T3.x`, `T4.1`–`T4.3`), and each claim that names plan 010 or the tree was re-measured; see [what changed and what did not](#refresh-2026-10-05--what-changed-and-what-did-not). **MW0 (docs only) ran in parallel with plan 010 on the owner's answer to OQ-19 (`OR-91`): P11-0.1, P11-0.2 and P11-5.0 are executed on `p011/mw0` ([Results](#results)).** Every later wave keeps its own gates, now stated per task. **The owner sitting was held the same day (`OR-92`…`OR-95`):** 25 of 29 questions ruled, OQ-3/5/20/29 deferred until MF26 (`OR-95`), and six tasks released early by `OR-93`; the plan is still a DRAFT. **Reviewed a third time before that sitting (2026-10-05):** 5 MAJOR and 9 MINOR findings, [dispositions](#review-round-3-2026-10-05-before-the-owner-sitting-5-major-and-9-minor); OQ-26…OQ-29 added; storage re-sequenced ahead of plan 010 W10/W11 **subject to OQ-28**, which only the owner can rule. Nothing below claims approval. |
| **Authority** | The project owner, deciding on behalf of the architect group (M-Q1 `OR-69`, 2026-09-29: *"let's go ahead with option B, also i will be giving decisions on behalf of architect group"*), asked on 2026-09-29 *"Can we start creating plan for monitoring in parallel?"* The rulings are **registered in [`DECISIONS.md`](../../design/DECISIONS.md)** (2026-10-05, by plan 010's S0-records task): M-Q1 → `OR-69`; M-Q2 and M-Q2b → `OR-71`; M-Q3 → `OR-72`; M-Q4 and M-Q12 → `OR-73`; M-Q5, M-Q14 and M-Q15 → `OR-74`; M-Q6, M-Q7, M-Q9 and M-Q20 → `OR-75`; M-Q8 and M-Q19 → `OR-76`; M-Q13 → `OR-77`; M-Q10, M-Q11 and M-Q23 → `OR-78`; M-Q17, M-Q18, M-Q21, M-Q22 and M-Q24 → `OR-79`; M-Q16 → `OR-80`; leg 13 of the browser mesh gates → `OR-81`. Later rulings that bear on this plan: `OR-82` (direct-HTTP polls the **target's** registered address; the declare body carries no host), `OR-88` (an abandoned declare frees its instance name), `OR-89` (host-key uniqueness is per organization), `OR-90` (a CP registration landing after the sweep is accepted as rare) and `OR-91` (MW0 may run now). The verbatim record is the integrator's `monitoring-owner-rulings.md` (outside the repo); where a registered row and this plan differ, the row wins. This plan cites each ruling as **M-Qn `OR-xx`**. |
| **PROPOSED decisions relied on** (checklist item 2, knowingly failed) | `SM-D26`/`SM-D29`/`SM-D30`/`SM-D31` (storage tiers, metric declaration, dashboards). `UI-D5`/`UI-D8`/`UI-D13` (quarantine seam, gap rule, composition). All of `DM-D*` (the query API shape). `AGW-D9` (`TelemetryIngestPort`), which is **amended** by M-Q7's `binding_id := placement_id` mapping (a proposal against a proposal). Each is used **only where an M-Q ruling adopts it**, under the owner's authority (M-Q1). `docs/checklists/plan-review.md` item 2 therefore fails **on the record**, not silently. |
| **Requirements** | [`REQ-007`](../../requirements/REQ-007-six-month-metrics.md) (six months raw, queryable, exportable) · [`REQ-010`](../../requirements/REQ-010-per-instance-dashboards-and-comparison.md) (raised by the owner 2026-09-30 per M-Q23 `OR-78`; **it exists**: P11-0.1 cites it and creates nothing) · [`REQ-012`](../../requirements/REQ-012-event-driven-live-console.md) · [`REQ-006`](../../requirements/REQ-006-rbac.md) · [`REQ-014`](../../requirements/REQ-014-oss-commercial-licences.md) / `I8` · [`REQ-009`](../../requirements/REQ-009-no-mi-development.md) / `I1` |
| **Supersedes** | [Plan 009](009-console-api-and-monitoring-replacement.md), **in part and exactly as listed** in [Relationship to plan 009](#relationship-to-plan-009--exactly-what-is-replaced). |
| **Depends on** | [Plan 010](010-dp-data-model-and-remote-flow.md) (executing; through `T5.2`, `T2.7`, `T3.x` and `T4.1`–`T4.3` landed and pushed at `bd802fc`; `T4.4`, `T5.3`, `T6.1` and `T6.2` are built on unpushed local branches, see the [dependency table](#plan-010-dependencies--exact)). **MW0 (docs only) needs nothing from it** (`OR-91`). **Every later task has its own gate**, named in the [per-task gate table](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why). **Ruled OQ-28 (b), `OR-93`.** The text that follows describes the options; the status quo (**OQ-28 (a)**) was not taken. Under (a) the gate is what the rulings' closing line says: nothing in plan 011 code starts before plan 010 S6 and S7 have merged (its W10), and the tasks that touch declare, stage or `console.yaml` wait for its W11. Under the recommended **OQ-28 (b)**, six tasks (P11-1.1, P11-1.2, P11-2.1, P11-2.3, P11-4.2a, P11-7.1) open after the owner sitting, each waiting only for the plan 010 merge the table names for it, and P11-2.2 may be developed early but still merges after plan 010 T11.2's `V014`. `OR-91` relaxes the closing line **for MW0 only**, so **no code task was released early until the owner ruled OQ-28**. ✅ **Ruled (b), `OR-93` (2026-10-05):** six tasks start after the sitting on the per-task gates below; the "under OQ-28 (a)" clauses in this plan are the option not taken. Everything stays LOCAL until its wave review. |
| **Decisions this plan records** | None pre-allocated. The rulings are registered (`Authority` row). Any new `BE-D` takes the next free id **at merge**, in merge order (plan 010 Global Constraints, `OR-11`): `BE-D27`…`BE-D33` are plan 010's and allocated, `BE-D34` was the next free id at `bd802fc`, and plan 010 tasks still to land hold unallocated `«next free BE-D»` rows (for example T6.8, T9.2 and T10.3), so the id this plan receives will not be `BE-D34` unless it merges first. Wherever this plan says `«next free BE-D»` it means that. |

**Goal:** Replace `demo-launcher`'s throwaway monitoring with production monitoring served by the production
launcher. The Data Plane collects every declared metric of every active instance in the background (no logged-in
session), over the per-instance acquisition mode (direct HTTP to the **target's registered address**, `OR-82`, or SSH to the host loopback, M-Q2), and ingests
broker-pushed business metrics (M-Q12). It stores six months of raw samples plus type-aware hourly and daily rollups
in a DP-owned `sm_metrics` schema (M-Q19), and serves the `/api/console/v1` query, export, catalog and dashboard
routes, each authorized by its mapped verb (console-integration §5.4 rows 53–66). It surfaces collector health so an
operator can tell "the MI is down" from "SM's collector is broken" (M-Q21).

**Architecture:** One ingest seam, `TelemetryIngestPort` in `dp-api` (AGW-D9's shape, M-Q7's field mapping), fed
by three producers: the dp-remote **poll collector** (path 1), the **broker-push subscriber** (path 2), and, later,
the agent gateway (path 3, whose producer is the agent-gateway plan's; only the port and its contract kit are built
here). The collector reads only DP rows. At declare and stage, the gateway resolves from the CP package the metric
definitions (M-Q5, write-once per package version), the host ports (M-Q14) and the active metric list per
generation (M-Q15), and passes them to the DP. Samples land in `sm_metrics` (same database, own Flyway history,
owned by `sm_dp_owner`, written only by `sm_dp_runtime`). A no-argument `SECURITY DEFINER` function keeps the monthly
partition runway (M-Q13). The gateway composes CP and DP reads for the `/api/console/v1` routes (M-Q11). Dashboards
live in `sm_control` (M-Q10).

**Tech Stack** (versions **measured 2026-10-05** at `bd802fc` with `mvn -o -f backend/pom.xml help:evaluate`, or read from the poms): Java 17 · Spring Boot
**3.5.16** (`backend/pom.xml:25`) · PostgreSQL 14 · Flyway **11.7.2** (`flyway.version`; **MF1 measures the two-history
behaviour at that version**) · HikariCP **6.3.3** (`hikaricp.version`) · PgJDBC **42.7.11** (`postgresql.version`) · Jackson
**2.21.4** (`jackson-bom.version`; `JsonNode.at` for JSON Pointer) · JDK `java.net.http.HttpClient` (direct mode) ·
sshj **0.40.0** (`dp-core/pom.xml:31`) through plan 010's `SshSessionPool` · JCSMP **10.30.1** (`dp-core/pom.xml:27`,
already in `dp-core`, path 2) · Testcontainers **1.21.4** · ArchUnit **1.5.0** (`backend/pom.xml:50`) ·
`com.networknt:json-schema-validator` **2.0.8** (`cp-core/pom.xml:61`, plan 010 T3.0; it validates every package at load,
so P11-1.1's new schema keywords are a measured fact, **MF24**). **No new dependency** is planned. A task that finds it needs one stops and routes it through plan 010 T0.4b's
licence gate (`I8`).

**Spec (read with this plan):** the registered rulings, [`DECISIONS.md`](../../design/DECISIONS.md) `OR-69`,
`OR-71`…`OR-82` (**they win**; verbatim in the integrator's `monitoring-owner-rulings.md`, outside the repo) ·
`monitoring-discovery.md` R1 (facts, gaps C1–C13/N1–N10/S1–S10/Q1–Q9, scenarios S-A…S-M, defaults D-2…D-17; **the
rulings override its recommendations wherever they differ**). ⚠️ **That file is in neither the repo nor the recovered
scratchpad (checked 2026-10-05).** Its scenario ids, gap ids and defaults are therefore cited as this draft recorded
them and **could not be re-measured in the 2026-10-05 refresh**; a reader who needs one must ask the integrator for the
file. ⚠️ **The §3.2 defaults D-2…D-17 are
discovery defaults, not owner rulings.** The owner's sitting covered M-Q1…M-Q24 only, and the rulings file records no
sight of §3.2. Every `D-n` cited below is labelled "discovery default"; **OQ-22 was accepted (`OR-92`, 2026-10-05)**, so each is now an
accepted default, still not one of the monitoring rulings M-Q1…M-Q24 ·
[`dynamic-metrics/design.md`](../../design/dynamic-metrics/design.md) §5 (wire shapes) ·
[`console-integration.md`](../../design/common/contracts/console-integration.md) §5.4 rows 53–66 ·
[plan 010](010-dp-data-model-and-remote-flow.md) (conventions, the tasks this plan builds on).

---

## Refresh 2026-10-05 — what changed and what did not

This revision re-measured the draft against `bd802fc` (tip of `integration/dp-remote-demo` when MW0 began). **It is not a
review, and nothing here is approved.** Each row says what the draft claimed, what the tree or the registered record shows,
and where this plan now says so. A "measured" entry names a command or a file:line; nothing was recalled.

| # | The draft claimed | Measured at `bd802fc` | Applied in |
|---|---|---|---|
| 1 | Rulings cited as `M-Qn «OR-M-Qn»`, registered later as "OR-60+" | Registered 2026-10-05: M-Q1 → `OR-69`; M-Q2/M-Q2b → `OR-71`; M-Q3 → `OR-72`; M-Q4/M-Q12 → `OR-73`; M-Q5/M-Q14/M-Q15 → `OR-74`; M-Q6/M-Q7/M-Q9/M-Q20 → `OR-75`; M-Q8/M-Q19 → `OR-76`; M-Q13 → `OR-77`; M-Q10/M-Q11/M-Q23 → `OR-78`; M-Q17/M-Q18/M-Q21/M-Q22/M-Q24 → `OR-79`; M-Q16 → `OR-80`; leg 13 → `OR-81` | every placeholder; the Authority row; the rulings index |
| 2 | OQ-19 pending | **Ruled** `OR-91`: "Yes, start MW0 now (Recommended)". Every other OQ stays open | OQ table, dispatch map, Status |
| 3 | Direct mode needs a browser-supplied host: `instance_state.direct_host`, `InstanceView.directHost()`, `acquisition: {mode, host?}` | **None exists, by `OR-82`.** `instance_state.acquisition_mode` only (`V007…sql:39-40`); `InstanceView.acquisition()`; `DeclareInstanceCommand.acquisition` is the mode; the body is hostless. Direct mode polls `target_connection.host` (`V007…sql:94-95`, mutable, read fresh) at the mapped host port | Prerequisite 2; M-Q2, M-Q14 rows; P11-4.2, P11-4.3, P11-4.4 |
| 4 | P11-0.1 creates `REQ-010` | **Exists** (`REQ-010-per-instance-dashboards-and-comparison.md`; `README.md:82`; `traceability.md:24,44,45`) | P11-0.1 Step 1 (cites it) |
| 5 | P11-0.1 writes `D10` approval pointers in two places | Limitation 4's pointer **exists** (`D10-G2-APPROVAL-2026-09-15.md:266`); `console-integration.md:312`'s pointer does **not** (`OR-80` says so) | P11-0.1 (the console-integration pointer only) |
| 6 | P11-0.1 hands the integrator the `OR-49` / T7.1 wording; P11-4.3 writes the javadoc line | Plan 010 **already carries it** (`010-…md:59-68`, `:3719`) and `TenantEnumerationRepository`'s javadoc **already names `MetricsCollector`** (`:69-83`) | P11-0.1; P11-4.3 narrowed to OQ-6 only |
| 7 | `SketchMirrorTest` / `SmDataBootstrapIT` pins "unknown" after plan 010; P11-2.1 edits `eda-walking-skeleton.md` §3.1 | Resolved in plan 010's **code**: the mirror covers V001–V006 (`SketchMirrorTest.java:199`), later Phase 0 statements live in `PHASE0_AFTER_THE_SKETCH` (`:47`); Phase 0 is pinned at **9**, migrations at **10** | Prerequisite 7; P11-2.1, P11-2.2, P11-5.1; single-owner table |
| 8 | New Phase 0 lines go "beside `sm_data`'s lines 21–22" | They would break the mirror's "they come last" pin (`SketchMirrorTest.java:56`); they go **at the end**, after T2.4's block | P11-2.1 Files |
| 9 | P11-4.1 may need a new `SolutionCatalog.digestOf` | `find(…).digest()` **already returns the stored digest** (`LoadedPackage.java`); `documentOf`'s javadoc says never to hash its text | P11-4.1; single-owner table |
| 10 | Overlap fixtures need `port` when it becomes required | They (and the Cache fixture) **already carry it** (six `"port"` each). `engine-edge` is the only root fixture without | P11-1.1 Files |
| 11 | The schema lives in `contracts/` | **Two more consumers**: a byte-identical cp-core classpath copy (`PackageSchemaMirrorLockTest`) and the **frontend**, which compiles the root schema (`frontend/contracts/solution-package/validate.ts:12`, Ajv strict) and has fixtures without `port` (`testPackage.ts:62`; `version-scoped-component-excluded.json`) | P11-1.1 Files, Step 0b; MF25; **OQ-24** |
| 12 | A `^…$` pattern refuses a newline in `queryParams` at gate 1 | **Not in the Python gate**: `jsonschema` 4.26.0 accepts one trailing `\n`; networknt 2.0.8 refuses it. `(?![\s\S])` refuses it in both | P11-1.1 shape, Step 0c, Step 5; MF24 |
| 13 | Broker credential goes to `sm_secret` "by reference" | `SecretPurpose` is a **closed set** and `secret_purpose` is a CHECK (`V009…sql:23-24`); a broker purpose needs V016 and a `BE-D28` amendment | OQ-3, P11-5.1 Files, allocation |
| 14 | Port collisions are guarded per target | True, and `OR-89` (2026-10-05) now lets **two organizations register one machine**, so no guard spans them | **OQ-23**, V015 comment |
| 15 | Reviews are per task | `OR-70` changed the cadence **for plan 010** only | Global Constraints; **OQ-25** |
| 16 | Plan 010 landed T1.3, T2.x, T3.x, T4.x | Landed: through `T5.2`, `T2.7`, `T3.0`–`T3.3`, `T4.1`–`T4.3`. **Not landed:** `T4.4` (no `InstanceController`, `DeclareOrchestrator` or `DeclareInstanceRequest`), `T5.3`, `T6.x`, `T7.x`, `ApplyPathBoundaryTest` | dependency table |
| 17 | Pins and line cites into the tree and plan 010 | Re-resolved: see the "moved" list below | throughout |
| 18 | Stack versions | Measured: Spring Boot 3.5.16, Flyway 11.7.2, HikariCP 6.3.3, PgJDBC 42.7.11, Jackson 2.21.4, Testcontainers 1.21.4, ArchUnit 1.5.0, sshj 0.40.0, JCSMP 10.30.1, networknt 2.0.8 | Tech Stack |
| 19 | `sm_data` numbers V015/V016, `sm_control` V014 free | Still free and consistent with plan 010's table (`010-…md:366-376`); `sm_control` holds V001–V013, `sm_data` V001–V010 | Migration allocation |
| 20 | The `sm-data` pool might set a size | It sets **none** and pins `setSchema("sm_data")` (`ApplyPersistenceConfiguration.java:94-107`) | MF16 |

**Line cites that moved** (old → new, all re-read): `OperationPolicy.AUTHENTICATION_ONLY_OPERATIONS` `:34-35` → `:35-36`;
`validate-openapi.py` `EXPECTED_NON_CATALOG` `:59-67` → `:70-78`; `SshjDockerTransport` probes `:217-253` → `:225-261`;
`SshDockerTransport` defaults `:52-73` → `:53-75`; `backend/pom.xml` `demo-launcher` module `:73` → `:85`;
`TwoSchemaTestDatabase` migration call `:89` → `:116`; `ApplyPersistenceConfigurationIT` `:165-167` → `:167-169` (count 6 → 10);
`SmDataBootstrapIT` history lookup `:152-157` → `:183-187`; `PlaneBoundaryTest` fixtures import `:264-266` → `:264-268`, exclusion
`:42,209` → `:43,46,209`; `ResourceResolver` `:35-44` → `:23-30`; `SchemaMirrorLockTest` `:32,75,80` → the equality assertions at
`:79-90` and `:130`; plan 010 cites (examples): `:390` → `:409`, `:1591` → `OperationClassification.java:48`, `:2328` → `:2491`,
`:2518` → `:2706`, `:2680-2683` → `:2881-2884`, `:3128-3145` → `:3331-3356`, `:3534` → `:3742`, `:3548-3552` → `:3767`,
`:365-369` → `:366-376`, `:1280-1283` → `:1350-1351`. Unchanged and re-verified: `DesignerProjectorImpl` `:156-178,326-344,437-449,470`;
`JdbcAuditLog:14-20`; `ControlPlanePersistenceConfiguration:34-43`; `AuditEntry:20,22,62-66`; `OperationCoverageValidator:147`;
`console-integration.md:395-402,397`; `DemoMonitoringController:223-242`; `DemoDashboardStore:78-84,86-170,195-200`;
`solution-package.schema.json:196,244,549-629`; `cache-2.4.package.json:409-507,491-495`; `engine-edge.package.json:44`;
`run-checks.sh:135,141-154`; `validate-package.py:93-99,188-203`; `PackageModel:76-101`; `PackageJsonReader:40-41`; sibling repos
(read-only): `CacheController.java:60-67`, `EventState.java:20-27`, `Solace.java:28`, `env.sample:11-12`, `app.ts:70`.

**What did not change, and could not be re-measured here.** No ruling, task, wave or acceptance criterion changed meaning.
Every `MF` row marked ❌ stays empty (they need the EC2 host, WebFetch, or code that does not exist yet). The discovery
document is absent, so scenario ids S-A…S-M and defaults D-2…D-17 are as the draft recorded them. The owner-measured EC2
facts (M-Q8) are the owner's. **Handed to the integrator, not edited here:** the plan 010 wording in
`docs/tasks/plan-011-mw0-integrator-handoff.md` (written by P11-0.1).

---

## Global Constraints

Every task's requirements implicitly include this section, and **every plan 010 Global Constraint also applies**
(`010-…md` "Global Constraints"). These are the additions and the monitoring-specific readings:

- **`I1` — no MI change, ever.** Every value SM stores comes from something SM holds (its records, the package it
  owns, the path it used) or is **read from today's MI payload through a package-declared extraction** (M-Q7's
  condition, verbatim: *"I am okay with a as long as we are not asking for any change in the way MIs are sending
  payload or something"*). No new endpoint, no Micrometer registry, no `build-info`, no payload field. P11-3.1
  carries the explicit I1 check.
- **SM is generic for every MI.** No `if (solution == "cache")`, no Cache-only constant on a production path, and
  no **new** `8081` literal outside test fixtures. The existing hard-coded demo probes
  (`SshjDockerTransport.java:225-261`, the default methods at `SshDockerTransport.java:53-75`, and `SshDockerMetrics`)
  are used only by `demo-launcher` today (measured at `bd802fc`: `SshDockerMetrics` is named by five `demo-launcher`
  files and by no production class; the only `8081` literals in any `src/main` are the six in `SshjDockerTransport`).
  Plan 010 T6.7's two facade rules will pin that, but **T6.7 had not landed**: no `ApplyPathBoundaryTest` exists yet.
  **P11-9.1 deletes them.**
  Behaviour differs per MI **only** through the package (`contracts/solution-package.schema.json`). Cache is the
  integration fixture. It is never a code path.
- **`I5`.** The tenant is `Session.organizationId()`. Every statement is bind-parameterised and tenant-scoped.
  Exactly **two sanctioned cases** carry an identifier that is not a bind parameter:
  1. **The runway function** (M-Q13 `OR-77`, the narrow carve-out): the clock-derived partition names and bounds
     spelled **inside** `sm_metrics.ensure_metric_partitions()`, which takes no argument. Its grammar is exactly the
     ruled one, `metrics_raw_YYYY_MM`, because the hourly tier stays unpartitioned (OQ-21 (a), the recommendation).
     The grammar is widened only on an owner (b).
  2. **The operator command's partition identifiers** (M-Q13 "common", M-Q18: *"Archive COPY of a detached
     partition is identifier-only (operator command, M-Q13)"*). `MetricsPartitionMain`'s `freeze`, `detach`,
     `archive` and `drop` take a `<yyyy_mm>` argument. That argument must match `^[0-9]{4}_(0[1-9]|1[0-2])$`, and
     the partition it names must be **found by a bound catalog lookup** (`pg_catalog.pg_inherits` under the parent)
     before any statement is built. The statement then uses the **catalog's** `relname`, quoted as an identifier,
     never the argument text. A malformed or injected month is refused before any SQL runs (P11-2.1,
     `aMalformedOrInjectedMonthArgumentIsRefusedBeforeAnySql`).

  No other DDL, `VACUUM` or `COPY` carries a runtime value.
  **Background enumeration** uses only OR-49's `tenantsWithActiveInstances()`, with the metrics collector as its
  **fourth named caller** (M-Q3 `OR-72`). No third list, and the SQL is never re-spelled.
- **`I6`.** No secret value is written anywhere. On the broker-push path the payload is **never** stored, logged,
  echoed in an exception or counted by content. Only the number at a declared pointer is kept, and a non-number
  there is `parse_failed` with no value in the message (M-Q12 `OR-73`). A topic subscription is never built from a
  secret: a package whose `instanceKey` names a config field typed `secret` (or `secret: true`) is refused at load,
  by both validators (P11-1.1, gate 16). No operator command prints a credential it read: `SmDataMigrationMain`,
  `MetricsPartitionMain` and `BrokerConnectionMain` each carry a no-echo test over stdout, stderr and exception text.
- **`I7`.** `sm_metrics` is DP-owned (M-Q19 `OR-76`). CP code never touches it. The FKs from `sm_metrics` to
  `sm_data.placement` (from `series`, `restart_marker` and `ingest_counter`) are **permitted by M-Q19** (*"intra-DP
  FK to sm_data.placement allowed"*). None reaches `sm_control`, and no `sm_metrics` FK targets any `sm_data`
  table other than `placement`. P11-2.1 pins both (`everySmMetricsForeignKeyTargetsSmMetricsOrSmDataPlacementOnly`).
  Dashboards reference `sm_control.instance` inside `sm_control` only (M-Q10). The gateway composes; it never joins.
- **Schema-owner and Flyway credentials stay out of the running application** (owner standing rule, recorded in
  M-Q13). Migrations run in `SmDataMigrationMain` (a separate process). Partition creation at runtime goes only
  through the owner-owned function the runtime role may `EXECUTE`. Destructive partition work (freeze, detach,
  archive, drop) is an **operator command**, `MetricsPartitionMain`, run like the migration step.
- **No demo reuse.** No production class imports `com.solace.sm.demo..` (plan 010 T6.7's rules). The demo's
  **document shape** is adopted by ruling (M-Q23). Its code is not reused.
- **Contracts are single-writer.** `solution-package.schema.json` and `contracts/validation/**` (P11-1.1), and
  `console.yaml`, `ENDPOINT-INVENTORY.md`, `validate-openapi.py`, `ConsoleOperations.java` and `ErrorCode.java`
  (P11-1.2), are each changed in **one** reviewed task, with `./contracts/run-checks.sh --strict` green before and
  after.
- **No route is "served"** until a launcher-level IT boots `SolutionManagerApplication` against PostgreSQL over real
  HTTP (plan 010's rule).
- **Every guard carries a planted-violation proof (`L7`).** A mutation that does not fire is a finding about the
  tests (`L22`).
- **`I2`.** No external fact from memory. The [Measured facts](#measured-facts--fill-each-before-the-task-that-needs-it)
  table names each one. **A task whose row is empty stops at that row.**
- **Execution model (owner, 2026-09-30).** Implementers are **Sonnet 5.5 high**. Reviews are **Opus 5.5 high**.
  Both run via workflows. ⚠️ `OR-70` (2026-10-05) changed the cadence **for plan 010 only** (one Opus review per slice,
  the full build at the end of each slice, one combined Fable 5.1 review at the end); it names plan 010, so this plan's
  per-task Opus review stood until the owner extended it (**OQ-25**). ✅ **Ruled (b), `OR-92`: `OR-70`'s cadence applies to plan 011** (one Opus review per wave, one fix round, the full build at each wave's end, one combined Fable 5.1 review at the end in place of P11-10.1 Step 4); read every "Opus 5.5 high reviewer checks each task" below as that cadence. Parallel tasks run in separate git worktrees branched off the **pushed** integration head.
  **Never `mvn install`.** The integrator merges sequentially and runs a full verify after each wave. Testcontainers
  runs are handed out one at a time (plan 010's Docker rule). **Plan 010's `sm_data` migration numbering and
  `sm_data` ownership are not disturbed** ([Migration allocation](#migration-and-id-allocation)).
- **Commit attribution:** every commit ends with the session's attribution trailer.

### Verification commands (used by every task)

```bash
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock   # plan 010's process-local override
VERIFY() { mvn -o -f backend/pom.xml -pl "$1" -am verify; }

VERIFY <module>                 # the task's own module(s)
VERIFY arch-tests               # boundary rules + every module arch-tests depends on
VERIFY launcher                 # when a route, a bean or a readiness check changes
VERIFY demo-launcher            # when a dp-core type demo-launcher names changes (plan 010's rule) — until P11-9.1
./contracts/run-checks.sh --strict
git diff --check
```

`-o` (offline) stays on. **No task in this plan is permitted an online resolution.** A task that needs one has
found a new dependency, and it stops (`I8`).

---

## Review Focus

These are the five input classes most likely to hurt a real operator that no task's happy path exercises. Each line
names the task that owns the pinning test.

1. **Two Caches on one host, publishing container 8081 on host ports 18081 and 28081 (S-G).** Expected: each
   instance's samples come from its **own** host port, in both acquisition modes. A declare that maps a metrics
   host port already used by another live placement on that target, whether as a metrics port or a health port, is
   refused `409` and writes nothing on either plane. → P11-2.2 (`aMetricsHostPortTakenByAnotherLivePlacementOnTheTargetIsRefused`),
   P11-4.3 (`twoPlacementsOnOneTargetAreEachScrapedOnTheirOwnHostPort`), P11-4.4 (live, on EC2).
2. **A Cache restarts while its host is unreachable (S-D).** The fixture's source declares **`intervalSeconds:
   180`**. With that interval, 12:00→12:05 (300 s) is within 2 × the interval and 12:05→12:15 (600 s) is a gap. At
   the 30 s default, 12:00→12:05 would itself be a gap and the hour would total 300, not 350. The counter reads
   10,000 at 12:00 and 10,050 at 12:05, SSH is down from 12:05 to 12:15, the container restarts at 12:10, and the
   counter reads 300 at 12:15. Expected (M-Q9 (d′)): no delta across the gap; the restart signal changed, so it is a
   reset, `reset_count` is +1, and the hour's increase is exactly **50 + 300**. The same numbers **without** a
   restart-signal change give `decrease_without_restart` +1 and no delta, never a reset. A source whose signal is
   declared (or docker) but **fails to fetch** in one cycle is never turned into an inferred reset. → P11-3.1
   (`sdRestartDuringAnOutageGivesTheRuledIncrease`, `aDecreaseWithoutASignalChangeIsCountedNotReset`,
   `aDeclaredSignalThatFailsToFetchNeverBecomesAnInferredReset`).
3. **A package whose `queryParams` key or value carries `$(id)`, `&`, `'`, `;`, a backtick, a space or a newline.**
   Expected: refused at package load (schema and Java validator) **and** by the SSH probe builder before any
   session. Nothing reaches `session.exec`. → P11-1.1 (negatives), P11-4.2 (`aShellMetacharacterInQueryParamsNeverReachesExec`).
4. **A chart over 12 instances where the caller may see 9 and 2 of those have no data.** Expected (M-Q17 (a)): the 3
   unauthorized instances are omitted exactly as nonexistent ids would be. `partial.unavailable[]` names only the 2
   authorized ones. The series cap counts rendered series over the authorized set, and more than 10 without
   aggregation is `422 SERIES_CAP_EXCEEDED`. → P11-7.2 (`unauthorizedInstancesAreOmittedLikeNonexistentOnes`,
   `theCapCountsRenderedSeriesOverTheAuthorizedSet`).
5. **The partition runway runs out, or the database refuses writes for 5 minutes (S-J).** Expected: **only metrics
   ingest** degrades. It is counted (`runway_degraded` / `buffer_dropped`) and shown in capabilities' `collector`
   block. Config, apply, health and login stay up. The loss shows as a gap, never as zeros. → P11-3.1
   (`runwayExhaustionDegradesIngestOnly`, `aDatabaseOutageBuffersThenDropsOldestAndCounts`), P11-2.1
   (`aBootWithNoRunwayStillStarts`).

**Review cadence for these (`OR-92`, OQ-25 (b)).** Each wave's single Opus review reads these five classes and the sixth below against the wave's
pinning tests, not per task: MW3 (items 2, 5), MW4 (items 1, 2, 3), MW5 (the sixth), MW7 (item 4); a finding is fixed in the wave's one fix
round, and the full build runs at the wave's end. The combined Fable 5.1 review at the end replaces P11-10.1 Step 4.

**Also pinned** (the sixth most likely): a File Mesh `start` event carrying `password` reaches the push subscriber.
Expected: the password appears in no table, log, counter or exception. → P11-5.1 (`aPayloadSecretNeverLeavesTheDecoder`).

---

## Prerequisites

Measured at `bd802fc` (2026-10-05). **MW0 (docs only) needs none of these** (`OR-91`); they gate every later wave.

1. **Plan 010 gates are per task, not one wave** (✅ OQ-28 (b) ruled, `OR-93`; the paragraph below describes (a) as the status quo not taken). The [per-task gate table](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)
   names, for each task, the plan 010 merge it still waits for and why; this item is only its summary. Status quo
   (**OQ-28 (a)**): plan 010 W10 (S7: T7.1 `SshSessionPool` and T7.2 `HealthPoller`, plus everything before them) for every
   code task, and W11 for the tasks that touch declare, stage or `console.yaml`. Under OQ-28 (b)/(c) several tasks have
   narrower gates. ⛔ **At `bd802fc` neither T7.1 nor T7.2 has landed** (no `SshSessionPool`, `HealthPoller` or `HostProbe`
   class exists); `T4.4`, `T5.3`, `T6.1` and `T6.2` are built on unpushed local branches (read-only `git show`, 2026-10-05).
   See the [dependency table](#plan-010-dependencies--exact).
2. ✅ **Plan 010 was amended by M-Q2b (`OR-71`) and then by `OR-82` before its S4 built, and it landed with names that
   differ from this plan's first draft. Plan 011 rebinds to the landed names.** Measured:
   - `sm_data.instance_state.acquisition_mode text NOT NULL CHECK (acquisition_mode IN ('direct-http','via-ssh'))`
     (`V007__instance_inventory.sql:39-40`), instance-level, immutable in v1 (in no `UPDATE` grant), replacing the
     draft's `placement.health_acquisition`.
   - ⛔ **There is no `direct_host` column, and no address of any kind on the instance** (`OR-82`; the column's absence is
     stated at `V007__instance_inventory.sql:34-38`). In `direct-http` mode the DP polls the **target's registered
     address**, `sm_data.target_connection.host` (`V007…sql:94-95`; grammar `^[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?$`,
     so a DNS name or an IPv4 literal, never an IPv6 literal, a path or userinfo), **mutable by rotation and read fresh
     each round**, at a port the DP holds. A re-target follows the new target's address with nothing carried forward.
   - `InstanceView.acquisition()` (an `AcquisitionMode`) in `dp-api`, **not** `acquisitionMode()` and **not**
     `directHost()`; `DeclareInstanceCommand.acquisition` is the mode only (`DeclareInstanceCommand.java`).
   - The declare body carries `acquisition: {mode}` and nothing else (`console.yaml` `Acquisition`, closed;
     plan 010 T4.4's `declareBodyWithAnAcquisitionHostIsRefused422`). **T4.4 had not landed at `bd802fc`**; it is built on `p010/merge3`, where
     `acquisition` is a `JsonNode` (so an extra key is a `422`, not a `400`) and `healthPort` sits inside the nested `Placement`.
   - T7.2 reads the instance-level mode once per instance per round and polls `target_connection.host` at
     `health_port`/`health_path` (plan 010 T7.2, `010-…md:3733-3770`).

   **Everything in this plan that named `direct_host`, `directHost()` or a browser-supplied host is rebound** (P11-2.2's
   `MetricsDeclaration`, P11-4.2 `DirectHttpFetcher`, P11-4.3 `CollectionPlanBuilder`, P11-4.4). **If T4.4 or T7.2 land
   with other names**, each plan 011 task rebinds again and says so in its commit.
3. ✅ **Plan 010 T0.3 (with the S0-records task) has registered the monitoring rulings**: `OR-69`, `OR-71`…`OR-81`, and
   `OR-82`…`OR-91` beside them (`DECISIONS.md`). `OR-80` carries M-Q16's supersession and its forward pointer in the `D10`
   approval is already written. P11-0.1 is the plan 011 side of the record, and registers nothing.
4. ✅ **Plan 010 T0.4b's licence gate is in the build** (`backend/licence-allowlist.txt`, `licence-exceptions.txt`,
   `licence-third-party.ftl`; plan 010 T0.4b Results). Plan 011 adds no dependency, and the gate proves it.
5. **The EC2 test host.** This is t4g.medium: 2 burstable vCPU aarch64, 4 GB RAM, 20 GB disk with 18 GB free
   (measured by the owner, M-Q8), shared with cache-solution. It is reachable over SSH (`scratchpad/e2e-target.md`,
   never committed). ⚠️ **Running PostgreSQL there is a change on the owner's host, and P11-8.1 Step 0 asks the
   owner first** (M-Q8).
6. **The worktree is clean** apart from `.playwright-cli/` (never staged). Run `git status --porcelain` before each
   task.
7. ✅ **The pin-test drift the first draft feared is resolved in plan 010's code, not in its text.** At `bd802fc`
   `SketchMirrorTest` mirrors **only V001–V006** (`SketchMirrorTest.java:199`, `compareTo("V007") < 0`), carries the
   Phase 0 statements added after the sketch in `PHASE0_AFTER_THE_SKETCH` (`:47`) and pins Phase 0 at **9**
   (`:55-56`); `SmDataBootstrapIT` pins Phase 0 at 9 (`:65`), **10** migrations (`:170-176`) and exact table, function and
   trigger sets (`:199-245`). So P11-2.1 follows T2.4's convention (append its two statements to
   `PHASE0_AFTER_THE_SKETCH`, 9 → 11 in both files) and **does not edit `eda-walking-skeleton.md` §3.1**. P11-2.2 and
   P11-5.1 need **no** `SketchMirrorTest` edit (V007 onward is outside the mirror) and edit only `SmDataBootstrapIT`'s
   exact sets. Plan 010's text still never names `SketchMirrorTest` (measured: no match in `010-…md`); the code is the
   record, so there is nothing for the integrator to amend.

---

## Rulings this plan builds on (index)

The rulings are registered in [`DECISIONS.md`](../../design/DECISIONS.md) (the "Registered as" column), and verbatim
in the integrator's `monitoring-owner-rulings.md`. This table only says where each one lands. **No task reopens a
ruling.** Where this plan reads a ruling more narrowly or differently than its registered row, the row says so, and
the difference is an owner question, never a silent choice.

| Ruling | Registered as | What it fixes | Tasks |
|---|---|---|---|
| M-Q1 (b) | OR-69 | Bounded owner rulings. The owner decides for the architect group. The DM design's adversarial review runs **before storage** | P11-0.1, P11-0.2 |
| M-Q2 | OR-71, amended by OR-82 | Both direct HTTP and SSH + host-loopback curl, **chosen per instance** at declare (direct first). No automatic fallback. A change of mode later is a future operation. ⚠️ **`OR-82` (2026-10-05) amends the ruling's "DP-reachable host:port per instance (declare body)"**: direct mode polls the **target's registered address** (`target_connection.host`) at the package's port (here, the host port `metricsPorts` maps to), and nothing about the address comes from the browser | P11-4.2, P11-4.3; runbook residual in P11-4.4 |
| M-Q2b (a) | OR-71 | Health follows the same per-instance mode (**amends OR-39**; plan 010 is amended and has landed it, [Prerequisite 2](#prerequisites)) | consumed by P11-4.3 |
| M-Q3 (b) | OR-72 | A separate `MetricsCollector` on OR-49's list as the **fourth named caller**. A bounded worker pool, per-target cap, next-due scheduling with jitter, and deadline = interval | P11-4.3 (the caller; the javadoc already names it, see the T2.4 row of the [dependency table](#plan-010-dependencies--exact)). Plan 010 already carries the amended wording (Global Constraints `I5`, `010-…md:59-68`; T7.1, `:3719`), so **nothing is handed to the integrator**. ⚠️ **`OR-92` (OQ-6) widens the named caller** from "the metrics collector" to "the metrics background loop" (collection, rollups, push reconcile); `OR-72` is unedited |
| M-Q4 (c) | OR-73 | A per-kind discriminated `source` | P11-1.1 (agent branch: **out of this change**, `OR-92` OQ-2 (b); ⚠️ amends the ruling's "now"; `OR-73` unedited) |
| M-Q5 (a′) | OR-74 | A write-once `sm_data` copy per package version, written in declare's `reserve` transaction. A package version is immutable once loaded, and a digest mismatch fails closed. ⚠️ **`OR-82` also overrides `OR-74`'s clause** *"The instance carries `declaredPackageVersion`, the acquisition mode and the direct host and port"*: there is **no** direct host or port on the instance (`V007…sql:34-38`). `OR-82` names only `OR-71` as amended, so the registered `OR-74` sentence is contradicted without being flagged. This plan follows `OR-82`; an `I3` clarifying row (`OR-74` unedited) is drafted for the integrator in the [hand-off](../../tasks/plan-011-mw0-integrator-handoff.md) §A7 | P11-2.2, P11-4.1 |
| M-Q6 (b) | OR-75 | Quarantine is deferred. Undeclared names are dropped and counted (*"per instance+source"*). The ingest port reserves `pending`. `metric.declare` is a contracted refusal. `histogram` is skipped and shown as `unsupported`. ⚠️ M-Q6's *"per instance+source"* and M-Q21's per-placement counter key pull apart for a push event with no single placement: **OQ-20** | P11-3.1, P11-1.2, P11-7.2; OQ-17, OQ-20. ⚠️ **`OR-92` OQ-17 (a): drop counts are shown per placement**, which departs from "per instance+source" (`OR-75` unedited); OQ-20 stays OPEN (`OR-95`) |
| M-Q7 (a) | OR-75 | Series identity (8 parts), with `package_version` as an attribute. On dp-remote, `binding_id := placement_id`, `source_key := placement_id`, and the discriminator is `dp-poll`. **I1 condition** | P11-2.1, P11-3.1 |
| M-Q8 (b) | OR-76 | Build storage, then a gate wave: a **one-month** benchmark of S-A on the EC2 host, extrapolated to six months (labelled), plus a scaled 5 s point | P11-8.1 |
| M-Q9 (d′) | OR-75 | The gap rule, plus an independent restart signal declared by the package. SSH mode uses docker `State.StartedAt` + container id. Neither available → decrease-as-reset, catalog "restart detection: inferred". `reset_count` per series. `cache.hit_rate` is stored as-is. **Reconciled (a fact the plan keeps; nothing to hand over):** the ruling's verbatim parenthetical *"reuse health inspect when fresh"* has nothing to reuse. Measured: plan 010 T7.2's poller probes health with `HostProbe` curl or direct HTTP, and `docker inspect` stays in the apply engine's health gate only (`010-…md:3767`). So the collector's own fixed inspect **is** the M-Q9 SSH signal (P11-4.2). The registered `OR-75` row **omits that parenthetical**, so no registered text contradicts this. **SM-D30 (the ruling's own instruction, *"Reconcile with SM-D30 in the ruling text"*):** `OR-75` already records that guard 2 fires only where a restart signal exists. This plan's reading is the same and finer: SM-D30 guard 2 (a decrease is a type suspect) fires **only for a source whose restart detection is `declared` or `docker`**, where a decrease with an unchanged token is `decrease_without_restart`. For a source marked `inferred`, guard 2 **stays dead**: every decrease is taken as a reset, so no suspect is ever raised there. Whether a source is `declared`, `docker` or `inferred` is fixed by its declaration and acquisition mode, never by whether a token arrived in one cycle (P11-3.1 rule 3) | P11-1.1, P11-3.1, P11-4.2 (shape: OQ-1) |
| M-Q10 (a) | OR-78 | SM-D31 per-instance shared dashboards in `sm_control` (scope column, only `instance` legal). Query API: **one metric × many instances** | P11-2.3, P11-6.1, P11-7.1 |
| M-Q11 (a) | OR-78 | `console-gateway` hosts the routes (OR-10's composition module), plus a gateway-planted JDBC fixture | P11-7.2 |
| M-Q12 (revised) | OR-73 | **Path 2 built here**: the DP subscribes to the customer broker, applies the I6 filter before write, and serves REST history. **The ruling's live-delivery leg** (*"live delivery to browser via broker topic"*) is **not deferred by this plan**. The backend half is a **conditional P11-5.2 in this plan** that opens only once open items 32/33/35 are closed (OQ-14, `OR-95`). Path-2 placement and binding attribution is **OQ-15**; where an unattributable drop is counted is **OQ-20**; which enumeration the push reconcile rides is **OQ-6**; which metric a `solace_event` source can actually serve is **OQ-29**; whether LOCKED `B/D5` bars the wave is **OQ-26**. ⚠️ OQ-14's option (b) would move the live leg out of plan 011, which **departs from M-Q12's "build path 2 in plan 011"**; only the owner can move it. Path 3: the DP ingest port only | P11-5.0, P11-5.1, P11-3.1; OQ-6, OQ-14, OQ-15, OQ-20, OQ-26, OQ-29 |
| M-Q13 (c′) | OR-77 | `ensure_metric_partitions()`: owner-owned, no arguments, `EXECUTE` to the runtime role, a closed name grammar, CREATE + CHECK + ATTACH, `lock_timeout` and retry, called at boot and daily, each migration seeding 3 months ahead. **The narrow I5 carve-out.** Destructive work is an operator command. Runway exhaustion degrades ingest only. ⚠️ **Two points go to the owner as OQ-21 (confirm-only, at the main sitting); neither is the ruling's text, and neither is recorded before the answer:** (1) the ruling names `sm_data.ensure_metric_partitions()`; this plan builds **`sm_metrics.ensure_metric_partitions()`**, because M-Q19 puts the partitioned table in `sm_metrics`. (2) The grammar stays **exactly** the ruled `metrics_raw_YYYY_MM`: `metrics_rollup_1h` is **unpartitioned** (OQ-21 (a), the recommendation), like `metrics_rollup_1d`. Only an owner (b) would partition it and widen the grammar to two parents. `OR-77` is registered as the ruling's text (`sm_data.ensure_metric_partitions()`, one parent), so these two readings need a **clarifying row** (`I3`: a new row, `OR-77` unedited), **drafted only after OQ-21 is answered** (P11-0.1 Step 4b). It names **both** sanctioned identifier cases: the function's clock-derived names, and `MetricsPartitionMain`'s catalog-resolved month (Global Constraints `I5`, case 2) | P11-2.1, P11-3.2; OQ-21 |
| M-Q14 (a) | OR-74 | A declare-body `metricsPorts` map (package source port → host port) and an immutable `placement_metrics_endpoint`. ⚠️ **`OR-82`:** the host port is a port **on the target's registered address** (direct mode) or on the host loopback (SSH mode); the body still carries no host. The ruling's "direct: M-Q2 host + mapped port" reads "the target's address + the mapped port". ⚠️ **Where the map sits:** the ruling says *declare-body* `metricsPorts` that *mirrors healthPort*; the built declare body nests `healthPort` under `placement` (`DeclareInstanceRequest.Placement`, `console.yaml`), so this plan puts `metricsPorts` in **`placement`**, beside `healthPort`, which is a reading of *declare-body* that the ruling's words allow (the same holds for the per-placement rows it creates). Same `OR-74` clause as in the M-Q5 row | P11-1.2, P11-2.2, P11-4.1 |
| M-Q15 (a) | OR-74 | The gateway evaluates `when:` with cp-core's evaluator. The active metric-name list is stored per immutable `config_generation`, and the collector switches to it at `restart-observed` | P11-2.2, P11-4.1, P11-4.3 |
| M-Q16 (b) | OR-80 | The Operator gains `metrics.export` and `metrics.forceRaw`. ⚠️ **This supersedes part of the RATIFIED D10 amendment** (`D10-G2-APPROVAL-2026-09-15.md:264-266`, limitation 4). It is a NEW decision (`OR-80`, registered); the approval text is not edited, and **its forward pointer is already written at limitation 4** (`:266`). The role table (`:240-245`, "Operator … **19**") carries no pointer, and `OR-80` itself states the 19 → 21 change, so that is not a plan 011 duty | P11-2.3, P11-8.1 (runs as Operator); P11-0.1 only writes the `console-integration.md` pointer `OR-80` says is still unwritten |
| M-Q17 (a) | OR-79 | OR-51 reach filtering governs metrics queries. DM §5.8's "unauthorized" branch (`dynamic-metrics/design.md:425`) is superseded. **Replaces** the M-Q10 "404 the whole query" default | P11-7.2, P11-0.2 |
| M-Q18 (a) | OR-79 | Export is a bound, streaming `SELECT` with CSV encoded in the application. Measure streaming first. Correct the DM §5.8 / architecture §8.4 wording | P11-6.2, P11-0.2 |
| M-Q19 (b) | OR-76 | A DP-owned `sm_metrics` schema in the same database with its own Flyway history. Intra-DP FK allowed. Two histories in one run: **measure first**. Three common items: a bounded query/export connection budget (**P11-3.1** creates the one `MetricsDbBudget`); a **disk-usage alarm** (**P11-3.2** measures it, **P11-1.2** contracts it as `capabilities.collector.disk`; the measure and threshold are **OQ-16**); tenant erasure by `tenant_id` (a new open item, P11-0.1) | P11-2.1, P11-3.1, P11-3.2, P11-1.2, P11-6.2 |
| M-Q20 (b) | OR-75 | No cross-placement gauge aggregation. Counters sum. Non-overlapping placements form one line | P11-6.1 |
| M-Q21 (a) | OR-79 | A durable `sm_metrics.ingest_counter` with a closed reason list, surfaced in capabilities' `collector` block and the catalog. The table is keyed per placement and has no metric column, so what the catalog shows **per metric** is **OQ-17**. Its `placement_id NOT NULL` key cannot hold a drop that has no single placement: **OQ-20** | P11-2.1, P11-3.1, P11-7.2; OQ-17, OQ-20 |
| M-Q22 + M-Q24 | OR-79 | Eleven defaults, all configurable (listed in P11-6.1/6.2/7.2). Item 27(b) is settled for stored data (flagged). Item 28 is narrowed | P11-6.x, P11-7.2, P11-8.1 |
| M-Q23 (a) | OR-78 | The demo dashboard document **shape**, generalized. One instance per saved widget. **REQ-010** | P11-0.1, P11-7.1 |
| `B/D5` 🔒 | `DECISIONS.md` (**LOCKED**) | *(not a monitoring ruling.)* All three runtimes in v1; **hard gate:** a thin walking skeleton (one generated screen, one graph, one role, one Docker `service` deploy with health + telemetry, accepted end to end) must pass **"before any systemd/K8s/scheduled/File-Mesh work begins"**. Path 2 (M-Q12 `OR-73`, *"what we have in file mesh"*) is built in MW5, and `OR-73` does not name `B/D5` (`OR-1`: a LOCKED change names the decision and its scope). Cited first by P11-0.2 (DMR-25). **LOCKED; read by the owner as not holding a generic, package-driven path 2 (`OR-94`, OQ-26 (a)); `B/D5`'s text is unedited** (a reading, not an amendment) | P11-5.1 (MW5); OQ-26 |
| *(not a ruling)* D-2…D-17 | — | **Discovery defaults; accepted by the owner as a group on 2026-10-05 (`OR-92`, OQ-22), still not part of M-Q1…M-Q24** (discovery §3.2: *"adopted unless the owner overrides"*; the owner has not seen them). The ones this plan leans on: D-2 (duplicate `DO NOTHING`), D-3 (derived gaps, interval history), D-4 (dedicated executor), D-6 (tier thresholds 7 d / 90 d, raw-tail stitch), D-8 (delete the orphan V001; narrow open item 3), D-10 (empty label allowlist), D-11 (no metrics notify; bears on OQ-14 (c)), D-12 (`GET /export/range`), D-16 (the S-J buffer), D-17 (nothing deleted on retire; tenant erasure raised as an open item). Each is put to the owner as **OQ-22**, one accept-all question; until then each citation below reads "discovery default" | the tasks that cite them; OQ-22 |

---

## Owner questions — open (for one sitting, before MW1)

> **Owner sitting held 2026-10-05 (`OR-92`…`OR-95`).** **25 of the 29 questions are RULED**: the 21 routine ones by `OR-92`
> (OQ-1, 2, 4, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18, 21, 22, 23, 24, 25, 27, each **as recommended**, including the three
> wording amendments below), OQ-28 by `OR-93`, OQ-26 by `OR-94`, OQ-14 by `OR-95`, and OQ-19 earlier by `OR-91`. **Four stay OPEN,
> deferred until MF26 is measured (`OR-95`): OQ-3, OQ-5, OQ-20 and OQ-29**; they gate **MW5 only**, so P11-1.1 now leaves the
> `solace_event` branch out (its own text, "nothing is landed provisionally") and P11-5.1 adds it in its own reviewed contract
> change once they are ruled. **Three of the 21 reword an earlier ruling** (M-Q4 (c) by OQ-2, M-Q3 by OQ-6, M-Q6 by OQ-17); the
> registered `OR-71`…`OR-81` rows are unedited (`I3`), and the [rulings index](#rulings-this-plan-builds-on-index) says which. The
> Question cell of each row below carries its status; the Options/Recommendation text is kept as asked, and each task names what
> was ruled. This plan is **still a DRAFT, not approved**: only the owner approves it.
>
> **Since the sitting (recorded at close, 2026-10-07):** the four deferred questions are ruled. **OQ-29 `OR-106`** (2026-10-06, option (b): a count-of-matching-events extraction); **OQ-3, OQ-5 and OQ-20 `OR-107`** (2026-10-06: one broker connection per (tenant, environment) by `BrokerConnectionMain`, the payload-form key match, and a placement-less drop counted at the broker connection). The four rows below keep their sitting-day text and are not edited (`I3`). The owner approved the plan by `OR-97`.

The rulings do not settle the points below, and **this plan may not decide them**. All but one are put in **one
owner sitting**, held after P11-0.2 **and P11-5.0** (both MW0, both executed on `p011/mw0`, whose facts are merged here),
so execution stops once and not twenty-eight times, and so P11-1.1 lands the `solace_event` branch **once, in its ruled
shape** (OQ-5, OQ-15, OQ-20 and OQ-29 have their facts by then). The one exception was **OQ-19**, asked before MW0 and
**ruled 2026-10-05 (`OR-91`)**. `OR-91` lists the remaining questions as OQ-1…OQ-18 and OQ-20…OQ-22; **OQ-23, OQ-24 and
OQ-25 were raised by the 2026-10-05 refresh, OQ-26 and OQ-27 by P11-0.2's design review
([`dm-design-review-2026-10.md`](../../tasks/dm-design-review-2026-10.md) §4, DMR-25 and DMR-34), and OQ-28 and OQ-29 by the
pre-sitting plan review**; all of them join the same sitting, so the sitting covers **OQ-1…OQ-18 and OQ-20…OQ-29**. Each row
names the first task that stops if the question is still open. Each task is written for the recommendation, and the
alternative is noted in the task. A different ruling changes that task before it starts. **Three rows are coupled and are
ruled together: OQ-5, OQ-20 and OQ-29** (the attribution route decides how large OQ-20's blind spot is; OQ-29 decides what a
`solace_event` source can extract at all).

| Id | Question | Status quo (measured) | Options — delta | Recommendation | Gates |
|---|---|---|---|---|---|
| **OQ-1** | **The shape and scope of M-Q9's "package-declared restart signal".** **Status: RULED 2026-10-05, `OR-92`: (a), as recommended: an optional `restartSignal` per component, used in direct mode; SSH mode always uses docker `StartedAt` + container id, so it needs nothing from the package. One start-time fetch per placement per cycle.** | The ruling names two signals: *"direct mode = MI's own start-time metric over HTTP … SSH mode = docker State.StartedAt … + new container id"*, and *"Neither declared → fall back to decrease-as-reset"*. It does not say whether the SSH-mode signal is declared, or where the HTTP signal is declared (per metric, per source or per component). Cache serves `/actuator/metrics/process.start.time` on 8081 (measured on EC2, 2026-09-30). The body is Boot's `{"name","measurements":[{"statistic","value"}]}` shape [V: MF8] | **(a)** An optional `restartSignal` per **component**: `{kind: http_json, path, port, pointer}`. It is used in **direct** mode. **SSH** mode always uses docker `{{.Id}} {{.State.StartedAt}}`, which is an SM-held runtime observation, not a declaration. Direct mode with no declaration → inferred. **(b)** The same, but SSH mode also uses the declared HTTP signal when present, and docker only when absent. **(c)** Per source instead of per component: one more fetch per source | **(a)**: one fetch per placement per cycle, no per-source repetition, and the SSH path needs nothing from the package | P11-1.1 |
| **OQ-2** | **Does M-Q4 (c)'s agent branch land in this plan's schema change, or in the agent-gateway plan's?** **Status: RULED 2026-10-05, `OR-92`: (b), as recommended: the agent branch is **not** in P11-1.1's schema change; it lands additively, under the discriminator, with the agent-gateway plan. ⚠️ **Amends M-Q4 (c)'s "now"** (`OR-71`…`OR-81` text unedited).** | **The rulings pull two ways.** M-Q4 (c): *"per-kind discriminated `source` **now** (… agent: collection spec + cadence)"*. Against it, M-Q6: *"Path-3 user-configured agent metrics are declarations (**agent-gateway plan**)"*, and M-Q12: *"Path 3 = DP ingest port only in 011 (agent itself in the agent-gateway plan)"*. No agent collection spec is designed yet (`AGW-D*` designed, not built; the owner's 5 s / 12-sample / 1-minute push note is design input for that plan) | **(a)** The literal M-Q4 text: add `{kind: agent, cadenceSeconds, collection: {…}}` now, with `collection` shaped ahead of the agent design. **(b)** Here: the discriminator plus the poll and push branches; the agent branch is added **additively** under the discriminator by the agent-gateway plan's own reviewed contract change | **(b)**. ⚠️ **This departs from M-Q4's literal "now"**, for M-Q6's and M-Q12's reason: the agent declaration belongs to the agent-gateway plan, and nothing is schema-ahead-of-design. The discriminator keeps the later branch additive. Only the owner can pick between the two ruled texts | P11-1.1 |
| **OQ-3** | **Where path 2 gets its broker coordinates and credential.** **Status: OPEN, deferred until MF26 (`OR-95`).** The owner deferred this question until MF26 (Solace topic case-sensitivity) is measured; it gates **MW5 only**. Rule OQ-3, OQ-5, OQ-20 and OQ-29 together. | Nothing in `sm_data` or the contracts names a customer broker for MI business metrics. `target_connection` is SSH only (plan 010 T2.1). The meshbridge publishes to SM's own mesh (`MeshBridgeConfiguration`). No verb authorizes binding an instance to a broker. ⚠️ **Refresh 2026-10-05 (measured):** plan 010 T2.7 landed `TargetConnectionMain` (the shape (a) copies). But `SecretPurpose` is a closed set (`SSH_PRIVATE_KEY`, `CONFIG_FIELD`, `CONFIG_HASH_KEY`, `CANARY`; `SecretPurpose.java`), and `sm_secret.secret`'s `secret_purpose` CHECK admits only the first three (`V009__secret_store.sql:23-24`). The purpose is part of the authenticated data (`BE-D28`). So (a) also needs a purpose for a broker credential: a new value (for example `BROKER_CREDENTIAL`, with V016 widening the CHECK and a new `BE-D` amending `BE-D28`'s closed set), or `CONFIG_FIELD`, which would label a broker credential as a config field in its own authenticated data | **(a)** One **broker connection per (tenant, environment)**, registered by a DP operator command `BrokerConnectionMain` (the `TargetConnectionMain` shape: runtime DP credential, credential into `sm_secret` by reference). Every instance in that environment with a push source uses it. No declare-body field, no new verb. **(b)** Per instance: a declare-body `brokerConnectionId`. This needs a verb for "may bind an instance to this connection", which reopens the closed catalog (OR-9's route). **(c)** Reuse SM's own mesh connection. This only works if the MI publishes to SM's VPN, which File Mesh does not (UNVERIFIED: P11-5.0 read File Mesh's topics and payloads, not which broker it is configured to publish to; MF13) | **(a)**, with a new secret purpose rather than `CONFIG_FIELD` (the purpose is confirmed at the sitting) | P11-5.1 |
| **OQ-4** | **The push-path sample timestamp.** **Status: RULED 2026-10-05, `OR-92`: (a): a pushed sample's `ts` is the DP's UTC receipt instant; the MI's timestamp is ignored in v1.** | M-Q24 (8) rules the timestamp for **polls** only (the midpoint of request-sent and response-received). File Mesh's header timestamp has **no zone**. **Measured by P11-5.0** ([`011-filemesh-publisher-facts.md`](../../design/backend/evidence/011-filemesh-publisher-facts.md) F14): it is `yyyy/MM/dd HH:mm:ss.SSS` in the **connector host's local time** (on the file-complete capture, `timestamp` equalled `completionTime` plus 5 h 30 min; the capturing host was UTC+05:30, another host's offset is not knowable from the event), with a variable-width fraction (14 of 34,509 heartbeats carry two fractional digits), so option (b) would also need a declared zone and a tolerant parser (`file-mesh-ui/solace-error-listener-service/src/app.ts:70`, discovery §1.6) | **(a)** The DP's UTC receipt instant is `ts`. The MI's timestamp is ignored in v1. **(b)** A package-declared timestamp pointer, with a declared zone | **(a)**: no guessed zone, and ordering is DP-clocked | P11-5.1 |
| **OQ-5** | **How a pushed event is attributed to an instance without an MI change, and which topic forms a package may declare.** **Status: OPEN, deferred until MF26 (`OR-95`).** The owner deferred this question until MF26 (Solace topic case-sensitivity) is measured; it gates **MW5 only**. Rule OQ-3, OQ-5, OQ-20 and OQ-29 together. | **Measured by P11-5.0 (MW0)**, [`011-filemesh-publisher-facts.md`](../../design/backend/evidence/011-filemesh-publisher-facts.md) (`p011/fm` `0127b50`, merged here; `F` numbers are that file's). It settled **the key**, not the publisher: `ADAPTER_ID` is an ordinary, non-secret config field SM already writes (F18, F19; gate 16 would not refuse it), it is topic level 7 of nine and `header.adapterID`, and the two matched in **34,527 of 34,527** captured events (F8). It did **not** read publisher code: `commons@cd52e31` has the event models but no topic builder or serializer, and the connectors are not on disk (§1); six of the eight event states are unmeasured. Four facts the earlier text lacked. (1) `{instanceKey}` substitutes **one** level, but the base topic is a free-text field of any depth (F4), so a depth-pinned pattern such as `*/*/*/*/*/*/*/{instanceKey}/heartbeat` works for the generated nine-level default and **silently selects nothing** for a customized base; Solace `*` matches one level and `>` only the last (verified 2026-10-05), so no wildcard fixes the second-to-last level of an unknown depth. (2) Whether Solace matches topics **case-sensitively** is unverified (**MF26**), and two captured adapter ids differ only by case (`…_V1` and `…_v1`, §2.4). (3) The payload route (`/header/adapterID`) is depth-independent but needs a broader subscription and a decode before the match (F7). (4) `start` may carry a plaintext broker password (F15), and the state is the last topic level (F16). `I1` forbids asking the MI to add an id; every option below reads fields the MI already emits. | **(a)** The package declares `instanceKey: {configField: "<key>"}`: a config field SM itself writes, matched against a declared topic level **or** a payload pointer. An event with no match is dropped (where it is counted: OQ-20). Delta: the topic form pins a depth and fails closed for a customized base; the payload form is depth-independent but subscribes more broadly, which enlarges OQ-20's blind spot. **(b)** Topic-level only. Delta: sufficient only where the base topic is the generated default, and the package author must be told the nine-level assumption. **(c)** Another shape, stated at the sitting. **(c′)** A **multi-field substitution**: the pattern names the base-topic field and the adapter id (for example `{baseTopic}/{instanceKey}/heartbeat`), so the subscription is exact at any depth. Delta: `topicPattern` grows a second substitution token whose field must also be non-secret (gate 16 extended) and whose value is free text needing its own grammar check, and a change of the base field re-subscribes. **Under every option (`I6`):** the `instanceKey` field may **not** be typed `secret` or carry `secret: true` (a secret would be resolved into a broker subscription, which lands in broker logs and ACLs); **and the last level of a `solace_event` `topicPattern` is a literal** (no `>`, `*` or substitution), so a subscription names its states and a `start` event never reaches the DP. Both validators refuse a violation at load (P11-1.1: gate 16 and the grammar), unless the owner rules otherwise here. | Chosen with P11-5.0's facts in hand, at the main sitting, **ruled together with OQ-20 and OQ-29**, and **not before MF26 is measured** (the match is case-sensitive or it is not). The facts support (a) with both match forms available: the key exists, is non-secret and agrees in every sample. (c′) is the only topic form that is exact at any depth; (b) is enough only for the default base. If a topic route is chosen, OQ-20's blind spot is nearly empty (see OQ-20) | P11-1.1 (the `solace_event` branch), P11-5.1; MF26 |
| **OQ-6** | **Which jobs may ride the collector's OR-49 enumeration: the rollups, and the broker push-subscription reconcile?** **Status: RULED 2026-10-05, `OR-92`: (a): one `MetricsBackgroundLoop` reads `tenantsWithActiveInstances()` once per tick and fans out to collection, rollups and the push reconcile. ⚠️ **Widens M-Q3's named fourth caller** ("the metrics collector", `OR-72`) to "the metrics background loop".** | M-Q3 names **one** fourth caller, *"the metrics collector"*, for collection. Two other jobs need a per-tenant loop: the **rollup** job (P11-3.2) and the **push-subscription reconcile** (P11-5.1, which keeps each tenant's broker subscriptions matched to its active `solace_event` sources). Riding the collector's call extends the named caller's **purpose**, which no ruling does. The runway function is tenant-free and needs no enumeration. Scenarios: S-A (200 instances, one tenant): one enumeration per 10 s tick either way. A tenant whose last active instance retires: under (a) its rollups and subscriptions stop on the next tick with collection; under (b)/(c) each job stops on its own list's next read | **(a)** One `MetricsBackgroundLoop` calls `tenantsWithActiveInstances()` once per tick and fans out per tenant to scrape planning, the rollup (on its own cadence) **and** the push reconcile. Still one named caller; its purpose is widened to "the metrics background loop". **(b)** Rollups ride the loop; the push reconcile is named as a **fifth** caller. **(c)** Both are named callers (fifth and sixth) | **(a)**: one read of the list, and a tenant's metrics jobs start and stop together. It widens M-Q3's caller from "collector" to "metrics background loop", which is **the owner's call** | P11-3.2, P11-4.3, P11-5.1 |
| **OQ-7** | **How a package keeps a metric out of the default dashboard layer without a Cache branch.** **Status: RULED 2026-10-05, `OR-92`: (b): an optional `defaultChart: boolean` (default `true`) on `$defs/metric`; the Cache fixture sets `false` on `cache.hit_rate`.** | M-Q9: *"cache.hit_rate stored as-is; default charts rate(hitCount)/rate(missCount)"*. No schema field says "not a default chart" (`$defs/metric`, `solution-package.schema.json:549-585`) | **(a)** Every active, supported metric gets a default widget, so `cache.hit_rate` appears as a lifetime-ratio line. **(b)** An optional `defaultChart: boolean` (default `true`) on `$defs/metric` in P11-1.1's reviewed change. The Cache fixture sets `false` on `cache.hit_rate` | **(b)** | P11-1.1, P11-7.1 |
| **OQ-8** | **What `POST …/reset` restores.** **Status: RULED 2026-10-05, `OR-92`: (a): reset clears the hide-overrides and default-widget placements and never touches user or shared widgets.** | SM-D31: default graphs are *"replaced wholesale"* on upgrade, and user graphs are *"never touched"*. The demo's reset empties the whole document (`DemoDashboardStore.java:78-84`), which deletes shared widgets | **(a)** Reset clears the hide-overrides and default-widget placements, and keeps every shared widget. **(b)** The demo's behaviour: everything is cleared | **(a)**: it matches SM-D31's "user graphs never touched" | P11-7.1 |
| **OQ-9** | **The interval numbers.** **Status: RULED 2026-10-05, `OR-92`: (a): default 30 s, floor 10 s on dp-remote, schema `minimum: 10`, `maximum: 3600`.** | M-Q3's note: *"dp-remote interval floor proposed 10 s (M-Q4)"*. M-Q4 (c) rules `intervalSeconds` per source, but no default or floor | **(a)** Default 30 s, floor 10 s on dp-remote, schema `minimum: 10`, `maximum: 3600`. **(b)** Other values | **(a)** | P11-1.1 |
| **OQ-10** | **Who runs M-Q13's "VACUUM FREEZE each partition on close", and when.** The freeze itself is ruled (M-Q13); only the runner and the timing are open. **Status: RULED 2026-10-05, `OR-92`: (a): `MetricsPartitionMain freeze` is a monthly operator command (owner credential, separate process) after the month closes; `unfrozenClosedPartitions` makes a missed run visible.** | [V] PG 14: *"To vacuum a table, one must ordinarily be the table's owner or a superuser … VACUUM will skip over any tables that the calling user does not have permission to vacuum"*, and *"VACUUM cannot be executed inside a transaction block"* (MF3). So neither the runtime role nor the `SECURITY DEFINER` function can freeze | **(a)** `MetricsPartitionMain freeze` (owner credential, a separate process), run as the runbook's monthly step after the month closes. The collector-health block shows `unfrozenClosedPartitions`. **(b)** The same command, run from an operator-scheduled job (cron or a Kubernetes `CronJob`) that the runbook specifies, with the same credential rule | **(a)**: one manual monthly step, and `unfrozenClosedPartitions` makes a missed run visible | P11-2.1 |
| **OQ-11** | **Archive before drop, without encryption.** **Status: RULED 2026-10-05, `OR-92`: (a): `archive` writes gzip CSV plus a sha256/row-count manifest, unencrypted in v1 (the runbook requires an encrypted destination); `drop` refuses without a verified manifest.** | Architecture §8.5 requires archive → verify → drop. The discovery routed "archive destination and encryption" out of v1. Without a drop, six months of raw grows without bound | **(a)** `MetricsPartitionMain archive` writes gzip CSV plus a sha256/row-count manifest to an operator-supplied directory, **unencrypted** (the runbook requires an encrypted destination). `drop` refuses without a verified manifest. Encryption is routed. **(b)** Detach only in v1, with no drop | **(a)** | P11-2.1 |
| **OQ-12** | **A sample older than its series' last sample** (a clock step-back, or a replayed buffer). **Status: RULED 2026-10-05, `OR-92`: (a): a new reason `out_of_order` (dropped and counted).** | M-Q21's reason list is closed and has no reason for it. Discovery default D-2 makes an exact duplicate `DO NOTHING` | **(a)** Add reason `out_of_order`: dropped and counted. **(b)** Count it as `parse_failed` | **(a)**: a mis-named counter misleads an operator | P11-2.1, P11-3.1 |
| **OQ-13** | **`onUnavailable: degrade` vs `error`** (PROPOSED `UI-D*` field, schema `:622-627`). **Status: RULED 2026-10-05, `OR-92`: (a): in v1 `degrade` and `error` both give a gap plus `scrape_failed`; the catalog reports the declared value.** | Both are declared in the schema. Neither ruling says how they differ | **(a)** v1 treats both as a gap plus `scrape_failed`. The catalog reports the declared value. The distinction is routed. **(b)** `error` marks the metric `state: collection-error` in the catalog, which is a new state value (contract) | **(a)** | P11-4.3 |
| **OQ-14** | **M-Q12's live-delivery leg: which plan builds its backend half, and does it wait for the §11.3 spike?** **Status: RULED 2026-10-05, `OR-95`: (a), conditional: a **conditional P11-5.2** in this plan builds the backend half of the live leg (post-RBAC ticket or ACL scope, leg-13 shape, its own contract change). It opens only once open items 32/33/35 are closed; until then pushed metrics are REST-only. Keeps M-Q12's "in plan 011".** | M-Q12 (revised) rules path 2 as *"DP subscribes …, I6 secret filter before write, ingest, REST history; live delivery to browser via broker topic"*. The browser mesh gates are open items 32/33/35. The owner said *"Let's solve this today and unblock it"*. Leg 13 is ruled (a) (`OR-81`): exact-topic ancestor exceptions, a closed list in `contracts/events`. Leg 14 was answered *"Okay got it"*: the spike measures substitution, with a per-session SEMP user as the fallback. **The console-integration §11.3 ACL spike has run** (round 4, 2026-09-30, on a spike-owned local broker): [`spike-scope-acl-result.md`](../../design/backend/spike-scope-acl-result.md), harness `backend/spikes/mesh-scope-acl`. ⚠️ `OR-81` states that it does **not** open the gates, and the result itself says *"Nothing here declares item 32, 33 or 35 closed, and `BE-D5` is not ratified"*, so items 32/33/35 stay gated until the owner closes them. (The §11.3 header at `console-integration.md:789` still reads "written, NOT executed": stale, routed to the integrator in the [hand-off](../../tasks/plan-011-mw0-integrator-handoff.md) §B.) Nothing in this plan builds the ticket or ACL scope that would let a browser session subscribe to an MI's business-metric topics | **(a)** A conditional **P11-5.2** in this plan. It opens only when items 32/33/35 are closed (the spike result and legs 13/14 are the evidence). It builds the backend half: the post-RBAC ticket or ACL scope for the MI's business-metric topics, under the ruled leg-13 shape, with its own contract change. **(b)** The follow-up plan that closes items 32/33/35 for every live surface builds it, and this plan's MW5 stays REST-only until then. **(c)** Re-publish the DP's ingested samples to an SM-owned notify topic and do not grant the browser the MI's topics. This adds a notify event, which discovery default D-11 (not an owner ruling, OQ-22) routes out | **(a)** if the owner closes items 32/33/35 before MW7 opens; **(b)** otherwise. Either way the leg is **scheduled**, not dropped. ⚠️ **(b) departs from M-Q12's text**, *"build path 2 **in plan 011**: … live delivery to browser via broker topic"*: it moves the live leg to another plan, and **only the owner can move it**. (a) is the only option that keeps the ruled text. P11-5.2 is not written until this is ruled | P11-5.1's MW5 claim; a conditional P11-5.2 |
| **OQ-15** | **Path-2 placement and binding attribution** (the rest of M-Q7's identity for broker push). **Status: RULED 2026-10-05, `OR-92`: (a): an event matching more than one live placement is dropped and counted under a new reason `ambiguous_placement`, which extends M-Q21's list. The option's `bindingId := broker_connection_id` clause depends on OQ-3, which is deferred (`OR-95`), so P11-5.1 settles that part when OQ-3 is ruled.** | M-Q7 fixes `binding_id := placement_id`, `source_key := placement_id` and the discriminator `dp-poll` **for dp-remote polls only**. The series identity includes `placement_id`, and `IngestBatch` requires `bindingId`, `placementId` and `sourceKey`. An instance may have more than one live placement: a re-target leaves a retired one, and M-Q20 allows overlapping live ones. OQ-5 attributes an event to an **instance**, not to a placement. **Measured by P11-5.0** ([`011-filemesh-publisher-facts.md`](../../design/backend/evidence/011-filemesh-publisher-facts.md) F1, F8, F20, §5): the event names no placement. Topic and payload carry the adapter id but no SM placement id; `header.host` is the connector machine's OS hostname (its equality with the SM target's registered address is **not established**), `header.ip` was a loopback alias (`127.0.1.1`), and `runID` changes on every adapter run (three run ids for one adapter id in the capture), so none can key; during a re-target overlap two adapters with the same adapter id publish indistinguishable topics | **(a)** The event is attributed to the instance's **single live placement** (`retired_at IS NULL`). `bindingId := broker_connection_id` (OQ-3 (a)), `sourceKey := placement_id`, discriminator `broker-push`. With **more than one** live placement, the event is dropped and counted under a **new reason `ambiguous_placement`**, which extends M-Q21's closed list. **(b)** The same, but more than one live placement is counted as `undeclared_dropped`, so the list stays closed. **(c)** Attribute to the newest live placement. **Under (a) and (b), which `ingest_counter` row the drop is charged to is OQ-20**: an ambiguous event has no single placement to key the row on | **(a)**: an event is never mis-attributed, and a mis-named counter misleads an operator (OQ-12's reason). (b) keeps M-Q21's list closed at the cost of the name. The facts support (a) and (b); no field in the event supports (c) 'newest live placement' | P11-2.1 (the reason CHECK), P11-3.1 (`CollectorReason`), P11-5.1 |
| **OQ-16** | **The disk-usage alarm (M-Q19 "common"): what is measured, and against what threshold.** **Status: RULED 2026-10-05, `OR-92`: (a): the DP measures `sm_metrics` size and database size against an operator-set budget in bytes (`warning` 80 %, `critical` 95 %, `unconfigured` when none is set); visible only, never stops ingest. Capabilities carries only `disk.state`; the byte figures appear only in `MetricsPartitionMain status`.** | M-Q19 lists a *"disk-usage alarm (M-Q21)"*; neither ruling gives a measure or a number. The runtime role may not shell out or read the filesystem, and PostgreSQL exposes relation and database **sizes** through SQL, not filesystem free space (the privilege rules are MF19, UNVERIFIED) | **(a)** The DP measures `sm_metrics`' own size (Σ `pg_total_relation_size` over its leaf partitions and tables, one bound catalog query) and `pg_database_size(current_database())`. It compares them with an operator-set **budget in bytes** (`sm.dp.metrics.disk.budget-bytes`): `warning` at 80 %, `critical` at 95 %, `unconfigured` when no budget is set. The alarm is **visible only**: it never stops ingest. **(b)** The same, but with a default budget, for example 15 GB, from P11-8.1's numbers. **(c)** The operator's own host monitoring only, and no SM alarm, which drops a ruled item. **Under (a) and (b), who sees what (`I5`):** row 53 `getCapabilities` is authentication-only at install scope, so any principal of any organization reads it. The **byte figures** are install-wide totals across every tenant, and would leak other tenants' aggregate data volume in a multi-organization install. So capabilities carries **only `disk.state`** (an install-level health enum, like `runwayEnd`), and the byte figures are printed only by the operator command `MetricsPartitionMain status` (owner credential, a separate process) | **(a)**, with the byte figures out of the API: SM cannot know the disk size without an OS read, so the operator states it once. `unconfigured` is shown, never silently "ok". No new verb is needed | P11-3.2, P11-1.2 |
| **OQ-17** | **What M-Q21's "catalog per metric" shows, when `ingest_counter` has no metric column.** **Status: RULED 2026-10-05, `OR-92`: (a): the catalog `collector` block shows reasons over the last 24 h for the placements serving that metric, labelled "per placement serving this metric"; `undeclared_dropped` is never shown on a declared metric. ⚠️ **Departs from M-Q6's "per instance+source"**; M-Q21's table is unchanged.** | M-Q21 rules the table as `ingest_counter(tenant_id, placement_id, reason, day, count)` and says the counters surface *"via capabilities collector block + catalog per metric"*. **M-Q6 also rules** that undeclared arrivals are *"dropped and counted **per instance+source**"*. The per-placement key cannot say which metric (or which source) a `scrape_failed` belonged to | **(a)** The catalog entry's `collector` block shows the reasons counted, over the last 24 h, **for the placements whose package version declares that metric**. It is labelled "per placement serving this metric", not "per metric". `undeclared_dropped` is **never** shown on a declared metric, only in capabilities. **(b)** Add `metric_name` (or `source_key`) to `ingest_counter`'s key. This follows M-Q6's per-source granularity, but changes M-Q21's ruled shape and multiplies the rows. **(c)** No per-metric block; capabilities only | **(a)**: no change to M-Q21's ruled table, and the label says exactly what the number is. ⚠️ **(a) and (c) depart from M-Q6's "per instance+source"** (they count per placement, with no source); **(b) follows M-Q6 but changes M-Q21's shape.** The two rulings cannot both be met exactly with one table, so the owner picks. P11-1.2 does **not** bake a per-metric shape into `console.yaml` until this is ruled | P11-1.2, P11-2.1 (only if (b)), P11-7.2 |
| **OQ-18** | **Is the "package-default layer that can be hidden and never deleted" a REQ-010 acceptance criterion?** (Asked with OQ-8.) **Status: RULED 2026-10-05, `OR-92`: (a): REQ-010 keeps the owner's three criteria; the default layer is built as the adopted demo shape and SM-D31 (PROPOSED), not as a requirement.** | The owner stated REQ-010's content as three elements: *"per-instance shared dashboards; line/gauge/single-stat; one-metric multi-instance comparison (M-Q10)"* (M-Q23). The default-layer and hide behaviour comes from PROPOSED SM-D31 and the demo's behaviour, which M-Q23 adopts as a **shape**, not as a requirement. P11-0.1 writes REQ-010 with the three owner-stated criteria only | **(a)** Keep REQ-010 at three criteria. The default layer is built (P11-7.1) as the adopted demo shape and SM-D31 (PROPOSED), not as a requirement. **(b)** Add a fourth criterion, sourced to this answer and not to M-Q23 | **(a)**: a requirement credited to the owner carries only what the owner said | P11-0.1 (only if (b)), P11-7.1 |
| **OQ-19** | **May the docs-only MW0 (P11-0.1, P11-0.2, P11-5.0) run before plan 010 S6/S7 merge?** Asked by the integrator before MW0 was dispatched. **Status: RULED 2026-10-05, `OR-91`: (b), "Yes, start MW0 now (Recommended)".** | The rulings close with *"execute after plan 010 S6/S7"*. M-Q1 says the DM review runs *"as plan 011 W0, before storage"*. Both held if MW0 opened after plan 010 W10. MW0 edits only docs. | **(a)** MW0 opens after plan 010 W10 (S6/S7) merges, like the rest of the plan. **(b)** MW0 may start now, in parallel with plan 010 (it is docs only: no code, no migration, no contract). | **Ruled (b).** It relaxes the closing order **for MW0 only**: this plan stays a DRAFT, not approved, every later wave keeps its own gates, and the remaining questions go to one sitting after MW0 | MW0 dispatch (**open no longer**) |
| **OQ-20** | **Where a dropped push event with no single placement is counted.** **Status: OPEN, deferred until MF26 (`OR-95`).** The owner deferred this question until MF26 (Solace topic case-sensitivity) is measured; it gates **MW5 only**. Rule OQ-3, OQ-5, OQ-20 and OQ-29 together. | M-Q21 fixes `ingest_counter(tenant_id, placement_id, reason, day, count)`, and V001 makes `placement_id` `NOT NULL` with an FK to `sm_data.placement` (M-Q19). M-Q6 rules that undeclared arrivals are *"dropped and counted per instance+source"*. Two push drops have **no single placement**: (1) an event that maps to **no known instance**, or to an instance with **no live placement**; (2) an instance with **more than one** live placement (OQ-15). Writing either row needs a `placement_id`, so an implementer would hit `23503` or invent a rule. **M-Q6's "per instance+source" and M-Q21's per-placement key pull apart here.** Scenarios: S-E (File Mesh heartbeat every 5 s) with a topic for an instance SM never declared: 17,280 events a day. S-I (re-target) with both placements live for 2 minutes: 24 events in the overlap. **Measured by P11-5.0** ([`011-filemesh-publisher-facts.md`](../../design/backend/evidence/011-filemesh-publisher-facts.md) §5, F17): the size of the first blind spot depends on **OQ-5's route**. On a **topic-narrowed** subscription the broker delivers only declared adapters' events, so an undeclared adapter's stream never reaches the DP and there is nothing to count. On a **wildcard or payload** route every undeclared running adapter on the VPN is delivered, and each is one full heartbeat stream: **17,280 events a day at the 5 s default**, 51,840 a day for the three adapter ids in the captured sample (§2.4). In that sample 0 of 34,527 events lacked a usable key, which is a floor (one organization, one connector kind, two of eight states) | **(a)** An ambiguous event counts +1 on **every** live placement of the instance (so an overlap of 2 placements shows 24 on each, 48 in total). An event for no known instance, or for an instance with no live placement, is counted **nowhere**, stated in the runbook and the capabilities description as a **known blind spot**. No shape change. **(b)** A tenant-level (or broker-connection-level) counter row for drops with no placement. This **changes M-Q21's ruled shape** (a second key, or a second table). **(c)** A nullable `placement_id` with a partial unique index for the placement-less rows. Also a **shape change** to M-Q21 | **Conditional on OQ-5; rule them together.** **(a)** if OQ-5 picks a topic route (including (c′)): the blind spot is nearly empty, and (a) keeps M-Q21's table exactly as ruled and never charges a drop to a placement the event did not name alone, with the remainder written down, not hidden. **(b)** if OQ-5 picks a wildcard or payload route: (a) would leave every undeclared adapter's stream (17,280 events a day each, F17) uncounted, and (b) is the only option that counts every drop, as M-Q6 reads; it needs the owner to change M-Q21. ⛔ **Until this is ruled, P11-5.1's two tests assert only "dropped, nothing stored", never "+1"** | P11-2.1 (V001's key, only if (b)/(c)), P11-5.1; OQ-5 |
| **OQ-21** | **Confirm two readings of M-Q13 (confirm-only).** **Status: RULED 2026-10-05, `OR-92`: confirmed: (1) the partition function lives in `sm_metrics`; (2a) `metrics_rollup_1h` stays unpartitioned, so the grammar is exactly `metrics_raw_YYYY_MM`. Re-open (2b) only if P11-8.1's measured numbers show the hourly tier needs detach-based retention.** | The ruling names *"`sm_data.ensure_metric_partitions()` … closed grammar (metrics_raw_YYYY_MM, UTC months)"*. M-Q19 later put the partitioned tables in `sm_metrics`. No ruling partitions `metrics_rollup_1h`. At S-A, the hourly tier is 1,000 series × 24 × 183 days ≈ **4.4 M rows in six months** (17.5 M over two years, discovery S-A), small enough to stay unpartitioned like `metrics_rollup_1d` | **(1)** The function lives in `sm_metrics` (M-Q19's consequence), under the same no-argument, owner-owned, `EXECUTE`-to-runtime rules. Confirm, or name the schema. **(2a)** `metrics_rollup_1h` stays **unpartitioned**, so the grammar is **exactly** `metrics_raw_YYYY_MM`, as ruled. The hourly tier is never trimmed in v1 (as `metrics_rollup_1d` already is). **(2b)** Partition `metrics_rollup_1h` monthly and **widen** the grammar to two fixed parents, `metrics_raw_YYYY_MM` and `metrics_rollup_1h_YYYY_MM`. This widens the ruled carve-out | **(1)** confirm; **(2a)**, unless P11-8.1's measured numbers show the hourly tier needs detach-based retention. This plan is written for (2a). The OR-M-Q13 wording is drafted **only after** this answer (P11-0.1 Step 4b) | P11-2.1 |
| **OQ-22** | **Confirm the discovery's §3.2 defaults (one accept-all question, as M-Q22/M-Q24 were).** **Status: RULED 2026-10-05, `OR-92`: (a): the discovery's defaults D-2…D-17 are accepted. Read every "discovery default" in this plan as accepted by `OR-92`.** | Discovery §3.2 lists D-2…D-17 as *"adopted unless the owner overrides"*. The owner's sitting covered M-Q1…M-Q24 only; the rulings file records no sight of §3.2. Several carry real choices: **D-6**'s tier thresholds (≤ 7 d raw, 7–90 d hourly, > 90 d daily) and the raw-tail stitch; **D-8** deletes the orphan V001 and narrows open item 3; **D-11** "no metrics notify", which bears on OQ-14 (c); **D-17** nothing is deleted on retire; D-2, D-3, D-4, D-10, D-12, D-16 | **(a)** Accept all (listed one by one, with the task each lands in). **(b)** Accept all except the named ones, each then a separate row | **(a)**, unless the owner names one. Until the answer, every `D-n` citation in this plan reads "discovery default, not an owner ruling" | every task that cites a `D-n` (first: P11-0.2, P11-2.1) |
| **OQ-23** | **Does anything stop two organizations' placements on one registered machine from naming the same metrics host port?** Raised by the 2026-10-05 refresh, from `OR-89`. **Status: RULED 2026-10-05, `OR-92`: (a): organizations sharing one machine must partition metrics host ports, by a runbook rule (follows `OR-89`). No code; the runbook line is in P11-4.4.** | `OR-89` (2026-10-05) scopes a live target's pinned host key **per organization**: two organizations may each register the same machine, and its stated consequence is *"one host could then be driven by two tenants' configurations"*. `V007` still reads install-wide at `bd802fc` (`target_connection_host_once`, `V007__instance_inventory.sql:118`) while the lane that implements `OR-89` is pending. Every port-collision guard is per `(tenant, target)`: `placement_health_port_once` is `(tenant_id, target_id, health_port)` (`V007…sql:178-179`), and P11-2.2's triggers lock the tenant's own `target_connection` row. Once two organizations hold two targets on one machine, org A's and org B's placements can both claim host port 28081. In `direct-http` mode both collectors then poll `<that host>:28081`; in `via-ssh` mode both curl the same loopback port. Each organization's series would silently hold the other's numbers (`I5`'s intent is violated by data, not by a query). The same hazard exists for the health port in plan 010. ⚠️ **Partly superseded once `p010/merge3` is pushed:** `26919bb` edits `V007` in place so the host-key index becomes `(tenant_id, host_key_fingerprint)` (per organization, `OR-89`), and the 'still reads install-wide' sentence above then goes stale; the question about a host **port** across organizations stands | **(a)** Accept it as `OR-89` accepted it. The runbook states that organizations sharing a machine must partition host ports. No code. **(b)** Add an install-wide guard: P11-2.2 copies a host identity (the pinned host-key fingerprint) onto `placement_metrics_endpoint` and adds a unique index across tenants. That is a cross-tenant constraint rather than a read, but it is a new `I5` pattern, the fingerprint is mutable by rotation (`target_connection`'s `UPDATE` grant), and plan 010's health port would need the same, so it is plan 010's to change. **(c)** Re-open `OR-89` (install-wide again), which is the owner's alone | **(a)**, with a runbook line in P11-4.4: `OR-89` was ruled with this consequence stated, plan 011 may not re-open it, and (b) costs a plan 010 change. If the owner wants the guard, (b) must be designed in plan 010 first | P11-2.2 (the collision tests), P11-4.4 |
| **OQ-24** | **May P11-1.1 make `port` required on an `http_json` source, when that breaks consumers outside this plan's write set?** Raised by the 2026-10-05 refresh. **Status: RULED 2026-10-05, `OR-92`: (b): `port` stays optional on an `http_json` source; a metric without one is admitted at load, never collected, and shown `unsupported` (reason: no port declared). No frontend change.** | `source.port` is optional today (`{"type": "integer"}`, `solution-package.schema.json:607-609`). The Cache fixture and both overlap fixtures carry `port` (six each: five sources and the health probe), and the 19 negatives are generated from the Cache fixture (`negative/generate.py`), so they carry it too. Three `http_json` sources do **not**: root `engine-edge.package.json:44` (in this plan's write set), and, **outside it**, `frontend/src/app/dev/testPackage.ts:62` (used by five frontend test files) and `frontend/contracts/fixtures/view-model/version-scoped-component-excluded.json` (`/package/components[1]/metrics[0]`). The frontend compiles the **root** schema directly (`frontend/contracts/solution-package/validate.ts:12`, Ajv `strict: true`) and validates packages with it. This plan carries the owner's standing rule **no frontend change**. Whether those two frontend files go red is **unmeasured** (P11-1.1 Step 0b runs the frontend's contract tests read-only to find out) | **(a)** `port` required on `http_json` (the draft). A package without one is refused at load. The two frontend files need an edit, which the standing rule forbids without the owner's exception. **(b)** `port` stays optional in the schema. A metric whose `http_json` source has no `port` is admitted at load, never collected, and shown in the catalog as `unsupported` (reason: no port declared). No frontend or fixture change beyond the plan's own `engine-edge`. **(c)** Required, and the owner authorizes the frontend fixture edits as a separate task outside plan 011 | **(b)**: it breaks nothing outside the write set and degrades visibly (a catalog state, not a silent gap). (a) is the stricter contract and is the owner's call if they will accept (c). P11-1.1 is written for (b) | P11-1.1, P11-1.2 (`state`), P11-4.3 |
| **OQ-25** | **Does `OR-70`'s review cadence apply to plan 011?** Raised by the 2026-10-05 refresh. **Status: RULED 2026-10-05, `OR-92`: (b): `OR-70`'s cadence applies to plan 011 (one Opus review per wave, one fix round, the full build at each wave's end, one combined Fable 5.1 review at the end in place of P11-10.1 Step 4). Process only.** | `OR-70` is titled "Execution model **for plan 010**": one Opus review per slice, the full build at the end of each slice, one combined Fable 5.1 review after the last slice, independent tasks in parallel per the dispatch map. This plan's Global Constraints and dispatch map still say an Opus 5.5 high reviewer checks **each task** (the 2026-09-30 line). Nothing in `OR-70` names plan 011 | **(a)** Keep per-task Opus review (as written). **(b)** Adopt `OR-70`'s cadence: one review per wave (MW1…MW10), one fix round, the full build at each wave's end (already this plan's rule), one combined Fable 5.1 review at the end in place of P11-10.1 Step 4. | **(b)**: it is the owner's evident preference for plan 010, and this plan's waves are the same size as its slices. Process only: no task content changes. The owner decides | the first code wave (MW1) |
| **OQ-26** | **Does LOCKED `B/D5`'s walking-skeleton gate hold plan 011's path-2 (broker-push) wave?** (Drafted by P11-0.2, DMR-25; [`dm-design-review-2026-10.md`](../../tasks/dm-design-review-2026-10.md) §4.) **Status: RULED 2026-10-05, `OR-94`: (a): plan 011's path 2 is generic, package-driven ingest with no File-Mesh-specific code path, so LOCKED `B/D5`'s walking-skeleton gate does not hold it; MW5 keeps only its own gates. A **reading** of `B/D5` by its owner, not an amendment; its text is unedited.** | `B/D5` 🔒 (`DECISIONS.md`): *"a thin walking skeleton — one generated screen, one graph, one role, one Docker `service` deploy with health + telemetry, accepted **end-to-end** — must pass **before any** systemd/K8s/scheduled/File-Mesh work begins"*. Plan 010's first E2E slice says it must not claim that skeleton (`010-…md:3327-3329`). MW5 builds **path 2**, which the owner described as *"what we have in file mesh"* (`OR-73`), and P11-5.0 read File Mesh's code (docs only, unaffected). This plan did not cite `B/D5` until this review, and `OR-73` does not name it (`OR-1`: a LOCKED change names the decision and its scope). The skeleton's graph needs plan 011's query routes (MW7) **and** a frontend screen this plan does not build (owner standing rule). **What does not change under any option:** path 2 is ruled (`OR-73`), P11-5.0 is done, `B/D5` is not edited. | **(a)** Read path 2 as **generic** ingest (no File-Mesh-specific code path; behaviour comes from the package), so it is not 'File-Mesh work'; MW5 keeps its gates. Delta: none to the plan; the reading is recorded as a ruling. **(b)** MW5 waits until the `B/D5` skeleton is accepted end to end. Delta: path 2 lands later, on a dependency outside this plan (a frontend screen); nothing else moves (MW5 is off the critical path). **(c)** Amend `B/D5`'s sequencing for path 2 by an `OR-1` ruling that names it. Delta: a LOCKED-amendment row. Scenario: a File Mesh deployment publishing heartbeats every 5 s (S-E): under (a) its metrics can be ingested as soon as MW5 lands; under (b) not until a Cache graph is accepted end to end; under (c) as (a), with the gate formally narrowed. | **(a)**, because plan 011's path 2 is package-driven and carries no File-Mesh-specific code, which is what `B/D5`'s ordering protects against. It is a reading of a LOCKED decision, so **only the owner can make it**; until then the plan flags `B/D5` as *LOCKED; path-2 reading pending OQ-26* | P11-5.1 (MW5), not P11-5.0 |
| **OQ-27** | **Is the monthly archive per tenant (architecture §8.5) or per partition (plan 011 as written)?** (Drafted by P11-0.2, DMR-34.) **Status: RULED 2026-10-05, `OR-92`: (b): monthly archives are **per tenant**, by a bound cursor `SELECT … WHERE tenant_id = ?` over the detached partition, CSV encoded in the application (no `COPY`, no interpolated tenant id); one manifest per tenant. Keeps `architecture.md` §8.5 and `I5`.** | P11-2.1's `MetricsPartitionMain archive` writes one identifier-only `COPY (SELECT … FROM <detached>) TO STDOUT` per detached partition, with one manifest. `architecture.md` §8.5 (`SM-D26`, PROPOSED) says archives are produced and verified **per tenant** ('tenant-partitioned so a restore cannot cross the plane boundary'; step 2 selects `WHERE tenant_id = $1`). `COPY` is not a preparable statement (WebFetch 2026-10-05, PostgreSQL 14 `PREPARE` and `COPY` pages, DMR-34), so a per-tenant `COPY` would interpolate the tenant id into SQL (`I5`); whether the extended protocol can bind a parameter inside `COPY`'s query is not stated on either page (unverified, not assumed). **What does not change under any option:** archive → verify → drop, `DETACH … CONCURRENTLY`, nothing dropped without a verified manifest, no tenant id interpolated into SQL, the operator command. | **(a)** Per partition, as written. Delta: one file per month holding every tenant; a per-tenant restore or erasure must filter the archive later; departs from §8.5's non-negotiable (PROPOSED, so a flag, not a conflict with a LOCKED row). **(b)** Per tenant, by a **bound cursor `SELECT … WHERE tenant_id = ?`** over the detached partition, CSV encoded in the application (M-Q18's export mechanism reused; no `COPY`). Delta: N scans of a detached (no longer written) table and N manifests; keeps §8.5 and `I5`. The tenant list comes from the detached partition itself (`SELECT DISTINCT tenant_id`, run under the owner credential), a cross-tenant read, so whether it needs an `OR-49`-style ruling is part of this question. **(c)** Per tenant with the id interpolated into `COPY`: rejected on `I5`, listed so it is not reinvented. Scenario: one month at S-A (about 11–12 GB, `OR-76`) for two organizations; organization B offboards. Under (a) its rows sit in a shared archive until a filtering tool exists; under (b) its archive files are separable and deletable. | **(b)**: it keeps §8.5 and `I5`, and makes the tenant-erasure open item (`OR-76`) tractable for archived months. The owner decides; `OR-77` and `OR-79` are not edited by any option. P11-2.1 is written for (a) until ruled | P11-2.1 (the `archive` subcommand), with OQ-11 |
| **OQ-28** | **May the plan 011 tasks that touch no unpushed plan 010 file start after the sitting, with plan 010 S2 and S3 pushed, instead of after plan 010 S6/S7 (W10) and W11?** Raised by the pre-sitting review (2026-10-05). **Status: RULED 2026-10-05, `OR-93`: (b): six tasks start early, each on its own named gate (see the [per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)). Everything stays LOCAL until its wave review.** | `OR-91` relaxes the rulings' closing line (*"execute after plan 010 S6/S7"*, in the verbatim record) **for MW0 only**, and says every later wave keeps its own gates. The plan's gates were uneven: the Depends-on row and Prerequisite 1 said every later wave waits for W10 and the declare, stage and `console.yaml` tasks for W11, while the dispatch map put MW1 and MW2 behind W11 whole. **Measured at `bd802fc`:** S2 (inventory, `V007`–`V010`, the secret store, `SmDataBootstrapIT`'s Phase 0) and S3 (catalog) are pushed (`4267c1e` closed their review). P11-2.1's only plan 010 inputs are T2.1 and T2.4, both landed, and no plan 010 task after T2.4 owns `sm_data_phase0.sql` or `SmDataMigrationMain.java` (plan 010 single-owner table). Plan 010 allocates no `sm_control` number after `V013` (`010-…md:388-389`), so P11-2.3 (`V014`) has no migration contention. P11-1.1's files have no plan 010 owner left (T0.1a landed). P11-2.2's `V015` does: plan 010's `V011`–`V014` (T6.8, T7.2, T9.3, T11.2) must merge first by the version-order rule (`010-…md:391-395`). Moving any task earlier without a ruling would be a silent decision. | **(a)** Keep the gates as written: nothing in plan 011 code starts before plan 010 S6/S7 merge, and the declare, stage and `console.yaml` tasks wait for W11, so MW1 and MW2 start after W11. Delta: none; after W11 the serial chain is P11-1.1, P11-1.2, P11-2.1, P11-2.2, P11-3.1, P11-3.2, P11-4.3, P11-6.x, P11-7.2, P11-8.1, P11-9.1, P11-10.1 (12 tasks). **(b)** Release **six** tasks, each with its own named plan 010 merge ([per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)): P11-2.1 and P11-2.3 right after the sitting (S2 is pushed; they wait for nothing more); P11-7.1 after P11-2.3; P11-4.2a after P11-1.1 and plan 010 T6.1; P11-1.1 after the sitting but merged only after plan 010's in-flight S5/S6 branches are pushed; P11-1.2 after P11-1.1 and plan 010 T4.4, T5.3 and T10.3 (not W11). P11-2.2 may be **developed** early but still **merges** after T11.2's `V014`; MW3, P11-4.1 and P11-4.3 are unchanged. Delta: the post-W11 chain becomes P11-2.2, P11-3.1, P11-3.2, P11-4.3, P11-6.1, P11-7.2, P11-8.1, P11-9.1, P11-10.1 (9 tasks). Extra rebase work: from P11-2.1's merge every plan 010 launcher IT also migrates `sm_metrics` (a red `sm_metrics` migration is a red plan 010 build) and plan 010's T6.8, T7.2, T9.3 and T11.2 rebase their `SmDataBootstrapIT` hunks onto it; plan 010's ITs from T6.3 on load the Cache fixture P11-1.1 changes (additively); P11-1.2 rebases onto any later plan 010 edit of `console.yaml`, `ErrorCode.java` or `SchemaMirrorLockTest`. **(c)** (b), and P11-2.2 also **merges before** plan 010's `V011`–`V014` (`V015` out of version order) under plan 010's own recreate-the-dev-database fallback (`010-…md:391-395`), which opens MW3 early. Delta: the integrator recreates every persistent dev `sm_data` that has applied a higher version and records it (pre-production, no customer database); plan 010's 'version order stays merge order' no longer holds, so the authors of T6.8, T7.2, T9.3 and T11.2 must be told; P11-4.1 still waits for W11, so the post-W11 chain is P11-4.1, P11-4.3, P11-6.1, P11-7.2, P11-8.1, P11-9.1, P11-10.1 (7 tasks). | **(b)**: it moves exactly the tasks whose files no unpushed plan 010 task owns, shortens the post-W11 chain from 12 to 9 tasks, and keeps `V015` in version order. (c) buys two more tasks at the price of breaking plan 010's version-order rule, so it is the owner's call. ⚠️ Whatever is ruled, `OR-91` does not cover it: it relaxed the closing line for MW0 only | Every code wave (first: P11-1.1, P11-2.1) |
| **OQ-29** | **Which metric must v1 path 2 actually serve?** File Mesh's events offer no event-count extraction. Raised by the pre-sitting review from P11-5.0's facts. **Status: OPEN, deferred until MF26 (`OR-95`).** The owner deferred this question until MF26 (Solace topic case-sensitivity) is measured; it gates **MW5 only**. Rule OQ-3, OQ-5, OQ-20 and OQ-29 together. | **Measured** ([`011-filemesh-publisher-facts.md`](../../design/backend/evidence/011-filemesh-publisher-facts.md) F11–F13, F17): the File Mesh **heartbeat body carries no number** (`{"adapterState": {"heartBeatTimeStamp": "<string>"}}`; 34,509 captured, F11). The only numeric fields captured on the wire are per-file values on `fcomplete`: `size`, `runTimeMs` and the part counts (18 captured, F12). The `complete` and `error` totals (`totalFiles`, `totalDataProcessed`, `adapterRuntimeMs`) exist only in the model classes and are unmeasured on the wire (F13). `contracts/validation/fixtures/filemesh-ftp-solace.package.json` declares **no metrics** (all four components, measured 2026-10-05). The schema's `valuePointer` must point at a finite number (P11-1.1's `sourceSolaceEvent`, P11-5.1's decoder) and there is no event-count extraction, so 'heartbeat rate' or 'files completed per hour' **cannot be declared**. Unanswered, MW5 builds and tests path 2 only on synthetic packages, Acceptance #4 passes with no MI behind it, and the shape P11-1.1 lands 'once, in its ruled shape' may be the wrong shape. | **(a)** Numeric fields only: v1 declares an `fcomplete` `size` or `runTimeMs` gauge (measured, F12) and, once captured, `complete` totals as counters with **inferred** restart detection (they reset on every adapter run). Delta: no schema or ingest change; Acceptance #4 and a reviewed fixture carry one real metric; rates and event counts are not available. **(b)** Add an extraction kind **count of matching events** to `sourceSolaceEvent`: a counter with increment 1 per event that passes `eventFilter`, `valuePointer` then optional. Delta: the schema branch changes (P11-1.1, both validators, negatives); the ingest rules gain a DP-synthesised cumulative value that resets when the DP restarts (an inferred reset, M-Q9), so P11-3.1's and P11-5.1's tests change; heartbeats per interval and files completed become declarable; `I1` holds (it reads events the MI already emits). **(c)** Defer the `solace_event` branch: P11-1.1 leaves it out (the discriminator keeps it additive) until a package declares a real metric. Delta: path 2 (ruled, M-Q12) is not built in MW5, OQ-3, OQ-4, OQ-5, OQ-6, OQ-15, OQ-20 and OQ-26 wait, and P11-5.1 and Acceptance #4's push half move out. | **(a)** as the floor, with (b) the follow-on if the owner needs rates: it is the smallest change, keeps the shape OQ-5 rules and needs no invented counter. Under every option **MW5 may not claim path 2 without one real, declared `solace_event` metric in a reviewed fixture** (under (a): an `fcomplete` `runTimeMs` gauge). Rule it with OQ-5 and OQ-20 | P11-1.1 (the `solace_event` branch), P11-5.1, Acceptance #4 |

---

## Relationship to plan 009 — exactly what is replaced

Plan 009 gets a dated **SUPERSEDED in part by plan 011** header (P11-0.1). Step by step:

| Plan 009 | Disposition | Replaced by |
|---|---|---|
| Global Constraints: `console-api` owns no database; depends on the `-api`s only | **Kept in substance, rehomed.** The module is `console-gateway` (M-Q11), which already has `gatewayUsesApisOnly` (`PlaneBoundaryTest.java:63-70`) and `jdbcIsConfinedToPersistencePackages` (`:128-134`) | P11-7.2's gateway-planted JDBC fixture |
| Global Constraints: *"`metrics.export` and `metrics.forceRaw` are … Administrator-only"* | **Superseded** by M-Q16 `OR-80` | P11-2.3 |
| Task 1 (the `console-api` module + `ConsoleApiBoundaryTest`) | **Dropped** (M-Q11) | P11-7.2 Step 1 (fixture) |
| Task 2 (`ConcurrentComposer`, per-surface availability) | **Dropped as a task** (M-Q11). OR-10's properties still bind: the one composed read (catalog: CP package ∥ DP observed) runs concurrently and reports per-surface `Availability` | P11-7.2 (`theCatalogComposesBothPlanesConcurrently`) |
| Task 3 (the fourteen operations, `console.yaml`) | **Replaced.** Its `metricsExportAndForceRawAreAdministratorOnly` test is **inverted** (M-Q16) | P11-1.2 (contract), P11-7.2 (routes) |
| Task 4 (durable metrics, dashboards) | **Replaced.** Its `anUndeclaredMetricIsQuarantined…` test is **dropped** (M-Q6: dropped and counted instead) | P11-2.1/2.2/2.3, P11-3.1, P11-7.1 |
| Task 5 (handoff and demo retirement) | **Replaced** | P11-9.1 |
| Task 6 (close) | **Replaced** | P11-10.1 |
| File Structure row `frontend/src/features/monitoring/**` | **Not adopted.** No frontend change | — |

Also re-pointed by P11-0.1, **as dated pointers beside the text, which stays** (measured 2026-10-05): plan 007's five
mentions of plan 009 as the owner of the replacement (`007-…md:1424`, `:1437` (Task 13b's row), `:1468`, `:1480-1481`,
`:1619`), `docs/tasks/demo-delivery-sequence.md:31-32`, `docs/requirements/traceability.md:17`, and
console-integration §5.4's opening paragraph (`console-integration.md:388-391`, which names `console-api` and plan 009).
**Handed to the integrator, not edited here (plan 010 is read-only for this plan):** plan 010 still names plan 009 at
`010-…md:42` (its Spec line), `:164` (*"`demo-launcher` is deleted by plan 009"*), `:4004` (*"plan 009's `console-api` may
later absorb it"*), `:4122` (T12.1 Step 2: *"⛔ Metrics/dashboards/Explore are excluded (plan 009's)"*) and `:4193` (the
out-of-scope row *"Metrics, dashboards, Explore, `console-api`, deleting `demo-launcher`/`demo_control` | plan 009"*).
Each should read plan 011 (P11-9.1 deletes `demo-launcher`; the routes live in `console-gateway`, `OR-78`).

---

## Plan 010 dependencies — exact

Re-measured 2026-10-05 against `bd802fc`. **"Landed"** means the code is in the tree and the task has a Results entry in
plan 010 (`010-…md`); line cites into plan 010 are as of `bd802fc` and drift as plan 010 is edited, so the **symbol is the
reference** and the line is a convenience. The first draft's cites (`0cbaa28`) are all re-resolved here. The new column **"In flight"** (added by the pre-sitting review, read-only `git show` on the local branches `p010/merge3`, `p010/t53`, `p010/s6`, `p010/t61`, 2026-10-05) says what plan 010 has already **built but not pushed**: the code exists, its Results are not yet on the integration head, so a plan 011 task may rebind to it but may not rely on it merging unchanged.

| Plan 010 task | At `bd802fc` | In flight (unpushed branch) | What plan 011 uses (measured) | Needed by |
|---|---|---|---|---|
| **T0.3** (records) | ✅ landed | — | `OR-69`, `OR-71`…`OR-91` registered in `DECISIONS.md`; `BE-D27`…`BE-D33` allocated (`BE-D34` next free). Plan 010 already carries the `OR-72` wording: Global Constraints `I5` (`010-…md:59-68`) and T7.1 (`:3719`) | P11-0.1 (cites them; registers nothing) |
| **T0.4b** | ✅ landed | — | The `I8` licence gate in `verify` (`backend/licence-allowlist.txt`, `licence-exceptions.txt`). It must stay green with no new artifact | every code task |
| **T1.3** | ✅ landed | — | `ReachAuthorization.authorizedSubset(PrincipalRef, Permission, ResourceKind, Collection<UUID>)` returning `Set<UUID>` (`cp-api/.../authz/ReachAuthorization.java:48`); `OperationClassification.Reach(Permission)` (`OperationClassification.java:48`); `OperationPolicy.REACH_OPERATIONS` (`OperationPolicy.java:43`, today `listSolutions`, `listInstances`, `listTargets`, `getHomeSummary`, `getHomeAttention`, `submitFleetApply`, `observeFleetApply`) and `AUTHENTICATION_ONLY_OPERATIONS` (`:36`); `ReachOperationsMirrorTest`. T1.3 is the **only** plan 010 owner of `OperationPolicy`, `OperationClassification` and `AuthenticationInterceptor` (`010-…md:409`) | P11-1.2 (the reach and authentication-only sets), P11-7.2 |
| **T2.0** | ✅ landed | — | `sm_control` V013: `instance_id_unique` (`V013__dp_remote_governance.sql:15`), `solution_package.digest` with its `sha256:` CHECK (`:34`), the V013 body of `record_change_audit()` | P11-2.3 |
| **T2.1** | ✅ landed (V007) | `p010/merge3`, `p010/s6`, `p010/t53` (`26919bb`): `V007` edited in place, host-key index `(tenant_id, host_key_fingerprint)` (`OR-89`) | `sm_data.placement` (`health_port`, `placement_health_port_once (tenant_id, target_id, health_port) WHERE retired_at IS NULL`, `V007…sql:178`), `instance_state.acquisition_mode` (`:39-40`, **no address column**, `OR-82`), `target_connection` (`host :94-95`, mutable, read fresh). `OR-89`'s per-organization host-key index is **recorded but not yet in V007** (`:118` still reads install-wide) | P11-2.1 (FK), P11-2.2, P11-4.2, P11-4.3 |
| **T2.4** | ✅ landed (V009, Phase 0) | — | `sm_secret`, `SecretStore` / `EnvelopeSecretStore.put(tenantId, SecretPurpose, char[], actor)`, the **closed** `SecretPurpose` (`SSH_PRIVATE_KEY`, `CONFIG_FIELD`, `CONFIG_HASH_KEY`, `CANARY`); `sm_data_phase0.sql` now has the two `sm_secret` statements (T2.4 is its last plan 010 editor); **Step 3b** `TenantEnumerationRepository.tenantsWithActiveInstances()` (`:84`) whose javadoc (`:69-83`) **already names plan 011's `MetricsCollector` as the fourth caller** | P11-2.1, P11-4.3 (javadoc only, and only if OQ-6 widens the name), P11-5.1 |
| **T2.5 / T2.6** | ✅ landed | — | `InstanceInventoryService.reserve(UUID organizationId, DeclareInstanceCommand)` (`dp-api/.../inventory/InstanceInventoryService.java:19`, implemented by `dp-core/.../inventory/InventoryService.java:88`); `DeclareInstanceCommand(environmentId, catalogSolutionId, solution, declaredPackageVersion, name, targetId, AcquisitionMode acquisition, PlacementSpec placement, requestedBy)`; `InstanceView`; the `DataPlaneConfiguration` aggregator | P11-4.1; every DP configuration |
| **T2.7** | ✅ landed | — | `TargetConnectionMain` (`dp-core/.../apply/persistence/TargetConnectionMain.java`, the shape `BrokerConnectionMain` copies), `TargetConnectionService` | P11-5.1, P11-4.3 (reads `target_connection.host`) |
| **T3.1** | ✅ landed | — | The Java validator `cp-core/.../catalog/validation/{PackageValidator,SemanticGates,SchemaGate,OverlapGate,…}`, held at parity with the Python; `NegativeFixtureParityTest` with `thereAreNineteenNegatives…` pinning `hasSize(19)` (`:28-31`) and the parameterized `theValidatorAndThePythonGateAgreeOnEveryNegative` / `…EveryPositive` (`:45`, `:55`); `OverlapParityTest` (`:51`) | P11-1.1 (the new gates 15 and 16 in `SemanticGates`, the negative-count pin) |
| **T3.2** | ✅ landed | — | `SolutionCatalog.find(UUID organizationId, UUID catalogSolutionId, String packageVersion) → Optional<LoadedPackage>`, whose `digest()` is *"`sha256:` + the hex digest of the canonical document text"* (`cp-api/.../catalog/{SolutionCatalog,LoadedPackage}.java`), and `documentOf(...)`, whose javadoc says **never hash that text** (the `jsonb` store re-spells it). A re-load of a version with different content is refused (`PackageRejectedException`). The byte-locked classpath copy of the schema, `backend/cp-core/src/main/resources/contracts/solution-package.schema.json`, pinned by `PackageSchemaMirrorLockTest` | P11-4.1 (carries `find(…).digest()`, never recomputes it), P11-1.1 (the copy), P11-7.1, P11-7.2 |
| **T4.1 – T4.3** | ✅ landed | — | The two-phase declare (`BE-D32`): `reserve` (DP, `pending`), CP `register` (quota under the entitlement lock), `settlePending`; an over-quota or unregistered declare is abandoned by the sweep, its placements retired, its name freed (`OR-88`) | P11-4.1 (S-K, the over-quota test) |
| **T4.4** | ❌ **not landed** | **built**: `p010/merge3` (`a0bfde4` feature, `10af3cf` Results, `5861e7d` S4-review fixes), also on `p010/s6` and `p010/t53`; orphan-cleanup runbook `28c035f` (`OR-90`) | `DeclareOrchestrator`; `DeclareInstanceRequest` **as built** (read on `p010/merge3`): `declaredPackageVersion` and `acquisition` are `JsonNode`s (the contract makes an `acquisition` carrying any key but `mode` a `422`, not a `400`); the host-side ports nest in `Placement(containerName, configDirectory, containerConfigPath, fileName, healthPort)`; every record level has a `@JsonAnySetter` that refuses an unknown key (`400`). So `metricsPorts` is added to **`Placement`** and to `console.yaml`'s `placement` schema (`additionalProperties: false`), beside `healthPort`; `SchemaMirrorLockTest`'s declare-body extension (`010-…md:2702-2765`) | P11-1.2 (`metricsPorts` on `Placement`), P11-4.1 |
| **T5.2** | ✅ landed (V010) | — | `config_generation`; `instance_state.last_generation` / `reached_rung` (**one** instance-level rung, which describes only `last_generation`; `V010__config_generation.sql`, `010-…md:2881-2884`); `ConfigRecordService.stage(UUID organizationId, UUID instanceId, StageCommand)` (`dp-api/.../config/ConfigRecordService.java:21`), `StageCommand(baseStateVersion, packageSolution, packageVersion, componentSelectionJson, draftValuesJson, rendered, origin, derivedFromGeneration, actor)` | P11-4.1, P11-4.3 (why a per-generation query is needed) |
| **T5.3** | 🔶 in progress | **built**: `p010/t53` (`0a3a4b7`), `p010/merge3` (`2cbfa69`) | The gateway stage flow and its re-projection: `DesignerOrchestrator` (the flow; `DesignerController` is its thin HTTP face), `DesignerCodec`, `ConfigLadder`, and **`dp.config.ConfigLadderReader`, which T5.3 creates** (T6.5 only extends it); +3 lines in `console.yaml` (`404` responses) | P11-4.1; P11-1.2 rebases onto its `console.yaml` hunk |
| **T6.1** | 🔶 in progress | **built**: `p010/t61` (`025a7c3`), `p010/s6` (`74207fb`) | `HostProbe` (pattern), `SshCredentialSource`; **M8** and **M19** to be filled | P11-4.2a (the `HostProbe` pattern and **M19**), P11-4.2b (**M8**) |
| **T6.2** | ❌ not landed | **built**: `p010/s6` (`3f9f107`, production apply wiring) | Nothing directly: the durable worker runs against SSH-Docker in production, and `TwoSchemaTestDatabase` switches to the production executor. It is part of S6 | S6 as a whole gates **P11-1.1's merge** (its ITs load the Cache fixture), see the [per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why) |
| **T6.5** | ❌ not landed | — | The `restart-observed` rung derived from the journal (`dp.config.ConfigLadderReader`, created by T5.3 and extended by T6.5, `010-…md:3247-3250`) **for the latest request**; it provides no per-generation query | P11-4.3 (adds one, Step 0) |
| **T6.7** | ❌ not landed | — | `ApplyPathBoundaryTest` (`010-…md:3331-3356`): `theGatewayHoldsNoDatasource` (plan 007 Task 12), `onlyTheMetricsFacadeCallsTheDemoCacheProbes`, `nothingProductionUsesTheMetricsFacade`. T6.7 is its only plan 010 owner. **No `ApplyPathBoundaryTest` exists in the tree at `bd802fc`** | P11-4.2 (stay green), P11-7.2 (extends `theGatewayHoldsNoDatasource`), P11-9.1 (retires the two facade rules) |
| **T6.8 Step 0** | ❌ not landed | — | **M18** filled (docker `StartedAt` semantics) | P11-4.2b |
| **T7.1** | ❌ not landed | — | `SshSessionPool`; **M12** filled. Its text already reads that the pool serves the poller and *"plan 011's `MetricsCollector` shares it under M-Q3's per-target reserve"* (`010-…md:3719`) | P11-4.2b (`SshMetricFetcher`), P11-4.3 |
| **T7.2** | ❌ not landed | — | `HealthPoller` (`dp.health`), `HealthConfiguration` (`010-…md:3742`: one `@Configuration` per area, one `@Import` in `DataPlaneConfiguration`), `thePollerReadsNoTenantOutsideTheTwoRuledLists`, and the direct-mode read of `target_connection.host` fresh per round with `health_port`/`health_path` (`:3761`) | P11-4.3 (the poller pattern and the direct-mode read of `target_connection.host`) and P11-4.2b. For P11-3.1…P11-6.2 it is **pattern only**: `HealthConfiguration`'s one-`@Configuration`-per-area rule is a naming convention to copy, not code to wait for |
| **T8.1, T10.2, T10.4, T9.4** (W10–W11) | ❌ not landed | — | `copyForward` and the restore/accept generation mints; `ConfigRecordServiceImpl`'s last plan 010 owner (T9.4) | P11-4.1 (**waits for plan 010 W11**) |
| **T10.3** (W10) | ❌ not landed | — | The last plan 010 `console.yaml` editor | P11-1.2 (**waits for T10.3 itself, W10, docs only** — not for W11: plan 010's single-owner table names no other `console.yaml` editor after it; if the integrator dispatches T10.3 early, P11-1.2 is not held behind S7) |
| **T11.2** (W11) | ❌ not landed | — | `V014` (`fleet_action`), the last plan 010 `sm_data` number (`010-…md:376`) | P11-2.2 (**merge**; takes `V015`; the task may be developed earlier) |
| **T12.1** (W12) | ❌ not landed | — | The parity checklist; the `ENDPOINT-INVENTORY.md` "served" rows; plan 010 closed | P11-1.2 (its inventory hunk rebases onto T12.1's if both are open), P11-9.1 |
| **Plan 008** (unscheduled; not a plan 010 task) | header note landed by T0.3 Step 2 | — | *"its migrations renumber to the next free `sm_data` numbers after plan 010's allocation (**V015** onward — V011 is now T6.8's)"* (`010-…md:1350-1351`, and plan 008's own header, point 4) | P11-0.1 (adds a dated line: **V017** onward; see [Migration allocation](#migration-and-id-allocation)) |

---

## What each wave may and may not claim

| Wave | May claim when merged | Must not claim |
|---|---|---|
| **MW0** records | The rulings' plan 011 side recorded (pointers and the integrator hand-off; the rulings themselves are registered by plan 010's records); REQ-010 cited; the DM design adversarially reviewed and its findings triaged; File Mesh's publisher facts measured (P11-5.0); the owner sitting held | That any DM-D* is ratified; that a discovery default is a ruling before OQ-22 is answered; that the LOCKED `B/D5` gate has been read for path 2 (OQ-26). **Executed 2026-10-05:** P11-0.1 (`a521378`), P11-0.2 (`7f8a6dc`) and P11-5.0 (`0127b50`, merged at `c9be575`); the owner sitting was held 2026-10-05 (`OR-92`…`OR-95`: 25 of 29 questions ruled, OQ-3/5/20/29 deferred); P11-0.1 Step 4b (the OQ-21/OQ-22 record drafts) is not |
| **MW1** contracts | The reviewed package-schema change and the reviewed `/api/console/v1` contract, **specified, not served** | That any route is served |
| **MW2** storage | `sm_metrics` (own history), the partition runway function, and the definition, endpoint and active-list tables exist and are tested as `sm_dp_runtime`; dashboards exist in `sm_control` | That anything is collected; that six months fits (MW8) |
| **MW3** ingest | One ingest port with the ruled identity, counter semantics, caps, buffer and counters; rollups type-aware with a late window | That path 3 exists (port and contract kit only) |
| **MW4** collection | Background collection of every active instance's declared metrics, in both acquisition modes, with no logged-in session; S-G attributed correctly, live on EC2 | Collection by `host-local` agents; push health (`I1`) |
| **MW5** push | Broker-pushed business metrics ingested and queryable over REST | **Live browser delivery.** This is M-Q12's live leg, **scheduled as the conditional P11-5.2 (OQ-14, `OR-95`)**. It is not claimed until P11-5.2 lands, and P11-5.2 opens only once open items 32/33/35 are closed. **Also not claimed (OQ-29, `L19`):** path 2 as working with no real, declared `solace_event` metric in a reviewed fixture behind it, and path 2's broker tests (gated by `broker.host`) when they did not run |
| **MW6** query/export | Tier routing, raw-tail stitching, type-aware aggregation, gaps, caps and limits; streaming export with a bounded heap **measured at 5 M and 20 M rows locally** | That export is shipped, or proven at 105 M rows (MW8, M-Q18) |
| **MW7** routes | 13 of the 14 `/api/console/v1` operations **served** (12, plus `metric.declare` served as its contracted `501`). Row 60 (`GET /export/range`) is **wired and tested but not counted served** | **That export is served or shipped.** Row 60 counts as served only after P11-8.1 Step 6 records the 105 M-row streaming measurement (M-Q18: *"MEASURE streaming … with a 105 M-row test before export ships"*). Also not claimed: Explore server drafts (out, M-Q23), live chart tails |
| **MW8** gate | REQ-007 marked satisfied or not on the EC2 host, with the six-month figures **labelled extrapolated**. The 105 M-row export stream measured, so row 60 may count as served | That the extrapolation is a measurement |
| **MW9** retirement | `demo-launcher` and `demo_control` deleted after full parity | Frontend cutover or deployability (plan 007's release gate) |

---

## Measured facts — fill each before the task that needs it

`I2`: WebFetch a specific URL, or measure locally or on EC2. Write the result (URL or command, plus the verbatim
line that settles it, plus the date) into this table **in the task's own commit**. **A task whose row is empty
stops at that row.**

| # | Fact | How to settle it | Needed by | Status |
|---|---|---|---|---|
| **MF1** | Flyway **at the reactor's managed version** (**measured 11.7.2**, 2026-10-05: `mvn -o -f backend/pom.xml help:evaluate -Dexpression=flyway.version -q -DforceStdout`): (i) two `Flyway` instances in one process, each with its own `schemas(...)`, the default history table name, and `createSchemas(false)`, each record their history in **their own** first schema; (ii) the second run sees only its own history; (iii) a failure in the second run leaves the first run's work committed. **UNVERIFIED** | `mvn -o -f backend/pom.xml help:evaluate -Dexpression=flyway.version -q -DforceStdout`, then **measure** in `SmMetricsMigratorIT` (P11-2.1 Step 0). WebFetch the Flyway `schemas` and `table` settings pages at that version if a pinned page exists; otherwise the measurement is the evidence, labelled as such | P11-2.1 | ✅ **Filled by P11-2.1 (2026-10-05, Flyway 11.7.2):** two separate histories in one run, idempotent, a failing second migration leaves the first committed (Results of P11-2.1) |
| **MF2** | PostgreSQL 14 `SECURITY DEFINER` safety (the relation-shadowing rule is **MF21**) | ✅ **Verified 2026-09-30** — [postgresql.org/docs/14/sql-createfunction.html](https://www.postgresql.org/docs/14/sql-createfunction.html), "Writing SECURITY DEFINER Functions Safely": *"search_path should be set to exclude any schemas writable by untrusted users … write `pg_temp` as the last entry in `search_path`"*; *"by default, execute privilege is granted to PUBLIC for newly created functions … revoke the default PUBLIC privileges and then grant execute privilege selectively … create it and set the privileges within a single transaction"*; *"The SET clause causes the specified configuration parameter to be set to the specified value when the function is entered, and then restored to its prior value when the function exits."* P11-2.1 measures the grants as `sm_dp_runtime` | P11-2.1 | ✅ |
| **MF3** | PostgreSQL 14 `VACUUM` privilege and context | ✅ **Verified 2026-09-30** — [postgresql.org/docs/14/sql-vacuum.html](https://www.postgresql.org/docs/14/sql-vacuum.html): *"To vacuum a table, one must ordinarily be the table's owner or a superuser … VACUUM will skip over any tables that the calling user does not have permission to vacuum"*; *"VACUUM cannot be executed inside a transaction block."* So freeze is OQ-10 | P11-2.1 | ✅ |
| **MF4** | PostgreSQL 14 `ATTACH`/`DETACH PARTITION` locks and ownership | ✅ **Verified 2026-09-30** — [postgresql.org/docs/14/sql-altertable.html](https://www.postgresql.org/docs/14/sql-altertable.html): *"Attaching a partition acquires a SHARE UPDATE EXCLUSIVE lock on the parent table"*; *"It is possible to avoid this scan by adding a valid CHECK constraint … before running this command"*; *"to attach a table as a new partition of the table, you must own the table being attached"*; *"CONCURRENTLY cannot be run in a transaction block and is not allowed if the partitioned table contains a default partition"*, and it waits for *"all other transactions using the partitioned table"* | P11-2.1 | ✅ (ATTACH's lock on the **referenced** table is MF20) |
| **MF5** | PgJDBC **42.7.11** cursor streaming through the app's Hikari pool and `JdbcTemplate`: with autocommit off (inside a transaction), `TYPE_FORWARD_ONLY`, one statement and a fetch size, rows are fetched in batches, and heap stays flat as rows grow. Documented on current docs only [discovery, V: jdbc.postgresql.org/documentation/query/], **not pinned, UNVERIFIED in this stack** | **Measure in two halves.** (i) **Local** (P11-6.2 Step 0): 5 M and 20 M generated rows in Testcontainers, streamed through the real repository into a counting `OutputStream`, sampling `MemoryMXBean` heap-used. (ii) **At scale** (P11-8.1 Step 6, M-Q18): **all 105 M rows streamed** through the real route, with the row limit raised **for that labelled run only**, recording the heap high-water mark. The run is on EC2 if P11-8.1 Step 6a's disk arithmetic shows it fits, otherwise locally in Testcontainers (M-Q18 does not require EC2). Export is **not claimed shipped**, and row 60 is not counted served, before (ii) is recorded | P11-6.2, P11-8.1 | ⚠️ **half: (i) ✅ MEASURED (P11-6.2: 26 MB live heap at both 5 M and 20 M rows); (ii) NOT MEASURED (2026-10-07, P11-8.1 Step 6b: no tooling written, and 105 M rows do not fit the host or safely the Mac; see the evidence file's Steps 6b to 6d). Row 60 stays wired, not served** |
| **MF6** | Exec latency over a **pooled** sshj session on the EC2 host for the fixed `curl` metrics command and for `docker inspect` (est. 100–300 ms, M-Q3). **UNVERIFIED** | **Measure** on EC2 (P11-4.3 Step 0): 500 execs of each over one `SshSessionPool` session, p50/p95/p99. **Integrator, on the E2E host** (P11-4.3 was built without a live host; the per-target cap and health reserve are configuration, so this number only sets their defaults) | P11-4.3 | ❌ **NOT MEASURED.** P11-4.4's live run proved collection over SSH but did not take the 500-exec latency percentiles; the per-target cap and reserve stay at their configured defaults (close, 2026-10-07) |
| **MF7** | Amazon Linux 2023's effective sshd `MaxSessions` on the EC2 host (upstream default 10 [V: man.openbsd.org/sshd_config], discovery C7). ✅ **Measured 2026-10-06 on the E2E host** (integrator, `sshd -T`): **`MaxSessions 10`**, and `MaxStartups 10:30:100`. So the per-target concurrency cap's default `min(4, MaxSessions − 2)` is 4 and needs no change. The harness half (opening N+1 channels on one connection) was **not run** | **Measure**: `sudo sshd -T \| grep -i maxsessions` on the host, plus opening N+1 channels on one connection in the harness | P11-4.3 | ⚠️ half: the value ✅ (channel-opening half not run) |
| **MF8** | Boot's single-metric response: `GET /actuator/metrics/{name}` → `{"name", "description", "baseUnit", "measurements":[{"statistic":"VALUE","value":…}], "availableTags"}`, so the value is at JSON Pointer `/measurements/0/value` | ✅ **Verified 2026-09-30 (shape)** — [docs.spring.io/spring-boot/3.5/api/rest/actuator/metrics.html](https://docs.spring.io/spring-boot/3.5/api/rest/actuator/metrics.html) example for `jvm.memory.max`. ⚠️ **`process.start.time`'s `baseUnit` and the value's epoch unit are UNVERIFIED** → measure once on EC2 (`curl -s 127.0.0.1:8081/actuator/metrics/process.start.time`) in P11-1.1 Step 0 | P11-1.1, P11-4.2 | ⚠️ shape ✅, unit ❌ — ✅ **Unit measured 2026-10-06 on the E2E host** (integrator, read-only `curl -s 127.0.0.1:8081/actuator/metrics/process.start.time` over SSH): `"baseUnit":"seconds"`, value `1.790665479471E9`, i.e. **epoch seconds with a fractional part** (it matched the container's `StartedAt` 2026-09-29T07:04:39Z). P11-1.1 Step 0, MF8 closed |
| **MF9** | `GET /api/cache/size?filterByNamespace=false` answers a **bare JSON number**. Repo read: `CacheController.java:60-67` returns `long` | **Measure** on EC2 in P11-1.1 Step 0 (body and `Content-Type`). The fixture's `cache.size` source carries no pointer, meaning "the whole body is the number" | P11-1.1 | ⚠️ code ✅, wire ❌ — ✅ **Wire measured 2026-10-06 on the E2E host** (integrator, read-only, over SSH): `GET /api/cache/size?filterByNamespace=false` → `200`, `Content-Type: application/json`, body `0` (a bare JSON number). MF9 closed |
| **MF10** | JDK 17 `java.net.http.HttpClient`: `connectTimeout`, per-request `timeout`, the exception on timeout, and how to bound the body read | WebFetch `https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.Builder.html` and `…/HttpRequest.Builder.html` and `…/HttpResponse.BodySubscribers.html` | P11-4.2a | ✅ **Verified 2026-10-06** (P11-4.2a Step 0), Oracle JDK 17 API pages: `HttpClient.Builder.connectTimeout(Duration)` throws `HttpConnectTimeoutException` for a synchronous call and has no default; `HttpRequest.Builder.timeout(Duration)`: *"If the response is not received within the specified timeout then an HttpTimeoutException is thrown from HttpClient::send"*, and *"not setting a timeout is the same as setting an infinite Duration, i.e. block forever"*, so it bounds the wait for the **response**, and says nothing of a body that is then dripped; `HttpResponse.BodySubscribers.ofInputStream()` documents **no size limit** and requires the caller to read to EOF or `close()` the stream. `HttpClient.Builder.followRedirects` defaults to `NEVER`. **Consequence built:** the body is read at most `maxBodyBytes + 1` under a watchdog that closes the stream at the request deadline; `DirectHttpFetcherIT.theDirectFetcherBoundsTheBodyAndTimesOut` measures a header-then-drip server (mutation: no watchdog, the fetch returned `Ok` after 5 s) |
| **MF11** | Docker `State.StartedAt` and container id on restart, stop/start and recreate | **Plan 010 M18** (filled by its T6.8 Step 0). Re-read, do not re-derive | P11-4.2b | depends on plan 010 |
| **MF12** | `curl` exit codes and `--write-out '%{http_code}'` without `--fail` | **Plan 010 M19** (filled by its T6.1 Step 0). Re-read | P11-4.2a | depends on plan 010 |
| **MF13** | File Mesh publisher: the topics it publishes, the payload per `EventState` (`EventState.java:20-27`), the header timestamp zone, and whether `start`'s `password` (`source/start/Solace.java:28`) is published. | Settled by **P11-5.0** (executed, `p011/fm` `0127b50`, merged here) from [`011-filemesh-publisher-facts.md`](../../design/backend/evidence/011-filemesh-publisher-facts.md). ⚠️ **The earlier "local source read of `pubsubplus-connector-file-common@cd52e31` (the publisher's topic builder and serializer)" was impossible:** that library holds the event **models** and the `EventState` constants but no topic builder and no serializer, and the source/sink connectors are not on this machine (§1). The facts come, in descending strength, from captured listener logs (34,527 events), the consumer code, the FM Manager's topic generator and template, and the model classes. Cite file:line as the evidence file does | P11-5.0 | ⚠️ **Partly measured 2026-10-05.** Topic `<base>/<adapter_id>/<state>` (F1), nine levels in captures (F2), base free text of any depth (F4). Payload measured for `heartbeat` and `fcomplete` (F11, F12), from model classes for `start`/`complete`/`error` (F13), none for `init`/`warning`/`fstart` (F10). Header timestamp: the connector host's local time, no zone, variable-width fraction (F14). **`start`'s `password`: UNVERIFIED** (F15) — treat `start` as possibly carrying a plaintext broker password. Still open: publisher code, the six unmeasured states, other hosts' zones (capture on a spike broker, or obtain the connector repositories) |
| **MF14** | JCSMP **10.30.1**: adding topic subscriptions to a second session and receiving direct messages through an `XMLMessageConsumer` with a `XMLMessageListener`, and the listener threading model | WebFetch the JCSMP 10.30.1 API reference pages (`JCSMPSession.addSubscription`, `getMessageConsumer`). The tree already measured publishing only (`JcsmpEventPublisher.java`) | P11-5.1 | ✅ **Filled by P11-5.1 Step 0** from the sol-jcsmp 10.30.1 jar (`javap`) and measured on a local broker (Results of P11-5.1) |
| **MF15** | PostgreSQL 14 PL/pgSQL `EXECUTE format('%I …', …)` identifier quoting and `%L` literal quoting | WebFetch `https://www.postgresql.org/docs/14/functions-string.html` (`format`) and `…/plpgsql-statements.html` (dynamic commands) | P11-2.1 | ✅ **Filled by P11-2.1** (PostgreSQL 14 documentation; Results of P11-2.1) |
| **MF16** | HikariCP **6.3.3** default `maximumPoolSize` (the arithmetic for M-Q22 (6)'s budget of 4). **UNVERIFIED.** The version is measured (`hikaricp.version` = 6.3.3, 2026-10-05). The `sm-data` pool sets **no** `maximumPoolSize` (`ApplyPersistenceConfiguration.smDataDataSource`, `:94-107`; `SmDataProperties` has no pool property), so P11-3.1 would edit that bean. The pool pins `setSchema("sm_data")` (`:104`), so every `sm_metrics` statement must be schema-qualified, as this plan's SQL is | WebFetch the HikariCP README at tag `HikariCP-6.3.3` ("maximumPoolSize"). Record the arithmetic: pool = N; query+export ≤ 4; ingest writer 1; health/apply the rest. Filled at **P11-3.1 Step 0**, before the budget is built on it. If the landed `sm-data` pool does not set `maximumPoolSize` explicitly, P11-3.1 sets it from this arithmetic | P11-3.1 (MW3; P11-6.x re-read it) | ✅ **Filled by P11-3.1:** HikariCP 6.3.3 `maximumPoolSize` default 10, read from the shipped bytecode; the `sm-data` pool is now explicit (Results of P11-3.1) |
| **MF17** | Bytes per raw row (the discovery's 120–140 B floor is **arithmetic, not measurement**, and leaves out indexes and WAL), insert rate, and 30-day query times on the EC2 host | **Measure** in P11-8.1: **one day first** (Step 0b), including indexes and WAL, projected to the month **before** the month is loaded; then the month (Step 1) | P11-8.1 | ⚠️ **Partly MEASURED 2026-10-06/07 at ONE WEEK of S-A (`OR-112` (3)), the month and six months EXTRAPOLATED:** 170.19 B/row (one day) and **170.68 B/row** (week, 23.04 M rows, heap 81.32 + index 89.34), WAL 244 and **247 B/row**, 23.7 k rows/s DB-only load, six-month raw at S-A **88.5 GB EXTRAPOLATED**; 30-day query times NOT measured (the window held 7 days) and the co-located `IngestService` insert rate NOT measured (its one figure is tunnel-dominated); the HTTP query times are tunnel-dominated. Measured: bytes/row and WAL at one week. [`011-storage-gate.md`](../../design/backend/evidence/011-storage-gate.md) |
| **MF18** | Spring MVC async request timeout for a `StreamingResponseBody`: the property (`spring.mvc.async.request-timeout`, expected), what applies when it is unset (the servlet container's `asyncTimeout`), and the embedded Tomcat default. The export's 30-minute timeout (M-Q22 (2)) and a 20 M-row stream both depend on it. **UNVERIFIED** | WebFetch the Spring Boot **3.5** application-properties reference (`docs.spring.io/spring-boot/3.5/appendix/application-properties/index.html`, "spring.mvc.async.request-timeout") and the Tomcat connector reference for the Tomcat version the reactor actually resolves (read it first with `mvn -o -f backend/pom.xml -pl launcher dependency:tree -Dincludes=org.apache.tomcat.embed`; the page is `tomcat.apache.org/tomcat-<major.minor>-doc/config/http.html`, `asyncTimeout`). Then **measure** it in P11-7.2 Step 0 with a launcher IT that streams for longer than the default. **Also (widened, round 2):** export is the gateway's first `StreamingResponseBody` route, and the gateway has no `DispatcherType`, `ASYNC` or `AsyncHandlerInterceptor` handling today (measured: no match in `console-gateway/src/main/java`; **re-measured 2026-10-05** over `console-gateway/src/main` and `launcher/src/main`: no match for `DispatcherType`, `AsyncHandlerInterceptor` or `async.request-timeout`). Whether `AuthenticationInterceptor`, the rate gates and the OR-25 audit context run **again** on the async re-dispatch (`DispatcherType.ASYNC`) is UNVERIFIED framework behaviour. WebFetch the Spring Framework 6.2 reference on async requests and `HandlerInterceptor`/`AsyncHandlerInterceptor`, then measure in `ExportAsyncTimeoutIT`: **exactly one** authorization decision, **one** rate-gate count and **one** audit row per export | P11-7.2 | ✅ **Measured by P11-7.2:** the container default is 30 s, so the export's MVC async timeout is set explicitly; an async re-dispatch DOES re-invoke `preHandle` (measured against the framework text), handled in `AuthenticationInterceptor` (Results of P11-7.2) |
| **MF19** | PostgreSQL 14 privileges for `pg_database_size` and `pg_total_relation_size` when called by `sm_dp_runtime` (expected: `CONNECT` on the database, or `pg_read_all_stats`, for the first). **UNVERIFIED** | WebFetch `https://www.postgresql.org/docs/14/functions-admin.html` ("Database Object Size Functions"). Then measure as `sm_dp_runtime` in P11-3.2 | P11-3.2 | ✅ **Filled by P11-3.2** (Results of P11-3.2) |
| **MF20** | PostgreSQL 14: the lock `ATTACH PARTITION` takes on the table a partition's **cloned FK references** (`sm_metrics.series`), and whether it conflicts with ingest's open `INSERT … ON CONFLICT` on `series`. MF4 covers only the parent. **UNVERIFIED** | WebFetch the `ALTER TABLE` and explicit-locking pages (`…/docs/14/sql-altertable.html`, `…/docs/14/explicit-locking.html`), then **measure** in P11-2.1 (`attachTakesNoAccessExclusiveOnTheParent`, with an open `series` insert). Record the lock observed in `pg_locks` and its effect on the retry and `runway_degraded` path | P11-2.1 | ✅ **Measured by P11-2.1:** `ATTACH` takes `SHARE ROW EXCLUSIVE` on the series table (Results of P11-2.1) |
| **MF21** | PostgreSQL 14 `search_path`: when `pg_temp` is **not** listed, it is searched **first** for relations (before `pg_catalog`), and `pg_temp` is never searched for functions or operators. This is why an unqualified `pg_class`/`pg_inherits` inside a `SECURITY DEFINER` function can be shadowed. **UNVERIFIED** | WebFetch `https://www.postgresql.org/docs/14/runtime-config-client.html` (`search_path`) and quote the lines | P11-2.1 | ✅ **Filled by P11-2.1** (PostgreSQL 14 documentation; Results of P11-2.1) |
| **MF22** | Whether ArchUnit **1.5.0** can see a **string constant** in a class (a literal such as `:8081`). Its rules are about dependencies and accesses; a constant-pool read is expected **not** to be exposed. **MEASURED 2026-10-07 (P11-9.1): it cannot** -- the 1.5.0 jar has no literal/constant-pool type (only `JavaEnumConstant`) and the guide (1.5.1) lists only field, method and constructor accesses; the guard is the source scan | WebFetch the ArchUnit 1.5.0 user guide (`archunit.org/userguide/html/000_Index.html`) and search it for constant or literal support. If it has none, the rule is a **source-tree scan** (a plain JUnit test in `arch-tests` that walks `backend/*/src/main/java`, locating the repo root the way `DesignSketch.repoRoot()` does, `DesignSketch.java:34-52`), and its proof points the same scanner at a planted fixture directory | P11-9.1 | ✅ |
| **MF23** | PostgreSQL 14's error for an insert that **no partition accepts** vs a failed `CHECK`: the SQLSTATE of each (both are expected to be `23514`, `check_violation`) and whether the error carries a **constraint name** (expected: `raw_value_finite` for the CHECK, **none** for the routing failure). **UNVERIFIED** | **Measure** in P11-2.1 (`anInsertWithNoPartitionIsRefusedNotRoutedToADefault` and `aNonFiniteValueIsRefused` each record SQLSTATE and `ServerErrorMessage.getConstraint()`, read inside `dp.apply.persistence`, the only package that may see `org.postgresql`, `PlaneBoundaryTest.java:128-134`) | P11-2.1, P11-3.1 | ✅ **Measured by P11-2.1:** both failure paths raise `23514` (Results of P11-2.1) |
| **MF24** | The JSON-Schema keywords and the **end anchor** that P11-1.1's new fields need, in all three validators that read the schema: `com.networknt:json-schema-validator` **2.0.8** (cp-core, plan 010 T3.0), Python `jsonschema` (`validate-package.py:20`, `Draft202012Validator`) and Ajv (`frontend/contracts/solution-package/validate.ts`, strict). T3.0 measured only the 24 keywords the schema used then (`docs/tasks/t3-0-json-schema-library.md:61-62`); `propertyNames`, `maxProperties`, `maximum` and `maxLength` were not among them, and its only `pattern` was `^[a-z][a-z0-9-]*$`. | ⚠️ **Partly measured 2026-10-05** (scratch Java and Python, not committed). networknt 2.0.8 enforces `propertyNames`, `maxProperties` and `maxLength`, and **refuses** a trailing newline under `^[A-Za-z0-9_.-]{1,64}$` (`k=v\n` → 1 error). Python `jsonschema` 4.26.0 **accepts** one trailing newline under the same pattern (`'abc\n'` valid, `'abc\n\n'` invalid), so the two gates **disagree**. `(?![\s\S])` in place of `$` refused every newline variant in both. **Not measured:** `maximum` in networknt; all three keywords and the anchor in Ajv strict mode. P11-1.1 Steps 0b/0c settle those, in `NegativeFixtureParityTest`'s harness | P11-1.1 | ✅ **Measured by P11-1.1 Step 0c** in `NegativeFixtureParityTest` and the Python gate; the end anchor is `(?![\s\S])` (Results of P11-1.1) |
| **MF25** | The frontend's tests that compile the root schema (`frontend/contracts/solution-package/validate.ts:12`) stay green when the schema changes, with `frontend/` untouched (OQ-24). The draft never named this consumer | **Measure** (P11-1.1 Step 0b): `cd frontend && npm ci && npx vitest run --project contracts`, and `npx vitest run src/app/dev` (`testPackage.ts:62` has an `http_json` source with no `port`). Record the counts before and after | P11-1.1 | ⚠️ **half: Step 0b executed by P11-1.1** (the frontend's contract tests, read-only, counts in its commit message); **the close did not re-run them** (`frontend/` is read-only here) |
| **MF26** | Whether Solace topic matching is **case-sensitive**. Two captured File Mesh adapter ids differ only by case (`…_V1` and `…_v1`, [`011-filemesh-publisher-facts.md`](../../design/backend/evidence/011-filemesh-publisher-facts.md) §2.4, F8), so the match OQ-5 rules is exact or it is not. The Solace wildcard page fetched 2026-10-05 gives `My/Test/Topic` and `my/test/topic` as examples but does not state the rule, and two other documentation URLs returned a 404 page (facts §6). **UNVERIFIED** | **Measure** on the local spike broker (`solace-pubsub-enterprise:10.25.0.177`, ⛔ never the `oms-*` containers): subscribe to `a/B/c`, publish to `a/b/c` and to `a/B/c`, record which are delivered, with the broker version and the date. Do it **before the sitting**, as OQ-5 is not ruled without it | OQ-5, P11-5.1 | ❌ — ✅ **Measured 2026-10-06 on a local broker** (`solace-pubsub-enterprise:10.25.0.177`, SMF via JCSMP 10.28.3, default VPN): **topic matching is case-sensitive**. Subscribe `a/B/c`: publish `a/b/c` not delivered, `a/B/c` delivered; subscribe `a/*/c`: both delivered; subscribe `a/b/*`: `a/B/x` not delivered, `a/b/x` delivered. The local container was removed after the run. MF26 closed |
| **MF27** | Whether Solace **message-VPN names** (and client usernames) are case-sensitive, which decides whether the `OR-112` (2) broker-connection guard (`sm_data` V019, `BrokerConnectionMain`) compares the VPN name exactly | ✅ **Measured 2026-10-07 on a local broker** (`solace-pubsub-enterprise:10.25.0.177`, SEMP v2.48, JCSMP 10.28.3; `OR-117`): **case-sensitive.** Creating `CaseVpn` then `casevpn` gives two VPNs (an exact repeat answers `ALREADY_EXISTS`); `GET …/msgVpns/CASEVPN` is `NOT_FOUND`; a client logs in only to the exactly named VPN (`CASEVPN` → `403 Message VPN Not Allowed`); client usernames `u1`/`U1` are distinct. The guard's exact VPN comparison (with `lower(host)`) is therefore correct as built | `OR-112` (2), P11-5.1 | ✅ |

---

## Migration and id allocation

- **`sm_metrics`** has its **own** Flyway history (M-Q19 (b)). It starts at `V001` and has no contention with
  `sm_data`. Migrations live in `backend/dp-core/src/main/resources/db/migration/sm_metrics/`. **Every
  `sm_metrics` migration ends with `SELECT sm_metrics.ensure_metric_partitions();`** (M-Q13: *"each migration seeds
  3 months ahead"*). P11-2.1 owns `V001`, and writes it **in the shape the sitting ruled** (`OR-92`: OQ-12's `out_of_order` and
  OQ-15's `ambiguous_placement` in the reason CHECK, OQ-21 (2a)'s partitioning; ✅ ruled 2026-10-05). **OQ-20 is still open (`OR-95`)**, so the counter
  key is exactly M-Q21's (`placement_id NOT NULL`, OQ-20 (a)'s shape).
  `V002` is reserved for P11-5.1, used **only** if OQ-20 is ruled (b) or (c) **after** P11-2.1 has merged
  (it then widens `ingest_counter`'s key; OQ-12 and OQ-15 are already in `V001`). A later task that needs another `sm_metrics` migration
  asks the integrator.
- **`sm_data`: the numbers are fixed now.** *Re-checked 2026-10-05 against plan 010's allocation table
  (`010-…md:366-376`) and the tree: `V001`–`V010` exist (`V007` inventory, `V008` apply-request state version, `V009`
  secret store, `V010` config generation; `sm_secret` has no history of its own, its tables are created by `V009`), and
  plan 010 still allocates `V011` (T6.8), `V012` (T7.2), `V013` (T9.3, conditional) and `V014` (T11.2).* Plan 010 ends at
  **V014** (T11.2's `fleet_action`, W11). Plan 008 is **unscheduled**, and its block has no fixed size: plan 010 T0.3
  Step 2 says only *"V015 onward"* (`010-…md:1350-1351`; plan 008's own header, point 4). Waiting for it would put an
  unscheduled plan on this plan's critical path. So:
  - **`V015`** is P11-2.2 (`V015__metric_definitions_and_endpoints.sql`).
  - **`V016`** is reserved for P11-5.1's `broker_connection` (`V016__broker_connection.sql`), used only if OQ-3 is
    ruled (a). If it is not needed, the gap is harmless (plan 010's own precedent for V013, `010-…md:375`, `:384-389`). If
    OQ-3 is ruled (a) with a new secret purpose, **V016 also widens `sm_secret.secret`'s `secret_purpose` CHECK**
    (`V009__secret_store.sql:23-24`), which is why that migration cannot be left to a later number.
  - ⚠️ **Landed 2026-10-06 (wave-2 integrator):** P11-2.2's migration merged as **`V016__metric_definitions_and_endpoints.sql`**,
    behind OR-108's `V015__apply_attempt_preimage_change.sql`; the `V015` and `V016` above are history.
  - ⚠️ **Superseded 2026-10-06 (integrator):** OR-96's layout migration is renumbered **V013** before publication, and
    every unmerged `sm_data` migration (this plan's P11-2.2 onward included) now takes the **next free number at merge
    time** — see plan 010's allocation table note. The fixed numbers below are history.
  - **Wave 3 merge (2026-10-06, integrator):** P11-5.1's `broker_connection` takes **`sm_data` `V017`** (the lane built it as the
    placeholder `V018`; `sm_metrics` `V002__broker_ingest_counter.sql` keeps `V002`). `sm_data` is now `V001`-`V017` with no gap; plan 008's
    next free number is `V018`.
  - **Plan 008 renumbers above plan 011: V017 onward.** P11-0.1 adds a dated note to plan 008's header saying so,
    beside plan 010 T0.3's note, which it corrects and does not delete. ⚠️ **Superseded 2026-10-05 (wave review of
    `p011/merge`): `V017` is taken** by plan 010 S6's `designer_layout` (`OR-96`); plan 008 is **V018 onward** (next bullet).
  - **`V017` is `OR-96`'s `designer_layout`** (`V017__designer_layout.sql`, plan 010 slice S6, branch `p010/s6` `bce79e5`,
    unmerged). Plan 010's allocation table and plan 008's header carry dated notes saying so.

  ⛔ Plan 010's V007–V014 are not renumbered. ⚠️ **Corrected 2026-10-05 (wave review):** the earlier claim here, that
  P11-2.2's merge after plan 010 T11.2 keeps plan 010's version-order rule "without the recreate-the-dev-database
  fallback", **does not hold**. S6 is in flight and carries `V017`, so it is expected to merge **before** plan 010's own
  `V011`–`V014` and before plan 011's `V015`/`V016`. Those then merge **out of version order**, and the integrator
  **recreates the persistent dev `sm_data`** under plan 010's "Out-of-order merges" rule (`010-…md:391-395`) and records it
  (pre-production, no customer database). P11-2.2 still merges after T11.2 and after P11-2.1. A database that has
  already applied `V017` refuses a lower version unless Flyway's `outOfOrder` is on (plan 010 M9). Also before S6
  merges: its unpushed `V017` header comment says "V015 and V016 are plan 008's", which is wrong (they are plan 011's); the
  slice owner corrects the comment (not edited here: another worktree).
- **`sm_control`: `V014`** is P11-2.3 (`V014__dashboards_and_operator_metrics_verbs.sql`), after plan 010 T2.0's
  V013 (✅ landed: `V001`–`V013` exist; `V013__dp_remote_governance.sql`). Plan 010 allocates no later `sm_control` number
  (`010-…md:388-389`: *"`sm_control` numbering is unchanged (`V013` is T2.0's)"*). It is pinned in `CHECKSUMS.sha256` in
  the same commit, and its SHA is anchored in `frozen-migration-anchors.txt` in a follow-up commit after push
  (OR-22). If any other `sm_control` migration has landed first, the integrator stops P11-2.3 and re-allocates.
- **The orphan** `backend/src/main/resources/db/migration/V001__telemetry_schema.sql` and
  `backend/src/test/sql/V001_constraints_test.sql` (built by no module, `backend/README.md:150-153`, re-read 2026-10-05) are **deleted**
  by P11-2.1 (discovery default D-8, not an owner ruling until OQ-22; not overridden by the rulings). Their useful
  constraint cases are salvaged into `SmMetricsSchemaIT` without `DROP SCHEMA`. If OQ-22 rejects D-8, P11-2.1 leaves
  the orphan in place and says so.

---

## Single-owner files — who may edit what

Plan 010's table still binds for its own files until plan 010 closes. Plan 011's owners, in merge order:

| File | Owner in plan 011 | Everyone else |
|---|---|---|
| `contracts/solution-package.schema.json` **and its byte-identical classpath copy `backend/cp-core/src/main/resources/contracts/solution-package.schema.json`** (pinned by `PackageSchemaMirrorLockTest`), `contracts/validation/**` (the two `validation/fixtures/overlap/*.json` need **no** edit: they already carry `port`), `contracts/run-checks.sh` steps 1–3, `contracts/view-models/src/types.ts` (only if affected); the Java side of the same change: `cp-core/.../catalog/validation/SemanticGates.java` and `NegativeFixtureParityTest.java` (plan 010 T3.1's), `cp-api/.../designer/PackageModel.java`, `cp-core/.../designer/PackageJsonReader.java` and the two test files that call the changed record constructors (`DesignerStageValidatorTest.java`, `DesignerProjectorRulesTest.java`, measured) | **P11-1.1** (after plan 010 T0.1a and T3.1; merged after plan 010's in-flight S5/S6 branches are pushed, per-task gates) | read only. ⛔ `contracts/view-models/fixtures/**` stays **ownerless** (OR-34's 33-fixture lock). ⛔ **Everything under `frontend/` is read only for this plan** (owner standing rule), including the three frontend files that declare metrics or compile the schema (OQ-24) |
| `contracts/openapi/console.yaml`, `validate-openapi.py`, `ENDPOINT-INVENTORY.md` §1–§3; `cp-api/.../authz/ConsoleOperations.java` and its `ConsoleOperationsMirrorTest`; `console-gateway/.../http/ErrorCode.java`; `console-gateway/.../http/OperationPolicy.java` (the reach and authentication-only sets) and `ReachOperationsMirrorTest`; `console-gateway/.../http/DeclareInstanceRequest.java` (plan 010 T4.4's record: `metricsPorts`) and `SchemaMirrorLockTest.java` | **P11-1.2** (after plan 010 T4.4, T5.3 and T10.3, or W11 under OQ-28 (a); `OperationPolicy`'s only plan 010 owner is T1.3, W1). Inventory "served" rows go in **P11-10.1**. If plan 010 T12.1 (W12) is open at the same time, P11-1.2's inventory hunk rebases onto T12.1's, and the integrator merges T12.1 first | read only |
| `dp-core/.../db/bootstrap/sm_data_phase0.sql`, `SmDataMigrationMain.java` | **P11-2.1** (after plan 010 T2.4) | read only |
| `dp-core/src/test/.../persistence/SmDataBootstrapIT.java` and `dp-core/src/test/.../persistence/SketchMirrorTest.java` (**not** `eda-walking-skeleton.md` §3.1: at `bd802fc` the mirror covers V001–V006 only, [Prerequisite 7](#prerequisites)) | **P11-2.1 first** (both files: `PHASE0_AFTER_THE_SKETCH` and the Phase 0 count 9 → 11; `SmDataBootstrapIT`: the schema-qualified history lookup and its `sm_metrics` twin), **then P11-2.2** (`SmDataBootstrapIT` only: V015's migration list and count, tables, functions, triggers), **then P11-5.1** (`SmDataBootstrapIT` only: V016, only if OQ-3 (a)). **Sequential merges:** P11-2.2 rebases onto P11-2.1's merge before it touches `SmDataBootstrapIT`, and P11-5.1 onto P11-2.2's. Plan 010's own later tasks (T6.8 V011, T7.2 V012, T9.3 V013, T11.2 V014) also move `SmDataBootstrapIT`'s exact sets; whichever merges later rebases | read only |
| `dp-core/.../dp/metrics/MetricsConfiguration.java` | **P11-3.1** only. It holds exactly the two **shared** beans, `MetricsDbBudget` and the `metricsExecutor`. No later task edits it: each later area has its own `@Configuration` (see [Readiness and configuration](#readiness-and-configuration-per-area)) | read only |
| `dp-api/.../metrics/ingest/CollectorReason.java` | **P11-3.1** (every reason the main sitting ruled, OQ-12/OQ-15). **P11-5.1** only in the `V002` fallback (a reason ruled after P11-3.1 merged) | read only |
| `dp-core/.../apply/persistence/TenantEnumerationRepository.java` | **P11-4.3**, only if OQ-6 widens the named caller (the javadoc at `:69-83` **already** names plan 011's `MetricsCollector` as the fourth caller, written by T2.4 Step 3b, ✅ landed; javadoc only). Plan 010 owns the file (`010-…md:406`) | read only; P11-4.3 and P11-5.1 never re-spell its SQL |
| `dp-core/.../dp/metrics/collect/MetricsBackgroundLoop.java` | **P11-4.3**, then **P11-5.1** (the push reconcile hook) | read only |
| `arch-tests/.../ApplyPathBoundaryTest.java` (plan 010 T6.7's file; ⛔ **does not exist at `bd802fc`**) | **P11-7.2** (extends `theGatewayHoldsNoDatasource`), then **P11-9.1** (retires the two facade rules). Both wait for T6.7 | read only |
| `sm_control/V014__…sql`, `CHECKSUMS.sha256`, `ControlPlanePersistenceConfiguration.java` readiness lists, `cp-core/.../identity/AuditOperations.java` | **P11-2.3** | read only |
| `dp-core/.../dp/inventory/InventoryService.java`, `dp-api/.../inventory/DeclareInstanceCommand.java`, `console-gateway/.../http/DeclareOrchestrator.java`, `dp-core/.../dp/config/ConfigRecordServiceImpl.java`, `dp-api/.../config/StageCommand.java`, the gateway stage flow | **P11-4.1** (after plan 010 W11: T9.4 is `ConfigRecordServiceImpl`'s last plan 010 owner) | read only |
| `cp-core/.../designer/DesignerProjectorImpl.java` | **P11-4.1** (a pure extraction of `evaluate` **and** of the defaults-applied, normalized value building; `DesignerProjectorFixtureTest` must stay green) | read only |
| `cp-api/.../catalog/SolutionCatalog.java` (plan 010 T3.2's port) | **nobody**: `find(…)` already returns `LoadedPackage.digest()`, so P11-4.1 adds no `digestOf` | read only |
| `dp-core/.../dp/DataPlaneConfiguration.java` · `cp-core/.../ControlPlaneConfiguration.java` | each task adds **one `@Import` line** | rebase-merge |
| `arch-tests/src/test/java/com/solace/sm/arch/MonitoringBoundaryTest.java`, `arch-tests/src/test/java/com/solace/sm/dp/archfixture/monitoring/**`, `arch-tests/src/test/java/com/solace/sm/gateway/archfixture/monitoring/**` | **P11-7.2** | read only |
| `demo-launcher/**`, `demo_control` migrations; the demo probes in `dp-core/.../apply/runtime/ssh/` (`SshDockerMetrics`, the Cache methods of `SshDockerTransport`/`SshjDockerTransport`, `SshDockerMetricsTest`) | **P11-9.1** (delete) | ⛔ never |
| `docs/design/DECISIONS.md` | the **integrator** (the rulings are already registered; at merge otherwise) | draft with `«next free OR»` / `«next free BE-D»` |

### Readiness and configuration, per area

**Readiness checks are per configuration** (plan 010's rule). `sm_metrics` gets its own `InitializingBean` in
`MetricsPersistenceConfiguration` (P11-2.1). P11-2.2's five `sm_data` tables get theirs in
`MetricDefinitionPersistenceConfiguration` (P11-2.2), so the two parallel MW2 tasks never share a configuration
file. No task edits `ApplyPersistenceConfiguration`'s `EXPECTED_TABLES` (`ApplyPersistenceConfiguration.java:54`).

**Every bean has a named registering configuration, and no two tasks share one** (plan 010 T7.2's
`HealthConfiguration` pattern, `010-…md:3742`: one `@Configuration` per area, with its `@EnableConfigurationProperties`,
and **one `@Import` line** in `DataPlaneConfiguration` or `ControlPlaneConfiguration`). **JDBC confinement splits
each area in two** (measured: `jdbcIsConfinedToPersistencePackages`, `PlaneBoundaryTest.java:128-134`, confines every
class that references `javax.sql`/`org.springframework.jdbc`/`org.postgresql` to `dp.apply.persistence..` or
`cp.persistence..`, and a `@Bean` method taking `NamedParameterJdbcTemplate` is such a reference,
`ControlPlanePersistenceConfiguration.java:34-43`). So each area has a **persistence** configuration that builds its
`Jdbc*` repositories, and a **service** configuration that builds its services from interface types only.

| Task | Persistence configuration (builds the `Jdbc*` beans) | Service configuration (builds the services, `@EnableConfigurationProperties`) |
|---|---|---|
| P11-2.1 | `dp.apply.persistence.MetricsPersistenceConfiguration` (`sm_metrics` readiness, `JdbcPartitionRunwayRepository`) | — |
| P11-2.2 | `dp.apply.persistence.MetricDefinitionPersistenceConfiguration` (V015 readiness, `JdbcMetricDefinitionRepository`) | — |
| P11-3.1 | `dp.apply.persistence.IngestPersistenceConfiguration` (`JdbcSeriesRepository`, `JdbcRawSampleRepository`, `JdbcIngestCounterRepository`, `JdbcRestartMarkerRepository`) | `dp.metrics.MetricsConfiguration` (**the shared** `MetricsDbBudget` and `metricsExecutor`, a `ScheduledExecutorService` for the runway keeper and the disk alarm; `MetricsDbProperties`) and `dp.metrics.ingest.MetricsIngestConfiguration` (`IngestService` as `TelemetryIngestPort`, `IngestBuffer`, `IngestWriter`, `IngestProperties`) |
| P11-3.2 | `dp.apply.persistence.RollupPersistenceConfiguration` (`JdbcRollupRepository`, `JdbcMetricsDiskRepository`) | `dp.metrics.rollup.MetricsRollupConfiguration` (`RollupService`, `PartitionRunway`, `MetricsDiskAlarm`, `RollupProperties`) |
| P11-4.2 | — (no JDBC) | `dp.metrics.collect.fetch.MetricsFetchConfiguration` (`SshMetricFetcher`, `DirectHttpFetcher`, `FetchProperties`) |
| P11-4.3 | `dp.apply.persistence.CollectPersistenceConfiguration` (`JdbcRestartObservedGenerationRepository`) | `dp.metrics.collect.MetricsCollectConfiguration` (`MetricsBackgroundLoop`, `MetricsCollector`, `CollectionPlanBuilder`, `SourceScheduler`, `CollectorProperties`) |
| P11-5.1 | `dp.apply.persistence.BrokerConnectionPersistenceConfiguration` (`JdbcBrokerConnectionRepository`) | `dp.metrics.push.MetricsPushConfiguration` (`PushIngestService`, `BrokerSampleDecoder`, `PushAttribution`) and `dp.meshbridge.ingest.MeshbridgeIngestConfiguration` (`BrokerSubscriptionManager`, `JcsmpSampleSubscriber`; JCSMP stays in meshbridge) |
| P11-6.1 | `dp.apply.persistence.QueryPersistenceConfiguration` (`JdbcMetricsQueryRepository`) | `dp.metrics.query.MetricsQueryConfiguration` (`MetricsQueryServiceImpl`, `QueryProperties`) |
| P11-6.2 | `dp.apply.persistence.ExportPersistenceConfiguration` (`JdbcMetricsExportRepository`) | `dp.metrics.export.MetricsExportConfiguration` (`MetricsExportServiceImpl`, `CsvEncoder`, `ExportProperties`) |
| P11-4.1 (CP) | — | `cp.designer.MetricApplicabilityConfiguration` (`MetricApplicabilityImpl`), one `@Import` in `ControlPlaneConfiguration` |
| P11-7.1 (CP) | `cp.persistence.DashboardPersistenceConfiguration` (`JdbcDashboardRepository`, and the **first production `AuditLog` bean**, `JdbcAuditLog`, `cp-core/.../cp/persistence/JdbcAuditLog.java:14-20`, unless plan 010 has landed one, Step 0) | `cp.dashboard.DashboardConfiguration` (`DashboardService`, `DashboardDocumentValidator`, `DefaultLayerDeriver`) and `cp.audit.ExportAuditConfiguration` (`AuditLogExportAudit` as `ExportAudit`) |

So P11-5.1 ∥ MW6 and P11-6.1 ∥ P11-6.2 never share a configuration file: each adds only its own configurations and
one `@Import` line per configuration (rebase-merged, the single-owner table's `DataPlaneConfiguration` row).

---

## Dispatch map — what may run in parallel

**OQ-28 is RULED (b), `OR-93`.** Read each "under OQ-28 (a)" clause below as the option not taken. Six tasks start after the sitting
on their per-task gate: P11-2.1 and P11-2.3 now, P11-7.1 after P11-2.3, P11-4.2a after P11-1.1 and plan 010 T6.1, P11-1.1 (built now, merged
only after plan 010's in-flight S5/S6 branches are pushed), P11-1.2 after P11-1.1 and plan 010 T4.4, T5.3 and T10.3; P11-2.2 may be developed early
but merges after plan 010 T11.2's `V014`. ⚠️ **Everything stays LOCAL until its wave review:** S2 is reviewed and on the local integration
branch but held from origin by the owner's push policy, so these tasks build on the local branch.

**A wave is a set of tasks whose files are disjoint and whose dependencies are merged.** Each task runs as a Sonnet
5.5 high implementer in its **own worktree** off the pushed integration head
(`git worktree add ../p011-<task> -b p011/<task> integration/dp-remote-demo`). **Review cadence (`OR-92`, OQ-25 (b); `OR-70`'s):
one Opus 5.5 high review per wave, one fix round, the full build at each wave's end, and one combined Fable 5.1 review at the
end** (in place of P11-10.1 Step 4). The integrator merges **in the listed order**, then runs `mvn -o -f backend/pom.xml clean verify`,
`./contracts/run-checks.sh --strict` and `git diff --check`, and only then opens the next wave. **Never `mvn
install`.** Testcontainers runs are handed out one at a time.

| Wave | Tasks (parallel within a wave) | Opens when | Sequential constraints |
|---|---|---|---|
| **MW0** | **P11-0.1** (records) → **P11-0.2** (DM adversarial review + docs hygiene) · **P11-5.0** (File Mesh facts, docs only, a local source read; it may run beside P11-0.1/0.2 in its own worktree) → **owner sitting** (held 2026-10-05: `OR-92`…`OR-95`; OQ-3, OQ-5, OQ-20 and OQ-29 deferred until MF26) → **P11-0.1 Step 4b** (the post-sitting record hand-off, still open) | **Open since 2026-10-05 (`OR-91`, OQ-19 ruled): docs only, in parallel with plan 010. P11-0.1, P11-0.2 and P11-5.0 are executed** (`a521378`, `7f8a6dc`, `0127b50`; merged on `p011/mw0`). The rulings' closing line (*"execute after plan 010 S6/S7"*) is relaxed **for MW0 only**; every other wave opens as the [per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why) say. MW0 is still before storage, as M-Q1 requires | ⛔ P11-0.1 → P11-0.2 sequential, one agent; P11-5.0 was disjoint (one new evidence file). M-Q1: the DM review runs **before storage** |
| **MW1** | **P11-1.1 → P11-1.2** (same agent) | The sitting **and OQ-28 (b)**; under OQ-28 (a): plan 010 W11 merged. Per task ([per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)): P11-1.1 is developed at once but **merges after plan 010's in-flight S5/S6 branches are pushed**; P11-1.2 follows P11-1.1 and needs plan 010 **T4.4, T5.3 and T10.3** (not W11) | one agent: both are single-writer contract changes, and `run-checks.sh` is shared |
| **MW2** | **P11-2.1** (`sm_metrics`) · **P11-2.2** (`sm_data` V015) · **P11-2.3** (`sm_control` V014, dashboards + Operator verbs) | P11-2.1 and P11-2.3: the sitting **and OQ-28 (b)** (S2 is pushed; nothing more from plan 010); under (a): plan 010 W11 merged. P11-2.2: **developed** after P11-1.1, **merged** after P11-2.1 **and plan 010 T11.2 (`V014`, W11)**, unless OQ-28 (c) | disjoint schemas and configuration classes (`MetricsPersistenceConfiguration` vs `MetricDefinitionPersistenceConfiguration`). ⚠️ **One shared file set:** `SmDataBootstrapIT` (P11-2.1 and P11-2.2) and `SketchMirrorTest` (P11-2.1 only). `eda-walking-skeleton.md` §3.1 is **not** edited ([Prerequisite 7](#prerequisites)). **Merge order P11-2.1 → P11-2.2 → P11-2.3**: P11-2.2 is developed in parallel but rebases onto P11-2.1's merge and only then applies its V015 deltas to `SmDataBootstrapIT` (single-owner table). P11-2.3 touches none of them. Plan 010's own later migration tasks (T6.8 `V011`, T7.2 `V012`, T9.3 `V013`, T11.2 `V014`) also move `SmDataBootstrapIT`'s exact sets: whichever merges later rebases |
| **MW3** | **P11-3.1 → P11-3.2** (same agent) | P11-2.1, P11-2.2 merged (so, through P11-2.2, plan 010 T11.2) | P11-3.2's rollups read P11-3.1's `increment` column semantics. P11-3.1 creates the one `MetricsDbBudget` that MW6 shares |
| **MW4** | **P11-4.1** (declare/stage wiring) · **P11-4.2a** (probe command builders, pointer extractor, direct fetcher) → **P11-4.2b** (SSH fetcher) → then **P11-4.3** (collector) → **P11-4.4** (EC2 gate) | P11-4.1: P11-1.2, P11-2.2 and plan 010 W11 (T9.4 frees `ConfigRecordServiceImpl`). P11-4.2a: P11-1.1 and plan 010 T6.1 (**not** T7.1; OQ-28 (b); under (a): W10). P11-4.2b: P11-4.2a, plan 010 T7.1 and M18 (T6.8 Step 0). P11-4.3: P11-4.1, P11-4.2b, P11-3.2 and plan 010 T6.5 and T7.2 | P11-4.1 ∥ P11-4.2 (disjoint: gateway/inventory/config vs `ssh`/`metrics.collect.fetch`). P11-4.3 and P11-4.4 are one agent |
| **MW5** | **P11-5.1** (push) | **MW4 merged** (it hooks into P11-4.3's `MetricsBackgroundLoop`), P11-1.1, P11-2.2 merged (its `SmDataBootstrapIT` delta rebases onto P11-2.2's), OQ-4, OQ-6, OQ-15, OQ-26 ruled (`OR-92`, `OR-94`); **OQ-3, OQ-5, OQ-20 and OQ-29 still to rule, after MF26 is measured (`OR-95`)** | P11-5.0 moved to MW0. P11-5.1 may run in parallel with MW6 (disjoint files and configurations) |
| **MW6** | **P11-6.1** (query) · **P11-6.2** (export) | P11-6.1: P11-3.2 **and P11-4.3** (its `activeMetrics` read is backed by `JdbcRestartObservedGenerationRepository`). P11-6.2: **P11-3.1** (the `MetricsDbBudget`) and P11-2.1's tables, **not** P11-3.2 (its repository reads `metrics_raw` and `series` only), so it may run beside P11-3.2 and MW4 | disjoint: `metrics.query` vs `metrics.export`, their repositories and their own configurations. Neither creates the budget; both inject it |
| **MW7** | **P11-7.1** (CP dashboards + export audit) → **P11-7.2** (gateway routes) | P11-7.1: P11-2.3 and **OQ-28 (b)** (plan 010 T3.2 is landed; under (a): W11). P11-7.2: MW6, P11-7.1, P11-4.1, P11-1.2, plan 010 T6.7 | P11-7.2 composes P11-7.1's port. **Whichever of P11-5.1 and P11-7.2 merges second** creates `launcher/.../PushedMetricsQueryableOverRestIT.java` (path 2's REST half, over the real boot); the integrator names which at dispatch |
| **MW8** | **P11-8.1** (storage gate on EC2, as an Operator) | MW2–MW7 merged; owner confirms PG on the host | alone |
| **MW9** | **P11-9.1** (demo monitoring retirement) | MW7, MW8, plan 010 T12.1 | alone |
| **MW10** | **P11-10.1** (close) | all | alone |

**Critical path (honest, per OQ-28).** What bounds the back half of this plan is the same under every option: the plan 010
tasks that free the files MW3 and MW4 need. They are **T11.2 (`V014`, W11) → P11-2.2 (merge)** for storage, **T9.4 (W11) →
P11-4.1** for declare and stage, and **T6.5 and T7.1/T7.2 (W8a, W10) → P11-4.2b** for collection, which join at **P11-4.3**.
What OQ-28 changes is how much work sits *behind* W11.

| | Serial chain after plan 010 W11 merges | Tasks |
|---|---|---|
| **OQ-28 (a)**, as written before this review | P11-1.1 → P11-1.2 → P11-2.1 → P11-2.2 (rebased) → P11-3.1 → P11-3.2 → P11-4.3 → P11-6.x → P11-7.2 → P11-8.1 → P11-9.1 → P11-10.1 | 12 |
| **OQ-28 (b)**, recommended, **RULED (`OR-93`)** | P11-2.2 (rebase + verify) → P11-3.1 → P11-3.2 → P11-4.3 → P11-6.1 → P11-7.2 → P11-8.1 → P11-9.1 → P11-10.1. P11-1.1, P11-1.2, P11-2.1, P11-2.3, P11-4.2a and P11-7.1 are already merged | 9 |
| **OQ-28 (c)** | P11-4.1 → P11-4.3 → P11-6.1 → P11-7.2 → P11-8.1 → P11-9.1 → P11-10.1. P11-2.2, P11-3.1 and P11-3.2 are already merged | 7 |

Ahead of W11 under (b), after the sitting, in parallel with plan 010: P11-2.1 and P11-2.3 (nothing more from plan 010) →
P11-7.1; P11-1.1 (merged after plan 010's S5/S6 branches are pushed) → P11-1.2 (after T4.4, T5.3, T10.3) and P11-4.2a (after
T6.1); P11-2.2 developed, not merged. The feeder chains are P11-5.0 → sitting (done), P11-4.2a → P11-4.2b → P11-4.3, P11-4.1 →
P11-4.3, and P11-2.3 → P11-7.1 → P11-7.2. **P11-6.2 now hangs off P11-3.1, not P11-3.2**, so it is off the critical path.
Path 2 (MW5, after MW4) is **off** the critical path. **Not moved, and why:** P11-3.1 reads `metric_definition` (so it waits for
P11-2.2, hence `V014`); P11-4.1 edits `ConfigRecordServiceImpl` (T9.4 is its last plan 010 owner); P11-4.3 needs the
`restart-observed` journal (T6.5), the poller pattern (T7.2) and P11-4.1; P11-6.1 needs P11-4.3's restart-observed query;
P11-9.1 needs T12.1.
No unscheduled plan is on it: plan 008 renumbers above plan 011 ([Migration allocation](#migration-and-id-allocation)).

**Task count: 22** (P11-4.2 is delivered as two merge points, P11-4.2a and P11-4.2b; P11-0.1, 0.2, 1.1, 1.2, 2.1, 2.2, 2.3, 3.1, 3.2, 4.1, 4.2, 4.3, 4.4, 5.0, 5.1, 6.1, 6.2, 7.1, 7.2,
8.1, 9.1, 10.1), plus a conditional **P11-5.2** (OQ-14 ruled (a), `OR-95`): a stub below, opened only once open items 32/33/35 are closed.

---

## Per-task gates — which plan 010 merge each task waits for, and why

Added by the pre-sitting review (2026-10-05). Read it with **OQ-28**, **ruled (b) by `OR-93`** (2026-10-05). `OR-91` relaxed the
rulings' closing line (*"execute after plan 010 S6/S7"*) for MW0 only, so **under OQ-28 (a) (not taken) the last column is the gate**. Under the ruled (b) the middle columns are. "Opens" means the owner sitting has been held with that
task's questions answered, and OQ-28 has been ruled. "Merge after" means the task may be **developed** in its own worktree
off the pushed head but the integrator merges it only after the named plan 010 merge. "Pushed" means on
`integration/dp-remote-demo`. Plan 010 state is as of `bd802fc` plus the unpushed local branches `p010/merge3`, `p010/t53`,
`p010/s6`, `p010/t61` (read-only, 2026-10-05; the [dependency table](#plan-010-dependencies--exact) has the commits).

| Task | Opens when (plan 011 side) | Still waits for in plan 010, and why | Gate before this review |
|---|---|---|---|
| **P11-1.1** | The sitting (OQ-1, OQ-2, OQ-5, OQ-7, OQ-9, OQ-15, OQ-24, OQ-29; MF26) and OQ-28 (b) | **Merge after plan 010's in-flight S5 and S6 branches are pushed** (T5.3 on `p010/merge3`/`p010/t53`; T6.1 and T6.2 on `p010/s6`/`p010/t61`). Why: their ITs (`DesignerEndpointsIT`, `DesignerControllerTest`, the declare ITs) load the Cache fixture and `PackageModel`, and this task changes both (additive: `restartSignal`, `queryParams`, `defaultChart`), so merging first would turn plan 010's slice-review runs red for a reason that is not theirs. **Nothing else:** T0.1a (the last schema owner) and T3.1 (the Java validator) are landed, and no remaining plan 010 task owns `solution-package.schema.json`, `contracts/validation/**`, `SemanticGates` or `PackageModel`. Cost: plan 010's later ITs (T6.3 onward) run against the new fixture | W11 |
| **P11-1.2** | P11-1.1 (same agent), OQ-16, OQ-17, OQ-24, and OQ-28 (b) | **Merge after T4.4 and T5.3 are pushed** (it edits T4.4's `DeclareInstanceRequest.Placement` and rebases onto T5.3's `console.yaml` hunk, both built on `p010/merge3`) **and after T10.3 has merged** (W10, **docs only**). Why T10.3: plan 010's single-owner table makes it the only `console.yaml` editor after S0 (conditional, *"only if D-ACCEPT needs a change beyond point 18"*), and `console.yaml` is single-writer. **Not W11:** T9.x, T10.4, T10.5 and T11.2 are listed as owning no `console.yaml` change, and the inventory's "served" rows are T12.1's (this task's inventory hunk is disjoint, Step 5). The owner's own M-Q14 constraint is only that the `console.yaml` change *merges after plan 010 T0.1's edits* (verbatim record), long satisfied. If the integrator dispatches T10.3 early (docs only, no dependency), this task is not held behind S7. Cost: any later plan 010 task that edits `ErrorCode.java`, `SchemaMirrorLockTest` or `console.yaml` response blocks (T5.3 already did, +3 lines) rebases onto this task | W11 |
| **P11-2.1** | The sitting (OQ-10, OQ-11, OQ-12, OQ-15, OQ-20, OQ-21, OQ-22, OQ-27) and OQ-28 (b) | **Nothing beyond S2, which is pushed** (T2.1 and T2.4 landed; `4267c1e` closed the S2/S3 review). Nothing after T2.4 owns `sm_data_phase0.sql` or `SmDataMigrationMain.java` (plan 010 single-owner table), and its migrations are in its own Flyway history (`sm_metrics`), so there is no number contention. The shared file is `SmDataBootstrapIT`: plan 010's later migration tasks (T6.8 `V011`, T7.2 `V012`, T9.3 `V013`, T11.2 `V014`) move its exact sets, and **whichever merges later rebases**. **Cost to plan 010:** from this task's merge, every plan 010 launcher IT also migrates `sm_metrics` (`SmDataMigrationMain.runAll`) and runs its readiness check, so a red `sm_metrics` migration is a red plan 010 build | W11 |
| **P11-2.3** | The sitting and OQ-28 (b) | **Nothing beyond S2** (T2.0, `V013`, landed). Plan 010 allocates no `sm_control` number after `V013` (`010-…md:388-389`), so `V014` has no contender. Shared files (`CHECKSUMS.sha256`, the readiness lists, `BuiltInRoleSeedIT`) are T2.0's, landed. `sm_control` is frozen once published (`OR-22`): the anchors follow-up commit comes after push, and if any other `sm_control` migration lands first the integrator re-allocates | W11 |
| **P11-7.1** | P11-2.3 merged; the sitting (OQ-7, OQ-8, OQ-18); OQ-28 (b) | **Nothing beyond S3** (T3.2 landed). It takes its declared-metric inputs as data (`DefaultLayerInput`) and reads neither P11-1.1's `PackageModel` fields nor P11-6.1's service, so its `defaultChart=false` test plants a document until P11-1.1 has merged (the Cache-fixture assertion is P11-7.2's IT). No plan 010 task adds an `AuditLog` bean (`010-…md` has no match), so the first production `AuditLog` bean has no contender | W11 (MW7) |
| **P11-2.2** | Developed after P11-1.1 (the `source` is opaque `jsonb`) and P11-2.1's merge; **merged** only as the next column says | **Merge after T11.2 (`V014`, W11)**, unless OQ-28 (c). Why: `V015` must sit above `V011` (T6.8), `V012` (T7.2), `V013` (T9.3, conditional) and `V014` (T11.2); Flyway applies by version, and a persistent dev `sm_data` that already applied `V011`+ refuses a lower number, so plan 010's version-order rule (`010-…md:391-395`) says merge in version order. Under (c) it merges first and the integrator recreates the dev `sm_data` and records it | W11 |
| **P11-3.1 → P11-3.2** | P11-2.1 and P11-2.2 merged | Through P11-2.2 only (so T11.2): P11-3.1's `ingest` reads `metric_definition` and the `placement` rows (rule 1), so it cannot start before `V015`. No other plan 010 gate | W11 |
| **P11-4.1** | P11-1.2 and P11-2.2 merged | **Plan 010 W11 whole.** T9.4 is `ConfigRecordServiceImpl`'s last plan 010 owner (T8.1 → T10.2 → T9.4), and the restore and accept generation mints (T10.2, T10.4) and `copyForward` (T8.1) must be classified in Step 0 before the active-list write is placed. `DeclareInstanceCommand` and `InventoryService` are plan 010 files through T4.4 | W11 |
| **P11-4.2a** (`HostMetricProbe` command builders, `JsonPointerExtractor`, `MetricFetcher`/`FetchRequest`/`FetchResult`, `DirectHttpFetcher`) | P11-1.1 merged; OQ-1; MF10; OQ-28 (b) | **T6.1 pushed** (the `HostProbe` pattern, and M19 filled in its Step 0). **Not T7.1:** these classes never open an SSH session. T2.7 (`TargetConnectionRows`) is landed | W10 (T6.1, T7.1, T6.8 Step 0) |
| **P11-4.2b** (`SshMetricFetcher`, the restart-token reading) | P11-4.2a merged | **T7.1** (`SshSessionPool`, M12, M8) and **T6.8 Step 0** (M18, docker `StartedAt` semantics), because it execs on the pooled session | W10 |
| **P11-4.3 → P11-4.4** | P11-3.2, P11-4.1, P11-4.2b merged; OQ-6, OQ-13 | **T6.5** (the `restart-observed` journal; its `ConfigLadderReader` is T5.3's, extended), **T7.2** (the poller pattern, and MF6/MF7 are measured over T7.1's pool), and T6.6's target for P11-4.4 | W10, W11 |
| **P11-5.1** | MW4 merged; P11-1.1 and P11-2.2 merged; OQ-3, OQ-4, OQ-5, OQ-6, OQ-15, OQ-20, OQ-26, OQ-29; MF26 | Through P11-4.3 | W10, W11 |
| **P11-6.1** | P11-3.2 and P11-4.3 merged | Through P11-4.3 (`JdbcRestartObservedGenerationRepository` backs `activeMetrics`) | W10, W11 |
| **P11-6.2** | **P11-3.1** merged (the `MetricsDbBudget`; P11-2.1's tables), **not** P11-3.2 | Through P11-3.1 → P11-2.2 → T11.2 only. Its repository reads `metrics_raw` and `series`, and `ExportStreamingMeasurementIT` generates its own rows, so the rollups are not an input | W11 |
| **P11-7.2** | P11-1.2, P11-4.1, P11-6.1, P11-6.2, P11-7.1 merged | T1.3 is landed; **T6.7** for `ApplyPathBoundaryTest` | W11 |
| **P11-8.1** | MW2–MW7 merged; owner confirms PostgreSQL on the host | none new | W11 |
| **P11-9.1** | MW7, MW8 merged | **T12.1** (plan 010's parity checklist) | W12 |
| **P11-10.1** | all | none new | W12 |

**What each early task costs plan 010** (stated so the integrator can refuse it): P11-2.1 adds a second Flyway run and a
readiness check to every plan 010 launcher IT from its merge; P11-1.1 changes the Cache fixture every later plan 010 E2E
loads; P11-1.2 changes `console.yaml`, `ErrorCode.java` and `SchemaMirrorLockTest`, which any later plan 010 task that
touches them rebases onto. Each is a rebase, never a design change to plan 010, and none edits a plan 010 file's meaning.

---

## File structure (new code)

| Path | Responsibility |
|---|---|
| `dp-api/.../dp/api/metrics/ingest/*` | `TelemetryIngestPort`, `IngestBatch`, `IngestSample`, `IngestResult` (+ `Committed`/`RetryAfter`/`Rejected`/`Pending`), `SourceDiscriminator`, `RestartDetection`, `GapMarker`, `CollectorReason` |
| `dp-api/.../dp/api/metrics/declare/*` | `MetricsDeclaration`, `MetricDefinitionSpec`, `RestartSignalSpec`, `PackageVersionMismatchException`, `MetricsPortCollisionException` |
| `dp-api/.../dp/api/metrics/query/*` | `MetricsQueryService`, `RangeQuery`, `RangeResult`, `SeriesView`, `QueryRefusal`, `MetricsQueryRefusedException`, `ObservedMetric`, `CollectorHealthView` |
| `dp-api/.../dp/api/metrics/export/*` | `MetricsExportService`, `ExportCommand`, `ExportPlan`, `ExportBusyException` |
| `dp-core/.../dp/metrics/ingest/*` | `IngestService` (the port), `SeriesResolver`, `CounterSemantics`, `IngestBuffer`, `IngestWriter` |
| `dp-core/.../dp/metrics/rollup/*` | `RollupService`, `PartitionRunway` |
| `dp-core/.../dp/metrics/collect/*` | `MetricsBackgroundLoop`, `MetricsCollector`, `CollectionPlanBuilder`, `SourceScheduler`, `JsonPointerExtractor` |
| `dp-core/.../dp/metrics/collect/fetch/*` | `MetricFetcher`, `DirectHttpFetcher`, `SshMetricFetcher`, `FetchResult` |
| `dp-core/.../dp/metrics/push/*` | `BrokerSampleDecoder`, `PushIngestService` |
| `dp-core/.../dp/metrics/query/*` · `…/export/*` | `MetricsQueryServiceImpl`, `TierRouter`, `Aggregator`, `GapDeriver` · `MetricsExportServiceImpl`, `CsvEncoder` |
| `dp-core/.../dp/metrics/MetricsConfiguration.java` | the **shared** metrics beans only (`MetricsDbBudget`, `metricsExecutor`), P11-3.1; every area's own configurations are listed in [Readiness and configuration](#readiness-and-configuration-per-area) |
| `dp-core/.../dp/metrics/MetricsDbBudget.java` | the **one** query/export connection budget (M-Q22 (6)): a fair semaphore, default 4, configurable, one bean (P11-3.1) |
| `dp-core/.../dp/apply/persistence/*PersistenceConfiguration.java` · `dp-core/.../dp/metrics/**/Metrics*Configuration.java` · `cp-core/.../cp/{persistence/DashboardPersistenceConfiguration,dashboard/DashboardConfiguration,audit/ExportAuditConfiguration,designer/MetricApplicabilityConfiguration}.java` | one pair per area (JDBC confinement), each with one `@Import` line |
| `dp-core/.../dp/metrics/rollup/MetricsDiskAlarm.java` · `…/apply/persistence/JdbcMetricsDiskRepository.java` | the disk-usage alarm (M-Q19, OQ-16), P11-3.2 |
| `dp-core/.../dp/apply/persistence/JdbcRestartObservedGenerationRepository.java` | the newest generation that reached `restart-observed`, per instance (P11-4.3) |
| `console-gateway/.../gateway/http/metrics/DashboardResourceResolver.java` | `dashboardId` → the instance `ResourceRef`, from the path only (P11-7.2) |
| `dp-core/.../dp/apply/runtime/ssh/HostMetricProbe.java` | the fixed metrics curl and docker-inspect command builders (the `HostProbe` sibling, SM-D11a) |
| `dp-core/.../dp/meshbridge/ingest/*` | `BrokerSubscriptionManager`, `JcsmpSampleSubscriber`: JCSMP stays in meshbridge (`onlyMeshbridgeTouchesJcsmp`), and bytes, never a mapper, cross out (`meshbridgeHoldsNoOwnMapper`) |
| `dp-core/.../dp/apply/persistence/{SmMetricsMigrator,MetricsPersistenceConfiguration,JdbcSeriesRepository,JdbcRawSampleRepository,JdbcRollupRepository,JdbcIngestCounterRepository,JdbcRestartMarkerRepository,JdbcPartitionRunwayRepository,JdbcMetricDefinitionRepository,JdbcMetricsQueryRepository,JdbcMetricsExportRepository,MetricsPartitionMain,BrokerConnectionMain}.java` | every JDBC type (JDBC confinement, `PlaneBoundaryTest.java:128-134`) |
| `cp-api/.../cp/api/designer/MetricApplicability.java` | the `when:` evaluation for metrics (M-Q15) |
| `cp-api/.../cp/api/dashboard/*` · `cp-core/.../cp/dashboard/*` · `cp-core/.../cp/persistence/JdbcDashboardRepository.java` | dashboards (M-Q10, M-Q23) |
| `cp-api/.../cp/api/audit/ExportAudit.java` · `cp-core/.../cp/audit/AuditLogExportAudit.java` | the HIGH-consequence export audit row (M-Q22 (7)), over the existing `AuditLog`/`JdbcAuditLog` |
| `console-gateway/.../gateway/http/metrics/*` | `CapabilitiesController`, `MetricsCatalogController`, `QueryController`, `ExportController`, `DashboardController`, request/response records, `MetricsRateGate` |

---

## MW0 — Records and review

### Task P11-0.1 — Records: plan 011's side of the rulings (pointers, plan 009 superseded, plan 008 renumbered), REQ-010 cited, the integrator hand-off

**Status: EXECUTED 2026-10-05**, `a521378` (Steps 1–3, 5 and 6). **Step 4b is still open, and narrowed:** the owner sitting was held 2026-10-05 and the integrator registered `OR-92`…`OR-95` in `DECISIONS.md` directly; what remains is the `OR-77` clarifying row (OQ-21 confirmed, `OR-92`), which is not drafted yet, and the per-answer record is now `OR-92`…`OR-95` themselves.
**Results:** plan 009 header, plan 007/008 pointers, two `console-integration.md` pointers, one pointer each in `demo-delivery-sequence.md` and `traceability.md`, and `docs/tasks/plan-011-mw0-integrator-handoff.md`; `REQ-010` cited, not created; links resolved; docs only.

**Wave:** MW0, first. **Depends on:** nothing (`OR-91`: MW0 is docs only and may run now, in parallel with plan 010).
**Parallel:** ⛔ none (P11-0.2 follows). **Docs only:** this task edits no code, no migration, no contract, no pom and **no
plan 010 file**.

**What the 2026-10-05 refresh removed from this task (each measured against `bd802fc`, none is a duty any more):**
the rulings are **registered** (`OR-69`, `OR-71`…`OR-81`; plan 010's S0-records task wrote them, and `OR-82`…`OR-91` followed);
**`REQ-010` exists** (`docs/requirements/REQ-010-per-instance-dashboards-and-comparison.md`; its `README.md` row at `:82` and
`traceability.md` rows `:24`, `:44`, `:45` are written), so nothing is created; the **`D10` approval's forward pointer
for M-Q16 is already written** (`D10-G2-APPROVAL-2026-09-15.md:266`, limitation 4; `OR-80` says so) and the role-table
pointer is not a plan 011 duty (`OR-80` states the 19 → 21 change itself); **plan 010 already carries the `OR-72` wording**
(Global Constraints `I5`, `010-…md:59-68`; T7.1, `:3719`; `TenantEnumerationRepository`'s javadoc, `:69-83`), so no `OR-49` /
T7.1 hand-off remains; and the pin-test drift note is moot ([Prerequisite 7](#prerequisites)).

**Files:**
- Modify `docs/plans/backend/009-console-api-and-monitoring-replacement.md`: a dated **SUPERSEDED-in-part** header linking
  the [replacement table](#relationship-to-plan-009--exactly-what-is-replaced). The body is unedited.
- Modify `docs/plans/backend/007-production-api-and-event-driven-walking-skeleton.md`: Task 13b and the four other places that
  name plan 009 as the owner of the replacement (`:1424`, `:1437`, `:1468`, `:1480-1481`, `:1619`) each gain a dated pointer
  beside the link, text unedited.
- Modify `docs/plans/backend/008-placement-registry-and-apply-journal-identity.md`: **a dated header note only**, beside plan
  010 T0.3's (which stays): *"plan 011 holds `sm_data` V015 and reserves V016; this plan's migrations start at **V017**"*
  (`V008__intent_identity` → V017, `V009__placement_referential_guard` → V018). T0.3's `V015`-onward note is corrected by the
  new line, never deleted.
- Modify `docs/design/common/contracts/console-integration.md`: **two forward pointers**, no text rewritten. (1) At the
  "Who holds it" bullet (`:311-314`, *"Operator (19). Everything outside Operator's 19 is Administrator-only"*): superseded in
  part by `OR-80` (the Operator also holds `metrics.export` and `metrics.forceRaw`, 21). `OR-80` records this pointer as
  *"not yet written … no uncommitted plan owns it"*; this task is that owner. (2) At §5.4's opening paragraph (`:388-391`,
  which names `console-api` and plan 009): the routes are hosted by `console-gateway` (`OR-78`, M-Q11) and the replacement is
  plan 011.
- Modify `docs/tasks/demo-delivery-sequence.md` (`:31-32`, point 5: *"`demo_control` cannot be dropped until plan 009 lands"*)
  and `docs/requirements/traceability.md` (`:17`, the `console-api` row: *"it is plan 009's"*): one dated pointer each.
- Create `docs/tasks/plan-011-mw0-integrator-handoff.md`: **the integrator hand-off.** `docs/design/DECISIONS.md` is the
  integrator's file (single-owner table), so this task drafts and does not edit it. It carries (a) the draft rows and text
  below, (b) the plan 010 wording plan 011 needs changed (plan 010 is read-only for this task), and (c) the next free ids at
  `bd802fc` (`OR-92`, `BE-D34`), which the integrator re-checks.
- ⛔ **Not in this task:** `docs/design/DECISIONS.md`, any plan 010 file, `TenantEnumerationRepository.java`,
  `docs/design/D10-G2-APPROVAL-2026-09-15.md` (its pointer exists), `frontend/**`.

- [x] **Step 1: REQ-010 is cited, not created.** Read it against `OR-78` / M-Q23 and confirm: source line *"Project owner …
  2026-09-30 — monitoring ruling M-Q23 … Recorded as `OR-78`"*; acceptance is **exactly the three elements the owner stated**
  (per-instance shared dashboards; line, gauge and single-stat; one metric compared across instances, an ad-hoc query, not
  saved); **no fourth criterion** (the package-default layer that can be hidden is `SM-D31` PROPOSED and the demo's shape, and
  whether it should be a criterion is **OQ-18**). Record the check and the row numbers in the commit. No edit unless a
  mismatch is found, and a mismatch is a finding for the owner, never a silent rewrite.
- [x] **Step 2: The dated pointers** (Files above). Wording, each beginning *"⚠️ 2026-10-05 (plan 011, a DRAFT, not approved)"*
  and saying what is superseded, by which ruling, and that the text is unedited (`I3`). Plan 009's header lists exactly the
  [replacement table](#relationship-to-plan-009--exactly-what-is-replaced): Tasks 1 and 2 dropped (`OR-78`), Tasks 3 and 4
  replaced, the Administrator-only constraint inverted (`OR-80`), Tasks 5 and 6 replaced by P11-9.1 and P11-10.1.
- [x] **Step 3: The integrator hand-off file** (drafts, not applied):
  1. **A new open item, "tenant erasure on demand"** (discovery default D-17, `OR-76`'s "common" list): offboarding a tenant
     needs a `tenant_id` erasure across `sm_data`, `sm_metrics` and the CP; nothing is deleted on retire; no operator command
     exists. Kind: GDPR-style offboarding, not v1-blocking. (Draft row.)
  2. **Open item 25 narrowed** by `OR-78` (the dashboard document shape is adopted; out of v1: server-side Explore drafts, saved
     comparison widgets, templating) and `partial.unavailable[]` by `OR-79`. (Draft text; the rest of item 25 stays.)
  3. **Open item 27(b) flagged** by `OR-79` (daily tier day boundary UTC for **stored data**; display timezone stays the
     frontend's). **Open item 28 narrowed, not closed** by `OR-79` (query rate limit 10/s per session, export
     HIGH-consequence audited, 1 export per organization and 2 global, 20 M rows, `statement_timeout` 30 s).
  4. **Open item 3 narrowed to "events"** (discovery default D-8) **only if OQ-22 accepts D-8**; otherwise untouched.
  5. **Plan 010 wording plan 011 needs changed** (read-only here): the five mentions of plan 009 listed in the
     [Relationship section](#relationship-to-plan-009--exactly-what-is-replaced) (`010-…md:42`, `:164`, `:4004`, `:4122`,
     `:4193`); `010-…md:159`'s `backend/pom.xml:73` (the `demo-launcher` module line is `:85` at `bd802fc`); and the
     consequence of `OR-89` for host-port collisions across organizations (**OQ-23**), which is plan 010's health port too.
  6. **Already done, so absent from the hand-off** (kept here so a reader does not re-raise them): the M-Q7 flag against
     `dynamic-metrics/design.md:75,158` (it is in `OR-75`'s right-hand column); the `OR-M-Q9` / SM-D30 reconciliation
     (`OR-75` records that guard 2 fires only where a restart signal exists, and omits the ruling's *"reuse health inspect
     when fresh"* parenthetical, so there is nothing to reconcile); the `OR-49` and T7.1 wording.
- [x] **Step 4b — done by the integrator 2026-10-05 on `p011/merge`: `OR-98` (the `OR-74` clarification, hand-off A7) and `OR-99` (the `OR-77` clarification, OQ-21), and no per-answer row beyond `OR-92`…`OR-95`, which already carry each answer. Original text (after the owner sitting; the sitting was held 2026-10-05, `OR-92`…`OR-95`; the OQ-21 `OR-77` clarifying row and the OQ-22 record are the part still to draft): the post-sitting hand-off.** Append to the same file: (i) a **clarifying row for
  `OR-77`** (`I3`: a new row, `OR-77` unedited) drafted from OQ-21's answer: the function's schema as confirmed
  (`sm_metrics`, not the ruling's `sm_data`), the grammar as ruled (`metrics_raw_YYYY_MM` exactly under (2a)), and **both**
  sanctioned identifier cases (the function's clock-derived names; `MetricsPartitionMain`'s argument-checked,
  catalog-resolved month); (ii) one draft row per answered OQ, each citing the owner's words; (iii) the OQ-22 answer (the
  discovery defaults accepted, or which were not). Docs only; the same verify as Step 5. Commit `docs: plan 011 post-sitting
  record drafts (OQ answers, OR-77 clarification)`.
- [x] **Step 5: Verify.** Every markdown link in the touched docs resolves (a `python3` check over the touched files: every
  relative link and anchor target exists). `git diff --cached --name-only` lists docs only, and none of `DECISIONS.md`, a plan
  010 file or `frontend/`. `git diff --cached --check` is clean.
- [x] **Step 6: Commit.** `docs: plan 011 records — plan 009 superseded in part, plan 008 renumbered above plan 011, pointers for OR-78 and OR-80, integrator hand-off`

### Task P11-0.2 — The DM design's adversarial review (M-Q1), and the docs hygiene the rulings require

**Status: EXECUTED 2026-10-05**, `7f8a6dc` (all five steps). It drafted two owner questions, **OQ-26** and **OQ-27**, now rows of the owner-question table, **both ruled at the 2026-10-05 sitting** (OQ-26 `OR-94`, OQ-27 `OR-92`).
**Results:** `docs/tasks/dm-design-review-2026-10.md`, 34 findings (DMR-1…DMR-34), each with its own disposition; dated pointers in `dynamic-metrics/design.md` and `architecture.md` §8.4; links resolved. Not "all closed": per finding.

**Wave:** MW0, after P11-0.1. **Parallel:** ⛔ none. **Model:** the review is Opus 5.5 high, read-only; the fixes are
Sonnet 5.5 high.

**Files:** Create `docs/tasks/dm-design-review-2026-10.md` (findings + dispositions); Modify
`docs/design/dynamic-metrics/design.md`; `docs/design/architecture.md` §8.4 (the export sentence only).

- [x] **Step 1: Review** `dynamic-metrics/design.md` against the rulings, for defects the rulings do not already
  settle. It checks, specifically, that nothing contradicts M-Q5/M-Q7/M-Q17/M-Q18/M-Q20/M-Q23. Record the findings
  with file:line.
- [x] **Step 2: Apply the ruled corrections** (each is a pointer to the ruling, never a silent rewrite):
  - §4.2 / `:75,158`: "V001 is the source of truth" → superseded by M-Q7 `OR-75` (identity) and discovery default D-8 (V001 deleted; only if OQ-22 accepts it).
  - §4.4 / §5.7: "average everything" across replicas → M-Q20 `OR-75`.
  - §5.8 row "Some instances unreachable/unauthorized" (`:425`) → M-Q17 `OR-79`.
  - §5.8 `:415-418` and `architecture.md` §8.4's "Raw export is a streaming server-side `COPY … TO STDOUT`" → a
    bound streaming `SELECT` (M-Q18 `OR-79`). The **archive** `COPY` of a detached partition (§8.5) stays, and is
    identifier-only.
  - `design.md:14` "DM-D1…DM-D20" → cite the family prefix (`I3`).
  - The export verb: `POST` (`:412`) → `GET /export/range` (discovery default D-12, and console-integration row 60,
    which is the contract either way).
- [x] **Step 3: Triage.** Every finding is fixed, routed to an OQ row, or disputed with evidence. **Never claim "all
  closed"; report per finding.**
- [x] **Step 4: Verify.** Links resolve.
- [x] **Step 5: Commit.** `docs(dynamic-metrics): adversarial review, and the corrections the monitoring rulings require (M-Q1)`

**Then the owner sitting** — ✅ **held 2026-10-05** (`OR-92`…`OR-95`; 25 of 29 ruled, OQ-3/5/20/29 deferred until MF26) — once P11-0.2 **and P11-5.0** had merged (**both had**, on `p011/mw0`): every open OQ, **OQ-1…OQ-18 and OQ-20…OQ-29**
(OQ-19 is ruled, `OR-91`; OQ-23…OQ-25 came from the refresh, OQ-26 and OQ-27 from P11-0.2, OQ-28 and OQ-29 from the pre-sitting review),
including OQ-5, OQ-15, OQ-20 and OQ-29 (with P11-5.0's facts; OQ-5, OQ-20 and OQ-29 are ruled together, and not before MF26 is
measured), the confirm-only OQ-21 and the accept-all OQ-22. One by one, with scenarios;
recommendations shown, never bare options. The answers are drafted by P11-0.1 Step 4b and recorded by the integrator
as new `OR` rows (`OR-92` onward at `bd802fc`), and each gated task is updated before it starts.

---

## MW1 — Contracts

### Task P11-1.1 — The package schema: a per-kind `source` (M-Q4), the restart signal (M-Q9), `defaultChart` (OQ-7)

> **Owner rulings applied (`OR-92`, `OR-93`, `OR-95`, 2026-10-05).** **OQ-1 (a):** an optional `restartSignal` per **component**
> (`{kind: http_json, path, port, pointer}`), used in direct mode; SSH mode needs nothing from the package. **OQ-2 (b):** the
> **agent branch is not in this change** (it lands additively with the agent-gateway plan); ⚠️ amends M-Q4 (c)'s "now". **OQ-7 (b):**
> `defaultChart` on `$defs/metric`. **OQ-9 (a):** `intervalSeconds` `minimum: 10`, `maximum: 3600`, DP default 30 s. **OQ-24 (b):**
> `port` stays optional; `engine-edge` and the two frontend files are not edited (Step 0b still runs read-only). **OQ-28 (b), `OR-93`:** built
> now, **merged only after plan 010's in-flight S5/S6 branches are pushed**; local until its wave review. **OQ-5 and OQ-29 stay
> OPEN (`OR-95`, deferred until MF26):** so this task does **not** land the `solace_event` branch, its gate 16 (`instanceKey` not secret),
> its topic-grammar negatives or its fixture metric; that is **P11-5.1's own reviewed contract change**, per this task's text below
> ("Nothing is landed provisionally"). OQ-15 is ruled but lands in P11-2.1/P11-3.1, not here. This task no longer waits for MF26.

**Wave:** MW1. **Depends on:** plan 010 T0.1a (the last plan 010 schema owner; ✅ landed) and **T3.1** (the Java
validator this change must keep at parity; ✅ landed), MW0 (including P11-5.0), the sitting (OQ-1, OQ-2, OQ-5, OQ-7,
OQ-9, OQ-15, **OQ-24**, **OQ-29**), MF26 (OQ-5 is not ruled without it) and **OQ-28 (b)**. **Merge gate:** plan 010's
in-flight S5 and S6 branches pushed, because their ITs load the Cache fixture and `PackageModel` this task changes ([per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why));
under OQ-28 (a): plan 010 W11. **Parallel:** ⛔ none (P11-1.2 follows in the same agent).

**Files:** Modify `contracts/solution-package.schema.json` (`$defs/source` at `:586-629`, `$defs/metric` at
`:549-585`, and the component `$def` for `restartSignal`), `contracts/validation/validate-package.py`,
`contracts/validation/fixtures/cache-2.4.package.json` (`:409-507`), `contracts/validation/negative/generate.py`
(`CASES`) and the regenerated negatives, `contracts/run-checks.sh` steps 1–3 only if a new step is needed;
`contracts/view-models/src/types.ts` only if affected; `docs/design/dynamic-ui/design.md` §6.4 (**a dated pointer only**, Step 3). **Measured 2026-10-05, and a correction to the first draft:**
`contracts/validation/fixtures/engine-edge.package.json:44` is the **only** root fixture whose `http_json` source has no
`port` (`run-checks.sh:135` validates it), and **both overlap fixtures** (`validation/fixtures/overlap/cache-2.0.package.json`,
`…/overlap/cache-2.4.package.json`, `run-checks.sh:145`) **already carry `port` on all five of their `http_json` sources**
(six `"port"` each, with the health probe), as does the Cache fixture, so they need **no edit** for a `port` rule. Under
**OQ-24 (b)** (the recommendation, written for below) `port` stays optional and `engine-edge` is not edited either; under
OQ-24 (a) it gains `"port": 8080`, a synthetic value, labelled as such in the commit, and the two frontend files OQ-24
names need an edit that this plan may not make.

**Two copies of the schema outside `contracts/` (measured; the draft missed both).**
- `backend/cp-core/src/main/resources/contracts/solution-package.schema.json` is a **byte-identical classpath copy**
  (`CatalogConfiguration.SCHEMA_RESOURCE`), pinned by `PackageSchemaMirrorLockTest`, whose own failure message gives the
  command: `cp contracts/solution-package.schema.json backend/cp-core/src/main/resources/contracts/`. Modify it in this
  task, in the same commit, or `VERIFY cp-core` is red.
- `frontend/contracts/solution-package/validate.ts:12` **imports the root schema directly** and compiles it with Ajv
  `strict: true` (`strictRequired: false`, `allowUnionTypes: true`), and `frontend/contracts/packages/{cache,db-designer}/package.json`
  plus `frontend/src/app/dev/testPackage.ts` are validated against it by the frontend's tests. This plan changes nothing
  in `frontend/` (owner standing rule), so **Step 0b measures** whether the changed schema keeps those tests green, and a
  red result **stops** the task and goes to the owner (OQ-24).

**The Java side of the same contract change (unconditional).** Plan 010 T3.1 holds a Java validator at parity with the
Python (`NegativeFixtureParityTest.theValidatorAndThePythonGateAgreeOnEveryNegative` / `…EveryPositive`, `:45`, `:55`;
`OverlapParityTest`, `:51`; plan 010 T3.1, `010-…md:2401-2452`), so a Python-only gate turns its parity test red:
- `backend/cp-core/src/main/java/com/solace/sm/cp/catalog/validation/SemanticGates.java`: the two new gates, **15**
  (metric names unique across a package's components) and **16** (an `instanceKey` config field may not be a secret),
  with the same gate labels the Python prints;
- `backend/cp-core/src/test/java/com/solace/sm/cp/catalog/validation/NegativeFixtureParityTest.java`: its negative-count
  pin (`thereAreNineteenNegativesSeventeenMeasuredPlusT01asTwo`, `assertThat(ValidationFixtures.negatives()).hasSize(19)`,
  `:28-31`; the directory holds 19 negatives, `contracts/validation/negative/gate01…gate14*.json`) moves to 19 plus this
  task's negatives, stated as a number in the commit (and the method renamed, because its name states the count);
- `backend/cp-api/src/main/java/com/solace/sm/cp/api/designer/PackageModel.java`: `PackageMetric` gains `Boolean
  defaultChart` (null = true) and `PackageComponent` gains `Map<String, Object> restartSignal`. **Measured:** the reader
  sets `FAIL_ON_UNKNOWN_PROPERTIES=false` (`PackageJsonReader.java:40-41`), and neither record has these components
  today (`PackageModel.java:76-101`), so without this edit both fields parse and are **silently dropped**, and
  P11-4.1 (`MetricDefinitionSpec.defaultChart`, `RestartSignalSpec`) and P11-7.1 (`DefaultLayerDeriver`) would have no
  typed source. The symptom would be direct-mode restart detection silently becoming `inferred`, and `defaultChart:
  false` being ignored;
- `backend/cp-core/src/main/java/com/solace/sm/cp/designer/PackageJsonReader.java` (only if the canonical-constructor
  binding needs a hint; the survival test below decides);
- the two test files that call the changed record constructors (measured by grep:
  `cp-core/src/test/java/com/solace/sm/cp/designer/DesignerStageValidatorTest.java`,
  `cp-core/src/test/java/com/solace/sm/cp/designer/DesignerProjectorRulesTest.java`): each call gains the new
  argument (`null`). No secondary constructor is added: a second constructor on a Jackson-bound record is a
  deserialization-choice risk;
- Create `backend/cp-core/src/test/java/com/solace/sm/cp/designer/PackageJsonReaderMetricsFieldsTest.java`.

**The shape (for OQ-2 (b); OQ-1 (a); OQ-7 (b); OQ-9 (a)):**

```jsonc
"source": {
  "type": "object",
  "required": ["kind", "onUnavailable"],
  "oneOf": [
    { "$ref": "#/$defs/sourceHttpJson" },     // path 1 (poll)
    { "$ref": "#/$defs/sourceSolaceEvent" },  // path 2 (push)
    { "$ref": "#/$defs/sourceUncollected" }   // prometheus | exec | jdbc_query: admitted, not collected in v1. It KEEPS every property
                                              // $defs/source has today (path, port, pointer, topicPattern, query, command): measured,
                                              // frontend/contracts/packages/db-designer/package.json has a jdbc_query source with a pointer
  ]
},
"sourceHttpJson": {
  "type": "object", "additionalProperties": false,
  "required": ["kind", "path", "onUnavailable"],                              // OQ-24 (b): port optional. Under (a): add "port"
  "properties": {
    "kind": { "const": "http_json" },
    "path": { "type": "string", "pattern": "^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])" },          // HostProbe's grammar (plan 010 T6.1)
    "port": { "type": "integer", "minimum": 1, "maximum": 65535 },                 // the CONTAINER port; host side from metricsPorts (M-Q14). Absent: not collectable (OQ-24 (b))
    "pointer": { "type": "string", "pattern": "^(/[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])" },  // absent = the whole body is the number
    "queryParams": {                                                               // M-Q4: the closed grammar, keys AND values
      "type": "object", "maxProperties": 8,
      "propertyNames": { "pattern": "^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])" },
      "additionalProperties": { "type": "string", "pattern": "^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])" }
    },
    "intervalSeconds": { "type": "integer", "minimum": 10, "maximum": 3600 },      // OQ-9; DP default 30
    "onUnavailable": { "enum": ["degrade", "error"] }
  }
},
"sourceSolaceEvent": {
  "type": "object", "additionalProperties": false,
  "required": ["kind", "topicPattern", "valuePointer", "onUnavailable"],
  "properties": {
    "kind": { "const": "solace_event" },
    "topicPattern": { "type": "string", "pattern": "^(([A-Za-z0-9_.-]{1,64}|\\*|\\{instanceKey\\})/){0,15}[A-Za-z0-9_.-]{1,64}(?![\\s\\S])" },
                      // I6 (OQ-5): the LAST level is a literal: no ">" , "*" or {instanceKey}. A subscription names its states, so a
                      // "start" event (which may carry a plaintext broker password, facts F15/F16) is never delivered to the DP.
                      // One substituted level (OQ-5 (a)/(b)); option (c') would add a second token for the base-topic field.
    "eventFilter": { "type": "object", "additionalProperties": false, "required": ["pointer", "equals"],
                     "properties": { "pointer": { "type": "string", "pattern": "^(/[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])" },
                                     "equals": { "type": "string", "pattern": "^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])" } } },
    "valuePointer": { "type": "string", "pattern": "^(/[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])" },   // required under OQ-29 (a); optional (with a count extraction) only under (b)
    "instanceKey": { "type": "object", "additionalProperties": false, "required": ["configField"],   // OQ-5 (a)
                     "properties": { "configField": { "type": "string", "pattern": "^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])" } } },
                     // gate 16 (both validators): configField names a non-secret field of this component (I6)
    "onUnavailable": { "enum": ["degrade", "error"] }
  }
},
// component: "restartSignal": { "$ref": "#/$defs/sourceHttpJson" minus intervalSeconds/queryParams } (OQ-1 (a))
// metric: "defaultChart": { "type": "boolean" }  (OQ-7 (b); absent = true)
// metric: "name": { "type": "string", "pattern": "^[a-z][a-z0-9_.]{0,127}(?![\\s\\S])" }   // = P11-2.2's and P11-2.1's DB CHECKs
// metric: "unit": { "type": "string", "minLength": 1, "maxLength": 64 }         // = the DB CHECK length(unit) BETWEEN 1 AND 64
```

The metric `name` and `unit` constraints are added **in this same reviewed change**, so a package cannot load and
then fail at declare against P11-2.2's CHECKs. Measured 2026-09-30 and **re-measured 2026-10-05 over every tracked
`*.json` that declares a metric** (7 files: the Cache fixture, `engine-edge`, both overlap fixtures, and, outside this plan's write
set, the frontend's `contracts/packages/cache`, `contracts/packages/db-designer` and
`contracts/fixtures/view-model/version-scoped-component-excluded.json`): every name and unit already matches both
(`cache.*`, `db.*`, `edge.*`, `otlp.*` names; units `ratio`, `events`, `entries`, …), and `filemesh-ftp-solace` declares no
metrics. The view-model fixtures under `contracts/view-models/fixtures/**` carry no
`source` block, and `project()` ignores `source`, so they stay untouched (OR-34's lock).

⚠️ **The `sourceSolaceEvent` branch lands once, in the shape OQ-5 ruled.** P11-5.0 runs in MW0 and OQ-5 is asked at
the main sitting, so this task has the ruling before it writes the branch. The shape above is OQ-5 (a)'s topic form and OQ-29 (a)'s
extraction (`valuePointer` required), with the `instanceKey` declaration `{"instanceKey": {"configField": "<key>"}}` on the
source; a different ruling (OQ-5 (c′)'s second token, OQ-29 (b)'s count extraction, or OQ-29 (c) leaving the branch out) changes
this block before the task starts. **If the sitting did not rule OQ-5**, this task leaves the `solace_event` branch
**out** (the discriminator keeps it additive, as for the agent branch), and P11-5.1 adds it in its own reviewed
contract change. Nothing is landed provisionally. ⚠️ **`source.query` (`:616-618`) keeps its type** inside
`sourceUncollected`, and `queryParams` exists only on `http_json` (the discovery's M-Q4 constraint).

- [ ] **Step 0: Measure MF8's unit and MF9 on EC2** (one `curl` each over SSH). Record them.
- [x] **Step 0b: The frontend consumer (OQ-24), read-only.** Before editing the schema, run the frontend's tests that
  compile it, as the baseline, and again after the change, **without editing anything under `frontend/`**:
  `cd frontend && npm ci && npx vitest run --project contracts` (the `contracts/solution-package` tests) and
  `npx vitest run src/app/dev` (`testPackage.ts:62` declares an `http_json` source with no `port`; five test files use it).
  Record both counts. **Any red caused by the change stops this task** and goes to the owner as OQ-24; it is never fixed by
  editing a frontend file. (If `npm ci` cannot run offline here, say so in the commit and ask the integrator to run it.)
- [x] **Step 0c: Fill MF24** (the new schema keywords and the end anchor), measured in `NegativeFixtureParityTest`'s
  harness and in the Python gate, before any pattern is written. Already measured once on 2026-10-05, to be re-measured here:
  networknt 2.0.8 enforces `propertyNames`, `maxProperties` and `maxLength` and **refuses** `k=v\n` under `^…$`, while
  Python `jsonschema` 4.26.0 `Draft202012Validator` **accepts** one trailing `\n` under `^[A-Za-z0-9_.-]{1,64}$`
  (`'abc\n'` is valid; `'abc\n\n'` is not). So a trailing-newline negative passes gate 1 in the Python and fails in the
  Java, which breaks parity. The end anchor `(?![\s\S])` refused every newline variant in both (measured), so **every new
  pattern in this change ends with it, not with `$`**.
- [x] **Step 1: Verify that the named-gate check still fires** (plan 010 T0.1a's B-1 lesson). The hardening already
  exists: `--expect-fail` asserts each negative's **named gate** through `GATE_NAME_RE` and `expected_gate`
  (`validate-package.py:188-203`). Plant a negative whose file name claims one gate but which fails at another, run
  `./contracts/run-checks.sh --strict`, and see step 2 go red. Remove the plant. If it does **not** go red, stop:
  the check regressed, and that is a finding about the tests (`L22`).
- [x] **Step 2: Write the negatives first** (in `generate.py` `CASES`), and see each rejected **for the wrong reason
  or not at all** on today's schema:
  - `queryParams` key and value each carrying `$(id)`, `&`, `'`, `;`, a backtick, a space, `%`, a **trailing** newline
    (the one case a `$` anchor lets through in the Python, MF24) and an **embedded** newline (18 cases, gate 1);
  - `queryParams` on `solace_event` and on `prometheus`;
  - `intervalSeconds: 5`;
  - `pointer` with `..` or a space;
  - `topicPattern` with `#`, `+` or an empty level, and with a **last level** of `>`, `*` or `{instanceKey}` (three cases;
    `I6`, OQ-5);
  - `source` with both `path` and `topicPattern`;
  - `restartSignal` with `queryParams`;
  - an `http_json` source without `port` (**only under OQ-24 (a)**; under (b) such a source is valid, and `engine-edge`
    stays a positive);
  - a metric `name` of `Cache.Hits`, `1cache`, `cache hits` and 129 characters; a metric `unit` of `""` and of 65
    characters;
  - **`gate15-metric-name-repeated-across-components.json`**: two components each declaring `cache.hits` (the new
    gate 15, its own number and file prefix; gate 9 already covers duplicates **within** one component,
    `validate-package.py:93-99`, `gate09-duplicate-metric-name.json`);
  - **`gate16-instancekey-names-a-secret-field.json`** and **`gate16-instancekey-names-a-secret-flagged-field.json`**
    (only if the `solace_event` branch lands here): an `instanceKey.configField` naming a field of `type: "secret"`
    (schema `:196`), and one naming a field with `secret: true` (`:244`). `I6`: the value would become a broker topic
    subscription.
- [x] **Step 3: Change the schema; update the Cache fixture.** `cache.size` gains `"queryParams":
  {"filterByNamespace": "false"}` (the fixture's literal `/api/cache/size` is a 400, `CacheController.java:61`). The
  component gains `"restartSignal": {"kind": "http_json", "path": "/actuator/metrics/process.start.time", "port":
  8081, "pointer": "/measurements/0/value", "onUnavailable": "degrade"}` (MF8). `cache.hit_rate` gains
  `"defaultChart": false`. Add the two `validate-package.py` gates: **gate 15**, metric names are unique across one
  package's components (M-Q5's key has no component); **gate 16**, an `instanceKey.configField` must name a field of
  the same component that is neither `type: "secret"` nor `secret: true`. Add both to `SemanticGates.java` with the
  same labels, and the two `PackageModel` components. Copy the schema to the cp-core classpath mirror
  (`cp contracts/solution-package.schema.json backend/cp-core/src/main/resources/contracts/`). Edit no overlap fixture
  (they already carry `port`) and no frontend file; `engine-edge` gains `port` only under OQ-24 (a).
  Add a dated pointer (text unedited, `I3`) after `docs/design/dynamic-ui/design.md` §6.4's gate table, which stops at gate 13:
  gates 14 (plan 010 T0.1a), 15 and 16 (this task) exist in `validate-package.py` and `SemanticGates`, which are canonical.
  Test, in `PackageJsonReaderMetricsFieldsTest`: `aDefaultChartFalseAndADeclaredRestartSignalSurviveTheRead()` (read
  the Cache fixture; `cache.hit_rate`'s `defaultChart` is `false`, and the component's `restartSignal` carries the
  path, port and pointer) and `anAbsentDefaultChartReadsAsNull()`.
- [x] **Step 4: Run** `./contracts/run-checks.sh --strict` (every positive passes; every negative fails **at its
  named gate**, and step 3's overlap still fails at gate 3 only). Run `VERIFY cp-core`: plan 010 T3.1's Java
  validator agrees on every file (`NegativeFixtureParityTest.theValidatorAndThePythonGateAgreeOnEveryNegative` and
  `…EveryPositive`, `OverlapParityTest`), `PackageSchemaMirrorLockTest` is green, the negative-count pin holds at its new
  number, and `DesignerProjectorFixtureTest` is green (the view-model fixtures are untouched).
  Run **`VERIFY launcher`**: the Cache fixture is loaded by plan 010's catalog and declare ITs, so a fixture the
  production boot refuses must go red here, not in MW4.
- [x] **Step 5: Prove they fire.** Widen the `queryParams` value pattern to `.*` → the 8 value negatives go green,
  so red. Drop `additionalProperties:false` from `sourceHttpJson` → the both-`path`-and-`topicPattern` case goes
  green, so red. Drop the metric `name` pattern → the four name negatives go green, so red. Remove gate 15 from
  `SemanticGates.java` only → the parity test goes red on the gate-15 negative. Remove gate 16 from the Python only →
  the gate-16 negatives are accepted, so red. Mark `PackageMetric.defaultChart` `@JsonIgnore` → the survival test goes red.
  Replace one `(?![\s\S])` with `$` → the trailing-newline negative is accepted by the Python gate and refused by the Java
  one, so the parity test goes red (MF24). Leave the cp-core classpath copy stale → `PackageSchemaMirrorLockTest` goes
  red. Allow `>` or `*` as the `topicPattern` last level → the three last-level negatives are accepted, so red. Revert each.
- [x] **Step 6: Commit.** `contracts: per-kind metric sources, closed query-parameter grammar, package restart signal (M-Q4, M-Q9)`

**Results (P11-1.1, executed 2026-10-06 on `p011/p111`, Sonnet 5.5; reviewed in the wave review, not yet).** Built: `$defs/source` is a `oneOf` of
`sourceHttpJson` (closed; `port` optional per OQ-24 (b), `queryParams` with both keys and values on the closed token grammar, `intervalSeconds` 10..3600)
and `sourceUncollected` (prometheus, exec, jdbc_query **and `solace_event`, unchanged**); `$defs/restartSignal` on the component (OQ-1 (a)); `metric.defaultChart`
(OQ-7 (b)); `metric.name` and `metric.unit` bounds. Gate 15 is in `validate-package.py` and `SemanticGates.java`; `PackageModel` gained
`PackageMetric.defaultChart` and `PackageComponent.restartSignal` (survival proven by `PackageJsonReaderMetricsFieldsTest`); the cp-core classpath mirror is copied; the
Cache fixture carries `cache.size.queryParams`, the component `restartSignal` and `cache.hit_rate.defaultChart: false`; `design.md` §6.4 has its dated pointer.
No overlap fixture, `engine-edge` or `frontend/` file was edited.
- **Step 0 NOT run.** MF8's unit and MF9 need an SSH `curl` to EC2, which this run was forbidden to open; the `restartSignal` fixture values are the plan's own
  (`/actuator/metrics/process.start.time`, port 8081, pointer `/measurements/0/value`). The integrator or a later EC2 step must record MF8/MF9.
- **Step 0b (OQ-24), measured with an offline `npm ci` in the worktree, nothing under `frontend/` edited.** Baseline: `--project contracts` 73/73 (6 files); `src/app/dev` 128 passed,
  **4 failed (2 files) before any change** (`Gallery.test.tsx` x2, `Gallery.a11y.test.tsx` x2: `TypeError: Cannot read properties of undefined (reading 'setItem')`, an environment fault).
  After the change: `contracts` 73/73 and `src/app/dev` the same 128/4. **One red was caused by the change and is the stop condition:** with gate 15's negative in
  `contracts/validation/negative/`, `frontend/contracts/solution-package/validate.test.ts` ("rejects every committed semantic negative fixture") went **72/73**, because it
  iterates every file in that directory and `validate.ts` (frontend-owned) has no gate 15. So **the gate-15 negative file is HELD**: `generate.py` keeps it in `HELD_CASES`
  (not written), the count pin is **54** (19 + 35), and gate 15 is proven instead by `CrossComponentMetricGateTest` (Java, with the Python as oracle; removing the gate from
  Java reds 3 of its 4 tests, from the Python 2). **Owner action (OQ-24):** add gate 15 to `frontend/contracts/solution-package/validate.ts`, then move the `HELD_CASES` entry into `CASES`.
  The schema itself compiles under the frontend's Ajv `strict: true` and every other negative is rejected there.
- **Step 0c re-measured:** Python `jsonschema` 4.26.0 accepts `'abc\n'` under `^[A-Za-z0-9_.-]{1,64}$` and refuses it under `(?![\s\S])`; networknt 2.0.8 refuses it under both.
- **Step 1:** a planted `gate04-PLANTED-wrong-gate.json` (a gate-9 failure) made `run-checks.sh --strict` go red ("rejected by gate9, expected gate4"); removed.
- **Step 2, red before green (today's schema, 36 new negatives, 55 files with gate 15):** 11 accepted outright (the 4 metric-name, 2 unit, `pointer ..`, `pointer` space, port 70000, `path`+`topicPattern`, and gate 15)
  and 25 rejected for the wrong reason (an unknown `queryParams`/`restartSignal`/`intervalSeconds`/`defaultChart` property). Java: the reader test did not compile (5 missing symbols); `PackageSchemaMirrorLockTest`
  and the gate-15 parity pair were red (3 failures).
- **Step 4 (targeted, no full reactor):** `run-checks.sh --strict` ALL CHECKS PASSED; cp-api 48 and cp-core 401 unit tests green (`NegativeFixtureParityTest` 113 incl. the Python oracle, `CrossComponentMetricGateTest` 4; `OverlapParityTest`, `PositiveFixtureParityTest`,
  `PackageSchemaMirrorLockTest`, `DesignerProjectorFixtureTest`); ITs `CatalogServiceIT`, `CatalogServiceEventsIT` (cp-core, 23) and `CatalogEndpointsIT`, `DesignerEndpointsIT`, `CatalogEventIT` (1 skipped, pre-existing),
  `ApplyEndpointsIT`, `DeclareCrashWindowIT` (launcher, 48) green on the Cache fixture. `VERIFY launcher` was **not** run in full.
- **Step 5, mutation proofs (each reverted, files compared byte-for-byte):** `queryParams` value pattern `.*` -> 9 value negatives accepted (the plan said 8; the plan's list yields 9); `propertyNames` dropped -> 9 key negatives;
  `additionalProperties` dropped from `sourceHttpJson` -> `path`+`topicPattern` accepted; name pattern dropped -> 4; unit bounds dropped -> 2; pointer `.`/`..` guard dropped -> 1; `additionalProperties` dropped from `restartSignal` -> 2;
  `intervalSeconds.minimum` dropped -> 1; `port.maximum` dropped -> 1; `defaultChart` type dropped -> 1; one `(?![\s\S])` replaced with `$` -> the trailing-newline negative accepted by the Python and refused by Java, and
  `theValidatorAndThePythonGateAgreeOnEveryNegative` red on exactly it (MF24); gate 15 removed from `SemanticGates.java` -> red; removed from the Python -> red; `defaultChart` and `restartSignal` ignored by the reader (a Jackson mix-in
  standing in for `@JsonIgnore`, since `cp-api` carries no Jackson) -> `...SurviveTheRead` red at each assertion; classpath mirror perturbed by one byte -> `PackageSchemaMirrorLockTest` red.
  **Not applicable:** the gate-16 removal and the "allow `>`/`*` as the `topicPattern` last level" mutants, because the `solace_event` branch, gate 16 and the last-level negatives are not landed (`OR-95`).
- **Deviations, for the reviewer.** (1) `solace_event` stays admitted inside `sourceUncollected` with today's loose properties, so no existing package is refused and `P11-5.1` carves it out; the plan said only "leave the branch out".
  (2) The pointer pattern refuses a `.` or `..` **segment** (a lookahead per segment), where the plan's sketch class `[A-Za-z0-9_.-]` accepts `/a/../b`, so its own "pointer with `..`" negative could not have been rejected; `a..b` stays valid. (3) The `path` pattern is the plan's, which still accepts a `..` segment
  (a `path` is the package author's own choice of an endpoint on the same host and port, so no new reach; flagged for the reviewer). (4) The gate-15 negative is held (above). (5) `generate.py` regenerates `fixtures/overlap/cache-2.0.package.json` from the Cache fixture;
  that file was restored after each run to honour "edit no overlap fixture", so one more regeneration will change it (valid, harmless). (6) `NegativeFixtureParityTest`'s count method was renamed (its name stated the count).

### Task P11-1.2 — `console.yaml`: the 14 console operations, `metricsPorts` in the declare body, the error codes

> **Owner rulings applied (`OR-92`, `OR-93`).** **OQ-16 (a):** `capabilities.collector.disk` carries **only `disk.state`** (`ok`/`warning`/`critical`/`unconfigured`); the
> byte figures never enter the API. **OQ-17 (a):** the catalog's `collector` block is "per placement serving this metric" (reasons over 24 h), and
> `undeclared_dropped` is never shown on a declared metric. **OQ-24 (b):** the catalog `state` carries `unsupported` for an `http_json` metric with no `port` (reason: no port declared).
> **OQ-12 and OQ-15** add `out_of_order` and `ambiguous_placement` to the reason enum. **OQ-28 (b):** after P11-1.1 and plan 010 T4.4, T5.3 and T10.3 (not W11). Nothing OQ-20 may change is baked in.

**Wave:** MW1, after P11-1.1 in the same agent. **Depends on:** P11-1.1; the sitting (OQ-16, OQ-17, OQ-24); **OQ-28 (b)**.
**Merge gate:** plan 010 **T4.4 and T5.3 pushed** (this task edits T4.4's `DeclareInstanceRequest.Placement`, built on
`p010/merge3` and not yet on the integration head, and rebases onto T5.3's `console.yaml` hunk) and **T10.3 merged** (W10, docs
only: plan 010's only conditional `console.yaml` editor after S0). **Not W11** ([per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)); under OQ-28 (a):
plan 010 W11 merged. **Parallel:** ⛔ none.

**Files:** Modify `contracts/openapi/console.yaml`, `contracts/openapi/validate-openapi.py` (the row checks, and
`EXPECTED_NON_CATALOG` at `:70-78`, which gains `getCapabilities: none.authenticationOnly`),
`contracts/openapi/ENDPOINT-INVENTORY.md` (rows 53–66 "specified, not served"),
`backend/cp-api/src/main/java/com/solace/sm/cp/api/authz/ConsoleOperations.java`,
`backend/cp-api/src/test/java/com/solace/sm/cp/api/authz/ConsoleOperationsMirrorTest.java` (`AUTHENTICATION_ONLY`
at `:34-35` and `NON_CATALOG_MARKERS` at `:37-44` each gain `getCapabilities`),
`backend/console-gateway/src/main/java/com/solace/sm/gateway/http/OperationPolicy.java` (`AUTHENTICATION_ONLY_OPERATIONS`
at `:35-36` gains `getCapabilities`, and T1.3's `REACH_OPERATIONS` (`:43-45`, exposed by `reachOperations()`) gains rows 54–60), `backend/console-gateway/src/test/.../ReachOperationsMirrorTest.java`
(plan 010 T1.3's test: its expected set gains rows 54–60), `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/ErrorCode.java`,
**`backend/console-gateway/src/main/java/com/solace/sm/gateway/http/DeclareInstanceRequest.java`** (plan 010 T4.4's
record, `010-…md:2706`, **built, not pushed** (`p010/merge3`, read 2026-10-05): its nested **`Placement`** record gains `Map<String,
Integer> metricsPorts`, **accepted and not yet acted on**; P11-4.1 acts on it) and every test file Step 0 finds calling its constructor, and **`backend/console-gateway/src/test/java/com/solace/sm/gateway/http/SchemaMirrorLockTest.java`**
(unconditionally: its convention is **equality** of a schema's properties with the record's components, not presence
(`assertEquals(flattenedProperties(schema), recordComponents(type), …)`, `SchemaMirrorLockTest.java:79-90` and
`targetAndTargetListMatchTheirRequirednessPropertiesAndEnumsExactly`, `:130`), and plan 010 T4.4 extends it to the declare body, so adding `metricsPorts` to
`console.yaml` alone would turn the MW1 full verify red). Create
`backend/console-gateway/src/test/.../MetricsOperationClassificationTest.java`.

**Why the authorization files are in this task (measured).** `OperationPolicy.classify` (`OperationPolicy.java:45-60`)
throws `UnclassifiableOperationException` for any operation that is not `login`, a `LocalOperation`, in
`AUTHENTICATION_ONLY_OPERATIONS` or registered in `ConsoleOperations`, and `OperationCoverageValidator` fails the
launcher boot on it. So row 53, which console-integration marks *"authentication only"*
(`console-integration.md:395`), must enter the authentication-only set in the same change that adds it to the
YAML. Rows 54–60 are *"organization ↓"* with their ids in the body or query (`:396-402`), and M-Q17 rules that
OR-51's reach filtering governs them. Plan 010 T1.3 makes `OperationPolicy` classify *"exactly the operations
`console.yaml` marks `x-sm-scope: reach`"*, and `ReachOperationsMirrorTest` reads the YAML (`ReachOperationsMirrorTest.theReachSetMirrorsConsoleYamlExactly`, `:35`).
So marking rows 54–60 `reach` turns that test red in MW1 unless `OperationPolicy`'s reach set changes in the same
task. `OperationPolicy` is plan 010 T1.3's file, and T1.3 (W1) is its only plan 010 owner.

**The contract content:**
1. **Rows 53–66 exactly as console-integration §5.4** (verb and scope per row), with the **wire shapes of
   `DemoMonitoringController.java:223-242`** (which match DM §5.4–5.6 and the frontend's `metricsRepository.ts`), as
   M-Q23 adopts the shape, plus these additions:
   - **Scopes:** row 53 `getCapabilities` is `x-sm-permission: none.authenticationOnly` (install). Rows 54–60 are
     `x-sm-scope: reach` (M-Q17 `OR-79`). Row 61 is `organization`. Rows 62–66 are `instance`, resolved from the
     `dashboardId` path parameter (P11-7.2's `DashboardResourceResolver`; `dashboard_id = 'db_' || instance_id`,
     P11-2.3).
   - **Row 55 carries two verbs** (`metrics.query` **and** `instance.view`, `console-integration.md:397`), but the
     reach classification carries one `Permission` (`OperationClassification.Reach(Permission)`, `OperationClassification.java:48`). So row 55 is classified
     `Reach(METRICS_QUERY)` for the pre-check, and its YAML carries `x-sm-permission-also: instance.view`; the
     handler (P11-7.2) **intersects** `authorizedSubset(METRICS_QUERY)` with `authorizedSubset(INSTANCE_VIEW)`.
     `validate-openapi.py` checks that row 55 carries both.
   - `capabilities.collector: {state: live|degraded, runwayEnd: date, unfrozenClosedPartitions: int, reasons: [{reason, last24h}], disk: {state: ok|warning|critical|unconfigured}}` (M-Q21; `disk` is M-Q19's disk-usage alarm, shaped per OQ-16 (a)). **Scope of each field (`I5`), stated in the YAML description:** `state`, `runwayEnd`, `unfrozenClosedPartitions` and `disk.state` are install-level health; `reasons` are counted over **the caller's organization only** (`Session.organizationId()`). ⛔ **No byte figure** (`metricsBytes`, `databaseBytes`, `budgetBytes`) is in the contract: they are install-wide totals across every tenant, and row 53 is readable by every authenticated principal of every organization. They are printed only by `MetricsPartitionMain status` (OQ-16). `unfrozenClosedPartitions` is the number of attached `metrics_raw_*` partitions whose upper bound is at or before `now()` (UTC) and that have **no row** in `sm_metrics.partition_freeze_log` (P11-2.1);
   - `coverage.provisionalFrom` (discovery default D-6, S-L);
   - `partial.unavailable[]` items `{instanceId, label, reason}` with `reason ∈ {not-collecting, collector-degraded}` (M-Q17: authorized instances only);
   - catalog entries gain `restartDetection: declared|docker|inferred`, `state ∈ {plottable, unsupported}` (M-Q6: `histogram` is `unsupported`; under OQ-24 (b) so is an `http_json` metric that declares no `port`) and `onUnavailable`. **The per-metric `collector` block is contracted only as OQ-17 rules it.** For OQ-17 (a) it is `collector: {scope: "placements-serving-this-metric", reasons: [{reason, last24h}]}`, where `undeclared_dropped` is never listed. For (c) there is no block. ⛔ Nothing per-metric is written into `console.yaml` while OQ-17 is open.
2. `GET /export/range?metric&from&to[&instanceId…]` → `text/csv`, chunked (discovery default D-12; console-integration row 60 is the contract either way). It returns `422 EXPORT_TOO_LARGE`
   with `{projectedRows, limit}` (M-Q22 (4)), and `429 RATE_LIMITED` with reason `export-concurrency` (M-Q22 (3)).
3. `POST /metrics/catalog/{name}/type` → **`501 NOT_IMPLEMENTED`**, reason `metric-declare-deferred` (M-Q6: the
   contracted refusal).
4. `dashboardId` pattern `^db_[0-9a-f-]{36}$` (the demo's wire id, `DemoDashboardStore.java:195-200`).
5. The declare body's **`placement`** gains optional `metricsPorts: {"<sourcePort>": <hostPort>}`, where each key and value is
   1–65535 and an absent key defaults to the source port (M-Q14 `OR-74`). It sits **beside `healthPort`**, which M-Q14 says it
   *mirrors*: measured on `p010/merge3`, the built contract nests the host-side ports under `placement` (`console.yaml`'s
   `placement` schema, `additionalProperties: false`; `DeclareInstanceRequest.Placement`, whose `@JsonAnySetter` refuses an unknown
   key with `400`), so a top-level `metricsPorts` would be a second place for host-side ports. `acquisition: {mode}` stays closed
   and hostless (`OR-71` as amended by `OR-82`; `console.yaml`'s `Acquisition` schema, `:1902`; the built `acquisition` is a
   `JsonNode`, so an extra key is a `422`, not a `400`). ⚠️ `metricsPorts` names a port
   **on the target's registered address** (direct mode) or on the host loopback (SSH mode); the body carries no
   address, and plan 010 T4.4's `declareBodyWithAnAcquisitionHostIsRefused422` is the pattern for refusing one.
6. `422` codes: `SERIES_CAP_EXCEEDED` `{cap, projected, seriesBy}`, `AGGREGATION_NOT_APPLICABLE`,
   `GROUP_BY_REQUIRES_AGGREGATION`, `STAT_REQUIRES_AGGREGATION`, `METRIC_UNTYPED`, `EXPORT_TOO_LARGE`. `409`:
   `PACKAGE_VERSION_MISMATCH`, `METRICS_PORT_CONFLICT`. (Measured: plan 010 already added the generic `CONFLICT` (409) and
   `VALIDATION_FAILED` (422), `ErrorCode.java`, and several codes share a status by design; whether a metrics-port
   collision is its own code or `CONFLICT` with a reason is a contract-review point for the task's reviewer, not an owner
   question.)

- [x] **Step 0: Read the landed T1.3 files** (`OperationPolicy`, `OperationClassification`, `ReachOperationsMirrorTest`)
  and record the landed name of the reach set. **Measured at `bd802fc`:** it is `OperationPolicy.REACH_OPERATIONS`
  (`:43`), read through `reachOperations()`, and `OperationClassification.Reach(Permission)`; if it has moved, rebind this
  task's Files to the landed file and say so in the commit. Read the landed `DeclareInstanceRequest` (T4.4, **built on
  `p010/merge3` and not on the integration head at the review**; this task starts only once it is pushed) and grep its constructor
  call sites (`grep -rn "new DeclareInstanceRequest" backend/*/src`, which also finds `DeclareInstanceRequest.Placement`); each
  `Placement` call gains the new `metricsPorts` argument (`null`, "absent"), and the commit lists them.
- [x] **Step 1: Failing checks.** In `validate-openapi.py`, every row 53–66 exists with its verb (`x-sm-permission`)
  and scope; `query/range` carries `x-sm-permission-also: metrics.forceRaw` when `resolution: raw`; the 422 enum in
  the YAML equals `ErrorCode`'s. In `MetricsOperationClassificationTest`:
  `getCapabilitiesClassifiesAuthenticationOnly()`, `queryRangeIsReachClassified()` (and each of rows 54–60),
  `metricDeclareIsCatalogClassifiedAtTheOrganization()`, `everyDashboardOperationIsCatalogClassified()`,
  `row55CarriesMetricsQueryAndInstanceView()`, `theCapabilitiesSchemaCarriesNoByteFigure()`. In
  `SchemaMirrorLockTest`: `declareInstanceRequestMirrorsTheDeclareBodyIncludingPlacementMetricsPorts()`. Run them and see red.
- [x] **Step 2: Write the contract, `ConsoleOperations` rows, the `OperationPolicy` sets, the two mirror tests'
  expectations, the `ErrorCode` entries and `DeclareInstanceRequest.Placement.metricsPorts`** (validated 1–65535 on both
  sides; nothing downstream reads it until P11-4.1).
- [x] **Step 3: Run** `./contracts/run-checks.sh --strict`, `VERIFY cp-api` (`ConsoleOperationsMirrorTest`),
  `VERIFY console-gateway` (check 11: every registered operation has a shippable verb; `ReachOperationsMirrorTest`),
  **`VERIFY launcher`** (the production boot stays green; no handler exists yet) and `VERIFY arch-tests`.
- [x] **Step 4: Prove they fire.** Give `export/range` the verb `metrics.query` → the verb check goes red. Drop
  `STAT_REQUIRES_AGGREGATION` from `ErrorCode` → the enum parity check goes red. Leave `getCapabilities` out of
  `AUTHENTICATION_ONLY_OPERATIONS` → `getCapabilitiesClassifiesAuthenticationOnly` goes red. (The launcher boot would go red too, but only once P11-7.2 adds the handler: `OperationCoverageValidator` classifies each handler method, `OperationCoverageValidator.java:147`.)
  Mark `query/range` `x-sm-scope: organization` → `ReachOperationsMirrorTest` goes red. Add `metricsPorts` to the
  YAML only, not to `DeclareInstanceRequest.Placement` → `SchemaMirrorLockTest` goes red. Drop `x-sm-permission-also:
  instance.view` from row 55 → the row-55 check goes red. Revert each.
- [x] **Step 5: The inventory hunk.** If plan 010 T12.1 (W12) is open, rebase the `ENDPOINT-INVENTORY.md` hunk onto
  it. T12.1 edits the "served" rows of existing operations, and this task adds rows 53–66 as "specified, not
  served", so the hunks are disjoint. The integrator merges T12.1 first.
- [x] **Step 6: Commit.** `contracts(openapi): the /api/console/v1 surface and declare-body metricsPorts, specified not served (M-Q10, M-Q14, M-Q17, M-Q22)`

**Results (P11-1.2, executed 2026-10-06 on `p011/p12`, Sonnet 5.5; commit `5ead541d`; not yet wave-reviewed).** Built: `console.yaml` has the
14 operations at `/console/v1/*` (rows 53-66; the `/api` server prefix makes them `/api/console/v1`), a `monitoring` tag, seven parameters, two responses and the
`Capabilities`, `MetricCatalogEntry`, `MetricQueryRequest`/`MetricQueryResult`, `Widget`/`WidgetSubmission`, `Dashboard`, `WidgetPlacement` and three problem
schemas; `placement.metricsPorts` is beside `healthPort`; `ErrorCode` gained the eight codes in the YAML and in `ErrorCode.java`.
`ConsoleOperations` registers rows 54-66; `OperationPolicy` classifies `getCapabilities` authentication-only and rows 54-60 reach;
`DeclareInstanceRequest.Placement` gained `@WireOptional Map<String,Integer> metricsPorts` (key and value 1-65535, one regex shared with the YAML;
accepted and not acted on). Step 0: the landed names are as read on this base, `11a1244e` (`OperationPolicy.REACH_OPERATIONS`, `reachOperations()`,
`OperationClassification.Reach`); `DeclareInstanceRequest.Placement` has **no constructor call site outside Jackson** (`grep` over `backend/*/src`), so no test needed the new argument.
Check 18 is new in `validate-openapi.py` (the fourteen rows, the 501, the dashboard-id pattern, no byte figure, `ErrorCode` parity with the Java enum); `EXPECTED_NON_CATALOG` gained `getCapabilities`.
**Red first:** the Python check printed 32 `FAIL` lines; the Java side was a compile error (2), then, with only the `Placement` component added, 11 of 44 targeted tests red
(`MetricsOperationClassificationTest` 9 of 9, `SchemaMirrorLockTest` 2 of 35: the new test and the existing equality test). **Green:** `run-checks.sh --strict` passes; `cp-api` 48 of 48 and `console-gateway` 334 of 334
(surefire, whole modules); `arch-tests` 56 of 56; `launcher` `ProductionAuthenticationIT` and `HierarchyEndpointsIT` 10 of 10 (the boot stays green; no handler exists).
**15 mutations, each red, each restored byte-for-byte:** the six of Step 4 (the `metricsPorts` one done as its converse, the YAML property renamed while the record keeps its component, because the plan's direction does not compile against the new test), and nine more: `queryRange` loses `metrics.forceRaw`, a byte figure in capabilities, the dashboard-id pattern loosened (red in check 18 only), the key regex loosened, the host-port bound off by one, `exportRange` dropped from the reach set, `saveWidget` registered under the wrong verb, `getDashboard`'s 404 dropped, and the 501 dropped.
⚠️ **Decisions this task made that no ruling states, for the wave reviewer and the integrator:** (1) **The catalog has no availability envelope.** The wire shape the plan adopts (the demo's) is a bare array, which has no place
for the per-surface `Availability` P11-7.2 says the catalog reports; the contract says a failed control-plane half is `503` and a failed data-plane half leaves the observed fields (`lastSeen`, `collector`) **absent**, never zero.
Wrapping the array in `{availability, items}` is the alternative and changes the frontend's `metricCatalog()`. (2) **`lastSeen` is epoch milliseconds**, as the demo and the frontend carry it; `dynamic-metrics` §5.5's example shows ISO text.
(3) **Dashboard concurrency has a wire form:** `Dashboard.revision` and an optional `X-SM-Dashboard-Revision` request header on rows 63-66; absent, the write applies to the current revision. P11-7.1's port requires a revision on every write and no ruling says how it travels.
(4) **`dashboardId` is the strict UUID shape** (`^db_` and the 8-4-4-4-12 form), which is a subset of the plan's `^db_[0-9a-f-]{36}$` and what P11-7.2's resolver describes. (5) **The catalog is narrowed to v1's producers:** `state` is `plottable` or `unsupported`
(with `unsupportedReason`), `layer` is `package-baseline`; the frontend's wider types still accept it. (6) **Row 59 carries one verb**, as `console-integration.md` says; only row 58 declares `metrics.forceRaw`.
(7) **The 422/429/501 bodies extend `Problem`** with `reason` or `{cap, projected, seriesBy}` or `{projectedRows, limit}`; the Java `ProblemBody` has no such member, so P11-7.2 adds the subtypes. (8) `METRICS_PORT_CONFLICT` and `PACKAGE_VERSION_MISMATCH` are their own codes (the plan's
reviewer point), at 409 beside `CONFLICT`. (9) A structurally invalid `metricsPorts` is `400`, as every other body fault; only a semantic refusal is `422`. **For the integrator's merge with plan 010 T10.4's `console.yaml`:** this task's hunks are the `monitoring` tag (after `governance`), the paths block
(appended before `components:`), the parameters (after `CsrfToken`), the responses (before `schemas:`), the schemas (appended at the end of the file), the `ErrorCode` prose and enum (and the `RATE_LIMITED` and `CONFLICT` lines), and two spots in `declareInstance` (the `metricsPorts` property, and its 409/422 text).
Not run: the other launcher ITs and the full `dp-core` verify (nothing in them reads these files).

---

## MW2 — Storage

### Task P11-2.1 — `sm_metrics`: its own history, the fact tables, the runway function, the operator command

> **Owner rulings applied (`OR-92`, `OR-93`, 2026-10-05).** **Starts after the sitting** (OQ-28 (b); needs nothing beyond S2); local until its wave review. **OQ-27 (b): the archive is per tenant,
> by a bound cursor `SELECT … WHERE tenant_id = ?` over the detached partition, CSV encoded in the application, no `COPY`, one manifest per tenant** (the
> tenant list is `SELECT DISTINCT tenant_id` on the detached partition under the owner credential); this **replaces** the "written for OQ-27 (a)" reading in the
> operator-command paragraph below. **OQ-10 (a):** `freeze` is a monthly operator command; the collector-health block shows `unfrozenClosedPartitions`. **OQ-11 (a):** unencrypted gzip CSV plus a verified manifest; `drop` refuses without one.
> **OQ-12 (a):** `out_of_order` is in `V001`'s reason CHECK. **OQ-15 (a):** `ambiguous_placement` is in `V001`'s reason CHECK. **OQ-16 (a):** `status` prints the byte figures. **OQ-21 (1) and (2a):**
> the function is `sm_metrics.ensure_metric_partitions()`, and `metrics_rollup_1h` is unpartitioned. **OQ-22:** D-2 and D-8 accepted (the orphan V001 is deleted; open item 3 narrowed).
> **OQ-20 stays OPEN (`OR-95`):** `ingest_counter` keeps M-Q21's key exactly (`placement_id NOT NULL`); `V002` is reserved if OQ-20 is later ruled (b) or (c).

**Wave:** MW2. **Depends on:** plan 010 T2.1 (placement; ✅ landed) and T2.4 (the last plan 010 editor of
`sm_data_phase0.sql`; ✅ landed); OQ-10, OQ-11, OQ-12, OQ-15 (the reason list), OQ-20 (the counter key), OQ-21 (the
partitioning), OQ-22 (D-8), OQ-27 (the archive's shape) and **OQ-28 (b)**. **Nothing more from plan 010 than S2, which is
pushed** ([per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why); under OQ-28 (a): plan 010 W11). **Parallel with:** P11-2.2, P11-2.3; **merges first** in MW2 (the
shared pin files).

**Files:** Modify `backend/dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql`: **append** `CREATE SCHEMA sm_metrics
AUTHORIZATION sm_dp_owner;` and `GRANT USAGE ON SCHEMA sm_metrics TO sm_dp_runtime;` **at the end**, after T2.4's
`sm_secret` block (⚠️ not "beside `sm_data`'s lines 21–22", as the first draft said: `SketchMirrorTest` pins the seven
sketch statements first and the later ones last, `:56`, so inserting among the first seven breaks the mirror),
`backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/SmDataMigrationMain.java` (see "The migration
entry point" below). **The pin tests** (P11-2.1 is their first plan 011 owner; P11-2.2 and P11-5.1 follow,
single-owner table; **re-measured 2026-10-05, and the first draft's pin analysis is superseded**, [Prerequisite 7](#prerequisites)):
- `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/SmDataBootstrapIT.java`: (i) its Phase 0 statement
  count (`phase0RunsCleanAsTheSuperuserWithNoErrorAndNoWarning`, `:65`, **9** today → 11); (ii) **the history-table
  lookup, schema-qualified to `sm_data`**. Measured: in `flywayAppliesExactlyTenMigrationsIntoSmDataAndRecordsThemThere`
  (`:170`), `:183-187` selects `n.nspname … WHERE c.relname = 'flyway_schema_history'` with **no schema filter** through
  `queryForObject` (the owner query at `:190-193` filters `n.nspname = :s` and is already safe). The harness runs
  `SmDataPostgres.bootstrap()` → `migrate()` → `SmDataMigrationMain.run` (`SmDataPostgres.java:63-67, 173-174`), so once
  `run` also migrates `sm_metrics` there are **two** history tables and that query returns two rows and throws. The
  assertion becomes "a history table exists in `sm_data`, owned by `sm_dp_owner`", plus a new twin assertion for
  `sm_metrics`, and a twin of `phase0CreatesSmSecretOwnedBySmDpOwner` (`:92`) for the new schema. (iii) Its exact-set pins
  (migration count and list at `:173-176`, table count at `:215`, function list and trigger list at `:217-245`) are left
  for P11-2.2/P11-5.1's deltas (and plan 010's V011–V014); this task changes only what Phase 0 and the second history
  change;
- `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/SketchMirrorTest.java`: **one** change, following
  T2.4's convention. The mirror covers only V001–V006 (`migrationFiles()` filters `compareTo("V007") < 0`, `:199`) and
  treats Phase 0 statements added after the sketch as `PHASE0_AFTER_THE_SKETCH` (`:47`). So this task appends its two
  statements to that list, and `phase0CarriesExactlyTheSketchPlusTheSecretSchemasTwoStatements` (`:52-57`) moves from
  `assertEquals(9, …)` / `subList(7, 9)` to 11 / `subList(7, 11)` (rename the method: its name says "two statements").
  **No other `SketchMirrorTest` assertion changes**, and `eda-walking-skeleton.md` §3.1 is **not** edited (it "does not grow
  with the schema", `SketchMirrorTest.java:42-46`). The `evidence/ddl-execution-pg14.md` §2.2 count is historical and is not
  edited; the new list entries' comment cites M-Q19.

Create
`…/dp-core/src/main/resources/db/migration/sm_metrics/V001__metrics_store.sql`,
`…/dp/apply/persistence/{SmMetricsMigrator,MetricsPersistenceConfiguration,JdbcPartitionRunwayRepository,MetricsPartitionMain,MetricsPartitionArgument,MigrationReport}.java`;
Create tests `…/persistence/{SmMetricsMigratorIT,SmMetricsSchemaIT,PartitionRunwayIT,MetricsPartitionMainIT,MetricsPartitionArgumentTest}.java`;
Modify `…/dp/DataPlaneConfiguration.java` (one `@Import`); Delete `backend/src/main/resources/db/migration/V001__telemetry_schema.sql`
and `backend/src/test/sql/V001_constraints_test.sql`; Modify `backend/README.md` (`:150-153`) and
`docs/runbooks/install-prerequisites.md` (the Phase 0 delta, below).

`SmMetricsMigrator` mirrors `SmDataMigrator` (`SmDataMigrator.java:34-63`): `SCHEMA = "sm_metrics"`, `INIT_SQL = "SET
ROLE sm_dp_owner"`, `LOCATION = "classpath:db/migration/sm_metrics"`, `createSchemas(false)`, and the default history
table **inside `sm_metrics`** (MF1).

**The migration entry point.** `SmDataMigrationMain.run(url, username, password)` (`:165-166`) **keeps its
signature and still returns `sm_data`'s `MigrateResult`**. It now migrates `sm_data` **then** `sm_metrics` through
a new `runAll(url, username, password)`, which returns `MigrationReport(MigrateResult smData, MigrateResult
smMetrics)`, and `run` returns `runAll(…).smData()`. `exitCode` (`:118`, whose `run` call is at `:140`) calls `runAll` and prints both
histories' counts and versions (no URL, user or password, the class's existing I6 rule). Every existing caller
therefore migrates both schemas without an edit. That keeps the javadoc's rule (`:157-158`: *"the one-shot step
every caller shares, including the integration harness, so no test can prove a path production does not take"*)
and satisfies P11-2.1's readiness check in every harness. The callers, measured, and why none changes:
`SmDataPostgres.java:174`, `TwoSchemaTestDatabase.java:116`, `HierarchyEndpointsIT.java:109`,
`ProductionAuthenticationIT.java:215` and `ProductionRateGateAndForwardingIT.java:96` use the result for nothing
that `sm_metrics` changes. `ApplyPersistenceConfigurationIT.java:99,167-169` asserts **`sm_data`'s** count (`10` at `bd802fc`; it moves with plan 010,
not with this task) and a second run's idempotence, both unchanged because `run` still returns `sm_data`'s result.
`SmDataMigrationMainTest.java:284,374` exercise URL refusal before any migration, and `:517` calls `exitCode` under a log
capture (its I6 assertions are about secrets, and the extra `sm_metrics` line prints none; Step 0 re-reads it). Several of these files belong to
plan 010 tasks (T2.6, T6.2); because nothing in them changes, plan 010's ownership is not disturbed. ⚠️ **One test of
the harness does change:** `SmDataBootstrapIT`'s unfiltered history-table lookup (above), which is why that file is in
this task. If Step 0 finds any other caller, or any other test, that looks up `flyway_schema_history` without a
schema filter or asserts on a total, it is added to this task's Files and fixed the same way, and the commit names
it.

**The Phase 0 delta for an install that already ran Phase 0.** (Measured 2026-10-05: `install-prerequisites.md` has **no
Phase 0 section at all** today, only the Argon2id capacity and the single-replica rules, and plan 010 T2.4's `sm_secret`
statements are not described there either; so this task writes the first one, covering both schemas.)
`install-prerequisites.md` gains one step, run by the
same installer who ran Phase 0 (the executor must be a member of `sm_dp_owner`, the Phase 0 comment at
`sm_data_phase0.sql:16-20`): `CREATE SCHEMA sm_metrics AUTHORIZATION sm_dp_owner; GRANT USAGE ON SCHEMA sm_metrics
TO sm_dp_runtime;`, then the migration step as usual. A fresh install gets both lines from Phase 0 itself.

**V001 (reviewable here):**

```sql
-- sm_metrics V001 — run by SmMetricsMigrator as sm_dp_migrator with SET ROLE sm_dp_owner. DP-owned (M-Q19).
CREATE TABLE sm_metrics.series (                        -- M-Q7 (a): identity; insert-only
    tenant_id uuid NOT NULL,
    series_id bigint GENERATED ALWAYS AS IDENTITY,
    instance_id uuid NOT NULL,                          -- resolved from sm_data.placement, never from a batch
    placement_id uuid NOT NULL,
    metric_name text NOT NULL CONSTRAINT series_metric_name CHECK (metric_name ~ '^[a-z][a-z0-9_.]{0,127}$'),
    metric_type text NOT NULL CONSTRAINT series_metric_type CHECK (metric_type IN ('counter','gauge')),
    unit text NOT NULL CONSTRAINT series_unit CHECK (length(unit) BETWEEN 1 AND 64),
    source_discriminator text NOT NULL CONSTRAINT series_source CHECK (source_discriminator IN ('dp-poll','broker-push','agent')),
    labels_fingerprint text NOT NULL CONSTRAINT series_labels CHECK (labels_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, series_id),
    CONSTRAINT series_identity UNIQUE (tenant_id, instance_id, placement_id, metric_name, metric_type, unit,
                                       source_discriminator, labels_fingerprint),
    CONSTRAINT series_typed UNIQUE (tenant_id, series_id, metric_type),   -- the rollups' typed FK target
    -- M-Q19: an intra-DP FK to sm_data.placement, which the ruling permits (so are restart_marker's and ingest_counter's).
    CONSTRAINT series_placement FOREIGN KEY (tenant_id, placement_id) REFERENCES sm_data.placement (tenant_id, placement_id));

CREATE TABLE sm_metrics.series_segment (                -- package_version is an ATTRIBUTE (M-Q7); interval in force (discovery default D-3)
    tenant_id uuid NOT NULL, series_id bigint NOT NULL,
    effective_from timestamptz NOT NULL,
    package_version text NOT NULL,
    interval_seconds integer NULL CHECK (interval_seconds BETWEEN 1 AND 86400),   -- NULL on the push path
    PRIMARY KEY (tenant_id, series_id, effective_from),
    FOREIGN KEY (tenant_id, series_id) REFERENCES sm_metrics.series (tenant_id, series_id));

CREATE TABLE sm_metrics.series_reset (                  -- M-Q9: reset_count = count(*) per series; append-only
    tenant_id uuid NOT NULL, series_id bigint NOT NULL, at timestamptz NOT NULL,
    signal text NOT NULL CHECK (signal IN ('declared','docker','inferred')),
    PRIMARY KEY (tenant_id, series_id, at),
    FOREIGN KEY (tenant_id, series_id) REFERENCES sm_metrics.series (tenant_id, series_id));

CREATE TABLE sm_metrics.restart_marker (                -- the LATEST restart token observed per placement (M-Q9).
    -- Not the comparison base: each series is compared with the token seen with ITS OWN previous sample (in memory,
    -- P11-3.1). This row only records the latest observation, and seeds a series' previous token after a boot.
    tenant_id uuid NOT NULL, placement_id uuid NOT NULL,
    token text NOT NULL CHECK (length(token) BETWEEN 1 AND 256), observed_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, placement_id),
    FOREIGN KEY (tenant_id, placement_id) REFERENCES sm_data.placement (tenant_id, placement_id));

CREATE TABLE sm_metrics.metrics_raw (                   -- REQ-007: raw for the full window
    tenant_id uuid NOT NULL, series_id bigint NOT NULL,
    ts timestamptz NOT NULL,                            -- M-Q24 (8): UTC midpoint of sent/received (poll)
    value double precision NOT NULL
        CONSTRAINT raw_value_finite CHECK (value <> 'NaN'::float8 AND value <> 'Infinity'::float8 AND value <> '-Infinity'::float8),
    increment double precision NULL,                    -- counters only: M-Q9's attributable increase; NULL = none
    received_at timestamptz NOT NULL,                   -- M-Q24 (8): kept, 8 B/row
    PRIMARY KEY (tenant_id, series_id, ts),
    FOREIGN KEY (tenant_id, series_id) REFERENCES sm_metrics.series (tenant_id, series_id)
) PARTITION BY RANGE (ts);                              -- no DEFAULT partition, ever

CREATE TABLE sm_metrics.metrics_rollup_1h (
    tenant_id uuid NOT NULL, series_id bigint NOT NULL, bucket timestamptz NOT NULL,
    metric_type text NOT NULL CHECK (metric_type IN ('counter','gauge')),   -- bound to series by the typed FK below
    sample_count integer NOT NULL CHECK (sample_count > 0), expected_count integer NULL,
    sum double precision NULL, min double precision NULL, max double precision NULL,   -- gauges
    delta double precision NULL,           -- counters: sum(increment); NULL when every increment is NULL (never 0, M-Q9)
    covered_seconds double precision NULL, -- counters: Σ actual Δts of the samples whose increment is non-null (M-Q24 (8))
    last_value double precision NOT NULL, last_ts timestamptz NOT NULL,
    reset_count integer NOT NULL DEFAULT 0,
    computed_at timestamptz NOT NULL,
    -- The TYPE decides the shape; nullability never stands in for the type. A gauge hour has sum/min/max and no
    -- delta. A counter hour has no sum/min/max, and its delta and covered_seconds are NULL together (a new series'
    -- first sample, or an hour made only of post-gap samples, has increment NULL throughout).
    CONSTRAINT rollup_1h_type_shape CHECK (
        (metric_type = 'gauge'   AND sum IS NOT NULL AND min IS NOT NULL AND max IS NOT NULL
                                 AND delta IS NULL AND covered_seconds IS NULL)
     OR (metric_type = 'counter' AND sum IS NULL AND min IS NULL AND max IS NULL
                                 AND (delta IS NULL) = (covered_seconds IS NULL))),
    PRIMARY KEY (tenant_id, series_id, bucket),
    FOREIGN KEY (tenant_id, series_id, metric_type) REFERENCES sm_metrics.series (tenant_id, series_id, metric_type)
);   -- UNPARTITIONED (OQ-21 (2a)): ≈4.4 M rows in six months at S-A, so the carve-out's grammar stays metrics_raw_YYYY_MM

CREATE TABLE sm_metrics.metrics_rollup_1d (LIKE sm_metrics.metrics_rollup_1h INCLUDING DEFAULTS INCLUDING CONSTRAINTS,
    PRIMARY KEY (tenant_id, series_id, bucket),
    FOREIGN KEY (tenant_id, series_id, metric_type) REFERENCES sm_metrics.series (tenant_id, series_id, metric_type));   -- unpartitioned; retention null

CREATE TABLE sm_metrics.ingest_counter (                -- M-Q21 (a), exactly as ruled under OQ-20 (a); (b)/(c) reshape it
    tenant_id uuid NOT NULL, placement_id uuid NOT NULL,     -- NOT NULL: a drop with no single placement is OQ-20's
    reason text NOT NULL CONSTRAINT ingest_counter_reason CHECK (reason IN ('scrape_failed','parse_failed','cap_rejected',
        'undeclared_dropped','decrease_without_restart','runway_degraded','deadline_missed','buffer_dropped'
        'out_of_order','ambiguous_placement'  /* OQ-12 (a) and OQ-15 (a), ruled OR-92 */)),
    day date NOT NULL, count bigint NOT NULL CHECK (count > 0),
    PRIMARY KEY (tenant_id, placement_id, reason, day),
    FOREIGN KEY (tenant_id, placement_id) REFERENCES sm_data.placement (tenant_id, placement_id));

CREATE TABLE sm_metrics.partition_freeze_log (          -- M-Q13 "VACUUM FREEZE each partition on close" (OQ-10): written
    partition_name text PRIMARY KEY                     -- by MetricsPartitionMain freeze (owner credential) after the VACUUM;
        CHECK (partition_name ~ '^metrics_raw_[0-9]{4}_(0[1-9]|1[0-2])$'),   -- read by the runtime for unfrozenClosedPartitions
    frozen_at timestamptz NOT NULL);

-- M-Q13 (c′): the narrow I5 carve-out. No argument; every name and bound comes from the clock through a
-- closed grammar (metrics_raw_YYYY_MM exactly, UTC months; OQ-21 (2a)). MF2: pinned search_path ending in
-- pg_temp; PUBLIC revoked and EXECUTE granted in this same (transactional) migration. MF21, defence in depth: every
-- catalog relation is written pg_catalog-qualified, so neither a missing pg_temp entry NOR an unqualified name
-- alone lets a planted temp table shadow it. ⚠️ OQ-21 (1), confirm-only: the function lives in sm_metrics, not
-- sm_data (M-Q19's consequence). The grammar is the ruled one; it names one parent.
CREATE FUNCTION sm_metrics.ensure_metric_partitions() RETURNS date
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = sm_metrics, pg_temp
    SET lock_timeout = '5s'
    SET "TimeZone" = 'UTC'
AS $$
DECLARE
    first_month date := date_trunc('month', now())::date;   -- UTC by the SET clause
    child text; lo date; hi date; i int; attempt int;
    runway date;
BEGIN
    FOR i IN 0..3 LOOP                                         -- this month + 3 ahead; ONE parent, metrics_raw
        lo := (first_month + make_interval(months => i))::date;
        hi := (first_month + make_interval(months => i + 1))::date;
        child := 'metrics_raw_' || to_char(lo, 'YYYY_MM');     -- the closed grammar, exactly as ruled
        CONTINUE WHEN to_regclass('sm_metrics.' || child) IS NOT NULL;
        FOR attempt IN 1..3 LOOP
            BEGIN
                EXECUTE format('CREATE TABLE sm_metrics.%I (LIKE sm_metrics.metrics_raw INCLUDING DEFAULTS INCLUDING CONSTRAINTS)', child);
                EXECUTE format('ALTER TABLE sm_metrics.%I ADD CONSTRAINT %I CHECK (ts >= %L::timestamptz AND ts < %L::timestamptz)',
                               child, child || '_bound', to_char(lo, 'YYYY-MM-DD') || ' 00:00:00+00',
                               to_char(hi, 'YYYY-MM-DD') || ' 00:00:00+00');
                EXECUTE format('ALTER TABLE sm_metrics.metrics_raw ATTACH PARTITION sm_metrics.%I FOR VALUES FROM (%L) TO (%L)',
                               child, to_char(lo, 'YYYY-MM-DD') || ' 00:00:00+00', to_char(hi, 'YYYY-MM-DD') || ' 00:00:00+00');
                EXIT;
            EXCEPTION WHEN lock_not_available THEN
                IF attempt = 3 THEN RAISE; END IF;              -- the caller counts runway_degraded; never boot-fatal
            END;
        END LOOP;
    END LOOP;
    SELECT max(to_date(right(c.relname, 7), 'YYYY_MM')) + interval '1 month' INTO runway
      FROM pg_catalog.pg_inherits h JOIN pg_catalog.pg_class c ON c.oid = h.inhrelid
     WHERE h.inhparent = 'sm_metrics.metrics_raw'::regclass;
    RETURN runway;
END $$;
REVOKE ALL ON FUNCTION sm_metrics.ensure_metric_partitions() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION sm_metrics.ensure_metric_partitions() TO sm_dp_runtime;

-- Grants: facts are append-only for the runtime; rollups and counters upsert; nothing is deleted at runtime.
GRANT SELECT, INSERT ON sm_metrics.series, sm_metrics.series_segment, sm_metrics.series_reset, sm_metrics.metrics_raw TO sm_dp_runtime;
GRANT SELECT, INSERT, UPDATE ON sm_metrics.metrics_rollup_1h, sm_metrics.metrics_rollup_1d, sm_metrics.restart_marker TO sm_dp_runtime;
GRANT SELECT, INSERT, UPDATE (count) ON sm_metrics.ingest_counter TO sm_dp_runtime;
GRANT SELECT ON sm_metrics.partition_freeze_log TO sm_dp_runtime;   -- written only by the owner-credential operator command

SELECT sm_metrics.ensure_metric_partitions();            -- M-Q13: each migration seeds 3 months ahead
```

`MetricsPartitionMain` (a standalone `main` with the migrator credential from the environment, `SmDataMigrationMain`'s
shape, never a Spring context) has these subcommands: `status` (runway, closed and unfrozen partitions, and the
disk-alarm **byte figures**, `sm_metrics` bytes, database bytes and the configured budget, which the API never carries,
OQ-16); `freeze <yyyy_mm>` (`VACUUM (FREEZE) sm_metrics.metrics_raw_<yyyy_mm>`, only for a closed month, OQ-10, then
one `INSERT` into `sm_metrics.partition_freeze_log`, bound); `detach <yyyy_mm>`
(`ALTER TABLE … DETACH PARTITION … CONCURRENTLY`, `lock_timeout`, never in a transaction, MF4); `archive <yyyy_mm>
<dir>` (identifier-only `COPY (SELECT … FROM <detached>) TO STDOUT`, gzip, plus a sha256/row-count manifest,
OQ-11; **ruled OQ-27 (b), `OR-92`: one archive per tenant**: a bound cursor `SELECT … WHERE tenant_id = ?` over the
detached partition, CSV encoded in the application, replaces the identifier-only `COPY`, and the manifest is per tenant; the "per partition" `COPY` form was OQ-27 (a), not taken); `drop <yyyy_mm>` (refuses without a verified manifest). **The second sanctioned I5 case** (Global
Constraints): `MetricsPartitionArgument.parse` checks the argument against `^[0-9]{4}_(0[1-9]|1[0-2])$` **before any
connection is opened**. A bound catalog lookup (`SELECT c.relname FROM pg_catalog.pg_inherits h JOIN
pg_catalog.pg_class c ON c.oid = h.inhrelid WHERE h.inhparent = ?::regclass AND c.relname = ?`, or the same over
`pg_catalog.pg_class` for a detached table) must then find the partition. Every statement is built from the
**catalog's** `relname`, quoted as an identifier, and never from the argument text. Only `metrics_raw` children
exist (OQ-21 (2a)), so the lookup's parent is always `sm_metrics.metrics_raw`. **`I6`:** the command reads the
migrator credential from the environment and never prints it: no URL, user or password on stdout or stderr, and none
in an exception message it lets escape (the `SmDataMigrationMain` rule).

- [x] **Step 0: Fill MF1** (`SmMetricsMigratorIT.twoHistoriesInOneRunRecordSeparately`: migrate `sm_data` then
  `sm_metrics` on one database; assert `sm_data.flyway_schema_history` has no `sm_metrics` row and vice versa; fail
  `sm_metrics` V001 on purpose and assert `sm_data`'s work stays committed), **MF15**, **MF20** and **MF21**
  (WebFetch; MF20 is then measured by Step 1's attach test), and **MF23** (measured by Step 1's no-partition and
  non-finite tests). **Re-read the pins at the landed state** ([Prerequisite 7](#prerequisites) settled
  them on 2026-10-05, and plan 010's V011–V014 will have moved some since): (1) `SketchMirrorTest`: it mirrors V001–V006
  only and carries later Phase 0 statements in `PHASE0_AFTER_THE_SKETCH`; (2) `SmDataBootstrapIT`: the Phase 0 count, the
  migration count and list, the table, function and trigger sets; (3) the unfiltered history lookup (`:183-187`). Record
  what landed in the commit and follow T2.4's convention for this task's two Phase 0 statements. **If either file is red
  on arrival**, stop and ask the integrator: fixing plan 010's drift is not this task's. Read every caller of
  `SmDataMigrationMain.run` and `exitCode` (the list above). A caller not on the list, or one that asserts a total,
  stops the task.
- [x] **Step 1: Failing tests**, all as `sm_dp_runtime` over Phase 0 + both migrations:

```java
@Test void phase0CreatesSmMetricsOwnedBySmDpOwnerWithRuntimeUsageOnly() { }
@Test void theRuntimeRoleCannotCreateDropOrAlterAnythingInSmMetrics() { }            // 42501
@Test void theRuntimeRoleCanExecuteTheRunwayFunctionAndNothingElseOwnerLevel() { }   // MF2
@Test void publicCannotExecuteTheRunwayFunction() { }                                 // MF2: REVOKE … FROM PUBLIC
@Test void theFunctionIgnoresPlantedCatalogShadows() {
    // As sm_dp_runtime (TEMP comes through PUBLIC; Phase 0 does not revoke it): CREATE TEMP TABLE pg_inherits (…)
    // and pg_class (…) with rows that would make the runway look a year long. Call the function. Assert the returned
    // runway and the attached children (read by the test through pg_catalog.*) are unchanged (MF2, MF21).
}
@Test void theFunctionSearchPathEndsWithPgTemp() { }        // pg_proc.proconfig holds search_path=sm_metrics, pg_temp
@Test void theFunctionBodyQualifiesEveryCatalogRelation() { } // pg_proc.prosrc: no unqualified pg_inherits / pg_class
@Test void theRunwayIsThisMonthPlusThreeAndIdempotent() { }
@Test void partitionNamesFollowTheClosedGrammarOnly() { }                             // ^metrics_raw_\d{4}_\d{2}$ (OQ-21 (2a))
@Test void theHourlyTierIsUnpartitioned() { }                                         // pg_class.relkind = 'r' for metrics_rollup_1h
@Test void attachTakesNoAccessExclusiveOnTheParent() {
    // hold a ROW EXCLUSIVE insert transaction open on metrics_raw AND an open INSERT … ON CONFLICT on sm_metrics.series
    // (ATTACH clones the partition's FK to series, MF20); ensure_metric_partitions() still returns within lock_timeout,
    // or raises lock_not_available and is retried. Record the lock pg_locks shows on series, and whether the
    // runway_degraded path was taken (MF4, MF20)
}
@Test void everySmMetricsForeignKeyTargetsSmMetricsOrSmDataPlacementOnly() { }  // pg_constraint confrelid; none in sm_control (I7, M-Q19)
@Test void aCounterRollupRowWithNullDeltaIsAccepted() { }      // rollup_1h_type_shape: counter, delta NULL, covered_seconds NULL
@Test void aRollupRowWhoseTypeDiffersFromItsSeriesIsRefused() { }  // the typed FK (23503)
@Test void aGaugeRollupRowWithADeltaIsRefused() { }            // 23514
@Test void aMalformedOrInjectedMonthArgumentIsRefusedBeforeAnySql() {
    // MetricsPartitionArgumentTest: "2026_13", "2026-01", "2026_01; DROP TABLE x", "2026_01\n", "../x", "" — each
    // refused by parse(); a spy DataSource sees no connection opened
}
@Test void aWellFormedMonthWithNoPartitionIsRefusedByTheCatalogLookup() { }   // MetricsPartitionMainIT
@Test void aLockTimeoutIsRetriedThenRaisedAndNeverCorruptsTheRunway() { }
@Test void anInsertWithNoPartitionIsRefusedNotRoutedToADefault() { }                  // no DEFAULT partition; MF23: record SQLSTATE + constraint name
@Test void aBootWithNoRunwayStillStarts() { }        // Review Focus 5: readiness does not require runway
@Test void theRuntimeRoleCannotDeleteOrUpdateRawFacts() { }                           // 42501
@Test void aNonFiniteValueIsRefused() { }                                             // raw_value_finite; MF23: record SQLSTATE + constraint name
@Test void theRoutingFailureAndTheFiniteCheckAreDistinguishable() { }                 // JdbcRawSampleRepository's classifier: RUNWAY vs CHECK
@Test void aSeriesMustReferenceALivePlacementRowOfTheSameTenant() { }                 // series_placement FK (23503)
@Test void everyStatementShapeIsTenantScoped() { }                                    // tenant B cannot read tenant A by id
@Test void theRuntimeRoleVacuumIsSkippedNotApplied() { }                              // MF3 pinned: WARNING, no freeze
@Test void freezeDetachArchiveDropRunInOrderAndDropRefusesWithoutAManifest() { }     // MetricsPartitionMainIT
@Test void freezeRecordsTheMonthInTheFreezeLog() { }                                  // the runtime can SELECT it, not INSERT (42501)
@Test void statusPrintsTheByteFiguresAndTheApiNeverDoes() { }                        // OQ-16's scope rule
@Test void detachIsConcurrentAndNeverRunsInsideATransaction() { }
@Test void migrationMainPrintsNoUrlUserOrPasswordForEitherHistory() { }              // SmDataMigrationMain's I6 rule, twice
@Test void partitionMainPrintsNoUrlUserOrPassword() {
    // MetricsPartitionMainIT, every subcommand, success AND failure (a bad password, an unreachable URL): capture stdout,
    // stderr and every escaping exception's message and cause chain; none contains the URL, the user or the password
}
// SmDataBootstrapIT (the pin file):
@Test void flywayRecordsSmDataHistoryInSmDataAndSmMetricsHistoryInSmMetrics() { }    // replaces the unfiltered relname lookup
```

- [x] **Step 2: Run; all fail.** **Step 3: Implement** V001, the migrator, readiness (tables + function +
  `has_function_privilege('sm_dp_runtime', …, 'EXECUTE')`), `JdbcPartitionRunwayRepository.ensure()` (`SELECT
  sm_metrics.ensure_metric_partitions()`, bound, no text), and `MetricsPartitionMain`. Append the two Phase 0 statements
  to `PHASE0_AFTER_THE_SKETCH` and move the pins Step 0 recorded (no design-document edit). Salvage the orphan V001's
  constraint cases into `SmMetricsSchemaIT`; delete the orphan files; update `backend/README.md`.
- [x] **Step 4: `VERIFY dp-core`; `VERIFY launcher`** (readiness on the real boot); **`VERIFY arch-tests`**
  (`MetricsPartitionMain` sits in `dp.apply.persistence`, and JDBC stays confined).
- [x] **Step 5: Prove they fire.** The shadowing guard has **two layers**, and each is proved on its own:
  - Drop `pg_temp` from `search_path` **only** → `theFunctionSearchPathEndsWithPgTemp` goes red.
    `theFunctionIgnoresPlantedCatalogShadows` stays green, because the qualification still protects. Record both.
  - Unqualify `pg_inherits`/`pg_class` **only** → `theFunctionBodyQualifiesEveryCatalogRelation` goes red.
    The behavioural test stays green, because `pg_temp` is last. Record both.
  - Do **both** → `theFunctionIgnoresPlantedCatalogShadows` goes red (MF21: unlisted `pg_temp` is searched first for
    relations). **If it stays green, the planted test does not reach the attack, and that is a finding (`L22`).**

  Omit the `REVOKE … FROM PUBLIC` → the PUBLIC test goes red. Add a `DEFAULT` partition → the no-default test goes
  red. Grant `DELETE` on `metrics_raw` → red. Make readiness require runway → `aBootWithNoRunwayStillStarts` goes red.
  Restore the old nullability-only `rollup_1h_type_shape` → `aCounterRollupRowWithNullDeltaIsAccepted` goes red. Let
  `MetricsPartitionMain` interpolate the argument (skip `parse`) → the malformed-month test goes red. Add an FK from
  `series` to `sm_data.instance_state` → the FK-target test goes red. Print the password in `MetricsPartitionMain`'s
  connection-failure message → `partitionMainPrintsNoUrlUserOrPassword` goes red. Restore `SmDataBootstrapIT`'s
  unfiltered lookup → it throws on two rows (record that it does). Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): sm_metrics — own Flyway history, append-only facts, the owner-owned partition runway (M-Q13, M-Q19)`

**Results (P11-2.1, 2026-10-05; executed on `p011/p21`, lane commit `acb59d2`, cherry-picked onto `p011/merge`).** `sm_metrics` has its own Flyway history. Red first: surefire 22 of 31 and failsafe 44 of 52 failing before the implementation. `dp-core verify`: surefire **383**, failsafe **564**. **16 named mutations, each red (the lane reported 19; corrected 2026-10-05 at the wave review, see Results):** `pg_temp` removed from the pinned `search_path`; unqualified catalog names; the `REVOKE` from `PUBLIC` removed; a default partition created after the seed; `GRANT DELETE` on raw facts; readiness that does not require the runway; a nullable rollup delta; a skipped parse; a foreign key to `instance_state`; a printed password; an unfiltered history lookup; `freeze` that skips `VACUUM`; `detach` without `CONCURRENTLY`; `drop` that skips verification; an archive without the tenant filter; a runtime `INSERT` on the freeze log (the lane reported 19 and named these sixteen; the other three are not named in the report and are **not** claimed here, L22). **Measured facts filled:** **MF1** (Flyway 11.7.2: two separate histories in one run; idempotent; a failing second migration leaves the first committed), **MF15** and **MF21** (PostgreSQL 14 documentation), **MF20** (`ATTACH` takes `SHARE ROW EXCLUSIVE` on the series table), **MF23** (both failure paths raise `23514`). **Decisions made in the task:** the archive is per tenant by a bound cursor (OQ-27 (b)); `drop` verifies every tenant's manifest and takes a `<dir>` argument; the `V001` reason `CHECK` comma was fixed; `SmDataMigrationMain` prints a second line for `sm_metrics`. **Plan 010 files touched (four lines, the integrator authorizes them):** `ApplyPersistenceConfigurationIT.freshDatabase` and `DataPlaneConfigurationTest`. OQ-20 stays open and `V002` is reserved; `SM_METRICS_DISK_BUDGET_BYTES` is an environment variable here and P11-3.2 owns the real property. **Merge note:** the cherry-pick conflicted on `DataPlaneConfiguration` and `DataPlaneConfigurationTest` because plan 010 had since added `ObservedStateConfiguration`; resolved as the union of both import lists, no assertion dropped. Not yet wave-reviewed (`OR-97`: finished tasks take their wave review and full build before they merge and push).


### Task P11-2.2 — `sm_data` V015: per-version definitions, host-side metrics ports, active lists

> **Owner rulings applied (`OR-92`, `OR-93`).** **OQ-1 (a):** `metric_restart_signal` is per component, optional. **OQ-23 (a):** no cross-organization host-port guard; the rule is a runbook line (P11-4.4). **OQ-28 (b), `OR-93`:** may be **developed** early, but
> **merges only after plan 010 T11.2's `V014`** (version order kept); local until its wave review.

**Wave:** MW2. **Depends on:** plan 010 T2.1 and T5.2 (V010) (both ✅ landed), P11-1.1 (the source shapes; the `source` column
is opaque `jsonb` to this task). **Its merge waits for plan 010 T11.2 (`V014`, W11)**, so that V015 sits directly above plan
010's V014 and plan 010's version-order rule holds (⚠️ corrected 2026-10-05: S6's `V017` is expected to merge first, so this ordering does not hold, see the allocation section; plan 008 renumbers to V018+,
[Migration allocation](#migration-and-id-allocation)); unless **OQ-28 (c)** is ruled. Under OQ-28 (b) it may be **developed** earlier
([per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)). **Parallel with:** P11-2.1, P11-2.3 for development; **merges after P11-2.1** and rebases onto it before
it edits the shared pin files.

**Files:** Create `backend/dp-core/src/main/resources/db/migration/sm_data/V015__metric_definitions_and_endpoints.sql`;
Modify (as the **second** owner, after rebasing onto P11-2.1's merge) `…/persistence/SmDataBootstrapIT.java` **only**
(the migration count and list gain `015`; the table count gains five; the function and trigger lists gain V015's). **No
`SketchMirrorTest` edit and no `eda-walking-skeleton.md` edit**: the mirror covers V001–V006 only
([Prerequisite 7](#prerequisites));
Create `…/dp/apply/persistence/{JdbcMetricDefinitionRepository,MetricDefinitionPersistenceConfiguration}.java` and
`…/persistence/MetricDefinitionSchemaIT.java`; Modify `…/dp/DataPlaneConfiguration.java` (**one** `@Import` line for
`MetricDefinitionPersistenceConfiguration`); Create
`backend/dp-api/src/main/java/com/solace/sm/dp/api/metrics/declare/{MetricsDeclaration,MetricDefinitionSpec,RestartSignalSpec,PackageVersionMismatchException,MetricsPortCollisionException}.java`.

`MetricDefinitionPersistenceConfiguration` registers `JdbcMetricDefinitionRepository` and carries its **own**
readiness `InitializingBean` (plan 010's per-configuration rule). The check requires the five V015 tables and their
two digest triggers, and refuses to start with a message naming the missing migration. It does **not** share P11-2.1's
`MetricsPersistenceConfiguration`, which is created in parallel.

```sql
-- M-Q5 (a′): a package version is immutable once loaded; its metric definitions are copied ONCE per
-- (tenant, solution, version), INSERT-if-absent, never UPDATE, in declare's reserve transaction (T4.4 pattern).
CREATE TABLE sm_data.package_version_digest (
    tenant_id uuid NOT NULL, catalog_solution_id uuid NOT NULL, package_version text NOT NULL,
    package_digest text NOT NULL CHECK (package_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, catalog_solution_id, package_version));
CREATE TABLE sm_data.metric_definition (
    tenant_id uuid NOT NULL, catalog_solution_id uuid NOT NULL, package_version text NOT NULL,
    metric_name text NOT NULL CHECK (metric_name ~ '^[a-z][a-z0-9_.]{0,127}$'),
    component_type text NOT NULL,
    metric_type text NOT NULL CHECK (metric_type IN ('counter','gauge','histogram')),
    unit text NOT NULL CHECK (length(unit) BETWEEN 1 AND 64),
    source_kind text NOT NULL CHECK (source_kind IN ('http_json','solace_event','prometheus','exec','jdbc_query')),
    source jsonb NOT NULL,                              -- the grammar-checked source, as P11-1.1 defines it
    interval_seconds integer NULL CHECK (interval_seconds BETWEEN 10 AND 3600),
    default_chart boolean NOT NULL,
    package_digest text NOT NULL,
    PRIMARY KEY (tenant_id, catalog_solution_id, package_version, metric_name),
    FOREIGN KEY (tenant_id, catalog_solution_id, package_version)
        REFERENCES sm_data.package_version_digest (tenant_id, catalog_solution_id, package_version));
CREATE TABLE sm_data.metric_restart_signal (           -- M-Q9 (d′) / OQ-1 (a): per component, optional
    tenant_id uuid NOT NULL, catalog_solution_id uuid NOT NULL, package_version text NOT NULL,
    component_type text NOT NULL, source jsonb NOT NULL, package_digest text NOT NULL,
    PRIMARY KEY (tenant_id, catalog_solution_id, package_version, component_type),
    FOREIGN KEY (tenant_id, catalog_solution_id, package_version)
        REFERENCES sm_data.package_version_digest (tenant_id, catalog_solution_id, package_version));
-- BEFORE INSERT on both child tables: NEW.package_digest must equal the recorded digest, else 23514
-- ("package version content differs") — the DB half of "a digest mismatch fails closed".

-- M-Q14 (a): immutable host-side ports per placement; re-target mints new rows with the new placement.
ALTER TABLE sm_data.placement ADD CONSTRAINT placement_target_key UNIQUE (tenant_id, placement_id, target_id);
CREATE TABLE sm_data.placement_metrics_endpoint (
    tenant_id uuid NOT NULL, placement_id uuid NOT NULL, target_id uuid NOT NULL,
    source_port integer NOT NULL CHECK (source_port BETWEEN 1 AND 65535),
    host_port integer NOT NULL CHECK (host_port BETWEEN 1 AND 65535),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, placement_id, source_port),
    CONSTRAINT placement_metrics_host_port_once UNIQUE (tenant_id, placement_id, host_port),
    FOREIGN KEY (tenant_id, placement_id, target_id) REFERENCES sm_data.placement (tenant_id, placement_id, target_id));
-- placement_metrics_host_port_once: one declare may not map two source ports to one host port ({8081: 28081, 9090: 28081}
-- would scrape one of them on the wrong container port). It is the within-placement half of M-Q14's "(tenant,target,host_port)
-- unique among live placements"; the trigger below is the cross-placement half and exempts the placement's own rows.
-- SCOPE: one target, hence one tenant (target_connection is tenant-bound). Under OR-89 two organizations may register the
-- same machine as two targets, and nothing here (or in plan 010's placement_health_port_once) stops them naming one host
-- port: OQ-23.
-- BEFORE INSERT trigger on placement_metrics_endpoint: lock the target_connection row FOR UPDATE (serializes
-- declares per target), then refuse (23505-class SQLSTATE, message naming the CLASS, never the other instance) when
-- host_port equals (a) a metrics host_port of ANOTHER live placement on the target, or (b) the health_port of ANOTHER
-- live placement on the target. The same placement may share its own health port (Cache: 8081 serves both).
-- A second BEFORE INSERT trigger on sm_data.placement takes THE SAME target_connection FOR UPDATE lock FIRST, then
-- refuses a new placement whose health_port equals another live placement's metrics host_port. Without that lock
-- this race gets through: B inserts its placement (health 28081) and sees no metrics port 28081; A then inserts
-- endpoint 28081 and cannot see B's uncommitted placement; both commit. With both triggers taking the one row lock,
-- the second declare on the target waits for the first to commit and then sees its rows (READ COMMITTED re-reads).
-- In one declare the placement insert locks first and the endpoint insert re-locks the same row: no inversion.
-- "Live" = placement.retired_at IS NULL, read at insert time: no copied column, no UPDATE (M-Q14, M-Q5's concern).

-- M-Q15 (a): the active metric-name list, born with its generation, never edited. generation 0 = the declare-time set.
CREATE TABLE sm_data.generation_active_metrics (
    tenant_id uuid NOT NULL, instance_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation >= 0),
    metric_names text[] NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, instance_id, generation),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.instance_state (tenant_id, instance_id));
-- No FK to config_generation: retention (T5.2) may delete an old generation. The one the collector uses (the newest
-- that reached restart-observed) is journal-referenced, so OR-50's delete trigger already protects it.

GRANT SELECT, INSERT ON sm_data.package_version_digest, sm_data.metric_definition, sm_data.metric_restart_signal,
      sm_data.placement_metrics_endpoint, sm_data.generation_active_metrics TO sm_dp_runtime;
```

```java
package com.solace.sm.dp.api.metrics.declare;
/** What the gateway resolved from the CP package at declare (M-Q5, M-Q14, M-Q15). The DP never reads a package. */
public record MetricsDeclaration(String packageDigest, List<MetricDefinitionSpec> definitions,
        List<RestartSignalSpec> restartSignals, Map<Integer, Integer> metricsPorts /* sourcePort -> hostPort */,
        List<String> activeMetricNames /* generation 0 */) {}
public record MetricDefinitionSpec(String metricName, String componentType, String metricType, String unit,
        String sourceKind, String sourceJson, Integer intervalSeconds, boolean defaultChart) {}
public record RestartSignalSpec(String componentType, String sourceJson) {}
```

- [x] **Step 0: Read the landed lock order on `target_connection`** (plan 010 T2.1's triggers and T6.x's updates):
  a trigger that locks `target_connection` must never invert an order a plan 010 path takes, or two declares deadlock
  where they used to serialize. Record it. **Measured at `bd802fc` (V007):** no path takes a `target_connection` row
  lock today. `placement_inside_target_envelope` (BEFORE INSERT on `placement`, `V007…sql:200-225`) reads the target
  **without** a lock; `target_not_retired_under_live_placements` (BEFORE UPDATE on `target_connection`, `:229-239`) reads
  `placement` without a lock and runs under the row lock its own `UPDATE` already holds; the runtime role holds
  `UPDATE (host, port, ssh_user, host_key_fingerprint, credential_ref, retired_at)` on `target_connection`, so
  `SELECT … FOR UPDATE` is permitted to it. So V015's two locks are the **first** `target_connection` row locks, and the
  order `instance_state` → `placement` insert → `target_connection FOR UPDATE` → endpoint inserts has no inverse in
  plan 010 as landed; **re-read it after T6.x/T7.x/T8.x/T11.2 land**. Read P11-2.1's merged pin-file convention
  (single-owner table).
- [x] **Step 1: Failing `MetricDefinitionSchemaIT`** (as `sm_dp_runtime`):

```java
@Test void definitionsAreWriteOncePerPackageVersion() { }                    // INSERT-if-absent; UPDATE is 42501
@Test void aSecondDeclareOfTheSameVersionWritesNothingNew() { }              // idempotent retry (M-Q5)
@Test void aDigestMismatchForAKnownVersionIsRefused() { }                    // 23514; M-Q5's new rule
@Test void aFailedDeclareRollsBackItsDefinitionsWithThePlacement() { }       // same transaction as reserve
@Test void aMetricsHostPortTakenByAnotherLivePlacementOnTheTargetIsRefused() { }   // Review Focus 1
@Test void aMetricsHostPortEqualToAnotherLivePlacementsHealthPortIsRefused() { }
@Test void aPlacementMayShareItsOwnHealthPortWithItsMetricsPort() { }        // Cache: 8081 for both
@Test void aPlacementCannotMapTwoSourcePortsToOneHostPort() { }              // {8081: 28081, 9090: 28081} → 23505 on placement_metrics_host_port_once
@Test void aNewPlacementsHealthPortEqualToAnotherLiveMetricsPortIsRefused() { }
@Test void aRetiredPlacementFreesItsMetricsPorts() { }
@Test void twoConcurrentDeclaresOnOneTargetCannotBothTakeOnePort() { }       // two threads, a latch; exactly one wins
@Test void aConcurrentHealthPortAndMetricsPortOnOneTargetCannotBothCommit() {
    // Both directions, each with a latch: (i) B's placement (health 28081) inserted and held open, then A's endpoint
    // (host 28081); (ii) A's endpoint held open, then B's placement. In each order exactly one declare commits
}
@Test void anEndpointsTargetMustBeItsPlacementsTarget() { }                  // placement_target_key FK
@Test void theActiveListIsImmutable() { }                                    // UPDATE 42501
@Test void noRowNamesAnotherTenant() { }                                     // tenant-bound reads
@Test void theCollisionMessageNamesTheClassNeverTheOtherInstance() { }
@Test void readinessRefusesASchemaWithoutV015() { }                          // MetricDefinitionPersistenceConfiguration's own check
```

- [ ] **Step 2: Run; fail.** **Step 3: Implement** the migration, `JdbcMetricDefinitionRepository`
  (`insertDefinitionsIfAbsent`, `insertEndpoints`, `insertActiveMetrics`, `definitionsFor(tenant, solution,
  version)`, `endpointsFor(tenant, placement)`, `activeMetricsFor(tenant, instance, generation)`), every statement
  bound, and `MetricDefinitionPersistenceConfiguration` with its readiness check.
- [x] **Step 4: `VERIFY dp-core`; `VERIFY launcher`** (the readiness check on the real boot); **`VERIFY arch-tests`.**
- [x] **Step 5: Prove they fire.** Drop the `FOR UPDATE` in the endpoint trigger → the concurrency test goes red
  (report how many of 20 interleavings broke; if none did, force the race with a latch). Drop the lock from the
  **placement-side** trigger only → `aConcurrentHealthPortAndMetricsPortOnOneTargetCannotBothCommit` goes red in
  order (i). Compare digests in the
  service only → the DB-side mismatch test goes red. Drop `placement_metrics_host_port_once` → `aPlacementCannotMapTwoSourcePortsToOneHostPort` goes red. Exempt the same placement from the check → still green, as
  required; exempt **all** placements → red. Drop one table from the readiness list → `readinessRefusesASchemaWithoutV015`
  goes red. Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): per-version metric definitions, host-side metrics ports and per-generation active lists (M-Q5, M-Q14, M-Q15)`

**Results (P11-2.2, 2026-10-06; executed on `p011/p22` off `p011/p111`, Sonnet 5.5; not yet wave-reviewed).** The migration is `V016__metric_definitions_and_endpoints.sql`: the lane built it as the placeholder `V021` for this plan's `V015`, and the wave-2 integrator renumbered it **V016** (the next free `sm_data` number, behind OR-108's `V015`), re-pinned it in `CHECKSUMS.sha256` and moved the pins that list it (`SmDataBootstrapIT`, `SmMetricsMigratorIT`, `RefusalShapesIT` and the messages in `ApplyPersistenceConfigurationIT`). Built: five tables, `placement_target_key`, three trigger functions (**one** shared digest guard for both child tables, since both name the same four columns; one port guard per direction), `JdbcMetricDefinitionRepository` and `MetricDefinitionPersistenceConfiguration` (its own readiness, imported by one line in `DataPlaneConfiguration`), and five `dp-api` types. Every CHECK is **named** (nine of them), as the sm_data convention requires; the plan's SQL left them unnamed. **Red first:** `MetricDefinitionSchemaIT` 21 of 21 failing (14 errors, mostly `UnsupportedOperationException` from the stub or a missing relation, and 7 failed assertions). **Green:** `MetricDefinitionSchemaIT` 22 of 22; `dp-core` surefire 579; the 13 neighbouring ITs 216 of 216 (`SmDataBootstrapIT`, `ApplyPersistenceConfigurationIT`, `SmMetricsMigratorIT`, `RefusalShapesIT`, `InventorySchemaIT`, `InventoryServiceIT`, `InventoryConfigurationIT`, `DeclareSweepIT`, `DesignerLayoutSchemaIT`, `TargetHostKeyIT`, `HealthPollerIT`, `ConfigRecordServiceIT`, and the new one); `arch-tests` 56; `launcher` `MeshDisabledBootIT`, `InstanceEndpointsIT`, `HealthEndpointsIT` (the readiness check on a real boot) green; no full reactor build. **Step 0 (lock order, measured at `p011/p111`):** unchanged from the plan: no plan 010 path takes a `target_connection` row lock, so the order `instance_state`, placement insert, `target_connection FOR UPDATE`, endpoint inserts has no inverse; re-read it after plan 010's T6.x/T7.x/T8.x/T11.2 land. **Mutations, each red, each reverted:** (M1) `FOR UPDATE` dropped from the endpoint trigger: 3 tests red, and **20 of 20** racing interleavings let two declares take one host port (the race test widens the window with a 40 ms hold; unmutated: 0 of 20); (M2) the lock dropped from the **placement-side** trigger only: red, **but in order (ii), not order (i) as this step predicted**: with the placement-side lock gone, order (i) stays green, because the second declare still waits, plausibly on the `FOR KEY SHARE` the placement's own foreign key takes on the `target_connection` row (inferred from the result, not separately measured); order (ii) goes red, so both orders stay tested; (M3) both digest triggers removed (the digest compared in the service only): red, plus the readiness test; (M4) `placement_metrics_host_port_once` dropped: red; (M5a) the own-placement exemption removed: `aPlacementMayShareItsOwnHealthPortWithItsMetricsPort` red; (M5b) every placement exempted: 9 tests red; (M7) a retired placement not freeing its ports: `aRetiredPlacementFreesItsMetricsPorts` red; (M6, M6b, M6c) a table, a trigger and `metric_definition` removed from the readiness list: each 2 red. **A test defect found by mutation, fixed:** the first readiness test dropped objects cumulatively and derived its expected list from the class under test, so M6 passed; it now loses each of the seven objects alone in a database of its own and holds its own literal list. **Decisions made in the task:** (1) the placement-side trigger takes the lock **unconditionally**, even for a placement with no health probe: the placement's foreign key takes `FOR KEY SHARE` on the target row, and a transaction holding only that that then asks for `FOR UPDATE` at its endpoint insert, while another declare queues, is a deadlock; (2) `insertEndpoints` writes the whole map in **one** statement, so a refused map leaves not even its first row; (3) `insertDefinitionsIfAbsent` compares the recorded digest in the service before writing any child and returns the number of rows newly written (0 on a retry); (4) `insertActiveMetrics` is a plain `INSERT`: a second write for one generation is a `23505` to be seen, not a silent no-op; (5) a `source` that is not JSON is refused in Java before PostgreSQL's class-22 error would quote it. **Handed to P11-4.1:** the collision a **new placement** causes (its health port equals another placement's metrics host port) surfaces from plan 010's `JdbcPlacementRepository.insert` as a raw `PgConstraintViolation` naming `health_port_is_a_metrics_port`; `JdbcMetricDefinitionRepository.collisionOf` maps it, and wiring it into the declare's `409` is that task's. **Files outside the plan's list, each one line or one pin:** `DataPlaneConfigurationTest` (the import set), `ApplyPersistenceConfigurationIT`, `SmMetricsMigratorIT`, `RefusalShapesIT` (CHECK count 67 to 76). OQ-3, OQ-5, OQ-20 and OQ-29 are untouched; the tables are not read by anything yet.

### Task P11-2.3 — `sm_control` V014: dashboards, and the Operator's two verbs (M-Q16)

> **Owner rulings applied (`OR-93`).** **Starts after the sitting** (OQ-28 (b)): it needs nothing beyond plan 010 S2 (`V013` landed), and plan 010 allocates no later `sm_control` number. Local until its wave review; P11-7.1 follows it.

**Wave:** MW2. **Depends on:** plan 010 T2.0 (V013, ✅ landed; V014 is this task's, [Migration allocation](#migration-and-id-allocation)) and **OQ-28 (b)**; nothing more from plan 010 than S2 ([per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why); under OQ-28 (a): W11). **Parallel with:** P11-2.1, P11-2.2.

**Files:** Create `backend/cp-core/src/main/resources/db/migration/sm_control/V014__dashboards_and_operator_metrics_verbs.sql`;
Modify `…/sm_control/CHECKSUMS.sha256`, `cp-core/.../persistence/ControlPlanePersistenceConfiguration.java` (readiness
lists), `cp-core/.../identity/AuditOperations.java`, `cp-core/src/test/java/com/solace/sm/cp/authz/BuiltInRoleSeedIT.java`
(`:55-68`); Create `cp-core/.../persistence/DashboardSchemaIT.java`.

```sql
-- The DB binds a dashboard's organization to its instance's organization (I5 then never rests on the service alone).
-- sm_control.instance's PK is (organization_id, organization_unit_id, environment_id, solution_id, instance_id)
-- (V001__scope.sql:46-53) and OR-27 makes instance_id unique (T2.0), so this key is implied and adds no new rule.
ALTER TABLE sm_control.instance ADD CONSTRAINT instance_org_key UNIQUE (organization_id, instance_id);

CREATE TABLE sm_control.dashboard (                     -- M-Q10 (a): per instance, shared (SM-D31)
    organization_id uuid NOT NULL REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    dashboard_id text NOT NULL CONSTRAINT dashboard_id_shape
        CHECK (dashboard_id ~ '^db_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    scope_kind text NOT NULL CONSTRAINT dashboard_scope_v1 CHECK (scope_kind = 'instance'),   -- widen later without re-keying
    instance_id uuid NOT NULL,
    document jsonb NOT NULL CONSTRAINT dashboard_size CHECK (octet_length(document::text) <= 65536),
    revision bigint NOT NULL CHECK (revision > 0),
    updated_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (organization_id, dashboard_id),
    CONSTRAINT dashboard_instance_same_org FOREIGN KEY (organization_id, instance_id)
        REFERENCES sm_control.instance (organization_id, instance_id) ON DELETE RESTRICT,
    CONSTRAINT dashboard_one_per_instance UNIQUE (instance_id),
    CONSTRAINT dashboard_id_is_instance CHECK (dashboard_id = 'db_' || instance_id::text));

-- M-Q16 (b) `OR-80`: supersedes D10-G2-APPROVAL-2026-09-15.md §1.8 limitation 4 IN PART (a new decision; the
-- approval text is not edited). The Operator (…0003) gains exactly these two verbs.
INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES
    ('00000000-0000-4000-8000-000000000003', 'metrics.export'),
    ('00000000-0000-4000-8000-000000000003', 'metrics.forceRaw');
```

⚠️ **Orphan cleanup (`OR-90`).** Both dashboard foreign keys are `ON DELETE RESTRICT`. Plan 010's operator runbook for an orphaned governance node (`docs/runbooks/operations.md`, on `p010/merge3`, `28c035f`) deletes the orphan's `sm_control.instance` row in its step 5, which would fail with `23503` if a dashboard had been written for that instance id (an organization-level `dashboard.edit` holder and a path-derived `dashboardId` are enough). So the **write path refuses an instance the DP does not report as active** (P11-7.2, `findActive`), and the runbook's step 4 should also check `sm_control.dashboard` (hand-off §B, plan 010's file).

In the same file: `CREATE OR REPLACE FUNCTION sm_control.record_change_audit()`, **V013's body verbatim** (plan 010
T2.0) with exactly these edits: (1) the OR-25 allow-list gains `DASHBOARD_WIDGET_SAVED`, `DASHBOARD_LAYOUT_SET`,
`DASHBOARD_WIDGET_HIDDEN` and `DASHBOARD_RESET`; (2) the `row_key` `CASE` gains `dashboard` (`dashboard_id`); (3) for
`dashboard`, `document` is stripped from `new_values` and replaced by its `sha256` (the T2.0 `solution_package`
pattern, m9). Widen the `change_audit_audit_operation` CHECK; attach `change_audit_trigger` and `truncate_refused` to
`dashboard`. Grants: `GRANT SELECT, INSERT, UPDATE (document, revision, updated_by, updated_at) ON sm_control.dashboard
TO sm_cp_runtime`. The migrator's `role_permission` insert is audited with NULL context (V012's rule for non-runtime
roles, `V012__audit_fail_closed.sql:78-90`).

- [x] **Step 1: Failing tests** (`DashboardSchemaIT` as `sm_cp_runtime`; `BuiltInRoleSeedIT` rewritten):

```java
@Test void operatorHoldsExactlyTheTwentyOnePermissionsAfterOrMQ16() { }   // replaces operatorHoldsExactlyTheNineteenApprovedPermissions
@Test void metricsExportAndForceRawAreNowOperatorVerbs() { }             // replaces the two names in administratorOnlyPermissionsAreAbsentFromOperator
@Test void viewerIsUnchanged() { }
@Test void oneDashboardPerInstanceAndItsIdIsTheInstances() { }
@Test void onlyInstanceScopeIsLegalInV1() { }
@Test void aDashboardForAnUnknownInstanceIsRefused() { }                 // 23503 to sm_control.instance
@Test void aDashboardNamingAnotherOrganizationsInstanceIsRefused() { }   // dashboard_instance_same_org (23503)
@Test void everyDashboardWriteIsAuditedWithADigestNotTheDocument() { }
@Test void aRuntimeDashboardWriteWithoutAuditContextIsRefusedOR025() { }
@Test void aDocumentOver64KiBIsRefused() { }
@Test void anInstanceWithADashboardCannotBeDeletedUntilTheDashboardIs() { }  // ON DELETE RESTRICT, 23503: the orphan-cleanup interaction above
@Test void readinessRefusesASchemaWithoutThisMigration() { }
```

- [x] **Step 2: Run; fail.** **Step 3: Write the migration, pin the checksum, extend readiness.** **Step 4: `VERIFY
  cp-core`; `VERIFY launcher`; `./contracts/run-checks.sh --strict`** (frozen migrations stay byte-identical).
- [x] **Step 5: Prove they fire.** Drop the `dashboard` `row_key` branch → red. Keep `document` in the audit row →
  the digest test goes red. Insert only `metrics.export` → the 21-permission test goes red. Reference `sm_control.instance (instance_id)` alone → `aDashboardNamingAnotherOrganizationsInstanceIsRefused` goes red. Revert each.
- [x] **Step 6: Commit.** `feat(cp-core): sm_control dashboards, audited; the Operator gains metrics.export and metrics.forceRaw (M-Q10, M-Q16)`

**Results (P11-2.3, 2026-10-05; lane commit `ae2e954`, cherry-picked onto `p011/merge`).** `sm_control` `V014` (dashboards, the audit-function edits, `metrics.export` and `metrics.forceRaw` for the Operator, 19 to 21 verbs), the `CHECKSUMS` pin and the third readiness stage. Red first: Flyway reported no migration 014. Mutations, each red: drop the `dashboard` `row_key` branch; keep the `document` in the audit row; grant only one Operator verb; reference `instance(instance_id)` alone; remove the `V014` readiness call. Step 7 (the frozen-anchor append) is deliberately **not** done: it follows the push, and `backend/scripts/frozen-migration-anchors.txt` is not touched here. Not yet wave-reviewed.

- [x] **Step 7 (after push, follow-up commit):** ✅ 2026-10-05: V014 first reached origin in `66ab713`; that SHA is appended. append this commit's SHA to `backend/scripts/frozen-migration-anchors.txt`.

---

## MW3 — Ingest and rollups

### Task P11-3.1 — `TelemetryIngestPort`: identity (with the I1 check), counter semantics, caps, buffer, counters

> **Owner rulings applied (`OR-92`).** **OQ-12 (a):** `CollectorReason.OUT_OF_ORDER`, for a sample older than its series' last (dropped, counted). **OQ-15 (a):** `CollectorReason.AMBIGUOUS_PLACEMENT`. Both are written unconditionally here, so P11-5.1 does not edit the enum. **OQ-22:** D-2 (`DO NOTHING` on a duplicate), D-3 (derived gaps) and D-4 accepted. OQ-20 (where a placement-less push drop is counted) stays OPEN (`OR-95`).

**Wave:** MW3. **Depends on:** P11-2.1, P11-2.2; OQ-12, OQ-15 (the reason list); MF16. **Parallel:** ⛔ P11-3.2
follows in the same agent.

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/metrics/ingest/{TelemetryIngestPort,IngestBatch,IngestSample,IngestResult,SourceDiscriminator,RestartDetection,GapMarker,CollectorReason}.java`
(`CollectorReason` carries **every reason the sitting ruled** (`OR-92`): `OUT_OF_ORDER` (OQ-12 (a)) and `AMBIGUOUS_PLACEMENT`
(OQ-15 (a)), so P11-5.1 does not edit it);
Create `backend/dp-core/src/main/java/com/solace/sm/dp/metrics/ingest/{IngestService,SeriesResolver,CounterSemantics,IngestBuffer,IngestWriter,IngestProperties,MetricsIngestConfiguration}.java`,
`…/dp/metrics/{MetricsConfiguration,MetricsDbBudget,MetricsDbProperties}.java`; Create `…/dp/apply/persistence/{JdbcSeriesRepository,JdbcRawSampleRepository,JdbcIngestCounterRepository,JdbcRestartMarkerRepository,IngestPersistenceConfiguration}.java`;
Create tests `…/dp/metrics/ingest/{CounterSemanticsTest,IngestServiceIT,IngestBufferTest}.java`,
`…/dp/metrics/MetricsDbBudgetTest.java` and the contract kit
`…/dp/metrics/ingest/AbstractTelemetryIngestPortContractTest.java` + `IngestServiceContractTest.java`; Modify
`DataPlaneConfiguration.java` (**three** `@Import` lines: `MetricsConfiguration`, `IngestPersistenceConfiguration`,
`MetricsIngestConfiguration`, [the configuration table](#readiness-and-configuration-per-area)); the landed `sm-data`
pool's `maximumPoolSize` setting **only if** Step 0 finds it unset (the file Step 0 finds, named in the commit).

- [x] **Step 0: Fill MF16** (HikariCP 6.3.3's default `maximumPoolSize`, WebFetch at the tag), read the landed
  `sm-data` pool configuration, and write the arithmetic into the commit: pool = N; query + export ≤ 4 (this
  budget); the ingest writer 1; health, apply and everything else the rest. If the pool is not set explicitly, set
  `maximumPoolSize` from the arithmetic here (configurable), because the budget of 4 is meaningless against an
  unknown pool.

**`MetricsDbBudget` (M-Q22 (6), M-Q19 "bounded query/export connection budget").** This task creates the **one**
budget, so MW6's two parallel tasks inject it and neither builds its own. It is a fair `java.util.concurrent.Semaphore`
with `sm.dp.metrics.db-budget` permits (default **4**, configurable), registered as **one** bean in
`MetricsConfiguration`, beside the **one** shared `metricsExecutor` (a small `ScheduledExecutorService`, default 2
threads, configurable) that P11-3.2's runway keeper and disk alarm run on. `MetricsConfiguration` holds only these
two; no later task edits it. `acquire(Duration wait)` returns an `AutoCloseable` permit or throws `MetricsBusyException`
after the wait. It is a semaphore on the one `sm-data` pool: no second pool and no second credential
(`PlaneBoundaryTest.java:123`'s reason). Ingest and health never take a permit (they are not query or export work).
Tests: `theBudgetDefaultsToFourAndIsConfigurable()`, `aFifthHolderWaitsThenTimesOut()`,
`aPermitIsReleasedOnException()`, `exactlyOneBudgetBeanExists()` (the context holds one `MetricsDbBudget`).

**Interfaces produced** (`dp-api`, JDK types only):

```java
package com.solace.sm.dp.api.metrics.ingest;
/** AGW-D9's seam (PROPOSED), with M-Q7's dp-remote mapping: bindingId := placementId, sourceKey := placementId. */
public interface TelemetryIngestPort { IngestResult ingest(IngestBatch batch); }
public enum SourceDiscriminator { DP_POLL, BROKER_PUSH, AGENT }       // wire: dp-poll | broker-push | agent
public record IngestBatch(UUID organizationId, UUID bindingId, UUID placementId, String sourceKey,
        SourceDiscriminator discriminator, List<IngestSample> samples, List<GapMarker> gapMarkers) {}
/** EXACTLY these components (the I1 check pins it): no type, no unit, no instance, no label from the producer. */
public record IngestSample(String metricName, Instant ts, double value, Instant receivedAt, String restartToken /* null = none */) {}
public record GapMarker(Instant from, Instant to, String reason) {}   // must be empty from DP_POLL in v1 (discovery default D-3)
/** How a source detects a restart (M-Q9 (d′)). DERIVED BY THE DP from its own rows, never taken from the batch:
 *  DP_POLL + via-ssh → DOCKER; DP_POLL + direct-http + a restartSignal declared for the component → DECLARED;
 *  everything else (direct-http with none, BROKER_PUSH, AGENT until the agent plan) → INFERRED. The catalog's
 *  restartDetection (P11-1.2) reports the same value. */
public enum RestartDetection { DECLARED, DOCKER, INFERRED }
public sealed interface IngestResult permits IngestResult.Committed, IngestResult.RetryAfter, IngestResult.Rejected, IngestResult.Pending {
    record Committed(int accepted, Map<CollectorReason, Integer> dropped) implements IngestResult {}
    record RetryAfter(long millis) implements IngestResult {}
    record Rejected(String code) implements IngestResult {}
    /** Reserved for UI-D5 quarantine (M-Q6 `OR-75`). Never returned in v1. */
    record Pending(int held) implements IngestResult {}
}
public enum CollectorReason { SCRAPE_FAILED, PARSE_FAILED, CAP_REJECTED, UNDECLARED_DROPPED,
        DECREASE_WITHOUT_RESTART, RUNWAY_DEGRADED, DEADLINE_MISSED, BUFFER_DROPPED
        , OUT_OF_ORDER, AMBIGUOUS_PLACEMENT   // OQ-12 (a), OQ-15 (a): ruled OR-92 }
```

**What `ingest` does, in one transaction per batch:**
1. Resolve the placement **tenant-bound** from `sm_data.placement` (live), which gives `instance_id`. An unknown or
   retired placement → `Rejected("placement.unknown")`. Resolve the instance's `declared_package_version` and
   `catalog_solution_id`, and that version's `metric_definition` rows.
2. Each sample: an undeclared name → dropped, `UNDECLARED_DROPPED` (M-Q6). A `histogram` → dropped, **not counted**
   (the collector never fetches it; the catalog shows `unsupported`).
3. Resolve or create the series by the eight-part identity (`INSERT … ON CONFLICT (series_identity) DO NOTHING`,
   then select). `metric_type`/`unit` come **from the definition**, `labels_fingerprint` is sha256 of the empty
   canonical map (discovery default D-10: the allowlist is empty in v1), and the discriminator is the batch's. A
   non-finite `value` (NaN or ±Infinity, for example a body number `1e400` that parses to `Infinity`) is dropped as
   `PARSE_FAILED` **here, before any insert**, as defence in depth behind the extractors (P11-4.2, P11-5.1), so it can
   never reach `raw_value_finite` and be mistaken for a runway failure. A new series also gets
   its first `series_segment`. More than **500** series per instance (M-Q24 (10), configurable) → `CAP_REJECTED`.
4. **Counters (M-Q9 (d′) `OR-75`).** Each series is compared with **its own previous sample and the restart
   token seen with that sample** (both in an in-memory cache per series; after a boot, the previous sample falls back
   to the latest raw row and the previous token to the placement's `restart_marker`, the latest observation). The
   placement's `restart_marker` is **only** a record of the latest token observed, advanced on every observation; it
   is never the comparison base. Reason: when one of two counter URLs fails (or its sample is dropped
   `cap_rejected`/`parse_failed`) in the cycle where the token changes, a per-placement base would already have moved
   to the new token, and that series' next sample would be misread as a decrease without a restart. The source's
   **`RestartDetection`** is derived by the DP (the enum's javadoc), fixed by the declaration and the acquisition
   mode, **never** by whether a token arrived this cycle. The rules are applied **in this order, and the first that
   matches wins**:
   1. Detection `DECLARED` or `DOCKER`, the sample carries a token, and it differs from the series' previous token →
      reset: a `series_reset` row (`signal` = the detection), `increment = value` (counted from the restart), **at
      any spacing, even across a gap** (S-D).
   2. Detection `DECLARED` or `DOCKER`, the sample carries a token equal to the previous one, and `value < prev` →
      `increment = NULL` and `DECREASE_WITHOUT_RESTART` +1, **at any spacing** (SM-D30 guard 2, counted now;
      `TYPE_SUSPECT` later). It is never a reset.
   3. Detection `DECLARED` or `DOCKER`, and the sample carries **no** token (the signal failed to fetch this cycle:
      the restart endpoint answered 503, the inspect timed out) → `increment = NULL` whatever the value, the series'
      previous token is **unchanged**, and `SCRAPE_FAILED` +1 (the signal read is a scrape; M-Q21's list stays
      closed). ⛔ **Never an inferred reset**: that would drop guard 2 for the sample while the catalog still reports
      `declared`/`docker`.
   4. Detection `INFERRED`, and `value < prev` → a reset (`signal = 'inferred'`), `increment = value`. Guard 2 is
      dead for this source, as the OR-M-Q9 reconciliation records.
   5. `value ≥ prev`, and spacing ≤ 2 × the interval in force (from the segment) → `increment = value − prev`.
   6. `value ≥ prev`, and spacing > 2 × the interval → `increment = NULL` (a gap: no delta across it, never
      zero-filled).

   The first sample of a series has `increment = NULL`. Gauges: `increment` is always `NULL`. "Spacing" is always
   the **actual** `ts` difference to the previous sample (M-Q24 (8)), never the nominal interval.
5. `INSERT … ON CONFLICT (tenant_id, series_id, ts) DO NOTHING` (discovery default D-2). A `ts` earlier than the series' last →
   `OUT_OF_ORDER` (OQ-12 (a), ruled `OR-92`).
6. A missing partition ("no partition of relation … found for row") is recognised by **the measured SQLSTATE plus
   the absence of a constraint name** (MF23: `raw_value_finite` also raises `check_violation`, `23514`, but names its
   constraint; the routing failure is expected to name none). The classification lives in `JdbcRawSampleRepository`
   (`dp.apply.persistence`, the only package that may read `org.postgresql` types), which returns a typed outcome,
   never message text. If MF23 shows the two are not distinguishable that way, the repository instead checks for an
   attached partition covering `ts` (a bound `pg_catalog` read) before classifying. Either way → the batch is **not**
   lost to an exception. `RUNWAY_DEGRADED` is counted, and it returns `RetryAfter`. The collector-health flag goes
   `degraded`. **Nothing else in the DP is affected.**
7. Counters are upserted per `(tenant, placement, reason, day UTC)`.

**`IngestBuffer` (discovery default D-16, B/D2):** bounded per placement (default 20 ticks, configurable). It holds batches on
`RetryAfter` or a DB error. Past the bound it drops the **oldest** and counts `BUFFER_DROPPED`. A single `IngestWriter`
thread drains it in batches, so it uses one pool connection. An SM restart loses the buffer, which shows as a gap.

**The I1 check (M-Q7's condition), recorded in the commit message and in Results.** Every identity part and stored
value, and where SM gets it:

| Part | Source | MI change needed? |
|---|---|---|
| `tenant_id` | the placement row (SM record) | no |
| `instance_id` | `sm_data.placement.instance_id` (SM record) | no |
| `placement_id` | the placement SM scraped (path 1); on path 2, the instance's single live placement per OQ-15 (a) — an SM record either way | no |
| `metric_name`, `metric_type`, `unit` | the package SM owns (the per-version copy) | no |
| `source_discriminator` | the path SM used | no |
| `labels_fingerprint` | constant in v1 (empty allowlist) | no |
| `package_version` (attribute) | `instance_state.declared_package_version` (SM record) | no |
| `value` | the package-declared pointer into **today's** payload | no |
| restart token | docker inspect (SM's runtime observation) or the package-declared pointer into an **existing** MI endpoint | no |

- [x] **Step 1: Failing tests.**

```java
// The I1 check, as code
@Test void anIngestSampleCarriesExactlyNameTsValueReceivedAtAndRestartToken() { }   // reflect record components
@Test void typeAndUnitComeFromTheDefinitionNeverTheProducer() { }
@Test void theInstanceComesFromThePlacementRowNeverTheBatch() { }
@Test void identityPartsAreAllSmHeld() { }   // a batch naming another tenant's placement is Rejected, and writes nothing
@Test void aRetypeOrUnitChangeUnderANewPackageVersionMintsANewSeries() {
    // S-H: package 2.4 declares cache.size gauge/entries; 2.5 declares it counter, or gauge/bytes. After the instance
    // moves to 2.5 (the fixture sets declared_package_version as superuser; the upgrade operation itself is routed, M-Q5), the same (instance, placement, name) resolves to a NEW series_id, the 2.4 series keeps its rows,
    // and a plain version change with type and unit unchanged keeps the SAME series (a new series_segment only)
}
// Semantics (M-Q9 d′) — Review Focus 2
@Test void sdRestartDuringAnOutageGivesTheRuledIncrease() {
    // The source declares intervalSeconds 180 (so 12:00→12:05 = 300 s ≤ 360 is a delta, 12:05→12:15 = 600 s > 360
    // is a gap). 12:00 10000, 12:05 10050 (token T1); 12:15 300 (token T2). Hour increase == 50 + 300; reset_count == 1.
}
@Test void aDecreaseWithoutASignalChangeIsCountedNotReset() { }        // same numbers and interval, token T1 throughout
@Test void withNoSignalADecreaseIsAnInferredReset() { }                 // direct-http, no restartSignal declared
@Test void aDeclaredSignalThatFailsToFetchNeverBecomesAnInferredReset() {
    // direct-http with a declared restartSignal: 12:00 10000 (T1), 12:00:30 9000 with NO token (the restart endpoint
    // answered 503). Expected: increment NULL, SCRAPE_FAILED +1, no series_reset row, the series' previous token still
    // T1. The same for via-ssh with the inspect timing out (DOCKER)
}
@Test void theDetectionIsDerivedFromDpRowsNeverFromTheBatch() { }       // via-ssh → DOCKER; direct + declared → DECLARED
@Test void oneOfTwoCounterUrlsFailingDuringTheRestartCycleStillSeesTheReset() {
    // A placement whose counters come from two URLs, U1 and U2. The token changes T1 → T2 in cycle k; U2's fetch fails
    // in cycle k (and, in a second case, its sample is dropped cap_rejected). In cycle k+1 U2's counter reads lower
    // than its cycle k−1 value with token T2: expected a RESET for U2's series (its own previous token is T1), never
    // DECREASE_WITHOUT_RESTART. The placement's restart_marker reads T2 after cycle k
}
@Test void anSmRestartLosesTheBufferAsAGapNeverZeros() {
    // S-J's second half: batches held in IngestBuffer, then the IngestService bean is rebuilt (a new context over the
    // same database). The lost interval has no rows and no zeros; the next sample after the restart has increment NULL
    // if its spacing exceeds 2 × the interval; the rollup for that hour has sample_count < expected_count (a gap)
}
@Test void aNonFiniteNumberIsParseFailedNotRunwayDegraded() { }        // value Infinity → PARSE_FAILED; no RetryAfter, no RUNWAY_DEGRADED
@Test void noDeltaIsAttributedAcrossAGapWithoutAReset() { }
@Test void aGapIsNeverZeroFilled() { }
@Test void aGaugeHasNoIncrement() { }
// Caps, drops, duplicates
@Test void anUndeclaredNameIsDroppedAndCounted() { }                     // M-Q6
@Test void theFiveHundredAndFirstSeriesIsCapRejected() { }
@Test void anExactDuplicateIsIgnored() { }                               // discovery default D-2
@Test void gapMarkersFromDpPollAreRefused() { }                          // discovery default D-3
// Degradation — Review Focus 5
@Test void runwayExhaustionDegradesIngestOnly() { }   // drop next month's partition; ingest RetryAfter + runway_degraded; a declare and a health read in the same test still succeed
@Test void aDatabaseOutageBuffersThenDropsOldestAndCounts() { }          // stop the container mid-run (the serialized Testcontainers slot)
@Test void theBufferNeverExceedsItsBound() { }
@Test void pendingIsNeverReturnedInV1() { }
```

  And the contract kit, which a future remote adapter (the agent gateway) must pass:
  `AbstractTelemetryIngestPortContractTest` with `committedIsIdempotentForTheSameBatch`,
  `retryAfterCarriesAPositiveDelay`, `aForeignTenantPlacementIsRejected`, `resultsAreOneOfTheFourPermittedKinds`.
- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY dp-core`; `VERIFY arch-tests`; `VERIFY launcher`** (the new configurations are imported into the production boot).
- [x] **Step 5: Prove they fire.** Take `metricType` from the producer → `typeAndUnitComeFromTheDefinition…` goes red.
  Treat every decrease as a reset → `aDecreaseWithoutASignalChangeIsCountedNotReset` goes red. Key rule 4 on a null
  token instead of on `RestartDetection.INFERRED` → `aDeclaredSignalThatFailsToFetchNeverBecomesAnInferredReset` goes
  red. Compare against the placement's `restart_marker` instead of the series' previous token →
  `oneOfTwoCounterUrlsFailingDuringTheRestartCycleStillSeesTheReset` goes red. Let a non-finite value reach the insert
  → `aNonFiniteNumberIsParseFailedNotRunwayDegraded` goes red (it is misread as `RUNWAY_DEGRADED`). Zero-fill the
  interval an SM restart lost → the buffer-loss test goes red. Carry a delta across a
  gap → `noDeltaIsAttributedAcrossAGap…` goes red. Remove the cap → red. Drop the newest instead of the oldest → the
  buffer test goes red. Let a runway failure escape as an exception → `runwayExhaustion…` goes red. Drop `unit` from
  the identity lookup → the S-H test goes red. Revert each.
- [x] **Step 6: Commit.** `feat(dp): one telemetry ingest port — SM-held identity (I1), ruled counter semantics, bounded buffer, durable counters (M-Q6, M-Q7, M-Q9, M-Q21)`

**Results (2026-10-06, `p011/p3`, Sonnet 5.5).** `TelemetryIngestPort`, its eight `dp-api` types, the ingest service, the shared `MetricsDbBudget`
and `metricsExecutor`, five `Jdbc*` repositories and three configurations are built; three `@Import` lines in `DataPlaneConfiguration`.
**MF16 (filled):** HikariCP 6.3.3's `maximumPoolSize` defaults to **10**, read from the shipped `HikariConfig` bytecode (the constructor's
`bipush 10`), not recalled. The `sm-data` pool did not set it, so it is now explicit and configurable (`sm.data.pool.maximum-size`, default 10;
`SmDataProperties.Pool`). The arithmetic: pool 10 = query + export at most 4 (`sm.dp.metrics.db-budget`) + the ingest writer 1 + health, apply
and everything else 5. **The I1 check** is the table above, pinned by `IngestApiShapeTest` (reflection on `IngestSample`: exactly five
components; `IngestBatch` has no instance component) and by `IngestServiceIT` (type, unit and instance come from the definition and the
placement row; another tenant's batch is `Rejected("placement.unknown")` and writes nothing). **Tests:** unit 32 (`CounterSemanticsTest` 15,
`IngestApiShapeTest` 6, `IngestBufferTest` 4, `MetricsDbBudgetTest` 7) and IT 36 (`IngestServiceIT` 28, `IngestServiceContractTest` 4,
`IngestRunwayIT` 1, `IngestWiringIT` 3), all green; `arch-tests` (32) and the launcher boot ITs (`MeshDisabledBootIT`, `HealthEndpointsIT`,
`ApplyEventsProductionBootIT`) green. **Mutation proofs (each planted, seen red, restored byte-for-byte):** type taken as a constant instead of
the definition: 2 red; every decrease a reset: 3 unit + 3 IT red; rule 4 keyed on a null token: 3 unit + 2 IT red; compare against the
placement marker: 1 IT red (`oneOfTwoCounterUrls...`); a non-finite value reaches the insert: 2 red; zero-fill the gap: 2 unit + 3 IT red; carry a
delta across a gap: 2 unit + 3 IT red; no cap: 1 red; drop the newest: 1 red; unbounded buffer: 2 red; a runway failure escapes as an exception:
`IngestRunwayIT` red; `unit` dropped from the identity lookup: the S-H test red. **Deviations (all in the direction of the plan's text):** (1) the
database outage is simulated by a `DataSource` that refuses connections (`IngestFixture.FlakyDataSource`), not by stopping the shared container,
because every other IT in the JVM uses it; the production code path (`DataAccessException` / `TransactionException` handling) is the same.
(2) The ITs live in `dp.apply.persistence`'s test package, beside `MetricsDb`, which is package-private; the kit
(`AbstractTelemetryIngestPortContractTest`) is in `dp.metrics.ingest`. (3) The plan's `SeriesResolver` is folded into `IngestService.load`; the
persistence ports are one file of small interfaces, `IngestRows`, and `JdbcIngestContextRepository` (placement + instance + definitions in
one tenant-bound read) is an addition. (4) Beyond the plan's text, and flagged: ingest refuses a `DP_POLL` batch whose `bindingId` or `sourceKey`
is not the placement (M-Q7's mapping), `GapMarker`s from `DP_POLL` are `Rejected("gap-markers.refused")`, a batch over 5000 samples is
`Rejected("batch.too-large")`, a restart token over 256 characters is `PARSE_FAILED` (the column's CHECK), and a counter source with no declared
interval is read at the OQ-9 default of 30 s. (5) When a `DECLARED` or `DOCKER` series' previous sample carried no token, a same-or-lower value
with a new token is counted `DECREASE_WITHOUT_RESTART` (a reset cannot be proved). (6) `RetryAfter` means the batch is HELD (the bounded
buffer, drop-oldest, `BUFFER_DROPPED` counted in memory and written after the database returns); the caller is answered within `caller-wait`
and a database outage never blocks it for a pool timeout (a failing database rests `retry-delay`). **OQ-20 is untouched:** `ingest_counter`
keeps M-Q21's per-placement key exactly (`OR-107`: a placement-less push drop is P11-5.1's `sm_metrics` `V002`). No `sm_data` migration was needed.

### Task P11-3.2 — Type-aware rollups with a late window, the runway keeper, and the disk-usage alarm

> **Owner rulings applied (`OR-92`).** **OQ-6 (a):** `RollupService.rollUp(TenantId, Instant)` is called per tenant by the `MetricsBackgroundLoop` from the same single `tenantsWithActiveInstances()` read as collection (⚠️ widens M-Q3's named caller to "the metrics background loop"). **OQ-16 (a):** `MetricsDiskAlarm` compares `sm_metrics` and database size with `sm.dp.metrics.disk.budget-bytes` (`warning` 80 %, `critical` 95 %, `unconfigured` with no budget); visible only, never stops ingest; the API carries only `disk.state`.

**Wave:** MW3, after P11-3.1 in the same agent. **Depends on:** OQ-6, OQ-16; MF19.

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/metrics/rollup/{RollupService,PartitionRunway,MetricsDiskAlarm,RollupProperties,MetricsRollupConfiguration}.java`,
`…/dp/apply/persistence/{JdbcRollupRepository,JdbcMetricsDiskRepository,RollupPersistenceConfiguration}.java`; Create
tests `…/rollup/{RollupServiceIT,PartitionRunwayTest,MetricsDiskAlarmIT}.java`; Modify `DataPlaneConfiguration.java`
(**two** `@Import` lines, [the configuration table](#readiness-and-configuration-per-area)). The runway keeper and the
disk alarm run on P11-3.1's shared `metricsExecutor` (injected; this task creates no executor).

- [x] **Step 0: Fill MF19** (WebFetch `https://www.postgresql.org/docs/14/functions-admin.html`, "Database Object
  Size Functions"; quote the privilege lines for `pg_database_size` and `pg_total_relation_size`). Then measure both
  calls **as `sm_dp_runtime`** on a Phase 0 + migrated database, and record the result. If either is refused, stop:
  OQ-16 (a) needs another measure, and that goes to the owner.

- `RollupService.rollUp(TenantId, Instant now)`, **tenant-scoped**, called per tenant by P11-4.3's loop (OQ-6 (a);
  under OQ-6 (b)/(c) the loop is not its caller, and this task's cadence test moves to the named caller)
  **on its own cadence**: `sm.dp.metrics.rollup.interval`, default **5 min**, configurable, not every 10 s collector
  tick. The loop runs rollups for a tenant only when that tenant's last rollup is at least one interval old. Reason:
  at S-A, recomputing `(now − 3 h, now]` for every series re-reads about 360 k raw rows. At every 10 s tick that is
  about 36 k rows/s on a burstable t4g.medium; at 5 minutes it is about 1.2 k rows/s. P11-8.1 measures the real cost.
  > **Obligation from the P11-2.1 wave review (2026-10-05).** `MetricsPartitionMain` lets `freeze` and `detach` act on any month before
  > the current UTC month, so on the 1st the previous month's last days, still inside the 7-day raw tier and this late window, can be
  > detached and vanish from raw queries; if the rollups recompute from raw, those hours get no rollup and the gap is permanent.
  > This task owns the rollup watermark, so it owns the guard: `detach` refuses until the watermark has passed month end plus the
  > late window (and the raw-tier threshold), with a test and a mutation. Until then the runbook carries the operator rule.
  It recomputes every hourly bucket whose end lies in `(now − lateWindow − 1 h, now]` (late window **2 h**, M-Q24 (9),
  configurable) by an idempotent `INSERT … ON CONFLICT DO UPDATE`, per series, reading raw with `series_id = ANY(:ids)`
  (the PK serves it; no extra `(ts)` index). Every row carries the series' `metric_type` (the typed FK, P11-2.1).
  Gauges get `count/sum/min/max/last`. Counters get `delta = sum(increment)`, which is **NULL, never 0, when every
  increment in the hour is NULL** (a new series' first sample, or post-gap samples; M-Q9: never zero-fill), and
  `covered_seconds = Σ (ts − previous ts)` over exactly the samples whose increment is non-null (the actual deltas,
  M-Q24 (8)). They also get `last` and `reset_count` from `series_reset`. `expected_count = 3600 / interval` from the
  segment (NULL on push). Daily buckets (UTC day, M-Q24 (11)) are recomputed from hourly for every day touched:
  `delta` and `covered_seconds` are the sums of the non-null hourly values, and NULL when all are NULL.
- `PartitionRunway`: at boot (after readiness, **never boot-fatal**) and daily on the metrics executor, it calls
  `JdbcPartitionRunwayRepository.ensure()`. A lock timeout is counted `RUNWAY_DEGRADED` for every placement ingesting
  that tick, and the capabilities flag reflects `runwayEnd − now < 1 month`.
- **`MetricsDiskAlarm` (M-Q19's "disk-usage alarm", shaped by OQ-16 (a)).** Hourly on the metrics executor, and
  never boot-fatal, `JdbcMetricsDiskRepository` runs two bound catalog reads and nothing else. It never shells out
  and never reads the filesystem:
  - `SELECT pg_database_size(current_database())`;
  - `SELECT coalesce(sum(pg_total_relation_size(c.oid)), 0) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace
    n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relkind = 'r'`, with `?` bound to `sm_metrics`. Leaf
    partitions are `r`, and `pg_total_relation_size` includes their indexes and TOAST.

  These are compared with `sm.dp.metrics.disk.budget-bytes`. The state is `ok` below 80 %, `warning` from 80 %,
  `critical` from 95 %, and `unconfigured` when no budget is set (never a silent `ok`). The result is held in memory
  and **only its state** is surfaced, as `capabilities.collector.disk.state` (P11-1.2's contract, served by P11-7.2).
  The byte figures are install-wide totals across tenants and never leave the DP through the API (OQ-16's scope
  rule); `MetricsPartitionMain status` computes and prints them for the operator. **The alarm only reports.** It
  never stops ingest, and no ingest reason is added for it (M-Q21's list is unchanged). MF19 settles the functions'
  privileges for `sm_dp_runtime` before Step 3.

- [x] **Step 1: Failing tests.** `aGaugeHourIsSumCountMinMaxNeverAvgOfAvg()`, `aCounterHourIsTheSumOfItsIncrements()`,
  **`aCounterHourWithOnlyNullIncrementsRollsUpWithNullDeltaNotZero()`** (a new counter series' first sample alone in
  its hour, and an hour whose only samples follow a gap: the row is written, `delta` and `covered_seconds` are NULL,
  and the insert does not fail), `aLateSampleInsideTheWindowCorrectsItsBucketAndTheDay()`,
  `aBucketOlderThanTheWindowIsFinal()`, `multipleResetsInOneBucketAndOneOnABoundary()` (architecture §8.3's required
  cases), `outOfOrderArrivalWithinTheWindow()`, `theDayBoundaryIsUtc()`, `rollupReadsOnlyItsTenant()`,
  `rollupsRunOnTheirOwnCadenceNotEveryTick()`, `aRunwayFailureNeverFailsBoot()`, `theRunwayIsKeptDaily()`;
  `MetricsDiskAlarmIT` (as `sm_dp_runtime`): `theDiskStateIsUnconfiguredWithoutABudget()`,
  `aUsageOverEightyPercentOfTheBudgetIsWarning()` (a tiny budget against a real database),
  `aUsageOverNinetyFivePercentIsCritical()`, `theDiskReadIsBoundAndTouchesNoFilesystem()`,
  `aCriticalDiskStateNeverStopsIngest()`, `theAlarmsPublicViewIsTheStateOnly()` (the `dp-api` view type has no byte
  component).
- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY dp-core`; `VERIFY arch-tests`; `VERIFY launcher`** (the new configurations are imported into the production boot).
- [x] **Step 5: Prove they fire.** Compute the gauge average as `avg(avg)` over unequal counts → red. Shrink the
  recompute range to the current hour → the late-sample test goes red. Throw from `PartitionRunway` at boot → red.
  Write `coalesce(sum(increment), 0)` → the null-delta test goes red. Report `ok` when no budget is set → the
  `unconfigured` test goes red. Compare against the budget with `>` swapped for `<` → the warning test goes red. Run
  rollups every tick → the cadence test goes red. Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): type-aware hourly and daily rollups with a late window; the partition runway keeper; the disk-usage alarm (M-Q13, M-Q19, M-Q24)`

**Results (2026-10-06, `p011/p3`, Sonnet 5.5).** `RollupService`, `PartitionRunway`, `MetricsDiskAlarm`, their properties and two
configurations are built (two `@Import` lines), with `JdbcRollupRepository` and `JdbcMetricsDiskRepository`. **MF19 (filled).** The PostgreSQL 14
manual (Database Object Size Functions, fetched 2026-10-06) states one privilege rule: `pg_database_size` needs `CONNECT` on the database (granted
by default) or membership in `pg_read_all_stats`; `pg_total_relation_size`, `pg_relation_size` and `pg_table_size` state none. **Measured as
`sm_dp_runtime`** on a Phase 0 + migrated database (`MetricsDiskAlarmIT.theTwoSizeFunctionsWorkForTheRuntimeRole`): `pg_database_size` = 10,358,107
bytes and the sum of `pg_total_relation_size` over `sm_metrics`' tables = 229,376 bytes, both answered, so OQ-16 (a) holds with no new grant.
**Design points.** Hourly buckets are `date_bin('1 hour', ts, <UTC epoch origin>)`, never `date_trunc`, so a session in a half-hour zone cannot
move a bucket (`bucketsAreUtcWhateverTheSessionTimeZone`, red when swapped). The window is the hourly buckets whose END lies in
`(now - lateWindow - 1 h, now]`, so the open hour is never rolled up (raw covers it, D-6); the daily rollup is recomputed from the hourly rows
of every UTC day those buckets touch. A counter hour's `delta` is the plain `sum(increment)` (NULL for free when all are NULL) and its
`covered_seconds` sums the ACTUAL spacing over exactly the non-NULL-increment samples, looking back across the hour boundary and past the start
of the scan for the first sample's previous timestamp. **The P11-2.1 wave review's obligation is closed:** `MetricsPartitionMain detach` now
refuses (1) until the clock is past the month's end by the raw tier (default P7D, `SM_METRICS_RAW_TIER`) plus the late window (default PT2H,
`SM_METRICS_ROLLUP_LATE_WINDOW`), and (2) while any series with a raw row in the partition lacks an hourly rollup of its LAST raw hour computed after
that hour's late window closed (one primary-key probe per series, never a scan; no tenant id is printed). Stated limit: it checks each series' last
hour, not every hour between, so a mid-month rollup outage is not detected by it and the runbook rule stays. **Tests:** unit 16
(`RollupServiceTest` 6, `PartitionRunwayTest` 5, `MetricsDiskAlarmTest` 5) and IT 25 (`RollupServiceIT` 13, `MetricsDiskAlarmIT` 5, three new
`MetricsPartitionMainIT` guard tests; plus `IngestServiceIT.aKeeperFailureCountsRunwayDegradedForEveryPlacementThatIsIngesting` and
`IngestWiringIT.theRollupsTheRunwayKeeperAndTheDiskAlarmAreWired...` in P11-3.1's classes); the existing `MetricsPartitionMainIT` fixtures now roll up their
series, since they detach. `arch-tests` and the launcher boot ITs green; `demo-launcher` compiles. **Mutation proofs (each planted, seen red,
restored byte-for-byte):** gauge `sum` stored as `avg`: 2 red; recompute only the current hour: 2 unit red; `PartitionRunway` rethrows: 2 red;
`coalesce(sum(increment), 0)`: the null-delta test red; `ok` with no budget: 1 red; warning comparison swapped: 2 red; rollups every tick: the cadence
test red; detach guard removed: 2 red; `date_trunc` for `date_bin`: 3 failures and 4 errors; `covered_seconds` counting NULL-increment samples: 1 red;
runway kept hourly instead of daily: `theRunwayIsKeptDaily` red. **Deviations / additions, flagged:** (1) a keeper failure is told to ingest through a
new `dp.metrics.RunwayDegradation` seam (implemented by `IngestService`, which counts `RUNWAY_DEGRADED` for every placement that sent a batch within
`sm.dp.metrics.ingest.active-window`, default 5 min; `IngestProperties` gained that component). (2) `dp-api` gained `DiskAlarmView` (a state-only
record, `ok`/`warning`/`critical`/`unconfigured`) because `theAlarmsPublicViewIsTheStateOnly` needs a dp-api view type, and `DiskProperties`
(`sm.dp.metrics.disk.*`) beside `RollupProperties`. (3) `MetricsDiskAlarm.view()` is an `Optional`: empty only when a budget is set and no
measurement has succeeded yet, so a failed first read is never a silent `ok`; P11-7.2 decides how to render it. (4) The alarm compares the larger of
the database size and `sm_metrics`' size with the budget. (5) `JdbcPartitionRunwayRepository` now implements `PartitionRunway.Source` (one line).
(6) No `sm_data` or `sm_metrics` migration was needed. `./contracts/run-checks.sh --strict` was not run: no contract file changed.

---

## MW4 — Collection (path 1)

### Task P11-4.1 — Declare and stage carry the metrics declaration (M-Q5, M-Q14, M-Q15)

**Wave:** MW4. **Depends on:** **plan 010 W11 merged** (T8.1, T9.4, T10.2 and T10.4 are in; `ConfigRecordServiceImpl`
is free; ❌ none had landed at `bd802fc`, and T4.4 and T5.3 are built on unpushed branches), [Prerequisite 2](#prerequisites)
(M-Q2b and `OR-82`, rebound to the landed names), P11-1.2 (the `placement.metricsPorts` body), P11-2.2. **This gate does not
move under any OQ-28 option** ([per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)). **Parallel with:** P11-4.2.

**Files:** Create `backend/cp-api/src/main/java/com/solace/sm/cp/api/designer/MetricApplicability.java`,
`backend/cp-core/src/main/java/com/solace/sm/cp/designer/{WhenEvaluator,DraftValueResolver,MetricApplicabilityImpl}.java`;
Modify `cp-core/.../designer/DesignerProjectorImpl.java`. **Two** pieces move out of the projector unchanged, and the
projector calls them:
(1) the private `evaluate` (`:437-449`) moves to `WhenEvaluator`;
(2) the defaults-applied, normalized value building (pass 1, `:156-178`: *"draft beats default"*, through
`normalize` at `:470`) moves to `DraftValueResolver`, which returns the values and the bind errors exactly as today.
`evaluate` alone would **not** give the "defaults-applied, normalized" values the interface promises.
⛔ Deriving the list from `project()`'s own `metrics[].state` (`:326-344`) is **rejected**: that state compares
running against draft (`pending-config-change` means either direction), so it cannot say whether a metric is active
for the **draft**. Modify `dp-api/.../inventory/DeclareInstanceCommand.java` (a new `MetricsDeclaration metrics`
component), `dp-core/.../inventory/InventoryService.java` (`reserve` writes the definitions if absent, the endpoints
and the generation-0 list **in `reserve`'s own transaction**, the same DP transaction as the placement, M-Q5: *"written
in the SAME DP transaction as declare's `reserve`"*); **no change to `cp-api/.../catalog/SolutionCatalog.java`**
(measured 2026-10-05: `find(organizationId, catalogSolutionId, packageVersion)` already returns a `LoadedPackage` whose `digest()`
is the stored `solution_package.digest`, so the draft's `digestOf` is not needed);
`dp-api/.../config/StageCommand.java` (a new `List<String> activeMetricNames`), `dp-core/.../config/ConfigRecordServiceImpl.java`
(`stage` writes the list with the generation; `copyForward` copies the source generation's list),
`console-gateway/.../http/DeclareOrchestrator.java` and the gateway stage flow (T5.3's `DesignerOrchestrator`), the
restore/accept handlers per Step 0; Create `cp-core/.../cp/designer/MetricApplicabilityConfiguration.java` (registers
`MetricApplicabilityImpl`; one `@Import` line in `ControlPlaneConfiguration`, [the configuration
table](#readiness-and-configuration-per-area)); Create tests `cp-core/.../designer/MetricApplicabilityTest.java`,
`dp-core/.../inventory/MetricsDeclareIT.java`, `launcher/.../MetricsDeclarationEndpointsIT.java`,
`arch-tests/src/test/java/com/solace/sm/arch/MetricsDeclarationConstructorTest.java`.

**The record ripple (measured risk).** `DeclareInstanceCommand` gains `MetricsDeclaration metrics` and `StageCommand`
gains `List<String> activeMetricNames`. Every plan 010 test and fixture that calls their canonical constructors
(`InventoryServiceIT`, the declare crash-window ITs, the stage, rollback, restore and accept tests, among others) would
stop compiling. So each record gains **one secondary constructor with plan 010's arity**, which passes
`MetricsDeclaration.none()` / `List.of()`: plan 010's tests stay byte-unchanged. ⚠️ A production path that used the
secondary constructor would silently declare no metrics, so `MetricsDeclarationConstructorTest` (ArchUnit, production
classes only) forbids any `src/main` class from calling either secondary constructor, with a planted fixture
(`com.solace.sm.gateway.archfixture.monitoring.DeclaresWithoutMetrics`). `DeclareOrchestrator`, the stage flow and the
rollback/restore/accept paths call the canonical constructors. Step 0 greps the call sites and lists them in the
commit, so a reviewer can see the ripple the secondary constructor absorbs.

```java
package com.solace.sm.cp.api.designer;
/** M-Q15 `OR-74`: cp-core's own predicate grammar (UI-D2: equality + AND), over defaults-applied, normalized
 *  draft values — the same evaluation project() uses, extracted, never re-implemented. */
public interface MetricApplicability {
    List<String> activeMetricNames(PackageModel.SolutionPackage pkg, StateModel.ComponentSelection selection,
            Map<String, Map<String, Object>> draft);
}
```

**The gateway at declare** (after M-Q2b's `acquisition`): it reads the package (`SolutionCatalog.documentOf`) and
the **CP-recorded** digest of that version (`SolutionCatalog.find(…).digest()`, which is `solution_package.digest`:
*`sha256:` + the hex digest of the canonical document text*, plan 010 T3.2, `010-…md:2491`; the CHECK is in `sm_control`
V013, `:34`), and passes that digest through unchanged. ⛔ **It never recomputes a digest.** `documentOf`'s own javadoc
says why: the store is `jsonb`, which re-spells the text, so hashing it can differ from the CP's digest and cause spurious
`PACKAGE_VERSION_MISMATCH` refusals. The CP already refuses a re-load of a version with a different digest, so its
stored digest is the one digest per version. It builds `MetricDefinitionSpec` for **every** declared metric
(grammar-rechecked; a failure is `422`). It builds `RestartSignalSpec` per component and `metricsPorts` from the body's `placement.metricsPorts`, with an endpoint row for
**every distinct port the collector will call: the union of the `http_json` source ports and the components' `restartSignal.port`s**,
each **defaulting to itself** (M-Q14). A restart-signal port that no metric source uses still gets a row; without one, a direct-mode
fetch of the signal has no host port and a component declared `DECLARED` would always read as a failed signal. For generation 0 it builds `activeMetricNames` from `MetricApplicability` over the
**empty draft** (package defaults). ⛔ No branch on the solution. **At stage:** the list is from the stage's
server-projected draft. **At rollback:** the DP copies the source generation's list (T8.1's copy-forward).

- [x] **Step 0: Classify the landed plan 010 generation mints, and find the stored digest.** Read T10.2
  `restore-runtime` and T10.4 `accept-runtime` as merged. Each is a **copy-forward** (the DP copies the source list)
  or **re-projected** (the gateway evaluates, as at stage). A flow that fits neither stops and asks the integrator.
  Re-read T3.2's `SolutionCatalog`: `find(…).digest()` returns the stored digest (measured at `bd802fc`); use it and add
  nothing to cp-api. `grep -rn "new DeclareInstanceCommand(\|new StageCommand("
  backend/*/src` and record every call site, split main vs test; every **main** site moves to the canonical
  constructor in this task.
- [x] **Step 1: Failing tests.**

```java
@Test void theExtractedEvaluatorLeavesAllThirtyThreeProjectionFixturesUnchanged() { }   // DesignerProjectorFixtureTest stays green
@Test void activeNamesFollowWhenOverTheDraftNotTheRunningValues() { }                  // provider=caffeine → five; another provider → none
@Test void declareWritesDefinitionsEndpointsAndGenerationZeroInTheReserveTransaction() { }
@Test void aRefusalInsideReserveLeavesNoMetricsRow() {
    // (i) What the design guarantees: a refusal INSIDE reserve's transaction (a metrics-port collision, a package
    // digest mismatch, an envelope refusal) leaves no placement AND no metrics row. P11-2.2's
    // aFailedDeclareRollsBackItsDefinitionsWithThePlacement is the DB half; this is the service half
}
@Test void afterAnOverQuotaAbandonThePlacementIsRetiredItsPortsAreFreeAndItIsNeverCollected() {
    // (ii) reserve COMMITS before CP register checks quota (010-…md:2608-2611, the declare table's steps 3-4; BE-D32), so the metrics rows are already
    // committed when the over-quota refusal comes, and the runtime role has no DELETE on them (by M-Q5's write-once
    // rule). Expected: the placement is retired, so its metrics host ports are free (a second declare may take them)
    // and CollectionPlanBuilder never plans it; its definitions, endpoints and generation-0 rows REMAIN, harmless and
    // append-only. ⛔ No DELETE grant is added to make them go away. The same holds for a declare the sweep abandons
    // (BE-D32: a pending row with no CP registration; `abandon` retires its placements in the same transaction)
}
@Test void anAbsentMetricsPortDefaultsToTheSourcePort() { }
@Test void aRestartSignalPortWithNoMetricSourceStillGetsAnEndpoint() { }              // 9091 declared only as restartSignal.port → a row 9091 → 9091
@Test void aMetricsPortCollisionIs409AndWritesNothingOnEitherPlane() { }                // Review Focus 1, end to end
@Test void stagingStoresTheListWithItsGenerationAndNeverEditsAnOlderOne() { }
@Test void rollbackReusesTheSourceGenerationsList() { }
@Test void aPackageVersionMismatchIs409AndWritesNothing() { }
@Test void theDigestPassedToTheDpIsTheCpStoredDigest() { }                               // equals solution_package.digest; the gateway hashes nothing
@Test void theExtractedDraftResolverAppliesDefaultsAndNormalizesLikeProject() { }        // a "true" string for a bool, an empty string → default
@Test void theListsAreEvaluatedByCpCoreNeverTheDp() { }                                  // arch: dp-core has no predicate evaluator
@Test void declareAndStageStillReachableInTheProductionBoot() { }                        // plan 010's ITs stay green + the new IT
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY cp-core`, `VERIFY dp-core`, `VERIFY
  console-gateway`, `VERIFY launcher`, `VERIFY arch-tests`** (every plan 010 declare/stage/rollback IT is the
  regression proof).
- [x] **Step 5: Prove they fire.** Evaluate `when:` against the running values → the draft test goes red. Write the
  metrics rows in a **second** transaction after `reserve` commits, and inject a metrics-port collision into that
  second transaction → `aRefusalInsideReserveLeavesNoMetricsRow` goes red, because the **placement row survives** the
  refusal. Call a secondary constructor from `DeclareOrchestrator` → `MetricsDeclarationConstructorTest` goes red. Recompute the list
  on rollback → the reuse test goes red. Hash `documentOf`'s string in the gateway → the digest test goes red. Skip
  normalization in `MetricApplicabilityImpl` → the draft-resolver test goes red. Build the endpoint rows over the metric source ports only →
  `aRestartSignalPortWithNoMetricSourceStillGetsAnEndpoint` goes red. Revert each.
- [x] **Step 6: Commit.** `feat: declare and stage carry the resolved metrics declaration to the DP (M-Q5, M-Q14, M-Q15)`

**Results (P11-4.1, executed 2026-10-06 on `p011/p41` off `71eafb66`, Sonnet 5.5; not yet wave-reviewed).** Built: `MetricApplicability` (cp-api) and its `cp-core` implementation over `WhenEvaluator` and `DraftValueResolver`, both **moved verbatim** out of `DesignerProjectorImpl` (checked by text: the `normalize` block and pass 1 appear unchanged in the new class; the 33 projection fixtures, 100 fixture-test cases in all, stay green); `MetricsDeclaration.none()`, the `metrics` component on `DeclareInstanceCommand` and `activeMetricNames` on `StageCommand`; `dp-core`'s `MetricDeclarationRows` port (implemented by P11-2.2's `JdbcMetricDefinitionRepository`), written by `InventoryService.reserve` in its own transaction and by every minted generation; the gateway's `MetricsDeclarationBuilder` and the two new `409` handlers; `MetricsDeclarationConstructorTest` with three planted fixtures. **No migration**: P11-2.2's tables already carry everything, so no `V017` and no `CHECKSUMS.sha256` line. **Step 0 (the mints, classified before the write was placed):** *re-projected at the gateway*: `stageConfig` (`DesignerOrchestrator`), accept-runtime (`AcceptRuntimeOrchestrator`) and a console secret (`ConfigSecretOrchestrator`); *copy-forward in the DP* (the source's list is copied, never re-evaluated): rollback (`copyForward`), restore-runtime (`copyLatestForward`) and a configured log level (`copyLatestWithLogLevel`). ⚠️ **The plan names one `dp-core` mint and there are two:** `ConfigSecretServiceImpl.storeAndStage` inserts its generation itself (it cannot call `ConfigRecordServiceImpl.stage`, which is `REQUIRES_NEW`), so a list written only in `ConfigRecordServiceImpl` would have left every console-secret generation without one. It now writes the list too, and an ArchUnit rule says every class that calls `ConfigGenerationRows.insert` must also call `MetricDeclarationRows.insertActiveMetrics`, so a third mint that forgets fails the build. **Call sites** (`grep "new DeclareInstanceCommand(\|new StageCommand("`): main, six, all now on the canonical constructors (`DeclareOrchestrator`, `DesignerOrchestrator`, `AcceptRuntimeOrchestrator`, `ConfigSecretOrchestrator`, `ConfigRecordServiceImpl` twice); test, 39 in 16 files, **byte-unchanged**, absorbed by the secondary constructors. **Red first:** `MetricApplicabilityTest` 10 of 10 failing (stub); `MetricsDeclarationBuilderTest` 13 of 13 (5 failures, 8 errors; stub); the `dp-core`, gateway, arch and launcher tests were written against an API whose behavior was already in place in `dp-core` (the record shapes had to exist to compile), so for those the red is the mutation list below, each a plant that turned a named test red. **Green:** `cp-core` 420 (surefire, whole module), `dp-core` 883 (1 skipped, as before), `console-gateway` 529, `cp-api` 48, `arch-tests` 67 (surefire, `-DskipITs`); the 13 `dp-core` ITs a declare, stage, rollback, restore or log-level change touches 175 of 175 (`MetricsDeclareIT` 16, `InventoryServiceIT`, `InventoryConfigurationIT`, `ConfigRecordServiceIT`, `ConfigSecretServiceIT`, `DeclareSweepIT`, `MetricDefinitionSchemaIT`, `ApplyServiceRollbackIT`, `ApplyServiceRestoreRuntimeIT`, `ApplyServiceSetLogLevelIT`, `RollbackTargetsIT`, `ConfigLadderIT`, `ConfigGenerationRenderedConfigSourceIT`); the 15 `launcher` ITs a declare or a stage runs through (`MetricsDeclarationEndpointsIT` 6, `InstanceEndpointsIT`, `DeclareCrashWindowIT`, `DeclareRegisterTimeQuotaIT`, `DesignerEndpointsIT`, `ConfigSecretEndpointIT`, `AcceptRuntimeEndpointIT`, `RollbackEndpointIT`, `RestoreRuntimeEndpointIT`, `LogLevelConfigEndpointIT`, `LogLevelEndpointsIT`, `LogsEndpointsIT`, `HomeEndpointsIT`, `ApplyEndpointsIT`, `FleetEndpointsIT`) 122 of 122 in the final pass, plus `HealthEndpointsIT`, `MeshDisabledBootIT` and `SolutionManagerConfigurationTest` green in the pass before it. **The regression the plan did not predict:** the first launcher pass failed 5 plan 010 ITs with `409 METRICS_PORT_CONFLICT`, because their shared fixture declares several instances of the Cache package on one target, and every one defaulted source port 8081 to host port 8081, which M-Q14 makes unique among a target's live placements. That is the ruling working, not a defect; `DeclareFixtures.declareBody` now maps 8081 to `healthPort + 30000` (each test already gives a placement a distinct health port), which is why that fixture shows in the diff. **Mutations, each red, each restored:** (D1) the metrics rows written in a second transaction after `reserve` commits: 2 `MetricsDeclareIT` tests red (the placement survives the refusal), and the same plant turns `MetricsDeclarationEndpointsIT.everyKindOfMetricsPortCollision…` red (instances 4 against 1); (D2) `writeNext` writes no list: 8 red; (D3) a copy-forward passes an empty list: 2 red; (D3b) the log-level copy does: 1 red; (D4) the console-secret mint writes no list: 1 red; (D5) `JdbcPlacementRepository` leaves the health-versus-metrics collision raw: 1 red (a `PgConstraintViolation` reaches the caller); (G1) endpoint rows over the metric source ports only: 2 red, one being the restart-signal-port test; (G2) the gateway hashes `documentOf`'s text instead of passing `find(…).digest()`: 1 red; (G3) the stage evaluates over an empty draft instead of the one it stores: 1 red, and the same plant in the launcher IT turns the redis-draft assertion red; (G4) accept-runtime evaluates the pre-accept draft: 1 red; (G5) the console secret evaluates the draft without its new reference: 1 red; (G6) `METRICS_PORT_CONFLICT` answered as `CONFLICT`: 1 red; (A1) a secondary constructor called from `DeclareOrchestrator`: the real `@ArchTest` and my composition-root test red; (A2) `ConfigSecretServiceImpl` stops writing the list: the real `@ArchTest` and the proof test red; (M-A) `MetricApplicabilityImpl` skips normalization: the draft-resolver test red. The plan's "evaluate `when:` against the running values" has no input to plant against (`MetricApplicability` takes no observed state, by construction), so G3, which feeds the evaluator the wrong draft, stands in for it, and `theDraftIsReadAndTheRunningValuesAreNotEvenAnInput` shows `project()` says `pending-config-change` for a caffeine to redis edit where the list says none. **Decisions this task made that no ruling states, for the wave reviewer:** (1) **`MetricApplicability.activeMetricNames` takes the declared package version** (`activeMetricNames(pkg, packageVersion, selection, draft)`): the plan's three-argument signature cannot scope components and fields by `version` as `project()` does, so it would disagree with the projection for a versioned package; `null` reads the last version, as `project()`. (2) **Generation 0 is evaluated over one instance of each `exactly-one` component that applies to the declared version**, keyed by its type, with an empty draft: no selection exists at declare, and a `one-of-many` or `zero-or-more` component is a choice the operator has not made. (3) **A `placement.metricsPorts` key no `http_json` source or restart signal uses is `422 placement.metricsPorts`**, not stored: kept, it would sit unused while the port it meant to remap fell back to its default. (4) **Definitions are copied for the components that apply to the declared version only**, the last declaration of a type winning, and a metric name declared twice in that set is `422`. (5) **`MetricsDeclaration.none()` has a `null` digest** (the record permits that only when everything else is empty) and writes nothing; a package with no metrics is a recorded declaration with a digest and an empty list. (6) **Every minted generation has a list row, an empty one included**; a copy of a generation that has none recorded records an empty list. (7) **A secondary constructor on three services as well as on the two records** (`InventoryService`, `ConfigRecordServiceImpl`, `ConfigSecretServiceImpl`), because a new constructor argument would otherwise change the plan 010 ITs that build them; the repository-less one passes `MetricDeclarationRows.DISCARDING`, which discards an empty list and **refuses** anything else, and the arch rule bars `src/main` from all five secondary constructors (`TargetConnectionMain` moved to the canonical one). (8) **The health-versus-metrics collision a new placement causes is mapped in `JdbcPlacementRepository.collision`**, where plan 010's three collisions already are, using P11-2.2's `collisionOf`. **Flags, not resolved:** (a) ⚠️ **`ProblemBody.TITLES` has no entry for the eight codes P11-1.2 added**: a body for such a code throws on its own non-null `title` check and the original refusal is lost (measured: the first `409` came back as an unhandled exception). This task added the two it answers with (`PACKAGE_VERSION_MISMATCH`, `METRICS_PORT_CONFLICT`); **P11-7.2 must add the other six** before it answers with them. (b) ⚠️ **`console.yaml` describes `METRICS_PORT_CONFLICT` as "two source ports mapped to one host port" only**, while the gateway answers all four collision classes with it (another placement's metrics port, another's health port, this health port against another's metrics port, two sources to one host port); the text, not the code, needs widening, and this task's Files list does not include `console.yaml`. (c) `MetricsDeclareIT` is in `dp-core/.../apply/persistence/`, not `.../inventory/` as the plan names it: the Testcontainers harness `SmDataPostgres` is package-private there. (d) Plan 010 test files touched, one line each: `ConfigRecordServiceIT` and `InventoryConfigurationIT` add `MetricDefinitionPersistenceConfiguration` to their imported configurations (the two services now take the repository port); five gateway tests (`DesignerControllerTest`, `InstanceControllerTest`, `ApplyControllerTest`, `ConfigSecretControllerTest`, with `AcceptRuntimeControllerTest` inheriting) add the two collaborators to the orchestrators they build, through a new `MetricsFakes`. (e) The "never collected" half of `afterAnOverQuotaAbandon…` is the placement being retired (asserted) plus `CollectionPlanBuilder` not planning a retired placement, which is P11-4.3's to prove. (f) A mutation restored with `mv` kept the older file time, so Maven did not recompile it and a later build used the **mutated** class (found when `MetricApplicabilityTest` failed in a whole-module run); every file this task touched was then `touch`ed and the whole run repeated. Restore mutations with `cp`, never `mv`. (g) ⚠️ **Operator ergonomics, for the owner, not resolved here:** two instances of one metrics-declaring package on one target cannot both take the default port map, so a second declare on a target needs an explicit `placement.metricsPorts` (M-Q14 as ruled; the `409` names the class and the operator must pick a free port). `OR-74`/M-Q14 say "default the package port" without saying what the second instance does.

### Task P11-4.2 — The two fetchers: SSH to the host loopback, and direct HTTP; the restart token

> **Owner rulings applied (`OR-92`, `OR-93`).** **OQ-1 (a):** one start-time fetch per placement per cycle; direct mode reads the component's declared `restartSignal`, SSH mode reads docker `{{.Id}} {{.State.StartedAt}}`; with neither, restart detection is `inferred`. **OQ-28 (b):** P11-4.2a (command builders, `JsonPointerExtractor`, `DirectHttpFetcher`) starts after P11-1.1 and plan 010 T6.1 (not T7.1); P11-4.2b waits for T7.1 and T6.8 Step 0.

**Wave:** MW4, delivered as **two merge points in one worktree sequence** (split by the pre-sitting review; the gates are the
point of the split, [per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)).
- **P11-4.2a** — the command builders, the pointer extractor and the direct fetcher: `HostMetricProbe` (+ test), `MetricFetcher`,
  `FetchRequest`, `FetchResult`, `FetchProperties`, `MetricsFetchConfiguration` (with `DirectHttpFetcher`), `JsonPointerExtractor`,
  `DirectHttpFetcherIT`, `JsonPointerExtractorTest`. **Depends on:** P11-1.1 (the shapes), OQ-1, **OQ-28 (b)**, MF10; plan 010 **T6.1
  pushed** (the `HostProbe` pattern, and **M19** filled in its Step 0; under OQ-28 (a): W10). **Not T7.1:** none of these classes opens
  an SSH session. `HostMetricProbe` only *builds* the two fixed command strings (golden tests); nothing here execs.
- **P11-4.2b** — the SSH fetcher: `SshMetricFetcher` (+ `SshMetricFetcherTest`, which holds the spy-session tests), the
  restart-token reading, and its `@Bean` in `MetricsFetchConfiguration`. **Depends on:** P11-4.2a merged; plan 010 **T7.1**
  (`SshSessionPool`, M12, **M8**) and **T6.8 Step 0** (**M18**, docker `StartedAt` semantics).

**Parallel with:** P11-4.1.

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ssh/HostMetricProbe.java` + `HostMetricProbeTest.java`;
Create `backend/dp-core/src/main/java/com/solace/sm/dp/metrics/collect/fetch/{MetricFetcher,FetchRequest,FetchResult,SshMetricFetcher,DirectHttpFetcher,FetchProperties,MetricsFetchConfiguration}.java`,
`…/metrics/collect/JsonPointerExtractor.java`; Modify `DataPlaneConfiguration.java` (one `@Import` line for
`MetricsFetchConfiguration`); Create tests `…/collect/fetch/{SshMetricFetcherTest,DirectHttpFetcherIT}.java`,
`…/collect/JsonPointerExtractorTest.java`.

**`HostMetricProbe`** (SM-D11a: no shell composition from data) builds exactly two fixed commands:
- `curl --silent --show-error --max-time <n> --output - --write-out '\n%{http_code}' 'http://127.0.0.1:<port><path>[?k=v&…]'`.
  The inputs are an `int` port, a path matching `^/[A-Za-z0-9/_.-]{0,255}$`, and `queryParams` whose keys and values
  match `^[A-Za-z0-9_.-]{1,64}$` (re-checked **here**, independent of the schema; anything else is refused before
  any session). The DP inserts `?`, `=` and `&`; the URL is single-quoted as defence in depth (the grammar is the
  guarantee). **No `--fail`** (plan 010 M19/MF12: a `503` stays distinguishable from a refused connection). There is
  **one exec per distinct URL per placement**: metrics sharing a URL share the fetch (Cache: four metrics, one
  `/api/cache/stats`). The body is bounded by the transport's byte cap.
- `docker inspect --format '{{.Id}} {{.State.StartedAt}}' -- <container>`, with the container name from the placement
  row (`SshDockerPlacement`'s grammar). The restart token in SSH mode is `"<id> <startedAt>"` (M-Q9 (d′), MF11).

**M-Q9's "reuse health inspect when fresh", reconciled (carry this note into the OR-M-Q9 record).** There is no
fresh health inspect to reuse. Measured: plan 010 T7.2's `HealthPoller` probes over SSH with `HostProbe` curl and in
direct mode over HTTP, and *"the apply engine's health gate is untouched — it stays on `docker inspect`"*
(`010-…md:3767`). So the only `docker inspect` is the apply gate's, which runs only during an apply. The
collector's own fixed inspect above **is** M-Q9's SSH-mode signal. It runs once per placement per cycle on the
pooled session (MF6 measures its cost). If a later plan makes the health poller run `docker inspect`, reusing a
result younger than one collection interval becomes possible, and this task's note says so.

**`DirectHttpFetcher`**: JDK `HttpClient` to `http://<target address>:<host_port><path>?…`, where the address is the
**target's registered address** (`target_connection.host`, `OR-82`), **read fresh from the placement's target on each
round** through `TargetConnectionRows.find(tenantId, targetId)` (`dp-core`, `com.solace.sm.dp.inventory`; `dp-api`'s
`TargetConnectionService` deliberately exposes no host), and `<host_port>` is the placement's `placement_metrics_endpoint`
mapping (`OR-74`). **Nothing about the address comes from the browser**, so there is no host to validate against a deny
list and no carried-forward value; a re-target polls the new target's address; a target with no live record is a
registration problem (T2.7), counted `scrape_failed` and shown as a gap, never a fallback to SSH. The column's grammar
(`^[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?$`, `V007…sql:94-95`) admits a DNS name or an IPv4 literal and nothing with
`@`, `/`, `:` or `[`; the fetcher **re-checks it** before building the `URI` (defence in depth: the row is
runtime-writable, `GRANT UPDATE (host, …)`), so the URI cannot gain a second authority. It uses the
same grammar checks, the MF10 timeouts (connect 2 s, request 5 s, configurable), and a bounded body. A non-2xx status
is a `FetchResult.Http(status)`; a transport failure is `FetchResult.Transport(classifier)`. The **direct-mode**
restart token is the component's declared `restartSignal` value (OQ-1 (a)). With none declared it is `null`, and
the DP derives the source's detection as `INFERRED`. With one declared that **failed to fetch** this cycle it is also
`null`, but the detection stays `DECLARED`, so P11-3.1 rule 3 treats it as a failed signal read, never as an inferred
reset. The same holds in SSH mode when the inspect fails (`DOCKER`).

**`FetchRequest.midpoint`**: `ts` = the UTC midpoint of request-sent and response-received, and `receivedAt` = the
response time (M-Q24 (8)).

**`JsonPointerExtractor` refuses a non-finite number.** A body value such as `1e400` is a valid JSON number that
Jackson reads as `Infinity` as a double. The extractor returns `parse_failed` for NaN or ±Infinity, with no value in
the message, before anything reaches the ingest port (which re-checks, P11-3.1).

- [ ] **Step 0: Fill MF10** (WebFetch; P11-4.2a). Re-read plan 010's M19 row (P11-4.2a) and its M8, M12 and M18 rows (P11-4.2b); if the one
  a half needs is empty, **stop** at that half.
- [ ] **Step 1: Failing tests.** In P11-4.2a: the golden-string and grammar tests of `HostMetricProbe`, the `DirectHttpFetcher` tests and the
  extractor tests. In P11-4.2b: the tests that need a spy session (`aShellMetacharacterInQueryParamsNeverReachesExec`,
  `aDownAnswerIsItsHttpStatusNotATransportFailure`, `metricsSharingAUrlShareOneFetch`).

```java
@Test void theCurlCommandIsExactlyTheFixedShapeForAGrammarPathAndParams() { }   // golden string
@Test void aShellMetacharacterInQueryParamsNeverReachesExec() { }              // Review Focus 3: $(id) & ' ; ` space \n % — each refused, a spy session sees no exec
@Test void aPathOutsideTheGrammarIsRefusedBeforeAnySession() { }
@Test void theInspectCommandIsTheFixedShape() { }
@Test void aDownAnswerIsItsHttpStatusNotATransportFailure() { }                // M19: 503 stays 503
@Test void metricsSharingAUrlShareOneFetch() { }
@Test void theDirectFetcherUsesTheTargetsRegisteredAddressAndTheMappedPort() { }   // OR-82; a local stub server (JDK HttpServer, no dependency)
@Test void aHostOutsideTheRegisteredGrammarNeverBuildsAUri() { }               // "a@evil", "h/x", "h:80", "[::1]" — refused before any request
@Test void theDirectFetcherBoundsTheBodyAndTimesOut() { }
@Test void theTimestampIsTheMidpointAndReceivedAtIsKept() { }
@Test void anAbsentPointerMeansTheWholeBodyMustBeANumber() { }                 // cache.size (MF9)
@Test void aStringAtThePointerIsParseFailedAndTheValueNeverAppearsInTheMessage() { }
@Test void aNonFiniteNumberIsParseFailedNotRunwayDegraded() { }                // 1e400 and -1e400 → parse_failed at the extractor
@Test void noProductionFetchReachesTheDemoCacheProbes() { }                    // T6.7's two rules stay green (VERIFY arch-tests)
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY dp-core`, `VERIFY demo-launcher`, `VERIFY
  arch-tests`.**
- [ ] **Step 5: Prove they fire.** Concatenate a `queryParams` value unchecked → Review Focus 3's test goes red. Add
  `--fail` → the 503 test goes red. Read the port from the package instead of the endpoint row → the direct-port
  test goes red. Build the URI from an unchecked host string → `aHostOutsideTheRegisteredGrammarNeverBuildsAUri` goes red. Revert each.
- [ ] **Step 6: Commit** — two commits, one per merge point. P11-4.2a: `feat(dp-core): metric command builders, pointer extractor and the direct HTTP fetcher, closed grammar end to end (M-Q2, M-Q4, M-Q9)`; P11-4.2b: `feat(dp-core): the SSH-to-loopback metric fetcher and the restart token (M-Q2, M-Q9)`.

**Results (P11-4.2a, executed 2026-10-06 on `p011/p42`, Sonnet 5.5; reviewed in the wave review, not yet).** Built: `HostMetricProbe` (`apply.runtime.ssh`: the fixed `curl` and `docker inspect` builders, returning `Curl`/`Inspect` values only it can construct; `relativeUrl` is the one path-and-query grammar both modes use), `JsonPointerExtractor` (`metrics.collect`: `number` and `token`, a closed `parse_failed`), `MetricFetcher` (with `fetchAll`, which dedups equal requests, so metrics sharing a URL share the fetch), `FetchRequest` (sorted query copy, `midpoint`), `FetchResult` (`Ok`/`Http`/`Transport`), `FetchProperties` (`sm.dp.metrics.fetch.*`, connect 2 s, request 5 s, body cap 64 KiB), `DirectHttpFetcher` and `MetricsFetchConfiguration` (one `@Import` line in `DataPlaneConfiguration`, and the two matching lines in `DataPlaneConfigurationTest`, a plan 010 test the integrator authorizes). **Red first:** surefire 18 of 18 failing (`HostMetricProbeTest` 10, `JsonPointerExtractorTest` 8) and failsafe 15 of 15 (`DirectHttpFetcherIT`) against UnsupportedOperation stubs. **Green:** surefire 18 (+ `DataPlaneConfigurationTest` 3), failsafe 15; `arch-tests` `ApplyPathBoundaryTest` 7, `PlaneBoundaryTest` 20, `CompositionBoundaryTest` 5; `demo-launcher` 5; `launcher` `SolutionManagerConfigurationTest` 5 and `ProductionAuthenticationIT` 4 (a full production boot with the new bean). **Mutations, each red and reverted:** a query value left unchecked (`everyShellMetacharacterInAQueryParamIsRefusedBeforeACommandExists`, 1 red); `--fail` added (2 red: the golden string and `theCommandCarriesNoFailFlag…`); the host port fixed at 8081 instead of the mapped port (10 red); the registered-host grammar check removed (`aHostOutsideTheRegisteredGrammarNeverBuildsAUri` red, and measured: `new URI("http", null, "a@evil", …)` does **not** throw, the request went out to a connect failure, so the re-check is the only guard); the finite check removed (`aNonFiniteNumberIsParseFailedNotRunwayDegraded` red); the body watchdog lengthened (`theDirectFetcherBoundsTheBodyAndTimesOut` red, `Ok` after 5 s); `fetchAll` without dedup (`metricsSharingAUrlShareOneFetch`, 5 requests instead of 2). **Decisions made in the task (flagged for the wave review):** (1) the shapes the plan leaves open: `FetchRequest(PlacementRef, targetId, hostPort, path, queryParams)` carries no host (direct reads it fresh from `target_connection`); the restart read is `MetricFetcher.restartToken(RestartRequest)` answering `Token` / `NotDeclared` / `Failed(classifier)` so P11-3.1 rule 3 can tell a failed declared signal from an inferred source; (2) `ts` is the midpoint of request-sent and the instant the body was fully read, and `receivedAt` is that second instant; (3) a failed fetch is a result (`Transport`/`Http`), never an exception, and the classifier vocabulary is closed (`timeout`, `connect.failed`, `io.failed`, `body.too.large`, `fetch.refused`, `target.unavailable`, `target.host.refused`); (4) the direct restart token accepts a finite number (rendered as written, e.g. `1.790665479471E9`, MF8) or a string of `[A-Za-z0-9_.:+-]{1,128}`. **Not done here:** `SshMetricFetcher` and its `@Bean` are P11-4.2b. MF10 filled (table row). MF12 and M19 were plan 010's (`t6-9-measured-facts.md`), re-read, not re-derived.

**Results (P11-4.2b, executed 2026-10-06 on `p011/p42`, Sonnet 5.5; reviewed in the wave review, not yet).** Gates: plan 010 T7.1 (`SshSessionPool`, M8 and M12's local half), T6.8 Step 0 (M18, `t6-9-measured-facts.md`: `StartedAt` changes on `docker restart` and on stop-then-start and is unchanged by `stop` alone; a recreated container has a new id; the daemon's clock) re-read, not re-derived. Built: `SshMetricFetcher` (the spy-session tests are `SshMetricFetcherTest`, 13), `HostMetricExec` (`apply.runtime.ssh`: `curl(placement, Curl)` and `inspect(placement, Inspect)`, taking only the values `HostMetricProbe` builds, so no caller string reaches a host), `SshSessionPool` implementing it (`onPool`/`runOnPool` are the old probe path generalised: the same bounded probe slots, per-session slots and reconnect-once policy), `HostConnection.inspect` and its sshj implementation, and the `sshMetricFetcher` `@Bean` in `MetricsFetchConfiguration` (it shares the health poller's pool and exists exactly when `sm.dp.health.enabled` does). **Red first:** `SshMetricFetcherTest` 12 of 13 failing (the 13th tests the already-built `HostMetricProbe.inspect` grammar) and `SshSessionPoolTest` 3 of 18 (the three new tests; the fifteen existing stayed green). **Green:** `SshMetricFetcherTest` 13, `SshSessionPoolTest` 18, `HostMetricProbeTest` 10, `JsonPointerExtractorTest` 8, `SshjDockerTransport{Command,Credential,Probe,Root}Test` 21, `ProbeBackedHealthTest` 12, `DataPlaneConfigurationTest` 3, `DirectHttpFetcherIT` 15; `arch-tests` `ApplyPathBoundaryTest` 7, `PlaneBoundaryTest` 20, `CompositionBoundaryTest` 5 (T6.7's two rules stay green, `noProductionFetchReachesTheDemoCacheProbes`); `demo-launcher` 5; `launcher` `SolutionManagerConfigurationTest` 5 and `ProductionAuthenticationIT` 4 (the production boot with `sshMetricFetcher` wired). **Mutations, each red and reverted:** a query value left unchecked (`aShellMetacharacterInQueryParamsNeverReachesExec`, 1 red: the spy saw an exec); `--fail` added (`theFetcherRunsExactlyTheFixedCurl…` red; the spy cannot show the exit code `--fail` would give, so the golden string is the proof, with M19's measured exits in `SshjDockerTransportProbeTest`); a `503` mapped to a transport failure (`aDownAnswerIsItsHttpStatusNotATransportFailure` red); `fetchAll` without dedup (`metricsSharingAUrlShareOneFetch`, 5 execs instead of 2); the inspect exit status ignored; the `mode.mismatch` guard removed; the pool's `inspect` taken outside the probe slots (`theMetricsCommandsAreBoundedByTheSameProbeSlotsAsHealth` red). **Decisions made in the task (flagged for the wave review):** (1) **files outside the plan's list, all plan 010 T7.1's**: `SshSessionPool`, `HostConnection`, `SshjHostConnector` and `SshSessionPoolTest` (its `FakeConnection` gains `inspect`, and three tests are added): T7.1's own text says the pool is shared with the collector, and `docker inspect` returns no trailing status line so `HostConnection.probe` could not carry it; the integrator authorizes these. (2) The session's host-side limit is not measured here: MF6 (exec latency) and MF7 (`MaxSessions`) are P11-4.3 Step 0's, and the per-target reservation is P11-4.3's, so this task adds metrics traffic to the poller's pool with **no reserve yet**; nothing calls the fetcher until P11-4.3. (3) A placement whose instance is `direct-http` is refused (`mode.mismatch`) as a second line behind P11-4.3's no-fallback rule; `DirectHttpFetcher` has no instance to check, so the guard is one-sided. (4) The restart token is `"<64 hex id> <StartedAt>"`, one line matching a closed pattern; any other output is `Failed("docker.inspect.invalid")`, never a token. **Not measured here (needs the host):** the cost of the inspect over a pooled session (MF6), and `docker inspect` on the E2E host; M18's demo-host repeat remains plan 010's W9. **Process note:** a shared scratchpad script of another lane overwrote my mutation helper mid-run once; I restored the one mutated file from git and re-ran the affected proofs with a uniquely named script, and the stray run also executed `find backend -name failsafe-summary.xml -delete` in another lane's worktree (it removes a build artefact only).

### Task P11-4.3 — The `MetricsCollector`: one background loop, a bounded pool, per-target caps, deadlines

> **Owner rulings applied (`OR-92`).** **OQ-6 (a):** the single named caller is **the metrics background loop**, running collection, rollups and the push reconcile; the one edit to `TenantEnumerationRepository`'s javadoc names it and its three jobs (M-Q3's "metrics collector" is widened; `OR-72` unedited). **OQ-13 (a):** `onUnavailable: degrade` and `error` both give a gap plus `SCRAPE_FAILED`. **OQ-24 (b):** an `http_json` source with no `port` is excluded from collection (catalog `unsupported`).

**Wave:** MW4, after P11-4.1 and P11-4.2 (both halves, a and b) merge. **Depends on:** P11-3.2, plan 010 T6.5 (the
`restart-observed` rung; its `ConfigLadderReader` is T5.3's, extended), T7.2 (the poller pattern), T2.4 Step 3b; OQ-6, OQ-13. **Parallel:** ⛔ P11-4.4 follows in the same agent.

**Files:** Create `backend/dp-core/src/main/java/com/solace/sm/dp/metrics/collect/{MetricsBackgroundLoop,MetricsCollector,CollectionPlanBuilder,CollectionPlan,SourceScheduler,CollectorProperties,MetricsCollectConfiguration}.java`,
`…/dp/apply/persistence/{JdbcRestartObservedGenerationRepository,CollectPersistenceConfiguration}.java`; Create tests
`…/collect/{CollectionPlanBuilderIT,SourceSchedulerTest,MetricsCollectorIT}.java`,
`…/persistence/RestartObservedGenerationIT.java`; Modify `DataPlaneConfiguration.java` (**two** `@Import` lines; this
task does **not** edit P11-3.1's `MetricsConfiguration`), and,
**only if OQ-6 widens the named caller**, `…/dp/apply/persistence/TenantEnumerationRepository.java` (**javadoc only**).
Measured 2026-10-05: T2.4 Step 3b ✅ already wrote the javadoc (`:69-83`), and it **already names plan 011's
`MetricsCollector`** as the fourth caller (`OR-72`). So the first draft's "this task writes the line" is moot; under
OQ-6 (a) the one edit is to name the *metrics background loop* and its three jobs (collection, rollups, push reconcile)
instead. The method and its SQL are untouched.

- **`MetricsBackgroundLoop`** is the **fourth named caller** of `TenantEnumerationRepository.tenantsWithActiveInstances()`
  (M-Q3 `OR-72`), called **once per tick** (default 10 s). Per tenant, tenant-scoped from there on, it (i)
  refreshes that tenant's `CollectionPlan` and (ii) calls `RollupService.rollUp` when that tenant's rollup cadence is
  due (OQ-6 (a); P11-3.2's cadence, default 5 min). Under OQ-6 (a), P11-5.1 adds a third per-tenant step, the
  push-subscription reconcile, to this same loop. ⚠️ OQ-6 (a) widens M-Q3's named caller from "the metrics collector"
  to "the metrics background loop" (collection, rollups, push reconcile); the javadoc line this task writes names the
  loop and its jobs as ruled, and cites the OQ-6 answer.
- **`CollectionPlanBuilder`** reads only DP rows: live placements → the instance (`acquisition_mode`,
  `declared_package_version`; **no `direct_host`**, `OR-82`) → for `direct-http` instances, the placement's target
  address (`TargetConnectionRows.find`, `target_connection.host`, read fresh each round) → `metric_definition` for that version → ∩ **the active list of the newest generation
  that reached `restart-observed`**, or generation 0 when none has → endpoints → restart signal. `histogram`,
  uncollected kinds and (under OQ-24 (b)) an `http_json` source with no `port` are excluded. "The DP reads no package" (plan 010 T7.2) holds. A **retired** placement
  (`retired_at IS NOT NULL`) and an **abandoned** instance are never in the plan (S-K), and neither is a `pending` one
  (between `reserve` and `activate`, `BE-D32`: only `lifecycle = 'active'` is planned, the same filter
  `tenantsWithActiveInstances()` applies); their series and samples stay
  and remain queryable until retention ages the partitions out (discovery default D-17, not an owner ruling until
  OQ-22). Offboarding a tenant's data is the
  "tenant erasure on demand" open item that P11-0.1 raises, not this task.
- **Where "the newest generation that reached `restart-observed`" comes from (measured gap).** Plan 010 does not
  provide it. V010 stores **one** instance-level `reached_rung` beside `last_generation` (`010-…md:2881-2884`), so
  once generation N+1 is staged the rung no longer describes generation N. T6.5's `ConfigLadderReader` derives rungs
  from the journal **for the latest request** only (`010-…md:3254-3255`). This task therefore adds a tenant-bound query in
  `dp.apply.persistence`: `JdbcRestartObservedGenerationRepository.latestRestartObservedGeneration(TenantId,
  InstanceId) → OptionalLong`. It is derived from the journal rows that record `restart-observed` for a generation,
  **never** from `instance_state.reached_rung` alone. The exact SQL is written at Step 0 against the landed
  `apply_request`/intent schema. It is instance-level because plan 010's journal is instance-level; per-placement
  restart observation is plan 008's (multi-placement) concern, and this note routes it there.
- **`SourceScheduler`**: a dedicated fixed pool of platform threads (default **16**, configurable; not Boot's
  one-thread scheduler, discovery default D-4). Scheduling is per source, next-due, with ±10 % jitter; the deadline is the
  interval. A fetch still running at its next due time is a **gap**, plus `DEADLINE_MISSED`. There is a per-target
  concurrency cap (default `min(4, MaxSessions − 2)` from MF7, configurable; health keeps its reserve). Failures back
  off per host (1 × to 8 × interval) and reset on success. `onUnavailable` is `degrade` or `error`: both are a gap
  plus `SCRAPE_FAILED` (OQ-13 (a)).
- **The collector never logs a body.** It does not read `placement_health`: collection and health are independent
  (B/D2, UI-D8), and a transport failure is a gap, never a zero. Its SSH restart signal is its **own** fixed
  inspect, because there is no health inspect to reuse (P11-4.2's M-Q9 reconciliation; `010-…md:3767`).

- [x] **Step 0: Read the landed journal, then measure.** *(a) done; (b) MF6 and MF7 are **integrator, on the E2E host**: this lane has no live host and did not connect to one. The per-target reservation is configuration, so the measured numbers only set defaults.* (a) Read the landed `ConfigLadderReader`, the
  `apply_request` and intent schema, and `OutcomeService`'s `reached_rung` update point (plan 010 T6.5,
  `010-…md:3249-3250`). Write `latestRestartObservedGeneration`'s SQL against them, bound and tenant-scoped. If the
  journal does not record which generation reached `restart-observed`, **stop** and ask the integrator: the M-Q15
  switch has no data source. (b) **Measure MF6 and MF7 on EC2** with a throwaway harness over plan 010's
  `SshSessionPool` (500 execs of each fixed command; `sshd -T`). Record p50/p95/p99 and `MaxSessions`. **Set the
  defaults from the numbers**, and state the arithmetic: S-A = 200 placements × 3 execs / 30 s = 20 execs/s ≈ 6 busy
  threads at 0.3 s.
- [x] **Step 1: Failing tests.**

```java
@Test void theLoopReadsNoTenantOutsideTheRuledLists() { }            // the T7.2 pattern: only tenantsWithActiveInstances()
@Test void anIdleTenantsInstancesAreCollectedWithNoSession() { }      // no logged-in user, no apply in flight
@Test void twoPlacementsOnOneTargetAreEachScrapedOnTheirOwnHostPort() { }   // Review Focus 1 (S-G), both modes
@Test void theModeIsTheInstancesAndSurvivesARetarget() { }            // M-Q2 / M-Q2b
@Test void aReTargetedDirectInstanceIsPolledAtTheNewTargetsAddress() { } // OR-82: nothing carried forward
@Test void aPendingInstanceIsNeverPlanned() { }                       // BE-D32
@Test void aDirectModeInstanceIsNeverScrapedOverSshAndViceVersa() { } // no automatic fallback (M-Q2)
@Test void theActiveListSwitchesOnlyAtRestartObserved() { }           // M-Q15: staged but unapplied → old list
// RestartObservedGenerationIT (as sm_dp_runtime):
@Test void genNPlusOneStagedOrAppliedButNotRestartedStillYieldsGenN() { }   // the rung on instance_state moved on; the answer did not
@Test void noRestartObservedGenerationYieldsEmptyAndThePlanUsesGenerationZero() { }
@Test void theQueryIsTenantBound() { }                                // another tenant's instance id → empty
@Test void aDeactivatedMetricStopsButItsHistoryRemains() { }
@Test void aRetiredPlacementAndAnAbandonedInstanceAreNeverCollectedAndTheirHistoryStaysQueryable() { }   // S-K
@Test void histogramsAreNeverFetched() { }
@Test void aMissedDeadlineIsAGapAndACounterNeverAZero() { }
@Test void thePerTargetCapIsNeverExceeded() { }                       // 12 placements on one target, cap 4
@Test void aFailingHostBacksOffAndRecoversOnSuccess() { }
@Test void s_fAHostWhoseMiIsInARestartLoopYieldsGapsAndScrapeFailedNotZeros() { }
@Test void rollupRunsPerTenantFromTheSameEnumeration() { }            // OQ-6 (a)
```

- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY dp-core`, `VERIFY launcher`** (the collector
  runs in the production boot with no session), **`VERIFY arch-tests`**.
- [x] **Step 5: Prove they fire.** Call `tenantsWithNonTerminalWork()` instead → the idle-tenant test goes red. Read
  the latest generation's list, not the restart-observed one → the M-Q15 test goes red. Answer
  `latestRestartObservedGeneration` from `instance_state.reached_rung`/`last_generation` → the gen-N test goes red.
  Remove the per-target semaphore → the cap test goes red. Zero-fill a missed deadline → red. Drop the
  `retired_at IS NULL` predicate → the S-K test goes red. Drop the `lifecycle = 'active'` filter → `aPendingInstanceIsNeverPlanned` goes red.
  Fall back to SSH when a direct-mode fetch fails → `aDirectModeInstanceIsNeverScrapedOverSshAndViceVersa` goes red. Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): background metrics collection on OR-49's list as its fourth caller (M-Q3)`

**Results (P11-4.3, executed 2026-10-06 on `p011/p43`, Sonnet 5.5; reviewed in the wave review, not yet).** Built, in `dp-core`: `MetricsBackgroundLoop` (`SmartLifecycle`; the ONE `tenantsWithActiveInstances()` read per tick, fanned out per tenant to plan refresh and `RollupService.rollUp`, then the counts flush; a second 1 s schedule dispatches what is due), `CollectionPlanBuilder` + `CollectionPlan` (a value: units of one placement at one interval, sources = distinct URLs shared by their metrics), `SourceScheduler` (pure bookkeeping: jitter, deadline = interval, per-target cap, per-host backoff 1x/2x/4x/8x), `MetricsCollector` (the worker half: one fetcher per instance mode, one restart token per placement per cycle, one `IngestBatch` per unit), `CollectorCounts` (the counts only the collector can make, written on the loop's thread so no worker opens a connection), `CollectorProperties` (`sm.dp.metrics.collect.*`), `MetricsCollectConfiguration`, `CollectionRows`/`RestartObservedGenerations` (the two read ports), and in `dp.apply.persistence`: `JdbcRestartObservedGenerationRepository`, `MetricDefinitionCollectionRows` (an adapter over P11-2.2's existing reads, no new SQL) and `CollectPersistenceConfiguration`. Two `@Import` lines in `DataPlaneConfiguration`, and the matching lines in `DataPlaneConfigurationTest` (a plan 010 test, as P11-4.2a did; the integrator authorizes). The `TenantEnumerationRepository` javadoc now names the loop and its three jobs and cites `OR-92` (javadoc only). No migration, no contract edit, no new dependency. **Red first (counts):** `SourceSchedulerTest` 11 of 11, `RestartObservedGenerationIT` 6 of 6, `CollectionPlanBuilderIT` 13 of 13, `MetricsCollectorIT` 14 of 14 (13 errors at the stubs, 1 failure), all against `UnsupportedOperationException("red")` stubs, so each test reached the stub through the real arrangement. **Green:** surefire `SourceSchedulerTest` 11, `DataPlaneConfigurationTest` 3; failsafe 35 (`RestartObservedGenerationIT` 6, `CollectionPlanBuilderIT` 13, `MetricsCollectorIT` 14, `CollectWiringIT` 2); `arch-tests` `ApplyPathBoundaryTest` 7, `PlaneBoundaryTest` 20, `CompositionBoundaryTest` 5, `MetricsDeclarationConstructorTest` 5, `ReadBackSecretBoundaryTest` 2; `launcher` `SolutionManagerConfigurationTest` 5 and `ProductionAuthenticationIT` 4 (the production boot with the loop wired). `CollectWiringIT` was written AFTER the implementation (it measures the production wiring: the loop is running and collects an active direct instance off a local HTTP server with no test driving it), so it has no red run; it is the "measure the outcome" check, not a rule. **Mutations, each red and reverted (counts are the failing tests):** `tenantsWithNonTerminalWork()` for the active list (13 of 14 `MetricsCollectorIT` red, including the idle-tenant test and the constant-pool test); the newest generation's list instead of the restart-observed one (2 red: `theActiveListSwitchesOnlyAtRestartObserved`, `aGenerationWithNoRecordedList…`); the answer from `reached_rung`/`last_generation` (2 red: `genNPlusOneStagedOrAppliedButNotRestartedStillYieldsGenN` read `2` for `1`, and the re-target case); the tenant predicate dropped from the journal query (`theQueryIsTenantBound` red); the per-target cap removed (2 unit + 1 IT red: 12 started for a cap of 4); a second run of a unit still going (1 unit + 1 IT red); a missed deadline zero-filled (`aMissedDeadlineIsAGapAndACounterNeverAZero` red); no backoff (2 unit + 1 IT red); the other mode's fetcher as a fallback (3 red, including `aDirectModeInstanceIsNeverScrapedOverSshAndViceVersa`); `retired_at IS NULL` dropped from `PlacementRows.live` (3 + 2 red, S-K); `pending` planned beside `active` (`aPendingInstanceIsNeverPlanned` red; the collector-level test stays green because the tenant list already excludes a tenant with only pending instances, so the two layers each have their own test); one source per metric (shared URL, 1 red); the container port instead of the mapped host port (4 red, S-G); histograms collected (2 red). **Decisions made in the task, none in a ruling (flagged for the wave review):** (1) **Restart-observed query.** The journal does record it, so Step 0 (a) did not stop. A generation `G` reached `restart-observed` when EVERY live placement has an `APPLIED` `rolling-health-gated` apply request of `G` whose every `MATERIALIZE` replica has a `LIFECYCLE` intent and a `VERIFY` intent (hash equal to the bound hash), each with a latest attempt that `SUCCEEDED`; it is `ConfigLadder`'s rule restated per generation (the ladder reads only each placement's latest request), driven in the IT through the real engine, not planted rows. Consequence, recorded, not ruled: after a re-target the NEW live placement has observed nothing, so the answer falls back to an older generation or to generation 0 until it is applied (`aReTargetedInstanceNeedsItsNewPlacementToHaveObservedTheRestartToo`). (2) A generation chosen as in force but with NO recorded active list collects NOTHING (fail closed): every generation is born with a list, so its absence is a defect. (3) **Scheduling unit.** `SourceScheduler` schedules a (placement, interval) unit, not a bare source, so one restart token and one ingest batch cover a placement's URLs and one slot is held per run; the worker pool belongs to `MetricsCollector`, not the scheduler. Dispatch is a separate 1 s schedule from the 10 s tick; otherwise a 10 s interval has no jitter room. (4) **Per-target reservation (M-Q3), configuration not measurement.** Effective cap = `min(per-target-cap, ssh probes-per-session - health-reserve)`, floor 1; defaults 4, 1, and the pool's own default of 4 probes per session, so **3 today**. MF6/MF7 only change those defaults. (5) A failed read counts one `SCRAPE_FAILED` per failed URL (not per metric); a non-2xx answer is a gap and a count but does NOT back the host off (it answered; M19), only a run in which every document is a transport failure does. (6) A restart token that could not be read is passed as none: `IngestService` rule 3 already counts the `SCRAPE_FAILED` for a declared or docker counter, so the collector does not double count. (7) A direct-mode restart signal whose port no endpoint maps is not read (the ingest rule then treats the source as a declared source with no token); a failed `restartSignal` read is the same. (8) With `sm.dp.health.enabled=false` the SSH fetcher bean is absent and an SSH instance is counted `SCRAPE_FAILED`, never read directly. (9) The plan builder does not read `target_connection`: the direct fetcher reads the registered address fresh from the target each request names (OR-82), so a re-target is a new placement and a new `targetId` and nothing is carried forward. (10) Counts are accumulated in memory and written once per tick by the loop (a database outage keeps them); the default interval is `sm.dp.metrics.ingest.default-interval-seconds`, so the cadence and the stored segment interval cannot diverge. **Deviations from the Files list:** (a) the ITs live in the `dp.apply.persistence` test package, not `…/collect` (the Testcontainers harness `SmDataPostgres` is package-private), as `MetricsDeclareIT` already notes; (b) added `CollectionRows`, `RestartObservedGenerations`, `CollectorCounts`, `MetricDefinitionCollectionRows`, `CollectFixture`, `CollectWiringIT` (ports, a counter holder, an adapter and test arrangement the list does not name); (c) `DataPlaneConfigurationTest` edited. **Flags:** `HealthPollerProperties` and the collector both run on the health poller's SSH session pool; until MF6/MF7 are measured the numbers above are defaults, not facts. Nothing here exercises a real sshd, so the SSH-mode path is proven against a scripted fetcher only; P11-4.4 is its live check. The loop starts in every production boot and every boot test with a database (`sm.dp.metrics.collect.enabled=false` withdraws it); against a fake SSH host it counts `SCRAPE_FAILED` as the health poller already probes it.

**OR-112 (1) note (2026-10-07, lane `p/or112a`; the ruling wins over the text above).** The active-list switch (M-Q15) no longer waits for a *gated* apply: `RestartObservedGenerations` (the one reader `CollectionPlanBuilder`, the push plan and the query service all take) now answers the newest generation that reached `restart-observed` by any of three routes: the gated journal, a gated apply confirmed by verify-then-retry with its gate proved, or an **ungated** apply followed by a container start the data plane observed later than the write (plan 010 T6.5's note has the design and the tests). W3-01 is therefore closed for `manual` and every strategy that runs no gate. `StartObservationIT.theCollectorsActiveListSwitchesToTheGenerationOnlyAfterAnObservedRestart` drives the real `CollectionPlanBuilder` over the real reader: generation 0's list while the manual apply is only `persisted`, the generation's own list after a start later than the write, and (in the same class's push test) a start BEFORE the write changes nothing. `JdbcRestartObservedGenerationRepository`'s class text and SQL are rewritten for the three routes (NULL-safe under every `NOT`); the `RestartObservedGenerationIT` cases that said an ungated restart "is never observed" still hold, now because no start has been observed, not because none can be.

### Task P11-4.4 — The live collection gate on EC2 (S-G, S-F), and the direct-mode runbook residual

> **Owner rulings applied (`OR-92`).** **OQ-23 (a):** the runbook states that organizations sharing one machine must partition metrics host ports (`OR-89` was ruled with that consequence). No code guard.

**Wave:** MW4, after P11-4.3 in the same agent. **Depends on:** P11-4.3; plan 010 T6.6's target set up.

**Files:** Create `docs/design/backend/evidence/011-collection-live.md`; Modify
`docs/runbooks/install-prerequisites.md` (M-Q2's accepted residual: *"Cache's 8081 also serves unauthenticated POST
/api/cache; restrict it to the DP's source address"* in direct mode, and a general line for any MI whose metrics
port has a mutating route, **and, only if OQ-23 is ruled (a)**, a line that organizations sharing one machine (`OR-89`) must
partition host ports); Create `launcher/src/test/.../MetricsCollectionLiveIT.java`
(`@EnabledIfSystemProperty(named = "sm.e2e.target", matches = ".+")`, skipped otherwise, with the reason recorded).

- [ ] **Step 1** *(live run pending, integrator, on the E2E host)*: On the EC2 target, declare one Cache in `via-ssh` mode and, when the owner provides a second host
  port, a second Cache on the same host (S-G: 18081/28081). Declare one in `direct-http` mode if the DP can reach the
  **target's registered address** (`OR-82`) at the mapped host port; the body names no address, so the registered host
  must be one the DP can route to (otherwise record the skip and why, and never edit the target to make it pass). Let it collect for 10 minutes.
- [ ] **Step 2** *(live run pending, integrator, on the E2E host)*: Assert from the database: each instance's `cache.hit_count` series has the samples of its own
  port; restart one Cache and see exactly one `series_reset` with `signal = 'docker'`; stop one Cache and see
  `scrape_failed` and a gap, never zeros (S-F).
- [ ] **Step 3** *(live run pending, integrator: the template is written, the values are not)*: Record the evidence (commands, row counts, timestamps; no credentials, no host names beyond what
  `e2e-target.md` allows).
- [x] **Step 4: Commit.** `test: live background collection on the reference host, two instances on one target attributed correctly (S-G, S-F)`

**Results (P11-4.4, built and rehearsed 2026-10-06 on `p011/p43`, Sonnet 5.5; reviewed in the wave review, not yet; the live run is recorded in the last paragraph of this entry).** *(Built and rehearsed part:)* Nothing here was measured on a host and no host was contacted. Built: `MetricsCollectionLiveIT` (`launcher`; gated on the T6.6 walkthrough's own `sm.walkthrough.ssh.host`, so it is SKIPPED with its reason without the target: measured, 7 of 7 skipped, the reason naming `run-e2e-metrics-collection.sh`), its scenarios in `AbstractMetricsCollectionWalkthrough`, `MetricsLiveTarget` (the few `sm.walkthrough.metrics.*` knobs on top of the walkthrough's own target: `minutes` default 10, `host-port`, an optional all-three-or-none second Cache, `second.mode`, and `disruptive`), `MetricsCollectionRehearsalIT` (the SAME scenarios against two local stub Caches in `direct-http` mode, every build, `@Tag("broker")`, skipped without the local broker image), `backend/launcher/scripts/run-e2e-metrics-collection.sh` (the walkthrough runner's sibling: same `SM_E2E_*` target, runs only the one IT, `--check` prints and runs nothing) and `RunE2eMetricsCollectionScriptTest`, `docs/design/backend/evidence/011-collection-live.md` (the template, every value `pending`) and `docs/runbooks/install-prerequisites.md` (the M-Q2 residual: restrict the metrics port in `direct-http` mode, the general line for any MI whose metrics port has a mutating route, and the OQ-23 (a) line that organizations sharing one machine must partition host ports). **Red first:** `MetricsLiveTargetTest` 8 of 8 (5 errors and 3 failures against a stub). The script test has no honest red run: `RunE2eMetricsCollectionScriptTest` assumes the script exists (as `RunE2eWalkthroughScriptTest` does), so with the script absent it SKIPS; its proof is the three script mutations below. The rehearsal IT was written against the finished scenarios and has no red run either; its proof is the two collector mutations below. **Green:** surefire `MetricsLiveTargetTest` 8, `RunE2eMetricsCollectionScriptTest` 5, `RunE2eWalkthroughScriptTest` 6, `WalkthroughTargetTest` 11; failsafe `MetricsCollectionRehearsalIT` 7 (about 2 minutes), `MetricsCollectionLiveIT` 7 skipped. **Mutations, each red and reverted:** `SM_E2E_CONFIRM_DISRUPT` accepted with any value (1 red); a partial second Cache accepted (1 red); the T6.6 ITs added to the command (1 red); `disruptive` on any non-null value (1 red); the envelope-prefix check dropped (1 red); the config-root check dropped (2 red); **in the rehearsal:** a failed read stores a zero (`stoppingOneCacheIsAGapAndScrapeFailedNeverZeros` red: 14 samples in the down window), and the container port used instead of the mapped host port (4 red: no samples, no reset, no resume). **WHAT THE LIVE RUN DOES TO THE HOST, loudly.** By default it READS ONLY: the collector issues `GET`s (a `curl` to the host's loopback over SSH, or directly) and `docker inspect`; no configuration file is written, no container touched; the declare, the registered target and every metrics row live in this run's throwaway database. The plan's Step 2 asks for a restart and a stop, and those **write to the host**: with `SM_E2E_CONFIRM_DISRUPT=yes` (exactly; the script refuses `true`) the run RESTARTS one Cache container (`docker restart`, through the production adapter's `startOrUpdate`: no file is written) and STOPS one (`docker stop`), starts it again, and `@AfterAll` starts anything it stopped, FAILING the run loudly with the manual instruction when it cannot. Without it each of those two scenarios is skipped with that reason recorded. **Decisions made in the task, none in a ruling:** (1) the gate reuses the T6.6 target and `E2eStack`/`EvidenceLog`, and adds NO new property for the host, key, fingerprint or container; the only new properties are the `sm.walkthrough.metrics.*` knobs above, because the second Cache, the window and the disruptive switch have no T6.6 equivalent; (2) the task text's `sm.e2e.target` gate is replaced by the walkthrough's `sm.walkthrough.ssh.host`, as instructed; (3) a `direct-http` instance is declared only if this machine can open a TCP connection to the target's registered address at the Cache's host port (a connect reads nothing); otherwise the IT records the skip and why and never edits the target (Step 1); (4) attribution is proved by an independent fresh read through the production fetcher (the last stored sample is not after it) and by the Caches' counters differing; with two EQUAL counters the evidence says plainly that equality cannot tell a mix-up from none, and the read-only run does not generate traffic to tell them apart; (5) a stop then start changes the container's start time, so a further `series_reset` after the stop is expected and recorded, not asserted away; (6) the gate's cadence is the ingest default of 30 s, so the 10-minute window expects at least `window / interval / 2` samples (10). **Amendment 2026-10-06 (attribution, after the first live run read `hit_count` 0.0 on both Caches, which cannot tell a mix-up from none):** the attribution step now compares EVERY declared counter (`cache.hit_count`, `cache.miss_count`, `cache.eviction_count`, rules in `AttributionCheck`, unit tested) of each Cache with a fresh read of its OWN host port and records which counters differ between the Caches (or "attribution not distinguishable by value"); and with `SM_E2E_ATTRIBUTION_TRAFFIC=yes` (`sm.walkthrough.metrics.traffic=yes`, exactly, second Cache required) it first sends 30 `GET /api/cache?key=sm-attribution-<n>` (absent keys: a 404 and a miss, nothing written) to the second Cache's loopback port only through the closed-grammar fetcher, waits two intervals, and requires `cache.miss_count` to differ and each stored value to equal its own port's. **Not proven by the rehearsal, only by the live run:** the SSH transport, `docker inspect`'s start time as the reset signal (`docker`, not the rehearsal's `declared`), a real restart and stop, the host's `sshd` limits under the collector plus the health poller (MF6, MF7), and whether the Cache's loopback `curl` works for the SSH user. **Flags:** the rehearsal adds about two minutes to the `launcher` failsafe phase of every full build; a live run at 10 minutes plus the disruptive waits should take about 15 minutes (an estimate, not measured).

**Live run (P11-4.4, integrator, 2026-10-06, on the dedicated SM test host, `OR-110`; evidence in `docs/design/backend/evidence/011-collection-live.md`, redacted).** Four runs of `MetricsCollectionLiveIT`. **Run 1 (read-only) failed and found a defect:** zero samples and ten `scrape_failed`, because the test boot had `sm.dp.health.enabled=false` and so no SSH fetcher; fixed in `a8895442` (the live boot carries the fetcher; the collector now logs why a document failed). **Runs 2 to 4 passed** (the integrator reports the IT's seven methods green for each; the two disruptive scenarios record their skip in the read-only run 2). **Proven live:** background collection over SSH with no session (19 to 30 samples per Cache in a ten-minute window at a 30 s interval; `parse_failed` and `deadline_missed` 0); **S-G** attribution by value (run 4, after `SM_E2E_ATTRIBUTION_TRAFFIC=yes` sent 30 read-only GETs of absent keys to the second Cache only: `cache.miss_count` stored 30.0 on the second Cache against 0.0 on the first, each equal to a fresh read of its own port; the two Caches' other counters were 0 on both, which is why the earlier all-zero runs 2 and 3 could not tell a mix-up from none); exactly one `series_reset` with signal `docker` after a real restart, the first sample after it counted from the restart (0.0, never negative); **S-F** a real `docker stop` stores no sample in the down window (`scrape_failed` 0 to 8 over 2 min 30 s) and collection resumes after the start. **Not proven live:** the `direct-http` mode (no run used it), MF6's latency percentiles, more than two instances on one host, a window longer than ten minutes. **What it did to the host:** the disruptive runs restarted one Cache container and stopped one, and **a second Cache container (`cache-solution-2`, host port 8082) was created on the dedicated test host under `OR-110`**. **MF7 is measured:** sshd `MaxSessions 10`, `MaxStartups 10:30:100` (the measured-facts table row is updated; the cap's default `min(4, MaxSessions − 2)` stays 4).

---

## MW5 — Broker push (path 2)

### Task P11-5.0 — Fact gathering: what File Mesh actually publishes (MF13)

**Status: EXECUTED 2026-10-05** on `p011/fm` (`0127b50`), merged into `p011/mw0` (`c9be575`). Evidence: [`011-filemesh-publisher-facts.md`](../../design/backend/evidence/011-filemesh-publisher-facts.md) (`docs/design/backend/evidence/`). Partly measured; `start`'s `password` stays UNVERIFIED (MF13 row above). The facts feed OQ-4, OQ-5, OQ-15, OQ-20 and OQ-29 and are decided by none of them. **Results:** `011-filemesh-publisher-facts.md`, F1–F20 (34,527 captured events: `ADAPTER_ID` agrees in every one); the owner has since ruled OQ-4 (a) and OQ-15 (a) (`OR-92`) and deferred OQ-5, OQ-20 and OQ-29 until MF26 (`OR-95`).

**Wave:** **MW0** (moved from MW5 in review round 2), **before the owner sitting**, so OQ-5, OQ-15 and OQ-20 are
asked once with their facts and P11-1.1 lands the `solace_event` branch in its ruled shape. It is docs only (a local
source read of a sibling repository) and has no plan 010 or plan 011 dependency. **Parallel:** P11-0.1, P11-0.2 (its
own worktree; one new file). It is written here, under MW5, because it is path 2's first task.

**Files:** Create `docs/design/backend/evidence/011-filemesh-publisher-facts.md`.

- [x] **Step 1 (reworded by the pre-sitting review; the original premise was wrong):** the step asked for *"the publisher's topic
  builder and serializer"* in `pubsubplus-connector-file-common@cd52e31`. **Neither is there** (facts §1, §3, §8 item 1): that library
  holds the event **model** classes and the `EventState` constants (`EventState.java:20-27`), and its only topic code builds the
  data-plane file topic (`FileUtils.buildCustomTopicDestination`). The source and sink connectors, which compose the topic and call
  the serializer, are not on this machine. So the step reads **the model classes, the captured listener logs (34,527 events), the
  consumer code and the FM Manager's topic generator and template**, cites file:line for each (the header timestamp zone, F14;
  whether `start`'s `password`, `source/start/Solace.java:28`, is serialized, F15 — still UNVERIFIED), and marks whatever is not
  wire data INFERRED or UNVERIFIED.
- [x] **Step 2:** Answered, for OQ-5: which topic levels and payload fields carry something SM **already** holds (a
  config value SM writes), so an event maps to an instance with no MI change. Say, for each candidate key, whether
  the config field is secret-typed (gate 16 would refuse it), and how often an event would carry no usable key (the
  OQ-20 blind spot's size). (Facts §4 and §5: `ADAPTER_ID`, non-secret, topic level 7 and `header.adapterID`, 34,527 of 34,527
  agree; 0 of 34,527 lacked a key, as a floor.)
- [x] **Step 3:** MF13 filled: **partly measured**, see the MF13 row.
- [x] **Step 4: Commit.** `docs: measured File Mesh publisher facts for the push path (M-Q12)` (`0127b50`).
- [x] **Step 5:** Facts handed to the main sitting through OQ-4, OQ-5, OQ-15 and OQ-20 (their rows now cite them), plus two rows
  the facts called for: **OQ-29** (no event-count extraction, no numeric heartbeat) and **MF26** (topic case sensitivity). There is no
  separate short sitting.

### Task P11-5.1 — The push path: subscribe, filter (I6), extract, ingest

> **Owner rulings applied (`OR-92`, `OR-94`, `OR-95`, 2026-10-05).** **OQ-26 (a), `OR-94`:** path 2 is generic, package-driven ingest with no File-Mesh-specific code path, so `B/D5`'s walking-skeleton gate does **not** hold it; MW5 keeps its own gates. **OQ-4 (a):** `ts` is the DP's UTC receipt instant. **OQ-6 (a):** the push-subscription reconcile is a third per-tenant step of the metrics background loop (no fifth caller). **OQ-15 (a):** an event matching more than one live placement is dropped and counted as `ambiguous_placement`.
> **Still OPEN, deferred until MF26 is measured (`OR-95`): OQ-3** (broker coordinates and credential, `V016`, a new secret purpose), **OQ-5** (attribution route and topic forms), **OQ-20** (where a placement-less drop is counted) **and OQ-29** (the real v1 path-2 metric). **This task does not start until MF26 is measured and those four are ruled together**; it then also adds the `solace_event` schema branch in its own reviewed contract change (P11-1.1 left it out). Until OQ-20 is ruled its counter tests assert only "dropped, nothing stored".
> **Update 2026-10-06 (`OR-106`, `OR-107`; this task now runs).** The four deferred questions are ruled: **OQ-3 (a)** (one broker connection per organization and environment, `BrokerConnectionMain`, a new secret purpose `BROKER_CREDENTIAL`, `BE-D43`), **OQ-5 (a)** in its payload form (one broad subscription, attribution by the adapter id the event carries, exact and case-sensitive, MF26 measured), **OQ-20 (b)** (a broker-connection-level counter in `sm_metrics` `V002`), **OQ-29 (b)** (a count-of-matching-events extraction). Where a ruling differs from the text below, the ruling won; the differences are listed in Results.

**Wave:** MW5, **after MW4 merges**. **Depends on:** P11-3.1, **P11-4.3** (the `MetricsBackgroundLoop` this task
hooks into; without it there is no loop, and P11-7.2's rule forbids a fifth caller of `tenantsWithActiveInstances()`),
P11-2.2 merged (the shared pin files, single-owner table), P11-5.0 (done) + OQ-3, OQ-4, OQ-5, OQ-6, OQ-15, OQ-20, **OQ-26**
(LOCKED `B/D5`), **OQ-29** (what a `solace_event` source can extract) and MF26; plan 010 T2.4 (`SecretStore`). **MW5 may not claim
path 2 without one real, declared `solace_event` metric in a reviewed fixture (OQ-29)**. **Parallel with:** MW6 (disjoint files and configurations).

**Files (for OQ-3 (a)):** Create `…/sm_data/V016__broker_connection.sql` (**the reserved `sm_data` number**, see
[Migration allocation](#migration-and-id-allocation): `broker_connection(tenant_id, environment_id,
broker_connection_id uuid, host, port, vpn, client_username, credential_ref uuid, created_at, retired_at)`, one live
row per (tenant, environment)); **and, if OQ-3 is ruled with a new secret purpose, the same
migration widens `sm_secret.secret`'s `secret_purpose` CHECK** (`V009__secret_store.sql:23-24`) **and
`dp-core/.../secret/SecretPurpose.java` gains the value** (plan 010 T2.4's file; `EnvelopeSecretStore.put` already accepts
any purpose except `CANARY` and `CONFIG_HASH_KEY`, `:47`) with a new `BE-D` amending `BE-D28`'s closed set;
`…/dp/apply/persistence/{BrokerConnectionMain,JdbcBrokerConnectionRepository,BrokerConnectionPersistenceConfiguration}.java`
(`BrokerConnectionMain` copies `TargetConnectionMain`'s shape: standalone `main`, runtime DP credential and master key from
the environment, no Spring context, idempotent on its id, secret zeroised in a `finally`);
`backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/ingest/{BrokerSubscriptionManager,JcsmpSampleSubscriber,RawBrokerMessage,MeshbridgeIngestConfiguration}.java`;
`…/dp/metrics/push/{BrokerSampleDecoder,PushIngestService,PushSubscriptionPlan,PushAttribution,MetricsPushConfiguration}.java`;
Modify **`…/dp/metrics/collect/MetricsBackgroundLoop.java`** (one per-tenant step: the push-subscription reconcile,
under OQ-6 (a); under (b) the reconcile is its own named caller and this file is not touched) and
`DataPlaneConfiguration.java` (**three** `@Import` lines; this task does **not** edit `MetricsConfiguration`, [the
configuration table](#readiness-and-configuration-per-area)); as the **third** owner of the pin file (after
P11-2.2's merge): `…/persistence/SmDataBootstrapIT.java` (V016's deltas; no `SketchMirrorTest` edit, V007 onward is outside
the mirror); tests
`…/push/{BrokerSampleDecoderTest,PushAttributionTest,PushIngestIT}.java`, `…/persistence/BrokerConnectionMainIT.java`,
`…/meshbridge/ingest/JcsmpSampleSubscriberIT.java` (a local spike broker, the `solace-pubsub-enterprise:10.25.0.177`
image, and ⛔ never the `oms-*` containers or their ports). **The broker-dependent tests are gated like every existing broker IT**
(`@EnabledIfSystemProperty(named = "broker.host", matches = ".+")`: `JcsmpEventPublisherIT:63`, `MeshLifecycleIT:47`,
`CatalogEventIT:180`), so a default `verify` **skips them, visibly**. P11-10.1's Results must therefore **report whether they ran**, and
MW5 is **not claimed** if they did not (`L19`: a skipped test proves nothing). **Fallbacks, only if a ruling arrived after the task that
owns the file merged:** `contracts/…` (only if the sitting did not rule OQ-5 and P11-1.1 left the `solace_event`
branch out: this task then adds it, with gate 16, as its own reviewed contract change), `sm_metrics`
`V002__ingest_counter_reasons.sql` and `dp-api/.../ingest/CollectorReason.java` (only if OQ-15/OQ-20 were ruled after
P11-2.1/P11-3.1 merged).

- **Subscription:** per (tenant, environment) with at least one active instance declaring a `solace_event` source,
  `BrokerSubscriptionManager` holds one JCSMP session (MF14) from `broker_connection` (credential resolved from
  `sm_secret` in memory, zeroised). It subscribes each declared `topicPattern` with `{instanceKey}` substituted from
  the instance's config-of-record value (the grammar-checked result; a value outside the level grammar is refused and
  counted `parse_failed`). **Every pattern's last level is a literal** (P11-1.1's grammar; `PushSubscriptionPlan` re-checks it and refuses a wildcard last level
  even if planted past the validators), so a subscription names its states and a `start` event, which may carry a plaintext broker
  password (facts F15, F16), is never delivered to the DP. `I6`: the substituted value is never a secret, because gate 16 refuses a package whose
  `instanceKey` names a secret-typed field at load (P11-1.1), and `PushSubscriptionPlan` re-checks the field's type
  from the per-version definition before it subscribes (defence in depth; a secret-typed key is refused and never
  resolved). It is reconciled by **one added step in P11-4.3's `MetricsBackgroundLoop`**, per tenant, from the same
  `tenantsWithActiveInstances()` call, **as OQ-6 (a) rules** (OQ-6 now asks this explicitly; no fifth caller under
  (a)). Only
  `meshbridge.ingest` touches JCSMP (`onlyMeshbridgeTouchesJcsmp`), and it hands **bytes + topic** to
  `PushIngestService`, never a mapper (`meshbridgeHoldsNoOwnMapper`).
- **Attribution (M-Q7's identity for path 2; OQ-15 (a), the recommendation this task is written for).**
  `PushAttribution` maps an event to an instance by OQ-5's rule, then to that instance's **single live placement**
  (`retired_at IS NULL`, tenant-bound). The batch carries `placementId` = that placement, `bindingId :=
  broker_connection_id`, `sourceKey := placement_id` and the discriminator `BROKER_PUSH`. Two drops have **no single
  placement** to key an `ingest_counter` row on: an event for **no known instance** (or an instance with **no** live
  placement), and an instance with **more than one** live placement (a re-target overlap, M-Q20). Both are dropped;
  **where each is counted is OQ-20**, not this task's choice. Under OQ-20 (a): the ambiguous event counts +1 (as
  `ambiguous_placement` under OQ-15 (a), `undeclared_dropped` under (b)) on **every** live placement of the
  instance, and the no-placement event is counted nowhere, a blind spot this task writes into the runbook and the
  capabilities description. Under (b)/(c) the counter shape P11-2.1 built per the ruling is used. The reason list
  and the counter key are already in V001 and `CollectorReason` as the main sitting ruled (P11-2.1, P11-3.1); this
  task adds neither, except in the fallback above (V002 then ends with `SELECT sm_metrics.ensure_metric_partitions();`
  like every `sm_metrics` migration).
- **The I6 filter before write (M-Q12):** `BrokerSampleDecoder` parses into a `JsonNode`, applies `eventFilter`,
  reads **only** `valuePointer`, requires a **finite** JSON number (NaN or ±Infinity, for example `1e400`, is
  `parse_failed`, never passed on to be misread as a runway failure), and discards the node.
- **`BrokerConnectionMain`** (the `TargetConnectionMain` shape) reads the broker credential from the environment,
  stores it in `sm_secret` by reference, and **never echoes it**: not on stdout or stderr, and not in an exception
  message or cause it lets escape. No payload, topic value or field
  value is logged, stored, counted by content or put in an exception message.
- **ts** = the DP's UTC receipt instant (OQ-4 (a)). The discriminator is `BROKER_PUSH`. The batch goes through the
  same `TelemetryIngestPort`, bounded buffer and counters as path 1. **Live browser delivery is not built in this
  task.** It is M-Q12's ruled live leg, and **OQ-14** schedules it: a conditional P11-5.2 in this plan, or the plan
  that closes open items 32/33/35. This task does not treat the leg as deferred.

- [x] **Step 0: Fill MF14** (read from the sol-jcsmp 10.30.1 jar with `javap`, and measured on a local broker; not WebFetch: see Results).
- [x] **Step 1: Failing tests.** `aPayloadSecretNeverLeavesTheDecoder()` (a `start` event with `password` → grep every
  table, the captured log and the exception text: absent), `onlyTheDeclaredPointerIsRead()`,
  `aNonNumberAtThePointerIsParseFailedWithoutTheValue()`, `aNonFiniteNumberIsParseFailedNotRunwayDegraded()`,
  **`anEventForNoKnownInstanceIsDroppedAndNothingIsStored()`** (no sample and no series row; ⛔ it asserts **nothing**
  about a counter until OQ-20 is ruled, then the ruled count), `aSecretTypedInstanceKeyIsNeverResolvedIntoATopic()`
  (a definition whose key field is secret-typed, planted as superuser past gate 16: the plan refuses it, no
  subscription is made, the secret store is never read), `brokerConnectionMainNeverEchoesTheCredential()` (success and
  failure paths; stdout, stderr and every escaping exception's message and cause chain),
  `anEventForAnotherTenantsTopicNeverReachesThisTenant()`, `aBrokerOutageIsAGapAndReconnectResubscribes()`,
  `theConnectionCredentialIsResolvedInMemoryOnly()`, `jcsmpStaysInMeshbridgeAndBytesCrossOut()` (arch),
  **`aStartEventIsNeverSubscribed()`** (for the reviewed `solace_event` fixture metric of OQ-29, the planned subscription's last level is its declared
  state, for example `fcomplete`, and never `start`; a pattern with a wildcard last level, planted past the validators, is refused by the plan),
  **`aRealDeclaredSolaceEventMetricIsIngestedEndToEnd()`** (OQ-29: one reviewed fixture metric, for example an `fcomplete`
  `runTimeMs` gauge under (a), takes a captured event to a stored sample; a synthetic-only package does not satisfy it),
  `pushedSamplesLandInMetricsRawUnderTheirSeries()` (a service-level test: the sample is in `metrics_raw` under a
  `broker-push` series; the REST half is `PushedMetricsQueryableOverRestIT`, created by whichever of P11-5.1 and
  P11-7.2 merges second, the dispatch map),
  `anEventIsAttributedToTheInstancesSingleLivePlacement()` (`bindingId` = the broker connection, `sourceKey` = the
  placement), **`anInstanceWithTwoLivePlacementsHasItsEventDroppedNotGuessed()`** (a re-target overlap: no
  sample lands on either placement; ⛔ the counter assertion is written only once OQ-20 is ruled, for example +1 on
  each live placement under OQ-20 (a)), `aRetiredPlacementIsNeverAttributed()`,
  `theReconcileRunsInsideTheOneBackgroundLoop()` (no new caller of `tenantsWithActiveInstances()`).
- [ ] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY dp-core`, `VERIFY arch-tests`; `VERIFY launcher`** (the new configurations are imported into the production boot).
- [x] **Step 5: Prove they fire.** Log the payload on a parse failure → the secret test goes red. Read the whole
  payload into a stored field → red. Build the topic from the raw config value unchecked → the grammar test goes red.
  Attribute to the newest live placement → the two-placement test goes red. Skip the key-type re-check in
  `PushSubscriptionPlan` → the secret-key test goes red. Print the credential in `BrokerConnectionMain`'s failure
  message → the no-echo test goes red. Pass a non-finite double on → the non-finite test goes red. Subscribe with a `>` last level → `aStartEventIsNeverSubscribed` goes red. Call
  `tenantsWithActiveInstances()` from
  `BrokerSubscriptionManager` → P11-7.2's caller rule goes red once it exists (and `theReconcileRunsInsideTheOneBackgroundLoop`
  now). Revert each.
- [x] **Step 6: Commit.** `feat(dp): broker-pushed business metrics — subscribe, I6 filter before write, one ingest seam (M-Q12)`

---

**Results (P11-5.1, executed 2026-10-06 on `p011/p51`, Sonnet 5.5, as ruled by `OR-106` and `OR-107`; NOT yet reviewed).**

*Built.* **Contracts:** `$defs/sourceSolaceEvent` (a `topicPattern` whose levels are literals or `*` and whose LAST level is a literal; `instanceKey: {configField, pointer}`;
`extract: value | count`, `valuePointer` required for value and refused for count; `eventFilter {pointer, equals}`), `metric.allOf` (a count is a counter), `solace_event` removed from
`sourceUncollected`; gate 16 in `validate-package.py` and `SemanticGates.java`; 18 gate-1 negatives (and `gate01-queryparams-on-solace-event.json` sharpened to be valid but for `queryParams`); the
classpath copy of the schema; `filemesh-adapter.py` now emits one authored metric, so the reviewed fixture declares a REAL one (`filemesh.heartbeats`). **Data Plane:** `sm_data` `V017__broker_connection.sql`
(built as the placeholder `V018`, renumbered to `V017` at the wave 3 merge; the table, `broker_connection_live_once`, and the `secret_purpose` CHECK widened to `BROKER_CREDENTIAL`) and `sm_metrics` `V002__broker_ingest_counter.sql`, both pinned in their `CHECKSUMS.sha256`;
`SecretPurpose.BROKER_CREDENTIAL` and **`BE-D43`** (amends `BE-D28`, `BE-D28` unedited); `BrokerConnectionMain` + `JdbcBrokerConnectionRepository` + `JdbcBrokerCounterRepository` +
`BrokerConnectionPersistenceConfiguration` (its readiness names the missing tables and the missing purpose); `dp.meshbridge.ingest` (`BrokerSubscriptionManager`, `JcsmpSampleSubscriber(+Factory)`,
`RawBrokerMessage`, the sink and control interfaces, `MeshbridgeIngestConfiguration`); `dp.metrics.push` (`PushSubscriptionPlan`, `PushRows(+Adapter)`, `PushRouting`, `PushAttribution`,
`BrokerSampleDecoder`, `TopicPattern`, `PushMetric`, `CountAccumulator`, `PushIngestService`, `MetricsPushConfiguration`, `PushProperties`); `PushReconcile` and the loop's third per-tenant step
(`MetricsBackgroundLoop`, `MetricsCollectConfiguration`); three `@Import` lines in `DataPlaneConfiguration`; `PushPathBoundaryTest` (three arch rules, each with a planted fixture);
`docs/runbooks/register-a-broker-connection.md`; dated pointer in `design.md` §6.4.

*Where the rulings differ from the plan text above (the ruling won each time).* (1) **Payload form, no `{instanceKey}` token:** `topicPattern` takes no substitution (the plan's `{instanceKey}` level is refused), and the key is `instanceKey: {configField, pointer}` matched
against the event body; the Data Plane subscribes ONCE PER DISTINCT PATTERN per broker connection, never per instance. (2) **V017 (built as the placeholder V018, renumbered at the wave 3 merge), not V016,** and **V002** for the counter (the plan's reserved numbers were taken or moved).
(3) **`bindingId := broker_connection_id`**, `sourceKey := placement_id`, `BROKER_PUSH`. (4) **The count extraction** (`OR-106`): `ts` is the Data Plane's receipt instant, the cumulative value is synthesised in memory, and a restart is
M-Q9's inferred reset. (5) **A drop with no single placement is counted at the broker connection** (`undeclared_dropped`, `ambiguous_placement`, `parse_failed`, `buffer_dropped`), not on a placement and not "nowhere" (`OR-107`, OQ-20 (b)).

*Flagged, not decided.* **(F1) The reviewed metric is on the file-EVENTS source, not the file-to-file one, and it is one metric (a count of heartbeats).** The four `fm-*` view-model fixtures select the FILETOFILE components and carry `"metrics": []`;
`contracts/view-models/fixtures/**` is ownerless (`OR-34`'s lock), so a metric on a FILETOFILE component would change four locked files (measured: `run-checks.sh` step 8 failed on exactly those four before I moved it). The measured capture (facts 2.4) is
file-to-file only, so the file-events heartbeat shape is INFERRED (the shared commons heartbeat class, F11, and the fixture's own heartbeat fields, F19). To declare the measured `fcomplete` metrics too, the integrator must regenerate those four fixtures (`npm run fixtures:gen`: mechanical, they gain only the metric rows).
**(F2) "One broad subscription per broker connection" is read as one subscription per distinct declared pattern** (a literal last level means one state per subscription; a single `>` would deliver `start`). **(F3) A count takes no `valuePointer`** (the ruling says it is "optional"; a pointer that is never read is dead configuration, so the schema refuses it: a one-line change if the owner wants it accepted). **Reversed in wave 3 (W3-05): OR-106 says optional and wins; the schema now accepts a count's `valuePointer` and the DP ignores it.**
**(F4) `broker_ingest_counter` has no foreign key**: `SmMetricsSchemaIT` enforces M-Q19 (b) (`sm_data.placement` is the only cross-schema target), and a second target is the owner's to rule; the writer counts only for connections it holds.
**(F5) The pattern pins the number of levels** (the File Mesh package pins the generated nine): a customised base topic of another depth selects nothing, silently, until the package declares its own pattern (facts F4); the runbook says so. **(F6)** If the Data Plane restarts and the stored count equals one, the first event after the restart reads as "no increase"
rather than as a reset (documented in `CountAccumulator`). **(F7) `BrokerConnectionMain` reads the credential from a FILE whose path is in the environment** (the `TargetConnectionMain` shape), not from an environment variable as the plan's wording says. **(F8) `scheme` is a column** (`tcp|tcps|ws|wss`), because the local broker is reached over WebSocket and the SDK needs the scheme;
no port is defaulted (I2). **(F9) Gate 16 has no shared negative file** (the three are `HELD_CASES`, as gate 15's is): the frontend's `validate.ts` has no gate 16 and its test iterates every negative; `InstanceKeyGateTest` proves it in both validators. **(F10) `PushIngestService.drainNow()` is public** (a synchronous step for tests and operator tools; the worker runs the same code).

*Defects found in the base, not fixed (not mine to rewrite).* (D1) `ApplyPersistenceConfigurationIT.afterTheOneShotMigrationStep...` fails on `SmDataProperties` having a third component `pool` (`[runtime, apply, pool]`): measured at `HEAD` (`SmDataProperties` carries `Pool pool`), independent of this change; I corrected only the migration counts in that file (17).
(D2) The schema at `HEAD` already fails the frontend's Ajv options (`strict: true`, `strictTypes`) at the component-level `if` for `logs.loggers` (`OR-103`): measured with Ajv 8.20 over the schema from `git show HEAD:`. With that one node patched in memory, THIS change compiles under the same options and rejects all 18 new negatives; I did not run the frontend's vitest (never touch `frontend/`).

*Evidence.* **Red before green:** the reviewed fixture (with its metric) was refused by the old schema ("3 gate violations") before the schema change; `InstanceKeyGateTest` 4 of 5 failed before the Java gate; the first `BrokerConnectionMainIT`/`BrokerConnectionSchemaIT` run failed 13 of 15 on a real defect (PostgreSQL's regex repetition limit is 255, so `{1,256}` is refused: fixed to 255);
`SmMetricsSchemaIT` refused my foreign key (F4); the first `JcsmpSampleSubscriberIT` run failed 3 of 8 and each failure is a measured SDK fact now in the code: a reconnected session is open and SILENT without `REAPPLY_SUBSCRIPTIONS` (the outage test failed after 127 s), a repeated subscribe is refused by the broker (`IGNORE_DUPLICATE_SUBSCRIPTION_ERROR`),
a PERSISTENT publisher needs `allowGuaranteedMsgSendEnabled` on the client profile. **Green (this task's files):** cp-core `InstanceKeyGateTest` 5, `NegativeFixtureParityTest` 157 (76 negatives, with the Python as oracle), `PositiveFixtureParityTest` 8, `OverlapParityTest` 4, `PackageSchemaMirrorLockTest` 2, `DesignerProjectorFixtureTest` 100 (untouched); dp-core unit
`TopicPatternTest` 5, `BrokerSampleDecoderTest` 8, `PushMetricTest` 6, `PushSubscriptionPlanTest` 12, `PushAttributionTest` 11, `CountAccumulatorTest` 3, `PushIngestServiceTest` 14, `BrokerSubscriptionManagerTest` 13, `BrokerConnectionMainTest` 6; dp-core IT `BrokerConnectionMainIT` 7, `BrokerConnectionSchemaIT` 7, `PushIngestIT` 10, `PushWiringIT` 3, pins `SmDataBootstrapIT` 11,
`SmMetricsMigratorIT` 4, `SmMetricsSchemaIT` 28, `DataPlaneMigrationChecksumsTest` 4; arch `PushPathBoundaryTest` 6, `PlaneBoundaryTest` 33, `ApplyPathBoundaryTest` 13, `CompositionBoundaryTest` 10, `MetricsDeclarationConstructorTest` 8, `ReadBackSecretBoundaryTest` 3; gateway `MetricsDeclarationBuilderTest` 15 (one added);
launcher boot ITs `MetricsDeclarationEndpointsIT` 6, `MeshDisabledBootIT` 1, `MasterKeyRequiredAtBootIT` 1; `./contracts/run-checks.sh --strict` ALL CHECKS PASSED (the four `fm-*` view-model fixtures untouched). **The broker-gated tests RAN** (a local `solace-pubsub-enterprise:10.25.0.177`, started by the test, never pulled, never a remote broker):
`JcsmpSampleSubscriberIT` 8 of 8 and `PushBrokerIT` 5 of 5, including `aRealDeclaredSolaceEventMetricIsIngestedEndToEnd` (the reviewed fixture's `filemesh.heartbeats`, from a PERSISTENT publisher, through the real session manager, the real secret store and the real ingest service to three stored points 1, 2, 3 under a `broker-push` series),
`aStartEventWithAPasswordIsNeverDeliveredAndNothingOfItIsKept`, `aBrokerOutageIsAGapAndReconnectResubscribes` (the count continues, no reset). MF26 was measured again on this image (a/B/c versus a/b/c). **Not run (Step 2-4 stays unticked for this reason):** the full reactor build and the full launcher `verify` (three boot ITs only), the frontend's tests, `PushedMetricsQueryableOverRestIT`
(**left to P11-7.2**: the plan says whichever of P11-5.1 and P11-7.2 merges second creates it, and P11-7.2 is not in this base), P11-5.2 (the live leg), and `ApplyPersistenceConfigurationIT` passes 4 of 5 (D1). **MF14** was filled from the SDK jar (`javap` of `XMLMessageListener`, `getMessageConsumer`, `addSubscription`, `JCSMPProperties`, `JCSMPChannelProperties`) and measured, not from a WebFetch.

*Mutation proofs (each reverted with `cp`, bytes compared).* Payload logged on a parse failure -> `aPayloadSecretNeverLeavesTheDecoder` red. Unmanaged adapter id logged -> the same test red. Wildcard last level allowed -> 5 red (`TopicPatternTest`, `PushMetricTest`, `PushSubscriptionPlanTest`). Newest live placement instead of dropping -> 3 red. Secret-reference re-check skipped -> `aSecretTypedInstanceKey...` red.
Credential appended to `BrokerConnectionMain`'s failure message -> the no-echo test red. Non-finite number passed on -> 2 red. A `TenantEnumerationRepository` held by the session manager -> `PushPathBoundaryTest` red (the real rule, not a planted one). The loop's push call removed -> `theReconcileRunsInsideTheOneBackgroundLoop...` red. Tenant binding removed from both layers -> `anEventForAnotherTenantsTopic...` red
(removing ONE layer stays green: defence in depth). Case-insensitive key match -> red. `ts` taken at processing -> red. `DP_POLL` as the discriminator -> red. Credential copy not zeroised -> red. A reconcile that waits for the control thread -> red (`aReconcileNeverWaitsForTheBroker`, after I made it fail by assertion instead of hanging). Body bound removed -> red. One count for every metric -> red. Count on a gauge accepted -> red.
Gate 16 removed from Java only -> 3 red; from Python only -> 2 red; the missing-field branch removed -> red (an error, not an assertion). Schema: last-level wildcards allowed -> 2; substitution token allowed -> 2; `then` or `else` of the count/value rule removed -> 1 each; count-on-a-gauge rule removed -> 1; `instanceKey` opened -> 1; `#`, `+`, empty level allowed -> 3; `eventFilter.equals` optional -> 1;
`..` guard removed from the key pointer -> 1; `extract` left open -> 1; branch opened to extra members -> 2 (`path`, `queryParams`); `instanceKey` not required -> the Python's gate 16 still refuses it (so the negative is attributed to gate 1 only by the schema). Database: `UPDATE` granted on `broker_connection` -> red; `BROKER_CREDENTIAL` out of the purpose CHECK -> 13 red across two ITs; the live-once index widened -> 2 red; `ambiguous_placement` out of the counter's reasons -> 2 red;
the credential stored as `CONFIG_FIELD` -> red. `REAPPLY_SUBSCRIPTIONS` removed -> exactly the outage test red (1 of 8).

**OR-112 (1) note (2026-10-07, lane `p/or112a`; MON-02; the ruling wins over the text above).** A File Mesh instance applied with `manual` was never subscribed: `PushSubscriptionPlan` attributes only the generation in force (the newest to reach `restart-observed`), and a manual apply never reached it. It follows the same reader as path 1 (`PushRowsAdapter.inForceGeneration` -> `RestartObservedGenerations`), so it is planned from the moment the data plane observes a container start later than the apply's write, and not before. `StartObservationIT.aManualAppliedFileMeshInstanceIsPlannedForPushOnlyAfterAnObservedRestart` runs it end to end over the real engine, the real poller round (a scripted host answers `docker inspect`), the real reader and `PushRowsAdapter`: nothing is planned while the generation is `persisted`; a start BEFORE the write still plans nothing; a start after it plans the route keyed by the adapter id the generation configures, with the instance and its live placement. File Mesh declares no probe the poller can run, which is why the observation is independent of the health probe (`aPollRoundObservesTheStartOfAManualAppliedPlacementWithNoProbe...`).

### Task P11-5.2 — Live browser delivery of pushed metrics (conditional, M-Q12's live leg)

**Status: CONDITIONAL, not written in full (`OR-95`, OQ-14 (a)).** The owner ruled that M-Q12's live-delivery leg is built **in plan 011**, but
**only once open items 32/33/35 are closed** (the browser mesh gates; `OR-81` and the §11.3 spike result do not open them). Until then pushed metrics
are REST-only, MW5 does not claim live delivery, and nothing in this plan builds the ticket or ACL scope. **Scope when it opens:** the backend half only
(a post-RBAC ticket or ACL scope for the MI's business-metric topics under the ruled leg-13 shape, `OR-81`, with its own contract change); no frontend
change. **Depends on:** P11-5.1 merged, open items 32/33/35 closed by the owner. The task's steps, files and tests are written, and reviewed, when it opens.

## MW6 — Query and export (DP side)

### Task P11-6.1 — `MetricsQueryService`: tier routing, the raw-tail stitch, type-aware aggregation, gaps, caps

> **Owner rulings applied (`OR-92`).** **OQ-22:** the discovery defaults D-3 (derived gaps, interval history) and D-6 (tier thresholds 7 d / 90 d, raw-tail stitch) are accepted.

**Wave:** MW6. **Depends on:** P11-3.2, **P11-4.3** (`JdbcRestartObservedGenerationRepository`, which backs
`activeMetrics`). **Parallel with:** P11-6.2.

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/metrics/query/{MetricsQueryService,RangeQuery,RangeResult,SeriesView,QueryRefusal,MetricsQueryRefusedException,ObservedMetric,CollectorHealthView}.java`;
Create `backend/dp-core/src/main/java/com/solace/sm/dp/metrics/query/{MetricsQueryServiceImpl,TierRouter,Aggregator,GapDeriver,QueryProperties,MetricsQueryConfiguration}.java`,
`…/dp/apply/persistence/{JdbcMetricsQueryRepository,QueryPersistenceConfiguration}.java`; Modify
`DataPlaneConfiguration.java` (**two** `@Import` lines); Create tests `…/query/{TierRouterTest,AggregatorTest,MetricsQueryServiceIT}.java`.

```java
public interface MetricsQueryService {
    /** instanceIds are ALREADY reach-filtered by the gateway (M-Q17); the DP re-binds the tenant (I5). */
    RangeResult range(UUID organizationId, RangeQuery query);    // throws MetricsQueryRefusedException
    RangeResult instant(UUID organizationId, RangeQuery query);
    List<ObservedMetric> observed(UUID organizationId, String entityType, Set<UUID> instanceIds);   // the catalog's DP half
    /** The instance's CURRENT active metric names: the list of the newest generation that reached restart-observed,
     *  or generation 0 when none has (M-Q15; P11-4.3's query). The gateway's only source for the default layer. */
    List<String> activeMetrics(UUID organizationId, UUID instanceId);
    /** reasons: the caller's organization only; runwayEnd, unfrozenClosedPartitions, disk state: install-level. */
    CollectorHealthView collectorHealth(UUID organizationId);
}
public record RangeQuery(String metric, String entityType, List<UUID> instanceIds, Map<String, String> labels,
        Instant from, Instant to, Resolution resolution, SeriesBy seriesBy, Across acrossInstances, List<String> groupBy) {
    public enum Resolution { AUTO, RAW } public enum SeriesBy { INSTANCE, REPLICA } public enum Across { NONE, SUM, AVG, MIN, MAX } }
public enum QueryRefusal { SERIES_CAP_EXCEEDED, AGGREGATION_NOT_APPLICABLE, GROUP_BY_REQUIRES_AGGREGATION,
        STAT_REQUIRES_AGGREGATION, METRIC_UNTYPED }
```

**The rules:**
- Tier: ≤ 7 d raw (bucketed on the fly to ≤ `maxPointsPerSeries` 1000, bucket ≥ the interval); 7–90 d hourly;
  > 90 d daily; `RAW` forces raw (the gateway already required `metrics.forceRaw`).
- **Raw-tail stitch (discovery default D-6, S-L):** rollup-tier ranges serve the un-rolled and within-late-window tail from raw,
  re-bucketed to the tier's bucket, with `coverage.provisionalFrom` set.
- **Counter rates use the actual `ts` deltas at every tier (M-Q24 (8): *"rates on actual ts deltas"*),** never the
  nominal interval. Gauges render `none`. The rendered value is `rate/s × rateSeconds` (60).
  - **Raw, one sample:** `rate_i = increment_i ÷ (ts_i − ts_{i−1})`, where `ts_{i−1}` is the previous sample of the
    same series (a window `lag`). A NULL increment (a gap, a first sample, a decrease without restart) gives no
    point.
  - **Raw, bucketed on the fly:** `Σ increment ÷ Σ (ts_i − ts_{i−1})` over the bucket's samples with a non-null
    increment. The **covered span** is the denominator, not the bucket width, so a bucket with a gap is not
    understated.
  - **Hourly and daily tiers:** `delta ÷ covered_seconds` (P11-2.1's column, filled by P11-3.2). A NULL `delta` gives
    no point, never 0.
  - **Across placements (M-Q20: counters sum):** the instance's rate is the **sum of the per-placement rates**, each
    computed as above. It is not `Σ delta ÷ Σ covered_seconds` over the placements, which would average them.
- **M-Q20:** counters sum across placements. Gauges from more than one **overlapping** placement become one line
  **per placement**; non-overlapping placements (a re-target) form one continuous line.
- Across instances: gauges use `sum(sum)/sum(count)`, never `avg(avg)`.
- **Gaps are server-declared:** raw spacing > 2 × the segment interval; rollup `sample_count < expected_count`.
  Absent buckets emit no tuple. **The threshold, per served tier (DMR-21; discovery default D-3, OQ-22, not an owner ruling):**
  **raw** — spacing between consecutive samples of a series greater than 2 × that segment's declared interval, and the response's
  `gapThresholdSeconds` is that value; **hourly and daily** — an absent bucket, or one with `sample_count < expected_count`, is a gap,
  and `gapThresholdSeconds` is 2 × the tier's **bucket width** (this is what `dynamic-metrics/design.md` §5.6's `7200` beside
  `bucketSeconds: 3600` means: 2 × the bucket, not 2 × a scrape interval).
  > **2026-10-07, combined Fable review (OR-70), finding MON-01: the rollup half of that rule is superseded.** The collector re-arms a
  > source from the dispatch tick that fired it, so its realised period is always a little longer than the declared interval and a
  > closed hour at 30 s holds ~118 of the 120 samples `expected_count` says; `sample_count < expected_count` therefore drew a
  > `partial-samples` band on nearly every hour and day of every polled series, and a day with an absent hour (which adds nothing
  > to the day's `expected_count`) raised none. A rollup bucket is now a gap when the longest spacing between two consecutive
  > samples that **ended in it** exceeded 2 × the interval in force (`sm_metrics` `V003`: `max_gap_seconds`, `interval_seconds`;
  > judged by the query, never frozen into the data; a row from before `V003` is unknown, not a gap). It looks back across the
  > bucket's edge, and a bucket that follows an absent bucket is not flagged twice. `expected_count` is unchanged and still reported.
- **The cap** counts **rendered series** (10, M-Q24 (10)) → `SERIES_CAP_EXCEEDED {cap, projected, seriesBy}`. **Under M-Q20 a gauge
  reported by more than one overlapping live placement renders one line per placement, so the projection counts each live placement
  of such a gauge as a series** (`DM-D7`'s *Σ replicas* means live placements here; DMR-2), not one per instance.
  `groupBy` without aggregation → `GROUP_BY_REQUIRES_AGGREGATION`. With the label allowlist empty in v1, any
  `labels` or `groupBy` key is a `400`. An instant query over > 1 instance with `NONE` → `STAT_REQUIRES_AGGREGATION`.
  A `histogram` → `AGGREGATION_NOT_APPLICABLE`.
- `statement_timeout` 30 s (M-Q22 (1)) via `SET LOCAL` in the query transaction. Query/export DB work takes a permit
  from the **one** `MetricsDbBudget` bean (4 permits, M-Q22 (6)) that **P11-3.1 created**. This task injects it and
  never builds a budget of its own.
- **Retired placements and abandoned instances (S-K):** their series stay queryable over the ranges they have data
  for. A re-target's retired placement contributes the earlier part of the continuous instance line (M-Q20).
- **`collectorHealth`:** `reasons` sums `ingest_counter` over the last 24 h **for the caller's organization only**
  (bound `tenant_id`). `unfrozenClosedPartitions` is one bound catalog read: the attached children of
  `sm_metrics.metrics_raw` (`pg_catalog.pg_inherits`) whose partition upper bound is at or before `now()` (UTC),
  minus those with a row in `sm_metrics.partition_freeze_log` (written by `MetricsPartitionMain freeze`, P11-2.1).
  `disk` is P11-3.2's alarm **state only**.
- **Tier thresholds** (≤ 7 d raw, 7–90 d hourly, > 90 d daily) and the raw-tail stitch are **discovery default D-6**,
  not an owner ruling until OQ-22.

- [ ] **Step 1: Failing tests.** `sevenDaysIsRawBucketedToAtMostOneThousandPoints()`, `thirtyDaysIsHourlyWithAProvisionalRawTail()`
  (S-L at 14:40, 2 h late window), `aForcedRawRangeReadsRaw()`, `counterRateUsesIncrementsNeverTheRawClimb()`,
  **`aCounterRateUsesTheActualTimestampDeltasNotTheNominalInterval()`** (a 30 s source whose samples arrive with ±10 %
  jitter, for example Δts of 27, 33, 30 and 29 s with increments of 27, 33, 30 and 29: the expected per-sample rate
  is exactly 1.0/s at each point, where a nominal-30 s rate would give 0.9, 1.1, 1.0 and 0.967. The bucketed and
  hourly-tier variants give Σ increment ÷ Σ Δts), `anHourlyCounterRateIsDeltaOverCoveredSeconds()` (an hour with a
  20-minute gap: the rate is over the 40 covered minutes),
  `overlappingGaugePlacementsAreSeparateLinesAndCountersSum()` (M-Q20: 400 and 600 → two lines; counters add),
  `aRetargetedInstanceIsOneContinuousLine()`, `gaugeAcrossInstancesIsSumOverCountNotAvgOfAvg()`,
  `gapsAreDeclaredAndAbsentBucketsEmitNothing()`, `theCapCountsRenderedSeries()`,
  **`aGaugeOnTwoOverlappingPlacementsCountsTwoTowardTheCap()`** (6 instances of which one has two overlapping live placements for a gauge
  project 7, not 6), **`aGapIsJudgedByItsServedTiersOwnThreshold()`** (raw: 2 × the interval; hourly: 2 × 3600), each refusal code provoked,
  `aSlowQueryHitsTheStatementTimeout()`, `aFifthConcurrentQueryWaitsForTheBudget()`, `everyStatementBindsTheTenant()`,
  **`aRetiredPlacementAndAnAbandonedInstanceAreNeverCollectedAndTheirHistoryStaysQueryable()`** (S-K, the query
  half: a range over the retired placement's period returns its samples),
  `activeMetricsIsTheRestartObservedGenerationsList()` (gen N+1 staged, not restarted → gen N's list),
  `unfrozenClosedPartitionsCountsClosedMonthsWithNoFreezeRecord()` (two closed months, one frozen → 1; the open month
  never counts), `collectorReasonsAreTheCallersOrganizationOnly()` (tenant B's counters never appear in A's view).
- [ ] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY dp-core`; `VERIFY arch-tests`; `VERIFY launcher`** (the new configurations are imported into the production boot).
- [ ] **Step 5: Prove they fire.** Average the gauge averages → red. Drop the stitch → the S-L test goes red (an empty
  right edge). Count instances instead of rendered series → the cap test goes red. Count a gauge's overlapping placements as one series → the two-placement
  cap test goes red. Judge an hourly gap by the scrape interval → the per-tier threshold test goes red. Divide by the nominal interval →
  the jitter test goes red. Divide an hourly delta by 3600 → the covered-seconds test goes red. Filter series to live
  placements → the S-K query test goes red. Count every closed month without reading the freeze log → the unfrozen
  test goes red. Drop the tenant bind from the reasons read → the organization-only test goes red. Revert each.
- [ ] **Step 6: Commit.** `feat(dp-core): metrics range and instant queries — tier routing with a raw tail, type-aware aggregation, declared gaps (M-Q10, M-Q20, M-Q22, M-Q24)`

**Results (P11-6.1, executed 2026-10-06 on `p011/p61` off `a8895442`, Sonnet 5.5; not yet wave-reviewed).** Built: the `dp-api` read side (`MetricsQueryService`, `RangeQuery`, `RangeResult`, `SeriesView`, `ObservedMetric`, `CollectorHealthView`, `QueryRefusal`, `MetricsQueryRefusedException`, plus **`MetricsQueryUnavailableException`**, an addition: the `503` the gateway needs for a spent budget or a statement timeout, so it never catches a dp-core type); in dp-core `TierRouter`, `Aggregator`, `GapDeriver` (pure), `MetricsQueryServiceImpl`, `QueryProperties`, the `MetricsQueryRows` seam, `JdbcMetricsQueryRepository` (one read-only transaction, `set_config('statement_timeout', ..., true)` bound) and the two configurations (two `@Import` lines; `DataPlaneConfigurationTest` updated). No migration (V017 and `sm_metrics` V002 are not this task's; V002 is P11-5.1's, OR-107's broker-connection counter is therefore **not** in `collectorHealth.reasons` yet: P11-5.1 adds it beside `reasons`). No contract edit (none named). Red first: unit 28 of 28 errored on stubs; the IT 28 of 33 failed against a stubbed `run`. Green: unit **28**, `MetricsQueryServiceIT` **33**, `DataPlaneConfigurationTest`, arch-tests (`PlaneBoundaryTest` 20, `ApplyPathBoundaryTest` 7, `CompositionBoundaryTest` 5, `MetricsDeclarationConstructorTest` 5, `ReadBackSecretBoundaryTest` 2), and the launcher's production boot (`MetricsDeclarationEndpointsIT` 6, `SolutionManagerConfigurationTest` 5). Targeted runs only, as the execution model says; no full reactor build. **Mutation proofs, each red then restored:** average the gauge averages (AggregatorTest); divide by a nominal interval (AggregatorTest, 2); counters across placements as a mean of rates (AggregatorTest); provisional edge one hour late (TierRouterTest, 2); drop the stitch (IT, 2); count instances not rendered series (IT, 2); overlapping gauge placements as one series (IT); judge hourly gaps by the scrape interval (IT, 2) and report that threshold (IT, 3); divide an hourly delta by 3600 (IT); filter to live placements (IT, 2); count closed months without the freeze log (IT); drop the tenant bind from the reasons read (IT, 2); activeMetrics ignoring the restart-observed generation (IT); no budget permit (IT, 2); no statement timeout (IT, the safety valve makes it fail rather than hang); series read without its tenant bind (IT `everyStatementBindsTheTenant`: the behavioural isolation test did NOT fire, because the raw read is bound independently, which is defence in depth working); groupBy without aggregation allowed (IT). **Readings and deviations, for the reviewer:** (1) the IT is in `dp/apply/persistence/` (the fixtures are package-private there), not `dp/metrics/query/`. (2) Raw bucket width is `max(ceil(range/(maxPoints-1)), longest interval)`, so an unaligned 7 d range never touches more than 1000 buckets. (3) `gapThresholdSeconds` on raw is `max(2 x interval, bucket)`, which equals 2 x interval whenever the bucket is not wider than twice the interval; gaps are derived from the emitted points. (4) The provisional edge restates `RollupService.window` (package-private elsewhere); the IT runs the real service and compares stitched and stored hours. (5) A gauge `SUM` across instances is the sum of the instances' means, not DM section 5.7's literal `sum(sum)` (which scales with the sampling rate). (6) Under M-Q20 a gauge whose placements overlap cannot be aggregated across instances: `AGGREGATION_NOT_APPLICABLE`. (7) `METRIC_UNTYPED` is raised for a metric with no series and no definition among the requested instances (the contract says it has "no v1 trigger"; the enum value needed one to be provoked). (8) Two placements overlap only when they share more than `placement-overlap-grace` (default 1 minute), so a re-target is one line. (9) `last24h` counts the UTC days that overlap the last 24 hours (the counter is per day). (10) `partial.unavailable`: an authorized instance with no point is `not-collecting`, or `collector-degraded` when it has a scrape, parse, deadline, buffer or runway failure counted in that window. (11) `seriesBy: REPLICA` with an aggregation is the aggregation over instances. (12) Hourly over 90 d is up to 2160 points and daily beyond 1000 is possible: the plan bounds only the raw tier at 1000, so neither is re-bucketed. (13) `instanceId` is null on an across-instances line (the contract's `seriesRef.instanceId` is required: P11-7.2 must decide). (14) `replicasInactive` is 0 and `state` is `OK`: both are the gateway's composition. (15) `disk` is null until the first measurement. (16) A bucket `sampleCount < expectedCount` is a gap on rollup tiers except in the provisional tail.

### Task P11-6.2 — Streaming export: bound `SELECT`, CSV in the application, limits (M-Q18, M-Q22)

> **Owner rulings applied (`OR-92`).** **OQ-22:** D-12 (`GET /export/range`) accepted. **OQ-11 (a)** concerns the archive, not this export.

**Wave:** MW6. **Depends on:** **P11-3.1** (the one `MetricsDbBudget`) and P11-2.1's tables; **not P11-3.2**: the repository reads `metrics_raw` and
`series` only, and `ExportStreamingMeasurementIT` generates its own rows ([per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)). **Parallel with:** P11-6.1 and P11-3.2.

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/metrics/export/{MetricsExportService,ExportCommand,ExportPlan,ExportBusyException,ExportTooLargeException}.java`;
Create `backend/dp-core/src/main/java/com/solace/sm/dp/metrics/export/{MetricsExportServiceImpl,CsvEncoder,ExportProperties,MetricsExportConfiguration}.java`,
`…/dp/apply/persistence/{JdbcMetricsExportRepository,ExportPersistenceConfiguration}.java`; Modify
`DataPlaneConfiguration.java` (**two** `@Import` lines); Create tests `…/export/{CsvEncoderTest,MetricsExportServiceIT,ExportStreamingMeasurementIT}.java`.

```java
public interface MetricsExportService {
    /** Before any byte is written: projected rows = Σ series (range ÷ interval); > limit → ExportTooLargeException. */
    ExportPlan plan(UUID organizationId, ExportCommand command);
    /** Streams CSV rows into out; never buffers the result. Throws ExportBusyException past the concurrency caps. */
    void stream(UUID organizationId, ExportCommand command, java.io.OutputStream out) throws java.io.IOException;
}
public record ExportCommand(String metric, List<UUID> instanceIds /* reach-filtered */, Instant from, Instant to, UUID correlationId) {}
```

The repository runs one bound `SELECT i.instance_id, s.placement_id, r.ts, r.value FROM sm_metrics.metrics_raw r JOIN
sm_metrics.series s … WHERE r.tenant_id = ? AND s.metric_name = ? AND s.instance_id = ANY(?) AND r.ts >= ? AND r.ts < ?
ORDER BY …` inside a read-only transaction (autocommit off), with `setFetchSize(10_000)`, `TYPE_FORWARD_ONLY` (PgJDBC streams from a cursor only with autocommit off,
`TYPE_FORWARD_ONLY`, a single statement **and a fetch size greater than 0**; *"setting it back to 0 restores default behavior of caching
all rows"*, DMR-20, WebFetch 2026-10-05 of `jdbc.postgresql.org/documentation/query/`), `SET
LOCAL statement_timeout = '30min'` (M-Q22 (2)) and a `RowCallbackHandler` writing through `CsvEncoder`. `CsvEncoder`
is hand-written, with **no dependency** (uuids, ISO-8601 UTC timestamps, finite doubles; RFC 4180 quoting applied
anyway). Limits (all configurable): 20 M rows (4, `sm.dp.metrics.export.row-limit`); 1 export per organization and
2 globally (3); a permit from the **one** `MetricsDbBudget` bean P11-3.1 created (6), injected, never rebuilt here. A
mid-stream failure closes the stream: the status is already sent, so the CSV ends early and `ExportAudit` records the
outcome.

- [x] **Step 0: Fill MF5 (local half); re-read MF16** (filled at P11-3.1 Step 0; if it is empty, stop).
  `ExportStreamingMeasurementIT` (serialized Testcontainers slot)
  generates 5 M and 20 M rows server-side (`INSERT … SELECT generate_series`) and streams each through the **real**
  repository into a counting sink. It samples heap-used every 100 k rows and asserts the high-water mark does not
  grow with the row count (within a stated tolerance). It records rows/s, which P11-8.1 Step 6b uses to project the
  105 M-row run's duration against the 30-minute timeouts. If heap grows, **stop**: the stack is not
  streaming, and M-Q18 (a) needs another look before anything ships.
- [x] **Step 1: Failing tests.** `thePlanRefusesPastTheRowLimitBeforeAnyByte()`, `aSecondExportInOneOrganizationIsBusy()`,
  `aThirdGlobalExportIsBusy()`, `everyValueIsBoundNoTextIsInterpolated()` (the statement text is a constant; the test
  asserts it), **`theStreamingStatementCarriesANonZeroFetchSize()`** (the executed statement's `getFetchSize()` is greater than 0, with
  autocommit off and `TYPE_FORWARD_ONLY`), `theCsvIsRfc4180AndUtc()`, `anExportNeverReadsAnotherTenant()`, `aClientDisconnectReleasesTheConnectionAndThePermit()`,
  `theExportTimeoutIsThirtyMinutes()`, **`queriesAndExportsShareOneBudgetOfFour()`** (3 queries + 2 exports started
  together: exactly 4 hold a permit, the fifth waits, whichever kind it is).
- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY dp-core`; `VERIFY arch-tests`; `VERIFY launcher`** (the new configurations are imported into the production boot).
- [x] **Step 5: Prove they fire.** Use `queryForList` (buffering) → the measurement IT's heap assertion goes red. Set the fetch size to 0 → the heap assertion **and** `theStreamingStatementCarriesANonZeroFetchSize` go red.
  Remove the per-organization cap → red. Interpolate the metric name → the constant-text test goes red. Give the
  export service its own `new MetricsDbBudget(4)` → `queriesAndExportsShareOneBudgetOfFour` goes red. Revert each.
- [x] **Step 6: Commit.** `feat(dp-core): streaming metrics export by bound SELECT, CSV in the application, bounded (M-Q18, M-Q22)`

---

**Results (P11-6.2, 2026-10-06; lane `p011/p62`, base `71eafb66`).** `MetricsExportService` (`plan`, `stream`) with its five `dp-api` types, `MetricsExportServiceImpl`, `CsvEncoder`, `ExportProperties`, `ExportRows` (the port, so the service holds no JDBC), `JdbcMetricsExportRepository`, and the two configurations (two `@Import` lines, `DataPlaneConfiguration` and its test). No migration. Red first was a compile failure (188 error lines over the new tests). **Step 0, MF5 local half, MEASURED:** 200 series x 25 000 and x 100 000 rows generated server-side, streamed through the real service and repository into a counting sink; live heap (full GC before each sample, every 100 k rows) went 23 MB baseline to **26 MB at both 5 M and 20 M rows** (growth 2 MB, 50 and 200 samples); 5 M rows = 518 MB of CSV, 20 M = 2.08 GB; throughput **202 k to 423 k rows/s** across runs (the host was shared with other lanes), so 105 M rows projects to about 4 to 9 minutes, well inside the 30-minute timeouts (P11-8.1 Step 6b re-projects on its own host). MF5 is therefore filled for the local half only; the 105 M-row half, the 'shipped' claim and row 60 'served' remain P11-8.1's. **A finding worth keeping:** the first measurement run FAILED (1170 MB growth at 5 M, 4659 MB at 20 M, about 233 B per row) because the harness built the `JdbcTemplate` and the `DataSourceTransactionManager` over two different `DataSource` instances, so the statement ran in autocommit and PgJDBC cached every row despite the fetch size; production shares one (`smDataJdbc` and `smDataTransactionManager`). The repository now **refuses** (`IllegalStateException`) a stream statement on an autocommit connection, so that mistake is an error and not a silent buffer (`aStreamOutsideATransactionIsRefusedNotSilentlyBuffered`). Tests: `dp-core` unit 26 (`CsvEncoderTest` 4, `MetricsExportServiceTest` 19, `DataPlaneConfigurationTest` 3), IT `MetricsExportServiceIT` 11, `ExportWiringIT` 2, `ExportStreamingMeasurementIT` 1, `IngestWiringIT` 4 (regression), `launcher` `MetricsExportBootIT` 1 and `ApplyEventsProductionBootIT` 1, `arch-tests` 59, 0 failures. **18 mutations (fetch size 0 and buffering each also against the measurement IT), each red then restored byte for byte** (`git checkout` from the staged tree): buffering the result, fetch size 0 (the cursor test and the heap assertion), per-organization cap removed, global cap removed, a service-owned `MetricsDbBudget(4)` (3 unit tests and the wiring IT), tenant bind dropped, metric name interpolated into the text, plan limit skipped, mid-stream backstop removed, autocommit guard removed, `set_config` timeout removed (`SHOW statement_timeout` read 0), slot release removed, permit release removed, segment split removed, CSV quoting of a line break removed, timestamp rendered in the JVM zone, read-only transaction removed, and `MetricsExportConfiguration` dropped from `DataPlaneConfiguration` (aggregator test and boot IT). Not wave-reviewed.

**Decisions and deviations for the reviewer and for P11-7.2.** (1) `stream` returns `long` (data rows written), not `void`, because `ExportAudit.record(..., long rows, ...)` needs the count; on a mid-stream failure the count is not recoverable from the exception, so P11-7.2 can audit `count` only for `completed`. (2) `ExportBusyException` carries a `Reason` (`ORGANIZATION`, `INSTALL`, `DATABASE_BUDGET`): the first two are the contracted `429 export-concurrency`; the third is the shared budget staying spent for `sm.dp.metrics.export.budget-wait` (default 30 s), which the contract does not name and P11-7.2 must map (a `503` busy, like the query side's `MetricsBusyException`, is the natural answer). (3) All refusals happen before the first byte, inside `stream`; whether a `429` then reaches the client through `StreamingResponseBody` before the response commits is MF18's measurement in P11-7.2, not assumed here. (4) The projection is an upper bound for a declared interval; a series with no interval (the push path) is projected at `unknown-interval-seconds`, default 1 s, so it never under-counts; and the limit is also enforced on the rows actually written (`ExportTooLargeException` mid-stream, the CSV ends early). (5) `plan` and the projection read take a permit from the shared budget; `plan` is one bound read of `series` and `series_segment` and selects no raw row. (6) The two ITs that need `MetricsDb` (package-private) live in `dp.apply.persistence` beside `RollupServiceIT`, not in `…/export/` as the Files list says. (7) Order is `series_id, ts`, the primary key's order, chosen so the planner can read it from the index; the plan shape (no `Sort` node) is NOT asserted or measured here, so P11-8.1's at-scale run should read `EXPLAIN` once; the columns are `instance_id,placement_id,timestamp,value`. (8) The statement timeout is `set_config('statement_timeout', ?, true)`, bound and transaction-local, because `SET LOCAL` cannot take a bound value (`I5`). (9) MF5's table row in this plan is not edited here (outside this task's section); its local half is the figures above. **Amended in wave 3 (W3-12):** a series with no interval is no longer projected at `unknown-interval-seconds` (that guess projected File Mesh's 5 s heartbeat at five times its real count, so `EXPORT_TOO_LARGE` fired at about a fifth of the real capacity); the projection COUNTS its raw rows in the range with one bound `count(*)` over `(series_id, ts)`, stopped at the room left under the limit plus one, inside the same permit and statement timeout. `unknown-interval-seconds` is retired and still binds. **Amended in wave 3 (W3-14):** a `BROKER_PUSH` batch is answered `RetryAfter` after `sm.dp.metrics.ingest.push-caller-wait` (default 250 ms) instead of `caller-wait`, so the push worker keeps draining its bounded receive queue through a slow commit; the batch stays held.

---

## MW7 — Routes, catalog, dashboards

### Task P11-7.1 — CP dashboards (M-Q23's document shape) and the export audit port

> **Owner rulings applied (`OR-92`, `OR-93`).** **OQ-7 (b):** a metric with `defaultChart: false` gets no default widget. **OQ-8 (a):** reset clears hide-overrides and default placements and keeps every shared widget. **OQ-18 (a):** REQ-010 stays at the owner's three criteria; the default layer is the adopted demo shape and SM-D31 (PROPOSED). **OQ-28 (b):** starts after P11-2.3 (nothing beyond S3); local until its wave review.

**Wave:** MW7. **Depends on:** P11-2.3, plan 010 T3.2 (landed; nothing more from plan 010, [per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why)); OQ-7, OQ-8, **OQ-28 (b)** (under (a): W11). **Parallel:** ⛔ P11-7.2 follows.

**Files:** Create `backend/cp-api/src/main/java/com/solace/sm/cp/api/dashboard/{DashboardStore,DashboardView,WidgetSubmission,DefaultLayerInput,InvalidDashboardException}.java`,
`backend/cp-api/src/main/java/com/solace/sm/cp/api/audit/ExportAudit.java`; Create `backend/cp-core/src/main/java/com/solace/sm/cp/dashboard/{DashboardService,DashboardDocumentValidator,DefaultLayerDeriver,DashboardConfiguration}.java`,
`…/cp/persistence/{JdbcDashboardRepository,DashboardPersistenceConfiguration}.java`,
`…/cp/audit/{AuditLogExportAudit,ExportAuditConfiguration}.java` (over the existing `AuditLog` and
`cp-core/.../cp/persistence/JdbcAuditLog.java:14-20`, which has **no production bean today**; measured: only
`AuditLogIT` constructs it). `DashboardPersistenceConfiguration` (in `cp.persistence`, because a `@Bean` taking
`NamedParameterJdbcTemplate` must live there, `ControlPlanePersistenceConfiguration.java:34-43`) registers
`JdbcDashboardRepository` and the first production `AuditLog` bean; `DashboardConfiguration` and
`ExportAuditConfiguration` build the services from interface types. Modify `ControlPlaneConfiguration.java` (**three**
`@Import` lines, [the configuration table](#readiness-and-configuration-per-area)); Create tests `…/dashboard/{DashboardDocumentValidatorTest,DashboardServiceIT}.java`, `…/audit/AuditLogExportAuditIT.java`.

**The document (M-Q23 (a) `OR-78`), `DemoDashboardStore.java:86-170`'s shape, generalized:**
- `schemaVersion` 1, `title` ≤ 120 after trim, `mark ∈ line|gauge|single-stat`, `layer` `shared`,
  `query{metric, selector{entityType, instanceIds}}` with **exactly one instanceId, equal to this dashboard's**
  (M-Q10: one instance per saved widget), and `options/axes/legend/series/thresholds/refresh` of the right JSON types.
  The layout is a 12-column grid, each widget placed exactly once, with the demo's bounds.
- **The Cache-specific checks are replaced:** `metric ∈` the instance's package-version declared metrics (the caller
  passes `DefaultLayerInput`: declared metrics from `SolutionCatalog.documentOf` for the instance's version, and the
  active list from the DP through **`MetricsQueryService.activeMetrics(org, instanceId)`**, P11-6.1's `dp-api` read;
  the gateway composes the two, the CP never calls the DP). `entityType` = the instance's component type.
- **The default layer is derived, never stored:** one widget per active, non-histogram metric with `defaultChart`
  (OQ-7 (b)). Counters are `line` rate, gauges `line`. Product-default widgets can be **hidden** (stored as
  overrides) and never deleted. **Reset** clears overrides and default placements and keeps shared widgets (OQ-8 (a)).
  ⚠️ This behaviour is built as the **adopted demo document shape** (M-Q23) and PROPOSED SM-D31. It is **not** a
  REQ-010 acceptance criterion: REQ-010 carries only the owner's three elements (P11-0.1). Whether it should be one
  is OQ-18.
- An optimistic `revision`: a stale write is `409`. Writes set the OR-25 audit context (`DASHBOARD_*` operations).
- **A dashboard exists only for an instance the DP reports as active.** The CP cannot ask the DP (`I7`), so this service documents the
  precondition and **P11-7.2 enforces it** (`InstanceInventoryService.findActive`): a pending or abandoned instance (an `OR-90`
  orphan is one) gets no dashboard, so the operator runbook's orphan delete never meets this task's `ON DELETE RESTRICT` key (P11-2.3).
- `ExportAudit.record(PrincipalRef, RequestOrigin, TenantId organization, UUID correlationId, long rows,
  ExportResult result)` writes one `HIGH_CONSEQUENCE` `AuditEntry` for verb `metrics.export` on the organization
  (M-Q22 (7)). **How the result is expressed (measured):** `AuditEntry.Outcome` is only `{ALLOW, DENY}`
  (`AuditEntry.java:22`), which is the authorization decision, so an export that was allowed is `ALLOW`; the
  **stream's** result goes in the allow-listed detail key `new_status` (`AuditEntry.java:62-66`, pattern
  `[a-z][a-z_]{0,63}`, `:20`) as one of `completed`, `failed` or `cut` (the async timeout or a client disconnect).
  Detail keys are `correlation_id`, `count` (rows written) and `new_status`, nothing else. A refused export (`403`,
  `422`, `429`) is the interceptor's `DENY` row, not this one.

- [x] **Step 0: Read the landed CP configuration.** If plan 010 has landed a production `AuditLog` bean, reuse it and
  do not register `JdbcAuditLog` again (the commit says which). Record where the landed CP persistence configurations
  are imported.
- [x] **Step 1: Failing tests.** `aWidgetForAMetricOutsideThePackageVersionIsRefused()`, `aWidgetForAnotherInstanceIsRefused()`, **`aWidgetNamingTwoInstancesIsRefused()`** (M-Q10: one instance per saved widget),
  `noCacheLiteralIsInTheValidator()` (grep the class for `cache`), `theDefaultLayerIsDerivedFromTheActiveDeclaredMetrics()`,
  `aDefaultChartFalseMetricHasNoDefaultWidget()`, `aDefaultWidgetCanBeHiddenNotDeleted()`, `resetKeepsSharedWidgets()`,
  `aStaleRevisionIs409()`, `everyWriteIsAuditedAsItsOperation()`, `anExportWritesOneHighConsequenceAuditRow()`,
  `theExportResultIsCarriedInNewStatus()` (each of `completed`, `failed`, `cut`; the detail holds no other key),
  `exactlyOneProductionAuditLogBeanExists()`.
- [x] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY cp-core`; `VERIFY arch-tests`.**
- [x] **Step 5: Prove they fire.** Restore the demo's `METRICS` set → the no-literal test goes red. Reset to empty →
  `resetKeepsSharedWidgets` goes red. **One mutation per remaining guard:** accept a second `instanceId` in a widget →
  `aWidgetNamingTwoInstancesIsRefused` goes red; skip the revision comparison → `aStaleRevisionIs409` goes red; validate a metric against the whole
  catalog instead of the instance's version → `aWidgetForAMetricOutsideThePackageVersionIsRefused` goes red; ignore `defaultChart` →
  `aDefaultChartFalseMetricHasNoDefaultWidget` goes red; make *hide* delete the default widget → `aDefaultWidgetCanBeHiddenNotDeleted`
  goes red; skip the OR-25 audit context on a write → `everyWriteIsAuditedAsItsOperation` goes red; add a fourth detail key (for
  example the metric name) to the export audit → `theExportResultIsCarriedInNewStatus` goes red; register a second `AuditLog` bean →
  `exactlyOneProductionAuditLogBeanExists` goes red; derive the default layer from the declared (not the active) metrics →
  `theDefaultLayerIsDerivedFromTheActiveDeclaredMetrics` goes red; write the export audit as a plain `ALLOW` row, not `HIGH_CONSEQUENCE` →
  `anExportWritesOneHighConsequenceAuditRow` goes red. Revert each.
- [x] **Step 6: Commit.** `feat(cp): per-instance shared dashboards in the demo's document shape, generalized; the export audit (M-Q10, M-Q22, M-Q23)`

**Results (P11-7.1, 2026-10-05; lane commit `875f8d7`, cherry-picked onto `p011/merge`).** `DashboardStore`, `DashboardService`, the document validator, the default-layer deriver, the export audit and the first production `AuditLog` bean. Red first was a compile failure. **12 mutations, each red** (the plan's Step 5 ten, plus a `Permission.METRICS_QUERY` swap and reset-to-empty). Combined verify on the lane: `cp-core` 287 of 287, `dp-core` 360 surefire and 516 failsafe, `launcher` 46 and 43, 0 failures. Not yet wave-reviewed.


### Task P11-7.2 — The 14 `/api/console/v1` routes served from `console-gateway` (M-Q11)

> **Owner rulings applied (`OR-92`).** **OQ-16 (a):** `getCapabilities` carries only `collector.disk.state`, never a byte figure. **OQ-17 (a):** the catalog `collector` block is "per placement serving this metric", and `undeclared_dropped` is capabilities-only (⚠️ departs from M-Q6's "per instance+source"). **OQ-22:** D-10 (empty label allowlist) and D-11 (no metrics notify) accepted.

**Wave:** MW7, after P11-7.1. **Depends on:** P11-1.2, P11-4.1, P11-6.1, P11-6.2, P11-7.1, plan 010 T1.3; MF18.

> **Pinned test (wave review of P11-2.3, `OR-97`).** `DashboardStore.get` returns a fresh view for **any** instance id, including another organization's, so existence and reach rest wholly on this task: `anUnknownOrForeignInstanceIdGets404BeforeDashboardStoreIsCalled` (every dashboard route; the store is a verifying fake that fails the test if `get` is reached). Mutation: call the store before the reach check and the test goes red.

**Files:** Create `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/metrics/{CapabilitiesController,EntityTypesController,MetricsCatalogController,QueryController,ExportController,DashboardController,DashboardResourceResolver,MetricsRateGate,…Request/…Response records}.java`;
Modify `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/GatewayProperties.java` **or** the launcher's
`application` properties, whichever the landed code uses for MVC settings (Step 0), to set the async request timeout;
Create `backend/arch-tests/src/test/java/com/solace/sm/arch/MonitoringBoundaryTest.java` (**one** rule,
`onlyTheRuledCallersEnumerateActiveTenants`) and its planted fixture
**`backend/arch-tests/src/test/java/com/solace/sm/dp/archfixture/monitoring/CollectorReadsAThirdTenantList.java`**
(package `com.solace.sm.dp.archfixture.monitoring`);
Modify **`backend/arch-tests/src/test/java/com/solace/sm/arch/ApplyPathBoundaryTest.java`** (plan 010 T6.7's file, which
carries plan 007 Task 12's `theGatewayHoldsNoDatasource`, `007-…md:1400`, `010-…md:3339-3340`): that **existing**
rule gains M-Q11's **gateway-planted** fixture,
**`backend/arch-tests/src/test/java/com/solace/sm/gateway/archfixture/monitoring/GatewayHoldsADataSource.java`**
(package `com.solace.sm.gateway.archfixture.monitoring`). The rule is not re-declared. ⚠️ **Why these packages
(measured):** a planted fixture is reported only if it sits inside the plane package the rule scans. The existing
fixtures live at `com.solace.sm.{gateway,dp,cp,…}.archfixture` (`arch-tests/src/test/java/com/solace/sm/*/archfixture`,
imported by `withPlantedViolations()`, `PlaneBoundaryTest.java:264-268`), and `theGatewayHoldsNoDatasource` scans
`resideInAPackage("com.solace.sm.gateway..")`. A fixture beside `MonitoringBoundaryTest` (`com.solace.sm.arch…`)
would never be reported. The production import excludes `..archfixture..` through `ExcludeArchFixtures`
(`PlaneBoundaryTest.java:43,46,209`); Step 0 confirms the landed `ApplyPathBoundaryTest` does the same, and the new
fixtures follow it. Create `launcher/src/test/.../{MetricsEndpointsIT,DashboardEndpointsIT,ExportEndpointIT,ExportAsyncTimeoutIT}.java`,
`console-gateway/.../metrics/*ControllerTest.java`, `console-gateway/.../metrics/DashboardResourceResolverTest.java`.

**`DashboardResourceResolver`** (rows 62–66, *"dashboard's scope"*). It implements `ResourceResolver`
(`ResourceResolver.java:23-30`: the resource *"must therefore come from the path, the query string, or the session — never from the request body"*). It reads `dashboardId` from the
path, checks it against `^db_[0-9a-f]{8}-…$` (P11-1.2's pattern), and derives the instance id from it directly
(`dashboard_id = 'db_' || instance_id`, P11-2.3's CHECK). It needs **no database read** to find the instance. It
returns the instance `ResourceRef`, and the existing interceptor then `require`s `dashboard.view`/`edit`/`reset` at
that instance's scope. A malformed id is `404`, exactly like an unknown instance, and the message is never built
from the path.

**The export's async timeout (MF18).** `StreamingResponseBody` runs as Spring MVC async processing, so the request
is bounded by the MVC async timeout, not only by the DB's `statement_timeout`. The async timeout is **set
explicitly** (configurable, default = the export timeout, 30 min, M-Q22 (2)), never left to a container default.

**Authorization and composition:**
- **Row 55** (`GET /entity-types/{entityType}/instances`, `metrics.query` **and** `instance.view`,
  `console-integration.md:397`): the reach classification carries one `Permission` (`OperationClassification.Reach(Permission)`, `:48`), so the handler
  returns `authorizedSubset(METRICS_QUERY) ∩ authorizedSubset(INSTANCE_VIEW)` (P11-1.2's `x-sm-permission-also`).
- Every route calls `authorizationService.require` or the OR-51 reach classification. Query, instant and export:
  the verb must be held at ≥ 1 grant in the organization (else `403`). The requested `instanceIds` are then
  **filtered by `ReachAuthorization.authorizedSubset`, silently, exactly like nonexistent ids** (M-Q17 `OR-79`).
  `resolution: raw` intersects with `metrics.forceRaw`. Export intersects with `metrics.export`.
- `partial.unavailable[]` lists only **authorized** instances with no collected data in range (`not-collecting`) or
  a degraded collector (`collector-degraded`).
- `GET /metrics/catalog?entityType` composes CP (declared metadata for the package versions in use by reachable
  instances) **concurrently with** DP `observed`. Each surface reports its own `Availability`, and a failed plane is
  never an empty list that looks like data (OR-10). **W2-06:** the array stays bare, and each entry carries a required
  `observedAvailability` (`Availability`: `live`, or `unavailable` with a `reason`); a failed DP half sets it
  `unavailable` on every entry, a never-scraped metric is `live` with `lastSeen` and `collector` absent.
- Rate limit **10/s per session** (M-Q24 (5) / M-Q22 (5)) → `429`.
- `POST /metrics/catalog/{name}/type` → `501`.
- Export streams through `StreamingResponseBody` with `text/csv`, and audits via `ExportAudit` (outcome and rows).
  ⚠️ **Row 60 is wired and tested here but not counted served**, and export is not claimed shipped, until P11-8.1
  Step 6 records the 105 M-row stream (M-Q18). `ENDPOINT-INVENTORY.md` row 60 moves to "served" in P11-10.1 only
  if that measurement exists.
- The catalog's per-metric `collector` block is served exactly as OQ-17 rules (P11-1.2). `capabilities.collector`
  carries **only `disk.state`** from P11-3.2's alarm, and `reasons` for the caller's organization only (OQ-16's scope
  rule; row 53 is authentication-only at install scope).
- **The async re-dispatch (MF18, widened).** Export's `StreamingResponseBody` completes on an async dispatch. The
  authorization decision, the `MetricsRateGate` count and the OR-25 audit context are taken **once**, on the initial
  `REQUEST` dispatch; whatever MF18 measures about the interceptor on `DispatcherType.ASYNC`, the route is made to
  count and audit exactly once (for example, the interceptor skips a non-`REQUEST` dispatch, or implements
  `AsyncHandlerInterceptor`, whichever the measurement shows is needed).

- [x] **Step 0: Fill MF18** (WebFetch), and find where the landed code sets MVC properties. Then write
  `ExportAsyncTimeoutIT`: with the async timeout set below the stream's duration, the stream is cut and audited with
  `new_status = cut`; with the configured default, a stream longer than the container default completes and is
  audited `completed`. **In both runs, count** the authorization decisions, the rate-gate increments and the audit
  rows for the one export: each must be exactly **one**. Record all of it. Also read how the landed
  `ApplyPathBoundaryTest` and `PlaneBoundaryTest` exclude `archfixture` packages from the production import.
- [x] **Step 1: Failing tests.**

```java
@Test void eachOfTheFourteenOperationsRequiresItsMappedVerbAndScope() { }   // one per row 53–66, launcher IT
@Test void anOperatorMayExportAndForceRaw() { }                             // plan 009's test, INVERTED (M-Q16)
@Test void aViewerMayQueryButNotExportOrForceRaw() { }
@Test void unauthorizedInstancesAreOmittedLikeNonexistentOnes() { }         // Review Focus 4
@Test void theCapCountsRenderedSeriesOverTheAuthorizedSet() { }
@Test void aCallerWithNoMetricsGrantInTheOrganizationIs403() { }
@Test void theCatalogComposesBothPlanesConcurrently() { }                   // two 200 ms fakes → ~200 ms, not 400
@Test void aFailedPlaneIsDegradedNeverAnEmptyCatalog() { }                  // W2-06: DP down → every entry observedAvailability.state == unavailable, never live-and-empty
@Test void theQueryRateLimitIs429() { }
@Test void metricDeclareIs501() { }
@Test void exportStreamsCsvAndWritesOneAuditRow() { }
@Test void exportPastTheRowLimitIs422BeforeAnyByte() { }
@Test void capabilitiesCarryTheCollectorBlock() { }                         // M-Q21
@Test void dashboardRoutesUseTheDemoWireIdAndDocumentShape() { }
@Test void aDashboardIdResolvesToItsInstanceScopeFromThePathAlone() { }     // DashboardResourceResolverTest; no DB read
@Test void aMalformedDashboardIdIs404LikeAnUnknownInstance() { }
@Test void aDashboardWriteForAnInstanceTheDpDoesNotReportActiveIs404AndWritesNothing() { }   // pending/abandoned (an OR-90 orphan): InstanceInventoryService.findActive is empty
@Test void aViewerOfAnotherInstanceCannotReadThisDashboard() { }            // require at the resolved instance scope
@Test void theExportAsyncTimeoutIsTheConfiguredValueNotTheContainerDefault() { }   // ExportAsyncTimeoutIT (MF18)
@Test void oneExportIsOneAuthorizationOneRateCountAndOneAuditRow() { }      // ExportAsyncTimeoutIT: the ASYNC re-dispatch adds none
@Test void aCallerWithMetricsQueryButNotInstanceViewSeesNoInstances() { }   // row 55's intersection
@Test void capabilitiesNeverCarryInstallWideByteCounts() { }                // OQ-16's scope rule; two organizations
@Test void capabilitiesReasonsAreTheCallersOrganizationOnly() { }
@Test void noRouteFallsBackToTheDemo() { }
```

  And the boundary rules: in the **existing** `ApplyPathBoundaryTest.theGatewayHoldsNoDatasource`, the new
  **gateway-planted** fixture (M-Q11; the L19 residue in discovery §1.2) must be reported by that rule; in
  `MonitoringBoundaryTest`, `onlyTheRuledCallersEnumerateActiveTenants` (callers of `tenantsWithActiveInstances` ∈
  {`HealthPoller`, plan 010 T11.2's batch advancer (the integrator fills the landed name), `KeyRotationMain`,
  `MetricsBackgroundLoop`}), with a planted fifth caller.
- [ ] **Step 2: Run; fail.** **Step 3: Implement.** **Step 4: `VERIFY console-gateway`, `VERIFY launcher`, `VERIFY
  arch-tests`, `./contracts/run-checks.sh --strict`** (the served shapes match `console.yaml`).
- [x] **Step 5: Prove they fire.** Refuse the whole query on one unauthorized id → Review Focus 4 goes red. Drop the
  `forceRaw` intersection → a Viewer's raw query succeeds → red. Compose the catalog serially → the concurrency test
  goes red. Exclude the gateway-planted fixture from `theGatewayHoldsNoDatasource`'s import → the rule no longer
  reports it, so its planted-fixture assertion goes red. Resolve the dashboard's instance from the request body → the
  resolver test goes red. Leave the async timeout unset → the async-timeout IT goes red. Drop row 55's
  `INSTANCE_VIEW` intersection → `aCallerWithMetricsQueryButNotInstanceViewSeesNoInstances` goes red. Skip the `findActive` check before a dashboard write → `aDashboardWriteForAnInstanceTheDpDoesNotReportActiveIs404AndWritesNothing` goes red. Add `metricsBytes` to the
  capabilities view → `capabilitiesNeverCarryInstallWideByteCounts` goes red. Let the rate
  gate count on the async dispatch too → the one-export test goes red (if MF18 shows the interceptor never re-runs on
  `ASYNC`, record that this mutation cannot be expressed and why). Move a fixture to `com.solace.sm.arch.archfixture`
  → its rule no longer reports it, so its planted-fixture assertion goes red. Revert each.
- [x] **Step 6: Commit.** `feat(gateway): the /api/console/v1 metrics, export and dashboard routes served, reach-filtered (M-Q10, M-Q11, M-Q16, M-Q17)`

**Results (P11-7.2, executed 2026-10-06/07 on `p011/p72` off `1262b03b`, Sonnet 5.5; not yet wave-reviewed).**

*Built.* All fourteen `/api/console/v1` operations have a handler in `console-gateway`, in `http/metrics/`: `CapabilitiesController`, `MetricsCatalogController` (rows 54, 55, 57, 61, and 56 through `CatalogComposer`), `QueryController` + `QueryComposer` (58, 59), `ExportController` (60), `DashboardController` + `DashboardResourceResolver` (62-66), `MonitoringInstances` (the active-instance list, the reach filters and the package reads), `MetricsRateGate`, `MetricsErrorAdvice` (+ `ExtendedProblemBody`, `MetricsRejectionException`), the wire (`MetricsWire`, `MetricQueryRequest`, `DeclareMetricTypeRequest`, `HiddenRequest`), `MetricsRoutesConfiguration`, `MetricsGatewayProperties` (`sm.gateway.metrics.*`) and `DataPlaneQueryLimits` (the data plane's own `sm.dp.metrics.query.*` keys). The six missing `ProblemBody.TITLES` entries are added (`ProblemBodyTitlesTest` pins that every `ErrorCode` has one). `PushedMetricsQueryableOverRestIT` is created (path 2's REST half, over the real boot). Arch: `MonitoringBoundaryTest.onlyTheRuledCallersEnumerateActiveTenants` with the planted fifth caller `CollectorReadsAThirdTenantList` (and a test that the four real callers are found calling it), and `ApplyPathBoundaryTest.theGatewayHoldsNoDatasource` gains the planted gateway fixture `GatewayHoldsADataSource`. `ENDPOINT-INVENTORY.md`: the "Served now" table is corrected (it listed 7 of the 32 §1 operations; all 32 are served, plan 010's 25 included) and §1a now says what is served; row 60 is **wired, not counted served** (P11-8.1). No contract edit, no migration, no new dependency.

*MF18, measured.* (1) Boot 3.5.16's own configuration metadata (`spring.mvc.async.request-timeout`: "If this value is not set, the default timeout of the underlying implementation is used"; the reference page fetched was truncated before that row) and Tomcat 10.1's `asyncTimeout` (default 30000 ms, WebFetch of the connector reference): the container default is 30 s, and a 20 M-row export outlasts it, so the timeout is set EXPLICITLY (`AsyncSupportConfigurer.setDefaultTimeout`, default 30 m). (2) **The Spring Framework async reference (fetched through the summarizing tool) said `preHandle` is NOT invoked again on an async re-dispatch. Measured: it IS** (`ExportAsyncTimeoutIT` saw two `requireHeldAnywhere(metrics.export)` decisions for one export). The fix is four lines at the top of `AuthenticationInterceptor.preHandle`: return on `DispatcherType.ASYNC` (a re-dispatch is the container finishing a request the REQUEST dispatch already admitted, and a client cannot cause one). The rate count and the audit row sit in the handler, which is not re-run: one export is one count and one row (measured, and by a MockMvc test through `asyncDispatch`). (3) Where the landed code sets MVC properties: nowhere (`spring.mvc.*` is unset; `GatewayProperties` has no MVC setting), so `MetricsRoutesConfiguration` owns it. (4) `ApplyPathBoundaryTest` and `PlaneBoundaryTest` exclude `..archfixture..` through `PlaneBoundaryTest.ExcludeArchFixtures`; the new fixtures follow it (and the planted gateway fixture is reported only because it sits under `com.solace.sm.gateway..`; proved by moving it).

*Red first.* `MetricsEndpointsIT` against the base (the `metrics` package moved out): **13 run, 11 failures, 1 error**, every contracted route `404` (the first test lists all fourteen). The other new ITs were not run against the base (their module would not compile without the gateway classes the unit tests need); they exercise the same unmapped routes. Green: console-gateway **595** unit tests (new: `MetricsQueryRoutesTest` 18, `MetricsCatalogRoutesTest` 12, `MetricsDashboardRoutesTest` 14, `MetricsExportRouteTest` 12, `MetricsRateGateTest` 3, `DashboardResourceResolverTest` 2, `MetricsSchemaMirrorTest` 3, `ProblemBodyTitlesTest` 1), arch-tests (`MonitoringBoundaryTest` 3, `ApplyPathBoundaryTest` 7, `PlaneBoundaryTest` 20, ...), launcher ITs `MetricsEndpointsIT` 14, `DashboardEndpointsIT` 8, `ExportEndpointIT` 4, `ExportAsyncTimeoutIT` 1, `ExportAsyncCompletionIT` 1 (a 36 s stalled stream completes under the 30 min default and is audited `completed`; ~55 s), `PushedMetricsQueryableOverRestIT` 1, and the neighbours I touched or sit beside (`ProductionAuthenticationIT` 4 after its pinned route set gained the 14, `StopEndpointIT` 10, `InstanceEndpointsIT` 12, `HomeEndpointsIT` 7, `HierarchyEndpointsIT` 6, `CatalogEndpointsIT` 12, `FleetEndpointsIT` 6, `MetricsDeclarationEndpointsIT` 6, `MetricsExportBootIT` 1, `ProductionRateGateAndForwardingIT` 2). `./contracts/run-checks.sh --strict`: ALL CHECKS PASSED (a temporary `node_modules` symlink, removed).

*Mutation proofs (each red, each restored byte-for-byte, checked with `cmp`).* 43 in all. Unit: refuse the whole query on one unreachable id (4 red); drop the forceRaw pre-check; drop the forceRaw intersection; instant also demanding forceRaw; hand the data plane an empty selection; remove the rate gate from `queryRange` and from `exportRange`; the nil UUID replaced by null; the series-cap body without its cap; a byte figure in the disk block (3 red, the mirror test included); an unknown runway reported live; capabilities reasons asked for another organization; the catalog composed serially; a failed data plane reported live; row 55 without its `instance.view` intersection; a control-plane failure returned as an empty list; metric declare as `403`; the dashboard instance read from the request body; the dashboard-id pattern loosened; the `findActive` check skipped; the revision header optional; a shared-widget hide not translated to `404`; an invalid dashboard document as `422`; the layout body accepting extra keys; export ids not reach-filtered; the stream with no deadline of its own; a failed stream audited `completed`; `plan()` skipped; an organization cap mapped to `503`; the `ASYNC` skip removed (unit); `EXPORT_TOO_LARGE` and `STAT_REQUIRES_AGGREGATION` without titles; a bucket that never refills; one global bucket instead of per session; a stray key ignored; labels accepted. Arch: the gateway fixture excluded from the rule's scope (red); the fixture moved to `com.solace.sm.arch.archfixture` (red); a fifth caller allowed (2 red); a real caller dropped (2 red). IT: the MVC async timeout left unset (`ExportAsyncCompletionIT` red: the container's 30 s cuts the stream); the `ASYNC` skip removed (`ExportAsyncTimeoutIT` red: 2 decisions); the capabilities stating their own series cap (`MetricsEndpointsIT.capabilitiesAdvertiseTheLimitsTheDataPlaneEnforces` red, run with a non-default `series-cap`). **Not expressible, as the plan allowed:** "let the rate gate count on the async dispatch too": the gate is in the handler, which Spring does not re-run on a re-dispatch (the count stayed at one before the interceptor fix too), so no one-line mutation produces a second count.

*Where I read the rulings and the contract, for the reviewer.* (1) **An across-instances aggregate has no instance, but the contract requires `seriesRef.instanceId` to be a UUID** (P11-6.1 reading 13 handed it to this task): it is served as the nil UUID. A contract answer (nullable) is the honest fix and is not mine to make. (2) **`getCapabilities` has no spelling for "unknown":** before the data plane's first partition keep (`runwayEnd` null) it answers `state: degraded` and `runwayEnd` = today (UTC); with a budget set and no measurement yet (`disk` null) it answers `disk.state: unconfigured` ("not measuring", never `ok`). (3) The capabilities' limits are read from the data plane's own keys with the defaults restated in the gateway (the `DeclareOrchestrator.abandonAfter` precedent); an IT binds both to one context with non-default values and compares them. `raw.minBucketSeconds` is the collector's default scrape interval (a gateway property, 30), because the gateway cannot read declared intervals. (4) **`replicaComposition.inactive` is `0` and every series' `state` is `ok`:** an inactive replica is the health plane's fact and composing it is not in this task's test list; `aggregation.acrossReplicas` is validated and **not applied** (the data plane has no such switch). (5) **One entity type per dashboard** (`DefaultLayerInput` and a widget selector carry one): a package version that declares metrics on two components gets the first's. No package in the repository does. (6) The catalog is one entry per metric NAME from the first declaring instance; `durability` is `best-effort` for a `solace_event` source (SM-D27; no ruling states the mapping) and `restartDetection` is the value `RestartDetection` documents, `inferred` when instances disagree. (7) Dashboard `INVALID` is `400` (the contract lists no `422` on those routes; the exception's javadoc says `422`), a hide of a shared widget is `404`. (8) A refused export is not audited by `ExportAudit` (its javadoc: the refusal is the interceptor's `DENY` row, which a `422` or `429` does not write); a stream that ends after its first byte is audited `completed`, `failed` or `cut`. (9) An `ExportBusyException` for the organization or install cap is thrown by `stream()` on the streaming thread; it is a clean `429` only because nothing has been flushed yet (measured: no `Content-Disposition`, a problem body). A `reserve`-style port method would remove that fragility; flagged, not changed. (10) The route tests live in `com.solace.sm.gateway.http` (the plan names `.../metrics/`), because the fakes they reuse are package-private; production is in `http/metrics`. Three visibility edits in shared plan 010/P11-4.1 files so `http.metrics` reuses them (`MetricsDeclarationBuilder` and `componentsIn`, `InstanceController.packageVersionOnTheWire`: `public`), one four-line interceptor hunk, one `ProblemBody` hunk, one `ProductionAuthenticationIT` expected-set hunk, and one test-support hunk (`FakeAuthorizationService.reachOutcomeByPermission`, additive).

*Not done, or not measured.* The full reactor build and the full launcher `verify` (targeted runs only, per the execution model); the Postman lane; `exportRange` at scale (P11-8.1); a contract change for any of (1) and (2). One launcher fork was left hanging in a PgJDBC handshake after its last test had passed (an environment flake: the four ITs had all reported); I killed it and the reports are the record. **Defects in the base, not fixed:** none new beyond the readings above.

---

## MW8 — The storage gate on the EC2 host (M-Q8)

### Task P11-8.1 — One month of S-A, measured; six months extrapolated and labelled; run as an Operator

> **Owner rulings applied (`OR-92`).** **OQ-21 (2a):** the hourly tier is unpartitioned; put OQ-21 (2b) back to the owner only if the measured numbers show it needs detach-based retention.

**Wave:** MW8. **Depends on:** MW2–MW7 merged. **Parallel:** ⛔ alone.

**Files:** Create `docs/design/backend/evidence/011-storage-gate.md`. Create **two** benchmarks, each skipped unless
its system property is set (no pom change):
- `dp-core/src/test/.../metrics/MetricsStorageBenchmarkIT.java` (`@EnabledIfSystemProperty(named = "sm.bench.url",
  matches = ".+")`): the **DP-only** measurements. Load, bytes/row, WAL, vacuum, insert rate through `IngestService`,
  and rollup cost. It calls DP services and nothing else.
- `launcher/src/test/.../MetricsGateBenchmarkIT.java` (`@EnabledIfSystemProperty(named = "sm.bench.gate.url",
  matches = ".+")`): the **as-an-Operator** measurements, which need `sm_control`, a session, reach filtering and verb
  checks, and so cannot run in a dp-core test. It boots `SolutionManagerApplication` against the benchmark database
  (both schemas migrated), seeds an Operator binding the way the landed launcher ITs do (confirm the helper at Step
  0, for example `HierarchyEndpointsIT`'s seed), logs in over HTTP, and times `POST /query/range` and
  `GET /export/range` over real HTTP.

Modify `docs/requirements/REQ-007-six-month-metrics.md` (status line), `docs/requirements/traceability.md` (REQ-007
row), and `docs/design/DECISIONS.md` open item 5 (draft for the integrator).

- [x] **Step 0: Ask the owner** to confirm running PostgreSQL 14 on the EC2 host (M-Q8: *"a change on the owner's
  host — confirm at the gate wave"*). Without a yes, **stop**. Record the host's free disk before the run. **Stop
  whenever free disk falls below 3 GB.** Confirm the launcher IT's binding-seed helper.
- [x] **Step 0b: Measure one day before loading the month (`I2`).** M-Q8's *"≈11-12 GB"* is arithmetic at the
  120–140 B/row floor, which leaves out indexes and WAL (MF17). With the `(tenant_id, series_id, ts)` primary key, the
  month's 86.4 M rows plausibly need about 14–16 GB, against about **15 GB usable** (18 GB free − the 3 GB floor), so
  the month may not fit and the run would stop partway. Load **one day** of S-A (2.88 M rows) the same way Step 1
  will, and measure bytes/row **including** indexes (`pg_total_relation_size` of the day's partition ÷ rows) and the
  WAL written per row (`pg_current_wal_lsn()` before and after, `pg_wal_lsn_diff`). Project the month: 30 × the day's
  bytes + the month's WAL (until checkpointed) + the 3 GB floor. **If the projection does not fit** the measured free
  disk, **stop and take it to the owner with a scaled option** (for example, 15 days loaded and measured, the month
  extrapolated and **labelled** so), before loading anything more. This is M-Q8's own lesson: offer the simple scaled
  path first rather than hit the wall. Record the projection and the answer.
- [x] **Step 1 (DONE AT ONE WEEK, `OR-112` (3); the month EXTRAPOLATED): Load one month of S-A** (200 instances × 5 metrics × 30 s = 86.4 M raw rows, plus rollups) through
  Phase 0 and both migrations, with server-side `generate_series` in realistic series and time order
  (`MetricsStorageBenchmarkIT`). **Measure:** bytes/row (heap + indexes, from `pg_total_relation_size` per partition
  ÷ rows), WAL generated, and vacuum time. Fill MF17.
- [x] **Step 2: Insert rate** through the **real** `IngestService` batch path, run from the Mac over an SSH tunnel
  (labelled: it includes network latency), **and** server-side `INSERT … SELECT` (labelled: DB only).
- [x] **Step 2b (host CPU NOT MEASURED: the sampler returned no samples): Rollup cost.** Time `RollupService.rollUp` for the S-A tenant over the loaded month at the default
  5-minute cadence (P11-3.2), and once at a 10 s cadence for comparison. Record duration per run, rows read, and the
  host's CPU during it (`top`/`vmstat` over SSH). If one run at the default cadence takes longer than its interval,
  or holds the burstable CPU above its baseline, **record it as a finding** and put the cadence to the owner.
- [x] **Step 3 (the window held 7 days: the 30 d shapes read 7 days; TUNNEL-DOMINATED): Query timings, as an Operator principal** (M-Q16: REQ-007 must be met by a production role), run by
  `MetricsGateBenchmarkIT` over HTTP: p50/p95 for 24 h raw, 7 d raw, 30 d hourly and a 30-day forced raw over 1, 10
  and 200 instances.
- [x] **Step 4: Extrapolate six months** (the monthly partition × 6 for size; query times **labelled
  EXTRAPOLATED**), per M-Q8. Do **not** present the extrapolation as a measurement. **Also S-B and S-C, as sizing
  arithmetic only, labelled "arithmetic from the measured bytes/row, not exercised":** S-B (S-A at 15 s, ≈1.05 B raw
  rows in six months) and S-C (100 instances × 100 series × 15 s, ≈10.5 × 10⁹ rows) are 2× and ≈20× S-A's volume, far
  past a 20 GB host, so neither can be loaded here; their six-month size is the measured bytes/row × their row count.
  M-Q8 rules the gate as one month of S-A plus a scaled 5 s point, and names no S-B or S-C run.
- [x] **Step 5: The scaled 5 s point** (for the agent plan, M-Q8): one day at 5 s for 200 series. Measure bytes/row
  and insert rate; extrapolate, labelled scaled.
- [x] **Step 6a: Disk arithmetic for the 105 M-row stream, before loading anything.** Required = 105 M × the
  bytes/row measured in Step 1 (MF17, **including** indexes) + the WAL/row measured in Step 1 × 105 M + the 3 GB
  floor. Compare it with the free disk after the benchmark month is dropped. For scale, the discovery's floor of
  120–140 B/row **before** indexes and WAL already gives 12.6–14.7 GB, against 15 GB usable (18 GB free − 3 GB). It
  is therefore **expected not to fit**, but the measured number decides. Record the arithmetic.
  - **Fits:** run Step 6b on EC2.
  - **Does not fit:** run Step 6b **locally in Testcontainers** on the Mac (M-Q18 requires the 105 M-row stream, not
    EC2), with `MetricsGateBenchmarkIT` booting against the local container. The EC2 duration is then reported as
    **not measured**, never extrapolated from the local run.
  - **Does not fit locally either:** **stop** and put it to the owner. A scaled run does not meet M-Q18's *"105 M-row
    test"*, so export stays unshipped until the owner rules.
- [ ] **Step 6b (NOT DONE, see Results: no tooling, and the local disk is not safe): The 105 M-row export stream (MF5, the at-scale half; M-Q18).** **Before the run, project its
  duration** from P11-6.2's measured rows/s: 105 M rows in the ruled 30 minutes (M-Q22 (2), both the export
  `statement_timeout` and the MVC async timeout) needs at least ≈58 k rows/s end to end. Record the projection.
  - **Projected ≤ 30 min:** run with the default timeouts.
  - **Projected > 30 min:** run with **both** timeouts raised for this run (to the projection × 2), **labelled in the
    evidence as a benchmark-only override**, and record, **as a finding for the owner**, that a full S-M export
    cannot finish inside the ruled 30 minutes on this host (M-Q22 (2) against M-Q18). The finding goes to the owner;
    this plan does not change either ruled number.

  Load 105 M rows of **one** metric (S-M). Boot the launcher for this run with
  `sm.dp.metrics.export.row-limit=110000000`, **labelled in the evidence as a benchmark-only override**, and stream
  **all 105 M rows** through the real `GET /export/range`, as an Operator, into a counting sink. Record SM's heap high-water mark (sampled every 1 M rows through `MemoryMXBean`),
  the duration, the rows/s, and whether ingest continues meanwhile (a synthetic producer through `IngestService` in
  the same boot: its commit latency before, during and after). If the heap grows with the row count, **stop**: export
  does not ship, and M-Q18 (a) goes back to the owner.
- [ ] **Step 6c (NOT DONE, see Results): Re-check the defaults (M-Q22 (3)/(4)/(6)), with the default configuration, on the EC2 host**
  (M-Q22: *"Storage gate re-checks 3/4/6 on the EC2 host"*), **whichever host Step 6b ran on**. The projection needs
  only the series and segments, not the rows, so load S-M's series and segments (no raw rows) on EC2 if 6b ran
  locally. Record the host used. Boot with **no** override. `GET /export/range` over S-M fleet-wide returns `422 EXPORT_TOO_LARGE` `{projectedRows, limit}`
  **before any byte** (the projection needs only the series and segments, not the rows). A second concurrent export
  in the organization is busy. Query and export together never hold more than 4 permits.
- [ ] **Step 6d (NOT MET, row 60 stays wired, not served): Row 60 may now count as served.** Only if Step 6b recorded a flat heap for the full 105 M rows,
  mark MF5 ✅ with the numbers. P11-10.1 may then move row 60 to "served". Otherwise row 60 stays "wired, not
  served", and the Results say why.
- [x] **Step 7: Verdict.** REQ-007 is **satisfied** or **not satisfied** on this host, with the numbers. Open item 5
  is narrowed or closed as the numbers say. **Always put M-Q19 (c) (a separate database) to the owner after the
  gate**, as the ruling says (*"Revisit (c) separate DB after the storage gate"*, unconditionally), with the measured
  numbers and this plan's recommendation. Put OQ-21 (2b) (partition the hourly tier) back to the owner only if the
  hourly tier's measured size calls for detach-based retention.
- [ ] **Step 8 (NOT REPORTED by the integrator): Tear down** the benchmark database. Leave cache-solution untouched. Record the Docker state and free
  disk before and after.
- [x] **Step 9: Commit.** `docs: the monitoring storage gate on the reference host — one month measured, six months extrapolated (M-Q8, REQ-007)`

**Results (P11-8.1, the live run, 2026-10-06/07: the integrator on the dedicated SM test host, `OR-100`/`OR-110`; ONE WEEK of S-A, `OR-112` (3); evidence in [`011-storage-gate.md`](../../design/backend/evidence/011-storage-gate.md), recorded 2026-10-07 on `p/close`).** Every figure is in that file with its label; this paragraph is the reading. **What was run:** Step 0b at S-A's real settings (one day), then Steps 1, 2, 2b, 5, 6a and Step 3 at **7 days** of S-A (20.16 M rows), because the month did not fit (Step 0b projected 39.05 GB, WAL counted in full, against 18 GB free) and the owner ruled *"Do it for 1 week, we will extrapolate accordingly"*. **What the week shows (MEASURED):** 170.19 B/row for the day and **170.68 B/row** for the week with indexes (heap 81.32, index 89.34), WAL 244 and **247 B/row**, 23.7 k rows/s DB-only load (falling from 43.6 k to 15.7 k across the week), vacuum 24 s, the rollup at 3.1 to 4.0 s per run over 360 k raw rows (duty cycle 0.01 at 5 minutes, 0.31 at 10 s), the hourly tier 35.1 MB for the week, and the same 170.9 B/row at a 5 s cadence. **What is EXTRAPOLATED:** six months of raw at S-A **88.5 GB** (170.68 B x 518.4 M rows), 4.9 times the host's free disk; hourly about 0.9 GB; S-B 177 GB and S-C 1.77 TB as arithmetic only. **Step 3 (as an Operator over HTTP) ran with the application on the integrator's laptop and the database on the host through an SSH tunnel, over a 7-day window:** p50 6.2 to 6.7 s for one instance at the first three shapes (8.1 s for the 30 d forced raw one) and 6.3 to 8.8 s at ten, **9.2 s at 200 instances for 24 h raw, 18.2 s for 7 d raw (p95 37.9 s, one `503`), 6.8 s for 30 d hourly and 15.6 s for the 30 d forced raw shape (p95 28.2 s, two `503`)**. The "30 d" shapes read 7 days. **Findings against REQ-007, not a pass:** (1) the 6 s floor is not the database (one series over 24 h is 15.7 ms on the host) but the many SQL statements per HTTP query times the tunnel's round trip, so it belongs to the topology and to the statement count; (2) the 200-instance raw shapes are database-bound (all 1,000 series over 24 h is 5.47 s of database time) and reach the 30 s statement timeout as a `503` (an inference from the timings; the server log was not read); the hourly tier does not have the problem. The honest reading is to route raw-heavy wide queries to the hourly tier and to reduce statements per query, and neither is done here. **NOT MEASURED, stated:** the month; `IngestService` throughput beside the database (the only figure, 0.86 samples/s with batch p50 5.8 s, is TUNNEL-DOMINATED); the rollup's host CPU (the sampler returned no samples); Steps 6b, 6c and 6d (below); Step 8 (not reported to this lane). **Step 6b was not attempted:** no 105 M-row export-stream test exists (the tooling stops at Step 6a), and the plan's local fallback does not fit the Mac safely (the Docker VM reports 403 GB free, but it is a sparse image on a volume with 51 GiB free of 460 GiB, 88 % used, against a 47.1 GB requirement), so no disk was partially filled. MF5 stays half measured, **row 60 stays "wired, not served"**, and acceptance criterion 6 is not claimed. **Harness fixes made with the evidence:** `edd3463d` one master key per benchmark database (`GateMasterKey`, first derived from the database URL and the admin password, then, in the close's fix round, from the database's own identity (the cluster's `system_identifier` and its name) and the admin password, so a re-made SSH port-forward does not change it; the second boot of the live run had died on the first boot's canary; `GateMasterKeyTest` 6, `MetricsGateBenchmarkLocalIT` now boots twice, red first with the integrator's own error, six mutations fired) and `ebee04a8` the six-month evidence line's label (86.4 M x 6 = 518.4 M rows, not "432 M"). **Flagged, not fixed:** the harness does not compare the benchmark database's migration level with the build (the live run's two `V003` failures). **Records updated:** `REQ-007`'s status line, its row in `traceability.md`, MF5 and MF17 in this plan, and a dated note under `DECISIONS.md` open item 5 (a draft for the owner, not a ruling). `M-Q19` (c), a separate database, is put to the owner with these numbers (evidence file, Step 7); `OQ-21` (2b) is not, because the hourly tier's size does not call for it.

**Results (P11-8.1 tooling (the pre-run record; superseded by the live run above), 2026-10-06; lane `p011/p81`, base `1262b03b`). The gate is PREPARED, NOT RUN: nothing was measured on the host, and `docs/design/backend/evidence/011-storage-gate.md` is a template whose every value is `pending`.** **The live run on the reference host is pending; no figure in this repo is a measurement of S-A.** (Wave 4 note: P11-7.2's query route is now on this branch, so the Step 3 limitation in flag (1) below no longer holds on the integration base; the route has still not been answered by the gate runner.) Written: `MetricsStorageBenchmarkIT` (dp-core, gated on `sm.bench.url`; Steps `prepare`, 0b, 1, 2, 2b, 5, 6a, one `@Test` per step, selectable by `sm.bench.steps` or a method filter), `MetricsGateBenchmarkIT` (launcher, gated on `sm.bench.gate.url`; `prepare` and Step 3), `backend/launcher/scripts/run-storage-gate.sh`, and the evidence template. **Not covered, on purpose:** Steps 4 (arithmetic; Step 1 prints the labelled `EXTRAPOLATED` raw-tier six-month figure only), 6b, 6c and 6d (they need P11-7.2's export route and a 105 M-row load), 7, 8. **Proven locally only:** a throwaway `postgres:14` at a tiny scale (`MetricsStorageBenchmarkLocalIT`, 14 tests; `MetricsGateBenchmarkLocalIT`, 1), and the integrator's own sequence through the script against a `docker run` container with the memory limit below (tiny scale, 127.0.0.1 bind, named volume, since removed). Every tiny-scale line says `SCALED-DOWN, NOT S-A`; **the figures it printed (about 168 B/row, 238 B/row of WAL) are of a 4 to 6 instance database and say nothing about S-A.**

**Tests (all green, final targeted run):** dp-core unit 27 (`BenchSettingsTest` 9, `GateMathTest` 8, `VmstatTest` 4, `BenchResultsTest` 4, `CpuSamplerTest` 2); dp-core IT `MetricsStorageBenchmarkLocalIT` 14 and `MetricsStorageBenchmarkIT` 7 skipped (no `sm.bench.url`: the gate); launcher unit `GateRunnerTest` 9 (a JDK stub of the query route), `RunStorageGateScriptTest` 10; launcher IT `MetricsGateBenchmarkLocalIT` 1 and `MetricsGateBenchmarkIT` 2 skipped; `arch-tests` 73, 0 failures. **Order was not strictly red-first for the two Local ITs** (they were written with the steps they exercise); the pure units were red first (compile failure, 104 error lines), and the ITs' assertions are proven by the 26 mutations below. **26 mutations, each red then restored from the staged tree** (counts are failures or errors of the targeted run): projection without WAL (1), `fits` strict (1), percentile rank rounded down (1, found only after adding a seven-value test: 20 values hid it), `toString` prints a password (1), password read from a property (1), vmstat since-boot line kept (3), evidence not masked (1), `prepare` in the default steps (1), a measurement not stored (1 error), the CPU command's first-line wait removed (1), the hour-divides-interval check removed (1), series-major load order (1), no resume (5 errors), no refusal after 0b says no fit (1), no disk ceiling (1), 6a verdict inverted (2), `declare` skipped before the ingest path (1), absolute instead of delta bytes in 0b (1), rollup window moved off the data (1), loopback check removed (1), script prints a password (3), `all` loses Step 0b (1), spacing inside the timing (1), a `429` counted as a result (1), one instance aggregated (1), the Operator left unbound (1).

**Deviations and flags for the integrator.** (1) ⚠️ **Step 3 cannot run on this base:** `POST /api/console/v1/query/range` is P11-7.2's and is not in `1262b03b`. Against the real boot the gate seeds the Operator, logs in over HTTP, proves the Operator sees the loaded environment through an existing hierarchy read, then stops with *"answered 404: this build does not serve the monitoring query routes"* (seen through the script). The request shape follows `contracts/openapi/console.yaml` `MetricQueryRequest`; **it has never been answered by a real route**, so the first run after P11-7.2 may find a mismatch (the entity type defaults to `cache-engine`, the metric to `bench.gauge_1`, both overridable). The runner's timing, spacing, `429` and refusal rules are proven against a stub only. (2) Placement: the benchmark classes are in `com.solace.sm.dp.metrics.bench` (public, test sources) so the launcher IT reuses them through the dp-core test-jar, not under `.../metrics/` beside the plan's file name; no pom changed, no new dependency. (3) **Step 1 resumes by the hour** (a statement per hour, time-major, already-present hours skipped) and Step 0b loads day 0 of the month rather than a throwaway day, so Step 1 reuses it. (4) **The rollup backfill is chunked** (24 h per statement, through `RollupRows`, not `RollupService.rollUp`, whose window is the late window only); the steady-state runs at 5 minutes and 10 s ARE `RollupService.rollUp`, at simulated instants just past the data. (5) The gate IT seeds `sm_control.instance` from `sm_data.instance_state` as the superuser (a benchmark seed, never application code), because reach filtering reads the control plane's instance rows. (6) Step 0b's WAL figure is `pg_current_wal_lsn()` before and after on a database nothing else writes to; WAL is counted in full in the projection, as the plan says, though a checkpoint recycles it. (7) `pg_database_size` excludes `pg_wal`, so `SM_BENCH_MAX_DB_BYTES` (default free disk minus the floor) leaves WAL headroom unaccounted: watch `df` during Step 1. (8) The container line's memory figures (1536 MB limit, `shared_buffers=256MB`) are my sizing for a 3.8 GB host with a Cache running; **the Cache's real memory was not measured** (I2: check `free -m` and `docker stats` first). (9) Base `1262b03b` is wave 3, not yet reviewed; no wave 3 file was edited, and the only shared-file hunk is this paragraph.

**The exact sequence for the integrator** (details and every variable are in the script header). ON THE HOST: confirm the owner's yes (Step 0), record `df` and `docker ps`, then `docker run -d --name sm-bench-pg --restart no --memory 1536m --memory-swap 1536m --shm-size 256m -p 127.0.0.1:55432:5432 -v sm_bench_pgdata:/var/lib/postgresql/data -e POSTGRES_PASSWORD="$SM_BENCH_ADMIN_PASSWORD" -e POSTGRES_DB=sm postgres:14 -c shared_buffers=256MB -c work_mem=8MB -c maintenance_work_mem=96MB -c max_connections=30 -c listen_addresses='*'`. ON THE MAC: `ssh -N -L 127.0.0.1:55432:127.0.0.1:55432 <user>@<host>` in its own terminal; export `SM_BENCH_URL=jdbc:postgresql://127.0.0.1:55432/sm` and the passwords (`SM_BENCH_ADMIN_PASSWORD`, `SM_BENCH_RUNTIME_PASSWORD`, `SM_BENCH_MIGRATOR_PASSWORD`, then `SM_BENCH_CP_RUNTIME_PASSWORD`, `SM_BENCH_CP_MIGRATOR_PASSWORD`) without echoing them; `export SM_BENCH_FREE_DISK_BYTES=<the number `run-storage-gate.sh free-disk` prints, with `SM_BENCH_SSH_TARGET` set, or `df -B1` on the host>`. Then: `run-storage-gate.sh prepare` (Phase 0 and the migrations) · `run-storage-gate.sh step 0b` · **STOP and read the VERDICT and `monthProjectionBytes`; if it does not fit, take the scaled option to the owner** (`SM_BENCH_DAYS=<fitting days>`, labelled) · `step 1`, `step 2`, `step 2b` (with `SM_BENCH_SSH_TARGET` for the host's vmstat), `step 5`, `step 6a` · `gate-prepare` · `gate` (after P11-7.2) · Step 8 by hand: `docker rm -f sm-bench-pg && docker volume rm sm_bench_pgdata`, then `df` and `docker ps`. Copy the evidence log's lines into the template; fill the REQ-007, traceability and `DECISIONS.md` hunks yourself with the measured numbers.

---

## MW9 — Retire the demo's monitoring

### Task P11-9.1 — Parity, then delete `demo-launcher` and `demo_control` in one cleanup

**Wave:** MW9. **Depends on:** MW7, MW8, plan 010 T12.1 (its parity checklist green for everything but monitoring).

**Files:** Delete `backend/demo-launcher/**` (including `db/migration/demo_control/V001…V004`); Modify `backend/pom.xml`
(`:85` module line), `arch-tests/pom.xml` (measured 2026-10-05: it has **no** `demo-launcher` edge, so no edit), `docs/runbooks/…` (the `demo_control` drop
step and its rollback: *"once `demo_control` is gone, reverting to the demo is a reinstall, not a revert"*, plan 009
Task 5 Step 2's wording); Modify plan 007 Task 13a's checklist test (plan 010 T12.1 Step 2) to **include** metrics,
dashboards and Explore. Create `backend/arch-tests/src/test/java/com/solace/sm/arch/FixedMiPortSourceScanTest.java` and
`backend/arch-tests/src/test/resources/archfixture/monitoring/FixedPort.java.txt` (below; MF22).

**Also delete the demo's Cache probes on the dp-core production path** (the Global Constraint "no `8081` literal on
a production path"; measured, they survive a `demo-launcher` deletion because they live in dp-core):
`dp-core/.../apply/runtime/ssh/SshDockerMetrics.java` (the whole class), the four Cache methods and
`CacheMetricResponses` in `SshDockerTransport.java` (`:53-75`), their overrides in `SshjDockerTransport.java`
(`:225-261`, including the hard-coded `http://127.0.0.1:8081/...` URLs), `MAX_METRIC_BYTES` if nothing else uses it,
and `dp-core/src/test/.../ssh/SshDockerMetricsTest.java`. **In the same commit**, retire plan 010 T6.7's two facade
rules in `arch-tests/.../ApplyPathBoundaryTest.java`, `onlyTheMetricsFacadeCallsTheDemoCacheProbes` and
`nothingProductionUsesTheMetricsFacade` (`010-…md:3345-3348`), and their planted fixtures: the classes they guard no
longer exist. Replace them with one guard, `noProductionSourceNamesAFixedMiPort`, which fails on `:8081` anywhere in a
production source file. **Its mechanism is measured first (MF22):** ArchUnit's rules are about dependencies and
accesses, and a string constant is expected not to be visible to them. Unless MF22 shows ArchUnit 1.5.0 can read
constants, the guard is a plain JUnit test in `arch-tests`,
`backend/arch-tests/src/test/java/com/solace/sm/arch/FixedMiPortSourceScanTest.java`, that walks
`backend/*/src/main/java/**/*.java` from the repo root (found the way `DesignSketch.repoRoot()` finds it,
`DesignSketch.java:34-52`) and fails on any match, naming the file and line. Its planted-violation proof points the
**same** scanner at `backend/arch-tests/src/test/resources/archfixture/monitoring/FixedPort.java.txt` (a `.txt`, so it
is never compiled) and asserts the scanner reports it.

- [x] **Step 1: Parity.** Every demo monitoring capability (`DemoMonitoringController.java:49-138`'s 12 operations,
  plus the two it never served) has an authorized production route with a launcher IT. Explore is the ad-hoc query
  (`query/range`); its drafts stay in `sessionStorage` (M-Q23).
- [x] **Step 2: Delete** the module and its reactor entry, then the dp-core demo probes and the two facade rules.
  Run T6.7's mutating-`RuntimeAdapter` rule **unscoped**. Fill MF22 first. Prove `noProductionSourceNamesAFixedMiPort`
  fires on its planted fixture (and, as a second proof, on a temporary `:8081` added to one production file, then
  removed), and that `grep -rn ':8081' backend/*/src/main` finds nothing.
- [ ] **Step 3: Full gate.** `mvn -o -f backend/pom.xml clean verify` · `./contracts/run-checks.sh --strict` · `git
  diff --check`.
- [x] **Step 4: Record** that the branch is **not deployable with the current frontend** until the consumer cutover
  (plan 007's release gate).
- [ ] **Step 5: Commit.** `chore: retire demo-launcher and demo_control after full backend parity`

**Results (P11-9.1, executed on branch `p011/p91`, base `849e3e12`; ⛔ THE MERGE IS HELD FOR THE OWNER).** Deleting the module makes
the branch **not deployable with the current frontend** until its consumer cutover (plan 007's release gate), so the integrator
holds the merge until the owner confirms.

- **Parity (Step 1): no gap.** `DemoMonitoringController`'s 12 operations each map to a production route in `console-gateway`
  with a launcher IT, plus the two the demo never served: `getCapabilities` (authentication only), `listEntityTypes`,
  `listMetricInstances`, `getMetricsCatalog`, `getLabelValues`, `queryInstant` (reach, `metrics.query`), `queryRange` (Explore; reach,
  `metrics.query`), `getDashboard`, `saveWidget`, `setDashboardLayout`, `setWidgetHidden` (`dashboard.view`/`dashboard.edit`),
  `resetDashboard` (`dashboard.reset`), and the never-served `exportRange` (reach, `metrics.export`) and `declareMetricType`
  (`metric.declare`, still 501 for every holder). Same paths and verbs as the demo (`/api/console/v1/...`). ITs:
  `MetricsEndpointsIT` (14), `DashboardEndpointsIT` (8), `ExportEndpointIT` (4), `ProductionAuthenticationIT`. `DemoParityChecklistIT`
  now holds 13 capabilities (metrics, dashboards, explore added; 14 `/api/console/v1` rows, each asserted registered with its
  method, path, exact classification and, for catalog verbs, a resource resolver). Measured red: a re-pathed `resetDashboard`
  row fails naming the row; restored, green. `exportRange` is a served route but its 105 M-row measurement is P11-8.1's (P11-10.1
  decides the inventory row).
- **MF22 (measured):** ArchUnit 1.5.0 cannot see a string constant. Its jar has no domain type for a literal or constant-pool
  entry (only `JavaEnumConstant`, an annotation value), and the user guide (WebFetch, 2026-10-07, version 1.5.1) lists field,
  method and constructor accesses only. The guard is therefore the source scan `FixedMiPortSourceScanTest`
  (`noProductionSourceNamesAFixedMiPort`), which walks `backend/*/src/main/java/**/*.java`.
- **Deleted:** `backend/demo-launcher/**` (including `demo_control` `V001`-`V004`, its README and compose file), its reactor
  module and dependency-management entries, `SshDockerMetrics` and its test, the four Cache probes and `CacheMetricResponses` in
  `SshDockerTransport`/`SshjDockerTransport`, `MAX_METRIC_BYTES`, T6.7's two facade rules and their two planted fixtures.
  `grep -rn ':8081' backend/*/src/main` finds nothing. T6.7's mutating-`RuntimeAdapter` rule now runs unscoped (the
  `com.solace.sm.demo..` exemption is removed) and is green.
- **Proofs:** `FixedMiPortSourceScanTest` is green on the real tree, reports the planted `FixedPort.java.txt` by file and line, and
  was red with a temporary `:8081` comment in `QueryController.java` (naming file and line), then removed. `mvn -o -f
  backend/pom.xml -DskipITs verify -q` is green without the module; the targeted arch-tests and launcher ITs are green.
  Step 3's full reactor build is the integrator's.
- **Operator step:** `docs/runbooks/operations.md` gains "Drop the retired demo's `demo_control` schema", with its rollback: once
  `demo_control` is gone, reverting to the demo is a reinstall, not a revert.
- **What the frontend loses when this merges:** the demo's `/api/console/v1` monitoring routes are no longer served by the demo
  (the production gateway serves the same paths, but behind the production session and CSRF, not the demo's), and every other
  demo route (`/setup`, `/ssh-keys`, `/ssh-host-key`, `/packages`, `/solutions/load/confirm`, `/solutions/load/discard`,
  `/instance*`, `DELETE /instances/{id}`) and the demo's `SM_DEMO_SESSION` and `SM_DEMO_CSRF` cookies and its own account/login are gone; none has a production
  equivalent by recorded decision (`DEMO_CAPABILITIES_WITHOUT_A_PRODUCTION_ENDPOINT`). Until the frontend's cutover it cannot log in.

---

## MW10 — Close

### Task P11-10.1 — Inventory, Results, the adversarial review

> **Owner rulings applied (`OR-92`, OQ-25 (b)).** Step 4 below is **replaced by one combined Fable 5.1 review** after the last wave (`OR-70`'s cadence); the Opus review is per wave, not per task. The checklist of what to look for stays. OQ-20 is checked here only if it was ruled.

- [x] **Step 1: `ENDPOINT-INVENTORY.md`:** rows 53–59 and 61–66 move to "Served now", each citing its launcher IT.
  **Row 60 (`GET /export/range`) moves only if P11-8.1 Step 6d marked MF5 ✅** with the 105 M-row measurement
  (M-Q18). Otherwise it stays "wired, not served", with the reason.
- [x] **Step 2: Full gate** (as P11-9.1 Step 3). **Done as a TARGETED gate, not a full reactor build** (the close lane may not run one; the figures and what was not re-run are in Results, `P11-10.1`).
- [x] **Step 3: Results** below (`P11-10.1`): exact counts per module, every skip with its reason, every mutation and whether it
  fired, every measured fact with its evidence, every OQ with its answer and registered id, every ruling with its
  allocated OR id, the I1 table from P11-3.1, **whether the `broker.host`-gated tests (P11-5.1) ran** (MW5 is not claimed if they did not),
  and the flags still open (below).
- [x] **Step 4: Independent adversarial review** (Opus 5.5 high; **replaced by the combined Fable 5.1 review, `OR-92`/`OR-70`: done 2026-10-07, dispositions in Results**), specifically looking for:
  - a route counted served without a production-boot IT;
  - a third cross-tenant read, a fifth caller of OR-49's list, or its SQL re-spelled;
  - a branch on a solution name;
  - an identity part not SM-held (`I1`);
  - a payload value persisted or logged on the push path (`I6`);
  - runtime DDL outside `ensure_metric_partitions()`, or an operator-command identifier not resolved from the catalog (`I5`);
  - an owner or migrator credential in the running application;
  - a CP class touching `sm_metrics` (`I7`);
  - a gauge averaged across placements;
  - a delta across a gap;
  - an extrapolation presented as a measurement;
  - a flagged ruling described as ratified, or a discovery default described as a ruling;
  - a restart decided from whether a token arrived rather than from the source's declared detection (M-Q9);
  - a push drop charged to a counter row the ruling (OQ-20) does not name;
  - an install-wide byte figure on an authentication-only route (OQ-16), or a credential echoed by an operator command;
  - a bean with no named registering configuration, or two parallel tasks sharing one;
  - the demo reachable from production.
- [x] **Step 5: Commit.**

---

## Rollback and forward-only changes

- **`sm_metrics` V001+** are forward-only (no down-migrations in this repo). Before production, a dev database may be
  recreated, and each time it is, the commit says so. Because `sm_metrics` has its own history, recreating it never
  touches `sm_data`'s history.
- **`sm_data` V015 (and V016)** and **`sm_control` V014** are forward-only; `sm_control` is frozen
  once published (OR-22). Reverting M-Q16's two verbs, if ever needed, is a **new** decision and a new migration
  (`DELETE FROM role_permission …`, audited).
- **Partitions:** a detached partition is never dropped without a verified archive manifest (`MetricsPartitionMain`).
  A failed archive loses nothing.
- **Hosts:** collection is read-only on every host (fixed commands, no mutation). There is nothing to roll back
  there.
- **Demo deletion** is the one irreversible step, stated in P11-9.1.

## Acceptance

| # | Criterion | Evidence |
|---|---|---|
| 1 | The 14 `/api/console/v1` operations are served by the production launcher, each authorized by its mapped verb; export and forced raw are available to an Operator. Row 60 counts only after the 105 M-row measurement (criterion 6) | `MetricsEndpointsIT`, `DashboardEndpointsIT`, `ExportEndpointIT` |
| 2 | Every active instance's declared metrics are collected in the background with no session, in both acquisition modes; S-G is attributed correctly | `MetricsCollectorIT`, P11-4.4 evidence |
| 3 | S-D gives exactly the ruled numbers; no delta across a gap; gaps never zero-filled | `IngestServiceIT` |
| 4 | Broker-pushed metrics are ingested with no payload value persisted or logged, **and at least one real, declared `solace_event` metric in a reviewed fixture (OQ-29) is ingested end to end**; a synthetic-only package does not satisfy this. The broker-gated tests (`broker.host`) ran, or MW5 is not claimed | `PushIngestIT`, `aRealDeclaredSolaceEventMetricIsIngestedEndToEnd`, P11-10.1 Results |
| 5 | Runway exhaustion and a DB outage degrade metrics ingest only | `IngestServiceIT`, `SmMetricsSchemaIT` |
| 6 | Export streams with a measured, bounded heap at 5 M and 20 M rows locally, **and for all 105 M rows** through the real route (M-Q18), on EC2 if the disk arithmetic allows it, otherwise locally, labelled either way. The default 20 M limit refuses S-M with `422` before any byte | `ExportStreamingMeasurementIT`, `MetricsGateBenchmarkIT`, P11-8.1 Steps 6a–6d |
| 7 | REQ-007 is marked satisfied or not on the reference host, with measured one-month and labelled six-month figures | P11-8.1 evidence |
| 8 | `arch-tests` green, including the gateway-planted JDBC fixture and the OR-49 caller rule | `VERIFY arch-tests` |
| 9 | The demo's monitoring is deleted after parity | P11-9.1 |
| 10 | Every OQ is answered and registered; every flag is listed as open unless ratified | Results |

## Non-goals — stated exactly

| Not here | Why / where |
|---|---|
| Live chart tails for **polled** metrics (REQ-012 phase 2) | Gated by open items 32/33/35 (plan 010 Global Constraints) |
| Browser delivery of **pushed** metrics (M-Q12's live leg) | **Not settled as a non-goal.** M-Q12 rules it *"in plan 011"*; which plan builds its backend half, and whether it waits for the §11.3 spike, is **OQ-14**. ⚠️ OQ-14 (b) would move it to another plan, which **departs from M-Q12's text**; only the owner can do that. It is listed here only until OQ-14 is ruled |
| The agent (path 3) producer, the agent `gapMarkers[]`, 5 s agent collection | The agent-gateway plan; the port and its contract kit are built here |
| Quarantine, `metric.declare`, `TYPE_SUSPECT` | M-Q6 (deferred; the `pending` outcome is reserved) |
| Histograms | M-Q6: skipped, `unsupported` |
| Package upgrade of an existing instance (a new version's rows + a pointer flip), changing acquisition mode after declare | Future operations (M-Q5, M-Q2); routed |
| Per-instance overrides (e.g. a per-instance interval) | M-Q5: out of v1 |
| Cross-placement gauge aggregation, `replicaAggregation` | M-Q20: a later schema extension |
| Label mapping, allowlists, `labels/{k}/values` beyond an empty list | Discovery default D-10 (OQ-22): empty in v1 |
| `DM-D5` scopes, saved comparison widgets, templating, server-side Explore drafts | M-Q10, M-Q23 |
| Archive encryption and destinations | OQ-11; routed |
| Tenant erasure on demand | A new open item (P11-0.1) |
| SM's own Micrometer export | M-Q21 (a): the counter table instead |
| Metrics notify events | Discovery default D-11 (OQ-22) |
| Frontend changes | The owner's standing rule; plan 007's release gate |

---

## Results

*Empty for the code waves until execution. Fill per task: what was built, exact test counts per module, every skip and its reason,
every mutation run and whether it fired, every measured fact with its evidence, every deviation from this plan and
why, every flag still open, and the allocated id for every `«next free …»` placeholder this plan still carries.*

### P11-10.1 — the close (2026-10-07, `p/close` off `7839ac8f`)

**Plan 011 is CLOSED AS BUILT AND REVIEWED, NOT AS FULLY PROVEN.** Every task below that was written is built, was reviewed (wave reviews, the combined Fable 5.1 review of `OR-70`), and has its own Results above. **What is not claimed, in one place:** the storage gate ran at **one week** of S-A (`OR-112` (3)), the month and six months are **extrapolated**, and **REQ-007 is marked NOT MET at the measured gate topology**; the **105 M-row export was not measured** (Step 6b), so **`exportRange` stays "wired, not counted served"** and acceptance criterion 6 is **not claimed**; **P11-5.2 (browser delivery of pushed metrics) is not built** (conditional, `OR-95`); the `direct-http` collection mode was never run live; the full reactor build and the frontend's tests were **not re-run** by this lane. Read the acceptance table below before reading anything as "done".

**Step 4 (the independent adversarial review) is the combined Fable 5.1 review of `OR-70`/`OR-92`, run 2026-10-07 and recorded above** ("Combined Fable 5.1 review (OR-70), fix round"): **12 findings, 0 BLOCKER, 3 MAJOR, 9 MINOR, all 12 fixed, none disputed or skipped**, each with a test red before its fix. It was **not re-run** here. This lane attests nothing from its checklist (a route counted served without a production-boot IT, a third cross-tenant read, an extrapolation presented as a measurement, and the rest) beyond what the tests and records cited below show.

**Step 1: the inventory.** `contracts/openapi/ENDPOINT-INVENTORY.md` already had rows **53-59 and 61-66 in "Served now"**, each citing its launcher IT (`MetricsEndpointsIT` 14, `DashboardEndpointsIT` 8), and **row 60 (`GET /export/range`) in "Wired, not counted served"** (`ExportEndpointIT`, `ExportAsyncTimeoutIT`, `ExportAsyncCompletionIT`). **Row 60 does NOT move**: Step 6d is not met (below). The inventory's paragraph now says P11-10.1 looked and found no 105 M-row measurement; Postman regenerated (no diff), `check-postman.py` OK (46 operations: 45 served, 1 wired-not-counted-served), and `./contracts/run-checks.sh --strict` **ALL CHECKS PASSED** (step 14 against the sibling worktree's Ajv 8.20.0, read-only).

**Step 2: the gate, TARGETED.** *Re-run at this head by this lane (`p/close`, `mvn -o`, Testcontainers on Docker Desktop, no failures):* **dp-core failsafe 141 run, 0 failed, 0 skipped** (`PushIngestIT` 10, `ExportStreamingMeasurementIT` 1, `MetricsQueryServiceIT` 40, `MetricsCollectorIT` 14, `SmMetricsSchemaIT` 28, `IngestServiceIT` 35, `PushBrokerIT` 5, `JcsmpSampleSubscriberIT` 8); **launcher** `GateMasterKeyTest` 6 and failsafe **41 run, 0 failed, 7 skipped** (`MetricsEndpointsIT` 14, `DashboardEndpointsIT` 8, `ExportEndpointIT` 4, `PushedMetricsQueryableOverRestIT` 1, `MetricsCollectionRehearsalIT` 7, `MetricsCollectionLiveIT` 7 skipped); **`arch-tests` 78 run, 0 failed** (all nine classes); the benchmark tooling's own tests (`MetricsStorageBenchmarkLocalIT` 14, `MetricsGateBenchmarkLocalIT` 1, `GateRunnerTest` 10, `RunStorageGateScriptTest` 10). *Last recorded FULL green build* (`mvn -o -f backend/pom.xml clean verify`, the Fable fix round, above): cp-api 48; mesh-events 160; cp-core 466 / 287; dp-core 1086 (1 skipped) / 1140 (12 skipped); console-gateway 601; launcher 77 / 289 (27 skipped); demo-launcher 6; arch-tests 76. **Since that build the tree changed** (`OR-112` items (1), (2), (4), (6); the demo module's deletion, which removes its 6 tests; later per-lane targeted counts are in the `or112a`/`or112b` entries above, for example console-gateway 618, cp-api 48), **and nobody has recorded a full reactor build at this head.** **FLAG: the integrator runs the full gate at the final commit** (`mvn -o -f backend/pom.xml clean verify`), then fills the per-module totals; this lane did not (a full reactor build is not permitted here) and invents none.

**The `broker.host`-gated and local-broker tests (acceptance 4).** Plan 011's own broker ITs (`PushBrokerIT`, `JcsmpSampleSubscriberIT`, `@Tag("broker")`) start a **local** `solace-pubsub-enterprise:10.25.0.177` from the image already in the Docker daemon (never pulled, never a remote broker) and **RAN at this head: 5 of 5 and 8 of 8**, including `aRealDeclaredSolaceEventMetricIsIngestedEndToEnd` (a reviewed fixture's `filemesh.heartbeats`, a PERSISTENT publisher, the real session manager, the real secret store, the real ingest service). The `-Dbroker.host`-gated ITs (plan 010's mesh publishers, for example `ApplyOutcomePublishIT`) are plan 010's and were **not run here**. **MW5 is claimed on P11-5.1's own local-broker ITs (`PushBrokerIT` 5, `JcsmpSampleSubscriberIT` 8, run at this head), not on plan 010's `-Dbroker.host` ITs, with the F1 flag below** (one reviewed metric, on the file-events source).

#### The tasks, what each is, and where its evidence is

| Task | Status at close | Evidence (each task's own Results, above, has the counts, mutations and flags) |
|---|---|---|
| P11-0.1, P11-0.2 (MW0, records; the DM design review, M-Q1) | built, merged | `94d66f3d`, `15ecd49e`; `docs/tasks/dm-design-review-2026-10.md` (34 findings, each with its disposition) |
| P11-5.0 (File Mesh publisher facts, MF13) | built, merged | `docs/design/backend/evidence/011-filemesh-publisher-facts.md` |
| P11-1.1 (package schema: per-kind `source`, restart signal, `defaultChart`) | built, reviewed | `277dc5a9`; gates 15 and 16; `NegativeFixtureParityTest` |
| P11-1.2 (`console.yaml`: the 14 operations, `metricsPorts`) | built, reviewed; amended by `OR-112` (6) | `ab628e02`; contract checks 1-17 |
| P11-2.1, 2.2, 2.3 (`sm_metrics`; `sm_data` V016; `sm_control` V014) | built, reviewed, **frozen** (`OR-22`) | `c3521fa8`, `2b61ae57`/`695e9a78`, `b3f9d2b5`; `frozen-migration-anchors.txt` |
| P11-3.1, 3.2 (ingest port; rollups, runway keeper, disk alarm) | built, reviewed; MON-01 amended (`V003`, anchored `e1b424cf`) | `074eef43`, `cbb92ca3`, `c1556f04`; `IngestServiceIT` 35, `IngestRunwayIT` |
| P11-4.1, 4.2a/b, 4.3 (declare and stage; the two fetchers; the collector) | built, reviewed | `691272b5`, `a4db5c83`, `471c54eb`, `d6f74740` |
| P11-4.4 (the live collection gate) | built; **live-run by the integrator on the dedicated test host**, SSH mode | `docs/design/backend/evidence/011-collection-live.md`; Results of P11-4.4 (four runs; runs 2-4 passed; **not proven live: `direct-http`, MF6, more than two instances, a window over ten minutes**) |
| P11-5.1 (broker push) | built, reviewed; ITs ran against a local broker (and again here) | `b378bee3`; `PushBrokerIT`, `PushIngestIT`; `OR-106`, `OR-107` |
| **P11-5.2 (browser delivery of pushed metrics)** | **NOT BUILT, by ruling** (conditional; opens only when open items 32/33/35 close, `OR-95`). Pushed metrics are REST-only | the task's own "CONDITIONAL, not written in full" status |
| P11-6.1, 6.2 (query service; streaming export) | built, reviewed | `f1a6a417`, `4500d3c5`; `MetricsQueryServiceIT` 40, `ExportStreamingMeasurementIT` (26 MB at 5 M and at 20 M rows, re-run here) |
| P11-7.1, 7.2 (CP dashboards; the 14 `/api/console/v1` routes) | built, reviewed; 13 routes served, 1 wired | `709bf525`, `875da234`; `MetricsEndpointsIT`, `DashboardEndpointsIT`, `ExportEndpointIT` |
| P11-8.1 (the storage gate) | tooling built (`192b4334`); **live run at ONE WEEK, extrapolated** (`OR-112` (3)); **6b, 6c, 6d NOT MEASURED** | `docs/design/backend/evidence/011-storage-gate.md`; the P11-8.1 Results above; this lane's `edd3463d` and `ebee04a8` (the SHAs on this branch; on `p/close` they were `8bd0d454` and `098cc312`) |
| P11-9.1 (retire `demo-launcher` and `demo_control`) | **DONE, merged** (`OR-112` (4)) | `a5167c07`; `DemoParityChecklistIT` (13 capabilities); `FixedMiPortSourceScanTest` |
| P11-10.1 (this close) | done as recorded here | this section |

#### Acceptance, each item with its evidence or "not claimed"

| # | Criterion | At close | Evidence |
|---|---|---|---|
| 1 | The 14 operations served by the production launcher, each by its mapped verb | **13 of 14 claimed.** `exportRange` is wired and tested, **NOT counted served** | `MetricsEndpointsIT` 14, `DashboardEndpointsIT` 8, `ExportEndpointIT` 4 (all run here) |
| 2 | Declared metrics collected in the background with no session, in both acquisition modes; S-G attributed correctly | **SSH mode: claimed, proven live** (19 to 30 samples per Cache in ten minutes; S-G by value after read-only traffic to one Cache; one `series_reset` with signal `docker` after a real restart; S-F a real `docker stop` stores no sample). **`direct-http`: NOT claimed live**; proven only against two local stub Caches (`MetricsCollectionRehearsalIT` 7, run here) | `011-collection-live.md`; `MetricsCollectorIT` 14 |
| 3 | S-D gives exactly the ruled numbers; no delta across a gap; no gap zero-filled | claimed | `IngestServiceIT` 35 (run here) |
| 4 | Broker-pushed metrics ingested with no payload value persisted or logged, **and one real declared `solace_event` metric in a reviewed fixture ingested end to end**; the broker-gated tests ran | claimed, with flag F1 (the reviewed metric is one count of file-events heartbeats; the measured file-to-file metrics are not declared) | `PushIngestIT` 10, `PushBrokerIT` 5, `JcsmpSampleSubscriberIT` 8 (all run here, against a local broker) |
| 5 | Runway exhaustion and a DB outage degrade metrics ingest only | claimed | `IngestServiceIT`, `IngestRunwayIT`, `SmMetricsSchemaIT` 28 |
| 6 | Export streams with a bounded heap at 5 M and 20 M rows locally **and for all 105 M rows** through the real route; the default 20 M limit refuses S-M with `422` before any byte | **NOT claimed.** The 5 M and 20 M local half is measured (26 MB live heap at both, re-run here). **The 105 M-row half was NOT MEASURED** (no tooling; it does not fit the host, nor safely the local machine). The 422-before-any-byte behaviour is proven only with a test-set 30-row limit (`ExportEndpointIT`), not the 20 M default over S-M (Step 6c not run) | `ExportStreamingMeasurementIT`; the storage-gate evidence, Steps 6a-6d |
| 7 | REQ-007 is marked satisfied or not on the reference host, with measured one-month and labelled six-month figures | **Met as ruled, and the answer is NOT SATISFIED.** The month was not measured: one week was (`OR-112` (3)); the month and six months are labelled EXTRAPOLATED. The status line, the traceability row and open item 5 say so | `011-storage-gate.md`; `REQ-007`; `traceability.md`; `DECISIONS.md` open item 5 (a dated draft note) |
| 8 | `arch-tests` green, including the gateway-planted JDBC fixture and the OR-49 caller rule | claimed | `arch-tests` 78, 0 failures (run here); `ApplyPathBoundaryTest.theGatewayHoldsNoDatasource`, `MonitoringBoundaryTest.onlyTheRuledCallersEnumerateActiveTenants` |
| 9 | The demo's monitoring is deleted after parity | claimed | `a5167c07`; `DemoParityChecklistIT`; `FixedMiPortSourceScanTest` |
| 10 | Every OQ answered and registered; every flag listed as open unless ratified | claimed, below | the OQ table and the flags below |

#### Every OQ, with its answer and registered id

| OQ | Answer | Registered |
|---|---|---|
| OQ-1, 2, 4, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18, 21, 22, 23, 24, 25, 27 | each **as recommended** (three reword earlier rulings: OQ-2, OQ-6, OQ-17) | `OR-92` (2026-10-05) |
| OQ-19 | MW0 may start now | `OR-91` |
| OQ-28 | six tasks start early on their own gates | `OR-93` |
| OQ-26 | `B/D5`'s walking-skeleton gate does not hold plan 011's path 2 | `OR-94` |
| OQ-14 | a **conditional** P11-5.2 (**not built**) | `OR-95` |
| OQ-29 | a "count of matching events" extraction | `OR-106` (2026-10-06) |
| OQ-3, OQ-5, OQ-20 | one broker connection per (tenant, environment), the payload-form key, a placement-less drop counted at the broker connection | `OR-107` (2026-10-06) |

Rulings this plan applied, by their registered ids: `OR-69` to `OR-81` (the M-Q rulings), `OR-82`, `OR-88` to `OR-91`, `OR-92` to `OR-95`, `OR-97` (approval), `OR-98`, `OR-99`, `OR-100` and `OR-110` (the host), `OR-106`, `OR-107`, `OR-112` (items (1), (2), (3), (4), (6)), `OR-113`, and, for what follows, `OR-114`, `OR-115` and `OR-116`. **Ids allocated by this plan's tasks:** `BE-D43` (P11-5.1), and the migration numbers `sm_metrics` `V001` to `V003`, `sm_data` `V015` (plan 010's), `V016`, `V017` (renumbered from `V018`) and `V019` (`broker_connection_live_broker_once`, `OR-112` (2); `V018` is the `or112a` lane's `placement_start_observation`; `V018` to `V020` were anchored by `7839ac8f`), `sm_control` `V014`. **NOT allocated, still placeholders (the integrator's, in merge order, `I3`):** `BE-D«next-or112b»` (`OR-112` (2), one live connection per broker and VPN; it is also named in the comment of `sm_data` `V019`, which is frozen and cannot be edited, so the allocation is recorded in `backend/decisions.md` and not in that file) and `BE-D«next»` (plan 010's load-time validation library, not this plan's). **The I1 table** is P11-3.1's (above, pinned by `IngestApiShapeTest` and `IngestServiceIT`): every identity part and stored value is an SM record or the package SM owns, and no row needs an MI change.

#### Skips, each with its reason

`MetricsCollectionLiveIT` (7): gated on `sm.walkthrough.ssh.host`; the integrator's live runs are the evidence. `MetricsStorageBenchmarkIT` (7) and `MetricsGateBenchmarkIT` (2): gated on `sm.bench.url` / `sm.bench.gate.url`; the live run is the integrator's. The full-build skips recorded earlier (dp-core 1 + 12, launcher 27): live-gated and `broker.host`-gated ITs of plans 010 and 011, whose reasons each skip prints. **Not run at all (not a skip, a gap):** Steps 6b, 6c, 6d; the Step 8 teardown report; the frontend's contract tests (MF25's counts are P11-1.1's; `frontend/` is read-only here); a full reactor build at this head.

#### Mutations

Each task's Results records its own proofs, each seen red and restored (for example P11-3.1 12, P11-6.2 18, P11-7.2 43, P11-8.1 tooling 26, and the `or112a`/`or112b` entries). **This lane's:** `GateMasterKey` six (URL ignored, password ignored, random key id, blank password accepted, the boot back to the random key, the key equal to the password), each red; and the six-month evidence label's assertion, red against the old text. This lane **did not re-run** the earlier tasks' mutations.

#### Measured facts (MF), status at close

**Filled, with their evidence:** MF1, MF15, MF21, MF20, MF23 (P11-2.1), MF16 (P11-3.1), MF19 (P11-3.2), MF14 (P11-5.1), MF18 (P11-7.2), MF24 (P11-1.1 Step 0c), MF26 (a local broker: topic matching is case-sensitive), MF22 (ArchUnit 1.5.0 cannot see a string constant), MF8 and MF9 (the E2E host, read-only), MF10, MF2, MF3, MF4. **Half:** MF17 (the storage gate: bytes/row and WAL measured at one week; the 30-day query times and the co-located insert rate NOT measured), MF7 (the `MaxSessions` value measured; the N+1-channel harness half not run), MF5 (the local half measured; the 105 M-row half NOT MEASURED), MF13 (the file-to-file capture; the file-events shape inferred), MF25 (P11-1.1 ran the frontend's tests read-only; not re-run here). **Not measured:** MF6 (exec-latency percentiles over a pooled session); MF11 and MF12 belong to plan 010. The table rows above were reconciled with this list in this commit.

#### Flags still open (none is ratified; every one is listed, none is "closed" unless a ruling says so)

1. **REQ-007 NOT MET at the measured gate topology; open item 5 narrowed, not closed; `SM-D8` unratified.** Six months of raw at S-A is about 88.5 GB (extrapolated) against about 18 GB free on the reference host. For the owner: disk sizing or a longer interval; **`M-Q19` (c), a separate database, is put to the owner** with the measured numbers (evidence file, Step 7); a co-located re-run of Step 3; reduce statements per query; route raw-heavy wide queries to the hourly tier. **`OQ-21` (2b) is not put to the owner** (the hourly tier is about 0.9 GB at six months).
2. **Step 6b (the 105 M-row export) is the owner's:** the tooling does not exist, the reference host cannot hold it, and the local machine has about 51 GiB free against a 47 GB requirement. Until it is measured, **`exportRange` is wired, not counted served**, MF5 is half measured, and acceptance 6 is not claimed.
3. **The storage gate's unmeasured parts:** `IngestService` throughput beside the database (its one figure is tunnel-dominated), the rollup's host CPU (the sampler returned no samples), the month, query latency with the application co-located, Step 8 (teardown not reported). A `503` on the 200-instance raw shapes is inferred, not read from a log, to be the 30 s statement timeout.
4. **Harness:** it does not check the benchmark database's migration level against the build (the live run's two `V003` failures). The one-key fix and the label fix are made.
5. **P11-5.2 is not built.** Pushed metrics are REST-only until open items 32/33/35 close.
6. **P11-5.1:** (F1) the reviewed metric is a count of file-events heartbeats; to declare the measured `fcomplete` metrics, the four `fm-*` view-model fixtures must be regenerated (ownerless, `OR-34`); (F4) `broker_ingest_counter` has no foreign key (M-Q19's cross-schema rule), a second target is the owner's to rule; (F5) the topic pattern pins the number of levels; (F6) a data-plane restart with a stored count of one reads the first event as "no increase".
7. **P11-7.2 readings, unruled:** `replicaComposition.inactive` is always `0`, a series' `state` is always `ok`, and `aggregation.acrossReplicas` is validated and not applied (an inactive replica is not composed into a series); one entity type per dashboard; the catalog's `durability` mapping for a `solace_event` source has no ruling; an `ExportBusyException` from the streaming thread is a clean `429` only because nothing was flushed yet (a `reserve`-style port method would remove that fragility).
8. **`OR-112` (6a):** only the export's refusals write a `DENY` audit row; **no other HIGH verb's refusal does** (not ruled).
9. **Frontend consequences (`frontend/` not touched):** `seriesRef.instanceId` is nullable for an aggregate; `collector.runwayEnd` and `collector.disk` may be absent and `state` may be `unknown`.
10. **MON-01:** the tolerance is D-3's 2 x interval (the owner may retune it); a rollup row written before `V003` has no spacing and is judged by nothing until its hour is recomputed.
11. **P11-4.4:** not proven live: `direct-http`, MF6, more than two instances on one host, a window longer than ten minutes. The second Cache container the live runs created on the dedicated host (`OR-110`) is not reported removed.
12. **Stale prose left to the owner:** `CLAUDE.md` line 8 still describes the deleted demo (`P11-9.1-STALE-PROSE`, `9af7c646`).
13. **Two `BE-D` placeholders are unallocated** (above).
14. **Not re-run at this head:** the full reactor build and the frontend's tests (flag in Step 2 above).

### OR-112 follow-up, lane `or112b` (2026-10-07, `p/or112b` off `391bc1c1`): items (2) and (6)

**Item (2), W3-06: one live broker connection per broker and VPN (`OR-112` (2); one commit).** `OR-112` wins over plan text where they differ: P11-5.1 and `BE-D43` said one connection per (organization, environment) and nothing about a broker shared across environments or organizations; that key stands, and a second, install-wide key is added beside it. What was built: `BrokerConnectionMain` refuses a second LIVE connection to the same (host, port, message VPN) in its own sentence, before any credential is sealed and after its idempotence check; `sm_data` migration `V019` (a **placeholder** number: `V018` is the other lane's; the integrator renumbers) adds the partial unique index `broker_connection_live_broker_once ON (lower(host), port, vpn) WHERE retired_at IS NULL` with its `CHECKSUMS.sha256` line; the uniqueness read (`JdbcBrokerConnectionRepository.liveAtBroker`) is the operator command's own, install-wide and package-private, recorded as the new `BE-D«next-or112b»` (BE-D43 unedited, `I3`); the runbook states one live connection per broker and VPN, adapter ids unique across everything sharing a VPN, and why. **Red first** (`BrokerConnectionMainIT` + `BrokerConnectionSchemaIT`, 23 tests): 4 failures and 1 error, all five the new rules; green 14 + 9. **Mutations, each fired:** pre-check disabled (3 `BrokerConnectionMainIT` failures); host compared case-sensitively (1); index removed from `V019` (1 `BrokerConnectionSchemaIT` failure); pre-check moved before the idempotence check (2: the re-run tests); the `retired_at IS NULL` predicate dropped from the read (1). **Existing tests that contradicted the ruling and were changed, not weakened:** `BrokerConnectionMainIT.anotherEnvironmentAndAnotherOrganizationEachHaveTheirOwn` registered all three on one host (each now has its own broker); `BrokerConnectionSchemaIT`'s row fixture and `PushIngestIT`'s connection used one fixed host for every row (now a host per connection); `PushBrokerIT` registers connections to ONE real broker across tests (it retires the previous test's, as an operator would) and its two-tenant test names the same broker by `localhost`/`127.0.0.1` for the second tenant, which the key (compared as text) treats as two brokers; the five tests that count `sm_data` migrations now expect 18 (`SmDataBootstrapIT`, `SmMetricsMigratorIT` x3 assertions, `ApplyPersistenceConfigurationIT` x2). **Flag for the integrator:** if both lanes need `sm_data`, renumber `V019` to the next free number, fix those counts and the `CHECKSUMS` line, and allocate the `BE-D«next-or112b»` id.

**Item (6a), W4-04: a refused export writes a `DENY` audit row (`OR-112` (6); one commit).** `OR-112` wins over plan text: P11-7.1/P11-7.2, `ExportController` and `ExportAudit` each said a refused export (`403`, `422`, `429`) "is the interceptor's `DENY` row", and **no such row existed**: measured, nothing in the tree writes an `audit_log` row with outcome `deny` (`AuditEntry.Outcome.DENY` was only ever constructed by tests), so "find how other refused HIGH-consequence operations are audited and do the same" found nothing to copy, and the narrow path was built for the export only (the same omission holds for every other HIGH verb's refusal; **flag, not fixed: it was not ruled**). Built: `ExportAudit.refused(actor, origin, organization, correlationId, RefusalReason)` with the closed reasons `forbidden` / `export_too_large` / `rate_limited` (valid `reason_code`s; detail is exactly `correlation_id` and `reason_code`, no count, no metric, no range); `AuditLogExportAudit` writes it as outcome `deny`, verb `metrics.export`, resource the organization; `ExportRefusalAuditInterceptor` (registered ahead of `AuthenticationInterceptor`, in `afterCompletion`) writes it from the status the client is about to see, so the three producers (the interceptor's reach check, the handler's rate gate and `plan()`, and the stream thread's busy on the async re-dispatch) are covered by one rule; a failing audit write is logged by class and never replaces the refusal. Not audited, by the ruling: `400`, `401`, `404`, `503`. **Red first:** gateway `MetricsExportRouteTest` 21 tests, 5 failures (one per refusal class, the async-executor refusal included); `AuditLogExportAuditIT` 6, 1 error (stub); `ExportEndpointIT` (real boot, real audit log) 4, 3 failures with the interceptor's registration removed; all green after (21, 6, 4; gateway module 609, arch-tests 76 + 77). **Mutations, each fired:** `422` not mapped (1); registered after `AuthenticationInterceptor` (the `403` goes unaudited, 1); `503` audited (1); any route audited (1); the row written as `allow` (1). **One guard is defence in depth and no test can fire it:** the `try/catch` around the audit write, because Spring swallows an exception from `afterCompletion` itself (`aDenyRowThatCannotBeWrittenNeverReplacesTheRefusal` therefore passes with or without it; it pins the observable outcome, not the guard). A `429` from the query rate and one from the export concurrency share the reason `rate_limited` (the correlation id joins to the response body's own `reason`).

**Item (6b), W4-05: `getCapabilities` may omit `runwayEnd` and `disk`, and says `unknown` (`OR-112` (6); one commit).** Answers the contract gap P11-7.2's Results flagged as its reading (2): before the data plane's first partition keep `runwayEnd` was served as today's date with `state: degraded`, and an unmeasured disk as `unconfigured`, each a stand-in that read as a measurement. Now `console.yaml`'s `collector` requires only `state`, `unfrozenClosedPartitions` and `reasons`; `state` gains `unknown`; `runwayEnd` and `disk` are absent while unmeasured (`@WireOptional`, so omitted, not `null`). The rule: `degraded` when the data plane reports it (a measured fact, even beside a missing one); otherwise `unknown` while either is absent; `live` only over both. A budget-less install still answers `disk.state: unconfigured` (a measured answer). **Red first:** `MetricsQueryRoutesTest` + `MetricsOperationClassificationTest` + `MetricsSchemaMirrorTest`, 35 tests, 5 failures; green after (gateway module 614; launcher `MetricsEndpointsIT` 14, whose collector assertions now tolerate absence and pin "live only over both"). **Mutations, each fired:** the data plane's `DEGRADED` ignored (1); `unknown` decided on `runwayEnd` alone (1); `disk` not `@WireOptional` (3, `MetricsSchemaMirrorTest` among them); the contract's `required` restored (2). `ENDPOINT-INVENTORY.md` reading (10) rewritten; Postman regenerated (byte-identical: the collection carries no response schema) and `./contracts/run-checks.sh --strict` ALL CHECKS PASSED. The frontend (`frontend/`, not touched) must treat both fields as optional and `unknown` as "not measured yet".

**Item (6c), W4-06: an across-instances aggregate carries no `seriesRef.instanceId` (`OR-112` (6); one commit).** Answers P11-7.2's reading (1) and P11-6.1's W3-09: the nil UUID `00000000-…` is gone from the wire and from the code (`SeriesView.SeriesRef.AGGREGATE_INSTANCE` and its non-null check are deleted; the data plane builds the aggregate's reference all-null). `console.yaml`'s `seriesRef.instanceId` is now `type: [string, 'null']`, **required and nullable**, written as `null` exactly like `placementId` and `sourceKey` (this is my reading of "carries NO instanceId ... nullable for aggregates": the key is present and null, not omitted, so the three fields stay one shape). **Red first:** `MetricsQueryRoutesTest` + `MetricsOperationClassificationTest` + `MetricsSchemaMirrorTest`, 37 tests, 2 failures and 1 error; `MetricsQueryServiceIT` `gaugeAcrossInstancesIsSumOverCountNotAvgOfAvg` now asserts null (red by the mutation below). Green after: gateway module 616, `MetricsQueryServiceIT` 40, `AggregatorTest` 18, `GapDeriverTest` 8, launcher `MetricsEndpointsIT` 14 (the real boot's aggregate line has `instanceId: null`). **Mutations, each fired:** the data plane serving the nil UUID again (1, `MetricsQueryServiceIT`); the contract's `instanceId` back to `type: string` (1). `ENDPOINT-INVENTORY.md` reading (7) rewritten; Postman regenerated (no diff) and `run-checks --strict` ALL CHECKS PASSED. **Frontend consequence, flagged:** `frontend/` types `instanceId` as a string and keys on it (SeriesView's own old javadoc); it must now accept `null` for an aggregate line. Not touched here.

**Item (6d), GW-4: every operation that can answer `400` declares it (`OR-112` (6); one commit).** Generalises `e0fe8e83`'s three operations. `DeclaredBadRequestsTest` now enumerates the handlers (every `@RestController` method carrying `@ConsoleOperation`, by reflection) and derives each operation's framework-answerable `400` from its parameters, the exceptions `GatewayErrorAdvice.handleBadRequest` maps: a non-`String` `@PathVariable` or `@RequestParam` (a UUID, a number, a date, a list), a required `@RequestParam` or `@RequestHeader`, a `@RequestBody`. Handlers that answer `400` with none of those (a bounded string, a value parsed by hand) are the `EXPLICIT` map (`listMetricInstances`, `getMetricsCatalog`, `getLabelValues`), with a tripwire that counts `new GatewayRejectionException(ErrorCode.BAD_REQUEST)` per source file and fails when a site is added or removed. Each such operation must declare `400` in `console.yaml` as the shared Problem body (the shared `BadRequest` response, or inline with the reason, the form `e0fe8e83` used). **Red first:** the enumeration failed on exactly five operations, `getInstance`, `getDesigner`, `getConfigHistory`, `getHealth`, `getConfigLadder` (a UUID path `instanceId` and no `400`); fixed by declaring `400` (`` `BAD_REQUEST` — `{instanceId}` is not a UUID ``) on each. Postman regenerated (the five allowed sets gain `400`; check 15 and `run-checks --strict` pass). Green: gateway module 618, cp-api 48. **Mutations, each fired:** `400` deleted from `observeApply` (2 tests); a planted `new GatewayRejectionException(ErrorCode.BAD_REQUEST)` in a controller no one listed (the tripwire). **Not covered, stated:** `HttpMediaTypeNotAcceptableException` and `HttpMediaTypeNotSupportedException` also map to `400`; the first is a property of the client's `Accept` header on every operation and the contract declares content negotiation nowhere per operation, and the second only follows from a `@RequestBody`, which is enumerated. Reading it per operation would declare `400` on all 46, which is a different ruling.

### Combined Fable 5.1 review (OR-70), fix round (2026-10-07)

**Combined Fable 5.1 review (OR-70), fix round, 2026-10-07 (`p/fable`, base `a93078a6`).** The one combined review after all slices (plan 010 S0 to S12 and plan 011, in place of P11-10.1 Step 4) returned **12 findings: 0 BLOCKER, 3 MAJOR, 9 MINOR**. All 12 are fixed (none disputed, none skipped); each had a test red before its fix, recorded in its commit message.

| Id | Sev | Disposition |
|---|---|---|
| APPLY-01 | MAJOR | **Fixed in `690f7460`.** A config-secret set minted a generation with no carried log levels (the designer stage and accept-runtime kept them). One shared `CanonicalContent.carryingLevelsOf` now serves all three paths; `ConfigRecordServiceImpl.withCarriedLevels` is gone. Red first: `ApplyServiceSetLogLevelIT.aSecretSetKeepsTheConfiguredLogLevel` expected `DEBUG`, got `null`. |
| APPLY-02 | MAJOR | **Fixed in `bf942f03`.** `SshDockerRuntimeAdapter` keeps the newest `placement.retained_generations` distinct generations of its pre-image / fingerprint / absence files (always the current one) after a verified write and removes the rest through `deleteFile`; only names in SM's fixed grammar for the declared file are candidates; a listing or delete failure never fails the apply. New transport method `listHiddenFileNames` (bounded to 4096). Red first: three `SshDockerRuntimeAdapterContractTest` cases saw generations 1-6 where 4-6 were expected. The evidence file no longer says "SM never deletes them". |
| MON-01 | MAJOR | **Fixed in `c1556f04` (+ `645f137f`).** The rollup rule `sample_count < expected_count` flagged nearly every closed hour and day (the collector's period is always a little over the declared interval) and missed a day with an absent hour. `sm_metrics` **`V003`** stores `max_gap_seconds` (longest spacing ending in the bucket, looking back across the edge) and `interval_seconds`; the query judges `max_gap > 2 x interval` (D-3's rule); a bucket following an absent one is not flagged twice; the judgement survives widened folds and pooling. `expected_count` is unchanged. Dated note beside the plan text. Red first: `MetricsQueryServiceIT` drew 48 `partial-samples` bands for a 30.5 s-spaced series, none for a day with an absent hour, and a double flag for a straddling outage. |
| APPLY-05 | MINOR | **Fixed in `24afc99a`** (section 4's T9.1 row: flag (i) resolved by `OR-108` (5), flag (ii) stays open). |
| MON-03 | MINOR | **Fixed in `4205f739`.** An insert whose timestamp is outside the current and next UTC month runs in a savepoint (`IngestRows.Raw.insertBeyondRunway`), so the failed `INSERT` no longer aborts the batch's transaction (25P02 -> database resting -> the batch held forever). Red first: `IngestServiceIT.anOutOfPartitionFirstSampleOfAFreshSeriesDropsOnlyThatSample` got `RetryAfter` and its five successors failed on the resting service. Residual: the empty series row minted for the dropped sample stays (harmless, no samples). |
| code-GW-1 | MINOR | **Fixed in `e0fe8e83`.** Six descriptions said "not yet served" / "awaiting the owner"; `getHomeSummary` now cites `OR-61` as ruled. Check 17 also scans paragraphs with whitespace collapsed and refuses `not (yet) served` (seven hits on the old file, none on the new). Postman regenerated. |
| code-GW-2 | MINOR | **Fixed in `e0fe8e83`.** `logs.loggers.path` and `levelProperty` end in `(?![\s\S])`; two negatives (`gate01-loggers-{path,levelproperty}-trailing-newline`, 76 -> 78) and two `MutantParityTest` cases; cp-core's classpath copy of the schema updated. Red first: `validate-package.py ... --expect-fail` reported both new negatives accepted. |
| code-GW-3 | MINOR | **Fixed in `e0fe8e83`** (`ENDPOINT-INVENTORY.md`: `listSolutions` is a reach pre-check with no per-item filter; the bijection is pinned by check 15 and `ConsoleOperationsMirrorTest`). |
| code-GW-4 | MINOR | **Fixed in `e0fe8e83`** for the three named operations (`observeApply`, `listInstances`, `observeFleetApply` declare `400`), pinned by `DeclaredBadRequestsTest` (red first on `observeApply`). **Not generalised:** every other operation with a UUID path parameter can answer the same `400` and does not declare it; see the owner questions. |
| code-GW-5 | MINOR | **Fixed in `959cbcb1`.** `OperationCoverageValidator` also requires no root and no default handler on a mapping it lets through; two planted-violation tests (root only, default only) were red first. |
| code-GW-6 | MINOR | **Fixed in `959cbcb1`** (comment only: a package with no probe is `NOT_APPLICABLE`, `OR-101`). |
| code-GW-7 | MINOR | **Fixed in `e0fe8e83`** (`readLogs` is **not** redacted; `logs.read` is the control). |

**Owner questions from this fix round (none blocks).** (1) `V003` is a new `sm_metrics` migration: it is unfrozen until the publishing commit's SHA is appended to `frozen-migration-anchors.txt` after the push, and a rollup row written before it has no spacing and is judged by nothing (unknown is not a gap) until its hour is recomputed. (2) MON-01's factor is D-3's 2 x interval, kept from the raw rule; a different tolerance is the owner's. (3) Operations with a UUID path or query parameter other than the three above can also answer `400` undeclared; declaring `400` on all of them (and in the Postman allowed set) is a wider contract change. (4) Still open as already known, not re-reported: W3-01, W3-06, W4-04, W4-05, W4-06, P11-8.1's scaled storage gate, P11-9.1's demo deletion; their impact is no worse than stated.

**Gate after the fixes** (`mvn -o -f backend/pom.xml clean verify` with the Testcontainers override, **BUILD SUCCESS**, all ten modules; the first run of the round failed once, in `SmMetricsMigratorIT`, which pinned two `sm_metrics` migrations - fixed in `645f137f`, then a second complete green build): cp-api 48 / -; mesh-events 160 / -; cp-core 466 / 287; dp-core 1086 (1 skipped) / 1140 (12 skipped); console-gateway 601 / -; launcher 77 / 289 (27 skipped); demo-launcher 6 / -; arch-tests 76. `./contracts/run-checks.sh --strict` ALL CHECKS PASSED (step 14 against a sibling worktree's Ajv 8); `check-frozen-migrations.py` PASS (33 migrations byte-identical; `V003` not yet anchored); `git diff --check a93078a6...HEAD` clean.

### Wave 2 fix round (W2-01 to W2-13), dispositions (2026-10-06)

One fix round on `p/w2` after the wave review: 5 MAJOR (W2-01, 03, 04, 05, 06) and 8 MINOR (W2-07 to W2-13, W2-12 included). Every finding
had a test red before its fix; the red is recorded in the commit message.

| # | Sev | Disposition |
|---|---|---|
| W2-01 | MAJOR | **Fixed.** `copyLatestWithLogLevel` takes the placement and, under the instance lock, refuses `UNAPPLIED_DRAFT_PENDING` (`409 CONFLICT`) when the latest generation, level entries set aside, differs from the generation last written to the placement (`RollbackTargetRows.highestEligibleBelow`, so `OR-108` (7)'s resolved-applied counts). A placement nothing was written to, and a written generation retention pruned, are refused too (not comparable). Mutation: check removed, two new `ApplyServiceSetLogLevelIT` cases red. `LogLevelConfigEndpointIT` now applies its staged generations first. |
| W2-03 | MAJOR | **Fixed.** `timeout --kill-after=2 <n> docker inspect` (exit 124 or 137 is `Failed("timeout")`), a per-channel watchdog in `SshjHostConnection`, and a pool backstop that abandons a call outliving deadline plus backstop, closes the connection and frees the slot (`timeout`, never reconnect-and-repeat). The sshj watchdog cannot be driven by a unit test (no sshd in this module); the pool backstop is, with a connection whose inspect never returns. Mutation: backstop wait unbounded, both new `SshSessionPoolTest` cases red. ⚠️ The host needs GNU `timeout`; a host without it answers 127, which reads as `docker.inspect.failed`, and the watchdog still bounds the call. |
| W2-04 | MAJOR | **Fixed.** The guard's second condition is `computed_at >= newest received_at` of the series' last raw hour; `MetricsPartitionMain rollup yyyy_mm` recomputes a closed month for the tenants the guard lists. Runbook updated. Red against the old main: six `MetricsPartitionMainIT` cases. Honest limit: the guard proves the rollup ran after the newest row's application-clock `received_at`, not that its snapshot included an uncommitted row; the clock condition (month end plus raw tier plus late window) is what rules a late commit out. |
| W2-05 | MAJOR | **Fixed.** `port` required on `$defs/restartSignal` (schema and cp-core mirror), `gate01-restartsignal-without-port` (59 negatives), and the ingest context ignores a signal row with no numeric port. |
| W2-06 | MAJOR | **Fixed in the contract; the handler test belongs to P11-7.2.** `MetricCatalogEntry.observedAvailability` is required (the shared `Availability`, whose states are lowercase `live`/`degraded`/`unavailable`), check 18 asserts it. No catalog handler exists yet, so `aFailedPlaneIsDegradedNeverAnEmptyCatalog` stays P11-7.2's test, now specified. |
| W2-07 | MINOR | **Fixed, narrower than proposed.** `X-SM-Dashboard-Revision` is required on rows 63-66 (missing or malformed is `400`, stale is `409`), asserted by check 18. **Not taken:** `If-Match` with `428`, because `428` is already `MUST_ROTATE` and a new error code reaches the Java enum, check 18's parity and the frontend. The frontend sends no revision today; it must when it integrates. |
| W2-08 | MINOR | **Fixed.** Dated note in `dynamic-metrics/design.md` section 5.5. |
| W2-09 | MINOR | **Fixed.** The evidence note no longer says which registered value matched. **Owner action:** the real E2E Cache broker's credential is weak and should be rotated (told to the owner in the fix-round report, not recorded here). |
| W2-10 | MINOR | **Fixed.** A sample more than five minutes past the clock is dropped `parse_failed` before a series is minted; `NO_PARTITION` outside the current and next UTC month is dropped `out_of_order`; only those two months are held. The ingest ITs run on a clock that starts an hour after `base()` (they were date-dependent on the 1st of a month), and `IngestRunwayIT` drops next month's partition with a clock two minutes before month end. |
| W2-11 | MINOR | **Fixed.** The cap counts series of a live placement whose latest segment is under the instance's current package version (or has no segment yet). Reading: a retired placement's series and a retyped metric's superseded series are history, not load. |
| W2-12 | MINOR | **Documented, not derived.** The `setLogLevel` description says a lost scope race leaves the level staged, that `getLogLevel` then reports it and the next apply writes it, and that a re-submit admits it (pinned by an IT). `configuredLevel` is still the latest generation's, not the last written one: that needs a placement parameter through `LogLevelServiceImpl` and the read-back, and it would hide a staged but unapplied level from the console. |
| W2-13 | MINOR | **Fixed.** `IngestBuffer.claimHead` marks the batch being committed; `offer` evicts the next-oldest instead, and the committing batch sits outside the bound. |

### Wave review of `p011/merge` (d062ba6): P11-2.1, P11-2.3, P11-7.1, dispositions (2026-10-05, `OR-97`)

An Opus review returned `changes-required`: 2 MAJOR, 7 MINOR. All nine are applied; none disputed.

| # | Sev | Disposition |
|---|---|---|
| 1 | MAJOR | **Fixed.** Red first: `MetricsPartitionMainIT.anInterruptedDetachIsFinishedByRunningDetachAgainAndStatusShowsItPending` held a REPEATABLE READ reader open past an injectable (1 s) `lock_timeout`, and failed with the raw SQLSTATE 55P03 and no recovery. Now: the catalog lookup also reads `inhdetachpending`; a pending partition is finished with `DETACH PARTITION ... FINALIZE`; a failed `CONCURRENTLY` that left the partition pending exits 1 with a fixed message saying so and to run `detach` again; `status` prints `pending detach: N` and tags the partition; the runbook table says it. The `lock_timeout` is now set through a bound `set_config`. Mutation: `FINALIZE` branch removed (always `CONCURRENTLY`), the new IT red at the second `detach`. |
| 2 | MAJOR | **Fixed in the docs; one part left to the S6 owner.** Plan 010's allocation table gains `V017` (`OR-96`, dated note); plan 008's header gains a dated "V018 onward" note (earlier notes kept); this plan's Migration allocation and P11-2.2 text are corrected: P11-2.2's merge after T11.2 does **not** avoid the out-of-order fallback because S6 is expected first, so V011-V016 merge out of version order and the integrator recreates the persistent dev `sm_data` (option (b) of the review). The wrong "V015 and V016 are plan 008's" comment in the **unpushed** `p010/s6` `V017` is **not edited** (another worktree): the slice owner fixes it before S6 merges. |
| 3 | MINOR | **Fixed.** `drop` hashes the archive as a stream (`sha256Of`); `MetricsPartitionDigestTest` checks a multi-buffer file and an empty one. Honest limit: that test would also pass with `readAllBytes`, since the heap bound is not observable in a unit test; the guard is the code shape, not a mutation. |
| 4 | MINOR | **Recorded as a P11-3.2 obligation** (the watermark exists only there) and as a runbook rule; no code guard in this task. |
| 5 | MINOR | **Fixed.** Dated pointers appended to `architecture.md` §8.4 and `dynamic-metrics/design.md` (plan 011 approved, `OR-97`; archive per tenant by bound `SELECT`, no `COPY`). |
| 6 | MINOR | **Fixed.** `operations.md` now says the console gateway refuses, that P11-7.2 is not yet built, and that until then the check is the only protection. |
| 7 | MINOR | **Fixed.** `DashboardDocumentValidator.shared` refuses a declared histogram with a fixed sentence; `aWidgetForADeclaredHistogramIsRefusedBecauseNoSeriesWillExist`. Mutation: check removed, test red. |
| 8 | MINOR | **Fixed.** `DashboardStore.get` javadoc states the precondition; P11-7.2 gains the pinned test `anUnknownOrForeignInstanceIdGets404BeforeDashboardStoreIsCalled` with a mutation. |
| 9 | MINOR | **Fixed.** P11-2.1 Results corrected to the sixteen named mutations (the other three were never traceable, L22); the review's own six proofs (shadow defences, org filter, runtime UPDATE grant, argument matcher, classifier, drop verification) are cited in its report. |

**Gate results after the fixes** (`mvn -o -f backend/pom.xml clean verify`, BUILD SUCCESS, 0 failures; per-module surefire / failsafe): cp-api 48 / -; mesh-events 160 / -; cp-core 324 / 287; dp-core 399 (1 skipped) / 568 (4 skipped); console-gateway 278 / -; launcher 5 / 79 (1 skipped); demo-launcher 4 / -; arch-tests 43 / -. **Skips, each visible and by design:** `InMemoryRuntimeAdapterContractTest` 1 (disabled when `broker.host` is set); `CacheApplyWalkthroughIT` 2 and `CatalogEventIT` 1 (need `broker.host`); `JcsmpEventPublisherIT` 1 and `MeshLifecycleIT` 1 (need `broker.host` / `sm.demo.cache`). No broker was configured, so the live-broker legs did not run. `./contracts/run-checks.sh --strict`: ALL CHECKS PASSED (node_modules symlinked temporarily, removed). `git diff --check fea3f77..HEAD`: clean.

### MW0 (docs only), executed 2026-10-05 on `p011/mw0` (`OR-91`)

| Task | Commit | Built | Verified |
|---|---|---|---|
| **P11-0.1** | `a521378` | Plan 009 SUPERSEDED-in-part header; dated pointers beside plan 007's five mentions of plan 009, plan 008's `V017`-onward header note, two forward pointers in `console-integration.md` (`OR-80`, `OR-78`), one pointer each in `demo-delivery-sequence.md` and `traceability.md`; `docs/tasks/plan-011-mw0-integrator-handoff.md`. `REQ-010` was **cited, not created** (it exists with the owner's three elements) | Relative links and anchors in the touched files resolve (three `agent-gateway` links in `traceability.md` were already missing at HEAD and are not this task's); `git diff --cached --check` clean; docs only. **Step 4b (post-sitting drafts) is not done** |
| **P11-0.2** | `7f8a6dc` | `docs/tasks/dm-design-review-2026-10.md`: **34 findings (DMR-1…DMR-34)**, each with its own disposition (fixed, routed, flagged, no change); hand-off H1–H3; **OQ-26** and **OQ-27** drafted; dated pointers (text unedited, `I3`) in `dynamic-metrics/design.md` and `architecture.md` §8.4 | External facts WebFetched 2026-10-05: ECharts doc source (`2d-data`, `series/line`), PgJDBC cursor fetch, WCAG 2.1, PostgreSQL 14 `COPY` and `PREPARE`. Not "all closed": per finding |
| **P11-5.0** | `0127b50` (merged `c9be575`) | `docs/design/backend/evidence/011-filemesh-publisher-facts.md` (F1–F20, MF13 answered, OQ-5/OQ-15/OQ-20 facts, handoff items). Cited by OQ-4, OQ-5, OQ-15, OQ-20 and OQ-29 | **Deviation:** the task's premise (a publisher topic builder and serializer in `commons@cd52e31`) was wrong; the facts come from 34,527 captured events, the consumer code, the FM Manager generator and the model classes. `start`'s `password`: **UNVERIFIED** |
| **Owner sitting** | the commit that adds this row | `OR-92`…`OR-95` recorded in this plan: 25 of 29 owner questions RULED (21 as recommended `OR-92`, OQ-28 `OR-93`, OQ-26 `OR-94`, OQ-14 `OR-95`), OQ-3/5/20/29 kept OPEN until MF26; each touched task names what was ruled; P11-5.2 stubbed; the review cadence set in the dispatch map and Review Focus | Links and anchors in the file resolve; every OQ row has six cells; docs only; the plan stays a DRAFT |
| **Pre-sitting review fixes** | the commit that adds this row | Review round 3 (below): the per-task gate table, OQ-26…OQ-29, MF26, the `p011/fm` facts merged into OQ-4/OQ-5/OQ-15/OQ-20 and MF13, the plan 010 in-flight column, the `placement.metricsPorts` rebinding, V015's `UNIQUE`, the P11-4.2 split, mutation proofs | Links and anchors in the touched files resolve; every OQ table row has six cells; `git diff --cached --check` clean; docs only; no code, migration, contract or pom changed |


**Flag dispositions after the 2026-10-05 sitting.** Ruled by the owner (no longer open questions; the registered rulings they reword stay unedited): OQ-2 (departs from M-Q4 (c)'s "now"), OQ-6 (widens M-Q3's named caller), OQ-14 (live leg is a conditional P11-5.2), OQ-17 (departs from M-Q6's "per instance+source"), OQ-21, OQ-22 (D-2…D-17 accepted), OQ-23, OQ-24, OQ-25, OQ-26 (a reading of LOCKED `B/D5`, `OR-94`), OQ-27, OQ-28. **Still open:** OQ-3, OQ-5, OQ-20 and OQ-29, and MF26, deferred (`OR-95`). The list below is as written before the sitting; read each of those items by the line above.

**Flags that stay open unless ratified** (to be listed again at close):
- M-Q16 supersedes part of a RATIFIED D10 approval under the owner's authority;
- M-Q7 amends PROPOSED `AGW-D9`'s field semantics (a proposal against a proposal);
- M-Q19's intra-DP cross-schema FKs (from `series`, `restart_marker` and `ingest_counter` to `sm_data.placement`) are
  a new pattern;
- M-Q13's narrow I5 carve-out, recorded **only after OQ-21** (P11-0.1 Step 4b): the function's schema (`sm_metrics`,
  not the ruling's `sm_data`; confirm-only), and the grammar kept **exactly** `metrics_raw_YYYY_MM` under OQ-21 (2a)
  (the hourly tier unpartitioned). Both sanctioned identifier cases go into the OR-M-Q13 wording: the function's
  clock-derived names, and `MetricsPartitionMain`'s argument-checked, catalog-resolved month (M-Q13 "common", M-Q18);
- M-Q9's *"reuse health inspect when fresh"* reconciled to "there is none to reuse" (P11-4.2), **and** the SM-D30
  reconciliation (guard 2 fires only for `declared`/`docker` sources and stays dead for `inferred` ones), both for the
  OR-M-Q9 record;
- OQ-2's recommendation departs from M-Q4's literal "now" for the agent branch (M-Q6/M-Q12), until the owner rules;
- M-Q12's live-delivery leg is scheduled by OQ-14, not dropped; **OQ-14 (b) would depart from M-Q12's "in plan 011"**,
  which only the owner can do;
- OQ-6 (a) widens M-Q3's named caller from "the metrics collector" to "the metrics background loop" (collection,
  rollups, push reconcile);
- OQ-17 (a)/(c) depart from M-Q6's "per instance+source"; (b) follows it but changes M-Q21's shape;
- OQ-20: M-Q6's "counted per instance+source" and M-Q21's per-placement key pull apart for push drops with no single
  placement; under (a) an event for no known instance is a **known, documented blind spot**;
- the discovery's §3.2 defaults (D-2…D-17) are **not owner rulings** unless OQ-22 accepted them;
- `capabilities.collector.disk` exposes only an install-level state; the byte figures reach only the operator command
  (OQ-16's scope rule);
- M-Q19 (c) (a separate database) is put to the owner after the storage gate, unconditionally (P11-8.1 Step 7);
- any P11-8.1 finding that a full S-M export cannot finish inside M-Q22 (2)'s 30 minutes on the reference host;
- S-B and S-C are sizing arithmetic from measured bytes/row, never exercised (P11-8.1 Step 4);
- M-Q24 (11) settles open item 27(b) for stored data only;
- open item 28 is narrowed, not closed;
- every `DM-D*`, `SM-D26/29/30/31`, `UI-D5/8/13` still PROPOSED;
- `OR-82` amends `OR-71`'s contract reading (the host is the target's registered address, not a declare-body field); `OR-71`'s
  text is unedited (`I3`);
- `OR-89` lets two organizations register one machine, and no port-collision guard spans them (**OQ-23**);
- OQ-24: a required `port` on `http_json` would break consumers outside this plan's write set, including the frontend
  (owner standing rule: no frontend change);
- OQ-25: `OR-70`'s review cadence names plan 010 only;
- `monitoring-discovery.md` is absent from the repo and the recovered scratchpad: its scenario ids and defaults are unverified;
- **`B/D5` is LOCKED and its gate bars "any … File-Mesh work" before the walking skeleton is accepted; path 2 (MW5) is read against it by no ruling** — *LOCKED; read as not holding a generic path 2 (`OR-94`, OQ-26 (a)); text unedited*;
- `OR-82` also contradicts `OR-74`'s clause *"the direct host and port"* of the instance, which `OR-82` does not name (an `I3` clarifying row is drafted in the hand-off);
- **OQ-28:** `OR-91` relaxed the closing line for MW0 only, so no code task starts early without a ruling; the early starts also cost plan 010 rebases (stated in OQ-28 and the per-task gates);
- OQ-27: the plan archives per partition while `architecture.md` §8.5 (PROPOSED) says per tenant;
- **OQ-29:** File Mesh's heartbeat carries no number and no event-count extraction exists, so path 2 has no real metric until the owner picks (a), (b) or (c); `start` may carry a plaintext broker password (facts F15, MF13 partly measured); MF26 (topic case sensitivity) is unmeasured;
- plan 010's `T4.4`, `T5.3`, `T6.1` and `T6.2` were **read on unpushed local branches** (2026-10-05) and are rebound on that basis; they rebind again at push.

---

## Plan review — dispositions

*(File:line cites and plan 010 line numbers in this section are as of `0cbaa28`, when the reviews ran. They are historical and
were deliberately not re-pointed in the 2026-10-05 refresh; the live body above carries the re-measured ones.)*

*Two independent adversarial reviews (Opus 5.5 high, read-only) are required before approval. Their dispositions go
here, one row per finding, each re-verified against the repository with the file:line that settled it.*

### Review round 1 (2026-09-30): 41 findings, 17 MAJOR and 24 MINOR

Every finding was re-checked against the tree at `0cbaa28` or against plan 010 before it was applied. **None is
disputed.** Four findings duplicate earlier ones and are marked **dup of R1-n**; each pair got one fix. "Fixed"
means fixed **in this plan's text**. Nothing is built yet, and each fix is proved only when its task's tests and
mutations run.

| # | Sev | Finding (short) | Disposition | Where / the evidence that settled it |
|---|---|---|---|---|
| R1-1 | MAJOR | The 105 M-row stream was never run; the refusal replaced it; export was served in MW7; disk not budgeted | **Fixed** | P11-8.1 Steps 6a–6d (disk arithmetic first; all 105 M rows streamed with a labelled override; the `422` re-check is separate; EC2 or local Testcontainers); MF5; MW6/MW7/MW8 claims; P11-7.2; P11-10.1 Step 1 (row 60 counted only after 6d); Acceptance 1 and 6 |
| R1-2 | MAJOR | The M-Q19 disk-usage alarm was dropped | **Fixed** | P11-3.2 `MetricsDiskAlarm` (two bound catalog reads, no shell), tests and mutations; P11-1.2 `capabilities.collector.disk`; **OQ-16** (measure and threshold); MF19 (privileges, UNVERIFIED) |
| R1-3 | MAJOR | M-Q12's live leg was moved to Non-goals without a ruling | **Fixed** | **OQ-14** (status quo, options, recommendation, conditional P11-5.2); the Non-goals row reworded; MW5 claim; P11-5.1; rulings index |
| R1-4 | MAJOR | REQ-010 carried a fourth, unruled criterion | **Fixed** | P11-0.1 Step 1 (exactly the three owner-stated elements); P11-7.1 note; **OQ-18** |
| R1-5 | MAJOR | Path-2 placement, binding and `sourceKey` attribution undefined | **Fixed** | **OQ-15**; P11-5.1 `PushAttribution` and `anInstanceWithTwoLivePlacementsHasItsEventDroppedAndCountedNotGuessed` (renamed `…DroppedNotGuessed` in round 2, R2-1); the I1 table row; `sm_metrics` V002 reserved |
| R1-6 | MINOR | M-Q9's "reuse health inspect" cited D-9 instead of reconciling | **Fixed** | Measured `010-…md:3548-3552` (the poller uses `HostProbe`/HTTP; `docker inspect` stays in the apply gate). P11-4.2 note; P11-4.3 D-9 citation replaced; rulings index; OR-M-Q9 wording handed over by P11-0.1 |
| R1-7 | MINOR | OQ-2 did not cite the M-Q4 vs M-Q6/M-Q12 conflict | **Fixed** | OQ-2 status-quo and recommendation cells quote all three; Results flag |
| R1-8 | MINOR | OQ-10 option (b) would reverse M-Q13's freeze | **Fixed** | OQ-10 (b) replaced by a runner/timing alternative; the question is only who runs it and when |
| R1-9 | MINOR | The function's schema and grammar changed without a recorded reconciliation | **Fixed** | Rulings index M-Q13 row; V001 comment; Results flags; OR-M-Q13 wording handed over by P11-0.1 |
| R1-10 | MINOR | The I5 constraint contradicted `MetricsPartitionMain` | **Fixed** | Global Constraints (two sanctioned cases); P11-2.1 `MetricsPartitionArgument` and `aMalformedOrInjectedMonthArgumentIsRefusedBeforeAnySql` |
| R1-11 | MINOR | The I7 sentence said "the only cross-schema reference" | **Fixed** | Global Constraints I7; V001 comment; `everySmMetricsForeignKeyTargetsSmMetricsOrSmDataPlacementOnly` |
| R1-12 | MINOR | Counter rates were not pinned to actual Δts | **Fixed** | P11-6.1 formula per tier; `covered_seconds` (P11-2.1, P11-3.2); `aCounterRateUsesTheActualTimestampDeltasNotTheNominalInterval` (±10 % jitter) |
| R1-13 | MINOR | The per-metric catalog reasons had no derivation | **Fixed** | **OQ-17** (a) defines the derived view ("placements serving this metric"; `undeclared_dropped` never per declared metric); P11-1.2 gates the shape on it |
| R1-14 | MINOR | MW0 ran before plan 010 S6 against the rulings' closing line | **Fixed** | MW0 now opens after plan 010 W10; an earlier start only on **OQ-19** (pending, asked before dispatch) |
| R1-15 | MINOR | The gateway recomputed the digest; "own transaction" was ambiguous | **Fixed** | Plan 010 `010-…md:2328`, `:1646`. P11-4.1 carries the CP-stored digest, never hashes; `theDigestPassedToTheDpIsTheCpStoredDigest`; "in `reserve`'s own transaction" |
| R1-16 | MAJOR | `rollup_1h_type_shape` refused a counter hour with a NULL delta | **Fixed** | V001: a `metric_type` column bound by a typed FK (`series_typed`), type-decided CHECK, `covered_seconds`; `aCounterHourWithOnlyNullIncrementsRollsUpWithNullDeltaNotZero`; schema tests and mutation |
| R1-17 | MAJOR | The planted `metrics_raw` temp table could never be resolved, so the mutation could not fire | **Fixed** | Planted `pg_inherits`/`pg_class` shadows; the function qualifies `pg_catalog.*`; two static tests plus one behavioural test; the Step 5 mutations prove each layer alone and both together; MF21 (UNVERIFIED) |
| R1-18 | MAJOR | `V«dp-next»` waited on unscheduled plan 008 | **Fixed** | Measured `010-…md:365-369`, `:1280-1283`. [Migration allocation](#migration-and-id-allocation): `sm_data` V015 (P11-2.2) and V016 (reserved), plan 008 renumbers to V017+ (P11-0.1's header note), `sm_control` V014; the dependency table gains T11.2 and plan 008 |
| R1-19 | MAJOR | P11-0.1 edited `TenantEnumerationRepository`, plan 010's file | **Fixed** | Measured `010-…md:387`, `:2038-2043`. Removed from P11-0.1 (now docs only, no `VERIFY dp-core`); the javadoc line moves to P11-4.3; single-owner table |
| R1-20 | MAJOR | The authorization files were not listed | **Fixed** | Measured `OperationPolicy.java:34-35,45-60`, `ConsoleOperationsMirrorTest.java:34-44`, `validate-openapi.py:59-67`, `010-…md:1591-1606`. P11-1.2 Files, scopes, Step 0, tests and mutations; P11-7.2 `DashboardResourceResolver` |
| R1-21 | MAJOR | "The newest generation that reached restart-observed" had no data source | **Fixed** | Measured `010-…md:2680-2683`, `:2760`. P11-4.3 Step 0 and `JdbcRestartObservedGenerationRepository` with `RestartObservedGenerationIT` (gen N+1 staged → gen N) |
| R1-22 | MAJOR | P11-5.1 did not depend on P11-4.3's loop | **Fixed** | P11-5.1 Depends and Files (`MetricsBackgroundLoop`, `MetricsConfiguration`); MW5 opens after MW4; single-owner table |
| R1-23 | MAJOR | The shared budget of 4 had no owner | **Fixed** | P11-3.1 creates `MetricsDbBudget` (one bean, tests); P11-6.1/6.2 inject it; `queriesAndExportsShareOneBudgetOfFour` |
| R1-24 | MAJOR | A dp-core IT cannot run "as an Operator" | **Fixed** | P11-8.1 splits `MetricsStorageBenchmarkIT` (DP only) and the launcher-level `MetricsGateBenchmarkIT` (HTTP, session, Operator binding) |
| R1-25 | MAJOR | dup of R1-1 | **Fixed with R1-1** | as R1-1 |
| R1-26 | MAJOR | S-H and S-K had no named test | **Fixed** | P11-3.1 `aRetypeOrUnitChangeUnderANewPackageVersionMintsANewSeries`; P11-4.3 and P11-6.1 `aRetiredPlacementAndAnAbandonedInstanceAreNeverCollectedAndTheirHistoryStaysQueryable`; offboarding cites the tenant-erasure open item (P11-0.1) |
| R1-27 | MAJOR | The `StreamingResponseBody` async timeout was an unmeasured fact | **Fixed** | **MF18**; P11-7.2 Step 0, `ExportAsyncTimeoutIT`, the timeout set explicitly |
| R1-28 | MINOR | `SmDataMigrationMain.run`'s return shape and callers; no Phase 0 delta | **Fixed** | Measured the callers (`SmDataPostgres.java:174`, `TwoSchemaTestDatabase.java:89`, three launcher ITs, `ApplyPersistenceConfigurationIT.java:99,165-167`, `SmDataMigrationMainTest.java:284,374`). `run` keeps its return; `runAll` + `MigrationReport`; the runbook delta. **Also found:** `SketchMirrorTest.java:54-55` and `SmDataBootstrapIT.java:66-67` pin Phase 0 at 7 statements; both are now in P11-2.1's Files |
| R1-29 | MINOR | `engine-edge.package.json` lacks `port`; no name/unit patterns | **Fixed** | Measured `engine-edge.package.json:44`, `run-checks.sh:135`. P11-1.1 Files; name/unit patterns and negatives; every current fixture measured to match |
| R1-30 | MINOR | The S-D expectation fails at a 30 s interval | **Fixed** | Review Focus 2 and the test declare `intervalSeconds: 180`; P11-3.1's rules are reordered so a decrease is decided before the gap rule |
| R1-31 | MINOR | P11-2.2's repository had no configuration or readiness | **Fixed** | `MetricDefinitionPersistenceConfiguration` with its own readiness check and one `@Import`; `readinessRefusesASchemaWithoutV015` |
| R1-32 | MINOR | dup of R1-9 | **Fixed with R1-9** | plus the Results flags |
| R1-33 | MINOR | dup of R1-13 | **Fixed with R1-13** | OQ-17; nothing per-metric in `console.yaml` before the ruling |
| R1-34 | MINOR | The dp-core demo probes survive `demo-launcher`'s deletion | **Fixed** | Measured `SshjDockerTransport.java:217-253`, `SshDockerTransport.java:52-73`. P11-9.1 deletes them, retires T6.7's two facade rules in the same commit, and adds `noProductionClassNamesAFixedMiPort` (renamed `noProductionSourceNamesAFixedMiPort` in round 2, R2-29); Global Constraints reworded |
| R1-35 | MINOR | The dashboard's organization was not bound to its instance's | **Fixed** | Measured `V001__scope.sql:46-53`. P11-2.3 `instance_org_key` + the composite FK; `aDashboardNamingAnotherOrganizationsInstanceIsRefused` |
| R1-36 | MINOR | Rollups recomputed on every 10 s tick | **Fixed** | P11-3.2's own cadence (default 5 min) and a test; P11-8.1 Step 2b measures the cost |
| R1-37 | MINOR | ATTACH's lock on `series` was unverified | **Fixed** | **MF20**; P11-2.1's attach test holds an open `series` insert and records the lock |
| R1-38 | MINOR | `theGatewayHoldsNoDatasource` was re-declared | **Fixed** | Measured `007-…md:1400`, `010-…md:3130-3133`. P11-7.2 extends the existing rule in `ApplyPathBoundaryTest`; `MonitoringBoundaryTest` keeps only the caller rule |
| R1-39 | MINOR | Extracting `evaluate` alone does not give normalized values | **Fixed** | Measured `DesignerProjectorImpl.java:156-178,326-344,437-449,470`. P11-4.1 also extracts `DraftValueResolver`; deriving from `collectionState` is rejected, with the reason; a new test |
| R1-40 | MINOR | dup of R1-15 | **Fixed with R1-15** | as R1-15 |
| R1-41 | MINOR | `ENDPOINT-INVENTORY.md` vs T12.1; Step 1 hardening already exists | **Fixed** | Measured `validate-package.py:188-203`. P11-1.1 Step 1 is now verify-it-still-fires; P11-1.2 Step 5 and the single-owner row sequence the inventory hunk after T12.1 |

**Carried to the second review:** the new OQ-14…OQ-19, the flagged readings in Results, and the new facts
MF18–MF21, which are not yet measured. Each is a place where this revision may have introduced an error of its own.

### Review round 2 (2026-09-30): 31 findings, 6 MAJOR and 25 MINOR

Every finding was re-checked against the tree at `0cbaa28`, against plan 010, or against the rulings file before it
was applied. **None is disputed.** "Fixed" means fixed **in this plan's text**; nothing is built, and each fix is proved
only when its task's tests and mutations run. The row numbers follow the review's order.

| # | Sev | Finding (short) | Disposition | Where / the evidence that settled it |
|---|---|---|---|---|
| R2-1 | MAJOR | `ingest_counter.placement_id NOT NULL` cannot hold a push drop with no single placement | **Fixed (routed to the owner)** | New **OQ-20** (options (a)/(b)/(c), scenarios S-E/S-I; M-Q6 vs M-Q21 recorded); P11-5.1's two tests assert only "dropped, nothing stored" until ruled; V001 comment; rulings index M-Q6/M-Q21; Results flag |
| R2-2 | MINOR | The M-Q13 carve-out was widened to two parents and the wider text handed over as the ruling | **Fixed (routed to the owner)** | New confirm-only **OQ-21**, recommending (2a); V001: `metrics_rollup_1h` unpartitioned, the function spells `metrics_raw_YYYY_MM` only; Global Constraints `I5` case 1; the OR-M-Q13 wording moved to P11-0.1 **Step 4b**, after the answer, naming both identifier cases; P11-2.1 tests `partitionNamesFollowTheClosedGrammarOnly`, `theHourlyTierIsUnpartitioned` |
| R2-3 | MINOR | Rule 3 keyed the inferred reset on a null token, not on the declaration | **Fixed** | `RestartDetection` (derived by the DP from its rows); P11-3.1 rules 1–6 (rule 3: a declared/docker source with no token this cycle is `increment NULL`, `SCRAPE_FAILED`, never inferred); `aDeclaredSignalThatFailsToFetchNeverBecomesAnInferredReset` and its mutation; P11-4.2 token note; Review Focus 2 |
| R2-4 | MINOR | OQ-14 (b) departs from M-Q12's "in plan 011" without saying so | **Fixed** | OQ-14 recommendation cell; rulings index M-Q12; Non-goals row; Results flag |
| R2-5 | MINOR | `solace_event` landed provisionally before OQ-5 and P11-5.0 | **Fixed** | P11-5.0 moved to **MW0** before the sitting; OQ-5/OQ-15/OQ-20 at the main sitting; P11-1.1 lands the branch once, in the ruled shape, or leaves it out (P11-5.1 then adds it); dispatch map MW0/MW5 |
| R2-6 | MINOR | The push reconcile rode the collector's enumeration without a ruling | **Fixed (routed to the owner)** | **OQ-6** widened to both piggy-backed jobs, with scenarios and a (b)/(c) naming them as callers; P11-4.3's loop note; P11-5.1 written for (a) with the (b) alternative; Results flag |
| R2-7 | MINOR | OQ-17 quoted only M-Q21, not M-Q6's "per instance+source" | **Fixed** | OQ-17 status quo and recommendation: (a)/(c) depart from M-Q6, (b) follows it but reshapes M-Q21; Results flag |
| R2-8 | MINOR | M-Q16's supersession pointed at one of three statements of the old rule | **Fixed** | Measured `D10-G2-APPROVAL-2026-09-15.md:240,242-245,264`, `console-integration.md:312`. P11-0.1 Files: pointers beside the role table and at `:312`, no text rewritten |
| R2-9 | MINOR | The SM-D30 reconciliation was missing from the OR-M-Q9 hand-off | **Fixed** | Rulings index M-Q9 row; P11-0.1 hand-off; Results flag |
| R2-10 | MINOR | The discovery's §3.2 defaults were presented as settled | **Fixed (routed to the owner)** | New **OQ-22** (one accept-all question); a rulings-index row; the Spec line; every `D-n` citation relabelled "discovery default"; P11-2.1 keeps the orphan if D-8 is rejected; MW0 claim |
| R2-11 | MINOR | Step 7 narrowed "revisit M-Q19 (c)" to "only if the numbers call for it" | **Fixed** | P11-8.1 Step 7: always put to the owner, with numbers and a recommendation; Results flag |
| R2-12 | MINOR | The one-month load had no measure-first disk check | **Fixed** | P11-8.1 **Step 0b**: one day measured with indexes and WAL, the month projected, a scaled option to the owner if it does not fit; MF17 |
| R2-13 | MINOR | Step 6c did not name its host | **Fixed** | P11-8.1 Step 6c pinned to EC2 (series and segments only if 6b ran locally), host recorded |
| R2-14 | MINOR | An `instanceKey` could name a secret field and land in a broker subscription | **Fixed** | Gate 16 in both validators with two negatives (P11-1.1); schema `instanceKey` block; OQ-5 option text; `PushSubscriptionPlan` re-check and `aSecretTypedInstanceKeyIsNeverResolvedIntoATopic` (P11-5.1); Global Constraints `I6` |
| R2-15 | MINOR | The two new operator commands had no no-echo test | **Fixed** | `partitionMainPrintsNoUrlUserOrPassword` (P11-2.1), `brokerConnectionMainNeverEchoesTheCredential` (P11-5.1), each with a mutation; Global Constraints `I6` |
| R2-16 | MINOR | `getCapabilities` would show install-wide byte totals to every organization | **Fixed** | OQ-16's scope rule; P11-1.2 contract carries `disk.state` only, reasons per organization, `theCapabilitiesSchemaCarriesNoByteFigure`; P11-3.2 public view; `MetricsPartitionMain status` prints the bytes; P11-7.2 `capabilitiesNeverCarryInstallWideByteCounts` |
| R2-17 | MAJOR | The pin tests are touched by three tasks and owned too narrowly by one | **Fixed** | Measured `SketchMirrorTest.java:43-50,54,56,64,81`, `DesignSketch.java:24`, `SmDataBootstrapIT.java:65,141-205,152-157`, `SmDataPostgres.java:63-67,173-174`; no `SketchMirrorTest` in `010-…md`. P11-2.1 Files and Step 0 (all four assertions, the schema-qualified history lookup, the named §3.1 edit); single-owner table (P11-2.1 → P11-2.2 → P11-5.1, sequential merges); MW2 merge order; P11-2.2/P11-5.1 Files; Prerequisite 7 and P11-0.1 Step 4 (plan 010's own drift, for the integrator) |
| R2-18 | MAJOR | P11-1.1 left out the Java side of its contract change | **Fixed** | Measured `PackageJsonReader.java:40-41`, `PackageModel.java:76-101`, `run-checks.sh:141-154`, `validate-package.py:93-99`, the two constructor call sites by grep, `010-…md:2244-2280`. P11-1.1 Files (`SemanticGates`, the count pin, `PackageModel`/`PackageJsonReader` unconditional, both overlap fixtures at the measured path); gates 15/16 numbered with their own prefixes; `VERIFY launcher`; `aDefaultChartFalseAndADeclaredRestartSignalSurviveTheRead`; mutations |
| R2-19 | MAJOR | Beans and `*Properties` had no registering configuration; parallel tasks would share `MetricsConfiguration` | **Fixed** | Measured `PlaneBoundaryTest.java:128-134`, `ControlPlanePersistenceConfiguration.java:34-43`, `JdbcAuditLog.java:14-20` (no production bean). New section [Readiness and configuration, per area](#readiness-and-configuration-per-area): a persistence + service configuration pair per task; `MetricsConfiguration` holds only the shared budget and `metricsExecutor` (P11-3.1), and is in the single-owner table; every task's Files and `@Import` count updated |
| R2-20 | MAJOR | `aDeclareRefusedAfterReserveWritesNoMetricsRow` could not pass, and its mutation could not fire | **Fixed** | Measured `010-…md:2425-2431,2562`. Replaced by `aRefusalInsideReserveLeavesNoMetricsRow` (i) and `afterAnOverQuotaAbandonThePlacementIsRetiredItsPortsAreFreeAndItIsNeverCollected` (ii); the mutation now writes the rows in a second transaction and injects a collision there, so the surviving placement turns (i) red; no DELETE grant |
| R2-21 | MAJOR | Nobody owned `DeclareInstanceRequest` or the record ripple | **Fixed** | Measured `010-…md:2518-2520`, `SchemaMirrorLockTest.java:32,75,80`. P11-1.2 Files (the record, `SchemaMirrorLockTest` unconditional, constructor call sites by grep), test and mutation; P11-4.1 secondary constructors with plan 010's arity, `MetricsDeclarationConstructorTest` forbidding them in `src/main`, Step 0 grep |
| R2-22 | MINOR | The placement-side trigger took no lock | **Fixed** | V015 trigger comment (both triggers take `target_connection FOR UPDATE`; the race written out); `aConcurrentHealthPortAndMetricsPortOnOneTargetCannotBothCommit` in both orders; the placement-side mutation; P11-2.2 Step 0 reads the landed lock order |
| R2-23 | MINOR | The restart token was compared with a per-placement marker | **Fixed** | P11-3.1 rule 4 preamble (per-series previous token; the marker only records and seeds after a boot); V001 `restart_marker` comment; `oneOfTwoCounterUrlsFailingDuringTheRestartCycleStillSeesTheReset` and its mutation |
| R2-24 | MINOR | A non-finite value would be misread as `RUNWAY_DEGRADED` | **Fixed** | `JsonPointerExtractor` and `BrokerSampleDecoder` refuse NaN/±Infinity as `parse_failed`; `IngestService` re-checks; runway matched on SQLSTATE **plus** no constraint name (new **MF23**), classified in `dp.apply.persistence`; `aNonFiniteNumberIsParseFailedNotRunwayDegraded` in P11-3.1, P11-4.2 and P11-5.1 |
| R2-25 | MINOR | Row 55's second verb had no enforcement | **Fixed** | Measured `console-integration.md:397`, `010-…md:1591`. P11-1.2 `x-sm-permission-also: instance.view` and its check; P11-7.2 intersection, `aCallerWithMetricsQueryButNotInstanceViewSeesNoInstances` and its mutation |
| R2-26 | MINOR | Async re-dispatch behaviour unmeasured; the export result had no audit key | **Fixed** | Measured `AuditEntry.java:20,22,62-66`; no `DispatcherType`/`ASYNC` in `console-gateway/src/main/java`. **MF18** widened; `oneExportIsOneAuthorizationOneRateCountAndOneAuditRow`; P11-7.1: `ALLOW` plus `new_status ∈ {completed, failed, cut}`, `theExportResultIsCarriedInNewStatus` |
| R2-27 | MINOR | The 105 M-row run could be cut by its own 30-minute timeouts | **Fixed** | P11-8.1 Step 6b: duration projected from P11-6.2's rows/s (≥ 58 k rows/s needed); both timeouts raised only as a labelled benchmark override, and exceeding 30 minutes recorded as a finding for the owner (M-Q22 (2) vs M-Q18); Results flag |
| R2-28 | MINOR | MF16 was filled after its consumer; P11-3.2 had no Step 0 for MF19 | **Fixed** | MF16 moved to P11-3.1 **Step 0** (sets `maximumPoolSize` if unset); P11-6.2 re-reads it; P11-3.2 **Step 0** fills MF19 and measures as `sm_dp_runtime` |
| R2-29 | MINOR | Fixture packages would not be scanned; the literal-check mechanism was unverified | **Fixed** | Measured `arch-tests/src/test/java/com/solace/sm/*/archfixture`, `PlaneBoundaryTest.java:42,209,264-266`. Full fixture packages in P11-7.2 and the single-owner table, with a mutation; new **MF22**; P11-9.1's guard becomes the source-tree scan `FixedMiPortSourceScanTest` (`noProductionSourceNamesAFixedMiPort`) unless MF22 shows ArchUnit can read constants, proved on a `.txt` fixture |
| R2-30 | MINOR | S-B, S-C and S-J's "SM restarts" half had no disposition or test | **Fixed** | P11-8.1 Step 4 (S-B/S-C: arithmetic from measured bytes/row, labelled, with the reason); `anSmRestartLosesTheBufferAsAGapNeverZeros` (P11-3.1); Results flag |
| R2-31 | MINOR | `unfrozenClosedPartitions` undefined; no `dp-api` active-list read; disk block scope; `CollectorReason` unlisted; the push REST test had no home | **Fixed** | (a) `sm_metrics.partition_freeze_log` (V001, written by `MetricsPartitionMain freeze`), the measure in P11-1.2 and P11-6.1, tests in P11-2.1 and P11-6.1; (b) `MetricsQueryService.activeMetrics` (P11-6.1, which now depends on P11-4.3), used by P11-7.1's default layer; (c) as R2-16; (d) `CollectorReason` owned by P11-3.1 with every ruled reason, P11-5.1 only in the fallback; the push REST half is `PushedMetricsQueryableOverRestIT`, created by whichever of P11-5.1/P11-7.2 merges second (dispatch map MW7) |

**Carried to the next review:** OQ-20, OQ-21 and OQ-22; the widened OQ-6; the per-area configuration table; the
P11-2.1 → P11-2.2 → P11-5.1 pin-file sequence; the new facts MF22 and MF23 and the widened MF18, none measured. Each is
a place where this revision may have introduced an error of its own.

### Review round 3 (2026-10-05, before the owner sitting): 5 MAJOR and 9 MINOR

An independent read-only review (Opus 5.5 high) of the draft at `7f8a6dc` (base `bd802fc`), returned `changes-required`. Every finding
was re-checked against the tree, the local plan 010 branches (`git show`), the evidence file or `DECISIONS.md` before it was applied.
**None is disputed**; two are extended (R3-1 and R3-2 also release P11-1.2 and P11-4.2a, which the reviewer's option (b) did not name, because
the integrator's request was to open the contract work as soon as plan 010's `console.yaml` editors allow). "Fixed" means fixed **in this
plan's text** and in the hand-off file; nothing is built, and no owner question is decided.

| # | Sev | Finding (short) | Disposition | Where / the evidence that settled it |
|---|---|---|---|---|
| R3-1 | MAJOR | No owner question lets any wave after MW0 start before plan 010 S6/S7; the Depends-on row, Prerequisite 1 and the MW1/MW2 rows disagree | **Fixed (routed to the owner)** | New **OQ-28** (a)/(b)/(c); Depends-on row and Prerequisite 1 rewritten; MW1/MW2 "Opens when" cells. `OR-91` is quoted: MW0 only. Measured: S2/S3 pushed at `4267c1e`; `010-…md:388-389` (no `sm_control` number after `V013`) |
| R3-2 | MAJOR | Dispatch waits wrong in both directions (P11-2.1, P11-2.3, P11-7.1, P11-1.1 earlier; P11-2.2, P11-1.2, P11-4.1, P11-4.2 SSH half, P11-6.2 elsewhere) | **Fixed** | New section [Per-task gates](#per-task-gates--which-plan-010-merge-each-task-waits-for-and-why); P11-4.2 split into 4.2a/4.2b; P11-6.2 hangs off P11-3.1; T7.2 row "pattern only"; critical path redrawn (12 → 9 → 7 tasks after W11). Measured: `010-…md:399-424` single-owner table, `git diff bd802fc...p010/*` (no in-flight branch touches `sm_data_phase0.sql`, `SmDataMigrationMain` or a migration but `V007`) |
| R3-3 | MAJOR | OQ-26 and OQ-27 drafted by P11-0.2 are not in the table; `B/D5` (LOCKED) is never cited | **Fixed** | OQ-26 and OQ-27 are rows; `B/D5` row in the rulings index and a Results flag (*LOCKED; path-2 reading pending OQ-26*); the sitting paragraph names OQ-1…OQ-18 and OQ-20…OQ-29 (OR-91's own list is registered text and is not edited). `DECISIONS.md` row `B/D5` read: *"before any systemd/K8s/scheduled/File-Mesh work begins"*, LOCKED |
| R3-4 | MAJOR | P11-5.0's facts did not reach the plan: Step 1's premise, MF13 "UNVERIFIED", OQ-5 "settled as a fact", depth-pinned patterns, case sensitivity, OQ-20's one-route recommendation, OQ-4 | **Fixed (routed to the owner)** | `p011/fm` merged; P11-5.0 reworded and ticked; MF13 rewritten (partly measured); new **MF26**; OQ-4 cites F14; OQ-5 rewritten with option (c′) and the last-level-literal rule; OQ-15, OQ-20 (now conditional on OQ-5, ruled together) cite the facts |
| R3-5 | MAJOR | No real metric exists for path 2 to ingest | **Fixed (routed to the owner)** | New **OQ-29** (numeric fields only / count-of-events extraction / defer the branch), Acceptance #4, MW5's "must not claim", P11-5.1 test `aRealDeclaredSolaceEventMetricIsIngestedEndToEnd`. Measured: all four components of `filemesh-ftp-solace.package.json` declare 0 metrics; F11–F13 |
| R3-6 | MINOR | Plan 010 table out of date against local branches; `DeclareInstanceRequest` shape; `DesignerController`; `ConfigLadderReader`; OQ-23's `V007` | **Fixed** | "In flight" column; T6.2 row; P11-1.2 rebound to `Placement` (`placement.metricsPorts`, beside `healthPort`); renames; OQ-23 note. Measured: `git show p010/merge3:…/DeclareInstanceRequest.java` and `console.yaml:554-565` |
| R3-7 | MINOR | `metricsPorts`: two source ports to one host port; a restart-signal port without an endpoint | **Fixed** | V015 `placement_metrics_host_port_once` + test + mutation; P11-4.1 endpoint rows over the union of ports + test + mutation |
| R3-8 | MINOR | A dashboard for an `OR-90` orphan blocks the runbook's delete (`ON DELETE RESTRICT`) | **Fixed** | P11-2.3 note and test; P11-7.1 precondition; P11-7.2 `findActive` guard + test + mutation (`InstanceInventoryService.findActive` exists, `dp-api`); hand-off §B line for the runbook (plan 010's file) |
| R3-9 | MINOR | `OR-74`'s "direct host and port" clause contradicted by `OR-82` but never flagged | **Fixed** | Rulings index M-Q5/M-Q14 notes; Results flag; hand-off §A7 (an `I3` clarifying-row draft). `DECISIONS.md` `OR-74` read |
| R3-10 | MINOR | OQ-14 cites a stale "NOT executed" header and keys option (a) on the spike | **Fixed** | OQ-14 cites `spike-scope-acl-result.md` (round 4) and its own sentence *"Nothing here declares item 32, 33 or 35 closed"*; (a) keyed on the owner closing the items; the stale header (`console-integration.md:789`) goes to the hand-off |
| R3-11 | MINOR | P11-5.1's broker tests have no `broker.host` gate; a wildcard last level delivers `start` | **Fixed (the second part routed to OQ-5)** | Gate stated with the three existing cites; P11-10.1 reports whether they ran; last level a literal (P11-1.1 grammar + negatives, `PushSubscriptionPlan`, `aStartEventIsNeverSubscribed`) unless the owner rules otherwise in OQ-5 |
| R3-12 | MINOR | DM-review items never reached the text: cap counts placements, per-tier gap threshold, fetch size, gate list | **Fixed** | P11-6.1 cap rule/test and the per-tier gap rule (discovery default D-3); P11-6.2 fetch-size test + mutation; P11-1.1 dated pointer in `dynamic-ui/design.md` §6.4 (gates 14–16; the table stops at 13) |
| R3-13 | MINOR | Mutation proofs thin in P11-7.1, P11-4.3, P11-7.2 | **Fixed** | One mutation per listed guard in each |
| R3-14 | MINOR | MW0 boxes unticked, Results empty, `p011/fm` not merged, OQ rows out of order | **Fixed** | Ticks and status lines; MW0 Results table; merge `c9be575`; the OQ table is in numeric order |

**Carried to the next review:** OQ-26…OQ-29, MF26, the per-task gate table and its cost column, the P11-4.2 split, the narrowed
`topicPattern` grammar, and the `placement.metricsPorts` rebinding. Each is a place where this revision may have introduced an
error of its own, and the plan 010 facts in it are read from branches that are not yet pushed.
