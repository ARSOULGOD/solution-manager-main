# Endpoint inventory — for the frontend team

**Updated 2026-10-12 (`OR-147`, the reconciliation of plan 015 into `UAT`): `console.yaml` holds 85 operations** — plan 015's 78
plus the seven `UAT` added (`listConnections`, `getConnectionTypes`, `createConnection`, `updateConnection`, `deactivateConnection`,
`deleteConnection` and `getSolutionPackage`); 84 are counted served and `exportRange` stays wired-not-counted (`check-postman`). The
agent verbs are catalog rows **48-51** (rows 44-47 are `UAT`'s `connection.*` verbs, `D10` amendment §1.10.1; the agent rows are
§1.11.1), so the catalog is 51; the rows below that say "rows 44-47" for the agent operations were written before the
reconciliation. The `ErrorCode` vocabulary is the union (`CONNECTION_TEST_FAILED` and the five agent codes).

**Updated 2026-10-11 (plan 015 T4.3): the five agent operations are served.** `issueAgentEnrolment`, `listAgents`, `getAgent`,
`revokeAgent` and `drainAgent` (`D10` rows 44-47) bring `console.yaml` to 78 operations (73 + 5; the older counts below are as
they were when written). The `Acquisition.mode` vocabulary gains `agent` (set by discovery, never chosen by an operator; a declare
naming it is `422`). The agent token is shown once, in the issue's answer. Every agent operation is environment-scoped; the issue
needs `agent.enrol` **and** `target.create` there.

**Updated 2026-10-09 (plan 013 T4.2, wave 6): the registration API is complete and the local stack and the Postman collection use it.**
No operation was added in this revision; it records the merged tree. **`console.yaml` holds 73 operations: 72 are counted served and
`exportRange` is wired and tested but not counted served** (§ "Wired, not counted served"); nothing is contracted-and-unserved. What
the console can now do over HTTP, by wave: **W1** target registration and management (`scanHostKeys`, `registerTarget`,
`getTargetConnection`, `editTargetConnection`, `rotateTargetCredential`, `testTargetConnection`, `retireTarget`; sshj host-key pinning,
the address policy `sm.dp.targets.allowed-networks`, `sm_data` V022); **W2** the scope tree and entitlements (`createSubOrganization`,
`createEnvironment`, the three renames, `listEntitlements`, `setEntitlement`; `sm_control` V015 and V018); **W3** local users and role
bindings (`listUsers`, `createUser`, `resetUserCredential`, `deactivateUser`, `listRoles`, `listAssignments`, `grantAssignment`,
`revokeAssignment`; usernames unique per organization, `sm_control` V019, login's optional `organization`); **W4-W5** placements
(`addPlacement`, `retirePlacement`), `undeclareInstance`, the apply schedules, and the pinned host key's type (`sm_data` V027). Migration
state: **`sm_control` V001-V021, `sm_data` V001-V027, `sm_metrics` V001-V003.** The *Admin* surface the frontend may build is therefore
the eight users, roles and assignments rows of the served table, the hierarchy and entitlement administration rows and the target rows
(§6 item 4 is narrowed accordingly); the four operations of §2 and the group operations of §3 stay unbuilt. **Still owed, stated
exactly:** the live gates L1-L5 (owed: pending an owner decision on running them); X1 is built (BE-D61 rider); its live apply on the E2E
host is owed with L1-L5; the combined Fable review; the agent gateway (plan 015, not built, so no `host-local` path and no `agent.*` verbs);
the plain-VM adapter (plan 014, not built); live notify. The Postman collection gained a `Setup` folder that walks a fresh install
(rotate, name the organization, hierarchy, entitlement, package, scan, register, test, Operator, bind); it has not been run against a
live stack in this revision (owed). The frontend owes `credential` (not `password`) in the login body (`OR-131` Q3).

**Updated 2026-10-08 (plan 012 T5, `OPS-Q2` U-C): `undeclareInstance` is served.** `DELETE /dp/instances/{instanceId}?workload=leave-running`
releases an instance from management without touching its host (§3 no longer lists it as a `501`); it adds one operation to the
counts below (a new row under "Served now"). `retirePlacement` now also records who retired the placement.

**Updated 2026-10-08 (plan 012 T7, `OPS-Q9` Sch-2): `scheduled` is built, as a window and not as a strategy.** `submitApply` and
`rollbackApply` take an optional `schedule: {notBefore, notAfter}`; the answer is `202` with a `scheduleId` and **no `applyId`**
(nothing is admitted). Two operations are new — `listApplySchedules` and `cancelApplySchedule` — which is why the counts below
read 50 where they read 48 (53 now that plan 013 T1.4's `scanHostKeys`, `registerTarget` and `getTargetConnection` are counted, and 57 once T1.5's `editTargetConnection`, `rotateTargetCredential`, `testTargetConnection` and `retireTarget` are, and 64 once plan 013 T2.3's seven hierarchy and entitlement operations are, and 65 once plan 012 T5's `undeclareInstance` is, and 73 once T3.2's eight user and role operations are): `strategy: "scheduled"` itself now answers **`422`** (naming the `schedule` field; on `restoreRuntimeConfig`
and `submitFleetApply` it says those cannot be scheduled), and **no operation answers `501` for a strategy any longer.** The
gateway's `ScheduleFirer` re-requires the submitter's verbs when the window opens. See §2 for the rows.

**Updated 2026-10-06 (plan 011 P11-7.2): the served table below is corrected to what the gateway's `@ConsoleOperation`
handlers actually serve, and the 14 monitoring operations of §1a are wired.** The "Served now" table had not been touched since
plan 010's first slice and listed 7 operations; **all 32 operations of §1 are served** (plan 010 T3-T11: 25 more), and the
`/api/console/v1` surface (§1a) now answers its 14 routes: 13 are served (`getCapabilities` through `resetDashboard`, row 61
as its contracted `501`), and `exportRange` (row 60) is **wired and tested but not counted served** until plan 011 P11-8.1
records the 105 M-row stream (M-Q18). Before this change every one of the 14 answered `404` (measured by the Postman lane).
The 73 operations in `console.yaml` and the 73 `@ConsoleOperation` handlers are the same set: run-checks step 15
(`contracts/openapi/postman/check-postman.py`, which scans the handlers against this inventory) and cp-api's
`ConsoleOperationsMirrorTest` pin it (`MetricsOperationClassificationTest` pins rows 53-66 only, and
`OperationCoverageValidatorTest` refuses a handler it cannot classify; neither sees a contract operation with no handler).

**Updated 2026-10-05: the T0.1 Step 7 owner sitting (2026-09-30, closed 2026-10-05) is recorded —
every item §7 used to list as a recommendation is now ratified — and `declareInstance` / `Instance`
carry the per-instance acquisition mode (owner rulings M-Q2 and M-Q2b, 2026-09-29), **mode only: no
host (`OR-82`, owner ruling 2026-10-05 — `direct-http` polls the target's registered address).** Previously
updated 2026-09-29 (plan 010 T0.1, the remaining delta after `9ec6797`). Seven of the 32
contracted operations were then implemented and served by the production launcher; the other 25 were
contracted, not implemented (⚠️ superseded: all 32 are served now, see the 2026-10-06 note above).** The
count moved from 30 to 32 in this revision: `submitFleetApply`
and `observeFleetApply` (`REQ-013`, ruling `OR-47`/`OR-57`, designed in
[`fleet-apply.md`](../../docs/design/backend/fleet-apply.md)) — neither served then, so the served
count was unchanged. **Every remaining contracted operation this plan touches was rewritten in
this revision** — the short version:

- `x-sm-scope: reach` (ruling `OR-51`) replaces `organization`/`solution` on `listSolutions`,
  `listInstances`, `listTargets`, `getHomeSummary`, `getHomeAttention` and both fleet operations —
  the verb held anywhere in the caller's organization, then each item filtered at its own scope.
- `declareInstance` moved to `POST /dp/environments/{environmentId}/instances` (ruling `OR-52`)
  and its body now names a full `placement` envelope, `declaredPackageVersion`, the catalog
  `solutionId` and the per-instance `acquisition` mode (`{mode: direct-http | via-ssh}` — **no host**,
  M-Q2/M-Q2b, `OR-82`) — not the ad hoc three-field body. `Instance` carries the same `acquisition`;
  the placement no longer has a `healthAcquisition` of its own.
- `getDesigner`'s `Designer` schema is now `Versioned` + `generation` + a `viewModel` transcribed
  from `contracts/view-models/src/types.ts` (ruling `OR-34`) — the old ad hoc `fields` array is
  gone. `stageConfig`'s body is now `{baseStateVersion, componentSelection, draftValues,
  rendered}`, checked server-side against the caller's own projection (`422` on mismatch).
- `Designer` gained a required `layout` (instance key to `{x, y}`) and `stageConfig`'s description now says where
  layout lives (ruling `OR-96`): a per-instance presentation record in the data plane, **outside**
  `config_generation`, its history and its hash. A layout-only save mints no generation and draws no stamp but is
  kept and returned; it is authorized by `config.edit` like any save. Additive on the response, no request change.
- `submitApply`, `rollbackApply` and `restoreRuntimeConfig` all require `placementId` now
  (`BE-D23` — v1 refuses instance-wide apply); `Strategy` widened to all five engine tokens, with
  `restart-all`/`rolling-no-validation`/`scheduled` answering `501` rather than being rejected by
  the schema. `rollbackApply`'s required `placementId` and `restoreRuntimeConfig`'s optional
  `strategy` (default `manual`) were **ratified at the Step 7 sitting** — see §7 below.
- `Health` dropped its retired REST vocabulary for the S0-11 notify vocabulary, per placement,
  with no top-level `state`/`stateVersion` (ruling `OR-39`/`OR-55`). `ConfigLadder` gained a
  top-level `reached` and per-placement `placements[]`. `observeApply` now returns an
  `ApplyObservationList`. `readLogs` gained `placementId`/`before`/`after`. `setLogLevel` now
  returns `202 ApplyAccepted` (not a bare `Versioned`) and its body names `placementId` and
  `mode`.
- `loadPackage`'s body no longer names `organizationId` (`I5`) and now answers `201` with a
  `LoadPackageResult`, not a bare `202 Versioned`.
- `ErrorCode` gained four members: `CONFLICT`, `VALIDATION_FAILED`, `ENTITLEMENT_REFUSED`,
  `NOT_IMPLEMENTED`.

**Plan 011 P11-1.2 (2026-10-06):** [§1a](#1a-the-14-monitoring-operations)
adds the 14 `/api/console/v1` monitoring operations (rows 53-66 of `console-integration.md` §5.4), contracted and
**not served** until plan 011 P11-7.2 (⚠️ served since: see the 2026-10-06 note at the top). `declareInstance`'s `placement` gained the optional `metricsPorts` map, and
`ErrorCode` gained eight members (`SERIES_CAP_EXCEEDED`, `AGGREGATION_NOT_APPLICABLE`,
`GROUP_BY_REQUIRES_AGGREGATION`, `STAT_REQUIRES_AGGREGATION`, `METRIC_UNTYPED`, `EXPORT_TOO_LARGE`,
`PACKAGE_VERSION_MISMATCH`, `METRICS_PORT_CONFLICT`). The counts of 32, 7 and 25 above are of §1's operations only.

⛔ **Frontend action required: regenerate generated types from `console.yaml`.** Every
hand-written or previously generated type this touches is now stale — that is most of the
document; regenerate rather than patch by hand.

It says what is *served*, what is *contracted*, what is *planned*, and what will answer `501`,
so the console is built against a surface that is agreed rather than guessed.

Read with [`console.yaml`](console.yaml) (the machine-readable contract, gated by
`run-checks.sh` check 11) and
[`../../docs/design/common/contracts/console-integration.md`](../../docs/design/common/contracts/console-integration.md)
§5 (the full 66-operation map with rationale). Every change in this revision follows the
2026-09-29 owner rulings (`OR-48`–`OR-59`) and the Step 7 sitting's rulings (2026-09-30 and
2026-10-05) — §7 below. Three contract-shape readings of the acquisition ruling are recorded there; the host-field
hazard they once listed was ruled away by `OR-82` (2026-10-05) — the declare body carries no host.

---

## The honest status, in one paragraph

**Every operation in `console.yaml` has a handler, and the authentication boundary in front of them is real.** The production
launcher (`backend/launcher`, `SolutionManagerApplication`) serves the four `/api/cp/auth/*` operations against
PostgreSQL-backed identity, sessions, CSRF, forced rotation and the durable login throttle (`sm_control` migrations, `sm_cp_runtime`
credentials only in the process), the hierarchy reads, the catalog, targets, instances, designer, apply, health, ladder, logs,
fleet and the home reads (plan 010), and the monitoring discovery, query, refusal and dashboard routes of
`/api/console/v1` (plan 011 P11-7.2). What is **not** yet true of monitoring: the export is not counted served until P11-8.1's
105 M-row measurement exists, an inactive replica is not yet composed into a query series (`replicaComposition.inactive` is
`0` and a series' `state` is `ok`), and `acrossReplicas` is validated and not applied (see §1a). `demo-launcher` is deleted (plan 011 P11-9.1); **no production
endpoint ever fell back to it**.

✅ **The frontend can integrate every §1 operation, and the monitoring routes of §1a, against real APIs now.** Generate types
from `console.yaml` so the switch from the demo is a base-URL change, not a rewrite.

### Served now

| Method | Path | Notes |
|---|---|---|
| POST | `/api/cp/auth/login` | Body `{organization?, username, credential}` (`OR-125` (4): a username is unique per organization; `organization` is its id or its name, compared without regard to case; present but empty, over 256 characters or padded with whitespace is `400`). `OR-131` Q3: `organization` is OPTIONAL: omitted, it is the install's only organization, and `400` when the install holds none or several; a NAMED organization that does not exist stays a wrong credential, at the same database cost. No CSRF. `Origin`/`Referer` must be trusted. Sets `__Host-SM_SESSION` (`HttpOnly`) and `__Host-SM_CSRF` (script-readable), both `Path=/`, `SameSite=Strict`, always `Secure`. `401 AUTHENTICATION_FAILED` is identical for an unknown organization, an unknown user, a wrong credential, an inactive user and a locked client address. A locked **username** never refuses a correct credential from an unlocked address (`OR-23`). `429 RATE_LIMITED` with `Retry-After` comes from a per-address pre-auth token bucket, before any credential check (`OR-24`); it is per process. |
| GET | `/api/cp/auth/identity` | Session required, no CSRF. Returns permissions with scopes and the `csrfToken`. |
| POST | `/api/cp/auth/credential` | Session + `X-SM-CSRF`. **Forced rotation only** in v1; throttled, and behind the same `429 RATE_LIMITED` gate as login. `204` revokes every other session. |
| POST | `/api/cp/auth/logout` | Session + `X-SM-CSRF`. `204` and both cookies expired. |
| GET | `/api/cp/hierarchy/organizations` | Session required, no CSRF. Authentication only — no catalog verb (`none.authenticationOnly`). Returns the caller's own organization, or `[]` if no held grant makes it visible. |
| GET | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs` | Session required, no CSRF. `404` (never `403`) if `organizationId` is not the caller's own organization or is not visible to them — identical to a nonexistent id. |
| GET | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments` | Session required, no CSRF. Same `404` collapse as the row above, evaluated against `subOrganizationId`. |
| ✅ | **Proving ITs** | Auth: `ProductionAuthenticationIT` (login, identity, credential, logout). Hierarchy: `HierarchyEndpointsIT`. Both boot the real `SolutionManagerApplication` over PostgreSQL. The demo-parity checklist `DemoParityChecklistIT` asserts the route, method, path and classification of the overlapping capabilities (plan 010 T12.1). |
| ✅ | all three hierarchy reads | `ScopeNode.name` is the real display name (ruling `OR-120`, `sm_control` `V015`); a node that existed before `V015`, including the install organization, carries its id's text until it is renamed. Names are set over the API: `createSubOrganization`, `createEnvironment`, `renameOrganization` (install scope), `renameSubOrganization` and `renameEnvironment` (plan 013 T2.3); a production install's organization shows its id text until it is first renamed. Names are unique among siblings, organizations across the install, compared by PostgreSQL `lower()` under the database's locale; not trimmed, not Unicode-normalised (`OR-123`). The lists are ordered by name (byte-wise), then id. As of `OR-38`, `ScopeNodeList` carries no `stateVersion`. |


Plan 010 and plan 011 (corrected 2026-10-06 against the `@ConsoleOperation` handlers). Same session, `mustRotate`, CSRF (unsafe methods) and
`Cache-Control: no-store` rules as above; the **Permission (scope)** column is the contract's `x-sm-permission` and `x-sm-scope`.

| Method | Path | operationId | Permission (scope) | Notes | Proved by (launcher-level production-boot IT) |
|---|---|---|---|---|---|
| GET | `/api/cp/home/summary` | `getHomeSummary` | `solution.view` (reach) | `OR-45`. Counts filtered per instance by `instance.view`, `byHealth` also by `health.view`; a failed data plane is a declared `availability`, never an empty list. | HomeEndpointsIT |
| GET | `/api/cp/home/attention` | `getHomeAttention` | `health.view` (reach) | `OR-45`. Items filtered by `instance.view` and the verb that guards each reason. | HomeEndpointsIT |
| GET | `/api/cp/solutions` | `listSolutions` | `solution.view` (reach) | Reach pre-check only (`solution.view` held at >= 1 grant); no per-item filter, a catalog row carries no scope path of its own (`console.yaml`, `SolutionController`). | CatalogEndpointsIT |
| POST | `/api/cp/solutions/load` | `loadPackage` | `package.load` (organization) | `201 LoadPackageResult`; the body names no organization (`I5`). `422` names the failed gate; `409` for a missing entitlement. | CatalogEndpointsIT |
| GET | `/api/cp/solutions/{solutionId}/package` | `getSolutionPackage` | `solution.view` (reach) | D-4 (2026-10-09): the raw document, reused by Designer's local `project()` and the Solutions package preview. Reach, not organization (`listSolutions`'s own reason — a catalog entry has no scope path of its own), so an environment-bound Operator's own Designer still works. `200 SolutionPackageDocument`; `packageVersion` query param optional, defaults to the entry's latest; `404` when the entry or version isn't this organization's. | CatalogEndpointsIT |
| GET | `/api/cp/targets` | `listTargets` | `target.view` (reach) | Reach. | TargetEndpointsIT |
| PATCH | `/api/cp/targets/{targetId}` | `editTarget` | `target.edit` (named: the target's own `ownershipScope`) | Updates linked-target name and runtime-specific settings. The parent Connection and runtime are immutable; Docker accepts `containerPrefix`, systemd accepts `serviceNamePattern`, and `configRoot` remains an absolute restricted path. No secrets are accepted or returned. `409` for a duplicate name or attempted reassociation/runtime switch. | TargetRegistrationSchemaMirrorTest; focused gateway tests |
| POST | `/api/dp/host-key-scans` | `scanHostKeys` | `target.create` (named: the body's `ownershipScope`) | Plan 013 T1.4, D-REG 5.1 T1. `200 HostKeyScan`: every key the host offers, with its key type; **untrusted, writes nothing, pins nothing**. Rate-limited per session (10 a minute, `429 RATE_LIMITED` with `Retry-After`). Recorded in the audit log by verb, scope and outcome, never the keys. `422 TARGET_ADDRESS_REFUSED` / `VALIDATION_FAILED`; `503` for a host that cannot be reached or offers no key. A scan is bounded to 10 s in all: each of up to seven attempts is a hard deadline (a watchdog closes its socket), not only a per-read timeout, so a peer that trickles bytes cannot hold a request thread (review W3C-3). | TargetRegistrationEndpointIT (15 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| POST | `/api/cp/targets` | `registerTarget` | `target.create` (named: the body's `ownershipScope`) | Plan 013 T1.4, D-REG 5.1 T2 and 8, `BE-D54`. Authorize, then every data-plane pre-check (grammar, key parse, address policy, a fresh scan that must offer the pin, a host-key uniqueness probe), then the control-plane row, then the data-plane record with the sealed key. **`201` new, `200` an identical replay** (the same record and the same key, compared by the PUBLIC key's fingerprint, so a re-export of one key is a replay), `409` (name taken differently, a different key or a registered key that cannot be opened to compare, a host key held by another target, a record changed under a concurrent request), `422` (`VALIDATION_FAILED`, `CREDENTIAL_INVALID`, `HOST_KEY_NOT_OFFERED`, `TARGET_ADDRESS_REFUSED`), `429` (the pre-check's scan draws on the same per-session budget as `scanHostKeys` and leaves the same audit row, review W3C-2), `503`. Every `409`/`422`/`503` of the pre-check leaves nothing in either plane (the scan's audit row apart). `403 INSECURE_TRANSPORT` before the body is read unless the channel is secure, a trusted proxy reports `https`, or the peer is loopback. The response never carries the key. | TargetRegistrationEndpointIT (15 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| GET | `/api/dp/targets/{targetId}/connection` | `getTargetConnection` | `target.edit` (named: the target's own `ownershipScope`) | Plan 013 T1.4, D-REG 5.1 T3, `OQ-13` (a). A management read under `target.edit`, **not** `target.view`: `listTargets` stays the envelope-only shape. `404` when the target is not visible, not live or retired. No field carries private-key material, its reference, or a hash of it; `credential.publicKeyFingerprint` is the fingerprint of the PUBLIC half. | TargetRegistrationEndpointIT (15 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| PATCH | `/api/dp/targets/{targetId}/connection` | `editTargetConnection` | `target.edit` (named: the target's own `ownershipScope`) | Plan 013 T1.5, D-REG 5.1 T4, `OQ-12` (a). Body: any of `host`, `port`, `sshUser`, `hostKeyFingerprint` (at least one); the envelope, key, organization and target id are `400`. A change of host, port or pin re-scans and requires the pin offered (`422 HOST_KEY_NOT_OFFERED`, `422 TARGET_ADDRESS_REFUSED`, `503`), is charged to the same per-session scan budget (`429`) and is audited as `target.edit`. `409` while work is in flight on a placement of the target, while another live target holds an offered key, or when the record changed. An `sshUser`-only edit is not scanned. `200 TargetConnection`. | TargetManagementEndpointIT (14 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| PUT | `/api/dp/targets/{targetId}/credential` | `rotateTargetCredential` | `target.edit` (named: the target's own `ownershipScope`) | Plan 013 T1.5, D-REG 5.1 T5 and 6.4, `OR-85`. Body `{privateKey}`, write-only; `403 INSECURE_TRANSPORT` before the body is read unless the channel is secure (OQ-14 (b)). The same key again (by the PUBLIC key's fingerprint) is `200` and writes nothing; a different key is a new secret and a `credential_rotated` event. **Not** refused while work is in flight. `422 CREDENTIAL_INVALID`. `200 {credential: CredentialSummary}`. SM stopping use of the old key does not revoke it: remove the old public key from each host's `authorized_keys` (runbook, plan 013 T4.2). | TargetManagementEndpointIT (14 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| POST | `/api/dp/targets/{targetId}/connection/test` | `testTargetConnection` | `target.use` (named: the target's own `ownershipScope`) | Plan 013 T1.5, D-REG 5.1 T6, `OQ-9` (a). No body. Connects with the pin, authenticates, and runs the one fixed `docker version --format '{{.Server.Version}}'`. **Always `200 TargetConnectionTest`** for a live target: an unreachable host, another host key, a refused key, a stopped daemon and a user outside the `docker` group are results (`hostKeyPinned`, `authenticated`, `docker.available`), never a `5xx`. Audited as `target.use` with the furthest step reached. `404` for a target with no live connection record. | TargetManagementEndpointIT (14 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08); live gate L4 (**OWED**) |
| POST | `/api/cp/targets/{targetId}/retire` | `retireTarget` | `target.edit` (named: the target's own `ownershipScope`) | Plan 013 T1.5, D-REG 5.1 T7, `OQ-11` (b). No body. Data plane first, then the control-plane row; `409` while a live placement names the target; retiring a retired target is `200` with the original `retiredAt`; a control-plane row with no connection record retires on its own; the retired name is free for a new registration. `200 Target` with `retiredAt`. | TargetManagementEndpointIT (14 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| POST | `/api/cp/environments/{environmentId}/agent-enrolments` | `issueAgentEnrolment` | `agent.enrol` + `target.create` (environment) | Plan 015 T4.3, `OR-126` A8, `OR-128` (4). Body `{targetName, allowedConfigRoot, allowedContainerPrefix, solutionId, packageVersion}`; `201 AgentEnrolment` carries the **token once** (`readOnly`; no read returns it again, `I6`). Lifetime is configuration (`OR-136`: 24 h default, 30 d ceiling). A structurally malformed body is `400`; an envelope value outside its grammar (`allowedConfigRoot`, `allowedContainerPrefix`) is `422`, as `registerTarget`'s is; a taken target name `409 CONFLICT`; a solution or version not loaded `422`; `403` names the verb that is missing; `403 INSECURE_TRANSPORT` on an unsecured channel (the response is a credential). | AgentEndpointsIT |
| GET | `/api/cp/agents` | `listAgents` | `agent.view` (reach) | Plan 015 T4.3. Reach, filtered by `agent.view` at each agent's environment. No `stateVersion` (`OR-38`, as `Target`); `session` (connection facts from the data plane) is omitted until the agent has connected. Never a token, digest, certificate or key. | AgentEndpointsIT |
| GET | `/api/cp/agents/{agentId}` | `getAgent` | `agent.view` (named: the agent's environment) | Plan 015 T4.3. `404` for another organization's agent or none (`I5`). | AgentEndpointsIT |
| POST | `/api/cp/agents/{agentId}/revoke` | `revokeAgent` | `agent.revoke` (named: the agent's environment) | Plan 015 T4.3, `AGW-D19`. Sticky and idempotent; raises the revocation epoch; the data plane closes the session within its reconciliation interval. Allowed on a token never used (releases the target name). `200 Agent`. | AgentEndpointsIT |
| POST | `/api/dp/agents/{agentId}/drain` | `drainAgent` | `agent.drain` (named: the agent's environment) | Plan 015 T4.3, `AGW-D18`. `202 AgentDrainAccepted`: the live session is told to finish and reconnect after the gateway's `reconnect_after`; `409 AGENT_NOT_CONNECTED` when nothing was sent: no live session on this gateway, or it is already draining (nothing queued; single replica, `AGW-D32`). The `Drain` and its `agent_event` carry reason `operator-drain`. | AgentEndpointsIT, AgentGatewayServiceTest |
| GET | `/api/dp/solutions/{solutionId}/instances` | `listInstances` | `instance.view` (reach) | Reach, by `instance.view` at each instance. Settles a stale `pending` declare first. | InstanceEndpointsIT |
| POST | `/api/dp/environments/{environmentId}/instances` | `declareInstance` | `instance.create` (environment) | Body carries `acquisition: {mode}` and the optional `placement.metricsPorts` (plan 011 M-Q14). `409 METRICS_PORT_CONFLICT` and `PACKAGE_VERSION_MISMATCH` are plan 011's. | InstanceEndpointsIT, DeclareCrashWindowIT, DeclareRegisterTimeQuotaIT |
| GET | `/api/dp/instances/{instanceId}` | `getInstance` | `instance.view` (instance) | Only an ACTIVE instance is readable; a pending, abandoned or out-of-scope one is the same `404`. | InstanceEndpointsIT |
| POST | `/api/dp/instances/{instanceId}/placements` | `addPlacement` | `instance.bindTarget` (instance) + `target.use` (the body's target) | Plan 008 S3, `BE-D47`. `201 Instance`. Body is `declareInstance`'s `placement` plus `targetId`; the probe and metrics come from the instance's declared package, never the body. `409` for a collision, `METRICS_PORT_CONFLICT`, `PACKAGE_VERSION_MISMATCH`, a package that declares push metrics, or an instance-wide request in flight (a stop reads the live set once); `422` names the field; `404` for a non-active instance. No quota check (`OR-121`, OQ-8.8). The new placement starts unconverged: the ladder drops to `rendered` until a fleet rolls the current generation to it. The add stamps the instance, so an open designer session's next `stageConfig` is `409`. The acting principal is recorded as `placement.created_by` (`OR-125` (1), `sm_data` V026). | AddPlacementEndpointIT (15 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| DELETE | `/api/dp/instances/{instanceId}/placements/{placementId}` | `retirePlacement` | `instance.bindTarget` (instance) | Plan 008 S3, `BE-D53`. `?workload=leave-running` is required (`400` otherwise); the host is never touched and the response says `workload: left-running`. `409` for the last live placement, while work in flight names it, and (Fable F-3, `OR-131` Q2 by analogy) while a `PENDING` scheduled apply names it (cancel the schedule first). `200 PlacementRetirement`. Stamps the instance (an open designer session's next `stageConfig` is `409`). A submit that validated the placement just before it was retired fails visibly at preflight (W2 review W2A-3). The acting principal is recorded as `placement.retired_by` (`OR-125` (1), `sm_data` V026) **and**, since plan 012 T5, in a placement-scoped `instance_retirement` row (the session principal, the request's correlation id, the placement's last persisted health) written in the same transaction as the retirement. | AddPlacementEndpointIT (15 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| DELETE | `/api/dp/instances/{instanceId}` | `undeclareInstance` | `instance.undeclare` (instance) | Plan 012 T5, `BE-D49`, `OPS-Q2` U-C. `?workload=leave-running` is required (`400` otherwise); **the host is never touched** (the container keeps running, unmanaged; its config file and `.sm-preimage-*` / `.sm-fp-*` stay, `docs/runbooks/operations.md` gives the cleanup) and the response says `workload: left-running`. No body. `409` while a request is in flight (an `APPLY_INDETERMINATE` one included), a `RUNNING` fleet reserves a placement, or a `PENDING` apply schedule names the instance (`OR-131`: cancel the instance's pending schedules first; `submitApply` and `rollbackApply` (with a `schedule`) take the same admission lock, so a concurrent create cannot slip past). Three steps over two planes; a crash is finished by retrying the call (an authorized retry resumes and answers `200`) or by a lazy sweep inside `declareInstance` / `listInstances` / `getInstance` (it completes a finished control-plane step, and rolls an unfinished one back after `sm.dp.undeclare.rollback-after`, ten minutes by default, so the instance may reappear). A record older than half that window answers `503` and the control plane is not written. A repeated call is `404`. `200 InstanceRetirement` (`instanceId`, `retiredAt`, `workload`, each placement with its last persisted health, `stateVersion`). Frees the name, container name, directory, ports and the quota slot. | UndeclareEndpointIT (15 tests), UndeclareControllerTest, UndeclareSweepTest |
| GET | `/api/dp/instances/{instanceId}/designer` | `getDesigner` | `config.view` (instance) | Versioned, with the transcribed `viewModel` and `layout`. | DesignerEndpointsIT |
| POST | `/api/dp/instances/{instanceId}/config` | `stageConfig` | `config.edit` (instance) | `409` for a stale `baseStateVersion`; `422` checked against the server's own projection. | DesignerEndpointsIT |
| GET | `/api/dp/instances/{instanceId}/config/history` | `getConfigHistory` | `config.view` (instance) |  | DesignerEndpointsIT |
| POST | `/api/dp/instances/{instanceId}/config/secrets/{fieldKey}` | `setConfigSecret` | `config.edit` (instance) | Never returns a resolved value (`I6`). | ConfigSecretEndpointIT |
| POST | `/api/dp/instances/{instanceId}/config/accept-runtime` | `acceptRuntimeConfig` | `config.edit` (instance) | `AcceptedRuntimeConfig`; `503` when the host cannot be read. | AcceptRuntimeEndpointIT |
| POST | `/api/dp/instances/{instanceId}/config/restore-runtime` | `restoreRuntimeConfig` | `config.apply` (instance) | `202 ApplyAccepted`. The two ungated strategies as `submitApply`; `scheduled` answers `422` (restore has no schedule). | RestoreRuntimeEndpointIT |
| POST | `/api/dp/instances/{instanceId}/apply` | `submitApply` | `config.apply` (instance) | `202`: durable, not done. `rolling-no-validation` and `restart-all` also need `instance.restart.overrideSafety` (`OR-121`); `restart-all` needs `outageConfirmedBy` (`400` without it; the server records the session principal). With a `schedule` the answer is `202 ScheduleAccepted` (a `scheduleId`, **no `applyId`**; plan 012 T7). `strategy: scheduled` answers `422`. | ApplyEndpointsIT, ApplyScheduleEndpointsIT (9 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| GET | `/api/dp/instances/{instanceId}/apply/observe` | `observeApply` | `config.view` (instance) | An `ApplyObservationList`. | ApplyEndpointsIT, ObserveBackfillIT |
| POST | `/api/dp/instances/{instanceId}/apply/rollback` | `rollbackApply` | `config.rollback` (instance) | `202`; `placementId` required. The two ungated strategies as `submitApply`; a refused `restart-all` rollback mints no generation. With a `schedule` the copy-forward happens **when the window opens**, not at submit (`202 ScheduleAccepted`). `scheduled` answers `422`. | RollbackEndpointIT, ApplyScheduleEndpointsIT (9 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| POST | `/api/dp/instances/{instanceId}/stop` | `stopInstance` | `instance.stop` (instance) | `202`; Administrator and Operator (`OR-121`). | StopEndpointIT |
| GET | `/api/dp/instances/{instanceId}/apply-schedules` | `listApplySchedules` | `config.view` (instance) | Plan 012 T7. The instance's schedules, newest first, every state (`PENDING`, `ADMITTED`, `CANCELLED`, `MISSED`, `REFUSED`); `?limit=` 1..200 (default 50). Carries **no principal** and no outage confirmation. A schedule is created by `submitApply`/`rollbackApply` with a `schedule`, not here. | ApplyScheduleEndpointsIT (9 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| DELETE | `/api/dp/instances/{instanceId}/apply-schedules/{scheduleId}` | `cancelApplySchedule` | `config.apply` (instance) | Plan 012 T7. Any `config.apply` holder at the instance, not only the submitter; the canceller is the session's principal. `200 ApplySchedule` (`CANCELLED`). `409` once the schedule is no longer `PENDING` (admitted, missed, refused or cancelled) — an admitted one is an ordinary apply, stopped with `stopInstance`. There is **no cancel-on-stage**. | ApplyScheduleEndpointsIT (9 tests, green on `postgres:14` in the combined W3+W4 full build, 2026-10-08) |
| POST | `/api/dp/fleet-applies` | `submitFleetApply` | `config.apply` (reach) | `OR-47`. A refused fleet is `403` with `refusedMembers[]`; a member refused on `instance.restart.overrideSafety` is `FORBIDDEN` there. `scheduled` answers `422` (a fleet cannot be scheduled), before any member is authorized. | FleetEndpointsIT |
| GET | `/api/dp/fleet-applies/{fleetId}` | `observeFleetApply` | `config.view` (reach) | `OR-47`. Members filtered by `config.view`. | FleetEndpointsIT |
| GET | `/api/dp/instances/{instanceId}/health` | `getHealth` | `health.view` (instance) | Per placement, the S0-11 vocabulary; persisted rows only, so a host outage cannot slow it. | HealthEndpointsIT |
| GET | `/api/dp/instances/{instanceId}/config-ladder` | `getConfigLadder` | `config.view` (instance) |  | DesignerEndpointsIT |
| GET | `/api/dp/instances/{instanceId}/logs/{component}` | `readLogs` | `logs.read` (instance) | Bounded pages; **NOT redacted** - a log line is the MI's own output and `logs.read` is the only control (`console.yaml`, check 16). | LogsEndpointsIT |
| GET | `/api/dp/instances/{instanceId}/logs/{component}/export` | `exportLogs` | `logs.read` (instance) | Plan 016: text/plain download of an AGENT placement's collected log, bounded by bytes written and time; refusals known before the first byte are `429 log-export-concurrency` / `503` / `404` / `501`; gaps and truncation after it are fixed `# sm-export:` marker lines, and no end marker means complete. **NOT redacted.** | LogExportControllerTest |
| GET | `/api/dp/instances/{instanceId}/log-levels/{component}` | `getLogLevel` | `logs.read` (instance) |  | LogLevelEndpointsIT |
| POST | `/api/dp/instances/{instanceId}/log-levels/{component}` | `setLogLevel` | `logs.setLevel` (instance) | `202 ApplyAccepted`; `mode: live` answers `501` (`OR-103`). | LogLevelEndpointsIT, LogLevelConfigEndpointIT |
| GET | `/api/console/v1/capabilities` | `getCapabilities` | `none.authenticationOnly` (install) | Authentication only, install scope. No byte figure anywhere (`OQ-16`); `collector.reasons` are the caller's organization's. | MetricsEndpointsIT |
| GET | `/api/console/v1/entity-types` | `listEntityTypes` | `metrics.query` (reach) | Reach by `metrics.query`; `[]` for nothing reachable, never `403`. | MetricsEndpointsIT |
| GET | `/api/console/v1/entity-types/{entityType}/instances` | `listMetricInstances` | `metrics.query` (reach) | Two verbs: the intersection of what the caller reaches with `metrics.query` and with `instance.view`. | MetricsEndpointsIT |
| GET | `/api/console/v1/metrics/catalog` | `getMetricsCatalog` | `metrics.query` (reach) | A bare array. Declared (control plane) and observed (data plane) read concurrently; a failed data plane sets each entry's `observedAvailability` to `unavailable`, a failed control plane is `503`. | MetricsEndpointsIT |
| GET | `/api/console/v1/labels/{labelKey}/values` | `getLabelValues` | `metrics.query` (reach) | `[]`: the v1 label allowlist is empty (D-10). | MetricsEndpointsIT |
| POST | `/api/console/v1/query/range` | `queryRange` | `metrics.query` (reach) | Reach, not refusal: ids out of reach are omitted silently, like ids that do not exist. `resolution: raw` also needs `metrics.forceRaw`. `422` codes, `429 query-rate` (10 per second per session). | MetricsEndpointsIT |
| POST | `/api/console/v1/query/instant` | `queryInstant` | `metrics.query` (reach) | As `queryRange`; row 59 carries one verb whatever the resolution. | MetricsEndpointsIT |
| POST | `/api/console/v1/metrics/catalog/{name}/type` | `declareMetricType` | `metric.declare` (organization) | A **contracted refusal**: `501 NOT_IMPLEMENTED`, `reason: metric-declare-deferred`, for every caller who holds `metric.declare` at the organization; never `403`. | MetricsEndpointsIT (`metricDeclareIs501ForEveryCallerWhoHoldsTheVerb`) |
| GET | `/api/console/v1/dashboards/{dashboardId}` | `getDashboard` | `dashboard.view` (instance) | `db_` plus the instance id; a malformed, unknown, foreign or non-active id is the same `404`. | DashboardEndpointsIT |
| POST | `/api/console/v1/dashboards/{dashboardId}/widgets` | `saveWidget` | `dashboard.edit` (instance) | Requires `X-SM-Dashboard-Revision` (`400` missing or malformed, `409` stale). | DashboardEndpointsIT |
| PUT | `/api/console/v1/dashboards/{dashboardId}/layout` | `setDashboardLayout` | `dashboard.edit` (instance) | `204`. Every widget placed exactly once, else `400`. | DashboardEndpointsIT |
| PATCH | `/api/console/v1/dashboards/{dashboardId}/widgets/{widgetId}` | `setWidgetHidden` | `dashboard.edit` (instance) | `204`. Only a product-default widget; a shared or unknown one is `404`. | DashboardEndpointsIT |
| POST | `/api/console/v1/dashboards/{dashboardId}/reset` | `resetDashboard` | `dashboard.reset` (instance) | Keeps every shared widget; returns the dashboard. | DashboardEndpointsIT |
| POST | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs` | `createSubOrganization` | `hierarchy.edit` (organization, the parent) | Plan 013 T2.3, D-REG 5.3 H1, `OR-123`. Body `{name}` (1-100 characters, stripped, no control character; else `400`). `201` new; **`200` the identical replay** (the exact name already under this parent; nothing written); **`409 CONFLICT` a sibling whose name differs only in case** (the database compares, never the server). A path organization that is not the session's is `404`. | HierarchyAdministrationEndpointIT (written; **not yet run**, Docker down), HierarchyAdministrationControllerTest |
| POST | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments` | `createEnvironment` | `hierarchy.edit` (sub-organization, the parent) | D-REG 5.3 H2. As above, per sub-organization: the same name under two sub-organizations is not a clash. A new environment can be a target's `ownershipScope` at once. | HierarchyAdministrationEndpointIT (written; not yet run), HierarchyAdministrationControllerTest |
| PATCH | `/api/cp/hierarchy/organizations/{organizationId}` | `renameOrganization` | `hierarchy.edit` (organization) | D-REG 5.3 H3. Renames the session's own organization (any other id is `404`). `200`; a rename to its own name writes nothing. ⚠️ **The `409` is install-wide, not a sibling clash, and discloses that another organization has the name: open owner item W2D-2, recorded and not decided.** | HierarchyAdministrationEndpointIT (written; not yet run), HierarchyAdministrationControllerTest |
| PATCH | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}` | `renameSubOrganization` | `hierarchy.edit` (sub-organization) | D-REG 5.3 H4. `200`; `409 CONFLICT` a sibling already has the name, compared without regard to case. | HierarchyAdministrationEndpointIT (written; not yet run), HierarchyAdministrationControllerTest |
| PATCH | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments/{environmentId}` | `renameEnvironment` | `hierarchy.edit` (environment) | D-REG 5.3 H5. As H4. | HierarchyAdministrationEndpointIT (written; not yet run), HierarchyAdministrationControllerTest |
| GET | `/api/cp/entitlements` | `listEntitlements` | `solution.view` (organization) | Plan 013 T2.3, D-REG 5.2 E1. The session's organization only; `declaredInstances` excludes retired instances. | HierarchyAdministrationEndpointIT (written; not yet run), EntitlementControllerTest |
| PUT | `/api/cp/entitlements/{solution}` | `setEntitlement` | `entitlement.set` (install) | D-REG 5.2 E2, `OQ-4` (a), `OR-121`. An organization-bound Administrator is `403`; an install-bound one sets its own organization's limit. Body `{maxInstances}` (an integer, 1-1 000 000). **`409 ENTITLEMENT_REFUSED` below the declared count.** Idempotent. A solution name outside `^[a-z][a-z0-9-]{0,63}$` is `400`. | HierarchyAdministrationEndpointIT (written; not yet run), EntitlementControllerTest |
| GET | `/api/cp/connections` | `listConnections` | `connection.view` (reach) | Plan 013 T1/T4, `BE-D66`/`OR-130`. The envelope-only Connections list; no field carries secret material, only `secretStatus`/`{set:true}` redaction (`I6`). | ConnectionManagementEndpointIT (11 tests) |
| GET | `/api/cp/connection-types` | `getConnectionTypes` | *authenticated only* | Plan 013 T1/T4, `BE-D66`/`OR-130`. Static catalog (`solace-broker`, `remote-server`), no scope check beyond a valid session. | ConnectionManagementEndpointIT (11 tests) |
| POST | `/api/cp/connections` | `createConnection` | `connection.create` (named: the body's `ownershipScope`) | Plan 013 T1/T4/T5, `BE-D66`/`OR-130`. `testBeforeSave: true` runs the real production tester (`SolaceBrokerConnectionTester`/`RemoteServerConnectionTester`) before any write; a genuine `503` or credential `422` writes nothing. `201` new. The response never carries the secret (`I6`). | ConnectionManagementEndpointIT (11 tests) |
| PATCH | `/api/cp/connections/{connectionId}` | `updateConnection` | `connection.edit` (named: the connection's own `ownershipScope`) | Plan 013 T1/T4/T5, `BE-D66`/`OR-130`. An untouched secret reference is left unchanged; a newly-typed secret rotates it. A forced-unreachable test (`testBeforeSave: true`) leaves the row unchanged on failure. `200 Connection`. | ConnectionManagementEndpointIT (11 tests) |
| POST | `/api/cp/connections/{connectionId}/deactivate` | `deactivateConnection` | `connection.edit` (named: the connection's own `ownershipScope`) | Plan 013 T1/T4/T5, `BE-D66`/`OR-130`. One-directional — no reactivate operation. `200 Connection`. | ConnectionManagementEndpointIT (11 tests) |
| DELETE | `/api/cp/connections/{connectionId}` | `deleteConnection` | `connection.delete` (named: the connection's own `ownershipScope`) | Plan 013 T1/T4/T5, `BE-D66`/`OR-130`. **`409`** while `usedByCount > 0` ("Deactivate it instead" reason) — a deleted connection must have no dependents. `204` once unused. | ConnectionManagementEndpointIT (11 tests) |
| POST | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs` | `createSubOrganization` | `hierarchy.edit` (organization, the parent) | Plan 013 T2.3, D-REG 5.3 H1, `OR-123`. Body `{name}` (1-100 characters, stripped, no control character; else `400`). `201` new; **`200` the identical replay** (the exact name already under this parent; nothing written); **`409 CONFLICT` a sibling whose name differs only in case** (the database compares, never the server). A path organization that is not the session's is `404`. | HierarchyAdministrationEndpointIT (10/10 on postgres:14), HierarchyAdministrationControllerTest |
| POST | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments` | `createEnvironment` | `hierarchy.edit` (sub-organization, the parent) | D-REG 5.3 H2. As above, per sub-organization: the same name under two sub-organizations is not a clash. A new environment can be a target's `ownershipScope` at once. | HierarchyAdministrationEndpointIT (10/10 on postgres:14), HierarchyAdministrationControllerTest |
| PATCH | `/api/cp/hierarchy/organizations/{organizationId}` | `renameOrganization` | `hierarchy.edit` (**install**) | D-REG 5.3 H3. **Install scope only (`OR-125` (3), closes W2D-2):** an organization-bound Administrator is refused `403` before the path id is read (the same answer for its own, another and a nonexistent organization); an install-bound caller naming an organization that is not its own tenant's gets `404`. `200`; a rename to its own name writes nothing. A path id that is not a UUID is `404` before authorization (W5C-3). The `409` is install-wide, not a sibling clash, and is reachable only by install principals, so it is no cross-tenant oracle. **Consequence (W5C-2, ruled `OR-131`):** only the install-bound principal's own organization can be renamed over HTTP (no API creates an install-bound binding; the only such principal is the bootstrap administrator); other organizations are renamed by the operator command `OrganizationRenameMain` (`OR-131`, runbook `operations.md`), audited `HIERARCHY_NODE_RENAMED` from `operator-cli` with no actor (the `EntitlementMain` pattern, `sm_control` V020). The sub-organization and environment rename `409`s (a sibling clash that may be with a sibling the caller cannot list, inside one organization) are accepted. | HierarchyAdministrationEndpointIT (10/10 on postgres:14), HierarchyAdministrationServiceIT, HierarchyAdministrationControllerTest |
| PATCH | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}` | `renameSubOrganization` | `hierarchy.edit` (sub-organization) | D-REG 5.3 H4. `200`; `409 CONFLICT` a sibling already has the name, compared without regard to case. | HierarchyAdministrationEndpointIT (10/10 on postgres:14), HierarchyAdministrationControllerTest |
| PATCH | `/api/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments/{environmentId}` | `renameEnvironment` | `hierarchy.edit` (environment) | D-REG 5.3 H5. As H4. | HierarchyAdministrationEndpointIT (10/10 on postgres:14), HierarchyAdministrationControllerTest |
| GET | `/api/cp/entitlements` | `listEntitlements` | `solution.view` (organization) | Plan 013 T2.3, D-REG 5.2 E1. The session's organization only; `declaredInstances` excludes retired instances. | HierarchyAdministrationEndpointIT (10/10 on postgres:14), EntitlementControllerTest |
| PUT | `/api/cp/entitlements/{solution}` | `setEntitlement` | `entitlement.set` (install) | D-REG 5.2 E2, `OQ-4` (a), `OR-121`. An organization-bound Administrator is `403`; an install-bound one sets its own organization's limit. Body `{maxInstances}` (an integer, 1-1 000 000). **`409 ENTITLEMENT_REFUSED` below the declared count.** Idempotent. A solution name outside `^[a-z][a-z0-9-]{0,63}$` is `400`. | HierarchyAdministrationEndpointIT (10/10 on postgres:14), EntitlementControllerTest |
| GET | `/api/cp/admin/users` | `listUsers` | `user.view` (organization) | Plan 013 T3.2, D-REG 5.4 U1. The session's organization's users, deactivated ones included, ordered by username. Never a credential or a hash. | UserAdministrationEndpointIT, UserAdministrationControllerTest |
| POST | `/api/cp/admin/users` | `createUser` | `user.create` (organization) | D-REG 5.4 U2. Body `{username, initialCredential}` (1-64 characters; 12-1024). `201`; the user is `local` and `mustRotate`. **`409` only for a username of THIS organization** (compared without regard to case): a username is unique per organization (`OR-125` (4), `V019`), so another organization's name is no conflict and is never revealed. Credential-bearing: `403 INSECURE_TRANSPORT` unless the channel is secure. | UserAdministrationEndpointIT, UserAdministrationControllerTest |
| PUT | `/api/cp/admin/users/{userId}/credential` | `resetUserCredential` | `user.edit` (organization) | D-REG 5.4 U3, second half. Body `{resetCredential}`; sets a provisional credential, ends the user's sessions. `409` for the caller itself, an Administrator and a deactivated user. Credential-bearing. | UserAdministrationEndpointIT, UserAdministrationControllerTest |
| GET | `/api/cp/admin/roles` | `listRoles` | `role.view` (organization) | D-REG 5.4 U4. The three built-in roles and their verbs. | UserAdministrationEndpointIT, RoleAssignmentControllerTest |
| GET | `/api/cp/admin/assignments` | `listAssignments` | `role.view` (organization) | D-REG 5.4 U5. `?principalId=`; only the assignments the caller's `role.view` reaches. | UserAdministrationEndpointIT, RoleAssignmentControllerTest |
| POST | `/api/cp/admin/assignments` | `grantAssignment` | `role.assign` (named: the granted scope) | D-REG 5.4 U6. Body `{principalId, roleId, scope}` (`scope` is a `ScopeRef`, the deepest id). `201` new, `200` the identical replay; `422` the Administrator role, `409` a deactivated user, `404` a node outside the session's organization. | UserAdministrationEndpointIT, RoleAssignmentControllerTest |
| DELETE | `/api/cp/admin/assignments/{assignmentId}` | `revokeAssignment` | `role.assign` (named: the binding's own scope) | D-REG 5.4 U7. `204`; `409` an Administrator binding is never revoked over the API. | UserAdministrationEndpointIT, RoleAssignmentControllerTest |
| POST | `/api/cp/admin/users/{userId}/deactivate` | `deactivateUser` | `user.deactivate` (organization) | D-REG 5.4 U3, first half. `200`, idempotent; ends the user's sessions. `409` for the caller itself and an Administrator. | UserAdministrationEndpointIT, UserAdministrationControllerTest |

### Wired, not counted served (P11-8.1)

Plan 011 P11-7.2 wired and tested this route, but P11-8.1 Step 6b has not recorded the 105 M-row stream (M-Q18), so it is **not counted
served** and the export feature is not shipped on it. The Postman collection marks it `WIRED — NOT COUNTED SERVED`. P11-10.1
(2026-10-07) looked for that measurement and found none: the storage gate ran at one week of S-A and Step 6b was not run
([`011-storage-gate.md`](../../docs/design/backend/evidence/011-storage-gate.md)), so the route stays here. It moves to the table
above only when a 105 M-row stream is recorded.

| Method | Path | operationId | Permission (scope) | Notes | Proved by (launcher-level production-boot IT) |
|---|---|---|---|---|---|
| GET | `/api/console/v1/export/range` | `exportRange` | `metrics.export` (reach) | ⚠️ **Wired and tested, NOT counted served** until P11-8.1's 105 M-row stream exists (M-Q18). `text/csv`, audited once per export (`completed`, `failed` or `cut`); `422 EXPORT_TOO_LARGE` before any byte; `429 export-concurrency`. | ExportEndpointIT, ExportAsyncTimeoutIT, ExportAsyncCompletionIT |

Every other authenticated route answers `428 MUST_ROTATE` while `mustRotate` is set; clients
switch on the Problem `code`, never on status alone. No `/api` response is cacheable (`Cache-Control: no-store`), and every state change is audited by database trigger, joined to the response by `X-Correlation-Id`.

⚠️ **Deployment shape the frontend must plan for.** The launcher serves **only** `/api/**`: it
refuses to start if any static content (an `index.html`, a static bundle) or a management
endpoint mapping appears in it, because a handler mapping outside the authenticated path would
bypass the gate. So the console is served by a **separate same-origin front server** that proxies
`/api` to the launcher — and the `__Host-` cookies are `Path=/` on that origin. Non-browser clients must send a trusted `Origin` on every state-changing request. ⛔ **The launcher refuses to start until the deployment declares how clients reach it** — `sm.gateway.trusted-proxies` for the proxied shape above, or `sm.gateway.direct-client-connections=true` — because behind an unlisted proxy every user would share one login-throttle key.
Trusted origins accept hostnames, IPv4 and bracketed IPv6 literals.

---

## 1. Contracted and served — the first 32 operations of `console.yaml` (the table at the top of this file lists all 73)

These have an agreed path, verb, scope, request and response shape, and **every one has a handler in the production launcher**
(plan 006 owned the authorization substrate, plan 007 the HTTP surface, plan 010 the rest; the rows marked **`OR-45`** and
**`OR-47`** below are the ones no earlier plan built). The paths in this table are beneath the `/api` server.

| Method | Path | Permission | Grant scope |
|---|---|---|---|
| POST | `/cp/auth/login` | *none — establishes the session* | self |
| POST | `/cp/auth/logout` | *authentication only* | self |
| GET | `/cp/auth/identity` | *authentication only* (`OR-15`) | self |
| POST | `/cp/auth/credential` | `local.rotateOwnCredential` ⚠️ not a catalog verb | self |
| GET | `/cp/home/summary` ⚠️ `OR-45` | `solution.view` + `instance.view` | reach |
| GET | `/cp/home/attention` ⚠️ `OR-45` | `health.view` + `instance.view` | reach |
| GET | `/cp/hierarchy/organizations` | *authentication only* | derived |
| GET | `/cp/hierarchy/organizations/{organizationId}/sub-orgs` | *authentication only* | derived |
| GET | `/cp/hierarchy/.../environments` | *authentication only* | derived |
| GET | `/cp/solutions` | `solution.view` | reach |
| POST | `/cp/solutions/load` | `package.load` | organization (`OR-14`) |
| GET | `/cp/targets` | `target.view` | reach |
| GET | `/dp/solutions/{solutionId}/instances` | `instance.view` | reach |
| POST | `/dp/environments/{environmentId}/instances` | `instance.create` + `instance.bindTarget` + `target.use` — body carries `acquisition` (`{mode}` only, M-Q2, `OR-82`) | environment |
| GET | `/dp/instances/{instanceId}` | `instance.view` | instance |
| GET | `/dp/instances/{instanceId}/designer` | `config.view` | instance |
| POST | `/dp/instances/{instanceId}/config` | `config.edit` | instance |
| GET | `/dp/instances/{instanceId}/config/history` | `config.view` | instance |
| POST | `/dp/instances/{instanceId}/config/secrets/{fieldKey}` ⚠️ `OR-45` | `config.edit` — never returns a resolved value (`I6`); answers `422 VALIDATION_FAILED` for a rejected value, an ambiguous field key, an unreadable package or a stage rule, never echoing the value | instance |
| POST | `/dp/instances/{instanceId}/config/accept-runtime` ⚠️ `OR-45` | `config.edit` + `config.overrideWarning` | instance |
| POST | `/dp/instances/{instanceId}/config/restore-runtime` ⚠️ `OR-45` | `config.apply` (+ `instance.restart` when the strategy restarts — ratified, Step 7 batch 1) — writes to the host, returns `ApplyAccepted`; `422` when no generation is staged to restore | instance |
| POST | `/dp/instances/{instanceId}/apply` | `config.apply` (+ `instance.restart` when the strategy restarts) | instance |
| GET | `/dp/instances/{instanceId}/apply/observe` | `config.view` — returns an `ApplyObservationList` | instance |
| POST | `/dp/instances/{instanceId}/apply/rollback` | `config.rollback` (+ `instance.restart`) — body takes `applyId` **or** `generation` (not equivalent: `applyId` undoes that apply, the target being the highest eligible generation BELOW it on this placement), `placementId` required (ratified, Step 7 batch 1) | instance |
| POST | `/dp/instances/{instanceId}/stop` | `instance.stop` ⚠️ **Administrator and Operator** (`OR-121`, `D10` §1.9) | instance |
| GET | `/dp/instances/{instanceId}/health` | `health.view` — the S0-11 notify vocabulary, per placement | instance |
| GET | `/dp/instances/{instanceId}/config-ladder` | `config.view` | instance |
| GET | `/dp/instances/{instanceId}/logs/{component}` | `logs.read` | instance |
| GET | `/dp/instances/{instanceId}/log-levels/{component}` | `logs.read` | instance |
| POST | `/dp/instances/{instanceId}/log-levels/{component}` | `logs.setLevel` — returns `202 ApplyAccepted`, not a bare `Versioned` | instance |
| POST | `/dp/fleet-applies` ⚠️ `OR-47` | `config.apply` (+ `instance.restart` when the strategy restarts) | reach (per-member `require`) |
| GET | `/dp/fleet-applies/{fleetId}` ⚠️ `OR-47` | `config.view` | reach (members filtered by `config.view`) |

✅ **`acceptRuntimeConfig`'s read-back/parse design exists and is ruled** (`OR-45`'s caveat, answered by
[`accept-runtime.md`](../../docs/design/backend/accept-runtime.md); the owner's rulings are `OR-104`, 2026-10-06). It is
served by plan 010 T10.4: the `200` is `AcceptedRuntimeConfig` (`unchanged`, `placementId`,
`fileBytesDifferFromRendered`, `truncated`, and `notAccepted[]` by path with a closed reason and, for a secret, at most
`same`/`differs`); an unreadable host is a declared `503` (`SERVICE_UNAVAILABLE`, widened to "a dependency did not
answer"). A secret's value is never stored; the next apply removes every key listed in `notAccepted`. A `yaml`-typed field
is never accepted (Q-A6 is not yet ruled).

⚠️ **`declareInstance`'s path moved** from `POST /dp/solutions/{solutionId}/instances` to
`POST /dp/environments/{environmentId}/instances` (owner ruling 2026-09-29 (S0-8), `OR-52`) —
`instance.create` is checked at the environment, and the resource is resolved before the body is
read, so the environment cannot come from the body.

⚠️ **`declareInstance` carries the per-instance acquisition mode** (owner rulings M-Q2 and M-Q2b,
2026-09-29; address ruled by `OR-82`, 2026-10-05). The operator **chooses** how the DP reaches the
instance — `acquisition.mode`: `direct-http` (the DP polls the **target's registered address**, fixed
by `registerTarget` (or the operator command that registered the target), at the placement's `healthPort` and the
package-declared health path, over HTTP; listed first) or `via-ssh` (the DP curls the host's
loopback over the target's SSH session) — and the DP remembers the **mode** at instance level, so it
survives a re-target. **No automatic fallback.** The one setting governs both health polling (M-Q2b
amends `OR-39`) and metrics collection. **The declare body carries no host and no address of any
kind** (`OR-82`; amends the contract reading of `OR-71`, whose "DP-reachable host:port per instance
(declare body)" became "the target's address + the package's port"): `acquisition` is `{mode}`
only, closed, and `Instance` returns the mode only. `getInstance` and every `listInstances` item
return `acquisition`, read-only (changing the mode later is a future operation, not v1). It
replaces the earlier per-placement `healthAcquisition`, which is gone from both the declare body
and `Instance.placements[]`. The closed vocabulary lives once, in `components.schemas.Acquisition`.
Accepted residual (M-Q2): a Cache's port also serves its unauthenticated `POST /api/cache`, so the
runbook tells operators to restrict it to the DP's source address. Later, not v1: an
admin-registered list of extra direct addresses per target (`OR-82`).

---

## 1a. The 14 monitoring operations

Plan 011 P11-1.2 contracted them; **P11-7.2 serves them from `console-gateway`** (rows 53-66 of `console-integration.md`
§5.4, under `/api/console/v1`; the paths in `console.yaml` are `/console/v1/...` beneath the `/api` server). Thirteen are
served. **Row 60 (`exportRange`) is wired and tested but not counted served until P11-8.1's 105 M-row measurement
exists (M-Q18)**; a client may call it, and it answers as below, but nobody has measured it at scale. Row 61 is served as its
contracted `501`. The wire shapes are the demo's (`DemoMonitoringController`, M-Q23 `OR-78`); these are data-plane reads, so no
response carries `stateVersion`.

| # | Method | Path | Permission | Grant scope |
|---|---|---|---|---|
| 53 | GET | `/console/v1/capabilities` | *authentication only* (`none.authenticationOnly`) | install |
| 54 | GET | `/console/v1/entity-types` | `metrics.query` | reach |
| 55 | GET | `/console/v1/entity-types/{entityType}/instances` | `metrics.query` + `instance.view` (the handler intersects the two reachable sets) | reach |
| 56 | GET | `/console/v1/metrics/catalog` | `metrics.query` | reach |
| 57 | GET | `/console/v1/labels/{labelKey}/values` | `metrics.query` | reach |
| 58 | POST | `/console/v1/query/range` | `metrics.query` + `metrics.forceRaw` when `resolution: raw` | reach |
| 59 | POST | `/console/v1/query/instant` | `metrics.query` | reach |
| 60 | GET | `/console/v1/export/range` | `metrics.export` (high-consequence, audited) | reach |
| 61 | POST | `/console/v1/metrics/catalog/{name}/type` | `metric.declare` | organization |
| 62 | GET | `/console/v1/dashboards/{dashboardId}` | `dashboard.view` | instance (from the id) |
| 63 | POST | `/console/v1/dashboards/{dashboardId}/widgets` | `dashboard.edit` | instance |
| 64 | PUT | `/console/v1/dashboards/{dashboardId}/layout` | `dashboard.edit` | instance |
| 65 | PATCH | `/console/v1/dashboards/{dashboardId}/widgets/{widgetId}` | `dashboard.edit` | instance |
| 66 | POST | `/console/v1/dashboards/{dashboardId}/reset` | `dashboard.reset` | instance |

⚠️ **What the console must handle.** (1) **Reach, not refusal** (M-Q17): an instance id the caller cannot see is
omitted silently, exactly like one that does not exist; `partial.unavailable[]` names only instances the caller may
see. (2) **Row 61 is a contracted refusal** (M-Q6): `501 NOT_IMPLEMENTED`, `reason: metric-declare-deferred`, never
`403`. (3) **`getCapabilities` carries no byte figure** (`OQ-16`): `collector.disk` is `{state}` only; its `reasons`
are the caller's own organization's. (4) A dashboard id is `db_` plus the instance id; a malformed or foreign id is
the same `404`. A write must present `X-SM-Dashboard-Revision` (required, W2-07); a stale one is `409 CONFLICT`, a missing one `400`.
(5) `lastSeen` in the catalog is epoch milliseconds (as the demo and the frontend carry it), and a plane that cannot
answer leaves the observed fields **absent** and sets each entry's required `observedAvailability` to `unavailable`
(W2-06): a metric never observed has the same absent fields with `live`, so an empty answer never stands for a failed plane.
(6) The catalog's v1 states are `plottable` and `unsupported`; `needs-typing` and `user-extension` have no v1
producer.

**What the served routes add (P11-7.2), which the console must also handle.** (7) **An across-instances aggregate has no instance:**
its series carries `seriesRef.instanceId` = `null` (required and nullable, as `placementId` and `sourceKey` are; `OR-112` (6), `W4-06`: it was the nil UUID, a
sentinel a renderer could mistake for an instance). (8) `replicaComposition.inactive` is always `0` and a series'
`state` is `ok`: an inactive replica is the health plane's fact and is not composed into a series yet. (9) `aggregation.acrossReplicas` is
validated and **not applied** (counters sum across placements, M-Q20; `seriesBy: replica` is the per-placement line). (10)
`getCapabilities` omits `collector.runwayEnd` before the data plane has kept its first partition and omits `collector.disk` when a budget is set and
nothing has been measured yet, and answers `collector.state: unknown` while either is absent (`degraded` still wins when the data plane reports it, `live`
only over both): `OR-112` (6), `W4-05`; it was a stand-in date and `unconfigured` before. (11) A
dashboard write the control plane refuses as invalid is `400` (the routes list no `422`); the dashboard routes' `404` also covers an instance
the data plane does not report active. (12) The rate limit is **per session and per process**: a `429` with `reason: query-rate` and
`Retry-After: 1`, shared by `queryRange`, `queryInstant` and `exportRange`. (13) A `catalog` entry's `restartDetection` is the more
cautious value (`inferred`) when the reachable instances disagree, and its `durability` is `best-effort` if any declaring source is a
broker-pushed one.

---

## 2. Planned, not yet contracted — 4 admin operations

Verbs exist in the ratified catalog; **no plan builds them yet**, so they are absent from
`console.yaml` rather than stubbed. Building the Admin screens against them today means
building against a shape nobody has agreed.

`GET /cp/admin/permission-catalog` · `GET /cp/admin/principals` · `GET /cp/admin/scope-tree` ·
`GET /cp/admin/effective-permissions`

(Plan 013 T3.2 built the rest of the list this section used to carry: `GET`/`POST /cp/admin/users`,
`GET /cp/admin/roles`, `GET`/`POST /cp/admin/assignments`, `DELETE /cp/admin/assignments/{assignmentId}`, and the old
`PATCH /cp/admin/users/{id}` as two operations, `deactivateUser` and `resetUserCredential`; they are in the served table above.)

⚠️ **`POST /cp/admin/roles` is a different case** — `role.edit` **is** catalog member #26, but
`sm_control.role` is `SELECT`-only to the runtime role by DCL, because v1 ships **built-in
roles only**. It is blocked by the data model, not by vocabulary, and needs a decision before
it can ship at all.

---

## 3. Will answer `501`, not `403` — no approved verb

⛔ **The distinction matters to the console.** `403` means *you may not*; `501` means *nobody
may, because the operation has no verb*. Rendering a `501` as a permission error would tell a
user to go ask an administrator for something no administrator can grant.

| Operation | Why | Open item 39 |
|---|---|---|
| `POST /dp/instances/{instanceId}/actions/{actionKey}` | package-declared actions have no verb | letter (e) |
| The ten `/cp/admin/groups*` operations | no `group.*` verb was approved | letter (c) |
| `mode: live` on `setLogLevel` | no live write is built in v1 (`OR-103`, option Rd) — a documented later step, so a build gap too; answers `501`, except on a direct-HTTP instance, where the contract's `422` stands | — |
| `instance.version.redeclare`, archive/restore | resource not identified | letter (i) |
| Agent Gateway operations | the human actions are not enumerated yet | letter (h) |
| `editTargetConnection`, `rotateTargetCredential`, `testTargetConnection` **on an agent target** (plan 015 T3.5) | an agent target has no address, user, pin or credential; `409 TARGET_TRANSPORT_UNSUPPORTED`, nothing read, written or connected to, no probe budget drawn | — |
| `retireTarget` on an agent target whose agent is not revoked; `addPlacement`, or a declare naming an agent target (plan 015 T3.5) | `409 AGENT_STILL_ENROLLED` (revoke first); `409 TARGET_IS_AGENT_MANAGED` (placements on an agent target come only from `OR-27` discovery) | — |
| `readLogs`, `getLogLevel`, `setLogLevel`, `acceptRuntimeConfig`, `restoreRuntimeConfig`, `rollbackApply` **on an agent placement** (plan 015 T3.3) | the agent path reads no host log, logger or config file and runs no `restore` step in v1; `501 AGENT_OPERATION_UNSUPPORTED`, decided on the placement's transport before any host is touched. Not a `200 unavailable`, which would read as a host problem | — |

⚠️ **`Strategy` in `console.yaml` has five members**, of which **four are selectable** since plan 012
T2 (`OR-121`): `manual`, `rolling-health-gated`, and the two ungated ones — `rolling-no-validation` and
`restart-all` — each needing `instance.restart.overrideSafety` (Administrator and Operator) in addition to
`instance.restart`; `restart-all` also needs `outageConfirmedBy` (any non-blank value; the server records the
session principal's id and ignores the value). `scheduled` is **not a strategy anyone selects** (plan 012 T7): a
schedule is a **window** — `schedule: {notBefore, notAfter}` on `submitApply` / `rollbackApply` — that runs one of the
four when it opens. The OpenAPI schema keeps the token so the server can answer **`422`** (naming the `schedule`
field) rather than the schema rejecting the request with `400`. Do not offer `scheduled` in a picker. `outageConfirmedBy` is now accepted on `rollbackApply` and
`restoreRuntimeConfig` as well as `submitApply` and `submitFleetApply`.

---

## 4. What the console must handle, and is easy to get wrong

1. **`stateVersion` is the only ordering signal.** Every stateful response carries it. Refetch
   when a mesh notice reports a higher one; ⛔ **never order by timestamp**, and never treat an
   event payload as state — events are refetch notices (`BE-D1`, `OR-3`).
2. **`availability` is a first-class field, per surface.** A degraded surface is *declared*, with
   a reason. ⛔ **Never render a degraded surface as an empty one** — an empty list and a broken
   feed look identical to a user and mean opposite things. Polling is a declared degraded mode,
   not the normal architecture.
3. **`404` and "exists but out of scope" are deliberately indistinguishable.** Discriminating
   them leaks existence across a scope boundary.
4. **Every mutation carries `X-SM-CSRF`.** Its value comes from the login or identity response.
5. **`mustRotate` gates almost everything.** A principal in that state has a session that
   authorizes only identity, logout and credential rotation. The console must route to the
   rotation screen, not to a dead dashboard.
6. **Permissions in the identity response are for rendering, never for deciding.** The server
   decides; the overlay only hides what would be refused anyway.
7. **No resolved secret is ever returned** (`I6`) — not by the designer, not by history, not by
   the ladder. Fields carry a reference and a presence flag.
8. **`202` means durable, not done.** Apply, rollback, stop, `restoreRuntimeConfig` (`OR-45`),
   `setLogLevel` and `submitFleetApply` (`OR-47`) all return `202 {applyId, stateVersion}` (or,
   for the fleet, `{fleetId, stateVersion, members[]}`) before any adapter call — `setLogLevel`'s
   `202` is a durable request, never a bare `Versioned` body. The outcome of an
   apply/rollback/stop/restore-runtime/setLogLevel `202` is observed at `/apply/observe`; a fleet's at
   `GET /dp/fleet-applies/{fleetId}`, and each admitted member's also at
   `/apply/observe?applyId=`. `ApplyObservation.operation` is `op_known`'s closed set
   `[apply, restart, stop]`, with no log-level member. **Resolved (`OR-103`, 2026-10-06; built in `T9.4`):** D-LOG
   decided against a journaled live-set operation (option Rd: the level is READ live over SSH, and
   every CHANGE is configuration), so there is no fourth `op_known` member and no `V013`
   for it: `setLogLevel` `mode: config` is an ordinary `manual` apply of a new (`edit`) generation whose SM-owned
   overlay carries `logging.level.<logger>`, observed at `/apply/observe` as an `apply`; the level takes effect at
   the next restart, and `getLogLevel` reports it as `configuredLevel`.
   ⚠️ A `202` from `stop` does **not** mean the workload
   stopped: a post-admission failure (`stop.no.known.placement`, an instance-wide stop against an instance with no live
   placement, or a replica that could not be stopped) surfaces as a terminal `STOP_FAILED` on the request. There is no
   `stop.replica.label.collision` any more: the journal is placement-aware (`BE-D44`, `V021`), so two placements whose
   containers share a name are both stopped. An instance-wide stop addresses the instance's **live** placements, from the
   registry (`BE-D46`).
9. **`observeApply` now returns a list, not one object.** `ApplyObservationList {items[]}`,
   ascending by `stateVersion` — `sinceStateVersion` is strictly newer, and `applyId` selects one
   request (what the browser polls for stop observation).

---

## 5. Two hierarchies — do not use one where the other belongs

- **API / topic / event vocabulary — six names** (`OR-2`):
  `Organization → Sub-organization → Environment → Solution → Instance → Placement`.
- **RBAC grant hierarchy — five levels** (`OR-16`, which amends `D11`): the same list
  **ending at Instance**.

⛔ **Placement is an operational child identity of Instance and is not a grantable level.** It
appears in URLs, topics and payloads; it never appears in a role binding. A placement-targeted
`stop` is authorized at the owning instance, which is why a binding cannot grant `stop` for one
placement only. `ScopePath` in `console.yaml` has five components and no placement.

---

## 6. What the frontend can do now

1. **Generate types from `console.yaml`** and delete the hand-written equivalents. The document
   is gated by check 11, so a verb or scope that drifts from the ratified catalog fails the
   build rather than reaching a screen.
2. **Model `availability` and `stateVersion` in the client state layer now.** Retrofitting
   degraded-mode rendering after the fact is how a console ends up showing empty lists for
   broken feeds.
3. **Build the `501` path.** It is a different user-facing message from `403`.
4. **The Admin screens are buildable for what is contracted and served** (plan 013 T3.2): users (`listUsers`, `createUser`,
   `resetUserCredential`, `deactivateUser`), the three built-in roles (`listRoles`) and role bindings (`listAssignments`,
   `grantAssignment`, `revokeAssignment`), plus the hierarchy, entitlement and target administration rows. ⛔ **Do not build** what
   §2 lists (`permission-catalog`, `principals`, `scope-tree`, `effective-permissions`: no shape is agreed), custom roles
   (`POST /cp/admin/roles`: roles are built-in only in v1) or the group screens (§3: `501`). A user is created with a provisional
   credential and must rotate it at first sign-in; the Administrator role is never granted over the API (`422`), and a bootstrap
   administrator's other organizations are renamed by the operator command, not a screen.

---

## 7. Ratified at the T0.1 Step 7 sitting — and what is still flagged

The owner decides on behalf of the architect group (recorded 2026-09-29). At the **T0.1 Step 7
sitting** — 2026-09-30, batches 1–4, with the licence batch closed 2026-10-05 — the owner accepted
every recommendation this section used to list, each as the option marked "(a)" / "Recommended".
The contract text in `console.yaml` is now written as ratified; `validate-openapi.py` check 16 pins
each shape and check 17 refuses any description that still calls one of them pending. The integrator
registers the rulings as `OR-*` rows with T0.3; this document cites the sitting, not a row number.

| Item | What `console.yaml` says | Sitting |
|---|---|---|
| Point 12 | `rollbackApply`'s `placementId` **required** (matching `submitApply`, `BE-D23`) | 2026-09-30, batch 1 |
| Point 16 | `getHomeAttention`'s `items[].stateVersion` **present, per item** (§6.1) | batch 1 |
| Point 17 | `setConfigSecret` → `200 StagedConfig`, one call | batch 1 |
| Point 18 | `acceptRuntimeConfig`'s `placementId` **added, required** | batch 1 |
| Point 19 | `restoreRuntimeConfig`'s `strategy` present, default `manual`; a restarting strategy requires `instance.restart` | batch 1 |
| `Strategy` | all five engine tokens; four run, and `scheduled` is the window token, answered `422` since plan 012 T7 (it was `501`) | batch 1 |
| Q-C1 | `getHomeSummary`: `instanceCount` filtered per instance by `instance.view` at each instance's own scope; `byHealth` counts only those instances where `health.view` is **also** held at that instance's scope (the round-3 corrected reading) | 2026-09-30, batch 2 |
| Q-C1-vocab | `byHealth` keys: the nine-value vocabulary (five rollup keys + `missing`, `unreachable`, `not_exposed`, `bootstrap`) | batch 2 |
| Q-C1-rollup | `byHealth` counts per instance by `project.ts`'s `healthRollupOf` literally at placement scope, the four `NOTFOUND` sub-states inserted (`NOT_APPLICABLE` if no probe; first of any `RED`, `missing`, `unreachable`, `not_exposed`, `RESTART_EXPECTED`, `bootstrap`; all `INACTIVE_REPLICA` → `INACTIVE_REPLICA`; else `GREEN`) | batch 2 |
| Q-C2 | `getHomeAttention`'s `reason` filtered by its source's verb: `health` → `health.view`; `apply-indeterminate` / `config-diverged` → `config.view` | batch 2 |
| Q-C3 | `Target.kind` (MI runtime) kept beside `transport`; legacy `ssh-docker` targets and linked `ssh` Docker/systemd targets are supported | batch 2 |
| Q-G1 | `rolling-health-gated` with no SM-executed probe fails closed before any write: `strategy.health.signal.absent` → `FAILED_NO_EFFECT` | 2026-09-30, batch 3 |
| Q-F4 | fleet `members` `maxItems: 100`, `batchSize` default `1`, `onFailure` default `halt-remaining` | 2026-09-30, batch 4 |
| Q-F5 | a refused fleet always answers `403 FORBIDDEN` with `refusedMembers[]` (never a distinct code) | batch 4 |
| Q-F6, Q-F8 | fleet members (refused ones included) filtered by `config.view` at each member's instance; `404` when none is visible | batch 4 |
| Q-F7 | **no fleet cancel or halt operation in v1** — a known gap, not an omission | batch 4 |
| M-Q2, M-Q2b | the per-instance acquisition mode on `declareInstance` and `Instance` (above) | 2026-09-29, monitoring rulings |

The batch-3 and batch-4 rulings that change no contract text (Q-R1–Q-R4, Q-G2, Q-F1–Q-F3, Q-F9) are
engine and fleet behaviour, carried by T6.8, T6.9 and T11.x, not by this document.

**`gateProved` / `probeStrength` parity is now checked.** `T0.1a` landed `strength` in
`solution-package.schema.json`, and `validate-openapi.py` check 15 pins both console enums to it
(`gateProved` equal; `probeStrength` equal plus `none`). The earlier "blocked on T0.1a" note is closed.

**Three readings of M-Q2 that the ruling's words left open, and one hazard (item 4) — all resolved.**
Items 1, 2 and 4 are **resolved by `OR-82`** (owner ruling 2026-10-05, the S0 slice review); item 3
was a removal and stands. The readings were the contract's text, written minimally.

1. **The port in "host:port" — resolved by `OR-82`.** M-Q2's words were "a DP-reachable host:port
   per instance (declare body)". There is no host in the body now, so the question of how to split
   host and port inside it falls away: the **host is the target's registered address**
   (`target_connection.host`) and the **port is the placement's `healthPort`**, one port field.
   M-Q14's `metricsPorts` map is plan 011's and is not in this contract.
2. **The host grammar — resolved by `OR-82` (dissolved).** With no `acquisition.host`, there is no
   host grammar in the contract (the RFC 1123 pattern is deleted from `Acquisition`). The grammar
   and reachability policy of a target's address belong to the registration of the target
   (`registerTarget`, plan 013, or the operator command), where the registering administrator, not the `instance.create`
   holder, supplies it; the same policy is applied again at every later connection (`target.address.refused`).
3. **`healthAcquisition` removed from the placement.** M-Q2b says "one setting, one path" and M-Q2
   says it survives a re-target's new placement, so the per-placement copy is gone rather than kept
   beside the instance one. Plan 010's T2.1 DDL, T2.5 views, T4.4 and T7.2 are amended by the
   integrator (T0.3, and for the host by the `OR-82` ripple).
4. **`acquisition.host` was an SSRF surface — resolved by `OR-82`** (answer "Target's address",
   option (i) in its first form). (a) The browser no longer supplies any address, so an
   `instance.create` holder (an Operator verb) cannot make the DP poll link-local metadata
   addresses or the DP's own loopback services, and plan 010's constraint B2 ("never come from the
   browser unbounded") holds for this path too. (b) A re-target mints a new placement, possibly on
   a different target (`OR-29`); direct mode follows the **new** target's registered address, so no
   stale host is carried forward and no other machine's process can be attributed to this
   instance. Enforcement is by construction (the field does not exist) and by
   `validate-openapi.py` check 16, which fails if the declare body or `Instance` ever regains a
   host-like property.
