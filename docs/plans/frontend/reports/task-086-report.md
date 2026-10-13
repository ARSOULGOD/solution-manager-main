# FE-086 · DashboardGrid and WidgetFrame — Report

## What I implemented

Refs Dec `DM-D6` `FE-D20` · Design §12, §17 · `dynamic-metrics` §8, §13, §17 · UI §A.11 (widget cell). Deps `085, 006, 072`.

| File | Summary |
|---|---|
| `frontend/src/shared/ui/WidgetErrorBoundary.tsx`, `.test.tsx` (moved from `frontend/src/app/errors/`) | `WidgetFrame` is a `@features/monitoring` component and the module-boundaries policy (`eslint.config.js`) has no `feature -> app` edge — only `app -> feature` and `feature -> shared`. Task 006 put this file under `src/app/errors/` alongside `RouteErrorBoundary` (which correctly stays there — `AppShell` is genuinely app-layer), but this one has zero app-layer dependencies, same imports as `ErrorPanel.tsx`, which already lives in `@shared/ui`. Relocated, not duplicated — verified no other file referenced the old path. |
| `frontend/src/test/setup.ts` (modified) | Added an `IntersectionObserverStub`, same shape and rationale as the existing `ResizeObserverStub`: default-visible (fires `isIntersecting: true` synchronously on `observe()`) since jsdom has no real layout, so the vast majority of tests — not exercising `useWidgetVisibility`'s off-screen behaviour — see a widget as visible without extra ceremony. A test that IS exercising it swaps this for a never-intersecting stub via `vi.stubGlobal`. |
| `frontend/src/features/monitoring/dashboard/useWidgetVisibility.ts`, `.test.ts` (new) | `IntersectionObserver`-backed hook, `rootMargin: '120px'` (matches the visual reference's own `WidgetCell`). Sticky, not reactive — once visible, always visible; a widget scrolled back off-screen stays mounted and governed by `DM-D17`'s own pause/resume (TanStack Query, not this hook). |
| `frontend/src/features/monitoring/dashboard/LayerBadge.tsx`, `.test.tsx`, `.a11y.test.tsx` (new) | Two layers only — `product-default`/`shared` — reusing the exact `dashboardViewLayer*` copy `DashboardView`'s own (now-retired) inline widget-list rows used, task 085's own words: "one copy, not two that can drift." |
| `frontend/src/features/monitoring/dashboard/WidgetFrame.tsx`, `.test.tsx`, `.a11y.test.tsx` (new) | One dashboard widget, entirely self-contained: title, `LayerBadge`, a menu (one row — a stated pending note, since no task builds "Edit widget" and hide/restore/reset are FE-087's own job), state chrome, and its own `WidgetErrorBoundary`. Owns the full query lifecycle: `useWidgetVisibility` gates the first fetch; the request is built via `buildQuery(widget.query, …)` against the **dashboard's** own `absoluteRange`/`resolution` props — never `widget.range`/`widget.resolution` (`DM-D21` — this is where that rule actually binds a query, not just states it on screen); `refetchInterval`/a per-query `refetchOnWindowFocus` override implement `DM-D17`'s pause-on-hidden/resume-with-one-fetch (see decision 2 below); dim-not-blank is `isFetching && data !== undefined`, broader than `ExploreResult.tsx`'s key-change-only `isPlaceholderData`; a `schemaVersion` newer than the console (`migrateWidgetDocument`) renders read-only and whole, issuing no query at all. Mark dispatch: `line` → `ChartRenderer`; `gauge`/`single-stat` → `GaugeMark`/`SingleStatMark`. |
| `frontend/src/features/monitoring/dashboard/DashboardGrid.tsx`, `.test.tsx`, `.a11y.test.tsx` (new) | 12-column CSS Grid. Visual position (`{x,y,w,h}`, explicit `gridColumn`/`gridRow`) and traversal order are independent axes on purpose (§18) — the DOM is authored in the `order`-sorted sequence `DashboardView` already hands it (never CSS `order`, which would change visual position while leaving focus order at DOM order — the opposite of the goal). Owns "Edit layout": move-earlier/move-later, scoped to adjacent **visible** widgets, persisted via `dashboardRepository.setLayout` against the **full** dashboard layout (order is a dashboard-global sequence) and invalidates the dashboard query on success. |
| `frontend/src/features/monitoring/dashboard/DashboardView.tsx` (modified) | The task-085 placeholder Paper/Stack widget list is retired; the loaded, non-empty branch now renders `<DashboardGrid>`. `resolvedRange` is now `useMemo`'d on `timeRange` alone (same reasoning `ExplorePage.tsx`'s own `absoluteRange` gives) — every widget on the dashboard must resolve the exact same window, or two widgets built from two render ticks could disagree by milliseconds and defeat `canonicalQueryHash`'s cache-collapsing. Header comment and a stale "no poll runs from this task yet" code comment updated to describe the real integration. |
| `frontend/src/features/monitoring/dashboard/DashboardView.test.tsx`, `.a11y.test.tsx` (modified) | Added the `echarts` mock (same pattern `ExplorePage.test.tsx` uses) now that the grid reaches a real `ChartRenderer`; rewrote the one assertion that checked for literal `mark`/`metric` chip text (retired along with the placeholder list) to check the real rendered widgets instead. |
| `frontend/src/features/instance/InstanceShell.test.tsx`, `.a11y.test.tsx` (modified) | Added the same `echarts` mock — the monitoring category branch (task 085) now reaches a real `ChartRenderer` too, which task 085's own a11y case (added when the branch was still a placeholder list) didn't need at the time. Caught by the full suite, not by this task's own new files — see residual 1. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | Added `dashboardGridEdit*`/`widgetFrame*` keys (~13). Removed `dashboardViewGridPendingNote`, now dead (the grid it was standing in for now exists). |
| `docs/plans/frontend/task-list.md`, `docs/plans/frontend/execution.md` (modified) | Marked `086` done. |

## Design decisions, and why

1. **`estimatedBucketSeconds` from task 085 stays a dashboard-wide estimate; each widget's own served `bucketSeconds` is not fed back into it.** Once a widget's `QueryResponse` arrives it carries the REAL served bucket, but wiring per-widget results back into the shared toolbar estimate would mean the estimate flickers based on whichever widget answered first/slowest, no clearer than the estimate already is. `refreshPolicy.ts`'s own header already named this: "once 086 wires real per-widget queries, each widget's own served `bucketSeconds` is authoritative over this estimate **for that widget**" — read literally, that authority is exercised by the FLOOR each `WidgetFrame`'s own `refetchInterval` uses being the dashboard-wide `effectiveRefreshSeconds` DashboardView already floored, not a second per-widget floor recomputed from the live response. Revisiting this is only worth it if two widgets' real tiers ever legitimately diverge, which `DM-D21`'s one-range-per-dashboard rule makes structurally unlikely (§8.4's tier routing is a pure function of range length).

2. **`refetchOnWindowFocus` is overridden to `'always'` per-widget, only while that widget's own refresh is on — never touching the app-wide default.** `queryClient.ts` sets `refetchOnWindowFocus: false` globally, with a stated rationale ("an admin/governance console re-focusing the tab is not a signal to burn a request"). `DM-D17` explicitly wants "resumed with **one** immediate fetch on visibility" for a widget that IS polling — verified against `@tanstack/query-core`'s own source (`queryObserver.js`) that `refetchInterval`'s pause-while-hidden is already a library default gated on `focusManager.isFocused()`, independent of `refetchOnWindowFocus`; only the "resume with one fetch" half needed this per-query override. A per-`useQuery` option override is scoped to that query alone — Designer/Governance's own queries are untouched.

3. **The widget menu has exactly one row: a stated pending note — no "Edit widget" item, not even disabled.** Design §12's edit flow ("widget menu → Explore prefilled → overwrite or fork") has no owning `FE-*` task, and hide/restore/reset are FE-087's, which depends on this task. A clickable-but-disabled menu item reads as a bug; a caption stating the truth is the same posture `SaveWidgetDialog`'s own dev-note already set for its own invented route.

4. **Move-earlier/move-later swap `order` between adjacent items in the currently *visible* (layer-filtered) sequence, using the *full* dashboard layout to read/write those two entries.** `order` is a dashboard-global sequence (`WidgetPlacement.order`), not scoped to a layer filter, so a move must resolve real neighbours in that full sequence's terms — but the UI can only offer to move what the operator can see, and moving a visible widget past an invisible one it doesn't visually border would be confusing. Mirrors the visual reference's own `moveWidget`.

5. **`WidgetErrorBoundary` carries no `title` override in `WidgetFrame`.** Its own default ("This widget failed to render") is deliberately distinct from `widgetFrameQueryErrorTitle` ("This widget failed to load") — the boundary catches an unexpected RENDER throw (a real bug), never the normal query-error state `LiveWidgetBody` already renders inline. Conflating the two copy strings would make a genuine crash and an ordinary 500 read as the same event.

6. **Gauge/single-stat still go through `metricsRepository.queryRange`, never `queryInstant`.** `queryInstant` has no live caller anywhere in the app yet, and nothing in this task's own scope (or any task before it) specifies what an "instant" response's shape or semantics differ by. `resolveMarkValue` already extracts "the aggregated series' last point" from a ranged response, which is what both single-value marks need — reaching for an unused, unspecified second call path would be inventing a contract, not implementing one.

## Residuals (flagged, not silently decided)

1. **A pre-existing task-085 a11y test needed an `echarts` mock it didn't need before.** `InstanceShell.a11y.test.tsx`'s "monitoring category" case (added in FE-085, when the branch rendered a placeholder list) started reaching a real, un-mocked `ChartRenderer` once this task wired the real grid in underneath it — caught by the full suite, fixed alongside `InstanceShell.test.tsx` for the same reason. Not a defect in either task's own logic; a natural consequence of 086 making 085's already-passing test exercise more real code than it did when it was written.
2. **`estimatedBucketSeconds` (085) is not reconciled against a widget's own real `bucketSeconds`** — see decision 1. Flagged there, not silently decided; revisit only if two widgets' real served tiers are ever observed to diverge.
3. **No `queryInstant` call path exists anywhere in the app** — see decision 6. Still true after this task; nothing here changes it.

## Testing

Every command run from `frontend/`:

```
npm run typecheck   # tsc -p tsconfig.json && tsc -p tsconfig.node.json — clean
npm run lint        # eslint . --max-warnings=0 — clean
npm run build       # vite build + assert-echarts-chunk — clean, echarts still isolated to its own chunk
npx vitest run src/features/monitoring/dashboard/   # 15 files — refreshPolicy, DashboardToolbar, LayerBadge,
                                                     # useWidgetVisibility, WidgetFrame, DashboardGrid, DashboardView
                                                     # (+ each's .a11y suite) — all passing
npx vitest run src/features/instance/               # 4 files, 21/21
npx vitest run src/features/inventory/              # 16 files, 159/159 (isolates the one flake below)
npx vitest run                                      # full suite — see below
```

**Full-suite note.** Two consecutive full runs each showed exactly one unrelated failure in
`src/features/inventory/instances/InstancesPage.test.tsx` — a different assertion each time (a `waitFor` on
search-param focus text, then a `waitFor` on a dialog closing), in a file this task never touches. Run in
isolation (`npx vitest run src/features/inventory/`), it passes 159/159 every time. That signature — a
different `waitFor` timeout each run, clean in isolation — is a pre-existing timing flake under full-suite
resource contention, not a regression; a clean full run (298/298 files, 1829/1829 tests) was captured before
committing.

**Browser-verified**, not just tested — a throwaway Playwright script (discarded, not committed) drove
`npm run dev -- --port 5190 --strictPort` against the real msw fixture backend end to end:

- `/solutions/cache/instances/cache-prod-01/monitoring` renders both real fixture widgets ("Hit rate",
  "Cache size") inside the real grid.
- "Edit layout" shows the `#0`/`#1` order badges; clicking "Move later" on the first widget fires a real
  `PUT .../dashboards/db_cache-prod-01/layout` with the two widgets' `order` values correctly swapped
  (`{"layout":[{"widgetId":"w1",...,"order":1},{"widgetId":"w2",...,"order":0}]}`); "Done" removes the move
  controls.
- The widget menu (⋮) opens and shows the stated pending note, not a fake action.
- The layer filter still narrows the real grid to the empty state when nothing matches.
- Console/page-error listener: empty — no errors across the whole run.
- **One honest gap**: this Playwright/Chromium sandbox renders zero `<canvas>` elements for ANY ECharts chart
  in this environment — confirmed pre-existing and unrelated to this task by checking the already-shipped
  Explore page (`/monitoring/explore`) in the same browser session, which shows the identical symptom despite
  being untouched by this work. Every ChartRenderer-reaching unit test in this codebase (including this
  task's own) mocks `echarts.init` rather than exercising a real canvas paint, so this is the first time
  anyone checked for one in this specific sandbox, not a new regression. The DOM chain up to the echarts
  container div resolves to a correct, non-zero size (582×220, confirmed via `boundingBox()`), so the gap is
  in this sandbox's canvas rendering path, not this task's layout/sizing code.

## Status

Done. Commit: `8710106`.
