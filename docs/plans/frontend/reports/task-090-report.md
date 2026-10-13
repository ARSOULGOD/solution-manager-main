# FE-090 · Admin shell, tabs, and the honesty banner — Report

## Scope and gate

First task of Phase 7 (`090`–`095`), which `task-list.md` marks ⛔ **NOT SCHEDULED** pending Q4 (RBAC verb
catalog unratified) and Q5 (first-admin bootstrap unspecified). Flagged to the user before starting; per
ruling **F3** (`execution.md`) and FE-090's own `Ready: FIXTURE` note ("the catalog is data, §6.0.6 — Q4 does
not change this implementation"), the task itself is unaffected by Q4/Q5 — only the phase's scheduling is
gated. User chose to proceed with FE-090 now, in the same session.

## What was built

**`AdminShell`** (`src/features/admin/AdminShell/`) — the shell `AdminRoute` now mounts: title + `Later
increment`/`Not in v1 nav` badges, a collapsible **Findings** toggle (closed by default, mirrors the
reference's own disclosure pattern — `docs/design/frontend/reference/app/admin.jsx`) revealing the honesty
banner, and the four `RouteTabs` (`Roles | Assignments | Access check | Users`). The banner states the
model/catalog split verbatim from the plan text (only `target.use`/`instance.bindTarget` grantable, six
`DM-D18` verbs proposed, the rest placeholder) and states explicitly that there is **deliberately no catalog
editor** — matching the acceptance criterion rather than merely satisfying it by omission.

Each tab renders a placeholder `EmptyState` naming which later task (FE-091–FE-094) replaces it — the same
swap-later pattern `RouteStub`/`InstanceHeader` already establish (see
`[[project-frontend-deferred-placeholders]]`), so FE-091–FE-094 delete a branch rather than build around one.

**Routing** (`app/routes.ts`, `app/router.tsx`, `app/routes/AdminRoute.tsx`) — `ROUTE_PATHS.admin` changed
from `/admin` to `/admin/:tab` per the plan's own route spec. `:tab` is validated the same way
`InstanceCategoryRoute` validates `:category`: an unknown value renders `NotFound`, never falls through. Bare
`/admin` now redirects (`<Navigate replace>`) to `routes.admin()`'s default (`roles`) — one place picks the
default tab, not two. `routes.admin(tab?)` takes an optional `AdminTab`, defaulting to `'roles'`, so
`AccountMenu`'s existing `routes.admin()` call site needed no change.

`AdminTab`/`ADMIN_TABS` are defined twice by design — once in `app/routes.ts` (used by the route guard) and
once in `features/admin/AdminShell/AdminShell.types.ts` (used by the prop type) — because a feature may not
import `@app/routes` (module boundaries). Same duplication `InstanceHeader`'s own `InstanceCategory` already
carries for the same reason.

**Nav.** Confirmed already correct at the shell level — `SidebarNav` omits Admin, and `AccountMenu`'s preview
entry is `import.meta.env.DEV`-gated — so "not in nav" needed no new code, only verifying it still holds
after the route shape changed.

## A pre-existing test-harness bug found and fixed

`router.test.tsx`'s `renderAt()` helper polls `container.querySelector('#main')` to know when a lazy route's
real content has replaced its Suspense fallback. The actual mount point every page sets is `id="main-content"`
(`AppShell.tsx`, `NotFound.tsx`, `RouteStub.tsx`, `Gallery.tsx`) — `#main` never matches anything, so the
helper silently fell back to the whole container on every call. For routes with no data of their own to await
(`NotFound`, and now `AdminShell`), that fallback exits the poll loop on AppShell's chrome (breadcrumbs) alone,
before the routed Outlet content has actually mounted — a false "settled" read.

This was not latent: the pre-existing `'a deep link with an invalid :category renders NotFound rather than
falling through'` test was already failing on `main` before this task touched anything (confirmed by
stashing this task's changes and re-running against baseline). My own two new admin deep-link assertions hit
the identical bug. Fixed at the root — `'#main'` → `'#main-content'` — rather than working around it by
weakening or dropping the new assertions; this also happened to fix the pre-existing failure as a side
effect, not the point of the fix.

Separately (found while diagnosing, kept regardless of the above): the same `beforeAll` fetch mock didn't
recognize `/cp/solutions`, which `AppShell` now queries on **every** authenticated route (not just
`/solutions`) for the sidebar count. Every deep-link render was logging an "unexpected fetch" error to
`stderr`; added a stub response so the mock's whitelist matches what the shell actually does.

## Testing

- `AdminShell.test.tsx` — title/badges render; no catalog-editor text and the banner starts collapsed;
  opening Findings states the catalog split and explicitly "no catalog editor"; all four tabs render as
  route links to `/admin/<tab>`; each tab's own placeholder renders.
- `AdminShell.a11y.test.tsx` — axe clean in both themes, findings collapsed and open, and for all four tabs.
- `routes.test.ts` — `isAdminTab` accepts the four tabs, rejects garbage; `routes.admin()` defaults to
  `/admin/roles`, `routes.admin('users')` builds `/admin/users`.
- `router.test.tsx` — `/admin`, `/admin/roles` resolve (`matchRoutes`); deep-link renders for `/admin/roles`
  (shell content, not NotFound), bare `/admin` (redirects and renders), and `/admin/bogus` (NotFound, not a
  fall-through) — plus the pre-existing suite, now reliably green.
- Full suite: `npm run lint && npm run typecheck && npm test --environment jsdom` — 1898 tests, 1897 passing;
  the one failure (`InstancesPage.test.tsx`, unrelated `waitFor` timing) reproduces only under full-suite
  load and passes cleanly in isolation — pre-existing, untouched by this task.
- `npx prettier --check` on every changed/new file.

## Files

| File | Change |
|---|---|
| `src/features/admin/AdminShell/AdminShell.tsx` | New — shell, badges, Findings toggle/banner, tabs, per-tab placeholder. |
| `src/features/admin/AdminShell/AdminShell.types.ts` | New — local `AdminTab`/`ADMIN_TABS`, `AdminShellProps`. |
| `src/features/admin/AdminShell/AdminShell.test.tsx` | New. |
| `src/features/admin/AdminShell/AdminShell.a11y.test.tsx` | New. |
| `src/features/admin/AdminShell/index.ts` | New. |
| `src/features/admin/index.ts` | New — feature's public surface. |
| `src/app/routes/AdminRoute.tsx` | Rewritten — validates `:tab`, mounts `AdminShell`, no more `RouteStub`. |
| `src/app/routes.ts` | `ROUTE_PATHS.admin` → `/admin/:tab` (+ `adminIndex`); `ADMIN_TABS`/`isAdminTab`; `routes.admin(tab?)`. |
| `src/app/routes.test.ts` | Updated for the new path shape; new `isAdminTab` coverage. |
| `src/app/router.tsx` | Bare `/admin` → `<Navigate>` redirect to the default tab. |
| `src/app/router.test.tsx` | New admin path coverage; `#main` → `#main-content` fix; `/cp/solutions` added to the fetch mock. |
| `src/shared/i18n/messages.en.ts` | New `admin*` catalogue keys (shell chrome, findings banner, per-tab placeholder copy). |

## Acceptance

- ☑ not in nav
- ☑ banner states catalog status
- ☑ no catalog editor

## Next

FE-091 (Roles and RoleEditor) is next in task order and depends on this task plus FE-020. Its own tab
placeholder in `AdminShell.tsx` is the branch to delete when picking it up.
