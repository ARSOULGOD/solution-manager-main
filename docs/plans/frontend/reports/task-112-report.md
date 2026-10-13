# FE-112 · Sidebar solution list wired to the catalog — Report

## What I implemented

One operator-reported defect: the navy drawer read **"Solutions (0)"** on every screen — including
`/solutions` itself, while three package cards rendered behind it (task spec:
`docs/plans/frontend/implementation.md` §FE-112; refs `UI-D8`, `UI-D11`, UI §A.2).

### Root cause

Not a stale or mis-derived count — a literal. `AppShell.tsx` held

```ts
const NO_SOLUTIONS: readonly SidebarNavSolution[] = [];
…
<SidebarNav solutions={NO_SOLUTIONS} />
```

and `SidebarNav` defaulted `const count = solutionCount ?? solutions.length` → `0`. Task 036's own
file header records why it shipped that way: at the time no task-036 file owned a plane-client
fetch, and inventing one would have duplicated the client-distribution mechanism Home (038) and the
Solutions catalog (039) still had to establish. Those tasks landed and established it; the
placeholder was never removed. The header comment even names the failure mode it was avoiding —
"fabricate a `0` count `UI-D8` forbids" — which is exactly what shipped.

| File | Summary |
|---|---|
| `src/features/inventory/solutions/sidebarSolutions.ts` | **New.** `toSidebarSolutions()` (filter `devMode`, project to `{id, name, instanceCount}`) and `countDistinctSolutions()`. Both rules are taken from the reference shell, not invented — see Ruling 2. |
| `src/features/inventory/solutions/index.ts` | Exports both from the feature barrel — `app` may import a feature, and this keeps the derivation on the feature side of the boundary rather than in `app/shell`. |
| `src/app/shell/AppShell.tsx` | Takes an optional `solutionsRepository`; runs `useQuery({ queryKey: queryKeys.solutions.list() })` against it and passes the derived list and count to `SidebarNav`. Same key `SolutionsPage` uses, so drawer and catalog share one cache entry and one request. Header comment replaced (it documented the now-removed limitation). |
| `src/app/routes/AuthenticatedLayout.tsx` | Builds the repository from `RuntimeConfig` and injects it — the same reason `SolutionsRoute.tsx` builds its own: `RuntimeConfig` is app-layer and `app/shell` is not where deploy-time config is read. |
| `src/shared/ui/SidebarNav/SidebarNav.tsx` | Renders the parenthesised count only when `solutionCount` is supplied. The old `?? solutions.length` fallback was the mechanism of the bug's second half. |
| `src/shared/ui/Breadcrumbs/Breadcrumbs.types.ts` | `solutionCount?: number \| undefined` (explicit `undefined` — the project runs `exactOptionalPropertyTypes`), documented as "omit while unknown". |
| `src/shared/ui/SidebarNav/SidebarNav.gallery.tsx` | Passes `solutionCount={2}` so the gallery still demonstrates the counted state. |
| `src/app/shell/AppShell.test.tsx` | Two new tests (below) plus a fixture-backed repository in `renderShell()`. |

## Rulings

1. **Injected repository, not a fetch inside `AppShell`.** `AppShell` lives in `app/shell` and cannot
   read `RuntimeConfig` the way a route can — `AppShell.test.tsx` and `AppShell.a11y.test.tsx` mount
   it outside `RuntimeConfigBoot`, so `useRuntimeConfig()` there would throw. `AuthenticatedLayout`
   already reads that config for `idleTimeoutMinutes`, so it is the natural construction site. It is
   also where the query is guaranteed to run **inside** `RequireAuth`: putting the `useQuery` in
   `AuthenticatedLayout` itself would fire it before the auth gate, since children are elements
   passed as props and hooks in the parent run regardless.

2. **Count solutions, not packages; omit dev-mode from the sub-list.** Both rules come from the
   reference shell (`docs/design/frontend/reference/app/shell.jsx`), which computes
   `new Set(SOLUTIONS.map(s => s.solution)).size` and renders `SOLUTIONS.filter(s => !s.devMode)`.
   The fixtures make the distinction visible: three packages, two solutions — `cache` appears twice
   (release + nightly). `solutions.length` would have read `3`, and the nightly would have appeared
   in the nav as a third entry. The nav link stays keyed on the *package* id, because that is what
   `/solutions/:solutionId/instances` resolves against.

3. **No count at all while unknown, rather than `(0)`.** `UI-D8` forbids a fabricated empty, and a
   loading or errored catalog is not "no solutions". `solutionCount` is therefore optional and
   `SidebarNav` renders a bare "Solutions" when it is absent. This changed two existing call sites
   (the gallery and the standalone-mounted shell) and is the one behaviour change beyond the fix
   itself. The catalog screen keeps ownership of the error surface — the drawer stays silent rather
   than becoming a second error report.

4. **`enabled: false` queryFn throws rather than resolving `[]`.** TanStack still requires a
   function on a disabled query. Resolving `[]` would seed the *shared* `solutions.list()` cache
   entry with a fabricated empty catalog that `SolutionsPage` would then read — the same class of
   defect this task is fixing. Throwing is unreachable and honest.

## Verification

| Check | Result |
|---|---|
| `npm run typecheck` | clean |
| `npm run lint` | clean (`--max-warnings=0`) |
| New/changed tests | `AppShell.test.tsx` (19), `sidebarSolutions.test.ts` (4) — pass |
| **Both new tests fail against the pre-fix component** | confirmed by temporarily restoring `<SidebarNav solutions={NO_SOLUTIONS} solutionCount={NO_SOLUTIONS.length} />` — 2 failed / 17 passed |
| Full suite | 1062 passed / 1 failed — `InstancesPage.a11y.test.tsx > populated grid … dark mode`, an axe timeout under worker-pool saturation. Passes in isolation (6/6); the file's own header documents this load-dependent budget, and commit `20acd0d` addressed the same class. Untouched by this change. |
| Real browser (`npm run dev`, MSW fixtures, Chromium 1440×900) | drawer reads `Solutions (2)` → `Cache Engine (12 instances)`, `File Mesh (0 instances)`; no nightly entry; identical on `/` and `/solutions`; no console errors |

## Follow-ups (not done here)

- `src/features/inventory/solutionsRepository.ts` (+ its test) at the `inventory/` root is a dead
  duplicate of `inventory/solutions/solutionsRepository.ts`, imported by nothing but its own test.
  Out of scope for a defect fix; worth deleting deliberately.

---

## Revision — 2026-08-26

The drawer's solution entries gained a glyph (carried through from `SolutionPackageSummary.icon`, so the
drawer and the catalog card show one mark) and a green selection/health dot that follows the current
route. Written up with the rail collapse it shipped alongside, in
[`task-111-report.md`](task-111-report.md) (Revision — 2026-08-26).
