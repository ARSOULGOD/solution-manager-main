# Integration plan 002 · Task 1 — Contract-conformance audit — Report

**Result:** ✅ done. One new test file, no production code changed, no behaviour changed.

## What was built

`frontend/src/shared/api/contractConformance.test.ts` (5 tests). It reads `contracts/openapi/console.yaml` (a small
indentation-based reader — no YAML dependency exists in the frontend, and none was added) and statically scans every
`cpClient`/`dpClient` call in `frontend/src` (test files, stories and `testdoubles/` excluded), normalising
`${id}` segments to `{}` and dropping query suffixes. Each call must be in the contract or in one of three
justified tables. The tables are a **two-way ratchet**: new unlisted drift fails, and a row that no longer matches a
real call (or is now in the contract) fails, so they cannot rot.

## Findings (45 non-contract calls scanned; 27 match the contract)

| Class | Items |
|---|---|
| **Real drift** (path exists in contract differently, or has no operation) | `POST /dp/solutions/{}/instances` → should be `declareInstance` (`POST /dp/environments/{environmentId}/instances`, OR-52); `GET /cp/solutions/{}/package` (no operation); `POST /dp/instances/{}/actions/{}` (no operation) |
| **No contract operation** (stay fixture-backed) | `/cp/admin/*` (roles, principals, users, groups, assignments, scope-tree, permission-catalog, effective-permissions); `/cp/connections*`, `/cp/connection-types` |
| **Contract ops the frontend never calls** | `declareInstance` (wrong route, above), `stopInstance`, `submitFleetApply`, `observeFleetApply` |
| **Outside `console.yaml` entirely** | `consoleClient` (`/api/console/v1/*`, Dynamic Metrics): the repo has **no OpenAPI document** for it — only the demo launcher implements it. Excluded from the scan, noted in the test. |

## Verification

- `npx vitest run src/shared/api/contractConformance.test.ts` → 5/5 pass; `tsc --noEmit` and `eslint` clean on the file.
- **Mutation proof (3 planted violations, all caught, then restored to green):**
  1. a new `cpClient.get('/nope/${1}')` call → `no new drift` test fails naming the file;
  2. deleting the `KNOWN_DRIFT` row for the declare route → fails, naming `instancesRepository.ts`;
  3. adding a `KNOWN_DRIFT` row nothing calls (`/dp/ghost`) → the stale-row test fails.
- Not run: the full frontend suite (4 min; this is one new standalone file that touches no production code). The pre-existing 25 `contracts`-project failures are unchanged and unrelated.

## Deviations from the plan as drafted

- **Step 3 replaced** (no `NOT_YET_SERVED` fixture-fallback). It would have been a behaviour change inside a "no behaviour change" task, and the plan's own Review Focus forbids silent fixture fallback. In real mode an unserved operation already 404s and the screen shows an honest error state (observed in the 2026-10-01 browser run). Recorded in the plan.
- The test resolves paths from `process.cwd()` (Vitest root = `frontend/`) because `import.meta.url` is not a `file:` URL under jsdom. Fine for `npm test`; would need care if the test were ever run from another directory.
- The audit reads `../contracts/openapi/console.yaml`, outside `frontend/`. CI (`.github/workflows/frontend.yml`) checks out the whole repo, but this is the first frontend test that depends on a sibling directory — worth knowing if the monorepo is split (CLAUDE.md).

## Follow-ups

- Task 4 must fix the declare route (local to `instancesRepository.ts`).
- Admin, connections and `/console/v1` need contracts before they can integrate; that is a ruling/plan, not a wiring task.
