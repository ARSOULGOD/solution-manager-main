# FE-046 · View-model types, `project()`, shared fixture runner — Report

## What I implemented

`UI-D15`'s pure projection engine — `project(package, componentSelection, draftValues, observedState) →
ViewModel` — proven against a shared JSON fixture suite a future Java implementation must also pass (task
spec: `docs/plans/frontend/implementation.md` §FE-046; refs `REQ-001`, Dec `UI-D2` `UI-D3` `UI-D4` `UI-D15`
`UI-D16`, `docs/design/dynamic-ui/design.md` §3/§6.5).

| File | Summary |
|---|---|
| `frontend/contracts/view-model/types.ts` | `SolutionPackage`/`ComponentDeclaration`/`FieldDeclaration` (package input, §3.1–§3.5, pinned) plus `ComponentSelection`/`DraftValues`/`ObservedState` (the other three `project()` inputs — inferred, called out per field) and `ViewModel`/`ViewSection`/`ViewField`/`ViewGraph`/`ViewStatus` (output, §6.5, pinned verbatim). Self-contained closed vocabularies (`FieldType`, `SourceKind`, `Mark`, `HealthKey`, `VersionState`, `ConfigState`) — `contracts/**` cannot import `@shared/vocab`, so these are declared fresh here, hand-kept consistent with the same-meaning frontend vocab. |
| `frontend/contracts/view-model/project.ts` | The engine. Defaults-resolution → equality/AND predicate evaluator → per-field visibility/masking/advanced projection → version-state edit-gate → section/action assembly → `ViewModel`. Nothing is memoized across calls — obligation 4 ("changing a discriminator re-evaluates all descendants") falls out of that for free, not from special-case code. |
| `frontend/contracts/fixtures/view-model/*.json` (11 files) | One fixture per obligation/scenario — see below. |
| `frontend/contracts/fixtures/view-model/run.test.ts` | The shared-suite runner: reads every `*.json` in the directory, calls `project()`, asserts a structural deep-equal, plus a direct scan proving no raw secret value leaks into the serialized output (obligation 7 / `I6`, stronger than the deep-equal alone). Runs under Vitest's pre-existing `contracts` project (`node`, not `jsdom`). |
| `frontend/contracts/fixtures/README.md` | Pins the fixture file shape and the two things that actually need pinning for a future Java runner to agree with this one: number formatting (plain JSON numbers, no `1.0`) and `null` vs. absent. Key ordering is documented as a *readability* convention only — the comparison is structural, not string-level, so a Java `Map`'s different iteration order can't fail it. |
| `frontend/tsconfig.json` | `"include"` gained `"contracts"` — it only listed `"src"`, so nothing under the new tree was reachable by `tsc -p tsconfig.json`'s import-graph walk (confirmed: `projectService: true` makes ESLint's own type-aware linting tolerant of files outside `include`, but plain `tsc` is not). |
| `frontend/vite.config.ts`, `frontend/src/app/aliases.test.ts` | `@contracts/*` repointed from `./src/contracts/*` to `./contracts/*` (see Ruling 1). The alias-resolution test now imports `project` and asserts `typeof project === 'function'`, matching the pattern its own `@app/*` case already used, instead of a placeholder constant. |
| `frontend/src/contracts/index.ts` | Deleted (with its now-empty directory) — see Ruling 1. |

## Rulings

1. **`contracts/` lives at `frontend/contracts/`, not a repo-root sibling of `frontend/`, despite the task's
   own prose and `CLAUDE.md`'s repo-layout table both reading like a repo-root folder.** Two pieces of
   already-built, already-committed tooling say otherwise and are stronger evidence than a tree diagram:
   `vitest.config.ts` (task 008) already declares a `contracts` project with
   `include: ['contracts/fixtures/**/*.test.ts']`, a glob resolved relative to Vitest's root (`frontend/`);
   and `eslint.config.js` (task 009) forbids `contracts/**` files from importing `'../src/*'` — a pattern that
   only resolves correctly one level up from `frontend/src/`. Placing the engine at the repo root would have
   left both already-written checks silently inert. Fixing the wiring to reach it meant three follow-on
   changes beyond the task's own Files list: `tsconfig.json`'s `include`, the `@contracts/*` alias, and
   retiring `frontend/src/contracts/index.ts` (task 001's scaffold — its own header comment said it existed
   only "so the `@contracts/*` path alias has something to resolve against" until a later task supplied the
   real thing; nothing else imported it, confirmed by grep before deleting).
2. **The fixture runner is `contracts/fixtures/view-model/run.test.ts`, not `src/test/contracts.test.ts`**
   (the task's own Files list). The latter would run under the `unit` Vitest project (jsdom) instead of the
   purpose-built `contracts` project (lean `node`) that's been sitting empty since task 008 waiting for
   exactly this. Same treatment as Ruling 1 — real, already-committed infrastructure over literal task prose.
3. **Dropped a planned `discriminator-switch-and-back.json` fixture.** `project()` is stateless — it
   recomputes from its four arguments on every call, so "switch a discriminator, then switch back" isn't a
   property a *single* call can exercise; the retention half of it is exactly what
   `hidden-key-retention.json` already proves (a field's draft value survives while its branch is inactive),
   and the "descendants re-evaluate" half is proven by every other fixture that has more than one discriminator
   state active. Adding a two-step fixture format just to re-prove something a pure function already
   guarantees structurally would have been test theater, not rigor.
4. **`controlKind` is `'secret'` whenever `secret: true`, regardless of `type`.** Found by a failing fixture,
   not designed in up front: `design.md` §3.3's own `GIT_CLOUD_TOKEN` example is `type: string, secret: true`
   — the closed `type` vocabulary *also* lists a distinct `'secret'` member (§3.4), so a naive
   type→controlKind table (my first pass) rendered a secret string field as a plain text box. Fixed by making
   `secret: true` override the control-kind lookup rather than only driving `masked`.
5. **`graphs[]` is a thin declarative pass-through** (metric name + a placeholder `mark: 'line'`), and
   `optionsSource`-driven fields get an empty static `options: []`. Neither gap-breaking/rate-vs-delta
   computation nor live catalog-option resolution is one of task 046's 9 named obligations — both belong to
   later engines/tasks (`projectSeries()` / task 071 for the former, a Designer data-fetching task for the
   latter). Documented in-code at both call sites rather than left as an unexplained stub.
6. **Section order is first-occurrence, keyed by each field's own `ui.order`/`ui.section`.** The package
   schema declares no top-level "sections" list (§3.1/§3.2) — a section is an emergent grouping of the
   `ui.section` string every field carries. Field order *within* a section is simply array/declaration
   order — `ViewField` has no `order` of its own in the pinned §6.5 signature, so the package's `fields[]`
   array is presumably already curated into the right order by the authoring pipeline (`UI-D17` lists `order`
   as an *authored* column); `project()` doesn't re-sort by it.

## The 9 obligations → fixtures

| # | Obligation | Fixture |
|---|---|---|
| 1 | `when:` evaluates over defaults-applied values | `defaults-applied-predicate.json` |
| 2 | Predicates are equality, implicitly AND-ed | `and-only-equality.json` |
| 3 | Fields are a flat list carrying the whole ancestor chain | `flat-ancestor-chain.json` (3-deep, mirrors `design.md`'s own example verbatim) |
| 4 | Changing a discriminator re-evaluates all descendants | `flat-ancestor-chain.json` (free — see Ruling 3) |
| 5 | Inactive branches keep draft values; only visible fields render into config | `hidden-key-retention.json` |
| 6 | `advanced: true` is a disclosure flag, not a visibility rule | `advanced-is-disclosure-only.json` |
| 7 | `secret: true` masked in form, diff, export and history (`I6`) | `secret-masked-everywhere.json` (+ `run.test.ts`'s own direct leak-scan) |
| 8 | `VERSION_DRIFT`/`VERSION_UNKNOWN` block editing; `VERSION_UNVERIFIED` does not | `version-state-drift-blocks.json`, `version-state-unknown-blocks.json`, `version-state-unverified-allows.json` |
| 9 | `project()` is auth-free | `auth-free.json` |

Plus `multi-component-selection.json` — a File-Mesh-shaped two-instance case exercising `ComponentSelection`
disambiguation, since Cache alone never touches that path.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                    # clean
npm run typecheck                               # clean
npx vitest run --project contracts              # 11/11 fixtures passed
npx vitest run                                  # full suite: 145 files, 692 tests — all passed
npm run build                                   # production build succeeds
```

Also verified directly (not assumed): temporarily added a `../../src/app/NotFound` import to
`contracts/view-model/types.ts` and confirmed `boundaries/dependencies` fires
("no policy allowing dependencies from elements of type 'contracts' to elements of type 'app'"); removed it
and confirmed lint returns clean again.

One pre-existing flake, unrelated to this task: `InstancesPage.test.tsx`'s 320px reflow test times out only
under full-suite parallelism load (passes standalone, and passes on a second full-suite run) — same class of
issue already noted in the FE-107 report for a different file in the same suite. Not touched by this task's
changes.

## Status

DONE — implemented, tested (692/692 on a clean full run), lint-clean (including a verified-not-assumed
boundaries check), typecheck-clean, production build succeeds. Not yet closed on GitHub — pending review,
particularly Ruling 1 (relocating `contracts/` wiring, which touches build config beyond this task's own
Files list) and Ruling 2 (the fixture-runner path departing from the task's literal text).
