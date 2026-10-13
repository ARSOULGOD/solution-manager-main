# FE-041 · Instance inventory grid — Report

## What I implemented

The table that proves `REQ-005`/`REQ-008`: runtime (`driver`) and locus (`control_locus`) are row
data, never a UI branch (task spec: `docs/plans/frontend/implementation.md` §FE-041; refs
`REQ-005`, `REQ-008`, `REQ-002`, `UI-D9`, `UI-D12`, `SM-D22`, `SM-D14`, design §9/§14, UI §A.6/§A.13).

| File | Summary |
|---|---|
| `src/features/inventory/instances/InstancesPage.tsx` | Orchestrates two queries: `instancesRepository.list(solutionId, {})` (primary — drives loading/error/empty/populated) and `solutionsRepository.list()` (secondary, non-blocking — resolves the page title from the solution's display name, falls back to the raw id if it hasn't resolved yet or fails). `PageHeader` action + empty-state action both render a **disabled** `Declare instance` button with a stated reason (see Ruling 3). |
| `src/features/inventory/instances/columns.tsx` | `buildInstanceColumns(solutionId)` — nine `ColumnDef`s. Health/version/config cells are dedicated render functions (each state genuinely renders differently — the `na`/`in_sync` special-casing is business logic, not the branching the acceptance criterion forbids); driver/locus cells are pure `driverVocab[row.driver]`/`locusVocab[row.locus]` lookups through `StateChip` — zero comparisons on either value anywhere in this file. |
| `src/features/inventory/instances/ReplicaDetailPanel.tsx` | The `DataTable` `detailPanel` for an expanded row: per-replica health cards, plus Version/Configuration explanation text reusing the exact `shared/vocab` wording the row's own cells read from. |
| `src/features/inventory/instances/instancesRepository.ts` | **Moved**, not created — see Ruling 1. |
| `src/features/inventory/instances/index.ts`, `columns.test.tsx`, `InstancesPage.test.tsx`, `InstancesPage.a11y.test.tsx` | Public barrel; vocabulary-coverage tests (29 cases); page tests (loading/error/empty/populated/expand/reflow); a11y (populated + empty, light/dark). |
| `src/app/routes/InstancesRoute.tsx` | Swapped the `RouteStub` placeholder for the real `InstancesPage`, reading `:solutionId` via `useParams` (404s via the existing `NotFound` if absent, matching `InstanceCategoryRoute.tsx`'s own param-validation precedent) and constructing both repositories — same call-site reasoning `SolutionsRoute.tsx` documents. |
| `src/shared/ui/DataTable/*`, `src/shared/ui/EnvChip/*`, `src/shared/i18n/messages.en.ts` | See Rulings 2, 4, 5. |

## Rulings

1. **`instancesRepository.ts` already existed, flat, at `src/features/inventory/instancesRepository.ts`** — a task-033 scaffold, fully built (`list`/`get`, `Paged<InstanceRow>`, a documented reason for omitting `declare()`) but never imported by anything except its own test. The exact same situation as `src/features/inventory/solutionsRepository.ts` (also orphaned by task 033, superseded when FE-039 built the real, wired `solutions/solutionsRepository.ts` — left in place, apparently not cleaned up then). Rather than duplicate it under `instances/` and leave a second orphan, I `git mv`'d it (plus its test) into `instances/`, matching where `solutions/solutionsRepository.ts` already lives. **I did not touch the sibling `inventory/solutionsRepository.ts` orphan** — out of this task's scope, flagged here for whoever next touches that folder.
2. **`DataTable`'s `ColumnDef` had no way to render a custom cell visual** — only `valueGetter: (row) => string`. FE-041 depends on task 020 (✅ shipped) but a status cell is inherently a `Chip`/`StateChip`, not a string. Added `renderCell?: (row: R) => ReactNode`, independent of `valueGetter` (which keeps driving sort order and the CSV cell) — a minimal, backward-compatible addition (every existing `DataTable` call site is unaffected), same posture as FE-039/040's own infra-gap rulings. Covered by a new `DataTable.test.tsx` case.
3. **`Declare instance` renders, disabled, with a stated reason — not omitted, not enabled-and-inert.** The plan's own Implementation bullet says "action present but see task 045," unlike FE-039/040's case (where the trigger wasn't mentioned in that task's own files at all, and omitting it was the right call). Task 045 owns the actual dialog ("no API, no field set... ship a dialog stating what is not yet specified") — building any dialog now would be doing 045's job, possibly inconsistently with what it ships. An enabled button with a silent no-op `onClick` was rejected: FE-039's own "an inert button is worse than no button" ruling applies just as much to a *dead click* as to a *missing* button. Disabled-with-reason is `FE-D9`'s own vocabulary (`Can.tsx`), stretched slightly here from "capability limit" to "not yet built" — but it's true, not fabricated, and prevents the dead click.
4. **Added `EnvChip` to `shared/ui`** (`ENV_TONE: DEV→neutral, TEST→info, PROD→purple`) — the reference's own `data.jsx` bundles this tone map directly alongside `HEALTH`/`NOTFOUND`/etc. (the same §A.13.1 "state vocabularies" concept), and `ChipTone`'s own doc comment already reserved `purple` for exactly this ("no task-013 vocabulary uses it yet"). Not folded into `shared/vocab` itself: `VocabEntry.icon` is required and env chips carry none (matching the reference, which renders `EnvChip` with no icon) — forcing icons on would diverge from the reference's own rendering. Built with the full Phase-1 component convention (types/component/index/test/a11y/gallery) since it's a new shared primitive, not a one-off.
5. **Fixed a real, pre-existing a11y violation in `DataTable`'s own expander column** (`empty-table-header` — the `__detail__` column's header cell had no accessible name), found by `InstancesPage.a11y.test.tsx` being the first test anywhere to run axe against a `DataTable` with both a populated grid *and* `detailPanel` together. Fixed with a visually-hidden `renderHeader` (inline CSS clip-rect — no new dependency) rather than a visible `headerName`, so the 56px icon-only column stays icon-only for a sighted user. **`DataTable` had no dedicated `DataTable.a11y.test.tsx` before this task — I wrote one.** Process note, not a code issue: I created it with `Write` without first confirming via a directory listing whether one already existed; it turned out one did (from the original Phase-1 commit, one basic case, no `detailPanel`). My version is a strict superset (kept the original case, added the `detailPanel`+axe case that caught the bug) — no coverage was lost — but the right process is `Read`/`Edit` an existing file, never `Write` over an unconfirmed path. Correcting the habit here rather than after a real loss.
6. **Did not add the "not-found: `restart_expected`" fixture row** even though `notFoundVocab` has 5 keys and `CACHE_INSTANCES` (task 033's fixture, "12 instances spanning EVERY... state") only exercises 4 of them — traced to the *reference's own* `data.jsx`, which never modeled that sub-case either (verified by reading it directly), so this isn't a task-033 porting slip. Extending the shared 12-row fixture would ripple into `instancesRepository.test.ts`'s `page.total === 12` assertion and the Home page's `instancesDeclared` count for no real benefit: the acceptance criterion ("all 5 not-found states render distinctly") is about the CELL RENDERER's coverage, which `columns.test.tsx` proves directly against `notFoundVocab`'s five keys — `allVocabEntries`/`Object.values(vocab)` table-driven testing is the vocab module's own established pattern (`shared/vocab/vocab.test.ts`), not something that needs a real fixture row per state.
7. **Skipped the reference's package-version chip in the page header title** (`Cache Engine · pkg v7`) — `PageHeaderProps.title` is typed `string`, not `ReactNode`; widening it would be a `PageHeader`-wide change affecting every consumer, well beyond this task. Title is the solution's plain name (or the raw `solutionId` before/if that lookup resolves).
8. **Did not build the reference's env-filter toggle, URL selection, bulk-restart dialog, or CSV column-visibility beyond what `DataTable` already provides** — all explicitly later tasks (FE-042 filters/URL state, FE-043 bulk restart). `DataTable`'s own search/density/CSV toolbar is already wired in from task 020, unchanged here.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run typecheck      # clean
npm run lint            # clean (0 warnings, --max-warnings=0)
npx vitest run          # 135 files, 597 tests, all passing (up from 130/549 before this task)
npm run build             # production build succeeds; InstancesRoute chunk 12.34 kB / 3.94 kB gzip
npx playwright test       # 5/5 e2e tests passing, no regression
```

Task-specific acceptance items, each backed by a concrete check:

- **All 5 health, 5 not-found, 4 version, 6 config states render distinctly** — `columns.test.tsx`,
  table-driven over every `shared/vocab` entry (29 cases total), including the `na`-renders-a-dash
  and `in_sync`-always-carries-the-qualifier special cases.
- **Zero `if (driver === …)` in the tree** — `columns.tsx`'s driver/locus cells are one-line
  `vocab[row.X]` lookups (verified by reading the file — no comparison exists); behaviorally proven
  by the driver/locus table-driven tests (all 3+3 values render through the one function).
- **`in_sync` always carries the honesty qualifier** — dedicated test asserting both "Applied" and
  "effective-unverified" render together.
- **Table-driven test over all 12 fixture instances** — `InstancesPage.test.tsx`'s populated test
  renders through the real `createInstancesRepository`/MSW path and asserts every one of
  `CACHE_INSTANCES`'s 12 names appears.
- **No branching on `driver`/`locus`** — same evidence as above.
- **Detail panel** — expander-click test asserts a replica name and the "Configuration" explanation
  heading both appear.
- **axe** — `InstancesPage.a11y.test.tsx` (populated + empty, light/dark) and a new
  `DataTable.a11y.test.tsx` (see Ruling 5); a 15s per-case timeout on the populated-grid cases
  (observed timeout under the full suite's parallelism at the 5s default, passes standalone).
- **Reflow at 320px** — dedicated `assertReflow` test.
- **Visually verified against the reference** — screenshotted the running dev server
  (`/solutions/cache/instances`, 1440×1000) via a Playwright script against `npm run dev` +
  `msw/browser`; all 12 rows, every column, and the expanded replica panel match
  `docs/design/frontend/reference/app/instances.jsx`'s layout and copy; 0 console errors. One minor,
  non-blocking cosmetic gap: the `Config` column truncates a couple of longer labels ("Staged ·
  restart req'd", "Rendered · not persisted") at its current fixed width — a column-width tune, not
  a rendering defect (full text is present and correct).

## Status

DONE — implemented, tested, lint-clean, typecheck-clean, full unit suite green (597 tests across
135 files), e2e green (5 tests, no regression), production build succeeds, visually verified against
the reference by screenshot. Not yet marked `✅` in `task-list.md`/`execution.md` or closed on
GitHub — pending review, particularly Ruling 1 (moving, not creating, `instancesRepository.ts`),
Ruling 2 (extending `DataTable`'s public `ColumnDef` type), Ruling 3 (`Declare instance` disabled
rather than omitted), and the noted build-time warning that the production `layout` chunk is now
~679 kB gzip ~202 kB — `@mui/x-data-grid` reaching a real (lazy-loaded) route for the first time via
the shared `shared/ui` barrel. No CI budget currently gates this; flagging as a follow-up
(barrel-level splitting or importing `DataTable` directly rather than through the barrel) rather
than fixing it here, since the root cause is the barrel-export pattern itself, not anything specific
to this task.
