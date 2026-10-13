# Tasks 140–147 — Groups/RBAC ratification: `D18`, `D19`, `FE-D47`…`FE-D50`

**Plan:** [`128-groups-rbac-ratification.md`](../128-groups-rbac-ratification.md) ·
**Decisions:** `D18` 🔒 · `D19` 🔒 · `FE-D47`…`FE-D50`
**Executed:** 2026-09-04 · **Branch:** `dev/frontend` · **Session mode:** Same Session (developer's choice)

---

## What changed

The permission catalog is ratified, and the Groups feature stops borrowing verbs it was never meant to have.

### Decisions taken

| ID | Status | What it settles |
|---|---|---|
| **`D18`** | **LOCKED** (2026-09-04) | The 35-operation `D10` catalog: the 30 the console had shipped ahead of ratification under `FE-D45`, plus `group.view` · `group.create` · `group.edit` · `group.deactivate` · `group.member.manage`. Declines `group.member.view` and `group.role.assign`. States the server-side delegation-safety obligation on membership writes. |
| **`D19`** | **LOCKED** (2026-09-04) | v1 local auth: one Bootstrap Super Admin created directly in the database at install, holding the built-in super-administrator role at the root scope; root grants installer-managed; roles are not user types; creating a user grants nothing; the last active bootstrap admin is undeactivatable; no plaintext credential anywhere. |
| **`FE-D47`** | PROPOSED (implements `D18`) | The Groups screens gate on the ratified verbs; membership is `group.member.manage`; group role assignment is `role.assign`. |
| **`FE-D48`** | PROPOSED (implements `D18`) | The console's v1 group model — one organization, immutable, flat, local-or-IdP, additive allow-only, and the ratified name rules. |
| **`FE-D49`** | PROPOSED (implements `D19`) | Assignments may be composed at **Instance** scope; root/global renders and confers but is never created, and is labelled system-managed. |
| **`FE-D50`** | PROPOSED (implements `D19`) | The Bootstrap Super Admin is represented, never created, by the console. |

`FE-D45` is **marked superseded, not rewritten** — it remains the record of what the console did, and why, while
the catalog was unratified.

### `D10` is untouched (`I3`)

Its historical text stands word-for-word. It gained one appended sentence naming `D18` and `D19`, and its status
cell now reads `LOCKED · catalog leg amended by D18`. Nothing about the model, the two-sided binding rule, or the
deny-by-default check was reworded.

### The amendment proposal came back onto this branch

`docs/design/D10-AMENDMENT-PROPOSAL.md` was not on `dev/frontend` at all — it lives on `main` (`80e9b6d`) and in
the stale agent worktrees, and `FE-D45` linked it as a cross-branch GitHub URL. It is now **restored verbatim**
(`diff` against `git show main:…` is empty below the banner) under a **RATIFIED / SUPERSEDED** banner that maps
each of its five asks to what answered it — §2 → `D18`, §3a → `D19`, §3b partly, §3c and §4 still open.

---

## Files changed

### Documentation

| File | Change |
|---|---|
| `docs/design/DECISIONS.md` | `D18` + `D19` added (index rows in §6 and full entries); `D10` status/pointer; ID-discipline table `D1`–`D19`; ratification matrix; `SM-D31`'s stale *"`D10` has none today"* RBAC note corrected; header date |
| `docs/design/D10-AMENDMENT-PROPOSAL.md` | **restored** from `main` + ratification banner |
| `docs/design/frontend/decisions.md` | `FE-D47`…`FE-D50` entries + index rows; `FE-D45` superseded banner + index status; changelog row |
| `docs/design/frontend/design.md` | the `DM-D18`-verbs-wait-on-`D10` line corrected; the `/admin` phase-7 row annotated |
| `docs/design/common/contracts/console-rest-surface.md` | §13 header de-blocked; §13.1 catalog now 35 and `grantable`; routes 20–27 added to the table; **§13.12–§13.19** (the eight Groups routes) and **§13.20** (the audit obligation) written, all `CONTRACT: TBD`; fixture-fidelity row S6, the `bootstrapFirstAdmin()` absence row and the route map updated |
| `docs/design/README.md`, `CLAUDE.md` | stale `D1`–`D17` family ranges |
| `docs/plans/frontend/128-groups-rbac-ratification.md` | **new** — the plan, with its Review section |

### Frontend

| File | Change |
|---|---|
| `shared/authz/permissions.ts` | five verbs on `GrantableVerb`; **new** `ALL_GRANTABLE_VERBS` |
| `shared/authz/catalog.ts` | five catalog entries (`group.member.manage` high-consequence) + a `Groups` permission group |
| `shared/authz/catalog.test.ts` | five new tests, including the catalog↔union equality |
| `shared/authz/permissions.typetest.ts` | five must-compile lines + **two `@ts-expect-error`** lines for the declined ids |
| `features/admin/groups/groupPermissions.ts` | direct verbs; `memberView` key removed; backend delegation obligation documented |
| `features/admin/groups/groupPermissions.test.ts` | **new** — six tests |
| `features/admin/groups/groupValidation.ts` | 1–128, `&` and `'` admitted, leading/trailing whitespace refused |
| `features/admin/groups/groupsTestData.ts` | `ALL_GROUP_PERMISSIONS` → the ratified six |
| `features/admin/groups/GroupsView.tsx` | header comment only (the gap it described is closed) |
| `features/admin/assignments/ScopeSelector.tsx` | `'instance'` type, `segmentsRequired` → 4, fourth dependent select |
| `features/admin/assignments/AssignmentsView.tsx` | by-scope default `[]`; system-managed label on root rows |
| `features/admin/users/UsersView.tsx` | bootstrap chip; Deactivate disabled-with-reason; backend-responsibility note |
| `shared/testdoubles/data/rbac.ts` | `LocalUser.isBootstrapAdmin`, set on `u-devon` |
| `shared/testdoubles/handlers/rbacStore.ts` | `setUserActive` returns `boolean`; last-active-bootstrap-admin guard |
| `shared/testdoubles/handlers/cp.ts` | `409 CONFLICT` when that guard trips |
| `shared/i18n/messages.en.ts` | eight keys added, three rewritten |
| Tests | `AdminShell`, `GroupsView`, `GroupFormDialog`, `groupValidation`, `groupsIntegration`, `AssignmentsView`, `UsersView`, `AddUserDialog`, `rbacStore` |

---

## Permission catalog and Groups mapping

**Catalog — 35 operations, all `grantable`.** The 30 unchanged, plus:

| Added | High-consequence | Governs |
|---|---|---|
| `group.view` | — | the Groups list, a group's detail, **and its members** |
| `group.create` | — | creating a group |
| `group.edit` | — | name and description |
| `group.deactivate` | — | deactivate / reactivate |
| `group.member.manage` | **yes** | adding and removing members |

**Declined:** `group.member.view` (`group.view` covers it) and `group.role.assign` (`role.assign` covers it — a
group is a principal). Both are `@ts-expect-error` cases in `permissions.typetest.ts`, so re-introducing either at
a call site is a compile error.

**Groups mapping** — `GROUP_PERMISSIONS`, the one place a Groups action meets a verb:

| Action | Verb |
|---|---|
| View groups and members | `group.view` |
| Create group | `group.create` |
| Edit group | `group.edit` |
| Deactivate / reactivate | `group.deactivate` |
| Add / remove members | `group.member.manage` |
| Assign / revoke a group's role | `role.assign` |

---

## Verification

Run from `frontend/`.

| Gate | Command | Result |
|---|---|---|
| Focused — authz | `npx vitest run src/shared/authz/` | **4 files / 21 tests passed** |
| Focused — groups | `npx vitest run src/features/admin/groups/` | **7 files / 80 tests passed** |
| Focused — assignments + access | `npx vitest run src/features/admin/assignments src/features/admin/access` | **7 files / 56 tests passed** |
| Focused — users + fixture store | `npx vitest run src/features/admin/users src/shared/testdoubles` | **8 files / 80 tests passed** |
| Lint | `npm run lint` | clean (`--max-warnings=0`) |
| Typecheck | `npm run typecheck` | clean |
| **Full suite** | `npx vitest run` | **336 files / 2381 tests passed** |

Baseline before any change: **335 files / 2347 tests passed**, lint and typecheck clean. Net **+1 file, +34
tests**, nothing failing, nothing skipped.

---

## Four things worth knowing

### 1. The mapping module was the single point of change it claimed to be

`groupPermissions.ts` had written its own exit condition into its header: *"if the architect group ratifies the
`group.*` verbs, this module is the single place that changes — no call site names a verb directly."* That held
exactly. Six values moved, one key was deleted, and **not one of the twelve `can(GROUP_PERMISSIONS.…)` call
sites** — seven in `GroupsView`, five in `GroupDetails` — needed touching. The discipline of never writing a verb
at a call site paid for itself in one commit.

### 2. The catalog and the union were two lists nobody was comparing

`GrantableVerb` (in `permissions.ts`) and `PERMISSION_CATALOG` (in `catalog.ts`) held the same 30 ids, maintained
by hand, in different files, with **nothing asserting they agreed**. Adding a verb to one and forgetting the other
was silent. `ALL_GRANTABLE_VERBS` closes it in both directions at once: its *type* is `readonly GrantableVerb[]`,
so an id outside the union is a compile error, and `catalog.test.ts` asserts it equals the catalog's ids, so a
catalog id missing from the union is a test failure. That is what *"no bare permission string may bypass
`GrantableVerb`"* needs to mean to be true rather than intended.

It lives in `permissions.ts` rather than `permissions.typetest.ts` for a concrete reason: the typetest file
executes `requireGrantable(someRuntimeId)` against a `declare const`, so importing it at runtime throws
`ReferenceError`. That was discovered by trying it.

### 3. Two latent defects fell out of §4 of the brief, and both were real

Asking that root assignments *"remain inspectable in by-scope views"* exposed `AssignmentsView`'s
`selectedScope ?? [scopeTree.name]` — the synthetic root's **label** used as a path **segment**. `FE-D46` had moved
the root's path to `[]`, and `ScopeTree` emits `[]` on selection, but this one default was never updated. The
by-scope view therefore opened on a scope that does not exist. Fixed to `[]`, with a test that the installer's
root grant now shows as a *direct* grant there and the two narrower grants correctly do not reach up to it.

Asking for an instance-scope option exposed that `ScopeLevel`, `scopeLevelOf` and `SCOPE_TREE` had all carried the
instance level since `FE-D46`; only `ScopeSelector` omitted it. The level was already in the data — the composer
just could not reach it. Adding it was one union member, one `switch` arm and one dependent `Select`.

### 4. The two group-name rules are not contradictory, and the order matters

The brief asks for names *"compared case-insensitively after trim"* **and** *"invalid when they contain
leading/trailing whitespace"*. Those are different rules doing different jobs. **Trimming** is what the
*uniqueness* comparison does, so `"  Ledger "` cannot slip past an existing `"Ledger"` — and it trims the *stored*
name too, which now has its own test. **Rejection** is what *validity* does, so the operator is told the rule
rather than silently getting a name in the audit line that is not the one they typed.

Order is part of the behaviour: empty → whitespace → length → characters → uniqueness. A padded duplicate reports
the whitespace rule, not the duplicate rule, and there is a test pinning that so a later reorder is visible.

---

## Backend and install-time dependencies — deliberately NOT implemented

| # | Dependency | Why it is not here |
|---|---|---|
| B1 | **Deny-by-default enforcement of the 35 verbs** | `REQ-006`'s *"no endpoint is reachable without it"* is a server property. `D18` ratifies the policy; nothing enforces it — there is no backend. |
| B2 | **Delegation safety on membership writes** | `D18` requires the server to refuse a membership change through which the actor would confer a role or scope it may not delegate. Holding `group.member.manage` is necessary, not sufficient. A client-side check is a race, not a rule. |
| B3 | **Append-only audit persistence** | Written into the contract as §13.20: actor · action · target · tenant · organization/scope · timestamp · before/after with secrets redacted · **`correlationId` as a join key, not the event id**. No client-side audit record is written, and none should be. |
| B4 | **Password hashing and forced first-login replacement** | Install-time. The console never sees, holds or displays a credential, and now says so on screen. |
| B5 | **Session / token invalidation** on deactivate, revoke or membership removal | Without it a revoked grant survives in a live session until expiry. |
| B6 | **Production database migrations** — groups, memberships, assignment `status`, the bootstrap-admin flag | No backend code and no `contracts/` directory exist. |
| B7 | **OpenAPI for `/admin/*`** | `FE-D7` wants generated types; §13 is still hand-written prose. |
| B8 | **Pagination protocol** for groups, members and assignments | The console renders whole collections against fixtures. Inventing one would assert an unratified contract. |
| B9 | **IdP synchronization** (`D13` 🔒, deferred) | `source: 'idp'` renders read-only; nothing synchronizes anything. |
| B10 | **Introspection scope** — who may read *whose* effective permissions | The amendment proposal's §4. `D18` explicitly does not decide it. |
| B11 | **Credential policy** — length, rotation, lockout | The amendment proposal's §3b, only partly answered by `D19`. |

---

## Ambiguities and conflicts found

### 1. Plan number 128, not 133

The brief suggested `133-groups-rbac-ratification.md` "or the next available number". The next available **plan**
number is **128** — `docs/plans/frontend/` holds `118`, `126`, `127`. `133` is a **task** number, already used:
`reports/task-133-139-report.md` is plan `127`'s report. Using it would have made plan and report numbering
collide. Tasks here are numbered `140`–`147`, continuing from `139`.

### 2. The built-in super-admin role's name

`D19` names the role **Super Administrator**. The console's built-in role is `RBAC_ROLES` id **`super`**, displayed
**`Solace Super-admin`** — the `FE-D30` reference's own string, and the name in the REST contract's §13.2 example.
**Not renamed.** Renaming would diverge from the reference for no behavioural gain; `D19` names the id and records
both spellings instead. Flagging it in case the architect group wants the display string changed.

### 3. Whether deactivating an **IdP** group is also provider-owned

`GroupsView` already gates Edit and Manage members on `group.source === 'local'`. **Deactivate is not so gated.**
The brief says *"IdP group identity and membership are read-only"*, which arguably covers a status change but does
not say so. **Left as it is**, and recorded as an open question in `FE-D48` rather than resolved silently.

### 4. `frontend/scripts/dummy-api-server.mjs` is not on this branch

The brief asked for it to be inspected. It exists only on `feat/local-dummy-api` (`scripts/dummy-api-server.mjs`),
not on `dev/frontend`, so **nothing was changed there**. When that branch merges, its identity seeding will need
the same treatment `devMockAdapter` and the `cp` handlers got — except those two needed none, because both derive
their permission list from `PERMISSION_CATALOG.map(p => p.id)` and so picked up the five new verbs for free.

### 5. The amendment proposal's internal references do not resolve on this branch

Its §1 census (*"2 grantable, 6 proposed, 22 absent"*) predates `FE-D45`, and its "open item 23 / 24" references
are a different branch's numbering — `dev/frontend` renumbered to 1–20. **Both left exactly as written**, with the
banner explaining them. Editing an approved paper to match a later world destroys the record of what the group was
actually asked.

### 6. `platform-sre` needed no re-scoping

The brief anticipated a conflict if that fixture were a global group. It is not — `LOCAL_GROUPS` already scopes it
to `Trading`, so the "no global groups" rule required no fixture surgery.

---

## What this deliberately did not do

- **No backend anything.** B1–B11 are recorded and untouched.
- **No `bootstrapFirstAdmin()`.** Its absence from `AdminRepository` is now permanent rather than provisional, and
  the REST contract's absences table says why.
- **No client-side audit record.** The obligation is written into the contract (§13.20) as a server obligation.
- **No unrelated refactors.** Everything touched is named in the Files changed table above.
