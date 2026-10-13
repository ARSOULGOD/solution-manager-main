# FE-013 · Chip, StateChip and the state-vocabulary module — Report

## What I implemented

The single place every status string, tone, icon and explanatory tooltip lives (the task's
objective) — a base `Chip`, a vocabulary-aware `StateChip`, and six vocabulary modules ported
verbatim from UI §A.13.1.

### `frontend/src/shared/theme/palette.ts` (extended, not created)

Added `theme.palette.tone` — a `Record<ChipTone, { main, bg }>` covering all seven tones
(`ok · warn · err · info · idle · neutral · purple`). `ok`/`warn`/`err`/`info` read off the
existing semantic tokens' `wMain`/`w10` pair; `idle`/`neutral` reuse the muted secondary/slate
family (no hue-specific token fits "non-alarming, non-positive"); `purple` is the DS accent
already reserved for the `PROD` env tone. `Chip` reads this — never a literal colour, per `FE-D5`.

### `frontend/src/shared/ui/Chip/`

`Chip` (`label`, `tone`, optional `icon`, optional `dot`) — a thin wrapper over MUI's own `Chip`,
`size="small"` (the kit's only chip size, UI §A.1), styled from `theme.palette.tone[tone]`.

### `frontend/src/shared/ui/StateChip/`

`StateChip({ entry })` renders a `VocabEntry` as `Chip` (icon + text + tone) plus an info
affordance when the entry carries a `tip`. **The info affordance is a plain MUI `Tooltip` here,
not the `InfoTip` component design §9 names** — `InfoTip` is FE-014's own deliverable, and this
task's deps (003, 010 only) don't reach it. Kept keyboard-reachable (`tabIndex=0`), the same shape
FE-014's own spec describes, so swapping it for a real `InfoTip` later is a one-line change, called
out in a source comment.

### `frontend/src/shared/vocab/`

- `types.ts` — `VocabEntry<Key>` (`key`, `label`, `tone`, `icon`, optional `tip`, optional
  `scope`) — not in the task's literal Files list, but needed once to avoid every category file
  redefining the shape (the same "one more small seam file" situation FE-004/FE-010 already hit).
- `toneIcons.tsx` — one inlined `SvgIcon` per tone (seven total), shared across every vocabulary
  entry of that tone. Not in the literal Files list either. Text (the entry's `label`) is what
  distinguishes individual states; the icon's job is only the non-colour channel. No icon package
  is a dependency (only `@mui/material` is — same constraint FE-011's danger marker hit).
- `health.ts` (5) · `notFound.ts` (5) · `version.ts` (4) · `configState.ts` (6) · `driver.ts` (3)
  · `locus.ts` (3) — ported verbatim from §A.13.1's key lists and copy. `not_applicable` is
  `neutral`, never `err` (the acceptance line). `unverified` is `neutral`, never styled as an
  error (`UI-D12` over `SM-D14`). `driver`/`locus` are `neutral`-toned row data with a tip, never a
  UI branch (`REQ-005`, `REQ-008`) — `locus`'s tips state the credential posture per
  `architecture.md`'s DP model (dp-remote holds scoped host credentials; host-local is an
  outbound-only agent with nothing stored centrally; observe-only holds no credentials at all).
- `index.ts` — re-exports every category plus `allVocabEntries: VocabEntry[]`, the flat list the
  table-driven test and the gallery demo both walk.

## Two real defects found by actually running the code, not just reading it

1. **`theme.palette.tone` was `undefined` in two pre-existing test files.** `router.test.tsx`'s
   `renderAt` and `Gallery.test.tsx`'s `render` both render their trees with plain `react-dom`,
   predating `renderWithProviders` (task 008) and never updated to use it — so neither wraps a
   `ThemeProvider`. `Chip` is the first shared/ui component to read a custom `theme.palette` key
   from a component reachable through the DEV gallery those two files render, and MUI's `useTheme()`
   silently falls back to its own default theme (no custom keys at all) rather than throwing at
   the provider boundary — so the gap was latent until now. The real app (`app/providers.tsx`)
   already wraps `RouterProvider` in a real `ThemeProvider`, so this was never a production bug —
   confirmed by reading `providers.tsx` before deciding the fix. Fixed by wrapping both test
   helpers in `ThemeProvider theme={createSmTheme('light')}`, matching the real app's structure,
   rather than adding defensive fallbacks to `Chip` for a state that can't happen in production.
2. **The `dot` leading indicator never got MUI's `.MuiChip-icon` class.** MUI's `Chip` clones
   whatever element is passed as `icon` to inject `className={classes.icon}` (verified against
   `@mui/material/Chip` source) — but the clone lands on the *custom component's own props*, and my
   `Dot` component didn't accept or forward `className`, so the injected class was silently
   dropped before reaching the rendered `<span>`. Fixed by accepting `className` and passing it to
   the underlying `Box`.

### Files created

`frontend/src/shared/ui/{Chip,StateChip}/` (component, types, test, a11y test, gallery demo each)
and `frontend/src/shared/vocab/` (six category files + `types.ts` + `toneIcons.tsx` + `index.ts`).

### Files modified

- `frontend/src/shared/theme/palette.ts` — the `tone` palette extension above.
- `frontend/src/shared/ui/index.ts` — barrel exports for `Chip`/`StateChip`.
- `frontend/src/app/dev/Gallery.tsx` — registers both gallery sections.
- `frontend/src/app/router.test.tsx`, `frontend/src/app/dev/Gallery.test.tsx` — the `ThemeProvider`
  fix above (not in this task's Files list, but the defect it fixes was only reachable because of
  this task's own change).

## Testing

```
$ npm run lint
$ npm run typecheck
$ npm test -- --run   (run twice to confirm the ThemeProvider fix held)
$ npm run build
$ npm run lint:fixtures:assert
```

All green. **221 tests across 45 files** (38 new), including the required table-driven test over
every vocabulary entry (non-empty label, non-null icon) and the dedicated `not_applicable`-is-never-
`err` assertion. Production build: 979 modules, unchanged — the chip kit and vocab module are
reachable only from the DEV-only gallery route today. `lint:fixtures:assert`: 8 files, intended
rule each (unchanged baseline).

## Status

DONE

## Commit

`3cad708..74cb7df` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `013`. Issue
[#35](https://github.com/parikshit-coe/solution-manager/issues/35) (closed).
