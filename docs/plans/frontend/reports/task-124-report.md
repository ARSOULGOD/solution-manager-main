# FE-124 · The composer branch and the rollout/apply half — Report

## What this closes

`DesignerForm` (FE-123) handled only the single-component flat form. Two things it deferred are built here:
a package composing more than one component now gets the Composer canvas instead of a merged, possibly
mis-scoped flat list; and the rollout/apply half of the screen (`RolloutCard`, `StrategyPicker`,
`RestartConfirm`, `ApplyOutcomePanel`, `DivergedBanner`, `RollbackDialog` — all built through task 063, none
previously wired to anything) appears and does what it can do given `DECISIONS.md` open item 1: nothing
stronger than `APPLY_INDETERMINATE`.

## What was built

**One draft, two views (`UI-D1`).** `componentSelection.length > 1` renders `<Composer>` in place of the
nav+section-list+search block; a single-component package keeps exactly what `FE-123` built, unchanged.
Both branches share the SAME `useDesignerDraft()` call this file already owned — `Composer` takes its
`getInstanceValues`/`onFieldChange` directly, never a second draft — so switching between the canvas and
Composer's own "All fields" toggle can never lose an edit or diverge from the save payload. `BlockedBanner`,
`PackageActions` and `SaveBar` wrap both branches identically; `ValidationSummary`/`SectionNav`/`FieldSearch`
stay single-component-only, since their jump-to-field model has no defined mapping onto Composer's
per-component-scoped internal state.

**Rollout/apply, single-component only.** No decision anywhere defines a per-component rollout story for a
composed solution, so — the same posture `FE-115`'s declare form takes toward `one-of-many` composition —
it is not invented. For a single-component package: `SaveBar.onApply` opens a `Dialog` wrapping
`StrategyPicker` (conservative `requiresUniformConfig`/`healthMappingDeclared` defaults — the same posture
`viewModelFor.ts` took, no source for the real MI-specific facts) → "Continue" opens `RestartConfirm`, which
calls `applyRepository.stage()` itself and hands back whatever `ApplyOutcome` it settles to (never
"succeeded" — `applyRepository.ts`'s own header) → `ApplyOutcomePanel` renders it, `onConfirm` calling
`confirmActual()`. `DivergedBanner` appears whenever `observed.configState === 'diverged'` OR the apply
outcome itself came back `DIVERGED`, offering its three remediations (restage / restart / roll back via
`RollbackDialog`) — never a fourth "force healthy".

**`reached: ConfigLadderReachedKey`** is derived locally, never fetched (Governance/Phase 5 doesn't exist yet
to fetch it from, and no decision maps `ObservedState.configState` onto the ladder): `'desired'` until this
session's own apply flow reports `INDETERMINATE`/`CONFIRMED_BY_OPERATOR`/`DIVERGED`, then `'restart-observed'`
— exactly what `INDETERMINATE` means (`G11`), never more.

**No source, left honest rather than fabricated:** `RollbackDialog`'s `targetGeneration` (no rollback-diff
endpoint names it) is `''`; `ConflictDialog`/`AcceptRuntimeDialog`'s `rows` were already `[]` per `FE-123`.

## A second instance of FE-123's a11y bug

`RolloutCard`'s own collapsible-header title used the identical unqualified `variant="subtitle2"` pattern —
a real `<h6>` inside a toggle `<button>`, skipping heading levels under `InstanceHeader`'s `<h1>`. Invisible
until this task wired `RolloutCard` into a real page for the first time (`InstanceShell.a11y.test.tsx`'s
`cache-prod-01` check caught it immediately). Fixed identically (`component="span"`). Swept the rest of the
designer feature for the same unqualified-`subtitle`/heading-variant pattern — the other three occurrences
(`ListControl`/`MapControl`/`TableControl`) already override to `component="legend"` correctly, so no further
instances remain.

## Testing

New `describe` blocks in `DesignerPage.test.tsx`:
- Single-component solutions show no `Composer`/`All fields` toggle; a multi-component package renders the
  Composer canvas (`Component types` palette) and no flat `Sections` nav.
- Switching from the canvas to "All fields" loses no edit — typed into the inspector, still present after
  the toggle.
- Apply is offered only once `dirtyCount === 0` — enabled with a saved-matching draft, disabled the instant a
  field is edited.
- Restart is confirmed with a replica count (`Apply & restart 1 replica`, the `replicas.length || 1` fallback
  since nothing has been observed yet); applying renders `APPLY_INDETERMINATE`'s own copy ("Outcome not
  confirmed") with no success-verdict text anywhere on the page.
- The diverged banner offers exactly its three remediation buttons and no fourth "force healthy" button
  (checked by button count, not by absence of the phrase — the banner's own copy correctly names that
  absence in prose: `'there is no "force healthy"'`).

`DesignerPage.a11y.test.tsx` gained a Composer-canvas check, both colour modes.

**Acceptance line, verified directly:** `grep -riE 'applied successfully|apply succeeded' src` finds nothing
outside this task's own negative test assertion.

**Manual browser verification** (`npm run dev` + Playwright, per CLAUDE.md): drove the full apply flow on
`cache-prod-01` — Apply → strategy dialog → "Apply requires a restart" (4 replicas) → confirmed →
`ApplyOutcomePanel` rendered with the real 4-row replica table (`ledger-0..3`, mixed Observed/Pending ·
Healthy/Restart-expected/Inactive), "Confirm actual outcome" button, `RolloutCard` showing "4 replicas".
Zero console errors throughout.

## Files

| File | Change |
|---|---|
| `src/features/designer/DesignerPage/DesignerPage.tsx` | Forwards `applyRepository` to `DesignerForm`; module doc updated. |
| `src/features/designer/DesignerPage/DesignerPage.types.ts` | `applyRepository` doc comment updated (now used, not just plumbed). |
| `src/features/designer/DesignerPage/DesignerForm.tsx` | Composer branch; rollout/apply state machine and modals; module doc rewritten. |
| `src/features/designer/RolloutCard/RolloutCard.tsx` | `component="span"` heading-order fix. |
| `src/shared/i18n/messages.en.ts` | Two new keys (`designerApplyStrategyDialogTitle`, `designerApplyDialogContinue`); reuses the existing generic `cancel` key rather than inventing a duplicate. |
| `src/features/designer/DesignerPage/{DesignerPage.test.tsx,DesignerPage.a11y.test.tsx}` | New Composer/rollout/apply coverage. |

## Acceptance

- ☑ single-component solutions show no toggle
- ☑ switching views loses no edit
- ☑ `grep -riE 'applied successfully|apply succeeded'` finds nothing
- ☑ open item 1 not claimed as closed (stated in the module header, `ApplyOutcomePanel`'s own copy, and this report)

## Verification

`npm run lint` · `npm run typecheck` · `npm test` (1311 tests) · `npm run build` — all green. Manual browser
verification per above.
