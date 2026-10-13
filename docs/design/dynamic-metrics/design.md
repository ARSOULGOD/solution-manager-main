# Dynamic Metrics — metric contract, query API, widgets and dashboards

**Tracking:** unassigned — open a GitHub Project issue before implementation · **Status:** DRAFT, self-review
(pass 1) applied; **independent adversarial review not yet run** · **Date:** 2026-08-14

> ⚠️ **2026-10-05 (plan 011 P11-0.2, a DRAFT plan, not approved):** the independent adversarial review that M-Q1 requires
> (`OR-69`) has now run — findings and dispositions in [`docs/tasks/dm-design-review-2026-10.md`](../../tasks/dm-design-review-2026-10.md). The owner's
> monitoring rulings (`OR-71`…`OR-82`, [`DECISIONS.md`](../DECISIONS.md)) **supersede parts of this design**; the map below lists each passage.
> The original text stays as written (`I3`); every correction is a dated pointer. Nothing here is ratified by the review.
>
> ⚠️ *2026-10-05, later (wave review of plan 011 P11-2.1; the note above is unedited, `I3`):* plan 011 is **approved** (`OR-97`), and the
> archive is **per tenant, by a bound `SELECT` cursor, with no `COPY`** (`OR-92` OQ-27 (b), restated in `OR-99`): where the map below says the
> archive `COPY` stays, read this.

**Relationship to the rest of the design:**

| Document | Relationship |
|---|---|
| [`../dynamic-ui/design.md`](../dynamic-ui/design.md) | **Subsystem A is the declaration layer** — how a Solution Package *declares* a metric. This document is the **consumption layer** — how a declared or discovered metric becomes a query, a chart and a saved dashboard. Where they overlap, Subsystem A wins on declaration and this document wins on consumption. |
| [`../architecture.md`](../architecture.md) | §8 (storage, tiers, rollups, query routing), §10 (acquisition) and §13 (capability limits) are **binding inputs**. This document does not restate them; it cites them. |
| [`../DECISIONS.md`](../DECISIONS.md) | Cross-cutting decisions. This document may refine them but never contradicts one silently — see §21. |

Decisions live in [`decisions.md`](decisions.md) as the `DM-D*` family. **All are `PROPOSED`.**

> ⚠️ **This design rests on unratified decisions.** `SM-D8`, `SM-D26`, `SM-D30`, `SM-D31` and the whole `UI-D*`
> family are **`PROPOSED`** ([`DECISIONS.md` ratification matrix](../DECISIONS.md)). Per `I4`, nothing here may be
> implemented as though settled, and `DM-D5` is a **proposal against a proposal** — see §21.

### Superseded by the monitoring rulings (2026-10-05)

⚠️ *Added 2026-10-05 by plan 011 P11-0.2 (`I3`: pointers only; the passages below are unedited).* The owner rules on
behalf of the architect group (`OR-69`); where a row below and the passage it names disagree, **the ruling wins**. The
rulings do **not** ratify `DM-D*`: every `DM-D*` stays PROPOSED. Finding ids (DMR-n) are the [review](../../tasks/dm-design-review-2026-10.md)'s.

| Passage | Superseded by | What holds now |
|---|---|---|
| §2.2 `V001` row; §4.2 identity; §4.3; §21 item 1 | M-Q7, `OR-75` (DMR-1) | Identity is `(tenant_id, instance_id, placement_id, metric_name, metric_type, unit, source_discriminator, labels_fingerprint)`; `package_version` is an **attribute**. The discriminator is in the identity. Deleting the orphan `V001` is discovery default D-8, **not** a ruling (plan 011 OQ-22) |
| §4.4; §5.7's `acrossReplicas`; `DM-D4` | M-Q20, `OR-75` (DMR-2) | No cross-placement gauge aggregation in v1: counters sum; a gauge reported by more than one placement shows **one line per placement**; non-overlapping placements after a re-target form one line |
| §3.2 rule 3 (histogram `_count`/`_sum`) | M-Q6, `OR-75` (DMR-7) | A histogram is **skipped** by the collector; the catalog reports `unsupported` |
| §2.2 `UI-D5`/`UI-D6` row; §3.3 `needs-typing`; §5.5 states; §5.8 `METRIC_UNTYPED`; §6 rows 2–3; §11.2; §17 | M-Q6, `OR-75`; M-Q5, `OR-74` (DMR-8, DMR-9) | Only **declared** metrics are stored; undeclared arrivals are dropped and counted (`undeclared_dropped`); quarantine is deferred (the ingest port reserves `pending`); the typing route (`metric.declare`) is a **contracted refusal**; per-instance overrides are out of v1. `needs-typing` and `user-extension` have no v1 producer |
| §5.2 and §21's composition-module row; `DM-D1`'s flag | `OR-10`; M-Q11, `OR-78` (DMR-10) | `console-gateway` (`OR-10`'s composition module) hosts the `/api/console/v1` metrics routes; the contract has no `/api/dp/v1` metrics route |
| §5.8 row "unreachable/unauthorized" | M-Q17, `OR-79` (DMR-3) | An instance the viewer cannot see is **omitted silently**, as if it did not exist; `partial.unavailable[]` names only authorized-but-unreachable instances |
| §2.2 §8.4 row; §5.8 export (`COPY … TO STDOUT`); `DM-D14` | M-Q18, `OR-79` (DMR-4) | A **bound, streaming `SELECT`** with CSV encoded in the application, measured before export ships. The archive `COPY` of a detached partition (architecture §8.5) stays |
| §5.8 export verb `POST` | contract row 60 (DMR-6) | `GET /export/range` ([`console-integration.md`](../common/contracts/console-integration.md) §5.4) |
| §7 `instanceIds` on a saved widget; §8 scopes and layers; §11.1 step 4; §19 "three scopes, three layers"; `DM-D5` | M-Q10 and M-Q23, `OR-78` (DMR-11, DMR-12) | Per-instance **shared** dashboards only (a scope column with only `instance` legal in v1); **one instance per saved widget**; multi-instance compare is an **ad-hoc query, not saved**; out of v1: Explore drafts on the server, saved comparison widgets, templating. `DM-D5` is not adopted for v1 |
| §8 RBAC ("`D10` has none of these"); §21 item 4; §22 `REQ-006` | `D10` amendment (ratified) and M-Q16, `OR-80` (DMR-13) | All six `DM-D18` verbs are in the ratified catalog; the Operator also holds `metrics.export` and `metrics.forceRaw` |
| §14 "no push channel"; §19 refresh row; `DM-D17` | M-Q12, `OR-73`; `BE-D6` (PROPOSED) (DMR-14) | Path 2's live delivery to the browser via a broker topic is ruled; which plan builds its backend half is plan 011 OQ-14. Polling remains the fallback |
| §21 item 2; §22 last row | M-Q23, `OR-78` (DMR-15) | [`REQ-010`](../../requirements/REQ-010-per-instance-dashboards-and-comparison.md) exists. `REQ-011` stays reserved |
| §2.1 (reference codebases absent) | measured 2026-10-05 (DMR-16) | All three sibling trees are present; this design's Cache/File Mesh claims were **not** re-derived by the review |
| §19 and §21 item 8 ("`docs/plans/` is empty") | measured 2026-10-05 (DMR-17) | Plans 001–011 exist; [plan 011](../../plans/backend/011-monitoring-backend.md) is the metrics backend (a DRAFT, not approved) |

---

## 1. Purpose and scope

An operator must be able to select several instances of an entity, plot their metrics together, customize the
chart, and save it to a dashboard — and a **new metric, instance, entity type or process runtime must appear with
zero console releases**. That last clause is `REQ-001` applied to telemetry, and it is what makes this a contract
problem rather than a charting problem.

**In scope:** the metric contract · the UI-facing query API · the data model and series identity · metric
discovery · widget configuration and its migration · the dashboard model · component architecture · query
lifecycle · chart rendering and customization · performance · refresh · degradation · the alerting hand-off.

**Out of scope, deliberately:**

| Excluded | Why |
|---|---|
| **Ingestion mechanism** | Agent, SDK, push, scrape, sidecar — a per-binding policy (`SM-D23`), invisible to the UI. §3.3 states the four places its *consequences* are nonetheless visible. |
| **The time-series store** | **`D14` is `LOCKED`**: PostgreSQL for everything. `SM-D29` makes `sm_data` PostgreSQL ≥ 14 only, permanently. This document designs the **API seam** that makes the store swappable, not a store choice. |
| **Alert lifecycle** | A separate system that **does not exist yet** (§16). Only the seed contract is defined here. |
| **Graph-builder UX beyond explore-and-save** | Deferred by `UI-D*` open item 1; unchanged here. |
| **Renderer component library beyond the ECharts boundary** | Open item 19 formally **blocks the frontend sub-spec**. §9 keeps the view model stack-neutral so only the last mile is blocked. |

## 2. What constrains this design

### 2.1 Verification caveat

⚠️ **The three reference codebases named in `CLAUDE.md` are not present on this machine**
(`/Users/parikshit/Work/Development/{solace-cache-engine, pubsubplus-connector-file-common, file-mesh-ui}` — all
absent). Every claim below about Cache or File Mesh is **taken from this repo's own checked-in verifications with
line references**, not independently re-derived. Per `I2` and `L1` that is stated rather than implied. Any
reviewer with access to those trees should treat re-verification as in scope.

### 2.2 The binding inputs

| Source | Constraint this design must satisfy |
|---|---|
| `D14` 🔒 | PostgreSQL only; VictoriaMetrics and Timescale explicitly not adopted |
| `D7` 🔒 | React + Vite + **Material-UI** |
| `D10` 🔒 | Deny-by-default RBAC, `resource.action`, policy-as-code |
| `B/D15` 🔒 | **UTC is canonical**; local time is display-only |
| `I5` / `D20` 🔒 | Organization scope is **never** derived from client input |
| `I6` | Secrets never reach exports, history or logs |
| `I7` | No cross-schema joins or FKs between `sm_control` and `sm_data` |
| `HC1`/`I1` | **No MI development** — see §3.1, which is where this bites hardest |
| `SM-D8`/`SM-D26` | Tiers raw 6mo · hourly 2y · daily indefinite; **type-aware rollups**; `avg(avg)` is wrong; counters carry a materialized `delta` |
| `SM-D30` | `metric_type` and `unit` **mandatory**; `TYPE_SUSPECT`; hard per-instance cardinality cap |
| `SM-D31` | v1 marks **line · gauge · single-stat**; **resolution automatic**, forced-Raw override only; defaults layered beneath user graphs; hide-not-delete |
| `SM-D27` | Durability is a **declared, verified per-signal property**; unverified signals render with a caveat badge |
| `UI-D5`/`UI-D6` | Undeclared metrics are **quarantined, not rejected**; `series_pending` + `metrics_pending` |
| `UI-D8` | **Absence is not zero**; a gap is **> 2× the declared scrape interval**; no zero-fill, no connect-across |
| `UI-D13` | Registry in `sm_control`, values in `sm_data`, **composed in the API layer — two reads, never one query** |
| `UI-D14` | Closed vocabularies, open data — `mark` is a **closed set** |
| `UI-D15` | The view model is a **pure function** guarded by a shared Java+TypeScript fixture suite |
| architecture §8.4 | ≤7d raw · 7–90d hourly · >90d daily · forced-Raw override; **raw stays exportable for the whole window** via streaming `COPY` — ⚠️ *2026-10-05: export is a streaming `SELECT` (M-Q18, `OR-79`); see the supersession map* |
| `V001__telemetry_schema.sql` | **The source of truth for series identity.** Prose loses to the migration — ⚠️ *2026-10-05: superseded by M-Q7 (`OR-75`); see §4.2's pointer* |

## 3. The metric contract

### 3.1 ⚠️ The contract binds the data plane, not the process (`DM-D3`)

The obvious framing — *"the canonical shape every process must emit"* — **violates `HC1`** on the v1 target
solution. Cache emits `/api/cache/stats` in its own shape, has no Prometheus registry, and **SM may not require it
to change** (`I1`: new telemetry emission is explicitly not fair game). A contract phrased as an obligation on the
process is unsatisfiable by the only MI v1 ships against.

**Therefore the conformance bar sits on the data plane's normalized output**, which SM does control:

```
process (unmodified)  ──► acquisition (per-binding policy, SM-D23)  ──► NORMALIZATION ──► contract-conformant
   any runtime              scrape · push · broker · federated          SM owns this      canonical sample
```

This is exactly `SM-D4`'s metrics-agnostic ingest and `D5`'s "normalize into one model", restated as an
obligation. The consequence for your question *"what must a new runtime satisfy"* is:

| Party | Obligation |
|---|---|
| **A new runtime (Node, Python, Go, …)** | **Nothing.** It emits whatever it emits. |
| **Its Solution Package** | Declares a `source` (closed vocabulary, `UI-D14`) and a mapping to canonical `name`, `metricType`, `unit`, and a **stable replica identity**. |
| **The data plane** | Produces contract-conformant samples from that mapping, with **no per-solution code** (`REQ-001`). |

**Nothing in the UI branches on runtime, and nothing can** — by §5's construction the UI never receives a field
that identifies one.

### 3.2 The canonical sample

```jsonc
{
  "organizationId": "…",         // server-derived, NEVER client-supplied (I5, D20)
  "instanceId":  "…",            // the DECLARED logical instance (SM-D13)
  "placementId": "…",            // immutable (instance, target) pairing (SM-D26)
  "sourceKey":   "cache-0",      // STABLE replica identity — ordinal/hostname, never a pod UID
  "sourceDiscriminator": "…",    // stable hash of the declared `source` (UI-D5) — see §4.3
  "metricName":  "cache.hit_rate",
  "metricType":  "counter | gauge | histogram",   // REQUIRED, never inferred (SM-D30)
  "unit":        "ratio",                          // REQUIRED (SM-D30)
  "labels":      { "region": "eu-west-1" },        // dimensional; capped (SM-D30)
  "ts":          "2026-08-14T09:15:00Z",           // UTC, always (B/D15)
  "value":       0.94
}
```

Three rules that are not negotiable and each of which has a recorded failure behind it:

1. **`metricType` and `unit` are required at declaration** (`SM-D30`). A counter mislabelled as a gauge produces a
   *plausible-looking wrong graph* and nothing errors.
2. **`sourceKey` is stable identity, not the ephemeral incarnation.** A pod UID creates a new series per restart
   and fragments history ([architecture.md:573](../architecture.md#L573)).
3. **`histogram` is accepted but not plottable in v1** — quantiles cannot be averaged into rollups
   ([architecture.md:649](../architecture.md#L649)). Only `_count` and `_sum` are ingested, as counters.

### 3.3 What the UI *does* see of the acquisition path

The mechanism is invisible; four **consequences** are not, and a UI that hides them lies to the operator:

| Surfaced | Source | Rendering |
|---|---|---|
| **Gap threshold** | the declared scrape interval | a gap is `> 2 × interval` (`UI-D8`); the server computes it (§5.6) |
| **`pending-config-change`** | between apply and `restart-observed` | metric marked pending, **not** retired (`UI-D7`) |
| **Durability badge** | `SM-D27` per-signal verification state | `guaranteed-unverified` and `best-effort` render a caveat badge |
| **`needs-typing`** | `series_pending` (`UI-D5`) | metric is listed and discoverable but **not plottable** |

## 4. Data model

### 4.1 The hierarchy

```
entity type            e.g. "cache-engine", "file-mesh"          (a Solution)
  └─ instance          a DECLARED logical Solution Instance      (SM-D13)
       └─ replica      a process carrying that instance identity, keyed by sourceKey
            └─ series  one metric + label-set on one replica     (V001)
                 └─ point   (ts, value)
```

⚠️ **A declared instance is logical; its replicas each own a separate series.** This is why "one line per
instance" needed deciding rather than assuming — see §4.4.

### 4.2 Series identity is the migration's, not this document's

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* **superseded by M-Q7 (`OR-75`).** Series identity is
> `(tenant_id, instance_id, placement_id, metric_name, metric_type, unit, source_discriminator, labels_fingerprint)`,
> with `package_version` an attribute, and it is fixed by the ruling, not by `V001`. Retyping still mints a new series,
> because `metric_type` and `unit` are in the key. §4.3's missing discriminator is in it. Whether the orphan `V001` is
> deleted is discovery default D-8, **not** a ruling (plan 011 OQ-22). Condition (`I1`): every part is SM-held or read
> from today's payload by a package-declared extraction.

`V001__telemetry_schema.sql` is declared the source of truth and prose loses to it. Identity is:

```
(organization_id, instance_id, placement_id, metric_name, package_version, source_key, labels_fingerprint)
```

`placement_id` — not a mutable binding — is what prevents multi-site merge: `cache-0` exists in every cluster
(`L9`). `package_version` is in the key so retyping a metric across package versions **mints a new series** rather
than corrupting existing rollups.

### 4.3 ⚠️ The source discriminator is required and is not in `V001` yet

`UI-D5` requires a **source discriminator** in both the pending key and `series_identity_unique`, because
`source_key` is *replica* identity and not *scrape* identity: without it, `cache_size` arriving from
`/actuator/prometheus` and from `/api/cache/stats` on the same replica **merge into one series**, and
`TYPE_SUSPECT` then observes a blended stream. `V001` does not carry it, and the `V002` that would add it does not
exist ([`../dynamic-ui/design.md` §8.1](../dynamic-ui/design.md)).

**This design depends on it.** Cross-instance overlay makes the defect worse, not better — a merged series
produces a plausible line that no constraint test can catch, because the insert *succeeds* (`UI-D18`). Routed as
open item 1 in §21; it blocks implementation, not this spec.

### 4.4 One line per **logical instance**; replicas aggregated (`DM-D4`)

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* **superseded in part by M-Q20 (`OR-75`).** No
> cross-placement **gauge** aggregation in v1: a gauge reported by more than one placement shows one line per placement;
> counters sum; non-overlapping placements after a re-target form one continuous line. On dp-remote a replica is a
> placement (`source_key := placement_id`, M-Q7), so §11.4's projection counts live placements for a gauge.

The default series-per-line unit is the **declared logical instance**, matching the EC2 mental model and SM's
declared-only authority (`SM-D13`). Replicas are aggregated into it, **type-awarely** (§5.7).

Two consequences that must be rendered, not swallowed:

- **`INACTIVE_REPLICA`** — a Cache `active_standby` standby reports `health: UP` while processing nothing
  ([`DECISIONS.md` open item 15](../DECISIONS.md), `UI-D9`). Aggregating it into the instance line **dilutes an
  average and contributes zero to a sum**. The line therefore carries a replica-composition annotation, and a
  series whose replicas are wholly inactive renders as `inactive-replica`, never as a healthy zero.
- **Replica count changes** are annotated on the time axis, because a `sum` across replicas steps when a replica
  is added and that step is not a workload change.

A `seriesBy: "replica"` override is available per widget for incident work.

## 5. The query API contract

### 5.1 It is the only boundary (`DM-D1`)

The UI reaches the data plane through this API and through nothing else — no direct SQL, no store-specific query
language in the client, no store identifier in any response. That is what allows `D14`'s PostgreSQL to be
swapped, or to differ per deployment, without touching UI code. `SM-D8` already requires ingest to sit behind an
interface; this is the read-side twin.

**A store-specific concept must never appear in a response.** No `partition`, no `rollup table name`, no SQL. The
`resolution.served` field names a **tier**, which is a contract concept (§5.6), not a table.

### 5.2 ⚠️ Where this API lives is an unresolved architectural question

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* **resolved.** `OR-10` (open item 21, closed 2026-09-16)
> approved the composition module, and M-Q11 (`OR-78`) places the `/api/console/v1` metrics routes in
> **`console-gateway`**, `OR-10`'s composition module. The contract
> ([`console-integration.md`](../common/contracts/console-integration.md) §5.4, rows 53–66) has no `/api/dp/v1` metrics route.

`UI-D13` states composition happens *"in the API layer above both data-access modules — two reads assembled,
never one query."* A metric catalog needs **declared** metadata from `sm_control` and **observed** series from
`sm_data`, so it is exactly that case.

But `I7` says *"control-plane code must not import data-plane code or its data-access module, and vice versa"* —
which means the composition layer is a **third module, neither CP nor DP**, and
[architecture.md §3.1](../architecture.md#L98)'s model names only two. The seam is implied by `UI-D13` and never
declared.

**This spec assumes a third `console` module** that depends on both CP and DP service interfaces (not their
data-access modules), and **flags it** as needing a decision in `DECISIONS.md` rather than resolving it here
(`I4`). Routes below use `/api/console/v1/*` for composed reads, `/api/dp/v1/*` for pure data-plane reads.

### 5.3 Versioning (`DM-D2`)

- **Major version in the path** (`/v1/`), per the contracts rule that every contract carries a version from the
  first commit ([`../common/contracts/README.md`](../common/contracts/README.md)).
- **Additive changes do not bump it.** Clients must ignore unknown response fields; the fixture suite (§9.4)
  carries a forward-compatibility case.
- **`queryVersion` inside request and response bodies** tracks the *semantics* of aggregation and gap handling
  independently of the route, so a change to counter-rate semantics is detectable without a route change.
- **A breaking change requires a compatibility window**, because on-prem consoles upgrade on the customer's
  schedule.

### 5.4 Capabilities — the UI hardcodes nothing (`DM-D10`)

```
GET /api/console/v1/capabilities
```

```jsonc
{
  "queryVersion": 1,
  "tiers": [
    { "name": "raw",    "retention": "P6M",  "minBucketSeconds": 15 },
    { "name": "hourly", "retention": "P2Y",  "minBucketSeconds": 3600 },
    { "name": "daily",  "retention": null,   "minBucketSeconds": 86400 }
  ],
  "maxRange": "P2Y",
  "maxSeriesPerWidget": 10,              // RENDERED series, not instances (DM-D7)
  "maxPointsPerSeries": 1000,
  "marks": ["line", "gauge", "single-stat"],
  "aggregations": ["sum", "avg", "min", "max"],
  "features": { "forceRaw": true, "streamingExport": true, "pngExport": false }
}
```

⚠️ **Time-range presets are derived from `tiers`, never hardcoded** — your stated intent, and the reason the
preset list must reach **six months**, not thirty days: `REQ-007` requires six months of raw, queryable. The UI
offers `15m · 1h · 6h · 24h · 7d · 30d · 90d · 6mo · custom`, filtered to what `tiers` actually advertises.

⚠️ **`REQ-007` is `ACCEPTED` but ❌ unproven** — the sizing model has never been benchmarked
([`traceability.md`](../../requirements/traceability.md), `DECISIONS.md` open item 5). The UI must render the
advertised retention as **advertised**, and never present six months as assured.

### 5.5 Catalogs — discovery, not configuration

```
GET /api/console/v1/entity-types
GET /api/console/v1/entity-types/{entityType}/instances?labels=…&page=…
GET /api/console/v1/metrics/catalog?entityType=…&instanceId=…
GET /api/console/v1/labels/{labelKey}/values?entityType=…&metric=…
```

`GET /metrics/catalog` is the composed read of §5.2 — declared metadata from `sm_control.solution_package`, observed
facts from `sm_data.series` and `sm_data.series_pending`:

> **2026-10-06 (W2-08, plan 011 P11-1.2, M-Q23).** The example below shows `lastSeen` as ISO-8601 text. **The wire
> carries epoch milliseconds** (`MetricCatalogEntry.lastSeen`, `contracts/openapi/console.yaml`), because the demo and
> the frontend already do and M-Q23 adopted their shape. The response is also a **bare array**, not `{ "metrics": [...] }`,
> and each entry carries a required `observedAvailability` (W2-06) so a failed data plane is never an empty-looking
> list. Where this page and the contract differ, the contract wins.

```jsonc
{
  "metrics": [
    { "name": "cache.hit_rate", "metricType": "gauge", "unit": "ratio",
      "layer": "package-baseline",              // vs "user-extension" (SM-D30)
      "state": "plottable",
      "lastSeen": "2026-08-14T09:15:00Z",
      "labelKeys": ["region"],
      "durability": "best-effort" },            // SM-D27
    { "name": "cache.custom_lag", "metricType": null, "unit": null,
      "layer": "user-extension",
      "state": "needs-typing",                  // UI-D5 quarantine — discoverable, NOT plottable
      "lastSeen": "2026-08-14T09:14:45Z" },
    { "name": "cache.evictions", "metricType": "counter", "unit": "count",
      "layer": "package-baseline",
      "state": "type-suspect",                  // SM-D30 empirical check disagrees with declaration
      "suspectReason": "counter decreased outside a detected reset" }
  ]
}
```

**Three states, three renderings, and none of them is silence:** `plottable` charts normally; `needs-typing`
appears in the picker **disabled**, with a "type this metric" affordance (`SM-D30` forbids inferring the type);
`type-suspect` charts with a persistent warning and never auto-corrects.

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* **`needs-typing` and `user-extension` have no v1 producer.**
> M-Q6 (`OR-75`): undeclared arrivals are dropped and counted, quarantine is deferred, and the typing route
> (`metric.declare`, contract row 61) is a contracted refusal; a histogram is `unsupported`. M-Q5 (`OR-74`): per-instance
> overrides are out of v1. The v1 catalog states and its `collector` block are the contract's (plan 011 P11-1.2).

### 5.6 Range query (`DM-D11`, `DM-D16`)

`POST` rather than `GET`: a ten-instance multi-select with label filters exceeds sane URL length. **URL sharing
is served by serializing the *widget config* (§7), not the query** — which is why Phase 2's share-by-URL costs
nothing to keep open.

**`metric` is exactly one metric name (`DM-D23`)** — instances, not metrics, are the comparison axis. A body
carrying a list of metric names is a **malformed request** (`400`), not a business refusal: the contract's shape
forbids it, so no error code in §5.8 covers it.

```jsonc
POST /api/console/v1/query/range
{
  "queryVersion": 1,
  "metric": "cache.evictions",                 // exactly one — DM-D23
  "selector": {
    "entityType": "cache-engine",
    "instanceIds": ["a1b2…", "c3d4…"],        // server re-scopes to the caller's organization (I5, D20)
    "labels": { "region": "eu-west-1" }
  },
  "range": { "from": "2026-08-07T00:00:00Z", "to": "2026-08-14T00:00:00Z" },
  "resolution": "auto",                        // auto | raw  — NOT a free bucket size (SM-D31)
  "seriesBy": "instance",                      // instance | replica
  "aggregation": { "acrossReplicas": "auto", "acrossInstances": "none" }
}
```

```jsonc
{
  "queryVersion": 1,
  "metric": { "name": "cache.evictions", "metricType": "counter", "unit": "count" },
  "render": { "transform": "rate", "rateSeconds": 60 },   // type-aware, server-decided (SM-D31)
  "resolution": { "requested": "auto", "served": "hourly", "bucketSeconds": 3600 },
  "coverage": {
    "requestedFrom": "2026-08-07T00:00:00Z",
    "servedFrom":    "2026-08-07T00:00:00Z",
    "truncatedByRetention": false
  },
  "gapThresholdSeconds": 7200,                            // 2 × declared interval (UI-D8)
  "series": [
    {
      "seriesRef": { "instanceId": "a1b2…", "placementId": "…", "sourceKey": null },
      "label": "cache-prod-01",
      "replicaComposition": { "total": 3, "inactive": 1 },   // §4.4
      "state": "ok",
      "points": [
        ["2026-08-07T00:00:00Z", 12.4, 60, 60],
        ["2026-08-07T02:00:00Z", 11.9, 41, 60]
      ],
      "gaps": [ { "from": "2026-08-07T01:00:00Z", "to": "2026-08-07T02:00:00Z", "reason": "no-samples" } ]
    }
  ],
  "partial": { "degraded": false, "unavailable": [] }
}
```

Point tuple is `[ts, value, sampleCount, expectedCount]`. The last two are what let the chart render **coverage**
rather than a confident line — required at the raw tier too, *"otherwise gaps are invisible in exactly the
forced-Raw view operators use during incidents"* ([architecture.md:655](../architecture.md#L655)).

**Four rules the response format enforces structurally, not by convention:**

1. **Absent is absent.** A bucket with no samples produces **no tuple** — never a `null`, which chart libraries
   coerce to `0` (`UI-D8`).
2. **Gaps are server-declared.** `UI-D8` requires the threshold live "in the contract, not in either
   implementation." Making the **server** the single evaluator and shipping `gaps[]` explicitly is stronger than
   agreeing a multiplier twice: the Java rollup and the TypeScript chart cannot disagree about where a line
   breaks, because only one of them decides.
3. **Counters never ship as monotonic values by default.** `render.transform` is server-decided from
   `metricType`; plotting a raw monotonic climb is *"a defect, not a user preference"* (`SM-D31`).
4. **No delta is computed across a gap** (`UI-D8` rule 4) — a restart produces a gap *and* resets the counter, so
   a delta across it yields a large false spike.

### 5.7 Aggregation is type-aware or it is wrong (`DM-D8`)

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* the **across-replicas** axis is **superseded for gauges by
> M-Q20 (`OR-75`)** — see §4.4's pointer. The **across-instances** axis stands: M-Q10 (`OR-78`) requires one metric over
> many `instanceIds` with `acrossInstances` none, sum, avg and so on.

Both aggregation axes — **across replicas** (into an instance line) and **across instances** (into one line) — obey
`SM-D26`'s rollup semantics. This is not a preference:

| `metricType` | `sum` | `avg` | `min`/`max` |
|---|---|---|---|
| **`gauge`** | `sum(sum)` | **`sum(sum) / sum(count)`** — never `avg(avg)`, which is wrong whenever bucket sample counts differ | `min(min)` / `max(max)` |
| **`counter`** | **`sum(delta)`** — the reason `delta` is materialized at rollup time rather than derived at query time | `sum(delta) / n` over contributing series | over `delta`, not over the raw counter |
| **`histogram`** | **unsupported in v1** | unsupported | unsupported |

**The API refuses an incoherent aggregation** rather than computing it: `avg` over a `histogram`, or any
aggregation over a `needs-typing` metric, returns `422` with a stated reason. Silently returning a number is the
failure mode `SM-D30` exists to prevent.

**`groupBy` composes *after* instance aggregation, and only after it.** The order is fixed:

```
replicas ──acrossReplicas──► instance series ──acrossInstances──► aggregate ──groupBy──► one line per group
```

⚠️ **`groupBy` with `acrossInstances: "none"` is refused** (`422`), not interpreted. With ten instances,
`groupBy: ["region"]` and no instance aggregation, "ten lines", "one line per region" and "ten lines per region"
are all defensible readings — so the request is ambiguous and the API declines it rather than picking one. To
group by a label, aggregate across instances first; to see per-instance lines, drop the `groupBy`.

**A `single-stat` or `gauge` mark requires aggregation to exactly one value (`DM-D22`).** These marks render one
number. With more than one instance selected, `acrossInstances: "none"` is refused (`422`) and the operator must
choose a real function. This is the same refuse-don't-compute posture as above: silently showing the *first*
instance's number under a title naming ten is the worst available outcome.

### 5.8 Instant query, export, and errors (`DM-D14`)

```
POST /api/console/v1/query/instant     → latest point per series, for single-stat and gauge marks
GET  /api/console/v1/export/range      → streaming CSV; RBAC-gated; see below
                                          (was POST; 2026-10-05: the contract, console-integration row 60, is GET)
```

⚠️ **Export is v1, not Phase 2.** [architecture.md §8.4](../architecture.md#L686) requires raw stay *"fully
queryable and exportable for the entire window"* and that export be a **streaming server-side
`COPY … TO STDOUT` piped to the client, never an in-memory API response**; §16's frontend sub-spec lists streaming
export among **RBAC-gated high-consequence actions**. The response is `text/csv` with
`Transfer-Encoding: chunked`. **PNG export is Phase 2** and is genuinely new.

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* **the `COPY … TO STDOUT` mechanism is superseded by M-Q18
> (`OR-79`).** Export is a **bound, streaming `SELECT`** with CSV encoded in the application (parameters sent apart from
> the SQL text, `I5`; cursor batches streamed), measured with a 105 M-row test before export ships. Still streaming, still
> never an in-memory response. The archive `COPY` of a detached partition (architecture §8.5) stays, identifier-only.
> `OR-79` also rules export limits (1 per organization, 2 global, 20 M rows, audited HIGH-consequence).

Errors are typed, and partial success is a first-class response rather than an exception:

| Condition | Response |
|---|---|
| Some instances unreachable/unauthorized ⚠️ *superseded in part — see the note below this table* | `200` with `partial.degraded: true` and `partial.unavailable[]` naming each with a reason |
| Range exceeds advertised retention | `200`, `coverage.truncatedByRetention: true`, served from the earliest retained point |
| Series cap exceeded | `422 SERIES_CAP_EXCEEDED` with `{ cap, projected, seriesBy }` — counts **rendered series**, not instances (`DM-D7`) |
| Incoherent aggregation for the metric type | `422 AGGREGATION_NOT_APPLICABLE` with the reason |
| `groupBy` without instance aggregation | `422 GROUP_BY_REQUIRES_AGGREGATION` (§5.7) |
| `single-stat`/`gauge` over >1 instance without aggregation | `422 STAT_REQUIRES_AGGREGATION` (`DM-D22`) |
| Metric is `needs-typing` | `422 METRIC_UNTYPED` |
| Query API unavailable | `503` — the UI degrades per §17, never blanks the dashboard |

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* **the "unauthorized" branch is superseded by M-Q17
> (`OR-79`).** An instance id the viewer cannot see is **omitted silently**, identical to a nonexistent id (`OR-51`'s
> reach filtering); `partial.unavailable[]` names **only authorized-but-unreachable** instances. This also replaces the
> "refuse the whole query with 404" default once stated for M-Q10. `METRIC_UNTYPED` has no v1 trigger (§5.5's pointer).

## 6. Metric discovery — why zero UI releases

A new metric, instance, entity type or runtime appears because **every one of them is data behind a catalog
endpoint**, and the UI's vocabularies are closed sets it already ships (`UI-D14`):

| New thing | How it appears | UI release? |
|---|---|---|
| A metric declared in a new package version | `GET /metrics/catalog`, `layer: package-baseline` | none |
| A metric an operator adds to collector config | same endpoint, `layer: user-extension` (`SM-D30`) | none |
| A metric nobody declared | arrives, is quarantined, appears as `needs-typing` (`UI-D5`) | none |
| A new instance | `GET /entity-types/{t}/instances` | none |
| A new entity type / solution | `GET /entity-types` | none |
| A new process runtime | invisible by construction (§3.1) | none |
| A new **mark**, `source.kind`, or aggregation | **an SM release** — closed vocabulary (`UI-D14`) | **yes, by design** |

The last row is the honest boundary. `UI-D14`'s bargain is that *data* is open and *dispatch vocabularies* are
closed, because a package-supplied dispatch value is per-solution code in everything but syntax.

## 7. Widget configuration schema (`DM-D6`, `DM-D12`, `DM-D19`)

Fully serializable — which is what makes dashboard persistence, Phase 2 URL sharing (`DM-D19`) and the §16
alerting seed all fall out of one artifact rather than three.

```jsonc
{
  "schemaVersion": 1,
  "id": "w_01H…",
  "title": "Evictions — production cache",
  "mark": "line",                       // THE ONLY viz axis. CLOSED SET (SM-D31, UI-D14):
                                        //   line | gauge | single-stat
  "options": {                           // styling only — never changes what is queried
    "fill":  true,                       // "area" is a fill on `line`, NOT a vocabulary member (DM-D12)
    "stack": false,                      // deferred by SM-D31; present so the schema need not change
    "scale": "linear"                    // linear | log  — see §12 for the counter restriction
  },
  "query": {
    "metric": "cache.evictions",         // EXACTLY ONE metric per widget (DM-D23).
                                         // Compare metrics with two widgets; compare
                                         // instances with the selector below.
    "selector": { "entityType": "cache-engine", "instanceIds": ["a1b2…"], "labels": {} },
    "seriesBy": "instance",              // instance | replica  (cap counts RENDERED SERIES, DM-D7)
    "aggregation": { "acrossReplicas": "auto", "acrossInstances": "none" },
    "groupBy": []                        // composes AFTER instance aggregation only (§5.7)
  },
  "range": { "kind": "preset", "preset": "24h" },   // DEFAULT — a dashboard overrides it (DM-D21)
  "resolution": "auto",                             // auto | raw  (SM-D31)
  "axes": {
    "y": { "label": "evictions/min", "unitFormat": "auto", "min": null, "max": null }
  },
  "legend":     { "visible": true, "position": "bottom", "maxHeightRows": 4 },  // scrolls, never truncates
  "series":     { "colorStrategy": "palette", "colors": { "a1b2…": "#4E79A7" } },
  "thresholds": [ { "value": 100, "label": "warn", "severity": "warning", "style": "dashed" } ],
  "refresh":    { "intervalSeconds": 60 }           // DEFAULT — a dashboard overrides it (DM-D21)
}
```

⚠️ **One visualization axis, not two.** An earlier draft carried both `viz.kind` and `viz.mark`, which made
`single-stat` appear on both and `{kind: timeseries, mark: gauge}` expressible and meaningless. `SM-D31` had
already fixed the mark set; a parallel axis was invented without checking. `mark` is now the sole axis and
`options` carries styling — see the changelog in [`decisions.md`](decisions.md).

**Range and refresh precedence (`DM-D21`).** A widget's `range` and `refresh` are **defaults**, not bindings:

| Context | What applies |
|---|---|
| On a dashboard | the **dashboard's** range and refresh, always — every widget shows the same window |
| Viewed standalone, or first placed | the widget's own `range`/`refresh` |
| Exported as a `DM-D15` alert seed | the widget's own `range` |

The config must still carry them, because `DM-D19`'s URL serialization and the `DM-D15` seed both need a
self-contained widget. **No pinning override:** two charts on one dashboard silently showing different windows is
a documented way to misread an incident, and the shared-dashboard rationale in `SM-D31` argues against it.

**Migration (`DM-D6`):**

- `schemaVersion` is a **monotonic integer**; migrations are forward-only and registered in an ordered chain, so
  `v1 → v4` runs three functions rather than one bespoke jump.
- **Unknown fields are preserved on round-trip.** An older console must not silently destroy a newer console's
  widget by saving it back — the failure mode is invisible and irreversible.
- A widget whose `schemaVersion` is **newer than the console** renders **read-only with a stated reason**, never
  partially. This mirrors `UI-D12`'s rule that a blocked state states its reason.
- Migrations are covered by fixture cases in the §9.4 suite, in both implementations.

## 8. Dashboard model (`DM-D5`)

> ⚠️ **`DM-D5` proposes superseding `SM-D31`'s scope clause, and is a proposal against a `PROPOSED` decision.**
> `SM-D31` decided dashboards are **per-instance and shared**, explicitly rejecting per-user dashboards because
> *"per-user dashboards fragment an operations view and double the RBAC surface."* A cross-instance widget has no
> home in that model — which is the point of `DM-D5`, and the reason it must be ratified as a supersession rather
> than absorbed. See §21.

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* **superseded for v1 by M-Q10 and M-Q23 (`OR-78`).**
> Dashboards are per-instance and shared, stored in `sm_control` with a scope column in which only `instance` is legal
> in v1; a saved widget names **one instance**; comparing instances is an ad-hoc query, not saved; Explore drafts on the
> server, saved comparison widgets and templating are out of v1. `DM-D5` is therefore not adopted for v1 and stays
> PROPOSED. The package-default layer is plan 011 OQ-7/OQ-8/OQ-18. **RBAC below is stale:** the six verbs are in the
> ratified `D10` amendment, and M-Q16 (`OR-80`) gives the Operator `metrics.export` and `metrics.forceRaw`.

**Three scope levels**, each holding dashboards:

| Scope | Holds | Who sees it |
|---|---|---|
| `instance` | `SM-D31`'s existing per-instance dashboards, unchanged | anyone with view on the instance |
| `entity` | **new** — cross-instance comparison, the EC2-style view | anyone with view on the entity type in an Organization |
| `organization` | **new** — fleet-wide overview | anyone with Organization-wide view |

**Three layers within a dashboard**, preserving `SM-D31`'s upgrade property exactly:

| Layer | Owner | On package upgrade |
|---|---|---|
| `product-default` | the Solution Package | **replaced wholesale** |
| `shared` | operators with `dashboard.edit` | **never touched** |
| `personal` | one user | **never touched** |

The rendered dashboard is the **union**. An operator may **hide** a default but not delete it — hiding is an
override stored in the layer above, so a later package can still improve that default and the operator's choice
survives. This is `SM-D31`'s mechanism, unchanged; only the *scope axis* is new.

**Layout** is a 12-column grid: each widget carries `{ x, y, w, h }` plus an explicit `order` for keyboard and
screen-reader traversal, which must not depend on pixel position (§18).

**RBAC (`DM-D18`)** — `D10` has **none** of these today, and `SM-D31` already flagged that gap:

`dashboard.view` · `dashboard.edit` · `dashboard.reset` · `metrics.query` · `metrics.export` · `metrics.forceRaw`

The last two are **high-consequence**: a forced-Raw query over six months and a streaming export are both
capable of straining the deployment, which is why architecture §16 already lists them as verb-gated.

## 9. Component architecture

### 9.1 The tree

```
<MetricsProvider>                      capabilities, query client, MUI theme → ECharts theme bridge
│
├── <ExploreView>                      ad-hoc: explore first, save second
│   ├── <FilterBar>
│   │   ├── <EntityTypeSelector>
│   │   ├── <InstanceSelector>         multi-select; shows PROJECTED series count (§11.4)
│   │   ├── <MetricSelector>           SINGLE-select (DM-D23); catalog-driven;
│   │   │                              disables `needs-typing` (§5.5)
│   │   ├── <LabelFilter>              label keys/values from the catalog
│   │   ├── <AggregationControl>       type-aware; forced non-`none` for single-stat/gauge (DM-D22)
│   │   └── <GroupByControl>           disabled unless acrossInstances != none (§5.7)
│   ├── <TimeRangePicker>              presets from capabilities, never hardcoded
│   ├── <ResolutionControl>            Auto · Raw   (SM-D31 — no free bucket size)
│   ├── <ChartRenderer> | <StatCard>
│   └── <SaveWidgetDialog>             → writes the §7 widget config
│
└── <DashboardView>
    ├── <DashboardToolbar>             TimeRangePicker · RefreshControl · ResolutionControl
    │                                  · LayerFilter          (all dashboard-wide, DM-D21)
    └── <DashboardGrid>
        └── <WidgetFrame>              title, LayerBadge, menu (Edit ▸), state chrome, error boundary
            ├── <ChartRenderer>        ECharts (DM-D13)
            └── <StatCard>
```

Two additions worth naming, because their absence was a real defect:

- **`ResolutionControl` belongs on the dashboard too**, not only in explore. Forcing Raw during an incident is
  precisely the case architecture §8.4 built the override for, and incidents are read on dashboards. Gated by
  `metrics.forceRaw`.
- **`LayerBadge` + `LayerFilter` make provenance visible.** `DM-D5`'s three layers have *different upgrade
  behaviour* — `product-default` is replaced wholesale on package upgrade, the others are never touched. Without
  a badge an operator cannot tell which of their widgets a package upgrade will replace, which makes hide-not-delete
  unintelligible.

**Pure core, shared by both views and by no component library:**

```
buildQuery(widgetConfig, timeRange, capabilities)  → QueryRequest
projectSeries(queryResponse, widgetConfig, theme)  → ChartModel      // data in, data out
```

### 9.2 State ownership

| State | Owner | Why |
|---|---|---|
| Capabilities | `MetricsProvider`, fetched once | every preset and cap derives from it |
| Server data (series, catalogs) | query cache keyed on the canonical query hash | §10 |
| Draft widget config | `ExploreView` local state | discarded on navigate-away unless saved |
| Saved widget + layout | server, via the dashboard API | shared scope means server-authoritative |
| Time range, refresh | `DashboardView`, one per dashboard — **authoritative over the widget's own** (`DM-D21`) | one range for all widgets is the operator's mental model, and two windows on one screen is how an incident gets misread |
| Chart instance state (zoom) | inside `ChartRenderer` | never lifted; it is presentation, like `UI-D20`'s canvas layout |

### 9.3 How a product embeds it

One package exporting `<MetricsProvider>` plus the two views. A host product supplies: the API base URLs
(`cpClient`/`dpClient` split per `SM-D1`), the MUI theme, an auth token provider, and an optional entity-type
filter. **No product-specific code paths inside the package** — `REQ-001`'s rule applied one level up.

⚠️ **Assumption, stated because it is unbacked:** "reusable across multiple products" has **no requirement in
`docs/requirements/`**. It changes the design materially (it is why §9.3 exists at all) and needs a `REQ` with a
source before it binds — see §21.

### 9.4 The divergence guard (`DM-D20`)

`projectSeries` is a pure function that will exist in **TypeScript** and, for server-side rendering of thresholds
and alert seeds, in **Java**. Per `UI-D15` the contract ships a **shared JSON fixture suite** —
`(response, widgetConfig) → expected ChartModel` — that both CIs must pass, with **gap cases**, **counter-rate
cases**, **replica-aggregation cases** and **schema-migration cases**. The suite pins JSON number formatting and
key ordering, *"or the comparison itself becomes the divergence."*

## 10. State management and data flow

**Query lifecycle:** filter change → `buildQuery` → canonical hash → cache lookup → in-flight dedup → fetch →
`projectSeries` → render.

| Concern | Rule |
|---|---|
| **Cache key** | the **canonical hash** of the query — key order normalized, instance IDs sorted, relative ranges resolved to absolute at issue time. Two widgets asking the same question issue one request. |
| **Deduplication** | identical in-flight keys share one promise. A dashboard of eight widgets on four distinct metrics issues four requests. |
| **Cancellation** | every request carries an `AbortController`; a filter change **aborts the superseded request before issuing the next**. Without this, a fast operator gets the older response last and the chart shows the wrong data with no error. |
| **Staleness** | stale-while-revalidate: the previous series stays on screen, dimmed, with a refreshing indicator. Blanking a chart on refresh is how an operator misreads a refresh as an outage. |
| **Retry** | bounded exponential backoff on `5xx` only. **Never retry `422`** — an incoherent aggregation is not transient. |
| **Batching** | widgets sharing a metric and range are coalesced into one multi-instance request. |

**Recommended implementation:** TanStack Query, which provides keying, dedup, cancellation and SWR directly.
⚠️ Flagged rather than decided — open item 19 blocks the frontend sub-spec, and the library choice belongs there.

## 11. UX flows and states

### 11.1 Explore, then save (`DM-D9`)

1. Pick entity type → instances (projected series count shown) → metric (`needs-typing` visibly disabled).
   **One metric per widget (`DM-D23`)** — picking a different metric **replaces** the current one and re-queries;
   it never adds a second series group. Instances are the comparison axis; to compare two *metrics*, save this
   widget and build a second one beside it.
2. Chart renders immediately at `resolution: auto`; aggregation controls offer only what the metric type permits.
3. Customize mark, axes, legend, colors, thresholds, scale.
4. **Save to dashboard** → pick scope (`instance`/`entity`/`organization`) and layer (`shared`/`personal`) → the widget
   is written as §7 config. Scope choice is gated by `dashboard.edit`.

**Edit closes the loop.** A saved widget's menu offers **Edit**, which reopens its config in `ExploreView` with
every control populated. Saving then either **overwrites in place** (requires `dashboard.edit` on that layer) or
**forks to `personal`** — which is the only way to modify a `product-default` widget, since a default may be
hidden but never deleted (`SM-D5`'s inherited rule from `SM-D31`). Without this path the explore flow is
write-once and every correction means rebuilding a widget from scratch.

**First run — no dashboards, no widgets.** The empty console does not show an empty grid. It shows the entity
types that exist with their instance counts, and a single primary action into `ExploreView`. If
`product-default` dashboards shipped with a loaded package, those render instead and the empty state never
appears — which is the normal case once a Solution Package is loaded, and the reason the empty state is a
first-install concern rather than a routine one.

### 11.2 The states, and what each must not do

| State | Renders | Must not |
|---|---|---|
| **First run (nothing saved)** | entity types + instance counts + one action into explore (§11.1) | show an empty dashboard grid |
| **Loading (first)** | skeleton at chart dimensions | shift layout when data arrives |
| **Refreshing** | previous series dimmed + indicator | blank the chart |
| **Empty selection** | prompt naming the missing choice | show an empty axis |
| **No data for range** | *"No samples in this range"* + the served tier + nearest data hint | **draw a zero line** (`UI-D8`) |
| **Partial data** | series that resolved, plus a banner naming each unavailable instance and why | present a partial fleet as complete |
| **Gaps** | line **breaks**; low-coverage buckets visually distinct via `sampleCount/expectedCount` | zero-fill or connect across (`UI-D8`) |
| **Retention truncation** | chart from the earliest retained point + *"Data before X is outside the Y retention window"* | pretend the range was served |
| **`needs-typing`** | disabled in picker + "type this metric" affordance | infer a type (`SM-D30`) |
| **`type-suspect`** | chart + persistent warning | auto-correct the declared type |
| **`pending-config-change`** | series marked pending | render as retirement (`UI-D7`) |
| **`inactive-replica`** | annotated, excluded from `avg` | count as a healthy zero |
| **Unverified durability** | caveat badge (`SM-D27`) | imply gaps are impossible |
| **Error** | typed message + retry, scoped to the widget via an error boundary | take down the dashboard |

### 11.3 Two rules inherited verbatim

- **A red badge means: open the graphs, and no data confirms it** (`UI-D9`). That cross-check only works if a gap
  renders as a gap — a zero-filled graph reads as *"data arriving, value 0"*, the opposite conclusion.
- **`restart-observed` is never presented as convergence** (`SM-D22`).

### 11.4 The series cap (`DM-D7`)

**The cap counts rendered series, not instances.** Default **10**, deployment-configurable **10–20**, advertised
as `capabilities.maxSeriesPerWidget`.

⚠️ **Counting instances was a defect.** In `seriesBy: replica`, ten instances with five replicas each is **50
lines** through a cap reading "10 / 10" — so a cap whose stated purpose is *query cost and chart legibility*
delivered neither in the one mode that needs it most.

**The projection is `Σ replicas` because a widget carries one metric (`DM-D23`).** Multi-metric would multiply
rendered series by metric count, so the number shown before committing would understate the real count — which is
the whole point of showing it. This is one of the three mechanisms `DM-D23` records as assuming a single metric.

**The selector projects before you commit.** As instances are selected it shows
`projected series = Σ replicas` for the current `seriesBy`, so switching to replica mode visibly *reduces* how
many instances remain selectable rather than silently blowing past the cap. At the cap, unselected instances are
**disabled** with an inline explanation and two routes forward: narrow by label, or aggregate across instances
into one line. It does **not** truncate silently, and it does not let a selection succeed and then fail at query
time.

⚠️ **Distinct from `SM-D30`'s per-instance cardinality cap** — different mechanism, different failure, different
remedy. `SM-D30` protects *ingest* by rejecting new series at write time; this protects *query cost and
legibility* at read time. They must never share a name, a config key, or an error code
(`SERIES_CAP_EXCEEDED` here).

## 12. Chart rendering (`DM-D13`)

**Apache ECharts** for every visualization; MUI for everything around it.

⚠️ **This is a new decision with no precedent in the repo.** `D7` 🔒 locks React + Vite + MUI and anticipates
*harvesting File Mesh's ~66 MUI wrappers including charts*; **no charting library is recorded anywhere**. `DM-D13`
records the choice rather than letting it arrive as an implementation detail.

| Concern | Approach |
|---|---|
| **Theming** | one adapter maps the MUI palette/typography to an ECharts theme object. Light/dark follows MUI's mode; **no second theme source** |
| **Renderer** | `canvas` (not SVG) — 10 series × ~1000 points is where SVG DOM cost becomes visible. `large: true` and `progressive` above a point threshold |
| **Colors** | see below — sequential within a chart, not hashed |
| **Legend** | **scrolls, never truncates.** `maxHeightRows` bounds its height, not its membership |
| **Tooltip** | see below — the primary identification mechanism |
| **Thresholds** | ECharts `markLine`; severity carries both color **and** dash pattern (§18 — never color alone) |
| **Gaps** | fed as absent points with `connectNulls: false`; the server's `gaps[]` drives shaded coverage bands |
| **Units** | formatted from the canonical `unit`, never parsed from the metric name |
| **Log scale** | restricted — see below |

⚠️ **Colors are assigned sequentially within a chart, not hashed from the instance ID.** An earlier draft
promised colors "assigned stably by instance ID so a series keeps its color across widgets" — but hashing into
an ~8-entry color-blind-safe palette **collides sooner than sequential assignment**, and two identically colored
lines on one chart is a worse failure than a color changing between widgets. So: sequential within a chart
(distinct up to palette size), cross-widget stability as a **preference** honoured when it does not cause a
collision, and past palette size a **second visual channel** — dash pattern — rather than a reused color.
Explicit per-series overrides remain available in the widget config.

⚠️ **The legend scrolls; it never truncates.** Plotting a series with no legend entry leaves the operator unable
to identify a line — which defeats the whole point of a multi-instance overlay.

**Tooltip is load-bearing, not decoration.** With ten overlapping lines it *is* how a series is identified. It is
a **shared-axis** tooltip listing every series at the cursor, **sorted by value**, each row carrying its color
swatch, formatted value, and its per-series state (`gap` · `inactive-replica` · `pending-config-change` ·
unverified durability). Without the state in the tooltip, §11.2's careful gap rendering stops at the line and
never reaches the number the operator actually reads.

⚠️ **Log scale is disabled for counters under `transform: rate`.** A counter rate is legitimately **zero**
whenever the workload is idle — the common case, not an edge case — and a log axis silently drops every
non-positive point. Offering it and warning afterwards would make the most informative points vanish from the
most common state. The control is disabled with the reason stated. For gauges it remains available, and
non-positive values are excluded **with a visible notice**, never silently.

## 13. Performance

| Pressure | Mitigation |
|---|---|
| Long range | **server-side tier routing** (§8.4): ≤7d raw · 7–90d hourly · >90d daily. Downsampling never happens in the browser |
| Point count | `maxPointsPerSeries` (~1000) — *"a graph is ~600–1200 pixels wide and cannot display more than ~1000 points"* ([architecture.md:677](../architecture.md#L677)) |
| Series count | the **series cap** (`DM-D7`) bounds rendered lines directly, in both `instance` and `replica` mode |
| Many widgets | `IntersectionObserver` — widgets below the fold do not query until visible |
| Refresh cost | incremental `appendData` when the new response extends the previous range; full replace only on a query change |
| Payload | tuple-array points, not per-point objects; label metadata sent once per series |
| Legend cardinality | legend **virtualizes its rows and scrolls** past `maxHeightRows` — every plotted series stays reachable in it |

## 14. Refresh behaviour (`DM-D17`)

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* **"no push channel" is superseded.** M-Q12 (`OR-73`) rules
> live delivery of broker-pushed business metrics to the browser via a broker topic, and `BE-D6` (PROPOSED) supersedes
> `DM-D17` with live raw-tier chart tails. Which plan builds the live leg's backend half is plan 011 OQ-14. Polling stays
> as the fallback.

**Polling, not streaming, in v1.** The data plane has no push channel to the browser today and adding one is a
new transport, not a UI feature — deferred, with the widget config's `refresh` block forward-compatible.

- Intervals: `off · 10s · 30s · 1m · 5m`, **floored at the served `bucketSeconds`** — polling a one-hour rollup
  every ten seconds returns the same bucket and wastes a query.
- **Paused on `document.hidden`**, resumed with one immediate fetch on visibility.
- Paused while a widget is off-screen (§13), and while a filter is being edited.
- Auto-refresh is **disabled for absolute ranges** — there is nothing new to fetch.

## 15. Extensibility

| Adding | Cost | Why |
|---|---|---|
| A metric, instance, entity type, label | **zero** — catalog data (§6) | discovery is a query |
| A process runtime | **zero** | the contract binds the data plane, not the process (§3.1) |
| An ingestion mechanism | **zero to the UI** | per-binding policy (`SM-D23`) |
| A **mark** (= a visualization type) | one `mark` member + one renderer + a `projectSeries` branch + fixture cases | `mark` is a **closed set** (`UI-D14`), bounded in v1 by `SM-D31` — an SM release, deliberately |
| A **styling option** | an `options` field; no vocabulary change | `options` is open data, not a dispatch field |
| An **aggregation function** | closed set + a type-semantics row in §5.7 | `avg(avg)` is why this is not open |
| A **store** | the query API is the seam (§5.1) | `D14` 🔒 stands; only the seam is portable |

⚠️ **There is exactly one visualization axis — `mark`.** An earlier draft added a parallel `viz.kind` alongside
it, which made `single-stat` a member of both and let meaningless pairs be expressed. Extension attaches to
`mark`, and to nothing else.

**`bar`, `table` and `heatmap`** are the anticipated `mark` additions; `heatmap` additionally needs the histogram
support v1 explicitly lacks. **`area` and `stack` are not on this list** — `area` is `options.fill` on
`mark: line`, identical data semantics, so it costs no vocabulary change. `stack` is present in `options` but
**deferred by `SM-D31`**: unlike fill it changes meaning, implying parts of a whole.

## 16. Alerting integration point (`DM-D15`)

⚠️ **The alerting service does not exist.** [architecture.md §16.1](../architecture.md#L915) states plainly that
until it does, *"drift and version states are UI-state-only and nobody is notified."* This section defines the
hand-off only, and the UI must not imply an alert will fire.

"Create alert from this widget" emits a **seed** — a proposal, not a rule:

```jsonc
{
  "seedVersion": 1,
  "source": { "widgetId": "w_01H…", "dashboardId": "d_01H…" },
  "metric": "cache.evictions",
  "selector": { "entityType": "cache-engine", "instanceIds": [...], "labels": {} },
  "aggregation": { "acrossReplicas": "auto", "acrossInstances": "none" },
  "resolution": "auto",
  "condition": { "op": ">", "value": 100, "forSeconds": 300 },   // seeded from the widget threshold
  "context": { "unit": "count", "metricType": "counter", "transform": "rate" }
}
```

Three rules: the seed is **immutable and one-way** (editing the widget later does not mutate a rule built from
it); it carries `metricType` and `transform` so the alerting system cannot re-derive them wrongly; and **alert
lifecycle, routing, dedup and severity are out of scope** and belong to the alerting sub-spec.

## 17. Degradation

| Failure | Behaviour |
|---|---|
| Query API fully unavailable | dashboard chrome renders; every widget shows a scoped error with retry. **Never a blank page** |
| Query API partially available | resolved series render; a banner names each unavailable instance **and why** |
| One widget errors | error boundary contains it; siblings keep refreshing |
| Instance has gaps | line breaks; coverage bands from `sampleCount/expectedCount` |
| Retention truncates the range | chart from the earliest retained point + explicit notice |
| Metric untyped | disabled in picker with a typing affordance |
| Metric `type-suspect` | charts with a persistent warning, no auto-correction |
| Capabilities fetch fails | **fail closed** — conservative defaults (series cap 10, presets to 24h, no forced-Raw), banner stating the UI is running on defaults |
| Durability unverified | caveat badge (`SM-D27`) |

## 18. Non-functional

**Accessibility.** Target **WCAG 2.1 AA**.

| Concern | Requirement |
|---|---|
| **Keyboard — chrome** | full traversal of every selector, the legend, and the widget menu |
| **Keyboard — chart** | the chart is one focus stop; `←`/`→` move the cursor along the time axis, `↑`/`↓` change the focused series, `Enter` opens the tabular alternative. A canvas chart is otherwise unreachable by keyboard entirely |
| **Non-visual alternative** | a **tabular data alternative** for every chart, keyboard-reachable, with a text summary (metric, range, series count, min/max/last per series) |
| **Color** | the categorical palette is **color-blind-safe** and **never the sole carrier** — thresholds also differ by dash pattern, series by marker shape (which is also what §12's past-palette-size fallback relies on) |
| **Contrast** | ⚠️ a color-blind-safe palette is **not automatically AA-compliant**. Every palette entry must meet **≥ 3:1** against **both** the light and dark surface tokens, and text/labels **≥ 4.5:1**. The palette is validated against both themes in CI, not chosen by eye |
| **Focus** | visible focus ring meeting contrast against both themes |
| **Motion** | transitions respect `prefers-reduced-motion`; auto-refresh never animates a re-plot |
| **ECharts** | `aria` enabled, with series names supplied from the same labels the legend uses |

⚠️ There is **no accessibility checklist in `docs/checklists/`** — this section is the proposal, and adding a
checklist entry is routed in §21.

**i18n.** All strings externalized; ICU message format for plurals; numbers, dates and units formatted by locale;
**UTC is canonical** (`B/D15`) with a display-timezone toggle and the active zone always labelled on the axis —
an unlabelled time axis is how an operator misreads an incident window.

**Browsers.** Evergreen Chrome, Edge, Firefox, Safari (latest two). ⚠️ **Assumption** — no browser-support matrix
exists in the repo.

**Viewport.** Desktop only, **≥ 1280 px**. Widgets reflow within the 12-column grid but there is **no phone or
tablet layout** — an explicit non-goal, recorded as a limit in §20 rather than left as silence. An operations
console read at 375 px would need a different information architecture, not a responsive stylesheet.

## 19. Phasing

| | MVP (v1) | Phase 2 |
|---|---|---|
| Marks | `line` (+`options.fill`), `single-stat`, `gauge` | `bar`, `table`, `heatmap`, `options.stack` |
| Selection | **one metric** (`DM-D23`) × multi-instance to the **series** cap, label filters, group-by | saved instance sets |
| Widget lifecycle | explore → save → **edit / fork** (§11.1) | duplicate, bulk move between dashboards |
| Aggregation | across replicas + across instances, type-aware | percentile/quantile (needs histogram support) |
| Dashboards | three scopes, three layers, grid layout | templating, variables |
| Export | **streaming CSV** (§5.8) | **PNG** |
| Sharing | config URL-serializable **by design** | share-by-URL feature |
| Refresh | polling + visibility pause | streaming |
| Alerting | **seed emission only** | full integration once alerting exists |

⚠️ **Sequencing constraint.** `docs/plans/` is empty and the GitHub Project owns status, so no in-flight work was
checkable. But `B/D5` 🔒 imposes a **hard gate**: the walking skeleton — one generated screen, **one graph**, one
role, one config apply, accepted end-to-end — must pass **before** anything else. The MVP above is broader than
that one graph, so **the walking skeleton's graph is this design's first vertical slice**, not a parallel effort.

## 20. Capability limits

Stated, not solved. These must be accepted knowingly.

| Limit | Cause |
|---|---|
| **One metric per widget — no two metrics on one chart** | `DM-D23`: `render.transform` is server-decided per `metricType` (§5.6), so a counter-rate and a gauge share no honest y-axis or unit format; `DM-D8`'s aggregation is per type; and `DM-D7`'s `Σ replicas` projection would understate series count. Compare via **two widgets**, which `DM-D21` guarantees share one window. **Deferred, not scheduled** — no `REQ` asks for it |
| **Histograms and quantiles are unplottable in v1** | quantiles cannot be averaged into rollups (architecture §8.3) |
| **Six-month retention is advertised, not assured** | `REQ-007` is ❌ unproven; the sizing model has never been benchmarked (open item 5) |
| **Cross-instance aggregation of a `counter` depends on materialized `delta`** | a late sample cascades correction through `N+1` and every upper tier (`SM-D26`); a chart drawn during correction can shift |
| **An `INACTIVE_REPLICA` cannot be detected from telemetry alone** | Cache exposes leader state only in a log line; `HC1` forbids asking for more. Excluded from `avg` only where declared |
| **The gap threshold assumes a *declared* scrape interval** | for `broker` push signals with no declared cadence, gap detection degrades to `best-effort` (`SM-D27`) |
| **A user-extension metric cannot back a package-declared graph** | `SM-D30` — the package cannot assume it exists |
| **No per-pod metrics for a footprint-refusing K8s customer** | architecture §13 — pod IPs unroutable; the UI shows fewer series with a stated reason |
| **Alerting does not exist** | §16 — the seed goes nowhere until the alerting sub-spec is built |
| **Distinct series colors are bounded by palette size** | §12 — past ~8 series, identity is carried by dash pattern rather than color; a color-blind-safe **and** AA-contrast palette cannot be extended indefinitely |
| **Desktop only, ≥ 1280 px** | §18 — an explicit non-goal; a small-viewport console needs different information architecture, not a responsive stylesheet |
| **Log scale is unavailable for counter rates** | §12 — an idle workload's rate is legitimately zero, and a log axis drops every non-positive point |
| **Everything here rests on unratified decisions** | `I4` — `SM-D30`/`SM-D31`/`UI-D*`/`DM-D*` are all `PROPOSED` |

## 21. Open items and flagged conflicts

> ⚠️ *2026-10-05 (plan 011 P11-0.2; text unedited, `I3`):* status of the items below. **Resolved by rulings:** the
> composition-module row (`OR-10`, `OR-78`); item 1 (M-Q7, `OR-75`); item 2 (`REQ-010` exists, `OR-78`); item 4 (the
> ratified `D10` amendment; `OR-80`); item 11 (this review ran; see the [review](../../tasks/dm-design-review-2026-10.md)). **Stale:** item 8 (plans 001–011
> exist). **Partly verified:** item 10 — see the note after the table. **Still open:** the `DM-D5`/`SM-D31` and
> `DM-D13`/`D7` flags, items 3, 5, 6, 7 (now plan 011 OQ-14) and 9.

⚠️ **Flagged, not resolved** (`I4`):

| | Conflict |
|---|---|
| **`DM-D5` vs `SM-D31`** | `SM-D31` scopes dashboards **per-instance and shared** and explicitly rejects per-user. `DM-D5`, with `DM-D24`'s `D20` vocabulary correction, adds `entity` and `organization` scopes and a `personal` layer. It is a **proposal against a `PROPOSED` decision** — the same ordering problem `UI-D12` vs `SM-D14` already created. It cannot be ratified before `SM-D31` is. |
| **`DM-D13` vs `D7`** | `D7` 🔒 anticipates harvesting File Mesh's MUI chart wrappers. `DM-D13` selects ECharts. Not a contradiction of `D7`'s locked *stack*, but it does discard an assumed reuse path, and `D7`'s own status is contested by **open item 19**. |
| **§5.2 composition module vs `I7`** | `UI-D13` requires composing `sm_control` and `sm_data` reads above both data-access modules; architecture §3.1 names only **two** backend modules. The composition layer is a **third**, and is undeclared. Needs a decision. |

Remaining open:

| # | Item |
|---|---|
| 1 | **`UI-D5`'s source discriminator is absent from `V001`** and the `V002` that adds it does not exist. Two scrape paths for one metric name **merge silently** — no constraint test can catch it (`UI-D18`). **Blocks implementation.** |
| 2 | **`REQ-010` — a metrics UI requirement does not exist.** `design/README.md` step 1 requires the requirement exist and be traced before a component design. Closest are `REQ-001`/`REQ-007`; neither asks for this. |
| 3 | **`REQ-011` — "reusable across multiple products" has no source.** It shapes §9.3 materially. Needs a `REQ` with a named source before it binds. |
| 4 | **New RBAC verbs are not in `D10`** — `dashboard.*` (already flagged by `SM-D31`) plus `metrics.query/export/forceRaw`. |
| 5 | **Query-cache library** — TanStack Query recommended; belongs to the frontend sub-spec, which **open item 19 blocks**. |
| 6 | **No accessibility or i18n checklist exists** in `docs/checklists/`. §18 is a proposal; the checklist entry should follow. |
| 7 | **Streaming refresh transport** — deferred; the DP has no browser push channel today. |
| 8 | **`docs/plans/` is empty and the Project was not readable from here**, so overlap with in-flight work is unverified (§19). |
| 9 | **The palette is unbuilt.** §18 requires every entry meet ≥ 3:1 against **both** theme surfaces while staying color-blind-safe, validated in CI. Whether ~8 entries survive both constraints simultaneously is **unverified** — if fewer do, §12's dash-pattern fallback starts earlier than assumed. |
| 10 | **ECharts behaviour is asserted, not verified.** `connectNulls`, `large`/`progressive` thresholds, `aria` output and canvas keyboard reachability (§18) were written from knowledge, not from vendor documentation. Per `I2`/`L1` these are exactly the class of claim this project has been burned by three times — **verify with WebFetch before implementation.** |
| 11 | **Pass 2 has not run.** Per [`../../checklists/spec-review.md`](../../checklists/spec-review.md) and `L2`, this document is **not done** until an independent adversarial review has run and been triaged. A self-review round (R1, see [`decisions.md`](decisions.md) changelog) found 14 findings including three internal contradictions — which is evidence for pass 2, not a substitute. |

> ⚠️ *2026-10-05, item 10 partly verified (plan 011 P11-0.2, DMR-19):* ECharts' own docs say *"'-' or null or undefined
> or NaN can be used to describe that a data item does not exist"* and *"line chart can break when encounter an empty
> value"*; `connectNulls` is *"Whether to connect the line across null points"*, default `false`
> ([2d-data](https://raw.githubusercontent.com/apache/echarts-doc/master/en/option/partial/2d-data.md),
> [series-line](https://raw.githubusercontent.com/apache/echarts-doc/master/en/option/series/line.md), fetched 2026-10-05,
> doc source `master`, not version-pinned). So ECharts does **not** coerce `null` to `0` (§5.6 rule 1's rationale is wrong
> for this library), and an **omitted** point does not break a line: `projectSeries` must insert an empty value at each
> server-declared gap. The wire rule (no tuple, explicit `gaps[]`) stands. `large`, `progressive` and `aria` remain
> unverified.

## 22. Traceability

| REQ | How this design serves it | Caveat |
|---|---|---|
| `REQ-001` | §6 — new metrics/instances/entity types/runtimes appear with zero console releases | closed vocabularies are the deliberate exception (`UI-D14`) |
| `REQ-006` | §8 RBAC verbs | the verbs do not exist in `D10` yet (open item 4) |
| `REQ-007` | §5.4 presets reach six months, driven by advertised retention | ❌ **unproven** — never present as assured |
| `REQ-009`/`HC1` | §3.1 — the contract binds the data plane, not the process | the one place this design could have violated `I1` |
| **none** | the feature itself | **open item 2** — needs `REQ-010` |

`../../requirements/traceability.md` must gain rows for `REQ-010`/`REQ-011` once raised.
