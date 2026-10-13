# FE-093 · Access check (effective permissions) — Report

## Scope and gate

Fourth task of Phase 7 (`090`–`095`), which `task-list.md` marks ⛔ **NOT SCHEDULED** pending Q4 (RBAC verb
catalog unratified) and Q5 (first-admin bootstrap unspecified). Same basis as FE-090/FE-091/FE-092: per ruling
**F3** (`execution.md`) and FE-093's own `Ready: FIXTURE` note ("scheduling stays behind the Phase 7 gate"),
the task itself is unaffected by Q4/Q5 — only the phase's *scheduling* is gated. Built now, in the same
session, against the fixture-backed `AdminRepository` (`CONTRACT: TBD`). GitHub issue #160.

## What was built

**`AccessCheckView`** (`src/features/admin/access/AccessCheckView.tsx`) — the `access` tab's real content,
replacing the FE-090 placeholder branch in `AdminShell`. Two panels:

| Panel | Content |
|---|---|
| **Left** | A `Select` over users and groups alike (label `"<name> · user\|group"` — the same picker FE-092's dialog uses), then a `ScopeTree` (reused from `../assignments`) for the scope. Default: first principal at the tenant root. |
| **Right** | `PrincipalTag` + `at` + the monospace scope path, then the effective-permissions result: a `Permission \| Conferred by` table where each granted permission names its conferring **role** and carries a `direct` / `inherited` `Chip` plus the scope the grant was made at. High-consequence verbs show the shared warn marker. A standing `neutral` `Alert` states the denial rule; when a concrete not-held permission exists it is named as an example. |

The three states the screen exists to make legible:

- **granted** — every row names the assignment (role + granting scope) that confers it.
- **inherited ≠ direct** — a grant made above the selected scope is tagged `inherited` and shows *where* it was
  granted, never as if granted here. Showing it as direct is how someone deletes an assignment and is surprised
  access here did not change (same failure FE-092's by-scope view guards).
- **deny-by-default** — with no assignment reaching the scope, a `warn` `Alert` says so explicitly rather than
  rendering an empty table. The `neutral` denial `Alert` renders in every state: *"a permission that isn't
  granted is denied; one that doesn't exist can't be granted at all."*

**`effectiveGrants.ts`** — two pure helpers, tested in isolation (the split mirrors FE-092's `scopePath.ts`):
- `groupEffectiveGrants(grants)` — one row per permission, sorted by id; when several assignments confer the
  same permission the **most specific** grant wins (longest granting scope, `direct` breaks a tie) — that is
  the assignment an operator would edit.
- `firstDeniedPermissionId(granted)` — the first catalog permission not in the granted set, for the concrete
  denial example. `undefined` when a `'*'` role covers the scope.

No verb is encoded in either helper or the view — only shape. The catalog (`@shared/authz/catalog`) is data
(§16 Q4); adding a verb changes that array, not this code.

## Contract seam touched: `effectivePermissions` MSW handler

`AdminRepository.effectivePermissions(principalId, scope)` and its `EffectiveGrant` shape already existed
(task 033) — FE-093 is the first consumer. The `*/cp/admin/effective-permissions` MSW handler was rewritten:
it previously ignored the scope, took only the principal's *first* assignment, and always returned
`grantType: 'direct'` with `permissionIds` left as the literal `['*']` for a super-admin. It now:

- takes **every** assignment for the principal whose scope **reaches** the queried scope (at or above it);
- expands each role's permissions, `'*'` → the whole catalog;
- marks a grant `direct` when its scope equals the queried scope, else `inherited`, carrying `grantingScope`.

`AdminRepository` is `CONTRACT: TBD`, so this is a fixture-handler fix, not a contract change. The frontend
still treats the server as the sole authority on what is granted (design §15) — the view renders the response
and computes nothing about entitlement itself.

## Wiring

- `AdminShell.tsx` — the `access` tab now renders `<AccessCheckView repository={repository} />`; its
  placeholder branch and the `PLACEHOLDER_COPY.access` entry are **deleted** (not extended), same as
  FE-091/FE-092. `PLACEHOLDER_COPY` narrows to `Record<'users', …>`; an explicit `tab === 'access'` branch
  keeps the final `else` type-narrowed to `'users'`.
- `src/features/admin/access/index.ts` — new public surface: `AccessCheckView`.
- `src/features/admin/index.ts` — unchanged; the shell re-exports what the route needs.
- `messages.en.ts` — new `adminAccess*` keys; removed the two now-dead `adminPlaceholderAccess*` keys.

## Testing

- `effectiveGrants.test.ts` (6) — one row per permission, sorted; most-specific grant wins on scope length and
  on a `direct` tie-break; empty in, empty out; `firstDeniedPermissionId` names a real catalog id it was not
  given, and returns `undefined` when the whole catalog is granted.
- `AccessCheckView.test.tsx` (5) — default view names the conferring role and tags a same-scope grant `direct`,
  with the HC marker present; selecting a principal whose grant is higher up and picking a deeper scope node
  tags it `inherited` and shows the granting scope; a principal with no reaching assignment shows the
  deny-by-default `Alert`; the denial rule renders even when permissions are granted, with an `Example:` line;
  an `effectivePermissions` failure shows the error `Alert` and `Retry` recovers.
- `AccessCheckView.a11y.test.tsx` (6) — granted view, deny-by-default state, error state — axe-clean in light
  and dark.
- `AdminShell.test.tsx` / `AdminShell.a11y.test.tsx` — updated: the `access` tab now asserts the real
  `AccessCheckView` heading (`Effective permissions`) instead of a placeholder; the placeholder-copy `it.each`
  drops `access` and keeps `users`; a11y `settle()` waits for the access heading.
- Verify line (`npm run lint && npm run typecheck && npm test -- src/features/admin/access`) — green
  (3 files, 17 tests). Also ran `npm run test:a11y -- src/features/admin/access` (6) and the full
  `src/features/admin` suite (12 files, 73 tests) plus `test:a11y -- src/features/admin` (4 files, 30 tests) —
  all green.
- `npm run lint` clean · `npm run typecheck` clean.
- Not done: live browser verification — no browser harness available in this session. Behaviour is covered by
  the fixture-backed component tests above and follows
  `docs/design/frontend/reference/app/admin_iam.jsx`'s `AccessCheckView` closely.

## Files

| File | Change |
|---|---|
| `src/features/admin/access/AccessCheckView.tsx` | New — the access tab's content. |
| `src/features/admin/access/AccessCheckView.types.ts` | New — `AccessCheckViewProps`. |
| `src/features/admin/access/effectiveGrants.ts` | New — grant grouping + denial-example helpers. |
| `src/features/admin/access/index.ts` | New — public surface. |
| `src/features/admin/access/effectiveGrants.test.ts` | New. |
| `src/features/admin/access/AccessCheckView.test.tsx` | New. |
| `src/features/admin/access/AccessCheckView.a11y.test.tsx` | New. |
| `src/features/admin/AdminShell/AdminShell.tsx` | `access` tab renders `AccessCheckView`; placeholder deleted; `PLACEHOLDER_COPY` narrows to `users`. |
| `src/features/admin/AdminShell/AdminShell.test.tsx` | Assert the real access view; drop `access` from the placeholder `it.each`. |
| `src/features/admin/AdminShell/AdminShell.a11y.test.tsx` | `settle()` waits for the access heading. |
| `src/shared/testdoubles/handlers/cp.ts` | `effective-permissions` handler computes reaching assignments, expands roles, marks direct/inherited. |
| `src/shared/i18n/messages.en.ts` | New `adminAccess*` keys; removed dead `adminPlaceholderAccess*` keys. |

## Acceptance

- ☑ every granted permission names its conferring assignment — the `Conferred by` column renders the role name,
  a `direct`/`inherited` chip, and the scope the grant was made at, for every row.
- ☑ denial is explained, not just absent — the deny-by-default `Alert` replaces an empty table when nothing
  reaches the scope; a standing `neutral` `Alert` states the not-granted-vs-doesn't-exist rule in every state,
  and names a concrete example permission when one exists.

## Next

FE-094 (Users — local) is next in task order. It is `Ready: DECISION` (§16 Q5 — the credential path is
unchosen), so it does not proceed on the same basis as `091`–`093`. Phase 7 remains ⛔ not scheduled.
