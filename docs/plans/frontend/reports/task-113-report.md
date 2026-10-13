# FE-113 · Solution Packages as the source of the Designer view model — Report

## What I implemented

`shared/testdoubles/data/designer.ts` has carried this promise in its header since Phase 2:

> `contracts/view-model/types.ts` (task 046) is the authoritative version; this is a local copy so
> this task's fixtures have somewhere typed to live **before that engine exists**, and **is deleted
> in favour of importing from there once it does**.

The engine landed with task 046. The 26 hand-written `VmField` literals of `CACHE_VIEW_MODEL` never
moved, because nothing had ever demonstrated they *could*. This task demonstrates it.

| File | Summary |
|---|---|
| `contracts/packages/cache/package.json` | **New.** A complete `SolutionPackage` for Cache — 26 fields, 8 sections, 1 action, 5 metrics. **Generated from the baked fixture, not transcribed** (a throwaway script read `CACHE_COMPONENT` and emitted the declarations), then hand-completed for the parts nothing can derive: the two `when:` predicates and the metric contract from `architecture.md` §5.2. |
| `contracts/packages/cache/draftValues.json` | The seven draft values the fixture bakes in as `source: 'draft'`. Not part of the package — it is `project()`'s third argument, the per-instance config-of-record. |
| `contracts/packages/db-designer/package.json` | Moved from `contracts/examples/`. One home for packages, not two. |
| `contracts/packages/README.md` | **New.** What these are, and the three contract gaps the Cache conversion surfaced. |
| `src/app/dev/toDesignerViewModel.ts` | **New.** Contract `ViewModel` → `DesignerViewModel`. Three mechanical differences: control-kind spellings (`toggle`/`list`/`map` vs `switch`/`string-list`/`key-value`), validation `tier` vs `severity`, and nested-sections vs flat-fields-with-a-section-name. |
| `src/app/dev/examplePackage.ts` | **New.** Runs `project()` over `db-designer` and serves catalog/inventory/Designer routes to the dev-server MSW worker. DEV-only. |
| `src/app/dev/cachePackageEquivalence.test.ts` | **New.** Whole-object comparison, projected Cache vs `CACHE_VIEW_MODEL`. |
| `src/shared/testdoubles/data/designer.ts` | Header only. `CACHE_VIEW_MODEL` is now marked DERIVABLE with a pointer to the package, and the reason it is not yet deleted is recorded in place rather than left for the next reader to rediscover. |

## Result

**All 26 fields and all 8 sections reproduce exactly.** The projected component deep-equals the
baked one except for two attributes of its single action.

## Rulings

1. **Generated, not transcribed.** 26 fields × ~10 attributes is precisely the input where hand-copying
   produces a subtly wrong fixture that still passes a spot check. A script read the baked objects and
   emitted declarations; only what a script cannot know — the `when:` predicates, the metrics — was
   authored. The equivalence test then compares the *whole object*, so an omission fails rather than
   silently narrowing the package.

2. **The residual difference is asserted, not normalised away.** `ActionDeclaration`
   (`dynamic-ui/design.md` §3.5) carries neither `label` nor `ui.section`, while `VmAction` requires
   both. The fixture puts `TEST_BROKER_CONNECTION` in `Broker` and calls it "Test connection"; the
   package can express neither, so the projection lands it in `General` labelled "Test broker
   connection" from its key. Normalising both away would have hidden a real contract gap behind a
   green test. Instead the test asserts the exact wrong values, so closing §3.5 turns it red and
   whoever closes it finds this file.

3. **`UI-D2` cannot express Cache's own documented condition — flagged, not resolved.** The fixture
   comments `topic-key-levels` as "hidden until key-source is TOPIC/**COMPOSITE**". That is an OR, and
   `UI-D2`'s grammar is equality-only, implicitly AND-ed. The package declares
   `when: { key-source: TOPIC }`, which is **narrower than the fixture's stated intent**. It projects
   identically here only because `key-source` is unset and both forms render invisible — no test
   catches it, and it would diverge the moment an operator picked `COMPOSITE`. Per `I3`/`I4` this is
   written down in `contracts/packages/README.md`, not silently resolved: either the grammar needs an
   OR form or Cache's schema needs restating, and both are decisions above this task.

4. **`ui.order` is written as the section's index.** `project()` derives sections as an emergent
   grouping of `ui.section` strings and takes each section's position from the `ui.order` of the first
   field it sees for that section — project.ts flags this itself as "not pinned by the design doc".
   Writing `ui.order` as the section index on every field is the only reading the engine honours;
   field order within a section is declaration order regardless.

5. **`CACHE_VIEW_MODEL` is NOT deleted, and the blocker is architectural.** `designer.ts` is `shared`,
   and `eslint.config.js`'s boundaries policy has no rule allowing `shared -> contracts` (verified with
   a probe file: *"There is no policy allowing dependencies from elements of type shared to elements of
   type contracts"*). So the MSW handler that would call `project()` cannot. Two ways forward — widen
   the policy for `src/shared/testdoubles/**`, or move the designer handler up to a layer that may
   import contracts. Changing an architectural lint rule to unblock my own change is exactly the kind
   of decision that should be made deliberately by a reviewer, so it is surfaced rather than taken.
   What this task delivers is the precondition: the literal is now provably derivable.

6. **Fixture data is organised by KIND, not by solution.** The example package's catalog card and
   declared instance first landed in a `data/dbDesigner.ts` of their own, which quietly introduced a
   second organising principle into a layer that had one: `solutions.ts` holds every catalog summary,
   `instances.ts` every instance row, `designer.ts` every view model. Merged into the first two
   accordingly and the per-solution file deleted. Not into `designer.ts`, despite that being the
   obvious-sounding merge — `db-designer` has no baked view model at all, which is the entire point
   of it. The move also removed a `DB_DESIGNER_COMPONENT` constant that restated the component type
   the package already declares; `examplePackage.ts` now reads it from `pkg.components[0].type`, so a
   rename in `package.json` cannot leave a fixture behind.

## Verification

| Check | Result |
|---|---|
| `npm run typecheck` | clean |
| `npm run lint` (`--max-warnings=0`) | clean |
| Equivalence test | 5/5 — all fields, all sections, whole component modulo the two asserted action gaps |
| `db-designer` projection test | 10/10 |
| `npm run build` | clean; no `db-designer`, `examplePackage` or `msw` in any emitted chunk |

## Follow-ups

- **Delete `CACHE_VIEW_MODEL`** once the boundary question above is settled. `FILE_MESH_VIEW_MODEL`
  needs the same treatment first — it is ~100 generated fields across 2 components, so its package
  is a bigger generation job than Cache's, and `toDesignerViewModel()` currently handles the
  single-component case only.
- **Close `§3.5`** — give `ActionDeclaration` a `label` and a `ui.section`.
- **`§16 Q19`** — whether the server sends the package for the browser to project, or a pre-projected
  view model, is still open. This task makes the first option concrete for the first time.
