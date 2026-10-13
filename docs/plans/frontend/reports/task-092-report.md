# FE-092 · Assignments and ScopeTree — Report

## Scope and gate

Third task of Phase 7 (`090`–`095`), which `task-list.md` marks ⛔ **NOT SCHEDULED** pending Q4 (RBAC verb
catalog unratified) and Q5 (first-admin bootstrap unspecified). Same basis as FE-090/FE-091: per ruling **F3**
(`execution.md`) and FE-092's own `Ready: FIXTURE` note ("scheduling stays behind the Phase 7 gate"), the task
itself is unaffected by Q4/Q5 — only the phase's *scheduling* is gated. Built now, in the same session,
against the fixture-backed `AdminRepository` (`CONTRACT: TBD`). GitHub issue #159.

## What was built

**`AssignmentsView`** (`src/features/admin/assignments/AssignmentsView.tsx`) — the `assignments` tab's real
content, replacing the FE-090 placeholder branch in `AdminShell`. Four parallel queries (`assignments`,
`principals`, `roles`, `scopeTree`) via `useQueries`, with a combined skeleton / error-with-retry
(`ErrorPanel`, retry refetches all four) lifecycle. A `ToggleGroup` switches between two reads of the same
data:

| View | Content |
|---|---|
| **By principal** (default) | A `<Table>` — one row per assignment: `PrincipalTag`, a role `Chip` (tone by role kind), and the scope path in monospace. |
| **By scope** | `ScopeTree` on the left; on the right, every assignment whose scope **reaches** the selected node (`isScopePrefix`), each tagged **direct** (`scopePathsEqual`) or **inherited**, with the conferring role and the scope it was granted at. A standing `neutral` `Alert` states that an inherited row comes from higher in the tree and removing it changes access here — showing it as "direct" is the mistake the view exists to prevent. |

**`ScopeTree`** (`src/features/admin/assignments/ScopeTree.tsx`) — a recursive, selectable hierarchy. Picking
a node yields its full path from the tenant root. Each node is a `<button aria-pressed>` for selection; the
expand/collapse control is a **sibling** button (`aria-label` "Expand/Collapse {name}", `aria-expanded`),
never nested inside the node's button — nested buttons are invalid and break the a11y tree. Default expanded.
Exported for reuse by FE-093 (Access check).

**`NewAssignmentDialog`** (`src/features/admin/assignments/NewAssignmentDialog.tsx`) — one `Dialog`. A single
`Select` lists users and groups alike (label `"<name> · user|group"` — "user or group, the same picker"); a
role `Select`; a `ScopeTree` for the scope with a live "Selected: …" readout. **Create assignment** is
disabled until both principal and role are chosen. When the chosen role grants a **two-sided** verb
(`target.use` / `instance.bindTarget`, read from `PERMISSION_CATALOG`, `'*'` counts) an `info` `Alert` states
the one rule that needs a permission on both the instance's scope and the target's `ownership_scope`. On
success it invalidates `queryKeys.admin.assignments()` and reports the outcome with `pushOutcome` carrying the
server correlation id.

**`PrincipalTag`** (`src/features/admin/assignments/PrincipalTag.tsx`) — a principal rendered as a
discriminated `user | group` reference (name + kind `Chip`; "IdP group" when `source === 'idp'`), never a
bare id, so an IdP migration is a data change. `showSub` toggles the secondary line. Exported for FE-093.

**`scopePath.ts`** — `scopePathString` (`join(' / ')`), `scopePathsEqual`, `isScopePrefix` (ancestor
reaches descendant — the "grants downward" test, and what separates direct from inherited). Ported from the
UI reference's `admin_data.jsx` `pathStr` / `isPrefix`. No scope names hard-coded — only shape.

## Contract seam touched: `createAssignment`

FE-092 is the first consumer of `AdminRepository.createAssignment`. Its return was widened from
`Promise<RbacAssignment>` to `Promise<CreateAssignmentResult>` (`{ assignment, correlationId }`) via
`cpClient.postWithCorrelation` — the same shape `SaveRoleResult` (FE-091) and `DeclareInstanceResult`
(task 115) already use, because the dialog reports its own outcome with `pushOutcome` and
`ToastInput.correlationId` is required (`FE-D13`), never client-fabricated. `AdminRepository` is
`CONTRACT: TBD`, so this is a seam refinement, not a contract break. The MSW handler
(`*/cp/admin/assignments` POST) is unchanged — `readCorrelationId` falls back to the client-generated id when
the response carries no header.

## Wiring

- `AdminShell.tsx` — the `assignments` tab now renders `<AssignmentsView repository={repository} />`; its
  placeholder branch and the `adminPlaceholderAssignments*` entries are **deleted** (not extended), same as
  FE-091 did for `roles`. `PLACEHOLDER_COPY` narrows to `Record<'access' | 'users', …>`, so the final `else`
  branch type-narrows `tab` and needs no fallback.
- `src/features/admin/assignments/index.ts` — new public surface: `AssignmentsView`, `ScopeTree`,
  `PrincipalTag`, and the `scopePath` helpers (the last three for FE-093 reuse).
- `src/features/admin/index.ts` — unchanged; the shell already re-exports what the route needs.
- `messages.en.ts` — new `adminAssignments*` / `adminNewAssignment*` / `adminScopeTree*` /
  `adminPrincipalKind*` keys; removed the two now-dead `adminPlaceholderAssignments*` keys.

## Testing

- `scopePath.test.ts` (7) — path string; ancestor reaches descendant and self; descendant/sibling do not;
  `scopePathsEqual` is segment-wise.
- `ScopeTree.test.tsx` (4) — every node renders; selecting a node reports its full path; only the selected
  node is `aria-pressed`; collapsing a branch hides its descendants.
- `AssignmentsView.test.tsx` (5) — by-principal table discriminates a group from a user; by-scope tags a
  node-level grant `direct` and an ancestor grant `inherited` and shows the inheritance note; New assignment
  blocks Create until principal + role are set, then calls `createAssignment` with the right draft, closes,
  and records a `pushOutcome`; the two-sided `Alert` appears only for a two-sided role; assignments-load
  failure shows `ErrorPanel` and Retry recovers.
- `AssignmentsView.a11y.test.tsx` (8) — by-principal table, by-scope view, New assignment dialog, and error
  state, axe-clean in light and dark.
- `AdminShell.test.tsx` / `AdminShell.a11y.test.tsx` — updated: generic shell assertions moved off the
  `assignments` tab onto `users` (still a static placeholder); the `assignments` tab now asserts the real
  `AssignmentsView` heading; a11y `settle()` waits for it.
- Verify line (`npm run lint && npm run typecheck && npm test -- src/features/admin/assignments && npm run
  test:a11y -- src/features/admin/assignments`) — green.
- Full regression: `npm run lint` clean · `npm run typecheck` clean · unit + a11y — see below.
- Not done: live browser verification — no browser harness available in this session. Behaviour is covered by
  the fixture-backed component tests above and follows `docs/design/frontend/reference/app/admin_iam.jsx`
  closely.

## Files

| File | Change |
|---|---|
| `src/features/admin/assignments/AssignmentsView.tsx` | New — tab content, by-principal / by-scope. |
| `src/features/admin/assignments/AssignmentsView.types.ts` | New — `AssignmentsViewProps`. |
| `src/features/admin/assignments/ScopeTree.tsx` | New — selectable scope hierarchy. |
| `src/features/admin/assignments/NewAssignmentDialog.tsx` | New — create-assignment dialog. |
| `src/features/admin/assignments/PrincipalTag.tsx` | New — discriminated principal chip. |
| `src/features/admin/assignments/scopePath.ts` | New — path string / equality / prefix helpers. |
| `src/features/admin/assignments/index.ts` | New — public surface (incl. FE-093 reuse exports). |
| `src/features/admin/assignments/scopePath.test.ts` | New. |
| `src/features/admin/assignments/ScopeTree.test.tsx` | New. |
| `src/features/admin/assignments/AssignmentsView.test.tsx` | New. |
| `src/features/admin/assignments/AssignmentsView.a11y.test.tsx` | New. |
| `src/features/admin/adminRepository.ts` | `createAssignment` → `CreateAssignmentResult` via `postWithCorrelation`. |
| `src/features/admin/AdminShell/AdminShell.tsx` | `assignments` tab renders `AssignmentsView`; placeholder deleted. |
| `src/features/admin/AdminShell/AdminShell.test.tsx` | Move generic assertions to `users`; assert real assignments view. |
| `src/features/admin/AdminShell/AdminShell.a11y.test.tsx` | Settle the assignments view. |
| `src/shared/i18n/messages.en.ts` | New `adminAssignments*` / `adminNewAssignment*` / `adminScopeTree*` / `adminPrincipalKind*` keys; removed dead placeholder keys. |

## Acceptance

- ☑ principal is discriminated — `PrincipalTag` renders a `user | group` reference with a kind chip
  ("IdP group" for IdP groups); the picker is one `Select` over both kinds; nothing renders a bare id.
- ☑ inherited ≠ direct — by-scope tags each reaching assignment `direct` only when `scopePathsEqual`, else
  `inherited`, and a standing `Alert` spells out why the distinction matters.
- ☑ scope grants downward and says so — `isScopePrefix` drives the by-scope list; the dialog's scope picker
  is captioned "grants here and everything below".

## Next

FE-093 (Access check — effective permissions) is next in task order, depends on FE-092, and reuses
`ScopeTree` and `PrincipalTag` from `src/features/admin/assignments`. Phase 7 remains ⛔ not scheduled.
