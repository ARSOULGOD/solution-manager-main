# Plan 008, Tasks 1 & 2 — report

**Date:** 2026-10-09
**Scope:** `getDesigner` (Task 1) and `stageConfig` (Task 2) only. Task 3 (history/secrets), Task 4
(accept/restore runtime — already done 2026-10-09, untouched) and Task 5 (Playwright) are out of
scope for this pass.

## What changed

### `frontend/src/features/designer/configRepository.ts`
- `loadDesigner()` now calls the real `getDesigner` (`{stateVersion, generation, viewModel,
  layout}`), reverse-maps the wire `ViewModel` into this engine's own `DraftValues`/`ObservedState`
  (`mapWireDesignerToLocal`, exported and unit-tested standalone), and fetches the raw
  `SolutionPackage` via an **injected** `GetSolutionPackage` function — `createConfigRepository`
  now takes a second parameter rather than this module importing `solutionsRepository` directly
  (cross-feature imports are restricted to a feature's public barrel by
  `eslint-plugin-boundaries`; dependency injection avoids the question entirely).
- `save()` / `SaveConfigBody` / `SaveConfigResult` now match the real `stageConfig` contract:
  `{baseStateVersion, componentSelection, draftValues, rendered}` in,
  `{stateVersion, generation, unchanged}` out.
- Added `RenderedEntry`, `GetSolutionPackage`, and `DesignerLoad.unsupportedFields`.
- `solutionsRepository.ts`'s `getPackage(solutionId, packageVersion?, signal?)` — `packageVersion`
  is now optional (console.yaml's own rule: omitted = the entry's latest), needed because
  `getDesigner`'s `viewModel.packageVersion` is nullable (no generation yet).

### `frontend/src/features/designer/form/useDesignerDraft.ts`
- Added `rendered: readonly RenderedEntry[]` to `DesignerDraft`, computed by a new
  `renderedEntriesFor` — a per-component-instance **scoped** `project()` pass, because this
  engine's own `ViewModel.sections` is flat (no per-instance grouping) and has no `renderedPath`
  list to read globally. Unlike `payload` (which keeps a `$from` reference for a connection-bound
  field), `rendered` carries the field's current resolved literal — what the server's own
  `stageConfig` recomputation actually checks against.

### `frontend/src/features/designer/SaveBar/{SaveBar.types.ts,SaveBar.tsx}`
- `generation: string` replaced by `baseStateVersion: number`, `componentSelection`, `rendered`.
- The mutation now builds the real `stageConfig` body.
- **Discovered contract gap:** `stageConfig`'s schema is `additionalProperties: false` with no
  `overrideReason` field. The override-reason dialog (`UI-D16`) still runs and still gates the
  click; the confirmed reason is accepted by the mutation function and discarded, never
  transmitted — there is nowhere on the wire to put it.

### `frontend/src/features/designer/DesignerPage/DesignerForm.tsx`
- Both `SaveBar` call sites updated to the new props.
- `handleSaved` now also updates `load.stateVersion` from the save result.
- Added a distinct `422` handling path (`staleProjectionOpen`), separate from the existing `409`
  `ConflictDialog` flow — an inline `Alert` with a "Reload" action that calls `onRefetch()`, per
  the plan's explicit instruction not to fold `422` into a generic save error.

### `frontend/src/features/designer/index.ts`
- Exported `GetSolutionPackage` and `RenderedEntry`.

### `frontend/src/features/inventory/solutions/solutionsRepository.ts`
- `getPackage`'s `packageVersion` parameter made optional (additive).

### `frontend/src/app/routes/InstanceCategoryRoute.tsx`
- Builds a `createSolutionsRepository(createCpClient(...))` and passes `.getPackage.bind(...)`
  into `createConfigRepository`.

### `frontend/src/features/instance/InstanceShell.{test,a11y.test}.tsx`, `frontend/src/app/dev/devPackageFlow.test.ts`
- Updated `createConfigRepository(...)` call sites for the new signature (compile-only fix for
  `devPackageFlow.test.ts` — see "Known regression" below).

### `frontend/src/shared/testdoubles/data/wireViewModel.ts` (new)
- `buildWireDesigner()` — builds a real console.yaml `Designer` envelope out of the same fixture
  inputs (`packageForSolution`/`draftValuesFor`/`observedStateFor`) and the same local `project()`
  engine every other fixture path already runs, scoped per component instance. This is the
  forward direction (local → wire); `mapWireDesignerToLocal` is the reverse. Building the MSW
  fixture this way (rather than hand-authoring wire JSON) is what makes a round trip through
  `loadDesigner()` meaningful to test.

### `frontend/src/shared/testdoubles/handlers/dp.ts`
- `/designer` GET now returns `buildWireDesigner(...)`'s real envelope.
- `/config` POST (stageConfig) now validates `baseStateVersion` against a tracked `stateVersion`
  (not the old string `generation`), returns `{stateVersion, generation, unchanged}`, and 409s on
  a stale `baseStateVersion`.
- Removed the now-dead `connectionCatalog`/`appliedConnectionRevisions` fabrication from this
  route (no wire counterpart — see below) and the now-unused `toCatalogEntries`/`getConnectionRows`
  imports.

### `frontend/src/shared/i18n/messages.en.ts`
- Added `designerStaleProjectionTitle`/`Detail`/`Reload` for the new `422` state.

### `docs/plans/integration/008-designer-and-config.md`
- Tasks 1 and 2 checked off with what was built and the flagged gaps; the old "still fixture-era"
  section replaced with a pointer to this report.

## Mapping decisions (the substance of Task 1)

The wire `ViewModel` (console.yaml: `components[].sections[].fields[]`, wrapped
`{kind,value,source}` field values) and this codebase's own `project()`-OUTPUT `ViewModel`
(`frontend/contracts/view-model/types.ts`: flat `sections[]`/`graphs[]`, raw-literal
`DraftValues`, a flat `ObservedState`) are genuinely different shapes — **not** the
`contracts/view-models/` top-level package I initially (and incorrectly) assumed governed
frontend compilation. That package exists but is a *different*, unconsumed copy; the alias
`@contracts/view-model/types` resolves to `frontend/contracts/view-model/types.ts`, which is the
one `useDesignerDraft`/`Composer`/`DesignerForm` actually import. Confirming this took a detour
through `contracts/view-models/dist/types.d.ts` before I found the real one — worth flagging as a
repo-wide trap for the next person who assumes "the" view-model package is the top-level one.

- **Load (`mapWireDesignerToLocal`):** for each wire field, `kind: 'value', source: 'draft'` maps
  to a literal in `DraftValues`; everything else (`source: 'default'`, `unset`, `secretRef`,
  `secretUnset`) is omitted, so the local `project()` re-derives it from the package's own
  defaults/bindings rather than this client guessing. **I6**: no secret arm ever contributes a
  literal — enforced in code (`draftLiteralOf`), tested (`configRepository.test.ts`'s "no secret
  literal ever reaches DraftValues" test).
- **`observedState.health`/`versionState`**: direct enum remaps (wire's SCREAMING_CASE to this
  engine's lowercase vocabulary).
- **`observedState.configState` — APPROXIMATED, not ratified.** `SM-D22`/architecture.md §6.1
  carries five tiers (`desired`/`rendered`/`persisted`/`restartObserved`/`effectiveVerified`)
  separately and explicitly never collapses them; this engine's own `ConfigState` is one
  closed-vocabulary member. No ratified mapping between the two exists anywhere in the design
  docs I checked. I picked the one rule the actual call sites (`isEditingBlocked`,
  `DivergedBanner`) care about: `'na'` when nothing is persisted yet, `'diverged'` when the
  restart-observed tier disagrees with what's persisted, `'in_sync'` otherwise — collapsing
  `'staged'`/`'rendered'`/`'drift'` into `'in_sync'`. Flagged in `DesignerLoad.unsupportedFields`.
- **`observed.restartObservedValues` — genuinely nowhere to go.** `UI-D7`'s per-field
  restart-observed snapshot never crosses the wire; `StatusView` only carries the rolled-up
  five-tier string state. A virtual field whose visibility depends on it will render from the
  draft/default pass only. Also flagged in `unsupportedFields`.
- **`connectionCatalog`/`appliedConnectionRevisions` — no wire counterpart at all.**
  `UI-D27`/`UI-D28`/`UI-D29` (Connection Objects) are not reflected in console.yaml's `ViewModel`
  schema. These fields are always `undefined` against the real backend now (the fixture-era route
  fabricated them; that fabrication is removed from the default MSW handler).
- **`componentTypes`** is now read directly off the wire's own `components[].type` list — it is
  no longer a separately-authored "binding" concept, it's literally what the server already
  decided to project.

## `SaveBar` decision (the substance of Task 2)

I expanded `SaveBarProps` directly (`baseStateVersion`, `componentSelection`, `rendered`) rather
than having `DesignerForm` pre-assemble the whole `SaveConfigBody` and pass one opaque object,
because `SaveBar` already owns the mutation and the override-confirmation flow — splitting "what
to send" from "how/when to send it" would have meant threading the override-reason gate back out
to `DesignerForm` too. The smaller blast radius was adding three focused props to the component
that already does the save, not moving the save.

`rendered[]` is sourced from `useDesignerDraft`'s new field rather than recomputed in `SaveBar` or
`DesignerForm`, per `FE-D17` ("the save/diff/hash payload, emitted, never re-derived by
consumers") — even though, as detailed above, getting it required a new scoped-projection helper
inside `useDesignerDraft.ts` rather than reading an existing exposed field, since none existed.

## Known regression (explicitly out of scope, not silently broken)

`frontend/src/app/dev/devPackageFlow.test.ts` — 35 of its 50 tests now fail. Its own
`devPackageHandlers.ts` MSW override for `/dp/instances/:id/designer` still returns the OLD
fixture shape (`{package, values, observed, configGeneration, ...}`), because a dev-session
package drop has no real backend to run `getDesigner`'s projection against. `configRepository.ts`
now expects the real wire envelope unconditionally, so every call through that override throws.
Rewriting `devPackageHandlers.ts` to the same wire shape (presumably via the same
`buildWireDesigner` helper) is real follow-up work — it is dev-tooling, not named in plan 008's
Tasks 1/2, and not part of the `designer` feature's own test suite. I did not attempt it given the
scope boundary in my instructions; fixing the compile errors (the `createConfigRepository` call
site) was the minimum needed so the rest of the frontend suite still typechecks.

Some of the pre-existing failures in that same file (`DeclareInstanceInput` shape mismatches,
`Instance.items`/`.total` property errors) are unrelated to this task — they were already broken
before I touched anything, from earlier plan work (`DeclareInstanceInput` gained required fields
this test file's literals don't supply). I did not touch those.

## Validation

```
$ npx tsc -p tsconfig.json
# clean, except: (a) pre-existing devPackageFlow.test.ts errors unrelated to this change
# (DeclareInstanceInput shape mismatches, Instance.items/.total), and (b) the devPackageFlow.test.ts
# createConfigRepository call site, fixed to the new signature (still runtime-fails, see above).

$ npx vitest run src/features/designer
 Test Files  79 passed (79)
      Tests  444 passed (444)

$ npx vitest run src/features/instance src/features/governance src/app/routes
 Test Files  14 passed (14)
      Tests  112 passed (112)

$ npx vitest run src/app/dev/devPackageFlow.test.ts
 Test Files  1 failed (1)
      Tests  35 failed | 15 passed (50)
 # Expected — see "Known regression" above.
```

I6 verification: `configRepository.test.ts` has an explicit test ("I6: no secret literal ever
reaches DraftValues, under any key") asserting `JSON.stringify(mapped.draftValues)` never matches
`/password/i` against a fixture whose wire field carries `secretUnset`; the pre-existing
`setConfigSecret` test (kept, now passing against the new numeric-generation fixture state)
asserts the literal secret value never appears in that response either. No secret value literal
appears anywhere in the fixtures or mapped objects this task added.

## Remaining limitations (stated plainly, not fixed)

1. `devPackageFlow.test.ts` / `devPackageHandlers.ts` — see above.
2. `mapWireDesignerToLocal`'s wire-shape interfaces are hand-transcribed from `console.yaml`, no
   codegen (same gap the module already had, now narrower in scope).
3. `observed.configState`'s approximation and the complete absence of
   `observed.restartObservedValues` are real, documented behavior differences from a perfectly
   faithful round trip — not fixable without either a ratified SM-D22↔local-ConfigState mapping
   or a wire schema change exposing the raw per-field restart-observed snapshot. Both are now
   named in `DesignerLoad.unsupportedFields` at runtime, not just in comments.
4. No layout-canvas UI exists yet, so every save through `SaveBar` omits `layout` for every
   instance — per `stageConfig`'s own description, this clears any previously-saved per-instance
   layout rather than leaving it alone. Flagged, not designed in this pass (same posture Task 4's
   placement-selector gap already took).
5. `overrideReason` (`UI-D16`) has no field in the real `stageConfig` request schema — confirmed
   but never transmitted.
