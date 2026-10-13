# FE-003 · MUI theme factory, colour mode, ECharts theme bridge — Report

> **Provenance note:** this report was reconstructed retroactively (2026-08-20) from the commit
> record — `frontend` git history, the commit message on `33f5e91`, and the merged diff — not
> transcribed live during implementation. It documents what shipped and why, verified against the
> current working tree, rather than a blow-by-blow of the original session.

## What was implemented

One `createSmTheme(mode)` factory producing a single MUI `Theme`, parameterised by
`palette.mode`, built entirely from the FE-002 token layer — no second dark-mode file, no raw hex
outside `shared/theme`. Colour mode itself lives in a small persisted Zustand store.

### Files created

- `frontend/src/shared/theme/palette.ts` (123 lines) — `createPalette(mode)`: primary/secondary/
  error/warning/info/success from `tokens.*`; a **status vocabulary** (`health.ok`,
  `health.down`, `health.restartExpected`, `health.inactiveReplica`, `pending`, `unverified`)
  and a ten-colour `chart.categorical` series palette, both added as typed `Palette`/
  `PaletteOptions` module augmentations so consumers get compile-time checking rather than a
  loose `string` lookup. `background`/`text`/`divider` branch on `mode` between light tokens and
  `tokens.darkTheme.*`; one `// INVENTED` comment on `background.paper` (dark) notes it has no
  direct DS token and was interpreted from `theme.css` component selectors, per FE-002's own
  `INVENTED` convention.
- `frontend/src/shared/theme/createSmTheme.ts` (141 lines) — `createSmTheme(mode)`: builds
  `Shadows` (25 required MUI slots) by banding the DS's five shadow depths across MUI's elevation
  range so component defaults (Paper=1, AppBar=4, Menu=8, Dialog=24, …) land on a real DS shadow
  rather than one MUI invented; typography mapped from the token scale (display font for
  h1–h4, sans for the rest); `spacing: 4` (4px base, 8px rhythm at component level per the
  brief); breakpoints taken from the token layer (`xs 0 / sm 600 / md 905 / lg 1240 / xl 1560`)
  rather than MUI's own defaults, so the theme and the CSS token file cannot disagree about where
  a layout changes; `CssBaseline` overrides add a 2px `--brand-wMain` focus-visible ring with a
  2px offset (§B.2).
- `frontend/src/shared/theme/echartsTheme.ts` (46 lines) — `echartsTheme(theme)`: a **pure**
  function from `Theme` to an ECharts theme object (series colour, axis/split-line/tooltip/
  legend styling), exported from `shared/theme` for `ChartRenderer` (task 072) to be the sole
  consumer of (`DM-D13`, `FE-D16`). Reads only `theme.palette`/`theme.typography`, no I/O.
- `frontend/src/shared/state/uiStore.ts` (30 lines) — `useUiStore` (Zustand + `persist`
  middleware, storage key `sm-ui-store`): `mode: SmThemeMode`, `setMode`, `toggleMode`, defaulting
  to `'light'`. Deliberately holds nothing else yet — the brief's own comment notes it "grows with
  later UI preferences (e.g. density, §8.2), not pre-built for them now."
- `frontend/src/shared/theme/palette.ts`, `createSmTheme.test.ts` (84 lines),
  `echartsTheme.test.ts` (47 lines), `uiStore.test.ts` (41 lines) — unit coverage for: theme
  output differing between light/dark, `echartsTheme` purity (same input → deep-equal output,
  and it contains no literal hex — every colour traces back through `theme.palette`), and the
  store's default/set/toggle/persistence behaviour.
- `frontend/src/shared/theme/index.ts` — extended with `createPalette`/`SmThemeMode`,
  `createSmTheme`, `echartsTheme`/`EChartsTheme` re-exports alongside FE-002's `tokens`.

### Explicitly deferred to FE-004

Per the commit message: mounting `<ThemeProvider>`/`<CssBaseline>` and proving "mode switch
repaints without remount" end-to-end couldn't happen in this task — no component tree existed
yet to mount them into. FE-004 (Providers) picked this up.

### Explicitly out of scope

Brand paint remains pending architect acceptance (`FE-D5`, open item 19) — this theme is built
from the checked-in **reference** tokens, not a ratified palette; nothing here should be read as
"final colours."

## Testing — re-verified against the current tree (2026-08-20)

```
$ npm run lint && npm run typecheck && npm test -- src/shared/theme src/shared/state
```
All green: lint clean, typecheck clean, `createSmTheme.test.ts` / `echartsTheme.test.ts` /
`uiStore.test.ts` / `tokens.test.ts` passing. (Full-suite numbers as of this backfill: 13 test
files / 98 tests passing across the whole `frontend/` tree, which includes this task's specs.)

## Files changed

- `frontend/src/shared/state/uiStore.ts` (new)
- `frontend/src/shared/state/uiStore.test.ts` (new)
- `frontend/src/shared/theme/createSmTheme.ts` (new)
- `frontend/src/shared/theme/createSmTheme.test.ts` (new)
- `frontend/src/shared/theme/echartsTheme.ts` (new)
- `frontend/src/shared/theme/echartsTheme.test.ts` (new)
- `frontend/src/shared/theme/palette.ts` (new)
- `frontend/src/shared/theme/index.ts` (modified — new re-exports)
- `frontend/package.json` / `package-lock.json` (modified — `@tanstack/react-query`, `zustand`
  and their transitive deps added)

## Status

DONE

## Commit

`157c47a..33f5e91` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `003`.
