# FE-053 · Validation tiers — Report

## What I implemented

`UI-D16`'s two validation tiers, live-evaluated: `project()` now populates `ViewField.validations`
(always `[]` since task 046/048) from the package's own `required`/`min`/`max`/`pattern` declarations,
against the field's current defaults-applied value.

| File | Summary |
|---|---|
| `frontend/contracts/view-model/project.ts` | `evaluateValidations(field, value)`: `required` (empty check) → `{ tier: 'bind', code: 'REQUIRED', message: 'This field is required.' }`; `min`/`max` (numeric values only) → `{ tier: 'semantic', code: 'BELOW_RECOMMENDED' \| 'ABOVE_RECOMMENDED', message: '(Above\|Below) the range Solace recommends (min–max).' }`; `pattern` (non-empty strings only) → `{ tier: 'semantic', code: 'PATTERN_MISMATCH', message: "Doesn't match the pattern Solace recommends." }`. Called from `projectField` for every field, wired into the existing `validations: []` slot. No caching, no dirty-check — it runs fresh on every `project()` call, which is every keystroke once `useDesignerDraft` (task 052) is wired to a page, so "REQUIRED clears as the operator types" falls out of the module's existing "re-derive everything on every call" design rather than needing new plumbing. |
| `frontend/contracts/view-model/types.ts` | Updated `ViewField.validations`'s doc comment — it's no longer "always `[]`, task 053 pending". |
| `frontend/contracts/fixtures/view-model/*.json` (6 new) | `required-blocks-when-empty` / `required-clears-when-filled` (same field, two draft values — the fixture-level proof of live re-evaluation the task's own testing line asks for), `above-recommended-warns` / `below-recommended-warns` / `within-recommended-range-no-warning`, `pattern-mismatch-warns`. |
| `frontend/src/features/designer/FieldRow/FieldRow.tsx` | One comment update only — `fieldIsRequired`'s note said "this is `[]` until task 053 runs"; no long true. |

## What I did *not* need to build

`FieldRow` (task 048), `SectionCard`/`sectionFieldGroups` (task 049) and `SectionNav` (task 050) already
consume `field.validations` fully — bind vs. semantic styling, the required asterisk, block/warn chip
counts, the `— a Solace recommendation, not a hard limit — you can proceed` suffix on semantic messages —
all written and tested against synthetic `Validation` objects in anticipation of this task (see task 048's
own comment: *"this is `[]` until that task runs, so no asterisk shows before then, which is correct... not
a bug in this task"*). Populating `project()`'s output was the only gap; every consumer's existing test
suite (142 tests across `contracts` + `src/features/designer`) passed unchanged once it was filled in.

## Design decisions, and why

1. **`required` is `bind`; `min`/`max`/`pattern` are `semantic`.** The plan text for this task doesn't spell
   out the mapping explicitly, so I resolved it from three converging pieces of evidence: (a) `UI-D16`'s own
   rationale — *"Cache pulls `spring-boot-starter-validation` and uses it zero times — no `@Min`, `@Max`,
   `@Pattern` or `@Validated` anywhere... Semantic rules are SM's assertions, not the MI's"* — names exactly
   these three as non-MI-derived; (b) the `Validation.code` doc comment's own two named examples, `REQUIRED`
   and `ABOVE_RECOMMENDED`, already read as bind- and semantic-flavoured respectively; (c) the reference
   prototype (`docs/design/frontend/reference/app/designer_data.jsx`) hard-declares `REQUIRED = [{ severity:
   "block", code: "REQUIRED", message: "This field is required." }]` and reuses it verbatim per required
   field, while `designer.jsx`'s only other validation example is `{ severity: "warn", code:
   "ABOVE_RECOMMENDED", ... }`. I copied the reference's exact `REQUIRED` message text (`"This field is
   required."`) since it's already load-bearing in `FieldRow.test.tsx`'s own fixtures.
2. **`ABOVE_RECOMMENDED`/`BELOW_RECOMMENDED` message format copies the reference's bracket phrasing exactly**
   (`"Above the range Solace recommends (1–1000000)."`, en dash) rather than inventing new wording, and
   builds the bound text from whichever of `min`/`max` the field actually declares (`≥ min` / `≤ max` when
   only one bound exists) rather than assuming both are always present — the schema (`FieldDeclaration`) has
   always allowed either independently.
3. **`pattern` has no reference precedent** — no field in `designer_data.jsx` declares one. I implemented it
   generically (same semantic tier, same "whose rule" wording style as `UI-D16` mandates for the range case)
   rather than skip it, since `FieldDeclaration.pattern` is already part of the shipped contract (task 046)
   and the task's own acceptance line (*"zero hard-coded business rules"*) applies to it exactly as much as
   to `min`/`max`. Flagged here as inference, not reference-corroborated, the same way task 047/051 disclosed
   their own unevidenced gaps rather than silently guessing.
4. **Validations are computed for every field regardless of `visible`**, mirroring how `value`/`options`/etc.
   are already computed unconditionally in `project()` — visibility filtering is already the downstream
   consumers' job (`groupSectionFields`'s `applicable = section.fields.filter(f => f.visible)`, task 049), so
   a hidden branch's own `REQUIRED` sits in its `ViewField.validations` but never reaches `FieldRow` (which
   is only rendered for `applicable` fields) or the block/warn chip counts. Computing it unconditionally
   keeps `project()`'s obligations uniform rather than adding a visibility special-case this task's own text
   doesn't ask for.
5. **The evaluator lives in `contracts/view-model/project.ts`, not a new `src/features/designer` module.**
   The task's own line — *"Validation rules come from the package via `project()`"* — and `ViewField.validations`
   already being part of the shared, Java-portable contract (task 046) both point the same way: this has to
   be inside the pure, fixture-tested engine, not a React-layer concern. It also gets "live" for free —
   `useDesignerDraft` (task 052) already recomputes `viewModel` via `project()` on every draft change, not
   only structural ones, specifically so a changed discriminator's descendants re-evaluate; a changed field's
   own validations ride the same mechanism with no new code.
6. **No numeric threshold, regex, or "required" boolean is a literal in `project.ts`** — every value
   `evaluateValidations` reads comes from the `FieldDeclaration` argument. The only literals are the four
   validation *codes* and their message templates, which label which rule fired rather than encode what the
   rule's threshold is — the `REQ-001` line this task's acceptance criterion names.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run contracts/fixtures/view-model      # 19 fixtures (13 existing + 6 new), all passed
npx vitest run contracts src/features/designer    # 29 files, 142 tests, all passed
npx eslint contracts src/features/designer/FieldRow   # clean
npx tsc --noEmit -p tsconfig.json                 # clean
npx vitest run                                    # full suite: 170 files, 816 tests
npm run build                                     # production build succeeds
```

The full-suite run showed 4 failures across 3 files (`SolutionsPage`, `InstancesPage.a11y`,
`InstancesPage`), none touched by this change — `InstancesPage.a11y.test.tsx` carries its own comment
documenting this exact class of flake (*"passes in isolation, times out only alongside the other 130+
files"*). Re-ran both `InstancesPage` files in isolation: 25/25 passed.

## Status

DONE — `project()` now derives `ViewField.validations` from the package's declared constraints, live,
with zero hard-coded business rules; every already-shipped consumer (`FieldRow`, `SectionCard`,
`sectionFieldGroups`, `SectionNav`) picked it up with no changes of its own needed. 6 new shared fixtures,
lint-clean, typecheck-clean, full suite green modulo documented pre-existing flakes, production build
succeeds. Issue: [#68](https://github.com/parikshit-coe/solution-manager/issues/68).
