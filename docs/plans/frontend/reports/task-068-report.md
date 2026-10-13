# FE-068 · MetricsProvider and capabilities (fail closed) — Report

## What I implemented

This task's Refs are `REQ-007`, `DM-D10`, `FE-D20`, design §11, `dynamic-metrics` §5.4/§17. Deps `027` (plane
clients) and `029` (QueryClient defaults) were already done. It is the first task in Phase 6 (Monitoring) and
opens the `dynamic-metrics` embed — no route consumes it yet (Explore is task 077, Dashboard is task 085); this
task builds the capabilities plumbing those later tasks read from, tested in isolation.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/metricsRepository.ts` | Expanded `Capabilities` from the task-033 placeholder (`{ maxSeriesPerWidget, rawResolutionAllowed }`) to the full `dynamic-metrics` §5.4 response shape: `queryVersion`, `tiers[]` (name/retention/minBucketSeconds), `maxRange`, `maxPointsPerSeries`, `marks[]`, `aggregations[]`, `features{forceRaw, streamingExport, pngExport}`. `capabilities()`'s implementation is unchanged — only its return type grew to match what the route actually specifies. |
| `frontend/src/features/monitoring/capabilityDefaults.ts` (new) | `FAIL_CLOSED_CAPABILITIES` — the one place a preset/cap/tier literal is allowed. Encodes §17's three named values (series cap 10, presets to 24h via a single `P1D` tier, `features.forceRaw: false`) and picks internally-consistent conservative values for the fields §17 doesn't name (documented in the file header as invented, not spec). |
| `frontend/src/features/monitoring/useCapabilities.ts` (new) | `useCapabilities(repository)` — wraps `useQuery(queryKeys.metrics.capabilities(), ...)`. Never returns `undefined`: `capabilities` is server data once resolved, `FAIL_CLOSED_CAPABILITIES` while loading or once the fetch has failed; `degraded` is `query.isError`. |
| `frontend/src/features/monitoring/MetricsProvider.tsx` (new) | Context + hook pair (`MetricsProvider`/`useMetrics`), same shape as `AuthProvider`/`useAuth` (task 030): builds one `MetricsRepository` from a `consoleClient` prop via `useMemo`, calls `useCapabilities`, exposes `{ metricsRepository, capabilities, degraded, isLoading }`. Scoped to Monitoring routes (a `consoleClient` prop, not mounted in `app/providers.tsx`) — capabilities are meaningless outside an instance's Monitoring category, same reasoning `InstanceCategoryRoute` already applies to `governanceRepository` etc. |
| `frontend/src/shared/testdoubles/handlers/console.ts` | `/console/v1/capabilities` MSW handler now returns §5.4's example response verbatim (three tiers, `maxRange: 'P2Y'`, full `marks`/`aggregations`/`features`) instead of the old two-field stub, so component tests exercise the real shape. |
| `frontend/src/features/monitoring/metricsRepository.test.ts` | Two new assertions on the existing capabilities case (`tiers.length > 0`, `features.forceRaw === true`) proving the expanded fixture round-trips through the repository. |
| `capabilityDefaults.test.ts` (new) | Four cases: series cap is 10; every tier's retention is `P1D` (nothing past 24h); `forceRaw` is `false`; every tier's bucket count at 24h stays under `maxPointsPerSeries` (catches a defaults edit that reintroduces the 1440-points-over-cap inconsistency an earlier draft had at `minBucketSeconds: 60`). |
| `useCapabilities.test.tsx` (new) | Against a fake `MetricsRepository`: resolves server capabilities with `degraded: false`; on rejection, settles to exactly `FAIL_CLOSED_CAPABILITIES` with `degraded: true`. |
| `MetricsProvider.test.tsx` (new) | Against the real MSW-backed `consoleClient`: happy path returns real capabilities, not degraded; `server.use()` override to a `500` on `/capabilities` proves the provider falls back to `FAIL_CLOSED_CAPABILITIES` and reports `degraded: true`; `useMetrics()` called outside a provider throws (mirrors `AuthProvider.test.tsx`'s equivalent case exactly, including the `console.error` spy). |

## Design decisions, and why

1. **Expanded `Capabilities` in place rather than adding a second, richer type.** The task-033 placeholder
   (`maxSeriesPerWidget`/`rawResolutionAllowed`) was never referenced outside `metricsRepository.ts` and its own
   test (checked with a repo-wide grep before editing), so there was no second type to reconcile and no caller to
   migrate — replacing it is a strict superset, not a breaking change.
2. **`capabilityDefaults.ts` states, in its own header, which values are spec text and which are invented.** §17
   names exactly three fail-closed values (series cap 10, presets to 24h, no forced-Raw). The other fields
   (`maxPointsPerSeries`, `marks`, `aggregations`, `features.streamingExport`/`pngExport`) have to hold *some*
   value for the type to compile, so the header says so explicitly rather than letting a future reader assume the
   whole object is spec-derived — the same discipline `authoring.reviewedRows` and other INVENTED fields already
   follow elsewhere in this codebase.
3. **`minBucketSeconds: 300` for the fail-closed tier, not 15 (the real raw tier's value) or 60.** At `P1D`
   retention, 15s buckets is 5,760 points and 60s is 1,440 — both exceed `maxPointsPerSeries: 1000`. 300s (5 min)
   gives 288 points, comfortably under the cap, so a later task deriving bucket size from `minBucketSeconds`
   doesn't inherit a self-contradictory fixture. Caught by `capabilityDefaults.test.ts`'s consistency case, not
   guessed — I ran the arithmetic and let the test assert it generically rather than hardcoding "288."
4. **`MetricsProvider` is not mounted in `app/providers.tsx`.** Auth is global; capabilities are not — they only
   mean anything inside an instance's Monitoring category. Constructing it per-route (a `consoleClient` prop, built
   where the route needs it) matches how `InstanceCategoryRoute` already constructs `configRepository`,
   `applyRepository`, `actionsRepository`, `governanceRepository` — none of those live in `app/providers.tsx`
   either. No route wires `MetricsProvider` in yet because none of Phase 6's UI exists yet (`InstanceShell` still
   renders `instanceMonitoringPlaceholder` for the `monitoring` category); that wiring is implicit scope of
   whichever task first builds real Monitoring content, not this one.
5. **No `src/features/monitoring/index.ts` barrel yet.** The boundaries lint (`eslint.config.js`) requires
   cross-feature imports to go through a feature's `index.ts`, but nothing outside `features/monitoring` imports
   from it yet — `GovernancePage`'s barrel was created in task 064, the task that made `@features/governance`
   externally consumed, not earlier. Adding an unused barrel now would be speculative; the first task that wires
   `MetricsProvider` into a route is the natural place to add it, following the established precedent.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc --noEmit -p .                                 # clean
npm run lint                                          # clean (project-wide, --max-warnings=0)
npx vitest run --project unit src/features/monitoring # 5 files, 12/12 (was 2 files/2 tests before this task)
npm test -- --run                                     # full suite: 240 files, 1379/1379 green (was 237/1370)
npm run build                                          # production build succeeds
```

Task-specific acceptance items:

- **Zero hardcoded presets/caps** — `capabilityDefaults.ts` is the only module with a literal `10`, `'P1D'`, or
  `false` tied to a capability field; every consumer (currently just `useCapabilities`/`MetricsProvider`
  themselves) reads through the hook. No downstream task exists yet to violate this, so there is nothing further
  to grep for today — the discipline is structural (the type forces going through `Capabilities`), not just
  convention.
- **Fail-closed proven** — `useCapabilities.test.tsx` and `MetricsProvider.test.tsx` each have a rejection/500
  case asserting the result equals `FAIL_CLOSED_CAPABILITIES` exactly (`toEqual`, not a partial match) and
  `degraded: true`.
- **Retention never presented as assured** — no copy exists yet to violate this (no preset-rendering UI has been
  built; that's task 081). Nothing to test at this layer beyond what's above; flagging so this acceptance box is
  understood as *not yet applicable* rather than silently skipped.

### Not browser-verified

Unlike page-level tasks (e.g. 067), this task ships no route-reachable UI — `MetricsProvider` has no mount point
until a later Monitoring task wires one up. Verification here is unit/integration tests against the real MSW
handler plus the fake-repository unit tests above; there is nothing to click through yet.

## Status

DONE — implemented, tested (12/12 on the touched suite; full suite green, 240/240 files, 1379/1379 tests),
lint-clean, typecheck-clean, production build succeeds. Not yet marked done in `task-list.md`/`execution.md` or
committed — pending user confirmation. GitHub issue #84 (`FE-068 · MetricsProvider and capabilities (fail
closed)`) created this session — to be closed on commit.
