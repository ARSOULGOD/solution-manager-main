# FE-105 · Responsive foundation: breakpoints, layout primitives, reflow harness — Report

## What I implemented

One breakpoint system, four layout primitives, and a reflow harness so later
screens cannot invent a media query or a second set of widths. This is the
last Phase 0 task; Gate A’s “reflow harness exists” line is this.

### Breakpoints

- `frontend/src/shared/theme/breakpoints.ts` — `breakpointValues`
  `{ xs: 0, sm: 600, md: 905, lg: 1240, xl: 1560 }` and `reflowFloorPx = 320`.
  MUI’s `xs` stays the 0-floor; 320 is what `assertReflow` asserts, not an
  MUI key. `columnsAtWidth` / `containerQuery` are the helpers Grid and
  Sidebar share so a column count is not re-derived ad hoc.
- `frontend/src/shared/theme/tokens.css` — `--bp-floor` / `--bp-xs`…`--bp-xl`
  next to the existing `--appbar-h` / `--drawer-w`. A test reads the CSS
  vars from the DOM and asserts they equal `breakpointValues`, so the theme
  and the stylesheet cannot drift.
- `frontend/src/shared/theme/createSmTheme.ts` — `breakpoints.values` is
  `{ ...breakpointValues }` instead of a second literal object.

### Layout primitives (`src/shared/ui/layout/`)

- `Stack` — column flex, 8px-grid gap.
- `Cluster` — wrapping row (toolbars / action rows).
- `Grid` — CSS **container queries** (`container-type: inline-size`) so a
  widget’s column count follows *its* width, not the viewport.
- `Sidebar` — rail + content; stacks below `lg` (container), row with
  `var(--drawer-w)` at `lg` and up. Overlay-drawer behaviour stays task 106.

Not registered in the gallery: that registry is Phase 1’s
(`011`–`025`); this task’s Files list does not include it.

### Reflow harness

- `frontend/src/test/reflow.ts` — `assertReflow(el, width)` fails if the
  element horizontally scrolls, if a descendant declares `width` /
  `min-width` larger than `width` (jsdom has no real layout engine, so
  `scrollWidth` alone is not enough), or if a laid-out focusable is clipped.
  `overflow-x: auto|scroll|hidden` descendants are skipped — G16’s table /
  toolbar exception.

### Lint (extends 009)

Raw `@media` is an error outside `shared/theme` and `shared/ui/layout`.
Combined with the colour rule in one `no-restricted-syntax` block so a
later block cannot wipe either (the FE-009 overlap lesson). New fixture
`src/test/lint-fixtures/app/raw-media.ts`.

## Testing

- `breakpoints.test.ts` — CSS vars match TS; theme consumes the same object;
  `columnsAtWidth` at 320 / 600 / 905 / 1240 / 1560.
- `layout.test.tsx` — each primitive at those five widths via `assertReflow`;
  Cluster wraps at 320.
- `reflow.test.ts` — overflowing `min-width: 800px` fixture **throws**; a
  wrapping control does not. Viewport meta is still
  `width=device-width, initial-scale=1` with no `user-scalable=no` /
  `maximum-scale`.

```
$ npm run lint && npm run typecheck && npm test -- --run src/shared/ui/layout src/test/reflow.ts
$ npm run lint:fixtures:assert
```

130 tests across 23 files (10 new). Production build 979 modules (was 978:
`breakpoints.ts` now sits on the theme graph). `lint:fixtures:assert`: 8
files, intended rule each.

Playwright smoke was not re-run here: this environment’s Chromium path is
missing (`npx playwright install` would be a machine-local step, not a
product change). The spec itself is unchanged from FE-008.

## Status

DONE

## Commit

`dc6ad1b..089d2d6` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `105`. Issue
[#31](https://github.com/parikshit-coe/solution-manager/issues/31).
