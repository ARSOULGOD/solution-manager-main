# FE-038 · Home — Report

## What I implemented

*"What needs my attention"* — not an aggregate dashboard — plus the first-run package prerequisite (task spec:
`docs/plans/frontend/implementation.md` §FE-038; refs `REQ-001`, `UI-D8`, design §9, UI §A.4).

| File | Summary |
|---|---|
| `src/features/home/HomePage.tsx` | Orchestrates two `useQuery` calls (`repository.summary()` / `.attention()`) via `queryKeys.home.*`. Derives one of five states: loading (single skeleton), error (`ErrorPanel` + retry), first-run (`summary.solutionsManaged === 0`), partial (warn banner + `"N +1?"`), populated (strip + feed + the no-alerting footer line). |
| `src/features/home/StatStrip.tsx` | Four tiles. Three (`Solutions managed`, `Instances declared`, `Config staged`) are real `Link`s; `Need attention now` is not — ported from the reference's own `home.jsx`, where only three `Stat`s carry an `onClick`. Unknown counts render `—`, never `0` (`UI-D8`). |
| `src/features/home/AttentionFeed.tsx` | Panel with `N items · newest first`, one deep-linking row per item (a real `Link`, not a `div`+`onKeyDown`), severity icon/colour from `theme.palette.tone` (`FE-D5`). Empty feed renders an explicit "Nothing needs attention" notice, not a blank panel. |
| `src/features/home/FirstRun.tsx` | Package icon, heading, body copy, a 3-tile grid (Solutions/Instances/Targets, all `—`), and either a `Load a package` action (super-admin) or an ask-your-administrator alert (`UI-D19`). Pure/presentational — takes `superAdmin: boolean` as a prop. |
| `src/features/home/icons.tsx` | `PackageIcon`/`GridIcon`/`ServerIcon`/`MapPinIcon`/`UploadIcon`/`ChevronRightIcon`/`LockIcon` — filled inline `SvgIcon`s matching this codebase's own icon convention (see Ruling 1). |
| `src/features/home/homeRepository.ts` | Extended `HomeSummary` with an optional, explicitly-flagged `partial?: { unreportingHost: string } | null` field (see Ruling 2). |
| `src/features/home/index.ts` | Public barrel — `HomePage`, `createHomeRepository`, `HomeRepository`, `HomeSummary`. |
| `src/app/routes/HomeRoute.tsx` | Swapped the `RouteStub` placeholder for the real `HomePage`, constructing `createHomeRepository(createCpClient(config.apiBasePath))` via `useMemo` — a feature file cannot import `@app/*` (boundaries), so the app-layer route is the required call site (same pattern `LoginRoute.tsx` documents for itself). |
| `src/shared/ui/index.ts` | Barrelled `ToneIcon` (previously reached only via `internal/toneIcons.tsx` by `Alert`/`StateChip`) — Home is the first *feature* consumer of the tone-coloured icon set. |
| `src/shared/i18n/messages.en.ts` | Added the `home*` message keys (title, subtitle, stat labels, attention copy, footer line, first-run copy, partial-banner copy). |

Five component-level test files plus one a11y file, 20 tests total, all backed by real assertions (not
snapshot-only): `StatStrip.test.tsx`, `AttentionFeed.test.tsx`, `FirstRun.test.tsx`, `HomePage.test.tsx`,
`HomePage.a11y.test.tsx`.

## Rulings

1. **The new icons are filled `SvgIcon`s, not the reference's stroke-based Lucide set.** The reference's
   `app/icons.jsx` draws a `stroke="currentColor"` Lucide-style set; this codebase's own precedent
   (`app/shell/icons.tsx`, `shared/ui/internal/toneIcons.tsx`) already re-authored every icon as a single filled
   `<path>` — deliberately, not an oversight (neither file reuses the reference's raw path data). Matched the
   codebase's own established convention rather than reintroducing a second icon style, per the memory that
   screens should match the reference (layout/spacing/copy/behaviour) — not necessarily its raw asset format
   where the codebase has already made a different, working choice.
2. **`HomeSummary.partial` is an invented field**, flagged the same way every other `CONTRACT: TBD` seam in this
   plan is flagged. §16 Q10 marks the whole Home endpoint TBD; there was no existing mechanism to express "which
   host hasn't reported" (needed for the partial-state banner + `"N +1?"` suffix the Implementation bullet calls
   for) without it. `cp.ts`'s default MSW handler was left unchanged (`partial` absent = the populated case);
   the partial state is exercised via a stub `HomeRepository` in tests, not a new MSW handler.
3. **"Need attention now" does not link anywhere**, even though the plan's own prose says "Each [tile] links into
   a filtered view." The reference's `home.jsx` — the concrete, working implementation — gives only three of the
   four `Stat`s an `onClick`; I matched the reference over the prose summary, since the feed directly below
   already names the specific thing this tile would filter to. Flagging per the visual-reference memory's "flag
   any deviation" instruction — this is a deviation from the plan's prose, not from the reference.
4. **The "Config staged" tile links to one hardcoded fixture instance** (`/solutions/cache/instances?focus=cache-stage-01`),
   ported verbatim from the reference. Not invented: FE-042 (URL state) makes filters component state and only
   selection/focus URL state, so there is no addressable "staged" filter to link to at all in this app's design —
   this is the only mechanism available, not a shortcut.
5. **The reference's second "Learn about packages" button is not ported.** It has no destination anywhere in this
   plan or the design docs. An inert button that does nothing on click is worse than one fewer button — kept only
   the super-admin's real action (`Load a package`, navigating to `/solutions`).
6. **`FirstRun` takes `superAdmin` as a prop, not via its own `useAuth()` call.** Keeps it pure/presentational and
   trivially testable without touching auth context; `HomePage` is the one place that reads `identity?.superAdmin`.
7. **First-run detection rule (`summary.solutionsManaged === 0`) is this task's own invention**, not a server flag
   — consistent with Ruling 2, since §16 Q10 leaves no real signal to read instead.
8. **Discovered and fixed, in scope, that the dev server has no way to render any fixture-backed screen in a real
   browser** — MSW only runs via `setupServer` (`msw/node`) inside Vitest; `src/app/main.tsx` never starts an
   `msw/browser` worker. This was invisible until now because no earlier Phase 3 screen (`AppShell`, `LoginPage`)
   calls `useQuery` against a repository. I verified this task's actual rendering with a temporary, uncommitted
   `msw/browser` worker wired into `main.tsx` (plus a generated `public/mockServiceWorker.js`), screenshotted all
   reachable states, then reverted both files exactly — `git status` confirms nothing from that check leaked into
   the diff. Flagging this as a real gap for whoever picks up the next Phase 3 screen (039+): the same blind spot
   will recur immediately.
9. **Checked for the `console.error`-in-e2e regression this task could plausibly cause** (`e2e/smoke.spec.ts`'s
   `expect(errors).toEqual([])` — the first e2e test to hit a route that now makes real fetch calls against a
   backend-less preview server) and confirmed it's a non-issue: `shared/api/http.ts`'s one `console.error` call
   fires only on a network-level failure (fetch itself throwing), never on an HTTP error status — the preview
   server answers with a real (if wrong) HTTP response, so the query fails into `ErrorPanel` silently, exactly as
   designed. Full e2e suite (5 tests) still passes.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run typecheck      # clean
npm run lint            # clean (0 warnings, --max-warnings=0)
npx vitest run          # 124 files, 515 tests, all passing
npm run build            # production build succeeds; HomeRoute chunk 19.68 kB / 6.55 kB gzip
npx playwright test      # 5/5 e2e tests passing (login + smoke), no regression
```

Task-specific acceptance items, each backed by a concrete test:

- **`—` not `0`** — `StatStrip.test.tsx` (`renders an unknown count as "—", never "0"`), `FirstRun.test.tsx`,
  `HomePage.test.tsx`'s first-run test.
- **Empty ≠ error** — `AttentionFeed.test.tsx`'s empty-feed test (success notice) vs `HomePage.test.tsx`'s
  error-panel test (retry button, distinct copy) — structurally unable to render simultaneously (mutually
  exclusive top-level branches).
- **No-alerting statement present** — `HomePage.test.tsx`'s populated-view test.
- **Every feed row deep-links** — `AttentionFeed.test.tsx` asserts each row is a real `<a href>` matching the
  fixture's own `href`.
- **Five demo states** — `HomePage.test.tsx` (loading, error, first-run, partial, populated) plus
  `HomePage.a11y.test.tsx` (populated + first-run, light and dark).
- **Reflow at 320px** — `StatStrip.test.tsx`'s dedicated `assertReflow` test (the fixed-looking 4-tile strip is
  actually the responsive `Grid` primitive, `xs:1 → sm:2 → lg:4`).
- **Visual match against the reference** — screenshotted via a temporary local `msw/browser` worker (Ruling 8);
  stat strip, attention feed, severity colours/icons and footer line all visually match
  `docs/design/frontend/reference/app/home.jsx`.

## Status

DONE — implemented, tested, lint-clean, typecheck-clean, full unit suite green (515 tests across 124 files),
e2e green (5 tests, no regression), production build succeeds, visually verified against the reference by
screenshot (Ruling 8). Not yet marked `✅` in `task-list.md`/`execution.md` or closed on GitHub (#50) — pending
review, particularly Ruling 3 (the "Need attention now" tile's non-clickability, a deviation from the plan's
prose) and Ruling 8 (the dev-server MSW-browser gap, worth a follow-up task before FE-039+ hits the same wall).
