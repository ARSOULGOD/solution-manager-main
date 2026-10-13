# FE-085 · DashboardView and toolbar — Report

## What I implemented

Refs Dec `DM-D21` `DM-D11` `DM-D17` `DM-D18` · Design §12 · `dynamic-metrics` §9.1/§9.2/§14 · UI §A.11. Deps `084, 081`.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/dashboard/refreshPolicy.ts` (new) | Pure `DM-D17` rules: `REFRESH_INTERVAL_SECONDS` (`off·10s·30s·1m·5m`), `estimatedBucketSeconds(rangeMs, tiers)` (a client-side estimate of the tier §8.4's routing would serve — `≤7d raw · 7–90d hourly · >90d daily` — against the REAL, capabilities-advertised `minBucketSeconds` per tier, never a hardcoded bucket literal), `flooredRefreshSeconds` (`off` always stays off), and `refreshDisabledForRange` (a `custom` range has nothing new to fetch). Grounded in architecture §8.4's own published cutoffs and the real fixture tier shape (`raw`/`hourly`/`daily`), not invented. |
| `frontend/src/features/monitoring/dashboard/refreshPolicy.test.ts` (new) | Tier-boundary cases at exactly 7d/90d; flooring up, leaving alone, and "off never turns on"; disabled-for-custom; the closed-set guard. |
| `frontend/src/features/monitoring/dashboard/DashboardToolbar.tsx` (new) | Time range (`TimeRangePicker`, task 081) · refresh (`off/10s/30s/1m/5m`) · resolution (`ResolutionControl`, task 081, Auto/Raw gated on `capabilities.features.forceRaw`) · layer filter (`all`/`product-default`/`shared` — **two layers only**, matching the walking skeleton's scope per design §12; `entity`/`tenant`/`personal` wait on `DM-D5`). Presentational and controlled; states why refresh is disabled (custom range), paused (tab hidden — takes priority over the floor note), or floored, never more than one reason at once. |
| `frontend/src/features/monitoring/dashboard/DashboardToolbar.test.tsx`, `.a11y.test.tsx` (new) | Every control renders and wires through; Raw omitted/offered per `forceRawAdvertised`; each of the three refresh notes (disabled/paused/floored) renders alone and cedes to the higher-priority one; a11y clean in both modes across the default, Raw-advertised+floored, and disabled-refresh states. |
| `frontend/src/features/monitoring/dashboard/DashboardView.tsx` (new) | Owns the dashboard-wide toolbar state and fetches `dashboardRepository.get(dashboardId)`. Time range is URL state (`?range=`/`from`/`to`, same scheme `ExplorePage` already uses) — routing.md's own table says "dashboard; range in query". Refresh/resolution/layer are session-only local state (see residual 2 below). Renders, in order: the `!landing` back-link+title (standalone route only), the landing note (`landing` only — "Monitoring renders the package's default dashboard directly… a dashboards index isn't assumed"), the always-on authoritative note (quoting the task brief verbatim: "the dashboard's range and refresh are authoritative for every widget… there is deliberately no per-widget time pinning"), the toolbar, then the body (loading skeleton / `ErrorPanel` with retry / empty state with an Explore CTA / a widget list). Tracks `document.hidden` via a `visibilitychange` listener to state a real paused reason (`DM-D17`). **No grid, no charts** — the widget list is title/mark/metric/layer per row, explicitly flagged as `DashboardGrid`/`WidgetFrame`'s (task 086) job to replace. |
| `frontend/src/features/monitoring/dashboard/DashboardView.test.tsx`, `.a11y.test.tsx` (new) | Toolbar renders; both on-screen notes; landing note present only when `landing`; back-link present only when `!landing` (and points at `/monitoring`); real fixture widgets list by title/mark/metric/layer; layer filter narrows to the empty state (the fixture's two widgets are both `product-default`); loading skeleton; MSW-forced error + retry; custom range disables refresh with the stated reason; **end-to-end floor check** — at the default 24h range (raw tier, fixture `minBucketSeconds: 15`) requesting `10s` floors to `15s`; a real `visibilitychange` dispatch shows the paused note. A11y clean (light/dark) for landing, standalone, empty, and error states. |
| `frontend/src/features/monitoring/index.ts` (modified) | Exports `DashboardView` and `defaultDashboardId` (task 084) — the two new things `@app/routes` and `@features/instance` need through the public barrel (module boundaries). |
| `frontend/src/app/routes/DashboardRoute.tsx` (modified) | Was a `RouteStub` placeholder. Now mirrors `ExploreRoute.tsx`: builds `consoleClient`, mounts `MetricsProvider`, renders `DashboardView` with `landing={false}` and the URL's own `:dashboardId` used verbatim (not recomputed — see decision 3 below). |
| `frontend/src/app/routes/InstanceCategoryRoute.tsx` (modified) | Now also builds a `consoleClient` (same `useMemo` as the `dpClient`-derived repositories) and passes it to `InstanceShell` as a new required prop. |
| `frontend/src/features/instance/InstanceShell.tsx` (modified) | The Monitoring category branch no longer renders `t('instanceMonitoringPlaceholder')`. It mounts `MetricsProvider` + `DashboardView` with `dashboardId={defaultDashboardId(instanceId)}` and `landing`. |
| `frontend/src/features/instance/InstanceShell.test.tsx`, `.a11y.test.tsx` (modified) | Replaced the three placeholder-text assertions with real-content assertions (`Hit rate` from the fixture dashboard, the landing note); added a `consoleClient` to every render call; added a dedicated monitoring a11y case (designer/governance already had one each). |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | Added the `dashboardToolbar*`/`dashboardView*`/`dashboardBackToMonitoringAriaLabel` keys (~20). Removed `instanceMonitoringPlaceholder`, now dead. |
| `docs/plans/frontend/task-list.md`, `docs/plans/frontend/execution.md` (modified) | Marked `085` done. |

## Design decisions, and why

1. **`estimatedBucketSeconds` is a client-side estimate, stated as one in its own header, not a "served" value.** No widget query runs from this task — `DashboardGrid`/`WidgetFrame` (086) is what actually queries a metric and gets back a real `QueryResponse.resolution.bucketSeconds`. But `DM-D21` already guarantees every widget on one dashboard shares one range, and architecture §8.4's tier-routing cutoffs (`≤7d raw · 7–90d hourly · >90d daily`) are a pure function of range length, not of the metric — so the tier every widget on this dashboard WOULD resolve to is the same one this estimate picks, using the real capabilities-advertised `minBucketSeconds` per tier (never a hardcoded bucket literal). This is what lets the acceptance box's "refresh floored" be real and end-to-end-tested today rather than deferred, without inventing a bucket-second constant nowhere in the design states (`I2`).

2. **`refreshSeconds`/`resolution`/`layer` are session-only local state; only `timeRange` is seeded from anything besides a stated default.** `dashboardRepository.ts`'s own header marks the WHOLE `DashboardDoc` shape `CONTRACT: TBD`, including its `range`/`refresh` fields — seeding the toolbar's initial refresh from `dashboard.refresh.intervalSeconds` would be guessing at a meaning the contract never stated (is it "last operator setting", "package-suggested default", something else?). `timeRange` defaults to `24h` the same way `ExplorePage`'s own does, for the same reason. Flagged in the file's own header, not silently decided — a future task can seed these once the contract says what the doc-level fields mean.

3. **`DashboardRoute.tsx` uses the URL's `:dashboardId` verbatim, never recomputing `defaultDashboardId(instanceId)`.** Today they're always equal (`SM-D31`: one per-instance dashboard), but trusting the param is what lets a second dashboard id work at this route without a code change, and it matches how every other dynamic-segment route in this app already treats its params as the source of truth.

4. **The widget list (loaded state) is title/mark/metric/layer rows, not a grid and not charts.** `DashboardGrid`/`WidgetFrame` is task 086's own title and explicit scope; building even a minimal chart here would be doing 086's job with 086's own dependencies (`006`, `072`) not yet wired into this task. The list still proves the toolbar's layer filter, the fetch, and the loading/error/empty states end-to-end against the real fixture — task 086 replaces the `Paper`/row block with a real grid and has real widgets to lay out when it does. Flagged in the file's own header, same posture task 084's `migrations.ts` residual already set.

5. **Layer filter offers exactly `All layers`/`Package defaults`/`Shared` — no `entity`/`tenant`/`personal`.** Design §12 is explicit that the walking skeleton implements `instance`-scoped dashboards with `product-default ∪ shared` only, because `DM-D5`/`SM-D31`'s scope model is still contested (`DECISIONS.md` conflicts table) — building UI surface for a scope that ratification could still reject is exactly what §12 says not to do.

6. **`DashboardView` renders its own back-link/title only when `!landing`.** When embedded in `InstanceShell` (landing), `InstanceHeader`'s own tabs already establish page context — a second title would be redundant. When standalone (`/monitoring/dashboards/:id`), there is no such chrome, so this file supplies the same minimal back-link `ExplorePage.tsx` already established for its own standalone route (`ExplorePage.tsx`'s own header names this exact residual: no `InstanceShell`, no tabs, no health chip — `DashboardView` inherits the same posture rather than inventing a different one).

7. **The three refresh notes (disabled / paused / floored) are mutually exclusive, in that priority order.** Disabled (custom range) and paused (tab hidden) are both reasons NOTHING is happening; floored is a reason something IS happening, just not at the exact requested cadence. Showing more than one at once would either contradict itself (disabled + floored) or bury the more urgent fact (paused matters more than "would have floored to Ns" once nothing is polling anyway).

## Residuals (flagged, not silently decided)

1. **No grid, no charts.** `DashboardGrid`/`WidgetFrame` (task 086) turns `DashboardDoc.widgets` into a real 12-column layout with live per-widget queries; this task's widget list is a real, tested stand-in, not a preview of 086's own UI.
2. **`dashboard.range`/`dashboard.refresh` are read from nowhere.** Both exist on `DashboardDoc` (and the fixture returns real values for them) but their intended MEANING is `CONTRACT: TBD` in `dashboardRepository.ts`'s own header. Decision 2 above explains why seeding from them was declined rather than guessed at.
3. **`estimatedBucketSeconds` stops being authoritative once 086 lands.** Each widget's own served `bucketSeconds` (from a real `QueryResponse`) is the true floor for THAT widget once 086 wires per-widget queries; this estimate only floors the one dashboard-wide control before any of them has answered. Flagged in `refreshPolicy.ts`'s own header.
4. **Hidden `product-default` widgets are excluded from the list with no restore affordance.** FE-087 ("Layers: badge, filter, hide-not-delete, reset") owns hide/restore; this task only counts and states how many are hidden (`dashboardViewHiddenCountNote`), consistent with 087 depending on 086 (which this task also doesn't build).

## Testing

Every command run from `frontend/`:

```
npm run typecheck   # tsc -p tsconfig.json && tsc -p tsconfig.node.json — clean
npm run lint        # eslint . --max-warnings=0 — clean
npm run build       # vite build + assert-echarts-chunk — clean, echarts still isolated to its own chunk
npx vitest run src/features/monitoring/dashboard/   # 5 files, 45/45
npx vitest run src/features/instance/               # 4 files, 21/21
npx vitest run src/app/                             # 20 files, 218/218
npx vitest run                                      # full suite — 291 files, 1797/1797
```

**Browser-verified**, not just tested — a throwaway Playwright script (discarded, not committed) drove
`npm run dev -- --port 5187 --strictPort` against the real msw fixture backend end to end:

- `/solutions/cache/instances/cache-prod-01/monitoring` (the Monitoring category landing, embedded in
  `InstanceShell`) renders the landing note, the authoritative note, and both fixture widgets ("Hit rate",
  "Cache size").
- Filtering the layer control to "Shared" — the fixture's two widgets are both `product-default` — renders the
  "No widgets saved yet" empty state, not a crash.
- Setting Refresh to "10s" at the default 24h range renders "Flooring to 15s…" (raw tier, the fixture's real
  `minBucketSeconds: 15`) — the end-to-end floor, not just the unit-tested function.
- Switching Time range to "Custom range" disables Refresh and states why.
- The standalone route (`/solutions/cache/instances/cache-prod-01/monitoring/dashboards/db_cache-prod-01`) renders
  a "← Back to Monitoring" link and does **not** show the landing note.
- Console/page-error listener: empty — no errors across the whole run.

## Status

Done. Commit: `a260497`.
