# FE-073 · Gaps, coverage, thresholds, annotations — Report

## What I implemented

Refs Dec `UI-D8` `DM-D16` `SM-D31`, `dynamic-metrics` §11.2, §12, UI §A.10.5 (series rendering). Dep `072`
(`ChartRenderer` core) already done.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/chart/ChartRenderer.tsx` (modified) | Everything below lives inside `buildOption()` — no new chrome, same bare `<div>` task 072 left it as. Gap breaks fed as `null` boundary markers (`connectNulls: false`); shaded `markArea` coverage bands per series; hollow markers (`symbol: 'circle'`, transparent fill, series-colour border) on `lowCoverage` points, everything else `symbol: 'none'`; retention truncation as a `markArea` band + a dashed `markLine` boundary carrying the reason as its label; `inactive-replica`/`pending-config-change` series rendered dashed at 0.6 opacity (A.10.5); thresholds as `markLine` (colour from severity, dash pattern always stated, never colour alone); replica-count-change annotations as dashed vertical `markLine`s with a caret and the literal design-doc label text; a restricted log scale (`resolveScale`, exported) with a `graphic` text notice when non-positive values are excluded under it. |
| `frontend/src/features/monitoring/chart/ChartRenderer.test.tsx` (modified, +12 tests) | Gap break + shading, same-timestamp collision safety, hollow markers, retention band/boundary, thresholds (colour+dash), annotation label text, dimmed inactive-replica line, `resolveScale` (3 cases), log-scale-stays-linear-for-counter-rate, log-scale-excludes-non-positive-with-notice. |
| `frontend/contracts/metrics/types.ts` (modified) | New `ReplicaCountChange { ts, delta }`, added as an optional `replicaCountChanges` field on `QueryResponseSeries` and `ChartSeries` (present only when non-empty, mirroring `truncatedBand`'s convention). New `ThresholdSeverity` and `ChartThreshold { value, label, severity, style? }`, mirroring §7's `thresholds` schema slice — declared here (not local to the component) because §9.4 names threshold rendering as one of the two reasons a Java `projectSeries` exists at all. |
| `frontend/contracts/metrics/projectSeries.ts` (modified) | `projectReplicaCountChanges`: field-by-field passthrough, `undefined` when absent or empty — same "never invent, never leak unknown fields" discipline as every other projection helper in this file. |
| `frontend/contracts/fixtures/metrics/replica-count-change-carried-through.json`, `replica-count-change-schema-migration.json` (new) | The new field's passthrough and unknown-field-stripped cases, one fixture per obligation per the fixture README's own rule. |
| `frontend/contracts/fixtures/README.md` (modified) | Documented `replicaCountChanges`'s absent-vs-present convention. |

No changes to `useEcharts.ts` (still ECharts-agnostic, task 072's file), `eslint.config.js`, or `ChartWidgetConfig`
(thresholds/scale deliberately kept out of it — see decision 2).

## Design decisions, and why

1. **Everything is an `EChartsOption` concern, not new component chrome.** Task 072's docstring said this
   explicitly ("Gap bands, thresholds, tooltip and legend are FE-073/074's job, layered onto this option shape —
   this task owns only the plumbing"). Kept the returned JSX untouched; the "visible notice" for excluded
   non-positive values and the retention "notice" both render as in-canvas ECharts elements (a `markLine.label`
   for the retention boundary, a `graphic` text for the log-scale exclusion) rather than new MUI chrome. This is
   also what keeps the Testing note's promise — "asserted on the ECharts option object, not a screenshot" — true
   for the whole task, not just the gap case it was written about.

2. **Thresholds and log scale are `ChartRenderer` props, not additions to `ChartWidgetConfig`.** `task-071-report`
   decision 2 scoped `ChartWidgetConfig` to *only what `projectSeries` itself consults*; a threshold or a scale
   choice changes nothing about the projected data, only how it's drawn, so folding them in there would blur that
   boundary for no reason. `ChartThreshold` is still declared in `contracts/metrics/types.ts` (not locally) because
   §9.4 says a Java `projectSeries` exists partly *for* "server-side rendering of thresholds" — so the shape needs
   to be portable even though the value doesn't flow through `projectSeries`. `scale` has no such Java need and
   stays a bare `'linear' | 'log'` prop, no shared type.

3. **`replicaCountChanges` is server data (`QueryResponseSeries`), not widget config — the label text is not.**
   §4.4: "replica count changes are annotated on the time axis" is a fact about what the *server* observed, so it
   belongs on the wire shape next to `gaps`, projected through unchanged (obligation-7-style: never dropped, never
   invented). The literal message text ("replica added (+1) — summed series steps; not a workload change") is
   generated in `ChartRenderer` (`formatReplicaCountChangeLabel`), not in `projectSeries` — `contracts/` stays
   structural data, consistent with every other projection helper never inventing a display string, and consistent
   with the existing 17 fixtures (and `run.test.ts`'s exact deep-equal) not needing a hand-maintained English
   sentence baked into JSON.
   ⚠️ **Flagging, not resolving:** the design gives no formal name or spec for this event stream beyond the one
   §4.4 sentence and the one A.10.5 example — `ReplicaCountChange { ts, delta }` and the label format are my
   reasonable reconstruction of "annotated on the time axis" for that one named case, not a cross-checked schema.

4. **Gap-boundary breaks are two `null` markers (`gap.from` and `gap.to`), not one.** `connectNulls: false` means
   any line segment touching a `null` value is skipped on *either* side, so a single boundary marker is
   topologically sufficient to break the whole gap span — verified this against `LineView.js`'s own segment-skip
   logic, not assumed. Feeding both edges is a visual-precision choice (the break starts and ends exactly at the
   server's stated boundary), not a correctness requirement; documented as such in the code so a future reader
   doesn't take "both edges" as load-bearing.
   Normal points stay as plain `[ts, value]` tuples — only points that need per-item styling (low-coverage,
   gap-null, log-excluded) become the object form. This is what keeps task 072's own `data` assertion
   (`ChartRenderer.test.tsx`'s "sets one line series per ChartSeries" test) passing **unmodified**: a series with
   no gaps and no low-coverage points produces the exact same plain-tuple array it did before this task.

5. **`showSymbol: true` + series-level `symbol: 'none'`, not `showSymbol: false` with per-item overrides.**
   Verified against `LineView.js` line 516 (`if (!showSymbol) { symbolDraw.remove(); }`) that `showSymbol: false`
   skips `symbolDraw` entirely — no per-item `symbol` override can force a marker to render once that's set. This
   is exactly the kind of "verify against installed source, don't guess from the type defs" case `I2` and
   `task-072-report`'s own precedent call for.

6. **Markers/bands attach to `series[0]`, per-series things attach to each series.** Gaps and replica-count-change
   annotations are per-series facts, so each series carries its own `markArea`/`markLine` entries for them.
   Thresholds and the retention band/boundary are chart-wide facts (one metric per widget, `DM-D23`) — ECharts
   draws a series's `markArea`/`markLine` across the whole coordinate system regardless of which series it's
   attached to, so there's no reason to duplicate them onto every series; they ride on `series[0]` only, guarded
   by `model.series.length > 0` implicitly (an empty series array attaches nothing, rather than throwing).

7. **Threshold/band/boundary colours come from `theme.palette.tone.{warn,err}` and `theme.palette.action.hover`,
   not new hex literals.** `palette.ts`'s own stated philosophy is "nothing here invents a colour" — reused the
   existing `tone` vocabulary (already used by `Chip`/`StateChip`) for severity, and `action.hover` (the existing
   generic "dimmed region" token) for gap shading, rather than adding a parallel colour system for charts.

8. **`⚠️ Not implemented: A.10.5's "gaps also break at more than 1.5× the gap threshold."`** A.10.5 (the reference
   mockup's UI spec) has this second sentence on gap breaks; `DM-D16` obligation 2 (already shipped and tested in
   `projectSeries`, task 071) says the client never re-derives `gapThresholdSeconds` — the field isn't even read by
   `projectSeries`. These conflict. Per `CLAUDE.md`'s rule for a `SM-D*`-vs-`LOCKED` conflict — flag it, don't
   silently resolve it — I did not implement client-side threshold-based gap derivation; the chart breaks only on
   server-declared `gaps[]`, matching the already-shipped, tested contract. Flagging here rather than in
   `DECISIONS.md` since this is an implementation-time conflict between two spec documents, not a new decision
   needing ratification — happy to escalate if that's wrong.

9. **`DIMMED_LINE_OPACITY = 0.6`** — A.10.5 says "reduced opacity" with no number. Picked a value that stays
   legible against both light and dark backgrounds; flagged inline in the code as an interpretation, not a spec
   value (same honesty convention A.10's own 🅸 markers use elsewhere in this doc).

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json --noEmit && npx tsc -p tsconfig.node.json --noEmit   # clean
npm run lint                                                                    # clean (project-wide, --max-warnings=0)
npm run lint:fixtures:assert                                                    # single-import-site guardrail still proven (10/10)
npm run check:forbidden-deps                                                    # clean
npx vitest run --project contracts                                              # 4 files, 55/55 (was 53/53)
npx vitest run --project unit src/features/monitoring/chart                     # 2 files, 27/27 (was 15/15)
npm test                                                                         # full suite: 246 files, 1449/1449 green (was 246/1435)
npm run build                                                                    # production build + assert-echarts-chunk.mjs, both succeed
```

Task-specific acceptance items:

- **No zero-fill, no connect-across, in any path** — gaps feed `null` boundary markers with `connectNulls: false`
  (test: "feeds a gap as null boundary markers, breaking the line"); the log-scale exclusion path does the same
  rather than dropping the point silently (test: "excludes non-positive values under a log-scale gauge... never
  silently"); a gap-boundary `null` can never displace a real point at the same timestamp (dedicated collision
  test).
- **Thresholds carry two channels** — the "renders thresholds as markLine carrying both colour and dash pattern"
  test asserts `warn`/`crit` get different `lineStyle.color` *and* an explicit `type` (`dashed`/`dotted`), never a
  bare colour-only line.
- **Log scale restricted with a stated reason** — `resolveScale`'s three unit tests plus the two `ChartRenderer`
  integration tests (`yAxis.type` stays `'value'` for a counter-rate fixture requesting `'log'`; stays `'log'` and
  produces a `graphic` notice for a gauge with non-positive values).

### Not browser-verified

Same position as tasks 071/072: `ChartRenderer` has no route/consumer yet (Explore/Dashboard wiring is later
Monitoring tasks). Verification here is the test suite plus the isolated build/chunk check above.

## Status

DONE — implemented, typecheck-clean, lint-clean (project-wide), single-import-site guardrail intact, contracts
fixture suite 55/55, chart test suite 27/27, full suite green (246/246 files, 1449/1449 tests), production build +
chunk-isolation check both succeed. Not yet marked done in `task-list.md`/`execution.md` or committed — pending
user confirmation. GitHub issue for `FE-073 · Gaps, coverage, thresholds, annotations` — to be closed on commit.
