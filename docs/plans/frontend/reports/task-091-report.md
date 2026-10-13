# FE-091 · Roles and RoleEditor — Report

## Scope and gate

Second task of Phase 7 (`090`–`095`), which `task-list.md` marks ⛔ **NOT SCHEDULED** pending Q4 (RBAC verb
catalog unratified) and Q5 (first-admin bootstrap unspecified). Same basis as FE-090: per ruling **F3**
(`execution.md`) and FE-091's own `Ready: FIXTURE` note ("Q4 changes catalog **data**, not this layout"), the
task itself is unaffected by Q4/Q5 — only the phase's *scheduling* is gated. Built now, in the same session,
against the fixture-backed `AdminRepository` (`CONTRACT: TBD`).

## What was built

**`RolesView`** (`src/features/admin/roles/RolesView.tsx`) — the `roles` tab's real content, replacing the
FE-090 placeholder branch in `AdminShell`. Query lifecycle around the role list: skeleton · error-with-retry
(`ErrorPanel`) · two card sections, **Built-in** (`t('adminRolesBuiltInSection')` — "read-only, cloneable")
and **Custom**. Each `RoleCard` is a single `<button>` (`aria-label={role.name}` for a deterministic
accessible name) showing the role name, a kind chip, the description, a permission-count chip (`'*'` renders
"all permissions", never a number), and an "includes high-consequence" marker when the role's expanded grant
set contains a `highConsequence` verb. "New custom role" opens the editor in `create` mode.

**`RoleEditor`** (`src/features/admin/roles/RoleEditor.tsx`) — one `Dialog` with four modes:

| Mode | Entered from | Behaviour |
|---|---|---|
| `inspect` | clicking a **built-in** role | read-only; info `Alert` ("Built-in roles can't be edited"); every permission checkbox `disabled`; footer is **Close** + **Clone…** |
| `edit` | clicking a **custom** role | name + description prefilled and editable |
| `clone` | **Clone…** from `inspect` | name seeded `"<name> (copy)"`, selection carried over from the source, fully editable |
| `create` | **New custom role** | blank; **Save role** disabled until the name is non-empty |

The permission list is `PERMISSION_GROUPS` (from `@shared/authz/catalog`) rendered as `<fieldset>`/`<legend>`
groups, each with an **All / Clear** toggle (editable modes only). Every row: a `Checkbox` labelled with the
verb id, a **high-consequence** marker, a **two-sided** chip, and a `CatalogStatusChip` — all three driven by
the row's `PermissionDef`, so adding a verb or flipping a status is a catalog data change and this layout does
not move. A closing warn `Alert` ("Catalog not ratified") states the 2-grantable / 6-proposed / rest-placeholder
split in the editor itself, matching the reference screen's own finding block.

**`CatalogStatusChip`** (`src/features/admin/roles/CatalogStatusChip.tsx`) — one `CatalogStatus` →
`{tone, label, tip}` mapping behind a tooltip-backed `Chip`, so "proposed · not grantable" reads identically on
every row.

**`rolePermissions.ts`** — `PERMISSION_BY_ID`, `ALL_PERMISSION_IDS` (the concrete set behind `'*'`),
`expandRolePermissions()`, `includesHighConsequence()`. No verb ids are hard-coded here — only shape.

## Contract seam touched: `saveRole`

FE-091 is the first consumer of `AdminRepository.saveRole`. Its return was widened from `Promise<RbacRole>` to
`Promise<SaveRoleResult>` (`{ role, correlationId }`) via `cpClient.postWithCorrelation`, the same shape
`DeclareInstanceResult` (task 115) already uses — because the editor reports its own outcome with `pushOutcome`
and `ToastInput.correlationId` is required (`FE-D13`), never client-fabricated. `AdminRepository` is
`CONTRACT: TBD`, so this is a seam refinement, not a contract break. The MSW handler
(`*/cp/admin/roles` POST) is unchanged — `readCorrelationId` falls back to the client-generated id when the
response carries no header.

## Wiring

- `AdminRoute.tsx` now builds `createAdminRepository(createCpClient(config.apiBasePath))` in a `useMemo` and
  passes it to `AdminShell` — repository construction lives in the app layer because `RuntimeConfig` is not a
  feature import (boundaries), exactly as `InstanceCategoryRoute` documents.
- `AdminShellProps` gains `repository: AdminRepository`; the `roles` tab renders `<RolesView>`, the other three
  keep their FE-092–FE-094 placeholders.
- `src/features/admin/index.ts` now also exports `createAdminRepository`, `AdminRepository`, `RoleDraft`,
  `SaveRoleResult`.
- `messages.en.ts` — new `adminRole*` / `adminRoleEditor*` keys (list chrome, card labels, status vocabulary,
  editor titles/actions, the catalog finding). `adminRoleEditorSelectedCount` reuses the `{count} selected`
  plural shape `tableSelectedCount` already established.

## Testing

- `RolesView.test.tsx` (7) — sections render from fixtures; `'*'` shows "all permissions"; built-in opens
  read-only with disabled checkboxes and Clone as the only mutation path; non-grantable verbs show
  "candidate · absent" and the "Catalog not ratified" finding is present; clone seeds name + selection;
  custom opens in `edit` prefilled; create blocks Save until named, then calls `saveRole` with the right draft,
  closes, and records a `pushOutcome` carrying the server correlation id; roles-load failure shows
  `ErrorPanel` and Retry recovers.
- `RolesView.a11y.test.tsx` (8) — list, read-only inspect dialog, create editor, and error state, axe-clean in
  light and dark.
- `AdminShell.test.tsx` / `AdminShell.a11y.test.tsx` — updated to pass a repository; the roles tab now asserts
  the real `RolesView` heading instead of the old placeholder; other tabs still assert their placeholders.
- Verify line (`npm run lint && npm run typecheck && npm test -- src/features/admin/roles && npm run test:a11y
  -- src/features/admin/roles`) — all green.
- Full regression: `npm run lint` clean · `npm run typecheck` clean · unit **1519 passing** · a11y **331
  passing**.
- Not done: live browser verification — no browser harness available in this session. Behaviour is covered by
  the fixture-backed component tests above and follows `docs/design/frontend/reference/app/admin.jsx` closely.

## Files

| File | Change |
|---|---|
| `src/features/admin/roles/RolesView.tsx` | New — role list, sections, cards, editor state. |
| `src/features/admin/roles/RolesView.types.ts` | New — `RolesViewProps`. |
| `src/features/admin/roles/RoleEditor.tsx` | New — inspect/edit/clone/create dialog. |
| `src/features/admin/roles/RoleEditor.types.ts` | New — `RoleEditorMode`, `RoleEditorProps`. |
| `src/features/admin/roles/CatalogStatusChip.tsx` | New — status → chip+tooltip mapping. |
| `src/features/admin/roles/rolePermissions.ts` | New — catalog read helpers. |
| `src/features/admin/roles/index.ts` | New — `RolesView` public surface. |
| `src/features/admin/roles/RolesView.test.tsx` | New. |
| `src/features/admin/roles/RolesView.a11y.test.tsx` | New. |
| `src/features/admin/adminRepository.ts` | `saveRole` → `SaveRoleResult` via `postWithCorrelation`. |
| `src/features/admin/index.ts` | Export the repository + role types. |
| `src/features/admin/AdminShell/AdminShell.tsx` | `repository` prop; `roles` tab renders `RolesView`. |
| `src/features/admin/AdminShell/AdminShell.types.ts` | `AdminShellProps.repository`. |
| `src/features/admin/AdminShell/AdminShell.test.tsx` | Pass a repository; assert the real roles view. |
| `src/features/admin/AdminShell/AdminShell.a11y.test.tsx` | Pass a repository; settle the roles view. |
| `src/app/routes/AdminRoute.tsx` | Build the admin repository, pass it to `AdminShell`. |
| `src/shared/i18n/messages.en.ts` | New `adminRole*` catalogue keys. |

## Acceptance

- ☑ built-ins not editable — `inspect` mode: no name field, every checkbox disabled, save path is clone-only
- ☑ clone works — seeds `"<name> (copy)"` and the source selection, saves as a new custom role
- ☑ non-grantable verbs visibly non-grantable — `CatalogStatusChip` renders "proposed · not grantable" /
  "candidate · absent" per row, plus the editor-level "Catalog not ratified" finding

## Next

FE-092 (Assignments and ScopeTree) is next in task order, depends on FE-090, and deletes the `assignments`
placeholder branch in `AdminShell.tsx`.
