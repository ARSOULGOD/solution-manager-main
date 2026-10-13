# FE-122 · The Designer read carries the package, not a projection (resolves §16 Q19) — Report

## What this closes

§16 Q19 was open: does the server return a package + values for the browser to `project()` itself, or a
pre-projected view model? The fixture layer had assumed the second since Phase 2, purely because `project()`
didn't exist yet — `configRepository.ts`'s own header said so. Every Phase 4 component built since (task 046
onward) already takes contract types (`ViewSection`, `SolutionPackage`, `DraftValues`), never the fixture's
`DesignerViewModel` — the pre-projected shape had exactly one consumer left: the MSW handler. This task
resolves Q19 for the frontend (**FE-D33**) and finishes the deletion `FE-113` left open, blocked only on the
`shared → contracts` boundary policy.

## What was built

**`DesignerLoad` becomes `{ package, packageVersion, values, observed, configGeneration }`**
(`configRepository.ts`). The browser now projects; the wire *encoding* of the response stays the backend
sub-spec's to write.

**Boundaries policy widened: `shared → contracts` (`FE-D34`)** — `eslint.config.js` gains one policy entry,
mirroring `app → contracts` / `feature → contracts`. `contracts/` is a leaf (imports nothing, not React, not
`@shared/*`), so this creates no cycle. `shared → feature` stays refused — proved by the pre-existing
`src/test/lint-fixtures/shared/imports-feature.ts` (task 009), re-verified with `npm run lint:fixtures:assert`
after the widening, still failing on the intended rule.

**`src/shared/testdoubles/data/packages.ts` (new)** — the one place mapping a solution id to its
`SolutionPackage` (`packageForSolution`, keyed on `InstanceRow.solutionId`, never an id-prefix heuristic —
replacing the old `instanceId.startsWith('filemesh')` guess) and an instance to its `ObservedState`
(`observedStateFor`) and configuration-of-record (`draftValuesFor`). Both `handlers/dp.ts` (default fixture
set) and `app/dev/devPackageHandlers.ts` (dev-loaded packages) now read this — one version-resolution rule,
not two.

**`contracts/packages/file-mesh/package.json` (new)** — the multi-component companion to Cache (two
`one-of-many` components, source and sink), so the Composer canvas has a real package to render. Derived by
a script, not transcribed, from the machine-generated `FILE_MESH_SOURCE`/`FILE_MESH_SINK` fixtures (~103
fields) — the same method `cache/package.json` was authored by, scaled up with a script because the field
count made hand-transcription unreliable. The script wrote the package file, ran a temporary whole-object
equivalence test through `project()` against the two fixtures, and was deleted once all four assertions
passed. One real defect surfaced and was fixed rather than reproduced: the fixture's bare key `ENABLED`
repeated across three sections (source) / two sections (sink) — harmless under the fixture's own three-level
`uid`, but a genuine values-map collision once `key` alone is the field's identity. Disambiguated by
prefixing the repeated key with its section slug, matching the dotted style the fixture's own `GENERAL`
section already used. Documented in `contracts/packages/README.md`'s new "The invented file-mesh" section.

**`src/shared/testdoubles/data/designer.ts` trimmed to observation fixtures only** — `ReplicaObservationFixture`,
`ROLLOUT_REPLICAS`, `DIVERGED_REPLICAS`, `SECRET_MASK_TOKEN`, `DiffRowFixture`, `DIFF_DRAFT`,
`CONFLICT_THEIRS`, `RestartStrategyFixture`, `RESTART_STRATEGIES` stay — `RolloutCard`, `ReplicaTable`,
`ConflictDialog` and `StrategyPicker` depend on them and they duplicate no view model. Everything else —
`VmField`/`VmSection`/`VmAction`/`VmComponent`, `CACHE_SECTIONS`/`CACHE_FIELDS`/`CACHE_ACTIONS`/
`CACHE_COMPONENT`, `FILE_MESH_SOURCE`/`FILE_MESH_SINK`, `DesignerViewModel`, `CACHE_VIEW_MODEL`,
`FILE_MESH_VIEW_MODEL` — deleted (1029 lines → ~140).

**Deleted whole:** `app/dev/toDesignerViewModel.ts`, `app/dev/viewModelFor.ts`, `app/dev/viewModelFor.test.ts`,
`app/dev/cachePackageEquivalence.test.ts`. The dev-mode Designer handler no longer projects
server-side at all — it serves the raw package and observed state, exactly like the default handler.

**`InstanceRow.packageVersion`** now imports `PackageVersionId` from `@contracts/view-model/types` directly
— the "not importable from `shared`" comment explaining the prior hand-synced `string | number` restatement
is deleted, not left to mislead.

## A parse bug the boundary widening did not cause, but the rewrite did

`designer.ts`'s new module-doc comment originally read `` `contracts/packages/*/package.json` `` — the glob's
own `*/` closed the `/** */` block comment early, turning the rest of the file into malformed source and
cascading into ~150 unrelated "unsafe" lint errors across every file that (transitively) imports from it
(`RestartConfirm`, `RolloutCard`, `StrategyPicker`, `applyRepository`, …). Caught by `npm run lint`, not by
`typecheck` or tests — a reminder that a lint pass is part of "green," not a redundant check.

## Testing

- `src/features/designer/configRepository.test.ts` — `loadDesigner()` returns `package.solution`,
  `packageVersion`, `configGeneration`.
- `src/features/designer/ConflictDialog/ConflictDialog.test.tsx` — `LOAD_RESULT` rebuilt to the new shape
  (a minimal real `SolutionPackage`, not a `DesignerViewModel` literal); the dialog itself never dereferences
  `DesignerLoad`'s internals, so no behavioural change.
- `src/features/designer/StrategyPicker/{StrategyPicker,StrategyPicker.a11y}.test.tsx` — the three Cache
  rollout-gating facts (`requiresUniformConfig`, `healthMappingDeclared`, `healthGateUnsafeReason` — MI-specific,
  no home in `SolutionPackage`/`ViewModel`) restated as a local `CACHE_ROLLOUT_GATING` constant instead of
  read off the now-deleted `CACHE_VIEW_MODEL`.
- `src/app/dev/devPackageFlow.test.ts` — the real-repository/real-handler integration suite rewritten to
  assert the raw `DesignerLoad` (package, packageVersion, values) rather than a pre-projected view model,
  and to run `project()` once itself where the old test needed the projection to make its point (declared
  version resolves through the solution; a v-scoped field excluded/included at the declared version).

## Files

| File | Change |
|---|---|
| `eslint.config.js` | `shared → contracts` policy added. |
| `contracts/packages/file-mesh/package.json` | New — derived, ~103 fields, 2 components. |
| `contracts/packages/README.md` | `file-mesh/` row, "The invented file-mesh" section, cache section updated for the deleted equivalence test and fixture. |
| `src/features/designer/configRepository.ts` | `DesignerLoad` shape changed; module doc rewritten for Q19 resolved. |
| `src/features/designer/configRepository.test.ts`, `ConflictDialog/ConflictDialog.test.tsx` | Updated to the new `DesignerLoad` shape. |
| `src/features/designer/StrategyPicker/{StrategyPicker,StrategyPicker.a11y}.test.tsx` | `CACHE_VIEW_MODEL` reads replaced with a local constant. |
| `src/shared/testdoubles/data/designer.ts` | Trimmed to observation fixtures only. |
| `src/shared/testdoubles/data/packages.ts` | New — `packageForSolution`, `observedStateFor`, `draftValuesFor`. |
| `src/shared/testdoubles/data/instances.ts` | `packageVersion: PackageVersionId`, imported directly. |
| `src/shared/testdoubles/handlers/dp.ts` | Designer handler serves `{package, packageVersion, values, observed, configGeneration}`. |
| `src/app/dev/devPackageHandlers.ts` | Designer handler no longer projects; serves the raw package + observed state. |
| `src/app/dev/devPackageFlow.test.ts` | Rewritten for the new `DesignerLoad` shape. |
| `src/app/dev/parsePackage.ts` | Stale `viewModelFor.ts` reference removed from the module doc. |
| *Deleted* | `app/dev/{toDesignerViewModel,viewModelFor,viewModelFor.test,cachePackageEquivalence}.ts` |

## Acceptance

- ☑ `DesignerViewModel` and both baked view models gone
- ☑ observation fixtures retained
- ☑ `shared → feature` still refused (`lint:fixtures:assert` re-verified)
- ☑ dev-loaded and fixture packages take the same code path (`DesignerLoad` shape, `packages.ts` shared by both handlers)

## Verification

`npm run lint` · `npm run typecheck` · `npm test` (1280 tests) · `npm run build` — all green.
`npm run lint:fixtures:assert` — 10/10 fixtures still fail on their intended rule.

## Also completes FE-113

`FE-113`'s own report named one blocker for deleting `CACHE_VIEW_MODEL`: `shared/testdoubles` could not call
`project()` because the boundaries policy had no `shared → contracts` rule. That policy is added here, and
the deletion it was blocking is done here too — `FE-113`'s status moves to done alongside this task's.
