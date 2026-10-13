# FE-054 · ValidationSummary — Report

## What I implemented

The form-level alert that rolls up every offending field across all Designer sections into one place
(design §10/§18, UI §A.8.9), matching the reference `ValidationSummary` in
`docs/design/frontend/reference/app/designer_panels.jsx`: a chip per field, clicking one jumps to it and
focuses it; the header distinguishes "must be fixed before saving" from "recommendations to review";
`role="alert"` fires only when blocking.

| File | Summary |
|---|---|
| `frontend/src/features/designer/ValidationSummary/collectValidationEntries.ts` (+ `.test.ts`) | Pure derivation: walks every `ViewSection`, reuses `groupSectionFields`'s own `applicable` (visible) filter, and applies the same enabled/tier rule `SectionCard`/`SectionNav` already use — so this summary's counts can never drift from theirs. Returns `{ blocks, warns }`, each `{ sectionIndex, fieldKey, label }`. |
| `frontend/src/features/designer/ValidationSummary/ValidationSummary.tsx` (+ types, tests, a11y test, `index.ts`) | Renders `null` when both lists are empty. Otherwise renders `Alert` (task 014) with `severity="err"` when any field blocks, `"warn"` otherwise — `Alert` already carries the `role="alert"`-only-for-`err` rule, so this task needed no new accessibility mechanism. Header is one full catalogue sentence per variant (never a concatenation — see below); a chip per offending field (arrow icon + label, err/warn toned) calls `onJump(sectionIndex, fieldKey)` on click. |
| `frontend/src/features/designer/SectionNav/useSectionJump.ts` | Added `jumpToField(index, fieldKey)` — the same expand+scroll+focus mechanism task 050 built, now parameterized by a specific field rather than always "the section's first applicable field". `jumpToSection` is now a thin wrapper over it (finds the first applicable field, then calls `jumpToField`), so there is exactly one jump mechanism, not two. |
| `frontend/src/features/designer/SectionNav/useSectionJump.test.tsx` | Added an integration test wiring `ValidationSummary` + `useSectionJump` + real `SectionCard`s: clicking a chip for a field that is *not* the first field in its (collapsed) section still expands that section, scrolls the field into view, and focuses it. |
| `frontend/src/shared/i18n/messages.en.ts` | 3 new keys: `validationSummaryBlockHeader`, `validationSummaryWarnHeader`, `validationSummaryWarnAlso`. |

## The "no concatenated sentences" constraint, and what I did about it

The reference implementation builds its header by string-gluing two fragments when both blocks and warns
are present: `` `${n} fields must be fixed before saving` + ` · ${m} recommendations` ``. `t.ts`'s own module
doc states the project rule this would violate: *"the mechanism that keeps a sentence one catalogue entry
instead of one concatenated from fragments (design §18: 'no concatenated sentences')"* — and `t()`'s
plural selection only supports one `{count}`-driven category per call, so a single message key can't
correctly pluralize two independent counts at once anyway (a "3 fields · 1 recommendations" mismatch).

Rather than concatenate two `t()` results with a literal `' · '` (which read the same as the excluded
pattern) or invent a four-way key matrix for every one/other combination of two counts, I gave the
both-tiers case its own secondary sentence — `validationSummaryWarnAlso` ("{count} recommendation(s) also
need(s) review") — rendered as a second line under the primary header rather than glued into it. Each
line is a complete, independently-pluralized catalogue sentence; nothing is assembled from fragments.

## Design decisions, and why

1. **`jumpToField` added to `useSectionJump` rather than giving `ValidationSummary` its own jump hook.**
   The two behaviours are identical — expand a section, wait for the DOM to commit, scroll the target
   field's row into view, focus it — and task 050's own `data-field-row`/`data-field-key` DOM-query
   mechanism doesn't care whether the target is "the first applicable field" or "this specific field";
   only the input differs. Extending the existing hook (and rewriting `jumpToSection` as a one-line caller
   of it) keeps the expand/scroll/focus logic and its test coverage in one place instead of a second copy
   drifting from the first.
2. **`jumpToField` no-ops if the given `fieldKey` isn't found in `sections[index]`**, mirroring
   `jumpToSection`'s existing no-op-when-nothing-to-jump-to rule. In practice `ValidationSummary` only ever
   passes a `(sectionIndex, fieldKey)` pair it just read off a real `ViewField`, so this guards a
   `sections` prop that changed shape between render and click (a discriminator flip that hid the field)
   rather than anything the summary's own tests need to exercise directly.
3. **`collectValidationEntries` re-derives from `groupSectionFields` rather than duplicating its enabled/tier
   filter.** Task 049's own module doc already makes the promise that `SectionCard` and `SectionNav` "never
   compute [applicable/block/warn] differently" — this task extends the same promise across sections: a
   field counted as blocking in this summary is, by construction, the same field counted as blocking in its
   section's own chip and nav dot.
4. **A field carrying both a `bind` and a `semantic` validation counts as blocking only**, never showing up
   in both lists — same rule `groupSectionFields.blockCount`/`warnCount` already apply, kept consistent
   rather than introducing an "affects both totals" edge case the reference doesn't show either.
5. **The chip is a real `<button>` styled like a chip (`Box component="button"`), not the shared `Chip`
   component.** `Chip` (task 013) has no `onClick` — it's presentational only, used everywhere else as a
   read-only badge — and every other clickable badge-shaped control in this codebase (`SectionNav`'s own nav
   rows, `SectionCard`'s advanced-disclosure toggle) is built the same way rather than growing `Chip`'s API
   for one interactive use. Colour still comes from `theme.palette.tone`, never a literal (`FE-D5`).
6. **Chip icon path copies the reference's `arrowRight` glyph exactly**
   (`M5 12h14M13 6l6 6-6 6`, from `docs/design/frontend/reference/app/icons.jsx`) rather than a different
   inlined arrow, for visual parity with the mockup this task is built against.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json --noEmit                                     # clean
npm run typecheck                                                     # clean (both tsconfigs)
npx eslint src/features/designer/ValidationSummary src/features/designer/SectionNav \
  src/shared/i18n/messages.en.ts --max-warnings=0                     # clean
npx vitest run src/features/designer/ValidationSummary src/features/designer/SectionNav
  # 6 files, 20 tests, all passed
npm test        # full suite: 173 files, 828 tests, all passed
npm run build   # production build succeeds
```

`prettier --check` reports pre-existing formatting drift across the repo (including files this task never
touched, e.g. `FieldRow.tsx`, and `SectionNav.tsx`/`SectionNav.test.tsx` which this task also didn't edit) —
confirmed unrelated to this change and out of this task's scope.

## Status

DONE — `ValidationSummary` renders a chip per offending field across all sections, header text
distinguishes blocking from advisory, `role="alert"` only when blocking, and every chip jumps to and
focuses its field via the same mechanism task 050 built (`useSectionJump`, now generalized with
`jumpToField`). Lint-clean, typecheck-clean, full suite green, production build succeeds.
