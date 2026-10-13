# FE-118 · A package declares several versions; an instance is declared against one — Report

## What this closes

A Solution Package declared exactly one `packageVersion`, as a scalar. Real packages evolve: one file has to
serve several schema versions, and an instance has to say which one it is configured against — otherwise the
Designer renders fields that do not exist for that instance.

There was **no version dimension anywhere**: `SolutionPackage.packageVersion` was a number, no component or
field could declare which versions it belonged to, `project()` had nothing to filter on, and `InstanceRow`
carried no package version at all.

## What was built

A version dimension that runs end to end — contract, engine, package files, loader, wire shape, form, grid
and Designer projection — and which **changes nothing for a caller that does not use it**.

### The contract

- `PackageVersionId = number | string`. A number for a curated, monotonically numbered package (`6`, `7`); a
  string for a build that is not numerically ordered (`'dev-2026.08.17'`). No ordering is implied by the type
  — `packageVersion` declares the order.
- `SolutionPackage.packageVersion: readonly PackageVersionId[]` — every version the one file serves,
  ascending, non-empty.
- `ComponentDeclaration.version` and `FieldDeclaration.version`, both **optional**.

**Absent ≠ empty, and the distinction is load-bearing.** Absent means "every version the package supports" —
which is what keeps a third-party package that has never heard of versioning valid, and what kept all 19
existing fixtures green. An empty array belongs to *no* version and never renders; `parsePackage.ts` rejects
it rather than letting a field disappear quietly.

### The engine

`project()` takes an optional **fifth** argument, the version to project at, plus an exported
`inVersion(declared, selected)` that is `true` when either side is `undefined`.

⚠️ **The one ordering detail that matters.** Fields are filtered at the top of `projectComponent`, **before
`applyDefaults` runs**. A field excluded by version must not contribute its `default` to the values map
obligation 1's `when:` evaluator reads — filtering later would leave a v7-only field silently steering the
visibility of a v6 field that names it. The code says so at the filter, and
`version-scoped-field-contributes-no-default.json` proves it.

Components are skipped whole — no sections, no fields, no actions — and **also excluded from `graphs[]`**,
which the plan did not name explicitly: a component the Designer refuses to render must not chart metrics on
the Monitoring tab either.

### The two shipped packages

`cache/package.json` becomes `"packageVersion": [6, 7]`, with `"version"` written explicitly on the component
and every one of its 26 fields. Three fields in the Expiry / eviction area are marked `"version": [7]`:
`expiry-header-key-pointer`, `expiry-header-format`, `max-weight`.

⚠️ **v6 is authored demo history, not derived from the Cache MI.** Stated plainly under a *"The invented v6"*
heading in `contracts/packages/README.md`, with the field keys named and the reasoning for each. It exists so
the version selector, the grid column and the engine's filter are exercised by something an operator can see
rather than only by test fixtures.

`db-designer/package.json` gets `[1]` and `"version": [1]` throughout — the array form even at one version,
because that is the form a package author should copy.

### The instance carries its version

`InstanceRow.packageVersion` is **required**, restated locally with a comment pointing at `PackageVersionId`
(`shared/**` may not import `contracts/**` — the same hand-sync posture `types.ts` already takes for
`HealthKey`). All twelve `CACHE_INSTANCES` carry a mix of 6 and 7, so the new grid column has something to
sort.

`DeclareInstanceInput.packageVersion` is required too, and **it belongs in the validated input**. Unlike
health, config state or `version.observed`, this is IDENTITY: nothing observes it, the operator chooses it,
and the Designer cannot render the right field set without it.

### The form

`initialDeclareForm` preselects at exactly one version and leaves `''` at several. **No silent default to
"latest"** — a package version defaulted invisibly is the exact class of bug this task exists to remove.

`validateDeclareForm` reports two *distinct* failures: empty is "you have not chosen"; a non-member is "this
package does not serve that version", reachable when the catalog reloads under an open dialog. Collapsing
them would tell an operator staring at a filled-in select that it is empty.

`toDeclareInput` maps the select's string back to the id the package declared, so `6` goes over the wire as
the number `6`.

`InstancesPage` passes `solution?.packageVersion ?? []` and **gates the Declare action** on
`solutionsQuery.isPending`, wrapped in a `Tooltip` stating why — you cannot declare against a version list you
cannot enumerate. The catalog query stays non-blocking for the *table*, which is the primary content.

### The Designer

`viewModelFor.ts` passes `instance.packageVersion` as `project()`'s fifth argument and sets
`DesignerViewModel.packageVersion` to the **instance's** version, not the package's array.

## Files

| File | Summary |
|---|---|
| `contracts/view-model/types.ts` | `PackageVersionId`; `packageVersion` as an array; `version` on component and field. |
| `contracts/view-model/project.ts` | Fifth argument; exported `inVersion`; field filter before `applyDefaults`; component filter in the main loop and in `projectGraphs`. |
| `contracts/packages/cache/package.json` | `[6, 7]`; `version` on every declaration; three v7-only fields. |
| `contracts/packages/db-designer/package.json` | `[1]`, `version: [1]` throughout — the authoring reference. |
| `contracts/packages/README.md` | **"The invented v6"** — what it is, which fields, and that it is authored. |
| `contracts/fixtures/view-model/*.json` | 19 fixtures to the array form; three new `version-scoped-*` cases. |
| `contracts/fixtures/view-model/run.test.ts` · `fixtures/README.md` | Optional `packageVersion` threaded as the fifth argument; documented shape. |
| `src/app/dev/parsePackage.ts` | `packageVersion` must be a non-empty array; `version` must be non-empty and every member must be one the package declares. |
| `src/shared/testdoubles/data/instances.ts` | `InstanceRow.packageVersion`, required; twelve fixtures mixing 6 and 7. |
| `src/shared/api/contracts/instances.ts` · `data/declaredInstance.ts` | Required on the wire; validated; copied onto the row. |
| `src/app/dev/derivePackageCatalog.ts` | `derivePackageInstance()` uses `packageVersion.at(-1)`; the array passes through to the card. |
| `src/shared/testdoubles/data/solutions.ts` · `SolutionCard.tsx` | Summary carries the array; the card joins it with ` · `. |
| `src/features/inventory/instances/declareForm.ts` · `DeclareInstanceDialog.tsx` · `InstancesPage.tsx` | Version state, two-way validation, the `Select`, the prop, the gated action. |
| `src/app/dev/viewModelFor.ts` | Fifth argument = the instance's version; scalar `packageVersion` on the view model. |
| `src/shared/i18n/messages.en.ts` | Five new keys; `instancesDeclareVersionHelp` sharpened. |

## Rulings

1. **Three fixtures, not two.** The plan named `version-scoped-field-excluded` and
   `version-scoped-component-excluded`. A third — `version-scoped-field-contributes-no-default` — was added
   because the fixture suite is `project()`'s *only* test (there is no `project.test.ts`), and the
   before-`applyDefaults` ordering is the plan's own "one detail that matters". Leaving it unproven would have
   left the rule asserted in a comment and nowhere else. One fixture still proves one thing.
2. **19 fixtures, not 20.** The plan says 20; the directory holds 19 `.json` files plus `run.test.ts`. All 19
   were converted.
3. **`graphs[]` filtered too.** Not named in the plan. A component excluded by version contributes no
   sections; letting it contribute metrics would put a chart on Monitoring for a component the Designer
   refuses to render.
4. **Two "version" fields on one form, said out loud.** *Package version* = the configuration schema this
   instance is configured against; *Declared version* = the MI build running. Both helper texts now name the
   other, because two fields called "version" on one form need the difference stated rather than inferred.
5. **`packageVersion` is validated input, not derived state.** `readDeclareInput` checks it alongside the
   name. It does **not** check membership of the package's version list — that module has no package to check
   against; the form does it where it can enumerate them.
6. **`cachePackageEquivalence` now projects at v7 explicitly.** It relied on "no version selected means no
   filtering". Passing `7` states the claim instead of inheriting it, and a new case asserts v6 is v7 minus
   exactly the three README-named fields — so editing a `"version": [7]` marking in the shipped package file
   turns a test red.

## Verification

`npm run lint` · `npm run typecheck` · `npm test` · `npm run build` — all green. **228 files / 1295 tests**,
up from a clean-tree baseline of **227 / 1257** measured before any change (no axe timeout on either run).

Driven in a browser at `npm run dev`, zero console errors:

- Solutions → Cache shows `6 · 7`.
- Declaring: the Package version select is populated from the package (`["6", "7"]`); submitting without a
  choice raises the required error; the new row shows `6`; it survives a reload still reading `6`.

⚠️ **The Designer check in the plan could not be performed in a browser.** The Designer *screen* is still a
Phase 4 placeholder — the route renders `instanceDesignerPlaceholder` ("Designer for this instance is coming
in a later phase"), so no instance's field set is on screen yet, at any version. The behaviour is proven one
layer down instead, through the real repository and the real dev MSW handlers:
`devPackageFlow.test.ts` declares two instances against a two-version package and asserts the v2-only field is
absent at v1, present at v2, and that `viewModel.packageVersion` is the **instance's** version in each case.

## Found, not fixed (out of scope)

Loading a package whose `solution` id matches a baked catalog card (e.g. `cache/package.json`) produces **two
cards with the same id** — `allSolutions()` concatenates `SOLUTIONS` and the dev store without de-duplicating —
and React logs `Encountered two children with the same key`. Pre-existing in the dev loader (FE-114), not
introduced here, and unrelated to versioning. Recorded rather than fixed.
