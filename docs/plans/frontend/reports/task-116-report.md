# FE-116 · Select-all selects nothing — Report

## The bug

On any `DataTable` with selection enabled, the header **select-all** checkbox did nothing:
nothing became checked, the toolbar stayed at `0 selected`, and `?sel=` never changed. Clicking an
individual row checkbox worked normally. Reported against the instances grid; the defect is in the
shared component, so every selectable table had it.

## Root cause

**`@mui/x-data-grid` 9.11.0 emits a different selection MODEL for select-all than for a row
click**, and `DataTable` read only half of it.

`GridRowSelectionModel` is `{ type: 'include' | 'exclude'; ids: Set<GridRowId> }`. Observed in the
browser by instrumenting the handler:

| Interaction | Model emitted |
|---|---|
| Click a row checkbox | `{ type: 'include', ids: ['cache-prod-01'] }` |
| Click **select all** | `{ type: 'exclude', ids: [] }` |

`exclude` means *"every row EXCEPT these"* — so select-all is expressed as **"all rows, minus
nothing"**, carrying an empty `ids` set. The handler ignored `type` and read `ids` alone, so
select-all arrived as an empty selection, i.e. **"select nothing"**, and cleared the selection.
Because `rowSelectionModel` is controlled, the grid then immediately re-rendered every checkbox
unchecked — which is why the click looked like it did nothing at all rather than like a clear.

Row clicks emit `type: 'include'`, where reading `ids` alone happens to be correct. That is the
whole asymmetry.

## Fix

`src/shared/ui/DataTable/DataTable.tsx` — `onRowSelectionModelChange` now branches on `model.type`,
expanding an `exclude` model over the **visible** rows minus its `ids`. Two details:

- **`exclude` expands over visible rows only.** Select-all means "all of what I can see", never
  "all of what the caller filtered away too". This composes with the pre-existing rule that a
  selection the caller filtered out of `rows` is preserved rather than pruned — so select-all under
  an env filter selects the visible rows and leaves an off-screen selection intact.
- **`hasId()` compares stringified ids.** `GridRowId` is `string | number`; this component works in
  strings throughout, so `Set.has()` alone would miss every numeric id in a grid whose `getRowId`
  returns numbers.

## Verification

Three tests in `DataTable.test.tsx` (`describe('select all')`), all confirmed failing against the
unfixed component:

| Test | Before | After |
|---|---|---|
| selects every visible row | `[]` | `['a','b']` |
| deselects when all already selected | not reached | `[]` |
| selects only visible rows, preserving a filtered-away selection | `{'b'}` | `{'a','b'}` |

Driven in a real browser on the instances grid, before and after:

```
BEFORE  select-all -> 0 selected · 12 of 12 rows   | 0/13 checked | URL unchanged
AFTER   select-all -> 12 selected · 12 of 12 rows  | 13/13 checked | ?sel=<12 ids>
AFTER   PROD filter + select-all -> 6 selected; widening back to All keeps exactly those 6
```

`npm run typecheck` · `npm run lint --max-warnings=0` · `prettier --check` all pass;
`vitest run src/shared/ui/DataTable src/features/inventory` → **166 passed, 18 files**.

## Noted, not fixed

Row checkboxes carry MUI's generic `aria-label="Select row"`. The catalogue already has
`tableSelectRow: 'Select {label}'` for a row-identifying name, and nothing uses it — so a screen
reader hears twelve identical "Select row" controls. Out of scope for this fix; worth its own task.
