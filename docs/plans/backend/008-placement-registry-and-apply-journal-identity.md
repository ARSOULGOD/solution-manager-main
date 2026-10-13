# Backend Plan 008 — multi-placement instances, and a placement-aware apply journal

> **Status: APPROVED (`OR-121`, 2026-10-07).** The owner approved this plan for execution under `OR-70` and ruled
> every open question: none of the seven judgement questions `OR-121` names is one of plan 008's, so every OQ-8.\*
> takes this draft's recommendation (*"Let's start plan-008 for docker implementation in parallel and close plan-12,
> plan013"*). One cross-plan ruling changes this plan's content: plan 012's `OPS-Q12` R-1 rules placement retirement
> **before S3 starts**, so, as OQ-8.4 (b) said, **S3 builds `retirePlacement`** — and, because retirement makes R6's
> stop defect reachable, **S3 also takes S4's live-placement enumerator and fence** (S4 keeps V022, the fixture move
> and the reconciliation record). The execution order, waves, single-owner files and migration numbers are in the
> [combined schedule](combined-008-012-013.md), which controls where it differs from the [Dispatch map](#dispatch-map).
> Refreshed 2026-10-07 against `integration/dp-remote-demo` @ `36af1df6` under `OR-114` item (1); the rewritten plan is
> the part above [History](#history--the-draft-as-written-2026-09-15-with-its-2026-10-05-notes), and where they differ,
> the refreshed text controls. Revised the same day after an independent review (P008-01..P008-17,
> [Review dispositions](#review-dispositions-2026-10-07)).
>
> **Closing status (wave 6, 2026-10-08): S1 to S5 are BUILT and MERGED (waves 1 to 5) and their tests ran on `postgres:14`
> in the integration builds; the combined Fable 5.1 review is OWED. The live gate L2 PASSED on 2026-10-09 (see below).** The completion record is
> [Results](#results--the-closing-record-wave-6-2026-10-08). The per-slice Results below are kept as written on the day
> each slice landed; where one says a Docker-gated test was "not run" or "owed", the closing Results says whether it has
> since run, and its owed list is the current one.
>
> *(2026-10-09 note: live gate L2 PASSED on its second attempt; the first found a defect, fixed in `7ff85a42`. See the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09).)*

> **Rulings applied (`OR-121`, 2026-10-07).** Each answer is the draft's recommendation unless marked.
>
> | Question | Ruled answer |
> |---|---|
> | OQ-8.1 multi-placement apply | **(b)** — a rollout across placements is a fleet of placement-scoped applies; no instance-wide apply (`BE-D45`) |
> | OQ-8.2 the instance-wide stop over same-named containers | **(a)** — the journal becomes placement-aware (S2, `BE-D44`) |
> | OQ-8.3 / OQ-8.3s adding a placement | **(a)** `addPlacement`, with `instance.bindTarget` checked **(i) at the instance** (S3, `BE-D47`) |
> | OQ-8.4 removing one placement | **built in S3 under plan 012's `OPS-Q12` R-1** (ruled before S3 starts, as this question's recommendation provided): `instance.bindTarget` at the instance, `?workload=leave-running`, never touches the host (`BE-D53`) |
> | OQ-8.5 referential guard for `apply_request.placement_id` | **(a)** — build it (S4, `BE-D48`) |
> | OQ-8.6 `agent_replica` vs `placement` | **(b)** — record, do not decide (`RECONCILIATION-008.md`, S4); an input to the agent-gateway revisit that starts 2026-10-08 |
> | OQ-8.7 one-placement operations that mint a generation | **(a)** — keep as built, and document it |
> | OQ-8.8 placement quota | **(a)** — a placement does not count against the instance quota |
> | OQ-8.9 push metrics with two placements | **(a)** — `addPlacement` refuses a package that declares push metrics |
> | OQ-8.10 the state a new placement starts in | **(a)** — leave it unconverged; `addPlacement` re-settles the ladder |
> | OQ-8.11 config uniformity | **(a)** — record the limit and give the `manual`-fleet recipe |
> | Cross-plan (plan 012 `OPS-Q8`) | K-2 and (x): no `rolling-*` fleet batch holds two placements of one instance; `rolling-no-validation` waits a settle delay between batches (`BE-D50`) |

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan slice by slice. Steps use checkbox (`- [ ]`) syntax. **Read the
> [Dispatch map](#dispatch-map) before starting.** It says which slices may run in parallel worktrees.

| | |
|---|---|
| **Component** | backend (`dp-api`, `dp-core`, `cp-api`, `console-gateway`, `launcher`, `arch-tests`) + `contracts/openapi/` + docs. **No frontend change.** |
| **Authority** | The project owner, ruling for the architect group (`OR-69`). Binding here: `OR-16`, `OR-18`, `OR-27`, `OR-29`, `OR-37`, `OR-40`, `OR-42`, `OR-47`, `OR-53`, `OR-60`, `OR-61`, `OR-70`, `OR-71`, `OR-92`, `OR-107`, `OR-110`, `OR-111`, `OR-114`, `OR-115`, `OR-119`. |
| **Requirements** | [`REQ-005`](../../requirements/REQ-005-three-runtimes.md) (multi-site, multi-runtime instances) · [`REQ-001`](../../requirements/REQ-001-unified-config-layer.md) · [`REQ-013`](../../requirements/REQ-013-fleet-apply.md) (the fleet this plan reuses) · [`REQ-009`](../../requirements/REQ-009-no-mi-development.md) / `I1` |
| **PROPOSED decisions relied on** | `BE-D13`, `BE-D19`, `BE-D23`, `BE-D26` (the journal and stop), `fleet-apply.md`'s design. Each is used as built and measured; ratification decides whether they stay. |
| **Sibling drafts (same owner sitting)** | Plan 012 (`d/ops501`, the `501` operations), plan 013 (`d/regapi`, registration API), plan 014 (`d/vm`, the plain-VM adapter). Interactions: [OQ-8.1's cross-plan section](#cross-plan-effects-of-oq-81-rule-it-before-plan-012s-ops-q6ops-q7ops-q8), OQ-8.11, R12. |
| **Tech stack** (unchanged since plan 011's measurement) | Java 17 · Spring Boot 3.5.16 · Flyway 11.7.2 · PostgreSQL 14 · Testcontainers 1.21.4 · ArchUnit 1.5.0 · sshj 0.40.0 |
| **Migrations** | **Allocated by the [combined schedule](combined-008-012-013.md#5-migration-numbers):** S2's journal migration is **`sm_data` V021**, S4's referential guard is **`sm_data` V024** (V022 and V023 go to plans 013 and 012 in wave 2). No `sm_metrics` or `sm_control` migration. **One more `sm_data` migration was added after approval: V026** (`placement.created_by` / `retired_by`, `OR-125` (1), wave 5; see [Results](#results--the-closing-record-wave-6-2026-10-08)). Published migrations are frozen (`OR-22`, `OR-105` (e)): nothing below edits V001–V020. The rule is *next free number at merge*: if the merge order changes, the integrator renumbers. Wherever the text below still says V022 for the guard, read V024. |

---

## Refresh 2026-10-07 — what changed and what did not

This revision re-measured every claim of the 2026-09-15 draft (and its three 2026-10-05 notes) against `36af1df6`.
**It is not a review, and nothing here is approved.** A "measured" entry names a file:line; nothing was recalled.

| # | The draft claimed | Measured at `36af1df6` | Consequence for this plan |
|---|---|---|---|
| R1 | Task 1 builds `sm_data.placement` | **Built by plan 010 T2.1** in `V007__instance_inventory.sql:124-194`: immutable except retirement (trigger `:184-194`), `target_id` FK (`:170`), `placement_not_sentinel` CHECK (`:126-127`), live-only per-target uniqueness of container, config directory and health port (`:174-180`), the target-envelope trigger (`:201-226`). `V016` adds `UNIQUE (tenant_id, placement_id, target_id)` (`V016…sql:75`) | Task 1 is **deleted**. Nothing in this plan creates a registry |
| R2 | An instance has placements to fan out over | **It has exactly one.** `declareInstance` takes one `placement` object (`console.yaml:505-583`); `InventoryService.reserve` inserts one row (`InventoryService.java:102-118`); there is **no operation that adds a placement to an existing instance**, and no re-target operation (re-target exists only as a schema test, `InventorySchemaIT.java:256-269`). The schema itself allows several live placements per instance (no per-instance unique index in `V007`) | **The first missing piece is a way to add a placement**, not the fan-out. New slice S3 ([OQ-8.3](#owner-questions--open)) |
| R3 | Multi-placement needs an instance-wide apply (one `apply_id`) | **The API cannot express one, by ruling:** `OR-53` "`placementId` is required on apply"; `submitApply` refuses an unknown or retired placement (`ApplyServiceImpl.java:95-106`, `requireLivePlacement` `:241-245`). **A rollout across several placements already exists:** a fleet's member is `(instance, placement)` (`FleetService.java:181-190`, `V014__fleet_action.sql:74` `fleet_member_named_once`), so two placements of one instance are two members, each its own ordinary placement-scoped request with its own journal, batched with `batchSize`/`onFailure` | The central question is now **whether to build the instance-wide fan-out at all** ([OQ-8.1](#owner-questions--open)). The recommended answer reuses the fleet and changes no apply path |
| R4 | `BE-D23`'s two clauses bite on a multi-placement apply | **Re-measured, both still hold in the DDL:** `UNIQUE (tenant_id, apply_id, step, replica_ref)` and `placement_scoped_steps` (`V004__apply_step_intent.sql:17,22-23`); the idempotency key is `applyId:step:replica` (`ApplyExecutor.java:1535-1537`), so it collides too. Pinned by `InstanceWideIdentityLimitIT` (`:64`, `:83`, `:108`). The engine refuses an instance-wide apply before admission (`ApplyExecutor.java:208-212`) and the worker's claim skips one (`ApplyClaimRepository.java:92`) | Unchanged for applies. **But clause 2 now bites a served operation** — see R5 |
| R5 | (not in the draft) | **The instance-wide stop is served** (`stopInstance` with no `placementId`, `ApplyServiceImpl.java:182-190`, `console.yaml:1212-1240`) and journals one `STOP` intent per replica under one `apply_id` (`ApplyExecutor.java:312-372`). The SSH-Docker adapter's `replica_ref` is the container name (`SshDockerRuntimeAdapter.java:309`), and container names are unique **per target** only (`V007…sql:174-175`). So two placements of one instance on two targets may both be `sm-cache`, and the instance-wide stop then lands `STOP_FAILED` / `stop.replica.label.collision` **having stopped nothing** (`ApplyExecutor.java:358-363`) | Once R2's gap is closed, the emergency lever fails for a legal configuration. This is what the journal identity change ([OQ-8.2](#owner-questions--open)) is now for |
| R6 | Task 5: retire the stopgap enumerator `placementsOf` | **Still present, in two places:** `ApplyRequestRepository.placementsOf` (`:169-176`, `SELECT DISTINCT placement_id FROM apply_request`) and its delegate `AdmissionService.placementsOf` (`:83-85`), called by the instance-wide stop (`ApplyExecutor.java:313-315`). **New defect found by the measurement:** history includes retired placements; `TablePlacementCatalog.lookup` refuses a retired one (`placement.unresolvable`, `TablePlacementCatalog.java:49-51`); the stop records the first failure (`ApplyExecutor.java:351-355`) and lands `STOP_FAILED` although every live placement was stopped. Unreachable today only because nothing retires a placement of an active instance | Kept and sharpened (S4): the stop enumerates `PlacementRows.live` (`JdbcPlacementRepository.java:102-105`). `console.yaml`'s stop text ("every placement the instance has a history with", `:1222-1223`, and `stop.no.known.placement`'s "no placement history", `:1236`) changes with it |
| R7 | Task 3: seed the nil UUID as a reserved `placement` row | **Not buildable as written:** `placement` has `placement_not_sentinel` (`V007…sql:126-127`), and `instance_id`/`target_id` are `NOT NULL` with FKs to `instance_state` and `target_connection` (`:169-170`), so a sentinel row would need a fake instance and target per tenant. `apply_request.placement_id` still has no referential guard (`V002__apply_request.sql:11-14`); the service checks it (`requireLivePlacement`; fleet `PLACEMENT_NOT_LIVE`, `FleetService.java:209-211`) and the catalog refuses it at execution | Re-shaped as [OQ-8.5](#owner-questions--open), with mechanisms to measure first (MF-3, MF-4, MF-5) |
| R8 | Task 6: create `dp-api/.../PlacementView.java` and three ArchUnit rules | **`PlacementView` exists** at `dp-api/.../api/inventory/PlacementView.java` (plan 010). `jdbcIsConfinedToPersistencePackages` (`PlaneBoundaryTest.java:129-134`) already keeps JDBC, Flyway and Hikari out of every package but the two persistence packages, `dp-api` included, with a planted-violation proof (`:366-391`). There is still **no `agent-gateway` module** and no `.proto` | Task 6 shrinks to one new rule and the reconciliation record (S4) |
| R9 | D3 — cross-scope supersession must be decided before Task 4 | Under OQ-8.1 (b) or (c) **neither case of `eda-walking-skeleton.md` §8 row 14 can arise:** an instance-wide `STAGED` row needs an instance-wide apply, which is refused before admission (R4), and a stop never lands `STAGED` (`state_fits_operation`, `V002…sql:49-52`). Exact-scope supersession (`AdmissionService.java:174-178`) stays, and `MonotonicityIT`'s pin (`:124-130`) stays green | D3 is **moot unless OQ-8.1 (a) is chosen**, in which case it becomes a blocking owner question |
| R10 | Per-placement reads must be built | **Already per placement** (plans 010/011): health (`placement_health`, `V012…sql:67-80`; `getHealth`, inventory row 138); start observation and the ungated `restart-observed` route (`V018…sql:21-34`, `OR-112` (1)); the ladder is the **lowest rung across live placements** (`ConfigLadder.java:74-75,154-170`, `OR-37`); **pull-path** metrics series identity carries `placement_id` (`sm_metrics V001…sql:17-21`, `OR-75`); `rollbackApply`, `restoreRuntimeConfig`, `acceptRuntimeConfig` and `setLogLevel` require a `placementId` (`OR-60`, `ApplyServiceImpl.java:113,139,161`); events are placement-scoped (`ApplyEventEmitter.java:96-121`, `OR-4`); a second placement on one target needs distinct health and metrics ports (`V007…sql:179-180`, `V016…sql:86-123`, `OR-110`). ⚠️ **Push-path (broker, path 2) metrics are not per placement:** `PushAttribution` attributes an event by the instance key in its payload; with more than one live placement the event is `ambiguous_placement` and dropped — "No field in the event names a placement (facts F20)" (`PushAttribution.java` class javadoc; `OR-107` OQ-5/OQ-20, `OR-92`). The key is read from the instance's draft values (`PushSubscriptionPlan.java:116-117`), so every placement carries the same one, and `I1` forbids adding a placement field to the MI's events | **No read path is rebuilt.** S5 proves each per-placement read with two live placements. **The exception is push metrics:** a second placement silently stops all push collection for a path-2 instance (File Mesh). That is [OQ-8.9](#oq-89--push-path-metrics-once-an-instance-has-two-placements) |
| R11 | Agent Gateway "designed, not implemented" | Still true: no `agent-gateway` module in `backend/pom.xml`; `docs/design/backend/agent-gateway/` is PROPOSED; `OR-115` puts its revisit after the PR into `dev` | The five statements in the history's Prerequisite 3 stand unchanged |
| R12 | Migrations `V015`…`V019` | Taken: `V015`–`V020` exist (`ls …/sm_data/`). ⚠️ **Plan 013** (`d/regapi`, `013-registration-api.md:16,112`) also reserves `sm_data` **V021**, measured at the same base | This plan's migrations are **V021** (S2) and, if OQ-8.5 (a), **V022** (S4). **Merge order** (`OR-111`/`OR-114`): plan 008 takes V021/V022 first; plan 012 has no migration in its draft; plan 013 renumbers to the next free number after both. Nobody edits a published file (`OR-22`) |
| R13 | `stop` has no API surface in this plan | `stopInstance` is served (`OR-18`, plan 007 Task 6b / plan 010) | This plan changes the stop's *enumeration and journal*, not its surface |

**What did not change.** `BE-D23`'s measurement, `BE-D19`'s `replica_ref` grammar and the rule that no plan encodes a
placement into `replica_ref`; `I5`/`I7` constraints; the Agent Gateway non-goals; `OR-16` (placement is not a grant level).

### Measured facts still to measure (before the task that needs them)

| # | Fact | How | Needed by |
|---|---|---|---|
| MF-1 | On the owner's `OR-118` database when S2 runs: the row count of `apply_step_intent`; the count of intents under an instance-wide (nil) stop; and **the count of non-terminal `apply_request` rows, by operation and by nil vs real placement** (V021 refuses an in-flight instance-wide stop, S2 Step 3) | `SELECT count(*) …` as the owner role | S2 Step 3 (lock class, backfill note, the refusal) |
| MF-2 | That the migration owner can `UPDATE apply_step_intent`, and that V021 touches no `apply_request` row. The table's immutability is DCL only (the runtime role has `SELECT, INSERT`, `V004…sql:26`); **no trigger guards `apply_step_intent`** — V006's `forbid_terminal_regression` is a `BEFORE UPDATE` trigger on `apply_request` (`V006…sql:25-26`) | Testcontainers, PG 14 | S2 Step 3 |
| MF-3 | Whether PostgreSQL 14 accepts a **foreign key whose referencing column is a `STORED` generated column** (`NULLIF(placement_id, nil)`). ⚠️ **Not documented either way:** the PG 14 generated-columns page lists no FK restriction and the `CREATE TABLE` page says nothing on it (WebFetched 2026-10-07: `postgresql.org/docs/14/ddl-generated-columns.html`, `…/sql-createtable.html`). What *is* documented (same page): *"`MATCH SIMPLE` allows any of the foreign key columns to be null; if any of them are null, the row is not required to have a match in the referenced table."* | Testcontainers, PG 14, a refusal test naming the constraint | S4 (only if OQ-8.5 (a)) |
| MF-4 | On the owner's `OR-118` database: how many `apply_request` rows name a real placement (nil excluded) whose `(tenant_id, instance_id, placement_id)` has **no** `placement` row. Expected 0, because V008 forced a recreate and every later submit path checks a live placement (R7). Expected is not measured | `SELECT count(*) FROM sm_data.apply_request r WHERE r.placement_id <> '00000000-…' AND NOT EXISTS (SELECT 1 FROM sm_data.placement p WHERE (p.tenant_id, p.instance_id, p.placement_id) = (r.tenant_id, r.instance_id, r.placement_id))` as the owner role | S4: V022's shape (below) |
| MF-5 | Whether `ADD COLUMN … GENERATED ALWAYS AS (…) STORED` rewrites `apply_request` on PG 14. **Documented** (`ALTER TABLE`, PG 14, WebFetched 2026-10-07, `postgresql.org/docs/14/sql-altertable.html`): *"An `ACCESS EXCLUSIVE` lock is acquired unless explicitly noted"*; *"`ADD FOREIGN KEY` requires only a `SHARE ROW EXCLUSIVE` lock … also … on the referenced table"*; `VALIDATE CONSTRAINT` *"acquires a `SHARE UPDATE EXCLUSIVE` lock"*; with `NOT VALID` *"this potentially-lengthy scan is skipped. The constraint will still be enforced against subsequent inserts or updates"*. **Not documented on that page:** whether adding a stored generated column rewrites the table | Testcontainers, PG 14: `pg_relation_filenode('sm_data.apply_request')` before and after | S4: V022's stated lock class |

---

## Owner questions — open

✅ **All ruled 2026-10-07 by `OR-121`** — see *Rulings applied* at the top. The questions are kept as written, so the
reasoning behind each answer stays readable.

⚠️ Each question states what does **not** change across its options, the status quo, the concrete delta per option, and
the same real scenarios through each. The scenarios, used throughout:

- **S-a (rollout).** A Cache instance has placement **P1** (target T1, container `sm-cache`) and **P2** (target T2,
  container `sm-cache`). The operator staged generation 7 and wants it on both, one placement at a time, halting if P1's
  health gate fails. (Cache's probe is liveness-only, `OR-53`, open item 15. The gate says "the process is up", not
  "it serves".)
- **S-b (partial failure).** In S-a, P1 applies; P2's host is unreachable at materialize.
- **S-c (rollback one).** The operator rolls P1 back to generation 5.
- **S-d (emergency).** The operator stops the whole instance.
- **S-e (push).** A File Mesh instance **F** (path-2 metrics, `solace_event` sources, `OR-107`) has placement P1; the
  operator adds P2. Used by OQ-8.9 and stated "unaffected" elsewhere where it is.

**Recommended order of ruling:** OQ-8.1 first, because plan 012's `OPS-Q6`/`OPS-Q7`/`OPS-Q8` presuppose its answer
(see its cross-plan section). Then OQ-8.2 to OQ-8.11.

### OQ-8.1 — What is a "multi-placement apply"?

**Does not change under any option:** `config_generation` is per instance, so all placements receive the same desired
configuration; health, the ladder (lowest across placements, `OR-37`), events, rollback, accept-runtime and pull-path
metrics are already per placement (R10); authorization is at the instance (`OR-16`); the fence is per placement
watermark. Push metrics are OQ-8.9 under every option.

**Status quo:** an apply names one placement (`OR-53`). A rollout across placements is expressible today as a fleet whose
members are the instance's placements (R3) — but no instance has two placements (R2).

| | (a) One instance-wide request, fanned out (the 2026-09-15 draft) | (b) A rollout across placements is a fleet of placement-scoped applies (as built) | (c) (b), plus a one-call "apply to every live placement" operation |
|---|---|---|---|
| **Delta** | `OR-53` amended (`placementId` optional); journal identity (S2) **plus** executor fan-out, ordered, with per-placement terminality and a **derived** request state; resume per placement; the claim filter and `resumeRunning`'s skips removed (`ApplyClaimRepository.java:92`, `ApplyExecutor.java:1271-1298`); `OR-4`'s events need a placement per notice, so an instance-wide request emits per-placement notices from one `applyId` (an `OR-4` amendment); `observeApply` reports one request with several placements; `OR-42`'s rollback eligibility reads per-placement evidence out of instance-wide journals; the reconciler (`observeHost` refuses instance-wide, `ApplyExecutor.java:940`) per placement; **D3 must be ruled** (R9) | **No apply-path change.** Documented in `console.yaml` (`submitFleetApply` description) and the Postman README: "to roll a generation across an instance's placements, submit a fleet whose members are those placements". `REQ-013`'s fleet already gives batches, `halt-remaining`, per-member journals and one observation | (b), plus `POST /dp/instances/{instanceId}/rollouts` that builds the fleet server-side from the **live** placements and returns `FleetAccepted`; observed with `observeFleetApply`. Authorized as the fleet is: `x-sm-permission: config.apply` **and `x-sm-permission-also: [instance.restart]` when the strategy restarts** (as `submitFleetApply`, `console.yaml:1284-1300`), both at the instance. One new operation, one handler, one DP method; no engine change |
| **S-a** | One request; executor walks P1 then P2; one `applyId` | Fleet `{batchSize: 1, onFailure: halt-remaining, members: [(i,P1,7),(i,P2,7)]}`; two `applyId`s, one `fleetId` | One call `{generation: 7, batchSize: 1, onFailure: halt-remaining}`; same fleet underneath |
| **S-b** | Request state derived from P1 `APPLIED` + P2 `FAILED_NO_EFFECT` by a rule that must be invented and ruled | P1 `APPLIED`, P2 `FAILED_NO_EFFECT`; fleet `COMPLETED_WITH_FAILURES` (`OR-108` (4)); ladder stays at the lower rung (P2) | as (b) |
| **S-c** | Rollback must find "generations written to P1" inside instance-wide journals | As built: `rollbackApply {placementId: P1, generation: 5}` copies forward to a new generation; P2 is then behind the newest generation and the ladder says so (see OQ-8.7) | as (b) |
| **S-d** | unaffected (stop is OQ-8.2) | unaffected | unaffected |
| **S-e** | unaffected (OQ-8.9) | unaffected | unaffected |
| **Cost** | ≈ +2.5 slice-days over (b), and two ruling amendments (`OR-53`, `OR-4`) plus D3 | 0 code; docs and two ITs in S5 | ≈ +0.5 slice-day over (b) |

**Recommendation: (b).** Everything (a) would add is already built per placement, and (a) re-opens three ruled shapes
(`OR-53`, `OR-4`, `OR-42`) to buy one `applyId` instead of one `fleetId`. (c) is purely additive and can be added later
without changing anything (b) builds; take it now only if the frontend wants one call.

#### Cross-plan effects of OQ-8.1 (rule it before plan 012's OPS-Q6/OPS-Q7/OPS-Q8)

Measured in the sibling drafts on 2026-10-07 (read-only, `git show`):

- **Plan 012 (`d/ops501`) assumes (a).** `ops-501.md:51-52` defines `rolling-no-validation` as "Between placements
  (after plan 008), wait a fixed settle delay" and `restart-all` as "no wait between placements", with an outage
  confirmation; its table at `:363-371` ("After plan 008 (instance-wide fan-out over placements)") and `OPS-Q8`
  (`:601`) ask how one request walks several placements. **Under (b) or (c) no apply spans placements**, so "between
  placements" belongs to the fleet:
  - **`restart-all` across an instance's placements** is a fleet with `strategy: restart-all` and
    `batchSize` = the number of members, so every member restarts without waiting. Plan 012's `confirmOutage`
    (`OPS-Q7`) would then need to be **fleet-level**: one recorded confirmation for the fleet, copied to each member's
    request. That changes plan 012's design (`ops-501.md` §5.3) and must be said in its sitting.
  - **`rolling-no-validation`'s settle delay** is a wait between fleet **batches**, not between placements inside one
    apply. The fleet has no inter-batch delay today (`fleet-apply.md`), so `OPS-Q8`'s delay (x)/(y)/(z) becomes a
    fleet property. Under one placement per apply, plan 012's "on one placement they are the same host action" stays
    true.
  - **Under (a)** plan 012's text stands as written, and this plan's S2 gains the fan-out.
- **Plan 012's undeclare.** Its recommended U-C never touches the workload, but its U-B option and its "Stop first"
  console step (`ops-501.md:210-222`) use the **instance-wide stop**. They therefore depend on S4's live-placement
  enumeration (R6's retired-placement defect) and on S2's collision fix (R5). Plan 012's undeclare is also the only
  future writer that moves an instance out of `active`, so it races S3's `addPlacement` (see S3 Step 3).
- **Plan 014 (`d/vm`).** `vm-adapter.md:50` S2 ("`rolling-health-gated` across three VMs, needs plan 008") under (b) is
  **a fleet of three members, `batchSize: 1`, `onFailure: halt-remaining`**. Plan 014's text should say so. Its
  single-host case needs nothing from this plan (`vm-adapter.md:554-555`).
- **Plan 013 (`d/regapi`).** No semantic interaction; it shares only the migration number (R12).

### OQ-8.2 — The instance-wide stop over placements whose containers share a name (S-d)

**Does not change:** a stop is scope-granular (`BE-D26`); `instance.stop` is Administrator-only at the instance
(`OR-18`); the stop never touches config.

**Status quo:** S-d with P1 and P2 both named `sm-cache` lands `STOP_FAILED` / `stop.replica.label.collision` **before
stopping anything** (R5). The operator must stop P1 and P2 one by one.

| | (a) The journal becomes placement-aware (V021) | (b) Keep the refusal | (c) Forbid the collision at the source |
|---|---|---|---|
| **Delta** | `apply_step_intent` gains `placement_id`; the key becomes `UNIQUE (tenant_id, apply_id, placement_id, step, replica_ref)`; **the idempotency key gains the placement only for an instance-wide request** (a placement-scoped request's `apply_id` already fixes its placement, so its key stays `applyId:step:replica` and a request in flight across the upgrade resumes onto its own rows, S2 Step 4); `StepIntentRow` carries it; `observeApply`'s steps report each step's own placement. **Both `BE-D23` clauses are closed in the DDL**; `BE-D23` is superseded by a new decision. Applies stay placement-scoped under OQ-8.1 (b) | Nothing. `console.yaml` already documents the refusal | A partial unique index: one container name per instance among live placements, across targets; `addPlacement` answers `409 CONFLICT` |
| **S-d** | One request, two `STOP` intents (P1/`sm-cache`, P2/`sm-cache`), `STOPPED` | `STOP_FAILED`, nothing stopped; two placement stops needed | S-a's configuration cannot be declared; the operator must name P2's container differently. S-d then succeeds |
| **Upgrade with a stop in flight** | V021 refuses to run while an instance-wide stop is non-terminal (S2 Step 3); the runbook says drain first | — | — |
| **Next adapter (`OR-111`, plain VM)** | A systemd unit `cache.service` on two VMs works | Same refusal on every two-VM instance | Every VM placement of one instance needs a differently named unit: a constraint on the customer's deployment, pushed into the VM plan |
| **Cost** | ≈ 1.5–2 slice-days (S2, including the cross-upgrade resume IT and the second expected inversion) | 0 | ≈ 0.2 slice-day |

**Recommendation: (a).** The stop is the emergency lever, and (c) moves the same problem into the very next adapter.
(a) is also what keeps OQ-8.1 (a) open as an additive later step instead of a second migration of the same table.

### OQ-8.3 — How does an instance get a second placement?

**Does not change:** placements are DP-owned (`OR-27`), immutable, and not a grant level (`OR-16`); the body names paths
and containers only inside the target's envelope (B2, three enforcers); the probe and metrics come from the package's
declared version, never the body; acquisition stays per instance (`OR-71`).

**Status quo:** impossible (R2).

| | (a) `addPlacement` — a new operation | (b) `declareInstance` takes `placements[]` | (c) both |
|---|---|---|---|
| **Delta** | `POST /dp/instances/{instanceId}/placements`, body = `declareInstance`'s `placement` object + `targetId`; authorized by `instance.bindTarget` + `target.use` at the target (`D10`'s two-sided rule; the Operator role holds both, `D10-G2-APPROVAL-2026-09-15.md:240`); returns `Instance`; `409 CONFLICT` / `METRICS_PORT_CONFLICT` / `422` as declare. No new verb, no CP table. ⚠️ **The `instance.bindTarget` scope is a new choice, not declare's:** declare checks it at the **environment** (`DeclareOrchestrator.java:124-125`, `ResourceKind.ENVIRONMENT`), because the instance does not exist yet. For `addPlacement` the instance exists, so the sub-question below asks which scope | A served operation's body changes shape (additive only if `placement` stays and `additionalPlacements[]` is added); declare's two-phase protocol (`BE-D32`) and its sweep must handle N rows | both |
| **S-a** | declare with P1, then `addPlacement` P2 | one declare naming P1 and P2 | either |
| **S-e** | as S-a; then OQ-8.9 decides | as S-a | either |
| **Re-target later** | `addPlacement` new, then retire old (OQ-8.4) | needs (a) anyway | — |
| **Cost** | ≈ 1.5 slice-days (S3, now including the contract, inventory and Postman rows, the lifecycle lock and its race IT) | ≈ 2 slice-days (touches `BE-D32`'s declare and sweep) | ≈ 2.5 |

**Recommendation: (a).** Additive, reuses the two existing verbs, and is the half of re-target that (b) would still need.

**Sub-question OQ-8.3s — where `instance.bindTarget` is checked for `addPlacement`.** *Does not change:* `target.use`
at the target; the Operator and Administrator roles hold `instance.bindTarget` at every scope they are granted
(`D10-G2-APPROVAL-2026-09-15.md:240`), so the built-in roles see no difference. *Status quo:* only declare checks it,
at the environment. **(i) At the instance** (`OR-16`: authorization for an instance-targeted operation resolves
through the owning instance). A binding scoped to one instance can then add placements to that instance only.
**(ii) At the environment**, as declare. Only an environment-level grant can add a placement, and an instance-scoped
operator must ask an environment admin. In S-a, under (i) an operator granted at instance `i` adds P2; under (ii) the
same operator gets `403`. **Recommendation: (i)**, because `OR-16` already rules how placement-targeted operations
authorize. The difference shows only with custom roles bound below the environment.

### OQ-8.4 — Removing one placement (retire)

**Does not change:** a retired row is kept forever (immutable except `retired_at`, `V007…sql:184-194`); a target cannot
be retired under a live placement (`:230-240`).

**Status quo:** a placement is retired only when its whole declare is abandoned (`InventoryService.java:172,217`). A
wrongly added placement cannot be removed. This is open item 39 (d)'s question at placement granularity —
*"inventory removal must not silently stop the workload: does undeclare require a prior stop, refuse while running, or
leave it running and orphaned?"*

| | (a) Build `retirePlacement` now, under `instance.bindTarget` | (b) Rule it in the same sitting as undeclare (`OR-114` item (2)); contract it as `501` until then | (c) Not in v1 |
|---|---|---|---|
| **Delta** | `DELETE /dp/instances/{instanceId}/placements/{placementId}`; refused for the instance's **last** live placement (that is undeclare), while any non-terminal request or running fleet reservation names it, and **never touches the host** (the container keeps running, now unmanaged — said in the response and the audit line) | A `501` row in `ENDPOINT-INVENTORY.md` §3; built later by the undeclare plan with whatever (d) rules | Nothing |
| **S-a, P2 added by mistake** | retire P2; P2's container keeps running | cannot remove until item (2) lands | cannot remove |
| **S-e** | retire P2 restores push attribution (one live placement again) | as (b) under OQ-8.9 (b): push stays dropped until item (2) lands | as (b), forever |
| **Cost** | ≈ 0.4 slice-day (in S3) | ≈ 0.1 | 0 |

**Recommendation: (b)**, because the owner said item (2)'s permission questions come first and this is the same
question; **if the owner rules it before S3 starts, S3 builds (a)** with whatever rule is given.

✅ **Ruled before S3 starts** (`OR-121`, plan 012 `OPS-Q12` R-1): S3 builds `retirePlacement` under
`instance.bindTarget` at the instance, with `?workload=leave-running`, refused for the last live placement and while
anything is in flight on it, never touching the host (`BE-D53`).

### OQ-8.5 — A database referential guard for `apply_request.placement_id` (§8 row 13)

**Does not change:** the nil UUID stays the instance-wide sentinel (stops only); every real placement is already checked
by the service before admission and by the catalog at execution (R7).

| | (a) Build it now (V022) | (b) Defer |
|---|---|---|
| **Delta** | A `STORED` generated column `placement_ref = NULLIF(placement_id, nil)` and `FOREIGN KEY (tenant_id, instance_id, placement_ref) REFERENCES placement (tenant_id, instance_id, placement_id) MATCH SIMPLE` (with a new `UNIQUE (tenant_id, instance_id, placement_id)` on `placement`) — **if MF-3 measures it accepted**; otherwise a `BEFORE INSERT` trigger with a named refusal. It also binds the placement to the request's **instance** (the v2 paper's F20) in the database. **Existing rows:** a plain FK if MF-4 measures 0; otherwise `NOT VALID`, then `VALIDATE CONSTRAINT` once the stated clean-up rule has run (the rule is the owner's: delete terminal demo-era rows, or keep them unvalidated). **Lock class:** `ACCESS EXCLUSIVE` on `apply_request` for the column add, and a full rewrite if MF-5 measures one; `SHARE ROW EXCLUSIVE` on both tables for the FK. **Tests (re-measured, P008-03):** 52 `dp-core` IT/fixture files write `apply_request`; **38 of them register no placement**, and 47 test files build a random `new PlacementId(UUID.randomUUID())`. `InventoryFixtures.declared(scope)` is called from 14 files and `declare(tenant, instance)` from 36, and neither registers a placement. S4 therefore adds a fixture that registers the scope's placement **on its own fresh target** (`InventoryFixtures.target` + `placement` exist, `InventoryFixtures.java:60-93`), unique per target in container name, config directory and health port, and moves every admitting IT onto it | Two enforcers stay (service, catalog); §8 row 13's second half stays open; no test moves |
| **A planted bug: a new submit path forgets `requireLivePlacement`** | **Unknown placement, or one of another instance:** refused `23503` at admission. **Retired placement of the same instance:** the FK checks existence, not liveness, so it is admitted and lands `FAILED_NO_EFFECT` / `placement.unresolvable` at pre-flight, as today | admitted; lands `FAILED_NO_EFFECT` / `placement.unresolvable` at pre-flight, no host touched |
| **S-a…S-e** | unaffected at runtime (every served path already submits a live placement) | unaffected |
| **Cost** | ≈ **1–1.5 slice-days** (V022, MF-3..5, the fixture and about 38 IT files) | 0 |

**Recommendation: (a)** — this plan owns §8 row 13, and S3 adds a new writer of placements. **If the owner wants the
estimate down, (b) is the one option here that saves a full slice-day** without weakening anything that exists today.

### OQ-8.6 — `AGW-D16`'s `agent_replica` vs `sm_data.placement` (the draft's D1)

**Does not change:** `sm_data.placement` is the DP's placement registry for `dp-remote` (plan 010); the Agent Gateway is
designed, not built (R11); `AGW-D2` (no datasource in the gateway). **Status quo:** routed to the `AGW-D*` owners; the
agent gateway is revisited after the `dev` PR (`OR-115`).

| | (a) Decide now that `agent_replica` hangs off `placement` | (b) Record, do not decide |
|---|---|---|
| **Delta** | A `BE-D` stating `agent_replica.placement_id` references `sm_data.placement`; settles part of `AGW-D16` from outside its family, which `I3`/`I4` forbid unless the `AGW-D*` owner (the owner, `OR-69`) rules it as an `AGW-D` | `RECONCILIATION-008.md` records *"`sm_data.placement` is the placement registry for both execution paths; whether `agent_replica` is a child of it is the agent-gateway plan's question"* |
| **S-a…S-e** | unaffected: none uses the agent path | unaffected |
| **Cost** | ≈ 0.1 (a decision text), plus an `AGW-D` amendment | ≈ 0.05 |

**Recommendation: (b).** Nothing this plan builds forecloses either answer (S2's journal carries a placement id the
agent path would also carry).

### OQ-8.7 — A one-placement operation that mints an instance generation

**Does not change:** configuration is per instance (`config_generation`), so a generation is the instance's desired
state; the ladder is the lowest rung across live placements (`OR-37`).

**Status quo (as built, measured):** `rollbackApply`, `restoreRuntimeConfig`, `acceptRuntimeConfig` and `setLogLevel`
name a placement but mint a new **instance** generation (`ApplyServiceImpl.java:108-180`).

| | (a) Keep as built, and document it | (b) Make those four operations instance-scoped rollouts |
|---|---|---|
| **Delta** | `console.yaml` (the four descriptions) and the runbook: after a one-placement operation, roll the new generation across the other placements (a fleet), or the instance reads as not converged | An `OR-60`/`OR-42` amendment; each of the four builds a fleet over the live placements; rollback eligibility is per placement, so "roll every placement back to 5" needs a per-member eligibility rule |
| **S-a** | unaffected | unaffected |
| **S-b** | unaffected (no new generation) | unaffected |
| **S-c** | Rollback P1 → generation 8 = content of 5, applied to P1; P2 is on 7, so the ladder drops to `rendered` for the instance (`ConfigLadder.java:74`) until a fleet applies 8 to P2 | One call rolls P1 and P2 to 8; if P2 is ineligible (no evidence of 5 on P2), the rule must say whether it halts or skips |
| **S-d** | unaffected (no generation) | unaffected |
| **S-e** | as S-c for File Mesh | as S-c |
| **Cost** | ≈ 0.1 (docs, in S1) | ≈ +1.5 slice-days, two amendments |

**Recommendation: (a)** — the ladder already reports the truth, and (b) reverses four recently ruled shapes.

### OQ-8.8 — Does a placement count against the instance quota (`OR-40`)?

**Does not change:** the quota is "maximum instances per solution", checked at declare (`OR-40`); capacity and packing
stay the customer's (`OR-29`); a placement is not a grant level (`OR-16`).

| | (a) No | (b) Yes — a new quota dimension |
|---|---|---|
| **Delta** | Nothing: `addPlacement` does not consult the entitlement | An entitlement-schema change in `sm_control` (a placements-per-instance or placements-per-solution limit), checked by the gateway before `addPlacement` (`I7`: the CP checks, the DP owns the rows) |
| **Arithmetic: quota 2 instances; instance A has 3 placements, B has 1** | A and B fit (2 instances). A third declare is refused (`OR-40`). A fourth placement on A is allowed | Depends on the new limit: e.g. "placements ≤ 2 × instances" = 4 refuses A's fourth placement (4 already: 3 + 1), and a third instance would need the instance quota too |
| **S-a…S-e** | unaffected (one instance each) | S-a: P2 is a second placement, so it counts against the new limit |
| **Cost** | 0 | ≈ 1 slice-day + a contract and entitlement change |

**Recommendation: (a).** A placement is not an instance (`OR-16`), and capacity is the customer's (`OR-29`).

### OQ-8.9 — Push-path metrics once an instance has two placements

**Does not change:** `I1` (no placement field in the MI's events); facts F20 (no event field names a placement);
`OR-107`'s attribution by the payload key; pull-path metrics, which are per placement under every option (R10);
the broker-connection-level drop counter (`OR-107` OQ-20 (b)), which already surfaces `ambiguous_placement`.

**Status quo:** `ambiguous_placement` is reachable only during a re-target overlap (`OR-92`/OQ-15), which no operation
can produce yet (R2). After S3 any operator can produce it permanently.

| | (a) `addPlacement` refuses for a package that declares push metrics | (b) Allow it; document and surface the drop | (c) Charge push readings to the instance, not a placement |
|---|---|---|---|
| **Delta** | S3: if the instance's **declared package version** has any `solace_event` metric source (`PushMetric`), `409 CONFLICT` with a fixed message naming the reason; one test. A future re-target of a path-2 instance (add, then retire) needs an exception for the overlap `OR-92` already accepts, which belongs to the plan that builds retire (OQ-8.4) | S3 adds nothing; `console.yaml`'s `addPlacement` description and the runbook say: "a path-2 instance with more than one live placement collects no push metrics; each event counts as `ambiguous_placement` on the broker connection". `getHealth`/attention show nothing new | An `OR-107` amendment: readings for a multi-placement instance are stored under an instance-level series (no `placement_id`), which needs a sentinel placement in `sm_metrics` series identity (`OR-75`, `sm_metrics V001…sql:17-21`), an `sm_metrics` migration, and a read-path change for charts |
| **S-a…S-d** (Cache, pull only) | unaffected | unaffected | unaffected |
| **S-e** | `addPlacement` P2 → `409`; F keeps one placement and its push metrics | P2 added; from that moment every push metric of F is dropped as `ambiguous_placement`; F's charts go flat; the broker-connection counter rises | P2 added; F's push metrics continue, charted at instance level, and no longer per placement |
| **Cost** | ≈ 0.2 slice-day | ≈ 0.1 | ≈ +1.5–2 slice-days, an `OR-107`/`OR-75` amendment |

**Recommendation: (a).** It fails closed and visibly at the moment of the action. (b) breaks monitoring silently for the
very instance the operator just changed, and (c) re-opens two ruled shapes. Cache, the case S-a to S-d exercise, is pull-only and is unaffected; whether a
multi-placement File Mesh instance is needed in v1 is the owner's call, and (c) stays available as a later amendment. **Correction carried into
Acceptance 1 and S5:** "metrics appear per placement" means **pull-path** metrics.

### OQ-8.10 — The state a new placement starts in

**Does not change:** `addPlacement` writes no host and never deploys anything (`I1`, `OR-29`: the customer's CI/CD
deploys the container); the ladder is the lowest rung across live placements (`OR-37`); health polls every live
placement (`HealthPoller.java:136`).

**Status quo:** none (no `addPlacement`). **Measured effects of a new live placement with no apply history:**

- **Ladder (read):** `ConfigLadder.reach` takes the lowest rung across live placements (`ConfigLadder.java:150-170`); a
  placement with no evidence reads `rendered`, so the instance's ladder drops to `rendered`.
- **Ladder (stored + event):** `instance_state.reached_rung` and `instance.config-state` are written and announced
  **only when an apply lands** (`OutcomeService.java:394-400`; firing rule `ApplyEventEmitter.java:124-128`, "fires only
  when that lowest rung moves", `OR-37`). Nothing re-settles them on `addPlacement`, so the stored rung and the
  announced one would stay at the old value while the computed ladder says `rendered`.
- **Health:** until the first poll, `HealthView.unpolledPlacements` = 1 and the REST answer carries "1 placement(s)
  have not been polled yet" (`HealthServiceImpl.java:28-33`, `HealthController.java:42-44`). Once polled, a container
  that CI/CD has not deployed yet reads as unreachable (`ProbeResultMapper.java:48-49`), and that feeds the instance
  rollup and the home attention.

| | (a) Leave it unconverged; `addPlacement` re-settles the ladder | (a′) Leave it unconverged; touch nothing | (b) `addPlacement` also admits an apply of the newest generation to the new placement |
|---|---|---|---|
| **Delta** | In `addPlacement`'s transaction, recompute the lowest rung through the same settle function and firing rule as the landing path (one rule, two callers), so `reached_rung` and `instance.config-state` follow the read. The runbook says: next, roll the current generation to the new placement (a one-member fleet, or `submitApply`) | Nothing; the stored rung stays stale until the next apply lands | `addPlacement` also requires `config.apply` (+ `instance.restart` if the strategy restarts) and returns an `applyId`; the apply fails `FAILED_NO_EFFECT` if the container is not deployed yet |
| **S-a (P1 at `restart-observed` on g7; add P2)** | Ladder → `rendered`, `instance.config-state {reached: rendered}` emitted; health "1 not yet polled", then GREEN if CI/CD deployed P2, else unreachable with an attention row; after a one-member fleet g7 → P2 the ladder climbs back | Ladder read → `rendered`, but the stored rung and the event stream still say `restart-observed`; any surface reading the stored rung shows "converged" | g7 applied to P2 at once if deployed: ladder dips and recovers in one step. If not deployed: `FAILED_NO_EFFECT` and an attention row, then a re-apply later |
| **S-e** | as S-a, then OQ-8.9 | as S-a | as S-a |
| **Cost** | ≈ 0.3 slice-day (S3) | 0 | ≈ 0.5 slice-day + a second verb on `addPlacement` |

**Recommendation: (a).** It keeps the read, the stored rung and the event telling one story, and it does not couple
registering a placement to the customer's deploy timing. A placement that is not deployed yet is shown as unreachable,
which is the truth.

### OQ-8.11 — Config uniformity across an instance's placements

**Does not change:** `architecture.md` §9 (`:218`, `:753`) and open item 15 say Cache's `mustBeUniformAcrossReplicas`
is "genuinely forced true" (a shared management queue and namespace), and that a uniformity-required solution should
have `rolling-*` suppressed and should default to stage-everywhere-then-restart. **The package schema has no such field**
(measured by plan 012, `ops-501.md:445-449`, which **routes this question to plan 008's refresh**). `OR-53`:
behaviour comes from package declarations, so this plan invents no field.

**Status quo:** with one placement per instance it cannot matter. With S-a under OQ-8.1 (b), a `halt-remaining` fleet
that halts after P1 leaves **P1 on g7 and P2 on g6**. For Cache that divergence is the split-brain risk §9 names.

| | (a) Record the limit and give the safe recipe | (b) A package field, enforced by the fleet | (c) Stage everywhere, then restart, inside one request |
|---|---|---|---|
| **Delta** | Runbook and `submitFleetApply`'s description: for a uniformity-required solution, roll across placements as a **`manual`** fleet (writes every placement and restarts none, so a halt leaves config *uniform-but-unapplied*), then restart by the customer's own means (`restart-observed` records it, `OR-112` (1)). The ladder already shows any divergence per placement | A `mustBeUniformAcrossReplicas` field in `solution-package.schema.json` (a `UI-D14` contract change), set for Cache; the fleet refuses `rolling-*` for members sharing an instance when it is true | Needs OQ-8.1 (a) |
| **S-a** | Operator uses a `manual` fleet over P1, P2; both files written; restarts are the customer's; no split | `rolling-health-gated` over P1, P2 → `422`; operator uses `manual` | one request stages both, then restarts |
| **S-b** | P2 unreachable: P1 staged, P2 not, both still running g6. Uniform in effect | as (a) | as (a) |
| **S-c, S-d, S-e** | unaffected | unaffected | unaffected |
| **Cost** | ≈ 0.1 (docs, S1/S5) | ≈ 1 slice-day + a contract change | OQ-8.1 (a)'s cost |

**Recommendation: (a)** now. Take (b) when a second uniformity-required solution or a frontend strategy picker needs the
declaration, and rule it together with plan 012's `OPS-Q8`.

### Recorded, not asked (the draft's D2 and D3)

- **D2 — what `replica_ref` means.** Settled by `BE-D23`'s own "what this forbids": a **key change**, never a value change.
  S2 implements that.
- **D3 — cross-scope supersession.** Moot under OQ-8.1 (b)/(c) (R9). Becomes a blocking question only under (a).

---

## Draft decisions — ids allocated 2026-10-07 (`I3`)

Each supersedes or amends by cross-reference; no decision text is edited in place. **Recorded in
[`docs/design/backend/decisions.md`](../../design/backend/decisions.md) as `BE-D44`–`BE-D48`, PROPOSED**, with the
ruled options; S1 checks them against this text. Placement retirement, built here in S3, is `BE-D53` (drafted as O-5
in [`ops-501.md`](../../design/backend/ops-501.md)).

1. **`BE-D44` — The apply journal is placement-aware** (if OQ-8.2 (a)). `apply_step_intent` carries the
   placement each intent addresses; uniqueness is `(tenant_id, apply_id, placement_id, step, replica_ref)`. **The
   idempotency key names the placement only for an instance-wide request** (`applyId:placementId:step:replica`); a
   placement-scoped request keeps `applyId:step:replica`, because its `apply_id` already fixes the placement. That
   keeps every request admitted before V021 resumable onto its own rows. Supersedes `BE-D23`'s identity statement;
   `BE-D23`'s prohibition on encoding a placement into `replica_ref` is carried forward unchanged.
2. **`BE-D45` — A rollout across an instance's placements is a fleet of placement-scoped applies; an
   instance-wide apply stays refused before admission** (if OQ-8.1 (b)/(c)). States why: `OR-53`, `OR-4`, `OR-42` and the
   per-placement read paths. Amends `BE-D12` §2.1 and `BE-D21` by cross-reference; disposes of §8 row 14 as unreachable.
   States the cross-plan reading (fleet-level "between placements") so plan 012 builds on it.
3. **`BE-D46` — An instance-wide stop addresses the instance's live placements from the registry.**
   Amends `BE-D26`'s enumerator (request history) by cross-reference.
4. **`BE-D47` — A placement is added to an active instance by `addPlacement`** (if OQ-8.3 (a)), under
   `instance.bindTarget` (at the scope OQ-8.3s rules) + `target.use` (target), in one DP transaction that holds the
   instance row and re-checks `active`, with its metrics endpoints, the probe and metrics copied from the instance's
   declared package version; refused for a push-metrics package (if OQ-8.9 (a)); re-settles the ladder (if OQ-8.10 (a)).
5. **`BE-D48` — `apply_request.placement_id` is referentially guarded** (if OQ-8.5 (a)), by the mechanism
   MF-3 measured; closes §8 row 13's second half for unknown and other-instance placements (liveness stays the
   service's and the catalog's).

## Global Constraints

- **`I1`.** A second placement is another container the customer's CI/CD deploys and the customer names; SM writes its
  config file and restarts it (`OR-29`: capacity, packing and ports stay with the customer). No MI change anywhere —
  including no placement field in push events (OQ-8.9).
- **`I5`.** Every new or changed query binds `tenant_id`, and every placement lookup binds **tenant + instance +
  placement** (`OR-29`, F20). Bind parameters only.
- **`I6`.** `addPlacement` carries paths, names and ports, never a secret; no new column holds a value.
- **`I7`.** No `sm_control` table changes (OQ-8.8 (a)); the gateway authorizes, the DP owns the rows.
- **Frozen migrations** (`OR-22`, `OR-105` (e)). New files only; anchor them in `CHECKSUMS.sha256` when published.
- **Do not weaken an existing guarantee.** A migration that drops a constraint re-adds an at-least-as-strong one in the
  same migration, with a refusal test naming it.
- **Every guard has a planted-violation proof** (`L7`); a mutation that does not fire is a finding about the tests.
- **Contract files (`console.yaml`, `ENDPOINT-INVENTORY.md`, the Postman collection) have one writer at a time, in
  dispatch order.** S1 edits **descriptions only** (no operation added or removed, so `ConsoleOperationsMirrorTest` and
  `check-postman.py` are unaffected). **S3 alone** adds the `addPlacement` operation, its `ConsoleOperations`
  registration, its handler, the inventory's served row and the Postman markers, in one slice, because
  `ConsoleOperationsMirrorTest` (`cp-api`) fails on a contracted operation with no registration and `check-postman.py`
  (run-checks step 15) fails on a served-marker mismatch. S3 starts after S1 ends; no other slice touches these files.
- **Every slice ends green.** The full reactor build at the end of each slice (`OR-70`). A slice that edits
  `console.yaml` also runs `mvn -f backend/pom.xml -pl cp-api,console-gateway -am verify` and
  `./contracts/run-checks.sh --strict` before it hands over.
- **Upgrade runbook.** V021 refuses to run while an instance-wide stop is in flight (S2 Step 3). Before upgrading, drain
  it, or let it land. Placement-scoped requests in flight resume across the upgrade (S2 Step 4).
- **Docker/Colima is shared state.** Worktree-owned Compose project names; clean up only your own containers.
- **Agent Gateway.** Nothing here describes it as implemented, conformant or reconciled; the history's Prerequisite 3
  stands verbatim.

## Slices

Written for the answers `OR-121` ruled (OQ-8.1 (b), 8.2 (a), 8.3 (a) with 8.3s (i), **8.4 built in S3 under plan
012's `OPS-Q12` R-1**, 8.5 (a), 8.6 (b), 8.7 (a), 8.8 (a), 8.9 (a), 8.10 (a), 8.11 (a)). The "If OQ-… (x)" lines below
record what another answer would have changed; none of them applies any more, except the ones marked as ruled.

### S1 — Records and contract descriptions (docs + `contracts/` descriptions; no Java)

**Files:** `docs/design/backend/decisions.md` (the ruled drafts above); `docs/design/backend/eda-walking-skeleton.md`
(a dated note under §8 rows 13 and 14 pointing at the new decisions — the rows are not rewritten);
`docs/design/common/contracts/console-integration.md` §12 / §15.3; `contracts/openapi/console.yaml` (**descriptions
only**); `contracts/openapi/postman/*` (regenerated, if descriptions flow into it);
`docs/design/backend/fleet-apply.md` (one paragraph: placements of one instance as members; the fleet-level reading of
"between placements" for plan 012); `docs/runbooks/` (the upgrade drain line; OQ-8.7 (a) and OQ-8.11 (a) recipes).

- [x] **Step 1:** The ruled decisions are already recorded as `BE-D44`–`BE-D48` (and placement retirement as `BE-D53`)
  by the docs integration of 2026-10-07. Check their text against this plan's Draft decisions and the slices as
  built; correct by a dated changelog row, never by editing a decision's meaning (`I3`).
- [x] **Step 2:** `stopInstance` (`console.yaml:1218-1237`): "every **live** placement of the instance" replaces "every
  placement the instance has a history with"; **`stop.no.known.placement`'s text becomes "instance-wide against an
  instance with no live placement"** (it was "no placement history", `:1236`; plan 012's undeclare reads this meaning);
  `stop.replica.label.collision` is removed from the post-admission failures if OQ-8.2 (a);
  `ApplyObservation.steps[].placementId` is described as the step's own placement. These describe the plan's end state;
  the branch merges only after S5.
- [x] **Step 3:** `submitFleetApply`: one sentence that two placements of one instance are two members (as built), and
  OQ-8.11 (a)'s `manual`-fleet recipe. The four one-placement operations: OQ-8.7 (a)'s sentence.
- [x] **Step 4:** `./contracts/run-checks.sh --strict` green; `mvn -f backend/pom.xml -pl cp-api,console-gateway -am
  verify` green (`ConsoleOperationsMirrorTest` unchanged by a description edit); `git diff --check`.
- [x] **Step 5:** Commit `docs(contracts): plan 008 S1 — the live-placement stop, the ruled decisions`.

**S1 Results (2026-10-07, wave 1 lane A).** *(W6 note: merged in wave 1; tick state and the current owed list are in the closing [Results](#results--the-closing-record-wave-6-2026-10-08).)* Steps 1-5 done, docs and `console.yaml` descriptions only; no operation added
or removed, no Java. Step 1: `BE-D44`-`BE-D48` and `BE-D53` read against the Draft decisions: no meaning changed, a dated
changelog row records the check. Step 2: `stopInstance` says "every **live** placement ... never from request history";
`stop.no.known.placement` is "instance-wide against an instance with no live placement"; `stop.replica.label.collision` is
gone from the post-admission failures; `ApplyObservation.steps[].placementId` is "the step's own placement". The first
paragraph of `stopInstance`'s description (the Administrator-only line) was left untouched for plan 012 T1 (HF3 order A then
B). Step 3: `submitFleetApply` carries the placements-as-members paragraph and the `manual`-fleet recipe; the four
one-placement operations (`acceptRuntimeConfig`, `restoreRuntimeConfig`, `rollbackApply`, `setLogLevel`) carry OQ-8.7 (a)'s
paragraph. Also edited: `console-integration.md` section 5.3 row 52a (the plan's "section 12 / 15.3" names no stop text: the
stop is row 52a, recorded in the changelog row), `fleet-apply.md` section 5.1, a dated note under
`eda-walking-skeleton.md` section 8 rows 13/14, and two `operations.md` procedures (the V021 drain; roll across placements and
uniformity). Step 4 evidence: `generate-postman.py` rewrote the collection (description text only: 46 operations, 45 served);
`check-postman.py` OK; `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `ConsoleOperationsMirrorTest` 6/6 and the
gateway `SchemaMirrorLockTest` 41/41 green (targeted, `-pl cp-api,console-gateway -am`, no full reactor). **Not edited
(flagged for S3, the inventory's writer):** `contracts/openapi/ENDPOINT-INVENTORY.md:384` still says the two post-admission
failures are `stop.no.known.placement` and `stop.replica.label.collision`; it is stale once S2 lands and S3 owns that file.

### S2 — The placement-aware journal (V021) — OQ-8.2 (a)

**Files (measured):** Create `…/sm_data/V021__intent_placement_identity.sql`; Modify `apply/model/StepIntentRow.java`;
`apply/persistence/StepIntentRepository.java` (`find` `:37` and `insert` `:47` gain the placement; `forRequest`
returns it); `apply/engine/StepIntentService.java` (`ensureIntent` takes the placement; `compare` unchanged);
`apply/persistence/Constraints.java` (`INTENT_STEP_REPLICA_UNIQUE` `:30` renamed to the new constraint);
`apply/engine/ApplyExecutor.java` (`runStop` `:312-372` and its collision refusal `:358-363`; `idempotencyKey` `:1535`;
the **11** `ensureIntent` call sites `:382, :515, :541, :621, :655, :718, :807, :836, :871, :1077, :1183`);
`apply/persistence/JdbcApplyObservationRepository.java`, `apply/service/ApplyObservationRows.java`,
`apply/service/ApplyServiceImpl.java` (`view`/`step`: the step's own placement). `ResumePlanner.java` is **not**
touched: it decides per intent and never looks intents up. Create `apply/persistence/IntentPlacementIdentityIT.java`,
`apply/persistence/IntentIdentityUpgradeIT.java`; Modify (invert) `InstanceWideIdentityLimitIT.java` and
`StopApplyIT.java:256`.

- [x] **Step 0:** Re-measure `BE-D23` on the merged tree (R4) and MF-1, MF-2. Record them. *(R4 is measured by the red-first run of `IntentPlacementIdentityIT` against the old schema (5 failures + 6 errors of 11) and by `InstanceWideIdentityLimitIT`; MF-2, that the migration owner can update the table, by V021's back-fill running as the migrator in `IntentIdentityUpgradeIT`. **MF-1, the counts on the owner's `OR-118` database, were not measured: a lane cannot reach it; V021's refusal and the runbook's drain query protect it. OWED to the operator.**)*
- [x] **Step 1: Failing tests.**

```java
// IntentPlacementIdentityIT
@Test void twoPlacementScopedIntentsOfOneApplyCoexistInDifferentPlacements() { }   // clause 1, at the DDL
@Test void twoStopIntentsWithTheSameReplicaRefInDifferentPlacementsCoexist() { }   // clause 2 — R5's case
@Test void twoIntentsWithTheSameReplicaRefInTheSamePlacementStillCollide() { }     // narrowed, not removed
@Test void anIntentsPlacementMustBeItsRequestsPlacementWhenTheRequestIsPlacementScoped() { } // named refusal
@Test void anInstanceWideStopsIntentNamesARealPlacementOfTheSameInstance() { }      // named refusal
@Test void aNewIntentNeverCarriesTheNilPlacement() { }                              // named refusal, every insert
@Test void placementScopedStepsStillRequireTheAtPlacementSentinel() { }             // unchanged CHECK
@Test void hashOnConfigStepsAndTheAttemptTripleFkAreUnchanged() { }
@Test void aPlacementScopedKeyIsUnchangedAndAnInstanceWideKeyNamesThePlacement() { }
@Test void anInstanceWideStopOverTwoPlacementsBothNamedSmCacheLandsStopped() { }    // engine, in-memory adapter
@Test void anInstanceWideApplyIsStillRefusedBeforeAdmission() { }                    // OQ-8.1 (b): unchanged

// IntentIdentityUpgradeIT — Flyway migrated to V020 only, then V021, then resume (no precedent in the suite:
// the first IT to stop Flyway at a target version; Flyway's `target` configuration)
@Test void aPlacementScopedApplyRunningAcrossV021ResumesOntoItsOwnIntents() { }     // no IntentMismatch, no 2nd intent
@Test void aPlacementScopedStopRunningAcrossV021ResumesOntoItsOwnIntents() { }
@Test void v021RefusesToRunWhileAnInstanceWideStopIsNonTerminal() { }               // names the drain procedure
@Test void aTerminalInstanceWideStopsNilIntentsSurviveAndAreNeverReinserted() { }
```

- [x] **Step 2:** Run; clauses 1 and 2, the stop test and the upgrade tests fail; the rest pass (the measurement that
  `BE-D23` holds).
- [x] **Step 3: V021.**
  1. **Refuse an in-flight instance-wide stop** (V008's pattern, `V008…sql:4-16`): if any `apply_request` with the nil
     placement is non-terminal, `RAISE` a fixed message naming the procedure ("let the instance-wide stop land, or
     wait for resume to strand it, then re-run"), never a row (`I6`). This is the one case that cannot be backfilled
     correctly, because its intents' placements were never persisted (`TargetReplica`'s placement, `ApplyExecutor.stopOne`).
  2. Add `placement_id uuid`; backfill from `apply_request.placement_id` (a placement-scoped request's intents get its
     placement). Intents of **terminal** instance-wide stops keep the nil value, **stated in the migration**; a terminal
     request admits no new intent (obligation (q), `StepIntentService.java:99-102`), so those rows are never
     re-inserted or re-found.
  3. `SET NOT NULL`; create the new unique key **before** dropping the old one.
  4. One `BEFORE INSERT` trigger, **refusing nil on every insert**: the intent's placement equals the request's when the
     request is placement-scoped; otherwise it is a live-or-retired placement of the request's instance. Existing nil rows
     are history, not inserts.
  5. Lock class (MF-1's counts) and rollback as the history's Task 2 Step 3 (one-way once a colliding pair exists). The
     `INSERT`-only grant is unchanged.
- [x] **Step 4:** Engine: `ensureIntent` takes the `TargetReplica`'s placement; `idempotencyKey` becomes
  `idempotencyKey(id, scope, placement, step, replica)`, which returns `applyId:step:replica` for a placement-scoped
  request (**unchanged**, so `compare()` passes on resume for every pre-V021 placement-scoped intent) and
  `applyId:placementId:step:replica` for an instance-wide one (only post-V021 instance-wide stops exist, Step 3.1). The
  two formats cannot collide on the global `UNIQUE`: one `apply_id` is either instance-wide or not. The stop's
  label-collision refusal is deleted (the case is now journalable); `find` binds the placement; the observation reports
  each step's placement.
- [ ] **Step 5: Prove nothing was weakened.** *(UNTICKED: the whole `dp-core` suite ran green (see Results), and the two inversions are made; the five mutation proofs below were NOT run, because the Docker daemon stopped answering that night, and no later wave ran them. OWED.)* Drop the new key → the same-placement collision test red; drop the trigger →
  the three refusal tests red; make the key placement-aware for placement-scoped requests too → the upgrade-resume tests
  red. Run the whole `dp-core` suite. **Exactly two tests are expected to change, both inverted in the same commit:**
  `InstanceWideIdentityLimitIT` (into the proof the limit is lifted; plan 005's Results say it was written for this) and
  **`StopApplyIT.anInstanceWideStopIsRefusedWhenTwoPlacementsShareAReplicaLabel` (`:256`)**, which asserts
  `stop.replica.label.collision` (`:268`) and becomes "…shareAReplicaLabel**AndBothAreStopped**". Any other red test is a
  defect. Record before/after counts.
- [x] **Step 6:** Re-record the DDL replay baseline and the sketch mirror if it covers V004 (`SketchMirrorTest`).
- [x] **Step 7:** Commit `feat(dp-core): placement-aware apply_step_intent identity — both BE-D23 clauses (V021)`.

**S2 Results (2026-10-07, wave 1 lane A) — implemented; verification INCOMPLETE, stated exactly.** *(W6 note: the ITs this paragraph lists as not re-run, and the count pins, ran green on `postgres:14` in the wave 1 integration build, and again in every later one; the five mutation proofs did NOT run and stay owed. See the closing Results, section 5.)*
Built: `sm_data` V021 (`V021__intent_placement_identity.sql`, checksum line added; the anchor line is the integrator's after the
push), `StepIntentRow.placement`, `StepIntentRepository` (`find`/`insert`/`forRequest` carry it), `StepIntentService.ensureIntent(t, id,
placement, ...)`, `ApplyExecutor` (11 call sites; `runStop`'s label-collision refusal deleted; `idempotencyKey(id, scope, placement,
step, replica)`, the three-argument form kept as the placement-scoped format), `ApplyObservationRows.ObservedStep.placementId` and
`ApplyServiceImpl.view` (a step reports its own placement; a pre-V021 nil value reads as unknown). V021: refuses an open
instance-wide request (`REQUESTED`/`RUNNING`/`APPLY_INDETERMINATE`) with a fixed sentence naming the runbook; adds the column,
back-fills it from the request, `SET NOT NULL`; adds `apply_step_intent_placement_identity_key` BEFORE dropping the old key; adds a
`BEFORE INSERT` trigger (nil refused on every insert; a placement-scoped request's intent must carry its own placement; an
instance-wide request's must be a live-or-retired placement of the request's instance).
**Red first** (counts, old schema): `IntentPlacementIdentityIT` 11 run, 5 failures + 6 errors; `IntentIdentityUpgradeIT` 4 run, 3
failures + 1 error. **Green** (targeted, one run): `IntentPlacementIdentityIT` 11/11, `IntentIdentityUpgradeIT` 6/6 (adds the two
resume-across-V021 tests, which drive the real executor over the upgraded database), `InstanceWideIdentityLimitIT` 3/3 (inverted),
`StopApplyIT` 15/15 (`...ShareAReplicaLabel` inverted to `...AndBothAreStopped`, plus a placement-scoped-key test), `StepIntentIT`
12/12, `IdempotencyKeyFormatTest` 2/2, `ResumePlannerTest` 20/20; later runs (the shared PostgreSQL was killed by the host's Docker under
load in several of them, which is infrastructure, not a result): `AttemptIT`, `AttemptPreImageChangeIT`, `EnsureIntentRaceIT`,
`EngineTransactionBoundaryIT`, `TenantIsolationIT`, `RefusalShapesIT`, `PrivilegeTopologyIT`, `ReconciliationSchemaIT`,
`SchemaConstraintsIT` (48), `StopDuringApplyIT`, `ResumeReadIT`, `ResumeRunningIT`, `KeyedHashJournalCompatibilityIT` all green; and
the surefire half of the broad run: 529 unit tests, 0 failures.
**NOT verified (no claim made):** (1) the final re-run of `SmDataBootstrapIT`, `ApplyPersistenceConfigurationIT`,
`SmMetricsMigratorIT` (their pinned counts 20 to 21, the function and trigger lists and the `migrated 21` line were edited; the
`SmMetricsMigratorIT` count assertions were red before the edit and the edits were made from the failure text), and of the other
apply-engine ITs that compile against the new signatures (`ConfigLadderIT`, `ResumeIT`, `AdmissionFencesIT`, ...); (2) the
**mutation proofs** (drop the new key; drop the trigger; make the placement-scoped key placement-aware; drop the in-flight refusal;
drop the placement predicate from `find`): the Docker daemon stopped answering (`/_ping` timed out for over an hour) before they
could run. They are the first thing to do when it recovers, with `runit`-style targeted runs of the two new ITs plus
`StopApplyIT` and `IdempotencyKeyFormatTest`. (3) MF-1 (the owner's `OR-118` database) is not reachable from a lane; V021's refusal
and the runbook's drain query protect it.
**Deviations from the plan's text** (each forced by the plan's own other lines): (a) *Exactly two tests change* is false: V021's
trigger, as Step 3.4 specifies it, ties an intent to its request, so every fixture that parked an intent on an **instance-wide
APPLY** request (a scope the engine never admits, `BE-D45`) had to move to a placement-scoped one (`AttemptIT`, `StepIntentIT`,
`EngineTransactionBoundaryIT`, `AttemptPreImageChangeIT`, `KeyedHashJournalCompatibilityIT`, `RefusalShapesIT`,
`PrivilegeTopologyIT`, `SchemaConstraintsIT`, `ReconciliationSchemaIT` take the request's placement), and `StopApplyIT` and
`InstanceWideIdentityLimitIT` register two real placements on two targets because an instance-wide STOP's intents must name a
registered placement of the instance (the plan puts that move in S3 Step 1a; S3 still renames the "history" tests and moves the
enumerator, `StopApplyIT`'s enumerator tests are unchanged here). (b) The plan said the Flyway-target IT has *no precedent*:
`ApplyRequestStateVersionIT` stops at V007 for V008; the same pattern is used. (c) `IdempotencyKeyFormatTest` is a no-Docker unit
test and the engine-level halves of the plan's `IntentPlacementIdentityIT` list live in `StopApplyIT`. (d) The DDL replay
baseline and `SketchMirrorTest` are unchanged: both cover the design's first six files only (checked, not assumed). (e) `backend/README.md`
carried two now-wrong counts (`V001`-`V020`, `migrated 20`); corrected in place (one word each).

**If OQ-8.2 (c):** S2 is replaced by one partial unique index (live placements, `(tenant_id, instance_id,
container_name)`) and its `409` mapping, in S3; `InstanceWideIdentityLimitIT` and `StopApplyIT:256` stay as they are.
**If (b):** S2 is dropped.

### S3 — `addPlacement`, `retirePlacement` and the live-placement stop — OQ-8.3 (a), OQ-8.4 (R-1), OQ-8.9 (a), OQ-8.10 (a)

**Files:** `contracts/openapi/console.yaml` (the `addPlacement` operation: `POST /dp/instances/{instanceId}/placements`,
`x-sm-permission: instance.bindTarget`, `x-sm-permission-also: [target.use]`, `x-sm-scope: instance` per OQ-8.3s (i),
CSRF, body = the `placement` object + `targetId`, `201 Instance`, `400/401/403/404/409/422` as `declareInstance`; the
push-package `409`); `contracts/openapi/ENDPOINT-INVENTORY.md` (operation count 46 → 47, or 48 with OQ-8.1 (c); the
served row; if OQ-8.4 (b) a `501` row); `contracts/openapi/postman/*` (regenerated; served markers);
`dp-api/.../inventory/InstanceInventoryService.java` (+ `AddPlacementCommand`); `dp-core/.../inventory/
InventoryService.java`; the ladder settle shared with `OutcomeService` (OQ-8.10 (a)); `cp-api/.../authz/
ConsoleOperations.java` (register `addPlacement`); `console-gateway/.../http/InstanceController.java`,
`DeclareOrchestrator.java` (or a sibling `PlacementOrchestrator`), a request record; `console-gateway`
`SchemaMirrorLockTest`, the classification tests; `launcher` IT `AddPlacementEndpointIT`; `dp-core` ITs
`AddPlacementIT`, `AddPlacementLifecycleRaceIT`. **Added by `OR-121`:** the `retirePlacement` operation
(`DELETE /dp/instances/{instanceId}/placements/{placementId}?workload=leave-running`, `x-sm-permission:
instance.bindTarget`, `x-sm-scope: instance`, CSRF, no body) in the same contract files, `ConsoleOperations` and the
handler; `InstanceInventoryService.retirePlacement` in `dp-api` and `InventoryService`; the launcher's
`ProductionAuthenticationIT` route set (+2: `addPlacement`, `retirePlacement`); `dp-core` IT `RetirePlacementIT`.
**Moved here from S4** (because retirement makes R6's defect reachable, and the two must never be pushed apart):
`ApplyRequestRepository.java` and `AdmissionService.java` (delete both `placementsOf`); `ApplyExecutor.java` (the stop
reads the live set through a narrow port); `StopApplyIT.java` (S4's Step 1a, unchanged in content);
`arch-tests/.../PlacementEnumerationTest.java` (S4's Step 5, unchanged in content). The operation count in
`ENDPOINT-INVENTORY.md` goes up by **2** (no `501` row).

- [x] **Step 1: Failing tests.** *(All written except `oneAuditLineIsWrittenPerCall`: no audit line exists to test, S3 deviation (6). `OR-125` (1) replaced it with the actor columns, proved by `PlacementActorIT` and two unit tests; `retireTakesTheSameInstanceRowLockAsAddPlacement` was rewritten as `retireAndAddQueueBehindOneInstanceWriterAndBothLand`.)*

```java
@Test void anActiveInstanceGainsASecondLivePlacementOnAnotherTarget() { }
@Test void aSecondPlacementOnTheSameTargetNeedsItsOwnContainerDirectoryHealthAndMetricsPorts() { } // 409, OR-110
@Test void thePlacementOutsideTheTargetsEnvelopeIs422NamingTheField() { }
@Test void aPendingOrAbandonedInstanceIs404() { }
@Test void bothVerbsAreRequiredAndTheSecondDenialIs403() { }       // instance.bindTarget @instance, target.use @target
@Test void anInstanceScopedBindTargetGrantSufficesAndAnotherInstancesDoesNot() { } // OQ-8.3s (i)
@Test void theProbeAndMetricsComeFromTheDeclaredPackageVersionNeverTheBody() { }
@Test void aPackageDeclaringSolaceEventMetricsIsRefused409() { }    // OQ-8.9 (a)
@Test void theNewPlacementIsPolledAndItsPullMetricsCollected() { }  // health row and pull series appear
@Test void theLadderDropsToRenderedAndConfigStateIsAnnounced() { }  // OQ-8.10 (a): read, reached_rung and event agree
@Test void anUndeployedContainerReadsUnpolledThenUnreachableAndRaisesAttention() { }
@Test void oneAuditLineIsWrittenPerCall() { }
@Test void anotherTenantsInstanceOrTargetIsIndistinguishableFrom404() { } // I5
@Test void addPlacementWaitsForAConcurrentLifecycleWriterAndRefusesIfTheInstanceLeftActive() { } // race IT, with a hold

// RetirePlacementIT (OR-121: OQ-8.4 under OPS-Q12 R-1)
@Test void aPlacementIsRetiredAndItsContainerIsLeftRunning() { }          // host untouched; response and audit say so
@Test void withoutWorkloadLeaveRunningIs400() { }
@Test void theLastLivePlacementIs409() { }                                // that is undeclare (plan 012)
@Test void aPlacementNamedByANonTerminalRequestOrARunningFleetReservationIs409() { }
@Test void theFreedContainerNameDirectoryAndPortsCanBeAddedAgain() { }    // addPlacement re-uses them
@Test void withoutInstanceBindTargetAtTheInstanceIs403() { }
@Test void retireTakesTheSameInstanceRowLockAsAddPlacement() { }          // race with addPlacement: one winner

// moved from S4 Step 1 — the live-placement enumerator
@Test void anInstanceWideStopAddressesExactlyTheLivePlacements() { }
@Test void aRetiredPlacementInTheHistoryNoLongerFailsTheInstanceWideStop() { }    // R6's defect, now reachable via retire
@Test void anInstanceWithNoLivePlacementLandsStopFailedAsBefore() { }              // stop.no.known.placement
@Test void theInstanceWideSentinelIsStillAcceptedForAStop() { }
```

- [x] **Step 1a (moved from S4): `StopApplyIT` moves to the registry**, exactly as S4 Step 1a below describes. *(Written; not run, see S3 Results.)*

- [ ] **Step 2:** Run; fail. *(UNTICKED, stated as it was: red first was NOT observed for the S3 unit tests, the implementation preceded them; each is instead proven by the mutation that turns it red.)*
- [x] **Step 3:** Implement in one DP transaction, in `reserve`'s lock order (instance, placement, target row,
  endpoints — `V016…sql:99-100`). **First, lock the `instance_state` row `FOR SHARE` and re-check `lifecycle = active`**
  inside the transaction. Today no path moves an active instance out of `active` (`abandon` refuses one,
  `InventoryService.java:166`), so the race partner is **plan 012's undeclare**. Plan 012 must take the same row
  `FOR UPDATE` before it retires placements; S3's race IT simulates that writer with a test transaction holding
  `FOR UPDATE` and flipping the lifecycle. No database guard ties a placement insert to an active instance; the lock is
  the guard, and the mutation below proves it.
- [x] **Step 4:** Green; register the operation, flip the inventory row and the Postman markers to served; run
  `./contracts/run-checks.sh --strict` and the full reactor build.
- [x] **Step 4a (moved from S4): the enumerator.** Delete both `placementsOf`; the instance-wide stop reads
  `PlacementRows.live` through a narrow port; then S4 Step 5's fence (`PlacementEnumerationTest`, with its planted
  fixture), unchanged in content.
- [ ] **Step 5:** *(UNTICKED: 11 of 11 unit-level mutations fired offline (S3 Results); the integration-level mutations, which need the data plane's `FOR NO KEY UPDATE` lock and the host-side counters, were NOT run. OWED.)* Prove they fire: drop the second `require`; read the probe from the body; skip the target-row lock
  (the concurrent-port test must go red); **drop the `FOR SHARE` lock (the race IT must go red)**; skip the push-package
  check; skip the ladder re-settle; **let `retirePlacement` retire the last live placement (red); restore the history
  enumerator (the retired-placement stop test goes red, and the fence reports it).**
- [x] **Step 6:** Commit `feat: addPlacement and retirePlacement; the stop enumerates live placements`.

**S3 Results (2026-10-08, wave 2 lane A) — implemented; verification INCOMPLETE, stated exactly (Docker was down for the whole
sitting).** *(W6 note: every Docker-gated IT named below as owed ran green in the wave 2 integration build or later, with one test-assertion fix in `StopApplyIT`; the IT-level mutation proofs did not run and stay owed. Deviation (6) is closed by `OR-125` (1), see the actor paragraph after the fix round. See the closing Results, section 5.)* **Built:** `addPlacement` (`POST /dp/instances/{instanceId}/placements`, `instance.bindTarget` at the instance +
`target.use` at the body's target) and `retirePlacement` (`DELETE .../placements/{placementId}?workload=leave-running`,
`instance.bindTarget`), in `console.yaml` (two operations, `PlacementId` parameter, `PlacementRetirement` schema; the three
"from plan 008 S3" qualifiers on `stopInstance` and `steps[].placementId` are now present tense), `ConsoleOperations`,
`ENDPOINT-INVENTORY.md` (48 operations, two served rows; `:384`'s stale `stop.replica.label.collision` fixed), the regenerated
Postman collection (48 operations, 47 served), `ProductionAuthenticationIT`'s route set (+2) and `postman/README.md`'s counts;
`dp-api` (`AddPlacementCommand`, `InstanceInventoryService.addPlacement`/`retirePlacement` as `default` methods so the test fakes
keep compiling, five refusal exceptions); `dp-core` (`InventoryService` canonical constructor now 10 arguments: + `LadderRecorder`,
`ApplyEventEmitter`; `InstanceRows.lockForPlacementChange`/`touch`; `PlacementRows.retire`/`lockApplyScope`/`inFlightOn`; the
shared ladder settle `ApplyEventEmitter.settleLadder`, which `OutcomeService` now calls too; `LivePlacements` in `apply.engine`
and `RegistryLivePlacements` in `inventory`; **both `placementsOf` deleted**; `ApplyExecutor` takes the live set through the
port, its short constructors failing loudly on an instance-wide stop (`LivePlacements.UNAVAILABLE`) and only
`ProductionApplyConfiguration`, `ApplyEngineHarness`, `ApplyItSupport` and `E2eStack` passing the registry's); the gateway
(`AddPlacementRequest`, `PlacementRetirement`, `PlacementOrchestrator`, `PlacementController`, three `GatewayErrorAdvice`
handlers, `DeclareOrchestrator.requirePlacementGrammar`/`probeOf` made package-private for reuse); `PlacementEnumerationTest` with
two planted fixtures and a legal counterpart. **No migration** (the plan allocated none to S3). **Verified, offline, run:**
`InventoryPlacementOperationsTest` 19/19 (the service with in-memory fakes: ORDER of operations asserted over one call log),
`PlacementControllerTest` 25/25 (the real filter, interceptor and advice, ports faked), `SchemaMirrorLockTest` 45/45 (+4),
`ConsoleOperationsMirrorTest` 6/6, `PlacementEnumerationTest` 7/7, `DeclaredBadRequestsTest` and `MetricsDeclarationConstructorTest`
(both adjusted, below) green, `./contracts/run-checks.sh --strict` green but for check 14's known `ajv/dist/2020` environment gap,
`check-postman.py` OK (48/47/1/0); `mvn -o -f backend/pom.xml test` (surefire only): cp-api 49, mesh-events 160, cp-core 466
(1 error), dp-core 1237 (1 error, 1 skipped), console-gateway 652, launcher 86, arch-tests 89, **0 failures**; the two errors are
`SmControlLockoutRecoveryMainTest` and `IngestServiceContractTest`, each `Container startup failed for image postgres:14`
(Docker). **Red first: NOT observed** for the unit tests (the implementation preceded them in the sitting; honest). Each is
instead proven by the mutation that must turn it red, **11 of 11 fired offline**: skip the push refusal (1 red); skip the stamp
(3); take the instance row before the apply scope in retire (1); skip the in-flight check (2); allow retiring the last placement
(1); skip the ladder settle (4); skip the active re-check under the lock (2); drop `target.use` (3); accept any `workload` (1);
not map the lifecycle race to `404` (1); a history enumerator returned to `apply.persistence` (1, the arch rule). One test *was*
red first for a real reason: `addPlacementRequestIsExactlyTheContractsBodyAndItsPlacementIsDeclaresPlacement` compared the two
placement objects including their prose and failed on the description, so it compares shape.
**Owed (Docker down 2026-10-07 night), none claimed:** the data-plane ITs `AddPlacementIT` (9 tests), `AddPlacementLifecycleRaceIT`
(3), `RetirePlacementIT` (12), the launcher `AddPlacementEndpointIT` (14), the edited `StopApplyIT` (renamed
`anInstanceWideStopAddressesExactlyTheLivePlacementsWhetherOrNotAnythingEverActedOnThem`, new
`aRetiredPlacementInTheHistoryNoLongerFailsTheInstanceWideStop`, `anInstanceWideStopWithNoLivePlacementLandsStopFailed`,
`theInstanceWideSentinelIsStillAcceptedForAStop`, `aLivePlacementWhoseApplyFailedIsStillStoppedByAnInstanceWideStop`) and
`StopEndpointIT` (test 9 now retires the instance's placement through the owner credential first), plus
`ProductionAuthenticationIT`'s route set, `DemoParityChecklistIT`, `InventoryConfigurationIT` and the whole failsafe half. **IT
mutation proofs not run (the predictions in this sentence were WRONG, corrected in the fix-round paragraph below):** remove `lockedActive`'s lock (`AddPlacementLifecycleRaceIT` tests 1 and 2 must go red: the add must
block and then refuse); `FOR SHARE` in its place (test 3 must deadlock); skip the metrics endpoint insert
(`AddPlacementIT.theNewPlacementIsPolled...`); skip `lockApplyScope` (no IT drives a request in between: the order is the unit
test's); restore a history enumerator in `ApplyExecutor` (`StopApplyIT.aRetiredPlacementInTheHistory...` and
`anInstanceWide...WhetherOrNotAnythingEverActedOnThem` must go red). **Deviations from this plan's text,** each stated in
`decisions.md`'s 2026-10-08 rider: (1) the instance row is locked `FOR NO KEY UPDATE`, not `FOR SHARE` (two `FOR SHARE` holders
that both then stamp the row deadlock); `retirePlacement` takes the same lock after the instance's `apply_scope` row, which is
one per instance in the built schema, not per placement. (2) The add and the retire both **stamp** the instance
(`instance_state` is touched), which the plan did not say. (3) The push refusal is made by the data plane from the definitions
the gateway hands in, not by the gateway. (4) The plan said "any other red test is a defect"; three suites needed a named
change beyond `StopApplyIT`: `MetricsDeclarationConstructorTest` (canonical `InventoryService` arity 8 to 10),
`DeclaredBadRequestsTest` (`PlacementController` has one explicit `400` site), and `TargetConnectionMain` (the operator command
calls the longer constructor with `LadderRecorder.none()` and a silent emitter). (5) `retirePlacement`'s response is
`{placementId, workload, instance}`; `ops-501.md`'s "and its last health" is not built.

**S3 review fix round (2026-10-08, wave 2 lane A, one round per wave, `OR-70`) — what changed after the Opus review, stated exactly.**
**Deviation (6), added to the five above, NEEDS THE OWNER (`W2A-1`): neither operation records who did it.** *(CLOSED 2026-10-08 by `OR-125` (1): the owner ruled the actor recorded, and `sm_data` V026 records it. The text that follows is what was true at the fix round.)* `addPlacement` never
reads `AddPlacementCommand.requestedBy()` and `retirePlacement` null-checks it and discards it; `placement` has no `created_by`
or `retired_by`, there is no control-plane write, and no per-call audit line is written for either route (the authorization
path writes none and the gateway writes none), so `BE-D53`'s "no CP write beyond the authorization audit line" described a
record that does not exist, and this plan's Step 1 tests `oneAuditLineIsWrittenPerCall` and "response and audit say so" could
not be written and were not. **No migration was added** (S3 was allocated none). **Options for the owner:** `created_by` /
`retired_by` columns on `placement`, or an `sm_data` placement-event row, in a later migration. Plan 012 T5 covers retirement of
an INSTANCE only, so adds stay unrecorded even after it. `PlacementOrchestrator`'s javadoc, `AddPlacementCommand`,
`InstanceInventoryService`, `console.yaml` (both operations), `ENDPOINT-INVENTORY.md` and `decisions.md` (a rider under `BE-D53`
and the changelog's rider 2) now say so; none claims a record.
**Fixed in code.** (`W2A-2`) `addPlacement` now takes `placements.lockApplyScope` first and then the instance row (retire's and
admission's order) and is refused `409` (`InstanceRequestInFlightException`, new `PlacementRows.instanceWideInFlight`) while a
`REQUESTED`, `RUNNING` or `APPLY_INDETERMINATE` instance-wide request exists: a stop reads the live set once, when it starts
running. `console.yaml`'s `stopInstance` now says "every instance-wide stop that starts running AFTER it exists". (`W2A-3`) NOT
closed in code, recorded: closing it means a liveness check inside `AdmissionService`, the journal-only engine, which has no
placement port (a new refusal reason and a constructor change across the engine's ITs), and `submitApply`'s admission runs in
its own `REQUIRES_NEW` transaction, so an outer `apply_scope` lock taken by the submit would deadlock against it. The window and
its visible failure (preflight; a failed request or a fleet ending `COMPLETED_WITH_FAILURES`/halting; nothing reaches the host)
are in `console.yaml`'s `retirePlacement` description and the rider. (`W2A-6`) the stamp's effect on an open designer session,
`every time` becoming `whenever they interleave`, and the inventory rows' test column now reads "written, NOT YET RUN".
**Tests changed or added.** Run, offline: `InventoryPlacementOperationsTest` 21/21 (+2: `anInstanceWideRequestInFlightRefusesTheAddUnderTheLocksAndWritesNothing`
asserts the exact call order `find-target, begin, lock-apply-scope, lock-instance, instance-wide-in-flight?` and no write;
`aRequestOnOnePlacementDoesNotRefuseTheAdd`; the two existing order assertions now include `lock-apply-scope`), red first by
mutation (check disabled: 2 failures), `PlacementControllerTest` 26/26 (+1, `anInstanceWideRequestInFlightIs409AndNamesNoRequest`).
Reactor `mvn -o -f backend/pom.xml test` (surefire only): cp-api 49, mesh-events 160, cp-core 466 (1 error), dp-core 1239 (1 error,
1 skipped), console-gateway 653, launcher 86, arch-tests 89, **0 failures**; the two errors are the same two Docker ones
(`SmControlLockoutRecoveryMainTest`, `IngestServiceContractTest`). `contracts/run-checks.sh` PASSED but check 14 (the frontend's
Ajv, an environment gap); `check-postman.py` OK (48/47/1/0), Postman regenerated.
**Owed, written and compiled, NOT run (Docker down), none claimed:** `AddPlacementIT.anInstanceWideRequestInFlightRefusesTheAddAndWritesNothing`
(a `REQUESTED` and a `RUNNING` instance-wide stop each refuse the add with the row counts, the stamp and the endpoints unmoved) and
`aLandedInstanceWideStopOrARequestOnOnePlacementDoesNotRefuseTheAdd` (a `STOPPED` stop, and a `RUNNING` apply on one placement, do not);
`AddPlacementLifecycleRaceIT` (all three tests rewritten: the writer holds `FOR NO KEY UPDATE`, see `W2A-4`) and
`twoAddsRacingOnOneTargetQueueOnTheScopeAndBothLandOnceTheTargetIsReleased` (a lock on the target row holds the first add at the
placement insert, the IT waits until two backends are observed waiting, then releases: both land, no deadlock across `apply_scope`,
instance row and target row); `RetirePlacementIT.retireAndAddQueueBehindOneInstanceWriterAndBothLand` (replaces
`retireTakesTheSameInstanceRowLockAsAddPlacement`; the scope row is pre-created and the writer holds `FOR NO KEY UPDATE`, so a
foreign key cannot be mistaken for the lock) and `retireWaitsForALifecycleWriterAndRefusesIfTheInstanceLeftActive` (the proof
that retire takes the instance lock: `InstanceNotActiveException`, not the stamp's `IllegalStateException`);
`HealthPollerIT.anUndeployedAddedPlacementReadsUnpolledThenUnreachableAndRaisesAttention` (`W2A-5`: `getHealth` counts both
placements unpolled and attention lists nothing, then after one poll the added placement reads `UNREACHABLE` and is listed alone);
`AddPlacementEndpointIT.anInstanceScopedBindTargetGrantSufficesAtThatInstanceAndNotAtItsSibling` (`W2A-5`, OQ-8.3s (i): two custom
single-verb roles, `instance.bindTarget` at one instance's scope and `target.use` at the environment: `201` there, `403` or `404` at the
sibling instance of the same environment, nothing written); plus everything the first Owed list names, which stands.
**Corrected IT mutation predictions (`W2A-4`; the first Results paragraph's were wrong).** Removing `lockedActive`'s lock (a plain
read in its place): `AddPlacementLifecycleRaceIT`'s FIRST test must go red, on the exception TYPE only (the stamp's own
`lifecycle = 'active'` predicate still refuses, as an `IllegalStateException`, so the lock is what makes the refusal clean and
decided before any write, and the stamp is a backstop behind it); the SECOND test stays GREEN (the add merely waits at the stamp), so
it is a liveness test, not a proof of the lock; `RetirePlacementIT.retireWaitsForALifecycleWriterAndRefusesIfTheInstanceLeftActive`
goes red the same way for retire. `FOR SHARE` in its place no longer makes the add race deadlock, because two adds now serialize on
`apply_scope` first; that mutation has no IT (the deadlock needs a writer that stamps `instance_state` without `apply_scope`, such as a
generation mint, and none is driven). Skipping `lockApplyScope` in `addPlacement`: `AddPlacementIT.anInstanceWideRequestInFlight...`
stays green unless a request is admitted between the check and the insert, which no IT drives; the order is the unit test's.

**OQ-8.4 — ruled (R-1), built above.** Plan 012 T3 creates `instance_retirement`; plan 012 T5 then makes
`retirePlacement` also write an `instance_retirement` row carrying `placement_id` (`BE-D53`). **If OQ-8.1 (c):** `submitInstanceRollout` here (it reads `PlacementRows.live` and calls the fleet's submit,
with `instance.restart` when the strategy restarts). **If OQ-8.9 (b):** drop the push refusal and add the documented
sentence. **If OQ-8.10 (b):** `addPlacement` also requires `config.apply` and admits the apply.

**OR-125 (1) Results (2026-10-08, wave 5 lane A) — `addPlacement` and `retirePlacement` record their actor (`W2A-1`).** The owner ruled the actor
recorded (`OR-125` (1)); the shape chosen is the smaller of the two the ruling allowed. `sm_data` **V026** adds two **nullable columns** to
`placement`, `created_by` and `retired_by`, not a placement-event table: a placement is added once and retired at most once, so each fact is one
value of a row that already changes once, and a second table would need its own key, grants, trigger and tenant-scoped read for no gain. They hold
an actor **id** (uuid, like `target_connection.created_by`), so the agent gateway writes an agent's id into the same two columns later
(`OR-128` (5)) with no further migration and no kind column. `created_by` is written at insert by `addPlacement` and by the declare for its own
placement; `retired_by` is written with `retired_at` by `retirePlacement` (`PlacementRows.retire` gains the parameter; `AddPlacementCommand.requestedBy`
and `retirePlacement`'s `requestedBy` were already carried, so no API changed). A `CHECK` ties `retired_by` to `retired_at`; the V007 immutability
trigger is widened to allow exactly "set `retired_at` and `retired_by` together, once" and still refuses every other change, `created_by` included, to
every writer (the superuser bypasses grants, not the trigger); the runtime role's UPDATE grant widens by `retired_by` only. **`NULL` means "not
attributed", never a guess:** every row that pre-dates V026 (the principal was discarded and cannot be recovered; no back-fill, no data rewrite) and
any retirement by a sweep or by undeclaring an instance (`retireAll`, which is not one principal's act on one placement). The inventory readiness stage
now also refuses a V025 schema. **Proof:** `InventoryPlacementOperationsTest` (24, two new: the actor reaches the row on add and on retire),
`InventoryReadinessTest` (5, one new), `PlacementActorIT` (5, new: the columns on the real rows, the grant, the trigger, the `CHECK` where the trigger
is stepped around, a sweep leaving `retired_by` null). RED was seen first: 1 of 24 failed on the unit side, 4 of 4 of the IT errored on a missing
column, and 1 of 5 failed on the readiness test. **Mutations:** `requestedBy` replaced by `null` in the retire call and `createdBy` forced `null` in
`placementOf` kill the two unit tests; dropping `'retired_by'` from the trigger's exclusion fails 2 of 5 of `PlacementActorIT` (the retire itself is
refused as "immutable"); dropping the `CHECK` fails the stepped-around test (1 of 5). The pinned counts moved (`sm_data` 26 migrations; 135 CHECKs;
the runtime role's updatable `placement` columns). The "not recorded" sentences in `console.yaml` (both operations), `ENDPOINT-INVENTORY.md`,
`console-integration.md` rows 52b and 52c and the operations runbook now say what is recorded; `BE-D47`/`BE-D53` rider 3 in the changelog.

### S4 — The referential guard and the reconciliation record (the enumerator and the fence moved to S3)

**Files** (after `OR-121` moved the enumerator, `StopApplyIT` and the fence to S3): if OQ-8.5 (a) — ruled (a):
`…/sm_data/V022__apply_request_placement_guard.sql`, `InventoryFixtures.java` (a `registered(scope)` fixture that creates
a fresh target and the scope's placement on it), the ≈ 38 admitting `dp-core` IT/fixture files that register no
placement today (measured list in the slice's Step 0), `ApplyPlacementReferentialIT.java`;
`docs/design/backend/agent-gateway/RECONCILIATION-008.md`. `ApplyExecutor`/`AdmissionService` are **not** touched
here any more. Step 1's stop tests, Step 1a and Step 5 below are executed in S3 and kept here only so their text stays
readable.

- [x] **Step 0:** Re-measure: the admitting ITs that register no placement (at `36af1df6`: 52 `dp-core` IT/fixture files
  write `apply_request`, 38 register no placement); MF-3, MF-4, MF-5 if OQ-8.5 (a). Record them.
- [x] **Step 1: Failing tests.** *(The guard-specific ones are in `ApplyPlacementReferentialIT` and `ApplyPlacementGuardUpgradeIT`; the stop tests were S3's.)*

```java
@Test void anInstanceWideStopAddressesExactlyTheLivePlacements() { }
@Test void aRetiredPlacementInTheHistoryNoLongerFailsTheInstanceWideStop() { }    // R6's defect
@Test void anInstanceWithNoLivePlacementLandsStopFailedAsBefore() { }              // stop.no.known.placement
@Test void anApplyRequestCannotNameAPlacementOfAnotherInstance() { }               // 23503, if (a)
@Test void anApplyRequestCannotNameAnUnknownPlacement() { }                        // 23503, if (a)
@Test void aRetiredPlacementPassesTheFkAndIsRefusedAtPreflight() { }                // the FK is not liveness, if (a)
@Test void theInstanceWideSentinelIsStillAcceptedForAStop() { }
@Test void placementWatermarkStillRefusesTheSentinel() { }                          // real_placements_only
```

- [x] **Step 1a (executed in S3, with Step 5; moved there by `OR-121`): `StopApplyIT` moves to the registry.** Its `setUp` (`:63-70`) uses random, unregistered placements with
  `InventoryFixtures.declare(tenant, instance)`, so a live-registry enumerator finds nothing. Register `placementA` and
  `placementB` as real `placement` rows on **two separate targets** (`InventoryFixtures.target` + `placement`), and
  rewrite `anInstanceWideStopAddressesEveryPlacementTheInstanceHasAHistoryWith` (`:151`) as
  `…EveryLivePlacement` and `aPlacementWhoseApplyFailedIsStillFoundByAnInstanceWideStop` (`:219`) against live
  placements. `anInstanceWideStopWithNoKnownPlacementLandsStopFailed` (`:201`) stays as written. These are **expected,
  named changes**; any other red test is a defect.
- [x] **Step 2:** Run; fail. **Step 3:** Implement; V022 by the mechanism MF-3 measured, in the shape MF-4 decided
  (plain FK, or `NOT VALID` + `VALIDATE CONSTRAINT` after the stated clean-up), with its lock class stated in the
  migration header (MF-5). **Step 4:** Green, full `dp-core`. *(Green on a PostgreSQL 16 harness except nine environment failures; see S4 Results. `postgres:14` run OWED.)*
- [x] **Step 5 (executed in S3): The fence — "no enumerator of placements from apply_request history".** The original "no class depends
  on `PlacementRows` except the named consumers" is false on the current tree: 22 production files reference
  `PlacementRows` (`grep -rl PlacementRows */src/main`, 2026-10-07), and five already enumerate live placements
  (`CollectionPlanBuilder.java:100`, `PushRowsAdapter.java:72`, `HealthPoller.java:136`, `HealthServiceImpl.java:28`,
  `InventoryService.java:292`). So the rule fences the **history** enumerator instead: an ArchUnit rule that no method of
  `ApplyRequestRepository` (or any class in `apply.persistence`) returns `List<PlacementId>`/`Collection<PlacementId>`/
  `Set<PlacementId>`, plus the deletion of both `placementsOf`. Asserted by type, never by counting text matches
  (`L12`), with a **planted-violation fixture** (a test-source repository with such a method) that the rule must
  report. Live enumeration stays `PlacementRows.live`'s, wherever it is needed. The existing
  `jdbcIsConfinedToPersistencePackages` already keeps persistence types out of `dp-api` (R8); cite it, do not duplicate it.
- [x] **Step 6: `RECONCILIATION-008.md`** — per item, routing and not resolving: the new journal shape (S2);
  `sm_data.placement` is the registry for both paths, `agent_replica`'s relation open (OQ-8.6 (b)); `BE-D22`'s seven
  operations vs `design.md` §3.5's one; `BE-D24`'s writer access mode; `AGW-20`'s actual-state projection still absent.
  ⛔ Do not edit `agent-gateway/design.md` or `decisions.md`.
- [x] **Step 7 (done differently):** the replay baseline was NOT re-recorded, because it covers the first six `sm_data` files only and V024 is not among them (see S4 Results); the commits are `feat(dp-core): the apply_request placement guard (sm_data V024, BE-D48)` and `docs(backend): plan 008 S4 record, V024 runbook section, Agent Gateway reconciliation`. The enumerator half of the original commit title shipped in S3.

*(W6 note on S4: the `postgres:14` run this Results asked for happened in the wave 3 integration build: `ApplyPlacementReferentialIT` 12/12 and `ApplyPlacementGuardUpgradeIT` 4/4 are green there, so the foreign key on a `STORED` generated column (MF-3) is accepted by PostgreSQL 14. Still owed: the V006/V008 fold mutations on 14, MF-5 on 14, MF-4. See the closing Results, section 5.)*

**S4 Results (2026-10-08, wave 3 lane A) — implemented; verification on a PostgreSQL 16 harness, `postgres:14` and Docker-gated ITs OWED, stated exactly (Testcontainers could not start a `postgres:14` container all sitting; the recorded evidence is that Docker's availability probe still answered, since `ManualApplyDockerWalkthroughIT` (`disabledWithoutDocker = true`) ran rather than skipped, so the state was "daemon reachable for the probe, container start failed", not "no daemon").**
**Built:** `sm_data` **V024** (`apply_request_placement_guard`; `V022` in this plan's older text): `UNIQUE (tenant_id, instance_id, placement_id)` on
`placement`, a `STORED` generated `apply_request.placement_ref = NULLIF(placement_id, nil)`, and the validated
`FOREIGN KEY (tenant_id, instance_id, placement_ref) REFERENCES placement (...) MATCH SIMPLE` named `apply_request_placement_registered`;
a pre-check that **refuses to run, rolls back and changes nothing** (a fixed sentence naming the runbook, no row value, `I6`) while any
request names an unregistered or another instance's placement; the `docs/runbooks/operations.md` section that sentence names;
`RECONCILIATION-008.md`; `InventoryFixtures.registered(scope)` (idempotent, a fresh target per placement) and its overloads, `ApplyRows.insertRequest`
now registering the placement it names (`insertRequestUnregistered` for the guard's own refusal tests), the launcher's
`DeclaredInstances.registered`; 22 `dp-core` admitting ITs and 8 launcher ITs moved onto them. **No contract, `console.yaml` or Postman edit** (S4 changes
no operation), so `run-checks.sh` was not re-run for this slice.
**A defect the harness found that the plan did not predict, and the fix.** A `STORED` generated column is computed *after* BEFORE triggers, and
inside one `NEW.placement_ref` is NULL (measured on 16 only: `old.ref IS NULL = f`, `new.ref IS NULL = t`; the PostgreSQL 14 "Generated Columns" page says "it is not allowed to access generated columns in BEFORE triggers" but does not specify the value, so whether the two fold tests discriminate on 14 is OWED, item (1) below). Two published functions compare `to_jsonb(NEW)` with `to_jsonb(OLD)`: V006's `forbid_terminal_regression`
(it **refused every `attempt_summary` fold** on a terminal request: `AttemptIT.theCompactionPair…` went red) and V008's
`stamp_apply_request_state_version` (it would have drawn a new `state_version`, and taken the `instance_state` lock, on **every** UPDATE, a silent change to
OR-35's "stamp when it changes" that no test of the first run caught). V024 therefore redefines both with `CREATE OR REPLACE`, bodies otherwise identical,
`placement_ref` also leaving the comparison. Two new ITs pin it. Not a `MF-1..5` outcome; a consequence of MF-3's chosen mechanism.
**MF-3:** the FK on a `STORED` generated column is accepted (PostgreSQL 16.15; **14 owed**). **MF-4:** NOT measured (the owner's `OR-118` database is not reachable from a lane);
the migration refuses rather than assumes, and the runbook gives the count query. **MF-5:** `ADD COLUMN ... GENERATED ... STORED` **rewrites** `apply_request`
(`pg_relation_filenode` 17921 -> 17927 on 16.15, re-measured this sitting; 14 owed); the header states `ACCESS EXCLUSIVE` held to the end of the transaction and a maintenance window.
**Red first (observed):** with the migration and the two new ITs in place and the admitting ITs not yet moved, the full `dp-core` IT run on the harness was red in **25
classes** (the `23503` on `apply_request_placement_registered` from `AdmissionService`'s insert), which is HF11's prediction exactly; the two fold tests were red
before V024 redefined the functions. **Verified, run:** *indicative, PG16 harness (native Homebrew PostgreSQL 16.15 behind a temporary `SmDataPostgres` shim, reverted
before commit; `postgres:14` run owed)*: full `dp-core` failsafe **1292 tests, 5 failures + 4 errors, all environment**: `SmDataBootstrapIT` x2 (PG14 floor assertion; the
`REVOKE ... ON SCHEMA public` warning semantics), `SmMetricsMigratorIT.migrationMain…:107` and `MetricsPartitionMainIT.partitionMain…:500` (the same `REVOKE ... sm_metrics` 42501 shape),
`SmMetricsSchemaIT` x2 (`VACUUM` permission text; `metrics_rollup_*` relation), `PushBrokerIT` and `JcsmpSampleSubscriberIT` (no Solace broker container), `MetricsStorageBenchmarkLocalIT`
(`postgres:14` container). **The first six were re-run with V024 removed from source and from `target/classes` and fail identically** (plus the count pins, which proves V024 was absent), so none is V024's.
`ApplyPlacementReferentialIT` 12/12 and `ApplyPlacementGuardUpgradeIT` 3/3 green. **Mutations, all fired on the harness:** drop the FK (3 failures + 1 error); FK without `instance_id`
(`anApplyRequestCannotNameAPlacementOfAnotherInstance`); FK on `placement_id` with no `NULLIF` (the sentinel stop refused); drop `placement_ref` from V006's comparison (both fold tests);
drop it from V008's comparison (the same two: the wrongly bumped stamp then trips V006's guard, the order-independence V008's header describes); remove the pre-check (both refusal tests in the upgrade IT).
Offline `mvn -o -f backend/pom.xml test`: cp-api 49, mesh-events 160, cp-core 491 (1 error), dp-core 1255 (1 error, 1 skipped), console-gateway 667, launcher 86, arch-tests 89; **0 failures**; the two errors are
`SmControlLockoutRecoveryMainTest` and `IngestServiceContractTest`, each `Container startup failed for image postgres:14` (as in S3). `CHECKSUMS.sha256` carries V024's line. The DDL replay baseline and `SketchMirrorTest` cover the first six
files only, so are unchanged. **Also fixed here, not V024's:** `StopApplyIT.aLivePlacementWhoseApplyFailedIsStillStoppedByAnInstanceWideStop` (S3's, never run when written) was red on the harness for a test reason: `setUp` registers
placement B, so the live-registry stop addresses B, and the test gave it no adapter; it now supplies one and expects two stop intents.
**Pinned counts:** `sm_data` is 24 migrations in `ApplyPersistenceConfigurationIT`, `SmDataBootstrapIT` and `SmMetricsMigratorIT`; lane B's V025 makes it 25 at integration (and `backend/README.md:61`'s `V001`-`V023`, left untouched to avoid a four-lane conflict).
**OWED (could not run tonight; superseded by the fix-round list below, which is the current one):** see **S4 fix round (W3 review)**.

**S4 fix round (W3 review, wave 3 lane A, 2026-10-08) — findings W3A-1..W3A-5, all fixed; nothing needs an owner ruling.** Docker was down again: no Testcontainers, no IT run. Offline only: `mvn -o -q -f backend/pom.xml test-compile` clean; new pure test `ApplyPersistenceReadinessTest` 5/5 green (`mvn -o -pl dp-core -am`; red first as a compile failure with the main change stashed, and a mutation, `if (false && placementGuard != 1)`, turned `aV023SchemaWithoutTheValidatedPlacementGuardIsRefusedNamingTheRunbookSection` red); `scripts/check-frozen-migrations.py` PASS (V024 not yet anchored, as expected).
- **W3A-1 (MAJOR):** `ProductionWorkerLiveIT`'s third test admitted `UUID.randomUUID()`, a placement nothing registers, which V024's FK now refuses at admission (23503 from `AdmissionService`). Kept the intent (the catalog refuses at execution): the Seed gained `insertPlacement` and `retiredPlacement()`, the test applies to a REGISTERED, RETIRED placement of the instance (passes the FK, refused by the catalog as `placement.unresolvable`; the pair `ApplyPlacementReferentialIT.aRetiredPlacementPassesTheFkAndIsRefusedAtPreflight` uses) and is renamed `aPlacementTheCatalogDoesNotResolveIsRefusedBeforeAnyConnection`. Re-measured by grep over every test that admits or inserts `apply_request` (`.admit(`, `INSERT INTO sm_data.apply_request`, `ApplyCommand(`; 63 files): the rest either register (`registered`, `insertRequest`, `registerPlacement`, `INSERT INTO sm_data.placement`), use the instance-wide sentinel (`ApplyPersistenceConfigurationIT`, `ConfigRecordServiceIT`, `OutcomeTransitionsIT`, `IndeterminateReconcilerIT` stops), or take a placement from a declared instance (`MetricsDeclareIT`, `PlacementItSupport`, `RollbackEndpointIT`). `ApplyOutcomePublishIT` builds a row to publish, never inserts. That grep is a reading, not a run.
- **W3A-2 (MINOR):** `CacheApplyWalkthroughIT` (`-Dsm.demo.cache=true`) now builds its scope with `InventoryFixtures.registered(...)`. Re-checked every property/env-gated IT that admits: it was the only one (`ApplyOutcomePublishIT` is `broker.host`-gated and inserts nothing).
- **W3A-3 (MINOR):** `ApplyPersistenceConfiguration.smDataSchemaReadiness` gained `PLACEMENT_GUARD_QUERY` (the constraint `apply_request_placement_registered` exists on `sm_data.apply_request` and `convalidated`; `pg_constraint` is readable by every role), refusing with a message that names V024 and the runbook section "Upgrade across the apply-request placement guard", as the V021 probe does. A mismatch was a silent absence of BE-D48's second enforcer; it is now a refusal to go ready.
- **W3A-4 (NIT):** V024's header comment (unpublished, edited in place, `CHECKSUMS.sha256` line regenerated: `5e067760…`) and `ApplyPlacementReferentialIT`'s javadoc now say "measured on 16 only; the 14 docs forbid the access but do not specify the value", and that the redefinition is correct whichever value NEW carries.
- **W3A-5 (NIT):** Step 1a marked executed in S3; Step 7 annotated with what was done; the Docker condition stated precisely above; `RECONCILIATION-008.md`'s Sources name the five decisions instead of a range.

**OWED, current (class#method: what it proves; none run):**
1. The whole `dp-core` IT suite on `postgres:14` through Testcontainers, above all `ApplyPlacementReferentialIT` (MF-3: the FK on a STORED generated column on 14) and `ApplyPlacementGuardUpgradeIT` (MF-5 rewrite, the refusal, the clean upgrade, and the new `theNewBinaryRefusesToGoReadyOnAV023SchemaAndPassesOnceV024Ran`: the probe refuses a real V023 schema and passes after V024).
2. On `postgres:14`, the two V006/V008 mutations (delete `- 'placement_ref'` from V006's `forbid_terminal_regression` comparison; from V008's `stamp_apply_request_state_version` comparison) must turn `ApplyPlacementReferentialIT#theCompactionFoldIsStillTheOneLegalWriteOnATerminalRequest` and `#aFoldOrANoOpUpdateStillDoesNotDrawAStateVersion` red. If they stay green on 14, NEW carries the stale value there and the tests prove nothing on the project's database (W3A-4); the redefinition is correct either way.
3. The eight moved launcher ITs (`ApplyWorkerLifecycleIT`, `ApplyWorkerBoundedClaimIT`, `ApplyWorkerPoisonAndDrainIT`, `NoNewClaimsAfterStopIT`, `GracefulShutdownWithinGraceLandsTerminalIT`, `WorkerSingletonLockHeldOnUndrainedShutdownIT`, `SecondWorkerPoolRefusedBeforeSweepIT`, `ObserveBackfillIT`) and the ninth, `ProductionWorkerLiveIT#aPlacementTheCatalogDoesNotResolveIsRefusedBeforeAnyConnection` (a registered, retired placement is admitted and lands FAILED_NO_EFFECT with `placement.unresolvable`; its two siblings that use the Seed's live placement must still pass). Compile only so far.
4. `CacheApplyWalkthroughIT` with `-Dsm.demo.cache=true` against the compose Cache: admission now succeeds on the registered placement and the walkthrough reaches the real container.
5. MF-4 on the owner's `OR-118` database (the count query in the runbook).
6. `ApplyPlacementGuardUpgradeIT` and the readiness IT in `IntentIdentityUpgradeIT` (`theNewBinaryRefusesToGoReadyOnAV020SchemaAndPassesOnceV021Ran`) together after the readiness change: the latter's final `afterPropertiesSet()` now also needs V024, which `upgrade()` (to latest) provides.


### S5 — Two placements end to end, the live gate, and close

**Files:** `launcher` ITs `MultiPlacementEndToEndIT`; `docs/runbooks/` (multi-placement, re-target, upgrade-drain
notes); `README.md`, `backend/README.md` (`OR-114`); this plan's Results.

- [x] **Step 1: The matrix, over HTTP, two placements P1/P2 on two targets with the same container name** *(written and compiled as `MultiPlacementEndToEndIT`, NOT RUN: Docker was down; see S5 Results)* (in-memory or
  fake SSH adapter; there is no local SSH harness, plan 010 T6.6): declare + `addPlacement` (ladder → `rendered`,
  `instance.config-state` emitted, P2 unpolled then polled); stage g7; fleet over both
  `{batchSize:1, onFailure: halt-remaining}` → both `APPLIED`, ladder `persisted` only after P2; S-b → fleet
  `COMPLETED_WITH_FAILURES`, ladder at P2's rung; S-c rollback P1 → ladder back to `rendered` until P2 follows (OQ-8.7);
  `acceptRuntimeConfig` on P2 only; `getHealth` lists both; **pull-path** metrics series per placement; S-e →
  `addPlacement` on a push-metrics package is `409` (OQ-8.9 (a)); S-d instance-wide stop → `STOPPED`, two steps with their
  own `placementId`; a per-placement `instance.rollout`/`instance.apply-outcome` per member.
- [x] **Step 2: The live gate on the E2E host** *(2026-10-09 note: L2 PASSED on the second attempt; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09). The text that follows is the earlier status, kept as written: procedure and code written, NOT RUN. L2 is OWED, pending an owner decision on running the live gates: `MultiPlacementLiveIT`, `MultiPlacementRehearsalIT`, `run-e2e-multi-placement.sh`, the runbook section "Live gate L2")* (`OR-110` allows a second Cache container): add a second placement of
  the existing instance on the same target (own container name, directory, health port, metrics ports); fleet g→both;
  stop P2; instance-wide stop; restart both; **restore the host's original state** (keyed hash of the config file, as
  `OR-108`'s T6.6 rule) and leave the original Cache working. Coordinates stay outside the repo. Finally **retire the
  added placement** with `retirePlacement?workload=leave-running` after its container is removed, so the E2E database
  holds one live placement again; record both in Results.
- [ ] **Step 3: Full gate** *(UNTICKED. Surefire and failsafe ran in full on `postgres:14` in each wave's integration build, with test failures ignored by the flag, and every failure was classified (Results, section 4); `./contracts/run-checks.sh --strict` passed in waves 1, 2, 3 and 5. A `clean verify` with no failure-ignore flag at the final head, and the DDL replay with drift reported, belong to the final gate after the Fable review.)*. `mvn -f backend/pom.xml clean verify`; `./contracts/run-checks.sh --strict`; DDL replay
  with drift reported; `git diff --check`.
- [x] **Step 4: Register.** `BE-D23` superseded, never edited; READMEs; `console-integration.md` §12.1. *(done: see S5 Results for what was edited and what was not)*
- [x] **Step 5: Results** *(S5 Results below; the items it lists from S2 to S4 stay in those slices' Results)* — R4's re-measurement, MF-1..5, S2 Step 5's counts and the two inversions, S4 Step 1a's
  `StopApplyIT` changes, every mutation and whether it fired, Docker state before/after, every skip with its reason.
- [ ] **Step 6: The combined Fable 5.1 review** *(OWED; not part of the implementer's lane)* (`OR-70`). It must test for: only one `BE-D23` clause closed; a weakened
  constraint; a request in flight across V021 that cannot resume; a history enumerator of placements; a lookup missing
  the instance; an `addPlacement` that reads the probe or a host from the body, or that races a lifecycle change; a
  push-metrics instance silently given two placements; a gateway path to `sm_data`; the Agent Gateway described as
  implemented or reconciled.

*(W6 note on S5: `MultiPlacementEndToEndIT` 11/11 and `MultiPlacementRehearsalIT` 10/10 ran green on `postgres:14` with the local broker image in the wave 3+4 integration build and again in wave 5; `MultiPlacementLiveIT`, the live gate L2, is skipped by its gate and OWED. See the closing Results.)* *(2026-10-09 note: `MultiPlacementLiveIT` ran and PASSED, 10 tests, on the second attempt; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09).)*

**S5 Results (2026-10-08, wave 4 lane A) — written and compiled; the end-to-end IT, the rehearsal and the live gate L2 are NOT RUN, stated exactly (Docker was down for the whole sitting, and no host, broker or `~/.sm-local` was touched).**

**Built (all new files; no existing source or test file was edited, no `console.yaml`, inventory, Postman, migration or `decisions.md` change).**
- `backend/launcher/src/test/java/com/solace/sm/launcher/MultiPlacementEndToEndIT.java` (Step 1): the real application over HTTP and PostgreSQL, ten ordered tests, two placements on two targets of one environment with the SAME container name (`sm-orders`), an in-memory host per placement, the REAL renderer, the configured emitter (so rollout notices are real), the real health poller driven by `pollOnce()` against two loopback stubs, and the offered notices observed by topic. It walks: declare, apply P1, ladder `persisted`; `addPlacement` (ladder `rendered`, exactly one `instance.config-state`, P2 unpolled then polled, `getHealth` listing both); pull-path metrics as one series per placement (through the ingest seam and `queryRange` with `seriesBy: replica`); the push-metrics refusal `409` (S-e); a `manual` fleet over both after P1 alone took a generation (ladder `rendered`, then `persisted` only after P2; per-member request, steps and apply-outcome/rollout notices naming their own placement); a failed second member (S-b: `COMPLETED_WITH_FAILURES`, ladder at P2's rung, then the retry); a rollback of P1 alone (S-c, `OQ-8.7 (a)`: one minted instance generation, ladder `rendered` until P2 follows); `acceptRuntimeConfig` on P2 only; a stop of one placement and the instance-wide stop (S-d: a step per live placement with its own `placementId`); and `retirePlacement` (the registry-driven stop no longer addresses it: R6's defect).
- Live gate L2 (Step 2), following the established walkthrough pattern: `AbstractMultiPlacementWalkthrough` (the scenario, ten steps, over `E2eStack`/`WalkthroughSupport`), `MultiPlacementLiveIT` (gated on `sm.walkthrough.ssh.host`, skipped with its reason otherwise), `MultiPlacementRehearsalIT` (the same scenario on the certified in-memory host and the local broker image), `MultiPlacementTarget` (the second Cache's `sm.walkthrough.multi.*` properties, validated against the first's registered envelope before any host is contacted), `backend/launcher/scripts/run-e2e-multi-placement.sh` (reads `SM_E2E_*` only; `--check` contacts nothing; says it WRITES; says DISRUPTIVE only for exactly `SM_E2E_CONFIRM_DISRUPT=yes`) and the runbook section "Live gate L2". The scenario: declare P1 and capture its original keyed hash; add P2 on the SAME target (its own container, directory and host port) and capture its original; a `manual` fleet writes both files; with `disruptive=yes`, stop P2, stop the instance, restart both with a `restart-all` fleet; then restore BOTH files by keyed hash through the engine's own restore (restarting both when they were restarted), then retire P2 and assert the host-side counters did not move. A container the run stopped is started again in `@AfterAll` whatever fails, and the run fails loudly if a file or container could not be put back.
- `docs/runbooks/operations.md`: "Give an instance a second placement", "Retire one placement of an instance", "Live gate L2: two placements on the E2E host". `backend/README.md` and `README.md`: the plan-008 status (written; live gate owed), the new operations in the Instances row, the multi-placement paragraph, `sm_data` `V001`-`V025` (V024 is merged), the L2 row in the opt-in table, "Not done yet" item 1 rewritten from "plan 008 not built" to "its live gate and review are owed". `console-integration.md`: rows 52b and 52c (`addPlacement`, `retirePlacement`; there were no rows), row 52a's "it is true when S2 and S3 land" now says they landed, and §13.1's `BE-D23` row now points at `RECONCILIATION-008.md`. The plan's "§12.1" in Step 4 names "a live request must never fall back to a fixture", which has nothing to do with placements: the same stale-section-number correction S1 recorded for "§12 / §15.3"; the rows that needed editing are in §5.3 and §13.1. `BE-D23` is not edited: `BE-D44`'s text already records that it supersedes `BE-D23`'s identity statement (`I3`).

**Verified, offline, run.** `MultiPlacementTargetTest` 10/10: **red first** (the class did not exist: compile error), green after; 3 mutations, all fired (drop the container-prefix check: 1 red; drop the `/` that makes the directory strictly under the root: 1 red; drop the host-port equality check: 1 red). `RunE2eMultiPlacementScriptTest` 6/6: **red first** (6 of 6 failed, the script did not exist), green after; 3 mutations, all fired (not requiring the second Cache: 2 red; passing the disruptive flag unconditionally: 1 red; accepting any `SM_E2E_CONFIRM_DISRUPT` value: 1 red). My first attempt at that last mutation (`&& false` inside `[[ ]]`, which is a non-empty string and so true) did not fire; it was a bad mutation, not a weak test, and was redone with `&& 1 -eq 0`. `mvn -o -q -f backend/pom.xml test-compile` clean. `mvn -o -f backend/pom.xml test -Dmaven.test.failure.ignore=true` (surefire only): cp-api 49, mesh-events 160, cp-core 547 (1 error), dp-core 1323 (1 error, 1 skipped), console-gateway 793, launcher 110, arch-tests 102, **0 failures**; the two errors are the same two Docker ones as S3/S4 (`SmControlLockoutRecoveryMainTest`, `IngestServiceContractTest`: `Container startup failed for image postgres:14`). `./contracts/run-checks.sh --strict` PASSED except check 14 (`ajv/dist/2020`, the known environment gap); `check-postman.py` OK (53 operations, 52 served, 1 wired-not-counted, 0 not served). Every relative link and anchor in the four edited documents resolves (a script, not a build).

**OWED, none claimed (class#method: what it proves).**
1. `MultiPlacementEndToEndIT#anInstanceIsDeclaredWithOnePlacementAppliedAndPolledAndTheLadderIsPersisted`: one placement applied is `persisted`, and a polled placement is listed with no `availability`. `#aSecondPlacementOnAnotherTargetWithTheSameContainerNameDropsTheLadderAndAnnouncesItOnce`: the add gives a second live placement with the same container name on another target; the ladder drops `persisted` to `rendered`; exactly ONE new `/config-state` notice; unpolled then polled, and `getHealth` lists both. `#pullPathMetricsAreOneSeriesPerPlacement`: `queryRange` returns two series carrying their own `placementId`. `#anInstanceThatDeclaresPushMetricsIsRefusedASecondPlacement409AndNothingIsWritten`: S-e (**its premise is unverified: that a cache package with one `solace_event` metric added loads (`201`) and declares; if the load or the declare is refused, the test fails at that assertion, which is a finding about the fixture and not about the refusal**). `#aFleetOverBothPlacementsAppliesEachAndTheLadderIsPersistedOnlyAfterTheSecond`, `#aFailedSecondMemberCompletesTheFleetWithFailuresAndTheLadderSitsAtThatPlacementsRung`, `#aRollbackOfOnePlacementAloneMintsAGenerationAndTheLadderDropsUntilTheOtherFollows`, `#acceptRuntimeConfigOnOnePlacementReadsThatPlacementsFileAndMintsOneInstanceGeneration` (assumes the real renderer's file contains `max-size: 1000` for this stage body; it asserts so before using it), `#aGatedFleetOverTwoPlacementsLandsBothAppliedAndTheLadderIsRestartObservedOnlyAfterTheSecond` (W4A-4: added in the fix round below), `#aStopOfOnePlacementAddressesThatPlacementAndTheInstanceWideStopAddressesBothWithTheirOwnIds`, `#aRetiredPlacementIsNoLongerAddressedAndTheInstanceWideStopStillLands`.
2. **IT mutation proofs not run (predictions, not results):** skip the push refusal in `InventoryService.addPlacement` (test 4 red); skip the ladder re-settle in the add (test 2 red on the `/config-state` count); restore a history enumerator in `ApplyExecutor` (test 10 red: the stop addresses the retired P2 and lands `STOP_FAILED`); revert `sm_data` V021's identity to placement-blind (test 9 red: the instance-wide stop's two `STOP` intents share `(apply, step, replica-1)`); make `rollbackApply` mint no instance generation (test 7 red on `before + 1`); read P1's file in `acceptRuntimeConfig` (test 8 red on `placementId`).
3. `MultiPlacementRehearsalIT` (ten steps against the in-memory host and the local Solace broker; needs Docker, `postgres:14` and `solace-pubsub-enterprise:10.25.0.177`): run it before the live gate. `MultiPlacementLiveIT` via `run-e2e-multi-placement.sh` against the E2E host with a second Cache container running (W4 review W4A-5/W4A-6, corrected: the script needs `SM_E2E_CONFIRM_WRITE=yes` and, for the disruptive steps, `SM_E2E_CONFIRM_DISRUPT=yes`; **`OR-110` already authorises stopping and restarting the Cache and adding a second one on the E2E host, so no further owner permission is needed**, and the switch is an opt-in, not a permission request; without it the stop and restart steps are skipped and recorded). **Only a disruptive pass satisfies L2**: a non-disruptive pass skips stop-P2, the instance-wide stop and the restart of both, and does NOT close L2. **The L2 claim is not made.**
4. Everything S3 and S4 left owed is still owed and was not touched by this slice: their Results lists stand (`AddPlacementIT`, `AddPlacementLifecycleRaceIT`, `RetirePlacementIT`, `AddPlacementEndpointIT`, `StopApplyIT`, `StopEndpointIT`, `ApplyPlacementReferentialIT`, `ApplyPlacementGuardUpgradeIT`, the eight moved launcher ITs, and `postgres:14`).
5. Step 3's full gate (`clean verify`, DDL replay with drift reported) and Step 6's combined review.

**Deviations from this plan's text, each a reading and not a decision.** (a) The fleets in the end-to-end IT are `manual` (member state `STAGED`), not the plan's "both `APPLIED`": with the health poller on, an ungated restart across two placements would interact with start observation, which `ConfigLadderTest` and `StartObservationIT` own, and the IT's ladder assertions are about which placements count. Plan 012 T6 owns the ungated strategies across placements. *Rider (W4 review W4A-4, 2026-10-08):* the reason did not cover `rolling-health-gated`, which is served, gated, and exempt from start observation; the IT now ends with one gated-fleet test (below). (b) Step 2 says to retire P2 "so the E2E database holds one live placement again"; this harness's database is a throwaway Testcontainers one, not the owner's, so the retire is exercised there and the runbook states the operator's own ordering (remove the container, restore the file, then retire). (c) The pull-path "series per placement" is proved at the read side (the ingest seam and `queryRange`), not by running the collector against a stub: the collector's per-placement plan is `AddPlacementIT`'s (owed). (d) Step 2 and the live scenario need a second container the customer's CI/CD has deployed; the harness never deploys one (`OR-29`, `I1`).

**For the integrator and the owner.** `README.md`'s "46 operations, 45 counted served" and `backend/README.md`'s Targets row ("read only") and `501` list are stale against waves W2/W3 and plans 012/013; they are other lanes' and W6's (013-T4.2) to correct and were not touched beyond plan 008's own statements. `W2A-1` (no actor recorded for add or retire) is stated, not worsened, in the new runbook sections, `console-integration.md` rows 52b/52c and the README paragraph. No `decisions.md` row was added (it is appended to by other lanes in this wave; `BE-D44` already records the supersession of `BE-D23`'s identity statement).

**W4 review fix round (2026-10-08, lane A, one round per `OR-70`): eight findings, all eight addressed in code or text; none is `needsOwner`; NO IT was run (Docker is down) and no mutation of an IT was run.**

- **W4A-1 (MAJOR) fixed.** `AbstractMultiPlacementWalkthrough` step 3 now captures P2's original immediately after the `201` (before any other assertion), and asserts the original exists with a keyed hash (a Cache with no `application.yml` of its own is refused with nothing written). Steps 4, 5, 6 and 7 call `requireBothOriginals`, which FAILS the step unless both originals are captured and exist, so no write or stop can follow a failed capture. `restoreSecond` reports `MANUAL` (not "nothing was written") when a write generation was recorded but P2's original is missing (`E2eStack.hasWriteGeneration`).
- **W4A-2 (MAJOR) fixed.** `E2eStack.restoreOriginal` has an overload taking the placement's own `configDirectory` and `fileName`; every MANUAL instruction is now a pure static function of those two (`manualAbsent`, `manualNoGeneration`, `manualRefused`, `manualFailed`), and P2's restore passes the second Cache's directory. **Unit-proved and run:** `E2eStackRestoreInstructionsTest` 3/3, red first (it did not compile: the functions did not exist), green after; one mutation (`manualAbsent` naming the first Cache's directory) fired, 2 red.
- **W4A-3 (MAJOR) fixed.** Step 9 (retire) is skipped with its reason (`evidence.skip`) unless step 8 put P2's file back with no MANUAL or DIFFERS line and no container this run stopped is still stopped. `@AfterAll` starts stopped containers FIRST, and its MANUAL text names the real container (kept in `stoppedContainers`), not a placeholder. The summary says "(retired)" only when step 9 ran. The runbook states the rule.
- **W4A-4 (MAJOR) fixed in code, not run.** `MultiPlacementEndToEndIT` test 11 (`aGatedFleetOverTwoPlacementsLandsBothAppliedAndTheLadderIsRestartObservedOnlyAfterTheSecond`): on a fresh instance (the in-memory hosts of the earlier instance were created without a health signal), P1 alone gated and verified reads `restart-observed`; after `addPlacement` the ladder reads `rendered`; P1 alone gated again leaves it at `rendered`; a `rolling-health-gated` fleet over both lands both members `APPLIED` and the instance `restart-observed`. **Premises unverified (stated in the test):** that the in-memory host's signal passes the gate exactly as `RollingHealthGatedIT` shows, and that a gated fleet member needs no `outageConfirmedBy`. Deviation (a) carries a rider.
- **W4A-5 (MINOR) fixed.** The script refuses unless `SM_E2E_CONFIRM_WRITE=yes` (exact), as `run-e2e-walkthrough.sh` does. `RunE2eMultiPlacementScriptTest` 8/8 (was 6): two new cases, **red first against the previous script (3 red: the new write gate, the L2 text, and the every-missing-variable case now naming `SM_E2E_CONFIRM_WRITE`)**, green after.
- **W4A-6 (MINOR) fixed.** Runbook, script header and banner, backend README and the OWED item 3 above cite `OR-110` as the authority for the disruptive run and drop "owner permission needed"; `SM_E2E_CONFIRM_DISRUPT=yes` stays the opt-in. All four state that **only a disruptive pass satisfies L2** and that a non-disruptive pass does not close it (script test asserts the banner and the header).
- **W4A-7 (MINOR) fixed.** Root `README.md` now says multi-placement is written, not verified, names the Docker-gated tests that have not run, and carries `53 operations, 52 counted served`; `backend/README.md`'s "Not done yet" item 1 no longer implies the in-process tests ran.
- **W4A-8 (NIT) fixed.** Step 1 parses the second Cache's target once; every later step `assumeTrue(multi != null)` (skipped, not failed), so a misspelt property is one clean refusal and no SSH session or NPE cascade; `@AfterAll` touches no host when nothing was added.

**Verified, run (offline).** `mvn -o -q -f backend/pom.xml test-compile` clean; `mvn -o -f backend/pom.xml test -Dmaven.test.failure.ignore=true`: cp-api 49, mesh-events 160, cp-core 547 (1 error), dp-core 1323 (1 error, 1 skipped), console-gateway 793, launcher 115 (was 110: +3 `E2eStackRestoreInstructionsTest`, +2 `RunE2eMultiPlacementScriptTest`), arch-tests 102, **0 failures**; the two errors are the same two Docker ones (`SmControlLockoutRecoveryMainTest`, `IngestServiceContractTest`: `Container startup failed for image postgres:14`). No contract, Postman, migration, `console.yaml` or `decisions.md` file was touched. **OWED additions, none run:** (i) `MultiPlacementEndToEndIT` test 11 above, and the rest of that class; (ii) `MultiPlacementRehearsalIT` with the new fail-fast, skip and restore-text behaviour of `AbstractMultiPlacementWalkthrough` (steps 1, 3, 4 to 9); (iii) predicted mutations, not run: drop `requireBothOriginals` in step 4 (a P2 with no captured original is written; showing it needs a rehearsal fault that fails the capture, which I did not look for or add, so it is unproven by any test); drop the step 9 guard (the retire runs after a refused restore); make the gated fleet in test 11 read P1 only (the final `restart-observed` assertion is the only place it shows). **L2 is not claimed.**

## Dispatch map

| Slice | Waits for | May run beside | Owns (single-writer) |
|---|---|---|---|
| S1 | owner rulings + approval | S2 | `decisions.md`, the design notes, runbook text, **`console.yaml` descriptions** |
| S2 | owner rulings + approval | S1 | V021, the journal classes, `ApplyExecutor`, `InstanceWideIdentityLimitIT`, `StopApplyIT:256` |
| S3 | S1 (contract files handed over) **and S2** (it now edits `ApplyExecutor`'s stop) | — | **the `addPlacement` and `retirePlacement` operations in `console.yaml`, `ENDPOINT-INVENTORY.md`, Postman**, `ConsoleOperations`, inventory, gateway handler, `ApplyExecutor`'s stop enumeration, `AdmissionService`, `StopApplyIT`, the arch fence |
| S4 | S3 (fixtures) | — | V022 (allocated **V024**), `InventoryFixtures`, the admitting ITs, reconciliation record |
| S5 | S1–S4 | — | READMEs, runbook, Results |

Under `OR-70`: Sonnet 5.5 (high) implements; **one Opus review per slice**; the **full reactor build at the end of each
slice** (each slice ends green, see Global Constraints); **one combined Fable 5.1 review** after S5's gate. One fix
round per slice review. **Superseded by the [combined schedule](combined-008-012-013.md)** for order, waves,
single-owner files and migration numbers (V021 for S2, V024 for S4).

## Acceptance

1. An active instance can be given a second placement through the API, on the same or another target, authorized by
   `instance.bindTarget` + `target.use`, and its health and **pull-path** metrics appear per placement. A package that
   declares push metrics is refused (OQ-8.9 (a)). The ladder, the stored rung and `instance.config-state` agree from the
   moment the placement is added (OQ-8.10 (a)).
2. A generation can be rolled across an instance's placements one at a time with `halt-remaining`, and each placement's
   journal, events and ladder contribution are correct (S5 Step 1).
3. The instance-wide stop stops every **live** placement, including two whose containers share a name, under one
   `applyId`; a retired placement in the history no longer fails it. A placement can be retired with
   `retirePlacement` (`?workload=leave-running`), never the last live one and never while work names it, and the host
   is untouched (`BE-D53`).
4. `apply_step_intent`'s key carries the placement; both `BE-D23` clauses are closed in the DDL; the same-placement
   collision still refuses; a placement-scoped request in flight across V021 resumes onto its own intents; V021 refuses
   an in-flight instance-wide stop; `InstanceWideIdentityLimitIT` and `StopApplyIT:256` are inverted, and
   `StopApplyIT:151`/`:219` are moved to live placements; nothing else regressed.
5. An apply request cannot name an unknown placement or one of another instance (if OQ-8.5 (a)).
6. No published migration edited; no `sm_control` change; no MI change; no frontend change.
7. The live gate passed on the E2E host and left it as found.

## Execution estimate (`OR-70` model)

Re-estimated after the review (P008-17). Estimates, not measurements: plans 010/011 recorded no per-slice timings.

| Slice | Effort (Sonnet implementation + one Opus review + full build) | Change from the first draft |
|---|---|---|
| S1 | ≈ 0.5 slice-day (decisions and descriptions; no operation added) | — |
| S2 | ≈ 1.5–2 slice-days (V021 with its in-flight refusal; the scope-aware key; the first Flyway-target IT; two inversions) | +0.5 (P008-01, P008-02) |
| S3 | ≈ **2.4** slice-days: 1.5 + 0.4 (`retirePlacement`, ruled) + 0.5 (the enumerator, `StopApplyIT` and the fence, moved from S4) | +0.5 (contract moved in, P008-05; lock + race, P008-16; push refusal, OQ-8.9; ladder re-settle and start-state tests, OQ-8.10); +0.9 by `OR-121` |
| S4 | ≈ 0.25 slice-day base + **1–1.5 for V024 and ≈ 38 IT files** (OQ-8.5 (a), ruled) | +0.75–1 (P008-03); −0.5 (enumerator moved to S3) |
| S5 | ≈ 1–1.25 slice-days, including the live gate | +0.25 (S-e, the start-state assertions) |
| Fable 5.1 review + one fix round | ≈ 0.5–1 day | — |

**Total ≈ 7–9 slice-days as ruled** (≈ 6.5–8.5 before `OR-121` added `retirePlacement`). The elapsed time is the
[combined schedule](combined-008-012-013.md)'s: S1 → S2 in wave 1, S3 in wave 2, S4 in wave 3, S5 in wave 4. **Deltas the owner can choose:** OQ-8.5 (b) saves ≈ 1–1.5; OQ-8.2 (c) instead of (a)
saves ≈ 1.5; OQ-8.1 (a) adds ≈ 2.5 and three ruling amendments; OQ-8.9 (c) adds ≈ 1.5–2; OQ-8.7 (b) adds ≈ 1.5;
OQ-8.11 (b) adds ≈ 1.

## Non-goals — stated exactly

| Not here | Why |
|---|---|
| An instance-wide **apply** under one `applyId` | OQ-8.1 (b) recommended; additive later on S2's journal |
| Undeclare, `restart-all`, `scheduled`, `rolling-no-validation` | `OR-114` item (2), open item 39; plan 012, which OQ-8.1's cross-plan section feeds |
| Target / SSH key / host key / entitlement registration over the API | `OR-114` item (3), plan 013 |
| The plain-VM adapter | `OR-111`, `OR-114` item (4): after this plan (plan 014) |
| Push metrics for a multi-placement instance | OQ-8.9 (a) refuses the configuration; (c) is a later `OR-107` amendment |
| A `mustBeUniformAcrossReplicas` package field | OQ-8.11 (a); (b) with plan 012's `OPS-Q8` |
| Agent Gateway implementation, `AGW-20`'s projection, `ActualStatePort`/`AgentAuditPort` kits | `OR-115`; `AGW-2`, `AGW-17`–`AGW-21` open |
| Kubernetes placements | `OR-111` |
| Multi-tenancy items 32/33/35 | unchanged |

## Review dispositions (2026-10-07)

An independent review of the refreshed draft (`c472bbdb`) raised 17 findings. All are **applied**. Each row says
where the fix landed and what was measured to make it. Counts were taken at `36af1df6` with `grep` over
`backend/*/src`, and file:line references come from reading the source.

| Id | Sev. | Disposition | Where | Measured |
|---|---|---|---|---|
| P008-01 | BLOCKER | **Applied — rule (a), plus a refusal.** The key keeps `applyId:step:replica` for placement-scoped requests and names the placement only for instance-wide ones; V021 refuses an in-flight instance-wide stop (the one case whose intents' placements were never persisted); `IntentIdentityUpgradeIT` covers resume across V021; the runbook gets a drain line | OQ-8.2, Draft decision 1, S2 Steps 1/3/4/5, Global Constraints, Acceptance 4 | `StepIntentService.compare` `:151-155` requires key equality; `resumeOne` re-runs a stop from the top through `runStop` (`ApplyExecutor.java:1363-1369`), so the instance-wide case re-enumerates and would insert fresh intents; no Flyway-target IT exists in `dp-core/src/test` |
| P008-02 | MAJOR | **Applied.** `StopApplyIT:256` named as S2's second expected inversion; `StopApplyIT` added to S4 with registered placements on two targets and `:151`/`:219` rewritten | S2 Step 5, S4 Step 1a, Dispatch map, Acceptance 4 | `StopApplyIT.java:63-70` (random ids, `declare(tenant, instance)`), `:151`, `:219`, `:256-268` |
| P008-03 | MAJOR | **Applied.** Count corrected; fixture plan on a fresh target; MF-4 (existing rows) with a `NOT VALID` route; MF-5 (rewrite) and the documented lock classes; OQ-8.5 (a) re-costed at 1–1.5 | OQ-8.5, MF-4, MF-5, S4, Estimate | 50 files reference `InventoryFixtures`; `declared(` in 14, `declare(` in 36; 52 `dp-core` IT/fixture files write `apply_request`, 38 register no placement; 47 test files build a random `PlacementId`. PG 14 `ALTER TABLE` lock classes WebFetched |
| P008-04 | MAJOR | **Applied.** R10 and Acceptance 1 say pull-path; new OQ-8.9 with S-e; S3/S5 test the recommended refusal | R10, OQ-8.9, S3, S5, Acceptance 1 | `PushAttribution.java` javadoc (`ambiguous_placement`, F20); key read from draft values, `PushSubscriptionPlan.java:116-117`; `OR-107` |
| P008-05 | MAJOR | **Applied — first option.** S3 owns the `addPlacement` operation, its registration, handler, inventory row and Postman markers; S1 edits descriptions only; S1 runs `cp-api,console-gateway` verify | Global Constraints, S1, S3, Dispatch map | `ConsoleOperationsMirrorTest.java:80-121`; `run-checks.sh:304-307` (step 15) |
| P008-06 | MAJOR | **Applied.** Cross-plan section under OQ-8.1 (plan 012's "between placements", fleet-level `confirmOutage`, undeclare's stop dependency; plan 014 as a fleet); ruling order stated; plan 012's routed uniformity question taken as OQ-8.11 | OQ-8.1, OQ-8.11, header row "Sibling drafts" | `ops-501.md:51-52, 210-222, 363-373, 445-449, 601` (`d/ops501`); `vm-adapter.md:25, 50, 554-555` (`d/vm`) |
| P008-07 | MAJOR | **Applied — first option.** The fence now bans a history enumerator (a `PlacementId`-collection return type in `apply.persistence`) with a planted fixture; "exactly one enumerator" claim withdrawn | S4 Step 5 | 22 `src/main` files reference `PlacementRows`; five `live(` enumerators listed |
| P008-08 | MAJOR | **Applied.** New OQ-8.10, with the measured ladder, stored-rung and health effects; S3/S5 tests for the ladder drop, event, unpolled and undeployed states. One correction to the finding: `instance.config-state` does **not** fire on its own; it is emitted only when an apply lands, which is why (a) re-settles it | OQ-8.10, S3, S5, Acceptance 1 | `ConfigLadder.java:150-170`; `OutcomeService.java:394-400`; `ApplyEventEmitter.java:124-128`; `HealthServiceImpl.java:28-33`; `HealthController.java:42-44` |
| P008-09 | MINOR | **Applied.** Scope difference stated, sub-question OQ-8.3s (recommend the instance per `OR-16`); OQ-8.1 (c) carries `instance.restart` | OQ-8.3, OQ-8.3s, OQ-8.1 (c) | `DeclareOrchestrator.java:124-125`; `console.yaml:1284-1300` |
| P008-10 | MINOR | **Applied.** Planted-bug cell narrowed; a test for the retired case added | OQ-8.5, S4 Step 1 | — |
| P008-11 | MINOR | **Applied.** MF-2 reworded | MF-2 | `V006…sql:25-26` (trigger on `apply_request`); `V004…sql:26` |
| P008-12 | MINOR | **Applied.** Trigger refuses nil on every insert; file list measured, `ResumePlanner` excluded | S2 files, Step 3.4 | 11 `ensureIntent` call sites; `StepIntentRepository.java:37,47`; `Constraints.java:30`; `ResumePlanner` has no intent lookup |
| P008-13 | MINOR | **Applied.** Plan 013 named in R12 with a merge order | R12, header | `013-registration-api.md:16,112` (`d/regapi`); plan 012 has no migration |
| P008-14 | MINOR | **Applied.** OQ-8.6, OQ-8.7, OQ-8.8 now state what does not change and run the scenarios; OQ-8.8 shows the quota arithmetic | OQ-8.6–8.8 | — |
| P008-15 | MINOR | **Applied.** `stop.no.known.placement` wording added to S1 | S1 Step 2 | `console.yaml:1236` |
| P008-16 | MINOR | **Applied, with a measured narrowing.** S3 locks the instance row `FOR SHARE` and re-checks `active`; race IT plus a lock-dropping mutation. Today `abandon` refuses an active instance, so the only race partner is plan 012's undeclare, which must take the row `FOR UPDATE` | S3 Step 3/5, OQ-8.1 cross-plan | `InventoryService.java:159,166` (`abandon` refuses `ACTIVE`) |
| P008-17 | MINOR | **Applied.** Re-estimated per slice, with deltas per option | Execution estimate | — |

## Results — the closing record (wave 6, 2026-10-08)

> **What this is.** The completion record for plan 008, written at the close of wave 6 against the merged tree
> (`x/w5-int` @ `7629960d`, waves 1 to 5). It does not replace the per-slice Results above (they say what each
> lane saw on the day, including what it could not run); it says what is **now** true, and it is the current owed list.
> Every count below was read from a build log or a command run for this record; where a figure comes from a slice's
> own Results and was not re-read, it says so. Nothing here is a claim about a live host: **no live gate has run**. *(2026-10-09 note: live gate L2 has since PASSED; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09).)*

**One-paragraph status.** Slices S1 to S5 are built and merged. An active instance can be given a second placement
(`addPlacement`) and lose one (`retirePlacement?workload=leave-running`); the apply journal identifies a step by its
placement (`sm_data` V021), so two placements whose containers share a name no longer collide in an instance-wide stop;
that stop addresses the registry's **live** placements, not request history; the database refuses an `apply_request`
naming an unknown or another instance's placement (V024); and each placement change records its actor (V026, `OR-125`
(1)). The Docker-gated integration tests this plan's slices left "owed" **have run on `postgres:14`** in the integration
builds, and are green. **Owed:** the integration-level mutation proofs (the live gate L2 has since passed; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09)),
three `postgres:14` and operator-side measurements, the combined Fable 5.1 review with its fix round, and the final
`clean verify` at the final head.

### 1. What S1 to S5 built, by slice and wave

| Slice | Wave | Commits (lane A) | Built |
|---|---|---|---|
| S1 | 1 | `d9609dd0` | `BE-D44`-`BE-D48` and `BE-D53` checked against the plan (no meaning changed; a dated changelog row); `console.yaml` descriptions only (`stopInstance` "every **live** placement", `stop.no.known.placement`, `steps[].placementId`, `submitFleetApply`, the four one-placement operations); `console-integration.md` row 52a, `fleet-apply.md` 5.1, a dated note under `eda-walking-skeleton.md` 8; two `operations.md` procedures (the V021 drain; roll across placements). No Java |
| S2 | 1 | `368d190e`, `a427cc24` (W1 review A-1..A-7) | `sm_data` **V021**: `apply_step_intent.placement_id`, key `apply_step_intent_placement_identity_key` added before the old key is dropped, a `BEFORE INSERT` trigger (nil refused on every insert; a placement-scoped request's intent carries its own placement; an instance-wide request's names a live-or-retired placement of its instance), a refusal while an instance-wide request is `REQUESTED`/`RUNNING`/`APPLY_INDETERMINATE`, `LOCK TABLE apply_request IN SHARE MODE` before that check, and a readiness probe so the new binary never goes ready on V020. Engine: `ensureIntent` takes the placement (11 call sites), `idempotencyKey(id, scope, placement, step, replica)` (the placement-scoped format is unchanged, so `compare()` passes on resume), the stop's label-collision refusal deleted, `observeApply` reports each step's own placement. `InstanceWideIdentityLimitIT` and `StopApplyIT`'s collision test inverted |
| S3 | 2 | `76a6a1f6`, `6fd7cd45` (W2A-2: `addPlacement` fenced against an instance-wide request), `414b155f`, `ca540df2` (the dropped S3 Step 1 tests; race ITs that can tell the lock from a foreign key) | The `addPlacement` and `retirePlacement` operations (contract, `ConsoleOperations`, inventory rows, Postman, gateway `PlacementController`/`PlacementOrchestrator`, `InstanceInventoryService`, `InventoryService`); the data plane takes `apply_scope` first and then the instance row `FOR NO KEY UPDATE` (a deviation from the plan's `FOR SHARE`, recorded in `decisions.md`), re-checks `active`, stamps the instance and settles the ladder through the shared `ApplyEventEmitter.settleLadder`; push-metrics packages are refused `409`; `LivePlacements`/`RegistryLivePlacements` replace **both** `placementsOf` and `PlacementEnumerationTest` fences a history enumerator, with planted fixtures. No migration |
| S4 | 3 | `e2ac6713`, `b67f36b6`, `d897b2b4` (W3A-1..5) | `sm_data` **V024**: `UNIQUE (tenant_id, instance_id, placement_id)` on `placement`, a `STORED` generated `apply_request.placement_ref = NULLIF(placement_id, nil)`, the validated `MATCH SIMPLE` foreign key `apply_request_placement_registered`, a pre-check that refuses to run (and changes nothing) while a request names an unregistered or foreign placement, `CREATE OR REPLACE` of V006's `forbid_terminal_regression` and V008's `stamp_apply_request_state_version` (a `STORED` column is stale inside a BEFORE trigger, so both comparisons exclude it); readiness probe for the constraint; the runbook section; `RECONCILIATION-008.md`; `InventoryFixtures.registered(scope)` and the 22 `dp-core` and 8 launcher ITs moved onto it |
| S5 | 4 | `a4a77a2f`, `4b32ee4a` (W4A-1..8) | `MultiPlacementEndToEndIT` (11 tests over HTTP: two placements on two targets with the same container name), the L2 procedure (`AbstractMultiPlacementWalkthrough`, `MultiPlacementLiveIT`, `MultiPlacementRehearsalIT`, `MultiPlacementTarget`, `run-e2e-multi-placement.sh`, `E2eStack.restoreOriginal` per placement), three runbook sections, README and `console-integration.md` rows 52b/52c |
| `OR-125` (1) | 5 | `49c0ece4` | `sm_data` **V026**, the actor on a placement change (section 3) |

**The merged tree this record describes.** 73 console operations in `console.yaml` (72 counted served; `exportRange` is
wired and tested but not counted served): `python3 contracts/openapi/postman/check-postman.py` reports
*73 operations, each exactly once (72 served, 1 wired-not-counted-served, 0 not served); no drift* (re-run for this
record). Plan 008 added two of them (`addPlacement`, `retirePlacement`). Migrations: `sm_control` V001-V019, `sm_data`
V001-V027, `sm_metrics` V001-V003; plan 008's are `sm_data` V021, V024 and V026 (V022, V023, V025 and V027 are plans 013 T1.3, 012 T3, 012 T7 and `OR-125` (2) in plan 013's lane, per the [combined schedule](combined-008-012-013.md#5-migration-numbers)). The operation count by wave, read from
each wave's `check-postman` line: W1 46 (45 served), W2 48 (47), W3 53 (52), W4 64, W5 73 (72).

**What each wave put on the served surface** (all three plans, from the wave commits; plan 008's part is the first
clause): **W1** plan 008 S1+S2; plan 012 T0/T1/T4 (the 43-verb catalog, the Operator's `instance.stop`, `sm_control` V016/V017, the control plane's half of undeclare);
plan 013 T1.1/T1.2 (one key-validation path, the host-key scan port). **W2** S3 (`addPlacement`, `retirePlacement`); plan 012 T2 (`rolling-no-validation` and
`restart-all` served) and T3 (undeclare's data plane, V023); plan 013 T1.3 (V022), T2.1/T2.2 (V018, hierarchy and entitlement administration in `cp-core`). **W3** S4 (V024); plan 012 T7 (scheduled applies and rollbacks, V025); plan 013 T1.4 (`scanHostKeys`,
`registerTarget`, `getTargetConnection`) and T3.1 (principal and binding administration in `cp-core`). **W4** S5; plan 012 T6 (the K-2 refusal and the
`rolling-no-validation` settle delay); plan 013 T1.5 (edit, rotate, test and retire a target) and T2.3 (hierarchy and entitlement routes). **W5** plan 012 T5
(`undeclareInstance`); plan 013 T3.2 (eight user and role operations; a username is unique per organization, `sm_control` V019); the `OR-125` rulings (V026 here, V027 and the schedule pre-mint in the other plans).

### 2. Not built, and said so

*(2026-10-09 note: the live gates L1, L2, L4 and L5 and X1's live apply PASSED, and L3 is partial; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09). The sentence below is the wave 6 status.)* Live gates **L1 to L5 and X1 are owed** (pending an owner decision on running them); the Docker file-mode fix (X1) is owed;
the combined Fable 5.1 review is owed; the agent gateway (plan 015) is **not built**; the plain-VM adapter ([plan 014](014-vm-adapter.md)) is **not built**;
live browser notify (the browser joining the mesh) is not built. `declareMetricType` and `setLogLevel` with `mode: live` still answer `501`.

### 3. The `OR-125` (1) rider: placement changes record their actor

The owner ruled `W2A-1` (`OR-125` (1)): `addPlacement` and `retirePlacement` record who did it. The shape built is the smaller of the two the ruling allowed (the full account is the *OR-125 (1) Results* paragraph in S3 above):

- `sm_data` **V026** adds two nullable `uuid` columns to `placement`, `created_by` and `retired_by`; no placement-event table. `created_by` is written at insert by `addPlacement` and by the declare for its own placement; `retired_by` is written with `retired_at` by `retirePlacement`.
- A `CHECK` ties `retired_by` to `retired_at`; V007's immutability trigger is widened to allow exactly "set `retired_at` and `retired_by` together, once" and still refuses every other change, `created_by` included, to every writer; the runtime role's `UPDATE` grant widens by `retired_by` only.
- **`NULL` means "not attributed", never a guess:** every placement that pre-dates V026 (the principal was discarded, so there is nothing to back-fill) and any retirement by a sweep or by undeclaring an instance (`retireAll`, which is not one principal's act on one placement).
- The id is an actor id of the same kind as `target_connection.created_by`, so the agent gateway can write an agent's id into the same two columns later (`OR-128` (5)) with no further migration. That path does not exist yet.
- Proof: `PlacementActorIT` 5/5 and `InventoryReadinessTest` and `InventoryPlacementOperationsTest` (wave 5 build); the mutations in the S3 paragraph fired.
- **What this supersedes:** S3 deviation (6) ("neither operation records who did it") is closed. The "not recorded" sentences in `console.yaml`, `ENDPOINT-INVENTORY.md`, `console-integration.md` rows 52b/52c and the operations runbook were changed in wave 5. `backend/README.md` and `README.md` may still carry the old sentence; they are other lanes' files in this wave.

### 4. Verification: the five integration builds on `postgres:14`

Each wave's integrator ran the whole reactor (`mvn -o -f backend/pom.xml clean verify`) with Testcontainers starting
`postgres:14`; the logs show `Container postgres:14 started`. **Read the per-module lines, not the final line:** the
logs of waves 2 to 5 end `BUILD SUCCESS` with failing tests listed, which a plain `verify` would not do. The
[combined schedule](combined-008-012-013.md#w3w4-integration-2026-10-08) says the W3+W4 run used
`-Dmaven.test.failure.ignore=true`; the flag is not echoed in any log, so for the other waves that is my inference from the same symptom, not a recorded fact. The logs are
the integrators' working files outside the repository (`w1-build4.log`, `w2-build1.log`, `w3-build1.log`, `w4-build1.log`,
`w34-build.log` with `w34-rerun.log`, `w5-build.log` with `w5-fixb.log`); the figures were read from them for this record.
Cells are *surefire (unit) / failsafe (integration)*; the second figure of a skipped pair is the opt-in tests that need a live host, broker or property.

| Module | W1 | W2 | W3 | W4 | W3+W4 combined | W5 |
|---|---|---|---|---|---|---|
| cp-api | 49 | 49 | 49 | 49 | 49 | 49 |
| mesh-events | 160 | 160 | 160 | 160 | 160 | 160 |
| cp-core | 466 / 334 | 491 / 385 | 547 / 427 | 558 / 433 | 558 / 433 | 578 / 461 |
| dp-core | 1221 / 1217 | 1258 / 1301 | 1326 / 1360 | 1421 / 1394 | 1421 / 1396 | 1466 / 1432 |
| console-gateway | 623 | 667 | 793 | 892 | 892 | 981 |
| launcher | 86 / 297 | 86 / 319 | 94 / 343 | 115 / 397 | 115 / 397 | 137 / 428 |
| arch-tests | 82 | 89 | 102 | 102 | 102 | 115 |
| **failures** | **0** | **3** | **6** | **3** | **2** | **2** |

Skipped (opt-in) tests were 1 + 12 in `dp-core` and 27 to 43 in `launcher`, the property-gated ITs of the README's opt-in table, `MultiPlacementLiveIT` (10) among them.

**Every failure, and what happened to it.** None is a plan-008 defect; each was a test fixture, a pinned count or the environment, and each class passed in the next build that ran it.

| Wave | Failure | Cause | Fix and later result |
|---|---|---|---|
| W2 | `RegistrationApiSchemaIT` (1) | fixture inserted a second organization scope | `42b5c7e8`; 19/19 in the W3+W4 build |
| W2 | `StopApplyIT.aLivePlacementWhoseApplyFailed...` (1) | placement B, registered by `setUp`, had no adapter (S3's test, never run when written) | `cab2f558`; 19/19 in the W3+W4 build and in W5 |
| W2 | `DockerMaterializeAndStatusIT` (1) | asserted `HEALTHY`/`STARTING`; the claim is "not `NOT_DECLARED`" | `29e77321`; 8/8 in the W3+W4 build |
| W3 | `SmDataBootstrapIT`, `SmMetricsMigratorIT` x2, `ApplyPersistenceConfigurationIT` x2 (5) | pins still said 24 `sm_data` migrations (V025 made it 25) | `f64a5e1a`; 11 / 4 / 5 green in the W3+W4 build |
| W3 | `ApplyScheduleEndpointsIT.w6_...` (1) | the test sent a rollback body to `/apply` | `94014304`; 9/9 in the W3+W4 build |
| W4 | `TargetConnectionTestIT`, `FleetBatchSafetyIT`, `TargetManagementIT` (3) | shared `sshd` `PerSourcePenalties`; a rig with no `gave_up_at` row; a fake that did not delegate | `5ae4a98d`, `c116323d`, `1b522a7d`; 7 / 6 / 17 green in the W3+W4 build |
| W3+W4 | `JcsmpSampleSubscriberIT.aBrokerOutageIsAGapAndReconnectResubscribes`, `MeshResilienceRehearsalIT.aMidSessionBrokerOutage...` (2) | broker-reconnect timing under machine load; W3 and W4 changed no mesh or broker code | rerun alone, 8/8 and 7/7. The classification rests on the reruns and on the absence of any mesh change, **not** on a root-cause analysis |
| W5 | `SkeletonWalkthroughRehearsalIT` (1 error) | the broker container (`solace-pubsub-enterprise:10.25.0.177`) failed to start in the full run | `w5-fixb.log`: 9 tests, 0 failed, 1 skipped |
| W5 | `MetricsEndpointsIT` (1) | `seedNobody` bypassed the organization registry, so the fixture login named the bootstrap organization | `4c9ea8bd`; `w5-fixb.log`: 14/14 |

**What was NOT re-run.** The last commit on the tree, `7629960d` (the W5 pre-push review), edited
`RetirePlacementIT` (four lines asserting both retire records) and some README/contract text, after the W5 build and after `w5-fixb.log`.
No log I read re-runs `RetirePlacementIT` after it; its 17/17 below predates the edit. The final gate must re-run it.

**The plan-008 tests, by class** (failsafe unless marked *unit*; the first figure is the first build that ran the class green, the second is the W5 build).

| Class | First green | W5 | Proves |
|---|---|---|---|
| `IntentPlacementIdentityIT` | 11 (W1) | 11 | both `BE-D23` clauses closed in the DDL; the same-placement collision still refuses; the trigger's named refusals |
| `IntentIdentityUpgradeIT` | 8 (W1) | 8 | a placement-scoped request in flight across V021 resumes; V021's refusal; the readiness probe |
| `InstanceWideIdentityLimitIT` | 3 (W1) | 3 | the old limit, inverted |
| `StopApplyIT` | 17 (W1); 19 from the W3+W4 build (W2 had one failure, table above) | 19 | the stop addresses live placements; the retired one does not fail it; two same-named containers both stop |
| `StepIntentIT`, `EnsureIntentRaceIT` | 12, 3 (W2) | 12, 3 | the journal's intent contract and race over the new key |
| `AddPlacementIT` | 11 (W2) | 11 | the add, its refusals, the ladder drop and one `config-state` notice, the metrics endpoints |
| `AddPlacementLifecycleRaceIT` | 3 (W2) | 3 | the add against a lifecycle writer, the lock, two adds on one target |
| `RetirePlacementIT` | 13 (W2) | 17 | the retire, its refusals, the lock; the count grew from 13 to 17 between the W3+W4 and W5 builds, and its actor assertions are `OR-125` (1)'s |
| `AddPlacementEndpointIT` | 15 (W2) | 15 | the route over HTTP, including the instance-scoped `bindTarget` grant (`OQ-8.3s (i)`) |
| `StopEndpointIT`, `HealthPollerIT`, `ProductionAuthenticationIT`, `DemoParityChecklistIT`, `InventoryConfigurationIT` | 11, 19, 4, 1, 2 (W2) | 11, 19, 4, 1, 2 | the stop route after the retire, the undeployed added placement reads unpolled then unreachable, the route set (+2), the inventory wiring |
| `ApplyPlacementReferentialIT` | 12 (W3) | 12 | the guard: foreign, unknown and other-tenant placements refused; a retired one passes the key and is refused at preflight; the sentinel still admitted; the two fold tests |
| `ApplyPlacementGuardUpgradeIT` | 4 (W3) | 4 | V024's refusal, rollback and clean upgrade; the readiness probe on a V023 schema |
| the eight moved launcher ITs (`ApplyWorkerLifecycleIT` 4, `ApplyWorkerBoundedClaimIT` 1, `ApplyWorkerPoisonAndDrainIT` 2, `NoNewClaimsAfterStopIT` 1, `GracefulShutdownWithinGraceLandsTerminalIT` 1, `WorkerSingletonLockHeldOnUndrainedShutdownIT` 1, `SecondWorkerPoolRefusedBeforeSweepIT` 1, `ObserveBackfillIT` 5) and `ProductionWorkerLiveIT` (3) | green (W2 and W3) | green | admission onto registered placements; a registered, retired placement is refused by the catalog |
| `MultiPlacementEndToEndIT` | 11 (W3+W4) | 11 | the S5 matrix over HTTP, S-a to S-e, including the push-metrics `409`: **its premise held** (a cache package with one `solace_event` metric loads and declares) |
| `MultiPlacementRehearsalIT` | 10 (W3+W4) | 10 | the L2 scenario against the in-memory host and the local broker image; **not** a live pass |
| `PlacementActorIT` | - | 5 | `OR-125` (1) on the real rows |
| unit: `InventoryPlacementOperationsTest` 28, `PlacementEnumerationTest` 7, `ApplyPersistenceReadinessTest` 5, `MultiPlacementTargetTest` 10, `RunE2eMultiPlacementScriptTest` 8, `E2eStackRestoreInstructionsTest` 3 | green | green | the service's call order, the history-enumerator fence with planted fixtures, the V024 probe, the second-Cache target validation, the script's gates, the restore instructions |
| `MultiPlacementLiveIT` | skipped (10) | skipped (10) | gated on `sm.walkthrough.ssh.host`: **not run** |
| `CacheApplyWalkthroughIT` | skipped (2) | skipped (2) | gated on `-Dsm.demo.cache=true`: **not run** |

**Contract and static gates.** `./contracts/run-checks.sh --strict` reported *ALL CHECKS PASSED* at the wave 1, 2, 3 and 5 integration points (`w1-checks.log`,
`w2-checks.log`, `w3-checks.log`, `w5-checks.log`; the W5 one ends *73 operations ... 72 served*); I found no wave 4 checks log, so I make no claim for it beyond the
operation count in its commit (`e44f4b90`). This wave's record edits only this plan file, so I did not re-run `run-checks.sh`; I re-ran `check-postman.py` (above) and `git diff --check`.
`backend/scripts/frozen-migration-anchors.txt` anchors V021 at `fcd6f931`, V022 and V023 at `f72986d7`, and V024 and V025 at `57bdd41f` (`OR-22`, `OR-105` (e)). **V026 and V027 are not anchored at this record's base (`7629960d`)**: they are unpublished. A later commit on `x/w5-int`, `50480d7f`, anchors `sm_control` V019 and `sm_data` V026 and V027; it is not in the tree this record was written against.

### 5. What is owed

Nothing here is claimed; each line says what is missing and what it would prove.

1. **Live gate L2** (`MultiPlacementLiveIT` through `backend/launcher/scripts/run-e2e-multi-placement.sh`, [runbook](../../runbooks/operations.md#live-gate-l2-two-placements-on-the-e2e-host)): **PASSED on 2026-10-09** (second attempt; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09)); the sentence that follows is the wave 6 text. It was owed, pending an owner decision on running the live gates. It needs a second Cache container the customer's CI/CD deployed (`OR-29`, `I1`); only a pass with `SM_E2E_CONFIRM_DISRUPT=yes` satisfies L2. **Acceptance 7 was therefore not met at wave 6; it is met as of 2026-10-09.** L1, L4, L5 and X1's live apply also passed that day, and L3 is partial; they are other plans' gates and are listed here only so no reader takes L2's absence as the only one.
2. **Mutation proofs that did not run** (the Docker-gated half; unit-level mutations fired and are recorded in S3 and S5):
   S2 Step 5's five (drop the new key; drop the trigger; make the placement-scoped key placement-aware; drop the in-flight refusal; drop the placement predicate from `find`);
   S3 Step 5's IT-level ones (`lockedActive`'s lock: only the first test of `AddPlacementLifecycleRaceIT` should go red, on the exception type; skip the metrics-endpoint insert; skip `lockApplyScope`; restore a history enumerator in `ApplyExecutor`);
   S5's six predicted ones (skip the push refusal; skip the ladder re-settle; restore a history enumerator; revert V021 to placement-blind; make `rollbackApply` mint no generation; read P1's file in `acceptRuntimeConfig`), and the three W4A-1/3/4 ones.
   The ITs are green; that they would go red is the unproved half.
3. **On `postgres:14`, three measurements.** (a) The V006 and V008 mutations (delete `- 'placement_ref'` from each comparison) must turn `ApplyPlacementReferentialIT#theCompactionFoldIsStillTheOneLegalWriteOnATerminalRequest` and `#aFoldOrANoOpUpdateStillDoesNotDrawAStateVersion` red; if they stay green on 14 the two fold tests prove nothing on the project's database (the redefinition is correct either way). (b) MF-5 on 14: whether `ADD COLUMN ... STORED` rewrites `apply_request` (measured on 16.15 only: `pg_relation_filenode` changed); no IT asserts it. (c) **MF-3 is closed**: V024 applies on 14 and the 12 + 4 guard ITs are green. MF-1 and MF-4 are counts on the owner's `OR-118` database (the runbook gives the queries); a lane cannot reach it. **Owed to the operator.**
4. **`CacheApplyWalkthroughIT`** with `-Dsm.demo.cache=true` against the compose Cache.
5. **The combined Fable 5.1 review and its one fix round** (S5 Step 6; the checks it must make are listed there), then **the final gate**: `clean verify` at the final head **without** the failure-ignore flag, `RetirePlacementIT` among it (section 4); the DDL replay with drift reported (the replay baseline covers the first six `sm_data` files, none of which plan 008 touched, so none is expected; no run is recorded in the logs I read); `git diff --check`.
6. **Other lanes' stale statements, found while writing this and not mine to edit:** `backend/README.md`'s "Not done yet" item 1 and the multi-placement paragraph, and the root `README.md`'s status paragraph, still say the multi-placement tests that need Docker have not run, and `backend/README.md` says who adds or retires a placement is not recorded; `backend/README.md`'s module map still lists `sm_control` `V001`-`V018` and `sm_data` `V001`-`V025`, and its local-run recipe prints *migrated 18* for `sm_control`. The merged tree is `V001`-`V019` (*migrated 19*) and `V001`-`V027` (*migrated 27*, already right in that recipe).
7. **Not built, by design of the order** (section 2): the agent gateway (plan 015), the plain-VM adapter (plan 014), live browser notify. Plan 008's non-goals table lists `undeclare`, `restart-all`, `scheduled` and `rolling-no-validation` as plan 012's; they are built (waves 2 to 5) and are not this plan's result.

### 6. Tick state of every task

Ticks in the slices above mean: built, and its verification ran green on `postgres:14` unless the step says otherwise in italics.
An unticked box is owed, and says why. The older tasks 1 to 7 under *History* are the superseded draft and were never to be ticked.

| Step | State | Note |
|---|---|---|
| S1 steps 1-5 | ticked | descriptions and records; `check-postman` and `run-checks.sh --strict` green |
| S2 steps 0-4, 6, 7 | ticked | step 0: MF-1 not measured (operator) |
| **S2 step 5** | **unticked** | mutation proofs not run |
| S3 steps 1, 1a, 3, 4, 4a, 6 | ticked | step 1: the audit-line test replaced by the actor proof |
| **S3 step 2, step 5** | **unticked** | red-first not observed for the unit tests; IT-level mutations not run |
| S4 steps 0-7 | ticked | step 4's "PG 16 harness" caveat is superseded by the `postgres:14` run (section 4); MF-3 closed, the rest in section 5 |
| S5 steps 1, 4, 5 | ticked | step 1's IT ran green (11/11) |
| **S5 step 2** | **ticked 2026-10-09** | live gate L2 PASSED (second attempt) |
| **S5 step 3** | **unticked** | integration builds ran; the final `clean verify` and DDL replay are the final gate's |
| **S5 step 6** | **unticked** | the Fable review is owed |

### 7. Acceptance, criterion by criterion

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | A second placement through the API, authorized by `instance.bindTarget` + `target.use`; per-placement health and pull metrics; push-metrics package refused; ladder, rung and `config-state` agree | **Met in tests** (not on a live host) | `AddPlacementIT`, `AddPlacementEndpointIT`, `MultiPlacementEndToEndIT` (the add, pull-series and push-refusal tests) |
| 2 | A generation rolled across placements one at a time with `halt-remaining`; per-placement journal, events and ladder correct | **Met in tests** | `MultiPlacementEndToEndIT` (the fleet, failed-member and gated-fleet tests) |
| 3 | The instance-wide stop stops every live placement under one `applyId`, including two with the same container name; a retired one no longer fails it; `retirePlacement` refuses the last live placement and pending work and leaves the host untouched | **Met in tests** | `StopApplyIT`, `RetirePlacementIT`, `MultiPlacementEndToEndIT` (the stop and retire tests); "host untouched" is asserted against the in-memory host only |
| 4 | The key carries the placement; both clauses closed in the DDL; same-placement collision refuses; in-flight placement-scoped request resumes; V021 refuses an in-flight instance-wide stop; two inversions and two moves; nothing else regressed | **Met** | `IntentPlacementIdentityIT`, `IntentIdentityUpgradeIT`, `InstanceWideIdentityLimitIT`, `StopApplyIT`; the W1-W5 failure table above |
| 5 | An apply request cannot name an unknown or another instance's placement | **Met** | `ApplyPlacementReferentialIT`, `ApplyPlacementGuardUpgradeIT` on `postgres:14` |
| 6 | No published migration edited; no `sm_control` change; no MI change; no frontend change | **Met** | plan 008's migrations are `sm_data` V021, V024, V026 only; the frozen-migration check passes at the W5 integration point (`w5-checks.log`) |
| 7 | The live gate passed on the E2E host and left it as found | **Met 2026-10-09** (was NOT met at wave 6: L2 was owed) | section 5, item 1; the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09) |

### 8. Rulings made after approval, and which are not this plan's

`OR-125` (1) is the only post-approval ruling that changed this plan's schema (section 3). The others that name this plan's neighbourhood are
other plans' work, already built in the same waves, and are listed so a reader does not look for them here:

- **`OR-125` (3), `OR-131` (Q1):** an organization is renamed at install scope only over HTTP; an operator CLI renames any organization. Plan 013 (W5).
- **`OR-125` (4), `OR-131` (Q3, Q4):** usernames are unique per organization and compared without regard to case (`sm_control` V019); login's `organization` is optional with a single-organization fallback (`400` when the install holds several and it is omitted). Plan 013 T3.2 (W5).
- **`OR-125` (5), `OR-131` (Q2):** the schedule firer pre-mints the `ApplyId`; `undeclareInstance` answers `409` while a `PENDING` schedule names the instance. Plan 012 T5 and T7. Plan 008's `retirePlacement` does not read `apply_schedule` (no schedule reference in the retire path's source; I did not test a retire against a `PENDING` schedule, so what a scheduled apply does to a retired placement is not recorded here).
- **`OR-125` (2):** the pinned host key's type (`sm_data` V027). Plan 013.
- **`OR-126` to `OR-130`:** the agent gateway (plan 015). **Not built.** The only trace in this plan is that the actor columns of section 3 are ready to take an agent's id (`OR-128` (5)); no code writes one.

---

## History — the draft as written 2026-09-15, with its 2026-10-05 notes

*Kept unedited for readability; its original title was "Backend Plan 008 — the placement registry and placement-aware
apply-journal identity". Where it conflicts with the refreshed plan above, the refresh controls (R1–R13).*

> **Amended 2026-10-05 (plan 010 T0.3; rulings `OR-29`, `OR-16`, `OR-35`) — read this before anything below.**
> Four things in this plan are changed by later rulings. The body is left as written (so its history stays
> readable); where it conflicts with this note, **this note controls**.
>
> 1. **Task 1's registry is delivered by plan 010 T2.1, not here — and with `target_id`** (`OR-29`: *"`sm_data.placement`
>    (with `target_id`, immutable; re-target mints a new placement) is built now; plan 008 keeps only multi-placement
>    and journal identity"*). Task 1's `placement(tenant_id, instance_id, placement_id, kind, label, created_at,
>    retired_at)` therefore lacks a column the registry now has; do not build it. This plan's remaining scope is
>    Tasks 2-5's journal identity and multi-placement apply, built on plan 010's table.
> 2. **Lookups bind `(tenant, instance, placement)`** (the v2 paper's F20). Task 1's `find(TenantId, PlacementId)`
>    omits the instance, so a placement id from one instance would resolve under another; every lookup this plan
>    writes takes all three.
> 3. **The premise citing `OR-2` is corrected to `OR-16`.** The paragraph below that calls placement a level of the
>    canonical hierarchy relies on the half of `OR-2` that `OR-16` superseded: the grantable hierarchy is the **five**
>    levels `Organization → Sub-organization → Environment → Solution → Instance`, and **Placement is an operational
>    child identity of Instance** — present in APIs, topics, events and runtime operations, **not** an independently
>    grantable RBAC level. What survives of `OR-2` is the immutable-id requirement. Authorization for a
>    placement-targeted operation resolves through the owning Instance scope.
> 4. **Migration numbers move.** `sm_data` `V007`-`V014` are now plan 010's (see its *Migration and id allocation*
>    table; `V011` is T6.8's). This plan's three migrations renumber to the next free numbers, **`V015` onward**:
>    `V007__placement` is delivered by plan 010 and is not built here; `V008__intent_identity` becomes `V015` and
>    `V009__placement_referential_guard` becomes `V016`, unless plan 010 has taken either by then — re-check the
>    merged tree before numbering, and let the integrator allocate. Every `V007`/`V008`/`V009` below reads
>    accordingly.

> **Dated note 2026-10-05 (plan 011 P11-0.1; plan 011 is a DRAFT, not approved) — point 4 above is corrected, not deleted.**
> [Plan 011](011-monitoring-backend.md) holds `sm_data` **V015** (`V015__metric_definitions_and_endpoints.sql`) and reserves
> **V016** (`broker_connection`, and possibly a widened `secret_purpose` CHECK), both above plan 010's V014. So this plan's
> migrations renumber **one block further: V017 onward** (`V008__intent_identity` becomes `V017`,
> `V009__placement_referential_guard` becomes `V018`; `V007__placement` is still plan 010's and is not built here). Until plan
> 011 is approved this is a reservation, not an allocation: re-check the merged tree before numbering, and let the integrator
> allocate in merge order.

> **Dated note 2026-10-05 (plan 011 wave review; the notes above stay) — one block further again: V018 onward.** `V017` is
> taken by `OR-96`'s `designer_layout` (plan 010 slice S6, `p010/s6`), so this plan's migrations start at **V018**
> (`V008__intent_identity` becomes `V018`, `V009__placement_referential_guard` becomes `V019`). Still a reservation until
> merged: re-check the tree and let the integrator allocate in merge order.

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lift `BE-D23`'s single-placement restriction by giving placements a real registry and the apply journal a
placement-aware identity — **both** of `BE-D23`'s clauses, not one — while keeping the `RuntimeAdapter` seam and the
Agent Gateway's typed semantic ports compatible, and without letting the gateway near `sm_data`.

**Architecture:** A `sm_data.placement` table becomes the referential home the nil-UUID sentinel currently has none
of, and `apply_step_intent`'s uniqueness gains `placement_id`. That is two changes and they are **not**
interchangeable: `BE-D23` refuses a multi-placement apply for **two independent reasons** — a placement-scoped step
collides on `replica_ref = '@placement'`, **and** a replica-scoped step collides when two placements produce the
same `replica_ref`. Fixing the first alone leaves the second, and the second is the one that bites on SSH-Docker,
where `SshDockerRuntimeAdapter` derives `replica_ref` from the container name.

✅ **Placement is a first-class level of the canonical hierarchy** (ruling **OR-2**, 2026-09-16):
`Organization → Sub-organization → Environment → Solution → Instance → **Placement**`. This plan builds the
registry for that level, and ⚠️ **OR-6 makes it a topic level too** —
`…/instance/{instanceId}/placement/{placementId}/{aspect}` — so the registry's ids are externally visible and
**immutable**, not internal keys.

**Requirements:** [`REQ-005`](../../requirements/REQ-005-three-runtimes.md) — multi-placement is what a
multi-site, multi-runtime instance needs · [`REQ-001`](../../requirements/REQ-001-unified-config-layer.md) ·
[`REQ-003`](../../requirements/REQ-003-onprem-and-saas.md) (the identity model must survive the plane split)

**Tech Stack:** Java 17 · **Spring Boot 3.5.16** · Flyway at the reactor's managed version (`sm_data` continues at
`V007`) · **PostgreSQL 14** · **Testcontainers 1.21.4** · **ArchUnit 1.5.0** · the existing DDL replay harness

**Spec:** [`docs/design/backend/decisions.md`](../../design/backend/decisions.md) `BE-D23` (both clauses) and
`BE-D13`/`BE-D19`/`BE-D20` · [`docs/design/backend/eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md)
§3.1 (the SQL block), §3.3 (admission), §8 rows 13 and 14 ·
[`docs/design/backend/agent-gateway/design.md`](../../design/backend/agent-gateway/design.md) §2.2, §3.2, §3.5, §13 ·
[`docs/design/common/contracts/console-integration.md`](../../design/common/contracts/console-integration.md) §12

---

## Global Constraints

- **Both `BE-D23` clauses, or neither.** A task that closes the placement-scoped-step collision and declares
  multi-placement supported has not read the decision. The acceptance test is two placements whose adapters return
  the **same** `replica_ref`.
- **Compatibility with both execution paths is a hard requirement**, and it is what makes this plan risky rather
  than routine:
  - **`dp-remote` adapters** — `DockerAdapter` and `SshDockerRuntimeAdapter` keep working unchanged, or the change
    to them is part of this plan and is tested.
  - **Agent Gateway typed semantic ports** — ⚠️ **measured: none of them exists.** `backend/dp-api` holds one file
    (`DpMarker.java`), there is no `agent-gateway` module in the reactor, and there is no contract kit to run. So
    the obligation is **not** "the kits still pass" (an earlier draft said that, and it was unverifiable by
    construction) — it is: **leave the nine ports `AGW-D6` documents implementable**, and in particular leave a
    future `AgentDispatchAdapter` able to sit behind the amended journal **without a second identity model**.
    Task 6's record is the evidence, and a record is all it is.
- ⛔ **The Agent Gateway may never touch `sm_data`.** No DP repository import, no JPA entity, no table access, no
  datasource, no DB role (`AGW-D2`). If this plan's identity change tempts a port toward a table, **the design is
  wrong, not the rule** — record it and stop.
- **Do not weaken an existing guarantee.** Every DDL constraint, the replay harness, the `I6` secret controls and
  the adapter contract tests stay as strong or stronger. A migration that drops a constraint to make room for a
  column must re-add an at-least-as-strong one **in the same migration**, with the refusal test to prove it.
- **`I3`** — ⛔ **No document reserves a decision id.** New decisions **allocate the next free id from the merged `DECISIONS.md`** — the one central allocator (ruling **OR-11**) — and only **after plan 005 merges and the register is re-read**, because plan 005 carries `BE-D25`/`BE-D26`.
- **`I5`** — every new query carries `TenantId`; all SQL uses bind parameters.
- **`I7`** — `sm_data` belongs to `dp-core`. No cross-schema join and no FK to `sm_control`; the placement's
  **scope** is validated by the CP/DP instance-identity protocol, not by a foreign key (see Task 6).
- **Docker/Colima is shared state.** The usual checks and serialization; a Compose project name owned by this
  worktree; clean up only its containers.
- **Every guard carries a planted-violation proof** (`L7`); a mutation that does not fire is a finding about the
  tests (`L22`).
- **Commit attribution.** Every commit ends with the session's attribution trailer.

---

## Prerequisites

1. **Plan 005's completion and plan 007 must both be landed.** This plan changes the identity the engine and the
   API already depend on; doing it first means changing it twice.
2. **Re-measure `BE-D23` before touching anything.** Its two clauses are the plan's premise; confirm both still
   hold against the DDL as it actually is (`UNIQUE (tenant_id, apply_id, step, replica_ref)` plus
   `placement_scoped_steps`), and record the measurement. **Do not accept this plan's own summary as evidence.**
3. ⛔ **Five statements about the Agent Gateway that must survive this plan unchanged.** They are restated here
   because an identity change is exactly the kind of work that erodes them by accident:
   - **It is designed, not implemented.** No `agent-gateway` module exists in the reactor; `backend/dp-api` holds
     one marker class. **Nothing in this plan may describe it as implemented, conformant, or partially built.**
   - **`AGW-2` is open: there is no `.proto` and no wire state machine.** All v1 agent work waits on it.
   - **`AGW-17`–`AGW-21` stay open and stay gated**, each blocking what `design.md` §13's own `Blocks` column says
     it blocks — not a paraphrase (see `console-integration.md` §12.2).
   - **The gateway retains no datasource, no DB role, no repository import and no `sm_data` access** (`AGW-D2`).
     Task 6's rules fence the placement repository inside `dp-core` and keep `dp-api` free of persistence types.
   - **This plan makes the journal and placement identity *compatible* with a future `host-local` adapter. That is
     all it claims.** Compatibility is recorded in Task 6's reconciliation document; it is not demonstrated, because
     there is no code to demonstrate it against.
4. **Read `agent-gateway/design.md` §3.5 and §13 `AGW-20` first.** The agent path's `status(replica)` reads a
   canonical per-replica actual-state projection **that does not exist**. This plan must not accidentally become
   that projection, and must not assume it.

---

## Design-first work that must happen before Task 1

⚠️ **Three questions this plan cannot answer by writing code.** Each is a contradiction between documents, and the
standing rule is: record it and stop that decision-dependent implementation rather than inventing semantics.

| # | Question | Why code cannot settle it |
|---|---|---|
| D1 | **Is `sm_data.placement` the same registry as `AGW-D16`'s `agent_replica`, a parent of it, or unrelated?** ⚠️ **Routed to the `AGW-D*` owners — not decided by a `BE-D` of this plan's.** A `BE-D` declaring the two registries identical would settle `AGW-D16`'s subject from outside its family, which `I3`/`I4` forbid and Task 6 Step 5 also forbids. That decision may state only **what `sm_data.placement` is**; whether `agent_replica` hangs off it is theirs. If the answer has not arrived, Task 1 proceeds on the narrow statement and Task 6 records the question. `AGW-D16` mints a two-level replica registry with a per-label grammar, live caps, one active incarnation and timestamped retirement. This plan mints a **placement** registry. A placement is a *site*; a replica is a *process at a site* — but `AGW-D16`'s registry is also DP-owned and also keyed by tenant, and two overlapping registries is how a dual identity model starts | It decides whether the agent path's replicas hang off a placement row or a parallel tree. Getting it wrong is a migration later, not a refactor |
| D2 | **What does `replica_ref` mean once two placements can produce the same string?** Either it becomes placement-qualified (a value change — `BE-D19`'s grammar and every existing row) or uniqueness moves to `(placement_id, replica_ref)` (a key change). `BE-D19`/`BE-D20` constrain the first; `BE-D23` names the second | The two choices have different migration costs and different effects on `AGW-D16`'s grammar |
| D3 | **§8 row 14 — cross-scope supersession.** An instance-wide apply admitted while a placement-scoped `STAGED` row exists leaves the elder `STAGED` although its staged bytes are wholly or partly overwritten. **Measured** in the plan 004 prototype (`MonotonicityIT`). Two candidate rules are named and neither is chosen | Multi-placement makes this common rather than a corner. Choosing silently would invent admission semantics |

- [ ] **Step 0: Record D1, D2 and D3 as decisions, allocating ids from the merged register, or stop.** No task below may begin on the part of the plan the unanswered question governs. Tasks 1 and 2 depend on D2; Task 4 depends on D3; Task 6 depends on D1.


### Ratification posture — stated, because `docs/plans/README.md` says not to start on a PROPOSED decision

[`docs/plans/README.md`](../README.md) says: *do not start a plan when it depends on a `PROPOSED` decision that
ratification could still change.* This plan does depend on PROPOSED decisions, and the posture is the same one
`BE-D16`–`BE-D18` and plans 004–005 already carry, stated rather than assumed:

- **What is settled** is the part this plan is built on: `BE-D13`'s journal is implemented and measured, and `BE-D23` is a measured restriction rather than a preference. The `AGW-D*` family, by contrast, is PROPOSED **and ratifies after `BE-D*`** — which is exactly why Task 6 produces a reconciliation record instead of editing that family.
- **What ratification would decide** is whether those decisions *stay*, not whether they may be built.
- **What is genuinely blocked** is listed in the non-goals table, and nothing in the task list depends on it.
- **A contradiction found during execution is design-first**: record it and stop the decision-dependent task.
  Do not invent semantics, relax a constraint, or write a migration around it.

---

## File Structure

| Path | Responsibility |
|---|---|
| `backend/dp-core/src/main/resources/db/migration/sm_data/V007__placement.sql` | The registry, its grammar, its caps, retirement. |
| `…/sm_data/V008__intent_identity.sql` | `apply_step_intent`'s placement-aware uniqueness, and the constraint that replaces the old one. |
| `…/sm_data/V009__placement_referential_guard.sql` | The FK the nil-UUID sentinel needs, and the sentinel's own row. |
| `backend/dp-core/src/main/java/com/solace/sm/dp/apply/placement/PlacementRepository.java` | Reads and writes the registry. |
| `…/dp/apply/placement/PlacementRegistry.java` | Enumerate a placement set for an instance; the production replacement for plan 005's `placementsOf`. |
| `…/dp/apply/engine/ApplyExecutor.java` | Multi-placement fan-out, ordering and per-placement terminality. |
| `backend/dp-api/src/main/java/com/solace/sm/dp/api/PlacementView.java` | What the gateway may see. No repository escapes `dp-core`. |
| `backend/arch-tests/.../PlacementBoundaryTest.java` | Three rules fencing the registry inside `dp-core` and keeping `dp-api` free of persistence and engine types — each with a planted violation. **Not** a proof about the gateway, which has no code to scan. |

---

## Task 1 — The placement registry

**Files:** Create `V007__placement.sql`, `…/placement/PlacementRepository.java`, `…/placement/PlacementIT.java`.

**Interfaces:**
- Produces: `sm_data.placement(tenant_id, instance_id, placement_id, kind, label, created_at, retired_at)`;
  `PlacementRepository.of(TenantId, InstanceId) → List<Placement>`, `find(TenantId, PlacementId)`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void aPlacementLabelObeysTheSameGrammarAsReplicaRef() { }            // BE-D19, one grammar
@Test void theNilUuidIsNotAStorablePlacementId() {                        // mirrors real_placements_only
    assertThatThrownBy(() -> insert(NIL_UUID)).hasMessageContaining("23514");
}
@Test void twoPlacementsOfOneInstanceMayNotShareALabel() { }
@Test void retirementIsATimestampAndTheRowSurvives() { }
@Test void aRetiredPlacementIsNotEnumeratedForANewApply() { }
@Test void enumerationIsTenantScopedAndBindParameterised() { }            // I5
@Test void theRuntimeRoleCannotDeleteAPlacementRow() { }                  // 42501
```

- [ ] **Step 2: Run; all fail.** **Step 3: Write the migration and the repository.** **Step 4: Run green.**
- [ ] **Step 5: Prove they fire.** Drop the grammar CHECK → its test red. Drop the label uniqueness → its test red. Grant `DELETE` → its test red.
- [ ] **Step 6: Re-record the DDL replay baseline** and commit. `feat(dp-core): the sm_data.placement registry`

⚠️ **This replaces plan 005's `placementsOf`**, which reads `SELECT DISTINCT placement_id FROM apply_request`.
That was correct as a stopgap and is wrong as a registry: it enumerates only placements that have *already had an
apply*, so a newly declared placement is invisible until something is applied to it. Delete it in Task 5 — do not
leave two enumerators.

---

## Task 2 — `apply_step_intent`'s placement-aware identity

**Files:** Create `V008__intent_identity.sql`; Modify `…/persistence/IntentRepository.java`; Create `…/persistence/IntentIdentityIT.java`.

- [ ] **Step 1: Write the failing tests. The second one is the whole point of the plan.**

```java
@Test void twoPlacementScopedStepsOfOneApplyCoexist() {
    // clause 1: both rows carry replica_ref = '@placement', different placement_id
}
@Test void twoReplicaScopedStepsWithTheSameReplicaRefInDifferentPlacementsCoexist() {
    // clause 2 — the one an SSH-Docker fleet hits, because replica_ref is the container name
}
@Test void twoStepsWithTheSameReplicaRefInTheSamePlacementStillCollide() {
    // the guarantee must NOT be weakened: uniqueness is narrowed, not removed
}
@Test void placementScopedStepsStillRequireTheAtPlacementSentinel() { }   // placement_scoped_steps intact
@Test void stopRemainsExemptFromPlacementScopedSteps() { }                // unchanged
@Test void hashOnConfigStepsIsUnchanged() { }
@Test void attemptOpenFenceAndTheTripleFkStillHold() { }
@Test void theIdempotencyKeyIsStillGloballyUnique() { }
```

- [ ] **Step 2: Run; clauses 1 and 2 fail, the rest pass** — which is itself the measurement that `BE-D23` was right.
- [ ] **Step 3: Write `V008`.** Replace `UNIQUE (tenant_id, apply_id, step, replica_ref)` with the placement-aware key **in one migration**, and re-add every dependent constraint and FK target the old key served (`UNIQUE (tenant_id, apply_id, intent_id)` and `UNIQUE (intent_id, step)` are `apply_attempt`'s FK targets — do not disturb them).

  ⚠️ **Four migration questions [`plan-review.md`](../../checklists/plan-review.md) requires answered — answered here rather than discovered in production:**
  1. **Backfill.** Existing `apply_step_intent` rows carry no `placement_id`. Derive it from the owning `apply_request.placement_id` in the same migration — including the nil-UUID sentinel for instance-wide requests — and assert zero nulls afterwards.
  2. **Lock class.** Dropping and creating a unique constraint takes `ACCESS EXCLUSIVE` and rebuilds the index. On a small journal that is milliseconds; record the measured row count the decision is made against, and if it is large, build the new index `CONCURRENTLY` **before** the constraint swap (which cannot itself be concurrent).
  3. **Order.** The new key must exist before the old one is dropped, or a concurrent insert slips through the gap.
  4. **Rollback.** Reverting means re-narrowing the key, which **fails once any multi-placement rows exist**. Say so plainly: after the first multi-placement apply this migration is one-way. That is a normal property of an identity change, and it belongs in writing rather than in a surprise.
- [ ] **Step 4: Run green — all eight.**
- [ ] **Step 5: Prove nothing was weakened — with **one** named exception, decided in advance.** Remove the narrowed key → `twoStepsWithTheSameReplicaRefInTheSamePlacement…` red. Then run the **whole** existing `dp-core` suite. ⚠️ **Exactly one class is *expected* to go red:** `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/InstanceWideIdentityLimitIT.java`, which plan 005's Results record as pinning both halves of the identity limit against the shipped DDL and as *"expected to go red when a persistence plan grows `apply_step_intent` a placement column"* — i.e. this plan. **Invert it in the same commit** so it becomes the proof the limit is lifted, rather than deleting it, and say so in the commit message. **Any other previously-green test going red is a defect, not a consequence.** Record before/after counts and this one inversion.
- [ ] **Step 6: Re-record the replay baseline** and commit. `feat(dp-core): placement-aware apply_step_intent identity — both BE-D23 clauses`

---

## Task 3 — The referential guard for the sentinel

**Files:** Create `V009__placement_referential_guard.sql`; Create `…/persistence/PlacementReferentialIT.java`.

`apply_request.placement_id` is `NOT NULL` with the nil UUID as the explicit instance-wide sentinel, and its own
comment says the referential guard arrives *"with the placements table, §8 row 13"*. This is that task.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void anApplyRequestCannotNameAPlacementThatDoesNotExist() { /* 23503 */ }
@Test void theInstanceWideSentinelIsStillAccepted() { }
@Test void theSentinelRowItselfIsNotEnumerableAsARealPlacement() { }
@Test void placementWatermarkStillRefusesTheSentinel() { }        // real_placements_only intact
@Test void aPlacementCannotBeRetiredWhileAnApplyIsInFlightOnIt() { }
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement — and this is a choice *this task* makes.** (An earlier draft deferred it to D1/D2, which are about registry identity and `replica_ref`'s meaning and answer nothing about the sentinel's referential form.) Two shapes; pick one and record it as a new decision (next free id from the merged register): **(a)** seed the nil UUID as a reserved `placement` row of `kind = 'instance-wide'`, excluded from enumeration by a predicate with its own test — simplest, and the FK is then unconditional; or **(b)** leave the sentinel unrepresented and guard the reference with a trigger (`placement_id = NIL OR EXISTS …`), which is not a declarative FK and therefore needs a stronger refusal test. **(a) is preferred**: it keeps the guarantee declarative, and `placement_watermark`'s `real_placements_only` already proves the exclusion predicate is expressible.
- [ ] **Step 4: Run green.** **Step 5: Prove they fire** — drop the FK → the first test red; enumerate the sentinel → its test red.
- [ ] **Step 6: Re-record the baseline** and commit. `feat(dp-core): close §8 row 13 — the placement referential guard and the sentinel's status`

---

## Task 4 — Multi-placement apply in the engine

**Files:** Modify `…/apply/engine/ApplyExecutor.java`; Create `…/apply/engine/MultiPlacementApplyIT.java`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void anInstanceWideApplyFansOutOverEveryLivePlacement() { }
@Test void aPlacementScopedApplyTouchesOnlyItsOwnPlacement() { }
@Test void oneFailingPlacementDoesNotSilentlyMarkTheOthersDone() { }
@Test void perPlacementTerminalityIsRecordedAndTheRequestsOutcomeIsDerivedNotGuessed() { }
@Test void theHealthGateIsEvaluatedPerPlacementNotAcrossPlacements() { }
@Test void resumeAfterACrashMidFanOutContinuesExactlyTheUnfinishedPlacements() { }
@Test void generationMonotonicityHoldsPerPlacementWatermark() { }
@Test void crossScopeSupersessionFollowsTheRecordedDecision() {            // D3
    // asserts the rule chosen in Step 0, whichever it is — never an invented one
}
@Test void stopIsStillScopeGranularAndHasNoApiSurfaceInThisPlan() { }
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement** — the fan-out is ordered, each placement's journal rows are independent, and the request's terminal state is **derived** from the per-placement outcomes by the rule D3 recorded.
- [ ] **Step 4: Run green.** **Step 5: Prove they fire.** Make a failing placement land the request `APPLIED` → its test red. Share one health gate across placements → its test red. Kill the process mid-fan-out → resume test covers it.
- [ ] **Step 6: Commit.** `feat(dp-core): multi-placement apply with per-placement terminality and resume`

---

## Task 5 — Retire the stopgap enumerator, and keep the adapters working

**Files:** Modify `…/persistence/ApplyRequestRepository.java` **and** `…/apply/admission/AdmissionService.java` — ⚠️ **there are two enumerators, not one**: `AdmissionService.placementsOf(ScopeKey)` delegates to the repository method and `ApplyExecutor` calls the **service**, so deleting only the repository method leaves a dangling delegate; Modify `…/apply/runtime/docker/DockerPlacementCatalog.java`, `…/runtime/ssh/SshDockerPlacementCatalog.java`; Modify `AbstractRuntimeAdapterContractTest`.

✅ **Both are on the current integration baseline:** Plan 005 is an ancestor of `integration/dp-remote-demo`, so locate `placementsOf` in this worktree before editing. **Historical provenance only:** the 2026-09-15 starting measurement at `2a0047e` predated the Plan 005 merge and reported no match; `plan005-runtime-completion` / `ba7db2e` was the source then inspected to write this task. It is not a current branch prerequisite or checkout instruction.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void everyAdapterAgreesWithTheRegistryAboutWhatAPlacementIs() { }
@Test void theContractKitStillPassesForBothAdaptersUnchanged() { }
@Test void replicasAndPrepareStillAgreeAndMutateNothing() { }        // the plan 005 contract clause
@Test void aPlacementInTheRegistryWithNoContainersYieldsAnEmptyReplicaList() { }
@Test void thereIsExactlyOnePlacementEnumeratorInProductionCode() {
    // a grep-count assertion is NOT acceptable here (L12): assert by type, not by text
}
```

- [ ] **Step 2: Run; fails.** **Step 3: Delete `placementsOf` and route every caller through `PlacementRegistry`.** **Step 4: Run green** — the full `dp-core` and `arch-tests` suites.
- [ ] **Step 5: Prove it fires.** Reintroduce a second enumerator → its test red.
- [ ] **Step 6: Commit.** `refactor(dp-core): one placement enumerator, both adapters unchanged`

---

## Task 6 — Fence the placement repository, and record the Agent Gateway reconciliation

⚠️ **Deliberately *not* titled "compatibility proved."** There is no gateway bytecode to scan, so no ArchUnit rule
in this repository can prove the gateway respects a boundary; claiming otherwise is exactly the `L19` pattern this
plan's own constraints cite. What this task delivers: a **fence** around the placement repository that holds today,
and a **written reconciliation** the gateway's own plan must consume. `agwUsesDpApiOnly` and `agwHoldsNoDatasource`
stay obligations on **that** plan.

**Files:** Create `backend/dp-api/src/main/java/com/solace/sm/dp/api/PlacementView.java`; Create `backend/arch-tests/src/test/java/.../PlacementBoundaryTest.java` + three planted-violation fixtures; Create `docs/design/backend/agent-gateway/RECONCILIATION-008.md`.

- [ ] **Step 1: Write the three rules and their three planted-violation fixtures.**

```java
// Scoped to the engine's own package, NOT to com.solace.sm.dp.. -- that wider package
// includes dp-api, the shared artifact the gateway imports, so the wider rule would have
// permitted precisely the leak that matters: a DP repository type surfacing in dp-api.
@ArchTest static final ArchRule onlyDpCoreReachesThePlacementRepository = noClasses()
        .that().resideOutsideOfPackage("com.solace.sm.dp.apply..")
        .should().dependOnClassesThat().haveSimpleName("PlacementRepository")
        .because("the registry is dp-core's; dp-api carries views, never repositories");

// The shared artifact must not reach into the engine either, or "typed semantic port"
// degrades into "a window onto dp-core".
@ArchTest static final ArchRule dpApiDependsOnNothingInsideDpCore = noClasses()
        .that().resideInAPackage("com.solace.sm.dp.api..")
        .should().dependOnClassesThat().resideInAnyPackage("com.solace.sm.dp.apply..",
                                                          "com.solace.sm.dp.meshbridge..");

// Five roots, matching agent-gateway/design.md §2.6's own agwHoldsNoDatasource. A lone
// javax.sql.DataSource check lets a JPA entity or a Flyway type straight through.
@ArchTest static final ArchRule dpApiCarriesNoPersistenceType = noClasses()
        .that().resideInAPackage("com.solace.sm.dp.api..")
        .should().dependOnClassesThat().resideInAnyPackage(
                "javax.sql..", "jakarta.persistence..", "org.springframework.jdbc..",
                "org.springframework.data..", "org.flywaydb..");
```

- [ ] **Step 2: Run against the fixtures; each of the three reports exactly its own violation.** **Step 3: Exclude the fixtures from the production scan.** **Step 4: Green excluded, red included — record both.**
- [ ] **Step 5: Write the reconciliation record.** A short document stating, per item, what this plan's identity change means for the Agent Gateway design — and **routing, not resolving**, anything that needs the `AGW-D*` owners:
  - `BE-D22` — §3.5's table still maps **one** `rollbackOrMarkIndeterminate`; the seam has **seven** operations (`assessRollback` + `restore`). Unreconciled.
  - `BE-D23`/this plan — the journal identity §3.5 writes against changes here. State the new shape.
  - `BE-D24` — a config location names a **writer access mode**, relevant to `materializeConfig` on the agent path. Unreconciled.
  - `AGW-D16` vs `sm_data.placement` — whatever D1 decided, written down where an `AGW-D*` owner will find it.
  - `AGW-20` — `status(replica)`'s canonical actual-state projection still does not exist; this plan does **not**
    supply it, and the `ActualStatePort` contract stays **PROVISIONAL**.

  ⚠️ **Do not edit `docs/design/backend/agent-gateway/design.md` or `decisions.md`.** That family is PROPOSED and ratifies
  **after** `BE-D*`; changing its meaning from here violates `I3`/`I4`. A reconciliation record beside it is the
  correct artifact.
- [ ] **Step 6: Commit.** `test(arch-tests): the gateway may not reach the placement registry — and the AGW reconciliation record`

---

## Task 7 — Close the plan

- [ ] **Step 1: Full gate.** `mvn -f backend/pom.xml clean verify` · `./contracts/run-checks.sh --strict` · the DDL replay harness with the baseline re-recorded and the drift reported · `python3 …/test_paths.py` · `git diff --check`.
- [ ] **Step 2: Results section** — the `BE-D23` re-measurement from the Prerequisites, before/after test counts for Task 2 step 5, every mutation with whether it fired, Docker state before and after, and every skip with its reason.
- [ ] **Step 3: Update the register.** `BE-D23` is **superseded, never edited** (`I3`): the new decision says the engine is multi-placement and names what changed. Update `console-integration.md` §12.1 and §15.3's `BE-D` id row.
- [ ] **Step 4: Independent adversarial review.** It must specifically test for: only one of `BE-D23`'s two clauses closed; a weakened constraint; a second placement enumerator; a gateway path to `sm_data`; the Agent Gateway represented as implemented or reconciled when it is neither; and an invented cross-scope supersession rule.
- [ ] **Step 5: Commit.**

---

## Scope and non-goals — stated exactly

| Not here | Why |
|---|---|
| Agent Gateway **implementation** | `AGW-2` (no `.proto`, no wire state machine) and `AGW-17`–`AGW-21` are open. This plan makes the journal *compatible*, it does not build the path |
| The canonical actual-state projection (`AGW-20`) | It is a four-part design question the `AGW-D*` family explicitly does not answer |
| `ActualStatePort` / `AgentAuditPort` durability kits | No contract to conform to until `AGW-20`/`AGW-21` close |
| A `stop` API surface | ⚠️ **The verb exists now — the surface still does not.** Ruling **OR-9** approved `instance.stop` as catalog row 36 (⛔ **an earlier revision of this row said "No verb (approval §3 item 1)"**, and §3 item 1 now says the opposite). Stop stays scope-granular in the engine and **absent from HTTP in this plan**. ✅ **Plan 007 Task 6b owns it**, assigned by ruling **OR-18** — ⚠️ **an earlier revision of this row recorded that *no* plan owned the endpoint, because plan 007's non-goals declined it while this table pointed there. OR-18 closed that gap** |
| Multi-tenancy | Open items 32/33/35 |
| `placement_watermark`'s replacement | It is correct; this plan adds the registry beside it, and the nil-UUID refusal stays |
| Kubernetes placements | `SM-D25`'s writer model is open item 4 |

### Fable F-3 Results (2026-10-09, fix round `x/fable-fix`) — a pending schedule holds a placement against retirement

`retirePlacement` now refuses `409` (`PlacementSchedulePendingException`, a fixed detail naming no schedule) while a `PENDING`
`apply_schedule` names the placement, checked under the same `apply_scope` and instance locks as the in-flight check on the retire side; on the schedule-creation side the placement is read outside the transaction and re-checked after `lockInstanceScopeAndCheckActive` inside the insert transaction (Fable FF-1, `ApplyScheduleServiceImpl.schedule`), so a retire that commits in between refuses the schedule `NOT_FOUND`
(`PlacementRows.scheduleReservesPlacement`); a schedule on a different placement does not hold it, and after the schedule is cancelled the
retire succeeds. **Recorded per `OR-131` Q2 by analogy (its undeclare refusal, applied to one placement) - owner to confirm.** Tests
(written with the change, then run): `InventoryPlacementOperationsTest` (refused, nothing retired, no record), `RetirePlacementIT` (a PENDING
schedule refuses and nothing changes, cancel then succeeds; another placement's schedule does not hold it), `PlacementControllerTest`
(`409 CONFLICT` with its own detail). `console.yaml` `retirePlacement`, `ENDPOINT-INVENTORY.md` and the runbook "Retire one placement" say so.

