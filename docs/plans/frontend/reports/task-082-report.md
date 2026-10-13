# FE-082 · Explore result states and typed `422` rendering — Report

## What I implemented

Refs Dec `DM-D16` `FE-D10` `FE-D20` `SM-D27` · `dynamic-metrics` §5.8, §11.2 · UI §A.10. Deps `077–081, 028`.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/explore/exploreResultState.ts` (new) | Pure state derivation, kept out of the rendering component the same way `exploreGating.ts` (task 077) split its own branching out — `deriveExploreResultState({hasMetric, hasInstances, status, isPlaceholderData, error, data})` → one of `empty-selection` (naming which choice — `metric` or `instances` — is missing, checked BEFORE the query status so a disabled query's permanent `'pending'` is never confused with a real first fetch), `loading` (true first fetch), `error` (the raw thrown value, unexamined — the render layer decides typed-422 vs generic), or `result` (`{response, refreshing}` — `refreshing` is TanStack's own `isPlaceholderData` signal from `placeholderData: keepPreviousData`, task 029, and `response` is deliberately the STALE previous response while refreshing, matching §11.2's "previous series dimmed" wording). Also exports `hasNoDataInRange(response)` — zero points across every series, whatever the reason (empty scenario, full-range retention truncation, or a genuinely dead metric). |
| `frontend/src/features/monitoring/explore/exploreResultState.test.ts` (new) | One test per branch: metric-missing beats instances-missing; a disabled query's stale `'pending'`/`'success'` never leaks past the empty-selection check; true first load is `loading`; a thrown error passes through unexamined; a settled result carries `refreshing:false`; a placeholder result carries `refreshing:true` **with the stale response**, not the new one; `hasNoDataInRange` for populated/all-empty/zero-series responses. |
| `frontend/src/features/monitoring/explore/ExploreResult.tsx` (new) | The result column's real body, replacing task 077's static placeholder. Owns its own `useQuery` (keyed by `queryKeys.metrics.query(canonicalQueryHash(request))`, task 070), and renders per `deriveExploreResultState`: empty-selection → `EmptyState` naming the missing step; loading → `Skeleton` at the same fixed height (`EXPLORE_CHART_HEIGHT = 300`) the settled chart uses, so nothing shifts on arrival; error → the shared `ErrorPanel` (task 006's "the one error surface") with `describeErrorCode(code, message)` (task 028) as the `detail` — **no `onRetry` for a `422`** (satisfies "never retried"), `onRetry={() => query.refetch()}` for anything else, plus a stated caveat that the `422` body shape has no contract source (same "state the limitation in the UI" posture `TypeMetricDialog`, task 079, already set for its own missing fields); result → a `partial.degraded` banner (naming each unavailable instance and why, with its own invented-shape caveat), a `coverage.truncatedByRetention` banner, then either the `hasNoDataInRange` empty box ("No samples in this range" + served tier — never a zero line) or a real `ChartRenderer` (task 072-075) built via `projectSeries` (task 071) + `echartsTheme` (task 003), dimmed to 0.6 opacity with a `LinearProgress` overlay while `refreshing`. Four §11.2 rows — `needs-typing`, `type-suspect`, `pending-config-change`/`inactive-replica`/unverified-durability, and gaps — get **no new rendering here**; the file's own header explains why each is already owned by `MetricSelector` (task 079) or `ChartRenderer`/`chartStateTags.ts` (tasks 073/074), and duplicating them would be the second copy that file's own header warns against. |
| `frontend/src/features/monitoring/explore/ExploreResult.test.tsx` (new) | One test per row this file owns: empty-selection (both missing choices), first-loading skeleton, typed `422` (code + correlation id shown, detail from `describeErrorCode`, no Retry button), generic error (Retry button present, re-issues the query), no-data box (served tier interpolated, no chart mounted), partial banner (instance + reason listed), retention banner, a normal chart mount, and refreshing (dimmed chart + `LinearProgress`, stale data stays visible, clears once the new request resolves) — the last one wires its own `QueryClient` with `placeholderData: keepPreviousData` since the shared test `renderWithProviders` client deliberately doesn't carry that default. |
| `frontend/src/features/monitoring/explore/ExploreResult.a11y.test.tsx` (new) | `jest-axe`, light+dark, across empty-selection, the typed `422`, the partial-data banner + chart, and the no-data box. |
| `frontend/src/features/monitoring/explore/ExplorePage.tsx` (modified) | Builds the real `QueryRequest` from the page's own draft state via `buildQuery` (task 070) — `null` whenever a metric or an instance isn't selected yet, which is `ExploreResult`'s own empty-selection signal rather than issuing a request that could only `422`. The absolute window comes from `resolveTimeRange` (task 081, memoized on `timeRange` via `useMemo` — see residual 1) rather than handing `buildQuery` a `'relative'` range, so the two files' separate preset-duration tables never get a chance to disagree. Swaps the static placeholder `Paper`/`EmptyState` for `<ExploreResult>`. |
| `frontend/src/features/monitoring/explore/ExplorePage.test.tsx` (modified) | Updated the old "renders a placeholder" assertion for the two real empty-selection copies; added two full end-to-end tests against the real msw fixture backend: selecting a metric + one instance mounts a real `ChartRenderer` (`aria-label="Chart: cache.hit_rate"`), and aggregating with exactly one instance selected — genuinely reachable through the live UI — renders the typed `422` rather than crashing. Added a file-level `vi.mock('echarts', ...)` (mirroring `ChartRenderer.test.tsx`'s own) since this suite can now genuinely reach `ChartRenderer`, and jsdom has no canvas 2D context for the real `echarts.init` to use. |
| `frontend/src/shared/testdoubles/handlers/console.ts` (modified) | Added `aggregationError()`: the mock's `/query/range` and `/query/instant` handlers now return a real `422 AGGREGATION_NOT_APPLICABLE` when `acrossInstances !== 'none'` and fewer than two instances are selected — the ONE `SPECIFIED` `422` code genuinely reachable through the running app today (every other one is already prevented client-side — see the handler's own comment and `ExploreResult.tsx`'s header for the by-code accounting). Deliberately does not add a `__scenario`-style trigger for the others: `console.ts`'s own existing comment already flags that kind of debug harness as "what task 036 must NOT ship." |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | Removed the now-dead `exploreResultPlaceholderTitle`/`Body`. Added the copy this task renders: `exploreResultEmptyMetricTitle`/`Body`, `exploreResultEmptyInstancesTitle`/`Body`, `exploreResultLoadingAriaLabel`, `exploreResultRefreshingAriaLabel`, `exploreResultNoDataTitle`/`Body`, `exploreResultPartialTitle`/`ResidualNote`, `exploreResultRetentionTruncated`, `exploreResultErrorTitle`, `exploreResult422Title`/`ResidualNote`, `exploreChartAriaLabel`. |

## Design decisions, and why

1. **`resolveTimeRange` is memoized on `timeRange` alone in `ExplorePage`, not called inline.** `resolveTimeRange` calls `Date.now()` internally for a preset. Calling it inline on every render (as `TimeRangePicker`'s own caption already does, harmlessly, since that's just display text) would mint a new `to` timestamp on every unrelated re-render — an aggregation toggle, say — which changes the canonical query hash and refetches for no reason. This is a bug specific to feeding that value into a query key, not a defect in task 081's own code; `useMemo(() => resolveTimeRange(timeRange), [timeRange])` fixes it at the one place it matters.

2. **Four of the thirteen `§11.2` rows get no new code.** `needs-typing` can never actually reach this file — `MetricSelector` (task 079) disables the picker row outright, so `selectedMetric` can never hold a `needs-typing` name; what this file sees is just "no metric selected," already `empty-selection`. `type-suspect`'s persistent warning is `MetricSelector`'s own `Alert`, on screen for as long as the chart is; `pending-config-change`/`inactive-replica`/unverified-durability are `SeriesState` values `chartStateTags.ts` (task 074) already dims/dashes/tags in the legend and tooltip; gaps are `ChartRenderer`'s own line breaks (task 073) from `ChartSeries.gaps`. Rendering any of these a second time here would be exactly the "second copy" `chartStateTags.ts`'s own header warns against — this task's job was making sure a real `ChartModel` reaches `ChartRenderer` at all, which it now does.

3. **The `422` renders through the shared `ErrorPanel`, not a bespoke inline `Alert`.** `ErrorPanel`'s own header calls it "the one error surface... so no other component may render this to mean 'no data'" — reusing it keeps that guarantee intact and gets `code`/`correlationId` rendering for free. The reference's own `explore.jsx` builds a one-off `Alert` for this, but the reference predates `ErrorPanel` (task 006) existing at all; this file follows the newer, real component instead of the prototype.

4. **The `422`'s "body shape is unspecified" caveat is real, visible UI copy, not just a code comment.** `TypeMetricDialog` (task 079) already set this precedent for its own missing catalog fields (`typeMetricDialogNoSuggestionNote`) — a stated limitation belongs in front of the operator, not just in source, per this repo's own "state the consequence in the UI" convention (see task 083's text making the same point for a different residual).

5. **The mock backend gained exactly one real `422` — `AGGREGATION_NOT_APPLICABLE` — not all five `SPECIFIED` codes.** The other four are already prevented client-side by earlier tasks (`InstanceSelector`'s cap, `GroupByControl`'s aggregation gate, no mark control exists to force single-value aggregation, `MetricSelector`'s disabled `needs-typing` row), so simulating them would be untestable through real interaction — there is no way for a user to actually reach them. `AGGREGATION_NOT_APPLICABLE` (aggregate with one instance selected) has no such client-side gate and is genuinely reachable, so the mock backend now enforces it for real rather than leaving every `422` path a component-test fiction. No `__scenario`-style live trigger was added for the twelve non-`422` states either, for the same reason `console.ts`'s own existing comment already gives: that kind of debug selector is explicitly the prototype-only harness this codebase does not ship.

## Residuals (flagged, not silently decided)

1. **The `422` body shape and `partial.unavailable[]`'s item shape are still invented** (§16 Q9, carried over from `ApiError`/`errorCodes.ts`, task 028, and `metricsRepository.ts`'s own `QueryResponse.partial` comment, task 033) — both residual notes are rendered to the operator (decision 4 above) rather than hidden.
2. **The "nearest data" hint in the no-data box is deliberately vague** ("Nearest data is outside the selected window"), matching the reference's own wording — the contract has no field carrying an actual nearest-sample timestamp, so this file does not fabricate one.
3. **No live way exists to reach the other twelve fixture states or the other four `422` codes through the running app** — they are exercised by `ExploreResult.test.tsx`'s direct repository mocking instead (decision 5 above explains why no debug selector was added to close this gap).

## Testing

Every command run from `frontend/`:

```
npm run typecheck   # tsc -p tsconfig.json && tsc -p tsconfig.node.json — clean
npm run lint        # eslint . --max-warnings=0 — clean
npm run build        # vite build + assert-echarts-chunk — clean, echarts still isolated to its own chunk
npx vitest run src/features/monitoring/explore/   # 25 files, 185/185
npx vitest run                                    # full suite: 281 files, 1700/1700
                                                    # (one InstancesPage.test.tsx timing flake on the full run,
                                                    #  unrelated to this task — file untouched here, passes
                                                    #  29/29 in isolation)
```

**Browser-verified**, not just tested — a throwaway Playwright script (discarded, not committed) drove
`npm run dev -- --port 5183 --strictPort` against `/solutions/cache/instances/cache-prod-01/monitoring/explore`,
using the real msw fixture backend end to end:

- On load: "Select a metric" (empty-selection, missing choice named).
- Picking `cache.hit_rate`: "Select at least one instance".
- Picking the first instance: a real chart mounts (`aria-label="Chart: cache.hit_rate"`), with the keyboard
  live-region announcing real values (confirming `ChartRenderer`'s task-075 accessibility path is live, not just
  unit-tested).
- Setting aggregation to `sum` with exactly one instance selected: the real mock backend returns a genuine `422`,
  rendered as "Request refused — not retried" with `Error code: AGGREGATION_NOT_APPLICABLE` visible and **no**
  Retry button.
- Setting aggregation back to `none`: the chart returns.
- Selecting the type-suspect `cache.queue_depth`: `MetricSelector`'s persistent warning appears, and the chart
  still renders with real data for the new metric.
- Console/page-error listener: the only entry across the whole run was the browser's own informational
  "Failed to load resource: 422" log for the deliberately-triggered refusal above — expected noise from a real
  non-2xx fetch, not an application error.
