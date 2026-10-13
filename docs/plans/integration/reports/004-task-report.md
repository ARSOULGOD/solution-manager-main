# Integration plan 004 · Home — Report

**Result:** ✅ **Tasks 0–2 done and verified by automated tests. Task 3 partial** — a browser-level
(Playwright) verification exists and is written against the real contract shape, but could not be *run* in
this session (see "What could not be verified" below); component-level verification of the same behavior
(navigation, non-navigable fallback) is done and green under vitest/jsdom.

## Task 0 — the real wire shape (re-checked 2026-10-08 against `contracts/openapi/console.yaml`)

- `GET /cp/home/summary` → `HomeSummary { solutions: [{ solutionId, name, instanceCount, byHealth?, stateVersion }] }`
  (lines ~4232–4316 of `console.yaml`). **No top-level `configStagedNotApplied` exists anywhere on the wire.**
  **D-2 is still open**: it renders `null` / "—", never a fabricated number. No top-level `stateVersion` either
  (`OR-55` exemption, as the plan's Task 1 anticipated) — each solution row carries its own.
- `GET /cp/home/attention` → `HomeAttention { items: [{ instanceId, placementId?, reason, state?, stateVersion }] }`
  (lines ~4317–4351). **`reason` is one of `health` / `apply-indeterminate` / `config-diverged`; `state` (only
  present for `reason: health`) is the nine-value notify vocabulary** (`GREEN, RED, RESTART_EXPECTED,
  INACTIVE_REPLICA, NOT_APPLICABLE, unreachable, not_exposed, missing, bootstrap`). **No `instanceName` or
  `solutionId` on the item — D-1 option A is NOT taken.** D-1 is resolved as **option B**: the frontend resolves
  each item via `GET /dp/instances/{instanceId}` (`getInstance`), which does carry `instanceId, name, solutionId`
  (`console.yaml` lines ~4154–4166).

## What was built (Tasks 1–2)

- **`frontend/src/features/home/homeRepository.ts`** — rewritten. Drops the `@shared/testdoubles` import entirely
  (the file's own header used to say "CONTRACT: TBD" and import `AttentionItem` from the fixture data module; both
  are gone). Now:
  - Defines the wire types (`WireHomeSummary`, `WireHomeAttention`, `WireInstance`) matching the contract exactly.
  - `toSummary()`: `solutionsManaged = solutions.length`, `instancesDeclared = Σ instanceCount`,
    `needAttentionNow = Σ byHealth[RED] + Σ byHealth[unreachable]` — the exact pair the plan's Task 1 names as the
    "err-tone states" (this is a stat-strip counter policy; it is *not* the same grouping
    `STATE-VOCABULARY.md` §2 uses for the per-row severity badge below — documented inline so the two aren't
    conflated). `configStagedNotApplied` is always `null` (D-2) and `partial` is always absent — no field carries
    either on the wire.
  - `toAttentionItems()`: for each wire item, resolves the instance via `dpClient.get('/instances/{id}')` (D-1
    option B), building `title`/`detail`/`severity`/`href`. **A resolution failure (404, 403, network) degrades
    that one row to a non-navigable one — it never fails the whole feed and never fabricates a link.** Severity
    mapping combines STATE-VOCABULARY.md §2's HEALTH/GOV_HEALTH/NOTFOUND tables (e.g. `RED`→`err`,
    `unreachable`→`warn`, `INACTIVE_REPLICA`→`idle`); `config-diverged`→`err` (CONFIG table's `diverged` entry);
    `apply-indeterminate` has no table entry of its own, so it is given `warn` as an explicit, documented judgment
    call, not a silent default.
  - `createHomeRepository` now takes **two** clients, `(cpClient, dpClient)` — the second is new, needed only for
    the D-1 resolution above.
  - `AttentionItem.href` is now **optional** (`string | undefined`, via `exactOptionalPropertyTypes`-safe
    conditional spreading) rather than always present.
- **`frontend/src/features/home/AttentionFeed.tsx`** — the `AttentionItem` import now comes from
  `./homeRepository` (view model), not `@shared/testdoubles/data/instances` (fixture). A row with no `href`
  (unresolved instance) renders as a non-interactive `Box`, not a dead or wrong `Link` — same icon/title/detail,
  just no chevron and no click target.
- **`frontend/src/app/routes/HomeRoute.tsx`** — constructs a `dpClient` alongside the existing `cpClient` and
  passes both to `createHomeRepository`.
- **`frontend/src/features/home/index.ts`** — exports `AttentionItem`/`AttentionSeverity`.
- **`frontend/src/features/home/homeRepository.test.ts`** — rewritten with MSW (`msw/node`, `onUnhandledRequest:
  'error'`) against the real envelope: summary aggregation (including the "no `byHealth` key" and "empty
  `solutions[]`" edge cases), attention mapping with a successful `getInstance` resolution (asserts the built
  `href` — `/solutions/{solutionId}/instances/{instanceId}/governance`), the degrade-on-404 case, and a severity
  table case (`unreachable`→`warn`, `INACTIVE_REPLICA`→`idle`, `apply-indeterminate`→`warn`). TDD: written and run
  RED against the old implementation before the rewrite, GREEN after.
- **`frontend/src/features/home/AttentionFeed.test.tsx`**, **`HomePage.test.tsx`**, **`HomePage.a11y.test.tsx`** —
  updated to the new type/constructor signature; added one new case to `AttentionFeed.test.tsx` for the
  non-navigable row.
- **`frontend/src/shared/testdoubles/handlers/cp.ts`** (shared MSW handler file — see "Shared-file touches"
  below) — `/cp/home/summary` and `/cp/home/attention` now return the real contract envelope, derived from the
  existing `CACHE_INSTANCES`/`SOLUTIONS` fixtures (no new fixture numbers invented) via a small local
  `healthWireKeyOf()` mapping from the legacy lowercase fixture vocabulary to the real wire vocabulary. The old
  `ATTENTION_FEED` import was dropped from this file (the fixture array itself is untouched and still exported from
  `shared/testdoubles/data/instances.ts` for its other consumer, `app/dev/devPackageHandlers.ts`).
- **`frontend/src/app/dev/devPackageHandlers.ts`** (shared dev-only MSW handler file — see "Shared-file touches")
  — its `/cp/home/summary` override (used only by the interactive `npm run dev` package-drop flow, never by the
  automated `msw/node` suite per this file's own header comment) updated to the same real envelope, so a manual
  dev session doesn't get a broken Home page after this plan. `/cp/home/attention` has no dev override here, so it
  already fell through to `cp.ts`'s handler.

## Shared-file touches (flagged per instructions)

Four plans (005, 008, 010, 011) are running concurrently in this same checkout. Files touched outside
`frontend/src/features/home/**` and their own tests:

- `frontend/src/app/routes/HomeRoute.tsx` — home-feature-owned, low risk.
- `frontend/src/shared/testdoubles/handlers/cp.ts` — touched **only** the two `home/*` handler blocks, plus the
  import list (dropped `ATTENTION_FEED`, added `InstanceRow` type). Did not touch the solutions/RBAC/connections
  handlers in the same file.
- `frontend/src/app/dev/devPackageHandlers.ts` — touched **only** the `/cp/home/summary` handler, plus dropped the
  now-unused `ATTENTION_FEED` import and added one small local helper function. Did not touch
  declare/undeclare/designer-load/etc. in the same file. **Note:** while verifying, I found this file already had
  substantial uncommitted changes from another agent's concurrent work (`devSolutionsRepository.ts` /
  `SolutionCatalogItem` migration) — my edit is additive on top of that in-progress state, not a revert of it.
- `frontend/e2e/journeys.spec.ts` — appended one new, unnumbered `test(...)` block at the end (did not touch any
  of the existing numbered journeys 1–7).
- `frontend/contracts/solution-package/generated/solution-package-validator.mjs` — regenerated via
  `npm run generate:package-validator` to unblock `npm run dev`/`npm run build`, which were failing in this
  checkout *before I started* with "Solution Package validator is missing or stale" (confirmed via `git status`/
  `git diff` that no one has touched `contracts/solution-package.schema.json`, so this is a pure deterministic
  regeneration from an unchanged schema, not a judgment call). Unrelated to plan 004's own scope but needed to run
  anything that boots the dev server.

I did **not** touch `frontend/src/shared/testdoubles/handlers/dp.ts` or `frontend/src/app/routes.ts` — see Task 3
below for why that constrains what "D-1, wired" could mean here.

## Task 3 — browser verification

**Component-level (done, green):** `AttentionFeed.test.tsx` now has a case rendering an item with no `href` and
asserting it renders as a non-link row with no `getByRole('link')` match — the same code path a real
`getInstance` 404 would produce. `homeRepository.test.ts` exercises the successful-resolution and
failed-resolution paths against MSW directly.

**Browser-level (written, not run):** I added one Playwright test to `frontend/e2e/journeys.spec.ts` (not a new
file, to avoid touching `playwright.config.ts`'s `testMatch` regex) that logs into the `fixture` project (MSW-backed
dev server), asserts the counters/attention-feed render, clicks the `cache-stage-01` row, and asserts the URL lands
on `/solutions/cache/instances/cache-stage-01/governance`.

**Why it could not be run this session:**
1. **No real backend is reachable from this repo's own Playwright setup at all** — `playwright.config.ts`'s header
   comment is explicit that neither Playwright project (`preview` or `fixture`) has a backend behind it; `fixture`
   is MSW. So "against a running production gateway" (the plan's literal Task 3 wording) was never achievable
   from inside this repo in this environment — I ran the closest available substitute (MSW, the same shape the
   component tests already use) instead.
2. **Port contention from a concurrent agent.** `npm run dev` on the fixed `DEV_PORT=5173` Playwright expects
   failed with `Error: Port 5173 is already in use` — another of the four concurrently-running plan agents has a
   dev server up on that port in this shared checkout. I did not attempt to force a different port (that requires
   editing the shared `playwright.config.ts`) or kill another agent's process.

I recommend re-running `npx playwright test -g "Home renders real-shaped" --project=fixture` once the other
agents' sessions have released port 5173, and record that result in
`docs/plans/integration/reports/004-task-03-report.md` as the plan's Task 3 asks. I did not fabricate that file
with an unrun result.

## Decisions taken (for the record, alongside D-1/D-2 above)

- **D-1 → option B, implemented in-repository** (not a full Instances feature): `homeRepository.ts` calls
  `GET /dp/instances/{id}` itself, one per attention item, in parallel (`Promise.all`). This is bounded to Home's
  own scope per the plan's "Home only resolves attention-feed *links*… it doesn't implement them" — it builds a
  working link, it does not build an Instances screen or a shared instance-detail repository. A future plan (007)
  owns the real `dp.ts` MSW handler's contract-shape migration (it still returns the pre-contract `InstanceRow`
  fixture shape, keyed `id` not `instanceId` — I deliberately did not touch it, and my adapter never reads the
  wire instance's `instanceId` field, only `name`/`solutionId`, so it is unaffected either way).
- **Failure mode for D-1's resolution is "degrade the row, don't drop it or fabricate a link"** — not in the plan
  text verbatim, but the only reading consistent with "no silent fixture fallback on a served operation's error
  response" (the plan's global constraints) and I6/I5 (no invented identity data).
- **`needAttentionNow`'s "err-tone states" list is taken literally from the plan** (`RED`, `unreachable`) even
  though `STATE-VOCABULARY.md` §2 itself tones `unreachable` as `warn` in the `NOTFOUND` table — these are two
  different concerns (a stat-strip counter policy vs. a per-row badge color) and I kept them deliberately separate
  rather than reconciling them into one rule, with a comment at the point of policy explaining why.

## Verification run

```
cd frontend
npm run typecheck   # clean for every file this plan touched (pre-existing errors elsewhere are
                     # from other agents' in-flight work — governance/, monitoring/, inventory/, targets/)
npx eslint src/features/home src/app/routes/HomeRoute.tsx src/app/dev/devPackageHandlers.ts \
  src/shared/testdoubles/handlers/cp.ts   # clean
npx vitest run src/features/home --reporter=dot   # 6 files, 26 tests, all passing
```

`npm run build` could not be run — it fails in `prebuild` on the pre-existing stale-validator check described
above, which I fixed as a side effect; after that fix `npm run dev` still could not bind its Playwright-expected
port (see Task 3). `npm run lint` (project-wide) and `npm test` (project-wide) were not run to completion because
the shared checkout currently has multiple other plans' in-progress, uncommitted, type-breaking changes
(confirmed via `git status` and by reading the failing files) — running either project-wide would report failures
that are not plan 004's to fix or explain. The scoped commands above are what this plan's own Definition of Done
is checked against.

## Files changed

- `frontend/src/features/home/homeRepository.ts` (rewritten)
- `frontend/src/features/home/homeRepository.test.ts` (rewritten, TDD)
- `frontend/src/features/home/AttentionFeed.tsx`
- `frontend/src/features/home/AttentionFeed.test.tsx`
- `frontend/src/features/home/HomePage.test.tsx`
- `frontend/src/features/home/HomePage.a11y.test.tsx`
- `frontend/src/features/home/index.ts`
- `frontend/src/app/routes/HomeRoute.tsx`
- `frontend/src/shared/testdoubles/handlers/cp.ts` (shared — scoped touch, see above)
- `frontend/src/app/dev/devPackageHandlers.ts` (shared — scoped touch, see above)
- `frontend/e2e/journeys.spec.ts` (shared — appended one test, see above)
- `frontend/contracts/solution-package/generated/solution-package-validator.mjs` (regenerated build artifact,
  unrelated pre-existing breakage, see above)

## Open items / deviations

1. **Task 3's Playwright run is not executed** — written, documented why it couldn't run this session (no real
   backend in this repo's Playwright setup at all; port 5173 contention from a concurrent agent). Needs a re-run
   and its own `004-task-03-report.md` once the environment is free.
2. **D-1's resolution is per-attention-item, N calls, not batched.** The attention feed is expected to be small
   (it is "what needs your attention right now", not a full inventory), so this was a deliberate simplicity choice
   over building a batch-lookup endpoint or a shared instance cache — flagging it in case plan 007's instance
   repository later wants Home to reuse a shared cache instead of calling `getInstance` cold each time Home loads.
3. **D-2 remains open** — `configStagedNotApplied` has no wire source; the stat tile still reads "—". This is
   unchanged from before this plan and is explicitly out of this plan's power to close (it would need a new
   `console.yaml` field).
