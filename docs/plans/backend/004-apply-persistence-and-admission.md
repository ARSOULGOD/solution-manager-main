# Backend Plan 004 - Apply Persistence and Admission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the PostgreSQL persistence foundation and the transactional admission path that
`BE-D12`/`BE-D13` design — the exact two-phase DDL/DCL as Flyway migrations behind a real PG 14 test
harness, a narrow tenant-scoped data-access boundary, the serialized `apply_scope` admission
transaction with its fences and watermarks, the immutable step-intent / retry-attempt identity
substrate — and prove every database-enforceable obligation `(a)`–`(u)` by repository tests, without
touching any gate.

**Architecture:** Everything lands in `dp-core` (design §7: the apply engine, its tables and the DP's
audit record live in `sm_data`). Three new package roots under `com.solace.sm.dp.apply`: `model`
(framework-free records and enums mirroring the row CHECKs), `persistence` (the **only** package that
may import JDBC/Flyway/Hikari — ArchUnit-enforced — holding the two-datasource configuration and four
repositories, every statement bind-parameterised and tenant-keyed), and `engine` (`AdmissionService`,
`OutcomeService`, `StepIntentService`, `AttemptService` — framework-neutral services over
`TransactionTemplate`). The design's §3.1 SQL block is split into six migrations **without changing a
statement**, and a surefire lock test proves the files and the design block carry the same
comment-stripped statement multiset (with per-file counts, so a statement cannot move between files
unseen), so code and design cannot drift silently. Zero contract files change. No `RuntimeAdapter`
implementation, no REST, no auth, no mesh.

**Tech Stack:** Java 17 (no preview features), Spring Boot 3.5.16 parent BOM — `spring-boot-starter-jdbc`
(HikariCP 6.3.3, spring-jdbc 6.2.19), `org.postgresql:postgresql` 42.7.11 (runtime scope),
Flyway 11.7.2 (`flyway-core` + `flyway-database-postgresql`), Testcontainers 1.21.4 (`postgresql`,
`junit-jupiter`) against `postgres:14` (measured: `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2)`),
JUnit 5, ArchUnit 1.5.0. **All versions are BOM-resolved — no `<version>` is written for any of
them; the numbers above are what `mvn -pl dp-core dependency:tree` resolved on 2026-09-07.**

**Spec:** [`../../design/backend/eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md)
at `f84030e` — §2.2 (rules 0–4), §3 in full (`BE-D12`/`BE-D13`), §6 (gate map), §8 rows 4/9/13–14, §9 notice 3;
[`../../design/backend/decisions.md`](../../design/backend/decisions.md) (`BE-D12`, `BE-D13` rows and
the R1–R8 changelog); [`../../design/backend/evidence/ddl-execution-pg14.md`](../../design/backend/evidence/ddl-execution-pg14.md)
(the design baseline this plan turns into repository tests — **a baseline, not a substitute**);
[`../../design/common/contracts/backend-consumption.md`](../../design/common/contracts/backend-consumption.md)
§5–§7 (module consumption rules, check ownership); `DECISIONS.md` `SM-D18`'s data-access direction
(*parameterized SQL on a connection pool — HikariCP + `NamedParameterJdbcTemplate` — no heavyweight
ORM, bind parameters everywhere, one central data-access module enforcing tenant scope*). Executors
read all of these before starting.

**Provenance (lesson L15):** every code block in this plan was compiled and run green in a throwaway
prototype worktree on 2026-09-07/08 (`postgres:14` via Testcontainers, `mvn -f backend/pom.xml clean
verify` BUILD SUCCESS) before being written here. Where a step states a constraint name, an error
message, a version, a bean-ordering fact or a PostgreSQL behaviour, it was measured in that run, not
recalled. The prototype worktree was kept only for this plan's own adversarial review and then deleted; the
plan is what survives, so an executor must still run every red/green step — the provenance lowers
the odds of a plan defect, it does not replace verification.

## Why this slice, and what was rejected

The EDA walking-skeleton design closed open item 1 (the `dp-remote` apply protocol) with a **design**
whose DDL now has execution evidence on PG 14 (168 legs green against `afee944`'s extracted sketch).
Nothing in the repository executes it yet: there is no datasource, no migration tool, no JDBC
dependency and no table anywhere in the reactor (measured 2026-09-07 — the only
`PlatformTransactionManager` in the tree is a test fake). Design §6's gate-map row for "the
walking-skeleton plan" names what is buildable **pre-G2**: *"`BE-D12`/`BE-D13` end to end (protocol,
adapters, persistence, events)"*. This plan takes the **persistence and admission** half of that
sentence and stops:

1. **The persistence foundation is the one part of the skeleton with no gate on it and a complete,
   measured spec.** Obligations `(a)`–`(u)` are written as migration-test requirements; the evidence
   report proves the DDL; turning them into repository tests is mechanical, review-gated work — and
   it is the work L19 says must exist before "settled" is claimed of the design's guarantees in code.
2. **Admission is the contract everything later composes against.** The `RuntimeAdapter` engine
   (Plan 005), the agent-report mapping, the REST apply endpoint (G2-gated) and the wire emission
   (`BE-D4`-amendment-gated for its terminal notice) all call `admit`, `land`, `ensureIntent` and
   `open`. Proving those four operations against a real PG 14 first means later plans build on
   measured behaviour, not on the design's prose.
3. **Plan 001 left an obligation this plan is the first that can honour:** *"wire a migration tool
   into the module that will own the schema (`dp-core`)"*. Flyway lands here, in `dp-core`.

**Rejected alternatives** (recorded so the choice is reviewable): the `DockerAdapter` + apply engine
state machine — needs a container runtime in the test harness and the six-operation adapter seam,
doubling the surface and coupling this plan's proofs to Docker-API behaviour (Plan 005); adopting the
orphan `backend/src/main/resources/db/migration/V001__telemetry_schema.sql` into the Flyway series —
it is the metrics workstream's (open item 3, `SM-D26`), verified on PG 16 not 14, hardcodes 2026
partitions and belongs to a different ratification blocker (see Out of scope); an embedded/H2 test
database — the obligations under test are ROLE, GRANT, partial-index and PL/pgSQL trigger behaviour,
none of which an in-memory shim reproduces; JPA/Hibernate — `SM-D18` already records the direction as
parameterized SQL on a pool with no heavyweight ORM.

## Prerequisites

**Open items that must close before this plan executes: none.** Every gate below is checked against
every task and none blocks persistence-and-admission work. The plan-review checklist's remaining
items, answered rather than skipped: **migrations against a large partitioned table** — N/A, none of
the five `sm_data` tables is partitioned and nothing is deployed (`SM-D26`'s partitioned tiers are the
metrics workstream's); **rollback** — N/A for the same reason: no database carries these tables yet,
so a defect in a migration is fixed by amending the design's SQL block and the migration together
(Global Constraints) rather than by a down-migration, and that posture must be revisited by the first
plan that ships to a live database; **execution route** — seven tasks, so `CLAUDE.md` §3 requires
the developer to be **asked** which session mode to use before execution starts — this plan does not
choose for them.

## Gate map — what this plan may touch and what stays blocked

| Gate / decision | Status | Effect on this plan |
|---|---|---|
| Runbook: the deploy-time migration step | runbook owners | **New prerequisite delivered, publication stays theirs:** Phase 1 runs as `SmDataMigrationMain` with the `sm_dp_migrator` credential before the application starts; the application never receives that credential (Codex finding 1). |
| G2: `D10` amendment (open item 24) | with the architect group | **No effect, enforced:** no endpoint, no session, no ticket, no principal, no `authorizationService`, no audit-log table. `requested_by` is a `String` principal id passed by the caller of `admit` — this plan has no caller but its tests. Every task's file list is auth-free. |
| Open items 32/33 (`BE-D5` ACL shapes) | with the architect group | **No effect:** nothing here touches the broker, the mesh ACL, or the install runbook's ACL section. The install runbook's **database** prerequisites (Phase 0 by the bootstrap superuser; two credentials, two processes) are delivered as a `README` section and the checked-in Phase 0 script — runbook *publication* stays with the runbook owners. |
| `BE-D4` amendment (4 Known gaps + §8 rows 10–13) | with the architect group | **No effect:** zero contract files change; no generated types; no event is emitted by any code in this plan. §3.5's execution-step ↔ wire mapping is Plan 005's, when an engine exists to emit. |
| Open item 1 (apply protocol) / `BE-D12`–`BE-D13` unratified (`I4`) | PROPOSED | **Same posture as plans 001–003:** gate-independent seams built to already-written design text, no architecture invented. The plan-review checklist's *"none of them is a PROPOSED decision that could still change"* is **not satisfiable for any backend plan today** (every `BE-D*` is PROPOSED) and is flagged, not hidden: what limits the exposure is that nothing is deployed, so a design amendment before ratification is a migration edit plus the mirror test, not a data migration. |
| `SM-D26` PG 14 floor | PROPOSED | Honoured literally: the harness runs `postgres:14`; every PG behaviour this plan relies on (NULLs-distinct indexes, grantor-only `REVOKE`, `FOR UPDATE` needing UPDATE on ≥ 1 column) was measured on 14.24. |
| Plan 003 watch item routed to "the first persistence/database plan" | routed here | **Delivered (Task 3):** `SpringTransactionBoundary`'s degradation paths run against a real `DataSourceTransactionManager` — and the measured behaviour corrects the class's javadoc. |

**Nothing in this plan waits on a gate. Nothing in this plan closes a gate.** Ratification of
`BE-D12`/`BE-D13` remains the architect group's; the `RuntimeAdapter` engine, the agent-report
mapping, wire emission and every endpoint remain later plans'.

## Scope and non-goals — stated exactly

**In scope (the deliverable):**

- `dp-core` gains the JDBC/Flyway/Testcontainers dependencies (BOM-managed), the Phase 0 bootstrap
  script and six Flyway migrations carrying design §3.1's SQL **extracted verbatim** (mirrored as a
  comment-stripped statement multiset with per-file counts — the lock test enforces statements and
  their placement, not bytes), and a PG 14 Testcontainers harness that executes Phase 0 as the bootstrap
  superuser and Phase 1 as `sm_dp_migrator` with the `SET ROLE sm_dp_owner` `initSql`.
- `SmDataMigrationMain`: Phase 1 as a **separate one-shot process** holding the `sm_dp_migrator`
  credential (three environment variables, one Flyway run, an exit code) — the application process
  never receives it. `ApplyPersistenceConfiguration`: the runtime pool as `sm_dp_runtime`, a startup
  **schema readiness check** (refuses to serve an unmigrated schema), `DataSourceTransactionManager`,
  `NamedParameterJdbcTemplate`, `TransactionTemplate`, repository beans — **not auto-configured**,
  imported deliberately like `MeshBridgeConfiguration`.
- The framework-free model (`TenantId`, `InstanceId`, `PlacementId` with the nil sentinel, `ApplyId`
  as UUIDv7, `ScopeKey`, `Operation`, `Strategy`, `RequestState` with `validFor(Operation)`, `Step`,
  `ReplicaRef`, `ApplyCommand`, row records) whose validation mirrors the CHECKs — and the **typed
  evidence seam** (`ErrorClass`, `RuntimeRef`, `PreBaseline`, `ReplicaObservation`,
  `ResolutionEvidence`) — **shape controls**: identifier-shaped values only (no whitespace, quote, `=`, backslash or newline) and, for `RuntimeRef`, a **closed key enum** of design §2.3's runtime identifiers, so an environment line, a `Bearer …` header, padded base64, PEM, a JSON blob, a stack trace or an exception message is unrepresentable before any SQL; a **bare identifier-shaped token is not excluded by shape** (a compact JWT fits the alphabet — asserted accepted in `EvidenceTypesTest`), so the I6 *content* control on these columns is each adapter's sentinel-tested builder — Plan 005's named obligation (`I6`); serialized to `jsonb` only inside the persistence package (`Evidence`).
- Four repositories (`ApplyScopeRepository`, `ApplyRequestRepository`, `StepIntentRepository`,
  `AttemptRepository`) — every method takes `TenantId`, every statement binds it (`I5`); the
  `PgConstraintViolation` classifier that names the constraint behind every `23xxx` refusal.
- Four services: `AdmissionService` (the §3.3 admission transaction — scope-row lock, cross-scope
  blocking, monotonicity against maintained watermarks, elder-`STAGED` supersession, fence-race
  classification), `OutcomeService` (`REQUESTED → RUNNING → terminal` **under the same scope-row
  lock**, watermark raise in the landing transaction, `state_fits_operation` mirrored in Java,
  `INDETERMINATE_RESOLVED` with typed evidence), `StepIntentService` (§2.2 rule 2's
  SELECT-then-INSERT with 23505 classified by constraint name; `(q)` engine-enforced),
  `AttemptService` (rule 0 write-before-act; `(m)` engine-enforced cap).
- Two new ArchUnit gates with planted proofs: JDBC confined to `..dp.apply.persistence..`; the model
  package framework-free.
- Repository tests for every database-enforceable obligation, each refusal asserted by SQLSTATE
  **and** constraint/index/trigger **name** beside a positive control; the live two-session
  serialization proof; the coverage matrix.

**Non-goals (explicit — a task that needs one of these is a plan defect, stop and report):**

- **No `RuntimeAdapter` implementation** (Docker, systemd, Kubernetes) and no apply engine state
  machine driving adapters. The `RuntimeAdapter` **interface** itself is also not declared here: with
  no implementation and no engine to call it, an interface in this plan would be an untested
  declaration (L7). Obligation **`(r)`** (stop-during-apply interaction) therefore has **no home in
  this plan** and is stated as such in the coverage matrix — it needs an adapter and an engine.
- **No agent RPC, no agent-report mapping** (§2.4/§3.5's `host-local` half).
- **No REST controllers, no authentication, no `authorizationService`, no principal model, no audit
  log, no session, no ticket, no browser mesh, no `LiveStateClient`** — G2-gated or `BE-D4`-gated.
- **No event emission.** No code here calls `EventPublisher`; §3.5's mapping is Plan 005's.
- **No generated types, no contract changes, no `contracts/` edits.**
- **No `sm_control` schema, tables or roles.** `I7`: this plan is data-plane only.
- **No pruning job, no archival, no retention scheduler** (§3.3's retention *policy* is design; the
  plan delivers the two primitives the job will use — `AttemptRepository.deleteForRequest` and
  `ApplyRequestRepository.foldAttemptSummary` — and proves the trigger's sanctioned write, nothing
  more).
- **No adoption of the orphan telemetry migration** (`backend/src/main/resources/db/migration/V001__telemetry_schema.sql`
  and `backend/src/test/sql/V001_constraints_test.sql`). They are outside every Maven module, so
  neither Flyway nor `mvn verify` sees them; their disposition (relocate into a metrics module's
  Flyway series with a **different version number than this plan's `V001`–`V006`**, or delete) is
  the metrics plan's, recorded in Task 7's documentation sweep so plan 001's still-open obligation
  gains a named owner instead of a third silent carry-forward.
- **No `placement` referential guard** for `apply_request.placement_id` (design §8 row 13 — the
  placements table is the telemetry schema's).
- **No metrics, no Micrometer** (Hikari's own metrics stay unwired).
- **No CI.** As before: `mvn -f backend/pom.xml clean verify` and `./contracts/run-checks.sh --strict`
  by hand.

## Global Constraints

- **Zero changes under `contracts/`.** `./contracts/run-checks.sh --strict` must pass before and after
  every task for the trivial reason that nothing there moved.
- **Zero changes to the design's SQL block** (`eda-walking-skeleton.md` lines 228–552 at `d87c2e7`,
  Phase 0 md5 `768e195b7c17861d97e402c5318be51b`, Phase 1 md5 `2f36077ce81bb77ae37d48315092fa7e`).
  The migrations are **extracted from** it; if executing them reveals a defect in the design's SQL,
  **stop and report** — the design is amended first (with its changelog row and a fresh evidence
  run), then the migration, never the migration alone. `SketchMirrorTest` makes the alternative a red
  build.
- **Every SQL statement issued through Spring binds its parameters** (`NamedParameterJdbcTemplate` +
  `MapSqlParameterSource`; jsonb bound as `String` under `CAST(:x AS jsonb)`). No `JdbcTemplate` bean
  is published. String-concatenated SQL anywhere in `..dp.apply..` is a defect (`I5`).
- **Every statement a repository issues carries `tenant_id = :t`**, and every method takes the
  `TenantId` explicitly or as part of the row it writes (`ApplyRequestRepository.insert(ApplyRequestRow)`
  binds `row.scope().tenant()`). Tenant scope never comes from anywhere else (`I5`). **`TenantIsolationIT`
  (Task 6) is the failing guard** — measured: without it, dropping the predicate from nine statements
  left the whole suite green, because every fixture id is a globally unique UUID.
- **JDBC/Flyway/Hikari/PostgreSQL types are confined to `com.solace.sm.dp.apply.persistence..`**
  (ArchUnit `jdbcIsConfinedToApplyPersistence`, Task 3). `model` imports nothing from Spring or
  `java.sql`; `engine` imports `spring-tx`'s `TransactionTemplate` and nothing JDBC.
- **`I6`:** no rendered configuration, no secret value, in any column, log line, exception message or
  test fixture; test passwords are literals inside the test harness only; `SmDataProperties` redacts.
  `apply_attempt.error_class`, `runtime_ref`, `pre_baseline` and `apply_request.resolution_observed`
  are written only through the typed seam (`ErrorClass`, `RuntimeRef`, `PreBaseline`,
  `ResolutionEvidence`) — shape controls, not a secret barrier: identifier-shaped values and a closed `RuntimeRef.Key` set exclude environment lines, `Bearer` headers, padded base64, PEM, JSON blobs, stack traces and exception text; a bare identifier-shaped token is measured **accepted**, so the content control is Plan 005's per-adapter sentinel test — a named obligation, not a deferral. `EvidenceTypesTest` refuses the secret shapes and asserts the acceptance. **JDBC URLs are locations:** both `SmDataProperties.Credentials` and the migration step admit only `JdbcUrls`' grammar — scheme, `host[:port]/database`, nine allowlisted parameters — because Flyway and Hikari log the URL, and the migration step's failure line carries classifiers only — measured with sentinels in URL, username and password. **No v1 path writes free text.** `resolution_note` is present in the DDL but **denied to `sm_dp_runtime` for both INSERT and UPDATE** (both `apply_request` grants are column-scoped — the database is the guard), and the `Resolution` type has no note component (the mirror) — Codex's fourth pass named it the one column an operator could paste a token into during an incident, persisted and then printed by JDBC's TRACE bind-value line; a comments feature needs its own sensitive-content/retention/access/logging design first, so the column stays NULL (`OutcomeTransitionsIT` asserts it, `EvidenceTypesTest` pins the record's components). **Every jsonb bind goes through `Sql.redacted`** — a `SqlValue` whose `toString` is a length — so Spring's `StatementCreatorUtils` TRACE line, which prints every plain bind value (measured), shows `[redacted text, N chars]` for evidence documents; structural, not a logger level. `config_hash`,
  `observed_hash` and `idempotency_key` are engine-minted values (hashes and keys), not caller text.
- **Lock ordering, everywhere:** any transaction that writes `apply_request` or a watermark takes
  `lockScope` first — `apply_scope` → `apply_request` → watermark tables, always. Admission and all
  three landing walks do; a future writer that skips the scope row re-opens the serialization gap
  Codex finding 3 found, and one that takes the rows in another order can deadlock.
- **`I7`:** nothing in this plan imports `com.solace.sm.cp..`; no `sm_control` object is named.
- **Phase 0 is executed byte-for-byte as shipped** — by the harness as the container's bootstrap
  superuser, by the runbook as the cluster's. Test passwords are assigned by a **separate**
  `ALTER ROLE … PASSWORD` step after Phase 0, because §3.1 deliberately ships no LOGIN credentials.
- **Refusal tests assert the constraint/index/trigger NAME** (or SQLSTATE `42501`/`P0001` plus the
  message) per statement, never "an exception was thrown" — the design's test-harness rule, measured
  necessary twice (a `42703` was read as a `P0001` for two review rounds).
- **Every refusal test sits beside a positive control**, and every runtime-privilege refusal beside
  the same statement succeeding as the owner — otherwise a broken table reads as a working grant.
- **Engine services run in a transaction of their own (`PROPAGATION_REQUIRES_NEW`), never a caller's** — which also means the engine borrows a **second pooled connection** while the caller's is suspended; a caller holding its own transaction across `open`/`close`/`land` costs two connections per in-flight step, so the runtime pool (Hikari default `maximumPoolSize = 10`, unchanged here) must be sized by the plan that sets apply concurrency (Plan 005).
  Measured: under the default `REQUIRED`, an attempt row "opened" inside a caller's transaction rolled
  back with it after `open()` had returned — voiding §2.2 rule 0 — and a lost race's `setRollbackOnly`
  would surface as `UnexpectedRollbackException` at the caller's commit. `EngineTransactions` (Task 4)
  is the one place that says so; `EngineTransactionBoundaryIT` (Task 5) keeps it true.
- **ITs run under `mvn -f backend/pom.xml -pl dp-core -am verify` with no extra property** and must
  appear in `dp-core/target/failsafe-reports/failsafe-summary.xml` as **completed**, not skipped, on a
  Docker host. A test class not named `*IT` (failsafe) or `*Test` (surefire) did not run (lesson R5).
- **Environment (measured on the development host):** every Maven run that starts containers needs
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` when `~/.testcontainers.properties`
  points `docker.host` at Colima while `/var/run/docker.sock` is Docker Desktop's — otherwise Ryuk
  fails to start (`error while creating mount source path '…/.colima/default/docker.sock'`) and,
  because Testcontainers caches the failure, **only the first IT to touch it errors** while the rest
  pass. Task 1 records the exact command; do not bake a host path into any POM.
- `rg` is a broken shim — use `grep -rn`; quote glob patterns in zsh (`--include="*.java"`).
- **`mvn -f backend/pom.xml clean verify` is the completion gate** (use `clean` — a stale `target/`
  has false-passed a red-first step in this repo before). Baseline measured 2026-09-07 at `31778ef`:
  surefire **187** (mesh-events 127 / dp-core 41 / arch-tests 19), failsafe **2 run, 2 skipped**
  (the two broker ITs), BUILD SUCCESS in ~7 s. Counts only grow; each task states its expected delta
  and the executor reconciles against the summary files, not the console.
- Commits are per the task's commit steps, scoped to the listed files, ending with
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Do not push. Do not merge.

## Code Execution Contract for Sub-Agents

Sub-agents implementing this plan must run on **Claude Opus 5** — every implementation and every review
sub-agent. If Opus 5 is unavailable, stop and report before proceeding. Model downgrades require
Parikshit's explicit approval.

Every sub-agent is bound by these rules:

- Implement exactly one task from this plan. Do not begin later tasks.
- Before editing, read `CLAUDE.md`, `docs/design/backend/eda-walking-skeleton.md` §2.2 and §3,
  `docs/design/backend/evidence/ddl-execution-pg14.md`, `docs/design/backend/decisions.md`
  (`BE-D12`/`BE-D13` rows), this plan, and the files listed in your task.
- Use the package names, class names, and signatures specified here. Do not invent alternates.
- Write the failing test first where the task specifies one, run it, and confirm the failure
  **reason**, not just the colour. Negative tests must fail for the intended reason — the
  constraint name in the `PSQLException`, not merely a `DataAccessException`.
- Where a step says "measured", re-measure it: the plan's figure is what the prototype saw on
  2026-09-07 and your run must agree. If the verified fact contradicts this plan's snippet, stop and
  report before writing code.
- If a check is intentionally skipped, report it as skipped with its condition.
- **Commit the green state before running any prove-it-fires mutation**, and revert the mutation with
  `git checkout -- <path>` only against that commit. Reverting against uncommitted work discards it —
  this plan's own prototype lost a fix wave to exactly that, twice.
- Commit only the task's files, with an imperative commit subject, ending with
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Report changed files, commands run (with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` where used),
  failures observed before the fix, deviations, and the commit hash.

### Dispatch Template

> You are implementing Task N from `docs/plans/backend/004-apply-persistence-and-admission.md`.
> Use Claude Opus 5.
> Implement only Task N. Do not start later tasks.
> Follow the Code Execution Contract in the plan.
> Read `CLAUDE.md`, `docs/design/backend/eda-walking-skeleton.md` §2.2 and §3,
> `docs/design/backend/evidence/ddl-execution-pg14.md`, `docs/design/backend/decisions.md`, this
> plan, and the files listed under Task N.
> Preserve gates: no endpoint, auth, principal, audit log, session, ticket, mesh, event emission,
> `RuntimeAdapter`, agent RPC, `sm_control`, or `contracts/` change. The design's SQL block is
> read-only — extract, never edit.
> Run the task's verification commands with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
> and report real output including the failsafe summary.
> Commit only Task N files and report the commit hash.

---

## Obligation map — where each `(a)`–`(u)` is proven, and by whom

The design separates what the **database** proves from what the **engine** must prove (§3.1's
residual table). This plan keeps that split visible: a row marked *DB* is asserted by constraint /
index / trigger / privilege **name**; a row marked *engine* is asserted by a service refusing in Java
**and** by a raw repository write that the database **accepts** (proving there is no DB artifact —
the honest half). *N/A* rows are stated, not skipped.

| Obligation | Design clause | Enforcer | Proven in | Test class (Task) |
|---|---|---|---|---|
| (a) stop admitted beside RUNNING / APPLY_INDETERMINATE apply | §3.3 ¶1, stop exemption | DB (fence predicates) + engine (exemption) | `Admitted` from `AdmissionService`; raw insert passes both fences | `AdmissionFencesIT` (4) |
| (b) second apply/restart refused in both states | §3.3 fence 1 | DB `apply_inflight_fence` (belt, raw insert) + engine `SCOPE_BUSY` (braces, through `admit`) | both layers. **Not proven:** the engine's `FENCE_RACE_LOST` branch — measured unreachable through `admit` under the row lock; it stays as a defensive belt and is stated untested | `AdmissionFencesIT` (4) |
| (c) second in-flight stop refused | §3.3 fence 2 | DB `stop_inflight_fence` (belt) + engine `STOP_IN_FLIGHT` (braces) | both layers; same `FENCE_RACE_LOST` caveat | `AdmissionFencesIT` (4) |
| (d) cross-scope lock, two live sessions, both directions; placement stop during instance-wide apply; admission during an uncommitted first AND later landing | §3.3 ¶2, §2.2 rule 1 (landing holds the lock) | engine over `apply_scope` `FOR UPDATE`, taken by admission AND by every landing (the FK's `KEY SHARE` is incidental — measured absent on the placement upsert's `DO UPDATE` branch) | measured wait on **`lockScope`'s `SELECT … FOR UPDATE`** (asserted by statement text, scope row pre-committed — the first draft measured the wrong lock); `FOR UPDATE` removal is a measured 5-of-7 red; removing `lockScope` from `land` is a measured red | `AdmissionSerializationIT` (5 of its 7 assert a lock wait) |
| (e) same (instance, placement) under different tenants does not collide | §3.3 tenant-scoped fences | DB key shape + engine | admitted / refused pair | `AdmissionFencesIT` (4) |
| (f) `hash_on_config_steps` both directions | §3.1 | DB `23514 hash_on_config_steps`, `placement_scoped_steps` | raw inserts | `StepIntentIT` (5) |
| (g) intent identity: composite UNIQUE, key UNIQUE, read-compare | §2.2 rule 2 | DB two UNIQUEs + engine compare | both layers + arbiter race | `StepIntentIT` (5) |
| (h) `state_known`, `state_fits_operation`, `apply_has_generation`, `lifecycle_has_strategy` (+ every other named CHECK) | §3.1 | DB `23514 <name>` | raw inserts | `SchemaConstraintsIT` (6) |
| (i) cross-request intent reference refused | §3.1 triple FK | DB `23503 apply_attempt_tenant_id_apply_id_intent_id_fkey` | raw insert | `AttemptIT` (5) |
| (j) `resolution_is_evidence` five refusals + `resolution_only_when_resolved` | §3.1 | DB `23514` | raw inserts + positive via `resolveIndeterminate` | `SchemaConstraintsIT` (6) |
| (k) runtime UPDATE on `apply_step_intent` refused; owner succeeds | §3.1 DCL | DB `42501` | privilege pair | `PrivilegeTopologyIT` (2) |
| (l) `SET ROLE sm_dp_owner` refused; `pg_has_role` false | Phase 0 | DB `42501` | privilege | `SmDataBootstrapIT` (1) |
| (m) `attempt_no_positive` (DB) + configured cap (engine) | §3.1 / §2.2 rule 4 | DB `23514` **and** engine `RetryCapExceeded` | both | `AttemptIT` (5) |
| (n) monotonicity, four cross-scope directions, incl. after `INDETERMINATE_RESOLVED`/applied | §2.2 rule 1 | **engine** over maintained watermarks (no DB artifact for the comparison) | `GENERATION_NOT_ABOVE_WATERMARK` ×4, admitted ×4, watermark values asserted | `MonotonicityIT` (4) |
| (o) column-scoped `apply_request` grants (INSERT and UPDATE; `resolution_note` in neither); terminal guard; legal walk; `state_fits_operation` mirrored in Java | §3.1 | DB `42501` / `P0001` / `23514` + engine `validFor` | privilege (identity columns; the free-text column on both doors, with owner controls) + trigger + positive + wrong-operation terminals refused before SQL | `PrivilegeTopologyIT` (4), `OutcomeTransitionsIT` (4), `RequestStateTest` (3) |
| (p) `staged_fence`; sanctioned `STAGED → SUPERSEDED`; mark-then-insert order | §3.1/§3.3 | DB `staged_fence` + trigger + engine order | both layers | `AdmissionFencesIT`, `OutcomeTransitionsIT` (4) |
| (q) intents only for non-terminal requests | §3.1 residual table | **engine** `TerminalRequest` — raw insert **accepted** by the DB (measured) | both halves | `StepIntentIT` (5) |
| (r) stop during a mid-flight apply halts it with evidence, never a torn write | §3.3 exemption ¶ | engine + adapter | **N/A in this plan** — needs a `RuntimeAdapter` and the engine's step loop (Plan 005). Stated in the coverage matrix as not proven here. | — |
| (s) exact two-phase script green on fresh PG 14 under the stated credentials; `has_schema_privilege(...)=false` asserted; negative control | §3.1 | DB + harness | Phase 0 as bootstrap superuser (zero `SQLWarning`s), Flyway 6 migrations, negative control in a second database | `SmDataBootstrapIT` (1, 2) |
| (t) per-table `*_only_rises` triggers; identity-column and DELETE refusals; positive controls | §3.1 | DB `P0001` + `42501` | trigger + privilege + positives | `WatermarkTriggerIT`, `PrivilegeTopologyIT` (2) |
| (u) `attempt_summary`-only fold on a terminal row; fold+other column refused; `request_has_scope` | §3.1 | DB trigger + FK `23503 request_has_scope` | raw statements | `OutcomeTransitionsIT` (4), `AttemptIT` (5) |
| §3.1 ↔ migrations token equality | L19 / definition of done ("do not leave code and design disagreeing") | lock test | multiset equality + prove-it-fires mutation | `SketchMirrorTest` (2) |
| Plan 003 watch item: `SpringTransactionBoundary` degradation paths | plan 003 Results | real `DataSourceTransactionManager` | after-commit visibility, rollback, `NOT_SUPPORTED`, `SYNCHRONIZATION_NEVER` | `SpringTransactionBoundaryDataSourceIT` (3) |
| `I5` — every repository statement carries `tenant_id` | `CLAUDE.md` I5, design §7 | statement predicates | tenant B addressing tenant A's rows by primary key sees and changes nothing; dropping any predicate is a measured red | `TenantIsolationIT` (6) |
| `I6` — evidence shape | design §3.1 residual table (amended `f84030e`, `58a2b9f`) | typed seam grammar + closed `RuntimeRef.Key` (shape controls) | secret-shaped values refused in constructors, no row written; a bare compact token measured accepted (content control = Plan 005's adapters); `resolution_observed` keys pinned to the CHECK's | `EvidenceTypesTest`, `EvidenceJsonTest` (3), `AttemptIT` (5) |
| `I6` — URLs are locations / failure output | design §9 notice 3 (amended `58a2b9f`, tightened this round) | `JdbcUrls` location grammar + allowlist, classifier-only failure line | sentinels in URL/username/password appear in no stream and no log; a URL carrying anything outside the grammar — a credential, an `options=` payload, an unknown parameter, a value outside its grammar — is refused before connecting, by position, never echoed | `JdbcUrlsTest`, `SmDataMigrationMainTest`, `SmDataPropertiesTest` (1) |
| Credential separation | design §9 notice 3 (amended `f84030e`) | `SmDataMigrationMain` + readiness check | the application's properties bind `runtime` only; an unmigrated schema stops the context; the migration step is idempotent | `SmDataMigrationMainTest` (1), `ApplyPersistenceConfigurationIT` (5) |
| §2.2 rule 0 survives a caller's transaction | design §2.2 rule 0 | `EngineTransactions.requiresNew` | attempt row survives the caller's rollback; admission commits on its own; refusal inside a caller's transaction is a value | `EngineTransactionBoundaryIT` (5) |

---
### Task 1: PG-14 test infrastructure, the exact two-phase SQL, and the bootstrap proof (`dp-core`)

**Sequence note.** The requested order separated "test harness" from "migration split". The harness's
bootstrap sequence is Phase 0 → test passwords → Flyway, and a harness that stopped before Flyway
would be a different harness from the one that was executed; so this task lands the harness **and**
the six migration files together, and Task 2 adds the mirror lock and the DCL/trigger proofs on top.
Both tasks remain independently reviewable deliverables.

**Files:**
- Modify: `backend/dp-core/pom.xml` (six dependencies, after the `sol-jcsmp` entry)
- Create: `backend/dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql`
- Create: `backend/dp-core/src/main/resources/db/migration/sm_data/V001__sm_data_defaults.sql` … `V006__terminal_guard.sql`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/SmDataMigrator.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/SmDataMigrationMain.java` (the one-shot migration process)
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/JdbcUrls.java` (a location grammar — scheme, `host[:port]/database`, nine allowlisted parameters with value grammars; everything else refused without echoing the URL, a value or an unknown parameter's name)
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/SmDataProperties.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/ApplyPersistenceConfiguration.java`
- Create (test): `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/{SqlStatements,SqlScript,DesignSketch,SmDataPostgres,Refusals,LogCapture}.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/SmDataBootstrapIT.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/{SmDataMigrationMainTest,SmDataPropertiesTest,JdbcUrlsTest}.java` (surefire)

**Interfaces:**
- Consumes: nothing new — `SpringTransactionBoundary` is untouched; `MeshBridgeConfiguration` is the wiring precedent.
- Produces (later tasks rely on these exact names): `SmDataMigrator.{INIT_SQL, SCHEMA, LOCATION, PHASE0_RESOURCE, migrate(DataSource)}`;
  `SmDataProperties(Credentials runtime, Apply apply)` with `Credentials(url, username, password)` and `Apply(Integer maxAttempts)` — **the application binds only the runtime credential**;
  `SmDataMigrationMain.{main(String[]), run(url, username, password) → MigrateResult, exitCode(Map, PrintStream out, PrintStream err) (package-private), dataSource(url, username, password) (package-private), NoDriverRegistered, NO_MESSAGE_TEXT, URL_VARIABLE, USERNAME_VARIABLE, PASSWORD_VARIABLE}` — the separate one-shot migration process, whose failure line carries **classifiers only** (exception class + SQLSTATE), never exception text or a URL; `JdbcUrls.{requireLocation(String), SCHEME, ALLOWED}` (package-private) — the URL must fit a **location grammar**: `jdbc:postgresql://host[:port][,host[:port]…]/database`, optionally `?name=value` pairs whose names are in `ALLOWED` (exact pgjdbc names: `ApplicationName`, `connectTimeout`, `loginTimeout`, `socketTimeout`, `sslcert`, `sslkey`, `sslmode`, `sslrootcert`, `tcpKeepAlive` — each verified against `PGProperty.forName` by `JdbcUrlsTest`) and whose values match that parameter's grammar; anything else — a credential parameter, an `options=` payload, any parameter this code has not named, userinfo, another scheme, pgjdbc's host-less forms — is refused **by position or grammar part, never by echoing the URL, a value, or an unrecognised name** (a name can carry a secret too). Called by the migration step (exit 2) and by `SmDataProperties.Credentials`. pgjdbc does **not** parse userinfo (`Driver.parseURL` returns null — measured); the host grammar simply has no place for it, so a libpq-style URI fails by name;
  `ApplyPersistenceConfiguration` beans `smDataSchemaReadiness` (`InitializingBean`), `smDataDataSource` (`HikariDataSource`), `smDataTransactionManager`, `smDataJdbc` (`NamedParameterJdbcTemplate`, `@DependsOn("smDataSchemaReadiness")`), `smDataTransactions` (`TransactionTemplate`), plus the constants `READINESS_QUERY`, `EXPECTED_TABLES`;
  test helpers `SmDataPostgres.{container(), bootstrap(), phase0Outcomes(), superuser(), migratorDataSource(), runtimeDataSource(), superuserJdbc(), migratorJdbc(), runtimeJdbc(), runPhase0(), assignTestPasswords(), migrate(), freshTenant(), dataSource(url,user,pw), StatementOutcome}`, `SqlStatements.of(String)`, `SqlScript.{execute, executeEach}`, `DesignSketch.{repoRoot(), sqlBlock(), read(Path), DESIGN_PATH}`, `Refusals.{assertRefused(state, constraintOrNull, exec[, because]), assertRaised(msgSubstring, exec), unwrap(Throwable), UNIQUE_VIOLATION, CHECK_VIOLATION, NOT_NULL_VIOLATION, FOREIGN_KEY_VIOLATION, INSUFFICIENT_PRIVILEGE, RAISE_EXCEPTION}`.

**Preconditions:** Docker running with `postgres:14` pullable/present; `mvn -f backend/pom.xml clean verify` green at the baseline (187 surefire / 2 failsafe-skipped). Determine whether the host needs the socket override: `ls -l /var/run/docker.sock; cat ~/.testcontainers.properties 2>/dev/null` — if the properties file points `docker.host` at Colima while the socket is Docker Desktop's, every container-starting Maven command in this plan needs `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (measured: without it Ryuk fails with `error while creating mount source path '…/.colima/default/docker.sock': operation not supported`, and only the *first* IT to start a container errors because Testcontainers caches the failure).

- [ ] **Step 1: Add the dependencies (all BOM-managed — write no `<version>`)**

Insert after the `sol-jcsmp` dependency in `backend/dp-core/pom.xml`:

```xml
    <!--
      sm_data (design §3). All BOM-managed by the Boot 3.5.16 parent — no <version> here,
      deliberately: a pinned version would silently diverge from the Hikari/JDBC pair Boot tests
      together. dp-core is the ONLY module that gets these; arch-tests' jdbcIsConfinedToApplyPersistence
      keeps the packages from spreading beyond com.solace.sm.dp.apply.persistence.
    -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <!--
      COMPILE scope, not runtime, and the reason is the design's: the engine branches on the
      CONSTRAINT NAME behind a 23505 (which intent UNIQUE refused, which fence lost a race), and the
      name lives on org.postgresql.util.PSQLException.getServerErrorMessage(). java.sql.SQLException
      exposes only SQLSTATE, and Spring's DataAccessException hierarchy collapses both intent UNIQUEs
      into one DuplicateKeyException. PgConstraintViolation is the one class that unwraps it, and
      arch-tests' jdbcIsConfinedToApplyPersistence keeps org.postgresql.. inside this package.
      ApplyPersistenceConfiguration still resolves the DRIVER through DriverManager rather than naming
      the class, so the vendor stays out of the bean wiring.
    -->
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <!--
      Flyway 10+ moved every database dialect out of flyway-core into per-database modules; without
      this artifact `Flyway.configure()...load()` throws at runtime on a jdbc:postgresql URL. Measured,
      not assumed.
    -->
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
```

and, in the test-scope block after `spring-boot-starter-test`:

```xml
    <!--
      A REAL PostgreSQL 14 for the sm_data ITs — the pinned floor (SM-D26), not an embedded
      substitute: the obligations under test are ROLE, GRANT, partial-index and PL/pgSQL trigger
      behaviour, none of which H2 or an in-memory shim reproduces. Testcontainers' BOM is imported by
      the Boot parent, so both artifacts are version-less here.
    -->
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
```

Run: `mvn -f backend/pom.xml -pl dp-core -am -q dependency:tree -Dincludes='org.postgresql,org.flywaydb,org.testcontainers,com.zaxxer,org.springframework:spring-jdbc' | grep -E "postgresql|flyway|testcontainers|Hikari|spring-jdbc"`
Expected (measured 2026-09-07 against the 3.5.16 BOM): `HikariCP:jar:6.3.3`, `spring-jdbc:jar:6.2.19`, `postgresql:jar:42.7.11`, `flyway-core:jar:11.7.2`, `flyway-database-postgresql:jar:11.7.2`, `testcontainers postgresql:jar:1.21.4`, `junit-jupiter:jar:1.21.4`. If any artifact fails to resolve without a version, **stop and report** — do not pin.

- [ ] **Step 2: Extract Phase 0 and the six migrations from the design — bytes, not typing**

The design at commit `d87c2e7` carries the block at lines 228–552 (Phase 0 = 228–265, md5 `768e195b7c17861d97e402c5318be51b`; Phase 1 = 266–552, md5 `2f36077ce81bb77ae37d48315092fa7e`). First confirm both, then produce the seven files with these exact recipes (run from the repo root; `D` is the design file). The split cuts at statement boundaries only; each table's runtime GRANT travels with the migration that creates the table; `SET ROLE sm_dp_owner` (line 277) goes to no file — it is Flyway `initSql`.

```bash
D=docs/design/backend/eda-walking-skeleton.md
M=backend/dp-core/src/main/resources/db/migration/sm_data
B=backend/dp-core/src/main/resources/db/bootstrap
mkdir -p $M $B
sed -n '228,265p' $D | md5   # must print 768e195b7c17861d97e402c5318be51b — else the design moved: find the block by its "-- PHASE 0" banner and re-derive every range below
sed -n '266,552p' $D | md5   # must print 2f36077ce81bb77ae37d48315092fa7e
sed -n '228,265p' $D > $B/sm_data_phase0.sql
(sed -n '266,276p' $D; echo; sed -n '279,281p' $D)                                             > $M/V001__sm_data_defaults.sql
(sed -n '283,285p' $D; echo; sed -n '287,369p' $D; echo; sed -n '525p' $D; sed -n '527,541p' $D) > $M/V002__apply_request.sql
(sed -n '371,437p' $D; echo; sed -n '543,552p' $D)                                             > $M/V003__apply_scope_and_watermarks.sql
(sed -n '439,462p' $D; echo; sed -n '526p' $D)                                                 > $M/V004__apply_step_intent.sql
(sed -n '464,496p' $D; echo; sed -n '542p' $D)                                                 > $M/V005__apply_attempt.sql
sed -n '498,523p' $D                                                                           > $M/V006__terminal_guard.sql
md5 -q $B/sm_data_phase0.sql $M/V00*.sql
```

Expected md5s, in that order: `768e195b7c17861d97e402c5318be51b`, `28c29fa570033ff027d6bfd6c515b5a9`, `96321edb257e39bb503192c3c3dff5a6`, `ea69e1fad80d3a4dcf56b074608e3b79`, `5a1f041bd3c42ff5f9428ebe1be019a0`, `049146260370f241e1321e865e97166a`, `9d6da8056e54fd0e1dee777b5c19bbee`. A mismatch means the design's line numbers drifted (the SQL block itself is byte-identical as long as the two block hashes hold) — re-derive the ranges from the banner: with `B = $(grep -n '^-- PHASE 0 — install bootstrap' $D | cut -d: -f1)` (229 at `f84030e`), every range above is a fixed offset from it — Phase 0 = `B-1 .. B+36`; Phase 1 = `B+37 .. B+316`; V001 = `B+37..B+47` + blank + `B+50..B+52`; V002 = `B+54..B+56` + blank + `B+58..B+140` + blank + `B+296` + `B+298..B+305`; V003 = `B+142..B+208` + blank + `B+307..B+316`; V004 = `B+210..B+233` + blank + `B+297`; V005 = `B+235..B+267` + blank + `B+306`; V006 = `B+269..B+294` — and re-check the seven md5s. **Never edit the SQL to make a hash match**. What each file holds: V001 = the Phase-1 header comment + `ALTER DEFAULT PRIVILEGES` (1 statement); V002 = the three-valued-logic note, `apply_request`, the three fences, `apply_history`, the column-scoped GRANT (6); V003 = `apply_scope`, `placement_watermark`, the two `*_only_rises` functions and triggers, `request_has_scope`, the two watermark GRANTs (9); V004 = `apply_step_intent` + GRANT (2); V005 = `apply_attempt`, `attempt_prune`, GRANT (3); V006 = `forbid_terminal_regression` + trigger (2). 7 + 1 + 23 = the 31 statements the evidence report executed.

- [ ] **Step 3: `SmDataMigrator` — Flyway configuration and nothing else**

`backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/SmDataMigrator.java`:

```java
package com.solace.sm.dp.apply.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Phase 1 of the {@code sm_data} bootstrap (eda-walking-skeleton §3.1), as Flyway configuration and
 * nothing else.
 *
 * <p><b>The two-phase split is not cosmetic.</b> Phase 0 — roles, {@code CREATE SCHEMA sm_data
 * AUTHORIZATION sm_dp_owner}, the schema grants and the {@code REVOKE CREATE ON SCHEMA public FROM
 * PUBLIC} — is install-runbook material run ONCE by the cluster administrator (on the PG 14 floor,
 * the bootstrap superuser: only the grantor can revoke, and for anyone else that last statement is a
 * silent {@code WARNING} no-op). It ships as {@code db/bootstrap/sm_data_phase0.sql} for the runbook
 * and the tests to execute, and Flyway must never see it. Phase 1 is everything below, run as
 * {@code sm_dp_migrator}.
 *
 * <p><b>{@link #INIT_SQL} is the load-bearing line.</b> {@code sm_dp_migrator} is already a member
 * of {@code sm_dp_owner}, so membership alone would let it {@code ALTER} owner-owned objects — what
 * {@code SET ROLE} buys is <b>ownership at CREATE time</b>, so every table, index, function and
 * trigger this migrator creates is born owned by {@code sm_dp_owner} rather than by the migrator
 * login. Flyway applies {@code initSql} to each connection it opens, which is why it is expressed
 * here and not as the first line of each migration file (§3.1's header says exactly that).
 *
 * <p><b>{@code createSchemas(false)} is a gate, not an optimisation.</b> {@code sm_data} is created
 * by Phase 0 with an explicit {@code AUTHORIZATION}; letting Flyway create it would produce a
 * migrator-owned schema and quietly diverge from the topology obligation (s) asserts. With the flag
 * off, a database that skipped Phase 0 fails loudly on the first migrate instead of coming up wrong.
 */
public final class SmDataMigrator {

    /** Flyway's per-connection init statement — see the class javadoc for why it is here. */
    public static final String INIT_SQL = "SET ROLE sm_dp_owner";

    /** The data plane's only schema ({@code I7}: no cross-schema joins with {@code sm_control}). */
    public static final String SCHEMA = "sm_data";

    /** Classpath location of the Phase-1 migrations, split out of §3.1 at statement boundaries. */
    public static final String LOCATION = "classpath:db/migration/sm_data";

    /** Classpath location of the Phase-0 script — for the runbook and the bootstrap IT, not Flyway. */
    public static final String PHASE0_RESOURCE = "db/bootstrap/sm_data_phase0.sql";

    private SmDataMigrator() {
    }

    /**
     * Applies Phase 1 with the migrator credential.
     *
     * @param migratorDataSource a {@code sm_dp_migrator} data source — never the runtime one, which
     *                           is not an {@code sm_dp_owner} member and cannot run {@link #INIT_SQL}
     * @return Flyway's own report: which versions were applied, in order
     */
    public static MigrateResult migrate(DataSource migratorDataSource) {
        return Flyway.configure()
                .dataSource(migratorDataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .initSql(INIT_SQL)
                .locations(LOCATION)
                .createSchemas(false)
                .load()
                .migrate();
    }
}
```

- [ ] **Step 4: `SmDataProperties` — two credentials and the engine's one knob**

```java
package com.solace.sm.dp.apply.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code sm_data} configuration the APPLICATION binds, from {@code sm.data.*} — and, as of the
 * external review's finding 1, that is the {@code sm_dp_runtime} credential and nothing else.
 *
 * <p><b>The migrator credential never reaches the application process.</b> Design §3.1's privilege
 * topology IS the security boundary: {@code sm_dp_migrator} is a member of {@code sm_dp_owner} and can
 * rewrite the schema. Binding it here would have put a schema-owner password in a bean any component
 * of the same JVM could read — the boundary would then be a convention about who calls
 * {@code props.migrator()}, not a property of the process. So Phase 1 is a SEPARATE one-shot process,
 * {@link SmDataMigrationMain}, run by the install runbook with that credential; what starts afterwards
 * holds only {@code runtime}: {@code NOINHERIT}, not an owner member, carrying only the per-table (and,
 * on three tables, per-column) grants the migrations issue. {@link ApplyPersistenceConfiguration}'s
 * readiness check is how the application still refuses to serve an unmigrated schema without ever
 * being able to migrate one.
 *
 * <p><b>The password is a deployment secret</b> ({@code I6}): supplied by the environment or the secret
 * backend, never persisted to config storage, never logged. Records generate a component-printing
 * {@code toString} by default, so the nested record overrides it — the same precedent
 * {@code MeshProperties} sets.
 *
 * @param runtime the application credential — the only one this process is given
 * @param apply   the apply engine's own knobs; defaulted when the block is absent
 */
@ConfigurationProperties(prefix = "sm.data")
public record SmDataProperties(Credentials runtime, Apply apply) {

    public SmDataProperties {
        if (runtime == null) {
            throw new IllegalArgumentException("sm.data.runtime must be set");
        }
        if (apply == null) {
            apply = new Apply(null);
        }
    }

    /**
     * Engine configuration the database deliberately cannot hold.
     *
     * <p>{@code maxAttempts} is obligation (m)'s cap: {@code attempt_no_positive} gives the lower
     * bound in SQL, the upper bound is configuration and therefore lives here. Default 3 — the number
     * design §3.3's growth arithmetic prices ("a 10-replica instance at 3 retries worst-cases 150
     * rows/apply").
     *
     * @param maxAttempts tries per step before the exhaustion path; null means the default
     */
    public record Apply(Integer maxAttempts) {

        /** The design's own worked number (§3.3 growth arithmetic). */
        public static final int DEFAULT_MAX_ATTEMPTS = 3;

        public Apply {
            if (maxAttempts == null) {
                maxAttempts = DEFAULT_MAX_ATTEMPTS;
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "sm.data.apply.max-attempts must be at least 1, was " + maxAttempts);
            }
        }
    }

    /**
     * One JDBC identity: a location, a role, and that role's secret — three separate things.
     *
     * <p><b>The URL may carry nothing but a location</b>, and that is enforced here rather than
     * reviewed. HikariCP names the URL in its pool diagnostics exactly as Flyway prints it at INFO on
     * every migrate (the measured line is in {@link SmDataMigrationMain}'s javadoc), so whatever the
     * URL holds will be logged. {@link JdbcUrls#requireLocation} therefore admits only
     * {@code jdbc:postgresql://host[:port]/database} plus an allowlist of nine non-secret pgjdbc
     * parameters with fixed value grammars — not merely "no {@code password=}" — at bind time, before a
     * pool exists to print anything, and without echoing the URL.
     *
     * @param url      a {@code jdbc:postgresql://…} URL fitting {@link JdbcUrls}' location grammar
     * @param username the PostgreSQL role to log in as
     * @param password that role's secret — never log it, never store it
     */
    public record Credentials(String url, String username, String password) {

        public Credentials {
            requireText("url", url);
            requireText("username", username);
            JdbcUrls.requireLocation(url);
            if (password == null) {
                password = "";
            }
        }

        private static void requireText(String name, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("sm.data.*." + name + " must be set");
            }
        }

        /**
         * Both halves of the credential redacted — {@link JdbcUrls} calls the role name "half of a
         * credential", and a {@code toString} that printed it would contradict that in the one place
         * Spring is most likely to print this record (a binding failure). The URL stays: it is a
         * location by construction, and it is what a misconfiguration diagnosis needs.
         */
        @Override
        public String toString() {
            return "Credentials[url=" + url + ", username=****, password=****]";
        }
    }
}
```

- [ ] **Step 5: `SmDataMigrationMain` — Phase 1 as its own process — and `ApplyPersistenceConfiguration`'s five infrastructure beans (Task 3 appends the repository beans)**

`JdbcUrls.java` — measured (see the Flyway INFO line quoted in `SmDataMigrationMain`'s javadoc): Flyway logs `Database: jdbc:postgresql://…` at INFO and Spring's `SimpleDriverDataSource` logs the URL at DEBUG, so whatever the URL carries WILL be logged. The rule is therefore a **grammar the URL must fit**, not a list of parameter names it must avoid — Codex's third pass showed the earlier blocklist (`user`/`password`/`sslpassword`) admitted a value smuggled into any other pgjdbc property, `options=` included, which Flyway would then have printed:

```java
package com.solace.sm.dp.apply.persistence;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One structural rule about JDBC URLs: a URL is a LOCATION, never a credential — enforced as a
 * grammar the URL must fit, not as a list of things it must not contain.
 *
 * <p><b>Why this is a refusal and not a lint.</b> Both consumers of an {@code sm_data} URL print it.
 * Flyway logs {@code Database: <url> (PostgreSQL 14.24)} at INFO on every successful migrate, and
 * Spring's {@code SimpleDriverDataSource} logs it again at DEBUG on each connect — both MEASURED, see
 * {@link SmDataMigrationMain}'s javadoc for the captured lines — while HikariCP names the URL in its
 * pool-startup and connection-failure diagnostics. Design §3.1 puts the two login secrets in the
 * install runbook — an environment variable, a mounted secret, a managed store — precisely so they exist
 * in as few places as possible. Whatever the URL carries WILL be logged, so the URL may carry nothing
 * that is not a location.
 *
 * <p><b>Why a grammar and not a blocklist.</b> A first version of this class refused the parameter
 * names {@code user}, {@code password} and {@code sslpassword} and the {@code alice:hunter2@host}
 * userinfo form, and said so. An external review pointed out what that leaves open: pgjdbc accepts
 * dozens of properties, and a value smuggled into one this class did not name — or into an
 * {@code options=} payload — would be admitted and then printed by Flyway. A blocklist can only be as
 * complete as its author's knowledge of the driver. So the rule is now positive: the URL must be
 * {@code jdbc:postgresql://host[:port][,host[:port]…]/database}, optionally followed by
 * {@code ?name=value} pairs whose NAMES are in {@link #ALLOWED} — exact pgjdbc property names, nine of
 * them, each a connection or TLS setting an installer legitimately needs — and whose VALUES match that
 * parameter's own grammar (a keyword, a number, an absolute path, an identifier). Everything else is
 * refused, including every parameter this class has never heard of and the userinfo form, which the
 * host grammar simply does not contain. ({@code Driver.parseURL} does not parse userinfo — measured —
 * but libpq/psql URIs do carry it, and a URI copied from a psql session should fail here, by name,
 * rather than at connect time for a reason nobody would connect to a leak.) Adding a parameter is a
 * deliberate, reviewed edit of the map — which is the point.
 *
 * <p><b>What the grammar does NOT decide, stated</b> — the same residual {@code ValueGrammar} states for
 * evidence: a value that FITS an allowlisted grammar is accepted whatever it means. {@code ?ApplicationName=hunter2}
 * or {@code ?sslkey=/etc/sm/hunter2} passes and is then printed by Flyway at INFO. The grammar bounds the
 * shape of what can be smuggled (an identifier, an absolute path, a keyword, a number); it cannot know that a
 * particular identifier is a secret. An installer that puts a secret where a name goes has defeated a
 * shape control, not a content one, and the runbook says so.
 *
 * <p><b>Both credentials, not just the password:</b> a leaked role name is half of a credential and,
 * under §3.1's topology, tells a reader whether the process holds {@code sm_dp_migrator} (schema owner)
 * or {@code sm_dp_runtime}. The role therefore has no place in the grammar either.
 *
 * <p><b>The refusal never echoes the URL (nor even the literal scheme), a parameter value, or an
 * unrecognised parameter NAME</b> — a name is just as capable of carrying a secret as a value
 * ({@code ?hunter2=1}), so an unknown parameter is reported by its POSITION in the query string. Only names from {@link #ALLOWED} are
 * ever printed, because those are this class's own words. Like every other {@code I6} refusal in this
 * codebase, the message names the RULE and never the offending input.
 */
final class JdbcUrls {

    /** The one scheme the grammar admits — pgjdbc's. The design's sole truth is PostgreSQL. */
    static final String SCHEME = "jdbc:postgresql://";

    /** {@code host[:port]}, comma-separated for pgjdbc's multi-host form; IPv6 in brackets. */
    private static final String HOST_PORT = "(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(?::[0-9]{1,5})?";
    private static final Pattern AUTHORITY = Pattern.compile(HOST_PORT + "(?:," + HOST_PORT + ")*");

    /** A PostgreSQL database name as an ordinary identifier — no quoting, no URL-encoding. */
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private static final Pattern SSLMODE =
            Pattern.compile("disable|allow|prefer|require|verify-ca|verify-full");
    /** An absolute POSIX path without spaces — a mounted certificate or key. */
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/[A-Za-z0-9._/-]+");
    /** An application name as PostgreSQL shows it in {@code pg_stat_activity}. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,63}");
    /** A timeout: pgjdbc's are whole seconds. */
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,6}");
    private static final Pattern BOOLEAN = Pattern.compile("true|false");

    /**
     * The parameters an {@code sm_data} URL may carry — exact pgjdbc property names — and the grammar
     * each value must match. {@code JdbcUrlsTest} checks every key against {@code PGProperty}, so a
     * misspelling cannot survive the build.
     */
    static final Map<String, Pattern> ALLOWED;

    static {
        Map<String, Pattern> m = new TreeMap<>();
        m.put("sslmode", SSLMODE);
        m.put("sslrootcert", ABSOLUTE_PATH);
        m.put("sslcert", ABSOLUTE_PATH);
        m.put("sslkey", ABSOLUTE_PATH);
        m.put("ApplicationName", NAME);
        m.put("connectTimeout", SECONDS);
        m.put("socketTimeout", SECONDS);
        m.put("loginTimeout", SECONDS);
        m.put("tcpKeepAlive", BOOLEAN);
        ALLOWED = Collections.unmodifiableMap(m); // a TreeMap, so the refusal lists names in one order
    }

    private JdbcUrls() {
    }

    /**
     * Requires {@code url} to fit the location grammar.
     *
     * @param url the JDBC URL as configured; {@code null} and blank are the CALLER's own check, not
     *            this one's, so they pass through untouched
     * @throws IllegalArgumentException naming which part of the grammar was missed — never the URL,
     *                                  a value, or an unrecognised parameter name
     */
    static void requireLocation(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (!url.startsWith(SCHEME)) {
            throw refusal("a scheme other than pgjdbc's");
        }
        String rest = url.substring(SCHEME.length());
        int query = rest.indexOf('?');
        String location = query < 0 ? rest : rest.substring(0, query);
        int slash = location.indexOf('/');
        if (slash < 0) {
            throw refusal("no /database after the host");
        }
        if (!AUTHORITY.matcher(location.substring(0, slash)).matches()) {
            throw refusal("a host part that is not host[:port] (userinfo, an unbracketed IPv6 "
                    + "address or a stray character)");
        }
        if (!DATABASE.matcher(location.substring(slash + 1)).matches()) {
            throw refusal("a database name that is not a plain identifier");
        }
        if (query >= 0) {
            requireAllowedParameters(rest.substring(query + 1));
        }
    }

    /**
     * {@code name=value} pairs separated by {@code &} — pgjdbc's separator; {@code ;} is not one, and
     * a pair containing it fails its value grammar. An EMPTY query ({@code …/db?}) is refused too:
     * pgjdbc accepts it (measured), but nothing legitimate writes it, and the grammar admits what an
     * installer needs rather than everything the driver tolerates.
     */
    private static void requireAllowedParameters(String query) {
        String[] pairs = query.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            int equals = pairs[i].indexOf('=');
            if (equals < 0) {
                throw refusal("a query parameter without '=' at position " + (i + 1));
            }
            String name = pairs[i].substring(0, equals);
            Pattern grammar = ALLOWED.get(name);
            if (grammar == null) {
                throw refusal("a query parameter at position " + (i + 1)
                        + " that is not in the allowlist (its name is deliberately not repeated)");
            }
            if (!grammar.matcher(pairs[i].substring(equals + 1)).matches()) {
                throw refusal("a '" + name + "' value outside that parameter's grammar (the value is "
                        + "deliberately not repeated)");
            }
        }
    }

    private static IllegalArgumentException refusal(String what) {
        // The scheme is described, not spelled: every stream SmDataMigrationMain prints is held to
        // "contains no 'jdbc:' at all", and this message is printed there.
        return new IllegalArgumentException("a JDBC URL must be a location only — pgjdbc's scheme, "
                + "then host[:port]/database, optionally ?name=value pairs from the allowlist "
                + String.join(", ", ALLOWED.keySet()) + " — found " + what
                + ". The username and password are supplied separately (design §3.1: they are install "
                + "runbook material), because Flyway and HikariCP both LOG the URL. The offending URL "
                + "is deliberately not repeated here (I6)");
    }
}
```

`SmDataMigrationMain.java`. The migrator credential never reaches the application: this `main` is what the install runbook runs (an init container/Job on Kubernetes, a pre-start command on-prem), with three environment variables, and the application process receives only `sm_dp_runtime`. On failure it prints **classifiers only** — Flyway's connection-failure message embeds the URL and the user, so no exception text is ever printed:

```java
package com.solace.sm.dp.apply.persistence;

import java.io.PrintStream;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Phase 1 as its OWN process — the deploy-time migration step of design §3.1, and the whole of the
 * external review's finding 1.
 *
 * <p><b>Why a {@code main} and not a bean.</b> {@code sm_dp_migrator} is a member of
 * {@code sm_dp_owner}: whoever holds its password can rewrite {@code sm_data}. While Phase 1 ran as a
 * Spring {@code InitializingBean}, that password was bound into the application's own configuration
 * properties for the life of the JVM, so the privilege boundary §3.1 draws between the two roles was a
 * convention about which bean read which field rather than a property of the process. Splitting the
 * step out makes it structural: the application is never given the credential, so no component of it —
 * careless, compromised or merely curious — can act as schema owner.
 *
 * <p><b>How the runbook runs it.</b> On Kubernetes this is an init container or a {@code Job} with the
 * migrator secret mounted, ordered before the Deployment; on-prem it is one command in the install and
 * upgrade procedures, run before the service is started. Either way the application process that
 * follows receives only {@code sm_dp_runtime}, and
 * {@link ApplyPersistenceConfiguration#smDataSchemaReadiness} is what stops it serving a schema this
 * step has not yet touched.
 *
 * <p><b>What this step prints, and what it deliberately does not.</b> Flyway logs the connection URL
 * itself. Measured on the success path of {@code ApplyPersistenceConfigurationIT} through a logback
 * {@code ListAppender} on the root logger — 193 events for one migrate, of which FOUR name the URL:
 *
 * <pre>
 * INFO  org.flywaydb.core.FlywayExecutor —
 *     Database: jdbc:postgresql://localhost:32958/sm_ctx_migrated (PostgreSQL 14.24)
 * DEBUG org.springframework.jdbc.datasource.SimpleDriverDataSource —
 *     Creating new JDBC Driver Connection to [jdbc:postgresql://localhost:32958/sm_ctx_migrated]
 * DEBUG org.flywaydb.core.internal.database.DatabaseTypeRegister —
 *     Multiple databases found that handle url 'jdbc:postgresql://…': CockroachDB,PostgreSQL
 * </pre>
 *
 * <p>The first of those is at INFO — the level a production deployment actually runs at — and it is why
 * {@link JdbcUrls#requireLocation} exists and why this class refuses any URL that is not a bare
 * location with allowlisted parameters, instead of passing it on. Anything inside the URL — a
 * {@code ?password=}, an {@code alice:hunter2@host}, or a value smuggled into a property this code
 * never heard of — would be printed on every successful migration, by a library this code does not
 * control and deliberately does not silence: suppressing the line would hide the exposure rather than
 * remove it.
 *
 * <p>The FAILURE path is stricter still, and it is the half this class DOES control. Flyway's own
 * connection-failure message embeds the URL and the login role, so it is never printed: a failure is
 * reported as one line built only from stable classifiers — the exception's simple class name and, when
 * a {@link SQLException} is anywhere in the cause chain, its SQLSTATE. Measured against an unreachable
 * host, that line is {@code the sm_data migration failed: FlywaySqlException (SQLSTATE 08001) — …}.
 *
 * <p>No Spring here on purpose: a context would need configuration, and configuration is the thing
 * being kept away from the application. Three environment variables, one Flyway run, an exit code.
 */
public final class SmDataMigrationMain {

    /** {@code jdbc:postgresql://…} — the SM database. Credentials do NOT belong in it. */
    public static final String URL_VARIABLE = "SM_DATA_MIGRATOR_URL";

    /** The migrator role, normally {@code sm_dp_migrator}. */
    public static final String USERNAME_VARIABLE = "SM_DATA_MIGRATOR_USERNAME";

    /** That role's secret. Read, used, and never echoed — not into stdout, not into a failure. */
    public static final String PASSWORD_VARIABLE = "SM_DATA_MIGRATOR_PASSWORD";

    /** A required environment variable was absent, blank, or carried something it must not. */
    static final int EXIT_MISSING_VARIABLE = 2;

    /** Flyway refused the migration. */
    static final int EXIT_FAILED = 1;

    /** The failure line's fixed tail — one sentence saying why the diagnosis is deliberately thin. */
    static final String NO_MESSAGE_TEXT = " — no message text is printed because Flyway's includes "
            + "the connection URL; diagnose with psql using the same credential";

    private SmDataMigrationMain() {
    }

    /**
     * The entry point the runbook invokes.
     *
     * @param args ignored — configuration arrives through the environment rather than the command
     *             line, so the password is not in the invoking shell's history. That is a convenience,
     *             not the protection: environment variables are process state, and whether another
     *             process on the host can read them depends on the host and the runtime. The boundary
     *             that matters is that this is a SEPARATE process from the application, which never
     *             receives the migrator credential at all; the install runbook owns how this one is
     *             isolated while it runs.
     */
    public static void main(String[] args) {
        System.exit(exitCode(System.getenv(), System.out, System.err));
    }

    /**
     * {@link #main} minus {@link System#exit}, and with both streams injected so a test can read every
     * byte this step emits.
     *
     * <p>Failures are reported as ONE line naming what is wrong, assembled from classifiers only. A
     * missing-variable message names the VARIABLE; a URL outside {@link JdbcUrls}' location grammar
     * names the part of the grammar it missed and the two variables a credential belongs in; a
     * migration failure names the exception's simple class name and its
     * SQLSTATE. No URL, no username, no password and no library-supplied message text reaches either
     * stream ({@code I6}) — the exception object is not printed at all, because Flyway's
     * {@code toString} carries the URL and the role.
     *
     * @param env the environment to read — {@code System.getenv()} in production
     * @param out where the success line goes
     * @param err where the single failure line goes
     * @return 0 on success, {@value #EXIT_FAILED} when the migration failed,
     *         {@value #EXIT_MISSING_VARIABLE} when a variable is missing or malformed
     */
    static int exitCode(Map<String, String> env, PrintStream out, PrintStream err) {
        Map<String, String> read = new LinkedHashMap<>();
        for (String name : new String[] {URL_VARIABLE, USERNAME_VARIABLE, PASSWORD_VARIABLE}) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                err.println(name + " is not set — the sm_data migration step reads its "
                        + "credential from the environment, and will not guess one");
                return EXIT_MISSING_VARIABLE;
            }
            read.put(name, value);
        }
        try {
            JdbcUrls.requireLocation(read.get(URL_VARIABLE));
        } catch (IllegalArgumentException e) {
            // Its message is JdbcUrls' own words — the rule and the allowlist, never the URL, a value or
            // an unrecognised parameter name — so it is safe to print, and it tells the operator which
            // part of the grammar was missed.
            err.println(URL_VARIABLE + " is not a bare location: " + e.getMessage()
                    + ". The credential belongs in " + USERNAME_VARIABLE + " / " + PASSWORD_VARIABLE);
            return EXIT_MISSING_VARIABLE;
        }
        try {
            MigrateResult result = run(read.get(URL_VARIABLE), read.get(USERNAME_VARIABLE),
                    read.get(PASSWORD_VARIABLE));
            out.println("migrated " + result.migrationsExecuted + " (versions "
                    + versions(result) + ")");
            return 0;
        } catch (NoDriverRegistered e) {
            // Its message is this class's own and names no URL, so it is safe to print verbatim —
            // and it is the one failure a classifier alone would leave undiagnosable.
            err.println("the sm_data migration failed: " + e.getMessage());
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            err.println("the sm_data migration failed: " + classify(e));
            return EXIT_FAILED;
        }
    }

    /**
     * Applies Phase 1 with the migrator credential — the one-shot step every caller shares, including
     * the integration harness, so no test can prove a path production does not take.
     *
     * @param url      the SM database's JDBC URL, carrying no credential
     * @param username the migrator role
     * @param password its secret
     * @return Flyway's own report: which versions were applied, in order
     */
    public static MigrateResult run(String url, String username, String password) {
        return SmDataMigrator.migrate(dataSource(url, username, password));
    }

    /**
     * A one-connection-at-a-time data source for the migrator credential.
     *
     * <p>A {@link SimpleDriverDataSource} on purpose: it opens one connection per
     * {@code getConnection()} with no pool to shut down, and it is used exactly once, for the length of
     * {@link SmDataMigrator#migrate}. It lives HERE rather than on the Spring configuration because
     * that class no longer has a migrator credential to build one from.
     *
     * <p>The driver comes from {@link DriverManager#getDriver(String)} rather than
     * {@code new org.postgresql.Driver()} so that the wiring compiles against {@code java.sql} only —
     * it registers itself through {@code META-INF/services/java.sql.Driver}. Naming the concrete
     * driver here would tie the step to the JDBC vendor for no gain.
     */
    static SimpleDriverDataSource dataSource(String url, String username, String password) {
        try {
            return new SimpleDriverDataSource(DriverManager.getDriver(url), url, username, password);
        } catch (SQLException e) {
            throw new NoDriverRegistered(e);
        }
    }

    /**
     * No registered driver claims the URL's scheme.
     *
     * <p>Its own type, and not a bare {@link IllegalStateException}, so {@link #exitCode} can tell the
     * one failure whose message is safe to print from every failure whose message is not. The message
     * says "the configured URL's scheme" rather than quoting the URL — a driver-resolution failure is
     * the FIRST thing a misconfigured deployment hits, and it must not be the place the URL escapes.
     */
    static final class NoDriverRegistered extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        NoDriverRegistered(SQLException cause) {
            super("no JDBC driver is registered for the configured URL's scheme", cause);
        }
    }

    /**
     * A failure as classifiers only: {@code FlywaySqlException (SQLSTATE 08001) — …}.
     *
     * <p>The simple class name is stable across versions and carries no data. The SQLSTATE is the
     * five-character standard code — {@code 08001} unable to connect, {@code 28P01} bad password,
     * {@code 42501} insufficient privilege — which is what an operator actually needs to know, and is
     * likewise data-free.
     */
    private static String classify(Throwable failure) {
        String sqlState = sqlStateOf(failure);
        return failure.getClass().getSimpleName()
                + (sqlState == null ? "" : " (SQLSTATE " + sqlState + ")")
                + NO_MESSAGE_TEXT;
    }

    /** The first SQLSTATE in the cause chain, or null. Bounded, because a chain may be cyclic. */
    private static String sqlStateOf(Throwable failure) {
        Throwable c = failure;
        for (int depth = 0; c != null && depth < 32; c = c.getCause(), depth++) {
            if (c instanceof SQLException sql && sql.getSQLState() != null
                    && !sql.getSQLState().isBlank()) {
                return sql.getSQLState();
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    private static String versions(MigrateResult result) {
        if (result.migrations == null || result.migrations.isEmpty()) {
            return "none — already at " + result.targetSchemaVersion;
        }
        return result.migrations.stream().map(m -> m.version).collect(Collectors.joining(", "));
    }
}
```

`ApplyPersistenceConfiguration.java` — no migration bean, no migrator credential; a **schema readiness check** the runtime role can honestly make replaces the old ordering edge:

```java
package com.solace.sm.dp.apply.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code sm_data} access graph as one bean set (design §3.1) — serving every query with the
 * deliberately weak {@code sm_dp_runtime} credential, and holding no other.
 *
 * <p>Not auto-configured, and not {@code spring.datasource.*}: the future application class
 * {@code @Import}s this deliberately, the same way {@code MeshBridgeConfiguration} is imported. Boot's
 * {@code DataSourceAutoConfiguration} would give the application ONE datasource, and the whole
 * privilege topology of §3.1 — obligations (k), (l), (o), (s), (t) — rests on the runtime connection
 * being a role that cannot migrate and cannot {@code SET ROLE sm_dp_owner}.
 *
 * <p><b>Migration is not here any more.</b> The external review's finding 1: while Phase 1 ran as a
 * bean in this class, the application process held the schema-owner credential for its whole life.
 * Phase 1 is now {@link SmDataMigrationMain}, a separate one-shot process the install runbook runs
 * before this one starts. What replaces the ordering edge is {@link #smDataSchemaReadiness} — a check
 * the runtime role CAN make, refusing to serve a schema the migration step has not reached.
 *
 * <p>{@code I5} note: every statement issued through {@link #smDataJdbc} binds its parameters —
 * {@code NamedParameterJdbcTemplate} is the only template exposed, and no {@code JdbcTemplate} bean
 * is published, so string-interpolated SQL has no convenient path here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmDataProperties.class)
public class ApplyPersistenceConfiguration {

    /**
     * The five tables design §3.1 creates, and the whole of what the runtime role may look for.
     *
     * <p>Deliberately a literal: nothing about this query is caller-supplied, so there is no bind
     * parameter to omit and no {@code I5} surface to open.
     */
    static final String READINESS_QUERY =
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sm_data'"
                    + " AND table_name IN ('apply_request','apply_scope','placement_watermark',"
                    + "'apply_step_intent','apply_attempt')";

    /** How many of them there must be. */
    static final int EXPECTED_TABLES = 5;

    /**
     * Refuses to start against a schema {@link SmDataMigrationMain} has not migrated.
     *
     * <p><b>Why table presence and not a version.</b> The obvious check — read
     * {@code flyway_schema_history} and compare the applied version against the one this build ships —
     * is not available to this role, and that is the intended posture, not an oversight: design §3.1's
     * {@code ALTER DEFAULT PRIVILEGES} only reaches tables created AFTER it, Flyway creates its history
     * table before the first migration runs, and so {@code sm_dp_runtime} gets
     * {@code permission denied for table flyway_schema_history} (measured, and stated in §3.1). Reading
     * it would mean granting the runtime role a view of the migrator's bookkeeping, which is a wider
     * grant than the check is worth. So the check is the one the runtime role can honestly make: are
     * the five tables it is granted on present.
     *
     * <p>That is presence, not version pinning: a future migration that only ALTERs an existing table
     * would pass this check. While there are six migrations creating five tables that is the whole
     * distance between "unmigrated" and "migrated"; when migrations grow, pinning the version belongs
     * to the plan that grows them — most likely by having the migration step write a small
     * runtime-readable marker rather than by widening the grant.
     */
    @Bean
    InitializingBean smDataSchemaReadiness(DataSource smDataDataSource) {
        return () -> {
            int found;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                rs.next();
                found = rs.getInt(1);
            }
            if (found != EXPECTED_TABLES) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build "
                        + "expects (found " + found + " of " + EXPECTED_TABLES + " tables): run the "
                        + "migration step — SmDataMigrationMain with the sm_dp_migrator credential — "
                        + "before starting the application");
            }
        };
    }

    /** The application pool: {@code sm_dp_runtime}, the role that owns nothing. */
    @Bean(destroyMethod = "close")
    HikariDataSource smDataDataSource(SmDataProperties props) {
        SmDataProperties.Credentials c = props.runtime();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(c.url());
        ds.setUsername(c.username());
        ds.setPassword(c.password());
        ds.setPoolName("sm-data");
        // The schema is set on the connection rather than written into every statement: unqualified
        // names in repository SQL then resolve inside sm_data and nowhere else.
        ds.setSchema(SmDataMigrator.SCHEMA);
        return ds;
    }

    /**
     * The transaction manager the admission transaction of §3.3 runs under — and, not incidentally,
     * the one that makes {@code SpringTransactionBoundary.inTransaction()} true, so an after-commit
     * publish is ordered against a real database commit rather than an empty synchronized scope.
     */
    @Bean
    PlatformTransactionManager smDataTransactionManager(DataSource smDataDataSource) {
        return new DataSourceTransactionManager(smDataDataSource);
    }

    /**
     * Bind-parameter-only template ({@code I5}).
     *
     * <p>{@code @DependsOn} the readiness check, by bean name, so no repository can issue a query
     * before it has run. Bean-name coupling is the cost; the alternative — a repository meeting a
     * schema with no tables and reporting it as a broken query — is what it buys.
     */
    @Bean
    @DependsOn("smDataSchemaReadiness")
    NamedParameterJdbcTemplate smDataJdbc(DataSource smDataDataSource) {
        return new NamedParameterJdbcTemplate(smDataDataSource);
    }

    /** Programmatic transactions for the admission path, which needs an explicit boundary. */
    @Bean
    TransactionTemplate smDataTransactions(PlatformTransactionManager smDataTransactionManager) {
        return new TransactionTemplate(smDataTransactionManager);
    }
}
```

- [ ] **Step 6: The test harness — six classes under `src/test/java/com/solace/sm/dp/apply/persistence/`**

`SqlStatements.java` — the ONE splitter both the harness and Task 2's mirror test use:

```java
package com.solace.sm.dp.apply.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * One SQL splitter, used by BOTH sides of {@link SketchMirrorTest} and by the harness that executes
 * Phase 0. Two splitters would make the mirror test compare two parsers instead of two scripts.
 *
 * <p>It is quote-aware in the two ways this DDL needs: single-quoted literals (so a {@code --}
 * inside one is not read as a comment, and a {@code ;} inside one does not end a statement) and
 * {@code $$} dollar quoting (the PL/pgSQL bodies of §3.1 are full of semicolons). It is NOT a
 * general PostgreSQL parser — no tagged dollar quotes, no C-style block comments, no {@code E''}
 * escapes — because §3.1 uses none of those, and {@link #assertNoDialectSurprises} fails loudly if
 * that ever stops being true.
 */
final class SqlStatements {

    private SqlStatements() {
    }

    /** Comment-stripped, whitespace-normalised, non-empty statements, in file order. */
    static List<String> of(String sql) {
        String stripped = stripLineComments(sql);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        boolean inDollar = false;
        int i = 0;
        while (i < stripped.length()) {
            char c = stripped.charAt(i);
            if (inDollar) {
                if (stripped.startsWith("$$", i)) {
                    current.append("$$");
                    inDollar = false;
                    i += 2;
                    continue;
                }
                current.append(c);
                i++;
                continue;
            }
            if (inQuote) {
                current.append(c);
                inQuote = c != '\'';
                i++;
                continue;
            }
            if (c == '\'') {
                inQuote = true;
                current.append(c);
                i++;
                continue;
            }
            if (stripped.startsWith("$$", i)) {
                current.append("$$");
                inDollar = true;
                i += 2;
                continue;
            }
            if (c == ';') {
                add(statements, current);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        add(statements, current);
        return statements;
    }

    private static void add(List<String> out, CharSequence raw) {
        String s = raw.toString().replaceAll("\\s+", " ").trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    /** Drops {@code --} to end of line, except inside a single-quoted literal. */
    private static String stripLineComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inQuote = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (inQuote) {
                out.append(c);
                inQuote = c != '\'';
                i++;
                continue;
            }
            if (c == '\'') {
                inQuote = true;
                out.append(c);
                i++;
                continue;
            }
            if (sql.startsWith("--", i)) {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? sql.length() : nl;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The splitter's own preconditions, asserted rather than assumed: no single-quoted literal in
     * the script contains {@code --} (which the comment stripper would have to reason about beyond
     * quote state) and no tagged dollar quote appears. Returns a human-readable complaint, or null.
     */
    static String assertNoDialectSurprises(String rawSql) {
        // Comments must be removed BEFORE any quote-tracking pass, and that ordering is measured,
        // not stylistic: §3.1's prose comments contain English apostrophes ("PG 14's default left
        // CREATE on schema public open to PUBLIC"), so a quote tracker run over the raw text opens a
        // string literal on a comment and reports nonsense for the rest of the file.
        String sql = stripLineComments(rawSql);
        boolean inQuote = false;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inQuote) {
                if (c == '\'') {
                    if (literal.indexOf("--") >= 0) {
                        return "a single-quoted literal contains '--': " + literal;
                    }
                    literal.setLength(0);
                    inQuote = false;
                } else {
                    literal.append(c);
                }
                continue;
            }
            if (c == '\'') {
                inQuote = true;
            } else if (c == '$' && i + 1 < sql.length() && sql.charAt(i + 1) != '$'
                    && Character.isLetterOrDigit(sql.charAt(i + 1))) {
                return "a tagged dollar quote appears near: "
                        + sql.substring(i, Math.min(sql.length(), i + 40));
            }
        }
        return null;
    }
}
```

`SqlScript.java` — per-statement execution that **captures `SQLWarning`s** (the whole reason it exists instead of Spring's `ScriptUtils`: obligation (s)'s negative control turns on a warning):

```java
package com.solace.sm.dp.apply.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Executes DDL/DCL scripts statement by statement and <b>collects the {@link SQLWarning}s</b>.
 *
 * <p>The warning capture is the whole reason this exists rather than Spring's {@code ScriptUtils}.
 * Obligation (s) turns on a warning: {@code REVOKE CREATE ON SCHEMA public FROM PUBLIC} is a
 * <i>silent</i> no-op for anyone but the grantor — PostgreSQL raises
 * {@code WARNING: no privileges could be revoked for "public"} and returns success. A harness that
 * only checks for exceptions cannot tell the superuser run from the installer run, which is exactly
 * the discrimination obligation (s)'s negative control demands.
 *
 * <p>These are scripts, not queries: no parameters, no user input, nothing to bind. Every statement
 * the application issues goes through {@code NamedParameterJdbcTemplate} instead ({@code I5}).
 */
final class SqlScript {

    private SqlScript() {
    }

    /** Runs one statement; returns its warnings, oldest first. */
    static List<String> execute(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
            List<String> warnings = new ArrayList<>();
            for (SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
                warnings.add(w.getMessage());
            }
            return warnings;
        } catch (SQLException e) {
            throw new IllegalStateException("failed: " + sql, e);
        }
    }

    /**
     * Runs every statement of a script on ONE connection, in order, each on its own
     * {@link Statement} so warnings stay attributable to the statement that raised them.
     */
    static List<SmDataPostgres.StatementOutcome> executeEach(DataSource ds, String script) {
        List<SmDataPostgres.StatementOutcome> outcomes = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            for (String sql : SqlStatements.of(script)) {
                try (Statement s = c.createStatement()) {
                    s.execute(sql);
                    List<String> warnings = new ArrayList<>();
                    for (SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
                        warnings.add(w.getMessage());
                    }
                    outcomes.add(new SmDataPostgres.StatementOutcome(sql, warnings));
                } catch (SQLException e) {
                    throw new IllegalStateException("script statement failed: " + sql, e);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not open a connection for the script", e);
        }
        return outcomes;
    }
}
```

`DesignSketch.java` — repo-root resolution (mirrors `ContractArtifacts`) and **structural** extraction of the §3.1 block (never by line number):

```java
package com.solace.sm.dp.apply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates and extracts the §3.1 DDL block out of {@code docs/design/backend/eda-walking-skeleton.md}.
 *
 * <p>Resolution mirrors {@code ContractArtifacts}: {@code -Dsm.repo.root} wins, otherwise an ancestor
 * walk from the working directory to the first directory that holds the design file. Surefire's
 * working directory is the module, so the walk is what makes this work from both {@code dp-core/} and
 * the reactor root.
 *
 * <p>The block is found <b>structurally</b> — the fenced region whose first line is the Phase-0
 * banner, to the closing fence — never by line number. Line numbers in a living design document go
 * stale the first time a paragraph is edited above them, and a mirror test that silently extracts the
 * wrong lines is worse than no mirror test.
 */
final class DesignSketch {

    static final String DESIGN_PATH = "docs/design/backend/eda-walking-skeleton.md";

    /** The first line of the block — the Phase-0 banner's opening comment. */
    private static final String BLOCK_START = "-- PHASE 0 — install bootstrap";

    private static final String FENCE = "```";

    private DesignSketch() {
    }

    static Path repoRoot() {
        String override = System.getProperty("sm.repo.root");
        if (override != null) {
            if (override.isBlank()) {
                throw new IllegalStateException("sm.repo.root is set but blank");
            }
            Path candidate = Path.of(override);
            if (!Files.isRegularFile(candidate.resolve(DESIGN_PATH))) {
                throw new IllegalStateException("sm.repo.root=" + override + " has no " + DESIGN_PATH);
            }
            return candidate;
        }
        Path from = Path.of("").toAbsolutePath();
        for (Path p = from; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve(DESIGN_PATH))) {
                return p;
            }
        }
        throw new IllegalStateException(DESIGN_PATH + " not found walking up from " + from);
    }

    /** The whole §3.1 SQL block, Phase 0 and Phase 1 together, exactly as the design carries it. */
    static String sqlBlock() {
        String doc = read(repoRoot().resolve(DESIGN_PATH));
        String[] lines = doc.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(BLOCK_START)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalStateException("no line starting with '" + BLOCK_START + "' in " + DESIGN_PATH
                    + " — the design moved and this test must be re-pointed, not deleted");
        }
        // Walk back to the opening fence — the banner sits under a `-- ===` rule, so it is the
        // block's second line, not its first. Anchoring on the fence keeps that a detail.
        int open = -1;
        for (int i = start; i >= 0; i--) {
            if (lines[i].startsWith(FENCE)) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            throw new IllegalStateException("the Phase-0 banner is not inside a fenced block");
        }
        int end = -1;
        for (int i = start; i < lines.length; i++) {
            if (lines[i].startsWith(FENCE)) {
                end = i;
                break;
            }
        }
        start = open + 1;
        if (end < 0) {
            throw new IllegalStateException("unterminated fenced block after the Phase-0 banner");
        }
        return String.join("\n", java.util.Arrays.asList(lines).subList(start, end));
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`SmDataPostgres.java` — ONE container per JVM, one cached `DataSource` **instance** per role (load-bearing, see its javadoc):

```java
package com.solace.sm.dp.apply.persistence;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ONE PostgreSQL 14 container per JVM, bootstrapped once, shared by every {@code sm_data} IT.
 *
 * <p>PostgreSQL 14 because {@code SM-D26} pins it as the floor and every obligation in design §3.3
 * is a statement about <i>that</i> version's behaviour — NULL index keys distinct, no
 * {@code NULLS NOT DISTINCT}, schema {@code public} still owned by the bootstrap superuser with
 * {@code CREATE} open to {@code PUBLIC}. Testing on 16 would test a database the product does not
 * claim.
 *
 * <p><b>One container, not one per class.</b> Roles are CLUSTER-scoped, so Phase 0 is a
 * once-per-cluster act — the design's own harness note says a literal re-run aborts at
 * {@code CREATE ROLE}. Sharing the container is therefore not a speed optimisation, it is what makes
 * the bootstrap testable at all. Tests isolate from each other with {@link #freshTenant()} instead.
 *
 * <p>Nothing here is a Spring context: the ITs want the raw privilege topology, and a context would
 * put an extra layer between the assertion and the grant it is asserting.
 */
final class SmDataPostgres {

    /** The pinned floor. Not "postgres:latest" — see the class javadoc. */
    static final DockerImageName IMAGE = DockerImageName.parse("postgres:14");

    /** Test-only login secrets. Phase 0 deliberately carries none — they are runbook material. */
    static final String MIGRATOR_PASSWORD = "migrator-test-pw";
    static final String RUNTIME_PASSWORD = "runtime-test-pw";

    private static PostgreSQLContainer<?> container;
    private static boolean bootstrapped;
    private static MigrateResult migrateResult;
    private static List<StatementOutcome> phase0Outcomes;
    private static DataSource superuserDs;
    private static DataSource migratorDs;
    private static DataSource runtimeDs;

    private SmDataPostgres() {
    }

    static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>(IMAGE);
            c.start();
            container = c;
        }
        return container;
    }

    /**
     * Phase 0 + test passwords + Phase 1, exactly once per JVM.
     *
     * @return Flyway's report from the single migrate run every IT shares
     */
    static synchronized MigrateResult bootstrap() {
        if (!bootstrapped) {
            phase0Outcomes = runPhase0();
            assignTestPasswords();
            migrateResult = migrate();
            bootstrapped = true;
        }
        return migrateResult;
    }

    /**
     * What Phase 0 said when it ran — kept because it runs once per JVM and the bootstrap IT must be
     * able to assert on it whatever order the ITs execute in.
     */
    static synchronized List<StatementOutcome> phase0Outcomes() {
        bootstrap();
        return phase0Outcomes;
    }

    /**
     * ONE DataSource instance per role, cached — and the caching is load-bearing, not tidiness.
     *
     * <p>Measured the hard way: Spring binds a transactional connection to the
     * {@code DataSource} <b>instance</b> as its {@code TransactionSynchronizationManager} resource
     * key. A {@code JdbcTemplate} over one instance and a {@code DataSourceTransactionManager} over
     * a second instance of the same URL therefore do NOT share a transaction — the template
     * silently takes its own auto-commit connection, every write commits immediately, and a
     * rollback test passes for the wrong reason. Handing out fresh instances made
     * {@code SpringTransactionBoundaryDataSourceIT}'s rollback case fail with the row still present;
     * that failure is what produced this comment. Production is safe by construction (one
     * {@code smDataDataSource} bean), so this is a harness hazard — and exactly the sort a harness
     * that hands out new objects from a getter creates for itself.
     */
    static synchronized DataSource superuser() {
        if (superuserDs == null) {
            PostgreSQLContainer<?> c = container();
            superuserDs = dataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword());
        }
        return superuserDs;
    }

    static synchronized DataSource migratorDataSource() {
        if (migratorDs == null) {
            migratorDs = dataSource(container().getJdbcUrl(), "sm_dp_migrator", MIGRATOR_PASSWORD);
        }
        return migratorDs;
    }

    static synchronized DataSource runtimeDataSource() {
        if (runtimeDs == null) {
            runtimeDs = dataSource(container().getJdbcUrl(), "sm_dp_runtime", RUNTIME_PASSWORD);
        }
        return runtimeDs;
    }

    static NamedParameterJdbcTemplate superuserJdbc() {
        return new NamedParameterJdbcTemplate(superuser());
    }

    static NamedParameterJdbcTemplate migratorJdbc() {
        return new NamedParameterJdbcTemplate(migratorDataSource());
    }

    static NamedParameterJdbcTemplate runtimeJdbc() {
        return new NamedParameterJdbcTemplate(runtimeDataSource());
    }

    /**
     * Executes {@code db/bootstrap/sm_data_phase0.sql} verbatim as the superuser, statement by
     * statement with {@link SqlStatements} — the same splitter {@link SketchMirrorTest} compares
     * with, so the script this runs is provably the script the design carries.
     *
     * <p>Per-statement, not as one batch: the design's own harness rule says a batched script
     * mis-attributes errors, and the {@code REVOKE}'s {@link java.sql.SQLWarning} has to be
     * attributable to the {@code REVOKE}.
     */
    static List<StatementOutcome> runPhase0() {
        return SqlScript.executeEach(superuser(), phase0Sql());
    }

    static String phase0Sql() {
        return DesignSketch.read(DesignSketch.repoRoot()
                .resolve("backend/dp-core/src/main/resources/" + SmDataMigrator.PHASE0_RESOURCE));
    }

    /**
     * Gives the two login roles passwords.
     *
     * <p>Separate from Phase 0 on purpose, and the separation is the design's, not the harness's:
     * §3.1 says LOGIN credentials are install-runbook material (pg_hba, managed secrets) and the
     * sketch deliberately carries none — but TCP connections need them, so the harness supplies its
     * own throwaway pair here rather than editing the script under test. DDL cannot bind parameters;
     * both values are compile-time constants of this class, never input.
     */
    static void assignTestPasswords() {
        SqlScript.execute(superuser(),
                "ALTER ROLE sm_dp_migrator PASSWORD '" + MIGRATOR_PASSWORD + "'");
        SqlScript.execute(superuser(),
                "ALTER ROLE sm_dp_runtime PASSWORD '" + RUNTIME_PASSWORD + "'");
    }

    /**
     * Phase 1, through the one-shot entrypoint the install runbook runs.
     *
     * <p>Deliberately {@link SmDataMigrationMain#run} and not {@code SmDataMigrator.migrate} directly:
     * this harness IS the runbook's stand-in, so every IT's bootstrap exercises the same call the
     * deployment makes. A migration path that only tests ever take is a migration path production has
     * never run.
     */
    static MigrateResult migrate() {
        return SmDataMigrationMain.run(container().getJdbcUrl(), "sm_dp_migrator", MIGRATOR_PASSWORD);
    }

    /** A tenant no other test has used — the isolation unit, since the container is shared. */
    static UUID freshTenant() {
        return UUID.randomUUID();
    }

    static SimpleDriverDataSource dataSource(String url, String user, String password) {
        return SmDataMigrationMain.dataSource(url, user, password);
    }

    /** One executed statement and whatever the server said about it. */
    record StatementOutcome(String sql, List<String> warnings) {
    }
}
```

`LogCapture.java` — a logback `ListAppender` on the root logger, so the sentinel tests can assert what the JVM *logged*, not only what it printed:

```java
package com.solace.sm.dp.apply.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/**
 * Everything the JVM LOGS while a block of code runs — not what it prints to the two streams the code
 * was handed.
 *
 * <p>The distinction is the whole point of the {@code I6} checks that use this. {@link
 * SmDataMigrationMain} controls its own two {@code PrintStream}s and can be held to "no URL, ever";
 * Flyway, HikariCP and Spring's {@code SimpleDriverDataSource} log through slf4j on their own account
 * and cannot be. Asserting only on the injected streams would therefore prove the weaker of the two
 * statements while looking like the stronger one. A {@code ListAppender} on the ROOT logger sees every
 * event from every library on the classpath, so what it returns is the honest answer to "did the URL
 * escape".
 *
 * <p>Root level is forced to TRACE for the duration so a library that only names the URL at DEBUG is
 * caught too, and restored afterwards.
 */
final class LogCapture {

    private LogCapture() {
    }

    /** One captured event, already formatted. */
    record Line(String level, String logger, String message) {

        @Override
        public String toString() {
            return level + " " + logger + " — " + message;
        }
    }

    /**
     * @param body the code to run with root logging captured
     * @return its value AND every event logged while it ran
     */
    static <T> Captured<T> during(Supplier<T> body) {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level original = root.getLevel();
        Collector collector = new Collector();
        collector.setContext(root.getLoggerContext());
        collector.start();
        root.addAppender(collector);
        root.setLevel(Level.TRACE);
        try {
            return new Captured<>(body.get(), List.copyOf(collector.lines));
        } finally {
            root.setLevel(original);
            root.detachAppender(collector);
            collector.stop();
        }
    }

    /** A result and the log it produced. */
    record Captured<T>(T value, List<Line> lines) {

        /** The formatted lines joined, for a substring assertion over the whole log. */
        String text() {
            StringBuilder b = new StringBuilder();
            for (Line l : lines) {
                b.append(l).append('\n');
            }
            return b.toString();
        }

        /** The first line containing {@code fragment}, or null. */
        String firstContaining(String fragment) {
            for (Line l : lines) {
                if (l.message().contains(fragment)) {
                    return l.toString();
                }
            }
            return null;
        }
    }

    private static final class Collector extends AppenderBase<ILoggingEvent> {

        private final List<Line> lines = new ArrayList<>();

        @Override
        protected synchronized void append(ILoggingEvent event) {
            // The formatted message AND the attached throwable chain: a URL-bearing Flyway or Hikari
            // exception logged as log.error(msg, ex) lives in the throwable, not the message, and a
            // capture that dropped it would be blind to exactly the leak it exists to catch (measured).
            StringBuilder text = new StringBuilder(event.getFormattedMessage());
            for (ch.qos.logback.classic.spi.IThrowableProxy t = event.getThrowableProxy(); t != null;
                    t = t.getCause()) {
                text.append(" | ").append(t.getClassName()).append(": ").append(t.getMessage());
            }
            lines.add(new Line(event.getLevel().toString(), event.getLoggerName(), text.toString()));
        }
    }
}
```

`Refusals.java` — refusal **by name**, per statement. This is the Task 1 stage of the file: Task 3 adds `assertViolation` (and its `assertNull` import) once `PgConstraintViolation` exists, so the class compiles here without referencing it:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.function.Executable;
import org.postgresql.util.PSQLException;

/**
 * Asserts a refusal <b>by name</b>, per statement.
 *
 * <p>The design's harness rule, measured and stated in §3.3: "every refusal is asserted per-statement
 * with its constraint/index/trigger NAME — a batched psql script mis-attributes errors (the execution
 * review's own first run read clean while an obligation was unproven)". The rule earned itself twice:
 * a {@code 42703} from a broken trigger function was once read as the refusal it was meant to prove,
 * and an {@code op_known} probe was refused by {@code apply_has_generation} instead because the probe
 * row violated both.
 *
 * <p>So: never {@code assertThrows(DataAccessException.class, …)} alone. SQLSTATE always; the
 * constraint name whenever PostgreSQL supplies one.
 */
final class Refusals {

    /** {@code unique_violation} — a UNIQUE constraint or a (partial) unique index. */
    static final String UNIQUE_VIOLATION = "23505";
    /** {@code check_violation}. */
    static final String CHECK_VIOLATION = "23514";
    /** {@code not_null_violation}. */
    static final String NOT_NULL_VIOLATION = "23502";
    /** {@code foreign_key_violation}. */
    static final String FOREIGN_KEY_VIOLATION = "23503";
    /** {@code insufficient_privilege} — the whole DCL half of §3.1 lands here. */
    static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** {@code raise_exception} — every PL/pgSQL {@code RAISE EXCEPTION} in §3.1. */
    static final String RAISE_EXCEPTION = "P0001";

    private Refusals() {
    }

    /**
     * Runs {@code e}, requires it to fail, and checks the PostgreSQL error.
     *
     * @param expectedSqlState          the five-character SQLSTATE, e.g. {@link #UNIQUE_VIOLATION}
     * @param expectedConstraintOrNull  the constraint/index name PostgreSQL must name, or null when
     *                                  the error carries none (privilege errors never do)
     * @return the unwrapped driver exception, so a caller can assert more
     */
    static PSQLException assertRefused(String expectedSqlState, String expectedConstraintOrNull,
            Executable e) {
        return assertRefused(expectedSqlState, expectedConstraintOrNull, e, null);
    }

    /**
     * The same, with a caller-supplied sentence saying WHICH of several probes this one is.
     *
     * @param because prefixed to every failure message; null for none
     */
    static PSQLException assertRefused(String expectedSqlState, String expectedConstraintOrNull,
            Executable e, String because) {
        String prefix = because == null ? "" : because + " — ";
        Throwable thrown = assertThrows(Throwable.class, e, prefix + "the statement must be REFUSED");
        PSQLException pg = unwrap(thrown);
        assertNotNull(pg, prefix + "expected a PSQLException in the cause chain, got: " + thrown);
        assertEquals(expectedSqlState, pg.getSQLState(),
                prefix + "wrong SQLSTATE — the statement failed, but not for the reason under test. "
                        + "Was: " + pg.getMessage());
        if (expectedConstraintOrNull != null) {
            assertNotNull(pg.getServerErrorMessage(), "no server error message to read a name from");
            assertEquals(expectedConstraintOrNull, pg.getServerErrorMessage().getConstraint(),
                    prefix + "wrong constraint name — a refusal that fires for the wrong reason is "
                            + "not a proof. Was: " + pg.getMessage());
        }
        return pg;
    }

    /** A PL/pgSQL {@code RAISE EXCEPTION}: no constraint name exists, so the message carries it. */
    static PSQLException assertRaised(String expectedMessageSubstring, Executable e) {
        PSQLException pg = assertRefused(RAISE_EXCEPTION, null, e);
        assertTrue(pg.getMessage().contains(expectedMessageSubstring),
                "the trigger must refuse with its own wording, or a different trigger fired. "
                        + "Expected to contain: " + expectedMessageSubstring + "\nWas: " + pg.getMessage());
        return pg;
    }

    static PSQLException unwrap(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PSQLException pg) {
                return pg;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }
}
```

- [ ] **Step 7: Write the failing test — `SmDataBootstrapIT` (obligations (s) and (l))**

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligation <b>(s)</b> and obligation <b>(l)</b> of design §3.3, executed: the exact two-phase
 * script runs green on a fresh PostgreSQL 14 under exactly the stated credentials, and the topology
 * it leaves behind is <i>asserted</i>, not assumed.
 *
 * <p>The obligation is written as "the migration suite's own bootstrap test … standing because
 * execution evidence does not transfer to text it never ran". This class is that test: the DDL under
 * assertion is the DDL that ships, mirrored to the design by {@link SketchMirrorTest}.
 *
 * <p>The negative control is the discriminating half. The {@code REVOKE} is a silent WARNING no-op
 * for anyone but the grantor, so a run that only checks for absence of exceptions cannot tell a
 * correct install from a broken one. Roles are cluster-scoped, so — per the design's own harness note
 * — the control runs the {@code REVOKE} <b>in isolation</b>, in a second database owned by a
 * non-superuser installer.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmDataBootstrapIT {

    @Test
    void theContainerRunsThePinnedPostgres14FloorAsABootstrapSuperuserOwningSchemaPublic() {
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();

        String version = su.queryForObject("SELECT version()", Map.of(), String.class);
        assertNotNull(version);
        assertTrue(version.startsWith("PostgreSQL 14."),
                "SM-D26 pins PostgreSQL 14 as the floor; every obligation is a claim about 14. Was: "
                        + version);

        // Phase 0's last statement is only executable by the grantor of public's PUBLIC grant. The
        // whole (s) obligation is meaningless unless the executing identity really is that role.
        assertEquals(Boolean.TRUE,
                su.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user",
                        Map.of(), Boolean.class),
                "Testcontainers' default user must be the bootstrap superuser");
        assertEquals(SmDataPostgres.container().getUsername(),
                su.queryForObject(
                        "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'public'",
                        Map.of(), String.class),
                "on PG 14 schema public is owned by the bootstrap superuser — that is WHY the REVOKE "
                        + "works here and not for an installer");
    }

    @Test
    void phase0RunsCleanAsTheSuperuserWithNoErrorAndNoWarning() {
        List<SmDataPostgres.StatementOutcome> outcomes = SmDataPostgres.phase0Outcomes();

        assertEquals(7, outcomes.size(),
                "Phase 0 is seven statements — the count evidence/ddl-execution-pg14.md §2.2 measured");
        for (SmDataPostgres.StatementOutcome o : outcomes) {
            assertTrue(o.warnings().isEmpty(),
                    "no statement of Phase 0 may warn when the executor is the bootstrap superuser; "
                            + o.sql() + " said " + o.warnings());
        }
    }

    @Test
    void afterPhase0TheRuntimeRoleCanNoLongerCreateAnythingInSchemaPublic() {
        SmDataPostgres.bootstrap();

        assertEquals(Boolean.FALSE,
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT has_schema_privilege('sm_dp_runtime', 'public', 'CREATE')",
                        Map.of(), Boolean.class),
                "obligation (s)'s literal assertion");

        // The behavioural half: the privilege function could be read wrong; the CREATE cannot.
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> SmDataPostgres.runtimeJdbc().getJdbcTemplate()
                        .execute("CREATE TABLE public.smuggled(x int)"));
    }

    @Test
    void aNonSuperuserInstallerGetsASilentWarningNoOpFromTheSameRevoke() {
        SmDataPostgres.bootstrap();
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();

        // Roles are CLUSTER-scoped, so Phase 0 cannot be re-run literally — it would abort at
        // CREATE ROLE (42710) before reaching the statement under test. The design's harness note
        // prescribes exactly this shape: the REVOKE alone, in a second database owned by a
        // non-superuser. Idempotent so a re-run of the suite in a warm container still works.
        if (Boolean.FALSE.equals(su.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sm_installer')",
                Map.of(), Boolean.class))) {
            SqlScript.execute(SmDataPostgres.superuser(), "CREATE ROLE sm_installer LOGIN CREATEROLE "
                    + "PASSWORD 'installer-test-pw'");
        }
        if (Boolean.FALSE.equals(su.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'smdb2')",
                Map.of(), Boolean.class))) {
            SqlScript.execute(SmDataPostgres.superuser(), "CREATE DATABASE smdb2 OWNER sm_installer");
        }

        DataSource installer = SmDataPostgres.dataSource(
                SmDataPostgres.container().getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/smdb2$1"),
                "sm_installer", "installer-test-pw");
        NamedParameterJdbcTemplate installerJdbc = new NamedParameterJdbcTemplate(installer);

        assertEquals(Boolean.FALSE,
                installerJdbc.queryForObject(
                        "SELECT rolsuper FROM pg_roles WHERE rolname = current_user",
                        Map.of(), Boolean.class),
                "the control is only a control if the executor is NOT a superuser");
        assertEquals("sm_installer",
                installerJdbc.queryForObject(
                        "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'smdb2'",
                        Map.of(), String.class),
                "…and does own the database");

        List<String> warnings =
                SqlScript.execute(installer, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");

        assertEquals(1, warnings.size(), "expected exactly one warning, got: " + warnings);
        assertTrue(warnings.get(0).contains("no privileges could be revoked"),
                "the no-op must announce itself as a WARNING and nothing stronger. Was: " + warnings);
        assertEquals(Boolean.TRUE,
                installerJdbc.queryForObject(
                        "SELECT has_schema_privilege('sm_dp_runtime', 'public', 'CREATE')",
                        Map.of(), Boolean.class),
                "the control MUST leave the privilege in place — that is what discriminates it from "
                        + "the superuser run, and what makes the positive assertion meaningful");
    }

    @Test
    void flywayAppliesExactlySixMigrationsIntoSmDataAndRecordsThemThere() {
        MigrateResult result = SmDataPostgres.bootstrap();

        assertEquals(6, result.migrationsExecuted,
                "the Phase-1 split is six migrations: " + result.migrations.stream()
                        .map(m -> m.version + " " + m.description).toList());
        assertEquals(List.of("001", "002", "003", "004", "005", "006"),
                result.migrations.stream().map((MigrateOutput m) -> m.version).toList(),
                "applied in version order");
        assertEquals(SmDataMigrator.SCHEMA, result.schemaName);

        assertEquals(SmDataMigrator.SCHEMA,
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE c.relname = 'flyway_schema_history'",
                        Map.of(), String.class),
                "defaultSchema(sm_data) must put the history table in sm_data, not in public");
        assertEquals("sm_dp_owner",
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT pg_get_userbyid(c.relowner) FROM pg_class c"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE c.relname = 'flyway_schema_history' AND n.nspname = :s",
                        new MapSqlParameterSource("s", SmDataMigrator.SCHEMA), String.class),
                "initSql must apply to the connection Flyway creates the history table on, or the "
                        + "history table is the one object the migrator owns");
    }

    @Test
    void everyObjectInSmDataIsOwnedBySmDpOwnerRatherThanByTheMigratorLogin() {
        SmDataPostgres.bootstrap();
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();

        List<Map<String, Object>> notOwner = su.queryForList(
                "SELECT c.relname, c.relkind, pg_get_userbyid(c.relowner) AS owner"
                        + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = :s AND c.relkind IN ('r','i')"
                        + "   AND pg_get_userbyid(c.relowner) <> 'sm_dp_owner'",
                new MapSqlParameterSource("s", SmDataMigrator.SCHEMA));
        assertTrue(notOwner.isEmpty(), "tables/indexes not owned by sm_dp_owner: " + notOwner);

        Integer tables = su.queryForObject(
                "SELECT count(*)::int FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = :s AND c.relkind = 'r'",
                new MapSqlParameterSource("s", SmDataMigrator.SCHEMA), Integer.class);
        assertEquals(6, tables, "five sm_data tables plus flyway_schema_history");

        List<Map<String, Object>> functions = su.queryForList(
                "SELECT p.proname, pg_get_userbyid(p.proowner) AS owner FROM pg_proc p"
                        + " JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = :s"
                        + " ORDER BY p.proname",
                new MapSqlParameterSource("s", SmDataMigrator.SCHEMA));
        assertEquals(List.of("forbid_terminal_regression", "instance_watermark_only_rises",
                        "placement_watermark_only_rises"),
                functions.stream().map(r -> (String) r.get("proname")).toList(),
                "three functions — one guard per table, never a shared TG_TABLE_NAME branch (§3.1)");
        for (Map<String, Object> f : functions) {
            assertEquals("sm_dp_owner", f.get("owner"), "function " + f.get("proname"));
        }

        assertEquals(List.of("apply_request_terminal_guard", "apply_scope_monotonic",
                        "placement_watermark_monotonic"),
                su.queryForList(
                        "SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = :s AND NOT t.tgisinternal ORDER BY t.tgname",
                        new MapSqlParameterSource("s", SmDataMigrator.SCHEMA), String.class));
    }

    @Test
    void theRuntimeRoleIsNotAnOwnerMemberAndCannotSetRoleToOne() {
        SmDataPostgres.bootstrap();

        assertEquals(Boolean.FALSE,
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT pg_has_role('sm_dp_runtime', 'sm_dp_owner', 'MEMBER')",
                        Map.of(), Boolean.class),
                "obligation (l): the topology must not drift silently");
        assertEquals(Boolean.TRUE,
                SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT pg_has_role('sm_dp_migrator', 'sm_dp_owner', 'MEMBER')",
                        Map.of(), Boolean.class),
                "…while the migrator IS a member, or Phase 1 could not have run");

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> SmDataPostgres.runtimeJdbc().getJdbcTemplate().execute("SET ROLE sm_dp_owner"));
    }

    @Test
    void migratingADatabaseThatSkippedPhase0FailsLoudlyRatherThanCreatingTheSchemaItself() {
        SmDataPostgres.bootstrap();
        NamedParameterJdbcTemplate su = SmDataPostgres.superuserJdbc();
        if (Boolean.FALSE.equals(su.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'smdb_nophase0')",
                Map.of(), Boolean.class))) {
            SqlScript.execute(SmDataPostgres.superuser(), "CREATE DATABASE smdb_nophase0");
        }

        DataSource noPhase0 = SmDataPostgres.dataSource(
                SmDataPostgres.container().getJdbcUrl()
                        .replaceFirst("/[^/?]+(\\?|$)", "/smdb_nophase0$1"),
                "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD);

        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> SmDataMigrator.migrate(noPhase0));

        // Measured shape, so the plan can quote it: Flyway logs a WARN about createSchemas being
        // false, then tries to create its history table in sm_data and dies on the driver's
        // 3F000. The top-level type is FlywaySqlScriptException whose message embeds the SQLSTATE;
        // the root cause is a PSQLException reading 'schema "sm_data" does not exist'.
        assertTrue(failure.getMessage().contains("3F000"),
                "expected SQLSTATE 3F000 (invalid_schema_name) in the message. Was: "
                        + failure.getClass().getName() + ": " + failure.getMessage());
        assertTrue(failure.getMessage().contains("schema \"" + SmDataMigrator.SCHEMA
                        + "\" does not exist"),
                "the failure must name the missing schema so an operator knows Phase 0 was skipped. "
                        + "Was: " + failure.getMessage());
        assertEquals("3F000", Refusals.unwrap(failure).getSQLState(),
                "…and the root cause must be the driver error, not a Flyway-side guess");
        assertFalse(Boolean.TRUE.equals(new NamedParameterJdbcTemplate(noPhase0).queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = :s)",
                        new MapSqlParameterSource("s", SmDataMigrator.SCHEMA), Boolean.class)),
                "createSchemas(false) must not have created sm_data on the way to failing");
    }
}
```

- [ ] **Step 7b: `JdbcUrlsTest`, `SmDataMigrationMainTest` and `SmDataPropertiesTest` — the location grammar, exit codes and the two URL doors, with sentinels (surefire)**

`JdbcUrlsTest.java` — the grammar on its own: every allowlisted name resolves via `PGProperty.forName` (so the allowlist is verified against the driver, not recalled — `I2`), the URL forms an installer writes are accepted, and each refusal is checked for two invariants — no planted sentinel and no `jdbc:` in the message:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.PGProperty;

/**
 * {@link JdbcUrls}' location grammar, both halves: what an installer may write, and everything else.
 *
 * <p>The negative cases each plant a SENTINEL where a secret would go — a value, an unknown parameter's
 * NAME, the userinfo — and assert the refusal names the rule without the sentinel. The positive cases
 * are the URLs the install runbook and the integration harness actually produce.
 */
class JdbcUrlsTest {

    private static final String SENTINEL = "SNTL-url-6b1e";

    // -------------------------------------------------------------------------------------------
    // The allowlist is made of real pgjdbc property names — verified against the driver, not recalled.
    // -------------------------------------------------------------------------------------------

    @Test
    void everyAllowlistedParameterIsAPgjdbcPropertyUnderExactlyThatName() {
        for (String name : JdbcUrls.ALLOWED.keySet()) {
            PGProperty property = PGProperty.forName(name);
            assertNotNull(property, "'" + name + "' is not a pgjdbc property — the allowlist would "
                    + "admit a parameter the driver ignores, and the runbook would set it in vain");
            assertTrue(name.equals(property.getName()),
                    "'" + name + "' differs in case from pgjdbc's '" + property.getName() + "'");
        }
        System.out.println("MEASURED: allowlist " + JdbcUrls.ALLOWED.keySet()
                + " all resolve via PGProperty.forName");
    }

    // -------------------------------------------------------------------------------------------
    // Accepted: locations.
    // -------------------------------------------------------------------------------------------

    @Test
    void theLocationFormsAnInstallerWritesAreAccepted() {
        for (String url : List.of(
                "jdbc:postgresql://db/sm",
                "jdbc:postgresql://db.example.internal:5432/sm_data",
                "jdbc:postgresql://10.0.0.7:5432/sm",
                "jdbc:postgresql://[::1]:5432/sm",
                "jdbc:postgresql://db1:5432,db2:5432/sm",
                "jdbc:postgresql://db:5432/sm?sslmode=verify-full&sslrootcert=/etc/sm/ca.pem",
                "jdbc:postgresql://db:5432/sm?sslmode=verify-full&sslcert=/etc/sm/tls.crt"
                        + "&sslkey=/etc/sm/tls.pk8",
                "jdbc:postgresql://db:5432/sm?ApplicationName=solution-manager.dp"
                        + "&connectTimeout=10&socketTimeout=30&loginTimeout=10&tcpKeepAlive=true")) {
            assertDoesNotThrow(() -> JdbcUrls.requireLocation(url), url);
        }
    }

    @Test
    void nullAndBlankPassThroughBecauseTheyAreTheCallersOwnCheck() {
        assertDoesNotThrow(() -> JdbcUrls.requireLocation(null));
        assertDoesNotThrow(() -> JdbcUrls.requireLocation("  "));
    }

    // -------------------------------------------------------------------------------------------
    // Refused: everything that is not a location — and the refusal never repeats the input.
    // -------------------------------------------------------------------------------------------

    @Test
    void theThreeCredentialParametersTheFirstDraftBlocklistedAreStillRefusedNowByAbsenceFromTheAllowlist() {
        for (String url : List.of(
                "jdbc:postgresql://db:5432/sm?password=" + SENTINEL,
                "jdbc:postgresql://db:5432/sm?user=" + SENTINEL,
                "jdbc:postgresql://db:5432/sm?sslmode=require&sslpassword=" + SENTINEL,
                "jdbc:postgresql://db:5432/sm?PASSWORD=" + SENTINEL)) {
            String message = refusalFor(url);
            assertTrue(message.contains("not in the allowlist"), message);
        }
    }

    @Test
    void aParameterTheDriverAcceptsButTheAllowlistDoesNotIsRefusedByPositionAndItsNameIsNotRepeated() {
        // `options=` is the review's example: a free-text payload pgjdbc forwards to the server.
        String message = refusalFor("jdbc:postgresql://db:5432/sm?sslmode=require&options=" + SENTINEL);
        assertTrue(message.contains("position 2"), "the refusal locates the parameter. Was: " + message);
        assertFalse(message.contains("options"),
                "…without naming it — a NAME can carry a secret too. Was: " + message);

        // And a parameter whose NAME is the secret.
        message = refusalFor("jdbc:postgresql://db:5432/sm?" + SENTINEL + "=1");
        assertTrue(message.contains("position 1"), message);
    }

    @Test
    void anAllowlistedParameterWithAValueOutsideItsGrammarIsRefusedNamingTheParameterButNotTheValue() {
        Map<String, String> outsideGrammar = Map.of(
                "sslmode", SENTINEL,
                "sslrootcert", "relative/" + SENTINEL,
                "sslkey", "/etc/sm/with space " + SENTINEL,
                "ApplicationName", "has spaces " + SENTINEL,
                "connectTimeout", "10s" + SENTINEL,
                "tcpKeepAlive", "yes" + SENTINEL);
        for (Map.Entry<String, String> e : outsideGrammar.entrySet()) {
            String message = refusalFor("jdbc:postgresql://db:5432/sm?" + e.getKey() + "=" + e.getValue());
            assertTrue(message.contains("'" + e.getKey() + "' value"),
                    "the refusal names the allowlisted parameter. Was: " + message);
        }
    }

    @Test
    void userinfoIsRefusedBecauseTheHostGrammarHasNoPlaceForIt() {
        String message = refusalFor("jdbc:postgresql://alice:" + SENTINEL + "@db:5432/sm");
        assertTrue(message.contains("host part"), message);
        assertFalse(message.contains("alice"), "the role is half a credential. Was: " + message);
    }

    @Test
    void theOtherPgjdbcUrlFormsAndOtherSchemesAreRefused() {
        for (String url : List.of(
                "jdbc:postgresql:sm",                         // pgjdbc's host-less short form
                "jdbc:postgresql:/",                          // …and its bare form
                "jdbc:postgresql://db:5432",                  // no database
                "jdbc:postgresql://db:5432/sm/" + SENTINEL,   // a path after the database
                "jdbc:postgresql://db:5432/sm?sslmode",       // a parameter without '='
                "jdbc:postgresql://db:5432/sm?",              // an empty query — pgjdbc tolerates it, the grammar does not
                "jdbc:postgresql://db:5432/sm?sslmode=require;password=" + SENTINEL, // ';' is not a separator
                "postgresql://db:5432/sm",                    // libpq's URI, not JDBC's
                "jdbc:nope://x/y",
                "jdbc:h2:mem:" + SENTINEL)) {
            refusalFor(url);
        }
    }

    /** Asserts refusal and the two invariants every refusal shares: no sentinel, no {@code jdbc:}. */
    private static String refusalFor(String url) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JdbcUrls.requireLocation(url), "must be refused: " + url);
        String message = e.getMessage();
        assertFalse(message.contains(SENTINEL),
                "the refusal echoed the planted value. Was: " + message);
        assertFalse(message.contains("jdbc:"),
                "the refusal must not carry a URL — nor the literal scheme, so that every stream the "
                        + "migration step prints can be held to 'no jdbc: at all'. Was: " + message);
        assertTrue(message.contains("location only") && message.contains("I6"),
                "the refusal names the rule. Was: " + message);
        return message;
    }
}
```

`SmDataPropertiesTest.java`:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SmDataProperties.Credentials} as the {@code I6} gate on the APPLICATION's runtime URL.
 *
 * <p>{@link SmDataMigrationMainTest} covers the migration step's environment; this covers the other
 * door a URL comes through. The two are not the same check written twice: the migration step reads an
 * environment variable and exits, while this one runs at Spring bind time, before
 * {@link ApplyPersistenceConfiguration}'s Hikari pool exists — and Hikari names the URL in its own
 * pool-startup and connection-failure diagnostics, so "refused at bind time" is what keeps anything
 * but a bare location from being logged by a library this code does not control.
 */
class SmDataPropertiesTest {

    private static final String SENTINEL = "SENTINEL-PROPS-PW-4b6d";

    @Test
    void aRuntimeUrlCarryingAPasswordParameterIsRefusedAtBindTimeWithoutEchoingIt() {
        String url = "jdbc:postgresql://db.example:5432/sm?password=" + SENTINEL;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmDataProperties.Credentials(url, "sm_dp_runtime", "pw"));

        System.out.println("MEASURED: ?password= refusal = " + e.getMessage());
        assertTrue(e.getMessage().contains("position 1") && e.getMessage().contains("allowlist"),
                "the refusal locates the parameter and names the rule. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains(SENTINEL), e.getMessage());
        assertFalse(e.getMessage().contains(url), e.getMessage());
    }

    @Test
    void aRuntimeUrlCarryingUserinfoIsRefusedAtBindTimeWithoutEchoingIt() {
        String url = "jdbc:postgresql://alice:" + SENTINEL + "@db.example/sm";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmDataProperties.Credentials(url, "sm_dp_runtime", "pw"));

        System.out.println("MEASURED: userinfo refusal = " + e.getMessage());
        assertTrue(e.getMessage().contains("userinfo"), e.getMessage());
        assertFalse(e.getMessage().contains(SENTINEL), e.getMessage());
        assertFalse(e.getMessage().contains(url), e.getMessage());
        assertFalse(e.getMessage().contains("alice"), e.getMessage());
    }

    @Test
    void aRuntimeUrlCarryingAParameterOutsideTheAllowlistIsRefusedAtBindTimeWithoutEchoingIt() {
        // The review's second pass: `options=` is a real pgjdbc property and a free-text one. Under the
        // first draft's blocklist it was admitted, and Hikari would have named the URL in its diagnostics.
        String url = "jdbc:postgresql://db.example:5432/sm?connectTimeout=5&options=" + SENTINEL;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmDataProperties.Credentials(url, "sm_dp_runtime", "pw"));

        System.out.println("MEASURED: ?options= refusal = " + e.getMessage());
        assertTrue(e.getMessage().contains("position 2") && e.getMessage().contains("allowlist"),
                e.getMessage());
        assertFalse(e.getMessage().contains("options"), "the NAME is not repeated. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains(SENTINEL), e.getMessage());
        assertFalse(e.getMessage().contains(url), e.getMessage());
    }

    @Test
    void aUrlWithAllowlistedParametersIsAcceptedBecauseARuleThatRefusedEveryParameterWouldBeUnusable() {
        String url = "jdbc:postgresql://h:5432/db?sslmode=verify-full&sslrootcert=/etc/sm/ca.pem"
                + "&connectTimeout=5";
        SmDataProperties.Credentials c = new SmDataProperties.Credentials(url, "sm_dp_runtime", "pw");
        assertEquals(url, c.url(),
                "the positive control: without it the refusals above would be tested green by a rule "
                        + "that refused everything");
    }

    @Test
    void bothHalvesOfTheCredentialAreRedactedByToStringAndTheUrlIsNot() {
        SmDataProperties.Credentials c = new SmDataProperties.Credentials(
                "jdbc:postgresql://h:5432/db", "sm_dp_runtime", SENTINEL);

        System.out.println("MEASURED: Credentials.toString() = " + c);
        assertFalse(c.toString().contains(SENTINEL),
                "a record's generated toString prints every component — this one is overridden for "
                        + "exactly that reason. Was: " + c);
        assertTrue(c.toString().contains("password=****") && c.toString().contains("username=****"),
                c.toString());
        assertFalse(c.toString().contains("sm_dp_runtime"),
                "the ROLE is redacted too — JdbcUrls calls it half of a credential, and this record "
                        + "must not say otherwise. Was: " + c);
        assertTrue(c.toString().contains("jdbc:postgresql://h:5432/db"),
                "the URL stays: a location by construction, and what a diagnosis needs");
    }
}
```

`SmDataMigrationMainTest.java` — every failure path with a sentinel in the URL, the username and the password; neither captured stream nor the captured log may contain a credential; the unreachable-host case is a real connection attempt (measured 131–148 ms to `08001`, capped at 10 s):

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SmDataMigrationMain}'s environment contract, without a database.
 *
 * <p>{@code main} itself calls {@link System#exit}, which a test cannot let happen, so the class
 * delegates to a package-private {@link SmDataMigrationMain#exitCode(Map, PrintStream, PrintStream)}
 * and that is what is measured here. Both streams are injected rather than swapped out from under
 * {@link System}, so a test reads exactly the bytes the step emitted and nothing a neighbouring test
 * happened to print.
 *
 * <p>Two families of case. The first is what a runbook can get wrong before a connection is attempted:
 * a missing variable, and — the external review's finding 1, tightened by its second pass — a URL that is
 * not a bare location: one carrying the credential that is supposed to live in the other two variables,
 * or carrying ANY parameter outside {@link JdbcUrls}' allowlist, since whatever the URL holds is logged. The second is what happens when the connection is
 * attempted and fails: the assertion that matters there is negative, and it is checked against
 * SENTINEL values planted in the environment. Nothing the step prints may contain the password, the
 * username, the host, or the string {@code jdbc:}.
 *
 * <p>Every failure case also captures the JVM's own LOG through {@link LogCapture}, because the step's
 * two streams are only half the surface: Flyway, Hikari and Spring's {@code SimpleDriverDataSource} log
 * on their own account. The split there is measured, not assumed. The two CREDENTIALS never reach a
 * logger. The URL does — {@code SimpleDriverDataSource} names it at DEBUG on the connect attempt, and
 * Flyway names it at INFO on the success path ({@link ApplyPersistenceConfigurationIT} measures that
 * one) — and neither is suppressed, because silencing a library would hide the exposure rather than
 * remove it. Removing it is {@link JdbcUrls}' job: since the URL will be logged, it may not carry a
 * credential.
 */
class SmDataMigrationMainTest {

    private static final String PASSWORD = "s3cr3t-migrator-pw";

    /** Planted in the environment of the connect-failure case and hunted for in every output. */
    private static final String SENTINEL_HOST = "sentinel-host-2e4d.invalid";
    private static final String SENTINEL_USER = "SENTINEL-USER-5d8e";
    private static final String SENTINEL_CONNECT_PW = "SENTINEL-PW-0a2f";

    // -------------------------------------------------------------------------------------------
    // Before a connection: missing variables.
    // -------------------------------------------------------------------------------------------

    @Test
    void aMissingUrlExitsTwoAndNamesTheVariableWithoutEchoingThePassword() {
        Map<String, String> env = complete();
        env.remove(SmDataMigrationMain.URL_VARIABLE);
        assertMissing(SmDataMigrationMain.URL_VARIABLE, env);
    }

    @Test
    void aMissingUsernameExitsTwoAndNamesTheVariableWithoutEchoingThePassword() {
        Map<String, String> env = complete();
        env.remove(SmDataMigrationMain.USERNAME_VARIABLE);
        assertMissing(SmDataMigrationMain.USERNAME_VARIABLE, env);
    }

    @Test
    void aBlankPasswordCountsAsMissingBecauseAnEmptySecretIsAlmostAlwaysAnUnsetOne() {
        Map<String, String> env = complete();
        env.put(SmDataMigrationMain.PASSWORD_VARIABLE, "   ");
        assertMissing(SmDataMigrationMain.PASSWORD_VARIABLE, env);
    }

    // -------------------------------------------------------------------------------------------
    // Before a connection: a URL that carries what the other two variables are for.
    // -------------------------------------------------------------------------------------------

    @Test
    void aUrlCarryingAPasswordParameterIsRefusedBeforeConnectingAndTheSentinelIsPrintedNowhere() {
        String sentinel = "SENTINEL-PW-9f3a";
        String url = "jdbc:postgresql://db.example:5432/sm?password=" + sentinel;
        Run run = run(withUrl(url));

        System.out.println("MEASURED: ?password= URL → exit " + run.code + ", stderr: " + run.stderr());
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code,
                "a credential-bearing URL is a configuration error (2), not a migration failure (1)");
        assertTrue(run.stderr().contains(SmDataMigrationMain.URL_VARIABLE)
                        && run.stderr().contains(SmDataMigrationMain.PASSWORD_VARIABLE),
                "the line must say where the credential belongs instead. Was: " + run.stderr());
        assertNowhere(run, sentinel, url);
    }

    @Test
    void aUrlCarryingAnSslpasswordParameterIsRefusedTooBecauseItIsARealPgjdbcCredential() {
        // Measured (review of plan 004): `sslpassword` is PGProperty.SSL_PASSWORD, the client-key
        // passphrase — a credential the first draft of JdbcUrls did not name, and one Flyway would
        // have logged at INFO inside the URL.
        String sentinel = "SENTINEL-SSLPW-4b6c";
        String url = "jdbc:postgresql://db.example:5432/sm?ssl=true&SSLPASSWORD=" + sentinel;
        Run run = run(withUrl(url));

        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertNowhere(run, sentinel, url);
    }

    @Test
    void aUrlCarryingUserinfoBeforeTheHostIsRefusedBeforeConnectingAndTheSentinelIsPrintedNowhere() {
        String sentinel = "SENTINEL-UI-7c1b";
        String url = "jdbc:postgresql://alice:" + sentinel + "@db.example/sm";
        Run run = run(withUrl(url));

        System.out.println("MEASURED: userinfo URL → exit " + run.code + ", stderr: " + run.stderr());
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code);
        assertTrue(run.stderr().contains(SmDataMigrationMain.URL_VARIABLE), run.stderr());
        assertNowhere(run, sentinel, url);
        assertFalse(run.stderr().contains("alice"),
                "the role in the userinfo is half a credential too. Was: " + run.stderr());
    }

    @Test
    void aUrlCarryingAParameterOutsideTheAllowlistIsRefusedAndNeitherItsNameNorItsValueIsPrinted() {
        // The external review's second-pass finding: a blocklist of three parameter names admitted a
        // value smuggled into any OTHER pgjdbc property — `options=` being the driver's free-text one —
        // which Flyway would then have logged at INFO. The grammar admits only allowlisted names.
        String value = "SENTINEL-OPT-3e9d";
        String url = "jdbc:postgresql://db.example:5432/sm?sslmode=require&options=" + value;
        Run run = run(withUrl(url));

        System.out.println("MEASURED: ?options= URL → exit " + run.code + ", stderr: " + run.stderr());
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains("position 2") && run.stderr().contains("allowlist"),
                "the line locates the parameter and names the rule. Was: " + run.stderr());
        assertFalse(run.stderr().contains("options"),
                "…and does not repeat the parameter's NAME, which can carry a secret as well as a "
                        + "value. Was: " + run.stderr());
        assertNowhere(run, value, url);

        // The name-is-the-secret shape.
        String name = "SENTINEL-NAME-8c47";
        url = "jdbc:postgresql://db.example:5432/sm?" + name + "=1";
        run = run(withUrl(url));
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertNowhere(run, name, url);
    }

    @Test
    void anAllowlistedParameterWithAValueOutsideItsGrammarIsRefusedWithoutPrintingTheValue() {
        String value = "SENTINEL-MODE-1f0b";
        String url = "jdbc:postgresql://db.example:5432/sm?sslmode=" + value;
        Run run = run(withUrl(url));

        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains("'sslmode' value"),
                "an ALLOWLISTED name is this code's own word and may be printed. Was: " + run.stderr());
        assertNowhere(run, value, url);
    }

    // -------------------------------------------------------------------------------------------
    // The connection itself fails — Flyway's own message names the URL and the role, so it is never
    // printed. This is the case the whole refactor exists for.
    // -------------------------------------------------------------------------------------------

    @Test
    void anUnreachableHostExitsOneWithClassifiersOnlyAndLeaksNeitherCredentialNorUrl() {
        Map<String, String> env = new LinkedHashMap<>();
        // connectTimeout is a URL PARAMETER, not a credential: it bounds this test rather than
        // relying on the resolver failing a .invalid lookup quickly.
        env.put(SmDataMigrationMain.URL_VARIABLE,
                "jdbc:postgresql://" + SENTINEL_HOST + ":1/sm?connectTimeout=2");
        env.put(SmDataMigrationMain.USERNAME_VARIABLE, SENTINEL_USER);
        env.put(SmDataMigrationMain.PASSWORD_VARIABLE, SENTINEL_CONNECT_PW);

        long startedAt = System.nanoTime();
        Run run = run(env);
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        System.out.println("MEASURED: unreachable host → exit " + run.code + " in " + took.toMillis()
                + " ms, stderr: " + run.stderr());
        System.out.println("MEASURED: JVM log during the failure (" + run.log.lines().size()
                + " events); first line naming a jdbc: URL = " + run.log.firstContaining("jdbc:"));

        assertEquals(SmDataMigrationMain.EXIT_FAILED, run.code,
                "an unreachable database IS a migration failure (1) — a runbook may retry it");
        assertTrue(took.compareTo(Duration.ofSeconds(10)) < 0,
                "the failure must be bounded; took " + took.toMillis() + " ms");
        assertTrue(run.stderr().contains(SmDataMigrationMain.NO_MESSAGE_TEXT.strip()),
                "the line must say WHY it is thin, or the next reader deletes the classifier. Was: "
                        + run.stderr());
        assertTrue(run.stderr().contains("SQLSTATE") || run.stderr().matches("(?s).*Exception.*"),
                "the line must carry a SQLSTATE or the exception's simple name. Was: " + run.stderr());

        for (String sentinel : List.of(SENTINEL_HOST, SENTINEL_USER, SENTINEL_CONNECT_PW)) {
            assertFalse(run.stdout().contains(sentinel) || run.stderr().contains(sentinel),
                    "the step printed " + sentinel + ". out=" + run.stdout() + " err=" + run.stderr());
        }
        assertFalse(run.stderr().contains("jdbc:") || run.stdout().contains("jdbc:"),
                "no JDBC URL reaches either stream — Flyway's own failure message would carry one, "
                        + "which is why the exception is classified rather than printed. Was: "
                        + run.stderr());

        // The LOG is a different surface, and the split below is the measured truth rather than the
        // convenient one. The two CREDENTIALS are absent from it — nothing hands them to a logger.
        for (String credential : List.of(SENTINEL_USER, SENTINEL_CONNECT_PW)) {
            assertFalse(run.log.text().contains(credential),
                    "a library LOGGED the " + (credential.equals(SENTINEL_USER) ? "role" : "password")
                            + ": " + run.log.firstContaining(credential));
        }
        // The URL is NOT absent, and this build does not pretend otherwise. Measured: Spring's
        // SimpleDriverDataSource prints it at DEBUG on the connect attempt, and Flyway prints it at
        // INFO on the success path (ApplyPersistenceConfigurationIT measures that one). Neither is
        // suppressed — silencing them would hide the exposure instead of removing it. What removes
        // it is JdbcUrls: since the URL WILL be logged, the URL may not carry a credential.
        String logged = run.log.firstContaining(SENTINEL_HOST);
        assertNotNull(logged,
                "if no library logs the URL any more, this assertion should be inverted "
                        + "deliberately — JdbcUrls' justification rests on the measurement");
        assertTrue(logged.startsWith("DEBUG"),
                "…and it is DEBUG-level here, i.e. below a production root level. Was: " + logged);
    }

    @Test
    void anUnknownUrlSchemeExitsTwoAsAMalformedLocationBeforeAnyDriverIsConsulted() {
        Map<String, String> env = withUrl("jdbc:nope://x/y");
        Run run = run(env);

        System.out.println("MEASURED: jdbc:nope → exit " + run.code + ", stderr: " + run.stderr());
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code,
                "a URL outside the location grammar is a configuration error (2): the variable IS set, "
                        + "it just does not name a PostgreSQL location");
        assertTrue(run.stderr().contains("scheme other than pgjdbc's"),
                "…and it must say which part of the grammar was missed. Was: " + run.stderr());
        assertFalse(run.stderr().contains("jdbc:nope") || run.stderr().contains("jdbc:"), run.stderr());
        assertFalse(run.stderr().contains(PASSWORD), run.stderr());
    }

    @Test
    void theDriverResolutionFailureIsStillReachableAndStillNamesNoUrl() {
        // JdbcUrls now refuses every non-pgjdbc scheme before this code runs, so the only way to reach
        // it in production is a distribution built without the driver. It stays, and stays URL-free.
        SmDataMigrationMain.NoDriverRegistered e = assertThrows(
                SmDataMigrationMain.NoDriverRegistered.class,
                () -> SmDataMigrationMain.dataSource("jdbc:nope://" + SENTINEL_HOST + "/y",
                        SENTINEL_USER, SENTINEL_CONNECT_PW));
        assertFalse(e.getMessage().contains("jdbc:") || e.getMessage().contains(SENTINEL_HOST),
                e.getMessage());
    }

    // -------------------------------------------------------------------------------------------

    private static Map<String, String> complete() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmDataMigrationMain.URL_VARIABLE, "jdbc:postgresql://db:5432/sm");
        env.put(SmDataMigrationMain.USERNAME_VARIABLE, "sm_dp_migrator");
        env.put(SmDataMigrationMain.PASSWORD_VARIABLE, PASSWORD);
        return env;
    }

    private static Map<String, String> withUrl(String url) {
        Map<String, String> env = complete();
        env.put(SmDataMigrationMain.URL_VARIABLE, url);
        return env;
    }

    /** Both injected streams, the exit code, and everything the JVM logged while it ran. */
    private record Run(int code, ByteArrayOutputStream out, ByteArrayOutputStream err,
            LogCapture.Captured<Integer> log) {

        String stdout() {
            return out.toString(StandardCharsets.UTF_8).strip();
        }

        String stderr() {
            return err.toString(StandardCharsets.UTF_8).strip();
        }
    }

    private static Run run(Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        LogCapture.Captured<Integer> log =
                LogCapture.during(() -> SmDataMigrationMain.exitCode(env, outStream, errStream));
        return new Run(log.value(), out, err, log);
    }

    /** The sentinel appears in neither stream, nor does the URL that carried it. */
    private static void assertNowhere(Run run, String sentinel, String url) {
        for (String printed : List.of(run.stdout(), run.stderr())) {
            assertFalse(printed.contains(sentinel),
                    "the credential inside the URL must never be printed. Was: " + printed);
            assertFalse(printed.contains(url),
                    "…and neither must the URL that carried it. Was: " + printed);
            assertFalse(printed.contains("jdbc:"), "no URL at all. Was: " + printed);
        }
        assertFalse(run.log.text().contains(sentinel),
                "nothing may LOG it either — the refusal happens before any library sees the URL. "
                        + "Was: " + run.log.firstContaining(sentinel));
    }

    /** Runs {@code exitCode} and reads both the code and what was printed. */
    private static void assertMissing(String variable, Map<String, String> env) {
        Run run = run(env);
        System.out.println("MEASURED: missing " + variable + " → exit " + run.code + ", stderr: "
                + run.stderr());

        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code,
                "a missing variable is a configuration error (2), not a migration failure (1) — a "
                        + "runbook that retries on 1 must not retry on this");
        assertTrue(run.stderr().contains(variable),
                "the message must name the variable that is missing. Was: " + run.stderr());
        assertFalse(run.stderr().contains(PASSWORD),
                "the failure must not echo the migrator password — the whole point of finding 1 is "
                        + "that this secret exists in as few places as possible. Was: " + run.stderr());
    }
}
```

Run: `mvn -f backend/pom.xml -pl dp-core -am -q test -Dtest='JdbcUrlsTest,SmDataMigrationMainTest,SmDataPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 8 + 11 + 5 green. Measured stderr lines (stdout empty in every failure case): missing variable → exit 2, `SM_DATA_MIGRATOR_URL is not set — the sm_data migration step reads its credential from the environment, and will not guess one`; URL with `?password=SENTINEL-PW-9f3a`, with `&SSLPASSWORD=SENTINEL-SSLPW-4b6c`, with `&options=SENTINEL-OPT-3e9d` or with a parameter whose *name* is the sentinel → exit 2, `SM_DATA_MIGRATOR_URL is not a bare location: a JDBC URL must be a location only — pgjdbc's scheme, then host[:port]/database, optionally ?name=value pairs from the allowlist ApplicationName, connectTimeout, loginTimeout, socketTimeout, sslcert, sslkey, sslmode, sslrootcert, tcpKeepAlive — found a query parameter at position N that is not in the allowlist (its name is deliberately not repeated). … The credential belongs in SM_DATA_MIGRATOR_USERNAME / SM_DATA_MIGRATOR_PASSWORD`; `?sslmode=SENTINEL-MODE-1f0b` → exit 2, `… found a 'sslmode' value outside that parameter's grammar (the value is deliberately not repeated) …`; userinfo `alice:SENTINEL-UI-7c1b@` → exit 2, `… found a host part that is not host[:port] (userinfo, an unbracketed IPv6 address or a stray character) …`; `jdbc:nope://x/y` → exit **2** (a malformed location, refused before any driver is consulted), `… found a scheme other than pgjdbc's …`; unreachable host → exit 1, `the sm_data migration failed: FlywaySqlException (SQLSTATE 08001) — no message text is printed because Flyway's includes the connection URL; diagnose with psql using the same credential`. In none of them does a sentinel, an unrecognised parameter name, the host or `jdbc:` appear in either stream — the refusal describes the scheme rather than spelling it, precisely so the streams can be held to "no `jdbc:` at all". `NoDriverRegistered` stays reachable only for a distribution built without the driver and is tested directly (`dataSource("jdbc:nope://…")`), still URL-free. **Stated limit, measured:** Spring's `SimpleDriverDataSource` logs the full URL (host included, no credential) at DEBUG on the connect attempt and Flyway logs it at INFO on success — the tests assert the *credentials* are absent from the log — **including from the throwable chain attached to logged events** (`LogCapture` records `getThrowableProxy()` as well as the message; a capture that only read the formatted message was measured blind to a URL-bearing exception logged as `log.error(msg, ex)`) — and that the URL line IS present, so an inversion of that posture must be deliberate; it is why the URL may carry nothing but a location.

- [ ] **Step 8: Red first — run before writing the SQL files**

Do Steps 1, 3–7b but **not** Step 2 yet, then:
Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test=SmDataBootstrapIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
Expected (measured): `Tests run: 8, Failures: 0, Errors: 7` — every test that calls `bootstrap()` errors with `UncheckedIOException: java.nio.file.NoSuchFileException: …/backend/dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql` (the harness reads the *shipped* file, not the design, so the missing file is the red); only `theContainerRunsThePinnedPostgres14Floor…` passes, since it needs no SQL. Confirm the failure is that, not a Docker or dependency error.

- [ ] **Step 9: Green — extract the SQL (Step 2) and re-run**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify`
Expected: BUILD SUCCESS; surefire dp-core **65** (41 + `JdbcUrlsTest` 8 + `SmDataMigrationMainTest` 11 + `SmDataPropertiesTest` 5); failsafe `Tests run: 10, Failures: 0, Errors: 0, Skipped: 2` — `SmDataBootstrapIT` **8** run (~9 s: it starts the container), the two broker ITs skipped. Confirm in `backend/dp-core/target/failsafe-reports/TEST-com.solace.sm.dp.apply.persistence.SmDataBootstrapIT.xml` that `tests="8" skipped="0"`. Measured facts the run must reproduce: the container's `version()` starts `PostgreSQL 14.`; 7 Phase-0 statements, zero `SQLWarning`s; `has_schema_privilege('sm_dp_runtime','public','CREATE')` false; the installer's `REVOKE` yields exactly one warning containing `no privileges could be revoked`; Flyway executes versions `001`–`006`, `flyway_schema_history` in `sm_data` owned by `sm_dp_owner`; three functions, three triggers; `pg_has_role(runtime, owner)` false; the no-Phase-0 migrate fails with `3F000` naming `schema "sm_data" does not exist`.

- [ ] **Step 10: Prove it fires — `createSchemas`**

Change `.createSchemas(false)` to `.createSchemas(true)` in `SmDataMigrator`, re-run the Step 8 command.
Expected (measured): exactly one red — `migratingADatabaseThatSkippedPhase0FailsLoudlyRatherThanCreatingTheSchemaItself`, with `expected SQLSTATE 3F000 … Was: org.flywaydb.core.internal.exception.FlywaySqlException: Unable to create schema "sm_data"` (the owner role holds no `CREATE` on the database, so Flyway's own attempt fails — a *different* loud failure, which is why the test asserts the SQLSTATE and the message rather than "some exception"). Revert; re-run; green.

- [ ] **Step 11: Commit**

```bash
git add backend/dp-core/pom.xml backend/dp-core/src/main/resources/db backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/SmDataMigrator.java backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/SmDataMigrationMain.java backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/JdbcUrls.java backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/SmDataProperties.java backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/ApplyPersistenceConfiguration.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence
git commit -m "Land the sm_data two-phase bootstrap as Flyway migrations behind a PG-14 harness (Task 1)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

**Risks:** the readiness check pins table **presence**, not a schema version — the runtime role cannot read `flyway_schema_history` (design §3.1), so a future migration that only `ALTER`s an existing table passes it; when migrations grow, the plan that grows them should have the migration step write a runtime-readable marker rather than widen the grant. Docker host socket split (see Preconditions — measured, mitigated by the env var, not by the POM). Three PostgreSQL/Flyway facts the run relies on silently, stated so they are not assumptions: the `REVOKE` is a *warning* no-op only for a role that holds SOME privilege on `public` (the installer holds the PUBLIC grant) — for a role with none it is a hard `permission denied for schema` error; Flyway 11.7.2 opens **two** connections per migrate (history, migration) and runs `initSql` on each, which is fine only because `SET ROLE sm_dp_owner` is idempotent; `ALTER DEFAULT PRIVILEGES` reaches V002–V006's tables because it writes `pg_default_acl`, not because of a shared connection (V001's header comment already says "cohesion, not necessity"). `disabledWithoutDocker` could not be proven to *skip* on this host (the strategy chain falls back to `/var/run/docker.sock`), so a genuinely Docker-less runner has not been measured — the mechanism is the same JUnit `ExecutionCondition` the broker ITs use, which do report `Skipped`; an executor on a Docker-less machine must report what happens rather than assume. The design's line numbers may shift again before execution — the per-file md5s catch it, the block hashes prove the SQL did not change.

---
### Task 2: The design-mirror lock and the DCL / trigger / refusal-shape proofs (`dp-core`)

**Files:**
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/SketchMirrorTest.java` (surefire)
- Create (test helper): `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ApplyRows.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/{PrivilegeTopologyIT,WatermarkTriggerIT,RefusalShapesIT}.java`

**Interfaces:**
- Consumes: Task 1's harness (`SmDataPostgres`, `SqlStatements`, `DesignSketch`, `Refusals`), `SmDataMigrator.{INIT_SQL, PHASE0_RESOURCE, SCHEMA}`.
- Produces: `ApplyRows.{INSTANCE_WIDE, insertScope(jdbc, tenant, instance), insertRequest(jdbc, tenant, instance, placement, state) → UUID, insertPlacementWatermark(jdbc, tenant, instance, placement, generation), scope(tenant, instance) → MapSqlParameterSource}` — the minimal legal rows every later raw-SQL test reuses; the pinned generated constraint names (below) that Task 3's `Constraints` class hard-codes.

**Preconditions:** Task 1 committed and green.

- [ ] **Step 1: Write the failing mirror test**

`SketchMirrorTest.java` — surefire (no Docker), the text relationship between the design's §3.1 block and the shipped files:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The executable half of design §3.1: the SQL that ships in {@code db/bootstrap} and
 * {@code db/migration/sm_data} carries exactly the statements the design sketch carries — no more,
 * no fewer, none altered.
 *
 * <p><b>Why a mirror and not a copy.</b> §3.1 is the reviewed artefact; the migration files are what
 * actually runs. Splitting one into six files at statement boundaries is a mechanical operation, and
 * a mechanical operation deserves a mechanical check — otherwise the next edit to the design lands in
 * a document nothing executes, or an edit to a migration diverges from the document everyone reads.
 * The evidence report {@code evidence/ddl-execution-pg14.md} measured 7 Phase-0 and 24 Phase-1
 * statements; this test recomputes both from source rather than trusting the numbers.
 *
 * <p>The comparison is a <b>multiset</b>, deliberately: the split redistributes the per-table GRANTs
 * to the migrations that create their tables, so file order is not design order. Statement <i>text</i>
 * is what must not move.
 *
 * <p>This is a surefire unit test — it needs no Docker, and it must fail on a laptop with no Docker
 * daemon, because it guards a text relationship, not a database.
 */
class SketchMirrorTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration/sm_data");
    private static final Path PHASE0 = Path.of("src/main/resources/" + SmDataMigrator.PHASE0_RESOURCE);

    @Test
    void theShippedSqlFilesCarryExactlyTheStatementsOfTheDesignSketch() {
        List<String> fromDesign = SqlStatements.of(DesignSketch.sqlBlock());
        List<String> fromFiles = shippedStatements();

        assertEquals(sorted(fromDesign), sorted(fromFiles),
                "the migration files and eda-walking-skeleton.md §3.1 have diverged. Change ONE of "
                        + "them deliberately and re-run — never let them drift.");
    }

    @Test
    void theStatementCountsMatchTheMeasuredPg14EvidenceRun() {
        assertEquals(7, SqlStatements.of(phase0Sql()).size(),
                "Phase 0 is the 7 statements evidence/ddl-execution-pg14.md §2.2 executed");
        assertEquals(24, phase1Statements().size(),
                "Phase 1 is the 24 statements evidence/ddl-execution-pg14.md §2.6 executed, plus "
                        + "nothing: SET ROLE is Flyway initSql and is counted in the 24");
    }

    @Test
    void everyMigrationFileCarriesAtLeastOneStatement() {
        List<Path> files = migrationFiles();
        assertEquals(6, files.size(), "the split is six migrations: " + files);
        for (Path f : files) {
            assertFalse(SqlStatements.of(DesignSketch.read(f)).isEmpty(),
                    f.getFileName() + " contributes no statement — a comment-only migration is a "
                            + "version number Flyway records for nothing");
        }
    }

    /**
     * The multiset comparison above cannot see a statement MOVING between files. Flyway's version
     * order would refuse most harmful moves (a GRANT before its table), but not all, and "would fail
     * at migrate time" is not a lock. Pinning each file's statement count is: a move changes two
     * counts.
     */
    @Test
    void eachMigrationCarriesExactlyTheStatementsTheSplitAssignedIt() {
        List<Path> files = migrationFiles();
        int[] expected = {1, 6, 9, 2, 3, 2};   // V001 .. V006 — measured at the split
        assertEquals(expected.length, files.size());
        for (int i = 0; i < files.size(); i++) {
            assertEquals(expected[i], SqlStatements.of(DesignSketch.read(files.get(i))).size(),
                    files.get(i).getFileName() + " carries a different number of statements than "
                            + "the split assigned — a statement moved between files");
        }
    }

    @Test
    void theSetRoleConstantIsTheOnlyStatementNoFileCarries() {
        List<String> inFiles = new ArrayList<>();
        for (Path f : migrationFiles()) {
            inFiles.addAll(SqlStatements.of(DesignSketch.read(f)));
        }
        assertFalse(inFiles.contains(SmDataMigrator.INIT_SQL),
                "SET ROLE sm_dp_owner belongs in Flyway initSql, not in a migration file — Flyway "
                        + "applies initSql to every connection it opens, a file applies it to one");
        assertTrue(SqlStatements.of(DesignSketch.sqlBlock()).contains(SmDataMigrator.INIT_SQL),
                "…and the design sketch does carry it, so the mirror's SET ROLE contribution is real");
    }

    @Test
    void theSplitterPreconditionsHoldForBothPhasesOfTheSketch() {
        assertNull(SqlStatements.assertNoDialectSurprises(DesignSketch.sqlBlock()),
                "the sketch uses SQL the simple splitter cannot see through");
    }

    /**
     * Prove-it-fires. A rule that cannot fail is decoration, so one token is mutated in an in-memory
     * copy of a migration and the same comparison must reject it, naming the statement.
     */
    @Test
    void mutatingOneTokenOfOneMigrationBreaksTheMirrorAndNamesTheStatement() {
        List<String> fromDesign = sorted(SqlStatements.of(DesignSketch.sqlBlock()));

        // `>= 0` becomes `>= -1`: still valid SQL, still parses, and would silently admit a negative
        // instance watermark — exactly the class of edit a byte-diff of the whole file might be
        // argued away as reformatting.
        List<String> mutated = new ArrayList<>();
        boolean touched = false;
        for (String s : shippedStatements()) {
            if (s.contains("instance_watermark_nonneg CHECK (instance_watermark >= 0)")) {
                mutated.add(s.replace("instance_watermark >= 0", "instance_watermark >= -1"));
                touched = true;
            } else {
                mutated.add(s);
            }
        }
        assertTrue(touched, "the mutation target must exist, or this proof proves nothing");

        assertNotEquals(fromDesign, sorted(mutated),
                "the mirror must reject a one-token change");

        List<String> onlyInMutated = new ArrayList<>(sorted(mutated));
        onlyInMutated.removeAll(fromDesign);
        assertEquals(1, onlyInMutated.size(), "exactly one statement should differ: " + onlyInMutated);
        assertTrue(onlyInMutated.get(0).contains("instance_watermark >= -1"),
                "the difference the comparison surfaces must be the mutated statement itself, so a "
                        + "failure message points a reader at the offending SQL. Was: " + onlyInMutated);
    }

    // -------------------------------------------------------------------------------------------

    /** Phase 0 + Flyway's initSql + every migration, in version order — the executed whole. */
    private static List<String> shippedStatements() {
        List<String> all = new ArrayList<>(SqlStatements.of(phase0Sql()));
        all.addAll(phase1Statements());
        return all;
    }

    /** What Flyway actually executes: its initSql, then the six migrations in version order. */
    private static List<String> phase1Statements() {
        List<String> all = new ArrayList<>();
        all.add(SmDataMigrator.INIT_SQL);
        for (Path f : migrationFiles()) {
            all.addAll(SqlStatements.of(DesignSketch.read(f)));
        }
        return all;
    }

    private static String phase0Sql() {
        return DesignSketch.read(moduleDir().resolve(PHASE0));
    }

    private static List<Path> migrationFiles() {
        try (Stream<Path> s = Files.list(moduleDir().resolve(MIGRATIONS))) {
            return s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Surefire runs with the module as cwd; the reactor root does not, so resolve explicitly. */
    private static Path moduleDir() {
        Path cwd = Path.of("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve(MIGRATIONS)) ? cwd : DesignSketch.repoRoot().resolve("backend/dp-core");
    }

    private static List<String> sorted(List<String> in) {
        return in.stream().sorted().toList();
    }
}
```

- [ ] **Step 2: Run it — green by construction against Task 1's files; make the red real**

Run: `mvn -f backend/pom.xml -pl dp-core -am -q test -Dtest=SketchMirrorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 7 tests, 0 failures, no container started. The **red** for this test is Step 6's mutation, executed deliberately below — a lock test whose red has never been seen is decoration (L7).

- [ ] **Step 3: `ApplyRows` — the minimal legal rows, once**

```java
package com.solace.sm.dp.apply.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The minimal legal rows of design §3.1, in one place, so the privilege tests are testing privileges
 * rather than re-deriving which CHECK wants what.
 *
 * <p>Every statement here binds its parameters ({@code I5}) — a test helper that interpolates
 * strings teaches the pattern the production code must not use.
 *
 * <p><b>The minimal legal {@code apply_request}</b> is worth stating, because seven CHECKs
 * constrain it simultaneously: {@code operation='apply'} forces {@code generation NOT NULL}
 * ({@code apply_has_generation}) and {@code strategy NOT NULL} ({@code lifecycle_has_strategy});
 * {@code strategy='manual'} is legal for {@code apply} ({@code restart_not_manual} only fences
 * {@code restart}) and avoids {@code full_outage_confirmed}, which would demand
 * {@code outage_confirmed_by} for {@code restart-all}; {@code state='REQUESTED'} forces
 * {@code terminal_at IS NULL} ({@code terminal_has_timestamp}) and satisfies
 * {@code state_fits_operation}; and leaving every resolution column NULL satisfies both resolution
 * CHECKs. {@code placement_id} is the nil-UUID sentinel, never SQL NULL — a NULL there defeats the
 * fences and is refused by {@code NOT NULL} anyway (23502).
 */
final class ApplyRows {

    /** The instance-wide sentinel of §3.1 — an explicit value, deliberately not NULL. */
    static final UUID INSTANCE_WIDE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private ApplyRows() {
    }

    /** The serialization point and instance watermark. Runtime holds table-wide INSERT. */
    static void insertScope(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance) {
        jdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance));
    }

    /** The minimal legal request — see the class javadoc for which CHECK each value answers. */
    static UUID insertRequest(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance,
            UUID placement, String state) {
        UUID applyId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.apply_request"
                        + " (apply_id, tenant_id, instance_id, placement_id, operation, generation,"
                        + "  strategy, requested_by, requested_at, state)"
                        + " VALUES (:a, :t, :i, :p, 'apply', 1, 'manual', 'operator-1', :now, :s)",
                new MapSqlParameterSource().addValue("a", applyId).addValue("t", tenant)
                        .addValue("i", instance).addValue("p", placement)
                        .addValue("now", OffsetDateTime.now()).addValue("s", state));
        return applyId;
    }

    /** A placement watermark row; {@code generation > 0} and the placement may not be the sentinel. */
    static void insertPlacementWatermark(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance,
            UUID placement, long generation) {
        jdbc.update("INSERT INTO sm_data.placement_watermark"
                        + " (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("p", placement).addValue("g", generation));
    }

    static MapSqlParameterSource scope(UUID tenant, UUID instance) {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance);
    }
}
```

- [ ] **Step 4: `PrivilegeTopologyIT` — (k), the grant half of (o), the identity half of (t)**

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations <b>(k)</b>, the grant half of <b>(o)</b>, and the identity half of <b>(t)</b> of
 * design §3.3: what the {@code sm_dp_runtime} credential may and may not write.
 *
 * <p>Each refusal is paired with a positive control. A grant that refuses everything is
 * indistinguishable from a broken table, and §3.3 says so explicitly: obligation (k) demands the
 * same statement <i>succeed</i> as the owner, and (t) demands the upsert, the {@code FOR UPDATE} and
 * the legal raise all still work. Without those halves this file would prove only that the tests
 * connect as somebody weak.
 */
@Testcontainers(disabledWithoutDocker = true)
class PrivilegeTopologyIT {

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    // --- (k) intent immutability IS the grant ---------------------------------------------------

    @Test
    void theRuntimeRoleCannotUpdateAnIntentWhileTheOwnerRunningTheSameStatementCan() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        UUID applyId = ApplyRows.insertRequest(runtime, tenant, instance,
                ApplyRows.INSTANCE_WIDE, "REQUESTED");
        UUID intentId = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id,"
                        + " replica_ref, step, idempotency_key, config_hash, created_at)"
                        + " VALUES (:id, :t, :a, 'replica-1', 'MATERIALIZE', :k, 'sha256:aaa', now())",
                new MapSqlParameterSource().addValue("id", intentId).addValue("t", tenant)
                        .addValue("a", applyId).addValue("k", "idem-" + intentId));

        String update = "UPDATE sm_data.apply_step_intent SET config_hash = 'sha256:zzz'"
                + " WHERE intent_id = :id";
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update(update, new MapSqlParameterSource("id", intentId)));

        // The positive control: identical SQL, migrator connection, SET ROLE sm_dp_owner first, one
        // row that certainly matches — so a 1 here means the refusal above was the grant and not a
        // broken table (obligation (k)). Both statements share one connection: SET ROLE is session
        // state. Rolled back inside asOwner, so the intent stays immutable in fact as well as in law.
        assertEquals(1, asOwner("UPDATE sm_data.apply_step_intent SET config_hash = 'sha256:zzz'"
                        + " WHERE intent_id = ?", intentId),
                "the refusal must be the grant, not a broken table (obligation (k))");
    }

    @Test
    void theRuntimeRoleCannotDeleteAnIntentEither() {
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.getJdbcTemplate().execute("DELETE FROM sm_data.apply_step_intent"));
    }

    // --- (o) the column-scoped grant on apply_request -------------------------------------------

    @Test
    void theRuntimeRoleCannotRetenantOrReattributeARequestButMayWalkItsState() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        UUID applyId = ApplyRows.insertRequest(runtime, tenant, instance,
                ApplyRows.INSTANCE_WIDE, "REQUESTED");

        // The I5 write surface the column grant closes: re-tenanting an audit row.
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("UPDATE sm_data.apply_request SET tenant_id = :new WHERE apply_id = :a",
                        new MapSqlParameterSource().addValue("new", UUID.randomUUID())
                                .addValue("a", applyId)));

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("UPDATE sm_data.apply_request SET requested_by = 'someone-else'"
                                + " WHERE apply_id = :a",
                        new MapSqlParameterSource().addValue("a", applyId)));

        assertEquals(1, runtime.update(
                        "UPDATE sm_data.apply_request SET state = 'RUNNING' WHERE apply_id = :a",
                        new MapSqlParameterSource().addValue("a", applyId)),
                "state IS granted, and REQUESTED -> RUNNING is not a terminal regression");
    }

    // --- (t) identity half: the column grant on both watermark tables ---------------------------

    @Test
    void theRuntimeRoleCannotMoveAWatermarkRowsIdentityOnEitherTable() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        ApplyRows.insertPlacementWatermark(runtime, tenant, instance, placement, 5);

        // Re-keying a row moves a fence to another scope WITHOUT lowering any watermark, so the
        // value trigger cannot see it. These five refusals are the other half of that door.
        for (String column : List.of("tenant_id", "instance_id")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                    () -> runtime.update("UPDATE sm_data.apply_scope SET " + column + " = :new"
                                    + " WHERE tenant_id = :t AND instance_id = :i",
                            ApplyRows.scope(tenant, instance).addValue("new", UUID.randomUUID())));
        }
        for (String column : List.of("tenant_id", "instance_id", "placement_id")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                    () -> runtime.update("UPDATE sm_data.placement_watermark SET " + column + " = :new"
                                    + " WHERE tenant_id = :t AND instance_id = :i",
                            ApplyRows.scope(tenant, instance).addValue("new", UUID.randomUUID())));
        }

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("DELETE FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i", ApplyRows.scope(tenant, instance)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("DELETE FROM sm_data.placement_watermark"
                        + " WHERE tenant_id = :t AND instance_id = :i", ApplyRows.scope(tenant, instance)));
    }

    @Test
    void theNarrowGrantStillAdmitsEveryStepOfTheAdmissionTransaction() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();

        // 1. the single-arbiter upsert
        assertEquals(1, runtime.update(
                "INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                        + " ON CONFLICT (tenant_id, instance_id) DO NOTHING",
                ApplyRows.scope(tenant, instance)));
        assertEquals(0, runtime.update(
                "INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                        + " ON CONFLICT (tenant_id, instance_id) DO NOTHING",
                ApplyRows.scope(tenant, instance)),
                "the second upsert is the concurrent admitter's — it must be a no-op, not an error");

        // 2. SELECT … FOR UPDATE — which needs UPDATE on at least one column, and has exactly one
        assertEquals(Integer.valueOf(0), runtime.queryForObject(
                "SELECT instance_watermark FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i FOR UPDATE",
                ApplyRows.scope(tenant, instance), Integer.class));

        // 3. the raise
        assertEquals(1, runtime.update(
                "UPDATE sm_data.apply_scope SET instance_watermark = 7"
                        + " WHERE tenant_id = :t AND instance_id = :i", ApplyRows.scope(tenant, instance)));
        ApplyRows.insertPlacementWatermark(runtime, tenant, instance, placement, 3);
        assertEquals(1, runtime.update(
                "UPDATE sm_data.placement_watermark SET generation = 9"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p",
                ApplyRows.scope(tenant, instance).addValue("p", placement)));
    }

    // --- (I6) resolution_note: the one free-text column, and the runtime cannot write it ---------

    /**
     * The database half of "v1 writes no free text". The Java half ({@code Resolution} has no note
     * component, the repository's SET list omits the column) guards the engine's own paths; this
     * guards every OTHER path — a future or mistaken persistence call issuing a perfectly legal
     * resolution UPDATE that also carries a note. The refusal is {@code 42501}, the GRANT's, and it
     * comes before any CHECK or trigger could speak. The owner control proves the column is writable
     * by someone, so the refusal is the grant and not a broken column.
     */
    @Test
    void theRuntimeRoleCannotWriteResolutionNoteOnALegalResolutionUpdateButTheOwnerCan() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        UUID applyId = ApplyRows.insertRequest(runtime, tenant, instance, ApplyRows.INSTANCE_WIDE,
                "APPLY_INDETERMINATE");

        String legalResolutionWithNote = "UPDATE sm_data.apply_request"
                + " SET state = 'INDETERMINATE_RESOLVED', terminal_at = now(), resolved_by = 'op:res',"
                + "     resolution_outcome = 'applied',"
                + "     resolution_observed = CAST(:ev AS jsonb), resolution_note = :note"
                + " WHERE tenant_id = :t AND apply_id = :a";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ev", "{\"stagedGeneration\": 1, \"replicas\": [\"r0\"]}")
                .addValue("note", "pasted: token=SNTL-note-9d2e")
                .addValue("t", tenant).addValue("a", applyId);
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update(legalResolutionWithNote, params));
        // Even naming the column with NULL: it is the privilege that is absent, not a value rule.
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("UPDATE sm_data.apply_request SET resolution_note = NULL"
                        + " WHERE tenant_id = :t AND apply_id = :a", params));
        assertEquals("APPLY_INDETERMINATE", runtime.queryForObject(
                        "SELECT state FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id = :a",
                        params, String.class),
                "the row is untouched by the refusals");

        // The positive control, rolled back inside asOwner.
        assertEquals(1, asOwner("UPDATE sm_data.apply_request SET resolution_note = NULL"
                        + " WHERE tenant_id = ? AND apply_id = ?", tenant, applyId),
                "the owner holds the column privilege, so the refusal above is the grant");
    }

    /** The INSERT door: a row cannot be BORN with a note either. */
    @Test
    void theRuntimeRoleCannotInsertARowNamingResolutionNoteButTheOwnerCan() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        String bornWithNote = "INSERT INTO sm_data.apply_request"
                + " (apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,"
                + "  requested_by, requested_at, state, resolution_note)"
                + " VALUES (?, ?, ?, ?, 'apply', 1, 'manual', 'op:born', now(), 'REQUESTED', NULL)";
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.getJdbcTemplate().update(bornWithNote, UUID.randomUUID(), tenant,
                        instance, ApplyRows.INSTANCE_WIDE));
        assertEquals(1, asOwner(bornWithNote, UUID.randomUUID(), tenant, instance,
                        ApplyRows.INSTANCE_WIDE),
                "the owner may name the column; the runtime's refusal is the INSERT column grant");
        // …and the runtime's own INSERT, which never names the column, is unaffected.
        ApplyRows.insertRequest(runtime, tenant, instance, ApplyRows.INSTANCE_WIDE, "REQUESTED");
    }

    // --- the catalog view of the same facts, so a grant cannot widen unnoticed ------------------

    @Test
    void theCatalogAgreesWithTheDesignAboutEveryColumnScopedUpdateGrant() {
        assertEquals(List.of("attempt_summary", "resolution_observed",
                        "resolution_outcome", "resolved_by", "state", "terminal_at"),
                updatableColumns("apply_request"),
                "the six columns §3.1's GRANT lists — identity is not runtime-writable, and neither "
                        + "is resolution_note (I6: v1 writes no free text)");
        assertEquals(List.of("apply_id", "attempt_summary", "generation", "instance_id", "operation",
                        "outage_confirmed_by", "placement_id", "requested_at", "requested_by",
                        "resolution_observed", "resolution_outcome", "resolved_by", "state",
                        "strategy", "tenant_id", "terminal_at"),
                columnsWithPrivilege("apply_request", "INSERT"),
                "INSERT is column-scoped too — every column but resolution_note — or a row could be "
                        + "BORN with a note");
        assertEquals(List.of("SELECT"), superuser.getJdbcTemplate().queryForList(
                        "SELECT privilege_type FROM information_schema.column_privileges"
                                + " WHERE table_schema = 'sm_data' AND table_name = 'apply_request'"
                                + "   AND grantee = 'sm_dp_runtime' AND column_name = 'resolution_note'"
                                + " ORDER BY 1", String.class),
                "the runtime holds SELECT and nothing else on resolution_note (table-level SELECT is "
                        + "expanded per column by information_schema)");
        assertEquals(List.of("instance_watermark"), updatableColumns("apply_scope"));
        assertEquals(List.of("generation"), updatableColumns("placement_watermark"));
        assertEquals(List.of(), updatableColumns("apply_step_intent"),
                "IMMUTABLE: apply_step_intent must appear in no UPDATE grant at all");

        assertEquals(14, updatableColumns("apply_attempt").size(),
                "apply_attempt is granted table-wide UPDATE — all 14 columns");

        assertEquals(List.of("apply_attempt"),
                superuser.queryForList(
                        "SELECT table_name FROM information_schema.table_privileges"
                                + " WHERE table_schema = 'sm_data' AND grantee = 'sm_dp_runtime'"
                                + "   AND privilege_type = 'DELETE' ORDER BY table_name",
                        Map.of(), String.class),
                "only the prunable diagnostics table may be deleted from (§3.3 retention split)");
    }

    private static List<String> updatableColumns(String table) {
        return columnsWithPrivilege(table, "UPDATE");
    }

    private static List<String> columnsWithPrivilege(String table, String privilege) {
        return superuser.queryForList(
                "SELECT column_name FROM information_schema.column_privileges"
                        + " WHERE table_schema = 'sm_data' AND table_name = :tbl"
                        + "   AND grantee = 'sm_dp_runtime' AND privilege_type = :priv"
                        + " ORDER BY column_name",
                new MapSqlParameterSource("tbl", table).addValue("priv", privilege), String.class);
    }

    /**
     * Runs one statement on a migrator connection that has first done {@code SET ROLE sm_dp_owner}.
     * Rolled back, always: the positive controls exist to prove the statement is permitted, not to
     * leave owner-made changes behind for the next test. Parameters are bound, never interpolated.
     */
    private static int asOwner(String sql, Object... params) {
        try (Connection c = SmDataPostgres.migratorDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(SmDataMigrator.INIT_SQL);
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                int updated = ps.executeUpdate();
                c.rollback();
                return updated;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("owner control failed: " + sql, e);
        }
    }

    @Test
    void theOwnerControlReallyRunsAsTheOwnerAndNotAsTheMigratorLogin() {
        // Cheap, and it closes the hole that would make every asOwner() control vacuous: if SET ROLE
        // silently did nothing, the controls would still pass on the migrator's own membership.
        try (Connection c = SmDataPostgres.migratorDataSource().getConnection();
                Statement s = c.createStatement()) {
            s.execute(SmDataMigrator.INIT_SQL);
            var rs = s.executeQuery("SELECT current_user");
            assertTrue(rs.next());
            assertEquals("sm_dp_owner", rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

The column-name loops (`SET " + column + "`) interpolate identifiers from a fixed literal list — identifiers cannot be bound; every *value* is bound. That is the one sanctioned shape of string assembly in this plan.

- [ ] **Step 5: `WatermarkTriggerIT` — the value half of (t) — and `RefusalShapesIT`**

`WatermarkTriggerIT.java`:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The value half of obligation <b>(t)</b>: the two {@code *_only_rises} triggers of design §3.1.
 *
 * <p>The hazard they close is row-relative and was measured: the runtime's {@code UPDATE} right
 * reaches every row, so one unguarded statement could rewind any tenant's fence and re-admit a
 * superseded generation. With the guard, a bug touching the wrong row can only OVER-fence — an
 * over-raised watermark refuses applies, it never re-admits a stale one.
 *
 * <p>Every refusal is asserted with the trigger's own wording, per §3.3's harness rule. That rule is
 * not pedantry here: §3.1 records that a single shared function branching on {@code TG_TABLE_NAME}
 * failed with {@code 42703} in three of four directions, and an earlier run read one of those
 * {@code 42703}s as the refusal it was asserting. A test that accepts "some error" would have passed
 * on a schema where no watermark could be raised at all.
 */
@Testcontainers(disabledWithoutDocker = true)
class WatermarkTriggerIT {

    private static NamedParameterJdbcTemplate runtime;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
    }

    @Test
    void theInstanceWatermarkRisesAndStaysEqualButMayNotDecrease() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);

        assertEquals(1, setInstanceWatermark(tenant, instance, 10), "a rise must succeed");
        assertEquals(1, setInstanceWatermark(tenant, instance, 10),
                "equal is not a decrease — the trigger tests '<', and re-landing the same generation "
                        + "must not be an error");

        Refusals.assertRaised("instance watermark may not decrease (10 -> 3)",
                () -> setInstanceWatermark(tenant, instance, 3));

        assertEquals(Integer.valueOf(10), currentInstanceWatermark(tenant, instance),
                "the refused statement must have left the fence where it was");
    }

    @Test
    void thePlacementWatermarkRisesAndStaysEqualButMayNotDecrease() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        ApplyRows.insertPlacementWatermark(runtime, tenant, instance, placement, 7);

        assertEquals(1, setPlacementWatermark(tenant, instance, placement, 12));
        assertEquals(1, setPlacementWatermark(tenant, instance, placement, 12));

        Refusals.assertRaised("placement watermark may not decrease (12 -> 1)",
                () -> setPlacementWatermark(tenant, instance, placement, 1));
    }

    @Test
    void theGreatestUpsertIdiomTheAdmissionPathUsesIsAdmittedAndItsLoweringFormIsNot() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        ApplyRows.insertPlacementWatermark(runtime, tenant, instance, placement, 20);

        // What the admission transaction actually writes: an upsert whose DO UPDATE can only raise.
        assertEquals(1, runtime.update(
                "INSERT INTO sm_data.placement_watermark (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)"
                        + " ON CONFLICT (tenant_id, instance_id, placement_id) DO UPDATE"
                        + " SET generation = GREATEST(sm_data.placement_watermark.generation, EXCLUDED.generation)",
                ApplyRows.scope(tenant, instance).addValue("p", placement).addValue("g", 25)));
        assertEquals(Integer.valueOf(25), currentPlacementWatermark(tenant, instance, placement));

        // GREATEST also makes a late, lower write a harmless no-op rather than a rewind…
        assertEquals(1, runtime.update(
                "INSERT INTO sm_data.placement_watermark (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)"
                        + " ON CONFLICT (tenant_id, instance_id, placement_id) DO UPDATE"
                        + " SET generation = GREATEST(sm_data.placement_watermark.generation, EXCLUDED.generation)",
                ApplyRows.scope(tenant, instance).addValue("p", placement).addValue("g", 4)));
        assertEquals(Integer.valueOf(25), currentPlacementWatermark(tenant, instance, placement));

        // …and the trigger is what stops an upsert that FORGOT the GREATEST. This is the version of
        // the hazard that reaches the row through ON CONFLICT rather than a plain UPDATE.
        Refusals.assertRaised("placement watermark may not decrease (25 -> 1)",
                () -> runtime.update(
                        "INSERT INTO sm_data.placement_watermark (tenant_id, instance_id, placement_id, generation)"
                                + " VALUES (:t, :i, :p, :g)"
                                + " ON CONFLICT (tenant_id, instance_id, placement_id) DO UPDATE"
                                + " SET generation = EXCLUDED.generation",
                        ApplyRows.scope(tenant, instance).addValue("p", placement).addValue("g", 1)));

        Refusals.assertRaised("instance watermark may not decrease (30 -> 2)",
                () -> {
                    setInstanceWatermark(tenant, instance, 30);
                    runtime.update(
                            "INSERT INTO sm_data.apply_scope (tenant_id, instance_id, instance_watermark)"
                                    + " VALUES (:t, :i, 2)"
                                    + " ON CONFLICT (tenant_id, instance_id) DO UPDATE"
                                    + " SET instance_watermark = EXCLUDED.instance_watermark",
                            ApplyRows.scope(tenant, instance));
                });
    }

    private static int setInstanceWatermark(UUID tenant, UUID instance, long value) {
        return runtime.update("UPDATE sm_data.apply_scope SET instance_watermark = :g"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                ApplyRows.scope(tenant, instance).addValue("g", value));
    }

    private static int setPlacementWatermark(UUID tenant, UUID instance, UUID placement, long value) {
        return runtime.update("UPDATE sm_data.placement_watermark SET generation = :g"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p",
                ApplyRows.scope(tenant, instance).addValue("p", placement).addValue("g", value));
    }

    private static Integer currentInstanceWatermark(UUID tenant, UUID instance) {
        return runtime.queryForObject("SELECT instance_watermark FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                ApplyRows.scope(tenant, instance), Integer.class);
    }

    private static Integer currentPlacementWatermark(UUID tenant, UUID instance, UUID placement) {
        return runtime.queryForObject("SELECT generation FROM sm_data.placement_watermark"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p",
                ApplyRows.scope(tenant, instance).addValue("p", placement), Integer.class);
    }
}
```

`RefusalShapesIT.java` — what a refusal looks like over JDBC per violation class, and the PostgreSQL-**generated** constraint names Task 3's `Constraints` class will hard-code:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a refusal LOOKS LIKE over JDBC, per violation class — the input the whole
 * assert-by-name discipline of §3.3 depends on.
 *
 * <p>The evidence report {@code evidence/ddl-execution-pg14.md} recorded these as psql
 * {@code CONSTRAINT NAME:} lines. Over JDBC the same facts arrive through
 * {@code PSQLException.getServerErrorMessage()}, and they are NOT uniform: a unique index and a
 * named CHECK give a constraint name, a NOT NULL violation gives a <b>column</b> and no constraint,
 * and a privilege refusal gives neither. Repository code and every future refusal test are written
 * against that shape, so it is pinned here rather than rediscovered.
 *
 * <p>It also pins the PostgreSQL-<b>generated</b> constraint names the design's inline
 * {@code UNIQUE (…)} clauses produce. Those names are not in the design text — they are derived
 * from table and column names — so any column rename silently renames a constraint that tests and
 * error handling refer to. Listing them makes that a visible change.
 */
@Testcontainers(disabledWithoutDocker = true)
class RefusalShapesIT {

    private static NamedParameterJdbcTemplate runtime;
    private static UUID tenant;
    private static UUID instance;
    private static UUID applyId;
    private static UUID intentId;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        applyId = ApplyRows.insertRequest(runtime, tenant, instance, ApplyRows.INSTANCE_WIDE,
                "REQUESTED");
        intentId = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id,"
                        + " replica_ref, step, idempotency_key, config_hash, created_at)"
                        + " VALUES (:id, :t, :a, 'replica-1', 'MATERIALIZE', :k, 'sha256:aaa', :now)",
                new MapSqlParameterSource().addValue("id", intentId).addValue("t", tenant)
                        .addValue("a", applyId).addValue("k", "idem-" + intentId)
                        .addValue("now", OffsetDateTime.now()));
    }

    @Test
    void aPartialUniqueIndexRefusalNamesTheIndexJustLikeATableConstraintWould() {
        PSQLException pg = Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "apply_inflight_fence",
                () -> ApplyRows.insertRequest(runtime, tenant, instance, ApplyRows.INSTANCE_WIDE,
                        "REQUESTED"));
        assertEquals("apply_request", pg.getServerErrorMessage().getTable());
        assertNull(pg.getServerErrorMessage().getColumn(),
                "a unique violation names the index, never a column");
    }

    @Test
    void aNamedCheckRefusalNamesTheCheck() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_has_generation",
                () -> runtime.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id,"
                                + " instance_id, placement_id, operation, generation, strategy,"
                                + " requested_by, requested_at, state)"
                                + " VALUES (:a, :t, :i, :p, 'apply', NULL, 'manual', 'op', :now, 'REQUESTED')",
                        new MapSqlParameterSource().addValue("a", UUID.randomUUID())
                                .addValue("t", tenant).addValue("i", instance)
                                .addValue("p", UUID.randomUUID()).addValue("now", OffsetDateTime.now())));
    }

    @Test
    void aNotNullRefusalCarriesTheColumnAndNoConstraintAtAll() {
        PSQLException pg = Refusals.assertRefused(Refusals.NOT_NULL_VIOLATION, null,
                () -> runtime.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id,"
                                + " instance_id, placement_id, operation, generation, strategy,"
                                + " requested_by, requested_at, state)"
                                + " VALUES (:a, :t, :i, NULL, 'apply', 1, 'manual', 'op', :now, 'REQUESTED')",
                        new MapSqlParameterSource().addValue("a", UUID.randomUUID())
                                .addValue("t", tenant).addValue("i", instance)
                                .addValue("now", OffsetDateTime.now())));
        assertNull(pg.getServerErrorMessage().getConstraint(),
                "23502 has no constraint name — code that reads getConstraint() to classify a "
                        + "refusal must handle null, and this is the case that produces it");
        assertEquals("placement_id", pg.getServerErrorMessage().getColumn(),
                "the column is where a NOT NULL violation identifies itself");
        assertEquals("apply_request", pg.getServerErrorMessage().getTable());
    }

    @Test
    void aPrivilegeRefusalCarriesNeitherConstraintNorColumnNorTable() {
        PSQLException pg = Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.getJdbcTemplate()
                        .execute("UPDATE sm_data.apply_step_intent SET config_hash = 'x'"));
        assertNull(pg.getServerErrorMessage().getConstraint());
        assertNull(pg.getServerErrorMessage().getColumn());
        assertNull(pg.getServerErrorMessage().getTable(),
                "42501 identifies its object only in the free-text message ('permission denied for "
                        + "table apply_step_intent') — there is no structured field to match on");
    }

    @Test
    void aTriggerRefusalCarriesOnlyItsOwnMessage() {
        PSQLException pg = Refusals.assertRaised("instance watermark may not decrease (4 -> 1)",
                () -> {
                    runtime.update("UPDATE sm_data.apply_scope SET instance_watermark = 4"
                            + " WHERE tenant_id = :t AND instance_id = :i",
                            ApplyRows.scope(tenant, instance));
                    runtime.update("UPDATE sm_data.apply_scope SET instance_watermark = 1"
                            + " WHERE tenant_id = :t AND instance_id = :i",
                            ApplyRows.scope(tenant, instance));
                });
        assertNull(pg.getServerErrorMessage().getConstraint(),
                "P0001 is a RAISE, not a constraint — the wording IS the identity, which is exactly "
                        + "why §3.1 puts the table name inside each message");
    }

    @Test
    void theGeneratedNamesOfTheDesignsUnnamedUniquesAndForeignKeysArePinned() {
        assertEquals(List.of(
                        "apply_attempt_intent_id_attempt_no_key",
                        "apply_attempt_intent_id_step_fkey",
                        "apply_attempt_pkey",
                        "apply_attempt_tenant_id_apply_id_fkey",
                        "apply_attempt_tenant_id_apply_id_intent_id_fkey"),
                generatedNames("apply_attempt"));

        assertEquals(List.of(
                        "apply_step_intent_idempotency_key_key",
                        "apply_step_intent_intent_id_step_key",
                        "apply_step_intent_pkey",
                        "apply_step_intent_tenant_id_apply_id_fkey",
                        "apply_step_intent_tenant_id_apply_id_intent_id_key",
                        "apply_step_intent_tenant_id_apply_id_step_replica_ref_key"),
                generatedNames("apply_step_intent"));

        // request_has_scope is the one FK §3.1 names explicitly (it is added by a later ALTER
        // TABLE), so it appears here beside the two generated names rather than instead of them.
        assertEquals(List.of("apply_request_pkey", "apply_request_tenant_id_apply_id_key",
                        "request_has_scope"),
                generatedNames("apply_request"));

        assertEquals(List.of("placement_watermark_pkey",
                        "placement_watermark_tenant_id_instance_id_fkey"),
                generatedNames("placement_watermark"));
        assertEquals(List.of("apply_scope_pkey"), generatedNames("apply_scope"));
    }

    /** Constraint names PostgreSQL derived, i.e. everything but the CHECKs the design names. */
    private static List<String> generatedNames(String table) {
        return SmDataPostgres.superuserJdbc().queryForList(
                "SELECT con.conname FROM pg_constraint con"
                        + " JOIN pg_class c ON c.oid = con.conrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'sm_data' AND c.relname = :tbl AND con.contype <> 'c'"
                        + " ORDER BY con.conname",
                new MapSqlParameterSource("tbl", table), String.class);
    }

    @Test
    void theDesignsOwnNamedConstraintsAreAllPresent() {
        assertEquals(23, SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT count(*)::int FROM pg_constraint con"
                                + " JOIN pg_class c ON c.oid = con.conrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = 'sm_data' AND con.contype = 'c'",
                        Map.of(), Integer.class),
                "every CHECK in §3.1 is explicitly named; the count changing means one was added, "
                        + "removed, or lost its name");
    }
}
```

- [ ] **Step 6: Run green, then prove the whole task fires with one grant mutation**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify`
Expected: BUILD SUCCESS; surefire dp-core **72** (65 + `SketchMirrorTest` 7); failsafe `Tests run: 29, Skipped: 2` — `SmDataBootstrapIT` 8, `PrivilegeTopologyIT` 9, `WatermarkTriggerIT` 3, `RefusalShapesIT` 7. Measured facts to reproduce: the six `apply_request` UPDATE columns exactly `[attempt_summary, resolution_observed, resolution_outcome, resolved_by, state, terminal_at]` and the sixteen INSERT columns (every column but `resolution_note`); the runtime's only privilege on `resolution_note` is `SELECT`; a legal resolution UPDATE that also names `resolution_note`, and an INSERT that names it even as NULL, refused `42501` with the owner control succeeding; `apply_scope` → `[instance_watermark]`; `placement_watermark` → `[generation]`; `apply_step_intent` → `[]`; `apply_attempt` → 14 columns and the only `DELETE` grant; the `P0001` texts `instance watermark may not decrease (10 -> 3)` / `placement watermark may not decrease (12 -> 1)`; 23 named CHECKs; the generated names listed above.

**Prove it fires (measured, two mutations):** (1) widen V002's GRANT by one identity column — change `UPDATE (state, terminal_at, …` to `UPDATE (tenant_id, state, terminal_at, …` in `V002__apply_request.sql` — and run
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dtest=SketchMirrorTest -Dit.test=PrivilegeTopologyIT -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=true`.
Expected: **four** reds, two per layer — `SketchMirrorTest.theShippedSqlFilesCarryExactlyTheStatementsOfTheDesignSketch` (the files no longer mirror the design) and `mutatingOneTokenOfOneMigrationBreaksTheMirrorAndNamesTheStatement` (`expected: <1> but was: <2>` — two statements now differ; `eachMigrationCarriesExactlyTheStatementsTheSplitAssignedIt` stays green because the widened GRANT is still one statement — that test guards *moves*, not edits); `PrivilegeTopologyIT.theCatalogAgreesWithTheDesignAboutEveryColumnScopedUpdateGrant` (`tenant_id` appears in the list) and `theRuntimeRoleCannotRetenantOrReattributeARequestButMayWalkItsState` — which fails with **`wrong SQLSTATE … Was: ERROR: insert or update on table "apply_request" violates foreign key constraint "request_has_scope"`**: with the grant widened, the re-tenant UPDATE is *admitted* and only the FK stops it, i.e. the wrong guard fired — the exact mis-attribution the assert-by-name rule exists to catch. Revert the file (its md5 must return to `96321edb257e39bb503192c3c3dff5a6`); re-run; green. (2) Put `resolution_note` back into BOTH column lists of the same GRANT (Codex's fifth pass — the grant that reopened the deleted free-text sink) and run the same command. Expected: **five** reds — the two `SketchMirrorTest` mirror tests, `PrivilegeTopologyIT.theCatalogAgreesWithTheDesignAboutEveryColumnScopedUpdateGrant` (seven UPDATE columns, seventeen INSERT columns, and `[SELECT]` becomes `[INSERT, SELECT, UPDATE]`), `theRuntimeRoleCannotWriteResolutionNoteOnALegalResolutionUpdateButTheOwnerCan` and `theRuntimeRoleCannotInsertARowNamingResolutionNoteButTheOwnerCan` (both: the statement must be REFUSED, and it was admitted). Revert; green.

- [ ] **Step 7: Commit**

```bash
git add backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/SketchMirrorTest.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ApplyRows.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/PrivilegeTopologyIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/WatermarkTriggerIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/RefusalShapesIT.java
git commit -m "Lock the migrations to design §3.1 and prove the DCL, triggers and refusal shapes (Task 2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

**Risks:** the mirror is a comment-stripped statement **multiset** plus per-file counts — not a byte comparison: a comment-only edit is invisible by design, and a statement moving between files is caught by the count test, not by the multiset. The mirror test's failure output prints both full statement lists (measured: long, but the one-token proof names the differing statement precisely — read that test's message first). The generated constraint names are derived from column names; a future design rename silently renames them — that is what `theGeneratedNamesOfTheDesignsUnnamedUniquesAndForeignKeysArePinned` exists to surface.

---
### Task 3: The framework-free model, the four repositories, the two ArchUnit gates, and plan 003's watch item (`dp-core`, `arch-tests`)

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/model/{Uuid7,TenantId,InstanceId,PlacementId,ScopeKey,ApplyId,Operation,Strategy,RequestState,Step,ReplicaRef,ResolutionOutcome,AttemptOutcome,ApplyCommand,ApplyRequestRow,Resolution,StepIntentRow,AttemptRow,ValueGrammar,ErrorClass,RuntimeRef,PreBaseline,ReplicaObservation,ResolutionEvidence}.java` (24 files)
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/{PgConstraintViolation,Constraints,Sql,Evidence,ApplyScopeRepository,ApplyRequestRepository,StepIntentRepository,AttemptRepository}.java`
- Modify: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/ApplyPersistenceConfiguration.java` (append four repository beans)
- Modify: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/SpringTransactionBoundary.java` (javadoc only — the `SYNCHRONIZATION_NEVER` paragraph)
- Modify (test): `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/Refusals.java` (add `assertViolation`)
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/model/{Uuid7Test,ApplyCommandTest,EvidenceTypesTest,RequestStateTest}.java`, `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/EvidenceJsonTest.java` (surefire) — `EvidenceTypesTest` includes the executable admission that a bare compact token passes `RuntimeRef`'s value grammar
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/SpringTransactionBoundaryDataSourceIT.java`
- Modify: `backend/arch-tests/src/test/java/com/solace/sm/arch/PlaneBoundaryTest.java` (+2 rules, +4 proof tests)
- Create: `backend/arch-tests/src/test/java/com/solace/sm/dp/apply/archfixture/BadJdbcOutsidePersistenceClass.java`, `…/dp/apply/persistence/archfixture/LegalPersistenceJdbcClass.java`, `…/dp/apply/engine/archfixture/BadEngineJdbcClass.java`, `…/dp/apply/model/archfixture/BadFrameworkModelClass.java`

**Interfaces:**
- Consumes: Task 1's configuration and harness, Task 2's `ApplyRows`.
- Produces: the `model` package exactly as printed below (Task 4–6 construct `ScopeKey`, `ApplyCommand`, `ApplyRequestRow`, `StepIntentRow`, `AttemptRow`, `Resolution`, `ResolutionEvidence`, `ReplicaObservation`, `PreBaseline`, `ErrorClass`, `RuntimeRef` and switch on the enums; `RequestState.validFor(Operation)` mirrors `state_fits_operation`); `Evidence.{write(PreBaseline|RuntimeRef|ResolutionEvidence) → String, readPreBaseline/readRuntimeRef/readResolutionEvidence(String)}` (package-private, the one persistence JSON mapper); `ApplyScopeRepository.{ensureScopeRow, lockScope, instanceWatermark, placementWatermark, maxPlacementWatermark, raiseInstanceWatermark, raisePlacementWatermark}`; `ApplyRequestRepository.{insert, find, blockingRequests, stopInFlight, stagedRequest, transition, resolveIndeterminate, nonTerminal, foldAttemptSummary}`; `StepIntentRepository.{find, insert, forRequest}`; `AttemptRepository.{open, close, latest, count, deleteForRequest}`; `PgConstraintViolation.{sqlState(), constraint(), table(), column(), namesAnyOf(String...), from(Throwable)}`; `Constraints.{APPLY_INFLIGHT_FENCE, STOP_INFLIGHT_FENCE, STAGED_FENCE, INTENT_STEP_REPLICA_UNIQUE, INTENT_IDEMPOTENCY_KEY_UNIQUE, ATTEMPT_NO_UNIQUE}`; beans `applyScopeRepository`, `applyRequestRepository`, `stepIntentRepository`, `attemptRepository`; `Refusals.assertViolation(state, constraintOrNull, exec) → PgConstraintViolation`.

**Preconditions:** Tasks 1–2 committed and green. The repositories' *behaviour* is proven by Tasks 4–6's ITs (every method is exercised there against the real schema); this task's own red/green is the model's unit tests, the ArchUnit proofs and the transaction-boundary IT.

- [ ] **Step 1: Write the failing model tests (surefire) — `Uuid7Test`, `ApplyCommandTest`, `EvidenceTypesTest`, `RequestStateTest`, `EvidenceJsonTest`**

`backend/dp-core/src/test/java/com/solace/sm/dp/apply/model/Uuid7Test.java`:

```java
package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The three claims {@link Uuid7} makes: the RFC 9562 bit layout, and that
 * {@link UUID#compareTo} — which compares SIGNED 64-bit halves — really does order these ids by
 * time.
 */
class Uuid7Test {

    @Test
    void everyGeneratedIdCarriesVersionSevenAndTheRfcVariant() {
        for (int i = 0; i < 1000; i++) {
            UUID id = Uuid7.next();
            assertEquals(7, id.version(), "RFC 9562 version nibble");
            assertEquals(2, id.variant(), "the 0b10 variant of RFC 9562 / 4122");
        }
    }

    @Test
    void theHighFortyEightBitsAreTheMillisecondTheIdWasStampedWith() {
        long millis = 0x0000_0123_4567_89ABL;
        UUID id = Uuid7.at(millis, new Random(42));
        assertEquals(millis, Uuid7.timestampMillis(id));
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void onlyTheLowFortyEightBitsOfTheTimestampAreUsedSoAnOverlongValueCannotCorruptTheVersion() {
        UUID id = Uuid7.at(-1L, new Random(1));
        assertEquals(0xFFFF_FFFF_FFFFL, Uuid7.timestampMillis(id));
        assertEquals(7, id.version(), "the version nibble survives a saturated timestamp");
        assertEquals(2, id.variant());
    }

    @Test
    void aThousandIdsSpanAtLeastTwoMillisecondsAndSortByCompareToInTimeOrder() throws Exception {
        List<UUID> generated = new ArrayList<>();
        Set<Long> distinctMillis = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            if (i == 500) {
                // Guarantees the ">= 2 distinct milliseconds" precondition instead of hoping the
                // loop is slow enough. Without it a fast machine mints all 1000 inside one
                // millisecond and the ordering claim below is never actually exercised.
                Thread.sleep(2);
            }
            UUID id = Uuid7.next();
            generated.add(id);
            distinctMillis.add(Uuid7.timestampMillis(id));
        }

        assertTrue(distinctMillis.size() >= 2,
                "the run must cross a millisecond boundary or it proves nothing about time order. "
                        + "Distinct milliseconds: " + distinctMillis.size());

        // Generation order is already non-decreasing in time…
        for (int i = 1; i < generated.size(); i++) {
            assertTrue(Uuid7.timestampMillis(generated.get(i))
                            >= Uuid7.timestampMillis(generated.get(i - 1)),
                    "wall clock went backwards between id " + (i - 1) + " and " + i);
        }

        // …and UUID.compareTo, which is signed, agrees with it: any two ids stamped in DIFFERENT
        // milliseconds compare in the same direction as their timestamps. This is the claim an
        // index depends on, and the one a signed comparison of the high half could have broken.
        for (int i = 0; i < generated.size(); i++) {
            for (int j = i + 1; j < generated.size(); j++) {
                long ti = Uuid7.timestampMillis(generated.get(i));
                long tj = Uuid7.timestampMillis(generated.get(j));
                if (ti != tj) {
                    assertTrue(generated.get(i).compareTo(generated.get(j)) < 0,
                            "compareTo disagreed with the clock: " + generated.get(i) + " (" + ti
                                    + ") vs " + generated.get(j) + " (" + tj + ")");
                }
            }
        }

        List<UUID> sorted = new ArrayList<>(generated);
        sorted.sort(UUID::compareTo);
        for (int i = 1; i < sorted.size(); i++) {
            assertTrue(Uuid7.timestampMillis(sorted.get(i))
                            >= Uuid7.timestampMillis(sorted.get(i - 1)),
                    "sorting by compareTo must leave timestamps non-decreasing");
        }
    }

    @Test
    void aThousandIdsAreDistinct() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(Uuid7.next()), "74 random bits per millisecond must not collide");
        }
    }
}
```

`backend/dp-core/src/test/java/com/solace/sm/dp/apply/model/ApplyCommandTest.java`:

```java
package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every CHECK {@link ApplyCommand} mirrors, refused in Java — one test per rule, each asserting the
 * message NAMES the constraint it is standing in for.
 *
 * <p>The naming assertion is the point: {@code SchemaConstraintsIT} proves the same rules fire in
 * PostgreSQL, and the two halves are only recognisable as the same rule if the Java half says which
 * CHECK it duplicates. A bare {@code IllegalArgumentException} would leave a reader unable to tell
 * whether the schema still needs the constraint.
 */
class ApplyCommandTest {

    private static final ScopeKey SCOPE = new ScopeKey(new TenantId(UUID.randomUUID()),
            new InstanceId(UUID.randomUUID()), PlacementId.INSTANCE_WIDE);

    @Test
    void aLegalApplyCommandIsAccepted() {
        ApplyCommand cmd = new ApplyCommand(SCOPE, Operation.APPLY, 7L, Strategy.MANUAL, null,
                "principal-1");
        assertEquals(7L, cmd.generation());
        assertEquals(Strategy.MANUAL, cmd.strategy());
    }

    @Test
    void aLegalRestartAndALegalStopAreAcceptedToo() {
        new ApplyCommand(SCOPE, Operation.RESTART, null, Strategy.ROLLING_HEALTH_GATED, null, "p");
        new ApplyCommand(SCOPE, Operation.STOP, null, null, null, "p");
    }

    @Test
    void anApplyWithoutAGenerationIsRefusedByTheJavaMirrorOfApplyHasGeneration() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.APPLY, null, Strategy.MANUAL, null, "p"));
        assertTrue(e.getMessage().contains("apply_has_generation"), e.getMessage());
    }

    @Test
    void aRestartCarryingAGenerationIsRefusedByTheSameRuleFromTheOtherSide() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.RESTART, 3L, Strategy.SCHEDULED, null, "p"));
        assertTrue(e.getMessage().contains("apply_has_generation"), e.getMessage());
    }

    @Test
    void aNonPositiveGenerationIsRefusedByTheJavaMirrorOfGenerationPositive() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.APPLY, 0L, Strategy.MANUAL, null, "p"));
        assertTrue(e.getMessage().contains("generation_positive"), e.getMessage());
    }

    @Test
    void anApplyWithoutAStrategyIsRefusedByTheJavaMirrorOfLifecycleHasStrategy() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.APPLY, 1L, null, null, "p"));
        assertTrue(e.getMessage().contains("lifecycle_has_strategy"), e.getMessage());
    }

    @Test
    void aStopCarryingAStrategyIsRefusedBySameRuleFromTheOtherSide() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.STOP, null, Strategy.MANUAL, null, "p"));
        assertTrue(e.getMessage().contains("lifecycle_has_strategy"), e.getMessage());
    }

    @Test
    void aManualRestartIsRefusedByTheJavaMirrorOfRestartNotManual() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.RESTART, null, Strategy.MANUAL, null, "p"));
        assertTrue(e.getMessage().contains("restart_not_manual"), e.getMessage());
    }

    @Test
    void restartAllWithoutAnOutageConfirmationIsRefusedByTheJavaMirrorOfFullOutageConfirmed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.APPLY, 1L, Strategy.RESTART_ALL, null, "p"));
        assertTrue(e.getMessage().contains("full_outage_confirmed"), e.getMessage());
    }

    @Test
    void restartAllWithAnOutageConfirmationIsAcceptedTheNeighbouringPositiveControl() {
        ApplyCommand cmd = new ApplyCommand(SCOPE, Operation.APPLY, 1L, Strategy.RESTART_ALL,
                "duty-manager-3", "p");
        assertEquals("duty-manager-3", cmd.outageConfirmedBy());
    }

    @Test
    void aBlankRequestedByIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.STOP, null, null, null, "   "));
        assertTrue(e.getMessage().contains("requested_by"), e.getMessage());
    }

    @Test
    void aNullScopeOrOperationIsRefused() {
        assertThrows(NullPointerException.class,
                () -> new ApplyCommand(null, Operation.STOP, null, null, null, "p"));
        assertThrows(NullPointerException.class,
                () -> new ApplyCommand(SCOPE, null, null, null, null, "p"));
    }
}
```

`EvidenceTypesTest.java` — the sentinel-secret refusals (a `password=…` line, a JWT-shaped token, a multi-line exception with a path and quotes, a value with a space) and the legal identifiers, for every evidence type; the messages must NOT echo the rejected value, or the exception would copy the secret into every log it reaches:

```java
package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code I6} types of design §3.1's residual table, tested the way that table asks: "content, not
 * shape — engine classification tests".
 *
 * <p>Every refusal case below is a SENTINEL: a string that looks like the thing the column must never
 * hold — {@code password=hunter2}, a JWT-shaped bearer token, a multi-line exception message with a
 * filesystem path and quotes, a value with a space. The point is not that these particular strings are
 * refused, it is that the grammar has no way to express any of them, so a caller cannot smuggle one in
 * by being slightly different.
 *
 * <p>The other half of each test is the positive control: the legal examples the design writes must be
 * accepted, or the type is merely strict rather than correct.
 *
 * <p><b>And one test is a NEGATIVE positive control</b> —
 * {@link #aBareCompactTokenIsAcceptedByShapeWhichIsWhyTheContentObligationIsTheAdaptersOwn} measures
 * what these grammars do NOT catch. They are shape controls: they exclude environment lines,
 * {@code Bearer} headers, padded base64, PEM, JSON blobs and stack traces, and they admit any bare
 * identifier-shaped token. Writing the limit down as an executable assertion is the difference between
 * a documented boundary and an overclaim that the next reader trusts.
 *
 * <p>One assertion recurs and is load-bearing on its own: a refusal must never echo the value it
 * refused. A type that rejects {@code password=hunter2} and then prints it into an
 * {@code IllegalArgumentException} has moved the secret from the database to the log.
 */
class EvidenceTypesTest {

    private static final String PASSWORD_LINE = "password=hunter2";
    private static final String BEARER =
            "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.abc";
    private static final String STACK_TRACE =
            "java.io.IOException: cannot read \"/etc/sm/secrets.yml\"\n\tat Foo.bar(Foo.java:12)";
    private static final String WITH_SPACE = "cache engine 1";

    // ----------------------------------------------------------------------------------------
    // ErrorClass
    // ----------------------------------------------------------------------------------------

    @Test
    void anErrorClassAcceptsTheDesignsOwnClassificationsAndRefusesEverySentinelSecret() {
        for (String legal : List.of("io.timeout", "docker.api.unreachable", "hash.mismatch",
                "k8s", "a1.b2.c3")) {
            assertEquals(legal, new ErrorClass(legal).token());
        }
        for (String sentinel : List.of(PASSWORD_LINE, BEARER, STACK_TRACE, WITH_SPACE)) {
            assertRuleNamedAndValueNotEchoed(sentinel, "[a-z]", () -> new ErrorClass(sentinel));
        }
        // Grammar-only cases, asserted without the no-echo check: the refusal message names
        // io.timeout as the canonical EXAMPLE, so "io.timeout " (trailing space) is a substring of
        // it. Measured — the first run of this test failed on exactly that, which is the no-echo
        // assertion being strict rather than the type leaking anything.
        for (String malformed : List.of("IO.TIMEOUT", "io..timeout", ".io.timeout", "io.timeout ",
                "io.time_out", "io-timeout.")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ErrorClass(malformed), "must be refused: " + malformed);
            assertTrue(e.getMessage().contains("[a-z]"), e.getMessage());
        }
    }

    @Test
    void anErrorClassLongerThanItsCapIsRefusedWithoutEchoingIt() {
        String tooLong = "a".repeat(ErrorClass.MAX_LENGTH + 1);
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new ErrorClass(tooLong));
        assertTrue(e.getMessage().contains(String.valueOf(ErrorClass.MAX_LENGTH)), e.getMessage());
        assertFalse(e.getMessage().contains(tooLong), e.getMessage());
        assertNotNull(new ErrorClass("a".repeat(ErrorClass.MAX_LENGTH)),
                "the boundary itself is legal — the cap is at most, not fewer than");
    }

    // ----------------------------------------------------------------------------------------
    // RuntimeRef
    // ----------------------------------------------------------------------------------------

    @Test
    void aRuntimeRefAcceptsAdapterIdentifiersAndRefusesEverySentinelSecretAsAValue() {
        RuntimeRef legal = RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "9f2c1e4b7a30",
                RuntimeRef.Key.UNIT_NAME, "solace-cache.service",
                RuntimeRef.Key.CONFIG_MAP_NAME, "cache-config-g17",
                RuntimeRef.Key.NAMESPACE, "prod/cache-engine");
        assertEquals(4, legal.fields().size());
        assertEquals("9f2c1e4b7a30", legal.fields().get(RuntimeRef.Key.CONTAINER_ID));

        for (String sentinel : List.of(PASSWORD_LINE, BEARER, STACK_TRACE, WITH_SPACE,
                "\"quoted\"", "a\\b")) {
            assertRuleNamedAndValueNotEchoed(sentinel, "[A-Za-z0-9._:/@+-]",
                    () -> RuntimeRef.of(RuntimeRef.Key.HOST, sentinel));
        }
    }

    /**
     * The honest limit of the shape control, asserted rather than admitted in prose.
     *
     * <p>A compact JWT is base64url and dots; every character of it is legal in an adapter identifier,
     * so {@link RuntimeRef} accepts it. That is not a defect in the grammar — a grammar that excluded
     * it would also exclude {@code sha256:…} digests and dotted unit names — it is the boundary between
     * a SHAPE control and a CONTENT control. The content control is each adapter's own contract test
     * against secrets drawn from its real inputs, which Plan 005 carries.
     */
    @Test
    void aBareCompactTokenIsAcceptedByShapeWhichIsWhyTheContentObligationIsTheAdaptersOwn() {
        String compactJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.abc";
        RuntimeRef accepted = assertDoesNotThrow(
                () -> RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, compactJwt),
                "the grammar admits this, and pretending otherwise is how an I6 claim becomes false");
        assertEquals(compactJwt, accepted.fields().get(RuntimeRef.Key.CONTAINER_ID),
                "MEASURED: the type stored it verbatim — only the ADAPTER can stop this value being "
                        + "put here in the first place");
    }

    @Test
    void aRuntimeRefKeyOutsideTheClosedEnumIsRefusedAndTheEnumIsExactlyDesignTwoPointThrees() {
        assertEquals(List.of("containerId", "imageDigest", "unitName", "host", "namespace",
                        "workload", "podName", "configMapName", "mountPath"),
                Arrays.stream(RuntimeRef.Key.values()).map(RuntimeRef.Key::wire).toList(),
                "the wire names are the stored contract: renaming one changes every jsonb document "
                        + "this build reads");

        // A free-String key is now a compile-time impossibility, so the only remaining door is the
        // wire name — a document written by another build, or a hand-edit.
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> RuntimeRef.Key.ofWire("apiToken"));
        assertTrue(unknown.getMessage().contains("containerId"),
                "the refusal names the closed set. Was: " + unknown.getMessage());
        assertFalse(unknown.getMessage().contains("apiToken"),
                "…and not the key it refused. Was: " + unknown.getMessage());

        assertThrows(IllegalArgumentException.class, () -> new RuntimeRef(Map.of()),
                "an empty reference references nothing");
    }

    @Test
    void aRuntimeRefIsDefensivelyCopiedAndUnmodifiable() {
        Map<RuntimeRef.Key, String> mutable = new LinkedHashMap<>();
        mutable.put(RuntimeRef.Key.CONTAINER_ID, "9f2c1e4b7a30");
        RuntimeRef ref = new RuntimeRef(mutable);

        mutable.put(RuntimeRef.Key.HOST, "some-other-host");
        assertEquals(1, ref.fields().size(),
                "a caller keeping the map it passed must not be able to add a field afterwards");
        assertThrows(UnsupportedOperationException.class,
                () -> ref.fields().put(RuntimeRef.Key.HOST, "some-other-host"));
    }

    // ----------------------------------------------------------------------------------------
    // PreBaseline
    // ----------------------------------------------------------------------------------------

    @Test
    void aPreBaselineAcceptsAProcessIdentifierAndRefusesEverySentinelSecret() {
        PreBaseline legal = new PreBaseline(Instant.parse("2026-09-01T00:00:00Z"), "9f2c1e4b7a30");
        assertEquals("9f2c1e4b7a30", legal.processRef());

        for (String sentinel : List.of(PASSWORD_LINE, BEARER, STACK_TRACE, WITH_SPACE)) {
            assertRuleNamedAndValueNotEchoed(sentinel, "[A-Za-z0-9._:/@+-]",
                    () -> new PreBaseline(Instant.EPOCH, sentinel));
        }
        assertThrows(NullPointerException.class, () -> new PreBaseline(null, "9f2c1e4b7a30"));
    }

    // ----------------------------------------------------------------------------------------
    // ReplicaObservation / ResolutionEvidence
    // ----------------------------------------------------------------------------------------

    @Test
    void aReplicaObservationRefusesAnythingThatIsNotAnAlgorithmColonHexHash() {
        assertEquals("sha256:3333333333333333333333333333333333333333",
                new ReplicaObservation(new ReplicaRef("cache-engine-1"), true,
                        "sha256:3333333333333333333333333333333333333333").observedHash());
        assertNull(new ReplicaObservation(new ReplicaRef("cache-engine-1"), false, null)
                        .observedHash(),
                "an adapter that could not read a hash says so with null, not with prose");

        for (String sentinel : List.of(PASSWORD_LINE, BEARER, STACK_TRACE, WITH_SPACE,
                "sha256:short", "SHA256:3333333333333333333333333333333333333333",
                "3333333333333333333333333333333333333333")) {
            assertRuleNamedAndValueNotEchoed(sentinel, "[0-9a-f]",
                    () -> new ReplicaObservation(new ReplicaRef("r1"), true, sentinel));
        }
    }

    @Test
    void resolutionEvidenceRefusesAnEmptyReplicaListAndANonPositiveGeneration() {
        ResolutionEvidence legal = new ResolutionEvidence(20, List.of(
                new ReplicaObservation(new ReplicaRef("cache-engine-1"), true, null)));
        assertEquals(20, legal.stagedGeneration());
        assertEquals(1, legal.replicas().size());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> new ResolutionEvidence(20, List.of()));
        assertTrue(empty.getMessage().contains("resolution_is_evidence"), empty.getMessage());

        for (long bad : new long[] {0, -1}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ResolutionEvidence(bad, List.of(
                            new ReplicaObservation(new ReplicaRef("r1"), true, null))));
            assertTrue(e.getMessage().contains("stagedGeneration"), e.getMessage());
        }
    }

    /**
     * A {@code Resolution} carries typed evidence and NOTHING free-text. An earlier version had an
     * operator {@code note}; an external review pointed out it was the one column a human could paste
     * a secret into during an incident, persisted and then printed by JDBC's TRACE bind-value line.
     * Pinned by shape: the record's components are exactly the three below, none of them a remark.
     */
    @Test
    void aResolutionCarriesTypedEvidenceAndNoFreeTextComponent() {
        ResolutionEvidence evidence = new ResolutionEvidence(20, List.of(
                new ReplicaObservation(new ReplicaRef("cache-engine-1"), true, null)));

        Resolution r = new Resolution("duty-operator", ResolutionOutcome.APPLIED, evidence);
        assertEquals(evidence, r.evidence());
        assertThrows(NullPointerException.class,
                () -> new Resolution("duty-operator", ResolutionOutcome.APPLIED, null));

        List<String> components = java.util.Arrays.stream(Resolution.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("resolvedBy", "outcome", "evidence"), components,
                "no free-text component may be added to a Resolution without its own I6 design "
                        + "(sensitive content, retention, access control, logging) — see the javadoc");
    }

    // ----------------------------------------------------------------------------------------

    /** Refused, with the RULE in the message and the offending value nowhere in it. */
    private static void assertRuleNamedAndValueNotEchoed(String sentinel, String ruleFragment,
            org.junit.jupiter.api.function.Executable e) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, e,
                "must be refused: " + sentinel.replaceAll("\\s+", " "));
        assertTrue(thrown.getMessage().contains(ruleFragment),
                "the refusal must name the rule (" + ruleFragment + "). Was: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains(sentinel),
                "the refusal must NOT echo the value it refused — that copies the secret into every "
                        + "log the exception reaches. Was: " + thrown.getMessage());
    }
}
```

`RequestStateTest.java` — `validFor` against `state_fits_operation`'s definition, all 12 states × 3 operations:

```java
package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestState#validFor} against {@code state_fits_operation}, all 36 pairs.
 *
 * <p>The CHECK, from design §3.1, verbatim:
 * <pre>{@code
 * CONSTRAINT state_fits_operation CHECK (CASE operation
 *         WHEN 'stop'    THEN state IN ('REQUESTED','RUNNING','STOPPED','STOP_FAILED')
 *         WHEN 'restart' THEN state NOT IN ('STAGED','STOPPED','STOP_FAILED')
 *         ELSE                state NOT IN ('STOPPED','STOP_FAILED') END)
 * }</pre>
 *
 * <p>The expectation below is written out as three explicit SETS rather than by re-running the
 * predicate's own logic, because a table that computes the answer the same way the code does proves
 * nothing. Transcribing the CHECK by hand is the point: the two spellings are compared, and
 * {@code OutcomeTransitionsIT} then proves the database agrees with this transcription on live rows.
 */
class RequestStateTest {

    /** {@code WHEN 'stop' THEN state IN (…)} — an allow-list, transcribed. */
    private static final Set<RequestState> STOP = EnumSet.of(RequestState.REQUESTED,
            RequestState.RUNNING, RequestState.STOPPED, RequestState.STOP_FAILED);

    /** {@code WHEN 'restart' THEN state NOT IN (…)} — a deny-list, transcribed. */
    private static final Set<RequestState> RESTART_DENIED = EnumSet.of(RequestState.STAGED,
            RequestState.STOPPED, RequestState.STOP_FAILED);

    /** {@code ELSE state NOT IN (…)} — the apply branch, transcribed. */
    private static final Set<RequestState> APPLY_DENIED =
            EnumSet.of(RequestState.STOPPED, RequestState.STOP_FAILED);

    @Test
    void everyStateAndOperationPairAgreesWithTheCheckAsTranscribed() {
        for (RequestState state : RequestState.values()) {
            assertEquals(STOP.contains(state), state.validFor(Operation.STOP),
                    "stop / " + state);
            assertEquals(!RESTART_DENIED.contains(state), state.validFor(Operation.RESTART),
                    "restart / " + state);
            assertEquals(!APPLY_DENIED.contains(state), state.validFor(Operation.APPLY),
                    "apply / " + state);
        }
        assertEquals(12, RequestState.values().length,
                "design §3.2 has twelve states; a thirteenth needs a row in the table above and a "
                        + "migration to state_known and state_fits_operation");
    }

    @Test
    void theThreeOperationsDisagreeInExactlyThePlacesTheCheckDoes() {
        // The pairs worth naming, because they are the ones the engine gets wrong: a stop may not
        // hold any apply outcome, and a restart may not hold STAGED (it has no staging step).
        assertEquals(false, RequestState.APPLIED.validFor(Operation.STOP));
        assertEquals(false, RequestState.STAGED.validFor(Operation.STOP));
        assertEquals(false, RequestState.APPLY_INDETERMINATE.validFor(Operation.STOP));
        assertEquals(false, RequestState.STAGED.validFor(Operation.RESTART));
        assertEquals(true, RequestState.STAGED.validFor(Operation.APPLY));
        assertEquals(false, RequestState.STOPPED.validFor(Operation.APPLY));
        assertEquals(false, RequestState.STOP_FAILED.validFor(Operation.APPLY));
        assertEquals(true, RequestState.APPLIED.validFor(Operation.RESTART),
                "a restart that completes IS applied — the restart branch denies only the three");
    }

    @Test
    void aNullOperationIsACallerBugRatherThanASilentFalse() {
        assertThrows(NullPointerException.class, () -> RequestState.APPLIED.validFor(null));
    }
}
```

`EvidenceJsonTest.java` (persistence package) — round trips, the `resolution_observed` top-level keys pinned to exactly the two the database CHECK reads, and two corruption-report sentinels: a corrupt `jsonb` document is reported without the column's content reaching the exception chain — the mapper disables `INCLUDE_SOURCE_IN_LOCATION` explicitly as defence in depth rather than relying on the 2.16+ default, but the property the tests actually pin is that `Evidence.read` attaches no cause (re-enabling `INCLUDE_SOURCE_IN_LOCATION` alone is measured green) — and a corrupt `startedAt` is reported without `DateTimeParseException`'s quoted text:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ResolutionEvidence;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Evidence}'s three shapes, without a database.
 *
 * <p>Round-tripping is half of it. The other half is the top-level key set of
 * {@code resolution_observed}, asserted exactly: {@code resolution_is_evidence} reads
 * {@code -> 'stagedGeneration'} and {@code -> 'replicas'} BY NAME, so a rename here would compile,
 * pass every unit test that only checks round-tripping, and then refuse every operator resolution in
 * production with a 23514. This is the test that makes the coupling to the DDL visible.
 */
class EvidenceJsonTest {

    private static final JsonMapper READER = JsonMapper.builder().build();

    @Test
    void aPreBaselineRoundTripsAsItsTwoNamedFields() throws Exception {
        PreBaseline baseline =
                new PreBaseline(Instant.parse("2026-09-01T02:14:07Z"), "9f2c1e4b7a30");

        String json = Evidence.write(baseline);
        System.out.println("MEASURED: pre_baseline JSON = " + json);
        assertEquals("{\"startedAt\":\"2026-09-01T02:14:07Z\",\"processRef\":\"9f2c1e4b7a30\"}", json);
        assertEquals(baseline, Evidence.readPreBaseline(json));
        assertNull(Evidence.write((PreBaseline) null), "an absent baseline stays SQL NULL");
        assertNull(Evidence.readPreBaseline(null));
    }

    @Test
    void aRuntimeRefRoundTripsAsAPlainObjectKeyedByTheEnumsWireNames() throws Exception {
        RuntimeRef ref = RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "9f2c1e4b7a30",
                RuntimeRef.Key.UNIT_NAME, "solace-cache.service");

        String json = Evidence.write(ref);
        System.out.println("MEASURED: runtime_ref JSON = " + json);
        assertEquals("{\"containerId\":\"9f2c1e4b7a30\",\"unitName\":\"solace-cache.service\"}", json);
        assertEquals(ref, Evidence.readRuntimeRef(json));
        assertNull(Evidence.write((RuntimeRef) null));
        assertNull(Evidence.readRuntimeRef(null));
    }

    /**
     * The closed key set on the READ side, which is the half a Java enum alone does not give.
     *
     * <p>{@code RuntimeRef.Key} makes an unknown key uncompilable for this build's adapters. It says
     * nothing about a document that is already in the column — written by an older build, by a
     * hand-edit, or by an adapter that reached the {@code jsonb} some other way. Without this refusal
     * the enum would be a convention about who calls the constructor rather than a property of the
     * stored contract, which is exactly the shape of argument finding 1 rejected about the migrator
     * credential.
     */
    @Test
    void aStoredRuntimeRefWithAKeyOutsideTheEnumIsRefusedRatherThanCarriedThrough() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Evidence.readRuntimeRef("{\"apiToken\":\"x\"}"));

        System.out.println("MEASURED: unknown stored key refusal = " + e.getMessage());
        assertTrue(e.getMessage().contains("containerId"),
                "the refusal names the closed set. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains("apiToken"),
                "…and never the key it refused — a stored key is data, and data may be anything. "
                        + "Was: " + e.getMessage());
        assertNotNull(Evidence.readRuntimeRef("{\"containerId\":\"9f2c1e4b7a30\"}"),
                "the positive control: a document written by this build still reads back");
    }

    /**
     * A corrupt column must not travel into the exception that reports it. Jackson quotes the source
     * document in its message unless {@code INCLUDE_SOURCE_IN_LOCATION} is off — the 2.16+ default, but a
     * default is not a guarantee, so {@code Evidence} disables it explicitly and this pins the effect.
     */
    @Test
    void aCorruptJsonColumnIsReportedWithoutQuotingItsContent() {
        String sentinel = "SNTL-json-5a1d";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Evidence.readPreBaseline("{not json " + sentinel));

        String chain = e.getMessage() + " | " + (e.getCause() == null ? "" : e.getCause().getMessage());
        System.out.println("MEASURED: corrupt jsonb report = " + chain);
        assertTrue(e.getMessage().contains("pre_baseline"), "names the column. Was: " + e.getMessage());
        assertFalse(chain.contains(sentinel),
                "the column's content reached the exception chain. Was: " + chain);
        assertTrue(chain.contains("REDACTED"),
                "Jackson's location must say REDACTED — if it no longer does, the redaction feature "
                        + "changed name or default; re-measure rather than delete. Was: " + chain);
    }

    /** {@code DateTimeParseException} quotes the text it could not parse — so it is not carried. */
    @Test
    void aCorruptInstantInsideAValidDocumentIsReportedWithoutQuotingIt() {
        String sentinel = "SNTL-time-7e2c";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Evidence.readPreBaseline(
                        "{\"startedAt\":\"" + sentinel + "\",\"processRef\":\"p1\"}"));

        System.out.println("MEASURED: corrupt instant report = " + e.getMessage() + " | cause="
                + e.getCause());
        assertTrue(e.getMessage().contains("pre_baseline.startedAt"), e.getMessage());
        assertFalse(e.getMessage().contains(sentinel), e.getMessage());
        assertNull(e.getCause(), "no cause: DateTimeParseException's message IS the text");
    }

    @Test
    void resolutionEvidenceRoundTripsAndItsTopLevelKeysAreExactlyTheOnesTheCheckReads()
            throws Exception {
        ResolutionEvidence evidence = new ResolutionEvidence(20, List.of(
                new ReplicaObservation(new ReplicaRef("cache-engine-1"), true,
                        "sha256:3333333333333333333333333333333333333333"),
                new ReplicaObservation(new ReplicaRef("cache-engine-2"), false, null)));

        String json = Evidence.write(evidence);
        System.out.println("MEASURED: resolution_observed JSON = " + json);
        assertEquals(evidence, Evidence.readResolutionEvidence(json));

        JsonNode root = READER.readTree(json);
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = root.fieldNames(); it.hasNext();) {
            keys.add(it.next());
        }
        assertEquals(List.of("stagedGeneration", "replicas"), keys,
                "resolution_is_evidence reads these two names out of the jsonb; renaming either "
                        + "would refuse every resolution with a 23514 and compile cleanly");
        assertTrue(root.path("stagedGeneration").isNumber(),
                "the CHECK requires jsonb_typeof(...) = 'number', not a quoted string");
        assertTrue(root.path("replicas").isArray() && root.path("replicas").size() == 2,
                "…and a NON-EMPTY array");
        assertTrue(root.path("replicas").get(1).path("observedHash").isNull(),
                "an unreadable hash is JSON null, not an absent key: absence would make the "
                        + "element's shape depend on what the adapter managed to read");
        assertNull(Evidence.write((ResolutionEvidence) null));
        assertNull(Evidence.readResolutionEvidence(null));
    }
}
```

Run: `mvn -f backend/pom.xml -pl dp-core -am -q test -Dtest='Uuid7Test,ApplyCommandTest,EvidenceTypesTest,RequestStateTest,EvidenceJsonTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `package com.solace.sm.dp.apply.model does not exist` / cannot find symbol `Uuid7`, `ApplyCommand`, `ErrorClass`, `Evidence`. That is the red.

- [ ] **Step 2: The model — twenty-four framework-free files under `com.solace.sm.dp.apply.model`**

Every file below imports nothing from Spring or `java.sql` (the ArchUnit rule in Step 5 enforces it). Imports are `java.util.Objects`, `java.util.UUID`, `java.time.Instant`, `java.security.SecureRandom`, `java.util.Random` as each file needs.

`Uuid7.java`:

```java
package com.solace.sm.dp.apply.model;

import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7 — the {@code apply_id} shape design §3.1 asks for ("UUIDv7: time-ordered,
 * index-friendly").
 *
 * <p>Java 17 has no v7 generator ({@code UUID.randomUUID()} is v4), and the PostgreSQL 14 floor has
 * no {@code uuidv7()} either (that arrived in PG 18), so the id is minted here. The layout, bit for
 * bit:
 *
 * <pre>
 *   bits 127..80  unix_ts_ms   48 bits, big-endian milliseconds since the epoch
 *   bits  79..76  ver          0b0111
 *   bits  75..64  rand_a       12 random bits
 *   bits  63..62  var          0b10
 *   bits  61..0   rand_b       62 random bits
 * </pre>
 *
 * <p><b>Why time-ordering survives {@link UUID#compareTo}</b>, which compares the two 64-bit halves
 * as SIGNED longs: the most significant bit of the high half is bit 47 of the millisecond counter,
 * and unix milliseconds do not reach 2^47 until the year 6429. Until then every generated id has a
 * non-negative high half and signed comparison agrees with unsigned. Stated because it is the one
 * assumption that makes "index-friendly" true rather than hoped-for.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {
    }

    /** A fresh v7 id stamped with the current wall clock. */
    public static UUID next() {
        return at(System.currentTimeMillis(), RANDOM);
    }

    /**
     * The pure form — every bit that is not the timestamp comes from {@code rnd}, so a test can pin
     * the millisecond and still exercise the real layout code.
     *
     * @param epochMillis milliseconds since 1970-01-01T00:00:00Z; only the low 48 bits are used
     * @param rnd         the randomness source for {@code rand_a} and {@code rand_b}
     */
    static UUID at(long epochMillis, Random rnd) {
        long msb = (epochMillis & 0x0000_FFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L;                          // version 7, bits 79..76
        msb |= rnd.nextInt(0x1000);              // rand_a, 12 bits
        long lsb = rnd.nextLong();
        lsb &= 0x3FFF_FFFF_FFFF_FFFFL;           // clear the two variant bits
        lsb |= 0x8000_0000_0000_0000L;           // variant 0b10, bits 63..62
        return new UUID(msb, lsb);
    }

    /** The 48-bit timestamp back out of a v7 id — the field {@link UUID#compareTo} sorts on first. */
    public static long timestampMillis(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }
}
```

The keys — `TenantId`, `InstanceId`, `PlacementId`, `ScopeKey`, `ApplyId`, `ReplicaRef`:

```java
package com.solace.sm.dp.apply.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The canonical tenant key (architecture §3.1 seam 1) — a distinct type because {@code I5} says every
 * query carries tenant scope, and three bare {@code UUID}s in one signature is how a caller passes
 * the instance id as the tenant id without the compiler noticing.
 *
 * @param value the tenant's UUID, never null
 */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "tenant id");
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A managed instance — the unit the {@code apply_scope} lock row and the instance watermark are keyed
 * by.
 *
 * @param value the instance's UUID, never null
 */
public record InstanceId(UUID value) {

    public InstanceId {
        Objects.requireNonNull(value, "instance id");
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A placement, or {@link #INSTANCE_WIDE} — design §3.1's explicit nil-UUID sentinel.
 *
 * <p>The sentinel is a VALUE, never SQL NULL, and the reason is in the DDL: PostgreSQL 14 treats NULL
 * index keys as distinct, so a nullable {@code placement_id} would let two instance-wide requests
 * both satisfy {@code apply_inflight_fence}. {@code placement_watermark} refuses the sentinel outright
 * ({@code real_placements_only}) because instance-wide watermarks live on {@code apply_scope}.
 *
 * @param value the placement's UUID, or the nil UUID for instance-wide
 */
public record PlacementId(UUID value) {

    /** The instance-wide sentinel of design §3.1. */
    public static final PlacementId INSTANCE_WIDE = new PlacementId(new UUID(0L, 0L));

    public PlacementId {
        Objects.requireNonNull(value, "placement id");
    }

    public boolean isInstanceWide() {
        return INSTANCE_WIDE.value.equals(value);
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * The three columns every fence in design §3.1 is keyed by: {@code (tenant_id, instance_id,
 * placement_id)}.
 *
 * <p>{@link #overlaps} is the cross-scope rule the partial unique indexes CANNOT express — an
 * instance-wide request excludes every placement-scoped one and vice versa — and which §3.3's
 * admission transaction therefore has to evaluate in code while holding the scope row.
 */
public record ScopeKey(TenantId tenant, InstanceId instance, PlacementId placement) {

    public ScopeKey {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(placement, "placement");
    }

    /** Same tenant and instance, and placements that are equal or either of them instance-wide. */
    public boolean overlaps(ScopeKey other) {
        return tenant.equals(other.tenant)
                && instance.equals(other.instance)
                && (placement.isInstanceWide() || other.placement.isInstanceWide()
                        || placement.equals(other.placement));
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The durable identity of one operator decision — {@code apply_request.apply_id}.
 *
 * @param value a UUIDv7 (see {@link Uuid7}); never null
 */
public record ApplyId(UUID value) {

    public ApplyId {
        Objects.requireNonNull(value, "apply id");
    }

    /** A fresh, time-ordered id. */
    public static ApplyId next() {
        return new ApplyId(Uuid7.next());
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * {@code apply_step_intent.replica_ref} — adapter vocabulary (a container id, a unit name, a pod
 * name), or the {@link #PLACEMENT} sentinel for the placement-scoped steps.
 *
 * @param value the adapter's own name for the replica; never blank
 */
public record ReplicaRef(String value) {

    /** The sentinel design §3.1 fixes for placement-scoped steps. */
    public static final ReplicaRef PLACEMENT = new ReplicaRef("@placement");

    public ReplicaRef {
        Objects.requireNonNull(value, "replica ref");
        if (value.isBlank()) {
            throw new IllegalArgumentException("replica ref must not be blank");
        }
    }

    public boolean isPlacement() {
        return PLACEMENT.value.equals(value);
    }
}
```

The enums — `Operation`, `Strategy`, `RequestState`, `Step`, `ResolutionOutcome`, `AttemptOutcome`. Where SQL spelling differs from the Java name the mapping is explicit (`sql()`/`fromSql()`), never a case fold; where they are identical there is deliberately no `sql()` to drift:

```java
package com.solace.sm.dp.apply.model;

/**
 * {@code apply_request.operation} — the closed set {@code op_known} enforces.
 *
 * <p>The SQL spelling is lower-case and the Java name is not, so the mapping is explicit here rather
 * than {@code name().toLowerCase()}: a locale-sensitive case fold is exactly the kind of thing that
 * works everywhere but Turkey.
 */
public enum Operation {

    APPLY("apply"),
    RESTART("restart"),
    STOP("stop");

    private final String sql;

    Operation(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public static Operation fromSql(String sql) {
        for (Operation o : values()) {
            if (o.sql.equals(sql)) {
                return o;
            }
        }
        throw new IllegalArgumentException("not an operation this schema can hold: " + sql);
    }
}
```

```java
package com.solace.sm.dp.apply.model;

/**
 * {@code SM-D15}'s restart-strategy ladder, verbatim — the values {@code strategy_known} admits.
 *
 * <p>Whether a given PACKAGE supports a value is admission-checked elsewhere: it is package-relative
 * and inexpressible in a CHECK (design §3.1).
 */
public enum Strategy {

    MANUAL("manual"),
    ROLLING_HEALTH_GATED("rolling-health-gated"),
    ROLLING_NO_VALIDATION("rolling-no-validation"),
    RESTART_ALL("restart-all"),
    SCHEDULED("scheduled");

    private final String sql;

    Strategy(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public static Strategy fromSql(String sql) {
        for (Strategy s : values()) {
            if (s.sql.equals(sql)) {
                return s;
            }
        }
        throw new IllegalArgumentException("not a strategy this schema can hold: " + sql);
    }
}
```

```java
package com.solace.sm.dp.apply.model;

/**
 * The twelve states of design §3.2. Spelt identically in Java and SQL, so there is no {@code sql()}
 * here — a second spelling would be a second place to get {@code APPLY_INDETERMINATE} wrong.
 *
 * <p>The three predicates below mirror DDL, and each is load-bearing rather than convenient:
 * {@link #isTerminal()} is the complement of {@code terminal_has_timestamp}'s NULL side,
 * {@link #blocksScope()} is the predicate of {@code apply_inflight_fence}, and
 * {@link #validFor(Operation)} is {@code state_fits_operation}. Keeping them here means the engine and
 * the database disagree in exactly one place, which a test can then watch.
 */
public enum RequestState {

    REQUESTED,
    RUNNING,
    STAGED,
    APPLIED,
    FAILED_NO_EFFECT,
    FAILED_ROLLED_BACK,
    CONFIG_DIVERGED,
    SUPERSEDED,
    APPLY_INDETERMINATE,
    INDETERMINATE_RESOLVED,
    STOPPED,
    STOP_FAILED;

    /** Mirrors {@code terminal_has_timestamp}: everything but the three open states. */
    public boolean isTerminal() {
        return !(this == REQUESTED || this == RUNNING || this == APPLY_INDETERMINATE);
    }

    /**
     * Mirrors {@code apply_inflight_fence}'s state predicate. {@code APPLY_INDETERMINATE} is in the
     * set on purpose — that is how architecture.md §16.1's "blocking further applies until an
     * operator confirms" is implemented.
     */
    public boolean blocksScope() {
        return this == REQUESTED || this == RUNNING || this == APPLY_INDETERMINATE;
    }

    /**
     * Mirrors {@code state_fits_operation}, clause for clause.
     *
     * <p>The CHECK reads:
     * <pre>{@code
     * CASE operation
     *   WHEN 'stop'    THEN state IN ('REQUESTED','RUNNING','STOPPED','STOP_FAILED')
     *   WHEN 'restart' THEN state NOT IN ('STAGED','STOPPED','STOP_FAILED')
     *   ELSE                state NOT IN ('STOPPED','STOP_FAILED') END
     * }</pre>
     *
     * <p>Two enforcers, the standing pattern of this design: the CHECK stays the last line and makes
     * the wrong pairing unrepresentable; this predicate lets {@code OutcomeService.land} refuse it
     * BEFORE any write, with a sentence naming the rule, so an engine bug is a readable error rather
     * than a 23514 out of a repository. A change here without a migration is a divergence
     * {@code RequestStateTest} exists to catch.
     *
     * @param op the request's operation
     * @return whether this state is one that operation may hold
     */
    public boolean validFor(Operation op) {
        switch (op) {
            case STOP:
                return this == REQUESTED || this == RUNNING || this == STOPPED
                        || this == STOP_FAILED;
            case RESTART:
                return !(this == STAGED || this == STOPPED || this == STOP_FAILED);
            case APPLY:
                return !(this == STOPPED || this == STOP_FAILED);
            default:
                throw new IllegalArgumentException("not an operation this schema can hold: " + op);
        }
    }
}
```

```java
package com.solace.sm.dp.apply.model;

/**
 * {@code apply_step_intent.step} — the EXECUTION vocabulary, internal to {@code sm_data}. Design §3.5
 * is explicit that this is not the wire vocabulary: {@code instance.rollout.payload.step} is the
 * contract's {@code [staged, restart-requested, restart-observed]} and the DP owns the mapping.
 *
 * <p>The two predicates mirror the two CHECKs that constrain an intent row, in both directions —
 * {@code hash_on_config_steps} and {@code placement_scoped_steps} are equalities, not implications,
 * so a hash on a hashless step is refused just as loudly as a missing one.
 */
public enum Step {

    PREFLIGHT,
    MATERIALIZE,
    LIFECYCLE,
    VERIFY,
    ROLLBACK,
    ROLLBACK_ASSESS,
    STOP;

    /** Mirrors {@code hash_on_config_steps}. */
    public boolean carriesHash() {
        return this == MATERIALIZE || this == VERIFY || this == ROLLBACK;
    }

    /** Mirrors {@code placement_scoped_steps}: these steps address {@code @placement}, not a replica. */
    public boolean placementScoped() {
        return this == PREFLIGHT || this == ROLLBACK || this == ROLLBACK_ASSESS;
    }
}
```

```java
package com.solace.sm.dp.apply.model;

/**
 * {@code apply_request.resolution_outcome} — what the operator confirmed actually happened, and the
 * input §2.2 rule 1's monotonicity consumes: {@code applied} raises the watermark, {@code not_applied}
 * does not.
 */
public enum ResolutionOutcome {

    APPLIED("applied"),
    NOT_APPLIED("not_applied");

    private final String sql;

    ResolutionOutcome(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public static ResolutionOutcome fromSql(String sql) {
        for (ResolutionOutcome o : values()) {
            if (o.sql.equals(sql)) {
                return o;
            }
        }
        throw new IllegalArgumentException("not a resolution outcome this schema can hold: " + sql);
    }
}
```

```java
package com.solace.sm.dp.apply.model;

/**
 * {@code apply_attempt.outcome} — spelt identically in Java and SQL ({@code outcome_known}), so no
 * {@code sql()} mapping exists to drift.
 */
public enum AttemptOutcome {

    SUCCEEDED,
    FAILED,
    TIMED_OUT
}
```

The command and the four row records:

```java
package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * What an operator asked for, before admission has decided anything about it.
 *
 * <p>The compact constructor mirrors the row CHECKs of design §3.1 so a malformed command fails in
 * Java first, with a sentence naming the rule, instead of arriving at PostgreSQL as a 23514 the
 * caller has to decode. <b>The CHECKs stay the last line</b> — both halves are proven, in
 * {@code ApplyCommandTest} and in {@code SchemaConstraintsIT} respectively, because a validation that
 * only exists in Java is one refactor away from not existing.
 *
 * @param scope             tenant + instance + placement (or {@link PlacementId#INSTANCE_WIDE})
 * @param operation         apply, restart or stop
 * @param generation        the config generation; present iff {@code operation == APPLY}
 *                          ({@code apply_has_generation}, {@code generation_positive})
 * @param strategy          present iff the operation is apply or restart ({@code lifecycle_has_strategy});
 *                          never {@code MANUAL} for a restart ({@code restart_not_manual})
 * @param outageConfirmedBy required by {@code full_outage_confirmed} when the strategy is
 *                          {@code restart-all}
 * @param requestedBy       the principal id — never a display name (design §3.1)
 */
public record ApplyCommand(ScopeKey scope, Operation operation, Long generation, Strategy strategy,
        String outageConfirmedBy, String requestedBy) {

    public ApplyCommand {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(operation, "operation");
        if ((operation == Operation.APPLY) != (generation != null)) {
            throw new IllegalArgumentException(
                    "apply_has_generation: an apply carries a generation and nothing else may — "
                            + operation + " with generation " + generation);
        }
        if (generation != null && generation <= 0) {
            throw new IllegalArgumentException(
                    "generation_positive: generation must be > 0, was " + generation);
        }
        boolean lifecycle = operation == Operation.APPLY || operation == Operation.RESTART;
        if (lifecycle != (strategy != null)) {
            throw new IllegalArgumentException(
                    "lifecycle_has_strategy: apply and restart carry a strategy and stop may not — "
                            + operation + " with strategy " + strategy);
        }
        if (operation == Operation.RESTART && strategy == Strategy.MANUAL) {
            throw new IllegalArgumentException(
                    "restart_not_manual: a manual restart is the operator's own act, not a request");
        }
        if (strategy == Strategy.RESTART_ALL && outageConfirmedBy == null) {
            throw new IllegalArgumentException(
                    "full_outage_confirmed: restart-all needs a named outage confirmation");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requested_by must be a principal id, not blank");
        }
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One {@code apply_request} row, minus the four resolution columns (which travel as a
 * {@link Resolution} because they are written together or not at all — {@code resolution_is_evidence}
 * is an equality over all of them).
 *
 * @param scope       the three fence columns
 * @param requestedAt when the operator asked
 * @param state       where SM's part of the operation currently stands (design §3.2)
 * @param terminalAt  non-null exactly when {@code state.isTerminal()} ({@code terminal_has_timestamp})
 */
public record ApplyRequestRow(ApplyId applyId, ScopeKey scope, Operation operation, Long generation,
        Strategy strategy, String outageConfirmedBy, String requestedBy, Instant requestedAt,
        RequestState state, Instant terminalAt) {

    public ApplyRequestRow {
        Objects.requireNonNull(applyId, "apply id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(requestedBy, "requested by");
        Objects.requireNonNull(requestedAt, "requested at");
        Objects.requireNonNull(state, "state");
        if (state.isTerminal() != (terminalAt != null)) {
            throw new IllegalArgumentException(
                    "terminal_has_timestamp: " + state + " with terminalAt " + terminalAt);
        }
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * The operator's confirmation of an {@code APPLY_INDETERMINATE} request — design §3.4's
 * evidence-bearing flavour, and the only way {@code INDETERMINATE_RESOLVED} is ever reached.
 *
 * <p>{@link #evidence} is a {@link ResolutionEvidence} rather than JSON text: the persistence layer
 * serializes it, so no caller can hand the database a string and hope
 * {@code resolution_is_evidence} catches what is wrong with it. The CHECK still runs, and still
 * proves the shape at the last line.
 *
 * <p><b>There is no free-text component, deliberately.</b> An earlier version carried an operator
 * {@code note}, written to §3.1's {@code resolution_note} column. An external review pointed out what
 * that is under {@code I6}: the one column an authenticated human could paste a token, a password or
 * command output into during an incident — persisted, and then printed by Spring JDBC's bind-value
 * TRACE line the moment someone turns TRACE on to diagnose that incident. A comment box needs its own
 * design (sensitive-content handling, retention, access control, logging) before any engine path
 * writes it, and v1 has none. The column stays in the DDL (schema ahead of use, stated in the design's
 * residual table); {@code ApplyRequestRepository.resolveIndeterminate} never binds it, so it is NULL
 * for every v1 resolution — {@code OutcomeTransitionsIT} asserts exactly that.
 *
 * @param resolvedBy the principal who confirmed — never a display name
 * @param outcome    whether the generation counts as applied
 * @param evidence   the typed observation set the confirmation rests on
 */
public record Resolution(String resolvedBy, ResolutionOutcome outcome, ResolutionEvidence evidence) {

    public Resolution {
        Objects.requireNonNull(resolvedBy, "resolved by");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(evidence, "resolution evidence");
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code apply_step_intent} row — §2.2 rule 2's immutable identity substrate: exactly one per
 * logical {@code (apply, step, replica)}, holding the DP-assigned key and the INTENDED hash.
 *
 * <p>No validation of the two step/shape CHECKs happens here on purpose. {@code ApplyCommand} mirrors
 * its CHECKs because a malformed command is an operator-facing error; an intent is minted by the
 * engine from a {@link Step} and a {@link ReplicaRef} it already chose, so mirroring
 * {@code hash_on_config_steps} in Java would hide the schema half that {@code StepIntentIT} exists to
 * prove fires.
 */
public record StepIntentRow(UUID intentId, ApplyId applyId, Step step, ReplicaRef replica,
        String idempotencyKey, String configHash, Instant createdAt) {

    public StepIntentRow {
        Objects.requireNonNull(intentId, "intent id");
        Objects.requireNonNull(applyId, "apply id");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(replica, "replica");
        Objects.requireNonNull(idempotencyKey, "idempotency key");
        Objects.requireNonNull(createdAt, "created at");
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code apply_attempt} row — one TRY of one intent. §2.2 rule 0's write-before-act artifact: the
 * row is committed before the adapter is invoked, which is what makes "an intent with no attempt row
 * provably issued nothing" a fact rather than a hope.
 *
 * <p>The three columns design §3.1's residual table names under {@code I6} —
 * {@code pre_baseline}, {@code error_class}, {@code runtime_ref} — are TYPED here rather than carried
 * as free-form text and jsonb: {@link PreBaseline}, {@link ErrorClass} and {@link RuntimeRef} each
 * refuse a secret in their constructor, before any SQL is built. {@code observedHash} stays a
 * {@link String} because it is one, but it is checked against the same
 * {@code <algorithm>:<hex>} grammar {@link ReplicaObservation} uses.
 *
 * @param attemptNo    1-based ({@code attempt_no_positive}); the configured cap is the engine's
 * @param preBaseline  process/container start baseline, LIFECYCLE only
 *                     ({@code baseline_only_for_lifecycle}); process metadata, never config
 * @param stepDeadline the bounded budget of §2.2 rule 4
 * @param outcome      null while the attempt is open
 * @param observedHash read-back EVIDENCE, never intent
 * @param errorClass   a CLASSIFIED code, never raw error text ({@code I6})
 * @param runtimeRef   adapter-scoped identifiers (design §2.3), never configuration
 */
public record AttemptRow(UUID attemptId, UUID intentId, Step step, short attemptNo,
        PreBaseline preBaseline, Instant startedAt, Instant endedAt, Instant stepDeadline,
        AttemptOutcome outcome, String observedHash, ErrorClass errorClass, RuntimeRef runtimeRef) {

    public AttemptRow {
        Objects.requireNonNull(attemptId, "attempt id");
        Objects.requireNonNull(intentId, "intent id");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(startedAt, "started at");
        Objects.requireNonNull(stepDeadline, "step deadline");
        if (observedHash != null) {
            ValueGrammar.require(ValueGrammar.HASH, observedHash, "an observed hash",
                    "<algorithm>:<hex>, read back from the replica — evidence, never intent");
        }
    }
}
```



The **typed evidence seam** (`I6`, design §3.1's residual table as amended): four values that used to be free `String`/jsonb are **shape controls** — their grammars exclude environment lines (`KEY=value`), `Bearer …` headers, padded base64, PEM, JSON blobs, stack traces and exception text, and `RuntimeRef`'s keys are a **closed enum** of design §2.3's runtime identifiers; they do **not** exclude a bare identifier-shaped token in a value (a compact JWT fits the alphabet — `EvidenceTypesTest` asserts that acceptance so the limit is executable, not prose). The I6 *content* control on these columns is each adapter's, tested against sentinel secrets from its own inputs — Plan 005's named obligation. The check happens in Java before any SQL; the database CHECK stays the last line. `ValueGrammar` holds the shared patterns so there is one place to weaken:

```java
package com.solace.sm.dp.apply.model;

import java.util.regex.Pattern;

/**
 * The two character grammars the evidence types of design §3.1 share, in one place.
 *
 * <p><b>These are SHAPE controls.</b> {@code error_class}, {@code pre_baseline} and
 * {@code runtime_ref} are the three columns §3.1's own table names as "content, not shape — engine
 * classification tests", and this class is deliberately only the first half of that. A grammar that
 * cannot express {@code =}, a space, a quote, a backslash or a newline excludes an environment line
 * ({@code password=hunter2}), a {@code Bearer eyJ…} header, padded base64, PEM, a JSON blob, a stack
 * trace and any exception text — whatever a caller intends, and that much IS structural.
 *
 * <p><b>What it does not exclude is a bare identifier-shaped token.</b> A compact JWT is base64url and
 * dots; a hex secret is hex; both match {@link #VALUE} and both are accepted. So the grammar reduces
 * the surface to "one identifier-shaped token per field" and stops there. The {@code I6} CONTENT
 * obligation is the ADAPTER's: whatever builds a {@link RuntimeRef} or a {@link PreBaseline} is the
 * only writer of these columns, and each adapter must be tested against sentinel secrets drawn from
 * its own inputs. Plan 005 carries that obligation by name.
 *
 * <p><b>A refusal never echoes the value it refused.</b> Naming the rule is diagnosable; repeating the
 * rejected text would copy the very value the type is filtering into a log line, an exception message
 * and a stack trace.
 */
final class ValueGrammar {

    /**
     * Adapter identifiers: container ids, unit names, namespaces, generation-named ConfigMap names.
     * No whitespace, no quotes, no {@code =}, no backslash.
     */
    static final Pattern VALUE = Pattern.compile("^[A-Za-z0-9._:/@+-]{1,128}$");

    /** {@code <algorithm>:<hex>} — the read-back evidence shape the design writes as {@code sha256:…}. */
    static final Pattern HASH = Pattern.compile("^[a-z0-9]+:[0-9a-f]{16,128}$");

    private ValueGrammar() {
    }

    /**
     * @param what the field being validated, for the message
     * @param rule the human-readable rule, quoted back to the caller INSTEAD of the value
     * @throws IllegalArgumentException naming {@code what} and {@code rule}, never the value
     */
    static String require(Pattern p, String value, String what, String rule) {
        if (value == null || !p.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must match " + p.pattern() + " — " + rule
                    + ". The offending value is deliberately not repeated here (I6)");
        }
        return value;
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.regex.Pattern;

/**
 * {@code apply_attempt.error_class} — a CLASSIFIED, greppable code, never raw exception text.
 *
 * <p><b>This is a SHAPE control.</b> The grammar — dotted, lower-case,
 * {@code ^[a-z][a-z0-9]*(\.[a-z0-9]+)*$}, at most {@value #MAX_LENGTH} characters — cannot express
 * {@code =}, a space, a quote, a slash, an uppercase letter or a newline, so an environment line
 * ({@code password=hunter2}), a {@code Bearer} header or any structured secret representation, padded
 * base64, PEM, a JSON blob, a stack trace and raw exception text are all excluded by construction. What
 * fits is what the design writes: {@code io.timeout}, {@code docker.api.unreachable},
 * {@code hash.mismatch}.
 *
 * <p><b>It is not a content control.</b> A bare lower-case identifier-shaped token still fits — a hex
 * secret is lower-case hex. Design §3.1's residual table calls {@code I6} in {@code error_class}
 * "content, not shape", pinned by "engine classification tests", and that half is the CALLER's: the
 * engine (and, in Plan 005, each adapter) chooses the classification, and its own tests are where a
 * classifier that pastes a token into a code is caught. This type narrows the surface to one dotted
 * lower-case token; it does not decide what that token means.
 *
 * <p>The column is {@code text}, so there is no JSON here: the persistence layer stores
 * {@link #token} directly.
 *
 * @param token the classification code
 */
public record ErrorClass(String token) {

    /** Long enough for a four-segment dotted code; short enough that no payload fits. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern TOKEN = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z0-9]+)*$");

    public ErrorClass {
        // The length is checked first and reported WITH the pattern, so every refusal from this
        // constructor names the same rule whichever half of it was broken.
        if (token == null || token.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("an error class must match " + TOKEN.pattern()
                    + " and be at most " + MAX_LENGTH + " characters — a CLASSIFIED code such as "
                    + "io.timeout, never raw error text (I6). Was "
                    + (token == null ? "null" : token.length() + " characters")
                    + "; the offending value is deliberately not repeated here");
        }
        ValueGrammar.require(TOKEN, token, "an error class",
                "a CLASSIFIED code such as io.timeout or docker.api.unreachable, never raw error "
                        + "text (I6)");
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code apply_attempt.runtime_ref} — the adapter-scoped reference fields of design §2.3.
 *
 * <p>§2.3 is explicit about what belongs here: "runtime-specific identifiers (container id, unit name,
 * namespace/workload ref) live in adapter-scoped reference fields, never as columns the core model or
 * API depends on". {@link Key} is that list, closed: the nine runtime identifiers §2.3's adapter table
 * names and nothing else. There is no free-{@code String} key any more, so an adapter cannot invent a
 * tenth field, and {@code Evidence} refuses an unknown key on the way back out of the {@code jsonb} too
 * — a row written by some other build cannot introduce one either.
 *
 * <p><b>What this type is: a SHAPE control.</b> Values must match
 * {@code ^[A-Za-z0-9._:/@+-]{1,128}$}, which admits {@code 9f2c1e…}, {@code solace-cache.service},
 * {@code prod/cache-engine} and {@code cache-config-g17}, and admits neither whitespace, nor quotes,
 * nor {@code =}, nor a backslash. That excludes an environment line ({@code KEY=value}), a
 * {@code Bearer …} header, padded base64, PEM, a JSON blob, a stack trace and any exception text.
 *
 * <p><b>What this type is NOT: a content control.</b> A bare identifier-shaped token passes — a compact
 * JWT ({@code eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig}) has only base64url characters and dots, a hex
 * secret is hex. If a secret is placed in a value, the grammar accepts it. The {@code I6} CONTENT
 * obligation on {@code runtime_ref} (and on {@code pre_baseline} and {@code error_class}) therefore
 * belongs to the ADAPTER: each adapter's {@code RuntimeRef} builder is the only writer of these
 * fields, and each must be tested against sentinel secrets drawn from its own inputs — docker inspect
 * output, the process environment, mounted files. That is a named obligation Plan 005 carries, not
 * something this record discharges.
 *
 * <p>The map is defensively copied, unmodifiable, and keeps insertion order (a {@link LinkedHashMap})
 * so the JSON this value serializes to is stable and diffable. That is a property of the VALUE, not of
 * the column: PostgreSQL's {@code jsonb} normalises key order on storage (shortest key first), which is
 * why equality here is map equality and no test asserts a stored key order.
 *
 * @param fields the adapter's own identifiers for the thing an attempt touched
 */
public record RuntimeRef(Map<Key, String> fields) {

    /**
     * The closed set of runtime identifiers — design §2.3's adapter table names, and the whole of what
     * {@code runtime_ref} may hold.
     *
     * <p>Each member carries the {@link #wire()} name used as the JSON key, so the Java constant can be
     * {@code SCREAMING_SNAKE} while the stored document keeps the lower-camel names the design writes.
     * The wire names are part of the stored contract: renaming one changes every {@code jsonb} document
     * this build reads.
     */
    public enum Key {

        /** Docker/OCI container id. */
        CONTAINER_ID("containerId"),

        /** The image the container was started from, as {@code sha256:…}. */
        IMAGE_DIGEST("imageDigest"),

        /** systemd unit, for the VM adapter. */
        UNIT_NAME("unitName"),

        /** The host the unit or container runs on. */
        HOST("host"),

        /** Kubernetes namespace. */
        NAMESPACE("namespace"),

        /** The Deployment/StatefulSet the pod belongs to. */
        WORKLOAD("workload"),

        /** The individual pod. */
        POD_NAME("podName"),

        /** The generation-named ConfigMap a materialize step wrote. */
        CONFIG_MAP_NAME("configMapName"),

        /** Where the rendered config was placed. */
        MOUNT_PATH("mountPath");

        private final String wire;

        Key(String wire) {
            this.wire = wire;
        }

        /** The JSON key this member is stored under. */
        public String wire() {
            return wire;
        }

        /**
         * @param wire a key read out of a stored {@code runtime_ref} document
         * @return the member that owns it
         * @throws IllegalArgumentException naming the closed set — never echoing the value, which
         *                                  came from a database column and may be anything
         */
        public static Key ofWire(String wire) {
            for (Key k : values()) {
                if (k.wire.equals(wire)) {
                    return k;
                }
            }
            throw new IllegalArgumentException("a runtime ref key must be one of " + wireNames()
                    + " (design §2.3's adapter identifiers) — the offending key is deliberately not "
                    + "repeated here (I6)");
        }

        /** The wire names, in declaration order, for a refusal message. */
        static String wireNames() {
            StringBuilder b = new StringBuilder();
            for (Key k : values()) {
                b.append(b.length() == 0 ? "" : ", ").append(k.wire);
            }
            return b.toString();
        }
    }

    public RuntimeRef {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("a runtime ref carries at least one of design §2.3's "
                    + "adapter identifiers, was " + (fields == null ? "null" : "empty"));
        }
        Map<Key, String> copy = new LinkedHashMap<>();
        for (Map.Entry<Key, String> e : fields.entrySet()) {
            Key key = e.getKey();
            if (key == null) {
                throw new IllegalArgumentException("a runtime ref key must be one of "
                        + Key.wireNames() + ", was null");
            }
            ValueGrammar.require(ValueGrammar.VALUE, e.getValue(),
                    "the runtime ref value of '" + key.wire() + "'",
                    "an adapter-scoped IDENTIFIER (design §2.3) — a container id, a unit name, a "
                            + "namespace, a generation-named ConfigMap. The grammar is a SHAPE check; "
                            + "keeping a secret out of the VALUE is the adapter's obligation (I6)");
            copy.put(key, e.getValue());
        }
        fields = Collections.unmodifiableMap(copy);
    }

    /** One identifier. */
    public static RuntimeRef of(Key key, String value) {
        return new RuntimeRef(pairs(key, value));
    }

    /** Two. Overloads rather than varargs, because alternating types are not expressible as one. */
    public static RuntimeRef of(Key k1, String v1, Key k2, String v2) {
        Map<Key, String> m = pairs(k1, v1);
        m.put(k2, v2);
        return new RuntimeRef(m);
    }

    /** Three. */
    public static RuntimeRef of(Key k1, String v1, Key k2, String v2, Key k3, String v3) {
        Map<Key, String> m = pairs(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return new RuntimeRef(m);
    }

    /** Four. Beyond this, an adapter builds the map and calls the canonical constructor. */
    public static RuntimeRef of(Key k1, String v1, Key k2, String v2, Key k3, String v3, Key k4,
            String v4) {
        Map<Key, String> m = pairs(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        return new RuntimeRef(m);
    }

    private static Map<Key, String> pairs(Key key, String value) {
        Map<Key, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code apply_attempt.pre_baseline} — the process/container start baseline a {@code LIFECYCLE}
 * attempt records BEFORE it acts, and compares against on resume.
 *
 * <p>Design §2.2 rule 3: "compare {@code status(replica)}'s start baseline against the attempt's
 * recorded {@code pre_baseline}: restart observed → continue; baseline unchanged → re-issue under the
 * same key; baseline missing or unreadable → {@code APPLY_INDETERMINATE}". So the whole content of this
 * row is process METADATA — when the thing was last started, and which process it was.
 *
 * <p><b>{@code processRef} is shape-controlled, not content-controlled.</b> It carries the same
 * grammar as a {@link RuntimeRef} value, which excludes an environment line, a {@code Bearer} header or
 * any structured secret representation, padded base64, PEM, a JSON blob, a stack trace and exception
 * text — and admits any bare identifier-shaped token, a compact JWT or a hex secret included. Keeping a
 * secret out of the VALUE is the {@code I6} obligation of whichever adapter reads the baseline (docker
 * inspect, systemd, the Kubernetes API); its own contract test against sentinel secrets is where that
 * is enforced, and Plan 005 carries it by name.
 *
 * <p>{@code baseline_only_for_lifecycle} is the schema half of the same statement: no other step may
 * carry one at all.
 *
 * @param startedAt  when the observed process/container started
 * @param processRef the adapter's identifier for it — a container id, a PID-and-boot-id, a pod name
 */
public record PreBaseline(Instant startedAt, String processRef) {

    public PreBaseline {
        Objects.requireNonNull(startedAt, "pre-baseline started at");
        ValueGrammar.require(ValueGrammar.VALUE, processRef, "a pre-baseline process ref",
                "an adapter identifier for the observed process (design §2.2 rule 3) — process "
                        + "metadata, never configuration (I6)");
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * One replica's line of the evidence an operator resolves an {@code APPLY_INDETERMINATE} with —
 * design §3.1's "replica-ELEMENT typing is the engine's typed resolver (own refusal test)".
 *
 * <p>The database's {@code resolution_is_evidence} CHECK proves the SHAPE around these elements: an
 * object, a NUMBER {@code stagedGeneration}, a NON-EMPTY ARRAY {@code replicas}. It deliberately does
 * not type the elements — at the PG 14 floor a per-element JSONB shape check is not expressible. This
 * record is the typed resolver the design promises in its place, so the CHECK is the last line rather
 * than the only one.
 *
 * @param ref          which replica was observed
 * @param restarted    whether it was seen to have restarted onto the staged generation
 * @param observedHash the read-back hash, or null where the adapter could not read one
 */
public record ReplicaObservation(ReplicaRef ref, boolean restarted, String observedHash) {

    public ReplicaObservation {
        Objects.requireNonNull(ref, "replica ref");
        if (observedHash != null) {
            ValueGrammar.require(ValueGrammar.HASH, observedHash, "an observed hash",
                    "<algorithm>:<hex>, read back from the replica — evidence, never intent");
        }
    }
}
```

```java
package com.solace.sm.dp.apply.model;

import java.util.List;

/**
 * {@code apply_request.resolution_observed}, typed — the shape {@code resolution_is_evidence} demands,
 * enforced at the seam so the CHECK is the last line rather than the only one.
 *
 * <p>Design §3.1 says it plainly: "resolution is EVIDENCE: {@code resolution_outcome = 'applied'} feeds
 * rule 1's monotonicity and must never rest on an unsupported claim… this CHECK is evidence-shaped,
 * deliberately not claimed evidence-complete". Everything the CHECK proves — a positive
 * {@code stagedGeneration}, a non-empty {@code replicas} array — this record proves first, in Java,
 * before any SQL is built; and it proves the two things the CHECK cannot, namely that the elements are
 * replica observations and that the JSON has no other keys at all.
 *
 * @param stagedGeneration the generation the operator is confirming; positive, like every generation
 * @param replicas         at least one observation — an empty list is the "unsupported claim"
 */
public record ResolutionEvidence(long stagedGeneration, List<ReplicaObservation> replicas) {

    public ResolutionEvidence {
        if (stagedGeneration <= 0) {
            throw new IllegalArgumentException(
                    "resolution evidence names a positive stagedGeneration, was " + stagedGeneration);
        }
        if (replicas == null || replicas.isEmpty()) {
            throw new IllegalArgumentException("resolution evidence names at least one replica "
                    + "observation — resolution_is_evidence refuses an empty array, and an operator "
                    + "confirming from nothing is the case the state exists to prevent");
        }
        replicas = List.copyOf(replicas);
    }
}
```

Run the Step 1 command again after Step 3 (which adds `Evidence`, the persistence-side serializer the JSON test needs). Expected: `Uuid7Test` 5, `ApplyCommandTest` 12, `EvidenceTypesTest` 10, `RequestStateTest` 3, `EvidenceJsonTest` 6 — 36 tests, 0 failures; the console prints the three `MEASURED: … JSON = …` lines and `MEASURED: a bare compact token is ACCEPTED by shape …` — the honesty assertion, executable.
- [ ] **Step 3: The persistence seam — classifier, names, `Sql`, `Evidence`, four repositories**

`PgConstraintViolation.java` — the named refusal the engine branches on. It is built from **four classifiers and nothing else** (SQLSTATE, constraint, table, column) — **no message text and no driver cause**. PostgreSQL's own message includes `DETAIL: Failing row contains (…)` — the whole rejected row, jsonb included — and a message that embedded it was measured echoing a sentinel written to a text column; a second version kept the `PSQLException` as its *cause*, and Codex's third pass showed that is the same leak one hop later, because ordinary `log.error("persistence failure", ex)` makes Logback print the cause chain (measured in `AttemptIT`, at the logger). The class javadoc states what this does **not** cover: non-class-23 refusals propagate from `Sql.write` as Spring exceptions with the driver cause attached; their PostgreSQL primary messages name objects and the §3.1 triggers echo identifiers, never row text:

```java
package com.solace.sm.dp.apply.persistence;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * A PostgreSQL integrity refusal, carrying the NAME the design's harness rule insists on.
 *
 * <p>Design §3.3: "every refusal is asserted per-statement with its constraint/index/trigger NAME".
 * The engine needs the same discipline at runtime, not only in tests: §3.3's admission classifies a
 * fence 23505 by which fence it names, and §2.2 rule 2 decides whether a 23505 on the intent table
 * warrants a fresh-transaction re-read by whether it names one of the two intent UNIQUEs. Spring's
 * {@code DuplicateKeyException} carries no name at all.
 *
 * <p>Only SQLSTATE class {@code 23} (integrity constraint violation) becomes one of these. A
 * privilege refusal (42501), a serialization failure (40001) or a broken connection is NOT an
 * integrity refusal and must not be reshaped into one — {@link #from} returns empty and the caller
 * rethrows the original.
 *
 * <p>{@link #constraint()} can be null even inside class 23: a {@code 23502} names a COLUMN and no
 * constraint (measured in {@code RefusalShapesIT}). Callers switch on the name and must handle null.
 *
 * <p><b>No driver exception is attached — deliberately, and this is an {@code I6} rule, not a
 * tidiness one.</b> PostgreSQL's error text carries {@code DETAIL: Failing row contains (…)} (and, for
 * a unique violation, {@code Key (…)=(…) already exists}), which reproduces the rejected row — jsonb
 * columns included — and Logback prints the WHOLE cause chain of anything passed to
 * {@code log.error(msg, ex)}. A version of this class that kept the {@code PSQLException} as its cause
 * had a sanitized message and still leaked a sentinel written to a text column through ordinary
 * application logging (measured in {@code AttemptIT}). So this exception is built from four
 * classifiers — SQLSTATE, constraint, table, column — and nothing else: no cause, no driver message.
 * The stack trace is this exception's own (it is raised inside the repository's write), which is where
 * a debugger needs to look anyway; the rejected values are what the caller just tried to write, and
 * are in the caller's hands.
 *
 * <p>What this does NOT cover, stated: {@link #from} reshapes class 23 only. A refusal in any other
 * class — {@code 42501} privilege, {@code 40001} serialization, class {@code 08} connection, and
 * class {@code P0} for the design's PL/pgSQL triggers — propagates from {@code Sql.write} as Spring's
 * {@code DataAccessException} WITH the driver exception attached. PostgreSQL's primary message for
 * those classes names objects (a table, a role, a host, a function), and the design §3.1 triggers echo
 * generations, an {@code apply_id} and a state — identifiers, never row text. A class {@code 22} data
 * exception CAN echo an input value; every column here is bound as a typed parameter (uuid, timestamptz,
 * jsonb from Jackson), so a class-22 refusal would be a repository defect rather than a data path, and
 * no test here claims otherwise.
 */
public final class PgConstraintViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** SQLSTATE class 23 — integrity_constraint_violation and its five members. */
    private static final String INTEGRITY_CLASS = "23";

    private final String sqlState;
    private final String constraint;
    private final String table;
    private final String column;

    private PgConstraintViolation(String sqlState, String constraint, String table, String column) {
        // Classifiers ONLY, and no cause: see the class javadoc for the measured leak that rules out
        // attaching the driver exception.
        super(sqlState + (constraint == null ? "" : " on " + constraint)
                + (table == null ? "" : " in " + table)
                + (column == null ? "" : " (column " + column + ")"));
        this.sqlState = sqlState;
        this.constraint = constraint;
        this.table = table;
        this.column = column;
    }

    public String sqlState() {
        return sqlState;
    }

    /** The constraint/index name PostgreSQL reported, or null (a 23502 reports a column instead). */
    public String constraint() {
        return constraint;
    }

    public String table() {
        return table;
    }

    /** The column, for the refusals that name one — {@code 23502} does, a unique violation does not. */
    public String column() {
        return column;
    }

    /** True when {@link #constraint()} equals any of {@code names}; null-safe. */
    public boolean namesAnyOf(String... names) {
        for (String n : names) {
            if (n.equals(constraint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the cause chain for a {@link PSQLException} and reshapes it — but only for SQLSTATE class
     * {@code 23}.
     *
     * <p>The walk is necessary because the driver exception arrives wrapped: Spring's
     * {@code SQLExceptionSubclassTranslator} produces a {@code DataIntegrityViolationException} whose
     * cause is the {@code PSQLException}. Reading {@code getMessage()} of the wrapper would give the
     * text and lose the structured fields. Only the four structured fields are read; the driver
     * exception is then dropped.
     */
    public static Optional<PgConstraintViolation> from(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PSQLException pg) {
                String state = pg.getSQLState();
                if (state == null || !state.startsWith(INTEGRITY_CLASS)) {
                    return Optional.empty();
                }
                ServerErrorMessage m = pg.getServerErrorMessage();
                return Optional.of(new PgConstraintViolation(state,
                        m == null ? null : m.getConstraint(),
                        m == null ? null : m.getTable(),
                        m == null ? null : m.getColumn()));
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return Optional.empty();
    }
}
```

`Constraints.java` — the six names control flow depends on (measured in Task 2's `RefusalShapesIT`):

```java
package com.solace.sm.dp.apply.persistence;

/**
 * The constraint and index NAMES the engine branches on — one place, so a migration rename breaks
 * compilation rather than behaviour.
 *
 * <p>Two of these are not in the design text at all: PostgreSQL derived them from table and column
 * names for the design's inline {@code UNIQUE (…)} clauses. {@code RefusalShapesIT} pins the full
 * generated set; these are the subset control flow depends on.
 *
 * <p>Public because {@code ..apply.engine} classifies refusals by name (§2.2 rule 2) and must not
 * hold string literals of its own. They are names, not JDBC types, so exporting them costs the
 * persistence seam nothing.
 */
public final class Constraints {

    /** Design-named partial unique index: at most one in-flight apply/restart per scope. */
    public static final String APPLY_INFLIGHT_FENCE = "apply_inflight_fence";
    /** Design-named partial unique index: at most one in-flight stop per scope. */
    public static final String STOP_INFLIGHT_FENCE = "stop_inflight_fence";
    /** Design-named partial unique index: at most one STAGED row per scope. */
    public static final String STAGED_FENCE = "staged_fence";

    /**
     * The composite intent UNIQUE — PostgreSQL-generated from {@code UNIQUE (tenant_id, apply_id,
     * step, replica_ref)}. A 23505 naming it or {@link #INTENT_IDEMPOTENCY_KEY_UNIQUE} sends the
     * engine into a fresh-transaction re-read (§2.2 rule 2 as amended); the re-read's outcome, not
     * the name, decides between "race lost" and "protocol error".
     */
    public static final String INTENT_STEP_REPLICA_UNIQUE =
            "apply_step_intent_tenant_id_apply_id_step_replica_ref_key";

    /**
     * The idempotency key's own UNIQUE — PostgreSQL-generated from the inline {@code NOT NULL UNIQUE}.
     * Measured: a legitimate concurrent same-step create violates BOTH intent UNIQUEs and PostgreSQL
     * names THIS one first, so it is not by itself the protocol error — see
     * {@code StepIntentService}.
     */
    public static final String INTENT_IDEMPOTENCY_KEY_UNIQUE = "apply_step_intent_idempotency_key_key";

    /**
     * {@code UNIQUE (intent_id, attempt_no)} — PostgreSQL-generated. Two concurrent {@code open()}s
     * for one intent both read the same count at READ COMMITTED; this index is what makes exactly one
     * of them the attempt it claims to be (measured), and the engine classifies the loser by this name.
     */
    public static final String ATTEMPT_NO_UNIQUE = "apply_attempt_intent_id_attempt_no_key";

    private Constraints() {
    }
}
```

`Sql.java` — the one write wrapper, the `Instant`↔`OffsetDateTime` seam (measured: pgjdbc 42.7.11 does not bind `java.time.Instant`), and `redacted(String)` — the `SqlValue` wrapper every jsonb bind in this package goes through, so Spring's `StatementCreatorUtils` TRACE bind-value line (measured: it prints every plain bind value) renders `[redacted text, N chars]` instead of the evidence document:

```java
package com.solace.sm.dp.apply.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.SqlValue;

/**
 * The four mechanical facts every repository in this package repeats, factored out once.
 *
 * <p><b>Time.</b> The model speaks {@link Instant}; the pgjdbc driver does not bind one. Its
 * {@code setObject} handles {@code OffsetDateTime}, {@code LocalDateTime} and friends, and hands an
 * {@code Instant} to its generic object path where it fails to infer a SQL type. So every
 * {@code timestamptz} crosses the seam as an {@code OffsetDateTime} at UTC, converted here rather
 * than in eight call sites.
 *
 * <p><b>Content columns are bound redacted.</b> Spring's {@code StatementCreatorUtils} logs EVERY bind
 * value at TRACE — {@code Setting SQL statement parameter value: column index 7, parameter value
 * [{…}]} (measured in {@code AttemptIT}) — so the moment an operator turns TRACE on to diagnose an
 * incident, every jsonb evidence document would be printed. {@link #redacted} wraps such a value in a
 * {@link SqlValue} whose {@code toString} is a length, not the text: Spring renders the wrapper in its
 * TRACE line and calls {@link SqlValue#setValue} to bind the real string. The control is structural —
 * it does not depend on a logger level anyone can change — and is applied to every jsonb bind in this
 * package. Identifiers, enums, hashes and timestamps stay plain: they ARE what a diagnostician needs.
 *
 * <p><b>Refusal reshaping.</b> {@link #write} is the only place a write is issued: it turns a
 * {@link DataAccessException} whose chain holds a class-23 {@code PSQLException} into a
 * {@link PgConstraintViolation} carrying the constraint NAME, and rethrows everything else
 * untouched. Repositories that hand-rolled this would each be one {@code catch} away from swallowing
 * a privilege error as an integrity one.
 */
final class Sql {

    private Sql() {
    }

    /** Runs a write, reshaping integrity refusals into a named {@link PgConstraintViolation}. */
    static int write(IntSupplierThrowingDataAccess body) {
        try {
            return body.get();
        } catch (DataAccessException e) {
            throw PgConstraintViolation.from(e).map(RuntimeException.class::cast).orElse(e);
        }
    }

    /**
     * Binds {@code text} so it reaches the statement but never Spring's TRACE bind-value line.
     *
     * @return null for null (so Spring binds SQL NULL exactly as for a plain null), else a wrapper
     */
    static Object redacted(String text) {
        return text == null ? null : new RedactedText(text);
    }

    /** A bound string that renders as its length. Package-private so a test can name the class. */
    static final class RedactedText implements SqlValue {

        private final String text;

        RedactedText(String text) {
            this.text = text;
        }

        @Override
        public void setValue(PreparedStatement ps, int paramIndex) throws SQLException {
            ps.setString(paramIndex, text);
        }

        @Override
        public void cleanup() {
        }

        @Override
        public String toString() {
            return "[redacted text, " + text.length() + " chars]";
        }
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    /** A {@code bigint} column that may be SQL NULL — {@code getLong} would silently return 0. */
    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** {@code IntSupplier} that may throw Spring's unchecked data-access exceptions. */
    @FunctionalInterface
    interface IntSupplierThrowingDataAccess {
        int get();
    }
}
```

`Evidence.java` — the ONE JSON mapper in the persistence package: the typed evidence goes to `jsonb` here and nowhere else, with the `resolution_observed` key names the database CHECK reads fixed in one place (the ArchUnit mapper rule confines mappers away from *publisher* packages because events have one contract mapper; persistence `jsonb` is a different concern, stated in the javadoc). Its two corruption reports carry no column content: `read` and `instant` attach no cause to what they throw, the mapper disables `StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` explicitly as defence in depth, and `Instant.parse` is wrapped because `DateTimeParseException` quotes the text (measured, `EvidenceJsonTest` — re-enabling `INCLUDE_SOURCE_IN_LOCATION` alone stays green; the no-cause property is what the tests pin):

```java
package com.solace.sm.dp.apply.persistence;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ResolutionEvidence;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three {@code jsonb} columns of design §3.1, serialized here and nowhere else.
 *
 * <p><b>Why this package holds a mapper.</b> {@code arch-tests}' {@code meshbridgeHoldsNoOwnMapper}
 * confines mappers away from the PUBLISHER package, and its reason is specific: mesh events have ONE
 * contract mapper ({@code EventJson}) because the bytes on the wire are a published contract that two
 * modules and a JSON-Schema catalogue must agree on. Persistence {@code jsonb} is a different concern —
 * the bytes are read only by this database's own CHECK constraints and by this class — so a mapper
 * here is not the thing that rule forbids. The rule is package-scoped for exactly that reason, and this
 * class keeps the concern honest by being the ONE place in the apply stack that builds JSON.
 *
 * <p><b>The key names are load-bearing.</b> {@code resolution_is_evidence} reads
 * {@code resolution_observed -> 'stagedGeneration'} and {@code -> 'replicas'} by name; renaming either
 * would not fail a compile, it would make every resolution refuse with a 23514. They are written
 * explicitly below rather than derived from record component names, so the coupling to the DDL is
 * visible at the point where it exists.
 *
 * <p>{@code error_class} is not here: it is a {@code text} column, so the persistence layer stores
 * {@code ErrorClass.token()} directly and there is no JSON to get wrong.
 */
final class Evidence {

    /**
     * One mapper, built once. Every shape below is constructed and read field by field. The one
     * setting is an {@code I6} one: a parse failure's message must not quote the document — a
     * corrupt {@code jsonb} column would otherwise travel into the exception that reports it, and from
     * there into a log. Jackson 2.16+ redacts the source by default; it is disabled explicitly so a BOM
     * change or a global enable cannot silently reopen it ({@code EvidenceJsonTest} asserts the
     * redaction with a sentinel).
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    private Evidence() {
    }

    /** {@code {"startedAt":"<ISO-8601 instant>","processRef":"…"}}, or null for an absent baseline. */
    static String write(PreBaseline baseline) {
        if (baseline == null) {
            return null;
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("startedAt", baseline.startedAt().toString());
        root.put("processRef", baseline.processRef());
        return root.toString();
    }

    static PreBaseline readPreBaseline(String json) {
        JsonNode root = read(json, "pre_baseline");
        if (root == null) {
            return null;
        }
        return new PreBaseline(instant(root.path("startedAt").asText(), "pre_baseline.startedAt"),
                root.path("processRef").asText());
    }

    /**
     * The reference map as a JSON object, keyed by {@link RuntimeRef.Key#wire()} — insertion order
     * preserved by {@link RuntimeRef}.
     */
    static String write(RuntimeRef ref) {
        if (ref == null) {
            return null;
        }
        ObjectNode root = MAPPER.createObjectNode();
        ref.fields().forEach((key, value) -> root.put(key.wire(), value));
        return root.toString();
    }

    /**
     * The read half of the closed key set: a key outside {@link RuntimeRef.Key} is refused rather than
     * carried through.
     *
     * <p>Closing the Java API alone would leave the {@code jsonb} as the open door — a row written by
     * an older build, a hand-edit, or an adapter that reached the column some other way could still
     * introduce {@code apiToken}. {@link RuntimeRef.Key#ofWire} is what makes the enum a property of
     * the stored document and not only of the constructor.
     */
    static RuntimeRef readRuntimeRef(String json) {
        JsonNode root = read(json, "runtime_ref");
        if (root == null) {
            return null;
        }
        Map<RuntimeRef.Key, String> fields = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            fields.put(RuntimeRef.Key.ofWire(e.getKey()), e.getValue().asText());
        }
        return new RuntimeRef(fields);
    }

    /**
     * {@code {"stagedGeneration":N,"replicas":[{"ref":"…","restarted":true,"observedHash":…}]}}.
     *
     * <p>Those two top-level key names are the ones {@code resolution_is_evidence} reads.
     */
    static String write(ResolutionEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("stagedGeneration", evidence.stagedGeneration());
        ArrayNode replicas = root.putArray("replicas");
        for (ReplicaObservation r : evidence.replicas()) {
            ObjectNode node = replicas.addObject();
            node.put("ref", r.ref().value());
            node.put("restarted", r.restarted());
            if (r.observedHash() == null) {
                node.putNull("observedHash");
            } else {
                node.put("observedHash", r.observedHash());
            }
        }
        return root.toString();
    }

    static ResolutionEvidence readResolutionEvidence(String json) {
        JsonNode root = read(json, "resolution_observed");
        if (root == null) {
            return null;
        }
        List<ReplicaObservation> replicas = new ArrayList<>();
        for (JsonNode node : root.path("replicas")) {
            JsonNode hash = node.path("observedHash");
            replicas.add(new ReplicaObservation(new ReplicaRef(node.path("ref").asText()),
                    node.path("restarted").asBoolean(),
                    hash.isNull() || hash.isMissingNode() ? null : hash.asText()));
        }
        return new ResolutionEvidence(root.path("stagedGeneration").asLong(), replicas);
    }

    /**
     * {@link Instant#parse} without its message: {@code DateTimeParseException} quotes the text it could
     * not parse ({@code Text 'x' could not be parsed at index 0} — measured), which for a corrupt column
     * is the column's content. Same corruption-report posture as {@link #read}, no cause.
     */
    private static Instant instant(String text, String field) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("sm_data." + field
                    + " does not hold an ISO-8601 instant this build wrote");
        }
    }

    private static JsonNode read(String json, String column) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // The column can only have been written by write() above, so this is a corruption report,
            // not a parse-failure path a caller recovers from.
            throw new IllegalStateException("sm_data." + column + " does not hold JSON this build "
                    + "wrote", e);
        }
    }
}
```

`ApplyScopeRepository.java`:

```java
package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code apply_scope} and {@code placement_watermark} — the serialization point of design §3.3 and
 * the two maintained watermarks of §2.2 rule 1.
 *
 * <p>Every statement is tenant-keyed ({@code I5}) and every value is bound, never interpolated.
 */
public final class ApplyScopeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ApplyScopeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The scope row's single-arbiter upsert.
     *
     * <p>{@code ON CONFLICT DO NOTHING} is legitimate HERE and nowhere else in this design: the table
     * has exactly one unique key (its primary key), so there is no second constraint whose violation
     * could be silently absorbed. §2.2 rule 2's intent insert is the contrasting case — two UNIQUEs,
     * different meanings, so it is a plain INSERT and the 23505 is classified by name.
     */
    public void ensureScopeRow(TenantId t, InstanceId i) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                        + " ON CONFLICT (tenant_id, instance_id) DO NOTHING",
                key(t, i)));
    }

    /**
     * Takes the row lock the whole admission transaction is serialized by, and returns the instance
     * watermark it was holding.
     *
     * <p>{@code SELECT … FOR UPDATE} rather than {@code pg_advisory_xact_lock}: design §3.1 rejects
     * advisory locks because their keys are hashed into one database-wide namespace (int4
     * {@code hashtext} collides — measured) while this row is exact, tenant-keyed, and visible in
     * {@code pg_locks} under its own identity.
     *
     * @throws IllegalStateException if the row is absent — {@link #ensureScopeRow} must run first,
     *                               and {@code request_has_scope} would refuse the insert anyway
     */
    public long lockScope(TenantId t, InstanceId i) {
        try {
            Long v = jdbc.queryForObject(
                    "SELECT instance_watermark FROM sm_data.apply_scope"
                            + " WHERE tenant_id = :t AND instance_id = :i FOR UPDATE",
                    key(t, i), Long.class);
            return v == null ? 0L : v;
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                    "no apply_scope row to lock for tenant " + t.value() + " instance " + i.value()
                            + " — ensureScopeRow must precede lockScope in the same transaction", e);
        }
    }

    public long instanceWatermark(TenantId t, InstanceId i) {
        Long v = jdbc.queryForObject(
                "SELECT coalesce(max(instance_watermark), 0) FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                key(t, i), Long.class);
        return v == null ? 0L : v;
    }

    /**
     * One placement's watermark, or 0.
     *
     * <p>NULL-explicit on purpose (design §2.2 rule 1): a placement with no row contributes 0, never
     * SQL NULL into a comparison — {@code G <= NULL} is NULL, and a NULL predicate admits.
     */
    public long placementWatermark(TenantId t, InstanceId i, PlacementId p) {
        Long v = jdbc.queryForObject(
                "SELECT coalesce(max(generation), 0) FROM sm_data.placement_watermark"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p",
                key(t, i).addValue("p", p.value()), Long.class);
        return v == null ? 0L : v;
    }

    /** The cross-scope maximum an INSTANCE-WIDE apply is fenced by. */
    public long maxPlacementWatermark(TenantId t, InstanceId i) {
        Long v = jdbc.queryForObject(
                "SELECT coalesce(max(generation), 0) FROM sm_data.placement_watermark"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                key(t, i), Long.class);
        return v == null ? 0L : v;
    }

    /**
     * Raises the instance watermark, never lowers it.
     *
     * <p>{@code GREATEST} in the statement and the {@code apply_scope_monotonic} trigger behind it are
     * belt and braces on purpose: the statement makes a late-landing lower generation a no-op instead
     * of an error, and the trigger makes an engine bug that tried anyway a loud P0001.
     */
    public void raiseInstanceWatermark(TenantId t, InstanceId i, long g) {
        Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_scope SET instance_watermark = GREATEST(instance_watermark, :g)"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                key(t, i).addValue("g", g)));
    }

    /** The placement equivalent; the sentinel is refused here by {@code real_placements_only}. */
    public void raisePlacementWatermark(TenantId t, InstanceId i, PlacementId p, long g) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.placement_watermark"
                        + " (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)"
                        + " ON CONFLICT (tenant_id, instance_id, placement_id) DO UPDATE"
                        + " SET generation = GREATEST(placement_watermark.generation, EXCLUDED.generation)",
                key(t, i).addValue("p", p.value()).addValue("g", g)));
    }

    private static MapSqlParameterSource key(TenantId t, InstanceId i) {
        return new MapSqlParameterSource().addValue("t", t.value()).addValue("i", i.value());
    }
}
```

`ApplyRequestRepository.java`:

```java
package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code apply_request} — design §3.1's control history, and the DP's audit record for the
 * apply-family verbs.
 *
 * <p>The column set every UPDATE here touches is a subset of the column-scoped grant §3.1 issues to
 * {@code sm_dp_runtime}: {@code state, terminal_at, resolved_by, resolution_outcome,
 * resolution_observed, attempt_summary}. Identity columns are not runtime-writable and no method
 * offers to write them; {@code resolution_note} is in neither the INSERT nor the UPDATE grant and no
 * method names it (see {@link Resolution}) — the grant is the enforcement, this class simply does not
 * tempt it.
 */
public final class ApplyRequestRepository {

    private static final String COLUMNS =
            "apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,"
                    + " outage_confirmed_by, requested_by, requested_at, state, terminal_at";

    /** Mirrors {@code apply_inflight_fence}'s state predicate — see {@code RequestState.blocksScope}. */
    private static final String BLOCKING_STATES = "('REQUESTED','RUNNING','APPLY_INDETERMINATE')";

    private final NamedParameterJdbcTemplate jdbc;

    public ApplyRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a freshly admitted request.
     *
     * @throws IllegalArgumentException if the row is not {@code REQUESTED} — every other state is
     *                                  reached by {@link #transition}, so that the terminal-guard
     *                                  trigger sees a real UPDATE rather than a rewritten history
     * @throws PgConstraintViolation    on any class-23 refusal, carrying the fence's name
     */
    public void insert(ApplyRequestRow row) {
        if (row.state() != RequestState.REQUESTED) {
            throw new IllegalArgumentException(
                    "a request is born REQUESTED; " + row.state() + " is reached by transition()");
        }
        ScopeKey s = row.scope();
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_request (" + COLUMNS + ")"
                        + " VALUES (:a, :t, :i, :p, :op, :gen, :strategy, :outage, :by, :at, :state,"
                        + " :terminalAt)",
                new MapSqlParameterSource()
                        .addValue("a", row.applyId().value())
                        .addValue("t", s.tenant().value())
                        .addValue("i", s.instance().value())
                        .addValue("p", s.placement().value())
                        .addValue("op", row.operation().sql())
                        .addValue("gen", row.generation())
                        .addValue("strategy", row.strategy() == null ? null : row.strategy().sql())
                        .addValue("outage", row.outageConfirmedBy())
                        .addValue("by", row.requestedBy())
                        .addValue("at", Sql.at(row.requestedAt()))
                        .addValue("state", row.state().name())
                        .addValue("terminalAt", Sql.at(row.terminalAt()))));
    }

    public Optional<ApplyRequestRow> find(TenantId t, ApplyId id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value()),
                MAPPER).stream().findFirst();
    }

    /**
     * Every request that fences the instance — ANY placement, {@code stop} excluded.
     *
     * <p>Returning the whole set rather than a boolean is what lets §3.3's cross-scope rule live in
     * the engine, where {@link ScopeKey#overlaps} can express "either is instance-wide, or they are
     * equal" — the rule the partial unique indexes provably cannot see across the sentinel boundary.
     * The stop exclusion mirrors the fence's own {@code operation <> 'stop'} predicate, so the
     * database constraint and the admission check say the same thing rather than contradicting each
     * other.
     */
    public List<ApplyRequestRow> blockingRequests(TenantId t, InstanceId i) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND instance_id = :i"
                        + " AND state IN " + BLOCKING_STATES + " AND operation <> 'stop'",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("i", i.value()),
                MAPPER);
    }

    /** The stop family's own, narrower concurrency rule: at most one in flight per exact scope. */
    public boolean stopInFlight(TenantId t, InstanceId i, PlacementId p) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p"
                        + " AND operation = 'stop' AND state IN ('REQUESTED','RUNNING'))",
                scope(t, i, p), Boolean.class));
    }

    /** The elder {@code STAGED} row admission must mark {@code SUPERSEDED} before inserting. */
    public Optional<ApplyId> stagedRequest(TenantId t, InstanceId i, PlacementId p) {
        return jdbc.queryForList("SELECT apply_id FROM sm_data.apply_request"
                                + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p"
                                + " AND state = 'STAGED'",
                        scope(t, i, p), UUID.class)
                .stream().findFirst().map(ApplyId::new);
    }

    /**
     * A guarded state walk.
     *
     * @param terminalAt the terminal timestamp, or null for a non-terminal target
     *                   ({@code terminal_has_timestamp} is an equality, both ways)
     * @return rows updated: 0 means the row was not in {@code from} — a lost race or a wrong state,
     *         reported rather than thrown, because admission and resume both treat it as information
     */
    public int transition(TenantId t, ApplyId id, RequestState from, RequestState to,
            Instant terminalAt) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_request SET state = :to, terminal_at = :terminalAt"
                        + " WHERE tenant_id = :t AND apply_id = :a AND state = :from",
                new MapSqlParameterSource()
                        .addValue("t", t.value()).addValue("a", id.value())
                        .addValue("from", from.name()).addValue("to", to.name())
                        .addValue("terminalAt", Sql.at(terminalAt))));
    }

    /**
     * {@code APPLY_INDETERMINATE → INDETERMINATE_RESOLVED} with all four resolution columns written
     * in ONE statement — {@code resolution_is_evidence} is an equality over the whole set, so writing
     * them in two statements is not merely slower, it is impossible.
     *
     * <p>The evidence arrives TYPED and is serialized by {@link Evidence} here: the two key names the
     * CHECK reads, {@code stagedGeneration} and {@code replicas}, are produced by one method rather
     * than by whichever caller assembled a string. {@code resolution_note} is deliberately NOT in the
     * SET list — see {@link Resolution}: v1 writes no free text, so the column stays NULL.
     */
    public int resolveIndeterminate(TenantId t, ApplyId id, Resolution r, Instant at) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_request SET state = 'INDETERMINATE_RESOLVED',"
                        + " terminal_at = :at, resolved_by = :by, resolution_outcome = :outcome,"
                        + " resolution_observed = CAST(:observed AS jsonb)"
                        + " WHERE tenant_id = :t AND apply_id = :a AND state = 'APPLY_INDETERMINATE'",
                new MapSqlParameterSource()
                        .addValue("t", t.value()).addValue("a", id.value())
                        .addValue("at", Sql.at(at))
                        .addValue("by", r.resolvedBy())
                        .addValue("outcome", r.outcome().sql())
                        .addValue("observed", Sql.redacted(Evidence.write(r.evidence())))));
    }

    /** The resume read of design §3.1: what was in flight when the DP died. */
    public List<ApplyRequestRow> nonTerminal(TenantId t) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND state IN " + BLOCKING_STATES
                        + " ORDER BY requested_at",
                new MapSqlParameterSource("t", t.value()), MAPPER);
    }

    /**
     * The compaction fold — {@code attempt_summary} and nothing else.
     *
     * <p>This is the terminal guard's sanctioned write 2. The trigger compares
     * {@code to_jsonb(NEW) - 'attempt_summary'} against the same projection of OLD, so a statement
     * that also touched one further column would be refused with P0001; the statement is written
     * narrowly here so that the refusal is a bug report, not a normal path.
     */
    public int foldAttemptSummary(TenantId t, ApplyId id, String summaryJson) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_request SET attempt_summary = CAST(:s AS jsonb)"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource()
                        .addValue("t", t.value()).addValue("a", id.value())
                        .addValue("s", Sql.redacted(summaryJson))));
    }

    private static MapSqlParameterSource scope(TenantId t, InstanceId i, PlacementId p) {
        return new MapSqlParameterSource().addValue("t", t.value()).addValue("i", i.value())
                .addValue("p", p.value());
    }

    private static final RowMapper<ApplyRequestRow> MAPPER = (rs, n) -> {
        String strategy = rs.getString("strategy");
        return new ApplyRequestRow(
                new ApplyId(rs.getObject("apply_id", UUID.class)),
                new ScopeKey(new TenantId(rs.getObject("tenant_id", UUID.class)),
                        new InstanceId(rs.getObject("instance_id", UUID.class)),
                        new PlacementId(rs.getObject("placement_id", UUID.class))),
                Operation.fromSql(rs.getString("operation")),
                Sql.nullableLong(rs, "generation"),
                strategy == null ? null : Strategy.fromSql(strategy),
                rs.getString("outage_confirmed_by"),
                rs.getString("requested_by"),
                Sql.instant(rs, "requested_at"),
                RequestState.valueOf(rs.getString("state")),
                Sql.instant(rs, "terminal_at"));
    };
}
```

`StepIntentRepository.java`:

```java
package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code apply_step_intent} — read and INSERT only, because that is all the grant allows: the table
 * is immutable to {@code sm_dp_runtime} by DCL, not by convention.
 *
 * <p><b>Never {@code ON CONFLICT}.</b> Design §2.2 rule 2 is explicit, and it is a measured position
 * rather than a stylistic one: the table carries two meaningful UNIQUEs, and a conflict on the
 * NON-arbiter {@code idempotency_key} index aborts the transaction before any read-compare could run.
 * A plain INSERT surfaces the 23505 with its constraint name, and the engine decides which of the two
 * stories it is.
 */
public final class StepIntentRepository {

    private static final String COLUMNS =
            "intent_id, tenant_id, apply_id, replica_ref, step, idempotency_key, config_hash,"
                    + " created_at";

    private final NamedParameterJdbcTemplate jdbc;

    public StepIntentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StepIntentRow> find(TenantId t, ApplyId id, Step step, ReplicaRef replica) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_step_intent"
                        + " WHERE tenant_id = :t AND apply_id = :a AND step = :s"
                        + " AND replica_ref = :r",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value())
                        .addValue("s", step.name()).addValue("r", replica.value()),
                MAPPER).stream().findFirst();
    }

    /** @throws PgConstraintViolation on 23505 — classify it by {@link PgConstraintViolation#constraint()} */
    public void insert(TenantId t, StepIntentRow row) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_step_intent (" + COLUMNS + ")"
                        + " VALUES (:id, :t, :a, :r, :s, :k, :hash, :at)",
                new MapSqlParameterSource()
                        .addValue("id", row.intentId())
                        .addValue("t", t.value())
                        .addValue("a", row.applyId().value())
                        .addValue("r", row.replica().value())
                        .addValue("s", row.step().name())
                        .addValue("k", row.idempotencyKey())
                        .addValue("hash", row.configHash())
                        .addValue("at", Sql.at(row.createdAt()))));
    }

    /** Every intent of one request — half of the resume read of design §3.1. */
    public List<StepIntentRow> forRequest(TenantId t, ApplyId id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_step_intent"
                        + " WHERE tenant_id = :t AND apply_id = :a ORDER BY created_at, intent_id",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value()),
                MAPPER);
    }

    private static final RowMapper<StepIntentRow> MAPPER = (rs, n) -> new StepIntentRow(
            rs.getObject("intent_id", UUID.class),
            new ApplyId(rs.getObject("apply_id", UUID.class)),
            Step.valueOf(rs.getString("step")),
            new ReplicaRef(rs.getString("replica_ref")),
            rs.getString("idempotency_key"),
            rs.getString("config_hash"),
            Sql.instant(rs, "created_at"));
}
```

`AttemptRepository.java`:

```java
package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code apply_attempt} — the execution journal of §2.2 rule 0, and the only one of the three tables
 * the runtime may DELETE from (§3.3's compaction).
 *
 * <p>{@link #open} and {@link #close} are two statements on purpose: the open row is what
 * "write before act" means, and it must be COMMITTED while the outcome is still unknown. A single
 * insert-with-outcome would journal the past tense of something that had not happened yet.
 *
 * <p>The three {@code I6} columns cross this seam as TYPES, not strings: {@code pre_baseline} and
 * {@code runtime_ref} are serialized by {@link Evidence} here inside the persistence package, and
 * {@code error_class} is an {@link ErrorClass} token. There is no overload that accepts free text —
 * the SHAPE of what reaches the journal is the code's; what an adapter chooses to put inside that
 * shape (a bare identifier-shaped token fits) is the adapter's, under its own sentinel tests.
 */
public final class AttemptRepository {

    private static final String COLUMNS =
            "attempt_id, tenant_id, apply_id, intent_id, step, attempt_no, pre_baseline, started_at,"
                    + " ended_at, step_deadline, outcome, observed_hash, error_class, runtime_ref";

    private static final String READ_COLUMNS =
            "attempt_id, intent_id, step, attempt_no, pre_baseline, started_at, ended_at,"
                    + " step_deadline, outcome, observed_hash, error_class, runtime_ref";

    private final NamedParameterJdbcTemplate jdbc;

    public AttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Journals a try that is ABOUT to happen.
     *
     * @throws IllegalArgumentException if the row already carries an outcome or an end time — the
     *                                  ordering rule is the point of this method
     */
    public void open(TenantId t, ApplyId id, AttemptRow row) {
        if (row.outcome() != null || row.endedAt() != null) {
            throw new IllegalArgumentException(
                    "write-before-act (§2.2 rule 0): an attempt is opened without an outcome; "
                            + "close() records what happened");
        }
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_attempt (" + COLUMNS + ")"
                        + " VALUES (:attempt, :t, :a, :intent, :step, :no,"
                        + " CAST(:baseline AS jsonb), :started, :ended, :deadline, :outcome,"
                        + " :hash, :err, CAST(:runtimeRef AS jsonb))",
                new MapSqlParameterSource()
                        .addValue("attempt", row.attemptId())
                        .addValue("t", t.value())
                        .addValue("a", id.value())
                        .addValue("intent", row.intentId())
                        .addValue("step", row.step().name())
                        .addValue("no", row.attemptNo())
                        .addValue("baseline", Sql.redacted(Evidence.write(row.preBaseline())))
                        .addValue("started", Sql.at(row.startedAt()))
                        .addValue("ended", Sql.at(row.endedAt()))
                        .addValue("deadline", Sql.at(row.stepDeadline()))
                        .addValue("outcome", row.outcome() == null ? null : row.outcome().name())
                        .addValue("hash", row.observedHash())
                        .addValue("err", row.errorClass() == null ? null : row.errorClass().token())
                        .addValue("runtimeRef", Sql.redacted(Evidence.write(row.runtimeRef())))));
    }

    /**
     * Records what the try did.
     *
     * @param errorClass a CLASSIFIED token, never raw text — the type refuses one ({@code I6})
     * @param runtimeRef adapter-scoped identifiers, serialized here (design §2.3)
     */
    public int close(TenantId t, UUID attemptId, AttemptOutcome o, String observedHash,
            ErrorClass errorClass, RuntimeRef runtimeRef, Instant endedAt) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_attempt SET outcome = :outcome, observed_hash = :hash,"
                        + " error_class = :err, runtime_ref = CAST(:runtimeRef AS jsonb),"
                        + " ended_at = :ended"
                        + " WHERE tenant_id = :t AND attempt_id = :attempt",
                new MapSqlParameterSource()
                        .addValue("t", t.value()).addValue("attempt", attemptId)
                        .addValue("outcome", o.name()).addValue("hash", observedHash)
                        .addValue("err", errorClass == null ? null : errorClass.token())
                        .addValue("runtimeRef", Sql.redacted(Evidence.write(runtimeRef)))
                        .addValue("ended", Sql.at(endedAt))));
    }

    /** The newest try of one intent — the resume read's "latest attempt per intent". */
    public Optional<AttemptRow> latest(TenantId t, UUID intentId) {
        return jdbc.query("SELECT " + READ_COLUMNS + " FROM sm_data.apply_attempt"
                        + " WHERE tenant_id = :t AND intent_id = :i"
                        + " ORDER BY attempt_no DESC LIMIT 1",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("i", intentId),
                MAPPER).stream().findFirst();
    }

    public int count(TenantId t, UUID intentId) {
        Integer n = jdbc.queryForObject("SELECT count(*)::int FROM sm_data.apply_attempt"
                        + " WHERE tenant_id = :t AND intent_id = :i",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("i", intentId),
                Integer.class);
        return n == null ? 0 : n;
    }

    /** The DELETE half of §3.3's compaction; {@code foldAttemptSummary} is the other half. */
    public int deleteForRequest(TenantId t, ApplyId id) {
        return Sql.write(() -> jdbc.update(
                "DELETE FROM sm_data.apply_attempt WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value())));
    }

    private static ErrorClass errorClass(String token) {
        return token == null ? null : new ErrorClass(token);
    }

    private static final RowMapper<AttemptRow> MAPPER = (rs, n) -> {
        String outcome = rs.getString("outcome");
        return new AttemptRow(
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("intent_id", UUID.class),
                Step.valueOf(rs.getString("step")),
                rs.getShort("attempt_no"),
                Evidence.readPreBaseline(rs.getString("pre_baseline")),
                Sql.instant(rs, "started_at"),
                Sql.instant(rs, "ended_at"),
                Sql.instant(rs, "step_deadline"),
                outcome == null ? null : AttemptOutcome.valueOf(outcome),
                rs.getString("observed_hash"),
                errorClass(rs.getString("error_class")),
                Evidence.readRuntimeRef(rs.getString("runtime_ref")));
    };
}
```

- [ ] **Step 4: Wire the repositories; extend `Refusals`; correct the `SpringTransactionBoundary` javadoc**

Append to `ApplyPersistenceConfiguration` (after `smDataTransactions`):

```java
    @Bean
    ApplyScopeRepository applyScopeRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new ApplyScopeRepository(smDataJdbc);
    }

    @Bean
    ApplyRequestRepository applyRequestRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new ApplyRequestRepository(smDataJdbc);
    }

    @Bean
    StepIntentRepository stepIntentRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new StepIntentRepository(smDataJdbc);
    }

    @Bean
    AttemptRepository attemptRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new AttemptRepository(smDataJdbc);
    }
```

Add to `Refusals` (after the four-argument `assertRefused`, before `assertRaised`), together with `import static org.junit.jupiter.api.Assertions.assertNull;` in the import block — the guard on the last line is what makes every repository refusal in the suite a regression check for the driver-cause leak Codex's third pass found:

```java
    /**
     * The same assertion for a call that went through a repository, which reshapes class-23 refusals
     * into {@link PgConstraintViolation}.
     *
     * <p>Asserts the reshaping too, not only the underlying error: a repository that let a raw
     * {@code DataAccessException} escape would still satisfy {@link #assertRefused}, and the engine's
     * classify-by-name logic would then never see the name it branches on. And asserts the reshaping is
     * COMPLETE — the driver exception is not carried along as a cause — because every repository
     * refusal in this suite passes through here, which makes this one line the standing regression
     * check for the row-echo leak {@code AttemptIT} measures in full.
     *
     * @return the reshaped exception, so a caller can read {@code table()} / {@code column()}
     */
    static PgConstraintViolation assertViolation(String expectedSqlState,
            String expectedConstraintOrNull, Executable e) {
        PgConstraintViolation v = assertThrows(PgConstraintViolation.class, e,
                "the repository must reshape a class-23 refusal into a NAMED PgConstraintViolation");
        assertEquals(expectedSqlState, v.sqlState(),
                "wrong SQLSTATE — the statement failed, but not for the reason under test. Was: "
                        + v.getMessage());
        if (expectedConstraintOrNull != null) {
            assertEquals(expectedConstraintOrNull, v.constraint(),
                    "wrong constraint name — a refusal that fires for the wrong reason is not a "
                            + "proof. Was: " + v.getMessage());
        }
        assertNull(v.getCause(), "no driver exception may cross the persistence boundary as a cause — "
                + "PostgreSQL's DETAIL echoes the rejected row, and Logback prints cause chains (I6)");
        return v;
    }
```

In `SpringTransactionBoundary`'s class javadoc, replace the sentence beginning *"The exotic inverse — an actual transaction with synchronization disabled (`SYNCHRONIZATION_NEVER`) — degrades to immediate publish, which is exactly today's pre-decorator behaviour: stated, not silent."* with the measured mechanism (plan 003's watch item, discharged by Step 6's IT):

```java
 * so publishing immediately is correct, not a shortcut.
 *
 * <p><b>{@code SYNCHRONIZATION_NEVER}, as measured</b> (not as first guessed — see
 * {@code SpringTransactionBoundaryDataSourceIT}): under that mode a real transaction is opened and
 * committed, yet <i>both</i> conjuncts read false, not merely the synchronization one.
 * {@code AbstractPlatformTransactionManager.prepareSynchronization} is the only place that sets the
 * actual-transaction flag and it is skipped entirely, so the open transaction is invisible to the
 * whole {@code TransactionSynchronizationManager} API. Calling {@link #afterCommit} anyway throws
 * ("Transaction synchronization is not active"); the {@link #inTransaction()} guard is what keeps
 * the decorator off that path. The outcome is still immediate publish — today's pre-decorator
 * behaviour, stated rather than silent — but by a different mechanism than the conjunction's
 * first-conjunct reading suggests.
```

- [ ] **Step 5: The two ArchUnit gates with planted proofs (`arch-tests`)**

Four fixtures (test scope, filtered from the real run by `ExcludeArchFixtures`):

```java
package com.solace.sm.dp.apply.archfixture;

import java.sql.Connection;

/**
 * Planted violation of {@code jdbcIsConfinedToApplyPersistence}: a data-plane class that is NOT in
 * {@code com.solace.sm.dp.apply.persistence..} yet holds a JDBC type. Test scope, arch-tests only,
 * filtered out of the real rule run by {@code PlaneBoundaryTest.ExcludeArchFixtures}.
 */
public final class BadJdbcOutsidePersistenceClass {

    private Connection connection;

    public Connection connection() {
        return connection;
    }
}
```

```java
package com.solace.sm.dp.apply.persistence.archfixture;

import java.sql.Connection;

/**
 * The counterpart fixture: a class INSIDE {@code com.solace.sm.dp.apply.persistence..} holding the
 * same JDBC type. The rule must leave it alone, or it is a rule that rejects everything and proves
 * nothing.
 */
public final class LegalPersistenceJdbcClass {

    private Connection connection;

    public Connection connection() {
        return connection;
    }
}
```

```java
package com.solace.sm.dp.apply.engine.archfixture;

import java.sql.Connection;

/**
 * A planted violation: the apply ENGINE reaching for a raw JDBC {@link Connection}.
 *
 * <p>It exists to answer a question the plan asked rather than assumed — does
 * {@code jdbcIsConfinedToApplyPersistence} already cover {@code ..dp.apply.engine..}, or is a second
 * rule needed? The rule is written as "every package OUTSIDE
 * {@code com.solace.sm.dp.apply.persistence}", so it should; this class is what turns "should" into
 * a failing check. Excluded from the real run by {@code ExcludeArchFixtures}.
 */
public final class BadEngineJdbcClass {

    private Connection connection;

    public Connection connection() {
        return connection;
    }
}
```

```java
package com.solace.sm.dp.apply.model.archfixture;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * A planted violation: the apply MODEL reaching for a Spring type.
 *
 * <p>The model is the one layer that is framework-free by design — it is the vocabulary shared by the
 * engine, the repositories and (later) the API and event mappers, and a Spring or JDBC import in it
 * would make every one of those consumers inherit the dependency. Deliberately a {@code spring-tx}
 * type rather than a JDBC one, so this fixture trips {@code applyModelIsFrameworkFree} and NOT
 * {@code jdbcIsConfinedToApplyPersistence} — two rules proven by two distinct violations, not one
 * violation counted twice.
 */
public final class BadFrameworkModelClass {

    private TransactionTemplate transactions;

    public TransactionTemplate transactions() {
        return transactions;
    }
}
```

In `PlaneBoundaryTest`, after `onlyMeshbridgeTouchesJcsmp`, the two rules:

```java
    // sm_data's access surface is ONE package (eda-walking-skeleton §3.1; I5's "central data-access
    // module"). Shaped like onlyMeshbridgeTouchesJcsmp and for the same reason: the seam is only a
    // seam while exactly one package can reach past it.
    //
    // Six package sets, each closing a different way around: java.sql/javax.sql (a raw Connection or
    // DataSource handed to a service), org.springframework.jdbc (a second JdbcTemplate built
    // anywhere), org.postgresql (vendor types leaking into domain code — PSQLException handling is
    // exactly the temptation), com.zaxxer.hikari (a second pool, so a second credential), and
    // org.flywaydb (migrations run from application code rather than the one startup initializer).
    //
    // Measured before it was written: `grep -rE 'java\.sql|javax\.sql|springframework\.jdbc|
    // org\.postgresql|zaxxer|flywaydb' --include=*.java backend` matched nothing outside
    // com.solace.sm.dp.apply.persistence, so this rule is green on arrival and can only ever fail on
    // a NEW dependency. It is not retrofitted around an existing exception.
    @ArchTest
    static final ArchRule jdbcIsConfinedToApplyPersistence =
            noClasses().that().resideOutsideOfPackage("com.solace.sm.dp.apply.persistence..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "java.sql..", "javax.sql..", "org.springframework.jdbc..",
                            "org.postgresql..", "com.zaxxer.hikari..", "org.flywaydb..");

    // The apply MODEL is framework-free (eda-walking-skeleton §3; the plan's wave-B constraint).
    //
    // Narrower than jdbcIsConfinedToApplyPersistence and pointed the other way: that rule keeps JDBC
    // OUT of everything except one package, this one keeps Spring AND JDBC out of one package
    // specifically. The model is the vocabulary the engine, the repositories and — later — the API
    // and the event mappers all share, so a framework import there is inherited by every consumer,
    // including modules that must never see spring-jdbc at all. java.sql/javax.sql are listed as well
    // as Spring because the model's own temptation is `java.sql.Timestamp` on a row record.
    @ArchTest
    static final ArchRule applyModelIsFrameworkFree =
            noClasses().that().resideInAPackage("com.solace.sm.dp.apply.model..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "java.sql..", "javax.sql..");
```

and, after `jcsmpRuleCatchesAPlantedViolation`, the four proofs (the file already imports `assertFalse`, `JavaClasses`, `ClassFileImporter`):

```java
    @Test
    void jdbcConfinementRuleCatchesAPlantedViolationWithoutRejectingTheLegalPackage() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> jdbcIsConfinedToApplyPersistence.check(withPlantedViolations()));
        assertTrue(failure.getMessage()
                        .contains("com.solace.sm.dp.apply.archfixture.BadJdbcOutsidePersistenceClass"),
                "jdbcIsConfinedToApplyPersistence must fail because of the planted fixture, not for "
                        + "some other reason. Was: " + failure.getMessage());

        // Presence first, then absence — otherwise the assertion below passes for free the day the
        // legal fixture stops being imported (the same trap gatewayUsesApisOnly's proof avoids).
        assertTrue(withPlantedViolations().stream().anyMatch(c -> c.getName().equals(
                        "com.solace.sm.dp.apply.persistence.archfixture.LegalPersistenceJdbcClass")),
                "LegalPersistenceJdbcClass must be in the analysed set, or the assertion below "
                        + "proves nothing");
        assertFalse(failure.getMessage().contains("LegalPersistenceJdbcClass"),
                "the persistence package itself must be allowed to hold JDBC types — that is the "
                        + "point of confining them there. Was: " + failure.getMessage());
    }

    /**
     * Answers the question the plan asked rather than assuming it: does the EXISTING confinement rule
     * already cover the new {@code ..apply.engine} package, so that no second rule is needed?
     *
     * <p>It does. The rule is phrased as "everything outside {@code com.solace.sm.dp.apply.persistence}",
     * and a planted {@code java.sql.Connection} in {@code ..dp.apply.engine.archfixture} makes it fire
     * by name. Recorded as a test rather than as a comment, because "the rule would cover it" is the
     * kind of claim that is true right up until someone rewrites the package predicate.
     */
    @Test
    void theExistingJdbcConfinementRuleAlreadyCoversTheApplyEnginePackage() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> jdbcIsConfinedToApplyPersistence.check(withPlantedViolations()));
        assertTrue(failure.getMessage()
                        .contains("com.solace.sm.dp.apply.engine.archfixture.BadEngineJdbcClass"),
                "the engine fixture must trip the EXISTING rule — if it does not, ..apply.engine "
                        + "needs a rule of its own. Was: " + failure.getMessage());

        // …and the same rule must NOT be what catches the model fixture, which holds no JDBC type:
        // that one belongs to applyModelIsFrameworkFree, below.
        assertFalse(failure.getMessage().contains("BadFrameworkModelClass"),
                "the model fixture holds a spring-tx type, not a JDBC one — if this rule catches it "
                        + "too, the two proofs are not independent. Was: " + failure.getMessage());
    }

    @Test
    void modelFrameworkRuleCatchesAPlantedViolationWithoutRejectingTheRealModel() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> applyModelIsFrameworkFree.check(withPlantedViolations()));
        assertTrue(failure.getMessage()
                        .contains("com.solace.sm.dp.apply.model.archfixture.BadFrameworkModelClass"),
                "applyModelIsFrameworkFree must fail because of the planted fixture, not for some "
                        + "other reason. Was: " + failure.getMessage());

        // Presence first, then absence — the same trap the gateway and JDBC proofs avoid.
        assertTrue(withPlantedViolations().stream().anyMatch(
                        c -> c.getName().equals("com.solace.sm.dp.apply.model.ApplyCommand")),
                "the real model must be in the analysed set, or the assertion below proves nothing");
        assertFalse(failure.getMessage().contains("com.solace.sm.dp.apply.model.ApplyCommand"),
                "the production model must be clean — a rule that also rejects the code it governs "
                        + "is a broken rule, not a strict one. Was: " + failure.getMessage());
    }

    @Test
    void archTestsActuallySeesTheNewPersistencePackageOfDpCore() {
        // The rule above is only meaningful if arch-tests imports dp-core's production classes. It
        // does (dp-core is a test-scope dependency), but the new package arrived after the module
        // list was written, so assert it rather than assume it.
        JavaClasses production = new ClassFileImporter()
                .withImportOption(new ExcludeArchFixtures()).importPackages("com.solace.sm");
        assertTrue(production.stream().anyMatch(c -> c.getName().equals(
                        "com.solace.sm.dp.apply.persistence.SmDataMigrator")),
                "dp-core's apply-persistence package must be on the arch-tests classpath");
    }
```

Before writing the rules, re-run the pre-scan and confirm it still matches nothing outside the persistence package: `grep -rlE 'java\.sql|javax\.sql|springframework\.jdbc|org\.postgresql|zaxxer|flywaydb' --include="*.java" backend | grep -v '/dp/apply/persistence/'` → expected: no output (test fixtures under `archfixture` are the only exception and they are filtered). If anything else appears, stop and report — the rule is not to be retrofitted around an exception.

- [ ] **Step 6: Plan 003's watch item — `SpringTransactionBoundaryDataSourceIT`**

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 003's standing watch item, discharged: {@link SpringTransactionBoundary} under a REAL
 * {@link DataSourceTransactionManager} over a real PostgreSQL connection, rather than the
 * datastore-free {@code AbstractPlatformTransactionManager} its unit test uses.
 *
 * <p>Why it was left open: the unit test's recording manager runs the genuine synchronization
 * machinery but commits nothing, so it can prove the ORDER of the callback and not that the
 * callback fires after a durable commit. This class writes a row, and the after-commit action reads
 * whether that row is visible to a different connection.
 *
 * <p>It also settles what {@code SYNCHRONIZATION_NEVER} does — a case the class javadoc describes
 * and no test had ever executed.
 */
@Testcontainers(disabledWithoutDocker = true)
class SpringTransactionBoundaryDataSourceIT {

    private static NamedParameterJdbcTemplate runtime;
    private static DataSourceTransactionManager ptm;
    private static final SpringTransactionBoundary BOUNDARY = new SpringTransactionBoundary();

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        ptm = new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource());
    }

    @Test
    void insideARealDatabaseTransactionTheActionRunsOnlyAfterTheRowIsCommittedAndVisible() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        List<Boolean> rowVisibleWhenActionRan = new ArrayList<>();
        List<Boolean> ranBeforeTheTemplateReturned = new ArrayList<>();

        new TransactionTemplate(ptm).executeWithoutResult(status -> {
            assertTrue(BOUNDARY.inTransaction(),
                    "a DataSourceTransactionManager transaction must satisfy BOTH conjuncts");
            ApplyRows.insertScope(runtime, tenant, instance);
            assertFalse(scopeExistsOnAnotherConnection(tenant, instance),
                    "precondition: the insert must be INSIDE the transaction. A JdbcTemplate over a "
                            + "different DataSource instance than the transaction manager takes its "
                            + "own auto-commit connection and this whole test proves nothing "
                            + "(measured — see SmDataPostgres' caching note)");
            BOUNDARY.afterCommit(() -> {
                // A SEPARATE connection out of the pool — so visibility here means committed, not
                // merely written on this session.
                rowVisibleWhenActionRan.add(scopeExistsOnAnotherConnection(tenant, instance));
                ranBeforeTheTemplateReturned.add(Boolean.TRUE);
            });
            assertTrue(rowVisibleWhenActionRan.isEmpty(),
                    "nothing may run while the transaction is still open");
        });

        assertEquals(List.of(Boolean.TRUE), rowVisibleWhenActionRan,
                "the after-commit action must observe a COMMITTED row — that ordering is BE-D1 §3, "
                        + "and only a real transaction manager can prove it");
        assertEquals(1, ranBeforeTheTemplateReturned.size());
    }

    @Test
    void aRolledBackDatabaseTransactionRunsNoActionAndLeavesNoRow() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        List<String> ran = new ArrayList<>();

        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(ptm).executeWithoutResult(status -> {
                    ApplyRows.insertScope(runtime, tenant, instance);
                    BOUNDARY.afterCommit(() -> ran.add("published"));
                    throw new IllegalStateException("business write failed");
                }));

        assertTrue(ran.isEmpty(), "a rolled-back write happened to nobody — no notice may go out");
        assertFalse(scopeExistsOnAnotherConnection(tenant, instance),
                "…and the row really did roll back, so the test is about a real rollback");
    }

    @Test
    void withPropagationNotSupportedThereIsNoTransactionToDeferAgainst() {
        TransactionTemplate template = new TransactionTemplate(ptm);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        template.executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive(),
                    "PROPAGATION_NOT_SUPPORTED still opens a synchronized scope…");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "…with no actual transaction in it");
            assertFalse(BOUNDARY.inTransaction(),
                    "publishing immediately is CORRECT here: there is no commit to order against. "
                            + "This is the case the second conjunct exists for");
        });
    }

    /**
     * {@code SYNCHRONIZATION_NEVER}, measured — and the measurement corrects the class javadoc.
     *
     * <p>{@code SpringTransactionBoundary} described this as "an actual transaction with
     * synchronization disabled", implying {@code isActualTransactionActive()} is true and the
     * synchronization conjunct is what degrades the boundary to immediate publish. It is not:
     * {@code AbstractPlatformTransactionManager.prepareSynchronization} is the ONLY place that sets
     * the actual-transaction flag, and it is skipped entirely when synchronization is never — so
     * BOTH conjuncts read false and the transaction, though genuinely open, is invisible to the
     * whole {@code TransactionSynchronizationManager} API.
     *
     * <p>The outcome the javadoc predicted (immediate publish, today's pre-decorator behaviour) is
     * right. Its stated mechanism was not.
     */
    @Test
    void withSynchronizationNeverTheOpenTransactionIsInvisibleToBothConjunctsNotJustOne() {
        DataSourceTransactionManager never =
                new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource());
        never.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();

        new TransactionTemplate(never).executeWithoutResult(status -> {
            // The transaction is REAL: this insert participates in it and is committed at the end.
            ApplyRows.insertScope(runtime, tenant, instance);

            assertFalse(TransactionSynchronizationManager.isSynchronizationActive(),
                    "synchronization is off — that much the mode's name promises");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "and the actual-transaction flag is ALSO false, because prepareSynchronization "
                            + "sets it and prepareSynchronization does not run in this mode");
            assertFalse(BOUNDARY.inTransaction(),
                    "so the boundary degrades to immediate publish, as the javadoc predicts — but "
                            + "on both conjuncts, not on the synchronization one alone");

            assertThrows(IllegalStateException.class, () -> BOUNDARY.afterCommit(() -> { }),
                    "registering anyway is refused: 'Transaction synchronization is not active'. "
                            + "The inTransaction() guard is what keeps AfterCommitEventPublisher off "
                            + "this path");
        });

        assertTrue(scopeExistsOnAnotherConnection(tenant, instance),
                "the transaction really did commit — this mode hides synchronization, not the write");
    }

    private static boolean scopeExistsOnAnotherConnection(UUID tenant, UUID instance) {
        return Boolean.TRUE.equals(SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i)",
                ApplyRows.scope(tenant, instance), Boolean.class));
    }
}
```

- [ ] **Step 7: Verify**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`
Expected: BUILD SUCCESS. Surefire: mesh-events 127 (unchanged); dp-core **108** (72 + `Uuid7Test` 5 + `ApplyCommandTest` 12 + `EvidenceTypesTest` 10 + `RequestStateTest` 3 + `EvidenceJsonTest` 6); arch-tests **25** (19 + 2 rules + 4 proofs — surefire counts each `@ArchTest` rule as a test alongside each `@Test`; confirm 25 `<testcase>` elements in `backend/arch-tests/target/surefire-reports/TEST-com.solace.sm.arch.PlaneBoundaryTest.xml`). Failsafe: `Tests run: 33, Skipped: 2` — the four new `SpringTransactionBoundaryDataSourceIT` tests beside Task 2's 27. Measured facts to reproduce: in `withSynchronizationNever…`, both `isSynchronizationActive()` and `isActualTransactionActive()` are false, `afterCommit` throws `IllegalStateException`, and the row still commits; in the first test the row is invisible on a second connection inside the transaction and visible inside the after-commit callback.

**Prove it fires:** the two ArchUnit proofs *are* the planted-violation runs (`assertThrows` on the rule against the fixture set). For the boundary IT, the vacuity guard is built in: the `assertFalse(scopeExistsOnAnotherConnection(…))` precondition inside the first test fails if the template and the transaction manager ever stop sharing one `DataSource` instance (measured when the harness handed out fresh instances).

- [ ] **Step 8: Commit**

```bash
git add backend/dp-core/src/main/java/com/solace/sm/dp/apply backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/SpringTransactionBoundary.java backend/dp-core/src/test/java/com/solace/sm/dp/apply backend/arch-tests/src/test/java
git commit -m "Add the framework-free apply model, the four sm_data repositories and their ArchUnit gates (Task 3)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

**Risks:** Jackson reaches `dp-core`'s compile classpath **transitively** through `mesh-events` (`jackson-databind` 2.21.4 measured via `dependency:tree`) — `Evidence` compiles against it without a POM entry; if `mesh-events` ever drops the dependency, add `com.fasterxml.jackson.core:jackson-databind` (BOM-managed, no version) to `dp-core` explicitly. `org.postgresql` at compile scope is deliberate (the classifier needs `PSQLException.getServerErrorMessage()`); the ArchUnit rule is what keeps it from spreading. `RequestState.blocksScope()`/`isTerminal()` and the `BLOCKING_STATES` literal in `ApplyRequestRepository` mirror the fence predicate in three places — Task 6's `SchemaConstraintsIT` and Task 4's fence tests are what keep them agreeing.

---
### Task 4: The serialized admission transaction and the outcome walks (`dp-core`)

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/{EngineTransactions,AdmissionOutcome,RefusalReason,AdmissionService,OutcomeService,ApplyEngineConfiguration}.java`
- Create (test helper): `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ApplyEngineHarness.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/{AdmissionFencesIT,AdmissionSerializationIT,MonotonicityIT,OutcomeTransitionsIT}.java`

**Interfaces:**
- Consumes: Task 3's model and repositories; `Constraints.{APPLY_INFLIGHT_FENCE, STOP_INFLIGHT_FENCE, STAGED_FENCE}`; `PgConstraintViolation.namesAnyOf`; `Refusals.{assertViolation, assertRaised}`; `SmDataPostgres`.
- Produces: `EngineTransactions.requiresNew(TransactionTemplate) → TransactionTemplate` (package-private; every engine service derives its own `PROPAGATION_REQUIRES_NEW` template from the one it is given); `AdmissionService.admit(ApplyCommand) → AdmissionOutcome` (`Admitted(ApplyId)` | `Refused(RefusalReason, String detail)`); `RefusalReason.{SCOPE_BUSY, STOP_IN_FLIGHT, GENERATION_NOT_ABOVE_WATERMARK, FENCE_RACE_LOST}`; `OutcomeService.{start(TenantId, ApplyId), land(TenantId, ApplyId, RequestState), resolveIndeterminate(TenantId, ApplyId, Resolution)}` — every one takes the scope row lock first, and `land` refuses `INDETERMINATE_RESOLVED` and any terminal `!validFor(operation)` in Java before SQL; `ApplyEngineConfiguration` with beans `smDataClock`, `admissionService`, `outcomeService` (Task 5 appends `stepIntentService`, `attemptPolicy`, `attemptService`); `ApplyEngineHarness.{jdbc(), transactions(), scopes(), requests(), intents(), attempts(), admission(), outcomes(), scope(tenant, instance, placement), apply(scope, generation), restart(scope), stop(scope), tenant(uuid), requestedRow(scope, op, generation, strategy), requestedApply(scope, generation)}` (Task 5 appends `stepIntents()` and `attemptsService(int)`).

**Preconditions:** Task 3 committed and green.

**The algorithm this task implements is design §3.3's, in this order, inside ONE `TransactionTemplate.execute` that is the service's OWN transaction** (`PROPAGATION_REQUIRES_NEW` via `EngineTransactions` — measured: under `REQUIRED`, a caller's enclosing transaction can roll an admission back after `admit` returned `Admitted`, and a lost race's `setRollbackOnly` becomes the caller's `UnexpectedRollbackException`; isolation is PostgreSQL's default — measured `read committed`, and `AdmissionFencesIT` pins it because §2.2 rule 2's reasoning depends on it):

1. `ensureScopeRow` (`INSERT … ON CONFLICT DO NOTHING` on the single-arbiter PK) then `lockScope` (`SELECT … FOR UPDATE`) — every later check runs under the held row; a concurrent admission for the same instance blocks **here** — on `lockScope`'s `FOR UPDATE` when the scope row already exists (the steady state, and what `AdmissionSerializationIT` asserts by statement text), or on the upsert's uncommitted index entry when the holder is the transaction creating the row (measured both ways).
2. `operation == STOP` → only `stopInFlight(exact scope)` → `Refused(STOP_IN_FLIGHT)`. **Stop is exempt from the blocking check** (§3.3, architecture §16.1).
3. Otherwise → any `blockingRequests(tenant, instance)` whose `ScopeKey.overlaps(scope)` → `Refused(SCOPE_BUSY, "<op> <id> is <state> on placement <p>")`.
4. `operation == APPLY` → fence = instance-wide ? `max(instanceWatermark, maxPlacementWatermark)` : `max(instanceWatermark, placementWatermark(p))`; `generation <= fence` → `Refused(GENERATION_NOT_ABOVE_WATERMARK)`.
5. `operation == APPLY` → `stagedRequest(exact scope)` present → `transition(STAGED → SUPERSEDED, terminalAt = now)` **before** the insert (obligation (p); `staged_fence` makes forgetting this a 23505). **Exact-scope only** — cross-scope supersession is design §8 row 14, undecided; `MonotonicityIT.aPlacementWatermarkOfTwelveFencesAnInstanceWideEleven` pins the v1 behaviour (a placement `STAGED` row survives an admitted instance-wide apply).
**Landing (`OutcomeService`) runs under the same lock, by design:** `start`/`land`/`resolveIndeterminate` call `lockScope` immediately after reading the request and before any write, so admission and landing serialize on the `apply_scope` row rather than by accident — measured (Codex finding 3): the placement-watermark upsert's `DO UPDATE` branch takes **no** lock on the scope row (only its INSERT branch does, through the FK's `KEY SHARE`), so before this fix a placement's *second* landing did not serialize against admission at all; correctness survived only on the `blocksScope()` backstop. `land` also mirrors `state_fits_operation` in Java (`RequestState.validFor`) and refuses `INDETERMINATE_RESOLVED` (only `resolveIndeterminate` writes the evidence) — the CHECK stays the last line.

6. `insert(REQUESTED row)`; a `PgConstraintViolation` naming one of the three fences → `status.setRollbackOnly()` and `Refused(FENCE_RACE_LOST, constraint)`. **Measured: unreachable through this service, and therefore UNTESTED** — under the row lock the loser sees the winner's committed row and refuses `SCOPE_BUSY` (the braces) before the belt can fire. The branch stays as a defensive belt for a writer that bypasses admission (the fences themselves are proven by `AdmissionFencesIT`'s raw inserts); no test asserts `FENCE_RACE_LOST` through `admit`, and none should be written to — the coverage matrix says so.

- [ ] **Step 1: Write the failing test — `ApplyEngineHarness` and `AdmissionFencesIT`**

`ApplyEngineHarness.java` (the production object graph, assembled by hand over the harness's ONE cached runtime `DataSource` — see `SmDataPostgres`' caching note):

```java
package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The apply engine wired over the shared {@code sm_dp_runtime} data source — the object graph
 * {@code ApplyPersistenceConfiguration} + {@code ApplyEngineConfiguration} build in production,
 * assembled by hand so the engine ITs assert on behaviour rather than on Spring.
 *
 * <p><b>One transaction manager over the CACHED data source instance.</b> Task 1 measured why: Spring
 * keys the transactional connection by the {@code DataSource} INSTANCE, so a template over one
 * instance and a manager over another silently do not share a transaction, and every rollback test
 * passes for the wrong reason. {@link SmDataPostgres#runtimeDataSource()} caches, and this class
 * builds exactly one manager over it.
 *
 * <p>{@code ApplyPersistenceConfigurationIT} (Task 5) covers the Spring-wired path separately, so
 * nothing here is the only proof of anything.
 */
final class ApplyEngineHarness {

    private static NamedParameterJdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ApplyScopeRepository scopes;
    private static ApplyRequestRepository requests;
    private static StepIntentRepository intents;
    private static AttemptRepository attempts;

    private ApplyEngineHarness() {
    }

    static synchronized NamedParameterJdbcTemplate jdbc() {
        if (jdbc == null) {
            SmDataPostgres.bootstrap();
            jdbc = new NamedParameterJdbcTemplate(SmDataPostgres.runtimeDataSource());
        }
        return jdbc;
    }

    static synchronized TransactionTemplate transactions() {
        if (transactions == null) {
            jdbc();
            transactions = new TransactionTemplate(
                    new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
        }
        return transactions;
    }

    static synchronized ApplyScopeRepository scopes() {
        if (scopes == null) {
            scopes = new ApplyScopeRepository(jdbc());
        }
        return scopes;
    }

    static synchronized ApplyRequestRepository requests() {
        if (requests == null) {
            requests = new ApplyRequestRepository(jdbc());
        }
        return requests;
    }

    static synchronized StepIntentRepository intents() {
        if (intents == null) {
            intents = new StepIntentRepository(jdbc());
        }
        return intents;
    }

    static synchronized AttemptRepository attempts() {
        if (attempts == null) {
            attempts = new AttemptRepository(jdbc());
        }
        return attempts;
    }

    /** A fresh service per test class: {@code AdmissionService} holds no state, but nor does it cost. */
    static AdmissionService admission() {
        return new AdmissionService(transactions(), scopes(), requests(), Clock.systemUTC());
    }

    static OutcomeService outcomes() {
        return new OutcomeService(transactions(), scopes(), requests(), Clock.systemUTC());
    }

    // -------------------------------------------------------------------------------------------
    // Command builders. Named for what they mean rather than which arguments they fill in.
    // -------------------------------------------------------------------------------------------

    static ScopeKey scope(UUID tenant, UUID instance, PlacementId placement) {
        return new ScopeKey(new TenantId(tenant), new InstanceId(instance), placement);
    }

    static ApplyCommand apply(ScopeKey scope, long generation) {
        return new ApplyCommand(scope, Operation.APPLY, generation, Strategy.MANUAL, null,
                "operator-1");
    }

    static ApplyCommand restart(ScopeKey scope) {
        return new ApplyCommand(scope, Operation.RESTART, null, Strategy.ROLLING_HEALTH_GATED, null,
                "operator-1");
    }

    static ApplyCommand stop(ScopeKey scope) {
        return new ApplyCommand(scope, Operation.STOP, null, null, null, "operator-1");
    }

    static TenantId tenant(UUID id) {
        return new TenantId(id);
    }

    /**
     * A minimal legal {@code REQUESTED} row for the BELT tests — the ones that go around
     * {@code AdmissionService} to prove the fences hold on their own.
     */
    static ApplyRequestRow requestedRow(ScopeKey scope, Operation op, Long generation,
            Strategy strategy) {
        return new ApplyRequestRow(ApplyId.next(), scope, op, generation, strategy, null,
                "operator-1", Instant.now(), RequestState.REQUESTED, null);
    }

    /** The instance-wide apply the belt tests use most. */
    static ApplyRequestRow requestedApply(ScopeKey scope, long generation) {
        return requestedRow(scope, Operation.APPLY, generation, Strategy.MANUAL);
    }
}
```

`AdmissionFencesIT.java` — obligations (a), (b), (c), (e) and (p), each at both layers:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.RefusalReason;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations (a), (b), (c) and (e) — at BOTH layers, because design §3.3 says neither alone is
 * sufficient: "the index is the belt for identical scopes; the serialized admission check is the
 * braces for overlapping ones".
 *
 * <p>So every fact here is proven twice where both layers can express it: once through
 * {@link AdmissionService}, which returns a {@link AdmissionOutcome.Refused} an operator can read,
 * and once by going AROUND the service with a raw repository insert, which must be refused by the
 * named partial unique index. A test suite that only exercised the service could not tell a working
 * fence from a missing one.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdmissionFencesIT {

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static ApplyRequestRepository requests;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        requests = ApplyEngineHarness.requests();
    }

    // -------------------------------------------------------------------------------------------
    // The measurement the whole engine's concurrency story rests on.
    // -------------------------------------------------------------------------------------------

    @Test
    void theTransactionTemplateRunsAtPostgresqlsDefaultIsolationWhichIsReadCommitted() {
        String isolation = ApplyEngineHarness.transactions().execute(status ->
                ApplyEngineHarness.jdbc().queryForObject(
                        "SELECT current_setting('transaction_isolation')",
                        new MapSqlParameterSource(), String.class));
        assertEquals("read committed", isolation,
                "design §2.2 rule 2 is a statement about READ COMMITTED specifically — the arbiter "
                        + "retry exists because a concurrent insert becomes visible to a NEW "
                        + "transaction. If the default ever moved, that reasoning would need redoing");
    }

    // -------------------------------------------------------------------------------------------
    // (a) + (b): the stop exemption, and what it is an exemption FROM.
    // -------------------------------------------------------------------------------------------

    @Test
    void aStopIsAdmittedWhileAnApplyOnTheSameScopeIsRunningWhileASecondApplyIsRefused() {
        Scope s = new Scope();
        ApplyId running = admitted(admission.admit(ApplyEngineHarness.apply(s.wide, 10)));
        outcomes.start(s.tenant, running);

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.wide)),
                "(a) the operator's emergency lever is never fenced by an apply — design §3.3");
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.apply(s.wide, 11)));
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.restart(s.wide)));
    }

    @Test
    void aStopIsAdmittedWhileAnApplyIsIndeterminateWhileASecondApplyIsRefused() {
        Scope s = new Scope();
        ApplyId stuck = admitted(admission.admit(ApplyEngineHarness.apply(s.wide, 10)));
        outcomes.start(s.tenant, stuck);
        outcomes.land(s.tenant, stuck, RequestState.APPLY_INDETERMINATE);

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.wide)));
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.apply(s.wide, 11)),
                "(b) architecture.md §16.1: an unresolved APPLY_INDETERMINATE blocks further applies");
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.restart(s.wide)));
    }

    // -------------------------------------------------------------------------------------------
    // (c): the stop family's own, narrower rule.
    // -------------------------------------------------------------------------------------------

    @Test
    void aSecondInFlightStopOnTheSameScopeIsRefused() {
        Scope s = new Scope();
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.wide)));
        assertRefused(RefusalReason.STOP_IN_FLIGHT, admission.admit(ApplyEngineHarness.stop(s.wide)));
    }

    @Test
    void aStopOnADifferentPlacementIsAdmittedBesideAnInFlightStopThePositiveControl() {
        Scope s = new Scope();
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.placement(s.p1))));
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.placement(s.p2))),
                "stop_inflight_fence is per SCOPE, and two placements are two scopes");
    }

    // -------------------------------------------------------------------------------------------
    // The cross-scope rule the indexes cannot see — design §3.3's "braces".
    // -------------------------------------------------------------------------------------------

    @Test
    void aPlacementScopedApplyIsRefusedWhileAnInstanceWideOneIsRunning() {
        Scope s = new Scope();
        ApplyId wide = admitted(admission.admit(ApplyEngineHarness.apply(s.wide, 10)));
        outcomes.start(s.tenant, wide);

        assertRefused(RefusalReason.SCOPE_BUSY,
                admission.admit(ApplyEngineHarness.apply(s.placement(s.p1), 11)),
                "no per-placement admission while an instance-wide request is in flight");
    }

    @Test
    void anInstanceWideApplyIsRefusedWhileAPlacementScopedOneIsRunning() {
        Scope s = new Scope();
        ApplyId narrow = admitted(admission.admit(ApplyEngineHarness.apply(s.placement(s.p1), 10)));
        outcomes.start(s.tenant, narrow);

        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.apply(s.wide, 11)),
                "…and vice versa: the sentinel overlaps every placement");
    }

    @Test
    void aSecondPlacementIsAdmittedBesideAFirstThePositiveControlForTheOverlapRule() {
        Scope s = new Scope();
        ApplyId first = admitted(admission.admit(ApplyEngineHarness.apply(s.placement(s.p1), 10)));
        outcomes.start(s.tenant, first);

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(s.placement(s.p2), 10)),
                "two disjoint placements do NOT overlap — an admission check that refused here "
                        + "would be serializing the whole instance, which is not what §3.3 says");
    }

    @Test
    void aPlacementScopedStopIsAdmittedWhileAnInstanceWideApplyIsRunningOrIndeterminate() {
        Scope running = new Scope();
        ApplyId wide = admitted(admission.admit(ApplyEngineHarness.apply(running.wide, 10)));
        running.startOf(wide);
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(running.placement(running.p1))),
                "the CROSS-SCOPE half of the stop exemption, which obligation (a) names explicitly");

        Scope stuck = new Scope();
        ApplyId indeterminate = admitted(admission.admit(ApplyEngineHarness.apply(stuck.wide, 10)));
        outcomes.start(stuck.tenant, indeterminate);
        outcomes.land(stuck.tenant, indeterminate, RequestState.APPLY_INDETERMINATE);
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(stuck.placement(stuck.p1))));
    }

    // -------------------------------------------------------------------------------------------
    // (e): tenancy. A fence that let tenant A block tenant B would be an I5 defect in the schema.
    // -------------------------------------------------------------------------------------------

    @Test
    void theSameInstanceAndPlacementUnderADifferentTenantIsAdmittedWhileTheSameTenantIsRefused() {
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        UUID tenantA = SmDataPostgres.freshTenant();
        UUID tenantB = SmDataPostgres.freshTenant();
        ScopeKey a = ApplyEngineHarness.scope(tenantA, instance, new PlacementId(placement));
        ScopeKey b = ApplyEngineHarness.scope(tenantB, instance, new PlacementId(placement));

        ApplyId first = admitted(admission.admit(ApplyEngineHarness.apply(a, 10)));
        outcomes.start(new TenantId(tenantA), first);

        assertInstanceOf(AdmissionOutcome.Admitted.class, admission.admit(ApplyEngineHarness.apply(b, 10)),
                "(e) the identical (instance, placement) under another tenant must not collide");
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.apply(a, 11)),
                "…while the same tenant-and-scope still collides as intended");
    }

    // -------------------------------------------------------------------------------------------
    // The BELT: the same facts, with AdmissionService removed from the picture.
    // -------------------------------------------------------------------------------------------

    @Test
    void aRawSecondRequestedRowOnTheSameScopeIsRefusedByApplyInflightFence() {
        Scope s = new Scope();
        s.ensureScopeRow();
        requests.insert(ApplyEngineHarness.requestedApply(s.wide, 10));

        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, Constraints.APPLY_INFLIGHT_FENCE,
                () -> requests.insert(ApplyEngineHarness.requestedApply(s.wide, 11)));
    }

    @Test
    void aRawSecondStopRowOnTheSameScopeIsRefusedByStopInflightFence() {
        Scope s = new Scope();
        s.ensureScopeRow();
        requests.insert(ApplyEngineHarness.requestedRow(s.wide, Operation.STOP, null, null));

        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, Constraints.STOP_INFLIGHT_FENCE,
                () -> requests.insert(
                        ApplyEngineHarness.requestedRow(s.wide, Operation.STOP, null, null)));
    }

    @Test
    void aRawStopBesideARawApplyOnOneScopeIsAcceptedTheTwoFencesAreSeparate() {
        Scope s = new Scope();
        s.ensureScopeRow();
        requests.insert(ApplyEngineHarness.requestedApply(s.wide, 10));
        requests.insert(ApplyEngineHarness.requestedRow(s.wide, Operation.STOP, null, null));
        assertEquals(2, requests.nonTerminal(s.tenant).size(),
                "the apply fence excludes stops by its own predicate — the constraint and the prose "
                        + "must agree, and this is the row-level proof that they do");
    }

    // -------------------------------------------------------------------------------------------
    // (p): the STAGED fence, and the supersession order it exists to enforce.
    // -------------------------------------------------------------------------------------------

    @Test
    void aRawSecondStagedRowWithoutMarkingTheElderIsRefusedByStagedFence() {
        Scope s = new Scope();
        s.ensureScopeRow();
        ApplyRequestRow elder = ApplyEngineHarness.requestedApply(s.wide, 10);
        requests.insert(elder);
        assertEquals(1, requests.transition(s.tenant, elder.applyId(), RequestState.REQUESTED,
                RequestState.STAGED, Instant.now()));

        ApplyRequestRow younger = ApplyEngineHarness.requestedApply(s.wide, 11);
        requests.insert(younger);
        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, Constraints.STAGED_FENCE,
                () -> requests.transition(s.tenant, younger.applyId(), RequestState.REQUESTED,
                        RequestState.STAGED, Instant.now()));
    }

    @Test
    void admissionMarksTheElderStagedRowSupersededAndRefreshesItsTerminalTimestamp() throws Exception {
        Scope s = new Scope();
        ApplyId elder = admitted(admission.admit(ApplyEngineHarness.apply(s.wide, 10)));
        outcomes.start(s.tenant, elder);
        outcomes.land(s.tenant, elder, RequestState.STAGED);

        Instant stagedAt = requests.find(s.tenant, elder).orElseThrow().terminalAt();
        assertNotNull(stagedAt, "STAGED is terminal for the manual strategy, so it carries a stamp");
        Thread.sleep(5);

        AdmissionOutcome outcome = admission.admit(ApplyEngineHarness.apply(s.wide, 11));
        assertInstanceOf(AdmissionOutcome.Admitted.class, outcome);

        ApplyRequestRow marked = requests.find(s.tenant, elder).orElseThrow();
        assertEquals(RequestState.SUPERSEDED, marked.state());
        assertTrue(marked.terminalAt().isAfter(stagedAt),
                "§3.2: the marking UPDATE refreshes terminal_at to the SUPERSESSION time, so the "
                        + "row's stamp says when it became superseded, not when it staged. Was "
                        + marked.terminalAt() + ", staged at " + stagedAt);

        List<ApplyRequestRow> open = requests.nonTerminal(s.tenant);
        assertEquals(1, open.size(), "only the new request is open");
        assertEquals(RequestState.REQUESTED, open.get(0).state());
    }

    // -------------------------------------------------------------------------------------------

    private static ApplyId admitted(AdmissionOutcome outcome) {
        return assertInstanceOf(AdmissionOutcome.Admitted.class, outcome,
                "expected admission, got " + outcome).applyId();
    }

    private static void assertRefused(RefusalReason expected, AdmissionOutcome outcome) {
        assertRefused(expected, outcome, "refusal reason");
    }

    private static void assertRefused(RefusalReason expected, AdmissionOutcome outcome,
            String because) {
        AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class, outcome,
                "expected a refusal, got " + outcome);
        assertEquals(expected, refused.reason(), because + " — detail was: " + refused.detail());
    }

    /** One fresh tenant, one instance, two placements — the isolation unit for a shared container. */
    private static final class Scope {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final UUID instance = UUID.randomUUID();
        private final PlacementId p1 = new PlacementId(UUID.randomUUID());
        private final PlacementId p2 = new PlacementId(UUID.randomUUID());
        private final ScopeKey wide = ApplyEngineHarness.scope(tenant.value(), instance,
                PlacementId.INSTANCE_WIDE);

        ScopeKey placement(PlacementId p) {
            return ApplyEngineHarness.scope(tenant.value(), instance, p);
        }

        void ensureScopeRow() {
            ApplyEngineHarness.scopes().ensureScopeRow(wide.tenant(), wide.instance());
        }

        void startOf(ApplyId id) {
            ApplyEngineHarness.outcomes().start(tenant, id);
        }
    }
}
```

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test=AdmissionFencesIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
Expected: test-compilation failure — `package com.solace.sm.dp.apply.engine does not exist`. The red.
- [ ] **Step 2: The engine — outcome types, `AdmissionService`, `OutcomeService`, `ApplyEngineConfiguration`**

`EngineTransactions.java` — the one place the engine's transaction posture is stated:

```java
package com.solace.sm.dp.apply.engine;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every engine service runs its unit of work in a transaction OF ITS OWN — never inside a caller's.
 *
 * <p>Measured before it was written (plan 004's adversarial review): with the default
 * {@code PROPAGATION_REQUIRED}, an {@code AttemptService.open()} invoked inside a caller's transaction
 * returned its row and the row was then rolled back with the caller — which silently voids §2.2
 * rule 0 ("an intent with no attempt row provably issued nothing"). Likewise a lost fence race inside
 * {@code admit} marks the transaction rollback-only, which under an enclosing transaction surfaces as
 * an {@code UnexpectedRollbackException} at the OUTER commit instead of as the refusal value.
 *
 * <p>So the services derive a {@code REQUIRES_NEW} template from the one they are given. The
 * property is not "may be composed into a larger transaction"; it is the opposite, and it is stated
 * here once rather than as a precondition in four javadocs.
 */
final class EngineTransactions {

    private EngineTransactions() {
    }

    /**
     * The same transaction manager, {@code PROPAGATION_REQUIRES_NEW}. Only the MANAGER is carried
     * over — an isolation level, timeout or read-only flag set on the supplied template is
     * deliberately not inherited, so the engine's transactions stay uniform whatever a caller does to
     * its own. {@code REQUIRES_NEW} also suspends the caller's transaction and borrows a SECOND pooled
     * connection for the engine's duration: a caller that holds its own transaction across
     * {@code open}/{@code close}/{@code land} costs two connections per in-flight step, which is the
     * pool-sizing input for whichever plan sets the apply concurrency.
     */
    static TransactionTemplate requiresNew(TransactionTemplate template) {
        TransactionTemplate own = new TransactionTemplate(template.getTransactionManager());
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return own;
    }
}
```

`AdmissionOutcome.java` and `RefusalReason.java`:

```java
package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;

/**
 * What §3.3's admission transaction decided. A refusal is a RETURN VALUE, not an exception: every
 * refusal here is an expected, operator-facing answer ("something else is running on this scope"),
 * and modelling expected answers as exceptions is how callers end up catching
 * {@code RuntimeException}.
 */
public sealed interface AdmissionOutcome
        permits AdmissionOutcome.Admitted, AdmissionOutcome.Refused {

    /** The request row is committed in {@code REQUESTED}; the engine may now plan its steps. */
    record Admitted(ApplyId applyId) implements AdmissionOutcome {
    }

    /** Nothing was written (the transaction rolled back if it had to). */
    record Refused(RefusalReason reason, String detail) implements AdmissionOutcome {
    }
}
```

```java
package com.solace.sm.dp.apply.engine;

/**
 * Why admission said no.
 *
 * <p>The first three are the BRACES — the checks §3.3's serialized admission transaction performs
 * while holding the scope row. {@link #FENCE_RACE_LOST} is the BELT: a partial unique index refused
 * the insert anyway. Under the row lock the belt should be unreachable through this service, and
 * keeping the two reasons distinct is what makes that assertable rather than assumed.
 */
public enum RefusalReason {

    /** An apply or restart is in flight (or unresolved) on an overlapping scope — design §3.3. */
    SCOPE_BUSY,
    /** A stop is already in flight on this exact scope. */
    STOP_IN_FLIGHT,
    /** §2.2 rule 1: the generation is at or below the fence for this scope shape. */
    GENERATION_NOT_ABOVE_WATERMARK,
    /** One of the three named fences refused the insert; {@code detail} carries the index name. */
    FENCE_RACE_LOST
}
```

`AdmissionService.java`:

```java
package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.ApplyScopeRepository;
import com.solace.sm.dp.apply.persistence.Constraints;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Design §3.3's admission control, as ONE transaction.
 *
 * <p>The order below is the design's, and every line of it is load-bearing:
 *
 * <ol>
 *   <li><b>{@code ensureScopeRow} then {@code lockScope}</b> — the serialization point. Everything
 *       after this runs with the scope's {@code apply_scope} row held {@code FOR UPDATE}, so a
 *       concurrent admission for the same instance blocks here (measured in
 *       {@code AdmissionSerializationIT}) rather than racing the checks below.
 *   <li><b>The blocking check, cross-scope</b> — {@link ScopeKey#overlaps} expresses what the partial
 *       unique indexes cannot: instance-wide excludes every placement and vice versa. {@code stop} is
 *       EXEMPT, deliberately (§3.3): architecture.md §16.1 blocks further <i>applies</i> behind an
 *       unresolved {@code APPLY_INDETERMINATE}, and fencing the operator's emergency lever would be a
 *       wider reading than the constraint asks for.
 *   <li><b>Monotonicity</b> (§2.2 rule 1) — the fence is the GREATEST of the maintained watermarks
 *       for this scope SHAPE, so an instance-wide {@code APPLIED} at 10 fences a later placement 9
 *       and vice versa.
 *   <li><b>Supersede the elder {@code STAGED} row BEFORE inserting</b> — obligation (p). The
 *       {@code staged_fence} turns forgetting this order into a 23505 instead of silent
 *       accumulation, which is why the order is code and the fence is the check on the code.
 *   <li><b>Insert</b>, and classify any fence 23505 as {@link RefusalReason#FENCE_RACE_LOST}.
 * </ol>
 *
 * <p><b>On the rollback of a lost race:</b> a 23505 aborts the PostgreSQL transaction, so the
 * callback marks the status rollback-only and returns the refusal as a value.
 * {@code TransactionTemplate} then rolls back and returns that value normally — no
 * {@code UnexpectedRollbackException}, because the rollback was locally requested rather than
 * globally imposed. The supersession mark of step 4 rolls back with it, which is correct: this
 * request never existed.
 */
public final class AdmissionService {

    private final TransactionTemplate tx;
    private final ApplyScopeRepository scopes;
    private final ApplyRequestRepository requests;
    private final Clock clock;

    public AdmissionService(TransactionTemplate tx, ApplyScopeRepository scopes,
            ApplyRequestRepository requests, Clock clock) {
        this.tx = EngineTransactions.requiresNew(tx);
        this.scopes = scopes;
        this.requests = requests;
        this.clock = clock;
    }

    public AdmissionOutcome admit(ApplyCommand cmd) {
        ScopeKey scope = cmd.scope();
        TenantId t = scope.tenant();
        InstanceId i = scope.instance();
        PlacementId p = scope.placement();

        return tx.execute(status -> {
            Instant now = clock.instant();
            scopes.ensureScopeRow(t, i);
            long instanceWatermark = scopes.lockScope(t, i);

            if (cmd.operation() == Operation.STOP) {
                if (requests.stopInFlight(t, i, p)) {
                    return refused(RefusalReason.STOP_IN_FLIGHT,
                            "a stop is already in flight on this scope");
                }
            } else {
                Optional<ApplyRequestRow> blocker = requests.blockingRequests(t, i).stream()
                        .filter(r -> r.scope().overlaps(scope))
                        .findFirst();
                if (blocker.isPresent()) {
                    ApplyRequestRow b = blocker.get();
                    return refused(RefusalReason.SCOPE_BUSY,
                            b.operation().sql() + " " + b.applyId().value() + " is " + b.state()
                                    + " on placement " + b.scope().placement().value());
                }
            }

            if (cmd.operation() == Operation.APPLY) {
                long fence = p.isInstanceWide()
                        ? Math.max(instanceWatermark, scopes.maxPlacementWatermark(t, i))
                        : Math.max(instanceWatermark, scopes.placementWatermark(t, i, p));
                if (cmd.generation() <= fence) {
                    return refused(RefusalReason.GENERATION_NOT_ABOVE_WATERMARK,
                            "generation " + cmd.generation() + " is not above the fence " + fence);
                }
                Optional<ApplyId> elder = requests.stagedRequest(t, i, p);
                if (elder.isPresent()) {
                    requests.transition(t, elder.get(), RequestState.STAGED,
                            RequestState.SUPERSEDED, now);
                }
            }

            ApplyId applyId = ApplyId.next();
            try {
                requests.insert(new ApplyRequestRow(applyId, scope, cmd.operation(),
                        cmd.generation(), cmd.strategy(), cmd.outageConfirmedBy(),
                        cmd.requestedBy(), now, RequestState.REQUESTED, null));
            } catch (PgConstraintViolation e) {
                if (e.namesAnyOf(Constraints.APPLY_INFLIGHT_FENCE, Constraints.STOP_INFLIGHT_FENCE,
                        Constraints.STAGED_FENCE)) {
                    status.setRollbackOnly();
                    return refused(RefusalReason.FENCE_RACE_LOST, e.constraint());
                }
                throw e;
            }
            return new AdmissionOutcome.Admitted(applyId);
        });
    }

    private static AdmissionOutcome refused(RefusalReason reason, String detail) {
        return new AdmissionOutcome.Refused(reason, detail);
    }
}
```

`OutcomeService.java`:

```java
package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.ApplyScopeRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * State walks on {@code apply_request}, each with its watermark maintenance in the SAME transaction —
 * design §2.2 rule 1: "the same transaction that lands {@code STAGED}/{@code APPLIED}/resolved-applied
 * updates the corresponding watermark".
 *
 * <p><b>Two enforcers, on purpose.</b> The Java state machine below refuses an illegal walk with an
 * {@link IllegalStateException} and a sentence; the {@code apply_request_terminal_guard} trigger
 * refuses it with P0001. The Java half exists so a bug is diagnosable, the SQL half so a bug is
 * impossible — {@code OutcomeTransitionsIT} proves both fire, because a Java-only guard is one
 * refactor from not existing and a SQL-only guard produces a stack trace nobody can read.
 *
 * <p>The read that classifies the current state is inside the same transaction as the write, so
 * "check then act" is not a race: the row is read and updated under one snapshot, and the UPDATE
 * carries {@code WHERE state = :from} as the compare-and-set anyway.
 *
 * <p><b>Every state-changing operation on a scope takes that scope's {@code apply_scope} row lock,
 * explicitly, first.</b> {@link #start}, {@link #land} and {@link #resolveIndeterminate} all call
 * {@code lockScope} immediately after reading the request and before writing anything, so admission
 * and landing serialize on the same row by design — one mechanism, named at both ends, rather than
 * two paths that happen to collide. The row always exists to be locked: {@code request_has_scope} is a
 * FOREIGN KEY, so a request cannot exist without it.
 *
 * <p>The external review's finding 3 is why the sentence above is not an embellishment. The previous
 * account rested on the watermark raise conflicting with {@code admit}'s {@code SELECT … FOR UPDATE}:
 * an instance-wide raise UPDATEs the scope row, and a placement raise inserts a
 * {@code placement_watermark} row whose FOREIGN KEY takes {@code FOR KEY SHARE} on it. Measured with
 * {@code pgrowlocks}: that holds for the INSERT branch of the upsert only. When the placement already
 * has a watermark row the statement takes the {@code DO UPDATE} branch, no FK check runs against the
 * parent, and NO lock is taken on {@code apply_scope} at all — so the serialization the design claimed
 * held for a placement's FIRST landing and quietly stopped afterwards. The explicit lock closes that;
 * the FK's {@code KEY SHARE} on the insert branch is now incidental and no longer load-bearing.
 *
 * <p>The {@code blocksScope()} backstop stays stated, and stays true: every request whose landing
 * raises a watermark is in a {@code blocksScope()} state until that landing commits, so even an
 * unlocked admission would read the in-flight row and refuse {@code SCOPE_BUSY}. It is the reason a
 * lock-ordering mistake here is a performance question rather than a correctness one. A change to which
 * states {@code land} accepts as SOURCE, or to {@code RequestState.blocksScope()}, must re-check that
 * sentence. (Monitoring note: the wait surfaces as {@code wait_event = 'transactionid'}, not a
 * {@code tuple} lock.)
 */
public final class OutcomeService {

    private final TransactionTemplate tx;
    private final ApplyScopeRepository scopes;
    private final ApplyRequestRepository requests;
    private final Clock clock;

    public OutcomeService(TransactionTemplate tx, ApplyScopeRepository scopes,
            ApplyRequestRepository requests, Clock clock) {
        this.tx = EngineTransactions.requiresNew(tx);
        this.scopes = scopes;
        this.requests = requests;
        this.clock = clock;
    }

    /** {@code REQUESTED → RUNNING}, under the scope lock like every other state change here. */
    public void start(TenantId t, ApplyId id) {
        tx.executeWithoutResult(status -> {
            ApplyRequestRow row = require(t, id);
            lockScope(row);
            if (row.state() != RequestState.REQUESTED) {
                throw new IllegalStateException(
                        "start() walks REQUESTED → RUNNING; " + id.value() + " is " + row.state());
            }
            apply(requests.transition(t, id, RequestState.REQUESTED, RequestState.RUNNING, null),
                    id, RequestState.REQUESTED, RequestState.RUNNING);
        });
    }

    /**
     * Lands a request on a terminal state — or on {@code APPLY_INDETERMINATE}, which is a landing
     * place without being terminal (§3.2: it BLOCKS the scope until an operator resolves it).
     *
     * <p>{@code APPLY_INDETERMINATE} is also a legal SOURCE, and that is design §3.4's agent-replay
     * path: the {@code host-local} flavour resolves deterministically by journal replay and the DP
     * records a DIRECT transition to {@code APPLIED}/{@code FAILED_*}/{@code SUPERSEDED} with all four
     * resolution columns left NULL, because no operator confirmed anything.
     *
     * <p>{@code INDETERMINATE_RESOLVED} is NOT reachable here — {@link #resolveIndeterminate} is the
     * only path to it, because it is the only one that writes the four evidence columns
     * {@code resolution_is_evidence} demands together. And the target must fit the request's operation:
     * {@link RequestState#validFor} mirrors {@code state_fits_operation}, so landing an {@code apply}
     * on {@code STOPPED} is refused in Java with the rule named, before any write, instead of surfacing
     * as a 23514 out of a repository.
     */
    public void land(TenantId t, ApplyId id, RequestState terminal) {
        tx.executeWithoutResult(status -> {
            ApplyRequestRow row = require(t, id);
            lockScope(row);
            RequestState from = row.state();
            if (from.isTerminal()) {
                throw new IllegalStateException("apply_request " + id.value() + " is terminal ("
                        + from + ") and immutable — land() may not walk out of it");
            }
            if (terminal == from || terminal == RequestState.REQUESTED
                    || terminal == RequestState.RUNNING) {
                throw new IllegalStateException(
                        "land() walks to a terminal state or APPLY_INDETERMINATE; " + from + " → "
                                + terminal + " is not a landing");
            }
            if (terminal == RequestState.INDETERMINATE_RESOLVED) {
                throw new IllegalStateException("land() may not reach INDETERMINATE_RESOLVED: only "
                        + "resolveIndeterminate() does, because resolution_is_evidence requires the "
                        + "four resolution columns land() does not write");
            }
            if (!terminal.validFor(row.operation())) {
                throw new IllegalStateException("state_fits_operation: a " + row.operation().sql()
                        + " request may not hold " + terminal + " (apply_request " + id.value()
                        + ") — the CHECK would refuse this with 23514");
            }
            Instant now = clock.instant();
            apply(requests.transition(t, id, from, terminal,
                    terminal.isTerminal() ? now : null), id, from, terminal);

            if (row.operation() == Operation.APPLY
                    && (terminal == RequestState.STAGED || terminal == RequestState.APPLIED)) {
                raiseWatermark(row);
            }
        });
    }

    /**
     * {@code APPLY_INDETERMINATE → INDETERMINATE_RESOLVED} — design §3.4's operator-confirmation
     * flavour, the only path that writes the four evidence columns.
     *
     * <p>{@code outcome = applied} raises the watermark in the same transaction, because §2.2 rule 1
     * names {@code resolved-'applied'} alongside {@code STAGED}/{@code APPLIED} as a generation that
     * counts. {@code not_applied} raises nothing: nothing landed.
     */
    public void resolveIndeterminate(TenantId t, ApplyId id, Resolution r) {
        tx.executeWithoutResult(status -> {
            ApplyRequestRow row = require(t, id);
            lockScope(row);
            if (row.state() != RequestState.APPLY_INDETERMINATE) {
                throw new IllegalStateException("resolveIndeterminate() walks APPLY_INDETERMINATE → "
                        + "INDETERMINATE_RESOLVED; " + id.value() + " is " + row.state());
            }
            apply(requests.resolveIndeterminate(t, id, r, clock.instant()), id,
                    RequestState.APPLY_INDETERMINATE, RequestState.INDETERMINATE_RESOLVED);

            if (row.operation() == Operation.APPLY && r.outcome() == ResolutionOutcome.APPLIED) {
                raiseWatermark(row);
            }
        });
    }

    /**
     * The serialization point, taken explicitly — finding 3.
     *
     * <p>Always the INSTANCE's scope row, whatever the request's placement: that is the row
     * {@code AdmissionService} locks, and two writers serialize only if they lock the same thing.
     */
    private void lockScope(ApplyRequestRow row) {
        scopes.lockScope(row.scope().tenant(), row.scope().instance());
    }

    /** Instance-wide lands on {@code apply_scope}; a placement lands on {@code placement_watermark}. */
    private void raiseWatermark(ApplyRequestRow row) {
        if (row.scope().placement().isInstanceWide()) {
            scopes.raiseInstanceWatermark(row.scope().tenant(), row.scope().instance(),
                    row.generation());
        } else {
            scopes.raisePlacementWatermark(row.scope().tenant(), row.scope().instance(),
                    row.scope().placement(), row.generation());
        }
    }

    private ApplyRequestRow require(TenantId t, ApplyId id) {
        return requests.find(t, id).orElseThrow(() -> new IllegalStateException(
                "no apply_request " + id.value() + " for tenant " + t.value()));
    }

    private static void apply(int updated, ApplyId id, RequestState from, RequestState to) {
        if (updated != 1) {
            throw new IllegalStateException("the " + from + " → " + to + " walk of " + id.value()
                    + " updated " + updated + " rows — another writer moved it first");
        }
    }
}
```

`ApplyEngineConfiguration.java` — a separate configuration in the `engine` package so the dependency between the two packages points one way (Task 5 appends three beans):

```java
package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.ApplyScopeRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The engine services of design §2.2/§3.3 as beans, over the repositories and the transaction
 * template {@code ApplyPersistenceConfiguration} publishes.
 *
 * <p>A separate configuration in the {@code engine} package — not more {@code @Bean} methods on the
 * persistence one — so the dependency between the two packages points one way: {@code engine}
 * consumes {@code persistence}; {@code persistence} knows nothing of the services built on it. A
 * configuration class that instantiated services from inside the persistence package would have made
 * the two packages a cycle, and {@code jdbcIsConfinedToApplyPersistence} would have had nothing to
 * say about it. The future application class {@code @Import}s both, deliberately, exactly as it
 * imports {@code MeshBridgeConfiguration}.
 *
 * <p>This class is the one place in {@code engine} that touches Spring's context API; the services
 * themselves see only {@link TransactionTemplate}.
 */
@Configuration(proxyBeanMethods = false)
public class ApplyEngineConfiguration {

    /**
     * The clock every engine service stamps rows with, as a BEAN rather than {@code Instant.now()}
     * scattered through the services: one seam, so a future test can freeze time in one place, and
     * UTC explicitly because {@code timestamptz} comparisons across a DST boundary are exactly the
     * kind of thing that works for eight months.
     */
    @Bean
    Clock smDataClock() {
        return Clock.systemUTC();
    }

    @Bean
    AdmissionService admissionService(TransactionTemplate smDataTransactions,
            ApplyScopeRepository applyScopeRepository, ApplyRequestRepository applyRequestRepository,
            Clock smDataClock) {
        return new AdmissionService(smDataTransactions, applyScopeRepository,
                applyRequestRepository, smDataClock);
    }

    @Bean
    OutcomeService outcomeService(TransactionTemplate smDataTransactions,
            ApplyScopeRepository applyScopeRepository, ApplyRequestRepository applyRequestRepository,
            Clock smDataClock) {
        return new OutcomeService(smDataTransactions, applyScopeRepository, applyRequestRepository,
                smDataClock);
    }
}
```

Run the Step 1 command. Expected: `AdmissionFencesIT` **15** tests, 0 failures. Measured facts to reproduce: isolation `read committed`; `SUPERSEDED` marking refreshes `terminal_at` (strictly after the staged stamp); the raw second REQUESTED row is refused by `apply_inflight_fence`, the raw second stop by `stop_inflight_fence`, the raw second STAGED by `staged_fence`.

- [ ] **Step 3: Obligation (d) live — `AdmissionSerializationIT`**

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligation (d), LIVE — "proven with two concurrent sessions (the second blocks … then refuses
 * cleanly)".
 *
 * <p>The blocking half is the part prose cannot carry. Every other admission test would pass against
 * an implementation that read the scope row without {@code FOR UPDATE}: the checks are all correct
 * when nothing runs concurrently. So this class holds the lock in one thread, watches the other stall
 * in {@code pg_stat_activity} from a THIRD (superuser) connection, and records how long it stalled.
 *
 * <p>Reported measurements are printed with a {@code MEASURED:} prefix rather than asserted to the
 * millisecond — the assertion is the ORDERING (blocked while held, refused after release), which is
 * deterministic; the duration is evidence, which is not.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdmissionSerializationIT {

    /** How long the holder keeps the scope row. Long enough for the poller to catch the wait. */
    private static final long HOLD_MILLIS = 3000;

    private static AdmissionService admission;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
    }

    /**
     * The harness precondition, measured rather than assumed: the runtime data source must hand out
     * two SIMULTANEOUS connections, or every test below deadlocks instead of proving anything.
     *
     * <p>{@code SmDataPostgres.runtimeDataSource()} is a {@code SimpleDriverDataSource} — not a pool,
     * which turns out to be the stronger property here: it opens a fresh physical connection per
     * {@code getConnection()} with no maximum, so no Hikari substitute is needed in the harness.
     */
    @Test
    void theRuntimeDataSourceHandsOutTwoSimultaneousConnections() throws SQLException {
        try (Connection a = SmDataPostgres.runtimeDataSource().getConnection();
                Connection b = SmDataPostgres.runtimeDataSource().getConnection()) {
            assertNotEquals(a, b, "two distinct physical connections");
            assertEquals(1, a.createStatement().executeQuery("SELECT 1").next() ? 1 : 0);
            assertEquals(1, b.createStatement().executeQuery("SELECT 1").next() ? 1 : 0);
            System.out.println("MEASURED: runtime DataSource is "
                    + SmDataPostgres.runtimeDataSource().getClass().getSimpleName()
                    + "; two concurrent connections opened, backends "
                    + backendPid(a) + " and " + backendPid(b));
        }
    }

    @Test
    void aPlacementScopedAdmissionBlocksOnTheScopeRowHeldByAnInstanceWideOneThenRefusesCleanly()
            throws Exception {
        Fixture f = new Fixture();
        f.run(f.wide, f.placement, "placement-scoped apply behind an instance-wide holder");
    }

    @Test
    void anInstanceWideAdmissionBlocksOnTheScopeRowHeldByAPlacementScopedOneThenRefusesCleanly()
            throws Exception {
        Fixture f = new Fixture();
        f.run(f.placement, f.wide, "instance-wide apply behind a placement-scoped holder");
    }

    /**
     * The same-scope PURE race: no artificial hold, two {@code admit} calls released together.
     *
     * <p>What it measures is WHICH refusal the design's two mechanisms produce when they are both
     * live. Under the scope-row lock the loser cannot reach the insert until the winner has
     * committed, at which point it SEES the winner's {@code REQUESTED} row and refuses
     * {@code SCOPE_BUSY} — the braces. {@code FENCE_RACE_LOST}, the belt, is therefore unreachable
     * through this service, and that is the point: the belt exists for writers that bypass admission,
     * which {@code AdmissionFencesIT}'s raw-insert cases exercise directly.
     */
    @Test
    void twoConcurrentAdmissionsForOneScopeProduceExactlyOneAdmittedAndOneRefused() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ScopeKey scope = ApplyEngineHarness.scope(tenant, instance, PlacementId.INSTANCE_WIDE);
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<AdmissionOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final long generation = 10L + i;
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    return admission.admit(ApplyEngineHarness.apply(scope, generation));
                }));
            }
            AdmissionOutcome first = futures.get(0).get(60, TimeUnit.SECONDS);
            AdmissionOutcome second = futures.get(1).get(60, TimeUnit.SECONDS);

            List<AdmissionOutcome> outcomes = List.of(first, second);
            long admitted = outcomes.stream()
                    .filter(o -> o instanceof AdmissionOutcome.Admitted).count();
            assertEquals(1, admitted, "exactly one may win: " + outcomes);
            AdmissionOutcome.Refused loser = outcomes.stream()
                    .filter(o -> o instanceof AdmissionOutcome.Refused)
                    .map(AdmissionOutcome.Refused.class::cast).findFirst().orElseThrow();
            System.out.println("MEASURED: same-scope pure race loser refused with "
                    + loser.reason() + " — " + loser.detail());
            assertEquals(1, ApplyEngineHarness.requests()
                    .nonTerminal(ApplyEngineHarness.tenant(tenant)).size(),
                    "exactly one row landed");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Admission ‖ landing, run live — the placement's FIRST landing, where the watermark row does not
     * yet exist.
     *
     * <p>This case has always passed, and the external review explained why that was luck rather than
     * design: a first placement landing INSERTs a {@code placement_watermark} row whose FOREIGN KEY
     * takes {@code FOR KEY SHARE} on the {@code apply_scope} row, and {@code admit}'s
     * {@code SELECT … FOR UPDATE} conflicts with that. The lock was a side effect of a foreign-key
     * check. Since finding 3, {@code OutcomeService.land} takes the scope lock EXPLICITLY and first, so
     * the manual landing below mirrors that — lock, transition, raise — and the test is honest about
     * which mechanism it exercises.
     */
    @Test
    void anAdmissionDuringAnUncommittedFirstLandingBlocksOnTheScopeRowThenIsFencedByTheWatermark()
            throws Exception {
        Landing landing = new Landing();
        landing.run(10, "the placement's FIRST landing (INSERT branch of the watermark upsert)");
    }

    /**
     * The same race on a placement that ALREADY HAS a watermark row — the external review's finding 3,
     * proved red then green.
     *
     * <p>Measured with {@code pgrowlocks} before the fix: the upsert's {@code DO UPDATE} branch takes no
     * lock at all on the parent {@code apply_scope} row, because no foreign-key check runs when the
     * child row is updated rather than inserted. So the serialization the design claimed held for a
     * placement's first landing and silently stopped from the second onwards — every later landing on
     * that placement raced admission with only the {@code blocksScope()} backstop between them. Run
     * with the {@code lockScope} call removed from the manual landing below, this test measured
     * {@code no sm_dp_runtime backend was ever seen waiting on a Lock} and the contender returning
     * {@code SCOPE_BUSY} immediately. With the explicit lock it blocks on {@code FOR UPDATE} exactly as
     * the first landing does.
     */
    @Test
    void anAdmissionDuringAnUncommittedLaterLandingBlocksToo() throws Exception {
        Landing landing = new Landing();
        // Land STAGED at 5 first, so the placement_watermark row EXISTS and the landing under test
        // takes the DO UPDATE branch. Through the service, so the row is committed and real.
        com.solace.sm.dp.apply.model.ApplyId first = landing.admitAndStart(5);
        ApplyEngineHarness.outcomes().land(landing.scope.tenant(), first,
                com.solace.sm.dp.apply.model.RequestState.STAGED);
        assertEquals(5, ApplyEngineHarness.scopes().placementWatermark(landing.scope.tenant(),
                        landing.scope.instance(), landing.scope.placement()),
                "the watermark row must exist, or this is the INSERT case again");

        landing.run(6, "a LATER landing (DO UPDATE branch — finding 3's case)");
    }

    /**
     * {@code OutcomeService.land} takes the scope lock ITSELF — the mutation prover for finding 3.
     *
     * <p>The two tests above hold a landing open by hand, because a transaction cannot be paused from
     * outside and {@code land()} runs {@code REQUIRES_NEW} and commits on its own. That makes them
     * proofs about the MECHANISM (a landing that locks the scope row serializes with admission) and
     * not about {@code land()} keeping its call to it — delete {@code scopes.lockScope} from
     * {@code OutcomeService.land} and both stay green.
     *
     * <p>So this one turns the telescope round: a third party holds the scope row, and the REAL
     * {@code land()} is measured blocking on it. Deliberately on a placement that already has a
     * watermark row, so the {@code DO UPDATE} branch runs and the explicit lock is the ONLY thing
     * that can be blocking.
     */
    @Test
    void landItselfBlocksOnAHeldScopeRowEvenOnTheDoUpdateBranch() throws Exception {
        Landing l = new Landing();
        com.solace.sm.dp.apply.model.ApplyId first = l.admitAndStart(5);
        ApplyEngineHarness.outcomes().land(l.scope.tenant(), first,
                com.solace.sm.dp.apply.model.RequestState.STAGED);
        com.solace.sm.dp.apply.model.ApplyId second = l.admitAndStart(6);

        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<Long> blockedMillis = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> ApplyEngineHarness.transactions()
                    .execute(status -> {
                        ApplyEngineHarness.scopes().lockScope(l.scope.tenant(), l.scope.instance());
                        held.countDown();
                        sleep(HOLD_MILLIS);
                        return null;
                    }));
            assertTrue(held.await(30, TimeUnit.SECONDS), "the holder never took the scope row");

            Future<?> landing = pool.submit(() -> {
                long t0 = System.nanoTime();
                ApplyEngineHarness.outcomes().land(l.scope.tenant(), second,
                        com.solace.sm.dp.apply.model.RequestState.STAGED);
                blockedMillis.set((System.nanoTime() - t0) / 1_000_000);
                return null;
            });
            String wait = pollForLockWait();
            holder.get(60, TimeUnit.SECONDS);
            landing.get(60, TimeUnit.SECONDS);

            System.out.println("MEASURED: OutcomeService.land (DO UPDATE branch) blocked "
                    + blockedMillis.get() + " ms behind a held scope row (holder held "
                    + HOLD_MILLIS + " ms); pg_stat_activity showed " + wait);
            assertEquals("Lock", waitEventType(wait),
                    "land() must be parked on a LOCK while a third party holds the scope row. "
                            + "Was: " + wait);
            assertTrue(wait.contains("FOR UPDATE"),
                    "…and on lockScope's SELECT … FOR UPDATE specifically: on the DO UPDATE branch "
                            + "nothing else in land() touches apply_scope at all. Was: " + wait);
            assertTrue(blockedMillis.get() >= HOLD_MILLIS - 250,
                    "land() returned in " + blockedMillis.get() + " ms, before the " + HOLD_MILLIS
                            + " ms hold ended — it did not take the scope lock");
            assertEquals(com.solace.sm.dp.apply.model.RequestState.STAGED,
                    ApplyEngineHarness.requests().find(l.scope.tenant(), second).orElseThrow()
                            .state(),
                    "…and it landed once the lock was free");
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------------------------

    /**
     * One placement, and the admission‖landing choreography both landing tests share.
     *
     * <p>{@link #land} mirrors {@code OutcomeService.land} statement for statement — lock the scope
     * row, transition, raise the watermark — held open by hand so a second thread can be watched
     * blocking on it. Mirroring matters: a manual landing that skipped the lock would be measuring a
     * mechanism production does not use.
     */
    private static final class Landing {

        private final UUID tenant = SmDataPostgres.freshTenant();
        private final ScopeKey scope = ApplyEngineHarness.scope(tenant, UUID.randomUUID(),
                new PlacementId(UUID.randomUUID()));

        com.solace.sm.dp.apply.model.ApplyId admitAndStart(long generation) {
            com.solace.sm.dp.apply.model.ApplyId id = assertInstanceOf(
                    AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(scope, generation))).applyId();
            ApplyEngineHarness.outcomes().start(scope.tenant(), id);
            return id;
        }

        void run(long generation, String label) throws Exception {
            com.solace.sm.dp.apply.model.ApplyId running = admitAndStart(generation);

            CountDownLatch landingOpen = new CountDownLatch(1);
            CountDownLatch mayCommit = new CountDownLatch(1);
            AtomicReference<Long> blockedMillis = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> landing = pool.submit(() -> ApplyEngineHarness.transactions()
                        .execute(status -> {
                            land(running, generation);
                            landingOpen.countDown();
                            try {
                                if (!mayCommit.await(30, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("never released");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return null;
                        }));
                assertTrue(landingOpen.await(30, TimeUnit.SECONDS), "the landing never opened");

                Future<AdmissionOutcome> contender = pool.submit(() -> {
                    long t0 = System.nanoTime();
                    AdmissionOutcome o = admission.admit(
                            ApplyEngineHarness.apply(scope, generation));
                    blockedMillis.set((System.nanoTime() - t0) / 1_000_000);
                    return o;
                });
                String wait = pollForLockWait();
                assertEquals("Lock", waitEventType(wait), label
                        + " — the admission must be parked on a lock while the landing is open. "
                        + "Was: " + wait);
                assertTrue(wait.contains("FOR UPDATE"), label
                        + " — the statement it is parked on must be lockScope's SELECT … FOR "
                        + "UPDATE, which is what land() now takes explicitly. Was: " + wait);
                Thread.sleep(HOLD_MILLIS);
                assertFalse(contender.isDone(), label + " — the admission must still be blocked "
                        + "while the landing is uncommitted (" + HOLD_MILLIS + " ms later)");

                mayCommit.countDown();
                landing.get(60, TimeUnit.SECONDS);
                AdmissionOutcome after = contender.get(60, TimeUnit.SECONDS);
                System.out.println("MEASURED: " + label + " — admission blocked "
                        + blockedMillis.get() + " ms, then " + after
                        + "; pg_stat_activity showed " + wait);

                AdmissionOutcome.Refused fenced = assertInstanceOf(AdmissionOutcome.Refused.class,
                        after, "once the landing commits, its raised watermark fences the same "
                                + "generation");
                assertEquals(
                        com.solace.sm.dp.apply.engine.RefusalReason.GENERATION_NOT_ABOVE_WATERMARK,
                        fenced.reason(), fenced.detail());
                assertInstanceOf(AdmissionOutcome.Admitted.class,
                        admission.admit(ApplyEngineHarness.apply(scope, generation + 1)),
                        "…and the next generation is admissible: the STAGED elder is superseded");
            } finally {
                pool.shutdownNow();
            }
        }

        /** {@code OutcomeService.land(STAGED)}, statement for statement, inside a caller's transaction. */
        private void land(com.solace.sm.dp.apply.model.ApplyId id, long generation) {
            ApplyEngineHarness.scopes().lockScope(scope.tenant(), scope.instance());
            ApplyEngineHarness.requests().transition(scope.tenant(), id,
                    com.solace.sm.dp.apply.model.RequestState.RUNNING,
                    com.solace.sm.dp.apply.model.RequestState.STAGED, java.time.Instant.now());
            ApplyEngineHarness.scopes().raisePlacementWatermark(scope.tenant(), scope.instance(),
                    scope.placement(), generation);
        }
    }

    /** One tenant/instance, its two scope shapes, and the two-thread choreography they share. */
    private final class Fixture {

        private final UUID tenant = SmDataPostgres.freshTenant();
        private final UUID instance = UUID.randomUUID();
        private final ScopeKey wide =
                ApplyEngineHarness.scope(tenant, instance, PlacementId.INSTANCE_WIDE);
        private final ScopeKey placement =
                ApplyEngineHarness.scope(tenant, instance, new PlacementId(UUID.randomUUID()));

        /**
         * Thread A takes the scope row and holds it; thread B admits into {@code contender} and must
         * block until A commits, then be refused.
         */
        void run(ScopeKey holder, ScopeKey contender, String label) throws Exception {
            // The scope row is COMMITTED before either thread starts. Measured (plan 004's
            // adversarial review): without this, the contender blocks on the holder's UNCOMMITTED
            // ensureScopeRow index entry — pg_stat_activity showed the INSERT … ON CONFLICT — and the
            // test stayed green with FOR UPDATE deleted from lockScope. With the row pre-committed the
            // only thing left to block on is the row lock itself, which is what obligation (d) is about.
            ApplyEngineHarness.transactions().executeWithoutResult(st ->
                    ApplyEngineHarness.scopes().ensureScopeRow(holder.tenant(), holder.instance()));

            CountDownLatch held = new CountDownLatch(1);
            AtomicReference<Long> blockedMillis = new AtomicReference<>();
            AtomicReference<AdmissionOutcome> outcome = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> a = pool.submit(() -> ApplyEngineHarness.transactions()
                        .execute(status -> {
                            ApplyEngineHarness.scopes()
                                    .ensureScopeRow(holder.tenant(), holder.instance());
                            ApplyEngineHarness.scopes()
                                    .lockScope(holder.tenant(), holder.instance());
                            held.countDown();
                            sleep(HOLD_MILLIS);
                            ApplyEngineHarness.requests()
                                    .insert(ApplyEngineHarness.requestedApply(holder, 10));
                            return null;
                        }));

                assertTrue(held.await(30, TimeUnit.SECONDS), "thread A never took the lock");

                Future<?> b = pool.submit(() -> {
                    long t0 = System.nanoTime();
                    outcome.set(admission.admit(ApplyEngineHarness.apply(contender, 11)));
                    blockedMillis.set((System.nanoTime() - t0) / 1_000_000);
                    return null;
                });

                String wait = pollForLockWait();
                a.get(60, TimeUnit.SECONDS);
                b.get(60, TimeUnit.SECONDS);

                System.out.println("MEASURED: " + label + " — contender blocked "
                        + blockedMillis.get() + " ms (holder held " + HOLD_MILLIS
                        + " ms); pg_stat_activity showed " + wait);

                assertEquals("Lock", waitEventType(wait),
                        "the contender must be waiting on a LOCK while the holder holds the scope "
                                + "row, not merely be slow. pg_stat_activity said: " + wait);
                assertTrue(wait.contains("FOR UPDATE"),
                        "the statement the contender is parked on must be lockScope's SELECT … FOR "
                                + "UPDATE — blocking on anything else (measured: the holder's "
                                + "uncommitted upsert) proves nothing about the row lock. Was: " + wait);
                assertTrue(blockedMillis.get() >= HOLD_MILLIS - 250,
                        "the contender returned in " + blockedMillis.get() + " ms, before the "
                                + HOLD_MILLIS + " ms hold ended — it did not serialize on the scope "
                                + "row at all");
                AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class,
                        outcome.get(), "after the holder commits, the contender must refuse CLEANLY");
                assertEquals(com.solace.sm.dp.apply.engine.RefusalReason.SCOPE_BUSY,
                        refused.reason(), refused.detail());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * Watches {@code pg_stat_activity} from a third, SUPERUSER connection for a {@code sm_dp_runtime}
     * backend parked on a lock. Superuser because a non-superuser sees only its own backends' query
     * text and wait state in PG 14.
     *
     * @return a human-readable description of what was seen, for the report
     */
    private static String pollForLockWait() throws InterruptedException {
        // Budget: twice the hold, derived rather than hard-coded — a poll that could run out exactly
        // when the hold ends (measured: 120 × 25 ms against a 3000 ms hold had zero margin) makes
        // obligation (d) flaky on a fast host.
        long deadline = System.nanoTime() + HOLD_MILLIS * 2 * 1_000_000;
        while (System.nanoTime() < deadline) {
            List<java.util.Map<String, Object>> rows = SmDataPostgres.superuserJdbc().queryForList(
                    "SELECT pid, wait_event_type, wait_event, state, query AS q"
                            + " FROM pg_stat_activity"
                            + " WHERE usename = 'sm_dp_runtime' AND wait_event_type = 'Lock'",
                    new MapSqlParameterSource());
            if (!rows.isEmpty()) {
                return rows.toString();
            }
            Thread.sleep(25);
        }
        return "no sm_dp_runtime backend was ever seen waiting on a Lock";
    }

    private static String waitEventType(String polled) {
        return polled.contains("wait_event_type=Lock") ? "Lock" : polled;
    }

    private static int backendPid(Connection c) throws SQLException {
        var rs = c.createStatement().executeQuery("SELECT pg_backend_pid()");
        rs.next();
        return rs.getInt(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
```

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test=AdmissionSerializationIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
Expected: **7** tests green in ~15 s; the console carries the `MEASURED:` lines. Measured on 2026-09-07 (after both external reviews' corrections): contenders blocked **~3011 ms** against a 3000 ms hold, `pg_stat_activity` showing `wait_event_type=Lock, wait_event=transactionid` on **`SELECT instance_watermark FROM sm_data.apply_scope … FOR UPDATE`** — the statement text is asserted, because the first draft of this test pre-committed nothing and measured the contender blocking on the holder's *uncommitted* `ensureScopeRow` upsert instead, which left the suite green with `FOR UPDATE` deleted; the same-scope pure race's loser refused **`SCOPE_BUSY`** (never `FENCE_RACE_LOST`); an admission during an uncommitted **first** landing (INSERT branch) and during an uncommitted **later** landing (`DO UPDATE` branch — Codex finding 3's case, which did NOT block before `land` took the lock explicitly: measured `no sm_dp_runtime backend was ever seen waiting on a Lock` and an immediate `SCOPE_BUSY`) both block on the same `FOR UPDATE` and then return `GENERATION_NOT_ABOVE_WATERMARK`; and `landItselfBlocksOnAHeldScopeRowEvenOnTheDoUpdateBranch` shows the real `land()` parked on a third party's held scope row.

- [ ] **Step 4: Obligation (n) — `MonotonicityIT`; (o)/(p)/(u) trigger halves — `OutcomeTransitionsIT`**

`MonotonicityIT.java`:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.RefusalReason;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ResolutionEvidence;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligation (n): generation monotonicity refused in ALL FOUR cross-scope directions, plus the fifth
 * case the obligation calls out by name — "including immediately after an
 * {@code INDETERMINATE_RESOLVED} with {@code resolution_outcome = 'applied'}".
 *
 * <p>The cross-scope maxima are the whole point (design §2.2 rule 1): an instance-wide {@code APPLIED}
 * at 10 must fence a later placement-scoped 9, and vice versa. A per-key lookup gets every same-shape
 * case right and every cross-shape case wrong, which is exactly the bug this obligation exists to
 * catch — so each test below asserts the WATERMARK TABLE's value after the landing as well as the
 * refusal, because a refusal with the wrong watermark behind it is a coincidence.
 */
@Testcontainers(disabledWithoutDocker = true)
class MonotonicityIT {

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static ApplyScopeRepository scopes;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        scopes = ApplyEngineHarness.scopes();
    }

    @Test
    void anInstanceWideAppliedAtTenFencesALaterPlacementScopedNineAndAdmitsEleven() {
        Fixture f = new Fixture();
        f.land(f.wide, 10, RequestState.APPLIED);

        assertEquals(10, scopes.instanceWatermark(f.tenant, f.instance),
                "an instance-wide landing raises apply_scope.instance_watermark");
        assertEquals(0, scopes.placementWatermark(f.tenant, f.instance, f.p1),
                "…and no placement row is created for it — the sentinel has no business there");

        assertRefused(admission.admit(ApplyEngineHarness.apply(f.at(f.p1), 9)));
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.at(f.p1), 11)));
    }

    @Test
    void aPlacementStagedAtTwelveFencesTheSamePlacementAtTwelveAndAdmitsThirteen() {
        Fixture f = new Fixture();
        f.land(f.at(f.p1), 12, RequestState.STAGED);

        assertEquals(12, scopes.placementWatermark(f.tenant, f.instance, f.p1),
                "STAGED is the manual strategy's terminal state and counts for monotonicity");
        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance),
                "a placement landing must NOT raise the instance watermark");

        assertRefused(admission.admit(ApplyEngineHarness.apply(f.at(f.p1), 12)),
                "the comparison is <=, not <");
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.at(f.p1), 13)));
    }

    @Test
    void aPlacementWatermarkOfTwelveFencesAnInstanceWideEleven() {
        Fixture f = new Fixture();
        ApplyId stagedOnP1 = f.land(f.at(f.p1), 12, RequestState.STAGED);

        assertEquals(12, scopes.maxPlacementWatermark(f.tenant, f.instance));
        assertRefused(admission.admit(ApplyEngineHarness.apply(f.wide, 11)),
                "an instance-wide apply is fenced by ANY placement's watermark — the direction a "
                        + "per-key index lookup cannot see across the sentinel boundary");
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.wide, 13)));

        // Design §8 row 14, pinned rather than implied: admission supersedes the elder STAGED row on
        // the EXACT scope only, so the placement's STAGED row survives an admitted instance-wide
        // apply. When the architect group decides cross-scope supersession, this is the assertion
        // that must change with it.
        assertEquals(RequestState.STAGED,
                ApplyEngineHarness.requests().find(f.tenant, stagedOnP1).orElseThrow().state(),
                "v1 is exact-scope supersession (design §8 row 14)");
    }

    @Test
    void anotherPlacementIsUnaffectedByTheFirstPlacementsWatermarkThePositiveControl() {
        Fixture f = new Fixture();
        f.land(f.at(f.p1), 12, RequestState.STAGED);

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.at(f.p2), 5)),
                "placement watermarks are per placement — a shared fence would serialize the whole "
                        + "instance, which §2.2 rule 1 deliberately does not do");
    }

    @Test
    void resolvingAnIndeterminateAsAppliedRaisesTheWatermarkImmediately() {
        Fixture f = new Fixture();
        ApplyId stuck = f.admit(f.wide, 20);
        outcomes.start(f.tenant, stuck);
        outcomes.land(f.tenant, stuck, RequestState.APPLY_INDETERMINATE);
        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance),
                "nothing is claimed while the request is unresolved");

        outcomes.resolveIndeterminate(f.tenant, stuck, new Resolution("duty-operator",
                ResolutionOutcome.APPLIED,
                new ResolutionEvidence(20, List.of(new ReplicaObservation(
                        new ReplicaRef("cache-engine-1"), true, null)))));

        assertEquals(20, scopes.instanceWatermark(f.tenant, f.instance),
                "§2.2 rule 1 names resolved-'applied' alongside STAGED and APPLIED");
        assertRefused(admission.admit(ApplyEngineHarness.apply(f.wide, 20)));
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.wide, 21)));
    }

    @Test
    void resolvingAnIndeterminateAsNotAppliedRaisesNothing() {
        Fixture f = new Fixture();
        ApplyId stuck = f.admit(f.wide, 20);
        outcomes.start(f.tenant, stuck);
        outcomes.land(f.tenant, stuck, RequestState.APPLY_INDETERMINATE);

        outcomes.resolveIndeterminate(f.tenant, stuck, new Resolution("duty-operator",
                ResolutionOutcome.NOT_APPLIED,
                new ResolutionEvidence(20, List.of(new ReplicaObservation(
                        new ReplicaRef("cache-engine-1"), false, null)))));

        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance),
                "not_applied means nothing landed, so nothing may be fenced");
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.wide, 20)),
                "…and the very same generation must be re-admissible, or the operator's honest "
                        + "'it did not happen' would have cost them the generation");
    }

    @Test
    void aFailedLandingRaisesNoWatermarkEither() {
        Fixture f = new Fixture();
        ApplyId failed = f.admit(f.wide, 30);
        outcomes.start(f.tenant, failed);
        outcomes.land(f.tenant, failed, RequestState.FAILED_NO_EFFECT);

        assertEquals(0, scopes.instanceWatermark(f.tenant, f.instance));
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(f.wide, 30)),
                "only STAGED, APPLIED and resolved-applied count — a failure must not burn the "
                        + "generation the operator is about to retry");
    }

    // -------------------------------------------------------------------------------------------

    private static void assertRefused(AdmissionOutcome outcome) {
        assertRefused(outcome, "the generation is at or below the fence");
    }

    private static void assertRefused(AdmissionOutcome outcome, String because) {
        AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class, outcome,
                "expected a refusal, got " + outcome);
        assertEquals(RefusalReason.GENERATION_NOT_ABOVE_WATERMARK, refused.reason(),
                because + " — detail was: " + refused.detail());
    }

    /** A fresh tenant/instance with two placements. */
    private static final class Fixture {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final InstanceId instance = new InstanceId(UUID.randomUUID());
        private final PlacementId p1 = new PlacementId(UUID.randomUUID());
        private final PlacementId p2 = new PlacementId(UUID.randomUUID());
        private final ScopeKey wide = new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE);

        ScopeKey at(PlacementId p) {
            return new ScopeKey(tenant, instance, p);
        }

        ApplyId admit(ScopeKey scope, long generation) {
            return assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(scope, generation)),
                    "setup admission must succeed").applyId();
        }

        ApplyId land(ScopeKey scope, long generation, RequestState terminal) {
            ApplyId id = admit(scope, generation);
            outcomes.start(tenant, id);
            outcomes.land(tenant, id, terminal);
            return id;
        }
    }
}
```

`aPlacementWatermarkOfTwelveFencesAnInstanceWideEleven` also pins design §8 row 14 with a real assertion: after the instance-wide 13 is admitted, the placement's `STAGED` row from the setup is **still `STAGED`** — exact-scope supersession only. If the architect group decides otherwise, this is the assertion that must change with it.

`OutcomeTransitionsIT.java`:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligation (o)'s trigger half, obligation (p)'s sanctioned transition, and obligation (u)'s
 * compaction branch — the three things {@code apply_request_terminal_guard} decides.
 *
 * <p>The guard has exactly three answers and all three are here: {@code STAGED → SUPERSEDED} passes
 * (sanctioned transition 1), an {@code attempt_summary}-only write on a terminal row passes
 * (sanctioned write 2), and everything else that touches a terminal row raises P0001. Testing only
 * the refusals would leave the two branches that make the design implementable unproven — and the
 * design says so: "measured: without this branch, compaction was unimplementable while the attempt
 * DELETE half succeeded".
 */
@Testcontainers(disabledWithoutDocker = true)
class OutcomeTransitionsIT {

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static ApplyRequestRepository requests;
    private static NamedParameterJdbcTemplate runtime;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        requests = ApplyEngineHarness.requests();
        runtime = ApplyEngineHarness.jdbc();
    }

    @Test
    void theLegalWalkRequestedToRunningToAppliedSucceedsThroughTheService() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);

        assertEquals(RequestState.REQUESTED, f.state(id));
        assertNull(requests.find(f.tenant, id).orElseThrow().terminalAt());

        outcomes.start(f.tenant, id);
        assertEquals(RequestState.RUNNING, f.state(id));
        assertNull(requests.find(f.tenant, id).orElseThrow().terminalAt(),
                "RUNNING is not terminal, so terminal_has_timestamp requires NULL");

        outcomes.land(f.tenant, id, RequestState.APPLIED);
        assertEquals(RequestState.APPLIED, f.state(id));
        assertNotNull(requests.find(f.tenant, id).orElseThrow().terminalAt());
    }

    @Test
    void landingOnAnAlreadyTerminalRowIsRefusedInJavaAndWritesNothing() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLIED);
        Instant terminalAt = requests.find(f.tenant, id).orElseThrow().terminalAt();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.land(f.tenant, id, RequestState.FAILED_NO_EFFECT));
        assertTrue(e.getMessage().contains("terminal"), e.getMessage());

        ApplyRequestRow after = requests.find(f.tenant, id).orElseThrow();
        assertEquals(RequestState.APPLIED, after.state(), "nothing may have been written");
        assertEquals(terminalAt, after.terminalAt());
    }

    @Test
    void aRawTerminalToNonTerminalWalkIsRefusedByTheTriggerWithItsOwnWording() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLIED);

        Refusals.assertRaised("is terminal (APPLIED) and immutable",
                () -> requests.transition(f.tenant, id, RequestState.APPLIED,
                        RequestState.REQUESTED, null));
    }

    @Test
    void theStagedToSupersededWalkPassesTheTriggerWhileStagedToAppliedDoesNot() {
        Fixture f = new Fixture();
        ApplyId staged = f.admit(10);
        outcomes.start(f.tenant, staged);
        outcomes.land(f.tenant, staged, RequestState.STAGED);

        Refusals.assertRaised("is terminal (STAGED) and immutable",
                () -> requests.transition(f.tenant, staged, RequestState.STAGED,
                        RequestState.APPLIED, Instant.now()));

        assertEquals(1, requests.transition(f.tenant, staged, RequestState.STAGED,
                        RequestState.SUPERSEDED, Instant.now()),
                "the ONE sanctioned terminal transition — obligation (p)");
        assertEquals(RequestState.SUPERSEDED, f.state(staged));
    }

    @Test
    void anAttemptSummaryOnlyUpdateOnATerminalRowSucceedsWhileOneTouchingAnotherColumnIsRefused() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLIED);

        assertEquals(1, requests.foldAttemptSummary(f.tenant, id,
                        "{\"MATERIALIZE\": {\"attempts\": 2, \"lastError\": \"io.timeout\"}}"),
                "obligation (u): sanctioned write 2 on a terminal row");
        assertEquals("{\"MATERIALIZE\": {\"attempts\": 2, \"lastError\": \"io.timeout\"}}",
                summaryOf(f.tenant, id));

        // The same statement, one GRANTED column wider (resolved_by — resolution_note would be
        // refused earlier still, by the grant, see PrivilegeTopologyIT). BEFORE UPDATE triggers run
        // before CHECK constraints, so this is the trigger's refusal (P0001), not
        // resolution_only_when_resolved's 23514 — and asserting the P0001 wording tells the two apart.
        Refusals.assertRaised("is terminal (APPLIED) and immutable",
                () -> runtime.update("UPDATE sm_data.apply_request"
                                + " SET attempt_summary = CAST(:s AS jsonb), resolved_by = :n"
                                + " WHERE tenant_id = :t AND apply_id = :a",
                        new MapSqlParameterSource()
                                .addValue("s", "{\"folded\": true}")
                                .addValue("n", "sneaking a second column in")
                                .addValue("t", f.tenant.value()).addValue("a", id.value())));
    }

    // -------------------------------------------------------------------------------------------
    // state_fits_operation, in Java before the write and in SQL as the last line. Finding 4: land()
    // used to accept any non-terminal→terminal walk and let the CHECK decide, so an engine bug
    // landing a stop on APPLIED surfaced as a 23514 out of a repository rather than as a sentence.
    // -------------------------------------------------------------------------------------------

    @Test
    void anApplyLandedOnAStopOutcomeIsRefusedInJavaAndTheRowIsUnchanged() {
        // A fresh fixture per case: apply_inflight_fence allows one in-flight apply per scope, so
        // two REQUESTED applies on one instance would be refused at admission and prove nothing.
        for (RequestState wrong : List.of(RequestState.STOPPED, RequestState.STOP_FAILED)) {
            Fixture f = new Fixture();
            assertRefusedByStateFitsOperation(f, f.admit(10), wrong, "apply");
        }
    }

    @Test
    void aStopLandedOnAnApplyOutcomeIsRefusedInJavaAndTheRowIsUnchanged() {
        for (RequestState wrong : List.of(RequestState.APPLIED, RequestState.STAGED)) {
            Fixture f = new Fixture();
            ApplyId id = f.admitStop();
            assertRefusedByStateFitsOperation(f, id, wrong, "stop");
        }
    }

    @Test
    void aRestartLandedOnStagedIsRefusedInJavaBecauseARestartHasNoStagingStep() {
        Fixture f = new Fixture();
        ApplyId id = f.admitRestart();
        assertRefusedByStateFitsOperation(f, id, RequestState.STAGED, "restart");
    }

    @Test
    void landingOnIndeterminateResolvedIsRefusedBecauseOnlyResolveIndeterminateWritesTheEvidence() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLY_INDETERMINATE);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.land(f.tenant, id, RequestState.INDETERMINATE_RESOLVED));
        assertTrue(e.getMessage().contains("resolveIndeterminate"), e.getMessage());
        assertTrue(e.getMessage().contains("resolution_is_evidence"), e.getMessage());
        assertEquals(RequestState.APPLY_INDETERMINATE, f.state(id),
                "the row is exactly as it was — land() refused before writing. Had it written, the "
                        + "CHECK would have refused an INDETERMINATE_RESOLVED with four NULL "
                        + "resolution columns anyway, and the request would be stuck");
    }

    /**
     * Schema ahead of use: {@code resolution_note} exists in §3.1's DDL and is in the runtime's
     * column-scoped UPDATE grant, and v1 never writes it. An external review named it the one place an
     * operator could paste a secret into during an incident (persisted, then printed by JDBC's TRACE
     * bind-value line), so the {@code Resolution} type has no free-text component and the repository's
     * SET list omits the column. This asserts the column IS null after a resolution, not merely that no
     * Java field maps to it.
     */
    @Test
    void aResolutionLeavesResolutionNoteNullBecauseV1WritesNoFreeText() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLY_INDETERMINATE);
        outcomes.resolveIndeterminate(f.tenant, id, new com.solace.sm.dp.apply.model.Resolution(
                "duty-operator", com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                new com.solace.sm.dp.apply.model.ResolutionEvidence(10, List.of(
                        new com.solace.sm.dp.apply.model.ReplicaObservation(
                                new com.solace.sm.dp.apply.model.ReplicaRef("cache-engine-1"), true,
                                null)))));

        assertEquals(RequestState.INDETERMINATE_RESOLVED, f.state(id));
        Boolean noteIsNull = runtime.queryForObject(
                "SELECT resolution_note IS NULL FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", f.tenant.value()).addValue("a", id.value()),
                Boolean.class);
        assertEquals(Boolean.TRUE, noteIsNull,
                "resolution_note is deferred schema — no v1 path may write it");
    }

    /**
     * The SQL half of the same rule, on the one pair the Java half now refuses first.
     *
     * <p>Going around {@code OutcomeService} on purpose: {@code state_fits_operation} stays the last
     * line, and a Java guard nobody can bypass is not a proof that the database has one.
     */
    @Test
    void theRawTransitionOfAnApplyToStoppedIsRefusedByTheDatabaseWithStateFitsOperation() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);

        PgConstraintViolation v = Refusals.assertViolation(Refusals.CHECK_VIOLATION,
                "state_fits_operation",
                () -> requests.transition(f.tenant, id, RequestState.REQUESTED,
                        RequestState.STOPPED, Instant.now()));
        assertEquals("apply_request", v.table());
        assertEquals(RequestState.REQUESTED, f.state(id));
    }

    // -------------------------------------------------------------------------------------------

    /** Refused in Java, with the rule named, and nothing written. */
    private static void assertRefusedByStateFitsOperation(Fixture f, ApplyId id,
            RequestState wrong, String operation) {
        RequestState before = f.state(id);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.land(f.tenant, id, wrong),
                operation + " must not be able to land on " + wrong);
        assertTrue(e.getMessage().contains("state_fits_operation"),
                "the refusal must name the rule it mirrors. Was: " + e.getMessage());
        assertTrue(e.getMessage().contains(operation) && e.getMessage().contains(wrong.name()),
                e.getMessage());
        assertEquals(before, f.state(id), "nothing may have been written");
        assertNull(requests.find(f.tenant, id).orElseThrow().terminalAt());
    }

    @Test
    void aRequestWhoseScopeRowDoesNotExistIsRefusedByRequestHasScope() {
        TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        ScopeKey orphan = new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE);

        PgConstraintViolation v = Refusals.assertViolation(Refusals.FOREIGN_KEY_VIOLATION,
                "request_has_scope",
                () -> requests.insert(ApplyEngineHarness.requestedApply(orphan, 1)));
        assertEquals("apply_request", v.table());

        // Positive control: the identical insert succeeds the moment the lock row exists, so the
        // refusal above is the FK and not a malformed row.
        ApplyEngineHarness.scopes().ensureScopeRow(tenant, instance);
        requests.insert(ApplyEngineHarness.requestedApply(orphan, 1));
        assertEquals(1, requests.nonTerminal(tenant).size());
    }

    // -------------------------------------------------------------------------------------------

    private static String summaryOf(TenantId t, ApplyId id) {
        return runtime.queryForObject(
                "SELECT attempt_summary::text FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value()),
                String.class);
    }

    private static final class Fixture {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final InstanceId instance = new InstanceId(UUID.randomUUID());
        private final ScopeKey wide = new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE);

        ApplyId admit(long generation) {
            return assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(wide, generation))).applyId();
        }

        ApplyId admitStop() {
            return assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.stop(wide))).applyId();
        }

        ApplyId admitRestart() {
            return assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.restart(wide))).applyId();
        }

        RequestState state(ApplyId id) {
            return requests.find(tenant, id).orElseThrow().state();
        }
    }
}
```

- [ ] **Step 5: Verify, then prove the cross-scope rule fires**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify`
Expected: BUILD SUCCESS; surefire dp-core 108 (unchanged); failsafe `Tests run: 74, Skipped: 2` — Task 3's 33 + `AdmissionFencesIT` 15 + `AdmissionSerializationIT` 7 + `MonotonicityIT` 7 + `OutcomeTransitionsIT` 12 (the five new ones are Codex finding 4's: an `apply` landed on a stop outcome, a `stop` on an apply outcome, a `restart` on `STAGED`, `land(…, INDETERMINATE_RESOLVED)` — each refused in Java with the row unchanged — and the raw `apply → STOPPED` transition refused by the database with `23514 state_fits_operation`, the last line). Measured facts: P0001 texts `apply_request <uuid> is terminal (APPLIED) and immutable` / `… (STAGED) …`; `request_has_scope` on the orphan insert; watermark values 10 / 12 / 20 / 0 exactly as asserted.

**Prove it fires (measured):** in `AdmissionService`, change `.filter(r -> r.scope().overlaps(scope))` to `.filter(r -> r.scope().equals(scope))` — the cross-scope rule collapses to exact-scope equality, which is what the partial unique indexes already give. Run
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test='AdmissionFencesIT,AdmissionSerializationIT' -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=true`.
Expected: exactly **four** reds — `AdmissionFencesIT.aPlacementScopedApplyIsRefusedWhileAnInstanceWideOneIsRunning` and `anInstanceWideApplyIsRefusedWhileAPlacementScopedOneIsRunning` (`expected a refusal, got Admitted[…]`), and both `AdmissionSerializationIT` holder/contender tests (`after the holder commits, the contender must refuse CLEANLY` — the contender still blocks, then is *admitted*). The same-scope tests, including the admission‖landing one, stay green, which is the point: without the overlap rule, cross-scope admission is exactly what the indexes cannot refuse. Revert; re-run; green.

**Prove it fires, second mutation (measured — the one the adversarial review found missing):** delete ` FOR UPDATE` from `ApplyScopeRepository.lockScope`'s statement and run `… -Dit.test=AdmissionSerializationIT …`. Expected: **five of seven** red — both holder/contender tests, both admission‖landing tests and `landItselfBlocksOnAHeldScopeRow…`, each with `… no sm_dp_runtime backend was ever seen waiting on a Lock` (only the two tests that assert no lock stay green). Before the review's correction this mutation left the suite **green**. Revert; re-run; green.

**Prove it fires, third mutation (measured — Codex finding 3):** remove the `lockScope(row)` call from `OutcomeService.land` and run the same class. Expected: exactly **one** red — `landItselfBlocksOnAHeldScopeRowEvenOnTheDoUpdateBranch` (`no sm_dp_runtime backend was ever seen waiting on a Lock`): on the `DO UPDATE` branch nothing but the explicit lock can block the landing. Revert; re-run; green.

- [ ] **Step 6: Commit**

```bash
git add backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ApplyEngineHarness.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/AdmissionFencesIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/AdmissionSerializationIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/MonotonicityIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/OutcomeTransitionsIT.java
git commit -m "Add the serialized admission transaction and the outcome walks with their fence, lock and monotonicity proofs (Task 4)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

**Risks:** `AdmissionSerializationIT` asserts ordering, prints durations — a loaded CI box can make the 3000 ms hold read as 3400 ms, which the `>= HOLD_MILLIS - 250` lower bound tolerates; an *upper* bound was deliberately not asserted; the lock poll's budget is `2 × HOLD_MILLIS`, derived, not hard-coded (a 120 × 25 ms loop against a 3000 ms hold had zero margin). `FENCE_RACE_LOST` is kept as a reason although the service cannot reach it (measured) — it documents the belt and is stated untested; do not write a test expecting it through `admit`. Landing serializes against admission by the **explicit `lockScope`** in `OutcomeService` — the FK's `KEY SHARE` on the scope row is now incidental (it exists only on the placement upsert's INSERT branch; measured absent on `DO UPDATE`), and the `blocksScope()` backstop stays stated; the wait surfaces in `pg_stat_activity` as `wait_event = 'transactionid'`, not a tuple lock. Cross-scope supersession is exact-scope only by design decision pending (§8 row 14).

---
### Task 5: Immutable step intents, the retry journal, the resume read, and the Spring-wired proof (`dp-core`)

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/{StepIntentService,AttemptPolicy,AttemptService}.java`
- Modify: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/ApplyEngineConfiguration.java` (append three beans)
- Modify (test): `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ApplyEngineHarness.java` (append `stepIntents()`, `attemptsService(int)`)
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/{StepIntentIT,AttemptIT,EngineTransactionBoundaryIT,ResumeReadIT,ApplyPersistenceConfigurationIT}.java`

**Interfaces:**
- Consumes: Task 3's `StepIntentRepository`/`AttemptRepository`/`ApplyRequestRepository`, `Constraints.{INTENT_STEP_REPLICA_UNIQUE, INTENT_IDEMPOTENCY_KEY_UNIQUE, ATTEMPT_NO_UNIQUE}`, `SmDataProperties.Apply.maxAttempts()`; Task 4's `EngineTransactions`/`AdmissionService`/`OutcomeService`/`ApplyEngineConfiguration`.
- Produces: `StepIntentService.ensureIntent(TenantId, ApplyId, Step, ReplicaRef, String key, String hash) → StepIntentRow` with `IntentMismatch`, `IdempotencyKeyReused`, `TerminalRequest`, and the measurement hook `arbiterReFinds() → long`; `AttemptPolicy(int maxAttempts)`; `AttemptService.{open(TenantId, ApplyId, StepIntentRow, PreBaseline, Duration stepBudget) → AttemptRow, close(TenantId, UUID attemptId, AttemptOutcome, String observedHash, ErrorClass, RuntimeRef)}` — the typed evidence seam: secret-SHAPED values (env lines, headers, blobs) are refused before any SQL; a bare identifier-shaped token is not, so content stays the adapter's with `RetryCapExceeded` and `ConcurrentAttempt` (the loser of a concurrent open for the same intent — measured: at READ COMMITTED both openers read the same count, and `UNIQUE (intent_id, attempt_no)` is what makes exactly one of them the attempt); beans `stepIntentService`, `attemptPolicy` (from `sm.data.apply.max-attempts`, default 3), `attemptService`.

**Preconditions:** Task 4 committed and green.

**The mechanism this task implements is §2.2 rule 2 *as amended by the plan-004 prototype measurement* (design commit `3977e26`):** find → absent → plain `INSERT` (never `ON CONFLICT`). A `23505` naming **either** intent UNIQUE marks the transaction rollback-only and the service re-reads the logical `(apply, step, replica)` in a **fresh** transaction (the first is aborted by the 23505 — measured, every later statement on it fails with `25P02`). Row found → compare key and hash (the race); no row → `IdempotencyKeyReused` (the protocol error). **Why not classify by constraint name:** a legitimate concurrent same-step create presents the *same* key, so it violates **both** UNIQUEs, and PostgreSQL reports whichever index it reaches first — measured to be `apply_step_intent_idempotency_key_key`, the inline column constraint. `StepIntentIT.anIdenticalRawReInsertNamesTheIdempotencyKeyIndexNotTheCompositeArbiter` pins that name so the comment can never silently go stale.

- [ ] **Step 1: Write the failing tests — `StepIntentIT` first**

Append to `ApplyEngineHarness` (imports: `com.solace.sm.dp.apply.engine.AttemptPolicy`, `com.solace.sm.dp.apply.engine.AttemptService`, `com.solace.sm.dp.apply.engine.StepIntentService`):

```java
    static StepIntentService stepIntents() {
        return new StepIntentService(transactions(), requests(), intents(), Clock.systemUTC());
    }

    static AttemptService attemptsService(int maxAttempts) {
        return new AttemptService(transactions(), attempts(), new AttemptPolicy(maxAttempts),
                Clock.systemUTC());
    }
```

`StepIntentIT.java` — obligations (f), (g), (q):

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations (f), (g) and (q) — the intent table's two UNIQUEs, its two shape CHECKs, and the one
 * rule it has no artifact for.
 *
 * <p>Obligation (q) is the interesting one: the design says intents-only-for-open-requests is
 * engine-enforced because the FK can only say the request EXISTS. This class proves that claim from
 * both sides — the service refuses, and the raw repository insert against the same terminal request
 * SUCCEEDS. Without the second half, "engine-enforced" would be an assertion about the schema that
 * nobody had checked.
 */
@Testcontainers(disabledWithoutDocker = true)
class StepIntentIT {

    private static final String HASH = "sha256:1111111111111111111111111111111111111111";
    private static final String OTHER_HASH = "sha256:2222222222222222222222222222222222222222";

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static StepIntentRepository intents;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        intents = ApplyEngineHarness.intents();
    }

    // -------------------------------------------------------------------------------------------
    // (g): one durable intent per logical step, and the two ways of getting it wrong.
    // -------------------------------------------------------------------------------------------

    @Test
    void ensuringTheSameIntentTwiceWithTheSameKeyAndHashReturnsTheSameRow() {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        StepIntentRow first = service.ensureIntent(f.tenant, f.apply, Step.MATERIALIZE,
                f.replica, f.key, HASH);
        StepIntentRow again = service.ensureIntent(f.tenant, f.apply, Step.MATERIALIZE,
                f.replica, f.key, HASH);

        assertEquals(first.intentId(), again.intentId(),
                "the binding is a ROW; a retry must land on the one that exists");
        assertEquals(1, intents.forRequest(f.tenant, f.apply).size());
        assertEquals(0, service.arbiterReFinds(),
                "the second call found the row on its first read — no race, no re-find");
    }

    @Test
    void aRetryPresentingTheRecordedKeyWithADifferentHashIsRejectedByTheReadCompare() {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        service.ensureIntent(f.tenant, f.apply, Step.MATERIALIZE, f.replica, f.key, HASH);

        StepIntentService.IntentMismatch e = assertThrows(StepIntentService.IntentMismatch.class,
                () -> service.ensureIntent(f.tenant, f.apply, Step.MATERIALIZE, f.replica, f.key,
                        OTHER_HASH));
        assertTrue(e.getMessage().contains("intends hash"), e.getMessage());
    }

    @Test
    void aSecondIntentForTheSameStepAndReplicaWithADifferentKeyIsRejectedAtBothLayers() {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        service.ensureIntent(f.tenant, f.apply, Step.MATERIALIZE, f.replica, f.key, HASH);

        assertThrows(StepIntentService.IntentMismatch.class,
                () -> service.ensureIntent(f.tenant, f.apply, Step.MATERIALIZE, f.replica,
                        f.key + "-different", HASH),
                "the engine's read-compare catches it first");

        // …and the composite UNIQUE catches a writer that went around the engine. The key differs,
        // so only ONE of the two indexes can fire here — which is what makes this the composite's
        // own proof rather than a repeat of the idempotency-key case below.
        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, Constraints.INTENT_STEP_REPLICA_UNIQUE,
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply,
                        Step.MATERIALIZE, f.replica, f.key + "-different", HASH, Instant.now())));
    }

    @Test
    void reusingAnIdempotencyKeyUnderAnotherStepIsRejectedAtBothLayers() {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        service.ensureIntent(f.tenant, f.apply, Step.MATERIALIZE, f.replica, f.key, HASH);

        assertThrows(StepIntentService.IdempotencyKeyReused.class,
                () -> service.ensureIntent(f.tenant, f.apply, Step.LIFECYCLE, f.replica, f.key,
                        null));

        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION,
                Constraints.INTENT_IDEMPOTENCY_KEY_UNIQUE,
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply,
                        Step.LIFECYCLE, f.replica, f.key, null, Instant.now())));
    }

    /**
     * WHICH index PostgreSQL names when a duplicate violates BOTH — the fact
     * {@link StepIntentService}'s classification is built around.
     *
     * <p>Design §2.2 rule 2 originally called the composite the "arbiter" and classified by name.
     * Measured here: an identical re-insert trips {@code apply_step_intent_idempotency_key_key}
     * first. Recorded rather than merely asserted, since the design's amended text cites the name.
     */
    @Test
    void anIdenticalRawReInsertNamesTheIdempotencyKeyIndexNotTheCompositeArbiter() {
        Fixture f = new Fixture();
        StepIntentRow row = new StepIntentRow(Uuid7.next(), f.apply, Step.MATERIALIZE, f.replica,
                f.key, HASH, Instant.now());
        intents.insert(f.tenant, row);

        PgConstraintViolation v = Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, null,
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply,
                        Step.MATERIALIZE, f.replica, f.key, HASH, Instant.now())));
        System.out.println("MEASURED: a duplicate violating BOTH intent UNIQUEs is reported as "
                + v.constraint());
        assertEquals(Constraints.INTENT_IDEMPOTENCY_KEY_UNIQUE, v.constraint(),
                "if this ever becomes the composite, StepIntentService's comment is stale");
    }

    @Test
    void twoThreadsEnsuringTheSameIntentConcurrentlyBothReceiveTheOneRowAndTheLoserReFinds()
            throws Exception {
        Fixture f = new Fixture();
        StepIntentService service = ApplyEngineHarness.stepIntents();
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<StepIntentRow>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);
                    return service.ensureIntent(f.tenant, f.apply, Step.MATERIALIZE, f.replica,
                            f.key, HASH);
                }));
            }
            StepIntentRow a = futures.get(0).get(60, TimeUnit.SECONDS);
            StepIntentRow b = futures.get(1).get(60, TimeUnit.SECONDS);

            assertEquals(a.intentId(), b.intentId(),
                    "the race must resolve to exactly one durable intent, both callers seeing it");
            assertEquals(1, intents.forRequest(f.tenant, f.apply).size(),
                    "…and exactly one row must exist");
            System.out.println("MEASURED: arbiter race re-find path executed "
                    + service.arbiterReFinds() + " time(s) for 2 concurrent ensureIntent calls");
            assertEquals(1, service.arbiterReFinds(),
                    "the loser must actually have gone down the fresh-transaction re-find path. "
                            + "Zero would mean the two calls never really collided and this test "
                            + "proves nothing about the race");
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------------------------
    // (q): engine-enforced, with the proof that the database does NOT enforce it.
    // -------------------------------------------------------------------------------------------

    @Test
    void theServiceRefusesAnIntentOnATerminalRequestWhileTheRawInsertStillSucceeds() {
        Fixture f = new Fixture();
        outcomes.start(f.tenant, f.apply);
        outcomes.land(f.tenant, f.apply, RequestState.APPLIED);

        StepIntentService service = ApplyEngineHarness.stepIntents();
        StepIntentService.TerminalRequest e = assertThrows(StepIntentService.TerminalRequest.class,
                () -> service.ensureIntent(f.tenant, f.apply, Step.MATERIALIZE, f.replica, f.key,
                        HASH));
        assertTrue(e.getMessage().contains("obligation (q)"), e.getMessage());

        // The other half of the claim: no DDL artifact stops this.
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, Step.MATERIALIZE,
                f.replica, f.key + "-raw", HASH, Instant.now()));
        assertEquals(1, intents.forRequest(f.tenant, f.apply).size(),
                "the FK only says the request EXISTS — 'still open' is a temporal rule no CHECK can "
                        + "hold, which is exactly why obligation (q) is engine-side");
    }

    // -------------------------------------------------------------------------------------------
    // (f): hash_on_config_steps and placement_scoped_steps, both directions each.
    // -------------------------------------------------------------------------------------------

    @Test
    void aConfigCarryingStepWithoutAHashIsRefusedByHashOnConfigSteps() {
        Fixture f = new Fixture();
        for (Step step : List.of(Step.MATERIALIZE, Step.VERIFY, Step.ROLLBACK)) {
            ReplicaRef replica = step.placementScoped() ? ReplicaRef.PLACEMENT : f.replica;
            Refusals.assertViolation(Refusals.CHECK_VIOLATION, "hash_on_config_steps",
                    () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, step,
                            replica, f.freshKey(), null, Instant.now())));
        }
    }

    @Test
    void aHashlessStepCarryingAHashIsRefusedByTheSameCheckFromTheOtherSide() {
        Fixture f = new Fixture();
        for (Step step : List.of(Step.STOP, Step.PREFLIGHT, Step.LIFECYCLE, Step.ROLLBACK_ASSESS)) {
            ReplicaRef replica = step.placementScoped() ? ReplicaRef.PLACEMENT : f.replica;
            Refusals.assertViolation(Refusals.CHECK_VIOLATION, "hash_on_config_steps",
                    () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, step,
                            replica, f.freshKey(), HASH, Instant.now())));
        }
    }

    @Test
    void aPlacementScopedStepAddressingARealReplicaIsRefusedByPlacementScopedSteps() {
        Fixture f = new Fixture();
        Refusals.assertViolation(Refusals.CHECK_VIOLATION, "placement_scoped_steps",
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply,
                        Step.PREFLIGHT, new ReplicaRef("r1"), f.freshKey(), null, Instant.now())));
    }

    @Test
    void aReplicaScopedStepAddressingThePlacementSentinelIsRefusedByTheSameCheck() {
        Fixture f = new Fixture();
        Refusals.assertViolation(Refusals.CHECK_VIOLATION, "placement_scoped_steps",
                () -> intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply,
                        Step.MATERIALIZE, ReplicaRef.PLACEMENT, f.freshKey(), HASH, Instant.now())));
    }

    @Test
    void theLegalCombinationsOfBothChecksAreAcceptedThePositiveControl() {
        Fixture f = new Fixture();
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, Step.PREFLIGHT,
                ReplicaRef.PLACEMENT, f.freshKey(), null, Instant.now()));
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, Step.MATERIALIZE,
                f.replica, f.freshKey(), HASH, Instant.now()));
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, Step.LIFECYCLE,
                f.replica, f.freshKey(), null, Instant.now()));
        intents.insert(f.tenant, new StepIntentRow(Uuid7.next(), f.apply, Step.ROLLBACK,
                ReplicaRef.PLACEMENT, f.freshKey(), HASH, Instant.now()));
        assertEquals(4, intents.forRequest(f.tenant, f.apply).size());
    }

    // -------------------------------------------------------------------------------------------

    /** A fresh tenant with one admitted, still-open instance-wide apply to hang intents off. */
    private static final class Fixture {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final InstanceId instance = new InstanceId(UUID.randomUUID());
        private final ScopeKey wide = new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE);
        private final ReplicaRef replica = new ReplicaRef("cache-engine-1");
        private final ApplyId apply;
        private final String key;

        Fixture() {
            apply = assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(wide, 10))).applyId();
            key = "idem-" + apply.value();
        }

        String freshKey() {
            return "idem-" + UUID.randomUUID();
        }
    }
}
```

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test=StepIntentIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
Expected: test-compilation failure — cannot find symbol `StepIntentService` (and the harness's `stepIntents()`). The red.

- [ ] **Step 2: `StepIntentService`, `AttemptPolicy`, `AttemptService`; wire the beans**

`StepIntentService.java`:

```java
package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.Constraints;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import com.solace.sm.dp.apply.persistence.StepIntentRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §2.2 rule 2, executable: exactly one durable intent per logical {@code (apply, step, replica)},
 * with the composite UNIQUE as the ARBITER rather than a lock.
 *
 * <p><b>Two transactions, and the second one is not optional.</b> The find-then-insert runs at READ
 * COMMITTED in transaction one. When a concurrent creator wins, PostgreSQL raises 23505 and
 * <i>aborts that transaction</i> — every subsequent statement on it fails with 25P02. So the losing
 * side cannot re-read inside the transaction it lost in; it must open a NEW one. That is measured, not
 * inferred: {@code StepIntentIT}'s arbiter race counts the re-finds this class performs.
 *
 * <p><b>Which index is the arbiter is a MEASURED fact, and it is not the one the design first
 * named.</b> Design §2.2 rule 2 originally called the composite {@code (tenant, apply, step, replica)}
 * UNIQUE "the arbiter" and treated a conflict on {@code idempotency_key} as the protocol error. But a
 * genuine concurrent same-step create presents the SAME key, so it violates BOTH indexes, and
 * PostgreSQL reports whichever it reaches first — which is {@code apply_step_intent_idempotency_key_key},
 * because an inline column {@code UNIQUE} is created before the table-level ones and index insertion
 * follows that order ({@code StepIntentIT} records the name it actually got). Classifying on the name
 * alone would therefore turn every legitimate race into {@code IdempotencyKeyReused}.
 *
 * <p>So the classification is by <b>outcome of the re-read</b>, with the name only deciding whether a
 * re-read is warranted at all: either 23505 sends this caller into a fresh transaction, and what it
 * finds there settles the story. A row for this logical step means the race happened — compare and
 * return it. NO row for this logical step, with one of the two keys nevertheless taken, means the key
 * belongs to some other step, which is the protocol error no retry can fix. Both endings are the ones
 * §2.2 rule 2 asks for; only the discriminator moved. {@code ON CONFLICT … DO NOTHING} remains
 * unusable for exactly the reason the design gives.
 *
 * <p><b>Obligation (q) is engine-enforced and the database has no artifact for it</b> — the FK only
 * says the request exists, not that it is still open. {@link TerminalRequest} is that rule, and
 * {@code StepIntentIT} proves the raw repository insert against a terminal request still succeeds, so
 * the claim "engine-enforced" is measured rather than asserted.
 */
public final class StepIntentService {

    private final TransactionTemplate tx;
    private final ApplyRequestRepository requests;
    private final StepIntentRepository intents;
    private final Clock clock;
    private final AtomicLong arbiterReFinds = new AtomicLong();

    public StepIntentService(TransactionTemplate tx, ApplyRequestRepository requests,
            StepIntentRepository intents, Clock clock) {
        this.tx = EngineTransactions.requiresNew(tx);
        this.requests = requests;
        this.intents = intents;
        this.clock = clock;
    }

    /**
     * The one durable intent for this logical step, creating it if this caller is the first.
     *
     * @throws IntentMismatch        the recorded intent carries a different key or hash
     * @throws IdempotencyKeyReused  the key already belongs to a different step
     * @throws TerminalRequest       the request is terminal — obligation (q)
     */
    public StepIntentRow ensureIntent(TenantId t, ApplyId id, Step step, ReplicaRef replica,
            String idempotencyKey, String configHash) {
        Objects.requireNonNull(idempotencyKey, "idempotency key");
        StepIntentRow settled = tx.execute(status -> {
            Optional<StepIntentRow> found = intents.find(t, id, step, replica);
            if (found.isPresent()) {
                return found.get();
            }
            ApplyRequestRow request = requests.find(t, id).orElseThrow(
                    () -> new IllegalStateException("no apply_request " + id.value()));
            if (request.state().isTerminal()) {
                throw new TerminalRequest("apply_request " + id.value() + " is terminal ("
                        + request.state() + "); obligation (q) forbids new intents on it");
            }
            StepIntentRow fresh = new StepIntentRow(Uuid7.next(), id, step, replica, idempotencyKey,
                    configHash, clock.instant());
            try {
                intents.insert(t, fresh);
                return fresh;
            } catch (PgConstraintViolation e) {
                if (e.namesAnyOf(Constraints.INTENT_STEP_REPLICA_UNIQUE,
                        Constraints.INTENT_IDEMPOTENCY_KEY_UNIQUE)) {
                    // Somebody else holds one of the two keys. This transaction is now ABORTED, so
                    // the read-compare cannot happen here — signal with null and let a NEW one run.
                    status.setRollbackOnly();
                    return null;
                }
                throw e;
            }
        });

        if (settled != null) {
            return compare(settled, idempotencyKey, configHash);
        }
        arbiterReFinds.incrementAndGet();
        Optional<StepIntentRow> winner = tx.execute(status -> intents.find(t, id, step, replica));
        if (winner.isEmpty()) {
            // No row for THIS logical step, yet one of the two UNIQUEs refused: the only remaining
            // explanation is that the key belongs to some other step. That is the protocol error.
            // The key itself is caller-supplied free text and is not echoed (I6 posture: identifiers
            // in messages, never values) — the step, replica and apply identify the collision.
            throw new IdempotencyKeyReused("the supplied idempotency key already belongs to another "
                    + "step; no intent exists for " + step + "/" + replica.value() + " on apply "
                    + id.value());
        }
        return compare(winner.get(), idempotencyKey, configHash);
    }

    /**
     * How many times the arbiter race sent this instance down the re-find path. A measurement hook,
     * not behaviour: {@code StepIntentIT} needs to prove the loser really re-read rather than
     * accidentally finding the row on its first {@code find}.
     */
    public long arbiterReFinds() {
        return arbiterReFinds.get();
    }

    private static StepIntentRow compare(StepIntentRow recorded, String key, String hash) {
        if (!recorded.idempotencyKey().equals(key)) {
            throw new IntentMismatch("intent " + recorded.intentId()
                    + " is bound to a different idempotency key than the one supplied");
        }
        if (!Objects.equals(recorded.configHash(), hash)) {
            throw new IntentMismatch("intent " + recorded.intentId() + " intends hash "
                    + recorded.configHash() + ", not " + hash + " (hashes are identifiers, echoed)");
        }
        return recorded;
    }

    /** A retry presented the recorded key with a different hash, or a different key entirely. */
    public static final class IntentMismatch extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IntentMismatch(String message) {
            super(message);
        }
    }

    /** {@code idempotency_key} is globally unique; this one already names another step. */
    public static final class IdempotencyKeyReused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IdempotencyKeyReused(String message) {
            super(message);
        }
    }

    /** Obligation (q): intents exist only for requests that are still open. */
    public static final class TerminalRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TerminalRequest(String message) {
            super(message);
        }
    }
}
```

`AttemptPolicy.java` and `AttemptService.java`:

```java
package com.solace.sm.dp.apply.engine;

/**
 * §2.2 rule 4's bounded retry, as configuration.
 *
 * <p>Obligation (m) splits the enforcement deliberately: {@code attempt_no_positive} is the database's
 * lower bound (and {@code smallint} a last-ditch backstop at 32767), while the CAP is the engine's,
 * because it is configuration the database cannot know.
 *
 * @param maxAttempts at least 1
 */
public record AttemptPolicy(int maxAttempts) {

    public AttemptPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "a step must be allowed at least one attempt; got " + maxAttempts);
        }
    }
}
```

```java
package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.apply.persistence.AttemptRepository;
import com.solace.sm.dp.apply.persistence.Constraints;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §2.2 rule 0 — write before act — and rule 4's cap, in the two methods that bracket every adapter
 * call.
 *
 * <p>{@link #open} COMMITS the attempt row and only then may the caller touch the adapter. That
 * ordering is architecture.md §16.1's verbatim obligation, and it is what makes resume readable:
 * an intent with no attempt row provably issued nothing.
 *
 * <p>The cap is checked against {@link AttemptRepository#count} in the same transaction as the insert
 * — which at READ COMMITTED does NOT stop two concurrent opens from both reading {@code count == n}
 * (measured). What does is {@code UNIQUE (intent_id, attempt_no)}: exactly one of them lands attempt
 * {@code n + 1}, the other's insert is refused with 23505 on {@code apply_attempt_intent_id_attempt_no_key},
 * and {@link #open} reports that as {@link ConcurrentAttempt} so a caller can tell "someone else is
 * running this try — do not act" from a refused cap. No attempt number is ever duplicated and the cap
 * is never breached; the count is the fast path, the index is the guarantee.
 *
 * <p>Both methods run in a transaction of their own ({@link EngineTransactions}) — rule 0 is void if
 * the attempt row can roll back with a caller's transaction after {@code open()} has returned.
 */
public final class AttemptService {

    private final TransactionTemplate tx;
    private final AttemptRepository attempts;
    private final AttemptPolicy policy;
    private final Clock clock;

    public AttemptService(TransactionTemplate tx, AttemptRepository attempts, AttemptPolicy policy,
            Clock clock) {
        this.tx = EngineTransactions.requiresNew(tx);
        this.attempts = attempts;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Journals the try that is about to run.
     *
     * @param preBaseline the process/container start baseline — LIFECYCLE only
     *                    ({@code baseline_only_for_lifecycle}); process metadata, never config
     * @param stepBudget  how long this try may take; becomes {@code step_deadline}
     * @throws RetryCapExceeded  when the configured cap is already spent — obligation (m)
     * @throws ConcurrentAttempt when another opener landed this attempt number first (the
     *                           {@code (intent_id, attempt_no)} UNIQUE refused this one)
     */
    public AttemptRow open(TenantId t, ApplyId id, StepIntentRow intent, PreBaseline preBaseline,
            Duration stepBudget) {
        AttemptRow opened = tx.execute(status -> {
            int spent = attempts.count(t, intent.intentId());
            if (spent >= policy.maxAttempts()) {
                throw new RetryCapExceeded("intent " + intent.intentId() + " has spent all "
                        + policy.maxAttempts() + " attempts; the exhaustion path is "
                        + "rollbackOrMarkIndeterminate, not another try");
            }
            Instant now = clock.instant();
            AttemptRow row = new AttemptRow(Uuid7.next(), intent.intentId(), intent.step(),
                    (short) (spent + 1), preBaseline, now, null, now.plus(stepBudget),
                    null, null, null, null);
            try {
                attempts.open(t, id, row);
                return row;
            } catch (PgConstraintViolation e) {
                if (e.namesAnyOf(Constraints.ATTEMPT_NO_UNIQUE)) {
                    status.setRollbackOnly();   // the transaction is aborted by the 23505 anyway
                    return null;
                }
                throw e;
            }
        });
        if (opened == null) {
            throw new ConcurrentAttempt("another opener landed the next attempt of intent "
                    + intent.intentId() + " first; do not act — resume will read its row");
        }
        return opened;
    }

    /**
     * Records what the try did. Fails loudly if the attempt id names no row of this tenant.
     *
     * <p>Both evidence arguments are TYPES ({@code I6}): there is no signature here that accepts a raw
     * error string or a hand-built jsonb document, so a caller cannot hand the journal a message it
     * caught from an adapter.
     */
    public void close(TenantId t, UUID attemptId, AttemptOutcome o, String observedHash,
            ErrorClass errorClass, RuntimeRef runtimeRef) {
        tx.executeWithoutResult(status -> {
            int updated = attempts.close(t, attemptId, o, observedHash, errorClass, runtimeRef,
                    clock.instant());
            if (updated != 1) {
                throw new IllegalStateException(
                        "closing attempt " + attemptId + " updated " + updated + " rows");
            }
        });
    }

    /** A concurrent {@code open()} for the same intent won the {@code (intent_id, attempt_no)} race. */
    public static final class ConcurrentAttempt extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConcurrentAttempt(String message) {
            super(message);
        }
    }

    /** Obligation (m)'s engine half: the cap is configuration, so the database cannot hold it. */
    public static final class RetryCapExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RetryCapExceeded(String message) {
            super(message);
        }
    }
}
```

Append to `ApplyEngineConfiguration` (imports: `com.solace.sm.dp.apply.persistence.AttemptRepository`, `com.solace.sm.dp.apply.persistence.SmDataProperties`, `com.solace.sm.dp.apply.persistence.StepIntentRepository`):

```java
    @Bean
    StepIntentService stepIntentService(TransactionTemplate smDataTransactions,
            ApplyRequestRepository applyRequestRepository, StepIntentRepository stepIntentRepository,
            Clock smDataClock) {
        return new StepIntentService(smDataTransactions, applyRequestRepository,
                stepIntentRepository, smDataClock);
    }

    /** {@code sm.data.apply.max-attempts}, default 3 — obligation (m)'s cap. */
    @Bean
    AttemptPolicy attemptPolicy(SmDataProperties props) {
        return new AttemptPolicy(props.apply().maxAttempts());
    }

    @Bean
    AttemptService attemptService(TransactionTemplate smDataTransactions,
            AttemptRepository attemptRepository, AttemptPolicy attemptPolicy, Clock smDataClock) {
        return new AttemptService(smDataTransactions, attemptRepository, attemptPolicy,
                smDataClock);
    }
```

Run the Step 1 command. Expected: `StepIntentIT` **12** green; console `MEASURED: a duplicate violating BOTH intent UNIQUEs is reported as apply_step_intent_idempotency_key_key` and `MEASURED: arbiter race re-find path executed 1 time(s) for 2 concurrent ensureIntent calls` (measured 2026-09-07; if the re-find count is 0 the two threads did not collide and the test correctly fails — re-run rather than weaken it).
- [ ] **Step 3: `AttemptIT` — obligations (i), (m), rule 0, the compaction pair**

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations (i) and (m), plus §2.2 rule 0's ordering and §3.3's compaction pair.
 *
 * <p>Obligation (m) is deliberately split across two enforcers and both halves are here: the DATABASE
 * owns the lower bound ({@code attempt_no_positive}) and the ENGINE owns the configured cap, "an
 * application-level prove-it-fires, because the cap is configuration the database cannot know".
 */
@Testcontainers(disabledWithoutDocker = true)
class AttemptIT {

    private static final String HASH = "sha256:3333333333333333333333333333333333333333";
    private static final Duration BUDGET = Duration.ofMinutes(2);
    private static final PreBaseline BASELINE =
            new PreBaseline(Instant.parse("2026-09-01T00:00:00Z"), "cache-engine-1");

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static AttemptRepository attempts;
    private static StepIntentRepository intents;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        attempts = ApplyEngineHarness.attempts();
        intents = ApplyEngineHarness.intents();
    }

    // -------------------------------------------------------------------------------------------
    // Rule 0: the row exists, committed, BEFORE anything could have been issued.
    // -------------------------------------------------------------------------------------------

    @Test
    void openCommitsTheAttemptRowBeforeReturningSoNoAdapterCanRunAheadOfItsJournalEntry() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);

        AttemptRow row = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        assertEquals((short) 1, row.attemptNo());
        assertNull(row.outcome(), "an open attempt has no outcome yet — that IS the ordering rule");
        assertNull(row.endedAt());
        assertNotNull(row.stepDeadline());
        assertTrue(visibleOnAnotherConnection(row.attemptId()),
                "the row must be COMMITTED when open() returns: a different connection sees it. "
                        + "Anything weaker and 'an intent with no attempt row provably issued "
                        + "nothing' stops being provable");
    }

    @Test
    void closeRecordsTheOutcomeAndLatestAndCountSeeIt() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);

        AttemptRow first = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        service.close(f.tenant, first.attemptId(), AttemptOutcome.FAILED, null,
                new ErrorClass("io.timeout"), null);
        AttemptRow second = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        service.close(f.tenant, second.attemptId(), AttemptOutcome.SUCCEEDED, HASH, null,
                RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "abc123"));

        assertEquals(2, attempts.count(f.tenant, f.intent.intentId()));
        AttemptRow latest = attempts.latest(f.tenant, f.intent.intentId()).orElseThrow();
        assertEquals(second.attemptId(), latest.attemptId());
        assertEquals((short) 2, latest.attemptNo());
        assertEquals(AttemptOutcome.SUCCEEDED, latest.outcome());
        assertEquals(HASH, latest.observedHash());
        assertNotNull(latest.endedAt());
        assertNull(latest.errorClass());
        assertEquals(RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "abc123"), latest.runtimeRef(),
                "the typed runtime ref round-trips through the jsonb column unchanged");
    }

    // -------------------------------------------------------------------------------------------
    // (m): the engine's cap, and the database's floor.
    // -------------------------------------------------------------------------------------------

    @Test
    void theFourthOpenUnderACapOfThreeIsRefusedByTheEngineAndWritesNoRow() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        for (int i = 0; i < 3; i++) {
            service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        }
        assertEquals(3, attempts.count(f.tenant, f.intent.intentId()));

        AttemptService.RetryCapExceeded e = assertThrows(AttemptService.RetryCapExceeded.class,
                () -> service.open(f.tenant, f.apply, f.intent, null, BUDGET));
        assertTrue(e.getMessage().contains("rollbackOrMarkIndeterminate"), e.getMessage());
        assertEquals(3, attempts.count(f.tenant, f.intent.intentId()),
                "a refused open must leave no row — otherwise the cap leaks one attempt per bounce");
    }

    /**
     * Two concurrent {@code open()}s for one intent — measured: at READ COMMITTED both read the same
     * count, so the count is not what stops a duplicate attempt number; {@code UNIQUE (intent_id,
     * attempt_no)} is, and the loser must learn it as {@link AttemptService.ConcurrentAttempt}, not as
     * a raw constraint violation a step loop cannot classify.
     */
    @Test
    void twoConcurrentOpensForOneIntentLandExactlyOneAttemptAndTheLoserIsToldSo() throws Exception {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(20, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        return service.open(f.tenant, f.apply, f.intent, null, BUDGET);
                    } catch (AttemptService.ConcurrentAttempt e) {
                        return e;
                    }
                }));
            }
            Object first = futures.get(0).get(60, java.util.concurrent.TimeUnit.SECONDS);
            Object second = futures.get(1).get(60, java.util.concurrent.TimeUnit.SECONDS);

            long rows = java.util.stream.Stream.of(first, second)
                    .filter(o -> o instanceof AttemptRow).count();
            long losers = java.util.stream.Stream.of(first, second)
                    .filter(o -> o instanceof AttemptService.ConcurrentAttempt).count();
            System.out.println("MEASURED: concurrent open — " + rows + " row(s) opened, " + losers
                    + " ConcurrentAttempt; attempts on the intent = "
                    + attempts.count(f.tenant, f.intent.intentId()));
            assertEquals(1, attempts.count(f.tenant, f.intent.intentId()),
                    "exactly one attempt row — the two opens must have collided. If they serialized "
                            + "instead (two rows, attempt_no 1 and 2) this test proves nothing about "
                            + "the race and fails CORRECTLY: re-run rather than weaken it (measured "
                            + "colliding on every prototype run)");
            assertEquals(1, rows, "one winner. Got: " + first + " / " + second);
            assertEquals(1, losers, "one loser told ConcurrentAttempt — never a raw "
                    + "PgConstraintViolation. Got: " + first + " / " + second);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aNonPositiveAttemptNumberIsRefusedByAttemptNoPositive() {
        Fixture f = new Fixture();
        for (short bad : new short[] {0, -1}) {
            Refusals.assertViolation(Refusals.CHECK_VIOLATION, "attempt_no_positive",
                    () -> attempts.open(f.tenant, f.apply, f.attemptRow(bad, Step.MATERIALIZE,
                            null)));
        }
        // The neighbouring positive control: attempt_no 1 is accepted, so the refusals above are
        // the CHECK and not a malformed row.
        attempts.open(f.tenant, f.apply, f.attemptRow((short) 1, Step.MATERIALIZE, null));
        assertEquals(1, attempts.count(f.tenant, f.intent.intentId()));
    }

    // -------------------------------------------------------------------------------------------
    // (i): the triple FK — an attempt may not borrow another request's intent.
    // -------------------------------------------------------------------------------------------

    @Test
    void anAttemptReferencingAnotherRequestsIntentIsRefusedByTheTripleForeignKey() {
        Fixture a = new Fixture();
        Fixture b = a.sibling();

        // Same tenant, same step (so the (intent_id, step) FK is satisfied), wrong apply_id.
        AttemptRow borrowed = new AttemptRow(Uuid7.next(), b.intent.intentId(), Step.MATERIALIZE,
                (short) 1, null, Instant.now(), null, Instant.now().plusSeconds(60), null, null,
                null, null);
        PgConstraintViolation v = Refusals.assertViolation(Refusals.FOREIGN_KEY_VIOLATION,
                "apply_attempt_tenant_id_apply_id_intent_id_fkey",
                () -> attempts.open(a.tenant, a.apply, borrowed));
        assertEquals("apply_attempt", v.table());

        // Positive control: the identical row under its OWN request is accepted.
        attempts.open(b.tenant, b.apply, borrowed);
        assertEquals(1, attempts.count(b.tenant, b.intent.intentId()));
    }

    // -------------------------------------------------------------------------------------------
    // The two per-step / per-time CHECKs.
    // -------------------------------------------------------------------------------------------

    /**
     * A refused row must not travel out through the exception — not its message, and not its cause
     * chain either. PostgreSQL's error carries {@code DETAIL: Failing row contains (…)}, the whole
     * rejected row, jsonb included. {@code PgConstraintViolation} once embedded that text in its own
     * message; then it carried the driver exception as its CAUSE, which is the same leak one hop
     * later, because ordinary application logging is {@code log.error("persistence failure", ex)} and
     * Logback prints the cause chain (both measured in plan 004's reviews). So the assertion here is
     * made where the leak would actually happen — on what a logger EMITS for the exception — and not
     * on {@code getMessage()} alone.
     */
    @Test
    void aRefusedRowsContentNeverReachesALoggerThroughThePgConstraintViolation() {
        Fixture f = new Fixture();
        // Identifier-shaped, so PreBaseline accepts it — and SHORT, because PostgreSQL truncates each
        // value in `DETAIL: Failing row contains (…)` at 64 characters (measured: a 15-char sentinel
        // after the 52-char jsonb prefix came back as "SENTINELROW..."), which would make the
        // precondition below fail for the wrong reason.
        String sentinel = "SNTL7c2e";
        PreBaseline baseline = new PreBaseline(Instant.parse("2026-09-01T00:00:00Z"), sentinel);
        AttemptRow row = f.attemptRow((short) 1, Step.MATERIALIZE, baseline);
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AttemptIT.class);

        // Precondition, through the SAME insert issued raw: PostgreSQL's own exception, logged the
        // ordinary way, DOES echo the sentinel — otherwise the assertion below is vacuous.
        LogCapture.Captured<Throwable> raw = LogCapture.during(() -> {
            Throwable t = assertThrows(Throwable.class, () -> ApplyEngineHarness.jdbc().update(
                    "INSERT INTO sm_data.apply_attempt (attempt_id, tenant_id, apply_id, intent_id,"
                            + " step, attempt_no, pre_baseline, started_at, step_deadline)"
                            + " VALUES (:attempt, :t, :a, :intent, :step, :no,"
                            + " CAST(:baseline AS jsonb), :started, :deadline)",
                    new MapSqlParameterSource()
                            .addValue("attempt", row.attemptId())
                            .addValue("t", f.tenant.value())
                            .addValue("a", f.apply.value())
                            .addValue("intent", row.intentId())
                            .addValue("step", row.step().name())
                            .addValue("no", row.attemptNo())
                            .addValue("baseline", Evidence.write(baseline))
                            .addValue("started", Sql.at(row.startedAt()))
                            .addValue("deadline", Sql.at(row.stepDeadline()))));
            log.error("persistence failure", t);
            return t;
        });
        assertEquals(Refusals.CHECK_VIOLATION, Refusals.unwrap(raw.value()).getSQLState(),
                "the raw probe must be refused for the reason under test");
        String rawLine = raw.firstContaining("persistence failure");
        assertNotNull(rawLine, "the ERROR line must have been captured");
        assertTrue(rawLine.contains(sentinel) && rawLine.contains("Failing row"),
                "precondition: a logged driver exception DOES echo the failing row. Line was: " + rawLine);

        // The repository path: the reshaped exception, logged the same way.
        LogCapture.Captured<PgConstraintViolation> reshaped = LogCapture.during(() -> {
            PgConstraintViolation v = Refusals.assertViolation(Refusals.CHECK_VIOLATION,
                    "baseline_only_for_lifecycle", () -> attempts.open(f.tenant, f.apply, row));
            log.error("persistence failure", v);
            return v;
        });
        PgConstraintViolation v = reshaped.value();
        String reshapedLine = reshaped.firstContaining("persistence failure");
        System.out.println("MEASURED: reshaped refusal logged as → " + reshapedLine);

        assertNull(v.getCause(), "no driver exception may be attached — Logback would print it");
        assertNotNull(reshapedLine);
        assertFalse(reshapedLine.contains(sentinel),
                "the rejected row reached a logger through the reshaped exception. Line was: "
                        + reshapedLine);
        assertFalse(reshapedLine.contains("Failing row"),
                "…and PostgreSQL's DETAIL line must not appear at all. Line was: " + reshapedLine);
        assertFalse(reshaped.text().contains("Failing row"),
                "…on ANY captured line, not only the one this test logged. Log was: " + reshaped.text());
        assertTrue(v.getMessage().contains("baseline_only_for_lifecycle")
                        && v.getMessage().contains("apply_attempt"),
                "…while still naming what refused and where. Was: " + v.getMessage());

        // A second surface this measurement exposed, and closed structurally: the capture runs the
        // root logger at TRACE, and at TRACE Spring's StatementCreatorUtils prints EVERY bind value —
        // "Setting SQL statement parameter value: column index 7, parameter value [{…}]". The RAW
        // probe above bound its jsonb as a plain String, so its TRACE line carries the sentinel — the
        // precondition. The repository binds every jsonb document through Sql.redacted, a SqlValue
        // whose toString is a length, so the same TRACE line for the repository path shows the wrapper
        // and never the document. This does not depend on a logger level anyone can change.
        String rawBind = raw.firstContaining("parameter value [{");
        assertNotNull(rawBind, "Spring JDBC no longer logs bind values at TRACE? Re-measure and "
                + "rewrite this comment rather than deleting it");
        assertTrue(rawBind.startsWith("TRACE org.springframework.jdbc.core.StatementCreatorUtils")
                        && rawBind.contains(sentinel),
                "precondition: a PLAIN String bind is printed at TRACE, sentinel included. Was: "
                        + rawBind);
        String redactedBind = reshaped.firstContaining("parameter value [[redacted text");
        System.out.println("MEASURED: repository bind at TRACE → " + redactedBind);
        assertNotNull(redactedBind, "the repository must bind jsonb through Sql.redacted. Log was: "
                + reshaped.text());
        assertFalse(reshaped.text().contains(sentinel),
                "the document reached a logger through SOME captured line. Log was: " + reshaped.text());
    }

    @Test
    void aPreBaselineOnANonLifecycleStepIsRefusedByBaselineOnlyForLifecycle() {
        Fixture f = new Fixture();
        Refusals.assertViolation(Refusals.CHECK_VIOLATION, "baseline_only_for_lifecycle",
                () -> attempts.open(f.tenant, f.apply, f.attemptRow((short) 1, Step.MATERIALIZE,
                        BASELINE)));

        // …and the positive control on the one step that may carry it.
        Fixture lifecycle = f.sibling();
        StepIntentRow intent = lifecycle.lifecycleIntent();
        attempts.open(lifecycle.tenant, lifecycle.apply, new AttemptRow(Uuid7.next(),
                intent.intentId(), Step.LIFECYCLE, (short) 1, BASELINE, Instant.now(), null,
                Instant.now().plusSeconds(60), null, null, null, null));
        assertEquals(1, attempts.count(lifecycle.tenant, intent.intentId()));
        assertEquals(BASELINE,
                attempts.latest(lifecycle.tenant, intent.intentId()).orElseThrow().preBaseline(),
                "the typed baseline round-trips: resume compares against THIS, so a lossy "
                        + "serialization would make the LIFECYCLE case undecidable");
    }

    @Test
    void anEndTimeBeforeTheStartTimeIsRefusedByAttemptTimeOrdered() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        Refusals.assertViolation(Refusals.CHECK_VIOLATION, "attempt_time_ordered",
                () -> attempts.close(f.tenant, open.attemptId(), AttemptOutcome.SUCCEEDED, HASH,
                        null, null, open.startedAt().minusSeconds(60)));

        assertEquals(1, attempts.close(f.tenant, open.attemptId(), AttemptOutcome.SUCCEEDED, HASH,
                        null, null, open.startedAt().plusSeconds(1)),
                "the same close with a sane end time is accepted");
    }

    @Test
    void anUnknownOutcomeCannotBeWrittenBecauseTheModelIsAnEnumAndTheColumnHasOutcomeKnown() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "outcome_known",
                () -> ApplyEngineHarness.jdbc().update(
                        "UPDATE sm_data.apply_attempt SET outcome = 'MAYBE', ended_at = now()"
                                + " WHERE tenant_id = :t AND attempt_id = :a",
                        new MapSqlParameterSource().addValue("t", f.tenant.value())
                                .addValue("a", open.attemptId())));
    }

    // -------------------------------------------------------------------------------------------
    // §3.3's compaction: fold, then delete. Both halves, in the order the design gives them.
    // -------------------------------------------------------------------------------------------

    @Test
    void theCompactionPairFoldsTheSummaryOntoATerminalRequestAndDeletesItsAttempts() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow one = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        service.close(f.tenant, one.attemptId(), AttemptOutcome.FAILED, null,
                new ErrorClass("io.timeout"), null);
        AttemptRow two = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        service.close(f.tenant, two.attemptId(), AttemptOutcome.SUCCEEDED, HASH, null, null);

        outcomes.start(f.tenant, f.apply);
        outcomes.land(f.tenant, f.apply, RequestState.APPLIED);

        String summary = "{\"MATERIALIZE\": {\"attempts\": 2, \"lastError\": \"io.timeout\"}}";
        assertEquals(1, ApplyEngineHarness.requests()
                .foldAttemptSummary(f.tenant, f.apply, summary));
        assertEquals(2, attempts.deleteForRequest(f.tenant, f.apply));
        assertEquals(0, attempts.count(f.tenant, f.intent.intentId()),
                "the request keeps its explanatory power in attempt_summary after its attempts are "
                        + "gone — that is why the fold precedes the delete");
        assertEquals(1, intents.forRequest(f.tenant, f.apply).size(),
                "intents live as long as their request: they are its identity, not its diagnostics");
    }

    // -------------------------------------------------------------------------------------------
    // I6: the evidence columns, and what the TYPES refuse before any SQL is built.
    // -------------------------------------------------------------------------------------------

    /**
     * The external review's finding 2, as a live check — and stated at the strength it actually has.
     *
     * <p>The old signatures took {@code String errorClass} and {@code String runtimeRefJson}, so what
     * these columns may hold was a rule about callers. It is now a rule about the type system, but a
     * rule about SHAPE: {@code token=SECRET-VALUE}, a {@code Bearer} header and a JSON blob are refused
     * because the grammars have no way to express {@code =}, a space, a quote or a brace, and the
     * journal row is provably untouched afterwards.
     *
     * <p><b>A bare compact token is accepted by shape</b>, and the last assertion here measures that
     * rather than leaving it to a javadoc. Keeping a secret out of the VALUE is the ADAPTER's
     * obligation: each adapter's {@link RuntimeRef} builder is the only writer of this column and must
     * be tested against sentinels drawn from its own inputs — docker inspect output, the process
     * environment, mounted files. That is the adapter contract test Plan 005 carries, and this test
     * exists partly to point at it.
     */
    @Test
    void aSecretShapedValueIsRefusedByTheTypeBeforeAnySqlAndNothingIsWritten() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> new ErrorClass("token=SECRET-VALUE"));
        assertTrue(err.getMessage().contains("[a-z]"), err.getMessage());
        IllegalArgumentException ref = assertThrows(IllegalArgumentException.class,
                () -> RuntimeRef.of(RuntimeRef.Key.HOST, "PASSWORD=hunter2"));
        assertTrue(ref.getMessage().contains("[A-Za-z0-9._:/@+-]"), ref.getMessage());
        IllegalArgumentException header = assertThrows(IllegalArgumentException.class,
                () -> RuntimeRef.of(RuntimeRef.Key.HOST,
                        "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc"));
        assertTrue(header.getMessage().contains("[A-Za-z0-9._:/@+-]"), header.getMessage());
        IllegalArgumentException blob = assertThrows(IllegalArgumentException.class,
                () -> RuntimeRef.of(RuntimeRef.Key.HOST, "{\"password\":\"hunter2\"}"));
        assertTrue(blob.getMessage().contains("[A-Za-z0-9._:/@+-]"), blob.getMessage());
        assertFalse(err.getMessage().contains("SECRET-VALUE"),
                "the refusal must not echo the value — otherwise the type moved the secret from the "
                        + "database into the log. Was: " + err.getMessage());
        assertFalse(ref.getMessage().contains("hunter2"), ref.getMessage());
        assertFalse(blob.getMessage().contains("hunter2"), blob.getMessage());

        AttemptRow still = attempts.latest(f.tenant, f.intent.intentId()).orElseThrow();
        assertEquals(open.attemptId(), still.attemptId());
        assertNull(still.outcome(), "the attempt is still OPEN — nothing was written");
        assertNull(still.runtimeRef(), "and runtime_ref is still NULL");
        assertNull(runtimeRefTextOf(open.attemptId()),
                "read raw, past the mapper: the column itself holds nothing");

        // The honest half, measured: what the SHAPE control does not catch. A compact JWT is
        // base64url and dots, so every character is a legal adapter identifier.
        RuntimeRef compact = assertDoesNotThrow(() -> RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID,
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.abc"));
        System.out.println("MEASURED: a bare compact token is ACCEPTED by shape — "
                + compact.fields().get(RuntimeRef.Key.CONTAINER_ID)
                + " — so the content control is the adapter's contract test, not this type");
    }

    @Test
    void aLegalClassificationAndRuntimeRefRoundTripThroughTheJournal() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        RuntimeRef ref = RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "9f2c1e4b7a30",
                RuntimeRef.Key.UNIT_NAME, "solace-cache.service");
        service.close(f.tenant, open.attemptId(), AttemptOutcome.FAILED, null,
                new ErrorClass("docker.api.unreachable"), ref);

        AttemptRow latest = attempts.latest(f.tenant, f.intent.intentId()).orElseThrow();
        assertEquals(new ErrorClass("docker.api.unreachable"), latest.errorClass());
        assertEquals(ref, latest.runtimeRef());
        // Read raw: the stored document is the map and NOTHING else. Key ORDER is not asserted —
        // PostgreSQL's jsonb normalises it (shortest key first), so the insertion order RuntimeRef
        // keeps is a property of the Java value and of Evidence's output, not of the column.
        String stored = runtimeRefTextOf(open.attemptId());
        System.out.println("MEASURED: stored runtime_ref = " + stored);
        assertTrue(stored.contains("\"containerId\": \"9f2c1e4b7a30\""), stored);
        assertTrue(stored.contains("\"unitName\": \"solace-cache.service\""), stored);
        assertEquals(2, SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT count(*)::int FROM jsonb_object_keys((SELECT runtime_ref FROM"
                                + " sm_data.apply_attempt WHERE attempt_id = :a))",
                        new MapSqlParameterSource("a", open.attemptId()), Integer.class),
                "two keys, no smuggled third");
    }

    // -------------------------------------------------------------------------------------------

    /** The runtime_ref column as text, read past the row mapper. */
    private static String runtimeRefTextOf(UUID attemptId) {
        return SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT runtime_ref::text FROM sm_data.apply_attempt WHERE attempt_id = :a",
                new MapSqlParameterSource("a", attemptId), String.class);
    }

    private static boolean visibleOnAnotherConnection(UUID attemptId) {
        return Boolean.TRUE.equals(SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_attempt WHERE attempt_id = :a)",
                new MapSqlParameterSource("a", attemptId), Boolean.class));
    }

    /** A fresh tenant with one open apply and one MATERIALIZE intent on it. */
    private static final class Fixture {

        private final TenantId tenant;
        private final InstanceId instance;
        private final ApplyId apply;
        private final StepIntentRow intent;

        Fixture() {
            this(new TenantId(SmDataPostgres.freshTenant()));
        }

        Fixture(TenantId tenant) {
            this.tenant = tenant;
            this.instance = new InstanceId(UUID.randomUUID());
            ScopeKey wide = new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE);
            this.apply = assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(wide, 10))).applyId();
            this.intent = new StepIntentRow(Uuid7.next(), apply, Step.MATERIALIZE,
                    new ReplicaRef("cache-engine-1"), "idem-" + UUID.randomUUID(), HASH,
                    Instant.now());
            intents.insert(tenant, intent);
        }

        /** Another request under the SAME tenant — what obligation (i)'s cross-reference needs. */
        Fixture sibling() {
            return new Fixture(tenant);
        }

        StepIntentRow lifecycleIntent() {
            StepIntentRow row = new StepIntentRow(Uuid7.next(), apply, Step.LIFECYCLE,
                    new ReplicaRef("cache-engine-1"), "idem-" + UUID.randomUUID(), null,
                    Instant.now());
            intents.insert(tenant, row);
            return row;
        }

        AttemptRow attemptRow(short attemptNo, Step step, PreBaseline preBaseline) {
            return new AttemptRow(Uuid7.next(), intent.intentId(), step, attemptNo, preBaseline,
                    Instant.now(), null, Instant.now().plusSeconds(60), null, null, null, null);
        }
    }
}
```

A trap this class avoids, measured during the prototype: a naive (i) probe that reuses an `attempt_no` already present under the borrowed intent is refused by `apply_attempt_intent_id_attempt_no_key` **instead of** the triple FK and reads green while proving nothing about attribution — the sibling fixture's intent has no attempts yet, so `attempt_no = 1` is free and only the FK can refuse.

- [ ] **Step 4: `EngineTransactionBoundaryIT` — rule 0 survives a caller's transaction; `ResumeReadIT` — the in-flight read of design §3.1**

`EngineTransactionBoundaryIT.java` (the measured reason `EngineTransactions.requiresNew` exists):

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The engine's transactions are its own — a caller's enclosing transaction can neither swallow them
 * nor be poisoned by them.
 *
 * <p>Measured in plan 004's adversarial review with the default {@code PROPAGATION_REQUIRED}:
 * {@code AttemptService.open()} returned its row inside a caller's transaction and the row then rolled
 * back with the caller — §2.2 rule 0's "no attempt row provably means nothing was issued" silently
 * voided. The fix is {@code EngineTransactions.requiresNew}; these tests are what keep it there.
 */
@Testcontainers(disabledWithoutDocker = true)
class EngineTransactionBoundaryIT {

    private static final String HASH = "sha256:6666666666666666666666666666666666666666";

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
    }

    @Test
    void anAttemptOpenedInsideACallersTransactionSurvivesTheCallersRollback() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AtomicReference<AttemptRow> opened = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () -> ApplyEngineHarness.transactions()
                .executeWithoutResult(status -> {
                    opened.set(service.open(f.tenant, f.apply, f.intent, null, Duration.ofMinutes(1)));
                    // Rule 0: at this point the caller "may touch the adapter". The row must already
                    // be durable — whatever the caller's own transaction does next.
                    assertEquals(1, ApplyEngineHarness.attempts().count(f.tenant, f.intent.intentId()));
                    throw new IllegalStateException("the caller's own work failed");
                }));

        assertEquals(1, ApplyEngineHarness.attempts().count(f.tenant, f.intent.intentId()),
                "the attempt row must outlive the caller's rollback, or resume would read 'nothing "
                        + "was issued' for a step that was");
        assertEquals(opened.get().attemptId(),
                ApplyEngineHarness.attempts().latest(f.tenant, f.intent.intentId()).orElseThrow()
                        .attemptId());
    }

    @Test
    void anAdmissionInsideACallersTransactionIsCommittedOnItsOwnAndTheCallersRollbackIsClean() {
        TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        ScopeKey scope = new ScopeKey(tenant, new InstanceId(UUID.randomUUID()),
                PlacementId.INSTANCE_WIDE);
        AtomicReference<AdmissionOutcome> outcome = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () -> ApplyEngineHarness.transactions()
                .executeWithoutResult(status -> {
                    outcome.set(ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(scope, 1)));
                    throw new IllegalStateException("the caller's own work failed");
                }));

        ApplyId admitted = assertInstanceOf(AdmissionOutcome.Admitted.class, outcome.get()).applyId();
        assertEquals(RequestState.REQUESTED,
                ApplyEngineHarness.requests().find(tenant, admitted).orElseThrow().state(),
                "admission is the DP's audit record of the operator's decision; it does not roll back "
                        + "because a caller's unrelated work did");

        // …and a refusal inside a caller's transaction is returned as a VALUE while the enclosing
        // transaction commits normally (the block below would throw otherwise). Stated precisely:
        // SCOPE_BUSY never marks anything rollback-only; the one admit path that does —
        // FENCE_RACE_LOST — is unreachable through admit (see RefusalReason). The reachable
        // setRollbackOnly path is AttemptService.open's ConcurrentAttempt, exercised in AttemptIT.
        AtomicReference<AdmissionOutcome> refused = new AtomicReference<>();
        ApplyEngineHarness.transactions().executeWithoutResult(status ->
                refused.set(ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(scope, 1))));
        assertInstanceOf(AdmissionOutcome.Refused.class, refused.get(),
                "the second admission for the same scope is refused (SCOPE_BUSY) and the enclosing "
                        + "transaction has already committed by the time this line runs");
    }

    /** A fresh tenant with one open apply and one MATERIALIZE intent on it. */
    private static final class Fixture {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final ApplyId apply;
        private final StepIntentRow intent;

        Fixture() {
            ScopeKey wide = new ScopeKey(tenant, new InstanceId(UUID.randomUUID()),
                    PlacementId.INSTANCE_WIDE);
            apply = assertInstanceOf(AdmissionOutcome.Admitted.class,
                    ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(wide, 10))).applyId();
            intent = new StepIntentRow(Uuid7.next(), apply, Step.MATERIALIZE,
                    new ReplicaRef("cache-engine-1"), "idem-" + UUID.randomUUID(), HASH,
                    Instant.now());
            ApplyEngineHarness.intents().insert(tenant, intent);
        }
    }
}
```

`ResumeReadIT.java`:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The "in-flight state" read of design §3.1, end to end: "resume after a DP crash reads the
 * non-terminal {@code apply_request} rows, their {@code apply_step_intent} rows … and the latest
 * {@code apply_attempt} per intent".
 *
 * <p>The distinguishing case is the LAST assertion: an intent with no attempt row. Rule 0's
 * write-before-act ordering is what makes its absence mean "provably issued nothing" rather than
 * "unknown", so resume's whole decision table hangs on this read returning empty for that intent
 * instead of, say, an arbitrary row.
 */
@Testcontainers(disabledWithoutDocker = true)
class ResumeReadIT {

    private static final String HASH = "sha256:4444444444444444444444444444444444444444";

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static StepIntentService stepIntents;
    private static AttemptService attemptService;
    private static ApplyRequestRepository requests;
    private static StepIntentRepository intents;
    private static AttemptRepository attempts;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        stepIntents = ApplyEngineHarness.stepIntents();
        attemptService = ApplyEngineHarness.attemptsService(3);
        requests = ApplyEngineHarness.requests();
        intents = ApplyEngineHarness.intents();
        attempts = ApplyEngineHarness.attempts();
    }

    @Test
    void afterThreeAdmissionsAndOneLandingResumeSeesExactlyTheTwoOpenRequestsWithTheirEvidence() {
        TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        PlacementId p1 = new PlacementId(UUID.randomUUID());
        PlacementId p2 = new PlacementId(UUID.randomUUID());
        PlacementId p3 = new PlacementId(UUID.randomUUID());

        ApplyId running = admit(tenant, instance, p1, 10);
        ApplyId requested = admit(tenant, instance, p2, 10);
        ApplyId landed = admit(tenant, instance, p3, 10);

        // One request is mid-flight with a MATERIALIZE intent and one open attempt…
        outcomes.start(tenant, running);
        StepIntentRow materialize = stepIntents.ensureIntent(tenant, running, Step.MATERIALIZE,
                new ReplicaRef("cache-engine-1"), "idem-" + running.value(), HASH);
        AttemptRow attempt = attemptService.open(tenant, running, materialize, null,
                Duration.ofMinutes(2));

        // …one has an intent but NO attempt (rule 0: it provably issued nothing)…
        StepIntentRow preflight = stepIntents.ensureIntent(tenant, requested, Step.PREFLIGHT,
                ReplicaRef.PLACEMENT, "idem-" + requested.value(), null);

        // …and one is already terminal, so resume must not see it at all.
        outcomes.start(tenant, landed);
        outcomes.land(tenant, landed, RequestState.APPLIED);

        List<ApplyRequestRow> open = requests.nonTerminal(tenant);
        assertEquals(Set.of(running, requested),
                open.stream().map(ApplyRequestRow::applyId).collect(Collectors.toSet()),
                "exactly the non-terminal rows, and the APPLIED one excluded");
        assertEquals(2, open.size());
        assertTrue(open.stream().allMatch(r -> r.state().blocksScope()),
                "every row resume reads must be one the fences also consider in flight, or the two "
                        + "would disagree about what 'in flight' means");
        assertTrue(open.stream().allMatch(r -> r.terminalAt() == null),
                "terminal_has_timestamp, read back from the rows resume actually loads");

        // The intent half of the read.
        List<StepIntentRow> runningIntents = intents.forRequest(tenant, running);
        assertEquals(1, runningIntents.size());
        assertEquals(materialize.intentId(), runningIntents.get(0).intentId());
        assertEquals(HASH, runningIntents.get(0).configHash(),
                "the INTENDED hash is what a MATERIALIZE resume compares its read-back against");
        assertEquals(materialize.idempotencyKey(), runningIntents.get(0).idempotencyKey());

        // The attempt half: present and OPEN for the one that was issued…
        AttemptRow latest = attempts.latest(tenant, materialize.intentId()).orElseThrow();
        assertEquals(attempt.attemptId(), latest.attemptId());
        assertEquals((short) 1, latest.attemptNo());
        assertNull(latest.outcome(), "an attempt still open is what 'the DP died mid-step' looks like");
        assertNull(latest.endedAt());

        // …and provably absent for the one that was not.
        assertEquals(1, intents.forRequest(tenant, requested).size());
        Optional<AttemptRow> none = attempts.latest(tenant, preflight.intentId());
        assertTrue(none.isEmpty(),
                "no attempt row means nothing was issued — §2.2 rule 0 is what upgrades that from "
                        + "an absence to a PROOF, and this read is where resume consumes it");

        // And the terminal request's own rows are still there — retention, not resume.
        assertEquals(RequestState.APPLIED, requests.find(tenant, landed).orElseThrow().state());
    }

    private static ApplyId admit(TenantId t, InstanceId i, PlacementId p, long generation) {
        return assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(new ScopeKey(t, i, p), generation)))
                .applyId();
    }
}
```

- [ ] **Step 5: `ApplyPersistenceConfigurationIT` — the Spring-wired path against an EMPTY database, the one-shot migration step, and the readiness refusal**

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyEngineConfiguration;
import com.solace.sm.dp.apply.engine.AttemptPolicy;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link ApplyPersistenceConfiguration} + {@link ApplyEngineConfiguration} as Spring actually builds
 * them — and, since the external review's finding 1, specifically whether an application that CANNOT
 * migrate still refuses to serve a schema nobody has migrated.
 *
 * <p><b>Why a database of its own per test.</b> Every other IT shares one already-migrated database, so
 * a context that never checked anything would still come up green there. Each test below creates an
 * EMPTY database, gives it only what Phase 0 gives ({@code CREATE SCHEMA … AUTHORIZATION sm_dp_owner}
 * plus the {@code USAGE} grant), and then does or does not run {@link SmDataMigrationMain}. Per test
 * rather than per class so the three cases do not depend on JUnit's method order.
 *
 * <p>The three cases are the whole of the new posture: unmigrated ⇒ the context refuses to start and
 * says how to fix it; migrated ⇒ it comes up wired, and its bound properties contain no migrator
 * credential to leak; migrated twice ⇒ the one-shot step is idempotent, so a runbook that re-runs it is
 * safe.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyPersistenceConfigurationIT {

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();          // roles are CLUSTER-scoped; Phase 0 already made them
    }

    @Test
    void anUnmigratedDatabaseStopsTheContextStartingWithAMessageNamingTheMigrationStep() {
        String url = freshDatabase("sm_ctx_unmigrated");

        runner(url).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "a context over an UNMIGRATED sm_data must not start: the runtime "
                    + "role cannot create the tables, so coming up would mean serving a schema that "
                    + "does not exist");
            String chain = causeChain(failure);
            System.out.println("MEASURED: readiness refusal — " + chain);
            assertTrue(chain.contains("sm_data is not migrated to the shape this build expects "
                            + "(found 0 of 5 tables)"), chain);
            assertTrue(chain.contains("SmDataMigrationMain with the sm_dp_migrator credential"),
                    "the message must name the step that fixes it, or an operator is left guessing. "
                            + "Was: " + chain);
        });
    }

    /**
     * The SUCCESS path, and what the JVM logs while it runs.
     *
     * <p>The migration step controls its own two streams and prints no URL. It does not control
     * Flyway, which logs {@code Database: <url> (PostgreSQL …)} at INFO on every migrate — measured
     * below through a logback {@code ListAppender} on the root logger, and deliberately NOT suppressed:
     * a build that silenced Flyway would be hiding the evidence rather than fixing the exposure. That
     * line is the whole justification for {@link JdbcUrls}: since the URL will be logged, a URL may not
     * carry a credential.
     */
    @Test
    void afterTheOneShotMigrationStepTheContextComesUpWiredAndHoldsNoMigratorCredential() {
        String url = freshDatabase("sm_ctx_migrated");
        LogCapture.Captured<MigrateResult> migrated = LogCapture.during(
                () -> SmDataMigrationMain.run(url, "sm_dp_migrator",
                        SmDataPostgres.MIGRATOR_PASSWORD));
        MigrateResult result = migrated.value();
        assertEquals(6, result.migrationsExecuted,
                "V001..V006 must all have run — in a database that was EMPTY a moment ago");

        List<LogCapture.Line> namingTheUrl = migrated.lines().stream()
                .filter(l -> l.message().contains(url)).toList();
        System.out.println("MEASURED: " + migrated.lines().size() + " log events during the "
                + "migration; " + namingTheUrl.size() + " name the JDBC URL:");
        namingTheUrl.forEach(l -> System.out.println("MEASURED:   " + l));

        assertFalse(namingTheUrl.isEmpty(),
                "a library prints the connection URL. If that ever stops being true the assertion "
                        + "should be inverted deliberately, not deleted — JdbcUrls' whole reason for "
                        + "existing is that a URL handed to Flyway and Hikari WILL be logged");
        assertTrue(namingTheUrl.stream().anyMatch(l -> "INFO".equals(l.level())),
                "…and at INFO, i.e. at the level a production deployment actually runs, not only at "
                        + "DEBUG. Was: " + namingTheUrl);
        assertFalse(migrated.text().contains(SmDataPostgres.MIGRATOR_PASSWORD),
                "the PASSWORD is not in any of those lines, because it is not in the URL — which is "
                        + "the property JdbcUrls.requireLocation preserves");

        runner(url).run(context -> {
            assertNull(context.getStartupFailure(),
                    "context failed to start: " + context.getStartupFailure());

            NamedParameterJdbcTemplate jdbc = context.getBean(NamedParameterJdbcTemplate.class);
            assertEquals(0, jdbc.queryForObject("SELECT count(*)::int FROM sm_data.apply_request",
                            new MapSqlParameterSource(), Integer.class),
                    "the runtime pool reaches the migrated tables");

            for (Class<?> bean : List.of(AdmissionService.class, OutcomeService.class,
                    StepIntentService.class, AttemptService.class, ApplyScopeRepository.class,
                    ApplyRequestRepository.class, StepIntentRepository.class,
                    AttemptRepository.class)) {
                assertNotNull(context.getBean(bean), bean.getSimpleName() + " must be wired");
            }
            assertEquals(5, context.getBean(AttemptPolicy.class).maxAttempts(),
                    "sm.data.apply.max-attempts must bind through relaxed naming");

            // Finding 1's structural claim, asserted structurally: there is no migrator component to
            // read. A test that only checked "we did not set sm.data.migrator.*" would pass against a
            // record that still HAS the field, which is the thing being removed.
            List<String> components = Arrays.stream(
                            context.getBean(SmDataProperties.class).getClass().getRecordComponents())
                    .map(RecordComponent::getName).toList();
            System.out.println("MEASURED: SmDataProperties components = " + components);
            assertEquals(List.of("runtime", "apply"), components,
                    "the application binds the runtime credential and the engine knobs — a migrator "
                            + "component here is a schema-owner password inside the application JVM");

            ScopeKey scope = new ScopeKey(new TenantId(UUID.randomUUID()),
                    new InstanceId(UUID.randomUUID()), PlacementId.INSTANCE_WIDE);
            assertInstanceOf(AdmissionOutcome.Admitted.class,
                    context.getBean(AdmissionService.class)
                            .admit(ApplyEngineHarness.apply(scope, 1)),
                    "an admission through the CONTEXT's own beans — the transaction manager, the "
                            + "pool and the repositories all agreeing");
        });
    }

    @Test
    void runningTheOneShotMigrationStepASecondTimeExecutesNothing() {
        String url = freshDatabase("sm_ctx_idempotent");

        assertEquals(6, SmDataMigrationMain.run(url, "sm_dp_migrator",
                SmDataPostgres.MIGRATOR_PASSWORD).migrationsExecuted);
        MigrateResult again = SmDataMigrationMain.run(url, "sm_dp_migrator",
                SmDataPostgres.MIGRATOR_PASSWORD);
        System.out.println("MEASURED: second run of SmDataMigrationMain executed "
                + again.migrationsExecuted + " migrations, target " + again.targetSchemaVersion);
        assertEquals(0, again.migrationsExecuted,
                "a runbook that re-runs the step — an init container on a pod restart, an operator "
                        + "being careful — must be a no-op, not a second attempt at V001");
    }

    // -------------------------------------------------------------------------------------------

    private ApplicationContextRunner runner(String url) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ApplyPersistenceConfiguration.class,
                        ApplyEngineConfiguration.class)
                .withPropertyValues(
                        "sm.data.runtime.url=" + url,
                        "sm.data.runtime.username=sm_dp_runtime",
                        "sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD,
                        "sm.data.apply.max-attempts=5");
    }

    /** An empty database with Phase 0's schema half and deliberately NOT the migrations. */
    private static String freshDatabase(String database) {
        PostgreSQLContainer<?> container = SmDataPostgres.container();
        try (Connection admin = SmDataPostgres.superuser().getConnection();
                Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + database);
            s.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create " + database, e);
        }
        String url = "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
        try (Connection admin = SmDataPostgres.dataSource(url, container.getUsername(),
                        container.getPassword()).getConnection();
                Statement s = admin.createStatement()) {
            s.execute("CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner");
            s.execute("GRANT USAGE ON SCHEMA sm_data TO sm_dp_runtime");
        } catch (SQLException e) {
            throw new IllegalStateException("could not lay Phase 0's schema half in " + database, e);
        }
        return url;
    }

    /** Every message in the failure's cause chain — the readiness sentence is somewhere inside it. */
    private static String causeChain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            b.append(c).append(" | ");
        }
        return b.toString();
    }
}
```

- [ ] **Step 6: Verify, then two prove-it-fires mutations (both measured)**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify`
Expected: BUILD SUCCESS; failsafe `Tests run: 105, Skipped: 2` — Task 4's 74 + `StepIntentIT` 12 + `AttemptIT` 13 + `EngineTransactionBoundaryIT` 2 + `ResumeReadIT` 1 + `ApplyPersistenceConfigurationIT` 3. Console: the two `MEASURED:` lines from `StepIntentIT`; `MEASURED: readiness refusal — org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'smDataSchemaReadiness' … sm_data is not migrated to the shape this build expects (found 0 of 5 tables): run the migration step — SmDataMigrationMain with the sm_dp_migrator credential — before starting the application`; `MEASURED: SmDataProperties components = [runtime, apply]`; `MEASURED: second run of SmDataMigrationMain executed 0 migrations, target null`; `MEASURED: stored runtime_ref = {"unitName": "solace-cache.service", "containerId": "9f2c1e4b7a30"}` (PostgreSQL `jsonb` reorders keys — shortest first — so no test asserts stored key order); `MEASURED: a bare compact token is ACCEPTED by shape — eyJhbGciOi… — so the content control is the adapter's contract test, not this type` (the honesty line: `RuntimeRef` narrows the sink, it does not certify content). Console also carries `MEASURED: concurrent open — 1 row(s) opened, 1 ConcurrentAttempt; attempts on the intent = 1` (measured on every prototype run; if the two threads ever serialize instead of colliding the test fails **correctly** and should be re-run, like the arbiter-race test). Console: the two `MEASURED:` lines from `StepIntentIT`.

**Mutation 1 — obligation (q) disabled.** In `StepIntentService.ensureIntent`, change `if (request.state().isTerminal()) {` to `if (false && request.state().isTerminal()) {` and run
`… -Dit.test=StepIntentIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=true`.
Expected: exactly one red — `theServiceRefusesAnIntentOnATerminalRequestWhileTheRawInsertStillSucceeds`: `Expected com.solace.sm.dp.apply.engine.StepIntentService.TerminalRequest to be thrown, but nothing was thrown.` (the raw-insert half is unaffected — that is the proof the DB does not enforce (q)). Revert.

**Mutation 2 — the readiness gate.** The gate's own red is `anUnmigratedDatabaseStopsTheContextStartingWithAMessageNamingTheMigrationStep` — a context against a database the migration step never touched must fail with the readiness message; if it ever comes up, the check is gone. To see it fire the other way, temporarily change `EXPECTED_TABLES` to 4 and run `… -Dit.test=ApplyPersistenceConfigurationIT …`. Expected (measured): **two** reds — `afterTheOneShotMigrationStepTheContextComesUpWiredAndHoldsNoMigratorCredential` (`context failed to start: … found 5 of 4 tables`) and `anUnmigratedDatabaseStopsTheContextStartingWithAMessageNamingTheMigrationStep` (the refusal now says `found 0 of 4`, and the test asserts the exact `0 of 5` wording — a gate that changes its own contract must change its test with it); the idempotency test stays green. Revert; re-run; green.

- [ ] **Step 7: Commit**

```bash
git add backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ApplyEngineHarness.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/StepIntentIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/AttemptIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/EngineTransactionBoundaryIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ResumeReadIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ApplyPersistenceConfigurationIT.java
git commit -m "Add immutable step intents, the retry journal and the resume read; prove the Spring-wired graph migrates before it pools (Task 5)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

**Risks:** `EngineTransactionBoundaryIT` proves the REQUIRES_NEW posture for `open` and `admit`; `land`, `resolveIndeterminate` and `ensureIntent` share the same `EngineTransactions` template by construction and are not separately exercised under an enclosing transaction. The arbiter-race test asserts `arbiterReFinds() == 1`; on a machine where the two threads never collide the count is 0 and the test fails **correctly** — re-run rather than weaken it (it was 1 on every prototype run). `sm.data.apply.max-attempts` binds through Boot's relaxed naming to `Apply(Integer maxAttempts)`; the context IT pins that binding at 5.

---
### Task 6: The constraint-refusal sweep, the `I5` guard, and the coverage matrix (`dp-core`)

**Files:**
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/{SchemaConstraintsIT,TenantIsolationIT}.java`
- Modify: this plan's **Obligation coverage matrix** (below, in Task 7's documentation sweep the executor fills the "Measured" column)

**Interfaces:**
- Consumes: Task 1's harness, Task 2's `Refusals`, Task 3's model (`TenantId`, `InstanceId`), Task 4's `ApplyEngineHarness.{jdbc(), scopes()}`.
- Produces: nothing new in `main`; the last DB-enforced obligations — (h) in full and (j) — pinned by name, and the **failing guard for `I5`**: `TenantIsolationIT`, which goes red if any repository statement loses its `tenant_id` predicate (measured: dropping it from nine statements left the rest of the suite green).

**Preconditions:** Task 5 committed and green.

**Why hand-written SQL here:** the repositories and the model records refuse most of these shapes in Java before they can reach PostgreSQL (`ApplyCommand` mirrors six of the CHECKs), and a test that could not express an illegal row could not prove the database refuses one. This is the one class that deliberately bypasses both — through the runtime `NamedParameterJdbcTemplate`, with bound parameters, one violation per probe.

- [ ] **Step 1: Write the test — every probe starts from one fully legal row and spoils exactly one field**

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations (h) and (j): every named CHECK in design §3.1, refused by a row that violates IT and
 * nothing else.
 *
 * <p><b>One probe, one violation</b> — the harness rule the prototype learned the hard way, when an
 * {@code op_known} probe was refused by {@code apply_has_generation} instead. Every probe here starts
 * from {@link #legalRequest()}, a row that satisfies all eleven CHECKs simultaneously, and changes
 * exactly the field under test; the javadoc on {@link #legalRequest()} records which value answers
 * which constraint, so a future edit can see what it is about to break.
 *
 * <p>Hand-written SQL through the runtime template, deliberately: the repositories and the model
 * records refuse most of these shapes in Java before they can reach PostgreSQL, and a test that could
 * not express an illegal row could not prove the database refuses one.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaConstraintsIT {

    private static final String INSERT_REQUEST = "INSERT INTO sm_data.apply_request"
            + " (apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,"
            + "  outage_confirmed_by, requested_by, requested_at, state, terminal_at, resolved_by,"
            + "  resolution_outcome, resolution_observed)"
            + " VALUES (:apply_id, :tenant_id, :instance_id, :placement_id, :operation, :generation,"
            + " :strategy, :outage_confirmed_by, :requested_by, :requested_at, :state, :terminal_at,"
            + " :resolved_by, :resolution_outcome, CAST(:resolution_observed AS jsonb))";
    // resolution_note is deliberately not in the list: the runtime role holds no INSERT or UPDATE
    // privilege on it (I6 — v1 writes no free text), so naming it would turn every CHECK probe
    // here into a 42501. Its own refusal is PrivilegeTopologyIT's.

    /** Object, NUMBER stagedGeneration, NON-EMPTY replicas array — everything the CHECK demands. */
    private static final String GOOD_EVIDENCE =
            "{\"stagedGeneration\": 12, \"replicas\": [{\"ref\": \"r1\", \"restarted\": true}]}";

    private static NamedParameterJdbcTemplate runtime;
    private static UUID tenant;
    private static UUID instance;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = ApplyEngineHarness.jdbc();
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        ApplyEngineHarness.scopes().ensureScopeRow(new TenantId(tenant), new InstanceId(instance));
    }

    // -------------------------------------------------------------------------------------------
    // (h): the four the obligation names, plus the rest of apply_request's CHECK set.
    // -------------------------------------------------------------------------------------------

    @Test
    void aStateOutsideTheEnumIsRefusedByStateKnown() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "state_known",
                () -> insert(legalRequest()
                        .addValue("state", "ALMOST_DONE")
                        // …terminal_has_timestamp is an equality over the KNOWN open states, so an
                        // unknown state counts as terminal and must carry a stamp, or two CHECKs
                        // would fire and the probe would prove neither.
                        .addValue("terminal_at", OffsetDateTime.now())));
    }

    @Test
    void anOperationOutsideTheEnumIsRefusedByOpKnown() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "op_known",
                () -> insert(legalRequest()
                        .addValue("operation", "dance")
                        .addValue("generation", null)
                        .addValue("strategy", null)));
    }

    @Test
    void aStopThatClaimsToBeStagedIsRefusedByStateFitsOperation() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "state_fits_operation",
                () -> insert(legalRequest()
                        .addValue("operation", "stop")
                        .addValue("generation", null)
                        .addValue("strategy", null)
                        .addValue("state", "STAGED")
                        .addValue("terminal_at", OffsetDateTime.now())));
    }

    @Test
    void anApplyWithoutAGenerationIsRefusedByApplyHasGeneration() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_has_generation",
                () -> insert(legalRequest().addValue("generation", null)));
    }

    @Test
    void aRestartWithoutAStrategyIsRefusedByLifecycleHasStrategy() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "lifecycle_has_strategy",
                () -> insert(legalRequest()
                        .addValue("operation", "restart")
                        .addValue("generation", null)
                        .addValue("strategy", null)));
    }

    @Test
    void aManualRestartIsRefusedByRestartNotManual() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "restart_not_manual",
                () -> insert(legalRequest()
                        .addValue("operation", "restart")
                        .addValue("generation", null)
                        .addValue("strategy", "manual")));
    }

    @Test
    void aFullOutageRestartWithoutAConfirmationIsRefusedByFullOutageConfirmed() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "full_outage_confirmed",
                () -> insert(legalRequest().addValue("strategy", "restart-all")));

        insert(legalRequest()
                .addValue("strategy", "restart-all")
                .addValue("outage_confirmed_by", "duty-manager-3"));
    }

    @Test
    void aStrategyOutsideTheLadderIsRefusedByStrategyKnown() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "strategy_known",
                () -> insert(legalRequest().addValue("strategy", "yolo")));
    }

    @Test
    void aTerminalStateWithoutATimestampIsRefusedByTerminalHasTimestamp() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "terminal_has_timestamp",
                () -> insert(legalRequest().addValue("state", "APPLIED")));

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "terminal_has_timestamp",
                () -> insert(legalRequest().addValue("terminal_at", OffsetDateTime.now())),
                "…and the other direction: REQUESTED with a terminal stamp");
    }

    @Test
    void aNonPositiveGenerationIsRefusedByGenerationPositive() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "generation_positive",
                () -> insert(legalRequest().addValue("generation", 0L)));
    }

    // -------------------------------------------------------------------------------------------
    // (j): resolution_is_evidence, clause by clause, and resolution_only_when_resolved.
    // -------------------------------------------------------------------------------------------

    @Test
    void everyWorthlessResolutionIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("no resolver named", resolved().addValue("resolved_by", null));
        assertResolutionRefused("no outcome", resolved().addValue("resolution_outcome", null));
        assertResolutionRefused("no evidence at all",
                resolved().addValue("resolution_observed", null));
        assertResolutionRefused("an empty object",
                resolved().addValue("resolution_observed", "{}"));
        assertResolutionRefused("JSON null (typeof 'null', not 'object')",
                resolved().addValue("resolution_observed", "null"));
        assertResolutionRefused("present keys, worthless values",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": null, \"replicas\": []}"));
        assertResolutionRefused("a STRING stagedGeneration",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": \"12\", \"replicas\": [{\"ref\": \"r1\"}]}"));
        assertResolutionRefused("an EMPTY replicas array",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 12, \"replicas\": []}"));
        assertResolutionRefused("replicas that is not an array",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 12, \"replicas\": \"r1\"}"));
    }

    @Test
    void aFullyEvidencedResolutionInsertsThePositiveControl() {
        insert(resolved());
        assertEquals(1, runtime.queryForObject(
                "SELECT count(*)::int FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND state = 'INDETERMINATE_RESOLVED'",
                new MapSqlParameterSource("t", tenant), Integer.class),
                "the CHECK is evidence-SHAPED, not impossible to satisfy");
    }

    @Test
    void aResolutionFieldOnAnyOtherStateIsRefusedByResolutionOnlyWhenResolved() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "resolution_only_when_resolved",
                () -> insert(legalRequest()
                        .addValue("state", "RUNNING")
                        .addValue("resolved_by", "a resolver nobody has asked for yet")));
    }

    // -------------------------------------------------------------------------------------------
    // The watermark tables' own CHECKs.
    // -------------------------------------------------------------------------------------------

    @Test
    void theInstanceWideSentinelIsRefusedByRealPlacementsOnly() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "real_placements_only",
                () -> insertWatermark(new UUID(0L, 0L), 1L));

        insertWatermark(UUID.randomUUID(), 1L);
    }

    @Test
    void aZeroPlacementGenerationIsRefusedByPlacementWatermarkPositive() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "placement_watermark_positive",
                () -> insertWatermark(UUID.randomUUID(), 0L));
    }

    @Test
    void aNegativeInstanceWatermarkIsRefusedByInstanceWatermarkNonneg() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_watermark_nonneg",
                () -> runtime.update("INSERT INTO sm_data.apply_scope"
                                + " (tenant_id, instance_id, instance_watermark)"
                                + " VALUES (:t, :i, -1)",
                        new MapSqlParameterSource().addValue("t", tenant)
                                .addValue("i", UUID.randomUUID())));
    }

    // -------------------------------------------------------------------------------------------
    // The other two tables' enum CHECKs.
    // -------------------------------------------------------------------------------------------

    @Test
    void aStepOutsideTheEnumIsRefusedByStepKnown() {
        UUID applyId = insertOpenRequest();
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "step_known",
                () -> runtime.update("INSERT INTO sm_data.apply_step_intent"
                                + " (intent_id, tenant_id, apply_id, replica_ref, step,"
                                + "  idempotency_key, config_hash, created_at)"
                                + " VALUES (:id, :t, :a, 'r1', 'DANCE', :k, NULL, now())",
                        new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                                .addValue("t", tenant).addValue("a", applyId)
                                .addValue("k", "idem-" + UUID.randomUUID())));
    }

    @Test
    void anOutcomeOutsideTheEnumIsRefusedByOutcomeKnown() {
        UUID applyId = insertOpenRequest();
        UUID intentId = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.apply_step_intent"
                        + " (intent_id, tenant_id, apply_id, replica_ref, step, idempotency_key,"
                        + "  config_hash, created_at)"
                        + " VALUES (:id, :t, :a, 'r1', 'MATERIALIZE', :k, 'sha256:aaa', now())",
                new MapSqlParameterSource().addValue("id", intentId).addValue("t", tenant)
                        .addValue("a", applyId).addValue("k", "idem-" + UUID.randomUUID()));

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "outcome_known",
                () -> runtime.update("INSERT INTO sm_data.apply_attempt"
                                + " (attempt_id, tenant_id, apply_id, intent_id, step, attempt_no,"
                                + "  started_at, step_deadline, outcome)"
                                + " VALUES (:at, :t, :a, :i, 'MATERIALIZE', 1, now(),"
                                + " now() + interval '1 minute', 'MAYBE')",
                        new MapSqlParameterSource().addValue("at", UUID.randomUUID())
                                .addValue("t", tenant).addValue("a", applyId)
                                .addValue("i", intentId)));
    }

    // -------------------------------------------------------------------------------------------

    private static void insert(MapSqlParameterSource params) {
        runtime.update(INSERT_REQUEST, params);
    }

    private static void assertResolutionRefused(String what, MapSqlParameterSource params) {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "resolution_is_evidence",
                () -> insert(params), "resolution_is_evidence must refuse " + what);
    }

    /**
     * A row that satisfies EVERY CHECK on {@code apply_request} at once — the baseline each probe
     * spoils in exactly one place.
     *
     * <p>{@code operation = 'apply'} forces a generation ({@code apply_has_generation}) and a strategy
     * ({@code lifecycle_has_strategy}); {@code manual} is legal for an apply
     * ({@code restart_not_manual} fences only restarts) and dodges {@code full_outage_confirmed};
     * {@code REQUESTED} forces {@code terminal_at IS NULL} ({@code terminal_has_timestamp}) and
     * satisfies {@code state_fits_operation}; all-NULL resolution columns satisfy both resolution
     * CHECKs. A fresh {@code placement_id} per probe keeps {@code apply_inflight_fence} out of the
     * picture, so a CHECK failure is never mistaken for a fence collision.
     */
    private static MapSqlParameterSource legalRequest() {
        return new MapSqlParameterSource()
                .addValue("apply_id", UUID.randomUUID())
                .addValue("tenant_id", tenant)
                .addValue("instance_id", instance)
                .addValue("placement_id", UUID.randomUUID())
                .addValue("operation", "apply")
                .addValue("generation", 1L)
                .addValue("strategy", "manual")
                .addValue("outage_confirmed_by", null)
                .addValue("requested_by", "operator-1")
                .addValue("requested_at", OffsetDateTime.now())
                .addValue("state", "REQUESTED")
                .addValue("terminal_at", null)
                .addValue("resolved_by", null)
                .addValue("resolution_outcome", null)
                .addValue("resolution_observed", null);
    }

    /** The legal row walked all the way to a fully evidenced {@code INDETERMINATE_RESOLVED}. */
    private static MapSqlParameterSource resolved() {
        return legalRequest()
                .addValue("state", "INDETERMINATE_RESOLVED")
                .addValue("terminal_at", OffsetDateTime.now())
                .addValue("resolved_by", "duty-operator")
                .addValue("resolution_outcome", "applied")
                .addValue("resolution_observed", GOOD_EVIDENCE);
    }

    private static void insertWatermark(UUID placement, long generation) {
        runtime.update("INSERT INTO sm_data.placement_watermark"
                        + " (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("p", placement).addValue("g", generation));
    }

    /** A minimal open request the intent/attempt probes can hang off. */
    private static UUID insertOpenRequest() {
        MapSqlParameterSource params = legalRequest();
        insert(params);
        return (UUID) params.getValue("apply_id");
    }
}
```

- [ ] **Step 2: Run it — the red is intrinsic to each probe, not to the class**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test=SchemaConstraintsIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
Expected: **18** green. The refusals are the DDL's; this class has no `main` code to make red first. Its non-vacuity is proven per probe by the **name** assertion — a probe that fires the wrong CHECK fails (measured during the prototype: the `op_known` probe first failed with `apply_has_generation` until `generation`/`strategy` were nulled with it; the `state_known` probe first failed with `terminal_has_timestamp` until it carried a stamp). Each probe's setup comment records which second CHECK it had to dodge.

- [ ] **Step 3: `TenantIsolationIT` — `I5` as a failing guard**

Every fixture id elsewhere is a globally unique UUID, so `tenant_id` is redundant *in those tests* — this class is the one where tenant B addresses tenant A's rows by their primary keys and must see and change nothing:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ResolutionEvidence;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code I5}, as a FAILING guard: every repository statement must carry {@code tenant_id = :t}, and
 * this class is what turns red when one does not.
 *
 * <p>Why it is needed at all — measured in plan 004's adversarial review: with {@code tenant_id}
 * dropped from nine statements across all four repositories, the whole suite stayed green. Every id in
 * the fixtures is a globally unique UUID, so the tenant column was redundant <i>in the tests</i>, and
 * the one deliberately cross-tenant admission test survived only because {@code ScopeKey.overlaps}
 * re-checks the tenant in Java. A tenant filter that no test can miss is a tenant filter that will be
 * refactored away.
 *
 * <p>The shape of every probe here: tenant A owns a row; tenant B addresses that row BY ITS PRIMARY
 * KEY — the apply id, the intent id, the attempt id, the (instance, placement) pair — and must see
 * nothing and change nothing. Only the {@code tenant_id} predicate can make that true, because the key
 * alone would match.
 */
@Testcontainers(disabledWithoutDocker = true)
class TenantIsolationIT {

    private static final String HASH = "sha256:5555555555555555555555555555555555555555";

    private static TenantId a;
    private static TenantId b;
    private static InstanceId instance;
    private static PlacementId placement;
    private static ApplyId applyOfA;
    private static StepIntentRow intentOfA;
    private static AttemptRow attemptOfA;

    @BeforeAll
    static void tenantAOwnsARequestAnIntentAnAttemptAndBothWatermarks() {
        SmDataPostgres.bootstrap();
        a = new TenantId(SmDataPostgres.freshTenant());
        b = new TenantId(SmDataPostgres.freshTenant());
        instance = new InstanceId(UUID.randomUUID());
        placement = new PlacementId(UUID.randomUUID());

        ScopeKey scopeOfA = new ScopeKey(a, instance, placement);
        applyOfA = assertInstanceOf(AdmissionOutcome.Admitted.class,
                ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(scopeOfA, 10))).applyId();
        ApplyEngineHarness.outcomes().start(a, applyOfA);
        intentOfA = new StepIntentRow(Uuid7.next(), applyOfA, Step.MATERIALIZE,
                new ReplicaRef("cache-engine-1"), "idem-" + UUID.randomUUID(), HASH, Instant.now());
        ApplyEngineHarness.intents().insert(a, intentOfA);
        attemptOfA = new AttemptRow(Uuid7.next(), intentOfA.intentId(), Step.MATERIALIZE, (short) 1,
                null, Instant.now(), null, Instant.now().plusSeconds(60), null, null, null, null);
        ApplyEngineHarness.attempts().open(a, applyOfA, attemptOfA);
        ApplyEngineHarness.scopes().raiseInstanceWatermark(a, instance, 7);
        ApplyEngineHarness.scopes().raisePlacementWatermark(a, instance, placement, 9);

        // Tenant B has its own scope row for the SAME instance, so that B's reads have a row to hit
        // and the only thing distinguishing A's rows from B's is tenant_id.
        ApplyEngineHarness.scopes().ensureScopeRow(b, instance);
    }

    // --- ApplyRequestRepository ---------------------------------------------------------------

    @Test
    void tenantBCannotFindTenantAsRequestByItsId() {
        assertTrue(ApplyEngineHarness.requests().find(b, applyOfA).isEmpty());
        assertTrue(ApplyEngineHarness.requests().find(a, applyOfA).isPresent(),
                "positive control: the owner sees it");
    }

    @Test
    void tenantBSeesNoBlockingStagedOrStoppingRequestOnTheSharedInstance() {
        assertTrue(ApplyEngineHarness.requests().blockingRequests(b, instance).isEmpty(),
                "A's RUNNING apply must not fence B");
        assertEquals(1, ApplyEngineHarness.requests().blockingRequests(a, instance).size());
        assertFalse(ApplyEngineHarness.requests().stopInFlight(b, instance, placement));
        assertTrue(ApplyEngineHarness.requests().stagedRequest(b, instance, placement).isEmpty());
        assertTrue(ApplyEngineHarness.requests().nonTerminal(b).isEmpty(),
                "resume for B must not load A's in-flight request");
    }

    @Test
    void tenantBCannotWalkFoldOrResolveTenantAsRequest() {
        assertEquals(0, ApplyEngineHarness.requests().transition(b, applyOfA, RequestState.RUNNING,
                RequestState.APPLIED, Instant.now()));
        assertEquals(0, ApplyEngineHarness.requests().foldAttemptSummary(b, applyOfA, "{}"));
        assertEquals(0, ApplyEngineHarness.requests().resolveIndeterminate(b, applyOfA,
                new Resolution("b", ResolutionOutcome.APPLIED,
                        new ResolutionEvidence(10, List.of(new ReplicaObservation(
                                new ReplicaRef("cache-engine-1"), true, null)))),
                Instant.now()));
        assertEquals(RequestState.RUNNING,
                ApplyEngineHarness.requests().find(a, applyOfA).orElseThrow().state(),
                "A's row is exactly as A left it");
    }

    // --- StepIntentRepository -----------------------------------------------------------------

    @Test
    void tenantBCannotFindOrListTenantAsIntents() {
        assertTrue(ApplyEngineHarness.intents()
                .find(b, applyOfA, Step.MATERIALIZE, intentOfA.replica()).isEmpty());
        assertTrue(ApplyEngineHarness.intents().forRequest(b, applyOfA).isEmpty());
        assertEquals(1, ApplyEngineHarness.intents().forRequest(a, applyOfA).size());
    }

    // --- AttemptRepository --------------------------------------------------------------------

    @Test
    void tenantBCannotSeeCloseCountOrDeleteTenantAsAttempts() {
        assertTrue(ApplyEngineHarness.attempts().latest(b, intentOfA.intentId()).isEmpty());
        assertEquals(0, ApplyEngineHarness.attempts().count(b, intentOfA.intentId()));
        assertEquals(0, ApplyEngineHarness.attempts().close(b, attemptOfA.attemptId(),
                AttemptOutcome.FAILED, null, new ErrorClass("b.tried"), null, Instant.now()));
        assertEquals(0, ApplyEngineHarness.attempts().deleteForRequest(b, applyOfA));
        assertEquals(1, ApplyEngineHarness.attempts().count(a, intentOfA.intentId()),
                "A's attempt is still there, still open");
        assertTrue(ApplyEngineHarness.attempts().latest(a, intentOfA.intentId()).orElseThrow()
                .outcome() == null);
    }

    // --- ApplyScopeRepository -----------------------------------------------------------------

    @Test
    void tenantBReadsAndRaisesItsOwnWatermarksAndTenantAsStayExactlyWhereAPutThem() {
        assertEquals(0, ApplyEngineHarness.scopes().instanceWatermark(b, instance));
        assertEquals(0, ApplyEngineHarness.scopes().placementWatermark(b, instance, placement));
        assertEquals(0, ApplyEngineHarness.scopes().maxPlacementWatermark(b, instance));
        assertEquals(7, ApplyEngineHarness.scopes().instanceWatermark(a, instance));
        assertEquals(9, ApplyEngineHarness.scopes().placementWatermark(a, instance, placement));

        long lockedWatermark = ApplyEngineHarness.transactions().execute(st ->
                ApplyEngineHarness.scopes().lockScope(b, instance));
        assertEquals(0L, lockedWatermark, "B locks B's row and reads B's watermark, not A's 7");

        ApplyEngineHarness.scopes().raiseInstanceWatermark(b, instance, 50);
        ApplyEngineHarness.scopes().raisePlacementWatermark(b, instance, placement, 60);
        assertEquals(50, ApplyEngineHarness.scopes().instanceWatermark(b, instance));
        assertEquals(60, ApplyEngineHarness.scopes().placementWatermark(b, instance, placement));
        assertEquals(7, ApplyEngineHarness.scopes().instanceWatermark(a, instance),
                "a raise keyed only by instance would have moved A's fence to 50");
        assertEquals(9, ApplyEngineHarness.scopes().placementWatermark(a, instance, placement));
    }
}
```

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test=TenantIsolationIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
Expected: **6** green.

**Prove it fires (measured, two mutations):** (a) drop `tenant_id = :t AND ` from `ApplyRequestRepository.find` and from `AttemptRepository.latest`/`count` (and the matching `.addValue("t", …)`) → exactly two reds: `tenantBCannotFindTenantAsRequestByItsId` and `tenantBCannotSeeCloseCountOrDeleteTenantAsAttempts` (`expected: <true> but was: <false>`); (b) drop it from `ApplyRequestRepository.blockingRequests` and `ApplyScopeRepository.placementWatermark` → exactly two reds: `tenantBSeesNoBlockingStagedOrStoppingRequestOnTheSharedInstance` (`A's RUNNING apply must not fence B`) and `tenantBReadsAndRaisesItsOwnWatermarksAndTenantAsStayExactlyWhereAPutThem` (`expected: <0> but was: <9>`). Revert both; green.

- [ ] **Step 4: Full verify and the coverage matrix**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`
Expected (measured 2026-09-08 on the prototype, after every review round): BUILD SUCCESS in ~35 s; surefire **mesh-events 127 / dp-core 108 / arch-tests 25 = 260**; failsafe **`Tests run: 129, Failures: 0, Errors: 0, Skipped: 2`** (127 executed: `SmDataBootstrapIT` 8, `PrivilegeTopologyIT` 9, `WatermarkTriggerIT` 3, `RefusalShapesIT` 7, `SpringTransactionBoundaryDataSourceIT` 4, `AdmissionFencesIT` 15, `AdmissionSerializationIT` 7, `MonotonicityIT` 7, `OutcomeTransitionsIT` 12, `StepIntentIT` 12, `AttemptIT` 13, `EngineTransactionBoundaryIT` 2, `ResumeReadIT` 1, `ApplyPersistenceConfigurationIT` 3, `SchemaConstraintsIT` 18, `TenantIsolationIT` 6; the two broker ITs skipped). Reconcile against `backend/dp-core/target/failsafe-reports/failsafe-summary.xml` and the `TEST-*.xml` files, not the console.

**Coverage matrix — every obligation, its enforcer, and the test that proves it (fill the last column with the executed run's evidence in Task 7):**

| Obligation | DB artifact proven by name | Engine rule proven by refusal + DB-accepts control | Class · test |
|---|---|---|---|
| (a) | `apply_inflight_fence` / `stop_inflight_fence` predicates admit the pair | stop exemption `Admitted` | `AdmissionFencesIT` · `aStopIsAdmittedWhile…Running…`, `…Indeterminate…`, `aRawStopBesideARawApply…` |
| (b) | `apply_inflight_fence` (raw insert) | `SCOPE_BUSY` through `admit`; the `FENCE_RACE_LOST` branch is **unreachable through `admit` under the lock (measured) and untested** — a defensive belt, not a proven path | `AdmissionFencesIT` · `aStopIsAdmittedWhile…` (second half), `aRawSecondRequestedRow…` |
| (c) | `stop_inflight_fence` (raw insert) | `STOP_IN_FLIGHT` through `admit`; same `FENCE_RACE_LOST` caveat | `AdmissionFencesIT` · `aSecondInFlightStop…`, `aRawSecondStopRow…` |
| (d) | `apply_scope` row `FOR UPDATE` — the polled `pg_stat_activity` statement is asserted to be `lockScope`'s `SELECT … FOR UPDATE` (scope row pre-committed); deleting `FOR UPDATE` is a measured 5-of-7 red (against the original 7-test class); landing holds the same lock explicitly (`DO UPDATE` branch proven — removing `lockScope` from `land` is a measured 1-test red); `start()` and `resolveIndeterminate()` each hold the identical lock, pinned and mutation-verified separately (1 red apiece: `startItselfBlocksOnAHeldScopeRow`, `resolveIndeterminateItselfBlocksOnAHeldScopeRowEvenOnTheDoUpdateBranch` — the latter forced onto the watermark upsert's `DO UPDATE` branch after a self-found correction, since the `INSERT` branch's incidental FK `FOR KEY SHARE` would otherwise mask the explicit lock) | `SCOPE_BUSY` after release, both directions; placement stop admitted; `GENERATION_NOT_ABOVE_WATERMARK` after a first or a later landing commits. **MEASURED** (`AdmissionSerializationIT`, this run's `TEST-*.xml`): every block seen on `pg_stat_activity` with `wait_event_type=Lock, wait_event=transactionid`, query text `SELECT instance_watermark FROM sm_data.apply_scope WHERE tenant_id = $1 AND instance_id = $2 FOR UPDATE`; holder/contender pairs blocked in the 3000–3060 ms range against a 3000 ms hold in this run (one run's own observation, not a fixed property — an independently reviewed run measured 3004–3045 ms for the same pairs; the class's own assertion is the `>= HOLD_MILLIS - 250` floor, not either exact band) | `AdmissionSerializationIT` (9 — the original 7 plus fix round 1's `startItselfBlocksOnAHeldScopeRow` and `resolveIndeterminateItselfBlocksOnAHeldScopeRowEvenOnTheDoUpdateBranch`) + `AdmissionFencesIT` · `aPlacementScopedStopIsAdmitted…` |
| (e) | tenant-keyed fences | `Admitted` under tenant B, `SCOPE_BUSY` under tenant A | `AdmissionFencesIT` · `theSameInstanceAndPlacementUnderADifferentTenant…` |
| (f) | `hash_on_config_steps` ×7 rows, `placement_scoped_steps` ×2 | — | `StepIntentIT` · the four `(f)` tests + positive control |
| (g) | `apply_step_intent_tenant_id_apply_id_step_replica_ref_key`, `apply_step_intent_idempotency_key_key` | `IntentMismatch`, `IdempotencyKeyReused`, arbiter race re-find = 1. **MEASURED** (`StepIntentIT`, this run's console/`TEST-*.xml`): `arbiter race re-find path executed 1 time(s) for 2 concurrent ensureIntent calls`; `a duplicate violating BOTH intent UNIQUEs is reported as apply_step_intent_idempotency_key_key` (the composite UNIQUE is checked, and named, before the key UNIQUE) | `StepIntentIT` · six `(g)` tests |
| (h) | `state_known`, `op_known`, `state_fits_operation`, `apply_has_generation`, `lifecycle_has_strategy`, `restart_not_manual`, `full_outage_confirmed`, `strategy_known`, `terminal_has_timestamp` (both directions), `generation_positive`, `step_known`, `outcome_known`, `real_placements_only`, `placement_watermark_positive`, `instance_watermark_nonneg`, `resolution_known` (fix round 1 — found pinned by nothing, closed beside the (j) probes) | — | `SchemaConstraintsIT` (16 — the other 3 of its 19 tests are (j)'s, credited on that row) |
| (i) | `apply_attempt_tenant_id_apply_id_intent_id_fkey` | — | `AttemptIT` · `anAttemptReferencingAnotherRequestsIntent…` |
| (j) | `resolution_is_evidence` ×9 shapes, `resolution_only_when_resolved`; positive control inserts | `resolveIndeterminate` positive path | `SchemaConstraintsIT` · three `(j)` tests; `MonotonicityIT` · `resolvingAnIndeterminateAsApplied…` |
| (k) | `42501` on runtime UPDATE; owner control updates 1 row | — | `PrivilegeTopologyIT` · `theRuntimeRoleCannotUpdateAnIntent…`, `…CannotDeleteAnIntent…` |
| (l) | `pg_has_role = false`; `SET ROLE` `42501` | — | `SmDataBootstrapIT` · `theRuntimeRoleIsNotAnOwnerMember…` |
| (m) | `attempt_no_positive` (0, −1); `apply_attempt_intent_id_attempt_no_key` (the concurrent-open arbiter) | `RetryCapExceeded` on the 4th of 3, no row written; `ConcurrentAttempt` for the loser of a concurrent open | `AttemptIT` · `theFourthOpen…`, `aNonPositiveAttemptNumber…`, `twoConcurrentOpensForOneIntent…` |
| (o) Java half | — | `state_fits_operation` mirrored by `RequestState.validFor`: wrong-operation terminals and `INDETERMINATE_RESOLVED` refused before SQL, row unchanged; the raw walk still refused by the DB (`23514 state_fits_operation`) | `OutcomeTransitionsIT` · the five Codex-finding-4 tests; `RequestStateTest` (surefire); the SHIPPED migration text vs. `validFor` — previously locked only pairwise (design↔migration by `SketchMirrorTest`, migration↔hand-transcription by `RequestStateTest`) with no lock tying the CHECK's actual bytes to `validFor` — is now closed by `StateFitsOperationMirrorTest` (surefire, Task 6), which extracts the three branches from the shipped `V002` file and compares them to `validFor` directly |
| `I6` shape | `resolution_is_evidence` (unchanged) | `ErrorClass`/`RuntimeRef`/`PreBaseline`/`ResolutionEvidence` refuse secret-SHAPED values (env lines, `Bearer` headers, blobs) before any SQL; `RuntimeRef.Key` is closed; a bare compact token is measured ACCEPTED — the content control is Plan 005's per-adapter sentinel test, stated | `EvidenceTypesTest` (surefire), `EvidenceJsonTest`, `AttemptIT` · `aSecretShapedValueIsRefusedByTheTypeBeforeAnySqlAndNothingIsWritten`, `aLegalClassificationAndRuntimeRefRoundTrip…` |
| `I6` credentials / exceptions | — | both URL doors admit only `JdbcUrls`' location grammar — scheme, `host[:port]/database`, nine allowlisted parameters with value grammars — and refuse everything else by position, never echoing the URL, a value or an unknown name (allowlist verified against `PGProperty`); the migration step prints classifiers only on failure; `PgConstraintViolation` carries four classifiers and **no driver cause** — measured at the logger, with the raw driver exception's `Failing row` echo as the precondition; Spring's `StatementCreatorUtils` bind-value TRACE line measured and pinned | `JdbcUrlsTest` (8), `SmDataMigrationMainTest` (15 — 11 at Task 1's first landing, grown to 13 in fix round 1 and 15 in fix round 2 as each round's leak closure added its own prove-it-fires test), `SmDataPropertiesTest` (5), `Refusals.assertViolation` (every repository refusal), `AttemptIT` · `aRefusedRowsContentNeverReachesALoggerThroughThePgConstraintViolation`; corruption reports content-free — `EvidenceJsonTest` · `aCorruptJsonColumn…`, `aCorruptInstant…` |
| `I6` free text / bind logging | **`sm_dp_runtime` holds neither INSERT nor UPDATE on `resolution_note`** (both `apply_request` grants column-scoped): a legal resolution UPDATE naming it, and an INSERT naming it even as NULL, are `42501` — the GRANT's refusal, before any CHECK or trigger — with owner positive controls; the catalog shows `SELECT` as the runtime's only privilege on the column | `Resolution` has no note component (the mirror, not the guard); `resolveIndeterminate` omits the column; every jsonb bind through `Sql.redacted` so JDBC's TRACE bind-value line prints `[redacted text, N chars]`, never the document (plain-String precondition measured) | `PrivilegeTopologyIT` · `theRuntimeRoleCannotWriteResolutionNote…`, `theRuntimeRoleCannotInsertARowNamingResolutionNote…`, `theCatalogAgrees…`; `EvidenceTypesTest` · `aResolutionCarriesTypedEvidenceAndNoFreeTextComponent`; `OutcomeTransitionsIT` · `aResolutionLeavesResolutionNoteNull…`; `AttemptIT` · the TRACE half of `aRefusedRowsContentNeverReachesALogger…` |
| (n) | — (no DB artifact for the comparison; watermark tables maintained) — the honest half is measured directly: a raw `ApplyRequestRepository.insert` below the watermark is accepted by the database (`REQUEST` state, row present) once the service has already refused the same generation | `GENERATION_NOT_ABOVE_WATERMARK` in all four directions + after resolved-applied; watermark values asserted | `MonotonicityIT` (9 — the original 7 plus fix round 1's `obligationNHasNoDatabaseArtifactARawInsertBelowTheWatermarkIsAccepted` and a fourth cross-scope direction, `anInstanceWideAppliedAtTwentyFencesTheSameShapeAtTwentyAndAdmitsTwentyOne`) |
| (o) | `42501` on identity columns; `P0001 … is terminal (APPLIED) and immutable`; legal walk succeeds | `IllegalStateException` in Java, nothing written | `PrivilegeTopologyIT` · `theRuntimeRoleCannotRetenant…`; `OutcomeTransitionsIT` · first three tests |
| (p) | `staged_fence`; `STAGED → SUPERSEDED` passes the trigger, `STAGED → APPLIED` refused | admission marks the elder first, `terminal_at` refreshed | `AdmissionFencesIT` · `aRawSecondStagedRow…`, `admissionMarksTheElder…`; `OutcomeTransitionsIT` · `theStagedToSuperseded…` |
| (q) | **none — raw insert against a terminal request is accepted (measured)** | `TerminalRequest` — proven for the sequential case only: `ensureIntent` takes no scope-row lock, so a concurrent `land` could still commit a terminal state between the read and the insert (no production path calls it concurrently today; watch item, Plan 005) | `StepIntentIT` · `theServiceRefusesAnIntentOnATerminalRequest…` |
| (r) | — | — | **NOT PROVEN IN THIS PLAN** — needs a `RuntimeAdapter` and the engine's step loop (Plan 005). Stated, not skipped. |
| (s) | Phase 0 clean (7 statements, 0 warnings) as the bootstrap superuser; `has_schema_privilege = false`; `CREATE TABLE public.smuggled` `42501`; negative control WARNING `no privileges could be revoked` with the privilege left in place; Flyway 6 migrations, history in `sm_data` owned by `sm_dp_owner`; all objects owner-owned; no-Phase-0 migrate fails `3F000` | — | `SmDataBootstrapIT` (all 8) |
| (t) | `P0001 instance watermark may not decrease (10 -> 3)` / `placement watermark may not decrease (12 -> 1)`; rise and equal succeed; `GREATEST` upsert idiom; `42501` on five identity columns and two `DELETE`s; upsert / `FOR UPDATE` / raise positive controls | — | `WatermarkTriggerIT` (3), `PrivilegeTopologyIT` · `theRuntimeRoleCannotMoveAWatermarkRowsIdentity…`, `theNarrowGrantStillAdmits…`, `theCatalogAgrees…` |
| (u) | `attempt_summary`-only fold succeeds; fold + `resolved_by` `P0001` (fold + `resolution_note` is `42501` — the grant, earlier still); `request_has_scope` `23503` | fold-then-delete pair | `OutcomeTransitionsIT` · `anAttemptSummaryOnlyUpdate…`, `aRequestWhoseScopeRowDoesNotExist…`; `AttemptIT` · `theCompactionPair…` |
| §3.1 ↔ files | comment-stripped statement multiset equality + per-file statement counts + one-token mutation | — | `SketchMirrorTest` (7, surefire) |
| Plan 003 watch item | real `DataSourceTransactionManager`: after-commit visibility, rollback, `NOT_SUPPORTED`, `SYNCHRONIZATION_NEVER` | — | `SpringTransactionBoundaryDataSourceIT` (4) |
| `I5` | every repository statement carries `tenant_id` — tenant B addressing A's rows by primary key sees/changes nothing; predicate removal is a measured red for all 24 tenant-keyed statements across the four repositories, checked one at a time (Task 6). Three — `stopInFlight`, `stagedRequest`, `resolveIndeterminate` — were measured GREEN under the original six probes (no genuine stop/STAGED/APPLY_INDETERMINATE row of A's existed to leak) and were closed by three added fixtures plus two added probes, not left stated-but-uncovered. **Evidence, precisely (Task 7 fix round 1):** all 24 measured red, but not uniformly by this class's own assertions — of the five INSERT statements (`ApplyRequestRepository.insert`, `StepIntentRepository.insert`, `AttemptRepository.open`, `ApplyScopeRepository.ensureScopeRow`, `ApplyScopeRepository.raisePlacementWatermark`), mutated with a literal, unrelated tenant UUID, **four** are refused `23503` by a composite FK before any `TenantIsolationIT` assertion runs (`insert`, `StepIntentRepository.insert`, `AttemptRepository.open`, `raisePlacementWatermark`), and **one** — `ApplyScopeRepository.ensureScopeRow` — is a genuine isolation failure with no FK in play: `IllegalStateException: no apply_scope row to lock`, both tenants' upserts having collapsed onto the one literal-tenant row. The class's real INSERT-side coverage proper is its **owner-side positive controls**, measured separately (fix round 1's independent re-review) against a **valid-tenant mis-stamp** (a literal drawn from a tenant whose `apply_scope` row genuinely exists, so an FK cannot be the thing refusing it) and confirmed red there too | — | `TenantIsolationIT` (8) |
| §2.2 rule 0 ‖ caller's transaction | — | `EngineTransactions.requiresNew`: the attempt row survives the caller's rollback; admission commits on its own; a concurrent open inside two callers' own transactions never surfaces as an `UnexpectedRollbackException` to either caller | `EngineTransactionBoundaryIT` (3 — the original 2 plus fix round 1's `aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback`. **MEASURED**: reverting `requiresNew`'s propagation to `PROPAGATION_REQUIRED` turns exactly 3 of these 3 red — the original 2 plus the new one, which fails with `UnexpectedRollbackException` at the caller's own commit — and 0 other tests in the reactor) |

- [ ] **Step 5: Commit**

```bash
git add backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/SchemaConstraintsIT.java backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/TenantIsolationIT.java
git commit -m "Pin every named CHECK of sm_data by a one-violation probe; make I5 a failing guard (Task 6)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

**Risks:** this class shares one tenant/instance across probes (each probe uses a fresh `placement_id`, so the fences never collide) — if a future probe reuses a placement, `apply_inflight_fence` will fire instead of the CHECK under test and the name assertion will say so.

---
### Task 7: Final evidence, documentation sweep, and the completion record

**Files:**
- Modify: `backend/README.md` (status paragraph + a new **Running the `sm_data` tests** section)
- Modify: `docs/design/backend/decisions.md` (one changelog row: plan 004 executed, with the measured counts)
- Modify: `docs/design/DECISIONS.md` (open item 3 gains the orphan telemetry migration's disposition — one clause)
- Modify: `docs/tasks/lessons.md` (three repo-local lessons: R7, R8, R9)
- Modify: `docs/plans/backend/004-apply-persistence-and-admission.md` (the **Results** section, and the coverage matrix's evidence column)

**Interfaces:** none — documentation and evidence only. No code changes in this task; if the sweep finds a code defect, stop and report rather than fixing it inside a documentation commit.

**Preconditions:** Tasks 1–6 committed and green.

- [ ] **Step 1: Final gates, recorded from the summary files**

```bash
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify 2>&1 | tee /tmp/plan004-final-verify.log | grep -E "^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|BUILD|Total time"
cat backend/dp-core/target/failsafe-reports/failsafe-summary.xml
for f in backend/dp-core/target/failsafe-reports/TEST-*.xml; do grep -o 'testcase name' "$f" | wc -l | xargs printf "%s  %s\n" "$(basename "$f" .xml | sed 's/TEST-com.solace.sm.dp.apply.persistence.//;s/TEST-com.solace.sm.dp.meshbridge.//')"; done
grep -o 'testcase name' backend/arch-tests/target/surefire-reports/TEST-com.solace.sm.arch.PlaneBoundaryTest.xml | wc -l
./contracts/run-checks.sh --strict 2>&1 | tail -3
git diff --check && echo "diff-check clean"
```

Expected (the prototype's figures; the executor records what the run prints): surefire 127 / 108 / 25, failsafe `completed=129 skipped=2 failures=0 errors=0`, arch-tests 25 `<testcase>` elements, `ALL CHECKS PASSED` from the contracts gate (zero contract files changed — this confirms it), `diff-check clean`.

Stale-text scan — every hit outside `docs/design/backend/decisions.md`'s changelog is a defect to fix in this task's documentation files, or to report if it is in code:

```bash
grep -rn "watermarks_only_rise\|classified BY CONSTRAINT NAME\|classified by constraint name\|the first persistence/database plan\|plan 004 will\|Plan 004 will" --include="*.java" --include="*.md" --include="*.xml" --include="*.sql" backend docs | grep -v "docs/design/backend/decisions.md" | grep -v "docs/design/backend/evidence/" | grep -v "docs/plans/backend/004-"
```

Expected: no output. (`evidence/ddl-execution-pg14.md` legitimately mentions the retired function name as history; the changelog rows are history; this plan's own text quotes the old wording when explaining the amendment.)

- [ ] **Step 2: `backend/README.md`**

Extend the status paragraph after the plan-003 sentence (before *"No auth-bearing code exists…"*):

```markdown
Plan 004 landed the `sm_data` persistence foundation and the admission path of `BE-D12`/`BE-D13`:
the design's two-phase DDL/DCL as six Flyway migrations (locked to §3.1 by `SketchMirrorTest`) behind
a PostgreSQL 14 Testcontainers harness that executes Phase 0 as the bootstrap superuser and Phase 1 as
`sm_dp_migrator`; a framework-free `apply.model`; four tenant-keyed repositories in `apply.persistence`
(the **only** package that may import JDBC — ArchUnit-enforced); and the `apply.engine` services —
`AdmissionService` (the serialized `apply_scope` admission transaction: cross-scope blocking,
watermark monotonicity, elder-`STAGED` supersession, fence-race classification), `OutcomeService`,
`StepIntentService` (§2.2 rule 2's arbiter with the measured re-read classification) and
`AttemptService` (write-before-act, configured cap, typed evidence — `ErrorClass`/`RuntimeRef`/`PreBaseline`,
identifier-shaped by grammar so environment lines, headers and exception text cannot reach the journal;
content classification within the grammar is the adapter's). Migration is a **separate one-shot process**
(`SmDataMigrationMain`, the only holder of the `sm_dp_migrator` credential); the application receives
`sm_dp_runtime` only and refuses to start against an unmigrated schema. Every database-enforceable
obligation `(a)`–`(u)` is proven by a repository test that asserts the constraint/index/trigger **name**,
`I5` has a failing guard (`TenantIsolationIT`), landing holds the same scope lock as admission, and the
engine's transactions are its own (`REQUIRES_NEW`); `(r)` waits for a `RuntimeAdapter`. No
`RuntimeAdapter`, no endpoint, no event emission yet.
```

Add a new section at the end:

````markdown
## Running the `sm_data` tests

The persistence ITs (`dp-core`, `*IT` under `com.solace.sm.dp.apply.persistence`) start **one**
`postgres:14` container per JVM through Testcontainers and run the checked-in two-phase bootstrap
against it — Phase 0 (`dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql`) as the
container's bootstrap superuser, Phase 1 (`db/migration/sm_data/V001`–`V006`) through Flyway as
`sm_dp_migrator` with `SET ROLE sm_dp_owner` as `initSql`, then every query as `sm_dp_runtime`.

```bash
mvn -f backend/pom.xml -pl dp-core -am verify        # ITs run when Docker is available
```

On a host whose `~/.testcontainers.properties` points `docker.host` at Colima while
`/var/run/docker.sock` is Docker Desktop's, Ryuk fails to start; prefix the command with
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. Without Docker the ITs report as
`Skipped` in `dp-core/target/failsafe-reports/failsafe-summary.xml` — check that file, not the
console, when counting.

**Install-time prerequisites the runbook must carry** (design `eda-walking-skeleton.md` §3.1, §9
notice 3): Phase 0 is executed **once, by the bootstrap superuser** (on PostgreSQL 14 only the owner
of schema `public` can revoke its `CREATE`; a database owner gets a silent `WARNING` no-op — obligation
(s) asserts the outcome); Phase 1 is a **separate one-shot process** — `java -cp <dp-core jar and deps>
com.solace.sm.dp.apply.persistence.SmDataMigrationMain` with `SM_DATA_MIGRATOR_URL`,
`SM_DATA_MIGRATOR_USERNAME`, `SM_DATA_MIGRATOR_PASSWORD` in its environment (an init container/Job on
Kubernetes, a pre-start command on-prem; exit 0 migrated, 1 failed — classifiers only, never exception
text, 2 missing variable or a URL outside the location grammar); **JDBC URLs are locations only** on
either side — `jdbc:postgresql://host[:port]/database` plus an allowlist of nine non-secret pgjdbc
parameters (`JdbcUrls`), because Flyway and Hikari log the URL; the
application process then receives **only** `sm.data.runtime.*` (`sm_dp_runtime`), bound by
`ApplyPersistenceConfiguration`, which the future application class `@Import`s together with
`ApplyEngineConfiguration` and `MeshBridgeConfiguration`, and which refuses to start against a schema
the migration step has not reached. `sm_dp_runtime` cannot read `flyway_schema_history` by design.

**Not adopted here:** `backend/src/main/resources/db/migration/V001__telemetry_schema.sql` and
`backend/src/test/sql/V001_constraints_test.sql` sit outside every Maven module and are the metrics
workstream's (`DECISIONS.md` open item 3). When adopted they must take a version number that does not
collide with `sm_data`'s `V001`–`V006`.
````

Update the "Approved plans" table row to *"plans 001, 002, 003 and 004 are all **EXECUTED**"*.

- [ ] **Step 3: Register, open item 3, lessons**

Append to the changelog table in `docs/design/backend/decisions.md` (after the plan-004-prototype row):

```markdown
| <execution date> | **Plan 004 executed** ([`../../plans/backend/004-apply-persistence-and-admission.md`](../../plans/backend/004-apply-persistence-and-admission.md), Results). `BE-D13`'s §3.1 sketch now ships as `dp-core`'s Phase 0 script + six Flyway migrations, mirrored to the design by `SketchMirrorTest`; obligations (a)–(q) and (s)–(u) are repository tests asserting names, (r) waits for the `RuntimeAdapter` plan. Measured on execution: <surefire per module>, failsafe <completed/skipped>, all green. Design text unchanged by execution — or: <list any amendment the execution forced, each with its measured reason>. |
```

In `docs/design/DECISIONS.md`, open item 3, append to the description cell: *" — including the disposition of the orphan `backend/src/main/resources/db/migration/V001__telemetry_schema.sql` (+ its `V001_constraints_test.sql`), which no module builds: relocate into the metrics module's own Flyway series with a version that does not collide with `sm_data`'s `V001`–`V006`, or delete."*

Append three repo-local lessons to `docs/tasks/lessons.md`, in the file's existing `## R<n> — title` / **What happened** / **Rule** shape:

```markdown
## R7 — Testcontainers on this host needs the socket override when Colima and Docker Desktop coexist

**What happened:** plan 004's first container run failed with `Container startup failed for image
testcontainers/ryuk:0.12.0 … error while creating mount source path '…/.colima/default/docker.sock':
operation not supported`. `~/.testcontainers.properties` pointed `docker.host` at Colima while
`/var/run/docker.sock` was Docker Desktop's. Worse, Testcontainers caches the reaper failure, so only
the FIRST IT class to start a container errored and the rest passed — a partially red run that reads
like a flake.

**Rule:** run container-starting Maven commands with
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` on such hosts; never bake a host path
into a POM; and when exactly one IT class errors at container start, suspect the environment before
the test.

## R8 — Spring keys a transaction by the DataSource INSTANCE, so a harness must cache one per role

**What happened:** a test harness handed out a fresh `SimpleDriverDataSource` from every getter. A
`JdbcTemplate` over one instance and a `DataSourceTransactionManager` over another did not share a
transaction: every write took its own auto-commit connection, the rollback IT failed with the row
still present, and the commit IT had been passing for the wrong reason.

**Rule:** one cached `DataSource` instance per role in any test harness; and every after-commit or
rollback test carries an in-transaction "row not yet visible on another connection" precondition so
the trap cannot recur silently.

## R9 — Strip SQL comments BEFORE tracking quotes when parsing the design's DDL

**What happened:** §3.1's prose comments contain English apostrophes ("PG 14's default …"). A
quote-aware statement splitter run over the raw text opened a phantom string literal on a comment and
reported nonsense for the rest of the file. Measured as a red test before the pass order was fixed.

**Rule:** any code that parses the design's SQL block strips `--` comments first, tracks quotes
second, and asserts its own preconditions (`SqlStatements.assertNoDialectSurprises`) so a dialect
feature the splitter cannot see through fails loudly instead of silently.
```

- [ ] **Step 4: Fill this plan's Results section**

Add a `## Results` section at the end of this plan with: status, the per-task commit table (task, commit, red observed, green counts, prove-it-fires outcome, review verdict, fix waves), the final gate outputs verbatim (surefire per module, failsafe summary XML, arch-tests testcase count, contracts gate line, `git diff --check`), every deviation from the plan with its measured reason, and the coverage matrix's evidence column filled from the executed run — the `MEASURED:` console lines for (d), (g) and the bean order, and the constraint names as the `TEST-*.xml` files recorded them. Watch items carried forward (the routing of `(r)`, cross-scope supersession §8 row 14, `CREATE TEMP TABLE` hardening for the runbook, `disabledWithoutDocker` on a Docker-less runner) go in a table with their homes.

- [ ] **Step 5: Verify the documents**

```bash
for f in backend/README.md docs/design/backend/decisions.md docs/design/DECISIONS.md docs/tasks/lessons.md docs/plans/backend/004-apply-persistence-and-admission.md; do grep -o "](\([^)#]*\.md\)" "$f" | sed 's/](//' | sort -u | while read l; do [ -e "$(dirname "$f")/$l" ] || echo "BROKEN in $f: $l"; done; done; echo "link check done"
grep -n "TBD\|TODO\|implement similarly\|decide later\|fill in" docs/plans/backend/004-apply-persistence-and-admission.md backend/README.md docs/tasks/lessons.md
git diff --check && echo clean
```

Expected: `link check done` with no `BROKEN` lines; the placeholder grep prints nothing; `clean`.

- [ ] **Step 6: Commit**

```bash
git add backend/README.md docs/design/backend/decisions.md docs/design/DECISIONS.md docs/tasks/lessons.md docs/plans/backend/004-apply-persistence-and-admission.md
git commit -m "Record plan 004's completion: README, register row, open item 3 disposition, lessons R7-R9, Results (Task 7)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

**Risks:** none technical. The one judgement call is the changelog row's "design text unchanged by execution" clause — if execution forced any amendment to `eda-walking-skeleton.md`, it is recorded there with its measured reason and the SQL block's hashes are re-stated (they must not have moved; if they did, the plan's Global Constraint was violated and that is a report, not a row).

---

## Out of scope — explicit boundaries

Restated from the Scope section so an executor reading a single task cannot miss it: **no `RuntimeAdapter` (interface or implementation), no engine step loop, no agent RPC or report mapping, no REST controller, no authentication, principal, `authorizationService`, audit log, session, ticket, browser mesh or `LiveStateClient`, no event emission, no generated types, no `contracts/` change, no `sm_control` object, no pruning/archival scheduler, no adoption of the orphan telemetry migration, no `placement` referential guard, no metrics wiring, no CI.** Obligation `(r)` is stated as not proven here. Anything a task appears to need from this list is a plan defect — stop and report.

## Deviations from the requested sequence, made deliberately

1. **Tasks 1 and 2 are drawn differently from the requested "harness, then migration split".** The harness's bootstrap sequence is Phase 0 → passwords → Flyway; a harness that stopped before Flyway would be a different, unexecuted harness (L15). So Task 1 = harness **and** the six extracted migrations with the bootstrap proof; Task 2 = the mirror lock and the DCL/trigger/refusal-shape proofs. Both remain independently reviewable.
2. **The `RuntimeAdapter` interface is not declared.** With no implementation and no engine to call it, an interface here would be an untested declaration (L7); it lands with its first implementation in Plan 005.
3. **The plan's own adversarial review changed it before it was declared done:** two blockers (the (d) test measured the wrong lock; `I5` had no failing guard) and five over-claims (rule 0 under an enclosing transaction; concurrent `open`; `FENCE_RACE_LOST` "both layers"; admission ‖ landing; the mirror's "token-level" wording) — each fixed in the prototype, re-measured, and carried here (`EngineTransactions`, `ConcurrentAttempt`, `TenantIsolationIT`, `EngineTransactionBoundaryIT`, the corrected `AdmissionSerializationIT`, per-file mirror counts). Recorded because the plan's provenance claim would otherwise overstate the first prototype.
4. **The design was amended before this plan was written** (`3977e26`), on three measured points the prototype surfaced: §2.2 rule 2's classification (by re-read outcome, not constraint name — a legitimate race violates both UNIQUEs and PostgreSQL names `apply_step_intent_idempotency_key_key` first), the runtime's inability to read `flyway_schema_history` (stated as intended), and cross-scope supersession (§8 row 14, undecided; v1 exact-scope, pinned by `MonotonicityIT`). The SQL block's hashes did not move.
5. **A second external review (Codex) changed the design and the plan again before either was declared done:** the migrator credential is no longer bound into the application (Phase 1 is `SmDataMigrationMain`, a separate process; the application verifies schema presence at startup), evidence is typed at the persistence seam (`I6` enforced by grammar before SQL, not deferred to Plan 005), landing holds the scope lock explicitly (the FK `KEY SHARE` serialization was measured absent on the upsert's `DO UPDATE` branch), and `land()` mirrors `state_fits_operation` in Java. The design was amended for the first three (`f84030e`); the SQL block did not move in content, only in line number.
6. **Codex's second pass corrected two I6 over-claims:** the migration step's failure path could have printed a credential-bearing URL (it now prints classifiers only, and both credentials refuse URLs that carry `user`/`password` or userinfo — measured with sentinels in URL, username and password across stdout, stderr and the JVM log), and the typed evidence seam had been described as making a secret unrepresentable — it is a shape control with a closed key set, a bare compact token is measured *accepted*, and the content control is Plan 005's per-adapter obligation. Design residual row and §9 notice 3 aligned (`58a2b9f`).
7. **The orphan telemetry migration is not adopted.** Plan 001's obligation gains a named owner (open item 3) instead of a third silent carry-forward.
8. **Codex's third pass closed the two I6 holes the second pass left:** `PgConstraintViolation` had a sanitized message but still carried the `PSQLException` as its cause, and Logback prints cause chains — so `log.error(msg, ex)` still emitted `DETAIL: Failing row contains (…)`; it now carries four classifiers and no cause, `Refusals.assertViolation` requires `getCause() == null` on every repository refusal, and `AttemptIT` measures the leak **at the logger** with a raw-JDBC precondition (mutations: cause re-attached → 17 reds through the guard; guard and `getCause()` assertion disabled too → the log-line assertion alone still fires). And `JdbcUrls` was a blocklist of three parameter names while Flyway logs the whole URL, so a value in any other pgjdbc property — `options=` — was admitted; it is now a location grammar with a nine-name allowlist and value grammars, refusing by position (allowlist off → 7 reds; value grammars off → 3 reds). The measurement also surfaced that Spring's `StatementCreatorUtils` logs every bind value at TRACE — pinned in `AttemptIT` as a second surface the evidence-shape controls exist for. The env-vs-argv comment on the migration `main` no longer claims process-table protection (finding 3). A second focused independent re-review then rated both CLOSED (it reproduced the 17-red mutation and the green run itself, and measured the URL attacks — `%70assword`, `;`, userinfo, duplicates — against `Driver.parseURL`) and corrected one over-claim: the design had said *no* driver exception crosses the persistence boundary as a cause, while `Sql.write` wraps only class 23 and reads are not wrapped at all — the design and changelog now say exactly that. Its minors are folded: `Evidence` disables Jackson's `INCLUDE_SOURCE_IN_LOCATION` explicitly and wraps `Instant.parse` (both corruption reports measured content-free at the time; the `INCLUDE_SOURCE_IN_LOCATION` disable is now defence in depth rather than a pinned property — the fix wave that dropped the leaking cause left the mapper setting untested, and re-enabling it alone measures green, with no-cause on `read`/`instant` the property the tests actually pin); `StepIntentService` no longer echoes the caller-supplied idempotency key; `AttemptIT` asserts `Failing row` is absent from the whole capture, not one line; `JdbcUrls` states its residual (an identifier-shaped secret in an allowlisted value fits the grammar — the same residual `ValueGrammar` states) and refuses an empty query.
9. **Codex's fourth pass removed the one free-text write and closed the bind-value TRACE surface structurally:** `resolution_note` had been the "one deliberately free-text column" — the column an authenticated operator could paste a token or command output into during an incident, persisted, and printed by Spring's `StatementCreatorUtils` the moment TRACE is enabled to diagnose that incident. v1 now writes no free text: `Resolution` has three components (pinned by reflection), `resolveIndeterminate`'s SET list omits the column, and `OutcomeTransitionsIT` asserts it is NULL after a resolution (re-adding the write → 1 red). The column stays in the design's DDL — schema ahead of use, stated in the residual table — and a comments feature owes its own design (sensitive content, retention, access control, logging) before any path writes it. The TRACE surface the third pass had *pinned* is now *closed*: every jsonb bind goes through `Sql.redacted`, a `SqlValue` whose `toString` is a length, so the TRACE line renders `[redacted text, 60 chars]` for the repository path while the plain-String raw probe still shows the sentinel (the precondition; un-redacting one bind → 1 red). The minor: `Credentials.toString` redacted the password but printed the role while `JdbcUrls` calls the role "half of a credential" — both halves are now redacted, the URL stays.
10. **Codex's fifth pass closed the database half of the same finding — and this one is on me, not on the reviewer's late arrival.** Item 9 removed the free-text write in Java and kept the DDL byte-unchanged *to protect the execution evidence*, leaving `sm_dp_runtime` with `UPDATE` on `resolution_note` and table-level `INSERT`: a legal resolution update carrying a note, or a row born with one, was still accepted by the database — a Java-only control where the design's own rule says the database is the last line, the same shape as the cause-chain miss in item 8. The design's `apply_request` GRANT is now column-scoped for INSERT (sixteen columns) as well as UPDATE (six); `resolution_note` is in neither. That changed the authoritative SQL block (Phase 1 md5 `5c28fa14…` → `2f36077c…`, design commit `d87c2e7`), so the PG 14 execution evidence was **re-run in full**: run 3's 168 legs were reconstructed from its logs into a replay harness — now **checked in** at [`evidence/ddl-replay/`](../../design/backend/evidence/ddl-replay/) with its signature manifest, session scripts and reference run, exiting 1 on any status/SQLSTATE/constraint drift (6 differences measured when the grant is widened back) — and executed in order — 0 status changes, 167/167 refusal-reason signatures identical — plus 11 new legs (`42501` on the legal-resolution-with-note UPDATE, on `resolution_note = NULL` alone, on the born-with-note INSERT and on the INSERT naming it as NULL, each with an owner control; the catalog leg showing `SELECT` as the runtime's only privilege on the column; the (u) fold-plus-note now refused by the grant before the trigger) — recorded as §0 of [`evidence/ddl-execution-pg14.md`](../../design/backend/evidence/ddl-execution-pg14.md). In the plan: `PrivilegeTopologyIT` gains the two grant tests with owner controls (widening the grant back → 5 reds), `SchemaConstraintsIT`'s probes no longer name the column (they would all have become `42501`), and the (u) trigger test uses `resolved_by` as its granted second column.

## Final self-review checklist (run before the plan is declared done — and re-run at execution end)

- [ ] **Spec coverage:** `BE-D12` §2.2 rules 0–2 → Tasks 5 (rule 0 `AttemptService.open`, rule 2 `StepIntentService`) and 4 (rule 1 `AdmissionService`/`OutcomeService`); rule 3 (resume decision table) is Plan 005's — this plan delivers only its **read** (`ResumeReadIT`); rule 4's cap → Task 5. `BE-D13` §3.1 DDL → Task 1; DCL → Tasks 1–2; §3.2 state table → `RequestState` + `OutcomeService` (Tasks 3–4); §3.3 admission → Task 4; §3.3 retention primitives → Task 5 (fold + delete); §3.4 `INDETERMINATE_RESOLVED` → Tasks 4/6; §3.5 wire mapping → **not this plan** (no emission). Obligations (a)–(u): every row of the map above has a task; (r) is stated N/A.
- [ ] **Gate coverage:** no task touches auth, endpoints, mesh, events, `contracts/`, `sm_control` or TS. `I5`: every repository method carries `TenantId`; every statement binds `tenant_id`; the two identifier-interpolation sites are fixed-list column names in test code. `I6`: no secret in any column, log, message or fixture; test passwords are harness constants. `I7`: nothing imports `com.solace.sm.cp..`; ArchUnit's existing rules stay green and two new ones land with proofs.
- [ ] **Placeholder scan:** no TBD/TODO/"similar to Task N"; every class and test in this plan is complete, compiled and executed code (prototype `6761af1`, after the adversarial review, its confirmation pass, five Codex rounds and two focused I6 re-reviews); Task 7's changelog row deliberately carries `<execution date>` / `<counts>` slots that the executor fills from the run — those are the only angle-bracket slots, and they are for measured values, not decisions.
- [ ] **Type/signature consistency:** `SmDataMigrator.{INIT_SQL,SCHEMA,LOCATION,PHASE0_RESOURCE}` (T1) used by T2's mirror test and harness; `SmDataMigrationMain.run` (T1) used by the harness and T5's context IT; `EngineTransactions.requiresNew` (T4) used by all four services; `Constraints.ATTEMPT_NO_UNIQUE` (T3) used by T5's `AttemptService` and `ConcurrentAttempt`; `ErrorClass`/`RuntimeRef`/`PreBaseline`/`ResolutionEvidence` (T3) consumed by T3's repositories and `Evidence`, T4's `OutcomeService`, T5's `AttemptService` and every IT that writes evidence; `RequestState.validFor` (T3) used by T4's `OutcomeService`; `Refusals.assertViolation` (T3) used by T4–T6; `Constraints.*` (T3) used by T4/T5 services and tests; `ApplyEngineHarness.{admission(),outcomes()}` (T4) and `{stepIntents(),attemptsService()}` (T5) used by T4–T6 ITs; `AdmissionOutcome.Admitted/Refused`, `RefusalReason` (T4) used by T4/T5 ITs; `StepIntentService.{IntentMismatch,IdempotencyKeyReused,TerminalRequest,arbiterReFinds()}` and `AttemptService.RetryCapExceeded` (T5) used by T5 ITs; `ApplyEngineConfiguration` (T4) extended by T5 and imported by T5's context IT.
- [ ] **Stale-wording scan:** Task 7's grep covers `watermarks_only_rise`, "classified by constraint name", "the first persistence/database plan", "Plan 004 will".
- [ ] **No auth-bearing or mesh-access work scheduled before gates:** confirmed per task; the Gate map states it and the Out-of-scope section restates it.
- [ ] **No new schema keyword or event-contract surface without a lock update:** trivially satisfied — zero contract files change; the SQL block's two hashes are the lock on the schema side and `SketchMirrorTest` is the executable form.

## What Plan 005 would need to cover next

The `RuntimeAdapter` seam and its first implementation — the `DockerAdapter` for the Cache slice — with the engine's step loop over `StepIntentService`/`AttemptService` (§2.2 rule 3's resume decision table: no-attempt→rerun, `MATERIALIZE` hash read-back, `LIFECYCLE` baseline compare, indeterminate on missing baseline), `rollbackOrMarkIndeterminate` routing, the stage-everywhere-then-restart ordering under `SM-D15`, obligation **(r)** (stop during a mid-flight apply), the §3.5 execution-step → wire-event mapping through `EventPublisher` (rollout `staged`/`restart-requested`/`restart-observed`, instance-scoped `config-state` for instance-wide requests only — no terminal-outcome event until the `BE-D4` amendment), the ArchUnit rule confining Docker-client types to the adapter package (design §2.3's named obligation), a Docker-in-Testcontainers harness for the adapter's contract tests, **and the `I6` content obligation this plan leaves open by design: each adapter's `RuntimeRef`/`PreBaseline`/`ErrorClass` builder is the only writer of those columns and must be tested against sentinel secrets drawn from its own inputs (docker inspect output, the container's environment, mounted files) — the evidence types here are shape controls and a bare identifier-shaped token passes them; `mountPath` and `host` are the keys most worth a sentinel; `imageDigest` is derived from §2.3's "same image — never a pull" rather than named there and should be confirmed or dropped when the Docker adapter is designed**. Also the runtime pool size for REQUIRES_NEW's second connection. Still gated after that: every endpoint (G2), the ACL runbook (items 32/33), generated types (`BE-D4` amendment), and the design decisions this plan pinned as open — cross-scope supersession (§8 row 14) and the placements referential guard (§8 row 13).

---

## Results

**Status: EXECUTED.** All seven tasks landed on branch `contracts-backend-consumption`, each independently reviewed (Tasks 1, 4, 5, 6 went through at least one fix round; Tasks 2 and 3 landed clean on the first review). HEAD at completion: `e0a1e7c`. This section is the completion record; the numbers below are this task's own re-measurement of the finished tree, not a copy of any task's report or the plan's prototype-era predictions — five of which (see Deviations) were already stale by the time Task 6 finished.

### Per-task record

| Task | Commit(s) | Red observed | Green (final for the task) | Prove-it-fires | Review verdict | Fix waves |
|---|---|---|---|---|---|---|
| 1 — PG-14 harness, two-phase SQL, bootstrap proof | `d81446c`; round 1 `72e2faa`; round 2 `f0be002`; round 3 `f3ee1e5` | Step 8: `SmDataBootstrapIT` 8 run / 7 errors (missing SQL files, before the migrations existed). Mutation (`createSchemas(false)→true`): 1 red (`migratingADatabaseThatSkippedPhase0FailsLoudly…`, wrong SQLSTATE `42501` vs expected `3F000`) | surefire dp-core 69; failsafe `SmDataBootstrapIT` 8/0 | 1 red, reverted, re-green | **3 rounds, all closed.** R1: found the URL-grammar guard covered only the env reader, not `run()`/`dataSource()` — Testcontainers' own `?loggerLevel=OFF` suffix would have gone through unguarded; fixed with `SmDataPostgres.jdbcUrl()` and a guard on `dataSource()`. R2: the R1 fix's driver-first ordering leaked a sentinel through `java.util.logging` before the grammar check ran (measured `WARNING` lines from `Driver`/`PGPropertyUtil`); reordered grammar-first. R3: two minors — the leak-capture read the raw JUL pattern, not the formatted message (vacuous scan, closed by calling `Formatter.formatMessage`), and one javadoc named the wrong capture surface | 3 |
| 2 — design-mirror lock, DCL/trigger/refusal proofs | `522a5ed` | Mutation 1 (widened UPDATE grant, `tenant_id` added): 4 reds. Mutation 2 (`resolution_note` reopened in both grant lists): 5 reds | surefire dp-core 76 (+7 `SketchMirrorTest`); failsafe `completed=29` — 27 executed (`SmDataBootstrapIT` 8, `PrivilegeTopologyIT` 9, `WatermarkTriggerIT` 3, `RefusalShapesIT` 7) plus the 2 skipped broker ITs, which `completed` counts in (it does not exclude skips) | both mutations reverted, re-green | none — clean on first review | 0 |
| 3 — framework-free model, four repositories, ArchUnit gates | `f10739e` | Step 1: test-compilation failure (`Uuid7`, `ApplyCommand`, etc. did not exist yet) | surefire dp-core 112 (+36 model/persistence unit tests); arch-tests 25 (19 + 2 rules + 4 proofs) | planted-violation fixtures (`BadJdbcOutsidePersistenceClass`, `BadEngineJdbcClass`, `BadFrameworkModelClass`) caught by name on every run — no mutate/revert cycle needed, the fixtures are permanent | none — clean on first review | 0 |
| 4 — serialized admission transaction, outcome walks | `729cea3`; round 1 `ca80cbf`, self-correction `a5c72c6` | Step 1: test-compilation failure. Mutation 1 (cross-scope rule collapsed to equality): 4 reds. Mutation 2 (`FOR UPDATE` removed from `lockScope`): 5 of 7 red. Mutation 3 (`lockScope` removed from `land`): 1 red | surefire dp-core 112 (unchanged — `*IT` only); failsafe 74 → 78 after round 1 | all three mutations reverted, re-green each time; round 1 added 3 mutations covering `start()`, `land()` and `resolveIndeterminate()`'s own lock calls — two of those three (`start`, `resolveIndeterminate`) were the round's new pins — each exactly 1 of 9 red | **1 round, closed.** 2 Important (obligation (n)'s honest half was unproven; `start()`/`resolveIndeterminate()` had no lock pin of their own — the latter needed a self-found correction after the first version accidentally exercised the FK's incidental `KEY SHARE` instead of the explicit lock) + 2 folded minors (pure race's loser reason unasserted; cross-scope watermark coverage uneven) | 1 |
| 5 — immutable step intents, retry journal, resume read, Spring-wired proof | `7da7cba`; round 1 `8cbaabb` | Step 1: test-compilation failure. Mutation 1 (obligation (q) disabled): 1 red. Mutation 2 (readiness gate `EXPECTED_TABLES` 5→4): 2 red. Mutation 3 (`REQUIRES_NEW`→`REQUIRED`): 2 red (round 0) → **3 red** once round 1's new test existed | surefire dp-core 112 (unchanged); failsafe 113 → 115 after round 1 | all reverted, re-green each time | **1 round, closed.** 2 Important (obligation (m)'s database-accepts half unproven; `EngineTransactions`' *second* reason — a caller-side `UnexpectedRollbackException` — had no test, closed by `aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback`, itself the mutation's third red) + 2 documentation-only points (R9's cleared-reference gap stated as a residual risk pre-empted for Plan 005; R6's negative result stated as shape-dependent, with the `javap` mechanism cited) | 1 |
| 6 — constraint-refusal sweep, `I5` guard, coverage matrix | `2edbe19`, `d771a8d`, `2f1df52`; round 1 `e0a1e7c` | `SchemaConstraintsIT` 18/0 on first run. `TenantIsolationIT` mutation sweep: **21 of 24** statements red on the first pass, 3 green-gaps (`stopInFlight`, `stagedRequest`, `resolveIndeterminate` — no genuine fixture existed for the predicate to protect); closed by 3 fixtures + 2 probes, then all 3 re-measured red. `StateFitsOperationMirrorTest` self-mutation (`validFor`'s STOP case): 2 of 3 red. Round 1's independent re-review reproduced the full 24-statement sweep at 24/24 red, including a *stronger* INSERT mutation (valid-tenant mis-stamp, not just a literal orphan) | surefire dp-core 115 (+3 `StateFitsOperationMirrorTest`); failsafe 141 → 142 after round 1 | all reverted, re-green | **1 round, closed.** 1 Important (`resolution_known` — the 23rd named CHECK — was pinned by nothing; closed by `aResolutionOutcomeOutsideTheEnumIsRefusedByResolutionKnown`) + 2 folded minors (the (h) matrix row double-counted 3 tests already credited to (j); the mirror test's mutation literal gave an unhelpful failure message on a legitimate coordinated edit) | 1 |
| 7 — this task | (this commit) | n/a — documentation only | n/a | n/a | n/a | n/a |

### Final gate outputs (measured for this record, HEAD `e0a1e7c`)

```
$ TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify
...
[INFO] Solution Manager :: backend ........................ SUCCESS
[INFO] Solution Manager :: cp-api ......................... SUCCESS
[INFO] Solution Manager :: dp-api ......................... SUCCESS
[INFO] Solution Manager :: mesh-events .................... SUCCESS
[INFO] Solution Manager :: cp-core ........................ SUCCESS
[INFO] Solution Manager :: dp-core ........................ SUCCESS
[INFO] Solution Manager :: console-gateway ................ SUCCESS
[INFO] Solution Manager :: arch-tests ..................... SUCCESS
[INFO] BUILD SUCCESS
```

Surefire per module, summed from `*/target/surefire-reports/TEST-*.xml`'s `tests="…"` attributes:

| Module | Tests | Failures | Errors |
|---|---|---|---|
| mesh-events | 127 | 0 | 0 |
| dp-core | 116 | 0 | 0 |
| arch-tests | 25 | 0 | 0 |
| **Total** | **268** | **0** | **0** |

`arch-tests`: `grep -o 'testcase name' TEST-com.solace.sm.arch.PlaneBoundaryTest.xml \| wc -l` → **25**.

`backend/dp-core/target/failsafe-reports/failsafe-summary.xml`:

```xml
<failsafe-summary ... result="null" timeout="false">
    <completed>142</completed>
    <errors>0</errors>
    <failures>0</failures>
    <skipped>2</skipped>
    <flakes>0</flakes>
    <failureMessage xsi:nil="true" .../>
</failsafe-summary>
```

Per-class failsafe counts (from `TEST-*.xml`, `grep -o 'testcase name' | wc -l`):

| Class | Tests | Class | Tests |
|---|---|---|---|
| `AdmissionFencesIT` | 15 | `RefusalShapesIT` | 7 |
| `AdmissionSerializationIT` | 9 | `ResumeReadIT` | 1 |
| `ApplyPersistenceConfigurationIT` | 4 | `SchemaConstraintsIT` | 19 |
| `AttemptIT` | 17 | `SmDataBootstrapIT` | 8 |
| `EngineTransactionBoundaryIT` | 3 | `SpringTransactionBoundaryDataSourceIT` | 4 |
| `MonotonicityIT` | 9 | `StepIntentIT` | 12 |
| `OutcomeTransitionsIT` | 12 | `TenantIsolationIT` | 8 |
| `PrivilegeTopologyIT` | 9 | `WatermarkTriggerIT` | 3 |
| `JcsmpEventPublisherIT` (skipped) | 1 | `MeshLifecycleIT` (skipped) | 1 |

`completed` **includes** skipped (Task 1's own baseline proves it: two IT methods existed, both skipped, and the summary read `completed=2, skipped=2`) — so the true figure is **142 total `*IT` methods, 140 executed, 2 skipped**, not 144. The 18-row table above sums to 142, consistent with this. 0 failures, 0 errors — matches this record's own `mvn clean verify` exactly (the two broker ITs skip for lack of a live broker, unaffected by this plan).

Contracts gate:

```
$ ./contracts/run-checks.sh --strict
...
== RESULT: ALL CHECKS PASSED
```

Diff-check:

```
$ git diff --check && echo "diff-check clean"
diff-check clean
```

**Stale-text scan** (`watermarks_only_rise|classified BY CONSTRAINT NAME|classified by constraint name|the first persistence/database plan|plan 004 will|Plan 004 will`, excluding this decisions.md's changelog, `evidence/`, and this plan's own text): no output.

**The seven SQL file md5s — unchanged from Task 1 through this record:**

| File | md5 |
|---|---|
| `db/bootstrap/sm_data_phase0.sql` | `768e195b7c17861d97e402c5318be51b` |
| `V001__sm_data_defaults.sql` | `28c29fa570033ff027d6bfd6c515b5a9` |
| `V002__apply_request.sql` | `96321edb257e39bb503192c3c3dff5a6` |
| `V003__apply_scope_and_watermarks.sql` | `ea69e1fad80d3a4dcf56b074608e3b79` |
| `V004__apply_step_intent.sql` | `5a1f041bd3c42ff5f9428ebe1be019a0` |
| `V005__apply_attempt.sql` | `049146260370f241e1321e865e97166a` |
| `V006__terminal_guard.sql` | `9d6da8056e54fd0e1dee777b5c19bbee` |

The design's two SQL-block hashes (`sed -n '228,265p'` / `sed -n '266,552p'` of `eda-walking-skeleton.md`, HEAD `d87c2e7`) are `768e195b7c17861d97e402c5318be51b` and `2f36077ce81bb77ae37d48315092fa7e` respectively — re-verified against this record's HEAD and unchanged from Task 1's first report. **No task touched the design's SQL block; the Global Constraint held for all seven tasks.**

### Controller rulings honoured (process, not code)

**R1 — model split.** The plan's Code Execution Contract (lines 240–283) mandates Claude Opus 5 for every sub-agent and states that a downgrade needs Parikshit's explicit approval. That approval was given for this execution: every implementer ran on Sonnet, every reviewer on Opus. Recorded as authorised, not as an unremarked gap.

**R2 — no worktree.** Every task (1 through 7) worked directly in the existing checkout on `contracts-backend-consumption`; no `git worktree` was created and no new branch was created at any point, confirmed per task by each report's own "no worktree used" line.

### Deviations from the plan's printed code and tests, each with its measured reason

**1. Task 1 (R4) — the URL grammar guard moved to the choke point.** `JdbcUrls.requireLocation` had one production call site (`SmDataMigrationMain.exitCode`, the env reader). `run(...)` and `dataSource(...)` reached Flyway/Hikari unguarded, and the harness's own `SmDataPostgres.migrate()` proved it live — Testcontainers' `PostgreSQLContainer` suffixes its URL with `?loggerLevel=OFF`, a parameter the allowlist does not carry, so the harness was handing Flyway a URL the production door would have refused. Fixed by moving the check into `dataSource(...)` itself and adding `SmDataPostgres.jdbcUrl()`/`jdbcUrl(String)`, which build the URL directly from the container's host/port/database rather than through `getJdbcUrl()`. Zero `getJdbcUrl()` call sites remain.

**2. Task 1 (R7) — grammar validated before driver resolution, after a measured leak.** Round 1's own fix chose driver-resolution-first (to keep `NoDriverRegistered` reachable), reasoned to be safe because no `DataSource` is returned either way. Round 2 measured this false: pgjdbc's `Driver.acceptsURL`/`PGPropertyUtil` calls `java.util.logging` `WARNING`s carrying the **whole URL, including a userinfo password**, before `dataSource`'s own grammar check ever ran — reproduced with the coordinator's three URLs against the real `postgresql-42.7.11.jar`. Fixed by reordering grammar-first, unconditionally; `NoDriverRegistered` was re-anchored to a grammar-conforming URL with the actual registered driver deregistered for the call, which reproduces the "distribution shipped without the driver" case more faithfully than the original scheme-typo probe and does not depend on internal ordering at all.

**3. Task 1 (R5) — `@DependsOn("smDataSchemaReadiness")` added to `smDataTransactionManager` and `smDataTransactions`**, joining `smDataJdbc`. Without these two edges, a bean wired from either could open a transaction against an unmigrated schema before Task 3's repositories exist to prove it matters.

**4. Task 2 — the mutation-verification command was substituted.** The brief's single combined command (`mvn … verify -Dtest=SketchMirrorTest -Dit.test=PrivilegeTopologyIT … -Dmaven.test.failure.ignore=true`) was refused by this session's own tool-permission classifier, specifically on the `-Dmaven.test.failure.ignore=true` flag (confirmed by isolating it). Substituted two separate Maven invocations — a plain `test` run of `SketchMirrorTest`, then the `failsafe:integration-test` goal invoked directly against `PrivilegeTopologyIT` on the already-built `target/test-classes` — to reach the same two test layers without that flag. Both mutations reproduced every red the brief predicted, with identical failure messages, so the substitution changed nothing about what was proven.

**5. Task 4 — lock pins for `start()` and `resolveIndeterminate()`, and obligation (n)'s accepts-half.** The plan's obligation map credited `AdmissionSerializationIT` with proving `OutcomeService`'s scope-row lock, but the original 7 tests only pinned `land()`'s two branches; `lockScope` could have been silently removed from `start()` or `resolveIndeterminate()` with the suite still green. Fix round 1 added a pin for each (mutation-verified: 1 of 9 red apiece), with a self-found correction to the `resolveIndeterminate` pin — its first version landed on the watermark upsert's **INSERT** branch, where the FK's incidental `FOR KEY SHARE` masked the explicit lock the test meant to isolate (measured: the mutation still failed, but for the wrong reason); rewritten to force the raise through the **DO UPDATE** branch, where no FK check runs and only the explicit lock can be blocking. Separately, obligation (n)'s "no DB artifact" half (a raw watermark-violating insert is accepted) had never been measured, only asserted by the engine-refusal half; `MonotonicityIT` gained `obligationNHasNoDatabaseArtifactARawInsertBelowTheWatermarkIsAccepted` to close it.

**6. Task 5 (R9/R10) — `AttemptRepository.close`'s SQL changed, in Java, not in any migration.** Old: `runtime_ref = CAST(:runtimeRef AS jsonb) ... WHERE tenant_id = :t AND attempt_id = :attempt`. New: `runtime_ref = coalesce(CAST(:runtimeRef AS jsonb), runtime_ref) ... WHERE tenant_id = :t AND attempt_id = :attempt AND outcome IS NULL`. R9: a close call with no new reference no longer overwrites what `open` (or an earlier writer) already stored with SQL NULL — measured by `aCloseWithNoNewRuntimeReferenceLeavesTheOneOpenAlreadyWrote` (a close **with** a new reference still overwrites, proven separately, so the fix is not silently append-only). R10: a second close on an already-closed attempt now updates 0 rows instead of 1, which `AttemptService.close` (already throwing on `updated != 1`) turns into a refusal instead of a silent outcome flip — measured by `aSecondCloseOnAnAlreadyClosedAttemptIsRefusedAndTheFirstOutcomeSurvives`.

**7. Task 5 — obligation (m)'s accepts-half, and `EngineTransactionBoundaryIT`'s third test.** Obligation (m)'s "no DB artifact" half was unproven, closed the same way as Task 4's (n) — a raw fourth `AttemptRepository.open` beyond the engine's cap of 3 is accepted by the database (`obligationMHasNoDatabaseArtifactARawFourthAttemptIsAccepted`). `EngineTransactions`' javadoc names **two** reasons for `REQUIRES_NEW`; only one (rollback survival) had a test. Round 1 added `aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback`, and the `REQUIRES_NEW → REQUIRED` mutation — previously measured to fail exactly Task 4's original 2 tests and 0 others — now fails exactly **3**, including the new one, with `UnexpectedRollbackException` as the failure's own text.

**8. Task 5 (R6) — `ApplyPersistenceConfigurationIT` gained a fourth test not in the brief's verbatim listing.** `aMalformedRuntimeUrlsRenderedBootFailureAnalysisCarriesNoSentinel` measures whether Spring Boot's own `BindFailureAnalyzer`/`LoggingFailureAnalysisReporter` echoes a malformed URL's sentinel into its rendered failure report. It could not use `ApplicationContextRunner` (which never drives Boot's `FailureAnalyzer` machinery) or `LogCapture` (measured blind here: `SpringApplication.run()` reinitialises Logback on the way in and detaches any appender attached beforehand) — it drives a real `SpringApplicationBuilder(...).web(NONE).run(...)` and captures raw `System.out`/`System.err` bytes around the call instead. This raises the class from the plan's printed 3 to **4**, and is the source of the R6 shape-dependence caveat recorded under Known-open-items below — not a fix-round addition and not brief-text staleness (see item 14).

**9. Task 6 — `StateFitsOperationMirrorTest` added** (not in the plan's file list), closing a Java↔DDL gap nothing caught: `SketchMirrorTest` only locks migration text against the design's text (blind to meaning), and `RequestStateTest` compares `validFor` against a hand-transcribed copy of the CHECK, not the shipped bytes — so a coordinated edit to `state_fits_operation` in both the migration and the design that forgot `validFor` (or the reverse) would have passed both. The new surefire test regex-extracts the three `CASE`/`WHEN`/`ELSE` branches directly from the shipped `V002__apply_request.sql` and compares them to `RequestState.validFor` for all 36 `(state, operation)` pairs; measured to catch a real, reverted divergence (`STOP_FAILED` removed from `validFor`'s STOP case: 2 of 3 red).

**10. Task 6 — `TenantIsolationIT` extended** by 3 fixtures and 2 tests. The statement-by-statement sweep found 3 of 24 statements (`stopInFlight`, `stagedRequest`, `resolveIndeterminate`) whose tenant predicate could be dropped with the class still green, because no fixture existed under tenant A for the predicate to protect against leaking. **21 of 24** statements were red on the first pass, 3 green-gaps; the 3 are now closed and re-measured red. Fix round 1's independent re-review reproduced the full 24-statement sweep at 24/24 red and additionally ran a **stronger** INSERT mutation (a valid-tenant mis-stamp rather than a literal orphan UUID), confirmed red too, caught by the class's owner-side positive controls.

**11. Task 6 — `resolution_known` pinned, and the coverage matrix's (h) row corrected.** Fix round 1 found the 23rd named CHECK (`resolution_known`) pinned by nothing, despite `SchemaConstraintsIT`'s own javadoc claiming "every named CHECK". Closed by `aResolutionOutcomeOutsideTheEnumIsRefusedByResolutionKnown`. The (h) row's citation had double-counted 3 tests already credited to (j); corrected to `SchemaConstraintsIT (16 — the other 3 of its 19 tests are (j)'s, credited on that row)`.

**12. This task (Task 7, first pass) — three stale test counts in the coverage matrix, corrected.** The matrix's (d) and `§2.2 rule 0` rows still cited `AdmissionSerializationIT`/`MonotonicityIT` at their pre-fix-round sizes (7) and `EngineTransactionBoundaryIT` at 2; Tasks 4 and 5's own fix rounds had already grown these classes to 9, 9 and 3 respectively (confirmed against this record's own `TEST-*.xml` files). Updated in place, with the new tests named and the mutation counts re-stated against the grown classes.

**13. This task (Task 7, fix round 1) — a fourth stale coverage-matrix count, a mis-attributed `I5` mechanism, an arithmetic error, and a wrong first-pass figure copied from a report, all found by independent review and corrected here.** The `I6` credentials/exceptions row still cited `SmDataMigrationMainTest` at 11 — the class grew to 13 in Task 1's fix round 1 and 15 in fix round 2, per `task-1-report.md`; corrected to 15 with the growth history stated. The `I5` row's evidence sentence had over-generalised "five of those reds are the composite FKs refusing an orphan" — measured precisely against `task-6-report.md`'s own 24-row table, **four** of the five mutated INSERT statements are composite-FK refusals (`23503`) and **one** (`ApplyScopeRepository.ensureScopeRow`) is a genuine isolation collapse with no FK in play (`IllegalStateException: no apply_scope row to lock`); rewritten to state both mechanisms distinctly. The "142 completed + 2 skipped = 144 total `*IT` methods" sentence was arithmetically wrong — failsafe's `completed` **already includes** skipped (proved by Task 1's own baseline: 2 IT methods, both skipped, `completed=2 skipped=2`); corrected to 142 total, 140 executed, 2 skipped, matching the 18-row per-class table's own sum. Task 2's row likewise stated "failsafe 29 (`SmDataBootstrapIT` 8, `PrivilegeTopologyIT` 9, `WatermarkTriggerIT` 3, `RefusalShapesIT` 7)" — those four sum to 27, not 29; the two extra are the skipped broker ITs `completed` counts in; corrected to state the split explicitly. Task 6's row had carried forward `task-6-report.md`'s own internally-contradictory summary line ("23 of 24 … 3 green-gaps") without reconciling it against that same report's 24-row table, which shows **21 RED, 3 GREEN**; corrected to 21. The (d) row's and the `MEASURED:` evidence bullet's lock-wait timing band (3008–3059 ms) was stated as if re-confirmed, when it is one run's own observation — an independently reviewed run measured 3004–3045 ms for the same pairs; both places now say so and cite the class's actual assertion floor (`>= HOLD_MILLIS - 250`) rather than either band. Task 4's row description of round 1's three mutations ("added 3 mutations for the two new lock pins") was imprecise — the three mutations covered `start()`, `land()` and `resolveIndeterminate()`; only two of those three were the round's new pins (`land()`'s was already pinned in the original 7) — corrected to name all three and say which two were new.

**14. Per-task baseline arithmetic in the brief's printed "Expected" text was stale in at least six places — `AdmissionSerializationIT` 9 not 7, `AttemptIT` 17 not 13, `MonotonicityIT` 9 not 7, `EngineTransactionBoundaryIT` 3 not 2, `SchemaConstraintsIT` 19 not 18, and `TenantIsolationIT` 8 not 6 — plus two whole-run totals, dp-core surefire 115 not 108 and failsafe 142 not 129** — because Tasks 4, 5 and 6's own fix rounds added tests the brief's per-task text predates. `ApplyPersistenceConfigurationIT`'s 3→4 is **not** filed here: it is item 8 above, a deliberately added test with its own provenance (the R6 measurement), not brief-text staleness. Every task report reconciled the figures above against the measured XML rather than the brief's printed arithmetic, and none of it required a stop-and-report: the growth is authorised fix-round work, not a defect. This record's own numbers (surefire 268, failsafe 142 total / 140 executed / 2 skipped) are the final measurement, superseding every intermediate count in Tasks 1–6's own step-level "Expected" text, which is left as originally written (a historical record of what was expected/measured at that point in the sequence) rather than retroactively edited.

### Two specific corrections applied verbatim

- **The `I5` row's evidence** (coverage matrix, above) now states precisely: all 24 statements measured red; **four** of those reds are composite FKs refusing an orphan and **one** is a genuine isolation collapse (`ApplyScopeRepository.ensureScopeRow`: `IllegalStateException: no apply_scope row to lock`), neither mechanism being `TenantIsolationIT` asserting directly — the class's real INSERT-side coverage is its owner-side positive controls, measured separately against a valid-tenant mis-stamp.
- **Obligation (r)** continues to say plainly, in the coverage matrix, that it has no home in this plan — it needs a `RuntimeAdapter` and the engine's step loop, both Plan 005's.

### `MEASURED:` evidence cited into the coverage matrix (this task's own contribution)

- **(d):** every lock wait seen on `pg_stat_activity` with `wait_event_type=Lock, wait_event=transactionid`, query text `SELECT instance_watermark FROM sm_data.apply_scope WHERE tenant_id = $1 AND instance_id = $2 FOR UPDATE`; holder/contender pairs blocked in the 3000–3060 ms range against a 3000 ms hold in Task 4's own run (`AdmissionSerializationIT`'s `TEST-*.xml`) — a single run's observation, not an asserted property: an independent review's own run measured 3004–3045 ms for the same pairs, and the class itself only asserts `>= HOLD_MILLIS - 250`.
- **(g):** `arbiter race re-find path executed 1 time(s) for 2 concurrent ensureIntent calls`; `a duplicate violating BOTH intent UNIQUEs is reported as apply_step_intent_idempotency_key_key` (`StepIntentIT`'s own `TEST-*.xml`, re-confirmed for this record).
- **The bean order / readiness edges:** `ApplyPersistenceConfigurationIT`'s success-path test resolves all eight beans (`AdmissionService`, `OutcomeService`, `StepIntentService`, `AttemptService`, and the four repositories) through the real Spring context and completes a real admission through the context's own transaction manager and pool; the same test's log capture measured 193 log events during startup, 4 naming the URL (`SimpleDriverDataSource` DEBUG ×2, `DatabaseTypeRegister` DEBUG, the load-bearing `FlywayExecutor` INFO line) — matching `SmDataMigrationMain`'s own javadoc citation exactly, with no update needed.
- **Constraint names, as `TEST-*.xml` recorded them:** `apply_step_intent_idempotency_key_key` (`StepIntentIT`), `apply_inflight_fence`/`stop_inflight_fence`/`staged_fence` (`AdmissionFencesIT`, named in assertion text) — spot-checked against this record's own reports and matching every task's cited name exactly.

### Known-open items, recorded honestly (this plan is not presented as flawless)

- A handful of deferred cosmetic minors remain, to be swept in a final pass: a stale "REAL process streams" comment banner in `SmDataMigrationMainTest`; an "the two FKs" count in `AttemptIT`'s javadoc where the DDL declares three plus a UNIQUE; a loose `@Validated` framing in the R6 boundary note; and an order-dependent `assertEquals(1, …)` in `SchemaConstraintsIT` that a random method order can turn red (a false red, never a false green).
- `RefusalReason.FENCE_RACE_LOST` remains measured-unreachable through `admit` and honestly untested — a defensive belt for a writer that bypasses admission, not a proven path.
- `AttemptService.close` throws one untyped `IllegalStateException` for wrong-tenant / wrong-id / already-closed alike — fine today (one caller), routed to Plan 005 before its watchdog caller exists.
- The R6 negative (Boot's failure report does not echo the bad URL) is **shape-dependent**: a `javap`-located mechanism (`BindFailureAnalyzer.buildDescription`'s `Value: "%s"` line is inside `if (property != null)`) means a record compact-constructor throw never populates `property`, but a conversion failure on a plain scalar property *does*, and prints the sentinel through the same capture technique — proof the negative is a fact about this failure shape, not a blind spot in the test's own instrumentation, and not a general safety claim.

### Watch items carried forward, with their homes

| Watch item | Current state | Home |
|---|---|---|
| Obligation **(r)** — stop during a mid-flight apply | Stated N/A in the coverage matrix; needs a `RuntimeAdapter` and the engine's step loop | Plan 005 |
| Cross-scope `STAGED` supersession (design §8 row 14) | Undecided by the architect group; v1 pinned exact-scope-only by `MonotonicityIT.aPlacementWatermarkOfTwelveFencesAnInstanceWideEleven` and `AdmissionService`'s own code (design §8, `DECISIONS.md`) | Architect group / a future design amendment |
| `CREATE TEMP TABLE` hardening for the install runbook | Proposed (`REVOKE TEMP ON DATABASE`) in `decisions.md`'s 2026-09-07 row; not yet in an executable runbook because no runbook document exists yet | The `dp-remote`/install runbook, when written (blocked on open items 32/33) |
| The placements referential guard (design §8 row 13) | No `placement` referential guard for `apply_request.placement_id` — out of scope for this plan, stated in the plan's own Scope section | A future placements/inventory plan |
| The orphan telemetry migration (`V001__telemetry_schema.sql` + its constraints test) | Not adopted; disposition now named in `DECISIONS.md` open item 3 (relocate into the metrics module's own Flyway series with a non-colliding version, or delete) | The metrics workstream, when it lands its own persistence plan |
| `ApplyRequestRepository.foldAttemptSummary`'s untyped `jsonb` bind | The one hole in the typed evidence seam — `summaryJson` is a caller-supplied `String` cast to `jsonb`, not routed through `Evidence`; created in Task 3 before the seam existed (Task 5), and has no caller yet. Bound via `Sql.redacted`, so the TRACE bind line is safe — the *shape* control is what is missing. A malformed string yields a class-`22` refusal that `Sql.write` does not reshape, propagating with the driver cause | Plan 005's compaction job — either give it a typed shape through `Evidence` or justify the string |
| `StepIntentService.ensureIntent`'s check-then-act on obligation (q) | Proven for the sequential case only: `ensureIntent` takes no scope-row lock, so a concurrent `OutcomeService.land` could commit a terminal state between the read and the insert. No production path calls it concurrently with `land` today (no step loop exists), so this is not a defect in this plan | Plan 005's step loop — decide whether `ensureIntent` needs the scope lock |
| An ArchUnit gate for the `engine` → `persistence` one-way dependency | `ApplyEngineConfiguration`'s javadoc states the direction as verified by inspection (no `apply.engine` import under `apply/persistence/`), but no rule enforces it the way `jdbcIsConfinedToApplyPersistence` enforces the data-plane/persistence boundary | Plan 005 — needs its own planted-violation fixture and review, out of scope for a fix wave |
| `STAGED` terminality for non-manual strategies (post-completion external review, Finding 1 of 2 not fixed in that wave) | **RETIRED — landed.** `BE-D16` ([`eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md) §3.2) is implemented verbatim as `staged_requires_manual` in the design's §3.1 SQL block and in `V002__apply_request.sql`, with the `OutcomeService.land` Java mirror and the DDL-replay baseline re-recorded against it. `BE-D16` stays PROPOSED — see the Results addendum below | Closed by the R18 repair wave; see Results addendum |
| Resolution evidence not bound to the request generation (post-completion external review, Finding 2 of 2 not fixed in that wave) | **RETIRED — landed.** `BE-D17` ([`eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md) §3.4) is implemented verbatim as `resolution_is_evidence`'s operation-discriminated `CASE` in the design's §3.1 SQL block and in `V002__apply_request.sql`, with the `ResolutionEvidence` sealed-type split and `OutcomeService.resolveIndeterminate`'s Java mirror, and the DDL-replay baseline re-recorded against it. `BE-D17` stays PROPOSED — see the Results addendum below | Closed by the R18 repair wave; see Results addendum |

### Post-completion external review fix wave

An independent external review of the completed plan raised two findings. Both are confirmed real,
both are fixed here, and neither touches the design, a migration, or `contracts/`. One commit,
`backend/` plus this addendum.

**Finding A (MAJOR) — `mvn verify` was a false green without Docker.** All sixteen `sm_data` IT
classes carry `@Testcontainers(disabledWithoutDocker = true)`. On a host where Testcontainers cannot
reach a Docker daemon, its `ExecutionCondition` reports every one of their tests `SKIPPED`, not
failed — and failsafe's `verify` goal only fails a build on a *failure*; a skip is not one. This
plan's own watch-item table had left the question open as "could not be proven to skip on this
development host"; it is now measured, and the consequence the watch item did not state is the
point.

**Measured before the fix**, under a genuinely Docker-unreachable environment (proof method below):
`mvn -f backend/pom.xml clean verify` exits **0**, `BUILD SUCCESS`, failsafe reports **142 total, 0
failures, 142 skipped** — every one of the sixteen `sm_data` classes plus the two broker ITs, zero
coverage of migrations, DCL, tenant isolation, fences or transaction boundaries, and nothing else in
the build says so.

Fixed with a mandatory failsafe preflight, `DockerAvailabilityPreflightIT`
(`com.solace.sm.dp.apply.persistence`), carrying no `disabledWithoutDocker` annotation so it always
runs; it calls the exact same check that annotation's `ExecutionCondition` consults —
`DockerClientFactory.instance().isDockerAvailable()` — and fails loudly with an actionable message
when it is `false`. Chosen over stripping `disabledWithoutDocker` from the sixteen classes: a
one-file diff that touches none of the sixteen already-proven classes, and one clear message naming
the missing precondition instead of sixteen classes each failing for their own (correct but noisier)
reason. The two broker-gated ITs (`JcsmpEventPublisherIT`, `MeshLifecycleIT`) are untouched — they
gate on `@EnabledIfSystemProperty(named = "broker.host", ...)`, a live-broker condition this fix has
nothing to do with, and must keep reporting `SKIPPED` with no broker configured.

**Proof method.** This development host runs two independent live Docker daemons (Colima and Docker
Desktop) whose sockets Testcontainers' full strategy chain finds regardless of
`DOCKER_HOST`/`~/.testcontainers.properties` tampering — `UnixSocketClientProviderStrategy` falls
through to the hardcoded `/var/run/docker.sock`, live on this host via a symlink to Docker Desktop's
own socket, no matter what `DOCKER_HOST` says. Actually stopping either daemon to test this was
refused by this session's own sandbox classifier as a host-wide side effect needing the developer's
explicit sign-off, so the `mvn` process itself was wrapped in a `sandbox-exec` (macOS Seatbelt)
profile denying `network*`/`file-read*`/`file-write*` on `/var/run/docker.sock`, `~/.docker/run/` and
`~/.colima/`. This is confirmed to genuinely reach the code path, not merely to fail for an unrelated
reason: both the plain `docker` CLI and Testcontainers, run under the same profile, reported the
identical underlying cause — `dial unix /var/run/docker.sock: connect: operation not permitted` /
`FileSystemException (/var/run/docker.sock: Operation not permitted)` — which is exactly the
"Docker unreachable" condition `isDockerAvailable()` exists to detect, not a permissions accident
elsewhere in the build.

**Measured after the fix**, same sandboxed environment, same command: exit **1**, `BUILD FAILURE`,
failsafe reports **143 total, 1 failure (`DockerAvailabilityPreflightIT`), 0 errors, 142 skipped** —
the same sixteen-plus-two skip as before, now paired with a build no reader can mistake for proof.

**With Docker present**, the full command
(`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`)
is unchanged except for the one new test: surefire **268** (mesh-events 127 / dp-core 116 /
arch-tests 25, unchanged), failsafe **143 total, 141 executed, 2 skipped** — the 2 being exactly the
two broker ITs, **zero** `sm_data` classes skipped. `./contracts/run-checks.sh --strict` ALL CHECKS
PASSED, `git diff --check` clean. `backend/README.md`'s "Without Docker" paragraph is rewritten to
state this measured behaviour in place of the old "intended … not actually measured" language; the
watch-items table's `disabledWithoutDocker` row above is removed — resolved, not open — and its
finding recorded here instead.

**Finding B (MINOR) — the register overstated what the developer approved.**
`docs/design/backend/decisions.md`'s 2026-09-08 row closed with: *"Model ran on Sonnet with Opus
review, and every task worked in the existing checkout with no worktree, per Parikshit's explicit
approval on both rulings."* That conflates R1 and R2. **R1** (the model split) *was* explicitly
approved: the plan's Code Execution Contract mandates Opus for every sub-agent and states a downgrade
needs the developer's explicit approval, which was given for this execution. **R2** (no worktree) was
not itself approved — the developer's execution instruction *delegated* it: "use separate worktrees
only for tasks that the plan and actual file ownership prove independent." The execution lead ruled,
under that delegation, that no task pair in this plan was independent by file ownership, and every
task therefore ran in the existing checkout. Approval and delegated-ruling are different things, and
the sentence claimed approval for both.

Fixed by rewording the sentence to attribute each ruling to its actual source: the model split to
the developer's explicit instruction, the no-worktree decision to the execution lead's ruling under
that instruction's delegation, with the reason stated. The two other MAJOR findings from the same
review — STAGED terminality for non-manual strategies, and resolution evidence not bound to the
request generation — are **deliberately not fixed here**: both need a design decision and are routed
to the developer, out of this fix wave's scope.

**Forward pointer (2026-09-08, does not alter the record above).** Both findings now have a design
decision: `BE-D16` (STAGED is exclusively `manual`'s terminal state — a non-manual apply must stay
`RUNNING` through every lifecycle step) and `BE-D17` (resolution evidence is operation-specific — an
`apply` resolution's evidence must equal the request's own generation; a `restart` resolution's
evidence carries no generation at all), both in
[`eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md) §3.2/§3.4 and
[`decisions.md`](../../design/backend/decisions.md). This is design-only, per the plan's own Global
Constraint (design first, then the SQL block, migrations and Java): the SQL block, all seven
migration files and this plan's own measurements above are **unchanged** by it. The CHECKs, the
migrations and the Java mirrors this design now specifies are the next task in the same repair wave —
listed in the watch-items table below, not executed here.

#### Result

One new file (`DockerAvailabilityPreflightIT.java`), `backend/README.md` reworded, this plan's
watch-item table and this Results section updated, `docs/design/backend/decisions.md`'s 2026-09-08
row reworded, one `docs/tasks/lessons.md` entry added. Zero migrations touched and design text
unchanged — the seven SQL file md5s are exactly the ones this record already carries above. `backend`
surefire **268** (unchanged) / failsafe **143 total, 141 executed, 2 skipped** (was 142/140/2 — the
delta is exactly the one new preflight test). `./contracts/run-checks.sh --strict` ALL CHECKS PASSED,
`git diff --check` clean.

### Results addendum — R2, R3 and R4 of the R18 repair wave close both findings (2026-09-08/09)

The forward pointer above (2026-09-08) left both post-completion findings design-only. They are now
**implemented**. This addendum is additive — nothing in this plan's original Results section, or in
the "Post-completion external review fix wave" subsection above, is rewritten; both record what was
measured at the time they were written.

**What landed, by commit:**

- `647d4e9` — **R2, `BE-D16` executed.** `staged_requires_manual` lands verbatim as an inline `CHECK`
  in `sm_data.apply_request`, identically in `V002__apply_request.sql` and the design's §3.1 SQL
  block, no seventh migration. `OutcomeService.land` gains the matching strategy-dependent Java guard.
  Test obligation discharged both at the raw-SQL layer (`SchemaConstraintsIT`, all four non-manual
  ladder strategies refused by name plus a manual positive control) and through the service
  (`OutcomeTransitionsIT`), red-first and prove-it-fires confirmed by mutation.
- `5f04d79` + `d15dc4b` — **R3, `BE-D17` executed, then its own fix round.** `resolution_is_evidence`'s
  body is replaced in place with the operation-discriminated `CASE` (apply: `stagedGeneration` equals
  the row's own `generation`, non-empty `replicas`; restart: no `stagedGeneration` key, non-empty
  `restartsObserved`) in both `V002__apply_request.sql` and the design's mirrored block.
  `ResolutionEvidence` becomes a sealed interface (`ApplyResolutionEvidence` /
  `RestartResolutionEvidence`); `OutcomeService.resolveIndeterminate` gains
  `requireEvidenceFitsOperation`. All four rejection classes proven at both the Java and raw-SQL
  boundaries, red-first, with dedicated positive controls including a fully-evidenced restart
  resolution. `d15dc4b`'s own fix round closed two gaps an independent re-review found in the same
  work: an unpinned array-clause assertion (fixed with a matching-generation leg) and a latent
  auto-unboxing NPE in the generation comparison.
- `90cce61` — **R4 part 1.** Closed the throw-freedom gap's remaining half with a text-level
  assertion (`SchemaConstraintsIT.resolutionIsEvidenceNeverCallsJsonbArrayLength`, reading
  `resolution_is_evidence`'s installed definition back via `pg_get_constraintdef`) rather than more
  permutation coverage, since `jsonb_typeof` short-circuits ahead of `jsonb_array_length` in the
  shipped clause order and no permutation count can substitute for a text guarantee against a
  regression there. Also corrected two changelog over-claims from the prior fix round and swept three
  stale `` `BE-D12`…`BE-D15` `` range citations to the `` `BE-D*` `` family prefix per `I3`.
- `221672d` — **R4 part 2, the baseline re-record.** The DDL-replay baseline (`evidence/ddl-replay/`),
  deliberately left red since R2 per that task's own instruction, is re-recorded against the two
  amendments via `--record-candidate`: five differences, all explained by `BE-D16`/`BE-D17` and
  nothing else (the `phase1` md5; two `(h)` legs whose signature moved from `state_fits_operation` to
  `staged_requires_manual` because both constraints now fire on the same STAGED-in-a-non-manual-op
  row, Postgres reporting whichever sorts first lexically; two `(j)` legs sharing a stale
  `stagedGeneration: 7` fixture against the row's own `generation = 1`, harmless before `BE-D17` and
  load-bearing after). The two `(h)` legs are retargeted rather than left to accept a renamed
  signature; the two `(j)` legs are retargeted to a matching generation. Eighteen new legs prove
  `BE-D16`/`BE-D17`'s remaining obligations by name, each with a positive control. `legs.json` grows
  184 → 202 entries (179 → 197 SQL legs: 122 refused / 75 ok, up from 107/72); `design-block.json`'s
  `phase1` md5 moves `2f36077c…` → `4ecc4470…`. Re-run from the checked-in artefacts: `NO DRIFT`.
- `17d0595` — landed *before* R2, as part of the R1 fix wave already recorded above (Finding A: the
  Docker fail-closed gate; Finding B: the register's approval-attribution reword). Named here only so
  this addendum's commit list is contiguous with the forward pointer it continues from.

The register's own 2026-09-08/2026-09-09 changelog rows (`docs/design/backend/decisions.md`) carry the
full substance of each round in detail; this addendum summarizes rather than duplicates them.

**Final measured state (HEAD `221672d`):** `mvn -f backend/pom.xml clean verify` — surefire **271**
(mesh-events 127 / dp-core 119 / arch-tests 25), failsafe **163 total, 161 executed, 2 skipped** (the
2 being the two broker ITs, gated on a live broker, unrelated to this wave). `./contracts/run-checks.sh
--strict` ALL CHECKS PASSED. `git diff --check` clean. The DDL-replay harness
(`python3 docs/design/backend/evidence/ddl-replay/replay.py`) reports `NO DRIFT`.

**Hashes as they stood at HEAD `221672d`** (R4's own re-record — historical, superseded by the R5–R7
addendum below): Phase 0 unchanged, `768e195b7c17861d97e402c5318be51b`
(`sm_data_phase0.sql`, lines 228–265). Phase 1 (the design's §3.1 SQL block, lines 266–572)
`4ecc44700ad165b80c2480fc6bbb7a46`. Of the seven migration/bootstrap files, only
`V002__apply_request.sql` moved across this wave, ending at `f4a20e648002d17f5a17f35692a4b31f`; the
other six are unchanged from this plan's original record: `V001__sm_data_defaults.sql`
`28c29fa570033ff027d6bfd6c515b5a9`, `V003__apply_scope_and_watermarks.sql`
`ea69e1fad80d3a4dcf56b074608e3b79`, `V004__apply_step_intent.sql`
`5a1f041bd3c42ff5f9428ebe1be019a0`, `V005__apply_attempt.sql`
`049146260370f241e1321e865e97166a`, `V006__terminal_guard.sql` `9d6da8056e54fd0e1dee777b5c19bbee`,
and `sm_data_phase0.sql` as above.

`BE-D16` and `BE-D17` both **stay PROPOSED** — landing a `CHECK`, its Java mirror, and a re-recorded
evidence baseline is implementation, not ratification. Both watch-item rows above (`STAGED`
terminality for non-manual strategies; resolution evidence not bound to the request generation) are
retired as landed; the two rows immediately above them (the placements referential guard, the orphan
telemetry migration) are unaffected and remain open on their own stated homes.

### Results addendum — R5, R6 and R7 close a fifth review's finding against `BE-D17` (2026-09-09)

The addendum above closed the post-completion review's two MAJOR findings. A fifth, independent
review then measured that `resolution_is_evidence`, even after `BE-D17`, validated only the evidence
**container**: `{"replicas":[{}]}` and `{"restartsObserved":[null]}` were both **accepted** — a
generation-matching apply resolution with no replica reference, no restart observation and no hash
could still land and raise the watermark. This addendum is additive, exactly as the one above it: no
sentence above this point is rewritten, only re-anchored to the commit it was measured against where
it had drifted into a present-tense claim.

**What landed, by commit:**

- `b8a0678` — **R5, `BE-D18` drafted.** A new decision (design-and-register only, no SQL) specifies
  six `apply`-branch and four `restart`-branch `jsonb_path_exists(…, silent => true)` predicates
  closing the container-only gap. `4844c0b` — **R5 fix round 1.** An independent review measured that
  the specified predicates themselves had a `lax`-mode escape (a filter applied directly to an
  array-valued element auto-unwraps and reports "no violation"); three `.type()` predicates were added
  to the specification before anything landed, and two rationale errors in this same document's
  Rationale were corrected.
- `56119a4` — **R6, `BE-D18` executed.** The ten predicates land verbatim inside `apply_request`'s
  existing `CREATE TABLE`, identically in `V002__apply_request.sql` and the design's §3.1 SQL block —
  no new migration. `Evidence.readResolutionEvidence` gains the one Java-side fix the amendment named:
  a `replicas` element whose `observedHash` key is entirely absent now throws
  `IllegalStateException`, rather than being null-coalesced. `22ebd61` — **R6 fix round 1.** An
  independent review mutated every pre-existing clause, not only the ten new ones, and found four of
  them silently unpinned by the new predicates (apply's `stagedGeneration = to_jsonb(generation)` —
  `BE-D17`'s entire reason for existing; both `jsonb_typeof(...) = 'array'` clauses; restart's
  `NOT jsonb_exists(resolution_observed, 'stagedGeneration')`) — three restored by element-completing
  six fixtures, the fourth left permanently unpinnable and documented as defence-in-depth. Recorded as
  the cross-cutting lesson `docs/design/common/lessons.md` L20.
- `3a5d423` — **R7 commit 1, four residuals.** The register itself had no changelog row for R6 or its
  fix round — closed by adding two rows carrying the substance directly, replacing a citation to a
  gitignored session report with the numbers themselves. A second logically-dead clause
  (`jsonb_typeof(resolution_observed) = 'object'`) was identified and documented the same way as the
  first; one more L20-pattern fixture (`[{"ref":"r1"}]`, which the well-formedness predicate refuses on
  its own regardless of `stagedGeneration`) was element-completed; a restart STRING-shaped leg that
  R6 fix round 1's own retarget had silently dropped was restored.
- `de8a6aa` — **R7 commit 2, the baseline re-record.** `BE-D18`'s landing and its fix round had both
  deliberately left the DDL-replay baseline red. Re-recording it reported twelve differences, all
  traced to `BE-D18`: the `phase1` md5, and eleven behavioural differences tracing to five legacy legs
  whose shared exemplars carried bare-string `replicas`/`restartsObserved` elements — one needing a
  genuine retarget (a violated constraint's *name* changed, `resolution_known` → `resolution_is_evidence`),
  the other four fixture bugs whose failure cascaded into six more differences through the admission
  fence. All five root causes element-completed; 37 new legs added proving `BE-D18`'s predicates by
  name on both branches, each with a positive control. `legs.json` grows 202 → 239 entries (197 → 234
  SQL legs: 152 refused / 82 ok). Re-run from the checked-in artefacts: `NO DRIFT`.

The register's own 2026-09-09 changelog rows (`docs/design/backend/decisions.md`) carry the full
substance of each round in detail; this addendum summarizes rather than duplicates them.

**Final measured state (HEAD `de8a6aa`):** `mvn -f backend/pom.xml clean verify` — surefire **273**
(was 271, **+2** new Java tests from R6), failsafe **176 total, 174 executed, 2 skipped** (was
163/161/2, **+13** new `SchemaConstraintsIT` legs from R6; the 2 skipped are still the two broker ITs).
`./contracts/run-checks.sh --strict` ALL CHECKS PASSED. `git diff --check` clean. The DDL-replay
harness (`python3 docs/design/backend/evidence/ddl-replay/replay.py`) reports `NO DRIFT`.

**Hashes as they now stand (HEAD `de8a6aa`):** Phase 0 unchanged, `768e195b7c17861d97e402c5318be51b`
(`sm_data_phase0.sql`, lines 228–265). Phase 1 (the design's §3.1 SQL block) moved
`4ecc44700ad165b80c2480fc6bbb7a46` (lines 266–572) → `a4d51cf21c30135093e94b484f07b53e` (lines
266–610) — the ten predicates' own text. Of the seven migration/bootstrap files, only
`V002__apply_request.sql` moved again, from `f4a20e648002d17f5a17f35692a4b31f` to
`a4e9829dafcfebfa7efc2bb7f914e0e6`; the other six are unchanged from this plan's original record:
`V001__sm_data_defaults.sql` `28c29fa570033ff027d6bfd6c515b5a9`,
`V003__apply_scope_and_watermarks.sql` `ea69e1fad80d3a4dcf56b074608e3b79`,
`V004__apply_step_intent.sql` `5a1f041bd3c42ff5f9428ebe1be019a0`, `V005__apply_attempt.sql`
`049146260370f241e1321e865e97166a`, `V006__terminal_guard.sql` `9d6da8056e54fd0e1dee777b5c19bbee`,
and `sm_data_phase0.sql` as above.

`BE-D18` **stays PROPOSED** — landing its ten predicates, the Java reader fix, and the re-recorded
evidence baseline is implementation, not ratification, exactly as `BE-D16`/`BE-D17` above.

### Results addendum — R8 and R9 close the DDL-replay leg sweep against `BE-D18` (2026-09-09)

The addendum above closed the fifth review's finding. A sixth, independent review then measured that
two DDL-replay legs whose `REFUSED` status and `resolution_is_evidence` signature never moved across
`BE-D18`'s landing had quietly become multi-cause — invisible to `--record-candidate`, which only
flags a *moved* recorded value. This addendum is additive, exactly as the ones above it.

- `97342bc` — **R8.** Legs #108 (`MISSING resolved_by`), #112 (`stagedGeneration` as a STRING) and
  #126 (mismatched `stagedGeneration`, "otherwise fully evidenced") no longer isolated the clauses
  their labels named — each also failed on a leftover bare-string `replicas` element, and #126's
  "otherwise fully evidenced" claim was factually false until fixed. All three were element-completed
  while keeping the defect each names; `--record-candidate` reported 0 differences and `legs.json`
  stayed at 239 entries.
- `c773b86` — **R9.** Rather than stop at the two legs R8's own report had flagged and left open
  (#113, #114), this round swept the full manifest for the same class per
  `docs/design/common/lessons.md` L20, finding five further instances (#128, #131, #134 in group
  `(j)`; #76, #88 in group `(h)`). It disclosed, rather than silently resolved, three things: #113
  and #131 are **structurally unfixable** to single-cause (a non-array JSON scalar's `lax`-mode
  auto-wrap; a restart's `stagedGeneration` key being itself a disallowed top-level key); fixed #114
  is now byte-identical to the pre-existing #127 — a disclosed duplicate, not a silently deduplicated
  one; and #88's recorded `status`/`signature` were already correct, so only its label misled. Seven
  legs changed in total (five by fixture repair, two — #88, #131 — by label only);
  `--record-candidate` again reported 0 differences.

The register's own 2026-09-09 changelog rows (`docs/design/backend/decisions.md`) and
[`../../design/backend/evidence/ddl-execution-pg14.md`](../../design/backend/evidence/ddl-execution-pg14.md)'s
two addenda carry the full substance of each round; this addendum summarizes rather than duplicates
them.

**Final state (HEAD `c773b86`):** `legs.json` stays at **239** entries, `design-block.json`
unchanged, and neither round moved a hash — surefire **273**, failsafe **176 total / 174 executed /
2 skipped**, unchanged throughout both rounds. The DDL-replay harness reports `NO DRIFT`.

`BE-D18` **stays PROPOSED** — a precision fix and a class sweep are not ratification.
