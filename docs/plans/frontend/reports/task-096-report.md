# FE-096 · Code splitting and a bundle budget — Report

## What I implemented

Turned design §17's *"Bundle → route-level code split per feature; ECharts async import inside
`ChartRenderer`"* and §21's air-gap/size pressure into a **build-failing CI gate** on the initial
JavaScript payload (task spec: `docs/plans/frontend/implementation.md` §FE-096; refs design §17,
§21; deps 072 + all features).

Route-level splitting already exists — `src/app/router.tsx` wraps every route element in
`lazy(() => import(...))`, and task 072 already isolates ECharts behind `ChartRenderer`'s
`import('echarts')` (`scripts/assert-echarts-chunk.mjs`, run by `npm run build`). What was missing
is the *budget*: nothing failed the build if a heavy dependency leaked into the first-render payload
or a feature forgot to lazy-split. This task adds that.

| File | Summary |
|---|---|
| `frontend/scripts/verify-bundle.mjs` | New. Runs the real production build in-memory (`vite build`, `write: false`, same `vite.config.ts`), computes the **initial payload** = the entry chunk plus its transitive **static-import** closure (`chunk.imports`, never `chunk.dynamicImports`), and hard-fails if (1) any initial chunk contains a `node_modules/echarts` module, (2) any initial chunk contains a `node_modules/@mui/x-data-grid` module, or (3) the gzipped total of the initial chunks exceeds **160 KiB**. Prints a per-chunk table with headroom and names the lazy echarts / data-grid chunks. |
| `frontend/package.json` | `"verify:bundle": "node scripts/verify-bundle.mjs"`. |
| `.github/workflows/frontend.yml` | `verify:bundle` added to the `gate` matrix as its own named check, with a comment block recording the ceiling and its rationale (task line: *"the ceiling is recorded in the workflow with its rationale"*). |

## The ceiling

**160 KiB gzip** for the initial JS payload.

Baseline on 2026-09-02 is **134.2 KiB gzip**, across four chunks:

| Chunk | gzip | Contents |
|---|---|---|
| `index-*.js` | 67.6 KiB | React + ReactDOM, `@tanstack/query-core`, theme (`createSmTheme`, tokens, palette), auth adapter/provider, `uiStore`, `router.tsx`, `providers.tsx`, `main.tsx` |
| `RuntimeConfig-*.js` | 60.6 KiB | MUI `material`/`system`/`utils` core + Emotion + `stylis` (FE-D4, one MUI design system), `loadConfig`, `RuntimeConfig` |
| `planeClientFactory-*.js` | 5.5 KiB | `@tanstack/react-query` + `planeClientFactory` |
| `rolldown-runtime-*.js` | 0.4 KiB | Vite 8 / rolldown module runtime |

None of that is route code; none is reducible without reversing a locked decision (FE-D1 TanStack
Query, FE-D4 MUI). Ceiling = baseline + ~20% headroom. It absorbs incremental growth in the shared
shell; it does **not** absorb a new heavy dependency in the initial graph or a feature that forgets
to lazy-split — which is exactly what this gate exists to catch. The rationale note in the workflow
and the header comment in `verify-bundle.mjs` say to raise it only with a one-line "what grew and
why" in both places.

## Rulings

1. **Enforce on gzip, initial JS only.** Gzip is what crosses the wire; `zlib.gzipSync` is
   deterministic for a given Node/zlib and CI + local both run Node 22. CSS is 1.7 KiB gzip and not
   gated — the design's bundle pressure is JS code-splitting, and a separate CSS budget would be
   noise at this size.
2. **"Initial" = static-import closure of the entry chunk**, computed from Rollup's own
   `chunk.imports` graph — the same set the browser must fetch before the first route renders, and
   the same set Vite emits `<link rel="modulepreload">` for. Lazy route chunks, the echarts chunk
   and the data-grid chunk fall outside it by construction.
3. **Kept `assert-echarts-chunk.mjs` as-is.** It proves a *different* thing — that
   `ChartRenderer`'s dynamic import splits even when the module has a synthetic consumer — via an
   isolated build. `verify-bundle.mjs` checks the *real app graph*. Both run in CI (`build` gate and
   `verify:bundle` gate).
4. **Data Grid: asserted absent from initial, not pinned to the inventory route's chunk.** In the
   current build the grid lands in a shared lazy chunk (`layout-*.js`) because `DataTable` is used
   by three route trees (inventory `InstancesPage`, designer `ReplicaTable`, monitoring
   `ChartDataTable`). That still satisfies the task's intent — it is code-split away from first
   render. Forcing it into one route's chunk via `manualChunks` would *duplicate* it across the
   other two, which is worse. The gate enforces the property that matters: not in the initial
   payload.
5. **Left `vite.config.ts` untouched.** The pre-existing `chunks larger than 500 kB` warning
   (echarts 1.1 MB, `layout` 773 KB — both already lazy) is a warning, not a failure, and predates
   this task. Bumping `chunkSizeWarningLimit` would be cosmetic; minimal-impact change.

## What this does NOT do (out of scope)

- No per-route or total-bundle budget — only the initial payload is gated. A growing lazy route
  chunk is a real concern but a different one; not silently expanding scope.
- No FE-101 assertions (no MSW / no fixture dataset / no auth mock in a prod bundle) — that is
  FE-101's task. `verify-bundle.mjs` runs the same fixture-backed build the `build` gate does.
- No `manualChunks` tuning. Rollup's default chunking already isolates echarts and the data grid;
  adding manual control now would be speculative.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                         # clean (import/order autofixed on the new script)
npm run typecheck                    # clean (tsconfig.json + tsconfig.node.json)
npm run build                        # succeeds; assert-echarts-chunk still passes
npm run verify:bundle                # exit 0 — initial 134.2 KiB gzip, ceiling 160 KiB, headroom 25.8 KiB
node scripts/verify-bundle.mjs  (ceiling temporarily forced to 100 KiB)  # exit 1 — "over the 100.0 KiB ceiling by 34.2 KiB"
```

Negative path for the dependency assertions is covered by construction — the same
`node_modules/echarts` / `node_modules/@mui/x-data-grid` module-id prefix match already tags those
chunks correctly in the initial-vs-lazy split the script prints.

## Status

DONE — implemented, verified (lint/typecheck/build clean; `verify:bundle` green at exit 0 and
demonstrably red when the ceiling is lowered). Not yet committed or closed on GitHub — pending the
user's go-ahead to commit to `dev/frontend`.
