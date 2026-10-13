# FE-071 · `projectSeries` and its shared fixture suite — Report

## What I implemented

Refs `DM-D20` `DM-D16` `UI-D8` `SM-D26` `SM-D27`, `dynamic-metrics` §9.4, §5.6. Dep `008` (test harness) already done.

| File | Summary |
|---|---|
| `frontend/contracts/metrics/types.ts` (new) | The shared `contracts/metrics` vocabulary flagged as this task's job by `reports/task-070-report.md` note 1: the range-query wire shape (`QueryResponse`, `SeriesState`, `Point`, …) `projectSeries` consumes, plus its own output vocabulary (`ChartModel`, `ChartSeries`, `ChartPoint`, `ChartGap`, `ChartCoverage`) and two task-scoped input slices — `ChartTheme` (a pure mirror of `echartsTheme.ts`'s `color: string[]`, since `contracts/` cannot import a module that pulls in `@mui/material`) and `ChartWidgetConfig` (only the `series.colors` slice of the still-unbuilt `DM-D6` schema, mirroring `buildQuery.ts`'s `WidgetQueryConfig`). |
| `frontend/contracts/metrics/projectSeries.ts` (new) | `projectSeries(queryResponse, widgetConfig, theme) → ChartModel`. Pure, field-by-field reconstruction (never a spread or object-reference passthrough) of every nested shape, which is what makes rule 4 below hold structurally rather than by convention. |
| `frontend/contracts/fixtures/metrics/*.json` (new, 17 files) | The shared fixture suite — one file per obligation/scenario, named for what it proves. |
| `frontend/contracts/fixtures/metrics/run.test.ts` (new) | Reads every `*.json` in the directory, calls `projectSeries`, asserts a structural deep-equal against `expected`, plus a direct per-series point-count check (obligation 1). Mirrors `view-model/run.test.ts` (task 046) exactly. |
| `frontend/contracts/fixtures/README.md` | Generalized from a `view-model`-only document to cover both fixture suites: a new "`metrics` fixture file shape" section, the key-ordering/number-formatting rules extended to point at `metrics/types.ts` too, and a new pinning rule for `metrics/*.json`'s absent-vs-empty conventions (`points: []`/`gaps: []` vs. omitted, `truncatedBand` present only when truncated). |

No changes to `buildQuery.ts`, `canonicalHash.ts` or `metricsRepository.ts` — each keeps its own pre-existing copy of the wire shape for the same "`contracts/` imports nothing app-side" reason `buildQuery.ts` documents; consolidating those into this task's `types.ts` is a separate, deliberate change I did not fold in here.

## The eight obligations, and how each is proven

1. **A bucket with no samples produces no point.** Structural: `points.map(projectPoint)` is 1:1 with the input array; nothing invents an entry. `absent-sample-produces-no-point.json` proves the count and values are unchanged; `run.test.ts`'s own per-series length assertion is a second, independent check beyond the deep-equal.
2. **Gaps are server-declared; the client never re-derives the threshold.** `projectSeries` does not read `gapThresholdSeconds` at all — the field isn't even in `ChartModel`. `gap-passes-through-unchanged.json` proves `gaps[]` round-trips unchanged.
3. **No delta is computed across a gap.** The module contains no subtraction between points anywhere. `no-delta-computed-across-gap.json` uses values (`5`, then `3` after a restart-gap) that a naive raw-counter diff would get wrong, and asserts they pass through exactly as given.
4. **Counters render as rate or delta from `render.transform`, never a raw climb.** `render` is passed through field-by-field, never accumulated. Two fixtures: `counter-renders-as-rate-with-rateSeconds.json` (transform present with `rateSeconds`) and `counter-renders-as-delta-without-rateSeconds.json` (transform present, `rateSeconds` correctly *absent* — exercises the same optional-field-omission pattern `buildQuery.ts`'s `groupBy` uses).
5. **`sampleCount < expectedCount` marks a point low-coverage, uniformly.** One `lowCoverage` computation, no resolution-tier branch. Two fixtures, `low-coverage-marker-at-hourly-tier.json` and `low-coverage-marker-at-raw-tier.json`, prove the rule holds at both tiers by construction rather than by a tier-specific test only.
6. **`truncatedByRetention` yields a boundary and a band.** The boundary is `servedFrom` (already on `ChartCoverage`); the band is `truncatedBand: { from: requestedFrom, to: servedFrom }`, present only when truncated. `retention-truncation-boundary-and-band.json` and `no-truncation-band-when-not-truncated.json` cover both branches — the second proves the key is *omitted*, not `null`, when there is nothing to truncate.
7. **Per-series `state` is carried through, and a series is never dropped for having zero points.** `series-state-carried-through.json` exercises all five `SeriesState` values in one chart; `zero-point-series-not-dropped.json` proves a wholly-inactive-replica series with `points: []` still appears in `ChartModel.series` (so the legend can show it, per §12).
8. **Colours are sequential by array position, never hashed; past palette size, dash pattern disambiguates rather than a bare reused colour.** Four fixtures:
   - `colors-sequential-by-position-not-hashed.json` — series ids ordered `zzz, aaa, mmm`; colours follow array position, not alphabetical/hash order.
   - `colors-wrap-with-dash-pattern-past-palette-size.json` — a 2-colour theme with 3 series; the third wraps to the first colour with `dashPattern: "dashed"`.
   - `explicit-color-override-honoured.json` — `widgetConfig.series.colors` is honoured for the overridden instance and does **not** consume a sequential palette slot for the next series.
   - `override-collision-falls-back-to-sequential.json` — two series both request the same override colour; the second falls back to the next sequential `(colour, dashPattern)` pair rather than colliding.

Plus the two case categories `DM-D20`'s rationale names by name beyond the eight numbered obligations:

- **Replica-aggregation** — `replica-aggregation-composition-carried-through.json` proves `replicaComposition` (a partial, non-wholly-inactive case) survives projection untouched.
- **Schema-migration / forward-compatibility** — `schema-migration-forward-compatibility.json` adds unknown fields at three levels (top-level `QueryResponse`, `metric`, and one `series` element) and asserts they are silently dropped, never surfacing in `ChartModel` and never throwing. This is what caught a real bug during implementation — see below.

## Design decisions, and why

1. **Collision-avoidance keys on the `(colour, dashPattern)` pair, not colour alone.** An earlier draft tracked only used *colours*, which meant a 2-series-past-palette-size case correctly wrapped, but a 2×-palette-size case (every colour already "used" once) would spin forever looking for a colour that had literally never been assigned — because the design's own intent is for colour *to* repeat past palette size, with dash pattern as the second channel. Keying on the pair matches §12's actual model and is what `colors-wrap-with-dash-pattern-past-palette-size.json` and `override-collision-falls-back-to-sequential.json` both verify. A `maxAttempts` bound stops a pathological input (far more series than the palette has solid/dashed slots for) from spinning; the real backstop is `DM-D7`'s series cap, enforced before a response ever reaches this function.
2. **`ChartWidgetConfig` is a task-scoped slice, not a copy of the full `DM-D6` schema.** That schema (`schemaVersion`, `axes`, `legend`, `thresholds`, migrations, …) is task 084's to build. Defining only `series.colors` here mirrors `buildQuery.ts`'s `WidgetQueryConfig` precedent exactly — a slice owned by the task that needs it, not a premature guess at a schema three tasks away.
3. **`ChartTheme` is a new, minimal interface, not an import of `echartsTheme.ts`.** `src/shared/theme/echartsTheme.ts` already exposes exactly the `color: string[]` this function needs, but importing it — even for its type — would pull `@mui/material`'s `Theme` into `contracts/`, which the eslint boundary rule (task 046's acceptance box) forbids. The caller (a future task wiring `<ExploreView>`/`<DashboardToolbar>`) adapts `echartsTheme(muiTheme).color` into `{ categoricalColors }` at the call site.
4. **Every nested object in the return value is reconstructed field-by-field — never `metric: queryResponse.metric` or similar.** This is not just style: it is the mechanism that makes obligation "schema-migration" hold. I initially wrote `metric: queryResponse.metric` as a direct passthrough; `schema-migration-forward-compatibility.json` failed immediately because an unknown `futureMetricField` on the input leaked into the output. Fixing it to `{ name: ..., metricType: ..., unit: ... }` (and doing the same for `seriesRef` and `replicaComposition`, which had the same latent issue) is what §5.3's "clients must ignore unknown response fields" actually requires in code, not just in the common case.
5. **`ChartModel` omits `resolution`, `gapThresholdSeconds` and `partial`.** None of the eight obligations or the two named test categories reference them: `gapThresholdSeconds` is explicitly the server's alone (obligation 2); `resolution` and `partial` belong to concerns later tasks own (dashboard-level degradation states, tooltip bucket-size context) rather than this pure projection step. Adding fields nothing yet consumes would be exactly the premature-schema problem decision 2 above avoids.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json && npx tsc -p tsconfig.node.json   # clean
npm run lint                                                 # clean (project-wide, --max-warnings=0)
npx vitest run --project contracts                            # 4 files, 53/53 (new: metrics fixture suite, 17/17)
npm test                                                       # full suite: 244 files, 1416/1416 green (was 243/1399)
npm run build                                                  # production build succeeds
```

Task-specific acceptance items:

- **No fixture produces a zero-filled point** — every fixture's point count matches its input exactly; `run.test.ts` asserts this independently of the deep-equal, per series.
- **Colours never collide within a chart** — verified by the `(colour, dashPattern)`-pair collision avoidance in decision 1 above, exercised by `colors-wrap-with-dash-pattern-past-palette-size.json` and `override-collision-falls-back-to-sequential.json`.
- **Suite runnable by the Java CI** — `contracts/fixtures/README.md`'s pinning conventions (key order, number formatting, absent-vs-empty) extended to cover `metrics/*.json`, same mechanism as task 046's `view-model/*.json`.

### Not browser-verified

Pure contract-layer function with no UI consumer yet (`<ChartRenderer>` is task 072). Verification is the fixture suite above; there is nothing to click through.

## Status

DONE — implemented, tested (53/53 on the touched `contracts` project; full suite green, 244/244 files, 1416/1416
tests), lint-clean, typecheck-clean, production build succeeds. Not yet marked done in `task-list.md`/
`execution.md` or committed — pending user confirmation. GitHub issue for `FE-071 · projectSeries and its shared
fixture suite` — to be closed on commit.
