# Dynamic Metrics decisions — `DM-D*`

Decisions arising from [`design.md`](design.md). **All are `PROPOSED`.** None is ratified.

**ID discipline (`I3`):** IDs are immutable and never renumbered. `DM-D*` is a **sixth family** alongside `D*`,
`B/D*`, `SM-D*`, `UI-D*` and the per-component families (`BE-D*`/`FE-D*`/`AG-D*`/`RT-D*`). The prefix was checked
against every existing family for collision before use. To change a decision, **add one that supersedes it** —
never edit a meaning in place.

> **Amendment policy for this draft.** `I3`'s supersession rule protects decisions **something has been built or
> ratified against**. `DM-D1`…`DM-D20` were drafted and corrected within the same review cycle, before any
> ratification, with no code and no commit against them — the same situation `UI-D1`…`UI-D21` were in, and the
> same policy applies. R1 corrections are therefore applied **in place** and itemized in the changelog at the end
> of this file. **Post-ratification the supersession rule applies normally.**

⚠️ **Three items conflict with, or supersede, an existing decision.** Per `I4` they are flagged, not resolved:
**`DM-D5` supersedes `SM-D31`** · **`DM-D13` vs `D7`** · **`DM-D1`/§5.2's composition module vs `I7`**.

⚠️ **This family rests on an unratified base.** `SM-D8`, `SM-D26`, `SM-D30`, `SM-D31` and all of `UI-D*` are
`PROPOSED`. `DM-D5` is therefore a **proposal against a proposal** — the same ordering problem `UI-D12` vs
`SM-D14` already created, and it must ratify *after* `SM-D31`, not alongside it.

---

| ID | Decision | Status |
|---|---|---|
| `DM-D1` | The query API is the sole UI/data-plane boundary and the store-swap seam | PROPOSED |
| `DM-D2` | API versioning: major in path, `queryVersion` in body, additive changes never bump | PROPOSED |
| `DM-D3` | The metric contract binds the **data plane**, not the process | PROPOSED |
| `DM-D4` | One line per **logical instance**; replicas aggregated type-awarely | PROPOSED |
| `DM-D5` | Dashboards gain `entity` and fleet-wide scopes and a `personal` layer | PROPOSED ⚠️ · `tenant` spelling superseded by `DM-D24`/`D20` |
| `DM-D6` | Widget config is versioned, forward-migrated, and preserves unknown fields | PROPOSED |
| `DM-D7` | The cap counts **rendered series**, and is distinct from `SM-D30`'s cardinality cap | PROPOSED |
| `DM-D8` | Aggregation is type-aware and ordered; an incoherent aggregation is refused, not computed | PROPOSED |
| `DM-D9` | Both ad-hoc and saved widgets; explore first, save second | PROPOSED |
| `DM-D10` | Capabilities are advertised; the UI hardcodes no preset, cap or tier | PROPOSED |
| `DM-D11` | Resolution stays automatic with a forced-Raw override — upholds `SM-D31` | PROPOSED |
| `DM-D12` | `mark` is the sole visualization axis; `area` is a fill on `line`, not a member | PROPOSED |
| `DM-D13` | Apache ECharts is the charting library | PROPOSED ⚠️ |
| `DM-D14` | Streaming CSV export is v1; PNG is Phase 2 | PROPOSED |
| `DM-D15` | Alerting receives an immutable one-way seed, never a rule | PROPOSED |
| `DM-D16` | Gaps are **server-declared**; absence is absent, never `null` or zero | PROPOSED |
| `DM-D17` | Refresh is polling, floored at bucket size, paused when hidden | PROPOSED |
| `DM-D18` | Six new RBAC verbs, two of them high-consequence | PROPOSED |
| `DM-D19` | Widget config is URL-serializable by design; sharing is deferred | PROPOSED |
| `DM-D20` | The view model is a pure function guarded by a shared fixture suite | PROPOSED |
| `DM-D21` | The dashboard's range and refresh are authoritative; the widget's are defaults | PROPOSED |
| `DM-D22` | `single-stat` and `gauge` require aggregation to exactly one value | PROPOSED |
| `DM-D23` | **One metric per widget**; instances are the comparison axis | PROPOSED |
| `DM-D24` | The fleet-wide dashboard scope and all telemetry isolation keys are named `organization`; there is no `tenant` scope or key | PROPOSED · implements `D20` 🔒 |

---

## `DM-D1` — The query API is the sole UI/data-plane boundary and the store-swap seam

The UI reaches telemetry only through this API: no direct SQL, no store query language in the client, and **no
store-specific concept in any response** — no partition, no rollup table name. `resolution.served` names a
**tier**, which is a contract concept.

**Rationale.** This is the portability the "open store decision" was really asking for. **`D14` is `LOCKED`** —
PostgreSQL for everything, VictoriaMetrics and Timescale explicitly not adopted on operational grounds — and
`SM-D29` makes `sm_data` **PostgreSQL ≥ 14 only, permanently**, because declarative partitioning,
`DETACH … CONCURRENTLY`, `COPY … TO STDOUT` and JSONB indexing have no portable equivalent. A store *choice* is
therefore settled; a store *seam* is not, and `SM-D8` already requires ingest to sit behind an interface. This is
the read-side twin of that interface.

⚠️ **Flagged: where this API lives is undeclared.** `UI-D13` requires composing `sm_control` and `sm_data` reads
*"in the API layer above both data-access modules — two reads assembled, never one query."* But `I7` forbids CP
and DP code importing each other, so the composition layer is a **third module** — and
[`../architecture.md`](../architecture.md) §3.1 names only two. `design.md` §5.2 assumes a `console` module
depending on both **service interfaces** (not their data-access modules) and flags it for a decision in
`DECISIONS.md`.

## `DM-D2` — API versioning: major in path, `queryVersion` in body

Major version in the path (`/api/console/v1/*`). Additive changes **do not** bump it, and clients must ignore
unknown response fields. A separate `queryVersion` in request and response bodies tracks **semantics** —
aggregation rules, gap handling — independently of the route. A breaking change requires a compatibility window.

**Rationale.** [`../common/contracts/README.md`](../common/contracts/README.md) requires every contract carry a
version from the first commit, and warns that *"a break caught at PR time costs five minutes, while one caught in
the field means an agent in an environment we cannot reach has stopped reconciling."* The same asymmetry applies
to an on-prem console that upgrades on the customer's schedule. The body-level `queryVersion` exists because a
change to counter-rate semantics is **not** a route change but is exactly as breaking — a chart that silently
switches from delta to rate is the worst kind of regression, because it still renders.

## `DM-D3` — The metric contract binds the **data plane**, not the process

The conformance bar sits on the data plane's **normalized output**. A process — in any runtime — is obliged to
emit nothing. Its Solution Package declares a `source` and a mapping to canonical `name`, `metricType`, `unit`
and a stable replica identity; the data plane produces conformant samples with no per-solution code.

**Rationale.** ⚠️ **The natural phrasing — "the canonical shape every process must emit" — violates `HC1` on the
v1 target solution.** Cache emits `/api/cache/stats` in its own shape, has no Prometheus registry, and `I1` names
*new telemetry emission* as explicitly not fair game. A contract phrased as an obligation on the process is
unsatisfiable by the only MI v1 ships against, and would have been discovered at integration rather than at
design. Re-homing the obligation to the data plane is `SM-D4`'s metrics-agnostic ingest and `D5`'s
normalize-into-one-model restated as a conformance bar.

**Consequence:** "the UI must never branch on runtime" becomes structural rather than disciplinary — by
`design.md` §5's construction the UI never receives a field identifying one.

## `DM-D4` — One line per **logical instance**; replicas aggregated type-awarely

The default series-per-line unit is the **declared logical instance** (`SM-D13`). Replicas aggregate into it per
`DM-D8`. A `seriesBy: "replica"` override exists for incident work and is cap-checked identically.

**Rationale.** Matches the EC2 mental model the requirement cites, and matches SM's declared-only authority. But
it is a real decision, not a default: a declared instance is *logical*, and its replicas each carry their own
`source_key` and therefore their **own series** ([`../architecture.md`](../architecture.md) §8.2).

⚠️ **Two consequences must be rendered, not swallowed.** A Cache `active_standby` **standby reports `health: UP`
while processing nothing** (open item 15, `UI-D9`), so aggregating it **dilutes an `avg` and contributes zero to a
`sum`** — the line therefore carries a replica-composition annotation and an all-inactive series renders as
`inactive-replica`, never as a healthy zero. And **replica-count changes are annotated on the time axis**, because
a `sum` steps when a replica is added and that step is not a workload change.

## `DM-D5` — Dashboards gain `entity` and `tenant` scopes and a `personal` layer ⚠️

Three scopes — `instance` (unchanged from `SM-D31`), **`entity`** (cross-instance comparison), **`tenant`**
(fleet overview). Three layers — `product-default` (replaced wholesale on package upgrade), `shared`, and
**`personal`**. Rendered view is the union; an operator may **hide** a default but not delete it.

**Rationale.** The headline requirement is EC2-style multi-instance overlay, and **`SM-D31`'s per-instance scope
gives a cross-instance widget nowhere to live.** The scope axis is the minimum addition that fixes it;
`SM-D31`'s layering mechanism — the part that makes package upgrades safe — is preserved **unchanged**.

> ⚠️ **SUPERSESSION, FLAGGED (`I3`, `I4`).** `SM-D31` states dashboards are **per-instance and shared** and
> explicitly rejects per-user: *"per-user dashboards fragment an operations view and double the RBAC surface."*
> `DM-D5` reverses that second clause by adding a `personal` layer, and widens the first. **`SM-D31` is itself
> `PROPOSED`**, so this is a proposal against a proposal — the exact ordering problem `UI-D12` vs `SM-D14`
> created. It must ratify **after** `SM-D31`, never as part of the same set.
>
> **The counter-argument, stated fairly:** `SM-D31`'s reasoning is sound for an *operations console* — two people
> debugging one incident must see the same thing, and a `personal` layer weakens that. `DM-D5` accepts the cost
> because exploration is inherently personal and forcing every exploratory widget into a shared dashboard is worse.
> The architect group should decide, not this document.

## `DM-D6` — Widget config is versioned, forward-migrated, and preserves unknown fields

Monotonic integer `schemaVersion`; forward-only migrations in an ordered chain, so `v1 → v4` runs three functions.
**Unknown fields are preserved on round-trip.** A widget newer than the console renders **read-only with a stated
reason**, never partially.

**Rationale.** The round-trip rule is the load-bearing one: without it an older console **silently destroys** a
newer console's widget by saving it back — invisible at the time and irreversible afterwards. Mixed console
versions are the normal on-prem state, not an edge case. The read-only-with-a-reason rule mirrors `UI-D12`, where
every blocked state states why it is blocked rather than appearing broken.

## `DM-D7` — The cap counts **rendered series**, and is distinct from `SM-D30`'s cardinality cap

Default **10**, deployment-configurable **10–20**, advertised as `capabilities.maxSeriesPerWidget`. The instance
selector **projects the series count** (`Σ replicas` for the current `seriesBy`) as instances are chosen; at the
cap, unselected instances are **disabled** with an inline explanation and two routes forward — narrow by label,
or aggregate across instances. Breach at query time is `422 SERIES_CAP_EXCEEDED`.

**Rationale.** ⚠️ **Counting instances was a defect, corrected in R1.** A cap on *instances* does nothing in
`seriesBy: replica`: ten instances with five replicas each renders **50 lines** while the counter reads "10 / 10".
Since the decision's own stated purpose is *query cost and chart legibility*, an instance-denominated cap fails in
exactly the mode that most needs it. Counting rendered series makes the cap mean what it claims in both modes.

Projecting before commit rather than failing after follows `SM-D30`'s "reject and surface" posture — **never
silent degradation**, and never a selection that succeeds in the UI and fails at the API.

⚠️ **These two caps must never share a name, a config key or an error code.** `SM-D30`'s cap protects **ingest**
by rejecting new series at write time and surfacing it as an ingest metric; this one protects **query cost and
legibility** at read time. Conflating them makes one breach look like the other, and the operator's remedy differs
completely.

## `DM-D8` — Aggregation is type-aware and ordered; an incoherent aggregation is refused, not computed

Across replicas and across instances alike: `gauge` averages as `sum(sum)/sum(count)` — **never `avg(avg)`**;
`counter` sums as `sum(delta)`; `histogram` is unsupported in v1. `avg` over a histogram, or any aggregation over
a `needs-typing` metric, returns **`422`** with a stated reason.

**The order is fixed:** `replicas → acrossReplicas → acrossInstances → groupBy`. **`groupBy` composes only after
instance aggregation**, and `groupBy` with `acrossInstances: "none"` is **refused** (`422
GROUP_BY_REQUIRES_AGGREGATION`).

⚠️ **Added in R1 — the combination was ambiguous and shipped unresolved.** Ten instances with `groupBy: ["region"]`
and no instance aggregation admits three defensible readings — ten lines, one line per region, or ten lines per
region. Pass 1 requires *"every requirement has one unambiguous reading"*; refusing is better than choosing one
silently, because the two remaining paths (aggregate first, or drop the `groupBy`) are both one control away.

**Rationale.** `SM-D26` establishes both rules and the reason they are not stylistic: `avg(avg)` is wrong whenever
bucket sample counts differ, and `sum`/`avg` over a monotonic counter is meaningless — which is why `delta` is
**materialized at rollup time** rather than derived at query time. The failure mode is *"insidious — a graph is
correct at the raw tier and silently becomes nonsense the moment the range crosses into the hourly tier."*
Refusing rather than computing follows `SM-D30`: a plausible-looking wrong number is worse than an error.

## `DM-D9` — Both ad-hoc and saved widgets; explore first, save second

Exploration is transient and unsaved by default; saving is an explicit act that chooses scope and layer, gated by
`dashboard.edit`.

**Rationale.** Requested, and not recorded anywhere in the repo — `SM-D31` decides how dashboards *layer* and
says nothing about how a widget *arrives*. Making the save explicit is what keeps `DM-D5`'s `shared` layer from
filling with half-finished exploration, which is the practical failure of consoles that auto-persist.

## `DM-D10` — Capabilities are advertised; the UI hardcodes no preset, cap or tier

`GET /capabilities` returns tiers with retention and minimum bucket size, caps, marks, aggregations and feature
flags. Presets are **derived** from it.

**Rationale.** Your stated intent, and it has a second effect worth naming: the preset list must reach **six
months**, not thirty days, because `REQ-007` requires six months of raw and queryable metrics. A 30-day ceiling
would have under-served the requirement the entire storage design exists to satisfy.

⚠️ **`REQ-007` is `ACCEPTED` but ❌ unproven** — the sizing model has never been benchmarked
([`../../requirements/traceability.md`](../../requirements/traceability.md), open item 5). The UI renders
advertised retention **as advertised** and never presents six months as assured. On a capabilities fetch failure
the UI **fails closed** to conservative defaults with a visible banner.

## `DM-D11` — Resolution stays automatic with a forced-Raw override

`resolution` accepts `auto` or `raw` only. **Not a free bucket size.** Tier routing is server-side: ≤7d raw ·
7–90d hourly · >90d daily.

**Rationale.** ⚠️ **This declines a stated requirement.** "Rollup interval" as a user-facing filter contradicts
`SM-D31`: *"Resolution stays automatic… The user picks the metric and the mark, never the tier."* The forced-Raw
override already exists in architecture §8.4 for exactly the incident case a manual interval would serve, and a
free bucket size re-introduces the tier-crossing hazard `DM-D8` guards — an operator choosing a one-hour bucket
over a counter is choosing, unknowingly, whether the graph is meaningful. Upholding the locked-in behaviour is
cheaper than superseding it, and the operator loses nothing they can act on.

## `DM-D12` — `mark` is the sole visualization axis; `area` is a fill on `line`, not a member

**One axis: `mark`**, the closed set `line · gauge · single-stat` (`SM-D31`). Styling lives in a sibling
`options` object (`fill`, `stack`, `scale`), which is **open data, not a dispatch field**. `area` is
`options.fill: true` on `mark: line`.

**Rationale.** An area chart has **identical data semantics** to a line — same query, same aggregation, same gap
handling — and differs only in fill. Modelling it as styling delivers the requested visualization **without a
vocabulary change, a decision, or a release**, which is exactly the line `UI-D14` draws between closed dispatch
vocabularies and open data. Stacking *does* change semantics (it implies parts of a whole) and stays deferred by
`SM-D31`, but its field is present so admitting it later is not a schema migration.

⚠️ **Corrected in R1: the first draft carried two visualization axes.** It had both `viz.kind`
(`timeseries | single-stat`) and `viz.mark` (`line | gauge | single-stat`) — so `single-stat` was a member of
both, and `{kind: timeseries, mark: gauge}` was expressible and meaningless. `SM-D31` had **already** fixed the
mark set; a parallel axis was invented without checking whether one existed. This is `L11` — *"the answer is
often already here, verified"* — reproduced inside a document that cites `L11`, and it is recorded rather than
quietly fixed for that reason.

## `DM-D13` — Apache ECharts is the charting library ⚠️

ECharts for every visualization, `canvas` renderer, with a single adapter mapping the MUI palette and typography
to an ECharts theme so light/dark has **one** source.

**Rationale.** Requested, and **no charting library is recorded anywhere in the repo** — this decision exists so
the choice does not arrive as an implementation detail. `canvas` over SVG because 10 instances × ~1000 points is
where SVG DOM cost becomes visible.

> ⚠️ **FLAGGED (`I4`).** `D7` is **`LOCKED`** and anticipates adopting *"the File Mesh MUI component stack (~66
> wrappers, setup wizard, tables, **charts**)"*. `DM-D13` does not contradict `D7`'s locked *stack* — MUI remains
> the component library — but it **discards an assumed reuse path**, and the reference tree could not be inspected
> from this machine to judge what is being given up. Compounding it, `D7`'s own status is contested by **open item
> 19** (MUI vs the deleted Tailwind + Radix handoff), which is recorded as *blocking the frontend sub-spec*.

## `DM-D14` — Streaming CSV export is v1; PNG is Phase 2

CSV export is a **streaming server-side** response (`COPY … TO STDOUT`, chunked), RBAC-gated by `metrics.export`.
PNG is Phase 2.

**Rationale.** ⚠️ **This corrects the brief, which placed both in Phase 2.** [`../architecture.md`](../architecture.md)
§8.4 requires raw stay *"fully queryable and exportable for the entire window"* and that export be *"a streaming
server-side `COPY … TO STDOUT` piped to the client, **never an in-memory API response**"*; §16 already lists
streaming export among RBAC-gated high-consequence actions. Deferring it would drop a designed v1 capability, and
building it later as a naive in-memory endpoint is precisely the anti-pattern §8.5 records from File Mesh. PNG has
no such history and is genuinely new.

## `DM-D15` — Alerting receives an immutable one-way seed, never a rule

"Create alert from this widget" emits a versioned seed carrying metric, selector, aggregation, condition and
`{ unit, metricType, transform }`. The seed is **immutable and one-way**: editing the widget later does not mutate
a rule built from it.

**Rationale.** ⚠️ **The alerting service does not exist.** Architecture §16.1 states that until it does, *"drift
and version states are UI-state-only and **nobody is notified**"* — so the UI must not imply an alert will fire.
Carrying `metricType` and `transform` in the seed prevents the alerting system re-deriving them wrongly and
alerting on a raw monotonic counter. One-way rather than live-linked, because a threshold quietly changing under a
firing alert is an incident-during-an-incident.

## `DM-D16` — Gaps are **server-declared**; absence is absent, never `null` or zero

A bucket with no samples yields **no tuple**. The response carries an explicit `gaps[]` and the computed
`gapThresholdSeconds`. Points carry `sampleCount` and `expectedCount`, at the raw tier too.

**Rationale.** `UI-D8` requires the 2× multiplier live *"in the contract, not in either implementation"*, warning
that otherwise *"the Java rollup and the TypeScript chart will disagree about where a line breaks."* Making the
**server the single evaluator** is stronger than agreeing a multiplier in two places: they cannot disagree,
because only one of them decides. Omitting the tuple rather than sending `null` is deliberate — chart libraries
coerce `null` to `0`, and *"a flat zero is worse than an error"* because §8.3's gap semantics cannot distinguish
"0 entries" from "not reported". Coverage at the raw tier matters because otherwise gaps are invisible *"in
exactly the forced-Raw view operators use during incidents"*.

## `DM-D17` — Refresh is polling, floored at bucket size, paused when hidden

`off · 10s · 30s · 1m · 5m`, floored at the served `bucketSeconds`, paused on `document.hidden` and when
off-screen, disabled for absolute ranges. Streaming is deferred.

**Rationale.** The data plane has no push channel to the browser, and adding one is a new transport rather than a
UI feature. Flooring at bucket size prevents the common waste of polling an hourly rollup every ten seconds and
re-rendering the same bucket. Disabling refresh for absolute ranges removes a class of pointless load nobody
notices.

## `DM-D18` — Six new RBAC verbs, two of them high-consequence

`dashboard.view` · `dashboard.edit` · `dashboard.reset` · `metrics.query` · `metrics.export` ·
`metrics.forceRaw`.

**Rationale.** `SM-D31` already flagged that `D10` has **none** of the `dashboard.*` verbs. The two
`metrics.*` high-consequence verbs follow architecture §16's existing list, which names forced-raw query and
streaming export among actions needing their own verbs — both can strain a deployment, and `D10`'s model is
deny-by-default with a policy-as-code catalog, so a verb that does not exist cannot be granted. Adding them to
`D10` is a change to a **`LOCKED`** decision and must be raised there, not assumed here.

## `DM-D19` — Widget config is URL-serializable by design; sharing is deferred

The §7 config is complete, self-describing and JSON-serializable, so a share-by-URL feature needs no schema work
later. The feature itself is Phase 2.

**Rationale.** Requested, and it costs nothing today because the same completeness is already required for
dashboard persistence and the `DM-D15` alert seed — one artifact serving three consumers. It is also why range
queries are `POST`: URL length is a *query* problem, not a *config* problem, and conflating them would have forced
either truncated sharing or unwieldy URLs.

## `DM-D20` — The view model is a pure function guarded by a shared fixture suite

`projectSeries(response, widgetConfig, theme) → ChartModel` is data in, data out — no DOM, no component library,
no HTTP. A shared JSON fixture suite must pass in **both** the TypeScript and Java CIs, including **gap**,
**counter-rate**, **replica-aggregation** and **schema-migration** cases, with JSON number formatting and key
ordering pinned.

**Rationale.** Directly mirrors `UI-D15`, for the same two reasons. It makes the renderer **specifiable while
open item 19 is unresolved** — only the last mile is stack-dependent, so an unresolved MUI-vs-Tailwind question
does not block the whole design. And two implementations of one function is exactly how semantics drift: `UI-D15`
warns *"the suite's own design must pin JSON number formatting and key ordering, or the comparison itself becomes
the divergence."*

## `DM-D21` — The dashboard's range and refresh are authoritative; the widget's are defaults

A widget's `range` and `refresh` apply when it is viewed **standalone**, when it is **first placed**, and when it
is exported as a `DM-D15` **alert seed**. On a dashboard, the **dashboard's** range and refresh govern every
widget. **There is no per-widget pin override.**

**Rationale.** ⚠️ **Added in R1 — the first draft carried both with no precedence rule.** §9.2 said the range was
dashboard-owned "one range for all widgets"; §7's config carried its own `range` and `refresh`. Both persisted,
neither won, and this is the classic dashboard ambiguity — shipped unresolved in a document whose own pass-1
checklist demands *"every requirement has one unambiguous reading."*

Dashboard-wins is chosen because two charts on one screen silently showing different windows is a documented way
to misread an incident, and `SM-D31`'s rationale — *"two people debugging the same incident must be looking at the
same thing"* — argues the same way for one operator reading two charts. The pin override was considered and
declined for that reason; the standalone case covers the legitimate "fixed baseline" need without putting two
windows side by side.

**The config must still carry both**, because `DM-D19`'s URL serialization and the `DM-D15` seed each require a
self-contained widget. Carrying them as *defaults* rather than *bindings* is what reconciles the two needs.

## `DM-D22` — `single-stat` and `gauge` require aggregation to exactly one value

With more than one instance selected, `acrossInstances: "none"` is **refused** (`422
STAT_REQUIRES_AGGREGATION`); the operator must choose `sum`/`avg`/`min`/`max`. The `AggregationControl` forces a
non-`none` value when either mark is selected.

**Rationale.** ⚠️ **Added in R1 — the combination was undefined.** The headline requirement is multi-instance
selection; these two marks render **one number**. The first draft specified both and never said what ten instances
produce.

Refusing follows `DM-D8`'s posture. The alternative considered — rendering a **small multiple**, one tile per
instance — is a legitimate design, but it makes a widget's footprint depend on selection size, which fights the
fixed `{x, y, w, h}` grid in `DM-D5`, and it is available later as a `mark` addition without invalidating this
decision. Showing the *first* instance's number under a title naming ten was never a candidate: a plausible wrong
number is the failure mode `SM-D30` and `DM-D8` both exist to prevent.

## `DM-D23` — One metric per widget; instances are the comparison axis

A widget queries **exactly one metric**. `query.metric` is a single metric name, not a list. The comparison axis
is **instances**: multi-select to the `DM-D7` series cap, with label filters and `groupBy` composing on top.
Comparing two *metrics* is **two widgets**, not two series groups on one chart.

`MetricSelector` is therefore **single-select** — changing the metric **replaces** it and re-queries; it never
adds a second series group. A request carrying a list of metric names is a **malformed request** (`400`), not a
business refusal: the contract's shape, not a runtime rule, is what forbids it. **No new error code is
introduced.**

**Rationale.** This records a constraint the contract already had rather than introducing one — §5.6's query API
and §7's widget config both carried `metric` as a singular string from the first draft. What was missing was the
*statement*: §9.1 marked `InstanceSelector` "multi-select" and left `MetricSelector`'s cardinality unsaid, and
§20 listed neither. Per `spec-review.md`, a limit that is implied by a JSON shape is not stated.

Three mechanisms in this document assume one metric, and each would need real design work to relax:

1. **`render.transform` is server-decided from `metricType`** (§5.6 rule 3) — a counter ships as a rate, a gauge
   as an absolute value. Two metrics of different types on one y-axis means one axis carrying a rate **and** an
   absolute, with `unitFormat`s that do not reconcile. That is a chart which is **wrong**, not merely crowded —
   the failure `DM-D8` and `SM-D30` both exist to refuse.
2. **`DM-D8`'s aggregation table is per metric type.** A second metric needs its own aggregation function, its
   own coherence check, and its own axis.
3. **`DM-D7`'s cap projection is `Σ replicas`** (§11.4). Multi-metric multiplies rendered series by metric count,
   so the projected-count the selector shows an operator *before* they commit would silently understate the real
   series count — defeating the one control that makes the cap non-surprising.

**Rejected: `metrics: [...]` capped at length 1.** It future-proofs the schema at the cost of advertising a
capability that does not exist, and a cap of one is the kind of constraint that gets relaxed by a one-line change
without the axis and unit-compatibility work above ever being done. `DM-D6`'s forward-only migration chain exists
precisely so a singular field can become plural later with a `schemaVersion` bump — that is the cheaper direction,
because it forces the design work to happen at the moment the capability lands.

**Limit, accepted:** no single-chart correlation of two metrics in v1 — an operator asking *"did evictions spike
when heap climbed?"* reads two widgets, not one chart. This is bounded by `DM-D21`, which already forces every
widget on a dashboard to share one range and refresh: side-by-side widgets are guaranteed to show the **same
window**, which is what makes visual correlation across them honest rather than a trap. **Deferred, not
scheduled** — no `REQ` requests multi-metric overlay (design §21 items 2 and 3 record that no metrics-UI
requirement exists at all), so attaching it to a phase would invent a commitment nothing backs.

## `DM-D24` — Organization is the fleet-wide dashboard and telemetry boundary

`D20` 🔒 removes the separate Tenant concept. Dynamic Metrics therefore uses `organization` for its fleet-wide
dashboard scope and `organizationId` / `organization_id` for its server-derived API and persistence keys. The
three dashboard scopes are `instance`, `entity`, and `organization`; none is named `tenant`.

This changes only `DM-D5`'s old spelling and the matching wire/storage vocabulary. Its proposed cross-instance
scope, personal layer, and flagged conflict with `SM-D31` are unchanged.

**Rationale.** A second name for the same isolation boundary would make frontend, event, and telemetry contracts
appear to describe different entities. One Organization term keeps the hierarchy, authorization, API, topics,
and database keys aligned.

---

## Changelog — R1 self-review corrections

A UX-architecture self-review of the first draft returned **14 findings: 3 CRITICAL, 9 MAJOR, 2 MINOR.** All
accepted and applied in place per the amendment policy above. Three were **internal contradictions that pass 1
should have caught and did not**.

⚠️ **This was self-review, not the independent adversarial pass.** Per `L2` and
[`../../checklists/spec-review.md`](../../checklists/spec-review.md), *"self-review catches contradictions; it does
not catch wrong facts"* — pass 2 is still outstanding (design §21 item 11), and the ECharts and WCAG claims in
§12/§18 are precisely the class it exists to catch (§21 item 10).

| # | Finding | Severity | Where fixed |
|---|---|---|---|
| 1 | **Two visualization axes** — `viz.kind` and `mark` both carried `single-stat`; meaningless pairs expressible | CRITICAL | `DM-D12`, design §7, §15 |
| 2 | **Dashboard vs widget time range had no precedence rule** — both persisted, neither won | CRITICAL | **`DM-D21` (new)**, design §7, §9.2 |
| 3 | **`single-stat`/`gauge` over multiple instances undefined** | CRITICAL | **`DM-D22` (new)**, design §5.7, §5.8 |
| 4 | **The cap counted instances**, so `seriesBy: replica` rendered 50 lines through a cap reading 10 | MAJOR | `DM-D7`, design §5.4, §5.8, §11.4, §13 |
| 5 | **Hash-assigned colors collide sooner than sequential**, putting two identical lines on one chart | MAJOR | design §12 |
| 6 | **`groupBy` × multi-instance × aggregation was three-way ambiguous** | MAJOR | `DM-D8`, design §5.7, §5.8, §9.1 |
| 7 | **Legend truncated past `maxItems`** while the chart plotted every series — unidentifiable lines | MAJOR | design §7, §12, §13 |
| 8 | **No tooltip/hover identification spec** — the primary identification mechanism for overlaid series | MAJOR | design §12 |
| 9 | **Log scale offered for counter rates**, whose legitimate zeros a log axis silently drops | MAJOR | design §12, §20 |
| 10 | **No edit path for a saved widget** — explore→save was write-once | MAJOR | design §11.1, §19 |
| 11 | **`ResolutionControl` missing from the dashboard toolbar** — forced-Raw is an incident need, and incidents are read on dashboards | MAJOR | design §9.1 |
| 12 | **Layer provenance invisible** — an operator could not tell which widgets a package upgrade replaces | MAJOR | design §9.1 |
| 13 | **WCAG AA claimed with no contrast constraint** — a color-blind-safe palette is not automatically AA | MINOR | design §18, §20, §21 item 9 |
| 14 | **Responsive/mobile entirely absent** — silence rather than a stated non-goal | MINOR | design §18, §20 |

**Not accepted, with reason:** none. Finding 1 is recorded rather than quietly fixed because it is `L11`
reproduced inside a document that cites `L11` — `SM-D31` had already fixed the mark set, and a parallel axis was
added without checking.

---

## Changelog — later changes

| Date | Change |
|---|---|
| 2026-09-07 | **`DM-D24` added after `D20` ratification.** The fleet-wide dashboard scope is `organization`, and telemetry APIs/storage use `organizationId` / `organization_id`; the former `tenant` vocabulary is superseded without changing `DM-D5`'s unresolved functional proposal. |
| 2026-08-17 | **`DM-D23` added — one metric per widget, instances as the comparison axis.** Not a scope reduction: §5.6's query API and §7's widget config already carried `metric` as a singular string. What changed is that the constraint is now **stated** — §9.1's `MetricSelector` is explicitly single-select (previously unsaid, while `InstanceSelector` said "multi-select"), §11.1 says a metric change replaces rather than adds, §11.4 notes the cap projection assumes one metric, and §19/§20 record the boundary. Schema shape, cap arithmetic, aggregation rules and `DM-D22` are **unchanged**; no `schemaVersion` bump and no migration. Multi-metric overlay is **deferred with no phase attached** — no `REQ` requests it. |
