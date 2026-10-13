# D-FLEET: fleet apply, designed before the apply slice

**Status: PROPOSED, UNBASELINED.** Written 2026-09-29 by [plan 010](../../plans/backend/010-dp-data-model-and-remote-flow.md)
Task T0.0, against `integration/dp-remote-demo` @ `466e560`. It designs
[`REQ-013`](../../requirements/REQ-013-fleet-apply.md) (fleet apply, ACCEPTED, v1). [`OR-47`](../DECISIONS.md)
requires this design to exist *"before the apply slice"*. The owner reviews it at plan 010's **T0.1 Step 7 sitting**,
together with the contract diff. That review checks that the text says what was ruled. It is not a new decision.

**Decisions drafted here** carry the literal placeholder `«next free BE-D»` (§9). The integrator allocates the real
ids in merge order, and T0.3 registers them. No id is reserved (plan 010 Global Constraints; `OR-11`, `OR-17`).

**Binding inputs, which this design implements and does not re-decide:**

| Ruling | What it fixes here |
|---|---|
| [`OR-47`](../DECISIONS.md) | Fleet apply is v1. It needs a design covering partial failure, ordering, batching, per-instance authorization and audit. |
| [`OR-57`](../DECISIONS.md) (owner ruling 2026-09-29, "fleet authorization") | The authorization shape (§2) and the refusal rule (§3). |
| [`OR-51`](../DECISIONS.md) (owner ruling 2026-09-29, "S0-24") | The reach-filtered classification (`x-sm-scope: reach`) that both fleet operations use. |
| [`OR-49`](../DECISIONS.md) (owner ruling 2026-09-29, "Q-T") | The second tenant list, which batch advancement iterates (§5.5). |
| [`OR-35`](../DECISIONS.md) | `stateVersion` comes from one `sm_data` sequence. `fleet_action` is an owning row, stamped under its own row lock. |
| [`OR-53`](../DECISIONS.md) (owner ruling 2026-09-29, "S0-10") | `placementId` is required on every apply, and restart strategies are generic and package-driven. |
| [`OR-16`](../DECISIONS.md) | A placement is not a grantable level. A member is authorized at its owning instance. |
| [`OR-58`](../DECISIONS.md) | Each member's outcome travels on that member's own `instance.apply-outcome` event. |
| [`OR-59`](../DECISIONS.md) | An `APPLY_INDETERMINATE` member is reconciled like any other request (T6.8). |

**Flagged, not resolved:** nothing in this design touches `D2`, `B/D4`, `B/D12` or `I6`'s flagged readings (`OR-31`,
`OR-40`, `OR-42`, `OR-32`). The design relies on LOCKED `D10`'s two-sided rule and deny-by-default without amending
them. It adds **no verb** (the catalog stays at 38, `Permission.java`), **no event** (the catalog is closed,
[`console-integration.md`](../common/contracts/console-integration.md) §6), and **no `ErrorCode`** beyond the four
that T0.1 point 1 already adds.

**What this does not design:** scheduling, canaries, and automatic rollback of a fleet. [Plan 010](../../plans/backend/010-dp-data-model-and-remote-flow.md)'s
S11 row forbids claiming anything beyond `REQ-013`. §10 Q-F7 records the absence of a fleet cancel operation.

---

## 1. The running scenario

Every option below is run through this one case. It is the case plan 010 T0.0 names.

- Organization `acme`. Environment `prod-us` holds the Cache instances `cache-1` and `cache-2`. Environment
  `prod-eu` holds `cache-eu`. Each instance has one live placement (`p1`, `p2`, `p-eu`). All three are instances of
  the same catalog solution, `cache`.
- The operator `op-us` holds a role with `config.apply` bound at `[acme, …, prod-us]` only. It has no grant that
  reaches `prod-eu`. The operator does not hold `instance.restart` unless a variant says so.
- The operator has already staged a generation on each instance through the normal stage path (§6): `cache-1` at
  generation 7, `cache-2` at 4, `cache-eu` at 9.
- The operator asks for one fleet apply of all three with the `manual` strategy.

**What the gateway and the authorization service do with this, measured:**

- `ScopeVisibility.isVisible(held, candidate)` is `held.authorizes(candidate) || candidate.authorizes(held)`
  (`ScopeVisibility.java:12-16`). A `prod-us` grant and a `prod-eu` instance are siblings, so `cache-eu` is
  **not visible** to `op-us`.
- `ScopeEvaluatingAuthorizationService.require` throws `ResourceNotFoundException` when no held grant is visible from
  the resource (`:41-43`). It throws `AccessDeniedException` when a visible grant exists but none of them authorizes
  the verb (`:44-49`). So for `op-us`, `require(config.apply, cache-eu)` answers **not found**, not forbidden. The
  same call from a principal with an organization-wide `instance.view` grant would answer **forbidden**. The
  scenario's per-member code is `NOT_FOUND`. §3 explains why reporting it leaks nothing.

---

## 2. Authorization shape (point 1). Ruled: `OR-57`

### 2.1 The constraint, measured

- A CATALOG operation's resource is resolved **before the handler runs**, and exactly one resource is `require`d.
  `AuthenticationInterceptor.preHandle` calls `authorize(...)` for every `OperationClassification.Catalog`
  (`AuthenticationInterceptor.java:134-136`). `authorize` resolves one `ResourceRef` and calls `require` once
  (`:154-159`).
- A `ResourceResolver` may read the path, the query string or the session. It may **never** read the body, because
  the body has not been read yet when `preHandle` runs (`ResourceResolver.java`, class javadoc).
- `OperationClassification` is a sealed interface with four kinds (`OperationClassification.java`). T1.3 adds the
  fifth, `reach` (`OR-51`).
- A fleet's members span environments. So **no single path resource authorizes a fleet**: an environment would
  exclude `cache-eu`, and the organization would refuse `op-us` outright.

### 2.2 Options, each run through the scenario

| | **(a) Reach pre-check, then one `require` per member** (ruled) | (b) One request per environment | (c) One `require` at the organization |
|---|---|---|---|
| Path | `POST /dp/fleet-applies` | `POST /dp/environments/{environmentId}/fleet-apply` | `POST /dp/fleet-applies` |
| Pre-handler | `requireHeldAnywhere(op-us, config.apply)`: at least one grant inside the session's organization holds the verb (T1.3's `ReachAuthorization`), else `403` | `require(config.apply, environment)`, the existing single-resource path | `require(config.apply, organization)` |
| In the handler | one `require(config.apply, instance)` per member, plus `require(instance.restart, instance)` when the strategy restarts | nothing more (the resource is the environment) | nothing more |
| Scenario result | pre-check passes. `cache-1` and `cache-2` are authorized. `cache-eu` is refused per member (`NOT_FOUND`). §3 decides what happens next. | the operator needs **two** actions (`prod-us`, then `prod-eu`), and gets two fleet ids. The `prod-eu` call is `404`. No one fleet id traces all three members, **so this fails `REQ-013` bullet 2**. | `403` for `op-us` before any member is considered, because an environment-bound grant does not authorize the organization (`ScopeEvaluatingAuthorizationService.java:44-49`). This is exactly the defect `OR-51` fixes for lists, **and it fails `REQ-013` bullet 1**. |
| What it changes | a fifth classification (already ruled and built by T1.3) plus per-member `require` in the handler | nothing in the gateway | nothing in the gateway, but the environment-bound operator is locked out |

**Ruled (a)**: owner ruling 2026-09-29 (fleet authorization), `OR-57`: *"A reach-filtered pre-check, then one
`require` per member at its own instance."* Options (b) and (c) are recorded as considered and rejected, for the
reasons shown in their scenario rows.

### 2.3 How (a) stays one enforcement point

[`console-integration.md`](../common/contracts/console-integration.md) §5.6 rule 1 says: *"One enforcement point.
`authorizationService.require(principal, verb, resource)`."* Under (a), every member is authorized by that call, at
that member's own instance. The handler holds no second authorization path and no role check. This is how declare
already checks its secondary verbs (`x-sm-permission-also: [instance.bindTarget, target.use]`, plan 010 T0.1
point 8). The reach pre-check is not an authorization of any member. It only refuses early a principal who holds
the verb nowhere.

**Per-member mechanics** (T11.2 builds these; they fix nothing the ruling left open):

1. **Order of checks.** (i) Body shape: `400 BAD_REQUEST`. (ii) Per-member authorization: `require(config.apply)`
   for every member, then `require(instance.restart)` for every member when the strategy restarts. (iii) Refusal
   handling (§3). (iv) Data-plane validation of the **authorized** members only (§5.1): `422 VALIDATION_FAILED`.
   Authorization comes before any data-plane read about a member. A `422` saying "instance X has no generation 9"
   would otherwise confirm that X exists to a caller who cannot see it.
2. **The `ResourceRef` for a member** is the member's **instance**, never its placement (`OR-16`;
   `AuthorizationService` javadoc: *"For a placement-targeted operation … the implementation resolves the owning
   instance's scope from server-side state"*).
3. **Which verbs.** `config.apply` always. `instance.restart` whenever the fleet's strategy restarts anything, as
   `submitApply` already says (`console.yaml`, `submitApply` description). In v1, the only admitted strategy that
   restarts is `rolling-health-gated` (`OR-53`). Variant of the scenario: the fleet is `rolling-health-gated` and
   `op-us` lacks `instance.restart`. Then `cache-1` and `cache-2` are refused `FORBIDDEN` on the second verb, and
   `cache-eu` is still `NOT_FOUND` on the first. Every member is refused, so the fleet is refused under either
   setting of `proceedWithAuthorized` (§3).
4. **`I5` across planes.** A member's instance id comes from the body, but its **authority** does not. `require`
   refuses a resource whose organization differs from the principal's (`ScopeEvaluatingAuthorizationService.java:36-38`).
   The data-plane call carries `tenant = Session.organizationId()`, never a body field. The DP then reads each
   member inside that tenant only. So a member id from another organization is `NOT_FOUND` at `require`, and is
   invisible to the DP as well.
5. **Authorization is decided at submit** (see §10 Q-F1 for the owner question this raises). The members that
   pass become the fleet. Batch advancement (§5) runs in a DP background loop, where no session exists, and
   `dpNeverImportsCp` (`PlaneBoundaryTest.java:53-61`) forbids the DP from calling `AuthorizationService`.

---

## 3. Per-member refusal reporting (point 2). Ruled: `OR-57`

### 3.1 Options, each run through the scenario

| | (a) Refuse the whole action | (b) Admit the authorized, report the refused |
|---|---|---|
| Scenario (`cache-eu` refused) | nothing is written or applied. The response lists `cache-eu` as refused. | `cache-1` and `cache-2` form the fleet. `cache-eu` is recorded as refused on the fleet and reported. |
| Risk | the operator has to resubmit without `cache-eu` | the operator may not notice that the change did not reach `prod-eu` |

**Ruled**: owner ruling 2026-09-29 (fleet authorization), `OR-57`: **by default (a)**. A fleet with any refused
member is refused whole, the refused members are listed, and nothing is applied. **An explicit
`proceedWithAuthorized: true`** gives (b): the authorized members are applied and the rest are reported. There is
never a silent partial action.

### 3.2 The two responses

- **Refused whole** (default, at least one member refused). The response is `403 FORBIDDEN`: a `Problem` with
  `code: FORBIDDEN`, plus an extension member `refusedMembers[] {ordinal, instanceId, placementId, code}`, where
  `code` is `FORBIDDEN` or `NOT_FOUND`. RFC 9457 allows extension members, and the contract shape is §7's. Nothing is
  written on either plane. The same `403` answers when **every** member is refused, even with
  `proceedWithAuthorized: true`, because nothing is left to apply.
- **Proceed with the authorized** (`proceedWithAuthorized: true`, at least one member authorized). The response is
  `202`. The refused members are stored on the fleet as `refused`, with their code, and are returned **in the
  `202`**. Whether later observations show them is **open, Q-F8**. The reason is that a later observation filters
  members by `config.view` at each member's instance (§7, Q-F6). In the scenario, `cache-eu` is not visible to
  `op-us` (§1), so the recommended filter drops it from `op-us`'s own later observations. Showing refused members
  to every viewer instead would reveal a hidden instance's id to a third viewer who never named it. So the text
  cannot promise both "every later observation" and the `config.view` filter. §7 is written for Q-F8's
  recommendation: the `202` is the submitter's record of what was refused, and a later observation shows a refused
  member only to a viewer who can see that instance.

**Why the per-member code leaks nothing.** Each member's code is exactly what a single `submitApply` on that
instance would have answered (`404` or `403`), and the caller named the id. §5.6 rule 4's `404`/`403`
discrimination is kept member by member. The aggregate adds no information that the members' single calls would
not give.

**Why `403`, and not a new code.** §5.6 rule 3: *"A `403` is a decision, not an error to retry."* That is the right
signal for a whole-action refusal. A new `ErrorCode` would be a contract change beyond T0.1 point 1. The choice of
`403` for a refusal whose members are all `NOT_FOUND` is §10 Q-F5.

---

## 4. Own request, journal and audit line per member; one fleet id (point 3)

### 4.1 The shape

- **`apply_request` gains nothing.** Every admitted member is an ordinary `apply_request` row. It has its own
  `apply_id`, `requested_by` (the submitting principal's id), its own journal (intents and attempts, `BE-D13`) and
  its own `instance.apply-outcome` event (`OR-58`). The journal's identity and T2.2's shape are untouched.
- **The audit line is that row.** The D10 approval §1.4 says: *"Privileged **DP** actions (`config.apply`,
  `config.rollback`, `instance.restart`) are audited by `apply_request` itself"*, and the gateway unions the two
  sources at query time ([`D10-G2-APPROVAL-2026-09-15.md`](../D10-G2-APPROVAL-2026-09-15.md) §1.4). A fleet
  therefore writes **no** `sm_control` row. There is no CP write, no `OR-25` audit context and no cross-plane
  transaction.
- **The fleet is two new DP tables** (sketch below; T11.2 owns `V014__fleet_action.sql`). `fleet_action` is the
  owning row. `fleet_member` holds one row per member, and it points at the member's request by `apply_id`.
- **Tracing, both ways.** Fleet to requests: `fleet_member.apply_id`. Request to fleet: `UNIQUE (tenant_id,
  apply_id)` on `fleet_member`, so a request belongs to at most one fleet, found by one indexed lookup.

### 4.2 How a member gets its `apply_id`: options

A member of batch 3 has no request until batch 3 opens (§5), because a `REQUESTED` row is claimable by the worker at
once (`ApplyWorker`). The link from member to request must survive a crash between the admission commit and the
member update.

| | **(a) Pre-minted id** (recommended) | (b) Same-transaction callback | (c) Link after admission, search on recovery |
|---|---|---|---|
| Mechanism | `FleetService.submit` mints each authorized member's `apply_id` (`ApplyId.next()`) and stores it on `fleet_member`. Admission inserts the request **under that id**. | `AdmissionService.admit` takes a callback that updates `fleet_member` inside its own transaction, after the insert. | admit, then update `fleet_member` in a second transaction. After a crash, find the orphan request by (instance, placement, generation, requester, time). |
| Crash between the two writes | no gap. The id is known before admission. Re-admitting under the same id answers "already admitted" (the id check below), so recovery is simply "admit again". | no gap (one transaction). A second scheduler racing it must still re-check the member under its lock. | an orphan request is matched **heuristically**, which can pick the wrong one. **Rejected.** |
| Engine change | A caller-supplied `ApplyId` reaches admission, which inserts it. **When one is supplied, admission first looks the id up, under the scope lock and before the blocking check**, and answers a new `AdmissionOutcome.AlreadyAdmitted(applyId)` if a request with that id exists (see "Why the id check comes first" below). `AdmissionService.java:118-130` today mints the id itself and rethrows any non-fence 23505. That rethrow stays: a primary-key conflict is then an invariant violation, not an answer. The new outcome also reaches `ApplyExecutor`, whose `admit` is the fleet's entry point (§5.5 (ii)); see "The engine delta, in full" below. | `AdmissionService` gains a hook inside design §3.3's one transaction, whose order is documented as *"every line of it is load-bearing"* (`AdmissionService.java`, class javadoc). | none |
| What the operator sees | a member's `applyId` once its disposition is `admitted`. The pre-minted id is a **reservation** until then: it names no `apply_request`, and `observeApply?applyId=` would answer `404`. So the contract omits it until admission (§7). For a `skipped` or `not_admitted` member it never names a request and is never shown. | a member's `applyId` only once it is admitted | the same as (b) |

**Why the id check comes first, and not a primary-key conflict at the insert.** Admission runs, in order, the scope
lock, the blocking check, the fence and the self-supersede, and only then the insert (`AdmissionService.java:84-130`).
A second admission of an id that is already admitted therefore usually never reaches the insert. It is refused
earlier, for the wrong reason. If the member's own request is still `REQUESTED`, `RUNNING` or
`APPLY_INDETERMINATE`, the blocking check answers `SCOPE_BUSY` (`RequestState.blocksScope`). If it landed `APPLIED`
or `STAGED`, or was resolved applied, its own landing raised the watermark (`OutcomeService.java:199-202, 236-238`),
so the fence answers `GENERATION_NOT_ABOVE_WATERMARK`. Only if it ended in a state that neither blocks nor raises
the watermark (`FAILED_NO_EFFECT`, for example) does the insert run, and fail on the primary key, and rethrow. In the
`SCOPE_BUSY` case, the fleet would record a member whose apply is really running as `not_admitted`, and settle it
`failed` permanently (§5.3). In the fence case it would settle a member that succeeded as `failed`. The id check,
taken under the scope lock and ahead of all three, makes admission idempotent per id. The scope lock serializes it, because a pre-minted id
belongs to exactly one member and so to exactly one scope. A single `submitApply` supplies no id, so its path is
unchanged: the check is one indexed read (`apply_request`'s primary key, `V002__apply_request.sql:7`) inserted at
the head of design §3.3's transaction for the fleet caller only. No existing step is reordered.

**The engine delta, in full (measured at `466e560`).** The fleet admits through `ApplyExecutor.admit` (§5.5 (ii)),
not through `AdmissionService` directly, because `admit` holds the pre-admission refusals (operation, strategy,
instance-wide scope, `ApplyExecutor.java:133-157`). So the new outcome crosses two sealed types, and each has a
cast that the new variant would break:

1. **`ApplyExecutor.admit`** maps the outcome with an unconditional cast,
   `((AdmissionOutcome.Admitted) admitted).applyId()` (`ApplyExecutor.java:164`). An `AlreadyAdmitted` would throw
   `ClassCastException` there, on exactly the crash-recovery path this design exists to make safe. `admit` must map
   it explicitly, to a new **`AdmissionResult.AlreadyAdmitted(ApplyId)`**. It must **not** be folded into
   `AdmissionResult.Admitted`: `execute()` (`:209-230`) runs whatever `Admitted` names, so folding would let
   `execute()` start an existing request a second time.
2. **`ApplyExecutor.execute()`** casts `((AdmissionResult.Admitted) result)` (`:227`). It is the test-only
   single-call shape (its javadoc: *"No production caller uses this"*), and it has no fleet caller. It must refuse
   an `AlreadyAdmitted` with a sentence rather than a `ClassCastException`. T11.2 picks the spelling.
3. **Any exhaustive switch over `AdmissionResult`** gains a case. None exists at `466e560` (the consumers are
   `instanceof` chains, `ApplyExecutor.java:211-227`). T6.3's `ApplyService` implementation, which does not exist
   yet, must handle the variant if it switches exhaustively. A single `submitApply` supplies no id, so for it the
   case is unreachable.
4. **How the id travels.** Two shapes, with the same semantics:
   - **(i) An overload, recommended.** `ApplyExecutor.admit(ApplyCommand, ApplyId)` and
     `AdmissionService.admit(ApplyCommand, ApplyId)`. The existing one-argument methods delegate with no id.
     `ApplyCommand` is untouched. Its javadoc defines it as *"what an operator asked for, before admission has
     decided anything about it"*, and a reservation id is not that. `runAdmitted` and the resume path rebuild an
     `ApplyCommand` from the persisted row (`:192`, `:924`, `:965`), and have no reservation to put back into one.
   - **(ii) A record component.** `ApplyCommand` gains a nullable `ApplyId`. That changes the record's canonical
     constructor, and **56 `new ApplyCommand(` sites** would have to change: 3 in `ApplyExecutor` (`:192`, `:924`,
     `:965`, each rebuilding a command from its row), 53 in dp-core and launcher tests, including 14 in `ApplyCommandTest`
     (measured: `grep -rn 'new ApplyCommand(' --include='*.java' backend`). Keeping the six-argument constructor as
     a delegating overload avoids that, at the cost of two constructors for one record.
5. **Ownership: `ApplyExecutor.java` has a fixed merge-order row** in plan 010's file-ownership table: T6.4 (W8),
   then T6.9 (W8a), then T6.8 (W8b), *"never two in one wave"*. T11.2 is not in it. **T9.3 is also in W11, is
   declared parallel with T11.2, and modifies both `ApplyCommand.java` and `ApplyExecutor.java`** (T9.3's Files
   block). So T11.2 editing `ApplyExecutor` is a same-wave conflict that the plan does not currently order. Shape
   (i) removes the `ApplyCommand` half of the conflict. It does not remove the `ApplyExecutor` half. The integrator
   must extend the row to *T6.4 → T6.9 → T6.8 → T9.3 → T11.2*, which matches the migration order the plan already
   fixes (T9.3's V013 before T11.2's V014), and must drop the T9.3 ∥ T11.2 parallel marking for this file (§11).

**Recommendation: (a).** The id is the idempotency key, and the member-to-request link is fixed at submit. Design
§3.3's transaction gains one read at its head, for fleet callers only, and no write and no reordering. Option (b)
would put a write to another table inside it. A UUIDv7 minted at submit and inserted later
carries its mint time. That time is used only for index locality (`V002__apply_request.sql:7`), never for ordering
(`stateVersion` is the only ordering signal, `BE-D4`). This is drafted as §9 F-1. **It adds `AdmissionService`, `AdmissionOutcome`,
`ApplyExecutor` and `AdmissionResult` to T11.2's Files block** (plus `ApplyCommand` only under shape (ii)), and
it puts T11.2 into `ApplyExecutor.java`'s merge-order row after T9.3 (§11).

### 4.3 Table sketch (T11.2 writes the DDL, and may change the spelling but not the rules)

```sql
-- sm_data V014 (T11.2). Sketch only: T11.2 owns names, CHECK spelling and grants.
CREATE TABLE sm_data.fleet_action (
    tenant_id           uuid        NOT NULL,
    fleet_id            uuid        NOT NULL,          -- UUIDv7, minted by the DP
    catalog_solution_id uuid        NOT NULL,          -- REQ-013: "instances of the same solution" (§5.1)
    strategy            text        NOT NULL,          -- one strategy for the whole fleet (§5.1)
    outage_confirmed_by text,
    batch_size          integer     NOT NULL CHECK (batch_size >= 1),
    on_failure          text        NOT NULL CHECK (on_failure IN ('continue','halt-remaining')),
    state               text        NOT NULL CHECK (state IN
                            ('RUNNING','SUCCEEDED','COMPLETED_WITH_FAILURES','HALTED')),
    current_batch       integer     NOT NULL DEFAULT 0,
    requested_by        text        NOT NULL,          -- principal id, never a display name (as apply_request)
    requested_at        timestamptz NOT NULL,
    correlation_id      uuid        NOT NULL,          -- the submit's X-Correlation-Id
    terminal_at         timestamptz,                   -- NULL exactly while RUNNING
    state_version       bigint      NOT NULL,          -- OR-35: state_version_seq, stamped by trigger
    PRIMARY KEY (tenant_id, fleet_id));

CREATE TABLE sm_data.fleet_member (
    tenant_id     uuid    NOT NULL,
    fleet_id      uuid    NOT NULL,
    ordinal       integer NOT NULL CHECK (ordinal >= 0),   -- request order, 0-based
    batch         integer,                                 -- ordinal / batch_size; NULL for 'refused'
    instance_id   uuid    NOT NULL,
    placement_id  uuid    NOT NULL,                        -- non-refused rows: a validated live placement of
                                                           -- instance_id, never the nil sentinel (OR-53, §5.1).
                                                           -- 'refused' rows: instance_id and placement_id are
                                                           -- the caller-named, UNVALIDATED ids (§2.3 item 1
                                                           -- validates authorized members only), and may name
                                                           -- no placement, or another organization's instance
    generation    bigint  NOT NULL CHECK (generation > 0),
    apply_id      uuid,                                    -- pre-minted (§4.2 a); NULL only for 'refused'.
                                                           -- No FK: the request does not exist until admission
    disposition   text    NOT NULL CHECK (disposition IN
                      ('refused','pending','admitted','not_admitted','skipped')),
    refusal_code  text    CHECK (refusal_code IN ('FORBIDDEN','NOT_FOUND')),  -- set iff 'refused'
    admission_refusal text,                                -- RefusalReason name; set iff 'not_admitted'
    settled       text    CHECK (settled IN ('succeeded','failed')),         -- §5.3; set once, never changed
    PRIMARY KEY (tenant_id, fleet_id, ordinal),
    FOREIGN KEY (tenant_id, fleet_id) REFERENCES sm_data.fleet_action (tenant_id, fleet_id),
    UNIQUE (tenant_id, fleet_id, instance_id, placement_id),
    UNIQUE (tenant_id, apply_id));

-- §5.6: at most one live fleet reservation per placement. A second fleet naming a reserved placement is refused
-- 409 CONFLICT, structurally, as apply_request's fences refuse a second in-flight apply.
CREATE UNIQUE INDEX fleet_member_reservation ON sm_data.fleet_member (tenant_id, instance_id, placement_id)
    WHERE disposition IN ('pending','admitted') AND settled IS NULL;
```

**Rules the DDL must carry** (each gets a planted-violation proof in T11.2, `L7`):

- **Append-only in meaning.** The runtime role has `SELECT, INSERT` and column-scoped `UPDATE` (`fleet_action`:
  `state, current_batch, terminal_at`. `fleet_member`: `disposition, admission_refusal, settled`). It has **no
  `DELETE`**, like `apply_request` (`V002__apply_request.sql:148-153`), because the fleet is part of the audit
  trail.
- **Forward-only transitions**, by trigger: `pending → admitted | not_admitted | skipped`, and `admitted` gains
  `settled` exactly once. `refused` is written only at insert. `fleet_action.state` leaves `RUNNING` exactly once.
  `terminal_at IS NULL` holds exactly while the state is `RUNNING`.
- **Stamping (`OR-35`, T0.3's commit-order rule).** `fleet_action` is stamped from `state_version_seq` on insert and
  on every update. Every transaction that changes a `fleet_member` row **first** takes the parent `fleet_action` row
  `FOR UPDATE`, then updates the member, and then updates the parent. A trigger on `fleet_member` also stamps the
  parent, so no member change can go unstamped. The lock taken first keeps the stamp order equal to the commit order
  for that fleet. `fleet_member` has no `state_version` of its own. Its observable changes are the parent's.
- **No secret** (`I6`). The tables hold ids, generation numbers and classifier names only.

---

## 5. Partial failure, ordering, batching (point 4)

### 5.1 What the request says

`{solutionId, members[] {instanceId, placementId, generation}, strategy, outageConfirmedBy?, batchSize,
onFailure, proceedWithAuthorized}`.

- **Order** is the order of `members[]`. Member `k` is in batch `floor(k / batchSize)`.
- **One strategy** applies to every member, because the fleet is one change. A per-member strategy would make the
  `instance.restart` question differ per member for no stated need.
- **Same solution** (`REQ-013`: *"many instances of the same solution"*). Each authorized member's instance must be
  `active` and its `catalog_solution_id` must equal `solutionId` (the DP's own record, `instance_state`, T2.1).
  Otherwise the response is `422 VALIDATION_FAILED` naming the member's ordinal.
- **The member's placement** must be a live placement of that instance (`sm_data.placement`, T2.1), else `422`.
- **The member's generation** must exist for that instance in `config_generation` (T5.2), else `422`. SM does
  **not** verify that the members' generations are "the same change". Each instance's generation is staged content
  for that instance, and instances legitimately differ (names, ports, secret references). The fleet is the
  operator's grouping. It is not a content equality claim. This reading is flagged in F-2.
- **A member is named once.** A duplicate `(instanceId, placementId)` is `422`. The same instance with two different
  placements is two members. Their scopes do not overlap (`ScopeKey.overlaps`), so admission does not refuse the
  second one.
- **Placements of one instance are the way to roll a generation across them** (`BE-D45`, plan 008, `OR-121`). There is
  no instance-wide apply: `OR-53` requires a `placementId`, and an instance-wide apply is refused before admission. So
  "apply generation 7 to both of an instance's placements, one at a time, halting if the first fails" is a fleet whose
  two members are `(instance, P1, 7)` and `(instance, P2, 7)`, `batchSize: 1`, `onFailure: halt-remaining`: two ordinary
  placement-scoped requests, each with its own journal, its own `applyId` and its own events. A partially failed rollout
  is `COMPLETED_WITH_FAILURES`, and the instance's ladder stays at the lower rung until the failed placement applies.
  **"Between placements" therefore means between a fleet's batches**: a wait between placements is a property of the
  fleet (plan 012's `rolling-no-validation` settle delay, `BE-D50`), and an outage confirmation for a `restart-all`
  across placements is the fleet's (`BE-D51`), not a request's. Configuration is per instance, so every placement of
  an instance receives the same generation; per-placement difference is not modelled (plan 008 OQ-8.11 (a); the
  recipe is in [`docs/runbooks/operations.md`](../../runbooks/operations.md)).
- **Whole-action validation.** `422` refuses the whole fleet and writes nothing. `proceedWithAuthorized` covers
  **authorization** refusals only, never validation.

### 5.2 Batches

1. The submit transaction writes `fleet_action` (`RUNNING`, `current_batch = 0`) and every `fleet_member` row
   (`pending` with a pre-minted `apply_id`, or `refused`), and commits. **The `202` is durable from this commit.**
2. The service then **wakes** the `FleetScheduler` (§5.5) and returns. It does **not** call `advance` on the
   request thread, because the scheduler is the fleet's only advancer (§5.5, "One advancer"). Batch 0 is admitted
   by the scheduler's next pass, which the wake brings forward. The `202` therefore reports every authorized member
   as `pending`. Its members' progress is read through `observeFleetApply`.
3. **The next batch is admitted only when every member of the current batch is settled** (§5.3). T0.0 point 4 says
   *"terminal"*. "Settled" departs from that wording in one case only, an `APPLY_INDETERMINATE` member whose
   reconciler has given up, and the departure is raised as Q-F2, not decided here. Members inside a
   batch are admitted in ordinal order. The worker executes them concurrently, up to its pool size
   (`sm.dp.apply.worker.pool-size`, default 2, `ApplyWorkerProperties.java:27`), and **no order is promised inside a
   batch**. `batchSize: 1` is a strictly sequential rollout.
4. When the last batch is settled, the fleet lands on its terminal state: `SUCCEEDED` (every non-refused member
   settled `succeeded`), `COMPLETED_WITH_FAILURES` (at least one member settled `failed` and none was `skipped`,
   under either `onFailure`), or `HALTED` (at least one member was `skipped`, which only `halt-remaining` does).
   A `halt-remaining` fleet whose failure is in its last batch had nothing later to skip, so nothing was halted and
   it lands `COMPLETED_WITH_FAILURES` (`OR-108` (4); pinned by `FleetServiceIT`'s
   `aHaltRemainingFleetWhoseLastBatchFailsLandsCompletedWithFailuresNotHalted`).

### 5.3 When a member is settled, and as what

A member is **settled** the first time the scheduler observes its request in a state that no longer needs waiting
for. The settlement is written once and never changes, so a fleet's verdict does not flip when a request moves on
later (a `STAGED` request superseded by a later apply, for example).

| Member's request, when first observed | Settled | Why |
|---|---|---|
| `REQUESTED`, `RUNNING` | not settled | in flight |
| `APPLIED` | `succeeded` | |
| `STAGED` | `succeeded` | the `manual` strategy's terminal success (`BE-D16`) |
| `INDETERMINATE_RESOLVED`, `resolution_outcome = 'applied'` | `succeeded` | resolved with evidence (`V002` `resolution_is_evidence`) |
| `INDETERMINATE_RESOLVED`, `resolution_outcome = 'not_applied'` | `failed` | |
| `FAILED_NO_EFFECT`, `FAILED_ROLLED_BACK`, `CONFIG_DIVERGED` | `failed` | |
| `SUPERSEDED` | `failed` (conservative) | reachable from `STAGED` through admission (`AdmissionService.java:111-115`) and directly from `APPLY_INDETERMINATE` through the agent-replay path (`OutcomeService.java:141-144`). A first observation cannot tell which. `failed` makes `halt-remaining` stop rather than proceed on an unproven member. See Q-F2. |
| `APPLY_INDETERMINATE` | **not settled**, until `reconcile_class` is set (T6.8's give-up, V011). Then `failed`. | `OR-59` reconciles it first. Waiting forever would stall the fleet with no end. Settling it at once would advance past a host whose state is unknown. See Q-F2. |
| *(no request: admission refused)* | `failed`, disposition `not_admitted`, `admission_refusal` = the `RefusalReason` | `SCOPE_BUSY`, `GENERATION_NOT_ABOVE_WATERMARK`, `FENCE_RACE_LOST` (`RefusalReason.java`), or a pre-admission refusal from `ApplyExecutor.admit` |

The request itself is never changed by the fleet. An `APPLY_INDETERMINATE` member settled `failed` still blocks its
own scope, as architecture.md §16.1 requires, until someone resolves it.

### 5.4 `onFailure`

- **`continue`.** A failed member changes nothing for the others. The next batch opens when the current one is
  settled.
- **`halt-remaining`.** When any member of the current batch settles `failed`, every `pending` member of a **later**
  batch becomes `skipped`. Admitted members of the current batch are **not** stopped. They run to completion,
  because an admitted apply cannot be taken back without a new operation (§10 Q-F7). T11.2's test is
  `haltRemainingStopsOnlyUnadmittedMembers`.
  - **Same-batch siblings are not stopped either** (a reading the plan leaves undefined, flagged as F-2 reading
    (iv) and **Q-F9**, open). `halt-remaining` skips `pending` members of **later** batches only. A current-batch
    member whose admission is refused in pass (ii) (`not_admitted`, settled `failed`) does not stop its
    same-batch siblings. Those that come after it in ordinal order are still admitted in the same pass. Siblings
    left `pending` by a crash mid-(ii) are admitted on the next tick, so the outcome is the same with or without
    the crash. The batch is the unit that `halt-remaining` acts between. `batchSize: 1` is how an operator gets a
    halt after every single member.
- **Visibility, both ways** (`REQ-013` bullet 3: *"one failing instance does not silently stop or hide the
  others"*). Under `continue`, the failure is visible on its member and in the fleet's `COMPLETED_WITH_FAILURES`.
  Under `halt-remaining`, the stop is visible: every skipped member is listed as `skipped`, and the fleet is
  `HALTED` (or `COMPLETED_WITH_FAILURES` when the failure was in the last batch and there was nothing to skip, §5.2
  item 4). Nothing is silent under either setting.

**Scenario, with `proceedWithAuthorized: true`, `batchSize: 1`**: the fleet is `cache-1` (batch 0), then `cache-2`
(batch 1). `cache-eu` is `refused`. Suppose `cache-1` lands `FAILED_ROLLED_BACK`. Under `halt-remaining`, `cache-2`
becomes `skipped` and the fleet is `HALTED`. Under `continue`, `cache-2` is admitted on the next tick, and the fleet
ends `COMPLETED_WITH_FAILURES` or, if `cache-2` fails too, still `COMPLETED_WITH_FAILURES`. In both cases the
observation lists all three members with their own dispositions.

### 5.5 Batch advancement: who advances a fleet, and when

- **A DP background loop, `FleetScheduler`.** A fleet between batches may have no request in flight, so OR-44's
  enumeration (`tenantsWithNonTerminalWork()`, `TenantEnumerationRepository.java:75-82`) would never yield its
  tenant. The scheduler iterates **`tenantsWithActiveInstances()`**, the second tenant list (owner ruling
  2026-09-29 (Q-T), `OR-49`, written once by T2.4 Step 3b and **called, never re-spelt**). For each tenant it runs one
  tenant-scoped query for that tenant's `RUNNING` fleets. It does **not** read `fleet_action` across tenants. That
  would be a third cross-tenant read, which Global Constraints forbid without an owner ruling.
  - *Can a `RUNNING` fleet's tenant have no active instance?* Only if every member's instance left `active`. In v1
    an `active` instance cannot leave that state, because undeclare has no verb (open item 39d, plan 010 S4 row).
    If undeclare is built, this edge needs its own answer.
- **The advance step**, idempotent and crash-safe. (i) Transaction A: lock `fleet_action` `FOR UPDATE`. Settle
  every admitted, unsettled member whose request is now settled (§5.3). Apply `halt-remaining`. If the current batch
  is fully settled, move `current_batch` forward, or land the terminal state. Commit (stamped). (ii) For each
  `pending` member of the current batch: `ApplyExecutor.admit` with the member's pre-minted id, **each in its own
  transaction**, as today. `Admitted` and `AlreadyAdmitted` (§4.2) both mean the member's request exists. (iii)
  Transaction B: lock `fleet_action`, and record each member as `admitted` or `not_admitted`, updating **only
  members still `pending`** (a member already recorded is left alone, so a late (iii) never meets the forward-only
  trigger). **Before recording `not_admitted`, it re-reads whether a request with the member's id exists, and records
  `admitted` if one does.** If `admit` **throws** rather than answering (today: the primary-key conflict rethrown at
  `AdmissionService.java:129`), (ii) catches it for that member, rolls back that member's transaction only, and
  hands (iii) no answer: (iii)'s re-read decides, and a member with no request stays `pending` for the next tick.
  An exception is never a refusal and never stalls the other members. Commit (stamped).
- **Lock order** (Global Constraints: `apply_scope → instance_state → (row)`). No transaction holds `fleet_action`
  while it takes `apply_scope`. Admission (step ii) holds `apply_scope` then `instance_state`. Steps (i) and (iii)
  hold only the fleet row. So the fleet adds no edge that could invert the order.
- **Crash between (ii) and (iii).** The next tick finds the member still `pending` and admits it again under the
  same id. Admission answers `AlreadyAdmitted` (§4.2's id check), and (iii) records `admitted`. Nothing is admitted
  twice. An admission that was refused and never recorded is simply tried again. No observer ever saw the
  unrecorded refusal, because nothing was committed.
- **One advancer, and why correctness depends on it.** An earlier draft of this design said that correctness did
  not depend on there being one scheduler. **That was wrong.** The id check (§4.2) and (iii)'s re-read close the
  case where a second advancer re-admits a member the first one already admitted. They do not close this case: a
  member's admission is **genuinely** refused to advancer B (a single apply holds the scope, `SCOPE_BUSY`). That
  single apply then finishes, and advancer A, which read the member as `pending` earlier, admits it. If B's (iii)
  commits before A's admission, the member is recorded `not_admitted` and settled `failed` while its apply runs.
  Closing that without a single advancer would need admission to read the fleet row inside design §3.3's
  transaction, which is option (b), rejected in §4.2. So the design **requires exactly one advancer per fleet**, and
  enforces it three ways:
  1. `FleetScheduler` runs `advance` on **one thread** (a single-threaded scheduled executor), fleet by fleet. It
     never runs two advances concurrently.
  2. It starts **only in a process that holds `WorkerSingletonGuard`**, the PostgreSQL session-level advisory lock
     that already confines the apply worker pool to one process system-wide (`WorkerSingletonGuard.java`, class
     javadoc; `OR-44`'s single replica). A second process cannot run a second scheduler. The scheduler inherits the
     guard's one stated limit: if the holding connection silently drops, the guarantee is gone until the next
     acquisition attempt notices (same javadoc). This design adds no remedy of its own for that.
  3. Nothing else calls `advance`. `FleetService.submit` wakes the scheduler and does not advance (§5.2 step 2).
     T11.2 pins this with a test (§11).

  The id check and the re-read stay as defence in depth. They are not what makes concurrent advancement safe,
  because there is no concurrent advancement. **They overlap, and that decides how they are tested.** On a
  re-admission of an id that is already admitted, each one alone is enough. Without the id check, the second
  admission waits on the first one's scope lock and is then refused (`SCOPE_BUSY` or the fence), or it throws the
  primary-key rethrow, which (ii) turns into "no answer" (the advance step above). A refusal under that lock means the first request has committed, so (iii)'s re-read finds it and records
  `admitted`. Without the re-read, the id check answers `AlreadyAdmitted`, and (iii) records `admitted`. So no test
  that re-admits an admitted id can go red when only one of the two is removed. Each defence is therefore proven at
  its own boundary (§11 item 2): the id check by what admission answers, and the re-read by what (iii) records when
  admission answers `Refused` while the member's request exists. The case that neither covers is a **genuine**
  `SCOPE_BUSY` followed by a later admission, described in the paragraph above. There, recording `not_admitted` is
  not a defect of either defence, and only the one-advancer rule prevents it.
- **Latency.** A settled batch opens the next one within one scheduler tick (`sm.dp.fleet.advance-interval`,
  recommended default: the worker's `1s` poll interval, `ApplyWorkerProperties.java:28`). The worker then claims
  within its own poll interval. A fleet's terminal state likewise lags its last member's terminal state by at most
  one tick. *(Plan 012 T6, `OPS-Q8` (x), `BE-D50`: a `rolling-no-validation` fleet is the exception - its next batch opens
  no earlier than `sm.dp.fleet.no-validation.settle`, default 30 s, after the current batch's last
  `apply_request.terminal_at` (for a member the reconciler gave up on, which has none, its `apply_reconciliation.gave_up_at`; `BE-D50` rider, W4B-1); the wait is recomputed from persisted rows on every tick, so it survives a restart. The
  last batch lands the fleet without waiting. Submit also refuses, for the `rolling-*` strategies, a batch that holds two
  placements of one instance - `422`, `fleet.batch.instance.repeated` - `restart-all` and `manual` exempt. Both rules apply per fleet: another fleet or a single-placement apply on a different placement of the same instance is neither refused nor delayed.)*
- **One mutating apply path.** `FleetService` and `FleetScheduler` call `ApplyExecutor.admit` only. They never call
  a `RuntimeAdapter`. The path stays `console-gateway → dp-api → durable worker → ApplyExecutor → RuntimeAdapter`
  (plan 010 Global Constraints). T6.7's arch rules should name `com.solace.sm.dp.fleet..` among the packages that
  may not depend on `RuntimeAdapter` (§11).

### 5.6 A fleet and other work on the same placements

- **Fleet against fleet.** A placement reserved by a live member of one fleet (`pending`, or `admitted` and not
  yet settled) cannot be a member of a second `RUNNING` fleet. The partial unique index `fleet_member_reservation`
  refuses it, and the second submit answers `409 CONFLICT` naming the member's ordinal. This also makes a
  double-clicked submit harmless: the second copy is refused, and nothing of it is written.
- **Fleet against a single apply.** A single `submitApply` on a reserved placement is **not** refused by the
  reservation in this design. Admission sees only `apply_request` rows. If the single apply is in flight when the
  member's batch opens, the member is `not_admitted` (`SCOPE_BUSY`). If it applied a higher generation, the member
  is `not_admitted` (`GENERATION_NOT_ABOVE_WATERMARK`). Either way the member fails **visibly**, and `onFailure`
  decides what happens next. Making single applies honour fleet reservations would change admission's blocking
  check, which is design §3.3's single transaction. See Q-F3.
- **A newer generation staged during the fleet.** The fleet applies the generation it names. If the operator stages
  generation 8 on `cache-2` while batch 0 runs, `cache-2`'s member still applies generation 4, unless generation 8
  has already been **applied**, in which case the member is refused by the fence. This is what the request said,
  and it is consistent with the fence ([`eda-walking-skeleton.md`](eda-walking-skeleton.md) §2.2 rule 1).

---

## 6. Member generations (point 5)

- **Every member's generation is staged before the fleet is submitted**, through the normal stage path
  (`stageConfig`: the server re-projects the draft and stores its own result, `OR-34`; `OR-33` guard 1 returns an
  unchanged generation for identical content). The fleet request names `(instanceId, placementId, generation)` for
  each member.
- **A fleet never copies forward inside its submit transaction.** Global Constraints: *"A copy-forward … and the
  submit that follows it run as **two transactions**"*, because one transaction doing both would take
  `instance_state` before `apply_scope` and invert admission's lock order. The fleet submit writes only
  `fleet_action` and `fleet_member`. It stages nothing and copies nothing.
- **Why no server-side "stage the same change on N instances" in v1.** It would need a definition of "the same
  change" across instances that differ, and a stage transaction per member ahead of the fleet. That is scheduling
  work beyond `REQ-013`. The browser stages each member through the existing endpoint, then submits the fleet. A
  staging failure is then an ordinary `409`/`422` on one member's stage, before any fleet exists.

---

## 7. The contract rows T0.1 transcribes (point 6)

T0.1 point 25 transcribes these into `contracts/openapi/console.yaml`, in the one reviewed change. **No verb is
added** (the catalog is closed at 38). **No `ErrorCode` is added** beyond T0.1 point 1's four.

### `POST /dp/fleet-applies`, `operationId: submitFleetApply`

- `x-sm-permission: config.apply`. `x-sm-permission-also: [instance.restart]` (when the strategy restarts).
  `x-sm-scope: reach`. The pre-handler check is `requireHeldAnywhere(config.apply)`. The handler `require`s each
  member at its own instance (§2.3). `CsrfToken` header, as every mutation.
- **Request body** `FleetApplyRequest`:
  - `solutionId` (uuid, required): the **catalog** id (`OR-41`).
  - `members` (array, required, `minItems: 1`, `maxItems`: see Q-F4, ordered) of
    `{instanceId (uuid), placementId (uuid), generation (int64, ≥ 1)}`, all three required. `placementId` is
    required because v1 refuses instance-wide apply (`BE-D23`, `OR-53`).
  - `strategy` (`Strategy`, required). `restart-all`, `rolling-no-validation` and `scheduled` answer `501
    NOT_IMPLEMENTED`, as for `submitApply` (T0.1 point 10).
  - `outageConfirmedBy` (string): required when `strategy` is `restart-all`, as for `submitApply`.
  - `batchSize` (integer, ≥ 1, default: see Q-F4).
  - `onFailure` (`continue | halt-remaining`, default: see Q-F4).
  - `proceedWithAuthorized` (boolean, default `false`, `OR-57`).
  - ⛔ No `organizationId` (`I5`; T0.1 check (v)).
- **Responses:**
  - `202 FleetAccepted`: `{fleetId, stateVersion, state, members[]}`. Each member is `FleetMember` (below).
    Durable from the submit commit (§5.2).
  - `400 BAD_REQUEST`: body shape.
  - `401`: unauthenticated.
  - `403 FORBIDDEN`: either the reach pre-check (no `refusedMembers`), or the refuse-whole rule, as the
    `FleetRefused` problem: `allOf [Problem, {refusedMembers[] {ordinal, instanceId, placementId, code: FORBIDDEN |
    NOT_FOUND}}]` (§3.2).
  - `409 CONFLICT`: a member's placement is reserved by another `RUNNING` fleet (§5.6). `detail` names the member's
    ordinal, never another fleet's id.
  - `422 VALIDATION_FAILED`: §5.1's semantic checks, on authorized members only. `detail` names the member's
    ordinal and the failed rule, never a value from the body.
  - `501 NOT_IMPLEMENTED`: an unbuilt strategy.

### `GET /dp/fleet-applies/{fleetId}`, `operationId: observeFleetApply`

- `x-sm-permission: config.view`. `x-sm-scope: reach`. The pre-handler check is `requireHeldAnywhere(config.view)`.
  The members are then filtered by `authorizedSubset(config.view, INSTANCE, memberInstanceIds)` (T1.3). The
  filter applies to `refused` members too (Q-F8's recommendation, open): a refused member is shown only to a viewer
  who can see its instance.
- **`200 FleetObservation`**: `{fleetId, solutionId, state, strategy, batchSize, onFailure, currentBatch,
  requestedAt, terminalAt?, stateVersion, members[], availability}`.
  - **`stateVersion` is the `fleet_action` row's own stamp**: an entity version, never the maximum of the members.
    That keeps `OR-55`'s rule that composed responses carry per-row versions only.
  - `FleetMember`: `{ordinal, batch?, instanceId, placementId, generation, disposition (refused | pending | admitted
    | not_admitted | skipped), applyId?, settled? (succeeded | failed), refusalCode?, admissionRefusal?,
    requestState?, requestStateVersion?}`. **`applyId` is present exactly when `disposition` is `admitted`.** Before
    that the pre-minted id is a reservation that names no `apply_request` (§4.2), and for `skipped` or
    `not_admitted` it never will, so the contract never shows an id that `observeApply` would answer `404` for.
    `requestState` and `requestStateVersion` are the member's `apply_request` state and stamp (T2.2's column),
    present on the same condition. An admitted member's own progress is also observable through
    `observeApply?applyId=` (T0.1 point 4) and its `instance.apply-outcome` event.
  - Counts, if the contract carries any, are computed over **visible** members only (Q-F6).
- `401`, `403` (reach pre-check), and **`404 NOT_FOUND`**: the fleet is absent, belongs to another organization (the
  lookup is tenant-scoped), or **none of its members is visible** to the caller (Q-F6). The three are not
  distinguished (§5.6 rule 4).

**No event.** The event catalog is closed ([`console-integration.md`](../common/contracts/console-integration.md) §6).
A fleet is observed through this `GET`, and its members through their own events. A `fleet.progress` notice would
be T0.2's single-writer change, and `REQ-013` does not need it.

---

## 8. Invariant audit

| Invariant | How the design holds it |
|---|---|
| `I1` (no MI development) | nothing is asked of any MI. Each member is an ordinary apply. |
| `I5` (tenant scope) | the tenant is `Session.organizationId()` throughout. Every fleet query is tenant-scoped and bind-parameterised. The scheduler's only cross-tenant read is `OR-49`'s list, called, not re-spelt (§5.5). The body names no organization. |
| `I5` across planes | each member is `require`d before either plane is written, and `require` refuses a foreign organization (§2.3 item 4). |
| `I6` (secrets) | fleet rows hold ids, generation numbers and classifier names only. Secret references stay inside each member's `config_generation`. |
| `I7` (plane boundary) | the gateway composes: CP authorization reads, then DP writes. There is no cross-plane transaction and no CP write. The DP never calls CP authorization (§2.3 item 5). |
| One mutating apply path | the fleet only admits (§5.5). T6.7's rules cover `dp.fleet`. |
| `OR-35` stamping | `fleet_action` is stamped, under its own row lock taken first, and member changes stamp the parent (§4.3). |
| `REQ-006` ("no endpoint reachable without it") | both operations are `reach`-classified CATALOG operations, with verbs from the closed catalog. |

---

## 9. Draft decisions (the integrator allocates the ids; T0.3 registers them)

### `«next free BE-D»` (draft F-1): a fleet is a DP-owned record over ordinary apply requests

**Decision.** A fleet apply is one `sm_data.fleet_action` row and one `sm_data.fleet_member` row per member (§4.3).
Every admitted member is an ordinary `apply_request`. **`apply_request` gains no column**, and the journal's identity
(`BE-D13`, `BE-D23`) is unchanged. A member is linked to its request by an `apply_id` **minted at submit** and
inserted by admission (§4.2 a). The id reaches admission through an `admit(ApplyCommand, ApplyId)` overload on
`ApplyExecutor` and `AdmissionService`; `ApplyCommand` is unchanged (§4.2, "The engine delta, in full"). When an id
is supplied, `AdmissionService` looks it up **under the scope lock and before the blocking check**, and answers
`AlreadyAdmitted` if a request with that id exists, and `ApplyExecutor.admit` carries it out as a distinct
`AdmissionResult.AlreadyAdmitted`, never folded into `Admitted`. A primary-key conflict at the insert stays an invariant
violation and rethrows. The pre-minted id is a reservation, and is shown in the contract only once the member is
`admitted` (§7). One `FleetScheduler` thread, in the process holding `WorkerSingletonGuard`, is the only advancer
of any fleet (§5.5). Correctness depends on that. The id check and (iii)'s re-read make crash recovery safe, but
they do not make concurrent advancers safe. The member's audit record is its `apply_request` (D10 approval §1.4).
A fleet writes no `sm_control` row.

**Rationale.** The single-apply journal is plan 005's and plan 007's most-reviewed artifact. A fleet that needed a
new request shape would re-open it. The pre-minted id makes the member-to-request link crash-safe, without a
heuristic search (§4.2 c, rejected) and without a hook in design §3.3's admission transaction (§4.2 b).
**Status: PROPOSED.** It relies on `BE-D13`/`BE-D23` (PROPOSED) as built.

### `«next free BE-D»` (draft F-2): batches settle once, and `onFailure` acts on settlements

**Decision.** Members run in request order, in batches of `batchSize`. The next batch is admitted only when every
member of the current batch is **settled**, by §5.3's table. A settlement is written once and never changes. Under
`halt-remaining`, a failed settlement skips every later `pending` member and never stops an admitted one. A
DP-side `FleetScheduler` advances fleets over `OR-49`'s tenant list, in two fleet-row transactions around per-member
admission, so no transaction holds the fleet row while it takes `apply_scope`. A fleet's placements are reserved
against other fleets (`409 CONFLICT`), not against single applies (§5.6). **Readings flagged for review:** (i) SM
does not verify that members' generations are "the same change" (§5.1). (ii) `SUPERSEDED` at first observation
settles `failed` (§5.3). (iii) `APPLY_INDETERMINATE` settles `failed` only after `OR-59`'s reconciler gives up (§5.3).
(iv) `halt-remaining` acts between batches only: a current-batch member refused at admission does not stop its
same-batch siblings (§5.4).

**Rationale.** A settlement that could change after the fact would make a fleet's verdict unstable. Tying
advancement to `OR-49`'s list uses the one cross-tenant read the owner ruled for this loop. **Status: PROPOSED.**
Readings (ii) and (iii) wait on Q-F2, reading (iv) on Q-F9, and the single-apply half of the reservation waits on
Q-F3.

### `«next free BE-D»` (draft F-3): the mechanics of `OR-57`'s per-member authorization

**Decision.** `POST /dp/fleet-applies` is `reach`-classified on `config.apply`. In the handler, every member is
authorized by `AuthorizationService.require` at its **instance** (never its placement, `OR-16`), for `config.apply`,
and for `instance.restart` when the strategy restarts. This happens before any data-plane read about that member.
Each refused member carries the code a single call would have produced (`FORBIDDEN` or `NOT_FOUND`). A refused fleet
answers `403 FORBIDDEN` with `refusedMembers[]`, unless `proceedWithAuthorized` is true and at least one member is
authorized. Authorization is decided **at submit**. Batch advancement does not re-authorize, because the DP cannot
call CP authorization (`I7`). `GET /dp/fleet-applies/{fleetId}` is `reach`-classified on `config.view`, filters
members by `config.view` at each member's instance, and answers `404` when no member is visible.

**Rationale.** It implements `OR-57` with the one enforcement point (§5.6 rule 1) and no new verb, classification
or `ErrorCode`. **Status: PROPOSED.** The submit-time authorization lifetime waits on Q-F1. The `403` code for a
refusal whose members are all not-found waits on Q-F5. The visibility rule waits on Q-F6, and its application to
`refused` members on Q-F8.

---

## 10. Owner questions this design raises, for the T0.1 Step 7 sitting

These are places where `OR-47` and `OR-57` do not say what happens, and this design **may not decide** them. Each
has a recommendation. §2 to §7 are written for the recommendation, with the alternative noted. **Four gate T0.1's
text**: Q-F4 and Q-F5 fix contract defaults and a response code, and Q-F6 and Q-F8 fix what `observeFleetApply`'s
description promises. The rest gate only T11.2 (W11).

| Id | Question | Status quo (measured) | Options: delta | Recommendation | Gates |
|---|---|---|---|---|---|
| **Q-F1** | **How long does a fleet's authorization last?** | A single apply is authorized once, pre-handler. Its `REQUESTED` row is later executed by the worker with no re-check. That gap is about one poll interval (`1s`, `ApplyWorkerProperties.java:28`). A fleet's later batches may open minutes or hours after submit. | **(a)** authorize at submit only. The authorized set is fixed, and each member's request carries the submitter as `requested_by`. A grant revoked mid-fleet does not stop later batches. **(b)** re-authorize each batch. The DP cannot call CP authorization (`I7`, `dpNeverImportsCp`). This would need a new cross-plane port called from a background loop, with no session to act as. **(c)** (a), plus a bound: a batch that would open more than `sm.dp.fleet.max-age` (for example 24 h) after submit is `skipped` instead. | **(a)**, with (c)'s bound as a later option if the owner wants it. (a) matches what a single apply already does. | T11.2 |
| **Q-F2** | **How do `APPLY_INDETERMINATE` and `SUPERSEDED` members settle?** | `APPLY_INDETERMINATE` is non-terminal and blocks its scope (`RequestState.java:29-40`). `OR-59`'s reconciler may give up, leaving it indeterminate for good (T6.8, `reconcile_class`). `SUPERSEDED` is reachable from `STAGED` and from `APPLY_INDETERMINATE` (§5.3). | **(a)** indeterminate: wait until resolved, or settle `failed` once `reconcile_class` is set. `SUPERSEDED` at first observation: `failed`. This departs from T0.0 point 4's *"every member of the current one is terminal"*, because an indeterminate member is not terminal (`RequestState.isTerminal()`). **(b)** indeterminate: wait until an operator or the reconciler resolves it, however long that takes. **This is the plan's literal reading** of *"terminal"*: `INDETERMINATE_RESOLVED` is terminal and `APPLY_INDETERMINATE` is not. The fleet can then stall with no end, though visibly. **(c)** indeterminate: settle `failed` at once. The fleet may then advance past a host whose state is unknown. | **(a)**, a deliberate departure from the plan's word *"terminal"*, which the owner is asked to accept. If not, (b) is the plan as written, and T11.2's `theNextBatchWaitsForTheCurrentOneToBeTerminal()` keeps its name (§11 item 5). | T11.2 |
| **Q-F3** | **Should a fleet reservation block a single `submitApply`?** | Admission's blocking check reads `apply_request` only (`AdmissionService.java:91-100`). | **(a)** no. A later member fails visibly at admission (`SCOPE_BUSY` or the fence), and `onFailure` decides. Admission is untouched. **(b)** yes. `submitApply` answers `409 CONFLICT` on a reserved placement. This changes design §3.3's admission transaction and the single-apply contract (T0.1 point 10, T6.3). | **(a)** for v1 | T11.2 (and T0.1 point 10, if (b)) |
| **Q-F4** | **Defaults and bounds** | none exist | `maxItems` for `members`: **100** recommended. It bounds per-request `require` calls (up to 2 per member, each reading the principal's grants). `batchSize` default **1**, the most conservative rollout. `onFailure` default **`halt-remaining`**: a failure stops the rollout visibly rather than continuing. | the three values above | **T0.1** (text) |
| **Q-F5** | **Which code answers a refused fleet whose refused members are all `NOT_FOUND`?** | `FORBIDDEN` (403) means *"the verb is not held at the resource's scope"* and `NOT_FOUND` (404) means *"absent, or outside every scope the caller holds"* (`console.yaml`, `ErrorCode`). | **(a)** always `403 FORBIDDEN` with `refusedMembers[]`. The whole action is a decision, and per-member codes carry the detail. **(b)** `404` when every refused member is `NOT_FOUND`, and `403` otherwise. **(c)** a new `FLEET_REFUSED` code, which is a contract change beyond T0.1 point 1. | **(a)** | **T0.1** (text) |
| **Q-F6** | **What does an observer who sees only some members see?** | `OR-51` filters items. It does not say whether a container with no visible item exists for the caller. | **(a)** members are filtered by `config.view`, counts cover visible members only, and the fleet answers `404` when no member is visible. **(b)** the same, but the fleet is visible to anyone with `config.view` anywhere, possibly with zero members. This reveals that a fleet id exists. **(c)** add a count of hidden members, which reveals that instances the caller cannot see exist. | **(a)** | T11.2 (and T0.1 text) |
| **Q-F7** | **No fleet cancel or halt operation in v1.** | `REQ-013` does not require one. It would be a new operation, and the owner decides whether it needs a new verb. | **(a)** none in v1. The operator's levers are `onFailure` at submit, and `instance.stop` per instance (which ends that member's apply, not the fleet). **(b)** add `POST /dp/fleet-applies/{fleetId}/halt` under `config.apply` at every remaining member's instance, which skips `pending` members. | **(a)**, recorded as a known gap | none in plan 010 |
| **Q-F8** | **Are `refused` members re-shown after the `202`, and to whom?** | `REQ-013`'s first acceptance bullet requires that the principal *"is told which are refused"*, and `OR-57` rules the mechanism: the refused fleet is *"listing the refused"*, and a proceeding one *"reports the rest"*. Neither says for how long, or to whom else. Q-F6's filter drops a member the viewer cannot see. In the scenario, that includes `cache-eu` for `op-us` itself (§1). | **(a)** `refused` members are filtered like every other member. The `202` is the submitter's record. A later observation shows a refused member only to a viewer who can see its instance, so `op-us` does not see `cache-eu` again. Nothing leaks, and there is one enforcement point. **(b)** refused members are also shown to the fleet's submitter (`requested_by` = the caller). The submitter named the id, so nothing new leaks to them. But the handler then holds a visibility rule that is not a grant check, which is a second authorization path (§2.3). **(c)** refused members are shown to every viewer of the fleet. This reveals a hidden instance's id to a third viewer who never named it. | **(a)**. `REQ-013` bullet 1 is met at submit, and bullet 2 traces the requests that exist. A refused member has no request. | **T0.1** (text) and T11.2 |
| **Q-F9** | **Does `halt-remaining` stop same-batch siblings of a member refused at admission?** | The plan defines `halt-remaining` only as skipping unadmitted members. Pass (ii) admits a batch's members one by one, in ordinal order (§5.5). | **(a)** no. `halt-remaining` acts between batches. Every member of the current batch is admitted whatever happens to its siblings, and a crash mid-pass changes nothing (§5.4). **(b)** yes. The first `not_admitted` member in pass (ii) turns the remaining `pending` siblings of its batch `skipped`. Siblings already admitted still run, and a sibling that is admitted and later fails at execution still cannot stop the others. So (b) halts on an admission failure but not on an execution failure inside the same batch. | **(a)**. It treats both kinds of failure alike, and `batchSize: 1` gives a halt after every member. | T11.2 |

---

## 11. What this design asks of later plan-010 tasks (for the integrator)

This design edits no other file. These are the deltas it implies, for the integrator to fold into the plan:

1. **T0.1 point 25**: transcribe §7, including `FleetApplyRequest`, `FleetAccepted`, `FleetRefused`, `FleetMember`
   and `FleetObservation`, `maxItems`, the defaults (Q-F4), and the `403`/`409`/`422`/`501` responses. The two
   operationIds proposed here are `submitFleetApply` and `observeFleetApply`. T0.1 Step 4 registers them in
   `ConsoleOperations` as `CONFIG_APPLY` and `CONFIG_VIEW`. **Stale pointer to fix:** T0.1 point 25's row reads
   *"transcribed from T0.0 §6"*. That is T0.0's task-text point 6, but in this design the contract rows are **§7**;
   §6 is member generations. The row should read *"transcribed from `fleet-apply.md` §7"*.
2. **T11.2 Files**: if §4.2 (a) is accepted, add `dp-core/.../apply/engine/AdmissionService.java`,
   `dp-core/.../apply/engine/AdmissionOutcome.java`, `dp-core/.../apply/engine/ApplyExecutor.java` and
   `dp-core/.../apply/engine/AdmissionResult.java`: the `admit(ApplyCommand, ApplyId)` overloads, the id lookup
   under the scope lock **before** the blocking check, the new `AlreadyAdmitted` variant on both sealed types, and
   the two casts it would otherwise break (`ApplyExecutor.java:164`, `:227`). Add
   `dp-core/.../apply/model/ApplyCommand.java` only if the owner or T11.2 prefers shape (ii) to shape (i) (§4.2, "The
   engine delta, in full", item 4). Under (ii), the 56 constructor sites stay unchanged only if the six-argument
   constructor is kept as an overload. **Ownership row:** `ApplyExecutor.java`'s merge order becomes *T6.4 → T6.9 →
   T6.8 → T9.3 → T11.2*. T9.3 and T11.2 are both W11, are marked parallel, and T9.3 also edits `ApplyExecutor.java`
   (and `ApplyCommand.java`), so for this file they must be sequential, T9.3 first (the same order as V013 before
   V014). Also add these tests, each with a planted-violation proof:
   - `aPreMintedIdIsAdmittedOnceAcrossACrashBetweenAdmissionAndRecord()`: crash after (ii) commits and before (iii);
     the next tick leaves exactly one `apply_request` for the member, under its pre-minted id, recorded `admitted`.
     Planted violation: the fleet passes no id, so admission mints a fresh one. The re-admission after the crash is
     then usually refused (`SCOPE_BUSY` while the crashed request is `REQUESTED`/`RUNNING`, the fence once it is
     `APPLIED`/`STAGED`); only after a state that neither blocks nor raises the watermark, such as
     `FAILED_NO_EFFECT`, does it insert a second request. Either way the test goes red on the *"under its pre-minted
     id"* and *"recorded `admitted`"* assertions: (iii)'s re-read finds no request under that id. (Removing only the id check
     does **not** turn this test red. §5.5 explains why, and the next two tests cover each defence.)
   - `reAdmittingAnAdmittedIdAnswersAlreadyAdmittedInEveryRequestState()`: the id check, proven at admission. The
     member's own request is in `REQUESTED`, `RUNNING`, `APPLIED`, `STAGED` and `FAILED_NO_EFFECT`, and
     `ApplyExecutor.admit(command, id)` must answer `AdmissionResult.AlreadyAdmitted(id)` in every one. Planted
     violations: (1) move the id check after the blocking check, and (2) separately, remove it. Each goes red with
     `SCOPE_BUSY`, `GENERATION_NOT_ABOVE_WATERMARK` or a rethrown primary-key conflict, depending on the state.
     (3) Map `AlreadyAdmitted` through `ApplyExecutor.admit`'s existing cast: the test goes red with
     `ClassCastException` (§4.2, "The engine delta, in full", item 1).
   - `aRefusedAdmissionIsRecordedAdmittedWhenTheMembersRequestExists()`: (iii)'s re-read, proven at (iii). The
     member's request already exists under its pre-minted id, and (iii) is handed an admission answer of
     `Refused(SCOPE_BUSY)` for it. It must record `admitted`, not `not_admitted`. The test drives (iii) with that
     answer directly (T11.2 picks the seam), because with the id check in place admission cannot produce it.
     Planted violation: remove the re-read. The member is recorded `not_admitted` while its request exists, and the
     test goes red.
   - *Withdrawn:* the earlier `twoConcurrentAdvancesOfOneFleetNeverRecordAnAdmittedMemberAsNotAdmitted()`. Its two
     planted violations could not fire, because each defence covers the other's removal (§5.5), and its "never"
     claimed more than the design holds: two advancers **can** record a member `not_admitted` after a genuine
     `SCOPE_BUSY` (§5.5, "One advancer"). If T11.2 keeps a concurrent test, its claim must be narrowed to
     *re-admission of an already-admitted id*, with both advancers holding the member as `pending` before either
     admits (a latch, not timing), and the latch must also fix the **commit order** of the two (iii)s, because with
     both defences removed the outcome depends on which commits first. The only planted violation that turns it red is removing **both** defences, and
     the test must say so.
   - `onlyTheFleetSchedulerAdvancesAndOnlyUnderTheWorkerSingletonGuard()`: `FleetService.submit` never calls
     `advance`, and the scheduler refuses to start without the guard. Planted violations: call `advance` from
     `submit`, and separately start the scheduler without the guard.
   - `aSecondRunningFleetOnAReservedPlacementIsRefused409()`, `aSettledMemberNeverChangesItsSettlement()` and
     `aMemberDataPlaneCheckNeverRunsBeforeItsRequire()`.
   - `aMemberShowsNoApplyIdUntilItIsAdmitted()` (§7).
3. **T6.7**: include `com.solace.sm.dp.fleet..` among the packages that may not depend on `RuntimeAdapter`.
4. **T0.3 Step 1**: its stamped-row list already names `fleet_action`. This design adds only that member changes
   stamp the parent (§4.3). No new decision is needed there.
5. **T11.2's existing tests** stay, with **one name and one description to reword**.
   **The name:** `theNextBatchWaitsForTheCurrentOneToBeTerminal()` follows T0.0 point 4's *"terminal"*. This design
   recommends **"settled"** instead (§5.3, Q-F2 (a)). For a member with a request, the two differ in exactly one
   case: an `APPLY_INDETERMINATE` member whose reconciler has given up is settled `failed` but is not terminal
   (`RequestState.isTerminal()` excludes it). A `not_admitted` member has no request, so "terminal" does not apply
   to it, and it settles `failed` at once under either reading. If Q-F2 (a) is accepted, rename the test
   `theNextBatchWaitsForTheCurrentOneToBeSettled()` and add that case to it. If the owner keeps the literal reading
   (Q-F2 (b)), the name stands. **The description:** §5.3's settlement table is what
   `oneFailingMemberNeitherStopsNorHidesTheOthersUnderContinue` and `haltRemainingStopsOnlyUnadmittedMembers`
   assert against. But `eachMemberIsAuthorizedByItsOwnRequire()`'s description reads *"a member in an environment
   the caller does not reach is reported refused, not `403` for the whole call"*. That contradicts `OR-57`'s ruled
   default (§3.2). In the scenario, with `proceedWithAuthorized` false, the whole call **is** `403 FORBIDDEN` with
   `refusedMembers[]`. Implemented literally, the test would assert against the ruling. Suggested wording: *"each
   member is refused by its own `require` at its own instance (`cache-eu` `NOT_FOUND`, the others authorized), not
   by one fleet-level `require`"*. The whole-call outcome is then asserted by
   `aPartlyRefusedFleetIsRefusedWholeByDefaultAndNothingIsApplied()` and
   `proceedWithAuthorizedAppliesOnlyTheAuthorizedAndReportsTheRest()`, as those names already say. The plan file is
   the integrator's to edit, so this is flagged here, not made.

---

## 12. `REQ-013` acceptance, traced

| Acceptance bullet | Where it is met |
|---|---|
| *"Each instance is authorized individually (`config.apply`, plus `instance.restart` when the strategy restarts); a principal who holds the verb on only some of the selected instances is told which are refused."* | §2.3 (one `require` per member per verb), §3 (`refusedMembers[]` on the `403`, or the `refused` members listed in the `202` of a proceeding fleet; re-showing them later is Q-F8) |
| *"Each instance gets its own apply request, journal and audit line; one fleet action is traceable to all of them."* | §4 (one `apply_request` per admitted member as its audit record; `fleet_member.apply_id` both ways; refused and skipped members are on the fleet too) |
| *"Partial failure, ordering and batching are defined and visible; one failing instance does not silently stop or hide the others."* | §5 (order, batches, the settlement table, `onFailure`, and the fleet and member states in every observation) |
