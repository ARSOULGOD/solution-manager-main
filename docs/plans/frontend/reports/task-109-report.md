# FE-109 · Responsive Monitoring — Report

## What I implemented

Five gated control steps and a 12-column dashboard grid, usable at 320 px, with charts that stay
honest when they get small (task spec: `docs/plans/frontend/implementation.md` §FE-109; refs Dec
`FE-D29` `DM-D21` `DM-D17` `UI-D8`, Design §11/§12, UI §A.10/§A.11, WCAG 2.2 SC 1.4.10).

| File | Summary |
|---|---|
| `src/features/monitoring/explore/ControlStep.tsx` | Below `lg`, the header (badge/title/subtitle/chevron) becomes a real `<button>` with `aria-expanded`, toggling the body — the same disclosure idiom `SectionCard.tsx`/`RolloutCard.tsx` already use in Designer, not MUI `Accordion`. The locked-reason line stays **outside** the collapsible region on purpose: a locked step that loses its reason on collapse is worse than a locked step. At `lg`+, nothing changed — no button semantics, no chevron, always expanded, same DOM as before. |
| `src/features/monitoring/dashboard/DashboardGrid.tsx` | Responsive `gridTemplateColumns`: 12 at `lg` (unchanged explicit `{x,y,w,h}` placement, `Edit layout` untouched), 6 at `md`, 1 at `sm` and below. Below `lg` no `gridColumn` start / `gridRow` is set at all — items auto-place in the DOM order the caller already pre-sorts by `order` (`DashboardView.tsx`'s `visibleWidgets`), which is the same "must not depend on pixel position" contract task 086/§18 already state, not a new one. `w` still clamps the column span at `md` so a wide widget doesn't shrink to the same single column as a narrow one. |
| `src/features/monitoring/dashboard/DashboardToolbar.tsx` | `position: sticky; top: 0` (matching the reference's own `.dash-toolbar`) plus `flexWrap: nowrap` + `overflowX: auto` — SC 1.4.10's persistent-toolbar exception, one non-wrapping row that scrolls on its own axis rather than five controls wrapping onto several lines at the reflow floor. |
| `src/features/monitoring/chart/ChartDataTable.tsx` | Extracted `ChartDataTableContent` (the summary + per-series tables) from the `Dialog`-wrapped `ChartDataTable`. Both the modal (`sm`+) and the new inline path (below `sm`, in `ChartRenderer`) call the same function, so a column or stat can never drift between them. |
| `src/features/monitoring/chart/ChartRenderer.tsx` | Two independent width thresholds. Under `md`: `buildOption`'s new `compactLegend` param switches the legend from vertical/right (`grid.right`) to horizontal/bottom (`grid.bottom`), still `type: 'scroll'` — it paginates sideways rather than truncating (G12) or growing into unbounded rows. Under `sm`: the canvas is **not unmounted**, it's `display: none`'d, and `ChartDataTableContent` renders inline in an `overflowY: auto` box occupying the same footprint — "a 320 px canvas is not readable" (task spec), offered directly rather than behind `Enter`/the button. Kept mounted rather than conditionally removed because `useEcharts`'s create/dispose effect keys on `containerRef`/`createInstance` identity, neither of which changes when the breakpoint flips — unmounting the `ref`-carrying `div` on a live resize would strand the chart instance with nothing to notice the container came back. |
| `src/features/monitoring/explore/ControlStep.test.tsx`, `DashboardGrid.test.tsx`, `DashboardToolbar.test.tsx`, `ChartRenderer.test.tsx`, `ExplorePage.test.tsx` | New responsive coverage (below). |

`ExplorePage.tsx` needed **no change** — its outer grid was already `1fr` below `lg` / `340px 1fr`
at `lg`+ (task 077); the accordion requirement is entirely `ControlStep`'s. Claiming a layout
change there would have been a change made to have something to report.

## Rulings

1. **A real `<button>`, not MUI `Accordion`, for `ControlStep`.** Matches the established
   in-repo disclosure idiom (`SectionCard.tsx`'s own header records the same choice, and
   `RolloutCard.tsx` cites it explicitly) rather than introducing a second one for this feature.
2. **The lock reason is structurally outside the collapsible region**, not just usually-visible.
   `showChildren` (gates the body) and the `locked &&` reason block are two separate conditionals;
   there is no code path where collapsing a locked step also hides why it's locked.
3. **`DashboardGrid`'s responsive columns are a CSS-only (`sx` breakpoint) concern, matching
   `ExplorePage`'s own two-column-at-`lg` layout and `Grid.tsx`'s (task 105) container-query
   columns** — neither of those is asserted for its literal resolved column count in a jsdom test
   either (checked: `layout.test.tsx`'s `Grid` case only asserts `containerType` + no overflow).
   jsdom has no real layout engine and doesn't evaluate `@media`/`@container` conditions against a
   controllable viewport the way `useMediaQuery` (backed by this repo's `matchMedia` polyfill)
   does — so DOM order and `assertReflow` are what's unit-tested here, and the actual column-count
   swap is confirmed in a real browser (below), the same limitation FE-108's report already states
   for its own CSS-only layout pieces.
4. **`ChartRenderer`'s two thresholds are `useMediaQuery`-driven (JS), not CSS-only**, specifically
   *because* they needed to be both testable (`setViewportWidth` + `getComputedStyle`/DOM
   assertions) and because the `sm` threshold changes what actually mounts (table vs. canvas), not
   just a style.
5. **The canvas `<div>` stays mounted, hidden via `display: none`, below `sm`.** Conditionally
   unmounting it was the first approach tried; rejected because `useEcharts.ts`'s create effect
   depends on `[containerRef, createInstance]` — the ref object's identity never changes, so
   removing and re-adding the `div` on a live resize would never re-trigger that effect, leaving a
   stale, disposed instance bound to a detached node and no live one for the div that reappears.
   `display: none` preserves the ref, the instance, and the `ResizeObserver`; the observer picks
   the real size back up the moment the box is shown again (verified in a real browser, below).
6. **The inline table scrolls within the same fixed-height footprint the canvas occupied**
   (`overflowY: auto`), rather than growing the box. `ExploreResult.tsx` and `WidgetFrame.tsx` both
   give `ChartRenderer` a `height: <fixed px>` wrapper (`EXPLORE_CHART_HEIGHT` /
   `WIDGET_BODY_HEIGHT`) — growing past that would overlap whatever renders after it in normal
   flow, since neither ancestor is `overflow: visible`-safe for a taller child. This let the whole
   feature stay inside `ChartRenderer.tsx`, matching the task's own `Files` list, instead of also
   touching those two callers.

## A pre-existing flake found, not fixed (out of scope)

Real-browser verification (below) intermittently found the ECharts canvas missing entirely, with
`[ECharts] There is a chart instance already initialized on the dom` / `Instance … has been
disposed` warnings in the console. Bisected with `git stash`: **reproduces identically on the
pre-FE-109 code**, at a constant 1280 px with zero resizing (1 failure in 5 runs). Root cause is a
classic React 18 `StrictMode` double-invoke race in `useEcharts.ts`'s create effect: the effect's
async body (`await import('echarts')` then `echarts.init`) can have its first invocation's promise
resolve *after* the second invocation's, so the "cancelled" first instance's `dispose()` runs after
the second (live) instance already called `setOption`, leaving `chart` pointing at a disposed
instance. `StrictMode` only double-invokes in development, so this should not reach production, but
it is a real, pre-existing bug in code this task did not touch (`useEcharts.ts`, task 072). Flagged
rather than fixed — the same posture FE-108's report takes for its own out-of-scope discovery,
except that one blocked test runs and this one only affects live dev-server sessions (every
`ChartRenderer` jsdom test mocks `echarts` entirely, so this race never surfaces in the suite).
Worth a follow-up issue.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                                     # clean
npm run typecheck                                                # clean
npx vitest run src/features/monitoring --environment jsdom       # 63 files, 456 tests — all passed
npm test                                                         # full suite: 302 files, 1871 tests — all passed
npm run build                                                    # production build succeeds; echarts stays its own chunk
```

(`npm run test:a11y` does not exist as a script — the same pre-existing gap FE-106/107/108's
reports already note; `.a11y.test.tsx` files ran as ordinary Vitest tests instead, included above.)

Task-specific acceptance items:

- **Locked steps keep their reasons** — `ControlStep.test.tsx` (collapsing a locked step at 320 px
  leaves its reason on screen, body hidden) and `ExplorePage.test.tsx` (all three "Pick a metric
  first." reasons present at the 320 px floor, both before and after collapsing an unrelated step).
- **Stacking follows `order`, not position** — `DashboardGrid.test.tsx`'s existing
  order-sorted-DOM-sequence assertion re-run at the 320 px floor (unaffected by the CSS-only column
  change, which is the point); real-browser confirmation below.
- **No per-widget time pinning** — unchanged code (`DashboardView`/`WidgetFrame` still read only
  the dashboard's `absoluteRange`/`resolution` props); not re-asserted here since this task touched
  neither file.
- **Legend never truncates** — `ChartRenderer.test.tsx`: under `md`, `legend.data` still lists every
  series (`type: 'scroll'`, `orient: 'horizontal'`); at `md`+, unchanged vertical/scroll legend.
- **No zero-fill at any width** — unchanged code path (`buildSeriesData`/gap handling untouched);
  the keyboard-accessibility describe block's existing "announces `gap`" test still passes
  unmodified with the new `compactLegend`/`primaryTable` hooks added above it.
- **Tabular alternative primary below `sm`** — `ChartRenderer.test.tsx`: at 320 px, no dialog, a
  real `<table>` on screen immediately, the canvas region still in the DOM but `display: none`;
  crossing back above `sm` restores the canvas and removes the inline table.
- **No horizontal scroll at 320 px** — `assertReflow(root, reflowFloorPx)` on `ExplorePage` (full
  page) and `DashboardGrid`; `DashboardToolbar`'s sticky/nowrap/scroll styling asserted directly via
  `getComputedStyle`.

**Real-browser confirmation**, via the dev server (`npm run dev`, MSW-fixture-backed — the same
posture CLAUDE.md's "fixture-backed with no server behind it" describes) driven by an ad-hoc
Playwright script (no `chromium-cli` in this environment; same approach FE-108's report used),
against `/solutions/cache/instances/cache-prod-01/monitoring/explore` and
`.../monitoring/dashboards/db_cache-prod-01`:

- **Explore**, 1280/900/700/320 px: `document.body.scrollWidth === document.documentElement.clientWidth`
  at every width (no page-body horizontal scroll), zero console errors. At 320 px, all five steps
  render as `aria-expanded="true"` disclosure buttons with a visible chevron; at 1280 px, no button
  semantics, no chevron, identical to the pre-FE-109 screenshot.
- **Dashboard** (`Hit rate` — a `line` widget — and `Cache size` — a `single-stat` widget), same
  four widths: no horizontal overflow, zero console errors. At 1280 px, the two widgets sit side by
  side (8/12 + 4/12, unchanged). At 1000 px (`md`, the 6-column band), both widgets stack to one
  column each spanning the full 6-column row, with the **vertical** legend (right edge) — `1000 ≥
  905`, so this is correctly *not* "under `md`". At 900 px (just below the `md` threshold), the
  legend moves **below** the plot, horizontal, `OPEN DATA TABLE` still offered as a button (canvas
  still primary — `900 ≥ 600`, above `sm`). At 320 px, both widgets stack full-width and the `Hit
  rate` widget shows the tabular alternative inline — range, series count, min/max/last, and a real
  `Timestamp`/`Value`/`Coverage` table — with **no** canvas, **no** "Open data table" button, and
  the toolbar collapsed to just `Time range` with the rest reachable by its own horizontal scroll
  (`Refresh`/`Auto·Raw`/`Layer` scrolled off, per the persistent-toolbar exception).

Screenshots were reviewed inline during the session (not retained — this repo has no
committed-screenshot convention for dev-server captures) and are the source of the "at 1000 px …"
/ "at 900 px …" claims above; the widths and DOM assertions quoted are from the Playwright script's
own `page.evaluate` output, reproduced verbatim in this session's transcript.

## Status

DONE — implemented, tested (1871/1871 on the full run), lint-clean, typecheck-clean, production
build succeeds, real-browser-confirmed at four widths on both Explore and the Dashboard route. Not
yet closed on GitHub (issue #129) — pending review, particularly Ruling 5 (canvas hidden not
unmounted below `sm`) and the pre-existing `useEcharts.ts` StrictMode race flagged above, which is
worth its own follow-up issue rather than folding a fix into this one.
