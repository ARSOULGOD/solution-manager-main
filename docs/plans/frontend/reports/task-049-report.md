# FE-049 · SectionCard and advanced disclosure — Report

## What I implemented

`SectionCard` (`UI-D15`'s advanced obligation, design §10, UI §A.8.6) — the accordion every Designer section
renders through: header with title, applicable-field count, an `Advanced` chip for all-advanced sections, and
a blocking-or-warning count chip; body with normal fields then a `Show N advanced fields` toggle; an explicit
empty-state message when a discriminator hides every field.

| File | Summary |
|---|---|
| `frontend/src/features/designer/SectionCard/SectionCard.tsx` | The component. Filters `section.fields` to `visible` ("applicable"); computes `allAdvanced` from those; splits into `normal`/`advanced`; counts block/warn per applicable, enabled field; renders each field through `FieldRow` (task 048). |
| `frontend/src/features/designer/SectionCard/SectionCard.types.ts` | `SectionCardProps` — `section: ViewSection`, a section-scoped `values`/`onFieldChange` pair (the same live-vs-projected split `FieldRow` draws), `disabled?`, controlled `expanded`/`onToggleExpanded`, `showRenderedPath?`. |
| `frontend/src/features/designer/SectionCard/{SectionCard.test.tsx,SectionCard.a11y.test.tsx,index.ts}` | 10 tests: collapse/expand, applicable-vs-hidden counting, empty state, all-advanced chip (and its exemption from the toggle), advanced disclosure default-collapsed, block-vs-warn precedence, disabled-field exclusion from counts, field edits. A11y: 5 states × 2 colour modes, zero violations. |
| `frontend/src/shared/i18n/messages.en.ts` | 7 new keys under a "SectionCard (task 049)" block. |

## Design decisions, and why

1. **Controlled `expanded`/`onToggleExpanded`, not internal state.** The reference prototype
   (`docs/design/frontend/reference/app/designer.jsx`'s own `SectionCard`) takes `expanded`/`onToggle` as
   props, with the *set* of which sections start open owned by its `DesignerScreen` container (non-advanced
   sections open, all-advanced sections closed, by default). No task in the register assembles that container
   yet — FE-050 (section nav and jump) is the next task that needs to drive several cards' expand state from
   one place. Keeping `SectionCard` controlled, exactly like `FieldRow`'s controlled `value`/`onChange`, is
   what lets FE-050 own that policy without a rewrite here. The task's own acceptance criterion "advanced
   collapsed by default" is about the advanced-fields sub-toggle, which *is* local state (`useState(false)`,
   matching the reference) — not the card's own expand/collapse, which this task deliberately leaves to the
   caller.

2. **"Applicable" = `field.visible`, computed independently in this component** — `ViewSection` (FE-046) has
   no `allAdvanced` flag of its own (the reference's package data hand-authors one per section;
   `FieldUiMeta`/`FieldDeclaration` carry no such property and `project()` doesn't compute one). I derived it
   locally: a section is all-advanced when it has at least one applicable field and every applicable field is
   `advanced`. This matches the reference's own runtime behaviour (`section.allAdvanced` there is also just
   package-authored data feeding the identical rule at the read site — a card with zero non-advanced
   applicable fields never shows the toggle).

3. **Block/warn chips are mutually exclusive, blocking wins** — task-list's own one-line summary says
   "block/warn count chips" (plural), but UI §A.8.6's fuller prose and the reference implementation
   (`designer.jsx`'s `SectionCard`) both do `block > 0 ? <blocking chip> : warn > 0 && <warning chip>`, never
   both at once. Followed the more precise source. Counting itself also mirrors the reference: a
   validation only counts if its owning field is both applicable and `enabled` (an RBAC-disabled field's
   stale validation shouldn't drive the header).

4. **Every new message key is a full accessible phrase** (`sectionCardFieldCount`, `sectionCardBlockCount`,
   `sectionCardWarnCount`, `sectionCardShowAdvanced`/`HideAdvanced`), not a bare number — following this
   catalogue's own established pattern (`showPassword`/`hidePassword`, `SidebarNav`'s
   `"{name} ({instanceCount})"`) and design §18's "status: text + icon + color" rule. The pre-seeded
   `advancedFieldCount` key (added in FE-007, unused until now) turned out not to fit: it has no "Show"/"Hide"
   verb, and gluing a hardcoded English verb onto a catalogue phrase would be exactly the "concatenated
   sentence" the i18n design forbids — so I left it unused and added dedicated `sectionCardShowAdvanced` /
   `sectionCardHideAdvanced` phrases instead, matching the `showPassword`/`hidePassword` precedent.

5. **Not added to the dev-only component gallery** (task 010). Checked first: `FieldRow`, also a
   `features/designer` component, was never added to the gallery either — its gallery inventory is scoped to
   `shared/ui`. Followed the existing precedent rather than introducing a new one unilaterally.

6. **Icons are inlined `SvgIcon`s** (an expander chevron, plus/minus for the advanced toggle) — `Button`'s
   danger marker and `TextField`'s eye icons already established that `@mui/icons-material` is not a project
   dependency; only `@mui/material` is.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run src/features/designer/SectionCard   # 2 files, 10 tests, all passed
npx eslint .                                        # clean
npx tsc --noEmit -p .                               # clean
npx vitest run                                      # full suite: 161 files, 767 tests, all passed
npm run build                                       # production build succeeds
```

## Status

DONE — implemented, tested (767/767 on a full run, 10/10 new, zero `jest-axe` violations across 5 states ×
2 colour modes), lint-clean, typecheck-clean, production build succeeds. Not yet closed on GitHub — pending
review.
