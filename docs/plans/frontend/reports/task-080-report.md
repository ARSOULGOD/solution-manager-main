# FE-080 · Aggregation and group-by controls — Report

## What I implemented

Refs Dec `DM-D8` `DM-D22` · `dynamic-metrics` §5.7 · UI §A.10. Deps `077, 079`.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/explore/aggregationOptions.ts` (new) | Pure `DM-D8` rule: `permittedAggregationFunctions(metricType, capabilityAggregations)`. `gauge`/`counter` permit `sum/avg/min/max` (same UI list, different server-side formula); `histogram` permits none (unsupported in v1); `null` (untyped) permits none. Always intersected with `capabilities.aggregations` and always returned in canonical `sum, avg, min, max` order regardless of the input array's own order — never a hardcoded option list. |
| `frontend/src/features/monitoring/explore/aggregationOptions.test.ts` (new) | Gauge/counter with full capabilities → all four in order; histogram → `[]` regardless of capabilities; `null` → `[]`; a restricted capability set (`['sum','avg']`) → only those two (proves real intersection, not just type-based); an out-of-order capability array still canonicalizes. |
| `frontend/src/features/monitoring/explore/AggregationControl.tsx` (new) | Step 4's real control. One `Select` (`@shared/ui`) bound to `acrossInstances`, its own field label ("Across instances", distinct from the step header's own "Aggregation" text one line above), options built from `permittedAggregationFunctions`. Forcing (`DM-D22`): reuses `marksRequiringAggregation` from `contracts/metrics/markAggregation.ts` (task 076) rather than reimplementing it; when forced, the `'none'` *option* is disabled and an effect corrects the value to the first permitted function if it's still `'none'` — the control stays interactive throughout, never the whole field disabled. Renders a forced-aggregation caption and (independently) a histogram-unsupported caption. |
| `frontend/src/features/monitoring/explore/GroupByControl.tsx` (new) | Step 5's real control. Empty `labelKeys` (7 of the current fixture catalog's 8 entries) renders a stated caption, not a silently-empty control. Non-empty renders one MUI `ToggleButton` per key in a non-exclusive `ToggleButtonGroup` (the shared `ToggleGroup` is hard-typed single-exclusive and not reusable here without widening its public type for one call site). |
| `frontend/src/features/monitoring/explore/{AggregationControl,GroupByControl}.test.tsx`, `.a11y.test.tsx` (new) | Options are type-permitted ∩ capability-advertised in canonical order; histogram offers only `'none'` plus its caption; a `gauge`/`single-stat` `mark` forces away from `'none'` and shows the caption (passed directly as a prop — nothing in the running app can supply a non-`'line'` mark yet, see residual 1); a `'line'` mark never forces; an already-non-`'none'` value is left undisturbed while forced; `disabled` disables the `Select`. `GroupByControl`: empty vs. populated `labelKeys`, click-to-toggle add/remove, `aria-pressed` reflects state, `disabled` disables every button. `jest-axe`, light+dark, across {unforced, forced, disabled, histogram-unsupported} / {empty, populated, disabled}. |
| `frontend/src/features/monitoring/explore/ExplorePage.tsx` (modified) | Steps 4/5's `Typography` placeholders become the two real controls. New `acrossInstances`/`groupBy` state feeds `evaluateExploreGating` directly (no more hardcoded `'none'` literal). Selecting a different metric now resets both — a stale `groupBy` key or aggregation function can be invalid for the newly-selected metric's own `labelKeys`/`metricType`. `InstanceSelector`'s "Aggregate into one line" escape route is wired for real (`setAcrossInstances('avg')`, matching the reference's own `setAggInst("avg")`). `mark` is hardcoded to `'line'` — see residual 1. |
| `frontend/src/features/monitoring/explore/ExplorePage.test.tsx` (modified) | Added: step 4 renders the real, gate-disabled `Select`; selecting a plottable metric enables it; selecting `cache.hit_rate` and setting aggregation shows its `region` group-by toggle; selecting `cache.hit_count` (no `labelKeys`) shows the no-labels caption instead; reaching the instance cap and clicking "Aggregate into one line" really sets aggregation and unlocks step 5; switching the selected metric resets aggregation/group-by and re-locks step 5. No existing assertion needed to change. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | Removed the now-dead `exploreStepAggregationPlaceholder`/`exploreStepGroupByPlaceholder`. Added `aggregationControlFieldLabel`, `…NoneLabel`/`…SumLabel`/`…AvgLabel`/`…MinLabel`/`…MaxLabel`, `…ForcedCaption` (interpolates `{mark}`), `…HistogramUnsupportedCaption`, and `groupByControlNoLabelsCaption`. |

## Design decisions, and why

1. **`acrossReplicas` is not a control.** Every JSON example in `dynamic-metrics/design.md` sends `"acrossReplicas": "auto"`; the component tree (§9.1) lists only `<AggregationControl>` (for `acrossInstances`) and `<GroupByControl>` — no third replica-aggregation control. Replicas are folded into each instance's line type-awarely by default (`DM-D4` §4.4); `seriesBy: "replica"` (task 078) is the only override, and it bypasses replica aggregation entirely rather than choosing a function for it. `AggregationControl` only ever drives `acrossInstances`.

2. **Forcing (`DM-D22`) is an auto-correct, not a disable-only.** The visual reference (`explore.jsx`) disables the whole `<select>` when a gauge/single-stat mark is active, while leaving the value stuck at `"none"` — a forced-but-still-`none` state, which is exactly the shape `DM-D22` exists to refuse. This control instead disables only the `'none'` *option* and corrects the value with a `useEffect`, so a forced draft is never left sitting in an already-known-refused shape.

3. **Forces on `mark` alone, not on instance count.** `DM-D22`'s own decision text: *"The `AggregationControl` forces a non-`none` value when either mark is selected"* — not conditioned on how many instances are selected. Conditioning on instance count would need an un-force path when the operator drops back to one instance; forcing unconditionally on mark avoids that flicker state machine entirely. The count-conditioned *refusal* check (`evaluateMarkAggregation`) stays `markAggregation.ts`'s job for wherever a query is eventually issued.

4. **`aggregationOptions.ts` lives feature-side (`explore/`), not in `contracts/metrics/`.** Unlike sibling `markAggregation.ts` (a portable mirror of one server refusal code, deliberately import-nothing-app-side because a future Java implementation reads that directory too), this rule needs live `capabilities.aggregations` — an app-side deployment concern. Symmetric with its direct siblings `exploreGating.ts`/`instanceProjection.ts`, both feature-scoped for the same reason.

5. **The `Select` gets its own field label, distinct from the step header's "Aggregation" text.** I initially reused `exploreStepAggregationTitle` (the step's own title) as the field's accessible label, since the reference's own `<select>` has no visible label at all. That printed "Aggregation" twice in the same step and broke an existing test (`getByText('Aggregation')` found two matches) — a real UI redundancy, not just a test artifact. `aggregationControlFieldLabel: 'Across instances'` gives the field its own, more specific label.

6. **`GroupByControl`'s empty state is a plain caption, not `EmptyState`.** `EmptyState` is sized for a block placeholder (the result column's "nothing to plot yet"), not a one-line sidebar caption — and 7 of 8 fixture metrics hit this state, so it needed to look like the deliberate common case it is, not an under-built one.

## Residuals (flagged, not silently decided)

1. **`mark` hardcoded to `'line'` in `ExplorePage`.** No `FE-*` task has claimed a mark toggle anywhere yet — the result column is still the task-077 placeholder. `AggregationControl`'s `DM-D22` forcing logic is real and unit-tested against a real `mark` prop (passed directly in tests), but has no live trigger in the running app until a mark control lands.
2. **`handleNarrowByLabel` remains a genuine no-op** — no `LabelFilter` control exists or is scheduled by any task; only the "Aggregate into one line" escape route became real in this task.
3. **`acrossReplicas` stays an implicit `"auto"`** — no task has built a `QueryRequest` call site yet.
4. **Only `cache.hit_rate` in the current fixture catalog has any `labelKeys`** (`['region']`) — `GroupByControl`'s populated state is exercised by exactly one fixture entry; its empty-caption state is the majority case in the test suite by necessity, not under-coverage.

## Testing

Every command run from `frontend/`:

```
npm run typecheck   # tsc -p tsconfig.json && tsc -p tsconfig.node.json — clean
npm run lint        # eslint . --max-warnings=0 — clean
npx vitest run src/features/monitoring/explore/   # 17 files, 122/122
npx vitest run                                    # full suite: 273 files, 1637/1637
npm run build                                     # succeeds; echarts still isolated to its own chunk
```

**Browser-verified**, not just tested — `GroupByControl`'s wrapping toggle row and `AggregationControl`'s popover
were the layout-risk areas, given FE-079 caught a real flex-layout bug this way that no automated test could:

```
npm run dev -- --port 5173 --strictPort
```
Drove it with a throwaway Playwright script against
`/solutions/cache/instances/cache-prod-01/monitoring/explore` (script discarded, not committed):
- `cache.hit_rate` → opened "Across instances", confirmed all five options in the exact expected order and text.
- Selected "sum" → step 5 unlocked live, `region` toggle button appeared; clicked it, `aria-pressed` flipped
  `false → true`.
- Switched to `cache.hit_count` (no `labelKeys`) → aggregation reset to `"none"` and step 5 re-locked
  automatically (the metric-change reset, confirmed live, not just in the unit suite); the no-labels caption
  rendered.
- Re-selected `cache.hit_rate`, set aggregation again, narrowed the viewport to 380px → no overflow, no wrapping
  issue, sidebar-only single-column layout renders cleanly.
- `console --errors` equivalent (a `page.on('console'/'pageerror')` listener): zero errors across the whole run.

No layout defect found — unlike FE-079, this task's two controls did not surface a real browser-only bug.
