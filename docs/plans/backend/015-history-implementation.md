# 011 — Production Data Plane agent-gateway implementation

> **History, taken verbatim from `origin/dp-agent-integration` @ `87e338e1` by plan 015 T0.1 (2026-10-08) and renamed from `011-…`** (the number collides with [`011-monitoring-backend.md`](011-monitoring-backend.md)). It proves the *branch's* tree, not ours, and it is **not authority**: [`015-agent-gateway.md`](015-agent-gateway.md) is. Apart from this note, only file names and the links between these history files were edited.

| | |
|---|---|
| **Component** | Backend `agent-gateway` + `dp-core` + `dp-api` |
| **Design** | [`docs/design/backend/agent-gateway/specs/2026-10-05-dp-agent-production-integration-design.md`](../../design/backend/agent-gateway/specs/2026-10-05-dp-agent-production-integration-design.md) |
| **Port matrix** | [`docs/design/backend/agent-gateway/port-matrix.md`](../../design/backend/agent-gateway/port-matrix.md) |
| **Predecessor** | [`docs/design/backend/agent-gateway/design.md`](../../design/backend/agent-gateway/design.md) |
| **Related backend work** | [`010-dp-data-model-and-remote-flow.md`](010-dp-data-model-and-remote-flow.md), [`004-apply-persistence-and-admission.md`](004-apply-persistence-and-admission.md), [`008-placement-registry-and-apply-journal-identity.md`](008-placement-registry-and-apply-journal-identity.md) |
| **Status** | **IN PROGRESS — implementation baseline and local hardening complete; release validation/approval gates remain external** |

## Scope

Build the Java Data Plane side that replaces `fake-gateway` for the Go `host-local` agent. The implementation must
use the existing `dp-core` apply engine and telemetry model, add the `agent-gateway` boundary, and preserve the
agent's crash/reconnect/idempotency guarantees.

The fake gateway remains a test double. It is adapted to the authoritative contract and retained for protocol and
agent integration tests; it is not removed as part of this plan.

### Out of scope

- Go-agent redesign beyond contract compatibility and generated-code regeneration.
- New MI code, build plugins or endpoints.
- Kubernetes, scheduled workload support, self-upgrade or HTTP/1.1 fallback.
- Multi-replica production deployment.
- Replacing the existing `dp-remote` adapter.
- Human authorization design.

## Prerequisites and blocking gates

This plan may be drafted and reviewed while these are open. It may not be marked `APPROVED` or executed until the
owning design/decision records close them:

- The `B/D16` versus `SM-D24`/`SM-D28` session-home ruling.
- The authoritative production `.proto` (`AG-1` / open item 6) and generated-code ownership.
- `AGW-17` replica identity verification.
- `AGW-18` CP lease-issuance idempotency.
- `AGW-19` report cadence required by liveness/retirement semantics.
- `AGW-20` actual-state report persistence, freshness, trust and projection contract.
- `AGW-21` durable agent-security audit contract.
- Backend open items 38 and 39: migration bootstrap authority and maintenance-job ownership/multiplicity.
- `BE-D12`/`BE-D13` ruling for agent `IN_FLIGHT`, awaiting completion and resume.
- A measured capacity model and an approved TLS/secret distribution path.

The implementation branch resolves technical choices where the repository had enough information to do so. Those
choices are recorded below and are not presented as architect or operations ratification.

Checklist status uses `[x]` for code/artifact work present in this branch. Verification and external release
approval are recorded separately below; an `[x]` is not a claim that a PostgreSQL, CP-backed or Docker scenario
has passed.

## Implementation decisions recorded by this branch

| Gate | Branch decision | Compatibility/release implication |
|---|---|---|
| AG-1 / protobuf | `go-agent/proto/agentgateway/v1draft/agent_gateway.proto` is the single generator input; the historical package name and field numbers remain unchanged. Maven and Buf generate Java/Go bindings from that file. Correlation is an append-only optional message on command notices/offers and actual-state items. | Existing Go wire compatibility is preserved: older peers omit/ignore correlation field 8/10/11. A separate architectural ratification may still rename the package in a future version. |
| SM-D24 / SM-D28 session home | `dp-core` owns durable identity, session fencing and lease projection. `agent-gateway` owns only streams, queues and polling. | The Java gateway has no datasource and can be extracted later. CP certificate/lease issuer beans are mandatory for production enablement and fail closed when absent. |
| AGW-18 leases | CP owns registration policy and signs the lease material; the lease nonce is deterministically derived from the request ID, agent, certificate thumbprint and consumed nonce. DP persists the resulting grant and renewals. | Retrying a lost CP response cannot create a second nonce for the same request. A caller must generate a new request ID for an intentional new issuance. |
| Enrollment replay | CP consumes a token only for one CSR digest and stores the issued chain/trust material in the registration row. A retry with the same token and CSR returns the stored material; a different CSR is refused. | The CP runtime role needs the additive V015 column-update grant. The CA keystore remains an operator-provided secret and live mTLS validation is still a release prerequisite. |
| AGW-20 reports | `agent_report` is keyed by `(agent_id,item_id)` with a semantic fingerprint; duplicate content replays the original result, changed content is `REQUEST_ID_REUSED`, and only lease-bound reports are trusted. Registry IDs are deterministic DP-owned values. | The current migration provides durable replay and trust/disposition storage. Capacity caps, retirement and freshness handoff need the production AGW-20 review before release. |
| AGW-21 audit | `agent_audit_event` is append-only by request ID and stores coded events, never tokens or payloads. | Retention is handled by the deployment-owned maintenance function. Security review must validate retention and alerting. |
| BE-D12 / BE-D13 | Delivery is non-terminal: `OFFERED → DELIVERED`; only `CommandAck` writes terminal outcome. Reconnect polls durable `QUEUED/OFFERED/DELIVERED` rows under the current fencing epoch. | The transport seam, placement catalog, durable completion/correlation projection, hash-verified rendered-generation source and agent-backed runtime/apply wiring are implemented. A live runtime/report drill remains a release validation gate. |
| Items 38/39 | Phase 0 remains bootstrap-admin owned; the migration process owns Phase 1. CP registration is `sm_control/V014–V015`; gateway/runtime/telemetry/correlation/generation migrations are `sm_data/V018–V023`. Maintenance is one SQL function protected by a transaction advisory lock and invoked by one deployment-owned scheduler. | No application scheduler is silently created. The release runbook must supply the scheduler and credentials. |
| TLS/capacity | Listener is disabled by default, requires explicit certificate/key/trust paths when enabled, and uses bounded message/poll/credit settings. | Certificate distribution and measured capacity remain external release prerequisites; no production capacity claim is made. |

These decisions authorize implementation in this branch; they do not claim external ratification. Rendered
generations are provisioned into `sm_data.agent_config_generation` by the upstream configuration publisher; DP runtime
has SELECT only and rejects missing or hash-mismatched generations.

## Task allocation

### Phase P0 — freeze inputs and create the test seam

- [x] **P0.1 — Reconcile the contract source of truth.** Record the approved `.proto` location, package/version,
  generated-code commands, compatibility window and ownership. Verification: a clean checkout regenerates Java and
  Go bindings from the same source and produces no diff.
- [x] **P0.2 — Freeze the port/API matrix.** For every gateway call, record the `dp-api` method, request ID,
  session reference, durable arbiter, transaction boundary, refusal codes and committed result. Verification: a
  contract matrix test fails if a port accepts caller-supplied tenant scope or lacks idempotency metadata.
- [x] **P0.3 — Assign migration ownership.** Reconcile with plan 010's `sm_data` migration ledger before choosing
  any version. Verification: one owner per migration file, no duplicate version, and the migration ledger is updated
  in the same change.
- [x] **P0.4 — Add protocol compatibility fixtures.** Convert the current fake-gateway scenarios into contract
  fixtures for enrollment, session accept/reject, command delivery, reports, telemetry credits and error mapping.
  Verification: fixtures run against the fake gateway and the Java in-process gateway adapter.

### Phase P1 — module and boundary

- [x] **P1.1 — Add the Maven `agent-gateway` module.** Register it in the backend reactor and launcher wiring with
  only the approved gRPC/Spring and `dp-api` dependencies. Verification: `mvn verify` builds it and the resolved
  dependency allow-list contains no JDBC, JPA, Flyway, broker or CP implementation dependency.
- [x] **P1.2 — Add package and ArchUnit boundaries.** Implement the companion design's two-way rules, including no
  datasource and no Spring bean lookup. Verification: every planted violation fails the intended rule; the clean
  reactor passes.
- [x] **P1.3 — Add generated-code isolation.** Keep generated gRPC types inside `agent-gateway`; expose versioned
  domain records/ports through `dp-api`, not generated types. Verification: `dp-core`, `dp-api`, `console-gateway`
  and `mesh-events` do not import generated gRPC classes.
- [x] **P1.4 — Add configuration and readiness.** Define listener address, TLS material reference, protocol limits,
  poll cadence, queue limits and drain settings. Verification: invalid/missing production configuration makes the
  application unready with a coded reason; no listener accepts sessions before readiness.

### Phase P2 — identity, enrollment and session

- [x] **P2.1 — Implement `AgentEnrollmentPort` adapter.** Map bootstrap token/CSR requests to the CP registry and
  PKCS12/JKS CA signer, including same-token/same-CSR replay. V015 durably stores the issued chain and trust bundle;
  a different CSR after consumption is refused. Verification: adapter and fail-closed configuration tests are present;
  first enrollment, replay, different CSR, rate limit and CP timeout still require the live CP/DB test environment.
- [x] **P2.2 — Implement mTLS enrollment and connect handlers.** Enforce certificate-less access only for `Enroll`
  and client-certificate access for `Connect`/`Telemetry`. Verification: a client certificate cannot bypass
  enrollment; an enrolled certificate with wrong trust/identity is refused.
- [x] **P2.3 — Implement session claim and fencing.** Call `AgentSessionPort`, retain only connection-local session
  state, and reject stale epoch writes. Verification: two gateway instances racing one agent produce one owner and
  stale writes return the approved refusal without changing durable state.
- [x] **P2.4 — Implement lease and heartbeat handling.** Map `LeaseGrant`, renewals, revocation, drain, clock and
  heartbeat messages without making the gateway a second lease authority. Verification: lease accepted/renewed,
  renewal timeout, revoked identity, drain and heartbeat-miss tests.
- [x] **P2.5 — Implement reconnect/replay behavior.** Rebuild connection-local state from DP ports after process
  restart; use agent resume hints only as hints. Verification: DP restart, gateway restart, lost reply and duplicate
  `SessionOpen` scenarios.

### Phase P3 — command delivery and apply-engine seam

- [x] **P3.1 — Implement durable command polling.** Poll `AgentCommandPort` for the current owner, with after-commit
  wake-up as an optimization only. Verification: polling works when the wake-up is lost; commands are not delivered
  to a non-owner or stale epoch.
- [x] **P3.2 — Implement notify/pull/offer.** Keep `GenerationAvailable` metadata-only, retrieve bytes only after
  `CommandPull`, enforce message limits and never log payloads. Verification: normal pull, duplicate pull, unknown
  command, stale generation, payload-too-large and hash mismatch tests.
- [x] **P3.3 — Implement delivery and terminal acknowledgements separately.** Persist `CommandReceived` before
  releasing the delivery state; map `CommandAck` only to terminal apply outcomes. Verification: lost received ack,
  lost terminal ack, duplicate terminal ack and reconnect-redelivery tests.
- [x] **P3.4 — Implement the complete `RuntimeAdapter` agent implementation.** Map `prepare`, `materializeConfig`,
  lifecycle, `status`, resume and rollback-method behavior to the ratified engine contract. The rollback behavior must
  be an explicit supported extension, coded unsupported result, or deliberate routing decision; an unclassified
  exception is forbidden. Verification: placement/catalog, durable completion and coded-rollback unit tests pass;
  the current wire contract deliberately routes rollback to `agent.rollback.unsupported`.
- [x] **P3.5 — Complete DP-to-agent operation correlation.** `RuntimeOperationContext` carries the apply request,
  step-intent, attempt and step IDs through context-aware adapter overloads. The correlation is persisted with the
  command, carried by append-only AG-1 fields into the agent journal, attached to post-operation report items, and
  persisted with the DP report. Existing adapters remain source-compatible through default overloads. Verification:
  `AgentRuntimeAdapterTest`, focused Java gateway tests, Go wire/agent tests and the V022 migration shape test pass.
- [x] **P3.6 — Implement outcome/error translation.** Agent outcomes are mapped to durable completion and coded
  runtime refusal paths, including timeout, interruption, unknown outcome and explicit unsupported rollback.

### Phase P4 — actual-state reports and registry

- [x] **P4.1 — Implement `ActualStatePort` persistence.** Use the approved AGW-20 schema and transaction boundary
  for report item idempotency, fingerprint, trust, disposition, freshness and current projection. Verification:
  duplicate, reordered, stale, untrusted, refused, handoff and incarnation-cap tests.
- [x] **P4.2 — Implement report acknowledgements.** Return one result per item by item ID, including DP-minted stable
  and incarnation IDs only when registration succeeds. Verification: reordered batch/result tests and retry returns
  the original result.
- [x] **P4.3 — Connect `status()` and convergence.** The agent adapter reads only the DP query port backed by
  accepted report rows and returns an absent/unreadable state when no trusted projection exists. PostgreSQL freshness
  and stale-report acceptance still require the live migration/concurrency test.

### Phase P5 — telemetry

- [x] **P5.1 — Implement `TelemetryIngestPort` adapter.** Validate stream/session/lease scope and pass normalized
  batches to the existing storage path. Verification: samples and gaps are committed atomically and tenant scope is
  derived server-side.
- [x] **P5.2 — Implement credit accounting.** Reserve credits before sending them, replenish only after commit,
  release them on teardown, and never drop gateway-side samples. The Java gateway now reserves per-stream credits
  before entering `TelemetryIngestPort`, grants only the committed result, refuses at zero, refunds non-credit
  failures, and releases outstanding credits on close. The deterministic local tests cover zero-credit refusal and
  teardown; cross-process restart, oversize buffering and fleet-wide budget evidence remain L2/L3 work.
- [x] **P5.3 — Integrate agent telemetry with existing query/rollup paths.** Accepted samples are projected into
  `series`/`metrics_raw` through an explicit `agent_telemetry_series` mapping and are read through
  `TelemetryQueryPort`, including labels. Live PostgreSQL query/partition validation remains pending.

### Phase P6 — audit, observability and operational hardening

- [x] **P6.1 — Implement `AgentAuditPort` and refusal audit.** Persist enrollment, revocation, claim, drain,
  capability, stale epoch and protocol refusals without secrets. Verification: every refusal class has a durable
  audit row and an idempotent replay test.
- [x] **P6.2 — Add gateway metrics, health and readiness.** The gateway exposes bounded Micrometer counters for
  claims, notices, pulls, command acknowledgements and telemetry outcomes, plus active-session and outstanding-credit
  gauges. The `HealthIndicator` is DOWN before an enabled TLS listener starts, reports coded configuration
  or listener failure, and stays independent of agent connectivity. An optional L4 readiness socket accepts only
  after the TLS listener is running. **No actuator HTTP endpoint is exposed** (2026-10-06: the launcher's
  `spring-boot-starter-actuator` dependency was removed because its web `HandlerMapping` made the console refuse to
  start); `/actuator/health` and `/actuator/metrics` therefore do not exist, and an authenticated scrape path is an
  open deployment item. Verification: `AgentGatewayOperationalTest`; external scrape, migration-health and
  deployment-probe validation remain release checks.
- [x] **P6.3 — Add structured logging and redaction tests.** Verification: token, private key, certificate,
  rendered config and telemetry values do not appear in captured logs. **Done 2026-10-06:** the real-agent E2E
  captures the whole Java JVM output and the agent's log and asserts neither contains the enrollment token, the CA
  keystore password, the gateway private key, any `BEGIN … PRIVATE KEY`/`BEGIN CERTIFICATE` block, or a marker
  embedded in the rendered payload (`RealAgentDockerE2EIT#noSecretReachesTheCapturedJavaOrAgentLogs`). Failure paths
  in the CP issuers and the gateway now log the exception class and SQLSTATE only, never the message.
- [x] **P6.4 — Add limits and overload behavior.** Verification: bounded queues, admission rate limits, max message
  sizes and poll backoff are exercised under a deterministic load test.

### Phase P7 — end-to-end acceptance and release packaging

- [x] **P7.1 — Run a real-agent Docker E2E.** Enroll the current Go agent against the Java DP, then run preflight,
  apply, restart, stop, report and telemetry against a disposable Docker target. **Done 2026-10-06,
  `backend/launcher/.../RealAgentDockerE2EIT` — 9/9.** Evidence and the defects it found:
  [`evidence/015-history-real-agent-e2e.md`](evidence/015-history-real-agent-e2e.md).
- [ ] **P7.2 — Run failure and recovery matrix.** Execute all scenarios in the execution plan, including agent/DP/
  gateway restarts, stale epoch, lease expiry, lost replies, duplicate messages and telemetry backpressure.
  **Partially executed 2026-10-06:** first enrollment, agent restart without re-enrollment (fencing epoch bumps),
  hash mismatch (refused, nothing written), duplicate/non-increasing generation (refused at admission) and the
  stale-epoch lease refusal were executed against the real agent. Gateway restart, DP restart, split brain, lost
  `CommandReceived`/terminal ack, lease expiry, revocation, telemetry pause/reject, planned drain and overload were
  **not** executed here; the scenario-by-scenario map is in the evidence file.
- [ ] **P7.3 — Add deployment packaging.** Include TLS/trust material references, listener exposure, migrations,
  readiness and drain configuration in the actual DP launcher/container packaging. Verification: a clean deployment
  from documented inputs reaches ready and accepts one agent. **Partially done 2026-10-06:** every setting is
  env-driven in the launcher's `application.properties`, and the operator procedure is
  [`docs/runbooks/backend/enable-agent-gateway.md`](../../runbooks/backend/enable-agent-gateway.md) (`UNTESTED` as
  typed; the equivalent sequence runs in the E2E from the same inputs). **Not done:** there is no container image
  (`runtime/` is empty by design), no drain message exists, and the monthly partition/maintenance scheduler is
  deployment-owned and unbuilt.
- [x] **P7.4 — Keep fake gateway as a contract test double.** Remove only production references. Verification:
  repository search shows `fake-gateway` only in tests, examples and developer runbooks. **Verified 2026-10-06:**
  no reference in `backend/`; it appears only under `go-agent/` (its own command, tests, README/RUNBOOK) and in design
  and plan documents.
- [x] **P7.5 — Produce evidence and review report.** Record test commands, environment versions, capacity constants,
  deviations and unresolved limits in the plan's Review section before marking complete. **Evidence produced
  2026-10-06** ([`evidence/015-history-real-agent-e2e.md`](evidence/015-history-real-agent-e2e.md)); capacity constants are
  **not measured** and the independent adversarial review of the design (§16) has **not** happened, so the plan is
  not complete.

## Verification

The plan is complete only when all of the following pass:

1. `mvn -f backend/pom.xml verify` including ArchUnit planted violations and generated-contract checks.
2. PostgreSQL migration and privilege tests on the supported database version.
3. Port contract kits against in-process `dp-core` adapters.
4. Protocol compatibility tests against the fake gateway and Java gateway.
5. Real Go-agent integration tests over mTLS.
6. Docker E2E for all four supported commands.
7. Actual-state report replay/freshness/convergence tests.
8. Telemetry commit/credit/reconnect/gap tests.
9. Security redaction and audit completeness tests.
10. Deployment readiness, drain, rollback and canary runbook execution.

## Review

Local implementation review covered the transport shell, CP-backed enrollment/lease adapters with durable same-CSR
enrollment replay, port boundary, placement resolver, durable completion/correlation projection, hash-verified
rendered-generation source and apply-worker wiring, canonical telemetry projection/query seam, migration shape,
credit accounting, metrics/readiness and fail-closed TLS configuration. The earlier statement here — that PostgreSQL
migration/privilege tests and the Docker E2E were unexecuted and that `mesh-events` failed — was true on
2026-10-05 and is **superseded by the verification update below**: Postgres 14 and Docker were available on
2026-10-06, the real-agent E2E now runs, and `mesh-events` is reconciled (`BE-D27`). The branch still does not claim:
real-CA operational enrollment against a customer CA, PostgreSQL concurrency/privilege suites beyond the existing ITs,
generation-publisher execution, measured capacity, or production approval.

### Verification update — 2026-10-06

Re-run on a machine with a working PostgreSQL/Testcontainers and Docker daemon (the paragraph above predates this).
**The first execution of the real Go agent against the Java DP found eleven interoperability defects that every
in-process test had missed**, all fixed on this branch and recorded in
[`evidence/015-history-real-agent-e2e.md`](evidence/015-history-real-agent-e2e.md) and `AGW-D17`. The headline: before this run,
enrollment could not succeed, no lease could be issued, no command could be accepted by the agent, and no telemetry
could be committed. Now `RealAgentDockerE2EIT` passes 9/9 against a real agent, real Postgres 14 and a real Docker
container.

Other defects found and fixed in the same pass: the launcher did not start (clock bean + actuator starter),
stale migration-shape tests, a timing flake in `ProductionRateGateAndForwardingIT`, the `Identity` contract split,
the designer projector's catalog options, and the `mesh-events` `tenant`→`organization` drift (`BE-D27`).

**`sm_control` V013 is intentionally absent** — plan 010 T2.0 reserves it for `V013__dp_remote_governance.sql`, which
this plan skipped to avoid a collision. Once V015 has been applied to a persistent database, a later V013 is refused
unless Flyway `outOfOrder` is on (plan 010, M9). Pre-production only: recreate the dev `sm_control` and record it, or
renumber at merge.

**Migrations added this pass:** `sm_data/V024` (runtime `INSERT` on `series`/`metrics_raw`) and `V025`
(`metrics_raw` partition horizon). The `sm_data` head is now `V025`.

**Still open:** P7.2 (most of the failure matrix), P7.3 (no container image, no drain message, deployment-owned
scheduler unbuilt), capacity measurement, independent review (§16), PostgreSQL privilege/concurrency suites beyond what
the E2E and existing ITs exercise, and three limits recorded in the runbook (revocation does not refresh the DP
identity, no planned drain, no rollback verb).

---

**Forward pointer, open items (plan 015 T0.1, 2026-10-08).** The "backend open items 38 and 39" cited above are the Agent Gateway's numbering; since 2026-10-08 they are central open items **46** (closed, moot) and **47** (the map is in [`DECISIONS.md`](../../design/DECISIONS.md) after the open-items table). The text above is unedited.
