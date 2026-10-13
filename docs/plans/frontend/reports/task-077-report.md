# FE-077 · Explore shell and gated control steps — Report

## What I implemented

Refs `DM-D9` `DM-D23`, Design §11, `dynamic-metrics` §9.1/§11.1, UI §A.10. Deps `068`–`076` (chart/catalog
internals this shell composes around, none of which this task extends). The task's own `Files` list named
`ExplorePage.tsx`/`ControlStep.tsx`; the file layout below adds a small pure gating module and the route wiring
the pre-existing `ExploreRoute.tsx` stub explicitly named this task as owning — both explained under "Design
decisions".

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/explore/exploreGating.ts` (new) | `evaluateExploreGating({metricState, acrossInstances})` — the pure ordering rule ("metric untyped locks 2-4; no instance aggregation locks 5") as a testable function, mirroring task 076's `markAggregation.ts` split of rule-from-render. Returns reason **codes** (`'metric-not-selected' \| 'metric-needs-typing' \| 'aggregation-not-set'`), not translated copy — same split the reference's own `ERR_TEXT` makes for its 422 vocabulary. `StepLock` is a discriminated union (`{locked:false}` \| `{locked:true; reason}`) so a locked step can't exist without a reason code at the type level. |
| `frontend/src/features/monitoring/explore/exploreGating.test.ts` (new) | Table-driven: step 1 never locks; steps 2-4 lock on both `metricState: null` and `'needs-typing'` with the right reason each; unlock for `plottable`/`type-suspect` alike; step 5 locks/unlocks independently of metric state, across all four non-`none` aggregation values. |
| `frontend/src/features/monitoring/explore/ControlStep.tsx` (new) | The numbered, gated step chrome — badge, uppercase label, optional subtitle, and (when locked) a full-opacity reason line above dimmed `children`. `ControlStepProps` is a discriminated union (`locked` implies `lockedReason: string`) — "every locked step states why" is a compile error to violate, not a convention. |
| `frontend/src/features/monitoring/explore/ControlStep.test.tsx`, `.a11y.test.tsx` (new) | Step number/title/subtitle render; locked reason renders and the body still renders (dimmed, not hidden); unlocked renders no reason and no `data-locked="true"`; `jest-axe` in both colour modes, locked and unlocked. |
| `frontend/src/features/monitoring/explore/ExplorePage.tsx` (new) | The `340px 1fr` grid (single column below `lg`, matching the reference's own breakpoint intent — task 109 owns the deeper responsive/container-query treatment). Renders the "Explore ← Monitoring" header and all five `ControlStep`s, driven by one `evaluateExploreGating({metricState: null, acrossInstances: 'none'})` call. Every step body is a stated placeholder Typography line ("… is not available yet.") — tasks 078-081 build the real controls. The result column is a `Paper`+`EmptyState` placeholder, not a fabricated chart. |
| `frontend/src/features/monitoring/explore/ExplorePage.test.tsx`, `.a11y.test.tsx` (new) | All five step titles render in order; steps 2-4 show the "Pick a metric first." reason (3 occurrences); step 5 shows the aggregation reason; step 1 is never locked (`data-locked="false"`); the result placeholder renders instead of a chart; the back link resolves to `/solutions/{sol}/instances/{id}/monitoring`; `jest-axe` in both modes. |
| `frontend/src/features/monitoring/index.ts` (new) | The feature's public barrel — exports only `ExplorePage`/`ExplorePageProps`. Required by `boundaries/dependencies` (`eslint.config.js`): `app -> feature` is only allowed through `fileInternalPath: 'index.ts'`, and `src/features/monitoring/` had no barrel yet (task 033/068-076 never needed one — their consumers were other files inside the same feature). |
| `frontend/src/app/routes/ExploreRoute.tsx` (modified) | Was a `RouteStub` whose own comment read *"Placeholder — real page is task 077 (Explore shell)"*. Replaced with the `useParams` → `ExplorePage` wiring, same undefined-check-then-`NotFound` shape `InstanceCategoryRoute.tsx` uses. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | New keys: `exploreTitle`, `exploreBackToMonitoringAriaLabel`, three `exploreLockReason*` strings, five step title/subtitle/placeholder groups, two `exploreResultPlaceholder*` strings. |

## Design decisions, and why

1. **Gating rule lives in its own pure module, not inlined in `ExplorePage`.** Same reasoning task 076's decision 5
   gives for `markAggregation.ts`: the acceptance box ("ordering matches the contract's evaluation order") is a
   claim about *logic*, and logic is what's actually testable in isolation. `evaluateExploreGating` returns reason
   **codes**; translating them to copy happens only at the render site (`ExplorePage`'s `LOCK_REASON_KEY` map),
   keeping the rule itself free of `@shared/i18n` and reusable — task 082 (typed `422` rendering) can reuse these
   same codes as UI-only companions to the server's SCREAMING_SNAKE 422 vocabulary without the two colliding: these
   three fire client-side before any query is issued (an empty/untyped selection never reaches the server), so
   they are deliberately lower-kebab and distinct from `METRIC_UNTYPED`-style codes.

2. **`metricState: null` is `ExplorePage`'s real starting state, not a placeholder value.** No `MetricSelector`
   (task 079) exists yet to choose a metric, so "nothing selected" is the *genuinely correct* state of this page
   today — steps 2-4 are correctly locked on first paint via the same `evaluateExploreGating` call task 079/080
   will later drive with live state. This is why the acceptance box is real and tested now rather than deferred:
   the gating mechanism doesn't need a working `MetricSelector` to be correct, only a state to gate on.

3. **`ControlStep` corrects a defect in the reference rather than reproducing it.** The reference's
   `.ctl-step.locked{opacity:.5;pointer-events:none}` dims a locked step but renders no reason anywhere — exactly
   the gap this task's acceptance box exists to close — and `pointer-events:none` alone leaves any focusable
   control inside still keyboard-reachable (dimmed, not actually disabled). `ControlStep` renders the reason at
   full opacity (never dimmed) and only dims `children`; it documents (in its own header comment) that the
   *caller* is responsible for passing `disabled` to whatever real, focusable control it renders inside a locked
   step — a wrapper `Box` cannot disable arbitrary children it did not create. This is a contract tasks 078-081
   need to honour when they replace today's placeholder `Typography` lines with real inputs.

4. **`GatedStep` (private, in `ExplorePage.tsx`) exists to avoid spreading a discriminated union into JSX.**
   `ControlStepProps` is `{locked?:false}` \| `{locked:true; lockedReason:string}` on purpose (decision above);
   spreading a `StepLock`-derived object into `<ControlStep {...x}>` across five call sites risks exactly the
   TypeScript union-spread ambiguity a plain `if (lock.locked)` branch avoids outright. `GatedStep` takes the
   `StepLock` object directly, branches once, and keeps every call site in `ExplorePage` a flat, readable prop
   list. Not exported — it is not part of this task's `Files` list and has no reason to be used outside this one
   page.

5. **Wired `ExploreRoute.tsx` and added `src/features/monitoring/index.ts`, neither named in the task's own
   `Files` list.** Building `ExplorePage.tsx` without wiring it in would leave the file dead code, and the
   pre-existing stub's own comment (`// Placeholder — real page is task 077 (Explore shell).`, present before this
   task started) already assigned that swap to this task — the same "swap it, don't extend it" convention
   `providers.tsx`'s forward-referenced placeholders and `router.tsx`'s other `RouteStub` files already follow.
   The barrel is a mechanical consequence: `eslint-plugin-boundaries` only allows `app -> feature` through a
   feature's `index.ts`, and `src/features/monitoring/` didn't have one yet (nothing outside the feature had
   needed to import from it before now).

6. **`ExploreRoute` does *not* wrap `ExplorePage` in `InstanceHeader`/`InstanceShell`, unlike the reference's own
   `Wrap`.** `routes.ts`'s own comment establishes `explore` as a **static sibling of `instanceCategory`**, not one
   of its `designer`/`governance`/`monitoring` categories — so there is no existing "instance chrome + tabs"
   wrapper this route already goes through the way `InstanceCategoryRoute.tsx` does via `InstanceShell`. Matching
   the reference's chrome exactly would mean re-fetching the instance and duplicating `InstanceShell`'s
   query/skeleton/error-panel lifecycle for a route that isn't an instance category — real, non-trivial work this
   task's `Files` list (`ExplorePage.tsx`, `ControlStep.tsx`) does not cover, and not something to invent silently.
   Flagged in `ExplorePage.tsx`'s own header comment as an open residual for whichever task (likely alongside 085
   or a dedicated follow-up) resolves it, rather than guessed at here. Today's page carries its own minimal
   "Explore ← Monitoring" wayfinding instead.

7. **The two-column grid is a plain `sx` breakpoint switch (`1fr` below `lg`, `340px 1fr` at `lg`+), not the
   `Sidebar` layout primitive.** `Sidebar`'s rail width is hard-coded to the shell's own `var(--drawer-w)` (256px,
   the app-shell nav-drawer token) — reusing it here would either misuse a semantically-named token for an
   unrelated 340px column or require changing `Sidebar` itself, out of scope for a feature-local page. Task 109
   ("Responsive Monitoring — Explore and dashboard grid") is the task actually scoped to the deeper responsive
   treatment (container queries, matching `WidgetFrame`'s own breakpoints); this task's grid is deliberately just
   enough to not break on a narrow viewport.

8. **Colours are theme semantics, not copied hex.** The reference's `.num` badge (`--primary-100` bg / `--primary`
   text, unlocked) and its locked variant (`--secondary-w20` / `--secondary-text-w50`) map to
   `alpha(theme.palette.primary.main, 0.12)`/`theme.palette.primary.main` and
   `theme.palette.action.disabledBackground`/`theme.palette.text.disabled` respectively — the same
   reason-from-token approach `GaugeMark.tsx` (task 076) already established (`theme.palette.action.hover`,
   `theme.palette.tone.warn.main`), not a fresh hex literal (`palette.ts`'s own stated rule).

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json --noEmit && npx tsc -p tsconfig.node.json --noEmit   # clean
npm run lint                                                                  # clean (project-wide, --max-warnings=0)
npx vitest run src/features/monitoring/explore/ --environment jsdom          # 5 files, 24/24
npx vitest run src/app/router.test.tsx src/app/routes/ src/features/monitoring/ --environment jsdom
                                                                               # 23 files, 147/147
npm test                                                                      # full suite: 261 files, 1538/1538
npm run build                                                                 # succeeds; echarts stays isolated to its own chunk;
                                                                               # ExploreRoute is now a real ~4kB chunk, not the old stub
```

One re-run of `npm test` (after the route-wiring changes) reported `260 passed (261)` with
`InstancesPage.a11y.test.tsx` timing out at 15000ms under full-suite load — that file's own comment already
documents it as environment-load-flaky ("passes in isolation, times … only alongside the other 130+ files"),
unrelated to any file this task touches. Re-run in isolation: 1 file, 6/6, 14s. Not this task's regression.

Task-specific acceptance items:

- **Every locked step states why** — enforced at the type level (`ControlStepProps`'s discriminated union) and
  exercised at runtime: `ExplorePage.test.tsx` asserts three simultaneous "Pick a metric first." reasons (steps
  2-4) and one "Turn on aggregation across instances first." reason (step 5) render on first paint.
- **Ordering matches the contract's evaluation order** — `exploreGating.test.ts` is the direct, renderer-free proof
  of `DM-D9`'s stated order ("metric untyped locks 2-4; no instance aggregation locks 5"), including that the two
  gates are independent (an unselected metric does not lock group-by; a non-`none` aggregation does not unlock an
  untyped metric).

### Not fully browser-verified

No dev server was started to click through the route; verification is the unit/a11y/route-matching suites above
plus the production build succeeding with `ExploreRoute` now a real chunk. Every later Explore control task
(078-082) will exercise this same page again as it fills in the placeholder bodies, the same position task 071's
and 076's reports recorded for their own not-yet-consumed pieces.

## Status

DONE — implemented, tested (261/261 files, 1538/1538 tests full-suite; the touched areas individually re-verified
above), lint-clean, typecheck-clean, production build succeeds. Not yet marked done in `task-list.md`/
`execution.md` or committed — pending user confirmation. GitHub issue for `FE-077 · Explore shell and gated
control steps` — to be closed on commit.
