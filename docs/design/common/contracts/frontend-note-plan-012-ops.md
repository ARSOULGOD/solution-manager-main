# Frontend note: what plans 012 and 013 changed on the wire

**Status: a note to the frontend team, not a decision.** It carries no authority of its own: every rule below is stated by
[`contracts/openapi/console.yaml`](../../../../contracts/openapi/console.yaml) (normative) and the decision it cites. It
exists because plan 012 and plan 013 made **no frontend change** (owner standing rule) and so the frontend has to be told.
Written 2026-10-09 by plan 012 T8 against the merged backend tree (73 console operations; permission catalog of 43;
`sm_control` V001–V019, `sm_data` V001–V027, `sm_metrics` V001–V003). `frontend/` was read, not changed.

The Postman collection ([`contracts/openapi/postman/`](../../../../contracts/openapi/postman/)) is generated from the same
contract and has a request for each operation named here.

## 1. Login: send `credential`, not `password`; `organization` is optional

**This is a breaking difference the frontend already has today.** The frontend sends `{username, password}`
(`frontend/src/shared/auth/AuthAdapter.ts`, `Credentials`) and the backend answers `400`: the contract field is `credential`
(plan 013 T1.2 and its W5 review, finding W5D-5).

`POST /api/cp/auth/login` takes:

```json
{ "organization": "<id or name, optional>", "username": "operator", "credential": "…" }
```

- `credential` replaces `password` (required, at least one character).
- `organization` is **optional** (`OR-131` Q3). A username is unique **per organization** (`OR-125` (4), `sm_control` V019),
  so two organizations may each have an `operator`.
  - Omitted on an install that holds **one** organization: that one is used.
  - Omitted on an install that holds **several**: `400 BAD_REQUEST`, before any credential work. The owner accepted that this
    tells a caller that more than one organization exists.
  - Given, it is the organization's **id (UUID) or its name**, compared without regard to case, taken as typed. Whitespace
    around it, an empty string or more than 256 characters is `400`.
  - **Named and unknown is `401 AUTHENTICATION_FAILED`, indistinguishable from a wrong credential.** The login form must not
    try to say which field was wrong (the existing form already says one message under the credential field; keep that).
- A sign-in form should therefore show an organization field only when it is needed: show it after a `400` that says one is
  required, or always on an install the operator knows to be multi-organization. The wire never says in advance.

## 2. The permission catalog is 43 on the server

The backend's catalog (`Permission.java`, `sm_control.permission_catalog`) is **43** verbs. Built-in roles: Administrator 43,
Operator 24, Viewer 8. The source of record is
[`D10-G2-APPROVAL-2026-09-15.md`](../../D10-G2-APPROVAL-2026-09-15.md) §1.1, §1.7 and §1.9 (rows 39 to 43, counted in §1.9.1).

Plan 012 T1 added five (rows 39 to 43), and gave the Operator three (`instance.undeclare`, `instance.restart.overrideSafety`
and `instance.stop`):

| Verb | Class | Held by | Gates |
|---|---|---|---|
| `instance.undeclare` | HIGH | Administrator, Operator | `undeclareInstance` |
| `instance.restart.overrideSafety` | HIGH | Administrator, Operator | an apply, rollback, restore or fleet whose strategy is `restart-all` or `rolling-no-validation`, **in addition to** `config.apply` (or `config.rollback`) and `instance.restart` |
| `target.edit` | HIGH | Administrator | `getTargetConnection`, `editTargetConnection`, `rotateTargetCredential`, `retireTarget` |
| `hierarchy.edit` | STANDARD | Administrator | `createSubOrganization`, `createEnvironment`, `renameOrganization`, `renameSubOrganization`, `renameEnvironment` |
| `entitlement.set` | HIGH, install scope | Administrator | `setEntitlement` |

**`frontend/src/shared/authz/catalog.ts` does not match the server.** It lists 35 ids, and it is not "38 plus five": measured
against `Permission.java`,

- the backend has 13 ids the frontend file lacks: the five above and `audit.view`, `authz.introspect.any`,
  `authz.introspect.self`, `instance.stop`, `mesh.stream.grant`, `mesh.stream.revoke`, `mesh.ticket.mint`, `user.edit`;
- the frontend has five the backend has **not**: `group.view`, `group.create`, `group.edit`, `group.deactivate`,
  `group.member.manage` (frontend `D18`, which this repo cannot corroborate; open item 34 in
  [`DECISIONS.md`](../../DECISIONS.md)). The server has no group routes and would refuse a grant of any of them.

The server is the security boundary and the frontend only projects what it receives (the file's own header says so), but a
screen that hides `undeclareInstance`, or the two restart strategies, until it holds the verb needs the ids. Reconciling the
two lists is the frontend's change; the server's list is the one to match.

## 3. `restart-all`, `rolling-no-validation` and the confirmation's wire shape

Both strategies are served on **four** requests: `submitApply`, `rollbackApply`, `restoreRuntimeConfig` and
`submitFleetApply`. The `Strategy` enum has five tokens; four are selectable (`manual`, `rolling-health-gated`,
`rolling-no-validation`, `restart-all`). **Do not offer `scheduled` in a picker** (section 4).

- **Permissions.** Both need `instance.restart.overrideSafety` in addition to `instance.restart`. A single request without it
  is `403` naming the verb. On a fleet, a member the caller cannot do is `FORBIDDEN` in `refusedMembers[]`; the fleet is `403`
  when every member is refused, and runs the authorized ones if `proceedWithAuthorized: true`.
- **The outage confirmation (`OPS-Q7` C-2).** `restart-all` needs `outageConfirmedBy` (a string) in the body of all four
  requests. Missing or blank is `400`, before anything is written (a refused `restart-all` rollback or restore mints no
  generation). **The value is ignored and never stored**: any non-blank string confirms, and the server records the
  **session principal's id** as the confirmation. So the console should make the operator tick or type a confirmation of its
  own, and send a fixed non-blank string (for example `"confirmed"`); there is nothing to put in it that the server will
  read, and nothing to display back from it.
- **Fleet rule K-2 (`OPS-Q8`).** A `rolling-health-gated` or `rolling-no-validation` fleet may not put two placements of one
  instance in one batch. Member `k` is in batch `k / batchSize`. The submit is refused whole, nothing written: `422
  VALIDATION_FAILED`, with `detail` of the form `member N shares its batch with another placement of its instance
  (fleet.batch.instance.repeated)`. Switch on the token inside the parentheses; `N` is the first member that repeats an
  instance. Fix: `batchSize: 1`. `restart-all` and `manual` are exempt.
- **Settle delay.** A `rolling-no-validation` fleet opens the next batch no earlier than `sm.dp.fleet.no-validation.settle`
  (default 30 s) after the last member of the previous batch finished. A fleet therefore takes longer than its members'
  durations; `observeFleetApply` is the progress source, and nothing on the wire states the delay.
- **Test in dev first (`OR-53`).** The runbook says so
  ([restart without a health gate](../../../runbooks/operations.md#restart-without-a-health-gate-restart-all-and-rolling-no-validation));
  a console that offers the strategies should say it too, at the moment of choosing them.
- After the restart the apply can end `CONFIG_DIVERGED` (SM does not roll back by itself, `OR-64`) or `APPLY_INDETERMINATE`.
  Neither is a failure of the request; both are states to show.

## 4. Scheduling is a `schedule` window, not a strategy

`strategy: "scheduled"` is **`422`** on every request that takes a strategy, naming the `schedule` field. The token stays in
the enum only so an old client is told what to do; it will appear in generated types as one value nobody may send.

To schedule, add to `submitApply` or `rollbackApply`:

```json
"schedule": { "notBefore": "2026-10-12T02:00:00Z", "notAfter": "2026-10-12T04:00:00Z" }
```

- RFC 3339 `date-time` **with an offset**. A date alone, a number or a local time is `400`; so is any other key; `notAfter`
  not after `notBefore` is `400`. The window is half-open, `[notBefore, notAfter)`.
- Both instants must be in the future and at most `sm.dp.apply.schedule.max-lead` ahead (default and ceiling **7 days**):
  otherwise `422`. A picker should not offer a date more than 7 days out.
- `strategy` is the one that runs when the window opens (any of the four). The submitter's verbs are checked **now** and
  again at the window, so a missing verb is a `403` at submit.
- **The `202` body is different.** It is `oneOf`: `ApplyAccepted` (`applyId`) or `ScheduleAccepted` (`scheduleId`, **no
  `applyId`**). Discriminate on which property is present. The `Location` header points at `listApplySchedules` for a
  schedule and at `observeApply` for an apply. Nothing is admitted and no generation is minted while a schedule waits.
- `GET /api/dp/instances/{id}/apply-schedules` (`listApplySchedules`, `config.view`, `limit` 1 to 200, newest first) returns
  every state: `PENDING`, `ADMITTED` (carries `applyId`; observe that at `observeApply`), `CANCELLED`, `MISSED`, `REFUSED`
  (a closed `reason` such as `schedule.authority.revoked`). Optional properties are **omitted** when they do not apply. The
  list shows no principal.
- `DELETE /api/dp/instances/{id}/apply-schedules/{scheduleId}` (`cancelApplySchedule`, `config.apply`, CSRF) cancels a
  `PENDING` schedule. **Any holder of `config.apply` may cancel, not only the submitter.** A schedule that is no longer
  `PENDING` (including a second cancel) is `409`.
- A scheduled **rollback** is copied forward when the window opens, not at submit; the generation it mints is not known at
  submit and is on the schedule record once `ADMITTED` (`admittedGeneration`).
- `restoreRuntimeConfig` and `submitFleetApply` take no `schedule` (`422` for `strategy: scheduled`). A schedule does not
  repeat, and staging a newer generation does not cancel one.

## 5. Undeclare an instance

`DELETE /api/dp/instances/{instanceId}?workload=leave-running` (`undeclareInstance`, `instance.undeclare` at the instance,
CSRF token required, no body).

- `workload=leave-running` is **required**; anything else or nothing is `400` before anything happens. The console must say,
  in its confirmation, what that means: **the container keeps running, unmanaged by SM, with its config file and SM's hidden
  `.sm-preimage-*` and `.sm-fp-*` files beside it, and cleaning them up is the operator's**
  ([runbook](../../../runbooks/operations.md#clean-the-host-after-an-undeclare)). To stop the container first, call
  `stopInstance` and wait for it to land; SM cannot reach a released placement afterwards.
- It is **not reversible**: there is no re-declare, ids are never reused.
- `200 InstanceRetirement`: `instanceId`, `retiredAt`, `workload` (always `left-running`) and `placements[]` with the last
  health SM had persisted.
- **`409`** while a request or stop is in flight, an `APPLY_INDETERMINATE` request is unresolved, a running fleet reserves one
  of its placements, **or a `PENDING` apply schedule names the instance** (`OR-131` Q2). Switch on the problem's `detail`,
  which names the kind and never another request's id. For the schedule case the console should offer the instance's pending
  schedules (`listApplySchedules`) with a cancel for each, then retry.
- `503` means this request's own record was older than half the roll-back window and the control plane was not written:
  wait and retry (the runbook gives the window, ten minutes by default). `404` means released already, or never the
  caller's: the two look the same. A repeated call is `404`.
- After `200` the instance disappears from `listInstances` and `getInstance` (`404`). Role bindings on it stay but grant
  nothing.

## 6. Smaller wire changes the frontend will meet

- **Placements.** `addPlacement` and `retirePlacement` (plan 008) exist; an instance may have more than one placement, a
  `rollbackApply`, `restoreRuntimeConfig` or `acceptRuntimeConfig` changes the instance's generation for **one** placement and
  leaves the others behind, and the configuration ladder reports the lowest rung. A rollout across placements is a fleet, one
  member per placement.
- **Organization rename.** Over HTTP only an install-bound Administrator renames an organization and only their own
  (`OR-125` (3)); every other rename of an organization is the operator command, not an API
  ([runbook](../../../runbooks/operations.md#rename-an-organization-operator-command)).
- **Still `501`.** Two operations answer `501 NOT_IMPLEMENTED` by contract: `declareMetricType`, and `setLogLevel` with
  `mode: live`. Everything else the contract names is served. Live browser notify is **not built**: the console must poll
  (`BE-D6`, the degraded mode of `console-integration.md` §10).
- The registration, hierarchy, entitlement, user and role-binding administration operations (plan 013) are served. Their
  shapes are in the contract and the Postman collection. Four admin read operations (`permission-catalog`, `principals`,
  `scope-tree`, `effective-permissions`) are still planned and not contracted
  ([`ENDPOINT-INVENTORY.md`](../../../../contracts/openapi/ENDPOINT-INVENTORY.md) §2), so an Admin screen that needs them is
  still blocked; the inventory's own "do not build the Admin screens" advice (its §6) is being narrowed to that remainder by
  the wave that owns the inventory.

## Where each rule is stated

| Rule | Authority |
|---|---|
| Login body, optional `organization` | `console.yaml` `login`; `OR-125` (4), `OR-131` Q3 |
| Catalog rows 39 to 43 | `D10-G2-APPROVAL-2026-09-15.md` §1.9, §1.9.1; `OR-121`, `OR-122` |
| Two ungated strategies, the confirmation | `console.yaml` `submitApply`, `rollbackApply`, `restoreRuntimeConfig`, `submitFleetApply`; `OPS-Q6` to `OPS-Q8`; `BE-D50`, `BE-D51` |
| Scheduling | `console.yaml` `ScheduleWindow`, `ScheduleAccepted`, `ApplySchedule`; `OPS-Q9`; `BE-D52`; `OR-131` Q2 |
| Undeclare | `console.yaml` `undeclareInstance`; `OPS-Q2`; `BE-D49` |
| Operator procedure for each | [`docs/runbooks/operations.md`](../../../runbooks/operations.md) |
