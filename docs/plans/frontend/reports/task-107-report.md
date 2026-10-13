# FE-107 · Responsive data presentation — the table-to-card transform — Report

## What I implemented

A 9-column inventory grid renders at 320px without losing a single state the operator needs to see
(task spec: `docs/plans/frontend/implementation.md` §FE-107; refs Req `REQ-005` `REQ-008`, Dec `FE-D29`
`FE-D11` `UI-D9`, WCAG 2.2 SC 1.4.10, UI §A.6).

| File | Summary |
|---|---|
| `src/shared/ui/DataTable/CardView.tsx` | New. The card presentation. Pure function of `rows`/`columns`/`expanded`/`selection` plus callbacks — never derives a value itself. Every cell comes from the same `ColumnDef.renderCell`/`valueGetter` the grid uses; the only branch is on column *metadata* (`cardEmphasis`), never on a column's *value*. Cards render: a checkbox (if selectable) + the primary column, a wrapping cluster of `cardEmphasis: 'chip'` columns, then the rest as a label/value grid, then — if expanded — the same `detailPanel(row)` content inline. |
| `src/shared/ui/DataTable/DataTable.types.ts` | New `ColumnDef.cardEmphasis?: 'primary' \| 'chip'` (structural card-layout hint) and `DataTableProps.bulkActions?: ReactNode` (selection-dependent actions, rendered inline in the toolbar at `md`+, moved to a sticky bottom bar below `md`). |
| `src/shared/ui/DataTable/DataTable.tsx` | `useMediaQuery(theme.breakpoints.down('md'))` picks `CardView` over `DataGrid` below `md`. Expansion (`toggleExpanded`) and a new `toggleRowSelection` are lifted out of the grid's own column definition so both views share the identical state and callbacks — switching views mid-session never loses selection, expansion or focus. `sortModel` is now controlled (`onSortModelChange`) so a new `applySort` helper can mirror the grid's own ordering for `CardView`'s rows (the grid still sorts itself internally; this only keeps the card list's order in sync). The "Density" control is hidden below `md` (compact/standard has no card-view meaning); the Columns-visibility menu stays and now also drives which fields the card view shows. |
| `src/features/inventory/instances/columns.tsx` | Marked `name` as `cardEmphasis: 'primary'` and `env`/`driver`/`locus`/`health`/`version`/`config` as `cardEmphasis: 'chip'`; `replicas`/`lastSeen` fall through to the label/value list — exactly the task's "primary identifier, then state chips, then remaining fields" ordering. |
| `src/features/inventory/instances/InstancesPage.tsx` | Bulk-restart trigger moved from `toolbarExtras` to the new `bulkActions` prop (env-filter `ToggleGroup` stays in `toolbarExtras` — it's a filter, not a selection-dependent action, so it doesn't move to the bottom bar). |
| `src/shared/i18n/messages.en.ts` | New keys: `tableCardListLabel` (the card list's `aria-label`), `tableSelectRow` (per-card checkbox accessible name — `"Select {label}"`, where `{label}` is the row's own primary-column value). |
| `src/shared/ui/DataTable/DataTable.test.tsx`, `DataTable.a11y.test.tsx`, `src/features/inventory/instances/InstancesPage.test.tsx`, `InstancesPage.a11y.test.tsx` | New responsive coverage (below). |

## Rulings

1. **`cardEmphasis` is new column metadata, not inferred.** The alternative — guessing "primary" from
   `hideable: false` or "chip" from the presence of `renderCell` — would have been fragile and implicit.
   Explicit metadata makes the card layout a deliberate per-column choice, matches how `hideable` already
   works, and defaults sensibly (`columns[0]` is the fallback primary) so existing `DataTable` call sites
   (gallery, unit tests) don't have to opt in to get a sane card.
2. **`bulkActions` is a new, separate prop from `toolbarExtras`.** The task says "bulk actions move to a
   sticky bottom bar below `md`" — but `toolbarExtras` is an opaque `ReactNode` blob that, for
   `InstancesPage`, mixes a filter control (env `ToggleGroup`) with a selection-dependent action (Restart).
   `DataTable` can't tell those apart inside an opaque node, so a second, explicit prop was the only way to
   move *only* the action without also relocating the filter.
3. **Sort is mirrored, not shared by reference.** The Community `DataGrid` sorts its own rows internally
   regardless of who owns `sortModel`; there's no API to ask it "what order did you just compute." `applySort`
   re-derives the same order for `CardView` from `sortModel` + each column's own `valueGetter`. It doesn't
   need to tie-break identically to the grid's internal comparator — only to keep the two views showing the
   same order, which is what "sort... survive[s] the transform" asks for.
4. **Checkbox accessible names need `slotProps.input`, not a bare `aria-label`.** MUI's `Checkbox` spreads an
   `aria-label` prop onto the *root* `<span>`, not the `<input type="checkbox">` that actually carries
   `role="checkbox"` — so `getByRole('checkbox', { name: … })` couldn't find it. Verified against
   `@mui/material/internal/SwitchBase.js` source; fixed with `slotProps={{ input: { 'aria-label': … } }}`,
   which is where MUI's own docs put a label for a checkbox with no visible text.
5. **No separate `CardView.test.tsx`.** `CardView` has no reachable state of its own outside what
   `DataTable` feeds it, so exercising it only through `DataTable` (its one real caller) matches how the
   grid's own column-definition logic is tested — through `DataTable.test.tsx`, not a `gridColumns.test.tsx`
   that doesn't exist.
6. **Live-resize is tested, not just fixed-width mounts.** Every other responsive test in this plan
   (`AppShell.test.tsx`) sets the viewport once before mounting; none re-renders across a width change
   mid-test. The sort-persistence acceptance item specifically needs a real resize (sort applied at `lg`,
   then check the card order at `320`), so `DataTable.test.tsx`'s sort test calls `rerender` after
   `setViewportWidth` — relying on `useSyncExternalStore`'s snapshot re-check on the next render (confirmed
   working, not assumed).

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                                                    # clean
npm run typecheck                                                               # clean
npm test -- src/shared/ui/DataTable src/features/inventory --environment jsdom  # 15 files, 117 tests — all passed
npm test -- src/features/inventory/instances/InstancesPage.a11y.test.tsx        # 6 tests — all passed
npx vitest run                                                                  # full suite: 144 files, 681 tests — all passed
npm run build                                                                   # production build succeeds
```

(`npm run test:a11y` from the task's own Verify line doesn't exist as a script — same pre-existing gap noted
in the FE-106 report; `.a11y.test.tsx` files ran as ordinary Vitest tests instead.)

Task-specific acceptance items:

- **Both views render all 26 vocabulary states** — `InstancesPage.test.tsx`'s new
  `'the card view emits the same state-chip labels as the grid'` renders the real 12-instance fixture at
  both widths and asserts the sorted `.MuiChip-label` text sets are identical; `columns.test.tsx`'s existing
  per-vocab-category sweep (26 cases across health/not-found/version/config/driver/locus) already proves
  every `renderCell` handles every state, and `CardView` calls that same `renderCell`.
- **Zero `driver`/`locus` branches in either** — structural: `CardView`'s only conditionals are on
  `column.cardEmphasis` (metadata) and `expanded`/`selection` (view state); it never reads `row.driver` or
  `row.locus` directly.
- **Table scrolls in its container, never the body** — unchanged; the grid path is untouched above `md`.
- **Selection and focus deep links work in both** — `DataTable.test.tsx`: a card checkbox reports the toggled
  id via `onSelectionChange`; a `focusRowId` card receives DOM focus. `InstancesPage.test.tsx`: a card
  checkbox updates `?sel=` in the URL, same contract as the grid.
- **No page-body horizontal scroll at 320px** — `DataTable.test.tsx` and `InstancesPage.test.tsx` each run
  `assertReflow` at `reflowFloorPx` against the card view (with `detailPanel` and `bulkActions` both present,
  the worst case); real-browser confirmation below also measured `document.body.scrollWidth === clientWidth
  === 320`.
- **axe clean** — `DataTable.a11y.test.tsx`: card view (populated + selected + with `bulkActions`) and an
  expanded card, both themes. `InstancesPage.a11y.test.tsx`: the real 12-row fixture in card view, both
  themes.
- **Visually verified against a real browser** — `npm run dev`, real Chromium (Playwright), navigated to
  `/solutions/cache/instances`: wide view (1280) shows the unchanged 9-column grid; at 375/320 all 12
  fixture instances render as cards (name + org/sub-org, env/driver/locus/health/version/config chips
  wrapping, replicas/last-seen as label/value pairs); expanding a card shows the same replica-health +
  version/config explanation content the grid's detail panel shows; selecting a card's checkbox shows "1
  selected" in the footer and a "Restart 1…" button in a sticky bar pinned to the bottom of the viewport; no
  console errors at any point.

## Status

DONE — implemented, tested (681/681 on the full run), lint-clean, typecheck-clean, production build
succeeds, visually verified in a real browser at 1280/375/320 including the expand and bulk-selection
interactions. Not yet closed on GitHub — pending review, particularly Ruling 1 (`cardEmphasis` as new
opt-in column metadata) and Ruling 2 (`bulkActions` as a prop distinct from `toolbarExtras`).
