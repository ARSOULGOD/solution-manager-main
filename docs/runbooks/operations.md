# Operations

Procedures for an operator running an install. One section per procedure, in the README's runbook shape. **Every
procedure here is `UNTESTED`** until someone has executed it end to end on a real install.

## Clean up an orphaned governance node after a late declare registration

| | |
|---|---|
| **Applies to** | Solution Manager with the served `declareInstance` (plan 010 T4.4), `sm_control` V013 and `sm_data` V007 |
| **Severity** | routine (a leaked quota slot and an invisible row; nothing is unsafe) |
| **Est. duration** | 15 minutes |
| **Reversible?** | no — step 5 deletes a Control Plane row (and its scope row); both deletions are written to `sm_control.change_audit` by the existing trigger |
| **Status** | `UNTESTED` |

### Why this exists

A declare writes two planes with no distributed transaction ([`BE-D32`](../design/backend/decisions.md)). The Data
Plane row is reserved first; the Control Plane registers the governance node second. If the CP registration is slow
and lands **after** the lazy sweep has abandoned the still-unregistered DP row (the row is older than
`sm.dp.declare.abandon-after`, default ten minutes), the CP node exists for an instance the DP has abandoned. The
abandoned state is terminal (the DP lifecycle trigger allows only `pending` to `active` or `abandoned`), so the sweep
cannot rescue it, and the Control Plane never deletes a governance node (`BE-D32` rule 7). Owner ruling
[`OR-90`](../design/DECISIONS.md) accepts this as **rare** and adds no deadline guard; this is the manual cleanup.

### When to use this

Either of:

- the `declareInstance` caller received `500` and the instance never appeared in `listInstances`, **and** a later
  look at the Control Plane (step 2) shows a node for it; or
- an organization's instance quota (`OR-40`) refuses declares (`409 ENTITLEMENT_REFUSED`) while `listInstances` shows
  fewer instances than the entitlement allows. The orphan counts against the quota (`countInstances` counts
  `sm_control.instance` rows) but is invisible to every read.

The same `500` also arises from a duplicate key at register; that leaves a DP row for the sweep and **no** CP node, so
it needs no cleanup — step 3 distinguishes the two.

### Preconditions

- A database role able to read `sm_data` and delete from `sm_control` (the schema owner or a DBA role; the
  application's runtime roles are not meant to delete governance nodes).
- The organization's id (`<org>`). The DP's `tenant_id` is the organization id.
- A fresh backup or snapshot of the `sm_control` schema.

### Steps

Do **not** join the two schemas in one query: `I7` forbids cross-schema joins in the product, and the manual
procedure keeps to the same discipline (it also makes the comparison explicit).

1. **List the DP's abandoned instances for the organization.**

   ```sql
   SELECT instance_id, name, environment_id
     FROM sm_data.instance_state
    WHERE tenant_id = '<org>' AND lifecycle = 'abandoned';
   ```

2. **List the CP's governance nodes for those ids.**

   ```sql
   SELECT instance_id, environment_id, solution_id
     FROM sm_control.instance
    WHERE organization_id = '<org>' AND instance_id IN ('<id from step 1>', ...);
   ```

3. **Decide.** An id present in **both** results is an orphan: the DP abandoned it, the CP registered it. An id only
   in step 1 is an ordinary abandoned declare (a refused quota, a failed register, a swept pending row) — leave it.
   Never treat an `active` or `pending` DP row as an orphan.
4. **Check nothing was granted on the orphan's scope.** The instance was never visible, so a binding should not
   exist; confirm.

   ```sql
   SELECT s.scope_id, (SELECT count(*) FROM sm_control.role_binding b WHERE b.scope_id = s.scope_id) AS bindings
     FROM sm_control.scope s
    WHERE s.kind = 'instance' AND s.instance_id = '<orphan id>';
   ```

   If `bindings` is not 0, stop and escalate: a principal was granted a role on a node whose instance does not exist.

   Also check that no dashboard was written for the orphan. `sm_control.dashboard` keys on the instance id with
   `ON DELETE RESTRICT`, so a dashboard row would fail the step 5 delete with SQLSTATE `23503`. (The console gateway
   refuses dashboard writes for an instance the Data Plane does not report active (plan 011 P11-7.2, not yet built),
   so a row here is unexpected once that is built; until then this check is the only protection.)

   ```sql
   SELECT count(*) AS dashboards FROM sm_control.dashboard WHERE instance_id = '<orphan id>';
   ```

   If `dashboards` is not 0, stop and escalate.
5. **Delete the orphan** in one transaction, scope row first (**this destroys** the orphan's `sm_control.scope` and
   `sm_control.instance` rows; the environment's Solution node is shared and is left in place).

   ```sql
   BEGIN;
   DELETE FROM sm_control.scope    WHERE kind = 'instance' AND instance_id = '<orphan id>';
   DELETE FROM sm_control.instance WHERE instance_id = '<orphan id>';
   COMMIT;
   ```

6. Repeat for each orphan found in step 3.

### Verification

- Re-run step 2: the orphan id is gone.
- The organization's next `declareInstance` for the same solution succeeds if the entitlement had been exhausted only
  by the orphan.
- `SELECT recorded_at, table_name, operation, row_key FROM sm_control.change_audit ORDER BY change_audit_id DESC LIMIT 5`
  shows the two deletes (`sm_control.scope` and `sm_control.instance`).

### Rollback

Not possible, by design. If the wrong id was deleted, restore the `sm_control` rows from the backup taken in the
preconditions. The DP side is untouched by this procedure.

### Escalation

If step 3 finds an orphan **more than occasionally**, the CP registration is routinely slower than the abandon
window: raise `sm.dp.declare.abandon-after` or investigate Control Plane latency, and tell the owner — `OR-90`
accepted the orphan as rare, and a recurring one is a reason to reopen it (a deadline guard at register). Cite
`OR-90` and `BE-D32` in the report.

## Drop the retired demo's `demo_control` schema

| | |
|---|---|
| **Applies to** | Any host where `backend/demo-launcher` ran (deleted by plan 011 P11-9.1); production installs never had it |
| **Severity** | routine |
| **Est. duration** | 10 minutes |
| **Reversible?** | **no** — destroys the demo's accounts, wrapped SSH-key vault, packages, instances, instance configuration and dashboards. **Once `demo_control` is gone, reverting to the demo is a reinstall, not a revert** (plan 009 Task 5 Step 2's wording) |
| **Status** | `UNTESTED` |

### Why this exists

Deleting the module's migration files is not dropping its schema: on any host where the demo ran, `demo_control` and
its Flyway history (`demo_control.flyway_schema_history`) survive. The demo used its **own** PostgreSQL (database
`sm_demo`, role `sm_demo`, the compose project `sm-demo-control`, loopback port 55433), never `sm_control`, `sm_data`
or `sm_metrics`, so production data is untouched either way. No migration drops it: a self-dropping migration would
need a datasource the retired module no longer has.

### Preconditions

- The branch that deletes the demo is deployed **together with** the frontend's consumer cutover (plan 007's release
  gate). Before then the current frontend still talks to the demo's routes, and this procedure removes the data
  behind them.
- If anything in the demo's vault (SSH private keys) is still needed, export it from the demo first: the vault is
  wrapped by the demo account's password and is not readable afterwards.

### Steps

1. Confirm the target is the demo database and no production schema is in it:
   `psql -h 127.0.0.1 -p 55433 -U sm_demo -d sm_demo -c '\dn'` lists `demo_control` and `public` only.
2. Drop the schema: `psql -h 127.0.0.1 -p 55433 -U sm_demo -d sm_demo -c 'DROP SCHEMA demo_control CASCADE'`.
3. If the whole demo database is no longer wanted, remove the compose project **named `sm-demo-control` only**:
   `docker compose -p sm-demo-control down -v` (the compose file was `backend/demo-launcher/compose.yaml`; recover it
   from git history if it is needed). Do not prune Docker or touch containers outside that project.

### Verification

- `psql … -c '\dn'` no longer lists `demo_control`.
- The production launcher boots as before and `GET /api/cp/auth/identity` still answers for a signed-in session.

### Rollback

Not possible. Restoring the demo is a reinstall from the pre-deletion commit and a new bootstrap admin; the vault
contents are not recoverable without the pre-drop backup.

## Upgrading from the V014/V020 release

| | |
|---|---|
| **Applies to** | an installation running the release whose heads are `sm_control` **V014** and `sm_data` **V020**, upgrading to the release whose heads are `sm_control` **V020** and `sm_data` **V027** (plans 008, 012 and 013, Fable F-1). `sm_metrics` is unchanged (head V003) |
| **Severity** | routine, but **one maintenance window**: three migrations carry a pre-check that can refuse, and they must not be run one at a time |
| **Est. duration** | 30 minutes, plus the time an in-flight stop takes to land |
| **Reversible?** | the pre-checks are read-only. The migrations are reversible by hand until the first instance-wide stop after V021 (see "Reference: V021"); take a backup first |
| **Status** | `UNTESTED` end to end on one database; each migration was run alone on PostgreSQL 14 or 16 |

### Why this exists

Three migrations in this range refuse to run, change nothing and roll back when the data does not satisfy them: `sm_data`
V021 (an open instance-wide request), `sm_data` V024 (a request naming an unregistered placement) and `sm_control` V019 (two
spellings of one username in one organization). Each was first written as a procedure for an upgrade that stopped at that
migration alone. An operator crossing the whole range must do **all the checks first, with the old release still serving**,
because a refusal at the third migration would otherwise arrive after the application was stopped and after the first two had
committed. The other migrations in the range (`sm_control` V015 to V018 and V020, `sm_data` V022, V023 and V025 to V027) have
no pre-check. V020 only replaces the audit function (it admits the organization-rename operator command's no-actor audit
row, `OR-131` Q1).

### Steps

1. **Back up** `sm_control` and `sm_data` (plain `pg_dump` of each schema), and keep the old release's image.
2. **Pre-check 1, `sm_data` V021: drain instance-wide requests, with the OLD release up** (the owner role;
   `00000000-0000-0000-0000-000000000000` is the instance-wide sentinel):
   `psql ... -c "SELECT apply_id, operation, state FROM sm_data.apply_request WHERE placement_id = '00000000-0000-0000-0000-000000000000' AND state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE')"`.
   Expected: no rows. For each row: a `REQUESTED` or `RUNNING` stop lands by itself (observe it at `observeApply`: `STOPPED` or
   `STOP_FAILED`); if the application is down, start the **old** release so its resume path settles it. A row in
   `APPLY_INDETERMINATE` does not move by itself: resolve it with the operator's `resolveIndeterminate`
   ([`install-prerequisites.md`](install-prerequisites.md) describes the give-up). Repeat until the query is empty.
3. **Pre-check 2, `sm_data` V024: count requests naming an unregistered placement** (the owner role):
   `psql ... -c "SELECT count(*) FROM sm_data.apply_request r WHERE r.placement_id <> '00000000-0000-0000-0000-000000000000' AND NOT EXISTS (SELECT 1 FROM sm_data.placement p WHERE p.tenant_id = r.tenant_id AND p.instance_id = r.instance_id AND p.placement_id = r.placement_id)"`.
   Expected `0`. The query prints a count only; do not select the rows into a ticket or a log. If it is not 0, **stop here and
   take the clean-up decision to the owner** (plan 008 OQ-8.5): clear the demo-era rows (their journal rows too, so not
   scripted here) or register the missing placement for the right instance. A `NOT VALID` constraint is deliberately not
   offered. Re-run until 0.
4. **Pre-check 3, `sm_control` V019: detect username case collisions** (the owner role). Expected: no rows.
   `psql ... -c "SELECT organization_id, lower(username) AS folded, count(*) FROM sm_control.principal GROUP BY 1, 2 HAVING count(*) > 1"`.
   It prints organization ids and a folded username; do not paste it into a ticket. For each pair, deactivate one of the two
   (principals are never deleted) and, if its owner still needs access, create a user with a different name once upgraded.
   Re-run until no rows.
5. **Stop the application** (scale to 0) so nothing is admitted and no user is created between the checks and the migrations.
6. **Re-run all three queries** (steps 2 to 4). Every one must still be clean; if a request or a user slipped in before the
   stop, start the **old** release again and go back to step 2.
7. **Run the control-plane migration once**: `SmControlMigrationMain` with the `sm_cp_migrator` credential. It prints
   `migrated N (versions ...)`; confirm the version list **ends in `20`** (`N` is 6 when upgrading from V014).
8. **Run the data-plane migration once**: `SmDataMigrationMain` with the `sm_dp_migrator` credential. It prints
   `sm_data: migrated N (versions ...)`; confirm the version list **ends in `27`** (`N` is 7 when upgrading from V020), and
   that `sm_metrics` still ends in `3`. If a migration still refuses, its message names this procedure and the refusal
   changes nothing; fix the data and re-run that step only. If a migration still refuses after step 7 has committed `sm_control`, restore both schemas from the step-1 backup before restarting the old release.
9. **Only then start the new release.** Its readiness check names the step it finds missing (`V018`, `V019`, `V020` for
   `sm_control`; `V021`, `V024` and later for `sm_data`), so a new binary pointed at an unmigrated database fails fast.

### Verification

- `SELECT max(version::int) FROM sm_control.flyway_schema_history WHERE success` returns `20`, and the same on `sm_data` returns `27`.
- The per-migration verification queries in the three reference notes below return as stated.
- `observeApply` for an old request still answers, and the organization-rename command (below) is accepted.

The three sections that follow ("Upgrade across ...") are **reference notes** for one migration each: why it refuses, how to reverse it, and what
changes for operators. They are not independent procedures; follow the one above.

## Upgrade across the placement-aware apply journal (`sm_data` V021)

> **Reference note** (the heading keeps its name because V019's SQL comment cites "Upgrading to V019"; V019 has no refusal message of its own and fails with SQLSTATE `23505`). Part of [Upgrading from the V014/V020 release](#upgrading-from-the-v014v020-release): do not run this alone.

| | |
|---|---|
| **Applies to** | Solution Manager upgrading to the release whose `sm_data` migration V021 adds `apply_step_intent.placement_id` (plan 008 S2, [`BE-D44`](../design/backend/decisions.md)) |
| **Severity** | routine (a pre-upgrade check) |
| **Est. duration** | 10 minutes, plus the time an in-flight stop takes to land |
| **Reversible?** | the check is read-only. The migration is reversible by hand until an instance-wide stop has journalled two intents of one `replica_ref` under different placements; after that the old unique key cannot be re-created, so it is one-way. Recipe: drop the trigger and function, re-create the old `UNIQUE (tenant_id, apply_id, step, replica_ref)`, drop `placement_id`, delete the V021 row from `flyway_schema_history` (the migration's own closing comment lists the exact order) |
| **Status** | `UNTESTED` |

### Why this exists

V021 refuses to run while **any instance-wide request is open** (`REQUESTED`, `RUNNING` or `APPLY_INDETERMINATE`; today
only a stop can be instance-wide). That is the one request whose journal rows cannot be back-filled correctly: its `STOP`
intents never recorded which placement each addressed. A placement-scoped request in flight (an apply, a restart, a stop
of one placement) is **not** affected: its journal rows are back-filled from the request's own placement and it resumes
onto them after the upgrade. V021 also takes a `SHARE` lock on `apply_request` before it checks, so no instance-wide
request can be admitted between the check and the column being added; writers to that table wait for the migration.

### Steps

The order matters: the old release must settle the request, and nothing may admit a new one between the drain and the
migration.

1. **Drain with the old release still up.** Look for an open instance-wide request (the owner role;
   `00000000-0000-0000-0000-000000000000` is the instance-wide sentinel), using V021's exact predicate:
   `psql … -c "SELECT apply_id, operation, state FROM sm_data.apply_request WHERE placement_id = '00000000-0000-0000-0000-000000000000' AND state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE')"`.
   For each row: a `REQUESTED` or `RUNNING` stop lands by itself (observe it at `observeApply`: `STOPPED` or
   `STOP_FAILED`); if the application is down, start the **old** release so its resume path settles it. A row in
   `APPLY_INDETERMINATE` does not move by itself (a stop that cannot be resumed is stranded, and SM does not poll it
   again): resolve it with the operator's `resolveIndeterminate`
   ([`install-prerequisites.md`](install-prerequisites.md) describes the give-up). Repeat until the query is empty.
2. **Stop the application** (scale to 0) so nothing can admit a new instance-wide stop.
3. **Re-run the drain query.** It must still be empty; if a request slipped in before the stop, go back to step 1 with
   the old release.
4. **Run the migration**: `SmDataMigrationMain` with the migrator credential. It prints `sm_data: migrated N (versions …)`: confirm the version list ends in `27` when this is the combined upgrade (this reference note's own migration is `21`; `N` is the count run in this invocation). If V021 still
   refuses, its message names this procedure and the refusal changes nothing.
5. **Only then start the new release.** Its readiness check refuses to go Ready on a schema without
   `apply_step_intent.placement_id`, so a new binary pointed at an unmigrated database fails fast and names the step.

### Verification

- `SELECT version, success FROM sm_data.flyway_schema_history WHERE version = '21'` returns one row with `success = t`.
- `SELECT conname FROM pg_constraint WHERE conname = 'apply_step_intent_placement_identity_key'` and
  `SELECT tgname FROM pg_trigger WHERE tgname = 'apply_step_intent_placement_identity'` each return one row.
- `observeApply` for an old request still answers.

## Upgrade across the apply-request placement guard (`sm_data` V024)

> **Reference note** (the heading keeps its name because the migration's own refusal message cites it). Part of [Upgrading from the V014/V020 release](#upgrading-from-the-v014v020-release): do not run this alone.

| | |
|---|---|
| **Applies to** | Solution Manager upgrading to the release whose `sm_data` migration V024 adds the referential guard on `apply_request.placement_id` (plan 008 S4, [`BE-D48`](../design/backend/decisions.md)) |
| **Severity** | routine (a pre-upgrade check) |
| **Est. duration** | 10 minutes; the migration rewrites `apply_request` once under an exclusive lock, so run it in a maintenance window |
| **Reversible?** | yes, by hand, in the order the migration's closing comment lists (restore V006's and V008's function bodies, drop the constraint, the `placement_ref` column and `placement`'s new key, delete the V024 row from `flyway_schema_history`). Nothing else depends on the guard |
| **Status** | `UNTESTED` on PostgreSQL 14 (the refusal and the clean upgrade ran on a PostgreSQL 16 harness only) |

### Why this exists

V024 makes the database refuse an `apply_request` that names a placement the registry does not hold **for that request's
own instance** (the instance-wide sentinel, the nil UUID, names no placement and is still accepted for a stop). It
validates the constraint over the existing history, so it **refuses to run, changes nothing and rolls back** while any
request names an unregistered placement, or one registered to another instance. The expected number of such rows is 0,
because the history was recreated at V008 and every later submit path checks a live placement. That expectation has not
been measured on a production database: this procedure is how an operator measures it.

### Steps

1. **Count the offending rows** (the owner role), before stopping anything:
   `psql … -c "SELECT count(*) FROM sm_data.apply_request r WHERE r.placement_id <> '00000000-0000-0000-0000-000000000000' AND NOT EXISTS (SELECT 1 FROM sm_data.placement p WHERE p.tenant_id = r.tenant_id AND p.instance_id = r.instance_id AND p.placement_id = r.placement_id)"`.
   `0` means go to step 3. The query prints counts only; do not select the rows into a ticket or a log (they carry tenant and
   instance ids).
2. **If it is not 0, stop here and take the clean-up decision to the owner** (plan 008 OQ-8.5). The two routes are to
   clear the demo-era rows, which means removing their journal rows too and so is not scripted here, or to register the
   missing placement for the right instance. A `NOT VALID` constraint is deliberately not offered: an unvalidated guard
   is not the guard. Re-run step 1 until it returns 0.
3. **Stop the application** (scale to 0) so no request is admitted between the check and the migration; the migration
   takes an `ACCESS EXCLUSIVE` lock on `apply_request` and does its own check under that lock, so this step only avoids
   writers waiting on it.
4. **Run the migration**: `SmDataMigrationMain` with the migrator credential. It prints `sm_data: migrated N (versions …)`:
   confirm the version list includes `24` (and ends in `27`). If V024 still refuses, its message names this section and the refusal changes nothing.
5. **Start the new release.** It refuses to go ready (naming this section) on a schema where the validated constraint `apply_request_placement_registered` is absent, so skipping step 4 cannot leave the guard silently missing.

### Verification

- `SELECT version, success FROM sm_data.flyway_schema_history WHERE version = '24'` returns one row with `success = t`.
- `SELECT convalidated FROM pg_constraint WHERE conname = 'apply_request_placement_registered'` returns `t` (validated, not `NOT VALID`).
- `SELECT conname FROM pg_constraint WHERE conname = 'placement_instance_identity_key'` returns one row.
- `observeApply` for an old request still answers.

## Upgrade across per-organization usernames (`sm_control` V019)

> **Reference note** (the heading keeps its name because the migration's own refusal message cites it). Part of [Upgrading from the V014/V020 release](#upgrading-from-the-v014v020-release): do not run this alone.

| | |
|---|---|
| **Applies to** | Solution Manager upgrading to the release whose `sm_control` migration V019 makes a username unique per organization, compared without regard to case (plan 013 T3.2, `OR-125` (4)) |
| **Severity** | routine (a pre-upgrade check) |
| **Est. duration** | 5 minutes |
| **Reversible?** | yes, by hand: drop `principal_org_username_ci_uq`, re-add `UNIQUE (username)` as `principal_username_unique` (only possible while no two organizations hold the same username), and delete the V019 row from `flyway_schema_history` |
| **Status** | `UNTESTED` outside the PostgreSQL 14 test harness |

### Why this exists

V002's `UNIQUE (username)` was case-**sensitive**, so `Alice` and `alice` in one organization were legal on V018 and earlier
(the API refused only a repeated spelling until V019). V019's index is on `(organization_id, lower(username))` and cannot be
built over such a pair: the migration fails with SQLSTATE `23505` naming `principal_org_username_ci_uq`, changes nothing, and
rolls back.

### Steps

1. **Detect collisions** (the owner role), before stopping anything. Expected: no rows.
   `psql ... -c "SELECT organization_id, lower(username) AS folded, count(*) FROM sm_control.principal GROUP BY 1, 2 HAVING count(*) > 1"`.
   The query prints organization ids and a folded username; do not paste it into a ticket.
2. **If there are rows, resolve each pair before upgrading.** Principals are never deleted: deactivate one of the pair and, if
   its owner still needs access, create a user with a different name through `createUser` once upgraded. Re-run step 1 until it
   returns no rows.
3. Run the migration (`SmControlMigrationMain` with the migrator credential) and start the new release. It refuses to go ready
   (naming V019) on a schema without the full, non-partial `principal_org_username_ci_uq` index or with the install-wide
   `principal_username_unique` still present.

### What changes for operators

- **Login throttle rows.** Throttle subjects changed: the username dimension is now keyed per organization (by the principal's
  id once it exists). `login_throttle` rows written under the old key (`sha256("local-login:" + username)`) are never read
  again. Rows are **never deleted** (the runtime role has no `DELETE` grant); only their locks expire. A username alarm that is
  active at upgrade time is therefore silently dropped, and no `LOCKOUT_CLEARED` audit row is written for it. Address-dimension
  rows are unchanged.
- **`SmControlLockoutRecoveryMain`** needs `SM_CONTROL_LOCKOUT_ORGANIZATION` with `SM_CONTROL_LOCKOUT_USERNAME`; the
  organization is used exactly as typed (no trimming), by id or name.
- **`TargetRegistrationMain`** needs `SM_TARGET_ORGANIZATION` (the organization it signs in to).
- **Case folding is the database's** `lower()` under its locale: see [install prerequisites](install-prerequisites.md#database-locale-usernames-are-folded-by-postgresql-lower).

### Verification

- `SELECT version, success FROM sm_control.flyway_schema_history WHERE version = '19'` returns one row with `success = t`.
- `SELECT indexdef FROM pg_indexes WHERE indexname = 'principal_org_username_ci_uq'` shows `UNIQUE ... (organization_id, lower((username)::text))` with no `WHERE`.

## Roll a generation across an instance's placements

| | |
|---|---|
| **Applies to** | an instance with two or more live placements (plan 008; `BE-D45`) |
| **Severity** | routine |
| **Est. duration** | the fleet's own duration |
| **Reversible?** | per placement: [`rollbackApply`](../../contracts/openapi/console.yaml) with that placement's id |
| **Status** | `UNTESTED` |

### Why this exists

There is no instance-wide apply: every apply names one placement (`OR-53`). A rollout across an instance's placements is
a **fleet** whose members are `(instance, placement, generation)`, one member per placement. Configuration is per
instance, so every placement receives the same generation.

### Steps

1. Stage the generation as usual (`stageConfig`), so the instance has the generation to roll.
2. Submit `submitFleetApply` with one member per live placement: `batchSize: 1`, `onFailure: halt-remaining`, and the
   strategy the solution supports. Observe the fleet at `observeFleetApply`; each member's request is also observable at
   `observeApply?applyId=`. A `rolling-*` fleet that puts two placements of one instance in one batch is refused at submit
   (`422`, `fleet.batch.instance.repeated`), which is why `batchSize: 1` is the value to use here; see
   [restart without a health gate](#restart-without-a-health-gate-restart-all-and-rolling-no-validation) for the rule and the
   settle delay between batches.
3. **After a one-placement operation** (`rollbackApply`, `restoreRuntimeConfig`, `acceptRuntimeConfig`, `setLogLevel`),
   the instance has a new generation that only that placement received (plan 008 OQ-8.7 (a)); the other placements read
   as behind and the config ladder shows the lowest rung. Roll the new generation across the others with a fleet.

### A solution that must not run two different configurations (uniformity)

A halted `rolling-*` fleet leaves one placement on the new generation and another on the old one, which is unsafe for a
solution whose placements share state (Cache's management queue and namespace, `architecture.md` §9). The package
schema has no field that forces a strategy (plan 008 OQ-8.11 (a): the limit is recorded, no field is invented). For such
a solution, roll across placements as a **`manual` fleet**: it writes every placement's file and restarts none, so a
halt leaves the configuration uniform but unapplied. Then restart by your own means (the customer's CI/CD or an operator
action); the start observation (`restart-observed`, `OR-112` (1)) records it. The ladder shows any divergence per
placement in the meantime.

### Verification

`getHealth` per placement; the instance's config ladder reaches the rung of the fleet's strategy once every placement
has applied.

## Give an instance a second placement

| | |
|---|---|
| **Applies to** | an **active** instance whose package declares no push (`solace_event`) metrics (plan 008 S3, [`BE-D47`](../design/backend/decisions.md)) |
| **Severity** | routine |
| **Est. duration** | 15 minutes, plus the customer's deployment of the container |
| **Reversible?** | yes: [retire the placement](#retire-one-placement-of-an-instance) (the row is kept; the container is yours) |
| **Status** | Live gate L2 PASSED on the E2E host on 2026-10-09 (second attempt; [live gate results](../plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09)). The in-process matrix is `MultiPlacementEndToEndIT`; the procedure is [below](#live-gate-l2-two-placements-on-the-e2e-host) |

### Why this exists

An instance can have more than one placement: a second container, on another target or on the same target with its own
container name, config directory and ports. SM does not deploy it. The customer's CI/CD starts the container, and the
operator **names** it to SM with [`addPlacement`](../../contracts/openapi/console.yaml) (`OR-29`: capacity, packing and
ports stay with the customer; `I1`).

### Preconditions

- The **second container is deployed and running** by your own means, with its configuration directory mounted. SM never
  starts it. Until it runs, `getHealth` reads the new placement `unreachable`, which is true.
- Its container name starts with the target's registered container prefix, and its directory lies under the target's
  registered config root (the envelope SM refuses to leave: `422` naming the field). On a target that already hosts a
  placement of any instance, it needs its **own** container name, directory, health port and metrics host port
  (`409`).
- The instance's package declares **no** `solace_event` metric source. A push-metrics instance is refused `409`: a push
  reading names the instance, not a placement, so with two it could not be attributed and would be dropped. Pull-path
  metrics are per placement and unaffected.
- You hold `instance.bindTarget` at the instance and `target.use` at the target (`D10`'s two-sided rule).
- **No instance-wide stop is in flight** for the instance (`409` otherwise): a stop reads the live placements once, when
  it starts, and would leave the new one running while reporting the instance stopped. Add after it lands.
- Nobody is staging in the designer: the add stamps the instance, so a session staged against the earlier `stateVersion`
  is refused `409` on its next stage and must re-read.

### Steps

1. **Send `addPlacement`**: `POST /api/dp/instances/{instanceId}/placements` with the target and the placement
   (`containerName`, `configDirectory`, `containerConfigPath`, `fileName`, `healthPort`, and `metricsPorts` as
   `{"<source port>": <host port>}`). The body carries no probe, host or address: those come from the declared package version
   and the registered target. The answer is the instance with its new `stateVersion`.
2. **Read what the new placement starts as.** It holds nothing, so the configuration ladder reads **`rendered`** (the
   lowest rung across live placements) and `instance.config-state` is announced if the stored rung moved. `getHealth`
   lists only the placements it has a verdict for and says, in `availability`, how many have not been polled yet; after
   the next poll the new one is listed.
3. **Roll the instance's current generation to it.** Configuration is per instance, so the new placement receives the
   **same** generation as the others: `submitFleetApply` with one member, or `submitApply` naming the placement. Until then
   the instance reads as behind. If the solution must not run two configurations, read
   [Roll a generation across an instance's placements](#roll-a-generation-across-an-instances-placements).
4. **Check it.** `getHealth` lists both; pull-path metrics appear **per placement** (the series carries its placement);
   the ladder reaches `persisted` (or `restart-observed`) once both placements hold the newest generation.

### Known limits

- **Docker config files are written mode 0640** (X1, [`BE-D61`](../design/backend/decisions.md)). The temp file is created
  with that mode and set again before the rename, so a resolved secret is never in a file with an "other" bit. Only the
  **config file** is rewritten on every apply, so only it becomes 0640 on its next write. SM's dot-files
  (`.<file>.sm-preimage-N`, `.sm-preimage-N.absent`, `.sm-fp-N`) are written once and **never rewritten**: those created before
  X1 stay 664 until pruned. One-time remediation, in the declared config directory (the same exact-name pattern as
  [the L5 cleanup](#clean-the-host-after-an-undeclare); read the first listing before running the second):
  ```
  cd -- <config-directory> && find . -maxdepth 1 -type f -name '.<file>.sm-*' \
    | grep -E '^\./\.<file, dots escaped>\.sm-(preimage-[0-9]+(\.absent)?|fp-[0-9]+)$'
  cd -- <config-directory> && find . -maxdepth 1 -type f -name '.<file>.sm-*' \
    | grep -E '^\./\.<file, dots escaped>\.sm-(preimage-[0-9]+(\.absent)?|fp-[0-9]+)$' \
    | while IFS= read -r f; do chmod o-rwx -- "$f" && printf '%s\n' "$f"; done
  ```
  The managed container must be able to read a file owned by the SSH user with no "other" bit: true for a **rootful container
  without userns-remap** (measured on the E2E host), **not** for a container running as a different non-root uid outside the
  file's group. (On the measured host the directory is mode 700 owned by the SSH user, so a remapped or non-root container
  could not traverse it before X1 either.) Refusals:
  - `ssh.config.mode.too.open`: the server left an "other" bit on the temp, or (after the rename) on the target. A refusal
    **before** the rename leaves the old file in place; one **after** it means the target was **already replaced**, so the
    pre-image rollback applies. Fix the host, never loosen the mode.
  - `ssh.config.mode.unsupported`: the host's SFTP server rejects setting the mode (for example `sftp-server -P setstat,fsetstat`);
    allow it, SM will not write secrets without being able to set the mode.
  - `ssh.config.mode.unverified`: the mode could not be read back **after** the rename; the target is already replaced (pre-image
    rollback applies) and its mode must be checked by hand (`stat -c %a <file>`).
- **A placement records who added and who retired it** (`OR-125` (1), `sm_data` V026): `placement.created_by` and
  `placement.retired_by`. A placement added before V026 has no recorded actor, and a placement retired by a sweep or by
  undeclaring its instance records none either (`NULL` means "not attributed", never a guess).
- **`retirePlacement` also writes a placement-scoped `instance_retirement` row** (plan 012 T5: the acting principal, the request's correlation id and the placement's last persisted health), in the same transaction as `placement.retired_by`. Adds are not covered by `instance_retirement`.

## Retire one placement of an instance

| | |
|---|---|
| **Applies to** | an instance with **two or more** live placements (plan 008 S3, [`BE-D53`](../design/backend/decisions.md)) |
| **Severity** | routine |
| **Est. duration** | 5 minutes |
| **Reversible?** | the placement is, by [adding one](#give-an-instance-a-second-placement) with the same container name, directory and ports (they are free again); the retired row itself is kept forever and stays retired |
| **Status** | `UNTESTED` over a real host |

### Why this exists

To stop managing one container of an instance: the customer is removing it, or moving it. Retirement is **SM-side only**.
It never stops, restarts or removes the container, and never writes a host: the request must say so
(`?workload=leave-running` is **required**; anything else is `400` before anything happens), and the response says the
container was left running, now **unmanaged by SM**.

### Preconditions

- It is **not the last live placement.** Retiring the last one is releasing the instance from management (`409`; that is
  undeclare, plan 012).
- **Nothing is in flight that names it**: no `REQUESTED`, `RUNNING` or `APPLY_INDETERMINATE` request on the placement or
  on the whole instance, and no running fleet reserving it (`409`, not naming the request or the fleet).
- **No `PENDING` scheduled apply names it** (`409` with its own detail, naming no schedule; Fable F-3). Cancel the
  placement's pending schedules (`cancelApplySchedule`), then retire it. A schedule on a different placement does not hold it.
- **Nothing of yours is being submitted for it.** `submitApply` and `submitFleetApply` check that their placement is live
  *before* admission, and admission does not check again, so a submit that raced the retirement fails visibly at its
  preflight (a failed request, or a fleet that ends `COMPLETED_WITH_FAILURES`) rather than being refused up front. Nothing
  is applied to the host (plan 008 S3 review, W2A-3).
- You hold `instance.bindTarget` at the instance (`target.use` is not asked: removal touches no target).

### Steps

1. **Remove or repurpose the container by your own means** if you are going to; SM neither does nor waits for it. Do this
   *first* if you want the host to be clean when SM lets go.
2. **Restore its file if SM wrote one you do not want** (the host's original configuration): retirement does not
   touch the file. A retired placement is no longer resolvable by the engine, so a restore through SM must happen
   **before** the retire.
3. **Send `retirePlacement`**: `DELETE /api/dp/instances/{instanceId}/placements/{placementId}?workload=leave-running`.
4. **Read the answer.** `workload: left-running`; the instance now reads without the placement. The row is kept
   (`retired_at` set); an instance-wide stop issued afterwards addresses only the **remaining** live placements, and the
   ladder is re-settled over them (retiring the placement that lagged raises the instance's rung, and
   `instance.config-state` is announced if it moved).

## Undeclare an instance

| | |
|---|---|
| **Applies to** | an instance you no longer want SM to manage: declared by mistake, decommissioned, or moving (plan 012 T5, [`BE-D49`](../design/backend/decisions.md), `OR-121` `OPS-Q2` U-C) |
| **Severity** | routine, but **HIGH** in the permission catalog (`instance.undeclare`: Administrator and Operator) |
| **Est. duration** | 10 minutes, plus the cleanup of the host |
| **Reversible?** | **no.** There is no re-declare of the same instance; ids are never reused. You declare a new instance, with a new id and none of the old history reachable through the API |
| **Status** | Live gate L5 PASSED on the E2E host on 2026-10-09 ([live gate results](../plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09)). The in-process matrix is `UndeclareEndpointIT`; the procedure is [below](#live-gate-l5-undeclare-an-instance-on-the-e2e-host) |

### Why this exists

To take an instance out of SM's inventory and management. **Undeclare never touches the host**: the container keeps running,
unmanaged, with SM's last-written config file and SM's hidden files beside it. The request must say so
(`?workload=leave-running` is **required**; anything else is `400` before anything happens), and the response says
`workload: left-running` and lists each placement with the last health state SM had persisted. SM then polls nothing on the
instance, collects no metrics from it, admits no apply to it, and the organization's quota slot is free again. Nothing in
`sm_data`, `sm_metrics` or `sm_control` is deleted.

### Preconditions

- You hold `instance.undeclare` at the instance.
- **Nothing is in flight on the instance**: no `REQUESTED` or `RUNNING` apply or stop, no unresolved `APPLY_INDETERMINATE`
  request, and no running fleet reserving one of its placements (`409`, naming the kind and never the request). An
  `APPLY_INDETERMINATE` request that the reconciler has given up on **blocks undeclare** until it resolves: there is no API to
  resolve it by hand (`OPS-Q5`, a known gap).
- **Decide about the host first.** If you want the container stopped, call `stopInstance` and wait for it to land **before**
  you undeclare: SM can no longer reach a released placement (`placement.unresolvable`), so it cannot stop, start or restore it
  afterwards. The same goes for putting back the configuration file the container started with: restore it through SM first.

### Steps

1. **Cancel the instance's pending apply schedules first** (`listApplySchedules`, then `cancelApplySchedule` for each `PENDING`
   one): `undeclareInstance` is refused `409` while one exists (`OR-131`). Then **send `undeclareInstance`**: `DELETE /api/dp/instances/{instanceId}?workload=leave-running`, no body.
2. **Read the answer.** `200 InstanceRetirement`. A `503` means this request's own record was older than half the roll-back
   window and the control plane was **not** written: wait until `sm.dp.undeclare.rollback-after` (ten minutes by default) has passed
   since the first attempt, then retry. A retry made after that point rolls the stale record back itself, by running the sweep for
   your organization, and then undeclares on a fresh record; it does not need anyone to list or read instances first (W5 review W5B-4). A `404` means the instance is already released, or never was
   yours: the two look the same.
3. **If the call did not return `200`**, the undeclare may be half done: the data plane has taken the instance out of every read
   (`retiring`) but the control plane still holds its node. **Retry the same call**; an authorized retry resumes and answers
   `200` (the control-plane audit row then names the retrying principal; the data-plane record keeps the original requester).
   If nobody retries, the next `declareInstance`, `listInstances` or `getInstance` of the organization finishes it (when the
   control plane's step was done) or **rolls it back** after `sm.dp.undeclare.rollback-after` (ten minutes by default): the
   instance then simply reappears.
4. **Clean the host, by your own means** ([below](#clean-the-host-after-an-undeclare)). SM does not.

### Clean the host after an undeclare

Undeclare leaves, in the placement's config directory, **SM's hidden files**: `.<file>.sm-preimage-<n>` (and
`.<file>.sm-preimage-<n>.absent`) and `.<file>.sm-fp-<n>`, where `<file>` is the placement's config file (for example
`application.yml`) and `<n>` a generation number. A pre-image is a **plaintext copy of an older configuration, resolved
secrets included**; the live config file holds the same kinds of secrets while the container runs. List them, then remove them,
placement directory by placement directory:

```
ls -la -- <config-directory>
cd -- <config-directory> && find . -maxdepth 1 -type f -name '.<file>.sm-*' \
  | grep -E '^\./\.<file, dots escaped>\.sm-(preimage-[0-9]+(\.absent)?|fp-[0-9]+)$'
cd -- <config-directory> && find . -maxdepth 1 -type f -name '.<file>.sm-*' \
  | grep -E '^\./\.<file, dots escaped>\.sm-(preimage-[0-9]+(\.absent)?|fp-[0-9]+)$' \
  | while IFS= read -r f; do rm -f -- "$f" && printf '%s\n' "$f"; done
ls -la -- <config-directory>
```

For `application.yml` the pattern is `^\./\.application\.yml\.sm-(preimage-[0-9]+(\.absent)?|fp-[0-9]+)$`. It matches **only** SM's
names (digits for `<n>`, nothing after them but the optional `.absent`): a file you made yourself that merely starts with the same
prefix, such as `.application.yml.sm-preimage-3.bak`, is neither listed nor removed. Read what the first `find` lists before running
the second, which deletes (W5 review W5B-7).

Keep a listing from before and after. **Never delete the live `<file>`**, and never use a wider pattern: the directory is the
customer's. If the container is going away too, remove it by your own means (`docker rm`), and the config directory with it.
`run-e2e-undeclare.sh` does exactly this for live gate L5.

### Known limits

- **Role bindings on a released instance stay** but grant and reveal nothing (`OPS-Q4` (a)); metric history stays until partition
  retention and is unreachable through the API.
- A rolled-back undeclare (nobody retried) makes the instance reappear after up to the window; the caller was never told `200`.
- **Pending schedules block undeclare** (`OR-131`): `undeclareInstance` answers `409` while a `PENDING` apply schedule names the
  instance. List them (`listApplySchedules`) and cancel each (`cancelApplySchedule`), then undeclare.
- Push metrics ingest for a `retiring` instance keeps landing until step 5 retires its placements.

## Register a target (SSH host) through the API

### Why this exists

A target is registered through the console (plan 013): `scanHostKeys`, then `registerTarget`, then `testTargetConnection`. The
two standalone commands that used to be the only way, `TargetRegistrationMain` (control plane) and
`TargetConnectionMain` (data plane), **remain for install and emergency use** (a database to seed before any administrator can
sign in, or a registration to repair by hand); they write rows and never connect to the host, and a target registered by them
has no recorded host-key type until its first successful connection (see "Known limits"). `TargetRegistrationMain` needs
`SM_TARGET_ORGANIZATION` since `sm_control` V019 (see "Upgrade across per-organization usernames"). The same four operations
also open SSH connections to the host before any authentication, and those connections can trip the host's sshd per-source
penalties and lock SM's own address out.

### Preconditions

- An administrator signed in: the bootstrap administrator after it has rotated its single-use credential, or an
  organization Administrator, holding `target.create` at the scope the target will be owned at (an environment created with
  `createEnvironment`). Every route answers `428 MUST_ROTATE` until the credential is rotated.
- The SSH private key as an **unencrypted** OpenSSH or PEM file, and the account it signs in as (it must be allowed to run
  `docker`). The key is sent once, over a secure channel (below), sealed by the data plane and never returned.
- The host's SSH host key fingerprint, **read on the host** (below).
- Plain HTTP works only to a loopback launcher; a deployment that terminates TLS in front needs "Behind a TLS-terminating proxy".

### Steps

1. Before the first registration against a host, add SM's address to that host's sshd `PerSourcePenaltyExemptList`
   (host configuration, allowed by `I1`) and reload sshd. The list takes addresses, wildcards and CIDR ranges.
2. **Know the address policy.** The data plane refuses to connect to a loopback, link-local, unspecified or multicast address
   whatever the setting, and, when the launcher sets `sm.dp.targets.allowed-networks` (a list of CIDR blocks; empty means no
   restriction beyond those four), to any address outside the listed networks. A host name is resolved and **every** address
   must pass. The check runs at registration, edit and test (`422 TARGET_ADDRESS_REFUSED`) **and again immediately before every
   later connection** (apply, health, log reads, the `direct-http` poll), because the stored value is a name and DNS can change
   afterwards. A refusal at use is a connection failure classified `target.address.refused`; no connection is made. If a healthy
   target suddenly reports that classifier, its name now resolves somewhere the policy refuses: fix the DNS record or edit the
   target. The built-in list does not refuse carrier-grade NAT (`100.64.0.0/10`) or unique-local IPv6 (`fc00::/7`), because customer
   hosts live there; exclude a metadata service in those ranges with the allow-list.
3. **Confirm the host key; do not trust the scan.** `scanHostKeys` lists every host key the host offers, with its type. It is
   **untrusted input**: it writes nothing and pins nothing, and an attacker between SM and the host would be offered, and
   shown, their own key. On the host run `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` (or the `_ecdsa_` or `_rsa_` file)
   and compare the `SHA256:` value with one the scan offered. Put **that** value in `hostKeyFingerprint`. SM never fills the pin
   in for you: `registerTarget` answers `422 HOST_KEY_NOT_OFFERED` when the host does not offer the key you named.
4. Do not loop on a scan or a registration: a scan is limited to 10 a minute per session, and the registration's own pre-check
   scan, `editTargetConnection`'s re-scan and `testTargetConnection` draw on the same budget (`429 RATE_LIMITED` with
   `Retry-After`).
5. `registerTarget` with the name, the owning environment, the connection (host, port, `sshUser`, the confirmed fingerprint,
   `allowedConfigRoot`, `allowedContainerPrefix`) and the key. The two envelope values are immutable once registered. A host key
   already held by another live target of the organization is refused `409`. `201` is a new target, `200` an identical replay
   (the same record and the same key, compared by the key's public fingerprint).
6. `testTargetConnection`. It runs one fixed command (`docker version`) over the pinned key and the stored key and always
   answers `200`: read which step was not reached (`hostKeyPinned`, `authenticated`, `docker.available`). Then `declareInstance`.

### Known limits

One registration can make up to about 16 pre-authentication disconnects from SM's address within seconds (`scanHostKeys` up to
7, `registerTarget`'s pre-check scan up to 7, and key-family discovery up to 2, which now happens only for a target whose pinned
key's type is not recorded yet; see below). OpenSSH 9.8 and later enables
`PerSourcePenalties` by default (`noauth` 1s, `authfail` 5s, `min` 15s, `max` 10m; `man.openbsd.org/sshd_config`, read
2026-10-08), so these can reach the 15s threshold and the host may refuse SM's address for up to 10 minutes, which breaks
apply, health and logs on that host until the penalty expires. **This is inferred from the documented defaults, not
measured.** `OR-125` (2) is built (`sm_data` V027): the pinned key's type is persisted at registration and at a re-pin, and every
SSH connector negotiates it in one attempt, so the discovery connections remain only for a target registered before V027 or by
the operator command, until its first successful connection records the type. Reusing the pre-check scan's result could remove
one scan.

## Edit, re-pin, rotate the key, test and retire a target

### Why this exists

A host is re-imaged and gets a new address and new host keys (the instances on it keep their placements if you edit the target
rather than register a new one); a key leaks; a target is decommissioned. All of these need `target.edit` at the target's scope
(`testTargetConnection` needs `target.use`), and every change is audited.

### Steps

1. **Edit** (`editTargetConnection`): any of `host`, `port`, `sshUser`, `hostKeyFingerprint`. A change of host, port or pin
   scans the host afresh, applies the address policy, and requires the pin to be one of the keys the scan offers; confirm the new
   host key on the host first, as in the registration steps. It is `409 CONFLICT` while an apply is `REQUESTED`, `RUNNING` or
   `APPLY_INDETERMINATE` on a placement of the target, or a fleet member has not settled; run it again once the work has finished.
   The config root and container prefix cannot be edited (`400`): to change them, retire the target and register a new one (a new
   id, so no placement follows; give an instance a second placement on the new target and retire the old placement).
2. **A key leaked (rotate, and the order matters).** SM stopping use of the old key does not stop anyone else using it; the old key
   dies only when its public half leaves each host's `authorized_keys`, and SM cannot do that (it may not edit files outside the
   placement envelope). On every host that target covers, in this order:
   1. add the **new** public key to `authorized_keys` (keep the old one for now);
   2. call `rotateTargetCredential` with the new private key (over a secure channel). It is not refused while work is in flight,
      because a leaked key must be replaceable at once. The apply path opens a connection per operation, so the next operation uses
      the new key; an operation already holding a connection finishes on the old one;
   3. confirm: `testTargetConnection` reports `authenticated: true`, and `getTargetConnection` shows `credential.publicKeyFingerprint`
      equal to `ssh-keygen -lf new_key.pub`;
   4. only then remove the **old** public key from `authorized_keys` on every host. Doing step 4 before step 2 makes the next apply fail
      authentication, and the engine's failure path handles it as any other failed connection.
   The old secret row stays in the secret store (the runtime role cannot delete one); it is purged only by the owner.
3. **Test** (`testTargetConnection`): safe to repeat, a probe bounded by the same budget as a scan; an unreachable host, a different
   host key, a refused key, a stopped Docker daemon and a user outside the `docker` group are each a result with the later steps
   `false`, never a `5xx`.
4. **Retire** (`retireTarget`): `409` while any live placement names the target; retire those placements first
   (`retirePlacement`, or `undeclareInstance`). The data plane is retired first (the connection is unusable at once and its host keys
   are freed), then the control-plane row; if the call fails between the two, the same call again completes it. Retiring a retired
   target is `200`. The name is free for a new registration, which gets a new id. Nothing on the host is touched.

## Create users and bind them to a scope

### Why this exists

Plan 013 gives the console local users and role bindings (`createUser`, `resetUserCredential`, `deactivateUser`, `listRoles`,
`grantAssignment`, `revokeAssignment`), so an Operator no longer needs SQL. Usernames are unique **per organization**, compared without
regard to case.

### Steps

1. Sign in as the bootstrap administrator (the organization may be omitted when the install holds exactly one; with several,
   an omitted `organization` is `400`). The initial credential is single-use: `rotateOwnCredential` (12 or more characters) first.
2. `createUser` with a username and a provisional `initialCredential` (12 to 1024 characters; over a secure channel). The user is
   `local` and must rotate at its first sign-in. `409` means a user of **this** organization already has the name.
3. `listRoles` for the built-in role ids, then `grantAssignment` with the user, the role and the scope (the deepest id: a
   sub-organization, an environment, a solution or an instance). `role.assign` must be held **at the scope being granted**. The
   **Administrator role is never granted over the API** (`422`): it is for bootstrap and emergency only, so bind an Operator at an
   environment and a Viewer wherever read access is wanted. Removing a binding is `revokeAssignment` (`409` for an Administrator's).
4. A forgotten credential: `resetUserCredential` sets a provisional one, ends the user's sessions and marks it for rotation. A leaver:
   `deactivateUser`, which is permanent (a deactivated user is never reactivated, and cannot be bound). Neither is allowed on the
   caller itself or on an Administrator; a locked-out Administrator is recovered with the operator command
   `SmControlLockoutRecoveryMain` (it needs `SM_CONTROL_LOCKOUT_ORGANIZATION` with the username).

## Behind a TLS-terminating proxy: `INSECURE_TRANSPORT`

### Why this exists

`registerTarget`, `rotateTargetCredential`, `createUser` and `resetUserCredential` carry a private key or a credential. They are
refused `403 INSECURE_TRANSPORT`, before the body is read, unless the connection is secure, the peer is a loopback address, or a
trusted proxy reports `https`. (`setConfigSecret` and `rotateOwnCredential`, shipped earlier, do not apply the rule yet; extending
it is a separate, flagged change.)

### Steps

1. In a deployment that terminates TLS in front of the launcher, list the proxy's exact address in `sm.gateway.trusted-proxies[n]`
   (exact IP literals only, never a hostname or a CIDR range) and make the proxy **overwrite** `X-Forwarded-Proto` with the scheme of its own listener (a proxy that passes a client-sent value through lets that client assert `https` over a plain listener and defeats the rule). Without the entry the
   refusal names the setting. The launcher refuses to start unless exactly one of `sm.gateway.trusted-proxies` (non-empty) or
   `sm.gateway.direct-client-connections=true` is set; the local stack uses the second.
2. Verify with a request through the proxy: `registerTarget` or `createUser` must no longer answer `403 INSECURE_TRANSPORT`.

## Rename an organization (operator command)

### Why this exists

Over HTTP an organization is renamed at install scope only, and the only install-bound principal is the bootstrap
administrator, who can rename its own organization and no other (`OR-125` (3); `OR-131`). Every other organization is
renamed with this command.

### Steps

1. Pick the organization's id (`sm_control.organization.organization_id`). No principal is named: like `EntitlementMain`,
   the command acts on the standing of the runtime credential on the host and its audit row carries **no actor** (`OR-131`
   Q1; `sm_control` V020 admits exactly that, on `organization` from client address `operator-cli`, and nothing else). That gate catches an application path that forgets its actor; it is not authentication: any `sm_cp_runtime` session could set the client-address GUC, and the HTTP application cannot produce `operator-cli` because `ClientAddresses.canonicalize` accepts IP literals only (the same posture as `EntitlementMain`'s V018 exemption).
2. Set the environment (never the command line): `SM_CONTROL_RUNTIME_URL` (a bare `jdbc:postgresql://host:port/db`),
   `SM_CONTROL_RUNTIME_USERNAME` and `SM_CONTROL_RUNTIME_PASSWORD` (the `sm_cp_runtime` credential),
   `SM_CONTROL_RENAME_ORGANIZATION_ID` and `SM_CONTROL_RENAME_ORGANIZATION_NAME` (1 to 100 characters, no control
   character). `SM_CONTROL_RENAME_ORGANIZATION_ACTOR` existed in an earlier build and is no longer read.
3. Run `java -cp <the control plane's classpath> com.solace.sm.cp.persistence.OrganizationRenameMain`. It prints
   `organization renamed: <name>` and exits 0.

### Exit codes and the audit row

`2` a variable is absent or malformed (including a name that breaks the shape rules); `3` the organization does not exist; `4` another organization already has that name
(compared ignoring case, `OR-123`); `1` anything else (the message is a fixed class, never a value). Nothing is written on a
non-zero exit. A rename writes one `change_audit` row: `HIERARCHY_NODE_RENAMED`, client address `operator-cli`, **no actor**
(an HTTP rename carries the caller's address and its authenticated principal instead; the database refuses a no-actor
rename from any other address, or on any other table). Renaming to the name the organization
already has writes nothing.

## Restart without a health gate: restart-all and rolling-no-validation

| | |
|---|---|
| **Applies to** | an apply, a rollback, a restore or a fleet whose `strategy` is `restart-all` or `rolling-no-validation` (plan 012 T2 and T6; [`BE-D50`](../design/backend/decisions.md), [`BE-D51`](../design/backend/decisions.md); `OR-121`, `OPS-Q6` to `OPS-Q8`) |
| **Severity** | **HIGH.** Each restarts the managed integration with no health gate in front of it, so a bad configuration is found after the process is already down. Both need the `HIGH` verb `instance.restart.overrideSafety` |
| **Est. duration** | one restart per placement, plus (for `rolling-no-validation` over a fleet) the settle delay between batches |
| **Reversible?** | the outage is not. A bad configuration is rolled back with `rollbackApply`, which is itself an apply and restarts again under the strategy you give it |
| **Status** | Live gate L1 (one of each on the E2E host's Cache) PASSED on 2026-10-09 ([live gate results](../plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09)). The in-process matrix is `ApplyEndpointsIT`, `RollbackEndpointIT`, `RestoreRuntimeEndpointIT`, `FleetEndpointsIT` and `FleetBatchSafetyIT` |

### Why this exists

`manual` writes the file and restarts nothing; `rolling-health-gated` restarts one placement at a time and waits for the
package's declared health probe before it moves on. The two strategies here have **no gate**: they exist for a package that
declares no probe, and for an operator who has decided the gate is wrong for this change. On Docker over SSH, with one
placement, both do the same thing (write the file, `docker restart`, read the file back and compare the keyed hash); they
differ only in that `restart-all` states a full outage and needs a confirmation. After the restart SM proves that the file
matches and that a container start was observed (`restart-observed`); it does **not** prove the process came back healthy.

**Test a restarting strategy in a development environment first** (`OR-53`). Run the same strategy, against the same package
version and the same kind of change, on a development instance before it is used on a production one, and read
`observeApply`, `getHealth` and the configuration ladder afterwards.

### Preconditions

- You hold, at the instance (or, for a fleet, at every member you want admitted): `config.apply`, `instance.restart` **and**
  `instance.restart.overrideSafety` (Administrator and Operator hold all three; a Viewer none). Without the third verb the
  answer is `403` naming it; on a fleet that member is refused `FORBIDDEN` in `refusedMembers[]`, and the fleet is `403` when
  every member is refused (or runs the authorized ones with `proceedWithAuthorized: true`).
- `restart-all` needs `outageConfirmedBy` in the body, on `submitApply`, `rollbackApply`, `restoreRuntimeConfig` and
  `submitFleetApply` alike. **Any non-blank string will do**: the value is ignored and never stored, and the server records the
  **session principal's id** as the confirmation. A missing or blank value is `400` before anything is written (a refused
  `restart-all` rollback or restore mints no generation).
- The window for the outage is yours to pick: neither strategy waits for a quiet period.

### Steps

1. **One placement.** Stage the generation as usual (`stageConfig`), then `submitApply` with `placementId`, the `generation`
   and `strategy` set to `rolling-no-validation` or `restart-all` (with `outageConfirmedBy` for the second). `202` means
   admitted and durable, not done: follow it with `observeApply`. `APPLIED` is the good end. `CONFIG_DIVERGED` (the read-back
   did not match) is left as it is: **SM does not roll back by itself** (`OR-64`); decide, then `rollbackApply`.
   `APPLY_INDETERMINATE` means SM could not read the file back; the reconciler works on it and `getHealth` tells you what is
   running.
2. **A fleet across several instances** (`submitFleetApply`): keep `batchSize: 1` and `onFailure: halt-remaining` unless you
   mean a larger outage. `restart-all` over a fleet is one outage per member, one batch at a time, from one click; the
   confirmation is recorded on the fleet and on every member's request.
3. **A fleet across the placements of one instance** ([roll a generation](#roll-a-generation-across-an-instances-placements)):
   - For `rolling-no-validation` (and `rolling-health-gated`) a **batch may hold at most one placement of any instance** (rule
     K-2). A fleet whose `members[]`, grouped by `batchSize` (member `k` is in batch `k / batchSize`), put two placements of
     one instance in one batch is refused whole at submit, nothing written: `422 VALIDATION_FAILED`, `member N shares its batch
     with another placement of its instance (fleet.batch.instance.repeated)`, `N` being the first member that repeats an
     instance. The fix is `batchSize: 1`, or one placement of each instance per batch. A member you were refused never counts.
   - `restart-all` is exempt from K-2, because its confirmation is what covers the outage: with `batchSize` 3 over three
     placements of one instance, all three restart at once.
   - **The settle delay.** A `rolling-no-validation` fleet opens the next batch no earlier than
     `sm.dp.fleet.no-validation.settle` (default 30 seconds) after the **last** member of the current batch finished. The clock
     is the persisted terminal time of the member's request (for a member whose request ended `APPLY_INDETERMINATE` and was
     given up on, the time the reconciler gave up), so a restart of the application does not shorten it. The last batch lands
     the fleet without waiting. `rolling-health-gated` waits on its own gate; `restart-all` and `manual` wait for nothing.
4. **Afterwards**, read `getHealth` for each placement and the configuration ladder. For a package with no declared probe the
   ladder stops at `restart-observed` ("restart observed, health not verified"); that is the most SM can say.

### What is refused, and how

| Answer | Cause |
|---|---|
| `403` naming `instance.restart.overrideSafety` | the verb is missing at the instance (single apply); on a fleet, that member is `FORBIDDEN` in `refusedMembers[]` |
| `400` | `restart-all` with no `outageConfirmedBy`; a malformed body |
| `422 fleet.batch.instance.repeated` | K-2, above |
| `422` | `strategy: scheduled`: it is not a strategy; give the window as `schedule` ([below](#schedule-an-apply-or-a-rollback)) |
| `409` | an apply or stop is in flight for the scope, or a placement is reserved by a running fleet |

### Known limits

- **K-2 and the settle delay hold within one fleet only.** A placement is reserved per placement, so another fleet, or a
  single-placement apply, can restart a different placement of the same instance at the same moment. Each needs its own
  authorization; an instance-level guard across actions would be a new decision.
- `sm.dp.fleet.no-validation.settle=0` withdraws the delay (a negative value refuses to start). With `0` and `batchSize: 1` a
  `rolling-no-validation` fleet restarts an instance's placements back to back with no confirmation, which is in effect
  `restart-all` without the confirmation. `0` is a **ruled** configuration (`OR-125` (6), the owner; `BE-D50` rider 2026-10-09), so
  choose it knowingly: it is the operator's decision, not an open question.
- A slow-starting integration with too short a delay is also in effect `restart-all`: SM cannot check the delay it is given.

## Restart SM while an apply to an agent-managed instance is in flight

### Why this exists

An apply that restarts a container through an **agent** (a `host-local` target) outlives an SM restart: the command is durable and the
agent keeps it in its own journal. After SM comes back, the agent reconnects some seconds later (measured: SM answers HTTP about 4 to 5 s
after a kill, the agent's new session opens 6 to 10 s after it). The startup apply-recovery sweep therefore **waits for that agent's
session** before resuming the request, rather than resuming it blind (`OR-146`).

### What you will see

1. SM starts and serves; the request stays `RUNNING` (`observeApply`) while the agent reconnects. Startup is not delayed by the wait.
2. Agent back inside the grace: the request resumes by itself and ends `APPLIED`, the container restarted exactly once (the agent's journal
   re-acks the same command; nothing is re-run).
3. Agent **not** back within `sm.dp.apply.worker.agent-session-grace` (`SM_DP_APPLY_AGENT_SESSION_GRACE`, default `30s`; `0s` turns the wait
   off): the request lands `APPLY_INDETERMINATE`, as it did before the grace existed. The reconciler resolves it from a read once the agent
   is back (`INDETERMINATE_RESOLVED`); until then `getHealth` shows what is running. Do not resubmit: the restart may already have happened.

The wait is **one window shared by every such request** (not one per request): the deferred requests are resumed one after another on a
single background thread, each as soon as its own agent's session is live (checked immediately before its resume) or once the window is
spent. A resume in flight can itself take up to the verb ceiling (a restart: 120 s), so with several agent requests a later one can be
resumed after the window; it still resumes normally if its agent is live.

A request on a **dp-remote** (SSH) target is never made to wait: it resumes during startup, as before. If SM is stopped again while a request
is waiting, it stays `RUNNING` and the next startup's sweep takes it up. On a stop, SM first lets a resume already in flight finish for up
to `sm.dp.apply.worker.shutdown-grace` (30 s; then interrupts it: the request lands `APPLY_INDETERMINATE` or stays `RUNNING`) and only then releases its
single-worker lock, so a replacement process never starts a sweep while this one is still driving the same request.

### The same rule at run time

When an agent's session is lost **after** a restart command was sent (an agent restart, a network break, a drain whose answer is lost), the
request lands `APPLY_INDETERMINATE` and the reconciler resolves it from a read. It does not land `CONFIG_DIVERGED`, which is kept for a
restart that was refused or failed with an answer, or never sent (no live session). A `CONFIG_DIVERGED` request is still not rolled back by SM.

## Schedule an apply or a rollback

| | |
|---|---|
| **Applies to** | `submitApply` and `rollbackApply` with a `schedule` (plan 012 T7; [`BE-D52`](../design/backend/decisions.md); `OR-121` `OPS-Q9` Sch-2, `OR-122` (2), `OR-125` (5), `OR-131` Q2) |
| **Severity** | routine to arm; **HIGH** when it fires, because it runs an ordinary apply under the verbs the strategy needs |
| **Est. duration** | arming is a request; the apply runs inside the window you gave |
| **Reversible?** | while `PENDING`: cancel it. Once `ADMITTED` it is an ordinary apply, and stopping it is `stopInstance` |
| **Status** | `UNTESTED` over a real host (no live gate belongs to scheduling). The in-process matrix is `ApplyScheduleEndpointsIT`, which ran green on `postgres:14` in the combined W3 and W4 full build (2026-10-08) |

### Why this exists

To make a change at a time you will not be at the console for. A schedule is a **record, not an apply**: nothing is admitted,
no fence is held and no generation is minted while it waits. When the window opens, SM admits an ordinary apply.

### Steps

1. **Arm it.** Add `schedule: {"notBefore": "...", "notAfter": "..."}` to `submitApply` or `rollbackApply` (RFC 3339 with an
   offset, for example `2026-10-12T02:00:00Z`; the window is half-open, `[notBefore, notAfter)`). Give `strategy` as for the
   run itself (`manual`, `rolling-health-gated`, `restart-all` or `rolling-no-validation`; the token `scheduled` is `422`).
   The answer is `202` with a `scheduleId` and **no `applyId`**. Both instants must be in the future and no more than
   `sm.dp.apply.schedule.max-lead` ahead (default **7 days, which is also the ceiling**: a larger value refuses to boot). Your
   verbs are checked now, so a missing one is a `403` at submit rather than a refusal at 02:00. A fleet cannot be scheduled.
2. **Watch it.** `listApplySchedules` (`config.view`) lists the instance's schedules, newest first, in every state. `PENDING`
   waits for its window. `ADMITTED` carries the `applyId` of the ordinary apply, which you follow with `observeApply`.
   `MISSED` means the window closed first (SM was down, or the placement's apply fence stayed busy until `notAfter`).
   `REFUSED` carries a closed `reason`: `schedule.authority.revoked` (the submitter lost a verb or was deactivated),
   `schedule.target.gone`, `schedule.generation.superseded`, `schedule.generation.not-staged` or
   `schedule.rollback.target-not-eligible`. `CANCELLED` is final. History is kept and nothing is deleted.
3. **Cancel it.** `cancelApplySchedule` (`config.apply` at the instance; **any holder, not only the submitter**). A schedule
   that is no longer `PENDING` answers `409`; a second cancel is `409` too.
4. **What the firer does at the window.** The gateway's `ScheduleFirer` re-requires the **submitter's** verbs (`config.apply`
   and, as the strategy needs, `instance.restart` and `instance.restart.overrideSafety`), then admits the apply with the
   submitter as its actor. A scheduled rollback is copied forward **at that moment**, above anything staged meanwhile.
   Staging a newer generation does **not** cancel a schedule; at the window the apply is refused (`REFUSED`,
   `schedule.generation.superseded`) if the placement has already moved past it.

### Operating it

- The firer polls every `sm.gateway.schedule.poll-interval` (default 15 seconds) and reads at most `sm.dp.apply.schedule.due-batch`
  (default 100) due schedules per organization per pass. `sm.gateway.schedule.enabled=false` withdraws it: every schedule then
  stays `PENDING` until its window closes and it is `MISSED`.
- Run **one** replica per install, as for everything else. If the application crashes between minting the apply and recording
  it, the next pass reconciles the row (it finds the apply already exists) rather than leaving an applied schedule `PENDING`.
- **A `PENDING` schedule blocks `undeclareInstance`** (`409`): cancel the instance's schedules first
  ([undeclare](#undeclare-an-instance)).
- A schedule does not repeat; a recurring change is a series of schedules.

## Live gate L2: two placements on the E2E host

| | |
|---|---|
| **Applies to** | plan 008 S5 Step 2; the owner's E2E host with a **second Cache container** (`OR-110`: "a second Cache: you can do that if needed") |
| **Severity** | live gate. It **writes** both Caches' configuration files (`SM_E2E_CONFIRM_WRITE=yes`) and, with a second explicit switch (`SM_E2E_CONFIRM_DISRUPT=yes`), **stops and restarts** containers. `OR-110` already authorises both on the E2E host |
| **Est. duration** | 30 minutes, plus deploying the second Cache |
| **Reversible?** | the run restores each file to its original keyed hash by the engine's own restore, and starts again any container it stopped; it fails loudly if it could not |
| **Status** | PASSED on 2026-10-09, on the second attempt (the first found an SSH inspect defect for a container with no Docker healthcheck, fixed in `7ff85a42`; the host re-measure of that fix is owed). See the [live gate results](../plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09) |

### Why this exists

The in-process matrix (`MultiPlacementEndToEndIT`) runs two placements against an in-memory host. It cannot show two real
containers on one host, Docker's start time, a real restart or a stop. This gate does, once, with the same scenario as
the rehearsal (`MultiPlacementRehearsalIT`) so the logic has been executed before it meets the host.

### Preconditions

- The host, user, pinned host-key fingerprint and key file are the walkthrough's own (`run-e2e-walkthrough.sh`). **The host's
  coordinates stay outside the repository.**
- A **second Cache container is running** on the host, deployed by you (SM deploys nothing): a container name that starts with
  the first Cache's name, its own config directory **under** the first one's config root (for example `<root>/second`) with
  its own `application.yml` mounted, and its own host port for container port 8081 (health and metrics are both on it).
- The first Cache is running. `OR-110` already authorises stopping and restarting it and adding a second one on the E2E host, so
  the **disruptive** run needs no further owner permission; `SM_E2E_CONFIRM_DISRUPT=yes` stays an explicit opt-in switch,
  not a permission request. Use it on the E2E host only.
- Docker for the throwaway PostgreSQL, and the local Solace image `solace-pubsub-enterprise:10.25.0.177` loaded (the run's
  mesh events go to a **local** broker, never a remote one).

### Steps

1. **Validate without contacting anything**:
   `SM_E2E_...=... backend/launcher/scripts/run-e2e-multi-placement.sh --check`. It names every missing variable at once
   (never a value) and prints the Maven command.
2. **Run it, read-and-write only**: the same command without `--check`, with `SM_E2E_CONFIRM_WRITE=yes` (exactly that word; as
   `run-e2e-walkthrough.sh` requires, because the run overwrites both Caches' config files). It declares the instance (P1, the
   first Cache), captures each file's original keyed hash, adds P2 on the **same target**, writes both files with a `manual`
   fleet, restores both, and retires P2. **This is a partial pass and does NOT close L2** (see step 3): it skips the stop and
   restart steps and records each skip.
3. **Run it disruptive, which is the run that can close L2**: add `SM_E2E_CONFIRM_DISRUPT=yes` (exactly that word; `OR-110`
   authorises it on the E2E host). It also stops P2 alone, stops the whole instance (a `STOP` step for each live placement,
   each with its own `placementId`), restarts both with a `restart-all` fleet, and restarts both again after the restore so
   they RUN the original configuration. **Only a disruptive pass satisfies L2**, because L2 as planned requires stopping P2,
   the instance-wide stop and restarting both.
4. **Check the end state yourself**: both containers running, both files equal to their originals (the evidence file prints
   each keyed hash before and after), one live placement for the instance. If the evidence says `MANUAL:`, a file or a
   container this run changed was **not** put back: do that now. The run retires P2 (its last step) only when P2's file was put back and every
   container it stopped is running again: once P2 is retired SM can no longer resolve it, so it could no longer start or
   restore it. If those conditions fail, the retire is **skipped and recorded**, and you restore P2 by hand first.
5. **Record it** in plan 008's Results (S5): the evidence file path, the original and final hashes, every step's outcome, and
   every skip with its reason. Until a **disruptive** run has passed against the host, **the L2 claim is not made**; a green
   non-disruptive run, with three steps skipped, is recorded as exactly that.

### What it does not do

It never removes the second container (retiring a placement leaves it running; remove it by your own means), never reads
`~/.sm-local`, and never talks to any broker but its own local one.

## Live gate L5: undeclare an instance on the E2E host

| | |
|---|---|
| **Applies to** | plan 012 T5; the owner's E2E host with a **second Cache container** (`OR-110`) |
| **Severity** | live gate. It **writes** the second Cache's configuration file once (`SM_E2E_CONFIRM_WRITE=yes`), restores it, undeclares an instance and, with `SM_E2E_CONFIRM_CLEANUP=yes`, **deletes SM's hidden files** in that one directory. It stops and restarts nothing and never touches the first Cache |
| **Est. duration** | 20 minutes, plus deploying the second Cache |
| **Reversible?** | the file is restored by the engine's own restore before the undeclare (the run fails loudly if it could not); the hidden files are deleted on purpose |
| **Status** | PASSED on 2026-10-09 (`run-e2e-undeclare.sh`, 7 tests, 0 failures). See the [live gate results](../plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09) |

### Why this exists

The in-process matrix (`UndeclareEndpointIT`) cannot show Docker's own view. This gate does, once: the second container's id and
start time are read over the script's **own** pinned SSH session before and after the run, and must be identical. The run itself
(`UndeclareLiveIT`, with `UndeclareRehearsalIT` for the logic against an in-memory host) asserts the REST and database half.

### Preconditions

- The walkthrough's target (`run-e2e-walkthrough.sh`) and, as for [L2](#live-gate-l2-two-placements-on-the-e2e-host), a **second
  Cache container running** with its own config directory under the first one's root, its own `application.yml` and its own host
  port. **The host's coordinates stay outside the repository.**
- `ssh`, `ssh-keyscan` and `ssh-keygen` on the machine that runs the script (it pins the host key to
  `SM_E2E_SSH_FINGERPRINT` before connecting and refuses any other).

### Steps

1. **Validate without contacting anything**: `SM_E2E_...=... backend/launcher/scripts/run-e2e-undeclare.sh --check`.
2. **Run it** with `SM_E2E_CONFIRM_WRITE=yes`. Without `SM_E2E_CONFIRM_CLEANUP=yes` it stops after the container check and exits
   `3`: **L5 stays open**, with the cleanup commands printed.
3. **Close it**: run the cleanup, by rerunning with `SM_E2E_CONFIRM_CLEANUP=yes` (the whole gate again) or by the commands in
   [the cleanup above](#clean-the-host-after-an-undeclare); keep the before and after listings
   (`L5-host-before.txt`, `L5-host-after.txt`, `L5-host-after-cleanup.txt` in the evidence directory).
4. **Record it** in plan 012 T5's Results: the evidence files, the container's id and start time before and after, and the
   files removed. Until the script has exited `0` against the host, **the L5 claim is not made**.

### What it does not do

It never stops, restarts or removes a container, never reads `~/.sm-local`, and never talks to any broker but its own local one.
