# FE-121 · One version rule: `packageVersion` reaches every `project()` call — Report

## What this closes

`project()`'s fifth parameter (`packageVersion`) is optional, and omitting it means no version filtering at
all — the behaviour every caller had before `FE-118` existed, preserved deliberately for old callers and
fixtures. Six call sites written before `FE-118` still omitted it: `useDesignerDraft.ts`'s render
(`viewModel`) and save-payload (`visibleValues`/`visibleInstanceValues`) paths, `Composer.tsx`'s
`computeNodeStats`, `ComposerFieldList.tsx`'s per-instance projection, `ComposerInspector.tsx`'s projection,
and `mergeReset.ts`'s `applyPackageDefaults` (which reads `component.fields` with no version filter at all,
not even via `project()`). A v6 instance would have rendered, seeded and saved a v7-only field — three
independent omissions of one argument.

## What was built

- `applyPackageDefaults(pkg, componentSelection, draft, packageVersion)` now filters both components and
  fields through the contract's own exported `inVersion()` — never a locally restated rule.
- `UseDesignerDraftArgs` gains `packageVersion: PackageVersionId`, threaded to both `project()` call sites
  (`viewModel`, `visibleValues`/`visibleInstanceValues`) and to the `applyPackageDefaults` call inside
  `onFieldChange`'s reset-by-merge, with `packageVersion` added to every dependency array that already lists
  `pkg`.
- `ComposerProps`, `ComposerFieldListProps` and `ComposerInspectorProps` each gain `packageVersion:
  PackageVersionId`, threaded from `Composer` down through `ComposerFieldList`/`ComposerInspector` to their
  own `project()` calls.

## The Cache package amendment

Verified over `contracts/packages/cache/package.json` before starting: no field was v6-only (so "removed in
v7" had no fixture), and none of the three v7-only fields declared a `default` (so "v6 seeds no v7 default"
would have passed for the wrong reason — nothing to seed at either version).

- `max-weight` (v7-only, Sizing) gained `"default": 536870912`.
- A new v6-only field, `max-size-bytes` (`"version": [6]`, `"default": 67108864`), was added — the byte-bound
  sizing field `max-weight` is authored to have replaced in v7.

**Authored demo history, not derived from the Cache MI** — the same posture `FE-D32` takes for the invented
v6 itself. Recorded in `contracts/packages/README.md`, which also had to drop its "v6 is a strict subset of
v7" claim: it no longer is, by design, so both directions of the version filter have something real to
exercise.

## Testing — all three levels, both directions

Per the task's own testing note, a suite checking only the *added-in-v7* direction would pass against an
implementation that filtered nothing on the v6 side — so both directions are asserted at all three levels:

| Level | File | What it proves |
|---|---|---|
| Defaults seeded into the draft | `mergeReset.test.ts` (new `describe`) | `applyPackageDefaults` against the real Cache package: v6 seeds `max-size-bytes`, never `max-weight`; v7 the reverse; a version-free default (`mode`) seeds identically at both. |
| Render | `useDesignerDraft.test.ts` (new `describe`) | `viewModel.sections` via the hook: v6 renders no `max-weight`, v7 renders no `max-size-bytes`; `namespace` (version-free) renders at both. |
| Save payload | same `describe`, same hook | `payload['cache-0']` carries exactly the version-appropriate field, with its default value, and never the other version's field. |

`cachePackageEquivalence.test.ts` (v7 equivalence against the frozen `CACHE_VIEW_MODEL` fixture) needed two
adjustments to stay green, both **normalising a deliberate demo-data amendment**, not a capability gap:

- `max-weight`'s new default/helperText are normalised out of the two whole-object comparisons, alongside
  the two pre-existing action gaps — documented inline as distinct from those gaps.
- The v6-projection test was rewritten from "v6 is a strict subset of v7" (no longer true) to "v6 is v7 minus
  the three added fields, plus the one replaced field" — asserting both directions explicitly.

This file is not in FE-121's own `Files` list (it is deleted whole in `FE-122`), but both the `max-weight`
default and the new `max-size-bytes` field would otherwise have left `npm test` red between the two tasks —
so it was updated here rather than left broken for one task's duration.

Composer/ComposerFieldList/ComposerInspector have no dedicated new tests: their `project()` calls reuse the
already-covered engine, and their existing test suites (`Composer.test.tsx`, `Composer.a11y.test.tsx`) were
updated only to supply the now-required `packageVersion` prop (`1`, against packages with no `version`
markings, so behaviour is unchanged).

## Files

| File | Change |
|---|---|
| `contracts/packages/cache/package.json` | `max-weight` gains a `default`; new v6-only `max-size-bytes`. |
| `contracts/packages/README.md` | "Invented v6" section rewritten for the no-longer-strict-subset relationship. |
| `src/features/designer/form/mergeReset.ts` | `applyPackageDefaults` gains `packageVersion`, filters via `inVersion`. |
| `src/features/designer/form/useDesignerDraft.ts` | `packageVersion` threaded to both `project()` calls and the reset-by-merge. |
| `src/features/designer/Composer/{Composer.types,Composer,ComposerFieldList,ComposerInspector}.tsx` | `packageVersion` threaded end to end. |
| `src/features/designer/form/mergeReset.test.ts` | `packageVersion` added to existing calls; new version-filtering `describe`. |
| `src/features/designer/form/useDesignerDraft.test.ts` | `packageVersion` added to existing calls; new version-filtering `describe`. |
| `src/features/designer/Composer/{Composer.test.tsx,Composer.a11y.test.tsx}` | `packageVersion={1}` added to render calls. |
| `src/app/dev/cachePackageEquivalence.test.ts` | Normalises the `max-weight` amendment; v6-projection test rewritten for both directions. |

## Acceptance

- ☑ the package expresses a field ADDED and a field REMOVED between versions (`max-weight` v7, `max-size-bytes` v6)
- ☑ no non-test `project()` call omits the version — verified by reading every call site in the six files above
- ☑ v6 draft, render and payload all exclude v7-only fields, and include the v6-only one — asserted at all three levels, both directions
- ☑ existing `mergeReset`/`useDesignerDraft` cases keep their meaning — all pre-existing tests pass unmodified in behaviour (only the new required `packageVersion` argument was added)

## Verification

`npm run lint` · `npm run typecheck` · `npm test -- src/features/designer` (346 tests) — all green, per the
task's own scoped Verify command.

Also ran, beyond what the task requires, to keep the tree green as a whole: `npm test` (full suite, 1301
tests) and `npm run build` — both green.
