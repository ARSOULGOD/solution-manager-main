# Plan 011 MW0 — integrator hand-off (drafts, not applied)

**Written by:** plan 011 task P11-0.1 ([`011-monitoring-backend.md`](../plans/backend/011-monitoring-backend.md), a DRAFT, not
approved, `OR-91`), 2026-10-05, measured against `bd802fc`.
**Why this file exists:** `docs/design/DECISIONS.md` and every plan 010 file belong to the integrator (plan 010's and plan
011's single-owner tables). P11-0.1 is docs-only and may edit neither, so what the integrator should apply is drafted here.
**APPLIED 2026-10-05 by the integrator on `p011/merge`, after `OR-97` approved plan 011; the per-item status is in the "Integrator disposition" section at the end.** *(The original text below said "Nothing below has been applied" and is kept as the draft record.)* *(Updated 2026-10-05 by plan 011's pre-sitting review: A7, A8, four rows added to B, and section C extended to OQ-29.)* Re-check every number against the merged tree before using it: plan 010 is still adding
rows and ids.

**Next free ids at `bd802fc` (re-check):** `OR-92` (the last registered is `OR-91`), `BE-D34` (`BE-D33` is the last), open item
**41** (the table runs to 40), `REQ-011` stays reserved and unraised.

## A. `DECISIONS.md` — open items (drafts)

The rulings themselves are already registered (`OR-69`, `OR-71`…`OR-81`; `OR-82`…`OR-91` beside them). What follows is only
the open-item text those rulings say they narrow, which the item rows do not yet carry.

**A1. New open item 41 — tenant erasure on demand** (`OR-76`'s "common" list names it; plan 011's S-K and discovery default D-17,
which is not an owner ruling until plan 011's OQ-22 is answered):

> | 41 | **Tenant erasure on demand.** Offboarding one organization needs an erasure keyed by `tenant_id` across `sm_data`,
> `sm_metrics` (series, samples, rollups, counters), `sm_secret` and the control plane's `sm_control` rows. Nothing is deleted when
> a placement or instance retires (their series stay queryable), the DP runtime role has no `DELETE` on raw facts
> (plan 011 P11-2.1's grants; `OR-77` makes destructive partition work an operator command), and no erasure command exists. Raised by plan 011 from `OR-76`'s "tenant erasure by `tenant_id`"
> item. Not v1-blocking; it becomes blocking for any SaaS offboarding or data-subject request. | privacy / offboarding |

**A2. Open item 25, append** (`OR-78`, `OR-79`):

> ✅ **NARROWED 2026-09-30 by `OR-78` and `OR-79`:** the dashboard document shape is adopted from the demo's validated document
> (`OR-78`, M-Q23), and `partial.unavailable[]` is ruled (`OR-79`, M-Q17: authorized-but-unreachable instances only). The other
> members of this list are not touched by those rulings.

**A3. Open item 27, append to (b)** (`OR-79`, M-Q24 (11)):

> ⚠️ **Settled for STORED data by `OR-79` (flagged):** the daily-tier day boundary is UTC. The display timezone remains the
> frontend's, so (b) stays open for display.

**A4. Open item 28, append** (`OR-79`, M-Q22 and M-Q24):

> ⚠️ **NARROWED, not closed, by `OR-79`:** the query API is limited to 10 requests per second per session; CSV export is audited as a
> HIGH-consequence action, limited to 1 per organization and 2 global at once and to 20 M rows (a typed `422` "narrow the range"),
> and query plus export share a connection budget of 4 that is separate from apply and health. The CSP, clickjacking, on-device
> draft-persistence, permission-enumeration and two-tab concerns are untouched. The storage gate re-checks the export limits on
> the EC2 host.

**A5. Open item 3, only if plan 011's OQ-22 accepts discovery default D-8** (delete the orphan
`backend/src/main/resources/db/migration/V001__telemetry_schema.sql`, salvage its constraint cases): narrow the item to the
**events** DDL; the metrics and rollup DDL is plan 011 P11-2.1 (`sm_metrics`, its own Flyway history, so no collision with
`sm_data` V001–V006). If OQ-22 does not accept D-8, leave item 3 untouched.

**A6. Already done, so not drafted** (do not re-raise): the M-Q7 flag against `dynamic-metrics/design.md:75,158` is in `OR-75`'s
right-hand column; `OR-75` records that `SM-D30` guard 2 fires only where a restart signal exists, and omits the ruling's
*"reuse health inspect when fresh"* parenthetical, so there is nothing to reconcile in the registered text (plan 011 keeps the
measured fact that there is no health `docker inspect` to reuse: `010-…md:3767`); plan 010 already carries the `OR-72` wording
(`010-…md:59-68`, `:3719`) and `TenantEnumerationRepository`'s javadoc (`:69-83`) already names plan 011's `MetricsCollector`;
`REQ-010` and its `README.md` and `traceability.md` rows exist; the `D10` approval's pointer for `OR-80` exists
(`D10-G2-APPROVAL-2026-09-15.md:266`).

**A7. An `I3` clarifying row for `OR-74` (added by the pre-sitting review, 2026-10-05).** `OR-74` (registered) says *"The instance
carries `declaredPackageVersion`, the acquisition mode and the direct host and port"*. `OR-82` (2026-10-05) removed every address
from the instance, and names only `OR-71` as amended, so `OR-74`'s sentence is contradicted without being flagged. Plan 011
follows `OR-82` throughout (no `direct_host`; `DirectHttpFetcher` reads `target_connection.host` fresh each round; P11-4.4). Draft,
a **new** row (`OR-74` stays unedited):

> **OR-9x** | ✅ **Clarifies `OR-74` against `OR-82` (no new ruling).** `OR-74`'s clause *"and the direct host and port"* of the
> instance is superseded by `OR-82`: the instance carries `declaredPackageVersion` and the acquisition mode only. In `direct-http`
> mode the DP polls the **target's registered address** (`sm_data.target_connection.host`, read fresh each round) at the **host
> port** `placement_metrics_endpoint` maps for the package's source port (`OR-74`, M-Q14), so `metricsPorts` carries no host. The rest of
> `OR-74` is unchanged. *Recorded by the integrator from plan 011's pre-sitting review; the owner's words are `OR-82`'s.*

**A8. `OR-91`'s own list.** The registered `OR-91` row lists the remaining owner questions as OQ-1…OQ-18 and OQ-20…OQ-22. The sitting now
covers **OQ-1…OQ-18 and OQ-20…OQ-29** (OQ-23…OQ-25 from the refresh, OQ-26 and OQ-27 from P11-0.2, OQ-28 and OQ-29 from the
pre-sitting review). `OR-91` needs no edit (`I3`); plan 011's text says so, and the post-sitting rows (Step 4b) name each question.

## B. Plan 010 wording plan 011 needs changed (plan 010 is read-only for P11-0.1)

| Plan 010 location (`bd802fc`) | Reads | Should read |
|---|---|---|
| `010-…md:42` (Spec line) | `[plan 009](009-console-api-and-monitoring-replacement.md)` | add `[plan 011](011-monitoring-backend.md)` (plan 009 is superseded in part, `OR-78`, `OR-80`) |
| `:164` | *"`demo-launcher` is deleted by plan 009, not here"* | *"… by plan 011 (P11-9.1), not here"* |
| `:4004` (T10.5) | *"plan 009's `console-api` may later absorb it — OR-10"* | `OR-78` hosts the routes in `console-gateway`; there is no `console-api` module to absorb it |
| `:4122` (T12.1 Step 2) | *"Metrics/dashboards/Explore are excluded (plan 009's)"* | *"… (plan 011's)"* |
| `:4193` (non-goals row) | *"Metrics, dashboards, Explore, `console-api`, deleting `demo-launcher`/`demo_control` \| plan 009"* | *"\| plan 011"* (and drop `console-api`) |
| `:159` | `demo-launcher` is a separate reactor module (`backend/pom.xml:73`) | `backend/pom.xml:85` |
| `V007__instance_inventory.sql:113-118` and plan 010 T2.1 | the `target_connection_host_once` index reads install-wide, **while `OR-89` rules it per organization** | the lane implementing `OR-89` is pending; plan 011's OQ-23 asks whether anything guards a host port across organizations that share one machine (also true of `placement_health_port_once`, `V007…sql:178`) |
| `docs/runbooks/operations.md` (on `p010/merge3`, `28c035f`): the orphaned-governance-node procedure, **step 4** (*"Check nothing was granted on the orphan's scope"*) and step 5 (the delete) | Checks role bindings only | Also check `sm_control.dashboard` for the orphan's instance id (plan 011 P11-2.3's `ON DELETE RESTRICT` keys would fail the step 5 delete with `23503`); plan 011 also refuses dashboard writes for an instance the DP does not report active (P11-7.2), so this is the belt to that brace |
| Plan 010's dispatch of **T10.3** (D-ACCEPT, docs only, W10, no stated dependency) | Scheduled in W10 beside T7.1/T7.2 | If the owner rules plan 011's OQ-28 (b), dispatching T10.3 **early** (it is docs only) is what lets P11-1.2 open before S7. If T10.3 needs no `console.yaml` change, record that in its Results so P11-1.2 may proceed |
| Plan 010's own migration tasks (T6.8 `V011`, T7.2 `V012`, T9.3 `V013`, T11.2 `V014`) and its launcher ITs | They assume `SmDataBootstrapIT`'s and `ApplyPersistenceConfigurationIT`'s exact sets move only for their own migration | **Only if OQ-28 (b) is ruled:** from P11-2.1's merge, `SmDataMigrationMain.runAll` also migrates `sm_metrics`, so each later plan 010 task rebases its `SmDataBootstrapIT` hunk onto P11-2.1's, and every launcher IT runs the `sm_metrics` readiness check. From P11-1.1's merge the Cache fixture carries `restartSignal`, `queryParams` and `defaultChart` (additive) |
| `docs/design/common/contracts/console-integration.md:789` (the §11.3 header: *"matrix written, NOT executed"*) | Stale: the spike ran (round 4, 2026-09-30) | A dated pointer to `docs/design/backend/spike-scope-acl-result.md`, which itself says *"Nothing here declares item 32, 33 or 35 closed, and `BE-D5` is not ratified"*. Not a plan 011 duty (single-owner), raised because plan 011's OQ-14 cites it |

## C. Owner questions added to plan 011 after its first draft (for the one sitting after MW0)

Each is a full row in plan 011's [owner questions](../plans/backend/011-monitoring-backend.md#owner-questions--open-for-one-sitting-before-mw1)
(status quo, options, delta, recommendation). None is decided. OQ-19 is ruled (`OR-91`).

- **OQ-23** — whether anything stops two organizations (`OR-89`) from naming the same metrics or health host port on one machine.
- **OQ-24** — whether `port` may be made required on an `http_json` package source, given that the frontend compiles the root
  schema and has two fixtures without a `port` (owner standing rule: no frontend change).
- **OQ-25** — whether `OR-70`'s review cadence (named for plan 010) applies to plan 011.
- **OQ-26** — whether LOCKED `B/D5`'s walking-skeleton gate bars path 2 (drafted by P11-0.2, DMR-25).
- **OQ-27** — whether the monthly archive is per tenant (`architecture.md` §8.5) or per partition (drafted by P11-0.2, DMR-34).
- **OQ-28** — whether six tasks that touch no unpushed plan 010 file may start after the sitting instead of after plan 010 S6/S7 and
  W11 (raised by the pre-sitting review; it needs a ruling because `OR-91` covered MW0 only).
- **OQ-29** — which metric v1 path 2 must serve, given that File Mesh's heartbeat carries no number (raised from P11-5.0's facts).
- **Before the sitting:** MF26 (Solace topic matching case sensitivity) should be measured on the local spike broker; OQ-5 is not ruled
  without it.

## D. Post-sitting records (appended by P11-0.1 Step 4b)

*Empty until the owner sitting has been held.* It will carry the clarifying row for `OR-77` (the runway function's schema and
grammar, from OQ-21), one draft row per answered owner question, and the OQ-22 answer.

## E. Integrator disposition (2026-10-05, `p011/merge`)

| Item | Disposition |
|---|---|
| A1 (open item 41, tenant erasure) | **Done.** Row 41 added to `DECISIONS.md` open items (D-17 accepted at `OR-92`/OQ-22, noted as a starting point, not an erasure design). |
| A2, A3, A4 (open items 25, 27, 28) | **Done.** Appended to each row. |
| A5 (open item 3, the orphan `V001`) | **Done**, because OQ-22 accepted D-8 (`OR-92`) and P11-2.1 deleted the orphan. Item 3 narrowed to the `events_raw` DDL and the `SM-D8` sizing model; `architecture.md` §8.2's two "Source of truth" lines now point at `sm_metrics` `V001__metrics_store.sql` and `SmMetricsSchemaIT`. The deleted file is still named in historical evidence (`docs/design/backend/agent-gateway/evidence/`, md5-pinned runs, plans 001 and 004, `dm-design-review-2026-10.md`); those are records of a past state and are left. |
| A6 | Nothing to do, as written. |
| A7 (`OR-74` against `OR-82`) | **Done**: registered as **`OR-98`**. |
| A8 (`OR-91`'s own list) | **No edit** (`I3`); `OR-92` covers the sitting. |
| B, plan 010 `:42`, `:164`, `:4004`, `:4122`, `:4193`, `:159` | **Done** (line numbers had moved: `:42`, `:159`, `:164`, `:4037`, `:4155`, `:4226`). |
| B, `V007` index (`OR-89`) | **Already applied** in the merged tree (plan 010 `T2.1` Results and the `OR-89` note). Added the `OR-92` OQ-23 pointer: nothing in the database guards a host port across organizations that share one machine; the rule is a runbook line. `V007` itself is a pushed migration and is **not touched**. |
| B, `operations.md` orphan procedure | **Done**: a dashboard check added to step 4 (the file is on this branch; the hand-off named `p010/merge3`). |
| B, T10.3 dispatch | **Done** as a note on T10.3 (`OR-93`: P11-1.2 waits for it, docs only, may go ahead of W10). |
| B, the plan 010 migration tasks and launcher ITs | **Done** as a note beside the critical path (`OR-93`: `sm_metrics` joins `runAll`; the Cache fixture gains three additive keywords from P11-1.1). |
| B, `console-integration.md` §11.3 header | **Done** as a dated pointer to `spike-scope-acl-result.md`; the matrix text is untouched. |
| C (OQ-23…OQ-29) | Ruled: `OR-92` (OQ-23, 24, 25, 27), `OR-93` (OQ-28), `OR-94` (OQ-26), `OR-95` (OQ-14). OQ-29 stays open with OQ-3, OQ-5 and OQ-20. |
| D (post-sitting records) | **Done**: the `OR-77` clarifying row is **`OR-99`** (cites `OR-92`/OQ-21); per-answer rows are `OR-92`…`OR-95` themselves; the OQ-22 answer is in `OR-92` (D-2…D-17 accepted). |
