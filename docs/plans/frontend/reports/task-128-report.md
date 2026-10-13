# FE-128 · Chart legibility: grid, axes, legend, data-table affordance — Report

## What this closes

The third defect in plan `126`. With `FE-126` making the chart draw and `FE-127` making it draw in the
console's own colours, this task is about what it draws *inside* — every item below was previously coming
from an ECharts default rather than from a decision.

## 1. The plot box

`buildOption` set only `grid.right` (or `right` + `bottom`). ECharts' remaining defaults — `top: 60`,
`bottom: 60`, `left: '10%'`, `containLabel: false` — then consumed ~120px of vertical space. Inside a 220px
widget body that left roughly 100px of actual plot, with the y labels overlapping it.

`GRID_INSET` now states all four sides (`top: 12, left: 8, right: 12, bottom: 8`) with `containLabel: true`,
plus the reservation for whichever edge the legend occupies.

`containLabel` is not a free choice in ECharts 6 — `coord/cartesian/Grid.js:167-176` logs
*"Specified `grid.containLabel` but no `use(LegacyGridContainLabel)`; use `grid.outerBounds` instead"* and
falls back to the outerBounds path when the feature is unregistered. Verified it **is** registered for us:
`ChartRenderer` imports the full package, and `node_modules/echarts/index.js:261` calls
`use(LegacyGridContainLabel)`. Only a tree-shaken `echarts/core` build would need to register it (`I2` —
checked in source, not recalled).

## 2. Legend below the plot by default

`WidgetConfigV1.legend.position` has only ever had one value, `'bottom'`, and `ChartRenderer` ignored it
while reserving 180px of *width* on the right. A new `legendPosition` prop threads it through and defaults
to `'bottom'`, matching both the config and the reference (`charts.jsx`'s `Legend` renders under the chart).

- `WidgetFrame` now passes `config.legend.position` — the config value is finally read.
- `ExploreResult` opts explicitly into `legendPosition="right"`: its result panel is the wide-and-tall case
  the vertical legend was designed for, unlike a short, wide widget card.
- Task 109's below-`md` override still wins over both, so the narrow layout is unchanged.
- `type: 'scroll'` and the rich-text state tags are retained in both variants (§12 — the legend scrolls, it
  never truncates).

## 3. Axis polish

| Change | Grounding |
|---|---|
| `yAxis.splitNumber` derived from available height, bounded 2–6 | Reference uses `const yticks = 4`; a 150px widget body should not carry six labels |
| `yAxis.axisLabel.formatter` → compact | A tick reads `47.9k`, not `47,900 count` |
| `xAxis.axisLabel.hideOverlap: true` | ECharts' equivalent of the reference never letting x labels collide |
| `xAxis.boundaryGap: [0, 0]` | The line starts and ends flush with the plot |

**Two deviations from the plan text, both deliberate:**

**(a) `boundaryGap: false` is not valid here.** The plan asks for `xAxis.boundaryGap: false`, but a boolean
`boundaryGap` is category-axis-only — a time axis takes a `[start, end]` tuple
(`echarts/types/dist/shared.d.ts:2676-2679` numeric vs `:2716` category). `[0, 0]` is the same intent and
type-checks. It also happens to be the existing default, so it is stated for intent rather than for effect.

**(b) `formatUnitValue` cannot produce the plan's own stated output.** The plan asks for tick labels "via
the existing `formatUnitValue`… so ratios read `0.7` and counts read `47.9k`", but that function returns
`85.5%` for a ratio and `47,900 count` for a counter — the unit repeated on every tick, and neither of the
two examples given. A compact axis formatter was needed either way, so `formatAxisValue(value, unit)` was
added beside it in `shared/format/units.ts`, modelled on the reference's `fmtVal`.

The ratio case was **put to the user rather than guessed**, since it is a visible choice: the plan and the
reference both render ratios as decimals, while our tooltip, data table and marks render them as percents.
The user chose the decimal, so a ratio axis reads `0.6 / 0.7 / 0.8 / 0.9` while the tooltip on the same
point still reads `70.2%`. `formatUnitValue` is untouched; **nothing outside the tick labels moved.**

The plan's parenthetical location for `formatUnitValue` (`chart/markValue.ts`) is also a slip — that module
holds `resolveMarkValue` and no formatter. The function lives in `@shared/format`, which is where
`formatAxisTimestamp` (already used by this file) lives too.

## 4. "Open data table" moved off the plot

It was a **text button absolutely positioned over the top-centre of the canvas** — on top of the data it
offers an alternative to. It is now a small icon button pinned bottom-right, clear of both plot and legend,
with a new inline `TableIcon` (`chart/icons.tsx`, following the existing per-folder icon pattern; MUI's
icon package is not a dependency).

`IconButton` requires `aria-label`, so `t('chartDataTableButtonLabel')` survives verbatim and
**`ChartRenderer.a11y.test.tsx` passes unchanged**, as the plan requires. The `Enter`-key path in
`handleKeyDown` and `ChartDataTable` itself are untouched.

**One a11y defect found and avoided here.** The first attempt wrapped the icon button in `shared/ui`'s
`Tooltip` for hover discoverability. That failed the a11y test with axe's `nested-interactive`: that
`Tooltip` exists to name *nameless* content, so it wraps its child in a `role="button" tabIndex={0}` span —
correct for a bare icon, wrong around an already-focusable button. Removed rather than suppressed; the
`aria-label` is the name and the icon needs no second one. The trade-off is noted honestly: mouse users lose
the visible words "Open data table" that the old text button carried.

## Tests

Existing option-object assertions repointed for the flipped default — three legend tests now pass
`legendPosition="right"` explicitly, which is stronger than before: they used to assert the vertical legend
by accident of it being the default, and now assert it as the caller's request.

Five new cases under `plot box and axis legibility (task 128)`: all four grid sides stated and smaller than
the defaults they replace, `containLabel: true`, the bottom-by-default reservation, the compact tick
formatter (`47_900` → `47.9k`), the bounded tick count, and `hideOverlap` / `boundaryGap: [0, 0]`.

Five new cases for `formatAxisValue` in `units.test.ts`, including one that pins the divergence from
`formatUnitValue` side by side.

## Files

| File | Change |
|---|---|
| `src/features/monitoring/chart/ChartRenderer.tsx` | `GRID_INSET`, `ySplitNumber`, `legendPosition` prop, axis polish, icon-button affordance; header extended. |
| `src/features/monitoring/chart/icons.tsx` | New — inline `TableIcon`. |
| `src/features/monitoring/chart/ChartRenderer.test.tsx` | Legend tests made explicit; five new legibility cases. |
| `src/shared/format/units.ts` | New `formatAxisValue`. |
| `src/shared/format/index.ts` | Barrel export. |
| `src/shared/format/units.test.ts` | Five new cases. |
| `src/features/monitoring/dashboard/WidgetFrame.tsx` | Passes `config.legend.position`. |
| `src/features/monitoring/explore/ExploreResult.tsx` | Opts into `legendPosition="right"`. |

## Verification

- `npm run typecheck` — clean
- `npm run lint` — clean at `--max-warnings=0`
- `npm test` — **324 files, 2191 tests, all passed** (up from 2181 before this task)
- `npm run verify:palette` — 169 passed
- `ChartRenderer.test.tsx` — 45 passed · `ChartRenderer.a11y.test.tsx` — 2 passed, **file unchanged**

The recovered plot height is a pixel claim and is deliberately **not** asserted here; it is the plan's own
task-`132` visual step (plot height > 60% of the card body).

### A note on the test environment, not on this change

Several full-suite attempts during this task hung for 15+ minutes per file, on files with no dependency on
anything touched here (`EmptyState.a11y`, `AccessCheckView.a11y`, `SectionCard.a11y`, `RolesView`,
`Gallery`). The cause was the machine, not the suite: swap was at **14.9 GB of 16 GB** with a
Virtualization.framework VM pinned near 100% CPU, and load average reached 21 on 10 cores. Every affected
test passed in seconds when run alone (`ExplorePage.test.tsx`: 34 passed in 10.6 s). Once load fell back to
~2, the full suite completed normally in 244 s.

Worth recording because issue #131 (`vitest.config.ts`'s `maxWorkers: '50%'`) is about exactly this class
of failure: under memory pressure the pool's timeouts stop being enforceable at all — a `testTimeout:
20_000` case ran for 903 s. Reducing `--maxWorkers` to 3, then 2, did **not** help; only relieving the
memory pressure did. If this recurs in CI, the lever is the runner's memory, not the worker count.
