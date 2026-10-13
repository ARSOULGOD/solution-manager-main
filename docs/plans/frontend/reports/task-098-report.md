# FE-098 · Automated accessibility sweep — Report

## What I implemented

Turned the 94 already-existing `.a11y.test.tsx` files (per-component and per-screen `jest-axe`
checks, already parametrized over both themes) into a named, independently runnable, CI-visible
gate, closed the one real coverage gap named in the task text (the assembled component gallery),
and wrote the two documents the task's own acceptance line asks for (task spec:
`docs/plans/frontend/implementation.md` §FE-098; refs Design §18, WCAG 2.2 AA).

Done ahead of FE-110 at the user's request — FE-110 lists `098` as a dependency and its own Verify
line runs `npm run test:a11y -- --all`, a script that didn't exist yet.

| File | Summary |
|---|---|
| `frontend/vitest.config.ts` | New `a11y` project (`include: ['src/**/*.a11y.test.tsx']`), mirroring `unit`'s environment/setup exactly. `unit`'s own `include` gained a matching `exclude` so every file runs under exactly one project — `npm test` (which runs every project) does not double-run the 95 a11y files. |
| `frontend/scripts/test-a11y.mjs` | New. A wrapper, not a raw `vitest` CLI line — see Ruling 1. |
| `frontend/package.json` | `"test:a11y": "node scripts/test-a11y.mjs"`. |
| `frontend/src/app/dev/Gallery.a11y.test.tsx` | New. Axe over the fully assembled `/dev/gallery` page, both themes — the one gap `Gallery.tsx`'s own header comment already named as task 098's target. |
| `frontend/src/shared/ui/SidebarNav/SidebarNav.gallery.tsx` | One line: `aria-hidden="true"` on the second (`collapsed`) demo instance's wrapper. See Ruling 2 — a real defect this new test found on its first run. |
| `docs/checklists/a11y-checklist.md` | New. Same format as the sibling checklists. |
| `docs/checklists/a11y-manual-keyboard-script.md` | New. The 10-item written script the task names verbatim (skip link, drawer, breadcrumbs, tabs, data grid, Designer traversal/jump-to-field, dialogs, composer nodes, dashboard `order`, chart keyboard model), each grounded in the real component, plus a results-log table. See Ruling 3 — one item (`RouteTabs`) is flagged as an open question inside the script itself rather than silently asserted clean. |
| `docs/checklists/README.md` | Both new checklist files added to the index table. |
| `.github/workflows/frontend.yml` | `"test:a11y"` added to the `gate` job's matrix, its own named CI check. |

## Rulings

1. **`test:a11y` is a wrapper script, not a raw `vitest` CLI line.** The plan's own verification
   protocol (`implementation.md` §6.0.8) uses this two ways: `npm run test:a11y -- <path>`
   (per-task, same shape every other `npm test -- <path>` call already uses) and `npm run test:a11y
   -- --all` (task 098's own full sweep). I verified empirically, against this repo, that a CLI
   positional filter **ANDs** with a Vitest project's `include` config (only a11y files under
   `<path>` ran) but **ORs** with a second CLI-level glob pattern (passing both `"**/*.a11y.test.tsx"`
   and a path as two bare CLI args matched 456 tests across all of `src/features/monitoring`, not
   just its 2 a11y files) — so the restriction had to live in the `a11y` project's config, not a
   CLI arg. Separately, raw `vitest run --all` errors (`CACError: Unknown option --all` — vitest has
   no such flag), confirmed by actually running it. The wrapper drops a literal `--all` (meaning the
   same thing as no args, since the project's own `include` already is "every a11y file") and
   forwards anything else — matching the exact idiom this repo already uses for
   `check-forbidden-deps.mjs`/`assert-echarts-chunk.mjs`.
2. **`SidebarNav.gallery.tsx`'s duplicate-landmark fix is `aria-hidden`, not a rewrite.** The new
   Gallery a11y test found a real `landmark-unique` axe violation on its first run: the gallery
   shows `SidebarNav`'s expanded and collapsed states side by side (deliberately, per that file's
   own comment — "the rail is the state most likely to regress unseen"), and both instances carry
   the identical hardcoded `aria-label="Solution Manager"` (`SidebarNav.tsx`'s own `t('navLabel')`,
   not exposed as an overridable prop). This is a **gallery-only** artifact — production never
   renders two `SidebarNav`s at once — so the fix is `aria-hidden="true"` on the second instance's
   wrapper, confirmed safe because `SidebarNav.a11y.test.tsx` already independently covers the
   collapsed state's own accessibility (`'has no violations collapsed to a rail'`), so nothing is
   lost by removing this second copy from the gallery page's accessibility tree while keeping it
   visible for the side-by-side visual comparison the section exists for. Rewriting `SidebarNav`
   itself to accept an overridable landmark label was rejected — it would touch the real
   component's public prop surface for a gallery-only concern, and `FE-D5` keeps paint/behaviour
   changes to shared components under review discipline this task doesn't need to invoke.
3. **The `RouteTabs` arrow-key gap is flagged in the script, not fixed here.** Investigating item 4
   (Tabs) for the manual script, I found `RouteTabs.tsx` implements the roving-tabindex HALF of the
   WAI-ARIA APG tabs pattern (`tabIndex={isSelected ? 0 : -1}`) but no `ArrowLeft`/`ArrowRight`
   handler to move focus *between* tabs — meaning a keyboard-only user landing on the tablist may
   only be able to reach the currently-selected tab (siblings are excluded from `Tab` order by
   `tabIndex={-1}`, and nothing else moves focus onto them). This wasn't confirmed with a screen
   reader or exercised end-to-end — it's a plausible reading of the source, not a demonstrated bug —
   so it's recorded as an explicit, named open question **inside the script itself** (item 4) for
   the first real run to confirm or clear, rather than either silently asserted fine or fixed
   without verification. Flagging honestly rather than guessing, consistent with `I2`.

## What this does NOT do (out of scope, as planned)

- `Admin`/`Targets` routes: bare `RouteStub` placeholders (Phase 7, "gated on Q4/Q5", not
  scheduled) — no a11y test added; nothing real to check yet.
- No Playwright/real-browser a11y pass. That mechanism — axe against a real rendered page, at
  multiple widths — is FE-110's own job, built next on top of this.
- None of the 94 pre-existing `.a11y.test.tsx` files were rewritten. They already did what task 098
  asks; this task only made them collectively nameable/runnable/CI-visible, plus closed the
  gallery gap.
- The manual keyboard script has not been *run* yet (its own log table says so) — writing it is
  this task's job; running it once per release is a separate, ongoing responsibility the checklist
  now points at.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                       # clean
npm run typecheck                                  # clean
npm run test:a11y                                   # 95 files (94 existing + Gallery), 315 tests — all passed
npm run test:a11y -- --all                           # same 95/315 — confirms --all composes without erroring
npm run test:a11y -- src/app/dev                     # 1 file, 2 tests — confirms path-scoping ANDs, not ORs
npm run build                                        # production build succeeds; echarts stays its own chunk
npm test                                             # full suite — see "Pre-existing flakiness" below
```

`npm run test:a11y -- src/app/dev` returning exactly 1 file / 2 tests (rather than the whole `unit`
project's worth of `src/app/dev` files) is itself the empirical proof that Ruling 1's AND-not-OR
claim holds in practice, not just in the scratch experiment used to design it.

### Pre-existing flakiness under full-suite parallelism (not introduced here)

`npm test` (all three projects, 303 files) was run **twice**. Run 1: 2 failures (1 file). Run 2: 7
failures (3 files), no overlap with run 1 beyond `InstancesPage.test.tsx` itself. Every failing
test, re-run in isolation (`npx vitest run --project unit src/features/inventory/instances/InstancesPage.test.tsx`),
**passes cleanly** (29/29). The failures are worker-pool-saturation timeouts, not logic errors:

- Two of run 2's five `a11y`-project failures are literal `Test timed out in 15000ms` on
  `InstancesPage.a11y.test.tsx` — a file whose own comment, predating this task, already documents
  exactly this: *"A real 12-row DataGrid axe scan, ×2 (light/dark), is slow enough on its own —
  under the full suite's parallelism it can miss the default 5000ms budget (observed, not assumed:
  passes in isolation, times out only alongside the other 130+ files)."* — hence its own explicit
  `15000` override, which the full run still missed.
- The new `Gallery.a11y.test.tsx` dark-mode case (`{ timeout: 60_000 }`, already the most generous
  in the suite) also timed out once, under the same contention.
- `InstancesPage.test.tsx`'s two non-a11y failures (`waitFor` giving up on a dialog close / a URL
  update) are the same signature — an async wait starved by CPU contention, not a state-machine bug
  — reinforced by passing 29/29 alone, twice.

This is the same class of flake FE-108's report already named and partially mitigated (`vitest.config.ts`'s
`unit` project `testTimeout: 20_000`, raised for exactly this reason) — general full-suite-under-load
flakiness that predates this task, evidenced by a comment already in the codebase before this task
touched anything. Not fixed here: the scope of a real fix (worker/concurrency tuning, or raising
every affected timeout defensively) is a test-infrastructure concern broader than "wire up an a11y
gate," the same posture the `useEcharts.ts` StrictMode race got in FE-109 (flagged, not folded into
an unrelated task). Recommending the same treatment: a follow-up issue, pending the user's go-ahead.

## Status

DONE — implemented, lint-clean, typecheck-clean, production build succeeds, both `test:a11y`
composition forms (`--all` and a scoped path) verified against this repo directly, every individual
file this task touches or added passes in isolation. `npm test`'s full-suite flakiness (above) is
pre-existing and not this task's to fix. No GitHub issue exists for FE-098 (`task-list.md`'s Phase 8
rows carry no Issue column at all, unlike Phase 0/1/3/6) — flagging that gap rather than silently
skipping the close step, per this repo's own task-completion convention.
