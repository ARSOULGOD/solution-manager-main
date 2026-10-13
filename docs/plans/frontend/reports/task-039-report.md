# FE-039 · Solutions catalog — Report

## What I implemented

The package cards, including the two things that carry trust weight: provenance and authoring
review (task spec: `docs/plans/frontend/implementation.md` §FE-039; refs `REQ-001`, `REQ-002`,
`UI-D11`, `UI-D17`, `UI-D19`, design §9, UI §A.5).

| File | Summary |
|---|---|
| `src/features/inventory/solutions/SolutionsPage.tsx` | Orchestrates one `useQuery` (`repository.list()`) via `queryKeys.solutions.list()`. Loading → 3-card skeleton grid; error → `ErrorPanel` + retry; empty → `EmptyState`; populated → `Grid` of `SolutionCard`. |
| `src/features/inventory/solutions/SolutionCard.tsx` | One package card: icon avatar, name, entitlement chip, provenance chip + `InfoTip` (signature fingerprint or the no-signature reason), package version / applies-to / source `dl`, `ReviewMeter`, component pills, footer (instance count, telemetry mode, Open). Unsigned packages get a conspicuous top banner and a disabled, tooltip-explained Open button that never fires `onOpen`. |
| `src/features/inventory/solutions/ReviewMeter.tsx` | `reviewedRows / totalRows` in three renderings: fully reviewed (ok, no qualifier), partial (warn, "the remainder auto-extracted — a normal state"), zero (err, "assembled by automated extraction, unreviewed"). The bar is `aria-hidden` — the caption text carries the same information for a screen reader, so the decorative bar isn't announced twice. |
| `src/features/inventory/solutions/icons.tsx` | `BoltIcon`/`FilesIcon`/`BeakerIcon` (the three known `solution.icon` fixture values) behind a `SolutionGlyph` lookup that falls back to a generic `PackageGlyphIcon` for an unknown value — the fixture's `icon` field is an open `string`, not a closed enum. Plus `ServerIcon`/`ActivityIcon`/`ArrowRightIcon` for the footer/Open button. Provenance and review-state icons reuse the shared `ToneIcon` vocabulary (`ok`/`warn`/`err`) instead of one-off glyphs. |
| `src/features/inventory/solutions/solutionsRepository.ts` | `SolutionsRepository.list()` → `GET /solutions`. `SolutionPackageSummary` is re-exported from the fixture module (task 033) — CONTRACT: TBD, `§16 Q10` — same pattern `homeRepository.ts` uses for `AttentionItem`. |
| `src/features/inventory/solutions/index.ts` | Public barrel — `SolutionsPage`, `createSolutionsRepository`, `SolutionsRepository`, `SolutionPackageSummary`. |
| `src/app/routes/SolutionsRoute.tsx` | Swapped the `RouteStub` placeholder for the real `SolutionsPage`, constructing `createSolutionsRepository(createCpClient(config.apiBasePath))` — same call-site reasoning `HomeRoute.tsx` documents (a feature cannot import `@app/*`). |
| `src/shared/i18n/messages.en.ts` | Added the `solutions*` message keys (title/subtitle, error/empty copy, provenance labels and tooltips, review-meter labels/captions). Reused the existing `instanceCount` and `errorGeneric` keys rather than adding near-duplicates. |
| `src/app/main.tsx` | See Ruling 1 — made the dev-mode MSW browser bootstrap permanent instead of a discard-before-commit hack. |
| `public/mockServiceWorker.js`, `package.json` (`msw.workerDirectory`) | Generated via `npx msw init public --save` — the vendor worker script `msw/browser` needs to intercept `fetch` in the dev server, and the directory MSW itself writes into its own config. |
| `eslint.config.js` | Added `public/**` to the root `ignores` — the generated worker script isn't project source and has no `tsconfig` project membership, so lint was erroring on a parse failure for a file nothing should ever hand-edit. |

Five test files, 18 tests total: `ReviewMeter.test.tsx` (3), `SolutionCard.test.tsx` (4),
`SolutionsPage.test.tsx` (6, including a reflow test at 320px), `SolutionsPage.a11y.test.tsx` (4,
light/dark × populated/empty).

## Rulings

1. **Found the previous session's task-038 "TEMP, reverted before commit" MSW-browser hack still
   sitting uncommitted in `main.tsx`, plus its generated `public/mockServiceWorker.js` untracked
   on disk** — the task-038 report's Ruling 8 claimed both were reverted; `git status` at the
   start of this task showed they were not. Rather than repeat the same discard-and-redo cycle
   (which had already silently failed once), made the bootstrap permanent: `main.tsx` starts
   `msw/browser` behind a **static** `if (import.meta.env.DEV)` — Vite replaces that flag as a
   literal at build time, so a production build dead-code-eliminates the whole branch. Verified
   directly, not assumed: `npm run build` then `grep -rl "mockServiceWorker|setupWorker|msw/browser"
   dist/assets/*.js` found nothing, and the built bundle's `index.js` doesn't reference the
   fixture `handlers` module either. `public/mockServiceWorker.js` is regenerated (not hand-edited)
   via `npx msw init public --save`, matching the checked-in `msw` version (2.15.0). This unblocks
   every FE-040+ task from hitting the identical "no way to view a fixture screen in a real
   browser" wall — flagging that as resolved now rather than "worth a follow-up task" (task-038's
   phrasing).
2. **The "Load a package" trigger is not rendered anywhere in this task** — neither the page
   header nor the empty state. `FE-040`'s own Implementation bullet describes the drop-zone
   dialog and its trigger as one deliverable, and `FE-039`'s own Files list doesn't include a
   dialog component. A button with no dialog behind it is worse than one fewer button — the same
   ruling `features/home/FirstRun.tsx` already documents for its own inert-button case (task-038
   report, Ruling 5). `FE-040` adds the header action once the dialog exists; the empty state
   (unreachable today — the fixture is never empty) renders explanatory copy only, with no
   role-gated action, for the same reason.
3. **`ReviewMeter`'s progress bar is `aria-hidden`.** The caption directly below it
   ("`1,287 / 1,494 rows reviewed · the remainder auto-extracted — a normal state`") already
   states the same percentage and qualifier in text; a `role="img"` with a redundant
   `aria-label` would have announced the number twice. The review-state `Chip` (with its own
   `ToneIcon`) carries the state's name for a screen reader; the bar is decoration.
4. **Provenance and review-state chip icons reuse the shared `ToneIcon` vocabulary** (`ok`/`warn`/
   `err`) rather than porting the reference's bespoke `badgeCheck`/`alert`/`check` Lucide glyphs.
   Same reasoning as task-038's Ruling 1 (matched this codebase's own established convention —
   `Alert`/`StateChip` already share one tone-icon set) with one refinement: reusing the *existing*
   set outright, not authoring three new bespoke ones, since a signed-package check and a
   fully-reviewed check are both just "ok" in the same vocabulary the rest of the app already
   uses for that concept.
5. **`SolutionsPage` hardcodes `/solutions/${id}/instances` rather than importing the app-layer
   `routes` helper**, even though `routes.ts`'s own header comment says feature code should build
   links through `routes.*` "never a hand-written template string". `features/home/StatStrip.tsx`
   already establishes the actual precedent: a feature module cannot import `@app/*` (architecture
   guardrails, task 009), so `routes.ts` is unreachable from feature code as written today. Matched
   the real, enforced constraint over the aspirational header comment — flagging the comment
   itself as stale for whoever next touches `routes.ts`.
6. **`icons.tsx`'s `SolutionGlyph` falls back to a generic glyph for an unrecognized `icon` value**
   rather than a TypeScript union, because `SolutionPackageSummary.icon` is typed `string` (task
   033's own fixture module) — a real package-schema field per `UI-D11`, not something this task
   can narrow without inventing a closed vocabulary the schema doesn't actually declare.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run typecheck      # clean
npm run lint            # clean (0 warnings, --max-warnings=0)
npx vitest run          # 128 files, 532 tests, all passing
npm run build            # production build succeeds; SolutionsRoute chunk 8.80 kB / 2.74 kB gzip
npx playwright test      # 5/5 e2e tests passing (login + smoke), no regression
```

Task-specific acceptance items, each backed by a concrete check:

- **Unsigned is conspicuous and non-navigable** — `SolutionCard.test.tsx`'s dev-mode tests (banner
  text present, Open button has the `disabled` attribute, clicking it never calls `onOpen`);
  `SolutionsPage.test.tsx`'s equivalent assertion against the real fixture's `cache-nightly` card.
- **0% ≠ 100% visually** — `ReviewMeter.test.tsx` asserts the zero state renders "Not reviewed"
  and never "Fully reviewed" in the same tree; distinct tone (`err` vs `ok`) and distinct fill
  colour on the bar.
- **Review fields marked TBD in source** — `solutionsRepository.ts`'s header comment plus the
  fixture module's own (task 033) header both flag `authoring.reviewedRows`/`totalRows` as
  invented, `§16 Q10`.
- **Three review states** — `SolutionsPage.test.tsx`'s fixture-backed test asserts all three
  ("Fully reviewed", "86% reviewed", "Not reviewed") render simultaneously from the real
  `SOLUTIONS` fixture (`cache`, `file-mesh`, `cache-nightly`).
- **axe** — `SolutionsPage.a11y.test.tsx`, light and dark, populated (all three cards) and empty.
- **Reflow at 320px** — `SolutionsPage.test.tsx`'s dedicated `assertReflow` test on the populated
  card grid.
- **Visual match against the reference** — screenshotted the running dev server (`/solutions`,
  1440×1000, light and dark `colorScheme`) via a Playwright script against `npm run dev` +
  `msw/browser` (Ruling 1's permanent bootstrap, not a temporary one this time); 0 console errors
  in either mode. Card layout, provenance chips, review meter, dev banner and disabled Open button
  all visually match `docs/design/frontend/reference/app/solutions.jsx`. Note: the running app has
  no live dark-mode toggle yet (`createSmTheme('dark')` is only exercised directly in tests today,
  same as task-038's app) — the `colorScheme` browser context therefore didn't change the rendered
  theme; dark-mode correctness is verified via the a11y test suite's direct `mode: 'dark'` render,
  matching the established convention for this codebase.

## Status

DONE — implemented, tested, lint-clean, typecheck-clean, full unit suite green (532 tests across
128 files), e2e green (5 tests, no regression), production build succeeds and confirmed
tree-shaken of dev-only MSW code, visually verified against the reference by screenshot. Not yet
marked `✅` in `task-list.md`/`execution.md` or closed on GitHub (#51) — pending review,
particularly Ruling 1 (making the dev MSW bootstrap permanent, which also touches `package.json`
and `eslint.config.js` outside this task's own Files list) and Ruling 2 (omitting the "Load a
package" trigger entirely, deferred to FE-040).
