# Agent Gateway port matrix — the contract T2.1a declares

> **ADAPTED by plan 015 T0.1 (2026-10-08)** from the branch's matrix (`origin/dp-agent-integration` @ `87e338e1`, 40 lines),
> restated against the adapted ports of [plan 015](../../../plans/backend/015-agent-gateway.md) §4.2.1. It is **the
> contract T2.1a declares** (every `dp-api/agw` signature, with the contracted stub behaviour of the ports a later task
> implements) and that the contract kits (T2.1b) prove. Written from the plan's port ledger and the decision texts
> ([`AGW-D23`–`AGW-D32`](decisions.md)); **it is a restatement, not a measurement of code** — T2.1a is the first task that
> compiles against it, and a signature that differs there is corrected here in the same commit. Every decision it cites is
> `PROPOSED` (`I4`). The branch's rows that this plan changes are listed under [What changed](#what-changed-from-the-branchs-matrix).

The gateway receives wire messages, converts them to these `dp-api` calls, and **never supplies tenant scope**. `dp-core`
resolves an agent's organization, environment and target from its certificate thumbprint (`resolveByThumbprint`, the one named
unbound read, `I5`) and enforces the current fencing epoch and lease. Every call that writes carries an
`AgentRequestOrigin(correlationId, clientAddress)` that **the gateway mints per RPC from the gRPC peer** — never a value the
agent sent (`BE-D70`, `OR-25`).

| Wire path | `dp-api` port | Durable key / fence | Commit result |
|---|---|---|---|
| `Enroll` | `AgentEnrollmentPort.enroll(EnrollmentRequest incl. AgentRequestOrigin)` | token digest (unkeyed SHA-256 of a 256-bit token, `OR-128` (9)) + CSR digest; a token cannot be consumed twice | certificate chain, trust bundle and endpoint; the issuer's answer also carries environment, target id, the envelope (`allowedConfigRoot`, `allowedContainerPrefix`) and the issuing principal; the agent target is created in both planes (`BE-D71`); replay returns the stored public result |
| `SessionOpen` | `AgentSessionPort.claim`, then `AgentLeasePort.current` / `issue` | agent identity plus claim request id; session fencing epoch | `AgentSessionRef(UUID agentId, long fencingEpoch)` (carries no tenant, `AGW-D3`) and the lease projection. `claim(certificateThumbprint, SessionOpening, AgentRequestOrigin)` refuses a revoked or unknown thumbprint; `SessionOpening` carries the protocol and agent versions, the agent's announced `AgentFeature`s (`DISCOVERY`, `REMATERIALIZE`, `DECLARATIONS`) and the agent clock (the clock offset is measured at open, `OR-134` F-6, because a reconnect with a valid lease never calls `issue`); the claim takes no request id (the origin's correlation id is the idempotency anchor). `featuresOf(agentId, placement)` answers the live session's features. `AgentSessionPort.liveFor(UUID agentId, AgentPlacementRef placement)` (session open **and** an unexpired lease holding the placement) is the adapter's fail-fast read (`BE-D66`); `close(ref, CloseReason)` replaces `release` |
| `Heartbeat` | `AgentSessionPort.heartbeat(ref, agentClock)` (the DP stamps `last_seen_at` with its own clock) | current `(agent_id, fencing_epoch)` | last-seen timestamp |
| `LeaseRenew` | `AgentLeasePort.renew` → `LeaseIssuer` (the CP, across the launcher's adapter, `BE-D69`) | current session plus the consumed lease nonce; the **DP passes the live placement set**, the CP signs exactly that set and never invents one (`AGW-D24`) | new lease projection; `binding_ids` on the wire carry placement ids (`AGW-D25`). The lease's `verb_ceilings` now include `rematerialize` (`AGW-D24` item 4) |
| poll / `GenerationAvailable` | `AgentCommandPort.pendingFor(ref, limit, renotifyAfter)` (the two-argument form defaults the interval; `agentsWithDeliverableWork` is the shared set-based read, kept) | current session, active lease, placement/capability/deadline gate | command rows stamped `OFFERED`; one already held by this session stays `DELIVERED`; an unfinished command last notified more than `renotifyAfter` ago is notified again (`AGW-D21`). Commands are keyed on the **intent key** — `enqueue(AgentCommandIntent)` is **byte-free** (identity and the keyed hash only, `I6`), mints the generation itself and returns the live or succeeded command for that key as an `AgentCommandRef` (`BE-D66`); `supersedeOutstanding(AgentPlacementRef, keepIntentKey)` touches QUEUED/OFFERED commands of other keys only |
| `CommandPull` | `AgentCommandPort.pull` | current session and offered epoch; lease gate repeated; the caller's `(tenant, agent)` is bound to the command | a `CommandOffer` whose `CommandPayload` (bytes, plain `configSha256`, and on REMATERIALIZE `expectedCurrentSha256`) is the **in-memory hand-off** of the engine's own `RenderedConfig`, keyed by command id (`AGW-D26`). Nothing stores the bytes; `toString` of the payload, the offer and the notice prints neither bytes nor digest After an SM restart: re-render, compare the keyed hash with `agent_command.config_hash`, and send only if equal — else a coded refusal `agent.payload.render.changed` |
| `CommandReceived` | `AgentCommandPort.received(ref, requestId, commandId, wireGeneration)` | current session and `OFFERED` row | row becomes `DELIVERED`; no apply completion |
| `CommandAck` | `AgentCommandPort.ack(ref, commandId, AgentAck)` | current session, command id and generation | row becomes terminal `ACKED` or `REFUSED`; a duplicate terminal ack replays. APPLY and RESTART acks carry `container_started_at` (host clock) |
| actual-state report batch | `ActualStatePort.report` (implemented by `AgentReportIntake`, T3.3) | current session, active lease, `(agent_id, item_id)` fingerprint, per-`(agent, placement)` watermark | durable `ReportResult` with disposition and stable ids; **returns `replica_id` and `incarnation_id`** or the agent sends no telemetry (`AGW-D25`). Health, start time and write baseline land in the **built** tables (`placement_health`, `placement_start_observation`, `apply_attempt.write_baseline`, `AGW-D28`); the adapter's `status` reads them through **`AgentObservedStatePort.latest(AgentPlacementRef)` → `AgentObservation`** (liveness, health facts, the reported plain digest, start time; T2.1b ships a stub answering "no observation"; T3.3 implements). `ReportItem` also carries the T2.3 `AgentHealthObservation` and `containerStartedAt`; every `ReportItem` field is agent-supplied and never resolves a tenant or instance (`I5`) |
| discovery report (`Connect` and every container start/stop) | **`AgentDiscoveryPort`** with `DiscoveredContainer` → `DiscoveryResult` | current session; tenant resolved by the DP from the agent's identity | per-item `PENDING` / `REGISTERED` / `REFUSED` / `ABANDONED` / `UNDECLARED` / `RETIRED` with a code, and `binding_id` (the placement id, `proto-amendments-015.md`) once registered; **facts, not conclusions** — the DP derives every placement field (`AGW-D30`). T2.1b's stub answers every item `REFUSED agent.discovery.unavailable` (`GatewayCode.AGENT_DISCOVERY_UNAVAILABLE`); T4.1 implements. `AgentDiscoveryPort.report(ref, items, AgentRequestOrigin)`; `DiscoveredContainer` fields (labels included) are agent-supplied facts |
| telemetry declarations (gateway → agent) | **`AgentTelemetryDeclarationsPort`** with `TelemetryDeclaration` | current session and lease; the placement set | the package's `http_json` metric objects for that placement and its declared `HealthProbeSpec` (none = nothing probed, `OR-101`), `interval` 5 s (`OR-73`). T2.1b's stub answers "no declarations"; T3.4 implements |
| telemetry batch | **`AgentTelemetryPort.ingest(AgentTelemetryBatch)`** → `AgentTelemetryResult` (renamed from `TelemetryIngestPort`, which collides by simple name with the built `dp.api.metrics.ingest.TelemetryIngestPort`) | current session and active lease; stream watermark | `dp-core`'s bridge maps samples straight to the built `IngestSample` (`SourceDiscriminator.AGENT`) and calls the **built** port; `AgentTelemetryResult.disposition` says *ACK* (only on `Committed`) / *DO_NOT_ACK* (`RetryAfter`, `Pending`) / *REJECTED_COUNTED* (`AGW-D27`) |
| coded refusal / audit | `AgentAuditPort.record(requestId, ref, origin, …)` (every agent-path writer takes the `AgentRequestOrigin`: `report`, `ingest`, `record`, discovery with a `requestId`, and the lease calls) | request id | append-only `sm_data.agent_event` (`AGW-D29`); payloads and secrets are excluded. Checks session ownership only, **not** identity standing, so a revocation can be recorded |
| authority sweep / permission change | `AgentIdentityPort.reconcile(sessions)`; `AgentRegistrationSource` | current session per entry; one batched CP read | `UNCHANGED`, `REISSUED` (new lease persisted and pushed), `REVOKED` (identity marked, lease expired, session ended) or `STALE`; a CP read failure is `INTERNAL_UNAVAILABLE` and changes nothing — `AGW-D19`. `AgentIdentity` carries tenant, environment and target ids, resolved by the DP and never sent by the agent, and no placement set (the DP derives it at lease time). `AgentRegistrationState` carries no `binding_ids`; `LeaseIssuer.issue` takes the DP-supplied `List<UUID> placementIds` |

## Boundary rules

- `agent-gateway` owns gRPC types and connection-local polling only. It has **no JDBC, Flyway, datasource or database
  principal**, imports `dp-api` only, and is the only module that touches gRPC (`agentGatewayUsesDpApiOnly`,
  `agentGatewayHoldsNoDatasource`, `onlyAgentGatewayTouchesGrpc`, each with a planted violation, `AGW-D2`, `L19`).
- `dp-core` owns all durable state and all authorization decisions. A stale epoch is rejected before a stored replay result
  is disclosed.
- Delivery is at-least-once. `DELIVERED` is not terminal; only the terminal command acknowledgement advances the durable
  command outcome.
- Report trust is independent of report disposition: an in-scope report can be trusted and stale, while a report outside the
  current lease is untrusted/refused.
- **No rendered byte and no plain digest of rendered bytes is stored or logged** by any port (`I6`, `OR-32`, `AGW-D26`).
  `agent_command` holds the keyed `hmacsha256v<n>:<hex>` hash only.
- **An agent can never starve `dp-remote`** (`BE-D66`): agent steps fail fast without a live session and lease
  (`agent.session.absent`), and at most `sm.dp.agent.max-concurrent-requests` agent requests are claimed at once.
- **Every port that a later task implements is declared by T2.1a with a contracted stub and a test of the stub**, so no
  task compiles against something a later task creates (plan 015 Global constraint 11).

## What the persistence implementation fixes (plan 015 T2.1b, as amended by its fix round of 2026-10-10)

The JDBC implementations in `dp-core` (`apply.persistence.agent`) prove the matrix above through the contract kits in `dp-api`'s test
sources. Where `V028`/`V030` could not hold what a port's signature implies, the implementation chose the safe reading; none changed a
signature (HF18). Each is a statement for the tasks that build on it:

- **`AgentLeasePort.current` never returns a grant.** A lease is stored as the digest of its renewal nonce only. `current` answers empty
  when no lease row has ever existed and `LEASE_RENEW_REQUIRED` when one does; a reconnecting agent renews with the nonce it holds.
  `issue` is the first issuance only: `ISSUE_NOT_ALLOWED` whenever any lease row exists (design 4.6, `AGW-D15`; `AGW-D19` item 3's
  "may be replaced" is NOT built, see its clarification), except the replay of a lost reply.
- **A lost reply is replayed, not locked out.** The lease issuer is deterministic in its inputs (identical inputs, identical grant). A
  retried `issue` or `renew` asks it again; if the nonce digest of the grant equals the ACTIVE row's, that grant is returned and nothing
  is written. Every grant is validated before any write (`GRANT_INVALID`: certificate, target, epochs, capabilities within the
  identity's, ceilings and margin, a lifetime covering the longest ceiling plus margin, placements within the DP-supplied set).
- **Fencing is inside the write.** The standing check reads the session and identity rows `FOR SHARE` as the first statement of the
  write's own transaction; a `claim` and a revocation both lock the same rows, so neither can land between check and write. The command
  writes also join the session table on the epoch. `claim` is one statement that refuses a revoked identity.
- **`claim` is fenced, not replayed**; it persists protocol, agent version (empty is `unknown`, capped at 64), declared features and the
  clock offset (`V030`; DP now minus the agent's clock, clamped to a day). `heartbeat` refreshes the offset; `last_seen_at` is always the DP clock.
- **Where an organization is derived.** `resolveByThumbprint` (certificate) and `AgentAuthority.requireEpochHolder` (and so `requireOwner`
  and `requireSession`; agent id, because `AgentSessionRef` has no tenant) are the only two statements that derive one, and arch rules
  name both. `completion`, `agentsWithDeliverableWork` and `close` are bound (placement tenant; the session table; the tenant `AgentAuthority` derived).
- **Not persisted (no column)**: `AgentCommandIntent.supersedesGeneration`, the ack's free-text message and `containerStartedAt`; a
  `CommandNotice` carries a plain digest only if the in-memory payload source still holds it. The sweep answers `RENEWAL_REQUIRED`, never `REISSUED`.
- **Report and telemetry are persistence helpers, not ports**: `JdbcAgentReportRepository` and `JdbcAgentTelemetryWatermarkRepository`
  implement neither `ActualStatePort` nor `AgentTelemetryPort`; T3.3 and T3.4 own those beans (an arch rule keeps any production class from
  implementing them before then). The kits run them through test-scope adapters.
- **Audit** writes the origin's correlation id and address; the first writer of a request id wins and a reuse for another event is a no-op.

## Compatibility

The generator input is `contracts/agent/v1/agent_gateway.proto` (moved from `go-agent/proto/agentgateway/v1draft/` by T1.2,
`AG-D13`'s exit condition), with the historical package and field numbers retained and the five wire additions of T2.3 appended
with new field numbers only (`AGW-D11`; draft in [`proto-amendments-015.md`](proto-amendments-015.md)). Java bindings are
generated during the `agent-gateway` Maven build; Go bindings are generated by `buf` against the same directory.

## What changed from the branch's matrix

| Branch row or sentence | Here | Why |
|---|---|---|
| `AgentGatewayPorts` aggregate | not built — each port is wired and kit-tested on its own | §4.2.1 |
| `AgentActualStateQueryPort` / `AgentActualState` | replaced by `AgentObservedStatePort` / `AgentObservation` | the adapter's read side for `status`; T2.1b stub, T3.3 implementation (P15-1) |
| `TelemetryIngestPort.ingest`, `CanonicalTelemetrySample`, `TelemetryQueryPort` | `AgentTelemetryPort.ingest`; samples map to the built `IngestSample`; no query port | `AGW-D27`; the metrics API is the built one |
| payload "opaque … returned after authorization" | in-memory hand-off, re-render-and-compare on a miss | `AGW-D26` (`OR-126` A5) |
| lease from the CP with `binding_ids` held in `sm_control` | the DP supplies the placement set | `AGW-D24` (`OR-126` A3) |
| "branch stores gateway telemetry in agent-scoped staging tables created by `V018`" and the release-prerequisite sentence | removed — telemetry lands in `sm_metrics` through the built port | `AGW-D27` |
| `EnrollmentCertificateIssuer.issue` → `EnrollmentResult` | `issue` → `EnrollmentIssuance` (tenant, catalog solution, solution, declared package version, environment, target, `UUID issuingPrincipalId`, certificate material); `dp-core` derives the tenant-free `EnrollmentResult` for the gateway | the `agent_identity` NOT NULL columns; the gateway never sees the tenant |
| `completion(CommandId)` | `completion(AgentPlacementRef, CommandId)` | scoped read, `I5` |
| `agentsWithDeliverableWork` default, two-argument `pendingFor` default | abstract / removed | the implementation binds `(tenant, agent)` pairs and the interval is configured, not hard-coded |
| (absent) discovery, declarations, `AgentRequestOrigin`, `liveFor`, `rematerialize` | added | `AGW-D30`, `AG-D28`, `BE-D70`, `BE-D66` |
| generator input `go-agent/proto/agentgateway/v1draft/…` | `contracts/agent/v1/` | T1.2 |
| `TelemetryIngestPort`/`TelemetryBatch`/`TelemetryResult` | `AgentTelemetryPort`/`AgentTelemetryBatch`/`AgentTelemetryResult` | name collision with the built `dp.api.metrics.ingest.TelemetryIngestPort`; the batch's `labels` are agent-supplied (`I5`) |
| `poll` / `release` / `enqueue(agentId, CommandPayload)` / `claim(requestId, thumbprint, protocol, version)` | `pendingFor` / `close(ref, CloseReason)` / byte-free `enqueue(AgentCommandIntent)` / `claim(thumbprint, AgentRequestOrigin)` | T2.1a signatures (`I6`; `BE-D70`); ids are `UUID`/`CommandId`, not strings |
| `OperationCorrelation(applyId, intentId, attemptId, step)` | `(applyId, intentId, intentKey, attemptId, step)` | `intentKey` is DP-side (null when mapped from the wire); the key never contains the attempt |
| `AgentOperation` PREFLIGHT/APPLY/RESTART/STOP | adds `REMATERIALIZE` | T2.3 wire |
| (absent) `GatewayCode` dotted codes | `AGENT_SESSION_ABSENT`, `AGENT_SESSION_LOST`, `AGENT_PAYLOAD_RENDER_CHANGED`, `AGENT_OPERATION_UNSUPPORTED`, `AGENT_DISCOVERY_*`, each with `wire()` = the dotted string | T2.1a; the wire `ErrorCode` mapping of the new codes is T3.2's |
