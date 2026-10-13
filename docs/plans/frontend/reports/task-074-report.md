# FE-074 · Tooltip and legend — Report

## What I implemented

Refs Dec `DM-D13` `DM-D7` `SM-D27`, `dynamic-metrics` §12, UI §A.10.6 (tooltip · legend). Dep `073` (gaps,
coverage, thresholds, annotations) already done.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/chart/ChartRenderer.tsx` (modified) | Adds `tooltip` and `legend` to the `EChartsOption` returned by `buildOption()` — same "everything lives inside `buildOption()`" shape tasks 072/073 established, no new component chrome. A shared-axis tooltip (`buildTooltipRows`, `renderTooltipHtml`) built from `model.series` directly rather than from ECharts' own axis-trigger `params`; a scrolling vertical legend (`type: 'scroll'`, `orient: 'vertical'`) whose `data` always lists every series' label, with a `formatter` appending a rich-text state-tag token for any non-`ok` series. A new `legendMaxHeightRows` prop (default 4, from §5's own example) bounds the legend's pixel `height`; a `grid.right` reservation keeps the plot from running under it. |
| `frontend/src/features/monitoring/chart/ChartRenderer.test.tsx` (modified, +8 tests) | Tooltip: sort-by-value-descending with a state tag, `gap` (not the series' declared state, not blank, not zero) for a series with no point at the cursor, unit-formatted values, and a no-op for an unrecognisable axis position. Legend: membership never reduced (5 series → 5 entries) independent of `legendMaxHeightRows`, height bounded without dropping membership, and a state tag appended only for non-`ok` series. Plus a `grid.right` reservation check. |

## Design decisions, and why

1. **The tooltip row list is built from `model.series`, never from ECharts' own axis-trigger `params`.** I read
   `axisTrigger.js` (`buildPayloadsBySeries`) before writing this: for an axis trigger on a continuous value/time
   axis, ECharts finds each series' own *nearest* data point to the raw cursor position, then keeps only the
   series tied for the **globally** closest match (`dist <= minDist`, resetting the batch on every strictly closer
   series) — every other series is silently dropped from `params` entirely. That is precisely the "blank, not
   `gap`" failure §12's acceptance box rules out ("A series with no point at the cursor renders `gap`, not a blank
   and not a zero"), so a stock `formatter` reading `params` can't satisfy the requirement regardless of how it's
   written. The fix: use only `params[0].axisValue` (always a real point's own timestamp — `axisTrigger.js` always
   passes `snapToValue`, one series' own actual data value, to `showTooltip`, regardless of any `snap` setting) as
   the hovered instant, then look up every series in `model.series` against that instant directly.

2. **A series "has a point at the cursor" by exact timestamp match against `ChartSeries.points` — the pure domain
   array — never against the ECharts series `data` array.** `buildSeriesData` (task 073) injects synthetic
   gap-boundary `null` markers into the *chart's* data array that don't exist in the domain model; searching
   `points` instead means those markers can never masquerade as real data, and — more importantly — a row reads
   `gap` for **any** missing point at the cursor, not only ones inside a formally declared `gaps[]` window. That
   matches the acceptance box's literal wording ("a series with no point... renders gap") rather than narrowing it
   to declared gaps only.
   ⚠️ **Precondition, stated not proven**: this assumes all series in one `ChartModel` share one bucket grid (one
   query, one `resolution.bucketSeconds`, per `dynamic-metrics` §5.6) — true by construction of `buildQuery`
   (`DM-D23`: exactly one metric per request) but not re-verified here.

3. **`Date.parse` vs ECharts' own `parseDate`, verified rather than assumed to agree.** Read `util/number.js`'s
   `parseDate`: for an ISO string carrying an explicit offset (every fixture and every projected `ts` carries `Z`),
   it builds the value via `Date.UTC(...)` from the same regex-captured fields `Date.parse` itself would use for a
   `Z`-suffixed string — so no epsilon/rounding tolerance is needed when comparing `Date.parse(point.ts)` against
   the numeric `axisValue` ECharts reports. Flagged inline in the code, same "verify against installed source, not
   the type defs" standard `task-073-report` decision 5 set.

4. **A `gap` row overrides the series' own declared `state`, it doesn't combine with it.** A series can be
   `type-suspect` (or `inactive-replica`, etc.) *and* have a gap at some instant — at that instant there's nothing
   to attach the declared-state tag to, so the row shows `gap` alone (test: "renders `gap`... for a series with no
   point at the cursor" asserts the row does **not** also show `type-suspect`). Outside a gap, the row shows the
   series' own state tag as normal.

5. **State-tag vocabulary and colour: `gap` (synthetic, per-cursor) plus one tag per non-`ok` `SeriesState`,
   sharing the tooltip's HTML rendering and the legend's rich-text rendering off one `StateTag` table.** §12/A.10.6
   name five tags (`gap · inactive replica · pending · type-suspect · best-effort`) and `SeriesState` has exactly
   four non-`ok` members plus the synthetic `gap` — a clean 1:1 map (`ok` renders no tag, matching the
   `contracts/metrics/types.ts` comment that `ok` is "visited by no rule"). Neither document gives a colour per
   tag, so I mapped onto the existing `tone` vocabulary (`palette.ts`: "nothing here invents a colour") rather
   than a fresh one: `type-suspect → tone.warn` (matches A.10.1's "persistent warn alert" for the same state
   elsewhere in the app), `pending-config-change → tone.info` (matches `palette.pending`'s own hue),
   `inactive-replica` and `unverified-durability` both → `tone.idle` (both match `palette.health.inactiveReplica`'s
   and `palette.unverified`'s hue, `tokens.accent.slate`). Sharing one tone between two tags is a deliberate
   "no invented colour" trade — the label text, not the colour, disambiguates them. Flagged inline, same convention
   as `task-073-report`'s `DIMMED_LINE_OPACITY` note.

6. **Legend is `orient: 'vertical'` + `type: 'scroll'` with an explicit pixel `height`, not `orient: 'horizontal'`.**
   §12's own wording — "`maxHeightRows` bounds its **height**" — only makes sense for a vertical list: ECharts'
   scroll-type legend is a single scrolling row (horizontal) or single scrolling column (vertical), never a
   multi-row wrap, so "N rows of visible height, more scroll" is achievable only in the vertical orientation
   (`height = legendMaxHeightRows × LEGEND_ROW_HEIGHT`). `§5`'s one JSON example additionally shows
   `"position": "bottom"`, which I did **not** reproduce — reconciling "bottom" with "bounds height, not membership"
   would need a non-native wrapping legend (a custom HTML overlay, mirroring how the tooltip is a raw HTML string),
   which is layout scope beyond this task's Implementation/Acceptance text (neither mentions legend *position*).
   ⚠️ **Flagging, not resolving**: legend placement (`right`/`middle` here) is an interpretation, not a spec value,
   same status as `DIMMED_LINE_OPACITY` and `LEGEND_ROW_HEIGHT`.
   `legendMaxHeightRows` defaults to **4** — reusing §5's own example number rather than inventing a fresh one.

7. **`legend.data` is always every series' label — built directly from `model.series`, independent of
   `legendMaxHeightRows` or anything else.** This is the one number the acceptance box makes non-negotiable
   ("legend membership never reduced") — tested directly (5 series → 5 `legend.data` entries) and again under a
   deliberately small `legendMaxHeightRows={2}` to prove height and membership are actually independent knobs, not
   one masquerading as the other.

8. **The tooltip header shows only the formatted, zone-labelled time (`formatAxisTimestamp`), not `· served
   <tier>`.** A.10.6's mockup text includes a resolution-tier suffix in the header, but `ChartModel` (task 071)
   deliberately does not carry `resolution.served` — only the raw `QueryResponse` does, and `projectSeries`
   doesn't project it through. Extending `ChartModel` for this would be a `contracts/metrics/types.ts` change
   unmentioned by FE-074's own Implementation/Acceptance text (which describes the header only as "a shared-axis
   tooltip listing every series... sorted by value" — no header-content bullet at all). Chose not to expand
   `contracts/` scope on that basis; flagging rather than silently dropping the mockup detail, same posture
   `task-073-report` decision 8 took for the 1.5× gap-threshold conflict.

9. **Both the tooltip (HTML) and the legend (zrender rich text) reuse one `StateTag` table** (`token`/`label`/
   `tone`), rather than two independent vocabularies — the rich-text `token` field only matters to the legend
   (zrender's `{tokenName|text}` mini-template needs a bare identifier key), the tooltip ignores it and inlines
   colours directly into the HTML string it returns. Kept both renderers reading the same table so the tooltip and
   legend can never show different text or colour for the same state.

10. **Values escaped (`escapeHtml`) before being interpolated into the tooltip's HTML string.** `tooltip.formatter`
    returning HTML means whatever it returns is parsed as markup; `chartSeries.label` is server-sourced (an
    instance/catalog-derived string, not a compile-time constant), so it's escaped before interpolation as a
    defensive measure — no fixture currently exercises a label containing `<`/`&`, but the risk is structural
    (any future catalog entry with such a label), not hypothetical-only.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json --noEmit && npx tsc -p tsconfig.node.json --noEmit   # clean
npm run lint                                                                    # clean (project-wide, --max-warnings=0)
npm run lint:fixtures:assert                                                    # single-import-site guardrail still proven (10/10)
npm run check:forbidden-deps                                                    # clean
npx vitest run --project unit src/features/monitoring/chart                     # 2 files, 35/35 (was 27/27)
npm test                                                                         # full suite: 246 files, 1457/1457 green (was 246/1449)
npm run build                                                                    # production build + assert-echarts-chunk.mjs, both succeed
```

Task-specific acceptance items:

- **Tooltip carries state** — "is a shared-axis tooltip, sorted by value descending, with a per-series state tag"
  asserts descending value order and the `inactive replica` tag text; "appends a state tag for a non-ok series and
  leaves an ok series plain" (legend) covers the same vocabulary on the legend side.
- **Missing point reads `gap`** — "renders `gap`... for a series with no point at the cursor" asserts the literal
  word `gap` appears and the series' own declared state (`type-suspect`) does **not** — never blank, never zero,
  never the wrong tag.
- **Legend membership never reduced** — "never truncates membership" (5 series → 5 `legend.data` entries) and
  "bounds height... without reducing membership" (height shrinks under `legendMaxHeightRows={2}`, `data` stays
  full) prove height and membership are independent.

### Not browser-verified

Same position as tasks 071–073: `ChartRenderer` has no route/consumer yet (Explore/Dashboard wiring is later
Monitoring tasks). Verification here is the test suite plus the isolated build/chunk check above — including that
the tooltip/legend `formatter` functions were called directly in tests (not exercised via real mouse-hover
DOM events), consistent with the file's own stated testing philosophy ("asserted on the ECharts option object, not
a screenshot").

## Status

DONE — implemented, typecheck-clean, lint-clean (project-wide), single-import-site guardrail intact, contracts
fixture suite unchanged and green, chart test suite 35/35, full suite green (246/246 files, 1457/1457 tests),
production build + chunk-isolation check both succeed. Not yet marked done in `task-list.md`/`execution.md` or
committed — pending user confirmation. GitHub issue for `FE-074 · Tooltip and legend` — to be closed on commit.
