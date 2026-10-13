# FE-004 · Providers, router, route table, code splitting — Report

> **Provenance note:** this report was reconstructed retroactively (2026-08-20) from the commit
> record — `frontend` git history, the commit message on `9f541e8`, and the merged diff — not
> transcribed live during implementation. It documents what shipped and why, verified against the
> current working tree, rather than a blow-by-blow of the original session.

## What was implemented

The full route surface from design §14, lazily loaded, with providers composed in the fixed
order and no route carrying a tenant.

### Files created

- `frontend/src/app/routes.ts` — `ROUTE_PATHS` (the nine react-router path patterns, `explore`
  and `dashboard` declared as static-segment siblings of the dynamic `:category` pattern so
  react-router's static-over-dynamic ranking resolves `/monitoring/explore` to its own route
  rather than into `:category`) plus `routes.*` typed link builders, so a feature never
  hand-writes a template string. `INSTANCE_CATEGORIES`/`isInstanceCategory` live here too —
  `:category ∈ designer | governance | monitoring`, validated at render time.
- `frontend/src/app/router.tsx` — `appRoutes: RouteObject[]` (exported separately from the
  browser router so tests build a `createMemoryRouter` off the same definitions) and
  `router = createBrowserRouter(appRoutes)`. Each route is `React.lazy` with a `Suspense`
  fallback (`RouteFallback`) that reserves `minHeight: 100vh` so the swap causes no layout shift;
  an unmatched path (`path: '*'`) renders `NotFound` in place, never a redirect.
- `frontend/src/app/routes/InstanceCategoryRoute.tsx` — reads `:category` via `useParams`,
  renders `NotFound` for anything outside the three valid values rather than falling through to
  one of them (design §14's "an unknown value 404s rather than falling through").
- `frontend/src/app/routes/{LoginRoute,HomeRoute,SolutionsRoute,InstancesRoute,ExploreRoute,
  DashboardRoute,TargetsRoute,AdminRoute}.tsx` — one thin module per remaining route, each a
  default export rendering `RouteStub` so `React.lazy(() => import(...))` has a real per-route
  chunk to split on.
- `frontend/src/app/routes/RouteStub.tsx` — the shared placeholder content (`<main
  id="main-content"><h1>{title}</h1></main>`) every stub route renders, explicitly commented as
  existing only to prove the routing mechanism (resolution, chunk splitting, deep-linking) ahead
  of the Phase 3/4 tasks that build the real pages — deleted route-by-route as each real page
  lands (see the `project-frontend-deferred-placeholders` memory).
- `frontend/src/app/providers.tsx` — `AppProviders()`, composing
  `ThemeProvider → CssBaseline → QueryClientProvider → AuthProvider → RouterProvider` exactly per
  design §8.1. `AuthProvider` (a bare pass-through) and `createPlaceholderQueryClient()` (a bare
  `new QueryClient()`) are explicitly commented placeholders for FE-030 and FE-029 respectively —
  neither of those tasks was a dependency of this one, so the composition order needed to be
  correct today without the real providers existing yet.
- `frontend/src/app/NotFound.tsx` — the one "unknown route" / "invalid `:category`" target:
  `<main id="main-content" role="alert">`, real chrome deferred to `AppShell` (task 036).
- `frontend/src/app/router.test.tsx`, `routes.test.ts` — pattern-resolution tests (`matchRoutes`
  against all eleven concrete example paths, `:category` rejecting a garbage value, no route
  pattern containing "tenant") plus deep-link rendering tests using real `react-dom`/`act` and a
  `createMemoryRouter`, flushing Suspense until the lazy chunk resolves.

### Files modified

- `frontend/src/app/main.tsx` — now renders `<AppProviders />` instead of the FE-001 scaffold's
  bare `<App />`.
- `frontend/src/app/aliases.test.ts` — updated for the `App.tsx` removal (see below).

### Removed

- `frontend/src/app/App.tsx` — the FE-001 scaffold placeholder, superseded by the routing tree;
  its landmark/skip-link role is now `NotFound`'s (for the error path) and will be `AppShell`'s
  (task 036) for the happy path.

## Testing — as recorded in the commit message, re-verified against the current tree (2026-08-20)

Original commit message states: lint, typecheck, 72/72 unit tests (route pattern resolution via
`matchRoutes`, deep-link rendering via real `react-dom` + Suspense flushing, `:category`
validation), a production build confirming each route is its own chunk, and a headless-browser
pass against `vite preview` (11 routes, zero console errors, correct `NotFound` behaviour for
both an invalid `:category` and a fully unknown path).

Re-run for this backfill:
```
$ npm run lint && npm run typecheck && npm test -- src/app && npm run build
```
All green — `router.test.tsx` and `routes.test.ts` passing, `vite build` output shows one chunk
per lazy route (`LoginRoute-*.js`, `HomeRoute-*.js`, … each its own file).

## Status

DONE

## Commit

`33f5e91..9f541e8` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `004`.
