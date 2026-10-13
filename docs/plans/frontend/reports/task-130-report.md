# FE-130 · Monitoring sub-tabs (Dashboards · Explore) — Report

## What this closes

Plan `126`'s **second** defect, and the one with the clearest user consequence: *"there is no way to add a
metric to a dashboard."*

Explore was reachable from exactly two places, and both were **empty states** — `DashboardView`'s
layer-filtered `EmptyState` and `MonitoringFirstRun`. So the moment a dashboard had a single widget on it,
the screen that builds widgets became unreachable. The console could show you a dashboard, and it could
build you a first widget, but it could never build you a second one.

## The `end` prop, and why it was required first

`RouteTabs` resolved the active tab with

```ts
tabs.find((tab) => location.pathname === tab.to || location.pathname.startsWith(`${tab.to}/`))
```

`/monitoring` is a path prefix of `/monitoring/explore`, so on Explore **both** tabs matched — and `find`
returns the first, so the wrong one would have won. A two-tab Monitoring bar was not possible until this was
fixed.

`RouteTab` gains an optional `end?: boolean`, mirroring react-router's own `NavLink` `end`: match `to`
exactly, never as a prefix. It defaults to `false`, so every existing tab bar keeps prefix matching and a
nested route still activates its parent tab — pinned by a test using `/designer/section/cache`.

Written test-first per `superpowers:test-driven-development`: the exact-match case failed against the old
implementation (`expected 'false' to be 'true'`) before the fix.

## `MonitoringTabs`

A two-entry bar — **Dashboards** (`end: true`) and **Explore** — built on `RouteTabs`, rendered at the top
of `DashboardView` (both `landing` and standalone) and `ExplorePage`. This is the reference's `.itabs` row,
which `dashboards.jsx` and `explore.jsx` both carry.

`monitoringTabs(solutionId, instanceId)` is exported separately from the component so the route shapes can
be asserted without rendering. Hrefs are hand-written rather than imported from `@app/routes`, which feature
code may not import (module boundaries) — the same pattern `DashboardView` and `ExplorePage` already used.

The gap under the bar lives inside `MonitoringTabs`, not in each caller: both screens then sit the same
distance below it, and `shared/ui`'s `Stack` takes no `sx`, which is the other reason it cannot be the
caller's job.

**Not wrapped in `InstanceShell`**, which is what the reference does — that needs all five instance
repositories on these routes and would create a `@features/monitoring` → `@features/instance` import against
the existing edge in the other direction, an `eslint-plugin-boundaries` cycle. Plan `126` names this out of
scope; the bar gets the reachability without it.

## Two ad-hoc back links removed

Both screens carried a one-way "← Monitoring" link. The bar routes **both** ways from a fixed position on
both screens, so those links became a second, weaker copy of the same affordance and were removed, along
with their two now-orphaned i18n keys (`dashboardBackToMonitoringAriaLabel`,
`exploreBackToMonitoringAriaLabel`) — dead strings are worse than none.

The two tests that asserted those links were **rewritten, not deleted**. What they exist to protect is still
protected, and asserted against the bar: Explore always routes back to the instance's Monitoring category,
the landing view doesn't duplicate chrome it is already inside, and no stale "Back to Monitoring" link
remains. Two cases were added on top: Explore is reachable from a *populated* dashboard, and only Explore is
selected on the Explore route (the regression the `end` prop exists to prevent).

## One deviation from the plan's text

> "Gallery entry + `.test.tsx` + `.a11y.test.tsx` alongside, matching the sibling monitoring components."

There is no gallery entry, because **no feature component has one** — `find src/features -name "*.gallery.tsx"`
returns nothing, while `src/shared/ui` has 31. The gallery (task 010's registry) is a `shared/ui` catalogue.
Following the sibling monitoring components literally means test + a11y test, which is what was written; a
gallery entry would have been the one exception in the tree, not a match for it.

## Files

| File | Change |
|---|---|
| `src/shared/ui/RouteTabs/RouteTabs.types.ts` | New optional `end` on `RouteTab`. |
| `src/shared/ui/RouteTabs/RouteTabs.tsx` | Honour `end` in the active-tab match. |
| `src/shared/ui/RouteTabs/RouteTabs.test.tsx` | Three new cases (exact match, own path, prefix still default). |
| `src/features/monitoring/MonitoringTabs.tsx` | New — the bar plus `monitoringTabs()`. |
| `src/features/monitoring/MonitoringTabs.test.tsx` · `.a11y.test.tsx` | New — 6 + 4 cases. |
| `src/features/monitoring/dashboard/DashboardView.tsx` | Wrapped in the bar; back link removed. |
| `src/features/monitoring/explore/ExplorePage.tsx` | Wrapped in the bar; back link removed. |
| `src/features/monitoring/dashboard/DashboardView.test.tsx` · `explore/ExplorePage.test.tsx` | Back-link cases rewritten against the bar. |
| `src/shared/i18n/messages.en.ts` | Three tab keys added; two orphaned back-link keys removed. |

## Verification

- `npm run typecheck` — clean
- `npm run lint` — clean at `--max-warnings=0`
- `npx prettier --check` — clean on every touched path
- `MonitoringTabs.a11y.test.tsx` — 4 passed (both routes × light/dark), axe clean
- `src/features/monitoring` + `src/shared/ui/RouteTabs` — 68 files, 489 tests, all passed
- `npm test` — **326 files, 2212 tests, all passed** (up from 2198)
