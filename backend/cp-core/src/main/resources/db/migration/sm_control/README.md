# `sm_control` migrations are frozen (`OR-22`)

`CHECKSUMS.sha256` pins the SHA-256 of every migration file in this directory (`V001` through the
current head). `MigrationChecksumsTest` (in `cp-core`'s test tree) fails the build if:

- any pinned file's bytes change,
- any pinned file disappears, or
- any migration file exists here that is not pinned.

## Why

`V001__scope.sql` was edited after it had already been pushed and applied, silently changing its
Flyway checksum. Because `sm_control` has only ever existed on `integration/dp-remote-demo`, the
owner ruled (2026-09-23, adopting the external review's recommendation) that:

- **every `sm_control` database created before this ruling must be dropped and recreated** — there
  is no migration path from a pre-ruling database to a post-ruling one;
- **`flyway repair` is not an accepted remedy**, because it accepts drift instead of preventing it;
- **from this ruling on, every applied migration is immutable.**

See `docs/design/DECISIONS.md`, row `OR-22`, for the full ruling and its provenance.

## What this means going forward

**A correction is a new migration file, never an edit to one that already exists here.** If `V011`
turns out to be wrong, the fix is `V012`, not a changed `V011` — even before `V012` exists, editing
an already-pinned file is exactly the drift this ruling exists to prevent, and `CHECKSUMS.sha256`
will fail the build the moment it happens.

To add a new migration: write `Vnnn__description.sql`, then add its SHA-256 to `CHECKSUMS.sha256`
(`shasum -a 256 Vnnn__description.sql`, in the same `<hash>  <filename>` format `sha256sum`/`shasum
-c` accept) so `MigrationChecksumsTest` picks it up.

## `V017`'s terminal retired rows (plan 012 T4, `OR-121`)

`V017` adds the trigger `instance_retired_is_terminal`: once `sm_control.instance.retired_at` is set, no column of the row
can change, even for the owner. **Future migrations that `UPDATE sm_control.instance` must exclude retired rows
(`retired_at IS NOT NULL`) or the terminal trigger aborts them (alternatively disable `instance_retired_is_terminal` inside that migration's own transaction and re-enable it before commit; a bulk `UPDATE` that does neither fails on the first retired row, rolls back, and leaves the migration unapplied); a restore (open item 39 (i)) needs a migration plus a
readiness change.** This is deliberate, not an oversight: re-declare is out of scope, so "undeclare is permanent" is
enforced by the database.

## `V011`'s `change_audit` (`OR-19`)

- **Append-only and immutable forever, as long as the triggers stand.** `change_audit` rows are
  never updated, deleted, or truncated — `V011`'s three `change_audit_immutable_*` triggers refuse
  all three attempts, for every role including the table owner, because a trigger fires regardless
  of privilege (`GRANT`/`REVOKE` alone would not have stopped the owner from writing directly). This
  is **not** the same as saying the owner cannot touch the mechanism: the owner holds DDL rights over
  the table and so CAN `DISABLE`/`DROP` these triggers (or the 13 `truncate_refused` triggers on the
  audited business tables). `ControlPlanePersistenceConfiguration`'s readiness check detects that —
  at the next application start, not continuously — by requiring all of them present and enabled;
  it cannot catch a trigger disabled or dropped while the application is already running. **Retention
  and partitioning are an open decision** — nothing here ages rows out; that is deliberately left
  unaddressed rather than guessed at.
- **A principal that appears as an audit actor can never be deleted.** `change_audit
  .actor_principal_id` is a foreign key to `sm_control.principal` with `ON DELETE RESTRICT`: the
  moment a principal is named as the actor on even one audit row, deleting that principal row
  outright is no longer possible. (Deactivation — `sm_control.principal.status` — remains available;
  it is not a delete.)
- **Rows seeded before `V011` have no audit rows.** `V003__role_and_binding.sql` and
  `V007__bootstrap_admin.sql` insert rows before the audit trigger exists, so those specific
  seeded rows carry no corresponding `change_audit` entry. Every write from `V011` onward is
  captured, seeded rows or not.
- **`audit_log` (`V005`) is a DIFFERENT, older mechanism and is out of `OR-19`'s trigger scope by
  ruling.** It predates `change_audit` and is not superseded, extended, or touched by `V011` — the
  two audit mechanisms coexist, and nothing here migrates `audit_log`'s rows or responsibilities
  into `change_audit`.

## `V012` — `OR-23`, `OR-24`, `OR-25` (the auth corrective wave)

Three owner rulings (2026-09-23), closing the external review's findings on top of `OR-19`/`OR-20`.
`V012` makes two schema-level changes — everything else is application code
(`LocalLoginService`, `CredentialRotationService`, `SmControlLockoutRecoveryMain`).

- **`OR-23` — the USERNAME throttle dimension is an alarm, not a gate.** A login whose credential
  verifies, from a client address that is not locked, now succeeds even while the username is
  locked, and clears the username counter. The username counter still exists, still locks, and
  every failure against it is still audited (`LOGIN_REFUSED`) — it is simply never READ by
  `authenticate()` any more, so it can no longer by itself refuse a correct credential. This needed
  no schema change: `login_throttle`'s shape is unchanged by `OR-23`.
- **`OR-24` — `refusal_recorded_for timestamptz`, a new column on `login_throttle`.** A refusal
  against a locked ADDRESS (login) or a locked ROTATION subject performs no Argon2 verification and
  is audited **at most once per lockout**, not once per request. `refusal_recorded_for` records
  which lockout (identified by its own `locked_until` value) has already had a refusal audited;
  `LoginThrottleRepository#probe`'s `UPDATE` only actually changes the row — and so only actually
  fires the `OR-19` trigger — the first time it runs for a given lockout. The USERNAME dimension is
  never probed at all (`OR-23` made it an alarm), so this column is always `NULL` on a
  username-keyed row.
- **`OR-25` — `sm_control.record_change_audit()` fails closed for `sm_cp_runtime`.** `CREATE OR
  REPLACE FUNCTION` in place (the trigger, not the tables, changes) — the previous mixed policy, in
  which a malformed correlation id was silently nulled while an unknown `audit_operation` aborted
  the write via the generic `change_audit` `CHECK` constraint, is replaced by one deliberate rule,
  scoped to `session_user = 'sm_cp_runtime'` only: a known operation, a well-formed correlation id
  and a client address are all required, and the actor may be absent only for `LOGIN_REFUSED` and
  `LOCKOUT_CLEARED` — **and even then, only on `login_throttle`** (m3): neither production path that
  uses either operation (`LocalLoginService`'s refusal recording, `SmControlLockoutRecoveryMain`'s
  clearing) ever writes any other audited table, so a `sm_cp_runtime` write to a DIFFERENT table
  under `LOGIN_REFUSED`/`LOCKOUT_CLEARED` with no actor is refused exactly like every other
  operation. The refusal carries a distinct SQLSTATE (`OR025`) and an `OR-25`-prefixed message.
  `sm_cp_migrator` / `sm_cp_owner` writes (including phase-0's superuser) are completely unaffected
  — they may still carry no context at all, exactly as before `V012`. The session idle-touch
  exclusion still returns before any context check, unchanged.
- **`sm_cp_runtime`'s address dimension is keyed by `ClientAddresses.throttleKey`, not the bare
  address (M1).** `LocalLoginService.addressSubjectHash` — the login throttle's own client-address
  subject-hash — now hashes the `/64` containing an IPv6 address rather than the address itself (an
  IPv4 address is unaffected), matching the pre-auth rate gate's own `/64` keying (`OR-24`).
  Otherwise, an attacker who controls a whole `/64` (routine for IPv6) could rotate the low 64 bits
  on every attempt and never trip the durable per-address lockout at all.
  `SmControlLockoutRecoveryMain`'s client-address selector goes through the SAME
  `addressSubjectHash`, so clearing by any address within a locked `/64` clears the one lockout that
  `/64` shares. This needed no `V012` schema change — `login_throttle.subject_hash` already stores
  an opaque hash, and what changes is only what goes into it.
- **Recovery (external finding 4, application code only — no `V012` schema change):**
  `SmControlLockoutRecoveryMain` now clears only an ACTIVE lockout (`locked_until > now()`); a row
  that exists but is not locked, or no row at all, clears nothing, audits nothing, and exits
  `EXIT_NO_ACTIVE_LOCKOUT` (`3`) rather than `0`.

See `docs/design/DECISIONS.md`, rows `OR-23`–`OR-25`, for the full rulings.

## Publishing and freezing (`OR-22`, anchor mode)

`CHECKSUMS.sha256` plus `MigrationChecksumsTest` catch drift **within a single commit** — but a
commit that rewrites a migration and its manifest line together, then gets pushed, passes that
test on its own terms. `backend/scripts/check-frozen-migrations.py` — wired into
`contracts/run-checks.sh` step 12, the opt-in `.githooks/pre-push` hook, and
`.github/workflows/migration-freeze.yml` — closes that gap two ways:

- **Anchor mode (always runs).** `backend/scripts/frozen-migration-anchors.txt` lists full 40-hex
  commit SHAs, one per line (`#` comments allowed). A commit SHA is content-addressed, so every
  migration file and `CHECKSUMS.sha256` line present at an anchor is frozen forever, regardless of
  what any branch later points to — this is what survives a rewritten branch tip or a moving
  `origin/main`/`origin/<branch>` ref. **The file is append-only: never edit or remove a line once
  it is committed.** The first anchor, `245d763` (V001–V012), is also built into the checker as
  its genesis anchor, so deleting or emptying the anchors file cannot unfreeze those migrations.
- **Base mode (`--base <commit>`).** Compares the commit being checked against a prior commit — in
  CI and the pre-push hook, the merge-base with what was last published — so a proposed change is
  checked against its own pre-change state even before it is pushed, and before it becomes an
  anchor. The workflow runs the **base commit's** copy of the checker, so a change cannot weaken the
  checker and rewrite a migration in the same step. The one exception is the first run after this
  change, when the base's copy predates anchors; the workflow warns and uses the head's copy.

**Scope (`OR-105 (e)`).** The anchors and both modes cover all three migration directories: this one, and the data
plane's `sm_data` and `sm_metrics` under `backend/dp-core/src/main/resources/db/migration/`. Each has its own
`CHECKSUMS.sha256` (same format) and is verified at build time by `DataPlaneMigrationChecksumsTest` in `dp-core`. A new
`sm_data`/`sm_metrics` migration needs its manifest line the same way a new `sm_control` one does.

**To freeze a newly-published migration:** once a migration (and its `CHECKSUMS.sha256` line) has
actually reached `origin`, append that publishing commit's full SHA to
`frozen-migration-anchors.txt` in a **follow-up** commit — a commit cannot anchor itself, since the
anchors file would need to already contain a SHA that doesn't exist yet. Until that follow-up
lands, the migration is still covered by base-mode comparisons (and by `CHECKSUMS.sha256` within
any single commit), just not yet by an immutable anchor; `check-frozen-migrations.py` prints a NOTE
(not a failure) listing any migration in the tree that no anchor yet covers.

**What this cannot do, in this repo today:** the GitHub repo is private on a free plan, so branch
protection / rulesets 403 and nothing server-side can BLOCK a push or a merge.
`.github/workflows/migration-freeze.yml` can only report a failing status after the fact,
`.githooks/pre-push` is local, opt-in (`git config core.hooksPath .githooks`) and bypassable with
`git push --no-verify`, and appending a new anchor is a manual step someone has to remember to do.

## `V018`'s grants and the no-actor `ENTITLEMENT_SET` (plan 013 T2.1, `OR-121`)

`V018` gives the runtime role exactly what the registration API writes with, and nothing beside it:

- `INSERT` on `environment`, and `INSERT (organization_id, organization_unit_id, name)` on `organization_unit` - **not**
  `parent_unit_id`, so a sub-organization is top-level only (`SM-D32`, `OR-16`) by privilege and not only by code;
  `UPDATE (name)` on `organization`, `organization_unit` and `environment`. No `INSERT` on `organization` (`OQ-5` (a)),
  no `DELETE` on any node.
- `INSERT` and `UPDATE (status, deactivated_at)` on `principal`, and `INSERT, DELETE` on `role_binding` (plan 013 W3,
  `OQ-6` (a)). `role_binding` is the only table the runtime role may `DELETE` from; `principal` is never deleted.
- The sibling-name rule is **not** in this migration: it is `V015`'s three case-insensitive unique indexes (`OR-123`),
  which the registration API relies on and maps a `23505` on to the named conflict.

`record_change_audit()` is replaced once: `V017`'s body plus seven operation names (`HIERARCHY_NODE_CREATED`,
`HIERARCHY_NODE_RENAMED`, `PRINCIPAL_CREATED`, `PRINCIPAL_DEACTIVATED`, `PRINCIPAL_CREDENTIAL_RESET`,
`ROLE_BINDING_GRANTED`, `ROLE_BINDING_REVOKED`). **A no-actor `ENTITLEMENT_SET` is now accepted only from the operator
command's client address, `operator-cli`** (it was: on the `entitlement` table from anywhere): over HTTP the entitlement
is set by an authenticated principal. `target_name_once` is a partial unique index on live targets, so a retired target
frees its name (`OQ-11` (b)); the index keeps the constraint's old name so a `23505` still reports the same string.

## `V021`, `V024`, `V025`: Connections (integration plans 013/014, `OR-130`, `BE-D66`)

`V021` adds the four `connection.*` verbs as catalog rows 44-47 (Administrator all four, Operator and Viewer `connection.view`)
and the `sm_control.connection` row, and replaces `record_change_audit()` with **`V018`'s** body plus the four `CONNECTION_*`
operations and the `connection` row key; `V024` and `V025` re-apply those two edits to a body that lacked them. ⚠️ Because `V021`
started from `V018`'s body it dropped `V020`'s operator-cli `HIERARCHY_NODE_RENAMED` gate; `V026` below restores it.

## The reconciliation into `UAT` (`OR-147`, 2026-10-12)

Plan 015's two migrations were written as `V021` and `V022`; `UAT` had already used `V021` (above) and the old agent's `V022`/`V023`.
The reconciliation removed the old agent's two and renumbered plan 015's to **`V026`** and **`V027`**, after `V025`. Both were
edited once for the reconciled chain (no database that matters ran either chain, `OR-147` (2)): the audit body they install keeps the
Connections edits (`CONNECTION_*`, the `connection` row key), the `audit_operation` CHECK names them, and the agent verbs are catalog
rows **48-51** (the catalog is **51**: Administrator 51, Operator 27, Viewer 10). The sections below keep plan 015's text, renumbered.

## `V026`'s agent registration, the agent as an audit actor, and catalog rows 48-51 (plan 015 T1.3, written `V021` / rows 44-47; `OR-126` A8)

`V026` is the control plane's half of the agent path (`BE-D70`, `BE-D71`, `AGW-D24`), and nothing in it is a reason to edit an
earlier file:

- `agent_registration` - scoped (`organization_id`, `organization_unit_id`, `environment_id`, the catalog solution and one loaded
  package version) and carrying the **target envelope** (`allowed_config_root`, `allowed_container_prefix`) the enrolment creates
  its target with. `agent_id` and the unsigned-SHA-256 token digest are each globally unique; revocation is sticky (a trigger),
  and `UPDATE` is column-scoped to the two later writers (the agent's consumption, the principal's revocation). The runtime role
  holds no `DELETE`. The token digest is a secret column: an audit row never copies it.
- `change_audit.actor_agent_id`, at most one kind of actor per row. The `audit_operation` CHECK and the OR-25 allow-list gain
  `AGENT_ENROLMENT_ISSUED` and `AGENT_REVOKED` (a **principal's** acts) and `AGENT_ENROLLED` and `AGENT_INSTANCE_REGISTERED` (the
  **agent's**: exactly an agent actor and no principal, on named tables only, for an active registration of the row's own
  organization; the second also needs the registration consumed). Any other operation carrying an agent actor is refused `OR025`,
  and the correlation id and client address stay mandatory. `AGENT_LEASE_ISSUED` is **not** added: T2.2 Step 0 has not found a
  lease-time `sm_control` write.
- `record_change_audit()` is rebuilt whole from **`V020`'s body** (`OR-131` Q1's no-actor `HIERARCHY_NODE_RENAMED` on `organization`
  from `operator-cli` is kept verbatim) plus seven marked edits. The readiness stage for `V020` still passes against this body and
  `V026` adds its own.
- `sm_control.target.target_transport` admits `agent` beside `ssh-docker` (the CP target row written at enrolment).
- Catalog rows 48-51 (`agent.enrol`, `agent.revoke`, `agent.drain`, `agent.view`): Administrator all four, Operator `agent.view` and
  `agent.drain`, Viewer `agent.view` - **51 / 27 / 10** with the `connection.*` rows. Authority: `D10-G2-APPROVAL-2026-09-15.md` section 1.11.1.

## `V027`'s system actor and the scheduled enrolment sweep (plan 015 T4.6, written `V022`; `OR-140`)

`V027` is the control plane's half of `OR-140`: the agent-enrolment sweep leaves `listTargets` and runs as a scheduled job, and its two
control-plane writes are audited to a **system actor** rather than to the principal who issued the token (who did not act) or to a
requester (there is none). Nothing in it edits an earlier file:

- `system_actor` - a registry of named non-human actors, one seeded row (`agent-jobs`, `00000000-0000-4000-8000-000000000a01`,
  `SystemActors.AGENT_JOBS`). The runtime role has **no grant** on it: an actor is seeded by migration, never minted by the application.
- `change_audit.actor_system_id` (foreign key to the registry), and `change_audit_actor_one_kind` widened to **at most one of three** kinds of
  actor (`num_nonnulls(actor_principal_id, actor_agent_id, actor_system_id) <= 1`).
- `record_change_audit()` is rebuilt whole from **`V026`'s body** plus four marked edits (`SYS-EDIT 1..4`). **The `OR-25` amendment:** a write
  audited to the system actor (`sm.audit.actor_system`) is allowed only for `AGENT_REVOKED` (an `agent_registration` going `active` ->
  `revoked`) and `TARGET_RETIRED` (an **agent-transport** `target` going live -> retired), from the client address `system-job`, with no
  principal and no agent actor, and for a registered system actor. Any other operation, table, kind of change or client address carrying a
  system actor is refused `OR025`. **Every other operation still fails closed without a principal**, and `AGENT_REVOKED`/`TARGET_RETIRED` with
  no actor at all are refused exactly as before. The readiness stage for `V027` requires the new markers; the `V020` and `V026` stages still
  pass against this body.
- No new `audit_operation`: the sweep's revoke and retire are `AGENT_REVOKED` and `TARGET_RETIRED`, so a reader sees the same vocabulary as an
  operator's revoke, with a different kind of actor.

There is no DDL replay for `sm_control` (`docs/design/backend/evidence/ddl-replay` covers the `sm_data` apply tables only), so none is re-recorded.
