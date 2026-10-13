# 126 — Monitoring UI: reference alignment and chart repair

**Component:** frontend · **Phase:** 6 (follow-on) · **Tasks:** `126`–`132`
**Written:** 2026-09-03 · **Status:** COMPLETE (2026-09-03) — reports `task-126`…`task-132` in [`reports/`](reports/)
**Reference of record:** [`docs/design/frontend/reference/`](../../design/frontend/reference/README.md) (`FE-D30`)

---

## Context

The Monitoring category ships (tasks `068`–`089`, `109`) but does not hold up on screen. Driving the dev
server at 1440 px against the visual reference turned up four distinct problems:

1. **Charts are broken, not merely plain.** On `/monitoring` and in Explore the ECharts canvas is
   *absent* — the host `<div>` carries `_echarts_instance_=""` with no `<canvas>` child, i.e. the
   instance was created and then disposed. Where a chart does survive the race (the standalone dashboard
   route) it renders squashed into ~100 px of a 220 px body with cramped, unthemed axes.
2. **There is no way to add a metric to a dashboard.** A populated Monitoring tab contains no link to
   Explore anywhere — `exploreHref` is referenced only from two empty states
   (`DashboardView.tsx`, `MonitoringFirstRun.tsx`). Explore's "Save as widget" then posts to a fixture
   that echoes and discards, so a saved widget never appears on the board.
3. **Dashboard chrome diverges from the reference** — no Dashboards/Explore sub-tab bar, a toolbar whose
   Layer select stretches the full width, two stacked full-width explainer banners, and widget cards with
   large dead space below the plot.
4. **The default charts demonstrate nothing.** The MSW dashboard fixture has two widgets (one line, one
   single-stat); the reference has six, covering gauge, thresholds, a hidden default and a read-only
   too-new widget. `GaugeMark`, threshold rendering and `HiddenDefaultWidget` are unexercised on screen.

**Intended outcome:** the Monitoring screens render charts correctly and read like
`reference/app/dashboards.jsx` / `explore.jsx` / `charts.jsx`, and an operator can walk
Monitoring → Explore → Save as widget → see it on the dashboard.

---

## Root causes (verified, not inferred)

### A. `useEcharts` loses the instance to StrictMode's double-invoked effect

`frontend/src/features/monitoring/chart/useEcharts.ts` starts an async `createInstance(container)` per
effect run and disposes only via a `created` local that is still `null` when the first cleanup fires:

- pass 1 effect starts promise A → cleanup 1 sets `cancelled1 = true` (`created` still `null`, nothing
  disposed) → pass 2 effect starts promise B
- A's `await import('echarts')` resolves first → `echarts.init(container)` creates instance **X**
- B's continuation calls `echarts.init(container)` on the same node → ECharts returns the **existing**
  instance X, not a new one
- A's `.then` sees `cancelled1` → `X.dispose()`
- B's `.then` sees not-cancelled → `setInstance(X)` — **X is already disposed**, so every later
  `setOption` is a no-op

This is why the canvas is missing, and why it is intermittent — whichever mount wins the module-cache
race sometimes renders.

### B. `echartsTheme()` returns keys ECharts does not read

`frontend/src/shared/theme/echartsTheme.ts` puts `axisLine`, `axisTick`, `axisLabel` and `splitLine` at
the **top level**. Verified against the installed ECharts source
(`frontend/node_modules/echarts/lib/theme/dark.js:47-74, 226-229`, per `I2` — checked, not recalled): a
theme carries axis styling under `timeAxis` / `logAxis` / `valueAxis` / `categoryAxis`, each holding its
own `axisLine` / `splitLine` / `axisLabel`. Ours are silently ignored, so charts render with stock
ECharts axis colours in both modes.

### C. `buildOption`'s grid leaves ECharts' defaults in place on three sides

`ChartRenderer.tsx` sets only `right` (or `right` + `bottom`). ECharts' default `top: 60` / `bottom: 60`
/ `left: '10%'` with `containLabel: false` then consumes 120 px of vertical space — inside a 220 px
widget body that leaves ~100 px of plot with overlapping y labels.

### D. Widget height ignores the layout

`WIDGET_BODY_HEIGHT` is a flat `220` in `WidgetFrame.tsx`, while `DashboardGrid` spans `placement.h`
auto-sized grid rows — so a `h: 2` widget stretches and leaves the remainder empty. The reference sizes
the body `h * 150`.

### E. Fixture handlers make the save flow a dead end

`frontend/src/shared/testdoubles/handlers/console.ts` — `GET /dashboards/:id` returns a static literal,
`POST /dashboards/:id/widgets` echoes the request, and `POST /dashboards/:id/reset` returns
`widgets: []`, so "Reset defaults" empties the board rather than restoring the package layer.

---

## Out of scope (flagged, not fixed)

- **`frontend/public/fonts/` is empty except a README** — every Rubik / Inter / JetBrains Mono request
  404s and the console falls back to system fonts (`OTS parsing error: invalid sfntVersion` in the
  browser console). This is `FE-002`'s deferred acquisition task; it needs binary assets plus an OFL
  licence check. **Record it in the task `132` report as a separate blocking visual defect.**
- **Wrapping Explore / the standalone dashboard in `InstanceShell`** (the reference renders all three
  inside `InstanceHeader`). That needs all five instance repositories on those routes and would create a
  `@features/monitoring` → `@features/instance` import against the existing
  `@features/instance` → `@features/monitoring` edge — a `eslint-plugin-boundaries` cycle. The sub-tab
  bar in task `130` gets the reachability without it.
- **Brand paint.** `FE-D5` keeps brand colour changes blocked pending open item 19. Every step below uses
  existing `@shared/theme` tokens; **no new colour literals.**

---

## Tasks

### `126` — Fix the `useEcharts` StrictMode race

`frontend/src/features/monitoring/chart/useEcharts.ts`

Serialise creation through a per-hook promise chain held in a ref, so the *n+1*-th effect's
`createInstance` never runs until the *n*-th has settled and disposed:

```ts
const chainRef = useRef<Promise<void>>(Promise.resolve());
// in the effect:
chainRef.current = chainRef.current.then(async () => {
  if (cancelled) return;                 // pass 1 exits here, never inits
  const instance = await createInstance(container);
  if (cancelled) { instance.dispose(); return; }
  created = instance;
  setInstance(instance);
});
```

Keeps the file ECharts-agnostic (`FE-D16` — the factory stays caller-supplied) and preserves both
dispose-on-unmount and dispose-on-factory-change.

**Tests** (`useEcharts.test.ts`): add a case that mounts under `<StrictMode>` (or simulates
effect → cleanup → effect) and asserts the surviving instance is **not** disposed, and that a factory
returning the same object twice is not handed back disposed. **This test must fail before the fix** —
demonstrate that, per `superpowers:test-driven-development`.

### `127` — Correct the ECharts theme shape

`frontend/src/shared/theme/echartsTheme.ts` + `echartsTheme.test.ts`

- Restructure `EChartsTheme` so `axisLine` / `axisTick` / `axisLabel` / `splitLine` live inside
  `timeAxis`, `valueAxis`, `logAxis` and `categoryAxis` — one shared `axisCommon(theme)` builder,
  mirroring `node_modules/echarts/lib/theme/dark.js`. Keep `color`, `backgroundColor`, `textStyle`,
  `tooltip` and `legend` at the top level; those *are* theme-level keys.
- Value/log axes: `axisLine.show: false`, `axisTick.show: false`, keep the dashed `splitLine`. Time axis:
  keep the axis line, drop the split line — the reference draws horizontal gridlines only
  (`charts.jsx`, `yticks` loop).
- Update the test's `chart.axisLine…` / `chart.axisLabel…` assertions to the new paths; add one asserting
  all four axis families are present and derive their colours from the same theme slots.
- `WidgetFrame` / `ExploreResult` call `echartsTheme(muiTheme).color` for `projectSeries`; `color` is
  unchanged, so those call sites need no edit.

### `128` — Chart legibility: grid, axes, legend, data-table affordance

`frontend/src/features/monitoring/chart/ChartRenderer.tsx`

- **Grid**: replace the partial override with an explicit box —
  `{ top: 12, left: 8, right: 12, bottom: 8, containLabel: true }` — plus the legend reservation on
  whichever edge the legend occupies. This alone recovers ~100 px of plot inside a widget.
- **Legend below the plot by default.** The reference's `Legend` sits under the chart, and the widget
  document already declares `legend.position: 'bottom'` — today it is ignored while 180 px of width is
  reserved on the right. Thread `position` through from `WidgetConfigV1.legend`, default to bottom, keep
  the vertical variant available for Explore's wide panel. Retain `type: 'scroll'` (§12 — the legend
  scrolls, it never truncates) and the existing rich-text state tags.
- **Axis polish**: `yAxis.splitNumber` derived from available height (the reference uses 4 ticks);
  `yAxis.axisLabel.formatter` via the existing `formatUnitValue` (`chart/markValue.ts`) so ratios read
  `0.7` and counts read `47.9k`; `xAxis.axisLabel.hideOverlap: true`; `xAxis.boundaryGap: false`.
- **"Open data table"** moves out of the plot area — currently absolutely positioned over the top-centre
  of the canvas. Make it a small icon button pinned bottom-right of the chart box, keeping its existing
  accessible name. The `Enter`-key path and `ChartDataTable` are unchanged.
- Update `ChartRenderer.test.tsx`'s option-object assertions. `ChartRenderer.a11y.test.tsx` must pass
  **unchanged** — no accessible name may be removed.

### `129` — Widget card sizing and chrome

`frontend/src/features/monitoring/dashboard/{DashboardGrid,WidgetFrame,HiddenDefaultWidget}.tsx`

- `DashboardGrid`: `gridAutoRows: '150px'` at `lg`+ so `placement.h` means a real height (reference
  `.grid12` + `h * 150`); gap `14px`.
- `WidgetFrame`: derive body height from `placement.h` instead of the flat `WIDGET_BODY_HEIGHT`, and make
  the `Paper` a flex column so the body fills its slot — removing the dead space under the plot. Keep a
  floor for `h: 1`.
- Header rule under the title (reference `.widget-h` carries a `border-bottom`), tighter padding
  (`11px 14px 10px` header / `12px 14px` body), title 13.5 px / 600. Theme tokens only.
- `HiddenDefaultWidget` keeps its dashed border; verify it still fills its slot at the new row height.

### `130` — Monitoring sub-tabs (Dashboards · Explore)

- Add optional `end?: boolean` to `RouteTab` (`frontend/src/shared/ui/RouteTabs/RouteTabs.types.ts`) and
  honour it in `RouteTabs.tsx`'s active-match, mirroring react-router's `NavLink` `end`. Required:
  `/monitoring` currently prefix-matches `/monitoring/explore`, so both tabs would read as active.
- New `frontend/src/features/monitoring/MonitoringTabs.tsx` — a two-tab bar (**Dashboards**, **Explore**)
  built on `RouteTabs`, rendered at the top of `DashboardView` (both `landing` and standalone) and
  `ExplorePage`, replacing today's ad-hoc "← Monitoring" links. This is the reference's `.itabs` row, and
  it is the actual fix for *"the add-metric screen is missing"*: Explore becomes reachable from a
  populated board.
- Gallery entry + `.test.tsx` + `.a11y.test.tsx` alongside, matching the sibling monitoring components.

### `131` — Toolbar, banners, and an explicit "Add widget" action

`frontend/src/features/monitoring/dashboard/{DashboardToolbar,DashboardView,DashboardGrid}.tsx`

- Toolbar to one dense row (reference `.dash-toolbar`): bound the `Layer` and `Refresh` selects with
  `fullWidth={false}` — `shared/ui/Select` defaults it to `true`, which is why Layer stretches — align
  items centre, and pull **Reset defaults** / **Edit layout** (today rendered separately by
  `DashboardGrid`) onto the same row behind a spacer. **Keep the `data-reflow-exception` attribute and
  `tabIndex`**: `verify:reflow` and the axe sweep both depend on them.
- Add an **Add widget** button to that row, routing to Explore — the reference's own Explore-then-save
  flow, given a front door.
- Collapse the two stacked full-width `Alert`s. Keep the `DM-D21` "range and refresh are authoritative"
  note as a single compact caption under the toolbar; move the landing-vs-index explanation to an
  `InfoTip` on the tab bar rather than a permanent banner.

### `132` — Make the save flow land, and enrich the fixture

`frontend/src/shared/testdoubles/handlers/console.ts`, `features/monitoring/explore/ExplorePage.tsx`

- Hold the dashboard document in a module-level mutable store (same posture as
  `app/dev/devPackageStore.ts`) so `POST /widgets` appends, `PUT /layout` persists,
  `PATCH /widgets/:id` flips `hidden`, and `GET` reflects all three. `POST /reset` must restore the
  **package defaults**, not return `widgets: []`.
- Seed six widgets mirroring `reference/app/dashboards.jsx`: hit-rate line; evictions line **with a
  warning threshold**; entries single-stat; hit-rate **gauge**; a `hidden: true` package default; and one
  with `schemaVersion: 2` to exercise the read-only too-new path. Give them real `{x,y,w,h}` so the new
  row sizing is visible.
- `ExplorePage`: on a successful save, navigate to the dashboard so the operator sees the widget land
  (the outcome toast already fires — `exploreSaveWidgetSucceededTitle`).
- Update `dashboardRepository.test.ts` / `DashboardGrid.test.tsx` / `DashboardView.test.tsx` for the new
  fixture shape.

---

## Verification

Run from `frontend/`:

```bash
npm run typecheck
npm run lint
npm test
npm run test:a11y
npm run verify:palette
npm run e2e -- --project=fixture     # reflow + journeys against the dev server
```

**Visual proof — the point of the task, so it is not optional.**

1. `npm run dev`, then screenshot at 1440 px (Playwright, `@playwright/test`'s `chromium` from
   `frontend/node_modules`):
   - `/solutions/cache/instances/cache-prod-03/monitoring`
   - `/solutions/cache/instances/cache-prod-03/monitoring/explore`
2. Assert on the screenshots, not just their existence:
   - the line widget has a `<canvas>` child under `[role="application"]` with non-zero size — **this is
     the exact check that fails today**
   - plot height inside a `h: 2` widget is > 60 % of the card body (no dead space)
   - gauge, threshold line and hidden-default placeholder all visible on the default dashboard
   - a Dashboards/Explore tab bar present on both screens
3. Walk the flow by hand: Monitoring → Explore → pick `cache.hit_rate` → pick 3 instances →
   **Save as widget** → confirm it appears on the dashboard; then **Reset defaults** and confirm the six
   package widgets come back (not an empty board).
4. Toggle dark mode on the dashboard and confirm axis labels and gridlines pick up the theme — the direct
   observable for the `127` fix.

Compare side-by-side against
[`reference/Solution Manager v2.html`](../../design/frontend/reference/Solution%20Manager%20v2.html)
(`#/solutions/cache/instances/…/monitoring`) before calling it done.

---

## Notes for the executing session

- **Commit directly to `dev/frontend`** — no per-task branch or PR, per the established FE-NNN pattern.
  **Ask before every commit.**
- Write `docs/plans/frontend/reports/task-NNN-report.md` for each of `126`–`132`.
- Every touched file carries a long header comment explaining its decisions. **Extend** those headers
  with what changed and why; do not strip them.
- `I2` applies. The ECharts theme-key claim above was checked against
  `frontend/node_modules/echarts/lib/theme/dark.js`. Verify any further ECharts option semantics the same
  way — the installed source, or WebFetch of the ECharts docs. Not recall.
- **Sequence:** `126` and `127` are independent of `129`–`132` and fix the most visible breakage. Land
  them first, so everything after is judged against a chart that actually draws.

---

## Review

**Status: complete.** All seven tasks landed on `dev/frontend` as one commit each (`add69f0`…`67dc23a`),
each with a report in [`reports/`](reports/). Final state: `npm run typecheck` clean, `npm run lint` clean at
`--max-warnings=0`, `npm test` **326 files / 2222 tests**, `npm run verify:reflow` **137 passed**,
`npm run verify:palette` **169 passed**, and a new `e2e/monitoring-visual.spec.ts` **4 passed** against the
dev server at 1440px.

### The intended outcome, checked

| Plan's stated outcome | Result |
|---|---|
| Charts render correctly | ✅ canvas present with real size; verified in a real browser, not just jsdom |
| Screens read like the reference | ✅ themed axes, horizontal gridlines only, legend below, split card chrome, one-row toolbar |
| Monitoring → Explore → Save → see it on the dashboard | ✅ walked end to end in `monitoring-visual.spec.ts` |

### Where the plan was wrong, and what was done instead

The plan was accurate on all four root causes (A–E), each confirmed before fixing. Five details did not
survive contact:

1. **`xAxis.boundaryGap: false`** (task 128) is category-axis-only; a time axis takes a `[start, end]`
   tuple. Used `[0, 0]`.
2. **`formatUnitValue` cannot produce the plan's own stated tick labels** (task 128) — it returns `85.5%`
   and `47,900 count`, not `0.7` and `47.9k`. Added `formatAxisValue` beside it; the ratio-vs-percent
   question was put to the user rather than guessed.
3. **`HiddenDefaultWidget` had no dashed border to "keep"** (task 129). The reference's `.hidden-def` is
   dashed and the component's own header claimed to mirror it, so it was added.
4. **No feature component has a gallery entry** (task 130) — the gallery is a `shared/ui` catalogue, so
   "matching the sibling monitoring components" means test + a11y test.
5. **`role="application"` is not what ships** (task 132) — see below.

### Three defects found by the visual proof that no unit test could see

The screenshot pass earned its place in the plan:

- **ECharts overwrites the chart container's `role` and `aria-label`.** The plan's own canvas check found
  zero `[role="application"]` elements while two canvases drew fine: `aria: { enabled: true }` replaces both
  at runtime. `tabIndex` and `aria-describedby` survive, so keyboard access is intact. **Left as-is and
  raised as a finding** — §18 explicitly wants ECharts' own reading, so reconciling the two is a decision,
  not a drive-by edit.
- **The gauge was clipped** by a flat 150px row track. Fixed at the root: `gridAutoRows: minmax(150px, auto)`.
- **The gauge fixture was invalid** (`acrossInstances: 'avg'` over one instance), rendering "failed to load".

### Still open, deliberately

- **`frontend/public/fonts/` is empty** — 14 font files 404 with `OTS parsing error`, so every screenshot
  above renders in a fallback face. `FE-002`'s deferred acquisition task; needs binary assets plus an OFL
  licence check. Recorded in task `132`'s report as the plan requires.
- **`InstanceShell` still does not wrap Explore or the standalone dashboard** — out of scope by the plan's
  own reasoning (a boundaries cycle). The sub-tab bar gives the reachability.
- **Brand paint** — `FE-D5` / open item 19 unchanged. No new colour literals were introduced; every change
  uses existing `@shared/theme` tokens.
- **The §18 a11y divergence above**, which wants an issue against the design rather than a code change.

### A note on the environment, not the code

Several full-suite runs hung for 15+ minutes per file, on files unrelated to any change here. The cause was
the machine — swap at 14.9 GB of 16 GB, load average 21 on 10 cores — not the suite; every affected test
passed in seconds alone, and the suite completed normally once load fell. Reducing `--maxWorkers` did not
help; only relieving memory pressure did. Recorded because issue #131 (`maxWorkers: '50%'`) is about this
class of failure, and the lever under memory pressure is the runner's memory, not the worker count.
