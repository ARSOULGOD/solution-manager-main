# FE-069 · Catalog queries — Report

## What I implemented

This task's Refs are `SM-D30`, `DM-D10`, `UI-D5`, `dynamic-metrics` §5.5, UI §A.10. Dep `068` (MetricsProvider)
was already done. Unlike most tasks, FE-069 lists no `Files` line — and auditing what task 033 already built
against this task's own Implementation text found that the routes, the type, and the missing-fields flag all
already existed:

- `MetricsRepository.entityTypes/instances/metricCatalog/labelValues` (`metricsRepository.ts`, task 033) already
  call the four routes this task names, against the exact response shapes.
- `CatalogEntry` (`shared/testdoubles/data/metrics.ts`, task 033) already carries every field this task's
  Implementation text lists (`name, metricType, unit, layer, state, lastSeen, labelKeys, durability?,
  suspectReason?`) — no type change needed.
- `declareMetricType`'s doc comment in `metricsRepository.ts` already flags "no `firstSeen`, no suggested-type
  field (§16 Q14)" — the "missing fields flagged in source" acceptance box was already satisfied.
- `METRIC_CATALOG`'s fixture already has all three `MetricState`s (six `plottable`, one `needs-typing`, one
  `type-suspect`) and the MSW handler already serves it whole.

The actual gap: **nothing wraps these repository calls in a `useQuery` hook.** Every prior catalog-adjacent
consumer (task 068's `MetricsProvider`) only ever needed `capabilities()`. Task 070+ (buildQuery, Explore) will
need a query layer over the catalog the same way `useCapabilities` is one over `capabilities()` — that's what this
task builds.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/useCatalog.ts` (new) | Four hooks — `useEntityTypes()`, `useMetricInstances(entityType, query?)`, `useMetricCatalog(query?)`, `useLabelValues(labelKey, query?)` — each a `useQuery` over the matching `MetricsRepository` method, reading the repository from `useMetrics()` (task 068) rather than constructing its own. `useMetricInstances`/`useLabelValues` are `enabled` only once their id argument is non-empty, matching the gating pattern `LogViewer.tsx` already uses for `component !== undefined`. |
| `frontend/src/features/monitoring/useCatalog.test.tsx` (new) | Against the real MSW-backed `consoleClient` (same harness `MetricsProvider.test.tsx` uses): `useEntityTypes()`/`useLabelValues('region')` resolve the fixture data unchanged; `useMetricInstances('cache-instance')` resolves the full `CACHE_INSTANCES` list; `useMetricCatalog()`'s result contains all three `MetricState`s and the `needs-typing` entry's `metricType` stays `null`; both id-gated hooks stay `fetchStatus: 'idle'` (no request issued) when given an empty string. |

## Design decisions, and why

1. **No repository, type, or fixture change.** A grep for every field this task's Implementation text names came
   back already present from task 033 — inventing a second `Capabilities`-style expansion (as FE-068 did) would
   have duplicated a shape that was already exactly right, since task 033 built `metricsRepository.ts` SPECIFIED
   from the start (per its own header comment: "implemented against the real shape, unlike the other eleven
   seams"). Confirmed by grepping every field name (`layer`, `state`, `lastSeen`, `labelKeys`, `durability`,
   `suspectReason`) against `CatalogEntry` before writing any code.
2. **One file for all four hooks, not four files.** They're four thin wrappers around one repository's four
   catalog methods, sharing the same `useMetrics()` seam and the same "pass through unchanged" rule — splitting
   them would scatter one coherent idea (task 069's own "three states, three renderings, none of them silence")
   across four files for no reader benefit.
3. **"None of them silence" is enforced by NOT filtering, not by a filter that's provably correct.** The simplest
   way to guarantee `needs-typing`/`type-suspect` entries never disappear is to not touch the array between the
   repository and the caller — `useMetricCatalog` returns `metricsRepository.metricCatalog(query, signal)`'s
   result verbatim. The test proves this holds today (all three states present) rather than trusting the
   pass-through by inspection alone.
4. **`enabled` gating on non-empty id, not a stricter guard.** `useMetricInstances`/`useLabelValues` take a
   required `string` (not `string | undefined`) so a caller mid-render with no selection yet passes `''`, which
   this hook treats as "nothing to fetch" rather than issuing a request for `/entity-types//instances`. Task
   078/079 (InstanceSelector, MetricSelector) are the actual callers and don't exist yet, so this is the
   conservative default that won't need revisiting once they do.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc --noEmit -p .                                 # clean
npm run lint                                          # clean (project-wide, --max-warnings=0)
npx vitest run --project unit src/features/monitoring # 6 files, 18/18 (was 5 files/12 tests before this task)
npm test -- --run                                     # full suite: 241 files, 1385/1385 green (was 240/1379)
npm run build                                          # production build succeeds
```

Task-specific acceptance items:

- **All three states render distinctly** — not yet applicable at this layer (no UI exists; that's task 079's
  `MetricSelector`). What this task can and does prove: the data underneath a future distinct rendering is present
  and untouched — `useCatalog.test.tsx` asserts all three `MetricState`s appear in `useMetricCatalog()`'s result.
- **No inferred type** — asserted directly: the `needs-typing` entry's `metricType` is `null` after the hook,
  same as the fixture.
- **Missing fields flagged in source** — already true from task 033 (`metricsRepository.ts`'s `declareMetricType`
  doc comment cites §16 Q14); this task's header comment in `useCatalog.ts` repeats the pointer so a reader
  arriving at the hook layer doesn't have to go looking for it.

### Not browser-verified

Same situation as task 068: no route consumes these hooks yet (`InstanceShell` still renders
`instanceMonitoringPlaceholder` for Monitoring). Verification is the hook-level integration tests above, against
the real MSW handler.

## Status

DONE — implemented, tested (18/18 on the touched suite; full suite green, 241/241 files, 1385/1385 tests),
lint-clean, typecheck-clean, production build succeeds. Not yet marked done in `task-list.md`/`execution.md` or
committed — pending user confirmation. GitHub issue #85 (`FE-069 · Catalog queries`) — to be closed on commit.
