# FE-114 · Load a Solution Package from a file at runtime (dev) — Report

## What this closes

FE-113 proved `REQ-001`'s acceptance gate — "onboard a solution from a package file alone, with
zero commits to SM" — for **one** package, `contracts/packages/db-designer/package.json`, baked
into the tree. That is a strong demonstration with one weakness: the package still arrived by
being committed. This task removes that: a package file dropped on the Solutions catalog at
runtime produces a catalog card, an instance, and a Designer view model, through the same
`project()` engine, with no code naming it.

The dropped package's bytes are read **in the browser and nowhere else**.
`solutionsRepository.load()`'s wire contract is untouched — it still posts `{ fileName, fileSize }`
— because the real upload format is `§16 Q10` TBD and inventing one would assert a contract nobody
has ratified.

## Files

| File | Summary |
|---|---|
| `src/app/dev/parsePackage.ts` | **New.** File → `LoadedPackage`, or a stated reason. Four rejection codes: `zip-unsupported`, `sompkg-unsupported`, `malformed-json`, `invalid-package`. Validates **only** the fields the existing flow dereferences (Ruling 2). |
| `src/app/dev/derivePackageCatalog.ts` | **New.** The console-side half a package deliberately does not carry — card colour, glyph, descriptor, name, telemetry line, and the one synthesized instance. Every derivation is a stated rule with a test. |
| `src/app/dev/devPackageStore.ts` | **New.** The session's loaded packages, keyed by `pkg.solution`, in `sessionStorage`, revalidated on rehydration. Holds the pending-package slot (Ruling 3). |
| `src/app/dev/devSolutionsRepository.ts` | **New.** `withDevPackages(repository)` — parses the dropped file, holds it pending, delegates the metadata call untouched. |
| `src/app/dev/viewModelFor.ts` | **New.** `viewModelFor(pkg, instance)` — the generalisation of FE-113's `dbDesignerViewModel()`. One implementation, used by both the example package and every dropped one. |
| `src/app/dev/testPackage.ts` | **New.** A small valid package for the loader's own tests. |
| `src/app/dev/examplePackage.ts` | Handlers now read the store **at request time**; `dbDesignerViewModel()` is `viewModelFor(pkg, DB_DESIGNER_INSTANCE)`. |
| `src/app/main.tsx` | Forces the session's stored packages to be read and revalidated before mount. |
| `src/app/routes/SolutionsRoute.tsx` | Installs the dev decorator behind a literal `import.meta.env.DEV` branch. |
| `src/features/inventory/solutions/solutionsRepository.ts` | `commitPending?()` / `discardPending?()` — both **optional**, both **absent** from the real repository. |
| `src/features/inventory/solutions/LoadPackageDialog.tsx` | Announces confirm / cancel / close / "Choose another" to the repository. |
| 5 new test files | 79 cases — see Verification. |

## Rulings

1. **The Designer SCREEN does not exist yet, so the click-through is asserted at the repository
   boundary.** `InstanceShell` (task 044) still renders `t('instanceDesignerPlaceholder')` for the
   `designer` category. Tasks 046–063 and 108 built every Designer *component* — `SectionCard`,
   `FieldRow`, `SaveBar`, the control registry — but no task has assembled them into a route body,
   and doing so here would be that separate task, not this one. So the acceptance flow is verified
   in two halves: `devPackageScreens.test.tsx` takes the catalog card through Open to
   `/solutions/widget-mesh/instances`, and `devPackageFlow.test.ts` asserts that
   `configRepository.loadDesigner()` returns a view model whose sections, fields, control kinds and
   options are all package declarations. **This is a real gap in the acceptance flow as specified
   (guardrail 17's last step), and it is a pre-existing one — flagged, not worked around.**

2. **Validation is scoped to what is dereferenced, and no schema library was added.** The checks
   are `solution`, `packageVersion`, `appliesTo`, `provenance.author`, the `authoring` counts, and
   per component `type` / `configPath` / `fields[].key` / `fields[].type`, plus `actions[].kind`
   and `metrics[].source.kind`. Those last three are checked *because* they index a closed-union
   `Record` in `project()` or the catalog — an unlisted member renders a control that does not
   exist. `cardinality`, `role` and `loggerPrefix` are contract fields nothing in this flow reads,
   so a package omitting them is admitted: this is a dev-mode loader, not a conformance gate. The
   authoritative package schema is `dynamic-ui/design.md` §3 and is not published as JSON Schema
   (`§16 Q10`); a hand-written schema here would be a second, drifting statement of it.

3. **Pending, then committed — and the confirm edge needed a second optional method.** Parsing has
   to happen when the operator *picks* the file (the only moment the bytes exist), but a picked
   file is not a loaded package. `holdPending()` parks it, and only the dialog's **confirm** — the
   same edge that fires `onLoaded` — registers it. The design named `discardPending?()` only.
   Registering "from `onLoaded`" is not reachable the way it reads: `SolutionsPage.handleLoaded` is
   feature-layer code, and the boundaries policy forbids `feature -> app`, so it cannot call into
   `app/dev`. **Deviation, flagged:** a symmetric `commitPending?(): void` was added beside it,
   called from `LoadPackageDialog.handleLoad` immediately before `onLoaded(result)`. Both are
   optional and both are absent from the real repository, which holds nothing between calls.

4. **A parse rejection is an `ApiError`, not a fake "unsigned" verdict.** The dialog's rejected
   branch is copy about *signatures* (`solutionsLoadRejected: 'Unsigned — not admitted'`).
   Returning `verified: false` for a file that is not a package would put the wrong sentence on
   screen. Instead the decorator throws `ApiError(422, code)` with the structured reason, which
   lands in the dialog's existing inline-error branch — the one place that already renders an
   `ApiError`'s own message — and keeps Load disabled. No new UI, and "this is not a package" and
   "this package is not signed" stay different answers.

5. **An unsigned package is admitted to the catalog but is not navigable — existing behaviour, and
   the acceptance flow works around it rather than changing it.** `devMode` is derived exactly as
   specified: `true` when `provenance.signature` is absent (an empty string counts as absent).
   `SolutionCard` then disables Open for a `devMode` card, per `UI-D19`. So **a package with no
   signature cannot be opened**, and the end-to-end flow must be exercised with a package that
   declares one. That tension is `UI-D19`'s, not this task's; it is asserted in
   `devPackageScreens.test.tsx` so it is visible rather than discovered. If the intent is that a
   dev-loaded package should be openable, `SolutionCard`'s gate is what needs revisiting — a
   decision above this task.

6. **`kind` is the component types joined, not a lookup table.** An earlier pass derived the card's
   descriptor from a substring table (`type contains "cache"` → "Distributed cache"). That is
   per-solution knowledge compiled into SM, which is precisely what `REQ-001`'s gate forbids,
   dressed as presentation. The shipped rule — declared component types, declaration order, joined
   with ` · ` — invents nothing and cannot drift per solution. It is documented in the function and
   pinned by `derivePackageCatalog.test.ts`.

7. **Colour is hue-only.** FNV-1a over the solution id gives the hue; saturation (0.46) and
   lightness (0.31) are fixed. That keeps every generated card at the same weight as the curated
   ones (`#015B82`, `#3A4880`, `#2E6E4E`) and — because lightness never rises — keeps
   `getContrastText()` on the same light foreground for all of them. The test asserts the
   BT.601 brightness ceiling directly, so no future hue can produce an unreadable card.

8. **Nothing unknowable is fabricated.** `published` stays empty rather than becoming today's date.
   The synthesized instance reports `lastSeen: 'never'`, `healthRollup: 'not_applicable'`,
   `configState: 'na'` and `version.state: 'unverified'` — the last chosen deliberately over
   `unknown`, because `unverified` does **not** block editing (`UI-D12`), so a dropped package's
   Designer is not accidentally read-only. `authoring.source` reads `'loaded package file'`.

9. **`viewModelFor()` projects EVERY component, one at a time.** `toDesignerViewModel()` folds a
   `ViewModel` into exactly one `VmComponent`, and a `ViewSection` carries only a title — so two
   components that both declare a "General" section are indistinguishable after a single combined
   projection. Projecting each component against a selection of just itself and concatenating is
   correct by construction (`UI-D2`'s `when:` grammar is within-component) and needs **no change**
   to the adapter, which is FE-113's own recorded follow-up. The alternative — rendering only
   `components[0]` — would silently show a third of a three-component package.

10. **Two changes to `db-designer`'s own view model, both toward honesty.** Generalising means one
    implementation serves both, so where they differed one had to win.
    - `DB_VENDOR` now reports `source: 'default'` instead of `source: 'draft'`. The DB-specific
      version seeded `{ DB_VENDOR: 'postgres' }` as a draft value; nothing generic can know which
      discriminator to seed. Nobody authored that value, so `default` is the true one.
    - `healthMappingDeclared` is now `false`, not `true`. Both it and `requiresUniformConfig` come
      from `descriptor.yaml`, which a package file is only half of. Each takes the **conservative**
      reading: uniform config required, no health mapping declared — which withholds the
      health-gated rollout strategy rather than offering one nothing can gate. Nothing renders
      either field today.

11. **The `msw/node` default set is untouched.** `devPackageFlow.test.ts` and
    `devPackageScreens.test.tsx` register the dev handlers with `server.use(...)`, which
    `src/test/setup.ts` resets after every test. `shared/testdoubles/**` imports nothing from
    `app/dev`, so no other suite can see a loaded package.

12. **`FileReader` fallback in `parsePackageFile`.** jsdom's `File` does not implement `Blob.text()`,
    which every real browser does. The fallback is what makes the function testable at all — noted
    because it reads like a browser-compatibility hedge and is not one.

## Known limitations, accepted rather than papered over

- **A discriminator with no `default` hides its dependent branch.** `project()` evaluates `when:`
  against defaults-applied values; a dropped package has no config-of-record, so the only values
  available are the package's own defaults. Where a discriminator declares one, the dependent
  branch renders. Where it declares none, every dependent field is invisible and its section can
  render empty. **This is `project()`'s specified behaviour, not a loader defect**, and it is not
  compensated for: seeding a discriminator would mean the console picking a branch the package
  author did not, leaving the operator looking at a screen no package declared. Both halves are
  asserted in `viewModelFor.test.ts`'s last block, under a comment saying so.
- **One instance per package, named `<solution>-dev-01`.** A package declares component *types*; only
  a deployed instance composes named members of a `one-of-many` component, and the console has no
  such composition data. The component type doubles as its instance key — the degenerate
  `exactly-one` case.
- **`SolutionCard`'s `data-solution-card` / `data-dev-mode` attributes never reach the DOM**,
  because `shared/ui`'s `Paper` does not forward unknown props. Pre-existing; the new tests locate
  cards by `data-surface` instead rather than fixing a shared component from this task.

## Verification

| Check | Result |
|---|---|
| `npm run typecheck` | clean |
| `npm run lint` (`--max-warnings=0`) | clean |
| `npm test` | **224 files, 1178 tests, all passing** (79 of them new) |
| `npm run build` | clean |
| Production-bundle audit | **no** dev module in any chunk. `grep` over `dist/assets/` for `devPackageStore\|devSolutionsRepository\|parsePackageFile\|derivePackageCatalog\|viewModelFor\|examplePackage\|widget-mesh\|db-designer` → nothing. `SolutionsRoute-*.js` is 447 bytes and contains **zero** `import(` calls: Vite folded the `import.meta.env.DEV` branch and eliminated the dynamic import with it. |
| `npm run dev` | server boots clean; every new module transforms and serves `200` through the dev pipeline (`main.tsx`, `examplePackage.ts`, `devSolutionsRepository.ts`, `devPackageStore.ts`, `parsePackage.ts`, `viewModelFor.ts`, `derivePackageCatalog.ts`, `SolutionsRoute.tsx`) |
| `npm run format` | not run as a gate — the repo has 115 pre-existing unformatted files, including two this task touched. Every **new** file is Prettier-clean, and `SolutionsRoute.tsx` (clean at `HEAD`) was kept clean. |

**Test coverage against the design's list**

| Required | Where |
|---|---|
| valid package parsing | `parsePackage.test.ts` — 3 cases, incl. an unsigned package and one omitting undereferenced fields |
| malformed JSON | `parsePackage.test.ts` — truncated, empty, and valid-JSON-that-is-not-an-object |
| missing / invalid required fields | `parsePackage.test.ts` — 7 table-driven root fields, each asserting the named path, plus field type, action kind, metric source, non-string signature |
| `.zip` rejection | `parsePackage.test.ts` — rejected by name, on a file whose *contents* are a valid package |
| `.sompkg` rejection | `parsePackage.test.ts` — incl. case-insensitive |
| store registration | `devPackageStore.test.ts` — keying, replacement, ordering, memory-only |
| store rehydration | `devPackageStore.test.ts` — round-trip, derived views survive, no storage access until first read |
| corrupt storage rejection | `devPackageStore.test.ts` — 4 corrupt payloads, per-entry rejection keeping the valid neighbours, and storage that throws on every access |
| merged catalog behaviour | `devPackageFlow.test.ts` — nothing displaced, read at request time, card intact over the wire |
| instance lookup | `devPackageFlow.test.ts` — list, get, fall-through to defaults for ids it does not own, 404 for an unloaded package |
| Designer click-through | `devPackageScreens.test.tsx` (card → Open → `/solutions/widget-mesh/instances`) + `devPackageFlow.test.ts` (`loadDesigner()` returns the projected model; edit the package and it follows) — see Ruling 1 for why it is in two halves |

## Follow-ups

- **Assemble the Designer screen.** Everything needed exists; `InstanceShell`'s `designer` branch is
  still a placeholder, and until it is not, "the Designer renders from a dropped package" cannot be
  seen in a browser — only asserted at the repository boundary.
- **Decide whether an unsigned dev-loaded package should be openable** (Ruling 5). Today `UI-D19`'s
  card gate says no, which makes the unsigned path — the one dev-mode admission exists for — a
  catalog-only experience.
- **`§16 Q10`** — the real package upload endpoint. When it lands, `withDevPackages` is the thing to
  delete, and `parsePackage.ts`'s validation list is a written record of exactly which fields the
  frontend depends on.
