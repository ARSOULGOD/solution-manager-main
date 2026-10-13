# FE-070 · `buildQuery` and the canonical query hash — Report

## What I implemented

Refs `DM-D1` `DM-D2` `DM-D23`, `dynamic-metrics` §5.6, §10. Dep `068` (MetricsProvider/capabilities) already done.

| File | Summary |
|---|---|
| `frontend/contracts/metrics/buildQuery.ts` (new) | `buildQuery(widgetConfig, timeRange, capabilities, now?) → QueryRequest`. Resolves a relative preset (`15m·1h·6h·24h·7d·30d·90d·6mo`) to an absolute `{from,to}` at issue time — `6mo` by calendar-month subtraction, not a fixed day count; passes an already-absolute range through unchanged. `metric` is a single `string` (`DM-D23` — a list is a type error, not a runtime check). Fails resolution closed to `auto` when `raw` is requested but `capabilities.features.forceRaw` is `false`, mirroring `capabilityDefaults.ts`'s fail-closed posture. `now` is an injectable clock (defaults to `() => new Date()`) so tests assert an exact resolved window instead of a moving target. |
| `frontend/contracts/metrics/canonicalHash.ts` (new) | `canonicalQueryHash(query) → string` — a canonical JSON string (not a checksum) used directly as `queryKeys.metrics.query(queryHash)`'s key. Sorts `selector.instanceIds` and `groupBy` by value (they're sets, not sequences — §5.6/§5.7 never assign them order) and sorts every object's keys recursively via a `JSON.stringify` replacer, so two configs built with different property or selection order hash equal. |
| `frontend/contracts/metrics/buildQuery.test.ts` (new) | 9 cases: single-metric passthrough, relative-preset resolution (including the calendar-month `6mo` case), absolute-range passthrough, empty-labels default, raw-resolution passthrough vs. fail-closed-to-auto, and `groupBy` present/absent. |
| `frontend/contracts/metrics/canonicalHash.test.ts` (new) | 5 cases: instance-order-insensitivity, label-key-order-insensitivity, `groupBy`-order-insensitivity, distinct metrics hash differently, and the task's own acceptance scenario — 8 widgets over 4 metrics (2 per metric, differently-ordered instance selections) collapse to 4 distinct hashes. |
| `frontend/vitest.config.ts` | Extended the `contracts` project's `include` from `contracts/fixtures/**/*.test.ts` to also match `contracts/metrics/**/*.test.ts` — without this the two new test files above would silently never run under either Vitest project (`unit` only matches `src/**`). |

## Design decisions, and why

1. **`contracts/metrics/buildQuery.ts` defines its own `QueryRequest`, not an import from `metricsRepository.ts`.**
   `contracts/` imports nothing app-side (task 046's acceptance box, enforced by `eslint.config.js` — contracts
   is shared with a future Java implementation). `src/features/monitoring/metricsRepository.ts` (task 033)
   already has its own copy of the same wire shape, predating this task. Task 071's `types.ts` (its own `Files`
   line, not this task's) is where a shared `contracts/metrics` vocabulary gets consolidated — this task doesn't
   pre-empt that ownership by inventing a `types.ts` a task early.
2. **`WidgetQueryConfig` excludes range and resolution.** The design doc's component tree (§9.1) puts
   `<TimeRangePicker>`/`<ResolutionControl>` beside `<FilterBar>`, shared by both `<ExploreView>` and
   `<DashboardToolbar>`, not owned by the filter-bar draft state — so `buildQuery`'s second parameter
   (`QueryTimeRange`) is deliberately a separate concern from the first (`WidgetQueryConfig`), matching that split
   rather than folding everything into one flat object.
3. **`now` as an injectable parameter, not `Date.now()` read inline.** §10 requires resolution "at issue time,"
   which is real wall-clock behavior, not something to fake away — but a hidden `Date.now()` call would make the
   exact resolved window untestable without mocking global time. A defaulted parameter keeps production callers
   unchanged (`buildQuery(cfg, range, caps)`) while tests pass a fixed clock.
4. **Resolution fail-closed to `auto`, not a thrown error.** `<ResolutionControl>` (task 081) already gates the
   choice on both `capabilities.features.forceRaw` and the `metrics.forceRaw` verb before ever offering `raw` — so
   this can't be reached via a correctly-gated UI. `buildQuery` still clamps rather than trusts, because it is the
   one function both `<ExploreView>` and `<DashboardToolbar>` funnel through (§9.1's "pure core, shared by both
   views") — putting the fallback here means the invariant holds even if a future gating bug in either caller
   forgot to check the capability, instead of needing the same check duplicated in both places.
5. **The hash is a canonical string, not an actual digest.** §10 says "the hash is the TanStack `queryKey`" — a
   short, already-order-insensitive string serves that role directly; hashing it further (e.g. through a digest
   function) would only add a collision risk with no benefit, since TanStack Query keys are compared by value,
   not by length.
6. **Generic key-sorting handles object fields; explicit sorts handle the two order-insignificant arrays.**
   `stableStringify`'s `JSON.stringify` replacer sorts every plain object's keys recursively, which is sufficient
   for `range`, `selector`, and `aggregation` (object key order is never semantically meaningful in JSON).
   `instanceIds` and `groupBy` are the only *arrays* where the API assigns no order (§5.6, §5.7 both describe them
   as selections/sets), so those two are canonicalized explicitly before the generic pass, rather than sorting
   every array generically — a hypothetical future order-significant array in `QueryRequest` would not be
   silently miscompared.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json && npx tsc -p tsconfig.node.json   # clean
npm run lint                                                 # clean (project-wide, --max-warnings=0)
npx vitest run --project contracts                            # 3 files, 36/36 (new: buildQuery 9, canonicalHash 5)
npm test                                                       # full suite: 243 files, 1399/1399 green (was 241/1385)
npm run build                                                  # production build succeeds
```

Task-specific acceptance items:

- **Hash is order-insensitive** — `canonicalHash.test.ts`: instance-order, label-key-order, and `groupBy`-order
  cases all assert equal hashes.
- **Dedup demonstrated** — the task's own example reproduced exactly: 8 widget configs across 4 distinct metrics
  (2 differently-ordered configs per metric) collapse to 4 distinct hashes.
- **One metric per request enforced by type** — `QueryRequest.metric: string` (not `string[]`); a test asserts
  `typeof request.metric === 'string'`, and passing an array would be a compile error, not a runtime check.

### Not browser-verified

Pure contract-layer functions with no UI consumer yet (`<ExploreView>`/`<DashboardToolbar>` are tasks 077/085+).
Verification is the unit-test suite above; there is nothing to click through.

## Status

DONE — implemented, tested (36/36 on the touched `contracts` project; full suite green, 243/243 files, 1399/1399
tests), lint-clean, typecheck-clean, production build succeeds. Not yet marked done in `task-list.md`/
`execution.md` or committed — pending user confirmation. GitHub issue #86 (`FE-070 · buildQuery and the canonical
query hash`) — to be closed on commit.
