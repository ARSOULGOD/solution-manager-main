# FE-042 · Inventory URL state, filters, deep-link focus — Report

## What I implemented

Selection and focus as URL state, plus the Env filter and column visibility `DataTable` (task 020)
had promised but never shipped (task spec: `docs/plans/frontend/implementation.md` §FE-042; refs
`FE-D6`, `FE-D21`, design §6.2/§14, UI §A.6).

| File | Summary |
|---|---|
| `src/features/inventory/instances/InstancesPage.tsx` | `useSearchParams` owns `?sel=`/`?focus=`; `selection`/`onSelectionChange`/`focusRowId` wired straight to `DataTable`. Env filter (`All/DEV/TEST/PROD`) is local `useState`, applied as a client-side `.filter()` before rows reach `DataTable`, rendered via `ToggleGroup` passed as `toolbarExtras` — component state, never the URL, per the task's own "filter and sort state are component state" line. |
| `src/features/inventory/instances/columns.tsx` | `name` column marked `hideable: false` — row identity can't be hidden from the Columns menu. |
| `src/shared/ui/DataTable/DataTable.tsx`, `.types.ts` | Three additions: `toolbarExtras` slot; a Columns menu (`hideable` on `ColumnDef`, `columnVisibilityModel`); `focusRowId` now actually does something — expands the row's detail panel and moves real DOM focus via `apiRef.setCellFocus` (which also scrolls it into view), not just the pre-existing CSS outline. See Rulings 2–4. |
| `src/shared/i18n/messages.en.ts` | Two new keys (`instancesEnvFilter`, `instancesEnvAll`); reused the existing unused `columnVisibility: 'Show columns'` key (left over from FE-020) for the Columns button rather than adding a duplicate. |
| `*.test.tsx`, `*.a11y.test.tsx` | New cases for URL round-trip, env filter, deep-link focus, toolbarExtras, column visibility, and the selection-preservation fix (Ruling 1); a11y pass for the open Columns menu. |

## Rulings

1. **Found and fixed a real selection-loss bug while wiring `DataTable`'s controlled `selection` into a
   real screen for the first time.** MUI DataGrid prunes a controlled `rowSelectionModel` id the moment
   its row leaves the `rows` prop — including when the *caller* narrows `rows` (the new env filter, but
   also `DataTable`'s own pre-existing quick-search box), not just a genuine uncheck. Verified in a real
   browser against the dev server: selecting two PROD instances, then filtering to DEV, silently dropped
   both from `?sel=` — reproduced as a unit test (`DataTable.test.tsx`, rerender with a narrower `rows`),
   fixed in `onRowSelectionModelChange` by restoring any previously-selected id not present in the
   current `gridRows`, while a real uncheck of a *visible* row still removes it correctly. This is not a
   pre-existing bug I merely surfaced — no consumer wired `selection` into `DataTable` before this task,
   so the interaction between controlled selection and a shrinking `rows` array never fired anywhere in
   the codebase until now. Fixed in `DataTable` itself (not just `InstancesPage`) since the same root
   cause was already latent in the built-in search box, and `DataTable` is the shared primitive.
2. **`FilterBar`/`SearchField` (task 025), though a listed dependency, is not used directly.** The
   reference screen (`docs/design/frontend/reference/app/instances.jsx`) puts the Env toggle inside the
   table's own toolbar via a `toolbarExtras`-equivalent slot, not a separate `FilterBar` above it, and
   `DataTable`'s existing built-in search box already satisfies "quick search" — adding `FilterBar` on
   top would duplicate it. Followed the reference rather than force the dependency in redundantly.
3. **`focusRowId`'s expand-on-arrival is real, user-collapsible `expanded` state, not a value re-derived
   every render.** Initially wrote it as a `useEffect` calling `setExpanded`, which the repo's own
   `react-hooks/set-state-in-effect` lint rule (task 009's guardrails) correctly rejected — setState
   synchronously in an effect body cascades a render. Switched to React's documented "adjust state
   during render when a prop changes" pattern (a `prevFocusRowId` ref-via-state comparison), with the
   initial `expanded` state's lazy initializer also seeding `focusRowId` so the very first mount is
   covered too (the prop-diff pattern alone misses mount, since the initializer already captures the
   current prop value).
4. **DOM focus uses the grid's own `apiRef.setCellFocus`, not a manual `.focus()` call on the row `div`.**
   First attempt queried the row's DOM node directly and called `.focus()` — silently a no-op, because
   MUI's roving-tabindex model puts `tabindex` on cells, not the row `div` (confirmed by inspecting the
   rendered DOM). `setCellFocus` is the correct, Community-safe API (verified `useGridApiRef` is exported
   from `@mui/x-data-grid`, not the Pro package) and also scrolls the cell into view for free.
5. **No repository/backend changes.** Env filtering is entirely client-side over the already-fetched
   instance list — the task spec is explicit that filter/sort state is component state, distinct from
   the URL-owned selection/focus.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc --noEmit    # clean
npm run lint         # clean (0 warnings, --max-warnings=0)
npx vitest run       # 135 files, 606 tests, all passing (up from 135/605 before this task)
npm run build         # production build succeeds, no new warnings beyond the pre-existing
                       # layout-chunk size note flagged in FE-041's own report
```

Task-specific acceptance items:

- **Selection survives reload** — `InstancesPage.test.tsx`: render with `?sel=cache-prod-01,cache-prod-02`
  in the initial URL, assert the footer shows "2 selected" and the DataGrid's rows are checked; a second
  test asserts clicking a checkbox updates `?sel=` while preserving `?focus=`.
- **Deep link from the attention feed lands on the right row** — `?focus=cache-prod-02` expands that
  row's detail panel and moves real DOM focus onto it, verified in jsdom (`document.activeElement`) and
  independently in a real Chromium browser against the dev server (screenshots inspected: focus ring on
  the correct cell, detail panel open, row visually distinguishable).
- **Env filter narrows the grid without touching the URL** — clicking `DEV` hides PROD/TEST rows; a
  dedicated test asserts the URL is unaffected.
- **Selection is not silently dropped by filtering** — Ruling 1's fix, unit-tested and browser-verified:
  selecting two PROD rows, filtering to DEV then back to All, both remain selected.
- **Column visibility** — `DataTable.test.tsx`/`.a11y.test.tsx`: the Columns menu excludes the
  non-hideable `Instance` column, toggling `Id`/`Replicas` off removes the column header, axe clean with
  the menu open (light/dark).
- **Visually verified against the reference** — screenshotted the running dev server
  (`/solutions/cache/instances`, 1440×1000) via a throwaway Playwright script against `npm run dev` +
  `msw/browser`; deep-link, env-filter and columns-menu states all match
  `docs/design/frontend/reference/app/instances.jsx`'s toolbar layout; 0 console errors in all three
  states.

## Addendum: validation against the real reference HTML

After the report above was written, re-validated directly against
`docs/design/frontend/reference/Solution Manager v2.html` (not just `app/instances.jsx`) in a real
Chromium browser — the HTML needs a local static server (`file://` breaks its `<script src>` module
loading) and a click through its own "Sign in" screen; its `app/grid.jsx`/`kit.jsx` are the actual
DataGrid source, more precise than reading the screen JSX alone. Found and fixed two more gaps
squarely within FE-042's own surfaces:

9. **Columns menu trigger was labelled "Show columns"; the reference labels the *button* "Columns"
   and uses "Show columns" only as the menu's caption** (`grid.jsx`'s `Menu` trigger vs its `mi-cap`).
   Fixed: added a `tableColumns: 'Columns'` key for the button, moved the existing `columnVisibility`
   key ("Show columns") to a `ListSubheader` caption inside the menu — reusing the shared `Menu`
   component's own established caption pattern (`UI §A.1 mi-cap`), matching how `src/shared/ui/Menu`
   already renders `{ kind: 'caption' }` entries.
10. **Toolbar had every control in one left-aligned cluster; the reference groups search + the env
    filter on the left, then pushes Columns/Density/CSV to the right via a spacer.** Added a `flex: 1`
    spacer immediately after `{toolbarExtras}` and moved the (pre-existing, FE-020) density dropdown
    to the right-hand cluster — a same-file reorder, not a rewrite of the control itself.

Also found four gaps that are **not** FE-042's — they're in `DataTable` itself (FE-020), affecting
every consumer, not just Instances: density-as-dropdown vs the reference's segmented toggle; no icons
on Columns/CSV (this app has no `@mui/icons-material` dependency, so an icon needs a new inline SVG,
not a prop change); the selected/row-count caption living in the top toolbar row instead of a separate
footer bar below the table (and always showing "0 selected" rather than only when count > 0); and
MUI's own default pagination footer rendering below the table, which the reference has none of and
which duplicates the row count DataTable already shows itself. Filed as
[#60](https://github.com/parikshit-coe/solution-manager/issues/60) rather than folded in here, since
fixing them means touching every `DataTable` consumer's tests/screenshots, not a single screen.

## Status

DONE — implemented, tested (607/607 unit tests, up from 605), lint-clean, typecheck-clean, production
build succeeds, visually verified against both the screen-level reference JSX and the full reference
HTML prototype end-to-end (login → Instances, in a real browser), including a real selection-loss bug
and two toolbar-wording/layout gaps found and fixed during that verification. Not yet closed on GitHub
— pending review, particularly Ruling 1 (the `DataTable` selection-preservation fix, which changes
behavior for any future consumer combining controlled `selection` with a shrinking `rows` array) and
Ruling 2 (not using `FilterBar` despite it being a listed dependency).
