# 127 — Hierarchy: drop the Tenant level, make Org → Sub-org → Environment a dependent cascade

> **Superseded data mapping (`D20`, 2026-09-07):** the synthetic root and three real ownership levels remain, but
> the canonical migration is former Tenant → Organization, former Organization → Sub-organization, and former
> Sub-org removed. `FE-D53` and the current fixtures implement that mapping; the task details below are execution
> history.

**Component:** frontend · **Phase:** 6 (follow-on) · **Tasks:** `133`–`139`
**Written:** 2026-09-04 · **Status:** COMPLETE (2026-09-04)
**Decision of record:** [`FE-D46`](../../design/frontend/decisions.md) · **Reference:** [`docs/design/frontend/reference/`](../../design/frontend/reference/README.md) (`FE-D30`)

---

## Context

The console presents a four-level resource hierarchy, `Tenant → Organization → Sub-org → Environment`. The
Tenant level earns nothing: it is a single node, never more than one, and `I5`/`FE-D8` guarantee it can never
be switched or sent. It costs a discriminating-nothing segment on every RBAC scope path and, worse, makes the
*isolation* boundary read as a *navigational* one.

`FE-D46` removes it as a hierarchy level and mandates a dependent cascade. **Tenant as the isolation boundary
stays** — `Identity.tenant`, the app-bar chip, and the two no-tenant-segment route tests are untouched.

**Intended outcome:** every hierarchy surface in the console reads `Organization → Sub-org → Environment`;
selecting an organization loads its sub-orgs, selecting a sub-org loads its environments; no stale tenant
reference remains in the hierarchy model.

---

## Baseline (measured 2026-09-04, before any change)

`npm run typecheck` clean · `npx vitest run` → **326 files / 2222 tests passed** · working tree clean on
`dev/frontend` apart from the untracked `go-agent/go-agent/`.

Every task below must leave that green, with the new tests added.

---

## Findings this plan rests on (verified, not inferred)

### A. Two disagreeing hierarchy fixtures

`shared/testdoubles/data/rbac.ts`'s `SCOPE_TREE` knows **two** organizations (Payments, Trading).
`shared/testdoubles/data/instances.ts` carries **six** (Payments, Trading, Risk, Onboarding, Logistics,
Sandbox). Adding a third source for the cascade would make three. Hence the derived tree in task 133.

### B. `[]` already works as a global scope

- `isScopePrefix([], x)` is `true` for every `x` — `scopePath.ts:22-27`.
- The fixture handler already decodes an absent `scope` param to `[]` — `handlers/cp.ts:103`
  (`const scope = scopeParam === '' ? [] : scopeParam.split('/')`), and its `reaches([])` is true for all.

So the synthetic root needs **no** change to `EffectiveGrant`, `groupEffectiveGrants`, `AdminRepository`, or the
handler. Two call sites do need it (task 135).

### C. Two fixture rows cannot satisfy a three-level cascade

`instances.ts:281` has `org: 'Sandbox', subOrg: '—'`. The `FE-D30` reference's `Trading`
(`reference/app/admin_data.jsx:51`) hangs Environments directly off the organization. Both are resolved as
fixture inventions in task 133 and recorded as such in `FE-D46`.

### D. There is no cascade to fix — there is one to build

`DeclareInstanceDialog.tsx:216-232` renders Org and Sub-org as free-text `TextField`s; Env is a `ToggleGroup`
over a hardcoded `ENV_OPTIONS`. Nothing loads anything from anything today.

---

## Tasks

### `133` — One hierarchy fixture; derive `SCOPE_TREE` from it

**Files:** new `src/shared/testdoubles/data/hierarchy.ts`; `src/shared/testdoubles/data/rbac.ts`

- [x] Add `SubOrgNode { name, environments: readonly Env[] }`, `OrganizationNode { name, subOrgs }`, and
      `ORGANIZATIONS`, seeded from the org/sub-org/env triples the instance rows already imply:
      Payments→Ledger→PROD · Trading→Quotes→PROD,TEST · Risk→Batch→TEST,DEV · Onboarding→KYC→TEST,DEV ·
      Logistics→EU-West→PROD · Sandbox→Default→DEV.
- [x] Change `instances.ts`'s Sandbox row from `subOrg: '—'` to `subOrg: 'Default'`.
- [x] `ScopeNode.level`: drop `'Tenant'`, add `'All'`.
- [x] Replace the hand-written `SCOPE_TREE` with one derived from `ORGANIZATIONS` (org → sub-org → env) plus
      instance leaves from `CACHE_INSTANCES`, under a synthetic root `{ name: <All organizations>, level: 'All' }`.
- [x] Rebase `RBAC_ASSIGNMENTS`: super-admin `['Acme Financial'] → []`; every other path drops its leading
      `'Acme Financial'` segment.
- [x] Reword the `'*'` and `RbacAssignment.scope` doc comments off "tenant root".
- [x] Update `RBAC_ROLES`' super description: "Every permission, at the tenant root." → across all organizations.

**Verify:** new `hierarchy.test.ts` asserts every organization has ≥1 sub-org, every sub-org ≥1 environment, and
that every `CACHE_INSTANCES` triple resolves in `ORGANIZATIONS` (the anti-drift assertion). No `'Tenant'` literal
survives in either fixture.

### `134` — `HierarchyRepository` and its three seams

**Files:** new `src/shared/hierarchy/{hierarchyRepository.ts,index.ts}`; `src/shared/api/queryKeys.ts`;
`src/shared/testdoubles/handlers/cp.ts`

- [x] `organizations(signal?)`, `subOrgs(org, signal?)`, `environments(org, subOrg, signal?)` — three sequential
      seams, not one tree fetch, per `FE-D46`. Follow `adminRepository.ts`'s `opts(signal)` / `PlaneClient` shape.
- [x] `queryKeys.hierarchy.{organizations,subOrgs,environments}` as `as const` tuples.
- [x] MSW handlers for the three routes, reading `ORGANIZATIONS`, with `encodeURIComponent` on path segments.
- [x] Lives in `shared/` — legal per `eslint.config.js`'s boundary policy, and needed by more than one feature.

**Verify:** `hierarchyRepository.test.ts` covers each seam, an unknown org/sub-org returning `[]`, and abort
propagation. `npm run lint` proves the boundary is clean.

### `135` — The synthetic root and the global scope

**Files:** `src/features/admin/assignments/scopePath.ts`, `ScopeTree.tsx`,
`src/features/admin/assignments/NewAssignmentDialog.tsx`, `src/features/admin/access/AccessCheckView.tsx`,
`src/shared/i18n/messages.en.ts`

- [x] `scopePathString([])` returns `t('adminScopeAllOrganizations')` instead of `''`.
- [x] `NewAssignmentDialog.tsx:57`: initial scope `[scopeTree.name] → []`.
- [x] `AccessCheckView.tsx:74`: default scope `[scopeTree.name] → []`.
- [x] `AccessCheckView.tsx:80`: `enabled` guard drops `scope.length > 0` — `[]` is now a legal scope, and the old
      guard would silently suppress the query at global scope. **This is the one real bug the change introduces if
      missed.**
- [x] Reword the "path from the tenant root" headers in `scopePath.ts` and `ScopeTree.tsx`.
- [x] Add the `adminScopeAllOrganizations` message.

**Verify:** `scopePath.test.ts` gains the empty-path case; `AccessCheckView.test.tsx` gains a global-scope
assertion showing super-admin's grants render as `direct` at `[]` and `inherited` below it.

### `136` — The Declare Instance cascade

**Files:** `src/features/inventory/instances/DeclareInstanceDialog.tsx`, `declareForm.ts`

- [x] Org and Sub-org become dependent `Select`s fed by `useQuery`; Sub-org disabled until an org is chosen.
- [x] Env stays a `ToggleGroup` (reference parity) but renders only the loaded environments and is disabled until
      a sub-org is chosen.
- [x] Reset semantics: changing org clears sub-org **and** env; changing sub-org clears env.
- [x] `DeclareFormState.env` becomes `Env | ''`; `initialDeclareForm` no longer defaults `'DEV'`;
      `validateDeclareForm` errors on empty env; `toDeclareInput` narrows to `Env` (validation guarantees it).
- [x] Loading and error states for each step — a failed sub-org load must not read as "this org has none".

**Verify:** `declareForm.test.ts` covers the new required-env rule and both reset cascades;
`DeclareInstanceDialog.test.tsx` switches from `typeInto` to option selection and asserts the disabled-until-chosen
chain end to end.

### `137` — Sweep the remaining hierarchy-level tenant references

**Files:** `src/app/routes.ts`, `src/app/router.tsx`, `src/app/shell/AppShell.tsx`, `src/features/auth/LoginPage.tsx`,
`src/app/dev/derivePackageCatalog.ts`

- [x] Prose-only pass over comments that describe Tenant **as a hierarchy level**.
- [x] **Do not touch** `AuthAdapter.ts`'s `Identity.tenant` block, `TenantDisplay.tsx`, `AppBar.tsx`'s chip slots,
      `AccountMenu.tsx`, `tenantLabel`/`tenantTooltip`, `devMockAdapter`, or the `cp` identity handler — those are
      `I5`/`FE-D8`'s isolation surface, which `FE-D46` explicitly preserves.
- [x] **Do not touch** `DashboardToolbar.tsx:14`, `LayerBadge.tsx:10`, `SaveWidgetDialog.tsx:15` or the matching
      `e2e/journeys.spec.ts:304` assertion — those are `DM-D5`'s dashboard-layer axis, not this hierarchy.

**Verify:** `routes.test.ts` and `router.test.tsx`'s no-tenant-segment tests still pass **unchanged** — they
assert `I5`, and this task must not make them vacuous.

### `138` — Update the existing specs

**Files:** `AssignmentsView.test.tsx`, `AssignmentsView.a11y.test.tsx`, `ScopeTree.test.tsx`,
`AccessCheckView.test.tsx`, `AccessCheckView.a11y.test.tsx`, `RolesView.test.tsx`, `RolesView.a11y.test.tsx`,
`InstancesPage.test.tsx`, `columns.test.tsx`, `instancesRepository.test.ts`, `SolutionsPage.test.tsx`,
`e2e/journeys.spec.ts`

- [x] Rebase scope paths off the tenant root; retarget the `Sub-org` free-text queries at the new selects.
- [x] E2E: the declare journey walks the cascade in order rather than typing three fields.

### `139` — Documentation

**Files:** `docs/design/frontend/design.md`

- [x] Update §28's as-built map with the new `shared/hierarchy/` module and cite `FE-D46`.
- [x] Correct the hierarchy as described in the scope/RBAC sections; leave §13/§15's `I5` tenant text alone.
- [x] Write `docs/plans/frontend/reports/task-133..139-report.md`.

---

## Out of scope, deliberately

- **Tenant as the isolation boundary** — `I5`, `REQ-003`, `FE-D8`, `Identity.tenant`, the app-bar chip.
- **`DM-D5`'s dashboard layers** (`entity`/`tenant`/`personal`) — a different axis, unratified and unbuilt.
- **Free-form environment names** — `Env` stays a union behind the new interface (`FE-D46`).
- **The `FE-D30` reference files** — the divergence is recorded in `FE-D46`; the reference is not edited.
- **Backend / contracts** — `contracts/` does not exist; the three hierarchy routes are fixture-only.

---

## Definition of done

- [x] `npm run typecheck` clean
- [x] `npx vitest run` — ≥2222 tests, all passing
- [x] `npm run lint` and `npm run lint:fixtures:assert` clean (boundary policy holds for `shared/hierarchy/`)
- [x] `npm run test:a11y` clean
- [x] `npm run e2e` — the declare journey passes through the cascade
- [x] `grep -ri tenant frontend/src` returns **only** the `I5`/`FE-D8` isolation surface and the `DM-D5` layer
      comments — nothing describing a hierarchy level
- [x] Screens checked at 1440 px against the reference for unintended visual drift

---

## Review (2026-09-04)

All seven tasks complete. Full write-up in
[`reports/task-133-139-report.md`](reports/task-133-139-report.md).

**Verified:** typecheck clean · `vitest run` 328 files / 2247 tests (from 326 / 2222) · lint clean ·
`lint:fixtures:assert` clean · `test:a11y` 355 tests · `build` succeeds · `playwright test` 172/172 on a clean
run.

**Three deviations from the plan as written, all deliberate:**

1. **The fixture export is `CACHE_INSTANCES`, not `INSTANCE_ROWS`** — the plan named it wrongly. Corrected in
   the task text above.

2. **Labels renamed beyond the plan's letter.** `Org` → `Organization` and `Sub-org` → `Sub-organization` in
   the form, and `ScopeNode.level`'s `'Sub-org'` → `'Sub-organization'`. The brief asked for a hierarchy that
   reads consistently; leaving three different spellings of the same level on screen would not have delivered
   that.

3. **One out-of-scope e2e fix.** `journey 4`'s title assertion was racy *on the untouched baseline* — measured
   at 2 failures in 3 runs, identical strict-mode violation. Task 138 did not anticipate it. Fixed by scoping
   the assertion to the notifications region, because the DoD's `npm run e2e` gate could not otherwise be
   claimed honestly.

**Left open, stated rather than hidden:**

- A rarer load-dependent e2e flake (a random reflow or journey-3 failure, roughly one full run in three)
  exists on baseline too. Not caused by this work, not fixed by it.
- `npm run format` fails on 164 files at baseline, 153 here. Pre-existing; out of scope.
- `derivePackageCatalog.ts` synthesizes a DEV-only row whose organization is not in `ORGANIZATIONS`. Flagged
  in the report; deliberately unchanged.
