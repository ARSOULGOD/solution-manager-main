# FE-057 · Warning override with recorded reason — Report

## What I implemented

The gate `SaveBar` (task 055) explicitly deferred: its own report states *"No warn-override gate on
Save... task 057 owns that gate as a separate task"*. Reference `WarnReasonModal` in
`docs/design/frontend/reference/app/designer_panels.jsx` (Dec `UI-D16`, Design §10/§16, UI §A.8 override
reason dialog).

| File | Summary |
|---|---|
| `frontend/src/features/designer/OverrideReasonDialog/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New component, built from `Dialog`/`Alert`/`TextArea`/`Button` primitives (same compositional style as `ConflictDialog`, task 056). Lists each outstanding warning as `label — message`, requires a reason of **at least 3 non-empty words** before "Save & record reason" enables, and calls `onConfirm(reason)`. |
| `frontend/src/features/designer/ValidationSummary/collectValidationEntries.ts` (+ test) | `ValidationEntry` gains `message: string` — the firing validation's own message (first `bind`-tier for blocks, first `semantic`-tier for warns). `ValidationSummary`'s chips ignore it; the new dialog needs it to show *why* each field warned. |
| `frontend/src/features/designer/SaveBar/SaveBar.tsx` (+ types, tests, a11y test) | `recommendationCount: number` replaced with `warnings: readonly ValidationEntry[]` (SaveBar derives the count itself — one fewer number the caller must keep in sync with the list). Save's `onClick`: with `warnings.length > 0`, opens `OverrideReasonDialog` instead of saving; with none, saves immediately. The mutation now takes an optional `overrideReason` variable, forwarded into `SaveConfigBody.overrideReason` only when present — a field the repository layer (task 033) already anticipated. |
| `frontend/src/shared/i18n/messages.en.ts` | 6 new `overrideReasonDialog*` keys. |

## Design decisions, and why

1. **Minimum-reason rule is 3 non-empty whitespace-separated words, not the reference's `reason.trim().length
   < 4`.** The reference's own confirm-disabled check counts *characters* (4 of them — `"ok!!"` would pass)
   while its own helper text reads "Minimum a few words" a few lines below it — the two contradict each
   other. The task's own implementation line says "a free-text reason (minimum a few words)", so I built what
   the copy actually promises rather than silently copying the weaker check. Flagged here as a deliberate
   deviation from the reference, the same way task 053 flagged its own unevidenced `pattern` inference.
2. **`SaveBar.warnings: readonly ValidationEntry[]` replaces `recommendationCount: number`.** The dialog needs
   the full entries (label + message) to render its list; keeping a separate `recommendationCount` prop
   alongside it would be a second number that could drift from `warnings.length`, the same "one fewer thing
   to keep in sync" reasoning task 055's own report used for its `applyDisabled` rule. `blockingCount` is left
   as a bare number — unlike warnings, blocks need no per-field list in any dialog this task owns, so
   widening that prop too would be unrelated scope.
3. **`ValidationEntry.message` computed uniformly for both blocks and warns**, even though only warns need it
   today, rather than adding a warn-only field to the shared type. `collectValidationEntries` already treats
   both tiers symmetrically (`bind`/`semantic`), and a lopsided type (`message` present only on one variant)
   would need a discriminated union for no behavioural gain here.
4. **Confirming the dialog closes it synchronously, then calls `save.mutate(reason)`** — it does not wait for
   the mutation to resolve before dismissing. `FE-D20` (no optimistic *data* update) is about `dirtyCount`/
   `generation`, which still only ever move on `onSaved`; closing a modal is not predicting a save's outcome.
   A failed override save still surfaces through `SaveBar`'s existing inline `Alert` (or `onSaveError`/`409`
   path, untouched by this task) exactly as an un-gated save failure already does — clicking Save again reopens
   the dialog since `warnings` is unchanged.
5. **No `required` prop on the reason `TextArea`.** MUI appends a literal `" *"` to the computed accessible
   name whenever `required` is set, which would have made every `getByLabelText('Reason for override')` query
   need a partial match for no functional gain — the actual "reason is mandatory" behaviour is already fully
   enforced by the confirm button's `disabled` state, not by native HTML form validation this component never
   submits through.
6. **`Cancel` clears the typed reason on close** (both the dialog's own scrim/Escape/× path and the explicit
   Cancel button, all routed through one `handleCancel`) so reopening the dialog for a later Save always
   starts blank, never showing a stale draft reason from an abandoned attempt.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run src/features/designer/ValidationSummary/collectValidationEntries.test.ts   # RED then GREEN, TDD
npx vitest run src/features/designer/OverrideReasonDialog                                  # RED then GREEN, TDD
npx vitest run src/features/designer/SaveBar                                               # RED then GREEN, TDD
npx vitest run src/features/designer contracts                                             # 38 files, 181 tests — all passed
npm run typecheck                                                                          # clean
npm run lint                                                                                # clean (project-wide)
npx prettier --check <all touched files>                                                   # clean
npm test                                                                                    # full suite: 179 files; 6 files/14 tests failed
npm run build                                                                               # production build succeeds
```

The full-suite failures are confined to `src/shared/ui/DataTable/*` (`Unknown Error: Axe is already
running` and one timeout) — a pre-existing parallel-worker flake this task's own change never touches,
already documented in tasks 044/046/048/050/051/052/053/055's own reports. Re-ran `src/shared/ui/DataTable`
in isolation: 28/28 passed.

Task-specific acceptance items:

- **Override is impossible without a reason** — `OverrideReasonDialog.test.tsx`: confirm renders `disabled`
  with an empty reason and stays disabled at `'ok'` (2 words); `SaveBar.test.tsx`: clicking Save with
  outstanding warnings opens the dialog and `repository.save` is never called before confirmation.
- **Reason travels with the change** — `SaveBar.test.tsx`: confirming with a valid reason calls
  `repository.save('cache-prod-01', { fields, generation, overrideReason: '<exact text>' })`; a save with no
  warnings sends no `overrideReason` key at all.
- **axe clean, both themes** — `OverrideReasonDialog.a11y.test.tsx`, and `SaveBar.a11y.test.tsx`'s existing
  matrix (now exercising the `warnings` prop's new shape).

## Status

DONE — implemented, tested (181/181 on the designer+contracts suite, full suite green modulo the documented
pre-existing `DataTable` flake), lint-clean, typecheck-clean, prettier-clean, production build succeeds. No
Designer page exists yet to assemble `SaveBar`+`OverrideReasonDialog` end-to-end — the same gap tasks 055/056
left open. Not yet closed on GitHub — no issue number listed for this task in `task-list.md`.
