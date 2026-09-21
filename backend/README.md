# backend

Control plane (governance: catalog, schema registry, entitlement, RBAC, config authoring) + data plane (config-of-record, telemetry ingest + store, dp-remote transports, jobs).

**Stack:** Java 17 / Spring Boot · **Status:** plans 001, 002, 003, 004 and 005 executed — 001
merged to `main`, the rest on branches. Plan 001 built the Maven reactor, `mesh-events` contract
bindings, the `mesh-events` `EventPublisher` seam with its `dp-core` JCSMP implementation, I7 ArchUnit
enforcement, and the G1 spike (`spikes/mesh-authz/`). Plan 002 added the contract-consumption locks:
`SchemaMirrorLockTest` (the schema ↔ Java mirror, both directions), the `ContractArtifacts` resolver
(the one way Java code reaches `contracts/`), the publisher contract-test kit every `EventPublisher`
implementation must extend, and two contract-consumption ArchUnit rules. Plan 003 made the publish path
production-shaped: `BE-D1` §3's write-then-notify ordering is now **enforced at the seam** by
`AfterCommitEventPublisher` over the framework-free `TransactionBoundary` (both in `mesh-events`), with
`SpringTransactionBoundary` as the one Spring-aware adapter; the **mesh session starts disconnected and
reconnects** in the background (`ReconnectingTransport` plus JCSMP channel reconnection), so a broker
that is down costs counted drops and never a failed boot (design §9 row 1); `MeshBridgeConfiguration` is
**the bean graph** — `EventPublisher` = `AfterCommit(Jcsmp(Reconnecting))`, `@Primary`, context-proven
against a dead broker; and `arch-tests` now enforces the **publisher inventory**
(`backend-consumption.md` §5.2's named trigger) and **JCSMP confinement** to `dp.meshbridge` (design
§2), each with a planted-violation proof.

Plan 004 landed the `sm_data` persistence foundation and the admission path of `BE-D12`/`BE-D13`:
the design's two-phase DDL/DCL as six Flyway migrations (locked to §3.1 by `SketchMirrorTest`) behind
a PostgreSQL 14 Testcontainers harness that executes Phase 0 as the bootstrap superuser and Phase 1 as
`sm_dp_migrator`; a framework-free `apply.model`; four tenant-keyed repositories in `apply.persistence`
(the **only Data Plane `sm_data`** package that may import JDBC — ArchUnit-enforced at current HEAD; Plan 006
targets a separately confined `com.solace.sm.cp.persistence..` package once that work is committed); and the
`apply.engine` services —
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

⚠️ **On auth-bearing code.** This paragraph used to end "no auth-bearing code exists or may be
written before G2". The prohibition stands — `CLAUDE.md`'s rule bars auth-bearing *code*, which is
stricter than `BE-D15`'s bar on *endpoints*, and the stricter rule controls (open item 38). The
factual half no longer holds at HEAD: `backend/demo-launcher` ships an authenticated demo slice
under two archived, expired waivers ([`../docs/design/archive/demo-waivers/D10-G2-DEMO-WAIVER-2026-09-14.md`](../docs/design/archive/demo-waivers/D10-G2-DEMO-WAIVER-2026-09-14.md)
and [`../docs/design/archive/demo-waivers/SM-D11A-DEMO-WAIVER-2026-09-14.md`](../docs/design/archive/demo-waivers/SM-D11A-DEMO-WAIVER-2026-09-14.md)).
Nothing under `dp-core`, `mesh-events` or `arch-tests` is auth-bearing.

Plan 005 added the **runtime seam and the apply step loop**. `com.solace.sm.dp.apply.runtime` holds
`RuntimeAdapter` — `BE-D12` §2.1's runtime-neutral operations, as amended by `BE-D21` (parameter
types), `BE-D22` (the exhaustion path split into a read-only `assessRollback` and a destructive
`restore`) and `BE-D25` (a generation-free `replicas` observation, because `prepare` is
generation-bearing and a stop has no generation), eight in total — plus the seam's value types and `ConfigFileStore`, whose every writer
goes through one durable atomic-replace helper. `com.solace.sm.dp.apply.runtime.docker` holds
`DockerAdapter`, the first implementation, over the Docker Engine API through
`docker-java-transport-zerodep` with **runtime API-version negotiation** (Colima's daemon has a
minimum above the version this plan originally pinned). Every implementation must pass
`AbstractRuntimeAdapterContractTest`; `PlaneBoundaryTest`'s adapter inventory is where that promise
is recorded, and it fails if an implementation is added without being listed.

`com.solace.sm.dp.apply.engine.ApplyExecutor` drives plan 004's journal under §2.2's rules 0–4:
admission, pre-flight (rendering first, so a render failure is provably `FAILED_NO_EFFECT`),
stage-everywhere, bounded retry, and `BE-D22`'s exhaustion journal — a hashless read-only
`ROLLBACK_ASSESS`, then a `ROLLBACK` intent **carrying the restore target's hash and committed
before the destructive call**, then one `VERIFY` per replica. `HealthGate` is `SM-D15`'s
`rolling-health-gated` gate, deliberately not an adapter operation: a runtime that publishes no
health signal makes the strategy `Unavailable` and is refused at pre-flight rather than silently
degraded (`L7`). `ResumePlanner` is §2.2 rule 3 as a pure, total decision table, and
`ApplyExecutor.resume` wires it — routing by **phase** rather than by the newest intent's step, so
a crash anywhere inside `BE-D22`'s tail finishes the rollback instead of re-materializing over it —
**with no production caller: no application class exists and `G2` forbids writing one.**
Obligation **(r)** is **proven** (`StopDuringApplyIT`), in §3.3's own terms: the stop executes at
the adapter, the apply is *not* cancelled in place — the engine reads no request row — and the
apply's next step observes the stopped replica through `status` and its health gate and terminates
`CONFIG_DIVERGED` with per-replica evidence and no torn write. Both halt routes are covered: the
gate, watching a replica that never comes back, and the liveness check, refusing to restart a
replica somebody just took down. Three ArchUnit rules with planted-violation proofs confine
`docker-java` to the adapter package and keep the seam and the persistence package pointing one
way. A **`stop`** is scope-granular (`BE-D26`): one placement, or a whole instance. Instance-wide is
admissible for a stop though not for an apply, and the asymmetry is structural — `BE-D23`'s limit
is about the placement-scoped steps (`PREFLIGHT`/`ROLLBACK`/`ROLLBACK_ASSESS`) and a stop writes
none of them, so one `apply_id` holds one `STOP` intent per replica across any number of
placements. `resume` is narrowed the same way: `resume(ScopeKey)` beside `resume(TenantId)`.

One v1 limit is deliberate and tested: the apply engine is **single-placement** (`BE-D23` — an
instance-wide *apply* is refused before admission, and `InstanceWideIdentityLimitIT` pins the
identity limit against the shipped DDL). Still no endpoint, no auth, no `sm_control`, no event
emission from the apply path.

## The Cache walkthrough

[`demo/README.md`](demo/README.md) brings up a real Solace Cache Engine container with the external
config directory SM owns bind-mounted into it, and `CacheApplyWalkthroughIT` narrates a real apply
against it. **It is not part of `mvn verify`'s gate** — it needs a sibling repository built — and
that README records two measured facts about the Cache that contradict plan 005's own, plus the
reason a broker turned out to be a prerequisite of running the integration at all.

## Running the `sm_data` tests

The persistence ITs (`dp-core`, `*IT` under `com.solace.sm.dp.apply.persistence`) start **one**
`postgres:14` container per JVM through Testcontainers and run the checked-in two-phase bootstrap
against it — Phase 0 (`dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql`) as the
container's bootstrap superuser, Phase 1 (`db/migration/sm_data/V001`–`V006`) through Flyway as
`sm_dp_migrator` with `SET ROLE sm_dp_owner` as `initSql`, then every query as `sm_dp_runtime`.

```bash
mvn -f backend/pom.xml -pl dp-core -am verify        # ITs run when Docker is available
```

The Docker-adapter ITs (`com.solace.sm.dp.apply.runtime.docker`) and the apply walkthroughs use the
same daemon. They write their bind-mounted config directories under `dp-core/target/it-config/`,
**not** `@TempDir`: measured on Colima, a directory created under macOS's `java.io.tmpdir`
(`/var/folders/…`) mounts into a container but host writes do not propagate to it, so the probe
round-trip fails with a 404 from `HEAD /archive`. A `$HOME`-rooted path works.

On a host whose `~/.testcontainers.properties` points `docker.host` at Colima while
`/var/run/docker.sock` is Docker Desktop's, Ryuk fails to start; prefix the command with
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

**Without Docker, `mvn verify` now fails the build, on purpose.** The sixteen `sm_data` IT classes
still carry `@Testcontainers(disabledWithoutDocker = true)`, and Testcontainers' `ExecutionCondition`
still reports every one of their tests `Skipped` — but a skip is not a failure to failsafe's `verify`
goal, and it was measured (external review, 2026-09) that a genuinely Docker-unreachable run gets
`BUILD SUCCESS` with **142 total, 0 failures, 142 skipped** and nothing else in the build saying so: a
green build with zero proof of migrations, DCL, tenant isolation, fences or transaction boundaries.
`DockerAvailabilityPreflightIT` (same package, no `disabledWithoutDocker`) exists to close exactly
that gap: it always runs, calls the same `DockerClientFactory.instance().isDockerAvailable()` check
the annotation consults, and fails loudly with an actionable message when Docker is unreachable —
measured to turn the same scenario into `BUILD FAILURE`, exit `1`, **143 total, 1 failure, 142
skipped**. With Docker present the sixteen classes and the two broker ITs behave exactly as before;
see `docs/plans/backend/004-apply-persistence-and-admission.md`'s Results section (post-completion
external review fix wave) for the full before/after measurement and the proof method used.

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

## Before writing code here

This component does not own its own architecture. Read, in order:

| What | Where |
|---|---|
| Cross-cutting decisions | [`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md) |
| Overall architecture | [`../docs/design/architecture.md`](../docs/design/architecture.md) |
| **This component's design** | [`../docs/design/backend/design.md`](../docs/design/backend/design.md) — **DRAFT, PROPOSED** |
| This component's decisions | [`../docs/design/backend/decisions.md`](../docs/design/backend/decisions.md), ID family `BE-D*` (all PROPOSED) |
| How this code consumes `contracts/` | [`../docs/design/common/contracts/backend-consumption.md`](../docs/design/common/contracts/backend-consumption.md) (`BE-D10`/`BE-D11`) |
| Approved plans | [`../docs/plans/backend/`](../docs/plans/backend/) — plans 001–005 are all **EXECUTED** (each plan's Results section is its completion record) |
| Setup checklist | [`../docs/checklists/new-module.md`](../docs/checklists/new-module.md) |

⚠️ **The baseline is UNBASELINED** — `SM-D*` decisions are PROPOSED and the design has open blocking items. Do not
start implementing against them. See [`../docs/tasks/codex-review-triage.md`](../docs/tasks/codex-review-triage.md).

**If the design is wrong or missing, raise it — do not invent architecture here.** Component-level decisions get an
ID in the `BE-D*` family and are recorded in the design folder, not in code comments.
