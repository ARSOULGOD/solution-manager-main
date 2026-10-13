# FE-012 · Form control wrappers — Report

## What I implemented

Seven form control wrappers — `TextField`, `NumberField`, `Select`, `TextArea`, `Autocomplete`,
`Switch`, `Checkbox` — sharing one label/required/helper/error contract (design §18 "Forms"), so no
field author can wire `aria-describedby` wrong (the task's objective).

### Shared internals (`frontend/src/shared/ui/internal/`)

- `FieldProps.ts` — the common prop contract (`label` required, `required?`, `helperText?`,
  `error?`, `disabled?`) and `resolveHelperText(error, helperText)`, which every wrapper uses to
  swap `helperText` for `error` (its *presence*, not a separate boolean, is what marks a field
  invalid) so a caller cannot show both at once.
- `useDescribedByField.ts` — `Switch`/`Checkbox` sit inside `FormControlLabel`, which (unlike
  `TextField`) does not link a helper/error line to its control. This hook builds the
  `aria-describedby`/`aria-invalid` pair by hand via `useId()`.

### Text-family controls — `TextField`, `NumberField`, `TextArea`, `Select`, `Autocomplete`

I verified against the installed `@mui/material` source (not docs prose — `I2`) that MUI's own
`TextField` already generates a stable id via `useId()` and wires `aria-describedby`/`aria-invalid`
from `helperText`/`error` internally, identically whether or not `select`/`multiline` is set. So
these five wrappers are thin: `NumberField` is `type="number"` + `inputMode="numeric"` (belt and
suspenders — the type drives the numeric value model, the inputMode is what some browsers need for
a numeric keypad even on `type="number"`) and reports `''` rather than coercing a cleared field to
`0`. `Select` is `TextField`'s own `select` mode with `MenuItem` options. `TextArea` is `multiline`.
`Autocomplete` wraps MUI's `Autocomplete`, using MUI's raw `TextField` (not this task's own
wrapper) in `renderInput` — `Autocomplete`'s `params` carry the `ref`/`InputProps` its popover
positioning needs, which our wrapper doesn't accept passthrough for.

`TextField`'s `reveal` prop adds a show/hide toggle (an `IconButton` from FE-011, two small inline
`SvgIcon`s — no icon package is a project dependency) whose accessible name comes from the message
catalogue (`showPassword`/`hidePassword` — FE-007's "zero literal user-facing strings" acceptance
line applies to a component's own built-in copy, not just view-model strings).

### `Switch` / `Checkbox`

Both hand-build the `aria-describedby`/`aria-invalid` wiring via `useDescribedByField` and a
`FormHelperText` sibling. `Checkbox` adds a `checked: boolean | 'mixed'` tri-state (the acceptance
line "`Checkbox` supports `aria-checked="mixed"`").

## Two real defects found via axe, not assumed

1. **`aria-describedby`/`aria-invalid` landed on the wrong element.** Passing them as top-level
   props on `<Switch>`/`<Checkbox>` put them on the *root* span (`SwitchBase`'s ripple/clickable
   wrapper) — confirmed by inspecting the rendered DOM in a failing test — not the native `<input>`
   a screen reader actually associates with the label. Fixed by routing them through
   `slotProps.input` instead.
2. **`Checkbox`'s `mixed` state failed its own axe pass.** MUI's `Checkbox` sets
   `aria-checked="mixed"` from `indeterminate` but — per its own source comment — deliberately does
   **not** set the native `<input>`'s `.indeterminate` DOM property ("inconsistent behaviour across
   browsers"), using a `data-indeterminate` attribute instead. Without the real DOM property backing
   it, axe-core's `aria-conditional-attr` rule correctly flags `aria-checked="mixed"` as unbacked by
   native semantics. Root-caused rather than suppressed: the wrapper now sets `.indeterminate`
   itself via a ref through the `input` slot — the standard React pattern for a tri-state native
   checkbox, since there's no HTML attribute for it. This is what actually gets `aria-checked="mixed"`
   past the acceptance line's own a11y bar, not just past a reading of the acceptance text.

## A third, incidental fix

`router.test.tsx`'s `renderAt` helper polls a fixed 20×5ms (100ms) budget waiting for a route's
lazy chunk to resolve. `/dev/gallery`'s chunk grew heavier as Phase 1 registered real MUI components
into it (`Autocomplete` alone pulls in `Popper`/`Portal`), and the deep-link test started missing
that budget intermittently under full-suite parallel load (reproduced twice, passed in isolation
both times — a genuine regression risk, not a false read). Raised the budget to 100×5ms (500ms);
two full-suite runs green afterward. Not in this task's own Files list, but the same
"gallery-consuming test moves as sections fill in" situation FE-011's `Gallery.test.tsx` update was.

### Files created

`frontend/src/shared/ui/{internal,TextField,NumberField,Select,TextArea,Autocomplete,Switch,Checkbox}/`
— each control folder has its component, a `.types.ts`, a `.test.tsx`, a `.a11y.test.tsx` (axe,
both themes) and a `.gallery.tsx` demo, per the Phase 1 preamble.

### Files modified

- `frontend/src/shared/i18n/messages.en.ts` — `showPassword`/`hidePassword` keys.
- `frontend/src/shared/ui/index.ts` — barrel exports for all seven controls.
- `frontend/src/app/dev/Gallery.tsx` — registers all seven gallery sections.
- `frontend/src/app/router.test.tsx` — the polling-budget fix above.

## Testing

```
$ npm run lint
$ npm run typecheck
$ npm test -- --run   (run twice to confirm the router fix held)
$ npm run build
$ npm run lint:fixtures:assert
```

All green. **183 tests across 40 files** (42 new). Production build: 979 modules, unchanged — the
form controls are reachable only from the DEV-only gallery route today, so none of this is in the
production graph yet. `lint:fixtures:assert`: 8 files, intended rule each (unchanged baseline).

## Status

DONE

## Commit

`83efa68..6dd8f24` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `012`. Issue
[#34](https://github.com/parikshit-coe/solution-manager/issues/34) (closed).
