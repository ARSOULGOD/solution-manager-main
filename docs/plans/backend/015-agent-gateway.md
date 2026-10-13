# Backend Plan 015 — the agent gateway: port `dp-agent-integration` onto `UAT` (`OR-126`)

> **Status: APPROVED (`OR-128` (1)).** Revision 2 was written 2026-10-08 on branch `p/agw` (worktree `p-agw`), answering the
> adversarial review's 22 findings (P15-1 to P15-22, verdict *"not approvable as written"*), each disposed of in
> [§11](#11-review-dispositions-2026-10-08). **W1 starts now** on `integration/dp-remote-demo` @ `2568862f` (`OR-133` (3)). If the
> merge into `UAT` changes anything, the plan-015 branches are rebased and migration numbers and ids re-checked before their PRs.
> The PRs target **`UAT`** (`OR-132` (1)); `OR-126`/`OR-128` text that says `dev` is read as `UAT`. Line numbers below that name our
> tree were measured at `x/w4-int` @ `a6cec7c7`; the base is now `2568862f` and the **measured** bindings are at the top of
> [Results](#results) (T0.1 Step 1, 2026-10-09). Docs only so far: nothing here is built.
> **Owner items still open** (none blocks W1 dispatch; see [§10](#10-what-needs-the-owner) rows 18-20): F-5's scope over
> `agent_lease`/`agent_session` (before T1.1 merges), F-6's window on sample age or on measured clock offset (before T3.4), three
> Java coordinates (before T1.2 merges). A task whose ruling excludes it is deleted, not skipped.

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans`. Steps use checkbox (`- [ ]`) syntax. Read [§4 the port ledger](#4-the-port-ledger),
> [§5 hot files](#5-hot-files) and [§6 the waves](#6-the-waves) before starting any task. The execution model is
> `OR-70`, as the [combined schedule](combined-008-012-013.md) applies it.

| | |
|---|---|
| **Component** | backend (`cp-api`, `cp-core`, `dp-api`, `dp-core`, `console-gateway`, `launcher`, `arch-tests`, **new `agent-gateway`**) · `go-agent/` · `runtime/` · `contracts/` (`openapi/`, **new `agent/v1/`**) · docs. **No frontend change**; the frontend receives the contract (incl. the widened `Target`/`TargetConnection`, T3.5) and the Postman collection. |
| **Authority** | `OR-126` (A1–A10), **`OR-127`** (licences L1–L4; now in this branch's base), `OR-125` (1), (2), (4) (placement actor, pinned key type, per-organization usernames — each moves a migration this plan must follow), `OR-121`/`OR-122` (catalog form; `OR-122` (2) the `ScheduleFirer` enumeration precedent), `OR-113` (7), `OR-25`, `OR-27`, `OR-32`, `OR-40`, `OR-44`, `OR-73`/`OR-92`, `OR-77`, `OR-111`, `OR-110`, `D10` §1.9 (`D10-G2-APPROVAL-2026-09-15.md`), the `AGW-D*`, `BE-D*` and `AG-D*` families. The owner rules for the architect group (`OR-69`). |
| **Inputs** | `recovered-scratchpad/DP-AGENT-INTEGRATION-ANALYSIS-2026-10-08.md` (cited **[An §x]**) · `recovered-scratchpad/AGW-REVISIT-BRIEF-2026-10-08.md` (cited **[Br Qn]**) · `recovered-scratchpad/AGW-LICENCE-AUDIT-2026-10-08.md` (the audit `OR-127` ruled; cited **[Au §x]**) · the adversarial review of revision 1 (findings **P15-n**) · branch `origin/dp-agent-integration` @ `87e338e1` (fork point `eabacdca`) and its plan `docs/plans/backend/011-agent-gateway-production-{implementation,execution}.md` · the dp-remote W4 integration head `x/w4-int` @ `a6cec7c7` (where every line number below that names our tree was measured; `dev` after the dp-remote PR will have moved them) · [design](../../design/backend/agent-gateway/design.md) and [decisions](../../design/backend/agent-gateway/decisions.md) |
| **Requirements** | `REQ-005` (runtimes; Docker on host only, `OR-111`) · `REQ-006` (RBAC: rows 44–47) · `REQ-008` (no-footprint customers keep dp-remote) · `REQ-009`/`I1` · `REQ-014`/`I8` (every new dependency and tool, T0.2) · `I5`, `I6`, `I7` |
| **Ids it records** | Plan number **015** (the branch's "011" is re-issued). `AGW-D23`–`AGW-D32`, `BE-D66`–`BE-D71`, `AG-D26`–`AG-D29` (proposed allocation in [§7](#7-ids-and-migration-numbers); **texts drafted and reviewed in T0.3**, registered by T0.1; next free at merge). Catalog rows **44–47** (`OR-126` A8). Central open items **42–47** — the gateway's lost items 34–39, re-registered in the commit that added this plan. Migrations by **symbolic name** (`VDA`, `VDB`, `VCA`, [§7](#7-ids-and-migration-numbers)). |

**Goal.** A Docker host running the SM agent enrols outbound-only to the Data Plane, which at enrolment creates its
agent target in both planes; its labelled Cache containers self-register into the environment it was enrolled into
(`OR-27`); and every operation SM serves on a dp-remote placement or target either works on the agent's through the
same apply engine or answers a contracted refusal — with dp-remote and agent placements coexisting in one install.

**Architecture.** The branch's gateway is taken nearly whole: a fifth plane module `agent-gateway` (gRPC/mTLS,
sessions, credits, drain) talks to the Data Plane only through `dp-api` ports; enrolment, the agent CA and leases sit
in the CP as built, adapted to `dp-api` by the launcher (`OR-126` A3, A4). What is **rewritten** is everything that
touches our data: one consolidated `sm_data` migration over our `placement`/`target_connection`/`config_generation`
tables; the **agent target created at enrolment** (CP governance row, then DP connection row, `BE-D32`'s order and
recovery); a `RuntimeAdapter` that keys every agent command on the apply journal's attempt-free intent key, hands the
**engine's own in-memory render** to the agent and never persists the bytes (`OR-126` A5, `I6`); a routing adapter
that picks SSH or agent per placement's target transport, with a concurrency cap so an offline agent cannot starve
dp-remote; reports written into our health, start and write-baseline tables; telemetry re-sunk into `sm_metrics`
through the built ingest port (A7); and `OR-27` auto-registration with per-container tombstones (A6). The Go agent is
taken as-is, then given six append-only wire additions (`OR-130` D-4 added the declared probe) and two hardening edits.

**Tech stack** (base measured at `x/w4-int`; the agent stack read from `87e338e1`; licences ruled by **`OR-127`**):
Java 17 target (JDK 21 runtime) · Spring Boot 3.5.16 · netty **4.2.13.Final** pinned for JCSMP (`backend/pom.xml:73`) ·
PostgreSQL 14 · Flyway 11.7.2 · ArchUnit 1.5.0 · **new:** `io.grpc:grpc-netty-shaded/grpc-protobuf/grpc-stub 1.68.1`
(`grpc-netty-shaded` incl. its embedded BoringSSL, `OR-127` L3), `com.google.protobuf:protobuf-java-util 3.25.5`,
`org.bouncycastle:bcpkix-jdk18on 1.86` + `bcutil-jdk18on 1.86` (exact-coordinate exceptions, L2), build-time
`org.xolstice.maven.plugins:protobuf-maven-plugin 0.6.1`, `kr.motd.maven:os-maven-plugin 1.7.1`, the native executables
`com.google.protobuf:protoc:3.25.5:exe` and `io.grpc:protoc-gen-grpc-java:1.68.1:exe` (audited by POM licence [Au §1
rows 11–12]; pinned by T0.2's executable check, P15-12) run with **`@generated=omit`** — **`javax.annotation:javax.annotation-api 1.3.2` is NOT
taken** (L1); Go agent: Go `1.26.4` (`go.mod`), `grpc v1.84.0`, `protobuf v1.36.12`, `docker/docker
v27.1.2+incompatible`, `prometheus/client_golang v1.24.1`, `yaml.v3`; Go tools **`buf`, `protoc-gen-go`,
`protoc-gen-go-grpc` — not in `OR-127`'s audit** (measured: the audit names none of them), audited by T0.2 Step 2;
images `eclipse-temurin:21-jre-jammy@sha256:f04fb3…` (launcher) and `gcr.io/distroless/static-debian13:nonroot` (agent,
`go-agent/Dockerfile:18`), both approved with a corresponding-source-offer obligation (L4) ([An §1.4, §1.7, §5 item 4]).
**Test fixture image:** `solace-cache-engine:local`, built from the sibling `~/Work/Development/solace-cache-engine` the
way `backend/demo/README.md` builds it (P15-21; T0.4 Step 3).

---

## Global constraints

Every task's requirements include these.

1. **`I6` / `OR-126` A5 — SM persists no rendered byte.** No `bytea`/`text` payload column in any agent table, no
   rendered bytes in `agent_command`, no generation table of the agent's own (a schema test fails on any column that
   could hold them, T1.1). **Render once:** the bytes the agent receives are the `RenderedConfig` the apply engine
   already rendered for the step (`ApplyExecutor` renders at pre-flight and binds `config.hash()` into the
   `MATERIALIZE` intent, `ApplyExecutor.java:727`), handed **in memory** to the payload source keyed by command id; they
   are re-rendered **only** on a redelivery after an SM restart, and then sent only if the re-render's keyed hash equals
   `agent_command.config_hash` (else the pull is refused with a code, T3.1). **On the host**, the agent's payload
   spool (`commands.go:258`) is `OR-129`'s named exception: mode 0600 in a 0700 directory, purged at terminal, swept at start (`AG-D29`).
2. **`OR-32`.** Every config hash SM **stores** is the keyed `hmacsha256v<n>:<hex>` from `ConfigHasher`. A plain
   SHA-256 exists only on the wire and in memory ([Br Q8] (a)) — never in a log line on either side: the agent's
   `CommandOffer.LogValue` stops printing `config_sha256` (`msg.go:352`, T2.3).
3. **`I5`.** The organization is never taken from the agent or the client. An agent's tenant, environment and target
   are resolved by `dp-core` from its certificate thumbprint through **one named unbound read**
   (`JdbcAgentIdentityRepository#resolveByThumbprint`), which is sound only because `agent_identity.agent_id` and
   `certificate_thumbprint` are each **globally** `UNIQUE` (T1.1); `TenantEnumerationBoundaryTest` names it as the one
   allowed unbound read (T2.1, P15-19). Every agent table carries `tenant_id`; every other query binds it; all SQL uses
   bind parameters.
4. **`I7` / `AGW-D2`.** `agent-gateway` imports `dp-api` only (never `cp..`, never `dp-core`, no datasource, no JDBC);
   `dp..` never imports `cp..`; the only cross-plane composers are `console-gateway` (`OR-10`) and the launcher's three
   named adapters (`BE-D69`, T2.2). The rules are `agentGatewayUsesDpApiOnly`, `agentGatewayHoldsNoDatasource`,
   `dpNeverImportsAgentGateway` and **`onlyAgentGatewayTouchesGrpc`** (the branch's `dataPlaneAndConsoleDoNotTouchGrpc`,
   renamed and widened to every module but `agent-gateway`, P15-22). Each has a planted violation that fails (`L19`).
5. **No global switch.** dp-remote and agent placements run in the same process; the adapter is chosen per placement
   by `target_connection.transport`. `sm.dp.agent.runtime.enabled` and the branch's second `ApplyExecutor` bean are
   not ported. **An agent can never starve dp-remote** (P15-6): agent steps fail fast without a live session and lease,
   and at most `sm.dp.agent.max-concurrent-requests` (< the worker pool) agent requests are claimed at once (T3.1).
6. **Migrations (`OR-22`, `OR-105` (e), `OR-125` (1), (2), (4), `OR-126` A1).** Ours are never renumbered or edited.
   This plan's migrations are **next free after the dp-remote PR's**, **measured 2026-10-09 on `2568862f`** ([§7](#7-ids-and-migration-numbers)):
   `sm_data` `VDA` = **V028**, `VDB` = **V029**; `sm_control` `VCA` = **V021** (V019 = `OR-125` (4), V020 = `OR-131` Q1, the
   operator rename); `sm_metrics` `VMA` = **V004** (T3.4, `OR-130` F-6). Their DDL is the reviewed draft
   `docs/design/backend/agent-gateway/schema-015-draft.sql`, **updated and replayed in the W0 fix round against those bindings**
   (P15-10); T1.1/T1.3 finalize it as a diff and re-check the numbers at the `UAT` rebase (`OR-133` (3)).
   `CHECKSUMS.sha256`, the anchors file and the pinned counts move in the same commit.
7. **Licences (`REQ-014`/`I8`, `OR-126` A10, `OR-127`).** Only what `OR-127` approved, at its exact pin, is merged;
   anything else (a Go module compiled into `sm-agent` that the audit did not cover, a tool, a changed pin) returns to
   the owner first (T0.2). **Our `I8` gate stays and is never ported over:** the `license-maven-plugin` executions in
   `backend/pom.xml`, `backend/licence-allowlist.txt`, `licence-exceptions.txt`, `licence-artifact-filters.txt`,
   `licence-third-party.ftl`, `scripts/check-licences.py` and `scripts/test-check-licences.sh`. The branch forked
   (`eabacdca`) **before** that gate existed: taking any branch `pom.xml` wholesale would delete it. Every wave's full
   gate checks it is intact (§6.2 step 4).
8. **Every operation contracted.** An operation the agent path does not do — on a placement **or on a target** —
   answers a coded refusal with a stated HTTP shape, never a silent no-op ([Br Q9], T3.3, T3.5).
9. **No auth material in any output.** No token, private key, CSR, certificate private part, keystore password,
   rendered byte or plain digest of rendered bytes in a log line, exception message, audit row, metric or response
   (the branch's `RealAgentDockerE2EIT#noSecretReachesTheCapturedJavaOrAgentLogs` is ported, T4.4).
10. **Full gate per wave** ([§6.2](#62-the-integration-point-of-every-wave)), offline (`-o`), plus the Go gate.
11. **No forward dependency (P15-1).** Every interface a later wave implements is **declared** (signature, records,
    contracted stub behaviour, test of the stub) by T2.1a in W2; a task never compiles or wires against something a
    later task creates, and every wave's full gate is green on that wave's head alone.
12. **Two PRs, split by path (P15-14).** Every commit touches **either** the agent PR's paths **or** the backend PR's
    paths, never both ([§6.4](#64-the-two-prs)).

## Review focus

The six inputs no task's happy path exercises that are most likely to hurt someone. Each line's test lives in the
named task.

1. **An agent placement or target reached through a dp-remote-only path** — the health poller, the metrics
   collector, logs, log level, read-config/accept-runtime, restore; `editTargetConnection`, `rotateTargetCredential`,
   `testTargetConnection`, `addPlacement`. Expected: the poller and collector skip it; each operation answers its coded
   refusal; nothing tries SSH to an agent target. Tests: T3.1, T3.3, T3.5.
2. **SM restarts between the agent's `CommandReceived` and its `CommandAck`** — e.g. during a `RESTART`. Expected: the
   resumed engine finds the same `agent_command` row by the intent's attempt-free key, the agent's journal re-acks
   under the same command id and generation, and the container restarts **exactly once**. Tests: T3.1
   (`AgentCommandCrashRecoveryIT`), T4.4, G-A1.
3. **A compromised or buggy agent reporting more containers than a host holds, a container outside the target's
   envelope, or a label naming another organization's instance.** Expected: the per-target cap refuses beyond the cap;
   the envelope trigger is never reached (the DP refuses first, with a code); the instance is always created in the
   agent's enrolled environment; every refusal is an `agent_event`. Test: T4.1.
4. **A container SM must not re-register** — undeclared (`BE-D49` leaves it running), its placement retired, or its
   pending registration abandoned by the sweep. Expected: its tombstone answers the re-report and nothing is minted.
   Tests: T4.1 (`undeclareThenReconnect…`, `sweepThenReconnect…`).
5. **An agent that is offline while applies are queued.** Expected: its steps fail fast with `agent.session.absent`;
   dp-remote requests are claimed and complete meanwhile. Test: T3.1 (`AgentStarvationIT`).
6. **A telemetry batch the built port answers `RetryAfter`, `Rejected("placement.unknown")` or (never in v1)
   `Pending`.** Expected: `RetryAfter` and `Pending` are not acked and the agent's disk buffer keeps them; a rejected
   batch is dropped and counted, never retried forever. Test: T3.4.

---

## 1. What `OR-126` decided, and what it left open

| Item | Ruled | What this plan does with it |
|---|---|---|
| A1 base | Port `dp-agent-integration` as a curated slice onto `dev` after the dp-remote PR; frontend and `go-agent` lines are separate PRs; migrations after ours; colliding ids re-issued | [§4](#4-the-port-ledger) per path; plan number 015; **two PRs defined mechanically in [§6.4](#64-the-two-prs)** (the agent PR carries `contracts/agent/v1` and merges first) |
| A2 session at the DP | Amends LOCKED `B/D16` by name for `host-local` | Recorded by `OR-126`; `AGW-D23` restates the deployment clause (launcher-composed) |
| A3 CP leases | As built (launcher adapters across `I7`) | T2.2 takes the CP issuer; `AGW-D24` changes one thing: the DP supplies the placement set (the CP holds no placement) |
| A4 operator keystore CA | v1 | T2.2 takes `Pkcs12AgentCertificateAuthority` as-is |
| A5 render at delivery | Never persist rendered bytes | Global constraint 1 (render once, in-memory hand-off, re-render-and-compare on redelivery); T1.1, T3.1; the host spool is `OR-129` (`AG-D29`) |
| A6 `OR-27` in scope | Auto-registration | T3.5 (the agent target at enrolment), T2.3 (agent discovery), T4.1 (DP side, tombstones), T4.2 (completer) |
| A7 telemetry | Own ingest, re-sunk via the built port | T3.4 |
| A8 rows 44–47 | `agent.enrol`, `agent.revoke`, `agent.drain`, `agent.view`; Admin all, Operator view+drain, Viewer view | T1.3 (catalog 47; roles 47 / 26 / 9) |
| A9 E2E-host agent | Granted on condition by `OR-128` (12), completed by `OR-134` (A9) | G-A2 runs after G-A1 is green (T5.3); the grant names four items: the agent process, its lifetime, the loopback reverse tunnel, and the clean-up |
| A10 licences | Every dependency audited at its pin before merge — **answered by `OR-127`** (L1–L4) | T0.2 applies it and audits what the audit did not cover (Go tools, protoc executables' pins, the shipped binary's module set); T1.2 proves L1; T2.2 L2; T2.4/T5.2 L3/L4 |

**Not ruled by `OR-126`; assumed here as the brief recommended, and since confirmed by `OR-128` (10) ([Br Q4] (b), [Br Q6] (b), [Br Q9] (a),
[Br Q10] (b), [Br Q12] (a)), `OR-128` (7) ([Br Q7], sized ±5 minutes by `OR-130` F-6) and `OR-128` (15) (the completer)** (each listed again in
[§10](#10-what-needs-the-owner)):
[Br Q4] (b) the placement is the replica; [Br Q6] (b) a blocking adapter behind the existing seam (what the branch
built), now with fail-fast and a concurrency cap; [Br Q9] (a) minimal parity plus health; [Br Q10] (b)
single-replica machinery; [Br Q12] (a) a DP `agent_event` store; [Br Q7] the agent sample-timestamp rule (departs
from `OR-92` OQ-4).

## 2. Scope

**In:** the agent-gateway module and its boundary proofs; enrolment, CA, lease, session, drain, revocation; **the
agent target, created in both planes at enrolment, and the behaviour of every existing target operation on it**;
command delivery for preflight, apply (materialize), rematerialize-if-current, restart, stop, with crash-safe command
identity; status with health, persisted hash and start baseline; restart-observed; agent telemetry into `sm_metrics`;
`OR-27` auto-registration with quota, cap, tombstones and audit; verbs 44–47 with five console operations and
Postman; the Go agent with six wire additions and two hardening edits; the real-agent harness; `runtime/` packaging;
live gate G-A1 (local) and, if granted, G-A2 (E2E host).

**Out (routed, not dropped):** Kubernetes and VM agents (`OR-111`); agent log reading, log level, read-config /
accept-runtime and rollback restore on agent placements (follow-on slice, [Br Q9] (b)); manual `addPlacement` on an
agent target (refused in v1, T3.5); multi-replica gateway machinery (claim ledger, two-gateway ITs: extraction plan,
`AGW-D14`); the `sm_secret`-sealed CA (`OR-126` A4 "later option"); everything on the branch that is not agent work —
the identity projection and `console.yaml` `Identity` delta, `DesignerProjectorImpl`/`PackageModel` edits (→ the
frontend-integration intake), `BE-D27` and the `tenant`→`organization` envelope rename (→ central item 36), the
plan-010 and `sm_control/README.md` amendments and `SmControlMigrationUpgradePathIT` (→ discarded), the orphan
`V001__telemetry_schema.sql` (→ stays deleted) [An §6.3 item 4].

## 3. File structure (target)

| Path | Responsibility | PR |
|---|---|---|
| `contracts/agent/v1/agent_gateway.proto` | The one wire contract, moved from `go-agent/proto/agentgateway/v1draft/` (package name kept `solace.sm.agentgateway.v1draft`, so the gRPC service name does not move); append-only | agent |
| `contracts/agent/v1/buf.yaml` | The buf v2 workspace for the contract (module `path: .`); `go-agent/buf.gen.yaml` generates from it (P15-7) | agent |
| `contracts/agent/v1/check_proto_append_only.py` + `baseline.json` + `run-checks.sh` step | Field numbers and names never change or disappear | agent |
| `backend/agent-gateway/` (`com.solace.sm.agw`) | gRPC server, interceptor, thumbprint resolver, admission, credits, drain, management listener, metrics; generated code confined here | backend |
| `backend/dp-api/.../dp/api/agw/` | The gateway's ports and records — **all** of them declared by T2.1a, incl. `AgentDiscoveryPort`, `AgentTelemetryDeclarationsPort`, `AgentObservedStatePort`, **`AgentTelemetryPort`** ([§4.2.1](#421-the-37-dp-apiagw-types)) | backend |
| `backend/dp-api/src/test/.../agw/kit/` (test-jar) | Abstract port contract kits a future remote adapter must pass (`AGW-D2`) | backend |
| `backend/dp-core/.../dp/apply/persistence/agent/` | JDBC implementations of the ports over `VDA` (inside the existing JDBC-confined package; no rule widened) | backend |
| `backend/dp-core/.../dp/apply/runtime/agent/` | `AgentRuntimeAdapter`, `AgentPayloadHandoff` (in-memory, by command id), `AgentCommandPayloadSource` (hand-off, else re-render-and-compare), `AgentHashBridge` (plain ↔ keyed, in memory) | backend |
| `backend/dp-core/.../dp/apply/runtime/RoutingRuntimeAdapter.java` | Picks the SSH or agent adapter per placement by target transport | backend |
| `backend/dp-core/.../dp/agent/` | Report intake, telemetry bridge, discovery intake, the enrolment-time target writer (no JDBC; calls repositories in `apply.persistence.agent`) | backend |
| `backend/cp-api/.../cp/api/agent/`, `backend/cp-core/.../cp/agent/`, `cp-core/.../cp/persistence/JdbcAgentRegistry.java` | Enrolment registry (with the target envelope), CA, lease issuance, the CP target row at enrolment | backend |
| `backend/launcher/.../ControlPlane{LeaseIssuer,EnrollmentCertificateIssuer,RegistrationSource}.java` | The three cross-plane adapters (`BE-D69`) | backend |
| `backend/console-gateway/.../http/AgentController.java`, `AgentOrchestrator.java`, `agent/AgentRegistrationCompleter.java` | Verbs 44–47's five operations; the `OR-27` completer (shape per §10 item 15) | backend |
| `go-agent/` | The agent (taken as-is, then T2.3's additions) | agent |
| `runtime/` | Image, compose, scripts | backend |

## 4. The port ledger

**Disposition:** **TAKE** = copy the file at `87e338e1` unchanged (`git checkout 87e338e1 -- <path>` on a fresh lane
branch), then only the mechanical edits named · **ADAPT** = take, then change what is named · **REWRITE** = read for
behaviour, write new against our tree · **DROP** = not ported.

### 4.1 Go agent and contract

| Source (at `87e338e1`) | Disposition | Why / what changes | Task |
|---|---|---|---|
| `go-agent/**` (100 files excl. `oldGo-Agent/`; history `48633557`, `0d406ca4`, `cbf96f5b`, `28d63140`, `6555488e`) | **TAKE** | Measured end to end against the plane on the branch; supersedes `origin/dev/go-agent` [An §1.4]. Only edits: `go-agent/buf.yaml` **deleted** (the workspace moves to `contracts/agent/v1/buf.yaml`); `go-agent/proto/**` deleted (moved by T1.2); `Makefile` `proto` target reads `../contracts/agent/v1`; `buf.gen.yaml` unchanged, run against the contract directory (T1.4 Step 2) | T1.4 |
| `go-agent/oldGo-Agent/**` (35 files; its own `go.mod`, so the parent module skips it, `AG-D15`) | **TAKE**, unless the owner rules §10 item 16 (b) | `AG-D15` says the superseded code is archived, not deleted | T1.4 |
| `go-agent/proto/agentgateway/v1draft/agent_gateway.proto` | **ADAPT** (move) | To `contracts/agent/v1/` per `AG-D13`'s exit condition [An §6.2 S2]; both generators read it there; content unchanged in W1 | T1.2 (agent-PR commit) |
| — | **REWRITE** (append) | Six wire additions: health observation (the agent runs the declared probe, `OR-130` D-4: `HealthProbe` pushed with the declarations, probe facts reported), container start time on the materialize/restart ack, `expected_current_sha256` precondition, discovery report (container facts, not conclusions), DP-pushed telemetry declarations | T2.3 |
| `go-agent/internal/msg/msg.go:352` (`config_sha256` in `CommandOffer.LogValue`) | **ADAPT** | Removed (Global constraint 2) | T2.3 |
| `go-agent/internal/commands/commands.go:258` (payload persisted as `store.Blob` until terminal) | **KEEP per `OR-129`**, plus the startup sweep and mode checks | Ruled (a): the spool stays as a named `I6` exception (0600/0700, purged at terminal, swept at start); `AG-D29` | T2.3 |
| `docs/design/go-agent/decisions.md` `AG-D13`–`AG-D20`, `docs/design/go-agent/specs/*`, `docs/plans/go-agent/001`–`005`, `docs/design/archive/go-agent-fork/*` | **TAKE** | No collision (ours stops at `AG-D12`) [An §1.9] | T0.1 |
| `AG-D21`–`AG-D25` (cited in `go-agent/README.md`, `CLAUDE.md`, `RUNBOOK.md` and seven Go files; **defined nowhere on the branch**, measured by `git grep`) | **REWRITE** (register) | Dangling citations; T0.3 drafts their text from the code and README, marked reconstructed; T0.1 registers | T0.3, T0.1 |

### 4.2 Backend

| Source (at `87e338e1`, under `backend/`) | Disposition | Why / what changes | Task |
|---|---|---|---|
| `agent-gateway/src/main/**` (9 classes), `src/test/**` (4 tests, 32 cases), `README.md` | **TAKE** → mechanical compile fixes **T2.1a** → **ADAPT** behaviour **T3.2** | Working server [An §1.3]. Its `pom.xml` is written by hand (row below) | T1.2, T2.1a, T3.2 |
| `pom.xml` (module + grpc/protobuf management), `launcher/pom.xml`, `cp-core/pom.xml` (`bcpkix`), `agent-gateway/pom.xml` | **ADAPT — never TAKE** | Only the named elements are re-applied by hand onto ours (HF13). `agent-gateway/pom.xml` loses `javax.annotation-api` (`OR-127` L1) and keeps `spring-boot-actuator` (`AgentGatewayServer` implements `actuate.health.HealthIndicator`, measured) | T1.2, T2.2 |
| (our) `backend/licence-*.txt`, `licence-third-party.ftl`, `scripts/check-licences.py`, `scripts/test-check-licences.sh`, the plugin in `backend/pom.xml` | **KEEP** (not in the branch) | `I8`'s gate; the new rows are `OR-127`'s | T0.2 |
| `arch-tests/.../PlaneBoundaryTest.java` rules `dpNeverImportsAgentGateway`, `agentGatewayUsesDpApiOnly`, `agentGatewayHoldsNoDatasource`, `dataPlaneAndConsoleDoNotTouchGrpc` | **ADAPT** | Taken into a new `AgentGatewayBoundaryTest`; the last **renamed `onlyAgentGatewayTouchesGrpc`** and widened to every module but `agent-gateway` (P15-22); **planted fixtures and a pom allow-list added** (none exist [An §3.2]); the widening of `jdbcIsConfinedToPersistencePackages` to `dp.agent..` is **dropped**; the softened inventory message is **reverted** | T1.2 |
| `dp-api/.../agw/*` (**37 types**, measured; revision 1 said 28) | **ADAPT** — per-type disposition in [§4.2.1](#421-the-37-dp-apiagw-types) | | T2.1a |
| `dp-core/.../dp/agent/JdbcAgentGatewayPorts.java` (943 lines), `JdbcAgentPlacementCatalog`, `AgentGatewayPersistenceConfiguration` | **REWRITE** | Same statements' intent, over `VDA` and our `placement`/`target_connection`; bind parameters kept; location `dp.apply.persistence.agent` | T2.1b |
| `dp-core/.../dp/agent/JdbcAgentRenderedConfigSource.java`, `AgentApplyConfiguration.java` | **DROP** | Replaced by the in-memory hand-off of the engine's own render, and by routing (no second `ApplyExecutor`) [An §3.1 items 3, 4] | — |
| `dp-core/.../apply/runtime/agent/AgentRuntimeAdapter.java`, `AgentPlacementCatalog.java`, `AgentRuntimeConfiguration.java` | **ADAPT** | Ten operations (adds `rematerializeIfCurrent`; `readConfig` refused); command identity on the intent key; render once; keyed hash bridge; status through `AgentObservedStatePort`; fail-fast; kit subclass | T3.1 |
| `dp-core/.../apply/runtime/RuntimeOperationContext.java` + `RuntimeAdapter` default overloads + `ApplyExecutor` call sites (+17/−7 in `28d63140`) | **ADAPT** (re-applied by hand) | Correlates a command with the journal (apply, intent, **intent key**; the attempt id rides as correlation only, never in the key — P15-3); additive defaults; `ApplyExecutor` is HF1 | T3.1 |
| `dp-core/.../apply/worker/ApplyWorker*.java` edits on the branch | **DROP** (they served the global switch [inferred]) — **but** T3.1 adds its own claim-time agent concurrency cap (HF22, P15-6) | | T3.1 |
| `dp-core/.../apply/persistence/SmDataMaintenanceMain.java`, `SmDataMigrator`/`SmDataMigrationMain` edits | **DROP** | Maintenance follows `OR-77` (central item 47); our migrator already runs `sm_metrics` (`SmDataMigrationMain.java:144,176`) [An §6.5] | T4.5 |
| `dp-core/.../meshbridge/JcsmpEventPublisher.java`, `mesh-events/**` edits | **DROP** | Envelope rename → central item 36 | — |
| `dp-core/src/main/resources/db/migration/sm_data/V018`–`V026` (9 files) | **REWRITE** | Collide with ours; V020/V021/V023/V024/V025 duplicate our `placement`/`sm_metrics`/`config_generation` and store rendered bytes and plain hashes [An §2.2]; → one state file **`VDA`** + one function file **`VDB`** | T1.1 |
| `cp-core/.../sm_control/V014__agent_registration.sql`, `V015__agent_enrollment_replay.sql` | **REWRITE** | Collide with our V014/V015; no tenant/environment; `target_id text`; `binding_ids[]` → **`VCA`** | T1.3 |
| `dp-core` tests `AgentRuntimeAdapterTest`, `AgentReportIT`, `AgentRevocationIT`, `AgentTelemetryProjectionIT`, `AgentGatewayMigrationShapeTest` | **ADAPT** | Same scenarios over our tables; `AgentTelemetryProjectionIT` re-targets `sm_metrics` | T2.1b, T3.x |
| `cp-api/.../agent/*` (6 types), `cp-core/.../agent/{AgentCertificateAuthority,AgentRegistry,ControlPlaneAgentGatewayService,Pkcs12AgentCertificateAuthority}.java`, `cp-core/.../config/AgentGatewayCaProperties.java`, `cp-core/.../persistence/JdbcAgentRegistry.java`, `ControlPlaneAgentGatewayServiceTest` | **TAKE** the CA and properties; **ADAPT** registry and service | CA as built (A4); registry gains scope and the target envelope, loses `binding_ids`, writes under `OR-25` audit context with the agent as actor where `BE-D70` allows | T2.2, T3.5 |
| `cp-core/.../identity/{CredentialHashMain,RoleBindingRepository}.java`, `JdbcRoleBindingRepository`, identity edits, `SmControlMigrationUpgradePathIT`, `upgrade-probe/*` | **DROP** | Not agent work (identity projection) or moot (V013 gap) [An §6.3] | — |
| `launcher/.../AgentGatewayControlPlaneAdapterConfiguration.java`, `ControlPlaneLeaseIssuer`, `ControlPlaneEnrollmentCertificateIssuer`, `ControlPlaneRegistrationSource`, `FailureDiagnostics` | **TAKE** → **ADAPT** (written against T2.1a's signatures) | The three adapters (`BE-D69`); `ControlPlaneLeaseIssuer` passes the DP-supplied placement set; `ControlPlaneEnrollmentCertificateIssuer` gains the CP target row in T3.5 | T2.2, T3.5 |
| `launcher/.../SolutionManagerConfiguration.java`, `application.properties` edits | **ADAPT** (by hand) | Import the agent configurations; no `sm.dp.agent.runtime.enabled` | T3.2 |
| `launcher` test harness: `RealAgentHarness`, `SyntheticAgent`, `FaultInjection`, `MatrixEvidence`, `AgentTestSupport`, `ContainerStack` | **TAKE** → **ADAPT** | Seeding moves from SQL to the API (enrolment token) and `OR-27` (no hand-made placement) | T4.4 (W5) |
| `launcher` ITs `RealAgentDockerE2EIT` (10), `AgentFailureMatrixIT` (17), `AgentLeaseExpiryIT` (4), `AgentFleetDrainIT` (2), `ExtraCommandUnderCpuPressureIT` (4), `AgentCapacityIT` (2), `ContainerPackagingIT` (13) | **ADAPT** | Same scenarios; **fail closed** under the `agent-live` profile instead of `assumeTrue` skips | T4.4 (W5) |
| `console-gateway` `ErrorCode`/`Identity`/`AuthController`/test edits | **DROP** (identity) / **REWRITE** (`ErrorCode` members for agent operations and targets) | `ErrorCode.java` is HF20 | T3.3, T3.5, T4.3 |

#### 4.2.1 The 37 `dp-api/agw` types

Measured with `git ls-tree 87e338e1 backend/dp-api` (P15-15). **K** = kept as is (package unchanged) · **A** = adapted
as named · **N** = new in T2.1a · **D** = dropped.

| Type | Disp. | What changes |
|---|---|---|
| `AgentGatewayPorts` (aggregate interface) | **D** | Each port is wired and kit-tested on its own; no aggregate bean |
| `AgentSessionPort`, `AgentSessionRef` | **A** | `AgentSessionRef` carries no tenant (`AGW-D3`); the port gains `liveFor(agentId)` (session open **and** unexpired lease) for the adapter's fail-fast |
| `AgentEnrollmentPort`, `EnrollmentRequest`, `EnrollmentResult`, `EnrollmentCertificateIssuer` | **A** | The request carries `AgentRequestOrigin`; the issuer's answer carries the environment, the target id, the envelope (`allowedConfigRoot`, `allowedContainerPrefix`) and the issuing principal (T3.5) |
| `AgentIdentityPort`, `AgentIdentity`, `IdentityReconciliation` | **A** | `AgentIdentity` gains tenant, environment, target (resolved by the DP, never sent by the agent) |
| `AgentLeasePort`, `LeaseGrant`, `LeaseIssuer` | **A** | The DP passes the placement set; the CP never invents one (`AGW-D24`) |
| `AgentRegistrationSource`, `AgentRegistrationState` | **A** | `binding_ids` removed |
| `AgentCommandPort`, `CommandNotice`, `CommandPayload`, `CommandAckResult`, `CommandCompletion`, `AgentOperation`, `AgentOutcome` | **A** | `enqueue` keyed by the intent key and returning the existing command (P15-3); `supersedeOutstanding`; `CommandPayload` holds no stored bytes |
| `OperationCorrelation` | **A** | `(applyId, intentId, intentKey, step)` + `attemptId` as correlation only; the key never contains the attempt |
| `ActualStatePort`, `ReportItem`, `ReportResult` | **A** | Report items gain health and container start time (T2.3); implemented by `AgentReportIntake` (T3.3) |
| `AgentActualStateQueryPort`, `AgentActualState` | **D** → replaced by **N** `AgentObservedStatePort` + `AgentObservation` | The adapter's read side for `status` (P15-1): T2.1b ships a stub answering "no observation"; T3.3 implements it |
| `TelemetryIngestPort` | **A** (renamed) | → **`AgentTelemetryPort`**: collides by simple name with the built `dp.api.metrics.ingest.TelemetryIngestPort` [An §1.3] |
| `TelemetryBatch`, `TelemetryResult` | **A** | Renamed `AgentTelemetryBatch`, `AgentTelemetryResult`; the result says ack / do-not-ack / rejected-and-counted |
| `CanonicalTelemetrySample` | **D** | Samples map straight to the built `IngestSample` (T3.4) |
| `TelemetryQueryPort` | **D** | Queries are the built metrics API's |
| `AgentAuditPort` | **K** (records `agent_event`) | |
| `AgentCapability`, `GatewayCode`, `GatewayPortException` | **K** | `GatewayCode` gains the new codes (`agent.session.absent`, `agent.payload.render.changed`, discovery codes) |
| — | **N** `AgentDiscoveryPort`, `DiscoveredContainer`, `DiscoveryResult` | T2.1b stub answers every item `REFUSED agent.discovery.unavailable`; T4.1 implements |
| — | **N** `AgentTelemetryDeclarationsPort`, `TelemetryDeclaration` | T2.1b stub answers "no declarations"; T3.4 implements |
| — | **N** `AgentRequestOrigin(UUID correlationId, String clientAddress)` | Minted by the gateway per RPC; the only source of `OR-25`'s correlation id and client address on the agent path (P15-8) |

Count: 37 taken = **5 dropped** (`AgentGatewayPorts`, `AgentActualStateQueryPort`, `AgentActualState`, `CanonicalTelemetrySample`, `TelemetryQueryPort`) + **32 kept or adapted**; **8 new** named here, plus the small value records the signatures below name (`AgentCommandIntent`, `AgentCommandRef`, `AgentAck`, `CommandOffer`, …), counted in T2.1a's commit.

### 4.3 Packaging, docs, evidence

| Source | Disposition | Why / what changes | Task |
|---|---|---|---|
| `runtime/launcher/Dockerfile`, `runtime/launcher/bin/{sm-launcher,sm-migrate,sm-healthcheck}`, `runtime/compose.yml`, `runtime/.env.example`, `runtime/bootstrap/phase0.sh`, `runtime/scripts/build-image.sh`, `runtime/.gitignore`, `runtime/README.md` | **ADAPT** | `87e338e1` removed `rewrite-timestamp=true` (the property `AGW-D22` item 1 relies on): restore it or re-measure `--check` [An §1.2]; compose adds the gateway port and CA keystore mount | T2.4 |
| `runtime/launcher/bin/{sm-maintain,sm-hash-credential}`, `runtime/deploy/*` | **DROP** | `OR-77` pattern (item 47); `sm-hash-credential` belongs to the dropped identity projection | — |
| `docs/design/backend/agent-gateway/port-matrix.md` | **ADAPT** | Re-stated against the adapted ports; becomes T2.1a's contract | T0.1 |
| `docs/design/backend/agent-gateway/specs/2026-10-05-dp-agent-production-integration-design.md` | **TAKE** as history | Its §16 review is empty [An §1.8]; it is input, not authority | T0.1 |
| `AGW-D17`–`AGW-D22` (branch register) | **TAKE** (verbatim, PROPOSED) | No collision on ours (we stop at `AGW-D16`); the parts the port changes are superseded by citation in `AGW-D23`+ (never edited, `I3`) | T0.1 |
| `BE-D27` (branch) | **DROP** | Hard collision with our `BE-D27`; its content is the envelope rename (central item 36) | — |
| `docs/runbooks/backend/{enable-agent-gateway,maintain-sm-data,restart-the-gateway-with-a-drain,revoke-or-change-an-agent}.md` | **ADAPT** | No SQL provisioning (verbs and `OR-27` replace it); `maintain-sm-data` dropped | T5.2 |
| `docs/plans/backend/011-agent-gateway-production-*.md`, `evidence/011-*.md` | **TAKE** as history, renamed `015-history-*` | Plan number 011 collides; the evidence proves the branch's tree, not ours | T0.1 |

## 5. Hot files

The combined schedule's rule applies: **one writer per wave**, or an explicit order; a task that finds it must edit a
hot file it does not own stops and reports. HF1–HF12 keep the combined schedule's meaning (re-measure paths at W0;
after the dp-remote PR their pinned counts will have moved). New or newly owned for this plan:

| Id | Hot file(s) | Why | Owners by wave |
|---|---|---|---|
| HF3 | `contracts/openapi/console.yaml`, `ConsoleOperations.java`, `ENDPOINT-INVENTORY.md`, Postman | one contract | W3 **C** (T3.3 descriptions → T3.5 `Target`/`TargetConnection` + target refusals) · W4 **B** (T4.3) |
| HF11 | `dp-core/src/test/.../apply/persistence/InventoryFixtures.java` | every agent IT in W3 needs `agentTarget(...)` (P15-18) | W2 **A** (T2.1b adds `agentTarget`, `agentPlacement`); **read-only** in W3–W4 (a needed edit stops and is folded into the next wave's A) |
| HF13 | `backend/pom.xml` (modules, dependency management), `backend/launcher/pom.xml`, `backend/cp-core/pom.xml`, `backend/licence-exceptions.txt`, `backend/licence-allowlist.txt`, `backend/THIRD-PARTY-NOTICES.md` | one reactor, one licence gate | W1 **B** · W2 **B** |
| HF14 | **The wire:** `contracts/agent/v1/{agent_gateway.proto,baseline.json,buf.yaml}`, `go-agent/internal/wire/pb/*.pb.go`, `go-agent/buf.gen.yaml`, `contracts/run-checks.sh` | both generators and the append-only check read it | W1 **B** (move) → **D** (generate) · W2 **C** |
| HF15 | `backend/arch-tests/**` (`PlaneBoundaryTest` incl. `RUNTIME_ADAPTER_IMPLEMENTATIONS`, `CompositionBoundaryTest`, `TenantEnumerationBoundaryTest`, new `AgentGatewayBoundaryTest`, fixtures) | one inventory, one exclusion list | W1 **B** · W2 **A → B** · W3 **A** · W4 **A** only if §10 item 15 is (a) |
| HF16 | `dp-core/.../apply/production/ProductionApplyConfiguration.java`, `dp-core/.../apply/runtime/RuntimeAdapter.java` | the routing and the seam | W3 **A** |
| HF17 | `launcher/.../SolutionManagerConfiguration.java`, `launcher/src/main/resources/application.properties` | one composition root | W3 **B** · W4 **A** (T4.2's completion wiring, if §10 item 15 is (b)) |
| HF18 | `backend/dp-api/.../dp/api/agw/**` (every signature) | the cross-lane contract of W2–W4 (P15-1) | W2 **A** (T2.1a, merged first); afterwards a change to an existing signature is a stop-and-report, never a lane edit; a **new** type is added only by that wave's lane A and only when the same task both declares and uses it (W4: T4.2's `AgentRegistrationCompletion`) |
| HF19 | `dp-core/.../metrics/collect/CollectionPlanBuilder.java`, `dp-core/.../health/HealthPoller.java` | both skip agent placements | W3 **A** (T3.1) only; T3.4 does **not** edit them (P15-18) |
| HF20 | `console-gateway/.../http/ErrorCode.java` | one closed enum | W3 **C** (T3.3 → T3.5) · W4 **B** (T4.3) |
| HF21 | `launcher/.../ControlPlane{LeaseIssuer,EnrollmentCertificateIssuer,RegistrationSource}.java`, `AgentGatewayControlPlaneAdapterConfiguration.java` | the three adapters | W2 **B** (T2.2 creates) · W3 **C** (T3.5 adds the CP target row) |
| HF22 | `dp-core/.../apply/worker/ApplyWorker.java`, `ApplyWorkerProperties.java`, the claim query in `apply.persistence` | the agent concurrency cap | W3 **A** (T3.1) |

## 6. The waves

Four lanes at most (**A** data plane, **B** gateway and CP, **C** wire, intake and agent targets, **D** agent,
packaging, harness), one Sonnet agent per lane at a time, in worktrees; **one Opus review per wave** (one reviewer per
lane, in parallel) and **one fix round**; the **full gate** at each wave's integration point; **one Fable review** at
the end with one fix round (`OR-70`). Push fast-forward only, after the wave's gates, to the two plan-015 integration
branches ([§6.4](#64-the-two-prs)).

### 6.1 The schedule

| Wave | Lane A | Lane B | Lane C | Lane D | Merge order and hot-file owners | Gates | Pipeline hours (elapsed) |
|---|---|---|---|---|---|---|---|
| **W0** — **may start now**, beside dp-remote W5/W6 | T0.1 records (Step 1 measured 2026-10-09 on `2568862f`) | T0.2 licence gate | T0.3 drafts: wire, console, schema, **decision texts** + one Opus review | T0.4 toolchain, Cache image | C's drafts before A's Step 2; docs by A only | owner: §10 items marked *before W1* | ≈ 3 (overlaps dp-remote) |
| **W1** | T1.1 `sm_data` `VDA`/`VDB` | T1.2 module, proto move, `buf.yaml`, boundary proofs | T1.3 `sm_control` `VCA`, catalog 47 | T1.4 Go agent intake | A, C, B, D · HF5: A · HF6, HF9: C · HF13, HF15: B · HF14: **B → D** | Go gate first run | ≈ 6 |
| **W2** | **T2.1a signatures** (first, pushed to the lane base within the wave's first hour) → T2.1b persistence, kits, stubs, fixtures | T2.2 CP enrolment, CA, lease, launcher adapters (branched from T2.1a) | T2.3 wire additions + agent hardening | T2.4 `runtime/` | **A (T2.1a) → B → A (T2.1b) → C → D** · HF18, HF4, HF11: A · HF13, HF21: B · HF14: C · HF15: **A → B** | — | ≈ 7 |
| **W3** | T3.1 adapter, routing, command identity, cap, kit | T3.2 gateway service, launcher composition, **G-A0** | T3.3 reports, refusals → **T3.5 agent target at enrolment** | T3.4 telemetry | A → C → B → D · HF1, HF15, HF16, HF19, HF22: A · HF17: B · HF3, HF20, HF21: C · HF4: **A → D** | G-A0 | ≈ 8 |
| **W4** | T4.1 → T4.2 `OR-27` DP, then completer | T4.3 verbs and operations | — | T4.5 retention (`OR-77`) | A → B → D · HF3, HF20: B · HF7: A · HF15: A (only if §10 item 15 (a)) · HF4: **A → D** | — | ≈ 5 |
| **W5** | T5.1 **G-A1** (after T4.4's `agent-live` run is green) | T5.2 runbooks, records, Results | **T4.4 real-agent harness** (moved from W4, P15-1) | T5.3 **G-A2** — after G-A1 is green (A9: `OR-128` (12), `OR-134`) | C → A → D (serial live gates) · HF12: D | `agent-live`, then G-A1, then G-A2 | ≈ 6 |
| **Final** | Fable review of both PRs, one fix round, full gate, push; the PRs into `UAT` (owner, `OR-132` (1)) | | | | | | ≈ 3 |

**Why this shape.** The chain the dependencies force is schema (T1.1) → signatures (T2.1a) → adapter (T3.1) and
intake (T3.3) and agent target (T3.5) → `OR-27` (T4.1/T4.2) → verbs (T4.3) → harness (T4.4) → G-A1. **No task
compiles or wires against a later task** (Global constraint 11): every port a later wave implements is declared in
T2.1a with a tested stub (`AgentDiscoveryPort` → T4.1, `AgentTelemetryDeclarationsPort` → T3.4,
`AgentObservedStatePort` → T3.3), so W2's and W3's full gates run on their own heads. T2.1a also makes
`agent-gateway` (taken in T1.2) compile against the reshaped types — the mechanical fixes only, no behaviour (T3.2) —
and T2.2 writes the launcher adapters **against T2.1a** from the start, so no adapter compiles against an old shape.
T4.4 seeds through T4.3's API and `OR-27`, so it runs in W5, after both. HF14 has two writers in W1 only (B's move,
then D's generation) and one per wave after. HF5 and HF6 are written once each.

### 6.2 The integration point of every wave

1. Each lane's own module verify passes (commands in its task).
2. One Opus review for the wave (a reviewer per lane, in parallel) and one fix round.
3. Merge lanes **in the order above**; each later writer rebases and re-runs its module build first. Migration
   numbers re-checked against [§7](#7-ids-and-migration-numbers).
4. **Full gate**, offline: `mvn -o -f backend/pom.xml clean verify` (reactor incl. the licence gate);
   `./contracts/run-checks.sh --strict` (with the new proto step); `python3 backend/scripts/check-frozen-migrations.py`;
   `python3 backend/scripts/check-licences.py`, `backend/scripts/test-licence-gate-present.sh` and
   `python3 backend/scripts/check-protoc-executables.py` (T0.2); `python3 contracts/openapi/postman/check-postman.py`;
   the DDL replay when `sm_data` changed (`docs/design/backend/evidence/ddl-replay/`); `git diff --check`; **the PR
   split check** ([§6.4](#64-the-two-prs)); **the Go gate:** `(cd go-agent && go vet ./... && go test ./... && go test
   -tags integration ./internal/agent/ -run Integration && scripts/check-licences.sh`, then regenerate with the pinned tools into a temp dir and `diff -r` against `internal/wire/pb` (byte-equal))` and, with Docker up, `go test
   -tags docker ./internal/runtime/docker/ -run E2E`. From W5: `mvn -o -f backend/pom.xml -pl launcher -Pagent-live
   verify` (needs `go`, Docker, `solace-cache-engine:local`; **fails** if any is missing).
5. Live gates for the wave, one at a time.
6. Push, fast-forward only. The next wave branches from that head.

### 6.3 Live gates

| Gate | Wave | Where | What runs | Authority |
|---|---|---|---|---|
| **G-A0** | W3 (T3.2 Step 4) | local | The launcher boots with the gRPC listener **and** a connected JCSMP session to the local broker in one JVM; both serve. Proof of the listener: an mTLS handshake and one RPC from a **Java gRPC client in the launcher's test sources** presenting a certificate issued by the test keystore CA (P15-22: the Go `fakegw` is a server, not a client) | none needed |
| **`agent-live`** | W5 (T4.4) | local Docker | The ported real-agent suites over the real Go agent and `solace-cache-engine:local` | none needed |
| **G-A1** | W5 (T5.1) | local machine, local Docker, local PostgreSQL 14 | The Go agent binary against a local Docker Cache: token issued by API → enrol (agent target created in both planes) → `OR-27` auto-registration → apply → `restart-observed` → metrics in `sm_metrics` → SM killed mid-`RESTART`, restarted, container restarted once → undeclare, reconnect, not re-registered → drain → revoke | none needed (developer machine) |
| **G-A2** | W5 (T5.3) | the E2E host (HF12) | The agent on the host beside the existing Cache: enrol, auto-register a second Cache container, apply, restart-observed, revoke; the host's Cache left working | `OR-126` A9, granted on condition by `OR-128` (12) and completed by `OR-134` (A9), which names four items: the agent process, its lifetime, the loopback reverse tunnel (the agent dials the DP; Q14's reachability is unverified [Br Q14]), and the clean-up. Runs after G-A1 is green |

### 6.4 The two PRs

*Note (2026-10-09, `OR-134` (Branches)):* the two PR branches are `integration/go-agent` (agent PR) and `integration/agent-gateway` (backend PR); lane branches are `feat/agent-gateway/w<N>-<task>`. Earlier text and the Results below name the working names `i015/agent` and `i015/backend` for the same branches.

`OR-126` A1 makes the `go-agent` line its own PR. Defined mechanically (P15-14):

| | **Agent PR** (`integration/go-agent` → `UAT`) | **Backend PR** (`integration/agent-gateway` → `UAT`) |
|---|---|---|
| Paths | `contracts/agent/v1/**`, the `contracts/run-checks.sh` proto step, `go-agent/**` (incl. `THIRD-PARTY-LICENCES.md`, `scripts/check-licences.sh`, `Dockerfile`), `docs/design/go-agent/**`, `docs/plans/go-agent/**`, `docs/design/archive/go-agent-fork/**` | everything else: `backend/**`, `runtime/**`, `contracts/openapi/**`, `docs/design/**` other than the above (incl. `DECISIONS.md`), `docs/plans/backend/**`, `docs/runbooks/**` |
| Tasks | T1.2's proto-move commit, T1.4, T2.3, the `AG-D*` half of T0.1, the agent rows of T0.2 | the rest |
| Merge order | **First.** The backend build's `protoSourceRoot` reads `contracts/agent/v1`, and `agent-live` builds `go-agent` | **Second**, rebased on `UAT` after the agent PR lands |

**`contracts/run-checks.sh` is classified by hunk, not by file** (review W0-15): the proto-append-only step is the agent PR's; T0.2's steps 16 and 17
(`test-licence-gate-present.sh`, `check-protoc-executables.py`) and any other hunk are the backend PR's. T0.2 already took steps 16 and 17,
so T1.2's proto step is numbered **18**. The split check below inspects the hunks of that file, not its name.

During execution `integration/agent-gateway` **merges `integration/go-agent`** at every wave's integration point (step 3), so the backend
gate always sees the wire and the agent of that wave; once the agent PR has merged, the backend PR's own diff is
backend paths only. **Split check** (step 4): for each commit of the wave, `git show --name-only --format= <c>` must
fall entirely in one column above; a commit in both fails the gate (a task that needs both commits twice — e.g. T0.1).

---

## W0 — records and gates (docs and tools; may start now)

### T0.1 — Records: ids, registers, citations (lane A)

**Files:** `docs/design/backend/agent-gateway/decisions.md` (append `AGW-D17`–`AGW-D22` verbatim from `87e338e1`,
then `AGW-D23`–`AGW-D32` **from T0.3's reviewed texts**; changelog row with the old→new open-item map);
`docs/design/backend/decisions.md` (append `BE-D66`–`BE-D71` from T0.3; fix the stale link at `:2319` →
`agent-gateway/decisions.md` [Br §1.1]); `docs/design/go-agent/decisions.md` (append `AG-D13`–`AG-D20` verbatim;
`AG-D21`–`AG-D25` reconstructed, each marked *"reconstructed on the T0.3 date from `go-agent/<file>`; no source text
existed"*; `AG-D26`–`AG-D29` from T0.3); `docs/design/go-agent/specs/*`, `docs/plans/go-agent/001`–`005`,
`docs/design/archive/go-agent-fork/*` (TAKE); `docs/design/backend/agent-gateway/port-matrix.md` (ADAPT, per §4.2.1);
`docs/plans/backend/015-history-implementation.md`, `015-history-execution.md`, `docs/plans/backend/evidence/015-history-*`
(TAKE, renamed); `docs/design/D10-G2-APPROVAL-2026-09-15.md` (new **§1.10**: rows 44–47 as ruled by `OR-126` A8, with
level and scope as the owner rules §10 item 4); `docs/design/DECISIONS.md` (the ratification matrix gains the new ids;
**items 42–47 already exist**, added with this plan); `docs/requirements/traceability.md`.

- [x] **Step 1: Measure the base.** Done 2026-10-09 on `integration/dp-remote-demo` @ `2568862f` (PR #174 into `UAT` open, `OR-133` (3)):
      `VDA` = `sm_data` V028, `VDB` = V029, `VCA` = `sm_control` V021, `VMA` = `sm_metrics` V004; the latest `record_change_audit()` is
      `sm_control` V020's; `OR-125` (1) is `sm_data` V026 and (2) is V027; next free `BE-D72`, `AGW-D33`, `AG-D30`, `OR-134`, catalog
      row 48, central open item 48 — **no renumbering**. The measured table is recorded at the top of Results; it is re-checked at the
      `UAT` rebase (`OR-133` (3)).
- [x] **Step 2:** Append the registers listed above, copying **T0.3's reviewed decision texts** (P15-20). Every new
      decision is PROPOSED; every supersession is by citation; no existing row is edited (`I3`). Two commits (agent PR
      and backend PR paths, §6.4).
- [x] **Step 3:** `grep -rn "open item 3[4-9]" docs/design/backend/agent-gateway go-agent docs/design/go-agent` —
      each hit gets a forward pointer *in its own file's changelog*, not an in-place edit.
- [x] **Step 4:** `./contracts/run-checks.sh --strict`; every relative link in the changed markdown resolves (the repo
      has no link script; run `git diff --name-only -- '*.md' | xargs python3 -c "import re,sys,os;[print(f,l) for f in sys.argv[1:] for l in re.findall(r'\]\(([^)#:]+)', open(f).read()) if not os.path.exists(os.path.join(os.path.dirname(f),l))]"`
      — empty output); `git diff --check`.
- [x] **Step 5: Commit** `docs: plan 015 T0.1 — register AGW-D17..D32, BE-D66..D71, AG-D13..D29, catalog §1.10` (one
      per PR).

**Done when:** every id this plan uses resolves to a registered row, and no citation of items 34–39 is ambiguous.
**Lane time:** ≈ 2 h.

### T0.2 — The licence gate: apply `OR-127`; audit what it did not cover (lane B; `REQ-014`/`I8`, `OR-126` A10)

**Ruled (`OR-127`, owner, 2026-10-08):** **L1** `javax.annotation-api 1.3.2` is **not** taken; the gRPC Java generator
runs with `@generated=omit` (proved in T1.2's first build; if `protobuf-maven-plugin 0.6.1` cannot forward the option,
it **returns to the owner** — no substitute is chosen here). **L2** `bcpkix-jdk18on 1.86` and `bcutil-jdk18on 1.86`
are approved as exact-coordinate exception lines. **L3** `grpc-netty-shaded 1.68.1` is approved including its embedded
BoringSSL, with the notice obligations carried in the distribution's NOTICE. **L4** `eclipse-temurin:21-jre-jammy` and
`gcr.io/distroless/static-debian13:nonroot` are approved, with a corresponding-source-offer obligation stated in the
release runbook.

**Files:** `docs/tasks/licence-audit-2026-09.md` (a section citing `OR-127` and the audit file; no re-litigation);
new `backend/scripts/test-licence-gate-present.sh` and `backend/scripts/check-protoc-executables.py` (both wired as
`run-checks.sh` steps); the exception/allow-list lines land **in the commit that adds the dependency** (HF13: L2's two
lines with T2.2, the gRPC set with T1.2); `backend/THIRD-PARTY-NOTICES.md` (L3's BoringSSL notices, with T1.2);
`go-agent/THIRD-PARTY-LICENCES.md` (new, with T1.4); the release-runbook line for L4 (T5.2).

- [x] **Step 1: The gate stays.** `test-licence-gate-present.sh` fails unless `backend/pom.xml` declares
      `license-maven-plugin` bound to `verify`, the four `licence-*` files plus `scripts/check-licences.py` exist, and
      `scripts/test-check-licences.sh` passes. Planted proof: on a scratch copy, delete the plugin block → it fails.
- [x] **Step 2: The native executables, pinned (P15-12).** `protoc` and `protoc-gen-grpc-java` are fetched by the
      protobuf plugin at execution time with an `exe` classifier, so they are in neither the licence plugin's artifact
      set nor `check-licences.py`'s `resolve-plugins` realm (whose pattern accepts `jar|maven-plugin|pom` only,
      `check-licences.py:240`). `check-protoc-executables.py` reads every `pom.xml` and fails unless the only
      `protocArtifact`/`pluginArtifact` coordinates are `com.google.protobuf:protoc:3.25.5:exe:${os.detected.classifier}`
      and `io.grpc:protoc-gen-grpc-java:1.68.1:exe:${os.detected.classifier}` ([Au §1 rows 11–12]), and no other
      `:exe:` coordinate appears. Planted proof: a scratch pom naming `protoc:3.25.6` → fails.
- [x] **Step 3: Coverage diff.** Compare the audit's rows with what the port will actually resolve: the gRPC Java
      transitive set at 1.68.1, `protobuf-java-util 3.25.5`, the two build plugins; **the Go modules compiled into the
      shipped binary** — `go list -deps -f '{{with .Module}}{{.Path}} {{.Version}}{{end}}' ./cmd/sm-agent | sort -u`
      (P15-12: the `go.sum`-only and test-only modules [Au §2] are not shipped and are out of the gate's scope; the
      `cmd/fake-gateway` dev tool is not shipped either); **and the tools T0.4 installs** (Go toolchain, `buf`,
      `protoc-gen-go`, `protoc-gen-go-grpc` — none is in the audit, measured). Each tool's licence is read at its
      pinned version from its own repository by WebFetch (`I2`), never recalled. Anything the audit does not name, or
      names at another version, **returns to the owner** before its task merges. Record the diff in the audit section.
- [x] **Step 4:** Run `./contracts/run-checks.sh --strict` (both new steps green on the untouched tree).
      *(Steps 16 and 17 are the two new checks; see Results for the four node-dependent steps that cannot run in this worktree.)*
- [x] **Step 5: Commit** `docs,build: plan 015 T0.2 — apply OR-127; the I8 gate and the protoc pins are checked present`.

**Done when:** every artifact or tool the port resolves is either an `OR-127` row at its exact pin, newly audited and
recorded, or has gone back to the owner; both checks run in every wave's gate. **Lane time:** ≈ 1.5 h.

### T0.3 — Contract drafts, decision texts, and one adversarial review (lane C)

**Files (all outside `contracts/`, so no check sees them until a task lands them):**
`docs/design/backend/agent-gateway/proto-amendments-015.md`; `docs/design/backend/agent-gateway/agent-console-contract-draft.yaml`;
`docs/design/backend/agent-gateway/schema-015-draft.sql` (`VDA`/`VDB`/`VCA` as reviewed DDL);
**`docs/design/backend/agent-gateway/decisions-015-draft.md`** (the full text of every new decision, P15-20).

- [x] **Step 1: The wire additions** (append-only, new field numbers only, `AGW-D11`): (a) `ActualStateReport.health`
      = `HealthObservation{state: HEALTHY|STARTING|UNHEALTHY|NOT_DECLARED|UNREACHABLE (the Docker check: a hint, never the gate), observed_at, probe_strength, http_status, actuator_status, failure}` — the facts of the probe the agent runs itself (`OR-130` D-4; the sixth addition, (f) `HealthProbe{kind, path, host_port, strength}` as `TelemetryDeclarations` field 5);
      (b) `CommandAck.container_started_at` (host clock, `OR-112`'s same-clock rule) on APPLY and RESTART;
      (c) `CommandOffer.expected_current_sha256` (`optional`; empty string = "still absent") for
      `rematerializeIfCurrent`; (d) `DiscoveryReport{items: [DiscoveredContainer{container_id, container_name,
      image_ref, labels (only the `com.solace.sm.*` keys), mounts: [{source, destination}], published_ports:
      [{container_port, host_port}]}]}` on `Connect` and on every container start/stop the agent sees — **facts, not
      conclusions**: the DP derives every placement field (T4.1's source table) — and `DiscoveryResult{container_id,
      state: PENDING|REGISTERED|REFUSED|ABANDONED|UNDECLARED|RETIRED, code, binding_id}` per item (F-1: `binding_id` is the placement id once registered); (e) `TelemetryDeclarations{binding_id,
      interval, metrics[]}` gateway→agent, the package's `http_json` metric objects for that placement (the shape
      `go-agent/examples/telemetry.yaml` already parses). Name each field's number.
- [x] **Step 2: The console contract.** (i) **Operations** (`D10` rows 44–47): `issueAgentEnrolment` (`POST
      /cp/environments/{environmentId}/agent-enrolments`; body `{targetName, allowedConfigRoot, allowedContainerPrefix, catalogSolutionId, packageVersion}`
      (the last two: G-4/R-3, the instance's solution and package version) with the grammars `registerTarget` uses; returns the token **once**, `readOnly`; permission per §10 item 4),
      `listAgents` (`GET /cp/agents`, `agent.view`), `getAgent` (`GET /cp/agents/{agentId}`, `agent.view`),
      `revokeAgent` (`POST /cp/agents/{agentId}/revoke`, `agent.revoke`), `drainAgent` (`POST /dp/agents/{agentId}/drain`,
      `agent.drain`); `x-sm-permission`, `x-sm-scope`, new `ErrorCode` members. (ii) **The agent target** (P15-2):
      `Target.transport` enum `[ssh-docker, agent]`; `TargetConnection` becomes `oneOf: [SshDockerTargetConnection
      (today's object, renamed, unchanged), AgentTargetConnection{targetId, transport: agent, agentId,
      allowedConfigRoot, allowedContainerPrefix}]` with `discriminator: {propertyName: transport}`;
      `registerTarget`'s request stays `ssh-docker` only. (iii) **Refusals** on existing operations: placement-level
      (T3.3) and target-level (T3.5's table), each with its status and `ErrorCode` member.
- [ ] **Step 3: The DDL** of T1.1 and T1.3 *(draft written and replayed; updated and re-replayed in the W0 fix round for the bound numbers; unticked until W1's finalization)*.
      **Finalized against `2568862f` in W1; re-checked at the `UAT` rebase** (P15-10). `OR-125` (1) is `V026` (a kind-less
      `created_by`/`retired_by uuid`: no placement DDL) and (2) is `V027` (`host_key_algorithm text NULL`: the shape CHECK leaves the
      `ssh-docker` leg unconstrained and requires NULL on the agent leg). The finalization is a diff against the draft, reviewed by
      the W1 reviewer, not a second T0.3 review.
- [x] **Step 4: The decision texts** (P15-20): `AGW-D23`–`AGW-D32`, `BE-D66`–`BE-D71`, `AG-D26`–`AG-D29` (allocation
      in §7), plus `AG-D21`–`AG-D25` reconstructed — each with status PROPOSED, the rule, the rationale, what it
      supersedes or amends by citation, and the enforcing test.
- [x] **Step 5:** One Opus adversarial review of the four drafts against `I5`/`I6`/`I7`/`OR-25`/`OR-32`/`AGW-D2`/`OR-27`
      and this plan; dispositions appended to `proto-amendments-015.md`.
- [x] **Step 6: Commit** `docs: plan 015 T0.3 — wire, console, schema and decision drafts, reviewed`.

**Lane time:** ≈ 3 h incl. the review (+ the Step 3 finalization in W1's review).

### T0.4 — Toolchain and the Cache image (lane D)

- [x] **Step 1:** Measure what exists: on 2026-10-08 this machine has **no `go`, no `buf`, no `protoc`** (`which`),
      JDK 21.0.12, Docker CLI present. **With the owner's permission (§10 item 6)** install by Homebrew: Go (at
      `go.mod`'s `1.26.4` if Homebrew offers it, else the version used is recorded — the branch's evidence ran 1.27.1
      [An §5 item 11]), `buf`, `protoc-gen-go`, `protoc-gen-go-grpc`; `protoc` itself only if a step is found that calls
      it (`buf` compiles `.proto` itself; the Java side uses the Maven-fetched executable). Each **after** T0.2 Step 3
      has cleared its licence.
- [x] **Step 2:** Prime `~/.m2` once online with the protobuf plugin's `protoc` and `protoc-gen-grpc-java` executables
      for the host classifier, so every later gate runs `-o`.
- [x] **Step 3: The Cache image (P15-21).** `solace-cache-engine` has **no tags** (measured 2026-10-08; checked-out
      branch `async-flow-test` @ `8b26dc9e`). Build the fixture exactly as `backend/demo/README.md` and plan 005 did:
      `mvn -f ~/Work/Development/solace-cache-engine/pom.xml package && docker build -t solace-cache-engine:local
      ~/Work/Development/solace-cache-engine`, from the commit recorded here (the owner may name another, §10 item 17).
      Record the commit and the image id in Results; `agent-live`, G-A1 and the Go docker-tagged tests use that image.
      (G-A2 uses whatever Cache already runs on the E2E host.)
- [x] **Step 4:** Record the versions in this plan's Results. No commit beyond the Results line.

**Lane time:** ≈ 1 h.

---

## W1 — foundations

### T1.1 — `sm_data` `VDA` (state) and `VDB` (maintenance function) (lane A; HF5)

**Files:** `dp-core/src/main/resources/db/migration/sm_data/V<VDA>__agent_gateway.sql`,
`V<VDB>__agent_gateway_maintenance.sql`, `CHECKSUMS.sha256`; `backend/scripts/frozen-migration-anchors.txt`; pinned
counts in `ApplyPersistenceConfigurationIT`, `SmDataBootstrapIT`, `RefusalShapesIT`, `PrivilegeTopologyIT`; DDL
replay baseline; new `dp-core/src/test/.../apply/persistence/agent/AgentSchemaIT.java`; `SketchMirrorTest` if it
mirrors table lists.

**Interfaces — produces** (the table set T2.1 binds to; columns abbreviated, every table leads with `tenant_id uuid
NOT NULL`). **This sketch is a summary. The reviewed draft `docs/design/backend/agent-gateway/schema-015-draft.sql` is
authoritative** (updated and re-replayed in the W0 fix round against `2568862f`); where they differ, the draft wins, and these lines
are corrected to match it (W0 review finding 11):

```sql
-- widen, never edit V007/V022 (BE-D68): the agent target and the agent acquisition
ALTER TABLE sm_data.target_connection DROP CONSTRAINT target_connection_transport,
  ADD CONSTRAINT target_connection_transport CHECK (transport IN ('ssh-docker','agent')),
  ALTER COLUMN host DROP NOT NULL, ALTER COLUMN port DROP NOT NULL, ALTER COLUMN ssh_user DROP NOT NULL,
  ALTER COLUMN host_key_fingerprint DROP NOT NULL, ALTER COLUMN credential_ref DROP NOT NULL,
  -- OR-125 (2) is V027's host_key_algorithm text NULL (NULL = type unknown, by design, for every pre-V027 SSH row): NO constraint
  -- on it for the ssh-docker leg (an ADD CONSTRAINT requiring it would fail on an upgraded database and forbid V027's fallback);
  -- the agent leg requires it NULL; no DROP NOT NULL is needed (P15-10, W0 review 1)
  ADD COLUMN agent_id uuid NULL,
  ADD CONSTRAINT target_connection_transport_shape CHECK (
    (transport = 'ssh-docker' AND host IS NOT NULL AND port IS NOT NULL AND ssh_user IS NOT NULL
       AND host_key_fingerprint IS NOT NULL AND credential_ref IS NOT NULL
       AND agent_id IS NULL)
 OR (transport = 'agent' AND agent_id IS NOT NULL AND host IS NULL AND port IS NULL AND ssh_user IS NULL
       AND host_key_fingerprint IS NULL AND host_key_algorithm IS NULL AND credential_ref IS NULL
       AND credential_public_fingerprint IS NULL));
-- G-1: V012's AFTER INSERT/UPDATE triggers copy the pinned key into target_host_key (fingerprint NOT NULL), so VDA REPLACES
-- sm_data.target_pinned_key_recorded() (V012's body plus a NULL guard): an agent target writes no target_host_key row.
-- allowed_config_root / allowed_container_prefix stay NOT NULL for BOTH transports (P15-2): an agent target carries
-- the envelope its enrolment token named (T3.5), so V007's placement_envelope trigger holds for agent placements too.
-- created_by stays NOT NULL: on an agent row it is the principal who issued the enrolment token (BE-D71, §10 item 5).
CREATE UNIQUE INDEX target_connection_agent_once ON sm_data.target_connection (tenant_id, agent_id)
  WHERE agent_id IS NOT NULL AND retired_at IS NULL;
-- V022's event table gains the enrolment event and an actor kind (P15-2, P15-8)
ALTER TABLE sm_data.target_connection_event
  DROP CONSTRAINT target_connection_event_kind,
  ADD CONSTRAINT target_connection_event_kind CHECK (kind IN
      ('registered','edited','repinned','credential_rotated','retired','enrolled')),
  ADD COLUMN actor_kind text NOT NULL DEFAULT 'principal' CHECK (actor_kind IN ('principal','agent')),
  ADD CONSTRAINT target_connection_event_agent_iff_enrolled CHECK ((kind = 'enrolled') = (actor_kind = 'agent'));
  -- 'enrolled' carries no public_key_fingerprint: V022's fp_iff_key_set already requires NULL for it
-- OR-125 (1) is V026: placement.created_by / retired_by uuid NULL, no kind column, and V026's comment says an agent's id goes into the
-- same columns. VDA needs NO placement DDL; T4.1 writes the agent's id into placement.created_by and an agent actor is told apart
-- by joining agent_identity (BE-D70 item 6).
-- instance_state.acquisition_mode gains 'agent' (drop + re-add the named CHECK)
CREATE TABLE sm_data.agent_identity (tenant_id, agent_id, environment_id, catalog_solution_id, solution,
  declared_package_version /* G-4, R-3: copied from the enrolment record, as the envelope is */, target_id FK target_connection,
  certificate_thumbprint, agent_epoch, fleet_epoch, revoked_at, capabilities text[] CHECK (closed set), created_at,
  PRIMARY KEY (tenant_id, agent_id),
  CONSTRAINT agent_identity_agent_id_global UNIQUE (agent_id),               -- P15-19: tenant derivable from agent_id
  CONSTRAINT agent_identity_thumbprint_global UNIQUE (certificate_thumbprint));
CREATE TABLE sm_data.agent_session (tenant_id, agent_id PK, fencing_epoch bigint, opened_at, last_seen_at, closed_at);
CREATE TABLE sm_data.agent_lease (tenant_id, agent_id, lease_id, issued_at, expires_at, renewal_nonce_digest,
  placement_ids uuid[], state CHECK (ACTIVE|EXPIRED|SUPERSEDED|REVOKED));
CREATE TABLE sm_data.agent_command (tenant_id, command_id, agent_id, placement_id FK placement, apply_id, intent_id,
  intent_key text NOT NULL /* = apply_step_intent.idempotency_key: applyId:step:replica, attempt-free (P15-3) */,
  attempt_id NULL /* the latest attempt; correlation only */,
  operation CHECK (PREFLIGHT|APPLY|REMATERIALIZE|RESTART|STOP),
  wire_generation bigint /* allocated ONCE per command row: max+1 per (agent, placement), AGW-D17 item 1 */,
  config_hash /* keyed only */ CHECK (config_hash IS NULL OR config_hash ~ '^hmacsha256v[0-9]+:[0-9a-f]{64}$'),
  state CHECK (QUEUED|OFFERED|DELIVERED|ACKED|REFUSED|SUPERSEDED), outcome, deadline, notified_at, received_at, acked_at);
CREATE UNIQUE INDEX agent_command_one_live_per_intent ON sm_data.agent_command (tenant_id, intent_key, operation)
  WHERE state IN ('QUEUED','OFFERED','DELIVERED');          -- R-6: a REMATERIALIZE may sit beside a live APPLY
-- R-13: one composite FK onto apply_step_intent (tenant, apply, intent, key, placement) and a trigger tying a command to the
-- agent's OWN target; R-16 lease retention is subject to the owner's F-5 confirmation (below)
CREATE TABLE sm_data.agent_report_watermark (tenant_id, agent_id, placement_id, last_sequence, last_item_id, updated_at);
CREATE TABLE sm_data.agent_telemetry_watermark (tenant_id, agent_id, stream_id, last_batch_seq, updated_at);
CREATE TABLE sm_data.agent_discovery (tenant_id, agent_id, instance_label, container_name, container_id,
  state CHECK (PENDING|REGISTERED|REFUSED|ABANDONED|UNDECLARED|RETIRED), instance_id NULL, placement_id NULL,
  refusal_code NULL, first_seen_at, settled_at,
  PRIMARY KEY (tenant_id, agent_id, instance_label, container_name));   -- the tombstone key (P15-9, T4.1)
CREATE TABLE sm_data.agent_event (tenant_id, event_id, agent_id NULL, request_id, code /* closed set */, actor_kind
  CHECK (AGENT|PRINCIPAL|SYSTEM), actor_id, correlation_id, client_address, detail_code, occurred_at);
  -- SELECT, INSERT only for sm_dp_runtime
```

- [ ] **Step 1: Write the failing test.** `AgentSchemaIT` (Testcontainers PG14 over the real migrations):
      ```java
      @Test void noAgentTableCanHoldRenderedBytesOrAPlainDigest() {
          // every column of sm_data.agent_% : none is bytea; none is named %payload%/%rendered%/%sha256%
          assertThat(columns("agent_%")).noneMatch(c -> c.type().equals("bytea")
              || c.name().matches(".*(payload|rendered|sha256).*"));
      }
      @Test void anSshTargetRowStillNeedsEverySshField() { /* insert ssh-docker with host NULL → 23514 */ }
      @Test void anAgentTargetRowRefusesSshFields() { /* transport agent + host 'x' → 23514 */ }
      @Test void anAgentTargetStillNeedsItsEnvelope() { /* transport agent, allowed_config_root NULL → 23502 */ }
      @Test void anAgentPlacementOutsideTheEnvelopeIsRefusedByTheTrigger() { /* V007 placement_envelope → 23514 */ }
      @Test void anEnrolledEventCarriesAnAgentActorAndNoFingerprint() { /* kind enrolled + actor_kind principal → 23514 */ }
      @Test void aPlainHashIsRefusedInAgentCommand() { /* config_hash 'sha256:…' → 23514 */ }
      @Test void oneLiveCommandPerIntentKeyAndOperation() { /* second QUEUED row, same intent_key + operation → 23505; REMATERIALIZE beside a live APPLY → allowed; after ACKED → allowed */ }
      @Test void aLegacySshRowWithANullHostKeyAlgorithmSurvivesVda() { /* a pre-V027 SSH target (host_key_algorithm NULL) exists BEFORE VDA runs; VDA applies; the row is intact → V027's fallback stays legal */ }
      @Test void anAgentRowWithAHostKeyAlgorithmIsRefused() { /* transport agent + host_key_algorithm 'ssh-ed25519' → 23514 */ }
      @Test void anAgentTargetWritesNoTargetHostKeyRow() { /* G-1: insert an agent target → commits, and target_host_key has no row for it */ }
      @Test void agentIdAndThumbprintAreGloballyUnique() { /* same agent_id in another tenant → 23505 */ }
      @Test void agentEventIsAppendOnlyForTheRuntimeRole() { /* UPDATE/DELETE as sm_dp_runtime → 42501 */ }
      @Test void everyAgentTableLeadsWithTenantId() { /* pg_index: PK's first column is tenant_id */ }
      @Test void oneLiveAgentTargetPerAgentPerTenant() { /* second live row same agent_id → 23505 */ }
      ```
- [ ] **Step 2:** Run `mvn -o -f backend/pom.xml -pl dp-core verify -Dit.test=AgentSchemaIT -Dtest=none
      -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (relations absent).
- [ ] **Step 3:** Write `VDA` (V028) from the reviewed draft (T0.3 Step 3, as updated in the W0 fix round); `VDB` (V029):
      `sm_data.agent_gateway_maintenance()` owned by `sm_dp_owner`, `SECURITY DEFINER`, `SET search_path = sm_data, pg_temp`
      (not `pg_catalog, sm_data`, which leaves `pg_temp` to be searched first: PostgreSQL 14, `CREATE FUNCTION`, "Writing
      SECURITY DEFINER Functions Safely"), no arguments, `EXECUTE` to `sm_dp_runtime` (the `OR-77` shape, extended by name to
      `agent_command`/`agent_event` retention by `OR-130` F-5; the branch's V019 statements are the reference). It deletes
      `ACKED`/`REFUSED`/`SUPERSEDED` commands older than a constant retention **except the highest `wire_generation` of each
      (agent, placement)** (R-1), and never deletes `agent_event`. **`OR-134` (F-5 scope, §10 item 18) rules that F-5 also covers
      `agent_lease`: `VDB` deletes leases that are not `ACTIVE` and expired more than 30 days ago, returns
      `(commands_deleted, leases_deleted)`, and never deletes `agent_session` rows** (one per agent; keeping it keeps the fencing
      epoch monotonic for the agent's life).
      Grants: runtime gets only the columns it writes; `agent_discovery.state` is updatable, its key columns are not.
- [ ] **Step 4:** Run the IT → PASS; then the pinned-count ITs, updated in this commit (sm_data migrations +2, tables +8,
      CHECKs re-measured); `python3 backend/scripts/check-frozen-migrations.py`; DDL replay.
- [ ] **Step 5: Mutation proofs** (each red, then reverted): drop `target_connection_transport_shape`; delete the highest
      `wire_generation` of a placement in `VDB` (R-1); restore the `NOT NULL` leg `<host_key_algorithm> IS NOT NULL` on the `ssh-docker`
      shape (the legacy-row test red); add a `payload
      bytea` column to `agent_command`; relax the `config_hash` CHECK to accept `sha256:`; drop
      `agent_command_one_live_per_intent`; drop `agent_identity_agent_id_global`; grant `UPDATE` on `agent_event`.
      Record each in Results.
- [ ] **Step 6: Commit** `feat(dp): sm_data VDA/VDB — agent gateway state over our placement and target tables (plan 015 T1.1)`.

**Lane time:** ≈ 4 h. **Why REWRITE:** [An §2.2], [An §5 items 1–3].

### T1.2 — `agent-gateway` module, the proto move, the boundary proofs (lane B; HF13, HF14 first, HF15)

**Files:** TAKE `backend/agent-gateway/**` at `87e338e1`; move `go-agent/proto/agentgateway/v1draft/agent_gateway.proto`
→ `contracts/agent/v1/agent_gateway.proto`; new `contracts/agent/v1/buf.yaml`, `baseline.json`,
`check_proto_append_only.py`, `contracts/run-checks.sh` (new step, numbered **18**; the proto-step hunk only) — **one agent-PR commit**; `backend/pom.xml` (module +
`grpc.version`/`protobuf.version` management); `arch-tests/pom.xml`; new
`arch-tests/src/test/java/com/solace/sm/arch/AgentGatewayBoundaryTest.java` and fixtures under the existing
`ExcludeArchFixtures` convention; new `arch-tests/.../AgentGatewayDependencyAllowListTest.java` — the backend-PR commit.

**The buf workspace (P15-7, verified 2026-10-08 against buf's own documentation):** in a v2 `buf.yaml`, *"The path to
a directory containing Protobuf files … must be defined relative to the workspace root (the directory that contains the
`buf.yaml` file)"* and *"All `path` values must point to directories within the workspace"*
(<https://buf.build/docs/configuration/v2/buf-yaml/>), so `go-agent/buf.yaml` cannot name `../contracts/agent/v1`.
The workspace therefore moves **to the contract**: `contracts/agent/v1/buf.yaml` is `version: v2`, `modules: [{path:
.}]`, `breaking: {use: [FILE]}` (the branch's `lint: STANDARD` is not carried: the package
`solace.sm.agentgateway.v1draft` in `contracts/agent/v1/` would fail `PACKAGE_DIRECTORY_MATCH`, and lint is not a gate
here — recorded, not hidden). Generation stays in `go-agent`: for `buf generate`, *"The paths in the template and the
-o flag will be interpreted as relative to the current directory"* (<https://buf.build/docs/reference/cli/buf/generate/>),
so `(cd go-agent && buf generate ../contracts/agent/v1 --template buf.gen.yaml)` writes into
`go-agent/internal/wire/pb` with `paths=source_relative` — the same output path as today, because the `.proto` sits at
its module root in both layouts. That the positional input resolves from the current directory is measured in T1.4
Step 2, not assumed.

- [ ] **Step 1: Write the failing tests.** `AgentGatewayBoundaryTest` carries the four rules
      (`agentGatewayUsesDpApiOnly` incl. no `cp..`, `agentGatewayHoldsNoDatasource`, `dpNeverImportsAgentGateway`,
      `onlyAgentGatewayTouchesGrpc` — the branch's `dataPlaneAndConsoleDoNotTouchGrpc` renamed and widened) **and one
      planted fixture per rule** that must be reported:
      ```java
      @Test void dpApiOnlyRuleCatchesAPlantedDpCoreImport() {
          AssertionError e = assertThrows(AssertionError.class,
              () -> AgentGatewayBoundaryTest.agentGatewayUsesDpApiOnly.check(classesOf(PlantedAgwImportsDpCore.class)));
          assertThat(e.getMessage()).contains("PlantedAgwImportsDpCore");
      }
      ```
      `AgentGatewayDependencyAllowListTest` reads `agent-gateway/pom.xml` and fails on any `<dependency>` outside
      `{dp-api, grpc-netty-shaded, grpc-protobuf, grpc-stub, protobuf-java-util, spring-boot-starter,
      spring-boot-actuator, micrometer-core, test scope}` (P15-15: `spring-boot-actuator` is needed — `AgentGatewayServer`
      imports `org.springframework.boot.actuate.health.HealthIndicator`, measured) — and on any datasource/JDBC/Flyway
      artifact **and on `javax.annotation-api`** (`OR-127` L1). `check_proto_append_only.py` self-test: a planted
      renumbered field fails.
- [ ] **Step 2:** Run `mvn -o -f backend/pom.xml -pl arch-tests verify` → FAIL (module absent).
- [ ] **Step 3:** TAKE the module's sources; write its `pom.xml` by hand from the branch's (Global constraint 7):
      `protoSourceRoot` → `${project.basedir}/../../contracts/agent/v1`, **no `javax.annotation-api`**, the gRPC plugin
      run with `@generated=omit` (`OR-127` L1), and the two executables' coordinates exactly as T0.2's check pins.
      Add the module to the reactor **without** a launcher import (composition is T3.2). Do **not** widen
      `jdbcIsConfinedToPersistencePackages`; do **not** touch `RUNTIME_ADAPTER_IMPLEMENTATIONS` (T3.1). Land the gRPC
      set's licence lines and L3's BoringSSL notices (HF13), **and `OR-130` L-a/L-b's five exact lines, each in this commit** (the
      build-tool-only ones cannot land earlier: `check-licences.py` rule 4 fails a stale build-tool approval), under a
      `# OR-130 = owner ruling 2026-10-08 (L-a, L-b)` authority comment:
      `org.codehaus.mojo--animal-sniffer-annotations--1.24=Owner-approved (shipped)`,
      `javax.annotation--jsr250-api--1.0=Owner-approved (build-tool-only)`,
      `org.eclipse.sisu--org.eclipse.sisu.inject--0.3.3=Owner-approved (build-tool-only)`,
      `org.eclipse.sisu--org.eclipse.sisu.plexus--0.3.3=Owner-approved (build-tool-only)`,
      `org.codehaus.plexus--plexus-utils--3.1.0=Owner-approved (build-tool-only)`. The three coordinates of the licence audit §D item 3
      (gson 2.13.2, checker-qual 3.42.0, listenablefuture 9999.0-empty-…) need the owner's confirmation **before T1.2 merges** (§10 item 20).
      **`AG-D13` ("never `contracts/`; superseded the day `contracts/` carries the real proto") is superseded by its own exit condition
      here (`OR-126` (A1)):** T1.2's agent commit adds the `AG-*` changelog row "`AG-D13` superseded by its exit condition, `OR-126` (A1),
      plan 015 T1.2" and states that the moved file is normative under `contracts/`' single-writer rule (the register row lands with
      T1.2, not before).
- [ ] **Step 3a: Prove L1 in this first build.** The generated `*Grpc.java` files carry no `javax.annotation`
      import (`grep -rL` over `target/generated-sources` lists every one of them), and `mvn dependency:tree -pl
      agent-gateway` shows no `javax.annotation-api`. Find out from the plugin's own documentation or source **at
      0.6.1** (`I2`) how a plugin parameter is forwarded and record it. ⚠️ **If 0.6.1 cannot forward
      `@generated=omit`, stop: the item returns to the owner (`OR-127` L1); no other plugin, version or annotation
      artifact is substituted without a ruling.**
- [ ] **Step 4:** `mvn -o -f backend/pom.xml -pl agent-gateway,arch-tests -am verify` → PASS, including the branch's
      32 unit tests (they compile against the branch's `dp-api/agw` types: TAKE those **37** types here unchanged,
      T2.1a adapts them) and the licence gate; `test-licence-gate-present.sh`, `check-protoc-executables.py` → PASS;
      `./contracts/run-checks.sh --strict` → PASS.
- [ ] **Step 5: Mutation proofs:** add `import com.solace.sm.cp.api…` to `AgentGatewayService` (rule red); add
      `spring-jdbc` to the module pom (allow-list red); add `javax.annotation-api` (allow-list red); delete the licence
      plugin block on a scratch copy (`test-licence-gate-present.sh` red); renumber a proto field (check red). Revert each.
- [ ] **Step 6: Commit** (two, §6.4) `feat(contracts): agent wire in contracts/agent/v1 with buf workspace and append-only check (plan 015 T1.2)`
      and `feat(agw): agent-gateway module and boundary rules with planted proofs (plan 015 T1.2)`.

**Lane time:** ≈ 4 h. **Why ADAPT:** the rules exist but were never proven to fire [An §3.2, §5 item 6].

### T1.3 — `sm_control` `VCA`, the catalog at 47 (lane C; HF6, HF9)

**Files:** `cp-core/src/main/resources/db/migration/sm_control/V<VCA>__agent_registration_and_verbs.sql`,
`CHECKSUMS.sha256`, anchors; `cp-api/.../authz/Permission.java`; `PermissionCatalogMirrorTest`;
`cp-core/.../authz/BuiltInRoleSeedIT` (47 / 26 / 9); `contracts/openapi/validate-openapi.py` (count 43 → 47);
`ControlPlanePersistenceConfiguration.java` (readiness for `VCA` — **it must still require the V020 markers** (`no-actor HIERARCHY_NODE_RENAMED`, `operator-cli`, `ROLE_BINDING_REVOKED`, `INSTANCE_UNDECLARED`, `DASHBOARD_RESET`, `document_sha256`, `V020_AUDIT_FUNCTION_QUERY`) **plus its own**); `SmControlMigrationMainTest`,
`ControlPlaneConfigurationIT`, `GovernanceSchemaIT`; `D10-G2-APPROVAL-2026-09-15.md` **§1.10.1** (the counted rows, in
the commit that mirrors them, as §1.9.1 did).

**Content of `VCA`** (**V021**). **This is a summary; `schema-015-draft.sql` is authoritative** (W0 review finding 11):
- `sm_control.agent_registration (organization_id, agent_id UNIQUE, environment_id, catalog_solution_id, package_version,
  target_name, allowed_config_root, allowed_container_prefix /* the envelope the enrolment creates its target with;
  immutable; the grammars of target_connection's */, target_id NULL /* set at consumption */,
  enrollment_token_digest bytea UNIQUE, enrollment_csr_digest, enrollment_used_at, expires_at, capabilities text[],
  agent_revocation_epoch, fleet_revocation_epoch, lease_ttl_seconds, audience, status, certificate_chain_der,
  trust_bundle_pem, valid_from, valid_to, created_by uuid NOT NULL REFERENCES principal, created_at)` — the branch's
  V014+V015 merged, **with scope** (`I5`, `OR-27`), **with the target envelope** (P15-2), and **without** `target_id
  text`/`binding_ids[]` (`AGW-D24`). The token digest stays SHA-256 of a 256-bit random token — ⚠️ §10 item 9.
- Rows 44–47 into the catalog with role grants (Administrator +4, Operator `agent.view`, `agent.drain`, Viewer
  `agent.view`); consequence level and scope as §10 item 4 rules (proposed: enrol/revoke HIGH, drain STANDARD, view
  read; environment scope).
- `sm_control.target.target_transport` widened to `('ssh-docker','agent')` (G-2: V013 admits `ssh-docker` only, and the CP target row
  written at enrolment is transport `agent`).
- `change_audit.actor_agent_id uuid NULL`; the `audit_operation` CHECK and the trigger's known-operation list gain
  **`AGENT_ENROLMENT_ISSUED`** and **`AGENT_REVOKED`** (principal acts: token issue and revoke write `agent_registration`; G-3),
  **`AGENT_ENROLLED`** (token consumption, certificate, the CP target row — T2.2, T3.5), **`AGENT_INSTANCE_REGISTERED`**
  (the `OR-27` governance node — T4.2) and, only if T2.2 Step 0 finds a lease-time write, **`AGENT_LEASE_ISSUED`**;
  `record_change_audit()` replaced whole **from V020's body** (`OR-131` Q1: `HIERARCHY_NODE_RENAMED` in the first gate's no-actor list
  plus a second gate allowing it only on `organization` from client address `operator-cli`; V020's two edits and its marker comment kept
  **verbatim**, or the readiness stage refuses to start) with the agent edits applied (`AGW-EDIT 1..7` in the draft), plus reading `sm.audit.actor_agent`: a write under one of those
  operations may carry an agent actor instead of a principal; every other operation still fails closed without a
  principal (`OR-25`) — `BE-D70` as widened (P15-8), §10 item 5. The correlation id and client address stay mandatory
  for these operations; on the agent path they come from `AgentRequestOrigin` (gateway-minted correlation id, the gRPC
  peer's address), never from an agent-supplied field.

- [ ] **Step 1: Failing tests:** `BuiltInRoleSeedIT` expects 47/26/9; `PermissionCatalogMirrorTest` expects the four
      constants; new `AgentRegistrationSchemaIT`: **a no-actor `HIERARCHY_NODE_RENAMED` on `organization` from `operator-cli` is still accepted after `VCA`
      (and refused on any other table or client address)**; a registration without `organization_id`/`environment_id`/envelope →
      `23502`; each agent operation with an agent actor, a correlation id and a client address → accepted; with neither
      actor → `OR025`; with an agent actor but no client address → `OR025`; any other operation with only an agent actor
      → `OR025`.
- [ ] **Step 2:** Run `mvn -o -f backend/pom.xml -pl cp-core verify -Dit.test='BuiltInRoleSeedIT,AgentRegistrationSchemaIT'` → FAIL.
- [ ] **Step 3:** Write `VCA`; mirror the catalog; update readiness and pinned tests.
- [ ] **Step 4:** Run → PASS; `./contracts/run-checks.sh --strict` (check 11 sees 47).
- [ ] **Step 5: Mutation proofs:** let a non-agent operation accept an agent actor (red); drop Viewer's `agent.view`
      (seed IT red); drop the envelope's NOT NULL (schema IT red).
- [ ] **Step 6: Commit** `feat(cp): sm_control VCA — scoped agent registration with target envelope, catalog rows 44-47 (OR-126 A8) (plan 015 T1.3)`.

**Lane time:** ≈ 3 h. **Why REWRITE:** [An §2.2], [An §5 item 3].

### T1.4 — The Go agent, as-is (lane D; HF14 second writer; agent PR)

**Files:** `go-agent/**` at `87e338e1` (TAKE), then `go-agent/buf.yaml` (deleted); regenerated
`go-agent/internal/wire/pb/*.pb.go`; `go-agent/THIRD-PARTY-LICENCES.md` (T0.2 rows); new
`go-agent/scripts/check-licences.sh`.

- [ ] **Step 1:** `git checkout 87e338e1 -- go-agent` on the lane branch (agent PR, §6.4).
- [ ] **Step 2:** Rebase onto lane B's proto-move commit; delete `go-agent/buf.yaml`; run `(cd go-agent &&
      PATH=$HOME/go/bin:$PATH buf generate ../contracts/agent/v1 --template buf.gen.yaml)` after asserting
      `PATH=$HOME/go/bin:$PATH protoc-gen-go-grpc --version` prints `1.5.1` (Homebrew's is 1.6.2); record `buf` **1.73.0** as the pinned dev
      tool in the go-agent docs; `git diff go-agent/internal/wire/pb` must show at most the protoc header line of `agent_gateway.pb.go` (v4.25.5 → (unknown): buf runs no protoc); `agent_gateway_grpc.pb.go` and every other byte identical. (If `buf` resolves the positional input otherwise, record it and use the form that
      works — the output path is the measure.) Anything else stops the task.
- [ ] **Step 3: The Go gate:** `go vet ./...`; `go test ./...`; `go test -tags integration ./internal/agent/ -run
      Integration -v`; with Docker, `go test -tags docker ./internal/runtime/docker/ -run E2E -v`. Record counts and
      versions in Results. A failure is fixed in its own commit with the failing test named, never skipped.
- [ ] **Step 4:** `go-agent/scripts/check-licences.sh` fails if `go list -deps -f '{{with .Module}}{{.Path}}
      {{.Version}}{{end}}' ./cmd/sm-agent | sort -u` (minus the main module) names a module or version not in
      `THIRD-PARTY-LICENCES.md` (P15-12: the shipped binary's set, not `go list -m all`); planted unknown module →
      fails.
- [ ] **Step 4a:** `go-agent/CLAUDE.md` (taken as-is) cites `AG-14`–`AG-21`, a spec and a plan 006 that exist on no branch (review W0-14).
      Correct those dangling citations **in their own commit** (agent PR): `AG-12`/`AG-13` are now defined in `docs/design/go-agent/design.md`
      §13.2; `AG-14` has no definition (`AG-D22` says so); drop the rest or point them at `AG-D28`.
- [ ] **Step 5: Commit** `feat(go-agent): take the agent from dp-agent-integration@87e338e1 as-is; generated from contracts/agent/v1 (plan 015 T1.4)`.

**Lane time:** ≈ 2 h. **Why TAKE:** [An §1.4, §6.1 (a)].

---

## W2 — signatures, CP authority, wire, packaging

### T2.1a — Every `dp-api/agw` signature, and `agent-gateway` compiling against them (lane A; HF18, HF15 first; merged first)

**Files:** `dp-api/.../dp/api/agw/*` per [§4.2.1](#421-the-37-dp-apiagw-types) (adapt, drop, add); the mechanical
compile fixes in `agent-gateway/src/**` (renames, record shapes; **no behaviour change** — T3.2 owns behaviour) and in
any `launcher` file present at W2's start that names an `agw` type; `port-matrix.md` re-checked against it.

**Interfaces — produces** (the full list in §4.2.1; the ones every later lane leans on):

```java
public record AgentSessionRef(UUID agentId, long fencingEpoch) {}           // no tenant (AGW-D3); dp-core derives it
public record AgentRequestOrigin(UUID correlationId, String clientAddress) {} // gateway-minted; OR-25 on the agent path
public interface AgentSessionPort {
    AgentSessionRef claim(String certificateThumbprint, AgentRequestOrigin origin); // refuses revoked/unknown thumbprints
    void heartbeat(AgentSessionRef ref, Instant receivedAt);
    void close(AgentSessionRef ref, CloseReason reason);
    boolean liveFor(UUID agentId, PlacementRef placement);              // open session AND unexpired lease holding it
}
public interface AgentCommandPort {
    AgentCommandRef enqueue(AgentCommandIntent intent);   // keyed by intent.intentKey(); returns the existing command (P15-3)
    void supersedeOutstanding(PlacementRef placement, String keepIntentKey); // QUEUED/OFFERED of other keys only
    List<CommandNotice> pendingFor(AgentSessionRef ref, int limit);
    CommandOffer pull(AgentSessionRef ref, CommandId id);  // bytes from AgentCommandPayloadSource; never stored
    void received(AgentSessionRef ref, CommandId id, long wireGeneration);
    CommandAckResult ack(AgentSessionRef ref, CommandId id, AgentAck ack);
    Optional<CommandCompletion> completion(CommandId id);
}
public interface AgentObservedStatePort {                  // T2.1b stub: Optional.empty(); T3.3 implements
    Optional<AgentObservation> latest(TenantId t, PlacementRef placement); // liveness, health, plain SHA, startedAt
}
public interface AgentDiscoveryPort {                      // T2.1b stub: every item REFUSED agent.discovery.unavailable; T4.1
    List<DiscoveryResult> report(AgentSessionRef ref, List<DiscoveredContainer> items, AgentRequestOrigin origin);
}
public interface AgentTelemetryDeclarationsPort {          // T2.1b stub: no declarations; T3.4 implements
    List<TelemetryDeclaration> forAgent(AgentSessionRef ref);
}
public interface AgentTelemetryPort {                      // renamed from the branch's TelemetryIngestPort
    AgentTelemetryResult ingest(AgentSessionRef ref, AgentTelemetryBatch batch);
}
```

`CommandOffer` carries the bytes for **one** in-memory hop to the gateway; its `toString` prints neither bytes nor
digest. `AgentCommandPayloadSource` is an interface in `dp-core` implemented in T3.1; T2.1b ships a fake.

- [ ] **Step 1:** Write the signatures and the records; fix `agent-gateway`'s compile mechanically.
- [ ] **Step 2:** `mvn -o -f backend/pom.xml -pl dp-api,agent-gateway,arch-tests -am verify` → PASS (the 32 taken unit
      tests still pass on the reshaped types, or a test that asserted a removed shape is recorded and moved to T3.2).
- [ ] **Step 3: Commit and push to the lane base** `feat(dp-api): every agent-gateway port signature, declared once (plan 015 T2.1a)`
      — T2.2 branches from this commit; T2.3, T2.4 and every W3 lane build on it.

**T1.2 review note (2026-10-09, findings 6):** (I6) `AgentCommandPort.enqueue(String agentId, CommandPayload)` is documented as durable queue admission yet `CommandPayload` carries `byte[] payload`: the declared `enqueue` is **byte-free** (command identity and the keyed hash only; bytes are handed over in memory by T3.1). (Global constraint 9) the records `EnrollmentRequest.token`, `LeaseGrant.nonce` and the plain-digest `configSha256` in `CommandNotice`/`CommandPayload` use the default `toString` and would print secrets: override `toString` to redact each, with a test asserting none of them ever appears. (I5) `TelemetryBatch.Sample.labels` and `ReportItem.bindingId` are agent-supplied: the javadoc says so, and T3.3, T3.4 and T4.1 never resolve tenant or instance from them.

**Lane time:** ≈ 1 h. **Why first:** P15-1 — nothing after it compiles against a shape that is about to change.

### T2.1b — Persistence, contract kits, stubs, fixtures (lane A; HF4, HF11, HF15)

**Files:** new `dp-api/src/test/java/.../agw/kit/Abstract*PortContractTest.java` (one per mutating port: session,
enrolment, lease, command, report, audit, telemetry, discovery) packaged in `dp-api`'s test-jar; new
`dp-core/.../apply/persistence/agent/Jdbc{AgentSession,AgentEnrollment,AgentLease,AgentCommand,AgentReport,AgentAudit,
AgentIdentity,AgentTelemetryWatermark}Repository.java` (**no `AgentDiscovery` repository**: the stub is the port until T4.1; Report and TelemetryWatermark are persistence helpers, not ports) (REWRITE of `JdbcAgentGatewayPorts`);
`dp-core/.../dp/agent/AgentGatewayPortsConfiguration.java` (`@Import` in `DataPlaneConfiguration`, HF4; it imports `apply/persistence/agent/AgentGatewayPersistenceConfiguration`, which holds the JDBC beans there because `jdbcIsConfinedToPersistencePackages` forbids Spring JDBC anywhere else) including the
**contracted stubs** of `AgentObservedStatePort`, `AgentDiscoveryPort`, `AgentTelemetryDeclarationsPort` (each its own
class, which the implementing task fills in, so no bean is ever swapped); kit subclasses
`dp-core/src/test/.../agent/Jdbc*PortContractIT.java`; `InventoryFixtures.agentTarget(...)`, `agentPlacement(...)`
(HF11, P15-18); new arch rules in `PlaneBoundaryTest` (every `dp.api.agw` port interface has a kit; planted fixture)
and in `TenantEnumerationBoundaryTest` (`AGENT_IDENTITY_RESOLUTION =
"…JdbcAgentIdentityRepository"` named as the one unbound read, and a repository-call rule — like
`NON_TERMINAL_CALLERS` — holding `resolveByThumbprint` to the session and enrolment repositories; planted second
caller red, P15-19).

- [ ] **Step 1: Failing tests:** the kits (each port: idempotent by request id; a stale fencing epoch refused before
      any read; a revoked identity refused; tenant never accepted from the caller); `JdbcAgentCommandRepositoryIT#
      pullStoresNothing` (row bytes unchanged before/after a pull, by `pg_column_size`);
      `#enqueueReturnsTheLiveCommandForTheSameIntentKey`, `#enqueueReturnsTheSucceededCommandRatherThanMintingAnother`,
      `#aRetryAfterAFailedAckMintsANewGeneration`, `#supersedeLeavesDeliveredCommandsAlone`; each stub's contracted
      answer.
- [ ] **Step 2:** Run `mvn -o -f backend/pom.xml -pl dp-api,dp-core -am verify -Dit.test='*Agent*PortContractIT,JdbcAgent*IT'` → FAIL.
- [ ] **Step 3:** Implement the repositories (statements ported from `JdbcAgentGatewayPorts` by intent, re-keyed on
      `tenant_id` resolved from `agent_identity`); `AgentGatewayPortsConfiguration`; the fixtures.
- [ ] **Step 4:** Run → PASS; the branch's `AgentReportIT`/`AgentRevocationIT` scenarios ported as kit cases → PASS.
- [ ] **Step 5: Mutation proofs:** skip the epoch check in `claim`-guarded writes (kit red); take tenant from the batch
      (kit red); let `enqueue` mint when a live command exists (IT red); store the offer bytes in a new column (schema
      IT from T1.1 red, then revert both).
- [ ] **Step 6: Commit** `feat(dp): agent-gateway ports over VDA with contract kits and contracted stubs (plan 015 T2.1b)`.

**T2.2 review notes (2026-10-10).** (a) `LeaseIssuer`, `EnrollmentCertificateIssuer` and `AgentRegistrationSource` have **exactly one implementation tree-wide**, the launcher's named adapters (`CompositionBoundaryTest` fails on a second concrete class, anywhere in main). So `dp-core` contexts that need them take them **optionally** (`ObjectProvider`/`@Autowired(required = false)`) or in tests use **test-scope** fakes in a `dp-core` test source set; never a main-source stub or default bean. (b) The DP-side enrolment idempotency key is the **agent id** (or the CP's token/CSR arbiter), **never `EnrollmentRequest.requestId`**: that id is minted per RPC (`"enroll-" + correlation id`), so a retried enrolment carries a new one by design.

**Lane time:** ≈ 4 h. **Why ADAPT/REWRITE:** [An §1.3, §3.1 item 5, §5 items 3, 5].

### T2.2 — CP enrolment, the CA, leases; the launcher's three adapters (lane B; HF13, HF15 second, HF21; branched from T2.1a)

**Files:** TAKE `cp-core/.../cp/agent/{AgentCertificateAuthority,Pkcs12AgentCertificateAuthority}.java`,
`cp-core/.../cp/config/AgentGatewayCaProperties.java`, `cp-core/pom.xml` (`bcpkix-jdk18on 1.86`); `backend/pom.xml`
`dependencyManagement` (Bouncy Castle pins, Step 4); ADAPT `cp-api/.../cp/api/agent/*`,
`cp-core/.../cp/agent/{AgentRegistry,ControlPlaneAgentGatewayService}.java`, `cp-core/.../cp/persistence/JdbcAgentRegistry.java`,
`ControlPlaneConfiguration.java` (bean), `ControlPlaneAgentGatewayServiceTest`; TAKE→ADAPT
`launcher/.../{AgentGatewayControlPlaneAdapterConfiguration,ControlPlaneLeaseIssuer,ControlPlaneEnrollmentCertificateIssuer,
ControlPlaneRegistrationSource,FailureDiagnostics}.java`, written against T2.1a's signatures;
`arch-tests/.../CompositionBoundaryTest.java` (the launcher may adapt exactly these three `cp-api` types to `dp-api`;
planted fourth adapter fails).

- [ ] **Step 0 (measure):** (a) how plan 013 registers a target's CP governance row (`TargetRegistration#register`,
      idempotent on organization and name, `target.create` checked at the scope) — T3.5 reuses its write path for the
      agent target; (b) whether issuing or renewing a lease writes any `sm_control` row (the branch's `JdbcAgentRegistry`
      updates only at token consumption and certificate issue, measured) — if none, `AGENT_LEASE_ISSUED` is not added
      to `VCA` and a test proves a lease writes no `change_audit` row.
- [ ] **Step 1: Failing tests:** `ControlPlaneAgentGatewayServiceTest` — token issue (service level; HTTP is T4.3)
      stores the envelope and target name, refuses an envelope outside the `registerTarget` grammars, and is audited
      with the issuing principal; enrolment refuses an expired/consumed token (`TOKEN_CONSUMED`), replays the same
      token+CSR to the same certificate (V015's behaviour), signs only with an operator keystore (no keystore → fail
      closed at boot); every CP write at consumption runs under `AGENT_ENROLLED` with `actor_agent_id`, the
      gateway-minted correlation id and the peer address from `AgentRequestOrigin` (P15-8); a lease's claims name agent,
      organization, environment, target and **the placement set the DP passed**, never one the CP invented; the lease's `verb_ceilings`
      carry `preflight` 30 s, `apply` 60 s, `restart` 120 s, `stop` 60 s and **`rematerialize` 60 s** (R-7: a missing key is a zero
      ceiling and every REMATERIALIZE step fails); nonce
      derivation as built (`AGW-18`). `CompositionBoundaryTest`: planted fourth adapter red.
- [ ] **Step 2:** `mvn -o -f backend/pom.xml -pl cp-core,launcher,arch-tests -am verify -Dtest='ControlPlaneAgent*,CompositionBoundaryTest'` → FAIL.
- [ ] **Step 3:** Adapt. The adapters carry `AgentRequestOrigin` into the CP audit context.
- [ ] **Step 4: Bouncy Castle (P15-13).** Revision 1 said "no other Bouncy Castle artifact resolved"; that is false:
      `dp-core` resolves `bcpkix 1.80`/`bcutil 1.80.2` through `sshj` (`licence-exceptions.txt:67-71`), and the launcher
      depends on both `dp-core` and `cp-core`. Pin `bcprov-jdk18on`, `bcpkix-jdk18on`, `bcutil-jdk18on` at **1.86** in
      `backend/pom.xml` `dependencyManagement`; assert with `mvn -o dependency:tree -pl launcher,dp-core` that exactly
      1.86 of each resolves; the existing SSH connector suites (the `sshj` users) must stay green on 1.86; the 1.80 /
      1.80.2 exception lines are removed only if no module resolves them any more (measured), and `OR-127` L2's two
      exact-coordinate lines are added in this commit.
- [ ] **Step 5: Mutation proofs:** accept a token after `expires_at`; let the CP add a placement id not in the request;
      write the consumption without an agent actor (`OR025` red); drop the BC pin (tree assertion red).
- [ ] **Step 6: Commit** `feat(cp,launcher): agent enrolment, operator-keystore CA, CP-issued leases; three named adapters (OR-126 A3/A4, BE-D69) (plan 015 T2.2)`.

**T1.2 review note (2026-10-09, finding 7):** the taken `AgentGatewayService.java:110-112` derives the enrolment request id as `UUID.nameUUIDFromBytes(token + ":" + csrHex)` — an unkeyed MD5 of the token, persisted as the replay key. Derive it from the CP's stored token digest (`OR-128` (9)) or mint it server-side; the token itself never feeds a persisted key.

**Lane time:** ≈ 4 h. **Why TAKE/ADAPT:** [An §1.3 rows mTLS/Enrolment/Lease], `OR-126` A3/A4.

**T2.2 note from the W4 lane B review (2026-10-11, NIT 8).** `AgentEnrolmentService.issue` signals its refusals with bare `IllegalStateException` (name taken) and `IllegalArgumentException` (everything else, including the `DataIntegrityViolationException` it rewrites to "not loaded"). T4.3's orchestrator maps the first to `409 CONFLICT` and the second to `422`, so an unexpected CHECK or trigger refusal would be reported as one of those. The next task that touches this service should replace both with a typed cp-api refusal (an enum on an `AgentEnrolment.Refused` exception) and map by reason.

### T2.3 — The six wire additions and two hardening edits, in Go and in the proto (lane C; HF14; agent PR)

**Files:** `contracts/agent/v1/agent_gateway.proto` + `baseline.json` (field numbers from `proto-amendments-015.md`, incl. the sixth
addition `HealthProbe` as `TelemetryDeclarations` field 5 and `HealthObservation` 4-6); `go-agent/internal/wire/pb/*` (regenerated);
`go-agent/internal/runtime/docker/docker.go` (the declared-probe GET on the host loopback per placement with Docker health as a hint,
`StartedAt` read, discovery scan of containers
carrying `com.solace.sm.instance`, reporting facts per T0.3 Step 1 (d), `AG-D26`); `go-agent/internal/commands/*`
(precondition check before write; the payload spool per `OR-129`/`AG-D29`: modes, purge at terminal, sweep at start); `go-agent/internal/telemetry/declare/*`
(declarations from the wire; the file flag stays for development only); `go-agent/internal/fakegw/scenarios/*` (one
scenario per addition); `go-agent/internal/msg/*` (`LogValue` without `config_sha256`); `docs/design/go-agent/decisions.md`
is T0.1's. The Java side is **not** touched here (the backend compiles the regenerated proto in W3; additions are
append-only, so W2's backend gate is unaffected).

- [ ] **Step 1: Failing Go tests:** the **declared-probe test against a stub HTTP server** (`OR-130` D-4): `200` with an
      actuator body `{"status":"UP"}` → facts `http_status=200, actuator_status=UP`; `503` → `http_status=503`; a closed port →
      `failure=REFUSED`; a slow server → `failure=TIMEOUT`; **no `HealthProbe` declared → nothing is probed** (the stub sees zero
      requests); Docker's health state rides along informationally and never changes the facts; `commands` — an offer whose `expected_current_sha256` differs
      from the file writes nothing and acks `REFUSED` with `reconcile.target.moved`'s code; `discovery_test.go` — two
      labelled containers produce two items with their mounts and published ports, an unlabelled one none, a
      re-created container reports its new `container_id` under the same name; `declare` — a `TelemetryDeclarations`
      replaces the binding's set atomically; `msg_test.go` — `LogValue` and `String` of an offer contain neither the
      payload nor its digest; **the `OR-129` spool (`AG-D29`)**, `commands_test.go`: spool file mode `0600` and directory `0700`; the
      file is purged on **each** terminal outcome (ACKED, REFUSED, RENDER_CHANGED, TARGET_MOVED, superseded, deadline); the start-up
      sweep deletes terminal and unknown files and **keeps** a live `RECEIVED`/`STAGED` file; after a restart the command resumes from
      the spool **without** `CommandPull`.
- [ ] **Step 2:** `go test ./...` → FAIL.
- [ ] **Step 3:** Append the fields (numbers from T0.3); regenerate; implement.
- [ ] **Step 4:** Go gate → PASS; `check_proto_append_only.py` → PASS; `scripts/check-licences.sh` → PASS.
- [ ] **Step 5: Mutation proofs:** skip the precondition (test red); **report `HEALTHY` when Docker says healthy but the declared
      probe fails (red, `OR-130` D-4)**; probe when no probe is declared (red, `OR-101`'s no-invention rule); log `config_sha256`
      again (red); **skip the start-up sweep; write the spool file `0644`; skip the purge on `REFUSED` (each red, `AG-D29`)**.
- [ ] **Step 6: Commit** `feat(go-agent,contracts): health, start time, write precondition, discovery facts, pushed declarations; no digest in logs (plan 015 T2.3)`.

**T1.4 review security notes (2026-10-09, carried into this task).** **S1** (the enrolment token in the flag usage text) is fixed in `9434de6f`. **S2:** `go-agent/internal/msg/msg.go:352` `CommandOffer.LogValue` logs `config_sha256`; remove it (Step 1's `msg_test.go` case; Global constraint 2). **S3:** the spool at `go-agent/internal/commands/commands.go:258` needs the `OR-129` modes, the purge at terminal and the start-up sweep (Steps 1 and 5). **S4:** `go-agent/internal/commands/ops.go` lines 50, 79, 99 and 121 send `err.Error()` as the ack detail; add a test that proves no payload content reaches the ack detail through it.

**T1.2 review note (2026-10-09, finding 13):** `check_proto_append_only.py` checks the proto against `baseline.json`, so a hand edit of `baseline.json` that drops entries is unchecked. Add a **superset-of-merge-base** check (HF14, agent PR): the baseline at `HEAD` must contain every entry of the baseline at the merge base with `UAT`, with a planted case that removes an entry.

**Lane time:** ≈ 5 h. **Why REWRITE (append):** [Br §1 rows 7–9, 15, 17].

### T2.4 — `runtime/` packaging (lane D)

**Files:** ADAPT `runtime/launcher/Dockerfile`, `runtime/launcher/bin/{sm-launcher,sm-migrate,sm-healthcheck}`,
`runtime/compose.yml`, `runtime/.env.example`, `runtime/bootstrap/phase0.sh`, `runtime/scripts/build-image.sh`,
`runtime/.gitignore`, `runtime/README.md`.

- [ ] **Step 1:** TAKE the files at `87e338e1`; drop `sm-maintain`, `sm-hash-credential`, `runtime/deploy/*`.
- [ ] **Step 2:** `build-image.sh --check`: restore `rewrite-timestamp=true` **or** record why the branch removed it and
      measure reproducibility twice (same digest) — not assumed [An §1.2].
- [ ] **Step 3:** compose: gateway port, CA keystore as a read-only secret mount, no keystore password in the file
      (`.env.example` placeholders only); `sm-migrate` runs `SmControlMigrationMain` then `SmDataMigrationMain` (which
      runs `sm_metrics`, measured [An §6.5]).
- [ ] **Step 4:** The launcher image's base is `eclipse-temurin:21-jre-jammy` and the agent image's
      `gcr.io/distroless/static-debian13:nonroot` (`OR-127` L4), each pinned by digest; the image carries the NOTICE
      with L3's BoringSSL notices and the NOTICE files of the seven shipped Apache-2.0 Go modules that carry one: `docker/docker`, the four `prometheus` modules, grpc (`NOTICE.txt`) and `yaml.v3` (W1 integration notes, 2026-10-09). The corresponding-source offer is a release-runbook line (T5.2).
- [ ] **Step 5: Commit** `build(runtime): launcher image and compose from dp-agent-integration, reproducibility re-measured (plan 015 T2.4)`.

**Lane time:** ≈ 2 h. `ContainerPackagingIT` runs in T4.4.

---

## W3 — the agent path inside the reactor

### T3.1 — `AgentRuntimeAdapter`, routing, command identity, the cap, the kit (lane A; HF1, HF15, HF16, HF19, HF22)

**Files:** ADAPT `dp-core/.../apply/runtime/agent/{AgentRuntimeAdapter,AgentPlacementCatalog,AgentRuntimeConfiguration}.java`;
new `AgentPayloadHandoff.java`, `AgentCommandPayloadSource.java` (implements T2.1b's fake's interface),
`AgentHashBridge.java`; new `dp-core/.../apply/runtime/RoutingRuntimeAdapter.java`; `RuntimeOperationContext.java` +
`RuntimeAdapter` default overloads + `ApplyExecutor` call sites (re-applied by hand from `28d63140`, intent key not
attempt); `ProductionApplyConfiguration.java` (builds both adapters and the router; one `ApplyExecutor`);
`ApplyWorker.java`/`ApplyWorkerProperties.java` + the claim query (the cap, HF22);
`AbstractRuntimeAdapterContractTest.java` (adds **declared-unsupported** cases: the fixture names operations it
refuses, and the kit asserts the code and that nothing moved); new `AgentRuntimeAdapterContractTest` (over a simulated
agent answering through T2.1's ports with the Go journal's dedupe rule — `commands.go:198-246`: a notice or offer for a
`(binding, generation)` already in the journal re-acks or re-announces under the **recorded** command id and never
re-runs); `RoutingRuntimeAdapterTest`; `AgentCommandCrashRecoveryIT`; `AgentStarvationIT`;
`PlaneBoundaryTest.RUNTIME_ADAPTER_IMPLEMENTATIONS` (+`AgentRuntimeAdapter`, +`RoutingRuntimeAdapter`, message restored
to "extending `AbstractRuntimeAdapterContractTest`"); `HealthPoller` and `CollectionPlanBuilder` (skip `transport =
'agent'` placements; HF19 — T3.4 does not edit them).

**Command identity (P15-3, `BE-D66`).** An agent command is keyed on the apply journal intent's **attempt-free**
`idempotencyKey(id, step, replica)` = `applyId:step:replica` (`ApplyExecutor.java:1556`), never on the attempt.
`enqueue` returns, in this order: the **live** command (`QUEUED`/`OFFERED`/`DELIVERED`) for that key; else the latest
**`ACKED` with success** for that key (its completion is immediate); else a **new** command with a new wire generation
— only on the first execution, or on the engine's bounded retry after a terminal failure, refusal or supersession. A
resumed apply (startup recovery, or the engine moving to the placement's next step) calls `supersedeOutstanding` first:
`QUEUED`/`OFFERED` commands of other keys become `SUPERSEDED` (their pull answers the agent's `ErrSuperseded`);
`DELIVERED` ones are left to their ack or deadline, because the agent holds them. A re-sent command therefore always
carries the **same command id and wire generation**, which is exactly what the agent journal dedupes on.

**Render once (P15-4).** `materializeConfig(replica, generation, config)` already receives the engine's
`RenderedConfig` (`ApplyExecutor.java:727`). The adapter puts its bytes in `AgentPayloadHandoff` keyed by command id
(in memory, bounded by `sm.dp.agent.handoff.max-entries` default 64, removed at ack, refusal, supersession or
deadline; never logged), then enqueues. A pull reads the hand-off; **only on a miss** (SM restarted since enqueue) is
the generation re-rendered through `RenderedConfigSource`, and it is sent only if `hasher.hash(tenant, bytes)` equals
`agent_command.config_hash`; otherwise the pull is refused `agent.payload.render.changed`, the command `REFUSED`, and
the attempt fails with that non-retryable classifier. `AgentHashBridge` keeps an in-memory `(placement, plain SHA) →
keyed hash` map, filled whenever bytes are sent; `status` resolves the reported plain SHA there, and on a miss renders
**at most** the placement's intended and last-applied generations once per process (cached) — never once per poll.

**Fail fast and the cap (P15-6, `BE-D66`).** Before enqueue, `AgentSessionPort.liveFor(agent, placement)`; false →
`agent.session.absent` at once (no wait). While waiting, a closed session or expired lease ends the wait at once with
`agent.session.lost`; the per-operation ceilings (30/60/120 s) apply only while the session is live. The worker claims
at most `sm.dp.agent.max-concurrent-requests` (default `poolSize − 1`, boot-validated `≥ 1` and `< poolSize`; with the
gRPC listener on, `sm.dp.apply.worker.pool-size` must be ≥ 2 — boot refuses otherwise) requests whose placement is on an
agent target; when the cap is reached the claim query skips agent-target requests, so at least one worker thread is
always free for dp-remote. Step 0 measures the claim path (`ApplyClaimRepository`) before editing it.

**Operation map (`BE-D66`, `BE-D67`):**

| Operation | Agent path |
|---|---|
| `replicas` | one replica per placement, `ReplicaRef` = `placement_id` (Q4 (b), `AGW-D25`) |
| `prepare` | `PREFLIGHT` command, blocks to the ack (ceiling 30 s while live) |
| `materializeConfig` | `APPLY` command holding **no bytes**; the agent pulls; the bytes are the hand-off's; the ack's plain SHA-256 is compared with SHA-256 of the bytes sent for that pull; equal → `MaterializeResult(hasher.hash(tenant, bytes), runtimeRef)` (P15-22: `MaterializeResult` is `(writtenHash, runtimeRef)`; `container_started_at` goes to the start observation via the ack report, T3.3, and back through `status().startBaseline()`); unequal → coded failure. Ceiling 60 s, read-back wait 45 s (`AGW-D21`) |
| `rematerializeIfCurrent` | `REMATERIALIZE` command with `expected_current_sha256` = the plain SHA of the generation whose keyed hash is `expectedCurrentHash` (from the bridge, else one render), or empty for absent; no such generation → `reconcile.target.moved` without sending |
| `startOrUpdate`, `stop` | `RESTART` (120 s), `STOP` (60 s); start baseline from the ack's report |
| `status` | through **`AgentObservedStatePort`** (T2.1a; a **fake** in this task's tests, the T2.1b stub in production until T3.3 fills it): liveness, `health` → `HealthSignal`, persisted hash = the bridge's keyed hash for the reported plain SHA, else "matches no known generation"; start baseline from `placement_start_observation` |
| `assessRollback` | `Unknowable` (read-only, contract-legal) |
| `restore` | refused `agent.rollback.unsupported` |
| `readConfig` | default refusal `config.read.unsupported` |

- [ ] **Step 0 (measure):** the claim path and where a transport filter joins it; record the plan in Results.
- [ ] **Step 1: Failing tests:** `AgentRuntimeAdapterContractTest` (the whole kit, `restore`/`readConfig` declared
      unsupported); `RoutingRuntimeAdapterTest#anSshPlacementNeverReachesTheAgentAdapter`,
      `#anAgentPlacementNeverReachesSsh`, `#anUnknownTransportIsRefusedNotDefaulted`; `HealthPollerIT#skipsAgentPlacements`;
      `CollectionPlanBuilderTest#excludesAgentAcquisition`; `ProductionApplyConfigurationTest#exactlyOneApplyExecutor`;
      **`AgentCommandCrashRecoveryIT`** (dp-core, PG14, simulated agent with the journal rule): kill the executor after
      the `RESTART` command is `DELIVERED`, start a new executor over the same database, let it resume → the simulated
      container's restart count is **1**, the apply settles once, no second command row for the intent key; the same
      for `APPLY` (one write); a pre-crash `QUEUED` command of a superseded step is never executed; **render-once
      tests**: one `materialize` renders once (spy on `RenderedConfigSource`: zero calls on the hand-off path); after a
      simulated restart a re-render with an equal keyed hash is sent, an unequal one refused
      `agent.payload.render.changed`; twenty `status` polls render at most twice; **`AgentStarvationIT`**: pool 2, an
      offline agent with three queued applies and one dp-remote apply → the dp-remote apply completes, each agent step
      fails `agent.session.absent` within one poll interval, at most one agent request is ever claimed.
- [ ] **Step 2:** `mvn -o -f backend/pom.xml -pl dp-core,arch-tests -am verify -Dtest='AgentRuntimeAdapterContractTest,Routing*,ProductionApply*' -Dit.test='HealthPollerIT,AgentCommandCrashRecoveryIT,AgentStarvationIT'` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS; the existing `SshDockerRuntimeAdapterContractTest` and `DockerAdapterContractIT` → PASS
      unchanged (the kit additions are opt-in per fixture); the worker's existing pool tests → PASS.
- [ ] **Step 5: Mutation proofs:** route by default to SSH when the transport is unknown (red); put the attempt id into
      the command key (crash IT red: two restarts); re-render on every pull (render-once spy red); drop the fail-fast
      (starvation IT red); persist the rendered bytes into `agent_command` (schema IT red); return the agent's plain
      digest as the materialize hash (kit's `theAdapterReportsBackTheHashItsOwnHasherComputes` red); let the poller
      probe an agent placement (red).
- [ ] **Step 6: Commit** `feat(dp): agent runtime adapter behind per-placement routing; intent-keyed commands, render once, fail fast, capped (BE-D66/67, plan 015 T3.1)`.

**T1.2 review note (2026-10-09, finding 7):** the taken `AgentGatewayService.dpHash` (`:890`) turns the agent's plain hex into `"sha256:" + hex` and stores it, which breaks `OR-32`; the keyed-hash bridge owned here (and in T3.3) is the only form that is stored. (I5) never resolve tenant or instance from agent-supplied `bindingId` or labels.

**T2.1b fix-round notes (2026-10-10), binding on this task:** (a) `pendingFor` must REFUSE the commands the lease cannot authorize (`CAPABILITY_MISSING` or `BINDING_NOT_IN_LEASE`, design §4.6) before it sends any notice for them; T2.1b's repository silently filters them. (b) After an SM restart a `CommandNotice` carries no plain digest (`AgentCommandPayloadSource#plainDigestFor` is empty); the agent compares at `pull`. (c) The 30-day re-enqueue edge: V029 retention deletes final commands older than 30 days except the highest generation of each (agent, placement); an apply resumed after that gap whose intent key matches a deleted succeeded command mints a NEW command (a new generation) instead of returning the old success, and the engine must treat that as a re-execution. `AGW-D19` item 3 / design §4.6: `issue` is refused whenever a lease row exists (`AGW-D19`'s clarification of 2026-10-10).

**T2.3 review note (2026-10-10, integrator), binding on this task:** a `RECEIVED` command resumed from the agent's `OR-129` spool is dispatched on the first lease grant after an outage, before the data plane can send `SUPERSEDED`; the agent's `STALE_GENERATION` guard covers only generations below one it has already applied. This task decides, and tests, whether the data plane **holds** such a command (no lease grant carries a placement with a superseded `RECEIVED` command until it is reconciled) or **reconciles** it after the fact (the late ack of a superseded generation is recorded and the newer generation re-applied), and states the window the choice leaves.

**Lane time:** ≈ 6 h. **Why ADAPT:** [An §3.1 items 2–5], [Br Q6, Q8, Q9].

### T3.2 — The gateway service on the amended wire; launcher composition; G-A0 (lane B; HF17)

**Files:** ADAPT `agent-gateway/.../AgentGatewayService.java` (≈ 900 lines), `AgentGatewayConfiguration`,
`AgentGatewayProperties`, `AdmissionLimiter`, `AgentGatewayManagementServer`, `AgentGatewayMetrics` and their 32 unit
tests; new `AgentGatewayRetainsNoPayloadTest`; `launcher/.../SolutionManagerConfiguration.java`,
`application.properties` (HF17); `launcher/src/test/.../AgentGatewayHandshakeIT.java` (the G-A0 client).

**T2.4 review addition (2026-10-10):** `application.properties` must map the environment names `runtime/compose.yml` sets: `SM_AGENT_GATEWAY_{ENABLED,PORT,READINESS_PORT,CERT,KEY,TRUST,ENDPOINT}`, `SM_AGENT_CA_{KEYSTORE,KEY_ALIAS,KEYSTORE_PASSWORD}` (the password variable is filled by `sm-launcher` from `SM_AGENT_CA_KEYSTORE_PASSWORD_FILE`), `SM_SHUTDOWN_TIMEOUT` and the readiness port; compose sets no `SM_DP_AGENT_RUNTIME_ENABLED` (global constraint 5).

- [ ] **Step 1: Failing tests:** the branch's `AgentGatewayServiceTest` cases on the new ports, plus: every RPC mints an
      `AgentRequestOrigin` whose client address is the gRPC peer's IP literal (the transport attribute's name verified
      at grpc-java 1.68.1, `I2`), port stripped, matching `^[A-Za-z0-9:._%-]{1,64}$`, and never an agent-supplied value;
      a `DiscoveryReport` is forwarded to `AgentDiscoveryPort` (declared T2.1a, the T2.1b stub answers) and its
      per-item result returned; `TelemetryDeclarations` from `AgentTelemetryDeclarationsPort` are sent after
      `SessionAccept` and on change; a revoked identity mid-renewal closes every stream (the branch's BLOCKER
      regression [An §1.10]). **Structural, not GC (P15-17):** `AgentGatewayRetainsNoPayloadTest` walks the declared
      instance fields of `AgentGatewayService` and every type reachable from them inside `com.solace.sm.agw` and fails
      on any field of type `byte[]`, `ByteString`, `CommandOffer`, a generated `*Offer` message, or a collection or map
      parameterized by one; a planted fixture holding a `ByteString` field is reported.
- [ ] **Step 2:** `mvn -o -f backend/pom.xml -pl agent-gateway -am verify` → FAIL.
- [ ] **Step 3:** Adapt; compose in the launcher (no property switch; the gRPC listener is off unless
      `sm.agw.listen` is set, so a dp-remote-only install opens no port).
      **As built (T3.2):** the listener's switch is `sm.agent.gateway.enabled` (compose: `SM_AGENT_GATEWAY_ENABLED`); there is no
      `sm.agw.listen`. `AgentGatewayConfiguration` is `@ConditionalOnProperty` on it, so a dp-remote-only install composes no gateway bean.
- [ ] **Step 4:** Run → PASS. **G-A0:** start the launcher locally with the listener set and the local broker
      configured; record that the JCSMP session is up and that `AgentGatewayHandshakeIT`'s **Java gRPC client** (test
      keystore CA certificate) completes an mTLS handshake and one RPC, in one JVM. Evidence in Results.
- [ ] **Step 5: Mutation proofs:** keep the offer in a field (structural test red); take the client address from the
      agent's `Hello` (origin test red); forget to close the telemetry stream on revocation (regression red).
- [ ] **Step 6: Commit** `feat(agw): gateway service on the amended wire; composed by the launcher (plan 015 T3.2)`.

**T1.2 review note (2026-10-09, finding 7):** the taken `AgentGatewayService.java:637-638` persists the agent's free-text `detail.getMessage()`: bound and sanitise it, and never log or echo it. The enrolment request id (`:110-112`, unkeyed MD5 of the token) is replaced per T2.2's note.

**T2.1b fix-round notes (2026-10-10), binding on this task:** (a) `SessionOpen` maps `LEASE_RENEW_REQUIRED` from `AgentLeasePort.current` (any lease row exists; the data plane stores only the nonce digest) to the wire `Error(LEASE_RENEW_REQUIRED)` and then expects `LeaseRenew`; it never forwards `ISSUE_NOT_ALLOWED` on a reconnect. A first-ever session (`current` empty) calls `issue`. Regression to state: design §4.6 case 1 (reuse a live lease) costs a renewal round trip, and so one control-plane call, per reconnect; a control-plane outage blocks reconnect (**accepted for v1, `OR-136` (3)**). (b) A launcher test asserts that the three control-plane adapters (`EnrollmentCertificateIssuer`, `LeaseIssuer`, `AgentRegistrationSource`) and the `AgentCommandPayloadSource` exist when the gateway is composed (the data plane resolves them at call time and answers `INTERNAL_UNAVAILABLE` otherwise). (c) Remove any use of `AgentIdentity.tenantId()` from `agw` (none exists today; arch rule `agentGatewayNeverReadsTheTenant` freezes that) and keep the `AgentIdentityPort#resolve` call sites to `AgentGatewayService` (rule `onlyTheSessionEnrollmentAndGatewayClassesResolve…`). (d) Handle the `REISSUED` clarification: the sweep answers `RENEWAL_REQUIRED` on a permission change; `REISSUED` is reserved (`AGW-D19` clarification).

**T2.2 review notes (2026-10-10).** (a) **Fail closed at boot (review 3):** when the agent listener is composed, T3.2 sets `sm.control.agent-gateway.ca.required=true` (T2.2 added the flag, default false because nothing composed the path yet), and a launcher test proves that a configured listener with **no keystore**, or with a **blank `sm.control.agent-gateway.ca.connect-endpoint`**, fails the context (the CP `enroll` throws on a blank endpoint only at the first enrolment; a listener must not start to find out). (b) `AgentGatewayInterceptor` passes the **bare IP literal** (`InetSocketAddress.getAddress().getHostAddress()`, never `SocketAddress.toString()`) to `AgentRequestOrigin`; the launcher's enrolment adapter strips an `:port` and canonicalizes, and fails closed on anything that is not an IP. (c) The launcher imports `AgentGatewayControlPlaneAdapterConfiguration` here (HF17); T2.2 did not.

**T3.4 note for the gateway service (2026-10-11, corrected in the T3.4 fix round after review).** `AgentGatewayService.telemetry` (as of `e3ea89ff`) always sent a `TelemetryAck{highWatermark, creditsGranted}` and never read `result.disposition()`, `retryAfter()` or `rejection()`. That is **safe from data loss but not live**: the agent's reducer clears a stream's in-flight batch on an `Ack` only when `seq <= highWatermark`, skips a busy stream, and has no in-flight timeout (`go-agent/internal/telemetry/send/reducer.go:201-207,254-257`), so one `DO_NOT_ACK` (which carries the UNMOVED watermark and zero credits) stalls that placement's telemetry until the gRPC stream breaks and costs a credit each time. The earlier remedies written here ("no `TelemetryAck`", a credit-only `TelemetryCredit`) stall the same way and are withdrawn. **Binding on T3.2:** map `DO_NOT_ACK` to `TelemetryReject{code: INTERNAL_UNAVAILABLE, retry_after: result.retryAfter()}` (non-terminal on the agent: credit back, retry time, requeue; `classify.go`), restoring the gateway's reserved credit as its `GatewayPortException` branch does; keep `TelemetryAck` for `ACK` and for `REJECTED_COUNTED` (the advanced watermark trims the batch). Lane B's fix round (`99a3a8f3`) does this; it needs a gateway test with a scripted `DO_NOT_ACK` port asserting a `TelemetryReject` and no `TelemetryAck`.

**Lane time:** ≈ 4 h.

**T3.1 note (2026-10-10, binding on this task).** (a) `AgentCommandPort.pull` can now throw `GatewayPortException(AGENT_PAYLOAD_RENDER_CHANGED)` **after** it has ended the command `REFUSED` (detail `agent.payload.render.changed`): the gateway answers the agent the wire `RENDER_CHANGED` error (the Go reducer terminates its record `REFUSED` on it and acks; that ack is `DUPLICATE`). (b) `pendingFor` no longer skips what the lease cannot authorize: the command is ended `REFUSED(CAPABILITY_MISSING | BINDING_NOT_IN_LEASE)` before any notice, so the gateway sends no notice and no error for it; `agentsWithDeliverableWork` is still an over-approximation (it may name an agent whose only commands the poll then refuses). (c) `ack` for a `SUPERSEDED` command is still answered `SUPERSEDED` (→ `Error(SUPERSEDED)`), but the data plane first records `late.<outcome>` on the row: the agent's closure of its journal is unchanged. (d) `PREFLIGHT`, `RESTART` and `STOP` carry a payload of zero bytes and the digest of zero bytes, because the Go agent verifies `sha256(payload) == config_sha256` for **every** command (`commands.go:242`); a `CommandNotice` carries that constant for them, the real digest for `APPLY`/`REMATERIALIZE` only while the in-memory hand-off holds it, and none after an SM restart. (f) **Lifecycle phase (review finding 2).** `ApplyStartupRecovery` (phase `Integer.MIN_VALUE + 1000`) resumes agent steps synchronously inside boot, before any later-phase gateway lifecycle bean listens. T3.1 makes a session not heard from within `sm.dp.agent.session-live-window` (default 2 min) not live, so a resumed step fails fast; place the gateway's `SmartLifecycle` AFTER `ApplyStartupRecovery` and `ApplyWorker`, and note that a restart faster than the window still waits for the step's ceiling. (g) `AgentSessionPort.liveFor` also requires the placement to be in the lease's `placement_ids`: a placement added to an agent target after the lease was issued answers `agent.session.absent` on every apply until the next renewal, so renew or re-issue the lease when the DP's placement set changes (or state the delay; T3.5 adds placements at enrolment). (e) The `ApplyWorker` cap counts requests, not sessions: with `sm.agent.gateway.enabled=true` the pool must be at least 2 or boot refuses (`ApplyWorkerProperties#agentCap`).

### T3.3 — Report intake; per-operation refusals; `AgentObservedStatePort` (lane C; HF3 descriptions, HF20)

**Files:** new `dp-core/.../dp/agent/AgentReportIntake.java` (implements `ActualStatePort`); fills in T2.1b's
`AgentObservedStatePort` stub class (reads the latest observation); writes through existing repositories for
`placement_health` (persist on change, `OR-39`), `placement_start_observation` (`OR-112`), the report watermark; the
services that today reach SSH directly for logs, log level, read-config/accept-runtime and restore — each checks the
placement's transport and answers `agent.operation.unsupported` (HTTP `501` with the `ErrorCode` member T0.3 named);
`console.yaml` **descriptions** of those operations; `ErrorCode.java`; launcher endpoint ITs.

- [ ] **Step 1: Failing tests:** `AgentReportIntakeIT` — a report with `health=HEALTHY` writes one `placement_health`
      row, the same again writes none; an older `sequence` is ignored; a report for a placement the agent's lease does
      not hold is refused and an `agent_event` written; `container_started_at` later than `write_baseline` drives the
      ladder to `restart-observed`; `AgentObservedStatePort#latest` returns what the last accepted report said.
      Endpoint ITs: `GET …/logs`, log level, read-config, accept-runtime, restore **and `rollbackApply` (refused at admission, so no doomed
      apply request is admitted; D-C2, confirmed by the review)** on an agent placement → `501
      AGENT_OPERATION_UNSUPPORTED`, and **no SSH connector constructed** (spy).
- [ ] **Step 2:** Run the targeted ITs → FAIL. **Step 3:** Implement. **Step 4:** → PASS.
- [ ] **Step 5: Mutation proofs:** write health on every report (red); accept a start observation whose baseline was
      taken on another placement's host — the rule is same-host clock (`OR-112`), so a cross-host comparison must
      neither be stored nor drive `restart-observed` (red) (P15-22).
- [ ] **Step 6: Commit** `feat(dp): agent reports into placement health, start observation and the ladder; contracted refusals (plan 015 T3.3)`.

**T1.2 review note (2026-10-09, findings 6 and 7):** the free-text `detail.getMessage()` is bounded and sanitised before it is persisted (never logged or echoed); the agent's plain hex is converted by the keyed-hash bridge, never stored as `"sha256:" + hex` (`OR-32`); `ReportItem.bindingId` is agent-supplied and is never used to resolve tenant or instance (I5).

**T2.1b fix-round notes (2026-10-10), binding on this task:** T3.3 owns the `ActualStatePort` bean (`AgentReportIntake`), wrapping `JdbcAgentReportRepository` (a persistence helper, not a port). It must (a) accept a report after the lease has expired and label the item `UNTRUSTED` (design §4.6; T2.1b's helper requires an active lease and refuses otherwise); (b) check `replica_ref` against the placement's `container_name` (`AGW-D25` item 2, `REPLICA_REF_INVALID`); (c) take `containerStartedAt` from the report, not from the ack (`AGW-D28`; the ack's field is not persisted); (d) choose a coded refusal for a malformed report item (T2.1b used `UNKNOWN`, no code fits).

**Lane time:** ≈ 3 h. Closes central item 44 when reviewed (`AGW-D28`); the staleness half (item 1) landed in the fix round, lazily, see T3.3 Results.

**T3.1 note (2026-10-10, binding on this task).** The adapter's `status` reads **only** `AgentObservedStatePort#latest` and maps it as follows; whatever the intake persists must make these true. (a) `startedAt` non-null whenever the container exists; `startedAt == null` or `health.state == UNREACHABLE` reads as **not running** (`Liveness.STOPPED`), no observation at all reads `ABSENT`; the start baseline is `PreBaseline(startedAt, agentId)`, so `startedAt` must move forward only (the engine compares baselines for `restart-observed`). (b) `reportedSha256` is `sha256:<hex>` (the DP canonical form) and `persistedGeneration` is the **wire** generation of the command whose APPLY wrote the file: after an APPLY or REMATERIALIZE is acknowledged the adapter waits (default 45 s, `sm.dp.agent.readback-wait`) for a report with `persistedGeneration >= the command's wire generation` and a digest; one that names a different digest than the bytes sent fails the step `agent.apply.hash.mismatch`, because `AgentAck` carries no digest (frozen, HF18) and the report is the only proof. (c) The health signal is the declared probe read as the dp-remote adapter reads its own (`HealthSignal` from `httpStatus`, `actuatorStatus`, `failure`); a package that declares no probe is `NOT_DECLARED` whatever is reported. (e) Until this task fills `AgentObservedStatePort`, a production agent APPLY waits the full read-back (45 s) for a report that cannot come and then fails the engine's read-back (`materialize.readback.unavailable`) on every attempt, holding a capped worker thread for about 45 s times the attempt cap; the W3 integration gate must not run an agent apply before this task is merged. (d) `placement_start_observation` stays the one baseline source: the ack's `container_started_at` and the report's must be recorded by the rule in `decisions.md`, not re-derived here.

### T3.5 — The agent target at enrolment, and every target operation on it (lane C, after T3.3; HF3, HF20, HF21; P15-2)

**T2.2 review note (2026-10-10): T3.5 is the DP half.** The CP target row is written by **T2.2**, in the consumption transaction, because V021's `agent_registration_consumed` CHECK makes `target_id`, the certificate and the used-at timestamp arrive together; `ControlPlaneAgentGatewayService` already does it, and `ControlPlaneEnrollmentCertificateIssuer` already returns the `EnrollmentIssuance` (tenant, solution, declared version, environment, target, envelope, issuing principal). Read "(1) CP, one transaction" below as done. T3.5 owns: the DP transaction (2) (`target_connection`, `agent_identity`, the `enrolled` event), the degraded listing, the lazy sweep, and the target-operation table. **Also (T2.2 review 7):** `registerTarget` must refuse a name held by an **active, unconsumed** registration (`agent_registration_pending_name` holds it for the token; today `TargetService.register` sees only `target` rows, so an operator could take a pending token's name and strand the agent at enrolment with `TARGET_NAME_TAKEN`). Add the check in the same transaction as the target insert, with a test.

**Files:** `dp-core/.../dp/agent/AgentEnrollmentTargetWriter.java` (+ its repository methods in
`apply.persistence.agent`); `launcher/.../ControlPlaneEnrollmentCertificateIssuer.java` (HF21: the CP target row);
`cp-core/.../cp/agent/ControlPlaneAgentGatewayService.java` (the consumption transaction gains the CP target row);
`contracts/openapi/console.yaml` (`Target.transport`, `TargetConnection` `oneOf`, target refusals — T0.3's reviewed
draft), `ConsoleOperations.java` if a shape mirror needs it, Postman; `ErrorCode.java`
(**five members** (D-C3): `AGENT_OPERATION_UNSUPPORTED` (T3.3), `TARGET_TRANSPORT_UNSUPPORTED`, `AGENT_STILL_ENROLLED`,
`TARGET_IS_AGENT_MANAGED`, `AGENT_NOT_CONNECTED`; each added to `ErrorCode.java` and `console.yaml` in the same commit); `TargetManagementOrchestrator`,
`TargetRegistrationOrchestrator`, the placement-declare path in `console-gateway` (transport checks); launcher ITs.

**The two-plane write at enrolment (`BE-D71`, `BE-D32`'s order).** On `Enroll(token, CSR)`:
(1) **CP, one transaction**, under operation `AGENT_ENROLLED`, actor the agent, origin from the RPC: consume the token
(or replay it), sign the certificate, and register the **CP target row** — `name` = the token's `target_name`, `kind
docker`, transport `agent`, ownership scope = the token's environment — through `TargetRegistration`'s write path,
idempotent on organization and name; the `target.create` check is **not** repeated here because it was checked at token
issue against the issuing principal (T4.3), whose id the row's audit carries beside the agent (§10 item 5). The answer
carries `targetId`, the envelope and the issuing principal. (2) **DP, one transaction:** insert
`target_connection` (transport `agent`, `agent_id`, the envelope, `created_by` = the issuing principal), `agent_identity`
and a `target_connection_event` `enrolled` (`actor_kind agent`, actor the agent, the RPC's correlation id and client
address, no fingerprint). (3) Return the certificate. **Recovery:** a crash between (1) and (2) leaves a CP target the
DP lacks; it is listed degraded, as an interrupted `registerTarget` is, and the agent's retry of the same token+CSR
replays (1) to the same certificate and target id and completes (2). If the agent never retries, the lazy,
tenant-scoped sweep on `listTargets` retires a degraded **agent** target once its token's `expires_at` plus the
`BE-D32` window has passed, under the attribution rule `BE-D32`'s sweep already uses (Step 0 measures it).

**Every existing target operation on an agent target:**

| Operation | On an agent target |
|---|---|
| `listTargets`, `getTargetConnection` | Listed; `transport: agent`; the connection is the `AgentTargetConnection` variant |
| `registerTarget` | Unchanged; cannot create an agent target (`transport` stays `ssh-docker` in its request) |
| `scanHostKeys` | Unchanged: it is not target-scoped (`POST /dp/host-key-scans` scans an address), and an agent target has no address |
| `editTargetConnection`, `rotateTargetCredential`, `testTargetConnection` | `409 TARGET_TRANSPORT_UNSUPPORTED`; nothing read or written; no SSH constructed |
| `retireTarget` | `409 AGENT_STILL_ENROLLED` while its agent is not revoked; after `revokeAgent`, as today (DP connection retired first, CP row second) |
| `addPlacement`, or a declare naming an agent target | `409 TARGET_IS_AGENT_MANAGED`: placements on an agent target come only from `OR-27` (v1) |
| `retirePlacement`, `undeclareInstance` on an agent placement | As today, **plus** the discovery tombstone in the same DP transaction (T4.1) |

**Readers break the moment the first agent row exists (T1.1 review, finding 12; forward note).** V028 makes `target_connection.host`,
`port`, `ssh_user`, `host_key_fingerprint` and `credential_ref` nullable and adds the `agent` transport, so every reader that assumed
an SSH row breaks when T3.5 writes the first agent row: `JdbcTargetConnectionRepository`'s `row(ResultSet, int)` mapper (it calls
`rs.getInt("port")`, which turns a NULL into 0, and builds `TargetConnectionRecord` from the other nullable columns as if present), the SSH
paths that build a connection from a stored target (the `PinnedHostKeyConnect` callers),
the target listing and detail paths, and the `acquisition_mode` parsers (`direct-http`, `via-ssh`, now `agent`). T3.5 therefore
starts by listing every reader of those columns and of `instance_state.acquisition_mode`, makes each tolerate the agent variant
(or refuse it by name, per the table below), and carries a test that lists, reads and edits a target set containing both
transports. This is T3.5's work, not T1.1's; V028 only guarantees the database shape.

- [ ] **Step 0 (measure):** `BE-D32`'s sweep attribution; whether check 11, `generate-postman.py`, `check-postman.py`
      and the console mirror tests accept a discriminated `oneOf` — if not, **stop and report**; no flattening without
      review.
- [ ] **Step 1: Failing tests:** `AgentEnrollmentTargetIT` (launcher test sources, PG14, a test context that imports the CP, DP and adapter configurations **directly** — not through HF17, which is lane B's this wave — and drives `AgentEnrollmentPort` with an `AgentRequestOrigin`; the gRPC path is T3.2's and is exercised end to end in T4.4 and G-A1): enrol → one CP target
      (`change_audit` `AGENT_ENROLLED`, `actor_agent_id` the agent, correlation id and client address present), one DP
      `target_connection` with the token's envelope, one `enrolled` event; a crash injected between (1) and (2) →
      degraded listing, then the replayed `Enroll` completes it with the same ids; a never-retried one is retired by
      the sweep after the window; a placement outside the envelope on that target → refused. Endpoint ITs for each row
      of the table above (status, `ErrorCode`, no SSH spy call). Contract: `validate-openapi.py`, check 11, Postman.
- [ ] **Step 2:** → FAIL. **Step 3:** Implement. **Step 4:** → PASS; `./contracts/run-checks.sh --strict`; `check-postman.py`.
- [ ] **Step 5: Mutation proofs:** write the DP row before the CP row (crash IT red: an orphan DP row); take the
      envelope from the agent's request instead of the token (red); allow `editTargetConnection` on an agent target
      (red).
- [ ] **Step 6: Commit** `feat(dp,cp,gateway,contracts): the agent target, created in both planes at enrolment; target operations contracted for agent targets (BE-D71, plan 015 T3.5)`.

**`OR-136` (1) (2026-10-10), binding on this task:** the enrolment-token lifetime becomes operator configuration: `sm.control.agent-gateway.enrolment.token-default-validity` (default **24 h**) and `sm.control.agent-gateway.enrolment.token-max-validity` (default **30 d**), replacing T2.2's constants (1 h / 7 d) in `AgentEnrolmentService`; startup fails when default > max, or either is ≤ 0. Tests: the defaults; a configured pair is honoured; a request above the maximum is refused; the invalid configurations fail the context.

**Lane time:** ≈ 3 h.

**T3.1 note (2026-10-10).** Routing is read from `target_connection.transport` per call by `JdbcAgentPlacementCatalog` (one object: `PlacementTransports` for the router and the pollers, `AgentPlacementCatalog` for the adapter). `JdbcAgentPlacementCatalog#resolve` requires a LIVE placement on a LIVE agent target; a retired one is refused by the agent adapter (`agent.placement.unresolved`), never by SSH. The existing `TablePlacementCatalog`/`TargetConnectionRows` still map an agent target (null `host`) to `placement.invalid`: every dp-remote-only operation on an agent target (T3.5's list) must be refused with its code **before** it reaches them.

### T3.4 — Telemetry into `sm_metrics` through the built port (lane D; HF4 second)

**Files:** new `dp-core/.../dp/agent/AgentTelemetryBridge.java` (implements `AgentTelemetryPort`): resolves
`organizationId`, `placementId` from the session and lease (`I5`), maps samples to `IngestSample`, calls the built
`dp.api.metrics.ingest.TelemetryIngestPort` with `SourceDiscriminator.AGENT`; fills in T2.1b's
`AgentTelemetryDeclarationsPort` stub (the placement's package metrics, `http_json` only); `instance_state.acquisition_mode
= 'agent'` handling; **`VMA` (`sm_metrics` V004)** drops and re-adds `ingest_counter_reason` with `clock_skew` (`OR-130` F-6: a closed CHECK, V001:86), with its checksum
line and the pinned `SmMetricsSchemaIT` counts in the same commit; the **±5 minute** sample window; agent cadence fixed at 5 s collect / 1 min push (`OR-73`), the package's `intervalSeconds`
(minimum 10, schema `:706`) not applied on this path [inferred reading of `OR-73`; §10 item 8]. **Does not edit
`CollectionPlanBuilder` or `HealthPoller`** (HF19 is A's; a needed edit stops and reports).

- [ ] **Step 1: Failing tests:** `AgentTelemetryBridgeIT` — a batch is acked only on `Committed`; on `RetryAfter` the
      gateway receives "do not ack"; on **`Pending`** (reserved for `UI-D5` quarantine, *"Never returned in v1"*,
      `IngestResult.java`) the same "do not ack" plus a counted `agent.telemetry.pending.unexpected` — the exhaustive
      switch handles all four `IngestResult` members (P15-22); `Rejected("placement.unknown")` → dropped, counted,
      acked-as-rejected (never retried forever); an undeclared metric is dropped and counted (`OR-75` M-Q6); a
      duplicate `batchSeq` is ignored; the sample `ts` rule as ruled (`OR-130` F-6): a sample within **±5 minutes** of DP receipt is
      accepted; one at **5 minutes + 1 second** is refused and counted `clock_skew`. **Ruled by `OR-134` (§10 item 19): the window runs on the agent's measured clock offset**
      (the lease's `server_clock` at session open), not on sample age, so the 24-hour disk buffer keeps its value (R-11).
- [ ] **Step 2:** → FAIL. **Step 3:** Implement. **Step 4:** → PASS; `sm_metrics` rows carry `source_discriminator = 'agent'`.
- [ ] **Step 5: Mutation proofs:** ack on `RetryAfter` (red); ack on `Pending` (red); take the organization from the
      batch (red).
- [ ] **Step 6: Commit** `feat(dp): agent telemetry re-sunk into sm_metrics via the built ingest port (OR-126 A7) (plan 015 T3.4)`.

**T1.2 review note (2026-10-09, finding 6):** `TelemetryBatch.Sample.labels` are agent-supplied: they are validated against the declared set and never resolve tenant or instance (I5).

**T2.1b fix-round notes (2026-10-10), binding on this task:** T3.4 owns the `AgentTelemetryPort` bean (`AgentTelemetryBridge`); `JdbcAgentTelemetryWatermarkRepository` is a persistence helper, not a port. The stream watermark advances in the same transaction as, or after, `Committed` (never before), so a failed ingest is redelivered rather than lost.

**Lane time:** ≈ 3 h. **Why REWRITE:** the branch writes `sm_data.series`/`metrics_raw` and trusts `ts` [An §1.3, §5 item 8].

---

## W4 — `OR-27`, the operator surface

**W4 note (2026-10-11, W3 integration nit 10): the declarations-change signal.** `AgentTelemetryDeclarationsPort` has no "declarations changed" signal. T3.4's old Javadoc promised a default method "with T3.4"; none was added (corrected in the W3 integration fix round). Declarations therefore reach an agent only at session open and at each lease renewal, so a package or metric-activation change takes up to one renewal interval to reach a connected agent. Owed to W4 (the gateway side already re-sends declarations at renewal): add the signal as a default method on the port (an HF18 change, so owner-visible), call it from the metric-activation and placement-change paths, and have the gateway push the new set on the live stream. Until then the delay is the documented behaviour.

**W4 note (2026-10-11, W3 integration re-review 10): owed items carried from W3.** (a) The `AgentTelemetryDeclarationsPort` contract kit (AGW-D2) and the declarations-change signal above, or an owner waiver, before G-A1. (b) `OR-140`'s scheduled jobs replace the lazy staleness verdict. (c) No dp-core IT composes `AgentGatewayPortsConfiguration`'s `AckStartSettler` lambda against the real ladder: the settler is asserted by count only. A W4 IT should drive an agent RESTART ack to `restart-observed` through the real bean (the ladder code it calls is the intake's and is proven there).

**W4 note (2026-10-11, W3 integration follow-up review 12, 13).** (d) The reconciler's re-write (`ApplyExecutor#rematerialize`) closes its attempt with no baseline. The first redrive attempt of a REMATERIALIZE carries the ack's start (the command was created under that attempt), but a later attempt that reuses the command after a session blip or read-back miss closes SUCCEEDED with none: finding 1's shape on the redrive path. dp-remote has never recorded a redrive baseline either, so this is parity, not a regression. Fix with the next engine touch: close with `WriteBaselinePolicy.of(adapter, replica.placement(), intent.intentId(), status)` there too, which for dp-remote means `status.baseline()` (a deliberate dp-remote behaviour change, so record it), plus a redrive-retry case in `AgentAckBaselineIT`. (e) `dp.apply.runtime.agent.AgentRuntimeAdapter` imports `dp.config.ConfigLadder.START_TOLERANCE` while `dp.config` imports `dp.apply.runtime`: a package cycle (no arch rule forbids it). Move the constant to `dp.apply.model` when the file is next touched; fix `RoutingRuntimeAdapter`'s import order in the same commit.

### T4.1 — `OR-27`, data plane side, with tombstones (lane A; HF7, HF4 first)

**T3.2 review note (2026-10-11): two declaration gaps with no owner.** (1) Nothing re-sends `TelemetryDeclarations` when a package's metrics change: the gateway re-reads them only at `SessionOpen` and at lease renewal or re-issue. Whoever owns the apply/declare path (here, or a §10 row) must add a change signal (e.g. a default method on `AgentTelemetryDeclarationsPort` plus a gateway subscription) or accept the lag. (2) The agent refuses a binding's whole set when a metric's `unit` is empty (`declare/set.go`); the gateway now sends `"1"` for a null or blank unit, but the declare path should still reject an empty unit at the source (T3.4 note).

**Files:** fills in T2.1b's `AgentDiscoveryPort` stub class as `dp-core/.../dp/agent/AgentDiscoveryService.java`; the DP
declare phase reused from `InventoryService` (HF7) for an **agent-declared** pending instance and its placement on the
agent's target; the tombstone writes for undeclare, retire-placement and the sweep's abandonment (**built as one trigger, `sm_data` V031, on `placement.retired_at`, not as hooks in the services** - see T4.1's Results: one place covers every caller, and it depends on the call order that each of those services now comments);
the `BE-D32` sweep's abandonment of an agent-declared pending instance; per-target cap
`sm.dp.agent.max-placements-per-target` (default **16**, §10 item 11); every refusal an `agent_event`.

**T3.5 note (2026-10-11, review MINOR 5):** `InventoryService.reserve` and `addPlacement` now refuse EVERY agent target (`TargetIsAgentManagedException`, `409 TARGET_IS_AGENT_MANAGED`). That is right for the operator-facing commands and wrong for this task's own path, so discovery's declare must enter through a separate entry (or a command origin that only the discovery service can construct) and leave the operator refusal in place. `AcquisitionMode.AGENT` exists for the instance it creates; a declare body naming it is `422`. `Instance.acquisition.mode` in `console.yaml` is still `[direct-http, via-ssh]` and widens with this task's contract.

**T3.3 note (2026-10-11, review finding 1):** lane A's `awaitReadBack` and `status` rely on `AgentObservedState` keeping the last observed hash when a periodic report carries none (the real agent's periodic report never sets `effective_hash`); T3.1/T4.4 should say so in their tests.

**New seam (W4 fix round):** the DP reads no package, so the probe and metrics come through `dp-api` `inventory/PackageDeclarationSource`, implemented by `console-gateway`'s `AgentPackageDeclarations` from the agent's ENROLMENT (never the report). A shared contract kit for it needs the dp-api test-jar on console-gateway's test classpath (a pom line, HF13: nobody this wave); `AgentPackageDeclarationsTest` carries the cases meanwhile.

**Where each placement column comes from** (the discovery item reports facts, T0.3 Step 1 (d); the DP decides):

| Column | Source | Missing or invalid → |
|---|---|---|
| `instance_state.name` | label `com.solace.sm.instance` | (no label: not reported) |
| solution, package version | the enrolment record, copied into `agent_identity` (`catalog_solution_id`, `solution`, `declared_package_version`) | — |
| `instance_state.acquisition_mode` | `'agent'` | — |
| `container_name` | the Docker name; must start with the target's `allowed_container_prefix` | `REFUSED agent.discovery.outside.envelope` (before the trigger) |
| `container_config_path`, `file_name` | dirname and basename of label `com.solace.sm.config-file` (an absolute in-container path) | `REFUSED agent.discovery.incomplete` |
| `remote_config_directory` | the `source` of the bind mount whose `destination` is `container_config_path`; inside `allowed_config_root` | `REFUSED agent.discovery.incomplete` / `…outside.envelope` |
| `mechanism`, `retained_generations` | what the declare path writes today for this package (Step 0 measures: package declaration or server default) | — |
| `health_kind`, `health_path`, `health_strength` | the package's `health.probe`, copied as declare copies it | NULL ×4 if the package declares none |
| `health_port` | the host port Docker publishes for the package's declared probe port (`published_ports`); else label `com.solace.sm.health-port` | `REFUSED agent.discovery.incomplete` when the package declares a probe. The DP's poller skips agent placements; the host port is **sent to the agent in `HealthProbe`** (`OR-130` D-4), which runs the probe, and it is stored because `health_probe_complete` and `placement_health_port_once` hold for every placement |
| placement actor (`OR-125` (1) = `V026`'s `placement.created_by`), `instance_state.created_by` | the agent's id written into `placement.created_by` (no kind column: told apart by joining `agent_identity`); `instance_state.created_by` the enrolment's issuing principal | §10 item 5 |

**Tombstones (P15-9, `AGW-D30`).** `agent_discovery` is keyed `(tenant, agent, instance_label, container_name)`.
`REFUSED`, `ABANDONED`, `UNDECLARED` and `RETIRED` are **terminal**: a re-report of the same key answers the stored
state and mints nothing. `undeclareInstance` (`BE-D49` leaves the workload running) and `retirePlacement` on an agent
placement set `UNDECLARED`/`RETIRED` **in the same DP transaction**; the `BE-D32` sweep's abandonment of an
agent-declared pending instance sets `ABANDONED` in the same transaction. A tombstone is released only by a change the
operator makes: `REFUSED`/`ABANDONED` when the container is **re-created** (a new `container_id` under the key) or its
label value changes; `UNDECLARED`/`RETIRED` **only** when the label value changes (a CI/CD redeploy re-creating the
container must not silently re-register what an operator removed). A re-created container of a `REGISTERED` key keeps
its placement (`container_name` is the placement's identity; the new `container_id` is recorded).

- [ ] **Step 0 (measure):** read `DeclareOrchestrator` and the DP declare phase (`BE-D32`): the sources of `mechanism`,
      `retained_generations` and the probe; how the sweep abandons (where the tombstone write joins it).
- [ ] **Step 1: Failing tests:** `AgentDiscoveryIT` — a new item mints a pending instance + placement in the agent's
      environment and returns `PENDING`; the same item again is idempotent; item 17 on a cap of 16 → `REFUSED`
      (`agent.placement.cap`); a container outside the prefix or config root → `REFUSED …outside.envelope` and the
      trigger never fires; a `discovery_key` equal to a live instance name → `REFUSED` (`instance.name.taken`); no item
      can name another environment or organization (the fields do not exist); **`undeclareThenReconnectDoesNotReRegister`**;
      **`retirePlacementThenReconnectDoesNotReRegister`**; **`sweepThenReconnectDoesNotReMint`** (pending past the
      window → `ABANDONED`; the next report answers `ABANDONED`); `refusedThenRecreatedIsRetried`;
      `recreatedContainerKeepsItsPlacement`; `undeclaredThenRecreatedStaysUndeclared`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Mutation proofs:** drop the cap (red); make `UNDECLARED` non-terminal (red); release `UNDECLARED` on
      re-create (red); let the item carry an environment (reflection test: the record has no such component).
- [ ] **Step 6: Commit** `feat(dp): agent discovery declares pending instances in the enrolled environment; capped, tombstoned, audited (OR-27) (plan 015 T4.1)`.

**W4 lane D note (2026-10-11, corrected in the fix round), the declarations-change signal and the placement set: T4.1's discovery confirm must tell the sink.** Lane D added `AgentTelemetryDeclarationsPort#onDeclarationsChanged(Consumer<UUID>)` (an HF18 default method) and `dp.apply.events.DeclarationsChangeSink`. The sink is told (`ApplyEventEmitter#settleLadder` after every ladder settle, and `ApplyEventEmitter#declarationsChanged` from the paths that do not settle: `InventoryService#activate` and `settlePending` Rule 1, the three retirement transitions, a dp-remote `StartObserver` start). It defers to the commit, finds the agents ENROLLED ON THE TARGETS of the instance's placements (from the placement's target, not from a lease), and the gateway then (1) reconciles that one agent, which now answers `RENEWAL_REQUIRED` whenever the stored lease's `placement_ids` differs from the target's live placement set, so the agent renews and the renewed lease and the pushed declarations include the new placement, and (2) re-sends the declarations. **Discovery** (as integrated, W4 integration 2026-10-12): `InventoryService#reserveDiscovered` does NOT settle the ladder (a PENDING instance declares nothing); the signal comes from the REGISTERED settlement, which activates the instance through `InventoryService#activate`, and activation tells the sink. No extra call is needed in `AgentDiscoveryService`; `AgentDiscoveryIT#aRegisteredContainerSignalsItsAgentThatItsDeclarationsChanged` pins it, with a mutation (activation not signalling) red. A placement is declared only after the agent renews (a lease's placement set is a snapshot), which the signal now requests; it is no longer "up to one renewal interval", but it is not instant either. The first version of this note said the signal alone delivers a placement add; that was wrong (review `w4d-review.md` finding 1).

**T1.2 review note (2026-10-09, finding 6):** `ReportItem.bindingId` is agent-supplied: tombstone and registration lookups never resolve tenant or instance from it (I5).

**T2.1b fix-round notes (2026-10-10), binding on this task:** T4.1 owns `JdbcAgentDiscoveryRepository` (`agent_discovery` is its table; T2.1b ships only the stub). It fills in `AgentDiscoveryStub` in place.

**Lane time:** ≈ 2 h.

### T4.2 — `OR-27`, the completer (lane A, after T4.1)

**Shape: ruled `OR-128` (15) — no cross-organization enumeration.** (The loop alternative is deleted, not skipped.)
`AgentDiscoveryService`, after its own commit, calls the `dp-api` sink `AgentRegistrationCompletion` with the tenant it
resolved from the agent's identity (`I5`) and the RPC's `AgentRequestOrigin`; `console-gateway`'s
`AgentRegistrationCompleter` implements the sink (the launcher wires it; `dp..` imports only the `dp-api` interface,
`I7`), registers the CP governance node through the existing declare protocol with `OR-40`'s quota check under
`AGENT_INSTANCE_REGISTERED` with the agent as actor, then confirms the DP row `REGISTERED` (refusal → DP `REFUSED` +
`agent_event`). The durable contract is the `PENDING` row: a crash before confirmation is finished by the agent's next
discovery report (sent on every `Connect`) or abandoned and tombstoned by the sweep (T4.1); the in-memory call is never
the sole contract (`AGW-D2`'s rule, applied here).

**Files:** `console-gateway/.../agent/AgentRegistrationCompleter.java`; `dp-api/.../agw/AgentRegistrationCompletion.java`
(declared here, not in T2.1a: nothing before this task calls or implements it); launcher wiring (HF17 is B's in W3 only;
in W4 the wiring line is A's, ordered A → B).

- [ ] **Step 1: Failing tests:** `AgentRegistrationCompleterIT` — pending → CP node → `REGISTERED`, the agent's next
      lease holds the new placement; over quota → `REFUSED (entitlement.quota)`, audited in `agent_event`, no CP node;
      SM killed between the CP write and the DP confirm → the next `Connect`'s report completes it (and, past the window,
      the sweep tombstones it instead); the `change_audit` row for the node has `actor_agent_id` = the agent,
      `actor_principal_id` NULL, the RPC's correlation id and client address. `TenantEnumerationBoundaryTest` unchanged and green (no new enumeration).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Mutation proofs:** skip the quota (red); write the CP node with the issuing administrator as actor (red).
- [ ] **Step 6: Commit** `feat(gateway): agent registration completer — CP governance node, quota, agent as actor (OR-27, OR-40) (plan 015 T4.2)`.

**Lane time:** ≈ 1.5 h. **Why in console-gateway:** [Br Q5] (b) keeps `AGW-D2` (the gateway never imports `cp..`).

### T4.3 — Verbs 44–47: five operations (lane B; HF3, HF20)

**Files:** `contracts/openapi/console.yaml` (five operations from T0.3's reviewed draft); `ConsoleOperations.java`;
`ENDPOINT-INVENTORY.md`; Postman via `generate-postman.py`; `ProductionAuthenticationIT` route set; `ErrorCode.java`;
new `console-gateway/.../http/AgentController.java`, `AgentOrchestrator.java`; launcher `AgentEndpointsIT`.

- [x] **Step 1: Failing tests** *(written with the code, not red-first; the mutation matrix in T4.3 Results is the evidence)*: `AgentEndpointsIT` — Administrator issues a token with a target name and envelope
      (shown once; a second `GET` never returns it; the permission check is §10 item 4's); a malformed envelope →
      `400`; Operator cannot issue or revoke (`403`), can drain and view; Viewer can view only; revoke raises the epoch
      and the session closes within the reconciliation interval; drain answers `202` and the agent reconnects after
      `reconnect_after`; every operation is scoped to the environment (`I5`: another organization's agent → `404`).
      `ConsoleOperationsMirrorTest`, check 11, Postman check.
- [x] **Step 2–4:** *(implemented with the tests, PASS; not a FAIL→PASS run)* `./contracts/run-checks.sh --strict`; `check-postman.py`.
- [x] **Step 5: Mutation proofs:** return the token on `getAgent` (red); grant Operator `agent.revoke` in the seed (red).
- [x] **Step 6: Commit** `feat(gateway,contracts): agent enrol, list, get, revoke, drain (D10 rows 44-47) (plan 015 T4.3)`.

**T4.3 note for T4.1 (2026-10-11): the `Acquisition.mode` widening is done here.** `console.yaml`'s `Acquisition.mode` is now
`[direct-http, via-ssh, agent]` (`validate-openapi.py` pins it; `SchemaMirrorLockTest` and `AgentSchemaMirrorTest` mirror it;
`AcquisitionMode.AGENT.wire()` is `agent`). The schema is shared by the declare body and by `Instance`, so a declare that names `agent`
must stay `422` (`DeclareOrchestrator` already refuses it, T3.5): T4.1 adds no contract change for it and need not touch `Acquisition`
(the wire record is a `String mode`).

**Lane time:** ≈ 3 h.

### T4.6 — OR-140: the sweep and the staleness verdict as scheduled jobs under a system actor (lane C)

**Specification.** There is no separate task text: `OR-140` (`DECISIONS.md`) and §10's owner questions Q-C1, Q-C2 and Q-C3 are it. The owner ruled (2026-10-11): the enrolment sweep and the agent-staleness verdict leave their request paths (`listTargets`, `getHealth`) and run as scheduled jobs; the sweep's revoke and retire are audited to a **system actor**, never to the token's issuer or the requester; one new `sm_control` migration introduces that actor and amends `OR-25`'s actor rule; the cross-tenant enumeration the jobs need is permitted for these two jobs only (`OR-44`/`OR-49` amended to that extent).

**Files.**
- `cp-core/.../db/migration/sm_control/V022__system_actor.sql` (+ `CHECKSUMS.sha256`, the migration README), `ControlPlanePersistenceConfiguration` (readiness stage V022), `SystemActors`, `SystemAuditContext`, `JdbcSystemAuditContext`, `AgentPersistenceConfiguration` (bean), `AgentEnrolmentSweepService`, `AgentRegistry`/`JdbcAgentRegistry` (`organizationsWithAbandonable`; `Abandonable` no longer carries the issuer), `AgentConfiguration`;
- `cp-api/.../AgentEnrolmentSweep` (`organizations`; `revoke`/`retire` take a correlation id, not a `RequestOrigin`; `Candidate` loses `issuedBy`);
- `console-gateway/.../AgentEnrolmentSweepJob` (new), `AgentTargetSweep` (per organization; correlation ids; `window()`), `TargetController` (the sweep call and its constructor are gone);
- `dp-core/.../dp/agent/AgentStalenessJob` (new), `AgentHealthStaleness` (`cutoff()`), `AgentGatewayPortsConfiguration` (the bean, in an existing configuration so there is no new `@Import`: HF4 untouched), `JdbcAgentReportRepository` (`tenantsWithStaleProbedPlacements`, `staleProbedInstances`), `HealthServiceImpl`/`HealthConfiguration` (no staleness dependency), `AgentReportStaleness`;
- `arch-tests` (HF15): `TenantEnumerationBoundaryTest` and seven planted fixtures (plus a method-reference one added in the fix round);
- `launcher/src/main/resources/application.properties`, `runtime/README.md`, `runtime/compose.yml`, `runtime/.env.example` (HF17, additive);
- tests: `SystemActorSchemaIT`, `ControlPlaneConfigurationIT` (V022 stage), `AgentEnrolmentSweepServiceTest`, `AgentTargetSweepTest`, `AgentEnrolmentSweepJobTest`, `AgentEnrollmentTargetIT`, `AgentReportIntakeIT` (+ `AgentJdbcWorld#stalenessJob`, a dp-core test source).

- [x] **Step 0 (measure).** How actors are modelled (`change_audit.actor_principal_id`, V021's `actor_agent_id`, the one-kind CHECK, the `sm.audit.*` transaction settings, `record_change_audit()` rebuilt whole per migration, the readiness stage per migration); what `ScheduleFirer` does for exactly-once (nothing: no lock; every state move is conditional); where the sweep and the verdict ran (`TargetController#listTargets`, `HealthServiceImpl#health`); whether the repo has a DDL replay for `sm_control` (no: `docs/design/backend/evidence/ddl-replay` is the `sm_data` apply tables).
- [x] **Step 1: Failing tests.** `SystemActorSchemaIT` (no V022: red); then the job, attribution and "a GET no longer sweeps" tests.
- [x] **Step 2-4:** implement; green.
- [x] **Step 5: Mutation proofs:** the sweep put back on the GET; audit to a principal; the allow-list name dropped; the verdict put back on the read; the migration's operation check dropped.
- [x] **Step 6: Commits** (see Results).

**Owed here, flagged for the integrator:** HF17 (`application.properties`) and HF15 (`arch-tests`) are edited additively. The migration is `sm_control` **V022** and is unanchored until it is published.

### T4.5 — Retention on the `OR-77` pattern (lane D; HF4 second)

**Files:** a call to `sm_data.agent_gateway_maintenance()` from the existing daily background loop (the one that calls
`ensure_metric_partitions()`); `AgentMaintenanceIT`.

- [x] **Step 1–4:** Failing IT (old acked commands past retention are gone after one call, **except the highest `wire_generation` of
      each (agent, placement)**, and a new command is numbered above the old maximum; live ones, every `agent_event` and every
      `agent_discovery` tombstone stay; the function takes no argument) → implement → PASS. **`OR-134` (§10 item 18) extends F-5 to `agent_lease`:
      `VDB` and this IT cover command retention and the deletion of non-ACTIVE leases expired more than 30 days ago** —
      closed `agent_session` rows and `agent_event` rows are never deleted.
- [x] **Step 5: Commit** `feat(dp): agent retention through an owner-owned function on the daily loop (OR-77, item 47) (plan 015 T4.5)`.

**Lane time:** ≈ 1 h.

---

## W5 — the real agent, live gates and close

### T4.4 — The real-agent harness on our tree (lane C; **scheduled in W5**, P15-1)

Kept its id for traceability; it seeds through T4.3's API and `OR-27` (T4.1/T4.2), so it runs after W4.

**T3.4 note (2026-10-11).** Add a harness case in which the data plane answers `RetryAfter` (or `DO_NOT_ACK`) to one telemetry batch and the REAL Go agent retries it: telemetry for that placement must resume and the batch must land once. It guards the stall measured in the T3.2 note above, which no unit test on either side can show.

**Files:** TAKE→ADAPT `launcher/src/test/java/com/solace/sm/launcher/{RealAgentHarness,SyntheticAgent,FaultInjection,
MatrixEvidence,AgentTestSupport,ContainerStack}.java` and the seven ITs of [§4.2](#42-backend); new
`AgentRuntimeAdapterRealAgentContractIT` (the kit over the real Go agent); `launcher/pom.xml` profile `agent-live`.

- [ ] **Step 1:** Seeding: replace every SQL `INSERT` of placements, bindings, generations or telemetry maps with the
      API (T4.3) and `OR-27` (T4.1/T4.2). A test that still needs SQL names the gap and stops.
- [ ] **Step 2:** Replace `assumeTrue(go, docker, alpine)` skips with a profile: under `agent-live` a missing tool or a
      missing `solace-cache-engine:local` image **fails**; outside it the classes are excluded and the build log says so
      (our Docker-fail-closed rule).
- [ ] **Step 3:** Run `mvn -o -f backend/pom.xml -pl launcher -Pagent-live verify -Dit.test='RealAgent*,Agent*IT,ContainerPackagingIT'`;
      fix to green on our tree; record counts against the branch's (10, 17, 4, 2, 4, 2, 13). `AgentFailureMatrixIT`
      gains the real-agent version of the crash case: SM killed after `CommandReceived` of a `RESTART` → the container's
      Docker start time moves **once**.
- [ ] **Step 4:** The redaction IT passes over the captured JVM and agent logs (no payload, no digest, no token).
- [ ] **Step 5: Commit** `test(launcher): real-agent harness and failure matrix on our tree, fail-closed under agent-live (plan 015 T4.4)`.

**Lane time:** ≈ 3 h (incl. the serial `agent-live` run). **Why ADAPT:** "the largest saving is the Go agent and the real-agent harness" [An §0 item 5].

### T5.1 — G-A1, local (lane A, after T4.4's `agent-live` is green)

- [x] **Step 1:** Local stack (`backend/local/sm-local.sh`) + gateway listener + a CA keystore made per the runbook;
      the Go agent built from `go-agent/` at the wave head; a Cache container from `solace-cache-engine:local`, labelled
      `com.solace.sm.instance=<name>` and `com.solace.sm.config-file=<path>`, inside the token's envelope.
- [x] **Step 2:** Through the API and Postman only: issue a token (target name, envelope) → `sm-agent enroll` → the
      agent target appears in `listTargets` (`transport: agent`) → the Cache appears as an instance in the environment
      (`OR-27`) → declare-free apply with `restart-all` → `restart-observed` → metrics in `sm_metrics`
      (`source_discriminator = 'agent'`) → kill SM between `CommandReceived` and `CommandAck` of a `RESTART`, restart,
      outcome settles once and the container's start time moved once → `undeclareInstance`, reconnect the agent, the
      instance is **not** re-registered → drain → revoke (session closed, next connect refused) → `retireTarget`.
- [ ] **Step 3:** A dp-remote placement on the same install applies in the same run (coexistence), including while the
      agent is stopped (no starvation).
- [x] **Step 4:** Evidence (redacted) in `docs/plans/backend/evidence/015-g-a1.md`. **Commit** `docs: plan 015 G-A1 evidence`.

**Lane time:** ≈ 1.5 h.

### T5.2 — Runbooks, records, Results (lane B)

**T3.2 review note (2026-10-11), to the owner (§10):** `ControlPlaneLeaseIssuer.code` folds the certificate-class first-issue refusals (`NOT_ENROLLED`, `CERTIFICATE_MISMATCH`, `CERTIFICATE_EXPIRED`) into `ISSUE_NOT_ALLOWED`. On a first `SessionOpen` the gateway cannot tell them from the `current`/`issue` race, so such an agent is asked to renew, has no grant, and is closed by the open timeout (audited as `agent-renewal-timeout`) with no operator-visible reason beyond that audit row. A distinguishing code (an HF18 change) is needed before `SessionReject(RE_ENROLL_REQUIRED)` can be sent for it; not built. A lost first `SessionAccept` strands an agent the same way; routing it to `issue()` does not recover it, because the lease nonce is bound to the first attempt's request id and a reconnect's `SessionOpen` has a new one. The runbook must say how an operator recognises and clears this (revoke and re-enrol).

**Files:** ADAPT `docs/runbooks/backend/{enable-agent-gateway,restart-the-gateway-with-a-drain,revoke-or-change-an-agent}.md`
(no SQL provisioning; the container labels and the envelope); the release runbook's **corresponding-source-offer** line
for both images (`OR-127` L4) and the NOTICE obligations (L3); `README.md`, `backend/README.md`, `go-agent/README.md`;
this plan's Results; both PRs' descriptions; central items 44, 45, 47 narrowed by forward pointer only.

**T1.2 review notes (2026-10-09, findings 3 and 9):** (a) the runbook's build command carries no `-Dprotoc*` flag (`-DprotocExecutable`, `-DprotocPluginExecutable` and `protocPlugin{Tool,Toolchain}` swap the audited binaries; `check-protoc-executables.py` refuses them in poms and `.mvn/*.config`, not in a shell command). (b) The distribution's NOTICE carries the OpenSSL License text for the AIX/NetBSD code in `grpc-netty-shaded` and the **clause 3 advertising acknowledgment** ("This product includes software developed by the OpenSSL Project for use in the OpenSSL Toolkit"); the texts are in `backend/THIRD-PARTY-NOTICES.md`.

**T2.2 follow-up (2026-10-10, T2.1b review): the lease-nonce key.** `enable-agent-gateway.md` must make the operator create the **HMAC key entry in the CA keystore** before the first start, with the same keystore password as the CA key, e.g. `keytool -importpass -alias lease-nonce-hmac -storetype PKCS12 -keystore <ca.p12>` (or any tool that writes a PKCS12 secret-key entry of at least 32 random bytes), under the alias `sm.control.agent-gateway.ca.lease-nonce-key-alias` (default `lease-nonce-hmac`). Without it no lease is issued and, with `ca.required=true`, the process does not start. State that the key is read once per process (rotating it needs a restart and changes every outstanding nonce, so an agent mid-renewal re-enrols), is never logged, and is not backed up with the config. Verify the exact `keytool` invocation against the JDK in the image (it must produce a 32-byte-or-longer entry; the unit test `Pkcs12LeaseNonceSignerTest` measures that the JDK KeyStore API reads such an entry) before the runbook is merged.

**`OR-136` (4) (2026-10-10): the console behind a TLS reverse proxy.** `enable-agent-gateway.md` (or the install runbook) documents the operator-run TLS front: it serves exactly `SM_TRUSTED_ORIGIN`, forwards to the loopback-published console, and `SM_TRUSTED_PROXY` is the address its connections arrive from **inside the launcher container** (for a proxy on the Docker host, the compose network's gateway address, not 127.0.0.1). Show how to read that address (`docker network inspect`), and that the launcher refuses to start with neither a trusted proxy nor direct connections configured (`GatewayProperties`). Compose already carries this (W2 integration).

**`OR-138` (2026-10-10): database role passwords.** The install runbook documents: generation (`runtime/scripts/init-secrets.sh`, run once on the host before `compose up`; five 0600 files in a 0700 `./secrets`, 48 random alphanumeric characters each, never overwritten, `--chown 10001` on Linux so the image user can read its files); the secrets directory itself (**back it up like a key; it is not configuration**; a copy of it is a database credential, and it is gitignored); and **rotation** (`init-secrets.sh rotate all|ROLE...`, which keeps the live file until the database accepted the `ALTER ROLE`, then which services to restart). The runbook also states that `.env` carries no password and `docker compose config` shows none, and names the future `NOLOGIN`-migrator option as not built. It also documents `docker compose exec launcher /opt/sm/bin/sm-tool <main-class> ...` for the operator mains that read the runtime passwords from the environment (`SmControlLockoutRecoveryMain`, `BrokerConnectionMain`, `KeyRotationMain`, `TargetRegistrationMain`, `TargetConnectionMain`), the `--group 10001` file permissions on Linux, that rotating a runtime role is followed by a maintenance restart of the launcher (never automatic), and that a failed Phase 0 is repaired with `init-secrets.sh rotate all`.

**Lane time:** ≈ 1.5 h.

### T5.3 — G-A2, the E2E host (lane D; HF12) — after G-A1 is green; A9 granted on condition by `OR-128` (12), completed by `OR-134` (A9)

- [ ] **Step 0:** Run only after G-A1 is green. `OR-128` (12) grants A9 on condition and `OR-134` (A9) completes it. The four items: the agent process on the host, its lifetime, the loopback reverse tunnel to the DP, and the clean-up. If G-A1 is not green, this task is recorded as **not run**.
- [ ] **Step 1:** As G-A1 Steps 2–3 with a second Cache container; the existing Cache untouched (same start time
      before/after) and working afterwards.
- [ ] **Step 2:** Remove the agent and its container; evidence outside the repo's coordinates rule.

**Lane time:** ≈ 1 h.

### Final

One Fable 5.1 review of plan 015 (both PRs), one fix round, full gate, push fast-forward; the PRs into `UAT` (`OR-132` (1)) on the
owner's word, the agent PR first (§6.4). **Lane time:** ≈ 3 h.

---

## 7. Ids and migration numbers

**Rule:** next free at merge, in wave order. The table is that rule applied to today's expectation; T0.1 Step 1
re-measures and binds the symbolic names.

| Kind | Number | Task | Note |
|---|---|---|---|
| `sm_data` | **`VDA`** state = **V028**, **`VDB`** maintenance function = **V029** | T1.1 | Measured 2026-10-09 on `2568862f`: the latest is V027 (`host_key_algorithm`, `OR-125` (2)); V026 is the placement actor (`OR-125` (1)). `OR-125` (2): *"dp-remote takes `sm_data` V026 (and V027 if (1) needs its own), the Agent Gateway starts after them"*. §10 item 3 confirms the next-free reading |
| `sm_control` | **`VCA`** = **V021** | T1.3 | Measured 2026-10-09: V019 = `OR-125` (4) (username per organization), V020 = `OR-131` Q1 (operator rename; replaced `record_change_audit()` again). `VCA` rebuilds the function from V020's body |
| `sm_metrics` | **`VMA`** = **V004** | T3.4 | The latest is V003. `OR-130` F-6: `ingest_counter_reason` is a closed CHECK (V001:86), so `clock_skew` needs a migration (drop and re-add), its checksum line and the pinned `SmMetricsSchemaIT` counts |
| Catalog | rows **44–47** | T1.3 | 43 → 47; Administrator 47 / Operator 26 / Viewer 9 |
| Central open items | **42–47** | the commit that added this plan | the gateway's 34–39 re-registered |
| `AGW-D` | `AGW-D17`–`AGW-D22` (branch's, verbatim); **`AGW-D23`** deployment composed by the launcher, session at the DP (`OR-126` A2) · **`AGW-D24`** CP-issued lease, DP-supplied placement set (A3; supersedes `AGW-D15`'s lease and registry ledger by name, `OR-130` F-4) · **`AGW-D25`** the placement is the replica; registry not built (supersedes `AGW-D16` as label authority) · **`AGW-D26`** render once, in-memory hand-off, re-render-and-compare on redelivery; plain digest transient (A5; supersedes `AGW-D17` item 2's storage part) · **`AGW-D27`** agent telemetry re-sunk via the built port; ack on commit only (A7; amends `AGW-D9`) · **`AGW-D28`** report intake into the built tables (item 44) · **`AGW-D29`** `agent_event` (item 45) · **`AGW-D30`** `OR-27` discovery from container facts, cap, tombstones, completer (shape per §10 item 15) · **`AGW-D31`** retention on the `OR-77` pattern (item 47; supersedes `AGW-D22` item 2) · **`AGW-D32`** single-replica machinery (`OR-44`) | T0.3 text, T0.1 register | proposed allocation |
| `BE-D` | **`BE-D66`** blocking agent adapter behind per-placement routing; commands keyed on the intent key; fail-fast; the agent concurrency cap; `RuntimeOperationContext` · **`BE-D67`** agent-path parity and placement-level refusals · **`BE-D68`** `target_connection` transport `agent`; `acquisition_mode` `agent`; the `enrolled` event · **`BE-D69`** the launcher's three cross-plane adapters (a second composer beside `OR-10`'s) · **`BE-D70`** an agent may be the `sm_control` actor of `AGENT_ENROLLED`, `AGENT_INSTANCE_REGISTERED` (and `AGENT_LEASE_ISSUED` if a lease writes), with a gateway-minted correlation id and the peer's address (§10 item 5) · **`BE-D71`** the agent target: created in both planes at enrolment (CP then DP, `BE-D32` recovery), envelope from the token, `created_by` the issuing principal, and the contracted behaviour of every target operation on it | T0.3 text, T0.1 register | proposed allocation |
| `AG-D` | `AG-D13`–`AG-D20` verbatim; `AG-D21`–`AG-D25` reconstructed; **`AG-D26`** discovery by label, reporting facts · **`AG-D27`** health, start time and write precondition · **`AG-D28`** declarations from the wire · **`AG-D29`** the command payload is spooled on the host under a named `I6` exception, purged at terminal, swept at start; no digest in logs (`OR-129`) | T0.3 text, T0.1 register | proposed allocation |
| Plan | **015** (branch's "011" files kept as `015-history-*`) | T0.1 | |

## 8. Estimate (calibrated)

Revision 1 summed ≈ 43 agent-days and called it ≈ 20–21 working days; that was not calibrated against how this
pipeline actually runs (P15-11). Recalibrated as the review measured it against the combined 008/012/013 waves:
**pipeline hours** = elapsed time of one wave with four Sonnet lanes in parallel, its Opus review, one fix round and
the full gate.

| Wave | Longest lane (h) | Review + fix + full gate (h) | Elapsed (h) |
|---|---|---|---|
| W0 (overlaps dp-remote W5/W6) | 3 (T0.3 incl. its review) | — | ≈ 3, off the critical path |
| W1 | 4 (T1.1, T1.2) | 2 | ≈ 6 |
| W2 | 5 (T2.1a+b, T2.3) | 2 | ≈ 7 |
| W3 | 6 (T3.1; T3.3+T3.5) | 2 | ≈ 8 |
| W4 | 3.5 (T4.1+T4.2) | 1.5 | ≈ 5 |
| W5 | serial: T4.4 3 → T5.1 1.5 → T5.3 1 | 0.5 | ≈ 6 |
| Final | — | 3 | ≈ 3 |
| **W1 → Final** | | | **≈ 35 pipeline hours ≈ 4 long working days** |

- **Realistic elapsed: 5–7 working days** from W1 dispatch, **including owner turnarounds**.
- **What dominates:** (1) **per-wave verification** — the offline reactor `clean verify`, the Go gate, the Opus review
  and its fix round cost ≈ 1.5–2 h per wave whatever the lanes do; (2) **serial live gates** — `agent-live`, G-A1 and
  G-A2 run one at a time on one Docker, and G-A0 sits inside W3; (3) **owner rulings** — the items marked *before W1*,
  A9 for G-A2, and the PR approvals.
- **Added by revision 2:** T3.5 (+≈ 3 h, inside W3's longest lane), command identity and render-once (+≈ 1 h in T3.1),
  tombstones (+≈ 0.5 h in T4.1), the spool's sweep and mode checks (+≈ 0.5 h in T2.3). The moves of
  T4.4 into W5 and of T2.1a to W2's head cost no elapsed time.
- **What stretches it:** `protobuf-maven-plugin 0.6.1` unable to forward `@generated=omit` (`OR-127` L1 returns to the
  owner); a tool or shipped Go module the audit did not cover; the console tooling refusing a discriminated `oneOf`
  (T3.5 Step 0); `sshj` failing on Bouncy Castle 1.86 (T2.2 Step 4); netty/gRPC coexistence failing at G-A0
  (`grpc-netty-shaded` makes a clash unlikely [inferred, An §1.10]); Docker availability for the Go docker-tagged
  tests and `agent-live` (wedged once on 2026-10-07 [Br risk 7]); A9.

## 9. Not verified here (marked, not assumed)

- Whether `OR-127`'s audit plus T0.2 Step 3 covers every tool and shipped Go module, and whether 0.6.1 can forward
  `@generated=omit` (T1.2 Step 3a). `buf`, `protoc-gen-go` and `protoc-gen-go-grpc` are **known** not to be in the
  audit (measured); their licences are not stated here (`I2`).
- That `buf generate` resolves a positional directory input from the current directory (T1.4 Step 2); the buf rules
  quoted in T1.2 were read from buf's documentation on 2026-10-08.
- The name of grpc-java 1.68.1's remote-address transport attribute (T3.2 Step 1).
- That `sshj` works on Bouncy Castle 1.86 (T2.2 Step 4).
- The shape of `OR-125` (1), (2), (4)'s migrations — **measured 2026-10-09 on `2568862f`** (T0.1 Step 1); re-checked at the `UAT` rebase.
- That the console tooling accepts a discriminated `oneOf` (T3.5 Step 0).
- That the branch's suites pass on its own tip — not re-run (no Docker, no builds in this task).
- That `rewrite-timestamp` removal breaks `--check` [An §1.2].
- JCSMP + gRPC in one JVM with a live broker (G-A0).
- E2E-host reachability for an outbound agent [Br Q14].
- `AG-D21`–`AG-D25`'s intended text (no source exists).
- That no `ApplyWorker` edit from the branch is needed once there is no global switch [inferred]; the cap is a new edit.

## 10. What needs the owner

Each line: the options with their concrete delta, what does **not** change across them, and a recommendation. Items
**1, 2(b), 3, 5, 6, 14, 15** were needed **before W1** (all ruled by `OR-128`/`OR-129`); the rest at the named task. The **Ruled** column
records the ruling; rows 18-20 are the three owner items W0's review raised (none blocks W1 dispatch).

| # | Item | Options and delta | What does not change | Recommendation | Needed before | If not given | Ruled |
|---|---|---|---|---|---|---|---|
| 1 | **Approve this plan** (`OR-114`: an approved plan precedes code) | approve / return | — | approve | W0 | nothing starts | `OR-128` (1) |
| 2 | **Licences** (`OR-127` answered L1–L4). Returns only if (a) 0.6.1 cannot forward `@generated=omit` (T1.2 Step 3a), or (b) T0.2 Step 3 finds a tool or shipped Go module the audit did not cover — **known already:** `buf`, `protoc-gen-go`, `protoc-gen-go-grpc` | per finding: approve the measured licence, or name a substitute | the `I8` gate; nothing unapproved is merged | approve each tool once T0.2 has read its licence at its pin | (b) before T0.4; (a) at T1.2 | that task waits; nothing is substituted | `OR-127`; `OR-128` (2); `OR-130` L-a/L-b/L-c |
| 3 | **Migration numbers: the next-free reading** of `OR-126` A1's "from V028 / V019" | (a) next free after dp-remote (measured 2026-10-09: `VDA` V028, `VDB` V029, `VCA` V021) · (b) A1's literal numbers, leaving a gap or colliding with `OR-125` (4)'s V019 | ours never renumbered; `OR-125` (2) already says "the Agent Gateway starts after them" | (a) | W1 | (a) is followed (it is what `OR-125` (2) says) | `OR-128` (3) |
| 4 | **Consequence level and scope of rows 44–47**, and the permission of `issueAgentEnrolment` | proposed: enrol/revoke HIGH, drain STANDARD, view read; all environment-scoped; `issueAgentEnrolment` requires `agent.enrol` **and** `target.create` at the environment (the token creates a target) · alternative: `agent.enrol` alone | `OR-126` A8's rows and role grants | the proposal | T1.3, T4.3 | rows and roles land; level/scope wait; T4.3 waits | `OR-128` (4) |
| 5 | **An agent as audit actor** (`BE-D70`, `BE-D71`; P15-8, P15-2, P15-10): **amends `OR-25` by name** | (a) agent as actor for `AGENT_ENROLLED`, `AGENT_INSTANCE_REGISTERED` (+ `AGENT_LEASE_ISSUED` if a lease writes) in `sm_control`, for the `enrolled` event and the `OR-125` (1) placement actor in `sm_data`; `created_by` = the enrolment's issuing principal · (b) every agent-caused write attributed to the issuing principal, the agent named only in `agent_event` — no `OR-25` change, but `OR-27`'s "audited with the agent as actor" is then not met in `change_audit` | correlation id and client address stay mandatory; every other operation still fails closed without a principal | (a) | W1 (T1.1, T1.3) | `OR-27` and the enrolment-time target cannot be written; W3–W4 wait | `OR-128` (5) |
| 6 | **Install the toolchain by Homebrew** on the build machine: Go, `buf`, `protoc-gen-go`, `protoc-gen-go-grpc` (`protoc` only if a step needs it) | permit / refuse | the Java side's protoc is Maven-fetched either way | permit | T0.4 | the Go gate, T1.4, T2.3 and every real-agent run cannot run | `OR-128` (6) |
| 7 | **Agent sample timestamp:** accept the agent's `ts` inside a skew window ([Br Q7]) — departs from `OR-92` OQ-4 (receipt time) for this path | (a) window · (b) receipt time | the ingest port and dedupe | (a) | T3.4 | receipt time; 12 samples a minute collapse onto batch arrival | `OR-128` (7); window ±5 min `OR-130` F-6 |
| 8 | Confirm the agent path's cadence is `OR-73`'s fixed 5 s / 1 min and the package's `intervalSeconds` does not apply to it | confirm / apply `intervalSeconds` (a reviewed schema change) | the `http_json` metric shape | confirm | T3.4 | a reviewed `solution-package.schema.json` change is added | `OR-128` (8) |
| 9 | The enrolment token digest stays unkeyed SHA-256 of a 256-bit random token (reasoned outside `OR-32`'s scope) | keep / HMAC under a CP key (+≈ 0.5 h) | token entropy, one-time use | keep | T1.3 | HMAC | `OR-128` (9) |
| 10 | Brief rulings `OR-126` did not name, assumed as recommended: [Br Q4] (b), [Br Q6] (b) (now with fail-fast and the cap, so the worker-pool question of revision 1 is answered by design, not by the owner), [Br Q9] (a), [Br Q10] (b), [Br Q12] (a) | confirm / name another | — | confirm | W1 | the plan follows the recommendations | `OR-128` (10) |
| 11 | The per-target placement cap | default 16 / another | the cap exists and refusals are audited | 16 | T4.1 | 16 | `OR-128` (11) |
| 12 | **`OR-126` A9: the agent on the E2E host** (G-A2), naming the network path | grant with path / withhold | G-A1 runs either way | grant after G-A1 is green | T5.3 | G-A2 recorded as not run | `OR-128` (12) (conditional) |
| 13 | The PRs into `UAT` (`OR-132` (1)): the agent PR first, then the backend PR (§6.4) | approve each | — | approve in that order | Final | the work stays on the two plan-015 branches | `OR-128` (13); target `UAT`, `OR-132` (1) |
| 14 | **The Go agent's payload spool** (P15-5): `commands.go:258` persists the rendered payload as a `store.Blob` (0600 under a 0700 directory) until terminal; a crash leaves it behind | **(a)** keep it as an **accepted `I6` exception** for the host-local spool: 0600/0700, deleted at terminal, plus a startup sweep deleting every spool file without a live journal record (+≈ 0.5 h) · **(b)** hold the payload **in memory only**: drop the `Persist{Blob}` effect; after an agent restart a `RECEIVED` record without payload sends `CommandPull` for the same command id, and the DP re-renders and compares (T3.1) (+≈ 1 h Go) | `I6` in SM's own storage; the config file the agent writes on the host (the payload's destination); plain digests leave the agent's logs either way | **Ruled (a), `OR-129`** (recommendation was (b)): the spool stays as a named `I6` exception, purged once the outcome is pushed and at every terminal state, swept at start | W2 (T2.3) | — | **`OR-129` (a)** |
| 15 | **The `OR-27` completer's shape** (P15-16): `OR-113` (7) forbids application code enumerating tenants from data rows; `OR-122` (2) excepted the `ScheduleFirer` **for the `scheduled` strategy only** | **(a)** a background loop on the `ScheduleFirer` precedent, one more sanctioned enumeration, ruled by name (allow-list + boundary test) · **(b)** no enumeration: the DP's discovery intake, already tenant-bound by the agent's identity, calls a `dp-api` completion sink that `console-gateway` implements; recovery is the next `Connect`'s report or the sweep's tombstone | quota (`OR-40`), the agent as actor, the `PENDING` row as the durable contract, `I7` | **(b)**: no `OR-113` (7) amendment, and the CP write carries the RPC's real correlation id and client address | W4 (T4.2); ruled before W1 so T0.3's texts are final | — | `OR-128` (15): (b) |
| 16 | *(optional)* **`go-agent/oldGo-Agent/`** (35 files, its own `go.mod`) | **(a)** carry it (as `AG-D15` says: archived, not deleted) · **(b)** omit it and record in `AG-D15`'s changelog that the archive lives at `87e338e1:go-agent/oldGo-Agent` on `origin/dp-agent-integration` — lighter tree, but the archive then lives only as long as that branch | nothing builds or ships it either way | (a) | T1.4 | (a) | `OR-128` (16): (a) |
| 17 | *(optional)* The `solace-cache-engine` commit the fixture image is built from (no tags exist) | the checked-out `async-flow-test` @ `8b26dc9e` / a named commit on `main` | the image is local-only, never shipped | name `main`'s head if the Cache team considers `async-flow-test` unstable; otherwise as checked out | T0.4 | as checked out, recorded | `OR-128` (17) |
| 18 | **F-5 scope:** may `VDB` delete `agent_lease` and `agent_session` rows under `OR-77`'s extension? `OR-130` F-5 names `agent_command` and `agent_event` retention only | yes (the draft's lease and session deletes ship) / no (command retention only) | `agent_event` is never deleted; the highest `wire_generation` of each (agent, placement) is never deleted | confirm (one line) | T1.1 merges | command retention only (T1.1, T4.5) | **ruled by `OR-134`**: leases yes, sessions never |
| 19 | **F-6's ±5 min window:** on the **sample's age**, or on the **agent's measured clock offset**? (R-11: a window on age refuses every sample buffered for more than 5 minutes, so the 24-hour disk buffer preserves nothing older) | age / measured offset | the ±5 minute size, the `clock_skew` reason and `VMA` | the owner's | T3.4 | T3.4 waits | **ruled by `OR-134`**: the agent's measured clock offset |
| 20 | **Five Java coordinates the audit did not name at the resolved version** (licence audit §D item 3): `com.google.code.gson:gson:2.13.2` (POM Apache-2.0), `org.checkerframework:checker-qual:3.42.0` (MIT), `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` (Apache-2.0, via guava-parent 26.0-android), `io.micrometer:micrometer-core:1.15.12` (Apache-2.0) and `org.springframework.boot:spring-boot-actuator:3.5.16` (Apache-2.0); the last two are new to the reactor with `agent-gateway`. **The `I8` gate passes all five by POM licence name, so no exception line is needed; the confirmation is a record, not a gate line.** | confirm / name a substitute | the `I8` gate (a gate pass is not the owner's confirmation) | confirm | **T1.2 merges** | T1.2 waits | **Ruled by `OR-135`** (owner, 2026-10-09: all five confirmed; T1.2 review finding 5) |
| 21 | **Clock-skew batches (T3.4 review Q1):** a batch refused for skew (the agent's measured offset beyond 5 minutes) is **dropped from the agent's buffer for good** (acknowledged, counted `clock_skew`), as built and as `OR-134` literally reads. `OR-134`'s own reason is that the 24-hour buffer keeps its value, and samples buffered while the clock was right are lost with it. | (a) drop and count, as built · (b) count, then `DO_NOT_ACK` until the offset recovers, so the buffer holds the batches (needs the T3.2 `TelemetryReject` mapping) | whether skewed-period data is lost | (b) | T3.4 merges is not blocked; decide before the agent runs on a drifting host | (a) stays | open |
| 22 | **A `batchSeq` below the stream watermark (T3.4 review Q2):** the agent starts again at 1 when its stream-state file is unreadable, so every later batch is `<=` the DP's watermark and is acknowledged unforwarded and trimmed, silently, for as long as it takes to pass the old number (about 70 days at 100 000 batches). The bridge now counts `agent.telemetry.sequence.behind.watermark` and logs a WARN naming the placement; the acking is unchanged. | (a) accept as is · (b) refuse with a coded error so the agent surfaces it · (c) re-baseline the watermark (design section 3.2 withdrew re-baselining on the session epoch for reports) | whether a reset loses months of telemetry | (b) or (c), the `AG-D*`/AGW owners' call | before agents run in production | (a) stays, observable only | open |
| 23 | **Agent gap markers (T3.4 review Q3):** a marker names agent sequence numbers (`fromSeq`..`toSeq`), `metrics_gap` needs instants, and the bridge forwards none (counted `agent.telemetry.gaps.not.forwarded`). | a rule such as the `producedAt` of the bounding batches · or no gap rows for agent drops | whether an agent's buffer drops chart as gaps | the bounding-batch rule | before agent drops are charted | no gap rows | **Ruled by `OR-143`** (owner, 2026-10-11): the read-time derived gaps suffice; markers stay counted and not forwarded; no migration; the threshold residual is accepted |
| 24 | **Discovery tombstones are keyed per agent, so a re-enrolment re-registers everything, and nothing ever deletes one** (T5.2 review M5; AGW-D30). **Measured/read:** the key is `(tenant, agent, instance label, container name)` (`V028` primary key); a terminal state (`REFUSED`, `ABANDONED`, `UNDECLARED`, `RETIRED`) is final on that key, and only a `REFUSED`/`ABANDONED` key is released, by a re-created container (`V031`). A new agent id (every re-enrolment), a renamed container or a changed label is a new key and registers afresh, **including an instance an operator undeclared on purpose**. `agent_gateway_maintenance()` never touches `agent_discovery` and the runtime role has no `DELETE`, so rows accumulate per distinct (label, container name) ever reported (bounded per report: 64 sent, 256 read). The runbooks document this as it is. | (a) as built: operators keep a container out of management by removing its label; watch the row count (the runbook's read-only query) · (b) key the tombstone by `(tenant, target, label, container name)` so it survives a re-enrolment (an `sm_data` migration and an `AGW-D30` amendment; the agent's id then names who reported, not what is remembered) · (c) add terminal-row retention to `V029`'s function (an `OR-130` F-5 style ruling on how long a tombstone lives; an expired tombstone lets a removed container come back) | the agent supplies facts and never a state; a terminal state is final on its key | (b), if item 25 is answered (a) or (c), because every re-enrolment would otherwise resurrect undeclared instances; otherwise (a) | the first agent in production | (a) stays; the runbook's statement is the whole mitigation | open |
| 25 | **Agent client certificates live at most 30 days and nothing renews one in place** (T5.2 review M6; `OR-136`). **Read from the code, not run:** `SM_AGENT_CA_LEAF_TTL` (default 30d) is a ceiling on the leaf lifetime; the API carries no certificate expiry (`Agent` has no such field), so an operator reads `enrolledAt` plus the lifetime or `identity/cert.pem` on the host; an expired agent cannot connect and retries without halting; the only procedure is revoke, undeclare every instance, retire, delete the agent's data directory, re-issue, restart: every 30 days per agent, and every instance comes back as a **new** instance (item 24). Nothing alerts before expiry (`AGW-D*` leaves monitoring to the operator). | (a) as built: the full cycle each period, documented in `enable-agent-gateway.md` · (b) raise `SM_AGENT_CA_LEAF_TTL` (a configuration change; longer exposure of a stolen certificate, bounded by `revokeAgent`; any upper bound in the code was not checked) · (c) in-place renewal over the mTLS session (a wire message, a contract and `AGW-D*` change, new tests; estimate not made) · (d) an expiry field on `Agent` and an alert (a contract change) | revocation as the security cut-off; the CA and the gateway certificate (365 days) | (b) now, as the interim, and a decision on (c) before agents are fleet-scale | the first agent in production | agents fall off at 30 days and every one re-enrols to new instances | open |

### Owner questions raised by lane C's reviews (2026-10-11; none blocks W3)

**Q-C1, Q-C2 and Q-C3 are answered by `OR-140` (system actor, scheduled sweep, scheduled verdict), built by T4.6; the text below is the question as asked.**

- **Q-C1. Who is the actor of the enrolment sweep's two control-plane writes?** Today `AGENT_REVOKED` and `TARGET_RETIRED` are audited to the principal who issued the token, with the requesting call's correlation id and address, so a reader sees "principal X, from requester Y's address, revoked this agent" though X did not act. `BE-D32`'s sweeps write no control-plane row, so there is no precedent. Options (what does not change: the trigger, the window, revoke-then-retire): (a) the issuer (today; no migration; a fictitious attribution, and a disabled issuer "revokes"); (b) the requester (no migration; a Viewer's GET is recorded as a revocation); (c) a system actor (a new `sm_control` migration in `V020`'s shape and an `OR-25` amendment; visible and honest); (d) no control-plane write by a sweep (a degraded target stays listed degraded until an operator revokes and retires it; matches `BE-D32`; the name stays held). Reviewer's lean and the integrator's recommendation: **(c)**, as a scheduled job under a system actor, or (d) if a migration is unwelcome. Needed before G-A1.
- **Q-C2. Is a security-relevant control-plane write on a reach-level GET acceptable?** `listTargets` is open to any authenticated principal of the organization and the sweep runs before the reach filter, so any such call can trigger revocations (when, not what). Options: accept and record in `BE-D71`; run the control-plane half only for callers holding `target.edit` or the revoke verb at the organization; fold into Q-C1 (d).
- **Q-C3. May the agent staleness verdict be written by a scheduled job?** It is lazy today (on `getHealth`, per organization and instance) because a loop must enumerate organizations, a third cross-tenant read that `OR-44`/`OR-49` do not permit without a ruling. A ruling would let a job mark every organization's silent agents and fire `instance.health` for instances nobody is reading. Recommendation: rule yes only together with Q-C1 (c), so both jobs share one system actor.

### Owner question raised by T4.4's review (2026-10-12; owed to Final's fix round under the full gate)

- **Q-T44-1. What state should a restart-bearing apply reach when the agent's session is lost after the RESTART was delivered?** Observed with the real agent (M17, `AgentFailureMatrixIT`): a LIFECYCLE adapter exception after a restart was sent lands terminal `CONFIG_DIVERGED` (`ApplyExecutor:553-558` maps any `RuntimeAdapterException` from `startOrUpdate`, with no retry), while the command row ends `ACKED:APPLIED` and the host restarted exactly once. `AgentRuntimeAdapter:452-457` ("the command stays as it is for the engine's retry") and the engine's own `APPLY_INDETERMINATE` wording (`ApplyExecutor` `GateVerdict.Unavailable` comment) say otherwise, and the SIGKILL case (M16) ends better (`INDETERMINATE_RESOLVED`, resolved as applied) than the graceful one. Options: (a) land `APPLY_INDETERMINATE` for a delivered-then-lost LIFECYCLE step, so the reconciler resolves it; (b) re-await the same command (command identity makes that safe); (c) keep `CONFIG_DIVERGED`. Not changed: the journal, the command row, the host. Any change is `dp-core` main code, so OR-144 (3) requires the full gate. M17 records the observed state and asserts none of it. Recommendation: (a), as the smaller change that matches the engine's own vocabulary. Owner to rule.

### W3 integration notes (2026-10-11, from the Opus seam review `w3-int-review.md`)

- **Contract kits owed for two ports (minor 6).** `AgentObservedStatePort` and `AgentTelemetryDeclarationsPort` have no contract kit; the arch inventory lists them as `UNKITTED_AGENT_GATEWAY_PORTS` (renamed from `CONTRACTED_STUB_AGENT_GATEWAY_PORTS`, which was a misnomer once the stubs became real classes). `AgentObservedStatePort` is dp-core-internal (the gateway module may not name it), so it needs none for extraction. **`AgentTelemetryDeclarationsPort` is called by the gateway, so AGW-D2's "every in-process port has contract tests a future remote adapter must pass" is UNMET for it.** A kit (`AbstractAgentTelemetryDeclarationsPortContractTest`: fenced session, active-instance-only, the empty-set clear) was not written in the fix round: it needs a new `AgentKitWorld` accessor plus the collection, ingest and placement rows behind the declarations in the kit world, which is more than the 1 h bound. **Owed to W4 or an owner waiver (review Q2), before G-A1.** `AgentTelemetryBridgeIT` over the real implementation is what pins it today.
- **`AGW-D28` item 2 (the ack's `container_started_at` as `write_baseline`; review finding 3).** Recorded here as OPEN while the fix round ran; the owner has since ruled it (`OR-139`: build it as written, owed to the W3 integration fix round before the W3 push). It was built in a second pass of the fix round (below).

## 11. Review dispositions (2026-10-08)

The adversarial review of revision 1 (22 findings; verdict *"not approvable as written"*). Each row says what changed
and where, or why not.

| Finding | Severity | Disposition |
|---|---|---|
| **P15-1** forward and cross-lane dependencies; W2's gate cannot go green | BLOCKER | **Restructured.** New **T2.1a** (W2, merged first) declares **every** `dp-api/agw` signature incl. `AgentDiscoveryPort`, `AgentTelemetryDeclarationsPort` and the new `AgentObservedStatePort`, and owns the mechanical compile fixes in `agent-gateway` (and any launcher file present); T2.2 branches from T2.1a and writes the launcher adapters against it; T2.1b ships **contracted stubs** that the implementing task fills in (T3.3, T3.4, T4.1). T3.1 reads status through `AgentObservedStatePort` (fake in its tests). **T4.4 moved to W5.** Global constraint 11; §6.1 merge orders; HF18 |
| **P15-2** the agent target fits neither schema nor contract; nothing creates it | BLOCKER | **New T3.5** (W3, lane C): CP target row then DP `target_connection` at enrolment, `BE-D32` order and recovery (replayed `Enroll` + sweep); the envelope rides `issueAgentEnrolment` and `agent_registration` (T1.3, T4.3) and stays NOT NULL (T1.1); `VDA` extends `target_connection_event` with kind `enrolled` (no fingerprint) and `actor_kind`; `created_by` = the issuing principal (§10 item 5); placement required columns sourced per T4.1's table (labels, mounts, published ports, package, declare defaults); `console.yaml` `Target.transport` widened and `TargetConnection` a discriminated `oneOf` (T0.3, T3.5); every existing target operation's behaviour tabled in T3.5 (edit/rotate/test `409`, scan unchanged, retire after revoke, manual `addPlacement` refused). `BE-D71` |
| **P15-3** crash recovery between journal and `agent_command`; double `RESTART` | MAJOR | T3.1 "Command identity": key = the intent's attempt-free `idempotencyKey(id, step, replica)`; `enqueue` returns the live or succeeded command; new generation only after a terminal failure; `supersedeOutstanding` on resume; T1.1 `intent_key` + one-live-per-key index; `AgentCommandCrashRecoveryIT` (restart during `RESTART` → exactly once), real-agent version in T4.4, G-A1 |
| **P15-4** render-at-pull renders twice; status renders every candidate per poll | MAJOR | T3.1 "Render once": `AgentPayloadHandoff` by command id from the engine's `RenderedConfig`; re-render only on a miss after restart, compared with `agent_command.config_hash`, refused `agent.payload.render.changed`; `AgentHashBridge` map, at most two renders per placement per process; spy tests. Global constraint 1 |
| **P15-5** the agent persists the payload; logs `config_sha256` | MAJOR | **Owner item 14** ((a) accepted `I6` exception + crash sweep vs (b) memory-only + re-pull; recommend (b)); the log digest is removed regardless (T2.3, Global constraint 2); `AG-D29` |
| **P15-6** an offline agent can starve dp-remote | MAJOR | Not an owner item. T3.1: fail-fast `agent.session.absent` via `AgentSessionPort.liveFor`, `agent.session.lost` mid-wait, and a claim-time cap `sm.dp.agent.max-concurrent-requests` < pool size (boot-validated); `AgentStarvationIT`; HF22; G-A1 Step 3. Revision 1's §10 item 10 pool question removed |
| **P15-7** buf v2 module paths must be inside the workspace | MAJOR | Verified by WebFetch of buf's docs (quoted in T1.2): the workspace is `contracts/agent/v1/buf.yaml` (`path: .`); `go-agent/buf.gen.yaml` generates with `buf generate ../contracts/agent/v1` from `go-agent/`, output unchanged; lint not carried (recorded). HF14, T1.4 Step 2 |
| **P15-8** `OR-25` fail-closed on gRPC writes with no principal | MAJOR | `BE-D70` widened to `AGENT_ENROLLED`, `AGENT_INSTANCE_REGISTERED` and (if T2.2 Step 0 finds one) the lease-time write; correlation id gateway-minted per RPC and client address from the gRPC peer via `AgentRequestOrigin` (T2.1a, T3.2); ITs in T1.3, T2.2, T3.5, T4.2. **Owner item 5** (it amends `OR-25`) |
| **P15-9** undeclared/retired containers re-registered; the sweep re-mints | MAJOR | T4.1 "Tombstones": terminal `REFUSED`/`ABANDONED`/`UNDECLARED`/`RETIRED` per `(agent, label, container name)`, set in the same transaction as undeclare, retire and the sweep; release rules; ITs `undeclareThenReconnect…`, `sweepThenReconnect…` and four more; T1.1 key and states; `AGW-D30` |
| **P15-10** `sm_control` V019 taken; "V018's body"; `OR-125` (1), (2) | MAJOR | Symbolic `VDA`/`VDB`/`VCA`; `VCA` expected V020; `record_change_audit()` from the latest body measured at T0.1 Step 1; `VDA` widens `OR-125` (2)'s key-algorithm column into the shape CHECK and `OR-125` (1)'s placement actor for agent rows; DDL finalized after the dp-remote merge (T0.3 Step 3). Global constraint 6, §7 |
| **P15-11** estimate not calibrated | MAJOR | §8 replaced: ≈ 35 pipeline hours W1→Final ≈ 4 long days; realistic 5–7 working days incl. owner turnarounds; dominated by per-wave verification, serial live gates, owner rulings. Per-task sizes are now lane hours |
| **P15-12** protoc executables outside both licence realms; Go gate over `go list -m all` | MINOR | T0.2 Step 2: `check-protoc-executables.py` pins both `exe` coordinates (planted proof); Go gate scoped to `go list -deps ./cmd/sm-agent` (T0.2 Step 3, T1.4 Step 4) |
| **P15-13** "no other Bouncy Castle resolved" is false | MINOR | T2.2 Step 4: pin `bcprov`/`bcpkix`/`bcutil` 1.86 in `dependencyManagement`; assert the launcher and `dp-core` resolve 1.86; `sshj` suites must stay green; 1.80 lines removed only if unresolved |
| **P15-14** two-PR split undefined | MINOR | New §6.4: paths per PR, which tasks, the agent PR (with `contracts/agent/v1`) merges first, `integration/agent-gateway` merges `integration/go-agent` at each integration, a per-commit split check in the full gate. Global constraint 12 |
| **P15-15** 37 types, not 28; actuator missing from the allow-list | MINOR | §4.2.1 disposes of all 37 (4 dropped, 33 kept or adapted, 8 new); `spring-boot-actuator` added to T1.2's allow-list |
| **P15-16** a background completer enumerates tenants | MINOR | **Owner item 15** ((a) the `ScheduleFirer` precedent, `OR-122` (2), ruled by name vs (b) no enumeration, tenant-bound completion sink; recommend (b)); T4.2 written for both |
| **P15-17** weak-reference GC test is nondeterministic | MINOR | T3.2: `AgentGatewayRetainsNoPayloadTest`, a structural walk of reachable fields (no `byte[]`/`ByteString`/offer), with a planted fixture |
| **P15-18** shared files without owners | MINOR | HF11 `InventoryFixtures` (T2.1b adds `agentTarget`; read-only after), HF19 `CollectionPlanBuilder`/`HealthPoller` (T3.1 only; T3.4 forbidden), HF20 `ErrorCode.java` (W3 C, W4 B), HF21 launcher adapters, HF22 the worker |
| **P15-19** tenant from `agent_id` needs global uniqueness | MINOR | T1.1 `agent_identity_agent_id_global` and `…thumbprint_global`; Global constraint 3 names the one unbound read; T2.1b adds it to `TenantEnumerationBoundaryTest` with a call-site rule and a planted caller |
| **P15-20** ~15 decision texts written only after approval | MINOR | T0.3 Step 4 drafts every new decision's full text into `decisions-015-draft.md`, reviewed in T0.3 Step 5; T0.1 copies them |
| **P15-21** Cache image source unstated | MINOR | T0.4 Step 3: `solace-cache-engine:local` built from the sibling repo as `backend/demo/README.md` does; no tags exist (measured), commit recorded; optional §10 item 17 |
| **P15-22** nits | NIT | G-A0 uses a Java gRPC client (§6.3, T3.2); T3.3 Step 5 rewritten; T3.1's map uses `MaterializeResult(writtenHash, runtimeRef)` and routes the start time through the report; `IngestResult.Pending` handled (T3.4); the rule is `onlyAgentGatewayTouchesGrpc` everywhere, the branch's name recorded as its origin; `DECISIONS.md` row 42 now says it was re-registered *"in the commit that added plan 015, not by its T0.1"* |
| Review's cheaper paths | — | **W0 runs now** beside dp-remote W5/W6 (status block, §6.1); decision texts folded into T0.3; signatures front-loaded (T2.1a); T4.4 in W5; structural test; Go gate scoped to the shipped binary — **all taken**. Replacing `oldGo-Agent/` with a pointer is offered as optional §10 item 16 (recommend carrying it) |

---

## Results

*(Filled during execution: T0.1's measured base and the bound `VDA`/`VDB`/`VCA`, every task's red/green counts,
mutation proofs, gate evidence, the toolchain and Cache image versions, and the reviews' dispositions.)*

### T0.1 Step 1 — the measured base (2026-10-09, on `i015/backend`; base `integration/dp-remote-demo` @ `2568862f`; PR #174 into `UAT` open)

Re-check at the `UAT` rebase (`OR-133` (3)). No renumbering was needed.

| Symbol | Binding | Why |
|---|---|---|
| `VDA` | `sm_data` `V028__agent_gateway.sql` | latest is V027 `target_host_key_algorithm`; V026 is `placement_actor` |
| `VDB` | `sm_data` `V029__agent_gateway_maintenance.sql` | |
| `VCA` | `sm_control` `V021__agent_registration_and_verbs.sql` | V019 = username per organization (`OR-125` (4)); V020 = operator rename (`OR-131` Q1) |
| `VMA` (new) | `sm_metrics` `V004` | latest is V003; `OR-130` F-6 `clock_skew` (T3.4) |

Shapes the final DDL accounts for: `record_change_audit()` was last replaced by `sm_control` V020 (= V018 + `HIERARCHY_NODE_RENAMED` in the
no-actor list + a gate allowing it only on `organization` from client address `operator-cli`); the readiness query
`ControlPlanePersistenceConfiguration.V020_AUDIT_FUNCTION_QUERY` requires the markers `no-actor HIERARCHY_NODE_RENAMED`, `operator-cli`,
`ROLE_BINDING_REVOKED`, `INSTANCE_UNDECLARED`, `DASHBOARD_RESET`, `document_sha256`; the `audit_operation` CHECK is unchanged since V018 (23
members). `placement` (V026): `created_by uuid NULL`, `retired_by uuid NULL`, no kind column (V026 says an agent's id goes into the same
columns), so `VDA` needs no placement DDL. `target_connection` (V027): `host_key_algorithm text NULL` with a CHECK over five types; NULL means
unknown by design. `sm_control.target`: still V013's `target_transport IN ('ssh-docker')`. `target_pinned_key_recorded()` is defined only by V012.
Next free: `BE-D72`, `AGW-D33`, `AG-D30`, `OR-134`, catalog row 48, central open item 48 (allocated: `BE-D66`–`71`, `AGW-D17`–`32`,
`AG-D13`–`29`, `OR-126`–`133`, rows 44–47, items 42–47).

### T0.3 — Results (2026-10-08, `x/agw0c`, Sonnet 5.5 implementing; review by Opus)

- **Done:** the four drafts under `docs/design/backend/agent-gateway/` — `proto-amendments-015.md` (wire; field numbers; compiled and
  descriptor-compared against `87e338e1`: 0 append-only violations), `agent-console-contract-draft.yaml` (five operations, the agent
  target `oneOf`, five `ErrorCode` members, the per-operation refusals; merged into `x/w4-int`'s console in scratch: refs resolve),
  `schema-015-draft.sql` (`VDA`, `VDB`, `VCA`; replayed on a throwaway `postgres:14` over sm_data V001–V025 and sm_control V001–V018;
  94 probes PASS), `decisions-015-draft.md` (25 texts: `AGW-D23`–`D32`, `BE-D66`–`D71`, `AG-D21`–`D29`).
- **Step 5:** one Opus review (read-only `claude -p --model opus`; the Agent tool was not available to the implementer) — 1 BLOCKER, 12
  MAJOR, 10 MINOR; one fix round; all 23 dispositioned in `proto-amendments-015.md`.
- **Left, and why:** Step 3's **final** DDL (after the dp-remote merge: `OR-125` (1), (2) and the audit function's latest body are
  `[AFTER-MERGE]` markers in the draft). `AG-D29` records both spool variants and decides neither (`OR-128` (14) open).
- **The plan's text should follow** (found by the drafts): `target_host_key` trigger NULL guard (G-1), `sm_control.target.transport`
  (G-2), audit operations `AGENT_ENROLMENT_ISSUED`/`AGENT_REVOKED` (G-3), solution/package on the issue body and in `agent_identity`
  (G-4, R-3), `DiscoveryResult.binding_id` (F-1), the `rematerialize` ceiling (R-7), `search_path = sm_data, pg_temp` (R-22).
- **Needs the owner / plan owner:** D-4 (a Docker health check standing in for the declared probe), F-4 (`AGW-D15`'s ledger superseded
  in effect), F-5 (cross-tenant `DELETE` by constants vs `OR-77`), F-6 (skew window size; `ingest_counter` reason), `OR-128` (14).

### T0.1 — Results (2026-10-08, `x/agw0a`, Sonnet 5.5 implementing; review and full build later)

- **Done (Steps 2–5), in two commits split by path (§6.4):** the **agent-PR** commit (`go-agent` registers and TAKE files) and the
  **backend-PR** commit (everything else).
  - `AG-D13`–`AG-D20` verbatim (as a pure-addition patch from `87e338e1`); `AG-D21`–`AG-D25` reconstructed, each marked *"no source
    text existed"*; `AG-D26`–`AG-D29` from T0.3's reviewed texts. `AGW-D17`–`AGW-D22` verbatim; `AGW-D23`–`AGW-D32` from T0.3. `BE-D66`–`BE-D71`
    from T0.3. All PROPOSED; supersessions by citation; no earlier row edited (`I3`) — the single in-place edit is the stale link
    in `backend/decisions.md`'s 2026-09-08 changelog row (`../agent-gateway/decisions.md` -> `agent-gateway/decisions.md`; the plan's
    ":2319" was line 2325 on this tree).
  - TAKE: `go-agent/specs/*`, `plans/go-agent/001`–`005`, `archive/go-agent-fork/*`, the 2026-10-05 integration spec, the two history
    plans and the seven history evidence files (`011-…` -> `015-history-…`), plus the eight `raw-*` evidence files they cite
    (`015-history-raw-…`, which the plan did not list: the evidence files name them, so without them the references dangle). Only
    file names, the links between history files and a one-line "history, not authority" note were edited.
  - `port-matrix.md` ADAPTed to §4.2.1 (a restatement from the plan and the decision texts, not a measurement; T2.1a is the first
    compile against it). `D10-G2-APPROVAL-2026-09-15.md` §1.10: rows 44–47, level and scope as `OR-128` (4) rules, in the verb-first
    (uncounted) form of §1.9 so the code mirror stays 43 until T1.3 appends §1.10.1. `DECISIONS.md` ratification matrix and
    `traceability.md` (REQ-005/006/007/014) gained the new ids. Central items 42–47 already existed.
  - Step 3: every "open item 34"–"39" hit under `agent-gateway/` and `go-agent/` has a forward pointer in its own file (changelog row
    or an appended section; no text edited). The plan's grep still lists them, as it matches the old text by design.
- **Step 4 checks:** `git diff --cached --check` clean (three nested `.gitattributes` under the taken agent-PR directories and one for
  the raw evidence turn off trailing-whitespace/EOF-blank checks for verbatim markdown hard line breaks and raw test output;
  nothing else is exempt). `./contracts/run-checks.sh` passes; `--strict` fails only on the four checks that need `node_modules`
  (checks 7, 8, 9, 14 — the worktree has none; docs-only change). Link check over changed markdown: the only new dangling link is
  the history plan's `../../runbooks/backend/enable-agent-gateway.md` (T5.2 writes the runbook); the rest were already dangling
  (a deleted `V001`, a waiver path, `demo-launcher`).
- **Left, and why:** **Step 1** (measure `VDA`/`VDB`/`VCA`, the latest `record_change_audit()` body, `OR-125` (1)/(2) shapes, next free
  ids) waits for the dp-remote merge; until then every id above is the plan's allocation and may need renumbering before W1.
  `docs/design/go-agent/design.md` was **not** taken (the plan does not list it; the branch adds two open items, `AG-12` and `AG-13`,
  there), and `docs/plans/backend/evidence/README.md` has no rows for the history evidence (not listed either).
- **Needs the owner:** `OR-128` (14) is still open: `AG-D29` records both spool variants and decides neither (the owner's comment on item
  14 is recorded in the draft as input, not a ruling). `BE-D69` and `AGW-D23` carry their `I7`/`B/D16` flags unresolved.

### T0.2 — the licence gate (lane B, Sonnet 5.5, 2026-10-08, worktree `agw0b`; review and full build pending)

- **Added:** `backend/scripts/test-licence-gate-present.sh` (run-checks step 16: plugin + both executions bound to
  `verify`, the four `licence-*` files, `check-licences.py`, `test-check-licences.sh` and its pass, the
  `Proprietary - Solace` block; **13 PASS** including 12 planted deletions on a scratch copy) and
  `backend/scripts/check-protoc-executables.py` (step 17: only the two audited coordinates, literal versions, no other
  `:exe:`, no `exe` type/classifier, no local `protocExecutable`; `--self-test` **10 PASS**, run in the same step).
  `docs/tasks/licence-audit-2026-09.md` gains the "Addendum 2026-10-08" section (OR-127 applied, coverage diff, owner list).
- **Plan wording corrected in practice:** "the five licence files and `check-licences.py`" is **four** `licence-*` files
  plus the script (the audit's "five" counts the script); the check requires all of them.
- **Finding for the port (HF13):** the branch writes the protoc coordinates via `${protobuf.version}`/`${grpc.version}`;
  step 17 rejects property-indirected versions on purpose (a `-D` flag would swap the audited binary), so the ported
  plugin configuration must spell `3.25.5` and `1.68.1` literally.
- **Coverage diff, summarised:** Go: all 32 shipped third-party modules (`linux`, `CGO_ENABLED=0`, Go 1.26.4,
  sha256-verified scratch toolchain, not installed) are in `[Au §2]` at the exact version; no gap. Java shipped:
  gson resolves 2.13.2 (not 2.11.0), `checker-qual 3.42.0` and `listenablefuture 9999.0-...` are new, all clear;
  **`animal-sniffer-annotations 1.24` would fail the gate** (POM name "MIT license" is not a mapped name). Build
  plugins: **four coordinates the audit did not name and `OR-127` does not cover** (`jsr250-api 1.0` CDDL-1.0; `sisu.inject`
  and `sisu.plexus` `0.3.3` EPL-1.0; `plexus-utils 3.1.0` version only). Tools: `buf` (1.73.0 read; **no pin on the
  branch**), `protoc-gen-go` v1.36.12, `protoc-gen-go-grpc` v1.5.1 (committed header) / 1.6.2 (Homebrew): all permissive.
  **Returns to the owner before T1.2 merges:** the `animal-sniffer` mapping and the four build-tool coordinates.
  For T0.4: install `protoc-gen-go-grpc` at `v1.5.1`, not Homebrew's 1.6.2 (T1.4 Step 2's byte-comparison), and
  whether `buf generate` reproduces the committed `protoc v4.25.5` header line is unmeasured.
- **`run-checks.sh --strict` in this worktree:** steps 13, 13b, 16, 17 and every Python step green; steps 7, 8, 9 and 14
  report "did not run" (`contracts/view-models/node_modules` and `frontend/node_modules` are absent in a fresh worktree;
  `frontend/` is out of this lane's scope) so `--strict` exits non-zero for that reason alone.
- **Side effect, repaired:** the first scratch `mvn dependency:resolve-plugins` ran online against the shared `~/.m2` and
  pulled the never-run site/deploy plugins' dependencies (jetty 9.4.46, sisu 0.3.5, aether, ...), which turned step 13
  red for every worktree (`check-licences.py`'s offline realm gained 25 unapproved coordinates). The 263 files that run
  downloaded (identified by a fresh `.sha1` sibling) were removed, and `check-licences.py` is back to
  `OK (156 build-plugin realm artifacts, 63 exception lines)`. Scratch Maven resolutions must use a private
  `-Dmaven.repo.local` (lesson recorded in `docs/tasks/lessons.md`). T0.4 Step 2's priming of `protoc` and
  `protoc-gen-grpc-java` should be checked against the same step-13 result afterwards.

### T0.4 — toolchain and the Cache image (lane D, Sonnet 5.5, 2026-10-08, worktree `agw0d`; review pending)

- **Step 1, installed (Homebrew, `OR-128` (6); licences cleared by T0.2 Step 3, Addendum 2026-10-08 §C.4):** Go
  **1.27.1** (`go.mod` says `1.26.4`; Homebrew offers only 1.27.1, the version the branch's evidence ran), `buf`
  **1.73.0**, `protoc-gen-go` **v1.36.12**. `protoc-gen-go-grpc` **v1.5.1** by
  `GOBIN=$HOME/go/bin go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1` (not Homebrew's 1.6.2: the
  committed `*_grpc.pb.go` header says 1.5.1 and T1.4 Step 2 compares byte-for-byte; T0.2's recommendation). `~/go/bin`
  is **not on `PATH`**: a later step that needs the plugin puts it there explicitly (`PATH=$HOME/go/bin:$PATH`).
  **Not requested but pulled in:** Homebrew's `protoc-gen-go` formula depends on the `protobuf` formula, so a
  `protoc` **libprotoc 36.2** (`/opt/homebrew/bin/protoc`) and `abseil` 20260817.0 are now on the machine. T0.2 read
  `protoc` only at 3.25.5; no step calls this binary (`buf` compiles `.proto` itself; Java uses the Maven executable
  below). Its licence was not read at 36.2 (protobuf is BSD-3-Clause upstream, by recollection, not verified: `I2`).
  It is a build-machine tool, never shipped; flagged to the owner.
- **Step 2, `~/.m2` primed online (`mvn dependency:get`, run from a scratch directory, nothing of ours installed):**
  `com.google.protobuf:protoc:3.25.5:exe:osx-aarch_64` (7,743,952 B, sha256 `c990aaabeda2fe040ba6f9f93f719abfece30fd20bfb07ad094b14629f2c066b`; runs, reports
  `libprotoc 25.5`) and `io.grpc:protoc-gen-grpc-java:1.68.1:exe:osx-aarch_64` (6,221,952 B, sha256
  `c2fb72eaefd4824833007b51ae49ab16f9157bbba98fdae0e379035a4e3fa7c9`). Host classifier
  `osx-aarch_64`. Sizes match T0.2's Central listing.
- **Step 3, the Cache image:** `solace-cache-engine` has no tags; the sibling checkout is branch `async-flow-test` @
  **`8b26dc9e49fabef5327ffda774e5803dafcfe4fc`** ("Update Dependabot configuration for Maven and daily checks",
  2026-08-04), working tree clean. To leave that repo untouched (no `target/` write), the commit was exported with
  `git archive` into scratch, built with `mvn -B package -DskipTests` (65,930,889 B jar), and
  `docker build -t solace-cache-engine:local` ran through the docker lock. **Image id
  `sha256:9c0a9f957b44c6af8a556360de26c89688499715846fc3dd427bfda7e545112f`** (manifest list; arm64; 271,911,510 B).
  The sibling repo's `git status` is still clean. No tag existed before; the image is local-only.
- **Left:** whether `buf generate` reproduces the committed `protoc v4.25.5` header line stays unmeasured (the
  committed proto is not in this worktree's base; T1.4 Step 2 measures it).

### W0 integration and review (2026-10-09)

**Integration.** The four lanes' W0 commits were integrated on `integration/dp-remote-demo` @ `2568862f` (`OR-133` (3)): `i015/agent` carries the
agent-PR paths and `i015/backend` the rest, with `i015/agent` merged in. Two conflicts were resolved by keeping both sides: the backend
`docs/design/backend/decisions.md` changelog (both 2026-10-08 rows kept) and this plan's Results (all four lane subsections kept, unedited).
The Opus review of `2568862f..982e702b` found the integration itself sound (no loss, no duplicate, no id collision; the split check passes per commit;
`run-checks.sh` is split by hunk, finding 15) and returned **24 findings**; verdict: W0 integrable after one fix round, W1 dispatchable once findings
1, 2, 10 and 11 land. This fix round (agent commit `f59403f7`, backend commit `51fb8a2e`, then `i015/agent` merged into `i015/backend`) answered them:

| # | Disposition |
|---|---|
| 1 | Done. The shape CHECK puts no constraint on `host_key_algorithm` for `ssh-docker` and requires NULL on the agent leg (plan T1.1, draft, `BE-D68`); new `AgentSchemaIT` cases. Replay: a legacy SSH row with a NULL algorithm survives `VDA`; an agent row with it set is refused `23514`. |
| 2 | Done. `VCA` = V021; the draft's audit function is rebuilt from V020's body (V020's gate and marker comment verbatim) with `AGW-EDIT 1..7` re-applied; readiness must keep the V020 markers; probe: the operator-cli rename on `organization` is still accepted. See the replay below. |
| 3 | Done. `AG-D29` rewritten in place for `OR-129` (agent PR) with a changelog row; the register, matrix, traceability, `proto-amendments-015.md` and plan T2.3 follow; the §10 item 14 row reads "Ruled (a), `OR-129`". |
| 4 | Done (text only; no `.proto` edited). `OR-130` D-4: `HealthProbe` as `TelemetryDeclarations` field 5 and `HealthObservation` fields 4-6 (new messages in the draft, no collision with the base proto), `AG-D27`, `AGW-D28`, plan Goal/T0.3/T2.3/T4.1 and the "six wire additions" count. |
| 5 | Done. `AGW-D27` item 5 is ±5 minutes (`OR-130` F-6); `VMA` = `sm_metrics` V004 in §7 and T3.4; R-11 recorded as stated; **open owner question (§10 item 19): the window on sample age or on the agent's measured clock offset, before T3.4.** |
| 6 | Done. `AGW-D31` records `OR-130` F-5 by name; `VDB` ships command retention only until the owner confirms the lease and session deletes (**§10 item 18**, before T1.1 merges); T1.1 and T4.5 say so. |
| 7 | Done. "Supersedes `AGW-D15`'s lease and registry ledger by name (`OR-130` F-4)" in `AGW-D24`, the matrix, the changelog row and plan §7. |
| 8 | Done. `OR-130` L-a/L-b/L-c recorded in licence audit §D, `traceability.md`, and T1.2 Step 3 (the five exact lines land in T1.2's commit, with an authority comment). The T0.2/T0.4 lane subsections above are not edited: their "returns to the owner" and "flagged to the owner" lines are superseded by this entry. |
| 9 | Done. gson 2.13.2, checker-qual 3.42.0 and listenablefuture 9999.0-empty-... added to licence audit §D item 3 and **§10 item 20** ("owner confirmation before T1.2 merges"). |
| 10 | Done. Status block, title, §6.1, §6.4, Final, §10 item 13, §7, §8, §9 now say `UAT` and the `2568862f` base; T0.1 Step 1 is ticked and the measured base is at the top of Results. |
| 11 | Done, both ways: T1.1 and T1.3 say the reviewed draft is authoritative and each listed line is corrected (G-1 to G-4, F-1, R-7, R-22, R-1, R-6, R-13, D-C2, D-C3, T4.2's "If (a)" branch deleted, §1 marked ruled by `OR-128` (7), (10), (15)). |
| 12 | Done. `BE-D67`, `AGW-D27`, `AGW-D28` review-stage wording replaced; changelog rows added. |
| 13 | Done. `BE-D70` exemption list and item 6, `BE-D68` widened list. |
| 14 | Done in the agent PR: `AG-12`/`AG-13` taken into `docs/design/go-agent/design.md` §13.2 from `origin/dp-agent-integration`; `AG-D22`'s parenthesis annotated; T1.4 Step 4a plans the `go-agent/CLAUDE.md` citation fix. |
| 15 | Done. §6.4 classifies `run-checks.sh` by hunk; T1.2's step is numbered 18. |
| 16 | Done. Dated header notes in `go-agent/decisions.md` and `agent-gateway/decisions.md`. |
| 17 | Done as plan text (T1.2): the `AG-D13` supersession row lands with T1.2. |
| 18 | Partly. PostgreSQL 14 `CREATE FUNCTION` ("Writing SECURITY DEFINER Functions Safely") is quoted in the plan, `proto-amendments-015.md` R-22 and the draft; the `-D` override claim is marked unverified in `check-protoc-executables.py` and the licence audit. **The sentence in the T0.4 lane subsection above that protobuf is BSD-3-Clause "by recollection" is withdrawn here, not deleted** (moot by `OR-130` L-c; lane subsections are never edited). |
| 19 | Done. `check-protoc-executables.py` forbids `<protocPlugins>`/`<protocPlugin>` (the xolstice compile-mojo page, fetched 2026-10-09, lists `protocPlugins` as "one of more custom protoc plugins, written in Java and available as Maven artifacts"; that page names no version, the repository pins 0.6.1) with a planted self-test case (11 cases). |
| 20 | Done. `decisions-015-draft.md` is marked registered and not updated; the fix round edited the registers only; `proto-amendments-015.md` and the draft DDL are updated (V028/V029/V021). |
| 21 | Done. The agent changelog row states that `AG-D26`-`AG-D29` link to files that exist only on the backend PR until it merges. |
| 22 | This subsection. |
| 23 | Done. "Measured 2026-10-09 on `2568862f`: no renumbering" in the matrix and the draft header. |
| 24 | Done. §10 has a Ruled column; T0.2 Step 1 says four `licence-*` files plus the script; T1.4 Step 2 puts `~/go/bin` on `PATH`, asserts `protoc-gen-go-grpc` 1.5.1 and records `buf` 1.73.0 as the pinned dev tool; the two `.gitattributes` are narrowed to `00[1-5]-*.md` and `2026-09-*.md`. |

**The measured base** is recorded at the top of Results (T0.1 Step 1).

**The replay (finding 2).** A throwaway `postgres:14` (container `w0fix-pg14`, run through `docker-lock.sh`, removed afterwards) took the repository's
phase-0 scripts, `sm_data` V001-V027, `sm_metrics` V001-V003 and `sm_control` V001-V020 (each in one transaction as `sm_dp_owner`/`sm_cp_owner`),
with a legacy SSH target (NULL `host_key_algorithm`) inserted before `VDA`; then `VDA`, `VDB` and `VCA` of the corrected draft: **all green**.
**21 probes ran, 21 PASS** (the three the review asked for plus 18: shape CHECK both legs, the V012 trigger guard, global uniqueness, no `bytea`,
runtime `EXECUTE` and append-only grants, the V020 readiness markers, the catalog at 47 / Administrator 47 / Operator 26 / Viewer 9, the transport
and operation CHECKs). **The original 94 probes were not re-run: their scripts were scratch files, never committed (the draft's header says so), and no copy exists on this machine.** The 21 are
kept in `docs/design/backend/agent-gateway/evidence/w0-fix-replay/` (`run-base.sh`, three SQL files). Sections of the draft those 21 do not touch
(`agent_command`, leases, discovery, the CP `agent_registration` triggers) applied without error but were not re-probed; T1.1/T1.3 write the ITs.

**Gates run on `i015/backend` after the merge** (a Maven reactor build is **not** required: W0 changes no reactor input, only two scripts, `run-checks.sh`
and docs): `git diff --check 2568862f..HEAD` clean; `check-frozen-migrations.py` PASS (50 migrations byte-identical at 15 anchors);
`check-licences.py` OK (156 build-plugin realm artifacts, 63 exception lines); `check-protoc-executables.py --self-test` OK (11 cases);
`test-licence-gate-present.sh` all scenarios passed; `check-postman.py` OK (73 operations, no drift); `./contracts/run-checks.sh --strict`
(with `SM_FRONTEND_NODE_MODULES` and a temporary `contracts/view-models/node_modules` symlink, removed before the commit) **ALL CHECKS PASSED**;
relative links over the changed markdown: **no new dangling link** (41 before, 41 after, all pre-existing); the §6.4 split check over every non-merge
commit in `2568862f..HEAD`: **9 commits, none mixed**.

### T1.1 — Results (2026-10-09, `x/agw1a`, Sonnet 5.5; review pending)

**Built.** `sm_data` **V028** `agent_gateway.sql` (VDA) and **V029** `agent_gateway_maintenance.sql` (VDB), taken from the reviewed draft
(`schema-015-draft.sql`, W0 fix-round version) as a diff-free copy for V028 and a reduced rewrite for V029. V028: the `target_connection`
widening (transport `agent`, SSH fields nullable, `agent_id`, the shape CHECK with **no** constraint on `host_key_algorithm` for `ssh-docker` and NULL
required for `agent`, `target_connection_agent_once`), V012's `target_pinned_key_recorded()` replaced with a NULL guard, `target_connection_event`
kind `enrolled` + `actor_kind`, `instance_state.acquisition_mode` `agent`, the eight tables (`agent_identity` with `catalog_solution_id`/`solution`/
`declared_package_version`, `agent_session`, `agent_lease`, `agent_command`, the two watermarks, `agent_discovery`, `agent_event`), `apply_step_intent_command_key`
(the referenced key for the composite FK), `agent_command_one_live_per_intent` on `(tenant_id, intent_key, operation)`, the own-target trigger, the sticky-revocation
trigger, per-table narrow grants. No placement DDL. V029: `sm_data.agent_gateway_maintenance()` returning `TABLE (commands_deleted bigint)`, `SECURITY DEFINER`,
`SET search_path = sm_data, pg_temp`, advisory lock, deletes final commands older than 30 days except the highest `wire_generation` of each (agent, placement);
**command retention only**: the draft's `agent_lease` and `agent_session` deletes are held back (§10 item 18) and `agent_event` is never deleted. `CHECKSUMS.sha256` has both
lines; `backend/README.md` says V001-V029; `frozen-migration-anchors.txt` untouched (`check-frozen-migrations.py` PASS, V028/V029 reported as not yet anchored).

**Tests.** New `AgentSchemaIT` (19 tests: the plan's 15 plus the own-target trigger and four VDB tests: retention deletes only superseded final commands and never the
highest generation, never touches lease/session/event, and the function is definer-owned with the pinned search path and runtime-only `EXECUTE`). It reaches the
package-private harness through a new public bridge `AgentItSupport` in `...apply.persistence` (test tree), because the plan's directory is a different Java package.
The legacy-row test builds a fresh database, migrates it to V027 (`target("27")`), inserts an SSH target with NULL `host_key_algorithm`, then runs V028/V029.
**Red** (before the migrations): `AgentSchemaIT` 19 run, 18 failed (6 failures, 12 errors). **Green** after: 19/19.
Pinned counts updated in the same commit: `ApplyPersistenceConfigurationIT` (27 to 29), `SmMetricsMigratorIT` (27 to 29), `SmDataBootstrapIT` (migrations 29, tables 26 to 34, functions +3, triggers +2),
`RefusalShapesIT` (CHECKs 136 to 183 = +47, itemised in the comment; and `apply_step_intent_command_key` in the generated-names list). `PrivilegeTopologyIT` needed **no** change
(the plan listed it; its assertions do not count objects V028 adds). `InventoryReadinessTest`, `SketchMirrorTest` unchanged and green.

**Mutation proofs** (each applied to the migration, `AgentSchemaIT` run, red, reverted; SHA-256 of both files re-verified identical to `CHECKSUMS.sha256` afterwards):
1. drop `target_connection_transport_shape`: 3 red (`anSshTargetRowStillNeedsEverySshField`, `anAgentTargetRowRefusesSshFields`, `anAgentRowWithAHostKeyAlgorithmIsRefused`).
2. VDB drops the "newer generation exists" guard (deletes the highest generation): 1 red (`retentionDeletesOldFinalCommandsButNeverTheHighestGenerationOfAnAgentPlacement`).
3. `host_key_algorithm IS NOT NULL` added to the `ssh-docker` leg: 1 error (`aLegacySshRowWithANullHostKeyAlgorithmSurvivesVda`; the migration itself fails on the legacy row).
4. `payload bytea` added to `agent_command`: 1 red (`noAgentTableCanHoldRenderedBytesOrAPlainDigest`).
5. `config_hash` CHECK relaxed to accept `sha256:`: 1 red (`aPlainHashIsRefusedInAgentCommand`).
6. drop `agent_command_one_live_per_intent`: 1 red (`oneLiveCommandPerIntentKeyAndOperation`).
7. drop `agent_identity_agent_id_global`: 1 red (`agentIdAndThumbprintAreGloballyUnique`).
8. `GRANT INSERT, UPDATE` on `agent_event`: 1 red (`agentEventIsAppendOnlyForTheRuntimeRole`).

**Module verify** (`mvn -o -f backend/pom.xml -pl dp-core -am verify -Dmaven.test.failure.ignore=true`, under the docker lock): unit 1477 run / 0 failed / 1 skipped
(dp-api 160/0); `dp-core` failsafe 1456 run, **2 failed, 12 skipped**: `MetricsExportServiceIT.anotherMetricOfTheSameInstanceIsNotExported` and
`AdmissionSerializationIT.anInstanceWideAdmissionBlocksOnTheScopeRowHeldByAPlacementScopedOneThenRefusesCleanly`. Both are timing-sensitive (the second asserts what
a parked statement is blocked on) and **both passed when re-run alone on the same tree** (12/12 and 9/9); neither touches a table or function V028/V029 adds. Treated as load flakes while other
lanes shared the machine, not caused by this change.

**Deviations from the plan text.** (1) The plan's Step 2/4 command `mvn -pl dp-core verify` without `-am` compiles against a stale `dp-api` in the local repository and fails; every run here used `-am`. (2) VDB's
return type is `TABLE (commands_deleted bigint)` (the draft's three-column table existed for the held-back deletes); widening is a new migration, never an edit (`OR-22`). (3) `PrivilegeTopologyIT` unchanged, as above.
(4) The sketch's `config_hash IS NULL OR` form is not needed: a NULL passes a CHECK, and the draft's `agent_command_hash_on_config` already ties NULL to the operation.

#### T1.1 fix round (2026-10-09, `x/agw1a`, Sonnet 5.5; answers the Opus review of `ef3c45fd`)

V028 and V029 were not published or anchored, so both were edited in place; `CHECKSUMS.sha256` is updated; V028 is again byte-identical to draft VDA (`schema-015-draft.sql`, diffed), and V029 is VDB minus the lease and session deletes. VDB still ships **command retention only** (§10 item 18 pending).

**Per finding.** (1) `target_connection_agent_binding UNIQUE (tenant_id, target_id, agent_id)` and `agent_identity_has_target` now references all three columns: an identity naming an SSH target or another agent's target is refused 23503. (2) The retention test pins each predicate with a row only it keeps and asserts exactly 2 deleted. (3) Every `target_connection` shape leg, the iff's other direction and the event fingerprint rule have their own refusal. (4) Tests for sticky revocation, every `agent_discovery` CHECK, the runtime role's exact UPDATE column set and no DELETE/TRUNCATE on any `agent_%` table, and a surviving tombstone in the retention test. (5) Two triggers: `agent_session_epoch` (fencing epoch only rises) and `agent_discovery_terminal` (a terminal state is final); `SmDataBootstrapIT` functions 29 to 31 and triggers 30 to 32 (names listed). (6) `agent_lease_verb_ceilings` CHECK (object, at most 4096 bytes), so CHECKs 183 to **184**; header reworded to name the nonce digest and the two thumbprints; the test pins the exact column set of all eight tables and an allow-list of hash-like columns. (7) LOCK CLASS and ROLLBACK paragraphs in both headers; the legacy test now pre-populates an event with a fingerprint, a via-ssh instance, a placement, a request and an intent and asserts `actor_kind = 'principal'`. (8) V029 cites AGW-D31. (9) the hash regex is `v[1-9][0-9]{0,8}`. (10) the envelope and own-target refusals assert their column. (11) Counts below; `backend/README.md` says migrated 29; the transport CHECK comment says defence in depth; the retention fixture is rewritten (no `oldest`/`older`). (12) the reader-breakage note is in T3.5's text, and `AgentSchemaIT` now runs in a database of its own (`AgentItSupport.freshDatabase`, which needed `declareInstance` to stop declaring in the shared database).

**Counts.** `AgentSchemaIT` 19 to **28** tests. Step 4's DDL replay is **not applicable** to this round (finding 11): the draft's replay baseline was run in the W0 fix round, and this round changed the migrations, not the replay harness; the ITs against a live PostgreSQL 14 are the evidence. (The earlier "15 plus own-target plus four VDB = 20" was 19; the VDB tests are three.)

**TDD.** Tests were written first, then run against the unchanged DDL. The first run was 16 errors from my own harness bug (the owner `JdbcTemplate` and the `TransactionTemplate` were built on two different data sources), fixed before counting; the second run, DDL unchanged: 28 run, **6 failed** (identity-to-own-target, config-hash spelling, fencing epoch, terminal discovery state, `verb_ceilings`, and one discovery test whose probe row violated an earlier-named CHECK, fixed to a probe that violates only the named one). `SmDataBootstrapIT` and `RefusalShapesIT` were red against the old V028 (1 failure each: the function list, 183 against 184). After the DDL: `AgentSchemaIT` 28/28, `SmDataBootstrapIT` 11/11, `RefusalShapesIT` 7/7. Tests that were green before the DDL because they pin behaviour that already existed (retention predicates, sticky revocation, discovery CHECKs, shape legs, grants, column set) get their red from the mutation proofs, not from a pre-fix run.

**Mutation proofs** (each applied, `AgentSchemaIT` run, red, reverted; both file hashes re-verified equal to `CHECKSUMS.sha256`): 9 drop the state clause: 1 red (retention); 10 drop the age clause: 1 red (retention); 11 drop placement equality: 1 red (retention); 12 identity FK back to (tenant, target): 1 red (`anAgentIdentityMustNameItsOwnAgentTarget`); 13 drop `agent_session_epoch`: 1 red; 14 drop `agent_discovery_terminal`: 1 red; 15 drop the `credential_ref IS NOT NULL` ssh leg: 1 red; 16 drop `verb_ceilings` size bound: 1 red; 17 drop the sticky-revocation trigger: 1 red; 18 hash regex back to `v[0-9]+`: 1 red; 19 `GRANT UPDATE (target_id)` on `agent_identity`: 1 red; 20 drop `agent_discovery_settled`: 1 red; 21 `GRANT DELETE` on `agent_lease`: 1 red.

**Deviations.** The plan's own-target mutation 12 proves the SSH-target leg; the other-agent's-target leg is asserted but has no separate mutation. A session restart interrupted the first full-verify run; it was re-run (below).

**Module verify** (`mvn -o -f backend/pom.xml -pl dp-core -am verify -Dmaven.test.failure.ignore=true`, under the docker lock, after the session restart killed the first attempt): `dp-api` 160 run / 0 failed; `dp-core` unit 1477 run / 0 failed / 1 skipped; failsafe 1465 run / **0 failed** / 12 skipped (the two timing-sensitive ITs that failed under load in the first round passed); BUILD SUCCESS. `check-frozen-migrations.py` PASS (V028/V029 reported as not yet anchored); `git diff --check` clean. Not re-run live after the last two edits (the `atexit` line in the scenario and the relative state-path wording in `sm-local.sh`'s printed messages): syntax-checked and `test-sm-local.sh` ok only.

**OR-134 follow-up (2026-10-09, branch `feat/agent-gateway/w1-sm-data-migrations`).** The owner ruling `OR-134` (F-5 scope) supersedes "command retention only": V029 now also deletes `agent_lease` rows that are not `ACTIVE` and expired more than 30 days ago, returns `(commands_deleted bigint, leases_deleted bigint)`, and still never deletes `agent_session` or `agent_event` rows; draft VDB drops its session delete and V029 equals it again. Tests, mutation proofs and counts are in the commit. Red first: `AgentSchemaIT` 29 run (28 plus the new lease test; the retention tests reworked), 2 errors against the old function (no `leases_deleted`); green after: `AgentSchemaIT` 29, `SmDataBootstrapIT` 11, `RefusalShapesIT` 7, `ApplyPersistenceConfigurationIT` 5, `SmMetricsMigratorIT` 4 (56, 0 failed). Mutations: drop `state <> 'ACTIVE'` red (2 tests: the ACTIVE lease went); drop the 30-day clause red (1 test: the 29-day lease went); both reverted and the file hash re-verified. Pinned counts needed no change (a function's signature is not counted).

### T1.3 — Results (2026-10-09, `x/agw1c`, Sonnet 5.5; review pending)

`sm_control` `VCA` = **V021** `V021__agent_registration_and_verbs.sql` and the catalog at **47** (Administrator 47 / Operator 26 / Viewer 9).

- **DDL.** The reviewed draft's `VCA` section taken verbatim (lines 442-910), then diffed against `V020`'s function body: exactly the seven `AGW-EDIT`s differ, so `V020`'s gate
  (`HIERARCHY_NODE_RENAMED` in the no-actor list, the `organization`-only / `operator-cli` second gate, the marker comment) is intact. `AGENT_LEASE_ISSUED` is **not** added (the draft makes it conditional on T2.2 Step 0).
  No deviation from the draft. Per-file checksum line, the `sm_control/README.md` section, `backend/README.md` / root `README.md` / `ENDPOINT-INVENTORY.md` version text and `SmControlPostgres.LATEST_VERSION` = `021` moved in the same commit.
  `frozen-migration-anchors.txt` untouched (V021 is unanchored until publication).
- **Catalog.** `Permission.java` (+`AGENT_ENROL`/`AGENT_REVOKE` HIGH, `AGENT_DRAIN`/`AGENT_VIEW` STANDARD; "read-only" is `STANDARD`, as every other view verb), `UnknownPermissionException`/`LocalOperation` text,
  `D10-G2-APPROVAL` **§1.10.1** (the counted rows), `validate-openapi.py` (`CATALOG_SIZE` 47, self-test removes row 47), `PermissionCatalogMirrorTest` (47 plus an agent-verb test; the 39-43 test uses `containsSequence`),
  `BuiltInRoleSeedIT` (47 / 26 / 9, the seed-time assertions exclude both V016's and V021's verbs, a V021 grant test), `IdentityServiceIT` (Viewer's described permissions gain `agent.view`). `AuditOperations` gains the four `AGENT_*` constants.
- **Readiness.** `ControlPlanePersistenceConfiguration`: a ninth stage `verifyV021AgentRegistration` (body markers `sm.audit.actor_agent` and the four operations; `agent_registration`'s enabled audit trigger; `target_transport` admitting `agent`; the 4 / 2 / 1 role grants).
  The `V020` stage and `V020_AUDIT_FUNCTION_QUERY` are unchanged and still require every V020 marker; the new `ControlPlaneConfigurationIT` tests prove a V020 schema is refused for V021, and that V021's body without V020's marker is refused by the V020 stage.
- **Tests.** New `AgentRegistrationSchemaIT` (18): the operator-cli no-actor `HIERARCHY_NODE_RENAMED` on `organization` still accepted after V021 (and refused on `organization_unit`/`environment` and from an HTTP address); `23502` on missing organization/environment/envelope columns;
  the envelope grammars; package-must-be-loaded; global uniqueness; sticky revocation; column-scoped grants and no `DELETE`; principal issue/revoke audited with the digest not copied; the agent as actor for `AGENT_ENROLLED` (target + consumption) and `AGENT_INSTANCE_REGISTERED`;
  every agent operation refused with neither / both actors, no address, no correlation id; no other operation accepts an agent actor; table, organization, active and consumed scoping; transport; vocabulary; one-actor-kind CHECK.
  Findings from writing them: the draft's package trigger runs before the NOT NULL check, so omitting `catalog_solution_id`/`package_version` is refused `23503` rather than `23502` (still refused; asserted as either).
- **Gates.** Targeted: `PermissionCatalogMirrorTest` 10, `MigrationChecksumsTest` 2, `AgentRegistrationSchemaIT` 18, `BuiltInRoleSeedIT` 21, `ControlPlaneConfigurationIT` 22, `IdentityServiceIT` 3, `OperatorRenameAuditSchemaIT` 3: green.
  `./contracts/run-checks.sh --strict` (check 11: 47 verbs, 48th-row self-test PASS): all passed. `mvn -o -pl cp-core -am verify` (full): surefire 50 / 160 / 578 green; failsafe 486 with one failure,
  `IdentityServiceIT.aScopedPrincipalShowsOnlyItsOwnBindingsInPermissionOrdinalOrder` (the Viewer now holds `agent.view`), fixed and re-run green. No launcher or console-gateway test pins the catalog count.
  Step 2's red run was `V021` absent (errors, not assertion failures); `PermissionCatalogMirrorTest` was already green because the enum and §1.10.1 were written first.
- **Mutation proofs (each red, then reverted; the file is byte-identical to the committed one).** (1) `ELSIF v_actor_agent IS NOT NULL` → `ELSIF false` (a non-agent operation accepts an agent actor): `AgentRegistrationSchemaIT` 2 failures.
  (2) Viewer's `agent.view` grant dropped: `BuiltInRoleSeedIT` 2 failures. (3) `allowed_config_root` NOT NULL dropped: 1 failure. (4) `HIERARCHY_NODE_RENAMED` removed from the no-actor list (V020's gate lost): the still-accepted test errors `OR025`.
- **Not done / for review.** The frontend `catalog.ts` is not edited (drifts further, §1.10.1 consequence 3). `AGENT_LEASE_ISSUED` waits on T2.2 Step 0. `frozen-migration-anchors.txt` is for publication.

#### T1.3 fix round (2026-10-09)

Opus review of `c6cb9fc5`: changes requested (two MAJORs, five MINORs, three NITs; all ten fixed). `V021` is unpublished, so it was edited in place; its `CHECKSUMS.sha256` line moved, and `V021` again equals `schema-015-draft.sql`'s `VCA` section byte for byte (the draft's lines 442 onward are replaced by the file). **Deviations from the reviewed draft** (all in `VCA`):

- **Finding 1 (MAJOR), the agent actor is scoped to its own acts.** Added after the existing registration lookup in `record_change_audit()`. `AGENT_ENROLLED`: only `UPDATE` of `agent_registration` where OLD and NEW `agent_id` are the actor and OLD `enrollment_used_at IS NULL` (the single consumption), or `INSERT` of a `target` with transport `agent` while the actor's registration has `target_id IS NULL`. `AGENT_INSTANCE_REGISTERED`: `INSERT` only, with `organization_unit_id` and `environment_id` equal to the actor's registration and, on `solution`, `catalog_solution_id` equal to it. Message: `the agent actor may write only ...` (also a readiness marker). **One gate beyond the reviewer's:** the agent's `target` INSERT must carry `name = registration.target_name` (T3.5: "`name` = the token's `target_name`"); with the live-name index this makes a second target before consumption a `23505`, which the reviewer's `target_id IS NULL` alone did not stop. Later tasks were read for what they need: T2.2/T3.5 insert the target first and then consume (the `agent_registration_target_fk` forces that order), replays read only; T4.1/T4.2 insert `solution`, `instance` and `scope` rows into the enrolled environment and catalog solution and never update or retire. Nothing a later task needs is blocked; no reviewer gate was loosened.
- **Finding 2 (MAJOR), `package_version` is one member.** `agent_registration_package_is_loaded()` refuses a `package_version` that is not a JSON number or string (`check_violation`, `23514`, column `package_version`) before the containment test. The `IT` fixture is now `'1'` (T2.2/T4.1 copy that shape); `'[]'`, `'[1]'`, `'[1, 1]'`, `'{}'`, `'null'` and `'true'` are refused.
- **Finding 3.** The same trigger returns early when `catalog_solution_id` or `package_version` is NULL, so `NOT NULL` answers: `23502` naming the right column, asserted exactly.
- **Finding 10 (NIT).** `CHECK target_agent_is_docker (transport <> 'agent' OR kind = 'docker')`. T3.5 still needs `TargetTransport.fromWire` (console-gateway) widened from `ssh-docker` only to `agent`; that is T3.5's, not done here.
- **Findings 4, 5 (readiness and grants).** `verifyV021AgentRegistration` also requires the `change_audit.actor_agent_id` column, the widened `change_audit_audit_operation` CHECK (all four operations, `pg_get_constraintdef`), the enabled `truncate_refused` trigger, the runtime role's SELECT and INSERT, no DELETE and `UPDATE` on exactly the eleven granted columns, and a marker from a `RAISE` text (`an agent actor is only permitted for`, `the agent actor may write only`) instead of only bare strings. `AgentRegistrationSchemaIT` asserts the exact updatable column set; `ControlPlaneConfigurationIT`'s last step is renamed "without V020's marker comment".
- **Findings 6-9 (records).** Forward pointers only, original text unedited (`I3`): `DECISIONS.md` open item 39 and `D10` approval §5 item 3 row (h) ("answered 2026-10-08 by `OR-126` A8 / `OR-128` (4)", §1.10); `Permission.java`, `PermissionCatalogMirrorTest` and `validate-openapi.py` now list (c), (e) and (i) as outstanding. `backend/README.md` states 47 (47 / 26 / 9) with a `V021` pointer. `validate-openapi.py` docstring "48th row" and the removed-row regex is built from `CATALOG_SIZE`. `D10` approval §1.10.1 now says row 44's Meaning is lightly reworded rather than "unedited".

**Test-first evidence.** `AgentRegistrationSchemaIT` 18 -> 27 (+9 new, 2 amended): red before the fix (9 failures: the eight new/changed scope, package and kind tests plus the `23503`-for-`23502` assertion), green after. `ControlPlaneConfigurationIT` 22 -> 23: the new structure test is red until the readiness stage and DDL land (it fails at "fully migrated starts"; the older V021 tests fail the same way with the new marker absent), green after. The exact-column-set test (finding 5) was already true of the DDL and is green from the start; it is a strengthening, not a fix. **Mutation proofs** (each red, then restored byte-identical): the `AGENT_ENROLLED` gate disabled -> four failures (mint, another's registration, own after consumption, retire target / non-agent / second target); the `AGENT_INSTANCE_REGISTERED` gate disabled -> two failures (retire instance, other environment / catalog solution); the `jsonb_typeof` check disabled -> one failure (`'[]'`, `'[1]'`, ...).

### T1.2 — Results (2026-10-09, `x/agw1b` = `feat/agent-gateway/w1-gateway-module`, Sonnet 5.5; review pending)

**Commits.** `cf42529e` is the **agent-PR** commit (the proto move: `contracts/agent/v1/{agent_gateway.proto,buf.yaml,baseline.json,check_proto_append_only.py}`,
the `run-checks.sh` step-18 hunk, the `AG-D13` supersession row in `docs/design/go-agent/decisions.md`); it touches agent-PR paths only and **reaches the
backend through `integration/go-agent`** (the backend build's `protoSourceRoot` reads `contracts/agent/v1`). The commit holding this subsection is the
**backend** commit (module, poms, boundary rules, licence lines, notices). Split check: neither commit is mixed.

**Order of work, honestly.** Steps 1–2 (red first) were **not observed in order**: the offline repository lacked `protobuf-maven-plugin` 0.6.1 and
`os-maven-plugin` 1.7.1, so nothing built until the integrator primed `~/.m2` once online (owner-approved). The red evidence is therefore the Step 5
mutation runs below, taken after the rules existed.

**What was done.** `backend/agent-gateway/**` TAKEN at `87e338e1` (9 classes, 4 tests, README) with a hand-written `pom.xml`: no `javax.annotation-api`,
`protoSourceRoot` = `../../contracts/agent/v1`, `protoc 3.25.5` / `protoc-gen-grpc-java 1.68.1` spelled literally, `spring-boot-actuator` kept,
`<pluginParameter>@generated=omit</pluginParameter>`; the 37 `dp-api/.../agw` types TAKEN unchanged; reactor root gains the module, its management entry and
`grpc.version`/`protobuf.version`; **no launcher import**, `jdbcIsConfinedToPersistencePackages` and `RUNTIME_ADAPTER_IMPLEMENTATIONS` untouched.
`AgentGatewayBoundaryTest` (four rules, five proofs: `dpNeverImportsAgentGateway`, `agentGatewayUsesDpApiOnly` incl. `cp..`,
`agentGatewayHoldsNoDatasource`, `onlyAgentGatewayTouchesGrpc` widened to every module but `agw`) and `AgentGatewayDependencyAllowListTest` (four tests), with
planted fixtures under `archfixture` packages. L-b classes in the build are as ruled.

**Step 3a — L1 proved.** (i) `pluginParameter` exists on `compile-custom` since 0.4.1 (xolstice compile-custom-mojo page, fetched 2026-10-09), so 0.6.1 forwards it.
(ii) `grep -rL "javax.annotation" agent-gateway/target/generated-sources` lists **both** generated files (`AgentGatewayGrpc.java`,
`AgentGatewayOuterClass.java`); the only `Generated` annotation in `AgentGatewayGrpc.java` is `@io.grpc.stub.annotations.GrpcGenerated`.
(iii) `mvn -o dependency:tree -pl agent-gateway` shows **no** `javax.annotation-api` (`jakarta.annotation-api 2.1.1` arrives via the Spring Boot starter).

**Step 4.** `mvn -o -f backend/pom.xml -pl agent-gateway,arch-tests -am verify` (docker lock, `tc-env.sh`): **BUILD SUCCESS**, all ten reactor modules, ITs included.
`agent-gateway` 32 unit tests (AdmissionLimiter 5, ManagementServer 4, Operational 4, Service 19); `arch-tests` **128** (`AgentGatewayBoundaryTest` 5,
`AgentGatewayDependencyAllowListTest` 4); upstream suites green (cp-core 465 failsafe, dp-core, launcher, etc.). An earlier attempt hit one unrelated
Postgres SSL setup error in `cp-core`'s `EntitlementMainIT` (1 of 465); the full re-run above passed it. The licence gate (both executions) passed.

**Licences.** `OR-130` L-a/L-b: the five exact lines in `backend/licence-exceptions.txt` under the `# OR-130 = owner ruling 2026-10-08 (L-a, L-b)` comment.
**The first gated build found two coordinates the audit never named**, both shipped via `micrometer-core`: `org.latencyutils:LatencyUtils:2.0.3` (CC0) and
`org.hdrhistogram:HdrHistogram:2.2.2` (BSD-2-Clause + CC0). Ruled **`OR-134` (L-d)**: both approved as exact-coordinate exceptions, HdrHistogram under
BSD-2-Clause (election recorded in the line's comment, quoting the jar's `META-INF/LICENSE.txt`), its copyright notice and BSD text in
`backend/THIRD-PARTY-NOTICES.md`. `gson 2.13.2`, `checker-qual 3.42.0` and `listenablefuture 9999.0-empty-…` **passed the gate without lines**, so no
"pending owner confirmation" commit was made (§10 item 20 stays open as a record question only). L3's BoringSSL notice (verbatim from the jar's
`LICENSE.boringssl.txt`) is in `THIRD-PARTY-NOTICES.md`; no exception line was added for `grpc-netty-shaded` (the gate passes it). Scripts: `check-licences.py` OK
(192 plugin-realm artifacts, 70 exception lines); `test-check-licences.sh` all scenarios passed; `test-licence-gate-present.sh` all scenarios passed (14 PASS lines);
`check-protoc-executables.py --self-test` OK; `./contracts/run-checks.sh` (non-strict) PASSED with 4 checks not run (steps 7, 8, 9, 14 need node modules).

**Step 5 — mutation proofs** (each run then reverted; worktree diff clean afterwards):

| Mutation | Result |
|---|---|
| `dpNeverImportsAgentGateway` predicate package `agw..` → `agwX..` | red: `dpNeverImportsAgentGatewayCatchesAPlantedImport` |
| `agentGatewayUsesDpApiOnly` exemption widened to all `dp..` | red: `dpApiOnlyRuleCatchesAPlantedDpCoreImport` |
| `agentGatewayHoldsNoDatasource` drops `java.sql..`/`javax.sql..` | red: `noDatasourceRuleCatchesAPlantedDataSource` |
| `onlyAgentGatewayTouchesGrpc` package `io.grpc..` → `io.grpcX..` | red: `onlyAgentGatewayTouchesGrpcCatchesAPlantedLauncherImport` |
| `spring-jdbc` added to `agent-gateway/pom.xml` | red: allow-list test names `spring-jdbc` |
| `javax.annotation-api 1.3.2` added to the pom | red: allow-list test names it (OR-127 L1) |
| renumber `message_id = 1` → `7` in the moved proto | `check_proto_append_only.py` exit 1 ("entry 1 … removed or renumbered") |
| delete the licence plugin block (scratch copies) | `test-licence-gate-present.sh`'s own planted-deletion scenarios fail as designed |

**Not done / not provable here.** The plan's mutation "add `import com.solace.sm.cp.api…` to `AgentGatewayService`" cannot reach the rule: `agent-gateway` has no
`cp-api` dependency, so the module **fails to compile** first (`package com.solace.sm.cp.api does not exist`) — the classpath is a second guard; the
`PlantedAgwImportsCp` fixture proves the rule itself. No launcher composition (T3.2) and no behaviour change (T2.1a/T3.2) were made. `buf generate`
from `go-agent` against the new location is T1.4 Step 2. §10 item 20 still lists the three coordinates as awaiting confirmation; the gate did not require it.

### T1.2 fix round (2026-10-09; review `w1b-review.md`; Sonnet 5.5; findings 1–13, 15, 16 fixed here, 14 is the integrator's)

See the commit of this subsection. Red before green is recorded per finding below; the earlier T1.2 text above is not rewritten. Corrections to it: Step 4 listed
`./contracts/run-checks.sh --strict` but the first pass ran it non-strict (re-run strict below); the "delete the licence plugin block" row of the mutation table is
`test-licence-gate-present.sh`'s **own** planted-deletion scenarios, not a mutation the implementer ran; and §10 item 20 was **not** closed by the gate passing.

| # | Finding | Disposition |
|---|---|---|
| 1 | wire classes invisible to the rules | `dpNeverImportsAgentGateway` now targets `com.solace.sm.agw..` and `solace.sm.agentgateway..`; `onlyAgentGatewayTouchesGrpc` targets `io.grpc..`, `com.google.protobuf..` and `solace.sm.agentgateway..` (subjects exclude the wire and agw packages); `@AnalyzeClasses` and the proof importer now include `solace.sm.agentgateway`. **Red then green:** `PlantedDpImportsWireMessage`, `PlantedLauncherTouchesProtobuf`. `theGeneratedWireClassesAreInTheAnalysedSet` asserts the generated class is imported. No `java_package` added. |
| 2 | I7 not enforced cp → agw | new `cpNeverImportsAgentGateway` (+ `PlantedCpImportsAgw`); `gatewayUsesApisOnly` forbids `agw..` and the wire package (+ `PlantedGatewayImportsAgw`, proof in `PlaneBoundaryTest`). Red then green. |
| 3 | `check-protoc-executables.py` fails open | override set extended to `pluginExecutable`, `protocPluginExecutable`, `pluginTool`, `protocPluginTool`, `pluginToolchain`, `protocPluginToolchain` (plus the existing two), refused as elements and `<properties>`; `-Dprotoc*` refused in `.mvn/maven.config` and `jvm.config`; the unmeasured sentence replaced by the measurement. Self-test **red 6/6 on the old set, green 0 fails after**. T5.2 note added. |
| 4 | `grpc-netty-shaded` not pinned | exact line `io.grpc--grpc-netty-shaded--1.68.1=Owner-approved (shipped)` with the L3 comment; a different resolved version aborts the build (planted: line at `1.68.0` → red, below). Notices reworded. |
| 5 | §10 item 20 downgraded | record corrected: five coordinates, first recorded OPEN; licence audit §D item 3 likewise; **resolved by `OR-135`** (below). **No exception lines added.** |
| 6 | I6/I5 in `dp-api/agw` | plan text added to T2.1a, T3.3, T3.4, T4.1. |
| 7 | taken-code defects | plan text added to T2.2, T3.1, T3.2, T3.3. |
| 8 | unclosed BoringSSL fence | closing fence on its own line. |
| 9 | notice coverage | `LICENSE.aix-netbsd.txt` reproduced verbatim; OpenSSL clause 3 obligation recorded, T5.2 note. |
| 10 | rule and allow-list scope | `agentGatewayUsesDpApiOnly` forbids every `com.solace.sm..` module except `agw..` and `dp.api..` (+ `PlantedAgwImportsEvents`, red then green); datasource rule gains `org.springframework.orm`, `org.hibernate`, `javax.persistence`, `io.r2dbc`, `org.jooq` (not on arch-tests' classpath, so asserted by rule text, not a compiled fixture); allow-list test is case-insensitive with hibernate/jooq/mybatis/r2dbc/liquibase, reads direct children only, and asserts `backend/pom.xml` has no top-level `<dependencies>` (planted cases red then green). |
| 11 | results honesty | this table; strict run below. |
| 12 | taken README false | header note and line 4 corrected. |
| 13 | append-only check gaps | plan text added to T2.3. |
| 14 | integration conflict | integrator. |
| 15 | pom | `protobuf-java` managed at `${protobuf.version}` in the reactor root. |
| 16 | authority references | `OR-127`, `OR-130`, `OR-134` added to the `licence-exceptions.txt` AUTHORITY header. |

**§10 item 20 resolved by `OR-135` (owner, 2026-10-09).** Its status in §10 and in licence-audit §D item 3 now reads "ruled by `OR-135`"; the confirmation is a record, not a gate line.

**Verification.** `mvn -o -f backend/pom.xml -pl agent-gateway,arch-tests -am verify` (docker lock `agw1b-fix`, `tc-env.sh`): **BUILD SUCCESS**, all ten modules, ITs included.
Per-module test counts: cp-api 49; dp-api 160; mesh-events 578 (the three leading totals); cp-core 465 failsafe after 1477 surefire (1 skipped); dp-core 1437 (12 skipped);
agent-gateway 32; console-gateway 982; launcher 137 surefire + 436 failsafe (44 skipped); **arch-tests 139** (was 128: `AgentGatewayBoundaryTest` 11 = 4 `@ArchTest` rules
run as 5 + the 6 proof/coverage tests, `AgentGatewayDependencyAllowListTest` 7). Gates: `check-licences.py` OK (192 plugin-realm artifacts, 71 exception lines);
`test-check-licences.sh`, `test-licence-gate-present.sh` all scenarios passed; `check-protoc-executables.py` OK and `--self-test` OK (new cases included);
`./contracts/run-checks.sh --strict` **ALL CHECKS PASSED** (temporary `contracts/view-models/node_modules` symlink and `SM_FRONTEND_NODE_MODULES`, symlink removed); `git diff --check` clean. Not re-run live after the last two edits (the `atexit` line in the scenario and the relative state-path wording in `sm-local.sh`'s printed messages): syntax-checked and `test-sm-local.sh` ok only.
Red evidence: arch run `/tmp/redA.log` (9 failures and 1 error before the fixes: wire message, cp→agw, usesDpApiOnly scope, protobuf, datasource text, gateway→agw, allow-list case/exclusions/parent);
`check-protoc-executables.py --self-test` 6 FAIL on the old override set; RULE 2 planted line `1.68.0` aborts the build.

### T1.4 — Results (2026-10-09, `feat/agent-gateway/w1-go-agent-intake`, Sonnet 5.5; reviewed by Opus)

- **Taken:** `go-agent/**` from `origin/dp-agent-integration@87e338e1` (6dd863e9), on top of lane B's proto-move commit 88707499. `go-agent/buf.yaml` is deleted and `oldGo-Agent/` is carried.
- **Regenerated** with buf 1.73.0, protoc-gen-go v1.36.12 and protoc-gen-go-grpc v1.5.1. `*_grpc.pb.go` is byte-identical. `agent_gateway.pb.go` differs only in its protoc header line (v4.25.5 → (unknown)).
- **Build:** Go 1.27.1 locally; go.mod still says 1.26.4. The cold module cache was filled online from the existing go.sum; go.sum is unchanged.
- **Gate:**
  - go vet is clean.
  - 24 packages pass: 228 PASS, 0 FAIL.
  - Integration: 33 PASS, 1 SKIP (AG-5).
  - Docker E2E: 2/2 after the test-only fix 927e0f03.
- **Licences (b8acce8d):** THIRD-PARTY-LICENCES.md (32 modules) and scripts/check-licences.sh, with planted cases.
- **Step 4a (eec49b83):** CLAUDE.md citations fixed.

**Fix round (Opus review: 3 MAJOR plus minors):**
- 60a1df73: go-agent/proto removed; the Makefile now reads contracts/agent/v1; README and CLAUDE.md corrected; step 18 refuses a stray .proto.
- d4c05d19: the append-only checker now records oneof membership, field options, syntax and file options. All 17 plants are reported, and buf breaking agrees on all of them.
- 004a6189: check-licences.sh runs with GOWORK=off and -mod=readonly, and fails on a replace or a versionless module.
- 9434de6f (S1): the enrolment token no longer appears in the flag usage text. The test was red first.

### W1 integration (2026-10-09)

Integrated on `integration/agent-gateway` (`OR-134`, Branches), in the plan's W1 order (A, C, B, D; HF14 B before D).

**Merge order.**
1. The `UAT` merge, `29afdca8` (two docs commits, the combined plan only).
2. Lane A: `715df8be`, `7cc65135`, `af3f6778`.
3. Lane C: `35fc681a`, `5dbe2026`.
4. Lane B: `4761eb8f`, `d7f5bd39`.
5. The merge of `integration/go-agent` at `389c9fbb`, which carries lane D and the proto move `88707499`. Lane B's `cf42529e` (also a proto move) was not carried directly; its content arrives through `integration/go-agent`.

**Conflict resolutions.**
- `backend/README.md`: both version bumps kept.
- Plan Results: all subsections kept.
- §10 rows 18 to 20: the rulings kept (`OR-134`, `OR-134`, `OR-135`).
- `contracts/run-checks.sh`: steps 16, 17 and 18 in that order.

**Rulings applied.** `OR-134` (branch names; A9 completed; the lease and clock rulings of §10 items 18 and 19) and `OR-135` (§10 item 20). Plan text corrected in this commit: branch names (§6.4 note), A9 in §1, §6.3 and T5.3, the §4.1 go-agent row, T1.4 Step 2, the §6.2 Go gate, T2.3 security notes S1 to S4 and the T2.4 NOTICE line.

**Full gate (2026-10-10, on `81493e5c`):**
- `mvn -o -f backend/pom.xml clean verify` on `postgres:14` under the docker lock: **BUILD SUCCESS, zero failing classes**.
  - cp-api 50; dp-api 160; mesh-events 578.
  - cp-core failsafe 496.
  - dp-core surefire 1477 (1 skipped); failsafe 1466 (12 skipped).
  - agent-gateway 32; console-gateway 982.
  - launcher surefire 137; failsafe 436 (44 skipped).
  - arch-tests 139.
- `./contracts/run-checks.sh --strict`: ALL CHECKS PASSED (steps 16, 17 and 18 present, in that order).
- Licence and pin checks:
  - `check-licences.py`: OK (192 build-plugin realm artifacts, 71 exception lines).
  - `test-check-licences.sh` and `test-licence-gate-present.sh`: all scenarios passed.
  - `check-protoc-executables.py` and `--self-test`: OK.
- Other checks:
  - `check-postman.py`: OK (73 operations, no drift).
  - `check-frozen-migrations.py`: PASS. `sm_control` V021 and `sm_data` V028/V029 are not yet anchored; the push anchors them.
- Go gate on `integration/go-agent`: `go vet`, `go test ./...` (no failures), integration suite and `check-licences.sh` all green.
- §6.4 split check: 32 non-merge commits, none mixed.
- `git diff --check 2568862f..HEAD`: three findings, each fixed.
  - The verbatim OpenSSL/BoringSSL notice text keeps its trailing spaces, so `backend/.gitattributes` now exempts `THIRD-PARTY-NOTICES.md` (`f342cbb3`).
  - The retired `go-agent/oldGo-Agent/` archive, taken as-is, gets its own `.gitattributes` (`ab3e8da1`).
  - V028 ended with a blank line. It was removed before publication and the checksum updated. `DataPlaneMigrationChecksumsTest`, `AgentSchemaIT` and `SmDataBootstrapIT` were re-run on the fixed file: 4/4, 29/29, 11/11, green.

### T2.1a — Results (2026-10-10, `feat/agent-gateway/w2-port-signatures`, Sonnet 5.5; reviewed by Opus; fix round below)

Every `dp-api/agw` signature is declared once and `agent-gateway` compiles against them. Backend-PR paths only.

- **Types (§4.2.1).** *Dropped (5):* `AgentGatewayPorts`, `AgentActualStateQueryPort`, `AgentActualState`, `CanonicalTelemetrySample`, `TelemetryQueryPort`. *Renamed:* `TelemetryIngestPort`/`TelemetryBatch`/`TelemetryResult` → `AgentTelemetryPort`/`AgentTelemetryBatch`/`AgentTelemetryResult`. *Adapted:* `AgentSessionPort` (`claim(thumbprint, origin)`, `close(ref, CloseReason)` replacing `release`, `liveFor`), `AgentSessionRef` (`UUID agentId`, no tenant), `AgentCommandPort`, `CommandNotice`, `CommandPayload` (now the in-memory bytes holder), `CommandCompletion`, `OperationCorrelation` (+`intentKey`), `AgentOperation` (+`REMATERIALIZE`), `EnrollmentRequest` (+`AgentRequestOrigin`), `EnrollmentResult` (+environment, target, `EnrollmentEnvelope`, issuing principal; `bindingIds` removed), `AgentIdentity` (+tenant, environment, target; `bindingIds` removed), `LeaseGrant` (`placementIds`), `LeaseIssuer` (+DP-supplied `placementIds`), `AgentRegistrationSource`/`State` (UUID ids, no `binding_ids`), `ReportItem` (+`health`, `containerStartedAt`), `GatewayCode` (+dotted codes with `wire()`). *Kept:* `AgentAuditPort`, `AgentCapability`, `GatewayPortException`, `AgentEnrollmentPort`, `EnrollmentCertificateIssuer`, `AgentIdentityPort`, `IdentityReconciliation`, `AgentLeasePort`, `ActualStatePort`, `ReportResult`, `CommandAckResult`, `AgentOutcome`. *Added:* `AgentRequestOrigin`, `AgentObservedStatePort`, `AgentObservation`, `AgentDiscoveryPort`, `DiscoveredContainer`, `DiscoveryResult`, `AgentTelemetryDeclarationsPort`, `TelemetryDeclaration`, plus the small value records `AgentCommandIntent`, `AgentCommandRef`, `AgentAck`, `CommandOffer`, `CommandId`, `CloseReason`, `AgentPlacementRef`, `AgentHealthObservation`, `EnrollmentEnvelope`.
- **Deviations from the task's interface block (all for a stated reason; HF18 consumers read these).** (1) `PlacementRef`/`TenantId` are `dp-core` types that `dp-api` cannot import and would collide by simple name: replaced by `AgentPlacementRef(UUID tenantId, UUID instanceId, UUID placementId)` mirroring `dp-core`'s `PlacementRef`; consequently `AgentObservedStatePort.latest(AgentPlacementRef)` has no separate tenant parameter. (2) `pendingFor` also has the three-argument form with `renotifyAfter` and `AgentCommandPort` keeps the set-based `agentsWithDeliverableWork(…)` returning `Set<UUID>` (the branch's AGW-D21 re-notification and the shared poll read, which the gateway uses). (3) `received` returns `void`; `ack` takes `AgentAck(requestId, generation, outcome, detailCode, detailMessage, containerStartedAt)`; `completion` is abstract. (4) `AgentTelemetryResult` gained `disposition` (ACK / DO_NOT_ACK / REJECTED_COUNTED, §4.2.1); `AgentTelemetryResult.acked(…)` is the common case. (5) `CommandPayload` is the in-memory byte holder `(bytes, configSha256, expectedCurrentSha256)` nested in `CommandOffer`, so `pull` returns the offer and `enqueue(AgentCommandIntent)` has no byte field. (6) ids are `UUID` (agent, target, placement) and `CommandId`; a malformed wire command id raises `UNKNOWN_COMMAND` from `CommandId.parse`. (7) `claim` no longer receives the protocol/agent version the branch passed (the plan's signature has none).
- **Redaction (Global constraint 9).** `dp-api/src/test/.../AgwRecordsRedactionTest` (8 cases; `dp-api` gains junit/assertj at test scope, as `cp-api` has). Written against the default record `toString`: **5 of 8 red** (`EnrollmentRequest` token, `LeaseGrant` nonce, `CommandNotice` digest, `CommandPayload`/`CommandOffer` digests, `ReportItem`/`AgentObservation` plain digest). `toString` overrides on `EnrollmentRequest`, `LeaseGrant`, `CommandNotice`, `CommandPayload`, `CommandOffer`, `ReportItem` and `AgentObservation` → **8 of 8 green**. The test also asserts `AgentSessionRef` has exactly `(agentId, fencingEpoch)` (no tenant), and the `CommandPayload` defensive copy.
- **`agent-gateway` compile fixes (mechanical).** New `AgentGatewayPortSet` record (bundles the separately-wired ports; `AgentGatewayConfiguration` assembles it; no logic). `AgentGatewayService`: each `ports.x(…)` call re-pointed at its port with the new shape; agent-id maps keyed by `UUID`; `CommandId.parse` at the three command-id entry points; `AgentAck`/`ReportItem`/`OperationCorrelation`/`AgentTelemetryBatch`/`LeaseGrant` mapping adjusted; `claim` and `enroll` receive a per-RPC `AgentRequestOrigin` (random correlation id plus the gRPC peer address, carried by a new `CLIENT_ADDRESS` context value set in `AgentGatewayInterceptor`). Not mapped yet, deliberately (T3.2 owns behaviour): the T2.3 wire additions (`health`, `container_started_at`, discovery, declarations, `AgentTelemetryResult.disposition`), `liveFor`, `enqueue`, the new `GatewayCode` → wire `ErrorCode` mapping, and the gateway's calls to the discovery/declarations ports (they are wired into the port set only).
- **Tests.** No test was moved or disabled. `AgentGatewayServiceTest`'s fake was re-shaped to the ten separate ports (`Ports.set()`); two fixture changes only: the command id is a UUID (the string `cmd-1` is no longer a valid `CommandId`), and fake agent ids are order-preserving UUIDs of the test names (the drain staggers by agent-id order). The 32 taken cases pass unchanged in assertion.
- **Gate.** `mvn -o -f backend/pom.xml -pl dp-api,agent-gateway,arch-tests -am verify` under the docker lock: **the first run ran dp-core's 1453 failsafe ITs: 1452 pass, 1 error — `MetricsStorageBenchmarkLocalIT`, "Could not start container tc.postgres:14" (Ryuk start timeout), an environment failure in a module that does not import `dp.api.agw`.** The gate was re-run with `-DskipITs` (no IT exists in `dp-api`, `agent-gateway` or `arch-tests`): BUILD SUCCESS across all 10 modules incl. `console-gateway`, `launcher` (compiled and tested, proving nothing else names an `agw` type) and `arch-tests`; surefire: `dp-api` 8 (new), `agent-gateway` 32, `arch-tests` 139, `dp-core` 1477 (1 skipped), `cp-core` 578, `launcher` 982, `console-gateway` 137, `cp-api` 160, `mesh-events` 50. The full dp-core IT suite is the integrator's wave gate.
- **`port-matrix.md`** re-checked against the signatures and updated in this commit (new signatures, `What changed` rows).

#### T2.1a amendment (2026-10-10)

Review of `1d9e0653` (1 BLOCKER, 4 MAJOR) addressed in one amendment commit; findings 1–11, 13–16 and the javadoc/doc parts of 8 and 17 are in. **Finding 12 (an ArchUnit rule that `agw..` must not reference `AgentPlacementRef`, `AgentCommandIntent` or `AgentObservedStatePort`, with a planted violation) is deferred to T2.1b**, which owns `PlaneBoundaryTest`. The DB columns for finding 1 (`agent_session.features text[]`, `clock_offset_ms bigint`) are **not** added here: V028 is published and anchored, so T2.1b adds them in a new `sm_data` migration; the `AgentSessionPort.claim` javadoc states what the implementation must persist. The interceptor's `getHostAddress()` fix (finding 6) is T3.2's; only the `AgentRequestOrigin` address regex and the L4/PROXY-protocol note are here. The static `NAMES` map in the `AgentGatewayServiceTest` fake stays shared across tests (nit; harmless, ids are deterministic).

Redaction/structural tests: 14 cases (was 8). Written red first with the `toString` overrides of `AgentAck`, `CommandCompletion`, `EnrollmentResult` and `EnrollmentIssuance` temporarily disabled: 3 failures (free-text and `[B@` leaks, and the structural guard `everyAgwRecordIsRedactedAndTenantFree`); green with them restored. The guard reads each `dp.api.agw` record: a `byte[]`/token/nonce/digest/`detailMessage` component needs an explicit `toString`, and a `tenant*` component is allowed only on `AgentPlacementRef`, `AgentIdentity` and `EnrollmentIssuance`; a planted fixture (two local records, three violations) proves it fires. Gate (`-pl dp-api,agent-gateway,arch-tests -am verify -DskipITs`, which also builds `launcher`): BUILD SUCCESS; `dp-api` 14, `agent-gateway` 32, `arch-tests` 139.

### T2.4 — Results (2026-10-10, `feat/agent-gateway/w2-runtime-packaging`, Sonnet 5.5; reviewed by Opus; fix round below)

**Taken** from `87e338e1`: `runtime/{.env.example,.gitignore,README.md,bootstrap/phase0.sh,compose.yml,launcher/Dockerfile,launcher/bin/{sm-launcher,sm-migrate,sm-healthcheck},scripts/build-image.sh}`. **Dropped:** `sm-maintain`, `sm-hash-credential`, `runtime/deploy/*`, and the compose `maintenance` service with every mention of it. (`SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH` stays in compose/`.env.example` because `migrate` still reads it; with `sm-hash-credential` dropped, how an operator makes that hash is a runbook question for T5.2.) `sm-migrate` already runs `SmControlMigrationMain` then `SmDataMigrationMain` (class names as on the branch; not re-verified against this lane's base, which has no `cp-core` agent code yet).

**Step 2 — reproducibility, measured (Docker Desktop 28.5.1, buildx 0.29.1, containerd image store, arm64 host; jar built offline from this worktree, sha256 `fc0c37e4…`).**
- The branch removed `rewrite-timestamp=true` for a reason it did not record; reproduced here: `type=docker,rewrite-timestamp=true` fails with `exporter option "rewrite-timestamp" conflicts with "unpack"`.
- Without the option, two `--no-cache` builds give **different** ids (`sha256:d9a75486…` vs `sha256:cf8df10f…`): the removal broke `--check`'s premise.
- `87e338e1`'s `--check` could not have said so reliably: a failed `docker buildx build` inside `$(build_once)` is masked (command substitution does not inherit `set -e`) and the script printed an unrelated image id twice and **"REPRODUCIBLE"** (observed here with the option restored: both builds errored, the script still passed). Every step is now checked explicitly.
- Fix: export an **OCI archive** with `rewrite-timestamp=true`, `docker load` it. Measured: two archives are byte-identical (tar sha256 `b50930f1…` twice) and `build-image.sh --check` on the final tree gives **identical manifest digests twice** (`sha256:aaf67e9a6b97…de4`; the digest moves with `SOURCE_DATE_EPOCH`, i.e. the HEAD commit time, so it differs after this commit). Scope of the claim: same tree, base digest and epoch, same builder platform; not across JDKs, Maven repositories or platforms.

**Step 3 — compose.** Gateway port `9443` (and readiness `9444` inside the image) as on the branch. The gateway cert/key/trust and the **CA keystore and its password are compose `secrets`** (read-only files under `/run/secrets/`, sourced from the gitignored `./certs`); the previous `SM_AGENT_CA_KEYSTORE_PASSWORD` environment value and the `.env.example` placeholder are gone. `sm-launcher` reads `SM_AGENT_CA_KEYSTORE_PASSWORD_FILE` into `SM_AGENT_CA_KEYSTORE_PASSWORD` for the JVM only (the property placeholder on the branch reads only that variable, so no `backend/` change was needed); unreadable file or both sources set → exit 64, tested with a stub `java`. `docker compose config` renders. **Not changed (pre-existing, owed to the owner of the DB-role design):** the four database role passwords are still environment values from `.env`. *(Forward pointer, 2026-10-10: closed by `OR-138` — the passwords are now generated secret files; see the "OR-138 (W3 side lane)" Results at the end of this plan.)*

**Step 4 — base images (OR-127 L4).**
- Launcher: `eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b` (unchanged; `docker buildx imagetools inspect` confirms that index digest today). The built image reports Ubuntu 22.04.5 LTS, user `sm` uid 10001.
- Agent: `gcr.io/distroless/static-debian13:nonroot` resolves today (anonymous registry call, 2026-10-10) to index `sha256:e2e927ec666bae08560abb3c55d0659eceabb657f56b6782ab500a9fc7f555e3`. **The agent image is `go-agent/Dockerfile`, outside this task's paths (`runtime/**`), so it is not edited**: it still uses the tag and does not copy any notice. Owed to the go-agent owner: pin that digest, and `COPY` `runtime/agent/NOTICE` and `THIRD-PARTY-LICENCES.md` into the image (build context is `go-agent/`, so stage the NOTICE into it).
- Facts checked (`I2`, WebFetch 2026-10-10): the Docker Hub `eclipse-temurin` page states "The project license is GNU GPL v2 with Classpath Exception" and that the images "likely also contain other software which may be under other licenses (such as Bash, etc from the base distribution …)" with compliance the image user's responsibility; the distroless repository `LICENSE` is Apache-2.0 and its README says the images are based on Debian 13 (trixie), the static image holds ca-certificates, `/etc/passwd`, `/tmp` and tzdata, and states **nothing** about the licences of those packages. The per-package licences are therefore the Debian packages' own; this is what the source-offer line (T5.2) must cover. No claim beyond these is made.

**NOTICE handling.** Launcher image: `runtime/launcher/NOTICE` plus `backend/THIRD-PARTY-NOTICES.md` (BoringSSL notices, the OpenSSL License text and the clause 3 acknowledgment) are copied to `/usr/share/doc/sm/` (0444, dir 0755; the first build produced an unreadable 0444 directory, caught by running the image and fixed). Agent: `runtime/agent/NOTICE` holds the **seven** Apache-2.0 NOTICE files verbatim (docker/docker v27.1.2, prometheus client_golang v1.24.1 / client_model v0.6.2 / common v0.70.1 / procfs v0.21.1, grpc v1.84.0 `NOTICE.txt`, yaml.v3 v3.0.1), generated from `go-agent/go.mod` by `runtime/scripts/gen-agent-notice.sh` (`--check` fails when stale). **Owed:** the licence texts themselves (Apache-2.0 section 4(a); the MIT/BSD conditions) for the 32 compiled-in modules are not in `go-agent/THIRD-PARTY-LICENCES.md`, which is a table; the corresponding-source offer for both images is the T5.2 release-runbook line.

**Not done:** `ContainerPackagingIT` (T4.4); a full `docker compose up` (no DB-backed launcher run here: the agent-gateway code is not in this lane's base). Images created for the measurement were removed.

#### T2.4 fix round (2026-10-10)

Review: changes required (5 MAJOR). Supersedes the T2.4 subsection above where they differ; all Docker steps ran under `docker-lock.sh agw2d-fix`.

- **1 Reproducible across days — done.** `useradd` wrote the build day into `/etc/shadow` (measured in the pinned base: `probe:!:20736::::::`, `today_days=20736`) and `SOURCE_DATE_EPOCH` never reaches RUN. The Dockerfile now uses `--no-log-init` and blanks that field; the built image has `sm:!:::::::`. Measured: `--check` gave the same manifest digest twice, and a **separate** later invocation gave the same again: `sha256:52b84bcc6a038a548ce5ff029beb46ae5db23c9f0c9eb2dc2b4e8405ceab19be`. **Not measured: a build on a different calendar day** (no `faketime`, and changing the Docker VM clock would disturb other lanes); the claim rests on the day-stamp being removed (shown above) plus the rewrite-timestamp clamp, not on an observed two-day pair.
- **2 Phase 0 SQL injection — done.** Passwords are psql variables (`\set x \`printenv VAR\``, `:'x'`) in a quoted heredoc. Throwaway `postgres:14.24` run with the password `o'ne;DROP ROLE sm_cp_runtime;--"$x\y` for both migrators: exit 0, all six roles intact, login as `sm_cp_migrator` with that password works, a wrong password is refused. Caveat measured: with the server set to `log_statement=all` (I enabled it on purpose) the plaintext is in the **server** log; the default `none` logs nothing. Client-side SCRAM hashing would remove that; not done.
- **3 Agent NOTICE — done:** `runtime/agent/NOTICE` and `runtime/scripts/gen-agent-notice.sh` removed; the agent NOTICE lives in the agent PR (`go-agent/`, which also owes finding 9, a derived module list).
- **4 Agent-runtime switch — done:** `SM_DP_AGENT_RUNTIME_ENABLED` deleted from compose.
- **5 Console on loopback — done:** `127.0.0.1:8080:8080`, with a comment that a TLS front must serve `SM_TRUSTED_ORIGIN`; 9443 stays on all addresses. **Open owner question: proxy vs direct client connections (`trusted-proxies`).** The branch's `SM_GATEWAY_DIRECT_CLIENT_CONNECTIONS: "true"` is kept unchanged and is not a ruling.
- **6 I8 gate — done:** the build runs `verify` (not `package`); `SM_SKIP_MVN=1` is documented as skipping the gate. Not re-run here (the jar from before was reused).
- **7 — done:** `TZ=UTC0` on the `touch` fallback and `date`; limits list updated. **8 — done:** `name=$1`, loaded id compared with the built digest, `trap` cleanup, `-recheck` image removed after `--check` (verified).
- **10 — done:** `.env.example` values empty; `docker compose --env-file .env.example config` fails closed (`required variable … is missing a value`), a filled copy renders. **11 — done:** `postgres:14.24@sha256:14bfab57…eb6e1` (the index digest `imagetools inspect` returns for both `postgres:14` and `postgres:14.24` today; the local `14.24` image was older, 6 weeks).
- **12, 13, 15 — done:** NOTICE reworded as a requirement gated by T5.2; README citations (`015-history-implementation.md` P7.3, manifest digests, runbooks do not exist yet); Dockerfile comment (/run/secrets); `sm-migrate` `exec`s its last step; `set -f` in `sm-launcher` and `sm-migrate`; `sm-healthcheck` comment on the loopback dial; distroless contents were read from `base/README.md` (`https://raw.githubusercontent.com/GoogleContainerTools/distroless/main/base/README.md`).
- **14 — done:** `sm-launcher` requires a regular, readable, non-empty file (empty file and a directory both exit 64, tested). Better future option, **recorded not adopted:** Spring `spring.config.import=configtree:` reads one property per file; its trailing-newline handling was not verified here.
- **Owed (a):** added to T3.2's text (map the compose environment names). **(b)** the Go standard library BSD-3 text is the agent image's 33rd licence text (agent PR). **(c)** measure that the Java MIT/BSD licence texts or NOTICEs travel in the jars or in `THIRD-PARTY-NOTICES.md`. **(d)** verify the distroless image carries the Debian copyright files (upstream docs say nothing about it).

### T2.1b — Results (2026-10-10, `feat/agent-gateway/w2-persistence-kits`, Sonnet 5.5; reviewed by Opus; fix round below)

The agent-gateway ports over `sm_data` (VDA), nine contract kits, three contracted stubs and the HF11 fixtures. Backend-PR paths only. No `dp-api/agw` signature was changed (HF18); the places where a signature could not be honoured literally are listed under **Stopped and reported**.

- **Kits** (`dp-api/src/test/.../agw/kit/Abstract*PortContractTest`, in `dp-api`'s new test-jar; one `AgentKitWorld` interface carries the scenario set-up and the owner's view of the stored truth, so a kit can assert "nothing was written"). Concrete `Jdbc*PortContractIT` per kit in `dp-core` run the REAL repositories **as the runtime role** on PostgreSQL 14 (`AgentJdbcWorld`; only the control plane's registration, the issuers, the payload source and the clock are fake). Cases (all green): session 8, lease 12, command 18, report (`ActualStatePort`) 7, audit 5, telemetry 6, enrolment 5, discovery 3, identity 9 = **73**, plus `JdbcAgentCommandRepositoryIT` 3 (eight concurrent enqueues mint one command; concurrent intents get distinct ascending generations; a 1 MiB payload leaves the row the same physical size). The plan's five named cases (`pullStoresNothing`, `enqueueReturnsTheLiveCommandForTheSameIntentKey`, `enqueueReturnsTheSucceededCommandRatherThanMintingAnother`, `aRetryAfterAFailedAckMintsANewGeneration`, `supersedeLeavesDeliveredCommandsAlone`) are kit cases under those names, so they bind every implementation. Every kit has the stale-epoch-refused-before-any-write case and a tenant-derived-server-side case; the branch's `AgentReportIT` and `AgentRevocationIT` scenarios are ported into the report and identity kits.
- **Implementation** (`dp-core/.../apply/persistence/agent/`): `AgentAuthority` (the one standing check: current open epoch, identity not revoked, lease active and matching; organization derived from the identity), `JdbcAgent{Identity,Session,Lease,Command,Report,Audit,TelemetryWatermark,Enrollment}Repository`, `AgentSql`, `EnrollmentFactsSource`. Bind parameters throughout (`I5`); only the keyed `hmacsha256v<n>` is stored for configuration (`OR-32`; a plain hash is refused at `enqueue`); the nonce is stored as a digest. `AgentGatewayPersistenceConfiguration` is in the persistence package because `jdbcIsConfinedToPersistencePackages` forbids `org.springframework.jdbc` anywhere else; `dp/agent/AgentGatewayPortsConfiguration` (imported by `DataPlaneConfiguration`, HF4) imports it and declares the three stubs. Collaborators only the launcher supplies (issuers, registration source, enrolment facts, payload source) are resolved at call time through `ObjectProvider`, so a context without the gateway still starts (checked: `SolutionManagerConfigurationTest`, `MetricsExportBootIT`, `InstanceRetirementBootIT`, `HomeEndpointsIT` green). `DataPlaneConfigurationTest` (the pinned import list and the qualified-`Clock` rule) was updated for the two new configurations.
- **Stubs** (each its own class, filled in by the implementing task): `AgentObservedStateStub` (empty), `AgentDiscoveryStub` (fences the session, then every item `REFUSED agent.discovery.unavailable`, in order, nothing written), `AgentTelemetryDeclarationsStub` (fences, then none); `FakeAgentCommandPayloadSource` (test sources, public) for T3.1. Pinned by `AgentContractedStubsTest` (4) and, against a database, by the discovery kit.
- **Fixtures (HF11):** `InventoryFixtures.agentTarget(jdbc, tenant, agent[, thumbprint])` (the agent target and its identity in one transaction; the two references are mutual) and `agentPlacement(...)`, with public bridges in `AgentItSupport`.
- **Arch rules:** `PlaneBoundaryTest` — every `dp.api.agw` `*Port` interface is in `KITTED_AGENT_GATEWAY_PORTS` (9) or `CONTRACTED_STUB_AGENT_GATEWAY_PORTS` (2), planted `BadUnkittedAgentPort` seen; `TenantEnumerationBoundaryTest` — `resolveByThumbprint` callers are held to the session and enrolment repositories, planted `BadThumbprintResolver` seen. arch-tests: `PlaneBoundaryTest` 13+23, `TenantEnumerationBoundaryTest` 6, green.
- **Red/green, stated as it happened.** The repositories were written together with the kits, so Step 2's "run the kits and see them fail" **was not taken as a separate pre-implementation run**; the red evidence is the mutation matrix below, in which every kit rule goes red against a deliberately broken implementation. The first run against the real database was 105 run, 3 errors, all one fixture bug of mine (the world created one `REQUESTED` apply request per intent and the apply fence refused the second: `DuplicateKey` on `apply_request`), fixed in `AgentJdbcWorld` (one request per placement, one replica per intent); then **105/105 green**.
- **Mutation proofs** (each applied, the ITs run, red, reverted; `git diff` empty afterwards):

  | # | Mutation | Red |
  |---|---|---|
  | M1 | drop `AND s.fencing_epoch = :epoch` from `AgentAuthority` (skip the epoch check) | 7 kits, one stale-epoch case each: audit, command, discovery, identity, lease, report, telemetry (session stays green: `heartbeat` carries its own predicate) |
  | M2 | take the organization from the batch's `tenant_id` label | `JdbcAgentTelemetryPortContractIT.theOrganizationIsTheIdentitysWhateverTheBatchLabelsClaim` (23503) |
  | M3 | `enqueue` mints even when a live command exists | `enqueueReturnsTheLiveCommandForTheSameIntentKey`, `oneLiveCommandPerIntentKeyAndOperation`, `eightConcurrentEnqueuesOfOneIntentMintExactlyOneCommand` (all 23505 on `agent_command_one_live_per_intent`) |
  | M4 | `payload bytea` added to `agent_command` in V028 | `AgentSchemaIT.noAgentTableCanHoldRenderedBytesOrAPlainDigest` |
  | M5 | `pull` writes the plain digest into the row (the closest the columns allow to "store the bytes") | `pullStoresNothing`, `aMegabytePayloadLeavesTheRowTheSamePhysicalSize` |
  | M6 | `supersedeOutstanding` also takes `DELIVERED` | `supersedeLeavesDeliveredCommandsAlone` |
  | M7 | drop `AgentLeasePort` from the certified set | `PlaneBoundaryTest.everyAgentGatewayPortInterfaceHasAKitOrAContractedStub` |
  | M8 | allow the planted resolver | `TenantEnumerationBoundaryTest.onlyTheSessionAndEnrollmentRepositoriesResolveACertificateToAnIdentity` |

- **Gates.** Targeted (`-Dit.test='*Agent*PortContractIT,JdbcAgent*IT,AgentSchemaIT'`, under the docker lock): BUILD SUCCESS, `dp-api` unit 8, `dp-core` unit 1481 (1 skipped), failsafe **105/105**. Full `mvn -o -f backend/pom.xml -pl dp-core -am verify -Dmaven.test.failure.ignore=true`: BUILD SUCCESS, failsafe **1542 run, 0 failed, 12 skipped** (no Testcontainers start failure). The first targeted attempt waited ~80 minutes on another lane's docker lock; nothing else was disturbed.
- **Stopped and reported (no signature changed; each needs the integrator or an owner, none blocks T2.2 or W3).**
  1. **`AgentLeasePort.current` cannot return a grant**: a stored lease holds only the digest of its renewal nonce, and `LeaseGrant` requires the nonce. It returns empty after the session check; a reconnecting agent goes `issue` (refused `ISSUE_NOT_ALLOWED` while a lease lives) then `renew`. T3.2's `SessionOpen` must treat that as the normal path, not an error.
  2. **`EnrollmentResult` has no organization, catalog solution, solution or package version**, all `NOT NULL` on `agent_identity`. The repository takes them from a new seam, `EnrollmentFactsSource`, that T2.2/T3.5 must implement from the registration that issued the certificate; the alternative is a signature change through the integrator.
  3. **`AgentAuditPort.record` has no origin**, while `agent_event.correlation_id` and `client_address` are `NOT NULL`: derived UUID and `unknown`.
  4. **Unbound reads** (superseded by the fix round: `completion`, `agentsWithDeliverableWork` and `close` are now bound; exactly TWO statements derive an organization, `resolveByThumbprint` and `AgentAuthority#requireEpochHolder`, and arch rules name both).
  5. **`claim` has no request id and `agent_session` no column for one**: fenced, not replayed; protocol `1` / agent version `unknown` recorded.
  6. Not persisted (no column): `AgentCommandIntent.supersedesGeneration`, the ack's free-text message and `containerStartedAt`; `CommandNotice.configSha256` is present only if the payload source still holds it. The sweep answers `RENEWAL_REQUIRED`, never `REISSUED`.
  7. **`JdbcAgentDiscoveryRepository` was not created** (the plan's file list names it): there is nothing to write until T4.1, and `agent_discovery` is T4.1's table; the report and telemetry repositories are the persistence halves (watermarks) that T3.3 and T3.4 wrap, and the telemetry one is deliberately not exposed as the `AgentTelemetryPort` bean.
  8. The incarnation id's UUIDv5 namespace constant in `JdbcAgentReportRepository` is mine; it is now FROZEN by a dated clarification of `AGW-D25` (see the fix round below).
- **Docs:** `port-matrix.md` gains "What the persistence implementation fixes (plan 015 T2.1b)" restating items 1-6 against the matrix.

#### T2.1b fix round (2026-10-10, `feat/agent-gateway/w2-persistence-kits`, Sonnet 5.5; answers the Opus review of `34d9e55b` and rebases onto the T2.1a amendment `656d01c5`; review pending)

Commits on top of `656d01c5`: the rebased T2.1b, `V030`, the fix (code and tests), the docs, and one kit-case commit. Backend-PR paths only. Statements in the section above that this round changes are corrected in place or marked "superseded".

- **Rebase.** `claim(thumbprint, SessionOpening, origin)` (protocol refused if not positive, agent version `""` to `unknown` and capped at 64, features, clock offset), `heartbeat(ref, agentClock)`, `featuresOf`; `issue`/`renew`/audit/report/telemetry/discovery take the origin; enrolment consumes `EnrollmentIssuance` (the `EnrollmentFactsSource` seam, its provider, bean parameter and test lambda are deleted); `received` carries a request id (1-64); `completion(placement, id)`; `agentsWithDeliverableWork` joins the session table; the 3-argument `pendingFor` everywhere.
- **V030** (`agent_session.features`, `clock_offset_ms`; header facts WebFetched 2026-10-10 and quoted in the commit): `AgentSchemaIT` 30, pinned counts moved (migrations 30, CHECKs 185). `frozen-migration-anchors.txt` untouched; `check-frozen-migrations.py` PASS with V030 reported as not yet anchored.
- **Findings, each with its evidence.** Tests were written WITH the fixes, not before them; the red evidence is the revert-the-fix matrix below (baseline 97 green, then each fix reverted).

  | Finding | Outcome | Revert-the-fix run (red) |
  |---|---|---|
  | 1 fencing | fixed: standing check `FOR SHARE OF` session and identity as the first statement of the write's own transaction; command writes also join the session on the epoch | M1 drop `FOR SHARE`: `JdbcAgentSessionRepositoryIT.aClaimCannotLandBetweenTheEpochCheckAndTheWrite` (the claim did not wait). The epoch join is defence in depth and has no separate test: with the lock and the check in front of it, no input reaches it |
  | 2 lost reply | fixed: deterministic issuer asked again, digest compared, nothing written | M3: `aLostRenewReplyIsReplayedToTheSameGrantWithoutAnotherWrite`, `aReplayedIssueAfterALostReplyReturnsTheSameGrantAndMintsNoSecondLease` |
  | 3 nonce-free re-issue | fixed (stricter reading): `issue` refused whenever any lease row exists except the replay; `AGW-D19` not edited, a dated clarification appended; conflict left for the owner | M4: `aLapsedLeaseCannotBeReplacedByIssueButCanBeRenewed`, `aReplayedIssue…` (23505 on `agent_lease_nonce_once`) |
  | 4 bean exposure | fixed: alias bean dropped; report and telemetry repositories implement neither port; kits run them through test-scope adapters; arch rule `noProductionClassImplementsTheReportOrTelemetryPortYet` with a planted implementation | arch: planted `PlantedPortBean` seen |
  | 5 / 5b rule bypass | fixed: the call-site rule counts `AgentIdentityPort#resolve` and `JdbcAgentIdentityRepository#resolve` (planted gateway-shaped caller seen); `agw..` may not call `AgentIdentity#tenantId` nor name `AgentPlacementRef`/`AgentCommandIntent`/`AgentObservedStatePort` (planted violations). `agw` does none of these today, so nothing was frozen | arch tests (`AgentGatewayBoundaryTest` 13, `TenantEnumerationBoundaryTest` 7, `PlaneBoundaryTest` 24) |
  | 6 unbound statements | fixed: `completion`, `agentsWithDeliverableWork`, `close` bound; `AgentAuthority` named as the second derivation (rule `onlyAgentAuthorityDerivesATenantFromAnAgentId`, planted read seen); the count above corrected | M9 completion unbound, M10 deliverable-work unbound: both red |
  | 7 claim vs revocation | fixed: one statement, `INSERT … SELECT … FROM agent_identity WHERE revoked_at IS NULL FOR SHARE … ON CONFLICT`; zero rows is `IDENTITY_REVOKED` | M5: `aClaimIsAtomicWithARevocationThatLandsFirst` |
  | 8 grant validation | fixed: thumbprint, target, epochs, capabilities within the identity's, non-negative ceilings and margin, lifetime at least longest ceiling plus margin, placements within the DP set; all `GRANT_INVALID` (the port's existing code) before any write | M6: `aGrantThatDoesNotMatchTheIdentityIsRefusedBeforeAnyWrite`, `aGrantNamingAPlacementTheDataPlaneDidNotSupplyIsRefused` |
  | 9 issue syncs first | fixed (`issueSyncsTheRegistrationFirstSoARevokedIdentityGetsNoLease`) | |
  | 11 unwired collaborators | fixed: absent or ambiguous provider answers `INTERNAL_UNAVAILABLE` (also for the payload source) | |
  | 12 enrolment kit | fixed: the replay is a fresh request id with the same token and CSR; `whenTheDpHalfFailsNothingIsWrittenAndAReplayCompletesIt` (a squatter on the thumbprint makes the identity insert fail and the whole DP transaction roll back) | |
  | 13 kit gaps | all added: same-request ack with another outcome; received/ack on a superseded command answer `SUPERSEDED` (code fixed); audit request-id reuse (first writer wins, pinned); `liveFor`/`featuresOf` with a forged tenant; deliverable-work with a stale epoch and across organizations; skewed heartbeat never moves `last_seen_at`; `featuresOf`; received request-id replay; completion across organizations; the vacuous `current(...).ifPresent` is gone | M8 superseded ack: red |
  | 14 sync | fixed: never lowers an epoch (`aControlPlaneReadThatIsBehindNeverLowersTheEpochsTheDataPlaneHolds`) | M7: red |
  | 17 | fixed: a negative report sequence refuses that item only; no `GatewayCode` names "malformed item", so `UNKNOWN` is used (reported); the 30-day re-enqueue edge is in T3.1's plan text | |
  | Stopped item 1 | `current` answers empty when no lease row has ever existed and `LEASE_RENEW_REQUIRED` otherwise; the nonce is stored in no form | |
  | Item 6 / 16 | doc-only javadoc on `LeaseIssuer` ("identical inputs return an identical grant"), `AgentIdentityPort#reconcile`, `IdentityReconciliation#REISSUED`; dated clarifications appended to `AGW-D19` and `AGW-D25` (the frozen UUIDv5 namespace) | |

- **Plan text** added to T3.1, T3.2, T3.3, T3.4, T4.1 and T2.1b's file list (as dispositioned).
- **Counts.** Kit cases per port: session 12, lease 16, command 23, report 8, audit 7, telemetry 6, enrolment 6, discovery 3, identity 10 = **91** (73 before); `JdbcAgentCommandRepositoryIT` 3; `JdbcAgentSessionRepositoryIT` 3; `AgentSchemaIT` 30. Targeted ITs 97 green (the revert matrix baseline); with `AgentSchemaIT` and the four pinned-count ITs 153 run, the 4 count failures (`ApplyPersistenceConfigurationIT`, `SmMetricsMigratorIT`) fixed.
- **Gate** (`mvn -o -f backend/pom.xml -pl dp-api,dp-core,agent-gateway,launcher,arch-tests -am verify -Dmaven.test.failure.ignore=true`, under the docker lock): BUILD SUCCESS, **0 failures, 0 errors** in every module. Surefire: mesh-events 50, dp-api 14, cp-api 160, cp-core 578, dp-core 1481 (1 skipped), agent-gateway 32, launcher 982, console-gateway 137, arch-tests 148. Failsafe: cp-core 496, dp-core **1564 run (12 skipped)**, launcher **436 run (44 skipped)**. `check-frozen-migrations.py` PASS (V030 unanchored by design), `check-licences.py` OK, `git diff --check` clean. Not re-run live after the last two edits (the `atexit` line in the scenario and the relative state-path wording in `sm-local.sh`'s printed messages): syntax-checked and `test-sm-local.sh` ok only.
- **Reported, not fixed here.** (i) No `GatewayCode` fits a malformed report item, a malformed received request id (an `IllegalArgumentException`) or a lifetime violation precisely; `UNKNOWN`, `IllegalArgumentException` and `GRANT_INVALID` were used. (ii) `AGW-D19` item 3 ("may be replaced") conflicts with design 4.6; the stricter reading is built and the cost (one control-plane call per reconnect; a control-plane outage blocks reconnect) is in T3.2's plan text for the owner. (iii) T2.2's `deterministicNonce` needs a keyed HMAC and the CP never checks `consumedNonce` (cross-lane note, T2.2's fix round). (iv) FIXED in a follow-up: a replayed grant is returned with the stored row's `issuedAt`/`expiresAt` and `serverClock` = DP now, and a replay whose capabilities, ceilings or placements differ from the row's is refused `LEASE_RENEW_REQUIRED` with nothing written.
- **Gap reported:** no `GatewayCode` fits a malformed report item or a malformed `received` request id; `UNKNOWN` and `IllegalArgumentException` are used for now.


### T2.2 — Results (2026-10-10, `feat/agent-gateway/w2-cp-enrolment`, Sonnet 5.5; reviewed by Opus; fix round below)

CP enrolment, the operator-keystore CA, CP-issued leases and the launcher's three adapters, written against T2.1a's signatures. No T2.1a signature was changed (HF18): every one was implementable as declared. Backend-PR paths only.

- **Step 0 (a), measured.** `TargetService.register(PrincipalRef actor, org, Request, RequestOrigin)` checks `target.create` at the owner scope, accepts only the pair `docker` / `ssh-docker`, and in one transaction sets the audit context (`TARGET_REGISTERED`, principal actor), reads the live row by name (a replay or a clash) and calls `TargetRepository.insert`; a lost race on `target_name_once` is retried once. It cannot be used by the agent as it stands (principal actor, `ssh-docker` only). T2.2 therefore reuses its **write path** (`TargetRepository.findByName` + `insert`, idempotent on organization and name) under `AGENT_ENROLLED`, with `created_by` = the token's issuing principal. **Consequence for T3.5:** V021's `agent_registration_consumed` CHECK makes `target_id`, the certificate and the used-at arrive together, so the CP target row had to be written in this task, in the consumption transaction; T3.5's "the consumption transaction gains the CP target row" is already true. T3.5 adds the DP half (`target_connection`, `agent_identity`, the `enrolled` event), the degraded listing and the sweep.
- **Step 0 (b), measured.** The branch's `JdbcAgentRegistry` updated only at token consumption and certificate store; `findActive` is a SELECT. Ours: `issueLease` is reads plus signing. `AgentEnrollmentIT` counts `change_audit` rows around an `issueLease` and finds none added; mutation M8 (an UPDATE planted in `findActive`) is refused by the database (`OR025`). **`AGENT_LEASE_ISSUED` is not added to `VCA`.**
- **What was built.** `cp-api/.../agent`: `ControlPlaneAgentGateway` (`enroll`, `issueLease`, `registrations`), `AgentEnrolment` (token issue), `AgentEnrollmentRequest` (+`RequestOrigin`), `AgentEnrollmentMaterial` (+organization, environment, target, envelope, issuing principal), `AgentLeaseRequest`/`AgentLeaseMaterial` (+`placementIds`, UUIDs), `AgentRegistration` (no `binding_ids`, no verb ceilings, `targetId` null until consumed), `AgentEnrollmentRefusedException` (`UNKNOWN_TOKEN`, `EXPIRED`, `CONSUMED`, `TARGET_NAME_TAKEN`; fixed sentences). `cp-core`: `Pkcs12AgentCertificateAuthority` and `AgentGatewayCaProperties` as built (A4), plus a CSR that does not parse or verify is an `IllegalArgumentException` naming nothing of it; `AgentRegistry` redesigned (`claimEnrollment` + `storeEnrollment` became one `consume`, for the CHECK above); `ControlPlaneAgentGatewayService`, `AgentEnrolmentService`, `JdbcAgentRegistry`, `JdbcAgentAuditContext` (sets `sm.audit.actor_agent`, clears the principal), `AgentConfiguration` (imported by `ControlPlaneConfiguration`). `launcher`: `ControlPlaneEnrollmentCertificateIssuer`, `ControlPlaneLeaseIssuer`, `ControlPlaneRegistrationSource`, `AgentGatewayControlPlaneAdapterConfiguration`, `FailureDiagnostics`, `AgentCapabilities` (maps `apply-config` to `APPLY_CONFIG`).
- **Behaviour the plan listed.** Every CP write at consumption runs under `AGENT_ENROLLED`, actor the agent, with the gateway-minted correlation id and the peer address (`AgentEnrollmentIT` reads the `change_audit` rows of both the registration UPDATE and the target INSERT: `actor_agent_id` set, `actor_principal_id` null, the origin's address). The CA signs **before** the first write, so a missing keystore consumes nothing and writes nothing (unit and IT). A replay (same token, same CSR) answers the same certificate and target id and writes nothing, even after the token's window; another CSR is `CONSUMED`. The lease signs exactly the DP-passed placement set (an empty set stays empty); `VERB_CEILINGS` carries `PREFLIGHT` 30 s, `APPLY` 60 s, `RESTART` 120 s, `STOP` 60 s and `REMATERIALIZE` 60 s. The ceilings moved from the registry to a constant in the service so a unit test can assert them. The nonce derivation is as built (`AGW-18`). Token issue checks `agent.enrol` and `target.create` at the environment before anything else, stores the envelope and name, stores only the unkeyed SHA-256 of a 256-bit token (`OR-128` (9)), refuses an envelope outside the `registerTarget` grammars (the V021 CHECK patterns), and audits `AGENT_ENROLMENT_ISSUED` with the principal (`created_by`). Default window 1 h, maximum 7 d (my choice; the plan names none).
- **T1.2 review finding 7 (enrolment request id).** The gateway's `enroll-` + `nameUUIDFromBytes(token:csrHex)` (an unkeyed MD5 of the token) is replaced in `AgentGatewayService.enroll` by `"enroll-" + origin.correlationId()`, minted per RPC. The control plane never used that id (its replay key is the stored token and CSR digests). It is the only `agent-gateway` edit; `AgentGatewayServiceTest` gained a case (same token and CSR twice give two different ids, none equal to the old MD5).
- **Decisions for T3.2 and the reviewer.** (1) *Peer address:* the gateway's `CLIENT_ADDRESS` is `SocketAddress.toString()` (`/ip:port`), which `RequestOrigin` rejects (it demands an IP literal). The enrolment adapter extracts the literal (`peerLiteral`, tested for `host/ip:port`, `/[v6]:port`, bare forms) and **fails closed** (`INTERNAL_UNAVAILABLE`, nothing sent) on anything else; it never defaults the address. T3.2's interceptor should pass the bare literal. (2) *Fail closed at boot:* `AgentGatewayCaProperties.required` (default false); when true, the CA bean verifies the keystore at startup and refuses to start without one. The default is false because nothing composes the agent path yet and every existing launcher context has no keystore; **T3.2's launcher composition sets `sm.control.agent-gateway.ca.required=true`.** (3) *Expiry mapping:* an expired and a consumed token both answer `TOKEN_CONSUMED` (the plan's wording); an unknown or revoked one `UNAUTHENTICATED_IDENTITY`; a taken target name `ISSUE_NOT_ALLOWED`. (4) The adapter configuration is **not** imported by `SolutionManagerConfiguration` (HF17 is T3.2's). (5) The environment's canonical `scope` row must exist (hierarchy creation mints it); the agent actor may not insert one, so a missing row is an `OR025` refusal, not a silent mint.
- **Step 4, Bouncy Castle (P15-13).** Baseline `dependency:tree`: cp-core bcprov 1.86; dp-core bcprov 1.80, bcpkix 1.80, bcutil 1.80.2 (runtime, through `sshj`); launcher bcprov 1.86 beside bcpkix 1.80 and bcutil 1.80.2, a mixed set. All three are now pinned at 1.86 in `backend/pom.xml` `dependencyManagement` (one `bouncycastle.version` property); cp-core depends on bcprov and bcpkix without versions. After: cp-core, dp-core and launcher resolve exactly bcprov, bcpkix and bcutil 1.86 (`agent-gateway` resolves no Bouncy Castle artifact at all; the first draft of this line wrongly listed it). `launcher/BouncyCastleSingleVersionTest` asserts the jar each of the three artifacts is loaded from is `-1.86.jar`. `dp-core`'s whole suite, including every `ssh` package class (`SshjDockerTransport*`, `PinnedHostKeyConnectTest`, `SshHostKeyScanner*`, `SshDockerRuntimeAdapterContractTest`, the pool), ran green on 1.86. **Licence:** `licence-exceptions.txt` gained `bcpkix-jdk18on--1.86` and `bcutil-jdk18on--1.86` (OR-127 L2's two exact-coordinate lines, text unchanged: Bouncy Castle Licence) and lost `bcprov--1.80`, `bcpkix--1.80`, `bcutil--1.80.2` (no module resolves them any more, measured). `python3 backend/scripts/check-licences.py` → `OK (192 build-plugin realm artifacts, 70 exception lines)`; `test-check-licences.sh` and `test-licence-gate-present.sh` pass; the shipped `license-maven-plugin` executions passed in every module of the full build. `bcpkix` and `bcutil` 1.86 were not in `~/.m2`; they were fetched from Maven Central with `mvn dependency:get` (exactly OR-127 L2's coordinates, no other coordinate added or changed). Nothing went back to the owner.
- **Test-first honesty.** The signatures were fixed (T2.1a) and the cp-api shapes churned while the adapters were written, so the production code was written before its tests; there was no red run before implementation. The red evidence is the mutation matrix below (each test class was observed red against a planted fault, then green on the restored file).
- **Mutation matrix (each red, then reverted byte-identical).**

Numbering note: the ids are those of the working list; **M10 and M11 were never run** (the list skipped them), so the table runs M1-M9 and M12 (the gap is kept so the ids already reported stay stable).

| # | Mutation | Red |
|---|---|---|
| M1 | accept a token after `expires_at` | `ControlPlaneAgentGatewayServiceTest` 2 (`anExpiredToken…`, `aTokenAtItsExactExpiryIsRefused`) |
| M2 | the CP adds a placement id not in the request | `aLeaseNamesExactlyThePlacementSet…` |
| M3 | write the consumption without the agent actor (unit) | 2 (event order, name-taken) |
| M3b | the same, against the real V021 trigger | `AgentEnrollmentIT` 2 errors, SQLSTATE `OR025` "no known audit_operation" |
| M4 | drop the BC pin (bcpkix 1.80, bcutil 1.80.2 managed) | `BouncyCastleSingleVersionTest` (`bcpkix-jdk18on-1.80.jar`) |
| M5 | a real fourth `LeaseIssuer` in the launcher | `CompositionBoundaryTest` 2 (the rule and the real-adapters test) |
| M5b | a launcher class outside the three touching `cp.api.agent` | `CompositionBoundaryTest` 2 |
| M6 | drop `REMATERIALIZE` from the ceilings | `aLeaseCarriesAVerbCeilingForEveryVerb…` |
| M7 | enrolment request id from the token again | `AgentGatewayServiceTest` 1 |
| M8 | a write planted in the lease read path | `AgentEnrollmentIT` 2 (`OR025`) |
| M9 | allow `..` in the config root | `AgentEnrolmentServiceTest` 1 |
| M12 | a replay ignoring the CSR | `theSameTokenWithADifferentCsrIsRefusedAsConsumed` |

  The planted fourth adapter (`launcher/archfixture/BadFourthCpAdapter`) and a planted launcher class (`BadLauncherCpAgentClass`) turn `onlyTheNamedAdapterImplementsTheLeaseIssuerPort` and `onlyTheNamedAdaptersTouchTheCpAgentApi` red in `CompositionBoundaryTest` (5 → 9 cases; four rules, three planted/real proofs).
- **Tests added.** `cp-core`: `ControlPlaneAgentGatewayServiceTest` 17, `AgentEnrolmentServiceTest` 8, `Pkcs12AgentCertificateAuthorityTest` 6, `AgentEnrollmentIT` 6 (PG14, as `sm_cp_runtime`). `launcher`: `ControlPlaneAdaptersTest` 10, `AgentGatewayControlPlaneAdapterConfigurationTest` 1, `BouncyCastleSingleVersionTest` 1. `arch-tests`: `CompositionBoundaryTest` 9. `agent-gateway`: `AgentGatewayServiceTest` 20 (+1).
- **Gate.** `docker-lock.sh agw2b mvn -o -f backend/pom.xml -pl cp-core,launcher,arch-tests -am verify -Dmaven.test.failure.ignore=true`, 1 h 09 min, all ten modules built. Surefire: cp-api 50, dp-api 8, mesh-events 160, cp-core 609, dp-core 1477 (1 skipped), agent-gateway 33, console-gateway 982, launcher 149, arch-tests 146. Failsafe: cp-core 502, dp-core 1466 (12 skipped), launcher 436 (44 skipped). **One failing class: `launcher/MeshResilienceRehearsalIT.aMidSessionBrokerOutageNeverParksTheRequestThreadAndPublishingResumesWhenTheBrokerReturns`** (`publishing resumed … expected true but was false`, a broker-outage timing test, 326 s in that run); re-run alone with the same flags it passed 7/7 (202 s), so it is an environment timing flake under load, not T2.2 (the class imports nothing in `agent`). `git diff --check` clean. Not re-run live after the last two edits (the `atexit` line in the scenario and the relative state-path wording in `sm-local.sh`'s printed messages): syntax-checked and `test-sm-local.sh` ok only.
- **Not done / for the reviewer.** The HTTP surface for token issue (T4.3), `revokeAgent` and its `AGENT_REVOKED` write (T4.3), the DP half of enrolment and the degraded-target sweep (T3.5), the launcher import and `required=true` (T3.2), and the `AgentGatewayInterceptor` peer literal (T3.2) are not in this commit.

#### T2.2 fix round (2026-10-10, Sonnet 5.5; review `w2b-review.md`, Opus; rebased onto `656d01c5`)

Rebased `30501818` onto the T2.1a amendment `656d01c5` (one conflict, this file). No `dp-api` signature was changed; every amended one was implementable as declared.

- **Rebase adaptations.** `ControlPlaneEnrollmentCertificateIssuer.issue` returns `EnrollmentIssuance` (tenant = the CP row's organization, never the request; catalog solution id, solution key, declared package version; issuing principal as a UUID). `AgentEnrollmentMaterial` and `AgentRegistry.Enrollment` carry `catalogSolutionId`, `packageVersion` and the solution key; `JdbcAgentRegistry.lockByTokenDigest` joins `catalog_solution` on `(organization_id, catalog_solution_id)` with `FOR UPDATE OF r`. `LeaseIssuer.issue` takes the trailing `AgentRequestOrigin` (unused: a lease writes no row). `AgentLeaseRequest` requires a non-blank thumbprint. `ControlPlaneAdaptersTest` uses regex-valid origins; `peerLiteral` lost its unreachable slash/bracket branches. `AgentGatewayServiceTest`'s two-RPC request-id case runs on `656d01c5`'s fake with a valid client address.
- **Red evidence.** The fixes were written before their tests were re-run, so "red before" is shown by reinstating each pre-fix behaviour on the fixed tree (one mutation per finding, reverted byte-identical), the same method as the matrix above:

| Finding | Pre-fix behaviour reinstated | Red |
|---|---|---|
| 1 BLOCKER | drop `scopes.canonicalize(environment)` at issue | `AgentEnrollmentIT` 1 failure + 4 errors, SQLSTATE `OR025`, "write to sm_control.scope refused: AGENT_ENROLLED is not permitted on this table" (the reviewer was right; the old IT hid it by seeding the scope row as the migrator, which is removed); unit `issuingStores...` also red |
| 2 | REVOKED mapped to ISSUE_NOT_ALLOWED | `ControlPlaneAdaptersTest.eachLeaseRefusalReason...` |
| 4 | key policy off | `onlyEcP256AndP384KeysAreAccepted` (RSA-1024 not refused) |
| 5 | no thumbprint binding / no `min(ttl, valid_to)` / no expired-leaf refusal | `aLeaseIsBoundToTheIssuedCertificate`, `aLeaseNeverOutlivesTheCertificate...` x2 |
| 6 | replay of an expired leaf allowed | `anExpiredCertificateIsNotReplayedAsAnEnrolment` |
| 8 | `consumedNonce` printed | `AgentRecordsRedactionTest.aLeaseRequestDoesNotPrintTheConsumedNonce` |
| 9 | hierarchy check off | `AgentEnrolmentServiceTest` 2 |
| 10 | `organization_id` unbound in the registration read | `AgentEnrollmentIT` (another organization's id must be `NOT_ENROLLED`) |
| 12 | `FOR UPDATE OF r` removed | `twoCsrsRacingOnOneTokenLeaveExactlyOneConsumptionAndOneTarget` |
| 14 | 160-bit serial / `keyEncipherment` / no CA-notAfter cap / SHA-256 for a P-384 CA | one `Pkcs12AgentCertificateAuthorityTest` case each (the serial case draws 24 serials) |
| 15 | stray `IllegalArgumentException` -> UNAUTHENTICATED; non-bytea element re-encoded | `ControlPlaneAdaptersTest.everyRefusalIs...`; `JdbcAgentRegistryTest` |
| 11 | (planted) | `CompositionBoundaryTest.aPlantedLauncherClassComposingBothPlanesApisTurnsItsRuleRed` (`BadLauncherBothPlanesClass`); the 10 earlier cases stay |

- **Outcomes.**
  1. *Fixed.* The reviewer's finding is confirmed by the table: the scope row is minted lazily (V018) and the agent actor may not insert it (V021). `AgentEnrolmentService.issue` canonicalizes the environment's scope in its audited transaction (`AGENT_ENROLMENT_ISSUED`, the principal as actor) via `JdbcScopeStore` (bean `agentScopeStore`). `AgentEnrollmentIT.tree()` no longer seeds it; a new IT enrols into a fresh environment, asserts the scope row's audit row names the issuing principal, and asserts exactly one canonical row afterwards.
  2. *Fixed.* `AgentLeaseRefusedException(Reason)` with `REVOKED`, `AGENT_EPOCH_STALE`, `FLEET_EPOCH_STALE`, `TARGET_MISMATCH`, `NOT_ENROLLED`, plus `CERTIFICATE_MISMATCH` and `CERTIFICATE_EXPIRED` (findings 5). Mapping (`ControlPlaneLeaseIssuer.code`): `REVOKED` and `AGENT_EPOCH_STALE` -> `IDENTITY_REVOKED` (the gateway's `revokeAndClose` path); `TARGET_MISMATCH` -> `TARGET_MISMATCH`; the rest -> `ISSUE_NOT_ALLOWED`. Justification from `AgentGatewayService`: `ISSUE_NOT_ALLOWED` on a first issue becomes `LEASE_RENEW_REQUIRED` and on a refused renewal `RE_ENROLL_REQUIRED`, which is right for "not enrolled" and "certificate is not the issued one / has expired" (the agent must enrol again) and for `FLEET_EPOCH_STALE`: a fleet bump that the DP's cached identity has not caught up with is not proof against THIS agent, and if the fleet really is revoked the DP's reconcile through `registrations()` flips the identity and closes the streams. An agent-epoch bump is how an agent revocation is expressed, so it closes streams. A revocation is named `REVOKED` even when its epochs also moved (checked first). One unit test per mapping (all seven reasons asserted, and the count of reasons equals the count of mappings).
  3. *Plan text (T3.2).*
  4. *Fixed.* EC P-256 and P-384 only (curve parameters compared, so secp256k1 is refused too); fixed message "the signing request is not acceptable" (`Reason.CSR_REFUSED`). Tested: RSA-1024, RSA-2048, P-521, secp256k1, DSA-1024 refused; P-256 and P-384 accepted.
  5. *Fixed.* The lease is refused unless the request thumbprint (lowercase hex SHA-256, compared case-insensitively) equals the SHA-256 of the stored `certificate_chain_der[0]`; `expiresAt = min(issuedAt + ttl, valid_to)`; refused when `valid_to <= now`. The granted `certificateThumbprint` is the issued one.
  6. *Fixed.* A replay is refused (`EXPIRED`) once the stored leaf's `valid_to` has passed; one second before, it still replays.
  7. *Plan text (T3.5).*
  8. *Fixed.* `AgentLeaseRequest.toString` omits `consumedNonce`; `AgentLeaseMaterial.toString` already omitted the nonce; `cp-api/.../AgentRecordsRedactionTest` (5 cases) covers the request, material, enrolment request and issued token.
  9. *Fixed.* Before authorization, `ResourceScopeDirectory.findScopePath(ENVIRONMENT)` must equal the named path, else `ResourceNotFoundException(ENVIRONMENT)` (wrong unit and unknown environment both tested).
  10. *Fixed, with one named exception.* `AgentRegistry.find(organizationId, agentId)` binds `organization_id`; `AgentLeaseRequest` carries `organizationId` (the DP-resolved `AgentIdentity.tenantId()`). `AgentRegistrationSource.current(Collection<UUID>)` names no organization (the reconcile spans sessions), so `findAll` stays keyed by agent id alone (agent ids are globally unique, V021) and is named in `arch-tests` `AgentRegistryBoundaryTest` as the sole unbound read with a single allowed caller (`ControlPlaneAgentGatewayService`), a planted second caller turning it red. `lockByTokenDigest` is keyed by the token, which is the credential and names its organization.
  11. *Fixed.* `noOtherLauncherClassTouchesBothPlanesApis`, with a planted fixture.
  12. *Fixed.* Two threads, two different CSRs, one token, real PostgreSQL: exactly one enrolment, one `CONSUMED`, one target, one `AGENT_ENROLLED` registration audit row. The CSR-SAN test is a unit case and an IT case (a CSR asking for a SAN, `CA:true` and a server EKU yields a leaf with none of them).
  13. *Owner.* The token window (default 1 h, maximum 7 d) is **an implementer default awaiting the owner**; no ruling names it. Left as is.
  14. *Fixed.* 159-bit positive serial; EC leaves carry `digitalSignature` only; a leaf's notAfter is capped at the CA's (and an expired CA signs nothing); `SHA384withECDSA` when the CA key is P-384.
  15. *Fixed.* A non-`byte[]` array element throws (`JdbcAgentRegistry.bytesOf`); the issuer maps only the CP's own CSR refusal to `UNAUTHENTICATED_IDENTITY`, and a stray `IllegalArgumentException` is `INTERNAL_UNAVAILABLE`.
  16. *Fixed* above: `agent-gateway` resolves no Bouncy Castle; the surefire/failsafe counts below were re-read from `target/*-reports/TEST-*.xml` (the original figures for this task match those reports; the swap the review noted, `cp-api` 160 / `mesh-events` 50, is in the T2.1a Results sentence, not here: the reports say `cp-api` 50 -> 55 now and `mesh-events` 160); the M-numbering is explained above.
- **Gate (fix round).** `docker-lock.sh agw2b-fix mvn -o -f backend/pom.xml -pl cp-core,dp-api,agent-gateway,launcher,arch-tests -am verify -Dmaven.test.failure.ignore=true`, 1 h 07 min, all ten modules built, **no failing test**. Surefire: cp-api 55, dp-api 14, mesh-events 160, cp-core 621, dp-core 1477 (1 skipped), agent-gateway 33, console-gateway 982, launcher 150, arch-tests 151. Failsafe: cp-core 505, dp-core 1466 (12 skipped), launcher 436 (44 skipped). `MeshResilienceRehearsalIT` passed in this run (7/7), so the 5x-and-5x comparison against `1d9e0653` was not triggered; the flake is also recorded in plan 008 Results. `check-licences.py` OK (192 / 70), `check-frozen-migrations.py` PASS, `test-check-frozen-migrations.sh` all cases, `git diff --check` clean. Not re-run live after the last two edits (the `atexit` line in the scenario and the relative state-path wording in `sm-local.sh`'s printed messages): syntax-checked and `test-sm-local.sh` ok only.
- **Lease nonce keyed (T2.1b review follow-up, 2026-10-10).** `ControlPlaneAgentGatewayService` no longer hashes public inputs: the nonce is `HMAC-SHA256` under a secret-key entry of the operator's PKCS12 CA keystore (alias `sm.control.agent-gateway.ca.lease-nonce-key-alias`, default `lease-nonce-hmac`, same password as the CA key), over a domain-tagged, length-prefixed encoding of (request id, agent id, issued thumbprint, consumed nonce; an absent nonce is length -1, distinct from empty). It stays deterministic (identical inputs, identical grant: a lost reply replays). The JDK's PKCS12 `KeyStore` was measured, by test, to read a secret-key entry here, so no other store was chosen. A missing, short (< 32 bytes) or unreadable entry makes `issueLease` throw `IllegalStateException` (the launcher maps it to `INTERNAL_UNAVAILABLE`), and with `ca.required=true` startup fails. Key bytes, the password and the cause are in no `toString` or exception message. **The control plane never checks `consumedNonce`; it only mixes it in. The data plane's constant-time digest compare is the only single-use check.** T5.2's runbook creates the key entry (see T5.2). Tests: `Pkcs12LeaseNonceSignerTest` 9 cases; mutations: the MAC keyed with zero bytes goes red (`aDifferentKeyGivesADifferentNonce`), and encoding without length prefixes goes red (`movingBytesAcrossAFieldBoundaryChangesTheNonce`, after the first version of that test survived the mutation and was strengthened).

### T2.3 — Results (2026-10-10, `feat/agent-gateway/w2-wire-additions`, Sonnet 5.5; reviewed by Opus; recorded by the integrator, since the plan is a backend-PR path)

- **Built (`258f8579`, agent PR):** health, start time, the write precondition, discovery facts and pushed declarations on the `contracts/agent/v1` wire, with the proto numbers taken from `proto-amendments-015.md` and pinned by `TestT23WireNumbers`. The agent runs the declared probe on host loopback (Docker health is a hint only); `StartedAt` is raw on the ack and in the report; `REMATERIALIZE` checks its precondition and refuses `TARGET_MOVED`; discovery reports only `com.solace.sm.*` labels and bind mounts; declarations replace a binding's set atomically. `OR-129` spool: `0600` in `0700`, purged on every terminal path, swept at start, resumes without a `CommandPull`. Review notes S2 (no digest in `LogValue`) and S4 (the ack detail is a bounded template) are fixed. `AG-D26`/`AG-D27`/`AG-D29` carry dated clarifications.
- **Not red-first:** the tests were written after the code; the red evidence is a 12-row mutation matrix.
- **Review (3 MAJOR, 10 MINOR, 2 NIT), fixed in `681e7b6f`:** the baseline superset check now fails closed (no skip prints "ok"; an unresolvable ref fails unless explicitly opted out; self-tested); an operation whose lease ceiling is 0 is refused before dispatch; the start-up sweep treats a spooled record below its binding's persisted APPLIED generation as dead, and `startNext` refuses `STALE_GENERATION`; the probe port is tied to the inspected container's published ports with no proxy; the `AG-D27` item numbering is restored (clarification appended as item 6). 14 new mutations plus the original 12, each red then reverted. Docker E2E (`-tags docker`, under the docker lock): exit 0.
- **Agent-PR packaging (`98595e69`, T2.4 review 3 and 9):** `go-agent/NOTICE` generated from `go list -deps` (32 modules, 7 NOTICE files) with a failing `--check`; `THIRD-PARTY-LICENCES.md` carries 19 distinct texts plus the Go standard library BSD-3 text; builder and distroless runtime pinned by digest; notices in `/usr/share/doc/sm-agent/` (0644 in 0755, measured in a built image); the distroless image carries the Debian copyright files (T2.4 owed item (d) closed). Still owed: the source-offer line (T5.2).
- **Carried to T3.1:** the resumed-spool versus `SUPERSEDED` note above.

### W2 integration (2026-10-10)

- **Order on `integration/agent-gateway`:** fast-forward to `656d01c5` (T2.1a and its amendment keep their hashes, so both lanes built on it applied cleanly); T2.4 (`0875e3dc`, `e22d96a3`); T2.1b with V030 first (`daacb3e7`), then the original commit and its review fix squashed into one so every commit compiles (`037fa658`), then its docs, kit case, Results and replay-times follow-up; T2.2 (`4a97de19`, `404ddabf`, `c3ffa032` — the keyed lease nonce); merge of `integration/go-agent` (T2.3 `a7101675`, `4a8339a4`; agent packaging `3a6e2938`). The two backend lanes overlap only in this plan file; every conflict was a Results or task-note block, resolved by keeping both. Lane A's tree equals its branch head outside `runtime/` and this plan.
- **Owner rulings recorded during W2:** `OR-136` (token lifetime 24 h / 30 d configurable → T3.5; the stricter re-issue rule supersedes `AGW-D19` item 3 in part; reconnect through the control plane accepted for v1; console behind a TLS reverse proxy, compose trusts `SM_TRUSTED_PROXY`), `OR-137` (L3 `l3-edit` permission), `OR-138` (database role passwords as generated secret files; side lane `feat/agent-gateway/w3-db-role-secrets`).
- **Full gate on `6e87ecbc`** (`mvn -o clean verify` under the docker lock; the later commits touch only docs and `runtime/`): BUILD SUCCESS. Surefire: cp-api 55, dp-api 14, mesh-events 160, cp-core 632, dp-core 1481 (1 skipped), agent-gateway 33, console-gateway 982, launcher 150, arch-tests 160. Failsafe: cp-core 505, dp-core 1566 (12 skipped), launcher 436 (44 skipped). **One failure:** `JcsmpSampleSubscriberIT.aBrokerOutageIsAGapAndReconnectResubscribes` ("an event published after the broker returned is delivered"), the broker-reconnect timing case plan 008's Results already record under load; W2 changed no mesh code (the diff of `dp/meshbridge` and `mesh-events` is empty); rerun alone three times under the lock: 8/8 each. Classified as the known load flake, on the reruns and the absence of a mesh change, not on a root cause.
- **Other gates:** `run-checks.sh --strict` ALL PASSED; `check-frozen-migrations` (V030 unanchored, as expected before publication) and its self-test (a–m); `check-licences` and both licence self-tests; `check-protoc-executables` and its self-test; `check-postman` (73 operations); the Go gate on `integration/go-agent` (vet, tests, `-tags integration`, `check-licences.sh`; `go-agent/` and `contracts/agent/v1` are byte-identical on both branches); the §6.4 split (the agent PR touches only its listed paths); `git diff --check`.

### T3.1 — Results (2026-10-11, `feat/agent-gateway/w3-runtime-adapter`, Sonnet 5.5; review pending)

The agent adapter, per-placement routing, command identity on the intent key, render once, the keyed-hash bridge, fail-fast, the agent concurrency cap and the contract kit. Backend-PR paths only. No `dp-api/agw` signature changed (HF18); no migration, no dependency coordinate.

- **Step 0 (measured).** `ApplyClaimRepository.CLAIM_SELECT` picks one `REQUESTED` row per tenant `FOR NO KEY UPDATE SKIP LOCKED`. The transport filter joins there as a second variant (`CLAIM_SELECT_WITHOUT_AGENTS`, `NOT EXISTS`-style over `placement` x `target_connection.transport = 'agent'`, instance-wide requests counting as agent when any live placement of the instance is on an agent target), chosen by `claimNext(tenant, agentAllowed)`; `CLAIM_SELECT` and `claimNextAsStarted` are untouched. `ApplyWorker` counts agent claims on its single poll thread and releases on every exit path; `ApplyWorkerProperties#agentCap` boot-validates (>= 1, < pool, pool >= 2 when `sm.agent.gateway.enabled`).
- **Built.** `RuntimeOperationContext(applyId, intentId, intentKey, attemptId, step)` + additive `RuntimeAdapter` overloads + five `ApplyExecutor` call sites (HF1); `PlacementTransports`, `RoutingRuntimeAdapter` (+ `delegateFor`), `AgentRuntimeAdapter`, `AgentPayloadHandoff`, `AgentHashBridge`, `HandoffAgentCommandPayloadSource`, `AgentPlacementCatalog`, `AgentConfigGenerations`, `AgentRuntimeProperties/Configuration`, `JdbcAgentPlacementCatalog`; one `RuntimeAdapter` bean (the router, SSH and agent adapters built inside it) and one `ApplyExecutor` (HF16); `HealthPoller` and `CollectionPlanBuilder` skip agent targets through `PlacementTransports` (HF19); `JdbcAgentCommandRepository`: `pendingFor` refuses what the lease cannot authorize before any notice, a pull refused `agent.payload.render.changed` ends the command `REFUSED`, a late ack of a `SUPERSEDED` command is recorded `late.<outcome>`.
- **Decisions.** (1) **RECONCILE, not hold**, for the T2.3 integrator note; the window is stated in the `AGW-D4` clarification (appended to `decisions.md`): from reconnect until `CommandReceived` reaches the DP, a command the agent had journalled `RECEIVED` before it was superseded may run once; recorded, never silent, never after a newer generation. (2) The hand-off is keyed `(tenant, intent key, operation)` and staged BEFORE enqueue (the plan said command id; that cannot precede the enqueue), clarified under `AGW-D26`. (3) A REMATERIALIZE cannot be rebuilt after a hand-off miss (its expectation is not stored) and is refused. (4) Status maps foreign content to a keyed hash of its own (distinct from "no file"), not null. (5) `AgentAck` carries no digest, so the proof of an APPLY is the later report. (6) HF4: no edit to `DataPlaneConfiguration` or `AgentGatewayPortsConfiguration`; one added bean `agentPlacementCatalog` in `AgentGatewayPersistenceConfiguration`, just above `agentAuditPort`.
- **Test renames.** `CollectionPlanBuilderTest#excludesAgentAcquisition` became `CollectionPlanBuilderIT#excludesAgentPlacements` (skipped by target transport; no AGENT acquisition mode exists yet); `ProductionApplyConfigurationTest#exactlyOneApplyExecutor` is a bean-method reflection check.
- **Red/green.** NOT red-first: tests were written with the code. Mutation matrix, each applied, run red, reverted: unknown transport defaulted to SSH (`anUnknownTransportIsRefusedNotDefaulted`); attempt id in the command key (5 adapter tests); re-render on every pull (kit); fail-fast dropped (`withoutALiveSession…`); plain digest as the materialize hash (3 kit cases); foreign content as absent (2); bridge renders per poll (`aFileMatchingNoCandidateDoesNotRenderOnEveryPoll`); supersede dropped (`aDifferentIntentKey…`); hand-off staging dropped (kit). The cap's red is `AgentStarvationIT#withoutTheCapTheSameLoadStarvesDpRemote…`. Not mutated: the schema IT (bytes persisted) and the poller (covered by `skipsAgentPlacements` only).
- **Gate** (`-pl dp-api,dp-core,agent-gateway,launcher,arch-tests -am verify -Dmaven.test.failure.ignore=true`, docker lock `agw3a`, 1 h 04 min, BUILD SUCCESS). Surefire: cp-api 55, dp-api 14, mesh-events 160, cp-core 632, dp-core 1568 (3 skipped), agent-gateway 33, console-gateway 982, launcher 150, arch-tests 163. Failsafe: cp-core 505, dp-core 1586 (12 skipped, 1 failure), launcher 436 (44 skipped, 1 failure). `AgentCommandCrashRecoveryIT` 4/4, `JdbcAgentCommandPortContractIT` 27/27 (the new kit cases green), `AgentStarvationIT` 2, `AgentClaimCapIT` 4, `JdbcAgentPlacementCatalogIT` 3, `AgentCommandRetentionEdgeIT` 1.
- **The two failures and their fixes.** (a) `HealthPollerIT.skipsAgentPlacements:617`: the assertion `direct.askedAbout("vm-1.corp.example").isEmpty()` was genuinely wrong, not the skip: the shared database holds other ITs' direct-http placements on that host name and the poller visits every tenant. Removed; the remaining assertions (nothing probes the agent placement, no health row, the dp-remote control still polled) stand. (b) `ApplyWorkerAbsenceIT`: the production adapter bean is now the router; the test asserts that, then that its `ssh-docker` delegate is `SshDockerRuntimeAdapter` and its `agent` delegate is `AgentRuntimeAdapter` (via a new `RoutingRuntimeAdapter#delegateFor`), keeping the intent: no fake and no demo adapter in the production boot. Focused re-run on the fix-round tree: green (counts in the fix-round subsection below).
- **Known gaps / owed.** A QUEUED or OFFERED command past its deadline is still returned by `enqueue` as live; startup recovery is not counted against the agent cap; T3.3 must satisfy the status mapping in the plan note under T3.3; T3.2's wire mapping of `RENDER_CHANGED` and the notice digest rules are in its note. **Overlap for lane D:** I added `renewLease` to the dp-api test-jar's `AgentKitWorld` and implemented it in `AgentJdbcWorld`; any lane editing either file will conflict on that one method.
- **Unrelated flake seen once:** `console-gateway` `InstanceControllerTest.aMetricsPortCollision…` (the string "28081" occurring in random ids); green 3/3 on re-run and in the gate.

#### T3.1 fix round (2026-10-11, Opus review `w3a-review.md`: 0 BLOCKER, 4 MAJOR, 8 MINOR, 10 NIT; commits `835ee27a` and this one)

- **Verification (focused, docker lock `agw3a`, `-pl dp-core,launcher,arch-tests -am verify`, BUILD SUCCESS).** dp-core failsafe 157 run, 0 failures: `HealthPollerIT` 20, `AgentClaimCapIT` 4, `AgentCommandRetentionEdgeIT` 1, `AgentCommandCrashRecoveryIT` 5, `AgentStarvationIT` 5, `JdbcAgentPlacementCatalogIT` 3, `JdbcAgentCommandPortContractIT` 29, `JdbcAgentLeasePortContractIT` 18, `JdbcAgentSessionPortContractIT` 12, `JdbcAgentSessionRepositoryIT` 3, `AgentSchemaIT` 30, `CollectionPlanBuilderIT` 14, `ApplyClaimRepositoryIT` 9, `ApplyClaimLockModeIT` 2, `ReconcilerWiringIT` 2. launcher failsafe 8, 0 failures: `ApplyWorkerAbsenceIT` 1, `ApplyWorkerBoundedClaimIT` 1, `ApplyWorkerLifecycleIT` 4, `ApplyWorkerPoisonAndDrainIT` 2. Unit: the dp-core, dp-api, agent-gateway, console-gateway, launcher and arch-tests surefire suites pass on a copy of the tree (the six errors seen there were the copy lacking `docs/`). Mutation (finding 7): with the `isAgentTarget` skip deleted from `HealthPoller`, `HealthPollerIT.skipsAgentPlacements:615` goes red ("without the skip an UNREACHABLE row is recorded"); run in a full copy of the tree, the source reverted by discarding the copy.
- **MAJOR 1 (never shown green):** fixed, the run above. **2 (boot recovery blocks on a dead session):** fixed. The `LIVE` predicate gains `last_seen_at` within `sm.dp.agent.session-live-window` (default 2 min); IT `resumingAtBootDoesNotBlockOnASessionThatWentQuiet` (no agent answers; resume returns in under 5 s with `agent.session.absent`; the command row stays DELIVERED). A restart faster than the window still waits for the step's ceiling (stated in the T3.2 note). **3 (reconciliations bypass the cap):** fixed. A due reconciliation counts in `agentInFlight` and is skipped at the cap; `AgentStarvationIT` has a real `IndeterminateReconciler`, with an uncapped control. **4 (`enqueue` returns a dead command):** fixed, option (a): the live command's deadline is refreshed (same id and generation); kit case `enqueueRefreshesTheDeadlineOfTheLiveCommandItReturns`.
- **5 (AGW-D26 item 2):** fixed by conforming. The hand-off is bound to the command id once `enqueue` returns, kept until ack, refusal, supersession or deadline, so a pull after a timeout does not re-render; the `decisions.md` clarification now says only that, dated 2026-10-11. **6 (`pull` write not fenced):** fixed (one transaction with the standing check and the epoch join). **7:** fixed, the vacuous SSH assertion replaced by "no health row and no `instance.health` event", mutation run above. **9 (HF18 `AgentKitWorld.renewLease`):** an HF18 test-jar addition the integrator accepted; it is now a `default` method that throws `UnsupportedOperationException` naming the world, so the lane C and D worlds still compile; no existing signature changed. **10 (pool 1, gateway off):** fixed, the cap is the pool size when the gateway is off. **12 (late-ack exactly once):** fixed, kit case `aReplayedLateAckLeavesTheFirstRecordStanding`.
- **8 (`STOP_NOOP_REPORT` relaxation): declared, kept.** The agent has no "already stopped" signal: a STOP is a command and the agent answers APPLIED whether or not the container was running, so a second stop of a stopped replica reports `issued=true`. Answering `issued=false` from `status` would skip a real stop on a possibly stale report. The deviation is named in the fixture and the kit.
- **11 (bridge): partly fixed.** A failed render is retried after 30 s (never per poll), and the production candidates now include the newest generation whose MATERIALIZE for the placement succeeded (`ProductionAgentConfigGenerations`). **Owed:** `ProductionAgentConfigGenerations` is not tested against a database.
- **13 (javadoc overclaim), 14 (`Staged.toString`), 15 (transports required, fail-open fallback removed), 17 (prose and dates):** fixed. **16 (`delegateFor` public): no change**, accepted for the launcher boot IT, which asserts both delegates; it is the only way to prove the production boot holds no fake. **18 (N+1 `isAgentTarget`): skipped**, not cheap; one indexed read per placement per round. **19 (fail fast within a poll):** fixed, `anOfflineAgentsRequestsLandWithinAFewPollsWithTheSessionAbsentCode`. **20 (REMATERIALIZE unreachable in production for agent placements): no change, stated:** `assessRollback` is always Unknowable, so `ApplyExecutor` never classifies agent content as PRIOR and any non-intended file is FOREIGN; `rematerializeIfCurrent` and `AgentHashBridge#plainFor` are exercised by tests only, so do not count them as live coverage. **21, 22:** plan-text notes added under T3.2 and T3.3.

### T3.2 — Results (2026-10-11, `feat/agent-gateway/w3-gateway-service`, Sonnet 5.5; lane B; not yet reviewed)

- **Built (`026cd8aa` and the follow-up commit).**
  - `AgentGatewayService`:
    - `SessionOpen` maps `LEASE_RENEW_REQUIRED` from `AgentLeasePort.current` (any lease row) to the wire `Error(LEASE_RENEW_REQUIRED)` and waits for `LeaseRenew`; a first-ever session calls `issue`; `ISSUE_NOT_ALLOWED` (the race between `current` and `issue`) is treated the same way and is never forwarded. A stream awaiting its renewal is now closed by the open timeout (it was exempt), because every reconnect now waits for one.
    - `DiscoveryReport` goes to `AgentDiscoveryPort`; the per-item results come back as `DiscoveryAck` (labels outside `com.solace.sm.` are dropped before the port).
    - `TelemetryDeclarations` are sent after `SessionAccept` to a session that announced `CAPABILITY_DECLARATIONS`, and again whenever the lease is renewed or re-issued and the set differs (a removed placement is cleared with an empty set). There is no change signal from the data plane and **no task adds one** (T3.4 does not); a declaration edited between lease events reaches the agent at the next renewal (up to about half a lease). See the note under T4.1.
    - The agent's ack `detail.message` is cut to 200 printable ASCII characters and never logged or echoed (T1.2 note 7); `containerStartedAt` is taken from the ack and from the report item (T2.1b); `ReportItem.health` is mapped; (corrected in the fix round: the effective hash is `sha256:<64 lowercase hex>` or null, see below); a notice or offer without a digest no longer throws.
    - `AgentGatewayInterceptor` passes the bare IP literal of the transport peer (`InetSocketAddress.getAddress().getHostAddress()`, zone stripped), `AgentRequestOrigin` gets `unknown` for anything else instead of failing the call. Revocation found while renewing an accepted session ends every stream (test added).
  - `AgentGatewayConfiguration` is `@ConditionalOnProperty(sm.agent.gateway.enabled=true)`, so a dp-remote-only install composes no gateway bean. The two ports later tasks supply (`ActualStatePort` T3.3, `AgentTelemetryPort` T3.4) fall back to `INTERNAL_UNAVAILABLE` until their beans exist, with a boot WARN; a real bean always wins.
  - Launcher (HF17): the gateway, the three control-plane adapters and `AgentGatewayBootGuard` are imported; `application.properties` maps `SM_AGENT_GATEWAY_{ENABLED,PORT,READINESS_PORT,CERT,KEY,TRUST,ENDPOINT,DRAIN_WINDOW}`, `SM_AGENT_CA_{KEYSTORE,KEY_ALIAS,KEYSTORE_PASSWORD,LEAF_TTL}`, `SM_SHUTDOWN_TIMEOUT`; `sm.control.agent-gateway.ca.required` follows the listener switch and the guard refuses an overlay that sets it false, a blank connect endpoint, or (via `AgentGatewayServer`) missing TLS material. No `SM_DP_AGENT_RUNTIME_ENABLED`. `launcher/pom.xml` gains the `agent-gateway` dependency (a reactor module, no new coordinate).
  - Deviation from the plan text: the switch is `sm.agent.gateway.enabled` (compose already sets `SM_AGENT_GATEWAY_ENABLED`), not `sm.agw.listen`.
- **Red first.** The new service cases were written before the code and run: 9 of 32 failed on the unchanged service (that run's output was not kept to a log file) (reconnect with a lease row closed the stream; no `DiscoveryAck`; unbounded detail; prefixed hash; non-IP origin threw; ...). The other tests (interceptor, structural, configuration, launcher ITs) were written alongside their code; the structural, origin and revocation tests have the mutation matrix below.
- **Mutation matrix (each red, then reverted to the byte-identical file, `diff` clean):** keep a `CommandOffer` in a service field: `AgentGatewayRetainsNoPayloadTest` red (`AgentGatewayService.lastOffer : CommandOffer`); take the origin address from `SessionOpen.agent_version`: 2 origin cases red; drop the telemetry abort on revocation: 2 cases red (the sweep case and the renewal case).
- **G-A0 (local, no E2E host), `AgentGatewayHandshakeIT`, one JVM, the production `SolutionManagerApplication`:** the broker (`solace-pubsub-enterprise:10.25.0.177`, started by the test) reported `1 connected client(s) for 'sm-internal'` (the launcher's JCSMP session) and the composed `AgentGatewayServer` was running; a Java gRPC client in `launcher/src/test` presenting a certificate issued by the test keystore's CA completed the mTLS handshake and the `Connect` RPC on `localhost:<port>`; the answer was `Error(ERROR_CODE_UNAUTHENTICATED_IDENTITY)` for an identity no enrolment issued (the same answer a client with no certificate gets, so this does not by itself prove the thumbprint was read and resolved; a successful `Connect` needs an enrolled identity and belongs to T4.4/G-A1). Negative cases: a client certificate from another CA gets `UNAVAILABLE` (no handshake); a client with no certificate reaches the listener and is refused unauthenticated.
- **Launcher fail-closed (`AgentGatewayCompositionIT`, real PostgreSQL, production boot):** a dp-remote-only boot composes no gateway bean; a correct listener boots with the three adapters; no CA keystore, a keystore without the lease-nonce key, a blank connect endpoint, `ca.required=false` under an enabled listener, and a missing TLS certificate each fail the context with the named reason. `SolutionManagerConfigurationTest` now pins the five imports.
- **Owed.** (a) `AgentCommandPayloadSource` (T3.1, lane A) is not in this base: `AgentGatewayCompositionIT.theCommandPayloadSourceExistsWhenTheListenerIsComposed` is `@Disabled` with that reason; enable it on integration. (b) `ActualStatePort` and `AgentTelemetryPort` fall back to `INTERNAL_UNAVAILABLE` until T3.3/T3.4. (c) Declarations "on change" is lease-event driven; no task owns a change signal (note under T4.1). (d) The T2.3 held-or-reconciled decision for a superseded `RECEIVED` command belongs to T3.1, not here. (e) No migration was added.

#### T3.2 fix round (Opus review, 2026-10-11; one new commit on `fa014b30`)

- **Gate numbers of lane B's first full gate** (`gate2.log`, `-pl agent-gateway,launcher,arch-tests -am verify -Dmaven.test.failure.ignore=true`, finished 01:34; BUILD SUCCESS only because failures were ignored). Surefire: cp-api 55, dp-api 14, mesh-events 160, cp-core 632, dp-core 1481 (1 skipped), agent-gateway 55, console-gateway 982, launcher 150, arch-tests 160. Failsafe: cp-core 505, dp-core 1566 (12 skipped), launcher 448 (45 skipped, **1 error**: `AgentGatewayHandshakeIT`, class level, "The ApplicationContext ... is not active", from closing the context by hand). The first attempt of that gate hung in a cp-core IT (a Postgres authentication read, Docker wedge) and was killed.
- **Fork kill.** `focus2.log` (the run before the by-hand close) shows "Surefire is going to kill self fork JVM ... 30 seconds after System.exit(0)": the cached context's JCSMP session retried a stopped broker. The `@DirtiesContext` follow-up (`fa014b30`) was not run, and its order (broker stopped in `@AfterAll`, before Spring's afterAll) was wrong; the fix round removes the broker stop (Ryuk reaps it) so the context closes first.
- **Fixed:** (1) effective hash is `sha256:<64 lowercase hex>` or null (HF18 form; T3.3 keeps it in memory only, so the prefix never broke OR-32; the earlier Results line was wrong). (2) A refused renewal answers `RE_ENROLL_REQUIRED` only for `LEASE_RENEW_REFUSED`, `ISSUE_NOT_ALLOWED` and `RE_ENROLL_REQUIRED` (the issuer's folded certificate-class and behind-DP refusals), before the accept and in session (never forwarded raw); `IDENTITY_REVOKED` and `STALE_EPOCH` keep their own codes; everything else is a coded `Error` and the stream closes with the agent's lease intact. Checked against `go-agent/internal/msg/classify.go` and `authority.go` (Halt: `ISSUE_NOT_ALLOWED`, `RE_ENROLL_REQUIRED`, `IDENTITY_REVOKED`; Retry: `INTERNAL_UNAVAILABLE`, `IN_PROGRESS`, `DRAINING`; Act: `SESSION_SUPERSEDED`, `CLOCK_SUSPECT`, `LEASE_RENEW_*`). Mutation (every refusal re-enrols): `aControlPlaneOutageDuringTheRenewalBeforeAcceptIsARetryable...` red. (3) REMATERIALIZE offers carry `expected_current_sha256` ("" kept). (4) Telemetry dispositions: ACK acks; DO_NOT_ACK sends `TelemetryReject` with `retry_after` and returns the credit; REJECTED_COUNTED is acknowledged with the watermark (the agent trims) and audited. (10) `AGENT_PAYLOAD_RENDER_CHANGED` maps to `RENDER_CHANGED`; a test pins which codes have no wire code. (11) comment corrected; an awaiting-renewal timeout is audited (`agent-renewal-timeout`); not built: routing a grant-less agent to `issue()` (it cannot recover a lost reply, see the T5.2 note). (12) empty unit defaults to `"1"`. (13) a stream accepted during a drain is told `DRAINING` and closed. (14) the structural walk forbids offer types and builders, `GatewayToAgent`, `Message`/`MessageOrBuilder` and `Object` fields, with planted cases. (15) timeout test asserts open-then-closed; telemetry origin tested. (16) readiness-port default 9444; the guard compares the switch to the string `true`.
- **Deviation record (MINOR 7).** `launcher/pom.xml` is hot file HF13, which has no W3 owner: this lane added one reactor-module dependency (`agent-gateway`), no coordinate, necessarily; accepted by the integrator.
- **Not changed (MINOR 9).** The two lambda port fallbacks stay; the integrator removes them and takes both ports as required parameters once lanes C and D's real beans merge, and adds to `AgentGatewayCompositionIT` that `reports()` and `telemetry()` are those classes.
- **Not done (MINOR 8).** The G-A0 RPC still returns `UNAUTHENTICATED_IDENTITY`; making it succeed needs an enrolment token issued through the control-plane service (principal, scope), which is not cheap; the claim is reworded instead.
- **Fresh focused run over `99a3a8f3`** (`focus4.log`, lock label agw3b, `-pl agent-gateway,launcher,arch-tests -am verify` with `-Dtest='com.solace.sm.agw.*Test,SolutionManagerConfigurationTest,com.solace.sm.arch.*Test' -Dit.test=AgentGatewayCompositionIT,AgentGatewayHandshakeIT`, BUILD SUCCESS, no failure or error): agent-gateway surefire 67; launcher surefire `SolutionManagerConfigurationTest` 5; failsafe `AgentGatewayCompositionIT` 8 (1 skipped: the payload-source test, owed to integration) and `AgentGatewayHandshakeIT` 3, **no error and no "kill self fork JVM" line** (0 matches), 29 s; arch-tests 160. The full gate was not repeated.

### OR-138 (W3 side lane) — Results (2026-10-10, `feat/agent-gateway/w3-db-role-secrets`, Sonnet 5.5)

- **Built:** `runtime/scripts/init-secrets.sh` (generate / rotate), `runtime/bootstrap/phase0.sh` (passwords from files; SCRAM verifier computed client-side; `rotate` mode), compose secrets for `db` (`POSTGRES_PASSWORD_FILE`), `migrate` and `launcher`; `sm-migrate`/`sm-launcher` read `SM_*_PASSWORD_FILE` with the CA password's checks (regular, readable, non-empty; both forms set refused; exit 64). No `SM_*_PASSWORD` value remains in `.env.example` or compose `environment:`.
- **Where generation runs:** a host script, not a compose job. Compose needs the secret files to exist when it creates a service, and `db` itself consumes the superuser file, so a container could not produce them first without a second bind-mounted path and a chicken-and-egg order.
- **Log safety:** the verifier is computed in the bootstrap container with perl's core `Digest::SHA` (already in the pinned image; nothing added); the statement is also run under `SET log_statement='none'`.
- **Fix round (Opus review: 3 MAJOR, 6 MINOR, 5 NIT; the SCRAM verifier was confirmed byte-identical to a Python reference).** `rotate` and the initial passwords are now **one psql session in one transaction** (all named roles or none); `--chown` became `--group` (role files only: group = the given gid, mode 0640, the directory and `db_superuser_password` never touched; `rotate` copies the live file's owner, group and mode); `SM_SECRETS_DIR` is also read from `runtime/.env` (read, never sourced; relative = against `runtime/`); the verifier travels on stdin and is shape-checked; more logging SETs (`log_min_duration_statement`, `log_min_duration_sample`, `log_statement_sample_rate`, `log_transaction_sample_rate`, and `pg_stat_statements.track_utility=off` when that library is loaded: each exists in PG14 and is superuser-settable per the logging and pg_stat_statements docs); `bin/sm-secrets.sh` is the shared reader (empty-after-read refused, which also fixes the CA password path) with a new `bin/sm-tool` wrapper; a runtime-role rotation prints a maintenance warning and never restarts anything.
- **Measured (postgres:14.24, `log_statement=all`, throwaway project):**
  - Fresh volume: bootstrap, then all four roles log in with their file's password; a wrong password is refused; re-running `init-secrets.sh` leaves identical checksums.
  - `rotate cp_runtime cp_runtime` (deduplicated): the new password logs in, the old is refused, `cp_migrator` untouched, the file's owner/group/mode unchanged (`-rw-r----- 502 20` before and after; gid 20 stands in for 10001, which an unprivileged user cannot chgrp to).
  - **Injected failure on the 2nd ALTER** (`rotate all`, a nonexistent role added after `sm_cp_runtime`'s ALTER): live files unchanged, **all four old passwords still log in, all four candidates are refused**, no lock directory left; a second `rotate all` then succeeded, all four new passwords work and all four old ones are refused.
  - A concurrent rotation is refused by the lock (rc=1).
  - Server log after bootstrap and all rotations: 0 hits for each of the five current, the superseded and the candidate passwords; 0 `ALTER ROLE ... SCRAM` lines; a decoy `SELECT` is logged once, proving `log_statement=all` was live. With `shared_preload_libraries=pg_stat_statements` a rotation succeeds and logs nothing.
  - `docker inspect` of `db`: 0 hits for the superuser password. `.env` set `SM_SECRETS_DIR=./mysec`: the script (run from `/`) and `docker compose config` agree on the path.
  - Shell level with a stub `java`: `sm-launcher`, `sm-migrate` and `sm-tool` deliver the right values to the right process, and an empty file, a newline-only file, a directory, a missing file and value-plus-file all exit 64.
- **Not measured:** the `launcher` and `migrate` containers were never started (no launcher image was built; `build-image.sh` runs Maven), so **uid 10001 reading the bind-mounted files is unmeasured on Linux** (on this Docker Desktop host a container user 10001 read a 0640 file mapped as `10001:10001`, which says nothing about a Linux bind mount) and `docker inspect` of those containers was not taken; no shellcheck (not installed; `sh -n` only); SASLprep is not implemented (generated passwords are ASCII); a failure injected at the COMMIT itself (the ambiguous-outcome case) was not produced, only reasoned: the recovery is the same `rotate` re-run.

### T3.4 — Results (2026-10-11, `feat/agent-gateway/w3-telemetry`, Sonnet 5.5; review pending)

- **Built (steps 1 to 4).** `dp-core/.../dp/agent/AgentTelemetryBridge` is the one `AgentTelemetryPort` bean. `AgentTelemetryDeclarationsService` replaces T2.1b's `AgentTelemetryDeclarationsStub` (class deleted, its stub case removed from `AgentContractedStubsTest`). `AgentTelemetryCounts` holds the in-process counts (dp-core has no metrics library). `JdbcAgentTelemetryWatermarkRepository` gains `admit`, `leased` and `acknowledge` (it stays a persistence helper and is not a port). `CollectorReason.CLOCK_SKEW` is added to the enum, because `JdbcMetricsQueryRepository.reason()` does `CollectorReason.valueOf` on every stored reason and a stored `clock_skew` would otherwise throw on read.
- **`sm_metrics` V004 (new migration; the integrator anchors it).** `V004__ingest_counter_clock_skew.sql` drops and re-adds `ingest_counter_reason` with `clock_skew`, with its `CHECKSUMS.sha256` line `f5abc335b54c7d5b4cfd12ef626788e05ce7ef2a31e4309f241bdd7add7aa3e5`. Pinned counts updated in the same commit: `SmMetricsSchemaIT` (reason list), `SmMetricsMigratorIT` (history `001..004`, `migrationsExecuted` 4, `migrated 4 `), `IngestApiShapeTest` (eleven reasons), `backend/README.md`.
- **Identity (`I5`).** Organization and agent come from `AgentAuthority.underSession` (share-locked session and identity). The stream id is the agent's binding, which the agent wire already sets to the placement id; it is parsed as a UUID and must be in the ACTIVE lease's `placementIds`, else `BINDING_NOT_IN_LEASE`. Labels are a closed list (`binding_id`, `replica_id`, `incarnation_id`, each a UUID, `binding_id` equal to the stream's placement): another key is `UNDECLARED_LABEL`, a non-UUID value `LABEL_INVALID`. A label is compared, never used to select anything. The sample type the agent sends is not forwarded (`IngestSample` has no type, by design).
- **Ack on commit only.** `Committed` -> `acknowledge` (watermark raised in its own transaction that re-checks the session) -> `ACK`. `RetryAfter` and `Pending` -> `DO_NOT_ACK` carrying the UNMOVED watermark and zero credits (`Pending` also counts `agent.telemetry.pending.unexpected`). `Rejected("placement.unknown")` -> `REJECTED_COUNTED` (`BINDING_NOT_IN_LEASE`), `batch.too-large` -> `PAYLOAD_TOO_LARGE`, any other permanent refusal -> `UNKNOWN`; each advances the watermark (acknowledged-as-rejected, never retried forever). `Rejected("buffer.dropped")` is transient and is `DO_NOT_ACK`. A duplicate `batchSeq` at or below the watermark is acknowledged without forwarding. The watermark advances AFTER the outcome, so a crash between `Committed` and the advance costs one redelivery that the ingest port absorbs as an exact duplicate (D-2).
- **Clock window, per `OR-134` (§10 item 19), not per sample age.** While the session's measured `clock_offset_ms` has `|offset|` greater than 5 minutes, every sample of the batch is refused, counted `clock_skew` per sample in `sm_metrics.ingest_counter`, the batch acknowledged as `REJECTED_COUNTED` (`CLOCK_SUSPECT`). At exactly 5 minutes, and for a never-measured (`NULL`) offset, samples are accepted whatever their age and their timestamps are not rewritten. The plan's Step 1 sentence about a sample "at 5 minutes + 1 second" is superseded by `OR-134`; the boundary test is on the offset.
- **Declarations.** Per placement of the ACTIVE lease that is still live: the version's `http_json` counter and gauge metrics with a port and a path, intersected with the newest restart-observed generation's active list (empty list -> no metrics, as the pull path); interval fixed at `OR-73`'s 5 s; the placement's probe columns as `HealthProbeSpec`, or `null`; `degradeWhenUnavailable` from the source's `onUnavailable`; a package `queryParams` map is carried as a sorted, form-encoded query string on `path` (the declaration has no field for it). Session fenced and lease required first.
- **`acquisition_mode = 'agent'`.** The ingest path reads the mode as text from `JdbcIngestContextRepository` and never through `AcquisitionMode`, and `AgentTelemetryBridgeIT` commits a batch on an instance set to `'agent'`. `AcquisitionMode` (dp-api, HF-unlisted) has only `DIRECT_HTTP` and `VIA_SSH`: `JdbcInstanceStateRepository` maps with `AcquisitionMode.fromWire` and throws on a stored `'agent'`, so every `InstanceRows` read of an agent instance fails. Not changed here (the enum is shared by `console-gateway`'s wire and several exhaustive uses); owed to whichever task first declares an instance with `'agent'` acquisition (T3.5 or T4.1).
- **Evidence.** Not red-first: tests were written after the code. Mutation matrix on `AgentTelemetryBridgeIT` (18 cases, green on the baseline), each mutation applied to the one line, run, and reverted byte-identical: M1 ack on `RetryAfter` -> red (`retryAfterIsNotAckedAndTheWatermarkDoesNotMove`); M2 ack on `Pending` -> red (`pendingIsNotAckedAndIsCountedAsUnexpected`); M3 stream not checked against the lease -> red (2: `aStreamNamingAnotherAgentsPlacementIsRefused...`, `aStreamThatIsNotAPlacementId...`); M4 watermark advanced before the ingest call -> red (3: the failure, `RetryAfter` and `Pending` cases); M5 window `>` to `>=` -> red (`anOffsetOfExactlyFiveMinutes...`); M6 label check removed -> red (`labelsOutsideTheClosedList...`); M7 window on sample age instead of offset -> red (`anOldBufferedSample...`).
- **Gate.** `docker-lock.sh agw3d mvn -o -f backend/pom.xml -pl dp-core,agent-gateway,launcher,arch-tests -am verify -Dmaven.test.failure.ignore=true`, BUILD SUCCESS, all ten modules built. Surefire / failsafe: cp-api 55 / 0, dp-api 14 / 0, mesh-events 160 / 0, cp-core 632 / 505, dp-core 1480 (1 skipped) / 1584 (12 skipped), agent-gateway 33 / 0, console-gateway 982 / 0, launcher 150 / 436 (44 skipped), arch-tests 160 / 0. Failures in that run: `cp-api` `AgentRecordsRedactionTest.anEnrollmentRequestDoesNotPrintTheTokenOrTheCsr` (1; the assertion `not to contain "42"` ran against a printed random correlation id and failed on a UUID that contained "42": a flake in a test this task does not touch); `SmMetricsMigratorIT` (2; pinned `sm_metrics` counts, fixed above and re-run green); `arch-tests` `PlaneBoundaryTest.noProductionClassImplementsTheReportOrTelemetryPortYet` (1; expected, see below).
- **Arch rule change owed to lane A (HF15; not edited here).** In `PlaneBoundaryTest`, drop `com.solace.sm.dp.api.agw.AgentTelemetryPort` from `PORTS_WITH_NO_BEAN_YET` (keep `ActualStatePort` until T3.3), and change the planted fixture `archfixture/PlantedPortBean` to implement `ActualStatePort` instead of `AgentTelemetryPort`, or the planted-violation assertion in the same test fails ("the planted implementation must be seen"). Measured: with exactly those two edits applied locally to this tree, `PlaneBoundaryTest` passes; the edits were reverted. Optional: move `AgentTelemetryDeclarationsPort` out of `CONTRACTED_STUB_AGENT_GATEWAY_PORTS` (it is no longer a stub; `AgentTelemetryBridgeIT` covers it); the union that the inventory test compares is unchanged either way.
- **HF4 edit (after lane A's).** Only `dp-core/.../dp/agent/AgentGatewayPortsConfiguration.java`: additive imports; the `agentTelemetryDeclarationsPort` bean body replaced (parameters now `JdbcAgentTelemetryWatermarkRepository, IngestRows.Placements, CollectionRows, RestartObservedGenerations, PlacementRows`, returns `new AgentTelemetryDeclarationsService(...)`); two new beans appended after it (`AgentTelemetryCounts agentTelemetryCounts()` and `AgentTelemetryPort agentTelemetryPort(...)` returning `AgentTelemetryBridge`). Nothing else in the file, and `AgentGatewayPersistenceConfiguration` is untouched.
- **Owed.** (1) `contracts/openapi/console.yaml` (HF3, lane C): add `clock_skew` to the `CollectorReason` enum and to the capabilities `reasons` enum, since a stored `clock_skew` can now appear in both; not edited here. (2) Gateway service reads `disposition` (note under T3.2). (3) `AcquisitionMode` has no `AGENT` (above). (4) Gap markers name agent sequence numbers and have no instant to chart, so none is forwarded (counted `agent.telemetry.gaps.not.forwarded`); the design's `metrics_gap` rows for agent drops need a rule for turning sequence ranges into times. (5) `AgentTelemetryCounts` is not bound to a meter (dp-core has no metrics dependency and none may be added by this lane). (6) The lease's placement set is read at call time from the ACTIVE lease row; a placement added after the lease was issued is not in it until the lease is re-issued (T3.5/T4.1).

#### T3.4 fix round (2026-10-11, Sonnet 5.5; answers the Opus review `w3d-review.md` of `cbee3f1b`; one new commit on top)

- **HF18 test-jar edit (test sources only, no main signature changed; authorised by the integrator).** `dp-api/src/test/.../agw/kit/AbstractAgentTelemetryPortContractTest.java` is rewritten to the bean's contract (stream = a placement of the active lease, the closed three labels bound to it, coded refusals, no unclassified failure; 7 cases), and `AgentJdbcWorld.telemetry()` (one method plus a lazily built shared `IngestService`; `AgentKitWorld` untouched) now returns the real `AgentTelemetryBridge` over the real ingest port. `JdbcAgentTelemetryWatermarkRepository.ingest` (the helper that acknowledged without forwarding) is deleted. The kit now fails if a class acknowledges without forwarding.
- **1 (MAJOR, `DO_NOT_ACK` stalls the stream): plan text fixed.** The T3.2 note now says "safe from data loss, but not live", withdraws both remedies, and binds T3.2 to `TelemetryReject{INTERNAL_UNAVAILABLE, retry_after}` for `DO_NOT_ACK` (lane B's `99a3a8f3` does it); a T4.4 note asks for a harness case where the real Go agent retries a `RetryAfter`. No gateway code in this lane.
- **2 (MAJOR, `batchSeq` reset): observability fixed, rule left to the owner.** `batchSeq < watermark` is counted apart (`agent.telemetry.sequence.behind.watermark`) from `== watermark` (`agent.telemetry.duplicate.batch`), with a WARN that names only the placement id; acking is unchanged. Section 10 item 22 names the question.
- **3 (MAJOR, kit certifies a test adapter): fixed** (above).
- **4 (MINOR, untested mappings): fixed.** Eight new `AgentTelemetryBridgeIT` cases (26 in all): `buffer.dropped` -> `DO_NOT_ACK`; `batch.too-large` -> `PAYLOAD_TOO_LARGE` and another permanent code -> `UNKNOWN`; `DO_NOT_ACK` with an existing watermark; malformed and all-malformed batches; redelivery after `Committed` without an advance against the real ingest service (rows not doubled); reset counter; upper-case stream; inactive instance. Mutation matrix, each red on the named case and reverted byte-identical: N1 `buffer.dropped` mapping removed, N2 `DO_NOT_ACK` watermark forced to 0, N3 malformed counted before the final outcome, N4 reset not separated (2 cases red), N5 `PAYLOAD_TOO_LARGE` mapping, N6 `replica_id` check removed, N7 raw stream key, N8 lifecycle filter removed. Not red-first (tests after code).
- **5 (MINOR, labels): fixed.** Exactly the three ids are required (`LABEL_INVALID` if missing or not a uuid, `UNDECLARED_LABEL` for another key); `binding_id` must equal the stream's placement (`BINDING_NOT_IN_LEASE`) and `replica_id` too (`UNKNOWN_REPLICA`). Recomputing `incarnation_id` (UUIDv5) was not done: it needs the start observation, which is T3.3's data, and labels select nothing.
- **6 (MINOR, unfenced sample write): residual documented** in the bridge Javadoc and the `AGW-D27` clarification; fencing the ingest port's writer thread is out of this task's file list.
- **7 (MINOR, records): fixed.** Dated `AGW-D27` clarification appended (`I3`; `OR-134`, the whole-batch rule, `NULL` offset, the +-24 h clamp, the residual, the label enforcement). The first Results bullet's "superseded" wording should read: the Step 1 bullet's first sentence predates the ruling that the same bullet records.
- **8 (MINOR, inflated counts): fixed for the bridge's own counts** (`parse_failed`, `clock_skew`, in-memory counts) which are now written only after a final outcome and after the acknowledge. The ingest port's own drop counts remain at-least-once across the crash window between its commit and the acknowledge (stated, not changed: `IngestService` is outside this task).
- **9 (MINOR, declarations vs. the pull path): fixed.** `leased()` keeps only placements whose instance is `active`.
- **10 (MINOR, 30 s gRPC wait): skipped, plan note only.** `IngestService` is not in T3.4's file list; the fix is an `agent-caller-wait` (or reuse `push-caller-wait` for `AGENT`) and depends on the T3.2 `TelemetryReject` mapping above so a shorter wait does not stall the stream.
- **11 (NIT, raw stream key): fixed** (`admit` and `acknowledge` key on `placement.toString()`).
- **12 (NIT, unobserved counts): skipped.** Binding `snapshot()` to Micrometer belongs in the launcher (the integrator checks I8 there); dp-core has no metrics dependency and this lane may add none.
- **Owner questions added to section 10:** items 21 (Q1, clock-skew batches), 22 (Q2, reset), 23 (Q3, gap-marker times). Built behaviour unchanged.
- **Integration items (I1-I6 of the review) are the integrator's;** `arch-tests` and `console.yaml` are not edited.
- **Counts (focused run under lock `agw3d`, BUILD SUCCESS):** `AgentTelemetryBridgeIT` 26/0, `JdbcAgentTelemetryPortContractIT` (the kit) 7/0, `SmMetricsMigratorIT` 4/0, `SmMetricsSchemaIT` 28/0; surefire dp-api 14/0, dp-core 1480/0 (1 skipped). No full gate was re-run.

### T3.3 — Results (2026-10-10, `feat/agent-gateway/w3-reports-and-target`, lane C, Sonnet 5.5)

- **Built.**
  - **Report intake** (`dp-core/.../dp/agent/AgentReportIntake`, the `ActualStatePort` bean). One transaction per batch: `JdbcAgentReportRepository#process` classifies every item and advances the watermark; a refused item is an `agent_event` (`agent.report.refused`, request id `report:<agent>:<item>`); an accepted, trusted item that moved the watermark writes `placement_health` (persisted on change, `OR-39`; the agent's probe facts go through the dp-remote `ProbeResultMapper`; a placement with no declared probe is `NOT_APPLICABLE` whatever the agent says, `OR-101`) and the container start into `placement_start_observation` **forward only**, then `settleLadder` so a start later than the write baseline reaches `restart-observed` (`OR-112`, host clock only).
  - **`AgentObservedState`** (replaces the T2.1b stub; same bean name, `AgentObservedStatePort`): the latest accepted report with liveness read fresh from the database. The plain digest lives **only in this process's memory** (bounded, per organization and placement, remembered after commit); after a restart `latest` answers empty, which the adapter reads as unknown.
  - **Contracted refusals.** `PlacementView` carries the placement's target transport; `AgentPlacements.refuseIfAgent` answers `501 AGENT_OPERATION_UNSUPPORTED` from it in `readLogs`, `getLogLevel`, `setLogLevel`, `acceptRuntimeConfig`, `restoreRuntimeConfig`, `rollbackApply` and `scheduleRollback`, before any data-plane service is called (D-C2: rollback is refused at admission, nothing admitted). `TablePlacementCatalog` also refuses an agent target by name (`agent.operation.unsupported`), so no SSH binding is ever built for it. `ErrorCode.AGENT_OPERATION_UNSUPPORTED` and the contract (`ErrorCode` enum and prose, the six operations' descriptions and `501` responses, `ENDPOINT-INVENTORY` §3, Postman) in the same commit.
  - **The T2.1b notes.** (a) A report after the lease **expired** (or whose epochs or capabilities no longer match the identity) is taken and labelled `UNTRUSTED`; it advances the watermark and is answered, but applies **no** effect (design §8 row 5: no gate satisfied). An agent that never held a lease is still `LEASE_RENEW_REQUIRED`. (b) `replica_ref` must equal the placement's `container_name` and the incarnation must match the `process_ref` grammar, else `REFUSED(REPLICA_REF_INVALID)` with the trust the lease gives and nothing written. (c) `containerStartedAt` is the report's. (d) A malformed report item (an item id outside its grammar, negative sequence or generation, a blank binding; first built as "no binding", which only caught `null`, never the wire's `""`) is `REFUSED(LABEL_INVALID)`, `UNTRUSTED`; the malformed `received` request id, which threw `IllegalArgumentException`, is the same `LABEL_INVALID` refusal (`JdbcAgentCommandRepository`, one line; the kit now asserts it). `LABEL_INVALID` is an existing wire code (`ERROR_CODE_LABEL_INVALID = 22`), so no `GatewayCode` or proto change was needed (HF18/HF14 untouched). (e) The placement is read **by the identity's tenant and target**, never by `bindingId` (`I5`): a lease row that names another host's placement is `BINDING_NOT_IN_LEASE`.
  - **Keyed-hash bridge.** Nothing in this task stores a digest: `effectiveHash` arrives as `sha256:<hex>` from the gateway and goes to the in-memory observation only (`I6`, `OR-32`); `AgentReportIntakeIT.noPlainDigestIsStoredInAnyTable` scans every table. The adapter's plain-to-keyed conversion is T3.1's.
- **Tests, red-to-green.** Not red-first: the tests were written with the code. The evidence is a mutation matrix on `JdbcAgentReportRepository`, run in one build and reverted to a byte-identical file: (1) `effective()` ignoring `advanced` -> `aReplayedItemAppliesNothingASecondTime` red; (2) `effective()` ignoring trust -> `anUntrustedReportAfterTheLeaseExpiredWritesNoHealthAndNoStart` and `AgentReportLadderIT.anUntrustedReportAfterTheLeaseExpired...` red; (3) the start upsert not forward-only -> `theStartIsRecordedForTheReportedPlacementOnlyAndMovesForwardOnly` and `AgentReportLadderIT.theObservationOnlyMovesForward...` red; (4) no `replica_ref` check -> `aWrongReplicaRefIsRefused...` and the kit's `aReplicaRefThatIsNotThePlacementsContainerName...` red; (5) the target filter removed -> `aPlacementOnAnotherHostIsRefusedEvenWhenTheLeaseRowNamesIt` red. 8 failures of 28, green again unmutated, `JdbcAgentReportRepository` restored identical. The P15-22 "cross-host" mutation is the last one: an observation is only ever written for the reporting placement, so a start from one host is never compared with a baseline taken on another.
- **Gate evidence.** `AgentReportIntakeIT` 13, `AgentReportLadderIT` 4, `JdbcAgentReportPortContractIT` 11 (the kit gained three cases), `JdbcAgentCommandPortContractIT` 23, all green on PostgreSQL 14 under the docker lock; `AgentReportIntakeMappingTest` 7, `AgentContractedStubsTest` 3, `TablePlacementCatalogTest` 7, `AgentPlacementsTest` 2, `SchemaMirrorLockTest` 55 (one assertion updated: `rollbackApply` and `restoreRuntimeConfig` may now carry a `501` that is `AGENT_OPERATION_UNSUPPORTED`, never a `NOT_IMPLEMENTED`), the two target mirror tests 10 and 9; `contracts/openapi/validate-openapi.py` clean, `check-postman.py` OK, `contracts/run-checks.sh` passed. `AgentPlacementRefusalsEndpointIT` (launcher) is written and **not yet run** at this commit: the docker lock was held by another lane for over an hour; its result is recorded with T3.5's gate.
- **Two regressions found by the full unit suites, fixed in their own commits (`f5f0ac58`, `8d34adfc`), and two test-body fixes (`dcde1732`, `7daf4fe6`).** The T3.3 commit was first checked with a focused set, which is how both got through. The refusal IT's FIRST run was red (`wt33.log`): `acceptRuntimeConfig` answered `400` because the body lacked `baseStateVersion`, and after that fix `rollbackApply` answered `400` for lack of a `strategy`; both were test bodies, not the production code, and the gate that follows was the first green run. The two regressions were: (1) the placement view read a target's transport with one `connections.find` per placement, which `InventoryPlacementOperationsTest`'s recording double rightly refused (2 sequence assertions, 10 cases); it is now one batched `TargetConnectionRows#transportsOf`; (2) `ProblemBody` builds nothing for an `ErrorCode` without a title (`ProblemBodyTitlesTest`), so the 501 would have been answered as an unrelated failure; the title is added. The lesson is recorded in the report: run every module's unit suite, not the files you touched.
- **Stop and report (HF15).** `arch-tests`' `PlaneBoundaryTest.noProductionClassImplementsTheReportOrTelemetryPortYet` fails by design the moment `AgentReportIntake` exists (measured: `expected: <[]> but was: <[com.solace.sm.dp.agent.AgentReportIntake]>`; every other arch test passes). **The exact change, for lane A:** in `PORTS_WITH_NO_BEAN_YET` drop `"com.solace.sm.dp.api.agw.ActualStatePort"` so the set is `AgentTelemetryPort` alone, and reword the assertion message to name T3.4 only; `PlantedPortBean` already implements `AgentTelemetryPort`, so the planted proof still fires. Optional, same file: `AgentObservedStatePort` is no longer a stub (it has a real class); it may stay in `CONTRACTED_STUB_AGENT_GATEWAY_PORTS` (the inventory test still passes) or move to `KITTED_AGENT_GATEWAY_PORTS` together with a kit and a `Jdbc*ContractIT`. I did not edit `arch-tests`.
- **Owed to a later task.** (1) ~~The AGW-D28 item 1 staleness rule is not built~~ Built in the fix round (below). (2) The ack's `container_started_at` as the attempt's `write_baseline` (AGW-D28 item 2, first half) belongs to the command path (T3.1). (3) `AgentHealthObservation.state`: only `UNREACHABLE` (a container that is not running or whose inspect failed, AGW-D28 item 1) is read, as a gate input; the Docker health check's `HEALTHY`/`UNHEALTHY`/`STARTING` is a hint and is never the gate (`AgentReportIntakeMappingTest` pins both). [Corrected in the fix round; this sentence first said it was never read.] (4) `listTargets`/`getTargetConnection` etc. are T3.5.

### T3.5 — Results (2026-10-11, `feat/agent-gateway/w3-reports-and-target`, lane C, Sonnet 5.5)

- **Step 0 (measured).** (a) **The sweep's attribution rule.** `BE-D32`'s declare sweep and the undeclare sweep write the data plane only and attribute nothing: they run inside some other user's request and never write `sm_control` (`UndeclareSweep` documents why, and `UndeclareBoundaryTest` fails the build if it ever does). The agent-target sweep has to write the control plane (a registration is revoked, a target retired), so there is **no precedent to borrow**. **Deviation, for the owner:** both writes are audited, under their own operations (`AGENT_REVOKED`, `TARGET_RETIRED`), to the **principal who issued the token** (`agent_registration.created_by`, the one principal tied to the row), with the requesting call's correlation id and address; the requester's identity is never used. (b) **A discriminated `oneOf` is accepted** by `validate-openapi.py` check 16 (after the check was updated to pin the new shape), by `ConsoleOperations` (no change) and by Postman generation; the console mirror tests needed their helpers to flatten a `oneOf` (properties are the union, required is the intersection), which is test code in this task, not a flattening of the contract.
- **Built.**
  - **`OR-136` (1).** `sm.control.agent-gateway.enrolment.token-default-validity` (24 h) and `token-max-validity` (30 d) replace T2.2's constants in `AgentEnrolmentService` (`AgentEnrolmentProperties`); the constructor refuses default > max or either <= 0, which fails the context at the `agentEnrolment` bean. Tests: the defaults, a configured pair honoured (default and ceiling), one second above the ceiling refused, five invalid configurations, and the bean factory.
  - **`registerTarget` and a pending token's name (T2.2 review 7).** `TargetService.registerOnce` refuses (`IllegalStateException`, as every other name clash) a name an **active, unconsumed** registration holds, in the same transaction as the insert; `TargetRepository#nameHeldByPendingEnrolment` reads the same condition as `agent_registration_pending_name` (which cannot read the clock, so an expired unused token holds the name until revoked). Revoking releases it. Tested against the real index in `AgentEnrollmentIT`. Residual: the other order (a token issued while a target is being registered) is a race the live-name index still arbitrates at enrolment (`TARGET_NAME_TAKEN`).
  - **The DP half of the enrolment is T2.1b's `JdbcAgentEnrollmentRepository`** (already one transaction: `target_connection`, `agent_identity`, the `enrolled` event, idempotent by primary key) and the CP target row is T2.2's; this task adds the proof that the two planes agree, that a crash between them is recoverable, and the sweep. `HF21`: **no edit was needed** in the launcher's three adapters.
  - **The sweep.** `cp-api` `AgentEnrolmentSweep` (`candidates`, `abandon`), `cp-core` `AgentEnrolmentSweepService` (revoke the registration FIRST in a transaction of its own, so a late replay, which the registry refuses for a revoked registration, cannot recreate the data plane half; then retire the target), the gateway's `AgentTargetSweep` (lazy, one organization, one bounded batch, best effort, `sm.dp.declare.abandon-after`), called by `listTargets`. Never touches a target whose data plane holds a connection record, however old its token.
  - **The target operations** (the plan's table). `409 TARGET_TRANSPORT_UNSUPPORTED` for `editTargetConnection` / `rotateTargetCredential` / `testTargetConnection` (the DP refuses at plan time and before the key is opened; the gateway also refuses the test **before the per-session probe budget is drawn**); `409 AGENT_STILL_ENROLLED` for `retireTarget` until `agent_identity.revoked_at`, then as today; `409 TARGET_IS_AGENT_MANAGED` for `addPlacement` and a declare; `getTargetConnection` answers the `AgentTargetConnection` variant; `listTargets` shows `transport: agent`; `registerTarget` stays `ssh-docker` (an `agent` transport is `400`); `scanHostKeys` unchanged. The three DP refusals are dp-api exceptions mapped once in `GatewayErrorAdvice`.
  - **Every reader of the nullable columns** (T1.1 review finding 12). `TargetConnectionRecord` gained `agentId`/`isAgent()` and the row mapper reads it; `TargetConnectionView` has an agent variant; `InventoryService` (`connection`, edit, rotation, replay, retire, reserve, `addPlacement`), `SshTargetConnectionTester`, `HealthProber` (an agent placement is `DEFERRED`: its agent reports it) and `TablePlacementCatalog` (T3.3) each tolerate or refuse it by name. `AcquisitionMode.AGENT` (V028's third value) exists so that the first agent instance is a value and not a parse failure in `JdbcInstanceStateRepository`; a declare naming it is `422`.
  - **Contract.** `Target.transport` is `[ssh-docker, agent]`; `TargetConnection` is a `oneOf` discriminated on `transport` (`SshDockerTargetConnection`, `AgentTargetConnection`); four `ErrorCode` members added to the enum and prose (`AGENT_NOT_CONNECTED` is declared now so the enum moves once; T4.3 uses it) and to `ErrorCode.java`, `ProblemBody` titles; the seven operations' descriptions and `409` responses; `ENDPOINT-INVENTORY`; Postman regenerated and checked; `validate-openapi.py` check 16 pins the new shape (and that `registerTarget` stays `ssh-docker`).
- **Tests, red-to-green.** Not red-first: written with the code. Seven mutations, run against the focused suites in three builds and reverted to a byte-identical tree (hash of every changed file before and after equal): M1 the DP envelope taken from a literal instead of the token -> `AgentEnrollmentTargetIT` (2 cases) red; M2 the sweep ignoring whether the DP holds the target -> `AgentTargetSweepTest` and `aCompletedEnrolmentIsNeverSweptHoweverOldItsTokenIs` red; M6 the pending-name check removed -> `AgentEnrollmentIT.registerTargetRefuses...` red; M7 default > max not refused -> `AgentEnrolmentServiceTest.anInvalidConfigurationDoesNotStart` red; M3 `editTargetConnection` allowed on an agent target -> `AgentTargetInventoryIT` and `AgentTargetEndpointIT.editRotateAndTest...` red; M4 retire without the revoked check -> `AgentTargetInventoryIT.anAgentTargetIsRetiredOnlyAfterItsAgentIsRevoked` and `AgentTargetEndpointIT.retireIs409...` red; M5 the test's transport precheck removed -> the connection test ran and answered `200` instead of `409` (`AgentTargetEndpointIT`) red. **The plan's first mutation ("write the DP row before the CP row") cannot be written:** the DP insert consumes the issuer's answer (the certificate's thumbprint, the envelope, the target id), so a data-plane-first order is not representable; the property is instead pinned by the crash IT, which stops after step (1) and shows the control plane's row alone, listed degraded and never hidden, completed by the retry with the SAME ids. The plan's second ("envelope from the agent's request") is also unrepresentable (the enrolment request carries no envelope); M1 is its form.
- **Gate (T3.3 + T3.5, head `59c751f8`).** `docker-lock.sh agw3c mvn -o -f backend/pom.xml -pl cp-core,dp-core,console-gateway,launcher,arch-tests -am verify -Dmaven.test.failure.ignore=true`, BUILD SUCCESS (failures ignored by flag, read below). Surefire: cp-api 55, dp-api 14, mesh-events 160, cp-core 640, dp-core 1488 (1 skipped), agent-gateway 33, console-gateway 990, launcher 150, arch-tests 160. Failsafe: cp-core 506, dp-core 1593 (12 skipped), launcher 450 (44 skipped). **One failure in the whole gate:** `arch-tests` `PlaneBoundaryTest.noProductionClassImplementsTheReportOrTelemetryPortYet` (160 run, 1 failed), the HF15 change recorded in T3.3's Results, owed by lane A (not edited here). Every other count is 0 failures, including `AgentPlacementRefusalsEndpointIT` (3), `AgentEnrollmentTargetIT` (6), `AgentTargetEndpointIT` (5), `AgentTargetInventoryIT` (7), `AgentEnrollmentIT` (10), `AgentReportIntakeIT` (13), `AgentReportLadderIT` (4). Against the W2 integration record the failsafe counts moved cp-core +1, dp-core +27, launcher +14. A separate flake, not caused here: `cp-api` `AgentRecordsRedactionTest.anEnrollmentRequestDoesNotPrintTheTokenOrTheCsr` asserts `doesNotContain("42")` on a string that carries a random UUID, so it fails about one run in four on its own (seen once, green on the rerun).
- **Owed to a later task / flagged.** (1) T4.1: `Instance`'s `acquisition.mode` in `console.yaml` is still `[direct-http, via-ssh]`; an agent-registered instance reads `agent` in `InstanceView`, so the schema, `Acquisition` and its mirror test widen with T4.1's contract, not here. (2) The agent sweep's attribution (issuing principal) is a deviation for the owner. (3) `HealthPoller` / `CollectionPlanBuilder` (HF19, lane A) must skip agent placements; until they do the poller's `HealthProber` answers `DEFERRED` for an `agent` acquisition and for an agent target, so it writes no verdict that races the agent's. (4) T4.3 uses `AGENT_NOT_CONNECTED`, already in the contract and `ErrorCode`.

### T3.3 — fix round (2026-10-11, after the Opus review `w3c-t33-review.md`: 2 MAJOR, 7 MINOR, 6 NIT)

Commit `6f401895`. Disposition of every finding:

| # | Finding | Disposition |
|---|---|---|
| 1 MAJOR | a report with no hash or health erases the remembered observation | **Fixed.** `AgentObservedState.remember` merges field by field (an absent field is "not observed", not "none on the host") and applies an item only if its sequence is higher than the one held, which also closes NIT 11. Cross-checked against the real agent (`integration/go-agent:go-agent/internal/agent/loop.go`): the periodic report (`:545-575`) sets no `EffectiveHash`, the op-finished report (`:288-310`) sets `Health = fin.Result.Health`, which may be nil. ITs: a hash-bearing report then a hash-less periodic one (hash, generation, start and health all survive; a NEW value does replace), an older sequence never overwrites a newer, a rolled-back report leaves nothing remembered. **For T3.1/T4.4:** lane A's `awaitReadBack` relies on this merge. |
| 2 MAJOR | AGW-D28 item 1 unbuilt and unowned | **Built, lazily.** `AgentHealthStaleness` records `unreachable` (the verdict AGW-D28 names; `OR-39` "unknown, not down") for an agent placement whose agent last reported before `sm.dp.agent.report-staleness` (default **5 minutes**, ten of the gateway's 30 s report intervals; the design gives no number, so it is configurable). Written on change only, probed placements only (`OR-101`), reported ones only (never-reported is `bootstrap`), bound by organization and instance. **It is lazy, not a scheduled job:** a loop must enumerate organizations, a third cross-tenant read that `OR-44`/`OR-49` and `TenantEnumerationBoundaryTest` do not permit without a new owner ruling; so, as `BE-D32`'s sweeps do, it runs inside `HealthService#health` for the request's own organization and instance. Consequence, stated: an instance nobody reads keeps its stale row, and no `instance.health` notice fires until someone looks. A scheduled variant is an owner question (§10, Q-C3). Central item 44 is closed on this basis. ITs: silent past the window -> `unreachable` once, a fresh report restores it, scoped by organization/instance/probe, never-reported untouched. |
| 3 MINOR | `scheduleRollback` refusal untested | **Fixed.** The refusal IT sends a scheduled rollback and asserts `501` and no `apply_schedule` row; removing the refusal turns it red (S8). |
| 4 MINOR | HF7 `InventoryService` edited without an owner | **Recorded, accepted by the integrator:** lane C is HF7's W3 writer (T3.3 `view()`, T3.5 the agent paths). |
| 5 MINOR | agent-supplied item id echoed into `agent_event` | **Fixed.** The id must match `[A-Za-z0-9._:-]{1,64}` and `binding_id` must be non-blank, else `LABEL_INVALID` by itself; such an id is audited under a SHA-256 prefix and never stored raw (a NUL would have aborted the batch, which the agent resends unchanged). IT: a control character, 65 characters and a blank binding in one batch; the fourth item is still `APPLIED`. |
| 6 MINOR | arch-test end state | **Integrator's** (the HF15 change and the `AgentObservedStatePort` certification), not done here. |
| 7 MINOR | the mutation matrix did not attribute | **Re-run singly, each its own build** (below). The plan's "write health on every report" survives because of V012's trigger (below); an intake-level test now pins the no-probe case. |
| 8 MINOR | Results inaccuracies | **Corrected in place** (the first red run and the two test-body commits; the `UNREACHABLE` state is read; "no binding" meant only `null`; the flake rate). |
| 9 MINOR | a future-dated start wedges the forward-only row | **Fixed, with a bound I chose:** a `container_started_at` later than receipt plus **5 minutes** is `REFUSED(CLOCK_SUSPECT)` (an existing wire code) and the whole item is refused. Five minutes is the clock window already ruled for the agent's data (`OR-130` F-6, T3.4), larger than the gateway's 30 s skew tolerance so an honest host a little ahead is taken. Recorded against AGW-D28 item 2 / review R-8 for the owner. IT: 6 minutes refused with nothing written; 4 minutes taken. |
| 10 NIT | `AgentObservation.health` not null when no probe | **Fixed** (null when none declared). |
| 11 NIT | after-commit ordering | **Fixed** by the sequence guard (finding 1). |
| 12 NIT | a missing transport fails open | **Skipped:** `TablePlacementCatalog` refuses an agent target by name and a target with no `target_connection` row cannot hold a live placement (a foreign key), so the open branch is unreachable; making `PlacementView` default to a refusal would change every caller's constructor. |
| 13 NIT | vacuous assertions | **Fixed** the `>=` one (now strictly greater, then equal after the replay). **Skipped** the random-UUID name and the spy-sensitivity for the four other services: the counts are zero for the agent placement and the control request shows the spy sees `read`; widening it needs an SSH-reaching fixture for each service. |
| 14 NIT | kit files and `AgentGatewayPortsConfiguration` edited by two lanes | **Integrator's** (additive, signature-free). |
| 15 NIT | `received` vs `ack` request-id handling | **Skipped:** the command path is lane A's (T3.1) and `ack`'s truncation is deliberate there; changing it here would edit another lane's semantics. Reported for T3.1. |
| flake | `AgentRecordsRedactionTest` | **Fixed:** a fixed correlation UUID, and the assertion names the CSR's renderings (`[42, 43]`, `Kis=`, `2a2b`) instead of the bare `"42"`. The rate was about **15%** (a v4 UUID string has 25 free hex pairs and a version pair), not "one in four". |

**Mutations, one per build** (`fix-S*.log`, `fix-G1.log`; the sources restored and checked clean after each): S1 `effective()` ignores `advanced` -> `aReplayedItemAppliesNothingASecondTime`; S2 ignores trust -> `AgentReportLadderIT.anUntrusted...` and `AgentReportIntakeIT.anUntrusted...`; S3 start not forward-only -> `theObservationOnlyMovesForward...` and `theStartIsRecorded...MovesForwardOnly`; S4 no `replica_ref` check -> `aWrongReplicaRefIsRefused...` and the kit's `aReplicaRefThat...`; S5 no target filter -> `aPlacementOnAnotherHostIsRefused...`; S7 remember immediately instead of after commit -> `aRolledBackReportLeavesNothingRemembered` (this survived the first matrix); S8 scheduled-rollback refusal removed -> the refusal IT. **S6, the plan's "write health on every report":** the intake already calls `HealthRecorder.record` for every effective report of a probed placement, and V012's trigger (not the intake) keeps `state_version` and `changed_at`, so `aHealthyReportWritesOneRowAndTheSameAgainWritesNone` proves the trigger and would survive that mutation. The honest restatement is "health is persisted on change by the V012 trigger, as for the dp-remote poller; the intake additionally writes nothing for a no-probe placement after the first time", and `aSecondReportForAPlacementWithNoProbeWritesNothingAtAll` pins the second half (S6, removing the once-guard, is red). Fix-round mutations in one build, each caught by a distinct test: N1 replace-not-merge, N2 no sequence guard, N3 staleness writes nothing, N4 no start bound, N5 no item grammar.

**Run** (docker lock `agw3c`, head `55d79c72` minus the docs): surefire cp-api 55, dp-api 14, mesh-events 160, cp-core 640, dp-core 1492 (1 skipped), console-gateway 994, launcher 150; failsafe (selected) cp-core `AgentEnrollmentIT` 12, dp-core `AgentReportIntakeIT` 21, `AgentReportLadderIT` 4, `JdbcAgentReportPortContractIT` 11, `JdbcAgentCommandPortContractIT` 23, `AgentTargetInventoryIT` 9, launcher `AgentPlacementRefusalsEndpointIT` 3, `AgentTargetEndpointIT` 5, `AgentEnrollmentTargetIT` 9. All 0 failures. `validate-openapi.py` clean, `check-postman.py` OK, `git diff --check` clean. Not re-run live after the last two edits (the `atexit` line in the scenario and the relative state-path wording in `sm-local.sh`'s printed messages): syntax-checked and `test-sm-local.sh` ok only.

### T3.5 — fix round (2026-10-11, after the Opus review `w3c-t35-review.md`: 2 MAJOR, 5 MINOR, 6 NIT, 2 owner questions)

Commit `55d79c72`.

| # | Finding | Disposition |
|---|---|---|
| 1 MAJOR | retiring a degraded agent target bypasses `AGENT_STILL_ENROLLED` | **Fixed in the control plane**, which owns the registration: `TargetService.retire` throws `TargetHeldByAgentException` (`409 AGENT_STILL_ENROLLED`) while an ACTIVE registration holds the target; the replay branch of `enrollOnce` refuses (`CONSUMED`) when its target is retired. ITs (`AgentEnrollmentTargetIT`): a half enrolment's retire is `409` and neither plane changes, the agent's retry still completes it with the same ids, a revoked half enrolment retires `200` and a later replay is refused with no data-plane row, and a replay into a control-plane-retired target is refused while the registration is still active. Mutations P1, P2 red. |
| 2 MAJOR | the sweep starves | **Fixed:** keyset paging on (token expiry, agent id), 20 per page, at most 10 pages per request. A healthy majority matches too because the control plane cannot see the data plane, so the query could not be narrowed to "degraded only"; paging is the bound. IT: 21 completed enrolments with the oldest tokens plus one degraded target behind them, one `listTargets` retires exactly the degraded one. Unit tests: page two, the cap. Mutation P3 red. |
| 3 MINOR | sweep vs a late replay | **Fixed:** revoke, look at the data plane again, retire only if the target is still absent; the Javadoc no longer claims "finished by the next call" without the second look. Residual stated: a data-plane write already in flight at the second read. Unit test: the DP becomes connected between the revoke and the retire, no `markRetired`. Mutation P4 red. |
| 4 MINOR | the name race | **Fixed:** one advisory lock on (organization, name) in `registerTarget`, token issue and the enrolment consumption (no migration). The randomised race IT passes with or without it (15 rounds); the **deterministic** IT (a holder keeps the lock, `registerTarget` must wait) is red without it (P5). |
| 5 MINOR | HF7/HF9 and T4.1's own refusal | **Recorded, accepted by the integrator:** lane C edited `InventoryService` (HF7) and `validate-openapi.py` (HF9) in W3. **T4.1 note:** `reserve` and `addPlacement` now refuse every agent target (`TargetIsAgentManagedException`); discovery's own declare must enter through a path the operator commands do not (a separate entry, or a command origin only the discovery service can construct), with the refusal kept on the operator-facing ones. |
| 6 MINOR | two ssh-only answers typed as the union | **Fixed:** `editTargetConnection` 200 and `TargetDetail.connection` name `SshDockerTargetConnection`; check 16 and `TargetRegistrationSchemaMirrorTest` pin both; `getTargetConnection` stays the union. |
| 7 MINOR | test gaps | **Fixed:** `SshTargetConnectionTesterTest` (agent refused, no probe), `HealthProberAgentTest` (agent acquisition, the classifier, a null host), `AgentTargetInventoryIT` (an `agent` acquisition row is read; a committed SSH edit in the mixed set with exactly one event), exact-count audit assertions in the sweep IT (one `AGENT_REVOKED` on the registration, one `TARGET_RETIRED` on the target, the requesting address). |
| 8 NIT | misnamed test | **Fixed** (`aTargetNameAPendingTokenHoldsCannotBeReservedByASecondToken`). |
| 9 NIT | cutoff on the wrong clock | **Fixed** with finding 2: the control plane computes the cutoff from its own clock. |
| 10 NIT | `agentRevoked` fails open | **Fixed:** `orElse(false)`. |
| 11 NIT | dead code | **Fixed** `Abandonable.active` removed and the validity defaults have one source. **Skipped** `requireSshTarget` reading the full view: the cheaper `transportsOf` is dp-core internal and the gateway has only dp-api; a new dp-api method for one test route is not worth it. |
| 12, 13 NIT | OR-136 properties in `application.properties`; duplicate PKI helpers | **Integrator's** (HF17; the merge). |

**Mutations** (`fix-G2.log`, `fix-P5b.log`): P1 CP retire unchecked, P2 replay into a retired target, P3 one page only, P4 no re-read, P5 no name lock: each red in the test named above. **Owner questions** are in §10 (Q-C1, Q-C2, Q-C3); the sweep was not redesigned.

**Run:** the same run as T3.3's fix round (the two share one build); `AgentEnrollmentIT` 12, `AgentEnrollmentTargetIT` 9, `AgentTargetEndpointIT` 5, `AgentTargetInventoryIT` 9, `AgentEnrolmentSweepServiceTest`/`AgentTargetSweepTest` in the surefire counts above; all 0 failures.

### W3 integration fix round (2026-10-11, `w3-integration`; review `w3-int-review.md`: 3 MAJOR, 4 MINOR, 5 NIT; Sonnet 5.5)

Counts: 3 MAJOR fixed (the third after the owner's `OR-139`); 4 MINOR fixed; NITs 8, 9, 10, 11 fixed, 12 no change.

| # | Finding | Disposition | Where / evidence |
|---|---|---|---|
| 1 MAJOR | after an SM restart no report restores the digest | **fixed**, DP only, nothing plain stored | `AgentPlacementCatalog#appliedConfigHash` (default empty) implemented by `JdbcAgentPlacementCatalog` (newest ACKED, APPLIED APPLY/REMATERIALIZE of the placement's agent with `wire_generation <= persistedGeneration`, its keyed `config_hash`); `AgentRuntimeAdapter#status` uses it when no plain digest is held, and `awaitReadBack` accepts it as the proof when it equals the keyed hash just written (a different newer hash keeps waiting, it is not a mismatch of this write). Keyed against keyed everywhere. **Red then green:** `AgentRestartHashIT` (3: resume at MATERIALIZE, resume at VERIFY, status) run with the main change stashed: all 3 failed (`APPLY_INDETERMINATE` with `materialize.readback.unavailable`; `observedHash` null); green with it. |
| 2 MAJOR | lost renewal reply plus a placement change strands the agent | **fixed**, option (a) | `JdbcAgentLeaseRepository#replay`: a digest-matching replay whose permissions moved returns the STORED row's grant (placements, capabilities, ceilings, margin, epochs, times) with the replayed nonce; nothing minted or written, no CP call beyond the deterministic re-issue that already proves the digest, no nonce-free re-issue (AGW-D15 / section 4.6 untouched); an unchanged replay is still re-validated. The next renewal under that nonce mints under the current set. **Test sources:** `AbstractAgentLeasePortContractTest` (dp-api test-jar) replaces `aReplayAfterACapabilityChangeIsRefusedAndWritesNothing` (which asserted the stranding) with the capability case and adds the placement-change case; `AgentJdbcWorld`'s issuer now derives its nonce from the real signer's inputs (agent, request id, thumbprint, consumed nonce), because a nonce that included the placement set made the review's sequence unreachable in the test. **Red then green:** both new cases failed against the old repository (`permissions changed since the lease was issued; renew it`), pass after; kit 19/19. |
| 3 MAJOR | `AGW-D28` item 2 not built | **fixed** after `OR-139` ("build as written"); first held for the owner, then built in a second pass | Section "Finding 3 as built" below. |
| 4 MINOR | merged health survives a restart | **fixed** | `AgentObservedState#remember` drops the held health when an item reports a different container start; `AgentReportLadderIT.aNewContainerStartDiscardsTheHealthHeldForThePreviousOneButTheSameStartKeepsIt` red without, green with. |
| 5 MINOR | arch rule cannot see a lambda | **fixed (documented + composition IT)** | `PlaneBoundaryTest` Javadoc says the rule sees classes only; `AgentGatewayCompositionIT` asserts exactly one bean of each owned port type, each the owned class. No new arch rule (the review's alternative). Not mutation-tested beyond the IT's existing `assertInstanceOf`. |
| 6 MINOR | `CONTRACTED_STUB_...` misnomer; kit missing | **fixed (rename) + noted (kit)** | Renamed set, test and message to `UNKITTED_AGENT_GATEWAY_PORTS` / `...HasAKitOrIsListedAsUnkitted`; the kit is owed (note above). |
| 7 MINOR | token properties never reach the container | **fixed** | `runtime/compose.yml` passes `SM_AGENT_ENROLMENT_TOKEN_DEFAULT_VALIDITY` (24h), `..._MAX_VALIDITY` (30d) and, same defect, `SM_AGENT_CA_LEAF_TTL` (30d) with `${...:-default}`; `runtime/.env.example` lists them commented; no compose-name pin test exists in the launcher (grep'd). |
| 8 NIT | staleness writer can overwrite a fresh verdict | **fixed (narrowed)** | `JdbcAgentReportRepository#stillStale` is re-read immediately before each `unreachable` record; the residual is one statement wide and heals at the next report. A true in-transaction recheck would change `HealthRecorder`; skipped. Tested directly (`AgentReportIntakeIT`); the race itself is not reproducible deterministically. |
| 9 NIT | stale merge prose | **fixed** | `AgentGatewayPortsConfiguration`, `AgentContractedStubsTest` Javadoc (no unused imports remained). |
| 10 NIT | declarations-change signal | **fixed (note)** | W4 note under `## W4`; port Javadoc corrected (dp-api, Javadoc only, HF18). |
| 11 NIT | duplicate PKI helpers | **fixed** | `AgentItPki` folded into `AgentGatewayTestPki` (`keystore()`, `csr()`); `AgentEnrollmentTargetIT` updated. |
| 12 NIT | credit re-grant can briefly exceed the gateway's view | **no change**, as the review says | Revisit only if the T4 fleet drill shows churn. |

**Finding 3 as built (`OR-139`, second pass).** Go agent checked first (`integration/go-agent`, read only): `commands.go` `startedAtOf` sets `container_started_at` on the terminal ack of an APPLIED APPLY, REMATERIALIZE and RESTART from the read-back or post-restart facts, absent for a failed attempt, PREFLIGHT, STOP and a never-started container (zero time); the journal keeps it so a replayed ack says the same. So an APPLIED ack with no start is only a never-started container, and fails closed. No migration.
- `JdbcAgentCommandRepository#ack`: on an APPLIED ack carrying a start, inside the ack's transaction: lock the instance row (as every landing and the intake do); for APPLY and REMATERIALIZE set `apply_attempt.write_baseline` (first writer wins, `step = 'MATERIALIZE'` because V020 allows no other); move `placement_start_observation` forward only (process_ref `ack:<command id>`); if it moved, settle the ladder through the new `AckStartSettler` (wired in `AgentGatewayPortsConfiguration`; `NONE` for the kits). A start more than 5 minutes ahead of the data plane's clock (the intake's `START_SKEW`) is `CLOCK_SUSPECT`: warned, nothing recorded, the command still acknowledged (refusing the ack would leave a real outcome un-acked and the agent resending forever).
- Engine: `WriteBaselinePolicy` (an optional interface, deliberately not a `RuntimeAdapter` method: `RuntimeSeamTypesTest` pins that interface at ten operations). `ApplyExecutor` records `WriteBaselinePolicy.of(adapter, placement, status)`; `RoutingRuntimeAdapter` delegates, `AgentRuntimeAdapter` answers `null`, every other adapter gets the unchanged `status.baseline()`, so dp-remote is byte-for-byte as before. `AttemptRepository.close` is untouched (its `coalesce(new, existing)` is harmless because the agent path passes `null`).
- `AgentRuntimeAdapter#status`: the report-derived baseline is gone. The baseline is `placement_start_observation` (`AgentPlacementCatalog#observedStart`), fed by acks and, forward only, by reports; a held health is read as unobserved while the recorded start is later than the start the report carries (a RESTART was acknowledged and no report has seen the new container), so the gate cannot pass on the previous container's verdict (the minor 4 hole, closed from the ack side).
- Not done / residual: the baseline now sits on an attempt that is still OPEN when the ack lands; if that attempt then closes FAILED, the failed attempt carries a `write_baseline` (the ladder reads it only for SUCCEEDED attempts, `JdbcRestartObservedGenerationRepository`). `SimulatedAgent` already sent starts on acks; it now also keeps the start in its journal for re-acks (as the Go journal does) and gained `omitStartOnAcks` and `startedAtIs`.
- Tests: `AgentAckBaselineIT` (5; ack-then-no-report, fail closed with and without a report-fed observation, `CLOCK_SUSPECT`, and two through the real executor), the command kit's two new cases (`JdbcAgentCommandPortContractIT` 31), `AgentKitWorld#placementStart` (a test-jar world addition, test sources only). **Mutations, each red then reverted byte-identical:** M1 ack stops recording (kit 1, `AgentAckBaselineIT` 2); M2 executor records `status.baseline()` again (`theEngineSuppliesNoBaselineOfItsOwn...`; this mutation first SURVIVED, and the test was strengthened to seed a report-fed observation, then it went red); M4 the agent policy answers the status baseline (2 `AgentAckBaselineIT`); M5 no clock bound (kit 1, `AgentAckBaselineIT` 1). Not mutation-tested: the settler call (asserted by count in `AgentAckBaselineIT`) and the health-versus-start rule in `status`.

**Runs (all under the docker lock, label `w3fix`, `-o`).** The second pass's focused build (`dp-api, dp-core, arch-tests, launcher -am`, BUILD SUCCESS, surefire 1581 in dp-core with 3 skipped, no failures): `AgentAckBaselineIT` 5, `JdbcAgentCommandPortContractIT` 31, `AgentRestartHashIT` 3, `AgentReportLadderIT` 5, `AgentReportIntakeIT` 22, `AgentCommandCrashRecoveryIT` 5, `JdbcAgentLeasePortContractIT` 19, `AgentTelemetryBridgeIT` 26, `JdbcAgentPlacementCatalogIT` 3, `AgentGatewayCompositionIT` 8, and the dp-remote ITs that read baselines: `ConfigLadderIT` 19, `PostRestartVerifyIT` 8, `RestartObservedGenerationIT` 23, `RollingApplyIT` 8, `StartObservationIT` 8, `PrivilegeTopologyIT` 9, `RefusalShapesIT` 7, plus `AgentStarvationIT` 5, `AgentClaimCapIT` 4, `AgentCommandRetentionEdgeIT` 1. The first pass: one focused build of `dp-api, dp-core, arch-tests, launcher -am` (plus the red runs of the new tests): the unit suites of those modules and the failsafe ITs `AgentRestartHashIT` 3, `AgentReportIntakeIT` 22, `AgentReportLadderIT` 5, `JdbcAgentLeasePortContractIT` 19, `AgentCommandCrashRecoveryIT` 5, `AgentTelemetryBridgeIT` 26, `JdbcAgentPlacementCatalogIT` 3, `AgentGatewayCompositionIT` 8, `AgentEnrollmentTargetIT` 9; `PlaneBoundaryTest` 15 (surefire) and 25 (as run in arch-tests); BUILD SUCCESS. The full gate was not run (the integrator's).

### W3 integration fix round 2 (2026-10-11, `w3-integration`; review `w3-int-rereview.md` of 648a4ceb: 2 MAJOR, 2 MINOR, 5 NIT; Sonnet 5.5)

| # | Finding | Disposition | Evidence |
|---|---|---|---|
| 1 MAJOR | after a retry or resume the SUCCEEDED attempt has no `write_baseline` | **fixed**, option (a) | `WriteBaselinePolicy.writeBaseline(placement, intentId, status)` (the executor passes `intent.intentId()`); `AgentRuntimeAdapter` answers `AgentPlacementCatalog#ackedWriteBaseline` (newest attempt of the intent that carries a baseline), empty (null) when the ack carried none. **Red** against the old `return null` policy: `AgentAckBaselineIT` retry and resume cases, `AgentRestartHashIT` resume case (baseline now asserted) all failed (`expected: not <null>`); green with the fix. The retry case blinds the observed state until an attempt has failed its read-back, so attempt 2 reuses the ACKED command; the resume case crashes after the APPLY ack and resumes on a rebuilt path. |
| 2 MAJOR | health rule compared ns with µs | **fixed** | `status` uses `recorded.isAfter(reported.plus(ConfigLadder.START_TOLERANCE))` (the constant, not a copy). Unit cases in `AgentRuntimeAdapterTest`: a start `...000000600Z` against a recorded `...000001Z` keeps HEALTHY; a recorded start one second later gives UNREADABLE. **Red** with the tolerance removed (`expected: <HEALTHY> but was: <UNREADABLE>`). Other comparisons grep'd: the ack and intake compare stored against stored in SQL (both microsecond-rounded), `HealthGate` and `ResumePlanner` compare database-read baselines with each other, `AgentObservedState` compares two in-memory values; no other agent-reported-versus-stored comparison exists. The step-back clock edge is written in the code comment and in AGW-D28. |
| 3 MINOR | hash fallback ignores late and indeterminate writes | **fixed** | `appliedConfigHash` considers ACKED APPLIED / APPLY_INDETERMINATE and SUPERSEDED `late.applied` / `late.apply_indeterminate`, newest first, and answers a hash only if that newest one landed. `AgentRestartHashIT` +2 (late applied names that file; indeterminate acked or late gives empty). **Red** with the old query: both failed. |
| 4 MINOR | V020 comment false on the agent path | **recorded** | AGW-D28 "Clarifications to the built shape" (2). V020 untouched. |
| 5 NIT | orphaned Javadoc | fixed | `AgentPlacementCatalog`. |
| 6 NIT | section pointer | fixed | `AgentTelemetryDeclarationsPort` now points at the W4 note (Javadoc only, HF18). |
| 7 NIT | extra routing lookup | **stated, not avoided** | `RoutingRuntimeAdapter.writeBaseline` resolves the route once more per write (one transport lookup); the recorded value for dp-remote is unchanged. A failed lookup there would fail an attempt whose write was read back; the router just resolved the same placement for `status`, so this is not a new reachable state in practice. |
| 8 NIT | pin the policy | fixed | `RuntimeSeamTypesTest.theWriteBaselinePolicyIsOneNonMutatingQuestion` and `PlaneBoundaryTest.onlyTheAgentAdapterAndTheRouterImplementTheWriteBaselinePolicy`. |
| 9 NIT | style | fixed | imports instead of qualified names; `log` field moved up. |
| W4 notes | 10, 11 | added | the two W4 notes under `## W4`. |

Not done: "assert that a later report-fed start promotes `restart-observed`" through the real ladder. The retry and resume cases assert the predicate the ladder's readers use (latest MATERIALIZE attempt `SUCCEEDED` with a `write_baseline`), and the climb itself remains proven by `AgentReportLadderIT` for a report-fed start over a baseline; composing the real `AckStartSettler` lambda is the W4 note (c).

### W3 integration (2026-10-11)

- **Order on `w3-integration`, off `f0f4eb6d`:** T3.1 (lane A, with its fix round) first, as the HF4/HF16/HF19 owner; then T3.2 (lane B); the `OR-138` side lane (`feat/agent-gateway/w3-db-role-secrets`: `497e8864`, `67cf96bb`, `runtime/` and docs only); T3.4 (lane D, HF4 after A); T3.3 and T3.5 (lane C) with lane C's notes for T4.1 and T3.1; each task with its review fix round. Every conflict was a Results block, a task note or a shared test-jar world method (`AgentKitWorld`), resolved by keeping both. A gate-1 fix (`609ae62a`: a `PlacementTransports.NONE` bean in `CollectWiringIT`/`PushWiringIT`'s support configs), the seam review's fix round (`w3-int-review.md`), `OR-139` built as written, the re-review's fix round 2 (`f4c504ca`, `w3-int-rereview.md`), and the follow-up review of `f4c504ca` (verdict: mergeable once the gate is green; no BLOCKER or MAJOR, one MINOR and one NIT carried as W4 note (d), (e)). Go code is unchanged in W3, so `integration/go-agent` was not re-merged and the §6.4 split is trivially clean.
- **Owner rulings recorded during W3:** `OR-139` (`AGW-D28` item 2 as written, with the dated clarification `5ee5006d`: the baseline belongs to the attempt the ack confirms, which on a retry or resume is the attempt that closes SUCCEEDED, compared within `START_TOLERANCE`), `OR-140` (the enrolment sweep and staleness verdict become scheduled jobs under a system actor; W4), `OR-141` (certificate-class first-issue refusal audit-only in v1), `OR-142` (clock-skew batches dropped and counted; a `batchSeq` behind the watermark warned and counted; gap markers not forwarded, W4).
- **New migration:** `sm_metrics` V004 (`ingest_counter_clock_skew`, T3.4, `OR-142` (1)); anchored at `f4c504ca` in the follow-up commit.
- **Full gate on `f4c504ca`** (`mvn -o clean verify -Dmaven.test.failure.ignore=true` under the docker lock, 1 h 07 min): BUILD SUCCESS. Surefire: cp-api 55, dp-api 14, mesh-events 160, cp-core 640, dp-core 1584 (3 skipped), agent-gateway 68, console-gateway 994, launcher 150, arch-tests 164. Failsafe: cp-core 508, dp-core 1673 (12 skipped), launcher 458 (44 skipped, 1 error). **The error:** `MeshResilienceRehearsalIT` could not start its broker container (Docker: `Bind for 0.0.0.0:<port> failed: port is already allocated`), so no test body ran; rerun alone under the lock: 7/7. Classified as an environmental host-port collision on the Docker error itself, not a code failure.
- **Other gates:** `run-checks.sh --strict` ALL PASSED; `check-frozen-migrations` (54 migrations byte-identical at 17 anchors; V004 unanchored, as expected before publication) and its self-test; `check-licences` and both licence self-tests (no coordinate added or changed in W3); `check-protoc-executables` and its self-test; `check-postman`; `git diff --check f0f4eb6d..HEAD`.
- **Pushed:** `integration/agent-gateway` fast-forwarded `f0f4eb6d..f4c504ca` (35 commits).

### T4.1 — Results (2026-10-11, `feat/agent-gateway/w4-agent-discovery`, lane A, Sonnet 5.5; review pending)

- **Built** (commit `138d98cf`, plus the follow-up in the T4.2 commit): `AgentDiscoveryService` (the T2.1b stub filled in where it stood, renamed to the plan's file name) with `JdbcAgentDiscoveryRepository`; one transaction per item under `AgentAuthority#underSession`; the plan's placement table. **Step 0 measured:** `mechanism` and `retained_generations` are the server defaults `InventoryService` stores for every declare (`PlacementSpec` has no field for either); the probe and metrics are copied from the package by the gateway's `DeclareOrchestrator`, and the DP reads no package. So **a new dp-api seam, `PackageDeclarationSource`** (outside `agw/`, not HF18), which `console-gateway` implements (`AgentPackageDeclarations`, T4.2 commit) from the agent's ENROLMENT (organization, catalog entry, version), never from the report. The host port is the one Docker published for the probe's container port, else label `com.solace.sm.health-port`; metrics endpoints are re-mapped to published ports in the DP. A metric with a blank `unit` is refused `incomplete` in the DP too (T3.2 note 2); the builder already refuses it for an operator.
- **Declare entry:** `InventoryService#reserveDiscovered(org, DiscoveredReservation)` (HF7; public on the class, on no dp-api interface; acquisition `agent`, instance by the token's issuer, placement `created_by` = agent). `reserve`/`addPlacement` still refuse agent targets (`AgentTargetInventoryIT` green); a declare body naming `agent` stays 422 (T3.5).
- **Tombstones — a deviation from the plan's wording, by design.** Instead of a hook in each of `InventoryService`'s abandon/sweep/retirePlacement and the retirement service, **sm_data V031** (new, `CHECKSUMS.sha256` pins it, **not yet anchored**) adds a trigger on `placement.retired_at`: PENDING/REGISTERED row of that placement becomes `ABANDONED` (instance `abandoned`), `UNDECLARED` (`retiring`/`retired`) or `RETIRED` (otherwise), in the retiring transaction whoever retires. Reasons: one place covers every caller, and a hook would have lengthened `InventoryService`'s canonical constructor, which `MetricsDeclarationConstructorTest` (HF15, not mine) pins at 11. V031 also gives the terminal-guard function the one release move (REFUSED/ABANDONED to PENDING in the statement that records a different `container_id`); V028 left the plan's release rule unwritable (no DELETE, terminal final). UNDECLARED/RETIRED release only by a changed label (a different key).
- **Codes:** `GatewayCode` gains `agent.placement.cap`, `instance.name.taken`, `entitlement.quota` (HF18, additive: the plan's own refusals T2.1a did not declare; `AgentGatewayServiceTest`'s no-wire-code set lists them). `AgentRegistrationCompletion` (the T4.2 sink) is declared with the intake that calls it, so it is in this commit.
- **Tests:** `AgentDiscoveryIT` 26 (placement table, idempotence, no-probe, label port, metrics mapping, blank unit, envelope, incomplete facts, cap, name taken, index codes incl. the cross health/metrics trigger, reflection test that the report names no environment/organization, stale session, sink contract, `undeclareThenReconnectDoesNotReRegister`, `retirePlacementThenReconnect…`, `sweepThenReconnectDoesNotReMint`, `refusedThenRecreatedIsRetried`, `recreatedContainerKeepsItsPlacement`, `undeclaredThenRecreatedStaysUndeclared`, multi-item order); `AgentSchemaIT` +1 (V031 release); the discovery kit now states what holds for any implementation. Red-first was NOT done (code preceded tests). **Mutation matrix** (each red, reverted byte-identical): cap check disabled (cap test); UNDECLARED write removed (`undeclareThenReconnect…`); sweep ABANDONED write removed (`sweepThenReconnect…`, `anAbandonedKeyIsRetried…`); UNDECLARED treated as releasable (`undeclareThenReconnect…`, IllegalState). The first three were run against the first, application-hook form of the tombstone writes; after the move to V031's trigger the same ITs pass, and the trigger itself is proved by those ITs (removing it is a migration edit, not run).
- **Test-jar/test edits:** `AgentJdbcWorld` (discovery wired to the real service; accessors), `AgentItSupport#inventory`, `AbstractAgentDiscoveryPortContractTest` (first case now "unlabelled item refused incomplete"), `AgentContractedStubsTest` (stub case removed), migration-count assertions 30→31 (`SmDataBootstrapIT` incl. function/trigger lists, `SmMetricsMigratorIT`, `ApplyPersistenceConfigurationIT`).
- **Hot files:** HF7 edited (`reserveDiscovered` only); HF4 **no edit needed** (no new DP configuration class: the intake is a bean of the already-imported `AgentGatewayPortsConfiguration`); HF17: `application.properties` (`sm.dp.agent.max-placements-per-target`, default 16) and a Javadoc paragraph in `SolutionManagerConfiguration` (the completer and the package reader are `@Component`s of the existing scan, so no bean line is needed); HF5: V031 taken.
- **Owed / for the integrator:** (1) anchor V031. (2) **WITHDRAWN** (review, integrator ruling): lane B's single widened `Acquisition` stands, because `validate-openapi.py` check 16 / M-Q2 require one schema; the declare-body schema therefore admits `agent`, which the server answers 422 (T3.5 tests it). (3) **Declarations change signal (lane D's `DeclarationsChangeSink`):** discovery's REGISTERED confirm is `AgentDiscoveryService.Settlement#registered()`, right after `tombstones.registered(...)` succeeds inside the transaction; that is where `DeclarationsChangeSink.changed(tenant, instance)` belongs. Not added here (the type is not on this branch). **Closed at W4 integration:** no call is needed there; activation signals (see the lane D note under T4.1). (4) Residual: `Settlement#refused` takes the discovery row lock then the instance lock, the sweep the reverse; a deadlock aborts one, which leaves the row PENDING for the next Connect. **Closed in lane A's fix round:** both settlements lock the instance first (mutation (d)).

### T4.2 — Results (2026-10-11, same branch, lane A, Sonnet 5.5; review pending)

- **Built:** cp-api `AgentInstanceGovernance#registerForAgent`; cp-core `GovernanceService#registerForAgent` (the shared `registerNode`: same lock order, quota and scope rows, audit context `AGENT_INSTANCE_REGISTERED` via `AgentAuditContext`) exposed by its own class `AgentInstanceGovernanceService` (a first form that made `GovernanceService` implement both interfaces broke 72 launcher contexts with `NoUniqueBeanDefinitionException`, and `DeclareRegisterTimeQuotaIT`'s post-processor would have wrapped it; found by the first full gate); console-gateway `agent.AgentRegistrationCompleter` (asks `registered(...)` first so a node that exists is only confirmed; `QuotaExceeded`/`EntitlementRefused` become `refused(entitlement.quota)`, `ResourceNotFound` becomes `refused(agent.discovery.outside.envelope)`, anything else propagates and leaves the row PENDING) and `http.AgentPackageDeclarations`. No tenant enumeration; `TenantEnumerationBoundaryTest` unchanged and green.
- **Tests:** `AgentRegistrationCompleterTest` 5 (unit); `AgentRegistrationCompleterIT` 4 (launcher: pending to CP node to REGISTERED, `change_audit` row with `actor_agent_id` = agent, no principal, the RPC's correlation id and address, probe/metrics from the enrolled package, and a lease RENEWAL that holds the new placement — a new placement reaches the agent only at its next renewal, the lease snapshot is not pushed; crash after the CP write completed by the next report with one node; crash before it tombstoned by the lazy sweep and not re-minted; over quota refused `entitlement.quota`, audited, no CP node). Mutation proofs: see the fix round below.

### W4 lane A fix round (2026-10-12; review `w4a-review.md`: 0 BLOCKER, 1 MAJOR, 10 MINOR, 6 NIT; Sonnet 5.5; commit after `c1c55223`)

**Gate:** none run by the lane (the integrator runs it). Focused tests (counts per class): dp-core `AgentDiscoveryIT` 33, `AgentSchemaIT` 31, `JdbcAgentDiscoveryPortContractIT` 3, `SmDataBootstrapIT` 11, `SmMetricsMigratorIT` 4, `ApplyPersistenceConfigurationIT` 5, `DataPlaneMigrationChecksumsTest` 4, `InventoryServiceIT` 17, `DeclareSweepIT` 10, `InstanceRetirementServiceIT` 21, `AgentTargetInventoryIT` 9, `AgentContractedStubsTest` 1; cp-core `InstanceRetirementServiceIT` 8; console-gateway `AgentPackageDeclarationsTest` 5, `AgentRegistrationCompleterTest` 5; launcher `AgentRegistrationCompleterIT` 4. (The second-tier classes ran just before the final V031 grant and test-only edits; `AgentDiscoveryIT`, `AgentSchemaIT`, the contract IT and the migration ITs ran after.) New V031 checksum: `a5f471f5c56bcfae725d34df73dd5f31c4c8d287a49d61f4e82425b5d6293e5e` (still unanchored).

**Mutation matrix (MAJOR 1)**, on the committed tree under the docker lock; each reverted with `git checkout`, `git diff` empty and the sha256 of the mutated files identical before and after (`7cf1af24d51c247e...`); V031 mutations regenerated CHECKSUMS so the checksum test is not what failed:

| Mutation | Went red |
|---|---|
| (a) quota check skipped on the agent path (`>= Integer.MAX_VALUE`) | `AgentRegistrationCompleterIT#overTheQuota...` |
| (b) node written with a principal actor and `INSTANCE_DECLARED` | `aDiscoveredContainerBecomesAGovernanceNode...`, `overTheQuota...` (REGISTERED expected, PENDING), `killedBetweenTheControlPlaneWrite...` |
| (c1) V031 swap UNDECLARED and RETIRED | `undeclareThenReconnect...`, `retirePlacementThenReconnect...` |
| (c2) V031 abandoned maps to RETIRED | `sweepThenReconnect...`, `anAbandonedKeyIsRetried...`, `aSettlementAfterTheSweepAbandoned...` |
| (c3) V031 release branch deleted | `refusedThenRecreatedIsRetried`-class cases (`anAbandonedKey...`, `theHealthPortComesFromTheLabel...`) and `AgentSchemaIT#v031Releases...` |
| (d) `Settlement#refused` takes the discovery row before the instance | `refusedTakesTheInstanceLockFirst...` (PessimisticLockingFailure, i.e. the deadlock) |

**Dispositions.** 1 mutations above (T4.1 and T4.2 were never red-first; the matrix is the evidence). 2 `refused`/`registered` lock the instance row first (`JdbcAgentDiscoveryRepository#lockInstance`, `FOR NO KEY UPDATE`) then branch on the lifecycle; the interleaving is deterministic enough to test (an open owner transaction in the sweep's order, `pg_locks` polled for the waiter), proved by mutation (d). 3 no package reader: audited, no row, refused `agent.discovery.unavailable`, retried next report (tested); Javadocs corrected. 4 a PENDING row older than `sm.dp.declare.abandon-after` is never handed to the sink (tested); the release restamps `first_seen_at`, which needed V031's `GRANT UPDATE (first_seen_at)`. 5 no-row refusals audit under a sha256 of the item's facts (tested: three reports, one event) and a report is bounded by `sm.dp.agent.max-discovery-items-per-report` (default 256; past it `agent.placement.cap`, never looked at; tested). **Owed under T5.2:** a bound on terminal `agent_discovery` rows per agent (or `OR-140`-style retention); tombstones are never deleted. 6 index `agent_discovery_by_placement` in V031. 7 branches on the lifecycle read under the lock instead of catching in a joined transaction (tests: "already active keeps REFUSED, event and instance", "settlement after the sweep is a no-op"). 8 plan text amended; `ORDER MATTERS` comments at `InventoryService#abandon`, `#settlePending`, `InstanceRetirementServiceImpl#completeRetire` and in V031. 9 the dangling pointer is gone and counts are listed above. 10 the three `GatewayCode` constants (`agent.placement.cap`, `instance.name.taken`, `entitlement.quota`) are **ratified by the integrator** as an additive HF18 change. 11 `AgentPackageDeclarationsTest` (5 cases). **Stopped:** the shared contract kit in the dp-api test-jar needs the dp-api test-jar as a console-gateway test dependency (a pom line, HF13, nobody this wave): the exact line is a `<dependency>` on `dp-api` with `<type>test-jar</type><scope>test</scope>`. **No arch-inventory line is needed:** `PlaneBoundaryTest` lists only interfaces under `dp.api.agw` named `*Port`; `PackageDeclarationSource` is in `dp.api.inventory`, so adding it there would only be a voluntary line, which I did not make (HF15). 12 stale Javadocs fixed. 13 documented on `refuseAgain`. 14 covered for no-key refusals by 5; the agent-chosen `messageId` dedup of the other audit events is the W2/W3 pattern, left. 15 left in `gateway.http` (it needs that package's helpers), to move when they are next touched. 16 the cap test uses cap 2 over one seeded placement, equivalent to 17 of 16; AGW-D30's wording is unchanged. 17 noted as expected: a sweep that activates a CP-registered instance leaves the discovery row PENDING until the agent's next report.

### T4.3 — Results (2026-10-11, `feat/agent-gateway/w4-agent-verbs`, lane B, Sonnet 5.5)

- **Commits.** `280241a3` (flake fix, own commit), `55103133` (T4.3), and a follow-up with `AgentEnrollmentIT`'s org-binding case and this record.
- **Flake.** `InstanceControllerTest.aMetricsPortCollision...` now matches `28081` with a lookaround token match (`(?<![0-9A-Za-z-])28081(?![0-9A-Za-z-])`): it still fails if the port is echoed as a number or in text, and cannot match inside a UUID.
- **Built on what exists, nothing rebuilt:** `AgentEnrolment.issue` (T2.2, `OR-136` lifetime), the DP enrolment/target creation and the sweep (T3.5), `AgentTelemetry...` untouched. New: cp-api `AgentAdministration` (+ cp-core `AgentAdministrationService`, `AgentRegistry#list/detail` default-throwing, `JdbcAgentRegistry`), dp-api `com.solace.sm.dp.api.agent.AgentSessionFacts` (JDBC `JdbcAgentSessionFacts`) and `AgentDrainRequester` (bean in `AgentGatewayConfiguration`, backed by new `AgentGatewayService#drainAgent`: one session, `reconnect_after` = `drain-reconnect-min`, forced close at the window), console-gateway `AgentController`/`AgentOrchestrator`/`AgentResolver`/`AgentErrorAdvice` and wire records.
- **Contract delta (HF3).** Operations `issueAgentEnrolment` (POST `/cp/environments/{environmentId}/agent-enrolments`, 201/400/401/403/404/409/422), `listAgents` (GET `/cp/agents`, reach), `getAgent`, `revokeAgent` (POST `/cp/agents/{agentId}/revoke`), `drainAgent` (POST `/dp/agents/{agentId}/drain`, 202/409). Schemas `IssueAgentEnrolmentRequest`, `AgentEnrolment`, `AgentSession`, `Agent`, `AgentList`, `AgentDrainAccepted`; parameter `AgentId`; tag `agents`. **No new `ErrorCode` member** (T3.5 declared all five; HF20 untouched). **`Acquisition.mode` widened to `[direct-http, via-ssh, agent]`** (carry for T4.1, see its note). `ConsoleOperations` +5, `OperationPolicy` reach set +`listAgents`, ENDPOINT-INVENTORY rows, Postman regenerated and checked, `validate-openapi.py` Acquisition pin. Catalog counts unchanged (47 verbs); 78 operations.
- **Deviations from the T0.3 draft (for the reviewer).** (1) Grammar faults are `400` (the plan's "malformed envelope"), not `422`; `422` is only an unloaded solution/version. (2) `ENTITLEMENT_REFUSED` removed from the issue's `409`: the built issue service does not check entitlement; it is enforced when instances register (T4.2). (3) `Agent.stateVersion` is derived (1 issued, 2 enrolled, 3 revoked); `capabilities` is required. (4) Timestamps are ISO strings. (5) The new ports live in `dp-api/.../agent`, not the HF18 `agw` package (no HF18 edit).
- **Tests.** `AgentEndpointsIT` 9, `AgentDrainCompositionIT` 2, `JdbcAgentSessionFactsIT` 4, `AgentEnrollmentIT` +1 (13), `AgentSchemaMirrorTest` 11, `AgentOrchestratorTest` 9, `AgentAdministrationServiceTest` 5, `AgentGatewayServiceTest` +3 (46 focused), `ProductionAuthenticationIT` route set +5. Not red-first (written with the code). **Mutation matrix** (each singly, in a scratch copy, restored byte-identical): M1 token returned on `getAgent` -> `AgentEndpointsIT.anAdministratorIssues...` red; M2 Operator granted `agent.revoke` -> `aViewerOnlyViews...` red; M3 revoke without epoch raise -> `revokeRaisesTheEpoch...` red; M5 drain ignoring the gateway's answer -> 3 IT cases + `AgentOrchestratorTest` red; M6 list without the reach filter -> IT case + `AgentOrchestratorTest` red; M4 registry read unbound by organization -> **survived the endpoint IT** (authorization is a second lock), so `AgentEnrollmentIT.theOperatorReadsAndTheRevoke...` was added and goes red under M4.
- **Revocation / drain proof.** After `revokeAgent` the CP epoch rises by one (once; idempotent), `AGENT_REVOKED` names the principal, and the real `AgentIdentityPort.reconcile` answers `REVOKED` and a new `claim` is `IDENTITY_REVOKED` (the gateway closes a session on `REVOKED`, T3.2). The HTTP IT stands a recording double in for the live-session map; `drainAgent` itself is proven in `AgentGatewayServiceTest` (Drain carries `reconnect_after`, only that agent, forced close at the window) and `AgentDrainCompositionIT` (the composed bean is the gateway's).
- **Gate** (`docker-lock.sh agw4b mvn -o -pl cp-api,cp-core,console-gateway,launcher,arch-tests -am verify -Dmaven.test.failure.ignore=true`, 1 h 14, BUILD SUCCESS, 0 failures anywhere). Surefire: cp-api 55, dp-api 14, mesh-events 160, cp-core 645, dp-core 1584 (3 skipped), agent-gateway 71, console-gateway 1014, launcher 150, arch-tests 164. Failsafe: cp-core 509, dp-core 1677 (12 skipped), launcher 475 (44 skipped). `run-checks.sh --strict` ALL PASSED; `check-postman.py` OK.
- **Process slip.** One early unit run (`-pl cp-api,cp-core,console-gateway,arch-tests -am test`) was started outside the docker lock; two Testcontainers tests failed to start their container and nothing else was affected. Every later run used the lock.
- **Hot files touched:** HF3 (console.yaml, `ConsoleOperations`, ENDPOINT-INVENTORY, Postman + `generate-postman.py`), `validate-openapi.py` (Acquisition pin; counts did not move). No HF7/17/15/4/18/20 edit, no migration, no dependency, no test-jar edit.

### T4.3 — fix round, as merged (recorded by the integrator at W4 integration, 2026-10-12; review `w4b-review.md`; commits `c377835d`, `516e143e`, `776e338a`)

The Results above predate the review round and are superseded where they differ:
- **The drain port** is `AgentDrainPort` in `com.solace.sm.dp.api.agw` (not `AgentDrainRequester` in `dp.api.agent`): `drain(agentId, reason)` answers `Optional<Duration>`, empty when nothing was sent. It is an **HF18 addition authorised by the integrator for lane B**, with `AbstractAgentDrainPortContractTest` in the dp-api test-jar run over the real `AgentGatewayService` (`agent-gateway/pom.xml` takes the in-reactor dp-api test-jar at test scope; no new coordinate).
- **HF15, lane B's own ports, authorised:** `AgentDrainPort` in the KITTED inventory, `dp.api.agent..` in `theDpRemoteAdaptersReachNoAgentType`, `AgentSessionFacts` in `agentGatewayNeverNamesDataPlaneInternals`, each with a planted violation. So "no HF15/HF18 edit" above is wrong.
- **`Agent` is unversioned** (no `stateVersion`, `OR-38`, as `Target`).
- **400 vs 422 (integrator ruling):** a structural fault is `400`; an envelope value outside its grammar is `422` naming the field.
- **Revoke** requires `agent.revoke` at the agent's own environment, checked in the service. **Drain** carries the reason `operator-drain` to the notice and the `agent_event`, and answers `409 AGENT_NOT_CONNECTED` when nothing was sent (not accepted, or already draining).
- **Postman:** `listAgents` no longer captures `agentId`.
- **Gate on `776e338a`:** BUILD SUCCESS, 0 failures (integrator's run, 06:33 to 07:47).

### T4.6 and W4 notes (c), (d), (e) - Results (2026-10-12, `feat/agent-gateway/w4-system-jobs`, lane C, Sonnet 5.5; focused tests only, the integrator runs the gate)

- **C1 (OR-140).** `sm_control` **V022** (`system_actor`, `change_audit.actor_system_id`, one-kind CHECK over three kinds, `record_change_audit()` rebuilt from V021's body; the system actor is allowed only for `AGENT_REVOKED` active->revoked on `agent_registration` and `TARGET_RETIRED` on a live agent target, from `system-job`). `AgentEnrolmentSweepJob` (gateway) and `AgentStalenessJob` (dp-core) replace the lazy forms; `listTargets` and `getHealth` write nothing. Exactly once: as `ScheduleFirer`, no lock; every move is conditional, so a second instance changes nothing (residual: duplicated read work). Properties `sm.gateway.agent-sweep.*`, `sm.dp.agent.staleness-job.*`. Mutations red then restored byte-identical: sweep on the GET, audit to another actor, allow-list names dropped, verdict on the read, migration admits any operation.
- **C2 (c).** `AgentAckSettlerLadderIT` (3): the real `agentAckStartSettler` bean against the real ladder drives an agent RESTART ack to `restart-observed`; control with `AckStartSettler.NONE` does not move; mutation "settler wired to nothing" red (2 failures).
- **C2 (d).** `ApplyExecutor#rematerialize` closes with `WriteBaselinePolicy.of(...)`; red first in `AgentAckBaselineIT` (redrive retry) and `IndeterminateReconcilerIT` (dp-remote records the baseline); mutation red. A deliberate dp-remote change, recorded under `BE-D34`.
- **C2 (e).** `dp.apply.model.StartTolerance.VALUE` replaces `ConfigLadder.START_TOLERANCE` (also read by `JdbcRestartObservedGenerationRepository`, whose SQL form must read the same constant: it would not compile otherwise). `RoutingRuntimeAdapter` imports ordered. `DpPackageCycleTest` (agent package only) with a planted fixture; mutation red. A second older edge, `runtime.readback.RuntimeReadBackConfiguration` -> `config.OverlayKeys`, is reported, not fixed.

### T4.6 - fix round (2026-10-12, after the Opus review `w4c-review.md`: 1 MAJOR, 9 MINOR, 6 NIT; Sonnet 5.5; focused tests only)

- **MAJOR (sweep starvation).** The candidate list cannot be narrowed in SQL (the control plane cannot see the data plane, so a healthy enrolment is indistinguishable from an interrupted one), so `AgentTargetSweep` keeps a per-organization keyset cursor across passes (reset on a short page, forgotten for organizations the job no longer lists); page limits are injectable. Test: 9 healthy rows plus one degraded sorted last, batch 2 x 2 pages, retired in exactly 3 passes; mutation "no cursor" red (2 tests). Javadoc corrected.
- **MINOR 1.** Chose the code fix: `JdbcPlacementHealthRepository#record` reads the state under `FOR UPDATE` in the caller's transaction, then writes, so a racing writer reports "not changed". Shared with dp-remote: `HealthPollerIT` 20, `HealthEndpointsIT` 8, `AgentReportIntakeIT` 25 green (a first attempt as one CTE failed those ITs, because `FOR UPDATE` in a CTE skips a row the same statement updates, and was replaced). Not proved under a true two-connection race.
- **MINOR 2.** Each enumeration is its own one-method class (`AgentSweepOrganizations`, `AgentStalenessTenants`); the guard uses `getAccessesFromSelf`, matched by assignability; planted method-reference fixture; mutation "calls only" red.
- **MINOR 3.** V022's revoke leg compares every column except status, revoked_at and the epoch. New V022 sha256 `b55a09b4247bdd1cab986dda26112b194164efa8076ab68a871f681cdb26d491`. IT `aSystemRevokeThatAlsoChangesAnyColumnBeyondTheRevocationIsRefused` (trust bundle, valid_to, fleet epoch); mutation red. The old test is renamed to say what it tests.
- **MINOR 4.** `TwoSchemaTestDatabase` turns both jobs off for every launcher IT. `AgentEnrollmentTargetIT` 9/9 green after the change; the earlier unexplained failure (seen once, in the mutation run on the tree before 89901347, with the sweep still on) did not recur, so it is consistent with the job racing the test, not proved.
- **MINOR 5-9.** `AgentStalenessJobTest` (5). The five dp-remote baseline ITs on the final tree: `ConfigLadderIT` 19, `PostRestartVerifyIT` 8, `RestartObservedGenerationIT` 23, `RollingApplyIT` 8, `StartObservationIT` 8, plus `IndeterminateReconcilerIT` 41 and `AgentAckBaselineIT` 8. BE-D34 blank line fixed; the BE-D71 clarification moved inside BE-D71 and reworded; `DpPackageCycleTest` widened to `dp.apply.runtime..` with the `OverlayKeys` edge frozen by name.
- **NITs.** README states the jobs run with the gateway off; property suffix unified to `poll-interval`; readiness registry check covers SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES; "seven" planted fixtures; Q-C1 to Q-C3 carry a pointer to `OR-140`; `JdbcAuditContext` and `JdbcAgentAuditContext` clear `sm.audit.actor_system`.
- **Results honesty (MINOR 7).** The first-round ITs ran on trees before the HF1 change; every IT named above was re-run on this final tree. `AgentJdbcWorld#stalenessJob` is a dp-core test-jar test-source addition. The integrator's gate supersedes all of these.

### T4.5 — Results (W4 lane D, `feat/agent-gateway/w4-retention-telemetry`, Sonnet 5.5; review pending)

- **Measured first: V029 already does everything T4.5 names, so there is no `sm_data` V032 for it.** `agent_gateway_maintenance()` (no argument, definer-owned, pinned `search_path`, advisory-lock no-op on a concurrent run) deletes final commands older than 30 days except the highest `wire_generation` of each (agent, placement), and non-ACTIVE leases expired more than 30 days ago; it never touches `agent_session`, `agent_event`, `agent_discovery`, `agent_identity` or a live command. `AgentSchemaIT` already pins those SQL shapes. V029 is not edited.
- **Built.** `PartitionRunway` (the daily loop that calls `ensure_metric_partitions()`) gains `DailyJob` (a functional interface) and a 6-argument constructor taking a list of them; `keep()` runs each job after the runway keep, whatever its outcome, and a job's failure is logged by class, is not counted as `RUNWAY_DEGRADED` and does not stop the next job. `MetricsRollupConfiguration` collects every `DailyJob` bean (`ObjectProvider`); the 5-argument constructor is unchanged. New `dp-core/.../apply/persistence/agent/JdbcAgentRetentionRepository implements PartitionRunway.DailyJob` calls `SELECT commands_deleted, leases_deleted FROM sm_data.agent_gateway_maintenance()` (nothing else, no tenant, id or time; I5), logs the two counts when nonzero; bean `agentRetention` added to `AgentGatewayPersistenceConfiguration` (an HF4-adjacent additive bean; `DataPlaneConfiguration` untouched, no new `@Import`).
- **Tests.** `AgentMaintenanceIT` (3): engine-minted commands through the loop (generations 1 and 2 aged 31 days: generation 1 deleted, the highest stays; the same intent again becomes generation 3); the loop keeps a 29-day-old inactive lease, an ACTIVE lease aged 90 days, a closed session aged 90 days, an old `agent_event` and an `ABANDONED` tombstone, and deletes the 45- and 31-day non-ACTIVE leases; the function has `pronargs = 0`. `PartitionRunwayTest` +2 (a job rides the loop and its failure is neither counted nor stops the next job; a failed runway keep still runs the jobs). `AgentGatewayCompositionIT` +1 (the composed launcher context's `PartitionRunway.dailyJobs()` holds the retention repository). Not red-first (tests after code). **Mutation:** `job.run()` commented out in `keep()` -> red (2 `PartitionRunwayTest`, 2 `AgentMaintenanceIT`), reverted byte-identical.

### W4 lane D, D2 — the declarations-change signal and the `AgentTelemetryDeclarationsPort` contract kit (`feat/agent-gateway/w4-retention-telemetry`, Sonnet 5.5; review pending)

- **HF18 addition (authorised by the integrator for W4): one default method.** `AgentTelemetryDeclarationsPort#onDeclarationsChanged(Consumer<UUID> affectedAgents)`, a no-op by default (Javadoc rewritten; no existing signature changed). It is a subscription: the data plane calls the consumer with the id of an agent whose declared set may have changed, AFTER the change committed; a notice is a prompt to re-read, never data; the same consumer registered twice is one registration.
- **Go agent, read only (the gate for the gateway push): it accepts a `TelemetryDeclarations` mid-session.** `go-agent/internal/agent/loop.go` handles `msg.TelemetryDeclarations` on any inbound `WireIn` as the loop receives it (line 228, `l.declare(m)` before the reducer), with no phase gate; `Declarations.Replace` swaps the binding's whole set atomically, a refused message leaves the previous set, and `session.go`'s wire reducer ignores the message. The existing `TestIntegrationDeclarationsArriveOnTheWire` and `set_test.go` cover the same path. So no Go change is needed and the gateway push was built. (The only measured caveat: no Go test sends a SECOND declarations message on a live session over the wire; the second message takes the code path of the first.)
- **Built.** dp-core: `DeclarationsChangeSink` (new, `dp.apply.events`); `ApplyEventEmitter#declarationsSink` and a `bestEffort` call in `settleLadder` after EVERY settle, moved or not (a placement add or retire changes the declared set without moving the rung; the funnel is called by the landing path, `InventoryService` add and retire, the agent report intake and the ack settler, none of which was edited); `AgentTelemetryDeclarationsService implements DeclarationsChangeSink`, keeps a `CopyOnWriteArraySet` of subscribers, defers to commit through the `TransactionBoundary` (`SpringTransactionBoundary` in production, immediate in the 5-argument constructor), reads nothing when nobody subscribed, finds the agents with `JdbcAgentTelemetryWatermarkRepository#agentsHolding(tenant, instance)` (ACTIVE leases whose `placement_ids` hold a placement of the instance, retired ones included; the instance comes from the data plane's own change, never from an agent: I5), and isolates a failing subscriber. `AgentGatewayPortsConfiguration` (HF4, additive) passes the emitter to the `agentTelemetryDeclarationsPort` bean and wires the sink. agent-gateway: `AgentGatewayService#startMaintenance` registers one stable consumer; a signal for an agent with a live session runs `ConnectSession#pushDeclarations` on the poll pool (never on the committing thread), which is the existing `sendDeclarations` diff (nothing differs, nothing sent; an empty set clears). The lease renewal remains the backstop for a lost signal.
- **The kit.** `AbstractAgentTelemetryDeclarationsPortContractTest` (dp-api test-jar), 7 cases: a stale session and a leaseless session are refused with their codes; a live active placement declares its metrics at the fixed 5 s cadence; an instance that is not active declares nothing; a placement with nothing active is declared with the EMPTY set while a retired one is absent; another organization's placement is never declared; a committed change is signalled once, to exactly the agent that holds the instance, and a re-read at notice time already sees it (the same consumer registered twice is one registration); a throwing subscriber neither fails the change nor silences the others. `JdbcAgentTelemetryDeclarationsPortContractIT` runs it over the real service, repositories and ladder settle as the runtime role. **Test-jar / test-source edits (HF18 test jar, allowed, listed for the integrator's conflict resolution):** `AgentKitWorld` gains `declarations()`, `declareMetrics(Agent, String...)`, `deactivateInstance(Agent)` and `changeDeclarations(Agent, UUID)` (all `default` that throw `UnsupportedOperationException`, as `renewLease` and `placementStart` do); `AgentJdbcWorld` implements them, hoists the emitter and the ladder recorder to fields and builds the real declarations service with the sink wired. The collection, ingest and placement rows behind the declarations are the world's own (`declareMetrics` writes the package definitions and generation 0's active list through `JdbcMetricDefinitionRepository`).
- **HF15 (`arch-tests`).** `PlaneBoundaryTest`: `AgentTelemetryDeclarationsPort` moved from `UNKITTED_AGENT_GATEWAY_PORTS` to `KITTED_AGENT_GATEWAY_PORTS` (two lines and the Javadoc of the unkitted set); the integrator rebases it after lane C's edit.
- **Tests and evidence.** Not red-first (tests after code). Focused run: `DeclarationsChangeSinkTest` 3, `JdbcAgentTelemetryDeclarationsPortContractIT` 7, `AgentTelemetryBridgeIT` 26, `AgentGatewayServiceTest` 44 (+1 `aDeclarationsChangeSignalPushesTheNewSetOnTheLiveStreamAndOnlyToTheAgentNamed`), `PlaneBoundaryTest` 15 + 26, all green. **Mutations (each red, reverted byte-identical):** Ma subscription not stored -> 2 kit cases red; Mc `settleLadder` does not signal -> `DeclarationsChangeSinkTest` 1 and 2 kit cases red; Md the gateway does not register -> the gateway case red. Me (drop the tenant predicate from the agent lookup) SURVIVED and was called an equivalent mutant; **that was wrong** (instances are keyed by `(tenant_id, instance_id)`, V007), and the fix round pins it with a kit case. The "after commit, not before" ordering is NOT mutation-proved: the world retires the placement as the owner (committed before the settle), so deferral cannot be seen to matter there; `SpringTransactionBoundary` is the class already proved by `SpringTransactionBoundaryDataSourceIT`.
- **Not done / owed (first version; see the fix round).** The signal reaches an agent's gateway only inside this replica (one process holds the agent's session); a second gateway replica is not told (the renewal backstop). T4.1's discovery confirm should call the funnel (note under T4.1). The T3.2 review note (2) (reject an empty unit at the source) is not part of this task.

### W4 lane D, D3 — agent gap markers (`OR-142` (3), section 10 item 23): MEASURED, AND THE PREMISE DOES NOT HOLD; no row written, nothing built beyond a pinning test (`feat/agent-gateway/w4-retention-telemetry`, Sonnet 5.5)

The task said "write a `metrics_gap` row through the existing gap path". **There is no such path.** Measured on `1b79a45f` plus lane D:

- No migration in `sm_data` or `sm_metrics` creates a `metrics_gap` table (grep of every `db/migration` file). The only `gap` columns are `rollup_1h/1d.max_gap_seconds` (V003), a spacing fact the query judges at read time.
- Gaps are **derived at read time** from the samples the data plane holds: `GapDeriver.raw` (spacing beyond twice the interval, reason `no-samples`) and `GapDeriver.rollup` (an absent bucket is `no-samples`, a bucket whose stored `max_gap_seconds` exceeds twice the interval is `partial-samples`) — plan 011 P11-6.1, DMR-21, discovery default D-3 ("the data plane derives its own gaps from the samples it holds; a producer's marker would be a second, unchecked claim about the same thing").
- `IngestBatch.gapMarkers` exists, but `IngestService` reads it only to REFUSE it from `DP_POLL` (`gap-markers.refused`); for every other discriminator, including `AGENT`, the markers are accepted and then ignored: nothing stores or reads one.
- What the data plane remembers per agent stream (`sm_data.agent_telemetry_watermark`, V028): `last_batch_seq` and `updated_at` (the data plane's own receipt time), nothing of a batch's `producedAt`. The bounding-batch rule (the instants of batches `fromSeq-1` and `toSeq+1`) would need a stored `producedAt` per remembered sequence, which does not exist.

**So the answer to "which, and why": no row is written.** The period an agent dropped (its buffer overflowed, so those samples never arrive) already charts as a gap: the samples are absent and `GapDeriver` flags a spacing above twice the declared interval, on the raw tier as `no-samples`. Forwarding a marker would add only a reason label, and building the storage to carry it is not "through the existing gap path": it is a new store plus a change to the query contract's gap semantics. I did not invent that. `agent.telemetry.gaps.not.forwarded` keeps counting every marker (now pinned by `AgentTelemetryBridgeIT#anAgentGapMarkerIsCountedAndNeverForwardedAndTheBatchIsStillAckedOnCommit`, which also asserts the ingest port receives no marker and the batch is still acked only on commit).

**Residual, stated honestly (and corrected in the fix round).** The raw tier flags a spacing beyond `max(2 × the largest declared interval of the chart's series, one bucket)` (`MetricsQueryServiceImpl`), so a drop shorter than that draws no band: one dropped 1-minute batch on a metric declared at 60 s leaves a 65 s spacing, below the 120 s threshold; on a wide range the bucket sets the floor; the rollup tiers judge the stored `max_gap_seconds` instead. That is the threshold working as ruled (`UI-D8`) and `OR-143` accepts it.

**What building it would cost, for the owner (option (a) was chosen: **ruled by `OR-143`**, which closes item 23; (b) is recorded as the road not taken):**
- **(a) Close item 23 as "derived gaps suffice"** — no code, no migration. Recommended: the chart already shows the hole, and a stored marker is a second unchecked claim (D-3).
- **(b) Build the bounding-batch rule** — `sm_data` V032 adds `last_produced_at` to `agent_telemetry_watermark` (the producedAt of the batch at the watermark); the bridge maps `fromSeq..toSeq` to `[producedAt(batch fromSeq-1), producedAt(batch toSeq+1)]` when the watermark equals `fromSeq-1` and this batch is `toSeq+1`, otherwise the bounds are unknown (a reset, a lost batch, a restart) and no row is written; `sm_metrics` V005 adds `metrics_gap(tenant_id, placement_id, from_ts, to_ts, reason)` with the ingest writer honouring `GapMarker` for `AGENT` only; `GapDeriver` and `MetricsQueryRows` merge stored bands into `gaps[]` (a Dynamic Metrics contract change, with its own review). About 6 to 8 hours with the review, two migrations, and a decision that `OR-142` (3) leaves open: whether a producer's claim may outrank the derived one. Not started; V032 and V005 are unused by lane D.

### W4 lane D, D4 — the agent telemetry counts bound to a meter (T3.4 owed item (5); `feat/agent-gateway/w4-retention-telemetry`, Sonnet 5.5)

- **Measured first.** No other dp-core count is bound to a meter: `CollectorCounts` writes `sm_metrics.ingest_counter` rows, `AgentTelemetryCounts` and `RunwayDegradation` are in-process; dp-core has no Micrometer dependency (and a pom edit is HF13, nobody this wave); the only `MeterRegistry` in the reactor is the one `AgentGatewayMetrics` holds (the Spring one when present, else a private `SimpleMeterRegistry`). So there is no dp-core pattern to follow; the binding follows the gateway's.
- **Built (no new dependency coordinate, no pom change; `micrometer-core` is the already-confirmed `OR-135` coordinate agent-gateway declares).** `AgentTelemetryCounts.NAMES` (closed list of the eight count names). `AgentGatewayMetrics#bindCount(name, source, reading)` registers a `FunctionCounter` on its registry. `launcher/.../AgentTelemetryMetersConfiguration` (a new scanned `@Configuration`, present only when `sm.agent.gateway.enabled`; the launcher is the one place that sees both modules, and `HF17` is untouched) binds each name as `agent_telemetry_<name with dots as underscores>_total`, reading the live `AgentTelemetryCounts` bean at scrape time. Nothing an agent sends is a meter name or tag (I6).
- **A defect found and fixed on the way.** Micrometer holds a function counter's source object WEAKLY; my first version passed a lambda, which is collected, so the meter fell back to 0 (seen as an intermittent `expected 3 but was 0` in a full-class run). The signature now takes the owning bean as `source`, and the Javadoc says why.
- **Tests.** `AgentTelemetryCountsTest` 2 (every public name constant is in `NAMES` exactly once); `AgentGatewayMetricsBindTest` 1 (live read, and still correct after `System.gc()` while the owner is referenced); `AgentGatewayCompositionIT` +2 (the composed context has a function counter for every name and it moves with the count; a dp-remote-only install has no gateway registry to bind to). **Mutations, each red and reverted:** a name dropped from `NAMES` -> `AgentTelemetryCountsTest` and the composition case red; the bind call commented out -> the composition case red. Not mutation-tested: the weak-source regression (a GC-dependent test would be flaky); the defect and its fix are recorded above and the signature makes the wrong call shape impossible to write silently.

### W4 lane D — fix round, as merged (recorded by the integrator at W4 integration, 2026-10-12; review `w4d-review.md`; commits `b6fb3afd`, `d52184b6`)

The D2 Results above describe the first version and are superseded where they differ:
- **Finding 1 (MAJOR).** The agents told are those **enrolled on the targets** of the instance's placements (`JdbcAgentTelemetryWatermarkRepository#agentsOnTargetsOf`; `agentsHolding` is gone), because a lease's `placement_ids` is a snapshot. The gateway then reconciles that one agent (`reconcileOne`), and `JdbcAgentIdentityRepository#reconcile` answers `RENEWAL_REQUIRED` when the stored lease's placement set differs from the target's live set (same predicate, order and 64 cap as `JdbcAgentLeaseRepository#livePlacements`). The sink is also told from the paths that do not settle the ladder: `InventoryService#activate` and `settlePending` Rule 1, the three retirement transitions and the dp-remote `StartObserver`.
- **HF7 and the retirement service (integrator ruling, recorded here):** those `InventoryService` one-liners (`events.declarationsChanged(...)`) and the `DeclarationsChangeSink` parameter of `InstanceRetirementServiceImpl` were authorised for lane D in its fix-round brief, after A and C in the HF7 order.
- **Findings 2 to 9:** the kit reads on another thread (after commit is observable); a two-organization same-`instance_id` kit case pins the tenant predicate (the "equivalent mutant" claim above was wrong); pushes are coalesced per session; the daily jobs catch `Throwable` and the retention call has a 300 s query timeout; `AgentMaintenanceIT` holds the real advisory lock on a second connection; meters are `dp_agent_telemetry_*_total`; exactly one retention job is composed; T4.5 checkboxes ticked. Not taken: the 5-argument `IMMEDIATE` constructor stays (tests only).
- **Kit:** `AbstractAgentTelemetryDeclarationsPortContractTest` has 11 cases, not 7.
- **Integrator fix `d52184b6`:** the renewal check took the 64-cap subset in Java's signed UUID order while the lease mints it in PostgreSQL's unsigned `ORDER BY`. On a target past the cap every pass asked for a renewal that could not settle it. It now orders in SQL. Red first: a kit case with 70 placements answered `RENEWAL_REQUIRED` on a fresh lease.
- **Gate on `b6fb3afd`:** BUILD SUCCESS, 0 failures (integrator's run, 07:47 to 09:01); `d52184b6` is covered by the W4 integration gate.

### W4 integration (2026-10-12)

- **Order on `w4-integration`, off `1b79a45f`:** A → B → C → D (`6e90bc4f`, `849b0306`, `a99ce7de`, `cf91c5e0`), per the W4 hot-file amendments. Conflicts: the plan's Results (every side kept), `AgentGatewayPortsConfiguration` (C's staleness job and A's discovery bean, both kept), `AgentJdbcWorld` (A's real discovery and D's emitter and declarations service; the stub dropped). Lane C's fix-round commit was reworded before the merge (`bbf5f21d`, tree identical to the lane's `c656f42f`).
- **Lane gates before the merge** (integrator's runs, `-pl <lane modules> -am clean verify`): B on `776e338a`, D on `b6fb3afd`, A on `35a463d8`, all BUILD SUCCESS with 0 failures; C covered by the integration gate. Lane checks (`run-checks.sh --strict`, frozen, licences, protoc, Postman, diff-check) passed on all four.
- **Integrator commits:** `d52184b6` (lane D: the renewal check orders the capped placement subset in SQL; red first); `d5ca8434` (the `PackageDeclarationSource` kit in dp-api, bound by console-gateway's `AgentPackageDeclarations`; console-gateway takes the in-reactor dp-api test-jar at test scope; a cross-organization cache mutation red); `2fb8806b` (console.yaml: the scheduled sweep retires an interrupted enrolment; the A/D seam is `InventoryService#activate`, pinned by `AgentDiscoveryIT#aRegisteredContainerSignalsItsAgentThatItsDeclarationsChanged`, with the activation's signal removed red); `3995a200`, `8292701e`, `48d16575` (seam review F1, below); `2dab92d0` (plan records, seam review F2 and O4).
- **Seam review** (Opus, `w4int-review.md`: 0 BLOCKER, 1 MAJOR, 1 MINOR, 1 NIT, plus O1–O4 outside the brief). **F1 MAJOR, fixed:** `revokeAgent` writes only the control plane and the data plane's identity learned a revocation only from a live session's reconciliation, so an OFFLINE agent's target answered `409 AGENT_STILL_ENROLLED` forever. The retire gate now asks `InventoryService.AgentRevocation`, composed to `JdbcAgentIdentityRepository#revokedApplyingTheControlPlane` (reads the control plane's registration and applies a revocation exactly as a reconciliation does, in the retire's transaction; an unreadable control plane refuses, fail closed). `AgentTargetEndpointIT`'s retire case now enrols a real agent and revokes it through the API with no session; red on the pre-fix code (`409` after the revoke), green after (5/5). **F2, O4:** plan records corrected (`2dab92d0`). **O3:** the renewal check's SQL carries the tenant predicate.
- **Owed, from the seam review:** **F3 (NIT)** a draining session can still be prompted to renew and be re-leased (only `pushDeclarations` checks draining): settle with T4.4's real-agent drain. **O1 (MINOR)** a discovery row can stay PENDING when the pending sweep's Rule 1 activates a control-plane-registered instance and the agent next reports after `abandon-after`: T5.2 runbook, or a later wave. **O2 (MINOR, reasoned only)** `reconcileOne` reads the control plane on the shared command-poll pool once per declarations signal; a stalled control plane delays command notices: measure in G-A1.
- **Full gate on `2dab92d0`** (`mvn -o clean verify -Dmaven.test.failure.ignore=true` under the docker lock, 1 h 22 min): BUILD SUCCESS with one failure, `AgentTargetEndpointIT`'s retire case (its agent existed only in the data plane, so the new gate treated it as unknown to the control plane, i.e. revoked); fixed test-side in `8292701e` and `48d16575` and re-run (5/5). Surefire / failsafe: cp-api 55, dp-api 14, mesh-events 160, cp-core 647 / 522, dp-core 1595 (3 skipped) / 1736 (12 skipped), agent-gateway 84, console-gateway 1036, launcher 150 / 482 (44 skipped), arch-tests 171. Main code is unchanged after `2dab92d0`.
- **Other gates on `8292701e`:** `run-checks.sh --strict` ALL PASSED; `check-frozen-migrations` and its self-test; `check-licences` and both licence self-tests (no coordinate added); protoc; `check-postman` (78 operations, no drift, after regenerating for the console.yaml wording); `git diff --check`.
- **New migrations:** `sm_control` V022 (`system_actor`, lane C, `OR-140`) and `sm_data` V031 (`agent_discovery_release`, lane A); anchored at `48d16575` in the follow-up commit.
- **Pushed:** `integration/agent-gateway` fast-forwarded `1b79a45f..48d16575` (32 commits).

### T5.2 — Results (2026-10-12, `feat/agent-gateway/w5-runbooks`, lane B, Sonnet 5.5; documentation only, no production code, no build run)

- **Runbooks (new files; the previous revision on `dp-agent-integration` was SQL-provisioned and is superseded, not ported):** `docs/runbooks/backend/enable-agent-gateway.md`, `restart-the-gateway-with-a-drain.md`, `revoke-or-change-an-agent.md`, and `release-the-images.md` (the plan's "release runbook"; none existed). All four are marked `UNTESTED`: the end-to-end run of the first three is G-A1 (T5.1). They describe the API path (`issueAgentEnrolment` to the first start of `sm-agent` with the token in `SM_AGENT_ENROLL_TOKEN`; **there is no `sm-agent enroll` subcommand**, the plan's wording notwithstanding), the container labels (`com.solace.sm.instance`, `com.solace.sm.config-file`, `com.solace.sm.health-port`), the immutable envelope, the verbs as built in W4 (paths, permissions, codes read from `contracts/openapi/console.yaml`), `retireTarget` after `revokeAgent` with the agent offline (W4 integration F1), the two scheduled jobs and their properties (`OR-140`), the TLS reverse proxy (`OR-136` (4)), the role passwords (`OR-138`: read from `runtime/scripts/init-secrets.sh`, `compose.yml`, `runtime/launcher/bin/sm-tool` and `sm-secrets.sh`, described as they are) and the T3.2 note (recognise a stranded first-issue agent by `sm_data.agent_event` rows (**corrected in the fix round below**: `gateway.refusal` / `LEASE_RENEW_REQUIRED` / `renewal-timeout-<uuid>`; the first draft named a string that is not stored); clear it by revoke, retire, delete the agent's data directory, re-enrol).
- **Measured for I2 (the lease-nonce key).** With `keytool` and a Java probe that reads the entry the way `Pkcs12LeaseNonceSigner` does (`KeyStore.getKey`, `getFormat()`, `getEncoded()`), on **Microsoft OpenJDK 17.0.17** and **Homebrew OpenJDK 21.0.12.1** (keystores written by either read by both; the base image's JRE is 21): `keytool -importpass -alias lease-nonce-hmac -storetype PKCS12 -keystore <p12>` with piped input reads one line for the secret (and one line for the keystore password first when `-storepass` is not given) and writes a `SecretKeyEntry`, `PBEKey`, format `RAW`, whose `getEncoded()` length equals the number of characters of the secret: 64 for `openssl rand -hex 32`. It adds the entry to a keystore made by `openssl pkcs12 -export` (OpenSSL 3.6.4) beside the CA key, and refuses an existing alias. It also accepts a 31-character secret, which the application refuses (minimum 32 bytes), so the runbook says to use at least 32 characters. The exact commands in the runbook were run as written on both JDKs. **Not measured:** the JRE inside the image; the interactive prompts; the application reading the keystore (the unit test `Pkcs12LeaseNonceSignerTest` measures that; my probe mirrors its calls).
- **Also verified (I2):** GPL v2 section 3(b) wording (read from gnu.org); `github.com/adoptium/jdk21u` is an unmodified OpenJDK 21 mirror; the Ubuntu and Debian archive pools are reachable and are archive pools (the source packages within them were not confirmed); a `docker network inspect --format '{{(index .IPAM.Config 0).Gateway}}'` template works (verified on a default Docker network; the `sm_default` network name is derived, not observed).
- **Owed items recorded in the runbooks.** *Discovery tombstone bound (lane A's item):* there is none. Keyed (agent, label, container name); nothing deletes a row (`agent_gateway_maintenance()` never touches `agent_discovery`; the runtime role has no `DELETE`); bounded per report (64 from the agent, 256 looked at) and by deduplicated no-row refusal audit, but not in total; the runbook names what to watch (a read-only count by state) and that deleting a tombstone is unsupported; a retention rule is an owner decision. *W4 integration O1:* reasoned from `AgentDiscoveryService` and `InventoryService#settlePending` (not reproduced): the row stays `PENDING`, the agent gets no placement id for it; recognition by a read-only query; no in-place clear exists (undeclare and relabel, or revoke and re-enrol). *Single-replica limits:* the drain port and the declarations signal reach only the replica holding the session; stated in the drain runbook and the enable runbook. *W4 F3:* a draining session can still be prompted to renew; stated as a known limit.
- **Release runbook:** the build commands carry no `-Dprotoc*` (T1.2 note (a)); the NOTICE obligations including the OpenSSL clause 3 acknowledgment (note (b)); the corresponding-source offer line for both images (`OR-127` L4), **a draft whose contact and wording are the owner's**. `runtime/launcher/NOTICE` now points at the runbook. **The agent image's NOTICE and `scripts/gen-notice.sh` still say the offer is owed**: they are agent-PR paths, so the fix is a patch (`go-agent-w5b-docs.patch` in the session scratchpad; it also refreshes `go-agent/README.md`), for the integrator to apply on `integration/go-agent` first. `git apply --check` is clean on this tree; `gen-notice.sh --check` was not run (it needs the Go module cache).
- **Other edits:** `README.md`, `backend/README.md`, `runtime/README.md`, `docs/runbooks/README.md` (forward pointers and the path's status: integrated through W4, live gates pending, **not counted served**); `docs/design/DECISIONS.md` items 44, 45, 47 narrowed by a forward pointer only (statements unedited, `I3`). The two PR descriptions are drafts in the session scratchpad (`pr-agent-description.md`, `pr-backend-description.md`), not in the repo.
- **Not verified / for G-A1 to record:** that the runbooks' steps work end to end; the real-agent revocation timing on this tree (the 891 ms figure is the earlier branch's); the first-report latency of auto-registration; the `SM_AGENT_CA_*` keystore path inside the image; the agent's behaviour when a stored identity and a new token are both present (the runbook says to delete the data directory, derived from `State.Enrolled`, not run); `docker network inspect` for the `sm` project.
- **Gates:** `git diff --check` clean at each commit. No contract, migration, pom or Java file touched; `contracts/run-checks.sh` not run (nothing under `contracts/`).

### T5.2 — fix round (2026-10-12, review w5b-review.md: 0 BLOCKER, 7 MAJOR, 14 MINOR, 4 NIT; Sonnet 5.5; documentation, one compose edit, one contract description line)

Integrator rulings applied as given. The plan's T3.2 note text is left unchanged (it is history); the correction is here.

| Finding | Disposition |
|---|---|
| **M1** stranded agent query | Fixed. The row actually written is `code = 'gateway.refusal'`, `detail_code = 'LEASE_RENEW_REQUIRED'`, `request_id LIKE 'renewal-timeout-%'`: confirmed in `AgentGatewayService` (the audit call), `JdbcAgentAuditRepository.record` (writes `code`, `detail_code = code.wire()`, `request_id`; the `subject` argument, `agent-renewal-timeout`, is **never persisted**) and `GatewayCode` (`LEASE_RENEW_REQUIRED` has no separate wire string, so `wire()` is the name). `enable-agent-gateway.md` carries the corrected query and says the string is not stored; the backend PR draft and my T5.2 Results line are corrected. Not read from a live table. |
| **M2** health-port and a containerised agent | Fixed in the runbook and the go-agent patch. The label is a **host** port (the probe dials `127.0.0.1:<host port>`); the agent probes and scrapes `127.0.0.1`, so the supported shape is the host binary, or a container with host networking (`--network host`, Linux; Docker Desktop's host networking is not the supported shape). Reasoned from `go-agent/internal/runtime/docker`, not run; routed to T4.4 / a later gate. |
| **M3** compose does not pass what the runbooks name | Fixed. `runtime/compose.yml` now has `SM_AGENT_GATEWAY_ENABLED: ${SM_AGENT_GATEWAY_ENABLED:-true}`, `SM_AGENT_GATEWAY_DRAIN_WINDOW: ${...:-30s}` and `SM_SHUTDOWN_TIMEOUT: ${...:-60s}` (all three are mapped in `application.properties`; verified with `docker compose config`, defaults and an override of the switch and the timeout). `stop_grace_period` stays hard-coded (the runbooks say to edit it with the others). `SM_AGENT_GATEWAY_DRAIN_RECONNECT_MIN` is removed: the key is the property `sm.agent.gateway.drain-reconnect-min` (and `-spread`), not mapped to any variable. `SM_DP_AGENT_MAX_PLACEMENTS_PER_TARGET` is mapped but not passed (stated). Rollback step 1 now works from `.env`. `runtime/README.md`'s stale "until then the launcher ignores them" is corrected. What reads `compose.yml`: no Java or arch test (grep of `backend/` finds only `application.properties` comments and the Testcontainers/spike compose files of other things); `runtime/scripts/init-secrets.sh` calls `docker compose -f compose.yml` (rotate); `ContainerPackagingIT` is T4.4's and not in this tree. |
| **M4** keystore secrets | Fixed. `umask 077`; `openssl rand -base64 32 > agent-ca.password`; `openssl pkcs12 ... -passout file:agent-ca.password`; `openssl rand -hex 32 \| keytool -importpass ... -storepass:file agent-ca.password`; no `export`, no `pass:`, no typed password. The trailing-newline trap is stated and avoided. **Re-run exactly as written on JDK 17.0.17 and 21.0.12.1**: rc 0, a 64-byte `RAW` entry, the CA key readable with the same password, the file `-rw-------`; the old stdin form on a newline-terminated file reproduces `rc=0` + a listed `SecretKeyEntry` + `Get Key failed: Empty key` on both. **Not done:** `keytool` of the launcher image's own JRE. The base is `eclipse-temurin:21-jre-jammy@sha256:f04f...` (the tag carries no patch version) and the image is not in the local Docker cache; reading it would need a pull and a container. Written into the runbook as an explicit pre-merge item with the command (m9). The bootstrap-hash step (`backend/README.md` step 4 types the secret into a shell command) is noted in the runbook's preconditions; the claim "no password is typed anywhere" is narrowed. |
| **M5** tombstone claims | Fixed. The key is `(tenant, agent id, label, container name)`; a re-enrolment (new agent id), a renamed container and a changed label are each a new key and register afresh, **including instances undeclared on purpose**; only `REFUSED`/`ABANDONED` release, on a re-created container; to keep a container out of management remove its label. Behaviour is not changed. Owner question added: **section 10 item 24**. |
| **M6** certificate expiry | Documented, not designed: a "Before an agent certificate expires" section (how to read the expiry: `enrolledAt` plus the ceiling, or `<data-dir>/identity/cert.pem`; what happens; the full revoke-undeclare-retire-reissue cycle; its consequence: new instances). Owner question added: **section 10 item 25**. Reasoned from the code; not run. |
| **M7** trusted-proxy order | Fixed by reordering: configure with a placeholder `SM_TRUSTED_PROXY` (step 1), bootstrap and migrate (step 2), read the network and replace it (step 3), start (step 4). **Measured:** with `SM_TRUSTED_PROXY` unset, `docker compose --profile bootstrap config` and `--profile migrate config` both fail ("required variable ... is missing a value"), so a placeholder is needed. Not run: `docker compose run`; that the network exists after step 2 is reasoned. |
| **m1** key rotation | Fixed: an agent holding a nonce still renews; only a lost-reply replay fails and the agent receives `RE_ENROLL_REQUIRED` and halts; the operator revokes and re-enrols (read from `JdbcAgentLeaseRepository` and `classify.go` via the review; not run). |
| **m2** drain audit | Fixed in the runbook (the row is `gateway.drain` / `DRAINING` / `gateway.drain-<uuid>`, not distinguishable from a planned-restart drain; the sentence is detached from the 404 bullet). The `drainAgent` description in `console.yaml` is corrected, Postman regenerated (`generate-postman.py`: 78 operations; `check-postman.py`: OK, no drift) and `contracts/run-checks.sh --strict`: **ALL CHECKS PASSED** (needed temporary symlinks to `contracts/view-models/node_modules` and `SM_FRONTEND_NODE_MODULES` pointing at another worktree's `frontend/node_modules`, because this worktree has none; the symlink was removed). **Owed to the Final:** the `AgentOrchestrator` comment (about line 139), Java main code. |
| **m3** sweep scope | Fixed in both runbooks: the sweep takes enrolments interrupted between the two planes; an unused token (`pending`/`expired`) is never swept and holds its name until `revokeAgent`. |
| **m4** item numbering | Fixed: "plan 015 section 10, item 21" in the runbook and the PR draft (central item 21 is a different, closed item). |
| **m5** `.env` quoting | Fixed: single-quote the hash in `.env` (Docker's interpolation rule read on docs.docker.com). `.env.example` not touched. |
| **m6** stale README text | Fixed: `README.md` and `backend/README.md` say 78 operations (77 counted served), `sm_control` V022, `sm_data` V031, `sm_metrics` V004, all anchored; `runtime/README.md` corrected (M3). |
| **m7** agent patch | Rebuilt: `--telemetry-config` optional and development-only (table and Telemetry paragraph); runbook references are plain text ("land with the backend PR"), no dangling link; host networking, data directory ownership and the health-port note added. `git apply --check -p1` clean; `gen-notice.sh --check` run in a throwaway copy with the patch applied (module cache only, `GOPROXY=off`): ok, 32 modules. |
| **m8** NOTICE wording | Fixed in `runtime/launcher/NOTICE` and the patch: the offer is "owed pending the project owner's approval", no repository path, no claim that it is shipped; the release runbook says to put the approved text into both NOTICE files (GPL v2 3(b): the offer must accompany the binary). |
| **m9** keytool in the image | Owed, stated as a pre-merge item with the command (see M4). Could not be done without a pull and a container. |
| **m10** `agent_event` reading | Fixed by adding a read-only `agent_event` query and the column meanings to `revoke-or-change-an-agent.md`; the DECISIONS 45 pointer stands. |
| **m11** agent data directory | Stated (the `nonroot` user must be able to write the volume; reasoned, not run) in the runbook and the patch. |
| **m12** PR drafts | Fixed: the split-check claim says what was recorded; the `gen-notice.sh --check` claim says what was run; the stranded-agent wording follows M1; the new section 10 items, the compose edit and the owed items are added. |
| **m13** `undeclareInstance` | Fixed: `DELETE /api/dp/instances/{instanceId}?workload=leave-running` (required), and the rollback step undeclares before retiring. |
| **m14** gateway off in compose | Fixed: compose still requires every `certs` file and `SM_AGENT_ENDPOINT`. |
| **n1** "healthy about 30 s" | Dropped; the HEALTHCHECK intervals are stated and the time is marked not measured. |
| **n2** `sm_metrics` head | Added (V004). |
| **n3** DECISIONS 44 pointer | "T3.4" removed (T3.3, T3.5 and the W3 integration). |
| **n4** README wording | "not merged into UAT". |

**Compose diff (the whole of it):** `SM_AGENT_GATEWAY_ENABLED: "true"` becomes `${SM_AGENT_GATEWAY_ENABLED:-true}`; two lines `SM_AGENT_GATEWAY_DRAIN_WINDOW: ${SM_AGENT_GATEWAY_DRAIN_WINDOW:-30s}` and `SM_SHUTDOWN_TIMEOUT: ${SM_SHUTDOWN_TIMEOUT:-60s}` plus one comment line are added. Nothing else.

### T5.1 — Results (2026-10-12, `feat/agent-gateway/w5-g-a1`, lane A, Sonnet 5.5; Steps 1, 2 and 4; Step 3 NOT run)

- **Verdict: G-A1 Steps 1, 2 and 4 PASS.** Evidence, redacted, with the per-step observations and the full log: [`evidence/015-g-a1.md`](evidence/015-g-a1.md). The real Go agent (built from `go-agent/` at this head, roles `control,telemetry`) enrolled against the production launcher with the gateway on, through the API only: token, enrolment, `listTargets` (`transport: agent`), the labelled Cache auto-registered (`OR-27`), a `restart-all` apply reaching the `restart-observed` rung, five `source_discriminator = 'agent'` series in `sm_metrics`, a `kill -9` of SM with the `RESTART` row `DELIVERED, received, not acked` (the apply resolves `APPLY_INDETERMINATE -> INDETERMINATE_RESOLVED`, three commands `ACKED`, one container start counted by a live `docker events` collector), `undeclareInstance` then an agent reconnect (the discovery row stays `UNDECLARED`, no re-registration after 30 s), `drainAgent` (back 2.2 s later, epoch 3 to 4), `revokeAgent` (session closed 3.4 s later, a fresh connect answered `IDENTITY_REVOKED`, no new session) and `retireTarget` (`200`). No product defect found; the launcher log of the clean pass has no `ERROR` line.
- **Built (harness only, no product code, no contract, no migration, no pom):** `backend/local/ga1-agent.sh` (PKI with the CA key and the lease-nonce HMAC entry, measured at 64 bytes through the JDK `KeyStore` API; gateway environment; broker and Cache on a private Docker network; agent process; `down`), `backend/local/ga1-scenario.py` (the steps through the API, database reads for evidence only), and two names in `sm-local.sh` made overridable (`SM_LOCAL_PG_CONTAINER`, `SM_LOCAL_PG_VOLUME`; defaults byte-identical, `test-sm-local.sh` ok). The harness uses its own PostgreSQL container `ga1-pg` on port 55461 because a long-running `sm-local-pg` of another session was found at the first attempt and was left untouched.
- **Re-run it (the integrator, for Step 3):** under the docker lock, with `tc-env.sh` sourced: `backend/local/ga1-agent.sh up && backend/local/ga1-agent.sh cache`, then `GA1_STEPS="setup enrol apply1 metrics" backend/local/ga1-agent.sh scenario` leaves an enrolled agent, a registered Cache and metrics flowing, and the target ON for a dp-remote placement comes from leaving `SM_LOCAL_TARGET_ENV` unset (sm-local's default `~/.sm-local/target.env`) at `up`. `GA1_STEPS` takes any of `setup enrol apply1 metrics killsm undeclare drain revoke retire`; `ga1-agent.sh down` removes everything (the Cache, the broker, the network, `ga1-pg`, the agent, `backend/local/.state/`). Stopping the agent for "no starvation" is `ga1-agent.sh agent-stop`.
- **Owed / not covered.** (1) **Step 3** (dp-remote coexistence, including with the agent stopped): needs the SSH target, the integrator's. (2) **F3** (a draining session re-leased) was not exercised: the drain here was between idle sessions. (3) **O2** (the control-plane read on the command-poll pool per declarations signal) was not measured: no stall was induced. (4) The `persisted` and `restart-observed` ladder hashes differ from the `desired`/`rendered` one at the same generation (first pass only); no rung was behind; not investigated. (5) `INDETERMINATE_RESOLVED` is the terminal state after the interrupted `RESTART`: whether that is what "settles once" means is for the integrator. (6) The first pass found three harness script defects (two format-argument bugs, a look-back margin that counted the previous restart); fixed, the clean pass is the evidence.

### T5.1 — fix round (2026-10-12, `feat/agent-gateway/w5-g-a1`, lane A, Sonnet 5.5; one round on the adversarial review of `2858392e..b0cc82ea`)

**Outcome.** The G-A1 local harness is rebuilt to the review's rulings and re-run live twice (docker lock, target OFF, E2E host untouched): every expectation is an assertion (45 in two invocations, all PASS both runs), and the scenario reports ONE measured FINDING with exit code 3 — **the crash-recovery path of the real agent is not the one `AgentCommandCrashRecoveryIT:175` proves** (M3, below). Evidence (redacted) and the unedited scenario log: [`evidence/015-g-a1.md`](evidence/015-g-a1.md), [`evidence/015-g-a1-run.log`](evidence/015-g-a1-run.log). No product code, contract, migration or pom was touched: `backend/local/{sm-local.sh,test-sm-local.sh,ga1-agent.sh,ga1-scenario.py,README.md}`, `.gitignore`, the evidence and this text only.

**M3 measured (for the integrator).** SM killed with the RESTART row `received, not acked`; the container restarted once; the three commands ended `ACKED`. After the restart: the `ApplyStartupRecovery` sweep ran ~4.7 s after the kill and logged `landed on APPLY_INDETERMINATE (ErrorClass[token=resume.state.unknown])`; the agent's new session opened 1.8 s (run 1) to 5.2 s (run 2) AFTER the sweep; the request ended `INDETERMINATE_RESOLVED`, `resolved_by=sm-reconciler`, `resolution_outcome=applied`, `reconcile_class` empty, ~34 s after the kill (a reconciler tick). The IT asserts `APPLIED` via `resumeRunning` with the session live; live, startup recovery cannot read the host before the agent reconnects. The outcome is correct; the route is slower and different. Open item (5) of the T5.1 Results is therefore a finding, not a question: the "settles once" observable holds (one container start, three acked commands, outcome `applied`), the terminal STATE is `INDETERMINATE_RESOLVED`.

| Finding | Disposition |
|---|---|
| **B1** shared `.state` | FIXED. `SM_LOCAL_STATE_DIR` in `sm-local.sh` (default unchanged); `ga1-agent.sh` uses `backend/local/.state-ga1` (gitignored). The PostgreSQL container name is recorded in `secrets.env`; `up/start/stop/seed/down` refuse a different one. `test-sm-local.sh` covers the override, the defaults, the refusal for each command and that a refused `down` removes nothing. |
| **M1** unasserted PASS claims | FIXED. `check()` raises; PASS/FAIL table; exit 1 on a failure, 3 on a finding. Asserted: apply state, start time moved, GREEN, ladder `restart-observed` at the generation, three commands `ACKED`, five agent series each with a sample since the apply (time-filtered), RESTART row received-not-acked at the kill, exactly one start event, `UNDECLARED`, no new session after revoke and at least one refusal line. |
| **M2** kill window | FIXED. The kill fires only on `received AND NOT acked`; a window missed (at the poll or in the row read once after the kill, which is frozen because the DP is dead) retries the whole step with a new generation, at most twice, then FAILS. The review's "drop the post-kill re-read" is applied to the 0.5 s "still so" re-read; ONE read immediately after the kill is kept as the authoritative row (a deliberate difference: it also catches an ack landing between poll and kill). |
| **M3** recovery outcome | MEASURED, not fixed; reported above and in the evidence. Compared with the IT at line 175. Exit code 3. |
| **M4** PGPASSWORD on argv | FIXED in `ga1-scenario.py` and `sm-local.sh` `psql_super`: `-e PGPASSWORD` by name, the value in the subprocess environment. |
| **M5** CA keystore password exported | FIXED. `gateway_env` runs only in the subshell that starts the launcher (`up`, `restart-sm`); the scenario environment holds none; the agent is started under `env -u` for every `SM_AGENT_(CA|GATEWAY)_*`, `SM_LOCAL_*PASSWORD`, `SM_CONTROL_*`, `SM_DATA_*`, `SM_DP_MASTER*`, `PGPASSWORD`. |
| **M6** ladder hashes / content | FIXED. Open item (4) CLOSED: `ConfigLadderView.java:10-13` says `persisted` and `restart-observed` are the keyed read-back hash, comparable only with each other, a different value from `desired`/`rendered` by design. Asserted: persisted hash == restart-observed hash at the same generation (never printed), and the Cache's `application.yml` holds each generation's `max-size` (match/no-match). The `degradedReason` is logged (null in both runs). |
| **M7** G-A2 reuse (local parts only) | DONE: `GA1_GOOS`/`GA1_GOARCH` (default host; per-target binary path; rebuilt when a go-agent source is newer; a non-host binary is built, not started locally); the agent dials `GA1_AGENT_DIAL` (default `127.0.0.1:<port>`); container probes (start time, id, `docker events`) fail loudly and take `GA1_PROBE_DOCKER_ARGS`, separate from the database calls; agent start/stop/alive/log are small functions plus `agent-log-size`/`agent-log-since` commands; fixed sleeps replaced by polls on the observed condition (the `docker events` collector is proven attached by a `top` canary). NOT done by design: remote/ssh/tunnel code, the token-on-stdin remote start, the remote Cache/broker, the host Cache start-time probe — the integrator's G-A2 driver. |
| **M8** `down` by name | FIXED. Every ga1 container, network and volume carries `com.solace.sm.ga1.run=<run id>`; `down` removes by that label only; an existing resource without it is refused, never adopted (`owned_or_die`; `sm-local.sh` `SM_LOCAL_PG_LABEL`). Names are resolved BEFORE `SM_LOCAL_ENV_FILE` is sourced and an env file that changes them is refused (tested). |
| **MINOR 1** partial PKI | FIXED: built in a temporary directory, verified, the keystore moved in last as the marker; the entry/length/expiry check runs on both paths; `keytool` errors shown. |
| **MINOR 2** plaintext CA key | FIXED: removed after the export. |
| **MINOR 3** broker password on argv | FIXED: `--env-file` from a 0600 file, deleted after `docker run`. |
| **MINOR 4** Cache config rewritten | FIXED: written only when the container is created. |
| **MINOR 5** PID trust | FIXED: `agent_alive` checks the process name is `sm-agent*`; `kill -9` only if still alive. |
| **MINOR 6** events watcher leak | FIXED: the collector registers an `atexit` terminate (any raise ends the process through it) and `stop()` runs after each attempt and at the end. |
| **MINOR 7** generated passwords printed | FIXED in `sm-local.sh`: the admin password is in the 0600 `secrets.env`, the operator password in a new 0600 `operator.pw`; the relative path is printed, not the value. README lines updated. `test-sm-local.sh` passes. |
| **MINOR 8** target default ON | FIXED: OFF unless `GA1_TARGET=on`. |
| **MINOR 9** evidence figures | FIXED: only figures from the log are stated (revoke close is a host-side poll latency; metrics are time-filtered; the manual launcher-log read is labelled). |
| **MINOR 10** positive control | OBSERVED agent-side (the agent's debug log: `reactor.Discovered` with effects after the new session), both runs; the DP's receipt is NOT observable in the database and is stated as not measured. |
| **MINOR 11** `test-sm-local.sh` | FIXED: new cases 5 to 7 (override named in the plan, defaults unchanged, refusals, env-file refusal). |
| **MINOR 12** Step 3 guidance | FIXED: Step 3 is measured in G-A2 per `OR-145`; the earlier "re-run with the target ON for Step 3" text is superseded by this round (the harness no longer registers a target unless `GA1_TARGET=on`). |
| NITs | FIXED: stale "sm-local.sh is unchanged" comments; `GA1_SCRIPT` absolute; the `pipefail` `grep` silent exits and the leftover `KeyLen.java` (temporary directory); certificate expiry checked; `ks.pw` added to the masked set; `packageVersion` no longer falls back silently (asserted an integer); SQL values go through `psql -v` binds; the plan wording "`sm-agent enroll`" corrected here (the first start with the token in the environment enrols; the driver is Python) ; the unedited redacted log is kept beside the evidence. |

**Harness defects the live runs themselves found** (fixed, re-run): the undeclare assertion over-read other sessions' REFUSED rows (now scoped, and the others asserted REFUSED); `sm-local.sh`'s label check used the container form for a volume.

**Live results.** Run 1 (`all`, then the remaining steps): steps 1 to 9 PASS with the finding; undeclare failed on the harness defect above; drain, revoke, retire PASS. Run 2 (`all` then `GA1_STEPS="undeclare drain revoke retire"` then `down`): 30 of 30 and 15 of 15 PASS, finding reproduced, `down` left no ga1-labelled container, volume or network and did not touch `sm-local-pg`, `oms-*`, Colima or `~/.testcontainers.properties`. `git diff --check` clean. Not re-run live after the last two edits (the `atexit` line in the scenario and the relative state-path wording in `sm-local.sh`'s printed messages): syntax-checked and `test-sm-local.sh` ok only.

**Owed.** Step 3 (G-A2, `OR-145`); F3 and O2 as before; the M3 finding for the owner of `ApplyStartupRecovery`/`AgentRuntimeAdapter` and for P15-3's reading of the IT; the remote G-A2 driver (M7 remainder).

### T4.4 — Results (2026-10-12, `feat/agent-gateway/w5-real-agent-harness` off `ef9f16ee`, lane C, Sonnet 5.5; focused runs under the docker lock `agw5c`; the integrator runs the module gate)

**Commits** (oldest first): `2834e065` profile `agent-live`, fail-closed prerequisites, the harness core; `9b4bf634`, `d8d333ee` the seven ITs, the synthetic fleet and the image stack ported; `7f4fe95c` the gateway fix below (own commit, red first); then eleven commits of live-run fixes (each named for what it fixed), the last two being `f7266a37` and the one that registers `FaultInjection.Decorator` explicitly and lengthens L2's wait.

**What was built.** `AgentLive` (prerequisites: `go`, a reachable Docker daemon, `alpine:3.17`, `solace-cache-engine:local`; **fails**, never skips), `AgentLiveExclusionNoticeTest` (prints, in an ordinary build, that the live classes are excluded), `AgentLivePrerequisitesIT`, `RealAgentHarness` (one PostgreSQL 14, one CA, N real `sm-agent`s, a data plane the test starts, stops and restarts, and, for the one case that needs it, starts as its own JVM and SIGKILLs: `startDpProcess` / `killDpProcess`), `FaultInjection` (decorates the command, report, lease, telemetry and session PORTS; the earlier single `AgentGatewayPorts` seam no longer exists), `MatrixEvidence`, `AgentTestSupport`, `SyntheticAgent` and `ContainerStack` (ported onto the amended wire and the secret-file runtime), the seven ITs, and `AgentRuntimeAdapterRealAgentContractIT`. `launcher/pom.xml` gains two profiles and nothing else: `agent-live-excluded` (`activeByDefault`, which Maven drops as soon as any other profile of this pom is activated; it excludes the nine live classes from failsafe) and `agent-live` (hands failsafe `sm.agent.live=true`). Measured with `help:active-profiles` and `help:effective-pom`: with no flag the excludes are in the build and the property is not; with `-Pagent-live` the reverse. `-Dit.test` overrides failsafe's excludes (a Maven rule), so every live class also calls `AgentLive.require()` itself. No dependency coordinate, no plugin, no migration; no `dp-api`/`dp-core` test-jar edit.

**Step 1, seeding through the product.** Every placement, instance and binding comes from OR-27 discovery of a container that carries `com.solace.sm.instance` and `com.solace.sm.config-file` with its config directory bind-mounted inside the token's envelope; every enrolment is `issueAgentEnrolment` (HTTP, as the environment's administrator) followed by the real agent's own enrolment with that one-time token; generations are `stageConfig`, applies are `submitApply`/`stopInstance`, a revocation is `revokeAgent`, a drain is `drainAgent`; telemetry declarations reach the agent by the data plane's push (no mapping is seeded). **No SQL INSERT of a placement, binding, generation or telemetry map remains.** What still touches a table, named: (1) **the install's operator steps**: the organization, environment, users and their bindings are `DeclareFixtures.seed` (owner SQL, as every launcher IT does) and the entitlement is `EntitlementMain`; the console has operations for these, none was used here. (2) **SQL GAP 1a: lease time-to-live and admission margin** (`RealAgentHarness#tuneLease`): `issueAgentEnrolment` fixes them server-side and no operation changes them; L1 and L2 need a 150 s lease. (3) **SQL GAP 1b: narrowing an agent's capabilities** (M12): no operation does it; the test updates the registration with the epoch bump an operation would make, and restores it in `finally`. (4) reads, to observe. The packaging and capacity drills seed (1) over the container's database as the superuser.

**Step 2, fail closed.** Done as above; outside `agent-live` the build log says so (`[agent-live] EXCLUDED ...`, from the unit test, in an ordinary run).

**Step 3, the run and the counts.** `mvn -o -f backend/pom.xml -pl launcher -am -Pagent-live verify -Dtest=NONE -Dit.test='RealAgent*,Agent*IT,ContainerPackagingIT,ExtraCommandUnderCpuPressureIT' ...` (the plan's pattern plus `ExtraCommandUnderCpuPressureIT`, which the plan's pattern does not match). **The branch's counts in the plan (10, 17, 4, 2, 4, 2, 13) are inflated: they count `@TestMethodOrder`/`@TestInstance` lines as tests (`grep -c '@Test'`). The branch's real `@Test` counts are 9, 15, 2, 1, 2, 1, 11.** Ours, on `f7266a37` (and the two classes re-run after the last commit, below):

| Class | Branch (real) | Ours | Added |
|---|---|---|---|
| `RealAgentDockerE2EIT` | 9 | **13** green | the RetryAfter retry (T3.4 note), drain with a command in flight (F3), a renewal in flight at the drain (F3), revoke end to end |
| `AgentFailureMatrixIT` | 15 | **17** green | M16 SIGKILL of the data plane after `CommandReceived` of a RESTART (data plane as its own process); M17 the graceful in-process stop of the same moment, kept for what it proves; M11 now runs on a second agent |
| `AgentLeaseExpiryIT` | 2 | 2 | |
| `AgentFleetDrainIT` | 1 | **1** green | |
| `ExtraCommandUnderCpuPressureIT` | 2 | **2** green | |
| `AgentCapacityIT` | 1 | **1** green (default 20 agents, 15 s phases) | |
| `ContainerPackagingIT` | 11 | **11** green | P09 (the maintenance job) is gone with `sm-maintain` (dropped by T2.4); a secret-file drill replaces it |
| `AgentRuntimeAdapterRealAgentContractIT` (new) | n/a | **26**, 0 failed, 1 skipped (the kit's own assumption: "this runtime does not express an artifact identity") | |
| `AgentLivePrerequisitesIT` (new) | n/a | 1 green | |

The same run executed every other `Agent*IT` of the reactor that the pattern matches (launcher: `AgentGatewayHandshakeIT` 3, `AgentEndpointsIT` 9, `AgentPlacementRefusalsEndpointIT` 3, `AgentTargetEndpointIT` 5, `AgentRegistrationCompleterIT` 4, `AgentDrainCompositionIT` 2, `AgentEnrollmentTargetIT` 9, `AgentGatewayCompositionIT` 11; dp-core 166), all green except the two failures reported under "last run" below.

**Step 4, redaction.** `RealAgentDockerE2EIT#noSecretPayloadDigestOrTokenReachesTheCapturedJavaOrAgentLogs` passes over the captured JVM output and the agent's debug log: the enrolment token, a payload marker staged into every generation, the CA keystore password, the PEM markers and the gateway key's first body line, the plain SHA-256 of each rendered generation, and the keyed digest of the applied config. Both logs are asserted non-empty so the scan is meaningful.

**Step 5 / the new cases.**
- **T3.4 note (RetryAfter).** The port answers one telemetry batch `DO_NOT_ACK, retryAfter 2 s` and commits nothing; the real agent offers the same `batchSeq` again, the watermark passes it, no `(series, ts)` is stored twice, the session stays ACTIVE. Green.
- **Real-agent crash (M16).** The data plane is its own JVM, SIGKILLed once the agent holds the RESTART; a second process finds the apply RUNNING. Measured: the container's Docker start moved **once**; exactly one RESTART command row (`ACKED:APPLIED`); the engine's recovery verdict is the LIFECYCLE attempt `TIMED_OUT:resume.state.unknown`, the request `APPLY_INDETERMINATE` and then `INDETERMINATE_RESOLVED` by `sm-reconciler` (resolution outcome `applied`). The test accepts `APPLIED` or `INDETERMINATE_RESOLVED` and nothing else. **M17** (the same moment, the context closed gracefully) was observed to land `CONFIG_DIVERGED` (the session is lost while the step runs, `agent.session.lost`, and `ApplyExecutor:553-558` maps the adapter exception to it); one start, same command. This is recorded, not blessed: it contradicts the adapter's own contract and the engine's `APPLY_INDETERMINATE` wording, and is owner question Q-T44-1 in section 10. M17 asserts none of the request state. (Fix round: see "T4.4 - fix round".)
- **Drain and revoke end to end** (E2E tests 9 and 12): a drain accepted with `202` is audited once (`gateway.drain` and `gateway.drain.complete`), the agent leaves and returns after more than the advised 1 s under a higher epoch, ACTIVE, one ACTIVE lease, the in-flight restart ran once; after `revokeAgent` the agent halts (`IDENTITY_REVOKED`) inside the revocation-check interval, `gateway.revoked` is audited once, no live lease remains, and a restarted agent is refused at the claim with no new epoch.
- **F3 (W4 integration owed), measured with the real agent; nothing changed.** `promptRenewalNow`, `pushLease` and `renew()` do not look at `drainingSession`. (a) A command in flight, a second container discovered inside the open drain window (which makes the lease stale and prompts a renewal): the agent answered `DrainComplete` (audited 3.0 s after the notice, with the 2.5 s slowed acknowledgement), two renewals reached the port 14 ms and 107 ms after the completion row (the agent's renewal, answered as it closed), leases 3 to 5, the agent reconnected 5.6 s after the drain request, one restart, exactly one ACTIVE lease afterwards. (b) A renewal held in flight for 4 s while the drain is requested 43 ms into it: the drain notice waits on the session monitor the renewal holds, is sent 4 s later when the renewal returns (audit rows 4 s after the request, `DrainComplete` 2 ms later), the agent reconnects after the advised delay (5.0 s after the request), a renewal on the new session follows, one ACTIVE lease, the agent not halted. **Harmless**: nothing strands, nothing runs twice; the visible effect is that a renewal in flight delays a requested drain by its own duration. No `AgentGatewayService` change for F3.

**Findings, with the evidence that surfaced each.**
1. **Gateway defect, fixed (`7f4fe95c`, red first).** `AgentGatewayService#ack` built `detail_code` as `ACK_DETAIL_CODE_ERROR:ERROR_CODE_<X>`; the data plane bounds `detail_code` to `[A-Za-z][A-Za-z0-9._]{0,63}` and stores anything else as `invalid.detail`, so **every refusal the real agent sent with an error code lost its reason**, and `AgentRuntimeAdapter`'s `TARGET_MOVED` classification (`"TARGET_MOVED".equals(done.detailCode())`) could never fire (the kit over the real agent failed `rematerializeIfCurrentExpectingAbsenceRefusesOverAFile` with `agent.command.refused.invalid.detail`). The error detail now travels as the bare code (`KEY_HASH_MISMATCH`, `TARGET_MOVED`). `AgentGatewayServiceTest#anAckDetailIsBounded...` asserts it (red before, green after; 51 tests). Edit: `AgentGatewayService` (not a hot file) and its test, nothing else.
2. **`runtime/compose.yml` cannot start the launcher: it supplies no data-plane master key.** `SecretConfiguration` fails closed at boot without `SM_DP_MASTER_KEY` and `SM_DP_MASTER_KEY_ID` (the first packaging run died on `SM_DP_MASTER_KEY_ID is not set`). The image drills pass a key in the environment; production accepts the environment form and the `_FILE` form (OR-85), and compose should use `_FILE` (fixed in the fix round). **Owed to the runtime owner / T5.2:** compose, `.env.example` and the runbook say nothing about it.
3. **The console refuses credential-bearing calls from the host unless a trusted proxy vouches for the channel.** Compose's model (`SM_GATEWAY_TRUSTED_PROXIES_0` = the address the proxy's connections arrive from, plus `X-Forwarded-Proto: https`) is what the drills use; Docker Desktop presents the host's connections from the network's gateway address (measured with a probe container). Runbook fact for T5.2.
4. **The management endpoint binds loopback unless `SM_AGENT_GATEWAY_MANAGEMENT_BIND_ADDRESS=0.0.0.0`** (the earlier harness's `..._BIND` does not bind to `management-bind-address`); compose sets no management variable at all.
5. **A renewal the control plane "refuses" with `LEASE_RENEW_REFUSED` halts the agent by design** (`SessionReject RE_ENROLL_REQUIRED`): the lease-expiry drill therefore injects `INTERNAL_UNAVAILABLE`, the code that leaves the agent holding its lease and retrying until it lapses (L1: `LEASE_EXPIRED` 149 to 150 s after the first refusal, a request under the expired lease ends `FAILED_NO_EFFECT` with no command, recovery by the renewal prompt, new work runs once).
6. **A cloned identity (M11) ends with one process halted, not two serving**: the lease nonce is single use, so the process that presents a consumed nonce is told to re-enrol (`RE_ENROLL_REQUIRED`). Which one loses is a race, so the drill runs on a second enrolled agent. Claims in 15 s: 3 (bound 20).
7. **After a DP outage longer than the lease (L2) the agent returns on its own backoff**: `min(5 min, 1 s * 2^attempt)` with full jitter (`go-agent/internal/agent/backoff.go`), so the next dial is anywhere in the first 256 s to 5 min. One run waited out more than the first form's 150 s; the wait is now 6 minutes and the return time is recorded. **Runbook fact for T5.2** (after a long outage fleet recovery is bounded by 5 minutes plus the admission limits).
8. **`DrainComplete` can be lost to a fast agent close when the gateway's handler is held** (M08 with the acknowledgement slowed 1.2 s: the agent answered and closed, the close overtook the unread answer; `drained: sessions=2 answered=1 forced=1`, `gateway.drain.complete` not audited, nothing lost: the command's outcome was already durable). Seen in two of the nine matrix runs made while fixing the drills (and not in the others: a race), so the drill asserts the durable facts and records the audit. A real, minor under-count, not fixed here.
9. **The revocation audit row (`gateway.revoked`) was absent once under load** (M15, a restart in flight, then the session ended by the reconcile pass); it is asserted strictly on an idle agent (E2E) and recorded under load. Not investigated further; owed.
10. **The engine has no standalone restart** (`OperationUnsupported[op=RESTART]`): a host restart is the second step of a restart-bearing apply (`rolling-no-validation`), so the earlier drills that enqueued a bare RESTART command by key are now real applies (PREFLIGHT, APPLY, RESTART), and "no standalone queued command while the agent is offline" is M09's measured behaviour (`agent.session.absent`, no row).
11. The "extra command under CPU pressure" no longer needs the read-back wait to be correct: with `sm.dp.agent.readback-wait=0` the engine's read-back attempt fails (`materialize.readback.unavailable` or `materialize.hash.mismatch`) and is retried, but command identity returns the SAME command, so no apply produced a second APPLY in 24 applies under load (12 and 12).
12. `sm-migrate` prints `migrated 0 (versions none — already at null)` on a no-op re-run (cosmetic).

**Hot files / test-jar.** HF13: `launcher/pom.xml`, the two profiles only. No HF3/5/6/7/9/18/20, no migration, no test-jar source edit (the launcher's own `DeclareFixtures` gained an optional extra-headers constructor on its HTTP client, additive). `AgentGatewayService` (+ test): finding 1. `runtime/README.md` still says `ContainerPackagingIT` "is not in this tree yet": it is now; the sentence is T5.2's.

**Owed.** (a) T5.2: findings 2, 3, 4, 7 and the README sentence. (b) `AgentCapacityIT` is a port, not a re-measurement: the earlier harness inserted command rows and ran thousands of agents, this tree has no such door (every command belongs to an apply's journal), so the load is real apply requests through the console, one in flight per placement, at the defaults 20 agents / 4 requests per second; the earlier numbers are not comparable. (c) The kit over the real agent takes about 28 minutes (a fresh container, discovery and lease per test); it is correct, not quick. (d) Findings 8 and 9. (e) The plan's `Agent*IT` pattern omits `ExtraCommandUnderCpuPressureIT`.

### T4.4 — fix round (2026-10-12, same branch, Sonnet 5.5; reviewer's findings in `w5c-review.md`; docker lock `agw5c-fix*`)

**Scope rule (OR-144).** No `cp-core`, `dp-core`, `cp-api`, `dp-api`, `mesh-events`, `console-gateway` main code, no `go-agent/**`, no `contracts/agent/v1/**`, no migration, no hot file beyond the HF13 profiles already there (the `agent-live` profile also gives **surefire** `sm.agent.live=true`; same pom, same profile, no coordinate). Main-code change: `AgentGatewayService` only (m-4a).

**Commits:** `709cc184` compose master key; the `AgentGatewayService` commit (m-4a, test red first); `c57596a7` test and harness fixes; `8b1e699e` idle-drain measurement, L1 backoff wait, plan edits; a final commit for this section.

**Red to green.** `AgentGatewayServiceTest#anUnspecifiedAckDetailCodeIsAnAbsentDetailCodeNotTheEnumName`: red before the fix (`expected: <> but was: <ACK_DETAIL_CODE_UNSPECIFIED>`, 52 tests, 1 failure), green after (53 tests in the class). The test-only changes have no red phase (they replace assertions); the new assertions were each seen to pass against the real agent below, and the compose change was verified with `docker compose config` (refuses without `SM_DP_MASTER_KEY_ID`; renders the secret and `SM_DP_MASTER_KEY_FILE` with it) and by running `init-secrets.sh` into a temporary directory (a 44-character file, no newline, decodes to 32 bytes, mode 0600, a second run changes nothing).

| Finding | Disposition |
|---|---|
| **B-1** compose cannot start the launcher | **Fixed.** `runtime/compose.yml`: secret `dp_master_key`, `SM_DP_MASTER_KEY_FILE=/run/secrets/dp_master_key`, `SM_DP_MASTER_KEY_ID` required (`:?`); `.env.example` gains the id; `init-secrets.sh` generates the key (32 random bytes, base64, no newline, 0600 or 0640 with `--group`, never overwritten); `ContainerPackagingIT` P10's filled env gains the id (it renders the file). The `_FILE` form is confirmed in `EnvironmentMasterKeyProvider` (`KEY_FILE`). Results finding 2 corrected. **Runbook text: patch, not applied** (the runbooks live on `feat/agent-gateway/w5-runbooks`, not on this branch): `/private/tmp/claude-502/-Users-parikshit-Work-Development-solution-manager/27a653be-74b4-460e-bfc6-46ef266da01b/scratchpad/w5c-runbook-fixes.patch` applies cleanly to that branch (`git apply --check` run in `agw5b`, nothing applied) and carries the master-key step with the back-up warning, plus m-10. `runtime/README.md` updated here. |
| **MJ-1** M17 "by design" | **Fixed in the tests and text; engine unchanged (dp-core out of scope).** Method renamed, scenario text and Results reworded, M17 now records the observed request state (observed again this round: `CONFIG_DIVERGED` with the command `ACKED:APPLIED`) and asserts only what is durable. Owner item **Q-T44-1** added to section 10, owed to Final's fix round under the full gate. |
| **MJ-2** M16 resolution not asserted | **Fixed.** On `INDETERMINATE_RESOLVED` it asserts `applied by sm-reconciler` (observed: `[applied by sm-reconciler]`). Both end states stay accepted. |
| m-1 renewal facts / `count == 1` | **Fixed.** The unique-index check is removed (V028 `agent_lease_one_active` guarantees it; cited in the code). E2E-9 asserts a renewal reached the port and every started renewal completed; both E2E-9 and E2E-10 assert an unexpired ACTIVE lease and an open session; E2E-10 asserts the held renewal was seen and completed. The synchronized Drain/forceClose observation (stuck handler holding the drain past the grace) is **owed**, routed beside O2 for G-A1; not changed here (agent-gateway behaviour, no test for it yet). |
| m-2 `gateway.revoked` absent (root cause) | **Owed to Final (dp-core).** Root cause as reviewed: `revokeAndClose` -> `closeSession` in the revocation transaction; an in-flight write then reaches `requireOwner`, which checks `sessionClosed` before `revoked` (`AgentAuthority:92-107`), answers `STALE_EPOCH` and `supersede()`s, so the later `REVOKED` finds `accepted=false` and writes no row (`AgentGatewayService:930-931`). Fix: test `revoked` before `sessionClosed`, or re-check revocation in the `STALE_EPOCH` branch. Security outcome unaffected; the DP-side audit is miscoded and one extra reconnect happens. Results finding 9 ("not investigated") is superseded by this. |
| m-3 finding 8 / F1 strict | **Test fixed; agent fix owed.** `AgentFleetDrainIT` no longer asserts `answered=3 forced=0` or the drain.complete rows: it asserts `sessions=3`, `answered+forced=3`, one `gateway.drain` per agent, and records the counts (this run: answered=3 forced=0, 3 rows). E2E-10's wait for the drain to finish also accepts the new epoch (it timed out once when the row was lost). **Go agent (finish with `CloseSend` and wait for the server's close rather than cancel): owed to the agent PR and Final; not edited.** |
| m-4a unspecified detail code | **Fixed** (`AgentGatewayService`, red then green above). |
| m-4b `GatewayCode` lacks `TARGET_MOVED`, `RENDER_CHANGED` (HF18) | **Owed**, stop-and-report: those refusals audit as `UNKNOWN` until `dp-api/agw` gains the members (HF18, Final). |
| m-5 M11 | **Fixed.** During the 15 s overlap both processes' `/healthz` are sampled every 250 ms and the test asserts they are never both `session_phase=ACTIVE` (observed 0 of 59 samples); the lease-count check is dropped (schema-guaranteed). The harness clone helper takes the admin port. |
| m-6 RetryAfter duplicate check | **Fixed.** Removed (`metrics_raw` primary key); replaced with "samples were stored after the refused batch" beside the watermark and delivery-count assertions. |
| m-7 exclusion notice | **Fixed.** `agent-live` gives surefire the property; the notice test prints INCLUDED under `-Pagent-live` (seen in the build log) and its javadoc now matches its body (it asserts nothing, and says so). |
| m-8 `gateway.revoked` race | **Fixed.** E2E-12 waits (bounded, 15 s) for the row before asserting one. |
| m-9 advised delay | **Fixed differently.** Close-to-reopen cannot be measured: the agent counts the delay from the Drain notice and reconnects as soon as the held command ends (measured: the reconnect came 3.2 s after the notice, with the session barely closed). The held drain's assertion is removed (it could not fail, and says so in the code); a new idle-drain step in E2E-10 measures notice to new session on the database clock: **1007 ms** with `drain-reconnect-min=1s`, asserted `>= 1000` and `<= 15000`. |
| m-10 runbooks | **Patch** (above): management bind address (default 127.0.0.1), the 5-minute full-jitter backoff (both runbooks), the cloned-identity text corrected to the measured halt. `runtime/README.md:64` fixed here. |
| m-11 hygiene | **Fixed.** Every property whose name contains `password` reaches the DP process through its environment (the relaxed-binding form), not `-D`; `create()` tears down the work directory (CA keystore, server key) and any `sm-m*` containers if it fails part-way. Not done: the redaction scan still omits the lease nonce and the agent's private key (owed, test-only, next harness change). |
| m-12 weak outcomes | M08 now records its request's terminal state; **L1** drops its always-true OR (the prompt is recorded) and now waits up to 6 minutes for recovery (it failed once at 90 s: the session closes at lapse, so the agent returns on its backoff, up to 5 minutes; same cause as finding 7); **M13**'s claim is reworded to what is asserted; M12 restores the capability list it read instead of a constant. **Not changed, with reason:** M12's fixed 4 s sleep before its negative check (a negative cannot be awaited; it is a bound) and its retry loop for the fresh request (no cause found); `AgentCapacityIT` recovery and command-completion assertions (a port of a measurement; owed). |
| m-13 `DeclareFixtures.seed` SQL | **Recorded.** The seed also inserts three `ssh-docker` targets and their `target_connection` rows (dummy host-key fingerprints); the exceptions to step 1's rule are organization, environment, users and bindings, **and** those targets. They are not placements, bindings, generations or telemetry maps. |
| NIT `containerStarts` window | Sub-second `--since` (9-digit fraction). The Docker VM clock drift remains: owed. |
| NIT kit status `sleep(300)` | Not changed (timing guess in the kit's adapter wrapper; the run is green); owed. |
| NIT M12 restore constant | Fixed (above). |
| NIT fault statics leak | Fixed: `RealAgentDockerE2EIT` resets `FaultInjection` after each test. |
| NIT `WriterAccess.RemoteSftp` in the kit's `ConfigLocation` | Not changed: unexplained, harmless to the run; noted for the kit's owner. |

**Per-class counts (`-Pagent-live`, docker lock, Docker context `default`, no Colima; no `sm-m*` container left behind):**

| Class | Result |
|---|---|
| `AgentFailureMatrixIT` | 17, 0 failed |
| `AgentFleetDrainIT` | 1, 0 failed |
| `ExtraCommandUnderCpuPressureIT` | 2, 0 failed |
| `AgentCapacityIT` | 1, 0 failed |
| `ContainerPackagingIT` | 11, 0 failed |
| `AgentLivePrerequisitesIT` | 1, 0 failed |
| `AgentLeaseExpiryIT` | first run **2, 1 failed** (L1: "timed out after PT1M30S waiting for the agent to recover", the backoff above); after the fix **2, 0 failed** (378 s) |
| `RealAgentDockerE2EIT` | first run **13, 1 failed, 1 error** (E2E-9: my close-watcher timed out, the measurement above was wrong; E2E-10: the drain wait, after E2E-9's failure left a 2.5 s ack delay in place and the `drain.complete` row was strictly awaited); after the fixes **13, 0 failed** (90 s) |
| `AgentRuntimeAdapterRealAgentContractIT` | 26, 0 failed, 1 skipped (the kit's own assumption, as before; 1685 s) |
| default-profile unit tests | `agent-gateway` 85, `launcher` 151, 0 failed (the pattern `com.solace.sm.agw.*Test,com.solace.sm.launcher.*Test`) |

**Deviations.** The runbook edits are a patch and not commits (the files are not on this branch). m-9 was measured with a new idle-drain step rather than by the reviewer's close-to-reopen. **Owed:** Q-T44-1 (owner, Final, full gate); m-2 (dp-core); finding 8 (go-agent, agent PR); m-4b (HF18); m-1's monitor/forceClose observation (G-A1, beside O2); the redaction scan's nonce and key; the `AgentCapacityIT` assertions; the VM clock drift in `containerStarts`; the kit status sleep.

### T5.3 — driver (dry run) (2026-10-12, `feat/agent-gateway/w5-g-a2`, lane D, Sonnet 5.5; harness only, no product code)

**Outcome.** The G-A2 host driver is built and dry-run locally against a stub host. Nothing of the E2E host was contacted or read: the driver reaches the host only through `$GA2_HOST_CMD`, and every run here used `backend/local/e2e-host-stub.sh` (same `run` / `put` / `tunnel` interface, `@ROOT@` and `@PREFIX@` substituted, a `STUB` banner on stderr). The real run is the integrator's.

**Built** (`backend/local/`): `ga2-host.sh` (`all`, `up`, `scenario`, `down`, `status`, the remote agent verbs, `docker`, `events-start/stop`, `conf-holds`, `snapshot`, `snapshot-check`, `evidence`); `e2e-host-stub.sh`; `ga2-dry-run.sh` (throwaway broker, synthetic password, stub host, no secret allowed in the log or in the stub's command log); `test-ga2-host.sh`; `ga1-scenario.py` hooks (`GA1_PROBE_CMD`, `GA1_EVENTS_CMD`, `GA1_CONF_CHECK_CMD`, broker host/VPN/username/namespace, `GA1_BROKER_PASSWORD_ENV` through `setConfigSecret`, `GA1_ALLOWED_CONFIG_ROOT`, `GA1_KNOWN_FINDINGS`, `GA1_MASK_ENV`, the four Step 3 steps behind `GA1_DPREMOTE=on`); two lines of `ga1-agent.sh` (`GA1_SCRIPT` and `GA1_CACHE` may be set by the caller); README section. G-A1's behaviour is unchanged with the new variables unset: `test-ga2-host.sh` asserts the stage body byte-for-byte and the G-A1 step order, and `test-sm-local.sh` passes.

**Dry run** (docker lock, Docker Desktop, target OFF, host-architecture agent, `solace-cache-engine:local`, synthetic broker password): every step except the four `remote_*` ones. `setup`, `enrol`, `apply1` (including the new checks: the real password stored through `setConfigSecret`, the designer shows `secretRef`, the response holds no literal), `metrics`, `killsm`, `undeclare`, `drain`, `revoke`, `retire`: **48 PASS, 0 FAIL, 1 FINDING** (`m3`, known, the run continued: exit 3). `remote_declare`, `remote_apply_agent_up`, `remote_apply_agent_down`, `remote_undeclare`: **NOT RUN** (no SSH-Docker target locally). The clean-up ran from the trap: agent, both labelled Caches, the run directory, the tunnel, the snapshot assertion (every pre-existing container byte-equal), the local stack; nothing of `sm-local-pg`, Colima, `oms-*` or `~/.testcontainers.properties` was touched. The synthetic password appears in neither the run log nor the stub's command log. Log (redacted, outside the repo): the scratchpad's `ga2-dry-run.log`.

**Defects the dry runs found** (fixed, re-run): the snapshot template failed on containers without a health check (`index .State "Health"`); a process-substitution descriptor leaked into the background agent and held the scenario's stderr open (stderr now goes through a temporary file and `redact`); `getHealth` read `not_exposed` for 24 s after the restart because the agent's report lags the apply (the G-A2 run reads until GREEN, bound 90 s, `GA1_HEALTH_WAIT`; G-A1 is unchanged: no wait).

**Not measurable locally** (owed to the real run): the four `remote_*` steps; the `uname -m`, memory, `ss` port and tunnel-port-closed checks (skipped under the stub); the existing Cache's health probe (no Cache at the stub's 8081; `GA2_EXISTING_HEALTH_OPTIONAL=1`); `put`'s `@ROOT@` substitution in a remote path and the real host command's stdin forwarding (assumed per the spec, exercised only by the stub); the linux/arm64 agent build (built by `up`, not started here).

### T5.3 — G-A2 Results (2026-10-12, integrator; `feat/agent-gateway/w5-g-a2`)

- **Verdict: PASS** — 69 checks, 68 PASS, 0 FAIL, 1 FINDING (`m3`, known, owed to Final under `OR-146` (2)). Evidence, redacted:
  [`evidence/015-g-a2.md`](evidence/015-g-a2.md). Steps 0–2 done; G-A1 Step 3 (dp-remote coexistence) measured here per `OR-145`: a dp-remote
  `restart-all` on an unlabelled Cache declared on the host's SSH target applied in 89 s with the agent ACTIVE (Cache A and the agent session undisturbed)
  and in 89 s with the agent STOPPED (no starvation).
- **The host's existing Caches untouched:** id, StartedAt and Status byte-equal before/after; health probe 200. Every container, file and port the run
  created was removed (labelled resources only; the run directory; the tunnel, its remote port verified closed).
- **Driver defects found on the real host and fixed** (each attempt left the host unchanged): `e2e-host.sh` redacts `@ROOT@`, so the root now comes from
  the caller and the host confirms it (`GA2_ROOT_PATH`); a dry run's stale baseline could be compared (baselines are now bound to their run; the stub keeps
  its own state); the password-literal check matched the plain username, which on this broker equals the password (now excluded by key, measured by
  JSON path, never by value).
- **Not covered:** the agent built at the W5 head before Final's engine and half-close fixes; Final's gate re-runs the live classes, not the host.

### W5 integration (2026-10-12)

- **Merged onto `506650f9`:** lane B (T5.2 runbooks, compose), lane A (T5.1 G-A1 harness + its fix round), the go-agent docs patch (first on
  `integration/go-agent` as `467d2fd4`, `gen-notice.sh --check` ok), lane C (T4.4 real-agent harness + its fix round, including the compose master key
  and the gateway's bare `detail_code`), the lane C runbook patch, `OR-146`, and G-A2 (T5.3 driver + evidence). Plan conflicts: appended Results kept in
  merge order.
- **Targeted gate (`OR-144`) on `db3943f0`** — upstream installed with tests skipped, then `-pl agent-gateway,launcher,arch-tests verify -Pagent-live`:
  agent-gateway 85/0; launcher surefire 151/0; launcher failsafe 554 run, 0 failures, 1 error, 45 skipped (the live-E2E walkthrough ITs, gated on their
  environment, as in every gate); arch-tests 171/0. The error was `AgentGatewayHandshakeIT` (its broker container's SEMP not up within 4 min, an
  infrastructure timeout); re-run alone: 3/3 green. `run-checks --strict`, frozen migrations and self-test, licences and both self-tests, protoc,
  Postman, `git diff --check`, `go test ./...`, `gen-notice.sh --check`, `test-sm-local.sh`: all 0. The G-A2 merge after the gate is scripts and docs
  only (`test-ga2-host.sh`, `test-sm-local.sh`, `diff --check` ok).
- **G-A1 PASS, G-A2 PASS** (evidence files `015-g-a1.md`, `015-g-a2.md`); the `m3` finding is fixed in Final under `OR-146` (2).
- **No migration in W5.**

## Final — engine fixes (`OR-146`)

*(Lane F, 2026-10-12, branch `feat/agent-gateway/final-engine-fixes`, merged into `final-integration` at `bf786149`; the Go half-close is `9bb1c88a` on `feat/go-agent/final-half-close`.)*

- **MJ-1 (`OR-146` (1)): the rule is "was the restart dispatched".** `ApplyExecutor`'s LIFECYCLE catch lands `APPLY_INDETERMINATE` when the adapter's exception
  carries `afterDispatch()` (the agent adapter marks it when the command was handed to the runtime and the wait ended with no terminal answer: a lost session, a
  timeout, an interrupt), and the terminal `CONFIG_DIVERGED` only when nothing was dispatched (no session, unresolvable placement, a refused or failed answer).
  `e2bd4071`. The reconciler then resolves the request from a read, as it already did for the SIGKILL case.
- **M3 (`OR-146` (2)): a reconnect grace in the startup recovery sweep.** An in-flight request on an agent target is *deferred* (a probe, `AgentSessionProbe`,
  says its placement is on an agent target whose session is not live) and a daemon thread polls the session every 500 ms, tenant-scoped; when it is live the
  request resumes normally and ends `APPLIED`, when the grace is spent it is resumed as it always was and lands `APPLY_INDETERMINATE` (no new failure mode).
  Property `sm.dp.apply.worker.agent-session-grace` (`SM_DP_APPLY_AGENT_SESSION_GRACE`), **default 30 s**, `0` disables. `3f103dc3`.
  *Why 30 s (G-A1 and G-A2 measurements):* SM answers HTTP 4 to 5 s after a kill, the sweep runs at about +4.7 s, and the agent's new session opens at +6.5 to
  +9.9 s, so the session returns 2 to 5 s after the sweep and at most about 10 s after the kill; 30 s is three times that, leaves room for the agent's own
  reconnect jitter and is no longer than the reconciler's own tick (about 30 s), so a request that never gets its agent back settles in about the time the old
  path took. `AgentCommandCrashRecoveryIT` restarts without a live session where the old test assumed one.
- **m-2 reverted at the data plane; the gateway answers instead.** `7c9561ed` (`requireSession` testing `revoked` before `sessionClosed`) broke the port
  contract the dp-api kits pin (a call on a session a revocation closed is refused `STALE_EPOCH`); `3c0e8daf` reverts it. `9438ad9a`: on a `STALE_EPOCH` write
  the gateway's `supersedeOrRevoke` asks `identity().reconcile(session)` once and sends Revoke (with the single `gateway.revoked` audit row) only on `REVOKED`,
  else the supersede; an unreadable standing falls back to the supersede. *The invariant it rests on is pinned in the fix round below.*
- **Resume-preflight behaviour change.** A resumed apply whose preflight fails (an agent not yet connected fails fast, `agent.session.absent`) used to land
  `FAILED_NO_EFFECT` ("no MATERIALIZE intent exists"), true only on a first run. `e2905101`: `APPLY_INDETERMINATE` when a non-PREFLIGHT intent exists, refined in the
  fix round to "...and an attempt was opened for it".
- **`GatewayCode` +2 (HF18, additive, authorised by the integrator): `TARGET_MOVED` and `RENDER_CHANGED`**, so refused acks carrying the two wire codes audit
  by name and not as `UNKNOWN` (W5 review m-4 (b)). `2e688a9a`. The names equal the proto's `ERROR_CODE_*` constants, which is how the gateway maps both ways. The
  `GatewayCode` naming is left as it is, deliberately: `RENDER_CHANGED` is the *wire* code the agent reports and `AGENT_PAYLOAD_RENDER_CHANGED` the data plane's
  own coded error for the same condition; a javadoc now says so, and renaming either would change a wire or audit string.
- **Go half-close (W5 finding 8).** `Conn.Close` half-closes (`CloseSend`), waits (2 s cap) for the gateway to end the stream in answer, then cancels; before,
  cancelling straight after the last `Send` reset the stream and a `DrainComplete` just sent could be discarded. `9bb1c88a`; `client_test.go` covers the lost
  `DrainComplete` and the timeout.
- **Red and green.** Red (the three fixes reverted by mutation, `laneF/red.log`): 4 failures in 3 classes, each tied to one fix —
  `LifecycleDispatchedFailureIT.aFailureAfterTheRestartWasDispatchedLandsIndeterminateUnderEveryRestartingStrategy` (MJ-1 reverted),
  `JdbcAgentSessionRepositoryIT.aCloseWriteAfterARevocationIsRefusedAsRevokedNotAsStale` (the m-2 reorder, since replaced by the gateway answer),
  `AgentCommandCrashRecoveryIT.aRestartTheAgentRanButTheDataPlaneNeverHeardAckedRestartsTheContainerExactlyOnce` and
  `...anAgentThatNeverReturnsInsideTheGraceLeavesTheRequestIndeterminateForTheReconciler` (the grace disabled; both saw `FAILED_NO_EFFECT` where
  `RUNNING` was expected). Green (`laneF/fix.log`, run after the three last fixes `3c0e8daf`, `9438ad9a`, `e2905101`): **354 tests in 30 integration classes, 0
  failures, BUILD SUCCESS**, finished 20:58 IST. (An earlier run, `green.log`, was of the tree before those commits and had the three failures they fix.)

## Final — review fix round

*(Branch `final-fixes` off `f6dc196f`, 2026-10-12; the independent review is `final-review.md` (Fable 5.1). Every change is its own commit.)*

| Finding | Disposition |
|---|---|
| **MAJOR-1** the grace thread outlives the singleton guard | **Fixed, in the worker (not on its pool).** `ApplyWorker.stop()` calls `ApplyStartupRecovery.signalStop()` (no further deferred resume starts), drains its own pool, then `awaitDeferredResumes(shutdownGrace)` and only then `guard.release()`: a resume in flight gets the shutdown grace, is interrupted once, gets the grace again (the pool's own rule); if the thread is still alive the guard stays held, exactly as for an undrained pool. The bound is the configured `sm.dp.apply.worker.shutdown-grace`, not a literal 5 s (the bean's own `stop()` keeps 5 s only as the fallback when the worker is disabled). *Why not the worker's pool:* a deferred resume would then take a pool slot and an agent-cap permit with no claim behind it, which the bounded-claim design (one permit per slot, the claim and the thread are one event) forbids; one thread that is awaited keeps both semantics unchanged. A resume interrupted mid-wait lands `APPLY_INDETERMINATE` (or the row stays `RUNNING` for the next sweep). `DeferredResumeShutdownTest` (2 tests): the guard is released only after the thread is gone, and an uninterruptible resume keeps the guard held with a bounded stop; both red when the await is removed (mutation). Worst-case stop time is two shutdown graces for the pool plus two for the thread; the defaults (30 s) fit inside the compose `stop_grace_period` in the realistic case (a deferred resume is interrupted after one grace). |
| **MAJOR-2** `supersedeOrRevoke` verified only against a fake reconciler | **Fixed.** `JdbcAgentRevocationReconcileIT` (real `JdbcAgentIdentityRepository` + `JdbcAgentSessionRepository`): after a revocation applied by the reconcile pass, and after one applied by the retire gate (`revokedApplyingTheControlPlane`), the session's next write is refused `STALE_EPOCH` and reconciling that session still answers `REVOKED`; a session closed without a revocation answers `STALE`. Cited in `supersedeOrRevoke`'s javadoc. (The `aCloseWriteAfterARevocationIsRefusedAsRevokedNotAsStale` test the review named is gone: it was the reverted reorder's.) |
| **MINOR-1** resume-preflight over-approximates | **Fixed (the ledger gives a clean answer).** Every adapter call of a non-PREFLIGHT step is preceded by `attempts.open`, so a non-PREFLIGHT intent with no attempt proves nothing was written or dispatched and the resume lands `FAILED_NO_EFFECT`; an intent with an attempt, however closed (a `FAILED` close can follow a write that landed unacknowledged), stays `APPLY_INDETERMINATE`. `AgentCommandCrashRecoveryIT.aResumeWhosePreflightFailsLandsNoEffectWhenNoStepEverOpenedAnAttempt` (the crash window is modelled by removing the attempt rows); the never-returns test still expects `APPLY_INDETERMINATE`. Rule documented in the method comment. |
| **MINOR-2** deferred requests resumed serially; wording | **Fixed.** Liveness is re-checked per request immediately before its resume and the "spent" test is taken per request, not once per pass (a long first resume no longer decides its siblings' fate): a live session resumes normally even after the window is spent; a request whose agent is still away resumes only once the window is spent. Javadoc (`ApplyStartupRecovery`, `ApplyWorkerProperties`), `operations.md` and the `markRan` reason now say exactly: one shared window, serial resumes on one thread, each resume possibly up to a verb ceiling. |
| **MINOR-3** the I6 record guard misses the new records | **Fixed.** `CredentialBearingRecordsTest` now scans `com.solace.sm..` and governs `token`, `enrollmentToken`, any name ending `nonce`, and `csrDer` (`String`, `char[]` or `byte[]`) besides the existing patterns; planted fixtures `BadTokenRequest`, `BadNonceRequest`, `BadConsumedNonceRequest`, `BadEnrollmentTokenRequest`, `BadCsrDerRequest` plus the legal `GoodTokenRequest`; removing each pattern in turn makes the proof red (measured, four runs). The seven records the review named are asserted as governed and all pass. Two judgements, recorded: the generic name `value` stays gateway-only (elsewhere it is an identifier: `ErrorClass`, `ReplicaRef`, a restart-read token), and two records that merely carry a governed *name* and hold no secret are exempt by name (`ErrorClass.token`, the dotted classification code; `CounterSemantics.Previous.token`, a counter-restart marker). |
| **MINOR-4** compose does not forward two variables | **Fixed.** `runtime/compose.yml` forwards `SM_DP_APPLY_AGENT_SESSION_GRACE` (default `30s`) and `SM_DP_AGENT_MAX_PLACEMENTS_PER_TARGET` (default `16`), the launcher's own defaults; `docker compose -f runtime/compose.yml config` renders both (required variables set to dummies; no `up`). |
| **MINOR-5** keytool read "owed before merge" | **Measured, closed.** The launcher image (built with `runtime/scripts/build-image.sh`, manifest `sha256:a75d9fc0...71de2`, under the docker lock) contains `keytool` (`/opt/java/openjdk/bin/keytool`, Temurin 21.0.12.1+1, uid 10001). Against a keystore made by the runbook's commands, bind-mounted read-only: `keytool -list` exit 0 showing `agent-ca` (`PrivateKeyEntry`) and `lease-nonce-hmac` (`SecretKeyEntry`); `-list -v -alias lease-nonce-hmac` exit 0, `Entry type: SecretKeyEntry`; the image's own `-importpass` (one stdin line, password from a file) exit 0 and its keystore lists both entries under the image and the host `keytool`. Not measured: the byte length of an entry the image's `keytool` wrote (only its type was listed; the 64-byte read-back is from the host's 21.0.12.1). Recorded in `enable-agent-gateway.md`; the "owed" line is gone. |
| **MINOR-6** `Enroll` outside the admission limiter | **Known limitation, no code** (availability only; token entropy makes guessing irrelevant; cost per call is a TLS handshake and one control-plane transaction on the 32-thread gRPC pool). Stated in the runbook's limits with the mitigation (keep the gateway port reachable from the agents' network only). Applying `admission.admit`'s rate to `Enroll` is a follow-up. |
| NIT `RENDER_CHANGED` vs `AGENT_PAYLOAD_RENDER_CHANGED` | **Documented, not renamed**: wire code the agent reports vs the data plane's own coded error; both are in use and appear on the wire/audit. |
| NIT `markRan` reason "wait up to 30s" | **Fixed** with MINOR-2. |
| NIT 3-arg `ApplyWorkerProperties` constructor | None needed (the review found it sound). |
| NIT Go `Conn.Close` 2 s cost | **Commented** at `Close` (go-agent patch for the agent PR line; comment only). |
| NIT `ApplyWorker` javadoc "left RUNNING" | **Fixed**: an interrupted agent LIFECYCLE wait lands `APPLY_INDETERMINATE`; only a thread that does not unwind leaves its row `RUNNING`. |
| NIT `OR-146` (4) NOTICE wording | None (ruled). |
| Owed `AgentOrchestrator` comment (T5.2 review m2) | **Fixed**: the `operator-drain` reason is carried in the `Drain` notice; it is not persisted, and the `agent_event` row (`gateway.drain` / `DRAINING`) is indistinguishable from a planned-restart drain. |
| Verdict item: lane F's verification job | **Read, green**: `laneF/fix.log` shows 354 tests in 30 classes, 0 failures, BUILD SUCCESS (finished 20:58 IST) on lane F's head `e2905101`. The integrator's full reactor gate on the merged tree is still the gate for the backend PR (`OR-144` (3)). |

**Verification of the fix round (docker lock, label `finalfix`).** Focused gate on `final-fixes`: dp-core unit 12, dp-core ITs 86 (`AgentCommandCrashRecoveryIT` 8, `AgentStarvationIT` 5, `ResumeIT` 9, `ResumeRunningIT` 5, `ResumeReadIT` 1, `WorkerSingletonGuardIT` 3, `LifecycleDispatchedFailureIT` 2, `ReconcilerWiringIT` 2, the agent identity, session, lease and facts kits and `JdbcAgentRevocationReconcileIT` 3), agent-gateway 88, arch-tests 171: 0 failures. Mutation matrix (each reverted byte-identical): the old resume-preflight rule makes `aResumeWhosePreflightFailsLandsNoEffectWhenNoStepEverOpenedAnAttempt` red (`APPLY_INDETERMINATE`, expected `FAILED_NO_EFFECT`); `reconcile` answering `STALE` for any closed session makes two `JdbcAgentRevocationReconcileIT` tests red; the await removed from `ApplyWorker.stop()` makes both `DeferredResumeShutdownTest` tests red; each `CredentialBearingRecordsTest` pattern removed in turn makes the proof red. `go vet` and `go test ./internal/wire/` pass (comment-only Go change). Not run here: the full reactor and `agent-live` (the integrator's gate).

**Known limitations carried to v1 (no code), for the owner's record.**
1. *dp-remote adapters never mark `afterDispatch`*: `OR-146` (1) is the agent adapter's rule; a dp-remote lifecycle failure after the restart verb is still `CONFIG_DIVERGED`.
2. *`Enroll` is outside the admission limiter* (MINOR-6 above).
3. *`OR-146` (3)*: plan section 10 item 24 (re-enrolment and discovery-tombstone keying), item 25 (30-day agent certificates: re-enrol before expiry; no in-place renewal) and the absence of a discovery-tombstone retention rule; each has its runbook procedure and is designed after the UI integration.
4. *Several deferred agent requests share one grace window and are resumed serially* (MINOR-2): a later request can be resumed after the window while an earlier resume is in flight; it still resumes normally if its agent is live.
5. *A stop with a deferred resume in flight can take up to two shutdown graces for the thread* on top of the pool's own drain.

## Reconciliation into UAT (OR-147)

*(2026-10-12, branch `reconcile/uat-plan015` off `f4cba32c` = `origin/UAT` `248f9fb9` + the `OR-147` row; implementer Opus 5.5; one merge of
`final-fixes` @ `1c1f5f59`, merge base `fbee8b1c`. The merge commit is `ad6c6b7c`; follow-ups are separate commits. Nothing pushed.)*

**Conflicts: 148** (114 add/add, 34 content); a further 350 additions and 116 modifications merged cleanly. Resolution:
- **Plan 015's side, whole (122):** every agent path, 119 files (`go-agent/**` 32, `backend/agent-gateway/**` 7, `dp-api/.../agw/**` 23, the
  cp-api/cp-core enrolment and CA code 13, the dp-core agent runtime and `sm_data` `V028`/`V029` 7, the launcher's agent adapters and harness 17,
  `runtime/**` 10, and 10 docs: the agent runbooks, specs, port matrix, AG-D/AGW-D registers and `docs/plans/go-agent/005`), plus `RuntimeAdapter`, `ApplyExecutor` and `PlaneBoundaryTest`, whose
  `UAT` side was the old agent's (`RuntimeOperationContext` call sites; the agent rules plan 015 moved to `AgentGatewayBoundaryTest`).
  `runtime/scripts/build-image.sh` therefore keeps `rewrite-timestamp=true` (`87e338e1` had removed it; `AGW-D22` item 1 relies on it).
- **Combined by hand (26):** listed under *Shared files* below.
- **Clean merges that carried old-agent code, reverted to plan 015's copy:** `ControlPlaneConfiguration`'s CA and gateway beans,
  `ControlPlanePersistenceConfiguration`'s `agent_registration` table count and `agentRegistry` bean (the `RoleBindingRepository` bean, `UAT`'s
  identity projection, is kept), `SmControlMigrator`/`SmDataMigrator` (`outOfOrder` and the flyway seam for the deleted upgrade-path test),
  `ApplyWorker`/`ApplyWorkerConfiguration`, the four poms and `application.properties` (the old agent's dependency and property blocks; plan
  015's are the ones re-applied by hand, HF13/HF17), `SketchMirrorTest`, `SmDataBootstrapIT`, `ControlPlaneConfigurationIT`, three trailing-line
  edits, the `sm_control` migration README and `agent-gateway/design.md`. **Kept from `UAT` although the old-agent branch wrote it:** the identity
  projection (`RoleBindingRepository`, `IdentityView.roleNames/superAdmin`, `Identity`, `AuthController`, `CredentialHashMain`), the
  `tenant` -> `organization` envelope rename in `mesh-events`/`contracts/events` (plan 015 had routed it to central item 36; `UAT` ships it, so
  one plan 015 test reads `EventEnvelope.organization()` now), `PackageModel`/`DesignerProjectorImpl` (restored by the developer, `e786913d`),
  and `ProductionRateGateAndForwardingIT`'s refill-rate fix.

**Deleted, old-agent leftovers (59 files; each checked with `git grep`: no kept non-agent code references any):**
- migrations: `sm_control` `V022__agent_registration.sql`, `V023__agent_enrollment_replay.sql`; `sm_data` `V030__agent_runtime_binding`,
  `V031__agent_telemetry_projection`, `V032__agent_operation_correlation`, `V033__agent_rendered_generation`, `V034__agent_telemetry_runtime_grants`,
  `V035__metrics_raw_partition_horizon` (it partitions `sm_data.metrics_raw`, which only the old agent's `V030` created, so it is agent),
  `V036__agent_command_renotify` (and the old `V028`/`V029`, replaced in the add/add resolution);
- `dp-api/.../agw/`: `AgentActualState`, `AgentActualStateQueryPort`, `AgentGatewayPorts`, `CanonicalTelemetrySample`, `TelemetryBatch`,
  `TelemetryIngestPort`, `TelemetryQueryPort`, `TelemetryResult` (the §4.2.1 drops and renames);
- `dp-core`: `dp/agent/{AgentApplyConfiguration, AgentGatewayPersistenceConfiguration, JdbcAgentGatewayPorts, JdbcAgentPlacementCatalog,
  JdbcAgentRenderedConfigSource}`, `apply/persistence/SmDataMaintenanceMain` and tests `AgentGatewayMigrationShapeTest`, `AgentReportIT`,
  `AgentRevocationIT`, `AgentTelemetryProjectionIT`, `SmDataMaintenanceMainIT`;
- `cp-core`: `SmControlMigrationUpgradePathIT` and `db/upgrade-probe/{late/V013__late_arrival.sql, next/V016__upgrade_probe.sql}` (the old
  branch's "V013 gap" test; it asserts no `V013` exists, which `UAT` itself contradicts);
- `go-agent/buf.yaml`, `go-agent/proto/agentgateway/v1draft/agent_gateway.proto` (moved to `contracts/agent/v1` by T1.2);
- `runtime/deploy/{kubernetes-maintenance-cronjob.yaml, sm-maintenance.cron, sm-maintenance.service, sm-maintenance.timer}`,
  `runtime/launcher/bin/{sm-maintain, sm-hash-credential}` (§4.3 DROP), and `runtime/1` (a stray copy of `.env.example` with local passwords,
  committed `5806dae3`; no reference);
- docs: `docs/plans/backend/011-agent-gateway-production-{execution,implementation}.md`, `evidence/011-*.md` (7), `evidence/raw-*.md` (8),
  `evidence/README.md` (kept by plan 015 as `015-history-*`), `docs/runbooks/backend/maintain-sm-data.md` (§4.3 DROP).

**Migrations (`OR-147` (2)).**

| Schema | Plan 015 wrote | Reconciled | Removed (old agent) |
|---|---|---|---|
| `sm_control` | `V021__agent_registration_and_verbs` | **`V026`** | `V022__agent_registration`, `V023__agent_enrollment_replay` |
| `sm_control` | `V022__system_actor` | **`V027`** | |
| `sm_data` | `V028__agent_gateway`, `V029__agent_gateway_maintenance`, `V030__agent_session_features`, `V031__agent_discovery_release` | **`V043`**–**`V046`** | `V028`–`V036` |
| `sm_metrics` | `V004__ingest_counter_clock_skew` | `V004` (no collision) | |

Kept `UAT` migrations: `sm_control` `V021__connections`, `V024__connection_audit_operations`, `V025__connection_audit_row_key`; `sm_data`
`V037`–`V042`. Each was read for a reference to an old-agent object; **none references one**. The cross-references found, and how each is
resolved (always in plan 015's renumbered migrations, never by editing a Connections one):
1. **The audit function.** `V021__connections` replaced `record_change_audit()` with **`V018`'s** body plus the four `CONNECTION_*` operations and
   the `connection` row key; `V024`/`V025` patch the same two edits in. Plan 015's `V026`/`V027` replace the function whole from `V020`'s body, so
   run after the Connections ones they would have **dropped** `CONNECTION_*` and the `connection` row key (every Connection write would then fail
   `OR-25`). Fixed: `V026` and `V027` carry both edits (marked `OR-147` inside the body), and `V026`'s `change_audit_audit_operation` CHECK names the
   four `CONNECTION_*` operations. Found on the way: `V021`'s `V018`-based body had dropped `V020`'s operator-cli `HIERARCHY_NODE_RENAMED` gate,
   which the `V020` readiness stage requires, so a fully migrated `UAT` schema should be refused at start (measured by reading, not by a run on
   `UAT`); `V026` restores it. The `V026` readiness stage now also requires `CONNECTION_DELETED` and `WHEN 'connection'` in the body and
   `CONNECTION_DELETED` in the CHECK.
2. **Catalog rows.** `V021__connections` took rows 44-47 for `connection.*`; plan 015's agent verbs become rows **48-51** and the catalog **51**
   (Administrator 51, Operator 27, Viewer 10; Administrator-only 24). `D10-G2-APPROVAL` keeps `OR-130`'s §1.10 and carries plan 015's section as
   **§1.11 / §1.11.1** with the reconciled numbers; `Permission`, `PermissionCatalogMirrorTest`, `BuiltInRoleSeedIT`, `IdentityServiceIT`,
   `validate-openapi.py` (`CATALOG_SIZE = 51`) and the `V026` readiness messages follow. `OR-126` A8 and `OR-128` (4) are not edited (`I3`): they
   still say "rows 44-47".
3. **Placement grammar and target tables.** `V039`/`V040` rewrite `placement`'s grammar CHECKs and `placement_inside_target_envelope()` with a
   `connection_id IS NOT NULL OR …` guard; plan 015's `V043` rewrites neither (agent placements are target-backed and keep the full grammar and
   the envelope trigger), and `placement_one_host_binding` admits them (`target_id` set). `V043`'s `target_connection` changes and `V038`'s
   `connection_runtime` are disjoint tables. `instance_state`: `V039`'s `deployment_mode` (default `dp-remote`) and `V042`'s `role` are column
   additions that `V043`'s `acquisition_mode` CHECK does not touch.
4. **Grants.** All column- or table-scoped and additive (`V039`/`V042` `INSERT (deployment_mode)`, `INSERT (role)`; `V043`/`V045` per-table
   grants); no revoke on either side.
5. **sm_data content** of `V043`–`V046` is unchanged; only their comments now name the new numbers (and each notes the renumbering).

`CHECKSUMS.sha256` rebuilt for `sm_control` and `sm_data` (every pre-existing line byte-identical). `frozen-migration-anchors.txt`: plan 015's
four anchors (`4ca1c9c5`, `c07eb130`, `f4c504ca`, `48d16575`) are **removed**, recorded in a comment there: each froze plan 015's migrations
under their old names, so anchor mode fails on any renumbering. Anchor mode then passes (50 migrations at 15 anchors). **Base mode against
`origin/UAT` fails by design** (the 11 removed old-agent migrations and their 11 manifest lines): `OR-147` (2) is the authority; the tool has no
exemption mechanism and none was added. **Owed (integrator):** after the merge reaches origin, append its SHA as the anchor that freezes
`sm_control` `V021`, `V024`-`V027`, `sm_data` `V037`-`V046` and `sm_metrics` `V004`. Code, test and doc references to the old numbers were updated
(readiness stages `V026_*`/`V027_*`, `SmControlPostgres.LATEST_VERSION = "027"`, `AgentRegistrationSchemaIT`/`SystemActorSchemaIT` versions,
`ControlPlaneConfigurationIT`, `SmDataBootstrapIT`/`SmMetricsMigratorIT` version lists and the count 37, the runbook's heads, `backend/README.md`);
decision bodies (`AGW-D*`) and earlier Results keep the numbers they were written with.

**Shared files (what each side contributed).**
- `Permission.java` / `PermissionCatalogMirrorTest` / `BuiltInRoleSeedIT` / `IdentityServiceIT` / `validate-openapi.py`: `UAT` the four
  `connection.*` verbs (rows 44-47); plan 015 the four `agent.*` verbs, now rows 48-51; counts recomputed to 51/27/10.
- `D10-G2-APPROVAL-2026-09-15.md`: `UAT`'s §1.10 kept as written; plan 015's §1.10 became §1.11 with a renumbering note.
- `AuditOperations`: both blocks (`CONNECTION_*`; `AGENT_*`, now cited as `V026`).
- `PlacementView`: `UAT`'s `connectionId` and plan 015's `transport` in one record (6 components); the three earlier arities kept; a connection-backed
  placement is `ssh-docker`. `AgentPlacementsTest` passes `(String) null` where the now-ambiguous `null` was.
- `PlacementRecord`: a 16-argument constructor (plan 015's `createdBy` with no connection) beside `UAT`'s 15/17.
- `InventoryService`: `UAT`'s `reserveOnConnection` and `ConnectionRuntimeAdministration` methods and plan 015's `reserveDiscovered`; the live
  placement view batches transports for target-backed rows only. `TargetConnectionRows`: both sets of default methods.
- `DeclareOrchestrator`: `UAT`'s connection-backed declare (and its deferral of the placement grammar check) with plan 015's refusal of
  `acquisition.mode: agent` moved into `UAT`'s relocated `acquisitionOf`.
- `ErrorCode` / `ProblemBody` / `console.yaml`: `CONNECTION_TEST_FAILED` and the five agent codes; `console.yaml` has both tags, both declare
  paragraphs and the union enum: **85 operations** (plan 015's 78 + `UAT`'s seven). Postman regenerated (85; 84 served, 1 wired);
  `ENDPOINT-INVENTORY.md` gains a reconciliation note. `OperationPolicy`: `getSolutionPackage` and `listAgents` both in the read-only list.
- `ControlPlaneConfiguration`: `UAT`'s `ConnectionConfiguration` and `RoleBindingRepository` wiring, plan 015's `AgentConfiguration`.
  `SolutionManagerConfiguration`: plan 015's imports only.
- `.gitignore`, `backend/local/sm-local.sh` (usage text), `docs/tasks/lessons.md`, `docs/requirements/traceability.md`: both sides' lines.
- `docs/design/backend/decisions.md`: plan 015's copy plus `UAT`'s `BE-D66` (Connections) index row, body and changelog row. ⚠️ **`BE-D66` now
  names two decisions** (plan 015's agent adapter, `UAT`'s Connections split): both kept as written (`I3`), flagged in a changelog row, not
  resolved. ⚠️ `UAT`'s copy (and its plan 010) carried a **committed tool-truncation artefact** ("Warning: truncated output … tokens truncated",
  from merge `cb2bae8c`) that had silently deleted `BE-D36`, `BE-D37`, `BE-D38`, `BE-D41`, `BE-D42`, `BE-D44`, `BE-D45`, `BE-D46` and 2,203 lines
  of plan 010; both are rebuilt from plan 015's copy (plan 010 only ever had old-agent amendments on `UAT`, which §2 discards).
- `docs/design/DECISIONS.md` (clean merge): every `OR-*` row of both sides (both `OR-130`s, `OR-134`-`OR-146`, `OR-147`); `SM-D32`-`SM-D34`, which
  `UAT`'s lineage had lost while still citing them, restored from plan 015's copy (3 index rows, 3 sections).
- `CLAUDE.md`: `UAT`'s copy (tool-agnostic workflow, `I5` wording) plus plan 015's agent-gateway row, the `AGW-D2` module-boundary block and the
  `AGW-D*` family in rule 2 and `I3`.
- `contracts/run-checks.sh`, `contracts/events/validate-events.py`: executable bit restored (`UAT` had dropped it), follow-up `da21646f`.

**Flagged, not resolved.**
1. `BE-D66` (two decisions) and the two `OR-130` rows: kept as written; the owner may re-issue one ID by a superseding row.
2. `docs/design/common/lessons.md`: `UAT` renumbered the backend line's `L19`-`L22` to `L22`-`L25` and inserted its own `L19`-`L21`, so the backend
   documents' 57 citations of `L19` (and of `L20`-`L22`) now resolve to different lessons. `UAT`'s file is kept unchanged.
3. An agent-discovered instance carries `deployment_mode` `dp-remote` (the column default `V039` added); `host-local` would describe it. Left for
   the UI integration (`OR-147` (4)).
4. Pre-existing on `UAT`, unchanged here: `run-checks` step 4 (the File Mesh adapter output drifted from its committed fixture, `contracts/validation`
   is byte-identical to `UAT`'s) and step 14 (`check-frontend-ajv.mjs` cannot read the options from `UAT`'s precompiled `validate.ts`); the duplicate
   step labels 12/13; the frontend's typecheck, lint and format failures and 54 failing tests in 8 files (the same set fails on a `UAT` snapshot).
   The frontend's `contractConformance.test.ts` (already failing on `UAT`) now also lists the five agent operations as uncalled: owed to the UI
   integration, since the frontend is not edited here.
5. `backend/agent-gateway/README.md` (plan 015's own copy) still describes the old branch's migrations and `sm-maintain`; unchanged here.
