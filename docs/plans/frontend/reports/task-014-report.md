# FE-014 · Alert / Banner and Tooltip / InfoTip — Report

## What I implemented

### `frontend/src/shared/ui/Alert/`

`Alert` — severities `info | warn | err | ok | neutral`, `title`, an `icon` override, an `action`
trailing slot (MUI's own `action` prop — a native fit for "the retry button"), and `fullWidth` for
the page-top **banner** placement design §16 names ("banner of unavailable instances"). Design's
own component table lists `Alert`/`Banner` as one row, not two — `fullWidth` is the only thing that
changes, so this is one component, not two.

**`role="alert"` only for `err`** (the acceptance line): MUI's `Alert` defaults `role="alert"` for
every severity regardless of colour (verified against `@mui/material/Alert` source) — left alone,
an `info`/`ok` banner would interrupt a screen reader the same way a real error does. Non-`err`
severities get `role="status"` (a polite live region) instead. Colour comes from
`theme.palette.tone` (task 013's extension) — the same source `Chip`/`StateChip` use, never a
literal here (`FE-D5`).

### `frontend/src/shared/ui/Tooltip/`

`Tooltip` wraps its child in a `tabIndex={0}` span so the trigger is always keyboard-reachable
(task acceptance) regardless of whether the wrapped content is itself focusable — covering both
`InfoTip`'s bare-icon case and design's documented "explain why this disabled control is disabled"
case (`FE-D9`, `Button`/task 011). A tooltip is never the only carrier of a blocking reason — the
reason must also be in text elsewhere on the page (design §9); not mechanically enforceable here,
since the component has no way to know whether its content is the *only* copy of a reason —
recorded as a comment for the convention, not a runtime check.

### `frontend/src/shared/ui/InfoTip/`

`InfoTip({ tip })` — the small circled "i" affordance the prototype uses ~40 times. `Tooltip` +
the shared `info`-tone icon; no new wiring needed.

### Consolidated: one icon set for both tone-coloured components

Moved the `SvgIcon` glyphs `Chip`/`StateChip` (task 013) already had into
`shared/ui/internal/toneIcons.tsx` (`ToneIcon` component + `TONE_ICON_PATHS`), since `Alert`'s
default severity icon needs the exact same set. `shared/vocab/toneIcons.tsx` now just builds its
`TONE_ICON` record from the relocated `ToneIcon` rather than owning the glyph paths itself — one
source of truth instead of two copies that could silently drift.

### `StateChip` now uses the real `InfoTip`

Swapped the hand-rolled `Tooltip` + inline `InfoGlyph` + manual `role="img"`/`aria-label` span (a
FE-013 stand-in, explicitly flagged in that task's own comment as "cannot depend on `InfoTip` —
this task's deps are 003, 010 only") for the real component — the promised one-line change.
`StateChip.test.tsx` updated to match: it queried `role="img"` before; now queries `role="tooltip"`
after a `Tab`, matching `InfoTip`'s real interaction model.

## Two real defects found by running the code, not by reading the spec

1. **`theme.palette.tone[severity]` would have needed a fifth `Alert`-only entry.** Caught while
   drafting `Alert`'s default-icon map: `AlertSeverity` (`info|warn|err|ok|neutral`) is a strict
   subset of `ChipTone` (`+idle, +purple`), so `theme.palette.tone` (already typed over all seven
   `ChipTone` keys from task 013) covers it with no change needed — confirmed by `Record<AlertSeverity, …>`
   compiling against the existing palette type with no widening. Recorded here because it was worth
   checking, not because anything broke.
2. **`aria-label` on the `Tooltip` wrapper span failed its own axe pass.** MUI's `Tooltip` defaults
   `describeChild={false}` (verified against `@mui/material/Tooltip` source) — the tooltip title
   becomes the trigger's `aria-label` rather than an `aria-describedby`, correct for a nameless
   icon trigger like `InfoTip`. But `aria-label` is only a *permitted* ARIA attribute on elements
   whose role supports naming; a plain `<span>` has no such role, and axe's `aria-prohibited-attr`
   rule caught it immediately once `InfoTip` was exercised through `StateChip`'s vocabulary-entry
   a11y test (every entry with a `tip` — 21 of them — failed). Fixed by giving the wrapper
   `role="button"`, the common axe-clean choice for an on-demand info trigger, rather than
   suppressing the rule.

### Files created

`frontend/src/shared/ui/{Alert,Tooltip,InfoTip}/` (component, types, test, a11y test, gallery demo
each) and `frontend/src/shared/ui/internal/toneIcons.tsx` (relocated from `shared/vocab/`).

### Files modified

- `frontend/src/shared/vocab/toneIcons.tsx` — now builds `TONE_ICON` from the relocated `ToneIcon`.
- `frontend/src/shared/ui/StateChip/{StateChip.tsx,StateChip.test.tsx}` — real `InfoTip`, test
  updated to match.
- `frontend/src/shared/ui/index.ts` — barrel exports for `Alert`/`Tooltip`/`InfoTip`.
- `frontend/src/app/dev/Gallery.tsx` — registers all three gallery sections.

## Testing

```
$ npm run lint
$ npm run typecheck
$ npm test -- --run   (run twice to confirm stability)
$ npm run build
$ npm run lint:fixtures:assert
```

All green. **241 tests across 51 files** (20 net new — some of `StateChip`'s existing tests were
rewritten rather than added to). Production build: 979 modules, unchanged — still reachable only
from the DEV-only gallery route. `lint:fixtures:assert`: 8 files, intended rule each (unchanged
baseline).

## Status

DONE

## Commit

`cd233cf..d48464c` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `014`. Issue
[#36](https://github.com/parikshit-coe/solution-manager/issues/36) (closed).
