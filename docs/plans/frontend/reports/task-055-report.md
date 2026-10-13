# FE-055 · Save flow, save bar, config generation — Report

## What I implemented

The unblocked half of `FE-D18`: writing a Designer draft to the DP's own configuration-of-record
(design §10.1/§13, UI §A.8 save bar). Apply/restart (clause B) stays untouched — `DECISIONS.md` open
item 1 is still unresolved — this task only wires the save side and exposes the seam a future Apply
implementation (task 061, blocked) will fill in.

| File | Summary |
|---|---|
| `frontend/src/features/designer/SaveBar/SaveBar.tsx` (+ types, tests, a11y test, `index.ts`) | New component: sticky bar showing dirty/blocking/recommendation counts, `Save draft` and `Apply…`. Owns a `useMutation` calling `repository.save()` directly (no page exists yet to own it) — on success pushes the prescribed toast and calls `onSaved`; on a non-`409` failure renders an inline `Alert`; on `409` stays silent and defers to `onSaveError` (task 056's seam). No optimistic state anywhere — `dirtyCount`/`generation` are caller-owned and only ever move in response to `onSaved`. |
| `frontend/src/features/designer/configRepository.ts` | `DesignerLoad` gains `configGeneration: string` (the load-time baseline `SaveBar` needs — nothing produced this before). `SaveConfigResult` gains `correlationId: string`; `save()` now calls `postWithCorrelation` instead of `post`, matching `solutionsRepository.load()`'s own established convention for a mutation that reports its own outcome (`FE-D13`). |
| `frontend/src/shared/testdoubles/handlers/dp.ts` | Designer `GET` fixture now returns `configGeneration: 'gen-41'` — reuses the same generation the config-history top entry and `ROLLOUT_REPLICAS` already use, so the fixture's generation story doesn't contradict itself across load/save/rollout. |
| `frontend/src/features/designer/configRepository.test.ts` | Asserts `load.configGeneration` and `saved.correlationId` are present; save now sends `load.configGeneration` rather than a literal, matching real usage. |
| `frontend/src/shared/i18n/messages.en.ts` | 13 new keys for the bar's copy, tooltips, and success/error toast text — including the exact success sentence task 055 pins in its own spec. |

## Design decisions, and why

1. **`SaveBar` owns its mutation and pushes its own success toast**, unlike the `LoadPackageDialog` /
   `SolutionsPage` split (task 040) where the *page* pushes the toast for a dialog's result. There is
   no Designer page yet for that role — Phase 2 built components bottom-up (`SectionCard`,
   `ValidationSummary`, …) with no page assembling them — and task 055's acceptance criteria pins an
   exact success sentence. Leaving that unpushed until some future, unscheduled assembly task would
   leave a specified requirement unmet. `onSaveError` (not in the original prop sketch) exists so task
   056, which *does* need to intercept a `409` specifically, has a seam without reaching into this
   component's mutation state.
2. **Apply's disabled rule is `dirtyCount > 0`, not "a save happened this session".** The plan text
   ("Apply is disabled until saved") reads either way; I took the literal "matches what's on record"
   reading over an implicit save-in-this-session flag, because a config saved in an earlier session and
   never applied is exactly as apply-able as one just saved here, and a separate `saved` boolean would
   be redundant state that could drift from `dirtyCount` (itself a deep comparison against the caller's
   baseline, task 052) — one fewer thing to keep in sync.
3. **No warn-override gate on Save.** The reference prototype pops a reason dialog when warnings are
   present before saving; task 057 ("Warning override with recorded reason", `Deps: 053, 055, 016`) owns
   that gate as a separate task. FE-055's own disabled rule is only `blockingCount > 0 || dirtyCount ===
   0` — saving with recommendations outstanding is allowed and silent here, by design, until 057 lands.
4. **The explanatory tooltip on Save/Apply only ever wraps the DISABLED state.** This project's shared
   `Tooltip` (task 014) wraps its child in a focusable `role="button"` span — correct when the target is
   a disabled, unfocusable control (nothing else there to give the explanation a home) but a
   `nested-interactive` axe violation over an already-focusable enabled button. Found via the a11y test,
   not assumed: the first draft wrapped both buttons unconditionally and both light/dark a11y cases
   failed. `SolutionCard` (task 039) makes the identical disabled-only choice for its own tooltip-wrapped
   button, so this isn't a new pattern.
5. **`configRepository.save()` switched to `postWithCorrelation`, matching `solutionsRepository.load()`
   exactly** (`{ ...data, correlationId }`), rather than inventing a second way to carry a correlation id
   through a repository seam.
6. **`DesignerLoad.configGeneration` added rather than leaving "the last-seen generation" as a bare
   prop with no real source.** Q8 marks the *field name* TBD, not the generation's existence — and
   without it, `SaveBar`'s own "generation on every save" acceptance criterion would only be
   demonstrable with a hand-fabricated value, never against what `loadDesigner()` actually returns.
7. **`payload`/`dirtyCount` are props, not something `SaveBar` derives.** `useDesignerDraft` (task 052)
   already computes `payload` (the `project()`-rendered, `getValues()`-free set) and
   `dirtyInstanceCount`; `collectValidationEntries` (task 054) already computes `blocks`/`warns`. Passing
   these through keeps `SaveBar` a plain, repository-driven component — like `LoadPackageDialog` — with
   no dependency on RHF or the view-model engine directly.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json --noEmit                                                  # clean
npm run typecheck                                                                  # clean (both tsconfigs)
npx eslint src/features/designer/SaveBar src/features/designer/configRepository.ts \
  src/features/designer/configRepository.test.ts src/shared/testdoubles/handlers/dp.ts \
  src/shared/i18n/messages.en.ts --max-warnings=0                                  # clean
npx prettier --check <same files>                                                  # clean
npx vitest run src/features/designer                                               # 33 files, 144 tests, all passed
npm run build                                                                       # production build succeeds
```

`npm test` (full suite, parallel workers): two separate runs each failed 2–3 unrelated tests
(`DataTable` axe-concurrency error, `InstancesPage` bulk-restart timeout) with a *different* failing
set each run — reproduced on a clean stash of this change too, and both files pass 100% in isolation.
Matches the same parallel-worker flake noted in tasks 050/051/052/053/046/048/044's own reports; not
caused by this change.

## Status

DONE — `SaveBar` renders dirty/blocking/recommendation counts, disables Save on any blocker or a clean
draft, disables Apply while dirty, sends the rendered payload plus the last-seen generation on every
save, and never optimistically updates before a save resolves. Lint-clean, typecheck-clean, prettier-
clean, designer-feature suite green, production build succeeds.
