# Revoke an agent, change what it may do, retire its target, and watch what it leaves behind

| | |
|---|---|
| **Applies to** | the operator verbs `listAgents`, `getAgent`, `revokeAgent`, `drainAgent`, `retireTarget`, `undeclareInstance` (plan 015 T3.5, T4.3); `AGW-D19` |
| **Severity** | urgent when a host is compromised; routine otherwise |
| **Est. duration** | seconds to take effect (the gateway re-checks every live session each `revocation-check-interval`, 5 s by default, and at every claim and renewal); minutes to verify |
| **Reversible?** | **Revocation is not.** It is sticky: the data plane keeps the identity revoked even if the control-plane row were set back. The remedy is a **fresh enrolment**. Retiring a target is not reversible either (a new registration is a new target). |
| **Status** | **`UNTESTED` as a procedure.** The verbs are covered by `AgentEndpointsIT`, `AgentEnrollmentIT`, `AgentTargetEndpointIT` (including a retire after an offline revoke, W4 integration F1). The run against the real agent is G-A1 (plan 015 T5.1), recorded separately. The previous revision of this runbook (SQL `UPDATE`s on `agent_registration`) is superseded: **do not change `sm_control` or `sm_data` rows by hand.** |

## When to use

- A host or its agent credentials are suspected compromised: **revoke**.
- An agent's envelope, solution, package version or target must change: **revoke and re-enrol** (below; there is no in-place change).
- An agent is gone for good (host decommissioned): **revoke, then retire its target**.
- You want to see what agents exist and what they are doing: **list**.

## What the verbs are (as built in W4)

| Verb | Call | Permission, scope | Notes |
|---|---|---|---|
| `issueAgentEnrolment` | `POST /api/cp/environments/{environmentId}/agent-enrolments` | `agent.enrol` **and** `target.create`, environment | see [`enable-agent-gateway.md`](enable-agent-gateway.md) |
| `listAgents` | `GET /api/cp/agents` | `agent.view`, reach (items filtered by `agent.view` at each agent's environment) | `availability: degraded` when the data plane's connection state cannot be read; the control-plane facts are still listed |
| `getAgent` | `GET /api/cp/agents/{agentId}` | `agent.view` at the agent's environment | `404` when absent **or** out of scope |
| `revokeAgent` | `POST /api/cp/agents/{agentId}/revoke` | `agent.revoke`, environment | `200` with the agent; idempotent; sticky |
| `drainAgent` | `POST /api/dp/agents/{agentId}/drain` | `agent.drain`, environment | `202` / `409 AGENT_NOT_CONNECTED`; see [`restart-the-gateway-with-a-drain.md`](restart-the-gateway-with-a-drain.md) |
| `retireTarget` | `POST /api/cp/targets/{targetId}/retire` | `target.edit`, the target | an agent target only **after** its agent is revoked; else `409 AGENT_STILL_ENROLLED` |

Roles: Administrator holds all four `agent.*` verbs; Operator `agent.view` and `agent.drain`; Viewer `agent.view`. `agent.enrol` and `agent.revoke` are consequence level HIGH. Mutating calls carry the CSRF token and need a secure transport.

`Agent.status`: `pending` (a token was issued, not used), `expired` (it lapsed unused), `active` (enrolled), `revoked` (sticky). `session` is omitted until the agent has connected once; `session.state` is `connected` or `disconnected`, a fact the data plane holds and never a promise. `draining` is not a state here (a drain is a gateway-process transient).

## What revocation does, and what it does not

`revokeAgent` raises the agent's revocation epoch and marks the registration `revoked` (audit `AGENT_REVOKED`, the issuing operator as actor). The data plane then closes the agent's session and refuses its next connection (`IDENTITY_REVOKED`); the agent halts. The speed was measured on the earlier branch at 891 ms with the real agent and is bounded here by the 5 s re-check; **the real-agent timing on this tree is G-A1's to record.**

A command **in flight at the moment of revocation is indeterminate**: the host operation may complete, the data plane will not record its outcome, and the apply engine's step timeout fails that step. Revocation is a security cut-off, not a transaction.

Revoking a **`pending` or `expired`** registration (a token never used) is allowed and **releases its target name** (a pending token holds the name; an expired unused one holds it until revoked, because the database index cannot read the clock).

The agent's **host-side** state is untouched: the managed container keeps running with its last written configuration. Revocation does not stop it.

## Steps: revoke

1. `getAgent` to confirm it is the right agent (`targetName`, `environmentId`, `session.lastSeenAt`).
2. `revokeAgent`. The response carries `status: revoked` and `revokedAt`.
3. Verify (below).

## Steps: retire the agent's target

`retireTarget` on the agent's target (`getAgent.targetId`). Until the agent is revoked it answers `409 AGENT_STILL_ENROLLED` and changes nothing. After `revokeAgent` it **works even if the agent is offline or has never reconnected** (W4 integration F1: the data plane learns the revocation from the control plane's registration inside the retire's own transaction, as a session reconciliation would). If the control plane cannot be read at that moment it **refuses, failing closed**: repeat the call. It also answers `409 CONFLICT` while any live placement names the target: undeclare the instances first: `undeclareInstance` is `DELETE /api/dp/instances/{instanceId}?workload=leave-running` (the parameter is **required**; any other value or none is `400`; the container keeps running, unmanaged). Instances an agent registered are undeclared like any other; a retired target's name is free for a new registration.

You do **not** need to do this by hand for an enrolment **interrupted between the two planes** (the token was used and the control plane has the target, but the data-plane connection never landed): the **enrolment sweep** retires it (revoke, then retire, actor: system) once the token expired and ten minutes (`sm.dp.declare.abandon-after`) passed. **An unused token (`pending`, or `expired`) is not swept**: it holds its target name until you `revokeAgent` it. See [`enable-agent-gateway.md`](enable-agent-gateway.md#the-two-scheduled-jobs-or-140).

## Steps: change what an agent may do (revoke and re-enrol)

There is **no verb that changes an enrolled agent in place**. Capabilities are fixed by the server (`apply-config`, `lifecycle`); the envelope (`allowedConfigRoot`, `allowedContainerPrefix`), the solution and the package version are fixed by the token and immutable. To change any of them: revoke, retire the target (after its instances are undeclared), stop the agent and delete its data directory on the host, `issueAgentEnrolment` with the new values, start the agent with the new token. A fresh agent has a **new agent id**, and the discovery key includes the agent id (below), so it re-registers **every** labelled container as a **new instance**, **including containers you undeclared on purpose** (their old tombstones belong to the old agent). To keep a container out of management for good, remove its `com.solace.sm.instance` label before the new agent starts. Instance history does not continue under the new instance (reasoned: new instance id; not traced).

## Verification

- `getAgent`: `status: revoked`, `revokedAt` set. `listAgents` shows it revoked.
- The agent's session goes `disconnected` and does not return (within the 5 s re-check). On the host the agent's `/healthz` (loopback, `--admin-addr`, default `127.0.0.1:9464`) reports it halted; the process stays up and does not reconnect.
- `retireTarget` answers `200` with `retiredAt` set; `listTargets` no longer lists it as usable.
- Nothing new is delivered: no new command is offered to a revoked identity.

## Discovery tombstones: the state an operator watches

Every container an agent reports is remembered in `sm_data.agent_discovery`, keyed **(tenant, agent id, `com.solace.sm.instance` label value, container name)** (the primary key of `agent_discovery`). A row is `PENDING`, `REGISTERED`, or one of the terminal tombstones `REFUSED`, `ABANDONED`, `UNDECLARED`, `RETIRED`. A terminal row is final: a re-report of the same key answers the stored state and mints nothing. That keeps an undeclared instance from being re-registered every time **this agent** reconnects, and a CI/CD redeploy that **keeps the container name and the label** from re-creating what an operator removed. The protection is per (agent, label, container name) and no wider: **a redeploy that renames the container** (a build number in the name, say), **a changed label value, and a new agent id (any re-enrolment) are each a new key and register the container afresh**. Only one move out of a terminal state exists on the same key: a `REFUSED` or `ABANDONED` key is released when the container is **re-created** (a different container id under the same key); an `UNDECLARED` or `RETIRED` key is never released. To keep a container out of management for good, remove its `com.solace.sm.instance` label.

**The bound: there is none.** Nothing deletes a tombstone: the maintenance function (`agent_gateway_maintenance()`, the daily job) deletes old final commands and expired non-active leases and never touches `agent_discovery`, `agent_event`, `agent_session` or `agent_identity`, and the runtime role has no `DELETE`. Rows accumulate **per distinct (label, container name) an agent has ever reported**. The live-placement cap (16 per agent target) bounds only live placements, not terminal rows. A host whose deployment tooling creates uniquely named containers (a name with a build number, say) adds a row per name. Three limits make it slow rather than unbounded in practice: at most 64 containers per report from the agent, at most 256 items per report looked at by the data plane, and no-row refusals are audited under a hash of the item's facts so repeated reports write one event, not many. **What to watch:** the count of terminal rows per agent. Read-only, as a role with `SELECT` on `sm_data` (an administrative role; the application's own roles are not for ad-hoc reading):

```sql
SELECT agent_id, state, count(*) FROM sm_data.agent_discovery GROUP BY agent_id, state ORDER BY count(*) DESC;
```

Act on a growing `REFUSED`/`ABANDONED`/`UNDECLARED` count by fixing the naming scheme on the host (stable container names, and one label value per instance), not by deleting rows: **deleting a tombstone is not a supported operation** (an `UNDECLARED` row is the only thing that stops a removed instance coming back). A retention rule for terminal rows is **not built**; it is a decision for the owner (it needs a ruling on what a tombstone's life is).

### A discovery row stuck `PENDING` (W4 integration O1)

**Symptom.** The instance an agent registered is `active` in the environment, but its `sm_data.agent_discovery` row is still `PENDING` well past ten minutes, and the agent keeps being answered `PENDING` (it receives no placement id for that container, so it cannot map the container name to its placement).

**Cause.** The pending sweep activates a control-plane-registered instance regardless of its age (its Rule 1) while the discovery row is settled only by the completer, which is **never handed a row older than `sm.dp.declare.abandon-after`** (a node written after the sweep could be orphaned). If the agent's next report comes after that window, nothing moves the row. This is reasoned from the code and recorded as owed at the W4 integration; it has **not** been reproduced on a running system.

**Recognise.** Read-only:

```sql
SELECT agent_id, instance_label, container_name, state, first_seen_at FROM sm_data.agent_discovery
 WHERE state = 'PENDING' AND first_seen_at < now() - interval '15 minutes';
```

**Clear.** No operator verb settles the row in place. The routes that exist: `undeclareInstance` the instance (the row becomes the final `UNDECLARED`), then give the container a **different** `com.solace.sm.instance` label value so the next report is a new key and registers afresh; or revoke and re-enrol the agent. Whether a simpler remedy is warranted (settling the row when the sweep activates the instance) is a later-wave decision; if you meet this, report it with the row.

## Reading what the gateway recorded (`sm_data.agent_event`)

Read-only, as an administrative role with `SELECT` on `sm_data`. Each row is one decision: `code` is the event type (`gateway.refusal`, `gateway.command.refused`, `gateway.drain`, `gateway.drain.complete`, `gateway.revoked`, `agent.enrolled`, `agent.report.refused`, `agent.telemetry.rejected`, `agent.discovery.registered`, `agent.discovery.refused`, `agent.discovery.settled`), `detail_code` the coded reason, `request_id` the request that caused it. The free-text subject the gateway passes to its audit call is **not stored**.

```sql
SELECT occurred_at, code, detail_code, request_id FROM sm_data.agent_event
 WHERE agent_id = '<agentId from getAgent>' ORDER BY occurred_at DESC LIMIT 50;
```

Two rows an operator will look for: a stranded first-issue agent (`gateway.refusal` / `LEASE_RENEW_REQUIRED` / `renewal-timeout-...`, see [`enable-agent-gateway.md`](enable-agent-gateway.md#a-first-issue-agent-that-never-gets-a-lease)) and a drain (`gateway.drain` / `DRAINING`: an operator drain and a planned-restart drain look the same).

## Rollback

Revocation has none by design. To restore a host: `undeclareInstance` the old target's instances (`?workload=leave-running`), retire the old target (else `409 CONFLICT`), **stop the agent and delete its data directory**, issue a new enrolment, start the agent with the new token ([`enable-agent-gateway.md`](enable-agent-gateway.md)).

## Escalation

Backend owners, and whoever owns the CA. Include the `agent_event` rows and the `agent_command` rows not in a terminal state. If revocation does not take effect within a few seconds, check that the control plane is readable: a control-plane read failure *keeps* sessions (it never mass-disconnects) while it is failing.
