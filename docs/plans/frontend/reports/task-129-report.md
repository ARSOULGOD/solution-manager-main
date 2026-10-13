# FE-129 · Widget card sizing and chrome — Report

## What this closes

The fourth defect in plan `126`: **`placement.h` meant nothing on screen.** `DashboardGrid` spanned
`placement.h` grid rows, but the rows were content-sized, so "span 2" was just "two rows of whatever height
the content happens to be". `WidgetFrame` then used a flat `WIDGET_BODY_HEIGHT = 220` regardless — so an
`h: 2` widget stretched its card and left the remainder empty under the plot.

## What changed

**`DashboardGrid`** — `gridAutoRows: '150px'` at `lg`+ and the reference's own `gap: 14px` (`.grid12`,
`theme.css:542`). A row track now has a real height, so `gridRow: span h` finally resolves to one.

**`WidgetFrame`** — the flat constant is replaced by an exported `widgetBodyHeight(h)`:

```
h * 150 + (h - 1) * 14      (floored at 150)
```

derived from the *same two numbers* `DashboardGrid` uses, so the card and its cell agree. The `(h - 1) * 14`
term recovers the gaps a spanned cell absorbs between its rows — without it an `h: 2` card would come up 14px
short. The floor covers `h: 1`, where 150px minus the header is already tight.

**The card is now a flex column** (`Paper surface="flat" fill`) with the reference's split chrome: a header
at `11px 14px 10px` with a `border-bottom` rule and a 13.5px/600 title (`.widget-h` / `.widget-h .wt`), and a
body at `12px 14px` with `flex: 1` (`.widget-b`). The card itself carries no padding — the two regions do.

## One bug found while building this, fixed at the root

A first pass gave the body a **fixed** `height: bodyHeight`. That is wrong in the regime this task creates:
at `lg`+ the cell is already `h * 150 + gaps` tall, so a body demanding that full height plus a ~40px header
plus 24px of body padding pushes the card past its own cell — and `Paper`'s `fill` carries
`overflow: hidden`, so the bottom of the plot would have been **clipped**, trading dead space for lost data.

The fix is `bodySlotSx`, which handles both regimes explicitly:

| Regime | Behaviour | Why |
|---|---|---|
| `lg`+ | `flex: 1`, `minHeight: 0` | The cell already has a height; the slot fills what the header leaves |
| below `lg` | `flex: 1`, `minHeight: bodyHeight` | No `gridRow` at all — rows auto-size, so nothing imposes a height and the slot must bring its own |

Applied uniformly to all four body states (skeleton, error/no-data, too-new, live), so a loading card is the
same height as the chart that replaces it — no layout shift on arrival.

## The plan was wrong about one thing, and it was worth fixing

> "`HiddenDefaultWidget` keeps its dashed border"

**It had no dashed border.** It rendered a plain `<Paper>`, even though its own file header claims to mirror
the reference's `.hidden-def` card, and the reference (`theme.css:547`) is explicitly
`border-style: dashed`. Nothing drew the dash. Since FE-129 is "card sizing and **chrome**" and the
reference is the source of truth (`FE-D30`), this was closed rather than left: a border *style* is not a
colour, so `FE-D5`'s block on brand paint does not apply.

## Two new `Paper` props — named, not an `sx` escape hatch

`Paper`'s contract is that callers pick a described behaviour rather than hand-rolling layout, so both
additions are named booleans defaulting to `false`; no existing caller changes shape (pinned by a test).

- **`fill`** — stretch to the container and lay children out as a column. Required by the plan's own "make
  the `Paper` a flex column so the body fills its slot"; `Paper` had no way to express it.
- **`dashed`** — the placeholder-card border above.

Both carry `data-fill` / `data-dashed` attributes, following the existing `data-surface` precedent, so tests
assert on the rendered surface rather than on internals.

## Tests

- `Paper.test.tsx` — three new: neither prop on by default, `fill` produces `height: 100%` + column flex,
  `dashed` produces `border-style: dashed`. Asserted through `getComputedStyle` on the rendered element.
- `Paper.gallery.tsx` — entries for both, with `fill` shown inside a fixed-height container since that is
  the only place it is visible.
- `WidgetFrame.test.tsx` — four new `widgetBodyHeight` cases: one row, the gap-recovery arithmetic for
  `h: 2`/`h: 3`, strict monotonicity in `h`, and a floor for `h <= 0` rather than a collapsed card.

## Files

| File | Change |
|---|---|
| `src/features/monitoring/dashboard/DashboardGrid.tsx` | `gridAutoRows: 150px` at `lg`+, gap 14px, named constants. |
| `src/features/monitoring/dashboard/WidgetFrame.tsx` | `widgetBodyHeight` + `bodySlotSx`; flat/fill card with header rule and reference padding. |
| `src/features/monitoring/dashboard/HiddenDefaultWidget.tsx` | Dashed, fills its slot. |
| `src/shared/ui/Paper/Paper.tsx` · `Paper.types.ts` | New `fill` and `dashed` props. |
| `src/shared/ui/Paper/Paper.test.tsx` · `Paper.gallery.tsx` | Coverage and demos. |
| `src/features/monitoring/dashboard/WidgetFrame.test.tsx` | `widgetBodyHeight` cases. |

## Verification

- `npm run typecheck` — clean
- `npm run lint` — clean at `--max-warnings=0`
- `npx prettier --check` — clean on every touched file
- `src/features/monitoring/dashboard` — 18 files, 118 tests, all passed
- `npm test` — **324 files, 2198 tests, all passed** (up from 2191)

The pixel claims this task exists to produce — an `h: 2` widget whose plot occupies >60% of the card body,
and a hidden default sitting at full slot height — are the plan's own task-`132` visual step and are not
claimed here.
