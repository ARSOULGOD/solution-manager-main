# Plan 015 T0.3 Step 4 -- decision texts for `AGW-D23`-`AGW-D32`, `BE-D66`-`BE-D71`, `AG-D21`-`AG-D29` (DRAFT)

> **Registered 2026-10-08 by T0.1; the three registers are authoritative; this file is not updated.** The W0 fix round (2026-10-09:
> `OR-129`, `OR-130`, the review's wording corrections) edited the registers only, so where this file differs, the registers win.
> It is kept as the reviewed source the registers were copied from.

**Status: DRAFT, reviewed in T0.3 Step 5.** These are the full texts T0.1 Step 2 copies into the three registers
(`docs/design/backend/agent-gateway/decisions.md`, `docs/design/backend/decisions.md`, `docs/design/go-agent/decisions.md`)
once reviewed (`P15-20`). Every decision here is **PROPOSED** (`I4`); every supersession is **by citation** and edits no
earlier row (`I3`). Numbers are the plan's proposed allocation (plan 015 section 7) and were measured 2026-10-09 on `2568862f` (no renumbering).

| | |
|---|---|
| **Authority** | plan 015 revision 2 (approved, `OR-128` (1)); `OR-126` A1-A10; `OR-127` L1-L4; `OR-128` (1)-(17) |
| **Written against** | this tree (`p/agw` @ `e6334622`), the dp-remote integration head `x/w4-int` @ `a6cec7c7`, and `origin/dp-agent-integration` @ `87e338e1` (read with `git show`) |
| **Companions** | [`proto-amendments-015.md`](proto-amendments-015.md) (the wire), [`agent-console-contract-draft.yaml`](agent-console-contract-draft.yaml) (the console), [`schema-015-draft.sql`](schema-015-draft.sql) (the DDL) |
| **Open on purpose** | **`AG-D29`**: `OR-128` (14), the Go agent's payload spool, is **still open**. The text below records both variants and decides neither. |

Section index: [AGW-D23](#agw-d23) [D24](#agw-d24) [D25](#agw-d25) [D26](#agw-d26) [D27](#agw-d27) [D28](#agw-d28)
[D29](#agw-d29) [D30](#agw-d30) [D31](#agw-d31) [D32](#agw-d32) | [BE-D66](#be-d66) [D67](#be-d67) [D68](#be-d68)
[D69](#be-d69) [D70](#be-d70) [D71](#be-d71) | [AG-D21](#ag-d21) [D22](#ag-d22) [D23](#ag-d23) [D24](#ag-d24)
[D25](#ag-d25) [D26](#ag-d26) [D27](#ag-d27) [D28](#ag-d28) [D29](#ag-d29) | [DDL and wire findings](#findings)

---

# `AGW-D*` (to `docs/design/backend/agent-gateway/decisions.md`)

## <a id="agw-d23"></a>`AGW-D23` -- The agent session terminates at the Data Plane; the launcher composes the gateway

**Status: PROPOSED.** **Records** `OR-126` (A2), the owner's ruling that amends LOCKED `B/D16` by name (the `OR-1` form) for the
`host-local` path only; this text re-decides nothing and `B/D16` is not edited (it stands for everything else). Restates the
deployment clause of `AGW-D1`; changes nothing else in it.

1. The `host-local` agent's session (mTLS, lease, commands, reports, telemetry) terminates at the **Data Plane**, not the
   Control Plane. Enrolment, the agent CA, revocation epochs and the agent's registration stay in the Control Plane, reached
   from the gateway only through the launcher's three adapters (`BE-D69`). `dp-remote` is untouched.
2. `agent-gateway` is imported by **the launcher only**. The launcher starts the gRPC listener **only when `sm.agw.listen`
   is set**: a `dp-remote`-only install opens no port. There is **no global runtime switch** (`sm.dp.agent.runtime.enabled`
   is not ported) and **no second `ApplyExecutor`**: the adapter is chosen per placement (`BE-D66`).
3. One process, one gateway replica in v1 (`AGW-D1`, `AGW-D32`, `OR-44`).

**Rationale.** `B/D16` puts the session at the CP; `SM-D24`/`SM-D28` re-home it and the architect group has not ruled. The
owner ruled for this repo that an agent that must keep a long-lived stream, a lease and a command journal belongs next to the
Data Plane that owns the apply engine, the config of record and the telemetry sink (`OR-126` A2). A listener that exists only
when configured keeps the no-footprint customers of `REQ-008` exactly as they are.

**Rejected.** *Terminating at the CP* -- a CP that holds command state is a CP that holds data (`I7`, `D4`'s thin-CP rule).
*A global enable property* -- two worlds in one process is the branch's second `ApplyExecutor`; per-placement routing makes
coexistence the default instead of a mode.

**Enforced by:** `AgentGatewayHandshakeIT` (G-A0: the launcher serves the listener and a JCSMP session in one JVM);
`AgentGatewayBoundaryTest` (`agentGatewayUsesDpApiOnly`, `dpNeverImportsAgentGateway`, each with a planted violation);
`LauncherOpensNoPortWhenGatewayUnset`; `ProductionApplyConfigurationTest#exactlyOneApplyExecutor`.

**Ordering.** Ratifies after `BE-D*` and `AG-D*` (family order). The `B/D16` amendment is the owner's ruling (`OR-126` A2); the architect group's ratification of it is the open `SM-D24`/`SM-D28` question, not this text's.

## <a id="agw-d24"></a>`AGW-D24` -- The Control Plane issues the lease; the Data Plane supplies the placement set; the CA signs from an operator keystore

**Status: PROPOSED.** Records `OR-126` (A3, A4). **Supersedes `AGW-D15`'s mechanism** (properties 1-4 and 7: the
request ledger, owner tuple, exactly-once completion, never-abandoned recovery, pruning) **as built in v1**, and keeps its
properties 5 and 6 (a grant is validated before it is trusted; delivery reads the stored row and nothing else). **Changes one
claim of the branch's design:** the lease's placement set.

1. Leases are issued by the **CP**, as built on the branch (`ControlPlaneLeaseIssuer`, `BE-D69`). The request carries the
   **set of live placement ids on the agent's target, supplied by the DP**; the CP signs exactly that set and **never invents
   one** (the CP holds no placement, `OR-27`). The CP registration has no `binding_ids` (`VCA`). On the wire the lease's
   `binding_ids` carry placement ids (`AGW-D25`).
2. The DP records the grant it was handed in `agent_lease` (history; one `ACTIVE` row per agent; the renewal nonce stored
   only as a digest, `I6`). The delivery poll and the report intake read **that row and nothing else** (`AGW-D15` property 6).
3. The agent CA signs from an **operator-supplied PKCS12 keystore** in v1; with no keystore the process **fails closed at boot**.
   Sealing the CA in `sm_secret` is a later option (`OR-126` A4).
4. **Lease content the agent depends on.** `capabilities` are fixed by the server at issue (`apply-config`, `lifecycle`; `report-only`
   is not issued). `verb_ceilings` carry `preflight` 30 s, `apply` 60 s, `restart` 120 s, `stop` 60 s and **`rematerialize` 60 s**
   (a missing key is a zero ceiling and every step of that operation fails `step deadline exceeded`, `AGW-D17` item 3; review R-7).
5. The enrolment token is 256 random bits shown once; its stored digest is an unkeyed SHA-256 (`OR-128` (9), reasoned outside
   `OR-32`'s scope: a preimage-resistant digest of a high-entropy random value cannot be used to test guesses).

**Residual, stated.** `AGW-D15`'s ledger defended the case where the CP spends the single-use nonce and the DP dies before it
records the grant. This decision builds no ledger: recovery is the CP honouring the same deterministic request id on
re-presentation (design section 13 `AGW-18`); without it the session ends `RE_ENROLL_REQUIRED`, an operator event on a bounded
path. **Flagged to the reviewer: this is a real reduction of `AGW-D15`, and the plan's section 7 names it only as "supersedes
`AGW-D15`'s gate".**

**Rejected.** *DP-minted leases* (the brief's option, not taken by `OR-126` A3) -- a second claim signer. *The branch's
`binding_ids` in the CP* -- the CP would hold the placement set, the exact data the thin CP must not hold.

**Enforced by:** `ControlPlaneAgentGatewayServiceTest` (a lease's claims name agent, organization, environment, target and the
DP's set); `CompositionBoundaryTest` (planted fourth adapter); `AgentSchemaIT#agentLeaseHoldsNoPlainNonce`; a boot test
(no keystore refuses to start).

## <a id="agw-d25"></a>`AGW-D25` -- The placement is the replica; the replica registry is not built

**Status: PROPOSED.** Records `OR-128` (10) (brief Q4 (b)). **Supersedes `AGW-D16` as the authority for telemetry labels**
and for replica minting; `AGW-D16`'s grammar and cap *intent* survive as the placement's own CHECKs and the per-target cap
(`AGW-D30`). Edits neither `AGW-D16` nor `AGW-D9`.

1. On the agent path there is **one replica per placement**: the engine's `ReplicaRef` is the placement id, and the wire's
   `binding_id` carries the **placement id** (field names are unchanged for compatibility).
2. An `ActualStateReport` is accepted only if its `binding_id` is in the active lease's placement set **and** its
   `replica_ref` equals the placement's `container_name`; otherwise `REFUSED` with `BINDING_NOT_IN_LEASE` /
   `REPLICA_REF_INVALID` and an `agent_event`.
3. The `ReportAck` still returns the two ids the Go agent labels every sample with (it holds a batch until it has them:
   `reports.IDTable`, `send/runner.go`): `replica_id` = the **placement id**, `incarnation_id` = a **derived** UUIDv5 of
   `(placement id, reported incarnation)`. Nothing is minted or stored; the DP can recompute both. **If the intake returned
   no ids the agent's telemetry would never start** (measured in the agent's code, not assumed).
4. No `agent_replica` tables exist (`VDA` has none). Counter-reset evidence comes from the start observation (`AGW-D28`).

**Rationale.** A Docker host runs one container per placement (`OR-29` makes `container_name` unique per target). A two-level
registry with caps and retirement solves a problem the placement tables already solve (uniqueness, cap, retirement), and its
`AGW-17`/`AGW-19`/`AGW-20` gates are not needed by a model with no registry.

**Rejected.** *Building `AGW-D16`* -- three tables and five gates for a cardinality the placement already bounds. *Letting the
agent propose the ids* -- the entire point of `AGW-D9`'s label rule.

**Enforced by:** `AgentReportIntakeIT` (not-in-lease and wrong-`replica_ref` refusals; both ids returned);
`AgentTelemetryBridgeIT` (labels are exactly the three ids); a schema test that `VDA` creates no `agent_replica%` table.

## <a id="agw-d26"></a>`AGW-D26` -- Render once, hand over in memory, re-render and compare on redelivery; the plain digest is transient

**Status: PROPOSED.** Records `OR-126` (A5) and `OR-32`. **Supersedes only the *storage* part of `AGW-D17` item 2** (the
DP stored `sha256:<hex>`); item 2's *wire* part (bare lowercase hex on the wire) stands.

1. **SM persists no rendered byte.** No column of any agent table can hold one (no `bytea`, nothing named payload, rendered
   or sha256 -- `AgentSchemaIT`), and `agent_command` holds no bytes.
2. The bytes an agent receives are the `RenderedConfig` the apply engine **already rendered for the step**
   (`ApplyExecutor` binds `config.hash()` into the `MATERIALIZE` intent). The adapter puts them in `AgentPayloadHandoff`
   (in memory, keyed by command id, bounded by `sm.dp.agent.handoff.max-entries` = 64, removed at ack, refusal,
   supersession or deadline, never logged). A pull reads the hand-off.
3. **Only on a hand-off miss** (SM restarted since enqueue) is the generation re-rendered through `RenderedConfigSource`,
   and it is sent only if `hasher.hash(tenant, bytes)` equals `agent_command.config_hash`; otherwise the pull is refused
   `agent.payload.render.changed` (wire `ERROR_CODE_RENDER_CHANGED`), the command is `REFUSED` and the attempt fails with that
   non-retryable classifier.
4. Every hash SM **stores** is the keyed `hmacsha256v<n>:<hex>` of `ConfigHasher` (`OR-32`). A plain SHA-256 exists only on
   the wire and in `AgentHashBridge`'s in-memory `(placement, plain) -> keyed` map, and never in a log line, exception message,
   audit row, metric or response. (The plain digest also lives in the agent's own on-host command journal, `AG-D4`: that copy is the
   agent's, not SM's, and is recorded in `AG-D29`; review R-19.) `status` resolves the reported plain digest through the bridge; on a miss it renders at most
   the placement's intended and last-applied generations once per process (cached), never once per poll.

**Rationale.** A rendered config can contain resolved secrets (`I6`). Persisting it "so redelivery is cheap" would make
`agent_command` the largest secret store in SM; re-rendering is cheap and the keyed hash proves it is the same file.

**Also stated.** `AgentSchemaIT` forbids `bytea` and any column named for a payload, rendered content or sha256 in every `agent_%`
table; `agent_lease` therefore stores its renewal-nonce digest as 64 hex characters of `text`.

**Rejected.** *The branch's `JdbcAgentRenderedConfigSource` (render at pull, from storage)* -- renders twice and stores. *Storing
the plain digest* -- it lets anyone with database read access test guessed secrets, the exact attack `OR-32` closes.

**Enforced by:** `AgentRuntimeAdapterContractTest` render-once spy (zero `RenderedConfigSource` calls on the hand-off path);
re-render equal/unequal tests; "twenty `status` polls render at most twice"; `AgentSchemaIT#noAgentTableCanHoldRenderedBytesOrAPlainDigest`;
`#aPlainHashIsRefusedInAgentCommand`; the redaction IT over captured JVM and agent logs (T4.4); a contract-kit case that `pull`,
`ack` and `received` bind the caller's `(tenant, agent)` to the command, so another agent's command id yields neither its payload
nor an outcome (review R-23).

## <a id="agw-d27"></a>`AGW-D27` -- Agent telemetry is re-sunk through the built ingest port; acknowledged on commit only

**Status: PROPOSED.** Records `OR-126` (A7), `OR-128` (7), (8). **Amends `AGW-D9`** (the producer and label rules; the port,
the credits and the transport are unchanged). Departs from `OR-92` OQ-4 by name, as `OR-128` (7) rules.

1. The branch's own telemetry tables (`series`, `metrics_raw`, the sample/gap tables) are **not ported**. The gateway hands a
   batch to `AgentTelemetryPort`; `dp-core`'s `AgentTelemetryBridge` resolves organization and placement from the session and
   lease (`I5`; never from the batch), maps samples to `IngestSample` with `SourceDiscriminator.AGENT`, and calls the **built**
   `dp.api.metrics.ingest.TelemetryIngestPort`.
2. **Ack only on `Committed`.** `RetryAfter` and `Pending` are **not** acked (the agent's disk buffer keeps the batch; `Pending`
   is reserved and "never returned in v1", so it also counts `agent.telemetry.pending.unexpected`); `Rejected("placement.unknown")`
   is dropped, counted and acknowledged-as-rejected (never retried forever). The switch over `IngestResult` is exhaustive.
3. An undeclared metric is dropped and counted (`OR-75` M-Q6). A duplicate `batchSeq` is ignored
   (`agent_telemetry_watermark`).
4. **Cadence is `OR-73`'s fixed 5 s collection / 1 min push**; the package's `intervalSeconds` does not apply on this path
   (`OR-128` (8)). `TelemetryDeclarations.interval` carries the 5 s.
5. **Sample timestamps** are accepted within a bounded skew window of the DP's receipt time (`OR-128` (7)). *The window is not
   ruled; a value is proposed here for the review: from **24 hours** before (the agent's default buffer age, `AG-D24` -- a shorter
   window would discard every buffered batch after an outage, defeating `AGW-D8`; review R-11) to 30 seconds after receipt, a sample
   outside it dropped and counted.* Open point: `ingest_counter.reason` is a closed CHECK without an `out_of_skew` member; T3.4 either
   reuses `out_of_order` or an `sm_metrics` migration adds the reason. Not decided here.

**Rationale.** The built port already owns dedupe, ordering (`out_of_order`), the `I6` secret filter, the series cap and the
rollups. A second sink would be a second meaning of "sample".

**Rejected.** *The branch's separate store* -- two `sample`s. *Ack on receipt* -- `AGW-D8`'s rule: a batch the DP did not
commit must stay in the agent's buffer. *Receipt time only* -- twelve 5 s samples collapse onto the batch's arrival.

**Enforced by:** `AgentTelemetryBridgeIT` (commit/RetryAfter/Pending/Rejected/duplicate/undeclared; `source_discriminator = 'agent'`).

## <a id="agw-d28"></a>`AGW-D28` -- Agent reports are taken into the tables SM already has

**Status: PROPOSED.** Closes central item 44 when reviewed. Amends nothing; applies `OR-39`, `OR-101`, `OR-112`.

`AgentReportIntake` (`dp-core`) implements `ActualStatePort` and writes through the existing repositories:

1. **Health** -> `placement_health`, **persisted on change only** (`OR-39`). The agent reports a fact (`HealthState`); the DP maps.
   **A placement whose package declares no probe is `NOT_APPLICABLE` whatever the agent reports** (`OR-101`). With a declared probe:
   `HEALTHY` -> `GREEN`; `UNHEALTHY` -> `RED`; `STARTING` -> `bootstrap`; `UNREACHABLE`, or no report newer than the staleness
   window -> `unreachable`; `NOT_DECLARED` -> `not_exposed`. `RESTART_EXPECTED` and `INACTIVE_REPLICA` remain the engine's. *Two
   choices flagged for review: the `STARTING` mapping, and that a Docker health check **stands in** for the package's declared probe
   (`proto-amendments-015.md` D-4): it is not that probe and carries no strength, so a `rolling-health-gated` gate on an agent
   placement proves what Docker's check proves.*
2. **Start time.** The ack's host-clock `container_started_at` (APPLY, REMATERIALIZE, RESTART) is recorded in **one DP transaction**
   as the attempt's `write_baseline` (`OR-112`) **and** as the placement's `placement_start_observation`; a report's
   `container_started_at` updates the observation by the same rule. **The observation only ever moves forward**, so a stale report can
   never lower the baseline the engine reads back (review R-8); the ack's value is the baseline everywhere (this replaces the plan's
   "back through `status().startBaseline()`" wording, which read the latest report). A start **different from and later than** the
   baseline drives the ladder to `restart-observed`. No data-plane timestamp is compared with a host one anywhere.
3. **Ordering and replay.** `agent_report_watermark(last_sequence, last_item_id)` per `(agent, placement)`: an older `sequence` is
   ignored; the same `item_id` is answered from the watermark; a report for a placement the lease does not hold is refused with an
   `agent_event`. `ReportAck` returns the ids of `AGW-D25`.
4. **Trust** is the lease's verdict (active, epoch, scope) as before; this decision adds no second one.
5. `AgentObservedStatePort#latest` returns what the last accepted report said (liveness, health, plain SHA, start time) for
   the adapter's `status` (`BE-D66`).

**Rejected.** *Collapsing agent health into the dp-remote probe path* -- the poller skips agent placements; two writers of one
row. *Inferring healthy from running* -- `OR-101`.

**Enforced by:** `AgentReportIntakeIT` (one `placement_health` row per change, none for a repeat; older sequence ignored;
not-in-lease refused and audited; start later than baseline -> `restart-observed`); mutation proofs in T3.3.

## <a id="agw-d29"></a>`AGW-D29` -- `agent_event`: the DP's append-only audit of gateway decisions

**Status: PROPOSED.** Closes central item 45 when reviewed. Complements, and does not replace, the CP's `change_audit`
(`OR-19`/`OR-25`), which audits governance writes; this audits gateway decisions that write no governance row.

`sm_data.agent_event` is append-only for the runtime role (`SELECT`, `INSERT`; the grant is the guarantee). A row carries the
tenant, an event id, the agent (NULL only for a tenant-level event), a request id (**minted by the gateway per item, never
agent-supplied -- an agent must not be able to suppress its own audit rows by reusing an id; unique per tenant, so a replay of the
same item is idempotent**), a **closed `code` set** (`gateway.refusal`, `gateway.command.refused`, `gateway.drain`, `gateway.drain.complete`,
`gateway.revoked`, `agent.enrolled`, `agent.report.refused`, `agent.telemetry.rejected`, `agent.discovery.registered`,
`agent.discovery.refused`, `agent.discovery.settled`), an `actor_kind` (`AGENT`, `PRINCIPAL`, `SYSTEM`), the actor, a
**gateway-minted correlation id and the gRPC peer's address** (never an agent-supplied value), a `detail_code` (the closed
`GatewayCode` vocabulary) and a time. It holds **no payload, no digest, no token and no host path** (Global constraint 9). Every
discovery refusal and every report refusal is an `agent_event`.

A refusal **before any identity resolved** (an unknown certificate thumbprint) has no tenant and so no row: it is a counted metric
(`agw_admission_rejected_total`, `AGW-D20`), stated here so its absence is not read as an oversight (review R-15).

**Rejected.** *Free-text details* -- `I6`. *Putting these in `sm_control.change_audit`* -- they are DP facts and `I7` keeps the
planes' audits apart.

**Enforced by:** `AgentSchemaIT#agentEventIsAppendOnlyForTheRuntimeRole` (`42501` on UPDATE/DELETE); the redaction IT.
**Retention:** none in v1 (events are not deleted by `AGW-D31`).

## <a id="agw-d30"></a>`AGW-D30` -- `OR-27` discovery: container facts, the DP derives the placement, quota and cap, tombstones, a tenant-bound completer

**Status: PROPOSED.** Records `OR-126` (A6), `OR-27`, `OR-40`, `OR-128` (11), (15). Applies `BE-D32`'s declare protocol and
`BE-D49`.

1. **The agent reports facts, not conclusions** (`AG-D26`): container id, name, image reference, the `com.solace.sm.*` labels,
   bind mounts, published ports. Every placement field is derived by the DP from those facts and the **enrolment record**
   (table in plan 015 T4.1): `container_name` must start with the target's `allowed_container_prefix`, `remote_config_directory`
   is the bind mount whose destination is the label `com.solace.sm.config-file`'s directory and must lie inside
   `allowed_config_root`; refusals `agent.discovery.outside.envelope` / `agent.discovery.incomplete` come **before** V007's trigger
   is reached.
2. **No identity from the agent.** The report names no environment, organization or target; the instance is created in the
   environment and solution the agent was **enrolled** into (`OR-27`), `created_by` the enrolling principal. The DP holds that
   record itself: `agent_identity` carries `environment_id`, `catalog_solution_id`, `solution` and `declared_package_version`,
   copied at enrolment (`BE-D71` step 2), because the DP cannot read the CP (`I7`) and `instance_state` needs all of them (review R-3).
3. **Cap and quota.** At most `sm.dp.agent.max-placements-per-target` (default 16, `OR-128` (11)) live placements per agent target
   (`agent.placement.cap`); the CP quota (`OR-40`) is checked at the governance write; a name already held by a live instance is
   refused (`instance.name.taken`). The three unique indexes a placement can trip (`placement_container_once`,
   `placement_config_dir_once`, `placement_health_port_once`) are **pre-checked and answered with codes**
   (`agent.discovery.container.taken`, `agent.discovery.config.taken`, `agent.discovery.port.taken`), never as a raw `23505`.
   **A refusal after the reservation (the CP quota, a conflict) abandons the pending instance and retires its placement in the
   same DP transaction** (`BE-D32`'s `abandon`), so a refused container holds neither a name nor one of the 16 slots (review R-10).
   Every refusal is an `agent_event`.
4. **Tombstones.** `agent_discovery` is keyed `(agent, instance_label, container_name)`. `REFUSED`, `ABANDONED`, `UNDECLARED`
   and `RETIRED` are **terminal**: a re-report answers the stored state and mints nothing. `undeclareInstance` (`BE-D49`: the
   workload keeps running) and `retirePlacement` set `UNDECLARED`/`RETIRED` **in the same DP transaction**; the `BE-D32` sweep's
   abandonment of a pending agent-declared instance sets `ABANDONED` in the same transaction. Release: `REFUSED`/`ABANDONED` when
   the container is re-created (a new `container_id` under the key) or its label value changes; `UNDECLARED`/`RETIRED` **only**
   when the label value changes (a CI/CD redeploy must not silently re-register what an operator removed). A re-created
   container of a `REGISTERED` key keeps its placement.
5. **The completer is tenant-bound and enumerates no tenant** (`OR-128` (15), plan option (b)). After its own commit,
   `AgentDiscoveryService` calls the `dp-api` sink `AgentRegistrationCompletion` with the tenant resolved from the agent's
   identity and the RPC's `AgentRequestOrigin`; `console-gateway`'s `AgentRegistrationCompleter` implements it, registers the CP
   governance node through the declare protocol under `AGENT_INSTANCE_REGISTERED` **with the agent as actor** (`BE-D70`), then
   confirms the DP row `REGISTERED` (a refusal -> DP `REFUSED` + `agent_event`). The durable contract is the `PENDING` row: a crash
   before confirmation is finished by the next `Connect`'s discovery report or tombstoned by the sweep; the in-memory call is never
   the sole contract (`AGW-D2`). No `OR-113` (7) amendment is needed.

**Rejected.** *A background loop enumerating pending discoveries across organizations* -- `OR-113` (7) forbids it and `OR-122` (2)
excepted only the schedule firer. *A conclusion-bearing report* ("register this as X") -- the agent would be choosing the DP's data.
*Re-registering on every report* -- an operator's undeclare would not stick.

**Enforced by:** `AgentDiscoveryIT` (`undeclareThenReconnectDoesNotReRegister`, `retirePlacementThenReconnect...`,
`sweepThenReconnectDoesNotReMint`, cap 16/17, envelope, name taken, release rules); `AgentRegistrationCompleterIT` (quota, crash
between CP write and confirm, actor); `TenantEnumerationBoundaryTest` unchanged and green; `AgentSchemaIT#everyAgentTableLeadsWithTenantId`.

## <a id="agw-d31"></a>`AGW-D31` -- Retention is an owner-owned, argument-free function called by the daily loop

**Status: PROPOSED.** Closes central item 47 when reviewed. **Supersedes `AGW-D22` item 2** (a separate one-shot maintenance
process with the migrator credential, run by the deployment's scheduler) with the `OR-77` pattern the metrics store already uses.

`sm_data.agent_gateway_maintenance()` is `SECURITY DEFINER`, owned by `sm_dp_owner`, takes **no arguments**, pins its
`search_path`, and `EXECUTE` is granted to `sm_dp_runtime`. The retention intervals are **constants inside it**: terminal
(`ACKED`/`REFUSED`/`SUPERSEDED`) commands older than 30 days **except the highest `wire_generation` of each (agent, placement)**,
non-`ACTIVE` leases expired more than 30 days ago, and session rows closed for more than 30 days. **The generation rule is not
optional:** the agent's journal refuses any generation below the highest it has seen and replays the stored outcome of one it holds, so
deleting a placement's last command would let the next command reuse a number and either never run or be reported done when it was not
(review R-1). It **never** deletes a live command, an `agent_discovery` tombstone (the tombstone is the contract that stops
re-registration), an identity, an `ACTIVE` lease, or an `agent_event`. An advisory lock makes a concurrent run a no-op. It is called from the existing daily background loop beside
`ensure_metric_partitions()`.

**Flagged, not resolved.** `OR-77` is a narrow `I5` carve-out for clock-derived **DDL** inside the function. A cross-tenant
`DELETE` by constants is the same shape but a different verb; the plan treats it as covered by "the `OR-77` pattern". Whether
that reading needs the owner's explicit ruling is for the reviewer to say. Also stated: deleting a closed session row resets
that agent's fencing epoch to 1 on its next claim; after 30 days of silence no live writer can hold the old epoch.

**Rejected.** *The scheduler-run process* -- a second credential and a deployment artifact for a 30-day sweep. *An `agent_event`
sweep* -- audit rows are evidence; keeping them is the default until the owner rules a horizon.

**Enforced by:** `AgentMaintenanceIT` (old terminal commands superseded by a newer generation, long-expired leases and closed sessions gone after one
call; live ones, the highest generation of every pair, the `ACTIVE` lease and every tombstone stay -- **and after retention a new
command is numbered above the old maximum**; the function takes no argument; `SECURITY DEFINER` with a pinned `search_path`).

## <a id="agw-d32"></a>`AGW-D32` -- Single-replica machinery in v1

**Status: PROPOSED.** Records `OR-128` (10) (brief Q10 (b)) and `OR-44`. Amends nothing; narrows what is *built* of `AGW-D3`,
`AGW-D10` and `AGW-D14`.

v1 runs **one SM replica and one gateway replica** (`OR-44`, enforced by its singleton lock). Built: the session-ownership row
with a monotonic fencing epoch (`AGW-D3`), epoch-fenced writes, revocation reconciliation (`AGW-D19`), planned drain
(`AGW-D18`). **Not built:** a claim ledger across replicas, poll-routing between replicas (`AGW-D10`), two-gateway integration
tests. The extraction plan (`AGW-D14`) is unchanged and is where multi-replica machinery is owed. Central item 42 (two `SM-D28`
claims under replication) stays open and does not block plan 015.

**Rejected.** *Building multi-replica now* -- it multiplies the test matrix for a deployment shape v1 forbids.

**Enforced by:** the singleton-lock test (`OR-44`); `AgentGatewayBoundaryTest` (no datasource in the module).

---

# `BE-D*` (to `docs/design/backend/decisions.md`)

## <a id="be-d66"></a>`BE-D66` -- A blocking agent adapter behind per-placement routing; commands keyed on the intent key; fail fast; capped

**Status: PROPOSED.** Records `OR-128` (10) (brief Q6 (b)). Extends `BE-D13`'s write-before-act by one hop.

0. **The reference to the intent is one composite key** `(tenant, apply, intent, intent key, placement)` into `apply_step_intent`, and a
   command may address only a placement on its agent's own target (trigger), so a row cannot name two intents or another agent's
   placement (review R-13).
1. **The agent path is a `RuntimeAdapter`** (`AgentRuntimeAdapter`), blocking behind the existing seam, chosen **per placement**
   by `RoutingRuntimeAdapter` from `target_connection.transport` (`ssh-docker` -> SSH, `agent` -> agent; an unknown transport is
   **refused, never defaulted**). One `ApplyExecutor`. `RuntimeOperationContext` correlates a command with the journal
   (apply, intent, intent key; the attempt rides as correlation only).
2. **Command identity.** An agent command is keyed on the apply journal intent's **attempt-free** `idempotencyKey`
   (`applyId:step:replica`) **and its operation** -- `rematerializeIfCurrent` reuses the `MATERIALIZE` intent
   (`ApplyExecutor`), so an `APPLY` and a `REMATERIALIZE` of one intent are different commands (review R-6). `enqueue` returns, in
   order: the **live** command for that `(key, operation)`; else the latest **`ACKED` with success**, **but only if no attempt on that
   intent closed `FAILED` after it** (a DP-side failure after the agent's success -- a read-back that failed -- must re-send, not
   no-op); else a **new** command with a new wire generation -- only on the first execution or the engine's bounded retry
   after a terminal failure, refusal or supersession. A resumed apply calls `supersedeOutstanding` first (`QUEUED`/`OFFERED` of
   other keys -> `SUPERSEDED`; `DELIVERED` left to their ack or deadline). A re-sent command therefore carries the **same command
   id and wire generation**, which is what the agent's journal dedupes on: a `RESTART` runs **exactly once** across an SM crash.
3. **Fail fast.** Before enqueue, `AgentSessionPort.liveFor(agent, placement)` (open session **and** unexpired lease holding the
   placement); false -> `agent.session.absent` at once. A closed session or expired lease ends a wait with `agent.session.lost`.
   Per-operation ceilings (30/60/120 s) apply only while the session is live.
4. **An offline agent can never starve dp-remote.** The worker claims at most `sm.dp.agent.max-concurrent-requests` (default
   pool size - 1, boot-validated `>= 1` and `< pool size`; with the listener on, the pool must be >= 2) requests whose placement is
   on an agent target; at the cap the claim query skips agent-target requests.
5. **Operations:** `replicas` (one per placement, `ReplicaRef` = placement id), `prepare` (`PREFLIGHT`), `materializeConfig`
   (`APPLY`, no bytes in the command, `MaterializeResult(hasher.hash(tenant, bytes), runtimeRef)`), `rematerializeIfCurrent`
   (`REMATERIALIZE` with `expected_current_sha256`; no such generation -> `reconcile.target.moved` without sending),
   `startOrUpdate`/`stop` (`RESTART`/`STOP`), `status` (through `AgentObservedStatePort`), `assessRollback` (`Unknowable`),
   `restore` (refused `agent.rollback.unsupported`), `readConfig` (default refusal `config.read.unsupported`).

**Rationale.** The engine already serializes, journals and recovers; an adapter inherits all of it. Keying on the intent key makes
crash recovery a property of identity rather than of timing.

**Rejected.** *A global switch and a second executor* (the branch) -- coexistence is the product. *Keying on the attempt* -- a
resumed apply would mint a second `RESTART`. *Async/reactive adapter* -- the seam is blocking; the cap is the fix for blocking.

**Enforced by:** `AgentRuntimeAdapterContractTest` (the contract kit; `restore`/`readConfig` declared unsupported; an `APPLY` and a `REMATERIALIZE` of one
intent are two commands; a success followed by a failed attempt re-sends); `RoutingRuntimeAdapterTest`; **`AgentCommandCrashRecoveryIT`** (kill after `DELIVERED`, restart over the same database: restart
count 1, one command row); **`AgentStarvationIT`**; `ProductionApplyConfigurationTest#exactlyOneApplyExecutor`.

## <a id="be-d67"></a>`BE-D67` -- Agent-path parity is minimal-plus-health; everything else is a coded refusal

**Status: PROPOSED.** Records `OR-128` (10) (brief Q9 (a)). Every operation SM serves on a dp-remote placement or target either
**works** on an agent's through the same engine or answers a **contracted refusal** -- never a silent no-op (Global constraint 8).

*Works:* declare via `OR-27`, stage/submit/observe apply, rolling and ungated strategies, `restart-observed`, stop, fleet apply,
health (`AGW-D28`), metrics (`AGW-D27`), config ladder, `retirePlacement`, `undeclareInstance`.

*Refused* (HTTP **501 `AGENT_OPERATION_UNSUPPORTED`**, nothing read or written, no SSH connection constructed): `readLogs`,
`getLogLevel`, `setLogLevel`, `acceptRuntimeConfig` (reads the host's config), `restoreRuntimeConfig`, and -- *draft finding D-C2,
for the reviewer* -- `rollbackApply`, refused at admission so no doomed apply request is admitted. The health poller and the
metrics collector **skip** agent placements (they have their own path); their skip is tested.

*Follow-on slice (not v1):* logs, log level, read-config, rollback restore on agent placements.

**Rejected.** *Parity with logs and rollback in v1* -- each needs a new wire verb and agent code; none is `OR-27`'s point.
*Answering `200 unavailable` for logs* -- reads as a host problem.

**Enforced by:** endpoint ITs per refused operation (status, `code`, **no SSH spy call**); `HealthPollerIT#skipsAgentPlacements`;
`CollectionPlanBuilderTest#excludesAgentAcquisition`; `RoutingRuntimeAdapterTest`.

## <a id="be-d68"></a>`BE-D68` -- `target_connection` transport `agent`; `acquisition_mode` `agent`; the `enrolled` event

**Status: PROPOSED.** Widens `V007`/`V022` (`OR-22`: those files are never edited; `VDA` widens them). Built by plan 015 T1.1.

1. `target_connection.transport` admits `agent`. A **shape CHECK** makes each transport's fields all-or-nothing: `ssh-docker`
   needs host, port, user, pin and credential and no `agent_id`; `agent` needs `agent_id` and none of those (nor a credential
   fingerprint). The envelope stays `NOT NULL` for both, so `V007`'s `placement_envelope` trigger holds for agent placements.
2. One live agent target per agent (partial unique index); the agent-identity <-> target reference is **deferred**, so neither row
   can commit without the other.
3. `instance_state.acquisition_mode` gains `agent`.
4. `target_connection_event.kind` gains `enrolled`, with an `actor_kind` (`principal`/`agent`); `enrolled` <-> `agent` is a
   biconditional CHECK; `enrolled` carries no fingerprint.
5. `V012`'s pinned-key triggers are replaced to **skip a NULL pin** (an agent has none). *Found by the DDL draft, not by the
   plan: without it the first agent target fails `23502`.*
6. `OR-125` (1) (the placement actor) and (2) (the pinned key algorithm) are folded in at the final DDL after the dp-remote merge.

**Enforced by:** `AgentSchemaIT` (the shape tests, the envelope trigger on an agent placement, the `enrolled`-event tests, the
live-target index, the deferred reference).

## <a id="be-d69"></a>`BE-D69` -- The launcher's three cross-plane adapters: a second composer beside `OR-10`'s

**Status: PROPOSED.** Extends the composition rule `OR-10` / `BE-D3` state for `console-gateway`. *Flagged, not resolved:
whether this widening of "the only composer" needs ratifying with the `BE-D*` set; it is the branch's built shape and `I7`
holds.*

The gateway (`dp-api` only) needs three things only the CP has: lease issuance, enrolment certificate issuance and the agent's
registration state. The **launcher** implements `LeaseIssuer`, `EnrollmentCertificateIssuer` and `AgentRegistrationSource` over the
CP's own service interfaces (`ControlPlaneLeaseIssuer`, `ControlPlaneEnrollmentCertificateIssuer`, `ControlPlaneRegistrationSource`),
passing `AgentRequestOrigin` into the CP audit context. These are the **only classes in the launcher** that may adapt a `cp-api` type to a `dp-api` type; the `OR-27` completer is
`console-gateway`'s (`AGW-D30`), the existing `OR-10` composer. The CP target row at enrolment is written by the
enrolment-certificate adapter (`BE-D71`).

**Rejected.** *The gateway importing `cp-api`* -- `AGW-D2`. *Reading CP tables from the DP* -- `I7`.

**Enforced by:** `CompositionBoundaryTest` (the launcher may adapt exactly these three; a planted fourth fails);
`agentGatewayUsesDpApiOnly`.

## <a id="be-d70"></a>`BE-D70` -- An agent may be the `sm_control` actor of the agent operations (amends `OR-25` by name)

**Status: PROPOSED.** **Records** `OR-128` (5), the owner's ruling that amends `OR-25` by name: a runtime write must carry a *principal*
actor except `LOGIN_REFUSED`, `LOCKOUT_CLEARED` and the operator-command `ENTITLEMENT_SET`; the ruling adds the agent. This text
specifies the mechanism and re-decides nothing.

1. `AGENT_ENROLLED` (token consumption, certificate issue, the CP target row) and `AGENT_INSTANCE_REGISTERED` (the `OR-27`
   governance node) are written **by the agent**: they require an agent actor (`sm.audit.actor_agent`) and **no principal**.
   Any other operation carrying an agent actor is refused `OR025`, so a stray setting cannot launder a principal's act.
   `change_audit.actor_agent_id` records it (one kind of actor per row, by CHECK).
   **The exemption is scoped as every other no-actor exemption is (`m3`, `Q-E`):** `AGENT_ENROLLED` may write only
   `agent_registration` and `target`; `AGENT_INSTANCE_REGISTERED` only the declare path's governance rows (`solution`, `instance`,
   `scope`); and each only for an agent that is an **active** registration **of the row's own organization** (and, for
   `AGENT_INSTANCE_REGISTERED`, one whose token has been consumed). A revoked agent can register nothing (review R-2).
2. The correlation id and client address stay **mandatory**. On the agent path they come from the gateway-minted
   `AgentRequestOrigin` (a per-RPC correlation id and the gRPC peer's address) -- **never from an agent-supplied field**.
3. `created_by` of the agent target, its registration and its instances is the **principal who issued the token**.
4. *Found by the DDL draft:* token **issue** and **revoke** are principal acts that also write `agent_registration` and need names:
   `AGENT_ENROLMENT_ISSUED` and `AGENT_REVOKED`. `AGENT_LEASE_ISSUED` is added **only if** T2.2 Step 0 finds a lease-time `sm_control`
   write (the branch's registry writes at token consumption and certificate issue only).
5. `enrollment_token_digest` is an audit **secret column** (never copied into `old_values`/`new_values`); the issued certificate
   chain and trust bundle are not copied either.
6. The DP's mirror: `target_connection_event.actor_kind = 'agent'` for `enrolled`, and the `OR-125` (1) placement actor for a
   placement `OR-27` created.

**Rejected.** *Attributing every agent-caused write to the issuing principal* (`OR-128` (5) option (b)) -- `OR-27` says "audited
with the agent as actor". *A NULL actor with a NULL-context exemption* -- it is exactly what `OR-25` closed.

**Enforced by:** `AgentRegistrationSchemaIT` (each agent operation with an agent actor, correlation id and address accepted; neither
actor -> `OR025`; agent actor without client address -> `OR025`; any other operation with an agent actor -> `OR025`; both actors ->
`OR025`; an agent operation on any other table, for another organization's row, before consumption, or after revocation ->
`OR025`); mutation proof: let a non-agent operation accept an agent actor (red).

## <a id="be-d71"></a>`BE-D71` -- The agent target is created in both planes at enrolment; every target operation on it is contracted

**Status: PROPOSED.** Applies `BE-D32`'s order and recovery to a target. Built by plan 015 T3.5.

**The two-plane write.** On `Enroll(token, CSR)`: **(1) CP, one transaction**, under `AGENT_ENROLLED` (actor the agent, origin
from the RPC): consume the token (or replay it), sign the certificate, register the **CP target row** (`name` = the token's target
name, `kind docker`, transport `agent`, ownership scope = the token's environment) through `TargetRegistration`'s write path,
idempotent on organization and name. `target.create` is **not** repeated here: it was checked at token issue against the issuing
principal. **(2) DP, one transaction:** `target_connection` (transport `agent`, the envelope from the **token**, never from the
agent; `created_by` the issuing principal), `agent_identity` (carrying, **copied from the enrolment record**, the environment, the
catalog solution id, the solution name and the declared package version -- the DP cannot read the CP, `I7`, and `OR-27` needs all
of them), and a `target_connection_event` `enrolled`. `EnrollmentResult` (`dp-api`, T2.1a) therefore carries all of them with the
envelope and the issuing principal. **(3)** return the
certificate. **Recovery:** a crash between (1) and (2) leaves a CP target the DP lacks, listed degraded as an interrupted
`registerTarget` is; the agent's retry of the same token+CSR replays (1) to the same certificate and ids and completes (2). If the
agent never retries, the lazy tenant-scoped sweep on `listTargets` retires a degraded **agent** target once the token's `expires_at`
plus the `BE-D32` window has passed. **Never write the DP row first** (a mutation proof): that leaves an orphan the CP does not know.

**Every existing target operation on an agent target:**

| Operation | On an agent target |
|---|---|
| `listTargets`, `getTargetConnection` | Listed; `transport: agent`; the connection is the `AgentTargetConnection` variant |
| `registerTarget` | Unchanged; cannot create an agent target |
| `scanHostKeys` | Unchanged: not target-scoped (it scans an address) |
| `editTargetConnection`, `rotateTargetCredential`, `testTargetConnection` | `409 TARGET_TRANSPORT_UNSUPPORTED`; nothing read or written; no SSH constructed |
| `retireTarget` | `409 AGENT_STILL_ENROLLED` until its agent is revoked; after `revokeAgent`, as today (DP connection first, CP row second) |
| `addPlacement`, or a declare naming an agent target | `409 TARGET_IS_AGENT_MANAGED`: placements come only from `OR-27` in v1 |
| `retirePlacement`, `undeclareInstance` on an agent placement | As today, plus the discovery tombstone in the same DP transaction |

**Rejected.** *A target created by the operator in advance* -- the envelope would then come from a second place than the token.
*Flattening `TargetConnection` into one object with optional SSH fields* -- the contract would stop saying which fields exist for which
transport.

**Enforced by:** `AgentEnrollmentTargetIT` (enrol -> one CP target with the audit row, one DP row with the token's envelope, one
`enrolled` event; a crash between (1) and (2) -> degraded listing, then the replayed `Enroll` completes it with the same ids; a
never-retried one is swept; a placement outside the envelope refused); endpoint ITs per row of the table; mutation proofs (DP row
first; envelope from the agent; `editTargetConnection` allowed).

---

# `AG-D*` (to `docs/design/go-agent/decisions.md`)

## <a id="ag-d21"></a>`AG-D21` -- The telemetry role is collectors -> buffer -> sender, beside the control loop, fed only by its published view

**Status: PROPOSED. Reconstructed on 2026-10-08 from `go-agent/internal/telemetry/pipeline.go` and
`go-agent/internal/telemetry/view/view.go` (@ `87e338e1`); no source text existed** -- the identifier is cited by `go-agent/CLAUDE.md`,
`internal/telemetry/pipeline.go` and `view/view.go` and defined nowhere on the branch (measured by `git grep`). It records what the
code does; it is not evidence of what was intended.

The telemetry role (`--roles control,telemetry`) is a pipeline of three stages run beside the control loop: **one collector per
declared binding**, isolated so a panic or hung endpoint fails alone; the on-disk **buffer** (`AG-D24`); and the **sender**
(`AG-D23`), plus a one-minute age sweep. The pipeline never touches the control loop's reducers: the loop publishes **one
immutable `Snapshot`** (the session and its limits, the lease's binding ids, the DP-minted id table, a halted flag), replaced
whole, so a reader never sees a session from one publish and ids from another. A binding outside the lease collects nothing. The
first batch waits for the first `ReportAck` that mints the ids every sample carries.

**Consequence on the agent path.** The DP must return `replica_id` and `incarnation_id` in every `ReportResult` (`AGW-D25`), or
no batch is ever sent.

**Enforced by:** the pipeline/collector/view unit tests in the agent (`go test ./internal/telemetry/...`).

## <a id="ag-d22"></a>`AG-D22` -- Metric declarations: an interim local file of schema-shaped `http_json` metrics

**Status: PROPOSED. Reconstructed on 2026-10-08 from `go-agent/internal/telemetry/declare/declare.go`, `examples/telemetry.yaml`,
`README.md` and `RUNBOOK.md`; no source text existed.** Superseded for production by `AG-D28`.

`--telemetry-config` names a YAML file (`schema_version: 1`) of bindings; each has an `interval` (>= 1 s), an optional `timeout`
(default `min(interval, 5s)`, never above the interval) and `metrics[]` shaped like the Solution Package schema's metric object
(`name`, `metricType` `counter` or `gauge`, `unit`, `source`). The source kind is `http_json` only (`port`, `path` starting `/`,
a required RFC 6901 `pointer`, `onUnavailable` `degrade` or `error`); `histogram`, `when` and the later kinds (`prometheus`,
`solace_event`, `exec`, `jdbc_query`) are **refused**. Parsing is strict (unknown keys are errors). A pointer absent from the
document produces **no sample, never a zero**. The file is *interim*: the plane did not deliver declarations (open item `AG-14`).

**Enforced by:** `declare_test.go`, `collect_test.go`.

## <a id="ag-d23"></a>`AG-D23` -- The telemetry sender is a pure reducer over credits, in-flight batches and the stream's state

**Status: PROPOSED. Reconstructed on 2026-10-08 from `go-agent/internal/telemetry/send/reducer.go` and `runner.go`; no source text
existed.** Applies `AG-D14` (reactor with pure reducers) to the Telemetry stream.

`send.Reduce(state, event, env)` returns `(state, effects)`; a `Runner` executes the effects against the buffer and the stream.
**Credits are the only backpressure** (`AGW-D8`): one batch in flight per credit; a `SessionUp` resets credits to
`Limits.initial_credits` and requeues in-flight batches; a broken stream returns the credits its in-flight batches held and
reopens with jittered exponential backoff (1 s doubling to a 30 s cap). A `TelemetryAck` trims the buffer to its high watermark; a
`TelemetryReject` with a *terminal* code (`PAYLOAD_TOO_LARGE`, `UNKNOWN_REPLICA`, `INCARNATION_RETIRED`, `UNDECLARED_LABEL`,
`LABEL_INVALID`, `BINDING_NOT_IN_LEASE`, `TENANT_MISMATCH`) drops the batch as a gap and does not retry it; any other reject retries
after the gateway's `retry_after`. A stream with no known ids is not sent. The oldest produced batch is sent first.

**Enforced by:** `reducer_test.go`, `runner_test.go`.

## <a id="ag-d24"></a>`AG-D24` -- The telemetry buffer: a file per batch, bounded by bytes and age across all streams, drop-oldest, every drop a gap marker

**Status: PROPOSED. Reconstructed on 2026-10-08 from `go-agent/internal/telemetry/buffer/buffer.go`, `tmetrics/tmetrics.go` and
`README.md`; no source text existed.** Implements `AG-D9`.

One file per batch under `<data-dir>/telemetry/<stream>/`, written atomically, surviving an agent restart. Two **global** caps:
`--telemetry-buffer-bytes` (default 64 MiB) and `--telemetry-buffer-age` (default 24 h, must be positive). When either is
exceeded the **oldest batch across all streams** is dropped first, and **every drop is recorded as a gap marker** the stream's next
batch carries (`from_seq`, `to_seq`, reason `DROPPED_OLDEST`, `EXPIRED`, `OVERSIZE`, `UNREGISTERED` or `CORRUPT`) and counted
(`sm_agent_telemetry_dropped_batches_total{reason}`); both caps are exported as gauges, not merely enforced. A batch produced in the
future (the clock stepped back) is never expired early. A stream id is a safe path segment.

**Enforced by:** `buffer_test.go`; the telemetry end-to-end tests.

## <a id="ag-d25"></a>`AG-D25` -- A failed buffer write drops the batch and is counted; it is never fatal

**Status: PROPOSED. Reconstructed on 2026-10-08 from `buffer.Append`'s doc comment and `tmetrics.WriteErrors`; no source text
existed.** **Deliberately differs from `AG-D14`**, whose consequence is that a failed `Persist` of the *command journal* is fatal.

A telemetry batch that cannot be written (disk full, permissions) is **dropped, reported through the error return and counted**
(`sm_agent_telemetry_buffer_write_errors_total`); the agent keeps running. `WriteAtomic` leaves no partial file, so the failed
batch's sequence number is reused. The asymmetry is the point: losing a telemetry sample is a gap the plane can see; losing the
command journal's durability would let the agent act without being able to prove it did.

**Enforced by:** `buffer_test.go` (a failing `Save` drops, counts and reuses the sequence).

## <a id="ag-d26"></a>`AG-D26` -- Discovery by label, reporting facts; the container is found by name through the DP-supplied binding

**Status: PROPOSED.** Records `OR-126` (A6). **Supersedes `AG-D20`** (a binding's container found by the label
`com.solace.sm.binding-id`), which flagged itself superseded the day the protocol carried placement facts. Applies
`AGW-D30`.

1. A container opts in with the label **`com.solace.sm.instance=<name>`** and the label **`com.solace.sm.config-file=<absolute
   path in the container>`** (optionally `com.solace.sm.health-port`). These are deployment configuration (`I1`).
2. The agent scans for labelled containers (running or stopped) and sends a `DiscoveryReport`: container id, name, image
   reference, **only `com.solace.sm.*` labels**, bind mounts, published ports -- **facts, not conclusions**. It is a complete
   snapshot, sent after every `SessionAccept` and whenever the set or an id changes. At most 64 items per report.
3. The agent derives **no** placement field and names **no** environment, organization or target. The DP decides (`AGW-D30`).
4. The `DiscoveryResult` for a registered container carries its **`binding_id` (the placement id)**; the agent persists
   `container_name -> binding_id` and resolves a command's container **by name** (a re-created container keeps its placement by
   name). *Draft finding F-1: without `binding_id` in the result the agent cannot reach the placements it caused the DP to
   create; this is a hole in the plan's wire, closed in `proto-amendments-015.md`.*
5. `AG-D20`'s other facts stand: the config path is the **label's** (`AGW-D30`; `AG-D20`'s launch-arg/env discovery remains only for the
   development `binding-id` path, so that the file the agent writes is the file the DP recorded -- review R-20), mapped through a **bind
   mount** (required -- the agent writes on the host); `replica_ref` is the container name and `incarnation` the container id.

**Rejected.** *Keeping the `binding-id` label* -- the id is DP-minted after discovery. *The agent choosing the instance's
solution or environment* -- `I5`.

**Enforced by:** `discovery_test.go` (two labelled containers -> two items with their mounts and ports; an unlabelled one none; a
re-created container reports its new id under the same name; non-`com.solace.sm.` labels never leave the host).

## <a id="ag-d27"></a>`AG-D27` -- Health, container start time and the write precondition are observed facts

**Status: PROPOSED.** Applies `OR-101`, `OR-112`, `AG-D19`. Wire: `ActualStateReport.health`/`container_started_at`,
`CommandAck.container_started_at`, `CommandOffer.expected_current_sha256`, `OPERATION_REMATERIALIZE`.

1. **Health is a Docker fact, never inferred.** `HEALTHY`/`STARTING`/`UNHEALTHY` only from a container health check; **no health
   check -> `NOT_DECLARED`**; not running or inspect failed -> `UNREACHABLE`. "Running" never becomes "healthy" (`OR-101`). The
   agent sends no strength it cannot know (`UNSPECIFIED`); the DP reads the *declared* strength.
2. **Start time is the host's clock, untouched.** Docker `State.StartedAt` is reported as read: **no `Clock.Offset()` is applied**
   (the existing `restart_observed_at` *is* offset-corrected and is left alone). A never-started container reports no value. This
   is `OR-112`'s same-clock rule: the DP compares host values with host values.
3. **The precondition.** On `OPERATION_REMATERIALIZE` the agent hashes the file as it stands, **before** writing: equal to
   `expected_current_sha256` (or the file absent when it is present and empty) -> write as `APPLY`; otherwise `OUTCOME_REFUSED`
   / `ERROR_CODE_TARGET_MOVED` and **no write**. The check and the write are one journaled step (`AG-D4`). The offer's digest and
   the file's digest are never logged (`AG-D29`).
4. A REMATERIALIZE offer without the field is a protocol error (`ERROR_CODE_KEY_HASH_MISMATCH`), never an unconditional write.
5. `OPERATION_REMATERIALIZE` is an `apply-config` operation with its own `verb_ceilings` key (`rematerialize`); a lease without the key gives
   it a zero ceiling and every REMATERIALIZE fails, so the agent's operation tables carry the member and T2.3 tests a lease that has it
   and one that does not (review R-7). The ack of an APPLY, a REMATERIALIZE and a RESTART carries `container_started_at`.

**Enforced by:** `docker_test.go` (healthy/starting with a health check, `NOT_DECLARED` without); `commands_test.go` (a differing
precondition writes nothing and acks `REFUSED`); mutation proofs in T2.3 (skip the precondition; report `HEALTHY` with no check).

## <a id="ag-d28"></a>`AG-D28` -- Metric declarations arrive on the wire; the file flag is development-only

**Status: PROPOSED.** Supersedes `AG-D22` for production by citation. The DP sends `TelemetryDeclarations{binding_id, interval,
metrics[]}` per binding after `SessionAccept` and on change; each message **replaces that binding's set atomically**. The metrics
are the package's `http_json` metric objects for the placement (the shape `AG-D22` already parses); `interval` is `OR-73`'s 5 s.
A metric the agent cannot collect (histogram, an unknown kind) is dropped with a counter, never a zero sample. `--telemetry-config`
remains for development and tests. The agent sends no declaration of its own.

**Enforced by:** `declare_test.go` (a message replaces the set atomically); `AgentTelemetryBridgeIT` for the DP's side.

## <a id="ag-d29"></a>`AG-D29` -- The command payload on the host: memory only, or a spool -- **OPEN (`OR-128` (14))**

**Status: PROPOSED -- AND UNDECIDED. This text records both variants, with the costs of each, and chooses neither.** `OR-128` (14)
is open: it awaits the owner. **Nothing here may be implemented until (14) is ruled**; T2.3 builds the variant the ruling names, and
the unchosen variant's paragraph is deleted when the ruling is recorded. (Plan 015 section 10 item 14 names (b) as the default "if not
given"; `OR-128` (14) says *open*, so this text treats T2.3 as blocked on the ruling, not defaulted.)

**Today (measured, `go-agent/internal/commands/commands.go:258`).** The agent persists the rendered payload as a `store.Blob`
(mode 0600 under a 0700 directory) until the command is terminal; a crash leaves it behind. The rendered payload is the
configuration file's content and **can contain resolved secrets** (`I6`). Separately, the agent's command **journal** records the
offer's plain `ConfigHash` on disk (`commands.go:250`): that digest copy exists **in both variants** (it is the agent's journal,
`AG-D4`, not SM's storage, `OR-32`).

**Common to both variants (decided, because `OR-32`/`I6` require it):** `CommandOffer.LogValue` and `String` stop printing
`config_sha256` and never print the payload; no digest of rendered bytes appears in any agent or gateway log line. An
`Error{ERROR_CODE_RENDER_CHANGED}` answering a pull is a terminal refusal of that command (`proto-amendments-015.md` (c″)).

**Variant (b) -- memory only, re-pull after a restart.** The `Persist{Blob}` effect is dropped. After an agent restart a `RECEIVED`
journal record without a payload sends `CommandPull` for the **same command id**; the DP answers from its in-memory hand-off if it
still holds one, else **re-renders and compares** (`AGW-D26`) and sends the bytes only if the keyed hash still matches. *Costs, stated:*
an agent crash loses the in-memory copy and costs one pull round-trip; **if the rendering changed meanwhile (a secret rotated, a
referenced value edited) the DP refuses the pull `agent.payload.render.changed` and the apply fails (retryable), where variant (a)
would have finished from the local file**; and the `RECEIVED` record must become terminal on that refusal (the agent change above),
or it is stranded. Benefit: one on-host copy of any resolved secret (the destination file), not two. Cost: about 1 h of Go.

**Variant (a) -- keep the spool as an accepted `I6` exception, with a crash sweep.** The spool stays (0600/0700, deleted at
terminal); a startup sweep deletes every spool file without a live journal record. A crash mid-command resumes from the local
file without a pull and is immune to a re-render difference. *Costs, stated:* a second on-host copy of any resolved secret, for the
life of the command and after a crash until the sweep; it requires the owner to accept that exception **by name**. Cost: about 0.5 h.

**Input received, not a ruling.** On the 2026-10-08 list the owner wrote, on item 14: *"If agent crashes then we will loose spool
data if write it in memory in my view"*, and on the rest: *"Rest decisions looks fine"*. That is recorded as the owner's stated
concern about variant (b) and is **not** read as choosing (a): the same answer rules `OR-128` (1)-(17) and lists (14) as open. What
the concern touches is exactly the (b) cost above (a crash plus a changed rendering fails an apply that a spool would finish); what it
does not touch is the command itself, which the DP holds. Only the owner can weigh the two.

**Rejected (for both).** *Logging the digest "for debugging"* -- it lets log readers test guessed secrets.

**Enforced by:** the agent's `msg_test.go` (offer `LogValue`/`String` contain neither payload nor digest) and, once ruled,
`commands_test.go` for the chosen variant (under (b): an offer produces no `store.Blob` effect; a restart with a payload-less
`RECEIVED` record sends `CommandPull`, and a `RENDER_CHANGED` answer ends the record terminal).

---

# <a id="findings"></a>Findings the drafts made in the plan (for the Step 5 review and the plan's text)

These are not decisions; they are places where the approved plan, read against the code, is wrong or silent. Each is already
handled in the draft named, and each needs the plan's task text to follow.

| # | Where | Finding | Handled in |
|---|---|---|---|
| G-1 | T1.1 | V012's pinned-key triggers fail on an agent target's NULL pin (`23502`; reproduced) | `schema-015-draft.sql` VDA; `BE-D68` item 5 |
| G-2 | T1.3 | `sm_control.target.transport` is `CHECK = 'ssh-docker'`; T3.5 writes `agent` | VCA; `BE-D71` |
| G-3 | T1.3 | Token issue and revoke are principal writes that need audit operation names | VCA; `BE-D70` item 4 |
| G-4 / D-C1 | T0.3 Step 2 vs T1.3, T4.1 | The issue body has no solution or package version, but the registration and discovery take them from "the enrolment record" | console draft; VCA |
| G-5 | T1.3 | The token digest and issued certificates are copied into audit rows by the generic trigger | VCA AGW-EDIT 5, 7; `BE-D70` item 5 |
| G-6 | T1.1 | Optional guards offered, not added (command/intent placement; acquisition mode vs transport) | `schema-015-draft.sql` header |
| F-1 | T0.3 Step 1 | `DiscoveryResult` carries no placement id, so the agent cannot find a registered container for a command | `proto-amendments-015.md` (d′); `AG-D26` item 4 |
| F-2 | T3.3/T3.4 | The report ack must return `replica_id` and `incarnation_id` or the agent never sends telemetry | `AGW-D25` item 3 |
| F-3 | T0.3 Step 1 | `OPERATION_REMATERIALIZE`, three feature capabilities and two error codes are needed beside Step 1's list | `proto-amendments-015.md` |
| F-4 | T1.3 | `AGW-D15`'s ledger is superseded in effect, not named as such | `AGW-D24` residual |
| F-5 | T4.5 | A cross-tenant `DELETE` by constants rides on "the `OR-77` pattern" | `AGW-D31` |
| F-6 | T3.4 | `OR-128` (7) rules a skew window but not its size; no `ingest_counter` reason fits | `AGW-D27` item 5 |
| D-C2 | T3.3 | `rollbackApply` runs the unsupported `restore` inside the engine | console draft; `BE-D67` |
| F-7 / D-4 | T2.3, T3.3 | A Docker health check is not the package's declared probe and carries no strength, yet it is what the agent can observe | `AGW-D28` item 1; **needs the plan owner / owner** |
| R-1 | T1.1, T4.5 | Retention deleting a placement's last command would let a generation number be reused | `AGW-D31`; VDB |
| R-3, R-4 | T1.3, T3.5, T4.1 | The DP had no copy of solution and package version; a package-version equality FK cannot hold one member of the loaded array | VDA `agent_identity`; VCA trigger; `BE-D71` |
| R-6 | T3.1 | Command identity must include the operation, and a success followed by a failed attempt must re-send | `BE-D66` item 2; VDA index |
| R-7 | T2.3, T3.2 | `rematerialize` needs a lease ceiling; `RENDER_CHANGED` needs an agent mapping | `AGW-D24` item 4; `proto-amendments-015.md` (c″) |

## Review (T0.3 Step 5)

One adversarial review of the four drafts (Opus, read-only) returned 1 BLOCKER, 12 MAJOR and 10 MINOR findings; all were
dispositioned in one fix round. The dispositions, finding by finding, are appended to
[`proto-amendments-015.md`](proto-amendments-015.md) (plan 015 T0.3 Step 5).
