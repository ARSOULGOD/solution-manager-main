# Dynamic Metrics design — independent adversarial review (M-Q1), 2026-10-05

**Task:** plan 011 [P11-0.2](../plans/backend/011-monitoring-backend.md#task-p11-02--the-dm-designs-adversarial-review-m-q1-and-the-docs-hygiene-the-rulings-require)
(plan 011 is a **DRAFT, not approved**; `OR-91` lets its docs-only MW0 run now). **Authority for the review:** M-Q1 (b),
registered as [`OR-69`](../design/DECISIONS.md): *"the dynamic-metrics design's adversarial review runs as plan 011's first
wave, before storage."* **Reviewer:** Opus 5.5, read-only pass, then the docs-hygiene edits the task lists.
**Measured against:** branch `p011/mw0` at `a521378`. **Every `file:line` below is as of `a521378`**, before this commit's
inserted pointers shifted the lines.

**Under review:** [`dynamic-metrics/design.md`](../design/dynamic-metrics/design.md) and
[`dynamic-metrics/decisions.md`](../design/dynamic-metrics/decisions.md) (`DM-D*`, all PROPOSED), checked against the
monitoring rulings `OR-71`…`OR-81` and `OR-82` ([`DECISIONS.md`](../design/DECISIONS.md)), the LOCKED decisions, the
architecture, the contracts ([`console-integration.md`](../design/common/contracts/console-integration.md) §5.4 rows 53–66),
the tree plan 010 has built, and external facts (fetched, cited below).

**What this review is not.** It decides nothing. Every owner question stays open; where a finding needs the owner, it is
routed to an existing plan 011 OQ row or drafted as a new one (§4). No decision's meaning is edited (`I3`): `DM-D*` bodies
are untouched, and every correction in `design.md` is a dated pointer to the ruling that supersedes the text, which stays.
**Do not read this as "all closed"** — each finding carries its own disposition.

---

## 1. Findings

Severity: **MAJOR** = an implementer following the design would build what a ruling forbids, or a fact is wrong;
**MINOR** = stale or inconsistent text that misleads but does not change what gets built; **INFO** = checked, no defect.
Dispositions: **fixed** (a dated pointer or the listed hygiene edit, in this commit) · **routed** (to a named OQ, plan 011
task, or the integrator) · **disputed** (with evidence) · **no change**.

### 1.1 The corrections P11-0.2 lists (Step 2)

| # | Sev | Where | Defect | Ruling / evidence | Disposition |
|---|---|---|---|---|---|
| DMR-1 | MAJOR | `design.md:75` (§2.2 row), `:158-168` (§4.2) | *"`V001__telemetry_schema.sql` … the source of truth for series identity. Prose loses to the migration"*, with the key `(…, package_version, source_key, labels_fingerprint)`. The orphan V001 (`backend/src/main/resources/db/migration/V001__telemetry_schema.sql:73-74`) still carries that key | **M-Q7 `OR-75`:** identity is `(tenant_id, instance_id, placement_id, metric_name, metric_type, unit, source_discriminator, labels_fingerprint)`, `package_version` an **attribute**; `OR-75`'s right-hand column flags these lines. Deleting V001 is discovery default **D-8**, not a ruling (**OQ-22**) | **Fixed** (pointers at §2.2 and §4.2; the supersession map). V001's deletion is **not** asserted: it waits on OQ-22 |
| DMR-2 | MAJOR | `design.md:182-196` (§4.4), `:377-386` (§5.7), `:327` (`acrossReplicas: "auto"`); `DM-D4` | Replicas are aggregated type-awarely into one line per logical instance, including `avg` of a gauge across replicas | **M-Q20 `OR-75`:** no cross-placement gauge aggregation in v1 — counters sum, a gauge reported by more than one placement shows **one line per placement**, non-overlapping placements after a re-target render as one line. On dp-remote a "replica" is a placement (`source_key := placement_id`, M-Q7) | **Fixed** in `design.md` (pointers at §4.4 and §5.7). `DM-D4`'s body is **not edited** (`I3`): hand-off H2. Consequence routed to **P11-6.1**: `DM-D7`'s projection `Σ replicas` must count **live placements** for a gauge in `seriesBy: instance` |
| DMR-3 | MAJOR | `design.md:425` (§5.8 row "Some instances unreachable/unauthorized") | An unauthorized instance is named in `partial.unavailable[]` with a reason — which tells the caller it exists | **M-Q17 `OR-79`:** an instance the viewer cannot see is omitted silently, identical to a nonexistent id (`OR-51` reach filtering); `partial.unavailable[]` names only authorized-but-unreachable instances. Also withdraws M-Q10's "404 the whole query" | **Fixed** (row marker + note under the table) |
| DMR-4 | MAJOR | `design.md:415-418` (§5.8), `architecture.md:686` (§8.4); `DM-D14` (`decisions.md:261,265-266`) | Export is a server-side `COPY … TO STDOUT` | **M-Q18 `OR-79`:** a bound, streaming `SELECT`, CSV encoded in the application, parameters separate from the SQL text (`I5`), cursor batches; measured with a 105 M-row test before export ships. The **archive** `COPY` of a detached partition (architecture §8.5) **stays**, and is identifier-only (`OR-77`) | **Fixed** in `design.md` and `architecture.md` §8.4 (pointers; the sentences stay). `DM-D14`'s body is a decision's meaning: hand-off H2 |
| DMR-5 | MINOR | `design.md:14` | *"`DM-D1`…`DM-D20`"* — a range in prose, already stale (`DM-D21`…`DM-D23` exist) | `I3`: cite the family prefix, never a range | **Fixed** (now "the `DM-D*` family"). The same range in `decisions.md:10-11` describes the R1 draft set historically; `decisions.md` is outside P11-0.2's file list: hand-off H2 |
| DMR-6 | MINOR | `design.md:412` | `POST /api/console/v1/export/range` | `console-integration.md` row 60 (the contract) is `GET /export/range`; plan 011 also cites discovery default **D-12** (not a ruling, **OQ-22**). The contract governs whichever way OQ-22 goes | **Fixed** (visible correction with a dated comment; `GET` is the contract's, not a new choice) |

### 1.2 Further text the rulings superseded (found by this review)

| # | Sev | Where | Defect | Ruling / evidence | Disposition |
|---|---|---|---|---|---|
| DMR-7 | MAJOR | `design.md:129-130` (§3.2 rule 3); `architecture.md:649` (§8.3) | *"`histogram` is accepted … Only `_count` and `_sum` are ingested, as counters"* | **M-Q6 `OR-75`:** a histogram is **skipped by the collector** and the catalog reports `unsupported` | **Fixed** in `design.md` (map). `architecture.md:649` is outside this task's architecture edit (the export sentence only): hand-off H3 |
| DMR-8 | MAJOR | `design.md:69` (§2.2), `:141` (§3.3), `:283-302` (§5.5), `:431`, `:443` (§6), `:685` (§11.2), `:844` (§17) | Undeclared metrics are **quarantined** and listed as `needs-typing` with a "type this metric" affordance; `422 METRIC_UNTYPED` | **M-Q6 `OR-75`:** every path stores **only declared** metrics; undeclared arrivals are dropped and counted (`undeclared_dropped`, M-Q21); the ingest port reserves `pending` so quarantine can return later; the typing route (`console-integration.md` row 61, `metric.declare`) is a **contracted refusal**. So `needs-typing` has **no v1 producer** | **Fixed** (pointer at §5.5; map). The catalog's v1 states are the contract's (**P11-1.2**), including `unsupported` (and OQ-24 (b)'s "no port declared") |
| DMR-9 | MINOR | `design.md:283`, `:442` (`layer: user-extension`, "a metric an operator adds to collector config") | A v1 producer of operator-added metrics is implied | **M-Q5 `OR-74`:** per-instance overrides are out of v1; M-Q6's `metric.declare` refusal | **Fixed** (map) |
| DMR-10 | MAJOR | `design.md:210-223` (§5.2), `:925` (§21 conflict row); `DM-D1` flag (`decisions.md:66-71`) | "Where this API lives is unresolved"; assumes an undeclared third `console` module; `/api/dp/v1/*` for pure-DP reads | **`OR-10`** (item 21 closed 2026-09-16) and **M-Q11 `OR-78`**: `console-gateway`, `OR-10`'s composition module, hosts the `/api/console/v1` metrics routes. The contract has **no** `/api/dp/v1` metrics route (rows 53–66 are all `/api/console/v1`) | **Fixed** (pointer at §5.2; map). `DM-D1`'s flag: hand-off H2 |
| DMR-11 | MAJOR | `design.md:517-553` (§8), `:658-659` (§11.1 step 4), `:886` (§19); `DM-D5` | Three scopes (`instance`/`entity`/`tenant`), three layers including `personal`; save picks scope and layer | **M-Q10 `OR-78`:** per-instance shared dashboards in `sm_control`, a scope column with **only `instance` legal in v1**. **M-Q23 `OR-78`:** layer `shared`, **one instance per saved widget**; multi-instance compare is an **ad-hoc query, not saved**; out of v1: server-side Explore drafts, saved comparison widgets, templating. `DM-D5` is therefore **not adopted for v1** and stays PROPOSED (`OR-78`: "`SM-D31` and `DM-D*` stay PROPOSED"). Whether the package-default layer is a `REQ-010` criterion is **OQ-18**; how a package keeps a metric out of it is **OQ-7**; what reset restores is **OQ-8** | **Fixed** (pointer at §8; map). **Routed:** OQ-7, OQ-8, OQ-18 |
| DMR-12 | MAJOR | `design.md:473` (§7 `instanceIds`), `:403-406` (`DM-D22`) | A saved widget may select many instances, so `DM-D22`'s aggregation rule applies to saved widgets | **M-Q23 `OR-78`:** one instance per saved widget. `DM-D22` still governs the **ad-hoc** multi-instance query (M-Q10: `acrossInstances` none, sum, avg …) | **Fixed** (map) |
| DMR-13 | MINOR | `design.md:548-553` (§8 RBAC), `:934` (§21 item 4), `:948` (§22) | *"`D10` has none of these today"* | The ratified `D10` amendment ([`D10-G2-APPROVAL-2026-09-15.md`](../design/D10-G2-APPROVAL-2026-09-15.md) §1.1 rows 16–22) carries all six `DM-D18` verbs plus `metric.declare`; **M-Q16 `OR-80`** gives the Operator `metrics.export` and `metrics.forceRaw` (19 → 21) | **Fixed** (pointer at §8; map) |
| DMR-14 | MAJOR | `design.md:778-787` (§14), `:889` (§19); `DM-D17` | *"Polling, not streaming, in v1. The data plane has no push channel to the browser"* | **M-Q12 `OR-73`** rules path 2's **live delivery to the browser via a broker topic**; `BE-D6` (PROPOSED) supersedes `DM-D17` ([`backend/decisions.md`](../design/backend/decisions.md) row `BE-D6`), a proposal against a proposal. Which plan builds the live leg's backend half is **OQ-14** | **Fixed** (pointer at §14). **Routed:** OQ-14 |
| DMR-15 | MINOR | `design.md:932` (§21 item 2), `:951-953` (§22) | *"`REQ-010` … does not exist"* | **M-Q23 `OR-78`** raised [`REQ-010`](../requirements/REQ-010-per-instance-dashboards-and-comparison.md). `REQ-011` is still reserved and unraised (item 3 stands) | **Fixed** (pointer at §21; map) |
| DMR-16 | MINOR | `design.md:47-51` (§2.1) | *"The three reference codebases … are not present on this machine"* | Measured 2026-10-05: `solace-cache-engine`, `pubsubplus-connector-file-common` and `file-mesh-ui` are present under `/Users/parikshit/Work/Development/`. Their Cache / File Mesh claims in this design (e.g. §20's "leader state only in a log line") were **not** re-derived here — a sibling-repo read is not in P11-0.2's brief | **Fixed** (map states the fact only). **Routed:** File Mesh facts to **P11-5.0**; Cache claims to whichever task first relies on them |
| DMR-17 | MINOR | `design.md:892` (§19), `:938` (§21 item 8) | *"`docs/plans/` is empty"* | Plans 001–011 exist; plan 011 is the metrics backend (DRAFT) | **Fixed** (map) |
| DMR-18 | INFO | `design.md:3-4`, `:941` (§21 item 11) | "Independent adversarial review not yet run" | This document is that review (M-Q1). Pass 2's triage is §1 here; it does **not** ratify anything | **Fixed** (header pointer) |

### 1.3 Defects the rulings do not settle

| # | Sev | Where | Defect | Evidence | Disposition |
|---|---|---|---|---|---|
| DMR-19 | MAJOR | `design.md:366-367` (§5.6 rule 1), `decisions.md:291-293` (`DM-D16`), `design.md:739` (§12 "Gaps") | **Wrong external fact, for the chosen library.** The rationale says a `null` is something *"chart libraries coerce to `0`"*. ECharts' own docs say the opposite: *"'-' or null or undefined or NaN can be used to describe that a data item does not exist"*, and *"line chart can break when encounter an empty value"*. And `connectNulls` is *"Whether to connect the line across null points"*, default `false` — it acts on **empty values**. §12's *"fed as absent points with `connectNulls: false`"* therefore does **not** break a line if "absent" means *omitted*: with no empty item between two neighbours there is nothing to break at. The **wire rule** (no tuple for an empty bucket, server-declared `gaps[]`) is still sound and stays; what is **unstated** is the renderer's duty: `projectSeries` must insert an explicit empty value at each server-declared gap, or the line silently connects across it | WebFetch 2026-10-05: [`echarts-doc …/partial/2d-data.md`](https://raw.githubusercontent.com/apache/echarts-doc/master/en/option/partial/2d-data.md) and [`…/series/line.md`](https://raw.githubusercontent.com/apache/echarts-doc/master/en/option/series/line.md) (the doc source's `master`, **not pinned to a release** — re-check at the version the frontend pins) | **Fixed** as a verified-fact note at §21 item 10 (the `DM-D16` rationale is a decision body: hand-off H2). **Routed** to the frontend sub-spec and `DM-D20`'s fixture suite (a gap case must assert the inserted empty value). Plan 011 builds no frontend (owner standing rule), so nothing in plan 011 changes. `large`/`progressive`/`aria` remain **unverified** |
| DMR-20 | MINOR | `OR-79` M-Q18; plan 011 P11-6.2 | `OR-79` lists PgJDBC's cursor preconditions as autocommit off, `TYPE_FORWARD_ONLY`, single statement. The driver docs list those **and** that cursor fetch is switched on by a **fetch size > 0** (*"setting it back to 0 restores default behavior of caching all rows"*) and the V3 protocol. A streaming export written to the ruling's three conditions alone, with fetch size left at 0, buffers every row — the in-memory failure §8.4 exists to prevent | WebFetch 2026-10-05: [PgJDBC "Issuing a Query and Processing the Result"](https://jdbc.postgresql.org/documentation/query/), §"Getting results based on a cursor" | **Routed** to **P11-6.2** (its 105 M-row streaming test must set and assert a non-zero fetch size). Not an owner question: it implements the ruling, it does not change it. `OR-79` is not edited |
| DMR-21 | MINOR | `design.md:342` (§5.6 example) | `"gapThresholdSeconds": 7200 // 2 × declared interval` beside `bucketSeconds: 3600`: 7200 is 2 × the **bucket**, not 2 × a scrape interval. `UI-D8` defines the threshold on the declared interval; the rule at a rollup tier is unstated | internal | **Routed** to **P11-6.1** (gap derivation, discovery default D-3, **OQ-22**): state the threshold per served tier. Not edited — no ruling settles it |
| DMR-22 | MINOR | `design.md:242-256` (§5.4 capabilities), `:279-298` (§5.5 catalog) | No `collector` block, disk state, or "restart detection: inferred" marker | **M-Q21 `OR-79`** (counters in capabilities' `collector` block and the catalog), **M-Q19 `OR-76`** (disk alarm), **M-Q9 `OR-75`** (inferred marker). Additions, not contradictions; the contract owns the shape | **Routed** to **P11-1.2** (`console.yaml`); per-metric shape is **OQ-17**, the disk measure **OQ-16** |
| DMR-23 | MINOR | `architecture.md:686` (§8.4) | *"A resolution control (`Auto · Raw · Hourly · Daily`)"* vs `SM-D31` / `DM-D11` (`auto` or `raw` only) and the contract (row 58: only `metrics.forceRaw`) | Both sides PROPOSED; no ruling | **Routed** to hand-off H3 (a pointer for whoever next edits §8.4's resolution sentence). Outside this task's "export sentence only" edit |
| DMR-24 | MINOR | `design.md:322`, `:273` (labels selector, `/labels/{labelKey}/values`) | Label filtering is designed as live | Discovery default **D-10** (empty label allowlist) makes v1 labels empty — **not a ruling** | **Routed** to **OQ-22** |
| DMR-34 | MAJOR | `architecture.md:704-706` (§8.5, *"Archives are produced and verified per tenant … Step 2 selects `WHERE tenant_id = $1`"*, and the non-negotiable *"tenant-partitioned"*); plan 011 `MetricsPartitionMain archive` (`011-…md:1304-1305`) | Plan 011 archives a **whole** detached partition with an identifier-only `COPY (SELECT … FROM <detached>) TO STDOUT`, which `OR-79` and `OR-77` describe. §8.5 (`SM-D26`, PROPOSED) requires one archive and manifest **per tenant**. The two cannot both hold with `COPY`: PostgreSQL's `PREPARE` accepts only *"Any `SELECT`, `INSERT`, `UPDATE`, `DELETE`, or `VALUES` statement"*, so `COPY` is not a preparable statement, and the `COPY` page documents no parameter placeholders in its query. A per-tenant `COPY` would need the tenant id **interpolated** into SQL (an `I5` violation). Whether the extended protocol can bind a parameter inside `COPY`'s query is **not stated** on either page — unverified, not assumed | WebFetch 2026-10-05: [PG 14 `PREPARE`](https://www.postgresql.org/docs/14/sql-prepare.html), [PG 14 `COPY`](https://www.postgresql.org/docs/14/sql-copy.html) (also confirms `COPY … TO STDOUT` needs no superuser, as §8.5 says) | **Flagged, routed** as draft **OQ-27** (§4); related to **OQ-11** (archive before drop) and the tenant-erasure open item (`OR-76`). Neither text edited: §8.5 is outside this task's architecture edit, and plan 011's task text is the plan's |

### 1.4 LOCKED decisions and `I1`

| # | Sev | Check | Result | Disposition |
|---|---|---|---|---|
| DMR-25 | MAJOR (flag) | **`B/D5` 🔒** — *"a thin walking skeleton — one generated screen, one graph, one role, one Docker `service` deploy with health + telemetry, accepted end-to-end — must pass before any systemd/K8s/scheduled/File-Mesh work begins"* ([`DECISIONS.md`](../design/DECISIONS.md) row `B/D5`) | `design.md:892-895` makes the skeleton's graph this design's first slice. Plan 010 T6.6 (the first E2E slice) says it **must not claim** *"B/D5's skeleton"* (`010-…md:3327-3329`). Plan 011 MW5 builds **path 2, broker-push ingest**, which the owner described as *"what we have in file mesh"* (`OR-73`), and P11-5.0 reads File Mesh's code. Plan 011 never cites `B/D5`. Whether generic broker-push ingest, built and tested before the skeleton is accepted, is "File-Mesh work" under `B/D5` is **not this review's call**, and `OR-73` does not name `B/D5` (`OR-1`: a LOCKED change names the decision and its scope) | **Flagged, not resolved.** Routed as draft **OQ-26** (§4). P11-5.0 (docs only) is unaffected either way |
| DMR-26 | INFO | **`D14` 🔒** PostgreSQL only | `sm_metrics` is a schema in the same PostgreSQL (`OR-76`) | no change |
| DMR-27 | INFO | **`D7` 🔒** vs `DM-D13` ECharts | Unchanged by the rulings; still flagged in `design.md` §21 and `DM-D13` | no change |
| DMR-28 | INFO | **`D10` 🔒** | `OR-80` amends limitation 4 by `OR-1`'s rule, already registered; the DM text is stale only (DMR-13) | no change beyond DMR-13 |
| DMR-29 | INFO | **`B/D15` 🔒** UTC canonical | `OR-79` (UTC sample midpoint, UTC daily boundary, display zone in the frontend) agrees with `design.md` §18 | no change |
| DMR-30 | INFO | **`I1`** | §3.1/`DM-D3` (the contract binds the data plane) holds and is what M-Q7's `I1` condition requires. M-Q9's direct-mode restart signal reads Cache's `/actuator/metrics/process.start.time` — exposing an actuator endpoint is a `management.*` property in a config file SM writes, which `I1` lists as fair game; it needs no MI code. Nothing in the design asks an MI for a new endpoint or emission | no change |
| DMR-31 | INFO | **`OR-82`** (direct-HTTP polls the target's registered address) | The DM design says nothing about acquisition addresses (§1 puts ingestion out of scope); nothing to correct | no change |
| DMR-32 | INFO | **M-Q5 `OR-74`** (per-version copy) vs §5.5 "declared metadata from `sm_control.solution_package`" | Consistent: authority stays in `sm_control` (`OR-74`); the catalog composes CP package ∥ DP observed (plan 011 P11-7.2) | no change |
| DMR-33 | INFO | **WCAG claims**, `design.md:859` | Verified: SC 1.4.3 *"contrast ratio of at least 4.5:1"* (AA) and SC 1.4.11 *"at least 3:1 against adjacent color(s)"* (AA) — [WCAG 2.1](https://www.w3.org/TR/WCAG21/), WebFetch 2026-10-05 | no change |

---

## 2. What changed in this commit

- [`dynamic-metrics/design.md`](../design/dynamic-metrics/design.md): a dated review pointer under the status line; line 14's
  range → the family prefix (DMR-5); a **supersession map** after the opening caveat, one row per superseded passage; dated
  inline pointers at §2.2 (two rows), §4.2, §4.4, §5.2, §5.5, §5.7, §5.8 (export paragraph, the `unauthorized` row and a note
  under the error table), §8, §14 and §21; the export route's verb (DMR-6); a verified-fact note for ECharts (DMR-19).
  **No sentence of the original was deleted or reworded** except line 14's range and line 412's verb, both visible; three
  table cells (§2.2's two rows, §5.8's `unauthorized` row) gained an appended dated marker, their text otherwise intact.
- [`architecture.md`](../design/architecture.md) §8.4: a dated pointer after the export sentence (DMR-4, naming DMR-34). The
  sentence stays.

## 3. Hand-off to the integrator (not applied here)

`DECISIONS.md`, the `DM-D*` bodies and plan 010 are outside this task's write set.

| Id | Item |
|---|---|
| H1 | **Plan 010, no new wording needed from this review** beyond P11-0.1's hand-off ([`plan-011-mw0-integrator-handoff.md`](plan-011-mw0-integrator-handoff.md) §B). One stale clause to consider: plan 010 T6.4's routed *"drop-counter export needs a metrics stack nobody has chosen"* (`010-…md:3232-3234`, and the out-of-scope row `:4192`). `OR-79` M-Q21 now chooses a durable counter table with no Micrometer registry **for the collector's counters**; the meshbridge publish-drop counter (plan 007 Task 5 step 4a) is still owned by **neither** plan 010 nor plan 011. Whether plan 011 adopts it is the owner's (not raised as an OQ; it is not a monitoring ruling) |
| H2 | **`DM-D*` bodies the rulings superseded** (when the family is next touched, by a superseding row, never an in-place edit, `I3`): `DM-D4` (M-Q20), `DM-D5` (not adopted for v1, M-Q10/M-Q23), `DM-D14`'s `COPY` (M-Q18), `DM-D16`'s "coerce to 0" rationale (DMR-19), `DM-D17` (already superseded by `BE-D6`, and M-Q12), `DM-D18`'s "Adding them to `D10` … must be raised there" (done: `D10-G2` approval, `OR-80`), `DM-D1`'s composition flag (`OR-10`, `OR-78`); `decisions.md:10-11`'s range (historical; cite the prefix if reworded) |
| H3 | **`architecture.md`**, beyond this task's export sentence: §8.3 `:649` histogram row (M-Q6, DMR-7); §8.4 `:686` resolution control (DMR-23) |

## 4. Draft owner questions (for the one sitting after MW0; not decided)

**OQ-26 (draft) — Does LOCKED `B/D5`'s walking-skeleton gate hold plan 011's path-2 (broker-push) wave?**

- **What does not change under any option:** path 2 is ruled (`OR-73`); P11-5.0 (docs only) runs in MW0; `B/D5` is not
  edited.
- **Status quo (measured):** `B/D5` 🔒 bars *"any … File-Mesh work"* until the skeleton (one screen, one graph, one role,
  one Docker deploy with health + telemetry) is accepted end-to-end. Plan 010's first E2E slice explicitly does not claim it.
  Plan 011 does not cite `B/D5`. `OR-73` rules path 2 built in plan 011 without naming `B/D5`.
- **(a)** Read path 2 as **generic** ingest (no File-Mesh-specific code path; behaviour from the package), not "File-Mesh
  work"; MW5 keeps its
  current gates. Delta: none to the plan; the reading is recorded as a ruling.
- **(b)** MW5 waits until the `B/D5` skeleton is accepted end-to-end. Its graph needs plan 011's query routes (MW7) **and** a
  frontend screen, which plan 011 does not build (owner standing rule), so MW5's start would depend on work outside this
  plan. Delta: path 2 lands later, on an outside dependency; nothing else in plan 011 moves (MW5 is off the critical path).
- **(c)** Amend `B/D5`'s sequencing for path 2 by an `OR-1` ruling that names it. Delta: a LOCKED amendment row.
- **Scenario:** a File Mesh deployment publishing heartbeats every 5 s (plan 011 S-E). Under (a) its metrics can be ingested
  as soon as MW5 lands; under (b) not until a Cache graph has been accepted end-to-end; under (c) as (a), with the gate
  formally narrowed.
- **Recommendation:** **(a)**, because plan 011's path 2 is package-driven and carries no File-Mesh-specific code, which is
  what `B/D5`'s ordering protects against — but it is a reading of a LOCKED decision, so only the owner can make it.

**OQ-27 (draft) — Is the monthly archive per tenant (architecture §8.5) or per partition (plan 011 as written)?**

- **What does not change under any option:** archive → verify → drop; `DETACH … CONCURRENTLY`; nothing is dropped without a
  verified manifest; `I5` (no tenant id interpolated into SQL); the operator command `MetricsPartitionMain`.
- **Status quo (measured):** plan 011 writes one identifier-only `COPY` per detached partition (`011-…md:1304-1305`); §8.5
  (`SM-D26`, PROPOSED) says one archive and manifest per tenant, "tenant-partitioned so a restore cannot cross the plane
  boundary". DMR-34: a per-tenant `COPY` cannot carry a bind parameter (not preparable; protocol behaviour unverified).
- **(a)** Per partition, as written. Delta: one file per month holding every tenant; a per-tenant restore or erasure must
  filter the archive later; departs from §8.5's non-negotiable (PROPOSED, so a flag, not a conflict with a LOCKED row).
- **(b)** Per tenant, by a **bound cursor `SELECT … WHERE tenant_id = ?`** over the detached partition, CSV encoded in the
  application — M-Q18's export mechanism reused; no `COPY`. Delta: N tenant scans of a detached (no longer written) table,
  N manifests; keeps §8.5 and `I5`. The tenant list would come from the detached partition itself (`SELECT DISTINCT
  tenant_id`, run by the operator command under the owner credential) — a cross-tenant read, so whether it needs an
  `OR-49`-style ruling is part of this question.
- **(c)** Per tenant with the id interpolated into `COPY`. Rejected on `I5`; listed so it is not reinvented.
- **Scenario:** one month at S-A (about 11–12 GB, `OR-76`) for two organizations; organization B offboards. Under (a) its
  rows sit in a shared archive until a filtering tool exists; under (b) its archive files are separable and deletable.
- **Recommendation:** **(b)** — it keeps §8.5 and `I5`, and makes the tenant-erasure item (`OR-76`) tractable for archived
  months. The owner decides; `OR-77`/`OR-79` are not edited by any option.

## 5. Verification

- Links: every relative link and anchor in the three touched files resolves (python3 check, run before commit).
- `git diff --cached --name-only`: docs only — this file, `dynamic-metrics/design.md`, `architecture.md`.
- `git diff --cached --check`: clean.
