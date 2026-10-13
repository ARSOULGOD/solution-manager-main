# FE-078 · InstanceSelector with projected series count — Report

## What I implemented

Refs Dec `DM-D7` `DM-D23` · `dynamic-metrics` §11.4 · Conflict C6 · UI §A.10.3. Deps `077, 068`. Objective: **the
cap is enforced before the query, not after it fails.**

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/explore/instanceProjection.ts` (new) | Pure arithmetic: `seriesCountFor(instance, seriesBy)` (1 under `'instance'`, the instance's replica count under `'replica'`), `projectSeriesCount(instances, selectedIds, seriesBy)` (`Σ`), and `wouldExceedCap(instances, selectedIds, seriesBy, cap, candidate)` — same "rule out of the component" split `exploreGating.ts` (077) and `markAggregation.ts` (076) already established. |
| `frontend/src/features/monitoring/explore/instanceProjection.test.ts` (new) | Table-driven: both `seriesBy` modes, empty selection, an id not in the instance list, and `wouldExceedCap`'s per-candidate boundary (inclusive at the cap, a small candidate fitting while a large one doesn't at the same running total). |
| `frontend/src/features/monitoring/explore/InstanceSelector.tsx` (new) | The real step-2 control: `**N** / cap series projected` line, `per instance`/`per replica` note, a meter bar (`aria-hidden`, colour flips to `theme.palette.tone.err.main` past the cap), a scrollable checkbox list where each row shows its own contribution (`N sr`), and — only when at least one unselected row is blocked — an info panel with the two escape-route buttons. Controlled: selection, `seriesBy`, `cap`, `disabled` and the two escape-route callbacks are all caller-owned, matching `ControlStep`'s "caller disables its own real controls" contract. |
| `frontend/src/features/monitoring/explore/InstanceSelector.test.tsx`, `.a11y.test.tsx` (new) | Row rendering and per-row counts; projection in both modes; at-cap disabling; the per-candidate (not flat) disabling boundary; a disabled row is a genuine `disabled` control, not styled-only; switching `seriesBy` live-reduces selectability for the same selection; the cap panel never shows below the cap; both escape-route callbacks fire; `disabled` disables everything and suppresses the panel even past the cap. `jest-axe` below the cap, at the cap (panel shown), and disabled — both colour modes. |
| `frontend/src/features/monitoring/metricsRepository.ts` (modified) | `MetricInstance` gains `replicaCount: number`, with a `CONTRACT: TBD` comment — §5.5 specifies only the `entity-types/{t}/instances` route, not a response shape, and the projection cannot be computed client-side without a per-instance replica count somewhere in the catalog entry. |
| `frontend/src/shared/testdoubles/handlers/console.ts` (modified) | The instances MSW handler now includes `replicaCount: i.replicas.length`, sourced from the same `CACHE_INSTANCES` fixture `generateQueryResponse` already reads `.replicas` from — no new fixture data invented, just a field the handler had been dropping. |
| `frontend/src/features/monitoring/useCatalog.test.tsx` (modified) | Added a `replicaCount > 0` assertion alongside the existing instances-hook test. |
| `frontend/src/shared/ui/ToggleGroup/ToggleGroup.types.ts`, `.tsx`, `.test.tsx` (modified) | Added an optional `disabled` prop (`ToggleButtonGroup`'s own, threaded through) plus a test. Needed because `ControlStep`'s contract requires the caller to disable its own real controls when a step is locked, and `ToggleGroup` — named in its own docstring as covering "seriesBy" — had no way to be disabled. |
| `frontend/src/features/monitoring/explore/ExplorePage.tsx` (modified) | Step 2's placeholder becomes a wired `InstanceSelector` (data from `useEntityTypes()`/`useMetricInstances()`, cap from `useMetrics().capabilities.maxSeriesPerWidget`, `disabled={locks.instances.locked}`). Step 3's placeholder becomes a `ToggleGroup` bound to a new local `seriesBy` state, `disabled={locks.seriesBy.locked}`. Both stay gate-locked (hence non-interactive in the running app) because `metricState` is still hardcoded `null` — task 079 (`MetricSelector`) is what will make them reachable. |
| `frontend/src/features/monitoring/index.ts` (modified) | Barrel now also exports `MetricsProvider`/`MetricsProviderProps` — needed by the route wiring below. |
| `frontend/src/app/routes/ExploreRoute.tsx` (modified) | Now constructs `consoleClient` (via `createConsoleClient`, `RuntimeConfig`) and wraps `ExplorePage` in `<MetricsProvider>` — `ExplorePage` didn't call any `useMetrics()`/`useCatalog` hook before this task, so nothing had mounted the provider anywhere in the real app. Same construct-in-the-route pattern `InstanceCategoryRoute.tsx` already documents for its own repositories (`RuntimeConfig` is app-layer; a feature may not import it directly). |
| `frontend/src/features/monitoring/explore/ExplorePage.test.tsx`, `.a11y.test.tsx` (modified) | Both now wrap `ExplorePage` in a real `MetricsProvider` (fixture-backed via the global MSW server every test already runs behind). Added: step 2 renders real checkboxes, all disabled, and the projected cap threads from the fixture's `capabilities` response (`10`) rather than a literal; step 3's toggle buttons are disabled. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | New keys for `InstanceSelector`'s copy (`instanceSelectorProjectionSuffix`, `…PerInstance/PerReplica`, `…RowSeriesCount`, `…CapReachedTitle`, `…NarrowByLabel`, `…AggregateIntoOneLine`) and the seriesBy toggle's two option labels. Removed `exploreStepInstancesPlaceholder`/`exploreStepSeriesByPlaceholder`, now dead since those two steps render real controls. |

## Design decisions, and why

1. **Deliberate correction of the reference's cap-disabling logic.** `explore.jsx`'s `atCap` gate is flat: once
   the running total reaches the cap, *every* unselected instance disables, regardless of its own contribution.
   With instances of differing replica counts this both over- and under-disables — critically, it lets a large
   instance be selected right up to the boundary and then push the total *past* the cap in one click, which is
   exactly the failure this task's acceptance box forbids ("must not let a selection succeed and then fail at
   query time"). `wouldExceedCap` checks each unselected row against `projected + its own contribution > cap`
   instead, so no click this component allows can ever push the total over. `instanceProjection.test.ts`'s
   "per-candidate, not a flat gate" case is the direct proof: at the same running total, a 1-replica candidate
   fits while an 8-replica one doesn't.

2. **`replicaCount` added to `MetricInstance`, flagged as invented.** §11.4's whole cap-projection mechanism is
   `Σ replicas` under `seriesBy: 'replica'`, but §5.5 never specifies a response shape for the instances catalog
   route — only the route itself. Without a per-instance replica count somewhere in that catalog entry, this
   task's core arithmetic has no input. Same posture task 069 already set for this same route's other missing
   fields (`firstSeen`, a suggested-type field) — flag in source, don't invent silently elsewhere. The MSW handler
   change is not new fixture data: `CACHE_INSTANCES` (the underlying fixture) already carries a `.replicas` array
   that `generateQueryResponse` (task 033) already reads for the query-response `replicaComposition` field — the
   handler for the *instances* route was just dropping it.

3. **`ExploreRoute.tsx` now mounts `MetricsProvider`, not named in this task's own scope on paper.** `ExplorePage`
   had never called `useMetrics()`/`useCatalog` before this task — task 077 built it as a fully static placeholder
   shell. `InstanceSelector` needs real instances and a real cap, so this is the first task for which the missing
   provider mount is an actual blocker, not a latent gap. Constructed in the route, not the feature, for the same
   reason `InstanceCategoryRoute.tsx`'s own comment gives for its repositories: `RuntimeConfig` is an app-layer
   concern a feature may not import (module boundaries, task 009).

4. **Step 3's `seriesBy` toggle wired here too, and `ToggleGroup` extended with `disabled`.** No task's `Files`
   list names step 3 (`docs/plans/frontend/implementation.md` assigns 079 to the metric, 080 to aggregation/
   group-by — the "Series by" step itself has no owner beyond the shared `ToggleGroup` primitive, whose own
   docstring already names "seriesBy" as one of its five listed use cases). But `InstanceSelector`'s acceptance
   box explicitly requires demonstrating "switching to replica mode visibly reduces how many instances remain
   selectable" — unverifiable end-to-end without something to switch it with, and nothing else in the plan claims
   this control. Same "wire it, it's needed, nothing else claims it" reasoning task 077's report gives for wiring
   `ExploreRoute.tsx` itself. `ToggleGroup` had no `disabled` prop before this task even though `ControlStep`'s own
   contract (task 077) requires every caller placing a real control inside a locked step to disable it — a
   necessary, minimal extension, not scope creep into the shared kit.

5. **Both escape-route callbacks are real, typed, no-op functions in `ExplorePage`, not fake behaviour.**
   "Narrow by label" has no `LabelFilter` anywhere in the plan (no task claims one); "aggregate into one line"
   would set `acrossInstances`, owned by `AggregationControl` (task 080, not yet built). Rather than fabricate a
   `alert()` (the reference's own placeholder) or wire a state change that doesn't actually mean what the button
   says, both are committed no-ops with a comment naming why — consistent with steps 1/4/5 still being stated
   placeholders. `InstanceSelector`'s own tests (`vi.fn()` spies) are what actually prove "both escape routes
   work" per the acceptance box: that the component invokes whatever callback it's given, which is the contract
   task 078 owns. Neither button is reachable in the running app today regardless, since step 2 stays gate-locked
   until task 079 lands.

6. **The meter bar is `aria-hidden`.** The `**N** / cap series projected` text line is the single accessible
   source of the same information the bar shows visually; duplicating it into ARIA `progressbar`/`meter` semantics
   would either repeat the same number twice per screen-reader pass or need a distinct accessible name the design
   doesn't specify. No acceptance item asks for meter-role semantics.

7. **`ExplorePage` now owns `selectedInstanceIds` and `seriesBy` as local state**, not lifted anywhere. §9.2's own
   state-ownership table calls this "draft widget config | `ExploreView` local state | discarded on navigate-away
   unless saved" — this task is the first to introduce state that table already assigns a home to, so it lands
   there rather than in a new context/store invented for it.

8. **No `EntityTypeSelector`.** Design §9.1's component tree lists one; no `FE-*` task claims it. `ExplorePage`
   uses `useEntityTypes()`'s first result (today, fixture-backed, always the one `cache-instance` entry) — flagged
   in the file's own header comment rather than silently decided or invented as an unscoped extra control.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json --noEmit && npx tsc -p tsconfig.node.json --noEmit   # clean
npm run lint                                                                  # clean (project-wide, --max-warnings=0)
npx vitest run src/features/monitoring/ src/shared/ui/ToggleGroup \
  src/app/routes/ src/app/router.test.tsx --environment jsdom                 # 28 files, 180/180
npx vitest run --environment jsdom                                           # full suite: 264 files, 1568/1568
npm run build                                                                 # succeeds; ExploreRoute is its own
                                                                               # chunk (9.44 kB); echarts still
                                                                               # isolated to its own chunk
```

Task-specific acceptance items:

- **Projection counts series** — `instanceProjection.test.ts` proves `Σ 1` vs `Σ replicas` directly; `InstanceSelector.test.tsx`
  proves the same through the rendered `N / cap` line and confirms replica mode strictly raises the total for a
  fixed selection.
- **No silent truncation** — the per-candidate `wouldExceedCap` check (decision 1) means a row that would push the
  total over the cap is `disabled` before it can be checked, never after; `InstanceSelector.test.tsx`'s "genuinely
  disabled control" test confirms this at the DOM level (a real `disabled` input, not a styled-only one).
- **Cap is capability-driven** — no literal `10`/`20` appears in `InstanceSelector.tsx` or `ExplorePage.tsx`;
  `ExplorePage.test.tsx`'s new assertion confirms the rendered cap comes from the fixture's `/capabilities`
  response (`10`), not a hardcoded value, closing Conflict C6 (the reference's hardcoded 20) for this task's own
  code path — task 068 already owns the fail-closed-to-10 guarantee this reuses via `useMetrics()`.

### Not fully browser-verified

No dev server was started to click through the page. Both new controls stay gate-locked (hence non-interactive)
in the running app until task 079 (`MetricSelector`) supplies a real `metricState` — the same position task 077's
report recorded for its own not-yet-consumed placeholders. `InstanceSelector`'s full interactive behaviour
(selection, live projection, at-cap disabling, escape routes) is exercised directly by its own component tests,
independent of that gating.

## Status

DONE — implemented, tested (264/264 files, 1568/1568 tests full-suite), lint-clean, typecheck-clean, production
build succeeds. Not yet marked done in `task-list.md`/`execution.md` or committed — pending user confirmation.
GitHub issue for `FE-078 · InstanceSelector with projected series count` — to be closed on commit.
