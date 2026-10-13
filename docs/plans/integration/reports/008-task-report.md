# Integration plan 008 · Designer & config — Report

**Result:** ⚠️ **partial.** Task 3 (`getConfigHistory`, `setConfigSecret`) is done, TDD'd, and verified. Tasks 1, 2, 4
and the live-browser half of Task 5 are **not done** — see Rulings below for why, and what a follow-up needs.

## What was built (Task 3)

- `frontend/src/features/designer/configRepository.ts`:
  - `ConfigHistoryEntry` now matches `console.yaml`'s `ConfigHistory.entries[]` exactly: `generation: number` (was
    `string`), `at`/`by` (were `savedAt`/`savedBy`), optional `configHash`. `history()` now unwraps the real
    `{stateVersion, entries}` envelope (`Versioned` + `entries`) instead of trusting a bare array — the fixture used
    to return one directly, the contract never did.
  - New `StagedConfig` type (`{stateVersion, generation}`) — `setConfigSecret`'s actual 200 body per `console.yaml`
    ("Ratified … one call, `200 StagedConfig` … this operation NEVER RETURNS a resolved value"). `setSecret()`'s
    return type changed from the invented `{ ref: string }` to this.
  - `SecretIntent` gained the contract-required `baseStateVersion: number`.
- `frontend/src/features/designer/index.ts`: exports `StagedConfig`.
- `frontend/src/shared/testdoubles/handlers/dp.ts`: the `GET .../config/history` and
  `POST .../config/secrets/:fieldKey` MSW handlers now return the real envelope/shape instead of the old
  fixture-only one.
- `frontend/src/features/designer/configRepository.test.ts`: two new test blocks (TDD — written first, watched RED,
  then made GREEN):
  - `getConfigHistory` maps the real envelope (numeric `generation`, `at`/`by`), not the old fixture shape.
  - **I6 test for `setConfigSecret`**: asserts `JSON.stringify(result)` never contains the literal secret value
    sent in the request, and that the response shape is a bare `StagedConfig` — no `ref`, no `value`. This is the
    test the plan explicitly asked for ("add a test that fails if a secret value literal ever appears in a
    captured response fixture") — it fails the moment a future change re-adds an echo field or reverts to the old
    `{ref}` shape backed by anything other than a server-minted opaque reference.

RED confirmed before implementation (`typeof first?.generation` expected `'number'`, got `'string'`;
`result.stateVersion` expected `'number'`, got `'undefined'`). GREEN after.

## Rulings — why Tasks 1, 2, 4 are not done

Before touching `getDesigner`/`stageConfig`/`acceptRuntimeConfig`/`restoreRuntimeConfig` I read every consumer in
`frontend/src/features/designer/**` to scope the change. Finding: **these four operations are one entangled
migration, not four independent repository fixes**, and `history`/`setSecret` (Task 3) were the only two operations
with no UI consumer yet — which is exactly why they were safe to fix in isolation and nothing else was.

- **`getDesigner` (Task 1).** The real `Designer` envelope is `{stateVersion, generation: number|null, viewModel,
  layout}` — the server runs `project()` once (`OR-34`) and the browser defers to it. The current
  `DesignerLoad`/`DesignerForm`/`Composer`/`FieldRow`/`SectionCard`/controls-\* pipeline is built entirely around a
  **different, older model**: `package: SolutionPackage` + `values: DraftValues` + `observed: ObservedState`, with
  the *browser* running `project()` locally (`configRepository.ts`'s own module header still cites "Q19 resolved
  — the browser projects", which **contradicts** the `console.yaml`/`OR-34` text this plan points at — an
  unreconciled conflict already flagged in the plan, not one I introduced). Swapping the wire shape means swapping
  what every rendering component consumes (`ViewModel`'s `ComponentView`/`RenderedEntry` vs `SolutionPackage`'s
  component schema) — a rewrite across the whole Composer tree, not a repository change.
- **`stageConfig` (Task 2).** The real body is `{baseStateVersion: number, componentSelection, draftValues,
  rendered}`; `SaveBar` today calls `repository.save(instanceId, {fields, generation: string, overrideReason})`,
  and `generation` is `DesignerLoad.configGeneration: string` threaded from `getDesigner`. Fixing the body shape
  requires first fixing Task 1's `string` → numeric `stateVersion`/`generation` split, and `componentSelection`
  already exists as a sibling concept (`DesignerForm`'s `ComponentSelection`) that would need reconciling with the
  wire shape's `instances[].layout` (`OR-96`). Not separable from Task 1.
- **`acceptRuntimeConfig` (Task 4, half).** The real operation (`{baseStateVersion, placementId}` →
  `AcceptedRuntimeConfig` with `notAccepted[]` path/reason entries) is a **different feature** from what
  `AcceptRuntimeDialog` implements today (`toVersion` + a client-built `renameMap` + `VersionDiff.rows`, modelling a
  schema-migration rename flow). There is no rename-mapping concept left in the ratified contract at all — the
  server does its own "accept-around" of undeclared keys. Wiring this correctly is a UI redesign of the dialog, not
  a type fix, and depends on a `placementId` that does not exist anywhere in the current data model (see next).
- **`restoreRuntimeConfig` (Task 4, half).** The real body is `{placementId: uuid, strategy}` (default `manual`,
  confirmed current against `console.yaml` — unchanged by plan 012's `scheduled`/`restart-all` additions, which
  don't touch this operation's default). `RestoreRuntimeDialog` today calls `repository.restoreRuntime(instanceId)`
  with no placement concept at all — `ObservedState`/`DesignerLoad` carry nothing named `placementId`. Threading one
  through means the same placement-model migration `acceptRuntimeConfig` needs.

**The common blocker:** a placement-model + numeric-`stateVersion` migration that plan 008 as drafted assumes is
already in place (its own body-shape quotes are already correct against `console.yaml`) but the existing
fixture-era frontend code has not undergone. Doing it safely means moving `DesignerLoad`, `DesignerForm`,
`Composer` and its children, `SaveBar`, `AcceptRuntimeDialog`, `RestoreRuntimeDialog` and `DivergedBanner` together,
in one reviewed pass — not something I could do correctly and verify inside this session without a high risk of
leaving the tree broken for the four other agents working in the same checkout concurrently. I chose the safer,
honest partial completion over a plausible-looking but unverified large diff.

**Cost if wrong (i.e., if this should have been pushed through anyway):** none of the deferred work is started, so
nothing is half-migrated or inconsistent — the next session starts from a clean, documented boundary. The cost of
*not* flagging this would have been a future agent trusting plan 008 as "closed" and building plan 009 (apply,
which plan 008 is a direct prerequisite for) on an interface that was never actually wired.

## Recommended follow-up (new plan or 008 revision)

1. Settle the `OR-34`/`configRepository.ts` module-header contradiction (server projects vs "Q19 resolved — the
   browser projects") explicitly — it's cited both ways in-tree today.
2. One plan that migrates `getDesigner` → `ViewModel` end to end (Composer render tree included), since Task 2's
   `stageConfig` and both halves of Task 4 are downstream of it.
3. Add the placement model (`placementId`) to `DesignerLoad`/`ObservedState` before `acceptRuntimeConfig` /
   `restoreRuntimeConfig` can be wired — `AcceptRuntimeDialog`'s rename-mapping UI should be reviewed by a
   designer/PM first, since the ratified contract has no equivalent to map onto.

## Task 5 — verification

- Component/unit tests: `npx vitest run src/features/designer --project unit` → **52 files, 360 tests, all pass**
  (includes the 2 new Task 3 tests plus every pre-existing designer test, confirming no regression from the type
  changes).
- `npx eslint` on every touched file → clean.
- `npm run typecheck` (whole frontend) → fails, but **not from anything in this plan's scope**: every failing file
  (`LoadPackageDialog`, `SolutionCard`, `TargetsPreview`, `healthRollup`, etc.) belongs to other agents' concurrent,
  uncommitted work on plans 004/005/006/007/010/011 in this shared checkout. Confirmed by `git stash` + re-running
  typecheck: the same errors, in the same files, exist with this plan's changes stashed out — i.e., pre-existing.
  `grep -i designer` over the typecheck output returns nothing.
- `npm run build`: not run — it would fail on the same pre-existing, out-of-scope errors, and a repo-wide build
  isn't informative while four other agents have uncommitted changes in the same tree.
- **Playwright / live browser verification: NOT performed.** No backend (`launcher`/`console-gateway`) was started
  in this session, and starting one risks colliding with the other agents' concurrent work on the same checkout.
  Task 3's two operations have no UI consumer yet (confirmed by grep — `history()`/`setSecret()` are called nowhere
  outside `configRepository.ts` and its test), so there is also no screen to browser-verify against yet; the
  consumer doesn't exist until Task 1's migration lands.

## Files changed

- `frontend/src/features/designer/configRepository.ts`
- `frontend/src/features/designer/configRepository.test.ts`
- `frontend/src/features/designer/index.ts`
- `frontend/src/shared/testdoubles/handlers/dp.ts`

No other files touched. Nothing committed (per instructions) — all changes are in the working tree.
