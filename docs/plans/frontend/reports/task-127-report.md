# FE-127 · Correct the ECharts theme shape — Report

## What this closes

The second defect in plan `126`: `echartsTheme()` returned axis styling under keys ECharts never reads, so
every chart drew its axes, ticks, labels and gridlines in **stock ECharts colours in both light and dark
mode**, regardless of the MUI theme. With `FE-126` making a chart actually draw, this is what it drew with.

## Root cause — verified against the installed source, not recalled (`I2`)

`axisLine` / `axisTick` / `axisLabel` / `splitLine` sat at the **top level** of the theme object. An axis
model does not look there:

- `node_modules/echarts/lib/coord/axisModelCreator.js:65-70` — `mergeDefaultAndTheme` merges
  `themeModel.get(axisType + 'Axis')`, then the built-in defaults. `axisType` comes from `AXIS_TYPES` =
  `value` | `category` | `time` | `log` (`coord/axisCommonTypes.js:44-49`), so the only readable keys are
  `valueAxis`, `categoryAxis`, `timeAxis`, `logAxis`.
- `model/Global.js:691-710` (`mergeTheme`) merges every remaining theme key onto the **root** option, where
  a bare `axisLine` is meaningless.
- ECharts' own bundled theme confirms the shape: `theme/dark.js:47-74` defines one `axisCommon()` builder
  and `:226-229` spreads it across all four families.

Merge order is `option` → theme → defaults, each step non-overwriting, so a per-chart option still wins over
the theme and the theme still wins over ECharts' defaults.

## Proof the defect was real — measured, not argued

A throwaway probe initialised a real ECharts instance (SVG renderer, so jsdom needs no canvas) with each
theme shape and read back the **resolved `yAxis` model**. The probes were scratch-only and are not
committed — an `echarts` import inside `shared/theme` would violate `FE-D16`'s single-import-site rule.

| Resolved `yAxis` property | Theme slot it should track | **Before** | **After** |
|---|---|---|---|
| `splitLine.lineStyle.color` | `palette.divider` = `#CFD3D9` | `#dbdee4` (stock) | `#CFD3D9` ✅ |
| `splitLine.lineStyle.type` | `dashed` | `solid` (stock) | `dashed` ✅ |
| `axisLabel.color` | `palette.text.secondary` = `#647481` | `#54555a` (stock) | `#647481` ✅ |
| `axisLabel.fontFamily` | `Rubik, Inter, system-ui, …` | *absent* | Rubik ✅ |

Not one axis property was tracking the theme before this task.

## What was built

**`axisCommon(theme, fontFamily)`** — one shared per-family builder mirroring `theme/dark.js`'s own, so all
four families draw from the same two slots (`palette.divider` for lines, `palette.text.secondary` for
labels) and none can drift. `EChartsAxisTheme` is exported for the families' shared type.

**Which lines are drawn is a reference decision, not an ECharts default.** `reference/app/charts.jsx` draws
**horizontal gridlines only**. `ChartRenderer`'s y axis is `value` or `log` (`resolveScale`) and its x axis
is `time`, so:

- `valueAxis` / `logAxis` — dashed `splitLine` on, `axisLine.show: false`, `axisTick.show: false`
- `timeAxis` — keeps its axis line (the plot's baseline), `splitLine.show: false`
- `categoryAxis` — styled for completeness. No chart uses one today, but leaving a family unstyled is
  exactly how this defect looked, so it is not left to chance.

`timeAxis`'s `splitLine.show: false` duplicates ECharts' own `timeAxis` default
(`coord/axisDefault.js:226-229`). Stated explicitly anyway so the intent survives a dependency bump.

`color`, `backgroundColor`, `textStyle`, `tooltip` and `legend` stay top level — those genuinely are
theme-level keys.

## Call sites

As the plan predicted, `WidgetFrame.tsx:263` and `ExploreResult.tsx:115` read only `echartsTheme(muiTheme).color`
for `projectSeries`, and `color` is unchanged — **neither needed an edit**. `ChartRenderer.tsx:623` passes the
whole object to `echarts.init` and is likewise untouched.

One consumer outside the plan's list did need a path update: `palette.contrast.test.ts:128` asserts the axis
label colour clears 4.5:1 (SC 1.4.3 AA) and now reads `valueAxis.axisLabel.color`. It still passes — the
colour it checks is the same `palette.text.secondary`, now actually reaching the chart.

## Tests

`echartsTheme.test.ts` — existing assertions repointed, plus four new cases:

- a parameterised case over all four families, asserting each derives line, tick, split-line and label
  colours **and** the font family from the same theme slots
- a case asserting **no top-level axis keys remain** — the shape that caused the defect cannot come back
- a case asserting all four families change together between light and dark
- a case pinning the reference's horizontal-gridlines-only rule (`show` flags per family)

## Files

| File | Change |
|---|---|
| `src/shared/theme/echartsTheme.ts` | Axis styling moved under `timeAxis`/`valueAxis`/`logAxis`/`categoryAxis` via a shared `axisCommon()`; header records the verified source references. |
| `src/shared/theme/echartsTheme.test.ts` | Paths updated; four new cases incl. the no-top-level-axis-keys guard. |
| `src/shared/theme/palette.contrast.test.ts` | Axis-label contrast check repointed to `valueAxis.axisLabel.color`. |

## Verification

- `npx tsc --noEmit` — clean (it caught all three stale call sites)
- `npm run lint` — clean at `--max-warnings=0`
- `npm test` — **324 files, 2181 tests, all passed**
- Resolved-axis-model probe — the before/after table above

Dark-mode visual confirmation on the running console is the plan's own task-`132` step 4 and is not claimed
here; the mechanism is verified at the resolved-model level, which is the direct observable behind it.
