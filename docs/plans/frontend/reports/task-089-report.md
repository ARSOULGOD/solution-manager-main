# FE-089 · Monitoring first-run — Report

## What I implemented

Refs Dec `DM-D9` · `dynamic-metrics` §11.1 · UI §A.11 (monitoring first run). Deps `085, 019` (both done).

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/dashboard/MonitoringFirstRun.tsx` (new) | What `DashboardView` renders in place of the widget grid when the dashboard has **zero widgets, full stop** — mirrors the visual reference's own `FirstRunMonitoring` in `docs/design/frontend/reference/app/dashboards.jsx`. Reuses `EmptyState` for the icon/title/body, and composes a per-entity-type tile row (instance count, replica count, metric count) plus a single `Open Explore` `Button` into its `action` slot. Per entity type, `EntityTypeTiles` runs its own `useMetricInstances`/`useMetricCatalog` queries (independent per entity type — one slow catalog fetch never blocks another's tiles), rendering a `Skeleton` at each count's own place while pending rather than a flash of `0`. |
| `frontend/src/features/monitoring/dashboard/icons.tsx` (new) | Four inline `SvgIcon`s (`ActivityIcon`, `BoltIcon`, `ServerIcon`, `ChartIcon`, `SearchIcon`) — `@mui/icons-material` is not a project dependency, same reasoning `features/home/icons.tsx` and `shared/ui/internal/toneIcons.tsx` already state. |
| `frontend/src/features/monitoring/dashboard/MonitoringFirstRun.test.tsx`, `.a11y.test.tsx` (new) | Title/body copy, tile counts against the real fixture data (`CACHE_INSTANCES`/`METRIC_CATALOG`, computed in the test rather than hardcoded), exactly one `Open Explore` action, graceful degradation to zero tiles (but still one action) when `entity-types` reports none, and axe-clean in both themes. |
| `frontend/src/features/monitoring/dashboard/DashboardView.tsx` (modified) | Adds a new branch **above** the existing layer-filter empty check: `dashboard.widgets.length === 0` → `<MonitoringFirstRun>`. Deliberately distinct from `shown.length === 0` (the pre-existing branch, unchanged) — a layer filter that hides an otherwise-populated dashboard is not first-run; the fix there is "change the filter", not "go build something in Explore", so it keeps the plain `EmptyState` it already had. |
| `frontend/src/features/monitoring/dashboard/DashboardView.test.tsx`, `.a11y.test.tsx` (modified) | New case proving the two branches don't collide: `widgets: []` renders `MonitoringFirstRun` ("No dashboards saved yet"), never the old "No widgets saved yet" text. The a11y file's old "empty state" case (which happened to send `widgets: []` and therefore now exercises the *new* first-run branch) is renamed to say so and re-asserted against the new title; a second a11y case was added, rebuilt from the real layer-filter interaction (select the `Shared` layer against an all-`product-default` fixture) to keep that distinct state's own a11y coverage. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | Added `monitoringFirstRunTitle`/`Body`/`ReplicasLabel`/`MetricsLabel`/`Action` (5 keys), copied verbatim from the visual reference's own `FirstRunMonitoring` strings. |
| `docs/plans/frontend/task-list.md`, `docs/plans/frontend/execution.md` (modified) | Marked `089` done. |

## Design decisions, and why

1. **True first-run vs. a layer filter that hides everything are two different states, and the existing code already conflated them.** Before this task, `DashboardView`'s only empty check was `shown.length === 0` (post-layer-filter) — so filtering an otherwise-populated dashboard down to nothing showed the exact same "No widgets saved yet" copy a genuinely empty dashboard would. Task 089's acceptance criteria (`no empty grid`, `counts shown`, `exactly one primary action`) only make sense for the *genuinely* empty case — the entity-type tiles would be actively misleading on a dashboard that has widgets, just not in the selected layer. The fix checks `dashboard.widgets.length` (pre-filter) for the new branch and leaves the post-filter check exactly as it was for the other case. `DashboardView.test.tsx`'s pre-existing "filters the widget list by layer" test needed no change — proof the old behavior for that case is untouched.

2. **One tile-row per entity type, generalizing the reference's own hardcoded three tiles.** The reference literally hardcodes `[["bolt","cache-engine","12"],["server","replicas","31"],["chart","metrics","6"]]` for the one entity type its mock data has. Rather than hardcode "three tiles," `MonitoringFirstRun` maps over whatever `useEntityTypes()` actually returns, rendering an (instances, replicas, metrics) triple per entity type — a change of zero behavior against today's one-entity-type fixture (the browser screenshot below is pixel-identical in tile count to the reference), but not a hardcoded assumption that only survives until a second entity type ships. This mirrors `ExplorePage.tsx`'s own documented posture on the same fixture limitation ("today, fixture-backed, always the one `cache-instance` entry... flagged, not silently decided") rather than inventing new scope (no `EntityTypeSelector`, no picker — just "loop over what the catalog says exists").

3. **Replica count is computed client-side as `Σ replicaCount` across `useMetricInstances(entityType.id).items`, not read off a field that doesn't exist.** Same `replicaCount` field task 078's series-cap projection already reads off `MetricInstance` (`metricsRepository.ts` — itself flagged `CONTRACT: TBD`, no contract source). No new invented field; just a second consumer of one that already exists.

4. **Metric count is `useMetricCatalog({ entityType: entityType.id }).length`** — the repository's `metricCatalog` route already accepts an `entityType` filter (`CatalogQuery`), so this is per-entity-type from day one rather than a single global count that would become wrong the moment a second entity type exists.

## Residuals (flagged, not silently decided)

Same open items `DashboardView.tsx`'s own header already carries — this task adds no new contract gaps. `entity-types`/`entity-types/{t}/instances`/`metrics/catalog` are all SPECIFIED routes (`dynamic-metrics` §5.5); nothing here reads an unspecified shape.

## Testing

Every command run from `frontend/`:

```
npm run lint                                                     # eslint . --max-warnings=0 — clean
npm run typecheck                                                 # tsc -p tsconfig.json && tsc -p tsconfig.node.json — clean
npm run build                                                      # vite build + assert-echarts-chunk — clean
npx vitest run src/features/monitoring/dashboard --environment jsdom   # 17 files, 109/109
npx vitest run src/features/monitoring --environment jsdom             # 63 files, 443/443
npx vitest run --environment jsdom                                     # full suite — 302 files, 1858/1858
```

**Browser-verified**, not just tested. The fixture MSW handler for `GET /console/v1/dashboards/:dashboardId`
always returns two widgets (and the fixture's `/reset` route is stateless — it answers the `POST` correctly but a
follow-up `GET` still replays the same static two-widget document, a pre-existing fixture limitation, not this
task's bug). To browser-verify the actual first-run rendering, `console.ts`'s dashboard handler was **temporarily**
edited to return `widgets: []`/`layout: []`, verified against the real `npm run dev` bundle with Playwright, then
reverted (`git checkout -- src/shared/testdoubles/handlers/console.ts` — confirmed clean via `git status`/`git
diff --stat` before committing anything).

- `/solutions/cache/instances/cache-prod-01/monitoring` renders the first-run view: "No dashboards saved yet", the
  reference's own body copy, three tiles (**Cache instance: 12**, **replicas: 24**, **metrics: 8** — matching
  `CACHE_INSTANCES`/`METRIC_CATALOG` fixture sizes), and exactly one `Open Explore` button.
- An early screenshot caught a real layout bug: the `Grid` primitive sizes columns off its own container's width
  (task 105), and a `flexDirection:'column', alignItems:'center'` wrapper shrinks a block child to its content
  width instead of stretching it — the tile row was rendering as 1 column instead of 3 even in a ≥600px space.
  Fixed by wrapping the `Grid` in an explicit `width:'100%'` `Box`; re-verified 3-across afterward.
- Clicking **Open Explore** navigates to `/solutions/cache/instances/cache-prod-01/monitoring/explore` — confirmed
  via `page.waitForURL`.
- Verified in both light and dark mode (`emulateMedia({ colorScheme: 'dark' })`); no visual regressions.
- Console/page-error listener: empty — no errors across the run.

## Status

Done. Commit: `e09fab7`.
