# FE-110 · Reflow conformance in CI — Report

## What I implemented

Turned `FE-D29` (console reflows to 320 CSS px, WCAG 2.2 AA SC 1.4.10) into a real CI gate — a
Playwright pass across every Phase 0–6 screen, at the six widths the task names, in a real browser,
plus a real-`axe-core` pass (not `jest-axe`'s jsdom shim, which task 098 already covers) and a
static orientation-lock guard (task spec: `docs/plans/frontend/implementation.md` §FE-110; refs Dec
`FE-D29`; WCAG 2.2 AA SC 1.4.10, SC 1.3.4; design §18).

| File | Summary |
|---|---|
| `frontend/e2e/reflow/reflowCheck.ts` | New. `measureReflowViolations(page, width)` runs a self-contained DOM-walking function inside the page (`page.evaluate`) — mirrors `src/test/reflow.ts`'s jsdom criterion (task 105) but adds the one thing jsdom can't prove: an **undeclared** horizontal scroller fails, not merely "no CSS forced one." A container earns its exception via `data-reflow-exception="data-table" \| "persistent-toolbar"` — an unrecognized value also fails (`unknown-exception`), so a typo can't silently pass. `checkReflow` (resize + measure) is kept for the self-test's static fixtures. |
| `frontend/e2e/reflow/screens.ts` | New. `REFLOW_SCREENS`: every real Phase 0–6 route (login × 2 arrival states, home, solutions catalog, instances list, designer, governance, explore, dashboard, the two Phase-7 `RouteStub`s, not-found) against real fixture ids (`cache`, `cache-prod-03`) — not invented ones. |
| `frontend/e2e/reflow/axeCheck.ts` | New. Injects the real `axe-core` bundle (`node_modules/axe-core/axe.min.js`, already a transitive dep of `jest-axe` — no new dependency added) via `page.addScriptTag`, scoped to `wcag2a`/`wcag2aa`/`wcag21*`/`wcag22aa` tags (matching design §18's stated bar) with `color-contrast` excluded (open item 19 — see Ruling 2). |
| `frontend/e2e/reflow/theme.ts` | New. Forces `useUiStore`'s persisted `mode` via `page.addInitScript` + `localStorage`, so the axe pass covers both themes without a `ThemeToggle` click per screen. |
| `frontend/e2e/reflow.spec.ts` | New. Three suites: reflow-violations × 12 screens × 6 widths (72 tests), axe-clean × 12 screens × {320, 1560} × {light, dark} (48 tests), and an orientation suite asserting each screen's main landmark still renders in a landscape-shaped (812×375) viewport (12 tests). Runs on the `fixture` Playwright project. |
| `frontend/e2e/reflow.selftest.spec.ts` | New. The task's own Testing line ("the job fails on a deliberately overflowing fixture; the allowlist rejects an undeclared scroller") as five fast `page.setContent` tests against `checkReflow` directly — no dev server round trip needed to prove the checker itself works before trusting it against the real app. |
| `frontend/scripts/verify-reflow.mjs` | New. `npm run verify:reflow`: (1) a static grep guard over `src/**/*.{ts,tsx,css}` for `.orientation.lock(` calls or `@media (orientation:` queries — SC 1.3.4, instant, no browser; (2) `playwright test e2e/reflow.spec.ts e2e/reflow.selftest.spec.ts --project=fixture` — explicit file args, not `--project=fixture` alone, so it doesn't also pull in `designer.spec.ts` (a different task's journey test, already covered by `npm run e2e`). |
| `frontend/package.json` | `"verify:reflow": "node scripts/verify-reflow.mjs"`. |
| `.github/workflows/frontend.yml` | New `reflow (FE-D29)` job — its own named check, Playwright browsers installed, `npm run verify:reflow` — so a regression here blocks the build under its own name rather than being folded into the existing `e2e` job. |
| `docs/checklists/a11y-checklist.md` | New Automated bullet naming `verify:reflow`'s scope; a "Why this exists" paragraph recording the two real defects this task's first real run caught (see Ruling 1/3), matching how the gallery-composition bullet already documents *why* it's there. |
| `docs/checklists/a11y-manual-keyboard-script.md` | §1 (Skip link) corrected to describe which element actually wins the "first Tab stop" race and to record the fix task 110 made there. |
| `frontend/tsconfig.node.json`, `frontend/eslint.config.js` | `lib: ["ES2022", "DOM"]` and `globals.browser` added to the e2e block. Needed because `reflowCheck.ts`'s in-page function references `document`/`HTMLElement`/`NodeFilter` — real code that runs in the browser via `page.evaluate`, authored in a file `tsconfig.node.json` type-checks as Node. |

## Real defects found and fixed (not part of the file list above until this task's own axe pass caught them)

1. **Broken skip link on every authenticated screen and on `/login`.** `index.html`'s static skip
   link (`<a href="#main-content">`, sitting before `#root`, so it always wins the "first Tab stop"
   race over `AppShell.tsx`'s own copy) pointed at a target that only existed on `NotFound`,
   `RuntimeConfig`'s boot error, `RouteStub`, and dev `Gallery` — `AppShell.tsx` used `id="main"`
   and `LoginPage.tsx` had no id at all. Invisible to `jest-axe` (task 098), which never renders
   `index.html` — RTL mounts only the component subtree. Fixed by retargeting `AppShell.tsx`'s own
   skip link and `<main>` id to `main-content` (matching the majority convention already used by
   four other screens) and adding the same id to `LoginPage.tsx`.
2. **Three scrollable regions with no keyboard access in Safari** (`scrollable-region-focusable`,
   WCAG 2.1.1/2.1.3): `InstanceSelector.tsx`'s scrollable instance list, `ChartRenderer.tsx`'s
   below-`sm` primary tabular alternative (confirmed via the actual flagged node's `outerHTML` — a
   `<table>` with no interactive descendants, so axe's `focusable-content` check correctly found
   nothing to fall back on), and `DashboardToolbar.tsx`'s sticky control row (added defensively,
   same reasoning, though its own Select/button controls likely already satisfied
   `focusable-content` — not independently confirmed as a violator the way the other two were). All
   three fixed with `tabIndex={0}` on the scrolling container itself.

## Rulings

1. **Resize before navigating, not after.** The first version of `reflow.spec.ts` resized the
   viewport *after* `page.goto`, mirroring how `checkReflow` reads naturally. Two tests
   (`solutions-catalog` at 320px, `dashboard` at 375px) failed once, non-reproducibly, with
   `clipped-focusable`/`body-scroll` violations that vanished on the next run — `useMediaQuery` and
   MUI's own `ResizeObserver`-driven components (`DataGrid`, `Collapse`) react to a callback that
   fires a tick *after* the CDP viewport resize lands, and measuring in the same call as the resize
   raced that tick. Fixed by resizing *before* `page.goto` so the first render already happens at
   the target width — avoids the race instead of papering over it with a wait, and two full 72-test
   runs afterward were clean.
2. **`color-contrast` excluded; the axe run scoped to WCAG 2.x A/AA tags.** Un-scoped `axe.run()`
   also runs `best-practice`-tagged rules (`region`, `skip-link`, …) — real, and this task did fix a
   genuine one (Ruling/defect 1), but not this gate's pass/fail bar: design §18 states the bar as
   "WCAG 2.2 Level AA," and a best-practice rule failing the build would move that bar without a
   decision recorded for it (`I3`/`I4`). `color-contrast` is `wcag2aa`-tagged and excluded on top of
   that scoping for the separate, already-tracked reason design §18 states directly: it "cannot be
   signed off until open item 19 tokens exist."
3. **`scrollable-region-focusable` flakiness was real, not test noise.** `explore`'s axe check
   failed intermittently until `InstanceSelector.tsx`'s fix landed — investigated with a `waitForTimeout`
   probe rather than assumed away, which showed the violation appeared only once the fixture's
   instance list actually grew past the list's `maxHeight` (empty/short lists don't trigger axe's
   `scrollable-region-focusable` `matches()`, which requires real overflow). In production, with
   however many instances a real deployment has, this would not be intermittent — it would be a
   permanent, unconditional defect. Investigated and fixed rather than working around with a longer
   wait or excluding the rule.
4. **`DashboardToolbar.tsx`'s `tabIndex` fix is precautionary, not confirmed-necessary.** Its own
   axe failure (`dashboard` at 320px light) turned out, once traced via the actual flagged node's
   `outerHTML`, to be `ChartRenderer.tsx`'s table wrapper, not the toolbar. The toolbar fix is kept
   because it's the same real risk (a declared `persistent-toolbar` exception scrolling with no
   guaranteed-focusable content at every possible control combination) and costs nothing, but this
   report doesn't claim it was independently proven necessary the way the other two were.

## What this does NOT do (out of scope, as planned)

- No dialog-open state in the screen list — `REFLOW_SCREENS` covers every real route once each
  (plus two Login arrival variants), not every widget/dialog/composer-canvas sub-state a screen can
  reach. A full state-matrix sweep is a larger undertaking than "every screen" in the task's own
  Implementation line asked for; left for a follow-up rather than silently expanding this task's
  scope.
- `Admin`/`Targets` are checked only as their current bare `RouteStub` — no real content exists yet
  (Phase 7, gated, per FE-098's own report).
- Axe's own `region`/`skip-link` best-practice rules stay excluded from the CI gate itself (Ruling 2)
  even though this task fixed a real `skip-link` defect by hand — the gate's bar is WCAG A/AA, not
  axe's full best-practice set.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.node.json                        # clean
npm run lint                                         # clean
npm run typecheck                                    # clean (tsconfig.json + tsconfig.node.json)
npm test -- src/app/shell src/features/auth --environment jsdom              # 51/51
npm test -- src/features/monitoring/explore --environment jsdom             # 216/216
npm test -- src/features/monitoring/dashboard --environment jsdom           # 111/111
npm test -- src/features/monitoring/chart --environment jsdom               # 88/88
npm test -- src/shared/ui/DataTable src/features/monitoring/dashboard --environment jsdom  # 145/145
npm run test:a11y -- src/app/shell src/features/auth # 15/15
npx playwright test e2e/reflow.selftest.spec.ts --project=fixture           # 5/5
npx playwright test e2e/reflow.spec.ts --project=fixture -g "reflows cleanly"  # 72/72, twice in a row
npx playwright test e2e/reflow.spec.ts --project=fixture -g "axe clean"       # 48/48, twice in a row
npx playwright test e2e/reflow.spec.ts --project=fixture -g "orientation"     # 12/12
npx playwright test e2e/reflow.spec.ts e2e/reflow.selftest.spec.ts --project=fixture  # 137/137
npm run verify:reflow                                # exit 0 — orientation guard + full 137-test suite
npm run build                                        # succeeds; echarts stays its own chunk
npx playwright test --project=preview --project=fixture e2e/smoke.spec.ts e2e/login.spec.ts e2e/designer.spec.ts  # 6/6 — pre-existing journeys unaffected
npm run check:forbidden-deps                         # clean
npm run lint:fixtures:assert                         # 10/10 fixtures fail their intended rule (pass condition)
npm test                                             # 1868/1873 (5 pre-existing flaky failures, see below)
npm run test:a11y -- --all                           # 309/315 (6 pre-existing flaky failures, same root cause)
```

### Pre-existing flakiness under full-suite parallelism (not introduced here)

Both full-suite runs (`npm test`, `npm run test:a11y -- --all`) failed only in
`InstancesPage.test.tsx`/`InstancesPage.a11y.test.tsx`, every failure a `Test timed out` — the exact
pre-existing issue FE-098's own report documented ("Pre-existing flakiness under full-suite
parallelism... `InstancesPage.test.tsx` itself"), down to that file's own comment: *"5000ms budget
(observed, not assumed: passes in isolation, times out only alongside the other 130+ files)."*
Confirmed unrelated to this task by re-running the same file in isolation:
`npm run test:a11y -- src/features/inventory/instances` → 14/14 passed.

## Status

DONE — implemented, verified (lint/typecheck/build clean, 137/137 on the new Playwright reflow
suite twice in a row, existing e2e journeys unaffected). Not yet committed or closed on GitHub —
pending the user's go-ahead to commit to `dev/frontend`.
