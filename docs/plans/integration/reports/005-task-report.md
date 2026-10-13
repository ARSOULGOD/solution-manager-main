# Integration plan 005 · Solutions & Targets catalog — Report

**Result:** ✅ **Tasks 1–3 done and verified by automated tests. Task 4's component-level half is done;
the Playwright browser-level half could not be run in this session** (no running dev server / Playwright
browsers available to this agent — see "What could not be verified" below).

## Task 1 — contract-conformance re-check

`frontend/src/shared/api/contractConformance.test.ts` already covers all three routes and still passes for
them: `GET /cp/solutions`, `POST /cp/solutions/load` and `GET /cp/targets` are in `KNOWN`/in-contract paths,
and `GET /cp/solutions/{}/package` is still correctly recorded in `KNOWN_DRIFT` as bucket F (no contract
operation — `loadPackage`/`listSolutions` are the only two `/cp/solutions*` operations in `console.yaml`).
No change was needed to that test file. (The suite's own
`every contract operation is called or listed as uncalled` assertion is currently red for unrelated
reasons — see "Pre-existing, out-of-scope failures" below.)

## Task 0.5 — what the real wire shapes actually are (re-verified against `console.yaml`)

- `GET /cp/solutions` → `SolutionList { items: Solution[] }`. `Solution` = `{ solutionId, solution, name,
  packageVersions, latestPackageVersion, signature: "unsigned", stateVersion }`. **No** `color`, `icon`,
  `kind`, `entitled`, `instanceCount`, `telemetryMode`, `components`, `authoring`, `appliesTo`, `provenance`,
  `devMode` or `published` — the entire pre-005 fixture shape (`SolutionPackageSummary`) was invented.
- `POST /cp/solutions/load` → body `{ package: <full Solution Package document> }` (not file metadata), reply
  `LoadPackageResult = { solutionId, packageVersion, signature: "unsigned", stateVersion }` on `200`/`201`.
  **No** `verified`, `devMode`, `signatureFingerprint` or `message` — `loadPackage` has no server-side
  signature-verification step. v1 loads no signed packages at all (`Solution.signature` enum is the single
  value `unsigned`), so there is nothing for a `verified`/`devMode` admission gate to distinguish.
- `GET /cp/targets` → `TargetList { items: Target[] }` (envelope rule, `OR-38`: `AvailabilityOnly`, no
  top-level `stateVersion`). `Target` = `{ targetId, name, kind, transport, ownershipScope, allowedConfigRoot?,
  allowedContainerPrefix?, retiredAt? }`. `ownershipScope` is a `ScopePath` **object** of optional ids
  (`organizationId`/`subOrganizationId`/`environmentId`/`solutionId`/`instanceId`), never a flat array of
  display-name strings. **No** `location`, `driver` or `locus` — the pre-005 fixture shape was invented.

## What was built

### Task 2 — Solutions adapter

- **`frontend/src/features/inventory/solutions/solutionsRepository.ts`** — rewritten. New `SolutionCatalogItem`
  type mirrors `Solution` exactly; `list()` unwraps the `SolutionList` envelope. `load()` now takes a
  `CanonicalSolutionPackage` (the already-parsed, already-validated document) and posts `{ package }`; the
  response maps straight onto the new `PackageLoadResult` (no `verified`/`devMode` synthesis).
- **`frontend/src/features/inventory/solutions/LoadPackageDialog.tsx`** — `verification.mutate(selected)` →
  `verification.mutate(inspected.package)` (the file is parsed client-side by `inspectPackageFile.ts` either
  way; the difference is what gets POSTed). The `isAdmitted()` dev-mode-only gate is removed: since
  `loadPackage` has no signature-verification step, a successful response **is** the admission — there is
  nothing left to check beyond "did the server accept it." ⚠️ **This is a real, flagged behavior change** —
  see "Open decision" below.
- **`frontend/src/features/inventory/solutions/SolutionCard.tsx`** — rewritten to render only
  `Solution`'s fields (name, `solution`, `packageVersions`, `latestPackageVersion`, an informational "Unsigned
  (v1 loads no signed packages)" chip). The provenance/signed badge, authoring-review meter, entitlement chip,
  instance count, telemetry-mode line and component-type chips are **gone** — none of that data exists on the
  contract. `ReviewMeter.tsx` (and its test) is deleted as dead code.
- **`frontend/src/features/inventory/solutions/sidebarSolutions.ts`** — `toSidebarSolutions()` maps
  `solutionId`/`name` only; `instanceCount` is a flagged `0` (`CONTRACT: TBD` comment — `Solution` carries no
  per-solution instance count, and computing a real one needs a join with `listInstances` this plan does not
  own), and `icon` is simply omitted (the field is optional on `SidebarNavSolution`).
- **`frontend/src/features/inventory/solutions/SolutionsPage.tsx`** — `solution.id` → `solution.solutionId`;
  `existingPackages` is now `[]` with a `CONTRACT: TBD` comment (`Solution` has `solution` but no `appliesTo`
  range, so the load dialog's duplicate/overlap-range preflight can't be populated honestly from the real
  catalog — flagged rather than invented, same posture as the confirm/discard question below); the success
  toast's detail string now reports the loaded `packageVersion` array instead of a fabricated server
  `message`.
- **`frontend/src/features/inventory/solutions/index.ts`** — barrel updated to export `SolutionCatalogItem`/
  `PackageLoadResult`/`PackageVersionId` instead of the deleted `SolutionPackageSummary`.
- Tests rewritten to the new shapes: `SolutionCard.test.tsx`, `sidebarSolutions.test.ts`,
  `LoadPackageDialog.test.tsx`, `LoadPackageDialog.a11y.test.tsx`, `SolutionsPage.test.tsx`,
  `SolutionsPage.a11y.test.tsx`.

### Task 3 — Targets (read) adapter

- **`frontend/src/features/targets/targetsRepository.ts`** — rewritten. New `TargetRow`/`TargetOwnershipScope`
  types mirror `Target`/`ScopePath` exactly; `list()` unwraps the `TargetList` envelope (no `stateVersion` to
  check, per `OR-38`).
- **`frontend/src/features/targets/TargetsPreview.tsx`** — table columns changed from
  location/driver/control-locus to id/kind/transport/ownership-scope; `ownershipScope` is rendered as its
  defined ids, deepest-last, with an "Organization-wide" fallback when none are set (new
  `formatOwnershipScope()` helper, new i18n key `targetsPreviewScopeOrganizationWide`). The locus-security
  banner copy (`control_locus is a security boundary…`) and the Target/Binding model-field panels are
  unchanged — they're static design-model copy, not derived from `TargetRow`.
- Tests rewritten: `targetsRepository.test.ts` (now MSW-driven, asserting the envelope unwrap and an
  empty-items case), `TargetsPreview.test.tsx`, `TargetsPreview.a11y.test.tsx`.

### Task 4 — component tests + browser verification

- Component tests above are the "real envelope shape" coverage this task asks for; all pass (see "Verification"
  below).
- **Playwright could not be run in this session** — see below.

## Shared files touched (flagged, additive, smallest-change)

Per the brief's "touch shared files only if strictly required" rule:

- **`frontend/src/shared/testdoubles/handlers/cp.ts`** — the default MSW handlers for `GET /cp/solutions`,
  `POST /cp/solutions/load` and `GET /cp/targets` now emit the real contract envelope/shape instead of the
  raw fixture arrays, mapping `SOLUTIONS`/`TARGETS` down to `Solution`/`Target` rows. Nothing outside those
  three handler bodies was touched (the `/cp/home/summary` handler two lines above is untouched).
- **`frontend/src/app/dev/devPackageHandlers.ts`** — only the `GET /cp/solutions` dev-mode override handler
  was changed, to emit the same contract envelope (it feeds the same `SolutionsRepository.list()` consumers).
  Every other handler in that file (home/summary, package-by-id, declare, undeclare, designer) is untouched.
- **`frontend/src/app/dev/devSolutionsRepository.ts`** — `withDevPackages().load()` used to intercept the raw
  `File` and parse its bytes itself, because the real repository's `load()` only ever sent file metadata.
  Plan 005 moved parsing to `LoadPackageDialog.tsx` (via `inspectPackageFile.ts`) and wired `load()` to the
  real `loadPackage` body. This wrapper now receives an already-validated document and only holds it pending
  — rewritten accordingly, with `parsePackageFile`/`ApiError`-rejection logic removed as dead code.
- **`frontend/src/app/dev/devPackageFlow.test.ts`** — fixed to compile and pass against the above: `.id` →
  `.solutionId` throughout; the "carries the derived card through the wire unchanged" test now asserts the
  contract-mapped fields instead of the invented ones; the "repository decorator" describe block (File-parsing
  interception) is rewritten for the new pending-only contract, dropping the two sub-tests whose premise
  (parsing happens in the wrapper) no longer holds — their coverage (parse rejection, non-`File` passthrough)
  now lives upstream in `inspectPackageFile.ts`'s own consumers; the two instance-count tests that read
  `solutions.list()`'s own `instanceCount` field are rewritten to read the instance grid directly (the FE-120
  behavior under test — declare/undeclare move the count, keyed on card id not shared `solution` — is
  unchanged; only the field that carries it moved).
- **`frontend/src/features/inventory/instances/InstancesPage.tsx`** and **`InstancesPage.test.tsx`** — minimal
  fix-up only (`candidate.id` → `candidate.solutionId`, `solution?.packageVersion` → `solution?.packageVersions`,
  and the test's mocked `SolutionsRepository` fixture updated to the new shape). This file is plan 007/008
  territory, not touched beyond what was needed to keep it compiling against the new repository types.
- **`frontend/src/app/shell/AppShell.test.tsx`** — a full-repo `vitest run` surfaced two real runtime
  regressions this plan's change to `sidebarSolutions.ts` caused (not just a compile break): the drawer's
  "Cache Engine (12 instances)" label is now honestly "(0 instances)" (flagged, not invented — see open
  decision 3), and the "dev-mode package never advertised in the drawer" assertion no longer holds, because
  `devMode` doesn't exist on `Solution` — there's no signed/unsigned split left to filter the drawer list on
  (open decision 1). Both assertions were updated to the new honest behavior, with comments pointing back to
  the relevant open decision; both tests pass. This is the one file in this plan where I fixed an actual
  behavioral regression, not just a type error — flagged here because it's outside the designated folders.
- **`frontend/src/shared/i18n/messages.en.ts`** — added `solutionsLatestPackageVersion`,
  `solutionsSignatureUnsigned`, `solutionsLoadAdmitted`, `solutionsLoadSuccessPackageVersions`,
  `targetsPreviewColKind`, `targetsPreviewColTransport`, `targetsPreviewScopeOrganizationWide`. Nothing
  existing was removed (some now-unused keys like `solutionsEntitled`/`solutionsReviewHeading` are left in
  place rather than deleted, to minimize touch surface on a file other agents may also be editing).

## Open decisions raised, not silently resolved

1. **The load-admission gate (`UI-D19`) is effectively superseded by the ratified v1 contract.** `UI-D19`
   ("unsigned admitted only in an explicit development mode") was designed around a server-side
   verified/unverified distinction that `loadPackage` does not have: `Solution.signature` is a closed enum of
   one value, `unsigned`. Under the real contract, every v1 load is unsigned, and the operation is gated by
   `package.load` RBAC + validation only — there is no second, signature-based gate left to enforce. This
   repository/dialog change makes every successful `loadPackage` response admitted, with no console-dev-mode
   check. **This needs an explicit architecture ruling** (new `OR-*` or a `UI-D19`-superseding decision) before
   it's treated as settled — flagged here rather than guessed at.
2. **`existingPackages` (the load dialog's duplicate/overlap-range preflight) has no real data source.**
   `ExistingPackageRange` needs `{ solution, appliesTo }`; `Solution` has `solution` but no `appliesTo`. Passed
   as `[]` from `SolutionsPage`, so the preflight simply never flags an overlap against the real catalog. Needs
   either a new contract field or a ruling that this check is dialog-local only (compares against the single
   file picked this session, never the catalog).
3. **The drawer's per-solution instance count (`sidebarSolutions.ts`) has no contract source at this scope.**
   Flagged `0`, not invented. Would need a join with `listInstances`, out of this plan's scope.
4. **`Target.ownershipScope` carries ids, never display names.** The fixture's `TARGETS` only has display-name
   path segments ("Acme Financial", "Payments", "PROD"), so the MSW mapping in `cp.ts` emits `{}` (empty scope)
   rather than fabricating uuids. The real backend will presumably return real ids; `TargetsPreview.tsx` renders
   whatever ids are present, falling back to "Organization-wide" when none are.

## Verification

```
npx vitest run src/features/inventory/solutions src/features/targets \
  src/app/dev/devPackageFlow.test.ts src/features/inventory/instances/InstancesPage.test.tsx
  # → 12 files, 123 tests, all passing
npx tsc --noEmit -p .        # → 0 errors
npx eslint <touched files>   # → 0 problems
```

A full-repo `npx vitest run` and `npm run build` were kicked off but could not be confirmed finished inside
this session's tool timeout; the scoped run above is the authoritative evidence for plan 005's own files.
`npm run lint` (full repo) and `npm run typecheck` (full repo) each show exactly one pre-existing, out-of-scope
failure — see below.

### Pre-existing, out-of-scope failures (not caused by this plan, not fixed by this agent)

- `npm run lint` (full repo): one `no-restricted-syntax` violation in `src/app/main.tsx` (direct `console.*`
  call) — that file was not touched by this plan; confirmed via `git diff --stat HEAD -- src/app/main.tsx`
  (empty).
- `npm run typecheck` (full repo): `vite.config.ts` / `vitest.config.ts` each have one pre-existing
  `exactOptionalPropertyTypes` overload error, confirmed untouched by this plan the same way.
- `src/shared/api/contractConformance.test.ts`'s "every contract operation is called or listed as uncalled"
  assertion is currently red: it lists ~15 uncalled operations (`registerTarget`, `retireTarget`,
  `scanHostKeys`, `addPlacement`, `createEnvironment`, `setDashboardLayout`, …) that belong to plans 006, 007,
  009, 011 and 012, none of which are wired yet. This test file itself was not modified by plan 005 and its
  two other assertions (the ones plan 005's three routes actually exercise) pass.

### What could not be verified

- **Playwright / real browser run** (login → Solutions list renders from backend → load a package → Targets
  list renders from backend) was not run. This sandboxed session has no way to start the backend's
  `backend/launcher` or a Playwright browser instance, and the task brief says to state this plainly rather
  than fabricate a result. Everything that a real browser run would exercise beyond the component tests above
  — actual HTTP wire bytes against the real `console-gateway`, CSRF header plumbing on `POST /cp/solutions/load`,
  and the real backend's actual `signature`/`stateVersion` values — is unverified end-to-end. The MSW-level
  tests prove the adapters parse/produce the documented contract shapes correctly; they do not prove the real
  backend sends exactly what `console.yaml` says, only that the frontend now trusts that document rather than
  the old fixture shape.

## File list

**New/changed by this plan:**
- `frontend/src/features/inventory/solutions/solutionsRepository.ts`
- `frontend/src/features/inventory/solutions/SolutionCard.tsx` (+ `.test.tsx`)
- `frontend/src/features/inventory/solutions/sidebarSolutions.ts` (+ `.test.ts`)
- `frontend/src/features/inventory/solutions/SolutionsPage.tsx` (+ `.test.tsx`, `.a11y.test.tsx`)
- `frontend/src/features/inventory/solutions/LoadPackageDialog.tsx` (+ `.test.tsx`, `.a11y.test.tsx`)
- `frontend/src/features/inventory/solutions/index.ts`
- `frontend/src/features/inventory/solutions/ReviewMeter.tsx` — **deleted** (+ its test)
- `frontend/src/features/targets/targetsRepository.ts` (+ `.test.ts`)
- `frontend/src/features/targets/TargetsPreview.tsx` (+ `.test.tsx`, `.a11y.test.tsx`)
- `frontend/src/shared/i18n/messages.en.ts` (additive keys only)

**Shared/out-of-folder, minimal flagged touches:**
- `frontend/src/shared/testdoubles/handlers/cp.ts` (3 handler bodies only)
- `frontend/src/app/dev/devPackageHandlers.ts` (1 handler body only)
- `frontend/src/app/dev/devSolutionsRepository.ts` (rewritten — interception point moved)
- `frontend/src/app/dev/devPackageFlow.test.ts` (compile/assert fix-up)
- `frontend/src/features/inventory/instances/InstancesPage.tsx` (2-line fix-up)
- `frontend/src/features/inventory/instances/InstancesPage.test.tsx` (fixture fix-up)
