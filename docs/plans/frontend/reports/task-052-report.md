# FE-052 · Draft form protocol (React Hook Form) — Report

## What I implemented

The `FE-D17` rule, implemented once: React Hook Form holds the whole Designer draft (every component
instance, every field, including inactive discriminator branches — `UI-D4` retention), and the save payload
is never read from it directly.

| File | Summary |
|---|---|
| `frontend/src/features/designer/form/useDesignerDraft.ts` | The hook. `useForm({ defaultValues: initialDraft, shouldUnregister: false })` seeded with the *entire* loaded draft (not just currently-visible fields) so retention holds without extra bookkeeping. `viewModel` re-runs `project()` on every draft change (obligation 4 falls out for free). `onFieldChange(instanceId, fieldKey, value)` sets the value and, only when the changed field is a `discriminator`, performs the reset-by-merge: recompute package defaults over the current full draft, merge them over it (never replacing), then `form.reset(...)`. `payload` and `isDirty`/`dirtyInstanceCount` are derived from `visibleValues()` — `project()` re-run per instance and filtered to `visible` fields — never from raw `getValues()` and never from RHF's own `formState.isDirty`. |
| `frontend/src/features/designer/form/mergeReset.ts` | Two pure functions, no RHF, no React: `applyPackageDefaults` (fills a missing field with its declared `default`, per selected instance, never overwriting a value already present) and `mergeReset` (merges `next` over `current` per instance, keeping any key `next` doesn't mention). |
| `frontend/src/features/designer/form/index.ts` | Barrel — `useDesignerDraft`, `applyPackageDefaults`, `mergeReset`, and their types. |
| `frontend/src/features/designer/form/{mergeReset.test.ts,useDesignerDraft.test.ts}` | 17 tests (5 + 12): pure-function merge/defaults behaviour in isolation, and the hook via `renderHook` — hidden-branch retention across a switch-away-and-back, a newly-active branch getting seeded with its declared default, a non-discriminator edit *not* triggering reset-by-merge, the save payload excluding an inactive branch's field and including a now-visible one, a secret field never resolving into the payload (`I6`), and dirty tracking surviving a discriminator round-trip without false-positiving. |
| `frontend/eslint.config.js` | `NO_GET_VALUES`: a `no-restricted-syntax` rule forbidding `MemberExpression[property.name="getValues"]` everywhere in `src/`, with an override block re-stating the file's other guardrails (raw colour/media/env-var/demo-state) for `src/features/designer/form/**` so only that module is exempt. |
| `frontend/package.json` | `react-hook-form@^7.86.0` (MIT, already vetted in `FE-D17`; peer-compatible with React 19). |

## Design decisions, and why

1. **The save payload is per-instance (`DraftValues` shape), not flattened by `renderedPath`.** I started
   with a flattened `renderedPath -> value` map (closer to "the literal MI config file"), but
   `ComponentDeclaration.configPath` is declared **per type**, not per instance — for a `one-of-many`
   component (File Mesh's multiple sources), two instances of the same type produce the *same*
   `renderedPath` for the same field, so a flattened map would silently collide two instances' values. This
   is the identical gap tasks 050/051 already documented against `ViewSection` (no component-instance
   identity survives into `project()`'s output) — I didn't re-litigate it, just avoided building on it where
   this task didn't have to. Keeping `payload` shaped as `instanceId -> (fieldKey -> value)` sidesteps the
   collision entirely and is still exactly "the rendered set from `project()`" per `FE-D17`; task 055 (save
   flow) is where the real wire shape gets decided, and can flatten this however the eventual API contract
   needs.
2. **Dirty tracking (and the payload) compare *visible* fields only, never the raw draft.** My first pass
   compared the full raw `DraftValues` against `initialDraft`, and a test caught it immediately: switching a
   discriminator away and back writes the newly-active branch's declared defaults into the raw draft (by
   design — see point 3), so the raw draft no longer matches the original baseline byte-for-byte even though
   nothing visible changed. `formState.isDirty` doesn't work either, since it's cleared by the reset-by-merge
   `form.reset()` call. Both problems disappear by scoping the comparison to `visibleValues()` — the same
   `UI-D4` filter `project()` itself applies — so a hidden branch's mechanically-seeded default never counts
   as an "unsaved change."
3. **Reset-by-merge writes a newly-active branch's declared defaults as real draft values**, not just as
   `project()`'s read-only default-fallback display. `design.md` §10's own wording ("new/derived values
   applied") reads as literal: the moment `DEPLOYMENT_MODE` becomes visible for the first time, it should
   hold a concrete starting value the operator can edit, not sit `unset`. `applyPackageDefaults` never
   overwrites a key already present, so an inactive branch's own previously-typed value is untouched by this
   — proven by the "seeds a newly-active branch field... " and "retains a hidden branch value..." tests
   running back to back against the same fixture.
4. **`visibleValues()` re-invokes `project()` per instance** (scoped to a single-entry `componentSelection`)
   rather than re-deriving visibility/defaults logic in this module. This can't drift from `project()`'s own
   obligations 1–4, and it's the same reason a secret field is provably absent from the payload: its
   `ViewField.value` is always `{ kind: 'secretUnset' }` (`I6`, obligation 7, already enforced in `project()`
   — task 046), which the `kind !== 'value'` filter here already excludes without this module needing to
   know what a secret field is.
5. **`useForm` is typed against RHF's own `FieldValues` (`Record<string, any>`), not `DraftValues` directly**,
   with casts at the RHF boundary inside this one module. `FieldValue`'s recursive array/object union made
   RHF's path-inference types (`FieldPath<T>`, used by `setValue`/`getValues`) recurse deeply enough to hit
   TypeScript's `TS2589` instantiation-depth limit once a templated path (`` `${instanceId}.${fieldKey}` ``)
   entered the mix. The external contract of the hook is still exactly `DraftValues` in and out — only the
   internal RHF plumbing is loosely typed, and only in this file.
6. **`getValues` is called exactly once** (inside `onFieldChange`, to snapshot the current full draft before
   computing reset-by-merge) — enforced by a new ESLint rule rather than left as a convention. I checked the
   rule actually fires by lint-checking a scratch file outside `designer/form` that called `.getValues()`
   before deleting it; it does.
7. **No page wires this up yet.** Nothing under `src/features/designer` outside this task's own files and
   tests calls `useDesignerDraft` — the plan's own **Files** line for this task lists only the hook and the
   merge helper, and no Designer page exists yet to consume it (`SectionCard`'s `values`/`onFieldChange` props
   are shaped to accept `getInstanceValues(instanceId)`/`onFieldChange` directly, once one does).

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run src/features/designer/form   # 2 files, 17 tests, all passed
npx eslint src/features/designer/form eslint.config.js   # clean
npx tsc --noEmit -p tsconfig.json           # clean
npx tsc --noEmit -p tsconfig.node.json      # clean
npm run check:forbidden-deps                # no Pro/Premium packages
npx vitest run                              # full suite: 170 files, 810 tests
npm run build                               # production build succeeds
```

The full-suite run showed one pre-existing flake unrelated to this change — `Toaster.a11y.test.tsx`'s "Axe is
already running" cluster (the same class of parallelism flake FE-046/048/050/051's reports already document,
in a file this task never touched). Re-ran it in isolation: 2/2 passed.

I also lint-checked the new `getValues()` guardrail directly: a scratch file under
`src/features/designer/SectionCard/` calling `.getValues()` fails lint with the rule's message naming
`useDesignerDraft.ts` as the only allowed caller; the same call inside `useDesignerDraft.ts` itself passes.
Scratch file removed after the check.

## Status

DONE — implemented, tested (17/17 new, full suite green modulo the documented pre-existing flake), lint-clean,
typecheck-clean (both `tsconfig.json` and `tsconfig.node.json`), production build succeeds. No contract gap
newly discovered; the pre-existing `ViewSection` instance-identity gap (tasks 050/051) was avoided rather than
built on. Issue: [#67](https://github.com/parikshit-coe/solution-manager/issues/67).
