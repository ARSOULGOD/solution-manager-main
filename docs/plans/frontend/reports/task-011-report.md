# FE-011 · Buttons — Report

## What I implemented

One `Button`/`IconButton` API covering the reference kit's five button weights (design §8.2, §15,
UI §A.1, §B.4) — including the two danger weights high-consequence confirmations need (`FE-D9`).

### Files created (`frontend/src/shared/ui/Button/`)

- `Button.types.ts` — `ButtonProps` (`variant: contained | outlined | text | danger |
  dangerContained`, `size: sm | md | lg` → 32/40/46px, `icon`/`iconRight` slots, `block`) and
  `IconButtonProps`, where **`'aria-label': string` is a required key, not optional** — the
  acceptance line "IconButton without aria-label fails typecheck" is a type-level guarantee, not a
  runtime check.
- `Button.tsx` — `Button` and `IconButton`, wrapping MUI's own. `variant` maps to an MUI
  `variant`+`color` pair (`danger` → outlined/error, `dangerContained` → contained/error). Danger
  variants render a built-in marker icon (an inlined `SvgIcon` — `@mui/icons-material` is not a
  project dependency, only `@mui/material` is) whenever the caller doesn't supply its own `icon`,
  so a danger action is never distinguishable by colour alone (`FE-D9`, design §18's "status is
  text + icon + colour" rule applied to the danger affordance itself). A caller-supplied `icon`
  always wins. Disabled renders at `opacity: 0.5` with `boxShadow: none` per the reference kit.
  Uppercase/semibold/letter-spacing labelling already comes from `theme.typography.button`
  (`createSmTheme.ts`, task 003) — nothing duplicated here.
- `Button.test.tsx` — click firing, disabled suppressing the click, the danger/dangerContained
  built-in icon marker present with no `icon` prop, no icon on the default `contained` variant, and
  a caller-supplied icon overriding the built-in marker.
- `Button.typecheck.test.tsx` — compile-only: two `@ts-expect-error` assertions that omitting
  `aria-label` on `IconButton` fails `tsc`. `@ts-expect-error` itself fails to compile if the next
  line does *not* error, so this file only passes `npm run typecheck` when the prop is genuinely
  required.
- `Button.a11y.test.tsx` — `jest-axe` over one render containing all five variants, the disabled
  state and an `IconButton`, in both `light` and `dark` mode (`renderWithProviders`'s `mode`
  param).
- `Button.gallery.tsx` — the `/dev/gallery` demo: every variant × size, the disabled state, and
  both `IconButton` sizes. Exports a plain `{ name, element }` object rather than importing the
  `GallerySection` type from `app/dev/Gallery` — `shared` has no `boundaries/dependencies` policy
  granting it access to `app` (task 009's guardrail), so the type has to be inferred structurally
  at the point `Gallery.tsx` (which owns the `GallerySection` type) pushes it into `sections`.
- `index.ts` — barrel re-exporting `Button`, `IconButton` and their prop types.

### Files created

- `frontend/src/shared/ui/index.ts` — the shared component kit's public barrel (did not exist
  before this task; the Phase 1 preamble names it as a file every task registers into).

### Files modified

- `frontend/src/app/dev/Gallery.tsx` — imports `buttonGallerySection` and seeds `sections` with
  it; dropped the now-stale "`sections` starts empty" line from the file's own doc comment.
- `frontend/src/app/dev/Gallery.test.tsx` — the old "renders the empty-registry message" test
  necessarily changed once a section is actually registered; replaced with an assertion that the
  `Button` section's `<h2>` renders. (Not a Files-list item for this task, but the same
  "gallery test moves as sections fill in" situation FE-010's own comment anticipated.)

## Testing

```
$ npm run lint
$ npm run typecheck
$ npm test -- --run
$ npm run build
$ npm run lint:fixtures:assert
```

All green: lint clean, typecheck clean, **141 tests across 26 files** (14 new: 6 `Button.test.tsx`
+ 2 `IconButton` + 2 `Button.typecheck.test.tsx` + 2 `Button.a11y.test.tsx` (light/dark) + the
`Gallery.test.tsx` rewrite + one unchanged). Production build: 979 modules — unchanged from FE-105,
because nothing outside the DEV-only gallery route imports the Button module yet, so it isn't in
the production graph. `lint:fixtures:assert`: 8 files, intended rule each (unchanged baseline).

## Status

DONE

## Commit

`0c3da70..5c5ff2c` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `011`. Issue
[#33](https://github.com/parikshit-coe/solution-manager/issues/33) (closed).
