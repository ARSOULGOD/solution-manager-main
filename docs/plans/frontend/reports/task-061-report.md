# FE-061 · Apply: strategy picker, restart confirm, `APPLY_INDETERMINATE` — Report

## What I implemented

Reference: `designer_panels.jsx`'s own `StrategyPicker` (`docs/design/frontend/reference/app/designer_panels.jsx:188-226`)
and `designer.jsx`'s own `RestartConfirm` (`docs/design/frontend/reference/app/designer.jsx:321-332`), plus
`applyRepository.ts` (task 033 scaffold) which already encodes the one hard rule — no `ApplyOutcome` variant
named "succeeded" exists.

| File | Summary |
|---|---|
| `frontend/src/features/designer/StrategyPicker/` (new: `.tsx`, `.types.ts`, `strategyAvailability.ts` + `.test.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New. Restart-strategy radio group. Suppression logic lives in `strategyAvailability.ts`, a pure function, same split as `majorityGeneration.ts` (task 060). |
| `frontend/src/features/designer/RestartConfirm/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New. The design-§10.1-prescribed confirm dialog. Calls `ApplyRepository.stage()` itself (same pattern as `AcceptRuntimeDialog`) and hands whatever `ApplyOutcome` it settles to up via `onApplied` — never a success verdict. |
| `frontend/src/features/designer/ApplyOutcomePanel/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New — "the load-bearing part." Renders `INDETERMINATE`/`CONFIRMED_BY_OPERATOR` from `ApplyOutcome`; blocks retry until the operator calls `confirmActual()` via `onConfirm`, then offers retry via `onRetry` only from the confirmed state. `NOT_STARTED`/`IN_FLIGHT`/`DIVERGED` render nothing — `DIVERGED` is deliberately deferred to task 062. |
| `frontend/src/shared/testdoubles/data/designer.ts` | `DesignerViewModel` gains `healthGateUnsafeReason?: string`. **Fixed a fixture bug**: `CACHE_VIEW_MODEL.requiresUniformConfig` was `false`, contradicting `DECISIONS.md` open item 15 ("`mustBeUniformAcrossReplicas` is genuinely forced true" for Cache) — corrected to `true`, and `healthGateUnsafeReason` set to the decision's own standby wording. |
| `frontend/src/shared/i18n/messages.en.ts` | 25 new keys across the three components. |

All three components are feature-local (Designer only), same placement as `ReplicaTable`/`RolloutCard` (task
060) and `VersionPill`/`BlockedBanner` (task 059) — no gallery entry, no cross-feature reuse need.

## Design decisions, and why

1. **The fixture fix.** `CACHE_VIEW_MODEL.requiresUniformConfig: false` directly contradicted
   `DECISIONS.md` open item 15, which the task's own Objective line names as "the design's hardest constraint,
   implemented honestly." Building the picker against the fixture as-found would have shown Cache's
   `rolling-health-gated`/`rolling-no-validation` as *available*, exactly the false settlement the task exists
   to prevent. Fixed at the source (the fixture) rather than special-cased in the component, so every future
   consumer of `CACHE_VIEW_MODEL` (task 065's governance ladder, a future Designer page) gets the correct value
   for free. No test previously asserted the old `false` value (confirmed by grep across `src/`), so nothing
   else needed updating; `FILE_MESH_VIEW_MODEL` was left untouched — no decision names it, and touching it would
   be scope this task doesn't cover.

2. **Two independent suppression signals, not one, for `rolling-health-gated`.** The reference JSX (`model.isFlagship`)
   and open item 15 both distinguish "Cache's standby lies about health" from "this package requires uniform
   config" — related but not the same claim, and conflating them would give `rolling-no-validation` (which has
   nothing to do with health gating) the standby narrative as its stated reason, which would be dishonest copy.
   `strategyAvailability.ts` checks `healthGateUnsafeReason` (MI-specific, e.g. Cache's standby wording) *before*
   the generic `requiresUniformConfig` check, so a solution where both are true — Cache — states the specific,
   honest cause for the strategy that specific cause explains, and the generic reason for the one it doesn't.
   `strategyAvailability.test.ts`'s last case proves both halves of this from one fixture.

3. **Every suppression states its own reason — not just the aggregate banner.** The reference relies on a single
   banner above the list plus a bare "Unavailable" chip for the uniform-config case, with no per-card text. The
   task's own Acceptance line is explicit — "every suppression states its reason" — so `evaluateStrategyAvailability`
   never returns `disabled: true` without a `reason`, and the picker renders it under the strategy's own
   description. The banner still renders too (design §10.1's "the picker says why"), so the aggregate warning and
   the per-card reason coexist rather than one substituting for the other.

4. **A native MUI `RadioGroup`, not the reference's hand-rolled `role="radio"` div.** The reference's own
   div-based implementation gets `tabIndex`/`onKeyDown` for Enter/Space but never implements a roving tabindex
   or arrow-key movement between options — the ARIA radio-group pattern's own expected behaviour. `RadioGroup`/
   `Radio`/`FormControlLabel` provide that, disabled-skip included, without hand-rolling it.

5. **`RestartConfirm` owns its own `stage()` call**, matching `AcceptRuntimeDialog`/`RestoreRuntimeDialog`
   (both call their own repository method via `useMutation` and hand the settled value up), rather than being a
   "pure confirm" dialog with `stage()` called by a caller. This keeps `onApplied(outcome)` the single seam
   between "the operator confirmed" and "here is what actually happened" — the same seam `ApplyOutcomePanel`
   consumes on the other side.

6. **`ApplyOutcomePanel` renders nothing for `DIVERGED`.** `CONFIG_DIVERGED`'s own remediation banner (re-stage/
   restart-remaining/rollback-with-diff) is task 062's Acceptance line, not this task's — FE-061's Deps (060,
   016, 032) don't reach a `DiffView`-based rollback flow, and building it here would answer a different task's
   acceptance criteria under this one's commit. A one-line code comment states this rather than leaving it
   silently unhandled.

7. **Retry is reachable only from `CONFIRMED_BY_OPERATOR`, never from `INDETERMINATE`.** Design §10.1's own
   wording — "offer only operator-initiated retry **after confirming actual**" — is an ordering constraint, not
   just an actor constraint. `ApplyOutcomePanel` renders a "Confirm actual outcome" button for `INDETERMINATE`
   and only a "Retry apply" button for `CONFIRMED_BY_OPERATOR`; no code path exposes both at once.
   `ApplyOutcomePanel.test.tsx` asserts the retry button is entirely absent (not merely disabled) while
   `INDETERMINATE`. (`QueryClient`'s own `mutations: { retry: false }`, task 029, already rules out an
   *automatic* retry underneath this — this task's job is only to not add a manual one that jumps the queue.)

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run src/features/designer/StrategyPicker src/features/designer/RestartConfirm \
  src/features/designer/ApplyOutcomePanel                       # 7 files, 38/38
npm run typecheck                                                # clean
npm run lint                                                     # clean (project-wide, --max-warnings=0)
npx prettier --check <every file this task touched or added>     # clean
npm run build                                                    # production build succeeds
npm test                                                         # full suite: 206 files, 982 tests, 2 failed
```

**The 2 full-suite failures are not from this task.** Both are in
`src/features/designer/ReplicaTable/ReplicaTable.a11y.test.tsx` (untouched by this task), an axe-core timeout
under full-suite parallel load (`Test timed out in 5000ms` / `Axe is already running`). Confirmed pre-existing
and unrelated by re-running that file in isolation: 2/2 green.

Task-specific acceptance items:

- **No success claim from a response** — `RestartConfirm.test.tsx`'s confirm test asserts `onApplied` receives
  the exact `INDETERMINATE` value `stage()` resolved to; `ApplyOutcomePanel.test.tsx` asserts no "Success"/"Failed"
  text renders and the reason/observed-time are shown verbatim instead.
- **Retry is operator-initiated** — `ApplyOutcomePanel.test.tsx`: the retry button does not exist while
  `INDETERMINATE`, exists only once `CONFIRMED_BY_OPERATOR`, and confirming/retrying each fire only their own
  callback.
- **Every suppression states its reason** — `strategyAvailability.test.ts` covers all four disabled branches
  (`outOfRelease`, missing health mapping, MI-specific health-gate-unsafe, generic uniform-config) each
  returning a non-empty `reason`; `StrategyPicker.test.tsx` asserts the reason text renders in the DOM for the
  Cache fixture and for `scheduled`.
- **Default is `manual`** — `StrategyPicker.test.tsx`'s first test asserts the `manual` radio is checked and
  enabled, and carries the "Default" chip.
- **`rolling-health-gated` unavailable for the Cache fixture** — `StrategyPicker.test.tsx`'s Cache-fixture test
  and `strategyAvailability.test.ts`'s last case, both against the corrected `CACHE_VIEW_MODEL`.
- **An apply that times out renders `APPLY_INDETERMINATE`, not success or failure** — covered at the unit level
  by `RestartConfirm.test.tsx`/`ApplyOutcomePanel.test.tsx` above; no e2e harness exists yet to run this against
  a real browser (same gap every report since 055 has left open — no Designer page assembling these components
  exists yet either).

## Status

DONE — implemented, tested (38/38 on the touched suite; full suite 980/982, the 2 failures pre-existing and
unrelated), lint-clean, typecheck-clean, prettier-clean, production build succeeds. No Designer page exists yet
to wire `StrategyPicker` → `RestartConfirm` → `ApplyOutcomePanel` together on an instance's Designer screen — the
same gap every report since 055 has left open. Not yet marked done in `task-list.md`/`execution.md` or closed on
GitHub — pending commit approval.
