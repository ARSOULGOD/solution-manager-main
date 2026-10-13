# backend

The Solution Manager backend: a **control plane** (governance: identity, scoped RBAC, catalog, entitlement, hierarchy,
dashboards) and a **data plane** (config-of-record, the apply engine, health, logs, metrics collection and storage),
behind one HTTP API under `/api`. **Stack:** Java 17 / Spring Boot, PostgreSQL 14.

**Status (2026-10-09).** Plans 001 to 005 built the reactor, the event seam, the `sm_data` persistence and the apply
engine; plans 006 and 007 supplied the identity, authorization and HTTP substrate; plans 010 and 011 completed the served
surface; plans 008 (multi-placement apply and a placement-aware journal), 012 (undeclare, the two ungated restart strategies,
scheduled applies, the Operator's `instance.stop`) and 013 (target, hierarchy, entitlement, user and role administration
over HTTP) are built and merged on the integration branch (each plan's Results section is its completion record). The
production launcher serves **all 85 operations of [`contracts/openapi/console.yaml`](../contracts/openapi/console.yaml)** (84
counted served, one wired but not counted; 85 since the `OR-147` reconciliation added `UAT`'s seven), behind real authentication: PostgreSQL-backed local identity, sessions, CSRF,
forced credential rotation and a database-enforced audit log. The schemas stand at `sm_control` V001 to V027 (no V022/V023), `sm_data`
V001 to V046 (no V028 to V036) and `sm_metrics` V001 to V004, after the `OR-147` reconciliation into `UAT`, which removed the
old agent's migrations and renumbered plan 015's after the Connections ones. The throwaway demo launcher (`backend/demo-launcher`) is **deleted** (plan 011
P11-9.1, `OR-112` (4)); the frontend integrates against the production API, using the Postman collection while its own
screens are built. Plans 008, 012 and 013 are built and merged on this tree, with the schemas anchored at `50480d7f`. **What is not done** (the order is in [Not done yet](#not-done-yet-in-the-order-it-will-be-done)): the live
gates ran on 2026-10-09 (L1, L2, L4, L5 and X1's live apply PASSED; L3 is partial: `l3-rotate` passed, and `l3-edit` is not run: the cloud security group blocks the alt-`sshd` port; L2's first attempt found a defect in the SSH inspect of a container with no Docker healthcheck, fixed in `7ff85a42`), see the [live gate results](../docs/plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09). Still owed on the host: only L3's `l3-edit`; the `7ff85a42` fix is proven end to end there (a real Cache with no healthcheck was declared, probed and applied, 2026-10-09). The combined Fable
review of plans 008, 012 and 013 and its fix round have not happened; the agent gateway (plan 015, the `host-local` agent
path) is in progress: W1 to W4 are integrated on `integration/agent-gateway` (module, schema, the gateway service, telemetry, the operator verbs `issueAgentEnrolment`/`listAgents`/`getAgent`/`revokeAgent`/`drainAgent`, the scheduled jobs), the real-agent harness and the live gates G-A1/G-A2 are W5 and not yet run, so the path is **not claimed served** (runbooks, each `UNTESTED`: [`enable-agent-gateway`](../docs/runbooks/backend/enable-agent-gateway.md), [`restart-the-gateway-with-a-drain`](../docs/runbooks/backend/restart-the-gateway-with-a-drain.md), [`revoke-or-change-an-agent`](../docs/runbooks/backend/revoke-or-change-an-agent.md), [`release-the-images`](../docs/runbooks/backend/release-the-images.md)); the plain-VM adapter (plan 014) is **not built**; live browser notify is not built. The design is still
**PROPOSED, not a ratified baseline** (read
[`../docs/tasks/codex-review-triage.md`](../docs/tasks/codex-review-triage.md) before building on it), and several
decisions are owner rulings awaiting the architect group's ratification (`OR-69`): check the ratification matrix in
[`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md) for the status of any decision you rely on.

## What it serves today

Everything is reached through the console gateway, in front of `cp-core` and `dp-core`. The operation groups, as
the Postman collection and [`ENDPOINT-INVENTORY.md`](../contracts/openapi/ENDPOINT-INVENTORY.md) name them:

| Group | Operations (`operationId`) | Proved by |
|---|---|---|
| Sign-in (`/api/cp/auth`) | `login` (the body may name the `organization`, `OR-125` (4); omitted, it is the install's only organization, `OR-131` Q3), `getIdentity`, `rotateOwnCredential`, `logout`; the identity carries an `organizations` field (`OR-116`): the organizations and sub-organizations the caller can see, each with an id and its display `name` (`OR-120`), by the rule the hierarchy lists apply, and empty while `mustRotate` is true | `ProductionAuthenticationIT`, `IdentityOrganizationsTest`, `HierarchyEndpointsIT` |
| Home (`/api/cp/home`) | `getHomeSummary`, `getHomeAttention` | `HomeEndpointsIT` |
| Hierarchy (`/api/cp/hierarchy`) | `listOrganizations`, `listSubOrganizations`, `listEnvironments`; `createSubOrganization`, `createEnvironment`, `renameOrganization`, `renameSubOrganization`, `renameEnvironment` | `HierarchyEndpointsIT`, `HierarchyAdministrationEndpointIT` |
| Users and roles (`/api/cp/admin`) | `listUsers`, `createUser`, `resetUserCredential`, `deactivateUser`; `listRoles`, `listAssignments`, `grantAssignment`, `revokeAssignment` (plan 013 T3.2; a username is unique per organization, `OR-125` (4)) | `UserAdministrationEndpointIT`, `UserAdministrationControllerTest`, `RoleAssignmentControllerTest` |
| Entitlements (`/api/cp/entitlements`) | `listEntitlements`, `setEntitlement` | `HierarchyAdministrationEndpointIT` |
| Catalog | `listSolutions`, `loadPackage` (needs an organization entitlement, see below) | `CatalogEndpointsIT` |
| Targets | `listTargets`; registration and management: `scanHostKeys`, `registerTarget`, `getTargetConnection`, `editTargetConnection`, `rotateTargetCredential`, `testTargetConnection`, `retireTarget` | `TargetEndpointsIT`, `TargetRegistrationEndpointIT`, `TargetManagementEndpointIT` |
| Instances | `listInstances`, `declareInstance`, `getInstance`; `addPlacement`, `retirePlacement` (plan 008: an instance may have more than one placement); `undeclareInstance` (plan 012 T5: releases an instance from management, never touches its host) | `InstanceEndpointsIT`, `AddPlacementEndpointIT`, `MultiPlacementEndToEndIT`, `UndeclareEndpointIT` |
| Designer and config | `getDesigner`, `stageConfig`, `getConfigHistory`, `setConfigSecret`, `acceptRuntimeConfig`, `restoreRuntimeConfig` | `DesignerEndpointsIT` and siblings |
| Apply | `submitApply`, `observeApply`, `rollbackApply`, `stopInstance`; `listApplySchedules`, `cancelApplySchedule` | `ApplyEndpointsIT` and siblings, `ApplyScheduleEndpointsIT` |
| Fleet apply | `submitFleetApply`, `observeFleetApply` | `FleetEndpointsIT` |
| Governance | `getHealth`, `getConfigLadder`, `readLogs`, `getLogLevel`, `setLogLevel` | `HealthEndpointsIT`, `LogsEndpointsIT`, `LogLevelEndpointsIT` |
| Monitoring (`/api/console/v1`) | `getCapabilities`, `listEntityTypes`, `listMetricInstances`, `getMetricsCatalog`, `getLabelValues`, `queryRange`, `queryInstant`, `getDashboard`, `saveWidget`, `setDashboardLayout`, `setWidgetHidden`, `resetDashboard`; `declareMetricType` is a contracted `501`; `exportRange` is wired and tested but **not counted served** until plan 011 P11-8.1 records the large export stream | `MetricsEndpointsIT`, `DashboardEndpointsIT`, `ExportEndpointIT` |

**What an apply reaches: Docker over SSH, and only that (`OR-111`).** The one runtime adapter built is
`SshDockerRuntimeAdapter` (`dp-core`, `com.solace.sm.dp.apply.runtime.ssh`), wired by `ProductionApplyConfiguration`: SM
connects to a registered target over SSH (a pinned host key, a sealed private key), writes the managed integration's
external configuration file, and drives the host's `docker` CLI to restart and inspect the container. A package's health
probe, log reads, log levels, config read-back, metrics collection and the `restart-observed` rung are all done through
that session. The local `DockerAdapter` (`com.solace.sm.dp.apply.runtime.docker`, over the Docker Engine API) is **not**
wired into the launcher: it exists for the contract tests and the Cache walkthrough. There is no plain-VM adapter, no
Kubernetes adapter and no `host-local` agent path yet (see [Not done yet](#not-done-yet-in-the-order-it-will-be-done)).

**More than one placement per instance (plan 008, `BE-D44` to `BE-D48`, `BE-D53`).** An active instance can be given a second
placement with `addPlacement` (a second container, on another target or on the same target with its own container name,
directory and ports) and lose one with `retirePlacement?workload=leave-running`; SM deploys and removes nothing, the
customer's CI/CD does (`OR-29`, `I1`). What follows from it, as built: the apply journal identifies a step by its placement
as well as its replica, so two placements whose containers share a name no longer collide (`sm_data` V021); an
instance-wide `stopInstance` addresses the instance's **live** placements from the registry, one `STOP` step per placement
with its own `placementId`, and a retired placement is not stopped (the history enumerator is gone); the database refuses an
`apply_request` naming a placement the registry does not hold for that instance (V024); a new placement starts unconverged
(the configuration ladder reads `rendered` until a fleet rolls the current generation to it); an instance whose package
declares push (`solace_event`) metrics is refused a second placement `409`, because a push reading names no placement.
A rollout across placements is a fleet with one member per placement; a solution that must not run two configurations rolls
`manual` (see the [runbook](../docs/runbooks/operations.md#roll-a-generation-across-an-instances-placements)). Who added or
retired a placement is recorded (`placement.created_by`, `placement.retired_by`; `sm_data` V026, `OR-125` (1)); a placement added
before V026 has no recorded actor. `MultiPlacementEndToEndIT` (11 tests) ran green against `postgres:14` in the wave-5 build; `MultiPlacementLiveIT` against a second Cache on the E2E host is live gate L2, which PASSED on 2026-10-09 (second attempt; [live gate results](../docs/plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09)). Procedures:
[give an instance a second placement](../docs/runbooks/operations.md#give-an-instance-a-second-placement) and
[retire one](../docs/runbooks/operations.md#retire-one-placement-of-an-instance).

**Undeclare, ungated restarts and scheduled applies (plan 012, `BE-D49` to `BE-D53`).** The catalog stood at **43 verbs** (Administrator 43,
Operator 24, Viewer 8) after plan 012 (it is **51** since the `OR-147` reconciliation: plan 013's `connection.*` verbs, `sm_control` `V021`, and plan 015 T1.3's agent verbs, `V026`: Administrator 51, Operator 27, Viewer 10): plan 012 added `instance.undeclare` and `instance.restart.overrideSafety` (Administrator and Operator) and
gave the Operator `instance.stop` (`OR-121`, `OR-122`). What is served:

- **`undeclareInstance`** (`DELETE /api/dp/instances/{id}?workload=leave-running`) takes an instance out of SM's inventory and
  management and **never touches the host**: the container keeps running, with its config file and SM's `.sm-preimage-*` and
  `.sm-fp-*` files beside it. It is refused `409` while an apply, stop or fleet is in flight on the instance, while an
  `APPLY_INDETERMINATE` request is unresolved, and while a `PENDING` apply schedule names the instance (`OR-131` Q2: cancel the
  schedules first). Nothing is deleted. Procedure, host cleanup and known limits:
  [undeclare an instance](../docs/runbooks/operations.md#undeclare-an-instance). Its live gate L5 PASSED on 2026-10-09 ([live gate results](../docs/plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09)).
- **`restart-all` and `rolling-no-validation`** are served on `submitApply`, `rollbackApply`, `restoreRuntimeConfig` and
  `submitFleetApply`. Each needs `instance.restart.overrideSafety` beside `instance.restart`; `restart-all` needs `outageConfirmedBy`
  (any non-blank value; the server records the session principal, never the string). Across one instance's placements a `rolling-*`
  fleet batch may hold only one placement of any instance (rule K-2, `422 fleet.batch.instance.repeated`), and a
  `rolling-no-validation` fleet waits `sm.dp.fleet.no-validation.settle` (default 30 s) between batches. Test them in a development
  environment first (`OR-53`); see [restart without a health gate](../docs/runbooks/operations.md#restart-without-a-health-gate-restart-all-and-rolling-no-validation).
  Live gate L1 PASSED on 2026-10-09 ([live gate results](../docs/plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09)).
- **Scheduling** is a `schedule` window on `submitApply` and `rollbackApply`, not a strategy: `strategy: "scheduled"` answers `422`.
  The answer is `202` with a `scheduleId` and no `applyId`; `listApplySchedules` and `cancelApplySchedule` complete it; see
  [schedule an apply](../docs/runbooks/operations.md#schedule-an-apply-or-a-rollback). Fleets cannot be scheduled.

**Operations that answer `501 NOT_IMPLEMENTED` today:** `declareMetricType`; `setLogLevel` with `mode: live`. Everything else
the contract names is served. A route that is not mapped is `404`.

## Module map

One Maven reactor, `backend/pom.xml`. Order matters: `arch-tests` is last because it consumes every other module.

| Module | Holds | Depends on |
|---|---|---|
| `cp-api` | The ratified 47-verb permission catalog (Administrator 47, Operator 26, Viewer 9) and the operation registry: the Control Plane's published types | nothing |
| `dp-api` | Data Plane service interfaces and value types (the ports) | nothing |
| `mesh-events` | The v1 mesh event contract's Java bindings (envelope, topic derivation), the `EventPublisher` seam and `AfterCommitEventPublisher` (write-then-notify ordering, enforced at the seam) | nothing |
| `cp-core` | Control Plane implementation: identity and sessions, scoped authorization (`authorizationService.require`), hierarchy, catalog and entitlement, targets, dashboards, audit. Owns `sm_control` (Flyway `V001`-`V021`, Phase 0 bootstrap SQL) and its operator commands | `cp-api`, `mesh-events` |
| `dp-core` | Data Plane implementation: the apply engine and journal, runtime adapters, inventory, secret store, health poller, logs, fleet apply, the metrics store and collectors, and the JCSMP mesh bridge (the only place JCSMP may appear). Owns `sm_data` (`V001`-`V029`), `sm_secret` and `sm_metrics` (`V001`-`V003`) and their operator commands | `dp-api`, `mesh-events` |
| `console-gateway` | The Spring MVC HTTP surface under `/api`: authentication, Origin and CSRF checks, the `@ConsoleOperation` handlers, the Problem error model. Holds **no datasource** | `cp-api`, `dp-api` |
| `launcher` | The production composition root, `SolutionManagerApplication`. Owns no business service, datasource or migration; refuses to start unless the deployment declares how clients reach it | `cp-core`, `dp-core`, `console-gateway` |
| `arch-tests` | ArchUnit rules and their planted-violation proofs (see [Guards](#guards)) | every module |

`backend/demo-launcher` and the demo's `demo_control` schema **no longer exist**. An operator who ran the demo drops the
leftover schema with the procedure in [`../docs/runbooks/operations.md`](../docs/runbooks/operations.md).
`backend/spikes/mesh-authz` and `backend/spikes/mesh-scope-acl` are the broker authorization spikes (G1) and sit outside
the reactor. `backend/demo/` is the Cache walkthrough environment (a real Cache container for
`CacheApplyWalkthroughIT`); it is not an application.

## Build and test

**Toolchain.** JDK **17** (`java.version` in `pom.xml`) and Maven 3.9.x. `./mvnw` in this directory pins the Maven
version; plain `mvn` builds the reactor too. Docker is needed for the integration tests (Testcontainers starts
`postgres:14`). Use `-o` (offline) once `~/.m2` is warm.

```bash
# Targeted: one module and what it needs. This is the everyday command.
mvn -o -f backend/pom.xml -pl dp-core -am verify

# One unit test and one integration test class; the failIfNoSpecifiedTests flags let the -am modules run nothing.
mvn -o -f backend/pom.xml -pl launcher -am verify -Dtest=SomeUnitTest -Dit.test=HealthEndpointsIT \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false

# The boundary rules and everything they consume
mvn -o -f backend/pom.xml -pl arch-tests -am verify

# The whole reactor (about 40 minutes with Docker; the gate plans 010 and 011 closed on)
mvn -o -f backend/pom.xml clean verify
```

`verify` runs surefire (unit tests, `*Test`) and failsafe (integration tests, `*IT`), then the dependency licence
gate (below). A stale `failsafe-summary.xml` left under a module's `target/` by an earlier run can mislead a later one:
find and delete it (`find backend -name failsafe-summary.xml -delete`) before an IT run you intend to read.

**Testcontainers prerequisites.**

- Each persistence IT JVM starts **one** `postgres:14` container and runs the checked-in two-phase bootstrap against it:
  Phase 0 (`cp-core/.../db/bootstrap/sm_control_phase0.sql`, `dp-core/.../db/bootstrap/sm_data_phase0.sql`) as the
  container's bootstrap superuser, Phase 1 (Flyway) as the migrator roles, then every query as the runtime roles.
  Launcher-level ITs boot the real `SolutionManagerApplication` over both databases.
- **Without Docker the build fails, on purpose.** `DockerAvailabilityPreflightIT` always runs and fails loudly when
  Docker is unreachable; the persistence ITs alone would be skipped, and a skip is not a failure to failsafe, so a
  Docker-less run used to end `BUILD SUCCESS` with 142 tests skipped.
- The Docker-adapter ITs write bind-mounted directories under `dp-core/target/it-config/`, not `@TempDir`: on Colima a
  directory under macOS's `java.io.tmpdir` mounts but host writes do not propagate.
- On a host whose `~/.testcontainers.properties` points `docker.host` at Colima while `/var/run/docker.sock` is Docker
  Desktop's, Ryuk fails to start. A process-local override avoids editing the properties file:
  `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (plus `DOCKER_HOST` and
  `TESTCONTAINERS_DOCKER_CLIENT_STRATEGY` if your setup needs them). Never prune or stop containers you did not start.

**Opt-in live integration tests.** These are skipped, visibly, unless their property is set. No real host, broker or
credential is ever committed: you supply the target through your own environment, and a password is read from the
environment only, never from a `-D` property.

| Test | Enabled by | What it needs |
|---|---|---|
| `JcsmpEventPublisherIT`, `MeshLifecycleIT`, `ApplyOutcomePublishIT` (`dp-core`), the broker case of `CatalogEventIT` (`launcher`) | `-Dbroker.host=<host>` | the G1 spike broker (`backend/spikes/mesh-authz/`) reachable at that host |
| `CacheApplyWalkthroughIT` (`dp-core`) | `-Dsm.demo.cache=true` | the Cache walkthrough environment in [`demo/README.md`](demo/README.md) (a sibling repository must be built) |
| `SkeletonWalkthroughIT`, `MeshResilienceIT`, `MetricsCollectionLiveIT`, `MultiPlacementLiveIT` (`launcher`) | `-Dsm.walkthrough.ssh.host=<host>` plus the other `sm.walkthrough.*` target properties | an operator-provided SSH Docker target running a Cache. **They write to that host** (overwrite its config file, restart its container). `MultiPlacementLiveIT` (plan 008 live gate L2) also needs a **second Cache container already running** and the `sm.walkthrough.multi.second.*` properties, and stops and restarts containers only with `sm.walkthrough.multi.disruptive=yes` (the script needs `SM_E2E_CONFIRM_WRITE=yes`, and `SM_E2E_CONFIRM_DISRUPT=yes` for the disruptive run; `OR-110` authorises both on the E2E host). **Only a disruptive pass satisfies L2.** Use `launcher/scripts/run-e2e-walkthrough.sh --check` (and read its header), `launcher/scripts/run-e2e-metrics-collection.sh` and `launcher/scripts/run-e2e-multi-placement.sh` rather than assembling the properties yourself; the evidence format is in [`../docs/plans/backend/evidence/010-first-e2e-walkthrough.md`](../docs/plans/backend/evidence/010-first-e2e-walkthrough.md) |
| `MetricsStorageBenchmarkIT` (`dp-core`), `MetricsGateBenchmarkIT` (`launcher`) | `-Dsm.bench.url=<jdbc location>`, `-Dsm.bench.gate.url=<jdbc location>` | a PostgreSQL 14 to load; driven by `launcher/scripts/run-storage-gate.sh` (its header lists every variable). The evidence file [`../docs/design/backend/evidence/011-storage-gate.md`](../docs/design/backend/evidence/011-storage-gate.md) is the filled record of the run on the reference host (2026-10-06/07): **one week of S-A measured** (`OR-112` (3)); the month and six-month figures are **extrapolated** and labelled so; **`REQ-007` is NOT MET at the measured gate topology** (six months of raw is about 88.5 GB extrapolated against about 18 GB free on the host); Step 6b (the 105 M-row export) and Steps 6c and 6d were **not measured**; the application side of Step 3 ran across an SSH port-forward, so its latencies carry the tunnel |

The `*RehearsalIT` classes (including `MultiPlacementRehearsalIT`) run the same scenarios against a local broker image and a synthetic host; they need no
real target.

**Last recorded full gate** (plan 010 Results section 9, the Fable fix round, 2026-10-07; recorded before the demo-launcher deletion,
the `OR-112` items (1), (2), (4) and (6) and `OR-116`, and **no full reactor build is recorded at this head**: plan 011 P11-10.1 flag 14.
The figures drift; regenerate rather than trust them): `mvn clean verify` BUILD SUCCESS, surefire / failsafe per module: cp-api 48; mesh-events 160; cp-core 466 / 287;
dp-core 1086 / 1140 (1 + 12 skipped, all opt-in above); console-gateway 601; launcher 77 / 289 (27 skipped, opt-in);
arch-tests 76.

**Later full build (2026-10-08).** The combined W3 and W4 full build of plans 008, 012 and 013 on `postgres:14` is recorded in the
[combined schedule](../docs/plans/backend/combined-008-012-013.md#w3w4-integration-2026-10-08): it ran with
`-Dmaven.test.failure.ignore=true`, so it is **not** a green build. Every module passed except two broker-reconnect classes
(`JcsmpSampleSubscriberIT`, `MeshResilienceRehearsalIT`), which passed when rerun alone and are classified as timing flakes on
the strength of those reruns and of no mesh change, not of a root-cause analysis. No full-reactor build of the wave 5 and wave 6
tree is recorded in the repository yet; the final gate (`mvn -o -f backend/pom.xml clean verify`, `./contracts/run-checks.sh
--strict`) is the integrator's, before the push.

## Running the production launcher locally

The launcher is a production composition: it needs PostgreSQL, **two migration steps** and a master key, and it refuses
to start without them. There is no docker-compose for it and no complete install runbook yet
([`../docs/runbooks/install-prerequisites.md`](../docs/runbooks/install-prerequisites.md) covers the migration step,
capacity and the single-replica rule). The recipe below is the one
[`../contracts/openapi/postman/README.md`](../contracts/openapi/postman/README.md) smoke-tested, and it was re-run against
this tree on 2026-10-07: migration, entitlement, sign-in, forced rotation and a hierarchy read all answered as documented.
Run it from the repository root. The heap must be at least 2 GiB (Argon2id login hashing).

```bash
# 1. Build the launcher (one jar with every module in it) and unpack its classpath.
mvn -o -f backend/pom.xml -pl launcher -am -DskipTests -DskipITs package
mkdir -p /tmp/sm-launcher && unzip -q -o backend/launcher/target/launcher-*-exec.jar 'BOOT-INF/*' -d /tmp/sm-launcher
CP='/tmp/sm-launcher/BOOT-INF/classes:/tmp/sm-launcher/BOOT-INF/lib/*'

# 2. A throwaway PostgreSQL 14; note the host port Docker chose.
docker run -d --name sm-pg -e POSTGRES_PASSWORD=smoke -e POSTGRES_DB=sm -p 127.0.0.1::5432 postgres:14
docker port sm-pg 5432/tcp                       # e.g. 127.0.0.1:49239
PORT=<that port>

# 3. Phase 0, once, as the bootstrap superuser: creates the roles and the sm_control, sm_data, sm_secret and sm_metrics
#    schemas. Then give each of the four roles a password of your choosing.
docker exec -i sm-pg psql -v ON_ERROR_STOP=1 -U postgres -d sm < backend/cp-core/src/main/resources/db/bootstrap/sm_control_phase0.sql
docker exec -i sm-pg psql -v ON_ERROR_STOP=1 -U postgres -d sm < backend/dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql
for r in sm_cp_migrator sm_cp_runtime sm_dp_migrator sm_dp_runtime; do
  docker exec sm-pg psql -U postgres -d sm -c "ALTER ROLE $r PASSWORD '<choose a password for $r>'"; done

# 4. The bootstrap administrator's credential. The database stores only an Argon2id hash, and there is no CLI for it:
#    hash the secret you chose with the application's own hasher. Keep the single quotes when you use the result,
#    because the hash contains '$'.
BOOT_HASH="$(printf 'System.out.println(new com.solace.sm.cp.identity.CredentialHasher().hash("<your bootstrap secret>".toCharArray()));\n/exit\n' \
  | jshell -R-Xmx2g --class-path "$CP" -s 2>/dev/null | grep '^{argon2id}')"

# 5. The two one-shot migration steps (environment-only configuration; the migrator credentials never reach the application).
SM_CONTROL_MIGRATOR_URL=jdbc:postgresql://127.0.0.1:$PORT/sm SM_CONTROL_MIGRATOR_USERNAME=sm_cp_migrator \
SM_CONTROL_MIGRATOR_PASSWORD='<sm_cp_migrator password>' SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH="$BOOT_HASH" \
  java -cp "$CP" com.solace.sm.cp.persistence.SmControlMigrationMain          # prints: migrated 21 (versions …)
SM_DATA_MIGRATOR_URL=jdbc:postgresql://127.0.0.1:$PORT/sm SM_DATA_MIGRATOR_USERNAME=sm_dp_migrator \
SM_DATA_MIGRATOR_PASSWORD='<sm_dp_migrator password>' \
  java -cp "$CP" com.solace.sm.dp.apply.persistence.SmDataMigrationMain       # prints: sm_data: migrated 29 (versions …) / sm_metrics: migrated 3 (versions …)

# 6. Entitle the install's organization to the solution you will load (see "Operator commands"; once the launcher is up
#    `setEntitlement` does the same as the bootstrap administrator). A fresh install has
#    one organization, id 00000000-0000-4000-8000-000000000011; the Cache package's solution name is "cache".
SM_CONTROL_RUNTIME_URL=jdbc:postgresql://127.0.0.1:$PORT/sm SM_CONTROL_RUNTIME_USERNAME=sm_cp_runtime \
SM_CONTROL_RUNTIME_PASSWORD='<sm_cp_runtime password>' \
SM_CONTROL_ENTITLEMENT_ORGANIZATION_ID=00000000-0000-4000-8000-000000000011 SM_CONTROL_ENTITLEMENT_SOLUTION=cache \
SM_CONTROL_ENTITLEMENT_MAX_INSTANCES=5 SM_CONTROL_ENTITLEMENT_SET_BY=bootstrap-admin \
  java -cp "$CP" com.solace.sm.cp.persistence.EntitlementMain

# 7. The launcher: runtime credentials only, plus the master key (from the environment, never committed).
SM_CONTROL_RUNTIME_URL=jdbc:postgresql://127.0.0.1:$PORT/sm SM_CONTROL_RUNTIME_USERNAME=sm_cp_runtime SM_CONTROL_RUNTIME_PASSWORD='<...>' \
SM_DATA_RUNTIME_URL=jdbc:postgresql://127.0.0.1:$PORT/sm     SM_DATA_RUNTIME_USERNAME=sm_dp_runtime     SM_DATA_RUNTIME_PASSWORD='<...>' \
SM_GATEWAY_TRUSTED_ORIGINS_0=http://localhost:8080 SM_GATEWAY_DIRECT_CLIENT_CONNECTIONS=true \
SM_DP_MASTER_KEY="$(head -c 32 /dev/urandom | base64)" SM_DP_MASTER_KEY_ID=local-1 \
  java -Xmx2g -cp "$CP" com.solace.sm.launcher.SolutionManagerApplication
```

Notes that matter:

- **Sign in as `bootstrap-admin`** to the install's organization (`organization` in the login body is optional on a fresh install, which holds one: its id, `00000000-0000-4000-8000-000000000011`, or its name, which is the same text until the organization is renamed) with the secret you hashed in step 4. A username is unique per organization (`OR-125` (4)); with several organizations a login names both. The credential is single-use: the first login
  answers `mustRotate: true` and every route except `getIdentity`, `rotateOwnCredential` and `logout` answers
  `428 MUST_ROTATE` until you call `rotateOwnCredential` (minimum 12 characters). The Postman collection's folder
  `0. Start here` does exactly this. With `curl`, send `Origin: http://localhost:8080`, keep the cookie jar, and send
  `X-SM-CSRF` (the `__Host-SM_CSRF` value) on every state-changing request.
- The launcher listens on **8080** (no `server.port` is set). `sm.gateway.trusted-origins` has no default and startup
  fails without it; exactly one of `sm.gateway.trusted-proxies` or `sm.gateway.direct-client-connections=true` must be
  set (direct is right for a local run).
  A listed proxy must **set** (overwrite) `X-Forwarded-Proto` to its own listener's scheme: one that passes a client's value
  through lets that client assert `https` and carry a private key in clear. The launcher pins
  `server.forward-headers-strategy=none` so the servlet container never rewrites the scheme or the peer address itself, and
  refuses to start if an overlay changes it (`ForwardedHeadersLockdown`).
- The launcher serves **only** `/api/**` and refuses to start if static content is on its classpath. The console is
  served by a separate same-origin front server that proxies `/api`.
- The mesh is off by default (`sm.mesh.enabled=false`, one `WARN` at boot): REST works without a broker.
- Run **exactly one** replica per install; a second process refuses to start (`WorkerSingletonGuard`).
- **Scheduled applies** (plan 012 T7): `schedule: {notBefore, notAfter}` on `submitApply` / `rollbackApply`. The gateway's
  `ScheduleFirer` polls every `sm.gateway.schedule.poll-interval` (default 15 s; `sm.gateway.schedule.enabled=false` withdraws
  it, and every schedule then stays `PENDING` until its window closes and it is `MISSED`). `sm.dp.apply.schedule.max-lead`
  (default 7 days, and **7 days is the ceiling** per `OR-122` (2): a larger value refuses to boot) bounds how far ahead both
  `notBefore` and `notAfter` may be; `sm.dp.apply.schedule.due-batch` (default 100) bounds one pass's read
  per organization. A schedule is checked for its submitter's verbs again when it fires.
- On a fresh database there are no targets: `listTargets` is empty and most of the instance surface answers `404`
  until an administrator registers one through the API (`scanHostKeys`, `registerTarget`; the Postman `Setup` folder, or
  `backend/local/sm-local.sh`, which walks it) or an operator registers one with the commands below. `loadPackage` answers
  `409 ENTITLEMENT_REFUSED` until the organization is entitled: step 6, or `setEntitlement` as the bootstrap administrator.
- Credential-bearing writes (`registerTarget`, `rotateTargetCredential`, `createUser`, `resetUserCredential`) are refused
  `403 INSECURE_TRANSPORT` unless the connection is secure, from loopback, or from a trusted proxy reporting `https`
  ([runbook](../docs/runbooks/operations.md#behind-a-tls-terminating-proxy-insecure_transport)).
- Clean up with `docker rm -f -v sm-pg`.

### Operator commands

Anything the API cannot do yet is a standalone `main` class that reads its configuration from the **environment** (never
the command line), starts no Spring context, and never prints a credential, URL or exception text. Run them like the
migration steps, with the launcher classpath; the variable names are constants in each class. All live under
`com.solace.sm.` in `cp-core` or `dp-core`.

| Class | What it does | Runbook or note |
|---|---|---|
| `cp.persistence.SmControlMigrationMain` | `sm_control` Phase 1, with the bootstrap credential hash | install |
| `dp.apply.persistence.SmDataMigrationMain` | `sm_data` then `sm_metrics` Phase 1 | [`install-prerequisites.md`](../docs/runbooks/install-prerequisites.md) |
| `cp.persistence.EntitlementMain` | sets one organization's entitlement to one solution (`OR-48`); `setEntitlement` does the same over HTTP (plan 013 T2.3, `OR-122` (3)), so this is for an install with no administrator able to sign in, or an organization other than the bootstrap administrator's own | needed before `loadPackage` unless `setEntitlement` is used |
| `cp.persistence.TargetRegistrationMain` and `dp.apply.persistence.TargetConnectionMain` | register a target in both planes: the CP node (it signs in as an operator-chosen principal: `SM_TARGET_ORGANIZATION` names the organization, by id or name, and the terminal prompts for the username and credential), then host, port, SSH user, pinned host key and a private-key **file path** sealed in the secret store | install and emergency use: registration is served over the API (`scanHostKeys`, `registerTarget`; [runbook](../docs/runbooks/operations.md#register-a-target-ssh-host-through-the-api)); these write rows and never connect |
| `dp.apply.persistence.BrokerConnectionMain` | registers one broker connection per environment, for push metrics | [`register-a-broker-connection.md`](../docs/runbooks/register-a-broker-connection.md) |
| `dp.apply.persistence.MetricsPartitionMain` | `status`, `freeze`, `rollup`, `detach`, `archive`, `drop` over the monthly raw partitions | [`install-prerequisites.md`](../docs/runbooks/install-prerequisites.md) |
| `dp.apply.persistence.KeyRotationMain` | re-wraps data keys from the previous master key to the current one | `OR-85` |
| `cp.persistence.OrganizationRenameMain` | renames **any** organization (over HTTP only an install-bound Administrator can, and only its own organization: `OR-125` (3), `OR-131` Q1); one audited `HIERARCHY_NODE_RENAMED` row with no actor (client address `operator-cli`, `sm_control` V020) | [`operations.md`](../docs/runbooks/operations.md#rename-an-organization-operator-command) |
| `cp.persistence.SmControlLockoutRecoveryMain` | clears one login-throttle lockout (`OR-20`); `SM_CONTROL_LOCKOUT_USERNAME` also needs `SM_CONTROL_LOCKOUT_ORGANIZATION`, since a username is unique per organization (`OR-125` (4)) | audited |

## The API contract and the Postman collection

| What | Where |
|---|---|
| The contract (OpenAPI), single source of truth | [`../contracts/openapi/console.yaml`](../contracts/openapi/console.yaml) |
| What is served, wired, contracted, `501`; the frontend deployment shape | [`../contracts/openapi/ENDPOINT-INVENTORY.md`](../contracts/openapi/ENDPOINT-INVENTORY.md) |
| Postman collection and local environment (generated, never edited by hand) | [`../contracts/openapi/postman/`](../contracts/openapi/postman/), start at its [README](../contracts/openapi/postman/README.md) |
| Design of the console API | [`../docs/design/common/contracts/console-integration.md`](../docs/design/common/contracts/console-integration.md) |

After a change to `console.yaml`, the inventory, a `@ConsoleOperation` handler or the package fixture, run
`python3 contracts/openapi/postman/generate-postman.py`, then `python3 contracts/openapi/postman/check-postman.py`.
The credentials in the Postman environment are blank placeholders; no real value is committed.

## Guards

The only automated runner is `.github/workflows/migration-freeze.yml`, which reports the migration freeze and cannot
block a merge on this repository plan. Everything else runs locally, and each guard has a self-test that plants a
violation.

| Guard | Run | Enforces |
|---|---|---|
| `arch-tests` | `mvn -o -f backend/pom.xml -pl arch-tests -am verify` | the `I7` plane boundary (cp and dp may not import each other), JDBC confined to the persistence packages, JCSMP confined to `dp.meshbridge`, `docker-java` confined to its adapter package, the gateway holds no datasource and no runtime adapter, the publisher and adapter inventories (an unlisted implementation fails), monitoring's fixed-port and tenant-enumeration rules, and a single production wiring. Each rule has a planted-violation class (`Bad*Class`) proving it fires |
| `contracts/run-checks.sh` | `./contracts/run-checks.sh --strict` | step 10 the event contract; step 11 every `console.yaml` operation maps to a ratified catalog verb and a scope; step 12 frozen migrations; steps 13 and 13b the build-plugin licence check and its self-test; **step 14** the package schema compiles under the frontend's own Ajv (needs `frontend/node_modules`, or `SM_FRONTEND_NODE_MODULES` pointing at one); **step 15** the Postman collection matches `console.yaml` and the inventory. Steps 7 to 9 need `contracts/view-models/node_modules`. Under `--strict` a check that cannot run is a failure |
| Frozen migrations | `python3 backend/scripts/check-frozen-migrations.py` | every published `sm_control`, `sm_data` and `sm_metrics` migration and its `CHECKSUMS.sha256` line is immutable (`OR-22`, `OR-105` (e)). A new migration takes the next free number, and is anchored by appending its publishing commit's SHA to `scripts/frozen-migration-anchors.txt` after the push |
| Licence gate | `verify` (Maven) and `python3 backend/scripts/check-licences.py` | `REQ-014` / `I8`, below |
| Self-tests | `backend/scripts/test-check-frozen-migrations.sh`, `backend/scripts/test-check-licences.sh` | the two script guards fire on a planted violation |

## Not done yet, in the order it will be done

The order is the owner's (`OR-114`, `OR-115`); each new piece follows the repo's workflow (design, plan, owner approval,
execution).

1. **What the live gates left owed.** The live gates ran on 2026-10-09 against `63eea35a` ([live gate results](../docs/plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09)): L1 (plan 012 T2), L2 (plan 008 S5, second attempt), L4 (plan 013 MF-R2), L5 (plan 012 T5) and X1's live apply PASSED, and L3 is partial. L2's first attempt found a product defect, now fixed in `7ff85a42`: `docker inspect` over SSH failed for a container with no Docker healthcheck, so such a Cache could not be declared, probed or applied. Still owed: **L3's `l3-edit`** (plan 013 T1.5 S2, "the next probe and apply use the edited record"), which is blocked: the cloud security group lets through only 22, 8082 and 8083, and moving the second `sshd` listener was refused by the permission check; the owner opens a security-group port to the stack's source address, or allows an alt `sshd` on an open port (combined schedule §8 row 9); `l3-rotate` passed. The `7ff85a42` inspect fix is **proven end to end on the host** (combined schedule §8 row 10, PASS 2026-10-09). The gate runs rebuilt the local stack from `63eea35a` with `sm-local.sh`, but no gate result records a run of the Postman `Setup` folder against a live stack, so that remains owed as plan 013 T4.1 left it (it verified its client against a mock of the API and `check` mode only). **X1**, the explicit mode on the Docker config file and its pre-images (plan 014 Q-V13 (b)), is built and its live apply passed: SM writes them `0640` (born `0640`, an "other" bit refused as `ssh.config.mode.too.open`).
2. **The combined review and the push.** The combined Fable review of plans 008, 012 and 013 and X1, one fix round, the full gate
   (`mvn -o -f backend/pom.xml clean verify`, `./contracts/run-checks.sh --strict`, the frozen-migration check, the DDL replay) and
   the push to `integration/dp-remote-demo`; then `OR-115`'s pull request into a new `dev` branch.
3. **Operations still answering `501`:** `setLogLevel` with `mode: live` and `declareMetricType`. Undeclare (plan 012 T5), the
   user and role-binding routes (plan 013 T3.2), and target, SSH-key, host-key, hierarchy and entitlement administration (plan 013
   T1.4, T1.5, T2.3) are served through the API; the operator commands above remain for install and emergency use.
   Hierarchy nodes are created and renamed through the API (plan 013 T2.3: `createSubOrganization`, `createEnvironment`, the three `rename*` operations), which validates names itself (trimmed, Unicode-whitespace-aware so a name of non-breaking or em spaces is refused, and free of format characters such as bidirectional overrides and zero-width characters); the `V015` CHECK (1-100 characters, no control characters, not ASCII-blank) is only the backstop, and a sibling clash is a named conflict compared by PostgreSQL `lower()` under the database's locale (not trimmed, not Unicode-normalised), with `V015`'s unique indexes (`OR-123`) as the backstop. A production install's organization still carries its id text as its display name until it is renamed (`OR-120`); over HTTP an organization is renamed at install scope only, by an install-bound Administrator (`OR-125` (3)), and any other organization by the audited operator command `OrganizationRenameMain` (`OR-131` Q1), whereas a sub-organization or environment is renamed within its organization.
4. **The plain-VM runtime adapter** (plan 014, approved as a plan and **not built**: the VM adapter lands in a later pull request,
   `OR-121` Q-V12 (b)). Kubernetes has no adapter until the owner reopens it.
5. **The agent gateway**, the `host-local` agent path (`AGW-D*`, [`../docs/design/backend/agent-gateway/`](../docs/design/backend/agent-gateway/)).
   `OR-126` and `OR-128` adopt the teammate's `dp-agent-integration` branch as plan 015's base and approve that plan. **In progress:** plan 015 W1 to W4 are integrated on `integration/agent-gateway` (the `agent-gateway` module, `sm_control` V022 and `sm_data` V031, the Go agent, the gateway service and telemetry, the five agent operations of the console contract, the two scheduled jobs); the real-agent harness (T4.4) and the live gates G-A1 and G-A2 (W5) have not run, so it is not counted served. Operator procedures: [`../docs/runbooks/backend/`](../docs/runbooks/backend/).

Also open, outside that order: **live browser notify** (the browser joining the event mesh through the gateway-proxied
`wss` with post-RBAC tickets; open items 32, 33 and 35. The backend already publishes notify events when
`sm.mesh.enabled=true`); the monitoring storage gate's remaining measurement: the 105 M-row export stream (Step 6b), without which
`exportRange` stays wired and tested but **not counted served** (the one-week run is done; `REQ-007` is not met at the measured topology); an inactive replica is not yet composed into a query series;
journal (`apply_request`) pruning; and plan 010's remaining flags (its Results, section 4). Docker-API-only hosts and the
smaller deferrals wait for a customer need.

## Schemas and the two-phase bootstrap

`sm_control` and the Data Plane's `sm_data`, `sm_secret` and `sm_metrics` are created by their Phase 0 SQL, executed
**once by the bootstrap superuser** (on PostgreSQL 14 only the owner of schema `public` can revoke its `CREATE`), and
migrated by Flyway in a **separate one-shot process** holding a migrator credential the application never receives. The
application gets `sm_cp_runtime` and `sm_dp_runtime` only and refuses to start against a schema its migration step has
not reached. **JDBC URLs are locations only**, `jdbc:postgresql://host[:port]/database` plus an allowlist of nine
non-secret pgjdbc parameters, because Flyway and Hikari log the URL. An install that ran Phase 0 before `sm_metrics`
existed runs two statements first (the install runbook has them). Published migrations are **frozen** (guards, above).

## Build prerequisites and the licence gate (`REQ-014`, invariant `I8`)

**Toolchain, pinned and recorded.** JDK **17** (`java.version` in `pom.xml`; measured on the build machine:
Microsoft Build of OpenJDK `17.0.17`). Maven **3.9.16**, through the Maven Wrapper in this directory
(`./mvnw`, `.mvn/wrapper/maven-wrapper.properties`; `only-script` type, wrapper `3.3.4`). The wrapper plugin was
verified before use: `maven-wrapper-plugin:3.3.4` declares no licence itself, its parent chain
(`maven-wrapper-parent:3.3.4` → `maven-parent:45` → `org.apache:apache:35`) declares `Apache-2.0`, and the
repository's `LICENSE` at tag `maven-wrapper-3.3.4` is the Apache License 2.0 (read 2026-10-05). The Maven
installation, the JDK and the never-run `site`/`deploy` plugins are **outside** `REQ-014`'s "builds with"
(owner ruling 2026-10-05). The reactor still builds with a plain `mvn`; the wrapper is how the Maven version
is pinned.

**The gate.** `license-maven-plugin:2.7.1` is bound to `verify` in `pom.xml` and runs in every jar module, in two
executions (shipped scopes, test scope). It fails the build on any resolved artifact whose licence is off the
allow-list and not an exact exception, whose POM declares no licence, whose licence name is not an exact entry in the
mapping, that declares several licences with one off the list and no recorded election, or whose version differs
from the approved one. It ignores `-DskipTests`. Every plugin parameter that has a `-Dlicense.*` user property and
could weaken it is pinned in `pom.xml`, and `scripts/check-licences.py` fails when a pin is removed or an execution
overrides one. **Measured** with the `jna:5.13.0` election removed (a red build): each of `excludedGroups`,
`excludedArtifacts`, `includedGroups`, `includedArtifacts`, `excludedTypes`, `includedTypes` and an
`artifactFiltersUrl` file took it green before the pins, and each stays red after them; `excludeTransitiveDependencies`,
`excludedLicenses`, `licenseMergesFile`, `overrideFile`, `missingFileUrl`, `useMissingFile`, `failOnMissing`,
`failOnBlacklist`, `force`, `skipAddThirdParty`, `includedScopes`, `excludedScopes` and `includedLicenses` also stay red
(two of them, `licenseMergesFile` and `overrideFile`, because the plugin refuses them next to the pinned URL form).
**One residual, named:** `includedTypes` is a comma list, not a regex, and an empty POM element does not beat the flag
(measured), so it is pinned to `jar,test-jar,pom,bundle`. The types resolved across the whole reactor today are `jar`
and `test-jar` (`dependency:list`). An artifact of any other type would not be gated.

| File | What it is |
|---|---|
| `licence-allowlist.txt` | the six `REQ-014` allow-list names and the exact-string name mapping |
| `licence-exceptions.txt` | one line per owner-approved exact coordinate and version, with its row, reason, election and ruling (`OR-66`, `OR-67`, `OR-68`); the approval name fixes its scope (shipped, test-only, build-tool-only). The two artifacts that state no licence are **owner waivers** under their own name, never a licence name. A stated licence choice is elected by one line citing `OR-66`, with the file and the connective quoted |
| `licence-artifact-filters.txt` | the comment-only file `artifactFiltersUrl` is pinned to |
| `licence-third-party.ftl` | the report template; also enforces the several-licences and exact-version rules, which the plugin cannot express |
| `THIRD-PARTY-NOTICES.md` | the human-maintained attribution notices for content a shipped jar bundles inside itself, which the generated report cannot list: the Unicode License V3 and IETF Trust notices for `json-schema-validator` 2.0.8's `ucd/` tables (`OR-87`) |
| `scripts/check-licences.py` | **the second check, not bound to Maven**: build plugins and their dependencies, system scope, the proprietary `<licenses>` block (`OR-68`), the pinned plugin parameters, waivers, and that the three copies of the allow-list agree. Run `python3 backend/scripts/check-licences.py` (offline). It and its self-test `scripts/test-check-licences.sh` are wired into `contracts/run-checks.sh` (steps 13 and 13b). |

**Known limits, stated plainly.** The plugin cannot see build plugins, and Maven has no offline exec-capable plugin
here, so the build-plugin check is not part of `verify`; it runs in `contracts/run-checks.sh`. A new dependency, a new plugin or a version
bump fails until someone adds one exception line with an owner ruling, or replaces it. The container images the
integration tests run (`busybox:1.36`, `postgres:14`, `testcontainers/ryuk:0.12.0`) are approved as tools by exact tag,
test-only (`OR-67`) and are not gated by Maven. The ryuk tag was read from `testcontainers-1.21.4.jar`
(`RyukContainer.class`); a Testcontainers bump that changes it needs a new owner line. Licence audit and the owner's decision table:
[`../docs/tasks/licence-audit-2026-09.md`](../docs/tasks/licence-audit-2026-09.md).

## Before writing code here

This component does not own its own architecture. Read, in order:

| What | Where |
|---|---|
| Cross-cutting decisions and the ratification matrix | [`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md) |
| Overall architecture | [`../docs/design/architecture.md`](../docs/design/architecture.md) |
| This component's design | [`../docs/design/backend/design.md`](../docs/design/backend/design.md), decisions in [`decisions.md`](../docs/design/backend/decisions.md) (`BE-D*`, PROPOSED) |
| How this code consumes `contracts/` | [`../docs/design/common/contracts/backend-consumption.md`](../docs/design/common/contracts/backend-consumption.md) (`BE-D10`/`BE-D11`) |
| Approved plans, each with a Results section as its completion record | [`../docs/plans/backend/`](../docs/plans/backend/): 001 to 005, 010 and 011 executed; 006 and 007 delivered the identity and HTTP substrate; 008, 012 and 013 built under the [combined schedule](../docs/plans/backend/combined-008-012-013.md) (live gates ran 2026-10-09: L3's `l3-edit` and the inspect-fix host re-measure are owed; the combined review is owed); 014 (VM adapter) approved, not built; 009 is superseded in part by 011 |
| Operator procedures | [`../docs/runbooks/`](../docs/runbooks/) |
| Setup checklist | [`../docs/checklists/new-module.md`](../docs/checklists/new-module.md) |

**If the design is wrong or missing, raise it, do not invent architecture here.** Component-level decisions get an ID in the
`BE-D*` family and are recorded in the design folder, not in code comments. Invariants `I1` (no MI development), `I5`
(server-side tenant isolation, bound SQL), `I6` (secrets never land in config storage), `I7` (cross-plane boundary) and
`I8` (open-source commercial licences only) bind every change; see `CLAUDE.md`.

## Run the production backend locally

`backend/local/` is a one-command local stack (PostgreSQL 14 in Docker, both migration steps and the production launcher on port 8080, then **everything
else through the API as the bootstrap administrator**: the credential rotated, the organization named, a hierarchy, the Cache entitlement, an Operator and an
optional SSH-Docker target; no seed SQL) for testing the APIs end to end from Postman: `backend/local/sm-local.sh up`, `down` to remove everything. See [`local/README.md`](local/README.md) (OR-118).
