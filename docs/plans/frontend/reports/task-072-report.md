# FE-072 · ChartRenderer core — Report

## What I implemented

Refs Dec `DM-D13` `FE-D16` `D7` 🔒, design §11/§17, `dynamic-metrics` §12. Dep `071` (`projectSeries` / `ChartModel`) already done.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/chart/useEcharts.ts` (new) | Generic chart-instance lifecycle hook — create via a caller-supplied factory once the container mounts, dispose on unmount or on factory-identity change, keep sized via `ResizeObserver`. Knows nothing about ECharts: `T extends { resize(): void; dispose(): void }`. |
| `frontend/src/features/monitoring/chart/ChartRenderer.tsx` (new) | The only module that imports `echarts` (task 009's `no-restricted-imports` exemption already named this exact path). Dynamic `import('echarts')` inside a `useCallback` keyed on the MUI theme; builds a minimal `EChartsOption` (time xAxis, value yAxis, one `line` series per `ChartSeries`) from a `ChartModel`; canvas renderer; progressive rendering above a point threshold; `prefers-reduced-motion` (via MUI's `useMediaQuery`) disables both `animation` and progressive rendering. |
| `frontend/src/features/monitoring/chart/useEcharts.test.ts` (new, 8 tests) | Create/dispose/resize/recreate-on-factory-change, plus the race case (unmount before creation resolves). |
| `frontend/src/features/monitoring/chart/ChartRenderer.test.tsx` (new, 7 tests) | Mocks `echarts` via `vi.mock`; asserts theme derivation, series mapping, progressive threshold on/off, reduced-motion, dispose, and recreate-on-theme-change-only. |
| `frontend/src/test/matchMedia.ts` / `matchMedia.test.ts` (modified) | Added a `prefers-reduced-motion` branch (defaults to no-preference) plus `setReducedMotion`/`resetReducedMotion`, mirroring the existing `setViewportWidth`/`resetViewportWidth` pair — nothing queried this before FE-072. |
| `frontend/src/test/setup.ts` (modified) | Resets reduced-motion state in the existing `afterEach`, next to `resetViewportWidth()`. |
| `frontend/scripts/assert-echarts-chunk.mjs` (new) | Standalone verification script (style: `check-forbidden-deps.mjs`). Runs an isolated Vite build from a synthetic entry and asserts, from Rollup's own chunk metadata, that `echarts` lands in a chunk separate from the entry. |
| `frontend/package.json` (modified) | `"build": "vite build && node scripts/assert-echarts-chunk.mjs"` — chains onto the existing CI `build` gate rather than adding a new one. |

No changes to `eslint.config.js` (task 009 already carved out the exemption) or `contracts/metrics/types.ts` (already correct as FE-071 left it).

## Design decisions, and why

1. **`useEcharts.ts` stays fully echarts-agnostic.** The `no-restricted-imports` exemption names only `ChartRenderer.tsx`; keeping `useEcharts` generic (factory pattern, `ChartInstanceLike` interface) makes `FE-D16`'s "no second chart path" guarantee hold structurally, not just by lint. Verified against `node_modules/eslint/lib/rules/no-restricted-imports.js` that the rule only visits `ImportDeclaration`/`Export*Declaration` — it doesn't see dynamic `import()` at all, so this split was a deliberate design choice, not something the lint rule forced.
2. **`large: true` is not implementable for `line` series — corrected to `progressive`/`progressiveThreshold`.** Verified against the installed `echarts@6.1.0` type defs (`SeriesLargeOptionMixin`) that `large`/`largeThreshold` exist only on `candlestick`, `lines`, and `scatter` series, not `line`. `progressive`/`progressiveThreshold` **are** valid on `line` (line series ships `progressive: 0`, i.e. disabled, by default — `LineSeries.js`). Reused ECharts' own verified global defaults (`progressiveThreshold: 3000`, `progressive: 400`, from `globalDefault.js`) rather than inventing new numbers. This is a spec correction, not a scope cut — flagging it explicitly per `I2`.
3. **Instance lifecycle: create on theme-identity change, `setOption` on data change.** ECharts themes are init-time-only (no live re-theme API), so "re-themed on mode change without remounting" means: the React `<div>` container never unmounts; `useEcharts` disposes+recreates only when the memoized factory's identity changes (theme mode toggling in the real app creates a new theme object per `app/providers.tsx`'s `useMemo(() => createSmTheme(mode), [mode])` — verified this is how the real app already does it, and fixed a test that had assumed otherwise, see below). A data-only rerender leaves the factory stable and just calls `setOption` on the existing instance.
4. **`usePrefersReducedMotion` is MUI's own `useMediaQuery`, not a bespoke hook.** The plan draft had `useEcharts` own reduced-motion detection; `useMediaQuery` from `@mui/material` is already used across the codebase (`AppShell.tsx` etc.) and needs no new abstraction — called directly in `ChartRenderer.tsx` where the boolean is actually consumed.
5. **Option-building stays inside `ChartRenderer.tsx`**, not a third file — the task's own **Files** line names only two, and any file building `EChartsOption`-typed objects would itself need `echarts` types, which only `ChartRenderer.tsx` is allowed to import.
6. **Bundle-chunk verification runs its own isolated build**, not a check against the real `dist/`. `ChartRenderer` has no consumer yet (no route wires it in — that's a later Monitoring task), so Rollup tree-shakes the whole file out of the real app build; a real `dist/` build currently contains zero `echarts` code either way, which would prove nothing about chunk-splitting specifically. `scripts/assert-echarts-chunk.mjs` builds a synthetic entry (same `vite.config.ts`) and reads Rollup's own `moduleIds` per chunk — a bare re-export entry was itself tree-shaken to nothing (confirmed empirically), so the synthetic entry anchors `ChartRenderer` to `window` to force retention.

## A real bug caught by TDD

`useEcharts`'s unmount-cleanup originally called `current?.dispose()` from inside a `setInstance` updater function. React 18 skips running a state updater at all once the fiber is already unmounted — so `dispose()` silently never fired. The "disposes the instance on unmount" test caught this (mock called 0 times, not 1). Fixed by disposing from a plain closure variable (`created`) captured in the effect, decoupled from the state setter — `setInstance(null)` is still called in cleanup, but now purely for clearing a stale reference during a factory-identity change, not as the dispose mechanism.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json && npx tsc -p tsconfig.node.json   # clean
npm run lint                                                 # clean (project-wide, --max-warnings=0)
npm run lint:fixtures:assert                                 # single-import-site guardrail still proven (10/10)
npm run check:forbidden-deps                                 # clean
npx vitest run --project unit src/features/monitoring/chart  # 2 files, 15/15
npm test                                                      # full suite: 246 files, 1435/1435 green
npm run build                                                 # production build + assert-echarts-chunk.mjs, both succeed
```

One `npm test` run showed a single failure in `InstancesPage.test.tsx` (an undeclare-dialog `waitFor`, unrelated
feature area, no code overlap with this task). Ran that file alone — passed — then reran the full suite —
246/246 files, 1435/1435 green. Pre-existing flake under full-suite parallel load, not a regression from this
task.

`scripts/assert-echarts-chunk.mjs` was verified both ways, not just on the happy path (repo lesson R2 — "test the verification, not just the thing being verified"): temporarily switched `ChartRenderer.tsx` to a static `import { init } from 'echarts'`, confirmed the script fails with exit 1 and the expected message, then restored the dynamic-import version and confirmed it passes again.

Task-specific acceptance items:

- **Single import site** — `lint:fixtures:assert` still passes (10/10); `useEcharts.ts` contains no reference to `echarts` at all, static or dynamic.
- **Async chunk** — `assert-echarts-chunk.mjs`, chained onto `npm run build`, proven both directions (see above).
- **Theme is derived, not duplicated** — `ChartRenderer.test.tsx`'s first test asserts `echarts.init`'s theme argument deep-equals `echartsTheme(theme)` from the shared theme module, not a hand-built object.
- **Motion respected** — reduced-motion test asserts `animation: false` and no `progressive`/`progressiveThreshold` even on a fixture above the point threshold.

### Not browser-verified

`ChartRenderer` has no route/consumer yet — that wiring is a later Monitoring task (077+). Same "not browser-verified" position FE-071 documented; verification here is the test suite plus the isolated build check above.

## Status

DONE — implemented, typecheck-clean, lint-clean (project-wide), single-import-site guardrail intact, 15/15 new
tests passing, full suite green (246/246 files, 1435/1435 tests), production build + chunk-isolation check both
succeed. Not yet marked done in `task-list.md`/`execution.md` or committed — pending user confirmation. GitHub
issue for `FE-072 · ChartRenderer core` — to be closed on commit.
