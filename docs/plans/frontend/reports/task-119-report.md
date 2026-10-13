# FE-119 · Package Version column, and a grid whose column widths actually apply — Report

## What this closes

Reported as "the instances grid is congested". The root cause was in the shared `DataTable`, not in the
instance column list — and a second, separate cause was found in the browser that the plan's stated fix would
not have addressed.

## Cause 1 — `width` and `flex` do not compose

`DataTable` built every `GridColDef` with **both** `width` and `flex: 1`.

Per `I2`, verified before writing the fix rather than asserted from memory. MUI X's own column-dimensions page
(<https://mui.com/x/react-data-grid/column-dimensions/>, fetched 2026-08-26) states it outright:

> "flex doesn't work together with width. If you set both flex and width in GridColDef, flex will override
> width."

The same page confirms the rest of the mapping: columns have "a minimum width of 50px" by default and
`minWidth` changes that per column; a column with neither prop is 100px wide; and `flex` divides *remaining*
space proportionally — its example resolves `width: 200`, `flex: 1`, `flex: 0.5` in a 500px grid to
"200px, 200px, 100px" because "the column with flex: 1 is twice the size of flex: 0.5". `minWidth` is
documented as available on flex columns, i.e. as a lower bound.

So every declared width in `columns.tsx` — 90, 170, 220 — was silently discarded, and nine columns rendered at
identical width.

**The fix** expresses the declared width in the two properties that actually compose:

```ts
const base = column.width ?? 160;
const def: GridColDef = { field, headerName, minWidth: base, flex: base / 100 };
```

`minWidth` is the floor, `flex` a proportional share. `/100` is a scale, not a magic number: it turns "220px"
into "2.2 shares", so a wide column stays proportionally wide when there is room and never narrower than
declared when there is not. Measured in a browser, the ten columns now render at
`240 · 88 · 110 · 150 · 170 · 210 · 190 · 210 · 96 · 120`, exactly their declared floors, with the grid
scrolling horizontally (1690px of content in a 1078px viewport). **That horizontal scroll is the intended
outcome, not a regression** — the alternative is ten columns at identical width with their contents truncated.

## Cause 2 — found in the browser: it was never a row-height problem

The plan's second bullet said two-line cells clip at compact density's 36px rows and prescribed an explicit
`rowHeight` per density. Setting one **did not fix the clipping**, and the browser showed why.

Two findings, both from measurement rather than inspection:

**(a) `density` scales `rowHeight`; it does not replace it.** `rowHeight={44}` rendered **30px** compact rows —
worse than the 36px default it was meant to fix. MUI applies `Math.floor(rowHeight × densityFactor)`; the docs
say so only obliquely ("Density is calculated based on the `rowHeight` and/or `columnHeaderHeight` props, when
present" — <https://mui.com/x/react-data-grid/row-height/>) and do not publish the factor. Rather than bake in
a reverse-engineered constant, the target is divided by the library's **own exported**
`COMPACT_DENSITY_FACTOR`, which is public API — so a future change to it moves this with it instead of
silently re-breaking the clipping.

**(b) The clipping cause was `line-height`, and no row height would have fixed it.** MUI centres single-line
cell text by setting `line-height` on the cell equal to its content height. An inline first child therefore
generates a line box as tall as the *whole row*, pushing any second line out of an `overflow: hidden` cell.
Measured: `InstanceCell`'s two 16px lines occupied **79px inside a 64px cell**, with "Payments / Ledger"
starting exactly at the cell's bottom edge. Raising `rowHeight` cannot help — the line-height scales with it,
which is why the org/sub-org and replica-summary sub-lines were missing at *both* densities before this fix,
not just compact.

The fix is `display: flex; align-items: center; line-height: normal` on `.MuiDataGrid-cell`: centring on the
box rather than on a line box is what lets `line-height` go back to normal and each `Typography` use its own.
Cell content then measured 34px inside a 64px cell, fully visible.

With the real cause fixed, the row heights are sized to the tallest content the grid actually draws: **64
standard, 52 compact**. Compact is 52 rather than the plan's 44 because the Config cell wraps two chips onto a
second row and measured 50px — 44 clipped it. Measured, not rounded to a nice number.

## The new column

`field: 'packageVersion'`, inserted **after `env`** and before `driver` — it is identity (which schema this
instance is configured against), so it belongs with the other identity columns rather than out among the
observed state.

Rendered as `<Chip tone="neutral" mono label={…} />`, following the reference, which renders exactly this in
its page head (`docs/design/frontend/reference/app/instances.jsx:93`:
`<Chip tone="neutral" sm><span className="mono">pkg v{…}</span></Chip>`). `ChipProps.label` is `string`-only,
so `mono?: boolean` was added to `@shared/ui/Chip` — types, component, gallery entry and tests.

**Deliberately not a semantic tone.** `ok`/`warn`/`err` mean *health* in this vocabulary; a version is not a
health state.

Widths re-tuned now that they take effect: Instance 240 · Env 88 · Pkg 110 · Driver 150 · Locus 170 ·
Health 210 · Version 190 · Config 210 · Replicas 96 · Last seen 120.

The card view below `md` is untouched (`FE-D29`) — only the desktop grid changed.

## Files

| File | Summary |
|---|---|
| `src/shared/ui/DataTable/DataTable.tsx` | `minWidth` + proportional `flex`; density-compensated `rowHeight`; the `line-height` fix; header documents that a declared width is a floor. |
| `src/shared/ui/DataTable/DataTable.columnDimensions.test.tsx` | **New.** The regression, asserted at the seam that broke. |
| `src/features/inventory/instances/columns.tsx` | The `packageVersion` column; re-tuned widths; header records why the numbers now matter. |
| `src/shared/ui/Chip/{Chip.types.ts,Chip.tsx,Chip.gallery.tsx,Chip.test.tsx}` | `mono`. |
| `src/features/inventory/instances/columns.test.tsx` | New cell, its `valueGetter`, and the column order. |
| `src/shared/i18n/messages.en.ts` | `instancesColPackageVersion`. |

## Rulings

1. **The width regression is asserted against the column DEFINITIONS, not the rendered header cells.** jsdom
   gives the grid no measurable container, so MUI's own layout pass resolves every header to `width: 0px`
   whatever it was told — a DOM assertion would have been vacuous. The definitions are what this component
   owns; the layout is MUI's, and the fetched docs pin its behaviour. The test mocks only `DataGrid`, captures
   its `columns` prop, and delegates to the real component via `createElement`.
2. **The `line-height` fix lives in `DataTable`, not in the three cells.** It is a property of how the shared
   table renders *any* multi-line cell — `configCell` clipped too, and it is not on the plan's list of three.
   Fixing it per-cell would have left the next multi-line cell to rediscover it.
3. **`COMPACT_DENSITY_FACTOR` over a measured constant.** `30/44 = 0.68…` was the observation; `0.7` is the
   library's exported value. Importing it keeps the intent ("render 52px") in the code and the arithmetic in
   MUI's hands.

## Verification

`npm run lint` · `npm run typecheck` · `npm test` · `npm run build` — all green.

Driven in a browser at `npm run dev`, 1440×950, zero console errors:

- Ten columns at visibly different widths, matching their declared floors exactly.
- Horizontal scroll (`scrollWidth 1690` vs `clientWidth 1078`), no truncation; `Last seen` present on
  scroll-right.
- **No clipped cells at either density**, asserted programmatically by comparing every cell's content
  `scrollHeight` against its `clientHeight` — the same probe reported six clipped cells before the fix.
- Row heights: 64 standard, 52 compact.
- Below `md`, the card view still renders all twelve rows.
