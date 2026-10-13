# FE-056 · `409` conflict → refetch and diff — Report

## What I implemented

The seam `SaveBar` (task 055) already documents and reserves: `onSaveError` fires on every failed save
including a `409`, staying silent itself so this task's own UI could own it (design §10.1, §16 · UI §A.8
conflict dialog).

| File | Summary |
|---|---|
| `frontend/src/features/designer/ConflictDialog/ConflictDialog.tsx` (+ types, tests, a11y test, `index.ts`) | New component. Explains that nothing was overwritten, renders `DiffView` (task 022) of the caller-supplied "your draft → current record" rows with secrets masked, and offers exactly four resolution buttons: three (`Overwrite anyway` / `Take theirs` / `Merge per field`) permanently disabled with a stated "not specified" tooltip, and `Refetch & review` — the only enabled path, which calls `ConfigRepository.loadDesigner` and hands the fresh `DesignerLoad` back to the caller via `onRefetched` on success. |
| `frontend/src/shared/i18n/messages.en.ts` | 13 new `conflictDialog*` keys — title, explanatory copy, diff column labels, the three disabled actions and their shared tooltip, and the refetch action/pending/error text. |

## Design decisions, and why

1. **`rows` is a prop, computed by the caller, never inside this component.** The `409` body shape is
   `API CONTRACT — TBD` (§16 Q9) — there is no actor, no timestamp, and per the seam table in
   `design.md` §9.2, "the current record, enough to diff" is itself an unspecified need. Building a
   diff-computation path against an unspecified wire shape would be exactly the kind of invented
   contract `I2`/`FE-D7` warn against (a fixture that gets promoted to a contract by accident). Keeping
   `rows: readonly DiffRow[]` as an input mirrors how `DiffView` itself already works ("renders what
   it's given") and matches the existing `DIFF_DRAFT`/`CONFLICT_THEIRS` fixture pair already staged in
   `shared/testdoubles/data/designer.ts` for exactly this purpose. Whoever eventually assembles the
   Designer page supplies real rows once Q9 lands; today's callers (tests, a gallery, a future fixture
   page) supply fixture rows.
2. **`Refetch & review` is real, not fixture-scoped, because `loadDesigner` already is.** Unlike the
   diff rows, "reload the current record" needs no unspecified wire shape — `ConfigRepository.loadDesigner`
   is a real method every Designer load already calls. Wiring the button to an actual `useMutation`
   (rather than a caller-supplied no-op callback) means this one path in the dialog is genuinely
   testable end-to-end today, not just a placeholder waiting on Q9.
3. **`onRefetched(load)`, not a close-on-success side effect.** Same split `SaveBar.onSaved` and
   `LoadPackageDialog.onLoaded` already use in this codebase: the component reports what happened, the
   caller decides what to do with it (reset the draft baseline, close the dialog) and a failed refetch
   therefore can never leave the caller in an inconsistent state, because nothing fires until success.
4. **No secret-masking logic lives in this component.** `DiffRow`'s own type (`DiffView.types.ts`)
   already makes a `secret: true` row's `old`/`new` a literal `SECRET_MASK` symbol, not a string — a
   real value in a secret row is a type error, not a runtime check this component would need to
   duplicate (`I6`, enforced the same way FE-058's action-verdict rendering is).
5. **First bug caught by the tests, not review: `onSuccess: onRefetched` passed the mutation's extra
   `(variables, context)` arguments straight through.** `useMutation`'s `onSuccess` callback receives
   three arguments; wiring the prop directly as the handler meant `onRefetched` (typed to take exactly
   one) still received all three at runtime, and a `toHaveBeenCalledWith(LOAD_RESULT)` assertion timed
   out rather than failing fast, because `waitFor` kept retrying against a call that would never match.
   Fixed by wrapping it in `onSuccess: (load) => { onRefetched(load); }` so the caller's contract stays
   exactly one argument.
6. **No visual reference to match.** Checked `docs/design/frontend/reference/Solution Manager v2.html`
   and the reference `app/*.jsx` data files directly — neither contains any conflict-dialog markup, so
   there is nothing to port pixel-for-pixel the way `LoginPage.tsx` or `SaveBar.tsx` could. Built the
   dialog from the existing `Dialog`/`Alert`/`Button`/`Tooltip`/`DiffView` primitives instead, the same
   composition `LoadPackageDialog` (task 040) already uses for a repository-driven dialog with inline
   error states — consistent with the rest of the app rather than inventing a new visual pattern for a
   screen the prototype never designed.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                                            # clean
npm run typecheck                                                        # clean
npm test -- src/features/designer --environment jsdom                    # 35 files, 151 tests — all passed
npm test                                                                  # full suite: 177 files, 852 tests — all passed
npm run build                                                            # production build succeeds
```

(`npm run test:a11y` from the task's own Verify line doesn't exist as a script — same pre-existing gap
noted in the task-106 and task-111 reports; `ConflictDialog.a11y.test.tsx` ran as part of the `npm test`
invocations above, both light and dark mode.)

Task-specific acceptance items:

- **No invented resolution** — `ConflictDialog.test.tsx`: `Overwrite anyway`, `Take theirs` and `Merge per
  field` all render `disabled`, unconditionally, with no prop or state that could ever enable them.
- **Refetch-and-reapply is the only working path** — the same test file: `Refetch & review` renders
  enabled, calls `repository.loadDesigner(instanceId)` on click, and hands the resolved `DesignerLoad`
  to `onRefetched`; a refetch failure renders an inline error and never calls `onRefetched`; closing the
  dialog (the `×` button) calls `onClose` and never `onRefetched` — dismissing is not a resolution.
- **Secrets masked** — the rendered diff shows the literal mask string for both `old` and `new` on the
  secret row, never the underlying `SECRET_MASK` symbol or a real value; enforced at the type level by
  `DiffRow` itself, so this can't regress without a type error.
- **axe clean, both themes** — `ConflictDialog.a11y.test.tsx`.

## Status

DONE — implemented, tested (852/852 on the full run), lint-clean, typecheck-clean, production build
succeeds. Not yet closed on GitHub — pending review, particularly Ruling 1 (rows supplied by the caller
rather than computed here) and Ruling 6 (no page wiring exists yet to exercise this end-to-end, the same
gap `SaveBar`'s own report left open).
