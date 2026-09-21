# Backend Plan 005 — `RuntimeAdapter`, the Docker adapter, and the apply step loop

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `dp-remote` a real, runtime-neutral apply path — seven operations behind a `RuntimeAdapter` seam, a Docker implementation, and an engine step loop over plan 004's persistence — so a real Cache container's configuration can be materialized, its lifecycle observed, and the outcome recorded as evidence rather than as a log line.

**Architecture:** `BE-D12`'s seam (`docs/design/backend/eda-walking-skeleton.md` §2.1) lands as a new package `com.solace.sm.dp.apply.runtime` in `dp-core`, as amended by `BE-D21`–`BE-D23`: seven operations, runtime-neutral types, and a contract-test kit every adapter must pass. `com.solace.sm.dp.apply.runtime.docker` implements it against the Docker Engine API over `docker-java-transport-zerodep` — four endpoints and a stat probe, no `docker-java-core`. The engine's `ApplyExecutor` (in the existing `com.solace.sm.dp.apply.engine`) drives the loop through plan 004's `AdmissionService` / `StepIntentService` / `AttemptService` / `OutcomeService`, writing before it acts (§2.2 rule 0) and resuming from its own rows (§2.2 rule 3). Three ArchUnit rules, each with a planted-violation proof, keep the Docker client inside the adapter package, the runtime seam free of the engine, and the persistence package free of the engine.

**Tech Stack:** Java 17 · Spring Boot 3.5.16 (BOM only — no web, no auto-configuration beyond what plan 004 already imports) · `com.github.docker-java:docker-java-transport-zerodep` 3.4.2 · Jackson (BOM-managed) · JUnit 5 · Testcontainers 1.21.4 · ArchUnit 1.5.0 · PostgreSQL 14.

**Spec:** [`docs/design/backend/eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md) §2 (`BE-D12`) and §3.2/§3.3/§3.4 (`BE-D13`, as amended by `BE-D16`–`BE-D20`), plus [`docs/design/backend/decisions.md`](../../design/backend/decisions.md). Sequenced as **W1** in [`docs/tasks/demo-delivery-sequence.md`](../../tasks/demo-delivery-sequence.md). Predecessor: [`004-apply-persistence-and-admission.md`](004-apply-persistence-and-admission.md), whose **Results** section and **watch items** are inputs to this plan, not background.

---

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the spec and the repo's own instruction file.

- **Java 17, Spring Boot 3.x, Maven** (`SM-D18`, plan 001's Global Constraints). No new Maven module — the seam and the adapter are packages inside `dp-core` (`BE-D3` fixes the module set at four).
- **`I1` — no MI development.** Nothing in this plan may require a change to the Cache image, its build, its code or its endpoints. The demo's `HEALTHCHECK` is declared in **compose**, not in the image — deployment configuration is fair game, a Dockerfile change is not.
- **`I2` — verify external facts before writing them down.** Every Docker Engine API fact in this plan was measured against a live daemon (Docker Desktop 28.5.1, Engine API 1.51, path-pinned to `/v1.43`) on 2026-09-11; every library version was read from a POM or from Maven Central's own metadata. Section [Measured facts](#measured-facts-this-plan-rests-on) records each one. A task that finds a measurement wrong reports it; it does not quietly adapt.
- **`I5` — server-side tenant isolation.** Every new persistence-touching call carries a `TenantId`. No new SQL is written by this plan: the engine speaks only plan 004's repositories and services.
- **`I6` — secrets never land in config storage.** Adapter evidence (`RuntimeRef`, `PreBaseline`, `ErrorClass`) is a **shape** control today; this plan owes the **content** obligation plan 004 left open (Task 7). No raw adapter error text, Docker response body, container environment variable or config-file byte may reach a column, a log line or an exception message.
- **`I7` — cross-plane boundary.** Nothing in this plan imports `com.solace.sm.cp..`.
- **G2 is in force.** `CLAUDE.md`: *"No auth-bearing backend code exists or may be written before G2."* The stricter rule controls over `BE-D15`'s endpoints-only reading (delivery sequence §2.1, `DECISIONS.md` open item 38). **No task in this plan may write a principal, credential, session, role, role binding, permission catalog, `authorizationService`, CSRF token, scope table, or any HTTP endpoint — authenticated or not.**
- **Contracts are single-writer.** No file under `contracts/` changes in this plan. `./contracts/run-checks.sh --strict` must stay green and is part of every task's gate.
- **`apply_request.requested_by` stores an actor id and nothing more.** Recording *who asked* is not an authorization decision (delivery sequence §2.2). The engine never consults it.
- **Plan 004's persistence semantics are not this plan's to change.** If an implementation contradiction is found, **stop and report a design defect**; do not invent a migration, a column, a CHECK or a service-method signature change. The one exception is explicitly scheduled: Task 8 Step 6 resolves plan 004's own `ensureIntent` scope-lock watch item, which that plan routed here by name.
- **No event emission.** §3.5's execution-step → wire-event mapping is **deferred**, with its reason stated in [Scope and non-goals](#scope-and-non-goals--stated-exactly).
- **Every guard carries a planted-violation proof.** `L7`: a guard that cannot fail is not a guard. Every ArchUnit rule, every refusal, and every gate in this plan lands with a mutation that makes it fire.
- **Never report a grep count as verified** (`L12`), and **a guarantee is settled only when its enforcing artifact is in the document** (`L19`).
- **Commit attribution.** Every commit ends with the trailer the session's attribution rule sets. Use the currently configured Git identity; do not override `user.name` or `user.email`.

---

## Prerequisites

1. **Branch and worktree.** `integration/dp-remote-demo` at `595d27b`, worktree `/Users/parikshit/Work/Development/solution-manager-worktrees/dp-remote-demo`. Do not merge, push, rebase, reset, amend, or modify another worktree.
2. **Docker must be reachable.** `dp-core`'s `DockerAvailabilityPreflightIT` already fails the build when it is not (plan 004's post-completion fix wave), and it lives in the same module as every IT this plan adds, so the new ITs inherit that protection. Task 3 Step 7 *measures* that inheritance rather than assuming it.
3. **The socket override.** On a host where `~/.testcontainers.properties` points at Colima while `/var/run/docker.sock` is Docker Desktop's, prefix every container-starting Maven command with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (lesson `R7`).
4. **No database carries `sm_data` yet.** Plan 004's Prerequisites note still holds: the migrations are replayed into a throwaway `postgres:14` per JVM. This plan adds no migration, so the note is carried forward unchanged.
5. **The Cache image is *not* a prerequisite for the gate.** Task 12's Cache walkthrough needs `solace-cache-engine` built and imaged; every other Docker-backed test in this plan runs against a stand-in container that reproduces the Cache's external-config layout exactly. See Task 12 for why that split is deliberate and what each half proves.

---

## Gate map — what this plan may touch, and what stays blocked

| Gate | Status for this plan | What it blocks here |
|---|---|---|
| **G2** (`D10` amendment / open item 24) | **In force.** | Everything in W2/W3: identity, sessions, permissions, roles, bindings, `sm_control.scope`, `authorizationService`, CSRF, the CP audit log, and **every HTTP endpoint**. This plan writes none of them and exposes no surface. |
| **G3** (`BE-D4` amendment) | **Off this plan's path.** | Generated types, the notify/rollout event emission, `LiveStateClient`. Deferred by scope, not merely blocked — see below. |
| **`BE-D5` ratification** (open items 32/33) | **Off this plan's path.** | The broker ACL runbook. No mesh session is opened by anything this plan adds. |
| **Open item 35** (environment-level mesh isolation) | **Off this plan's path.** | Unreachable: no mesh subscription exists in this plan. |
| **`SM-D21`/`SM-D22`** (PROPOSED) | **Implemented against, and flagged.** | `prepare`'s launch-source resolution and the bounded verification posture come straight from them. `I4` applies: these are proposals, and the plan implements the mechanism the proposals describe while the ratification question stays with the architect group. |

**How G2 compliance is proven, not asserted:** Task 13 Step 3 runs a repository-wide grep for the auth vocabulary over this plan's diff and records the output. `L12` applies — the count is evidence only alongside the reviewed diff, never on its own.

---

## Scope and non-goals — stated exactly

**In scope.**

1. The `RuntimeAdapter` seam: **seven** operations (`BE-D21`+`BE-D22`), runtime-neutral result types, a contract-test kit — including the step-label-truthfulness obligation §3.1 assigns to it — and an in-memory reference implementation that passes it.
2. `DockerAdapter`: `prepare` (launch-source resolution, mount-identity probe, replica enumeration), `materializeConfig` (temp → fsync → rename, pre-images, zero lifecycle effect), `startOrUpdate` (declared verb, **never** an image pull), `stop`, `status` (liveness, health signal, hash read-back, start baseline), and `BE-D22`'s split exhaustion pair `assessRollback` (read-only) + `restore` (hash-bearing).
3. The engine step loop over plan 004's persistence, **single-placement** (`BE-D23`): admission, intent/attempt journal, write-before-act, stage-everywhere-then-restart ordering, health gating by polling `status` under a bounded deadline, resume, and `BE-D22`'s bounded-retry exhaustion journal (assess → hash-bearing rollback → per-replica verify).
4. `manual` as the default strategy — the core **never** calls `startOrUpdate` under it, terminating in `STAGED` exactly as `BE-D16` requires.
5. Obligation **(r)** — stop during a mid-flight apply — which plan 004 recorded as N/A pending this seam.
6. Three ArchUnit rules with planted-violation proofs, one of them plan 004's carried-forward `engine → persistence` watch item.
7. The `I6` **content** obligation plan 004 left open for adapter evidence builders.
8. A non-auth demo compose and a Cache-shaped walkthrough, sufficient to prove the real apply path.

**Explicitly out of scope, each with its reason.**

| Not in this plan | Why |
|---|---|
| **Event emission (§3.5's mapping)** | The rollout enum's canonical home is the open `BE-D4` amendment (`contracts/events/README.md` Known gap 2), terminal outcomes have **no wire event at all** in the v1 catalog (§3.5's last two rows), and the demo is REST-only by the delivery sequence's own scoping decision (§1), which takes G3 off the critical path. Emitting three of the five facts and silently dropping the two that matter most would be worse than emitting none. Routed to the plan that lands the `BE-D4` amendment. |
| **systemd and Kubernetes adapters** | Delivery sequence §6. The seam is proven generic by `BE-D12` §2.3's three-runtime walk-through at design time and by the contract kit here; building the other two on speculation is the per-solution-branch failure `REQ-001` rejects. Task 2 records the exact extension points. |
| **Any HTTP endpoint, authentication, RBAC, sessions, CSRF, the `sm_control.scope` table, `sm-admin provision-demo-operator`** | G2. Delivery sequence §2.2. |
| **Frontend import and the `packageVersion` contract change** | W4. Not on this plan's path. |
| **Generated types, `contracts/` changes of any kind** | G3 and the single-writer rule. |
| **New `sm_data` migrations, columns, CHECKs or DCL** | Plan 004 owns the schema; this plan is its first consumer. A contradiction is a report, not a migration. |
| **`ApplyRequestRepository.foldAttemptSummary`'s untyped `jsonb` bind** | Plan 004 routed it to "Plan 005's compaction job". There is no compaction job in this plan, and the method still has no caller. Carried forward unchanged, re-stated as a watch item in Task 13. |
| **Pruning / archival scheduler, the placements referential guard, the orphan telemetry migration** | Plan 004's watch items with other homes; none is reachable from the apply path. |
| **A lease-bounded authority for `dp-remote`** | `B/D16`'s fifth clause, named out of scope by `BE-D12` §2.2 while CP and DP are co-located, routed as design §8 row 7. |
| **Instance-wide applies, and any placement fan-out** | `BE-D23`. `apply_step_intent`'s identity is placement-blind, so a fan-out journals the second placement's pre-flight as a retry of the first's and has nowhere to record its rollback. Refused before admission; the identity amendment is a **persistence** plan's. |
| **Automatic rollback to a target of *absence*** | `BE-D22`. `hash_on_config_steps` requires a `config_hash` on a `ROLLBACK` intent and absence has no hash, so §2.2 rule 0 forbids restoring it. Reaches `APPLY_INDETERMINATE` with `rollback.target.absent` — a defined, tested outcome, and the improvement (a hashless restorative rollback) is a DDL change this plan may not make. |

---

## Measured facts this plan rests on

Measured on 2026-09-11 against Docker Desktop 28.5.1 (Engine API 1.51), and read out of the named POMs. **A task that observes a different value stops and reports it.**

| Fact | Measured value | Where the plan uses it |
|---|---|---|
| `GET /v1.43/containers/json?all=true&filters=…` | `200`, JSON array; `filters={"name":["sm-probe"]}` matches | Task 3, Task 4 replica enumeration |
| `GET /v1.43/containers/{id}/json` | `200`; absent container → **`404`** | Task 3, Task 5 |
| `State` keys, healthcheck **absent** | `Dead, Error, ExitCode, FinishedAt, OOMKilled, Paused, Pid, Restarting, Running, StartedAt, Status` — **no `Health` key** | Task 5: `HealthSignal.NOT_DECLARED`, and Task 9's refusal of `rolling-health-gated` |
| `State` keys, healthcheck **declared in compose** | adds `Health` = `{"Status":"healthy","FailingStreak":0,"Log":[…]}` | Task 9, Task 12 |
| `State.Health.Status` on a **stopped** container | reads **`unhealthy`** — stale, not absent | Task 5: liveness is read first; health is never read while `Running == false` |
| `State.StartedAt` | RFC-3339 nanos, e.g. `2026-09-11T13:06:04.910664012Z`; changes on restart | Task 5 `PreBaseline`, Task 9 `restart-observed` |
| `Image` (inspect, top level) | `sha256:73aaf090…` — the **local image id**, 71 chars, unchanged across a restart | Task 6: the executable form of `SM-D2`/`I1`'s "never a pull"; fits `ValueGrammar.VALUE`'s 128-char bound |
| `POST /v1.43/containers/{id}/restart?t=N` | **`204`**; on a *stopped* container also `204` (it starts it); absent → **`404`** | Task 6 |
| `POST /v1.43/containers/{id}/stop?t=N` | **`204`** running; **`304`** already stopped | Task 6: `304` is idempotent success (§2.2 rule 2's "a replica already at the desired state reports success") |
| `HEAD /v1.43/containers/{id}/archive?path=…` | `200` + `X-Docker-Container-Path-Stat`, base64 of `{"name","size","mode","mtime","linkTarget"}`; absent path → **`404`** | Task 4's mount-identity probe — proves the bind from **inside** the container with no `exec` and no tar |
| A host write into a bind-mounted dir | visible to the daemon immediately (stat size changed 10 → 9 bytes within the same second) — **on a directory the VM actually mounts** | Task 5 |
| ⚠️ **`@TempDir` is unusable for a bind-mounted config directory under Colima** | Measured 2026-09-12: macOS `java.io.tmpdir` is `/var/folders/…`. A Colima bind of a directory created there mounts, but host writes do **not** propagate — `docker exec ls` shows the directory empty while the host shows the file, and `HEAD /archive` answers `404`. A directory under `$HOME` (the module's own `target/`) propagates correctly. | `DockerFixtures.newConfigDir()` creates IT config dirs under `target/it-config/`. Every Docker-backed IT in Tasks 4–7 and 12 uses it; `@TempDir` is correct only for the non-Docker `ConfigFileStoreTest` |
| `Config.Entrypoint` / `Config.Cmd` | `Entrypoint` may be **`null`** with `Cmd` carrying the whole command | Task 4: launch-source resolution reads both, never only `Entrypoint` |
| An unsupported API version in the path | `GET /v1.99/containers/json` → **`400`** | Task 3 |
| ⚠️ **The API-version floor is per daemon, and the pin was wrong** | Measured 2026-09-12 on **Colima**: Docker **29.2.1**, `ApiVersion 1.53`, **`MinAPIVersion 1.44`** — so `/v1.43` is BELOW the floor and every call is a flat `400`. Docker Desktop 28.5.1 on the same host accepts it (floor 1.24). The 2026-09-11 row above was measured against Desktop only. | **Task 3 negotiates instead of pinning**: one `GET /version` on the unversioned path, `PREFERRED` clamped into `[MinAPIVersion, ApiVersion]`, resolved once and cached. Recorded as an execution deviation — a constant cannot be right for both daemons, and raising it only moves the cliff |
| `testcontainers:1.21.4` (managed by Boot 3.5.16) | brings `com.github.docker-java:docker-java-transport-zerodep:3.4.2` at compile scope; `testcontainers-bom` does **not** manage docker-java | Task 3: the pin is **3.4.2**, deliberately not Central's `3.5.1` release, so exactly one version is on the test classpath |
| `docker-java-transport-zerodep:3.4.2` dependencies | `docker-java-transport`, `slf4j-api`, `jna` — the Apache HTTP client is shaded in | Task 3: why this is the transport and `docker-java-core` is not a dependency |
| `com.github.dockerjava.transport.DockerHttpClient` | `Response execute(Request)`; `Request.builder().method(Method.GET).path(String).build()`; `Response.getStatusCode()/getHeaders()/getBody()` | Task 3 |
| `jackson-databind` on `dp-core` | present transitively via `mesh-events` (compile scope) | Task 3 declares it directly rather than relying on the transitive edge |
| Cache's config path | `--spring.config.additional-location=/app/external/spring/config/` on the **command line** (`solace-cache-engine/Dockerfile:12`) | Task 4, Task 12 — and the precedence trap `SM-D21` exists to close |
| Cache's config path persistence | `RUN mkdir -p /app/external/spring/config`, **no `VOLUME`** | Task 12: the compose must bind-mount it, exactly `SM-D21`'s round-2 correction |
| `cache-engine.solace.enabled` | `@ConditionalOnProperty(havingValue = "true")` on `JcsmpSolaceConsumer`, `JcsmpSolaceProducer`, `LeaderElection` | Task 12: the demo Cache boots with **no broker**, so the walkthrough needs no `BE-D5` machinery |

---

## File structure

All paths are relative to the repository root. Nothing outside `backend/`, `docs/plans/backend/`, `docs/design/backend/decisions.md`, `docs/design/DECISIONS.md`, `docs/tasks/lessons.md` and `backend/README.md` is touched.

### New — the runtime seam (`dp-core`, package `com.solace.sm.dp.apply.runtime`)

| File | Responsibility |
|---|---|
| `RuntimeAdapter.java` | The seven operations (`BE-D21`+`BE-D22`). The only type the engine knows an adapter by. |
| `PlacementRef.java` | `(TenantId, InstanceId, PlacementId)` with the instance-wide sentinel **refused** — the sentinel-free refinement of `ScopeKey`. |
| `TargetReplica.java` | `(PlacementRef placement, ReplicaRef ref)` — `BE-D12` §2.1's "`ReplicaRef` carries its `PlacementRef`", expressed without touching the DDL-mirrored `ReplicaRef`. |
| `RenderedConfig.java` | `(byte[] bytes, String hash)` — one owner for the hash, computed once. |
| `RenderedConfigSource.java` | `RenderedConfig render(PlacementRef, long generation)`. Resume needs to re-render; this is the seam that lets it. |
| `ConfigLocation.java` | `(String containerPath, Path localDir, String fileName, Mechanism mechanism)`; `Mechanism ∈ {ADDITIONAL_LOCATION, OVERWRITE_IN_PLACE}` (`SM-D3`). |
| `PreflightResult.java` | Sealed: `Ready(replicas, location, healthSignalDeclared, runtimeRef)` \| `Refused(ErrorClass, runtimeRef)`. |
| `MaterializeResult.java` | `(String writtenHash, RuntimeRef runtimeRef)`. |
| `LifecycleResult.java` | `(boolean issued, RuntimeRef runtimeRef)` — `issued = false` is the idempotent "already in the desired state". |
| `RuntimeStatus.java` | `(Liveness, HealthSignal, String observedHash, PreBaseline baseline, RuntimeRef)`; `observedHash` and `baseline` nullable, and a null `baseline` is §2.2 rule 3's indeterminate trigger. |
| `Liveness.java` | `RUNNING \| STOPPED \| ABSENT`. |
| `HealthSignal.java` | `HEALTHY \| UNHEALTHY \| STARTING \| NOT_DECLARED`. |
| `RollbackAssessment.java` | Sealed: `Restorable(String targetHash)` \| `NothingToRestore()` \| `Unknowable(ErrorClass)` — what a **read-only** assessment concluded (`BE-D22`). Carries no observation list: per-replica evidence is rows, never a return value. No `Diverged` variant: divergence is a lifecycle verdict the engine takes from its journal, never a config-hash comparison. |
| `RestoreResult.java` | `(RuntimeRef runtimeRef)` — what a **destructive** restore touched. Its per-replica outcome is the `VERIFY` attempts the engine issues afterwards, not this value. |
| `RuntimeAdapterException.java` | Carries an `ErrorClass` and **no cause**. The `I6` posture `PgConstraintViolation` already holds, applied to the other boundary. |
| `ConfigFileStore.java` | **One** durable atomic-replace helper (`durablyPlace`: same-directory temp → fsync the temp → `ATOMIC_MOVE` + `REPLACE_EXISTING` → fsync the directory → clean the temp) used by **all four** writers — the config write, the pre-image capture, the absence marker and the restore — plus hashing, retained generation pre-images and the never-overwrite-an-unexpected-target guard. Runtime-neutral on purpose: the systemd adapter reuses it verbatim. |

### New — the Docker adapter (`dp-core`, package `com.solace.sm.dp.apply.runtime.docker`)

| File | Responsibility |
|---|---|
| `DockerEngineClient.java` | The only class that speaks HTTP to the daemon: `inspect`, `list`, `restart`, `stop`, `statInContainer`. Maps status codes to `ErrorClass`; never lets a response body escape. |
| `DockerProperties.java` | `sm.dp.runtime.docker.*` — `host` (a `DOCKER_HOST`-shaped URI), `connectTimeout`, `responseTimeout`, `stopTimeoutSeconds`. |
| `DockerErrors.java` | The status-code → `ErrorClass` table, in one place, as data. |
| `DockerPlacement.java` | The declared binding: container selector, `containerConfigPath`, `daemonConfigDir`, `localConfigDir`, `fileName`, `retainedGenerations`. |
| `DockerPlacementCatalog.java` | `DockerPlacement lookup(PlacementRef)`. The inventory tables are W5's and the endpoints are G2's, so v1 resolves from configuration. |
| `PropertiesDockerPlacementCatalog.java` | The v1 implementation, bound from `sm.dp.runtime.docker.placements[*]`. |
| `LaunchSource.java` | Parses `Config.Entrypoint` + `Config.Cmd` + `Config.Env` into the declared config location and the **competing** sources beside it (`SM-D21`). |
| `DockerAdapter.java` | `RuntimeAdapter` over the four above. |

### New — the engine step loop (`dp-core`, existing package `com.solace.sm.dp.apply.engine`)

| File | Responsibility |
|---|---|
| File | Created by | Responsibility |
|---|---|---|
| `ApplyOutcome.java` | **T8** | Sealed: `Landed(applyId, state, errorClass)` \| `NotAdmitted(reason, detail)` \| `StrategyUnsupported(strategy, detail)`. |
| `ApplyExecutor.java` | **T8**, extended by T9 and T10 | The step loop. Write-before-act, stage-everywhere, strategy dispatch, the exhaustion path, terminal landing. |
| `ApplyEngineProperties.java` | **T8** | `sm.dp.apply.*` — `stepBudget` only; T9 adds `healthGateDeadline` and `healthPollInterval`. **Not** the retry cap: that is plan 004's `AttemptPolicy`, bound from `SmDataProperties.apply().maxAttempts()`, and a second copy would be a second answer to the same question. **Not** `retainedGenerations` either: `DockerPlacement` already carries it, because how many pre-images to keep is a property of the placement's storage, not of the engine. |
| `HealthGate.java` | **T9** | The core's policy over `status` observations: poll interval, bounded deadline, the `NOT_DECLARED` refusal. |
| `GateVerdict.java` | **T9** | Sealed: `Passed(observed)` \| `Failed(why)` \| `Unavailable(why)`. |
| `ResumePlanner.java` | **T10** | §2.2 rule 3's decision table as a pure function over rows — no adapter calls, so it is unit-testable without Docker. |
| `StepDecision.java` | **T10** | `RERUN \| CONTINUE \| REISSUE \| INDETERMINATE` — `ResumePlanner`'s output. |
| `ApplyEngineConfiguration.java` *(modify)* | T8 (`ApplyExecutor`, `ApplyEngineProperties`), T9 (`HealthGate`) | Beans for what each task creates; the existing five untouched. |

The **Created by** column is not decoration: it is the claim the
[execution-boundary audit](#execution-boundary-audit) checks. No Task 8 file may name a type this
table attributes to T9 or T10.

### New — tests

`dp-core` unit (surefire): `ConfigFileStoreTest`, `RuntimeSeamTypesTest`, `ResumePlannerTest`, `HealthGateTest`, `LaunchSourceTest`, `DockerErrorsTest`, `DockerPropertiesTest`, `AbstractRuntimeAdapterContractTest` (abstract), `InMemoryRuntimeAdapterContractTest`.

`dp-core` failsafe (`*IT`): the six Docker-backed classes — `DockerEngineClientIT`, `DockerPrepareIT`, `DockerMaterializeAndStatusIT`, `DockerLifecycleIT`, `DockerEvidenceSecrecyIT`, `DockerAdapterContractIT` — and the six engine classes, which need a real PostgreSQL but **no Docker adapter**: `ManualApplyIT`, `InstanceWideIdentityLimitIT`, `EnsureIntentRaceIT`, `RollingApplyIT`, `ResumeIT`, `StopDuringApplyIT`. Plus `CacheApplyWalkthroughIT` (opt-in, **not** part of the gate — Task 12).

`arch-tests`: three rules and three fixtures — `com.solace.sm.dp.apply.engine.archfixture.BadEngineDockerClass`, `com.solace.sm.dp.apply.runtime.archfixture.BadRuntimeSeamEngineClass`, `com.solace.sm.dp.apply.persistence.archfixture.BadPersistenceEngineClass`, plus the legal control `com.solace.sm.dp.apply.runtime.docker.archfixture.LegalDockerClientClass`.

### Modified

| File | Change |
|---|---|
| `backend/dp-core/pom.xml` | Two dependencies: `docker-java-transport-zerodep` (pinned 3.4.2, compile) and `jackson-databind` (BOM-managed, compile, declared rather than inherited). |
| `backend/arch-tests/src/test/java/com/solace/sm/arch/PlaneBoundaryTest.java` | Three rules, three proofs, one legal control. |
| `backend/README.md` | The new packages, how to run the Docker-backed ITs, and the demo walkthrough. |
| `backend/demo/docker-compose.yml` *(new)* | PostgreSQL 14 + a real Cache container with the bind-mounted external config directory and a compose-declared healthcheck. |
| `backend/demo/README.md` *(new)* | How to build the Cache image, bring the stack up, and run the walkthrough. |
| `docs/design/backend/decisions.md` | One changelog row. |
| `docs/design/DECISIONS.md` | Open item 1's disposition (the `dp-remote` apply protocol now has an implementation). |
| `docs/tasks/lessons.md` | Lessons observed during execution. |
| `docs/plans/backend/005-runtime-adapter-and-docker-apply.md` | Its own **Results** section. |

---

## The seam and its scope are decisions, taken before Task 1 — not plan-level deviations

An earlier revision carried the signature change as *"two deviations, stated up front"*, to be
recorded at Task 13. A plan review rejected that, and a second review then found two further defects
that were **also** design questions wearing plan clothing. All three are now decisions with IDs,
taken before Task 1 rather than reported after Task 13.

| Decision | Status | What it settles | Implementation gate |
|---|---|---|---|
| [`BE-D21`](../../design/backend/decisions.md) | PROPOSED, 2026-09-11 | The seam's parameter types: `TargetReplica` instead of a widened `ReplicaRef`, and a caller-owned `RenderedConfig`. | None of its own. G2 does not touch this seam. |
| [`BE-D22`](../../design/backend/decisions.md) | PROPOSED, 2026-09-11 | The exhaustion path is **two** operations, because `BE-D13` §3.1 already has two journal steps for it. | None of its own. |
| [`BE-D23`](../../design/backend/decisions.md) | PROPOSED, 2026-09-11 | The v1 engine is **single-placement**; an instance-wide apply is refused before admission. | None of its own — but the amendment that would lift it is a **persistence** plan's, not this one's. |

All three amend `BE-D12` by cross-reference; `BE-D12`'s text is flagged in
[`eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md) §2.1 and §3.1, never
rewritten (`I3`). All three stay PROPOSED: implementation is not ratification, the sentence
`BE-D16`–`BE-D18` each carry. **None of them touches `sm_data`** — no migration, no column, no CHECK,
no repository or service signature.

### The accepted seam — seven operations

```java
public interface RuntimeAdapter {
    PreflightResult    prepare(PlacementRef placement, long generation);
    MaterializeResult  materializeConfig(TargetReplica replica, long generation, RenderedConfig config);
    LifecycleResult    startOrUpdate(TargetReplica replica, long generation);
    LifecycleResult    stop(TargetReplica replica);
    RuntimeStatus      status(TargetReplica replica);
    RollbackAssessment assessRollback(PlacementRef placement, long generation, String intendedHash);
    RestoreResult      restore(PlacementRef placement, long generation, String targetHash);
}
```

**`BE-D21` — why the parameter types change.** `ReplicaRef` cannot be widened to carry its
`PlacementRef`: as built it is `record ReplicaRef(String value)`, its grammar mirrored into
`resolution_is_evidence` by `BE-D19` and its `ref` key closed over by `BE-D18`'s per-element key-set
predicates, so a second component would change an evidence wire shape the database now holds rows to.
`TargetReplica(PlacementRef, ReplicaRef)` meets §2.1's *"adapters never see bare strings"* at the seam
and leaves the persisted vocabulary untouched. And the rendered bytes need one owner: §2.1 says
`materializeConfig` persists *"generation `G`'s rendered bytes"* but gives the signature nowhere to
receive them, so each adapter would render its own — a second renderer per runtime, free to disagree
with the `config_hash` the intent row bound before it was called under §2.2 rule 0.

**`BE-D22` — why the sixth operation became two.** §3.1's `step_known` enum carries **two** labels for
the exhaustion path: `ROLLBACK_ASSESS`, *"the hashless assessment that restored nothing"*, and
`ROLLBACK`, which `hash_on_config_steps` **requires** to carry a `config_hash` because *"a restorative
rollback knows its target generation before acting"*. §3.1's engine-invariant table states the rule
outright — *"a restore journals `ROLLBACK`, never `ROLLBACK_ASSESS`"* — and names its enforcement home:
*"adapter contract tests, per adapter"*.

An engine holding one conflated operation cannot satisfy that. Rule 0 makes it open an intent
**before** invoking the adapter, and at that moment it does not know whether the adapter will restore,
nor the hash it would restore to. Every branch is a defect, and this plan's first draft took the
worst of them:

| Branch | Defect |
|---|---|
| Open `ROLLBACK_ASSESS`, restore, land `FAILED_ROLLED_BACK` *(the first draft)* | A restore journalled as an assessment. Breaks §3.1's invariant directly. |
| Open `ROLLBACK` after the adapter acted | Breaks rule 0, and a crash in the window cannot tell whether the restore happened — the exact guess `APPLY_INDETERMINATE` exists to refuse. |
| Open `ROLLBACK` with an invented `config_hash` | Puts a value in an audit column that describes nothing. |

So the split. `assessRollback` is read-only and journals `ROLLBACK_ASSESS` (hashless,
`@placement` — what `hash_on_config_steps` and `placement_scoped_steps` require). Only on
`Restorable(targetHash)` does the engine open a **`ROLLBACK` intent carrying that hash as its
`config_hash`** and then call `restore`. The target is durable before the destructive act, so resume
reads it back instead of guessing.

**Durable evidence, and what is not evidence.** `RollbackAssessment` and `RestoreResult` are values
the engine branches on and then discards. **No part of this plan may call them the record.** The
record is rows: each replica's `MATERIALIZE` attempt carries what was on disk after that attempt
(including a *failed* attempt — the engine closes it with the hash `status` reads back, which is what
`observed_hash` is for: *"read-back EVIDENCE, never intent"*), and after a restore the engine issues
one **`VERIFY`** step per replica against `targetHash`, replica-scoped and hash-bearing, so each
read-back lands in its own attempt row. §3.2's `CONFIG_DIVERGED` row already says this is where the
evidence lives: *"per-replica evidence in `attempt` rows and `resolution_observed`"*.

**The one unjournallable case, decided.** Where the prior state was the **absence** of a file, the
restore target has no hash: `hash_on_config_steps` makes it unjournallable as `ROLLBACK`, and rule 0
forbids acting without the entry. The assessment reports `Unknowable("rollback.target.absent")` and
the request reaches `APPLY_INDETERMINATE` for operator confirmation. That is a defined, tested
outcome — Task 6 and Task 8 both cover it — and it is the honest one: the engine knows what to do and
cannot record having done it.

**`BE-D23` — why the engine is single-placement.** `apply_step_intent`'s logical key is
`UNIQUE (tenant_id, apply_id, step, replica_ref)`, and `placement_scoped_steps` pins `replica_ref` to
the literal `'@placement'` for `PREFLIGHT`, `ROLLBACK` and `ROLLBACK_ASSESS`. Measured consequences,
both against the shipped DDL rather than inferred:

1. **One `apply_id` can hold at most one `PREFLIGHT` intent.** An instance-wide fan-out over two
   placements finds the second `ensureIntent` returning the *first* placement's row — same logical
   key, same null hash, so no mismatch is raised — and records the second placement's pre-flight as a
   **retry of the first's**.
2. **Replica-scoped steps are keyed by `replica_ref` alone within the apply.** Two placements whose
   adapter produces the same label collapse onto one intent; where their rendered configs differ, the
   second `ensureIntent` raises `IntentMismatch`, reporting a legitimate two-placement apply as a
   protocol error.

The first draft of Task 8 fanned out anyway, and would have produced exactly this: **placement A
materialized, placement B failed, and the exhaustion path rolled back only B — leaving A changed,
under a request whose journal claimed one pre-flight.** That scenario is the reason for the refusal,
and Task 8 pins both identity limits with tests so the model's edge is recorded rather than
remembered.

An instance-wide apply is therefore **refused before admission**: no `apply_request` row, no intent,
no attempt, no adapter call. Refusing before admission rather than landing a failure is deliberate —
a request the engine cannot journal correctly should not occupy the scope, and a `FAILED_*` row would
imply an attempt was made.

⚠️ **Explicitly forbidden, in this plan and any successor: encoding a placement into `replica_ref`.**
`BE-D19` grammar-locks that column on both sides, `BE-D18` closes over its values, and
`placement_scoped_steps` compares it to a literal. The identity amendment — a `placement_id` on the
intent table, the UNIQUE and `placement_scoped_steps` widened, the fence and watermark interactions
re-measured — moves the DDL-replay baseline and belongs to the plan that revisits `sm_data`. It is
named in `BE-D23` so the next plan does not rediscover it.

---

## Dependency map

```
Task 1  seam types + ConfigFileStore
   │
   ├─► Task 2  contract kit + in-memory adapter ──┬───────────────────────────────┐
   │                                              │                               │
   │                                              ▼                               │
   │                                     Task 8  ApplyExecutor, MANUAL            │
   │                                     (complete slice: preflight, render,      │
   │                                      stage-everywhere, bounded retry,        │
   │                                      exhaustion path, all four terminals)    │
   │                                              │                               │
   │                                              ▼                               │
   │                                     Task 9  rolling + health gate            │
   │                                              │                               │
   │                                              ▼                               │
   │                                     Task 10 resume + obligation (r)          │
   │                                                                              │
   └─► Task 3  DockerEngineClient (pom, HTTP, ErrorClass)                         │
          │                                                                       │
          └─► Task 4 prepare ─► Task 5 materialize + status ─► Task 6 lifecycle   │
                                                                    │             │
                                                                    ▼             │
                                                           Task 7  I6 sentinels   │
                                                           + DockerAdapterContractIT
                                                                    │             │
Task 11 ArchUnit rules      ◄── needs Tasks 3 and 8 (the packages must exist to be confined)
Task 12 demo + walkthrough  ◄── needs Tasks 6 and 8 ───────────────┴─────────────┘
Task 13 documentation and Results ◄── last
```

**Task 8 does not depend on the Docker adapter**, and that is the change a plan review forced. Its
integration tests drive `InMemoryRuntimeAdapter` against a real PostgreSQL, so the whole engine branch
(8 → 9 → 10) runs beside the whole adapter branch (3 → 4 → 5 → 6 → 7) rather than after it. The two
branches meet at Tasks 11 and 12.

### Safe parallel work

| Pair | Why they are genuinely independent |
|---|---|
| **Task 2 ‖ Task 3** | Task 2 owns `AbstractRuntimeAdapterContractTest` and `InMemoryRuntimeAdapter` (test sources only); Task 3 owns `DockerEngineClient`, `DockerProperties`, `DockerErrors` and `pom.xml`. Disjoint file sets; neither reads the other's output. They converge at Task 7 Step 8. |
| **Tasks 8–10 ‖ Tasks 3–7** | Disjoint packages (`..apply.engine` versus `..apply.runtime.docker`), disjoint test classes, and one shared file touched by neither (`ApplyEngineConfiguration` is the engine branch's alone). The engine branch consumes only Tasks 1 and 2. |
| **Task 11 ‖ Task 12** | `arch-tests` sources versus `backend/demo/` and one opt-in IT. Both depend only on packages that exist by the end of Tasks 6 and 8. |

Within each branch everything is serial: Tasks 4–6 extend one class in sequence, and Tasks 8–10
extend one executor in sequence. Plan 004's own execution record notes that *no* task pair there was
independent by file ownership; this plan has three, and claiming more would be the same mistake
inverted.

### What remains blocked by G2 after this plan lands

In the order the delivery sequence sequences it:

| Blocked item | Gate | Consumes from this plan |
|---|---|---|
| Identity substrate, permission catalog, roles, bindings, `authorizationService`, CSRF, CP audit log, `sm_control.scope` (W2) | G2 | Nothing. W2 is independent of the apply path. |
| `POST /dp/instances/{id}/apply`, `GET …/health`, `GET …/config-ladder` (W3) | G2 | `ApplyExecutor.execute` and `RuntimeStatus` — the endpoint is a thin authorized caller over what Tasks 8 and 5 build. |
| Frontend wiring (W4) | G2 | The same, one layer further out. |
| `sm-admin provision-demo-operator`, the auth half of the seed (W5) | G2 | Nothing. |
| Rollout / config-state event emission | `BE-D4` amendment (G3) | §3.5's mapping over `ApplyExecutor`'s step transitions — the hooks exist as the executor's own state changes; no code is written for them here. |
| Terminal-outcome wire event | `BE-D4` amendment (§8 row 11) | Same. Until it lands the console must poll the apply status — a v1 limit this plan does not hide. |

---

## Execution-boundary audit

**The rule this section exists to check:** a task must compile and test green using only its own work
and the work of its declared predecessors. An earlier revision of this plan failed it — Task 8
declared `HealthGate`, called `restartAndGate` from Task 9 and `exhausted` from Task 10, and still
claimed a Task-8-only green build. That is not a documentation slip: it means the task could never
have been executed, reviewed or reverted on its own.

### Symbol inventory

`T<n>` is the task that **creates** a symbol. A task may consume only what an earlier row created.

| Task | Creates | Consumes (from) | Modifies |
|---|---|---|---|
| **T1** | `RuntimeAdapter` · `PlacementRef` · `TargetReplica` · `RenderedConfig` · `RenderedConfigSource` · `ConfigLocation`(+`Mechanism`) · `PreflightResult`(`Ready`/`Refused`) · `MaterializeResult` · `LifecycleResult` · `RuntimeStatus` · `Liveness` · `HealthSignal` · `ResolutionResult`(`RolledBack`/`NoEffect`/`Diverged`/`Indeterminate`) · `RuntimeAdapterException` · `ConfigFileStore`(+`Restoration`) | plan 004's `model` package only: `TenantId`, `InstanceId`, `PlacementId`, `ReplicaRef`, `ScopeKey`, `RuntimeRef`, `PreBaseline`, `ErrorClass`, `ReplicaObservation`, `AttemptRow`, `Step`, `Uuid7` | — |
| **T2** | `AbstractRuntimeAdapterContractTest`(+`Fixture`) · `InMemoryRuntimeAdapter`(+`Fault`) · `InMemoryRuntimeAdapterContractTest` | T1 | — |
| **T3** | `DockerEngineClient`(+`PathStat`) · `DockerProperties` · `DockerErrors` | T1 (`RuntimeAdapterException`), `model.ErrorClass` | `dp-core/pom.xml` |
| **T4** | `DockerPlacement` · `DockerPlacementCatalog` · `PropertiesDockerPlacementCatalog` · `LaunchSource` · `DockerAdapter` (ctor + `prepare`) | T1, T3 | — |
| **T5** | — | T1 (`ConfigFileStore`), T3, T4 | `DockerAdapter` (+`materializeConfig`, +`status`) |
| **T6** | — | T1, T3, T4 (incl. its extracted `enumerate`), T5 | `DockerAdapter` (+`startOrUpdate`, +`stop`, +`assessRollback`, +`restore`) |
| **T7** | `DockerEvidenceSecrecyIT` · `DockerAdapterContractIT` | T2 (the kit), T6 (a complete adapter), plan 004's `LogCapture` | `DockerAdapter` **only if a leak is found** |
| **T8** | `ApplyOutcome`(`Landed`/`NotAdmitted`/`StrategyUnsupported`/`ScopeUnsupported`) · `ApplyExecutor`(`execute`, `runManual`, `preflight`, `materialize`, `readBack`, `exhausted`, `verifyRestore`, `closeFailed`, `land`, `idempotencyKey`, private `Plan`, private `StepOutcome`) · `ApplyEngineProperties` · `ManualApplyIT` · `InstanceWideIdentityLimitIT` · `EnsureIntentRaceIT` | **T1 and T2 only**, plus plan 004's `AdmissionService`, `StepIntentService`, `AttemptService`(+`RetryCapExceeded`), `OutcomeService`, repositories and `model` | `ApplyEngineConfiguration` (two beans) · `ApplyEngineHarness` (test accessor) |
| **T9** | `HealthGate` · `GateVerdict`(`Passed`/`Failed`/`Unavailable`) · `HealthGateTest` · `RollingApplyIT` | T1, T2, T8 | `ApplyExecutor` (supported-strategy set, `restartAndGate`, one ctor parameter) · `ApplyEngineConfiguration` · `ApplyEngineProperties` |
| **T10** | `ResumePlanner` · `StepDecision` · `ResumePlannerTest` · `ResumeIT` · `StopDuringApplyIT` | T1, T2, T8 (`exhausted`, `ApplyOutcome`, `idempotencyKey`), T9 (a rolling apply to interrupt) | `ApplyExecutor` (+`resume`, the admitted-stop check) |
| **T11** | 3 `ArchRule`s · 4 `archfixture` classes · the adapter inventory test | T1 (the `runtime` package exists), T3 (`com.github.dockerjava` on the classpath), T8 (the `engine` package's new classes) | `PlaneBoundaryTest` |
| **T12** | `backend/demo/docker-compose.yml` · `backend/demo/README.md` · `CacheApplyWalkthroughIT` | T6 (a complete Docker adapter), T8 (`ApplyExecutor`) | — |
| **T13** | — | every prior task's measured output | `backend/README.md` · `docs/design/backend/decisions.md` · `docs/design/DECISIONS.md` · `docs/tasks/lessons.md` · this plan's Results |

### Green-command closure

Each task's green command must compile against **only** the artifacts above it in this table. Two
tasks are worth checking explicitly, because they are the two where the earlier revision was wrong:

- **T8** — `mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test=ManualApplyIT`. `ApplyExecutor`
  names no `HealthGate`, no `GateVerdict`, no `ResumePlanner`, no `StepDecision`, and no
  `restartAndGate`; its constructor takes seven collaborators, none of them T9's or T10's. A
  non-manual strategy never reaches `runManual`, so there is no rolling behaviour to be missing. The
  exhaustion path is **T8's own** `exhausted`, not T10's. `ManualApplyIT` drives
  `InMemoryRuntimeAdapter` (T2) against a real PostgreSQL, so no Docker adapter class is on its
  compile or run path either.
- **T11** — `mvn -f backend/pom.xml -pl arch-tests -am test`. The rules are phrased over *package
  names*, so they compile whether or not a given class exists; the **fixtures** are what need the
  packages, and both `com.solace.sm.dp.apply.runtime.docker` (T3) and
  `com.solace.sm.dp.apply.engine`'s new classes (T8) exist by then.

**How to check this mechanically at execution time**, per task, before its commit:

```bash
# Everything this task's new/modified sources import from the two engine/runtime packages…
git diff --name-only --diff-filter=AM HEAD~1 -- 'backend/**/*.java' \
  | xargs grep -ho 'com\.solace\.sm\.dp\.apply\.[A-Za-z.]*' | sort -u
# …must appear in this task's row or an earlier one. Read the list; do not count it (L12).
```

---

# Tasks

Every task ends with an independently testable deliverable and one commit. Every task's gate is:

```bash
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify
./contracts/run-checks.sh --strict
git diff --check
```

A task is not done until all three are green **and** its prove-it-fires mutation has been observed red and reverted.

---

### Task 1: The runtime seam's types and the config file store

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/RuntimeAdapter.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/PlacementRef.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/TargetReplica.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/RenderedConfig.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/RenderedConfigSource.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ConfigLocation.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/PreflightResult.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/MaterializeResult.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/LifecycleResult.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/RuntimeStatus.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/Liveness.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/HealthSignal.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/RollbackAssessment.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/RestoreResult.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/RuntimeAdapterException.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ConfigFileStore.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/ConfigFileStoreTest.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/RuntimeSeamTypesTest.java`

**Interfaces:**
- Consumes: `com.solace.sm.dp.apply.model.{TenantId, InstanceId, PlacementId, ReplicaRef, RuntimeRef, PreBaseline, ErrorClass, ReplicaObservation}` — all already shipped by plan 004, none modified.
- Produces: the whole seam. Task 2 implements `RuntimeAdapter`; Tasks 3–7 implement it for Docker; Tasks 8–10 consume it from the engine. `ConfigFileStore.write` returns the hash Task 5 hands to `MaterializeResult` and Task 8 hands to `StepIntentService.ensureIntent` as `configHash`.

- [ ] **Step 1: Write the failing tests**

`ConfigFileStoreTest` (JUnit 5, `@TempDir`):

```java
package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Uuid7;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileStoreTest {

    private final ConfigFileStore store = new ConfigFileStore(3);

    private static RenderedConfig rendered(String yaml) {
        return RenderedConfig.of(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aWriteLandsAtomicallyAndReportsTheHashOfWhatItWrote(@TempDir Path dir) throws Exception {
        RenderedConfig c = rendered("a: 1\n");
        String written = store.write(dir, "application.yml", c, 7);

        assertEquals(c.hash(), written, "the store must report the hash of the bytes it was given");
        assertEquals("a: 1\n", Files.readString(dir.resolve("application.yml")));
        assertEquals(written, store.readBackHash(dir.resolve("application.yml")).orElseThrow(),
                "read-back must agree with the write");
        assertTrue(Files.list(dir).noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                "no temp file may survive a successful write");
    }

    /**
     * The hash this store produces is handed straight to AttemptRow.observedHash and to
     * ApplyResolutionEvidence, both of which validate against ValueGrammar.HASH. Asserting the
     * grammar with a second copy of the regex here would prove only that two regexes agree; handing
     * the value to the type that owns the rule proves the thing that matters.
     */
    @Test
    void theHashIsAcceptedByTheModelTypesThatPersistIt(@TempDir Path dir) {
        String h = store.write(dir, "application.yml", rendered("a: 1\n"), 7);
        AttemptRow row = new AttemptRow(Uuid7.next(), Uuid7.next(), Step.MATERIALIZE, (short) 1,
                null, Instant.EPOCH, null, Instant.EPOCH.plusSeconds(60), null, h, null, null);
        assertEquals(h, row.observedHash());
    }

    @Test
    void readBackOfAnAbsentFileIsEmptyRatherThanAnException(@TempDir Path dir) {
        assertTrue(store.readBackHash(dir.resolve("nothing.yml")).isEmpty());
    }

    @Test
    void restoringReturnsTheFileToWhatItHeldBeforeThatGenerationWasWritten(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("application.yml"), "original: true\n");

        store.write(dir, "application.yml", rendered("generation: 8\n"), 8);
        assertEquals("generation: 8\n", Files.readString(dir.resolve("application.yml")));

        assertEquals(ConfigFileStore.Restoration.RESTORED,
                store.restore(dir, "application.yml", 8, originalHash));
        assertEquals("original: true\n", Files.readString(dir.resolve("application.yml")),
                "rollback restores the PRIOR persisted config, which SM did not write");
    }

    @Test
    void restoringAGenerationWrittenOverNothingRemovesTheFile(@TempDir Path dir) throws Exception {
        store.write(dir, "application.yml", rendered("generation: 9\n"), 9);

        assertEquals(ConfigFileStore.Restoration.RESTORED,
                store.restore(dir, "application.yml", 9, ConfigFileStore.ABSENT_TARGET));
        assertFalse(Files.exists(dir.resolve("application.yml")),
                "the prior state was ABSENT, and restoring to absent is a restoration, not a no-op");
    }

    @Test
    void restoringAGenerationThisStoreNeverWroteReportsNothingToRestore(@TempDir Path dir) {
        assertEquals(ConfigFileStore.Restoration.NOTHING_TO_RESTORE,
                store.restore(dir, "application.yml", 11, "sha256:" + "0".repeat(64)),
                "an untouched target is FAILED_NO_EFFECT's evidence, never a silent success");
    }

    /**
     * BE-D22's read half: the engine must learn the restore target's hash BEFORE it journals a
     * ROLLBACK intent, and learning it must not restore anything.
     */
    @Test
    void thePreImageCanBeReadWithoutRestoringIt(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), "original: true\n");
        String originalHash = store.readBackHash(dir.resolve("application.yml")).orElseThrow();
        store.write(dir, "application.yml", rendered("generation: 8\n"), 8);

        assertEquals(ConfigFileStore.PreImage.PRESENT, store.preImageKind(dir, "application.yml", 8));
        assertEquals(originalHash, store.preImageHash(dir, "application.yml", 8).orElseThrow());
        assertEquals("generation: 8\n", Files.readString(dir.resolve("application.yml")),
                "reading the pre-image must not put it back — that is the whole point of the split");
    }

    /** The case hash_on_config_steps cannot express: a restore target with no hash. */
    @Test
    void aPreImageOfAbsenceIsReportedAsAbsentAndHasNoHash(@TempDir Path dir) {
        store.write(dir, "application.yml", rendered("generation: 9\n"), 9);

        assertEquals(ConfigFileStore.PreImage.ABSENT, store.preImageKind(dir, "application.yml", 9));
        assertTrue(store.preImageHash(dir, "application.yml", 9).isEmpty());
        assertEquals(ConfigFileStore.PreImage.MISSING,
                store.preImageKind(dir, "application.yml", 99));
    }

    @Test
    void onlyTheConfiguredNumberOfPreImagesIsRetained(@TempDir Path dir) throws Exception {
        for (long g = 1; g <= 5; g++) {
            store.write(dir, "application.yml", rendered("generation: " + g + "\n"), g);
        }
        List<String> preImages = Files.list(dir)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.contains(".sm-pre-"))
                .sorted()
                .toList();
        assertEquals(List.of(".application.yml.sm-pre-3", ".application.yml.sm-pre-4",
                ".application.yml.sm-pre-5"), preImages,
                "B/D12 retains the last N generations; the oldest go first");
    }
}
```

`RuntimeSeamTypesTest`:

```java
package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeSeamTypesTest {

    @Test
    void aPlacementRefRefusesTheInstanceWideSentinel() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new PlacementRef(new TenantId(UUID.randomUUID()),
                        new InstanceId(UUID.randomUUID()), PlacementId.INSTANCE_WIDE));
        assertTrue(e.getMessage().contains("instance-wide"),
                "an adapter is always addressed at a real placement; the sentinel is the engine's");
    }

    @Test
    void aRenderedConfigRefusesAHashThatDoesNotDescribeItsBytes() {
        byte[] bytes = "a: 1\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> new RenderedConfig(bytes, "sha256:" + "0".repeat(64)));
    }

    @Test
    void aRenderedConfigCopiesItsBytesInBothDirections() {
        byte[] bytes = "a: 1\n".getBytes(StandardCharsets.UTF_8);
        RenderedConfig c = RenderedConfig.of(bytes);
        bytes[0] = 'z';
        assertEquals('a', c.bytes()[0], "the record must not alias the caller's array");
        c.bytes()[0] = 'z';
        assertEquals('a', c.bytes()[0], "nor hand its own array out");
    }

    /**
     * I6, at the other boundary. PgConstraintViolation carries four classifiers and no cause
     * because Logback prints cause chains; an adapter exception that wrapped a Docker client
     * exception would reintroduce exactly that leak, one layer out.
     */
    @Test
    void aRuntimeAdapterExceptionCarriesAClassifierAndNoCause() {
        RuntimeAdapterException e =
                new RuntimeAdapterException(new ErrorClass("docker.api.unreachable"));
        assertNull(e.getCause(), "an adapter exception never carries a driver or client cause");
        assertEquals("docker.api.unreachable", e.errorClass().token());
        assertEquals("docker.api.unreachable", e.getMessage(),
                "the message is the classifier, so there is nothing else in it to leak");
    }

    @Test
    void theSevenOperationsAreExactlyTheSevenTheDecisionsName() {
        assertEquals(java.util.Set.of("prepare", "materializeConfig", "startOrUpdate", "stop",
                        "status", "assessRollback", "restore"),
                java.util.Arrays.stream(RuntimeAdapter.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(java.util.stream.Collectors.toSet()),
                "BE-D12 §2.1 fixed the protocol at six; BE-D22 split the sixth in two, because "
                        + "BE-D13 §3.1 already carried two journal steps for it. An EIGHTH is a "
                        + "design change, not an implementation detail — and a health-gate verb "
                        + "specifically was REJECTED, because a gate is a policy over observations.");
        assertSame(RuntimeAdapter.class, RuntimeAdapter.class);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -f backend/pom.xml -pl dp-core -am test -Dtest='ConfigFileStoreTest,RuntimeSeamTypesTest'`
Expected: **compilation failure** — none of the seam types exist yet.

- [ ] **Step 3: Write the seam types**

`RuntimeAdapter.java` — the protocol, and the only type the engine knows an adapter by:

```java
package com.solace.sm.dp.apply.runtime;

/**
 * The `dp-remote` apply protocol — {@code BE-D12} §2.1's runtime-neutral operations, as amended
 * by {@code BE-D21} (parameter types) and {@code BE-D22} (the sixth operation split in two).
 *
 * <p>The core apply engine speaks only these six; everything runtime-specific lives behind this
 * seam. Lifecycle and observation are <b>replica-scoped</b>, because {@code SM-D15}'s strategies are
 * per-replica by construction and an instance-scoped verb cannot say "restart replica 2, wait, then
 * replica 3".
 *
 * <p><b>There is deliberately no {@code healthGate} operation.</b> A gate is a policy over
 * observations — the core's — expressed through repeated {@link #status} calls under a bounded
 * deadline ({@link com.solace.sm.dp.apply.engine.HealthGate}), not a runtime action.
 *
 * <p>Every implementation must pass {@code AbstractRuntimeAdapterContractTest}. An implementation
 * without a subclass of that kit is not certified, and {@code PlaneBoundaryTest}'s adapter inventory
 * is where that promise is recorded.
 */
public interface RuntimeAdapter {

    /**
     * Per-placement pre-flight, run <b>before every apply</b>, never once at enrollment
     * ({@code SM-D21}'s round-2 correction: launch command, mount and competing-source set can all
     * change between applies). Resolves the declared config location, verifies reachability,
     * permissions and writer ownership, detects competing sources, and captures the replica set the
     * apply will address. <b>Never mutates the target.</b>
     */
    PreflightResult prepare(PlacementRef placement, long generation);

    /**
     * Persists generation {@code G}'s rendered bytes durably at the declared location for
     * <b>one replica</b>, and returns the hash of what it wrote. <b>Zero lifecycle effect</b> —
     * writing configuration never restarts anything ({@code SM-D15}/{@code AG-D3}).
     */
    MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config);

    /**
     * Makes one replica reflect the materialized generation by executing the declared lifecycle verb
     * ({@code SM-D19}). Invoked <b>only</b> under an operator-selected restart strategy: under the
     * default {@code manual} the core never calls this. <b>Never pulls, replaces or re-tags an
     * artifact</b> — deployment is the customer's CI/CD ({@code SM-D2}, {@code I1}).
     */
    LifecycleResult startOrUpdate(TargetReplica replica, long generation);

    /** The declared stop verb on one replica, on explicit operator command only. */
    LifecycleResult stop(TargetReplica replica);

    /**
     * Observes one replica: liveness, health signal, the persisted-config hash read back at the
     * declared path, and the start baseline {@code restart-observed} detection compares against.
     * Read-only and safe to call at any time.
     */
    RuntimeStatus status(TargetReplica replica);

    /**
     * The bounded-retry exhaustion path's <b>read-only</b> half ({@code BE-D22}), placement-scoped.
     * Concludes what the failure left behind and whether a restore target exists, and <b>mutates
     * nothing</b> — the obligation {@code BE-D13} §3.1 assigns to "adapter contract tests, per
     * adapter", and the reason the engine can journal it as the hashless {@code ROLLBACK_ASSESS}
     * step and re-run it freely after a crash.
     */
    RollbackAssessment assessRollback(PlacementRef placement, long generation, String intendedHash);

    /**
     * The exhaustion path's <b>destructive</b> half ({@code BE-D22}): restore the prior persisted
     * config on every replica of the placement, to the hash {@link RollbackAssessment.Restorable}
     * named. Idempotent under the same key — writing identical bytes twice is the same write.
     *
     * <p>The engine opens a {@code ROLLBACK} intent carrying {@code targetHash} as its
     * {@code config_hash} <b>before</b> calling this, which is what makes resume decidable: a crash
     * in the window is resolved by reading each replica's hash back against the intent's own, never
     * by guessing whether the restore occurred.
     */
    RestoreResult restore(PlacementRef placement, long generation, String targetHash);
}
```

`PlacementRef.java`:

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.Objects;

/**
 * A real placement — {@link ScopeKey} without the instance-wide sentinel.
 *
 * <p>The engine addresses scopes, which may be instance-wide; an adapter is always addressed at one
 * placement. In v1 an instance-wide request never reaches this type at all: {@code BE-D23} makes
 * {@code ApplyExecutor} refuse it before admission, because {@code apply_step_intent}'s identity
 * cannot hold two placements' placement-scoped steps under one {@code apply_id}. This refusal is
 * the SECOND line — the first is {@code ApplyExecutor}'s own check, which fails gracefully; this
 * one throws.
 */
public record PlacementRef(TenantId tenant, InstanceId instance, PlacementId placement) {

    public PlacementRef {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(placement, "placement");
        if (placement.isInstanceWide()) {
            throw new IllegalArgumentException("an adapter is addressed at a real placement, never "
                    + "at the instance-wide sentinel — BE-D23 refuses an instance-wide request "
                    + "before admission, so one reaching here is an engine defect, not input");
        }
    }

    public ScopeKey scope() {
        return new ScopeKey(tenant, instance, placement);
    }
}
```

`TargetReplica.java`:

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ReplicaRef;
import java.util.Objects;

/**
 * One replica, with the placement it belongs to — {@code BE-D12} §2.1's "{@code ReplicaRef} carries
 * its {@code PlacementRef}; adapters never see bare strings".
 *
 * <p>Expressed as a pair rather than as a field on {@link ReplicaRef} deliberately: {@code ReplicaRef}
 * is grammar-mirrored into {@code resolution_is_evidence} by {@code BE-D19} and serialized as the
 * {@code ref} key that {@code BE-D18}'s element key-set predicate closes over, so a second component
 * would change a wire shape the database now enforces. This shape is {@code BE-D21}, which amends
 * {@code BE-D12} §2.1's printed signature by cross-reference (backend/decisions.md).
 */
public record TargetReplica(PlacementRef placement, ReplicaRef ref) {

    public TargetReplica {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(ref, "replica ref");
        if (ref.isPlacement()) {
            throw new IllegalArgumentException("the @placement sentinel names the placement-scoped "
                    + "steps (PREFLIGHT, ROLLBACK, ROLLBACK_ASSESS), not a replica to act on");
        }
    }
}
```

`RenderedConfig.java`:

```java
package com.solace.sm.dp.apply.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Generation {@code G}'s rendered bytes, with the hash of exactly those bytes.
 *
 * <p>One owner for the hash. The engine binds it into {@code apply_step_intent.config_hash} before
 * the adapter is invoked (§2.2 rule 0), and the adapter reports it back from
 * {@link RuntimeAdapter#materializeConfig}; a second, independent hashing would let those two
 * disagree about what was written, which is precisely the disagreement the intent row exists to make
 * impossible across a crash.
 */
public record RenderedConfig(byte[] bytes, String hash) {

    public RenderedConfig {
        Objects.requireNonNull(bytes, "rendered bytes");
        Objects.requireNonNull(hash, "hash");
        bytes = bytes.clone();
        String actual = sha256(bytes);
        if (!actual.equals(hash)) {
            throw new IllegalArgumentException("the hash does not describe these bytes — one owner "
                    + "computes it (RenderedConfig.of) and nobody supplies it. The offending values "
                    + "are deliberately not repeated here (I6)");
        }
    }

    public static RenderedConfig of(byte[] bytes) {
        return new RenderedConfig(bytes, sha256(bytes));
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK's own MessageDigest contract; this branch is
            // unreachable on any conformant JVM and is not a caller-recoverable failure.
            throw new IllegalStateException("SHA-256 is not available on this JVM");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RenderedConfig c && Arrays.equals(bytes, c.bytes) && hash.equals(c.hash);
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    /** Length only — the bytes are configuration and never render into a log line ({@code I6}). */
    @Override
    public String toString() {
        return "RenderedConfig[" + bytes.length + " bytes, " + hash + "]";
    }
}
```

`RenderedConfigSource.java`:

```java
package com.solace.sm.dp.apply.runtime;

/**
 * Where the engine gets generation {@code G}'s rendered bytes.
 *
 * <p>A seam rather than a parameter because <b>resume</b> needs it: an intent with no attempt row
 * provably issued nothing (§2.2 rule 0) and must be re-run deterministically, which means
 * re-rendering after a process restart that kept nothing in memory.
 */
@FunctionalInterface
public interface RenderedConfigSource {

    RenderedConfig render(PlacementRef placement, long generation);
}
```

`ConfigLocation.java`:

```java
package com.solace.sm.dp.apply.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The resolved config location for one placement — {@code SM-D21}'s "per-artifact, per-runtime,
 * discovered by pre-flight", in both views that matter.
 *
 * @param containerPath the directory the workload itself reads, as the workload sees it
 * @param localDir      the same directory as the SM process sees it — the two differ whenever SM
 *                      runs in a container of its own, which the demo compose does
 * @param fileName      the file SM owns inside that directory
 * @param mechanism     {@code SM-D3}'s declared mechanism
 */
public record ConfigLocation(String containerPath, Path localDir, String fileName,
        Mechanism mechanism) {

    public enum Mechanism { ADDITIONAL_LOCATION, OVERWRITE_IN_PLACE }

    public ConfigLocation {
        Objects.requireNonNull(containerPath, "container path");
        Objects.requireNonNull(localDir, "local dir");
        Objects.requireNonNull(fileName, "file name");
        Objects.requireNonNull(mechanism, "mechanism");
        if (fileName.contains("/") || fileName.contains("\\") || fileName.isBlank()) {
            throw new IllegalArgumentException("the config file name is a single path segment");
        }
    }

    public Path localFile() {
        return localDir.resolve(fileName);
    }

    public String containerFile() {
        return containerPath.endsWith("/") ? containerPath + fileName : containerPath + "/" + fileName;
    }
}
```

`PreflightResult.java`, `MaterializeResult.java`, `LifecycleResult.java`, `RuntimeStatus.java`, `Liveness.java`, `HealthSignal.java`, `RollbackAssessment.java`, `RestoreResult.java`:

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.util.List;

/** A plan or a refusal. {@code prepare} never mutates, so a refusal leaves nothing behind. */
public sealed interface PreflightResult {

    record Ready(List<TargetReplica> replicas, ConfigLocation location,
            boolean healthSignalDeclared, RuntimeRef runtimeRef) implements PreflightResult {

        public Ready {
            if (replicas == null || replicas.isEmpty()) {
                throw new IllegalArgumentException("a ready pre-flight names at least one replica; "
                        + "an empty set is a refusal, not a plan with nothing in it");
            }
            replicas = List.copyOf(replicas);
        }
    }

    record Refused(ErrorClass reason, RuntimeRef runtimeRef) implements PreflightResult {}
}
```

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.RuntimeRef;

/** What {@code materializeConfig} wrote, and where. */
public record MaterializeResult(String writtenHash, RuntimeRef runtimeRef) {}
```

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.RuntimeRef;

/**
 * @param issued whether the runtime was actually asked to act. {@code false} is the idempotent case
 *               §2.2 rule 2 requires an adapter to report as success — a replica already at the
 *               desired state, measured on Docker as HTTP {@code 304} from {@code /stop}.
 */
public record LifecycleResult(boolean issued, RuntimeRef runtimeRef) {}
```

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.util.Objects;
import java.util.Optional;

/**
 * One replica, observed.
 *
 * @param observedHash the hash read back at the declared path, or {@code null} when nothing is there
 * @param baseline     the process/container start baseline, or {@code null} when it cannot be read —
 *                     which §2.2 rule 3 makes the {@code APPLY_INDETERMINATE} trigger, never a
 *                     reason to re-issue a restart the operator may not have authorized twice
 */
public record RuntimeStatus(Liveness liveness, HealthSignal health, String observedHash,
        PreBaseline baseline, RuntimeRef runtimeRef) {

    public RuntimeStatus {
        Objects.requireNonNull(liveness, "liveness");
        Objects.requireNonNull(health, "health signal");
        Objects.requireNonNull(runtimeRef, "runtime ref");
    }

    public Optional<String> hash() {
        return Optional.ofNullable(observedHash);
    }

    public Optional<PreBaseline> startBaseline() {
        return Optional.ofNullable(baseline);
    }
}
```

```java
package com.solace.sm.dp.apply.runtime;

/** Whether the replica is there at all, and running. */
public enum Liveness { RUNNING, STOPPED, ABSENT }
```

```java
package com.solace.sm.dp.apply.runtime;

/**
 * What the runtime says about the replica's health.
 *
 * <p>{@link #NOT_DECLARED} is the honest answer when the runtime publishes no health signal at all —
 * measured on Docker as the absence of the {@code State.Health} key when the image and the launch
 * spec declare no healthcheck. It is <b>not</b> {@link #UNHEALTHY}, and it is not silently treated as
 * {@link #HEALTHY}: {@code HealthGate} refuses to run a health-gated strategy against it, because a
 * gate with no signal is a guard that cannot fail ({@code L7}).
 */
public enum HealthSignal { HEALTHY, UNHEALTHY, STARTING, NOT_DECLARED }
```

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ErrorClass;

/**
 * What a <b>read-only</b> rollback assessment concluded ({@code BE-D22}).
 *
 * <p><b>No observation list, deliberately.</b> An earlier draft returned per-replica observations
 * here and the plan described them as the evidence for {@code CONFIG_DIVERGED}; they were discarded
 * the moment the engine branched on them. Per-replica evidence is <b>rows</b> — each replica's
 * {@code MATERIALIZE} attempt, and the {@code VERIFY} attempts the engine issues after a restore —
 * exactly as §3.2's {@code CONFIG_DIVERGED} row says ("per-replica evidence in {@code attempt} rows
 * and {@code resolution_observed}"). A value that cannot be read back after the process exits is not
 * evidence, and giving it a field invited the claim that it was.
 */
public sealed interface RollbackAssessment {

    /**
     * A prior config exists and hashes to {@code targetHash} → the engine may open a
     * {@code ROLLBACK} intent carrying it and call {@link RuntimeAdapter#restore}.
     */
    record Restorable(String targetHash) implements RollbackAssessment {
        public Restorable {
            java.util.Objects.requireNonNull(targetHash, "target hash");
        }
    }

    /** The target was never touched → {@code FAILED_NO_EFFECT}. */
    record NothingToRestore() implements RollbackAssessment {}

    /**
     * The assessment cannot decide, or can decide but cannot be journalled →
     * {@code APPLY_INDETERMINATE}.
     *
     * <p>Classifiers this plan produces: {@code rollback.state.unreadable} (a replica's hash could
     * not be read), {@code rollback.state.unknown} (a replica holds a value this apply never wrote —
     * neither the rollback target nor its own {@code MATERIALIZE} hash — so somebody else did, and
     * {@code B/D12} forbids reverting them), and {@code rollback.target.absent} — the prior state was the ABSENCE of a
     * file, so there is no hash for a {@code ROLLBACK} intent's {@code config_hash} and
     * {@code hash_on_config_steps} makes the restore unjournallable. §2.2 rule 0 forbids acting
     * without the journal entry, so the engine stops and asks the operator. Defined and tested, not
     * an unhandled path (`BE-D22`).
     */
    record Unknowable(ErrorClass why) implements RollbackAssessment {}
}
```

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.RuntimeRef;

/**
 * What a destructive restore touched ({@code BE-D22}).
 *
 * <p>Carries no per-replica outcome, for the same reason {@link RollbackAssessment} carries no
 * observations: the engine issues one {@code VERIFY} step per replica afterwards, and those attempt
 * rows are the record.
 */
public record RestoreResult(RuntimeRef runtimeRef) {}
```

`RuntimeAdapterException.java`:

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ErrorClass;
import java.util.Objects;

/**
 * An adapter could not complete an operation. Carries a <b>classifier and no cause</b>.
 *
 * <p>{@code I6}, at the adapter boundary. Plan 004 measured the same hole one layer in:
 * {@code PgConstraintViolation} had a sanitized message but still carried the {@code PSQLException}
 * as its cause, and Logback prints cause chains, so {@code log.error(msg, ex)} re-emitted
 * {@code DETAIL: Failing row contains (…)}. A Docker client exception or an HTTP response body
 * wrapped here would reintroduce that leak from the other side — the daemon echoes container names,
 * mount paths and, on some errors, command lines.
 */
public final class RuntimeAdapterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorClass errorClass;

    public RuntimeAdapterException(ErrorClass errorClass) {
        super(Objects.requireNonNull(errorClass, "error class").token(), null, true, false);
        this.errorClass = errorClass;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }
}
```

`ConfigFileStore.java`:

```java
package com.solace.sm.dp.apply.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable config writes and their rollback pre-images. Runtime-neutral: the Docker adapter writes
 * through a bind mount and a future systemd adapter writes to a real host directory, and the file
 * semantics are identical.
 *
 * <p><b>temp → fsync → rename</b>, per {@code BE-D12} §2.1's binding rules, so a crash mid-write
 * leaves either the old file or the new one and never a half-written one.
 *
 * <p><b>Pre-images, not generation copies.</b> Rollback must restore "the prior persisted config"
 * ({@code B/D12} 🔒), and on a first apply that prior config is one SM never wrote — or nothing at
 * all. So each write snapshots what it is about to replace, keyed by the generation replacing it;
 * restoring generation {@code G} puts back exactly what was there before {@code G} landed, including
 * <b>absence</b>. Keeping N generations of SM's own output instead would have no answer for the first
 * apply, which is the case rollback is most likely to be asked about.
 *
 * <p>POSIX hosts only in v1: the directory fsync opens the directory for read, which Windows refuses.
 * {@code dp-remote}'s v1 runtimes (Docker on Linux/macOS, systemd) are all POSIX.
 */
public final class ConfigFileStore {

    private static final String PRE_IMAGE_INFIX = ".sm-pre-";
    private static final String ABSENT_MARKER = ".absent";

    private final int retainedGenerations;

    public ConfigFileStore(int retainedGenerations) {
        if (retainedGenerations < 1) {
            throw new IllegalArgumentException(
                    "at least one pre-image must be retained, or rollback has nothing to restore");
        }
        this.retainedGenerations = retainedGenerations;
    }

    /** What {@link #restore} did. */
    public enum Restoration { RESTORED, NOTHING_TO_RESTORE, TARGET_MOVED }

    /**
     * What {@link #restore} WOULD put back, without putting it back — the read half {@code BE-D22}
     * needs so the engine can journal a {@code ROLLBACK} intent's {@code config_hash} BEFORE the
     * destructive call.
     *
     * <p>{@link #ABSENT} is the case {@code hash_on_config_steps} cannot express: there was no file,
     * so a restore has no target hash to journal.
     */
    public enum PreImage { PRESENT, ABSENT, MISSING }

    public String hashOf(byte[] bytes) {
        return RenderedConfig.sha256(bytes);
    }

    /** Whether generation {@code G}'s pre-image is a file, a recorded absence, or not there at all. */
    public PreImage preImageKind(Path dir, String fileName, long generation) {
        Path preImage = preImage(dir, fileName, generation);
        if (Files.isRegularFile(preImage)) {
            return PreImage.PRESENT;
        }
        return Files.isRegularFile(Path.of(preImage + ABSENT_MARKER))
                ? PreImage.ABSENT
                : PreImage.MISSING;
    }

    /** The hash a restore of generation {@code G} would land, or empty unless the kind is PRESENT. */
    public Optional<String> preImageHash(Path dir, String fileName, long generation) {
        return preImageKind(dir, fileName, generation) == PreImage.PRESENT
                ? readBackHash(preImage(dir, fileName, generation))
                : Optional.empty();
    }

    /** The hash of what is at {@code file} now, or empty when nothing is. */
    public Optional<String> readBackHash(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(hashOf(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw io(e);
        }
    }

    /**
     * Snapshots what is there, then lands {@code config} atomically. Returns the hash of the bytes
     * written — the same value {@link RenderedConfig#hash()} already holds, returned so the caller
     * reports what the FILE now has rather than what it intended.
     */
    public String write(Path dir, String fileName, RenderedConfig config, long generation) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            capturePreImage(dir, fileName, target, generation);

            Path tmp = dir.resolve("." + fileName + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.write(tmp, config.bytes(), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    ch.force(true);
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            fsyncDirectory(dir);
            prunePreImages(dir, fileName);
            return readBackHash(target).orElseThrow(() -> new IllegalStateException(
                    "the file is not readable immediately after an atomic rename"));
        } catch (IOException e) {
            throw io(e);
        }
    }

    /**
     * Puts back whatever was there before {@code generation} landed — including nothing — but
     * <b>only if the pre-image still hashes to {@code expectedHash}</b>, the value the assessment
     * recorded on the {@code ROLLBACK} intent.
     *
     * <p>{@code BE-D22} correction B: without this check the hash on the intent would be a claim
     * about a file nobody re-read, and a restore could overwrite live configuration with bytes that
     * are no longer the recorded target. A pre-image that vanished or changed between assess and
     * restore means the world moved, so this writes <b>nothing</b> and says which.
     */
    public Restoration restore(Path dir, String fileName, long generation, String expectedHash) {
        try {
            Path preImage = preImage(dir, fileName, generation);
            Path target = dir.resolve(fileName);
            if (Files.isRegularFile(preImage)) {
                if (!readBackHash(preImage).map(expectedHash::equals).orElse(false)) {
                    return Restoration.TARGET_MOVED;    // nothing written
                }
                Files.copy(preImage, target, StandardCopyOption.REPLACE_EXISTING);
                fsyncDirectory(dir);
                return Restoration.RESTORED;
            }
            if (Files.isRegularFile(Path.of(preImage + ABSENT_MARKER))) {
                Files.deleteIfExists(target);
                fsyncDirectory(dir);
                return Restoration.RESTORED;
            }
            return Restoration.NOTHING_TO_RESTORE;
        } catch (IOException e) {
            throw io(e);
        }
    }

    private void capturePreImage(Path dir, String fileName, Path target, long generation)
            throws IOException {
        Path preImage = preImage(dir, fileName, generation);
        if (Files.isRegularFile(target)) {
            Files.copy(target, preImage, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(preImage);
            Files.write(Path.of(preImage + ABSENT_MARKER), new byte[0]);
        }
    }

    private Path preImage(Path dir, String fileName, long generation) {
        return dir.resolve("." + fileName + PRE_IMAGE_INFIX + generation);
    }

    private void prunePreImages(Path dir, String fileName) throws IOException {
        String prefix = "." + fileName + PRE_IMAGE_INFIX;
        List<Path> all;
        try (var stream = Files.list(dir)) {
            all = stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparingLong(ConfigFileStore::generationOf))
                    .toList();
        }
        // Both an image and its .absent marker share a generation, so prune by DISTINCT generation
        // rather than by file count: counting files would retain half as many generations as
        // configured whenever the two forms are mixed.
        List<Long> generations = all.stream().map(ConfigFileStore::generationOf).distinct().sorted()
                .toList();
        if (generations.size() <= retainedGenerations) {
            return;
        }
        List<Long> doomed = generations.subList(0, generations.size() - retainedGenerations);
        for (Path p : all) {
            if (doomed.contains(generationOf(p))) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static long generationOf(Path p) {
        String name = p.getFileName().toString();
        String tail = name.substring(name.lastIndexOf(PRE_IMAGE_INFIX) + PRE_IMAGE_INFIX.length());
        if (tail.endsWith(ABSENT_MARKER)) {
            tail = tail.substring(0, tail.length() - ABSENT_MARKER.length());
        }
        return Long.parseLong(tail);
    }

    private static void fsyncDirectory(Path dir) throws IOException {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    /**
     * A filesystem failure is a corruption report, not a caller-recoverable one, and it carries no
     * path: a bind-mount source is exactly the kind of value {@code RuntimeRef.MOUNT_PATH} is
     * sentinel-tested for in Task 7.
     */
    private static UncheckedIOException io(IOException e) {
        return new UncheckedIOException(
                "a config-store operation failed; the path is deliberately not repeated here (I6)",
                new IOException(e.getClass().getSimpleName()));
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -f backend/pom.xml -pl dp-core -am test -Dtest='ConfigFileStoreTest,RuntimeSeamTypesTest'`
Expected: PASS, **14 tests** (`ConfigFileStoreTest` 9, `RuntimeSeamTypesTest` 5).

- [ ] **Step 5: Prove the guards fire**

Three mutations, each observed red and then reverted:

| Mutation | Expected red |
|---|---|
| In `ConfigFileStore.capturePreImage`, drop the `else` branch (no `.absent` marker) | `restoringAGenerationWrittenOverNothingRemovesTheFile` — 1 red |
| In `prunePreImages`, count **files** instead of distinct generations (`all.size() <= retainedGenerations`) | `onlyTheConfiguredNumberOfPreImagesIsRetained` — 1 red, and the message shows 5 pre-images retained where 3 were configured |
| In `RenderedConfig`'s compact constructor, drop the hash comparison | `aRenderedConfigRefusesAHashThatDoesNotDescribeItsBytes` — 1 red |
| In `preImageKind`, return `PRESENT` for the `.absent` marker | `aPreImageOfAbsenceIsReportedAsAbsentAndHasNoHash` — 1 red, and it matters: that branch is what routes `rollback.target.absent` to `APPLY_INDETERMINATE` instead of journalling a `ROLLBACK` with no hash |
| In `preImageHash`, read the *target* file instead of the pre-image | `thePreImageCanBeReadWithoutRestoringIt` — 1 red: the engine would journal the hash it is replacing the file *with* as the hash it is restoring *to* |

Record the observed counts in the commit message.

- [ ] **Step 6: Commit**

```bash
git add backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime \
        backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime
git commit -m "$(cat <<'MSG'
Land the RuntimeAdapter seam's types and the config file store (Task 1)

BE-D12 §2.1's operations as an interface -- seven, per BE-D22 -- the runtime-neutral
result types,
and temp->fsync->rename with generation-keyed pre-images so rollback can restore
a prior config SM never wrote -- including absence.

The signature is BE-D21's, not BE-D12 §2.1's printed block: TargetReplica
rather than a widened ReplicaRef (BE-D18/BE-D19 enforce that wire shape at the
database) and a caller-owned RenderedConfig (an adapter that rendered its own
bytes would be a second renderer with no goldens watching it). The decision is
in the register and §2.1 carries the flag; this commit implements it.

Prove-it-fires: pre-image absent-marker dropped -> 1 red; prune counted files
rather than generations -> 1 red; RenderedConfig hash check dropped -> 1 red.
MSG
)"
```

**Risks:** the directory fsync is POSIX-only and will throw on Windows. Stated in the class javadoc and accepted: `dp-remote`'s v1 runtimes are all POSIX. If a Windows host ever matters, that is a new adapter concern, not a change to this store.

---

### Task 2: The adapter contract kit, and the in-memory adapter that proves it fires

Plan 004 declined to declare `RuntimeAdapter` at all, on the stated ground that *"with no implementation and no engine to call it, an interface here would be an untested declaration (`L7`)"*. This task is that objection's answer: the interface ships with an executable contract every implementation must pass, and with one implementation that passes it — so the seam is a tested obligation from its first commit, not a declaration waiting for Docker.

**Files:**
- Create: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/AbstractRuntimeAdapterContractTest.java`
- Create: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/InMemoryRuntimeAdapter.java`
- Create: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/InMemoryRuntimeAdapterContractTest.java`
- Test: the three above; no production file changes.

**Interfaces:**
- Consumes: every type from Task 1.
- Produces: `AbstractRuntimeAdapterContractTest` with one abstract factory method — `protected abstract Fixture newFixture()` returning `record Fixture(RuntimeAdapter adapter, PlacementRef placement, List<TargetReplica> replicas, ConfigLocation location, boolean healthSignalDeclared)`. Task 7 Step 8 subclasses it as `DockerAdapterContractIT`. `InMemoryRuntimeAdapter` is consumed by Tasks 8–10's fast ITs, which need an adapter that can be driven into failure states a real daemon will not reproduce on demand.

- [ ] **Step 1: Write the contract kit (it is the failing test)**

```java
package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.PreBaseline;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The obligations every {@link RuntimeAdapter} owes, regardless of runtime — the
 * {@code AbstractEventPublisherContractTest} idiom (design §5.2), applied to the second seam this
 * backend has.
 *
 * <p>A new adapter ships <b>with</b> a subclass of this class. {@code PlaneBoundaryTest}'s adapter
 * inventory records the promise; this class is what makes it cost something to break.
 */
public abstract class AbstractRuntimeAdapterContractTest {

    /** Everything the kit needs to exercise one real placement of the adapter under test. */
    public record Fixture(RuntimeAdapter adapter, PlacementRef placement,
            List<TargetReplica> replicas, ConfigLocation location, boolean healthSignalDeclared) {}

    /** A fresh, isolated fixture per test. Implementations create and clean their own runtime. */
    protected abstract Fixture newFixture() throws Exception;

    private static RenderedConfig config(String yaml) {
        return RenderedConfig.of(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void prepareIsReadyAndNamesEveryReplicaItWillAddress() throws Exception {
        Fixture f = newFixture();
        PreflightResult.Ready ready =
                assertInstanceOf(PreflightResult.Ready.class, f.adapter().prepare(f.placement(), 1));
        assertEquals(f.replicas(), ready.replicas());
        assertEquals(f.healthSignalDeclared(), ready.healthSignalDeclared(),
                "the gate's own precondition is an observation, not a configuration flag");
        assertNotNull(ready.runtimeRef());
    }

    @Test
    void prepareNeverMutatesTheTarget() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RuntimeStatus before = f.adapter().status(r);
        f.adapter().prepare(f.placement(), 1);
        RuntimeStatus after = f.adapter().status(r);
        assertEquals(before.liveness(), after.liveness());
        assertEquals(before.observedHash(), after.observedHash());
        assertEquals(before.startBaseline().map(PreBaseline::startedAt),
                after.startBaseline().map(PreBaseline::startedAt),
                "prepare returns a plan or a refusal; it is not a lifecycle operation");
    }

    @Test
    void materializeWritesTheBytesAndReportsTheirHash() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig c = config("materialized: true\n");

        MaterializeResult result = f.adapter().materializeConfig(r, 4, c);

        assertEquals(c.hash(), result.writtenHash());
        assertEquals(c.hash(), f.adapter().status(r).hash().orElseThrow(),
                "status must read back what materialize wrote, or the ladder is fiction");
    }

    /** {@code SM-D15}/{@code AG-D3}: writing configuration never restarts anything. Ever. */
    @Test
    void materializeHasZeroLifecycleEffect() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        PreBaseline before = f.adapter().status(r).startBaseline().orElseThrow();

        f.adapter().materializeConfig(r, 4, config("materialized: true\n"));

        assertEquals(before, f.adapter().status(r).startBaseline().orElseThrow(),
                "the start baseline must be byte-identical: a config write that moved it would make "
                        + "restart-observed unfalsifiable");
    }

    /** §2.2 rule 2: re-executing under the same key is success, not failure. */
    @Test
    void materializeIsIdempotentUnderTheSameGenerationAndBytes() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        RenderedConfig c = config("idempotent: true\n");

        String first = f.adapter().materializeConfig(r, 4, c).writtenHash();
        String second = f.adapter().materializeConfig(r, 4, c).writtenHash();

        assertEquals(first, second);
    }

    @Test
    void startOrUpdateMovesTheStartBaselineAndNothingElse() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        f.adapter().materializeConfig(r, 4, config("restarted: true\n"));
        RuntimeStatus before = f.adapter().status(r);

        LifecycleResult result = f.adapter().startOrUpdate(r, 4);
        assertTrue(result.issued());

        RuntimeStatus after = f.adapter().status(r);
        assertNotEquals(before.startBaseline().orElseThrow().startedAt(),
                after.startBaseline().orElseThrow().startedAt(),
                "restart-observed is a baseline comparison; without a moving baseline there is "
                        + "nothing to observe");
        assertEquals(before.observedHash(), after.observedHash(),
                "a restart reads config, it does not rewrite it");
        assertEquals(Liveness.RUNNING, after.liveness());
    }

    /**
     * {@code SM-D2} / {@code I1}, executable: deployment is the customer's CI/CD. An adapter that
     * pulled, replaced or re-tagged an artifact would change the image identity across a restart.
     */
    @Test
    void startOrUpdateNeverChangesTheArtifact() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        var key = com.solace.sm.dp.apply.model.RuntimeRef.Key.IMAGE_DIGEST;
        String before = f.adapter().status(r).runtimeRef().fields().get(key);
        org.junit.jupiter.api.Assumptions.assumeTrue(before != null,
                "this runtime does not express an artifact identity; the rule is vacuous for it");

        f.adapter().startOrUpdate(r, 4);

        assertEquals(before, f.adapter().status(r).runtimeRef().fields().get(key),
                "the artifact identity must be unchanged across a restart");
    }

    @Test
    void stopIsIdempotentAndReportsWhetherItActed() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);

        assertTrue(f.adapter().stop(r).issued());
        assertEquals(Liveness.STOPPED, f.adapter().status(r).liveness());

        assertFalse(f.adapter().stop(r).issued(),
                "a replica already in the desired state reports success without acting (§2.2 rule 2)");
    }

    /** §2.2 rule 3's trigger: a health signal the runtime does not publish is NOT_DECLARED. */
    @Test
    void theHealthSignalIsNeverInventedWhileTheReplicaIsDown() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        f.adapter().stop(r);

        assertNotEquals(HealthSignal.HEALTHY, f.adapter().status(r).health(),
                "a stopped replica is never reported healthy — Docker's own State.Health goes STALE "
                        + "rather than absent, measured, which is exactly the trap this asserts against");
    }

    /**
     * {@code BE-D13} §3.1's step-label-truthfulness invariant — "a restore journals {@code ROLLBACK},
     * never {@code ROLLBACK_ASSESS}" — whose enforcement home that table names as "adapter contract
     * tests, per adapter". This is that test. An {@code assessRollback} that restored would make the
     * engine's hashless {@code ROLLBACK_ASSESS} intent a lie, and no database CHECK can see it.
     */
    @Test
    void assessRollbackNamesTheRestoreTargetAndMutatesNothing() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        f.adapter().materializeConfig(r, 4, config("generation: 4\n"));
        String atFour = f.adapter().status(r).hash().orElseThrow();
        f.adapter().materializeConfig(r, 5, config("generation: 5\n"));
        RuntimeStatus before = f.adapter().status(r);
        assertNotEquals(atFour, before.hash().orElseThrow());

        RollbackAssessment.Restorable assessment = assertInstanceOf(
                RollbackAssessment.Restorable.class, f.adapter().assessRollback(f.placement(), 5, atFive));
        assertEquals(atFour, assessment.targetHash(),
                "the assessment names the hash the engine will journal as the ROLLBACK intent's "
                        + "config_hash BEFORE the destructive call");

        RuntimeStatus after = f.adapter().status(r);
        assertEquals(before.observedHash(), after.observedHash(), "assessRollback restored nothing");
        assertEquals(before.startBaseline().map(PreBaseline::startedAt),
                after.startBaseline().map(PreBaseline::startedAt),
                "…and issued no lifecycle action either");
    }

    @Test
    void assessRollbackOfAGenerationNeverWrittenReportsNothingToRestore() throws Exception {
        Fixture f = newFixture();
        assertInstanceOf(RollbackAssessment.NothingToRestore.class,
                f.adapter().assessRollback(f.placement(), 99, unknownHash));
    }

    /**
     * The case {@code hash_on_config_steps} cannot express: a first apply, whose prior state was the
     * ABSENCE of a file, has no hash for a {@code ROLLBACK} intent to carry. The adapter must say so
     * rather than offer a restore the engine is forbidden to journal (`BE-D22`).
     */
    @Test
    void aRestoreTargetOfAbsenceIsUnknowableRatherThanRestorable() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        f.adapter().materializeConfig(r, 6, config("first: true\n"));   // nothing was there before

        RollbackAssessment.Unknowable unknowable = assertInstanceOf(
                RollbackAssessment.Unknowable.class, f.adapter().assessRollback(f.placement(), 6, atSix));
        assertEquals("rollback.target.absent", unknowable.why().token());
    }

    @Test
    void restorePutsTheTargetHashBackOnEveryReplicaAndIsIdempotent() throws Exception {
        Fixture f = newFixture();
        TargetReplica r = f.replicas().get(0);
        f.adapter().materializeConfig(r, 4, config("generation: 4\n"));
        String atFour = f.adapter().status(r).hash().orElseThrow();
        f.adapter().materializeConfig(r, 5, config("generation: 5\n"));

        f.adapter().restore(f.placement(), 5, atFour);
        assertEquals(atFour, f.adapter().status(r).hash().orElseThrow());

        // §2.2 rule 2: re-execution under the same key is success. This is also exactly what resume
        // does when a crash lands between the restore and its close.
        f.adapter().restore(f.placement(), 5, atFour);
        assertEquals(atFour, f.adapter().status(r).hash().orElseThrow());
    }
}
```

- [ ] **Step 2: Run the kit against nothing to verify it cannot pass vacuously**

Run: `mvn -f backend/pom.xml -pl dp-core -am test -Dtest='AbstractRuntimeAdapterContractTest'`
Expected: **no tests run** — Surefire does not instantiate an abstract class. This is the observation that motivates Step 3: the kit is inert until something subclasses it, so the kit alone is not evidence.

- [ ] **Step 3: Write `InMemoryRuntimeAdapter` and its contract subclass**

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link RuntimeAdapter} with no runtime behind it.
 *
 * <p>Two jobs. It proves the contract kit is not vacuous — an abstract test class with no subclass
 * runs zero tests. And it gives Tasks 8–10 an adapter that can be driven into failure states a real
 * daemon will not reproduce on demand: an unreadable start baseline, a competing writer, a
 * pre-flight refusal, a hang past the step deadline.
 *
 * <p>Test scope only, and deliberately so: nothing in production may resolve a fake adapter.
 */
public final class InMemoryRuntimeAdapter implements RuntimeAdapter {

    /** What this fake should do instead of behaving. One switch per §2.2 rule it helps exercise. */
    public enum Fault {
        NONE,
        PREFLIGHT_REFUSED,
        BASELINE_UNREADABLE,
        COMPETING_WRITER,
        MATERIALIZE_THROWS,
        LIFECYCLE_THROWS,
        NEVER_HEALTHY,
        HANGS
    }

    private final PlacementRef placement;
    private final List<TargetReplica> replicas = new ArrayList<>();
    private final Map<ReplicaRef, String> hashes = new LinkedHashMap<>();
    private final Map<ReplicaRef, Instant> startedAt = new LinkedHashMap<>();
    private final Map<ReplicaRef, Boolean> running = new LinkedHashMap<>();
    private final Map<Long, Optional<String>> preImages = new LinkedHashMap<>();
    private final AtomicInteger restarts = new AtomicInteger();
    private final boolean healthSignalDeclared;

    private Fault fault = Fault.NONE;
    private Instant clock = Instant.parse("2026-09-11T00:00:00Z");

    public InMemoryRuntimeAdapter(PlacementRef placement, boolean healthSignalDeclared,
            String... replicaRefs) {
        this.placement = placement;
        this.healthSignalDeclared = healthSignalDeclared;
        for (String r : replicaRefs) {
            TargetReplica t = new TargetReplica(placement, new ReplicaRef(r));
            replicas.add(t);
            startedAt.put(t.ref(), clock);
            running.put(t.ref(), true);
        }
    }

    public void fault(Fault f) {
        this.fault = f;
    }

    public List<TargetReplica> replicas() {
        return List.copyOf(replicas);
    }

    public int restarts() {
        return restarts.get();
    }

    /** Simulates a competing writer landing bytes SM did not write (§2.2 rule 3's third branch). */
    public void competingWrite(TargetReplica r, String hash) {
        hashes.put(r.ref(), hash);
    }

    private void tick() {
        clock = clock.plus(Duration.ofSeconds(1));
    }

    @Override
    public PreflightResult prepare(PlacementRef p, long generation) {
        if (fault == Fault.PREFLIGHT_REFUSED) {
            return new PreflightResult.Refused(new ErrorClass("fake.preflight.refused"), ref());
        }
        if (fault == Fault.COMPETING_WRITER) {
            return new PreflightResult.Refused(new ErrorClass("fake.config.competing.source"), ref());
        }
        return new PreflightResult.Ready(replicas,
                new ConfigLocation("/app/external/spring/config",
                        java.nio.file.Path.of("/dev/null"), "application.yml",
                        ConfigLocation.Mechanism.ADDITIONAL_LOCATION),
                healthSignalDeclared, ref());
    }

    @Override
    public MaterializeResult materializeConfig(TargetReplica r, long generation, RenderedConfig c) {
        if (fault == Fault.MATERIALIZE_THROWS) {
            throw new RuntimeAdapterException(new ErrorClass("fake.materialize.failed"));
        }
        preImages.putIfAbsent(generation, Optional.ofNullable(hashes.get(r.ref())));
        hashes.put(r.ref(), c.hash());
        return new MaterializeResult(c.hash(), ref());
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
        if (fault == Fault.LIFECYCLE_THROWS) {
            throw new RuntimeAdapterException(new ErrorClass("fake.lifecycle.failed"));
        }
        tick();
        restarts.incrementAndGet();
        startedAt.put(r.ref(), clock);
        running.put(r.ref(), true);
        return new LifecycleResult(true, ref());
    }

    @Override
    public LifecycleResult stop(TargetReplica r) {
        boolean wasRunning = Boolean.TRUE.equals(running.get(r.ref()));
        running.put(r.ref(), false);
        return new LifecycleResult(wasRunning, ref());
    }

    @Override
    public RuntimeStatus status(TargetReplica r) {
        boolean up = Boolean.TRUE.equals(running.get(r.ref()));
        HealthSignal health;
        if (!healthSignalDeclared) {
            health = HealthSignal.NOT_DECLARED;
        } else if (!up) {
            health = HealthSignal.UNHEALTHY;
        } else if (fault == Fault.NEVER_HEALTHY || fault == Fault.HANGS) {
            health = HealthSignal.STARTING;
        } else {
            health = HealthSignal.HEALTHY;
        }
        PreBaseline baseline = fault == Fault.BASELINE_UNREADABLE || !up
                ? null
                : new PreBaseline(startedAt.get(r.ref()), r.ref().value());
        return new RuntimeStatus(up ? Liveness.RUNNING : Liveness.STOPPED, health,
                hashes.get(r.ref()), baseline, ref());
    }

    @Override
    public RollbackAssessment assessRollback(PlacementRef p, long generation, String intendedHash) {
        // READ ONLY. Not one line of this method writes to `hashes`, `startedAt` or `running`, and
        // the contract kit asserts that from the outside.
        if (fault == Fault.BASELINE_UNREADABLE) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unreadable"));
        }
        Optional<String> prior = preImages.get(generation);
        if (prior == null) {
            return new RollbackAssessment.NothingToRestore();
        }
        if (prior.isEmpty()) {
            // The prior state was ABSENCE: no hash exists for a ROLLBACK intent's config_hash, and
            // §2.2 rule 0 forbids acting without the journal entry (BE-D22).
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.target.absent"));
        }
        // BE-D22 correction A: recognize exactly TWO journal-known values -- the rollback target
        // and the MATERIALIZE intent's own hash. Their MIXTURE is the ordinary partial-write case a
        // rollback exists for, not divergence. A third value means somebody else wrote the file.
        for (TargetReplica r : replicas) {
            String seen = hashes.get(r.ref());
            if (seen == null || !(seen.equals(prior.get()) || seen.equals(intendedHash))) {
                return new RollbackAssessment.Unknowable(new ErrorClass(
                        seen == null ? "rollback.state.unreadable" : "rollback.state.unknown"));
            }
        }
        return new RollbackAssessment.Restorable(prior.get());
    }

    @Override
    public RestoreResult restore(PlacementRef p, long generation, String targetHash) {
        for (TargetReplica r : replicas) {
            hashes.put(r.ref(), targetHash);
        }
        return new RestoreResult(ref());
    }

    private RuntimeRef ref() {
        return RuntimeRef.of(RuntimeRef.Key.HOST, "in-memory");
    }
}
```

```java
package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import java.nio.file.Path;
import java.util.UUID;

/** The kit, run against the in-memory adapter. Without this class the kit runs zero tests. */
class InMemoryRuntimeAdapterContractTest extends AbstractRuntimeAdapterContractTest {

    @Override
    protected Fixture newFixture() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        return new Fixture(adapter, placement, adapter.replicas(),
                new ConfigLocation("/app/external/spring/config", Path.of("/dev/null"),
                        "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION),
                true);
    }
}
```

- [ ] **Step 4: Run the contract suite**

Run: `mvn -f backend/pom.xml -pl dp-core -am test -Dtest='InMemoryRuntimeAdapterContractTest'`
Expected: PASS, **13 tests** — one per obligation in the kit. Note `startOrUpdateNeverChangesTheArtifact` reports as *passed with an assumption* on this adapter: the in-memory runtime publishes no `IMAGE_DIGEST`, so the rule is vacuous for it and says so. Task 7 Step 8 runs the same test against Docker, where it is not vacuous.

- [ ] **Step 5: Prove the kit fires**

| Mutation in `InMemoryRuntimeAdapter` | Expected red |
|---|---|
| `materializeConfig` also calls `tick()` and updates `startedAt` | `materializeHasZeroLifecycleEffect` — 1 red |
| `stop` always returns `new LifecycleResult(true, ref())` | `stopIsIdempotentAndReportsWhetherItActed` — 1 red |
| `status` returns `HealthSignal.HEALTHY` whenever `healthSignalDeclared` | `theHealthSignalIsNeverInventedWhileTheReplicaIsDown` — 1 red |
| `assessRollback` also writes the prior hash back (the one-operation shape the plan's first draft had) | `assessRollbackNamesTheRestoreTargetAndMutatesNothing` — 1 red. **This is the mutation that proves a restore cannot be journalled as `ROLLBACK_ASSESS`**: the kit is where §3.1 puts that invariant, and without this test the split is decoration |
| `assessRollback` returns `Restorable` for an absent pre-image (with any hash) | `aRestoreTargetOfAbsenceIsUnknowableRatherThanRestorable` — 1 red |
| `restore` writes to only the first replica | `restorePutsTheTargetHashBackOnEveryReplicaAndIsIdempotent` — 1 red |

Six mutations, six reds, each reverted. Record the counts.

- [ ] **Step 6: Record the extension points for the two adapters this plan does not write**

Append to `AbstractRuntimeAdapterContractTest`'s class javadoc, so the next adapter's author finds it where they will already be reading:

```java
 * <p><b>What a systemd or Kubernetes adapter inherits, and what it must decide</b> (design §2.3's
 * three-runtime walk-through, which this kit is the executable half of):
 *
 * <ul>
 *   <li><b>systemd</b> reuses {@link ConfigFileStore} unchanged — a real host directory has the same
 *       file semantics as a bind mount. It owes: unit-name resolution for {@code prepare}, the
 *       {@code systemctl} verbs, and a start-time source for {@link RuntimeStatus#baseline()}.
 *   <li><b>Kubernetes</b> does <b>not</b> reuse {@link ConfigFileStore}: {@code SM-D25} makes
 *       materialization a new generation-named immutable ConfigMap, one per generation rather than
 *       one per replica, so the per-replica calls after the first verify-and-succeed under §2.2
 *       rule 2. It owes the {@code SM-D25} GC rule (retain N, delete oldest-first, never delete one
 *       still referenced by a live or paused workload) and the {@code B/D13} disposition: the
 *       platform sequences pods, SM still owns the verdict.
 * </ul>
 *
 * <p>Neither is in scope for plan 005 (delivery sequence §6). Both pass this kit unchanged or the
 * seam was not generic, which is the claim §2.3 makes at design time and this class is where it
 * becomes checkable.
```

- [ ] **Step 7: Commit**

```bash
git add backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime
git commit -m "$(cat <<'MSG'
Add the RuntimeAdapter contract kit and the in-memory adapter (Task 2)

Eleven obligations every adapter owes, in the AbstractEventPublisherContractTest
idiom, plus one implementation that passes them -- which is what answers plan
004's stated reason for not declaring the interface: an abstract test class with
no subclass runs zero tests, so the kit alone would have been the same untested
declaration.

Prove-it-fires: materialize ticks the baseline -> 1 red; stop always claims it
acted -> 1 red; health invented while down -> 1 red; rollback always NoEffect
-> 1 red.
MSG
)"
```

**Risks:** the kit can drift into testing the fake rather than the contract. Guarded by Task 7 Step 8, where the same class runs against Docker: an obligation only the fake can satisfy will fail there.

---

### Task 3: `DockerEngineClient` — four endpoints and a stat probe, measured

**Files:**
- Modify: `backend/dp-core/pom.xml` (two dependencies)
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker/DockerEngineClient.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker/DockerProperties.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker/DockerErrors.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/DockerErrorsTest.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/DockerPropertiesTest.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/DockerEngineClientIT.java`

**Interfaces:**
- Consumes: `ErrorClass`, `RuntimeAdapterException` (Task 1).
- Produces:
  ```java
  JsonNode inspect(String container);                  // GET  /v1.43/containers/{id}/json
  List<JsonNode> list(String nameFilter);              // GET  /v1.43/containers/json?all=true&filters=…
  boolean restart(String container, int timeoutSeconds);   // POST …/restart  -> true when 204
  boolean stop(String container, int timeoutSeconds);      // POST …/stop     -> true 204, false 304
  Optional<PathStat> statInContainer(String container, String path); // HEAD …/archive
  record PathStat(String name, long size, Instant mtime) {}
  ```
  Tasks 4–6 use all five. `DockerErrors.classify(int status, String operation)` is the single status→`ErrorClass` table.

- [ ] **Step 1: Write the failing tests**

`DockerErrorsTest` (unit):

```java
package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DockerErrorsTest {

    @Test
    void everyMappedStatusYieldsAClassifierAndNothingElse() {
        assertEquals("docker.container.absent", DockerErrors.classify(404, "inspect").token());
        assertEquals("docker.api.rejected", DockerErrors.classify(400, "inspect").token());
        assertEquals("docker.api.conflict", DockerErrors.classify(409, "restart").token());
        assertEquals("docker.api.failed", DockerErrors.classify(500, "restart").token());
        assertEquals("docker.api.unexpected", DockerErrors.classify(418, "stop").token());
    }

    /**
     * The classifier is the whole message. A Docker error body echoes container names, mount paths
     * and sometimes the command line; none of it may reach a column, a log line or an exception.
     */
    @Test
    void noClassifierCarriesAnythingButLowercaseDottedTokens() {
        for (int status : new int[] {304, 400, 404, 409, 500, 503, 418}) {
            String token = DockerErrors.classify(status, "inspect").token();
            assertTrue(token.matches("^[a-z][a-z0-9]*(\\.[a-z0-9]+)*$"), token);
            assertTrue(token.startsWith("docker."), token);
        }
    }
}
```

`DockerPropertiesTest` (unit) asserts: a `unix://` host is accepted; a `tcp://` host is accepted; a host carrying userinfo (`tcp://user:pw@host:2376`) is **refused** with a message that does not echo it — the `JdbcUrls` posture from plan 004, applied to the second location string this backend holds.

`DockerEngineClientIT` (failsafe, real Docker — starts its own `busybox:1.36` through Testcontainers `GenericContainer`):

```java
package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DockerEngineClientIT {

    private static GenericContainer<?> probe;
    private static DockerEngineClient client;

    @BeforeAll
    static void start() {
        probe = new GenericContainer<>(DockerImageName.parse("busybox:1.36"))
                .withCommand("sh", "-c", "while true; do sleep 3600; done");
        probe.start();
        client = DockerEngineClient.fromEnvironment(Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    @AfterAll
    static void stop() {
        if (probe != null) {
            probe.stop();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void inspectReturnsTheStateBlockTheAdapterReadsFrom() {
        var json = client.inspect(probe.getContainerId());
        assertTrue(json.path("State").path("Running").asBoolean());
        assertFalse(json.path("State").path("StartedAt").asText().isBlank());
        assertTrue(json.path("Image").asText().startsWith("sha256:"));
    }

    @Test
    void inspectingAnAbsentContainerIsAClassifiedRefusal() {
        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> client.inspect("sm-no-such-container"));
        assertEquals("docker.container.absent", e.errorClass().token());
        assertEquals("docker.container.absent", e.getMessage(),
                "the daemon's own 'No such container: …' text must not survive the boundary");
    }

    @Test
    void listingByNameFindsExactlyTheProbe() {
        var found = client.list(probe.getContainerName().replaceFirst("^/", ""));
        assertEquals(1, found.size());
        assertEquals(probe.getContainerId(), found.get(0).path("Id").asText());
    }

    @Test
    void restartReturnsTrueAndStopIsIdempotent() {
        assertTrue(client.restart(probe.getContainerId(), 5));
        assertTrue(client.stop(probe.getContainerId(), 5), "204: the daemon acted");
        assertFalse(client.stop(probe.getContainerId(), 5),
                "304: already stopped — success without acting, per §2.2 rule 2");
        assertTrue(client.restart(probe.getContainerId(), 5), "restart also starts a stopped one");
    }

    @Test
    void statInContainerSeesAFileAndReportsAbsenceAsEmpty() {
        // busybox always has /etc/hostname; the adapter's real use is a probe file it just wrote.
        Optional<DockerEngineClient.PathStat> stat =
                client.statInContainer(probe.getContainerId(), "/etc/hostname");
        assertTrue(stat.isPresent());
        assertEquals("hostname", stat.orElseThrow().name());
        assertTrue(stat.orElseThrow().size() > 0);

        assertTrue(client.statInContainer(probe.getContainerId(), "/nope/nope").isEmpty());
    }

    @Test
    void anUnreachableDaemonIsAClassifiedRefusalRatherThanAStackTrace() {
        try (DockerEngineClient dead = DockerEngineClient.at(
                java.net.URI.create("unix:///nonexistent/docker.sock"),
                Duration.ofMillis(250), Duration.ofSeconds(1))) {
            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> dead.inspect("anything"));
            assertEquals("docker.api.unreachable", e.errorClass().token());
            org.junit.jupiter.api.Assertions.assertNull(e.getCause());
        }
    }
}
```

- [ ] **Step 2: Run to verify red**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dtest='DockerErrorsTest,DockerPropertiesTest' -Dit.test='DockerEngineClientIT'`
Expected: compilation failure — the package does not exist.

- [ ] **Step 3: Add the two dependencies**

In `backend/dp-core/pom.xml`, after the Testcontainers block:

```xml
    <!--
      The Docker Engine transport, and ONLY the transport.

      I2, measured 2026-09-11: Maven Central's own metadata serves 3.5.1 as the release, and this
      pin is deliberately NOT that. `testcontainers:1.21.4` -- the version the Spring Boot 3.5.16
      BOM manages -- declares `docker-java-transport-zerodep:3.4.2` at compile scope in its own POM,
      and `testcontainers-bom` does not manage docker-java at all. Declaring 3.5.1 here would put a
      newer transport under a Testcontainers compiled against the older one, on the same test
      classpath, for no gain: the five calls this adapter makes are HTTP verbs, not client features.
      Revisit when Testcontainers itself moves.

      `docker-java-core` is deliberately absent. Core is the command/model layer -- Jackson-bound
      DTOs, an SSL stack, commons-compress -- and this adapter issues four requests and one HEAD
      against a version-pinned path. The transport is what is genuinely hard (unix socket, npipe,
      tcp+TLS, all behind one URI), and it is dependency-light: docker-java-transport, slf4j-api,
      jna, with Apache HttpClient shaded in.
    -->
    <dependency>
      <groupId>com.github.docker-java</groupId>
      <artifactId>docker-java-transport-zerodep</artifactId>
      <version>3.4.2</version>
    </dependency>
    <!--
      Already on the compile classpath transitively through mesh-events, and declared here because
      this module now compiles against it directly. BOM-managed, so no <version> (I2).

      This is a SECOND ObjectMapper in the module, and that is allowed by design: PlaneBoundaryTest's
      `meshbridgeHoldsNoOwnMapper` is scoped to `com.solace.sm.dp.meshbridge..` precisely because the
      one-mapper rule is about the EVENT contract, which has goldens watching it. Docker API JSON is
      not the event contract and has none.
    -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
```

- [ ] **Step 4: Write the client**

```java
package com.solace.sm.dp.apply.runtime.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * The only class that speaks HTTP to the Docker daemon.
 *
 * <p><b>The API version is negotiated once, then constant.</b> ⚠️ This plan originally pinned
 * {@code /v1.43}; execution measured that Colima's Docker 29.2.1 advertises
 * {@code MinAPIVersion 1.44}, so the pin is below its floor and every call is a {@code 400}. The
 * client now reads the daemon's own {@code /version} window and clamps a preferred version into it,
 * once, at first use.
 *
 * <p><b>No response body ever escapes this class</b> ({@code I6}). A daemon error body echoes
 * container names, mount paths and sometimes the command line. Non-2xx becomes a
 * {@link RuntimeAdapterException} carrying a {@link DockerErrors} classifier and no cause; the body
 * is drained and discarded without being read into a string.
 */
public final class DockerEngineClient implements Closeable {

    private static final String V = "/v1.43";

    private final DockerHttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    private DockerEngineClient(DockerHttpClient http) {
        this.http = http;
    }

    public static DockerEngineClient at(URI dockerHost, Duration connect, Duration response) {
        DockerProperties.requireLocationOnly(dockerHost);
        return new DockerEngineClient(new ZerodepDockerHttpClient.Builder()
                .dockerHost(dockerHost)
                .connectionTimeout(connect)
                .responseTimeout(response)
                .build());
    }

    /** {@code DOCKER_HOST} when set, else the platform's default socket. */
    public static DockerEngineClient fromEnvironment(Duration connect, Duration response) {
        String host = System.getenv("DOCKER_HOST");
        return at(URI.create(host == null || host.isBlank() ? "unix:///var/run/docker.sock" : host),
                connect, response);
    }

    public JsonNode inspect(String container) {
        return getJson(V + "/containers/" + encode(container) + "/json", "inspect");
    }

    public List<JsonNode> list(String nameFilter) {
        String filters = "{\"name\":[\"" + nameFilter + "\"]}";
        JsonNode array = getJson(V + "/containers/json?all=true&filters=" + encode(filters), "list");
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    /** @return {@code true} when the daemon acted ({@code 204}). */
    public boolean restart(String container, int timeoutSeconds) {
        return act("POST", V + "/containers/" + encode(container) + "/restart?t=" + timeoutSeconds,
                "restart");
    }

    /** @return {@code true} on {@code 204}; {@code false} on {@code 304} — already stopped. */
    public boolean stop(String container, int timeoutSeconds) {
        return act("POST", V + "/containers/" + encode(container) + "/stop?t=" + timeoutSeconds,
                "stop");
    }

    /**
     * Stats a path <b>inside</b> the container, from the container's own view of its filesystem.
     *
     * <p>This is the mount-identity probe {@code prepare} needs and the reason no {@code exec} is
     * required: {@code HEAD /archive} answers with a base64 {@code X-Docker-Container-Path-Stat}
     * header and no body, measured, and {@code 404} when the path is not there.
     */
    public Optional<PathStat> statInContainer(String container, String path) {
        DockerHttpClient.Request req = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.HEAD)
                .path(V + "/containers/" + encode(container) + "/archive?path=" + encode(path))
                .build();
        try (DockerHttpClient.Response res = execute(req, "stat")) {
            if (res.getStatusCode() == 404) {
                return Optional.empty();
            }
            require2xx(res.getStatusCode(), "stat");
            String header = res.getHeader("X-Docker-Container-Path-Stat");
            if (header == null) {
                throw refuse("docker.api.unexpected");
            }
            JsonNode stat = readTree(Base64.getDecoder().decode(header), "stat");
            return Optional.of(new PathStat(stat.path("name").asText(), stat.path("size").asLong(),
                    Instant.parse(stat.path("mtime").asText())));
        }
    }

    /** What the daemon says is at a path inside the container. Metadata only — never content. */
    public record PathStat(String name, long size, Instant mtime) {}

    private JsonNode getJson(String path, String operation) {
        DockerHttpClient.Request req = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.GET).path(path).build();
        try (DockerHttpClient.Response res = execute(req, operation)) {
            require2xx(res.getStatusCode(), operation);
            try (InputStream body = res.getBody()) {
                return readTree(body.readAllBytes(), operation);
            } catch (IOException e) {
                throw refuse("docker.api.failed");
            }
        }
    }

    private boolean act(String method, String path, String operation) {
        DockerHttpClient.Request req = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.valueOf(method)).path(path).build();
        try (DockerHttpClient.Response res = execute(req, operation)) {
            int status = res.getStatusCode();
            if (status == 304) {
                return false;            // already in the desired state: success without acting
            }
            require2xx(status, operation);
            return true;
        }
    }

    private DockerHttpClient.Response execute(DockerHttpClient.Request req, String operation) {
        try {
            return http.execute(req);
        } catch (RuntimeException e) {
            // Transport-level: no socket, refused connection, timeout. The exception's own message
            // carries the socket path, so it is classified and dropped, never wrapped.
            throw refuse("docker.api.unreachable");
        }
    }

    private void require2xx(int status, String operation) {
        if (status / 100 != 2) {
            throw new RuntimeAdapterException(DockerErrors.classify(status, operation));
        }
    }

    private JsonNode readTree(byte[] bytes, String operation) {
        try {
            return json.readTree(bytes);
        } catch (IOException e) {
            throw refuse("docker.api.unparseable");
        }
    }

    private static RuntimeAdapterException refuse(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            http.close();
        } catch (IOException e) {
            // Closing a transport is best-effort; a failure here has nothing a caller can do.
        }
    }
}
```

`DockerErrors.java` — one table, as data:

```java
package com.solace.sm.dp.apply.runtime.docker;

import com.solace.sm.dp.apply.model.ErrorClass;

/**
 * Status code → classifier, in one place.
 *
 * <p>Deliberately ignores {@code operation} for the value and takes it only so a future refinement
 * ("a 409 on restart means a conflicting operation; a 409 on stop means something else") has a
 * parameter to use, rather than a signature change. {@code DockerErrorsTest} pins the current
 * mapping so a refinement is a visible edit.
 */
public final class DockerErrors {

    private DockerErrors() {
    }

    public static ErrorClass classify(int status, String operation) {
        return new ErrorClass(switch (status) {
            case 304 -> "docker.api.notmodified";
            case 400 -> "docker.api.rejected";
            case 401, 403 -> "docker.api.forbidden";
            case 404 -> "docker.container.absent";
            case 409 -> "docker.api.conflict";
            case 500, 502, 503, 504 -> "docker.api.failed";
            default -> "docker.api.unexpected";
        });
    }
}
```

`DockerProperties.java` holds `host`, `connectTimeout`, `responseTimeout`, `stopTimeoutSeconds` and the `requireLocationOnly(URI)` grammar: scheme ∈ `{unix, tcp, npipe}`, **no** userinfo, no query, no fragment; refusal messages name the rule and never the value — the `JdbcUrls` posture, second location string.

- [ ] **Step 5: Run to green**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dtest='DockerErrorsTest,DockerPropertiesTest' -Dit.test='DockerEngineClientIT'`
Expected: surefire green; failsafe `DockerEngineClientIT` 6/0.

- [ ] **Step 6: Prove the guards fire**

| Mutation | Expected red |
|---|---|
| `require2xx` accepts any status | `inspectingAnAbsentContainerIsAClassifiedRefusal` — 1 red |
| `act` treats `304` as `require2xx` | `restartReturnsTrueAndStopIsIdempotent` — 1 red |
| `execute` wraps the transport exception as a cause (`new RuntimeAdapterException(cls, e)` with a two-arg constructor added) | `anUnreachableDaemonIsAClassifiedRefusalRatherThanAStackTrace` — 1 red on `assertNull(getCause())` |
| `DockerProperties.requireLocationOnly` accepts userinfo | `DockerPropertiesTest` — 1 red |

- [ ] **Step 7: Measure the Docker-absent expectation rather than assuming it**

The new ITs carry `@Testcontainers(disabledWithoutDocker = true)`, which reports `SKIPPED` rather than failing — lesson `R10`'s exact shape. The paired mandatory check already exists in this module (`DockerAvailabilityPreflightIT`, no condition on it). **Measure that it covers the new classes**, do not infer it:

```bash
# 1. Confirm the preflight and the new ITs are in the same module, so one failsafe run sees both.
ls backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/DockerAvailabilityPreflightIT.java
ls backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/DockerEngineClientIT.java

# 2. Make Docker unreachable for one run and read the EXIT CODE and the summary XML, not the console.
DOCKER_HOST=unix:///nonexistent/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/nonexistent/docker.sock \
  mvn -f backend/pom.xml -pl dp-core -am clean verify; echo "exit=$?"
cat backend/dp-core/target/failsafe-reports/failsafe-summary.xml
```

Expected: `exit=1`, and the summary shows `DockerAvailabilityPreflightIT` **failed** while the rest are skipped. Paste both into the commit message. If the exit code is 0, **stop**: the gate is a false green and that is a finding, not a step to work around.

- [ ] **Step 8: Commit**

```bash
git add backend/dp-core/pom.xml \
        backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker \
        backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker
git commit -m "$(cat <<'MSG'
Add DockerEngineClient: four endpoints and a stat probe, version-pinned (Task 3)

docker-java-transport-zerodep 3.4.2 -- pinned to what Testcontainers 1.21.4
already puts on the classpath rather than to Central's 3.5.1 release, so exactly
one transport is resolved -- and no docker-java-core: the adapter issues four
requests and one HEAD against /v1.43, and the transport is the only hard part.

No daemon response body crosses the boundary: non-2xx becomes a classifier with
no cause, because a Docker error body echoes container names and mount paths.

Docker-absent measured, not assumed: with the socket broken the build exits 1 on
DockerAvailabilityPreflightIT rather than going green with everything skipped.

Prove-it-fires: require2xx widened -> 1 red; 304 treated as an error -> 1 red;
transport exception re-attached as a cause -> 1 red; userinfo admitted -> 1 red.
MSG
)"
```

**Risks:** pinning `/v1.43` means a daemon older than API 1.43 (Docker 24.0, 2023) is refused with a `400`. Stated in the class javadoc and accepted — `SM-D26`-era floors are all newer. If a customer host is older, that is a supported-platform decision, not a client change.

---

### Task 4: `DockerAdapter.prepare` — launch-source resolution, mount identity, replica enumeration

The richest operation, and the one `SM-D21` exists for. Its round-2 correction is the whole task: *"Pre-flight runs per binding before every apply, not once at Target enrollment. Launch command, mount and competing-source set can all change between applies; a one-time check certifies a state that has since expired."*

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker/DockerPlacement.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker/DockerPlacementCatalog.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker/PropertiesDockerPlacementCatalog.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker/LaunchSource.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker/DockerAdapter.java` (this task writes `prepare` and the constructor; Tasks 5–6 add the other five operations)
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/LaunchSourceTest.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/DockerPrepareIT.java`

**Interfaces:**
- Consumes: `DockerEngineClient`, `DockerErrors` (Task 3); `PreflightResult`, `ConfigLocation`, `TargetReplica`, `PlacementRef` (Task 1).
- Produces:
  ```java
  public record DockerPlacement(String containerNamePrefix, String containerConfigPath,
          Path daemonConfigDir, Path localConfigDir, String fileName,
          ConfigLocation.Mechanism mechanism, int retainedGenerations) {}
  public interface DockerPlacementCatalog { DockerPlacement lookup(PlacementRef placement); }
  public record LaunchSource(List<String> declaredLocations, List<String> competingLocations) {
      static LaunchSource of(JsonNode inspect);
  }
  public DockerAdapter(DockerEngineClient engine, DockerPlacementCatalog catalog,
          ConfigFileStore store, DockerProperties props);
  ```
  Tasks 5–6 extend `DockerAdapter`; Task 8 injects it as a `RuntimeAdapter`.

**Why `PropertiesDockerPlacementCatalog` and not a table:** the placement/inventory tables are W5's, the endpoints that would populate them are G2's, and plan 004 explicitly left `apply_request.placement_id` with **no referential guard** (its watch item, design §8 row 13). A configuration-resolved catalog is the only non-auth, non-schema way to name a placement today, and it is one interface away from the table that replaces it.

- [ ] **Step 1: Write the failing tests**

`LaunchSourceTest` (unit, over inspect fixtures as `JsonNode`) — the five cases that matter, each drawn from a measured shape:

```java
package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class LaunchSourceTest {

    private static LaunchSource parse(String json) throws Exception {
        return LaunchSource.of(new ObjectMapper().readTree(json));
    }

    /** Cache's real shape: the flag is on the ENTRYPOINT, which is what SM-D21 was written about. */
    @Test
    void anAdditionalLocationOnTheEntrypointIsTheDeclaredLocation() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":["java","-jar","solace-cache-engine.jar",
                  "--spring.config.additional-location=/app/external/spring/config/"],
                  "Cmd":null,"Env":["PATH=/usr/bin"]}}""");
        assertEquals(List.of("/app/external/spring/config/"), s.declaredLocations());
        assertTrue(s.competingLocations().isEmpty());
    }

    /**
     * Measured 2026-09-11: Config.Entrypoint is NULL on a container started with a plain command,
     * and Cmd carries the whole line. A parser that read only Entrypoint would see nothing here.
     */
    @Test
    void aNullEntrypointDoesNotHideTheCommand() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":null,
                  "Cmd":["java","-jar","app.jar","--spring.config.additional-location=/conf/"],
                  "Env":[]}}""");
        assertEquals(List.of("/conf/"), s.declaredLocations());
    }

    @Test
    void springConfigLocationOnTheCommandLineIsACompetingSource() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":["java","-jar","app.jar",
                  "--spring.config.additional-location=/conf/","--spring.config.location=/other/"],
                  "Cmd":null,"Env":[]}}""");
        assertEquals(List.of("/conf/"), s.declaredLocations());
        assertEquals(List.of("/other/"), s.competingLocations(),
                "spring.config.location REPLACES the default set; a second writer is exactly what "
                        + "SM-D21's 'detect competing sources' means");
    }

    @Test
    void theEnvironmentFormIsAlsoACompetingSource() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":["java","-jar","app.jar",
                  "--spring.config.additional-location=/conf/"],"Cmd":null,
                  "Env":["SPRING_CONFIG_ADDITIONAL_LOCATION=/elsewhere/"]}}""");
        assertEquals(List.of("/elsewhere/"), s.competingLocations(),
                "the command line outranks the environment (architecture.md §6.1's precedence "
                        + "trap), so the env form is a source SM does not own, not a second declaration");
    }

    @Test
    void anEnvironmentValueIsNeverEchoedIntoTheParseResult() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":["java","-jar","app.jar"],"Cmd":null,
                  "Env":["SOLACE_PASSWORD=hunter2","PATH=/usr/bin"]}}""");
        assertTrue(s.declaredLocations().isEmpty());
        assertTrue(s.competingLocations().isEmpty(),
                "only the config-location variables are read; every other env entry is untouched "
                        + "and unreported (I6)");
    }
}
```

`DockerPrepareIT` (failsafe, real Docker) — five cases, each starting its own container with the shape under test:

| Test | Container shape | Expected |
|---|---|---|
| `aReadyPreflightNamesTheReplicasAndTheResolvedLocation` | `--spring.config.additional-location=/app/external/spring/config/`, bind-mounted, one replica | `Ready`, one `TargetReplica`, `location.containerPath` = the declared path, `healthSignalDeclared = false` |
| `twoContainersSharingThePrefixAreTwoReplicas` | two containers `sm-cache-1`, `sm-cache-2` | `Ready` with two replicas, in name order |
| `aDeclaredPathWithNoMountIsRefused` | declared path, **no** `-v` | `Refused("docker.config.not.persistent")` — `SM-D21`'s round-2 correction: the path lives in the container layer and SM's config dies with the container |
| `aCompetingConfigSourceIsRefused` | declared path **and** `SPRING_CONFIG_LOCATION` in the env | `Refused("docker.config.competing.source")` |
| `aMountThatIsNotTheDirectorySmWritesToIsRefused` | bind source is a *different* host directory from `localConfigDir` | `Refused("docker.config.mount.mismatch")` — caught by the probe round-trip, not by string comparison |

- [ ] **Step 2: Run to verify red**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dtest=LaunchSourceTest -Dit.test=DockerPrepareIT`
Expected: compilation failure.

- [ ] **Step 3: Write `LaunchSource`**

```java
package com.solace.sm.dp.apply.runtime.docker;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * What the container's launch spec says about where it reads configuration — {@code SM-D21}'s
 * "enrollment pre-flight inspects the actual launch command".
 *
 * <p>Reads {@code Config.Entrypoint}, {@code Config.Cmd} and {@code Config.Env}, in that order, and
 * <b>nothing else</b>. Env is scanned for exactly two names; every other variable is left untouched
 * and unreported, because a container's environment is the most secret-dense thing the daemon will
 * hand over ({@code I6}).
 *
 * <p>The split between declared and competing is {@code architecture.md} §6.1's precedence trap:
 * a command-line property outranks an environment variable, so an env-set location beside a
 * command-line one is a source SM does not own — not a second declaration of the one it does.
 */
public record LaunchSource(List<String> declaredLocations, List<String> competingLocations) {

    private static final String CLI_ADDITIONAL = "--spring.config.additional-location=";
    private static final String CLI_LOCATION = "--spring.config.location=";
    private static final String ENV_ADDITIONAL = "SPRING_CONFIG_ADDITIONAL_LOCATION=";
    private static final String ENV_LOCATION = "SPRING_CONFIG_LOCATION=";

    public LaunchSource {
        declaredLocations = List.copyOf(declaredLocations);
        competingLocations = List.copyOf(competingLocations);
    }

    public static LaunchSource of(JsonNode inspect) {
        List<String> declared = new ArrayList<>();
        List<String> competing = new ArrayList<>();
        JsonNode config = inspect.path("Config");

        for (String arg : strings(config.path("Entrypoint"))) {
            collectCommandLine(arg, declared, competing);
        }
        for (String arg : strings(config.path("Cmd"))) {
            collectCommandLine(arg, declared, competing);
        }
        for (String env : strings(config.path("Env"))) {
            if (env.startsWith(ENV_ADDITIONAL)) {
                competing.add(env.substring(ENV_ADDITIONAL.length()));
            } else if (env.startsWith(ENV_LOCATION)) {
                competing.add(env.substring(ENV_LOCATION.length()));
            }
        }
        return new LaunchSource(declared, competing);
    }

    private static void collectCommandLine(String arg, List<String> declared,
            List<String> competing) {
        if (arg.startsWith(CLI_ADDITIONAL)) {
            declared.add(arg.substring(CLI_ADDITIONAL.length()));
        } else if (arg.startsWith(CLI_LOCATION)) {
            competing.add(arg.substring(CLI_LOCATION.length()));
        }
    }

    private static List<String> strings(JsonNode array) {
        // Measured: Config.Entrypoint is JSON null on a container started with a plain command.
        // path() on a missing field gives a MissingNode, which is also not an array; both fall
        // through to the empty list rather than throwing.
        if (!array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
```

- [ ] **Step 4: Write `DockerAdapter.prepare`**

```java
    @Override
    public PreflightResult prepare(PlacementRef placement, long generation) {
        DockerPlacement p = catalog.lookup(placement);
        List<JsonNode> found = engine.list(p.containerNamePrefix());
        if (found.isEmpty()) {
            return refuse("docker.replica.absent");
        }

        List<TargetReplica> replicas = found.stream()
                .map(c -> c.path("Id").asText())
                .sorted()
                .map(id -> new TargetReplica(placement, new ReplicaRef(id)))
                .toList();

        // SM-D21: the launch command, the mount and the competing-source set are re-read on EVERY
        // apply. Each replica is checked, not just the first: a rolling deploy can leave two
        // replicas with different launch specs, and "the one I happened to inspect was fine" is
        // exactly the certificate that has since expired.
        boolean healthSignalDeclared = true;
        for (TargetReplica r : replicas) {
            JsonNode inspect = engine.inspect(r.ref().value());

            LaunchSource launch = LaunchSource.of(inspect);
            if (!launch.competingLocations().isEmpty()) {
                return refuse("docker.config.competing.source");
            }
            if (p.mechanism() == ConfigLocation.Mechanism.ADDITIONAL_LOCATION
                    && !declares(launch, p.containerConfigPath())) {
                return refuse("docker.config.path.mismatch");
            }
            if (!isPersistentlyMounted(inspect, p)) {
                return refuse("docker.config.not.persistent");
            }
            if (!probeRoundTrips(r, p)) {
                return refuse("docker.config.mount.mismatch");
            }
            healthSignalDeclared &= inspect.path("State").has("Health");
        }

        return new PreflightResult.Ready(replicas, location(p), healthSignalDeclared,
                RuntimeRef.of(RuntimeRef.Key.MOUNT_PATH, p.containerConfigPath()));
    }

    /**
     * The replica-listing half, extracted so Task 6's {@code assessRollback} can reuse it WITHOUT
     * pre-flight's probe round-trip: the probe writes a file, and an assessment may not write
     * anything ({@code BE-D22}).
     */
    private List<TargetReplica> enumerate(PlacementRef placement, DockerPlacement p) {
        List<JsonNode> found = engine.list(p.containerNamePrefix());
        if (found.isEmpty()) {
            throw new RuntimeAdapterException(new ErrorClass("docker.replica.absent"));
        }
        return found.stream().map(c -> c.path("Id").asText()).sorted()
                .map(id -> new TargetReplica(placement, new ReplicaRef(id))).toList();
    }

    private static boolean declares(LaunchSource launch, String containerConfigPath) {
        String want = trimTrailingSlash(containerConfigPath);
        return launch.declaredLocations().stream()
                .map(DockerAdapter::trimTrailingSlash)
                .anyMatch(want::equals);
    }

    /**
     * {@code SM-D21}'s round-2 correction, executable: Cache's image does {@code RUN mkdir -p
     * /app/external/spring/config} and declares <b>no {@code VOLUME}</b>, so without a bind or a
     * named volume the path lives in the container layer and SM's config is destroyed the moment the
     * container is replaced. Discovering the right path is necessary and not sufficient.
     */
    private boolean isPersistentlyMounted(JsonNode inspect, DockerPlacement p) {
        String want = trimTrailingSlash(p.containerConfigPath());
        for (JsonNode mount : inspect.path("Mounts")) {
            String destination = trimTrailingSlash(mount.path("Destination").asText());
            boolean covers = want.equals(destination) || want.startsWith(destination + "/");
            if (covers && mount.path("RW").asBoolean()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Proves the directory SM writes to and the directory the container reads from are the same
     * directory — from the container's own side, with no {@code exec} and no assumption that the
     * daemon's filesystem namespace is SM's.
     *
     * <p>Comparing {@code Mounts[].Source} to a configured host path would only compare two strings
     * SM was told; this writes a uniquely named probe through SM's own file handle and asks the
     * daemon what is at that path <b>inside</b> the container. Measured: {@code HEAD /archive}
     * answers {@code 200} with a base64 stat header and {@code 404} when it is not there.
     *
     * <p>The probe is deleted in a finally block, and is named so that a leaked one is obvious and
     * harmless — it is not the config file, and Spring ignores dotfiles in an additional location.
     */
    private boolean probeRoundTrips(TargetReplica r, DockerPlacement p) {
        String name = ".sm-preflight-" + UUID.randomUUID();
        Path local = p.localConfigDir().resolve(name);
        try {
            Files.createDirectories(p.localConfigDir());
            Files.write(local, PROBE_BYTES);
            return engine.statInContainer(r.ref().value(), containerPathOf(p, name))
                    .filter(stat -> stat.size() == PROBE_BYTES.length)
                    .isPresent();
        } catch (IOException e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(local);
            } catch (IOException ignored) {
                // A leaked probe is a dotfile Spring does not read. Failing pre-flight over the
                // cleanup would refuse an apply for a reason that is not about the apply.
            }
        }
    }
```

- [ ] **Step 5: Run to green**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dtest=LaunchSourceTest -Dit.test=DockerPrepareIT`
Expected: surefire `LaunchSourceTest` 5/0; failsafe `DockerPrepareIT` 5/0.

- [ ] **Step 6: Prove the guards fire**

| Mutation | Expected red |
|---|---|
| `prepare` inspects only `replicas.get(0)` instead of every replica | add a fixture where replica 2 alone carries `SPRING_CONFIG_LOCATION`; `aCompetingConfigSourceIsRefused` goes red with 2 replicas — **write this fixture as part of the task**, since a single-replica suite cannot distinguish the two loops |
| `isPersistentlyMounted` returns `true` unconditionally | `aDeclaredPathWithNoMountIsRefused` — 1 red |
| `probeRoundTrips` returns `true` without calling `statInContainer` | `aMountThatIsNotTheDirectorySmWritesToIsRefused` — 1 red |
| `LaunchSource.of` reads only `Entrypoint` | `aNullEntrypointDoesNotHideTheCommand` — 1 red |

The first row is the important one and is a **test addition**, not only a mutation: a prepare that checks one replica passes every single-replica test there is. `twoContainersSharingThePrefixAreTwoReplicas` proves enumeration; only a per-replica *violation* fixture proves per-replica *checking*.

- [ ] **Step 7: Commit**

```bash
git add backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/docker \
        backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker
git commit -m "$(cat <<'MSG'
Add DockerAdapter.prepare: launch source, mount persistence, probe round-trip (Task 4)

SM-D21 in full, including its round-2 correction: a declared path with no bind
or volume is REFUSED, because Cache's image creates that directory in the
container layer and declares no VOLUME, so SM's config would die with the
container. Pre-flight re-reads the launch spec of EVERY replica on EVERY apply.

Mount identity is proven, not asserted: a probe file written through SM's own
file handle is stat'd inside the container via HEAD /archive. Comparing
Mounts[].Source to a configured path would only compare two strings SM was told.

Prove-it-fires: single-replica loop -> 1 red against a new two-replica violation
fixture; mount check stubbed true -> 1 red; probe stubbed true -> 1 red;
Entrypoint-only parse -> 1 red.
MSG
)"
```

**Risks:** `Mounts[].Destination` covering a *parent* of the config path is accepted as persistent. That is correct (a mount at `/app/external` persists `/app/external/spring/config`) but it is a widening, so `isPersistentlyMounted` has the prefix comparison spelled out rather than a `startsWith` that would also match `/app/externalized`.

---

### Task 5: `DockerAdapter.materializeConfig` and `status`

**Files:**
- Modify: `.../docker/DockerAdapter.java` (two operations)
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/DockerMaterializeAndStatusIT.java`

**Interfaces:**
- Consumes: `ConfigFileStore` (Task 1), `DockerEngineClient` (Task 3), `DockerPlacement` (Task 4).
- Produces: `MaterializeResult materializeConfig(...)`, `RuntimeStatus status(...)`. Task 8's step loop calls both; Task 9's `HealthGate` polls `status`; Task 10's `ResumePlanner` consumes `RuntimeStatus.hash()` and `.startBaseline()`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void materializeWritesThroughTheBindAndTheContainerSeesIt() { /* write, then statInContainer
        reports the exact byte length — the same round-trip prepare uses, now on the real file */ }

    @Test
    void materializeHasZeroLifecycleEffectOnARealContainer() { /* State.StartedAt byte-identical
        before and after — the measured claim SM-D15/AG-D3 rest on */ }

    @Test
    void statusReadsBackTheHashLivenessBaselineAndImageIdentity() { /* observedHash equals the
        written hash; Liveness.RUNNING; baseline.startedAt parses State.StartedAt;
        runtimeRef carries CONTAINER_ID, IMAGE_DIGEST and MOUNT_PATH */ }

    @Test
    void aRuntimeWithNoHealthcheckReportsNotDeclaredRatherThanHealthy() { /* busybox with no
        --health-cmd: State has no Health key, measured. HealthSignal.NOT_DECLARED. */ }

    @Test
    void aStoppedReplicaIsNeverReportedHealthyEvenThoughDockerStillSaysUnhealthy() { /* stop, then
        assert Liveness.STOPPED and health != HEALTHY — the measured staleness trap */ }

    @Test
    void statusOfAnAbsentContainerIsAbsentWithNoBaseline() { /* Liveness.ABSENT, baseline empty —
        §2.2 rule 3's indeterminate trigger, reported rather than thrown */ }

    @Test
    void nothingAtThePathIsAnEmptyHashRatherThanAnException() { /* observedHash null */ }
```

- [ ] **Step 2: Run to verify red** — `UnsupportedOperationException` from the not-yet-written methods, 7 errors.

- [ ] **Step 3: Implement**

```java
    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation,
            RenderedConfig config) {
        DockerPlacement p = catalog.lookup(replica.placement());
        // Zero lifecycle effect, structurally: this method holds no reference to `engine` at all.
        // The claim "writing configuration never restarts anything" is then a property of the code's
        // shape rather than of its current control flow.
        String written = store.write(p.localConfigDir(), p.fileName(), config, generation);
        return new MaterializeResult(written, runtimeRef(p, replica.ref().value(), null));
    }

    @Override
    public RuntimeStatus status(TargetReplica replica) {
        DockerPlacement p = catalog.lookup(replica.placement());
        JsonNode inspect;
        try {
            inspect = engine.inspect(replica.ref().value());
        } catch (RuntimeAdapterException e) {
            if ("docker.container.absent".equals(e.errorClass().token())) {
                return new RuntimeStatus(Liveness.ABSENT, HealthSignal.NOT_DECLARED, null, null,
                        runtimeRef(p, replica.ref().value(), null));
            }
            throw e;
        }

        JsonNode state = inspect.path("State");
        boolean running = state.path("Running").asBoolean();
        Liveness liveness = running ? Liveness.RUNNING : Liveness.STOPPED;

        // Liveness is read FIRST and gates the health read. Measured 2026-09-11: a stopped
        // container's State.Health.Status reads "unhealthy" -- stale, not absent -- so a health
        // read that ignored liveness would report a dead replica's last opinion as current.
        HealthSignal health = health(state, running);

        String observedHash = store.readBackHash(p.localConfigDir().resolve(p.fileName()))
                .orElse(null);

        // A container that has never started carries State.StartedAt "0001-01-01T00:00:00Z";
        // treating that as a baseline would make a first-ever restart unobservable.
        PreBaseline baseline = running ? baselineOf(state, replica) : null;

        return new RuntimeStatus(liveness, health, observedHash, baseline,
                runtimeRef(p, replica.ref().value(), inspect.path("Image").asText(null)));
    }

    private static HealthSignal health(JsonNode state, boolean running) {
        if (!state.has("Health")) {
            return HealthSignal.NOT_DECLARED;      // no healthcheck declared, measured as an ABSENT key
        }
        if (!running) {
            return HealthSignal.UNHEALTHY;         // never HEALTHY while down, whatever the field says
        }
        return switch (state.path("Health").path("Status").asText("")) {
            case "healthy" -> HealthSignal.HEALTHY;
            case "starting" -> HealthSignal.STARTING;
            default -> HealthSignal.UNHEALTHY;
        };
    }

    private static PreBaseline baselineOf(JsonNode state, TargetReplica replica) {
        String startedAt = state.path("StartedAt").asText("");
        if (startedAt.isBlank() || startedAt.startsWith("0001-01-01")) {
            return null;
        }
        try {
            return new PreBaseline(Instant.parse(startedAt), replica.ref().value());
        } catch (DateTimeParseException e) {
            // An unparseable baseline is "missing or unreadable" — §2.2 rule 3's indeterminate
            // trigger — reported as absence, not thrown, so the ENGINE decides what that means.
            return null;
        }
    }
```

`runtimeRef(p, containerId, imageId)` builds `RuntimeRef` from `CONTAINER_ID`, `MOUNT_PATH` (the **container-side** path, never the host one) and, when present, `IMAGE_DIGEST`.

- [ ] **Step 4: Run to green** — failsafe `DockerMaterializeAndStatusIT` 7/0.

- [ ] **Step 5: Prove the guards fire**

| Mutation | Expected red |
|---|---|
| `health()` reads `Health.Status` before checking `running` | `aStoppedReplicaIsNeverReportedHealthyEvenThoughDockerStillSaysUnhealthy` — 1 red |
| `health()` returns `HEALTHY` when `Health` is absent | `aRuntimeWithNoHealthcheckReportsNotDeclaredRatherThanHealthy` — 1 red |
| `baselineOf` drops the `0001-01-01` guard | add a never-started container fixture; `statusOfAnAbsentContainerIsAbsentWithNoBaseline`'s sibling goes red |
| `materializeConfig` calls `engine.restart(...)` after writing | `materializeHasZeroLifecycleEffectOnARealContainer` — 1 red, **and** the contract kit's `materializeHasZeroLifecycleEffect` once Task 7 Step 8 wires it |

- [ ] **Step 6: Commit** — message states the three measured Docker behaviours this task encodes (absent `Health` key, stale `Health` while stopped, `0001-01-01` start time) and the mutation counts.

**Risks:** `status` reads the hash from **SM's** view of the file, not the container's. Task 4's probe round-trip is what makes that sound, and the two are coupled: if `prepare`'s probe is ever weakened, `status`'s read-back silently stops meaning what it says. Recorded as a watch item in Task 13, and the coupling is spelled out in `status`'s javadoc so it is visible where it matters.

---

### Task 6: `startOrUpdate`, `stop`, `assessRollback`, `restore` — and the never-a-pull witness

**Files:**
- Modify: `.../docker/DockerAdapter.java` (the last four operations)
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/DockerLifecycleIT.java`

**Interfaces:**
- Produces: `LifecycleResult startOrUpdate(...)`, `LifecycleResult stop(...)`, `RollbackAssessment assessRollback(...)`, `RestoreResult restore(...)`. With these, `DockerAdapter` implements `RuntimeAdapter` completely and Task 7 Step 8 can run the contract kit against it.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void startOrUpdateRestartsOneReplicaAndMovesItsBaseline() { /* StartedAt changes; issued=true */ }

    /**
     * SM-D2 and I1, executable. "Never pulls, replaces or re-tags an artifact" is a claim about
     * identity, and the container's own Image field is the identity: measured unchanged across a
     * restart, and it would change under every operation the rule forbids.
     */
    @Test
    void startOrUpdateNeverChangesTheImageIdentity() { /* inspect.Image identical before/after */ }

    @Test
    void startOrUpdateActsOnOneReplicaOnly() { /* two replicas; the sibling's StartedAt is
        byte-identical afterwards -- rolling strategies are per-replica by construction */ }

    @Test
    void stopReports204AsActedAnd304AsAlreadyThere() { /* issued true then false */ }

    @Test
    void assessRollbackNamesTheTargetHashAndLeavesTheFileAlone() { /* Restorable(targetHash) equal
        to the pre-image's hash; the file on disk still holds the NEW generation afterwards */ }

    @Test
    void assessRollbackOfAGenerationNeverMaterializedIsNothingToRestore() { /* NothingToRestore --
        FAILED_NO_EFFECT's evidence, and the file on disk is untouched */ }

    @Test
    void assessRollbackOfAFirstApplyIsUnknowableBecauseAbsenceHasNoHash() { /* write generation 1
        into an EMPTY config dir, then assess: Unknowable("rollback.target.absent"), because
        hash_on_config_steps gives a ROLLBACK intent nowhere to put "there was no file" */ }

    @Test
    void assessRollbackIsUnknowableWhenAReplicaStateCannotBeRead() { /* remove the container under
        the adapter: Liveness.ABSENT -> Unknowable("rollback.state.unreadable"), never a guess */ }

    @Test
    void restorePutsThePreImageBackAndIsIdempotent() { /* restore(placement, G, targetHash) twice;
        the file hashes to targetHash after each, and the container is NOT restarted -- a restore
        writes config, and SM-D15's "config write != restart" holds on this path too */ }
```

- [ ] **Step 2: Run to verify red** — 7 errors.

- [ ] **Step 3: Implement**

```java
    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
        DockerPlacement p = catalog.lookup(replica.placement());
        // The DECLARED verb (SM-D19). On Docker that is a container restart of the SAME image:
        // this adapter has no code path that pulls, tags, creates or replaces a container, and
        // DockerEngineClient exposes none — the absence is structural, not a promise.
        boolean acted = engine.restart(replica.ref().value(), props.stopTimeoutSeconds());
        return new LifecycleResult(acted, runtimeRef(p, replica.ref().value(), imageIdOf(replica)));
    }

    @Override
    public LifecycleResult stop(TargetReplica replica) {
        DockerPlacement p = catalog.lookup(replica.placement());
        boolean acted = engine.stop(replica.ref().value(), props.stopTimeoutSeconds());
        return new LifecycleResult(acted, runtimeRef(p, replica.ref().value(), null));
    }

    // `enumerate(placement, p)` is the replica-listing half of Task 4's `prepare`, extracted there
    // into a private method so this operation can reuse it WITHOUT the probe round-trip: pre-flight's
    // probe writes a file, and an assessment may not write anything.

    /**
     * {@code BE-D22}'s read half. <b>Writes nothing</b> — no {@code store.write}, no
     * {@code store.restore}, no {@code engine.restart}, no {@code engine.stop}. That is the property
     * the contract kit asserts from the outside, and the reason the engine may journal this call as
     * the hashless {@code ROLLBACK_ASSESS} step.
     */
    @Override
    public RollbackAssessment assessRollback(PlacementRef placement, long generation,
            String intendedHash) {
        DockerPlacement p = catalog.lookup(placement);

        List<TargetReplica> replicas;
        try {
            replicas = enumerate(placement, p);       // list + inspect only; no probe, no write
        } catch (RuntimeAdapterException e) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unreadable"));
        }

        Set<String> observed = new LinkedHashSet<>();
        for (TargetReplica r : replicas) {
            RuntimeStatus now = status(r);
            if (now.liveness() == Liveness.ABSENT) {
                return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unreadable"));
            }
            observed.add(String.valueOf(now.observedHash()));
        }

        switch (store.preImageKind(p.localConfigDir(), p.fileName(), generation)) {
            case MISSING:
                // Nothing was ever written for this generation: the target is untouched.
                return new RollbackAssessment.NothingToRestore();
            case ABSENT:
                // There WAS no file before this generation, so a restore has no target hash and
                // hash_on_config_steps gives a ROLLBACK intent nowhere to record one. §2.2 rule 0
                // forbids acting without the entry, so this stops and asks the operator (BE-D22).
                return new RollbackAssessment.Unknowable(new ErrorClass("rollback.target.absent"));
            case PRESENT:
            default:
                String targetHash = store.preImageHash(p.localConfigDir(), p.fileName(), generation)
                        .orElseThrow(() -> new RuntimeAdapterException(
                                new ErrorClass("rollback.state.unreadable")));
                // BE-D22 correction A: exactly TWO values are journal-known -- the rollback target
                // and the MATERIALIZE intent's own hash. Their mixture is the ordinary partial-write
                // case, not divergence. A third value means a human or a competing writer got there
                // first, and B/D12 forbids reverting them.
                for (String seen : observed) {
                    if (!(targetHash.equals(seen) || intendedHash.equals(seen))) {
                        return new RollbackAssessment.Unknowable(new ErrorClass(
                                "null".equals(seen) ? "rollback.state.unreadable"
                                                    : "rollback.state.unknown"));
                    }
                }
                return new RollbackAssessment.Restorable(targetHash);
        }
    }

    /** {@code BE-D22}'s destructive half, journalled as {@code ROLLBACK} with {@code targetHash}. */
    @Override
    public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
        DockerPlacement p = catalog.lookup(placement);
        // BE-D22 correction B: the pre-image is re-hashed against the target the assessment
        // recorded, BEFORE anything is written. If it moved, nothing is written at all.
        ConfigFileStore.Restoration restoration =
                store.restore(p.localConfigDir(), p.fileName(), generation, targetHash);
        if (restoration == ConfigFileStore.Restoration.NOTHING_TO_RESTORE) {
            throw new RuntimeAdapterException(new ErrorClass("rollback.preimage.vanished"));
        }
        if (restoration == ConfigFileStore.Restoration.TARGET_MOVED) {
            throw new RuntimeAdapterException(new ErrorClass("rollback.preimage.altered"));
        }
        return new RestoreResult(runtimeRef(p, placement, null));
    }
```

**Two things this deliberately does not do.**

It does not restart a replica as part of a restore. Design §2.1 says lifecycle happens on rollback
*"only where the failed apply had already restarted that replica"*, and knowing that is the
**journal's** job, not the adapter's — the adapter cannot see `apply_attempt`. The engine owns that
routing and issues a separate `startOrUpdate`.

It does not report per-replica observations. `RollbackAssessment` and `RestoreResult` carry none by
design (`BE-D22`): a value the process forgets is not evidence. The per-replica record is the
`VERIFY` attempt rows the engine writes after a restore, and the `MATERIALIZE` attempt rows before
it.

- [ ] **Step 4: Run to green** — failsafe `DockerLifecycleIT` 7/0.

- [ ] **Step 5: Prove the guards fire**

| Mutation | Expected red |
|---|---|
| `startOrUpdate` restarts **every** container matching the prefix | `startOrUpdateActsOnOneReplicaOnly` — 1 red |
| `stop` returns `new LifecycleResult(true, …)` regardless | `stopReports204AsActedAnd304AsAlreadyThere` — 1 red |
| `assessRollback` calls `store.restore(...)` before returning `Restorable` | `assessRollbackNamesTheTargetHashAndLeavesTheFileAlone` — 1 red here, **and** the contract kit's `assessRollbackNamesTheTargetHashAndMutatesNothing` once Task 7 Step 8 wires it. Two independent proofs of the one invariant §3.1 says no CHECK can hold |
| `assessRollback` maps `PreImage.ABSENT` to `NothingToRestore` | `assessRollbackOfAFirstApplyIsUnknowableBecauseAbsenceHasNoHash` — 1 red, and the request would land `FAILED_NO_EFFECT` after a first apply that *did* write bytes |
| `assessRollback` skips the `Liveness.ABSENT` check | `assessRollbackIsUnknowableWhenAReplicaStateCannotBeRead` — 1 red |
| `restore` returns a `RestoreResult` instead of throwing on `NOTHING_TO_RESTORE` | `restorePutsThePreImageBackAndIsIdempotent`'s sibling (delete the pre-image between assess and restore) — 1 red: the engine would land `FAILED_ROLLED_BACK` with nothing restored |
| A **planted pull**: add `engine.post("/v1.43/images/create?fromImage=busybox&tag=latest")` before the restart | `startOrUpdateNeverChangesTheImageIdentity` must go red. **If it does not, the test is decoration** — the image identity is `sha256:`-pinned so a same-tag pull of the same digest changes nothing. In that case, plant `docker tag busybox:1.36 busybox:sm-planted` + a recreate instead, and record which form was needed. This one matters more than the others: `I1` is a hard constraint, and a hard constraint with an unfalsifiable test is the worst of both. |

- [ ] **Step 6: Commit** — message records which form of the planted-pull mutation was needed to turn the `I1` test red, and why.

**Risks:** `stop`'s `304` handling makes "already stopped" indistinguishable from "stopped by someone else a moment ago". That is correct for idempotency and wrong for audit; the *journal* is what distinguishes them, through the `pre_baseline` the attempt row already carries.

---

### Task 7: The `I6` content obligation — sentinels through every evidence builder

Plan 004 named this exactly and left it open: *"each adapter's `RuntimeRef`/`PreBaseline`/`ErrorClass` builder is the only writer of those columns and must be tested against sentinel secrets drawn from its own inputs (docker inspect output, the container's environment, mounted files); `mountPath` and `host` are the keys most worth a sentinel; `imageDigest` is derived from §2.3's 'same image — never a pull' rather than named there and should be confirmed or dropped when the Docker adapter is designed."*

The evidence types are **shape** controls — a bare identifier-shaped token is measured *accepted* by `ValueGrammar`. The content control is this task, and it is per-adapter by construction, because only the adapter knows which of its inputs are secret-dense.

**Files:**
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/DockerEvidenceSecrecyIT.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/docker/DockerAdapterContractIT.java`
- Modify: `.../docker/DockerAdapter.java` only if a leak is found — a fix, not a refactor.

- [ ] **Step 1: Write the failing test**

One container, carrying a distinct sentinel in every input the adapter reads:

| Input | Sentinel |
|---|---|
| container **name** | `sm-cache-SENTINELNAME-1` |
| container **label** | `com.solace.sm.note=SENTINELLABEL` |
| **environment** | `SOLACE_PASSWORD=SENTINELENV` |
| bind-mount **host path** | a temp dir whose last segment is `SENTINELMOUNT` |
| **config file content** | `secret: SENTINELCONTENT` |
| the daemon's **error body** | provoked by inspecting `sm-SENTINELABSENT` |

```java
    private static final List<String> SENTINELS = List.of("SENTINELNAME", "SENTINELLABEL",
            "SENTINELENV", "SENTINELMOUNT", "SENTINELCONTENT", "SENTINELABSENT");

    @Test
    void noSentinelReachesAnyEvidenceValueOrAnyLogLine() throws Exception {
        LogCapture capture = LogCapture.attachToRoot();     // the plan 004 harness, reused
        try {
            // Drive the whole protocol: prepare, materialize, status, startOrUpdate, stop, rollback,
            // plus one deliberate refusal so the daemon's own error text has a chance to escape.
            ...
            List<String> evidence = new ArrayList<>();
            evidence.addAll(status.runtimeRef().fields().values());
            evidence.add(status.startBaseline().map(PreBaseline::processRef).orElse(""));
            evidence.add(refusal.errorClass().token());
            evidence.add(refusal.getMessage());
            evidence.add(String.valueOf(refusal.getCause()));
            evidence.add(capture.everything());

            for (String sentinel : SENTINELS) {
                for (String value : evidence) {
                    assertFalse(value.contains(sentinel),
                            "a value the adapter produced carries " + sentinel);
                }
            }
        } finally {
            capture.detach();
        }
    }

    /**
     * The scan above is only worth its runtime if the sentinels were REACHABLE. A capture that saw
     * nothing would pass it for free -- exactly the vacuous-negative shape plan 004's own review
     * caught twice. So: prove each sentinel is present in the daemon's answer BEFORE asserting it is
     * absent from the adapter's output.
     */
    @Test
    void everySentinelIsActuallyPresentInWhatTheDaemonHandsTheAdapter() {
        JsonNode raw = engine.inspect(containerId);
        assertTrue(raw.toString().contains("SENTINELNAME"));
        assertTrue(raw.toString().contains("SENTINELLABEL"));
        assertTrue(raw.toString().contains("SENTINELENV"));
        assertTrue(raw.toString().contains("SENTINELMOUNT"));
        // SENTINELCONTENT is in the file, not the inspect output; assert it through the store.
        assertTrue(Files.readString(localConfig).contains("SENTINELCONTENT"));
    }

    /**
     * imageDigest: CONFIRM, not drop. Plan 004 asked the question and this is the answer.
     */
    @Test
    void theImageIdentityIsCarriedAndIsNotSecretDense() {
        String image = status.runtimeRef().fields().get(RuntimeRef.Key.IMAGE_DIGEST);
        assertNotNull(image, "IMAGE_DIGEST is the witness startOrUpdateNeverChangesTheImageIdentity "
                + "rests on; dropping it would leave I1 tested only in a live comparison and never "
                + "recorded in the journal");
        assertTrue(image.startsWith("sha256:"));
        assertTrue(image.length() <= 128, "ValueGrammar.VALUE's bound; measured at 71 characters");
        assertFalse(image.contains("/"), "a local image ID carries no registry host, so it cannot "
                + "carry registry credentials in a userinfo segment");
    }
```

- [ ] **Step 2: Run and read the result honestly**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test=DockerEvidenceSecrecyIT`

There are two legitimate outcomes and they are handled differently:

- **Green on the first run** — then the presence test above is what makes it meaningful, and the task's value is the *pin*, not a fix. Record that plainly; do not manufacture a fix to have something to show.
- **Red** — a real leak. Fix it at the builder (drop the field, or replace it with a classifier), re-run, and record the leak and the fix. `MOUNT_PATH` is the most likely: it is the one key whose value is a filesystem path drawn from the deployment, and this plan puts the **container-side** path there for exactly that reason (Task 5), which this test is the check on.

- [ ] **Step 3: Prove the scan fires**

Plant a leak and confirm the count: add `RuntimeRef.Key.HOST → p.localConfigDir().toString()` to `runtimeRef(...)`. Expected: `noSentinelReachesAnyEvidenceValueOrAnyLogLine` red on `SENTINELMOUNT`. Revert.

Then plant the *vacuity*: make the container carry no sentinels at all. Expected: `everySentinelIsActuallyPresentInWhatTheDaemonHandsTheAdapter` red — which is the assertion that stops the whole class passing for free.

- [ ] **Step 4: Run the contract kit against Docker**

```java
package com.solace.sm.dp.apply.runtime.docker;

import com.solace.sm.dp.apply.runtime.AbstractRuntimeAdapterContractTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same eleven obligations, against a real daemon. Where the in-memory subclass skips
 * {@code startOrUpdateNeverChangesTheArtifact} by assumption, this one does not: Docker publishes an
 * image identity, so the {@code I1} obligation is live here.
 */
@Testcontainers(disabledWithoutDocker = true)
class DockerAdapterContractIT extends AbstractRuntimeAdapterContractTest {

    @Override
    protected Fixture newFixture() { /* a fresh busybox with the Cache's config layout, a
        healthcheck declared in the run spec, its own temp bind directory, and a catalog resolving
        one placement to it */ }
}
```

Run: `-Dit.test=DockerAdapterContractIT`. Expected: **11/0**, with `startOrUpdateNeverChangesTheArtifact` actually executing rather than skipping — assert that by reading the report, not the console.

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'MSG'
Close the I6 content obligation for the Docker adapter's evidence (Task 7)

Plan 004 left this open by name: the evidence types are shape controls and a bare
identifier-shaped token is measured accepted, so the content control is
per-adapter. Six sentinels -- container name, label, environment, host mount
path, config content, and the daemon's own error text -- are proven PRESENT in
what the daemon hands the adapter, then proven absent from every RuntimeRef
value, PreBaseline.processRef, ErrorClass token, exception message, cause and
log line.

imageDigest: CONFIRMED, not dropped. It holds the container's local image ID
(sha256:..., 71 chars, no registry host), which is the witness the I1 never-a-pull
test rests on and the only artifact identity the journal would otherwise lack.

Prove-it-fires: host path planted into RuntimeRef -> 1 red on SENTINELMOUNT;
sentinels removed from the fixture -> 1 red on the presence assertion, which is
what stops the scan passing for free.

DockerAdapterContractIT: 11/11, with the I1 obligation executing rather than
skipped.
MSG
)"
```

**Measured residual, recorded not papered over (execution, Task 7).** `docker-java-transport-zerodep` shades an Apache HttpClient that logs the **request URI** at DEBUG, and the URI is where a container name or id lives — so an identifier reaches the log whenever that shaded logger is at DEBUG. The sweep therefore splits its sentinels: the **secret-dense** four (label, environment, host mount path, config bytes) must appear in no evidence value and no log line, and do not; the **identifier** two are asserted absent from every evidence value, and their DEBUG-log appearance is pinned by its own test so that closing the residual later is a visible change rather than a silent one. This is the `JdbcUrls` residual in a second place, bounded the same way: `I6` is about secret values, and a container identifier is the same class of thing `apply_attempt.runtime_ref` deliberately stores. ⚠️ **Runbook note:** if container names are made to carry tenant or customer identifiers, those names are visible to anyone who can enable DEBUG on `com.github.dockerjava.zerodep.shaded`. The adapter cannot fix it — it cannot issue the request without the name — so naming is the deployment's call.

**Risks:** the sentinel set is drawn from the inputs *this adapter reads today*. A future operation that reads a new input (`docker logs`, `exec` output, a registry credential) needs its own sentinel, and nothing forces that. Recorded as a watch item, and named in the class javadoc as the maintenance rule.

---

### Task 8: `ApplyExecutor` — the complete single-placement `manual` slice, and every failure it can reach

**Task 8 is a vertical slice, not a prefix.** Everything the public `execute(ApplyCommand)` path can
reach under `manual` — including every failure route and the whole `BE-D22` exhaustion journal —
lands here. Nothing in this task references a type, method, helper or strategy behaviour first
created by Task 9 or Task 10; the [execution-boundary audit](#execution-boundary-audit) records that
symbol by symbol.

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/ApplyOutcome.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/ApplyEngineProperties.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/ApplyExecutor.java`
- Modify: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/ApplyEngineConfiguration.java`
- Modify: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ApplyEngineHarness.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ManualApplyIT.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/InstanceWideIdentityLimitIT.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/EnsureIntentRaceIT.java`

**Interfaces:**
- Consumes, from **Task 1 only** (`RuntimeAdapter`, `PlacementRef`, `TargetReplica`, `RenderedConfig`, `RenderedConfigSource`, `PreflightResult`, `MaterializeResult`, `RollbackAssessment`, `RestoreResult`, `RuntimeStatus`, `RuntimeAdapterException`) and **Task 2 only** (`InMemoryRuntimeAdapter`, test scope), plus plan 004's `AdmissionService.admit`, `StepIntentService.ensureIntent`, `AttemptService.open`/`close`/`RetryCapExceeded`, `OutcomeService.start`/`land`, the repositories and `model` — **all unchanged**.
- **Does not consume Tasks 3–7.** Its integration tests drive the in-memory adapter against a real PostgreSQL, so the Docker adapter is not on its path. Task 8 is parallel to Tasks 3–7, not downstream of them.
- Produces:
  ```java
  public sealed interface ApplyOutcome {
      record Landed(ApplyId applyId, RequestState state, ErrorClass errorClass) implements ApplyOutcome {}
      record NotAdmitted(RefusalReason reason, String detail) implements ApplyOutcome {}
      record StrategyUnsupported(Strategy strategy, String detail) implements ApplyOutcome {}
      record ScopeUnsupported(ScopeKey scope, String detail) implements ApplyOutcome {}
  }
  public ApplyOutcome execute(ApplyCommand command);
  static String idempotencyKey(ApplyId id, Step step, ReplicaRef replica);
  ```
  Task 9 widens the supported-strategy set and adds `restartAndGate`; Task 10 adds `resume` and reuses Task 8's `exhausted` unchanged.

**Two refusals before admission, both permanent, both persisting nothing.**

| Variant | Fires when | Why before admission |
|---|---|---|
| `StrategyUnsupported` | the strategy is not `MANUAL` (Task 9 widens this to all but `SCHEDULED`, which stays out of plan 005) | `SM-D15`: *"SM offers only strategies the solution's declared capabilities support."* No row for a request that was never going to run. |
| `ScopeUnsupported` | `placement_id` is the instance-wide nil sentinel (`BE-D23`) | `apply_step_intent`'s identity cannot express per-placement steps for one request. A request the engine cannot journal correctly must not occupy the scope, and a `FAILED_*` row would imply an attempt was made. |

Neither is a stub. `ScopeUnsupported` survives until an identity amendment lands in a **persistence**
plan; `StrategyUnsupported` survives for `SCHEDULED`. And neither is Task 9's health-signal case,
which is discovered by *observation* after admission and lands `FAILED_NO_EFFECT` — one is known
before anything is persisted, the other is not.

#### The order of operations, and why it makes each terminal state honest

```
execute ─► refuse non-MANUAL strategy            (nothing persisted)
        ─► refuse instance-wide scope            (nothing persisted)
        ─► admit ─► start ─► PREFLIGHT step, the one placement ─► MATERIALIZE ─► land STAGED
                             ├─ 1. render(placement, generation)     per replica
                             └─ 2. adapter.prepare(placement, generation)
                                nothing here mutates the target
```

**Rendering happens inside the `PREFLIGHT` step, before the adapter call, before the first
`materializeConfig`.** Three consequences, each load-bearing:

1. A render failure provably precedes every write — so `FAILED_NO_EFFECT` is a *measurement* ("no `MATERIALIZE` intent exists for this request") and not an assumption.
2. A render failure means the adapter was never invoked **at all**, not merely never asked to materialize.
3. The classifier has somewhere to live: `apply_attempt.error_class` on the `PREFLIGHT` attempt — *"classified, greppable; never raw secrets (`I6`)"*.

An earlier draft rendered inside the materialize loop, one placement at a time. Measured against the
state model that was wrong twice over: a render failure on placement 2 would follow placement 1's
completed writes, and — per `BE-D23` — there was never a second placement's journal to write it into.

`pre_baseline` is `null` on every step this task opens. That is **required**, not merely permitted:
`baseline_only_for_lifecycle CHECK (step = 'LIFECYCLE' OR pre_baseline IS NULL)`, and this task
issues no `LIFECYCLE` step.

#### The exhaustion journal (`BE-D22`), in full

Once a `MATERIALIZE` attempt has succeeded, `FAILED_NO_EFFECT` would be a false statement about the
target. Every failure from that point routes here:

```
ROLLBACK_ASSESS  @placement, config_hash NULL   adapter.assessRollback(p, G, intendedHash) ── read-only
   │   classifies each replica against exactly TWO journal-known values:
   │     · targetHash   — read back from the retained pre-image
   │     · intendedHash — the MATERIALIZE intent's own config_hash, handed in by the engine
   ├─ pre-image missing ────────────────────────────────────────────────►  FAILED_NO_EFFECT
   ├─ pre-image absent / unreadable / ANY replica on a THIRD value ─────►  APPLY_INDETERMINATE
   └─ Restorable(targetHash)   every replica on one of the two known values
         │                     (incl. the ordinary mixture: A on new, B on old)
         ▼
      ROLLBACK  @placement, config_hash = targetHash   ── OPENED BEFORE the call (§2.2 rule 0)
         │        adapter.restore re-hashes the pre-image and REFUSES if it moved
         ▼
      VERIFY  per replica, config_hash = targetHash    adapter.status ── the DURABLE per-replica record
         ├─ every replica at targetHash ──────────────────────────────►  FAILED_ROLLED_BACK
         ├─ all on known values, not all at target ───────────────────►  CONFIG_DIVERGED
         └─ any unreadable or third value ────────────────────────────►  APPLY_INDETERMINATE
```

Three properties this shape buys, none of which the single conflated operation could give:

- **A restore is never journalled as an assessment.** `ROLLBACK_ASSESS` is opened only around a call that `BE-D22` makes read-only and the contract kit proves read-only.
- **Only journal-known values are safe.** The ordinary partial-materialize mixture — replica A on the new generation, replica B still on the old — is what a rollback is *for*, so it is `Restorable`, not `CONFIG_DIVERGED`. A third, unknown value, or an unreadable one, means somebody else wrote to the file: the apply reaches `APPLY_INDETERMINATE` and **nothing is overwritten** (`B/D12` 🔒, *never silently auto-revert a human*). `restore` re-hashes the pre-image before writing, so a target that moved between assess and restore is refused rather than acted on.
- **The restore target is durable before the destructive act.** The `ROLLBACK` intent's `config_hash` is `targetHash`, written and committed before `restore` is called — so a crash in the window is resolved by reading each replica's hash back, never by guessing (Task 10's resume table).
- **Per-replica evidence is rows, not return values.** `RollbackAssessment` and `RestoreResult` carry no observations at all (`BE-D22`); the record is the `VERIFY` attempts, plus each replica's `MATERIALIZE` attempt — which this task closes with the hash `status` reads back **even on failure**, because `observed_hash` is *"read-back EVIDENCE, never intent"*.

- [ ] **Step 1: Write the failing tests**

```java
package com.solace.sm.dp.apply.persistence;

@Testcontainers(disabledWithoutDocker = true)
class ManualApplyIT {

    // ---- the happy path ---------------------------------------------------------------------

    @Test
    void aManualApplyStagesEveryReplicaAndTerminatesInStaged() {
        ApplyOutcome outcome = executor.execute(manualApply(scope, 7));   // one placement, 2 replicas

        ApplyId id = assertInstanceOf(ApplyOutcome.Landed.class, outcome).applyId();
        assertEquals(RequestState.STAGED, requests.find(t, id).orElseThrow().state());
        assertNull(((ApplyOutcome.Landed) outcome).errorClass());

        // One PREFLIGHT intent at @placement, one MATERIALIZE intent per replica. Nothing else:
        // BE-D12 §2.1 says the core NEVER calls startOrUpdate under manual.
        assertEquals(List.of(Step.MATERIALIZE, Step.MATERIALIZE, Step.PREFLIGHT),
                stepIntents.forRequest(t, id).stream().map(StepIntentRow::step).sorted().toList());
        assertEquals(0, adapter.restarts(), "manual means the operator restarts, and SM observes");
    }

    /** §2.2 rule 0, the ordering everything else rests on. */
    @Test
    void theAttemptRowIsCommittedBeforeTheAdapterIsCalled() {
        // The adapter is wrapped so materializeConfig asserts, on ANOTHER connection, that its own
        // attempt row is already visible. An intent with no attempt row provably issued nothing;
        // that is only true if the row lands first.
    }

    @Test
    void theIntentBindsTheHashSoADifferingRetryIsAProtocolError() {
        // ensureIntent with the same key and a DIFFERENT hash -> StepIntentService.IntentMismatch
    }

    @Test
    void admissionRefusalIsReportedRatherThanThrown() {
        // a second overlapping apply -> ApplyOutcome.NotAdmitted(SCOPE_BUSY, ...)
    }

    // ---- the two pre-admission refusals -------------------------------------------------------

    /** SM-D15, and the reason Task 8 has no non-manual code path to test. */
    @Test
    void aNonManualStrategyIsRefusedBeforeAdmissionAndPersistsNothing() {
        ApplyOutcome outcome = executor.execute(rollingApply(scope, 7));

        ApplyOutcome.StrategyUnsupported refused =
                assertInstanceOf(ApplyOutcome.StrategyUnsupported.class, outcome);
        assertEquals(Strategy.ROLLING_HEALTH_GATED, refused.strategy());
        assertEquals(List.of(), requests.nonTerminal(t),
                "nothing may be persisted for a request this build was never going to run");
        assertInstanceOf(ApplyOutcome.Landed.class, executor.execute(manualApply(scope, 7)),
                "…and the scope is therefore still admissible");
    }

    /**
     * BE-D23. The journal cannot hold two placements' placement-scoped steps under one apply_id, so
     * an instance-wide request is refused before anything at all is written.
     */
    @Test
    void anInstanceWideRequestIsRefusedBeforeAdmissionAndPersistsNothing() {
        ScopeKey instanceWide = new ScopeKey(t, instance, PlacementId.INSTANCE_WIDE);

        ApplyOutcome.ScopeUnsupported refused = assertInstanceOf(ApplyOutcome.ScopeUnsupported.class,
                executor.execute(manualApply(instanceWide, 7)));
        assertEquals(PlacementId.INSTANCE_WIDE, refused.scope().placement());

        // No apply row, no intent, no attempt, no adapter call -- all four, because "refused" must
        // mean the engine did not start rather than that it stopped early.
        assertEquals(List.of(), requests.nonTerminal(t));
        assertEquals(0, countAllIntents(t), "no intent");
        assertEquals(0, countAllAttempts(t), "no attempt");
        assertEquals(0, adapter.prepareCalls() + adapter.materializeCalls() + adapter.restarts(),
                "no adapter call");

        // …and the scope is untouched: a well-formed placement-scoped apply is admitted next.
        assertInstanceOf(ApplyOutcome.Landed.class, executor.execute(manualApply(scope, 7)));
    }

    // ---- pre-flight refusal -------------------------------------------------------------------

    @Test
    void aRefusedPreflightLandsFailedNoEffectAndWritesNoMaterializeIntent() {
        adapter.fault(InMemoryRuntimeAdapter.Fault.PREFLIGHT_REFUSED);
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.FAILED_NO_EFFECT,
                requests.find(t, landed.applyId()).orElseThrow().state());
        assertEquals(List.of(Step.PREFLIGHT), stepIntents.forRequest(t, landed.applyId()).stream()
                .map(StepIntentRow::step).toList());
        assertEquals("fake.preflight.refused", landed.errorClass().token());
    }

    // ---- render failure -----------------------------------------------------------------------

    /**
     * A renderer that throws leaves the request RUNNING and scope-blocking unless something routes
     * it. Rendering runs inside the PREFLIGHT step and before the adapter call, so nothing has been
     * written and nothing has been asked of the runtime: FAILED_NO_EFFECT is provable here, not
     * assumed. RUNNING -> FAILED_NO_EFFECT is a legal landing (RequestState.validFor(APPLY) admits
     * it, V006's terminal guard permits the transition out of RUNNING, and no watermark moves
     * because OutcomeService.land raises one only for STAGED/APPLIED).
     */
    @Test
    void aFailedRenderLandsFailedNoEffectWithNoMaterializeIntentAndNoAdapterCall() {
        RenderedConfigSource exploding = (p, g) -> {
            throw new IllegalStateException("cannot read /etc/sm/render/SENTINELRENDER.yml");
        };
        ApplyExecutor executor = harness.executor(adapter, exploding);

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.FAILED_NO_EFFECT,
                requests.find(t, landed.applyId()).orElseThrow().state());
        assertEquals(List.of(Step.PREFLIGHT), stepIntents.forRequest(t, landed.applyId()).stream()
                .map(StepIntentRow::step).toList());
        assertEquals(0, adapter.prepareCalls(),
                "rendering precedes the adapter call, so a render failure asks the runtime nothing");
        assertEquals(0, adapter.materializeCalls());
        assertEquals("render.failed", landed.errorClass().token());

        // The classifier is PERSISTED, not merely returned.
        AttemptRow attempt = attempts.latest(t, onlyIntent(t, landed.applyId()).intentId())
                .orElseThrow();
        assertEquals(AttemptOutcome.FAILED, attempt.outcome());
        assertEquals("render.failed", attempt.errorClass().token());
        assertNull(attempt.preBaseline(), "baseline_only_for_lifecycle: PREFLIGHT carries none");
    }

    @Test
    void aFailedRenderLeavesTheScopeAdmissibleForTheNextApply() {
        // FAILED_NO_EFFECT.blocksScope() is false, and no watermark was raised, so the SAME
        // generation is admissible again once the renderer is fixed. Both halves matter: a state
        // that unblocked the scope but raised a watermark would fence the retry at generation 7.
        ApplyExecutor broken = harness.executor(adapter, (p, g) -> { throw new IllegalStateException("x"); });
        broken.execute(manualApply(scope, 7));

        ApplyOutcome retry = executor.execute(manualApply(scope, 7));
        ApplyId id = assertInstanceOf(ApplyOutcome.Landed.class, retry).applyId();
        assertEquals(RequestState.STAGED, requests.find(t, id).orElseThrow().state());
    }

    /** I6: the renderer's own text is never read, so it cannot reach a column, an outcome or a log. */
    @Test
    void aFailedRenderPutsNoRendererTextIntoPersistenceOrTheOutcome() {
        LogCapture capture = LogCapture.attachToRoot();
        try {
            ApplyExecutor broken = harness.executor(adapter, (p, g) -> {
                throw new IllegalStateException("cannot read /etc/sm/render/SENTINELRENDER.yml");
            });
            ApplyOutcome.Landed landed = (ApplyOutcome.Landed) broken.execute(manualApply(scope, 7));

            AttemptRow attempt = attempts.latest(t, onlyIntent(t, landed.applyId()).intentId())
                    .orElseThrow();
            for (String seen : List.of(attempt.errorClass().token(),
                    String.valueOf(attempt.runtimeRef()), landed.errorClass().token(),
                    String.valueOf(landed), capture.everything())) {
                assertFalse(seen.contains("SENTINELRENDER"), "renderer text leaked: " + seen);
                assertFalse(seen.contains("/etc/sm/render"), "renderer path leaked: " + seen);
            }
        } finally {
            capture.detach();
        }
    }

    // ---- the exhaustion journal (BE-D22) --------------------------------------------------------

    /**
     * The first point at which FAILED_NO_EFFECT would be a LIE. Replica 1 is materialized, replica 2
     * fails: bytes are on disk, so the exhaustion path decides -- and it must journal the restore as
     * ROLLBACK, with the target hash, opened BEFORE the destructive call.
     */
    @Test
    void aMaterializeFailureAfterAPartialWriteJournalsAssessThenRollbackThenVerify() {
        adapter.failMaterializeOn("replica-2");
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.FAILED_ROLLED_BACK,
                requests.find(t, landed.applyId()).orElseThrow().state());

        Map<Step, List<StepIntentRow>> byStep = stepIntents.forRequest(t, landed.applyId()).stream()
                .collect(groupingBy(StepIntentRow::step));
        assertEquals(1, byStep.get(Step.ROLLBACK_ASSESS).size());
        assertNull(byStep.get(Step.ROLLBACK_ASSESS).get(0).configHash(),
                "hash_on_config_steps: the assessment restored nothing and carries no hash");
        assertEquals(1, byStep.get(Step.ROLLBACK).size());
        assertEquals(priorHash, byStep.get(Step.ROLLBACK).get(0).configHash(),
                "the restore target is DURABLE before the destructive call -- this is what makes "
                        + "resume decidable instead of a guess");
        assertEquals(2, byStep.get(Step.VERIFY).size(), "one VERIFY per replica");
    }

    /** The durable per-replica record, which no return value may stand in for. */
    @Test
    void everyReplicasRestoredHashIsReadableFromItsOwnVerifyAttempt() {
        adapter.failMaterializeOn("replica-2");
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        for (StepIntentRow verify : intentsFor(t, landed.applyId(), Step.VERIFY)) {
            AttemptRow a = attempts.latest(t, verify.intentId()).orElseThrow();
            assertEquals(priorHash, a.observedHash(),
                    "observed_hash is 'read-back EVIDENCE, never intent' -- and it is the ONLY "
                            + "per-replica record of the restore that survives the process");
        }
    }

    /** A failed MATERIALIZE still records what ended up on that replica's disk. */
    @Test
    void aFailedMaterializeAttemptCarriesTheHashStatusReadBack() {
        adapter.failMaterializeOn("replica-2");
        adapter.leaveBehindOnFailure("replica-2", partialHash);
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        AttemptRow failed = attempts
                .latest(t, intentFor(t, landed.applyId(), Step.MATERIALIZE, "replica-2").intentId())
                .orElseThrow();
        assertEquals(AttemptOutcome.FAILED, failed.outcome());
        assertEquals(partialHash, failed.observedHash(),
                "the per-replica evidence §3.2's CONFIG_DIVERGED row points at is THIS row");
    }

    @Test
    void anAssessmentThatCannotDecideLeavesTheRequestNonTerminalAndBlockingTheScope() {
        adapter.fault(InMemoryRuntimeAdapter.Fault.BASELINE_UNREADABLE);
        adapter.failMaterializeOn("replica-1");
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.APPLY_INDETERMINATE,
                requests.find(t, landed.applyId()).orElseThrow().state());
        assertNull(requests.find(t, landed.applyId()).orElseThrow().terminalAt(),
                "terminal_has_timestamp: APPLY_INDETERMINATE is NOT terminal, and carries no stamp");
        assertTrue(intentsFor(t, landed.applyId(), Step.ROLLBACK).isEmpty(),
                "no ROLLBACK intent may exist: nothing was restored, and an intent would say it was");
        assertInstanceOf(ApplyOutcome.NotAdmitted.class, executor.execute(manualApply(scope, 8)),
                "architecture.md §16.1: an unresolved indeterminate blocks the scope");
    }

    /**
     * BE-D22's one unjournallable case: the prior state was ABSENCE, so a ROLLBACK intent has no
     * config_hash to carry and rule 0 forbids restoring without the entry.
     */
    @Test
    void aFirstApplyThatFailsPartwayIsIndeterminateBecauseAbsenceHasNoHash() {
        // nothing was ever materialized for this placement before generation 7
        adapter.failMaterializeOn("replica-2");
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(freshScope, 7));

        assertEquals(RequestState.APPLY_INDETERMINATE,
                requests.find(t, landed.applyId()).orElseThrow().state());
        assertEquals("rollback.target.absent", landed.errorClass().token());
        assertTrue(intentsFor(t, landed.applyId(), Step.ROLLBACK).isEmpty());
    }

    // ---- BE-D22 corrections A and B: the four required proofs ---------------------------------

    /** (1) The ORDINARY partial write: A on the new hash, B on the target. Restorable, not diverged. */
    @Test
    void oneReplicaAtTheMaterializeHashAndOneAtTheTargetRestoresToFailedRolledBack() {
        adapter.failMaterializeOn("replica-2");          // A lands generation 7, B keeps the prior
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.FAILED_ROLLED_BACK,
                requests.find(t, landed.applyId()).orElseThrow().state(),
                "a mixture of the two journal-known values is what a rollback is FOR; classifying "
                        + "it CONFIG_DIVERGED would refuse to roll back the ordinary case");
        assertEquals(1, intentsFor(t, landed.applyId(), Step.ROLLBACK).size());
        assertEquals(2, intentsFor(t, landed.applyId(), Step.VERIFY).size());
    }

    /** (2) A third, unknown hash stops the restore and preserves the file. */
    @Test
    void anUnknownThirdHashPreventsTheRestoreAndPreservesTheFile() {
        adapter.failMaterializeOn("replica-2");
        adapter.competingWrite(replica2, COMPETING_HASH);      // neither target nor intended

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.APPLY_INDETERMINATE,
                requests.find(t, landed.applyId()).orElseThrow().state());
        assertEquals("rollback.state.unknown", landed.errorClass().token());
        assertTrue(intentsFor(t, landed.applyId(), Step.ROLLBACK).isEmpty(),
                "no ROLLBACK intent: the engine never even proposed to write");
        assertEquals(COMPETING_HASH, adapter.status(replica2).observedHash(),
                "B/D12: never silently auto-revert a human");
    }

    /** (3) The pre-image moves between assess and restore: restore refuses, writes nothing. */
    @Test
    void aPreImageAlteredBetweenAssessAndRestoreIsRefusedAndNothingIsWritten() {
        adapter.failMaterializeOn("replica-2");
        adapter.alterPreImageAfterAssess(7, ALTERED_HASH);     // the world moves mid-flight

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        assertEquals(RequestState.APPLY_INDETERMINATE,
                requests.find(t, landed.applyId()).orElseThrow().state());
        assertEquals("rollback.preimage.altered", landed.errorClass().token());
        // The ROLLBACK intent DOES exist -- the engine proposed the write before learning the target
        // had moved -- and its attempt is FAILED. That pair is the honest record of "we intended to
        // restore to X, and X was gone by the time we looked".
        StepIntentRow rollback = intentsFor(t, landed.applyId(), Step.ROLLBACK).get(0);
        assertEquals(AttemptOutcome.FAILED,
                attempts.latest(t, rollback.intentId()).orElseThrow().outcome());
        assertEquals(intendedHash, adapter.status(replica1).observedHash(),
                "replica 1 still holds what materialize wrote: restore wrote nothing at all");
    }

    /** §2.2 rule 4: the cap is spent BEFORE the exhaustion path is taken, and the DB counts it. */
    @Test
    void everyAllowedAttemptIsSpentBeforeTheExhaustionPathIsTaken() {
        adapter.failMaterializeAlways();
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) executor.execute(manualApply(scope, 7));

        StepIntentRow materialize = intentFor(t, landed.applyId(), Step.MATERIALIZE, "replica-1");
        assertEquals(harness.maxAttempts(), attempts.count(t, materialize.intentId()),
                "the bound is the database's count of attempts on this intent, not a field the "
                        + "engine keeps -- which is what makes it survive a crash");
    }
}
```

`InstanceWideIdentityLimitIT` — **the evidence for `BE-D23`**. These tests do not guard behaviour;
they **pin the model's edge**, so that when an identity amendment lands they go red and force this
plan's successor to notice. They call `StepIntentService` directly, because the executor now refuses
instance-wide before it can reach the journal at all.

```java
package com.solace.sm.dp.apply.persistence;

/**
 * Why BE-D23 makes the v1 engine single-placement, measured against the shipped DDL rather than
 * argued. Both tests assert the CURRENT model's behaviour; both are expected to go red when
 * apply_step_intent's identity grows a placement, and that is the point of writing them.
 */
@Testcontainers(disabledWithoutDocker = true)
class InstanceWideIdentityLimitIT {

    /**
     * UNIQUE (tenant_id, apply_id, step, replica_ref) + placement_scoped_steps pinning replica_ref
     * to the literal '@placement' == at most one PREFLIGHT intent per apply_id. A fan-out would
     * record placement B's pre-flight as a RETRY of placement A's.
     */
    @Test
    void twoPlacementsCannotBothHoldAPreflightIntentForOneApply() {
        StepIntentRow a = stepIntents.ensureIntent(t, id, Step.PREFLIGHT, ReplicaRef.PLACEMENT,
                ApplyExecutor.idempotencyKey(id, Step.PREFLIGHT, ReplicaRef.PLACEMENT), null);
        StepIntentRow b = stepIntents.ensureIntent(t, id, Step.PREFLIGHT, ReplicaRef.PLACEMENT,
                ApplyExecutor.idempotencyKey(id, Step.PREFLIGHT, ReplicaRef.PLACEMENT), null);

        assertEquals(a.intentId(), b.intentId(),
                "the second placement silently receives the FIRST placement's intent -- no error, "
                        + "no mismatch, and from here its attempts are numbered as retries of "
                        + "another placement's pre-flight");
    }

    /**
     * replica_ref is ADAPTER vocabulary (BE-D19): a systemd unit name or a Kubernetes workload name
     * is identical on two hosts. Two placements sharing a label share an intent -- and where their
     * rendered configs differ, a legitimate apply is reported as a protocol error.
     */
    @Test
    void twoPlacementsWithTheSameReplicaLabelCollapseOntoOneIntent() {
        ReplicaRef shared = new ReplicaRef("cache.service");

        StepIntentRow a = stepIntents.ensureIntent(t, id, Step.MATERIALIZE, shared,
                ApplyExecutor.idempotencyKey(id, Step.MATERIALIZE, shared), hashForPlacementA);
        assertEquals(a.intentId(),
                stepIntents.ensureIntent(t, id, Step.MATERIALIZE, shared,
                        ApplyExecutor.idempotencyKey(id, Step.MATERIALIZE, shared),
                        hashForPlacementA).intentId(),
                "same label, same hash: one intent covering two different hosts");

        assertThrows(StepIntentService.IntentMismatch.class,
                () -> stepIntents.ensureIntent(t, id, Step.MATERIALIZE, shared,
                        ApplyExecutor.idempotencyKey(id, Step.MATERIALIZE, shared),
                        hashForPlacementB),
                "and when the two placements render DIFFERENT configs -- the normal case -- the "
                        + "model reports a legitimate two-placement apply as a protocol error");
    }

    /**
     * The scenario the refusal prevents, stated as a test of the model rather than of the engine:
     * placement A materializes, placement B fails, and there is no second placement-scoped journal
     * to record B's assessment or A's restore in. An engine that fanned out would roll back only
     * the placement it noticed, leaving A changed under a request whose journal claims one
     * pre-flight and one assessment.
     */
    @Test
    void thereIsNoSecondPlacementScopedJournalToRollBackTheOtherPlacementIn() {
        stepIntents.ensureIntent(t, id, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT,
                ApplyExecutor.idempotencyKey(id, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT), null);
        assertEquals(1, intentsFor(t, id, Step.ROLLBACK_ASSESS).size());

        // A second placement's assessment resolves to the same row, so "we assessed both" is
        // unrepresentable -- and "we restored A" has nowhere to be written at all.
        stepIntents.ensureIntent(t, id, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT,
                ApplyExecutor.idempotencyKey(id, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT), null);
        assertEquals(1, intentsFor(t, id, Step.ROLLBACK_ASSESS).size());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test='ManualApplyIT,InstanceWideIdentityLimitIT'`
Expected: compilation failure — `ApplyExecutor`, `ApplyOutcome` and `ApplyEngineProperties` do not exist.

- [ ] **Step 3: Write `ApplyExecutor`**

```java
package com.solace.sm.dp.apply.engine;

/**
 * The apply step loop — {@code BE-D12} §2.2's safety properties, driven over plan 004's persistence
 * and one {@link RuntimeAdapter}.
 *
 * <p><b>Write before act</b> (rule 0): the intent row and the attempt row for the try about to run
 * are committed before the adapter is invoked, so an intent with no attempt row provably issued
 * nothing. Every service this class calls runs {@code REQUIRES_NEW} already
 * ({@link EngineTransactions}), so "committed" is a fact about the call returning rather than a hope
 * about an enclosing transaction.
 *
 * <p><b>Single placement</b> ({@code BE-D23}): an instance-wide request is refused before admission,
 * because {@code apply_step_intent}'s identity cannot hold two placements' placement-scoped steps
 * under one {@code apply_id}.
 *
 * <p><b>Under {@code manual} the core never calls {@code startOrUpdate}.</b> It stages and lands
 * {@code STAGED}, which {@code BE-D16} makes exclusively the manual strategy's terminal state.
 */
public final class ApplyExecutor {

    private final AdmissionService admission;
    private final StepIntentService intents;
    private final AttemptService attempts;
    private final OutcomeService outcomes;
    private final RuntimeAdapter adapter;
    private final RenderedConfigSource configs;
    private final ApplyEngineProperties props;

    /** A placement's pre-flight result: the replicas to address, and the bytes to write to them. */
    private record Plan(List<TargetReplica> replicas, RenderedConfig config) {}

    /** A step's result, or the classifier that ended it. Never both. */
    private record StepOutcome<T>(T value, ErrorClass errorClass) {
        static <T> StepOutcome<T> of(T v) { return new StepOutcome<>(v, null); }
        static <T> StepOutcome<T> failed(ErrorClass e) { return new StepOutcome<>(null, e); }
        boolean failed() { return errorClass != null; }
    }

    public ApplyOutcome execute(ApplyCommand command) {
        // SM-D15: "SM offers only strategies the solution's declared capabilities support." Refused
        // BEFORE admission, so no row is written for a request that was never going to run.
        if (command.strategy() != Strategy.MANUAL) {
            return new ApplyOutcome.StrategyUnsupported(command.strategy(),
                    "this build executes the manual strategy only");
        }
        // BE-D23: apply_step_intent's identity is placement-blind -- UNIQUE (tenant_id, apply_id,
        // step, replica_ref) with placement_scoped_steps pinning replica_ref to '@placement' admits
        // at most ONE PREFLIGHT intent per apply_id. A fan-out would journal placement B's
        // pre-flight as a retry of placement A's, and would have nowhere to record B's rollback.
        // Refused before admission, not landed as a failure: a request the engine cannot journal
        // correctly must not occupy the scope, and a FAILED_* row would imply an attempt was made.
        if (command.scope().placement().isInstanceWide()) {
            return new ApplyOutcome.ScopeUnsupported(command.scope(),
                    "the v1 journal cannot hold per-placement steps for one instance-wide request");
        }

        AdmissionOutcome admitted = admission.admit(command);
        if (admitted instanceof AdmissionOutcome.Refused r) {
            return new ApplyOutcome.NotAdmitted(r.reason(), r.detail());
        }
        ApplyId id = ((AdmissionOutcome.Admitted) admitted).applyId();
        TenantId t = command.scope().tenant();
        outcomes.start(t, id);
        return runManual(t, id, command);
    }

    private ApplyOutcome runManual(TenantId t, ApplyId id, ApplyCommand command) {
        long generation = command.generation();
        ScopeKey scope = command.scope();
        PlacementRef placement =
                new PlacementRef(scope.tenant(), scope.instance(), scope.placement());

        // ---- Phase 1: PREFLIGHT. Nothing here mutates the target: rendering writes nothing and
        // prepare() never mutates by contract. So a failure here is provably FAILED_NO_EFFECT -- a
        // measurement ("no MATERIALIZE intent exists"), not a guess.
        StepOutcome<Plan> preflight = preflight(t, id, placement, generation);
        if (preflight.failed()) {
            return land(t, id, RequestState.FAILED_NO_EFFECT, preflight.errorClass());
        }
        Plan plan = preflight.value();

        // ---- Phase 2: MATERIALIZE every replica (SM-D15's stage-everywhere ordering). From the
        // first successful write onward FAILED_NO_EFFECT would be a false statement, so every
        // failure here routes to BE-D22's exhaustion journal.
        for (TargetReplica replica : plan.replicas()) {
            ErrorClass failure = materialize(t, id, replica, generation, plan.config());
            if (failure != null) {
                return exhausted(t, id, placement, plan.replicas(), generation,
                        plan.config().hash(), failure);
            }
        }

        // ---- Phase 3: land. BE-D16: STAGED implies manual, and only manual reaches this method.
        return land(t, id, RequestState.STAGED, null);
    }

    /**
     * Pre-flight: render, then ask the adapter. <b>Rendering first is deliberate and load-bearing</b>
     * — it is pure and cannot touch the target, so a render failure means the runtime was asked
     * nothing at all, which is the strongest form of "no effect" available.
     */
    private StepOutcome<Plan> preflight(TenantId t, ApplyId id, PlacementRef placement,
            long generation) {
        StepIntentRow intent = intents.ensureIntent(t, id, Step.PREFLIGHT, ReplicaRef.PLACEMENT,
                idempotencyKey(id, Step.PREFLIGHT, ReplicaRef.PLACEMENT), null);
        AttemptRow attempt = attempts.open(t, id, intent, null, props.stepBudget());

        RenderedConfig config;
        try {
            config = configs.render(placement, generation);
        } catch (RuntimeException e) {
            // I6. The renderer's own message is NEVER read -- not getMessage(), not toString(), not
            // even the exception's class name. ONE fixed classifier, chosen here rather than
            // supplied by the throwing code, so a badly-behaved RenderedConfigSource cannot widen
            // it. ErrorClass's own constructor is the second line of defence: it refuses any token
            // outside ^[a-z][a-z0-9]*(\.[a-z0-9]+)*$.
            return closeFailed(t, attempt, new ErrorClass("render.failed"));
        }

        PreflightResult result;
        try {
            result = adapter.prepare(placement, generation);
        } catch (RuntimeAdapterException e) {
            return closeFailed(t, attempt, e.errorClass());
        }
        if (result instanceof PreflightResult.Refused refused) {
            attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, refused.reason(),
                    refused.runtimeRef());
            return StepOutcome.failed(refused.reason());
        }
        PreflightResult.Ready ready = (PreflightResult.Ready) result;
        attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, null, null,
                ready.runtimeRef());
        return StepOutcome.of(new Plan(ready.replicas(), config));
    }

    /**
     * One replica's config write, with §2.2 rule 4's bounded retry.
     *
     * @return {@code null} on success, or the last classifier seen once the cap is spent. The bound
     *         is {@link AttemptService}'s, which reads the database's own count of attempts on this
     *         intent — so it survives a crash, which a counter held here would not.
     */
    private ErrorClass materialize(TenantId t, ApplyId id, TargetReplica replica, long generation,
            RenderedConfig config) {
        StepIntentRow intent = intents.ensureIntent(t, id, Step.MATERIALIZE, replica.ref(),
                idempotencyKey(id, Step.MATERIALIZE, replica.ref()), config.hash());
        ErrorClass last = null;
        while (true) {
            AttemptRow attempt;
            try {
                attempt = attempts.open(t, id, intent, null, props.stepBudget());
            } catch (AttemptService.RetryCapExceeded e) {
                return last == null ? new ErrorClass("retry.cap.spent") : last;
            }
            try {
                MaterializeResult result = adapter.materializeConfig(replica, generation, config);
                RuntimeStatus status;
                try {
                    status = adapter.status(replica);
                } catch (RuntimeAdapterException e) {
                    last = e.errorClass();
                    attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, last,
                            result.runtimeRef());
                    continue;
                }
                // The adapter return is a claim, not evidence. STAGED requires the runtime's
                // read-back to equal the durable config_hash on the intent.
                String observed = status.observedHash();
                if (!intent.configHash().equals(result.writtenHash())
                        || !intent.configHash().equals(observed)) {
                    last = observed == null
                            ? new ErrorClass("materialize.readback.unavailable")
                            : new ErrorClass("materialize.hash.mismatch");
                    attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, observed, last,
                            result.runtimeRef());
                    continue;
                }
                attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, observed, null,
                        result.runtimeRef());
                return null;
            } catch (RuntimeAdapterException e) {
                last = e.errorClass();
                // Record what is ACTUALLY on this replica's disk after the failed try. This row is
                // the per-replica evidence §3.2's CONFIG_DIVERGED row points at, and observed_hash
                // is "read-back EVIDENCE, never intent" -- a failed attempt has evidence too.
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, readBack(replica),
                        last, null);
            }
        }
    }

    /** Best-effort read-back for evidence. A status failure here must not mask the real classifier. */
    private String readBack(TargetReplica replica) {
        try {
            return adapter.status(replica).observedHash();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code BE-D22}'s exhaustion journal: assess (hashless, read-only), then — only if a restore
     * target exists — a {@code ROLLBACK} intent carrying that target's hash, opened <b>before</b>
     * the destructive call, then one {@code VERIFY} per replica.
     */
    private ApplyOutcome exhausted(TenantId t, ApplyId id, PlacementRef placement,
            List<TargetReplica> replicas, long generation, String intendedHash, ErrorClass why) {
        StepIntentRow assessIntent = intents.ensureIntent(t, id, Step.ROLLBACK_ASSESS,
                ReplicaRef.PLACEMENT,
                idempotencyKey(id, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT), null);
        AttemptRow assessAttempt = attempts.open(t, id, assessIntent, null, props.stepBudget());

        RollbackAssessment assessment;
        try {
            assessment = adapter.assessRollback(placement, generation, intendedHash);
        } catch (RuntimeAdapterException e) {
            attempts.close(t, assessAttempt.attemptId(), AttemptOutcome.FAILED, null,
                    e.errorClass(), null);
            return land(t, id, RequestState.APPLY_INDETERMINATE, e.errorClass());
        }
        attempts.close(t, assessAttempt.attemptId(), AttemptOutcome.SUCCEEDED, null, null, null);

        if (assessment instanceof RollbackAssessment.NothingToRestore) {
            return land(t, id, RequestState.FAILED_NO_EFFECT, why);
        }
        if (assessment instanceof RollbackAssessment.Unknowable unknowable) {
            // Includes rollback.target.absent: the engine knows what to restore to (nothing) and
            // cannot journal a ROLLBACK for it, and rule 0 forbids acting without the entry.
            return land(t, id, RequestState.APPLY_INDETERMINATE, unknowable.why());
        }
        String targetHash = ((RollbackAssessment.Restorable) assessment).targetHash();

        // The ROLLBACK intent carries the target hash and is COMMITTED before restore() is called.
        // That single ordering is what makes a crash in the window decidable rather than a guess.
        StepIntentRow rollbackIntent = intents.ensureIntent(t, id, Step.ROLLBACK,
                ReplicaRef.PLACEMENT, idempotencyKey(id, Step.ROLLBACK, ReplicaRef.PLACEMENT),
                targetHash);
        AttemptRow rollbackAttempt = attempts.open(t, id, rollbackIntent, null, props.stepBudget());
        try {
            RestoreResult result = adapter.restore(placement, generation, targetHash);
            attempts.close(t, rollbackAttempt.attemptId(), AttemptOutcome.SUCCEEDED, null, null,
                    result.runtimeRef());
        } catch (RuntimeAdapterException e) {
            attempts.close(t, rollbackAttempt.attemptId(), AttemptOutcome.FAILED, null,
                    e.errorClass(), null);
            // Deliberately NOT a terminal decision here: the restore may or may not have landed,
            // and VERIFY below is what finds out. Landing on the exception would be the guess.
        }

        return verifyRestore(t, id, replicas, targetHash, intendedHash, why);
    }

    /**
     * One {@code VERIFY} step per replica against the restore target. This is where the per-replica
     * restored evidence becomes <b>durable</b>: {@code VERIFY} is replica-scoped and hash-bearing,
     * so each read-back lands in its own {@code apply_attempt.observed_hash}.
     */
    private ApplyOutcome verifyRestore(TenantId t, ApplyId id, List<TargetReplica> replicas,
            String targetHash, String intendedHash, ErrorClass why) {
        boolean allAtTarget = true;
        boolean anyUnknown = false;

        for (TargetReplica replica : replicas) {
            StepIntentRow intent = intents.ensureIntent(t, id, Step.VERIFY, replica.ref(),
                    idempotencyKey(id, Step.VERIFY, replica.ref()), targetHash);
            AttemptRow attempt = attempts.open(t, id, intent, null, props.stepBudget());
            String observed;
            try {
                observed = adapter.status(replica).observedHash();
            } catch (RuntimeAdapterException e) {
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, e.errorClass(),
                        null);
                anyUnknown = true;
                continue;
            }
            attempts.close(t, attempt.attemptId(),
                    observed == null ? AttemptOutcome.FAILED : AttemptOutcome.SUCCEEDED, observed,
                    observed == null ? new ErrorClass("verify.no.readback") : null, null);
            // The same two journal-known values the assessment used. A third one after a restore
            // means somebody wrote while we were working: report, never overwrite (BE-D22, B/D12).
            anyUnknown |= observed == null
                    || !(targetHash.equals(observed) || intendedHash.equals(observed));
            allAtTarget &= targetHash.equals(observed);
        }

        if (anyUnknown) {
            return land(t, id, RequestState.APPLY_INDETERMINATE,
                    new ErrorClass("rollback.state.unknown"));
        }
        // Every replica holds a value this apply wrote, but not all hold the target: the restore
        // landed only partially, so the config is genuinely not uniform. That IS CONFIG_DIVERGED --
        // unlike the PRE-restore mixture, which is the ordinary case a rollback exists for.
        return allAtTarget
                ? land(t, id, RequestState.FAILED_ROLLED_BACK, why)
                : land(t, id, RequestState.CONFIG_DIVERGED, why);
    }

    private StepOutcome<Plan> closeFailed(TenantId t, AttemptRow attempt, ErrorClass why) {
        attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, why, null);
        return StepOutcome.failed(why);
    }

    /**
     * {@code OutcomeService.land} accepts one non-terminal target — {@code APPLY_INDETERMINATE} —
     * so this one method covers both landings. The record is named {@code Landed} for the common
     * case; its javadoc states the exception rather than a second method hiding it.
     */
    private ApplyOutcome land(TenantId t, ApplyId id, RequestState state, ErrorClass why) {
        outcomes.land(t, id, state);
        return new ApplyOutcome.Landed(id, state, why);
    }

    /**
     * Deterministic, so resume recomputes the same key rather than minting a second one for a step
     * that already has an intent. The apply id is a UUID, which is what makes the column's GLOBAL
     * {@code UNIQUE} satisfiable without a registry. Package-visible to tests because
     * {@code InstanceWideIdentityLimitIT} pins the identity limit this key lives inside.
     */
    public static String idempotencyKey(ApplyId id, Step step, ReplicaRef replica) {
        return id.value() + ":" + step + ":" + replica.value();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl dp-core -am verify -Dit.test='ManualApplyIT,InstanceWideIdentityLimitIT'`
Expected: failsafe `ManualApplyIT` **14/0**, `InstanceWideIdentityLimitIT` **3/0**.

- [ ] **Step 5: Prove the guards fire**

| Mutation | Expected red |
|---|---|
| `execute` drops the strategy check | `aNonManualStrategyIsRefusedBeforeAdmissionAndPersistsNothing` — 1 red; the run then reaches `land(STAGED)` on a rolling request, which `OutcomeService.land` refuses in Java by name (`staged_requires_manual`) **before** the database sees it. Record both layers |
| `execute` drops the instance-wide check | `anInstanceWideRequestIsRefusedBeforeAdmissionAndPersistsNothing` — 1 red. Then observe what actually happens with the check removed and **record it**: `PlacementRef`'s own constructor refuses the sentinel, so the failure is a thrown `IllegalArgumentException` mid-run with the request left `RUNNING` and scope-blocking. Two independent layers, and the second is not a graceful one — which is why the first exists |
| `preflight` calls `adapter.prepare` **before** `configs.render` | `aFailedRenderLandsFailedNoEffectWithNoMaterializeIntentAndNoAdapterCall` — 1 red on `prepareCalls()` |
| the `catch (RuntimeException)` around `configs.render` is removed | `aFailedRenderLandsFailedNoEffectWithNoMaterializeIntentAndNoAdapterCall`, `aFailedRenderLeavesTheScopeAdmissibleForTheNextApply` and `aFailedRenderPutsNoRendererTextIntoPersistenceOrTheOutcome` — **3 red**, and the request is left `RUNNING` and scope-blocking, the defect this routing exists to close |
| that `catch` is kept but classifies as `new ErrorClass(e.getMessage())` | the construction throws (`ErrorClass` refuses any non-token) — record it: the second line of defence is real, not narration |
| **`exhausted` opens `ROLLBACK_ASSESS` and then calls `restore` under it** (the pre-repair shape) | `aMaterializeFailureAfterAPartialWriteJournalsAssessThenRollbackThenVerify` — 1 red on the missing `ROLLBACK` intent. **This is the mutation that proves a restorative path cannot be journalled as an assessment**, and it is paired with the contract kit's own non-mutation proof in Task 2 |
| `exhausted` opens the `ROLLBACK` intent **after** `adapter.restore` returns | `aMaterializeFailureAfterAPartialWriteJournalsAssessThenRollbackThenVerify` stays green — so **add** the assertion that the `ROLLBACK` intent's `created_at` precedes its attempt's `started_at` and that the attempt precedes the restore. Rule 0 is an ordering claim and only an ordering assertion can falsify it |
| `exhausted` maps `Unknowable` to `FAILED_NO_EFFECT` | `anAssessmentThatCannotDecideLeavesTheRequestNonTerminalAndBlockingTheScope` and `aFirstApplyThatFailsPartwayIsIndeterminateBecauseAbsenceHasNoHash` — 2 red |
| `verifyRestore` lands `FAILED_ROLLED_BACK` without comparing to `targetHash` | a partial-restore fixture (one replica restored, one not) — 1 red. **Write this fixture**: without it the comparison is untested and `FAILED_ROLLED_BACK` is asserted by the code, not measured |
| `materialize` closes a failed attempt with `null` instead of `readBack(replica)` | `aFailedMaterializeAttemptCarriesTheHashStatusReadBack` — 1 red |
| `materialize` trusts `MaterializeResult.writtenHash()` without a successful `status()` read-back | `aMaterializeThatLiesAboutTheWrittenHashIsNeverASuccess` — 1 red; the fault writes corrupt bytes while falsely returning the intent hash, and the request must never land `STAGED` |
| `materialize`'s failure returns `null` (treated as success) | `aMaterializeFailureAfterAPartialWriteJournalsAssessThenRollbackThenVerify` — 1 red: the request lands `STAGED` with bytes missing on one replica |
| `materialize` gives up after the first failure instead of looping | `everyAllowedAttemptIsSpentBeforeTheExhaustionPathIsTaken` — 1 red |
| `step` invokes the adapter before `attempts.open` | `theAttemptRowIsCommittedBeforeTheAdapterIsCalled` — 1 red |
| `idempotencyKey` includes `System.nanoTime()` | Task 10's `ResumeIT` goes red with `IdempotencyKeyReused`; noted here so the coupling is visible, and **not** counted as a Task 8 proof |

- [x] **Step 6: Resolve plan 004's `ensureIntent` scope-lock watch item** — RESOLVED 2026-09-14

Plan 004 recorded: *"`ensureIntent` takes no scope-row lock, so a concurrent `OutcomeService.land` could commit a terminal state between the read and the insert. No production path calls it concurrently with `land` today (no step loop exists), so this is not a defect in this plan. → Plan 005's step loop — decide whether `ensureIntent` needs the scope lock."*

**The step loop now exists, so decide it with a measurement, not a preference.**

1. Write `EnsureIntentRaceIT`: thread A in `ensureIntent`, thread B landing `SUPERSEDED` on the same request, with a latch between A's read and A's insert.
2. Observe what the database does. The FK `(tenant_id, apply_id) → apply_request` does not fence on state, so the expected observation is that the intent **lands on a terminal request** — obligation (q)'s guarantee holding only sequentially.
3. **The decision this plan takes, unless the measurement contradicts it:** do **not** add a lock to `ensureIntent`. The executor instead re-reads the request state before entering the exhaustion journal, and a terminal request aborts it — because the alternative widens plan 004's admission lock to cover every step of every apply, serializing unrelated instances' work behind one scope row.
4. If the measurement shows something else — in particular if the intent is *refused* — then obligation (q) is stronger than plan 004 recorded and **that is a finding to report**, not a silent upgrade.

**Measured, 2026-09-14** (`EnsureIntentRaceIT`, 3 tests, green). The window was held open without
hooking production code: the intent repository was built over a `NamedParameterJdbcTemplate` that
pauses the one `INSERT INTO sm_data.apply_step_intent`, which is the second half of the
check-then-act pair, over the **same** `DataSource` instance the transaction manager uses.

- **The intent lands on a terminal request.** Thread A entered the window, the main thread landed
  `SUPERSEDED` under the scope lock and committed, A's insert then succeeded: one row, against a
  `SUPERSEDED` request. The FK does not fence on state, so obligation (q) is engine-enforced and
  **sequential-only** — exactly what plan 004 recorded, now measured rather than expected. The
  intent is *not* refused, so obligation (q) is not stronger than recorded and there is no finding.
- **The sequential case still refuses**, so the measurement reads as "the window is real" rather
  than "obligation (q) does not work": `ensureIntent` after `land` throws `TerminalRequest` and
  writes nothing.
- **The decision stands: no lock is added to `ensureIntent`.** The executor re-reads
  `OutcomeService.stateOf` before entering `BE-D22`'s exhaustion journal — the longest stretch of
  writes the loop can enter after a failure and the only destructive one — and a request that has
  gone terminal underneath it is reported as it stands, with no `ROLLBACK_ASSESS` intent, no
  `ROLLBACK` intent and no adapter assessment. Rolling back a scope somebody else now owns is the
  second outage `B/D12` 🔒 exists to refuse.
- **Prove-it-fires:** delete the re-read → **1 red**,
  `StepIntentService$TerminalRequest: apply_request … is terminal (SUPERSEDED); obligation (q)
  forbids new intents on it`, thrown out of `ApplyExecutor.exhausted`.

- [ ] **Step 7: Commit**

```bash
git commit -m "$(cat <<'MSG'
Land ApplyExecutor: the single-placement manual slice, end to end (Task 8)

A vertical slice, not a prefix: admission, pre-flight, rendering, stage-
everywhere, bounded retry and the whole BE-D22 exhaustion journal -- assess,
then a hash-bearing ROLLBACK opened BEFORE the destructive call, then one VERIFY
per replica. Nothing here references Task 9's HealthGate or Task 10's
ResumePlanner.

Two refusals before admission, neither a stub. A non-manual strategy (SM-D15)
and an instance-wide scope (BE-D23) are refused with nothing persisted: no
request row, no intent, no attempt, no adapter call. The instance-wide refusal
is measured, not assumed -- InstanceWideIdentityLimitIT pins both halves of the
identity limit against the shipped DDL, and both tests are expected to go red
when a persistence plan grows apply_step_intent a placement.

Rendering moved inside the PREFLIGHT step and before the adapter call, which is
what makes FAILED_NO_EFFECT a measurement rather than an assumption on a render
failure. The classifier lands in apply_attempt.error_class; the renderer's own
message is never read.

Per-replica evidence is rows, never a return value: a failed MATERIALIZE closes
with the hash status reads back, and every restore is followed by one VERIFY per
replica.

Plan 004's ensureIntent scope-lock watch item is RESOLVED with a measured race
rather than a preference: <outcome>.

Prove-it-fires: strategy check dropped -> 1 red plus a named staged_requires_manual
refusal; instance-wide check dropped -> 1 red plus a thrown IllegalArgumentException
leaving the request RUNNING; prepare before render -> 1 red; render catch removed
-> 3 red; restore under ROLLBACK_ASSESS -> 1 red; Unknowable mapped to
FAILED_NO_EFFECT -> 2 red; VERIFY not compared to targetHash -> 1 red against a
new partial-restore fixture; failed materialize closed without its read-back ->
1 red; materialize failure swallowed -> 1 red; retry loop removed -> 1 red;
adapter before attempts.open -> 1 red.
MSG
)"
```

**Risks:** `ApplyExecutor` grows again in Tasks 9 and 10. If it passes ~350 lines by Task 10, split the exhaustion journal into its own type **then** — a split before the rolling path exists would be guessing at the seam.

---

### Task 9: The health gate and the rolling strategies

**Depends on Task 8** (extends `ApplyExecutor` and widens its supported-strategy set) and on Task 1's seam types. Everything Task 9 needs from Task 8 already exists when Task 9 starts.

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/HealthGate.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/GateVerdict.java`
- Modify: `.../engine/ApplyExecutor.java` (the supported-strategy set, `restartAndGate`, one constructor parameter)
- Modify: `.../engine/ApplyEngineConfiguration.java` (one bean)
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/engine/HealthGateTest.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/RollingApplyIT.java`

**Interfaces:**
- Produces:
  ```java
  public sealed interface GateVerdict {
      record Passed(PreBaseline observed) implements GateVerdict {}
      record Failed(ErrorClass why) implements GateVerdict {}
      record Unavailable(ErrorClass why) implements GateVerdict {}   // no signal to gate on
  }
  public GateVerdict await(RuntimeAdapter adapter, TargetReplica replica, PreBaseline before);
  ```

- [x] **Step 1: Write the failing tests**

`HealthGateTest` (unit, over `InMemoryRuntimeAdapter` and a controllable clock), five tests:

```java
    @Test
    void theGatePassesWhenTheBaselineMovedAndHealthIsHealthy() {}

    @Test
    void theGateFailsWhenTheDeadlineExpiresWithHealthStillStarting() {
        adapter.fault(Fault.NEVER_HEALTHY);
        assertInstanceOf(GateVerdict.Failed.class, gate.await(adapter, replica, before));
    }

    /**
     * L7, as a refusal rather than as a comment. A runtime that publishes no health signal cannot be
     * health-gated: treating NOT_DECLARED as a pass would make rolling-health-gated indistinguishable
     * from rolling-no-validation while keeping its name -- the worse of the two failures, because the
     * operator chose the gated strategy precisely to get a gate.
     */
    @Test
    void aRuntimeWithNoHealthSignalMakesTheGateUnavailableRatherThanPassing() {
        InMemoryRuntimeAdapter noSignal = new InMemoryRuntimeAdapter(placement, false, "r1");
        assertInstanceOf(GateVerdict.Unavailable.class, gate.await(noSignal, replica, before));
    }

    @Test
    void theGateDoesNotPassOnHealthAloneWhenTheBaselineNeverMoved() {
        // A replica that was already healthy and was never restarted must not satisfy the gate:
        // restart-observed is a baseline comparison, and a gate that skipped it would certify a
        // restart that did not happen.
    }

    @Test
    void theGatePollsRatherThanSleepingTheWholeDeadline() {
        // assert the number of status() calls is > 1 and the elapsed virtual time < deadline
    }
```

`RollingApplyIT`, seven tests:

| Test | Assertion |
|---|---|
| `aRollingHealthGatedApplyRestartsReplicasOneAtATime` | replica 2's baseline is unchanged until replica 1's gate passes; both `LIFECYCLE` intents exist; terminal `APPLIED` |
| `everyMaterializeIntentPredatesEveryLifecycleIntent` | by `created_at` — `SM-D15`'s stage-everywhere ordering, and the first test in this plan that can falsify it, because Task 8 has no lifecycle step to interleave with |
| `aNonManualApplyIsNeverObservedStaged` | `BE-D16` from the engine's side: sample the request state at every transition and assert `STAGED` never appears; terminal `APPLIED`. **Moved here from Task 8**, where no non-manual strategy reaches the executor at all |
| `rollingHealthGatedIsRefusedWhenTheRuntimePublishesNoHealthSignal` | the apply lands `FAILED_NO_EFFECT` **at pre-flight**, before any write: `PreflightResult.Ready.healthSignalDeclared` is false and the strategy requires it. Not `StrategyUnsupported` — that variant is for what is known before admission, and this is an observation |
| `aGateFailureBetweenReplicasTerminatesConfigDiverged` | replica 1 restarted and gated healthy, replica 2's gate fails → `CONFIG_DIVERGED` with per-replica evidence, **not** `FAILED_ROLLED_BACK`: `SM-D15` names divergence explicitly, and rolling back a replica already serving the new generation is a second outage |
| `restartAllRequiresAnOutageConfirmation` | enforced by `ApplyCommand`'s `full_outage_confirmed`; asserted here so the executor's path to it is covered |
| `aRollingNoValidationApplyRestartsWithoutGating` | no `status` poll between replicas; terminal `APPLIED` |

- [x] **Steps 2–4: red → implement → green**

```java
    /** The gate. A policy over observations, under a bounded deadline — never an adapter action. */
    public GateVerdict await(RuntimeAdapter adapter, TargetReplica replica, PreBaseline before) {
        Instant deadline = clock.instant().plus(props.healthGateDeadline());
        ErrorClass last = new ErrorClass("gate.no.observation");
        while (clock.instant().isBefore(deadline)) {
            RuntimeStatus now = adapter.status(replica);

            if (now.health() == HealthSignal.NOT_DECLARED) {
                // Not a failure and not a pass. The strategy the operator chose cannot be executed
                // against this runtime, and saying so is the only honest answer (L7).
                return new GateVerdict.Unavailable(new ErrorClass("gate.signal.absent"));
            }
            Optional<PreBaseline> observed = now.startBaseline();
            boolean restarted = observed.isPresent()
                    && !observed.get().startedAt().equals(before.startedAt());

            if (restarted && now.health() == HealthSignal.HEALTHY) {
                return new GateVerdict.Passed(observed.get());
            }
            last = new ErrorClass(restarted ? "gate.unhealthy" : "gate.no.restart");
            sleeper.sleep(props.healthPollInterval());
        }
        return new GateVerdict.Failed(last);
    }
```

In `ApplyExecutor`: widen the strategy check to a supported set (`MANUAL`, `ROLLING_HEALTH_GATED`, `ROLLING_NO_VALIDATION`, `RESTART_ALL`; `SCHEDULED` stays `StrategyUnsupported` for the whole of plan 005), and add `restartAndGate` after Phase 2 for the non-manual strategies. One placement (`BE-D23`), per replica in order: capture `status` as the `pre_baseline`, open the `LIFECYCLE` attempt **carrying that baseline** — the one step where `baseline_only_for_lifecycle` permits it, and the one step that needs it — call `startOrUpdate`, close, then gate. A `Unavailable` verdict is unreachable by then because pre-flight already refused the strategy; assert that with an `IllegalStateException` rather than a silent branch, so a future pre-flight change that drops the check fails loudly.

- [x] **Step 5: Prove the guards fire**

| Mutation | Expected red |
|---|---|
| `await` treats `NOT_DECLARED` as `HEALTHY` | `aRuntimeWithNoHealthSignalMakesTheGateUnavailableRatherThanPassing` and `rollingHealthGatedIsRefusedWhenTheRuntimePublishesNoHealthSignal` — 2 red |
| `await` drops the `restarted` conjunct | `theGateDoesNotPassOnHealthAloneWhenTheBaselineNeverMoved` — 1 red |
| `restartAndGate` restarts all replicas then gates once | `aRollingHealthGatedApplyRestartsReplicasOneAtATime` — 1 red |
| a gate failure lands `FAILED_ROLLED_BACK` | `aGateFailureBetweenReplicasTerminatesConfigDiverged` — 1 red |
| `runManual`'s Phase-3 `land(STAGED)` is reached for a rolling strategy | `aNonManualApplyIsNeverObservedStaged` — 1 red in Java (`OutcomeService.land`'s `staged_requires_manual` guard) **before** the database sees it; record both layers |
| the lifecycle loop is moved above the materialize loop | `everyMaterializeIntentPredatesEveryLifecycleIntent` — 1 red |

**Measured, 2026-09-14.** `HealthGateTest` 5/5, `RollingApplyIT` 8/8 (one more than planned — see
the deviations below). Every mutation fired; two differ from the prediction and are recorded as
measured rather than as planned:

| Mutation | Predicted | **Measured** |
|---|---|---|
| `await` treats `NOT_DECLARED` as any other signal | 2 red | **1 red** — `aRuntimeWithNoHealthSignalMakesTheGateUnavailableRatherThanPassing`: `expected: <GateVerdict.Unavailable> but was: <GateVerdict.Failed>`. The prediction was wrong about *where* the second red lives: `rollingHealthGatedIsRefusedWhenTheRuntimePublishesNoHealthSignal` is a **pre-flight** refusal and `await` is not on its path, so it needs its own mutation — the next row |
| the pre-flight health-signal refusal is dropped | *(not predicted)* | **1 red** — and it fires as the deliberately loud `IllegalStateException`: *"a health-gated apply reached the gate against a runtime with no health signal; pre-flight must have refused it"*. That assertion is therefore not decoration |
| `await` drops the `restarted` conjunct | 1 red | **1 red** — `theGateDoesNotPassOnHealthAloneWhenTheBaselineNeverMoved`: `expected: <Failed> but was: <Passed>` |
| every replica is restarted first, then gated | 1 red | **4 red** — `aRollingHealthGatedApplyRestartsReplicasOneAtATime` on the call order (the one that names the property), plus three replica-count assertions tripped because the mutation restarts every replica twice: `aRollingNoValidationApplyRestartsWithoutGating`, `restartAllRequiresAnOutageConfirmation` and `rollingNoValidationIsNotRefusedByAnAbsentHealthSignal`. Collateral, named so a reader can tell it from evidence |
| a gate failure lands `FAILED_ROLLED_BACK` | 1 red | **1 red** — `expected: <CONFIG_DIVERGED> but was: <FAILED_ROLLED_BACK>` |
| Phase 3 lands `STAGED` for every strategy | 1 red | **7 red**, every one of them `IllegalState staged_requires_manual: a rolling-health-gated apply may not land on STAGED … the CHECK would refuse this with 23514` — the Java guard, by name, **before** the database sees it. The database layer is plan 004's `SchemaConstraintsIT`, unchanged here |
| the `LIFECYCLE` intents are minted before the materialize loop | 1 red | **1 red** — `Last MATERIALIZE 2026-09-14T12:31:07.908179Z, first LIFECYCLE 2026-09-14T12:31:07.863164Z` |

**Deviations from the task as written, and why:**

1. **A fifth `ApplyOutcome` variant, `OperationUnsupported`.** Widening the supported-strategy set
   opened a hole Task 8 did not have: a `restart` request carries a strategy but no generation, so
   it would have passed the strategy check and then dereferenced a null generation. A `restart`
   **request** is a different slice — no `MATERIALIZE` step, a lifecycle journal with no hash to
   bind — and plan 005 does not open it. Refused before admission, on the operation. Not folded
   into `StrategyUnsupported`: the strategy may be perfectly supported and the operation still out
   of scope, and a refusal naming the wrong reason sends an operator to change the wrong thing.
2. **`ManualApplyIT`'s strategy-refusal test moved to `SCHEDULED`.** `ROLLING_HEALTH_GATED` is
   supported now, so a refusal test aimed at it would pass for the wrong reason. `SCHEDULED` is the
   one member of the enum plan 005 never opens.
3. **An eighth `RollingApplyIT` test, `rollingNoValidationIsNotRefusedByAnAbsentHealthSignal`.** The
   planned set proved the gated strategy is refused without a signal but never that the ungated one
   is not, which is the half that makes the refusal a property of the *gate* rather than of the
   runtime.
4. **`restart-all` issues the same call sequence as `rolling-no-validation` in this build, and that
   is recorded rather than disguised.** The engine is single-threaded over a synchronous seam, so
   "restart everything at once" and "restart each in turn without gating" are the same sequence of
   `startOrUpdate` calls. What genuinely differs is the authorization: `full_outage_confirmed` is
   enforced by `ApplyCommand` and by the CHECK of the same name, and `restartAllRequiresAnOutageConfirmation`
   pins it. A parallel restart-all would be a real difference and is **not** built.

- [x] **Step 6: Commit**, recording that the `NOT_DECLARED` refusal is a **strategy-availability** decision — `SM-D15`'s *"SM offers only strategies the solution's declared capabilities support"*, enforced at pre-flight rather than discovered at the gate — and that it is deliberately a different answer from Task 8's `StrategyUnsupported`, because one is known before anything is persisted and the other is an observation.

**Risks:** `sleeper` is an injected seam so the unit tests do not sleep for real. That is a testability affordance in production code; it is one interface with one production implementation, and the alternative would make the suite's runtime the deadline.

---

### Task 10: Resume, and obligation (r)

**Depends on Task 9.** It adds no new failure mapping — Task 8's `exhausted` is reused unchanged — so this task is resume plus the stop interaction, and nothing in Tasks 8 or 9 references anything it creates.

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/ResumePlanner.java`
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/StepDecision.java`
- Modify: `.../engine/ApplyExecutor.java` (`resume`, and the admitted-stop check inside the lifecycle loop)
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/engine/ResumePlannerTest.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ResumeIT.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/StopDuringApplyIT.java`

**Interfaces:**
- Produces:
  ```java
  public enum StepDecision { RERUN, CONTINUE, REISSUE, INDETERMINATE }
  public StepDecision decide(StepIntentRow intent, Optional<AttemptRow> open, RuntimeStatus observed);
  public List<ApplyOutcome> resume(TenantId t);
  ```

**Why `ResumePlanner` is a pure function:** §2.2 rule 3 is a decision table, and a decision table that can only be exercised through a database and a daemon is one nobody will exercise exhaustively. `decide` takes rows and an observation and returns a verdict; `ResumePlannerTest` walks every cell. `ResumeIT` then proves the wiring on two of them, which keeps the coverage honest without making it slow.

- [x] **Step 1: Write the failing tests — the full decision table**

| Intent | Open attempt | Observation | Expected | §2.2 rule 3's words |
|---|---|---|---|---|
| any | none | any | `RERUN` | *"an intent with no attempt row provably issued nothing: re-run deterministically — never indeterminate"* |
| `MATERIALIZE` | open | nothing at the path | `RERUN` | *"nothing at the path → rerun"* |
| `MATERIALIZE` | open | hash == intent's | `CONTINUE` | *"hash matches → continue"* |
| `MATERIALIZE` | open | hash ≠ intent's | `INDETERMINATE` | *"bytes present with an unexpected hash → a competing writer or partial write — never silently overwritten (`B/D12` 🔒)"* |
| `LIFECYCLE` | open | baseline moved | `CONTINUE` | *"restart observed → continue"* |
| `LIFECYCLE` | open | baseline unchanged | `REISSUE` | *"baseline unchanged → re-issue under the same key"* |
| `LIFECYCLE` | open | baseline absent | `INDETERMINATE` | *"baseline missing or unreadable → `APPLY_INDETERMINATE`, because re-issuing a restart the operator may not have authorized twice is exactly the guess this state exists to refuse"* |
| `PREFLIGHT` | open | any | `RERUN` | pre-flight never mutates, so re-running it is free and always safe |
| `STOP` | open | `Liveness.STOPPED` | `CONTINUE` | the desired state is reached |
| `STOP` | open | `Liveness.RUNNING` | `REISSUE` | idempotent under the same key |
| `ROLLBACK_ASSESS` | open | any | `RERUN` | `BE-D22` makes the assessment read-only, so re-running it is free and always safe — the same reason `PREFLIGHT` re-runs |
| `ROLLBACK` | open | every replica reads the intent's `config_hash` | `CONTINUE` | the restore landed. **The target is on the intent row**, written before the destructive call, so this is a comparison and not a guess |
| `ROLLBACK` | open | every replica reads the target **or** its own `MATERIALIZE` hash | `REISSUE` | both values are ones this apply wrote, so re-issuing is safe; restoring identical bytes twice is the same write (§2.2 rule 2) |
| `ROLLBACK` | open | any replica reads a **third** value, or is unreadable | `INDETERMINATE` | somebody else wrote while the DP was down. `B/D12` 🔒 — *never silently auto-revert a human*. The engine reports and stops; it does not overwrite |
| `VERIFY` | open | any | `RERUN` | read-only |

**Why the `ROLLBACK` rows exist at all, and what they would have been without `BE-D22`.** Before the
split there was one conflated operation journalled as `ROLLBACK_ASSESS` with a null `config_hash`.
A crash after that attempt opened would leave resume holding an intent that names no target: it
could not tell whether the adapter had restored, what it had restored to, or whether re-running was
safe — the exact guess `APPLY_INDETERMINATE` exists to refuse, arrived at by accident rather than by
measurement. **The `ROLLBACK` intent's `config_hash` is the single fact that makes these three rows
decidable.**

Fourteen cells, fourteen tests, plus one that asserts the table is **total**: every `(Step × attempt-presence × observation-class)` combination the enums admit returns a decision rather than falling through a default. A `switch` with a permissive default is how a decision table stops being one.

`ResumeIT` proves two cells end to end, chosen because they are the two that cannot be faked:

- `anIntentWithNoAttemptRowIsRerunAndProducesTheSameIdempotencyKey` — kill the executor between `ensureIntent` and `attempts.open` (an injected fault), resume, and assert the *same* intent row is reused rather than a second one minted. A non-deterministic key surfaces here as `IdempotencyKeyReused`.
- `anUnexpectedHashOnResumeIsIndeterminateAndNothingIsOverwritten` — `adapter.competingWrite(...)`, resume, assert `APPLY_INDETERMINATE` **and** that the file still holds the competing bytes. `B/D12` 🔒 is *never silently auto-revert a human*; asserting the state without asserting the file would let a revert pass.
- `aCrashAfterTheRollbackIntentPlusACompetingWriteIsIndeterminate` — **correction B's resume proof.** Crash after the `ROLLBACK` intent and its attempt are committed, then plant a third, unknown hash on one replica before resuming. Resume must reach `APPLY_INDETERMINATE` and leave the competing bytes on disk: re-issuing the restore would erase a write this apply did not make, which `B/D12` 🔒 forbids. Assert both the state **and** the file.
- `aCrashBetweenTheRollbackIntentAndTheRestoreIsResolvedByTheIntentsOwnHash` — **the `BE-D22` proof.** Kill the executor after the `ROLLBACK` intent and its attempt are committed but before `restore` returns. Resume must read `targetHash` off the intent row, compare each replica, and reach a terminal state **without calling `assessRollback` again** — assert the assessment call count is unchanged. Before the split this cell did not exist: the intent carried a null hash and resume had nothing to compare against.

`StopDuringApplyIT` — **obligation (r)**, which plan 004 recorded as N/A for want of this seam:

```java
    /**
     * Obligation (r): a stop admitted while an apply is mid-flight.
     *
     * <p>The database admits it by design -- BE-D13's apply fence carries `operation <> 'stop'`, so
     * the emergency stop is admissible by the DATABASE and not merely by prose, and the stop family
     * carries its own one-in-flight fence. What that leaves to the engine is what this test pins.
     */
    @Test
    void aStopAdmittedMidApplyHaltsTheApplyAndBothRequestsReachTheirOwnTerminalState() {
        // apply at generation 9, rolling-health-gated, paused between replica 1 and replica 2
        // stop admitted on the same scope -> Admitted (the fence permits it)
        // the apply halts and lands CONFIG_DIVERGED with per-replica evidence
        // the stop lands STOPPED
        // NO replica is restarted after the stop was admitted
    }

    @Test
    void theStopIsAdmittedByTheDatabaseAndNotMerelyByTheEngine() {
        // the negative control: with the apply in flight, a second APPLY is refused SCOPE_BUSY while
        // the STOP is admitted -- the asymmetry obligation (r) is about, and it is the database's,
        // so assert it with the engine's admission bypassed.
    }
```

- [x] **Steps 2–4: red → implement → green.**

- [x] **Step 5: Prove the guards fire**

| Mutation | Expected red |
|---|---|
| `decide` returns `REISSUE` for a `LIFECYCLE` with an absent baseline | 1 red in `ResumePlannerTest`, and the single most important one in this plan: it is the difference between "we do not know" and "restart it again" |
| `decide` returns `RERUN` for a `MATERIALIZE` with an unexpected hash | `anUnexpectedHashOnResumeIsIndeterminateAndNothingIsOverwritten` — 1 red, with the competing bytes gone from the file |
| the `switch` in `decide` gains a permissive `default -> CONTINUE` | the totality test — 1 red |
| the executor ignores an admitted stop and finishes the rolling apply | `aStopAdmittedMidApplyHaltsTheApplyAndBothRequestsReachTheirOwnTerminalState` — 1 red |
| `decide` returns `RERUN` for a `ROLLBACK` with an open attempt (re-running the assessment from scratch) | `aCrashBetweenTheRollbackIntentAndTheRestoreIsResolvedByTheIntentsOwnHash` — 1 red on the assessment call count. Re-assessing after a partial restore would read the *restored* file as the current state and conclude `NothingToRestore`, turning a completed rollback into `FAILED_NO_EFFECT` |
| `decide` ignores the `ROLLBACK` intent's `config_hash` and compares against the apply's own generation hash | 1 red in `ResumePlannerTest` — it would read a successful restore as an unfinished one and re-issue forever |
| `ApplyRequestRow`'s `terminal_has_timestamp` is tested directly by attempting `land(APPLY_INDETERMINATE)` with a stamp | the model type refuses it before the database does — record that layering; it is plan 004's, inherited here |

**Measured, 2026-09-14.** `ResumePlannerTest` 20/20, `ResumeIT` 5/5, `StopDuringApplyIT` 2/2,
`ApplyRequestRowTest` 3/3. Every mutation fired:

| Mutation | **Measured red** |
|---|---|
| an absent `LIFECYCLE` baseline returns `REISSUE` | **1** — `aLifecycleWithAnAbsentBaselineIsIndeterminateAndNeverReissued`: `expected: <INDETERMINATE> but was: <REISSUE>`. The most consequential cell in the table, and it is guarded |
| an unexpected `MATERIALIZE` hash returns `RERUN` | **1 unit** (`expected: <INDETERMINATE> but was: <RERUN>`) and **1 integration** — `anUnexpectedHashOnResumeIsIndeterminateAndNothingIsOverwritten`: `expected: <APPLY_INDETERMINATE> but was: <STAGED>`. The request *completed*, which means resume overwrote the competing bytes: the exact harm `B/D12` 🔒 names |
| the `MATERIALIZE` branch deleted in favour of `default -> CONTINUE` | **3**, one of them the totality test, which prints the whole row: `MATERIALIZE=CONTINUE` where `MATERIALIZE=RERUN` was expected |
| the executor ignores an admitted stop | **2** in `StopDuringApplyIT`: `expected: <CONFIG_DIVERGED> but was: <APPLIED>` — it restarted every remaining replica after the stop was admitted |
| a `ROLLBACK` with an open attempt returns `RERUN` | **2** in `ResumeIT`, as the deliberately loud `IllegalState: a ROLLBACK frontier may not be re-run from the top`. See the deviation below: the *state* rather than the assessment count is what moves, because `finishRollback` never re-assesses by construction |
| `rollback` compares against the apply's generation hash instead of the intent's | **2** — a successful restore reads as `INDETERMINATE` and a partial one too. Without `BE-D22`'s hash-bearing intent there is nothing else to compare against |
| `ApplyRequestRow` with `APPLY_INDETERMINATE` + a terminal stamp | refused by the **model**, naming `terminal_has_timestamp`, before the database is asked (`ApplyRequestRowTest`). The CHECK itself is plan 004's `SchemaConstraintsIT`; both layers, neither redundant |

**Deviations from the task as written, and why:**

1. **`decide` takes `List<RuntimeStatus>` plus the intended hash, not one `RuntimeStatus`.** The
   plan's own `ROLLBACK` rows speak of "every replica" and of "its own `MATERIALIZE` hash", and the
   sketched signature can express neither: `ROLLBACK` is placement-scoped, and the intended hash is
   a row of this apply rather than anything on the `ROLLBACK` intent. An empty list is a legal
   input meaning "nothing could be observed" — `INDETERMINATE` for the four acting steps, and
   nothing at all for the three read-only ones, which is what lets a crash inside the very first
   `PREFLIGHT` (no `MATERIALIZE` intent yet, so no replica to name) still answer `RERUN`.
2. **The `RERUN`-on-`ROLLBACK` mutation shows up as an exception, not as an assessment count.**
   `finishRollback` is a separate path that never calls `assessRollback`, so a second assessment is
   structurally impossible rather than merely untaken — and a `RERUN` frontier on `ROLLBACK` throws
   with the reason named. The plan's predicted assertion (an unchanged assessment count) is
   asserted anyway, and holds: **1 before resume, 1 after**.
3. **Two extra cells beyond the fourteen:** `ROLLBACK` with an unreadable replica, and a `ROLLBACK`
   intent carrying no target hash. The second is unreachable through the database
   (`hash_on_config_steps`) and is answered because a decision table with a hole is not one — it is
   also the cell that would have existed everywhere before `BE-D22`.
4. **`resume` reuses `run`, and therefore re-runs pre-flight, spending one attempt of the
   `PREFLIGHT` intent.** Re-running pre-flight is free by §2.2, but the attempt cap is finite: with
   the default cap of 3 a request survives two resumes. **Watch item**, carried to Results.
5. **The `STOP` *step* is decidable but never written by this build, and obligation (r) is
   therefore NOT proven.** An earlier revision of this task polled `apply_request` for an admitted
   stop between replicas and halted `CONFIG_DIVERGED`, and claimed (r). An independent review
   measured the claim against §3.3's own words — *"the stop executes immediately at the adapter …
   and **the apply is not cancelled in place**; the running apply's next step **observes the
   stopped replica through `status`** and its health gate"* — and every clause was inverted:
   nothing executed at the adapter, the apply *was* cancelled in place, and it terminated on a
   database row rather than an observation. The poll was **removed** rather than kept as a local
   invention; §3.3's mechanism cannot be built here either, for the enumeration reason below.
   `StopDuringApplyIT` now proves the database half and **pins the gap**, so implementing (r) has
   to rewrite a test deliberately.

**A design contradiction found during Task 10, recorded rather than resolved.** `BE-D12`'s
`prepare(placement, generation)` is the seam's only replica enumerator and it is generation-bearing,
while `apply_request` admits a `stop` whose `generation` is NULL by `apply_has_generation`. **A stop
cannot enumerate its own replicas through the seam as designed.** Three shapes exist — a
generation-free observation operation, a stop that reads the placement's last applied generation, or
a stop addressed at the placement rather than per replica — and picking one is a `BE-D*` decision,
not an implementation detail. Plan 005 therefore does **not** drive stops through `ApplyExecutor`;
`Step.STOP`'s two rows in the decision table are total-table coverage, not a reachable production
path. Recorded in Results as a design question.

- [x] **Step 6: Commit** — and, after review, recording obligation **(r)** as **UNPROVEN** with the test that says exactly how far the build gets. The step as written asked for the opposite; the measurement did not support it.

**Risks:** `resume(TenantId)` has no scheduler and no caller in production — there is no application class yet, and adding one would be scope this plan does not have. It is a public method with tests, invoked by `ResumeIT`. Recorded as a watch item: **the resume path is implemented and proven, and nothing calls it on startup yet.** That is exactly the shape of claim `L19` is about, so it is stated as a limit rather than as a feature.

---

### Task 11: Three ArchUnit rules, three planted violations

**Files:**
- Modify: `backend/arch-tests/src/test/java/com/solace/sm/arch/PlaneBoundaryTest.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/dp/apply/engine/archfixture/BadEngineDockerClass.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/dp/apply/runtime/archfixture/BadRuntimeSeamEngineClass.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/dp/apply/persistence/archfixture/BadPersistenceEngineClass.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/dp/apply/runtime/docker/archfixture/LegalDockerClientClass.java`

- [x] **Step 1: Write the rules and their proofs**

```java
    // BE-D12 §2.3's named obligation: "core apply packages must not depend on Docker (or any
    // adapter's client library) types, with a planted-violation proof, exactly as
    // onlyMeshbridgeTouchesJcsmp confines JCSMP today."
    //
    // Package-scoped rather than class-scoped, and for the same reason the JCSMP rule is: it must
    // cover the whole client surface, transport and request builders alike, not the handful of
    // types today's adapter happens to name.
    @ArchTest
    static final ArchRule onlyTheDockerAdapterTouchesDockerJava =
            noClasses().that().resideOutsideOfPackage("com.solace.sm.dp.apply.runtime.docker..")
                    .should().dependOnClassesThat().resideInAPackage("com.github.dockerjava..");

    // The seam points ONE way. An adapter that could reach the engine or the journal would be able
    // to decide its own outcome, which is the whole thing BE-D12 §2.1 puts behind an interface:
    // "the core apply engine speaks only these six; everything runtime-specific lives behind the
    // seam" is a claim about the direction of the arrow, and this is the arrow.
    @ArchTest
    static final ArchRule theRuntimeSeamNeverReachesBackIntoTheEngine =
            noClasses().that().resideInAPackage("com.solace.sm.dp.apply.runtime..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.solace.sm.dp.apply.engine..", "com.solace.sm.dp.apply.persistence..");

    // Plan 004's carried-forward watch item, in its own words: "ApplyEngineConfiguration's javadoc
    // states the direction as verified by inspection (no apply.engine import under
    // apply/persistence/), but no rule enforces it the way jdbcIsConfinedToApplyPersistence
    // enforces the data-plane/persistence boundary. -> Plan 005 - needs its own planted-violation
    // fixture and review." Here it is.
    @ArchTest
    static final ArchRule persistenceNeverReachesUpIntoTheEngine =
            noClasses().that().resideInAPackage("com.solace.sm.dp.apply.persistence..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.dp.apply.engine..");
```

Each gets a proof in the established shape — planted fixture named in the failure message, **and**, for the Docker rule, a presence-then-absence control so the rule is not one that rejects everything:

```java
    @Test
    void dockerConfinementCatchesAPlantedViolationWithoutRejectingTheAdapterPackage() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> onlyTheDockerAdapterTouchesDockerJava.check(withPlantedViolations()));
        assertTrue(failure.getMessage()
                .contains("com.solace.sm.dp.apply.engine.archfixture.BadEngineDockerClass"),
                "the rule must fail because of the planted fixture. Was: " + failure.getMessage());

        // Presence first, then absence — the trap gatewayUsesApisOnly's proof avoids.
        assertTrue(withPlantedViolations().stream().anyMatch(c -> c.getName().equals(
                "com.solace.sm.dp.apply.runtime.docker.archfixture.LegalDockerClientClass")),
                "LegalDockerClientClass must be in the analysed set, or the assertion below proves nothing");
        assertFalse(failure.getMessage().contains("LegalDockerClientClass"),
                "the adapter package itself must be allowed to hold Docker types — that is the "
                        + "point of confining them there. Was: " + failure.getMessage());

        // ...and the real adapter must be clean, or the rule rejects the code it governs.
        assertFalse(failure.getMessage().contains("com.solace.sm.dp.apply.engine.ApplyExecutor"),
                "the engine must not be reaching Docker on its own. Was: " + failure.getMessage());
    }
```

- [x] **Step 2: Extend the adapter inventory**

`PlaneBoundaryTest` already carries a `PUBLISHER_IMPLEMENTATIONS` set whose purpose is to make adding an implementation impossible without putting the contract-kit obligation in front of a reviewer. Add the same for adapters:

```java
    /** Kit-certified adapters. Extend ONLY together with a subclass of
     *  AbstractRuntimeAdapterContractTest (plan 005 Task 2). */
    private static final Set<String> RUNTIME_ADAPTER_IMPLEMENTATIONS = Set.of(
            "com.solace.sm.dp.apply.runtime.docker.DockerAdapter");

    @Test
    void everyRuntimeAdapterImplementationIsInTheCertifiedInventory() { /* same shape as the
        publisher inventory, with the same planted-violation proof */ }
```

Note `InMemoryRuntimeAdapter` is **test scope in `dp-core`** and therefore not on `arch-tests`' production import — assert that rather than assume it, in the same test, so the inventory is not quietly satisfied by a fake.

- [x] **Step 3: Run, prove, commit**

Run: `mvn -f backend/pom.xml -pl arch-tests -am test`
Expected: arch-tests **25 → 32** tests (three rules + three proofs + the inventory test), all green.

Prove-it-fires, in addition to the planted fixtures: temporarily delete each of the three fixtures in turn and confirm its proof test goes red with `assertThrows` failing — a proof whose fixture has been renamed away passes silently otherwise, which is the failure `fixturesAreExcludedFromTheRealRun` already guards in the other direction.

**Measured, 2026-09-14.** arch-tests **25 → 33**, all green — one more than the predicted 32,
because the adapter inventory needs its own planted-violation proof exactly as the publisher
inventory has one; a hard-coded set compared against a scan nobody has shown is looking in the
right place certifies nothing.

The inventory ships with **two** entries, not one: `SshDockerRuntimeAdapter` arrived on this branch
from the demo stream and carries `SshDockerRuntimeAdapterContractTest`, a kit subclass, so it is
certified and listed. Finding it is what the inventory is for.

**Prove-it-fires — each fixture deleted in turn, and one rule broadened:**

| Change | **Measured red** |
|---|---|
| `BadEngineDockerClass` deleted | `dockerConfinementCatchesAPlantedViolationWithoutRejectingTheAdapterPackage` — *Expected java.lang.AssertionError to be thrown, but nothing was thrown* |
| `BadRuntimeSeamEngineClass` deleted | `theSeamDirectionRuleCatchesAPlantedViolationWithoutRejectingTheSeam` — same shape |
| `BadPersistenceEngineClass` deleted | `persistenceDirectionRuleCatchesAPlantedViolationWithoutRejectingThePackage` — same shape |
| `BadUnkittedAdapterClass` deleted | `adapterInventoryCatchesAPlantedUnkittedAdapter` — *the scan must SEE the planted adapter … Saw: [DockerAdapter, SshDockerRuntimeAdapter]* |
| `LegalDockerClientClass` deleted | the **presence** half of the docker proof — *LegalDockerClientClass must be in the analysed set, or the assertion below proves nothing*. This is the trap `gatewayUsesApisOnly`'s proof avoids, and it is now avoided here too |
| the docker rule's exemption package changed to one that matches nothing | **2 red**: the proof's "must not reject the adapter package" assertion, and `onlyTheDockerAdapterTouchesDockerJava` itself, violated **39 times** against the shipped code. A confinement rule that also rejects what it governs is broken, not strict |

**Risks:** `theRuntimeSeamNeverReachesBackIntoTheEngine` will fire the moment someone puts a convenience method on a seam type that needs the executor. That is the point, and the fix is a parameter, not an exemption.

---

### Task 12: The demo environment, and the Cache walkthrough

**Files:**
- Create: `backend/demo/docker-compose.yml`
- Create: `backend/demo/config/cache-1/application.yml` (the SM-owned file's *initial* content — SM overwrites it)
- Create: `backend/demo/README.md`
- Create: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/CacheApplyWalkthroughIT.java`

**The split, stated plainly.** The gate rests on a **stand-in** container that reproduces the Cache's external-config layout exactly (`/app/external/spring/config/application.yml`, bind-mounted, healthcheck declared in the run spec). The **Cache walkthrough** runs against the real image and is deliberately **not** part of `mvn verify`'s gate, because it needs a sibling repository built and imaged, which no gate can guarantee.

This is not lesson `R10` being repeated. `R10`'s rule is about *"a conditional-skip annotation on a suite the plan calls its completion gate"* — and this plan does not call this suite its completion gate; it says the opposite, here and in `backend/demo/README.md`. The mechanism is proven by the stand-in, unconditionally, on every build. The Cache run proves the *target*, on demand, and is reported as a walkthrough rather than as a check.

**There is no SM container in the compose file, and that is deliberate.** No application class exists — there is no `main`, no endpoint, and G2 forbids writing one. The engine runs from the test JVM against the host daemon. Inventing a deployable to host it would be the largest unrequested thing this plan could do, and the delivery sequence already names the honest Monday shape as *"a narrated walkthrough of W1 under `mvn verify`… shown as passing tests rather than as a UI"*.

- [x] **Step 1: Write `backend/demo/docker-compose.yml`** — written, and **corrected against a live run**; see the measured facts below

```yaml
# The pre-G2 half of the demo environment (delivery sequence W5, items 1 and 2).
#
# What is here: PostgreSQL 14 for sm_data, and a real Cache container with the external config
# directory SM owns, bind-mounted so SM's writes survive the container (SM-D21's round-2
# correction: the image creates that path in the container layer and declares no VOLUME).
#
# What is NOT here, and why:
#   - no SM backend service. No application class exists; every endpoint is G2's. The apply engine
#     runs from the test JVM (see backend/demo/README.md).
#   - no Solace broker. `cache-engine.solace.enabled: false` in the SM-owned file switches off
#     JcsmpSolaceConsumer/Producer/LeaderElection (@ConditionalOnProperty, measured), so the
#     walkthrough needs nothing from BE-D5, open item 32 or open item 33.
#   - no auth of any kind (G2).
services:
  postgres:
    image: postgres:14
    container_name: sm-demo-postgres
    environment:
      POSTGRES_DB: sm
      POSTGRES_USER: sm_bootstrap
      POSTGRES_PASSWORD: sm-demo-only-not-a-secret
    ports:
      - "55432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U sm_bootstrap -d sm"]
      interval: 2s
      timeout: 3s
      retries: 30

  cache-1:
    image: solace-cache-engine:local
    container_name: sm-demo-cache-1
    # SM-D3 additional-location: the image already passes
    #   --spring.config.additional-location=/app/external/spring/config/
    # on its command line, so SM changes NO launch flag. It only needs that path to persist, which
    # is what this bind provides — and it is deployment configuration, not an image change (I1).
    volumes:
      - ./config/cache-1:/app/external/spring/config
    ports:
      - "58081:8081"
    # Declared HERE, not in the Dockerfile. I1 forbids changing the image; a compose-level
    # healthcheck is deployment configuration and is what makes State.Health appear in the daemon's
    # inspect output (measured), which is what HealthGate reads. Without it the adapter reports
    # NOT_DECLARED and a health-gated strategy is refused at pre-flight — correctly.
    healthcheck:
      test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081"]
      interval: 3s
      timeout: 2s
      retries: 20
      start_period: 20s
```

- [x] **Step 2: Write `backend/demo/README.md`**

It must carry, in this order: the Cache image build (`mvn -f ~/Work/Development/solace-cache-engine/pom.xml package && docker build -t solace-cache-engine:local ~/Work/Development/solace-cache-engine`), `docker compose up -d`, the walkthrough command, **what the walkthrough proves and what it does not**, and the sentence the delivery sequence already commits to: *a live, authenticated, browser-driven control demo cannot ship without G2 ratification or the documented waiver.*

- [x] **Step 3: Write `CacheApplyWalkthroughIT`**

```java
/**
 * The narrated walkthrough: one real Cache container, one real config change, one real restart,
 * and the evidence read back from the runtime rather than from a log line.
 *
 * <p><b>Not part of the completion gate.</b> It needs `solace-cache-engine:local` built from a
 * sibling repository and `backend/demo/docker-compose.yml` up, neither of which `mvn verify` can
 * guarantee. Every mechanism it exercises is already proven unconditionally against a stand-in
 * container by DockerAdapterContractIT, DockerPrepareIT, DockerMaterializeAndStatusIT,
 * DockerLifecycleIT and ManualApplyIT. What this class adds is the TARGET: the Cache's own
 * command-line additional-location, its own container-layer path, its own image.
 *
 * <p>Enabled by `-Dsm.demo.cache=true`. The property is named in backend/demo/README.md and in this
 * plan; it gates a walkthrough, never a guard (lesson R10's distinction).
 */
@EnabledIfSystemProperty(named = "sm.demo.cache", matches = "true")
class CacheApplyWalkthroughIT {

    @Test
    void aRealCacheContainerIsPreparedStagedRestartedAndObserved() {
        // 1. prepare: the declared location is resolved from the Cache's OWN command line, the bind
        //    is confirmed persistent, and the probe round-trips through the container.
        // 2. materialize generation 1: cache-engine.max-size: 100000 -> 250000, with
        //    solace.enabled: false. Assert State.StartedAt is byte-identical afterwards.
        // 3. the operator restarts:  docker restart sm-demo-cache-1   (manual strategy: SM does NOT)
        // 4. status: the start epoch moved, the hash read-back equals what SM wrote, the image id
        //    is unchanged, and health is HEALTHY.
        // 5. the request is STAGED, its intents are PREFLIGHT + MATERIALIZE, and apply_attempt
        //    carries the pre-baseline and the observed hash.
        //
        // Print each observation with a `MEASURED:` prefix, the plan-004 convention, so the run's
        // console output IS the narration.
    }
}
```

- [x] **Step 4: Run it, and record the actual output**

```bash
cd backend/demo && docker compose up -d && cd -
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  mvn -f backend/pom.xml -pl dp-core -am verify -Dsm.demo.cache=true -Dit.test=CacheApplyWalkthroughIT
```

Paste the `MEASURED:` lines into the plan's Results section verbatim. If the Cache image cannot be built, **say so in Results** and record which half of the claim is therefore unproven — the mechanism (proven by the stand-in) or the target (not).

- [x] **Step 5: Commit.**

#### Measured, 2026-09-14 — the walkthrough RAN, and it corrected two of this plan's own facts

`CacheApplyWalkthroughIT` **2/2 green** against `solace-cache-engine:local` built from
`~/Work/Development/solace-cache-engine` (image `sha256:03a059f6f8e3…`), on the `colima` context.
Verbatim:

```
MEASURED: cache container f16611200b42 configDir=…/backend/demo/config/cache-1
MEASURED: prepare -> Ready, replicas=1 containerPath=/app/external/spring/config mechanism=ADDITIONAL_LOCATION healthSignalDeclared=true
MEASURED: before apply  liveness=RUNNING health=HEALTHY hash=sha256:a167040bdb2040c3… startedAt=2026-09-14T13:18:35.979538205Z
MEASURED: apply 01a0a012-22e6-7748-9aa3-e59716211ef9 -> STAGED
MEASURED: journal step=PREFLIGHT replica=@placement configHash=null attempt=SUCCEEDED observedHash=null
MEASURED: journal step=MATERIALIZE replica=f16611200b42faf089c… configHash=sha256:4f8d79005c6f8cc1… attempt=SUCCEEDED observedHash=sha256:4f8d79005c6f8cc1…
MEASURED: after apply   hash=sha256:4f8d79005c6f8cc1… cacheSees=application.yml size=188
MEASURED: startedAt unchanged by materialize -> 2026-09-14T13:18:35.979538205Z
MEASURED: after operator restart startedAt=2026-09-14T13:18:58.917090211Z health=HEALTHY hash=sha256:4f8d79005c6f8cc1… image=sha256:03a059f6f8e3…
MEASURED: LIFECYCLE intents issued by SM = 0 (manual strategy)
MEASURED: gated apply, before  health=HEALTHY startedAt=2026-09-14T13:18:58.917090211Z
MEASURED: gated apply 01a0a012-3d56-7d20-ba5f-b0a9301b6bf8 -> APPLIED in 6s
MEASURED: LIFECYCLE attempt outcome=SUCCEEDED pre_baseline=2026-09-14T13:18:58.917090211Z observedHash=sha256:5ed37ba567b3b5ca…
MEASURED: gated apply, after   health=HEALTHY startedAt=2026-09-14T13:19:05.632220046Z hash=sha256:5ed37ba567b3b5ca…
```

Both halves of the target are proven: the `manual` slice (SM writes, SM does not restart, the
operator does, SM observes) **and** Task 9's `rolling-health-gated` slice against the Cache's
**real** health signal — SM issued the restart, the gate polled `State.Health` and the request
landed `APPLIED` in six seconds, with the `LIFECYCLE` attempt carrying the pre-verb baseline.

**Two of this plan's measured facts were wrong, and the compose file now carries the correction.**

1. **The Cache cannot be started broker-free.** This plan recorded that
   `cache-engine.solace.enabled: false` switches off `JcsmpSolaceConsumer`, `JcsmpSolaceProducer`
   and `LeaderElection` — "measured" — and concluded the walkthrough needs nothing from `BE-D5`.
   The three `@ConditionalOnProperty` annotations are real; the conclusion is not.
   `CacheManagerService` is a plain `@Service` with a hard constructor dependency on
   `JcsmpSolaceProducer`, so the context fails to refresh: *"Parameter 2 of constructor in …
   CacheManagerService required a bean of type … JcsmpSolaceProducer that could not be found."* No
   property makes that bean appear, and making one appear would be development on the managed
   integration (`I1`). A broker is a **prerequisite of running the Cache at all**. It is the
   integration's own messaging — SM connects to nothing on it — so `BE-D5` and the two open
   ACL-shape items remain untouched, but the compose file is no longer broker-free and this plan's
   sentence saying it would be is withdrawn.
2. **The queue bind is not asynchronous in practice.** `JcsmpSolaceConsumer`'s `@PostConstruct`
   calls its own `@Async` method by self-invocation, bypassing Spring's proxy, so the bind runs on
   the main thread and a missing queue kills start-up with
   `JCSMPErrorResponseException: 503: Unknown Queue [Subcode:20]`. A one-shot `provision` service
   creates `orders-cache-q` and its subscription before the Cache starts.

Also measured, and worth recording because it was a predicted risk that **did not** materialize:
the Cache's `cache-hit-response.template-mappings.ivr` lines — the ones pointing at an absolute
path on another developer's machine — are **commented out** in its own resource, so the SM-owned
file never had to work around them.

**Deviations from the task as written:**

1. **No PostgreSQL service in the compose file.** The walkthrough gets `sm_data` from
   Testcontainers exactly as every other `dp-core` IT does. A compose service nothing connects to
   would read as though the walkthrough used it, and two bootstrap paths can drift.
2. **The SM-owned file is a committed template, `backend/demo/cache-application.yml`, copied into
   a gitignored `config/cache-1/` working directory.** The walkthrough overwrites that file by
   design and `ConfigFileStore` retains pre-images beside it
   (`.application.yml.sm-pre-<generation>`, observed); committing the bind directory itself would
   make every demo run dirty the worktree.
3. **A `broker` service and a one-shot `provision` service**, for the two measured reasons above.
   The broker also needs `ulimits.nofile.hard: 1048576` — measured: at 42192 it logs
   *POST Violation [022]* and shuts the router down.
4. **Compose project name `sm-plan005-demo`, declared in the file.** Docker is shared state on
   this machine and another Solution Manager stream was running `sm-demo-control-postgres-1`
   throughout; a project name derived from the directory would collide the moment two worktrees
   both have a `backend/demo`.
5. **A second test.** Task 9 did not exist when this task was written; the gated slice against a
   real health signal is the capability most worth demonstrating and costs six seconds.

**Risks:** the Cache's `application.yml` carries `cache-hit-response.template-mappings.ivr` pointing at an absolute path on another developer's machine. The SM-owned file must not reproduce it. If the Cache refuses to start without it, that is a finding about the Cache's config defaults, recorded in Results, not worked around by widening what SM writes. *(Measured: it did not — see above.)*

---

### Task 13: Documentation, the register row, lessons, and Results

**Files:**
- Modify: `backend/README.md`
- Modify: `docs/design/backend/decisions.md` (one changelog row)
- Modify: `docs/design/DECISIONS.md` (open item 1's disposition)
- Modify: `docs/tasks/lessons.md`
- Modify: `docs/plans/backend/005-runtime-adapter-and-docker-apply.md` (Results)

- [x] **Step 1: `backend/README.md`** — the two new packages, the Docker-backed IT command, the demo pointer, and the standing note that the adapter's client library is confined by an ArchUnit rule.

- [x] **Step 2: The changelog row in `docs/design/backend/decisions.md`**, which must state, as facts and not as claims:

*(Template. The row as landed is the last line of [`../../design/backend/decisions.md`](../../design/backend/decisions.md), dated 2026-09-14, with every `<…>` slot filled from the gate outputs in Results and the intra-folder links resolved there. The links are code spans here because they resolve from `docs/design/backend/`, not from this file.)*

```markdown
| <execution date> | **Plan 005 executed** ([`../../plans/backend/005-runtime-adapter-and-docker-apply.md`](../../plans/backend/005-runtime-adapter-and-docker-apply.md), Results). `BE-D12`'s seven operations ship as `com.solace.sm.dp.apply.runtime.RuntimeAdapter` with a contract kit every implementation must pass, and `DockerAdapter` as its first implementation; the engine's step loop drives plan 004's journal under §2.2's rules 0–4. Obligation **(r)** was expected to close here and does **not**: see the landed row for what `StopDuringApplyIT` proves and what it pins as a gap. §2.3's ArchUnit obligation is discharged by `onlyTheDockerAdapterTouchesDockerJava`, and plan 004's carried-forward `engine → persistence` watch item by `persistenceNeverReachesUpIntoTheEngine`; both carry planted-violation proofs. The seam as built is `BE-D21` + `BE-D22`'s accepted block and `BE-D23`'s scope, all three recorded **before** Task 1 rather than reported after Task 13 — `BE-D12`'s text is flagged in `eda-walking-skeleton.md` §2.1 and §3.1, not rewritten (`I3`). All three stay **PROPOSED**; implementation is not ratification, the same sentence `BE-D16`–`BE-D18` each carry. The exhaustion path journals `ROLLBACK_ASSESS` around a read-only assessment and `ROLLBACK` — carrying the restore target's hash, opened **before** the destructive call — around the restore, with one `VERIFY` per replica as the durable per-replica record; §3.1's *"a restore journals `ROLLBACK`, never `ROLLBACK_ASSESS`"* invariant is enforced where that table says it must be, in the adapter contract kit, with a planted-restore mutation. The v1 engine is single-placement and refuses an instance-wide request before admission; `InstanceWideIdentityLimitIT` pins both halves of the identity limit against the shipped DDL and is expected to go red when a persistence plan lifts it. No migration, no `contracts/` change, no `sm_control` object, no endpoint: G2 is intact and the grep over this plan's whole diff is in Results. Measured on execution: <surefire per module>, failsafe <completed/skipped>, `./contracts/run-checks.sh --strict` ALL CHECKS PASSED, DDL-replay NO DRIFT, `git diff --check` clean. |
```

- [x] **Step 3: Prove G2 compliance over the whole diff**

```bash
git diff 595d27b..HEAD --stat
git diff 595d27b..HEAD -- backend/ | grep -nEi \
  'principal|credential|password_hash|must_rotate|role_binding|\brole\b|permission|authoriz|authentic|session|csrf|login|logout|audit_log|@RestController|@GetMapping|@PostMapping|RequestMapping|sm_control'
```

Expected: matches only in **comments and test-fixture names** that *reference* the gate — e.g. the `sm-demo-only-not-a-secret` compose password, `requestedBy` in an `ApplyCommand`, and javadoc naming G2. **Read every match.** `L12`: the count is not the evidence; the reviewed diff is. Paste the full output into Results, not a summary of it.

- [x] **Step 4: `docs/design/DECISIONS.md`, open item 1**

Open item 1 is *"Specify the `dp-remote` apply protocol — the agent protocol's safety properties (generation ordering, idempotency, lease, crash-safe resume) have no `dp-remote` equivalent."* Append its disposition: specified by `BE-D12`, persisted by `BE-D13`, and now **implemented and proven** for four of the five properties, with the fifth (lease-bounded authority) still named out of scope while CP and DP are co-located — design §8 row 7. Do **not** mark the item closed: `BE-D12` is PROPOSED, and implementation does not change ratification status (the sentence `BE-D16`, `BE-D17` and `BE-D18` each end with).

- [x] **Step 5: Lessons** — `L22` (cross-cutting: measuring an input is not measuring the outcome), `R11` and `R12` (repo-local)

Append every lesson the execution actually produced, in the file's `## R<n> — title` / **What happened** / **Rule** shape. Do not invent lessons to fill the section; plan 004's `R7`–`R10` are there because they were each paid for.

- [x] **Step 6: Fill Results**

Status; the per-task commit table (task, commit, red observed, green counts, prove-it-fires outcome, review verdict, fix waves); the final gate outputs **verbatim**; every deviation with its measured reason; the watch items carried forward with their homes; and the `MEASURED:` lines from Task 12.

- [x] **Step 7: Verify the documents and commit**

```bash
for f in backend/README.md backend/demo/README.md docs/design/backend/decisions.md \
         docs/design/DECISIONS.md docs/tasks/lessons.md \
         docs/plans/backend/005-runtime-adapter-and-docker-apply.md; do
  grep -o "](\([^)#]*\.md\)" "$f" | sed 's/](//' | sort -u | while read -r l; do
    [ -e "$(dirname "$f")/$l" ] || echo "BROKEN in $f: $l"
  done
done; echo "link check done"
grep -n "TBD\|TODO\|implement later\|decide later\|fill in\|similar to Task" \
  docs/plans/backend/005-runtime-adapter-and-docker-apply.md backend/README.md backend/demo/README.md
git diff --check && echo clean
```

Expected: `link check done` with no `BROKEN` lines; the placeholder grep prints nothing except this plan's own `<execution date>`, `<surefire per module>`, `<completed/skipped>` and `<outcome>` slots, which are measured values the executor fills, not decisions; `clean`.

---

## Final verification — the commands, and what each one settles

Run in this order, from the repository root, at the end of execution and again before the plan is declared done.

```bash
# 1. The whole reactor, with Docker reachable. THE gate.
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify
echo "exit=$?"

# 2. Count what actually ran, from the XML, never from the console (L12 and plan 004's R10).
for m in mesh-events dp-core arch-tests; do
  echo -n "$m surefire: ";
  grep -ho 'tests="[0-9]*"' backend/$m/target/surefire-reports/TEST-*.xml 2>/dev/null \
    | grep -o '[0-9]*' | paste -sd+ - | bc
done
cat backend/dp-core/target/failsafe-reports/failsafe-summary.xml

# 3. The contracts gate. Nothing in this plan touches contracts/, so this must be unchanged.
./contracts/run-checks.sh --strict

# 4. The DDL-replay baseline. This plan writes no SQL, so NO DRIFT is the expected result and a
#    drift is a finding — it would mean something edited a migration that was not supposed to.
python3 docs/design/backend/evidence/ddl-replay/replay.py

# 5. Whitespace.
git diff --check && echo clean

# 6. The Docker-absent expectation, measured rather than assumed (Task 3 Step 7's method).
DOCKER_HOST=unix:///nonexistent/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/nonexistent/docker.sock \
  mvn -f backend/pom.xml -pl dp-core -am clean verify; echo "exit=$? (MUST be 1)"

# 7. G2, over the whole diff (Task 13 Step 3). Read every match; do not report the count alone.
git diff 595d27b..HEAD -- backend/ | grep -nEi \
  'principal|credential|password_hash|must_rotate|role_binding|\brole\b|permission|authoriz|authentic|session|csrf|login|logout|audit_log|@RestController|@GetMapping|@PostMapping|RequestMapping|sm_control'
```

| Command | What it settles | What it does **not** settle |
|---|---|---|
| 1 + 2 | Every guard in this plan ran and passed | Nothing about the real Cache image — that is Task 12, run separately |
| 3 | The contract surface is untouched | — |
| 4 | No migration moved | — |
| 6 | The gate is not a false green on a Docker-less host | — |
| 7 | No auth-bearing code was written | That the design is right; only that the gate was respected |

### Expected module and test deltas

Baseline at `595d27b`, measured by plan 004's Results: surefire **268** (mesh-events 127 / dp-core 116 / arch-tests 25), failsafe **142 total (140 executed, 2 skipped)**.

| Module | Baseline | Expected after | Source of the delta |
|---|---|---|---|
| mesh-events | 127 | **127** | untouched |
| dp-core surefire | 116 | **≈ 174** | `ConfigFileStoreTest` 9, `RuntimeSeamTypesTest` 5, `InMemoryRuntimeAdapterContractTest` 13, `DockerErrorsTest` 2, `DockerPropertiesTest` 4, `LaunchSourceTest` 5, `HealthGateTest` 5, `ResumePlannerTest` 15 |
| arch-tests | 25 | **32** | three rules, three proofs, one adapter-inventory test |
| dp-core failsafe | 142 | **≈ 221** | `DockerEngineClientIT` 6, `DockerPrepareIT` 6, `DockerMaterializeAndStatusIT` 7, `DockerLifecycleIT` 9, `DockerEvidenceSecrecyIT` 3, `DockerAdapterContractIT` 13, `ManualApplyIT` 14, `InstanceWideIdentityLimitIT` 3, `EnsureIntentRaceIT` 1, `RollingApplyIT` 7, `ResumeIT` 3, `StopDuringApplyIT` 2; plus `CacheApplyWalkthroughIT` reported **skipped** unless `-Dsm.demo.cache=true` |

⚠️ **These are predictions and they will drift.** Plan 004's Results records that five of its own prototype-era counts were stale by the time its last task finished. The executor records the **measured** figures in Results and does not reconcile them to this table; a difference is information, not an error.

---

## The Monday deliverable, stated honestly

Today is Friday 2026-09-11. The target is Monday 2026-09-14. **This plan is not one weekend of work**, and no sequencing makes it one — the delivery sequence says so in its own words, and nothing here contradicts it.

**What can honestly be shown on Monday if execution starts now:** whatever subset of Tasks 1–8 has landed, as **passing tests**, narrated. Concretely, the first genuinely demonstrable milestone is the end of **Task 8**:

> A real container's configuration file, written by SM through the declared external-config path, with the write proven atomic, the mount proven identical from inside the container, the config hash read back from the runtime, the start epoch proven **unchanged** (a config write restarts nothing), the operator's own restart then observed as a moved start epoch and an unchanged image identity — and every one of those facts recorded as an `apply_request`, an `apply_step_intent` and an `apply_attempt` row that the database's own constraints have accepted.

**What it is not:**

- **Not a browser control demo.** There is no HTTP endpoint, no login, no screen. Every endpoint is G2's, and `CLAUDE.md`'s bar is on auth-bearing *code*, not merely on exposure (delivery sequence §2.1, `DECISIONS.md` open item 38).
- **Not scope evaluation.** `SM-D33`'s prefix rule is W2, entirely behind G2. The one seed element that would exercise it does not exist.
- **Not a live-updating console.** No mesh event is emitted by this plan at all, by scope.
- **Not, by Monday, the whole of W1.** Tasks 9–13 are real work after the milestone above.

**The single highest-value action remains the one the delivery sequence already named,** and it is not engineering: ask the architect group whether `origin/dev/frontend`'s `D18`/`D19` rulings stand (open item 34). If they do, G2 lifts and the entire post-G2 column opens at once. It is one email, and it decides whether the demo is blocked at all.

---

## Design questions this plan raises, and does not answer

Each is routed, none is absorbed. `I4` and `I3` both apply: these are proposals against proposals, and the fix for a decision is a new decision.

1. **`BE-D12` §2.1's printed signature — ANSWERED, before Task 1.** Taken as [`BE-D21`](../../design/backend/decisions.md) (PROPOSED, 2026-09-11), with `BE-D12`'s text flagged in `eda-walking-skeleton.md` §2.1 rather than rewritten (`I3`). What remains with the architect group is ratification, which decides whether the signature **stays**, not whether it may be built. Listed here so the answered question is visible beside the open ones rather than silently removed.
2. **The exhaustion path's journal shape — ANSWERED, before Task 1.** Taken as
   [`BE-D22`](../../design/backend/decisions.md): `assessRollback` (read-only, journals the hashless
   `ROLLBACK_ASSESS`) and `restore` (journals `ROLLBACK` with the target hash, opened before the
   destructive call), with one `VERIFY` per replica afterwards as the durable per-replica record.
   The one case `hash_on_config_steps` cannot express — a restore target of *absence* — has a
   defined, tested outcome (`APPLY_INDETERMINATE` with `rollback.target.absent`). What remains with
   the architect group is ratification, and a later persistence plan may choose to admit a hashless
   restorative rollback so a first apply can be rolled back automatically; that is an improvement,
   not an unhandled path.
3. **The v1 engine's placement scope — ANSWERED, before Task 1.** Taken as
   [`BE-D23`](../../design/backend/decisions.md): single-placement, instance-wide refused before
   admission, with both halves of the identity limit pinned by `InstanceWideIdentityLimitIT`. The
   amendment that would lift it — a `placement_id` on `apply_step_intent`, the UNIQUE and
   `placement_scoped_steps` widened, the fence and watermark interactions re-measured — is named in
   `BE-D23` and belongs to the plan that revisits `sm_data`.
4. **Where do placements actually come from?** `DockerPlacementCatalog` resolves them from configuration because the inventory tables are W5's and the endpoints are G2's — and plan 004 left `apply_request.placement_id` with no referential guard at all (design §8 row 13). `BE-D23` adds a third gap pointing at the same place: the intent table cannot name a placement either. **A future placements/inventory plan, and the persistence plan `BE-D23` routes to — they may well be the same plan.**
5. **Does `rolling-health-gated` belong in the Solution Package's declared capabilities?** This plan refuses the strategy at pre-flight when the runtime publishes no health signal. `SM-D15` says *"SM offers only strategies the solution's declared capabilities support"* — which suggests the refusal should happen when the strategy is **offered**, not when it is executed. The runtime-level check is the fail-closed backstop either way. **`SM-D19`'s package schema, when the capability leg is specified.**
6. **What calls `resume` on startup?** Nothing, because no application class exists. The path is implemented and proven; it is not wired. **The plan that first ships an SM deployable.**
7. **Cross-scope `STAGED` supersession** (design §8 row 14) — carried forward from plan 004, still undecided, still pinned exact-scope-only by `MonotonicityIT`. Unchanged by this plan, and the step loop does not make it any more or less urgent. **Architect group.**
8. **`ApplyRequestRepository.foldAttemptSummary`'s untyped `jsonb` bind** — plan 004 routed it to "Plan 005's compaction job". There is no compaction job here, and the method still has no caller. **Carried forward unchanged to whichever plan builds pruning.**

---

## Out of scope — explicit boundaries, restated

Restated from Scope so an executor reading a single task cannot miss it: **no authentication, principal, credential, session, CSRF, role, role binding, permission catalog, `authorizationService`, `sm_control` object of any kind, or HTTP endpoint — authenticated or not. No `contracts/` change, no generated types, no event emission, no mesh session, no browser or `LiveStateClient` work. No new `sm_data` migration, column, CHECK, index or DCL. No frontend import and no `packageVersion` change. No systemd or Kubernetes adapter. No pruning or archival scheduler. No placements referential guard. No adoption of the orphan telemetry migration. No CI. No instance-wide apply and no placement fan-out (`BE-D23`) — and, specifically, no encoding of a placement into `replica_ref` to simulate one.**

Anything a task appears to need from this list is a **plan defect** — stop and report it. In particular: if a task appears to need a change to plan 004's persistence semantics, that is a **design defect report**, not a migration.

---

## Final self-review checklist

Run before the plan is declared done, and re-run at execution end.

- [x] **Spec coverage.** `BE-D12` §2.1's operations, as amended to seven by `BE-D21`/`BE-D22` → Tasks 1 (interface), 4, 5, 6 (Docker). §2.1's `rollbackOrMarkIndeterminate` row → Task 6's `assessRollback`+`restore` and Task 8's `exhausted`; §3.1's step-label-truthfulness invariant → Task 2's contract kit, its named home. §2.2 rule 0 → Task 8 (`step`'s control flow, `ManualApplyIT`). Rule 1 → plan 004's `AdmissionService`, consumed unchanged by Task 8; no new code, and that is the point. Rule 2 → Task 8's deterministic `idempotencyKey` + plan 004's `StepIntentService`. Rule 3 → Task 10's `ResumePlanner`, every cell. Rule 4 → **Task 8's** bounded-retry loop and `exhausted`, over plan 004's `AttemptService.RetryCapExceeded` — in Task 8 and not Task 10 because a manual apply can exhaust its retries, and leaving that unrouted to keep Task 8 smaller would have left partial writes unclassified. §2.3's adapter table (Docker column) → Tasks 4–6. §2.3's ArchUnit obligation → Task 11. §2.4's three "does not change" clauses → asserted (`SM-D15` by Task 8's manual path and Task 9's ordering test; the agent path untouched; no artifact operation, by Task 6's `I1` test). `SM-D21` → Task 4. `SM-D22`'s bounded verification → Task 5 (`status` reads back **the file SM wrote**, which is precisely the one layer `SM-D22`'s table says is verifiable). `BE-D16` → Task 8 (the manual path lands `STAGED`) and Task 9 (`aNonManualApplyIsNeverObservedStaged`, which needs a non-manual strategy to exist). `BE-D21` → Tasks 1 and 2, and the decision itself is in the register **before** Task 1. Obligation **(r)** → Task 10. Plan 004's `engine → persistence` watch item → Task 11. Plan 004's `ensureIntent` lock watch item → Task 8 Step 6. Plan 004's `I6` content obligation → Task 7. §3.5's mapping → **deferred, with its reason stated** in Scope.
- [x] **Gate coverage.** No task writes auth, an endpoint, a `contracts/` file, an `sm_control` object, a migration or TS. `I5`: no new SQL; every persistence call carries a `TenantId` because plan 004's signatures require one. `I6`: Task 7 is the content obligation; `RuntimeAdapterException` carries no cause; `ConfigFileStore` reports no path; `LaunchSource` reads two env names and reports no other. `I7`: nothing imports `com.solace.sm.cp..`. `I1`: Task 6's image-identity test, and the demo healthcheck is compose-level.
- [x] **Placeholder scan.** No TBD/TODO/"similar to Task N". The angle-bracket slots are exactly four, all in Task 13's changelog row and Task 8's commit message: `<execution date>`, `<surefire per module>`, `<completed/skipped>` and `<outcome>` — measured values the executor fills from the run, never decisions. Verify by grepping for `<` followed by a lowercase letter and confirming the match set is those four.
- [x] **Type and signature consistency.** `ConfigFileStore.{write,restore,readBackHash,hashOf,preImageKind,preImageHash,Restoration,PreImage}` (T1) used by T5, T6; `RenderedConfig.{of,bytes,hash,sha256}` (T1) by T1, T2, T5, T8; `PlacementRef.{scope}` (T1) by T4, T8; `TargetReplica.{placement,ref}` (T1) everywhere; `PreflightResult.Ready.{replicas,location,healthSignalDeclared,runtimeRef}` (T1) by T4, T8, T9; `RuntimeStatus.{hash,startBaseline,liveness,health,observedHash,runtimeRef}` (T1) by T5, T8, T9, T10; `RollbackAssessment`'s four variants and `Restorable.targetHash()` (T1) by T2, T6, T8; `RestoreResult.runtimeRef()` (T1) by T6, T8; `RuntimeAdapterException.errorClass()` (T1) by T3–T8; `AbstractRuntimeAdapterContractTest.Fixture` (T2) by T7 Step 8; `DockerEngineClient.{inspect,list,restart,stop,statInContainer,PathStat,at,fromEnvironment}` (T3) by T4–T6; `DockerErrors.classify` (T3) by T3; `DockerPlacement`'s seven components (T4) by T4–T6; `DockerPlacementCatalog.lookup` and the private `enumerate` (T4) by T4–T6; `LaunchSource.{of,declaredLocations,competingLocations}` (T4) by T4; `ApplyOutcome.{Landed,NotAdmitted,StrategyUnsupported,ScopeUnsupported}` (T8) by T8–T12; `ApplyExecutor.{execute,idempotencyKey}` (T8) by T8–T12, its private `{runManual,preflight,materialize,readBack,exhausted,verifyRestore,closeFailed,land}` (T8) internal, and `resume` (T10) by T10; `ApplyEngineProperties.stepBudget` (T8) by T8, `{healthGateDeadline,healthPollInterval}` (T9) by T9; `HealthGate.await` and `GateVerdict`'s three variants (T9) by T9; `ResumePlanner.decide` and `StepDecision` (T10) by T10. **No plan-004 signature changes anywhere**, and no T8 symbol resolves to a T9 or T10 artifact — the [execution-boundary audit](#execution-boundary-audit) is where that is checked row by row.
- [x] **Every guard has a mutation.** Thirteen tasks, and every one with production code carries a prove-it-fires table. Four of them are *test additions* rather than mutations — Task 4's per-replica violation fixture; Task 8's rule-0 ordering assertion on the `ROLLBACK` intent (moving the intent after the restore leaves every state assertion green, so only an ordering assertion can falsify it); Task 8's partial-restore fixture (without it, `verifyRestore`'s comparison against `targetHash` is asserted by the code rather than measured); and Task 9's `everyMaterializeIntentPredatesEveryLifecycleIntent`, which lives in Task 9 because Task 8 issues no lifecycle step for a materialize to be interleaved with. In each case the existing suite could not distinguish the correct code from the broken code, and that is recorded as such: "the mutation did not turn anything red" is a finding about the tests, not a pass.
- [x] **No forward reference from Task 8 to Tasks 9 or 10.** Read `ApplyExecutor` as Task 8 leaves it and confirm it names none of: `HealthGate`, `GateVerdict`, `restartAndGate`, `ResumePlanner`, `StepDecision`, `resume`, or any `Strategy` value other than `MANUAL`. Confirm `ApplyEngineConfiguration` wires no bean whose type Task 9 or 10 creates. Confirm `ManualApplyIT` names no rolling or resume behaviour. This is the check the earlier revision failed; run it by reading the file, not by grepping for the word "Task".
- [x] **No adapter-signature or scope deviation is implemented that the register does not record.** The seam as built must equal `BE-D21`+`BE-D22`'s accepted block — seven operations, those parameter types — and `eda-walking-skeleton.md` §2.1 and §3.1 must carry the flags on `BE-D12`'s superseded text. If execution finds a further thing the design cannot express, it is another decision — **stop and report it**, do not widen an existing decision in place and do not carry it as a plan note. That is the specific failure this plan already made twice.
- [x] **No restorative path is journalled as an assessment.** Read `exhausted` and confirm: `ROLLBACK_ASSESS` wraps only `assessRollback`; the `ROLLBACK` intent is created with a non-null `config_hash` and committed **before** `adapter.restore` is called; `VERIFY` follows every restore, one per replica. Confirm the contract kit's `assessRollbackNamesTheTargetHashAndMutatesNothing` exists and that planting a write inside `assessRollback` turns it red. Confirm no code path or sentence claims a `RollbackAssessment` or `RestoreResult` value is the evidence — the record is attempt rows, and the plan says so in three places.
- [x] **Instance-wide is refused, and the refusal is proven empty.** Confirm the check precedes `admission.admit`, and that `anInstanceWideRequestIsRefusedBeforeAdmissionAndPersistsNothing` asserts all four of: no request row, no intent, no attempt, no adapter call. Confirm `InstanceWideIdentityLimitIT` exists and asserts the **current** model rather than the desired one — it is a pin on a known limit, and a pin that asserts the fix is a pin that never fires.
- [x] **Render failure terminalizes.** A `RenderedConfigSource` that throws must leave the request in a documented terminal state with the scope unblocked, and that must be proven by a test that goes red when the routing is removed. Confirm all four halves are present in Task 8: the state (`FAILED_NO_EFFECT`), the evidence that no write preceded it (no `MATERIALIZE` intent, zero adapter calls), the scope-release proof (a subsequent apply at the same generation is admitted), and the `I6` proof (no renderer text in the attempt row, the outcome or the log). Confirm the prove-it-fires mutation is the removal of the `catch` — not a weaker one — and that it is recorded as **3 red**.
- [x] **No claim is made that an artifact does not enforce** (`L19`). Where this plan defers — event emission, the other two adapters, the resume trigger, the placements table — it says so in Scope or in Design questions, and Results repeats it.

---

**Re-run at execution end, 2026-09-14.** Every box above holds as written except where this note
says otherwise; two answers changed during execution and one during review.

- **Type and signature consistency.** `ApplyOutcome` has **five** variants, not four:
  `OperationUnsupported` was added in Task 9 because widening the strategy set opened a hole — a
  `restart` request carries a strategy but no generation. `ResumePlanner` exposes
  `decideRollback` as well as `decide`, added during the review fix so resume's tail can ask the
  rollback question without the attempt-presence short-circuit. `ApplyExecutor`'s private set is
  `{run, preflight, materialize, readBack, exhausted, verifyRestore, resumeOne, resumeExhaustion,
  observe, observeAll, originalFailure, settle, openAttemptOf, strand, closeFailed, land}`;
  `runManual` was renamed `run` when Task 9 gave it a second branch. `AdmissionService.stopInFlight`
  was added in Task 10 and **removed** during the review fix, with the behaviour it served.
- **No forward reference from Task 8 to Tasks 9 or 10.** Held at Task 8's commit and was read, not
  grepped. It is necessarily false of `ApplyExecutor` *now* — Tasks 9 and 10 are what put those
  symbols there.
- **No adapter-signature or scope deviation the register does not record.** Held: the seam's seven
  operations and their parameter types are untouched by Tasks 9–13. Two further things the design
  could not express were found and **reported rather than absorbed** — the `stop`/`prepare`
  enumeration contradiction (Task 10) and, through it, obligation (r)'s unimplementability — and
  both are in Results under design questions rather than carried as plan notes.
- **`L19`.** The review's own most serious finding was a claim this checklist item exists to
  prevent: obligation (r) recorded as PROVEN by a test that proved something else. Withdrawn.

## Execution addendum — three review findings fixed before demo validation (2026-09-14)

Found by review of the Task 1–8 checkpoint, all three fixed with mutation evidence before any
demo run.

**1. `ConfigFileStore` durability was partial.** `write()` was already temp → fsync → rename, but
`capturePreImage` used a bare `Files.copy`, the absence marker a bare `Files.write`, and `restore`
a bare `Files.copy` — so three of the four writers were neither atomic nor fsynced. A crash could
lose a pre-image while keeping the config write that replaced it, and a later rollback would then
restore a file that should not exist, or restore nothing at all. All four now route through one
`durablyPlace` helper.

⚠️ **The claim is about which code path ran, so the test had to be too.** No assertion over
directory contents can tell an atomic temp-and-rename from a plain `Files.copy`: both leave the same
files and no residue. `ConfigFileStore.placements()` records, in order, every path the helper put
bytes at, and the tests assert the exact ordered sequence for each operation — which is also what
makes the absence-marker-before-config-write ordering checkable. Mutations: restore → direct copy
(**1 red**), pre-image capture → direct copy (**2 red**), absence marker → bare write (**1 red**).

**2. `ApplyExecutor.materialize` did not check what the adapter said it wrote.** It closed the
attempt `SUCCEEDED` with `result.writtenHash()` whatever that was, so an adapter reporting a hash
other than the one the `MATERIALIZE` intent bound could carry the request to `STAGED`. The
comparison is now against the **intent's** `config_hash` — the durable row a crash would resume
from, not the in-memory config — and a mismatch is classified `materialize.hash.mismatch`, closed
with the replica's actual read-back as `observed_hash`, and routed through the existing bounded
retry to `BE-D22`'s exhaustion journal. In the natural case the replica is then holding a value this
apply never intended, which correction A refuses to overwrite, so the request reaches
`APPLY_INDETERMINATE`. Mutation: remove the comparison → the request reaches `STAGED` and the test
goes red.

**3. The render `I6` proof did not cover the log.** It asserted persistence and the returned outcome
but not the root logger, which is where a caught exception would surface if anyone ever logged it —
and plan 004 measured exactly that class of leak one layer in. The test now wraps the run in
`LogCapture`, whose collector already walks the throwable chain, and asserts the sentinel, the path
**and the exception type** are absent from all three surfaces. Production stays message-free by
construction: the executor never reads the renderer's exception and never logs it. Mutation: log the
caught exception → red.

## Results

**Status: all thirteen tasks executed, and reviewed.** Two independent reviews ran before any
completion claim — one adversarial code review, one audit of the documentation against the
repository. They returned **two BLOCKERs, seven MAJORs and eleven MINORs**; every one is addressed
below or in the artifacts, and three of them changed what this plan *claims* rather than what it
does. The most consequential: **obligation (r) is not proven**, and an earlier revision that said
it was had also shipped a stop mechanism contradicting §3.3.

**A post-review addition, on the developer's direction (2026-09-14), and then a second review of
that addition:** a stop must be able to
address a particular instance or a number of them, and resume the same. That dissolved the
contradiction this plan had reported as a blocker, from the other end — the operator says what to
stop, so the seam needs an enumerator that does not presuppose an apply. `BE-D25` and `BE-D26`
were taken design-first and then built; **obligation (r) is now PROVEN**, and the "stop is not
driveable" limit is withdrawn. See *The stop half* below.

**Status: all thirteen tasks executed.** Tasks 1–8 landed on `integration/dp-remote-demo`; Task 8's
step 6 and Tasks 9–13 landed on `plan005-runtime-completion`, branched from `7d20d04`. The design
this plan implements is still **PROPOSED** — `BE-D12`, `BE-D13` and the three decisions this plan
took before Task 1 (`BE-D21`, `BE-D22`, `BE-D23`) are all unratified, and executing against them
does not change that.

### Per-task record

| Task | Commit | Red observed | Green | Prove-it-fires |
|---|---|---|---|---|
| 1–8 | `2c1d87c` (and the three review fixes it carries) | per task, recorded in the execution addendum | surefire 184 / failsafe 259 at that point | recorded in the addendum below |
| 8 step 6 | `35fee43` | `EnsureIntentRaceIT` 3 new | dp-core failsafe 263 | delete the executor's terminal re-read → **1 red**, `TerminalRequest … obligation (q) forbids new intents on it` |
| 9 | `3f8d801` | `HealthGateTest` 5, `RollingApplyIT` 8 | surefire 361 / failsafe 272 | 7 mutations, **all fired** — the table in Task 9 step 5 |
| 10 | `255a13a`, corrected in the review commit | `ResumePlannerTest` 20, `ResumeIT` 9, `StopDuringApplyIT` 2, `ApplyRequestRowTest` 3 | surefire 384 → 386 / failsafe 279 → 285 | 6 mutations at Task 10, **all fired**; 2 more after review, **both fired** — the routing mutation re-materializes over a completed rollback (6 writes, not 5) and the isolation mutation aborts the sweep |
| 11 | `0f61aac` | arch-tests 25 → 33 | surefire 392 | 4 fixtures deleted in turn + 1 legal control + 1 broadened rule, **all fired** |
| 12 | `bb25280` | `CacheApplyWalkthroughIT` 2, against the real Cache | failsafe 281 / 4 skipped | n/a — a walkthrough, not a guard |
| 13 | *(this commit)* | — | the final gate below | — |
| review | *(this commit)* | 2 BLOCKER + 7 MAJOR + 11 MINOR, all addressed | surefire 394 / failsafe 285 | 2 further mutations, **both fired** |

### The independent review, and what it changed

Two reviews ran before any completion claim: an adversarial code review of `7d20d04..HEAD` against
this plan and the design, and an audit of every documented claim against the repository and the
sibling Cache repo. Between them: **2 BLOCKER, 7 MAJOR, 11 MINOR**. Nothing was waved off.

| # | Finding | What changed |
|---|---|---|
| B1 | `resumeOne` routed on the frontier step alone, so a `ROLLBACK` frontier with **no open attempt** (a crash in rule 0's own window, or after the restore attempt closed) hit a "this is unreachable" throw, left the request `RUNNING` — scope-blocked, with no operator exit — and poisoned every later sweep | Resume now routes by **phase**: any journalled `ROLLBACK_ASSESS`/`ROLLBACK`/`VERIFY` finishes the tail on its own path. `ResumePlanner.decideRollback` is public so the tail can ask the question without the attempt-presence short-circuit. Two regression tests |
| B2 | A crash inside the `VERIFY` sweep left a `VERIFY` frontier → `RERUN` → **back into the step loop**, re-materializing the abandoned generation over a rollback that had already completed, and assessing a second time | Same fix. `aCrashInsideTheVerifySweepFinishesTheRollbackAndNeverRematerializes` pins it; the mutation that restores frontier-routing measures **6 materialize calls instead of 5** |
| M1 | `attempts.open` can throw `RetryCapExceeded`/`ConcurrentAttempt` from five call sites, and `resume(TenantId)` had no per-request isolation — one unrecoverable request blocked every other request of the tenant, permanently | The sweep catches per request and lands `APPLY_INDETERMINATE`, the only state with an operator exit. The classifier is fixed and the exception's text is never read (`I6`) |
| M2 | `GateVerdict.Unavailable` was treated as unreachable and **threw**, leaving the request `RUNNING`. It is reachable: health is observed per poll, and a replica removed mid-gate reports `NOT_DECLARED` | Lands `APPLY_INDETERMINATE`. And `HealthGate` now tells `Liveness.ABSENT` apart from "no signal declared" — an operator reading `gate.signal.absent` about a deleted container would look at the wrong thing |
| M3 | **Obligation (r) was claimed PROVEN and was not.** §3.3 defines it as the stop *executing at the adapter*, the apply *not* cancelled in place, and the apply observing a stopped replica through `status`. The shipped mechanism polled `apply_request` and halted — all three clauses inverted — and covered only `restartAndGate`, so `manual` and the materialize loop never noticed | The poll was **removed** rather than relabelled: it invented a cancellation semantic against a written design clause. Every "proven" claim withdrawn. `StopDuringApplyIT` now proves the database half and **pins the gap**, so landing (r) must rewrite a test |
| M4 | `aNonManualApplyIsNeverObservedStaged` was a tautology — it sampled `blockingRequests`, whose SQL excludes `STAGED` structurally | Rewritten to sample `stagedRequest` (the query that *can* return one) and to name the real proof: `land` throws `staged_requires_manual` before any write, so no exception **is** the evidence |
| M5 | "`JcsmpSolaceConsumer`'s `@PostConstruct` calls its own `@Async` method" — **false**. That method is empty; the caller is `LeaderElection`, itself conditional on `management.enabled`, which the SM-owned file switches **off** | Corrected in three places. Re-measured: against a broker whose queue list was `{"data":[]}` the Cache reached healthy in 3 s, so the `provision` service was **unnecessary and was deleted**. `L22` now carries the recursion — the correction repeated the error the lesson is about, one layer down |
| M6 | "the Cache's **real** health signal" — the image ships no `HEALTHCHECK`; the probe is SM's own, declared at the compose layer | Reworded everywhere to "the container's live `State.Health`, produced by the healthcheck SM declares" |
| M7 | "G2 is intact" (unscoped) is false at HEAD — the branch base carries an authenticated demo slice under two recorded waivers; and the G2 scoping sentence mis-assigned four of plan 005's own commits to another stream | Both corrected. `backend/README.md`'s stale absolute "no auth-bearing code exists" is corrected in place with the waivers named |
| MINORs | stale compose comment; two Javadoc blocks on one method; `ApplyOutcome` saying the executor drives `stop`; test-count and cell-count drift in three files; unnamed collateral reds; `R10` paraphrased as a prohibition; `ResumePlannerTest`/`ResumeIT` javadoc counts; the `decisions.md` self-links; `resume` claimed proven "against a real Docker runtime"; a strict `isBefore` on wall-clock timestamps; `HealthGate`'s exception-retry branch untested | All corrected. The strict timestamp assertion is now "no `LIFECYCLE` predates any `MATERIALIZE`", which is the actual property and survives equal microseconds; the retry branch has a test |

Two further mutations were run to prove the blocker fixes are guarded: restoring frontier-routing
→ **1 red**, `expected: <5> but was: <6>` materialize calls; removing the sweep's isolation →
**1 red**, the poisoned request's exception escaping `resume`.

### The stop half, added after review on the developer's direction

The reported contradiction was real but one-sided: `prepare(placement, generation)` is the seam's
only replica enumerator and `apply_has_generation` makes a stop's generation NULL, so a stop could
not learn what to address. Read as "the seam needs a generation-free enumerator" it is a seam
question; read as "the operator says what to stop" it is an addressing question, and the second
reading is the one that was taken.

| Decision | What it settles |
|---|---|
| **`BE-D25`** | The seam observes its replicas, generation-free: `List<TargetReplica> replicas(PlacementRef)`, an **eighth** operation. An *observation* beside `status` — read-only, no generation, no claim about fitness to apply, which stays in `prepare`. `prepare` is redefined as "`replicas` plus the per-apply validation", and the contract kit pins the two together, so an adapter whose `replicas` disagrees with its own `prepare` fails the kit. Rejected: a synthetic generation for stops (a lie the journal would hold), and a nullable generation on `prepare` (which makes the one operation whose purpose is per-apply re-validation sometimes mean something else) |
| **`BE-D26`** | A stop is **scope-granular** — one placement, or a whole instance — and instance-wide is admissible for a stop though not for an apply. Measured, not asserted: `BE-D23`'s limit is a property of the placement-scoped steps, and a stop journals none of them, so `UNIQUE (tenant_id, apply_id, step, replica_ref)` admits one `STOP` intent per replica across any number of placements under one `apply_id`. `resume(ScopeKey)` narrows the sweep the same way |

**Two things the code learned from the database rather than from a preference.**

1. `state_fits_operation` admits only `REQUESTED`/`RUNNING`/`STOPPED`/`STOP_FAILED` for a stop, so
   a stop addressing no known placement cannot land `FAILED_NO_EFFECT` — the apply path's answer
   for "nothing was asked of anything". It was refused **by name**, and the clause was corrected in
   the decision before code was built against it. `STOP_FAILED` is also the honest one: `STOPPED`
   would tell an operator the instance is down when SM never looked at anything.
2. `RuntimeSeamTypesTest`'s operation-set guard went **red** the moment `replicas` was added —
   *"an EIGHTH is a design change, not an implementation detail"* — and was changed only after
   `BE-D25` existed. That is the guard doing exactly the job it was written for.

**Obligation (r): five of six clauses asserted, the sixth partially proven and labelled.** The
stop **executes at the adapter** (a real `adapter.stop` per replica, liveness asserted); it **does
not queue** behind the apply — though this engine is single-threaded, so what is proven is that
neither admission nor execution waits, not that two threads interleave; the apply **is not
cancelled in place**, now asserted directly by a test that admits a stop and does **not** execute
it and requires the apply to reach `APPLIED` — which goes red if anyone reintroduces the row-poll;
the apply's next step **observes the stopped replica through `status` and its health gate**; it
**terminates through a normal failure path with per-replica evidence** (`CONFIG_DIVERGED`, with a
`LIFECYCLE` attempt row for the halted replica — one row on the gate route, two on the liveness
route, not "one per replica" as an earlier draft said); and **never a torn write** is *partially*
proven — every replica holds one whole generation after the halt, which follows from
stage-everywhere, but a stop landing **inside** `materializeConfig` is not exercised and that
atomicity is `ConfigFileStore`'s, proven separately.

Both halt routes are covered, and the split was measured rather than designed: the first test
expected the halt at replica 2's liveness check and got `gate.no.restart`, because the gate is
watching replica 1 at the moment the stop lands. §3.3 names both mechanisms, so both now have a
test.

**Prove-it-fires on the new paths — six mutations, all fired:**

| Mutation | Measured red |
|---|---|
| `BE-D23`'s instance-wide refusal applied to every operation | **3 red** — an instance-wide stop comes back `ScopeUnsupported` instead of `Landed` |
| `placementsOf` reads `placement_watermark` instead of the request history | **1 red** — `expected: <STOPPED> but was: <STOP_FAILED>`: the placement whose apply failed became invisible, which is the whole reason that table is the wrong enumerator |
| the apply no longer observes a replica that is not running | **1 red** — `expected: <CONFIG_DIVERGED> but was: <APPLY_INDETERMINATE>`: a stopped replica reads as an unreadable baseline, which is the conflation the check exists to prevent |
| `replicas()` returns a different set from `prepare()` | **1 red** in the contract kit, for every adapter that runs it |
| `resume(ScopeKey)` ignores its scope | **1 red** — `expected: <1> but was: <2>`: the other placement's in-flight stop was swept too |
| stop resume does not settle the attempt the crash left open | **1 red** — the request stays `RUNNING`, because `attempt_open_fence` refuses the next attempt |

### Second independent review (Fable), and what it changed

An adversarial review of `7c7a2b9` alone — the stop/resume/seam commit, the only one the first
round had not seen. **1 BLOCKER, 4 MAJOR, 7 MINOR.** Two of them are the same failure mode the
first round caught, arriving one layer further in.

| # | Finding | What changed |
|---|---|---|
| **B1** | **`BE-D26`'s central claim was built on half of `BE-D23`.** That decision has a *second* clause: replica-scoped steps are keyed by `replica_ref` alone, and `replica_ref` is adapter vocabulary, so two placements whose adapter yields the same label collapse onto one intent. `STOP` **is** replica-scoped. Docker's daemon-unique ids hide it; the SSH adapter's replica ref is the *configured container name*, so `cache-engine` on host A and host B is the ordinary shape — and the second placement's stop would be journalled as a retry of the first's, with both hashes null so not even an `IntentMismatch` to notice it by. `StopApplyIT` used `a-1`/`a-2`/`b-1`, so no test could reach it | The engine **enumerates every placement before acting** and refuses the whole request — `STOP_FAILED`, `stop.replica.label.collision`, nothing journalled and nothing stopped. Encoding the placement into `replica_ref` is what `BE-D23` forbids outright. `BE-D26` now carries the clause and cites `BE-D23`'s second half |
| **M1** | **A stop could be stranded in a state it cannot hold.** `state_fits_operation` gives a stop four states and `APPLY_INDETERMINATE` is not one, so `strand` threw *inside the method that exists to stop an exception escaping* — leaving the emergency lever `RUNNING`, `stop_inflight_fence` blocking every retry, and no operator exit. A regression this commit introduced, because resume had previously skipped non-`APPLY` rows | `strand` picks by operation: `STOP_FAILED` for a stop. And `attempts.open` is guarded inside `stopOne`, so a spent cap is one replica's problem rather than the request's |
| **M2** | **The unconditional "refuse a replica that is not running" check made `BE-D26`'s own workflow impossible** — stop the instance, then apply the new generation under `restart-all`: every replica is down at pre-flight, so the apply halted before issuing a verb and left the instance down. A policy taken in code and a test comment, in a commit claiming design-first | The apply captures each replica's liveness **at pre-flight**. Running-then-not-running is obligation (r) and halts; already down at pre-flight is the state the operator asked SM to apply from, so the verb starts it and the gate waits for *running and healthy* rather than a moved baseline. Recorded in `BE-D26` |
| **M3** | **Partial failure was misreported**, and `BE-D26`'s "what a stop reports" clause described code that does not exist: `LifecycleResult.issued` is discarded, an unenumerable placement gets no journal row at all, and the classifier was last-wins | Classifier is now **first**-wins, so it does not depend on iteration order. The decision text says what the journal actually holds, and states the two things it cannot: `issued` has no column, and a placement with no reachable replica cannot be journalled because a `STOP` intent must carry a real `replica_ref` |
| **M4** | **Obligation (r) was over-claimed again** — the same `L22` shape: "every clause asserted" was false for three of six. The torn-write clause was tested where a torn write is impossible by construction; "not cancelled in place" was a code-reading claim; "does not queue" is trivially true on one thread | "Not cancelled in place" is now **asserted**: a stop is admitted and deliberately *not* executed, and the apply must reach `APPLIED` — which goes red if anyone reintroduces the row-poll. The torn-write clause is labelled *partially proven* with the untested case named. "Does not queue" says what one thread can and cannot show |
| MINORs | `replicas()` threw where `ResumePlanner` says `CONTINUE`, so execute and resume disagreed about a placement whose containers are gone; `ResumePlanner`'s javadoc claimed a cell was unreachable that this commit made reachable; the kit pinned only the `Ready` case; `CONFIG_DIVERGED` on the first replica is narrower than §3.2's wording; two claims about attempt-row counts and scoped-resume overlap; `BE-D24`–`BE-D26` missing from the ratification matrix | All corrected. `replicas()` now returns an empty list rather than throwing, which is the same answer `ResumePlanner` gives `ABSENT`; §3.2's narrow wording is **flagged in `BE-D26` as a design-text observation**, not silently relied on |

**Six mutations on the fixes; four fired immediately, and two did not — recorded as findings about
the tests, not as passes**, then closed:

| Mutation | Measured |
|---|---|
| the label-collision check removed | **1 red** — `expected: <STOP_FAILED> but was: <STOPPED>`: the colliding stop silently succeeded on a wrong journal |
| `strand` always lands `APPLY_INDETERMINATE` | **1 red** — the stop is left `RUNNING` |
| the liveness halt is unconditional again | **1 red** — `stop, then restart-all` lands `CONFIG_DIVERGED` with the instance still down |
| `replicas()` disagrees with `prepare()` | **1 red** in the contract kit |
| `RetryCapExceeded` escapes `stopOne` | **silent at first.** The strand test used a hard enumeration failure, which the sweep's own catch absorbs, so the guard had no test. Replaced with one that spends a replica's cap on the journal and asserts the *other* replica still stops → now **1 red**, `resume.failed` instead of `stop.attempt.unavailable` |
| `replicas()` throws on an empty placement | **silent at first.** Every Docker fixture has replicas, so the empty case had no test. Added one → now **1 red** |

### The final gate, verbatim

```
$ TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify
[INFO] Tests run: 127, Failures: 0, Errors: 0, Skipped: 0      (mesh-events, surefire)
[INFO] Tests run: 236, Failures: 0, Errors: 0, Skipped: 1      (dp-core, surefire)
[INFO] Tests run: 303, Failures: 0, Errors: 0, Skipped: 4      (dp-core, failsafe)
[INFO] Tests run:  33, Failures: 0, Errors: 0, Skipped: 0      (arch-tests, surefire)
[INFO] BUILD SUCCESS

$ ./contracts/run-checks.sh --strict
== RESULT: ALL CHECKS PASSED

$ python3 -m unittest test_paths.py            # docs/design/backend/evidence/ddl-replay
Ran 11 tests in 0.002s
OK

$ python3 docs/design/backend/evidence/ddl-replay/replay.py --out <external dir>
  phase0: lines 347-384 md5 768e195b7c17861d97e402c5318be51b
  phase1: lines 385-734 md5 366c7d84c16425f967a2464a348d007e
  engine: PostgreSQL 14.24 …
254 SQL legs (162 refused, 92 ok) + 3 phase legs + 2 session legs
NO DRIFT: every leg matched its recorded status and signature.

$ git diff --check
(clean)
```

**Every skip, named.** One surefire skip: `InMemoryRuntimeAdapterContractTest`'s
`startOrUpdateNeverChangesTheArtifact`, whose `Assumptions.assumeTrue` fires because an in-memory
fake expresses no artifact identity — the rule is vacuous for it and says so. Four failsafe skips:
the two pre-existing broker ITs (`JcsmpEventPublisherIT`, `MeshLifecycleIT`, gated on
`-Dbroker.host`, untouched by this plan) and the two `CacheApplyWalkthroughIT` tests, gated on
`-Dsm.demo.cache=true`. The Cache walkthrough's gating is the subject of the `R10` paragraph in
`backend/demo/README.md`: it is explicitly **not** this plan's completion gate, and every mechanism
it exercises runs unconditionally against a stand-in container on every build.

The strict contract checks needed `npm install` in `contracts/view-models/` in a fresh worktree;
without it three checks report `FAIL (--strict): check did not run`, which is the harness refusing
to count an unrun check as a pass rather than a defect in anything this plan changed.

### G2 compliance over this branch's whole diff

Scope: `git diff 7d20d04..HEAD -- backend/` — this branch's own work. The range
`595d27b..7d20d04` is **mixed**, and an earlier draft of this sentence wrongly assigned all of it
to another stream: four of its twelve commits are plan 005's own (`5bea2dc`, `1eda3a8`, `2bc246f`
and `2c1d87c`, Tasks 1–8), one is a `contracts/` change (`25ee125`), and the remaining seven are
the Monday control-plane stream's, including the authenticated demo slice that carries its own
recorded waivers. The Tasks 1–8 commits were grepped at their own base on their own branch; the
control-plane commits are not this plan's to restate.

```
442: **A live, authenticated, browser-driven control demo cannot ship without G2 ratification or the
443: documented waiver.** Nothing here is a step toward one: there is no endpoint, no session, no
561:   # A bare broker is enough: the producer's @PostConstruct needs a session, and the consumer's
613:   -d '{"queueName":"orders-cache-q",…,"permission":"consume","accessType":"exclusive"}'
932:   // re-issuing a restart the operator may not have authorized twice is exactly the
1535:  // the operator may not have authorized twice is exactly the guess this state exists to
2006:  * "restart it again". Re-issuing a restart the operator may not have authorized twice is the
```

**Seven matches, all read** (`L12`: the count is not the evidence; the reviewed diff is). 442–443
is the demo README's own sentence *about* the gate. 561 is a JCSMP **messaging** session, not an
HTTP one. 613 is a Solace **queue** permission in a SEMP body, not an RBAC permission. 932, 1535
and 2006 are the same sentence of prose about a restart the operator may not have authorized twice
— §2.2 rule 3's reasoning, in three places. No endpoint, no `@RestController`, no authentication,
no session, no RBAC, no `sm_control`, no migration, no `contracts/` change.

### Watch items carried forward

| Item | Home |
|---|---|
| **`resume` has no production caller.** The path is implemented and proven; nothing invokes it on startup, because no application class exists and `G2` forbids writing one. | the plan that adds an application class |
| **`resume` re-runs pre-flight**, spending one attempt of the `PREFLIGHT` intent. Free by §2.2, but the cap is finite: at the default cap of 3 a request survives two resumes. | a future engine plan, or a cap dedicated to `PREFLIGHT` |
| ~~A `stop` request is not driven through the executor~~ — **closed** by `BE-D25`/`BE-D26`. Obligation (r) is proven. | — |
| **An instance-wide stop sees only placements SM has been asked to act on.** That is the complete set of placements SM has any relationship with, and there is no placements registry in v1 — but it means a placement enrolled and never applied to is invisible to a stop. | the plan that adds a placements table |
| **`stop_inflight_fence` is per *exact* scope**, so an instance-wide stop and a placement stop on the same instance can both be in flight and can address the same replica. Safe — `stop` is idempotent and the two hold separate `apply_id`s — but not narrowed. | a plan 004 change, with a migration |
| **`resume` is proven against the real journal but only against an in-memory adapter.** No Docker-backed test exercises the resume path. | a future engine plan |
| **`restart-all` issues the same call sequence as `rolling-no-validation`** in a single-threaded engine over a synchronous seam. What differs is the authorization, which is enforced and tested. A genuinely parallel restart-all is not built. | a future engine plan |
| **The v1 engine is single-placement** (`BE-D23`). `InstanceWideIdentityLimitIT` pins both halves of the identity limit against the shipped DDL and is **expected to go red** when a persistence plan grows `apply_step_intent` a placement. | a persistence plan |
| **The `I6` DEBUG-log residual**: the shaded Apache HttpClient inside `docker-java-transport-zerodep` logs request URIs at DEBUG, which carry container identifiers. Identifier-class sentinels are asserted absent from evidence values; their DEBUG-log appearance is pinned by its own test rather than suppressed. | recorded in the execution addendum |
| **The Docker-absent gate probe is inconclusive on this host** — `~/.testcontainers.properties` names a reachable socket, so breaking `DOCKER_HOST` does not exercise it. Recorded as a finding in the addendum, not as a pass. | unchanged from Tasks 1–8 |

### Design questions this execution raised, and did not answer

1. ~~**A `stop` cannot enumerate its replicas through the seam as designed.**~~ **ANSWERED** by
   `BE-D25` — the generation-free observation, which was the first of the three shapes this plan
   listed — and by `BE-D26`'s addressing. Recorded here as a question, decided by the developer,
   taken design-first, built and tested. The remaining half is the placements registry, which is a
   watch item above rather than an open question.
2. **Obligation (q) is sequential-only and that is now measured, not assumed.** The decision taken
   here is that the executor re-reads rather than that `ensureIntent` takes the scope lock; a
   future design that wants (q) to hold concurrently has to price the lock's cost across every step
   of every apply.

### The two facts this plan recorded and this execution corrected

Both concern the Solace Cache Engine and are written up in full in `backend/demo/README.md`:
`cache-engine.solace.enabled: false` does **not** yield a startable Cache, and the consumer's queue
bind is **not** asynchronous in practice. Lesson `L22` is what the first one bought.
