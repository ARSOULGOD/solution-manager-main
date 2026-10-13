# FE-132 · Make the save flow land, enrich the fixture, and prove it on screen — Report

## What this closes

The last of plan `126`'s four defects, plus the plan's own non-optional visual proof.

The dashboard fixture was **write-only theatre**: `GET` returned a static literal, `POST /widgets` echoed the
request body and dropped it, `PUT /layout` and `PATCH /widgets/:id` returned `204` and changed nothing, and
`POST /reset` returned `widgets: []` — so **"Reset defaults" emptied the board** instead of restoring the
package layer. An operator could walk Explore → Save as widget → see a success toast → and land on a
dashboard that never showed the widget.

## The store

`shared/testdoubles/handlers/dashboardStore.ts` holds one document per dashboard id, seeded from a frozen
package default. Same posture as `app/dev/devPackageStore.ts` — module state behind named functions, no
top-level side effects — but memory-only, since a fixture has no reason to survive a reload and
`sessionStorage` would leak one test run's writes into the next.

`resetDashboardStore()` is called from `src/test/setup.ts`'s `afterEach`, exactly as `resetConfigGenerations()`
already is: `server.resetHandlers()` restores handlers, never the module state they close over.

`POST /widgets` now assigns a **server-side id** rather than echoing the caller's, which is what a real
server does; `dashboardRepository.test.ts`'s round-trip assertion was updated to match, and five new cases
pin the behaviours that were previously untestable — a saved widget is on the board *with a placement*
(without one `DashboardGrid` skips it entirely), reset restores rather than empties, `hideDefault` survives
a re-read, `setLayout` persists, and the seed really does carry a gauge, a threshold, a hidden default and a
too-new widget.

## The six reference widgets

`reference/app/dashboards.jsx`'s own `DASH`, replacing a two-widget fixture (one line, one single-stat) that
demonstrated nothing: `GaugeMark`, threshold rendering and `HiddenDefaultWidget` were all shipped but
unexercised on screen, and every widget was `h: 1`, so task `129`'s sizing had nothing to show either.

Two deliberate departures from the reference's literal values, both because the reference's numbers are
tuned to *its* synthetic data:

- **Threshold `16`, not `24`.** The fixture's evictions series runs ~12–18, so a threshold at 24 sits
  off-scale and the `markLine` is never drawn — a threshold that demonstrates nothing is the exact defect
  this fixture exists to fix.
- **The gauge uses `acrossInstances: 'none'`.** Seeded with `'avg'` first, and the screenshot pass caught it
  rendering *"This widget failed to load"*: `console.ts`'s own `aggregationError` refuses cross-instance
  aggregation over fewer than two instances, and this selector resolves to one.

## Explore navigates to the board on save

`ExplorePage` invalidates the dashboard query and navigates to Monitoring on success, so the operator sees
the widget land. The toast alone left them on Explore with no way to tell whether anything had been added —
and the fixture happily reported success while discarding it, with nothing on screen to contradict the
claim.

## Visual proof — three defects it caught that no unit test could

`e2e/monitoring-visual.spec.ts` (new, joins the `fixture` project) drives the real dev server at 1440px.
These are assertions about the rendered page, with screenshots as artefacts alongside. **4 passed.**

**1. `role="application"` is not what ships.** The plan's own check — *"the line widget has a `<canvas>`
child under `[role="application"]`"* — found **zero** such elements while two canvases were happily drawing.
ECharts' `aria: { enabled: true }` (design §18's last row) **overwrites the container's `role` and
`aria-label` at runtime**: the shipped element is `role="img"` named by ECharts' generated data description,
not `role="application"` named by the widget title, which is what `ChartRenderer` sets and what §18
documents. `tabIndex={0}` and `aria-describedby` **do** survive, so the keyboard focus stop and the hint are
intact. **Not silently changed here** — §18 explicitly wants ECharts' own reading, so reconciling the two is
a decision, not a drive-by edit. Recorded below as a finding; the spec asserts reality
(`[_echarts_instance_]`) and says why in its header.

**2. The gauge was clipped by a flat 150px row.** `GaugeMark`'s arc plus the card header exceed one row, and
`Paper`'s `fill` carries `overflow: hidden`, so the bottom of the gauge was cut off. Fixed at the root in
`DashboardGrid`: `gridAutoRows: minmax(150px, auto)`. `h` still means "this many 150px rows", but a widget
that genuinely needs more is never clipped. The reference does not hit this because its `.grid12` rows are
content-sized; ours carry `h` explicitly, so they need the floor **and** the give.

**3. The gauge fixture was broken**, as above.

The spec also proves the whole loop end to end: Add widget → Explore → pick metric → pick three instances →
Save as widget → **the widget is on the board** (scoped to a widget card, because the success toast carries
the same title and an unscoped text match would pass without the widget ever landing) → Reset defaults →
the six package widgets come back, hidden default included.

A fourth test toggles dark mode. It is the plan's step-4 check for task `127`, and it does a second job:
switching mode changes the MUI theme → the memoized factory identity → `useEcharts` disposes and recreates.
That is precisely the path task `126` rewrote, so a chart surviving a theme toggle is direct evidence the
serialisation did not break recreation. The instance id changes and the new canvas has real size.

## Out of scope, confirmed on screen — `frontend/public/fonts/` is empty

As the plan requires, recorded here as a **separate blocking visual defect**. Every Rubik / Inter /
JetBrains Mono request 404s; the browser console carries `Failed to decode downloaded font` and
`OTS parsing error: invalid sfntVersion: 1008821359` for **14** font files, and the console falls back to
system fonts. This is `FE-002`'s deferred acquisition task — it needs binary assets plus an OFL licence
check, so it is not fixable here. Everything in the screenshots is therefore rendering in a fallback face,
not the console's own type.

## Finding for follow-up: the chart's a11y contract diverges from §18

Not fixed here, and not a regression from this plan — it has been true since task 074 set
`aria: { enabled: true }`.

| §18 / `ChartRenderer` says | What ships |
|---|---|
| `role="application"` | `role="img"` (ECharts overwrites) |
| `aria-label` = widget title | ECharts' generated data description |
| one keyboard focus stop | ✅ `tabIndex="0"` survives |
| `aria-describedby` → keyboard hint | ✅ survives |

The unit and axe suites cannot see this: they mock `echarts`, so its aria module never runs. Worth an issue
against §18 to decide which of the two readings is intended — the app-role-with-title, or ECharts' own
description — rather than leaving the code and the design doc disagreeing.

## Files

| File | Change |
|---|---|
| `src/shared/testdoubles/handlers/dashboardStore.ts` | New — the stateful document, six-widget seed, package-default reset. |
| `src/shared/testdoubles/handlers/console.ts` | Dashboard handlers now read/write the store. |
| `src/test/setup.ts` | `resetDashboardStore()` in `afterEach`. |
| `src/features/monitoring/explore/ExplorePage.tsx` | Invalidate + navigate to the board on save. |
| `src/features/monitoring/dashboard/DashboardGrid.tsx` | `gridAutoRows: minmax(150px, auto)`. |
| `e2e/monitoring-visual.spec.ts` · `playwright.config.ts` | New visual-proof spec, registered on `fixture`. |
| `dashboardRepository.test.ts` · `DashboardView.test.tsx` · `ExplorePage.test.tsx` | Updated for the six-widget fixture; 8 new cases. |

## Verification

- `npm run typecheck` — clean
- `npm run lint` — clean at `--max-warnings=0`
- `npm test` — **326 files, 2222 tests, all passed**
- `npx playwright test --project=fixture monitoring-visual` — **4 passed**
- Screenshots reviewed against `reference/Solution Manager v2.html`: both line charts draw with
  theme-derived axes and horizontal dashed gridlines only, the threshold renders as a dashed rule, the gauge
  reads 73.1% un-clipped, the single-stat, hidden-default and read-only cards all render, the sub-tab bar and
  one-row toolbar are present, and dark mode carries the theme into the axes.
