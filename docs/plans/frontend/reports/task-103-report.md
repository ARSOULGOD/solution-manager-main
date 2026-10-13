# FE-103 · Performance verification — Report

## What I implemented

FE-103 is *"tests, not assertions"*. The first useful thing I did was find out
how much of it was already true: **four of the six guarantees were already
proven** by earlier tasks. Reimplementing them would have created two places to
update and no extra confidence, so this task adds the two that were missing,
records the budgets that did not exist, and gathers all six under one named
command.

Task spec: `docs/plans/frontend/implementation.md` §FE-103. Refs design §17, §22
· `dynamic-metrics` §13.

| Guarantee | Where it is proven | New? |
|---|---|---|
| Off-screen widgets issue no request | `WidgetFrame.test.tsx` ("issues no request for an off-screen widget", drives a `NeverIntersectingObserver`) | existing (086) |
| **8 widgets over 4 metrics issue 4 requests** | `dashboardPerformance.test.tsx` | **new** |
| Refresh floored at the served bucket | `refreshPolicy.test.ts` (`flooredRefreshSeconds`, `estimatedBucketSeconds`) | existing (085) |
| **Refresh paused while the tab is hidden** | `dashboardPerformance.test.tsx` | **new** |
| **12-row grid and ~100-field form render budgets** | `renderBudgets.test.tsx` | **new** |
| Legend virtualises rather than truncating | `ChartRenderer.test.tsx` ("never truncates membership", "bounds height by legendMaxHeightRows without reducing membership") | existing (075) |
| Free text debounced, enums not | `FilterBar.test.tsx` ("debounces free text and applies enum immediately") | existing (020) |

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/dashboard/dashboardPerformance.test.tsx` | New. Dedup (8 widgets / 4 metrics → 4 requests, **and** the converse: 8 distinct metrics → 8 requests), plus refresh pausing while hidden. |
| `frontend/src/test/performance/renderBudgets.test.tsx` | New. Two budgets with a recorded baseline table and an explicit statement of what a jsdom millisecond is worth. |
| `frontend/scripts/verify-perf.mjs` | New. `npm run verify:perf` — names all six guarantees, **fails if a named file stops existing**, then runs them. |
| `frontend/package.json` | `verify:perf` script. |
| `.github/workflows/frontend.yml` | `verify:perf` added to the `gate` matrix (no browser needed — it is all Vitest). |

## Rulings

**1 · Dedup is tested in both directions.** "Eight widgets over four metrics
issue four requests" is satisfied by a cache that collapses *everything* to one
request, which would be a serious bug showing one metric's data under another's
title. The suite therefore also asserts that eight widgets on eight **distinct**
metrics issue eight requests. And the first test checks not just the count but
*which* four metrics were requested — a count of 4 is also what you get from
four requests for one metric plus three widgets that silently failed to query.

**2 · The pause-when-hidden test was wrong before the app was.** My first
version mocked `document.hidden` and reported four requests where one was
expected — which reads exactly like "refresh does not pause". It was the test:
TanStack Query's `focusManager` reads
`globalThis.document?.visibilityState !== 'hidden'`
(`@tanstack/query-core/build/modern/focusManager.js:59`), not `document.hidden`.
Verified in the dependency's source rather than assumed, and the corrected test
passes against unchanged app code. The comment in the test records which
property is load-bearing so the next person does not repeat it.

This is the second time in two tasks that a plausible-looking failure was the
harness rather than the subject. The general rule — check the instrument before
believing the reading — is already `docs/tasks/lessons.md` R5/R6 from FE-101;
this is another instance of it, not a new lesson.

**3 · The render budgets are jsdom tripwires, and the test says so.** jsdom has
no layout, no paint, no compositor and no real font metrics; a millisecond here
is not a millisecond in a browser, and a green number says nothing about frame
timing on an operator's machine. What it does catch is the class of regression
that moves a render by 10× — an O(n²) over rows, a per-field context
subscription, a form that re-renders wholly on every keystroke. Recording that
honestly in the file is the difference between a useful tripwire and a
misleading "performance test".

**4 · The grid budget is a RATIO, because the absolute one failed — and the
failure was right.** I measured three unloaded runs (342 · 348 · 409 ms), set a
1500 ms ceiling at ~4× headroom, and it passed in isolation. In the full suite
it failed at **1679 ms** — the identical render, five times slower purely from
contention with 300-odd other test files. I had measured in the quiet case and
asserted in the loud one, which is exactly the mistake the file's own header
warns about, made in the file that warns about it.

Raising the ceiling to 6000 ms would have gone green and been worthless: an
absolute millisecond budget in jsdom measures how busy the machine is, not how
expensive the render is.

So the grid budget now measures **scaling**. It renders one row and twelve rows
in the same process, moments apart, and asserts twelve rows cost no more than
3× one row. Contention inflates both measurements and cancels out; the
regressions this exists for do not:

| Regression | Effect on the ratio |
|---|---|
| O(n²) pass over rows | explodes |
| per-row subscription or observer | grows linearly |
| per-row layout read (forced reflow) | grows linearly |

A healthy Data Grid is dominated by fixed mount cost, so the honest expectation
is a ratio near 1 — which is what it measures. The projection budget stays
absolute at 150 ms, because at 7–11 ms it is stable enough that contention does
not move it past the ceiling; verified, it passed in the same full-suite run the
grid failed.

**Recorded baselines (2026-09-03, Node 22, jsdom):**

| Subject | Isolated | Under full suite | Budget |
|---|---|---|---|
| 12-row grid | 342 · 348 · 409 ms | 1679 ms | ratio ≤ 3× the 1-row render |
| 100-field projection | 7 · 8 · 10 ms | passed | 150 ms absolute |

My first draft had used placeholder ceilings of 4000 ms and 6000 ms — against an
8 ms projection, a 600× ceiling nothing could ever breach. Measuring first, then
setting the number from the measurement, then checking it survives the
conditions it actually runs in, is what "budgets recorded" has to mean.

**5 · The grid budget mounts the real MUI X Data Grid, and the projection budget
does not mount anything.** The two numbers are two orders of magnitude apart
because they are measuring different things: the grid budget is dominated by
Data Grid mount cost, and the projection budget isolates `project()` — the
engine every Designer render goes through (`FE-D17`), doing the schema walk,
`when` evaluation, section grouping and RBAC overlay. Keeping them separate is
what makes either number legible; a combined figure would be the grid's number
with the form's work hidden inside its noise.

**6 · Each budget asserts the work actually happened.** A budget met by
rendering nothing is the obvious failure mode, so the grid test asserts a row is
on screen and the projection test asserts 100 fields across 10 sections came
out.

**7 · `verify:perf` fails when a named target disappears.** A bare
`vitest <globs>` silently shrinks when a file is renamed — the suite goes green
with less in it. The wrapper checks each named path exists first and fails with
*what that file was proving*, so a rename forces a decision rather than quietly
reducing coverage. Same reasoning as `assert-lint-fixtures.mjs` checking that
fixtures still fail.

**8 · The 100-field package is built, not fixtured.** A hundred rows of invented
config committed to `shared/testdoubles/` purely for a timing test would be
exactly what `verify:production`'s `no-fixture-dataset` assertion exists to keep
out of the bundle, and it would have to be maintained. Generating it in the test
keeps the invented data next to the one thing that uses it.

## Acceptance

- ☑ **dedup proven** — 8 widgets / 4 metrics → 4 requests, by metric name, plus
  the converse (8 distinct → 8).
- ☑ **visibility gating proven** — off-screen issues no request
  (`WidgetFrame.test.tsx`, existing), and a scheduled refresh does not fire
  while `visibilityState` is `hidden` (new).
- ☑ **no truncated legend** — `ChartRenderer.test.tsx`'s "never truncates
  membership" and "bounds height without reducing membership", named by
  `verify:perf` so the guarantee has a home rather than being one test among
  many.
- ☑ **budgets recorded** — two, with observed baselines in both the isolated
  and the full-suite case, the budget form chosen for each (ratio vs absolute)
  and why, and a stated limit on what they mean.

## Verification

```
npm run lint                 ✓ 0 problems
npm run typecheck            ✓ clean
npm run verify:perf          ✓ 6 files, 75 tests — all six guarantees
npm test                     ⚠ 324 files, 2172 tests — 2167 passed; see note
npm run build                ✓
npm run verify:production    ✓ 7/7 assertions
```

**The budget redesign was validated under the condition that broke the first
version.** The absolute-ceiling grid budget passed in isolation and failed in
the full suite at 1679 ms. The ratio-based one passes in both, and
`renderBudgets.test.tsx` does not appear among the full-suite failures at all.

The five remaining failures are `InstancesPage.a11y.test.tsx` (4) and
`Gallery.a11y.test.tsx` (1), all `Test timed out`, never an assertion — the
pre-existing flake reproduced on `main` in FE-101's report (3 failed / 348
passed there with all of this work stashed). Nothing in FE-103 touches either
file's import graph.

## Limits, stated

- **No browser performance measurement exists.** Frame timing, layout thrash and
  interaction latency need Playwright against the `fixture` project. No task in
  this plan commissions them, and inventing a browser number from jsdom would be
  worse than recording none. This is the honest gap in FE-103 and it should be a
  follow-up issue rather than a silent absence.
- **The dedup proof is at the request layer, not the render layer.** It counts
  HTTP requests, which is what design §17 actually promises. It does not measure
  how many times the eight widgets re-render when one shared query resolves.
- **The budgets do not cover the Designer's rendered form**, only `project()`.
  Rendering 100 fields through the real `DesignerForm` needs the full repository
  stack and section-expansion interaction; the projection is the part that
  scales with field count and the part a schema change would regress.
