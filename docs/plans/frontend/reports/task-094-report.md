# FE-094 · Users (local) — Report

## Scope and gate

Fifth task of Phase 7 (`090`–`095`), which `task-list.md` marks ⛔ **NOT SCHEDULED** pending Q4 (RBAC verb
catalog unratified) and Q5 (first-admin bootstrap unspecified). Unlike `090`–`093` (all `Ready: FIXTURE`),
`094` is the plan's **single `Ready: DECISION`** task: §16 Q5 required the new-user credential path to be
picked "by decision, not by default". That decision was taken this session and recorded as **`FE-D38`** — the
developer confirmed the path (admin-set temporary credential) before any code was written. Phase 7 *scheduling*
stays gated; the task itself is now unblocked on the frontend surface. GitHub issue #161.

## The decision — `FE-D38` (frontend surface of §16 Q5)

- **Credential path: admin-set temporary credential, forced change on first sign-in.** The only option with no
  external dependency — it works in the air-gapped on-prem deployment `REQ-003` requires. The reference
  (`reference/app/admin.jsx`, `AddUserModal`) already marks it "the chosen option"; `FE-D30` makes the
  reference the source of truth for screens.
- **The other two options are shown, not shipped.** Emailed invitation renders **disabled** ("introduces an
  email capability this console does not have"); out-of-band handoff is selectable and passes
  `credentialPath: 'oob'` but carries no distinct mechanism. `credentialPath` is a three-value enum on the
  request so revisiting the decision does not move the request shape.
- **The first-admin bootstrap is deliberately not built.** `AdminRepository.bootstrapFirstAdmin()` stays
  absent; no installer-secret form is rendered. The reference's `BootstrapView` is itself labelled a
  "load-bearing invention … needs a decision before anyone builds it", so matching the reference's *intent*
  means leaving it out. `UsersView` carries a standing `neutral` note saying the bootstrap mechanism is
  unspecified and product/backend-owned.
- **Not settled by this:** Q5's mechanism, the `POST /admin/users` contract, and the bootstrap flow remain
  product/backend-owned and open (`I4`).

## What was built

**`UsersView`** (`src/features/admin/users/UsersView.tsx`) — the `users` tab's real content, replacing the
FE-090 placeholder branch in `AdminShell`. A table (User / Status / Last sign-in / Created by / action) over
`AdminRepository.users()`. Each row's only lifecycle control is **Deactivate** (active) or **Reactivate**
(deactivated) — there is no delete anywhere. A `warn` `Alert` states what an IdP migration removes (**Add
user**, **Deactivate** move to the provider) and what survives (**the list**; Assignments untouched). A
caption repeats "Deactivate, never delete — an account that authored configuration history stays
attributable." A `neutral` `Alert` records that the first-admin bootstrap is unspecified.

**`AddUserDialog`** (`src/features/admin/users/AddUserDialog.tsx`) — Full name + Email, a `neutral` `Alert`
that **creating a user does not assign a role** (identity and authorization are separate screens on purpose),
and a `RadioGroup` of the three credential options with `temp` selected by default and badged "chosen",
`invite` disabled and badged "needs email". Submit is blocked until name and email are present; on success it
invalidates the users query and pushes an outcome toast carrying the server correlation id (`FE-D13`), with a
per-path detail line.

No optimistic update on deactivate (`FE-D20`, `shared/api/queryClient.ts` — there is no `onMutate` pattern
anywhere): the mutation invalidates the users query and the list re-reads server truth. Against the stateless
default fixture the row does not visibly flip, the same limitation `NewAssignmentDialog`'s create already has.

## Contract seam touched: `createUser` + `POST /admin/users` handler

`AdminRepository` gained `createUser(draft: CreateUserDraft): Promise<CreateUserResult>` (`CredentialPath` =
`'temp' | 'invite' | 'oob'`), built on `postWithCorrelation` exactly like `saveRole`/`createAssignment`.
`bootstrapFirstAdmin()` stays absent — the header still says "do not invent one". A new
`http.post('*/cp/admin/users')` MSW handler echoes the draft back as an active user with a fresh id, mirroring
the existing `POST /admin/assignments` stub. `AdminRepository` is `CONTRACT: TBD` (§16 Q5) — this is a fixture
handler, not a contract change.

## Wiring

- `AdminShell.tsx` — the `users` tab now renders `<UsersView repository={repository} />`; its placeholder
  branch, the `PLACEHOLDER_COPY` map and the now-unused `EmptyState` import are **deleted** (not extended),
  same as FE-091/FE-092/FE-093. No tab is a placeholder any more.
- `src/features/admin/users/index.ts` — new public surface: `UsersView`.
- `src/features/admin/index.ts` — unchanged; the shell re-exports what the route needs.
- `messages.en.ts` — new `adminUsers*` / `adminAddUser*` keys; removed the now-dead `adminPlaceholderUsers*`
  and `adminPlaceholderRoles*` keys (FE-091 had left the latter orphaned — this task removes the last
  placeholder branch, so the whole "not yet built" copy set goes).
- `adminRepository.test.ts` — a case for `createUser` (echoes the draft, active, non-empty correlation id).
- The six existing `AdminRepository` stubs (`roles`, `assignments`, `access` × unit + a11y) gained a
  `createUser: () => Promise.reject(new Error('not used'))` line.

## Testing

- `UsersView.test.tsx` (5) — lists users with status chips and offers no delete; Deactivate calls
  `setUserActive(id, false)` and Reactivate calls `setUserActive(id, true)`, the only lifecycle actions; the
  IdP banner states what disappears and what survives, and no bootstrap form is rendered (no "initial secret"
  field); the header action opens the Add-user dialog; a load failure shows the error panel and Retry
  recovers.
- `AddUserDialog.test.tsx` (4) — no role field, and a note that says identity ≠ authorization; `temp` checked
  by default with `invite` disabled, both badges present; Create is blocked until name + email, then calls
  `createUser` with `credentialPath: 'temp'` and toasts with the correlation id; picking "Out-of-band handoff"
  passes `credentialPath: 'oob'`.
- `UsersView.a11y.test.tsx` (4) — the list and the open Add-user dialog, axe-clean in light and dark. (The
  action column's header is a visually-hidden "Actions" label — an empty `th` fails axe `empty-table-header`.)
- `AdminShell.test.tsx` / `AdminShell.a11y.test.tsx` — the `users` tab now asserts the real `UsersView`
  heading (`Users`) instead of a placeholder; a11y `settle()` waits for it.
- `npm run lint` clean · `npm run typecheck` clean.
- `npx vitest run src/features/admin` — 15 files, 87 tests, green. `npm run test:a11y -- src/features/admin/users`
  — 4 tests, green. Full suite `npx vitest run` — 317 files, 1968 tests, green.
- Not done: live browser verification — no browser harness in this session. Behaviour is covered by the
  fixture-backed component tests above and follows `reference/app/admin.jsx`'s `UsersView` / `AddUserModal`
  closely (minus `BootstrapView`, deliberately — see `FE-D38`).

## Acceptance

- ☑ no delete — the only per-row control is Deactivate / Reactivate; no delete affordance exists anywhere in
  the view or the dialog.
- ☑ role not implied by creation — `AddUserDialog` has no role field and a standing `neutral` `Alert` states
  that creating a user does not assign a role; `createUser` takes only name / email / credentialPath.
- ☑ the chosen credential path is a recorded decision — `FE-D38` in
  [`docs/design/frontend/decisions.md`](../../../design/frontend/decisions.md), register row + full entry +
  changelog. The dialog renders all three options with `temp` chosen.

## Files

| File | Change |
|---|---|
| `src/features/admin/users/UsersView.tsx` | New — the users tab's content. |
| `src/features/admin/users/UsersView.types.ts` | New — `UsersViewProps`. |
| `src/features/admin/users/AddUserDialog.tsx` | New — Add-user dialog with the credential radio group. |
| `src/features/admin/users/index.ts` | New — public surface. |
| `src/features/admin/users/UsersView.test.tsx` | New. |
| `src/features/admin/users/AddUserDialog.test.tsx` | New. |
| `src/features/admin/users/UsersView.a11y.test.tsx` | New. |
| `src/features/admin/adminRepository.ts` | `CredentialPath`, `CreateUserDraft`, `CreateUserResult`, `createUser()`; header note for `FE-D38`. |
| `src/features/admin/adminRepository.test.ts` | `createUser` case. |
| `src/features/admin/AdminShell/AdminShell.tsx` | `users` tab renders `UsersView`; placeholder branch, `PLACEHOLDER_COPY`, `EmptyState` import deleted. |
| `src/features/admin/AdminShell/AdminShell.test.tsx` | Assert the real users view; drop the placeholder test. |
| `src/features/admin/AdminShell/AdminShell.a11y.test.tsx` | `settle()` waits for the Users heading. |
| `src/features/admin/{roles,assignments,access}/*.test.tsx` | `createUser` added to the six `AdminRepository` stubs. |
| `src/shared/testdoubles/handlers/cp.ts` | `POST /admin/users` handler echoes the draft. |
| `src/shared/i18n/messages.en.ts` | New `adminUsers*` / `adminAddUser*` keys; removed dead `adminPlaceholder*` keys. |
| `docs/design/frontend/decisions.md` | `FE-D38` — register row, full entry, changelog. |
| `docs/plans/frontend/task-list.md` | `094` → ✅; Phase 7 note on the recorded decision. |
| `docs/plans/frontend/execution.md` | `094` register row → ✅ (`FE-D38`). |

## Next

`095` (Targets preview) is the last Phase 7 task — `Ready: BLOCKED` beyond read-only (open items 1, 2). Phase 7
remains ⛔ not scheduled; issue #161 can close once verification passes.
