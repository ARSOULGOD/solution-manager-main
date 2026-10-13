# FE-062 · `CONFIG_DIVERGED` and rollback with a diff preview — Report

## What I implemented

Reference: `designer_panels.jsx`'s own `DivergedBanner` (`docs/design/frontend/reference/app/designer_panels.jsx:334-347`)
and `designer.jsx`'s own rollback modal (`docs/design/frontend/reference/app/designer.jsx:304-308`), plus its
wiring at `designer.jsx:239` (`DivergedBanner`'s three callbacks) and `:272` (`RolloutCard` always rendered as a
sibling, `diverged` toggling only its own chip).

| File | Summary |
|---|---|
| `frontend/src/features/designer/DivergedBanner/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New. The `err`-severity banner: exactly three remediations (re-stage / restart / rollback), no fourth, no "force healthy". Bare callbacks — no repository wiring, same split `BlockedBanner` (task 059) already uses. |
| `frontend/src/features/designer/RollbackDialog/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New. Calls `ApplyRepository.rollback()` itself (same pattern as `RestartConfirm`, task 061) and hands the settled `ApplyOutcome` up via `onApplied` — feeds the same `ApplyOutcomePanel` task 061 already built, since a rollback also resolves `INDETERMINATE` (`applyRepository.ts`'s own mock: no generation ordering exists for `dp-remote` here either). |
| `frontend/src/shared/i18n/messages.en.ts` | 13 new keys across the two components. |

Both components are feature-local (Designer only), same placement as every FE-059/060/061 component — no
gallery entry, no cross-feature reuse need. No new fixture or repository method was needed: `ApplyRepository`
(task 033) already had `rollback(instanceId, toGeneration)`, and `DiffView`'s real `DiffRow` contract (task 022,
`@shared/ui`) was reused directly rather than the stale `DiffRowFixture`/`SECRET_MASK_TOKEN` shape in
`testdoubles/data/designer.ts` — same choice `ConflictDialog`/`AcceptRuntimeDialog` already made.

## Design decisions, and why

1. **The replica table is not embedded inside `DivergedBanner`.** The task's own Acceptance line says "replica
   table visible alongside" — read literally as *alongside*, not *inside*. The reference confirms this reading:
   `RolloutCard` (line 272) renders unconditionally as a sibling regardless of scenario, with `diverged` only
   toggling its own chip; `DivergedBanner` itself never receives a `replicas` prop. Embedding `ReplicaTable`
   inside `DivergedBanner` would duplicate what `RolloutCard` (task 060) already owns and diverge from the
   reference's own composition. `DivergedBanner.test.tsx`'s last case proves the "alongside" requirement instead:
   it renders `DivergedBanner` next to a real `RolloutCard` and asserts both are present and neither hides the
   other — the same "banner above the config, never a replacement for it" relationship `BlockedBanner` already
   established (task 059's own wording, reused here deliberately).

2. **`onRestage`/`onRestart` are bare callbacks; only `onRollback`'s dialog is built here.** The task's Acceptance
   line singles out the rollback path specifically — "the rollback must render a `DiffView`… before the operator
   confirms" — and says nothing equivalent for re-stage or restart. The reference confirms why: `onRestage` fires
   a toast directly (re-staging is the same low-consequence "stage and wait" tier as the `manual` strategy's own
   default, no restart, no confirm needed) and `onRestart` opens the *existing* restart-confirm modal — which is
   `RestartConfirm`, task 061, already built. Re-implementing a second restart-confirm dialog here would
   duplicate that component for no reason `DivergedBanner`'s own Deps (060, 022, 016 — not 061) suggest was
   intended.

3. **`rollback()`'s outcome feeds the same `ApplyOutcomePanel` `RestartConfirm` feeds**, not a new one. Both
   `stage()` and `rollback()` return `ApplyOutcome`, and the mock handler resolves both to `INDETERMINATE` for
   the identical reason (no `dp-remote` generation ordering). Building a second outcome-handling panel for
   rollback specifically would be exactly the kind of copy this codebase's own conventions avoid — one
   `ApplyOutcomePanel` instance downstream of whichever dialog fired, selected by whatever a future Designer page
   wires to `onApplied`.

4. **`rows: readonly DiffRow[]` (the real `@shared/ui` contract type), not `DiffRowFixture` from
   `testdoubles/data/designer.ts`.** The latter's `secret: true` rows carry the string `SECRET_MASK_TOKEN`, which
   does not satisfy `DiffView`'s actual `DiffRow` union (`old`/`new` must be the `SECRET_MASK` symbol on a secret
   row) — a pre-existing, unreconciled fixture-vs-contract gap (same shape task 060's report flagged for
   `governanceRepository.ConfigLadderStep`). `ConflictDialog`/`AcceptRuntimeDialog` already sidestep it by taking
   `rows: DiffRow[]` as a caller-supplied prop rather than deriving it from the stale fixture; `RollbackDialog`
   follows the same path rather than reconciling the fixture gap under this task's commit.

5. **No dedicated "fetch the rollback diff" repository method was added.** Design §10.1 says the rollback diff
   follows "the same pattern §7.2 requires for `VERSION_DRIFT`" — and `AcceptRuntimeDialog`'s own `rows` prop is
   already caller-supplied for exactly that reason (no accept-runtime-diff-preview endpoint is specified either).
   Inventing a rollback-diff-preview endpoint here would be exactly the kind of shadow contract `FE-D7` forbids;
   `RollbackDialog` renders whatever `rows` it is given, same as `DiffView` itself does.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run src/features/designer/DivergedBanner src/features/designer/RollbackDialog  # 4 files, 14/14
npm run typecheck                                                                          # clean
npm run lint                                                                               # clean (project-wide, --max-warnings=0)
npx prettier --check <every file this task touched or added>                               # clean
npm run build                                                                               # production build succeeds
npm test                                                                                    # full suite: 210 files, 996 tests, 1 failed
```

**The 1 full-suite failure is not from this task.** It's in
`src/features/designer/ReplicaTable/ReplicaTable.a11y.test.tsx` (untouched by this task, and by task 061 before
it), an axe-core timeout under full-suite parallel load (`Test timed out in 5000ms`) — the same pre-existing
flake task 061's report already documented and confirmed unrelated by isolated re-run.

Task-specific acceptance items:

- **Three remediations, no fourth** — `DivergedBanner.test.tsx`'s first test asserts exactly 3 buttons exist;
  a second test asserts no button matches `/force healthy/i` and the three button labels are exactly the
  prescribed three, in order.
- **Rollback shows the diff first** — `RollbackDialog.test.tsx`'s first test: the diff rows and the confirm
  button are both present in the same render, with no gating step between them (the component has no internal
  "reveal the diff" state at all — it is always rendered).
- **Replica table visible alongside** — `DivergedBanner.test.tsx`'s last test: `DivergedBanner` and a real
  `RolloutCard` render together, both visible, neither hides the other.
- **No success claim from a response** — `RollbackDialog.test.tsx`'s confirm test asserts `onApplied` receives
  the exact `INDETERMINATE` value `rollback()` resolved to.
- **Secrets stay masked in the diff** — `RollbackDialog.test.tsx` asserts the secret row's path renders but no
  resolved/plaintext value ever appears (`I6`).

## Status

DONE — implemented, tested (14/14 on the touched suite; full suite 995/996, the 1 failure pre-existing and
unrelated), lint-clean, typecheck-clean, prettier-clean, production build succeeds. No Designer page exists yet
to wire `DivergedBanner` → `RestartConfirm`/`RollbackDialog` → `ApplyOutcomePanel` together on an instance's
Designer screen — the same gap every report since 055 has left open. Not yet marked done in
`task-list.md`/`execution.md` or closed on GitHub — pending commit approval.
