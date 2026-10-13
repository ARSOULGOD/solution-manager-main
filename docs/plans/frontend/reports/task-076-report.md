# FE-076 · Single-stat and gauge marks — Report

## What I implemented

Refs `DM-D12` `DM-D22` `SM-D31`, `dynamic-metrics` §7, UI §A.10.7. Deps `071` (`ChartModel`/`projectSeries`), `015`
(`Paper`/page furniture — surfaced here via `EmptyState`, which task 015's era of components established). The
task text carried no `Files` list, so the file layout below is this task's own scoping decision, explained in
"Design decisions" below.

| File | Summary |
|---|---|
| `frontend/contracts/metrics/types.ts` (modified) | Adds `Mark = 'line' \| 'gauge' \| 'single-stat'` — the closed set `DM-D12` names — and `MarkOptions { fill?; stack?; scale? }`, the styling-only sibling object. Declared alongside `ChartThreshold` for the same stated reason that file already gives: a future Java widget-schema implementation needs the same vocabulary. |
| `frontend/contracts/metrics/markAggregation.ts` (new) | Pure, no app-side imports (`contracts/` rule, task 046). `MARKS` — the three-member array, so "three marks only" is checkable, not just typed. `marksRequiringAggregation(mark)` — true for `gauge`/`single-stat`. `evaluateMarkAggregation({mark, instanceCount, acrossInstances})` — mirrors `DM-D22`'s exact refusal condition ("more than one instance selected AND `acrossInstances: 'none'`") client-side, returning `{refused: true, code: 'STAT_REQUIRES_AGGREGATION'}` or `{refused: false}`. A single instance with `none` is **not** refused — already one value, matching the decision's own wording. |
| `frontend/contracts/metrics/markAggregation.test.ts` (new) | Table-driven: closed-set check, `marksRequiringAggregation` for all three marks, `evaluateMarkAggregation`'s four cases (refused, single-instance exemption, aggregated exemption across all four non-`none` values, `line` never refused). |
| `frontend/src/features/monitoring/chart/markValue.ts` (new) | `resolveMarkValue(model): {point, unit} \| null` — the one value a gauge/single-stat renders: the aggregated series' (`model.series[0]`) last point. Returns `null` (never a fabricated `0`) when there is no series or the series has no rendered point — same "no zero-fill" posture obligation 1 established for line rendering (task 071/073), extended here rather than re-invented. |
| `frontend/src/features/monitoring/chart/markValue.test.ts` (new) | No series → null; series with `points: []` → null (not zero); last point returned with the chart-wide unit. |
| `frontend/src/features/monitoring/chart/GaugeMark.tsx` (new) | `mark: 'gauge'` — a semicircular arc, **plain SVG**, matching UI §A.10.7 and the visual reference's own `GaugeMark` (`docs/design/frontend/reference/app/charts.jsx`) pixel-for-pixel (same arc path, same radius). Filled proportionally between `min`/`max` (defaults `0`/`100`, per the reference); the arc turns `theme.palette.tone.warn.main` once the value crosses any supplied threshold, otherwise `theme.palette.primary.main`. Renders `EmptyState` (task 015-era component) instead of a `0` when `markValue.ts` returns `null`. |
| `frontend/src/features/monitoring/chart/SingleStatMark.tsx` (new) | `mark: 'single-stat'` — a large formatted value (`formatUnitValue`) plus an optional caller-supplied `delta`, up/down marker. Same `EmptyState` no-data handling as `GaugeMark`. `delta` is never computed here — see decision 4. |
| `frontend/src/features/monitoring/chart/{GaugeMark,SingleStatMark}.test.tsx` (new) | Value rendering, unit formatting, empty state (no series / no points, asserts no stray `"0"`), gauge fraction clamping, single-stat delta up/down/absent. |
| `frontend/src/features/monitoring/chart/{GaugeMark,SingleStatMark}.a11y.test.tsx` (new) | `jest-axe`, light/dark, both the populated and empty-state render — matches the `*.a11y.test.tsx` convention tasks 072–075 established. |
| `frontend/src/features/monitoring/chart/ChartRenderer.tsx` (modified) | New optional `fill` prop (default `false`), threaded through `buildLineSeries`/`buildOption` to `LineSeriesOption.areaStyle`. `DM-D12`: *"area is `options.fill: true` on `mark: line`, not a fourth member"* — implemented as real behaviour, not just a type-level claim. No other line-rendering logic (gaps, thresholds, dash, colour) touched. |
| `frontend/src/features/monitoring/chart/ChartRenderer.test.tsx` (modified, +2 tests) | `fill` omitted by default (no `areaStyle`); `fill: true` adds `areaStyle: {}` to every series without changing `type` away from `'line'`. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | New keys: `markNoDataTitle`/`markNoDataBody` (shared by both marks' empty state), `gaugeMarkAriaLabel`, `singleStatMarkDeltaUp`/`Down`. |

## Design decisions, and why

1. **Neither `GaugeMark` nor `SingleStatMark` imports `echarts`.** `ChartRenderer.tsx` is the app's one `echarts`
   import site (`FE-D16`, enforced by an ESLint `no-restricted-imports` exemption naming that exact file, plus a
   build-chunk assertion). A semicircular arc has no axis, no series-over-time, no legend, no tooltip — none of
   what `DM-D13`/§12 actually specify (theming, canvas renderer, gaps, progressive rendering). The visual reference
   (`docs/design/frontend/reference/app/charts.jsx`) draws its own `GaugeMark`/`SingleStatMark` the same way — plain
   SVG and plain divs, not ECharts — which is the strongest available evidence for what "every visualization" was
   meant to cover. Extending `ChartRenderer.tsx` with an entirely different, axis-less ECharts series type for two
   marks that need none of its existing machinery would grow the file's scope without matching anything §12 or the
   reference actually asks for. Verified: `npm run build`'s `assert-echarts-chunk.mjs` still confirms `echarts`
   stays isolated to its own chunk after adding these two files.
2. **`Mark`/`MarkOptions` live in `contracts/metrics/types.ts`, not local to a component.** Three existing call
   sites already carry an inline `'line' | 'gauge' | 'single-stat'` literal (`dashboardRepository.ts`,
   `metricsRepository.ts`'s `Capabilities.marks`) predating this task; a fourth (this task's own aggregation logic)
   is what tipped it into "canonicalize now" rather than "leave duplicated" — the same threshold task 071 used for
   `ChartThreshold`. I did **not** retrofit the two pre-existing repositories to import the new type — they're
   fixture-scoped stubs from task 033 predating `contracts/metrics`, and touching them isn't this task's job.
3. **`evaluateMarkAggregation` implements `DM-D22`'s literal text (`>1` instance), not the reference prototype's
   simplified `forceAgg && !aggregated` (any instance count).** The reference's `AggregationControl` disables the
   aggregation select whenever a single-value mark is selected, regardless of instance count — a defensible UI
   simplification for a control task (080) to make, but not what actually gets refused server-side per the
   decision's own wording: *"With more than one instance selected, `acrossInstances: 'none'` is refused."* A single
   instance with `none` already renders one number; refusing it would be a false positive with no server-side
   counterpart. `evaluateMarkAggregation` mirrors the real refusal rule so task 082 (typed `422` rendering) can
   trust it as the authoritative client-side mirror; task 080's control-disabling UX is free to be stricter than
   this function without this function needing to match that UX choice.
4. **`SingleStatMark`'s `delta` is caller-supplied, never computed.** Obligation 3 (`dynamic-metrics` §11.2, already
   established for line rendering in tasks 071/073) rules out computing a delta across a gap. This component only
   ever receives one resolved point (`markValue.ts`) — it has no gap information to check, so it cannot safely
   compute a delta itself. A future caller holding two resolved points *and* knowing whether a gap separates them
   is the only place that computation could be done correctly; inventing it here would risk exactly the
   restart-produces-a-false-spike failure obligation 3 exists to prevent.
5. **No `mark`-dispatch component (e.g. `<MetricMark mark={...} model={...}/>`) was built.** `ChartRenderer`,
   `GaugeMark` and `SingleStatMark` take different, deliberately mark-appropriate props (`thresholds`+`scale`+`fill`
   vs. `min`/`max`/`thresholds` vs. `delta`) — the actual dispatch, and the props each call site needs (chart
   toolbar state, aggregation-forced query config), belongs to the pages that don't exist yet (Explore shell, task
   077; dashboard widget cell, task 083/084). Guessing that shape now would be exactly the premature-schema problem
   task 071's decision 2 already flagged for `ChartWidgetConfig`.
6. **`fill` was implemented as real `areaStyle` behaviour, not left as a type-only claim.** The acceptance box
   ("`area` is an option, not a mark") is satisfiable at the type level alone, but `DM-D12`'s own text is about a
   real rendering choice, and wiring it into `ChartRenderer.tsx` was a contained, one-line addition (a single
   conditional spread, matching the file's existing pattern for `markArea`/`markLine`) that touches nothing else —
   worth doing for real rather than only in the type signature.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json --noEmit && npx tsc -p tsconfig.node.json --noEmit   # clean
npm run lint                                                                  # clean (project-wide, --max-warnings=0)
npx vitest run --project contracts markAggregation                            # 1 file, 8/8
npx vitest run src/features/monitoring/chart/                                 # 11 files, 84/84 (new: 6 files)
npm test                                                                      # full suite: 256 files, 1514/1514 (was 250/1483)
npm run build                                                                 # succeeds; echarts stays isolated to its own chunk
```

Task-specific acceptance items:

- **Three marks only** — `MARKS` in `markAggregation.ts` is the literal three-element array, asserted by
  `markAggregation.test.ts`'s first test; `Mark` itself has no fourth member.
- **`area` is an option, not a mark** — `MarkOptions.fill`, not a `Mark` value; `ChartRenderer.test.tsx`'s new
  `fill` describe block proves `areaStyle` is added without ever changing a series' `type` away from `'line'`.
- **Single-value marks force aggregation** — `evaluateMarkAggregation` (decision 3) is the pure, tested rule both
  `GaugeMark`/`SingleStatMark` implicitly rely on (they render whatever single value they're given) and that
  `AggregationControl` (task 080) and the Explore error list (task 082) are expected to call directly.

### Not browser-verified

No consuming page exists yet (Explore shell is task 077; the dashboard widget cell is task 083/084) — same
position task 071's report recorded for `projectSeries`. Verification here is the unit/a11y suites above, plus the
production build's chunk-isolation assertion.

## Status

DONE — implemented, tested (256/256 files, 1514/1514 tests full-suite; the touched areas individually re-verified
above), lint-clean, typecheck-clean, production build succeeds. Not yet marked done in `task-list.md`/
`execution.md` or committed — pending user confirmation. GitHub issue for `FE-076 · Single-stat and gauge marks` —
to be closed on commit.
