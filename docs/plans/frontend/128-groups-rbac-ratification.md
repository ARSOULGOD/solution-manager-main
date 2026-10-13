# 128 — Groups/RBAC ratification: `D18`, `D19`, and the console changes they unblock

**Component:** frontend (+ cross-cutting decision register) · **Phase:** 6 (follow-on) · **Tasks:** `140`–`147`
**Written:** 2026-09-04 · **Status:** COMPLETE (2026-09-04)
**Decisions of record:** [`D18`](../../design/DECISIONS.md) 🔒 · [`D19`](../../design/DECISIONS.md) 🔒 ·
[`FE-D47`…`FE-D50`](../../design/frontend/decisions.md)
**Reference:** [`docs/design/frontend/reference/`](../../design/frontend/reference/README.md) (`FE-D30`)

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans` (this plan was executed inline in
> the authoring session — see the Review section). Steps use checkbox (`- [ ]`) syntax.

**Goal:** ratify `D10`'s permission catalog and the v1 local-auth bootstrap model as new `LOCKED` parent
decisions, then make the console stop mapping Groups actions onto borrowed `user.*` verbs.

**Architecture:** the register gets two new immutable IDs (`D18`, `D19`) rather than an in-place edit of
`D10` (`I3`). The frontend then removes the one deliberate compromise the Groups feature shipped with — a
`groupPermissions.ts` that mapped `group.view` onto `user.view` because no `group.*` verb existed — and adds
the two capabilities the ratified model implies: instance-scoped assignments, and a Bootstrap Super Admin
that the UI cannot deactivate.

**Tech Stack:** React 19 · TypeScript strict · MUI · TanStack Query · MSW fixtures · Vitest.

**Spec:** the architect/product owner's approved Groups/RBAC ratification brief, recorded verbatim in the
decisions this plan writes — [`D18`](../../design/DECISIONS.md), [`D19`](../../design/DECISIONS.md),
[`FE-D47`…`FE-D50`](../../design/frontend/decisions.md).

---

## Global Constraints

Copied from the brief. Every task's requirements implicitly include these.

- **`I3` — IDs are immutable.** `D10` is never edited to mean something else. `D18` supersedes its catalog
  leg; `D10`'s historical text stays exactly as written and gains only a pointer.
- **No bare permission string may bypass `GrantableVerb`.** A `group.*` id introduced as a string literal
  must be a compile error.
- **The frontend is a cosmetic overlay, never a security boundary** (`FE-D9`, design §15). Every gate added
  here changes what an operator *sees*.
- **Do not invent backend behaviour.** Backend enforcement, audit persistence, OpenAPI, pagination protocol,
  token invalidation and production migrations are recorded as dependencies, not implemented.
- **v1 group model, exactly:** one Group ↔ one Organization; one local User ↔ one Organization; members only
  from the owning Organization; no global/cross-organization groups; no tenant-wide groups; no nested
  groups; no organization-membership join table; no primary-organization abstraction; no opaque-ID
  migration for Groups. Organization is **immutable after creation**.
- **Group names:** unique per Organization · compared case-insensitively after trim · 1–128 characters ·
  Unicode letters/numbers/spaces plus `(` `)` `.` `_` `-` `&` `'` · invalid when they contain control
  characters, leading/trailing whitespace, `/`, `\`, or `:`.
- **Effective permissions are additive allow-only** — direct ∪ group ∪ inherited, deduplicated. No deny
  semantics. A deactivated group confers nothing.
- **Assignment lifecycle:** `status: 'active' | 'revoked'`; removal revokes, never deletes; `assignedBy` /
  `assignedAt` preserved.
- **Bootstrap credentials must never appear in plaintext** in source, migrations, fixtures, logs, API
  responses or frontend state.

---

## Context

Three facts, measured on `dev/frontend` at `78693b3` before any change.

### 1. The Groups feature shipped on borrowed verbs, and said so

[`groupPermissions.ts`](../../../frontend/src/features/admin/groups/groupPermissions.ts) opens with
*"⚠️ There are no `group.*` verbs in the catalog, and this file does not invent any."* It maps `group.view`
→ `user.view`, `group.create`/`group.edit` → `user.create`, `group.deactivate` → `user.deactivate`, and —
deliberately the stricter choice — `group.member.manage` → `role.assign`. The file names the ratification as
its one unresolved dependency and states that it is the single place that changes when the verbs land.

This plan is that change. The mapping module survives; only its values move.

### 2. `D18` and `D19` are free

`grep -nE '(^|[^A-Za-z/-])D1[89]([^0-9]|$)'` across every `.md`, `.ts`, `.tsx` and `.mjs` in the tree returns
**no matches**. `SM-D18` and `SM-D19` exist and are a *different family* (`I3` — "check the prefix"). The
parent family currently ends at `D17`.

### 3. Two defects the brief's §4 exposes

- **The by-scope assignments view cannot inspect a root assignment.** `AssignmentsView` defaults to
  `selectedScope ?? [scopeTree.name]` — the literal string `'All organizations'` used as a path *segment*.
  Under `FE-D46` the root's path is `[]`, which is what `ScopeTree` itself emits on selection. So the
  default view asks "who reaches the scope named *All organizations*?", a scope that does not exist.
- **The assignment dialog cannot express an instance scope.** `SelectableScopeType` is
  `'organization' | 'subOrganization' | 'environment'`. `scopeLevelOf` and `SCOPE_TREE` already carry the
  instance level; only the composer omits it.

### 4. The `D10` amendment proposal is not on this branch

`docs/design/D10-AMENDMENT-PROPOSAL.md` exists on `main` (`80e9b6d`) and in the stale agent worktrees. It was
never carried onto `dev/frontend`, whose docs have since diverged (open items renumbered 1–20; the proposal
cites 23 and 24). `FE-D45` links it as a GitHub URL on `main`. The brief says *preserve it, mark it
ratified/superseded, never delete it* — so it is **restored verbatim** onto this branch with a status banner,
rather than left as a dangling cross-branch reference.

---

## Baseline (measured 2026-09-04, before any change)

`npx vitest run` → **335 files / 2347 tests passed**. `npm run typecheck` clean. `npm run lint` clean.
Working tree clean on `dev/frontend` apart from the untracked `go-agent/go-agent/`.

Every task below must leave that green, with the new tests added.

---

## File structure

| File | Responsibility after this plan |
|---|---|
| `docs/design/DECISIONS.md` | `D18` (ratified catalog) + `D19` (v1 local auth / bootstrap) added; `D10` gains a pointer; ID-discipline table, ratification matrix and `SM-D31`'s RBAC note corrected |
| `docs/design/D10-AMENDMENT-PROPOSAL.md` | restored verbatim, banner marks it **RATIFIED / SUPERSEDED** by `D18`+`D19` |
| `docs/design/frontend/decisions.md` | `FE-D47`…`FE-D50` added; `FE-D45` marked superseded; index + changelog updated |
| `docs/design/frontend/design.md` | §13 RBAC text and the `DM-D18` line corrected to cite `D18` |
| `docs/design/common/contracts/console-rest-surface.md` | §13 header de-blocked; §13.12–§13.19 added for the eight Groups routes the console actually calls, each `CONTRACT: TBD` |
| `frontend/src/shared/authz/permissions.ts` | `GrantableVerb` gains the five ratified `group.*` verbs |
| `frontend/src/shared/authz/catalog.ts` | five catalog entries + a `group` permission group |
| `frontend/src/shared/authz/permissions.typetest.ts` | `ALL_GRANTABLE_VERBS` — the type-checked enumeration that closes the catalog↔union gap in both directions |
| `frontend/src/features/admin/groups/groupPermissions.ts` | direct verbs; `memberView` removed |
| `frontend/src/features/admin/groups/groupPermissions.test.ts` | **new** — the mapping's own tests |
| `frontend/src/features/admin/groups/groupValidation.ts` | 1–128, `&` and `'` admitted, whitespace/`/`/`\`/`:`/control rejection made explicit |
| `frontend/src/features/admin/assignments/ScopeSelector.tsx` | `'instance'` scope type + dependent Instance select |
| `frontend/src/features/admin/assignments/AssignmentsView.tsx` | root default fixed to `[]`; system/bootstrap-managed label on root assignments |
| `frontend/src/shared/testdoubles/data/rbac.ts` | `LocalUser.isBootstrapAdmin` |
| `frontend/src/shared/testdoubles/handlers/rbacStore.ts` | last-active-bootstrap-admin guard |
| `frontend/src/shared/testdoubles/handlers/cp.ts` | `409` when that guard trips |
| `frontend/src/features/admin/users/UsersView.tsx` | bootstrap chip; Deactivate disabled-with-reason for the last active bootstrap admin |

---

## Task 140 — Ratify the catalog: `D18` and `D19` in the parent register

**Files:**
- Modify: `docs/design/DECISIONS.md` (ID-discipline table, §6, ratification matrix, `SM-D31`'s RBAC note)
- Create: `docs/design/D10-AMENDMENT-PROPOSAL.md` (restored from `main`, banner prepended)

- [ ] **Step 1: Verify `D18`/`D19` are unused before taking them**

```bash
grep -rnE '(^|[^A-Za-z/-])D1[89]([^0-9]|$)' --include='*.md' --include='*.ts' --include='*.tsx' \
  --include='*.mjs' . | grep -v node_modules
```

Expected: no output. `SM-D18`/`SM-D19` must NOT match — the `[^A-Za-z/-]` guard is what excludes them.

- [ ] **Step 2: Add `D18` to §6 of `DECISIONS.md`**

`LOCKED`. Ratifies the full 35-operation catalog: the 30 shipped operations plus `group.view`,
`group.create`, `group.edit`, `group.deactivate`, `group.member.manage`. States the two verbs deliberately
**not** added (`group.member.view` — covered by `group.view`; `group.role.assign` — covered by
`role.assign`) and the server-side delegation-safety requirement on membership changes.

- [ ] **Step 3: Add `D19` to §6 of `DECISIONS.md`**

`LOCKED`. The v1 local-auth / bootstrap-super-admin model: exactly one Bootstrap Super Admin created
directly in the database at install; built-in Super Administrator role at the internal/root scope;
root/global grants are installer-managed and the console never creates them; Administrator / Developer /
Operator / Viewer are roles, not user types; creating a user grants no role; the last active Bootstrap
Super Admin is not deactivatable; credentials never in plaintext anywhere; hashing and forced first-login
replacement are install-time obligations.

- [ ] **Step 4: Point `D10` at its amendment without changing its meaning**

`D10`'s status cell becomes `LOCKED · catalog leg amended by **D18**` and its decision text gains a trailing
sentence naming `D18` and `D19`. **The original sentences are not reworded.**

- [ ] **Step 5: Update the ID-discipline table and the ratification matrix**

`D1`–`D17` → `D1`–`D19` in both places, and in `CLAUDE.md`'s two mentions of the family range if they name it.

- [ ] **Step 6: Correct `SM-D31`'s stale RBAC line**

It reads *"`D10` has none today"* about `dashboard.*`. Those verbs are in `D18`. Rewrite the line to cite
`D18` rather than deleting the note.

- [ ] **Step 7: Restore the amendment proposal with a ratification banner**

```bash
git show main:docs/design/D10-AMENDMENT-PROPOSAL.md > docs/design/D10-AMENDMENT-PROPOSAL.md
```

Then prepend a banner: **RATIFIED / SUPERSEDED — 2026-09-04**, naming `D18` (§2's verb list, expanded by
five) and `D19` (§3a's bootstrap question, option A chosen), and noting that §3b, §3c and §4 remain open as
backend dependencies. The body below the banner is **not edited** — including its references to open items
23/24, which are the numbering of the branch it was written on.

- [ ] **Step 8: Verify every link in the changed files resolves**

```bash
grep -o '](\.\./[^)]*)\|](\./[^)]*)\|](docs/[^)]*)' docs/design/DECISIONS.md docs/design/D10-AMENDMENT-PROPOSAL.md
```

Expected: every relative target exists on disk.

---

## Task 141 — Record `FE-D47`…`FE-D50`

**Files:**
- Modify: `docs/design/frontend/decisions.md` (index table, four new entries, changelog, `FE-D45` status)
- Modify: `docs/design/frontend/design.md` (§13 and the `DM-D18` line)

- [ ] **Step 1: Verify `FE-D47` is the next free frontend id**

```bash
grep -oE 'FE-D[0-9]+' docs/design/frontend/decisions.md | sort -u -t D -k2 -n | tail -3
```

Expected: `FE-D44`, `FE-D45`, `FE-D46` — so `FE-D47` is next.

- [ ] **Step 2: Add the four entries**

| ID | Decision |
|---|---|
| `FE-D47` | The Groups screens gate on the ratified `group.*` verbs; membership is `group.member.manage` and group role assignment is `role.assign`; `group.member.view` and `group.role.assign` are deliberately not introduced |
| `FE-D48` | The console's v1 group model: one organization, immutable, flat, local-or-IdP, additive allow-only |
| `FE-D49` | An assignment may be composed at Organization, Sub-organization, Environment or **Instance**; root/global is render-and-inspect-only and labelled system-managed |
| `FE-D50` | The Bootstrap Super Admin is represented, never created, by the console; the last active one cannot be deactivated through the UI |

Each carries the self-contained **Rationale** paragraph the R2 review requires, and a *what this costs*
paragraph.

- [ ] **Step 3: Mark `FE-D45` superseded**

Index row status → `PROPOSED · superseded by FE-D47`; the entry gains a status line at the top pointing at
`D18`/`FE-D47`. **Its body is not rewritten** — it is the record of what the console did while the catalog
was unratified, and the two `@ts-expect-error` assertions it removed stay removed.

- [ ] **Step 4: Add one changelog row dated 2026-09-04**

- [ ] **Step 5: Correct `design.md`**

Line ~637's *"**`DM-D18` verbs** wait on a `D10` catalog change. Those buttons are not implemented until the
register lists them."* — the register now lists them (`D18`). Rewrite to say the verbs are ratified and the
buttons remain unbuilt for their own reasons.

---

## Task 142 — Widen the catalog, and close the catalog↔union gap in both directions

**Files:**
- Modify: `frontend/src/shared/authz/permissions.ts`
- Modify: `frontend/src/shared/authz/catalog.ts`
- Modify: `frontend/src/shared/authz/permissions.typetest.ts`
- Test: `frontend/src/shared/authz/catalog.test.ts`

**Interfaces:**
- Produces: `GrantableVerb` widened by `'group.view' | 'group.create' | 'group.edit' |
  'group.deactivate' | 'group.member.manage'`; `ALL_GRANTABLE_VERBS: readonly GrantableVerb[]` exported
  from `permissions.typetest.ts`; `PERMISSION_GROUPS` gains a `{ resource: 'group', label: 'Groups' }` entry.

- [ ] **Step 1: Write the failing tests**

```ts
// catalog.test.ts
import { ALL_GRANTABLE_VERBS } from './permissions.typetest';

describe('the ratified D18 group verbs', () => {
  it('carries all five, and neither of the two D18 declined', () => {
    const ids = new Set<string>(PERMISSION_CATALOG.map((p) => p.id));
    for (const id of ['group.view', 'group.create', 'group.edit', 'group.deactivate',
                      'group.member.manage']) {
      expect(ids.has(id)).toBe(true);
    }
    expect(ids.has('group.member.view')).toBe(false);
    expect(ids.has('group.role.assign')).toBe(false);
  });

  it('marks group.member.manage high-consequence — it delegates roles by proxy', () => {
    expect(PERMISSION_CATALOG.find((p) => p.id === 'group.member.manage')?.highConsequence)
      .toBe(true);
  });
});

describe('GrantableVerb', () => {
  it('is exactly the catalog — no id outside the union, no union member outside the catalog', () => {
    expect([...ALL_GRANTABLE_VERBS].sort()).toEqual(PERMISSION_CATALOG.map((p) => p.id).sort());
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `npx vitest run src/shared/authz/catalog.test.ts`
Expected: FAIL — `group.view` absent, and `ALL_GRANTABLE_VERBS` does not exist.

- [ ] **Step 3: Add the five verbs to `GrantableVerb` and to `PERMISSION_CATALOG`**

```ts
{ id: 'group.view', status: 'grantable' },
{ id: 'group.create', status: 'grantable' },
{ id: 'group.edit', status: 'grantable' },
{ id: 'group.deactivate', status: 'grantable' },
{ id: 'group.member.manage', status: 'grantable', highConsequence: true },
```

plus a `PERMISSION_GROUPS` entry — `{ resource: 'group', label: 'Groups', permissionIds: [...] }` — placed
between `role` and `user`, because an operator reading the role editor groups identity concepts together.

- [ ] **Step 4: Add `ALL_GRANTABLE_VERBS` to the typetest**

A `readonly GrantableVerb[]` literal listing all 35 ids. The **type** rejects an id outside the union; the
**runtime test** in step 1 rejects a catalog id missing from the list. Together they are the "no bare
permission string" guarantee the brief asks for.

- [ ] **Step 5: Run tests and typecheck**

Run: `npx vitest run src/shared/authz/ && npm run typecheck`
Expected: PASS.

---

## Task 143 — Direct Groups permission mapping

**Files:**
- Modify: `frontend/src/features/admin/groups/groupPermissions.ts`
- Modify: `frontend/src/features/admin/groups/groupsTestData.ts` (`ALL_GROUP_PERMISSIONS`)
- Create: `frontend/src/features/admin/groups/groupPermissions.test.ts`
- Modify: `frontend/src/features/admin/groups/GroupsView.tsx` (header comment only)

**Interfaces:**
- Consumes: `GrantableVerb` from task 142.
- Produces: `GROUP_PERMISSIONS` with keys `view · create · edit · deactivate · memberManage ·
  roleAssign`. **`memberView` is removed** — `group.view` covers group and member visibility (`D18`).

- [ ] **Step 1: Write the failing test**

```ts
// groupPermissions.test.ts
describe('GROUP_PERMISSIONS', () => {
  it('maps every action to its own ratified verb', () => {
    expect(GROUP_PERMISSIONS).toEqual({
      view: 'group.view',
      create: 'group.create',
      edit: 'group.edit',
      deactivate: 'group.deactivate',
      memberManage: 'group.member.manage',
      roleAssign: 'role.assign',
    });
  });

  it('gates membership on group.member.manage, never on a user.* verb', () => {
    expect(GROUP_PERMISSIONS.memberManage).toBe('group.member.manage');
    expect(String(GROUP_PERMISSIONS.memberManage).startsWith('user.')).toBe(false);
  });

  it('has no separate member-view verb — group.view covers it', () => {
    expect('memberView' in GROUP_PERMISSIONS).toBe(false);
  });

  it('every mapped verb is in the catalog', () => {
    const ids = new Set<string>(PERMISSION_CATALOG.map((p) => p.id));
    for (const verb of Object.values(GROUP_PERMISSIONS)) expect(ids.has(verb)).toBe(true);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `npx vitest run src/features/admin/groups/groupPermissions.test.ts`
Expected: FAIL — the values are still `user.view` / `user.create` / `role.assign`.

- [ ] **Step 3: Rewrite the mapping**

Replace the borrowed-verb table in the header with the ratified one, and record the backend obligation the
brief states: *the server must also ensure an actor cannot use a membership change to delegate roles or
scopes it is not permitted to delegate.* Keep the `requireGrantable(...)` compile-time proof lines, one per
key, listed rather than iterated.

- [ ] **Step 4: Update `ALL_GROUP_PERMISSIONS`**

```ts
export const ALL_GROUP_PERMISSIONS = [
  'group.view', 'group.create', 'group.edit', 'group.deactivate',
  'group.member.manage', 'role.assign',
] as const;
```

- [ ] **Step 5: Run the Groups suite**

Run: `npx vitest run src/features/admin/groups/`
Expected: PASS. The view tests seed identities from `ALL_GROUP_PERMISSIONS`, so they move with it.

---

## Task 144 — Group-name rules to the ratified spec

**Files:**
- Modify: `frontend/src/features/admin/groups/groupValidation.ts`
- Modify: `frontend/src/shared/i18n/messages.en.ts`
- Test: `frontend/src/features/admin/groups/groupValidation.test.ts`

**Interfaces:**
- Produces: `validateGroupForm` unchanged in signature; `MAX_NAME_LENGTH` is 128; a new error key
  `adminGroupNameWhitespace`.

- [ ] **Step 1: Write the failing tests**

```ts
it('accepts the ratified punctuation set', () => {
  for (const name of ["Payments & Ledger", "O'Brien's Team", 'Ops (EU).v2_beta-1', 'Ürün Ekibi 3']) {
    expect(validateGroupForm({ ...base, name }, { existingGroups: [] }).name).toBeUndefined();
  }
});

it('rejects the four forbidden characters', () => {
  for (const name of ['ops/prod', 'ops\\prod', 'ops:prod', 'opsprod']) {
    expect(validateGroupForm({ ...base, name }, { existingGroups: [] }).name).toBeDefined();
  }
});

it('rejects leading or trailing whitespace rather than silently trimming it', () => {
  expect(validateGroupForm({ ...base, name: ' Ledger Ops' }, { existingGroups: [] }).name)
    .toBe('A group name cannot begin or end with a space.');
  expect(validateGroupForm({ ...base, name: 'Ledger Ops ' }, { existingGroups: [] }).name)
    .toBe('A group name cannot begin or end with a space.');
});

it('accepts 128 characters and rejects 129', () => {
  expect(validateGroupForm({ ...base, name: 'a'.repeat(128) }, { existingGroups: [] }).name)
    .toBeUndefined();
  expect(validateGroupForm({ ...base, name: 'a'.repeat(129) }, { existingGroups: [] }).name)
    .toBeDefined();
});
```

- [ ] **Step 2: Run and watch it fail**

Run: `npx vitest run src/features/admin/groups/groupValidation.test.ts`
Expected: FAIL — `&` and `'` rejected by the pattern; 128 over the old 64 limit; whitespace silently trimmed.

- [ ] **Step 3: Implement**

```ts
const NAME_PATTERN = /^[\p{L}\p{N} ()._\-&']+$/u;
const MAX_NAME_LENGTH = 128;
```

Order of checks: empty → leading/trailing whitespace → length → pattern → uniqueness. The **uniqueness**
comparison still trims and lowercases (the brief's "compared case-insensitively after trim"); the
**validity** check refuses to trim for the caller, so an operator sees the rule rather than a silently
different name in the audit line.

- [ ] **Step 4: Run**

Run: `npx vitest run src/features/admin/groups/groupValidation.test.ts`
Expected: PASS.

---

## Task 145 — Instance-scoped assignments; root renders but is never created

**Files:**
- Modify: `frontend/src/features/admin/assignments/ScopeSelector.tsx`
- Modify: `frontend/src/features/admin/assignments/AssignmentsView.tsx`
- Modify: `frontend/src/shared/i18n/messages.en.ts`
- Test: `frontend/src/features/admin/assignments/AssignmentsView.test.tsx`

**Interfaces:**
- Produces: `SelectableScopeType` = `'organization' | 'subOrganization' | 'environment' | 'instance'`;
  `segmentsRequired('instance') === 4`.

- [ ] **Step 1: Write the failing tests**

```ts
it('can compose an INSTANCE-scoped assignment', async () => { /* pick Instance, four selects, submit */ });

it('offers no root/global scope type — the dialog cannot create one', () => {
  expect(SELECTABLE_SCOPE_TYPES).not.toContain('all');
  // and: the scope-type select has exactly four options
});

it('still renders the installer-bootstrap root assignment, labelled system-managed', () => { /* … */ });

it('by-scope defaults to the root path [], so a root assignment is inspectable there', () => { /* … */ });
```

- [ ] **Step 2: Run and watch it fail**

Run: `npx vitest run src/features/admin/assignments/`
Expected: FAIL — no Instance option; the by-scope default is `['All organizations']`.

- [ ] **Step 3: Add the instance level to `ScopeSelector`**

`SELECTABLE_SCOPE_TYPES` gains `'instance'`; `segmentsRequired` returns 4 for it; a fourth dependent
`Select` renders at `depth >= 4`, its options `childNamesAt(scopeTree, [org, subOrg, env])`, disabled until
the environment is chosen. `setEnvironment` must **clear** a stale instance the way each level above it does.

- [ ] **Step 4: Fix the by-scope default and label root assignments**

`const scope = selectedScope ?? [];` — the root path is `[]` (`FE-D46`), and `ScopeTree` already emits that
on selection. In the by-principal table a `scope.length === 0` row gains a `system-managed` chip
(`adminAssignmentsSystemManaged`), because `D19` makes root grants installer-owned and the console offers no
flow that could have produced them.

- [ ] **Step 5: Run**

Run: `npx vitest run src/features/admin/assignments/ src/features/admin/access/`
Expected: PASS.

---

## Task 146 — The Bootstrap Super Admin, represented and undeactivatable

**Files:**
- Modify: `frontend/src/shared/testdoubles/data/rbac.ts`
- Modify: `frontend/src/shared/testdoubles/handlers/rbacStore.ts`
- Modify: `frontend/src/shared/testdoubles/handlers/cp.ts`
- Modify: `frontend/src/features/admin/users/UsersView.tsx`
- Modify: `frontend/src/shared/i18n/messages.en.ts`
- Test: `frontend/src/shared/testdoubles/handlers/rbacStore.test.ts`, `.../users/UsersView.test.tsx`

**Interfaces:**
- Produces: `LocalUser.isBootstrapAdmin?: boolean`; `rbacStore.setUserActive(id, active): boolean` — `false`
  when the write was refused because it would deactivate the last active bootstrap admin.

- [ ] **Step 1: Write the failing tests**

```ts
// rbacStore.test.ts
it('refuses to deactivate the last active one', () => {
  expect(setUserActive('u-devon', false)).toBe(false);
  expect(listUsers().find((user) => user.id === 'u-devon')?.status).toBe('active');
});

it('deactivates an ordinary user without complaint', () => {
  expect(setUserActive('u-mira', false)).toBe(true);
});

it('never blocks REACTIVATION — the rule only guards the count reaching zero', () => {
  expect(setUserActive('u-priya', true)).toBe(true);
});

it('carries no credential of any kind on a user row (`D19`, `I6`)', () => {
  for (const user of listUsers()) {
    for (const key of Object.keys(user)) {
      expect(/pass|secret|cred|token|hash|otp/i.test(key)).toBe(false);
    }
  }
});
```

⚠️ **The "second bootstrap admin" case is NOT tested through `createUser`.** `D19` says the console never creates
a bootstrap admin, so giving `createUser` an `isBootstrapAdmin` parameter to make a test convenient would
contradict the decision the test exists to prove. The two-admin case is covered at the *component* level instead,
where a repository stub can simply return two rows.

```ts
// UsersView.test.tsx
it('disables Deactivate for the last active Bootstrap Super Admin, with the reason on the control', () => {
  // the button is present, disabled, and its title names D19's rule
});
```

- [ ] **Step 2: Run and watch it fail**

Run: `npx vitest run src/shared/testdoubles/handlers/rbacStore.test.ts src/features/admin/users/`
Expected: FAIL — no `isBootstrapAdmin`; `setUserActive` returns `void`.

- [ ] **Step 3: Implement**

- `rbac.ts`: `isBootstrapAdmin?: boolean` on `LocalUser`, `true` on `u-devon`, whose `createdBy` is already
  `'installer bootstrap'` and who already holds `super` at scope `[]`. Comment states `D19`: created in the
  database at install, never through this console, and **no credential of any kind appears in this fixture**.
- `rbacStore.ts`: `setUserActive` returns `boolean`, refusing the last active bootstrap admin.
- `cp.ts`: `PATCH /admin/users/:id` answers `409` with the localized message when refused — §13.11 of the
  REST contract already documents that status for this case.
- `UsersView.tsx`: a `Bootstrap` chip on the row, and the Deactivate button rendered **disabled with a
  reason** rather than omitted. This is `FE-D9`'s capability-limit branch, not its RBAC branch: the operator
  is authorized, the *system* forbids the last one going away.

- [ ] **Step 4: Run**

Run: `npx vitest run src/shared/testdoubles/ src/features/admin/users/`
Expected: PASS.

---

## Task 147 — Contract, cross-cutting tests, and the full gate

**Files:**
- Modify: `docs/design/common/contracts/console-rest-surface.md`
- Test: `frontend/src/features/admin/groups/groupsIntegration.test.tsx`

- [ ] **Step 1: Document only the routes the frontend actually calls**

Eight, all already implemented in `createAdminRepository`:

| # | Method | Path | §  |
|---|---|---|---|
| 20 | `GET` | `/admin/groups` | 13.12 |
| 21 | `GET` | `/admin/groups/{id}` | 13.13 |
| 22 | `POST` | `/admin/groups` ✱ | 13.14 |
| 23 | `PATCH` | `/admin/groups/{id}` ✱ | 13.15 |
| 24 | `POST` | `/admin/groups/{id}/deactivate` · `/reactivate` ✱ | 13.16 |
| 25 | `GET` `POST` | `/admin/groups/{id}/members` ✱ | 13.17 |
| 26 | `DELETE` | `/admin/groups/{id}/members/{userId}` | 13.18 |
| 27 | `GET` | `/admin/groups/{id}/assignments` | 13.19 |

Every one carries **`CONTRACT: TBD`** and names the required server-side rule it depends on. Pagination,
OpenAPI, audit persistence and token invalidation are listed as **unspecified**, not invented.

- [ ] **Step 2: De-block §13's header**

*"`§16 Q4` — the verb catalog is unratified"* → the catalog is ratified as `D18`; what remains TBD is the
**wire contract**, not the policy. `§16 Q5` narrows likewise: `D19` settles the bootstrap *model*; the
install-time mechanism stays backend-owned.

- [ ] **Step 3: Add the remaining brief-mandated integration tests**

Cross-organization membership rejected · deactivated group confers nothing · direct + group + inherited
deduplicate · group organization immutable in edit mode · IdP group identity/membership read-only while
role assignment stays available · creating a user grants no role.

- [ ] **Step 4: Run the full gate**

```bash
npm run lint && npm run typecheck && npx vitest run
```

Expected: clean, clean, all green.

---

## Backend / install-time dependencies — deliberately NOT implemented

Recorded, not built. Each is a server obligation the console cannot discharge.

| # | Dependency | Why the frontend cannot do it |
|---|---|---|
| B1 | **Deny-by-default enforcement of the 35 verbs** | `REQ-006`'s *"no endpoint is reachable without it"* is a server property. `D18` ratifies the policy; nothing enforces it yet. |
| B2 | **Delegation safety on membership writes** | The server must refuse a membership change that would grant the actor's target a role or scope the actor may not delegate. A client check is a race, not a rule. |
| B3 | **Append-only audit persistence** | actor · action · target · tenant · organization/scope · timestamp · before/after with secrets redacted · **correlation ID as a join key, not the event id**. No client-side audit record is written, and none should be. |
| B4 | **Password hashing and forced first-login replacement** for the Bootstrap Super Admin | Install-time. The console must never see, hold or display a credential. |
| B5 | **Session/token invalidation** on deactivate, revoke, or membership removal | Without it a revoked grant survives in a live session until expiry. |
| B6 | **Production database migrations** — groups, memberships, the assignment `status` column, the bootstrap-admin flag | No backend code exists; `contracts/` does not exist. |
| B7 | **OpenAPI for `/admin/*`** | `FE-D7` wants generated types; §13 is still hand-written prose. |
| B8 | **Pagination protocol** for groups, members and assignments | The console renders whole collections against fixtures. A protocol invented here would be an unratified contract. |
| B9 | **IdP synchronization** (`D13` 🔒, deferred) | The console renders `source: 'idp'` read-only; nothing synchronizes anything. |
| B10 | **Introspection scope** — who may read *whose* effective permissions | The amendment proposal's §4, untouched by `D18`. |

---

## Review

Executed inline, same session, 2026-09-04. Narrative in
[`reports/task-140-147-report.md`](reports/task-140-147-report.md).

### Verification

Run from `frontend/`, 2026-09-04, in this order.

| Gate | Command | Result |
|---|---|---|
| Focused — authz | `npx vitest run src/shared/authz/` | **4 files / 21 tests passed** |
| Focused — groups | `npx vitest run src/features/admin/groups/` | **7 files / 80 tests passed** |
| Focused — assignments + access | `npx vitest run src/features/admin/assignments src/features/admin/access` | **7 files / 56 tests passed** |
| Focused — users + fixture store | `npx vitest run src/features/admin/users src/shared/testdoubles` | **8 files / 80 tests passed** |
| Lint | `npm run lint` | clean, `--max-warnings=0` |
| Typecheck | `npm run typecheck` | clean (both `tsconfig.json` and `tsconfig.node.json`) |
| Full suite | `npx vitest run` | **336 files / 2381 tests passed** |

Baseline was **335 files / 2347 tests**, so this is **+1 file and +34 tests**, with **zero** left failing or skipped.

**Everything red was made red first.** The catalog tests failed on `group.view` absent; the mapping tests on the
borrowed `user.*` values; the validation tests on `&`, on 128 characters, and on whitespace being trimmed instead
of refused; the instance-scope test on a missing option; the by-scope test on a root card that did not exist; the
bootstrap tests on a `LocalUser` with no such field.

**Five existing tests were updated, and each for a stated reason** — not to make a failure go away:

| Test | Why it moved |
|---|---|
| `GroupsView` × 2 authorization tests | seeded `['user.view']`; the view-only identity is now `['group.view']` |
| `groupsIntegration` admin identity | hard-coded the four borrowed verbs; now spreads `ALL_GROUP_PERMISSIONS` |
| `AdminShell` identity | same four verbs; the Groups tab needs the five `group.*` ones |
| `GroupFormDialog` "creates a group with a **trimmed** name" | trimming is no longer the behaviour — split into a clean-name create plus a new test that a padded name is *refused* |
| `groupValidation` "duplicate … after trimming" | split into three: case-insensitive duplicate, uniqueness against a *stored* padded name, and whitespace-beats-duplicate ordering |
| `UsersView` "deactivates an active user" | drove Devon, who is now the undeactivatable bootstrap admin; it drives an ordinary account supplied to that one test |

### What the plan got right

**The mapping module was the single point of change it claimed to be.** `groupPermissions.ts` said in its
own header that "if the architect group ratifies the `group.*` verbs, this module is the single place that
changes — no call site names a verb directly." That held exactly: six values moved, one key
(`memberView`) was deleted, and **not one of the twelve `can(GROUP_PERMISSIONS.…)` call sites — seven in
`GroupsView`, five in `GroupDetails` — needed touching.** (One comment above one of them was updated, because it
described the gap that no longer exists.) The discipline of never writing a verb at a call site paid its cost
back in one commit.

**The catalog↔union gap was real and is now closed both ways.** Before this plan, `GrantableVerb` and
`PERMISSION_CATALOG` were two hand-maintained lists of the same 30 ids with nothing comparing them — adding
a verb to one and forgetting the other was silent. `ALL_GRANTABLE_VERBS` is a `readonly GrantableVerb[]`
literal, so an id outside the union is a **compile** error, and the runtime equality test in
`catalog.test.ts` makes a catalog id missing from the list a **test** failure. That is what "no bare
permission string may bypass `GrantableVerb`" needs to actually mean.

### Three things worth knowing

#### 1. Two latent defects surfaced from §4 of the brief, and both were real

Asking for "root assignments must remain inspectable in by-scope views" exposed
`AssignmentsView`'s `selectedScope ?? [scopeTree.name]` — the synthetic root's **label** used as a path
**segment**. `FE-D46` had moved the root's path to `[]` and `ScopeTree` emits `[]` on selection, but the
component's own default was never updated, so the by-scope view opened on a scope that does not exist and
showed only the assignments whose paths happened to start with the literal string `All organizations` —
which is none of them except the global ones, by accident rather than by design. Fixed to `[]`.

Asking for an instance-scope option exposed that `scopeLevelOf`, `ScopeLevel` and `SCOPE_TREE` had all
carried the instance level since `FE-D46`; only `ScopeSelector` omitted it. The level was already in the
data — the composer just could not reach it.

#### 2. Whitespace validation is a rule, not a convenience

The brief asks for both "compared case-insensitively **after trim**" and "invalid when they contain
**leading/trailing whitespace**". Those look contradictory and are not: trimming is what the *uniqueness*
comparison does so `"  Ledger "` cannot slip past `"Ledger"`, and rejection is what *validity* does so the
operator is told the rule rather than silently getting a different name than they typed in the audit line.
The implementation does both, in that order, and the tests pin both behaviours separately.

#### 3. The Bootstrap Super Admin rule is a capability limit, not an RBAC gate

`FE-D9` omits controls an operator is not authorized to use. The last-active-bootstrap-admin rule is the
opposite case: the operator **is** authorized — they hold `user.deactivate` — and the *system* refuses,
because `D19` makes root grants installer-managed and an install with no active root admin cannot be
recovered through the console. So the button renders **disabled with its reason**, which is `FE-D9`'s
explicitly-carved-out capability-limit branch. Omitting it would have told an administrator they lack a
permission they hold.

### What this deliberately did not do

- **No backend anything.** B1–B10 above are recorded and untouched.
- **`D10`'s text is unedited.** Only its status cell and one appended pointer sentence changed (`I3`).
- **The amendment proposal's body is unedited.** It is restored verbatim from `main` under a banner. Its
  references to open items 23/24 are the numbering of the branch it was written on and are left alone.
- **The built-in super-admin role keeps the reference's name.** `D19` names the role *Super Administrator*;
  the console's built-in role is `RBAC_ROLES` id `super`, displayed **`Solace Super-admin`** — the
  `FE-D30` reference's own string. Renaming it would diverge from the reference for no behavioural gain, so
  `D19` names the id and records both spellings instead.
- **IdP group *deactivation* was left as it is.** Edit and membership are already gated on
  `group.source === 'local'`; status is not. The brief says "IdP group identity and membership are
  read-only", which arguably covers status, but does not say so — recorded as an open question in `FE-D48`
  rather than resolved silently.
